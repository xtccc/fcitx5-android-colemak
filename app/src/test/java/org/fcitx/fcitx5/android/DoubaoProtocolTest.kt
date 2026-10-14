/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.voice.doubao.Proto
import org.fcitx.fcitx5.android.input.voice.doubao.TtEncrypt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-exact checks against the reference implementation
 * (`vinput-registry/resources/providers/doubaoime/streaming/entry.py`).
 *
 * The expected values were produced by running the Python reference directly,
 * so these tests catch any drift in the crypto or the protobuf wire format.
 */
class DoubaoProtocolTest {

    @Test
    fun ttEncryptMatchesReference() {
        val salt = ByteArray(32) { it.toByte() }
        val actual = TtEncrypt.encryptGzipped("hello world".toByteArray(Charsets.UTF_8), salt)
        assertEquals(TT_ENCRYPT_REFERENCE, actual.toHex())
    }

    @Test
    fun protobufRequestMatchesReference() {
        val writer = Proto.Writer()
        writer.string(2, "OrnqKvSSrs")
        writer.string(3, "ASR")
        writer.string(5, "StartSession")
        writer.string(6, """{"a":1}""")
        writer.string(8, "tid")
        writer.bytes(7, byteArrayOf(1, 2, 3))
        writer.int(9, 7)
        assertEquals(PROTO_REQUEST_REFERENCE, writer.toByteArray().toHex())
    }

    @Test
    fun protobufResponseIsParsed() {
        val fields = Proto.parse(RESPONSE_REFERENCE.hexToBytes())
        assertEquals("tid", fields.string(1))
        assertEquals("SessionFailed", fields.string(4))
        assertEquals(403, fields.int(5))
        assertEquals("forbidden", fields.string(6))
        assertEquals("""{"x":2}""", fields.string(7))
        assertEquals(12, fields.int(9))
    }

    @Test
    fun emptyFieldsAreOmitted() {
        val writer = Proto.Writer()
        writer.string(2, "")
        writer.bytes(7, ByteArray(0))
        writer.int(9, 0)
        assertEquals("", writer.toByteArray().toHex())
    }

    private companion object {
        const val TT_ENCRYPT_REFERENCE =
            "746305100000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                    "6d7de0388e94a8f2f6016c9bc8dd9f05bf6c3d6eee67c7fdc7f7bef7d6ecd6a416a2ea8c29" +
                    "4b76bbc0e8d754da3fa405fe4f0ca06fe5c1ea5565937fdb49e5b5f298e5fc8ed624aac26f" +
                    "a48f173dc1c6"

        const val PROTO_REQUEST_REFERENCE =
            "120a4f726e714b76535372731a034153522a0c537461727453657373696f6e32077b2261223a31" +
                    "7d42037469643a030102034807"

        const val RESPONSE_REFERENCE =
            "0a03746964220d53657373696f6e4661696c65642893033209666f7262696464656e3a077b2278" +
                    "223a327d480c"
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
