/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Transparent activity whose only purpose is to request
 * [Manifest.permission.RECORD_AUDIO] on behalf of the IME, since a running
 * [android.inputmethodservice.InputMethodService] cannot show the permission
 * dialog itself. It finishes as soon as the user answers the dialog.
 */
class VoiceInputPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) {
            Timber.d(
                "VoiceInputPermissionActivity: RECORD_AUDIO granted=%s",
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            )
            finish()
        }
    }

    companion object {
        private const val REQUEST_CODE = 0x5601
    }

}
