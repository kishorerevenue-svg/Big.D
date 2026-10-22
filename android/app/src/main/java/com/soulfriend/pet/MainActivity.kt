package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
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

    // ---------- the screen ----------
    private fun render() {
        root.removeAllViews()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(PuppyView(this).apply { mood = if (PetService.running) PuppyView.Mood.EXCITED else PuppyView.Mood.HAPPY }, LinearLayout.LayoutParams(dp(120), dp(120)))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(text("Soul Friend", 26f, bold = true)); addView(text("ఆత్మ మిత్రుడు · your puppy friend", 15f, muted))
        })
        add(head, 0)

        // start / stop
        val ready = Settings.canDrawOverlays(this)
        add(button(if (PetService.running) "Hide the puppy" else "Show the puppy on my screen", primary = true) {
            if (PetService.running) stopService(Intent(this, PetService::class.java))
            else if (!ready) Toast.makeText(this, "First allow 'Show over other apps' below", Toast.LENGTH_LONG).show()
            else { startForegroundService(Intent(this, PetService::class.java)); moveTaskToBack(true) }
            root.postDelayed({ render() }, 400)
        }, 16)
        add(text("Tap the puppy to talk. Hold it for the menu. Drag it anywhere.\nపప్పీని నొక్కి మాట్లాడండి. మెనూ కోసం నొక్కి పట్టుకోండి.", 13f, muted), 8)

        // permissions
        val perms = card()
        perms.put(text("Setup · సెటప్", 18f, bold = true), 0)
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
        if (rems.isEmpty()) remCard.put(text("None. Say \"remind me at 5 pm to call the office\".", 13f, muted))
        rems.forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(r.text, 15f)); addView(text(fmt.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())), 12.5f, muted))
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

        add(text("Soul Friend is an AI and can make mistakes. Check important things before you rely on them.", 12.5f, muted), 18)
        add(button("Privacy policy") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store.server + "/privacy"))) } catch (e: Exception) { }
        }, 8)
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
