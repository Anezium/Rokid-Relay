package com.anezium.rokidrelay.glasses

class RelayInputComboBuffer(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxLength: Int = RelayInputSettings.MAX_COMBO_LENGTH,
) {
    data class Result(
        val matched: Boolean,
        val resetBeforeAdd: Boolean,
    )

    private val buffer = ArrayList<RelayDirection>(maxLength)
    private var lastInputAtMs = NO_EVENT

    fun add(nowMs: Long, direction: RelayDirection, combo: String): Result {
        val resetBeforeAdd = lastInputAtMs != NO_EVENT && nowMs - lastInputAtMs > timeoutMs
        if (resetBeforeAdd) buffer.clear()
        lastInputAtMs = nowMs
        buffer.add(direction)
        while (buffer.size > maxLength) buffer.removeAt(0)
        return Result(
            matched = RelayInputSettings.matchesCombo(buffer, combo),
            resetBeforeAdd = resetBeforeAdd,
        )
    }

    fun clear() {
        buffer.clear()
        lastInputAtMs = NO_EVENT
    }

    fun snapshot(): List<RelayDirection> = buffer.toList()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_200L
        private const val NO_EVENT = Long.MIN_VALUE
    }
}
