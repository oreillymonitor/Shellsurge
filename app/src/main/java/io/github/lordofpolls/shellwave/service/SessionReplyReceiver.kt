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
        if (intent.action != ACTION_NOTIFICATION_QUICK_REPLY) return
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId < 0L) return

        val remoteReply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_DIRECT_REPLY)
            ?.toString()
        val fixedReply = intent.getStringExtra(EXTRA_REPLY_TEXT)
        val replyText = remoteReply ?: fixedReply ?: return

        sessionManager.sendNotificationReply(sessionId, replyText)
    }

    companion object {
        const val ACTION_NOTIFICATION_QUICK_REPLY =
            "io.github.oreillymonitor.shellsurge.action.NOTIFICATION_QUICK_REPLY"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val KEY_DIRECT_REPLY = "key_direct_reply"
    }
}
