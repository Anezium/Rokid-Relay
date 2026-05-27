package com.rokid.relay.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object ReplyRepository {
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

    fun capture(context: Context, sbn: StatusBarNotification): PendingReply? {
        val action = findReplyAction(sbn.notification) ?: return null
        val remoteInputs = action.remoteInputs ?: return null
        if (remoteInputs.isEmpty()) return null

        val id = stableId(sbn.key)
        val extras = sbn.notification.extras
        val appLabel = appLabel(context, sbn.packageName)
        val reply = PendingReply(
            id = id,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = extras.charSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = notificationText(extras),
            actionIntent = action.actionIntent,
            remoteInputs = remoteInputs,
            capturedAtMs = System.currentTimeMillis(),
        )
        pending[id] = reply
        return reply
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

    private fun findReplyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true &&
                action.actionIntent != null
        }

    private fun notificationText(extras: Bundle): String {
        val big = extras.charSequence(Notification.EXTRA_BIG_TEXT)
        if (!big.isNullOrBlank()) return big.toString()
        val text = extras.charSequence(Notification.EXTRA_TEXT)
        if (!text.isNullOrBlank()) return text.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        return lines?.joinToString("\n") { it.toString() }.orEmpty()
    }

    private fun appLabel(context: Context, packageName: String): String =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

    private fun stableId(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
}

private fun Bundle.charSequence(key: String): CharSequence? =
    getCharSequence(key)
