package com.anezium.rokidrelay.phone

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

data class NotificationImagePreview(
    val id: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val source: String,
)

object NotificationImageExtractor {
    const val MAX_PREVIEW_LONG_EDGE_PX = 360
    const val MAX_PREVIEW_BYTES = 80 * 1024
    const val MIN_PREVIEW_EDGE_PX = 24
    private const val MIME_JPEG = "image/jpeg"
    private const val EXTRA_PICTURE_ICON = "android.pictureIcon"
    private const val TAG = "RelayImageExtractor"

    fun extract(context: Context, notification: Notification): NotificationImagePreview? {
        val extras = notification.extras ?: return null
        messagingStyleImage(context, extras)?.let { return it }
        bigPictureImage(context, extras)?.let { return it }
        return null
    }

    private fun messagingStyleImage(context: Context, extras: Bundle): NotificationImagePreview? {
        val bundles = messageBundles(extras) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        return messages.asReversed().firstNotNullOfOrNull { message ->
            val mimeType = message.dataMimeType?.lowercase().orEmpty()
            val uri = message.dataUri
            if (!mimeType.startsWith("image/") || uri == null) {
                null
            } else {
                decodeUri(context, uri)?.let { bitmap ->
                    encode(bitmap, source = "messaging_style")
                }
            }
        }
    }

    private fun bigPictureImage(context: Context, extras: Bundle): NotificationImagePreview? {
        val picture = extras.getNotificationParcelable(Notification.EXTRA_PICTURE)
        val bitmap = when (picture) {
            is Bitmap -> picture
            is Icon -> iconToBitmap(context, picture)
            is Drawable -> drawableToBitmap(picture)
            else -> null
        }
        if (bitmap != null) return encode(bitmap, source = "big_picture")

        val pictureIcon = extras.getNotificationParcelable(EXTRA_PICTURE_ICON) as? Icon
        return pictureIcon?.let { icon ->
            iconToBitmap(context, icon)?.let { encode(it, source = "big_picture_icon") }
        }
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? =
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (width, height) = targetSize(info.size.width, info.size.height)
                if (width > 0 && height > 0) decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.onFailure {
            Log.w(TAG, "image decode failed: ${it.javaClass.simpleName}")
        }.getOrNull()

    private fun iconToBitmap(context: Context, icon: Icon): Bitmap? =
        runCatching { icon.loadDrawable(context) }
            .getOrNull()
            ?.let(::drawableToBitmap)

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: MAX_PREVIEW_LONG_EDGE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: MAX_PREVIEW_LONG_EDGE_PX
        val (targetWidth, targetHeight) = targetSize(width, height)
        if (targetWidth <= 0 || targetHeight <= 0) return null
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    internal fun encode(bitmap: Bitmap, source: String): NotificationImagePreview? {
        if (bitmap.width < MIN_PREVIEW_EDGE_PX || bitmap.height < MIN_PREVIEW_EDGE_PX) return null

        val sourceBitmap = bitmap.softwareCopy()
        val targetEdges = intArrayOf(MAX_PREVIEW_LONG_EDGE_PX, 300, 240)
        val qualities = intArrayOf(70, 62, 54)
        for (edge in targetEdges) {
            val scaled = sourceBitmap.scaledToLongEdge(edge)
            if (scaled.width < MIN_PREVIEW_EDGE_PX || scaled.height < MIN_PREVIEW_EDGE_PX) continue
            for (quality in qualities) {
                val bytes = scaled.toJpeg(quality)
                if (bytes.isNotEmpty() && bytes.size <= MAX_PREVIEW_BYTES) {
                    val id = stableId(bytes)
                    Log.i(
                        TAG,
                        "image preview source=$source id=${id.take(8)} size=${scaled.width}x${scaled.height} bytes=${bytes.size}",
                    )
                    return NotificationImagePreview(
                        id = id,
                        mimeType = MIME_JPEG,
                        width = scaled.width,
                        height = scaled.height,
                        bytes = bytes,
                        source = source,
                    )
                }
            }
        }
        Log.i(TAG, "image preview skipped source=$source reason=too_large")
        return null
    }

    private fun Bitmap.softwareCopy(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false)
        } else {
            this
        }

    private fun Bitmap.scaledToLongEdge(maxLongEdge: Int): Bitmap {
        val (targetWidth, targetHeight) = targetSize(width, height, maxLongEdge)
        if (targetWidth == width && targetHeight == height) return this
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (!compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                ByteArray(0)
            } else {
                output.toByteArray()
            }
        }

    private fun targetSize(
        width: Int,
        height: Int,
        maxLongEdge: Int = MAX_PREVIEW_LONG_EDGE_PX,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun stableId(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun messageBundles(extras: Bundle): Array<Parcelable>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    @Suppress("DEPRECATION")
    private fun Bundle.getNotificationParcelable(key: String): Any? =
        get(key)
}
