package com.anezium.rokidrelay.glasses

class RelayDirectionDebouncer(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private var lastAcceptedAtMs = NO_EVENT
    private var lastAcceptedDirection: RelayDirection? = null

    fun accept(direction: RelayDirection, nowMs: Long): Boolean {
        if (
            lastAcceptedDirection == direction &&
            lastAcceptedAtMs != NO_EVENT &&
            nowMs - lastAcceptedAtMs < debounceMs
        ) {
            return false
        }
        lastAcceptedAtMs = nowMs
        lastAcceptedDirection = direction
        return true
    }

    fun clear() {
        lastAcceptedAtMs = NO_EVENT
        lastAcceptedDirection = null
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 260L
        private const val NO_EVENT = Long.MIN_VALUE
    }
}
