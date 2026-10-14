/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import kotlin.math.roundToInt

/**
 * Persistent status pill shown while a voice input session is active, plus the
 * live (interim) transcript.
 *
 * The pill is laid out at the top edge of the keyboard and grows *downwards*
 * over the letter keys — during dictation those are irrelevant — while
 * [setAvailableHeight] keeps it clear of the bottom microphone row.
 */
class VoiceInputIndicatorUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val verticalPadding = ctx.dp(6)

    private val title = textView {
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        isSingleLine = true
    }

    private val partial = textView {
        setTextColor(theme.keyTextColor)
        textSize = 16f
        // Wrap onto several lines instead of truncating. The transcript is
        // cumulative, so when it overflows we ellipsize at the *start* to keep
        // the most recent words visible. The line count is derived from the
        // space available above the microphone key, see [setAvailableHeight].
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        maxWidth = ctx.dp(320)
        visibility = View.GONE
    }

    /**
     * Height of the pill when it only shows its title line. The view is
     * positioned at the top of the keyboard, so shift it up by this much to
     * keep the collapsed pill exactly where it has always been: just above the
     * keyboard.
     */
    private val collapsedOffsetPx: Int by lazy {
        val metrics = title.paint.fontMetrics
        (metrics.descent - metrics.ascent).roundToInt() + verticalPadding * 2
    }

    override val root: View = verticalLayout {
        background = GradientDrawable().apply {
            setColor(theme.barColor)
            cornerRadius = ctx.dp(14).toFloat()
        }
        setPadding(ctx.dp(12), verticalPadding, ctx.dp(12), verticalPadding)
        elevation = ctx.dp(4).toFloat()
        translationY = -collapsedOffsetPx.toFloat()
        add(title, lParams())
        add(partial, lParams())
        visibility = View.GONE
    }

    /**
     * Fits as many preview lines as possible between the top of the keyboard
     * and the top of its bottom (microphone) row, so the pill never covers the
     * record key.
     *
     * The pill is drawn [collapsedOffsetPx] above the keyboard's top edge and
     * its height is `collapsedOffset + lines * lineHeight`, so the offset
     * cancels out and the line count is simply
     * `availableHeight / lineHeight`.
     */
    fun setAvailableHeight(availableHeightPx: Int) {
        val lineHeight = if (partial.lineHeight > 0) {
            partial.lineHeight
        } else {
            val metrics = partial.paint.fontMetrics
            (metrics.descent - metrics.ascent).roundToInt()
        }
        val usable = availableHeightPx - ctx.dp(BOTTOM_SLACK_DP)
        val lines = if (lineHeight > 0) usable / lineHeight else 1
        partial.maxLines = lines.coerceAtLeast(1)
    }

    fun showListening(elapsedSeconds: Long) {
        title.text = ctx.getString(R.string.voice_input_recording, formatElapsed(elapsedSeconds))
        root.visibility = View.VISIBLE
    }

    fun showRecognizing() {
        title.text = ctx.getString(R.string.voice_input_recognizing)
        root.visibility = View.VISIBLE
    }

    fun updatePartial(text: String) {
        partial.text = text
        partial.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
        updatePartial("")
    }

    private fun formatElapsed(seconds: Long): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return "%02d:%02d".format(minutes, remaining)
    }

    companion object {
        /** Small gap kept between the last preview line and the record key. */
        private const val BOTTOM_SLACK_DP = 6
    }
}
