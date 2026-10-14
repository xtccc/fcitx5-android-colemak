/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voicereplacement

import kotlinx.serialization.Serializable

/**
 * One entry of the voice input replacement dictionary.
 *
 * @param from spoken phrase as produced by the cloud ASR
 * @param to text to write instead ([to] may be empty to drop filler words)
 */
@Serializable
data class VoiceReplacement(
    val from: String,
    val to: String,
    val enabled: Boolean = true,
) {

    /** Round-trippable through [parse], used as the editor's text form. */
    fun serialize(): String = "$from=$to"

    companion object {

        private val SEPARATORS = charArrayOf('=', '＝')

        /**
         * Parses `spoken=replacement`. Returns `null` when the input has no
         * separator or an empty left-hand side.
         */
        fun parse(text: String): VoiceReplacement? {
            val separator = text.indexOfFirst { it in SEPARATORS }
            if (separator <= 0) return null
            val from = text.substring(0, separator).trim()
            if (from.isEmpty()) return null
            return VoiceReplacement(from, text.substring(separator + 1).trim())
        }
    }
}
