package com.anezium.rokidrelay.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build

internal object TestNotificationPoster {
    fun postPlain(
        context: Context,
        message: String,
        notificationId: Int,
    ) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)

        val notification = Notification.Builder(appContext, Constants.TEST_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("Rokid Relay test")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent(appContext, notificationId))
            .setAutoCancel(false)
            .addAction(replyAction(appContext, notificationId, NO_THREAD_INDEX))
            .build()

        manager.notify(notificationId, notification)
    }

    fun postThread(
        context: Context,
        notificationId: Int,
        threadIndex: Int,
        messages: List<TestThreadMessage>,
        conversationTitle: String,
    ) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)

        val user = Person.Builder().setName("You").build()
        val style = Notification.MessagingStyle(user)
            .setConversationTitle(conversationTitle)
            .setGroupConversation(true)
        messages.forEach { message ->
            style.addMessage(
                Notification.MessagingStyle.Message(
                    message.text,
                    message.timestamp,
                    Person.Builder().setName(message.sender).build(),
                ),
            )
        }

        val notification = Notification.Builder(appContext, Constants.TEST_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(conversationTitle)
            .setContentText(messages.lastOrNull()?.text ?: TestMessageFactory.DEFAULT_TEST_MESSAGE)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent(appContext, notificationId))
            .setAutoCancel(false)
            .addAction(replyAction(appContext, notificationId, threadIndex))
            .build()

        manager.notify(notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId)
    }

    private fun replyAction(context: Context, notificationId: Int, threadIndex: Int): Notification.Action {
        val replyIntent = Intent(context, TestNotificationReceiver::class.java)
            .setAction(Constants.ACTION_TEST_REPLY)
            .putExtra(Constants.EXTRA_TEST_ID, notificationId)
            .putExtra(Constants.EXTRA_TEST_THREAD_INDEX, threadIndex)
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
        val remoteInput = RemoteInput.Builder(Constants.EXTRA_TEST_REPLY)
            .setLabel("Reply")
            .build()
        return Notification.Action.Builder(
            R.drawable.ic_stat_relay,
            "Reply",
            replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun contentIntent(context: Context, notificationId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = manager.getNotificationChannel(Constants.TEST_NOTIFICATION_CHANNEL)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.TEST_NOTIFICATION_CHANNEL,
                "Rokid Relay test",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private const val NO_THREAD_INDEX = 0
}
