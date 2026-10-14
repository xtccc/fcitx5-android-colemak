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

/**
 * Persistent status pill shown above the keyboard while a voice input session
 * is active, plus the live (interim) transcript.
 */
class VoiceInputIndicatorUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val title = textView {
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        isSingleLine = true
    }

    private val partial = textView {
        setTextColor(theme.keyTextColor)
        textSize = 16f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = ctx.dp(280)
        visibility = View.GONE
    }

    override val root: View = verticalLayout {
        background = GradientDrawable().apply {
            setColor(theme.barColor)
            cornerRadius = ctx.dp(14).toFloat()
        }
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        add(title, lParams())
        add(partial, lParams())
        visibility = View.GONE
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
}
