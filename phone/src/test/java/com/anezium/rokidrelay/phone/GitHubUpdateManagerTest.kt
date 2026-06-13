package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateManagerTest {
    @Test
    fun higherVersionCodeWinsOverInstalledVersionCode() {
        val release = release(versionName = "0.1.0", versionCode = 15L)
        val installed = InstalledAppVersion(versionName = "9.9.9", versionCode = 14L)

        assertTrue(release.isNewerThan(installed))
    }

    @Test
    fun lowerOrEqualVersionCodeIsNotNewer() {
        val installed = InstalledAppVersion(versionName = "0.1.13", versionCode = 14L)

        assertFalse(release(versionName = "0.1.99", versionCode = 13L).isNewerThan(installed))
        assertFalse(release(versionName = "0.1.99", versionCode = 14L).isNewerThan(installed))
    }

    @Test
    fun semanticVersionNameComparisonWorksWhenVersionCodeIsNull() {
        val installed = InstalledAppVersion(versionName = "0.1.13", versionCode = 14L)

        assertTrue(release(tagName = "v0.1.14", versionName = "0.1.14", versionCode = null).isNewerThan(installed))
        assertFalse(release(tagName = "v0.1.13", versionName = "0.1.13", versionCode = null).isNewerThan(installed))
        assertFalse(release(tagName = "v0.1.12", versionName = "0.1.12", versionCode = null).isNewerThan(installed))
    }

    @Test
    fun tagOnlyFallbackRequiresEqualVersionComparison() {
        val installed = InstalledAppVersion(versionName = "0.1.13", versionCode = 14L)

        assertTrue(
            release(tagName = "nightly-2026-06-13", versionName = "0.1.13", versionCode = null)
                .isNewerThan(installed),
        )
        assertFalse(
            release(tagName = "v0.1.13", versionName = "0.1.13", versionCode = null)
                .isNewerThan(installed),
        )
        assertFalse(
            release(tagName = "nightly-2026-06-13", versionName = "0.1.12", versionCode = null)
                .isNewerThan(installed),
        )
    }

    @Test
    fun validDownloadedApkMetadataIsAccepted() {
        val installed = InstalledAppVersion(versionName = "0.1.13", versionCode = 14L)
        val update = release(versionName = "0.1.14", versionCode = 15L)
        val apk = downloadedApk(versionName = "0.1.14-hotfix", versionCode = 15L)

        assertEquals(
            apk,
            validateDownloadedApk(apk, update, installed, PHONE_PACKAGE),
        )
    }

    @Test
    fun wrongDownloadedApkPackageIsRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            validateDownloadedApk(
                downloadedApk(packageName = "com.example.other"),
                release(versionName = "0.1.14", versionCode = 15L),
                InstalledAppVersion(versionName = "0.1.13", versionCode = 14L),
                PHONE_PACKAGE,
            )
        }

        assertEquals("Downloaded APK package mismatch", error.message)
    }

    @Test
    fun staleDownloadedApkVersionCodeIsRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            validateDownloadedApk(
                downloadedApk(versionName = "0.1.99", versionCode = 14L),
                release(versionName = "0.1.99", versionCode = null),
                InstalledAppVersion(versionName = "0.1.13", versionCode = 14L),
                PHONE_PACKAGE,
            )
        }

        assertEquals("Downloaded APK is not newer", error.message)
    }

    @Test
    fun releaseVersionCodeMismatchIsRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            validateDownloadedApk(
                downloadedApk(versionName = "0.1.14", versionCode = 16L),
                release(versionName = "0.1.14", versionCode = 15L),
                InstalledAppVersion(versionName = "0.1.13", versionCode = 14L),
                PHONE_PACKAGE,
            )
        }

        assertEquals("Downloaded APK release metadata mismatch", error.message)
    }

    @Test
    fun releaseVersionNameMismatchIsRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            validateDownloadedApk(
                downloadedApk(versionName = "0.1.15", versionCode = 15L),
                release(versionName = "0.1.14", versionCode = null),
                InstalledAppVersion(versionName = "0.1.13", versionCode = 14L),
                PHONE_PACKAGE,
            )
        }

        assertEquals("Downloaded APK release metadata mismatch", error.message)
    }

    @Test
    fun releaseVersionNameFallbackWorksWhenVersionCodeIsNull() {
        val installed = InstalledAppVersion(versionName = "0.1.13", versionCode = 14L)
        val update = release(versionName = "v0.1.14", versionCode = null)
        val apk = downloadedApk(versionName = "0.1.14+15", versionCode = 15L)

        assertEquals(
            apk,
            validateDownloadedApk(apk, update, installed, PHONE_PACKAGE),
        )
    }

    private fun release(
        tagName: String = "v0.1.14",
        versionName: String = "0.1.14",
        versionCode: Long? = null,
    ): GitHubReleaseUpdate =
        GitHubReleaseUpdate(
            tagName = tagName,
            versionName = versionName,
            versionCode = versionCode,
            title = "Rokid Relay $versionName",
            releaseUrl = "https://example.test/releases/$tagName",
            releaseNotes = "",
            apkName = "rokid-relay-phone.apk",
            apkDownloadUrl = "https://example.test/rokid-relay-phone.apk",
        )

    private fun downloadedApk(
        packageName: String = PHONE_PACKAGE,
        versionName: String = "0.1.14",
        versionCode: Long = 15L,
    ): DownloadedApkInfo =
        DownloadedApkInfo(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            sha256 = "abc123",
        )

    private companion object {
        private const val PHONE_PACKAGE = "com.anezium.rokidrelay.phone"
    }
}
