package com.anezium.rokidrelay.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RelayNotificationImageCacheTest {
    @Before
    fun setUp() {
        RelayNotificationImageCache.clearForTests()
    }

    @After
    fun tearDown() {
        RelayNotificationImageCache.clearForTests()
    }

    @Test
    fun storesValidBoundedJpeg() {
        val bytes = jpegBytes(220, 140)

        assertTrue(RelayNotificationImageCache.putEncoded("image-1", bytes, bytes.size))

        val bitmap = RelayNotificationImageCache.get("image-1")
        assertNotNull(bitmap)
        assertEquals(220, bitmap!!.width)
        assertEquals(140, bitmap.height)
    }

    @Test
    fun rejectsOversizedEncodedPayload() {
        val bytes = ByteArray(RelayNotificationImageCache.MAX_PREVIEW_BYTES + 1)

        assertFalse(RelayNotificationImageCache.putEncoded("too-big", bytes, bytes.size))
        assertNull(RelayNotificationImageCache.get("too-big"))
    }

    @Test
    fun evictsOldestEntryWhenFull() {
        val bytes = jpegBytes(120, 80)

        repeat(RelayNotificationImageCache.MAX_ENTRIES + 1) { index ->
            assertTrue(RelayNotificationImageCache.putEncoded("image-$index", bytes, bytes.size))
        }

        assertEquals(RelayNotificationImageCache.MAX_ENTRIES, RelayNotificationImageCache.sizeForTests())
        assertNull(RelayNotificationImageCache.get("image-0"))
        assertNotNull(RelayNotificationImageCache.get("image-${RelayNotificationImageCache.MAX_ENTRIES}"))
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(4, 10, 6))
        paint.color = Color.rgb(92, 255, 136)
        canvas.drawRect(0f, 0f, width * 0.75f, height * 0.55f, paint)
        paint.color = Color.rgb(255, 134, 123)
        canvas.drawCircle(width * 0.76f, height * 0.68f, height * 0.2f, paint)
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output))
            output.toByteArray()
        }
    }
}
