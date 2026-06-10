package com.anezium.rokidrelay.glasses

import android.view.KeyEvent

object RelayDirectionKeyMapper {
    fun directionFromKey(keyCode: Int): RelayDirection? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_SWIPE_BACK,
            -> RelayDirection.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KEYCODE_SWIPE_FORWARD,
            -> RelayDirection.RIGHT
            else -> null
        }

    fun isDirectionKey(keyCode: Int): Boolean =
        directionFromKey(keyCode) != null

    private const val KEYCODE_SWIPE_FORWARD = 183
    private const val KEYCODE_SWIPE_BACK = 184
}
