package com.anezium.rokidrelay.glasses

class RelayInputInterpreter(
    private val directionDebouncer: RelayDirectionDebouncer = RelayDirectionDebouncer(),
    private val inboxDirectionGate: RelayInboxDirectionGate = RelayInboxDirectionGate(),
    private val comboBuffer: RelayInputComboBuffer = RelayInputComboBuffer(),
    private val singleTapDelayMs: Long = DEFAULT_SINGLE_TAP_DELAY_MS,
    private val replyWakeMs: Long = DEFAULT_REPLY_WAKE_MS,
    private val inboxWakeMs: Long = DEFAULT_INBOX_WAKE_MS,
) {
    data class Snapshot(
        val inboxOpen: Boolean = false,
        val inboxDetailOpen: Boolean = false,
        val inboxDetailPage: Int = 0,
        val inboxDetailPageCount: Int = 1,
        val voiceActive: Boolean = false,
        val voiceReviewing: Boolean = false,
        val hasNotification: Boolean = false,
        val hasPagedNotification: Boolean = false,
        val directionKeysEnabled: Boolean = true,
        val twoFingerCommandsEnabled: Boolean = false,
        val inputCombo: String = RelayInputSettings.DEFAULT_COMBO,
    ) {
        val relayActive: Boolean
            get() = inboxOpen || hasNotification || voiceActive

        fun canPageInboxDetail(direction: RelayDirection): Boolean =
            inboxDetailOpen && when (direction) {
                RelayDirection.LEFT -> inboxDetailPage > 0
                RelayDirection.RIGHT -> inboxDetailPage < inboxDetailPageCount - 1
            }
    }

    data class Decision(
        val consumed: Boolean,
        val actions: List<Action> = emptyList(),
    )

    enum class ConfirmMode {
        ACTIVITY_IMMEDIATE,
        SERVICE_IMMEDIATE,
        INBOX_TAP,
    }

    enum class BackMode {
        ACTIVITY,
        ACCESSIBILITY,
    }

    sealed class Action {
        object StartVoice : Action()
        object CancelVoice : Action()
        object OpenInbox : Action()
        object OpenInboxDetail : Action()
        object BackInInbox : Action()
        object HideNotification : Action()
        object OpenAccessibilitySettings : Action()
        object RestoreCommandVolumeSoon : Action()
        object ScheduleCommandVolumeClear : Action()
        object CancelSingleTap : Action()
        data class NavigateInbox(val delta: Int) : Action()
        data class PageInboxDetail(val delta: Int) : Action()
        data class PageNotification(val delta: Int) : Action()
        data class KeepReplyScreenOn(val durationMs: Long) : Action()
        data class CaptureCommandVolume(val replaceExisting: Boolean) : Action()
        data class ScheduleSingleTap(val delayMs: Long) : Action()
    }

    fun handleDirectionKey(
        snapshot: Snapshot,
        direction: RelayDirection,
        nowMs: Long,
    ): Decision {
        if (!snapshot.directionKeysEnabled) return pass()
        if (snapshot.voiceActive) return consume()
        if (!directionDebouncer.accept(direction, nowMs)) {
            return Decision(consumed = snapshot.relayActive)
        }
        if (snapshot.inboxOpen) {
            return handleInboxDirection(snapshot, direction, nowMs)
        }
        if (snapshot.hasNotification) {
            return consume(notificationDirectionActions(snapshot, direction))
        }

        val combo = comboBuffer.add(nowMs, direction, snapshot.inputCombo)
        if (!combo.matched) return pass()
        comboBuffer.clear()
        return consume(
            Action.OpenInbox,
            Action.KeepReplyScreenOn(inboxWakeMs),
        )
    }

    fun handleTwoFinger(
        snapshot: Snapshot,
        direction: RelayDirection,
        nowMs: Long,
    ): Decision {
        if (snapshot.inboxOpen) {
            inboxDirectionGate.onTwoFinger(nowMs)
            return consume(undoRecentInboxDirectionActions(snapshot, nowMs))
        }
        if (!snapshot.twoFingerCommandsEnabled) return pass()
        inboxDirectionGate.onTwoFinger(nowMs)
        if (!directionDebouncer.accept(direction, nowMs)) return consume()
        if (snapshot.hasNotification) {
            return consume(
                listOf(Action.CaptureCommandVolume(replaceExisting = false)) +
                    notificationDirectionActions(snapshot, direction) +
                    Action.RestoreCommandVolumeSoon,
            )
        }

        val hadComboInput = comboBuffer.snapshot().isNotEmpty()
        val combo = comboBuffer.add(nowMs, direction, snapshot.inputCombo)
        val actions = ArrayList<Action>()
        if (!hadComboInput || combo.resetBeforeAdd) {
            actions += Action.CaptureCommandVolume(replaceExisting = true)
        }
        if (combo.matched) {
            comboBuffer.clear()
            actions += Action.OpenInbox
            actions += Action.KeepReplyScreenOn(inboxWakeMs)
            actions += Action.RestoreCommandVolumeSoon
        } else {
            actions += Action.ScheduleCommandVolumeClear
        }
        return consume(actions)
    }

    fun handleConfirm(
        snapshot: Snapshot,
        mode: ConfirmMode,
    ): Decision {
        if (snapshot.voiceReviewing) return consume(Action.StartVoice)
        if (snapshot.voiceActive) return consume(Action.CancelVoice)
        return when (mode) {
            ConfirmMode.ACTIVITY_IMMEDIATE -> handleActivityConfirm(snapshot)
            ConfirmMode.SERVICE_IMMEDIATE -> {
                if (snapshot.hasNotification) {
                    consume(Action.KeepReplyScreenOn(replyWakeMs), Action.StartVoice)
                } else {
                    pass()
                }
            }
            ConfirmMode.INBOX_TAP -> handleInboxTap(snapshot)
        }
    }

    fun handleSingleTapTimer(snapshot: Snapshot): Decision {
        tapArmed = false
        if (!snapshot.inboxOpen) return consume()
        return if (snapshot.inboxDetailOpen) {
            consume(Action.KeepReplyScreenOn(replyWakeMs), Action.StartVoice)
        } else {
            consume(Action.OpenInboxDetail, Action.KeepReplyScreenOn(inboxWakeMs))
        }
    }

    fun handleBack(
        snapshot: Snapshot,
        mode: BackMode,
    ): Decision {
        tapArmed = false
        lastAppliedInboxDirection = null
        val actions = ArrayList<Action>()
        actions += Action.CancelSingleTap
        if (snapshot.inboxOpen) {
            if (snapshot.voiceActive) actions += Action.CancelVoice
            actions += Action.BackInInbox
            return consume(actions)
        }
        if (mode == BackMode.ACCESSIBILITY && snapshot.voiceActive) {
            actions += Action.CancelVoice
            return consume(actions)
        }
        if (snapshot.hasNotification) {
            actions += Action.HideNotification
            return consume(actions)
        }
        return pass()
    }

    fun resetTransientState() {
        tapArmed = false
        lastAppliedInboxDirection = null
        comboBuffer.clear()
        directionDebouncer.clear()
    }

    private fun handleInboxDirection(
        snapshot: Snapshot,
        direction: RelayDirection,
        nowMs: Long,
    ): Decision {
        if (!inboxDirectionGate.acceptDirectionKey(nowMs)) return consume()
        val actions = ArrayList<Action>()
        actions += Action.CancelSingleTap
        tapArmed = false
        if (snapshot.inboxDetailOpen) {
            if (!snapshot.canPageInboxDetail(direction)) return consume(actions)
            val delta = delta(direction)
            lastAppliedInboxDirection = AppliedInboxDirection(
                kind = AppliedInboxDirection.Kind.DETAIL_PAGE,
                direction = direction,
                receivedAtMs = nowMs,
            )
            actions += Action.PageInboxDetail(delta)
        } else {
            val delta = delta(direction)
            lastAppliedInboxDirection = AppliedInboxDirection(
                kind = AppliedInboxDirection.Kind.LIST,
                direction = direction,
                receivedAtMs = nowMs,
            )
            actions += Action.NavigateInbox(delta)
        }
        actions += Action.KeepReplyScreenOn(inboxWakeMs)
        return consume(actions)
    }

    private fun handleActivityConfirm(snapshot: Snapshot): Decision =
        when {
            snapshot.inboxDetailOpen -> consume(Action.StartVoice)
            snapshot.inboxOpen -> consume(Action.OpenInboxDetail)
            snapshot.hasNotification -> consume(Action.StartVoice)
            else -> consume(Action.OpenAccessibilitySettings)
        }

    private fun handleInboxTap(snapshot: Snapshot): Decision {
        if (!snapshot.inboxOpen) return pass()
        lastAppliedInboxDirection = null
        return if (tapArmed) {
            tapArmed = false
            consume(Action.CancelSingleTap, Action.BackInInbox)
        } else {
            tapArmed = true
            consume(Action.ScheduleSingleTap(singleTapDelayMs))
        }
    }

    private fun notificationDirectionActions(
        snapshot: Snapshot,
        direction: RelayDirection,
    ): List<Action> =
        buildList {
            add(Action.KeepReplyScreenOn(replyWakeMs))
            if (snapshot.hasPagedNotification) {
                add(Action.PageNotification(delta(direction)))
            } else {
                add(Action.StartVoice)
            }
        }

    private fun undoRecentInboxDirectionActions(
        snapshot: Snapshot,
        nowMs: Long,
    ): List<Action> {
        val applied = lastAppliedInboxDirection ?: return emptyList()
        if (!inboxDirectionGate.shouldUndoDirectionForTwoFinger(applied.receivedAtMs, nowMs)) {
            return emptyList()
        }
        lastAppliedInboxDirection = null
        if (!snapshot.inboxOpen || snapshot.voiceActive) return emptyList()
        val reverseDelta = -delta(applied.direction)
        return when (applied.kind) {
            AppliedInboxDirection.Kind.LIST -> listOf(Action.NavigateInbox(reverseDelta))
            AppliedInboxDirection.Kind.DETAIL_PAGE -> listOf(Action.PageInboxDetail(reverseDelta))
        }
    }

    private fun delta(direction: RelayDirection): Int =
        if (direction == RelayDirection.LEFT) -1 else 1

    private fun pass(): Decision =
        Decision(consumed = false)

    private fun consume(vararg actions: Action): Decision =
        consume(actions.toList())

    private fun consume(actions: List<Action> = emptyList()): Decision =
        Decision(consumed = true, actions = actions)

    private data class AppliedInboxDirection(
        val kind: Kind,
        val direction: RelayDirection,
        val receivedAtMs: Long,
    ) {
        enum class Kind {
            LIST,
            DETAIL_PAGE,
        }
    }

    companion object {
        const val DEFAULT_SINGLE_TAP_DELAY_MS = 220L
        const val DEFAULT_REPLY_WAKE_MS = 35_000L
        const val DEFAULT_INBOX_WAKE_MS = 30_000L
    }

    private var tapArmed = false
    private var lastAppliedInboxDirection: AppliedInboxDirection? = null
}
