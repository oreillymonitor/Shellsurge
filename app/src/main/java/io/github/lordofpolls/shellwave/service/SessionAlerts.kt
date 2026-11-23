package io.github.lordofpolls.shellwave.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lordofpolls.shellwave.MainActivity
import io.github.lordofpolls.shellwave.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "session_alerts"
private const val COMMAND_OUTPUT_CHANNEL_ID = "command_output"
private const val BELL_CHANNEL_ID = "bell"

// Clear of the ids SessionService (1), the terminal bell (2) and ScriptTriggerService (100+) post.
private const val NOTIFICATION_ID_BASE = 10_000

private const val RECOVERY_TIMEOUT_MS = 15_000L

private const val NOTICE_ID_BASE = 1_000_000
private const val OUTPUT_ID_BASE = 2_000_000
private const val BELL_ID_BASE = 3_000_000

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

    open fun commandOutput(
        sessionId: Long,
        title: String,
        body: String,
        isActiveTab: Boolean,
        notifyOnActiveTab: Boolean,
    ) {
        scope.launch {
            val appForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
            if (appForeground && isActiveTab && !notifyOnActiveTab) return@launch
            val manager =
                context.getSystemService(NotificationManager::class.java) ?: return@launch
            ensureCommandOutputChannel(manager)
            val id = outputNotificationId(sessionId)
            val notification =
                NotificationCompat.Builder(context, COMMAND_OUTPUT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_shellwave)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(openSessions(id, sessionId))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            manager.notify(id, notification)
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
            "Command & output alerts",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description =
            "Alerts when a terminal command finishes or new terminal output is ready to check."
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
