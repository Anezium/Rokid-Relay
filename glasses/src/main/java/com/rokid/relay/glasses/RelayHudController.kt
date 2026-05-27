package com.rokid.relay.glasses

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

object RelayHudController {
    data class State(
        val connection: String = "connecting",
        val notification: RelayHudView.NotificationModel? = null,
        val inbox: List<RelayHudView.NotificationModel> = emptyList(),
        val inboxVisible: Boolean = false,
        val inboxDetail: Boolean = false,
        val inboxIndex: Int = 0,
        val voiceState: String = "idle",
        val voicePartial: String = "",
        val countdownMs: Long = 0L,
        val countdownTotalMs: Long = 0L,
        val resultLine: String = "",
        val replyOk: Boolean = false,
        val replyEventId: Long = 0L,
        val transientLine: String = "",
        val accessibilityEnabled: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val views = LinkedHashSet<RelayHudView>()
    private val notificationShownListeners = LinkedHashSet<() -> Unit>()
    private val stateListeners = LinkedHashSet<(State) -> Unit>()
    private val clearSentResultRunnable = Runnable {
        update {
            if (replyOk && voiceState == "idle") {
                copy(
                    notification = null,
                    resultLine = "",
                    replyOk = false,
                    voicePartial = "",
                    transientLine = "",
                    inboxDetail = if (inboxVisible && inbox.isEmpty()) false else inboxDetail,
                )
            } else {
                this
            }
        }
    }

    @Volatile
    private var state = State()

    fun attach(view: RelayHudView) {
        runOnMain {
            views.add(view)
            refreshAccessibility(view.context)
            view.applyState(state)
        }
    }

    fun detach(view: RelayHudView) {
        runOnMain { views.remove(view) }
    }

    fun addNotificationShownListener(listener: () -> Unit) {
        runOnMain { notificationShownListeners.add(listener) }
    }

    fun removeNotificationShownListener(listener: () -> Unit) {
        runOnMain { notificationShownListeners.remove(listener) }
    }

    fun addStateListener(listener: (State) -> Unit) {
        runOnMain {
            stateListeners.add(listener)
            listener(state)
        }
    }

    fun removeStateListener(listener: (State) -> Unit) {
        runOnMain { stateListeners.remove(listener) }
    }

    fun refreshAccessibility(context: Context) {
        setAccessibilityEnabled(isAccessibilityEnabled(context))
    }

    fun setConnection(value: String) {
        update { copy(connection = value) }
    }

    fun showNotification(model: RelayHudView.NotificationModel) {
        runOnMain {
            main.removeCallbacks(clearSentResultRunnable)
            val shouldNotify = state.notification?.id != model.id
            state = state.copy(
                notification = model,
                inboxVisible = false,
                inboxDetail = false,
                inboxIndex = 0,
                voiceState = "idle",
                voicePartial = "",
                resultLine = "",
                replyOk = false,
                transientLine = "",
            )
            dispatchState()
            if (shouldNotify) notificationShownListeners.forEach { it.invoke() }
        }
    }

    fun setInbox(items: List<RelayHudView.NotificationModel>) {
        update {
            val selectedId = inbox.getOrNull(inboxIndex)?.id
            val nextIndex = when {
                items.isEmpty() -> 0
                inboxVisible && selectedId != null -> {
                    val preservedIndex = items.indexOfFirst { it.id == selectedId }
                    if (preservedIndex >= 0) preservedIndex else 0
                }
                else -> inboxIndex.coerceIn(0, items.lastIndex)
            }
            val currentNotification = notification
            val refreshedNotification = currentNotification?.let { current ->
                items.firstOrNull { it.id == current.id } ?: current
            }
            val nextNotification = if (
                currentNotification == null
            ) {
                null
            } else if (items.any { it.id == currentNotification.id }) {
                refreshedNotification
            } else if (replyOk && resultLine.isNotBlank()) {
                currentNotification
            } else {
                null
            }
            copy(
                notification = nextNotification,
                inbox = items,
                inboxIndex = nextIndex,
                inboxDetail = inboxDetail && items.isNotEmpty(),
            )
        }
    }

    fun openInbox() {
        update {
            copy(
                inboxVisible = true,
                inboxDetail = false,
                transientLine = "",
                resultLine = "",
                replyOk = false,
                voiceState = "idle",
                voicePartial = "",
            )
        }
    }

    fun closeInbox() {
        update {
            copy(
                inboxVisible = false,
                inboxDetail = false,
                transientLine = "",
                resultLine = "",
                replyOk = false,
                voiceState = "idle",
                voicePartial = "",
            )
        }
    }

    fun navigateInbox(delta: Int) {
        update {
            if (inbox.isEmpty()) {
                copy(inboxIndex = 0, inboxDetail = false)
            } else {
                val nextIndex = Math.floorMod(inboxIndex + delta, inbox.size)
                copy(
                    inboxIndex = nextIndex,
                    inboxDetail = false,
                    transientLine = "",
                    resultLine = "",
                    replyOk = false,
                    voiceState = "idle",
                    voicePartial = "",
                )
            }
        }
    }

    fun openInboxDetail() {
        update {
            if (inbox.isEmpty()) {
                copy(inboxDetail = false)
            } else {
                copy(
                    inboxDetail = true,
                    transientLine = "",
                    resultLine = "",
                    replyOk = false,
                    voiceState = "idle",
                    voicePartial = "",
                )
            }
        }
    }

    fun backInInbox() {
        update {
            if (inboxDetail) {
                copy(
                    inboxDetail = false,
                    voiceState = "idle",
                    voicePartial = "",
                    resultLine = "",
                    replyOk = false,
                )
            } else {
                copy(inboxVisible = false)
            }
        }
    }

    fun clearNotification() {
        update {
            copy(
                notification = null,
                voiceState = "idle",
                voicePartial = "",
                resultLine = "",
                replyOk = false,
                transientLine = "",
            )
        }
    }

    fun setVoice(
        stateName: String,
        partial: String,
        countdownMs: Long = 0L,
        countdownTotalMs: Long = 0L,
    ) {
        update {
            copy(
                voiceState = stateName.ifBlank { "idle" },
                voicePartial = partial,
                countdownMs = countdownMs,
                countdownTotalMs = countdownTotalMs,
                transientLine = "",
            )
        }
    }

    fun showReplyResult(ok: Boolean, message: String) {
        runOnMain {
            main.removeCallbacks(clearSentResultRunnable)
            state = state.copy(
                resultLine = if (ok) message.ifBlank { "Reply sent" } else message.ifBlank { "Reply failed" },
                replyOk = ok,
                replyEventId = state.replyEventId + 1L,
                voiceState = "idle",
                voicePartial = "",
                countdownMs = 0L,
                countdownTotalMs = 0L,
                transientLine = "",
            )
            dispatchState()
            if (ok) main.postDelayed(clearSentResultRunnable, SENT_RESULT_HOLD_MS)
        }
    }

    fun showTransient(message: String) {
        update { copy(transientLine = message) }
    }

    fun hasNotification(): Boolean = state.notification != null

    fun isInboxOpen(): Boolean = state.inboxVisible

    fun isInboxDetailOpen(): Boolean = state.inboxVisible && state.inboxDetail

    fun isVoiceActive(): Boolean =
        state.voiceState == "listening" ||
            state.voiceState == "recognizing" ||
            state.voiceState == "processing" ||
            state.voiceState == "reviewing"

    fun isVoiceReviewing(): Boolean =
        state.voiceState == "reviewing"

    fun currentNotificationId(): String {
        val snapshot = state
        if (snapshot.inboxVisible && snapshot.inboxDetail) {
            return snapshot.inbox.getOrNull(snapshot.inboxIndex)?.id.orEmpty()
        }
        return snapshot.notification?.id.orEmpty()
    }

    private fun setAccessibilityEnabled(enabled: Boolean) {
        update { copy(accessibilityEnabled = enabled) }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, RelayAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun update(block: State.() -> State) {
        runOnMain {
            state = state.block()
            dispatchState()
        }
    }

    private fun dispatchState() {
        views.forEach { it.applyState(state) }
        stateListeners.forEach { it.invoke(state) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    private const val SENT_RESULT_HOLD_MS = 1_250L
}
