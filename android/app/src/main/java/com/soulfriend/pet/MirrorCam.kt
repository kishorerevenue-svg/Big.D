package com.soulfriend.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs

/**
 * The pet looks at you: the front camera runs for a moment (in [view], or without any window when it is null),
 * then takes ONE photo and is closed. [done] gets the JPEG, or null if the camera couldn't be used.
 */
class MirrorCam(private val ctx: Context, private val view: TextureView?, private val done: (ByteArray?) -> Unit) {
    private val thread = HandlerThread("bigd-mirror").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sink: ImageReader? = null      // no window: the camera's preview frames come here and are let go at once
    private var small = Size(640, 480)
    private var sensor = 270
    @Volatile private var finished = false

    @SuppressLint("MissingPermission")   // the page asks for the camera permission before it opens this
    fun open() {
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
                ?: return finish(null)
            val ch = cm.getCameraCharacteristics(id)
            sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return finish(null)
            val still = map.getOutputSizes(ImageFormat.JPEG).filter { it.width * it.height <= 1600 * 1200 }.maxByOrNull { it.width * it.height } ?: Size(640, 480)
            val prev = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1280 && it.height <= 960 }
                .minByOrNull { abs(it.width.toFloat() / it.height - 4f / 3) * 10 + abs(it.width - 960) / 1000f } ?: Size(640, 480)
            small = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width >= 320 && it.width <= 800 }.minByOrNull { abs(it.width - 640) } ?: Size(640, 480)
            reader = ImageReader.newInstance(still.width, still.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buf = img.planes[0].buffer; val b = ByteArray(buf.remaining()); buf.get(b); img.close()
                    finish(b)
                }, bg)
            }
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; if (finished) d.close() else preview(prev) }
                override fun onDisconnected(d: CameraDevice) { d.close(); finish(null) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); finish(null) }
            }, bg)
        } catch (e: Exception) { finish(null) }
    }

    private fun preview(prev: Size) {
        try {
            val v = view
            val surface: Surface = if (v != null) {
                val st = v.surfaceTexture ?: return finish(null)
                st.setDefaultBufferSize(prev.width, prev.height)
                Surface(st)
            } else ImageReader.newInstance(small.width, small.height, ImageFormat.YUV_420_888, 3).also { s ->
                s.setOnImageAvailableListener({ r -> try { r.acquireLatestImage()?.close() } catch (e: Exception) { } }, bg)
                sink = s
            }.surface
            val d = device ?: return finish(null)
            val r = reader ?: return finish(null)
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            if (v != null) main.post { fit(prev) }
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try { s.setRepeatingRequest(req.build(), null, bg) } catch (e: Exception) { finish(null) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { finish(null) }
            }, bg)
        } catch (e: Exception) { finish(null) }
    }

    /** The round window shows the middle of the picture, not squeezed (in portrait the camera picture is turned, so width and height swap). */
    private fun fit(prev: Size) {
        val view = view ?: return
        val vw = view.width.toFloat(); val vh = view.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val pw = minOf(prev.width, prev.height).toFloat(); val ph = maxOf(prev.width, prev.height).toFloat()
        val k = maxOf(vw / pw, vh / ph)
        view.setTransform(Matrix().apply { setScale(pw * k / vw, ph * k / vh, vw / 2, vh / 2) })
    }

    /** Takes the one photo. */
    fun snap() {
        bg.post {
            try {
                val d = device ?: return@post finish(null)
                val s = session ?: return@post finish(null)
                val r = reader ?: return@post finish(null)
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(r.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, sensor)          // phone held upright, front camera
                    set(CaptureRequest.JPEG_QUALITY, 88.toByte())
                }
                s.capture(req.build(), null, bg)
                bg.postDelayed({ finish(null) }, 6000)                  // nothing came: give up
            } catch (e: Exception) { finish(null) }
        }
    }

    fun cancel() = finish(null)

    private fun finish(b: ByteArray?) {
        if (finished) return
        finished = true
        try { session?.close() } catch (e: Exception) { }
        try { device?.close() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { sink?.close() } catch (e: Exception) { }
        thread.quitSafely()
        main.post { done(b) }
    }
}
