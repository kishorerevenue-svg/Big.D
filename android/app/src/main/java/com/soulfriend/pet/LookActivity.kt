package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Color
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        // the camera click for the back camera only, so you know you can lower the phone
        // (when the pet looks at YOU it is quiet, like a friend just looking)
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
    private var again = false
    private var coach: EyesCoach? = null
    private var shot = false
    private var sensors: SensorManager? = null
    @Volatile private var spin = -1f    // how fast the phone is turning (rad/s), -1 = no gyroscope
    private val gyro = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values; val m = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
            spin = if (spin < 0f) m else spin * 0.6f + m * 0.4f
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAt = System.currentTimeMillis()
        store = Store(this); ui = Ui(this)
        back = intent.getBooleanExtra("back", true)
        ask = intent.getStringExtra("ask") ?: ""
        again = intent.getBooleanExtra("again", false)
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
        if (store.voiceOn && back) shutter
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
            again && te -> "👀 మళ్ళీ చూస్తున్నాను…"
            again -> "👀 Looking again…"
            back && te -> "👀 చూస్తున్నాను… ఫోన్‌ని కదలకుండా పట్టుకోండి"
            back -> "👀 Looking… hold the phone steady"
            te -> "👀 మిమ్మల్ని చూస్తున్నాను…"
            else -> "👀 Looking at you…"
        }
        val c = MirrorCam(this, null, back) { jpeg -> got(jpeg) }
        cam = c
        c.open()
        coach = EyesCoach(back, c.canLight, SystemClock.uptimeMillis())
        sensors = getSystemService(SensorManager::class.java)?.also { sm ->
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { g -> try { sm.registerListener(gyro, g, SensorManager.SENSOR_DELAY_UI) } catch (e: Exception) { } }
        }
        h.postDelayed(tick, 150)
    }

    /** A few times a second: like a friend holding the phone with you, say what's needed and take the photo when it's good. */
    private val tick = object : Runnable {
        override fun run() {
            val c = cam ?: return; val k = coach ?: return
            if (sent || shot) return
            val st = k.step(SystemClock.uptimeMillis(), spin, c.seen.light, c.seen.change, c.focusOk(), c.focusTrouble(), c.lightOk(), c.faces)
            if (st.torch) c.light(true)
            st.hint?.let { coachSays(it) }
            if (st.shoot) { shot = true; stopSensors(); note?.text = if (te) "📸 …" else "📸 …"; c.shootNow(); return }
            h.postDelayed(this, 150)
        }
    }

    private fun coachSays(k: String) {
        val (icon, words) = when (k) {
            "light" -> "🔦" to (if (te) "కొంచెం చీకటిగా ఉంది, లైట్ వేస్తున్నాను." else "It's a bit dark, I'm turning on the light.")
            "dark" -> "🌙" to (if (te) "ఇక్కడ చాలా చీకటిగా ఉంది. కొంచెం వెలుతురు దగ్గరికి వెళ్ళండి." else "It's too dark here. Please go near some light.")
            "still" -> "🤚" to (if (te) "ఫోన్ కదలకుండా పట్టుకోండి, ఊపకండి." else "Hold the phone still, don't shake it.")
            "close" -> "↩️" to (if (te) "చాలా దగ్గరగా ఉంది! ఫోన్ కొంచెం వెనక్కి జరపండి." else "Too close! Move the phone back a little.")
            "face" -> "🙂" to (if (te) "మీరు కనిపించడం లేదు. ఫోన్‌ని మీ ముఖం ముందు పట్టుకోండి." else "I can't see you. Hold the phone in front of your face.")
            else -> return
        }
        note?.text = "$icon $words"
        petSays(words, "surprised")
    }

    /** The pet says it out loud (with the phone's own voice, so it's quick). */
    private fun petSays(words: String, mood: String) {
        try {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_SAY)
                .putExtra("text", words).putExtra("prefix", "👀 ").putExtra("mood", mood).putExtra("local", true))
        } catch (e: Exception) { }
    }

    private fun stopSensors() { try { sensors?.unregisterListener(gyro) } catch (e: Exception) { }; sensors = null }

    private fun got(jpeg: ByteArray?) {
        cam = null
        if (sent) return
        if (jpeg == null) { done(null, if (te) "కెమెరా వాడలేకపోయాను. వేరే కెమెరా యాప్ మూసి మళ్ళీ అడగండి." else "I couldn't use the camera. Close other camera apps and ask me again."); return }
        if (store.voiceOn && back) try { shutter.play(MediaActionSound.SHUTTER_CLICK) } catch (e: Exception) { }
        note?.text = if (te) "🐾 చూశాను! ఆలోచిస్తున్నాను…" else "🐾 Got it! Thinking…"
        if (coach?.talked == true) petSays(if (te) "సరే, చూశాను!" else "Got it!", "happy")
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
        h.removeCallbacks(tick); stopSensors()
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
        h.removeCallbacksAndMessages(null); stopSensors()
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
