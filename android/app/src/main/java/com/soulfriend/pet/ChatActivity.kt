package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * One BigD chat (one-to-one, a group, or my own notes & reminders), like WhatsApp: messages with ✓✓ ticks, photos,
 * voice notes, and tasks pinned at the top until an admin closes them. Also opens BigD chat links (bigd://chat/<name>),
 * scans an invite QR code, and calls or invites a phone contact (action "scan", "contact").
 */
class ChatActivity : Activity() {
    companion object {
        private const val REQ_PHOTO = 7401
        private const val REQ_CAMERA = 7402
        private const val REQ_MIC = 7403
        private const val REQ_QR = 7404
        private const val REQ_QR_CAM = 7405
        private const val REQ_CONTACT = 7406
        private const val REQ_CALL = 7407
        val MINE = Color.parseColor("#DCF4EC")
        val BLUE = Color.parseColor("#2B8CE0")
        val NAMES = intArrayOf(Color.parseColor("#0E6B6B"), Color.parseColor("#B5532E"), Color.parseColor("#6A4FB3"), Color.parseColor("#1D8A5B"), Color.parseColor("#B23A6B"), Color.parseColor("#2B6CB0"))
        fun open(a: Activity, cid: String) = a.startActivity(Intent(a, ChatActivity::class.java).putExtra("cid", cid))
    }

    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var db: ChatDb
    private var cid = ""
    private var me = ""
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)

    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var avatar: TextView
    private lateinit var banner: LinearLayout
    private lateinit var pinned: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var input: EditText
    private lateinit var action: TextView
    private var pinnedOpen = false
    private var lastCount = -1
    private var readSent = 0L
    private val refresh: () -> Unit = { if (cid.isNotEmpty()) render() }

    // voice notes
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var player: MediaPlayer? = null
    private var playingId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this); db = ChatDb.get(this)
        window.statusBarColor = Ui.BG
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        me = ChatSync.myUid(this)
        cid = intent.getStringExtra("cid") ?: ""
        build()
        when {
            intent.getStringExtra("action") == "scan" -> scanQr()
            intent.getStringExtra("action") == "contact" -> pickContact()
            intent.data?.scheme == "bigd" -> openHandle(intent.data?.lastPathSegment ?: "")
            intent.getStringExtra("handle") != null -> openHandle(intent.getStringExtra("handle") ?: "")
            cid.isEmpty() || db.conv(cid) == null -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        ChatSync.listeners.add(refresh)
        ChatSync.hold(this, "chat")
        if (cid.isNotEmpty()) { ChatSync.openCid = cid; ChatSync.clearNotification(this, cid); render() }
    }
    override fun onPause() {
        super.onPause()
        ChatSync.listeners.remove(refresh)
        ChatSync.release("chat")
        if (ChatSync.openCid == cid) ChatSync.openCid = null
        stopPlaying()
        if (recorder != null) cancelRecording()
    }

    // =================== the screen ===================
    private fun build() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); fitsSystemWindows = true }
        // header: ←, picture, name and who's in it, ⋮
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)); setBackgroundColor(Color.WHITE) }
        head.addView(iconButton("←") { finish() })
        avatar = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER; background = ui.round(Ui.TEAL_SOFT, 22) }
        head.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(2); marginEnd = dp(10) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = ui.text("", 17f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        sub = ui.text("", 12.5f, Ui.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        names.addView(title); names.addView(sub)
        names.setOnClickListener { if (conv()?.isGroup == true) groupInfo() }
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconButton("⋮") { menu(it) })
        root.addView(head)
        root.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(-1, 1))
        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(banner)
        pinned = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pinned)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(12)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // the typing bar: 📎 photo, 📌 task, the text, then 🎤 (or ➤ when there is text)
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(6), dp(6), dp(6), dp(8)) }
        root.addView(bar)
        setContentView(root)
        buildBar()
    }

    private fun iconButton(label: String, onClick: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Ui.INK)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)); setOnClickListener { onClick(it) }
        background = ui.round(Color.TRANSPARENT, 22)
    }

    private fun buildBar() {
        bar.removeAllViews()
        if (recorder != null) {
            val secs = ((System.currentTimeMillis() - recStart) / 1000).toInt()
            bar.addView(iconButton("🗑️") { cancelRecording() })
            bar.addView(ui.text("🔴 " + t("Recording", "రికార్డ్ అవుతోంది") + "  %d:%02d".format(secs / 60, secs % 60), 15f, Ui.DANGER, bold = true).apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
            bar.addView(roundButton("➤") { stopRecording(true) })
            bar.postDelayed({ if (recorder != null) buildBar() }, 1000)
            return
        }
        bar.addView(iconButton("📎") { attach() })
        bar.addView(iconButton("📌") { newTask() })
        input = EditText(this).apply {
            hint = t("Message", "మెసేజ్"); textSize = 16f; maxLines = 5; minHeight = dp(44)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(8), dp(14), dp(8)); background = ui.round(Color.WHITE, 22, Ui.LINE); setTextColor(Ui.INK)
            setText(db.kv("draft:$cid"))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(6) })
        action = roundButton(if (input.text.isNullOrBlank()) "🎤" else "➤") { if (input.text.isNullOrBlank()) startRecording() else sendText() }
        bar.addView(action)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { action.text = if (s.isNullOrBlank()) "🎤" else "➤"; db.setKv("draft:$cid", s?.toString() ?: "") }
        })
    }
    private fun roundButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = ui.round(Ui.TEAL, 22)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)); setOnClickListener { onClick() }
    }

    private fun conv(): ChatConv? = if (cid.isEmpty()) null else db.conv(cid)

    /** Draws the whole chat again (the newest at the bottom). */
    private fun render() {
        val c = conv() ?: return
        avatar.text = c.emoji
        title.text = c.name
        sub.text = when {
            c.isSelf -> t("Only on this phone · notes and reminders", "ఈ ఫోన్‌లో మాత్రమే · నోట్స్, రిమైండర్లు")
            c.isGroup -> c.members().joinToString(", ") { if (it.optString("uid") == me) t("You", "మీరు") else it.optString("name") }
            else -> c.data.optString("handle").let { if (it.isNotEmpty()) "@$it · 🔒 " + t("locked end to end", "ఎండ్ టు ఎండ్ లాక్") else "🔒 " + t("locked end to end", "ఎండ్ టు ఎండ్ లాక్") }
        }
        renderBanner(c)
        renderPinned(c)
        val msgs = db.msgs(cid)
        val atBottom = !scroll.canScrollVertically(1) || lastCount < 0
        list.removeAllViews()
        if (msgs.isEmpty()) list.addView(ui.text(if (c.isSelf) t("Write notes to yourself here. Tap 📌 for a reminder: it stays pinned at the top until you tick it.",
            "ఇక్కడ మీ కోసం నోట్స్ రాసుకోండి. రిమైండర్ కోసం 📌 నొక్కండి: మీరు టిక్ చేసేవరకు అది పైన ఉంటుంది.")
            else t("🔒 Messages here are locked end to end: only the people in this chat can read them, not even BigD's server. Say hi! 👋",
            "🔒 ఇక్కడి మెసేజ్‌లు ఎండ్ టు ఎండ్ లాక్: ఈ చాట్‌లో ఉన్నవాళ్ళు మాత్రమే చదవగలరు, BigD సర్వర్ కూడా చదవలేదు. హాయ్ చెప్పండి! 👋"), 13.5f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(24)) })
        var day: LocalDate? = null
        for (m in msgs) {
            val d = Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).toLocalDate()
            if (d != day) { day = d; list.addView(dayLine(d)) }
            list.addView(bubble(c, m))
        }
        if (atBottom || msgs.size != lastCount) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        lastCount = msgs.size
        markRead(c, msgs)
        bar.visibility = if (!c.member || (!c.accepted && c.isGroup) || c.blocked) View.GONE else View.VISIBLE
    }

    private fun markRead(c: ChatConv, msgs: List<ChatMsg>) {
        db.readAll(cid)
        if (c.isSelf || !c.accepted) return
        val top = msgs.filter { it.sender != me && it.seq > 0 }.maxOfOrNull { it.seq } ?: 0L
        if (top > readSent) {
            readSent = top
            Thread { ChatSync.api(store, "POST", "/api/chat/read", JSONObject().put("cid", cid).put("seq", top).toString()) }.start()
        }
    }

    private fun dayLine(d: LocalDate): View {
        val today = LocalDate.now()
        val s = when (d) { today -> t("Today", "ఈరోజు"); today.minusDays(1) -> t("Yesterday", "నిన్న"); else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())) }
        return ui.text(s, 12f, Ui.MUTED, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4)); background = ui.round(Color.parseColor("#E2EAE8"), 10)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(4) }
        }
    }
    private fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    private fun nameColor(uid: String) = NAMES[Math.floorMod(uid.hashCode(), NAMES.size)]

    // ---------- one message ----------
    private fun bubble(c: ChatConv, m: ChatMsg): View {
        if (m.kind == "sys") return ui.text(ChatSync.sysText(this, cid, m.body), 12.5f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6)); background = ui.round(Color.parseColor("#FFF6E0"), 10)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6) }
        }
        val mine = m.sender == me || c.isSelf
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(7), dp(11), dp(6)); background = ui.round(if (mine) MINE else Color.WHITE, 14) }
        if (c.isGroup && !mine) box.addView(ui.text(c.nameOf(m.sender), 13f, nameColor(m.sender), bold = true))
        when (m.kind) {
            "text" -> box.addView(ui.text(m.body.optString("text"), 16f, Ui.INK).apply { maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt() - dp(22); Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS) })
            "photo" -> box.addView(photoView(m))
            "voice" -> box.addView(voiceView(m))
            "task" -> box.addView(taskInline(c, m))
            else -> box.addView(ui.text("🔒 " + t("This message couldn't be opened on this phone (it was locked for an older key).", "ఈ మెసేజ్ ఈ ఫోన్‌లో తెరవలేకపోయాం."), 14f, Ui.MUTED))
        }
        if (m.kind == "photo" && m.body.optString("text").isNotBlank()) box.addView(ui.text(m.body.optString("text"), 15.5f, Ui.INK).apply { setPadding(0, dp(4), 0, 0) })
        // the time and the ticks
        val tick = when {
            !mine || c.isSelf -> ""
            m.state == -1 -> " ⚠️"
            m.state == 0 || m.seq <= 0 -> " 🕓"
            else -> when (c.ticks(m.seq, me)) { 3 -> " ✓✓"; 2 -> " ✓✓"; else -> " ✓" }
        }
        val foot = ui.text(time(m.at) + tick, 11f, if (mine && c.ticks(m.seq, me) == 3 && m.state == 1) BLUE else Ui.MUTED).apply { gravity = Gravity.END }
        box.addView(foot, LinearLayout.LayoutParams(-1, -2))
        if (m.state == -1) box.addView(ui.text(t("Not sent. Tap to try again.", "పంపలేదు. మళ్ళీ ప్రయత్నించడానికి నొక్కండి."), 12f, Ui.DANGER))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (mine) Gravity.END else Gravity.START }
        val w = (resources.displayMetrics.widthPixels * 0.80).toInt()
        row.addView(box, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        box.post { if (box.width > w) box.layoutParams = (box.layoutParams as LinearLayout.LayoutParams).apply { width = w } }
        box.setOnClickListener { if (m.state == -1) retry(m) }
        box.setOnLongClickListener { msgMenu(c, m); true }
        return row
    }

    private fun msgMenu(c: ChatConv, m: ChatMsg) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (m.kind == "text" || m.body.optString("text").isNotBlank()) items.add(t("📋 Copy", "📋 కాపీ") to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", m.body.optString("text"))); toast(t("Copied", "కాపీ అయింది")) })
        items.add(t("🗑️ Delete on this phone", "🗑️ ఈ ఫోన్‌లో తొలగించు") to { db.deleteMsg(cid, m.id); render() })
        if (m.sender != me && !c.isSelf) items.add(t("⚠️ Report this message", "⚠️ ఈ మెసేజ్‌ని రిపోర్ట్ చేయండి") to { report(listOf(m), m.sender) })
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }
    private fun retry(m: ChatMsg) {
        db.setSent(cid, m.id, 0, 0, 0); render()
        Thread { ChatSync.flushPending(this, store) }.start()
    }

    // photos
    private fun photoView(m: ChatMsg): View {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; adjustViewBounds = true }
        val wPx = dp(240)
        val ratio = m.body.optInt("h", 3).toFloat() / maxOf(1, m.body.optInt("w", 4))
        iv.layoutParams = LinearLayout.LayoutParams(wPx, (wPx * ratio.coerceIn(0.4f, 1.6f)).toInt())
        val local = m.body.optString("local")
        val bmp = if (local.isNotEmpty() && File(local).exists()) decodeScaled(File(local), wPx) else null
        if (bmp != null) { iv.setImageBitmap(bmp); iv.setOnClickListener { showPhoto(File(local)) } }
        else {
            m.body.optString("thumb").takeIf { it.isNotEmpty() }?.let { try { val b = ChatCrypto.unb64(it); iv.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) } catch (e: Exception) { } }
            iv.setBackgroundColor(Ui.LINE)
            if (m.body.optString("file").isNotEmpty()) ChatSync.fetchFile(this, store, m)
        }
        iv.clipToOutline = true; iv.background = ui.round(Ui.LINE, 10)
        return iv
    }
    private fun decodeScaled(f: File, w: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1; while (o.outWidth / (s * 2) >= w) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }
    private fun showPhoto(f: File) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK); setImageBitmap(decodeScaled(f, resources.displayMetrics.widthPixels)); setOnClickListener { d.dismiss() } }
        d.setContentView(iv); d.show()
    }

    // voice notes
    private fun voiceView(m: ChatMsg): View {
        val local = m.body.optString("local")
        val ready = local.isNotEmpty() && File(local).exists()
        val secs = m.body.optInt("dur")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumWidth = dp(190) }
        val btn = TextView(this).apply { text = if (playingId == m.id) "⏸" else "▶"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = ui.round(Ui.TEAL, 20) }
        row.addView(btn, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(ui.text("🎤 " + (if (ready) "%d:%02d".format(secs / 60, secs % 60) else t("Loading…", "వస్తోంది…")), 15f, Ui.INK), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        btn.setOnClickListener { if (!ready) { ChatSync.fetchFile(this, store, m); return@setOnClickListener }; if (playingId == m.id) stopPlaying() else play(m.id, local) }
        if (!ready && m.body.optString("file").isNotEmpty()) ChatSync.fetchFile(this, store, m)
        return row
    }
    private fun play(id: String, path: String) {
        stopPlaying()
        try {
            player = MediaPlayer().apply { setDataSource(path); setOnCompletionListener { stopPlaying() }; prepare(); start() }
            playingId = id; render()
        } catch (e: Exception) { toast(t("Couldn't play it.", "ప్లే చేయలేకపోయాం.")) }
    }
    private fun stopPlaying() {
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        if (playingId.isNotEmpty()) { playingId = ""; if (cid.isNotEmpty()) render() }
    }

    // =================== tasks and reminders ===================
    private fun taskTitle(tid: String) = db.task(cid, tid)?.optString("title")?.ifBlank { null } ?: t("a task", "ఒక పని")
    /** A task message in the timeline: the card when it was given, a short line for remarks and changes. */
    private fun taskInline(c: ChatConv, m: ChatMsg): View {
        val b = m.body; val tid = b.optString("tid")
        val who = if (m.sender == me || c.isSelf) t("You", "మీరు") else c.nameOf(m.sender)
        return when (b.optString("action")) {
            "new" -> taskCard(c, db.task(cid, tid) ?: JSONObject().put("tid", tid).put("title", b.optString("title")).put("state", "open"), true)
            "remark" -> ui.text("💬 $who " + t("on", "") + " \"${taskTitle(tid)}\": ${b.optString("text")}", 15f, Ui.INK)
            "done" -> ui.text("✔ $who " + t("marked \"${taskTitle(tid)}\" done", "\"${taskTitle(tid)}\" పని పూర్తయిందని చెప్పారు") + b.optString("text").let { if (it.isBlank()) "" else ": $it" }, 15f, Ui.GOOD, bold = true)
            "close" -> ui.text("✅ $who " + t("closed \"${taskTitle(tid)}\"", "\"${taskTitle(tid)}\" మూసివేశారు"), 15f, Ui.GOOD, bold = true)
            else -> ui.text("↩ $who " + t("reopened \"${taskTitle(tid)}\"", "\"${taskTitle(tid)}\" మళ్ళీ తెరిచారు") + b.optString("text").let { if (it.isBlank()) "" else ": $it" }, 15f, Ui.SUN, bold = true)
        }
    }

    /** A task with its state and the buttons this person may use. */
    private fun taskCard(c: ChatConv, task: JSONObject, full: Boolean): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val state = task.optString("state", "open")
        val to = task.optString("to"); val by = task.optString("by")
        val reminder = c.isSelf || to.isEmpty()
        val head = (if (reminder) "⏰ " + t("Reminder", "రిమైండర్") else "📌 " + t("Task", "పని")) + "  ·  " + when (state) {
            "done" -> t("Done, waiting for the admin ✔", "పూర్తయింది, అడ్మిన్ కోసం ✔"); "closed" -> t("Closed ✅", "మూసివేశారు ✅"); else -> t("Open", "తెరిచి ఉంది") }
        box.addView(ui.text(head, 12.5f, when (state) { "closed" -> Ui.GOOD; "done" -> Ui.TEAL; else -> Ui.SUN }, bold = true))
        box.addView(ui.text(task.optString("title"), 16.5f, Ui.INK, bold = true).apply { if (state == "closed") paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
        if (task.optString("note").isNotBlank()) box.addView(ui.text(task.optString("note"), 14.5f, Ui.INK))
        val facts = ArrayList<String>()
        if (to.isNotEmpty()) facts.add("👤 " + (if (to == me) t("You", "మీరు") else c.nameOf(to)))
        if (by.isNotEmpty() && !c.isSelf) facts.add("✍️ " + (if (by == me) t("You", "మీరు") else c.nameOf(by)))
        task.optLong("due").takeIf { it > 0 }?.let { facts.add("📅 " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)) + if (it < System.currentTimeMillis() && state == "open") " ⚠️" else "") }
        if (facts.isNotEmpty()) box.addView(ui.text(facts.joinToString("   "), 13f, Ui.MUTED))
        if (full) task.optJSONArray("remarks")?.let { r -> for (i in maxOf(0, r.length() - 3) until r.length()) r.optJSONObject(i)?.let { x ->
            box.addView(ui.text("💬 " + (if (x.optString("by") == me) t("You", "మీరు") else c.nameOf(x.optString("by"))) + ": " + x.optString("text"), 13.5f, Ui.INK)) } }
        // what this person can do
        val admin = c.isSelf || c.isAdmin(me)
        val btns = ArrayList<View>()
        val tid = task.optString("tid")
        if (state != "closed" && c.member) {
            if (!c.isSelf) btns.add(chipButton("💬 " + t("Remark", "రిమార్క్")) { remark(tid) })
            if (state == "open" && !reminder && (to == me || by == me || admin)) btns.add(chipButton("✔ " + t("Done", "పూర్తి")) { markDone(tid) })
            if (admin) btns.add(chipButton("✅ " + t(if (reminder) "Tick" else "Close", if (reminder) "టిక్" else "మూసివేయి"), Ui.GOOD) { closeTask(tid) })
        } else if (state == "closed" && admin && c.member) btns.add(chipButton("↩ " + t("Reopen", "మళ్ళీ తెరువు")) { reopen(tid) })
        if (btns.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            btns.forEach { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
            box.addView(row)
        }
        return box
    }
    private fun chipButton(label: String, color: Int = Ui.TEAL, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13.5f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(7), dp(12), dp(7)); background = ui.round(Color.WHITE, 16, color); setOnClickListener { onClick() }
    }

    /** Open tasks stay pinned at the top until closed. */
    private fun renderPinned(c: ChatConv) {
        pinned.removeAllViews()
        val open = db.tasks(cid)
        if (open.isEmpty()) return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(9), dp(14), dp(9)); setBackgroundColor(Ui.SUN_SOFT) }
        val mineCount = open.count { it.optString("to") == me && it.optString("state") == "open" }
        bar.addView(ui.text("📌 " + t("${open.size} open", "${open.size} తెరిచి ఉన్నాయి") + (if (mineCount > 0) t(" · $mineCount for you", " · మీకు $mineCount") else "") + " · " +
            open.joinToString(" · ") { it.optString("title") }, 14f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.text(if (pinnedOpen) "▲" else "▼", 14f, Ui.MUTED))
        bar.setOnClickListener { pinnedOpen = !pinnedOpen; renderPinned(c) }
        pinned.addView(bar)
        if (pinnedOpen) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(8)); setBackgroundColor(Color.parseColor("#FFF9EC")) }
            for (task in open) box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9)); background = ui.round(Color.WHITE, 12)
                addView(taskCard(c, task, true))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val sv = ScrollView(this).apply { addView(box) }
            pinned.addView(sv, LinearLayout.LayoutParams(-1, minOf(dp(320), dp(150) * open.size)))
        }
        pinned.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(-1, 1))
    }

    private fun newTask() {
        val c = conv() ?: return
        if (!c.member) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(0)) }
        val titleIn = ui.input(t("What needs doing? e.g. Submit the survey report", "ఏం చేయాలి? ఉదా: సర్వే రిపోర్ట్ ఇవ్వాలి"))
        val noteIn = ui.input(t("Details (optional)", "వివరాలు (కావాలంటే)"), multi = true)
        box.addView(titleIn); box.addView(noteIn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        var to = ""
        if (!c.isSelf) {
            val people = c.members()
            val labels = listOf(t("Nobody (a reminder for everyone)", "ఎవరికీ కాదు (అందరికీ రిమైండర్)")) + people.map { if (it.optString("uid") == me) t("Me", "నేను") else it.optString("name") }
            val pick = ui.text("👤 " + labels[0], 15f, Ui.TEAL, bold = true).apply { setPadding(0, dp(14), 0, dp(4)) }
            pick.setOnClickListener { AlertDialog.Builder(this).setTitle(t("Who will do it?", "ఎవరు చేస్తారు?")).setItems(labels.toTypedArray()) { _, i -> to = if (i == 0) "" else people[i - 1].optString("uid"); pick.text = "👤 " + labels[i] }.show() }
            box.addView(pick)
        }
        var due = 0L
        val when_ = ui.text("📅 " + t("Add a date and time (optional)", "తేదీ, సమయం (కావాలంటే)"), 15f, Ui.TEAL, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
        when_.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, mo, d ->
                TimePickerDialog(this, { _, h, mi ->
                    cal.set(y, mo, d, h, mi, 0); due = cal.timeInMillis
                    when_.text = "📅 " + Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH))
                }, 17, 0, false).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        box.addView(when_)
        if (c.isGroup) box.addView(ui.text(t("It stays pinned at the top until a group admin closes it. The person can add remarks and mark it done.",
            "గ్రూప్ అడ్మిన్ మూసేవరకు ఇది పైన ఉంటుంది. ఆ వ్యక్తి రిమార్క్స్ రాసి, పూర్తయిందని చెప్పవచ్చు."), 12.5f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        AlertDialog.Builder(this).setTitle(if (c.isSelf) t("📌 New reminder", "📌 కొత్త రిమైండర్") else t("📌 New task", "📌 కొత్త పని")).setView(box)
            .setPositiveButton(t("Add", "చేర్చు")) { _, _ ->
                val title = titleIn.text.toString().trim()
                if (title.isEmpty()) { toast(t("Write what needs doing.", "ఏం చేయాలో రాయండి.")); return@setPositiveButton }
                val body = JSONObject().put("tid", "t" + System.currentTimeMillis().toString(36) + (100..999).random()).put("action", "new")
                    .put("title", title.take(120)).put("note", noteIn.text.toString().trim().take(600)).put("to", to).put("due", due)
                ChatSync.sendTask(this, store, cid, body) { e -> if (e != null) toast(e) else if (due > 0 && (to == me || c.isSelf)) toast(t("Reminder set on this phone too ⏰", "ఈ ఫోన్‌లో రిమైండర్ కూడా పెట్టాం ⏰")) }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun askText(title: String, hint: String, value: String = "", then: (String) -> Unit) {
        val inp = ui.input(hint, value, multi = true)
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(inp) }
        AlertDialog.Builder(this).setTitle(title).setView(wrap).setPositiveButton(t("Send", "పంపు")) { _, _ -> then(inp.text.toString().trim()) }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun taskAct(tid: String, action: String, text: String = "") =
        ChatSync.sendTask(this, store, cid, JSONObject().put("tid", tid).put("action", action).put("text", text.take(800))) { e -> if (e != null) toast(e) }
    private fun remark(tid: String) = askText(t("💬 Remark", "💬 రిమార్క్"), t("e.g. Visited the site, report ready by evening", "ఉదా: సైట్ చూశాను, సాయంత్రానికి రిపోర్ట్")) { if (it.isNotEmpty()) taskAct(tid, "remark", it) }
    private fun markDone(tid: String) = askText(t("✔ Mark done", "✔ పూర్తయింది"), t("What was done? (optional)", "ఏం చేశారు? (కావాలంటే)")) { taskAct(tid, "done", it) }
    private fun closeTask(tid: String) = AlertDialog.Builder(this).setMessage(t("Close \"${taskTitle(tid)}\"? It comes off the pinned list.", "\"${taskTitle(tid)}\" మూసివేయాలా? పైన ఉన్న జాబితా నుంచి తీసేస్తాం."))
        .setPositiveButton(t("Close ✅", "మూసివేయి ✅")) { _, _ -> taskAct(tid, "close") }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    private fun reopen(tid: String) = askText(t("↩ Reopen", "↩ మళ్ళీ తెరువు"), t("Why? (optional)", "ఎందుకు? (కావాలంటే)")) { taskAct(tid, "reopen", it) }

    // =================== sending ===================
    private fun sendText() {
        val s = input.text.toString().trim()
        if (s.isEmpty()) return
        input.setText(""); db.setKv("draft:$cid", "")
        ChatSync.send(this, store, cid, JSONObject().put("t", "text").put("text", s.take(4000))) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
    }

    private fun attach() {
        AlertDialog.Builder(this).setItems(arrayOf(t("🖼️ Photo from gallery", "🖼️ గ్యాలరీ ఫోటో"), t("📷 Take a photo", "📷 ఫోటో తీయండి"))) { _, i -> if (i == 0) pickPhoto() else camera() }.show()
    }
    private fun pickPhoto(req: Int = REQ_PHOTO) {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, req) } catch (e: Exception) { try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), req) } catch (e2: Exception) { toast("Couldn't open your photos.") } }
    }
    private fun camera(req: Int = REQ_CAMERA) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), req); return }
        val f = CameraFiles.file(this); f.parentFile?.mkdirs(); f.delete()
        val uri = CameraFiles.uri(this)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        try { startActivityForResult(i, req) } catch (e: Exception) { toast(t("No camera app found.", "కెమెరా యాప్ లేదు.")) }
    }
    /** Makes a photo small enough to send quickly (1600 px, JPEG), with a tiny preview, then sends it. */
    private fun sendPhoto(read: () -> ByteArray?) {
        val caption = input.text.toString().trim()
        Thread {
            val raw = try { read() } catch (e: Exception) { null }
            val bmp0 = raw?.let { scaled(it, 1600) }
            if (bmp0 == null) { runOnUiThread { toast(t("Couldn't read that photo.", "ఆ ఫోటో చదవలేకపోయాం.")) }; return@Thread }
            val bmp = rotate(raw, bmp0)
            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val th = ByteArrayOutputStream(); Bitmap.createScaledBitmap(bmp, 24, maxOf(1, 24 * bmp.height / maxOf(1, bmp.width)), true).compress(Bitmap.CompressFormat.JPEG, 50, th)
            val body = JSONObject().put("w", bmp.width).put("h", bmp.height).put("thumb", ChatCrypto.b64(th.toByteArray())).put("text", caption.take(1000))
            runOnUiThread {
                if (caption.isNotEmpty()) { input.setText(""); db.setKv("draft:$cid", "") }
                ChatSync.sendFile(this, store, cid, "photo", out.toByteArray(), body) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
            }
        }.start()
    }
    private fun scaled(bytes: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val big = maxOf(b.width, b.height)
        return if (big <= max) b else Bitmap.createScaledBitmap(b, b.width * max / big, b.height * max / big, true)
    }
    private fun rotate(bytes: ByteArray, b: Bitmap): Bitmap = try {
        val deg = when (android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
    } catch (e: Exception) { b }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        val f = File(cacheDir, "voice-note.m4a"); f.delete()
        try {
            @Suppress("DEPRECATION") val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(32000); r.setAudioSamplingRate(22050); r.setOutputFile(f.absolutePath); r.prepare(); r.start()
            recorder = r; recFile = f; recStart = System.currentTimeMillis(); buildBar()
        } catch (e: Exception) { recorder = null; toast(t("The microphone is busy (the pet may be listening). Try again.", "మైక్ బిజీగా ఉంది. మళ్ళీ ప్రయత్నించండి.")) }
    }
    private fun stopRecording(send: Boolean) {
        val r = recorder ?: return
        val secs = ((System.currentTimeMillis() - recStart) / 1000).toInt()
        try { r.stop() } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
        recorder = null
        val f = recFile
        buildBar()
        if (!send || f == null || !f.exists() || secs < 1) { if (send) toast(t("Hold on a bit longer to record.", "కొంచెం సేపు మాట్లాడండి.")); return }
        ChatSync.sendFile(this, store, cid, "voice", f.readBytes(), JSONObject().put("dur", secs)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
    }
    private fun cancelRecording() { stopRecording(false) }

    // =================== banners, menu, group info ===================
    private fun renderBanner(c: ChatConv) {
        banner.removeAllViews()
        fun box(text: String, vararg buttons: View) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(Color.parseColor("#FFF6E0")) }
            b.addView(ui.text(text, 14.5f, Ui.INK))
            if (buttons.isNotEmpty()) { val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }; buttons.forEach { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }; b.addView(row) }
            banner.addView(b)
        }
        when {
            !c.member -> box(t("You're no longer in this group.", "మీరు ఇప్పుడు ఈ గ్రూప్‌లో లేరు."), chipButton(t("🧹 Delete chat", "🧹 చాట్ తొలగించు"), Ui.DANGER) { db.clear(cid, true); finish() })
            c.blocked -> box(t("You blocked this person. They can't message you.", "మీరు ఈ వ్యక్తిని బ్లాక్ చేశారు."), chipButton(t("Unblock", "అన్‌బ్లాక్")) { block(false) })
            !c.accepted && c.isGroup -> box(t("${c.nameOf(c.data.optString("by"))} added you to this group. Join it?", "${c.nameOf(c.data.optString("by"))} మిమ్మల్ని ఈ గ్రూప్‌లో చేర్చారు. చేరతారా?"),
                chipButton(t("✅ Join", "✅ చేరండి"), Ui.GOOD) { accept() }, chipButton(t("🚪 Leave", "🚪 వద్దు")) { leave() }, chipButton(t("⚠️ Report", "⚠️ రిపోర్ట్"), Ui.DANGER) { report(db.msgs(cid).filter { it.sender != me }, c.data.optString("by")) })
            !c.accepted -> box(t("${c.name} wants to chat with you. Reply or accept to let them write more.", "${c.name} మీతో చాట్ చేయాలనుకుంటున్నారు. జవాబిస్తే లేదా ఒప్పుకుంటే వాళ్ళు ఇంకా రాయగలరు."),
                chipButton(t("✅ Accept", "✅ ఒప్పుకోండి"), Ui.GOOD) { accept() }, chipButton(t("🚫 Block", "🚫 బ్లాక్"), Ui.DANGER) { block(true) }, chipButton(t("⚠️ Report", "⚠️ రిపోర్ట్"), Ui.DANGER) { report(db.msgs(cid).filter { it.sender != me }, otherUid(c)) })
        }
    }
    private fun otherUid(c: ChatConv) = c.members().map { it.optString("uid") }.firstOrNull { it != me } ?: ""

    private fun call(path: String, body: JSONObject, ok: (JSONObject) -> Unit) {
        Thread {
            val r = ChatSync.api(store, "POST", path, body.put("cid", cid).toString())
            runOnUiThread { if (r.ok) { r.json.optJSONObject("conv")?.let { db.putConv(it) }; ok(r.json); render() } else toast(r.error("Couldn't do it.")) }
        }.start()
    }
    private fun accept() = call("/api/chat/accept", JSONObject()) { }
    private fun block(on: Boolean) { val c = conv() ?: return; call("/api/chat/block", JSONObject().put("uid", otherUid(c)).put("on", on)) { toast(if (on) t("Blocked", "బ్లాక్ చేశాం") else t("Unblocked", "అన్‌బ్లాక్ చేశాం")) } }
    private fun leave() {
        AlertDialog.Builder(this).setMessage(t("Leave this group?", "ఈ గ్రూప్ నుంచి వెళ్ళిపోవాలా?")).setPositiveButton(t("Leave", "వెళ్ళిపోండి")) { _, _ ->
            call("/api/chat/leave", JSONObject()) { db.patchConv(cid) { it.put("member", false) }; db.clear(cid, true); finish() }
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** Report: the messages (opened on this phone) go to the BigD team, so they can see what happened. */
    private fun report(msgs: List<ChatMsg>, who: String) {
        val reasons = arrayOf(t("Spam or fraud", "స్పామ్ / మోసం"), t("Bad or abusive words", "చెడ్డ మాటలు"), t("Troubling a child", "పిల్లలను ఇబ్బంది పెట్టడం"), t("Something else", "ఇంకేదైనా"))
        AlertDialog.Builder(this).setTitle(t("⚠️ Report: why?", "⚠️ రిపోర్ట్: ఎందుకు?")).setItems(reasons) { _, i ->
            val arr = JSONArray()
            for (m in msgs.takeLast(10)) arr.put(JSONObject().put("from", m.sender).put("text", ChatSync.preview(this, m)).put("at", m.at))
            call("/api/chat/report", JSONObject().put("uid", who).put("reason", reasons[i]).put("msgs", arr)) {
                toast(t("Reported. Thank you: the BigD team will look at it.", "రిపోర్ట్ చేశాం. ధన్యవాదాలు."))
                val c = conv()
                if (c != null && !c.isGroup && !c.blocked) AlertDialog.Builder(this).setMessage(t("Block them too?", "వాళ్ళని బ్లాక్ కూడా చేయాలా?"))
                    .setPositiveButton(t("Block", "బ్లాక్")) { _, _ -> block(true) }.setNegativeButton(t("No", "వద్దు"), null).show()
            }
        }.show()
    }

    private fun menu(anchor: View) {
        val c = conv() ?: return
        val pm = PopupMenu(this, anchor)
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, f: () -> Unit) { pm.menu.add(0, acts.size, acts.size, label); acts.add(f) }
        if (c.isGroup && c.member) add(t("👥 Group info", "👥 గ్రూప్ వివరాలు")) { groupInfo() }
        if (!c.isSelf) add(if (c.muted) t("🔔 Unmute", "🔔 శబ్దం ఆన్") else t("🔕 Mute", "🔕 శబ్దం ఆఫ్")) { db.setMuted(cid, !c.muted); toast(if (c.muted) t("Unmuted", "ఆన్ చేశాం") else t("Muted", "ఆఫ్ చేశాం")) }
        if (!c.isGroup && !c.isSelf) add(if (c.blocked) t("✅ Unblock", "✅ అన్‌బ్లాక్") else t("🚫 Block", "🚫 బ్లాక్")) { block(!c.blocked) }
        if (!c.isSelf) add(t("⚠️ Report", "⚠️ రిపోర్ట్")) { report(db.msgs(cid).filter { it.sender != me }, if (c.isGroup) "" else otherUid(c)) }
        add(t("🧹 Clear chat on this phone", "🧹 ఈ ఫోన్‌లో చాట్ క్లియర్")) { AlertDialog.Builder(this).setMessage(t("Clear all messages here? This can't be undone.", "అన్ని మెసేజ్‌లు తొలగించాలా?"))
            .setPositiveButton(t("Clear", "తొలగించు")) { _, _ -> db.clear(cid, false); render() }.setNegativeButton(t("Cancel", "వద్దు"), null).show() }
        if (c.isGroup && c.member) add(t("🚪 Leave group", "🚪 గ్రూప్ వదిలేయండి")) { leave() }
        pm.setOnMenuItemClickListener { acts[it.itemId](); true }
        pm.show()
    }

    private fun groupInfo() {
        val c = conv() ?: return
        val admin = c.isAdmin(me)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(ui.text(t("${c.members().size} people", "${c.members().size} మంది") + if (admin) t(" · you're an admin", " · మీరు అడ్మిన్") else "", 13.5f, Ui.MUTED))
        val dlg = AlertDialog.Builder(this).setTitle("👥 " + c.name).setView(ScrollView(this).apply { addView(box) }).setNegativeButton(t("Close", "మూసివేయి"), null).create()
        for (p in c.members()) {
            val uid = p.optString("uid"); val isA = p.optBoolean("admin")
            val row = ui.text("${p.optString("emoji", "🐾")}  ${if (uid == me) t("You", "మీరు") else p.optString("name")}" + (if (p.optString("handle").isNotEmpty()) "  @" + p.optString("handle") else "") + (if (isA) "  ⭐ " + t("admin", "అడ్మిన్") else ""), 15.5f, Ui.INK).apply { setPadding(0, dp(10), 0, dp(10)) }
            if (admin && uid != me) row.setOnClickListener {
                val opts = arrayOf(if (isA) t("Remove as admin", "అడ్మిన్ నుంచి తీసేయి") else t("⭐ Make admin", "⭐ అడ్మిన్ చేయి"), t("🚫 Remove from group", "🚫 గ్రూప్ నుంచి తీసేయి"))
                AlertDialog.Builder(this).setTitle(p.optString("name")).setItems(opts) { _, i ->
                    dlg.dismiss()
                    val body = JSONObject().put(if (i == 0) (if (isA) "unadmin" else "admin") else "remove", JSONArray().put(uid))
                    call("/api/chat/group/edit", body) { }
                }.show()
            }
            box.addView(row)
        }
        if (admin) {
            box.addView(chipButton(t("➕ Add people", "➕ వ్యక్తులను చేర్చు")) { dlg.dismiss(); ChatPeople.pick(this, store, t("Add to the group", "గ్రూప్‌లో చేర్చండి"), c.members().map { it.optString("uid") }.toSet()) { picked ->
                call("/api/chat/group/edit", JSONObject().put("add", JSONArray(picked.map { it.optString("uid") }))) { } } }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            box.addView(chipButton(t("✏️ Rename", "✏️ పేరు మార్చు")) { dlg.dismiss(); askText(t("Group name", "గ్రూప్ పేరు"), t("Group name", "గ్రూప్ పేరు"), c.name) { if (it.length >= 2) call("/api/chat/group/edit", JSONObject().put("name", it)) { } } },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        } else box.addView(ui.text(t("Only admins can add or remove people.", "అడ్మిన్లు మాత్రమే వ్యక్తులను చేర్చగలరు, తీసేయగలరు."), 12.5f, Ui.MUTED).apply { setPadding(0, dp(10), 0, 0) })
        dlg.show()
    }

    // =================== links, QR codes and phone contacts ===================
    /** Opens (or starts) a chat with the person who has this @name. */
    private fun openHandle(handle: String) {
        if (!ChatSync.isSetUp(this)) { toast(t("Open Messages › 💬 BigD Chat first.", "ముందు మెసేజ్‌లు › 💬 BigD చాట్ తెరవండి.")); startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        val h = handle.trim().removePrefix("@")
        if (h.isEmpty()) { finish(); return }
        title.text = "@$h"; sub.text = t("Opening…", "తెరుస్తున్నాం…")
        Thread {
            val r = ChatSync.api(store, "POST", "/api/chat/open", JSONObject().put("handle", h).toString())
            runOnUiThread {
                val conv = r.json.optJSONObject("conv")
                if (!r.ok || conv == null) { toast(r.error("Couldn't open the chat.")); finish(); return@runOnUiThread }
                db.putConv(conv); cid = conv.optString("cid"); ChatSync.openCid = cid; buildBar(); render()
            }
        }.start()
    }
    private fun scanQr() {
        title.text = t("Scan a BigD QR code", "BigD QR కోడ్ స్కాన్"); sub.text = ""
        AlertDialog.Builder(this).setItems(arrayOf(t("📷 Take a photo of the QR code", "📷 QR కోడ్ ఫోటో తీయండి"), t("🖼️ From a photo or screenshot", "🖼️ ఫోటో లేదా స్క్రీన్‌షాట్ నుంచి"))) { _, i -> if (i == 0) camera(REQ_QR_CAM) else pickPhoto(REQ_QR) }
            .setOnCancelListener { finish() }.show()
    }
    private fun onQr(read: () -> Uri?) {
        val text = try { read()?.let { QrRead.decode(this, it) } ?: "" } catch (e: Exception) { "" }
        val h = Regex("/c/([A-Za-z0-9_]{3,20})").find(text)?.groupValues?.get(1) ?: Regex("bigd://chat/([A-Za-z0-9_]{3,20})").find(text)?.groupValues?.get(1)
        if (h == null) { toast(if (text.isEmpty()) t("No QR code found in that picture.", "ఆ ఫోటోలో QR కోడ్ కనిపించలేదు.") else t("That isn't a BigD chat QR code.", "అది BigD చాట్ QR కోడ్ కాదు.")); finish(); return }
        openHandle(h)
    }
    private fun pickContact() {
        title.text = t("Call or invite", "కాల్ లేదా ఆహ్వానం"); sub.text = ""
        try { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT) } catch (e: Exception) { toast("Couldn't open your contacts."); finish() }
    }
    private var pickedNumber = ""
    private fun onContact(uri: Uri) {
        var name = ""; var number = ""
        try { contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: ""; number = c.getString(1) ?: "" } } } catch (e: Exception) { }
        if (number.isBlank()) { toast(t("That contact has no phone number.", "ఆ కాంటాక్ట్‌కి నంబర్ లేదు.")); finish(); return }
        pickedNumber = number
        val handle = ChatSync.me(this).optString("handle")
        val link = store.server.trimEnd('/') + "/c/" + handle
        val msg = t("Hi! Let's chat on BigD AI (free, locked end to end, Telugu and English): $link", "హాయ్! BigD AI లో చాట్ చేద్దాం (ఉచితం, సురక్షితం): $link")
        val digits = number.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
        AlertDialog.Builder(this).setTitle(name.ifBlank { number }).setItems(arrayOf(t("📞 Phone call", "📞 ఫోన్ కాల్"), t("💬 Invite on WhatsApp", "💬 వాట్సాప్‌లో ఆహ్వానించండి"), t("✉️ Invite by SMS", "✉️ SMS తో ఆహ్వానించండి"))) { _, i ->
            when (i) {
                0 -> phoneCall(number)
                1 -> { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(msg)))) } catch (e: Exception) { toast("WhatsApp isn't installed.") }; finish() }
                else -> { try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", msg)) } catch (e: Exception) { toast("No SMS app.") }; finish() }
            }
        }.setOnCancelListener { finish() }.show()
    }
    /** A normal phone call (to anyone, with BigD or not). */
    private fun phoneCall(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL); return }
        try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))) } catch (e: Exception) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val ok = resultCode == RESULT_OK
        when (requestCode) {
            REQ_PHOTO -> if (ok) data?.data?.let { u -> sendPhoto { contentResolver.openInputStream(u)?.use { it.readBytes() } } }
            REQ_CAMERA -> if (ok) sendPhoto { CameraFiles.file(this).takeIf { it.exists() }?.readBytes() }
            REQ_QR -> if (ok) onQr { data?.data } else finish()
            REQ_QR_CAM -> if (ok) onQr { CameraFiles.uri(this) } else finish()
            REQ_CONTACT -> if (ok) data?.data?.let { onContact(it) } ?: finish() else finish()
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (granted) startRecording()
            REQ_CAMERA, REQ_QR_CAM -> if (granted) camera(requestCode) else if (requestCode == REQ_QR_CAM) finish()
            REQ_CALL -> if (granted) phoneCall(pickedNumber) else { try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(pickedNumber)))) } catch (e: Exception) { }; finish() }
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Finding BigD people by name or @name (for a new chat, a new group, or adding to a group). */
object ChatPeople {
    /** Shows a search box; [done] gets the people picked. [multi]: tick several (groups); otherwise one tap picks. */
    fun pick(a: Activity, store: Store, title: String, skip: Set<String> = emptySet(), multi: Boolean = true, done: (List<JSONObject>) -> Unit) {
        val ui = Ui(a)
        val te = store.lang == "te"
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) }
        val q = ui.input(if (te) "పేరు లేదా @పేరు టైప్ చేయండి" else "Type a name or @name")
        val chosen = LinkedHashMap<String, JSONObject>()
        val chosenView = ui.text("", 13.5f, Ui.TEAL, bold = true)
        val results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(q); if (multi) box.addView(chosenView); box.addView(results)
        var dlg: AlertDialog? = null
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var seq = 0
        fun search(s: String) {
            val my = ++seq
            if (s.trim().length < 2) { results.removeAllViews(); return }
            Thread {
                val r = ChatSync.api(store, "GET", "/api/chat/find?q=" + Uri.encode(s.trim()))
                main.post {
                    if (my != seq) return@post
                    results.removeAllViews()
                    val ppl = r.json.optJSONArray("people") ?: JSONArray()
                    if (!r.ok || ppl.length() == 0) results.addView(ui.text(if (!r.ok) r.error("Couldn't search.") else if (te) "ఎవరూ దొరకలేదు. వాళ్ళు BigD చాట్ తెరిచారా? మీ ఆహ్వాన లింక్ పంపండి." else "No one found. Have they opened BigD Chat? Send them your invite link.", 13.5f, Ui.MUTED).apply { setPadding(0, ui.dp(10), 0, 0) })
                    for (i in 0 until ppl.length()) {
                        val p = ppl.getJSONObject(i); val uid = p.optString("uid")
                        if (uid in skip) continue
                        val row = ui.text("${p.optString("emoji", "🐾")}  ${p.optString("name")}  @${p.optString("handle")}" + if (chosen.containsKey(uid)) "  ✓" else "", 15.5f, Ui.INK).apply { setPadding(0, ui.dp(11), 0, ui.dp(11)) }
                        row.setOnClickListener {
                            if (!multi) { dlg?.dismiss(); done(listOf(p)); return@setOnClickListener }
                            if (chosen.containsKey(uid)) chosen.remove(uid) else chosen[uid] = p
                            chosenView.text = if (chosen.isEmpty()) "" else "✓ " + chosen.values.joinToString(", ") { it.optString("name") }
                            row.text = "${p.optString("emoji", "🐾")}  ${p.optString("name")}  @${p.optString("handle")}" + if (chosen.containsKey(uid)) "  ✓" else ""
                        }
                        results.addView(row)
                    }
                }
            }.start()
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: Editable?) { val v = s?.toString() ?: ""; main.removeCallbacksAndMessages(null); main.postDelayed({ search(v) }, 350) }
        })
        val b = AlertDialog.Builder(a).setTitle(title).setView(ScrollView(a).apply { addView(box) }).setNegativeButton(if (te) "వద్దు" else "Cancel", null)
        if (multi) b.setPositiveButton(if (te) "సరే" else "Done") { _, _ -> if (chosen.isNotEmpty()) done(chosen.values.toList()) }
        dlg = b.create(); dlg.show()
    }
}
