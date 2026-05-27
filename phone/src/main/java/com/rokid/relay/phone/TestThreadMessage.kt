package com.rokid.relay.phone

internal data class TestThreadMessage(
    val text: String,
    val sender: String,
    val timestamp: Long,
    val outgoing: Boolean = false,
)
