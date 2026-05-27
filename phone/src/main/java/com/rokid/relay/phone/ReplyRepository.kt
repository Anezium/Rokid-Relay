package com.rokid.relay.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
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
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val capturedAtMs: Long,
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
        val text = notificationText(extras)
        val previous = pending[id]
        val contentChanged = previous == null ||
            !previous.hasSameVisibleContent(
                packageName = sbn.packageName,
                appLabel = appLabel,
                title = title,
                text = text,
            )
        val reply = PendingReply(
            id = id,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            actionIntent = action.actionIntent,
            remoteInputs = remoteInputs,
            capturedAtMs = if (contentChanged) {
                nextCaptureAtMs()
            } else {
                previous?.capturedAtMs ?: nextCaptureAtMs()
            },
        )
        pending[id] = reply
        return CaptureResult(
            reply = reply,
            shouldShowNow = contentChanged && isMostRecent(reply.id),
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
        return runCatching {
            reply.actionIntent.send(context, 0, intent)
            true
        }.getOrDefault(false)
    }

    fun forget(notificationId: String) {
        if (notificationId.isNotBlank()) pending.remove(notificationId)
    }

    fun forgetStatusBarNotification(sbn: StatusBarNotification) {
        forget(stableId(sbn.key))
    }

    fun listPending(limit: Int = 8): List<PendingReply> =
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

    private fun notificationText(extras: Bundle): String {
        val messages = messagingStyleText(extras)
        if (messages.isNotBlank()) return messages

        val big = extras.charSequence(Notification.EXTRA_BIG_TEXT)
        if (!big.isNullOrBlank()) return big.toString()

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            return lines
                .takeLast(MAX_VISIBLE_MESSAGE_COUNT)
                .joinToString("\n") { it.toString() }
        }

        val text = extras.charSequence(Notification.EXTRA_TEXT)
        return text?.toString().orEmpty()
    }

    private fun messagingStyleText(extras: Bundle): String {
        val bundles = messageBundles(extras) ?: return ""
        return Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            .takeLast(MAX_VISIBLE_MESSAGE_COUNT)
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
    ): Boolean =
        this.packageName == packageName &&
            this.appLabel == appLabel &&
            this.title == title &&
            this.text == text

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

    private const val MAX_VISIBLE_MESSAGE_COUNT = 12
}

private fun Bundle.charSequence(key: String): CharSequence? =
    getCharSequence(key)
