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
import android.net.Uri
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
        const val ACTION_HUSH = "com.soulfriend.pet.HUSH"
        /** Habit's games came on the screen ("on" true) or went away: the pet sits small in the corner meanwhile. */
        const val ACTION_GAME = "com.soulfriend.pet.GAME"
        const val ACTION_STOP = "com.soulfriend.pet.STOP"
        const val ACTION_LISTEN = "com.soulfriend.pet.LISTEN"
        const val ACTION_PHOTO = "com.soulfriend.pet.PHOTO"
        const val ACTION_PET = "com.soulfriend.pet.PET"
        const val ACTION_SCREEN_DONE = "com.soulfriend.pet.SCREEN_DONE"
        const val ACTION_REMOTE = "com.soulfriend.pet.REMOTE"
        const val ACTION_VOICE_MODE = "com.soulfriend.pet.VOICE_MODE"
        const val ACTION_DANGER = "com.soulfriend.pet.DANGER"
        const val ACTION_REPLY = "com.soulfriend.pet.REPLY"
        const val ACTION_LOOK_DONE = "com.soulfriend.pet.LOOK_DONE"
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
    /** The last answer when Habit's own brain gave it (for 👍 / 👎): its id, the question, its exact words, which brain. */
    private var lastRate: JSONObject? = null
    /** After 👎: the next thing typed in the panel is the right answer (sent with the 👎), not a question. */
    private var fixing: JSONObject? = null
    private var rateUp: View? = null
    private var rateDown: View? = null
    private var panelNote: TextView? = null
    private var panelSend: Button? = null
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
        actions = Actions(this, onCall = { found -> callChoices(found) }) { title, text -> openPanel(title, text) }
        watchCalls()
        wm = getSystemService(WindowManager::class.java)
        goForeground()
        addPet()
        if (BuildConfig.HANDS_FREE && store.remoteOn) showRemote()
        MessageHub.listener = { m, autoSent -> onMessage(m, autoSent) }
        ChatSync.hold(this, "pet")       // while the pet floats, Habit Chat messages come at once
        tts = TextToSpeech(this, this)
        SoundFx.load(this)
        say("Hi! I'm Habit AI. Tap me and talk! To close me, drag me down to the ✕.", PuppyView.Mood.EXCITED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_GAME -> gameMode(intent.getBooleanExtra("on", false))
            ACTION_SAY -> if (intent.getBooleanExtra("game", false)) gameSay(intent.getStringExtra("text") ?: "") else {
                if (intent.getBooleanExtra("test", false)) naturalNotSetUp = false // "Test voice" asks the server again
                say(intent.getStringExtra("text") ?: "",
                    intent.getStringExtra("mood")?.let { PuppyView.moodOf(it) } ?: PuppyView.Mood.SURPRISED,
                    prefix = intent.getStringExtra("prefix") ?: "⏰ ", local = intent.getBooleanExtra("local", false))
            }
            ACTION_HUSH -> hush()
            ACTION_LISTEN -> startListening()
            ACTION_PHOTO -> { showPetAgain(); intent.getStringExtra("path")?.let { sendPhoto(it, intent.getBooleanExtra("screen", false)) } }
            ACTION_REMOTE -> { if (store.remoteOn) showRemote() else hideRemote() }
            ACTION_VOICE_MODE -> startVoiceMode()
            ACTION_DANGER -> intent.getStringExtra("item")?.let { try { showDanger(JSONObject(it)) } catch (e: Exception) { } }
            ACTION_REPLY -> {
                val m = MessageHub.recent(intent.getStringExtra("id") ?: "")
                if (m?.action != null) { openMsgCard(m); draft(m, "") }
                else showBubble("This message can't be answered from here any more. Open the chat app to reply.", 5000)
            }
            ACTION_SCREEN_DONE -> {
                showPetAgain(); photoQuestion = ""
                if (intent.getBooleanExtra("failed", false)) { pet.mood = PuppyView.Mood.SAD; showBubble("I couldn't read the screen this time. Please try again.", 5000) }
                else { pet.mood = PuppyView.Mood.HAPPY; showBubble("Okay, I won't look at your screen.", 3000) }
            }
            ACTION_PET -> { pet.kind = PuppyView.kindOf(store.petKind); pet.mood = PuppyView.Mood.EXCITED; showBubble(PuppyView.hello(pet.kind), 4000) }
            ACTION_LOOK_DONE -> lookDone(intent.getStringExtra("path"), intent.getStringExtra("ask") ?: "", intent.getBooleanExtra("back", true), intent.getStringExtra("problem") ?: "")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unwatchCalls(); closeChoices(); closeAskBox()
        ChatSync.release("pet")
        natural.stop(); voicePool.shutdownNow()
        MessageHub.listener = null
        closeMsgCard()
        main.removeCallbacksAndMessages(null)
        endEar(); hushBeeps(false)
        try { recognizer?.destroy() } catch (e: Exception) { }
        tts?.shutdown()
        removeBubble(); closePanel(); hideCloseTarget(); hideRemote(); closeDanger(); voiceMode = false
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
            .setContentTitle("Habit AI is on your screen")
            .setContentText("Tap the puppy to talk. Hold it for the menu.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide puppy", stop).build())
            .setOngoing(true)
            .build()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micFgs = mic || Build.VERSION.SDK_INT < 30          // the pet may use the microphone itself while other apps are open
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
        pet.contentDescription = "Habit AI pet. Tap to talk, hold to type, drag to the X at the bottom to close."
        val size = dp(112)
        petParams = overlayParams(size, size).apply {
            x = if (store.petX >= 0) store.petX else screenW - size - dp(8)
            y = if (store.petY >= 0) store.petY else screenH / 2
        }
        wm.addView(pet, petParams)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longDone = false
        val longPress = Runnable { longDone = true; wake(); toggleAskBox() }
        pet.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = petParams.x; startY = petParams.y
                    moved = false; longDone = false
                    main.postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    // over a game it stays in its corner (a slip of the finger doesn't drag it onto the game)
                    if (!moved && !gameSmall && (abs(dx) > slop || abs(dy) > slop)) { moved = true; main.removeCallbacks(longPress); showCloseTarget() }
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
                        if (!gameSmall) { store.petX = petParams.x; store.petY = petParams.y }
                    }
                    else if (!longDone && e.actionMasked == MotionEvent.ACTION_UP) {
                        wake()
                        if (pendingCall != null) { cancelCall(); return@setOnTouchListener true }
                        if (voiceMode) stopVoiceMode()
                        else if (listening) { noMore = true; if (convoOn) { tapStopped = true; endConvo() }; stopListening() }   // a tap ends the conversation
                        else startListening()
                    }
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
            override fun onStart(id: String?) { main.post {
                pet.talking = true
                val piece = synchronized(spoken) { spoken[id] }
                if (piece != null) showBubble(piece, 0)          // a long answer: the bubble follows the part being read
            } }
            override fun onDone(id: String?) { main.post {
                if (id != lastUtter) return@post
                pet.talking = false
                if (synchronized(spoken) { spoken.isNotEmpty() }) main.postDelayed(hideBubble, 6000)
                spokenDone()
            } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { if (id == lastUtter) { pet.talking = false; spokenDone() } } }
        })
    }

    private fun isTelugu(s: String) = s.any { it in 'ఀ'..'౿' }

    /** [local] = read with the phone's own voice only (the text never goes to the server). */
    private fun say(said: String, mood: PuppyView.Mood, extra: String = "", prefix: String = "", local: Boolean = false, delayMs: Long = 0) {
        val text = Talk.plain(said)                              // never a citation mark or HTML tag in the bubble or the voice
        if (text.isBlank()) return
        if (quietEar != null) quietStop()                        // it was waiting for someone to talk: it must not hear its own voice
        pet.mood = mood
        showBubble(prefix + text + extra, keepMs = (6000 + text.length * 80L).coerceAtMost(30_000))
        if (delayMs > 0) {                                      // a little sound first, then the voice
            val seq = ++speakSeq
            main.postDelayed({ if (seq == speakSeq) { if (local) speakPhone(text) else speak(text) } }, delayMs)
            return
        }
        if (local) { ++speakSeq; speakPhone(text) } else speak(text)
    }

    // Natural voice (server) first when it is set up; otherwise the phone's own voice.
    private val natural = NaturalVoicePlayer()
    @Volatile private var naturalNotSetUp = false
    private var speakSeq = 0
    private val voicePool = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun speak(said: String) {
        hushBeeps(false)                                              // its voice is heard (the listening beeps were hushed)
        if (onPhone()) { ++speakSeq; return }                         // a phone call: quiet (the words stay in the bubble)
        val text = Talk.speakable(said)                              // no marks or web addresses read aloud
        val seq = ++speakSeq
        if (store.voiceOn && store.naturalVoice && !naturalNotSetUp && text.length <= 700) {   // long answers: the phone's voice reads them in parts
            val kind = pet.kind; val mood = pet.mood
            voicePool.execute {
                val f = brain.ttsBlocking(text, kind.key, mood.name.lowercase(), cacheDir) { naturalNotSetUp = true }
                main.post {
                    if (seq != speakSeq) { f?.delete(); return@post }
                    if (f == null) { speakPhone(text); return@post }
                    try { tts?.stop() } catch (e: Exception) { }
                    natural.play(f, PetVoice.naturalPitch(kind, mood, store.cuteVoice),
                        onStart = { pet.talking = true },
                        onDone = { if (seq == speakSeq) { pet.talking = false; spokenDone() } },
                        onFail = { if (seq == speakSeq) speakPhone(text) })
                }
            }
            return
        }
        speakPhone(text)
    }

    private val emoji = Regex("\\p{So}|\uFE0F")
    private fun speakPhone(said: String) {
        hushBeeps(false)
        if (onPhone()) return
        val text = emoji.replace(Talk.speakable(said)) { if (it.value == "°") it.value else "" }.trim()
        natural.stop()
        val t = tts
        if (!store.voiceOn || t == null || !ttsReady) { fakeTalk(text); return }
        val te = isTelugu(text)
        val r = t.setLanguage(if (te) Locale.forLanguageTag("te-IN") else Locale.forLanguageTag("en-IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (te && !teluguVoiceMissingShown) {
                teluguVoiceMissingShown = true
                main.postDelayed({ showBubble("To hear me in Telugu, open the Habit AI app and tap 'Install Telugu voice'.", 8000) }, 7000)
            }
            fakeTalk(text); return
        }
        val (pitch, rate) = PetVoice.phone(pet.kind, pet.mood, store.cuteVoice)
        t.setPitch(pitch); t.setSpeechRate(rate)
        // everything is read: a long answer goes in parts, one after another (the voice reads at most ~4000 letters at once)
        val parts = Speech.chunks(text, if (text.length > 260) 220 else 3800)
        synchronized(spoken) { spoken.clear() }
        parts.forEachIndexed { i, piece ->
            val id = "u" + (++utter)
            if (parts.size > 1) synchronized(spoken) { spoken[id] = piece }
            if (i == parts.lastIndex) lastUtter = id
            t.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    private val spoken = HashMap<String, String>()   // part being read -> its text (for the bubble)
    @Volatile private var lastUtter = ""

    private fun fakeTalk(text: String) {
        pet.talking = true
        val seq = speakSeq
        main.postDelayed({ pet.talking = false; if (seq == speakSeq) spokenDone() }, (400 + text.length * 45L).coerceAtMost(6000))
    }

    private fun hush() { speakSeq++; afterSpeech = null; natural.stop(); try { tts?.stop() } catch (e: Exception) { }; pet.talking = false }

    // ---------------- conversation mode: after a spoken answer it listens again, so they don't tap for every question ----------------
    // It asks once if they want it. Silence: it relaxes. "bye", "చాలు", "ok ok", or a tap: it stops. "stay with me", "ఉండు": it
    // keeps listening for a few minutes. Low battery: it says so and rests.
    private var afterSpeech: (() -> Unit)? = null   // what to do when the pet has finished saying its answer
    private var convoOn = false                      // listening for the next question in a conversation
    private var convoStay = false                    // "stay with me": keeps listening through short silences
    private var convoHeardAt = 0L
    private var convoAsking = false                  // waiting for yes / no to "Shall I keep listening?"
    private var tapStopped = false
    private var voiceAsk = false                     // the question now being answered was spoken (not typed)

    private fun spokenDone() {
        val next = afterSpeech ?: return
        afterSpeech = null
        // its own voice must be over in the room before it listens (a Bluetooth speaker plays a little later), or the
        // voice typing hears the pet instead of the person
        val bt = try {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET }
        } catch (e: Exception) { false }
        main.postDelayed({ next() }, if (bt) 1_100L else 700L)
    }

    private fun te() = store.lang == "te" || (store.lang != "en" && isTelugu(lastReply))

    private fun batteryLow(): Boolean = try {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 1..15 && !bm.isCharging
    } catch (e: Exception) { false }

    /** After an answer to a spoken question: listen for the next one (or ask once whether they want that). */
    private fun convoNext() {
        if (listening || voiceMode || CallManager.active != null || msgCard != null) return
        if (batteryLow()) {
            endConvo(quiet = true)
            say(if (te()) "బ్యాటరీ తక్కువగా ఉంది, అందుకే కాసేపు విశ్రాంతి తీసుకుంటాను. అవసరమైతే నన్ను తట్టండి." else "My battery is low, so I'll rest now. Tap me when you need me.",
                PuppyView.Mood.SLEEPY, local = true)
            return
        }
        when {
            convoStay || store.convo == "on" -> { convoOn = true; startListening() }
            store.convo == "ask" && store.convoAsked < 2 -> askConvo()
        }
    }

    private fun askConvo() {
        store.convoAsked = store.convoAsked + 1
        say(if (te()) "ప్రతి ప్రశ్నకు నన్ను తట్టకుండా, నేను జవాబు చెప్పాక కాసేపు వింటూ ఉండనా? అవును లేదా వద్దు అని చెప్పండి."
            else "Shall I keep listening for a moment after I answer, so you don't have to tap me every time? Say yes or no.", PuppyView.Mood.THINKING, local = true)
        afterSpeech = { convoAsking = true; startListening() }
    }

    /** Their answer to "Shall I keep listening?": true if it was handled. */
    private fun convoAnswer(heard: String): Boolean {
        convoAsking = false
        val yes = Talk.yesNo(heard) ?: return false
        store.convo = if (yes) "on" else "off"
        if (yes) {
            say(if (te()) "సరే! జవాబు చెప్పాక కాసేపు వింటాను. ఇక చాలు అనుకుంటే \"చాలు\" లేదా \"బై\" అనండి. ఇప్పుడు ఏం అడుగుతారు?"
                else "Great! I'll listen for a moment after each answer. Say \"bye\" or \"enough\" when you're done. What's next?", PuppyView.Mood.HAPPY, local = true)
            afterSpeech = { convoOn = true; startListening() }
        } else {
            say(if (te()) "సరే, అవసరమైనప్పుడు నన్ను తట్టండి. (యాప్ › Voice లో ఎప్పుడైనా మార్చుకోవచ్చు.)" else "Okay, just tap me when you need me. (You can change this any time in the app › Voice.)",
                PuppyView.Mood.HAPPY, local = true)
        }
        return true
    }

    /** "Interesting talk": the conversation went quiet, so once per conversation (in the daytime) the pet says one short fact,
     *  tip or riddle, then listens once more. False when it doesn't (then the conversation ends). */
    private var sparkUsed = false
    private fun trySpark(): Boolean {
        val hour = java.time.LocalTime.now().hour
        if (!convoOn || sparkUsed || !store.sparks || hour < 7 || hour >= 22 || batteryLow() || GamesActivity.shown != null) return false
        sparkUsed = true
        val te = te()
        pet.mood = PuppyView.Mood.THINKING
        Thread {
            val j = if (te) Sparks.fromServer(store, "te") else null      // Habit Media's posts are in Telugu
            val (text, mood) = if (j != null) j.optString("reply") to PuppyView.moodOf(j.optString("emotion")) else Sparks.local(store, te)
            main.post {
                if (!convoOn || listening) return@post                   // they tapped or started talking meanwhile
                say(text, mood)
                brain.noteSaid(text)
                afterSpeech = { if (convoOn) startListening() }
            }
        }.start()
        return true
    }

    /** The conversation is over: the pet relaxes (and forgets who was talking: the next person may be someone else). */
    private fun endConvo(quiet: Boolean = false, bye: Boolean = false) {
        val was = convoOn || convoStay
        convoOn = false; convoStay = false; convoAsking = false; afterSpeech = null; sparkUsed = false
        if (was) Diary.tidy(this, afterTalk = true)          // the conversation is over: what mattered goes into the memory
        if (quiet || GamesActivity.shown != null) return     // (over a game: no sleepy bubble on the board)
        store.setSpeaker("")
        if (bye || was) {
            pet.mood = if (bye) PuppyView.Mood.HAPPY else PuppyView.Mood.SLEEPY
            showBubble(if (bye) (if (te()) "👋 సరే! అవసరమైతే నన్ను తట్టండి." else "👋 Okay! Tap me when you need me.")
                       else (if (te()) "😴 అవసరమైతే నన్ను తట్టండి." else "😴 Tap me when you need me."), 4000)
        }
    }

    // ---------------- playing Habit's games by voice ----------------
    // While a game is on the screen the pet sits small in the bottom corner (it doesn't cover the game), says the game's
    // lines (the page shows the words itself) and, after each one, listens for the answer or the move, without a tap.
    private var gameSmall = false

    private fun gameMode(on: Boolean) {
        if (!::petParams.isInitialized || on == gameSmall) return
        gameSmall = on
        val s = dp(if (on) 76 else 112)
        petParams.width = s; petParams.height = s
        if (on) { petParams.x = maxOf(0, screenW - s - dp(4)); petParams.y = maxOf(0, screenH - s - dp(96)) }   // as toCorner()
        else {
            petParams.x = if (store.petX >= 0) store.petX.coerceIn(0, maxOf(0, screenW - s)) else screenW - s - dp(8)
            petParams.y = if (store.petY >= 0) store.petY.coerceIn(0, maxOf(0, screenH - s)) else screenH / 2
            if (listening) quietStop()
            endConvo(quiet = true)                                     // the game is closed: its conversation ends quietly
        }
        try { wm.updateViewLayout(pet, petParams) } catch (e: Exception) { }
        removeBubble()
    }

    /** Over a game: small in the bottom corner, out of the game's way (put back there if anything moved it). */
    private fun toCorner() {
        val s = petParams.width
        val x = maxOf(0, screenW - s - dp(4)); val y = maxOf(0, screenH - s - dp(96))
        if (petParams.x == x && petParams.y == y) return
        petParams.x = x; petParams.y = y
        try { wm.updateViewLayout(pet, petParams) } catch (e: Exception) { }
    }

    /** Stops listening without any message (a new line is coming, or the game closed). */
    private fun quietStop() {
        tapStopped = true; noMore = true
        endEar(); unhushSoon(200)
        try { recognizer?.cancel() } catch (e: Exception) { }
        setListening(false)
    }

    /** A line from the games page: the pet says it, then listens for the answer or the next move. */
    private fun gameSay(line: String) {
        val text = Talk.plain(line)
        if (text.isBlank()) return
        if (listening) quietStop()                                    // it must not hear its own voice
        pet.mood = PuppyView.Mood.HAPPY
        if (gameSmall) toCorner() else showBubble(text, keepMs = (6000 + text.length * 80L).coerceAtMost(30_000))
        speak(text)                                                   // the pet's own voice, as in a talk (the natural voice when it's set up: Telugu too)
        if (store.convo != "off") afterSpeech = { if (GamesActivity.shown != null && !listening && !onPhone()) { convoOn = true; startListening() } }
    }

    // ---------------- listening ----------------
    // Patient listening: some phones' voice typing gives up a second after it starts, before people begin to talk. So one
    // turn of listening lasts a few seconds: while nobody has started talking, the voice typing quietly starts again; when
    // the phone's usual one keeps giving up at once (or can't work at all), the Google app's voice typing is tried ([Ears]).
    private var listenSince = 0L                     // when this turn of listening began (the quiet restarts keep it)
    private var readyAt = 0L
    private var heardSpeech = false                  // something like talking was heard in this turn
    private var heardNow = false                     // ...in this try
    private var quickMisses = 0
    private var restarts = 0
    private var unclearTries = 0
    private var noMore = false                       // they tapped to stop: this turn doesn't start again by itself
    private var ears: android.content.ComponentName? = null

    /** How long one turn listens for someone to start talking (a game: time to think). */
    private fun patienceMs(): Long = when { convoAsking -> 7_000L; GamesActivity.shown != null -> 20_000L; else -> 10_000L }

    // Waiting quietly: after the voice typing gave up once (nobody talked yet), the pet waits for talking with the
    // microphone alone ([QuietEar]): no voice typing runs meanwhile, so no beeps and nothing starts over every second.
    private var quietEar: QuietEar? = null
    private var quietFailed = false                  // the microphone can't be used for waiting here: the old way, this turn
    private var micFgs = false                       // the pet may use the microphone while other apps are on the screen

    private fun feedKey() = (ears ?: Ears.usual(this))?.flattenToString() ?: "default"

    /** The waiting ear lets go of the microphone. [learn]: the voice typing finished by itself, so whether it took the
     *  ear's sound is remembered for next time. */
    private fun endEar(learn: Boolean = false) {
        val e = quietEar ?: return
        quietEar = null
        e.stop()
        if (learn) e.taken()?.let { Ears.fed(this, e.key, it) }
    }

    /** Nobody has started talking yet: wait quietly for the rest of this turn. False: it can't (then the old way). */
    private fun waitQuietly(): Boolean {
        if (quietFailed || !micFgs || noMore || tapStopped || voiceMode || onPhone() || CallManager.active != null) return false
        val now = System.currentTimeMillis()
        val left = if (convoStay && convoOn) 3 * 60_000L - (now - convoHeardAt) else patienceMs() - (now - listenSince)
        if (left < 1_500L) return false
        endEar()
        val me = arrayOfNulls<QuietEar>(1)
        val ear = QuietEar(feedKey(),
            onTalk = { val e = me[0]; if (e != null && quietEar === e) startListening(again = true, ear = e) },   // someone talks: now the voice typing
            onDone = { blocked ->
                val e = me[0]
                if (e != null && quietEar === e) {
                    quietEar = null; e.stop(); setListening(false)
                    if (blocked) quietFailed = true
                    if (!(blocked && listenMore(false))) heardNothing(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                }
            })
        me[0] = ear
        quietEar = ear
        setListening(true)                                            // still listening (no flicker)
        // a moment first, so the voice typing has let go of the microphone
        main.postDelayed({
            if (quietEar === ear && !ear.start(left - 300)) {
                quietEar = null; quietFailed = true; setListening(false)
                if (!listenMore(false)) heardNothing(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            }
        }, 300)
        return true
    }

    // The voice typing's own beeps (each time it starts and stops) are hushed while the pet listens, when nothing else is
    // playing: the media sound is muted for those moments only and comes back right after (and before the pet talks).
    private var beepsHushed = false
    private val unhush = Runnable { hushBeeps(false) }
    private fun hushBeeps(on: Boolean) {
        main.removeCallbacks(unhush)
        if (on == beepsHushed) return
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        if (on && (voiceMode || am.isMusicActive)) return              // a video or song is playing: its sound stays
        try {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (on) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0)
            beepsHushed = on
        } catch (e: Exception) { }
    }
    private fun unhushSoon(ms: Long = 450) { if (beepsHushed) { main.removeCallbacks(unhush); main.postDelayed(unhush, ms) } }

    /** The turn ended without words: it asks again once, ends the conversation quietly, or says it didn't hear. */
    private fun heardNothing(error: Int) {
        val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (silent && heardSpeech && askAgain()) return
        replyVoice = false
        if (convoAsking) { convoAsking = false; if (silent) { removeBubble(); return } }   // no answer: it asks again another time
        if (convoOn) {
            // "stay with me": keeps listening through short silences (a few minutes); otherwise silence ends the conversation
            if (silent && convoStay && System.currentTimeMillis() - convoHeardAt < 3 * 60_000L && !batteryLow()) {
                main.postDelayed({ if (convoOn && !listening) startListening() }, 250); return
            }
            if (silent) { if (!trySpark()) endConvo(); return }
            endConvo(quiet = true)
        }
        val te = te()
        val msg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> notHeard(te)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (te) "వాయిస్‌కి ఇంటర్నెట్ లేదు. కనెక్షన్ చూడండి." else "No internet for voice. Check your connection."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (te) "Habit AI యాప్‌లో మైక్రోఫోన్ అనుమతి ఇవ్వండి." else "Please allow the microphone in the Habit AI app."
            else -> if (te) "వాయిస్ పని చేయలేదు. మళ్ళీ తట్టండి, లేదా నన్ను పట్టుకుని టైప్ చేయండి." else "Voice didn't work. Tap me to try again, or hold me to type."
        }
        pet.mood = PuppyView.Mood.CONFUSED; showBubble(msg, 6000)
    }

    /** Nothing heard yet (or this voice typing can't work): listen again quietly while this turn lasts. */
    private fun listenMore(broken: Boolean): Boolean {
        if (noMore || tapStopped || voiceMode || onPhone() || CallManager.active != null) return false
        val now = System.currentTimeMillis()
        if (now - listenSince > patienceMs() || restarts >= 25) return false
        if (broken || (!heardNow && now - readyAt < 2_500L)) quickMisses++
        if (broken || quickMisses >= 2) {
            val other = Ears.other(this, ears, anyKind = broken)
            if (other != null) { ears = other; quickMisses = 0 } else if (broken) return false
        }
        restarts++
        main.postDelayed({ if (!listening && !tapStopped && !onPhone()) startListening(again = true) }, if (broken) 300L else 120L)
        return true
    }

    /** They talked but the words weren't clear: once, the pet asks them to say it again. */
    private fun askAgain(): Boolean {
        if (unclearTries >= 1 || noMore || tapStopped || convoAsking || voiceMode || onPhone()) return false
        unclearTries++
        heardSpeech = false; quickMisses = 0; restarts = 0; listenSince = System.currentTimeMillis()
        showBubble(sayAgainText(), 0)
        main.postDelayed({ if (!listening && !tapStopped && !onPhone()) startListening(again = true) }, 150)
        return true
    }
    private fun sayAgainText() = if (te()) "🤔 సరిగ్గా వినపడలేదు, మళ్ళీ చెప్పండి…" else "🤔 I didn't get that. Say it again…"

    /** The turn ended: nothing heard at all, or words it couldn't make out. */
    private fun notHeard(te: Boolean) = when {
        !heardSpeech && te -> "🎙 ఏమీ వినపడలేదు. నన్ను తట్టి, \"వింటున్నాను\" అని కనిపించాక మాట్లాడండి."
        !heardSpeech -> "🎙 I didn't hear anything. Tap me and talk when you see \"Listening…\"."
        te -> "సరిగ్గా అర్థం కాలేదు. నన్ను తట్టి మళ్ళీ చెప్పండి."
        else -> "I didn't catch that. Tap me and speak again."
    }

    private fun startListening(again: Boolean = false, ear: QuietEar? = null) {
        if (ear == null) endEar()                                     // a new try from the start: a waiting ear lets go of the microphone
        if (CallManager.active != null || onPhone()) { endEar(); showBubble(if (store.lang == "en") "📞 You're on a call. I'll listen after it." else "📞 మీరు కాల్‌లో ఉన్నారు. కాల్ తర్వాత వింటాను."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            endEar()
            say("I need the microphone. Open the Habit AI app and tap 'Allow microphone'.", PuppyView.Mood.SAD); openApp(); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            endEar()
            say("Voice typing isn't available on this phone. Hold me to type instead.", PuppyView.Mood.CONFUSED); return
        }
        if (!again) {
            hush()
            tapStopped = false; noMore = false
            listenSince = System.currentTimeMillis(); heardSpeech = false; quickMisses = 0; restarts = 0; unclearTries = 0; quietFailed = false
            ears = Ears.start(this)
        }
        lookToken++
        readyAt = System.currentTimeMillis(); heardNow = false
        // a fresh voice typing for each try: a used one gets stuck on some phones
        recognizer?.let { try { it.destroy() } catch (e: Exception) { } }
        val rec = Ears.create(this, ears).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                readyAt = System.currentTimeMillis()
                if (gameSmall) toCorner()
                showBubble(when {
                    gameSmall && unclearTries == 0 -> "🎙"
                    voiceMode -> "🎙 Say: next · previous · pause · like\n(tap me to stop)"
                    unclearTries > 0 && !heardSpeech -> sayAgainText()
                    convoAsking -> if (te()) "🎙 అవును / వద్దు?" else "🎙 Yes or no?"
                    convoOn -> if (te()) "🎙 ఇంకా అడగండి… (\"చాలు\" అంటే ఆపుతాను)" else "🎙 Ask more… (say \"bye\" to stop)"
                    store.lang == "en" -> "Listening…"
                    else -> "వింటున్నాను… Listening…"
                }, 0)
            }
            override fun onBeginningOfSpeech() { heardSpeech = true; heardNow = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setListening(false) }
            override fun onError(error: Int) {
                val silentErr = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                endEar(learn = silentErr)
                unhushSoon()
                setListening(false)
                if (voiceMode) { replyVoice = false; voiceModeNext(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT); return }
                if (tapStopped) { tapStopped = false; replyVoice = false; return }   // they tapped to end the conversation
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                // busy, broken, or doesn't know the language: another voice typing, if the phone has one
                val broken = error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == 11 || error == 12 || error == 13 || error == 14   // server disconnected, language not supported / unavailable, can't check
                // nobody has started talking yet: it waits with the microphone alone (no beeps, no starting over)
                if (silent && !heardNow && waitQuietly()) return
                // a sound but no words: once "say it again"; after that it waits quietly too (a fan or a cough doesn't make it
                // start over and over)
                if (silent && heardNow && askAgain()) return
                if (silent && waitQuietly()) return
                if (silent || broken) { if (listenMore(broken)) return }   // a pause, noise, its own voice's echo: keep listening
                heardNothing(error)
            }
            override fun onPartialResults(partial: Bundle?) {
                if (voiceMode) return
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) { heardSpeech = true; heardNow = true; showBubble("“$it”", 0) } }
            }
            override fun onResults(results: Bundle?) {
                endEar(learn = true)
                unhushSoon()
                setListening(false)
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (voiceMode) { onVoiceModeHeard(heard.orEmpty()); return }
                if (tapStopped) { tapStopped = false; replyVoice = false; return }
                if (heard.isNullOrBlank()) {
                    if (!heardNow && waitQuietly()) return
                    if (heardNow && askAgain()) return
                    if (waitQuietly()) return
                    if (listenMore(false)) return
                    if (heardSpeech && askAgain()) return
                    replyVoice = false
                    if (convoAsking) { convoAsking = false; removeBubble(); return }
                    if (convoOn) { if (!trySpark()) endConvo(); return }
                    pet.mood = PuppyView.Mood.CONFUSED; showBubble(notHeard(te()), 6000); return
                }
                Ears.worked(this@PetService, ears)                          // next time it starts with the voice typing that heard this
                if (replyVoice) { replyVoice = false; replyByVoice(heard); return }
                if (convoAsking && convoAnswer(heard)) return
                convoHeardAt = System.currentTimeMillis()
                // "bye", "enough", "ok ok", "చాలు": the pet stops (no question for the brain)
                if (Talk.isStop(heard)) { endConvo(bye = true); return }
                // "stay with me", "ఇక్కడే ఉండు": it keeps listening for a few minutes
                if (Talk.isStay(heard)) {
                    convoStay = true; convoOn = true
                    say(if (te()) "సరే, నేను ఇక్కడే ఉన్నాను! ఎప్పుడైనా మాట్లాడండి." else "Okay, I'm right here! Talk to me any time.", PuppyView.Mood.HAPPY, local = true)
                    afterSpeech = { convoNext() }
                    return
                }
                voiceAsk = true
                send(heard)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LetterHelper.showing?.petLang() ?: if (store.lang == "en") "en-IN" else "te-IN")   // (a letter in English: English)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // someone started talking while the pet waited quietly: the voice typing gets the ear's sound (Android 13+), from a
        // moment before the talking, so the first word isn't cut; otherwise the ear lets go and the voice typing opens the microphone
        if (ear != null && !(Ears.feedOk(this, ear.key) && ear.attach(i))) endEar()
        setListening(true)
        hushBeeps(true)                                               // no beep each time the voice typing starts or stops
        try { rec.startListening(i) } catch (e: Exception) { endEar(); unhushSoon(); setListening(false); showBubble("Voice didn't start. Hold me to type instead.", 5000) }
    }

    private fun stopListening() { endEar(); try { recognizer?.stopListening() } catch (e: Exception) { }; setListening(false); unhushSoon() }

    private fun setListening(on: Boolean) {
        listening = on; pet.listening = on
        if (on) pet.mood = PuppyView.Mood.HAPPY
    }

    // ---------------- asking the brain ----------------
    private fun send(text: String) {
        val byVoice = voiceAsk; voiceAsk = false
        // a game is on the screen: the pet plays it with them ("కుర్చీ", "the second one", "next", "show the answer"…);
        // anything else goes to the brain with what's on the game's screen, so it knows what they mean
        GamesActivity.shown?.let { g ->
            g.voice(text) { handled ->
                if (!handled) askBrain(text, byVoice, g.context())
                // playing by voice: when the pet has said how it went, it listens for the next answer
                else if (byVoice && store.convo != "off") afterSpeech = { if (GamesActivity.shown != null && !listening && !onPhone()) { convoOn = true; startListening() } }
            }
            return
        }
        // a letter's questions are on the screen (Files): what they say goes into that letter (the answer being filled, else
        // the first empty one), not into a new letter
        LetterHelper.showing?.takeVoice(text)?.let { q ->
            say(if (te() || isTelugu(text)) "సరే, \"${q.take(50)}\" లో రాశాను." else "Okay, I put it in \"${q.take(50)}\".", PuppyView.Mood.HAPPY)
            return
        }
        // "let's play", "ఆట ఆడదాం": the games open straight away (no need to ask the brain, works without internet). The games
        // page then says what's next through the pet ("Which game? Quiz, riddles…", or the named game's first question)
        // and the pet listens for the answer.
        Talk.game(text)?.let { g ->
            if (convoOn) endConvo(quiet = true)
            if (GamesActivity.open(this, g)) { pet.mood = PuppyView.Mood.EXCITED; return }
        }
        HandsFree.match(text)?.let { if (handsFree(it)) return }
        if (BuildConfig.HANDS_FREE && Regex("^(reels? mode|hands ?free( mode)?|రీల్స్ మోడ్|హ్యాండ్స్ ఫ్రీ)$", RegexOption.IGNORE_CASE).matches(text.trim().trimEnd('.', '!'))) { startVoiceMode(); return }
        if (ScreenCaptureService.wantsScreen(text)) { readScreen(text); return }
        lookToken++; autoLooks = 0
        // "look at this shirt", "what's written on this board?", "what colour is my shirt?": the pet looks with the camera itself
        Eyes.wants(text, lastLook)?.let { l -> lastQuestion = l.ask; look(l); return }
        askBrain(text, byVoice)
    }

    /** Asks the brain. [context]: what is on Habit's own screen now (a game's question), told to the brain with the question. */
    private fun askBrain(text: String, byVoice: Boolean, context: String = "") {
        lastQuestion = text
        pet.mood = PuppyView.Mood.THINKING
        showBubble("“$text”\n…", 0)
        brain.ask(if (context.isBlank()) text else "[$context]\n$text", onReply = { json -> handleReply(json, byVoice = byVoice) }, onError = { err ->
            pet.mood = PuppyView.Mood.SAD; showBubble(err, 7000)
            if (convoOn) endConvo(quiet = true)
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
        brain.ask(q, onReply = { json -> handleReply(json, fromPhoto = true) }, onError = { err -> pet.mood = PuppyView.Mood.SAD; showBubble(err, 7000) }, photo = Pair("image/jpeg", b64))
    }

    // ---------------- the pet's eyes (the camera) ----------------
    private var lastLook: Eyes.Look? = null
    private var lookingSince = 0L
    private var lookToken = 0          // a new question (or tapping me to talk) stops a "look again" that is waiting
    private var autoLooks = 0          // "move a little left… looking again": at most twice per question

    /** Opens the pet's eyes: a small note at the top, ONE photo from the camera (no camera window), then the AI answers about it. */
    private fun look(l: Eyes.Look, announce: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - lookingSince < 20_000) return                       // already looking
        lookingSince = now; lastLook = l
        closePanel(); closeMsgCard()
        val te = isTelugu(l.ask) || store.lang == "te"
        if (announce) say(Eyes.going(l, te), PuppyView.Mood.EXCITED, local = true) else pet.mood = PuppyView.Mood.EXCITED
        try {
            startActivity(Intent(this, LookActivity::class.java).putExtra("back", l.back).putExtra("ask", l.ask).putExtra("again", l.again)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            lookingSince = 0; pet.mood = PuppyView.Mood.SAD
            showBubble(if (te) "ఈ ఫోన్‌లో కెమెరా తెరవలేకపోయాను." else "I couldn't open the camera on this phone.", 5000)
            return
        }
        // some phones (Xiaomi, Oppo, Vivo…) stop apps from opening a screen from the background unless you allow it
        val asked = now
        main.postDelayed({
            if (lookingSince == asked && LookActivity.openedAt < asked) {
                lookingSince = 0; pet.mood = PuppyView.Mood.CONFUSED
                showBubble(if (te) "నా కళ్ళు తెరుచుకోలేదు. ఫోన్ Settings › Apps › Habit AI లో \"Display pop-up windows while running in the background\" (లేదా \"Open in background\") అనుమతి ఇచ్చి మళ్ళీ అడగండి."
                           else "My eyes didn't open. In the phone's Settings › Apps › Habit AI, allow \"Display pop-up windows while running in the background\" (or \"Open in background\"), then ask me again.", 12_000)
            }
        }, 6000)
    }

    /** The photo is taken (or not): ask the AI about it. Without the AI, the phone reads the English letters on a board by itself. */
    private fun lookDone(path: String?, ask: String, back: Boolean, problem: String) {
        lookingSince = 0
        val f = path?.let { java.io.File(it) }
        val jpeg = try { f?.readBytes() } catch (e: Exception) { null } finally { try { f?.delete() } catch (e: Exception) { } }
        if (jpeg == null) {
            if (problem.isNotBlank()) say(problem, PuppyView.Mood.CONFUSED, local = true)
            return
        }
        val l = Eyes.Look(back, ask, again = lastLook?.let { it.again && it.ask == ask } == true)
        val te = isTelugu(ask) || store.lang == "te"
        lastQuestion = ask
        pet.mood = PuppyView.Mood.THINKING
        showBubble("👀 “$ask”\n…", 0)
        brain.ask(Eyes.question(l), onReply = { json -> handleReply(json, fromPhoto = true, eyes = true) }, onError = { err ->
            if (!back) { pet.mood = PuppyView.Mood.SAD; showBubble(err, 7000) }
            else Thread {
                val letters = EyesOffline.read(jpeg)
                main.post {
                    if (letters == null) { pet.mood = PuppyView.Mood.SAD; showBubble(err, 7000) }
                    else say((if (te) "నా పెద్ద మెదడు ఇప్పుడు అందుబాటులో లేదు, కానీ ఇక్కడ రాసి ఉన్న ఇంగ్లీష్ అక్షరాలు నేనే చదవగలను: " else "I can't reach my big brain right now, but I can read the letters here myself: ") +
                        letters.take(600), PuppyView.Mood.THINKING, local = true)
                }
            }.start()
        }, photo = "image/jpeg" to android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP), local = false)
    }

    /** [fromPhoto]: the answer is about a photo, so it doesn't open the eyes again, except [eyes] (a photo the pet took itself):
     *  then "move a little to the left… looking again" takes another photo by itself, at most twice. */
    private fun handleReply(json: JSONObject, fromPhoto: Boolean = false, eyes: Boolean = false, byVoice: Boolean = false) {
        val reply = json.optString("reply", "Woof!")
        lastReply = reply
        // the diary (on this phone): what was said and answered; the important things go into the pet's memory later
        if (!fromPhoto && lastQuestion.isNotBlank()) { Diary.add(store, store.speakerNow() ?: "", lastQuestion, reply); Diary.tidy(this) }
        lastRate = if (!fromPhoto && json.optString("rid").isNotEmpty()) JSONObject().put("rid", json.optString("rid")).put("q", lastQuestion.take(600))
            .put("r", reply).put("b", json.optString("brain")) else null
        endFix()
        val done = mutableListOf<String>()
        val arr = json.optJSONArray("actions")
        var replyTo: Pair<Incoming, String>? = null
        var lookAt: Eyes.Look? = null
        var screenAsk: String? = null
        var lookAgain: Eyes.Look? = null
        if (arr != null) for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            // the AI wants to see it: open the pet's eyes with the person's own words (so the answer comes in their language)
            if (a.optString("type") == "look") {
                if (eyes && a.optBoolean("again") && autoLooks < 2) lastLook?.let { lookAgain = Eyes.Look(it.back, it.ask, again = true) }
                else if (!fromPhoto && lookAt == null && screenAsk == null) {
                    val ask = lastQuestion.ifBlank { a.optString("ask") }
                    if (a.optString("camera") == "screen") screenAsk = ask else lookAt = Eyes.Look(back = a.optString("camera") != "front", ask = ask)
                }
                continue
            }
            // they said who they are ("I'm Ravi"): the pet uses that name for this conversation only
            if (a.optString("type") == "speaker") { a.optString("name").takeIf { it.isNotBlank() }?.let { store.setSpeaker(it) }; continue }
            // "send Ravi …": if Ravi just messaged, answer right here with one tap (no need to open WhatsApp)
            if (a.optString("type") == "whatsapp" && a.optString("number").isBlank() && replyTo == null) {
                val m = MessageHub.recentFrom(a.optString("contact"))
                if (m != null) { replyTo = m to a.optString("message"); done.add("Your message to ${m.chat} is ready below. Tap Send ➤"); continue }
            }
            actions.run(a)?.let { done.add(it) }
        }
        var extra = if (done.isEmpty()) "" else "\n\n" + done.joinToString("\n") { "✓ $it" }
        val src = json.optJSONArray("sources")
        if (src != null && src.length() > 0)
            extra += "\n\nSources:\n" + (0 until src.length()).joinToString("\n") { i -> src.optJSONObject(i)?.let { "• " + it.optString("title") + "\n  " + it.optString("url") } ?: "" }
        // which brain answered, when it wasn't the big AI (shown, not spoken)
        if (json.optBoolean("self")) extra += "\n\n🧠 " + (if (store.lang == "te") "హ్యాబిట్ సొంత మెదడు, తను నేర్చుకున్న దాని నుంచి జవాబిచ్చింది (పెద్ద AI ఇప్పుడు అందుబాటులో లేదు)" else "Habit's own brain answered from what it has learned (the big AI is away right now)")
        else if (json.optBoolean("own")) extra += "\n\n🧠 " + (if (store.lang == "te") "హ్యాబిట్ సొంత మెదడు జవాబిచ్చింది (పెద్ద AI ఇప్పుడు అందుబాటులో లేదు)" else "Habit's own brain answered (the big AI is away right now)")
        else if (json.optBoolean("learned")) extra += "\n\n📚 " + (if (store.lang == "te") "ముందే సిద్ధం చేసిన జవాబు" else "A prepared answer to a common question")
        else if (json.optBoolean("tiny")) extra += "\n\n📴 " + (if (store.lang == "te") "ఇంటర్నెట్ లేదు, ఫోన్‌లోని చిన్న మెదడు జవాబిచ్చింది" else "No internet: the tiny brain on this phone answered")
        val snd = json.optString("sound")
        val wait = if (snd.isNotEmpty() && store.voiceOn && store.funSounds) SoundFx.play(this, snd) else 0L
        say(reply, PuppyView.moodOf(json.optString("emotion")), extra, delayMs = wait)
        lookAt?.let { l -> main.postDelayed({ look(l, announce = false) }, 300) }
        lookAgain?.let { l ->
            autoLooks++
            val token = ++lookToken
            // after it has said how to hold the phone, and they've had a moment to do it
            main.postDelayed({ if (token == lookToken && !listening) look(l, announce = false) }, (1800L + reply.length * 60L).coerceIn(3000L, 9000L))
        }
        screenAsk?.let { q -> main.postDelayed({ readScreen(q) }, 1500) }
        replyTo?.let { (m, text) -> openMsgCard(m); msgInput?.setText(text); msgInput?.setSelection(text.length) }
        panelText?.let { if (panel != null && panelContent.isEmpty()) { it.text = reply + extra; panelTools?.visibility = View.VISIBLE } }
        showRate()
        // a spoken question: when the answer has been said, listen for the next one (conversation mode), unless the answer
        // took them somewhere else (a game, another app, a call, the letter helper)
        val away = arr != null && (0 until arr.length()).any { arr.optJSONObject(it)?.optString("type") in setOf("game", "open_app", "open_page", "call", "whatsapp", "letter") }
        if (byVoice && store.voiceOn && !away && lookAt == null && lookAgain == null && screenAsk == null && replyTo == null && !voiceMode &&
            (convoStay || store.convo != "off")) afterSpeech = { convoNext() }
        else if (convoOn) endConvo(quiet = true)
    }

    private fun endFix() { if (fixing == null) return; fixing = null; panelSend?.text = "Send"; panelInput?.hint = "Type, or ask about a photo 📷 or your screen 📱…" }
    /** 👍 / 👎 in the panel, under an answer from Habit's own brain (only with "Help Habit learn" on). */
    private fun showRate() {
        val on = lastRate != null && store.learnShare && panelContent.isEmpty()
        rateUp?.visibility = if (on) View.VISIBLE else View.GONE
        rateDown?.visibility = if (on) View.VISIBLE else View.GONE
    }
    private fun rate(good: Boolean, fix: String = "") {
        val r = lastRate ?: return
        val te = store.lang == "te"
        if (ChatDb.get(this).kv("gemma:rateOk") != "1" && panelNote?.tag != "ask") {
            panelNote?.tag = "ask"
            panelNote?.text = if (te) "మీ ప్రశ్న, ఈ జవాబు హ్యాబిట్ సర్వర్‌కి వెళ్తాయి, హ్యాబిట్ సొంత AIకి నేర్పించడానికి (నంబర్లు తీసేస్తాం, యజమాని చూస్తారు). పంపడానికి మళ్ళీ నొక్కండి."
                else "Your question and this answer go to Habit's server to teach Habit's own AI (numbers are taken out; the owner checks them). Tap again to send."
            return
        }
        ChatDb.get(this).setKv("gemma:rateOk", "1"); panelNote?.tag = null
        brain.rate(r.optString("rid"), r.optString("q"), r.optString("r"), good, fix) { err ->
            if (err != null) { panelNote?.text = err; return@rate }
            if (!good && fix.isEmpty()) {
                fixing = r; rateUp?.visibility = View.GONE; rateDown?.visibility = View.GONE
                panelSend?.text = if (te) "జవాబు పంపు" else "Send answer"
                panelInput?.hint = if (te) "సరైన జవాబు రాసి పంపండి (కావాలంటే)" else "Type the right answer and Send (you can skip this)"
                panelNote?.text = if (te) "👎 ధన్యవాదాలు! సరైన జవాబు తెలిస్తే కింద రాసి పంపండి." else "👎 Thank you! If you know the right answer, type it below and tap Send."
            } else { panelNote?.text = if (te) "ధన్యవాదాలు! దీంతో హ్యాబిట్ సొంత మెదడు నేర్చుకుంటుంది." else "Thank you! This helps Habit's own brain learn."; lastRate = null; showRate() }
        }
    }

    // ---------------- phone calls: the pet is quiet while the phone rings or a call is on ----------------
    private val audioMgr by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }
    /** Ringing, or on a call (phone, WhatsApp, Habit Chat): no talking, no listening. Needs no permission. */
    private fun onPhone(): Boolean = try {
        val m = audioMgr.mode
        m == android.media.AudioManager.MODE_RINGTONE || m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= 30 && m == android.media.AudioManager.MODE_CALL_SCREENING)
    } catch (e: Exception) { false }
    private var modeWatch: Any? = null
    private fun watchCalls() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val l = android.media.AudioManager.OnModeChangedListener { main.post { if (onPhone()) quietForCall() } }
            audioMgr.addOnModeChangedListener(mainExecutor, l); modeWatch = l
        } catch (e: Exception) { }
    }
    private fun unwatchCalls() {
        if (Build.VERSION.SDK_INT < 31) return
        try { (modeWatch as? android.media.AudioManager.OnModeChangedListener)?.let { audioMgr.removeOnModeChangedListener(it) } } catch (e: Exception) { }
    }
    private fun quietForCall() {
        val busy = pet.talking || listening || convoOn
        hush(); if (listening) stopListening(); endConvo(quiet = true); cancelCall(quiet = true)
        if (busy) showBubble(if (te()) "📞 కాల్ వచ్చింది, నేను నిశ్శబ్దంగా ఉంటాను." else "📞 A call! I'll be quiet.", 4000)
    }

    // ---------------- calling by the pet: one person: a call after 3 seconds (tap me to stop it); several: pick one ----------------
    private var pendingCall: Runnable? = null
    private fun callChoices(found: List<Pair<String, String>>) {
        if (found.size == 1) { callSoon(found[0].first, found[0].second); return }
        val te = te()
        showChoices(if (te) "📞 ఎవరికి కాల్ చేయాలి?" else "📞 Who should I call?", found.map { (n, num) -> "$n · $num" }) { i -> callSoon(found[i].first, found[i].second) }
    }
    private fun callSoon(name: String, number: String) {
        val te = te()
        val tel = number.filter { it.isDigit() || it == '+' }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // without the "Phone calls" permission the dialer opens with the number (one tap on Call)
            try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            showBubble(if (te) "📞 $name నంబర్ తెరిచాను, Call నొక్కండి. నేనే నేరుగా కాల్ చేయాలంటే: యాప్ › Your pet › Permissions › \"Phone calls\" › Allow."
                       else "📞 $name's number is ready: tap Call. To let me call by myself: the app › Your pet › Permissions › \"Phone calls\" › Allow.", 9000)
            return
        }
        cancelCall(quiet = true)
        pet.mood = PuppyView.Mood.EXCITED
        showBubble(if (te) "📞 $name కి కాల్ చేస్తున్నాను… ($number)\nఆపాలంటే నన్ను తట్టండి" else "📞 Calling $name… ($number)\nTap me to stop", 4000)
        speakPhone(if (te) "$name కి కాల్ చేస్తున్నాను" else "Calling $name")
        val go = Runnable {
            pendingCall = null
            try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(tel))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { showBubble(if (te) "కాల్ చేయలేకపోయాను." else "I couldn't place the call.", 5000) }
        }
        pendingCall = go
        main.postDelayed(go, 3000)
    }
    private fun cancelCall(quiet: Boolean = false) {
        val p = pendingCall ?: return
        main.removeCallbacks(p); pendingCall = null
        hush()
        if (!quiet) { pet.mood = PuppyView.Mood.HAPPY; showBubble(if (te()) "సరే, కాల్ ఆపాను." else "Okay, I stopped the call.", 3000) }
    }

    /** A small box under the pet with choices (tap one). */
    private var choices: View? = null
    private fun showChoices(title: String, items: List<String>, pick: (Int) -> Unit) {
        closeChoices()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.parseColor("#D8DDF0")) }
            elevation = dp(8).toFloat()
        }
        card.addView(TextView(this).apply { text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#1C2140")) })
        items.forEachIndexed { i, label ->
            card.addView(TextView(this).apply {
                text = label; textSize = 15f; setTextColor(Color.parseColor("#1C2140")); setPadding(dp(12), dp(11), dp(12), dp(11))
                background = GradientDrawable().apply { setColor(Color.parseColor("#F1F3FB")); cornerRadius = dp(14).toFloat() }
                setOnClickListener { closeChoices(); pick(i) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        card.addView(pill(if (te()) "✕ వద్దు" else "✕ Cancel") { closeChoices() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val w = minOf(screenW - dp(24), dp(320))
        val p = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT)
        wm.addView(card, p); choices = card
        card.post { placeNearPet(card, p, w) }
        main.postDelayed({ if (choices === card) closeChoices() }, 60_000)
    }
    private fun closeChoices() { choices?.let { try { wm.removeView(it) } catch (e: Exception) { } }; choices = null }

    /** Puts [v] just under the pet (or just above it when the pet is low on the screen). */
    private fun placeNearPet(v: View, p: WindowManager.LayoutParams, w: Int) {
        val h = v.height.takeIf { it > 0 } ?: dp(120)
        p.x = (petParams.x + pet.width / 2 - w / 2).coerceIn(dp(8), maxOf(dp(8), screenW - w - dp(8)))
        p.y = if (petParams.y + pet.height + h + dp(8) < screenH * 0.62f) petParams.y + pet.height + dp(4) else maxOf(dp(24), petParams.y - h - dp(4))
        try { wm.updateViewLayout(v, p) } catch (e: Exception) { }
    }

    // ---------------- the question box: hold the pet, type under it ----------------
    private var askBox: View? = null
    private fun toggleAskBox() { if (askBox != null) closeAskBox() else openAskBox() }
    private fun openAskBox() {
        closePanel(); closeChoices()
        val te = te()
        val input = EditText(this).apply {
            hint = if (te) "నన్ను అడగండి…" else "Ask me anything…"; textSize = 15f; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(Color.parseColor("#1C2140")); setHintTextColor(Color.parseColor("#8A90B0"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F1F3FB")); cornerRadius = dp(18).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val go = { val q = input.text.toString().trim(); if (q.isNotEmpty()) { hideKeyboard(input); closeAskBox(); send(q) } }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { go(); true } else false }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.parseColor("#D8DDF0")) }
            elevation = dp(8).toFloat()
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill("➤", primary = true) { go() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(pill("⋯") { hideKeyboard(input); closeAskBox(); openPanel(null, lastReply) }.apply { contentDescription = if (te) "మెనూ" else "Menu" },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            addView(pill("✕") { hideKeyboard(input); closeAskBox() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        }
        val w = minOf(screenW - dp(16), dp(380))
        val p = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE }
        wm.addView(card, p); askBox = card
        card.post { placeNearPet(card, p, w); input.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }
    }
    private fun closeAskBox() { askBox?.let { try { wm.removeView(it) } catch (e: Exception) { } }; askBox = null }

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
        showRate()
    }

    private fun buildPanel() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.parseColor("#D8DDF0")) }
            elevation = dp(8).toFloat()
        }
        val title = TextView(this).apply { text = "Habit AI"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#1C2140")) }
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
        panelNote = reported
        val up = pill("👍") { rate(true) }.apply { contentDescription = "Good answer"; visibility = View.GONE }
        val down = pill("👎") { rate(false) }.apply { contentDescription = "Wrong answer"; visibility = View.GONE }
        rateUp = up; rateDown = down
        val tools = row(
            up, down,
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
            val fx = fixing
            if (fx != null) {
                // after 👎: this is the right answer (it goes with the 👎), not a new question
                endFix(); input.setText("")
                if (q.isNotEmpty()) { lastRate = fx; rate(false, q) }
            } else if (q.isNotEmpty()) { input.setText(""); panelContent = ""; panelText?.text = "…"; panelTools?.visibility = View.GONE; send(q) }
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
        panelSend = sendBtn
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
        val footer2 = if (BuildConfig.HANDS_FREE) row(
            pill(if (store.remoteOn) "🎮 Hide remote" else "🎮 Remote") { store.remoteOn = !store.remoteOn; closePanel(); if (store.remoteOn) showRemote() else hideRemote() },
            pill("🎙 Reels voice") { closePanel(); startVoiceMode() },
            pill("✕ Close pet") { stopSelf() }
        ) else row(pill("✕ Close pet") { stopSelf() })
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
        rateUp = null; rateDown = null; panelNote = null; panelSend = null; fixing = null
    }

    private fun nextPet() {
        val all = PuppyView.Kind.values()
        val next = all[(pet.kind.ordinal + 1) % all.size]
        store.petKind = next.key; pet.kind = next; pet.mood = PuppyView.Mood.EXCITED
        brain.clear()
        showBubble(PuppyView.hello(next) + " · ${next.telugu}", 4000)
    }

    // ---------------- hands-free control (Plus edition) ----------------
    private val reelCmds = setOf(HandsFree.Cmd.NEXT, HandsFree.Cmd.PREVIOUS, HandsFree.Cmd.PAUSE, HandsFree.Cmd.LIKE)

    /** Runs one fixed command. Returns true when the words were a command (handled here, never sent to the AI). */
    private fun handsFree(cmd: HandsFree.Cmd): Boolean {
        if (!BuildConfig.HANDS_FREE) {
            if (cmd !in reelCmds) return false
            say("Scrolling Reels comes in Habit AI Plus, after the Play Store approval. For now, Google's Voice Access can do it: say \"scroll down\".", PuppyView.Mood.CONFUSED)
            return true
        }
        if (!HandsFreeService.isOn(this) || HandsFreeService.instance == null) {
            say("To do that, turn on Hands-free control in the Habit AI app: Pet › Hands-free control.", PuppyView.Mood.CONFUSED)
            main.postDelayed({ openApp("pet") }, 1800)
            return true
        }
        val ok = HandsFreeService.run(cmd)
        pet.mood = if (ok) PuppyView.Mood.WINK else PuppyView.Mood.SAD
        showBubble(if (ok) cmd.label else "Couldn't do that on this phone.", 1500)
        return true
    }

    // Reels voice mode: keeps listening for the 4 Reels words only. Nothing else you say is used or sent anywhere.
    private var voiceMode = false
    private var voiceModeLastUse = 0L

    private fun startVoiceMode() {
        if (!BuildConfig.HANDS_FREE) { handsFree(HandsFree.Cmd.NEXT); return }
        if (!HandsFreeService.isOn(this) || HandsFreeService.instance == null) { handsFree(HandsFree.Cmd.NEXT); return }
        closePanel(); closeMsgCard()
        voiceMode = true; voiceModeLastUse = System.currentTimeMillis()
        pet.mood = PuppyView.Mood.COOL
        startListening()
    }

    private fun stopVoiceMode() {
        voiceMode = false
        try { recognizer?.cancel() } catch (e: Exception) { }
        setListening(false)
        pet.mood = PuppyView.Mood.HAPPY; showBubble("Reels voice mode off.", 2500)
    }

    private fun onVoiceModeHeard(heard: String) {
        val cmd = HandsFree.match(heard)
        if (cmd != null && cmd in reelCmds) {
            voiceModeLastUse = System.currentTimeMillis()
            HandsFreeService.run(cmd)
            pet.mood = PuppyView.Mood.WINK
        } else if (Regex("stop listening|stop voice|exit|ఆపేయ్ వినడం|వినడం ఆపు").containsMatchIn(heard.lowercase())) { stopVoiceMode(); return }
        voiceModeNext(true)
    }

    /** Listen again, unless 5 minutes passed without a command or the pet was closed. */
    private fun voiceModeNext(quiet: Boolean) {
        if (!voiceMode) return
        if (System.currentTimeMillis() - voiceModeLastUse > 5 * 60_000L) { stopVoiceMode(); return }
        main.postDelayed({ if (voiceMode) startListening() }, if (quiet) 250L else 1500L)
    }

    // Small remote on the left edge (the right edge has Instagram's like/comment buttons).
    private var remote: View? = null

    private fun showRemote() {
        if (remote != null || !BuildConfig.HANDS_FREE) return
        fun btn(label: String, cmd: HandsFree.Cmd) = TextView(this).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC0E6B6B")) }
            setTextColor(Color.WHITE)
            setOnClickListener { handsFree(cmd) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(Color.parseColor("#66000000")) }
            listOf("⏮" to HandsFree.Cmd.PREVIOUS, "⏯" to HandsFree.Cmd.PAUSE, "⏭" to HandsFree.Cmd.NEXT, "❤" to HandsFree.Cmd.LIKE).forEach { (l, c) ->
                addView(btn(l, c), LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(4); bottomMargin = dp(4) })
            }
            addView(TextView(this@PetService).apply {
                text = "✕"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setOnClickListener { store.remoteOn = false; hideRemote() }
            }, LinearLayout.LayoutParams(dp(46), dp(30)))
        }
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL; x = dp(4); y = 0
        }
        try { wm.addView(box, p); remote = box } catch (e: Exception) { }
    }

    private fun hideRemote() {
        remote?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        remote = null
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
        pet.mood = if (m.warn.isNotBlank()) PuppyView.Mood.SCARED else PuppyView.Mood.SURPRISED
        val who = if (m.group) "${m.sender} (${m.chat})" else m.chat
        showBubble("💬 $who · ${m.app}\n${m.text}", 8000)
        if (store.msgReadAloud && !listening) speak(if (isTelugu(m.text)) "${m.sender}: ${m.text}" else "${m.sender} says: ${m.text}")
        openMsgCard(m)
        draft(m, "")
        if (m.warn.isNotBlank()) { msgWarn?.text = "🟡 Be careful: ${m.warn}"; msgWarn?.visibility = View.VISIBLE }
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

    // ---------------- danger alert: a red window for fraud and dangerous messages ----------------
    private var dangerCard: View? = null

    private fun showDanger(item: JSONObject) {
        closeDanger()
        val v = ScamCheck.Verdict.from(item.optJSONObject("v") ?: JSONObject())
        wake(); pet.mood = PuppyView.Mood.SCARED
        val red = Color.parseColor("#C62828")
        val ink = Color.parseColor("#3B0D0D")
        fun t(s: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); setLineSpacing(0f, 1.15f); if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFF6F6")); cornerRadius = dp(22).toFloat(); setStroke(dp(3), red) }
            elevation = dp(12).toFloat()
        }
        card.addView(t("⚠️  DANGER · ప్రమాదం", 19f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = GradientDrawable().apply { setColor(red); cornerRadius = dp(12).toFloat() }
        })
        card.addView(t(v.title.ifBlank { "This looks like a fraud message" }, 17f, red, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(t("From: ${item.optString("sender")} · ${item.optString("app")}", 13f, Color.parseColor("#7A4A4A")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        card.addView(t("“" + item.optString("text").take(240) + "”", 14f).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8)); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (v.reasons.isNotEmpty()) card.addView(t("Why · ఎందుకు:\n" + v.reasons.joinToString("\n") { "• $it" }, 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (v.advice.isNotEmpty()) card.addView(t("What to do · ఏం చేయాలి:\n" + v.advice.joinToString("\n") { "✓ $it" }, 14f, Color.parseColor("#1D5E3A"), true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        card.addView(row(
            pill("📞 1930") { closeDanger(); try { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:1930")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } },
            pill("🚩 Report") { closeDanger(); try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://sancharsaathi.gov.in/sfc/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } },
            pill("OK, I understand", primary = true) { closeDanger(); pet.mood = PuppyView.Mood.HAPPY }
        ), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        val p = overlayParams(minOf(screenW - dp(24), dp(400)), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = dp(48)
        }
        try { wm.addView(card, p); dangerCard = card } catch (e: Exception) { }
        if (store.voiceOn) speak(if (store.lang == "en") "Careful! This message looks like a fraud. Don't click any link, and never share your OTP."
            else "జాగ్రత్త! ఈ మెసేజ్ మోసపూరితంగా ఉంది. లింక్ నొక్కవద్దు, OTP ఎవరికీ చెప్పవద్దు.")
    }

    private fun closeDanger() {
        dangerCard?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        dangerCard = null
    }

    private fun openApp(tab: String = "pet") {
        try { startActivity(Intent(this, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }
}
