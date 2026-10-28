package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.DownloadManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** The BigD AI app: Home, Pet, Tasks, Files, Vault and Feedback pages with a bottom menu. */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var vault: VaultStore
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
    private val io = Executors.newSingleThreadExecutor()

    private var tab = "home"
    private var tasksTab = "todo"
    private var pickingPhotoFor: String? = null
    private var leavingForSystemScreen = false

    companion object {
        private const val REQ_PHOTO = 4402
        val TABS = listOf(
            Triple("home", "Home", "🏠"), Triple("pet", "Pet", "🐾"), Triple("tasks", "Tasks", "✅"),
            Triple("files", "Files", "📄"), Triple("vault", "Vault", "🔒"), Triple("feedback", "Feedback", "💬")
        )
    }

    private fun dp(v: Int) = ui.dp(v)
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun add(v: View, top: Int = 14) = content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this); vault = VaultStore(this)
        Reminders.ensureChannel(this)
        window.statusBarColor = Ui.BG
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(28)) }
        scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(8))
            setBackgroundColor(Color.WHITE); elevation = dp(12).toFloat()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); fitsSystemWindows = true
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        intent?.getStringExtra("tab")?.let { tab = it }
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); intent?.getStringExtra("tab")?.let { tab = it } }
    override fun onResume() { super.onResume(); leavingForSystemScreen = false; render() }

    override fun onPause() {
        super.onPause()
        if (pickingPhotoFor == null && !leavingForSystemScreen) VaultLock.lock()
    }

    private fun go(t: String) { tab = t; render(); scroll.scrollTo(0, 0) }

    // =================== frame ===================
    private fun render() {
        content.removeAllViews()
        if (!store.onboarded) { nav.visibility = View.GONE; secure(false); renderSetup(); return }
        nav.visibility = View.VISIBLE
        buildNav()
        secure(tab == "vault")
        when (tab) {
            "pet" -> petPage()
            "tasks" -> tasksPage()
            "files" -> filesPage()
            "vault" -> vaultPage()
            "feedback" -> feedbackPage()
            else -> homePage()
        }
    }

    /** Blocks screenshots and hides the app preview in Recents while the Vault is on screen. */
    private fun secure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun buildNav() {
        nav.removeAllViews()
        TABS.forEach { (key, label, icon) ->
            val on = key == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
                addView(TextView(this@MainActivity).apply {
                    text = icon; textSize = 19f; gravity = Gravity.CENTER
                    background = if (on) ui.round(Ui.TEAL_SOFT, 16) else null
                    setPadding(dp(14), dp(3), dp(14), dp(3))
                })
                addView(ui.text(label, 11f, if (on) Ui.TEAL_DARK else Ui.MUTED, bold = on).apply { gravity = Gravity.CENTER })
                contentDescription = label
                setOnClickListener { go(key) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun pageHeader(title: String, sub: String) {
        add(ui.title(title), 4)
        add(ui.text(sub, 14f, Ui.MUTED), 2)
    }

    // =================== 1. HOME ===================
    private fun homePage() {
        val now = ZonedDateTime.now()
        val (greetTe, greetEn) = when (now.hour) {
            in 4..11 -> "శుభోదయం" to "Good morning"
            in 12..15 -> "శుభ మధ్యాహ్నం" to "Good afternoon"
            in 16..20 -> "శుభ సాయంత్రం" to "Good evening"
            else -> "శుభ రాత్రి" to "Good night"
        }
        // hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.TEAL, Ui.TEAL_DARK)).apply { cornerRadius = ui.dpf(28) }
        }
        val words = ui.stack(
            ui.text(greetTe, 26f, Color.WHITE, bold = true),
            ui.text(greetEn + " · " + DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH).format(now), 13.5f, Color.parseColor("#CDE8E5")),
            ui.text("BigD AI · బిగ్ డి", 13.5f, Color.parseColor("#F6D58C"), bold = true).apply { setPadding(0, dp(10), 0, 0) }
        )
        val pet = PuppyView(this).apply { kind = PuppyView.kindOf(store.petKind); mood = if (PetService.running) PuppyView.Mood.EXCITED else PuppyView.Mood.HAPPY }
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pet, LinearLayout.LayoutParams(dp(112), dp(112)))
        })
        hero.addView(ui.button(if (PetService.running) "Close my pet" else "🐾  Show my pet on screen", "sun") { togglePet() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        add(hero, 4)

        // today
        add(ui.label("Today"), 20)
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val rems = store.reminders().sortedBy { it.at }
        val todayCount = rems.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
        val pending = store.todos().count { !it.done }
        fun stat(n: Int, label: String, target: String, sub: String = "todo") = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ui.round(Color.WHITE, 18, Ui.LINE)
            addView(ui.text(n.toString(), 26f, Ui.TEAL_DARK, bold = true))
            addView(ui.text(label, 12.5f, Ui.MUTED))
            setOnClickListener { tasksTab = sub; go(target) }
        }
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(stat(todayCount, "Reminders today", "tasks", "rem"), stat(pending, "To-dos pending", "tasks", "todo"), stat(store.files().size, "Files", "files"))
                .forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) }) }
        }, 8)
        rems.firstOrNull { it.at > System.currentTimeMillis() }?.let { r ->
            add(ui.card(Ui.SUN_SOFT).apply {
                addView(ui.text("⏰ Next: ${r.text}", 15f, Ui.INK, bold = true))
                put(ui.text(fmt.format(Instant.ofEpochMilli(r.at).atZone(zone)) + Reminders.repeatLabel(r.repeat).let { if (it.isEmpty()) "" else " · 🔁 $it" }, 13f, Ui.MUTED), 2)
            }, 8)
        }

        // quick actions
        add(ui.label("Quick actions"), 20)
        fun tile(icon: String, title: String, sub: String, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ui.round(Color.WHITE, 20, Ui.LINE)
            addView(ui.text(icon, 24f)); addView(ui.text(title, 15f, Ui.INK, bold = true).apply { setPadding(0, dp(6), 0, 0) })
            addView(ui.text(sub, 12.5f, Ui.MUTED))
            setOnClickListener { onClick() }
        }
        fun pair(a: View, b: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, -1, 1f)); addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })
        }
        add(pair(tile("🎤", "Talk now", "Ask anything by voice") { talkNow() },
            tile("📄", "Make a file", "Word letter or Excel sheet") { go("files") }), 8)
        add(pair(tile("⏰", "Add reminder", "Once or repeating") { tasksTab = "rem"; go("tasks") },
            tile("🔒", "My Vault", "Cards and documents") { go("vault") }), 10)

        val tips = listOfNotNull(
            "Say \"remind me to take medicine every day at 8 am\"",
            "Say \"NOC letter Word file for Sy.No. 370/1\"",
            "Hold the pet for the menu: type, photo, lists",
            if (BuildConfig.MESSAGE_REPLIES) "Turn on WhatsApp replies in the Pet page" else null,
            "Drag the pet down to ✕ to close it"
        )
        add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text("💡 Tip", 13f, Ui.TEAL_DARK, bold = true))
            put(ui.text(tips[(now.dayOfYear + now.hour) % tips.size], 14.5f, Ui.INK), 4)
        }, 16)
    }

    private fun togglePet() {
        if (PetService.running) stopService(Intent(this, PetService::class.java))
        else if (!Settings.canDrawOverlays(this)) { toast("First allow \"Show over other apps\" on the Pet page."); go("pet"); return }
        else { startForegroundService(Intent(this, PetService::class.java)); moveTaskToBack(true) }
        content.postDelayed({ render() }, 400)
    }

    private fun talkNow() {
        if (!Settings.canDrawOverlays(this)) { toast("First allow \"Show over other apps\" on the Pet page."); go("pet"); return }
        if (!PetService.running) startForegroundService(Intent(this, PetService::class.java))
        content.postDelayed({ try { startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_LISTEN)) } catch (e: Exception) { } }, 900)
        moveTaskToBack(true)
    }

    // =================== 2. PET ===================
    private fun petPage() {
        pageHeader("Your pet", "Choose, set up and teach your pet")
        add(ui.card().apply {
            val pet = PuppyView(this@MainActivity).apply { kind = PuppyView.kindOf(store.petKind); mood = if (PetService.running) PuppyView.Mood.EXCITED else PuppyView.Mood.SLEEPY }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ui.stack(
                    ui.text(PuppyView.kindOf(store.petKind).label + " · " + PuppyView.kindOf(store.petKind).telugu, 18f, Ui.INK, bold = true),
                    ui.text(if (PetService.running) "● On your screen" else "○ Resting", 13.5f, if (PetService.running) Ui.GOOD else Ui.MUTED)
                ), LinearLayout.LayoutParams(0, -2, 1f))
                addView(pet, LinearLayout.LayoutParams(dp(84), dp(84)))
            })
            put(ui.button(if (PetService.running) "Close my pet" else "🐾  Show my pet on screen", if (PetService.running) "ghost" else "primary") { togglePet() }, 12)
            put(ui.text("Tap it to talk · hold it for the menu · drag it down to ✕ to close", 12.5f, Ui.MUTED), 8)
        }, 14)

        add(ui.section("Choose your pet").apply { petPicker(this) }, 12)

        add(ui.section("Language · భాష", "Auto: listens in Telugu and replies in the language you use.").apply {
            put(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("auto" to "Auto", "te" to "తెలుగు", "en" to "English").forEachIndexed { i, (code, label) ->
                    addView(ui.chip(label, store.lang == code) { store.lang = code; render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
                }
            }, 12)
        }, 12)

        add(ui.section("Permissions · అనుమతులు").apply { permissionRows(this) }, 12)
        // "Reply to messages" exists only in the full (Play Store) edition. See build.gradle.kts.
        if (BuildConfig.MESSAGE_REPLIES) add(messagesCard(), 12)
        add(briefingCard(), 12)

        add(ui.section("Privacy", "Useful for office files. Photos are sent as they are.").apply {
            put(ui.check("Hide personal details before sending (Aadhaar, phone and PAN numbers become XXXX…1234)", store.hidePersonal) { store.hidePersonal = it }, 8)
        }, 12)

        add(ui.section("What your pet remembers", "Saved only on this phone. Say \"forget that\" or tap ✕.").apply {
            val mems = store.memories()
            if (mems.isEmpty()) put(ui.text("Nothing yet. Tell it about yourself, for example \"My daughter's name is Sita\".", 13.5f, Ui.MUTED))
            mems.forEachIndexed { i, m -> put(ui.line(ui.text(m, 15f), ui.button("✕", "ghost") { store.removeMemory(i); render() }), 8) }
        }, 12)

        add(ui.section("Advanced").apply {
            put(ui.text("Server address", 14f, Ui.INK, bold = true), 10)
            val box = ui.input("https://…", store.server).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            put(box, 6)
            put(ui.row(ui.button("Save address") {
                val v = box.text.toString().trim()
                if (!v.startsWith("https://")) toast("The address must start with https://") else { store.server = v; toast("Saved") }
            }), 8)
            put(ui.button("Show the setup guide again", "ghost") { store.onboarded = false; setupStep = 0; render() }, 12)
        }, 12)
    }

    /** The 6 pets to choose from. */
    private fun petPicker(box: LinearLayout) {
        val current = PuppyView.kindOf(store.petKind)
        PuppyView.Kind.values().toList().chunked(3).forEach { rowKinds ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowKinds.forEach { k ->
                val chosen = k == current
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(10))
                    background = ui.round(if (chosen) Ui.SUN_SOFT else Color.parseColor("#F5F8F7"), 18, if (chosen) Ui.SUN else null)
                    addView(PuppyView(this@MainActivity).apply { kind = k; mood = if (chosen) PuppyView.Mood.EXCITED else PuppyView.Mood.HAPPY }, LinearLayout.LayoutParams(dp(70), dp(70)))
                    addView(ui.text((if (chosen) "✓ " else "") + k.label, 13.5f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER })
                    addView(ui.text(k.telugu, 12f, Ui.MUTED).apply { gravity = Gravity.CENTER })
                    setOnClickListener {
                        store.petKind = k.key
                        if (PetService.running) startService(Intent(this@MainActivity, PetService::class.java).setAction(PetService.ACTION_PET))
                        render()
                    }
                }
                r.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            box.put(r, 10)
        }
    }

    /** Overlay, microphone, notifications, exact alarms, contacts and Telugu voice. */
    private fun permissionRows(box: LinearLayout) {
        fun step(done: Boolean, label: String, why: String, action: () -> Unit) {
            box.put(ui.line(ui.stack(ui.text((if (done) "✅  " else "◻️  ") + label, 15f, Ui.INK, bold = true), ui.text(why, 12.5f, Ui.MUTED)),
                if (done) null else ui.button("Allow") { leavingForSystemScreen = true; action() }), 12)
        }
        step(Settings.canDrawOverlays(this), "Show over other apps", "So your pet can float on your screen.") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        step(granted(Manifest.permission.RECORD_AUDIO), "Microphone", "Only while you tap your pet to talk.") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) step(granted(Manifest.permission.POST_NOTIFICATIONS), "Notifications", "For reminders, timers and files.") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            step(am.canScheduleExactAlarms(), "Exact reminders", "So reminders ring at the exact minute.") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        step(granted(Manifest.permission.READ_CONTACTS), "Contacts (optional)", "Only to find a number when you say \"call Ravi\". Never sent to the server.") {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3)
        }
        box.put(ui.line(ui.stack(ui.text("🔊  Telugu voice", 15f, Ui.INK, bold = true), ui.text("Install once so your pet can speak Telugu.", 12.5f, Ui.MUTED)),
            ui.button("Install") {
                leavingForSystemScreen = true
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                catch (e: Exception) { toast("Open Settings › Text-to-speech › Google › Install voice data › Telugu") }
            }), 12)
    }

    private fun messagesCard() = ui.section("Reply to messages · మెసేజ్ రిప్లైలు",
        "When a WhatsApp or SMS message comes, your pet shows it and writes a reply. Nothing is sent until you tap Send, unless Auto-reply is on.").apply {
        val access = MessageHub.accessGranted(this@MainActivity)
        put(ui.line(ui.stack(ui.text((if (access) "✅  " else "◻️  ") + "Notification access", 15f, Ui.INK, bold = true),
            ui.text("Lets your pet read new message notifications from the apps you tick.", 12.5f, Ui.MUTED)),
            if (access) null else ui.button("Allow") { showMessageDisclosure() }), 12)
        if (!access) put(ui.text("If Android says \"Restricted setting\": open Settings › Apps › BigD AI › ⋮ › Allow restricted settings, then tap Allow again.", 12f, Ui.MUTED), 6)
        put(ui.check("Watch new messages", store.msgWatch) { store.msgWatch = it; render() }, 10)
        if (store.msgWatch) {
            put(ui.text("Apps to watch", 14f, Ui.INK, bold = true), 8)
            val apps = store.msgApps
            MessageHub.APPS.forEach { (key, label, _) -> put(ui.check(label, key in apps) { on -> store.msgApps = if (on) store.msgApps + key else store.msgApps - key }, 0) }
            put(ui.check("Read new messages aloud", store.msgReadAloud) { store.msgReadAloud = it }, 8)
            put(ui.text("Auto-reply 🤖", 14f, Ui.INK, bold = true), 14)
            put(ui.check("Auto-reply ON (replies by itself)", store.autoReply) { store.autoReply = it }, 2)
            val autoBox = ui.input("Auto-reply message", store.autoMessage, multi = true)
            put(autoBox, 6)
            put(ui.row(ui.button("Save message") { store.autoMessage = autoBox.text.toString(); toast("Saved") }), 8)
            put(ui.check("Smart: adapt the reply to each message (language, greeting)", store.autoSmart) { store.autoSmart = it }, 6)
            put(ui.check("Add \"(auto-reply)\" at the end", store.autoTag) { store.autoTag = it }, 0)
            put(ui.text("Auto-reply never answers group chats, and replies only once per chat every 30 minutes.", 12f, Ui.MUTED), 6)
        }
    }

    private fun briefingCard() = ui.section("Good-morning briefing ☀️",
        "Every morning your pet tells you today's panchangam (tithi, nakshatram, Rahu kalam), the weather, your reminders and to-dos.").apply {
        put(ui.check("Morning briefing ON", store.briefOn) { store.briefOn = it; Briefing.schedule(this@MainActivity); render() }, 10)
        if (store.briefOn) {
            val h = store.briefHour; val m = store.briefMinute
            put(ui.line(ui.text("Time: %d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM"), 15f, Ui.INK, bold = true),
                ui.button("Change") { TimePickerDialog(this@MainActivity, { _, hh, mm -> store.briefHour = hh; store.briefMinute = mm; Briefing.schedule(this@MainActivity); render() }, h, m, false).show() }), 8)
            val cityBox = ui.input("Your town or city, e.g. Visakhapatnam", store.city)
            put(cityBox, 8)
            put(ui.row(ui.button("Save city") { store.city = cityBox.text.toString(); toast("Saved") },
                ui.button("Try it now", "primary") { store.city = cityBox.text.toString(); Briefing.runNow(this@MainActivity); toast("Your briefing will arrive in a few seconds.") }), 8)
        }
    }

    // =================== 3. TASKS ===================
    private fun tasksPage() {
        pageHeader("Tasks", "To-dos, reminders and notes")
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("todo" to "✅ To-do", "rem" to "⏰ Reminders", "notes" to "📝 Notes").forEachIndexed { i, (k, label) ->
                addView(ui.chip(label, tasksTab == k) { tasksTab = k; render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
            }
        }, 14)
        when (tasksTab) {
            "rem" -> remindersSection()
            "notes" -> notesSection()
            else -> todoSection()
        }
    }

    private fun todoSection() {
        val box = ui.input("Add a to-do, e.g. Pay electricity bill")
        add(ui.card().apply {
            addView(ui.line(box, ui.button("Add", "primary") { if (box.text.isNotBlank()) { store.addTodo(box.text.toString()); render() } }))
        }, 12)
        val todos = store.todos()
        if (todos.isEmpty()) add(ui.text("Nothing to do. Or say \"add buy milk to my list\" to your pet.", 14f, Ui.MUTED), 14)
        add(ui.card().apply {
            todos.forEachIndexed { i, t ->
                put(ui.line(ui.check(t.text, t.done) { c -> store.setTodoDone(i, c); render() }.apply { if (t.done) setTextColor(Ui.MUTED) },
                    ui.button("✕", "ghost") { store.removeTodo(i); render() }), if (i == 0) 0 else 8)
            }
            if (todos.isEmpty()) visibility = View.GONE
        }, 10)
    }

    private fun remindersSection() {
        // new reminder form
        var date = LocalDate.now(); var time = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        var repeat = ""
        val what = ui.input("What should I remind you about?")
        val dateBtn = ui.button("") { }; val timeBtn = ui.button("") { }
        fun refreshButtons() {
            dateBtn.text = "📅 " + DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(date)
            timeBtn.text = "🕒 " + DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(time)
        }
        refreshButtons()
        dateBtn.setOnClickListener { DatePickerDialog(this, { _, y, mo, d -> date = LocalDate.of(y, mo + 1, d); refreshButtons() }, date.year, date.monthValue - 1, date.dayOfMonth).show() }
        timeBtn.setOnClickListener { TimePickerDialog(this, { _, h, m -> time = LocalTime.of(h, m); refreshButtons() }, time.hour, time.minute, false).show() }
        val repeatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val repeatScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(repeatRow) }
        fun drawRepeat() {
            repeatRow.removeAllViews()
            listOf("" to "Once", "daily" to "Daily", "weekdays" to "Mon–Fri", "weekly" to "Weekly", "monthly" to "Monthly").forEachIndexed { i, (k, l) ->
                repeatRow.addView(ui.chip(l, repeat == k) { repeat = k; drawRepeat() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
            }
        }
        drawRepeat()
        add(ui.section("New reminder").apply {
            put(what, 10); put(ui.row(dateBtn, timeBtn), 10); put(repeatScroll, 10)
            put(ui.button("Save reminder", "primary") {
                val text = what.text.toString().trim()
                if (text.isEmpty()) { toast("Write what to remind you about."); return@button }
                var at = ZonedDateTime.of(date, time, ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at < System.currentTimeMillis()) { if (repeat.isEmpty()) { toast("That time has already passed."); return@button } else at = Reminders.nextTime(at, repeat) }
                val (_, exact) = Reminders.add(this@MainActivity, at, text, repeat)
                toast("Reminder saved" + if (exact) "" else " (may ring a few minutes late)"); render()
            }, 12)
        }, 12)

        val rems = store.reminders().sortedBy { it.at }
        add(ui.label("Coming up"), 18)
        if (rems.isEmpty()) add(ui.text("No reminders. You can also say \"remind me to take medicine every day at 8 am\".", 14f, Ui.MUTED), 8)
        rems.forEach { r ->
            add(ui.card().apply {
                addView(ui.line(ui.stack(ui.text(r.text, 15.5f, Ui.INK, bold = true),
                    ui.text(fmt.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())) + Reminders.repeatLabel(r.repeat).let { if (it.isEmpty()) "" else "  ·  🔁 $it" }, 13f, Ui.MUTED)),
                    ui.button("Cancel", "danger") { Reminders.cancel(this@MainActivity, r); render() }))
            }, 8)
        }
    }

    private fun notesSection() {
        val box = ui.input("Write a note…", multi = true)
        add(ui.card().apply {
            addView(box)
            put(ui.row(ui.button("Save note", "primary") { if (box.text.isNotBlank()) { store.addNote(box.text.toString()); render() } }), 10)
        }, 12)
        val notes = store.notes()
        if (notes.isEmpty()) add(ui.text("No notes yet. Or say \"note this number 98480 12345\".", 14f, Ui.MUTED), 14)
        notes.withIndex().reversed().forEach { (i, n) ->
            add(ui.card(Ui.SUN_SOFT).apply {
                addView(ui.text(n, 15f).apply { setTextIsSelectable(true) })
                put(ui.row(ui.button("Copy", "ghost") {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("note", n)); toast("Copied")
                }, ui.button("Delete", "danger") { store.removeNote(i); render() }), 10)
            }, 10)
        }
    }

    // =================== 4. FILES ===================
    private fun filesPage() {
        pageHeader("Files", "Word letters and Excel sheets made by your pet")
        val ask = ui.input("Describe the file, e.g. \"NOC letter for Sy.No. 370/1 as Word\"", multi = true)
        val status = ui.text("", 13f, Ui.MUTED)
        add(ui.section("Make a new file").apply {
            put(ask, 10)
            put(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("Word letter", "Excel table", "Report").forEachIndexed { i, s ->
                    addView(ui.chip(s, false) {
                        ask.setText(when (s) { "Word letter" -> "Write a letter as a Word file: "; "Excel table" -> "Make an Excel sheet with columns: "; else -> "Write a report as a Word file about: " })
                        ask.setSelection(ask.text.length); ask.requestFocus()
                    }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
                }
            }, 10)
            put(ui.button("✨ Make file", "primary") {
                val q = ask.text.toString().trim()
                if (q.length < 8) { toast("Tell me a little more about the file."); return@button }
                status.text = "Writing your file… this can take up to a minute."
                val acts = Actions(this@MainActivity) { t, body -> AlertDialog.Builder(this@MainActivity).setTitle(t).setMessage(body).setPositiveButton("OK", null).show() }
                Brain(store).ask("$q (make it as a file)", onReply = { json ->
                    val list = json.optJSONArray("actions")
                    var made = false
                    if (list != null) for (i in 0 until list.length()) { val a = list.optJSONObject(i) ?: continue; acts.run(a)?.let { if (a.optString("type") == "download") made = true } }
                    status.text = if (made) "✅ Done. Your file is downloading." else json.optString("reply")
                    if (made) { ask.setText(""); content.postDelayed({ if (tab == "files") render() }, 1500) }
                }, onError = { err -> status.text = "⚠️ $err" })
            }, 12)
            put(status, 8)
        }, 12)

        val files = store.files().sortedByDescending { it.at }
        add(ui.label("Your files"), 18)
        if (files.isEmpty()) add(ui.text("No files yet. Ask above, or tell your pet \"make an Excel list of my cases\".", 14f, Ui.MUTED), 8)
        val dm = getSystemService(DownloadManager::class.java)
        files.forEach { f ->
            val xl = f.format == "xlsx"
            val badge = ui.text(if (xl) "X" else "W", 18f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER; background = ui.round(Color.parseColor(if (xl) "#1D7A47" else "#2B579A"), 12)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            }
            add(ui.card().apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(badge)
                    addView(ui.stack(ui.text(f.name, 15f, Ui.INK, bold = true), ui.text(fmt.format(Instant.ofEpochMilli(f.at).atZone(ZoneId.systemDefault())), 12.5f, Ui.MUTED)),
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                })
                val mime = if (xl) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                put(ui.row(
                    ui.button("Open", "primary") {
                        val uri = dm.getUriForDownloadedFile(f.id) ?: run { toast("This file is not on the phone any more."); return@button }
                        try { leavingForSystemScreen = true; startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (e: Exception) { toast("Install Microsoft Word / Excel or Google Docs / Sheets to open it.") }
                    },
                    ui.button("Share") {
                        val uri = dm.getUriForDownloadedFile(f.id) ?: run { toast("This file is not on the phone any more."); return@button }
                        leavingForSystemScreen = true
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}"))
                    },
                    ui.button("Remove", "danger") {
                        AlertDialog.Builder(this@MainActivity).setTitle("Remove ${f.name}?").setMessage("The file will be deleted from this phone.")
                            .setPositiveButton("Remove") { _, _ -> try { dm.remove(f.id) } catch (e: Exception) { }; store.removeFile(f.id); render() }
                            .setNegativeButton("Keep", null).show()
                    }), 12)
            }, 8)
        }
    }

    // =================== 5. VAULT ===================
    private fun vaultPage() {
        pageHeader("Vault 🔒", "Your documents and cards, locked on this phone")
        if (!VaultLock.isOpen()) {
            add(ui.card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ui.text("🔐", 46f).apply { gravity = Gravity.CENTER })
                put(ui.text("Locked", 20f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }, 6)
                put(ui.text("Open with your fingerprint or phone lock. Screenshots are blocked here, and the Vault locks again after 2 minutes or when you leave the app.", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER }, 6)
                put(ui.button("Unlock Vault", "primary") { unlockVault() }, 14)
            }, 14)
            add(ui.card(Ui.TEAL_SOFT).apply {
                addView(ui.text("How your Vault is protected", 14.5f, Ui.TEAL_DARK, bold = true))
                put(ui.text("• Encrypted with a key kept in your phone's secure chip\n• Never sent to the BigD server or to the AI\n• Not included in phone backups\n• No CVV or PIN boxes: banks say never to store those anywhere", 13.5f, Ui.INK), 6)
            }, 12)
            return
        }
        val items = vault.items()
        add(ui.row(ui.button("＋ Add document", "primary") { editVaultItem(null, "aadhaar") }, ui.button("＋ Add card / bank", "sun") { editVaultItem(null, "card") }), 14)
        add(ui.button("🔒 Lock now", "ghost") { VaultLock.lock(); render() }.apply { }, 10)
        if (items.isEmpty()) add(ui.text("Empty. Add your Aadhaar, PAN, licence, bank cards and accounts.", 14f, Ui.MUTED), 16)
        val groups = listOf("Bank cards & accounts" to items.filter { it.type == "card" || it.type == "bank" }, "Documents" to items.filter { it.type != "card" && it.type != "bank" })
        groups.forEach { (name, list) ->
            if (list.isEmpty()) return@forEach
            add(ui.label(name), 18)
            list.sortedBy { it.title.lowercase() }.forEach { add(vaultCard(it), 8) }
        }
    }

    private fun unlockVault() {
        leavingForSystemScreen = true
        VaultLock.unlock(this) { err -> leavingForSystemScreen = false; if (err == null) render() else toast(err) }
    }

    private fun vaultCard(item: VaultItem): View {
        val isCard = item.type == "card"
        val card = if (isCard) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#14383A"), Color.parseColor("#0E6B6B"))).apply { cornerRadius = ui.dpf(20) }
        } else ui.card()
        val fg = if (isCard) Color.WHITE else Ui.INK
        val sub = if (isCard) Color.parseColor("#BFE3DF") else Ui.MUTED
        var shown = false
        val num = ui.text(VaultItem.masked(item.number), if (isCard) 20f else 16f, fg, bold = true).apply { letterSpacing = 0.06f }
        card.addView(ui.line(ui.text(VaultItem.icon(item.type) + "  " + item.title, 15.5f, fg, bold = true), ui.text(VaultItem.label(item.type), 12f, sub)))
        card.addView(num, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (isCard) 14 else 8) })
        val details = listOfNotNull(
            item.holder.takeIf { s -> s.isNotBlank() }?.let { s -> (if (item.type == "bank") "Holder: " else "Name: ") + s },
            item.bank.takeIf { s -> s.isNotBlank() }?.let { s -> "Bank: $s" },
            item.extra.takeIf { s -> s.isNotBlank() }?.let { s -> when (item.type) { "card" -> "Valid thru: $s"; "bank" -> "IFSC: $s"; else -> "Date / validity: $s" } },
            item.notes.takeIf { s -> s.isNotBlank() }
        )
        if (details.isNotEmpty()) card.addView(ui.text(details.joinToString("\n"), 13f, sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val showBtn = ui.button("Show", if (isCard) "sun" else "soft") { }
        showBtn.setOnClickListener {
            if (!VaultLock.isOpen()) { render(); return@setOnClickListener }
            shown = !shown; num.text = if (shown) item.number else VaultItem.masked(item.number); showBtn.text = if (shown) "Hide" else "Show"
        }
        val buttons = mutableListOf<View>(showBtn,
            ui.button("Copy", if (isCard) "ghost" else "soft") { if (VaultLock.isOpen()) { VaultLock.copySecret(this, item.title, item.number); toast("Copied. It will be cleared from the clipboard in 1 minute.") } else render() })
        if (item.photo) buttons.add(ui.button("Photo", if (isCard) "ghost" else "soft") { showVaultPhoto(item) })
        buttons.add(ui.button("⋯", if (isCard) "ghost" else "soft") { vaultMenu(item) })
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ui.row(*buttons.toTypedArray())) }
        card.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return card
    }

    private fun vaultMenu(it: VaultItem) {
        if (!VaultLock.isOpen()) { render(); return }
        val opts = arrayOf("Edit", if (it.photo) "Change photo" else "Add photo", "Delete")
        AlertDialog.Builder(this).setTitle(it.title).setItems(opts) { _, w ->
            when (w) {
                0 -> editVaultItem(it, it.type)
                1 -> { pickingPhotoFor = it.id; startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PHOTO) }
                2 -> AlertDialog.Builder(this).setTitle("Delete ${it.title}?").setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> vault.remove(it.id); render() }.setNegativeButton("Keep", null).show()
            }
        }.show()
    }

    private fun showVaultPhoto(it: VaultItem) {
        if (!VaultLock.isOpen()) { render(); return }
        val bytes = vault.photo(it.id) ?: run { toast("Photo not found."); return }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run { toast("Couldn't open the photo."); return }
        val img = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val d = AlertDialog.Builder(this).setTitle(it.title).setView(img).setPositiveButton("Close", null).create()
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.show()
    }

    /** Add or edit a vault item. Type decides which boxes show. */
    private fun editVaultItem(existing: VaultItem?, startType: String) {
        if (!VaultLock.isOpen()) { render(); return }
        var type = existing?.type ?: startType
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val typeScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(typeRow) }
        val title = ui.input("Name, e.g. \"My Aadhaar\" or \"SBI Debit card\"", existing?.title ?: "")
        val number = ui.input("Number", existing?.number ?: "", number = true)
        val holder = ui.input("Name on it", existing?.holder ?: "")
        val bank = ui.input("Bank name", existing?.bank ?: "")
        val extra = ui.input("", existing?.extra ?: "")
        val notes = ui.input("Notes (optional)", existing?.notes ?: "", multi = true)
        val warn = ui.text("For your safety there is no box for CVV or PIN. Never store or share them.", 12.5f, Ui.DANGER)
        fun layout() {
            typeRow.removeAllViews()
            VaultItem.TYPES.forEachIndexed { i, (k, l, ic) ->
                typeRow.addView(ui.chip("$ic $l", type == k) { type = k; layout() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
            }
            val money = type == "card" || type == "bank"
            bank.visibility = if (money) View.VISIBLE else View.GONE
            warn.visibility = if (type == "card") View.VISIBLE else View.GONE
            number.hint = when (type) { "card" -> "Card number"; "bank" -> "Account number"; "aadhaar" -> "Aadhaar number"; "pan" -> "PAN number"; else -> "Document number" }
            holder.hint = when (type) { "card" -> "Name on card"; "bank" -> "Account holder"; else -> "Name on document" }
            extra.hint = when (type) { "card" -> "Valid thru (MM/YY)"; "bank" -> "IFSC code"; else -> "Date of birth / validity (optional)" }
        }
        layout()
        listOf<View>(typeScroll, title, number, holder, bank, extra, notes, warn).forEach { form.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        val sv = ScrollView(this).apply { addView(form) }
        val d = AlertDialog.Builder(this).setTitle(if (existing == null) "Add to Vault" else "Edit").setView(sv)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!VaultLock.isOpen()) { d.dismiss(); render(); return@setOnClickListener }
                val n = number.text.toString().trim()
                if (n.isEmpty()) { number.error = "Enter the number"; return@setOnClickListener }
                val t = title.text.toString().trim().ifEmpty { VaultItem.label(type) }
                vault.put(VaultItem(existing?.id ?: UUID.randomUUID().toString(), type, t, n, holder.text.toString().trim(),
                    extra.text.toString().trim(), if (type == "card" || type == "bank") bank.text.toString().trim() else "", notes.text.toString().trim(), existing?.photo ?: false))
                d.dismiss(); VaultLock.markOpen(); render()
            }
        }
        d.show()
    }

    // =================== 6. FEEDBACK ===================
    private fun feedbackPage() {
        pageHeader("Feedback", "Tell us what to fix or add. We read every message.")
        var rating = 0; var kind = "Idea"
        val stars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawStars() {
            stars.removeAllViews()
            for (i in 1..5) stars.addView(ui.text(if (i <= rating) "★" else "☆", 34f, if (i <= rating) Ui.SUN else Ui.LINE).apply {
                setPadding(dp(4), 0, dp(4), 0); setOnClickListener { rating = i; drawStars() }
            })
        }
        drawStars()
        val kinds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawKinds() {
            kinds.removeAllViews()
            listOf("Idea" to "💡 Idea", "Problem" to "🐞 Problem", "Praise" to "❤️ Praise").forEachIndexed { i, (k, l) ->
                kinds.addView(ui.chip(l, kind == k) { kind = k; drawKinds() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
            }
        }
        drawKinds()
        val msg = ui.input("Write here, in Telugu or English…", multi = true)
        val status = ui.text("", 13.5f, Ui.MUTED)
        add(ui.section("How is BigD AI?").apply {
            put(stars, 8); put(kinds, 12); put(msg, 12)
            put(ui.button("Send feedback", "primary") {
                val text = msg.text.toString().trim()
                if (text.isEmpty() && rating == 0) { toast("Add a rating or a message."); return@button }
                status.text = "Sending…"
                sendFeedback(rating, kind, text) { ok -> status.text = if (ok) "✅ Thank you! Your feedback was sent." else "⚠️ Couldn't send. Check your internet and try again."; if (ok) msg.setText("") }
            }, 12)
            put(status, 8)
        }, 14)

        add(ui.section("Share BigD AI", "Know someone who would like a pet friend on their phone?").apply {
            put(ui.row(ui.button("Share with friends", "sun") {
                leavingForSystemScreen = true
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Try BigD AI 🐾 – a cute pet on your phone screen that talks in Telugu and English, " +
                        (if (BuildConfig.MESSAGE_REPLIES) "sets reminders, writes letters and replies to WhatsApp." else "sets reminders and writes letters.") +
                        " ${store.server}"), "Share BigD AI"))
            }), 10)
        }, 12)

        add(ui.section("About").apply {
            put(ui.text("BigD AI is an AI and can make mistakes. Check important things before you rely on them.", 13.5f, Ui.MUTED), 6)
            put(ui.row(ui.button("Privacy policy", "ghost") {
                leavingForSystemScreen = true
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store.server + "/privacy"))) } catch (e: Exception) { }
            }), 10)
            val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
            put(ui.text("Version $v", 12.5f, Ui.MUTED), 10)
        }, 12)
    }

    private fun sendFeedback(rating: Int, kind: String, text: String, done: (Boolean) -> Unit) {
        val body = JSONObject().put("rating", rating).put("kind", kind).put("text", text)
            .put("version", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" })
            .put("android", Build.VERSION.RELEASE).put("pet", store.petKind).toString()
        val server = store.server
        io.execute {
            val ok = try {
                val c = java.net.URL("$server/api/feedback").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.setRequestProperty("X-Device", store.deviceId)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode; c.disconnect(); code in 200..299
            } catch (e: Exception) { false }
            runOnUiThread { done(ok) }
        }
    }

    // =================== first-time setup guide ===================
    private var setupStep = 0

    private fun renderSetup() {
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            for (i in 0..3) addView(View(this@MainActivity).apply { background = ui.round(if (i <= setupStep) Ui.TEAL else Ui.LINE, 4) },
                LinearLayout.LayoutParams(dp(if (i == setupStep) 26 else 10), dp(8)).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        add(dots, 8)
        fun nav(nextLabel: String = "Next", onNext: () -> Unit = { setupStep++; render(); scroll.scrollTo(0, 0) }) {
            add(ui.line(if (setupStep > 0) ui.button("Back", "ghost") { setupStep--; render() } else View(this), ui.button(nextLabel, "primary") { onNext() }), 22)
        }
        fun bigPet(m: PuppyView.Mood) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(PuppyView(this@MainActivity).apply { kind = PuppyView.kindOf(store.petKind); mood = m }, LinearLayout.LayoutParams(dp(176), dp(176)))
        }
        when (setupStep) {
            0 -> {
                add(bigPet(PuppyView.Mood.EXCITED), 16)
                add(ui.text("నమస్తే! I'm BigD AI", 27f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }, 8)
                add(ui.text("Your pet friend on your phone. I talk in Telugu and English, set reminders, make Word and Excel files, " +
                    (if (BuildConfig.MESSAGE_REPLIES) "reply to WhatsApp and keep your cards safe." else "and keep your cards safe."), 15f, Ui.MUTED).apply { gravity = Gravity.CENTER }, 8)
                add(ui.section("Choose your language · మీ భాష").apply {
                    put(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        listOf("te" to "తెలుగు", "en" to "English", "auto" to "Both").forEachIndexed { i, (code, label) ->
                            addView(ui.chip(label, store.lang == code) { store.lang = code; render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
                        }
                    }, 12)
                }, 18)
                nav()
            }
            1 -> {
                add(ui.title("Choose your pet"), 14); add(ui.text("మీ పెంపుడు జంతువు · you can change it any time", 14f, Ui.MUTED), 4)
                add(ui.card().apply { petPicker(this) }, 14)
                nav()
            }
            2 -> {
                add(ui.title("Allow a few things"), 14)
                add(ui.text("Only \"Show over other apps\" is needed to start. You can allow the others later.", 14f, Ui.MUTED), 4)
                add(ui.card().apply { permissionRows(this) }, 14)
                nav()
            }
            else -> {
                add(bigPet(PuppyView.Mood.LOVE), 16)
                add(ui.text("All set! 🎉", 27f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }, 8)
                add(ui.card().apply {
                    listOf("👆" to "Tap me to talk", "✋" to "Hold me for the menu: type, photo, lists", "↕️" to "Drag me anywhere; drag me down to ✕ to close",
                        "📱" to "Open this app for tasks, files, your Vault and settings").forEachIndexed { i, (ic, t) ->
                        put(ui.row(ui.text(ic, 20f), ui.text(t, 15f), gap = 12), if (i == 0) 0 else 12)
                    }
                }, 14)
                nav("Start! Show my pet") {
                    store.onboarded = true; tab = "home"
                    if (Settings.canDrawOverlays(this)) { startForegroundService(Intent(this, PetService::class.java)); moveTaskToBack(true) }
                    else toast("Allow \"Show over other apps\" on the Pet page, then tap Show my pet.")
                    content.postDelayed({ render() }, 400)
                }
            }
        }
    }

    // =================== system results ===================
    private fun showMessageDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Read message notifications?")
            .setMessage("To suggest and send replies, BigD AI reads new message notifications from the chat apps you choose (for example WhatsApp or SMS).\n\n" +
                "For each new message, the sender's name and the message text are sent to the BigD AI server and to Anthropic's Claude AI to write a reply. They are not stored on the server.\n\n" +
                "Nothing is sent to the other person unless you tap Send or switch on Auto-reply. You can turn this off at any time.")
            .setPositiveButton("Agree and continue") { _, _ -> store.msgWatch = true; leavingForSystemScreen = true; MessageHub.openAccessSettings(this) }
            .setNegativeButton("Not now", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VaultLock.REQ_UNLOCK -> { if (resultCode == RESULT_OK) VaultLock.markOpen(); render() }
            REQ_PHOTO -> {
                val id = pickingPhotoFor; pickingPhotoFor = null
                val uri = data?.data
                if (resultCode != RESULT_OK || id == null || uri == null || !VaultLock.isOpen()) { render(); return }
                io.execute {
                    val ok = try {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
                        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                        if (bmp == null) false else {
                            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            vault.savePhoto(id, out.toByteArray())
                            vault.items().firstOrNull { it.id == id }?.let { vault.put(it.copy(photo = true, updated = System.currentTimeMillis())) }
                            true
                        }
                    } catch (e: Exception) { false }
                    runOnUiThread { toast(if (ok) "Photo saved in your Vault." else "Couldn't save that photo."); render() }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && PetService.running) {
            stopService(Intent(this, PetService::class.java))
            content.postDelayed({ startForegroundService(Intent(this, PetService::class.java)) }, 500)
        }
        render()
    }
}
