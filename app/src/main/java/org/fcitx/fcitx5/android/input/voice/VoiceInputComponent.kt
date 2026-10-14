/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.voice.doubao.DoubaoAsrSession
import org.fcitx.fcitx5.android.input.voice.doubao.DoubaoIdentity
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * State of a voice input session, broadcast to the keyboard for visual feedback.
 */
enum class VoiceInputState {
    Idle,

    /** Microphone is open, audio is being streamed and recognized. */
    Listening,

    /** Recording stopped, waiting for the final transcript. */
    Recognizing
}

/**
 * Voice input backed by the Doubao IME cloud ASR protocol (see
 * [DoubaoAsrSession]).
 *
 * Two ways to use the microphone key:
 * - tap to start recording, tap again to stop and commit
 * - long press to record while held, release to stop and commit
 *
 * Unlike the system `SpeechRecognizer` engine, the session is not ended by
 * silence: the user may pause mid-sentence for as long as they like.
 */
class VoiceInputComponent :
    UniqueComponent<VoiceInputComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val service by manager.inputMethodService()
    private val broadcaster: InputBroadcaster by manager.must()

    /** Which gesture started the current session. */
    private enum class Mode { Toggle, PushToTalk }

    private var mode = Mode.Toggle

    private val stopRequested = AtomicBoolean(false)
    private val discardResult = AtomicBoolean(false)

    private var session: DoubaoAsrSession? = null
    private var worker: Thread? = null
    private var watchdog: Job? = null

    private var state = VoiceInputState.Idle
        set(value) {
            if (field == value) return
            field = value
            broadcaster.onVoiceInputStateUpdate(value)
        }

    private fun onMainThread(block: () -> Unit) {
        ContextCompat.getMainExecutor(service).execute(block)
    }

    private fun toast(@StringRes textRes: Int) = onMainThread {
        Toast.makeText(service, textRes, Toast.LENGTH_SHORT).show()
    }

    private val sessionListener = object : DoubaoAsrSession.Listener {
        override fun onSessionStarted() = Unit

        override fun onPartial(text: String) {
            onMainThread { broadcaster.onVoiceInputPartialText(text) }
        }

        override fun onFailed(message: String) {
            Timber.w("VoiceInputComponent: %s", message)
        }
    }

    /**
     * Tap: start recording, or stop and commit when already recording.
     */
    fun toggle() = onMainThread {
        when (state) {
            VoiceInputState.Idle -> start(Mode.Toggle)
            VoiceInputState.Listening -> if (mode == Mode.Toggle) requestStop()
            // a tap while awaiting the result changes nothing
            VoiceInputState.Recognizing -> Unit
        }
    }

    /**
     * Long press: record while the key is held.
     */
    fun pushToTalkStart() = onMainThread {
        if (state == VoiceInputState.Idle) start(Mode.PushToTalk)
    }

    /**
     * Release after a long press: stop recording and commit.
     */
    fun pushToTalkStop() = onMainThread {
        if (state == VoiceInputState.Listening && mode == Mode.PushToTalk) requestStop()
    }

    private fun start(mode: Mode) {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission()
            return
        }
        this.mode = mode
        stopRequested.set(false)
        discardResult.set(false)
        // show the recording indicator right away; the session connects in the background
        state = VoiceInputState.Listening
        worker = Thread(::runSession, "voice-input").apply { start() }
    }

    private fun requestStop() {
        if (!stopRequested.compareAndSet(false, true)) return
        state = VoiceInputState.Recognizing
        armWatchdog()
    }

    private fun runSession() {
        var asr: DoubaoAsrSession? = null
        var capture: Thread? = null
        try {
            val identity = DoubaoIdentity.ensure(service)
            val created = DoubaoAsrSession(identity, sessionListener)
            asr = created
            session = created
            created.connect()
            if (discardResult.get()) return

            val recorder = openRecorder()
            try {
                recorder.startRecording()
            } catch (t: Throwable) {
                finishCapture(recorder)
                throw t
            }
            capture = Thread({ captureAudio(recorder, created) }, "voice-input-capture")
                .also { it.start() }

            val text = created.awaitFinal()
            capture.join(CAPTURE_JOIN_MS)
            if (discardResult.get()) return
            if (text.isEmpty()) {
                toast(R.string.voice_input_no_speech)
                return
            }
            commit(text)
        } catch (t: Throwable) {
            Timber.w(t, "VoiceInputComponent: session failed")
            if (!discardResult.get()) {
                toast(
                    if (t is DoubaoAsrSession.AsrException) R.string.voice_input_error
                    else R.string.voice_input_network_error
                )
            }
        } finally {
            // make the capture loop exit, then let it release the recorder
            stopRequested.set(true)
            runCatching { capture?.join(CAPTURE_JOIN_MS) }
            runCatching { asr?.close() }
            session = null
            onMainThread(::finishSession)
        }
    }

    private fun captureAudio(recorder: AudioRecord, session: DoubaoAsrSession) {
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            while (!stopRequested.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                session.sendAudio(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
            }
        } catch (t: Throwable) {
            Timber.w(t, "VoiceInputComponent: audio capture failed")
        } finally {
            finishCapture(recorder)
            session.requestFinish()
        }
    }

    private fun finishCapture(recorder: AudioRecord) {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }

    private fun openRecorder(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 4)
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val recorder = runCatching {
                AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            runCatching { recorder.release() }
        }
        error("no usable microphone")
    }

    private fun commit(text: String) {
        onMainThread {
            service.postFcitxJob {
                reset()
                service.lifecycleScope.launch { service.commitText(text) }
            }
        }
    }

    private fun requestPermission() {
        Timber.w("VoiceInputComponent: RECORD_AUDIO not granted")
        toast(R.string.voice_input_need_permission)
        service.startActivity(
            Intent(service, VoiceInputPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Safety net for sessions that never report back.
     */
    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = service.lifecycleScope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            if (state == VoiceInputState.Recognizing) {
                Timber.w("VoiceInputComponent: watchdog fired, resetting stale session")
                stopRequested.set(true)
                runCatching { session?.cancel() }
                finishSession()
            }
        }
    }

    private fun finishSession() {
        watchdog?.cancel()
        watchdog = null
        broadcaster.onVoiceInputPartialText("")
        state = VoiceInputState.Idle
    }

    /**
     * Cancel an in-progress session without committing anything, e.g. when the
     * keyboard is dismissed while recording.
     */
    fun cancel() = onMainThread {
        if (state == VoiceInputState.Idle) return@onMainThread
        discardResult.set(true)
        stopRequested.set(true)
        runCatching { session?.cancel() }
        finishSession()
    }

    /**
     * Called when the owning [InputView] is detached.
     */
    fun destroy() {
        discardResult.set(true)
        stopRequested.set(true)
        runCatching { session?.cancel() }
        watchdog?.cancel()
        watchdog = null
    }

    private companion object {
        const val SAMPLE_RATE = 16000

        /** 100 ms of 16 kHz mono signed 16-bit PCM. */
        const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10

        const val WATCHDOG_TIMEOUT_MS = 20_000L
        const val CAPTURE_JOIN_MS = 2_000L
    }
}
