/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.doubao

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ByteDance's `ttEncrypt` request-body obfuscation, used by the anonymous
 * device registration endpoint.
 *
 * Layout: `magic(6) || salt(32) || AES-128-CBC( SHA-512(gzip(body)) || gzip(body) )`
 * with PKCS#7 padding, where the AES key/IV are derived from
 * `SHA-512( SHA-512(salt) || CONSTANT )`.
 */
internal object TtEncrypt {

    private val MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)

    private val CONSTANT = hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
                "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    private val random = SecureRandom()

    fun encrypt(plain: ByteArray): ByteArray {
        val salt = ByteArray(32).also(random::nextBytes)
        return encryptGzipped(gzip(plain), salt)
    }

    /** [gzipped] must already be a gzip stream; exposed for byte-exact testing. */
    internal fun encryptGzipped(gzipped: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == 32) { "salt must be exactly 32 bytes" }
        val digest = sha512(sha512(salt) + CONSTANT)
        val key = digest.copyOfRange(0, 16)
        val iv = digest.copyOfRange(16, 32)

        val body = sha512(gzipped) + gzipped
        val padding = 16 - body.size % 16
        val padded = body + ByteArray(padding) { padding.toByte() }

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        val out = ByteArrayOutputStream(MAGIC.size + salt.size + padded.size + 16)
        out.write(MAGIC)
        out.write(salt)
        out.write(cipher.doFinal(padded))
        return out.toByteArray()
    }

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
