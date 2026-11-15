package com.soulfriend.pet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock

/**
 * Head control for the video screen: the front camera's own face finder says where the face is (a rectangle), and a
 * quick move of the head up or down is "next" or "back" ([onNod]). No picture is looked at, kept or sent anywhere: the
 * frames are thrown away as they come and only the rectangle's place is used.
 */
class HeadWatch(private val ctx: Context, private val onNod: (up: Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var on = false

    /** False: the camera isn't allowed, or this phone's front camera can't find faces by itself. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (on) return true
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        try {
            val cm = ctx.getSystemService(CameraManager::class.java) ?: return false
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT } ?: return false
            val ch = cm.getCameraCharacteristics(id)
            val modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0)
            val mode = when {
                modes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                modes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                else -> return false
            }
            val turn = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888) ?: return false
            val size = sizes.filter { it.width >= 176 }.minByOrNull { it.width * it.height } ?: return false
            val t = HandlerThread("habit-head").also { it.start(); thread = it }
            val h = Handler(t.looper)
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { reader = it }
            r.setOnImageAvailableListener({ x -> try { x.acquireLatestImage()?.close() } catch (e: Exception) { } }, h)   // the pictures aren't used
            on = true
            base = Double.NaN
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (!on) { cam.close(); return }
                    device = cam
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface); set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, mode)
                        }
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!on) { s.close(); return }
                                session = s
                                try {
                                    s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, res: TotalCaptureResult) {
                                            face(res.get(CaptureResult.STATISTICS_FACES), turn)
                                        }
                                    }, h)
                                } catch (e: Exception) { }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { }
                        }, h)
                    } catch (e: Exception) { }
                }
                override fun onDisconnected(cam: CameraDevice) { try { cam.close() } catch (e: Exception) { }; device = null }
                override fun onError(cam: CameraDevice, error: Int) { try { cam.close() } catch (e: Exception) { }; device = null }
            }, h)
            return true
        } catch (e: Exception) { stop(); return false }
    }

    private var base = Double.NaN          // where the face rests (it follows slow moves)
    private var quietUntil = 0L
    private var lastAt = 0L

    private fun face(faces: Array<Face>?, turn: Int) {
        val b = faces?.maxByOrNull { it.bounds.width() * it.bounds.height() }?.bounds ?: return
        // how high the face is on the upright picture, counted in face heights (the camera sits turned inside the phone:
        // [turn] is how far its picture is turned). A smaller number is higher up.
        val (y, size) = when (turn) {
            90 -> b.exactCenterX().toDouble() to b.width()
            270 -> -b.exactCenterX().toDouble() to b.width()
            180 -> -b.exactCenterY().toDouble() to b.height()
            else -> b.exactCenterY().toDouble() to b.height()
        }
        if (size < 20) return
        val pos = y / size
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastAt
        if (base.isNaN() || dt > 1500) { base = pos; lastAt = now; return }      // the face just came (back) into view
        lastAt = now
        val d = base - pos                                                       // above 0: the head went up
        if (now > quietUntil && Math.abs(d) > 0.16) {
            quietUntil = now + 1500; base = pos
            val up = d > 0
            main.post { if (on) onNod(up) }
        } else {
            // follows slow moves (holding the phone differently); right after a nod it follows fast, so the way back isn't a nod
            val a = (dt / (if (now > quietUntil) 1500.0 else 250.0)).coerceAtMost(1.0)
            base += (pos - base) * a
        }
    }

    fun stop() {
        on = false
        try { session?.close() } catch (e: Exception) { }
        try { device?.close() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { thread?.quitSafely() } catch (e: Exception) { }
        session = null; device = null; reader = null; thread = null
    }
}
