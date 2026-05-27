package com.rokid.relay.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class TestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Constants.ACTION_POST_TEST_NOTIFICATION -> {
                val message = intent.getStringExtra(Constants.EXTRA_TEST_MESSAGE)
                    ?: if (intent.getBooleanExtra(Constants.EXTRA_TEST_LONG, false)) LONG_TEST_MESSAGE else DEFAULT_TEST_MESSAGE
                val notificationId = intent.getIntExtra(Constants.EXTRA_TEST_ID, Constants.TEST_NOTIFICATION_ID)
                postTestNotification(context, message, notificationId)
            }
            Constants.ACTION_TEST_REPLY -> handleReply(context, intent)
        }
    }

    companion object {
        private const val TAG = "RokidRelayTestNotif"
        private const val DEFAULT_TEST_MESSAGE = "Message replyable de test depuis le telephone."
        private const val LONG_TEST_MESSAGE =
            "Long message de test pour Rokid Relay. Il doit rester lisible sur les lunettes sans prendre tout l'ecran: " +
                "on garde quelques lignes utiles, puis le reste est tronque proprement. Cette phrase ajoute volontairement " +
                "du contenu pour verifier l'ellipse, la hauteur maximale et le confort en notification reelle."

        fun postTestNotification(
            context: Context,
            message: String = DEFAULT_TEST_MESSAGE,
            notificationId: Int = Constants.TEST_NOTIFICATION_ID,
        ) {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(NotificationManager::class.java)
            ensureChannel(manager)

            val replyIntent = Intent(appContext, TestNotificationReceiver::class.java)
                .setAction(Constants.ACTION_TEST_REPLY)
                .putExtra(Constants.EXTRA_TEST_ID, notificationId)
            val replyPendingIntent = PendingIntent.getBroadcast(
                appContext,
                notificationId,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
            )
            val remoteInput = RemoteInput.Builder(Constants.EXTRA_TEST_REPLY)
                .setLabel("Reply")
                .build()
            val replyAction = Notification.Action.Builder(
                R.drawable.ic_launcher,
                "Reply",
                replyPendingIntent,
            )
                .addRemoteInput(remoteInput)
                .build()

            val contentIntent = PendingIntent.getActivity(
                appContext,
                notificationId,
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )

            val notification = Notification.Builder(appContext, Constants.TEST_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Rokid Relay test")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(contentIntent)
                .setAutoCancel(false)
                .addAction(replyAction)
                .build()

            manager.notify(notificationId, notification)
            RelayBridge.setStatus("test notification posted")
        }

        private fun handleReply(context: Context, intent: Intent) {
            val reply = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(Constants.EXTRA_TEST_REPLY)
                ?.toString()
                ?: intent.getStringExtra(Constants.EXTRA_TEST_REPLY)
                .orEmpty()
            val notificationId = intent.getIntExtra(Constants.EXTRA_TEST_ID, Constants.TEST_NOTIFICATION_ID)
            context.getSystemService(NotificationManager::class.java)
                .cancel(notificationId)
            RelayBridge.recordDeliveredReply(reply)
            Log.i(TAG, "test reply received length=${reply.length}")
        }

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
    }
}
