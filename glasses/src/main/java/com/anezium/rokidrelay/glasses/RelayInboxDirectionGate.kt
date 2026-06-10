package com.anezium.rokidrelay.glasses

class RelayInboxDirectionGate(
    private val twoFingerSuppressMs: Long = DEFAULT_TWO_FINGER_SUPPRESS_MS,
    private val twoFingerUndoMs: Long = DEFAULT_TWO_FINGER_UNDO_MS,
) {
    private var lastTwoFingerAtMs = NO_EVENT

    fun acceptDirectionKey(nowMs: Long): Boolean =
        !isSuppressing(nowMs)

    fun onTwoFinger(nowMs: Long) {
        lastTwoFingerAtMs = nowMs
    }

    fun shouldUndoDirectionForTwoFinger(directionKeyAtMs: Long, twoFingerAtMs: Long): Boolean =
        directionKeyAtMs <= twoFingerAtMs && twoFingerAtMs - directionKeyAtMs <= twoFingerUndoMs

    private fun isSuppressing(nowMs: Long): Boolean =
        lastTwoFingerAtMs != NO_EVENT && nowMs - lastTwoFingerAtMs < twoFingerSuppressMs

    companion object {
        const val DEFAULT_TWO_FINGER_SUPPRESS_MS = 340L
        const val DEFAULT_TWO_FINGER_UNDO_MS = 160L
        private const val NO_EVENT = Long.MIN_VALUE
    }
}
