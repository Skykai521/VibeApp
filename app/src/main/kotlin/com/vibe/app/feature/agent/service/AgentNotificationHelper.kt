package com.vibe.app.feature.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vibe.app.R
import com.vibe.app.presentation.ui.main.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        val ongoingChannel = NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.notification_channel_ongoing),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_ongoing_desc)
            setShowBadge(false)
        }

        val resultChannel = NotificationChannel(
            CHANNEL_RESULT,
            context.getString(R.string.notification_channel_result),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_result_desc)
        }

        notificationManager.createNotificationChannels(listOf(ongoingChannel, resultChannel))
    }

    fun buildOngoingNotification(activeCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancelIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_CANCEL_ALL,
            Intent(ACTION_CANCEL_ALL).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (activeCount == 1) {
            context.getString(R.string.notification_ongoing_single)
        } else {
            context.getString(R.string.notification_ongoing_multiple, activeCount)
        }

        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_ongoing_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                context.getString(R.string.notification_cancel_all),
                cancelIntent,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showCompletionNotification(chatId: Int, projectName: String?, success: Boolean) {
        val contentIntent = PendingIntent.getActivity(
            context,
            chatId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_NAVIGATE_CHAT_ID, chatId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val displayName = projectName ?: context.getString(R.string.notification_project_default_name)

        val (title, text) = if (success) {
            context.getString(R.string.notification_task_completed) to
                context.getString(R.string.notification_task_completed_text, displayName)
        } else {
            context.getString(R.string.notification_task_failed) to
                context.getString(R.string.notification_task_failed_text, displayName)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(RESULT_NOTIFICATION_BASE_ID + chatId, notification)
    }

    fun updateOngoingNotification(activeCount: Int) {
        notificationManager.notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(activeCount))
    }

    /**
     * Cancel all result notifications (the "Task Completed" / "Task Failed" ones).
     * Called when the app returns to the foreground so stale results don't pile up.
     */
    fun cancelAllResultNotifications() {
        notificationManager.activeNotifications.forEach { sbn ->
            if (sbn.id >= RESULT_NOTIFICATION_BASE_ID) {
                notificationManager.cancel(sbn.id)
            }
        }
    }

    companion object {
        const val CHANNEL_ONGOING = "agent_ongoing"
        const val CHANNEL_RESULT = "agent_result"
        const val ONGOING_NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_BASE_ID = 2000
        const val ACTION_CANCEL_ALL = "com.vibe.app.ACTION_CANCEL_ALL_AGENT_SESSIONS"
        const val EXTRA_NAVIGATE_CHAT_ID = "navigate_chat_id"
        private const val REQUEST_CODE_CANCEL_ALL = 100
    }
}
