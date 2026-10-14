/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler

/**
 * Owns the [VoiceInputIndicatorUi] pill: keeps it visible for as long as a
 * session is active and refreshes the elapsed time once per second.
 */
class VoiceInputIndicatorComponent :
    UniqueComponent<VoiceInputIndicatorComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()

    val ui by lazy { VoiceInputIndicatorUi(context, theme) }

    private var ticker: Job? = null
    private var startedAt = 0L
    private var currentState = VoiceInputState.Idle

    override fun onVoiceInputStateUpdate(state: VoiceInputState) {
        currentState = state
        when (state) {
            VoiceInputState.Idle -> {
                stopTicker()
                ui.hide()
            }

            VoiceInputState.Listening -> {
                startedAt = SystemClock.elapsedRealtime()
                ui.showListening(0)
                startTicker()
            }

            VoiceInputState.Recognizing -> {
                stopTicker()
                ui.showRecognizing()
            }
        }
    }

    override fun onVoiceInputPartialText(text: String) {
        ui.updatePartial(text)
    }

    private fun startTicker() {
        stopTicker()
        ticker = service.lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                if (currentState != VoiceInputState.Listening) break
                ui.showListening((SystemClock.elapsedRealtime() - startedAt) / 1000)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}
