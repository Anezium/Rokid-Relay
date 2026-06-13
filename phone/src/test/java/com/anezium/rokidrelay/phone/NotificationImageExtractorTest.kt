package com.anezium.rokidrelay.phone

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationImageExtractorTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun bigPictureNotificationProducesBoundedJpegPreview() {
        val notification = Notification.Builder(context, Constants.TEST_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("Image")
            .setContentText("Preview")
            .setStyle(Notification.BigPictureStyle().bigPicture(sampleBitmap(800, 420)))
            .build()

        val preview = NotificationImageExtractor.extract(context, notification)

        assertNotNull(preview)
        preview!!
        assertEquals("image/jpeg", preview.mimeType)
        assertTrue(preview.bytes.isNotEmpty())
        assertTrue(preview.bytes.size <= NotificationImageExtractor.MAX_PREVIEW_BYTES)
        assertTrue(maxOf(preview.width, preview.height) <= NotificationImageExtractor.MAX_PREVIEW_LONG_EDGE_PX)
        assertNotNull(BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size))
    }

    @Test
    fun textOnlyNotificationHasNoImagePreview() {
        val notification = Notification.Builder(context, Constants.TEST_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("Text")
            .setContentText("No image here")
            .build()

        assertNull(NotificationImageExtractor.extract(context, notification))
    }

    @Test
    fun tinyBitmapIsRejected() {
        val preview = NotificationImageExtractor.encode(sampleBitmap(16, 16), source = "test")

        assertNull(preview)
    }

    private fun sampleBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(8, 18, 11))
        paint.color = Color.rgb(92, 255, 136)
        canvas.drawRect(0f, 0f, width * 0.62f, height * 0.46f, paint)
        paint.color = Color.rgb(230, 190, 92)
        canvas.drawCircle(width * 0.72f, height * 0.58f, height * 0.18f, paint)
        return bitmap
    }
}
