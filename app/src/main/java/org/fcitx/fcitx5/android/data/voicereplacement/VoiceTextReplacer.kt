/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voicereplacement

/**
 * Applies the user's replacement dictionary to a transcript.
 *
 * This is a **pure, stateless** function of the whole text, and that property
 * matters: cloud ASR re-scores every sentence (two/three-pass decoding) and
 * re-sends the full text of a segment each time. Anything that remembered
 * earlier replacements and only processed newly appended text would desync as
 * soon as the server rewrote a sentence. Recomputing from scratch is always
 * consistent, and since the result is never fed back to the server the two
 * cannot influence each other.
 *
 * Matching is a single left-to-right, non-overlapping pass; at each position
 * the longest key wins, so a rule for `诸葛连弩` is never shadowed by one for
 * `诸葛`, and replaced text is never scanned again.
 */
class VoiceTextReplacer private constructor(private val rules: List<Pair<String, String>>) {

    val isEmpty: Boolean
        get() = rules.isEmpty()

    fun apply(text: String): String {
        if (rules.isEmpty() || text.isEmpty()) return text
        val output = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            var replaced = false
            for ((from, to) in rules) {
                if (text.startsWith(from, index)) {
                    output.append(to)
                    index += from.length
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                output.append(text[index])
                index++
            }
        }
        return output.toString()
    }

    companion object {

        val Empty = VoiceTextReplacer(emptyList())

        fun of(definitions: List<VoiceReplacement>): VoiceTextReplacer {
            val rules = definitions.asSequence()
                .filter { it.enabled && it.from.isNotEmpty() }
                .sortedByDescending { it.from.length }
                .map { it.from to it.to }
                .toList()
            return if (rules.isEmpty()) Empty else VoiceTextReplacer(rules)
        }
    }
}
