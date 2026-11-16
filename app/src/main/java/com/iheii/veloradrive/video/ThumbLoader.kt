package com.iheii.veloradrive.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.iheii.veloradrive.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Miniaturas de /video/thumb: caché chica en memoria (bytes, no cantidad), RGB_565 y reducidas a
 * ~[TARGET_W] px de ancho antes de decodificar. Como mucho 3 descargas a la vez; al reciclar una
 * tarjeta se cancela la descarga que ya no hace falta.
 */
object ThumbLoader {
    private const val TARGET_W = 320
    private const val CACHE_BYTES = 6 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(3)

    fun load(scope: CoroutineScope, view: ImageView, url: String?) {
        (view.getTag(R.id.thumb_job) as? Job)?.cancel()
        view.setTag(R.id.thumb_url, url)
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val job = scope.launch {
            val bmp = withContext(io) { fetch(url) } ?: return@launch
            cache.put(url, bmp)
            if (view.getTag(R.id.thumb_url) == url) view.setImageBitmap(bmp)
        }
        view.setTag(R.id.thumb_job, job)
    }

    fun clear() = cache.evictAll()

    private fun fetch(url: String): Bitmap? = runCatching {
        VideoApi.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.bytes() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_W) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }
    }.getOrNull()
}
