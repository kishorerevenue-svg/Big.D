package com.soulfriend.pet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * The call screen (CallManager has the call). Ringing: Decline / Answer. Calling and on the call: the name, the time,
 * and 🔇 mute, 🔊 speaker, 📹 camera on/off, 🔄 front/back camera, and the red button to end.
 * The call itself runs in a WebView page (assets/call.html, WebRTC) behind these buttons; for video it shows the
 * other person full screen and you in a small box.
 * Back keeps the call going (a notification brings you back here); the red button ends it.
 */
class CallActivity : Activity() {
    companion object {
        private const val ORIGIN = "https://call.bigdai.tech/"   // our own name: the page is loaded from the app, nothing is fetched
        private const val REQ_PERMS = 7501
    }

    private lateinit var store: Store
    private lateinit var ui: Ui
    private val main = Handler(Looper.getMainLooper())
    private var call: CallManager.Call? = null
    private lateinit var web: WebView
    private lateinit var shade: View
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var face: TextView
    private lateinit var controls: LinearLayout
    private var pageReady = false
    private var started = false          // the page got start(...)
    private var answerSent = false
    private var answerApplied = false
    private var muted = false
    private var speaker = false
    private var camOn = true
    private var wantAnswer = false
    private var proximity: PowerManager.WakeLock? = null
    private var audioSet = false
    private var failTimer: Runnable? = null
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)
    private val refresh: () -> Unit = { onCallChanged() }
    private val tick = object : Runnable { override fun run() { showStatus(); main.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this)
        call = CallManager.active
        val c = call
        if (c == null || c.state == "ended") { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        speaker = c.video
        build(c)
        CallManager.listeners.add(refresh)
        ChatSync.hold(this, "call")          // the other phone's answer and hang-up come through Habit Chat
        wantAnswer = intent.getBooleanExtra("answer", false)
        if (!c.incoming) begin()
        else if (wantAnswer) answer()
        main.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("answer", false)) answer()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        CallManager.listeners.remove(refresh)
        ChatSync.release("call")
        val c = call
        // the call lives in this screen's page: if the screen goes away, the call ends
        if (c != null && c.state != "ended" && ::web.isInitialized) CallManager.end(applicationContext, c, if (c.connectedAt > 0) "hangup" else if (c.incoming) "decline" else "cancel", true)
        if (::web.isInitialized) { try { web.evaluateJavascript("hangup()", null) } catch (e: Exception) { }; try { web.destroy() } catch (e: Exception) { } }
        audioOff()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = call
        if (c != null && c.state != "ended" && (c.connectedAt > 0 || !c.incoming)) moveTaskToBack(true)   // the call goes on
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // =================== the screen ===================
    @SuppressLint("SetJavaScriptEnabled")
    private fun build(c: CallManager.Call) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val ok = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                        if (request.origin.toString().startsWith(ORIGIN.trimEnd('/')) && ok.isNotEmpty()) request.grant(ok.toTypedArray()) else request.deny()
                    }
                }
            }
            webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true }
            addJavascriptInterface(Bridge(), "Habit")
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        // for voice calls a calm teal screen covers the page
        shade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0E6B6B"), Color.parseColor("#082F33"))) }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(48), dp(20), 0) }
        val conv = ChatDb.get(this).conv(c.cid)
        top.addView(ui.text("🔒 Habit · " + t("locked end to end", "ఎండ్ టు ఎండ్ లాక్"), 12.5f, Color.parseColor("#BFE3DE")).apply { gravity = Gravity.CENTER })
        nameView = ui.text(conv?.name ?: "Habit", 26f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 1f, Color.BLACK) }
        top.addView(nameView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        statusView = ui.text("", 15f, Color.parseColor("#DDF2EF")).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 1f, Color.BLACK) }
        top.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        face = TextView(this).apply { text = conv?.emoji ?: "🐾"; textSize = 64f; gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#33FFFFFF"), 70) }
        top.addView(face, LinearLayout.LayoutParams(dp(140), dp(140)).apply { topMargin = dp(36) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(14), dp(12), dp(36)) }
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        val html = try { assets.open("call.html").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
        web.loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null)
        drawControls()
        showStatus()
    }

    private fun roundBtn(label: String, bg: Int, size: Int = 60, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = if (size >= 70) 28f else 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = ui.round(bg, size / 2); contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(9); marginEnd = dp(9) }
        setOnClickListener { onClick() }
    }

    private fun drawControls() {
        val c = call ?: return
        controls.removeAllViews()
        val dim = Color.parseColor("#55FFFFFF"); val on = Color.parseColor("#E6FFFFFF")
        if (c.incoming && c.state == "ringing" && !c.answered) {
            controls.addView(roundBtn("✕", Ui.DANGER, 74, t("Decline", "తిరస్కరించు")) { CallManager.end(applicationContext, c, "decline", true) })
            controls.addView(View(this), LinearLayout.LayoutParams(dp(60), 1))
            controls.addView(roundBtn(if (c.video) "🎥" else "📞", Ui.GOOD, 74, t("Answer", "ఎత్తండి")) { answer() })
            return
        }
        controls.addView(roundBtn(if (muted) "🔇" else "🎤", if (muted) on else dim, desc = t("Mute", "మ్యూట్")) {
            muted = !muted; js("setMute($muted)"); drawControls() }.apply { if (muted) setTextColor(Color.BLACK) })
        controls.addView(roundBtn("🔊", if (speaker) on else dim, desc = t("Speaker", "స్పీకర్")) { speaker = !speaker; route(); drawControls() })
        if (c.video) {
            controls.addView(roundBtn(if (camOn) "📹" else "🚫", if (camOn) dim else on, desc = t("Camera on or off", "కెమెరా")) { camOn = !camOn; js("setCam($camOn)"); drawControls() })
            controls.addView(roundBtn("🔄", dim, desc = t("Switch camera", "కెమెరా మార్చు")) { js("flip()") })
        }
        controls.addView(roundBtn("✆", Ui.DANGER, 66, t("End call", "కాల్ ముగించు")) {
            CallManager.end(applicationContext, c, if (c.connectedAt > 0) "hangup" else "cancel", true) })
    }

    private fun showStatus() {
        val c = call ?: return
        val video = c.video && c.state != "ringing" && c.state != "ended"
        shade.visibility = if (video && c.connectedAt > 0) View.GONE else View.VISIBLE
        face.visibility = if (video && c.connectedAt > 0) View.GONE else View.VISIBLE
        statusView.text = when (c.state) {
            // no "ringing" from their phone yet: Habit may be closed there (then it rings only with Firebase push set up)
            "calling" -> if (System.currentTimeMillis() - c.startedAt < 9000) t("Calling…", "కాల్ చేస్తున్నాం…")
                else t("Calling… their phone isn't ringing yet.\nIf Habit is closed there, they'll see a missed call.",
                       "కాల్ చేస్తున్నాం… వారి ఫోన్ ఇంకా రింగ్ అవ్వలేదు.\nవారి ఫోన్‌లో Habit మూసి ఉంటే, మిస్డ్ కాల్ కనిపిస్తుంది.")
            "ringing" -> if (c.incoming) (if (c.answered) t("Connecting…", "కలుపుతున్నాం…") else if (c.video) t("Incoming video call", "వీడియో కాల్ వస్తోంది") else t("Incoming voice call", "వాయిస్ కాల్ వస్తోంది"))
                else t("Ringing…", "రింగ్ అవుతోంది…")
            "connecting" -> t("Connecting…", "కలుపుతున్నాం…")
            "reconnecting" -> t("Reconnecting…", "మళ్ళీ కలుపుతున్నాం…")
            "connected" -> { val s = ((System.currentTimeMillis() - c.connectedAt) / 1000).toInt(); "%d:%02d".format(s / 60, s % 60) }
            else -> endWords(c)
        }
    }

    private fun endWords(c: CallManager.Call) = when (c.reason.removePrefix("their-")) {
        "decline" -> if (c.reason.startsWith("their-")) t("Declined", "తిరస్కరించారు") else t("Call ended", "కాల్ ముగిసింది")
        "busy" -> t("Busy on another call", "వేరే కాల్‌లో ఉన్నారు")
        "timeout" -> if (c.incoming) t("Missed call", "మిస్డ్ కాల్") else t("No answer", "జవాబు లేదు")
        "failed" -> t("Couldn't connect. Check the internet.", "కనెక్ట్ కాలేదు. ఇంటర్నెట్ చూడండి.")
        else -> t("Call ended", "కాల్ ముగిసింది")
    }

    private fun onCallChanged() {
        val c = call ?: return
        if (c.state == "ended") {
            showStatus(); controls.removeAllViews()
            js("hangup()")
            audioOff()
            main.postDelayed({ finishAndRemoveTask() }, 1300)
            return
        }
        // their answer came: give it to the page
        if (!c.incoming && c.answer.isNotEmpty() && started && !answerApplied) {
            answerApplied = true; js("remoteAnswer(${JSONObject.quote(c.answer)})")
            failTimer?.let { main.postDelayed(it, 30_000) }      // answered, but no sound path within 30 s: give up
        }
        showStatus(); drawControls()
    }

    // =================== starting the page call ===================
    private fun needed(c: CallManager.Call) = (if (c.video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Caller: start at once. Callee: after Answer. */
    private fun begin() {
        val c = call ?: return
        val miss = needed(c)
        if (miss.isNotEmpty()) { requestPermissions(miss.toTypedArray(), REQ_PERMS); return }
        if (!pageReady || started) return
        started = true
        audioOn()
        Thread {
            val r = ChatSync.api(store, "GET", "/api/chat/turn")
            val ice = r.json.optJSONArray("ice") ?: JSONArray().put(JSONObject().put("urls", JSONArray().put("stun:stun.l.google.com:19302")))
            val cfg = JSONObject().put("role", if (c.incoming) "callee" else "caller").put("video", c.video).put("ice", ice)
            if (c.incoming) cfg.put("offer", c.offer)
            runOnUiThread { js("start($cfg)") }
        }.start()
        // no sound path within 30 s of starting: give up
        failTimer = Runnable { if (call?.state != "connected" && call?.state != "ended") CallManager.end(applicationContext, c, "failed", true) }
        if (c.incoming) main.postDelayed(failTimer!!, 30_000)
    }

    private fun answer() {
        val c = call ?: return
        if (!c.incoming || c.state != "ringing" || c.answered) return
        c.answered = true
        CallManager.stopRinging(this)
        drawControls(); showStatus()
        begin()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val c = call ?: return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) begin()
        else {
            Toast.makeText(this, t("Habit needs the microphone" + (if (c.video) " and camera" else "") + " for calls.", "కాల్స్ కోసం Habit కి మైక్" + (if (c.video) ", కెమెరా" else "") + " అనుమతి కావాలి."), Toast.LENGTH_LONG).show()
            CallManager.end(applicationContext, c, if (c.incoming) "decline" else "cancel", true)
        }
    }

    private fun js(code: String) { try { web.evaluateJavascript(code, null) } catch (e: Exception) { } }

    inner class Bridge {
        @JavascriptInterface fun ready() { runOnUiThread { pageReady = true; val c = call ?: return@runOnUiThread; if (!c.incoming || c.answered) begin() } }
        @JavascriptInterface fun local(type: String, sdp: String) {
            runOnUiThread {
                val c = call ?: return@runOnUiThread
                if (c.state == "ended") return@runOnUiThread
                if (type == "offer" && !c.incoming) CallManager.send(this@CallActivity, c, "offer", JSONObject().put("sdp", sdp))
                else if (type == "answer" && c.incoming && !answerSent) {
                    answerSent = true
                    CallManager.send(this@CallActivity, c, "answer", JSONObject().put("sdp", sdp))
                    c.state = "connecting"; CallManager.changed()
                }
                onCallChanged()   // (an answer may have come already)
            }
        }
        @JavascriptInterface fun state(s: String) {
            runOnUiThread {
                val c = call ?: return@runOnUiThread
                if (c.state == "ended") return@runOnUiThread
                when {
                    s == "connected" -> { failTimer?.let { main.removeCallbacks(it) }; CallManager.connected(applicationContext, c); route() }
                    s == "reconnecting" && c.connectedAt > 0 -> { c.state = "reconnecting"; CallManager.changed() }
                    s == "failed" || s.startsWith("error") -> {
                        if (s.startsWith("error:NotAllowed") || s.startsWith("error:NotReadable")) Toast.makeText(this@CallActivity,
                            t("The microphone or camera is busy (the pet may be listening). Try again.", "మైక్ లేదా కెమెరా బిజీగా ఉంది. మళ్ళీ ప్రయత్నించండి."), Toast.LENGTH_LONG).show()
                        CallManager.end(applicationContext, c, "failed", true)
                    }
                    s == "closed" -> CallManager.end(applicationContext, c, "hangup", true)
                }
            }
        }
        @JavascriptInterface fun log(s: String) { Log.d("HabitCall", s) }
    }

    // =================== sound: earpiece or speaker ===================
    private fun audioOn() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try { am.mode = AudioManager.MODE_IN_COMMUNICATION; audioSet = true } catch (e: Exception) { }
        route()
    }
    private fun route() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val want = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                // a wired or Bluetooth headset wins when there is one
                val head = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                val dev = if (!speaker && head != null) head else am.availableCommunicationDevices.firstOrNull { it.type == want }
                if (dev != null) am.setCommunicationDevice(dev)
            } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = speaker }
        } catch (e: Exception) { }
        // voice call at the ear: the screen goes dark so the cheek doesn't press buttons
        val c = call
        val ear = c != null && !c.video && !speaker && c.connectedAt > 0 && c.state != "ended"
        try {
            if (ear && proximity == null) {
                val pm = getSystemService(PowerManager::class.java)
                if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                    proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "habit:call").apply { acquire(3 * 3600_000L) }
            } else if (!ear) { proximity?.let { if (it.isHeld) it.release() }; proximity = null }
        } catch (e: Exception) { }
    }
    private fun audioOff() {
        try { proximity?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        proximity = null
        if (!audioSet) return
        audioSet = false
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) { }
    }
}
