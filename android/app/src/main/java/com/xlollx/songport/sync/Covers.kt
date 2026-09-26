package com.xlollx.songport.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xlollx.songport.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream

/** A playlist cover fetched and shrunk to what a service accepts: a JPEG under 256 KB, at most 640 px. */
object Covers {
    private const val MAX_BYTES = 256_000
    private const val MAX_SIDE = 640

    suspend fun fetchJpeg(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val raw = Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body?.bytes() ?: return@withContext null
        }
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@withContext null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        // Quality steps down until the bytes fit; a cover that never fits is not sent.
        for (q in intArrayOf(85, 70, 55, 40)) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= MAX_BYTES) return@withContext out.toByteArray()
        }
        null
    }
}
