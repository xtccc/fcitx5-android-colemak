/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.doubao

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal RFC 6455 WebSocket client.
 *
 * The app deliberately avoids pulling in a networking library for the single
 * feature that needs one, so this implements just what the Doubao ASR protocol
 * uses: TLS, an upgrade handshake with custom headers, client-masked binary
 * frames, and the ping/pong/close control frames.
 */
internal class WebSocketClient(
    url: String,
    extraHeaders: Map<String, String>,
    connectTimeoutMs: Int,
    readPollMs: Int,
) : Closeable {

    class ClosedException(message: String) : Exception(message)

    private val uri = URI(url)
    private val secure = uri.scheme.equals("wss", ignoreCase = true)
    private val readPollMs = readPollMs

    private val socket: Socket
    private val input: BufferedInputStream
    private val output: BufferedOutputStream
    private val sendLock = Any()
    private val random = SecureRandom()

    @Volatile
    private var closed = false

    init {
        require(uri.host != null) { "websocket url is missing a host: $url" }
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        socket = if (secure) {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).also {
                it.connect(InetSocketAddress(uri.host, port), connectTimeoutMs)
                it.soTimeout = connectTimeoutMs
                it.startHandshake()
            }
        } else {
            Socket().also {
                it.tcpNoDelay = true
                it.connect(InetSocketAddress(uri.host, port), connectTimeoutMs)
            }
        }
        socket.soTimeout = this.readPollMs
        input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        handshake(extraHeaders)
    }

    private fun handshake(extraHeaders: Map<String, String>) {
        val key = Base64.encodeToString(ByteArray(16).also(random::nextBytes), Base64.NO_WRAP)
        val defaultPort = if (secure) 443 else 80
        val host = if (uri.port == -1 || uri.port == defaultPort) uri.host else "${uri.host}:${uri.port}"

        val request = buildString {
            append("GET ").append(uri.rawPath.orEmpty().ifEmpty { "/" })
            uri.rawQuery?.let { append('?').append(it) }
            append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(request.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val statusLine = readHttpLine() ?: throw ClosedException("websocket handshake: empty response")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readHttpLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        if (!statusLine.contains(" 101")) {
            val logId = headers["x-tt-logid"]?.let { ", log_id=$it" }.orEmpty()
            throw ClosedException("websocket handshake failed: $statusLine$logId")
        }
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        if (headers["sec-websocket-accept"] != expected) {
            throw ClosedException("websocket handshake failed: invalid Sec-WebSocket-Accept")
        }
    }

    private fun readHttpLine(): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) return builder.toString().removeSuffix("\r")
            builder.append(b.toChar())
        }
    }

    fun sendBinary(payload: ByteArray) {
        synchronized(sendLock) {
            check(!closed) { "websocket is closed" }
            val header = ByteArrayOutputStream(14)
            header.write(0x82) // FIN + binary
            val mask = ByteArray(4).also(random::nextBytes)
            val length = payload.size
            when {
                length < 126 -> header.write(0x80 or length)
                length < 0x10000 -> {
                    header.write(0x80 or 126)
                    header.write((length ushr 8) and 0xFF)
                    header.write(length and 0xFF)
                }

                else -> {
                    header.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) header.write(((length.toLong() ushr shift) and 0xFF).toInt())
                }
            }
            header.write(mask)
            val masked = ByteArray(length)
            for (i in 0 until length) {
                masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    /**
     * Reads the next data message.
     *
     * @return the message payload, or `null` if no message arrived within the
     *   configured read poll interval (the caller is expected to poll so that
     *   it can observe cancellation and deadlines).
     * @throws ClosedException when the peer closed the connection
     */
    fun readMessage(): ByteArray? {
        var fragments: ByteArrayOutputStream? = null
        while (true) {
            val frame = try {
                readFrame()
            } catch (timeout: SocketTimeoutException) {
                // Only report idleness between messages; keep waiting mid-message.
                if (fragments == null) return null
                continue
            } ?: throw ClosedException("websocket closed by peer")

            when (frame.opcode) {
                OPCODE_CLOSE -> {
                    closed = true
                    throw ClosedException("websocket closed by peer")
                }

                OPCODE_PING -> {
                    sendControl(OPCODE_PONG, frame.payload)
                    continue
                }

                OPCODE_PONG -> continue

                OPCODE_BINARY, OPCODE_TEXT -> {
                    val buffer = ByteArrayOutputStream(frame.payload.size)
                    buffer.write(frame.payload)
                    if (frame.fin) return buffer.toByteArray()
                    fragments = buffer
                }

                OPCODE_CONTINUATION -> {
                    val buffer = fragments ?: continue
                    buffer.write(frame.payload)
                    if (frame.fin) return buffer.toByteArray()
                }

                else -> continue
            }
        }
    }

    private class Frame(val opcode: Int, val fin: Boolean, val payload: ByteArray)

    private fun readFrame(): Frame? {
        val first = input.read()
        if (first < 0) {
            closed = true
            return null
        }
        val second = input.read()
        if (second < 0) {
            closed = true
            return null
        }
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = readUnsigned(2)
        } else if (length == 127L) {
            length = readUnsigned(8)
        }
        require(length <= MAX_FRAME_BYTES) { "websocket frame is too large: $length" }
        val mask = if (masked) readFully(4) else null
        val payload = readFully(length.toInt())
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return Frame(opcode, fin, payload)
    }

    private fun readUnsigned(bytes: Int): Long {
        val buffer = readFully(bytes)
        var value = 0L
        for (b in buffer) value = (value shl 8) or (b.toLong() and 0xFF)
        return value
    }

    private fun readFully(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) throw EOFException("websocket connection closed unexpectedly")
            read += n
        }
        return buffer
    }

    private fun sendControl(opcode: Int, payload: ByteArray) {
        synchronized(sendLock) {
            if (closed) return
            val header = ByteArrayOutputStream(8)
            header.write(0x80 or opcode)
            val mask = ByteArray(4).also(random::nextBytes)
            header.write(0x80 or payload.size)
            header.write(mask)
            val masked = ByteArray(payload.size)
            for (i in payload.indices) {
                masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
        const val MAX_FRAME_BYTES = 4L * 1024 * 1024
    }
}
