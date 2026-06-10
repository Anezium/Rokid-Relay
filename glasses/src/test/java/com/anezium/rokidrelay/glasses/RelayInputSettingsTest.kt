package com.anezium.rokidrelay.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayInputSettingsTest {
    @Test
    fun comboInputIsCaseInsensitive() {
        assertEquals("RRLL", RelayInputSettings.sanitizeCombo("rrll"))
    }

    @Test
    fun directionKeysStayEnabledForOpenInboxInTwoFingerMode() {
        assertTrue(
            RelayInputSettings.directionKeysEnabled(
                RelayInputSettings.SWIPE_MODE_TWO_FINGER,
                inboxOpen = true,
            ),
        )
    }

    @Test
    fun directionKeysAreDisabledOutsideInboxInTwoFingerMode() {
        assertFalse(
            RelayInputSettings.directionKeysEnabled(
                RelayInputSettings.SWIPE_MODE_TWO_FINGER,
                inboxOpen = false,
            ),
        )
    }

    @Test
    fun twoFingerCommandsNeverDriveOpenInbox() {
        assertFalse(
            RelayInputSettings.twoFingerCommandsEnabled(
                RelayInputSettings.SWIPE_MODE_TWO_FINGER,
                inboxOpen = true,
            ),
        )
    }

    @Test
    fun twoFingerCommandsDriveRelayOutsideInboxOnlyInTwoFingerMode() {
        assertTrue(
            RelayInputSettings.twoFingerCommandsEnabled(
                RelayInputSettings.SWIPE_MODE_TWO_FINGER,
                inboxOpen = false,
            ),
        )
        assertFalse(
            RelayInputSettings.twoFingerCommandsEnabled(
                RelayInputSettings.SWIPE_MODE_NORMAL,
                inboxOpen = false,
            ),
        )
    }
}
