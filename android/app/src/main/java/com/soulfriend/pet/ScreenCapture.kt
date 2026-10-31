package com.soulfriend.pet

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * "Read my screen": Android asks the user ("Start capturing?") every time, then we take ONE picture of the
 * screen and give it to the pet. Nothing is recorded or stored. Secure screens (banking, passwords, our Vault) come out black.
 */
class ScreenCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            try { @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 9) }
            catch (e: Exception) { cancelled(); finish() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            try { startForegroundService(Intent(this, ScreenCaptureService::class.java).putExtra("code", resultCode).putExtra("data", data)) }
            catch (e: Exception) { cancelled() }
        } else cancelled()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun cancelled() {
        try { startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_SCREEN_DONE)) } catch (e: Exception) { }
    }
}

class ScreenCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { done(null); return START_NOT_STICKY }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: run { done(null); return START_NOT_STICKY }
            projection = p
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { cleanup() } }, main)
            val (w, h, dpi) = size()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            display = p.createVirtualDisplay("bigd-screen", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
            // give the "Start capturing?" box time to disappear, then grab a frame
            main.postDelayed({ grab(0) }, 700)
            main.postDelayed({ if (!finished) done(null) }, 8000)
        } catch (e: Exception) { done(null) }
        return START_NOT_STICKY
    }

    private fun size(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val dm = resources.displayMetrics
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() } else dm.widthPixels to dm.heightPixels
        // half size is plenty for reading text and keeps it fast
        val scale = if (maxOf(w, h) > 2000) 0.5f else 0.75f
        return Triple((w * scale).toInt().coerceAtLeast(320), (h * scale).toInt().coerceAtLeast(320), (dm.densityDpi * scale).toInt())
    }

    private fun grab(tries: Int) {
        if (finished) return
        val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) { if (tries < 10) main.postDelayed({ grab(tries + 1) }, 150) else done(null); return }
        val file = try {
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val crop = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            val s = 1568f / maxOf(crop.width, crop.height)
            val out = if (s < 1f) Bitmap.createScaledBitmap(crop, (crop.width * s).toInt(), (crop.height * s).toInt(), true) else crop
            File(cacheDir, "screen.jpg").also { f -> FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        } catch (e: Exception) { null } finally { img.close() }
        done(file)
    }

    private fun done(file: File?) {
        if (finished) return
        finished = true
        cleanup()
        try {
            val i = Intent(this, PetService::class.java)
            if (file != null) i.setAction(PetService.ACTION_PHOTO).putExtra("path", file.absolutePath).putExtra("screen", true)
            else i.setAction(PetService.ACTION_SCREEN_DONE).putExtra("failed", true)
            startService(i)
        } catch (e: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try { display?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        display = null; reader = null; projection = null
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("screen") == null)
            nm.createNotificationChannel(NotificationChannel("screen", "Reading your screen", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "screen").setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("BigD AI is reading your screen once").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(9, n)
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }

    companion object {
        /** Words that mean "look at my screen". */
        private val ASK = Regex("(my|this|the) screen|on (my )?screen|screen (lo|meeda|pai)|స్క్రీన్|స్క్రీను", RegexOption.IGNORE_CASE)
        fun wantsScreen(text: String) = ASK.containsMatchIn(text)
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, ScreenCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
    }
}
