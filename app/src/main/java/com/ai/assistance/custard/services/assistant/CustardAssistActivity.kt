package com.ai.assistance.custard.services.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.ai.assistance.custard.services.FloatingChatService
import com.ai.assistance.custard.ui.floating.FloatingMode
import com.ai.assistance.custard.util.AppLogger

/**
 * Receives system ASSIST intents and forwards them to Custard's voice assistant entry.
 */
class CustardAssistActivity : Activity() {

    companion object {
        private const val TAG = "CustardAssistActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchAssistEntry()
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchAssistEntry()
        finish()
    }

    private fun launchAssistEntry() {
        AppLogger.d(TAG, "Assist intent received, action=${intent?.action}")
        try {
            val floatingIntent = Intent(this, FloatingChatService::class.java).apply {
                putExtra("INITIAL_MODE", FloatingMode.FULLSCREEN.name)
                putExtra(FloatingChatService.EXTRA_AUTO_ENTER_VOICE_CHAT, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(floatingIntent)
            } else {
                startService(floatingIntent)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch assist entry", e)
        }
    }
}
