package com.anezium.rokidrelay.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Constants.ACTION_POST_TEST_NOTIFICATION -> TestNotificationHarness.handlePostIntent(context, intent)
            Constants.ACTION_TEST_REPLY -> TestNotificationHarness.handleReply(context, intent)
        }
    }

    companion object {
        val DEFAULT_TEST_THREAD_MESSAGE_COUNT: Int
            get() = TestNotificationHarness.DEFAULT_TEST_THREAD_MESSAGE_COUNT

        val MAX_TEST_THREAD_INDEX: Int
            get() = TestNotificationHarness.MAX_TEST_THREAD_INDEX

        val MAX_TEST_THREAD_MESSAGES_PER_POST: Int
            get() = TestNotificationHarness.MAX_TEST_THREAD_MESSAGES_PER_POST

        fun postTestNotification(
            context: Context,
            message: String = "Replyable test message from the phone.",
            notificationId: Int = Constants.TEST_NOTIFICATION_ID,
        ) {
            TestNotificationHarness.postTestNotification(context, message, notificationId)
        }

        fun nextThreadIndex(context: Context): Int =
            TestNotificationHarness.nextThreadIndex(context)

        fun postThreadTestNotification(
            context: Context,
            threadIndex: Int,
            addCount: Int = DEFAULT_TEST_THREAD_MESSAGE_COUNT,
            reset: Boolean = false,
            longMessages: Boolean = false,
        ) {
            TestNotificationHarness.postThreadTestNotification(
                context = context,
                threadIndex = threadIndex,
                addCount = addCount,
                reset = reset,
                longMessages = longMessages,
            )
        }

        fun clearTestThread(context: Context, threadIndex: Int) {
            TestNotificationHarness.clearTestThread(context, threadIndex)
        }

        fun clearAllTestThreads(context: Context) {
            TestNotificationHarness.clearAllTestThreads(context)
        }

        fun postBurstTestNotification(
            context: Context,
            notificationId: Int = Constants.TEST_NOTIFICATION_ID,
            messageCount: Int = 12,
            conversationTitle: String = "Rokid Relay test thread",
        ) {
            TestNotificationHarness.postBurstTestNotification(
                context = context,
                notificationId = notificationId,
                messageCount = messageCount,
                conversationTitle = conversationTitle,
            )
        }

        fun postSecondThreadTestNotification(context: Context) {
            TestNotificationHarness.postSecondThreadTestNotification(context)
        }
    }
}
