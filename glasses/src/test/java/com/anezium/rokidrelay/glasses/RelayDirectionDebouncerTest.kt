package com.anezium.rokidrelay.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDirectionDebouncerTest {
    @Test
    fun suppressesDuplicateDirectionalAliasesInsideDebounceWindow() {
        val debouncer = RelayDirectionDebouncer(debounceMs = 260L)

        assertTrue(debouncer.accept(RelayDirection.RIGHT, nowMs = 1_000L))
        assertFalse(debouncer.accept(RelayDirection.RIGHT, nowMs = 1_040L))
        assertTrue(debouncer.accept(RelayDirection.RIGHT, nowMs = 1_260L))
    }

    @Test
    fun allowsOppositeDirectionsInsideDebounceWindow() {
        val debouncer = RelayDirectionDebouncer(debounceMs = 260L)

        assertTrue(debouncer.accept(RelayDirection.RIGHT, nowMs = 1_000L))
        assertTrue(debouncer.accept(RelayDirection.LEFT, nowMs = 1_040L))
    }

    @Test
    fun clearAllowsNextDirectionImmediately() {
        val debouncer = RelayDirectionDebouncer(debounceMs = 260L)

        assertTrue(debouncer.accept(RelayDirection.RIGHT, nowMs = 1_000L))
        debouncer.clear()

        assertTrue(debouncer.accept(RelayDirection.RIGHT, nowMs = 1_010L))
    }
}
