/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.doubao

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Anonymous device identity for the Doubao IME ASR endpoint.
 *
 * The official client registers a device once (no account, no login) against
 * ByteDance's device registration endpoint and then authenticates the speech
 * WebSocket purely by that `device_id` / `install_id` pair. We do the same and
 * cache the pair, so registration happens exactly once per install.
 */
internal object DoubaoIdentity {

    data class Identity(val deviceId: String, val installId: String)

    private const val PREFS_NAME = "voice_input_doubao"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_INSTALL_ID = "install_id"

    private const val REGISTER_ENDPOINT = "https://log.snssdk.com/service/2/device_register/"

    /** Doubao IME (`com.bytedance.android.doubaoime`) app id. */
    private const val AID = 401734
    private const val APP_NAME = "oime"
    private const val APP_VERSION = "1.5.1"
    private const val VERSION_CODE = 100501006

    private val json = Json { ignoreUnknownKeys = true }

    private val random = SecureRandom()

    private val lock = Any()

    fun ensure(context: Context): Identity = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = Identity(
            prefs.getString(KEY_DEVICE_ID, null).orEmpty(),
            prefs.getString(KEY_INSTALL_ID, null).orEmpty()
        )
        if (cached.deviceId.isNotEmpty() && cached.installId.isNotEmpty()) return cached

        val identity = register()
        prefs.edit()
            .putString(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_INSTALL_ID, identity.installId)
            .apply()
        Timber.d("DoubaoIdentity: registered ${identity.deviceId}")
        return identity
    }

    private fun register(): Identity {
        val requestId = UUID.randomUUID().toString()
        val installation = Installation(
            openudid = randomHex(10),
            clientudid = UUID.randomUUID().toString(),
            cdid = UUID.randomUUID().toString(),
        )
        val body = buildJsonObject {
            put("magic_tag", "ss_app_log")
            put("_gen_time", System.currentTimeMillis())
            putJsonObject("header") {
                put("aid", AID)
                put("app_name", APP_NAME)
                put("channel", "official")
                put("package", "com.bytedance.android.doubaoime")
                put("app_version", APP_VERSION)
                put("app_version_minor", "6")
                put("version_code", VERSION_CODE)
                put("update_version_code", VERSION_CODE)
                put("manifest_version_code", VERSION_CODE)
                put("not_request_sender", 1)
                putJsonObject("custom") {}
                put("device_platform", "android")
                put("os", "Android")
                put("os_version", Build.VERSION.RELEASE ?: "unknown")
                put("os_api", Build.VERSION.SDK_INT)
                put("device_model", Build.MODEL ?: "unknown")
                put("device_brand", Build.BRAND ?: "unknown")
                put("device_manufacturer", Build.MANUFACTURER ?: "unknown")
                put("cpu_abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
                put("language", "zh")
                put("timezone", 8)
                put("tz_name", "Asia/Shanghai")
                put("tz_offset", 28800)
                put("access", "wifi")
                put("openudid", installation.openudid)
                put("clientudid", installation.clientudid)
                put("cdid", installation.cdid)
                put("git_hash", "b416399")
                put("sdk_version_code", 4030263)
                put("sdk_target_version", 30)
                put("sdk_version", "4.3.2-rc.13")
                put("sdk_flavor", "cnInner")
                put("guest_mode", 0)
                put("req_id", requestId)
                put("apk_first_install_time", installation.firstInstallTime)
                put("is_system_app", 0)
            }
        }

        val payload = TtEncrypt.encrypt(body.toString().toByteArray(Charsets.UTF_8))
        val url = URL(
            REGISTER_ENDPOINT +
                    "?req_id=" + URLEncoder.encode(requestId, "UTF-8") +
                    "&tt_data=a"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(20).toInt()
            setRequestProperty("Content-Type", "application/octet-stream;tt-data=a")
            setFixedLengthStreamingMode(payload.size)
        }
        val response = connection.use { conn ->
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("device registration failed with HTTP $code: ${text.take(200)}")
            text
        }

        val root = json.parseToJsonElement(response).jsonObject
        val deviceId = root.stringOrNumber("device_id_str") ?: root.stringOrNumber("device_id")
        val installId = root.stringOrNumber("install_id_str") ?: root.stringOrNumber("install_id")
        if (deviceId.isNullOrEmpty() || installId.isNullOrEmpty() || deviceId == "0" || installId == "0") {
            error("device registration returned no usable identity")
        }
        return Identity(deviceId, installId)
    }

    private fun kotlinx.serialization.json.JsonObject.stringOrNumber(key: String): String? {
        val element = this[key] ?: return null
        return runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            ?: runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    private class Installation(
        val openudid: String,
        val clientudid: String,
        val cdid: String,
        val firstInstallTime: Long = System.currentTimeMillis(),
    )

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }
}
