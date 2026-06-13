package com.anezium.rokidrelay.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

object RelayNotificationImageCache {
    const val MAX_PREVIEW_LONG_EDGE_PX = 360
    const val MAX_PREVIEW_BYTES = 80 * 1024
    const val MAX_ENTRIES = 12
    private const val TAG = "RelayImageCache"

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    fun putEncoded(imageId: String, bytes: ByteArray, expectedByteSize: Int): Boolean {
        val cleanId = imageId.trim()
        if (cleanId.isBlank()) return false
        if (bytes.isEmpty() || bytes.size > MAX_PREVIEW_BYTES) {
            Log.w(TAG, "image rejected id=${cleanId.take(8)} bytes=${bytes.size}")
            return false
        }
        if (expectedByteSize > 0 && expectedByteSize != bytes.size) {
            Log.w(TAG, "image rejected id=${cleanId.take(8)} expected=$expectedByteSize actual=${bytes.size}")
            return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "image rejected id=${cleanId.take(8)} reason=decode_bounds")
            return false
        }
        if (maxOf(bounds.outWidth, bounds.outHeight) > MAX_PREVIEW_LONG_EDGE_PX) {
            Log.w(TAG, "image rejected id=${cleanId.take(8)} size=${bounds.outWidth}x${bounds.outHeight}")
            return false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
            Log.w(TAG, "image rejected id=${cleanId.take(8)} reason=decode")
            return false
        }
        synchronized(lock) {
            cache[cleanId] = bitmap
        }
        Log.i(TAG, "image cached id=${cleanId.take(8)} size=${bitmap.width}x${bitmap.height} bytes=${bytes.size}")
        return true
    }

    fun get(imageId: String): Bitmap? =
        synchronized(lock) {
            cache[imageId.trim()]
        }

    fun clearForTests() {
        synchronized(lock) {
            cache.clear()
        }
    }

    fun sizeForTests(): Int =
        synchronized(lock) {
            cache.size
        }
}
