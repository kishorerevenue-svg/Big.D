package com.soulfriend.pet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** Keeps the puppy floating on top of other apps, listens, talks, and runs tasks. */
class PetService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var running = false
        const val ACTION_SAY = "com.soulfriend.pet.SAY"
        const val ACTION_STOP = "com.soulfriend.pet.STOP"
        const val ACTION_LISTEN = "com.soulfriend.pet.LISTEN"
        const val ACTION_PHOTO = "com.soulfriend.pet.PHOTO"
        const val ACTION_PET = "com.soulfriend.pet.PET"
        const val ACTION_SCREEN_DONE = "com.soulfriend.pet.SCREEN_DONE"
        private const val CHANNEL = "puppy"
    }

    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private lateinit var brain: Brain
    private lateinit var actions: Actions
    private val main = Handler(Looper.getMainLooper())

    private lateinit var pet: PuppyView
    private lateinit var petParams: WindowManager.LayoutParams
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelText: TextView? = null
    private var panelInput: EditText? = null
    private var panelTools: View? = null
    private var langBtn: Button? = null
    private var voiceBtn: Button? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var teluguVoiceMissingShown = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private var lastQuestion = ""
    private var lastReply = ""
    private var panelContent = ""
    private var utter = 0
    private var photoQuestion = ""

    private val hideBubble = Runnable { removeBubble() }
    private val goSleep = Runnable { if (!listening && !pet.talking) pet.mood = PuppyView.Mood.SLEEPY }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        store = Store(this)
        brain = Brain(store)
        actions = Actions(this) { title, text -> openPanel(title, text) }
        wm = getSystemService(WindowManager::class.java)
        goForeground()
        addPet()
        MessageHub.listener = { m, autoSent -> onMessage(m, autoSent) }
        tts = TextToSpeech(this, this)
        say("Hi! I'm BigD AI. Tap me and talk! To close me, drag me down to the ✕.", PuppyView.Mood.EXCITED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SAY -> say(intent.getStringExtra("text") ?: "",
                intent.getStringExtra("mood")?.let { PuppyView.moodOf(it) } ?: PuppyView.Mood.SURPRISED,
                prefix = intent.getStringExtra("prefix") ?: "⏰ ")
            ACTION_LISTEN -> startListening()
            ACTION_PHOTO -> { showPetAgain(); intent.getStringExtra("path")?.let { sendPhoto(it, intent.getBooleanExtra("screen", false)) } }
            ACTION_SCREEN_DONE -> {
                showPetAgain(); photoQuestion = ""
                if (intent.getBooleanExtra("failed", false)) { pet.mood = PuppyView.Mood.SAD; showBubble("I couldn't read the screen this time. Please try again.", 5000) }
                else { pet.mood = PuppyView.Mood.HAPPY; showBubble("Okay, I won't look at your screen.", 3000) }
            }
            ACTION_PET -> { pet.kind = PuppyView.kindOf(store.petKind); pet.mood = PuppyView.Mood.EXCITED; showBubble("Hi! I'm your ${pet.kind.label.lowercase()} now!", 4000) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        MessageHub.listener = null
        closeMsgCard()
        main.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (e: Exception) { }
        tts?.shutdown()
        removeBubble(); closePanel(); hideCloseTarget()
        try { wm.removeView(pet) } catch (e: Exception) { }
        super.onDestroy()
    }

    // ---------------- foreground notification ----------------
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Floating puppy", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, PetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("BigD AI is on your screen")
            .setContentText("Tap the puppy to talk. Hold it for the menu.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide puppy", stop).build())
            .setOngoing(true)
            .build()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(1, n, type)
            }
            Build.VERSION.SDK_INT >= 30 && mic -> startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else -> startForeground(1, n)
        }
    }

    // ---------------- the puppy window ----------------
    private fun overlayParams(w: Int, h: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPet() {
        pet = PuppyView(this)
        pet.kind = PuppyView.kindOf(store.petKind)
        pet.contentDescription = "BigD AI pet. Tap to talk, hold for menu, drag to the X at the bottom to close."
        val size = dp(112)
        petParams = overlayParams(size, size).apply {
            x = if (store.petX >= 0) store.petX else screenW - size - dp(8)
            y = if (store.petY >= 0) store.petY else screenH / 2
        }
        wm.addView(pet, petParams)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longDone = false
        val longPress = Runnable { longDone = true; wake(); togglePanel() }
        pet.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = petParams.x; startY = petParams.y
                    moved = false; longDone = false
                    main.postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; main.removeCallbacks(longPress); showCloseTarget() }
                    if (moved) {
                        petParams.x = (startX + dx).toInt().coerceIn(0, screenW - pet.width)
                        petParams.y = (startY + dy).toInt().coerceIn(0, screenH - pet.height)
                        wm.updateViewLayout(pet, petParams); placeBubble()
                        highlightClose(overClose())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        val close = overClose()
                        hideCloseTarget()
                        if (close) { stopSelf(); return@setOnTouchListener true }
                        store.petX = petParams.x; store.petY = petParams.y
                    }
                    else if (!longDone && e.actionMasked == MotionEvent.ACTION_UP) { wake(); if (listening) stopListening() else startListening() }
                }
            }
            true
        }
        wake()
    }

    // ---------------- drag to the ✕ to close ----------------
    private var closeTarget: View? = null
    private var closeCircle: TextView? = null
    private val closeSize get() = dp(64)
    private val closeCenterX get() = screenW / 2
    private val closeCenterY get() = screenH - dp(150)

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val circle = TextView(this).apply {
            text = "✕"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC1C2140")); setStroke(dp(2), Color.WHITE) }
        }
        val label = TextView(this).apply {
            text = "Drag here to close"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { setColor(Color.parseColor("#991C2140")); cornerRadius = dp(12).toFloat() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(circle, LinearLayout.LayoutParams(closeSize, closeSize))
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = closeCenterY - closeSize / 2 }
        try { wm.addView(box, p); closeTarget = box; closeCircle = circle } catch (e: Exception) { }
    }

    private fun hideCloseTarget() {
        closeTarget?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        closeTarget = null; closeCircle = null
    }

    private fun overClose(): Boolean {
        if (closeTarget == null) return false
        val cx = petParams.x + pet.width / 2; val cy = petParams.y + pet.height / 2
        val dx = (cx - closeCenterX).toFloat(); val dy = (cy - closeCenterY).toFloat()
        return dx * dx + dy * dy < dp(90).toFloat() * dp(90)
    }

    private var closeHot = false
    private fun highlightClose(hot: Boolean) {
        if (hot == closeHot) return
        closeHot = hot
        val c = closeCircle ?: return
        (c.background as? GradientDrawable)?.setColor(Color.parseColor(if (hot) "#E5484D" else "#CC1C2140"))
        c.animate().scaleX(if (hot) 1.25f else 1f).scaleY(if (hot) 1.25f else 1f).setDuration(120).start()
        if (hot) { pet.mood = PuppyView.Mood.SAD; c.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
        else pet.mood = PuppyView.Mood.HAPPY
    }

    private fun wake() {
        if (pet.mood == PuppyView.Mood.SLEEPY) pet.mood = PuppyView.Mood.HAPPY
        main.removeCallbacks(goSleep); main.postDelayed(goSleep, 180_000)
    }

    // ---------------- speech bubble ----------------
    private fun showBubble(text: String, keepMs: Long = 9000) {
        main.removeCallbacks(hideBubble)
        val b = bubble ?: TextView(this).apply {
            setTextColor(Color.parseColor("#1C2140")); textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10)); maxWidth = dp(250)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.parseColor("#D8DDF0")) }
            elevation = dp(4).toFloat()
            setOnClickListener { openPanel(null, lastReply) }
        }.also { v ->
            bubble = v
            bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            wm.addView(v, bubbleParams)
        }
        b.text = if (text.length > 260) text.take(250) + "…" else text
        b.post { placeBubble() }
        if (keepMs > 0) main.postDelayed(hideBubble, keepMs)
    }

    private fun placeBubble() {
        val b = bubble ?: return; val p = bubbleParams ?: return
        val bw = b.width.takeIf { it > 0 } ?: dp(200); val bh = b.height.takeIf { it > 0 } ?: dp(60)
        p.x = (petParams.x + pet.width / 2 - bw / 2).coerceIn(dp(8), maxOf(dp(8), screenW - bw - dp(8)))
        p.y = if (petParams.y - bh - dp(4) > dp(24)) petParams.y - bh - dp(4) else petParams.y + pet.height + dp(4)
        try { wm.updateViewLayout(b, p) } catch (e: Exception) { }
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null; bubbleParams = null
    }

    // ---------------- talking ----------------
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { pet.talking = true } }
            override fun onDone(id: String?) { main.post { pet.talking = false } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { pet.talking = false } }
        })
    }

    private fun isTelugu(s: String) = s.any { it in 'ఀ'..'౿' }

    private fun say(text: String, mood: PuppyView.Mood, extra: String = "", prefix: String = "") {
        if (text.isBlank()) return
        pet.mood = mood
        showBubble(prefix + text + extra, keepMs = (6000 + text.length * 80L).coerceAtMost(30_000))
        speak(text)
    }

    private fun speak(text: String) {
        val t = tts
        if (!store.voiceOn || t == null || !ttsReady) { fakeTalk(text); return }
        val te = isTelugu(text)
        val r = t.setLanguage(if (te) Locale.forLanguageTag("te-IN") else Locale.forLanguageTag("en-IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (te && !teluguVoiceMissingShown) {
                teluguVoiceMissingShown = true
                main.postDelayed({ showBubble("To hear me in Telugu, open the BigD AI app and tap 'Install Telugu voice'.", 8000) }, 7000)
            }
            fakeTalk(text); return
        }
        t.setPitch(1.35f); t.setSpeechRate(1.0f)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + (++utter))
    }

    private fun fakeTalk(text: String) {
        pet.talking = true
        main.postDelayed({ pet.talking = false }, (400 + text.length * 45L).coerceAtMost(6000))
    }

    private fun hush() { try { tts?.stop() } catch (e: Exception) { }; pet.talking = false }

    // ---------------- listening ----------------
    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("I need the microphone. Open the BigD AI app and tap 'Allow microphone'.", PuppyView.Mood.SAD); openApp(); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Voice typing isn't available on this phone. Hold me to type instead.", PuppyView.Mood.CONFUSED); return
        }
        hush()
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { showBubble(if (store.lang == "en") "Listening…" else "వింటున్నాను… Listening…", 0) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setListening(false) }
            override fun onError(error: Int) {
                setListening(false); replyVoice = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap me and speak again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet for voice. Check your connection."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Please allow the microphone in the BigD AI app."
                    else -> "Voice didn't work. Tap me to try again, or hold me to type."
                }
                pet.mood = PuppyView.Mood.CONFUSED; showBubble(msg, 5000)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) showBubble("“$it”", 0) }
            }
            override fun onResults(results: Bundle?) {
                setListening(false)
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (heard.isNullOrBlank()) { replyVoice = false; showBubble("I didn't catch that. Tap me and speak again.", 5000); return }
                if (replyVoice) { replyVoice = false; replyByVoice(heard) } else send(heard)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (store.lang == "en") "en-IN" else "te-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        setListening(true)
        try { rec.startListening(i) } catch (e: Exception) { setListening(false); showBubble("Voice didn't start. Hold me to type instead.", 5000) }
    }

    private fun stopListening() { try { recognizer?.stopListening() } catch (e: Exception) { }; setListening(false) }

    private fun setListening(on: Boolean) {
        listening = on; pet.listening = on
        if (on) pet.mood = PuppyView.Mood.HAPPY
    }

    // ---------------- asking the brain ----------------
    private fun send(text: String) {
        if (ScreenCaptureService.wantsScreen(text)) { readScreen(text); return }
        lastQuestion = text
        pet.mood = PuppyView.Mood.THINKING
        showBubble("“$text”\n…", 0)
        brain.ask(text, onReply = { json -> handleReply(json) }, onError = { err ->
            pet.mood = PuppyView.Mood.SAD; showBubble(err, 7000)
        })
    }

    /** "Read my screen": hide the pet, let Android ask permission, take one picture of the screen. */
    private fun readScreen(question: String) {
        photoQuestion = question
        closePanel(); closeMsgCard(); removeBubble(); hush()
        pet.visibility = View.INVISIBLE
        main.postDelayed({ showPetAgain() }, 40_000)
        try { ScreenCaptureService.start(this) } catch (e: Exception) { showPetAgain(); showBubble("Screen reading isn't available on this phone.", 5000) }
    }

    private fun showPetAgain() { pet.visibility = View.VISIBLE }

    private fun sendPhoto(path: String, screen: Boolean = false) {
        val b64 = try { android.util.Base64.encodeToString(java.io.File(path).readBytes(), android.util.Base64.NO_WRAP) } catch (e: Exception) { null }
        try { if (screen) java.io.File(path).delete() } catch (e: Exception) { }
        if (b64 == null) { showBubble("I couldn't open that picture.", 5000); return }
        val ask = photoQuestion.ifBlank { if (store.lang == "te") "నా స్క్రీన్‌పై ఏముందో చెప్పు" else "What is on my screen?" }
        val q = if (screen) {
            "[This picture is a screenshot of my phone screen. Ignore the status bar.] $ask\nExplain simply. If a message is in another language, translate it. If it looks like a scam, fake news or asks for OTP/money, warn me clearly."
        } else photoQuestion.ifBlank { if (store.lang == "te") "ఈ ఫోటోలో ఏముందో సులభంగా చెప్పు." else "What is in this photo? Explain it simply." }
        photoQuestion = ""
        lastQuestion = q
        pet.mood = PuppyView.Mood.THINKING
        showBubble((if (screen) "📱 “$ask”" else "📷 “$q”") + "\n…", 0)
        brain.ask(q, onReply = { json -> handleReply(json) }, onError = { err -> pet.mood = PuppyView.Mood.SAD; showBubble(err, 7000) }, photo = Pair("image/jpeg", b64))
    }

    private fun handleReply(json: JSONObject) {
        val reply = json.optString("reply", "Woof!")
        lastReply = reply
        val done = mutableListOf<String>()
        val arr = json.optJSONArray("actions")
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { a -> actions.run(a)?.let { done.add(it) } }
        var extra = if (done.isEmpty()) "" else "\n\n" + done.joinToString("\n") { "✓ $it" }
        val src = json.optJSONArray("sources")
        if (src != null && src.length() > 0)
            extra += "\n\nSources:\n" + (0 until src.length()).joinToString("\n") { i -> src.optJSONObject(i)?.let { "• " + it.optString("title") + "\n  " + it.optString("url") } ?: "" }
        say(reply, PuppyView.moodOf(json.optString("emotion")), extra)
        panelText?.let { if (panel != null && panelContent.isEmpty()) { it.text = reply + extra; panelTools?.visibility = View.VISIBLE } }
    }

    // ---------------- menu panel (hold the puppy) ----------------
    private fun togglePanel() { if (panel == null) openPanel(null, lastReply) else closePanel() }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setTextColor(if (primary) Color.parseColor("#1C1406") else Color.parseColor("#1C2140"))
        background = GradientDrawable().apply { setColor(Color.parseColor(if (primary) "#F5A524" else "#E3E7F8")); cornerRadius = dp(18).toFloat() }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(6) }) }
    }

    private fun langLabel() = when (store.lang) { "te" -> "తెలుగు"; "en" -> "EN"; else -> "Auto" }

    private fun openPanel(title: String?, content: String) {
        panelContent = if (title != null) content else ""
        if (panel == null) buildPanel()
        val head = if (title != null) "$title\n\n" else ""
        panelText?.text = head + content.ifBlank { "Type a message below, or tap 🎤 to talk." }
        panelTools?.visibility = if (content.isBlank()) View.GONE else View.VISIBLE
    }

    private fun buildPanel() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.parseColor("#D8DDF0")) }
            elevation = dp(8).toFloat()
        }
        val title = TextView(this).apply { text = "BigD AI"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#1C2140")) }
        langBtn = pill(langLabel()) {
            store.lang = when (store.lang) { "auto" -> "te"; "te" -> "en"; else -> "auto" }; langBtn?.text = langLabel()
        }
        voiceBtn = pill(if (store.voiceOn) "🔊" else "🔇") {
            store.voiceOn = !store.voiceOn; voiceBtn?.text = if (store.voiceOn) "🔊" else "🔇"; if (!store.voiceOn) hush()
        }
        val close = pill("✕") { closePanel() }
        val spacer = View(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title); addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            addView(langBtn); addView(voiceBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(close, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        card.addView(header)

        val text = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#1C2140")); setTextIsSelectable(true); setLineSpacing(0f, 1.2f) }
        panelText = text
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.AT_MOST))
        }.apply { addView(text) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val reported = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#5F6688")) }
        val tools = row(
            pill("Copy") { actions.copy(currentPanelText()); reported.text = "Copied" },
            pill("Share") { closePanel(); actions.share(currentPanelText()) },
            pill("Report") {
                brain.report("Reported from puppy", currentPanelText(), lastQuestion)
                reported.text = "Reported. Thank you."
            },
            reported
        )
        panelTools = tools
        card.addView(tools, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        val input = EditText(this).apply {
            hint = "Type, or ask about a photo 📷 or your screen 📱…"; textSize = 15f; setSingleLine(false); maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(Color.parseColor("#1C2140")); setHintTextColor(Color.parseColor("#8A90B0"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F1F3FB")); cornerRadius = dp(18).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        panelInput = input
        val sendIt = {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) { input.setText(""); panelContent = ""; panelText?.text = "…"; panelTools?.visibility = View.GONE; send(q) }
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendIt(); true } else false }
        val mic = pill("🎤") { input.clearFocus(); hideKeyboard(input); startListening() }
        val photo = pill("📷") {
            photoQuestion = input.text.toString().trim(); input.setText(""); hideKeyboard(input); closePanel()
            try { startActivity(Intent(this, PhotoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
        val screen = pill("📱") {
            val q = input.text.toString().trim(); input.setText(""); hideKeyboard(input)
            readScreen(q)
        }
        val sendBtn = pill("Send", primary = true) { sendIt() }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(photo, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(screen, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(mic, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        card.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val footer = row(
            pill("My lists") { closePanel(); openApp("tasks") },
            pill("New chat") { brain.clear(); lastReply = ""; panelText?.text = "New chat started."; panelTools?.visibility = View.GONE },
            pill("🐾 Change pet") { nextPet() }
        )
        card.addView(footer, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val footer2 = row(pill("✕ Close pet") { stopSelf() })
        card.addView(footer2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val p = overlayParams(minOf(screenW - dp(24), dp(380)), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = dp(56)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(card, p)
        panel = card
    }

    private fun currentPanelText() = panelText?.text?.toString() ?: ""

    private fun hideKeyboard(v: View) { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0) }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null; panelText = null; panelInput = null; panelTools = null; langBtn = null; voiceBtn = null; panelContent = ""
    }

    private fun nextPet() {
        val all = PuppyView.Kind.values()
        val next = all[(pet.kind.ordinal + 1) % all.size]
        store.petKind = next.key; pet.kind = next; pet.mood = PuppyView.Mood.EXCITED
        brain.clear()
        showBubble("Hi! I'm your ${next.label.lowercase()} now! · ${next.telugu}", 4000)
    }

    // ---------------- chat messages (WhatsApp, SMS, ...) ----------------
    private var msgCard: View? = null
    private var msgText: TextView? = null
    private var msgInput: EditText? = null
    private var msgWarn: TextView? = null
    private var msgAutoBtn: Button? = null
    private var msgCurrent: Incoming? = null
    private var replyVoice = false

    private fun onMessage(m: Incoming, autoSent: String?) {
        wake()
        if (autoSent != null) {
            pet.mood = PuppyView.Mood.WINK
            showBubble("🤖 Auto-replied to ${m.chat}: $autoSent", 7000)
            return
        }
        pet.mood = PuppyView.Mood.SURPRISED
        val who = if (m.group) "${m.sender} (${m.chat})" else m.chat
        showBubble("💬 $who · ${m.app}\n${m.text}", 8000)
        if (store.msgReadAloud && !listening) speak(if (isTelugu(m.text)) "${m.sender}: ${m.text}" else "${m.sender} says: ${m.text}")
        openMsgCard(m)
        draft(m, "")
    }

    private fun draft(m: Incoming, instruction: String) {
        msgInput?.setText(""); msgInput?.hint = "Writing a reply…"; msgWarn?.visibility = View.GONE
        pet.mood = PuppyView.Mood.THINKING
        brain.draftReply(m, instruction, auto = false, onDraft = { reply, warning ->
            if (msgCurrent !== m) return@draftReply
            msgInput?.setText(reply); msgInput?.setSelection(reply.length)
            if (warning.isNotBlank()) { msgWarn?.text = "⚠️ $warning"; msgWarn?.visibility = View.VISIBLE; pet.mood = PuppyView.Mood.SCARED }
            else pet.mood = PuppyView.Mood.HAPPY
        }, onError = { err ->
            if (msgCurrent !== m) return@draftReply
            msgInput?.hint = "Type your reply"; pet.mood = PuppyView.Mood.CONFUSED; showBubble(err, 5000)
        })
    }

    private fun sendMsgReply() {
        val m = msgCurrent ?: return
        val text = msgInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) { showBubble("Write a reply first, or tap 🎤 and say it.", 4000); return }
        msgInput?.let { hideKeyboard(it) }
        if (MessageHub.send(this, m, text)) {
            closeMsgCard(); pet.mood = PuppyView.Mood.PROUD; showBubble("Sent to ${m.chat} ✓", 4000)
        } else {
            pet.mood = PuppyView.Mood.SAD; showBubble("Couldn't send. The message may be old; open ${m.app} to reply.", 6000)
        }
    }

    /** Voice in the message card: "send" sends; anything else tells me how to reply. */
    private fun replyByVoice(heard: String) {
        val m = msgCurrent ?: run { send(heard); return }
        val h = heard.trim().lowercase().trimEnd('.', '!')
        if (h in setOf("send", "send it", "ok send", "yes send", "పంపు", "పంపించు", "సెండ్", "సెండ్ చెయ్", "పంపేయ్")) { sendMsgReply(); return }
        showBubble("“$heard”\n…", 0)
        draft(m, heard)
    }

    private fun openMsgCard(m: Incoming) {
        closePanel()
        msgCurrent = m
        if (msgCard == null) buildMsgCard()
        val who = if (m.group) "${m.sender} · ${m.chat}" else m.chat
        msgText?.text = "💬 $who · ${m.app}\n\n${m.text}"
        msgAutoBtn?.text = autoLabel()
    }

    private fun autoLabel() = if (store.autoReply) "🤖 Auto-reply: ON" else "🤖 Auto-reply: off"

    private fun buildMsgCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(2), Color.parseColor("#25D366")) }
            elevation = dp(8).toFloat()
        }
        val text = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#1C2140")); setLineSpacing(0f, 1.15f); maxLines = 8 }
        msgText = text
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill("✕") { closeMsgCard() })
        }
        card.addView(head)
        val warn = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#B4232A")); visibility = View.GONE }
        msgWarn = warn
        card.addView(warn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val input = EditText(this).apply {
            hint = "Writing a reply…"; textSize = 15f; setSingleLine(false); maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Color.parseColor("#1C2140")); setHintTextColor(Color.parseColor("#8A90B0"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#EAF8EF")); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        msgInput = input
        card.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(row(
            pill("Send ➤", primary = true) { sendMsgReply() },
            pill("🎤 Say reply") { input.clearFocus(); hideKeyboard(input); replyVoice = true; startListening() },
            pill("↻") { msgCurrent?.let { draft(it, "") } },
            pill("Ignore") { closeMsgCard(); pet.mood = PuppyView.Mood.HAPPY }
        ), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        msgAutoBtn = pill(autoLabel()) {
            store.autoReply = !store.autoReply; msgAutoBtn?.text = autoLabel()
            showBubble(if (store.autoReply) "Auto-reply is ON: I'll answer new messages with \"${store.autoMessage}\"" else "Auto-reply is off.", 5000)
        }
        card.addView(row(msgAutoBtn!!), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        val p = overlayParams(minOf(screenW - dp(24), dp(380)), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = dp(56)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        try { wm.addView(card, p); msgCard = card } catch (e: Exception) { }
    }

    private fun closeMsgCard() {
        msgCard?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        msgCard = null; msgText = null; msgInput = null; msgWarn = null; msgAutoBtn = null; msgCurrent = null; replyVoice = false
    }

    private fun openApp(tab: String = "pet") {
        try { startActivity(Intent(this, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }
}
