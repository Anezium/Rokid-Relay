package com.anezium.rokidrelay.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientBootstrapTest {
    @Test
    fun unchangedHelperVersionDoesNotRequireInstallWhenOldHashFingerprintWasSaved() {
        assertFalse(
            bundledClientChanged(
                lastFingerprint = "16:0.1.10-preview.6:old-apk-sha",
                nextVersionCode = 16,
                nextVersionName = "0.1.10-preview.6",
            ),
        )
    }

    @Test
    fun changedHelperVersionRequiresInstall() {
        assertTrue(
            bundledClientChanged(
                lastFingerprint = "16:0.1.10-preview.6",
                nextVersionCode = 17,
                nextVersionName = "0.1.10-preview.7",
            ),
        )
    }
}
