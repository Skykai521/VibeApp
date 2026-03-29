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
            "Agent Working",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when AI agent tasks are running in the background"
            setShowBadge(false)
        }

        val resultChannel = NotificationChannel(
            CHANNEL_RESULT,
            "Task Results",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications when AI agent tasks complete or fail"
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
            "1 task in progress"
        } else {
            "$activeCount tasks in progress"
        }

        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VibeApp - Working")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Cancel All",
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

        val displayName = projectName ?: "Project"

        val (title, text) = if (success) {
            "Task Completed" to "'$displayName' is ready! Tap to view."
        } else {
            "Task Failed" to "Task failed for '$displayName'. Tap to see errors."
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
