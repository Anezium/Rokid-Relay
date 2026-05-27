package com.rokid.relay.phone

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.RemoteInput
import android.util.Log

object TestNotificationHarness {
    const val DEFAULT_TEST_THREAD_MESSAGE_COUNT = TestMessageFactory.DEFAULT_THREAD_MESSAGE_COUNT
    const val MAX_TEST_THREAD_INDEX = TestThreadStore.MAX_THREAD_INDEX
    const val MAX_TEST_THREAD_MESSAGES_PER_POST = TestMessageFactory.MAX_MESSAGES_PER_POST

    private const val TAG = "RokidRelayTestNotif"

    fun handlePostIntent(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(Constants.EXTRA_TEST_ID, Constants.TEST_NOTIFICATION_ID)
        val messageCount = intent.getIntExtra(Constants.EXTRA_TEST_COUNT, 0)
        val threadIndex = intent.getIntExtra(
            Constants.EXTRA_TEST_THREAD_INDEX,
            threadIndexForNotificationId(notificationId),
        )
        if (messageCount > 1) {
            postThreadTestNotification(
                context = context,
                threadIndex = threadIndex,
                addCount = messageCount,
                reset = true,
                longMessages = intent.getBooleanExtra(Constants.EXTRA_TEST_LONG, false),
            )
        } else {
            val message = intent.getStringExtra(Constants.EXTRA_TEST_MESSAGE)
                ?: if (intent.getBooleanExtra(Constants.EXTRA_TEST_LONG, false)) {
                    TestMessageFactory.LONG_TEST_MESSAGE
                } else {
                    TestMessageFactory.DEFAULT_TEST_MESSAGE
                }
            postTestNotification(context, message, notificationId)
        }
    }

    fun handleReply(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Constants.EXTRA_TEST_REPLY)
            ?.toString()
            ?: intent.getStringExtra(Constants.EXTRA_TEST_REPLY)
            .orEmpty()
        val notificationId = intent.getIntExtra(Constants.EXTRA_TEST_ID, Constants.TEST_NOTIFICATION_ID)
        val threadIndex = intent.getIntExtra(Constants.EXTRA_TEST_THREAD_INDEX, NO_THREAD_INDEX)
        if (threadIndex > 0 && reply.isNotBlank()) {
            TestThreadStore.appendUserReply(context.applicationContext, threadIndex, reply)
        }
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId)
        RelayBridge.recordDeliveredReply(reply)
        Log.i(TAG, "test reply received length=${reply.length}")
    }

    fun postTestNotification(
        context: Context,
        message: String = TestMessageFactory.DEFAULT_TEST_MESSAGE,
        notificationId: Int = Constants.TEST_NOTIFICATION_ID,
    ) {
        TestNotificationPoster.postPlain(context, message, notificationId)
        RelayBridge.setStatus("test notification posted")
    }

    fun nextThreadIndex(context: Context): Int =
        TestThreadStore.nextThreadIndex(context)

    fun postThreadTestNotification(
        context: Context,
        threadIndex: Int,
        addCount: Int = DEFAULT_TEST_THREAD_MESSAGE_COUNT,
        reset: Boolean = false,
        longMessages: Boolean = false,
        conversationTitle: String = conversationTitle(threadIndex),
    ) {
        val appContext = context.applicationContext
        val normalizedThread = TestThreadStore.normalizeThreadIndex(threadIndex)
        val existingMessages = if (reset) emptyList() else TestThreadStore.load(appContext, normalizedThread)
        val appendedMessages = TestMessageFactory.generatedMessages(
            threadIndex = normalizedThread,
            startIndex = existingMessages.size,
            count = addCount,
            longMessages = longMessages,
        )
        val messages = existingMessages + appendedMessages
        TestThreadStore.save(appContext, normalizedThread, messages)
        TestThreadStore.updateNextThreadIndex(appContext, normalizedThread + 1)
        TestNotificationPoster.postThread(
            context = appContext,
            notificationId = notificationIdForThread(normalizedThread),
            threadIndex = normalizedThread,
            messages = TestThreadStore.load(appContext, normalizedThread),
            conversationTitle = conversationTitle,
        )
        RelayBridge.setStatus(
            if (reset) {
                "test thread $normalizedThread reset"
            } else {
                "test thread $normalizedThread appended"
            },
        )
    }

    fun clearTestThread(context: Context, threadIndex: Int) {
        val normalizedThread = TestThreadStore.normalizeThreadIndex(threadIndex)
        TestThreadStore.clear(context.applicationContext, normalizedThread)
        TestNotificationPoster.cancel(context, notificationIdForThread(normalizedThread))
        RelayBridge.setStatus("test thread $normalizedThread cleared")
    }

    fun clearAllTestThreads(context: Context) {
        val appContext = context.applicationContext
        val clearedThreads = TestThreadStore.clearAll(appContext)
        clearedThreads.forEach { threadIndex ->
            TestNotificationPoster.cancel(appContext, notificationIdForThread(threadIndex))
        }
        TestNotificationPoster.cancel(appContext, Constants.TEST_NOTIFICATION_ID)
        TestNotificationPoster.cancel(appContext, Constants.TEST_NOTIFICATION_SECOND_THREAD_ID)
        RelayBridge.setStatus("all test threads cleared")
    }

    fun postBurstTestNotification(
        context: Context,
        notificationId: Int = Constants.TEST_NOTIFICATION_ID,
        messageCount: Int = TestMessageFactory.burstMessageCount,
        conversationTitle: String = "Rokid Relay test thread",
    ) {
        postThreadTestNotification(
            context = context,
            threadIndex = threadIndexForNotificationId(notificationId),
            addCount = messageCount,
            reset = true,
            longMessages = false,
            conversationTitle = conversationTitle,
        )
    }

    fun postSecondThreadTestNotification(context: Context) {
        postBurstTestNotification(
            context = context,
            notificationId = Constants.TEST_NOTIFICATION_SECOND_THREAD_ID,
            messageCount = 8,
            conversationTitle = "Rokid Relay second thread",
        )
    }

    private fun notificationIdForThread(threadIndex: Int): Int =
        Constants.TEST_NOTIFICATION_ID + TestThreadStore.normalizeThreadIndex(threadIndex) - 1

    private fun threadIndexForNotificationId(notificationId: Int): Int =
        TestThreadStore.normalizeThreadIndex(notificationId - Constants.TEST_NOTIFICATION_ID + 1)

    private fun conversationTitle(threadIndex: Int): String =
        "Rokid Relay test thread ${TestThreadStore.normalizeThreadIndex(threadIndex)}"

    private const val NO_THREAD_INDEX = 0
}
