package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneUpdateControllerTest {
    @Test
    fun updateStateConvertsToGitHubReleaseUpdate() {
        val release = AppUpdateUiState(
            latestTag = "v0.1.14",
            latestVersionName = "0.1.14",
            latestVersionCode = 15L,
            releaseUrl = "https://example.test/releases/v0.1.14",
            releaseNotes = "Release notes",
            apkName = "rokid-relay-phone.apk",
            apkUrl = "https://example.test/rokid-relay-phone.apk",
        ).toGitHubReleaseUpdate()

        assertEquals("v0.1.14", release?.tagName)
        assertEquals("0.1.14", release?.versionName)
        assertEquals(15L, release?.versionCode)
        assertEquals("v0.1.14", release?.title)
        assertEquals("https://example.test/releases/v0.1.14", release?.releaseUrl)
        assertEquals("Release notes", release?.releaseNotes)
        assertEquals("rokid-relay-phone.apk", release?.apkName)
        assertEquals("https://example.test/rokid-relay-phone.apk", release?.apkDownloadUrl)
    }

    @Test
    fun updateStateConversionRequiresApkMetadata() {
        assertNull(
            AppUpdateUiState(
                apkName = "rokid-relay-phone.apk",
                apkUrl = "",
            ).toGitHubReleaseUpdate(),
        )
        assertNull(
            AppUpdateUiState(
                apkName = "",
                apkUrl = "https://example.test/rokid-relay-phone.apk",
            ).toGitHubReleaseUpdate(),
        )
    }

    @Test
    fun updateStateConversionTitleFallsBackToVersionAndApkName() {
        assertEquals(
            "0.1.14",
            AppUpdateUiState(
                latestVersionName = "0.1.14",
                apkName = "rokid-relay-phone.apk",
                apkUrl = "https://example.test/rokid-relay-phone.apk",
            ).toGitHubReleaseUpdate()?.title,
        )
        assertEquals(
            "rokid-relay-phone.apk",
            AppUpdateUiState(
                apkName = "rokid-relay-phone.apk",
                apkUrl = "https://example.test/rokid-relay-phone.apk",
            ).toGitHubReleaseUpdate()?.title,
        )
    }
}
