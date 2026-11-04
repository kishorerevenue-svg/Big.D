package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The pet's eyes, opened by the floating pet when you say "look at this…". A see-through screen with only a small note
 * at the top (the app you were in stays behind it): the camera takes ONE photo by itself (no camera window; Android
 * shows its green camera dot), makes it smaller and upright, gives it to the pet, and closes. Nothing is saved.
 */
class LookActivity : Activity() {
    companion object {
        private const val REQ_CAMERA = 31
        /** When this screen last opened: the pet checks it, because some phones stop apps from opening screens from the background. */
        @Volatile var openedAt = 0L
        // the usual camera click, so you know the photo is taken and you can lower the phone
        private val shutter by lazy { MediaActionSound().apply { try { load(MediaActionSound.SHUTTER_CLICK) } catch (e: Exception) { } } }
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var ui: Ui
    private var note: TextView? = null
    private var cam: MirrorCam? = null
    private var back = true
    private var ask = ""
    private var te = false
    private var asking = false          // Android's camera permission question is showing
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAt = System.currentTimeMillis()
        store = Store(this); ui = Ui(this)
        back = intent.getBooleanExtra("back", true)
        ask = intent.getStringExtra("ask") ?: ""
        te = store.lang == "te" || ask.any { it in 'ఀ'..'౿' }
        // only the note at the top; touches elsewhere go to the app behind
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(12), ui.dp(10), ui.dp(12))
            background = ui.round(Color.WHITE, 20, Ui.LINE); elevation = ui.dp(10).toFloat()
            addView(TextView(this@LookActivity).apply { text = "🐾"; textSize = 26f })
            note = ui.text(if (te) "👀 కెమెరా తెరుస్తున్నాను…" else "👀 Opening my eyes…", 15f, Ui.INK, bold = true)
            addView(note, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            addView(TextView(this@LookActivity).apply {
                text = "✕"; textSize = 18f; setTextColor(Ui.MUTED); setPadding(ui.dp(12), ui.dp(4), ui.dp(6), ui.dp(4)); contentDescription = "Stop"
                setOnClickListener { done(null, if (te) "సరే, చూడటం ఆపాను." else "Okay, I stopped looking.") }
            })
        }
        setContentView(FrameLayout(this).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(6))
            addView(banner, FrameLayout.LayoutParams(-1, -2))
            // below the status bar (the clock and battery), whatever the phone draws there
            setOnApplyWindowInsetsListener { v, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else @Suppress("DEPRECATION") insets.systemWindowInsetTop
                v.setPadding(ui.dp(12), top + ui.dp(6), ui.dp(12), ui.dp(6)); insets
            }
        })
        if (savedInstanceState != null) { done(null, ""); return }   // Android closed us in between: start again next time
        if (store.voiceOn) shutter
        if (!store.eyesAsked) consent() else start()
    }

    /** The first time only: what happens with the camera, before it is ever used. */
    private fun consent() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(if (te) "👀 కెమెరాతో చూడనా?" else "👀 Can I use the camera as my eyes?")
            .setMessage(if (te)
                "మీరు \"ఇది చూడు\", \"ఈ బోర్డు మీద ఏం రాసి ఉంది\", \"నా షర్ట్ రంగు ఏంటి\" అని అడిగినప్పుడు, నేను కెమెరాతో ఒక్క ఫోటో తీసి చూసి చెప్తాను. కెమెరా విండో తెరవదు; Android చిన్న పచ్చ చుక్క చూపిస్తుంది.\n\n" +
                    "ఆ ఫోటో మీకు జవాబు చెప్పడానికి మాత్రమే Claude AIకి వెళ్తుంది, ఎక్కడా సేవ్ అవ్వదు. మీరు అడిగినప్పుడు మాత్రమే చూస్తాను."
            else
                "When you ask me to look (\"look at this shirt\", \"what's written on this board?\", \"what colour is my shirt?\"), I take ONE photo with the camera and tell you what I see. No camera window opens; Android shows its small green camera dot.\n\n" +
                    "The photo goes to Claude AI only to answer you, and it is not saved. I look only when you ask.")
            .setPositiveButton(if (te) "సరే, చూడు" else "Yes, you can look") { _, _ -> store.eyesAsked = true; start() }
            .setNegativeButton(if (te) "వద్దు" else "No, thanks") { _, _ -> done(null, if (te) "సరే, కెమెరా వాడను." else "Okay, I won't use the camera.") }
            .setOnCancelListener { done(null, "") }
            .show()
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            asking = true; requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA); return
        }
        open()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        asking = false
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) open()
        else done(null, if (te) "చూడటానికి కెమెరా అనుమతి కావాలి. Settings › Apps › BigD AI › Permissions లో Camera ఆన్ చేయండి."
                        else "I need the camera permission to look. Turn on Camera in Settings › Apps › BigD AI › Permissions.")
    }

    private fun open() {
        note?.text = when {
            back && te -> "👀 చూస్తున్నాను… ఫోన్‌ని కదలకుండా పట్టుకోండి"
            back -> "👀 Looking… hold the phone steady"
            te -> "👀 మిమ్మల్ని చూస్తున్నాను…"
            else -> "👀 Looking at you…"
        }
        val c = MirrorCam(this, null, back) { jpeg -> got(jpeg) }
        cam = c
        c.open()
        h.postDelayed({ cam?.snap(1500) }, 700)       // a moment to point the phone; then as soon as it is sharp
    }

    private fun got(jpeg: ByteArray?) {
        cam = null
        if (sent) return
        if (jpeg == null) { done(null, if (te) "కెమెరా వాడలేకపోయాను. వేరే కెమెరా యాప్ మూసి మళ్ళీ అడగండి." else "I couldn't use the camera. Close other camera apps and ask me again."); return }
        if (store.voiceOn) try { shutter.play(MediaActionSound.SHUTTER_CLICK) } catch (e: Exception) { }
        note?.text = if (te) "🐾 చూశాను! ఆలోచిస్తున్నాను…" else "🐾 Got it! Thinking…"
        Thread {
            // upright, smaller, and without hidden details (like the place), before it goes anywhere
            val raw = File(cacheDir, "look-raw.jpg")
            val small = try { raw.writeBytes(jpeg); Images.prepare(this, Uri.fromFile(raw), 1568) } catch (e: Exception) { null } finally { raw.delete() }
            val out = small?.let { b -> try { File(cacheDir, "look.jpg").apply { writeBytes(b) } } catch (e: Exception) { null } }
            runOnUiThread { done(out, if (out == null) (if (te) "ఆ ఫోటో వాడలేకపోయాను. మళ్ళీ అడగండి." else "I couldn't use that photo. Please ask me again.") else "") }
        }.start()
    }

    /** Gives the photo (or what went wrong) to the pet, and closes. */
    private fun done(photo: File?, problem: String) {
        if (sent) return
        sent = true
        cam?.cancel(); cam = null
        try {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_LOOK_DONE)
                .putExtra("path", photo?.absolutePath).putExtra("ask", ask).putExtra("back", back).putExtra("problem", problem))
        } catch (e: Exception) { photo?.delete() }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { done(null, if (te) "సరే, చూడటం ఆపాను." else "Okay, I stopped looking.") }

    override fun onStop() {
        super.onStop()
        // gone from the screen (home button, a call…) while looking: stop the camera
        if (!asking && !isFinishing) done(null, "")
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (!sent) { sent = true; cam?.cancel() }
        super.onDestroy()
    }
}

/** When the AI can't be reached: the phone reads the English letters in the photo by itself (Google's on-phone text reader). */
object EyesOffline {
    /** Blocking: call it off the main thread. null when there are no letters it can read. */
    fun read(jpeg: ByteArray): String? = try {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bmp == null) null else {
            val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val t = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS)
                t.textBlocks.joinToString("\n") { b -> b.lines.joinToString(" ") { it.text } }.trim().takeIf { it.count { c -> c.isLetterOrDigit() } >= 3 }
            } finally { try { rec.close() } catch (e: Throwable) { } }
        }
    } catch (e: Throwable) { null }
}
