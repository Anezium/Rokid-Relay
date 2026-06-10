package com.anezium.rokidrelay.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayInputComboBufferTest {
    @Test
    fun rrllDoesNotMatchPhysicalRrlWhenFinalLeftArrivesTwice() {
        val debouncer = RelayDirectionDebouncer(debounceMs = 260L)
        val comboBuffer = RelayInputComboBuffer(timeoutMs = 2_200L)
        val combo = "RRLL"
        var matched = false

        fun input(nowMs: Long, direction: RelayDirection) {
            if (debouncer.accept(direction, nowMs)) {
                matched = comboBuffer.add(nowMs, direction, combo).matched
            }
        }

        input(1_000L, RelayDirection.RIGHT)
        input(1_320L, RelayDirection.RIGHT)
        input(1_640L, RelayDirection.LEFT)
        input(1_680L, RelayDirection.LEFT)

        assertFalse(matched)
    }

    @Test
    fun rrllMatchesOnlyAfterFourthPhysicalSwipe() {
        val debouncer = RelayDirectionDebouncer(debounceMs = 260L)
        val comboBuffer = RelayInputComboBuffer(timeoutMs = 2_200L)
        val combo = "RRLL"
        var matched = false

        fun input(nowMs: Long, direction: RelayDirection) {
            if (debouncer.accept(direction, nowMs)) {
                matched = comboBuffer.add(nowMs, direction, combo).matched
            }
        }

        input(1_000L, RelayDirection.RIGHT)
        input(1_320L, RelayDirection.RIGHT)
        input(1_640L, RelayDirection.LEFT)
        assertFalse(matched)

        input(1_960L, RelayDirection.LEFT)

        assertTrue(matched)
    }

    @Test
    fun comboTimeoutResetsPartialInput() {
        val comboBuffer = RelayInputComboBuffer(timeoutMs = 500L)

        comboBuffer.add(1_000L, RelayDirection.RIGHT, "RR")
        val result = comboBuffer.add(1_600L, RelayDirection.RIGHT, "RR")

        assertTrue(result.resetBeforeAdd)
        assertFalse(result.matched)
    }
}
