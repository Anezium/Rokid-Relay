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
            val shouldNotify = state.notification?.id != model.id
            state = state.copy(
                notification = model,
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
            val nextIndex = inboxIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val currentNotification = notification
            val nextNotification = if (
                currentNotification == null ||
                items.any { it.id == currentNotification.id }
            ) {
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

    fun setVoice(stateName: String, partial: String) {
        update {
            copy(
                voiceState = stateName.ifBlank { "idle" },
                voicePartial = partial,
                transientLine = "",
            )
        }
    }

    fun showReplyResult(ok: Boolean, message: String) {
        update {
            copy(
                resultLine = if (ok) message.ifBlank { "Reply sent" } else message.ifBlank { "Reply failed" },
                replyOk = ok,
                replyEventId = replyEventId + 1L,
                voiceState = "idle",
                voicePartial = "",
            )
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
            state.voiceState == "processing"

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
}
