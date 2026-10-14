/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.voicereplacement.VoiceReplacement
import org.fcitx.fcitx5.android.data.voicereplacement.VoiceTextReplacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTextReplacerTest {

    @Test
    fun replacesPhrase() {
        val replacer = VoiceTextReplacer.of(listOf(VoiceReplacement("诸葛", "猪哥")))
        assertEquals("猪哥是个好主播", replacer.apply("诸葛是个好主播"))
    }

    @Test
    fun longestKeyWins() {
        val replacer = VoiceTextReplacer.of(
            listOf(
                VoiceReplacement("诸葛", "猪哥"),
                VoiceReplacement("诸葛连弩", "连弩"),
            )
        )
        assertEquals("连弩", replacer.apply("诸葛连弩"))
        assertEquals("猪哥", replacer.apply("诸葛"))
    }

    @Test
    fun replacedTextIsNotScannedAgain() {
        val replacer = VoiceTextReplacer.of(
            listOf(
                VoiceReplacement("a", "b"),
                VoiceReplacement("b", "c"),
            )
        )
        assertEquals("b", replacer.apply("a"))
    }

    @Test
    fun matchesAreNonOverlapping() {
        val replacer = VoiceTextReplacer.of(listOf(VoiceReplacement("aa", "b")))
        assertEquals("bb", replacer.apply("aaaa"))
        // the trailing single char does not match and is copied as-is
        assertEquals("bba", replacer.apply("aaaaa"))
    }

    @Test
    fun emptyReplacementDropsFiller() {
        val replacer = VoiceTextReplacer.of(listOf(VoiceReplacement("嗯", "")))
        assertEquals("那个就是这样的", replacer.apply("嗯那个嗯就是嗯这样的"))
    }

    @Test
    fun disabledRulesAreIgnored() {
        val replacer = VoiceTextReplacer.of(
            listOf(VoiceReplacement("诸葛", "猪哥", enabled = false))
        )
        assertEquals("诸葛", replacer.apply("诸葛"))
    }

    @Test
    fun emptyDictionaryIsIdentity() {
        assertEquals("随便什么文本", VoiceTextReplacer.Empty.apply("随便什么文本"))
        assertEquals("", VoiceTextReplacer.Empty.apply(""))
    }

    @Test
    fun replacesAcrossLatinAndCjk() {
        val replacer = VoiceTextReplacer.of(listOf(VoiceReplacement("诸葛", "猪哥")))
        assertEquals("hello 猪哥 world", replacer.apply("hello 诸葛 world"))
    }

    @Test
    fun parsesAndRoundTripsEntries() {
        val entry = VoiceReplacement.parse("诸葛=猪哥")
        assertEquals(VoiceReplacement("诸葛", "猪哥"), entry)
        assertEquals("诸葛=猪哥", entry!!.serialize())

        // empty right-hand side removes the phrase
        assertEquals(VoiceReplacement("嗯", ""), VoiceReplacement.parse("嗯="))
        assertEquals("嗯=", VoiceReplacement.parse("嗯=")!!.serialize())

        // full-width separator and surrounding spaces are tolerated
        assertEquals(VoiceReplacement("诸葛", "猪哥"), VoiceReplacement.parse(" 诸葛 ＝ 猪哥 "))

        assertNull(VoiceReplacement.parse("诸葛"))
        assertNull(VoiceReplacement.parse("=猪哥"))
    }
}
