package io.github.lordofpolls.shellwave.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import io.github.lordofpolls.shellwave.ssh.SessionManager
import javax.inject.Inject

/**
 * Handles inline quick actions (`Yes` / `No`) and Direct Reply (`RemoteInput`) from command
 * output notifications, routing replies back to the originating live SSH session without opening
 * the app.
 */
@AndroidEntryPoint
class SessionReplyReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId < 0L) return

        when (intent.action) {
            ACTION_NOTIFICATION_QUICK_REPLY -> {
                val remoteReply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_DIRECT_REPLY)
                    ?.toString()
                val fixedReply = intent.getStringExtra(EXTRA_REPLY_TEXT)
                val replyText = remoteReply ?: fixedReply ?: return

                sessionManager.sendNotificationReply(sessionId, replyText)
            }

            ACTION_NOTIFICATION_DISMISSED -> {
                val turnEpoch = intent.getLongExtra(EXTRA_TURN_EPOCH, -1L)
                sessionManager.acknowledgeSessionAlert(sessionId, turnEpoch)
            }
        }
    }

    companion object {
        const val ACTION_NOTIFICATION_QUICK_REPLY =
            "io.github.oreillymonitor.shellsurge.action.NOTIFICATION_QUICK_REPLY"
        const val ACTION_NOTIFICATION_DISMISSED =
            "io.github.oreillymonitor.shellsurge.action.NOTIFICATION_DISMISSED"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_TURN_EPOCH = "turn_epoch"
        const val KEY_DIRECT_REPLY = "key_direct_reply"
    }
}
