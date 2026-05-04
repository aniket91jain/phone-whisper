package com.kafkasl.phonewhisper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Listens for an external broadcast intent (e.g. from Tasker) and forwards it to the
 * Accessibility Service's toggle handler — the same code path used by the floating button.
 *
 * Action: com.kafkasl.phonewhisper.action.TOGGLE_VOICE
 */
class ToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TOGGLE_VOICE) return

        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Toggle received but Phone Whisper accessibility service is not running")
            Toast.makeText(
                context,
                "Phone Whisper accessibility service is not enabled",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        service.handleExternalToggle()
    }

    companion object {
        const val ACTION_TOGGLE_VOICE = "com.kafkasl.phonewhisper.action.TOGGLE_VOICE"
        private const val TAG = "PhoneWhisperToggle"
    }
}
