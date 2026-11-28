package io.github.lordofpolls.shellwave.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.MainActivity
import io.github.lordofpolls.shellwave.R
import io.github.lordofpolls.shellwave.ssh.NotificationQuickAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "session_alerts"
private const val COMMAND_OUTPUT_CHANNEL_ID = "command_output"
private const val COMPLETED_TURN_CHANNEL_ID = "completed_turns"
private const val BELL_CHANNEL_ID = "bell"

// Clear of the ids SessionService (1), the terminal bell (2) and ScriptTriggerService (100+) post.
private const val NOTIFICATION_ID_BASE = 10_000

private const val RECOVERY_TIMEOUT_MS = 15_000L

private const val NOTICE_ID_BASE = 1_000_000
private const val OUTPUT_ID_BASE = 2_000_000
private const val BELL_ID_BASE = 3_000_000
private const val PEER_ONLINE_ID_BASE = 4_000_000

/**
 * Announces an unclean drop, and the reconnect that answers it, as well as interactive command /
 * output alerts and terminal bell notifications. A banner when the app is backgrounded - an Android
 * 12+ toast from a background app is dropped silently - and a toast when it isn't, where a banner
 * over the user's own terminal would be noise.
 */
@Singleton
open class SessionAlerts @Inject constructor(@ApplicationContext private val context: Context) {

    // Toast and ProcessLifecycleOwner are both main-thread-only; callers are on whatever thread
    // noticed the drop.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val alerted = ConcurrentHashMap.newKeySet<Long>()

    // open: SessionManagerTest substitutes a no-op subclass, since these post real notifications
    // through Dispatchers.Main, which has no JVM-test-friendly implementation in this codebase (no
    // Robolectric, no kotlinx-coroutines-test).
    open fun dropped(sessionId: Long, label: String) {
        if (!alerted.add(sessionId)) return
        alert(
            notificationId(sessionId),
            sessionId = sessionId,
            title = "$label disconnected",
            body = "Connection lost - reconnecting…",
            autoDismissMs = null,
        )
    }

    open fun reconnected(sessionId: Long, label: String) {
        if (!alerted.remove(sessionId)) return
        alert(
            notificationId(sessionId),
            sessionId = sessionId,
            title = "$label reconnected",
            body = "The session is live again.",
            autoDismissMs = RECOVERY_TIMEOUT_MS,
        )
    }

    // Its own notification id, or a drop or reconnect banner for the same session replaces it unseen.
    open fun notice(sessionId: Long, label: String, message: String) {
        alert(
            NOTICE_ID_BASE + notificationId(sessionId),
            sessionId = sessionId,
            title = message,
            body = label,
            autoDismissMs = RECOVERY_TIMEOUT_MS,
        )
    }

    open fun isAppForeground(): Boolean =
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)

    open fun commandOutput(
        sessionId: Long,
        title: String,
        body: String,
        isActiveTab: Boolean,
        notifyOnActiveTab: Boolean,
        isResponseNeeded: Boolean = false,
        quickActions: List<NotificationQuickAction> = emptyList(),
        turnEpoch: Long = 0L,
        subText: String? = null,
        isError: Boolean = false,
    ) {
        scope.launch {
            val appForeground = isAppForeground()
            if (appForeground && isActiveTab && !notifyOnActiveTab) return@launch
            val manager =
                context.getSystemService(NotificationManager::class.java) ?: return@launch
            val urgent = isResponseNeeded || isError
            val channelId = if (urgent) {
                ensureCommandOutputChannel(manager)
                COMMAND_OUTPUT_CHANNEL_ID
            } else {
                ensureCompletedTurnChannel(manager)
                COMPLETED_TURN_CHANNEL_ID
            }
            val id = outputNotificationId(sessionId)
            val builder =
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_stat_shellwave)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .apply { subText?.takeIf { it.isNotBlank() }?.let { setSubText(it) } }
                    .setContentIntent(openSessions(id, sessionId))
                    .setDeleteIntent(dismissPendingIntent(id * 5 + 4, sessionId, turnEpoch))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setOnlyAlertOnce(false)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(
                        if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
                    )

            val actionsToAttach = when {
                quickActions.isNotEmpty() -> quickActions.take(2)
                isResponseNeeded -> listOf(
                    NotificationQuickAction("Yes", "y"),
                    NotificationQuickAction("No", "n"),
                )
                else -> emptyList()
            }
            actionsToAttach.forEachIndexed { idx, action ->
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        action.label,
                        quickReplyPendingIntent(id * 5 + idx + 1, sessionId, action.sendText, mutable = false),
                    ).build()
                )
            }

            val remoteInput = RemoteInput.Builder(SessionReplyReceiver.KEY_DIRECT_REPLY)
                .setLabel("Send to terminal…")
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Reply",
                    quickReplyPendingIntent(id * 5 + 3, sessionId, null, mutable = true),
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )

            manager.notify(id, builder.build())
        }
    }

    open fun bell(sessionId: Long, label: String, preview: String?) {
        scope.launch {
            val manager =
                context.getSystemService(NotificationManager::class.java) ?: return@launch
            ensureBellChannel(manager)
            val id = bellNotificationId(sessionId)
            val body = preview?.takeIf { it.isNotBlank() } ?: "Terminal bell triggered."
            val notification =
                NotificationCompat.Builder(context, BELL_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_shellwave)
                    .setContentTitle("$label · Bell")
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(openSessions(id, sessionId))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            manager.notify(id, notification)
        }
    }

    open fun clearOutputAlert(sessionId: Long) {
        scope.launch {
            context.getSystemService(NotificationManager::class.java)?.run {
                cancel(outputNotificationId(sessionId))
                cancel(bellNotificationId(sessionId))
            }
        }
    }

    open fun tailscalePeerOnline(label: String, sessionId: Long?) {
        scope.launch {
            val manager =
                context.getSystemService(NotificationManager::class.java) ?: return@launch
            val title = "$label is back online"
            val body = if (sessionId != null) {
                "Tailscale peer came online — reconnecting session."
            } else {
                "Tailscale peer is reachable on your tailnet."
            }
            if (isAppForeground()) {
                Toast.makeText(context, title, Toast.LENGTH_LONG).show()
                return@launch
            }
            ensureChannel(manager)
            val id = PEER_ONLINE_ID_BASE + (label.hashCode() and 0xFFFF)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_shellwave)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openSessions(id, sessionId))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(id, notification)
        }
    }

    open fun forget(sessionId: Long) {
        alerted.remove(sessionId)
        scope.launch {
            context.getSystemService(NotificationManager::class.java)
                ?.run {
                    cancel(notificationId(sessionId))
                    cancel(NOTICE_ID_BASE + notificationId(sessionId))
                    cancel(outputNotificationId(sessionId))
                    cancel(bellNotificationId(sessionId))
                }
        }
    }

    private fun alert(
        id: Int,
        sessionId: Long,
        title: String,
        body: String,
        autoDismissMs: Long?,
    ) {
        scope.launch {
            val manager =
                context.getSystemService(NotificationManager::class.java) ?: return@launch
            if (ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            ) {
                manager.cancel(id)
                Toast.makeText(context, title, Toast.LENGTH_LONG).show()
                return@launch
            }
            ensureChannel(manager)
            manager.notify(id, build(title, body, autoDismissMs, id, sessionId))
        }
    }

    private fun build(
        title: String,
        body: String,
        autoDismissMs: Long?,
        id: Int,
        sessionId: Long,
    ) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shellwave)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openSessions(id, sessionId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { autoDismissMs?.let { setTimeoutAfter(it) } }
            .build()

    private fun quickReplyPendingIntent(
        requestCode: Int,
        sessionId: Long,
        fixedText: String?,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, SessionReplyReceiver::class.java).apply {
            action = SessionReplyReceiver.ACTION_NOTIFICATION_QUICK_REPLY
            putExtra(SessionReplyReceiver.EXTRA_SESSION_ID, sessionId)
            if (fixedText != null) {
                putExtra(SessionReplyReceiver.EXTRA_REPLY_TEXT, fixedText)
            }
        }
        val mutability = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            mutability or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun dismissPendingIntent(
        requestCode: Int,
        sessionId: Long,
        turnEpoch: Long,
    ): PendingIntent {
        val intent = Intent(context, SessionReplyReceiver::class.java).apply {
            action = SessionReplyReceiver.ACTION_NOTIFICATION_DISMISSED
            putExtra(SessionReplyReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(SessionReplyReceiver.EXTRA_TURN_EPOCH, turnEpoch)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openSessions(requestCode: Int, sessionId: Long? = null): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SESSIONS, true)
                .apply {
                    if (sessionId != null) putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, sessionId)
                }
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(CHANNEL_ID, "Session alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Alerts when a session drops and when it reconnects."
        manager.createNotificationChannel(channel)
    }

    private fun ensureCommandOutputChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(COMMAND_OUTPUT_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            COMMAND_OUTPUT_CHANNEL_ID,
            "Approvals & errors",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description =
            "High-priority alerts when a session needs approval, asks a question, or fails."
        manager.createNotificationChannel(channel)
    }

    private fun ensureCompletedTurnChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(COMPLETED_TURN_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            COMPLETED_TURN_CHANNEL_ID,
            "Completed turns",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description =
            "Quieter summaries when a command or agent turn finishes cleanly."
        manager.createNotificationChannel(channel)
    }

    private fun ensureBellChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(BELL_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            BELL_CHANNEL_ID,
            "Terminal bell",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "Alerts when a connected session rings the terminal bell."
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(sessionId: Long) = (NOTIFICATION_ID_BASE + sessionId).toInt()

    private fun outputNotificationId(sessionId: Long) = (OUTPUT_ID_BASE + sessionId).toInt()

    private fun bellNotificationId(sessionId: Long) = (BELL_ID_BASE + sessionId).toInt()
}
