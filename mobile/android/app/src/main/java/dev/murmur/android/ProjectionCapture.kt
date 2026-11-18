package dev.murmur.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Projection frames are drained and discarded. Only an explicit capture writes an image. */
object ProjectionCapture {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val requested = AtomicBoolean(false)
    val active: Boolean get() = reader != null
    fun open(context: Context, projection: MediaProjection, runtime: AppRuntime, id: String) {
        close()
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = source
        source.setOnImageAvailableListener({ images ->
            val image = runCatching { images.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (!requested.compareAndSet(true, false)) return@setOnImageAvailableListener
                val plane = image.planes[0]
                val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                val time = runtime.state.value.seconds
                runtime.scope.launch {
                    try {
                        val file = File(runtime.dataDir, "screens/$id/${System.currentTimeMillis()}.jpg").apply { parentFile!!.mkdirs() }
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        runtime.edit(id) { meeting -> meeting.put("screens", meeting.array("screens").put(json("t" to time, "path" to file.path))) }
                    } catch (error: Throwable) { runtime.failure("capture.screen", error) }
                    finally { bitmap.recycle(); if (bitmap !== padded) padded.recycle() }
                }
            } finally { image.close() }
        }, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay("Murmur", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, source.surface, null, Handler(Looper.getMainLooper()))
    }
    fun capture() { if (active) requested.set(true) }
    fun close() { requested.set(false); display?.release(); display = null; reader?.close(); reader = null }
}
