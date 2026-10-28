package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Setup screen: permissions, server address, start/stop the puppy, and your lists. */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private val navy = Color.parseColor("#1C2140")
    private val muted = Color.parseColor("#5F6688")
    private val bg = Color.parseColor("#EEF1FA")
    private val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Reminders.ensureChannel(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); fitsSystemWindows = true; addView(root) })
    }

    override fun onResume() { super.onResume(); render() }

    // ---------- small building blocks ----------
    private fun text(s: String, size: Float = 15f, color: Int = navy, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setLineSpacing(0f, 1.2f); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; stateListAnimator = null
        setTextColor(if (primary) Color.parseColor("#1C1406") else navy)
        background = GradientDrawable().apply { setColor(Color.parseColor(if (primary) "#F5A524" else "#E3E7F8")); cornerRadius = dp(22).toFloat() }
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
    }

    private fun add(v: View, top: Int = 12) = root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun LinearLayout.put(v: View, top: Int = 8) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** The 6 pets to choose from. */
    private fun petPicker(petCard: LinearLayout) {
        val current = PuppyView.kindOf(store.petKind)
        PuppyView.Kind.values().toList().chunked(3).forEach { rowKinds ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowKinds.forEach { k ->
                val chosen = k == current
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (chosen) "#FFF3D6" else "#F4F6FC")); cornerRadius = dp(16).toFloat()
                        if (chosen) setStroke(dp(2), Color.parseColor("#F5A524"))
                    }
                    addView(PuppyView(this@MainActivity).apply { kind = k; mood = if (chosen) PuppyView.Mood.EXCITED else PuppyView.Mood.HAPPY }, LinearLayout.LayoutParams(dp(72), dp(72)))
                    addView(text((if (chosen) "✓ " else "") + k.label, 14f, bold = true).apply { gravity = Gravity.CENTER })
                    addView(text(k.telugu, 12f, muted).apply { gravity = Gravity.CENTER })
                    setOnClickListener {
                        store.petKind = k.key
                        if (PetService.running) startService(Intent(this@MainActivity, PetService::class.java).setAction(PetService.ACTION_PET))
                        render()
                    }
                }
                r.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            petCard.put(r, 10)
        }
    }

    /** Overlay, microphone, notifications, exact alarms, contacts and Telugu voice. */
    private fun permissionRows(perms: LinearLayout) {
        val ready = Settings.canDrawOverlays(this)
        fun step(done: Boolean, label: String, why: String, action: () -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text((if (done) "✅ " else "⬜ ") + label, 15f, bold = true)); addView(text(why, 12.5f, muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (!done) r.addView(button("Allow") { action() })
            perms.put(r, 12)
        }
        step(ready, "Show over other apps", "So the puppy can float on your screen.") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        step(granted(Manifest.permission.RECORD_AUDIO), "Microphone", "Only while you tap the puppy to talk. Your voice is turned into text by your phone's voice typing.") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) step(granted(Manifest.permission.POST_NOTIFICATIONS), "Notifications", "For reminders and timers.") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            step(am.canScheduleExactAlarms(), "Exact reminders", "So reminders ring at the exact minute.") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        step(granted(Manifest.permission.READ_CONTACTS), "Contacts (optional)", "Only to find a number when you say \"call Ravi\". Contacts are never sent to the server.") {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3)
        }
        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        voiceRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Telugu voice", 15f, bold = true)); addView(text("Install it once so the puppy can speak Telugu.", 12.5f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        voiceRow.addView(button("Install") {
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
            catch (e: Exception) { Toast.makeText(this, "Open Settings › Text-to-speech › Google › Install voice data › Telugu", Toast.LENGTH_LONG).show() }
        })
        perms.put(voiceRow, 12)
    }

    // ---------- first-time setup guide ----------
    private var setupStep = 0

    private fun renderSetup() {
        root.removeAllViews()
        add(text("Step ${setupStep + 1} of 4", 13f, muted), 0)
        fun nav(nextLabel: String = "Next ›", onNext: () -> Unit = { setupStep++; render() }) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (setupStep > 0) r.addView(button("‹ Back") { setupStep--; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            r.addView(button(nextLabel, primary = true) { onNext() })
            add(r, 20)
        }
        fun bigPet(m: PuppyView.Mood) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(PuppyView(this@MainActivity).apply { kind = PuppyView.kindOf(store.petKind); mood = m }, LinearLayout.LayoutParams(dp(170), dp(170)))
        }
        when (setupStep) {
            0 -> {
                add(bigPet(PuppyView.Mood.EXCITED), 8)
                add(text("నమస్తే! I'm BigD AI 🐾", 26f, bold = true).apply { gravity = Gravity.CENTER }, 8)
                add(text("Your pet friend on your phone screen. I talk in Telugu and English, set reminders, write messages, reply to WhatsApp and more.", 15f, muted).apply { gravity = Gravity.CENTER }, 8)
                val c = card()
                c.put(text("Choose your language · మీ భాష", 18f, bold = true), 0)
                val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                listOf("te" to "తెలుగు", "en" to "English", "auto" to "Both").forEach { (code, label) ->
                    langRow.addView(button((if (store.lang == code) "● " else "") + label, primary = store.lang == code) { store.lang = code; render() },
                        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                }
                c.put(langRow, 10)
                add(c, 18)
                nav()
            }
            1 -> {
                add(text("Choose your pet · మీ పెంపుడు జంతువు", 22f, bold = true), 8)
                add(text("You can change it any time.", 14f, muted), 4)
                val c = card(); petPicker(c); add(c, 14)
                nav()
            }
            2 -> {
                add(text("Allow a few things · అనుమతులు", 22f, bold = true), 8)
                add(text("Only \"Show over other apps\" is needed to start. You can allow the others later.", 14f, muted), 4)
                val c = card(); permissionRows(c); add(c, 14)
                nav()
            }
            else -> {
                add(bigPet(PuppyView.Mood.LOVE), 8)
                add(text("All set! 🎉", 26f, bold = true).apply { gravity = Gravity.CENTER }, 8)
                val c = card()
                c.put(text("👆 Tap me to talk\n✋ Hold me for the menu (type, photo, lists)\n↕️ Drag me anywhere; drag me down to ✕ to close\n💬 Later: turn on WhatsApp replies and the ☀️ morning briefing in this app", 15f), 0)
                add(c, 14)
                nav("Start! Show my pet 🐾") {
                    store.onboarded = true
                    if (Settings.canDrawOverlays(this)) { startForegroundService(Intent(this, PetService::class.java)); moveTaskToBack(true) }
                    else Toast.makeText(this, "Allow 'Show over other apps' in Setup, then tap Show my pet.", Toast.LENGTH_LONG).show()
                    root.postDelayed({ render() }, 400)
                }
            }
        }
    }

    // ---------- the screen ----------
    private fun render() {
        if (!store.onboarded) { renderSetup(); return }
        root.removeAllViews()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(PuppyView(this).apply { kind = PuppyView.kindOf(store.petKind); mood = if (PetService.running) PuppyView.Mood.EXCITED else PuppyView.Mood.HAPPY }, LinearLayout.LayoutParams(dp(120), dp(120)))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(text("BigD AI", 26f, bold = true)); addView(text("బిగ్ డి · your pet friend", 15f, muted))
        })
        add(head, 0)

        // start / stop
        val ready = Settings.canDrawOverlays(this)
        add(button(if (PetService.running) "Close my pet" else "Show my pet on the screen", primary = true) {
            if (PetService.running) stopService(Intent(this, PetService::class.java))
            else if (!ready) Toast.makeText(this, "First allow 'Show over other apps' below", Toast.LENGTH_LONG).show()
            else { startForegroundService(Intent(this, PetService::class.java)); moveTaskToBack(true) }
            root.postDelayed({ render() }, 400)
        }, 16)
        add(text("Tap the pet to talk. Hold it for the menu. Drag it anywhere. To close it, drag it down to the ✕.\nనొక్కి మాట్లాడండి. మెనూ కోసం నొక్కి పట్టుకోండి. మూసివేయడానికి కిందకి ✕ వద్దకు లాగండి.", 13f, muted), 8)

        // choose a pet
        val petCard = card()
        petCard.put(text("Choose your pet · మీ పెంపుడు జంతువు", 18f, bold = true), 0)
        petPicker(petCard)
        add(petCard, 14)

        // permissions
        val perms = card()
        perms.put(text("Setup · సెటప్", 18f, bold = true), 0)
        permissionRows(perms)
        add(perms, 18)

        // language + server
        val settings = card()
        settings.put(text("Language · భాష", 18f, bold = true), 0)
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("auto" to "Auto", "te" to "తెలుగు", "en" to "English").forEach { (code, label) ->
            langRow.addView(button((if (store.lang == code) "● " else "") + label, primary = store.lang == code) { store.lang = code; render() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        settings.put(langRow, 10)
        settings.put(text("Auto: the puppy listens in Telugu and replies in the language you use.", 12.5f, muted), 6)
        settings.put(CheckBox(this).apply {
            text = "Hide personal details before sending (Aadhaar, phone and PAN numbers become XXXX…1234)"
            isChecked = store.hidePersonal; textSize = 14f; setTextColor(navy)
            setOnCheckedChangeListener { _, c -> store.hidePersonal = c }
        }, 10)
        settings.put(text("Useful for office files. Photos are sent as they are.", 12f, muted), 2)
        settings.put(text("Server address", 15f, bold = true), 16)
        val serverBox = EditText(this).apply {
            setText(store.server); textSize = 14f; inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT; setSingleLine()
            setTextColor(navy)
        }
        settings.put(serverBox, 4)
        settings.put(button("Save address") {
            val v = serverBox.text.toString().trim()
            if (!v.startsWith("https://")) Toast.makeText(this, "The address must start with https://", Toast.LENGTH_LONG).show()
            else { store.server = v; Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
        }, 6)
        add(settings, 14)

        // message replies
        val msgCard = card()
        msgCard.put(text("Reply to messages · మెసేజ్ రిప్లైలు", 18f, bold = true), 0)
        msgCard.put(text("When a WhatsApp or SMS message comes, your pet shows it and writes a reply. Nothing is sent until you tap Send, unless Auto-reply is on.", 12.5f, muted), 4)
        val access = MessageHub.accessGranted(this)
        val accRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        accRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text((if (access) "✅ " else "⬜ ") + "Notification access", 15f, bold = true))
            addView(text("Lets the pet read new message notifications from the apps you tick below.", 12.5f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!access) accRow.addView(button("Allow") { showMessageDisclosure() })
        msgCard.put(accRow, 10)
        if (!access) msgCard.put(text("If Android says \"Restricted setting\": open Settings › Apps › BigD AI › ⋮ (top right) › Allow restricted settings, then tap Allow again.", 12f, muted), 4)

        fun check(label: String, on: Boolean, change: (Boolean) -> Unit) = CheckBox(this).apply {
            text = label; isChecked = on; textSize = 15f; setTextColor(navy)
            setOnCheckedChangeListener { _, c -> change(c); render() }
        }
        msgCard.put(check("Watch new messages", store.msgWatch) { store.msgWatch = it }, 10)
        if (store.msgWatch) {
            msgCard.put(text("Apps to watch", 14f, bold = true), 8)
            val apps = store.msgApps
            MessageHub.APPS.forEach { (key, label, _) ->
                msgCard.put(check(label, key in apps) { on -> store.msgApps = if (on) apps + key else apps - key }, 0)
            }
            msgCard.put(check("Read new messages aloud", store.msgReadAloud) { store.msgReadAloud = it }, 8)

            msgCard.put(text("Auto-reply 🤖", 14f, bold = true), 12)
            msgCard.put(check("Auto-reply ON (replies by itself)", store.autoReply) { store.autoReply = it }, 0)
            val autoBox = EditText(this).apply {
                setText(store.autoMessage); textSize = 14f; setTextColor(navy); hint = "Auto-reply message"
                setSingleLine(false); maxLines = 3
            }
            msgCard.put(autoBox, 4)
            msgCard.put(button("Save message") {
                store.autoMessage = autoBox.text.toString(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }, 4)
            msgCard.put(check("Smart: adapt the reply to each message (language, greeting)", store.autoSmart) { store.autoSmart = it }, 4)
            msgCard.put(check("Add \"(auto-reply)\" at the end", store.autoTag) { store.autoTag = it }, 0)
            msgCard.put(text("Auto-reply never answers group chats, and replies only once per chat every 30 minutes. It works only while your pet's notification access is on.", 12f, muted), 4)
        }
        add(msgCard, 14)

        // good-morning briefing
        val briefCard = card()
        briefCard.put(text("Good-morning briefing ☀️ · శుభోదయం", 18f, bold = true), 0)
        briefCard.put(text("Every morning your pet tells you today's panchangam (tithi, nakshatram, Rahu kalam), the weather, your reminders and to-dos.", 12.5f, muted), 4)
        briefCard.put(check("Morning briefing ON", store.briefOn) { store.briefOn = it; Briefing.schedule(this) }, 8)
        if (store.briefOn) {
            val h = store.briefHour; val m = store.briefMinute
            val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            timeRow.addView(text("Time: %d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM"), 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            timeRow.addView(button("Change") {
                TimePickerDialog(this, { _, hh, mm -> store.briefHour = hh; store.briefMinute = mm; Briefing.schedule(this); render() }, h, m, false).show()
            })
            briefCard.put(timeRow, 6)
            val cityBox = EditText(this).apply { setText(store.city); hint = "Your town or city, e.g. Visakhapatnam"; textSize = 14f; setSingleLine(); setTextColor(navy) }
            briefCard.put(cityBox, 6)
            val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            bRow.addView(button("Save city") { store.city = cityBox.text.toString(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            bRow.addView(button("Try it now") {
                store.city = cityBox.text.toString(); Briefing.runNow(this)
                Toast.makeText(this, "Your briefing will arrive in a few seconds.", Toast.LENGTH_LONG).show()
            })
            briefCard.put(bRow, 6)
        }
        add(briefCard, 14)

        // to-do list
        val todoCard = card()
        todoCard.put(text("My to-do list · పనుల జాబితా", 18f, bold = true), 0)
        val todos = store.todos()
        if (todos.isEmpty()) todoCard.put(text("Empty. Say \"add buy milk to my list\".", 13f, muted))
        todos.forEachIndexed { i, t ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(CheckBox(this).apply {
                text = t.text; isChecked = t.done; textSize = 15f; setTextColor(if (t.done) muted else navy)
                setOnCheckedChangeListener { _, c -> store.setTodoDone(i, c); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(button("✕") { store.removeTodo(i); render() })
            todoCard.put(r, 4)
        }
        val newTodo = EditText(this).apply { hint = "Add an item"; textSize = 15f; setSingleLine(); setTextColor(navy) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addRow.addView(newTodo, LinearLayout.LayoutParams(0, -2, 1f))
        addRow.addView(button("Add") { store.addTodo(newTodo.text.toString()); render() })
        todoCard.put(addRow, 8)
        add(todoCard, 14)

        // reminders
        val remCard = card()
        remCard.put(text("Reminders and timers", 18f, bold = true), 0)
        val rems = store.reminders().sortedBy { it.at }
        if (rems.isEmpty()) remCard.put(text("None. Say \"remind me at 5 pm to call the office\" or \"remind me to take medicine every day at 8 am and 8 pm\".", 13f, muted))
        rems.forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(r.text, 15f)); addView(text(fmt.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())) + Reminders.repeatLabel(r.repeat).let { if (it.isEmpty()) "" else " · 🔁 $it" }, 12.5f, muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Cancel") { Reminders.cancel(this, r); render() })
            remCard.put(row, 8)
        }
        add(remCard, 14)

        // memory
        val memCard = card()
        memCard.put(text("What the puppy remembers about you", 18f, bold = true), 0)
        memCard.put(text("Saved only on this phone. Say \"forget that\" or tap ✕.", 12.5f, muted), 4)
        val mems = store.memories()
        if (mems.isEmpty()) memCard.put(text("Nothing yet. Tell it about yourself, for example \"My daughter's name is Sita\".", 13f, muted))
        mems.forEachIndexed { i, m ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(m, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("✕") { store.removeMemory(i); render() })
            memCard.put(row, 8)
        }
        add(memCard, 14)

        // notes
        val noteCard = card()
        noteCard.put(text("Notes", 18f, bold = true), 0)
        val notes = store.notes()
        if (notes.isEmpty()) noteCard.put(text("None. Say \"note this number 98480 12345\".", 13f, muted))
        notes.forEachIndexed { i, n ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(n, 15f).apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("✕") { store.removeNote(i); render() })
            noteCard.put(row, 8)
        }
        add(noteCard, 14)

        add(text("BigD AI is an AI and can make mistakes. Check important things before you rely on them.", 12.5f, muted), 18)
        add(button("Privacy policy") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store.server + "/privacy"))) } catch (e: Exception) { }
        }, 8)
    }

    /** Google Play asks apps to explain clearly before asking for notification access. */
    private fun showMessageDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Read message notifications?")
            .setMessage("To suggest and send replies, BigD AI reads new message notifications from the chat apps you choose (for example WhatsApp or SMS).\n\n" +
                "For each new message, the sender's name and the message text are sent to the BigD AI server and to Anthropic's Claude AI to write a reply. They are not stored on the server.\n\n" +
                "Nothing is sent to the other person unless you tap Send or switch on Auto-reply. You can turn this off at any time.")
            .setPositiveButton("Agree and continue") { _, _ -> store.msgWatch = true; MessageHub.openAccessSettings(this) }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A puppy started before the microphone was allowed must restart to use it.
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && PetService.running) {
            stopService(Intent(this, PetService::class.java))
            root.postDelayed({ startForegroundService(Intent(this, PetService::class.java)) }, 500)
        }
        render()
    }
}
