package com.rokid.relay.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ReplyRepository {
    data class CaptureResult(
        val reply: PendingReply,
        val shouldShowNow: Boolean,
    )

    data class PendingReply(
        val id: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val revision: String,
        val notificationKey: String,
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val capturedAtMs: Long,
    )

    private data class IndexedMessage(
        val index: Int,
        val message: Notification.MessagingStyle.Message,
    )

    private val pending = ConcurrentHashMap<String, PendingReply>()
    private val lastCaptureAtMs = AtomicLong(0L)

    fun capture(context: Context, sbn: StatusBarNotification): CaptureResult? {
        val action = findReplyAction(sbn.notification) ?: return null
        val remoteInputs = action.remoteInputs ?: return null
        if (remoteInputs.isEmpty()) return null

        val id = stableId(sbn.key)
        val extras = sbn.notification.extras
        val appLabel = appLabel(context, sbn.packageName)
        val title = extras.charSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val messageLimit = NotificationSettingsStore(context).threadMessageLimit()
        val text = notificationText(extras, messageLimit)
        val revision = notificationRevision(sbn, extras)
        if (hasRemoteInputHistory(extras) && NotificationSettingsStore(context).clearPhoneNotificationAfterReply()) {
            NotificationControl.cancelAfterReply(sbn.key)
        }
        val previous = pending[id]
        val contentChanged = previous == null ||
            !previous.hasSameVisibleContent(
                packageName = sbn.packageName,
                appLabel = appLabel,
                title = title,
                text = text,
                revision = revision,
            )
        val reply = PendingReply(
            id = id,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            revision = revision,
            notificationKey = sbn.key,
            actionIntent = action.actionIntent,
            remoteInputs = remoteInputs,
            capturedAtMs = if (contentChanged) {
                nextCaptureAtMs()
            } else {
                previous?.capturedAtMs ?: nextCaptureAtMs()
            },
        )
        pending[id] = reply
        val mostRecent = isMostRecent(reply.id)
        Log.i(
            TAG,
            "captured pkg=${sbn.packageName} id=${id.take(8)} changed=$contentChanged mostRecent=$mostRecent textLen=${text.length}",
        )
        return CaptureResult(
            reply = reply,
            shouldShowNow = contentChanged && mostRecent,
        )
    }

    fun sendReply(context: Context, notificationId: String, text: String): Boolean {
        val reply = pending[notificationId] ?: return false
        if (text.isBlank()) return false
        val intent = android.content.Intent()
        val results = Bundle()
        reply.remoteInputs.forEach { input ->
            if (input.allowFreeFormInput) {
                results.putCharSequence(input.resultKey, text)
            }
        }
        if (results.isEmpty) return false
        RemoteInput.addResultsToIntent(reply.remoteInputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        val sent = runCatching {
            reply.actionIntent.send(context, 0, intent)
            true
        }.getOrDefault(false)
        if (sent) {
            if (NotificationSettingsStore(context).clearPhoneNotificationAfterReply()) {
                NotificationControl.cancelAfterReply(reply.notificationKey)
            }
            pending.computeIfPresent(notificationId) { _, current ->
                if (current === reply) null else current
            }
        }
        return sent
    }

    fun forget(notificationId: String) {
        if (notificationId.isNotBlank()) pending.remove(notificationId)
    }

    fun forgetStatusBarNotification(sbn: StatusBarNotification) {
        forget(stableId(sbn.key))
    }

    fun listPending(limit: Int = NotificationSettingsStore.DEFAULT_INBOX_ENTRY_LIMIT): List<PendingReply> =
        pending.values
            .sortedByDescending { it.capturedAtMs }
            .take(limit.coerceAtLeast(1))

    private fun isMostRecent(notificationId: String): Boolean =
        pending.values.maxByOrNull { it.capturedAtMs }?.id == notificationId

    private fun findReplyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true &&
                action.actionIntent != null
        }

    private fun notificationText(extras: Bundle, messageLimit: Int): String {
        val maxMessages = messageLimit.coerceIn(
            NotificationSettingsStore.MIN_THREAD_MESSAGE_LIMIT,
            NotificationSettingsStore.MAX_THREAD_MESSAGE_LIMIT,
        )
        val messages = messagingStyleText(extras, maxMessages)
        if (messages.isNotBlank()) return messages

        val big = extras.charSequence(Notification.EXTRA_BIG_TEXT)
        if (!big.isNullOrBlank()) return big.toString()

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            return lines
                .takeLast(maxMessages)
                .joinToString("\n") { it.toString() }
        }

        val text = extras.charSequence(Notification.EXTRA_TEXT)
        return text?.toString().orEmpty()
    }

    private fun messagingStyleText(extras: Bundle, messageLimit: Int): String {
        return messagingStyleMessages(extras)
            .takeLast(messageLimit)
            .mapNotNull { message ->
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) {
                    null
                } else {
                    val sender = message.senderPerson?.name?.toString()?.trim().orEmpty()
                    if (sender.isBlank()) text else "$sender: $text"
                }
            }
            .joinToString("\n")
    }

    private fun notificationRevision(sbn: StatusBarNotification, extras: Bundle): String {
        val messages = messagingStyleMessages(extras)
        if (messages.isNotEmpty()) {
            val first = messages.first()
            val last = messages.last()
            return "msg:${messages.size}:${first.timestamp}:${last.timestamp}"
        }
        return "plain:${sbn.notification.`when`}"
    }

    private fun hasRemoteInputHistory(extras: Bundle): Boolean =
        extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            ?.any { !it.isNullOrBlank() } == true

    private fun messagingStyleMessages(extras: Bundle): List<Notification.MessagingStyle.Message> {
        val bundles = messageBundles(extras) ?: return emptyList()
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        if (messages.none { it.timestamp > 0L }) return messages
        return messages
            .mapIndexed { index, message -> IndexedMessage(index, message) }
            .sortedWith(
                compareBy<IndexedMessage> {
                    it.message.timestamp.takeIf { timestamp -> timestamp > 0L } ?: Long.MAX_VALUE
                }.thenBy { it.index },
            )
            .map { it.message }
    }

    private fun messageBundles(extras: Bundle): Array<Parcelable>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    private fun appLabel(context: Context, packageName: String): String =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

    private fun PendingReply.hasSameVisibleContent(
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        revision: String,
    ): Boolean =
        this.packageName == packageName &&
            this.appLabel == appLabel &&
            this.title == title &&
            this.text == text &&
            this.revision == revision

    private fun nextCaptureAtMs(): Long {
        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastCaptureAtMs.get()
            val next = if (now > previous) now else previous + 1L
            if (lastCaptureAtMs.compareAndSet(previous, next)) return next
        }
    }

    private fun stableId(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
    private const val TAG = "RelayReplyRepo"
}

private fun Bundle.charSequence(key: String): CharSequence? =
    getCharSequence(key)
