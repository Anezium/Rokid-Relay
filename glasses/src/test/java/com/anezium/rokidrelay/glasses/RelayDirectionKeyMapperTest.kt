package com.anezium.rokidrelay.glasses

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDirectionKeyMapperTest {
    @Test
    fun mapsDpadVerticalAliasesToTheSamePhysicalSwipeDirection() {
        assertEquals(RelayDirection.LEFT, RelayDirectionKeyMapper.directionFromKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RelayDirection.RIGHT, RelayDirectionKeyMapper.directionFromKey(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun mapsDpadHorizontalDirections() {
        assertEquals(RelayDirection.LEFT, RelayDirectionKeyMapper.directionFromKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RelayDirection.RIGHT, RelayDirectionKeyMapper.directionFromKey(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun ignoresNonDirectionalKeys() {
        assertNull(RelayDirectionKeyMapper.directionFromKey(KeyEvent.KEYCODE_ENTER))
    }
}
