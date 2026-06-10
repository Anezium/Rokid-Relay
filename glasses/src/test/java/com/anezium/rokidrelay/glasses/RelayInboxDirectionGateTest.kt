package com.anezium.rokidrelay.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayInboxDirectionGateTest {
    @Test
    fun directionKeyIsAcceptedImmediatelyWhenNoTwoFingerWasSeen() {
        val gate = RelayInboxDirectionGate(twoFingerSuppressMs = 340L)

        assertTrue(gate.acceptDirectionKey(nowMs = 1_000L))
    }

    @Test
    fun directionKeyAfterRecentTwoFingerIsSuppressed() {
        val gate = RelayInboxDirectionGate(twoFingerSuppressMs = 340L)

        gate.onTwoFinger(nowMs = 1_000L)

        assertFalse(gate.acceptDirectionKey(nowMs = 1_200L))
    }

    @Test
    fun directionKeyWorksAgainAfterTwoFingerWindowExpires() {
        val gate = RelayInboxDirectionGate(twoFingerSuppressMs = 340L)

        gate.onTwoFinger(nowMs = 1_000L)

        assertTrue(gate.acceptDirectionKey(nowMs = 1_341L))
    }

    @Test
    fun twoFingerCanUndoDirectionThatArrivedJustBeforeBroadcast() {
        val gate = RelayInboxDirectionGate(
            twoFingerSuppressMs = 340L,
            twoFingerUndoMs = 160L,
        )

        assertTrue(gate.shouldUndoDirectionForTwoFinger(directionKeyAtMs = 1_000L, twoFingerAtMs = 1_120L))
    }

    @Test
    fun oldDirectionIsNotUndoneByLaterTwoFinger() {
        val gate = RelayInboxDirectionGate(
            twoFingerSuppressMs = 340L,
            twoFingerUndoMs = 160L,
        )

        assertFalse(gate.shouldUndoDirectionForTwoFinger(directionKeyAtMs = 1_000L, twoFingerAtMs = 1_200L))
    }
}
