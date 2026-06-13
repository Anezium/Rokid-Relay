package com.anezium.rokidrelay.glasses

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

object RelayHudController {
    data class State(
        val connection: String = "connecting",
        val notification: RelayHudView.NotificationModel? = null,
        val notificationPage: Int = 0,
        val inbox: List<RelayHudView.NotificationModel> = emptyList(),
        val inboxVisible: Boolean = false,
        val inboxDetail: Boolean = false,
        val inboxIndex: Int = 0,
        val inboxDetailPage: Int = 0,
        val voiceState: String = "idle",
        val voicePartial: String = "",
        val countdownMs: Long = 0L,
        val countdownTotalMs: Long = 0L,
        val resultLine: String = "",
        val replyOk: Boolean = false,
        val replyEventId: Long = 0L,
        val transientLine: String = "",
        val accessibilityEnabled: Boolean = false,
        val notificationPopupDurationMs: Long = DEFAULT_NOTIFICATION_POPUP_DURATION_MS,
        val notificationOverlayYOffsetDp: Int = NotificationOverlaySettings.DEFAULT_Y_OFFSET_DP,
        val notificationFontSizeSp: Float = NotificationOverlaySettings.DEFAULT_FONT_SIZE_SP,
        val inputCombo: String = RelayInputSettings.DEFAULT_COMBO,
        val swipeMode: String = RelayInputSettings.DEFAULT_SWIPE_MODE,
        val imageCacheVersion: Long = 0L,
    )

    private val main = Handler(Looper.getMainLooper())
    private val views = LinkedHashSet<RelayHudView>()
    private val notificationShownListeners = LinkedHashSet<() -> Unit>()
    private val stateListeners = LinkedHashSet<(State) -> Unit>()
    private var notificationAutoHideRunnable: Runnable? = null
    private var emptyInboxAutoHideRunnable: Runnable? = null
    private val clearSentResultRunnable = Runnable {
        update {
            if (replyOk && voiceState == "idle") {
                val closeEmptyInbox = inboxVisible && inbox.isEmpty()
                copy(
                    notification = null,
                    notificationPage = 0,
                    resultLine = "",
                    replyOk = false,
                    voicePartial = "",
                    transientLine = "",
                    inboxVisible = if (closeEmptyInbox) false else inboxVisible,
                    inboxDetail = if (closeEmptyInbox) false else inboxDetail,
                    inboxDetailPage = if (closeEmptyInbox) 0 else inboxDetailPage,
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
            cancelEmptyInboxAutoHide()
            main.removeCallbacks(clearSentResultRunnable)
            val shouldNotify = state.notification != model
            state = state.copy(
                notification = model,
                notificationPage = 0,
                inboxVisible = false,
                inboxDetail = false,
                inboxIndex = 0,
                inboxDetailPage = 0,
                voiceState = "idle",
                voicePartial = "",
                resultLine = "",
                replyOk = false,
                transientLine = "",
            )
            dispatchState()
            if (shouldNotify) notificationShownListeners.forEach { it.invoke() }
            scheduleNotificationAutoHide(model)
        }
    }

    fun setNotificationPopupDuration(durationMs: Long) {
        runOnMain {
            state = state.copy(notificationPopupDurationMs = sanitizePopupDuration(durationMs))
            dispatchState()
            scheduleNotificationAutoHide(state.notification)
        }
    }

    fun setNotificationOverlayYOffset(value: Int) {
        update {
            copy(notificationOverlayYOffsetDp = NotificationOverlaySettings.sanitizeYOffsetDp(value))
        }
    }

    fun setNotificationFontSizeSp(value: Float) {
        update {
            val cleanValue = NotificationOverlaySettings.sanitizeFontSizeSp(value)
            val selected = inbox.getOrNull(inboxIndex)
            val currentNotification = notification
            val nextDetailPage = if (inboxDetail && selected?.text?.isNotBlank() == true) {
                inboxDetailPage.coerceIn(0, pageCount(selected, cleanValue) - 1)
            } else {
                inboxDetailPage
            }
            val nextNotificationPage = if (currentNotification?.text?.isNotBlank() == true) {
                notificationPage.coerceIn(0, pageCount(currentNotification, cleanValue) - 1)
            } else {
                0
            }
            copy(
                notificationFontSizeSp = cleanValue,
                notificationPage = nextNotificationPage,
                inboxDetailPage = nextDetailPage,
            )
        }
    }

    fun setInputSettings(combo: String?, swipeMode: String?) {
        runOnMain {
            state = state.copy(
                inputCombo = combo?.let(RelayInputSettings::sanitizeCombo) ?: state.inputCombo,
                swipeMode = swipeMode?.let(RelayInputSettings::sanitizeSwipeMode) ?: state.swipeMode,
            )
            dispatchState()
        }
    }

    fun setInbox(items: List<RelayHudView.NotificationModel>) {
        update {
            val selectedId = inbox.getOrNull(inboxIndex)?.id
            val preservedIndex = if (inboxVisible && selectedId != null) {
                items.indexOfFirst { it.id == selectedId }
            } else {
                -1
            }
            val nextIndex = when {
                items.isEmpty() -> 0
                preservedIndex >= 0 -> preservedIndex
                inboxVisible -> inboxIndex.coerceIn(0, items.lastIndex)
                else -> inboxIndex.coerceIn(0, items.lastIndex)
            }
            val sameSelectedItem = preservedIndex >= 0
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
            val notificationText = nextNotification?.text.orEmpty()
            val nextNotificationPage = if (notificationText.isNotBlank()) {
                nextNotification?.let { model ->
                    notificationPage.coerceIn(0, pageCount(model, notificationFontSizeSp) - 1)
                } ?: 0
            } else {
                0
            }
            val nextDetail = inboxDetail && items.isNotEmpty() && sameSelectedItem
            copy(
                notification = nextNotification,
                notificationPage = nextNotificationPage,
                inbox = items,
                inboxIndex = nextIndex,
                inboxDetail = nextDetail,
                inboxDetailPage = if (nextDetail && sameSelectedItem) {
                    inboxDetailPage.coerceIn(0, pageCount(items[nextIndex], notificationFontSizeSp) - 1)
                } else {
                    0
                },
            )
        }
        runOnMain {
            if (items.isEmpty()) {
                scheduleEmptyInboxAutoHide()
            } else {
                cancelEmptyInboxAutoHide()
            }
        }
    }

    fun openInbox() {
        update {
            copy(
                inboxVisible = true,
                inboxDetail = false,
                inboxDetailPage = 0,
                transientLine = "",
                resultLine = "",
                replyOk = false,
                voiceState = "idle",
                voicePartial = "",
            )
        }
        runOnMain {
            if (state.inbox.isEmpty()) scheduleEmptyInboxAutoHide() else cancelEmptyInboxAutoHide()
        }
    }

    fun closeInbox() {
        cancelEmptyInboxAutoHide()
        update {
            copy(
                inboxVisible = false,
                inboxDetail = false,
                inboxDetailPage = 0,
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
                copy(inboxIndex = 0, inboxDetail = false, inboxDetailPage = 0)
            } else {
                val nextIndex = Math.floorMod(inboxIndex + delta, inbox.size)
                copy(
                    inboxIndex = nextIndex,
                    inboxDetail = false,
                    inboxDetailPage = 0,
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
                copy(inboxDetail = false, inboxDetailPage = 0)
            } else {
                copy(
                    inboxDetail = true,
                    inboxDetailPage = 0,
                    transientLine = "",
                    resultLine = "",
                    replyOk = false,
                    voiceState = "idle",
                    voicePartial = "",
                )
            }
        }
    }

    fun pageInboxDetail(delta: Int): Boolean {
        val snapshot = state
        if (!snapshot.inboxVisible || !snapshot.inboxDetail) return false
        val selected = snapshot.inbox.getOrNull(snapshot.inboxIndex) ?: return true
        val pageCount = pageCount(selected, snapshot.notificationFontSizeSp)
        if (pageCount <= 1) return false
        val nextPage = (snapshot.inboxDetailPage + delta).coerceIn(0, pageCount - 1)
        if (nextPage == snapshot.inboxDetailPage) return false
        update {
            copy(
                inboxDetailPage = nextPage,
                transientLine = "",
                resultLine = "",
                replyOk = false,
                voiceState = "idle",
                voicePartial = "",
            )
        }
        return true
    }

    fun pageNotification(delta: Int): Boolean {
        val snapshot = state
        if (snapshot.inboxVisible || snapshot.isVoiceBusy()) return false
        val model = snapshot.notification ?: return false
        val pageCount = pageCount(model, snapshot.notificationFontSizeSp)
        if (pageCount <= 1) return false
        val nextPage = (snapshot.notificationPage + delta).coerceIn(0, pageCount - 1)
        if (nextPage == snapshot.notificationPage) return true
        update {
            copy(
                notificationPage = nextPage,
                transientLine = "",
                resultLine = "",
                replyOk = false,
                voiceState = "idle",
                voicePartial = "",
            )
        }
        return true
    }

    fun backInInbox() {
        update {
            if (inboxDetail) {
                copy(
                    inboxDetail = false,
                    inboxDetailPage = 0,
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
        cancelNotificationAutoHide()
        update {
            copy(
                notification = null,
                notificationPage = 0,
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

    fun hasPagedNotification(): Boolean {
        val snapshot = state
        val model = snapshot.notification ?: return false
        if (snapshot.inboxVisible || snapshot.isVoiceBusy()) return false
        return pageCount(model, snapshot.notificationFontSizeSp) > 1
    }

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

    fun inputCombo(): String =
        state.inputCombo

    fun notificationOverlayYOffsetDp(): Int =
        state.notificationOverlayYOffsetDp

    fun isInputSourceEnabled(source: RelayInputSource): Boolean =
        RelayInputSettings.sourceEnabled(state.swipeMode, source)

    fun directionKeysEnabled(): Boolean =
        RelayInputSettings.directionKeysEnabled(state.swipeMode, state.inboxVisible)

    fun twoFingerCommandsEnabled(): Boolean =
        RelayInputSettings.twoFingerCommandsEnabled(state.swipeMode, state.inboxVisible)

    fun inputSnapshot(): RelayInputInterpreter.Snapshot {
        val snapshot = state
        val voiceActive = snapshot.isVoiceBusy()
        val selected = snapshot.inbox.getOrNull(snapshot.inboxIndex)
        val inboxDetailPageCount = if (
            snapshot.inboxVisible &&
            snapshot.inboxDetail &&
            selected != null
        ) {
            pageCount(selected, snapshot.notificationFontSizeSp)
        } else {
            1
        }
        val notificationPageCount = snapshot.notification?.let { model ->
            pageCount(model, snapshot.notificationFontSizeSp)
        } ?: 0
        return RelayInputInterpreter.Snapshot(
            inboxOpen = snapshot.inboxVisible,
            inboxDetailOpen = snapshot.inboxVisible && snapshot.inboxDetail,
            inboxDetailPage = snapshot.inboxDetailPage,
            inboxDetailPageCount = inboxDetailPageCount,
            voiceActive = voiceActive,
            voiceReviewing = snapshot.voiceState == "reviewing",
            hasNotification = snapshot.notification != null,
            hasPagedNotification = snapshot.notification != null &&
                !snapshot.inboxVisible &&
                !voiceActive &&
                notificationPageCount > 1,
            directionKeysEnabled = RelayInputSettings.directionKeysEnabled(
                snapshot.swipeMode,
                snapshot.inboxVisible,
            ),
            twoFingerCommandsEnabled = RelayInputSettings.twoFingerCommandsEnabled(
                snapshot.swipeMode,
                snapshot.inboxVisible,
            ),
            inputCombo = snapshot.inputCombo,
        )
    }

    private fun setAccessibilityEnabled(enabled: Boolean) {
        update { copy(accessibilityEnabled = enabled) }
    }

    fun notifyImageCacheChanged() {
        update { copy(imageCacheVersion = imageCacheVersion + 1L) }
    }

    fun notificationImage(imageId: String) =
        RelayNotificationImageCache.get(imageId)

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, RelayAccessibilityService::class.java)
        val componentLong = component.flattenToString()
        val componentShort = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { value ->
            value.equals(componentLong, ignoreCase = true) ||
                value.equals(componentShort, ignoreCase = true) ||
                ComponentName.unflattenFromString(value) == component
        }
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

    private fun scheduleNotificationAutoHide(model: RelayHudView.NotificationModel?) {
        cancelNotificationAutoHide()
        model ?: return
        val durationMs = state.notificationPopupDurationMs
        if (durationMs <= 0L) return
        val runnable = Runnable {
            update {
                if (
                    notification == model &&
                    !inboxVisible &&
                    voiceState == "idle" &&
                    !replyOk
                ) {
                    copy(notification = null, transientLine = "")
                } else {
                    this
                }
            }
        }
        notificationAutoHideRunnable = runnable
        main.postDelayed(runnable, durationMs)
    }

    private fun cancelNotificationAutoHide() {
        notificationAutoHideRunnable?.let { main.removeCallbacks(it) }
        notificationAutoHideRunnable = null
    }

    private fun scheduleEmptyInboxAutoHide() {
        cancelEmptyInboxAutoHide()
        if (!state.inboxVisible || state.inbox.isNotEmpty() || state.voiceState != "idle" || state.replyOk) return
        val runnable = Runnable {
            update {
                if (inboxVisible && inbox.isEmpty() && voiceState == "idle" && !replyOk) {
                    copy(
                        inboxVisible = false,
                        inboxDetail = false,
                        inboxDetailPage = 0,
                        transientLine = "",
                    )
                } else {
                    this
                }
            }
        }
        emptyInboxAutoHideRunnable = runnable
        main.postDelayed(runnable, EMPTY_INBOX_HOLD_MS)
    }

    private fun cancelEmptyInboxAutoHide() {
        emptyInboxAutoHideRunnable?.let { main.removeCallbacks(it) }
        emptyInboxAutoHideRunnable = null
    }

    private fun sanitizePopupDuration(durationMs: Long): Long =
        durationMs.coerceIn(0L, MAX_NOTIFICATION_POPUP_DURATION_MS)

    private fun State.isVoiceBusy(): Boolean =
        voiceState == "listening" ||
            voiceState == "recognizing" ||
            voiceState == "processing" ||
            voiceState == "reviewing"

    private fun pageCount(model: RelayHudView.NotificationModel, fontSizeSp: Float): Int =
        NotificationTextPager.pageCount(model.text, fontSizeSp, model.textPageMaxLines())

    private const val SENT_RESULT_HOLD_MS = 1_250L
    private const val EMPTY_INBOX_HOLD_MS = 1_500L
    private const val DEFAULT_NOTIFICATION_POPUP_DURATION_MS = 5_000L
    private const val MAX_NOTIFICATION_POPUP_DURATION_MS = 300_000L
}
