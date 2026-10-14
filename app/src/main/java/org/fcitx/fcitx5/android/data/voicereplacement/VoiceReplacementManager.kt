/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voicereplacement

import android.content.Context
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext

/**
 * Persistence for the voice input replacement dictionary.
 *
 * The list is tiny, so it lives as a JSON array in preferences instead of
 * going through the database. [replacer] compiles a fresh snapshot, which is
 * what the voice input component reads once per session, so edits made in the
 * settings screen take effect from the next recording on.
 */
object VoiceReplacementManager {

    private const val PREFS_NAME = "voice_replacement"
    private const val KEY_RULES = "rules"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<VoiceReplacement> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<VoiceReplacement>>(raw) }
            .getOrDefault(emptyList())
    }

    fun save(rules: List<VoiceReplacement>) {
        prefs.edit().putString(KEY_RULES, json.encodeToString(rules)).apply()
    }

    fun replacer(): VoiceTextReplacer = VoiceTextReplacer.of(list())
}
