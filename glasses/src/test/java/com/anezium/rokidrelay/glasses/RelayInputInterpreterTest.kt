package com.anezium.rokidrelay.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayInputInterpreterTest {
    @Test
    fun duplicateDirectionalAliasMovesInboxOnlyOnce() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot()

        val first = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)
        val duplicate = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_040L)

        assertTrue(first.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.CancelSingleTap,
                RelayInputInterpreter.Action.NavigateInbox(1),
                RelayInputInterpreter.Action.KeepReplyScreenOn(30_000L),
            ),
            first.actions,
        )
        assertTrue(duplicate.consumed)
        assertEquals(emptyList<RelayInputInterpreter.Action>(), duplicate.actions)
    }

    @Test
    fun oppositeDirectionsInsideDebounceWindowStillMove() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot()

        interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)
        val opposite = interpreter.handleDirectionKey(snapshot, RelayDirection.LEFT, nowMs = 1_040L)

        assertTrue(opposite.consumed)
        assertTrue(opposite.actions.contains(RelayInputInterpreter.Action.NavigateInbox(-1)))
    }

    @Test
    fun twoFingerAfterRecentInboxKeyUndoesTheMove() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot()

        interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)
        val twoFinger = interpreter.handleTwoFinger(snapshot, RelayDirection.RIGHT, nowMs = 1_080L)

        assertTrue(twoFinger.consumed)
        assertEquals(
            listOf(RelayInputInterpreter.Action.NavigateInbox(-1)),
            twoFinger.actions,
        )
    }

    @Test
    fun twoFingerBeforeInboxKeySuppressesThePairedDirectionKey() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot()

        val twoFinger = interpreter.handleTwoFinger(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)
        val pairedKey = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_120L)

        assertTrue(twoFinger.consumed)
        assertEquals(emptyList<RelayInputInterpreter.Action>(), twoFinger.actions)
        assertTrue(pairedKey.consumed)
        assertEquals(emptyList<RelayInputInterpreter.Action>(), pairedKey.actions)
    }

    @Test
    fun pagedNotificationDirectionPagesInsteadOfStartingVoice() {
        val interpreter = RelayInputInterpreter()
        val snapshot = RelayInputInterpreter.Snapshot(
            hasNotification = true,
            hasPagedNotification = true,
        )

        val decision = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)

        assertTrue(decision.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.KeepReplyScreenOn(35_000L),
                RelayInputInterpreter.Action.PageNotification(1),
            ),
            decision.actions,
        )
    }

    @Test
    fun inboxDetailDirectionPagesWhenAnotherPageExists() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot(
            inboxDetailOpen = true,
            inboxDetailPage = 2,
            inboxDetailPageCount = 4,
        )

        val decision = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)

        assertTrue(decision.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.CancelSingleTap,
                RelayInputInterpreter.Action.PageInboxDetail(1),
                RelayInputInterpreter.Action.KeepReplyScreenOn(30_000L),
            ),
            decision.actions,
        )
    }

    @Test
    fun inboxDetailDirectionAtPageBoundaryDoesNotMoveList() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot(
            inboxDetailOpen = true,
            inboxDetailPage = 3,
            inboxDetailPageCount = 4,
        )

        val decision = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)

        assertTrue(decision.consumed)
        assertEquals(
            listOf(RelayInputInterpreter.Action.CancelSingleTap),
            decision.actions,
        )
    }

    @Test
    fun unpagedNotificationDirectionStartsVoice() {
        val interpreter = RelayInputInterpreter()
        val snapshot = RelayInputInterpreter.Snapshot(
            hasNotification = true,
            hasPagedNotification = false,
        )

        val decision = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)

        assertTrue(decision.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.KeepReplyScreenOn(35_000L),
                RelayInputInterpreter.Action.StartVoice,
            ),
            decision.actions,
        )
    }

    @Test
    fun rrllComboDoesNotOpenAfterOnlyRrlOrDebouncedFourthAlias() {
        val interpreter = RelayInputInterpreter()
        val snapshot = RelayInputInterpreter.Snapshot(inputCombo = "RRLL")

        val first = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)
        val second = interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_320L)
        val third = interpreter.handleDirectionKey(snapshot, RelayDirection.LEFT, nowMs = 1_640L)
        val alias = interpreter.handleDirectionKey(snapshot, RelayDirection.LEFT, nowMs = 1_680L)

        assertFalse(first.consumed)
        assertFalse(second.consumed)
        assertFalse(third.consumed)
        assertFalse(alias.consumed)
        assertEquals(emptyList<RelayInputInterpreter.Action>(), alias.actions)
    }

    @Test
    fun rrllComboOpensOnlyAfterFourthPhysicalSwipe() {
        val interpreter = RelayInputInterpreter()
        val snapshot = RelayInputInterpreter.Snapshot(inputCombo = "RRLL")

        interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)
        interpreter.handleDirectionKey(snapshot, RelayDirection.RIGHT, nowMs = 1_320L)
        interpreter.handleDirectionKey(snapshot, RelayDirection.LEFT, nowMs = 1_640L)
        val decision = interpreter.handleDirectionKey(snapshot, RelayDirection.LEFT, nowMs = 1_960L)

        assertTrue(decision.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.OpenInbox,
                RelayInputInterpreter.Action.KeepReplyScreenOn(30_000L),
            ),
            decision.actions,
        )
    }

    @Test
    fun inboxSingleTapTimerOpensDetail() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot()

        val tap = interpreter.handleConfirm(snapshot, RelayInputInterpreter.ConfirmMode.INBOX_TAP)
        val timer = interpreter.handleSingleTapTimer(snapshot)

        assertTrue(tap.consumed)
        assertEquals(
            listOf(RelayInputInterpreter.Action.ScheduleSingleTap(220L)),
            tap.actions,
        )
        assertTrue(timer.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.OpenInboxDetail,
                RelayInputInterpreter.Action.KeepReplyScreenOn(30_000L),
            ),
            timer.actions,
        )
    }

    @Test
    fun inboxDoubleTapGoesBackAndCancelsSingleTap() {
        val interpreter = RelayInputInterpreter()
        val snapshot = inboxSnapshot()

        interpreter.handleConfirm(snapshot, RelayInputInterpreter.ConfirmMode.INBOX_TAP)
        val secondTap = interpreter.handleConfirm(snapshot, RelayInputInterpreter.ConfirmMode.INBOX_TAP)

        assertTrue(secondTap.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.CancelSingleTap,
                RelayInputInterpreter.Action.BackInInbox,
            ),
            secondTap.actions,
        )
    }

    @Test
    fun twoFingerPartialComboOutsideRelayDoesNotRestoreCommandVolume() {
        val interpreter = RelayInputInterpreter()
        val snapshot = RelayInputInterpreter.Snapshot(
            directionKeysEnabled = false,
            twoFingerCommandsEnabled = true,
            inputCombo = "RRLL",
        )

        val decision = interpreter.handleTwoFinger(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)

        assertTrue(decision.consumed)
        assertEquals(
            listOf(
                RelayInputInterpreter.Action.CaptureCommandVolume(replaceExisting = true),
                RelayInputInterpreter.Action.ScheduleCommandVolumeClear,
            ),
            decision.actions,
        )
    }

    @Test
    fun twoFingerCommandModeDisabledPassesThroughWithoutActions() {
        val interpreter = RelayInputInterpreter()
        val snapshot = RelayInputInterpreter.Snapshot(
            directionKeysEnabled = true,
            twoFingerCommandsEnabled = false,
            inputCombo = "RRLL",
        )

        val decision = interpreter.handleTwoFinger(snapshot, RelayDirection.RIGHT, nowMs = 1_000L)

        assertFalse(decision.consumed)
        assertEquals(emptyList<RelayInputInterpreter.Action>(), decision.actions)
    }

    private fun inboxSnapshot(
        inboxDetailOpen: Boolean = false,
        inboxDetailPage: Int = 0,
        inboxDetailPageCount: Int = 1,
    ): RelayInputInterpreter.Snapshot =
        RelayInputInterpreter.Snapshot(
            inboxOpen = true,
            inboxDetailOpen = inboxDetailOpen,
            inboxDetailPage = inboxDetailPage,
            inboxDetailPageCount = inboxDetailPageCount,
            directionKeysEnabled = true,
            twoFingerCommandsEnabled = false,
        )
}
