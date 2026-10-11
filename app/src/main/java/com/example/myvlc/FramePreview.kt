package com.example.myvlc

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Grabs a small picture of the video at a given time (for the progress-bar preview). */
class FramePreview(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    @Volatile private var latest = 0L
    @Volatile private var generation = 0
    private var retriever: MediaMetadataRetriever? = null
    private var dstW = 320
    private var dstH = 180

    fun setSource(uri: Uri?) {
        generation++
        executor.execute {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // ignore
            }
            retriever = null
            if (uri != null) {
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(appContext, uri)
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) {
                        val t = w
                        w = h
                        h = t
                    }
                    if (w <= 0 || h <= 0) {
                        w = 16
                        h = 9
                    }
                    val scale = 320f / maxOf(w, h)
                    dstW = maxOf(2, (w * scale).toInt())
                    dstH = maxOf(2, (h * scale).toInt())
                    retriever = r
                } catch (e: Exception) {
                    retriever = null
                }
            }
        }
    }

    fun request(timeMs: Long, onFrame: (Bitmap) -> Unit) {
        latest = timeMs
        if (!busy.compareAndSet(false, true)) return
        val gen = generation
        executor.execute {
            try {
                var t = latest
                while (true) {
                    val bmp = frameAt(t)
                    if (bmp != null && gen == generation) main.post { onFrame(bmp) }
                    if (latest == t) break
                    t = latest
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun frameAt(ms: Long): Bitmap? {
        val r = retriever ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH)
            } else {
                val full = r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (full == null) null else Bitmap.createScaledBitmap(full, dstW, dstH, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        generation++
        executor.execute {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // ignore
            }
            retriever = null
        }
        executor.shutdown()
    }
}
