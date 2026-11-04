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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs

/**
 * The pet's eyes: a camera runs for a moment (in [view], or without any window when it is null), waits until the
 * picture is sharp and bright, then takes ONE photo and is closed. [back] = the camera on the back of the phone
 * (boards, shirts, things in front of you); otherwise the front camera. [done] gets the JPEG, or null if the camera couldn't be used.
 */
class MirrorCam(private val ctx: Context, private val view: TextureView?, private val back: Boolean = false, private val done: (ByteArray?) -> Unit) {
    private val thread = HandlerThread("bigd-eyes").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sink: ImageReader? = null      // no window: the camera's preview frames come here and are let go at once
    private var small = Size(640, 480)
    private var sensor = 270
    private var front = true
    private var autoFocus = false
    @Volatile private var afState: Int? = null
    @Volatile private var aeState: Int? = null
    @Volatile private var turn = OrientationEventListener.ORIENTATION_UNKNOWN   // how the phone is held (for a board read sideways)
    private var tilt: OrientationEventListener? = null
    @Volatile private var finished = false
    @Volatile private var shooting = false
    private var openedAt = 0L
    private var previewSurface: Surface? = null
    private var faceMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    @Volatile private var torch = false

    /** What the camera sees right now, for the pet's coaching (filled only without a window). */
    class Seen { @Volatile var light = -1; @Volatile var change = 0f; @Volatile var frames = 0 }
    val seen = Seen()
    /** Faces in the picture; -1 when this camera can't tell. */
    @Volatile var faces = -1; private set
    /** The back camera has a light (flash) it can keep on. */
    var canLight = false; private set
    private var grid: IntArray? = null
    private var lastLook = 0L

    @SuppressLint("MissingPermission")   // the camera permission is asked for before this is opened
    fun open() {
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val want = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val ids = cm.cameraIdList
            val id = ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: ids.firstOrNull() ?: return finish(null)       // a tablet with one camera: use that one
            val ch = cm.getCameraCharacteristics(id)
            front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: if (front) 270 else 90
            autoFocus = (ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)).contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            canLight = !front && ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (front && view == null) {
                val fd = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0)
                faceMode = when {
                    fd.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                    fd.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
                    else -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
                }
            }
            openedAt = System.currentTimeMillis()
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return finish(null)
            // a little bigger from the back camera, so small letters on a board can still be read
            val most = if (front) 1600 * 1200 else 2592 * 1944
            val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return finish(null)
            val still = sizes.filter { it.width * it.height <= most }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height } ?: return finish(null)
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
            tilt = object : OrientationEventListener(ctx) {
                override fun onOrientationChanged(o: Int) { if (o != ORIENTATION_UNKNOWN) turn = o }
            }.also { try { if (it.canDetectOrientation()) it.enable() } catch (e: Exception) { } }
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; if (finished) d.close() else preview(prev) }
                override fun onDisconnected(d: CameraDevice) { d.close(); finish(null) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); finish(null) }
            }, bg)
        } catch (e: Exception) { finish(null) }
    }

    private val watch = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            afState = result.get(CaptureResult.CONTROL_AF_STATE); aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            if (faceMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) faces = result.get(CaptureResult.STATISTICS_FACES)?.size ?: 0
        }
    }

    private fun preview(prev: Size) {
        try {
            val v = view
            val surface: Surface = if (v != null) {
                val st = v.surfaceTexture ?: return finish(null)
                st.setDefaultBufferSize(prev.width, prev.height)
                Surface(st)
            } else ImageReader.newInstance(small.width, small.height, ImageFormat.YUV_420_888, 3).also { s ->
                s.setOnImageAvailableListener({ r ->
                    val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
                    if (img != null) try { measure(img) } catch (e: Exception) { } finally { img.close() }
                }, bg)
                sink = s
            }.surface
            val d = device ?: return finish(null)
            val r = reader ?: return finish(null)
            previewSurface = surface
            if (v != null) main.post { fit(prev) }
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    if (!repeat()) finish(null)
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

    /** (Re)starts the live picture: focus, light, the torch when it is on, and face finding. */
    private fun repeat(): Boolean = try {
        val d = device; val s = session; val surface = previewSurface
        if (d == null || s == null || surface == null) false else {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if (autoFocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (torch) { set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH) }
                if (faceMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
            }
            s.setRepeatingRequest(req.build(), watch, bg); true
        }
    } catch (e: Exception) { false }

    /** Turns the back camera's light on (it goes off by itself when the camera closes). */
    fun light(on: Boolean) { if (canLight) bg.post { if (!finished && !shooting && torch != on) { torch = on; repeat() } } }

    /** How bright the picture is and how much it moved since the last frame: every 8th dot of a small copy, a few times a second. */
    private fun measure(img: android.media.Image) {
        val now = System.currentTimeMillis()
        if (now - lastLook < 120) return
        lastLook = now
        val p = img.planes[0]; val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
        val step = 8; val gw = img.width / step; val gh = img.height / step
        if (gw < 2 || gh < 2) return
        val g = IntArray(gw * gh); var sum = 0L
        for (y in 0 until gh) { val row = y * step * rs; for (x in 0 until gw) { val v = buf.get(row + x * step * ps).toInt() and 0xFF; g[y * gw + x] = v; sum += v } }
        val before = grid
        var moved = 0L
        if (before != null && before.size == g.size) for (i in g.indices) moved += abs(g[i] - before[i])
        grid = g
        seen.light = (sum / g.size).toInt()
        seen.change = if (before == null) 0f else moved.toFloat() / g.size
        seen.frames++
    }

    /** Focus has settled (or the camera has no autofocus, or it has said nothing for 2 seconds). */
    fun focusOk(): Boolean {
        val af = afState
        return !autoFocus || af == null || (af != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN &&
            (af != CaptureResult.CONTROL_AF_STATE_INACTIVE || System.currentTimeMillis() - openedAt > 2000))
    }
    /** The camera can't get it sharp: usually it's too close. */
    fun focusTrouble(): Boolean {
        val af = afState
        return autoFocus && (af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED)
    }
    /** Brightness has settled. */
    fun lightOk(): Boolean = aeState.let { it == null || it != CaptureResult.CONTROL_AE_STATE_SEARCHING }

    /** Takes the photo now (as soon as the camera is open). */
    fun shootNow() = snap(0)

    /** Sharp (focus has settled) and bright enough (exposure has settled), or the camera can't tell. */
    private fun steady(): Boolean {
        val af = afState; val ae = aeState
        val focusOk = !autoFocus || af == null || (af != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && af != CaptureResult.CONTROL_AF_STATE_INACTIVE)
        val lightOk = ae == null || ae != CaptureResult.CONTROL_AE_STATE_SEARCHING
        return focusOk && lightOk
    }

    /** Takes the one photo: as soon as the picture is steady (at most [waitMs] more), even if the camera is still opening. */
    fun snap(waitMs: Long = 1500) {
        val until = System.currentTimeMillis() + waitMs
        val giveUp = System.currentTimeMillis() + waitMs + 4000          // the camera never opened
        bg.post(object : Runnable {
            override fun run() {
                if (finished || shooting) return
                val now = System.currentTimeMillis()
                if (session == null) { if (now > giveUp) finish(null) else bg.postDelayed(this, 80); return }
                if (!steady() && now < until) { bg.postDelayed(this, 80); return }
                shoot()
            }
        })
    }

    private fun shoot() {
        try {
            val d = device ?: return finish(null)
            val s = session ?: return finish(null)
            val r = reader ?: return finish(null)
            shooting = true
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if (autoFocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (torch) { set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH) }
                set(CaptureRequest.JPEG_ORIENTATION, upright())
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }
            s.capture(req.build(), null, bg)
            bg.postDelayed({ finish(null) }, 6000)                     // nothing came: give up
        } catch (e: Exception) { finish(null) }
    }

    /** Turns the photo the way the phone is held, so a board read with the phone sideways is still upright. */
    private fun upright(): Int {
        val o = turn
        val d = if (o == OrientationEventListener.ORIENTATION_UNKNOWN) 0 else ((o + 45) / 90 * 90) % 360
        return if (front) (sensor - d + 360) % 360 else (sensor + d) % 360
    }

    fun cancel() = finish(null)

    private fun finish(b: ByteArray?) {
        if (finished) return
        finished = true
        try { session?.close() } catch (e: Exception) { }
        try { device?.close() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { sink?.close() } catch (e: Exception) { }
        main.post { try { tilt?.disable() } catch (e: Exception) { } }
        thread.quitSafely()
        main.post { done(b) }
    }
}
