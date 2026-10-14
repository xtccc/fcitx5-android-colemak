/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.doubao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Streaming speech recognition over the Doubao IME ("豆包输入法") ASR protocol.
 *
 * This is an unofficial, reverse-engineered protocol: the client presents
 * itself as the Doubao IME app and authenticates with an anonymous device id
 * (see [DoubaoIdentity]). It is documented by the reference implementation in
 * `vinput-registry/resources/providers/doubaoime/streaming/entry.py`.
 *
 * Notable properties that make it a good fit for a keyboard:
 * - server-side VAD, but silence never ends the session: audio (including
 *   silence) is streamed continuously and only [requestFinish] ends it, so the
 *   user may pause as long as they like mid-sentence;
 * - interim `partial` results arrive continuously, so text can be previewed
 *   while the user is still speaking.
 */
internal class DoubaoAsrSession(
    private val identity: DoubaoIdentity.Identity,
    private val listener: Listener,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : Closeable {

    interface Listener {
        fun onSessionStarted()

        /** Cumulative display text for everything recognized so far. */
        fun onPartial(text: String)

        fun onFailed(message: String)
    }

    class AsrException(val event: String, val statusCode: Int, message: String) : Exception(message)

    private val taskId = UUID.randomUUID().toString()
    private val sequence = AtomicInteger(0)
    private val finishRequestedAt = AtomicLong(0)
    private val transcript = Transcript()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cancelled = false

    private var websocket: WebSocketClient? = null

    /** Connects and opens a recognition session. Blocking. */
    fun connect() {
        if (cancelled) return
        val query = buildString {
            append("device_id=").append(identity.deviceId)
            append("&aid=").append(AID)
            append("&iid=").append(identity.installId)
        }
        val socket = WebSocketClient(
            url = "$endpoint?$query",
            extraHeaders = mapOf(
                "proto-version" to "v2",
                "sdk-version" to "2",
                "x-custom-keepalive" to "true",
            ),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readPollMs = READ_POLL_MS,
        )
        websocket = socket
        try {
            send(socket, "StartTask")
            awaitEvent(socket, "TaskStarted")
            send(
                socket, "StartSession",
                payload = buildJsonObject {
                    putJsonObject("audio_info") {
                        put("sample_rate", SAMPLE_RATE)
                        put("channel", 1)
                        put("format", "pcm")
                    }
                    putJsonObject("extra") {
                        put("enable_punctuation", true)
                        put("enable_asr_twopass", true)
                        put("enable_asr_threepass", true)
                        put("end_smooth_window_ms", 800)
                        put("did", identity.deviceId)
                        put("app_name", APP_NAME)
                        put("app_version", APP_VERSION)
                    }
                }
            )
            awaitEvent(socket, "SessionStarted")
        } catch (t: Throwable) {
            close()
            throw t
        }
        Timber.d("DoubaoAsrSession: session started ($taskId)")
        listener.onSessionStarted()
    }

    /** Streams a chunk of 16 kHz mono signed-16-bit little-endian PCM. */
    fun sendAudio(pcm: ByteArray) {
        val socket = websocket ?: return
        if (cancelled || pcm.isEmpty()) return
        val payload = buildJsonObject {
            putJsonObject("extra") {}
            put("timestamp_ms", System.currentTimeMillis())
        }
        send(socket, "TaskRequest", payload = payload, audio = pcm, sequence = sequence.incrementAndGet())
    }

    /** Tells the server that no more audio follows. Safe to call more than once. */
    fun requestFinish() {
        val socket = websocket ?: return
        if (!finishRequestedAt.compareAndSet(0L, System.currentTimeMillis())) return
        runCatching { send(socket, "FinishSession") }
            .onFailure { Timber.w(it, "DoubaoAsrSession: failed to send FinishSession") }
    }

    /**
     * Reads messages until the server finishes the utterance, streaming
     * [Listener.onPartial] updates meanwhile. Returns the final text.
     */
    fun awaitFinal(): String {
        val socket = websocket ?: error("session is not connected")
        val sessionDeadline = System.currentTimeMillis() + MAX_SESSION_MS
        while (true) {
            if (cancelled) return transcript.display

            val message = try {
                socket.readMessage()
            } catch (closed: WebSocketClient.ClosedException) {
                // The peer hung up; whatever we have is the best we can do.
                Timber.d("DoubaoAsrSession: connection closed (${closed.message})")
                return transcript.display
            } catch (io: IOException) {
                Timber.d(io, "DoubaoAsrSession: connection failed")
                return transcript.display
            } ?: run {
                val now = System.currentTimeMillis()
                val finishAt = finishRequestedAt.get()
                if (finishAt > 0 && now - finishAt > FINAL_WAIT_MS) {
                    Timber.w("DoubaoAsrSession: timed out waiting for the final result")
                    return transcript.display
                }
                if (now > sessionDeadline) {
                    Timber.w("DoubaoAsrSession: session exceeded its maximum duration")
                    return transcript.display
                }
                continue
            }

            val response = parseResponse(message)
            if (response.taskId.isNotEmpty() && response.taskId != taskId) continue

            when (response.event) {
                "Ping" -> {
                    send(socket, "Pong")
                    continue
                }

                "Pong" -> continue

                "SessionFailed", "TaskFailed" -> throw AsrException(
                    response.event, response.statusCode,
                    "${response.event}: ${response.statusCode} ${response.statusText}".trim()
                )
            }

            applyUpdates(response.payload)?.let { listener.onPartial(it) }

            if (response.event == "SessionFinished" || response.event == "TaskFinished") {
                if (response.event != "TaskFinished") {
                    runCatching { send(socket, "FinishTask") }
                }
                return transcript.display
            }
        }
    }

    /** Aborts the session without waiting for a result. */
    fun cancel() {
        cancelled = true
        close()
    }

    override fun close() {
        val socket = websocket
        websocket = null
        runCatching { socket?.close() }
    }

    private fun awaitEvent(socket: WebSocketClient, expected: String) {
        val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw WebSocketClient.ClosedException("timed out waiting for $expected")
            val message = socket.readMessage() ?: continue
            val response = parseResponse(message)
            if (response.taskId.isNotEmpty() && response.taskId != taskId) continue
            when (response.event) {
                "Ping" -> {
                    send(socket, "Pong")
                    continue
                }

                "Pong" -> continue

                "SessionFailed", "TaskFailed" -> throw AsrException(
                    response.event, response.statusCode,
                    "${response.event}: ${response.statusCode} ${response.statusText}".trim()
                )

                expected -> return
            }
        }
    }

    private fun send(
        socket: WebSocketClient,
        event: String,
        payload: JsonObject? = null,
        audio: ByteArray = EMPTY,
        sequence: Int = 0,
    ) {
        val writer = Proto.Writer(1024)
        if (event != "TaskRequest") writer.string(FIELD_APP_KEY, APP_KEY)
        writer.string(FIELD_NAMESPACE, NAMESPACE)
        writer.string(FIELD_EVENT, event)
        payload?.let { writer.string(FIELD_PAYLOAD, it.toString()) }
        writer.string(FIELD_TASK_ID, taskId)
        writer.bytes(FIELD_AUDIO, audio)
        writer.int(FIELD_SEQUENCE, sequence)
        socket.sendBinary(writer.toByteArray())
    }

    private class Response(
        val taskId: String,
        val event: String,
        val statusCode: Int,
        val statusText: String,
        val payload: String,
    )

    private fun parseResponse(data: ByteArray): Response {
        val fields = Proto.parse(data)
        return Response(
            fields.string(RESPONSE_TASK_ID),
            fields.string(RESPONSE_EVENT),
            fields.int(RESPONSE_STATUS_CODE),
            fields.string(RESPONSE_STATUS_TEXT),
            fields.string(RESPONSE_PAYLOAD),
        )
    }

    /**
     * Applies one recognition update and returns the new cumulative text.
     */
    private fun applyUpdates(payload: String): String? {
        if (payload.isEmpty()) return null
        val body = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val results = runCatching { body["results"]?.jsonArray }.getOrNull() ?: return null
        val first = results.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val text = runCatching { first["text"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        val segmentFinished = runCatching {
            first["is_vad_finished"]?.jsonPrimitive?.content?.toBoolean()
        }.getOrNull() ?: false
        return transcript.apply(text, segmentFinished)
    }

    /**
     * Accumulates confirmed segments while replacing hypotheses within the
     * segment currently being decoded.
     */
    private class Transcript {
        private var committed = ""
        private var current = ""
        private var finished = false

        var display = ""
            private set

        fun apply(text: String, segmentFinished: Boolean): String? {
            if ((text.isEmpty() && !segmentFinished) ||
                (segmentFinished && finished && text == current)
            ) {
                return null
            }
            display = committed + text
            current = text
            finished = segmentFinished
            if (segmentFinished) committed = display
            return display
        }
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws"
        const val APP_KEY = "OrnqKvSSrs"
        const val AID = "401734"
        const val NAMESPACE = "ASR"
        const val APP_NAME = "oime"
        const val APP_VERSION = "1.5.1"
        const val SAMPLE_RATE = 16000

        const val FIELD_APP_KEY = 2
        const val FIELD_NAMESPACE = 3
        const val FIELD_EVENT = 5
        const val FIELD_PAYLOAD = 6
        const val FIELD_AUDIO = 7
        const val FIELD_TASK_ID = 8
        const val FIELD_SEQUENCE = 9

        const val RESPONSE_TASK_ID = 1
        const val RESPONSE_EVENT = 4
        const val RESPONSE_STATUS_CODE = 5
        const val RESPONSE_STATUS_TEXT = 6
        const val RESPONSE_PAYLOAD = 7

        const val CONNECT_TIMEOUT_MS = 15_000
        const val RESPONSE_TIMEOUT_MS = 12_000
        const val READ_POLL_MS = 1_000
        const val FINAL_WAIT_MS = 12_000L
        const val MAX_SESSION_MS = 10 * 60 * 1000L

        val EMPTY = ByteArray(0)
    }
}
