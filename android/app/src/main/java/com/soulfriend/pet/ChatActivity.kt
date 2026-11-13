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
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
 * One Habit chat (one-to-one, a group, my notes, 🐾 Habit AI or a 📢 broadcast list), like WhatsApp:
 *  - messages with ✓✓ ticks, photos, videos, documents, voice notes, stickers, GIFs, polls, events, locations,
 *    contact cards, view-once photos and link previews; replies, forwarding (also the quick ↪ next to photos and links),
 *    reactions, ⭐ stars, 📌 pinned messages, edits (15 minutes), message info, delete for everyone;
 *  - the typing bar: 😊 emojis · GIFs · stickers panel, 📎 attach (document, camera, gallery, video, audio, location,
 *    contact, poll, event, view once, sticker, task), 📋 tasks, 📷 camera, 🎤 voice notes; @mentions in groups;
 *  - 🔍 search in the chat, "typing…", online / last seen, disappearing messages, "only admins send" groups;
 *  - voice and video calls (📞 🎥 at the top), and tasks pinned at the top until an admin closes them.
 * Also opens Habit chat links (bigd://chat/<name>) and group invite links (bigd://group/<code>), scans an invite QR code,
 * and calls or invites a phone contact (action "scan", "contact").
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
        private const val REQ_VIDEO = 7408
        private const val REQ_DOC = 7409
        private const val REQ_CALL_PERMS = 7410
        private const val REQ_LOC = 7411
        private const val REQ_SHARE_CONTACT = 7412
        private const val REQ_ONCE = 7413
        private const val REQ_STICKER = 7414
        private const val REQ_AUDIO = 7415
        private const val REQ_DOODLE = 7416
        /** Files bigger than this don't go (the server keeps chat files up to 32 MB). */
        const val MAX_FILE = 32L * 1024 * 1024
        val MINE = ChatTheme.MINE
        val NAMES = ChatTheme.NAMES
        /** Kinds that are real messages (they can be replied to, forwarded, starred…). */
        val CONTENT = setOf("text", "photo", "voice", "video", "doc", "sticker", "gif", "poll", "event", "loc", "contact")
        fun open(a: Activity, cid: String) = a.startActivity(Intent(a, ChatActivity::class.java).putExtra("cid", cid))
        /** Opens a chat at one message (from ⭐ starred messages, media, search). */
        fun openAt(a: Activity, cid: String, msgId: String) = a.startActivity(Intent(a, ChatActivity::class.java).putExtra("cid", cid).putExtra("goto", msgId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var db: ChatDb
    private var cid = ""
    private var me = ""
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var head: LinearLayout
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var avatarBox: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var pinned: LinearLayout
    private lateinit var pinMsgBar: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var inputRow: LinearLayout
    private lateinit var notice: TextView
    private lateinit var input: EditText
    private lateinit var action: ImageView
    private lateinit var emojiBtn: ImageView
    private lateinit var tasksBtn: ImageView
    private lateinit var camBtn: ImageView
    private lateinit var onlineDot: View
    /** The look this screen was drawn in (it is drawn again if the phone's dark mode or the setting changes). */
    private var builtDark = true
    /** The colours of the bubble being drawn (mine or theirs); the page's colours otherwise. */
    private var ink = ChatTheme.ink(false)
    private lateinit var callBtns: LinearLayout
    private lateinit var tasksChip: TextView
    private lateinit var lpBox: LinearLayout
    private lateinit var panelBox: FrameLayout
    private var panel: ChatPanel? = null
    private var panelOpen = false
    private var pinnedOpen = false
    private var shownAvatar = ""
    // replying to a message (shown above the typing bar)
    private var replyTo: ChatMsg? = null
    private lateinit var replyBar: LinearLayout
    private var lastCount = -1
    private var readSent = 0L
    private var showLimit = 400
    private var pendingGoto = ""
    private var lastExpire = 0L
    // search in the chat
    private var searchQ = ""
    private var hits: List<String> = emptyList()
    private var hitIdx = -1
    private lateinit var hitLabel: TextView
    // the link preview while typing
    private var lpUrl = ""
    private var lpData: JSONObject? = null
    private var lpOff = ""
    // @mentions typed in a group: name -> uid
    private val mentions = LinkedHashMap<String, String>()
    private var presence = ""
    private val refresh: () -> Unit = { if (cid.isNotEmpty()) render() }
    private val typingRefresh: () -> Unit = { conv()?.let { renderHeader(it) } }

    // voice notes
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var player: MediaPlayer? = null
    private var playingId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ChatTheme.apply(this); builtDark = ChatTheme.dark
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this); db = ChatDb.get(this)
        ink = ChatTheme.ink(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        me = ChatSync.myUid(this)
        cid = intent.getStringExtra("cid") ?: ""
        pendingGoto = intent.getStringExtra("goto") ?: ""
        build()
        ChatTheme.bars(this, ChatTheme.BAR, ChatTheme.CHAT_BG)
        when {
            intent.getStringExtra("action") == "scan" -> scanQr()
            intent.getStringExtra("action") == "contact" -> pickContact()
            intent.data?.scheme == "bigd" && intent.data?.host == "group" -> joinGroup(intent.data?.lastPathSegment ?: "")
            intent.data?.scheme == "bigd" -> openHandle(intent.data?.lastPathSegment ?: "")
            intent.getStringExtra("handle") != null -> openHandle(intent.getStringExtra("handle") ?: "")
            cid.isEmpty() || db.conv(cid) == null -> finish()
            intent.getStringExtra("call") != null -> startCall(intent.getStringExtra("call") == "video")
            intent.getBooleanExtra("search", false) -> handler.post { openSearch() }
        }
    }

    override fun onResume() {
        super.onResume()
        ChatSync.listeners.add(refresh)
        ChatSync.typingListeners.add(typingRefresh)
        ChatSync.hold(this, "chat")
        if (cid.isNotEmpty()) { ChatSync.openCid = cid; ChatSync.clearNotification(this, cid); render(); setWall() }
        handler.post(presenceTick)
        ChatTheme.refresh(this); if (ChatTheme.dark != builtDark) recreate()
    }
    override fun onPause() {
        super.onPause()
        ChatSync.listeners.remove(refresh)
        ChatSync.typingListeners.remove(typingRefresh)
        ChatSync.release("chat")
        if (ChatSync.openCid == cid) ChatSync.openCid = null
        handler.removeCallbacks(presenceTick)
        stopPlaying()
        if (recorder != null) cancelRecording()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (innerBack()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }
    /** Back closes what's open on this screen first (picked messages, the 😊 panel, search); false when nothing is open. */
    private fun innerBack(): Boolean {
        when {
            picked.isNotEmpty() -> clearPick()
            panelOpen -> closePanel()
            searchBar.visibility == View.VISIBLE -> closeSearch()
            else -> return false
        }
        syncBack(); return true
    }
    // Android 16 doesn't call onBackPressed for apps like this one: Back reaches [innerBack] through this callback instead,
    // registered only while something is open (so Back still leaves the chat at once otherwise).
    private var backCb: Any? = null
    private fun syncBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = picked.isNotEmpty() || panelOpen || searchBar.visibility == View.VISIBLE
        if (want && backCb == null) {
            val cb = android.window.OnBackInvokedCallback { innerBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        } else if (!want && backCb != null) {
            (backCb as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCb = null
        }
    }

    /** Online / last seen of the other person (every 30 seconds while the chat is open). */
    private val presenceTick: Runnable = object : Runnable {
        override fun run() {
            val c = conv()
            if (c != null && c.isDm && c.accepted && !c.blocked) {
                val other = c.otherUid(me)
                Thread {
                    val j = ChatSync.presence(store, other)
                    runOnUiThread {
                        presence = when {
                            j == null || j.optBoolean("hidden") -> ""
                            j.optBoolean("on") -> t("online", "ఆన్‌లైన్‌లో ఉన్నారు")
                            j.optLong("at") > 0 -> lastSeen(j.optLong("at"))
                            else -> ""
                        }
                        conv()?.let { renderHeader(it) }
                    }
                }.start()
            }
            handler.postDelayed(this, 30_000)
        }
    }
    private fun lastSeen(at: Long): String {
        val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val tm = z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        return when (z.toLocalDate()) {
            LocalDate.now() -> t("last seen today at $tm", "ఈరోజు $tm కి చూశారు")
            LocalDate.now().minusDays(1) -> t("last seen yesterday at $tm", "నిన్న $tm కి చూశారు")
            else -> t("last seen ", "చివరిగా ") + z.format(DateTimeFormatter.ofPattern("d/M/yy", Locale.ENGLISH))
        }
    }

    // =================== the screen ===================
    private fun build() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.background = ChatTheme.Backdrop(root, ChatTheme.BAR, ChatTheme.CHAT_BG)
        // header: back, picture (with the green "online" dot), name and who's in it (tap: contact / group info), video, call, more
        head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(2), dp(4)); minimumHeight = dp(62); setBackgroundColor(ChatTheme.BAR) }
        head.addView(iconBtn(R.drawable.ic_back, t("Back", "వెనక్కి")) { finish() })
        val pic = FrameLayout(this).apply { setOnClickListener { openInfo() } }
        avatarBox = FrameLayout(this)
        pic.addView(avatarBox, FrameLayout.LayoutParams(dp(42), dp(42)))
        onlineDot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(ChatTheme.ONLINE); setStroke(dp(2), ChatTheme.BAR) }
            visibility = View.GONE
        }
        pic.addView(onlineDot, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END))
        head.addView(pic, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(2); marginEnd = dp(10) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = ui.text("", 16.5f, ChatTheme.TXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1f) }
        sub = ui.text("", 12.5f, ChatTheme.SUB).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1f) }
        tasksChip = tasksPill().apply {
            visibility = View.GONE
            setOnClickListener { pinnedOpen = !pinnedOpen; conv()?.let { renderPinned(it) } }
        }
        names.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
            addView(tasksChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })
        names.addView(sub)
        names.setOnClickListener { openInfo() }
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        callBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        callBtns.addView(iconBtn(R.drawable.ic_video, t("Video call", "వీడియో కాల్")) { startCall(true) })
        callBtns.addView(iconBtn(R.drawable.ic_phone, t("Voice call", "వాయిస్ కాల్"), size = 21) { startCall(false) })
        head.addView(callBtns)
        head.addView(iconBtn(R.drawable.ic_more, t("More", "ఇంకా"), size = 20, box = 40) { menu(it) })
        root.addView(head)
        // 🔍 search in the chat (in place of the header)
        searchBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)); minimumHeight = dp(62); setBackgroundColor(ChatTheme.BAR); visibility = View.GONE }
        root.addView(searchBar)
        // while messages are picked (a long-press): ← · how many · reply · star · delete · forward · ⋮ (in place of the header)
        pickBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(2), dp(4)); minimumHeight = dp(62); setBackgroundColor(ChatTheme.BAR); visibility = View.GONE }
        root.addView(pickBar)
        root.addView(View(this).apply { setBackgroundColor(ChatTheme.DIVIDER) }, LinearLayout.LayoutParams(-1, 1))
        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(banner)
        pinMsgBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pinMsgBar)
        pinned = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pinned)
        // (no padding at the sides: each message's row is the full width, so a picked message lights up from edge to edge)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(12)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        // the messages, and the quick emojis that float over them while one message is picked
        stage = FrameLayout(this)
        stage.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (strip != null) placeStrip() }
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (strip != null) placeStrip() }
        replyBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ChatTheme.CHAT_BG) }
        root.addView(replyBar)
        lpBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ChatTheme.CHAT_BG) }
        root.addView(lpBox)
        bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ChatTheme.CHAT_BG) }
        root.addView(bar)
        panelBox = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(panelBox, LinearLayout.LayoutParams(-1, dp(300)))
        setContentView(root)
        buildBar()
    }

    /** The chat's wallpaper (⋮ › 🎨 Chat theme, or Chat settings for all chats). */
    private fun setWall() {
        if (cid.isEmpty()) return
        scroll.background = ChatTheme.WallDrawable(ChatTheme.wall(ChatTheme.wallOf(this, cid)), resources.displayMetrics.density)
    }

    /** A round tap area with a line icon. */
    private fun iconBtn(res: Int, desc: String, color: Int = ChatTheme.TXT, size: Int = 22, box: Int = 44, onClick: (View) -> Unit) =
        Ico.view(this, res, color, size, box, desc, null, onClick)
    /** "2 tasks": the pill that opens the open tasks (gold on navy, navy on white). */
    private fun tasksPill() = TextView(this).apply {
        textSize = 12.5f; setTextColor(ChatTheme.ON_PRIMARY); typeface = Fonts.heavy(context); gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(12), dp(4)); background = ui.round(ChatTheme.PRIMARY, 14); includeFontPadding = false
    }
    private fun setTasksPill(v: TextView, n: Int) {
        Ico.label(v, R.drawable.ic_tasks, ChatTheme.ON_PRIMARY, if (n == 1) t("1 task", "1 పని") else t("$n tasks", "$n పనులు") + if (pinnedOpen) "  ▴" else "", 14)
    }

    /** The typing bar: a round box [smile · Message · attach · tasks · camera] and the round mic (send when there is text).
     *  Or a line saying why I can't write here. */
    private fun buildBar() {
        bar.removeAllViews()
        bar.setPadding(dp(8), dp(6), dp(8), dp(10))
        notice = ui.text("", 14f, ChatTheme.SUB).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(8)); visibility = View.GONE }
        bar.addView(notice, LinearLayout.LayoutParams(-1, -2))
        inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        bar.addView(inputRow, LinearLayout.LayoutParams(-1, -2))
        if (recorder != null) {
            val secs = ((System.currentTimeMillis() - recStart) / 1000).toInt()
            val pill = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = composerBg(); minimumHeight = dp(52); setPadding(dp(4), 0, dp(14), 0) }
            pill.addView(iconBtn(R.drawable.ic_trash, t("Cancel the recording", "రికార్డింగ్ రద్దు"), ChatTheme.DANGER, 21, 44) { cancelRecording() })
            pill.addView(View(this).apply { background = ChatTheme.circle(ChatTheme.DANGER); alpha = if (secs % 2 == 0) 1f else 0.35f }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginStart = dp(4); marginEnd = dp(10) })
            pill.addView(ui.text(t("Recording", "రికార్డ్ అవుతోంది") + "   %d:%02d".format(secs / 60, secs % 60), 15f, ChatTheme.TXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            inputRow.addView(pill, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            inputRow.addView(roundButton(R.drawable.ic_send, t("Send the voice note", "వాయిస్ మెసేజ్ పంపు")) { stopRecording(true) })
            inputRow.postDelayed({ if (recorder != null) buildBar() }, 1000)
            if (secs % 4 == 0 && conv()?.isLocal == false) ChatSync.typing(store, cid, "rec")
            return
        }
        val c = conv()
        val boxRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = composerBg(); minimumHeight = dp(52); setPadding(dp(5), 0, dp(4), 0) }
        emojiBtn = iconBtn(if (panelOpen) R.drawable.ic_keyboard else R.drawable.ic_smile, t("Emoji, GIFs and stickers", "ఎమోజీ, GIF, స్టిక్కర్లు"), ChatTheme.SUB, 22, 42) { if (panelOpen) closePanel(showKeyboard = true) else openPanel() }
        boxRow.addView(emojiBtn)
        input = EditText(this).apply {
            hint = t("Message", "మెసేజ్"); textSize = 15.5f * ChatTheme.fontScale(this@ChatActivity); maxLines = 5; minHeight = dp(48); background = null
            setTextColor(ChatTheme.TXT); setHintTextColor(ChatTheme.SUB); setPadding(dp(4), dp(11), dp(4), dp(11))
            if (ChatTheme.enterSends(this@ChatActivity)) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; imeOptions = EditorInfo.IME_ACTION_SEND; setHorizontallyScrolling(false)
                setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false }
            } else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(db.kv("draft:$cid"))
            setOnClickListener { if (panelOpen) closePanel(showKeyboard = true) }
            setOnFocusChangeListener { _, f -> if (f && panelOpen) closePanel(showKeyboard = false) }
        }
        boxRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        boxRow.addView(iconBtn(R.drawable.ic_attach, t("Attach", "జత చేయి"), ChatTheme.SUB, 21, 40) { attach() })
        tasksBtn = iconBtn(R.drawable.ic_tasks, t("New task or reminder", "కొత్త పని / రిమైండర్"), ChatTheme.SUB, 21, 40) { newTask() }
        if (c == null || (!c.isAi && !c.isBcast)) boxRow.addView(tasksBtn)
        camBtn = iconBtn(R.drawable.ic_camera, t("Camera", "కెమెరా"), ChatTheme.SUB, 21, 40) { camera() }
        boxRow.addView(camBtn)
        inputRow.addView(boxRow, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        action = roundButton(R.drawable.ic_mic, t("Record a voice note", "వాయిస్ మెసేజ్ రికార్డ్")) { if (input.text.isNullOrBlank()) { if (conv()?.isAi == true) toast(t("Type your question for Habit AI.", "Habit AI కి మీ ప్రశ్న టైప్ చేయండి.")) else startRecording() } else sendText() }
        inputRow.addView(action)
        updateBarIcons()
        input.addTextChangedListener(object : TextWatcher {
            private var before = ""
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) { before = s?.toString() ?: "" }
            override fun onTextChanged(s: CharSequence?, start: Int, b: Int, count: Int) {
                // "@" typed at the start of a word in a group: pick who to mention
                if (count == 1 && s != null && start < s.length && s[start] == '@' && (start == 0 || s[start - 1].isWhitespace()) && conv()?.isGroup == true) handler.post { pickMention(start) }
            }
            override fun afterTextChanged(s: Editable?) {
                updateBarIcons(); db.setKv("draft:$cid", s?.toString() ?: "")
                val cv = conv()
                if (!s.isNullOrBlank() && cv != null && !cv.isLocal) ChatSync.typing(store, cid)
                linkPreviewSoon(s?.toString() ?: "")
            }
        })
    }
    private fun updateBarIcons() {
        val empty = input.text.isNullOrBlank()
        val send = !empty || conv()?.isAi == true
        Ico.tint(action, if (send) R.drawable.ic_send else R.drawable.ic_mic, ChatTheme.ON_PRIMARY_ICON)
        action.contentDescription = if (send) t("Send", "పంపు") else t("Record a voice note", "వాయిస్ మెసేజ్ రికార్డ్")
        camBtn.visibility = if (empty) View.VISIBLE else View.GONE
        tasksBtn.visibility = if (empty) View.VISIBLE else View.GONE
    }
    private fun composerBg() = ChatTheme.round(ChatTheme.CARD, dp(26).toFloat(), ChatTheme.EDGE, dp(1))
    /** The big round button next to the typing box (gold on navy, navy on white). */
    private fun roundButton(res: Int, desc: String, onClick: () -> Unit) =
        Ico.view(this, res, ChatTheme.ON_PRIMARY_ICON, 24, 52, desc, ChatTheme.circle(ChatTheme.PRIMARY)) { onClick() }

    // ---------- the 😊 panel (emojis, GIFs, stickers) ----------
    private fun openPanel() {
        val p = panel ?: ChatPanel(this, store, onEmoji = { e -> input.text.insert(maxOf(0, input.selectionStart), e) },
            onBackspace = {
                // deletes one whole character (a whole emoji, with its skin tone or joined parts)
                val i = input.selectionStart
                if (i > 0) {
                    val bi = android.icu.text.BreakIterator.getCharacterInstance()
                    bi.setText(input.text.toString())
                    val start = bi.preceding(i).let { if (it == android.icu.text.BreakIterator.DONE) i - 1 else it }
                    input.text.delete(maxOf(0, start), i)
                }
            },
            onSticker = { ref -> sendSticker(ref) }, onGif = { g -> sendGif(g) }, onCreateSticker = { pickPhoto(REQ_STICKER) }).also { panel = it }
        if (p.view.parent == null) panelBox.addView(p.view, FrameLayout.LayoutParams(-1, -1))
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        handler.postDelayed({ panelBox.visibility = View.VISIBLE; panelOpen = true; Ico.tint(emojiBtn, R.drawable.ic_keyboard, ChatTheme.SUB); syncBack() }, 120)
    }
    private fun closePanel(showKeyboard: Boolean = false) {
        panelBox.visibility = View.GONE; panelOpen = false; Ico.tint(emojiBtn, R.drawable.ic_smile, ChatTheme.SUB); syncBack()
        if (showKeyboard) { input.requestFocus(); getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0) }
    }

    private fun conv(): ChatConv? = if (cid.isEmpty()) null else db.conv(cid)

    /** Draws the whole chat again (the newest at the bottom). */
    private fun render() {
        val c = conv() ?: return
        if (scroll.background == null) setWall()
        if (System.currentTimeMillis() - lastExpire > 30_000) { lastExpire = System.currentTimeMillis(); db.expire() }
        renderHeader(c)
        renderBanner(c)
        renderPinned(c)
        renderPinMsg(c)
        renderReplyBar()
        val total = db.count(cid)
        val msgs = db.msgs(cid, showLimit).filter { it.kind !in ChatDb.HIDDEN }
        if (picked.isNotEmpty()) { val ids = msgs.mapTo(HashSet()) { it.id }; if (picked.retainAll(ids) && picked.isEmpty()) clearPick() }
        val atBottom = !scroll.canScrollVertically(1) || lastCount < 0
        list.removeAllViews()
        if (total > msgs.size) list.addView(ui.text("⬆  " + t("Earlier messages", "పాత మెసేజ్‌లు"), 13.5f, ChatTheme.GOLD_TXT, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); background = ChatTheme.round(ChatTheme.DAY_BG, dp(16).toFloat(), ChatTheme.EDGE, dp(1))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) }
            setOnClickListener { showLimit += 400; lastCount = -2; render(); scroll.post { scroll.scrollTo(0, 0) } }
        })
        if (msgs.isEmpty()) list.addView(ui.text(when {
            c.isSelf -> t("Write notes to yourself here. Tap 📋 for a reminder: it stays at the top (📋 next to the name) until you tick it.",
                "ఇక్కడ మీ కోసం నోట్స్ రాసుకోండి. రిమైండర్ కోసం 📋 నొక్కండి: మీరు టిక్ చేసేవరకు అది పైన ఉంటుంది.")
            c.isAi -> t("🐾 Ask Habit AI anything: \"What's a good reply to this?\", \"Remind me at 6 to call the office\", \"Explain this in Telugu\". You can send a photo too. (AI answers can be wrong sometimes.)",
                "🐾 Habit AI ని ఏదైనా అడగండి: \"దీనికి మంచి జవాబు ఏంటి?\", \"సాయంత్రం 6కి ఆఫీస్‌కి కాల్ చేయమని గుర్తు చేయి\", \"ఇది తెలుగులో చెప్పు\". ఫోటో కూడా పంపవచ్చు. (AI జవాబులు అప్పుడప్పుడు తప్పు కావచ్చు.)")
            c.isBcast -> t("📢 A message here goes to each person in this broadcast list, in their own chat with you. They don't see each other.",
                "📢 ఇక్కడ పంపిన మెసేజ్ ఈ లిస్ట్‌లో ప్రతి ఒక్కరికీ, మీతో వాళ్ళ సొంత చాట్‌లో వెళ్తుంది. ఒకరినొకరు చూడరు.")
            else -> t("🔒 Messages here are locked end to end: only the people in this chat can read them, not even Habit's server. Say hi! 👋",
                "🔒 ఇక్కడి మెసేజ్‌లు ఎండ్ టు ఎండ్ లాక్: ఈ చాట్‌లో ఉన్నవాళ్ళు మాత్రమే చదవగలరు, Habit సర్వర్ కూడా చదవలేదు. హాయ్ చెప్పండి! 👋")
        }, 13.5f, ChatTheme.SUB).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(14), dp(18), dp(14)); background = ChatTheme.round(ChatTheme.DAY_BG, dp(18).toFloat(), ChatTheme.EDGE, dp(1))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(28), dp(20), dp(28), dp(20)) }
        })
        var day: LocalDate? = null
        for (m in msgs) {
            val d = Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).toLocalDate()
            if (d != day) { day = d; list.addView(dayLine(d)) }
            list.addView(pickRow(c, m))
        }
        if (picked.isNotEmpty()) { renderPickBar(); list.post { if (strip != null) placeStrip() } }
        if (pendingGoto.isNotEmpty()) { val g = pendingGoto; pendingGoto = ""; scroll.post { goTo(g) } }
        else if (searchQ.isEmpty() && (atBottom || msgs.size != lastCount)) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        lastCount = msgs.size
        markRead(c, msgs)
        val why = whyNoSend(c)
        notice.text = why; notice.visibility = if (why.isEmpty()) View.GONE else View.VISIBLE
        inputRow.visibility = if (why.isEmpty()) View.VISIBLE else View.GONE
        bar.visibility = if (!c.member || (!c.accepted && c.isGroup) || c.blocked) View.GONE else View.VISIBLE
        if (why.isNotEmpty() && panelOpen) closePanel()
    }
    /** "" when I can write here, else why not (like WhatsApp's "Only admins can send messages"). */
    private fun whyNoSend(c: ChatConv): String = when {
        c.isLocal -> ""
        c.isGroup && c.onlyAdmins && !c.isAdmin(me) -> t("Only admins can send messages", "అడ్మిన్లు మాత్రమే మెసేజ్‌లు పంపగలరు")
        else -> ""
    }

    private fun renderHeader(c: ChatConv) {
        val key = c.emoji + "|" + c.avatar
        if (key != shownAvatar) {
            shownAvatar = key
            avatarBox.removeAllViews()
            avatarBox.addView(ChatDraw.avatar(this, store, c.emoji, if (c.isDm) c.avatar else "", dp(42), null, c.name, c.isGroup || c.isBcast, c.cid, c.isAi), FrameLayout.LayoutParams(-1, -1))
        }
        title.text = c.name
        sub.text = Ico.iconize(this, subtitle(c), "🔒 ", R.drawable.ic_lock, ChatTheme.SUB, dp(13))
        val typing = ChatSync.typingIn(cid).isNotEmpty() || (c.isAi && ChatSync.aiBusy)
        val online = presence.isNotEmpty() && presence == t("online", "ఆన్‌లైన్‌లో ఉన్నారు")
        sub.setTextColor(if (typing) ChatTheme.GOLD_TXT else if (online) ChatTheme.ONLINE_TXT else ChatTheme.SUB)
        sub.typeface = if (typing || online) Fonts.semi(this) else Fonts.regular(this)
        onlineDot.visibility = if (online && c.isDm) View.VISIBLE else View.GONE
        callBtns.visibility = if (c.isDm && c.accepted && !c.blocked && c.member) View.VISIBLE else View.GONE
    }
    private fun subtitle(c: ChatConv): String {
        if (c.isAi && ChatSync.aiBusy) return t("typing…", "టైప్ చేస్తోంది…")
        val typing = ChatSync.typingIn(cid)
        if (typing.isNotEmpty()) {
            val (u, what) = typing.first()
            val verb = if (what == "rec") t("recording audio…", "ఆడియో రికార్డ్ చేస్తున్నారు…") else t("typing…", "టైప్ చేస్తున్నారు…")
            return if (c.isGroup) c.nameOf(u).substringBefore(' ') + " " + verb else verb
        }
        val timer = ChatTheme.disappear(this, cid).let { if (it > 0) "⏱ " else "" }
        return timer + when {
            c.isSelf -> t("Only on this phone · notes and reminders", "ఈ ఫోన్‌లో మాత్రమే · నోట్స్, రిమైండర్లు")
            c.isAi -> t("Your AI friend · says where facts come from", "మీ AI స్నేహితుడు · సమాచారం ఎక్కడిదో చెబుతుంది")
            c.isBcast -> "📢 " + t("${c.members().size} recipients", "${c.members().size} మందికి")
            c.isGroup -> (c.comm?.let { "🏘️ " + it.optString("name") + " · " } ?: "") + c.members().joinToString(", ") { if (it.optString("uid") == me) t("You", "మీరు") else it.optString("name") }
            presence.isNotEmpty() -> presence
            else -> c.data.optString("handle").let { if (it.isNotEmpty()) "@$it · 🔒 " + t("locked end to end", "ఎండ్ టు ఎండ్ లాక్") else "🔒 " + t("locked end to end", "ఎండ్ టు ఎండ్ లాక్") }
        }
    }

    private fun markRead(c: ChatConv, msgs: List<ChatMsg>) {
        db.readAll(cid)
        if (c.isLocal || !c.accepted) return
        val top = msgs.filter { it.sender != me && it.seq > 0 }.maxOfOrNull { it.seq } ?: 0L
        if (top > readSent) {
            readSent = top
            // (read receipts off: only my linked computers learn it, quietly)
            val quiet = !ChatTheme.receiptsOn(this)
            Thread { ChatSync.api(store, "POST", "/api/chat/read", JSONObject().put("cid", cid).put("seq", top).put("quiet", quiet).toString()) }.start()
        }
    }

    private fun dayLine(d: LocalDate): View {
        val today = LocalDate.now()
        val s = when (d) { today -> t("Today", "ఈరోజు"); today.minusDays(1) -> t("Yesterday", "నిన్న"); else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())) }
        return ui.text(s, 12f, ChatTheme.DAY_TXT, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(5), dp(12), dp(5)); setLineSpacing(0f, 1f)
            background = ChatTheme.round(ChatTheme.DAY_BG, dp(13).toFloat(), ChatTheme.EDGE, dp(1))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(4) }
        }
    }
    private fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    private fun nameColor(uid: String) = NAMES[Math.floorMod(uid.hashCode(), NAMES.size)]
    private fun centerLine(s: String) = ui.text(s, 12.5f, ChatTheme.SUB).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); maxWidth = (resources.displayMetrics.widthPixels * 0.86).toInt()
        background = ChatTheme.round(ChatTheme.DAY_BG, dp(14).toFloat(), ChatTheme.EDGE, dp(1))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
    }

    // ---------- one message ----------
    private fun bubble(c: ChatConv, m: ChatMsg): View {
        if (m.kind == "sys") return centerLine(ChatSync.sysText(this, cid, m.body))
        if (m.kind == "timer") return centerLine("⏱ " + (if (m.sender == me) t("You", "మీరు") else c.nameOf(m.sender)) + " " + (if (m.body.optLong("s") > 0)
            t("turned on disappearing messages. New messages will disappear after ${ChatTheme.timerText(m.body.optLong("s"), false)}.", "కనిపించకుండా పోయే మెసేజ్‌లు ఆన్ చేశారు: కొత్త మెసేజ్‌లు ${ChatTheme.timerText(m.body.optLong("s"), true)} తర్వాత పోతాయి.")
            else t("turned off disappearing messages.", "కనిపించకుండా పోయే మెసేజ్‌లు ఆఫ్ చేశారు.")))
        val mine = m.sender == me || c.isSelf
        val bare = m.kind == "sticker"
        // a new task is a card of its own (nearly full width), not a bubble
        val card = m.kind == "task" && m.body.optString("action") == "new"
        val page = ink; val pageMine = inkMine
        ink = ChatTheme.ink(mine && !card); inkMine = mine && !card
        try { return drawBubble(c, m, mine, bare, card, ink) } finally { ink = page; inkMine = pageMine }
    }
    private var inkMine = false
    private fun drawBubble(c: ChatConv, m: ChatMsg, mine: Boolean, bare: Boolean, card: Boolean, k: ChatTheme.Ink): View {
        val fs = ChatTheme.fontScale(this)
        val media = (m.kind == "photo" && !m.body.optBoolean("once")) || m.kind == "video" || m.kind == "gif"
        val r = dp(20).toFloat(); val small = dp(6).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            when {
                bare -> { }
                card -> { setPadding(dp(14), dp(13), dp(14), dp(10)); background = ChatTheme.round(ChatTheme.CARD, dp(22).toFloat(), if (ChatTheme.dark) Color.parseColor("#59F5C451") else ChatTheme.GOLD, if (ChatTheme.dark) dp(1) else dp(2)) }
                else -> {
                    if (media) setPadding(dp(4), dp(4), dp(4), dp(5)) else setPadding(dp(11), dp(7), dp(11), dp(6))
                    background = if (mine) ChatTheme.corners(k.bg, r, small, r, r) else ChatTheme.corners(k.bg, small, r, r, r, k.edge, dp(1))
                }
            }
        }
        val inset = if (media) dp(6) else 0      // words above or under a photo line up with the photo's edge
        if (m.sender == "ai") box.addView(ui.text("", 12.5f, k.gold, bold = true).apply { text = Ico.withIcon(context, R.drawable.ic_sparkle, k.gold, dp(14), "Habit AI"); setPadding(inset, 0, 0, dp(2)) })
        else if (c.isGroup && !mine && !card) box.addView(ui.text(c.nameOf(m.sender), 13f, nameColor(m.sender), bold = true).apply { setPadding(inset, 0, 0, dp(2)) })
        if (m.body.optBoolean("fwd")) box.addView(ui.text("", 12f, k.sub).apply { text = Ico.withIcon(context, R.drawable.ic_forward, k.sub, dp(13), t("Forwarded", "ఫార్వర్డ్ చేసినది")); setTypeface(typeface, Typeface.ITALIC); setPadding(inset, 0, 0, dp(2)) })
        m.body.optJSONObject("re")?.let { box.addView(quoteView(c, it, true)) }
        val foot = footView(c, m, mine, bare, k)
        when (m.kind) {
            "text" -> {
                m.body.optJSONObject("lp")?.let { box.addView(previewCard(it, true)) }
                box.addView(ui.text("", 15.5f * fs, k.txt).apply { text = rich(m.body.optString("text")); maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt() - dp(22); Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS); setLinkTextColor(k.link); setLineSpacing(0f, 1.15f) })
            }
            "photo" -> box.addView(if (m.body.optBoolean("once")) onceView(m) else photoView(m))
            "voice" -> box.addView(voiceView(m, foot))
            "video" -> box.addView(videoView(m))
            "doc" -> box.addView(docView(m))
            "sticker" -> box.addView(stickerView(m))
            "gif" -> box.addView(gifView(m))
            "poll" -> box.addView(pollView(c, m))
            "event" -> box.addView(eventView(c, m))
            "loc" -> box.addView(locView(m))
            "contact" -> box.addView(contactView(m))
            "task" -> box.addView(taskInline(c, m))
            "clog" -> box.addView(callLogView(m))
            "gone" -> box.addView(ui.text("", 14.5f, k.sub).apply { text = Ico.withIcon(context, R.drawable.ic_close, k.sub, dp(15), t("This message was deleted", "ఈ మెసేజ్ తొలగించబడింది")); setTypeface(typeface, Typeface.ITALIC) })
            else -> box.addView(ui.text("", 14f, k.sub).apply { text = Ico.withIcon(context, R.drawable.ic_lock, k.sub, dp(15), t("This message couldn't be opened on this phone (it was locked for an older key, or it needs a newer Habit AI).", "ఈ మెసేజ్ ఈ ఫోన్‌లో తెరవలేకపోయాం (కొత్త Habit AI కావాలేమో).")) })
        }
        if (media && m.body.optString("text").isNotBlank())
            box.addView(ui.text("", 15.5f * fs, k.txt).apply { text = rich(m.body.optString("text")); setPadding(dp(6), dp(6), dp(6), 0); Linkify.addLinks(this, Linkify.WEB_URLS); setLinkTextColor(k.link); setLineSpacing(0f, 1.15f) })
        if (m.sender == "ai") m.body.optJSONObject("_rate")?.let { box.addView(rateRow(m, it, k)) }
        // the time and the ticks (voice notes have them next to their length)
        if (m.kind != "voice") box.addView(foot, LinearLayout.LayoutParams(if (bare) -2 else -1, -2).apply { gravity = Gravity.END; if (media) marginEnd = dp(6) })
        if (m.state == -1) box.addView(ui.text(t("Not sent. Tap to try again.", "పంపలేదు. మళ్ళీ ప్రయత్నించడానికి నొక్కండి."), 12f, if (mine) k.txt else ChatTheme.DANGER, bold = true).apply { setPadding(inset, dp(2), 0, 0) })
        // the bubble, its reactions under it, and the quick forward button for photos, videos and links
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (mine) Gravity.END else Gravity.START }
        col.addView(box, LinearLayout.LayoutParams(if (card) -1 else -2, -2))
        reactions(m)?.let { col.addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -dp(7); if (mine) marginEnd = dp(10) else marginStart = dp(10) }) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = (if (mine) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL }
        val quick = !c.isLocal && (m.kind in setOf("photo", "video", "gif", "doc") && !m.body.optBoolean("once") || (m.kind == "text" && m.body.has("lp")))
        val fwd = if (quick) Ico.view(this, R.drawable.ic_forward, ChatTheme.TXT2, 17, 34, t("Forward", "ఫార్వర్డ్"), ChatTheme.round(ChatTheme.CARD, dp(17).toFloat(), ChatTheme.EDGE, dp(1))) { forward(m) } else null
        if (mine && fwd != null) row.addView(fwd, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) })
        val w = (resources.displayMetrics.widthPixels * 0.80).toInt()
        row.addView(col, if (card) LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(6); bottomMargin = dp(2); if (mine) marginStart = dp(22) else marginEnd = dp(22) }
            else LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        if (!mine && fwd != null) row.addView(fwd, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })
        if (!card) box.post { if (box.width > w) box.layoutParams = (box.layoutParams as LinearLayout.LayoutParams).apply { width = w } }
        box.setOnClickListener { if (m.state == -1) retry(m) }
        box.setOnLongClickListener { msgMenu(c, m); true }
        return row
    }
    /** Under an answer from Habit's own brain (when Claude is resting or down): which brain answered, and 👍 / 👎, which
     *  teach Habit's own AI model (only with "Help Habit learn" on). */
    private fun rateRow(m: ChatMsg, r: JSONObject, k: ChatTheme.Ink): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        val self = r.optString("b") == "self"
        row.addView(ui.text("🧠 " + (if (self) t("Habit's own brain", "హ్యాబిట్ సొంత మెదడు") else t("Habit's own AI (Gemma)", "హ్యాబిట్ సొంత AI (జెమ్మా)")), 12f, k.sub), LinearLayout.LayoutParams(0, -2, 1f))
        if (!store.learnShare) return row
        val rated = m.body.optString("_rated")
        if (rated.isNotEmpty()) { row.addView(ui.text(t("Thank you ", "ధన్యవాదాలు ") + (if (rated == "up") "👍" else "👎"), 12f, k.sub)); return row }
        fun chip(e: String, good: Boolean, say: String) = ui.text(e, 15f, k.txt).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(3), dp(10), dp(3)); contentDescription = say
            background = ChatTheme.round(AppLook.mix(k.bg, k.txt, 0.12f), dp(14).toFloat())
            setOnClickListener { rateAi(m, good) }
        }
        row.addView(chip("👍", true, t("Good answer", "మంచి జవాబు")), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        row.addView(chip("👎", false, t("Wrong answer", "తప్పు జవాబు")), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        return row
    }
    private val rater by lazy { Brain(store) }
    private fun rateAi(m: ChatMsg, good: Boolean) {
        val r = m.body.optJSONObject("_rate") ?: return
        val send = { fix: String ->
            rater.rate(r.optString("rid"), r.optString("q"), r.optString("r"), good, fix) { err ->
                if (err != null) toast(err)
                else { m.body.put("_rated", if (good) "up" else "down"); db.setBody(cid, m.id, m.body); render(); toast(t("Thank you! This helps Habit's own brain learn.", "ధన్యవాదాలు! దీంతో హ్యాబిట్ సొంత మెదడు నేర్చుకుంటుంది.")) }
            }
        }
        val go = {
            if (good) send("")
            else askText(t("👎 What is the right answer?", "👎 సరైన జవాబు ఏమిటి?"), t("In your own words (you can leave it empty). No phone numbers or personal details.", "మీ మాటల్లో (ఖాళీగా వదిలేయొచ్చు). ఫోన్ నంబర్లు, వ్యక్తిగత వివరాలు వద్దు."), "", t("Send", "పంపు")) { send(it) }
        }
        if (db.kv("gemma:rateOk") == "1") { go(); return }
        AlertDialog.Builder(this).setTitle(t("Teach Habit's own brain?", "హ్యాబిట్ సొంత మెదడుకి నేర్పించాలా?"))
            .setMessage(t("Your 👍 or 👎 helps Habit's own AI learn. Your question, this answer and the right answer you write go to Habit's server. Phone numbers, e-mail addresses and long numbers are taken out, and the app's owner checks them before anything is used. Please don't write personal details. (You can turn this off in Settings: Help Habit learn.)",
                "మీ 👍 లేదా 👎 హ్యాబిట్ సొంత AIకి నేర్పిస్తుంది. మీ ప్రశ్న, ఈ జవాబు, మీరు రాసే సరైన జవాబు హ్యాబిట్ సర్వర్‌కి వెళ్తాయి. ఫోన్ నంబర్లు, ఈమెయిల్స్, పెద్ద నంబర్లు తీసేస్తాం; వాడే ముందు యాప్ యజమాని చూస్తారు. వ్యక్తిగత వివరాలు రాయకండి. (సెట్టింగ్స్‌లో ఆపేయొచ్చు: Help Habit learn.)"))
            .setPositiveButton(t("OK, send", "సరే, పంపు")) { _, _ -> db.setKv("gemma:rateOk", "1"); go() }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** The time, ⭐ starred, "Edited", ⏱ and the ticks (a clock while it is still being sent). */
    private fun footView(c: ChatConv, m: ChatMsg, mine: Boolean, bare: Boolean, k: ChatTheme.Ink): View {
        val col = if (bare) Color.WHITE else k.sub
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            if (bare) { setPadding(dp(8), dp(2), dp(6), dp(2)); background = ui.round(Color.parseColor("#99000000"), 10) } else setPadding(0, dp(2), 0, 0)
        }
        if (m.body.optBoolean("_star")) row.addView(smallIcon(R.drawable.ic_star, col), LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(4) })
        val words = (if (m.body.optBoolean("_ed")) t("Edited · ", "మార్చారు · ") else "") + (if (m.body.has("_exp")) "⏱ " else "") + time(m.at)
        row.addView(ui.text(words, 11f, col).apply { setLineSpacing(0f, 1f) })
        if (mine && !c.isLocal) {
            val read = c.ticks(m.seq, me) == 3 && m.state == 1
            val res = when {
                m.state == -1 -> R.drawable.ic_close
                m.state == 0 || m.seq <= 0 -> R.drawable.ic_clock
                else -> if (c.ticks(m.seq, me) >= 2) R.drawable.ic_ticks else R.drawable.ic_tick
            }
            val tc = when { bare && read -> Color.parseColor("#7CC8FF"); bare -> Color.WHITE; m.state == -1 -> ChatTheme.DANGER; read -> k.read; else -> k.tick }
            val size = if (res == R.drawable.ic_clock) 13 else 16
            row.addView(smallIcon(res, tc), LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(3) })
        }
        return row
    }
    private fun smallIcon(res: Int, color: Int) = ImageView(this).apply { setImageDrawable(getDrawable(res)!!.mutate()); imageTintList = android.content.res.ColorStateList.valueOf(color) }
    /** Text with @mentions in gold and the 🔍 search words marked. */
    private fun rich(s: String): CharSequence {
        val sb = SpannableStringBuilder(s)
        for (r in Regex("""@[\p{L}\p{M}\p{N}_.]+""").findAll(s)) { sb.setSpan(ForegroundColorSpan(ink.gold), r.range.first, r.range.last + 1, 0); sb.setSpan(StyleSpan(Typeface.BOLD), r.range.first, r.range.last + 1, 0) }
        if (searchQ.isNotEmpty()) { val low = s.lowercase(); val q = searchQ.lowercase(); var i = low.indexOf(q); while (i >= 0) { sb.setSpan(BackgroundColorSpan(Color.parseColor("#88F6C453")), i, i + q.length, 0); i = low.indexOf(q, i + q.length) } }
        return sb
    }
    /** The reactions under a message: the emojis and how many; tap to see who (and take mine back). */
    private fun reactions(m: ChatMsg): View? {
        val rx = m.body.optJSONObject("_rx") ?: return null
        if (rx.length() == 0) return null
        val counts = LinkedHashMap<String, Int>()
        rx.keys().forEach { u -> rx.optJSONObject(u)?.optString("e")?.takeIf { it.isNotEmpty() }?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        if (counts.isEmpty()) return null
        val label = counts.entries.sortedByDescending { it.value }.take(3).joinToString("") { it.key } + (if (rx.length() > 1) " " + rx.length() else "")
        return ui.text(label, 12.5f, ChatTheme.TXT, bold = true).apply {
            setPadding(dp(9), dp(3), dp(9), dp(4)); setLineSpacing(0f, 1f); background = ChatTheme.round(ChatTheme.BAR, dp(13).toFloat(), if (ChatTheme.dark) Color.parseColor("#1FFFFFFF") else ChatTheme.LINE, dp(1))
            setOnClickListener { whoReacted(m) }
        }
    }
    private fun whoReacted(m: ChatMsg) {
        val c = conv() ?: return
        val rx = m.body.optJSONObject("_rx") ?: return
        val rows = rx.keys().asSequence().toList().sortedBy { if (it == me) 0 else 1 }.map { u -> (rx.optJSONObject(u)?.optString("e") ?: "") + "   " + (if (u == me) t("You · tap to remove", "మీరు · తీసేయడానికి నొక్కండి") else c.nameOf(u)) to u }
        AlertDialog.Builder(this).setTitle(t("Reactions", "స్పందనలు")).setItems(rows.map { it.first }.toTypedArray()) { _, i -> if (rows[i].second == me) { ChatSync.react(this, store, m, ""); render() } }.show()
    }

    // =================== picking messages with a long-press, like WhatsApp ===================
    // The picked message's row lights up from edge to edge, the top bar shows what to do with it
    // (reply · star · delete · forward · ⋮ more) and the quick emojis float just above it. Nothing covers the chat:
    // it still scrolls, and a tap on other messages picks them too. ← or the phone's Back stops picking.
    private val picked = LinkedHashSet<String>()
    private lateinit var pickBar: LinearLayout
    private lateinit var stage: FrameLayout
    private var strip: View? = null
    private var stripFor = ""
    private var stripMine = false
    private var searchWasOpen = false

    /** One message's row, the whole width. A long-press anywhere on it (on a photo, a link, a button, or beside the
     *  bubble) picks it; while picking, a tap anywhere on it picks or unpicks it (the things inside don't get that tap). */
    private inner class PickRow(val mid: String, val onLong: () -> Unit) : FrameLayout(this@ChatActivity) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var fired = false
        private val longPress = Runnable {
            fired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLong()
            cancelInside()
        }
        /** The photo, link or button under the finger doesn't also get a tap (or a long-press) from this touch. */
        private fun cancelInside() {
            val e = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(e); e.recycle()
        }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false; downX = ev.x; downY = ev.y; removeCallbacks(longPress)
                    if (picked.isEmpty()) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) removeCallbacks(longPress)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            if (fired) return true      // the rest of this touch belongs to the long-press
            return super.dispatchTouchEvent(ev)
        }
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = picked.isNotEmpty() || super.onInterceptTouchEvent(ev)
        fun paint() = setBackgroundColor(if (mid in picked) ChatTheme.PICK else Color.TRANSPARENT)
    }
    private fun pickRow(c: ChatConv, m: ChatMsg): View {
        val v = bubble(c, m)
        if (m.kind == "sys" || m.kind == "timer") return v.apply { tag = m.id }
        return PickRow(m.id) { msgMenu(c, m) }.apply {
            tag = m.id
            setPadding(dp(12), 0, dp(12), 0)
            addView(v, FrameLayout.LayoutParams(-1, -2))
            setOnClickListener { if (picked.isNotEmpty()) togglePick(m.id) }
            paint()
        }
    }
    private var lastPickAt = 0L
    private var lastPickId = ""
    /** A long-press on a message: pick it (while picking: pick or unpick it). The row and the photo / bubble inside it
     *  may both feel the same long-press: it counts once. */
    private fun msgMenu(c: ChatConv, m: ChatMsg) {
        if (m.kind == "sys" || m.kind == "timer" || c.cid != cid) return
        val now = android.os.SystemClock.uptimeMillis()
        if (m.id == lastPickId && now - lastPickAt < 700) return
        lastPickAt = now; lastPickId = m.id
        if (picked.isEmpty()) searchWasOpen = searchBar.visibility == View.VISIBLE
        togglePick(m.id)
    }
    private fun togglePick(id: String) {
        if (!picked.remove(id)) picked.add(id)
        if (picked.isEmpty()) { clearPick(); return }
        head.visibility = View.GONE; searchBar.visibility = View.GONE; pickBar.visibility = View.VISIBLE
        paintRows(); renderPickBar(); showStrip(); syncBack()
    }
    private fun clearPick() {
        picked.clear(); hideStrip()
        if (pickBar.visibility == View.VISIBLE) {
            pickBar.visibility = View.GONE
            if (searchWasOpen) searchBar.visibility = View.VISIBLE else head.visibility = View.VISIBLE
            paintRows()
        }
        syncBack()
    }
    private fun paintRows() { for (i in 0 until list.childCount) (list.getChildAt(i) as? PickRow)?.paint() }
    private fun rowOf(id: String): View? { for (i in 0 until list.childCount) list.getChildAt(i).let { if (it.tag == id) return it }; return null }
    private fun pickedMsgs(): List<ChatMsg> = picked.mapNotNull { db.msg(cid, it) }.sortedBy { it.at }

    /** The top bar while picking: ← · how many · then only what can be done with all of them. */
    private fun renderPickBar() {
        val c = conv() ?: return
        val msgs = pickedMsgs()
        pickBar.removeAllViews()
        pickBar.addView(iconBtn(R.drawable.ic_back, t("Stop picking", "ఎంచుకోవడం ఆపు")) { clearPick() })
        pickBar.addView(ui.text(msgs.size.toString(), 20f, ChatTheme.TXT, bold = true).apply { setPadding(dp(12), 0, 0, 0); setLineSpacing(0f, 1f) }, LinearLayout.LayoutParams(0, -2, 1f))
        val one = msgs.singleOrNull()
        val content = msgs.isNotEmpty() && msgs.all { it.kind in CONTENT }
        if (one != null && canReply(c, one)) pickBar.addView(iconBtn(R.drawable.ic_reply, t("Reply", "జవాబు")) { replyNow(one) })
        if (content) {
            val all = msgs.all { it.body.optBoolean("_star") }
            pickBar.addView(iconBtn(if (all) R.drawable.ic_star_off else R.drawable.ic_star_line, if (all) t("Unstar", "స్టార్ తీసేయి") else t("Star", "స్టార్")) { starPicked(!all) })
        }
        pickBar.addView(iconBtn(R.drawable.ic_trash, t("Delete", "తొలగించు"), size = 21) { deletePicked() })
        if (content && msgs.none { it.kind == "photo" && it.body.optBoolean("once") })
            pickBar.addView(iconBtn(R.drawable.ic_forward, t("Forward", "ఫార్వర్డ్")) { clearPick(); forward(msgs) })
        if (moreActions(c, msgs).isNotEmpty()) pickBar.addView(iconBtn(R.drawable.ic_more, t("More", "ఇంకా"), size = 20, box = 40) { pickMore(it) })
    }
    private fun canReply(c: ChatConv, m: ChatMsg) = m.kind in CONTENT && c.canSend(me) && whyNoSend(c).isEmpty() && c.member && !c.isBcast
    private fun replyNow(m: ChatMsg) {
        clearPick(); replyTo = m; renderReplyBar()
        if (panelOpen) closePanel(showKeyboard = true)
        else { input.requestFocus(); getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0) }
    }
    private fun starPicked(on: Boolean) {
        val msgs = pickedMsgs().filter { it.kind in CONTENT }
        clearPick()
        msgs.forEach { ChatSync.star(this, it, on) }
    }
    /** Delete: also "for everyone" when they are all mine and were sent in the last 2 days. */
    private fun deletePicked() {
        val c = conv() ?: return
        val msgs = pickedMsgs()
        if (msgs.isEmpty()) return
        val now = System.currentTimeMillis()
        val forAll = !c.isLocal && c.member && msgs.all { it.kind in CONTENT && it.sender == me && it.state == 1 && now - it.at < 2 * 86_400_000L }
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (forAll) opts.add(t("Delete for everyone", "అందరికీ తొలగించు") to {
            clearPick()
            msgs.forEach { m -> ChatSync.deleteForAll(this, store, m) { e -> if (e != null && !e.startsWith("No connection")) toast(e) } }
        })
        opts.add(t("Delete for me", "నాకు మాత్రమే తొలగించు") to {
            clearPick()
            for (m in msgs) { m.body.optString("local").takeIf { it.isNotEmpty() }?.let { try { File(it).delete() } catch (e: Exception) { } }; db.deleteMsg(cid, m.id) }
            render()
        })
        AlertDialog.Builder(this).setTitle(if (msgs.size == 1) t("Delete this message?", "ఈ మెసేజ్ తొలగించాలా?") else t("Delete ${msgs.size} messages?", "${msgs.size} మెసేజ్‌లు తొలగించాలా?"))
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** Copy: one message's words; several as "[date, time] name: words" lines (like WhatsApp). */
    private fun copyPicked(msgs: List<ChatMsg>) {
        val c = conv() ?: return
        val withText = msgs.filter { it.body.optString("text").isNotBlank() }
        if (withText.isEmpty()) return
        val s = if (withText.size == 1) withText[0].body.optString("text") else withText.joinToString("\n") { m ->
            "[" + Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d/M/yy, h:mm a", Locale.ENGLISH)) + "] " +
                (if (m.sender == me) t("You", "మీరు") else c.nameOf(m.sender)) + ": " + m.body.optString("text")
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", s))
        toast(if (withText.size == 1) t("Copied", "కాపీ అయింది") else t("${withText.size} messages copied", "${withText.size} మెసేజ్‌లు కాపీ అయ్యాయి"))
    }
    /** ⋮ while picking: the rest (info, copy, pin, edit, share, report…), only what fits the picked messages. */
    private fun moreActions(c: ChatConv, msgs: List<ChatMsg>): List<Pair<String, () -> Unit>> {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val m = msgs.singleOrNull()
        if (m == null) {
            if (msgs.any { it.body.optString("text").isNotBlank() }) items.add(t("Copy", "కాపీ") to { copyPicked(msgs) })
            val from = msgs.map { it.sender }.toSet().singleOrNull()
            if (from != null && from != me && from != "ai" && !c.isLocal) items.add(t("Report", "రిపోర్ట్") to { report(msgs, from) })
            return items
        }
        val content = m.kind in CONTENT
        if (m.sender == me && !c.isLocal && m.state == 1 && content) items.add(t("Info", "వివరాలు") to { msgInfo(c, m) })
        if (m.body.optString("text").isNotBlank()) items.add(t("Copy", "కాపీ") to { copyPicked(msgs) })
        val pinnedNow = ChatSync.pinned(this, cid)?.id == m.id
        if (content && !c.isLocal && c.member && m.state == 1 && !(c.isGroup && c.adminsEdit && !c.isAdmin(me)))
            items.add((if (pinnedNow) t("Unpin", "పిన్ తీసేయి") else t("Pin", "పిన్ చేయి")) to { if (pinnedNow) ChatSync.pin(this, store, m, false, 0) else pinFor(m) })
        if (m.kind == "text" && m.sender == me && (c.isLocal || m.state == 1) && System.currentTimeMillis() - m.at < 15 * 60_000L && !c.isBcast && !c.isAi)
            items.add(t("Edit", "మార్చు") to { editMsg(m) })
        if ((m.kind == "video" || m.kind == "doc" || m.kind == "photo" || m.kind == "gif") && fileReady(m) && !m.body.optBoolean("once"))
            items.add(t("Share / save", "షేర్ / సేవ్") to { openFile(m, true) })
        if (m.kind == "sticker") stickerRef(m)?.let { ref ->
            items.add((if (Stickers.isFav(this, ref)) t("Remove from favourite stickers", "ఇష్టమైన స్టిక్కర్ల నుంచి తీసేయి") else t("Add to favourite stickers", "ఇష్టమైన స్టిక్కర్లలో చేర్చు")) to {
                val r = if (ref.startsWith("file:") && m.sender != me) "file:" + Stickers.saveMine(this, File(ref.removePrefix("file:")).readBytes()).absolutePath else ref
                Stickers.fav(this, r, !Stickers.isFav(this, ref)); toast(t("Done ⭐", "అయింది ⭐"))
            })
        }
        if (m.kind == "event") items.add(t("Add to calendar", "క్యాలెండర్‌లో చేర్చు") to { addToCalendar(m) })
        if (m.sender != me && !c.isLocal && (m.kind == "text" || m.body.optString("text").isNotBlank())) items.add(t("Check for fraud", "మోసమేమో చెక్ చేయి") to { checkFraud(m) })
        if (m.sender != me && m.sender != "ai" && !c.isLocal) items.add(t("Report", "రిపోర్ట్") to { report(listOf(m), m.sender) })
        return items
    }
    private fun pickMore(anchor: View) {
        val c = conv() ?: return
        val items = moreActions(c, pickedMsgs())
        if (items.isEmpty()) return
        val pm = PopupMenu(this, anchor)
        items.forEachIndexed { i, it -> pm.menu.add(0, i, i, it.first) }
        pm.setOnMenuItemClickListener { mi -> clearPick(); items[mi.itemId].second(); true }
        pm.show()
    }

    /** The quick emojis (👍 ❤️ 😂 😮 😢 🙏 ＋), floating just above the picked message (when one message is picked). */
    private fun showStrip() {
        hideStrip()
        val c = conv() ?: return
        val m = pickedMsgs().singleOrNull() ?: return
        if (m.kind !in CONTENT || (c.isLocal && !c.isSelf)) return
        val mineNow = m.body.optJSONObject("_rx")?.optJSONObject(me)?.optString("e") ?: ""
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4))
            background = ChatTheme.round(ChatTheme.STRIP, dp(28).toFloat(), ChatTheme.EDGE, dp(1)); elevation = dp(8).toFloat()
        }
        for (e in Emojis.QUICK) s.addView(TextView(this).apply {
            text = e; textSize = 25f; gravity = Gravity.CENTER; includeFontPadding = false; contentDescription = e
            background = if (e == mineNow) ChatTheme.circle(ChatTheme.PILL) else Ico.ripple(context)
            setOnClickListener { clearPick(); ChatSync.react(this@ChatActivity, store, m, if (e == mineNow) "" else e) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        s.addView(Ico.view(this, R.drawable.ic_plus, ChatTheme.TXT2, 20, 36, t("More reactions", "ఇంకా స్పందనలు"), ChatTheme.circle(ChatTheme.CARD2)) { clearPick(); anyReaction(m) },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4); marginEnd = dp(2) })
        strip = s; stripFor = m.id; stripMine = m.sender == me || c.isSelf
        s.visibility = View.INVISIBLE
        stage.addView(s, FrameLayout.LayoutParams(-2, -2))
        s.post {
            if (strip !== s) return@post
            placeStrip()
            s.pivotX = if (stripMine) s.width.toFloat() else 0f; s.pivotY = s.height.toFloat()
            s.alpha = 0f; s.scaleX = 0.8f; s.scaleY = 0.8f
            s.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
    }
    /** Keeps the emojis just above the message (under it when there's no room above), also while the chat scrolls. */
    private fun placeStrip() {
        val s = strip ?: return
        val row = rowOf(stripFor) ?: run { hideStrip(); return }
        val top = row.top - scroll.scrollY
        val h = s.height; val w = s.width
        var y = top - h + dp(2)
        if (y < dp(4)) y = top + row.height + dp(2)
        y = y.coerceIn(dp(4), maxOf(dp(4), stage.height - h - dp(4)))
        s.translationX = maxOf(dp(4), if (stripMine) stage.width - w - dp(10) else dp(10)).toFloat()
        s.translationY = y.toFloat()
        s.visibility = if (top + row.height < 0 || top > stage.height) View.INVISIBLE else View.VISIBLE
    }
    private fun hideStrip() { strip?.let { stage.removeView(it) }; strip = null; stripFor = "" }
    private fun anyReaction(m: ChatMsg) {
        val all = Emojis.recent(this).take(16) + Emojis.CATS.flatMap { it.list }.take(160)
        val grid = android.widget.GridView(this).apply { numColumns = 7; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        var d: AlertDialog? = null
        grid.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = all.size
            override fun getItem(p: Int) = all[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, old: View?, parent: android.view.ViewGroup?): View = ((old as? TextView) ?: TextView(this@ChatActivity).apply { textSize = 26f; gravity = Gravity.CENTER; layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(46)) }).apply { text = all[p] }
        }
        grid.setOnItemClickListener { _, _, p, _ -> d?.dismiss(); Emojis.used(this, all[p]); ChatSync.react(this, store, m, all[p]); render() }
        d = AlertDialog.Builder(this).setView(grid).create(); d.show()
    }
    private fun pinFor(m: ChatMsg) {
        val opts = arrayOf(t("24 hours", "24 గంటలు"), t("7 days", "7 రోజులు"), t("30 days", "30 రోజులు"))
        val ms = longArrayOf(86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L)
        AlertDialog.Builder(this).setTitle(t("📌 Pin for how long?", "📌 ఎంత కాలం పిన్ చేయాలి?")).setItems(opts) { _, i -> ChatSync.pin(this, store, m, true, System.currentTimeMillis() + ms[i]); render() }.show()
    }
    private fun editMsg(m: ChatMsg) {
        askText(t("✏️ Edit message", "✏️ మెసేజ్ మార్చండి"), t("Message", "మెసేజ్"), m.body.optString("text"), t("Save", "సేవ్")) { s ->
            if (s.isBlank() || s == m.body.optString("text")) return@askText
            if (conv()?.isLocal == true) { m.body.put("text", s.take(4000)).put("_ed", true); db.setBody(cid, m.id, m.body); render() }
            else ChatSync.edit(this, store, m, s)
        }
    }
    /** Message info: who has it (✓✓) and who has read it (blue ✓✓). */
    private fun msgInfo(c: ChatConv, m: ChatMsg) {
        val st = c.data.optJSONObject("st") ?: JSONObject()
        val others = c.members().filter { it.optString("uid") != me }
        val read = others.filter { (st.optJSONObject(it.optString("uid"))?.optLong("r") ?: 0) >= m.seq }
        val got = others.filter { it !in read && (st.optJSONObject(it.optString("uid"))?.optLong("d") ?: 0) >= m.seq }
        val waiting = others.filter { it !in read && it !in got }
        val sb = StringBuilder()
        sb.append(t("Sent ", "పంపింది ") + Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)) + "\n\n")
        sb.append("✓✓ " + t("Read by", "చదివినవాళ్ళు") + " (${read.size})\n"); read.forEach { sb.append("   " + it.optString("name") + "\n") }
        sb.append("\n✓✓ " + t("Delivered to", "అందినవాళ్ళు") + " (${got.size})\n"); got.forEach { sb.append("   " + it.optString("name") + "\n") }
        if (waiting.isNotEmpty()) { sb.append("\n✓ " + t("Not yet", "ఇంకా అందలేదు") + " (${waiting.size})\n"); waiting.forEach { sb.append("   " + it.optString("name") + "\n") } }
        if (!ChatTheme.receiptsOn(this)) sb.append("\n" + t("(Your read receipts are off, so you may not see others' either.)", "(మీ చదివిన గుర్తులు ఆఫ్‌లో ఉన్నాయి.)"))
        AlertDialog.Builder(this).setTitle(t("ℹ️ Message info", "ℹ️ మెసేజ్ వివరాలు")).setMessage(sb.toString()).setPositiveButton(t("Close", "మూసివేయి"), null).show()
    }
    /** 🛡️ Check a message for fraud: Habit's fraud checker (Tasks › 🛡️ Fake messages) opens with it. */
    private fun checkFraud(m: ChatMsg) {
        val text = m.body.optString("text").ifBlank { m.body.optJSONObject("lp")?.optString("u") ?: "" }
        if (text.isBlank()) return
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun retry(m: ChatMsg) {
        db.setSent(cid, m.id, 0, 0, 0); render()
        Thread { ChatSync.flushPending(this, store) }.start()
    }

    // =================== message kinds ===================
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
            if (m.body.optString("file").isNotEmpty()) ChatSync.fetchFile(this, store, m)
        }
        iv.clipToOutline = true; iv.background = ui.round(ChatTheme.LINE, 16)
        iv.setOnLongClickListener { conv()?.let { msgMenu(it, m) }; true }
        return iv
    }
    /** A view-once photo: opens one time, then it's gone from this phone (the sender sees "Opened"). */
    private fun onceView(m: ChatMsg): View {
        val mine = m.sender == me
        val opened = m.body.optBoolean("_opened")
        val label = when {
            mine -> "👁  " + t("View once photo", "ఒకసారి చూసే ఫోటో") + if (opened) " · " + t("Opened", "తెరిచారు") else ""
            opened -> "👁  " + t("Opened", "తెరిచారు")
            !fileReady(m) -> "⏳  " + t("View once photo · getting it…", "ఒకసారి చూసే ఫోటో · వస్తోంది…")
            else -> "👁  " + t("Photo · tap to view once", "ఫోటో · ఒకసారి చూడటానికి నొక్కండి")
        }
        if (!mine && !opened && !fileReady(m) && m.body.optString("file").isNotEmpty()) ChatSync.fetchFile(this, store, m)
        val col = if (!mine && !opened) ink.gold else ink.sub
        return ui.text("", 15f, col, bold = !mine && !opened).apply {
            text = if (label.startsWith("👁")) Ico.withIcon(context, R.drawable.ic_eye, col, dp(18), label.removePrefix("👁").trim()) else label
            setPadding(dp(2), dp(8), dp(8), dp(8)); minWidth = dp(200)
            if (!mine && !opened && fileReady(m)) setOnClickListener {
                val f = File(m.body.optString("local"))
                val d = Dialog(this@ChatActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                d.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                val iv = ImageView(this@ChatActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK); setImageBitmap(decodeScaled(f, resources.displayMetrics.widthPixels)); setOnClickListener { d.dismiss() } }
                d.setContentView(iv)
                d.setOnDismissListener { try { f.delete() } catch (e: Exception) { }; m.body.remove("local"); m.body.put("_opened", true); db.setBody(cid, m.id, m.body); ChatSync.opened(this@ChatActivity, store, m); render() }
                d.show()
            }
        }
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
    // stickers and GIFs
    private fun stickerRef(m: ChatMsg): String? = when {
        m.body.optString("pack").isNotEmpty() -> m.body.optString("pack") + ":" + m.body.optString("n")
        fileReady(m) -> "file:" + m.body.optString("local")
        else -> null
    }
    private fun stickerView(m: ChatMsg): View {
        val size = dp(150)
        val pack = m.body.optString("pack")
        val bmp = if (pack.isNotEmpty()) Stickers.draw(this, pack, m.body.optString("n"), 320)
            else if (fileReady(m)) decodeScaled(File(m.body.optString("local")), 320) else { if (m.body.optString("file").isNotEmpty()) ChatSync.fetchFile(this, store, m); null }
        if (bmp == null && pack.isNotEmpty()) return ui.text("🏷️ " + m.body.optString("alt").ifBlank { t("Sticker", "స్టిక్కర్") }, 15f, ChatTheme.TXT).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = ui.round(ChatTheme.CARD, 12) }
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = LinearLayout.LayoutParams(size, size)
            if (bmp != null) setImageBitmap(bmp) else setImageDrawable(null)
            contentDescription = m.body.optString("alt").ifBlank { t("Sticker", "స్టిక్కర్") }
            setOnLongClickListener { conv()?.let { msgMenu(it, m) }; true }
        }
    }
    private fun gifView(m: ChatMsg): View {
        val wPx = dp(220)
        val ratio = m.body.optInt("h", 3).toFloat() / maxOf(1, m.body.optInt("w", 4))
        val frame = FrameLayout(this)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(iv, FrameLayout.LayoutParams(wPx, (wPx * ratio.coerceIn(0.4f, 1.5f)).toInt()))
        if (fileReady(m)) AnimImg.show(iv, m.body.optString("local"))
        else {
            m.body.optString("thumb").takeIf { it.isNotEmpty() }?.let { try { val b = ChatCrypto.unb64(it); iv.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) } catch (e: Exception) { } }
            if (m.body.optString("file").isNotEmpty()) ChatSync.fetchFile(this, store, m)
        }
        frame.addView(ui.text("GIF", 11f, Color.WHITE, bold = true).apply { setPadding(dp(7), dp(1), dp(7), dp(2)); background = ui.round(Color.parseColor("#99000000"), 8); setLineSpacing(0f, 1f) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(8)) })
        frame.clipToOutline = true; frame.background = ui.round(ChatTheme.LINE, 16)
        frame.setOnLongClickListener { conv()?.let { msgMenu(it, m) }; true }
        return frame
    }
    /** A link's preview: picture, title, words and the site (in a message, or above the typing bar). */
    private fun previewCard(lp: JSONObject, inBubble: Boolean): View = LinearLayout(this).apply {
        val k = ink
        orientation = LinearLayout.VERTICAL; background = if (inBubble) ui.round(k.inner, 14) else ChatTheme.round(ChatTheme.CARD, dp(14).toFloat(), ChatTheme.EDGE, dp(1)); clipToOutline = true
        lp.optString("img").takeIf { it.isNotEmpty() }?.let { s ->
            try {
                val b = ChatCrypto.unb64(s); val bm = BitmapFactory.decodeByteArray(b, 0, b.size)
                val w = dp(240); val h = (w * bm.height.toFloat() / maxOf(1, bm.width)).toInt().coerceIn(dp(70), dp(170))
                if (inBubble) addView(ImageView(this@ChatActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bm) }, LinearLayout.LayoutParams(-1, h))
            } catch (e: Exception) { }
        }
        val words = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(7), dp(10), dp(8)) }
        if (lp.optString("t").isNotBlank()) words.addView(ui.text(lp.optString("t"), 14.5f, k.txt, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (lp.optString("d").isNotBlank()) words.addView(ui.text(lp.optString("d"), 13f, k.sub).apply { maxLines = if (inBubble) 3 else 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        words.addView(ui.text("", 12f, k.gold, bold = true).apply { text = Ico.withIcon(context, R.drawable.ic_link, k.gold, dp(13), lp.optString("s")) })
        addView(words)
        layoutParams = LinearLayout.LayoutParams(if (inBubble) dp(250) else -1, -2).apply { bottomMargin = dp(5) }
        if (inBubble) setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lp.optString("u")))) } catch (e: Exception) { } }
    }
    // polls
    private fun pollView(c: ChatConv, m: ChatMsg): View {
        val b = m.body
        val opts = b.optJSONArray("o") ?: JSONArray()
        val votes = b.optJSONObject("_votes") ?: JSONObject()
        val counts = IntArray(opts.length())
        val myChoices = HashSet<Int>()
        votes.keys().forEach { u -> votes.optJSONObject(u)?.optJSONArray("ch")?.let { ch -> for (i in 0 until ch.length()) { val k = ch.optInt(i, -1); if (k in counts.indices) { counts[k]++; if (u == me) myChoices.add(k) } } } }
        val voters = votes.length()
        val top = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val k = ink
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumWidth = dp(240) }
        box.addView(ui.text("", 16f, k.txt, bold = true).apply { text = Ico.withIcon(context, R.drawable.ic_poll, k.gold, dp(17), b.optString("q")) })
        box.addView(ui.text(if (b.optBoolean("multi")) t("Select one or more", "ఒకటి లేదా ఎక్కువ ఎంచుకోండి") else t("Select one", "ఒకటి ఎంచుకోండి"), 12.5f, k.sub).apply { setPadding(0, dp(2), 0, dp(6)) })
        val canVote = c.member && (c.accepted || c.isLocal)
        for (i in 0 until opts.length()) {
            val picked = i in myChoices
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(choiceMark(picked, b.optBoolean("multi"), k), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
                addView(ui.text(opts.optString(i), 15f, k.txt), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.text(counts[i].toString(), 14f, k.sub, bold = true))
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; background = ui.round(k.button, 4)
                addView(View(this@ChatActivity).apply { background = ui.round(k.accent, 4) }, LinearLayout.LayoutParams(0, -1, counts[i].toFloat() / top))
                addView(View(this@ChatActivity), LinearLayout.LayoutParams(0, -1, 1f - counts[i].toFloat() / top + 0.0001f))
            }, LinearLayout.LayoutParams(-1, dp(5)).apply { marginStart = dp(30); topMargin = dp(4) })
            if (canVote) row.setOnClickListener {
                val now = HashSet(myChoices)
                if (b.optBoolean("multi")) { if (!now.add(i)) now.remove(i) } else { if (now.contains(i)) now.clear() else { now.clear(); now.add(i) } }
                if (c.isLocal) { val v = b.optJSONObject("_votes") ?: JSONObject(); if (now.isEmpty()) v.remove(me) else v.put(me, JSONObject().put("ch", JSONArray(now.toList())).put("at", System.currentTimeMillis())); b.put("_votes", v); db.setBody(cid, m.id, b) }
                else ChatSync.vote(this, store, m, now.toList().sorted())
                render()
            }
            box.addView(row)
        }
        box.addView(ui.text(t("$voters voted · View votes", "$voters మంది ఓటు వేశారు · ఓట్లు చూడండి"), 14f, k.gold, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(2))
            setOnClickListener {
                val sb = StringBuilder()
                for (i in 0 until opts.length()) {
                    sb.append(opts.optString(i)).append(" — ").append(counts[i]).append('\n')
                    votes.keys().forEach { u -> val ch = votes.optJSONObject(u)?.optJSONArray("ch"); if (ch != null && (0 until ch.length()).any { ch.optInt(it) == i }) sb.append("    • ").append(if (u == me) t("You", "మీరు") else c.nameOf(u)).append('\n') }
                    sb.append('\n')
                }
                AlertDialog.Builder(this@ChatActivity).setTitle("📊 " + b.optString("q")).setMessage(sb.toString().trim()).setPositiveButton(t("Close", "మూసివేయి"), null).show()
            }
        })
        return box
    }
    /** A poll's round (one answer) or square (several) tick box. */
    private fun choiceMark(on: Boolean, multi: Boolean, k: ChatTheme.Ink): View = FrameLayout(this).apply {
        val r = if (multi) dp(6).toFloat() else dp(11).toFloat()
        background = if (on) ChatTheme.round(k.accent, r) else ChatTheme.round(Color.TRANSPARENT, r, k.sub, dp(2))
        if (on) addView(smallIcon(R.drawable.ic_tick, k.onAccent), FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
    }
    // events
    private fun eventView(c: ChatConv, m: ChatMsg): View {
        val b = m.body
        val rs = b.optJSONObject("_rsvp") ?: JSONObject()
        var y = 0; var mb = 0; var n = 0; var mine = ""
        rs.keys().forEach { u -> when (rs.optJSONObject(u)?.optString("a")) { "y" -> y++; "m" -> mb++; "n" -> n++ }; if (u == me) mine = rs.optJSONObject(u)?.optString("a") ?: "" }
        val ik = ink
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumWidth = dp(240) }
        box.addView(capsLabel(R.drawable.ic_calendar, t("EVENT", "కార్యక్రమం"), ik.gold))
        box.addView(ui.text(b.optString("title"), 17f, ik.txt, bold = true).apply { setPadding(0, dp(4), 0, dp(2)) })
        val at = b.optLong("at")
        if (at > 0) box.addView(ui.text("", 14f, ik.txt).apply { text = Ico.withIcon(context, R.drawable.ic_clock, ik.sub, dp(15), Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a", Locale.ENGLISH)) + if (at < System.currentTimeMillis()) "  (" + t("over", "అయిపోయింది") + ")" else "") })
        if (b.optString("place").isNotBlank()) box.addView(ui.text("", 14f, ik.txt).apply { text = Ico.withIcon(context, R.drawable.ic_map_pin, ik.sub, dp(15), b.optString("place")) })
        if (b.optString("note").isNotBlank()) box.addView(ui.text(b.optString("note"), 14f, ik.sub).apply { setPadding(0, dp(4), 0, 0) })
        box.addView(ui.text("✅ $y " + t("going", "వస్తున్నారు") + "   🤔 $mb " + t("maybe", "చూద్దాం") + "   ❌ $n " + t("can't", "రాలేరు"), 13f, ik.sub).apply { setPadding(0, dp(6), 0, dp(4)) })
        if (c.member && !c.isLocal) box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("y" to t("Going", "వస్తాను"), "m" to t("Maybe", "చూద్దాం"), "n" to t("Can't go", "రాలేను")).forEach { (k, l) ->
                addView(chipButton(l, primary = mine == k) { ChatSync.rsvp(this@ChatActivity, store, m, if (mine == k) "" else k); render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
        })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0)
            addView(chipButton("📆 " + t("Calendar", "క్యాలెండర్")) { addToCalendar(m) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            if (at > System.currentTimeMillis()) addView(chipButton("⏰ " + t("Remind me", "గుర్తు చేయి")) {
                try { Reminders.add(this@ChatActivity, at - 30 * 60_000L, "📅 " + b.optString("title")); toast(t("I'll remind you 30 minutes before ⏰", "30 నిమిషాల ముందు గుర్తు చేస్తాను ⏰")) } catch (e: Exception) { }
            })
        })
        return box
    }
    private fun addToCalendar(m: ChatMsg) {
        val b = m.body
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, b.optString("title"))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, b.optString("place")).putExtra(CalendarContract.Events.DESCRIPTION, b.optString("note"))
        if (b.optLong("at") > 0) i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, b.optLong("at")).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, b.optLong("at") + 3600_000L)
        try { startActivity(i) } catch (e: Exception) { toast(t("No calendar app on this phone.", "ఈ ఫోన్‌లో క్యాలెండర్ యాప్ లేదు.")) }
    }
    // locations and contact cards
    private fun locView(m: ChatMsg): View {
        val b = m.body
        val lat = b.optDouble("lat"); val lng = b.optDouble("lng")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; minimumWidth = dp(230)
            val k = ink
            addView(FrameLayout(this@ChatActivity).apply {
                background = ChatTheme.WallDrawable(ChatTheme.wall(2), resources.displayMetrics.density)
                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) } }
                clipToOutline = true
                addView(smallIcon(R.drawable.ic_map_pin, ChatTheme.GOLD_TXT), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, dp(110)))
            addView(ui.text(b.optString("name").ifBlank { t("Location", "లొకేషన్") }, 15f, k.txt, bold = true).apply { setPadding(0, dp(6), 0, 0) })
            addView(ui.text("%.5f, %.5f".format(Locale.ENGLISH, lat, lng) + (if (b.optInt("acc") > 0) " · ±${b.optInt("acc")} m" else ""), 12.5f, k.sub))
            addView(ui.text("", 14f, k.gold, bold = true).apply { text = Ico.withIcon(context, R.drawable.ic_map_pin, k.gold, dp(15), t("Open in Maps", "మ్యాప్స్‌లో తెరవండి")); setPadding(0, dp(6), 0, dp(2)) })
            setOnClickListener {
                val label = Uri.encode(b.optString("name").ifBlank { "Location" })
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)"))) }
                catch (e: Exception) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng"))) } catch (e2: Exception) { } }
            }
        }
    }
    private fun contactView(m: ChatMsg): View {
        val b = m.body
        val phones = b.optJSONArray("phones") ?: JSONArray()
        val first = if (phones.length() > 0) phones.optString(0) else ""
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; minimumWidth = dp(230)
            addView(LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val (bg, fg) = ChatTheme.tint(b.optString("name"))
                addView(ui.text(ChatDraw.initials(b.optString("name")).ifEmpty { "?" }, 17f, fg, bold = true).apply { gravity = Gravity.CENTER; background = ChatTheme.circle(bg); typeface = Fonts.heavy(context) }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                    addView(ui.text(b.optString("name"), 15.5f, ink.txt, bold = true))
                    for (i in 0 until phones.length()) addView(ui.text(phones.optString(i), 13f, ink.sub))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            if (first.isNotEmpty()) addView(LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
                addView(chipButton("📞 " + t("Call", "కాల్")) { ChatContacts.call(this@ChatActivity, ChatContacts.Person(b.optString("name"), first)) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                addView(chipButton("💾 " + t("Save", "సేవ్")) {
                    val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.NAME, b.optString("name")).putExtra(ContactsContract.Intents.Insert.PHONE, first)
                    try { startActivity(i) } catch (e: Exception) { }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                addView(chipButton("💬 " + t("Invite", "ఆహ్వానించు")) { ChatContacts.invite(this@ChatActivity, store, ChatContacts.Person(b.optString("name"), first)) })
            })
        }
    }

    // voice notes
    /** A voice note: the round play button, its sound bars (the played part darker) and its length, then the time and ticks. */
    private fun voiceView(m: ChatMsg, foot: View): View {
        val k = ink
        val local = m.body.optString("local")
        val ready = local.isNotEmpty() && File(local).exists()
        val secs = m.body.optInt("dur")
        val playing = playingId == m.id
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumWidth = dp(232) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val res = when { playing -> R.drawable.ic_pause; ready -> R.drawable.ic_play; ChatSync.isFetching(m) -> R.drawable.ic_clock; else -> R.drawable.ic_download }
        val btn = Ico.view(this, res, k.onAccent, 18, 40, if (playing) t("Pause", "ఆపు") else t("Play the voice note", "వాయిస్ మెసేజ్ వినండి"), ChatTheme.circle(k.accent)) {
            if (!ready) { ChatSync.fetchFile(this, store, m); render(); return@view }
            if (playingId == m.id) stopPlaying() else play(m.id, local)
        }
        row.addView(btn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val wave = Wave(this, m.id, k.accent, k.waveRest).apply { progress = if (playing) playProgress() else 0f }
        if (playing) playingWave = wave
        row.addView(wave, LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginStart = dp(10); marginEnd = dp(4) })
        box.addView(row)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(50), 0, 0, 0) }
        line.addView(ui.text(if (secs > 0) "%d:%02d".format(secs / 60, secs % 60) else if (ready) "" else t("Loading…", "వస్తోంది…"), 11f, k.sub, bold = true).apply { setLineSpacing(0f, 1f) }, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(foot)
        box.addView(line)
        if (!ready && m.body.optString("file").isNotEmpty()) ChatSync.fetchFile(this, store, m)
        return box
    }
    private var playingWave: Wave? = null
    private fun playProgress(): Float = try { player?.let { if (it.duration > 0) it.currentPosition.toFloat() / it.duration else 0f } ?: 0f } catch (e: Exception) { 0f }
    private val playTick = object : Runnable {
        override fun run() { if (player == null) return; playingWave?.progress = playProgress(); handler.postDelayed(this, 100) }
    }
    private fun play(id: String, path: String) {
        stopPlaying()
        try {
            player = MediaPlayer().apply { setDataSource(path); setOnCompletionListener { stopPlaying() }; prepare(); start() }
            playingId = id; render(); handler.post(playTick)
        } catch (e: Exception) { toast(t("Couldn't play it.", "ప్లే చేయలేకపోయాం.")) }
    }
    private fun stopPlaying() {
        handler.removeCallbacks(playTick); playingWave = null
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        if (playingId.isNotEmpty()) { playingId = ""; if (cid.isNotEmpty()) render() }
    }

    // videos and documents
    private fun fileReady(m: ChatMsg) = m.body.optString("local").let { it.isNotEmpty() && File(it).exists() }
    private fun sizeText(n: Long) = when { n >= 1_000_000 -> "%.1f MB".format(Locale.ENGLISH, n / 1_000_000.0); n >= 1000 -> "${n / 1000} KB"; else -> "$n B" }
    private fun videoView(m: ChatMsg): View {
        val wPx = dp(220)
        val ratio = m.body.optInt("h", 9).toFloat() / maxOf(1, m.body.optInt("w", 16))
        val frame = FrameLayout(this)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#1B2242")) }
        m.body.optString("thumb").takeIf { it.isNotEmpty() }?.let { try { val b = ChatCrypto.unb64(it); iv.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) } catch (e: Exception) { } }
        frame.addView(iv, FrameLayout.LayoutParams(wPx, (wPx * ratio.coerceIn(0.5f, 1.4f)).toInt()))
        val ready = fileReady(m)
        val secs = m.body.optInt("dur")
        val res = if (ready) R.drawable.ic_play else if (m.body.optString("file").isNotEmpty() && ChatSync.isFetching(m)) R.drawable.ic_clock else R.drawable.ic_download
        frame.addView(Ico.view(this, res, Color.WHITE, 24, 54, "", ChatTheme.circle(Color.parseColor("#80000000"))), FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        frame.addView(ui.text("", 12f, Color.WHITE, bold = true).apply {
            text = Ico.withIcon(context, R.drawable.ic_video, Color.WHITE, dp(14), (if (secs > 0) "%d:%02d".format(secs / 60, secs % 60) + " · " else "") + sizeText(m.body.optLong("size")))
            setPadding(dp(10), dp(2), dp(10), dp(6)); setShadowLayer(4f, 0f, 1f, Color.BLACK) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        frame.clipToOutline = true; frame.background = ui.round(ChatTheme.LINE, 16)
        frame.setOnClickListener { if (fileReady(m)) openFile(m, false) else { ChatSync.fetchFile(this, store, m); toast(t("Downloading…", "డౌన్‌లోడ్ అవుతోంది…")); render() } }
        frame.setOnLongClickListener { conv()?.let { msgMenu(it, m) }; true }
        return frame
    }
    private fun docView(m: ChatMsg): View {
        val ready = fileReady(m)
        val name = m.body.optString("name").ifBlank { t("Document", "ఫైల్") }
        val ext = name.substringAfterLast('.', "").uppercase().take(5)
        val audio = m.body.optString("mime").startsWith("audio")
        val k = ink
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(7), dp(7), dp(8), dp(7))
            background = ui.round(k.inner, 14); minimumWidth = dp(232)
            val (tile, word) = fileTile(ext, audio)
            addView(ui.text(word, 10.5f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Fonts.heavy(context); background = ui.round(tile, 10); setLineSpacing(0f, 1f) }, LinearLayout.LayoutParams(dp(40), dp(48)))
            addView(LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0)
                addView(ui.text(name, 14f, k.txt, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                addView(ui.text(listOf(ext, sizeText(m.body.optLong("size"))).filter { it.isNotBlank() }.joinToString(" · "), 12f, k.sub))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val res = when { !ready && ChatSync.isFetching(m) -> R.drawable.ic_clock; !ready -> R.drawable.ic_download; audio && playingId == m.id -> R.drawable.ic_pause; audio -> R.drawable.ic_play; else -> R.drawable.ic_chevron_right }
            addView(Ico.view(context, res, k.txt, 18, 36, "", ChatTheme.circle(k.button)), LinearLayout.LayoutParams(dp(36), dp(36)))
            setOnClickListener {
                if (!fileReady(m)) { ChatSync.fetchFile(this@ChatActivity, store, m); toast(t("Downloading…", "డౌన్‌లోడ్ అవుతోంది…")); render() }
                else if (audio) { if (playingId == m.id) stopPlaying() else play(m.id, m.body.optString("local")) }
                else openFile(m, false)
            }
            setOnLongClickListener { conv()?.let { msgMenu(it, m) }; true }
        }
    }
    /** The coloured tile of a file: its type in short (PDF red, Word blue, Excel green…). */
    private fun fileTile(ext: String, audio: Boolean): Pair<Int, String> = when {
        audio -> Color.parseColor("#8E5BE8") to ext.ifBlank { "AUDIO" }.take(4)
        ext == "PDF" -> Color.parseColor(if (ChatTheme.dark) "#E5484D" else "#D93A3F") to "PDF"
        ext in setOf("DOC", "DOCX", "TXT", "RTF", "ODT") -> Color.parseColor("#2F6FED") to ext.take(4)
        ext in setOf("XLS", "XLSX", "CSV", "ODS") -> Color.parseColor("#1E9E5A") to ext.take(4)
        ext in setOf("PPT", "PPTX", "ODP") -> Color.parseColor("#E8792B") to ext.take(4)
        ext in setOf("ZIP", "RAR", "7Z") -> Color.parseColor("#6B7280") to ext.take(4)
        else -> Color.parseColor("#5B6B8C") to ext.ifBlank { "FILE" }.take(4)
    }
    /** Opens a video or document in an app on the phone ([share]: share or save it instead). */
    private fun openFile(m: ChatMsg, share: Boolean) {
        val src = File(m.body.optString("local"))
        if (!src.exists()) return
        val name = (if (m.kind == "doc") m.body.optString("name") else "Habit-" + m.kind + "-" + m.id + "." + src.extension).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "file" }
        val lower = name.lowercase()
        if (lower.endsWith(".apk") || lower.endsWith(".exe") || lower.endsWith(".bat")) { toast(t("For safety, app files can't be opened from Habit Chat.", "భద్రత కోసం, యాప్ ఫైల్స్ Habit చాట్ నుంచి తెరవలేము.")); return }
        try { src.copyTo(File(ShareFiles.dir(this), name), overwrite = true) } catch (e: Exception) { toast(t("Couldn't open it.", "తెరవలేకపోయాం.")); return }
        val uri = ShareFiles.uri(this, name)
        val mime = contentResolver.getType(uri) ?: "*/*"
        val i = if (share) Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("Share or save", "షేర్ లేదా సేవ్"))
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) { toast(t("No app on this phone can open this file.", "ఈ ఫైల్ తెరిచే యాప్ ఫోన్‌లో లేదు.")) }
    }
    private fun callLogView(m: ChatMsg): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val missed = m.body.optString("st") == "missed" && m.sender != me
        val k = ink
        val video = m.body.optBoolean("video")
        addView(Ico.view(this@ChatActivity, if (video) R.drawable.ic_video else R.drawable.ic_phone, if (missed) ChatTheme.DANGER else k.txt, 18, 34, "", ChatTheme.circle(k.button)),
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        addView(ui.text(CallManager.logText(this@ChatActivity, m), 15f, if (missed) ChatTheme.DANGER else k.txt, bold = missed), LinearLayout.LayoutParams(-2, -2))
        if (conv()?.isDm == true) addView(ui.text("   " + t("Call back", "మళ్ళీ కాల్"), 13.5f, k.gold, bold = true).apply {
            setOnClickListener { startCall(m.body.optBoolean("video")) } })
    }

    // calls (one-to-one): the microphone (and camera) first, then the call screen
    private var callVideo = false
    private fun startCall(video: Boolean) {
        val c = conv() ?: return
        if (!c.isDm) { toast(t("Group calls come later. For now, call one person.", "గ్రూప్ కాల్స్ తర్వాత వస్తాయి. ఇప్పటికి ఒక్కరికి కాల్ చేయండి.")); return }
        if (!c.accepted || c.blocked || !c.member) return
        callVideo = video
        val need = (if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), REQ_CALL_PERMS); return }
        CallManager.startOutgoing(this, cid, video)
    }

    // replies: the quoted message above the typing bar, and inside the bubble
    private fun quoteView(c: ChatConv, re: JSONObject, inBubble: Boolean): View = LinearLayout(this).apply {
        val k = ink
        orientation = LinearLayout.HORIZONTAL
        val who = re.optString("who")
        val onMine = inBubble && inkMine
        val col = if (onMine) k.accent else if (who == me) ChatTheme.GOLD_TXT else nameColor(who)
        addView(View(this@ChatActivity).apply { setBackgroundColor(col) }, LinearLayout.LayoutParams(dp(4), -1))
        addView(LinearLayout(this@ChatActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(5), dp(9), dp(6))
            addView(ui.text(if (who == me) t("You", "మీరు") else c.nameOf(who), 12.5f, if (onMine) k.txt else col).apply { typeface = Fonts.heavy(context); setLineSpacing(0f, 1f) })
            addView(ui.text(re.optString("p"), 13f, k.sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(-2, -2))
        background = if (inBubble) ui.round(k.inner, 12) else ui.round(ChatTheme.CARD2, 12)
        clipToOutline = true
        if (inBubble) {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) }
            setOnClickListener { goTo(re.optString("id")) }
        }
    }
    private fun goTo(id: String) {
        if (id.isEmpty()) return
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v.tag == id) {
                scroll.post { scroll.smoothScrollTo(0, maxOf(0, v.top - dp(80))) }
                v.setBackgroundColor(ChatTheme.PILL); v.postDelayed({ (v as? PickRow)?.paint() ?: v.setBackgroundColor(Color.TRANSPARENT) }, 1400)
                return
            }
        }
        // not drawn yet: an older message, show more
        if (db.msg(cid, id) != null && showLimit < db.count(cid) + 10) { showLimit = db.count(cid) + 10; pendingGoto = id; lastCount = -2; render(); return }
        toast(t("That message isn't on this phone any more.", "ఆ మెసేజ్ ఈ ఫోన్‌లో లేదు."))
    }
    private fun renderReplyBar() {
        replyBar.removeAllViews()
        val r = replyTo ?: return
        val c = conv() ?: return
        replyBar.setPadding(dp(8), dp(6), dp(8), 0)
        replyBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(2), dp(6))
            background = ChatTheme.round(ChatTheme.CARD, dp(18).toFloat(), ChatTheme.EDGE, dp(1))
            addView(quoteView(c, reOf(r), false), LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconBtn(R.drawable.ic_close, t("Don't reply", "జవాబు వద్దు"), ChatTheme.SUB, 18, 40) { replyTo = null; renderReplyBar() })
        })
    }
    private fun reOf(m: ChatMsg) = JSONObject().put("id", m.id).put("who", m.sender).put("p", ChatSync.preview(this, m).take(90))
    /** Adds "replying to" (and @mentions) to a message body (and forgets them once used). */
    private fun withReply(body: JSONObject): JSONObject {
        replyTo?.let { body.put("re", reOf(it)) }
        if (replyTo != null) { replyTo = null; renderReplyBar() }
        return body
    }

    // forwarding: a copy goes to other chats (files are locked again with new keys)
    private fun forward(m: ChatMsg) = forward(listOf(m))
    private fun forward(msgs: List<ChatMsg>) {
        val waiting = msgs.filter { ChatSync.hasFile(it) && !fileReady(it) }
        if (waiting.isNotEmpty()) { waiting.forEach { ChatSync.fetchFile(this, store, it) }; toast(t("Getting it first… try again in a moment.", "ముందు డౌన్‌లోడ్ చేస్తున్నాం… కాసేపట్లో మళ్ళీ ప్రయత్నించండి.")); return }
        val targets = db.convs().filter { (it.accepted || it.isLocal) && it.member && !it.blocked && !it.isAi && it.canSend(me) }.sortedByDescending { it.last }
        if (targets.isEmpty() || msgs.isEmpty()) return
        val ticked = BooleanArray(targets.size)
        AlertDialog.Builder(this).setTitle(if (msgs.size == 1) t("↪️ Forward to…", "↪️ ఎవరికి ఫార్వర్డ్ చేయాలి?") else t("↪️ Forward ${msgs.size} messages to…", "↪️ ${msgs.size} మెసేజ్‌లు ఎవరికి ఫార్వర్డ్ చేయాలి?"))
            .setMultiChoiceItems(targets.map { "${it.emoji}  ${it.name}" }.toTypedArray(), ticked) { _, i, on -> ticked[i] = on }
            .setPositiveButton(t("Send", "పంపు")) { _, _ ->
                val chosen = targets.filterIndexed { i, _ -> ticked[i] }.take(5)
                if (chosen.isEmpty()) return@setPositiveButton
                for (m in msgs.sortedBy { it.at }) {
                    val bytes = if (ChatSync.hasFile(m)) try { File(m.body.optString("local")).readBytes() } catch (e: Exception) { null } else null
                    for (c in chosen) {
                        val body = ChatSync.cleanBody(m.body).apply { for (k in listOf("file", "key", "iv", "re", "mn")) remove(k); put("fwd", true) }
                        if (ChatSync.hasFile(m)) { if (bytes != null) ChatSync.sendFile(this, store, c.cid, m.kind, bytes, body) { e -> if (e != null && !e.startsWith("No connection")) toast(e) } }
                        else ChatSync.send(this, store, c.cid, body) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
                    }
                }
                toast(t("Forwarded ✓", "ఫార్వర్డ్ చేశాం ✓"))
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    // =================== tasks and reminders ===================
    private fun taskTitle(tid: String) = db.task(cid, tid)?.optString("title")?.ifBlank { null } ?: t("a task", "ఒక పని")
    /** A task message in the timeline: the card when it was given, a short line for remarks and changes. */
    private fun taskInline(c: ChatConv, m: ChatMsg): View {
        val b = m.body; val tid = b.optString("tid")
        val who = if (m.sender == me || c.isSelf) t("You", "మీరు") else c.nameOf(m.sender)
        return when (b.optString("action")) {
            "new" -> taskCard(c, db.task(cid, tid) ?: JSONObject().put("tid", tid).put("title", b.optString("title")).put("state", "open"), true)
            "remark" -> ui.text("💬 $who " + t("on", "") + " \"${taskTitle(tid)}\": ${b.optString("text")}", 15f, ink.txt)
            "done" -> ui.text("✔ $who " + t("marked \"${taskTitle(tid)}\" done", "\"${taskTitle(tid)}\" పని పూర్తయిందని చెప్పారు") + b.optString("text").let { if (it.isBlank()) "" else ": $it" }, 15f, ChatTheme.GOOD, bold = true)
            "close" -> ui.text("✅ $who " + t("closed \"${taskTitle(tid)}\"", "\"${taskTitle(tid)}\" మూసివేశారు"), 15f, ChatTheme.GOOD, bold = true)
            else -> ui.text("↩ $who " + t("reopened \"${taskTitle(tid)}\"", "\"${taskTitle(tid)}\" మళ్ళీ తెరిచారు") + b.optString("text").let { if (it.isBlank()) "" else ": $it" }, 15f, ink.gold, bold = true)
        }
    }

    /** A task with its state and the buttons this person may use. */
    private fun taskCard(c: ChatConv, task: JSONObject, full: Boolean): View {
        val k = ink
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val state = task.optString("state", "open")
        val to = task.optString("to"); val by = task.optString("by")
        val reminder = c.isSelf || to.isEmpty()
        val word = (if (reminder) t("REMINDER", "రిమైండర్") else t("TASK", "పని")) + " · " + when (state) {
            "done" -> t("DONE, WAITING FOR THE ADMIN", "పూర్తయింది, అడ్మిన్ కోసం"); "closed" -> t("CLOSED", "మూసివేశారు"); else -> t("OPEN", "తెరిచి ఉంది") }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(capsLabel(if (reminder) R.drawable.ic_clock else R.drawable.ic_tasks, word, if (state == "open") k.gold else ChatTheme.GOOD), LinearLayout.LayoutParams(0, -2, 1f))
        if (by.isNotEmpty() && by != me && !c.isSelf) head.addView(ui.text(t("from ", "") + c.nameOf(by).substringBefore(' ') + t("", " నుంచి"), 12f, k.sub).apply { maxLines = 1 })
        box.addView(head)
        box.addView(ui.text(task.optString("title"), 16f, k.txt, bold = true).apply { setPadding(0, dp(6), 0, 0); if (state == "closed") paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
        if (task.optString("note").isNotBlank()) box.addView(ui.text(task.optString("note"), 14.5f, k.txt).apply { setPadding(0, dp(2), 0, 0) })
        val facts = SpannableStringBuilder()
        val iconPx = dp(14)
        if (to.isNotEmpty()) facts.append(Ico.withIcon(this, R.drawable.ic_person, k.sub, iconPx, if (to == me) t("For you", "మీ కోసం") else c.nameOf(to)))
        task.optLong("due").takeIf { it > 0 }?.let {
            if (facts.isNotEmpty()) facts.append("     ")
            facts.append(Ico.withIcon(this, R.drawable.ic_clock, k.sub, iconPx, Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)) + if (it < System.currentTimeMillis() && state == "open") " ⚠️" else ""))
        }
        if (facts.isNotEmpty()) box.addView(ui.text("", 12.5f, if (inkMine) k.sub else ChatTheme.TXT2).apply { text = facts; setPadding(0, dp(4), 0, 0) })
        if (full) task.optJSONArray("remarks")?.let { r -> for (i in maxOf(0, r.length() - 3) until r.length()) r.optJSONObject(i)?.let { x ->
            box.addView(ui.text("💬 " + (if (x.optString("by") == me) t("You", "మీరు") else c.nameOf(x.optString("by"))) + ": " + x.optString("text"), 13.5f, k.txt).apply { setPadding(0, dp(3), 0, 0) }) } }
        // what this person can do
        val admin = c.isSelf || c.isAdmin(me)
        val btns = ArrayList<View>()
        val tid = task.optString("tid")
        if (state != "closed" && c.member) {
            val canDone = state == "open" && !reminder && (to == me || by == me || admin)
            if (canDone) btns.add(chipButton(t("Mark done", "పూర్తయింది"), primary = true) { markDone(tid) })
            if (!c.isSelf) btns.add(chipButton(t("Add remark", "రిమార్క్ రాయండి")) { remark(tid) })
            if (admin) btns.add(chipButton(t(if (reminder) "Tick" else "Close", if (reminder) "టిక్" else "మూసివేయి"), if (canDone || !c.isSelf) ChatTheme.GOOD else null, primary = !canDone && c.isSelf) { closeTask(tid) })
        } else if (state == "closed" && admin && c.member) btns.add(chipButton(t("Reopen", "మళ్ళీ తెరువు")) { reopen(tid) })
        if (btns.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(2)) }
            btns.forEach { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        return box
    }
    /** A small pill button: [primary] filled (gold on navy; navy inside my gold bubble), else an outline in [color]. */
    private fun chipButton(label: String, color: Int? = null, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        val k = ink
        text = label; textSize = 13.5f; typeface = Fonts.heavy(context); gravity = Gravity.CENTER; includeFontPadding = false
        setPadding(dp(16), dp(11), dp(16), dp(11))
        if (primary) { setTextColor(k.onBtn); background = ui.round(k.btn, 20) }
        else { setTextColor(color ?: k.txt); background = ChatTheme.round(Color.TRANSPARENT, dp(20).toFloat(), k.outline, dp(1)) }
        setOnClickListener { onClick() }
    }
    /** "TASK · OPEN": small capital words with an icon. */
    private fun capsLabel(res: Int, words: String, color: Int) = ui.text("", 11.5f, color).apply {
        text = Ico.withIcon(context, res, color, dp(15), words); typeface = Fonts.heavy(context); letterSpacing = 0.05f; setLineSpacing(0f, 1f); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /**
     * The bar under the top bar: the pinned message (tap: go to it; hold: unpin) and "2 tasks" (tap: the open tasks).
     * With no pinned message, open tasks show as a small pill next to the name (or as this bar, if chosen in Chat settings).
     */
    private fun renderPinned(c: ChatConv) {
        pinned.removeAllViews()
        val open = db.tasks(cid)
        val p = ChatSync.pinned(this, cid)
        val showBar = p != null || (open.isNotEmpty() && (pinnedOpen || ChatTheme.tasksBar(this)))
        tasksChip.visibility = if (open.isNotEmpty() && !showBar) View.VISIBLE else View.GONE
        if (open.isNotEmpty()) setTasksPill(tasksChip, open.size)
        if (!showBar) return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(7), dp(12), dp(7)); minimumHeight = dp(54); setBackgroundColor(ChatTheme.PIN_BG) }
        bar.addView(smallIcon(if (p != null) R.drawable.ic_pin else R.drawable.ic_tasks, ChatTheme.GOLD_TXT), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (p != null) {
            words.addView(ui.text(ChatSync.preview(this, p), 13.5f, ChatTheme.TXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1f) })
            words.addView(ui.text(t("Pinned", "పిన్ చేసింది") + " · " + (if (p.sender == me) t("You", "మీరు") else c.nameOf(p.sender)), 12f, ChatTheme.PIN_SUB).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
            bar.setOnClickListener { goTo(p.id) }
            val canUnpin = !c.isLocal && c.member && !(c.isGroup && c.adminsEdit && !c.isAdmin(me))
            if (canUnpin) bar.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage(t("Unpin this message?", "ఈ మెసేజ్ పిన్ తీసేయాలా?"))
                    .setPositiveButton(t("Unpin", "పిన్ తీసేయి")) { _, _ -> ChatSync.pin(this, store, p, false, 0); render() }.setNegativeButton(t("Cancel", "వద్దు"), null).show(); true
            }
        } else {
            val mineCount = open.count { it.optString("to") == me && it.optString("state") == "open" }
            words.addView(ui.text(open.joinToString(" · ") { it.optString("title") }, 13.5f, ChatTheme.TXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1f) })
            words.addView(ui.text(t("${open.size} open", "${open.size} తెరిచి ఉన్నాయి") + (if (mineCount > 0) t(" · $mineCount for you", " · మీకు $mineCount") else ""), 12f, ChatTheme.PIN_SUB).apply { setPadding(0, dp(2), 0, 0) })
            bar.setOnClickListener { pinnedOpen = !pinnedOpen; renderPinned(c) }
        }
        bar.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        if (open.isNotEmpty()) bar.addView(tasksPill().apply { setTasksPill(this, open.size); setOnClickListener { pinnedOpen = !pinnedOpen; renderPinned(c) } },
            LinearLayout.LayoutParams(-2, dp(28)).apply { marginStart = dp(10) })
        pinned.addView(bar)
        pinned.addView(View(this).apply { setBackgroundColor(ChatTheme.PIN_LINE) }, LinearLayout.LayoutParams(-1, 1))
        if (pinnedOpen && open.isNotEmpty()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(10)); setBackgroundColor(ChatTheme.CHAT_BG) }
            for (task in open) box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(10)); background = ChatTheme.round(ChatTheme.CARD, dp(20).toFloat(), ChatTheme.EDGE, dp(1))
                addView(taskCard(c, task, true))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val sv = ScrollView(this).apply { addView(box) }
            pinned.addView(sv, LinearLayout.LayoutParams(-1, minOf(dp(340), dp(176) * open.size)))
            pinned.addView(View(this).apply { setBackgroundColor(ChatTheme.DIVIDER) }, LinearLayout.LayoutParams(-1, 1))
        }
    }
    /** (The pinned message is in [renderPinned]'s bar now.) */
    private fun renderPinMsg(c: ChatConv) { pinMsgBar.removeAllViews() }

    private fun newTask() {
        val c = conv() ?: return
        if (!c.member) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(0)) }
        val dl = ChatTheme.dlg(this)
        val titleIn = ChatTheme.input(this, t("What needs doing? e.g. Submit the survey report", "ఏం చేయాలి? ఉదా: సర్వే రిపోర్ట్ ఇవ్వాలి"))
        val noteIn = ChatTheme.input(this, t("Details (optional)", "వివరాలు (కావాలంటే)"), multi = true)
        box.addView(titleIn); box.addView(noteIn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        var to = ""
        if (!c.isSelf) {
            val people = c.members()
            val labels = listOf(t("Nobody (a reminder for everyone)", "ఎవరికీ కాదు (అందరికీ రిమైండర్)")) + people.map { if (it.optString("uid") == me) t("Me", "నేను") else it.optString("name") }
            val pick = ui.text("👤 " + labels[0], 15f, dl.accent, bold = true).apply { setPadding(0, dp(14), 0, dp(4)) }
            pick.setOnClickListener { AlertDialog.Builder(this).setTitle(t("Who will do it?", "ఎవరు చేస్తారు?")).setItems(labels.toTypedArray()) { _, i -> to = if (i == 0) "" else people[i - 1].optString("uid"); pick.text = "👤 " + labels[i] }.show() }
            box.addView(pick)
        }
        var due = 0L
        val when_ = ui.text("📅 " + t("Add a date and time (optional)", "తేదీ, సమయం (కావాలంటే)"), 15f, dl.accent, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
        when_.setOnClickListener { pickDateTime { at -> due = at; when_.text = "📅 " + Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)) } }
        box.addView(when_)
        if (c.isGroup) box.addView(ui.text(t("It stays pinned at the top until a group admin closes it. The person can add remarks and mark it done.",
            "గ్రూప్ అడ్మిన్ మూసేవరకు ఇది పైన ఉంటుంది. ఆ వ్యక్తి రిమార్క్స్ రాసి, పూర్తయిందని చెప్పవచ్చు."), 12.5f, dl.sub).apply { setPadding(0, dp(6), 0, 0) })
        AlertDialog.Builder(this).setTitle(if (c.isSelf) t("📌 New reminder", "📌 కొత్త రిమైండర్") else t("📌 New task", "📌 కొత్త పని")).setView(box)
            .setPositiveButton(t("Add", "చేర్చు")) { _, _ ->
                val title = titleIn.text.toString().trim()
                if (title.isEmpty()) { toast(t("Write what needs doing.", "ఏం చేయాలో రాయండి.")); return@setPositiveButton }
                val body = JSONObject().put("tid", "t" + System.currentTimeMillis().toString(36) + (100..999).random()).put("action", "new")
                    .put("title", title.take(120)).put("note", noteIn.text.toString().trim().take(600)).put("to", to).put("due", due)
                ChatSync.sendTask(this, store, cid, body) { e -> if (e != null) toast(e) else if (due > 0 && (to == me || c.isSelf)) toast(t("Reminder set on this phone too ⏰", "ఈ ఫోన్‌లో రిమైండర్ కూడా పెట్టాం ⏰")) }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun pickDateTime(done: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi -> cal.set(y, mo, d, h, mi, 0); done(cal.timeInMillis) }, 17, 0, false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun askText(title: String, hint: String, value: String = "", ok: String = "", then: (String) -> Unit) {
        val inp = ChatTheme.input(this, hint, value, multi = true)
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(inp) }
        AlertDialog.Builder(this).setTitle(title).setView(wrap).setPositiveButton(ok.ifEmpty { t("Send", "పంపు") }) { _, _ -> then(inp.text.toString().trim()) }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
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
        val body = JSONObject().put("t", "text").put("text", s.take(4000))
        // @mentions still in the text
        val mn = mentions.filter { (name, _) -> s.contains("@$name") }.values.distinct()
        if (mn.isNotEmpty()) body.put("mn", JSONArray(mn))
        mentions.clear()
        // the link preview shown while typing goes with it (or none, if it was closed with ✕)
        val u = LinkPreview.firstUrl(s)
        if (u != null && u == lpOff) body.put("_nolp", true) else if (u != null && u == lpUrl && lpData != null) body.put("lp", lpData)
        lpUrl = ""; lpData = null; lpOff = ""; renderLp()
        ChatSync.send(this, store, cid, withReply(body)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
    }
    /** While typing a link: its preview shows above the typing bar (✕ to send without it). */
    private fun linkPreviewSoon(text: String) {
        val c = conv() ?: return
        val u = if (c.isAi || !ChatTheme.linkPreviews(this)) null else LinkPreview.firstUrl(text)
        if (u == null) { if (lpUrl.isNotEmpty()) { lpUrl = ""; lpData = null; renderLp() }; return }
        if (u == lpUrl || u == lpOff) return
        lpUrl = u; lpData = null; renderLp()
        handler.postDelayed({
            if (lpUrl != u) return@postDelayed
            Thread { val lp = LinkPreview.get(u); runOnUiThread { if (lpUrl == u) { lpData = lp; renderLp() } } }.start()
        }, 700)
    }
    private fun renderLp() {
        lpBox.removeAllViews()
        if (lpUrl.isEmpty()) return
        lpBox.setPadding(dp(8), dp(6), dp(8), 0)
        lpBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val d = lpData
            if (d == null) addView(ui.text("", 13f, ChatTheme.SUB).apply { text = Ico.withIcon(context, R.drawable.ic_link, ChatTheme.SUB, dp(14), t("Getting the link's preview…", "లింక్ ప్రివ్యూ వస్తోంది…")); setPadding(dp(14), dp(12), dp(8), dp(12))
                background = ChatTheme.round(ChatTheme.CARD, dp(14).toFloat(), ChatTheme.EDGE, dp(1)) }, LinearLayout.LayoutParams(0, -2, 1f))
            else addView(previewCard(d, false).apply { (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 0 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconBtn(R.drawable.ic_close, t("Send without the preview", "ప్రివ్యూ లేకుండా పంపు"), ChatTheme.SUB, 18, 40) { lpOff = lpUrl; lpUrl = ""; lpData = null; renderLp() })
        })
    }
    /** "@" in a group: pick the person (or everyone); their name goes into the message. */
    private fun pickMention(at: Int) {
        val c = conv() ?: return
        val people = c.members().filter { it.optString("uid") != me }
        if (people.isEmpty()) return
        val labels = listOf("📢 " + t("Everyone", "అందరూ")) + people.map { it.optString("emoji", "🐾") + "  " + it.optString("name") }
        AlertDialog.Builder(this).setTitle(t("Mention…", "ఎవరిని పిలవాలి?")).setItems(labels.toTypedArray()) { _, i ->
            val name = if (i == 0) "everyone" else people[i - 1].optString("name").trim().replace(Regex("\\s+"), "_")
            val uid = if (i == 0) "*" else people[i - 1].optString("uid")
            val e = input.text
            if (at < e.length && e[at] == '@') { e.replace(at, at + 1, "@$name "); mentions[name] = uid; input.setSelection(minOf(e.length, at + name.length + 2)) }
        }.show()
    }

    // ---------- 📎 attach: a grid like WhatsApp's ----------
    private fun attach() {
        val c = conv() ?: return
        // (icon, tint: 0 gold · 1 teal · 2 lilac · 3 coral · 4 sky · 5 green · 6 pink · 7 orange, name, what it does)
        class Item(val icon: Int, val tint: Int, val label: String, val f: () -> Unit)
        val items = ArrayList<Item>()
        items.add(Item(R.drawable.ic_image, 4, t("Gallery", "గ్యాలరీ")) { pickPhoto() })
        items.add(Item(R.drawable.ic_camera, 3, t("Camera", "కెమెరా")) { camera() })
        if (!c.isAi) {
            items.add(Item(R.drawable.ic_video, 2, t("Video", "వీడియో")) { pickVideo() })
            items.add(Item(R.drawable.ic_doc, 0, t("Document", "ఫైల్")) { pickDoc() })
            items.add(Item(R.drawable.ic_headphones, 7, t("Audio", "ఆడియో")) { pickAudio() })
            items.add(Item(R.drawable.ic_map_pin, 5, t("Location", "లొకేషన్")) { shareLocation() })
            items.add(Item(R.drawable.ic_person, 1, t("Contact", "కాంటాక్ట్")) { shareContact() })
        }
        if (!c.isAi && !c.isBcast) {
            items.add(Item(R.drawable.ic_poll, 0, t("Poll", "పోల్")) { newPoll() })
            items.add(Item(R.drawable.ic_calendar, 6, t("Event", "కార్యక్రమం")) { newEvent() })
            if (!c.isSelf) items.add(Item(R.drawable.ic_eye, 4, t("View once", "ఒకసారి చూసేది")) { pickPhoto(REQ_ONCE) })
            items.add(Item(R.drawable.ic_tasks, 0, t("Task", "పని")) { newTask() })
        }
        if (!c.isAi) items.add(Item(R.drawable.ic_sticker, 2, t("Sticker", "స్టిక్కర్")) { openPanel(); handler.postDelayed({ panel?.open("sticker") }, 150) })
        items.add(Item(R.drawable.ic_edit, 3, t("Draw", "బొమ్మ గీయి")) {
            AlertDialog.Builder(this).setTitle(t("✏️ Draw", "✏️ బొమ్మ గీయండి")).setItems(arrayOf(t("On a blank page", "ఖాళీ పేజీ మీద"), t("On a photo", "ఫోటో మీద"))) { _, i -> if (i == 0) doodle(null) else pickPhoto(REQ_DOODLE) }.show()
        })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(14), dp(10), dp(4)) }
        var d: Dialog? = null
        items.chunked(4).forEach { rowItems ->
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEach { it ->
                    val (bg, fg) = ChatTheme.TINTS[it.tint]
                    addView(LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(12))
                        addView(Ico.view(context, it.icon, fg, 24, 58, it.label, ui.round(bg, 20)), LinearLayout.LayoutParams(dp(58), dp(58)))
                        addView(ui.text(it.label, 12.5f, ChatTheme.TXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); maxLines = 1 })
                        background = Ico.rowRipple(context)
                        setOnClickListener { _ -> d?.dismiss(); it.f() }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                repeat(4 - rowItems.size) { addView(View(this@ChatActivity), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
        d = ChatTheme.sheet(this, grid)
    }
    private fun pickVideo() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*") else Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_VIDEO) } catch (e: Exception) { try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), REQ_VIDEO) } catch (e2: Exception) { toast("Couldn't open your videos.") } }
    }
    private fun pickDoc(type: String = "*/*", req: Int = REQ_DOC) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, req) } catch (e: Exception) { try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE), req) } catch (e2: Exception) { toast("Couldn't open your files.") } }
    }
    private fun pickAudio() = pickDoc("audio/*", REQ_AUDIO)
    /** The name and size of a picked file. */
    private fun nameAndSize(u: Uri): Pair<String, Long> = try {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) Pair(c.getString(0) ?: "file", if (c.isNull(1)) -1L else c.getLong(1)) else null
        } ?: Pair("file", -1L)
    } catch (e: Exception) { Pair("file", -1L) }
    private fun tooBig() = toast(t("That file is too big. Habit Chat sends files up to 32 MB.", "ఆ ఫైల్ చాలా పెద్దది. Habit చాట్ 32 MB వరకు పంపుతుంది."))
    private fun sendVideo(u: Uri) {
        val caption = input.text.toString().trim()
        val (name, size) = nameAndSize(u)
        if (size > MAX_FILE) { tooBig(); return }
        toast(t("Getting the video ready…", "వీడియో సిద్ధం చేస్తున్నాం…"))
        Thread {
            val bytes = try { contentResolver.openInputStream(u)?.use { readLimited(it) } } catch (e: Exception) { null }
            if (bytes == null) { runOnUiThread { if (size > MAX_FILE || size < 0) tooBig() else toast(t("Couldn't read that video.", "ఆ వీడియో చదవలేకపోయాం.")) }; return@Thread }
            val body = JSONObject().put("name", name.take(80)).put("text", caption.take(1000))
            try {
                val r = android.media.MediaMetadataRetriever(); r.setDataSource(this, u)
                body.put("dur", ((r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt())
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
                body.put("w", if (rot == 90 || rot == 270) h else w).put("h", if (rot == 90 || rot == 270) w else h)
                r.getFrameAtTime(500_000)?.let { f ->
                    val th = ByteArrayOutputStream(); Bitmap.createScaledBitmap(f, 160, maxOf(1, 160 * f.height / maxOf(1, f.width)), true).compress(Bitmap.CompressFormat.JPEG, 60, th)
                    body.put("thumb", ChatCrypto.b64(th.toByteArray()))
                }
                r.release()
            } catch (e: Exception) { }
            runOnUiThread {
                if (caption.isNotEmpty()) { input.setText(""); db.setKv("draft:$cid", "") }
                ChatSync.sendFile(this, store, cid, "video", bytes, withReply(body)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
            }
        }.start()
    }
    private fun sendDoc(u: Uri) {
        val (name, size) = nameAndSize(u)
        val lower = name.lowercase()
        if (lower.endsWith(".apk") || lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".xapk")) {
            toast(t("For safety, Habit Chat doesn't send app files (.apk). Fraudsters use them to take over phones.", "భద్రత కోసం, Habit చాట్ యాప్ ఫైల్స్ (.apk) పంపదు. మోసగాళ్ళు వాటితో ఫోన్లను ఆక్రమిస్తారు.")); return
        }
        if (size > MAX_FILE) { tooBig(); return }
        Thread {
            val bytes = try { contentResolver.openInputStream(u)?.use { readLimited(it) } } catch (e: Exception) { null }
            runOnUiThread {
                if (bytes == null) { tooBig(); return@runOnUiThread }
                val body = JSONObject().put("name", name.take(100)).put("mime", contentResolver.getType(u) ?: "")
                ChatSync.sendFile(this, store, cid, "doc", bytes, withReply(body)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
            }
        }.start()
    }
    /** Reads a picked file, at most 32 MB (null when bigger). */
    private fun readLimited(inp: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var n = 0L
        while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > MAX_FILE) return null; out.write(buf, 0, r) }
        return out.toByteArray()
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
    /** Makes a photo small enough to send quickly (1600 px, JPEG), with a tiny preview, then sends it ([once]: view once). */
    private fun sendPhoto(once: Boolean = false, read: () -> ByteArray?) {
        val caption = if (once) "" else input.text.toString().trim()
        Thread {
            val raw = try { read() } catch (e: Exception) { null }
            val bmp0 = raw?.let { scaled(it, 1600) }
            if (bmp0 == null) { runOnUiThread { toast(t("Couldn't read that photo.", "ఆ ఫోటో చదవలేకపోయాం.")) }; return@Thread }
            val bmp = rotate(raw, bmp0)
            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val th = ByteArrayOutputStream(); Bitmap.createScaledBitmap(bmp, 24, maxOf(1, 24 * bmp.height / maxOf(1, bmp.width)), true).compress(Bitmap.CompressFormat.JPEG, 50, th)
            val body = JSONObject().put("w", bmp.width).put("h", bmp.height).put("text", caption.take(1000))
            if (once) body.put("once", true) else body.put("thumb", ChatCrypto.b64(th.toByteArray()))
            runOnUiThread {
                if (caption.isNotEmpty()) { input.setText(""); db.setKv("draft:$cid", "") }
                ChatSync.sendFile(this, store, cid, "photo", out.toByteArray(), withReply(body)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
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

    // stickers, GIFs
    private fun sendSticker(ref: String) {
        if (ref.startsWith("file:")) {
            val bytes = try { File(ref.removePrefix("file:")).readBytes() } catch (e: Exception) { return }
            ChatSync.sendFile(this, store, cid, "sticker", bytes, withReply(JSONObject().put("w", 512).put("h", 512))) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
        } else ChatSync.send(this, store, cid, withReply(JSONObject().put("t", "sticker").put("pack", ref.substringBefore(':')).put("n", ref.substringAfter(':')).put("alt", Stickers.altOf(this, ref)))) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
    }
    private fun sendGif(g: Gifs.Gif) {
        toast(t("Sending the GIF…", "GIF పంపుతున్నాం…"))
        Thread {
            val bytes = Gifs.download(g.url)
            if (bytes == null) { runOnUiThread { toast(t("Couldn't get that GIF.", "ఆ GIF రాలేదు.")) }; return@Thread }
            val body = JSONObject().put("w", g.w).put("h", g.h)
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { f -> val th = ByteArrayOutputStream(); Bitmap.createScaledBitmap(f, 96, maxOf(1, 96 * f.height / maxOf(1, f.width)), true).compress(Bitmap.CompressFormat.JPEG, 55, th); body.put("thumb", ChatCrypto.b64(th.toByteArray())) } } catch (e: Exception) { }
            runOnUiThread { ChatSync.sendFile(this, store, cid, "gif", bytes, withReply(body)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) } }
        }.start()
    }
    /** ✏️ Create sticker: a photo cut out as a circle or rounded square, with words if wanted. */
    private fun makeSticker(u: Uri) {
        Thread {
            val raw = try { contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (e: Exception) { null }
            val src = raw?.let { scaled(it, 900)?.let { b -> rotate(raw, b) } }
            runOnUiThread {
                if (src == null) { toast(t("Couldn't read that photo.", "ఆ ఫోటో చదవలేకపోయాం.")); return@runOnUiThread }
                var round = true
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(10), dp(20), 0) }
                val prev = ImageView(this).apply { setBackgroundColor(Color.parseColor("#DDE3E2")) }
                val words = ChatTheme.input(this, t("Words on the sticker (optional)", "స్టిక్కర్ మీద మాటలు (కావాలంటే)"))
                fun redraw() { val b = Stickers.fromPhoto(src, round, words.text.toString()); prev.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) }
                box.addView(prev, LinearLayout.LayoutParams(dp(200), dp(200)))
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4))
                    addView(ui.button(t("⚪ Circle", "⚪ వృత్తం"), "soft") { round = true; redraw() })
                    addView(ui.button(t("⬜ Square", "⬜ చతురస్రం"), "soft") { round = false; redraw() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                })
                box.addView(words, LinearLayout.LayoutParams(-1, -2))
                words.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { redraw() }
                })
                redraw()
                AlertDialog.Builder(this).setTitle(t("✏️ Create sticker", "✏️ స్టిక్కర్ చేయండి")).setView(ScrollView(this).apply { addView(box) })
                    .setPositiveButton(t("Save and send", "సేవ్ చేసి పంపు")) { _, _ ->
                        val b = Stickers.fromPhoto(src, round, words.text.toString())
                        val f = Stickers.saveMine(this, b)
                        Stickers.used(this, "file:" + f.absolutePath)
                        ChatSync.sendFile(this, store, cid, "sticker", b, JSONObject().put("w", 512).put("h", 512)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
                    }
                    .setNeutralButton(t("Only save", "సేవ్ మాత్రమే")) { _, _ -> Stickers.saveMine(this, Stickers.fromPhoto(src, round, words.text.toString())); toast(t("Saved in 😊 › 🏷️ › 🖼️ My stickers", "😊 › 🏷️ › 🖼️ నా స్టిక్కర్లలో సేవ్ చేశాం")) }
                    .setNegativeButton(t("Cancel", "వద్దు"), null).show()
            }
        }.start()
    }

    /** ✏️ Draw with a finger (on a blank page or on a photo), in colours, then send it as a photo. */
    private fun doodle(bg: Bitmap?) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val pad = DoodleView(this, bg)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        tools.addView(iconBtn(R.drawable.ic_close, t("Close", "మూసివేయి"), Color.WHITE) { d.dismiss() })
        tools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tools.addView(iconBtn(R.drawable.ic_reply, t("Undo", "వెనక్కి"), Color.WHITE) { pad.undo() })
        tools.addView(iconBtn(R.drawable.ic_edit, t("Pen size", "పెన్ సైజు"), Color.WHITE) { pad.thick = if (pad.thick > 14f) 7f else pad.thick + 7f; toast(t("Pen size changed", "పెన్ సైజు మార్చాం")) })
        tools.addView(Ico.view(this, R.drawable.ic_send, ChatTheme.Brand.INK, 22, 48, t("Send", "పంపు"), ChatTheme.circle(ChatTheme.Brand.GOLD)) {
            val b = pad.picture()
            val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, out)
            d.dismiss()
            sendPhoto { out.toByteArray() }
        })
        col.addView(tools)
        col.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
        val colours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(8), dp(6), dp(14)) }
        for (cs in listOf("#E53935", "#FB8C00", "#FDD835", "#43A047", "#1E88E5", "#8E24AA", "#000000", "#FFFFFF")) {
            val cl = Color.parseColor(cs)
            colours.addView(View(this).apply { background = ChatTheme.circle(cl); setOnClickListener { pad.colour = cl } }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(5); marginEnd = dp(5) })
        }
        col.addView(colours)
        d.setContentView(col)
        d.show()
    }

    // location, contact, poll, event
    private fun shareLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC); return
        }
        toast(t("Finding where you are…", "మీరు ఎక్కడున్నారో చూస్తున్నాం…"))
        OneLocation.get(this) { l ->
            if (l == null) { toast(t("Couldn't find your location. Turn on Location in quick settings and try again.", "మీ లొకేషన్ దొరకలేదు. క్విక్ సెట్టింగ్స్‌లో లొకేషన్ ఆన్ చేసి మళ్ళీ ప్రయత్నించండి.")); return@get }
            val label = ChatTheme.input(this, t("Name this place (optional), e.g. Office, Home", "ఈ చోటుకి పేరు (కావాలంటే), ఉదా: ఆఫీస్, ఇల్లు"))
            val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(label) }
            AlertDialog.Builder(this).setTitle(t("📍 Send your location?", "📍 మీ లొకేషన్ పంపాలా?"))
                .setMessage(t("Only the people in this chat can open it (locked end to end). Accuracy: about ${l.accuracy.toInt()} m.", "ఈ చాట్‌లో ఉన్నవాళ్ళు మాత్రమే తెరవగలరు (ఎండ్ టు ఎండ్ లాక్). సుమారు ${l.accuracy.toInt()} మీటర్ల ఖచ్చితత్వం."))
                .setView(wrap).setPositiveButton(t("Send", "పంపు")) { _, _ ->
                    ChatSync.send(this, store, cid, withReply(JSONObject().put("t", "loc").put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toInt()).put("name", label.text.toString().trim().take(80)))) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
                }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
        }
    }
    private fun shareContact() {
        try { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_SHARE_CONTACT) } catch (e: Exception) { toast("Couldn't open your contacts.") }
    }
    private fun onSharedContact(uri: Uri) {
        var name = ""; var number = ""
        try { contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: ""; number = c.getString(1) ?: "" } } } catch (e: Exception) { }
        if (number.isBlank()) { toast(t("That contact has no phone number.", "ఆ కాంటాక్ట్‌కి నంబర్ లేదు.")); return }
        AlertDialog.Builder(this).setTitle(t("👤 Send this contact?", "👤 ఈ కాంటాక్ట్ పంపాలా?")).setMessage("$name\n$number")
            .setPositiveButton(t("Send", "పంపు")) { _, _ -> ChatSync.send(this, store, cid, withReply(JSONObject().put("t", "contact").put("name", name.ifBlank { number }.take(80)).put("phones", JSONArray().put(number.take(30))))) { e -> if (e != null && !e.startsWith("No connection")) toast(e) } }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun newPoll() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val dl = ChatTheme.dlg(this)
        val q = ChatTheme.input(this, t("Ask a question", "ప్రశ్న అడగండి"))
        box.addView(q)
        box.addView(ui.text(t("Options", "ఎంపికలు"), 13f, dl.sub, bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
        val opts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun addOpt() { if (opts.childCount < 12) opts.addView(ChatTheme.input(this, t("Option ${opts.childCount + 1}", "ఎంపిక ${opts.childCount + 1}")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
        addOpt(); addOpt()
        box.addView(opts)
        box.addView(ui.text("➕ " + t("Add option", "ఎంపిక చేర్చు"), 15f, dl.accent, bold = true).apply { setPadding(0, dp(10), 0, dp(4)); setOnClickListener { addOpt() } })
        val multi = CheckBox(this).apply { text = t("Allow more than one answer", "ఒకటి కంటే ఎక్కువ ఎంచుకోవచ్చు"); isChecked = false; setTextColor(dl.txt) }
        box.addView(multi)
        AlertDialog.Builder(this).setTitle(t("📊 New poll", "📊 కొత్త పోల్")).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(t("Send", "పంపు")) { _, _ ->
                val o = (0 until opts.childCount).mapNotNull { (opts.getChildAt(it) as? EditText)?.text?.toString()?.trim()?.take(100)?.ifEmpty { null } }.distinct()
                if (q.text.isBlank() || o.size < 2) { toast(t("Write the question and at least 2 options.", "ప్రశ్న, కనీసం 2 ఎంపికలు రాయండి.")); return@setPositiveButton }
                ChatSync.send(this, store, cid, JSONObject().put("t", "poll").put("q", q.text.toString().trim().take(200)).put("o", JSONArray(o)).put("multi", multi.isChecked)) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun newEvent() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = ChatTheme.input(this, t("Event name, e.g. Grama sabha meeting", "కార్యక్రమం పేరు, ఉదా: గ్రామ సభ సమావేశం"))
        val place = ChatTheme.input(this, t("Place (optional)", "చోటు (కావాలంటే)"))
        val note = ChatTheme.input(this, t("Details (optional)", "వివరాలు (కావాలంటే)"), multi = true)
        var at = 0L
        val whenT = ui.text("📅 " + t("Pick the date and time", "తేదీ, సమయం ఎంచుకోండి"), 15f, ChatTheme.dlg(this).accent, bold = true).apply { setPadding(0, dp(12), 0, dp(6)) }
        whenT.setOnClickListener { pickDateTime { x -> at = x; whenT.text = "📅 " + Instant.ofEpochMilli(x).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)) } }
        box.addView(name); box.addView(whenT); box.addView(place); box.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        AlertDialog.Builder(this).setTitle(t("📅 New event", "📅 కొత్త కార్యక్రమం")).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(t("Send", "పంపు")) { _, _ ->
                if (name.text.isBlank() || at == 0L) { toast(t("Write the name and pick the date and time.", "పేరు రాసి, తేదీ, సమయం ఎంచుకోండి.")); return@setPositiveButton }
                ChatSync.send(this, store, cid, JSONObject().put("t", "event").put("title", name.text.toString().trim().take(120)).put("at", at)
                    .put("place", place.text.toString().trim().take(120)).put("note", note.text.toString().trim().take(500))) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    // voice notes
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
        ChatSync.sendFile(this, store, cid, "voice", f.readBytes(), withReply(JSONObject().put("dur", secs))) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
    }
    private fun cancelRecording() { stopRecording(false) }

    // =================== 🔍 search in the chat ===================
    private fun openSearch() {
        if (cid.isEmpty()) return
        searchBar.removeAllViews()
        val q = EditText(this).apply {
            hint = t("Search…", "వెతకండి…"); textSize = 16f; setTextColor(ChatTheme.TXT); setHintTextColor(ChatTheme.SUB); setSingleLine(); background = null
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        hitLabel = ui.text("", 12.5f, ChatTheme.SUB)
        searchBar.addView(iconBtn(R.drawable.ic_back, t("Close search", "వెతకడం ఆపు")) { closeSearch() })
        searchBar.addView(q, LinearLayout.LayoutParams(0, -2, 1f))
        searchBar.addView(hitLabel)
        searchBar.addView(iconBtn(R.drawable.ic_chevron_up, t("Earlier", "ముందుది"), size = 22, box = 40) { moveHit(-1) })
        searchBar.addView(iconBtn(R.drawable.ic_chevron_down, t("Later", "తర్వాతది"), size = 22, box = 40) { moveHit(1) })
        head.visibility = View.GONE; searchBar.visibility = View.VISIBLE; syncBack()
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { handler.removeCallbacks(searchRun); searchText = s?.toString()?.trim() ?: ""; handler.postDelayed(searchRun, 300) }
        })
        q.requestFocus(); getSystemService(InputMethodManager::class.java)?.showSoftInput(q, 0)
    }
    private var searchText = ""
    private val searchRun = Runnable {
        searchQ = searchText
        hits = if (searchQ.length >= 2) db.search(cid, searchQ).map { it.id } else emptyList()
        hitIdx = hits.size - 1
        hitLabel.text = if (searchQ.length < 2) "" else if (hits.isEmpty()) t("0", "0") else "${hitIdx + 1}/${hits.size}"
        lastCount = -2
        render()
        if (hitIdx >= 0) scroll.post { goTo(hits[hitIdx]) }
    }
    private fun moveHit(d: Int) {
        if (hits.isEmpty()) return
        hitIdx = (hitIdx + d).coerceIn(0, hits.size - 1)
        hitLabel.text = "${hitIdx + 1}/${hits.size}"
        goTo(hits[hitIdx])
    }
    private fun closeSearch() {
        searchBar.visibility = View.GONE; head.visibility = View.VISIBLE; syncBack()
        searchQ = ""; hits = emptyList(); hitIdx = -1
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchBar.windowToken, 0)
        lastCount = -1; render()
    }

    // =================== banners, menu, info ===================
    private fun renderBanner(c: ChatConv) {
        banner.removeAllViews()
        fun box(text: String, vararg buttons: View) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(ChatTheme.PIN_BG) }
            b.addView(ui.text(text, 14.5f, ChatTheme.TXT))
            if (buttons.isNotEmpty()) { val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }; buttons.forEach { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }; b.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }) }
            banner.addView(b)
            banner.addView(View(this).apply { setBackgroundColor(ChatTheme.PIN_LINE) }, LinearLayout.LayoutParams(-1, 1))
        }
        when {
            c.isLocal -> {}
            !c.member -> box(t("You're no longer in this group.", "మీరు ఇప్పుడు ఈ గ్రూప్‌లో లేరు."), chipButton(t("Delete chat", "చాట్ తొలగించు"), ChatTheme.DANGER) { db.clear(cid, true); finish() })
            c.blocked -> box(t("You blocked this person. They can't message you.", "మీరు ఈ వ్యక్తిని బ్లాక్ చేశారు."), chipButton(t("Unblock", "అన్‌బ్లాక్"), primary = true) { block(false) })
            !c.accepted && c.isGroup -> box(t("${c.nameOf(c.data.optString("by"))} added you to this group. Join it?", "${c.nameOf(c.data.optString("by"))} మిమ్మల్ని ఈ గ్రూప్‌లో చేర్చారు. చేరతారా?"),
                chipButton(t("Join", "చేరండి"), primary = true) { accept() }, chipButton(t("Leave", "వద్దు")) { leave() }, chipButton(t("Report", "రిపోర్ట్"), ChatTheme.DANGER) { report(db.msgs(cid).filter { it.sender != me }, c.data.optString("by")) })
            !c.accepted -> box(t("${c.name} wants to chat with you. Reply or accept to let them write more.", "${c.name} మీతో చాట్ చేయాలనుకుంటున్నారు. జవాబిస్తే లేదా ఒప్పుకుంటే వాళ్ళు ఇంకా రాయగలరు."),
                chipButton(t("Accept", "ఒప్పుకోండి"), primary = true) { accept() }, chipButton(t("Block", "బ్లాక్"), ChatTheme.DANGER) { block(true) }, chipButton(t("Report", "రిపోర్ట్"), ChatTheme.DANGER) { report(db.msgs(cid).filter { it.sender != me }, c.otherUid(me)) })
        }
    }

    private fun call(path: String, body: JSONObject, ok: (JSONObject) -> Unit) {
        Thread {
            val r = ChatSync.api(store, "POST", path, body.put("cid", cid).toString())
            runOnUiThread { if (r.ok) { r.json.optJSONObject("conv")?.let { db.putConv(it) }; ok(r.json); render() } else toast(r.error("Couldn't do it.")) }
        }.start()
    }
    private fun accept() = call("/api/chat/accept", JSONObject()) { }
    private fun block(on: Boolean) { val c = conv() ?: return; call("/api/chat/block", JSONObject().put("uid", c.otherUid(me)).put("on", on)) { toast(if (on) t("Blocked", "బ్లాక్ చేశాం") else t("Unblocked", "అన్‌బ్లాక్ చేశాం")) } }
    private fun leave() {
        AlertDialog.Builder(this).setMessage(t("Exit this group?", "ఈ గ్రూప్ నుంచి వెళ్ళిపోవాలా?")).setPositiveButton(t("Exit", "వెళ్ళిపోండి")) { _, _ ->
            call("/api/chat/leave", JSONObject()) { db.patchConv(cid) { it.put("member", false) }; db.clear(cid, true); finish() }
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** Report: the messages (opened on this phone) go to the Habit team, so they can see what happened. */
    private fun report(msgs: List<ChatMsg>, who: String) {
        val reasons = arrayOf(t("Spam or fraud", "స్పామ్ / మోసం"), t("Bad or abusive words", "చెడ్డ మాటలు"), t("Troubling a child", "పిల్లలను ఇబ్బంది పెట్టడం"), t("Something else", "ఇంకేదైనా"))
        AlertDialog.Builder(this).setTitle(t("⚠️ Report: why?", "⚠️ రిపోర్ట్: ఎందుకు?")).setItems(reasons) { _, i ->
            val arr = JSONArray()
            for (m in msgs.takeLast(10)) arr.put(JSONObject().put("from", m.sender).put("text", ChatSync.preview(this, m)).put("at", m.at))
            call("/api/chat/report", JSONObject().put("uid", who).put("reason", reasons[i]).put("msgs", arr)) {
                toast(t("Reported. Thank you: the Habit team will look at it.", "రిపోర్ట్ చేశాం. ధన్యవాదాలు."))
                val c = conv()
                if (c != null && c.isDm && !c.blocked) AlertDialog.Builder(this).setMessage(t("Block them too?", "వాళ్ళని బ్లాక్ కూడా చేయాలా?"))
                    .setPositiveButton(t("Block", "బ్లాక్")) { _, _ -> block(true) }.setNegativeButton(t("No", "వద్దు"), null).show()
            }
        }.show()
    }
    private fun openInfo() {
        val c = conv() ?: return
        when {
            c.isBcast -> ChatInfoActivity.open(this, cid, "info")
            c.isLocal -> {}
            else -> ChatInfoActivity.open(this, cid, "info")
        }
    }

    /** ⋮ like WhatsApp's: the main things, and "More ›" for the rest. */
    private fun menu(anchor: View) {
        val c = conv() ?: return
        val pm = PopupMenu(this, anchor)
        val acts = ArrayList<() -> Unit>()
        fun add(menu: android.view.Menu, label: String, f: () -> Unit) { menu.add(0, acts.size, acts.size, label); acts.add(f) }
        val m = pm.menu
        if (c.isDm) {
            add(m, t("👥 New group with them", "👥 వీళ్ళతో కొత్త గ్రూప్")) { newGroupWith(c) }
            add(m, t("👤 View contact", "👤 కాంటాక్ట్ వివరాలు")) { openInfo() }
        }
        if (c.isGroup && c.member) { add(m, t("👥 Group info", "👥 గ్రూప్ వివరాలు")) { openInfo() } }
        if (c.isBcast) add(m, t("📢 Broadcast list info", "📢 బ్రాడ్‌కాస్ట్ లిస్ట్ వివరాలు")) { openInfo() }
        add(m, t("🔍 Search", "🔍 వెతకండి")) { openSearch() }
        if (!c.isAi) add(m, if (c.isGroup) t("🖼️ Group media", "🖼️ గ్రూప్ మీడియా") else t("🖼️ Media, links and docs", "🖼️ మీడియా, లింక్స్, ఫైల్స్")) { ChatInfoActivity.open(this, cid, "media") }
        if (!c.isLocal) add(m, if (c.muted) t("🔔 Unmute notifications", "🔔 నోటిఫికేషన్లు ఆన్") else t("🔕 Mute notifications", "🔕 నోటిఫికేషన్లు ఆఫ్")) { db.setMuted(cid, !c.muted); toast(if (c.muted) t("Unmuted", "ఆన్ చేశాం") else t("Muted", "ఆఫ్ చేశాం")) }
        if (!c.isLocal && c.member && !(c.isGroup && c.adminsEdit && !c.isAdmin(me))) add(m, t("⏱ Disappearing messages", "⏱ కనిపించకుండా పోయే మెసేజ్‌లు")) { pickTimer() }
        add(m, t("🎨 Chat theme", "🎨 చాట్ థీమ్")) { pickWall() }
        if (db.tasks(cid).isNotEmpty()) add(m, t("📋 Open tasks", "📋 తెరిచిన పనులు")) { pinnedOpen = true; renderPinned(c) }
        val more = m.addSubMenu(0, 900, 900, t("More ›", "ఇంకా ›"))
        if (!c.isLocal) add(more, t("⚠️ Report", "⚠️ రిపోర్ట్")) { report(db.msgs(cid).filter { it.sender != me }, if (c.isGroup) "" else c.otherUid(me)) }
        if (c.isDm) add(more, if (c.blocked) t("✅ Unblock", "✅ అన్‌బ్లాక్") else t("🚫 Block", "🚫 బ్లాక్")) { block(!c.blocked) }
        add(more, t("🧹 Clear chat", "🧹 చాట్ క్లియర్")) { AlertDialog.Builder(this).setMessage(t("Clear all messages in this chat on this phone? This can't be undone.", "ఈ ఫోన్‌లో ఈ చాట్ మెసేజ్‌లు అన్నీ తొలగించాలా? మళ్ళీ రావు."))
            .setPositiveButton(t("Clear", "తొలగించు")) { _, _ -> db.clear(cid, false); if (c.isAi) ChatSync.aiForget(); render() }.setNegativeButton(t("Cancel", "వద్దు"), null).show() }
        add(more, t("📤 Export chat", "📤 చాట్ ఎగుమతి")) { exportChat(c) }
        add(more, t("📲 Add shortcut", "📲 షార్ట్‌కట్ చేర్చు")) { ChatShortcut.add(this, c) }
        add(more, t("📋 Add to list", "📋 లిస్ట్‌లో చేర్చు")) { ChatLists.pick(this, cid) }
        if (!c.isSelf && !c.isAi) add(more, if (ChatTheme.isHidden(this, cid)) t("👁 Show in the chat list again", "👁 మళ్ళీ చాట్ లిస్ట్‌లో చూపించు") else t("🔒 Lock (hide) this chat", "🔒 ఈ చాట్ లాక్ (దాచు)")) {
            val on = !ChatTheme.isHidden(this, cid); db.setKv("lock:$cid", if (on) "1" else "")
            toast(if (on) t("Locked. It's in 🔒 Locked chats at the top of your chat list.", "లాక్ చేశాం. చాట్ లిస్ట్ పైన 🔒 లాక్ చేసిన చాట్‌లలో ఉంది.") else t("Shown in the list again", "మళ్ళీ లిస్ట్‌లో చూపిస్తాం"))
        }
        if (c.isGroup && c.member) add(more, t("🚪 Exit group", "🚪 గ్రూప్ వదిలేయండి")) { leave() }
        add(more, t("⚙️ Chat settings", "⚙️ చాట్ సెట్టింగ్స్")) { startActivity(Intent(this, ChatSettingsActivity::class.java)) }
        pm.setOnMenuItemClickListener { if (it.itemId in acts.indices) { acts[it.itemId](); true } else false }
        pm.show()
    }
    private fun exportChat(c: ChatConv) {
        AlertDialog.Builder(this).setTitle(t("📤 Export chat", "📤 చాట్ ఎగుమతి"))
            .setMessage(t("The messages go out as a text file you can share or save. Photos and files too?", "మెసేజ్‌లు టెక్స్ట్ ఫైల్‌గా వస్తాయి, షేర్ లేదా సేవ్ చేయవచ్చు. ఫోటోలు, ఫైల్స్ కూడా కావాలా?"))
            .setPositiveButton(t("Include media", "మీడియాతో")) { _, _ -> ChatExport.export(this, c, true) }
            .setNeutralButton(t("Without media", "మీడియా లేకుండా")) { _, _ -> ChatExport.export(this, c, false) }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** ⏱ Disappearing messages: off, 24 hours, 7 days or 90 days (for everyone in the chat). */
    private fun pickTimer() {
        val cur = ChatTheme.disappear(this, cid)
        val labels = ChatTheme.TIMERS.map { ChatTheme.timerText(it, te) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(t("⏱ Disappearing messages", "⏱ కనిపించకుండా పోయే మెసేజ్‌లు"))
            .setSingleChoiceItems(labels, ChatTheme.TIMERS.indexOfFirst { it == cur }.coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (ChatTheme.TIMERS[i] != cur) ChatSync.setTimer(this, store, cid, ChatTheme.TIMERS[i]) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
    private fun newGroupWith(c: ChatConv) {
        val name = ChatTheme.input(this, t("Group name", "గ్రూప్ పేరు"))
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(name) }
        AlertDialog.Builder(this).setTitle(t("👥 New group with ${c.name}", "👥 ${c.name} తో కొత్త గ్రూప్")).setView(wrap).setPositiveButton(t("Next: add more people", "తర్వాత: ఇంకా వ్యక్తులను చేర్చండి")) { _, _ ->
            val n = name.text.toString().trim()
            if (n.length < 2) { toast(t("Give the group a name.", "గ్రూప్‌కి పేరు పెట్టండి.")); return@setPositiveButton }
            ChatPeople.pick(this, store, t("Add people to \"$n\"", "\"$n\" లో వ్యక్తులను చేర్చండి"), setOf(me, c.otherUid(me))) { ppl ->
                Thread {
                    val r = ChatSync.api(store, "POST", "/api/chat/group", JSONObject().put("name", n).put("members", JSONArray(listOf(c.otherUid(me)) + ppl.map { it.optString("uid") })).toString())
                    runOnUiThread {
                        val conv = r.json.optJSONObject("conv")
                        if (!r.ok || conv == null) { toast(r.error("Couldn't make the group.")); return@runOnUiThread }
                        db.putConv(conv); open(this, conv.optString("cid")); finish()
                    }
                }.start()
            }
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    private fun pickWall() {
        val walls = ChatTheme.WALLS
        val cur = ChatTheme.wallOf(this, cid)
        AlertDialog.Builder(this).setTitle(t("🎨 Chat theme for this chat", "🎨 ఈ చాట్‌కి థీమ్"))
            .setSingleChoiceItems(walls.map { if (te) it.te else it.en }.toTypedArray(), walls.indexOfFirst { it.id == cur }) { d, i ->
                db.setKv("wall:$cid", walls[i].id.toString()); setWall(); d.dismiss()
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }

    // =================== links, QR codes and phone contacts ===================
    /** Opens (or starts) a chat with the person who has this @name. */
    private fun openHandle(handle: String) {
        if (!ChatSync.isSetUp(this)) { toast(t("Open Messages › 💬 Habit Chat first.", "ముందు మెసేజ్‌లు › 💬 Habit చాట్ తెరవండి.")); startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        val h = handle.trim().removePrefix("@")
        if (h.isEmpty()) { finish(); return }
        title.text = "@$h"; sub.text = t("Opening…", "తెరుస్తున్నాం…")
        Thread {
            val r = ChatSync.api(store, "POST", "/api/chat/open", JSONObject().put("handle", h).toString())
            runOnUiThread {
                val conv = r.json.optJSONObject("conv")
                if (!r.ok || conv == null) { toast(r.error("Couldn't open the chat.")); finish(); return@runOnUiThread }
                db.putConv(conv); cid = conv.optString("cid"); ChatSync.openCid = cid; buildBar(); render(); setWall()
            }
        }.start()
    }
    /** A group invite link: shows the group, then joins it. */
    private fun joinGroup(code: String) {
        if (!ChatSync.isSetUp(this)) { toast(t("Open Messages › 💬 Habit Chat first.", "ముందు మెసేజ్‌లు › 💬 Habit చాట్ తెరవండి.")); startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        title.text = t("Group invite", "గ్రూప్ ఆహ్వానం"); sub.text = t("Opening…", "తెరుస్తున్నాం…")
        Thread {
            val r = ChatSync.api(store, "GET", "/api/chat/group/peek?code=" + Uri.encode(code))
            runOnUiThread {
                if (!r.ok) { toast(r.error("This invite link doesn't work any more.")); finish(); return@runOnUiThread }
                val j = r.json
                if (j.optBoolean("member")) { cid = j.optString("cid"); if (db.conv(cid) != null) { ChatSync.openCid = cid; buildBar(); render(); setWall() } else finish(); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle(j.optString("emoji", "👥") + "  " + j.optString("name"))
                    .setMessage(t("${j.optInt("n")} people", "${j.optInt("n")} మంది") + (if (j.optString("desc").isNotBlank()) "\n\n" + j.optString("desc") else "") + "\n\n" + t("Join this group?", "ఈ గ్రూప్‌లో చేరతారా?"))
                    .setPositiveButton(t("Join", "చేరండి")) { _, _ ->
                        Thread {
                            val x = ChatSync.api(store, "POST", "/api/chat/join", JSONObject().put("code", code).toString())
                            runOnUiThread {
                                val conv = x.json.optJSONObject("conv")
                                if (!x.ok || conv == null) { toast(x.error("Couldn't join.")); finish(); return@runOnUiThread }
                                db.putConv(conv); cid = conv.optString("cid"); ChatSync.openCid = cid; buildBar(); render(); setWall()
                            }
                        }.start()
                    }.setNegativeButton(t("Cancel", "వద్దు")) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            }
        }.start()
    }
    private fun scanQr() {
        title.text = t("Scan a Habit QR code", "Habit QR కోడ్ స్కాన్"); sub.text = ""
        AlertDialog.Builder(this).setItems(arrayOf(t("📷 Take a photo of the QR code", "📷 QR కోడ్ ఫోటో తీయండి"), t("🖼️ From a photo or screenshot", "🖼️ ఫోటో లేదా స్క్రీన్‌షాట్ నుంచి"))) { _, i -> if (i == 0) camera(REQ_QR_CAM) else pickPhoto(REQ_QR) }
            .setOnCancelListener { finish() }.show()
    }
    private fun onQr(read: () -> Uri?) {
        val text = try { read()?.let { QrRead.decode(this, it) } ?: "" } catch (e: Exception) { "" }
        if (LinkedDevices.isLink(text)) { LinkedDevicesActivity.open(this, text); finish(); return }
        Regex("/g/([A-Za-z0-9]{10,20})").find(text)?.groupValues?.get(1)?.let { joinGroup(it); return }
        val h = Regex("/c/([A-Za-z0-9_]{3,20})").find(text)?.groupValues?.get(1) ?: Regex("bigd://chat/([A-Za-z0-9_]{3,20})").find(text)?.groupValues?.get(1)
        if (h == null) { toast(if (text.isEmpty()) t("No QR code found in that picture.", "ఆ ఫోటోలో QR కోడ్ కనిపించలేదు.") else t("That isn't a Habit chat QR code.", "అది Habit చాట్ QR కోడ్ కాదు.")); finish(); return }
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
        val p = ChatContacts.Person(name.ifBlank { number }, number)
        ChatContacts.remember(this, p.name, number)      // shows under "Invite your people" next time
        AlertDialog.Builder(this).setTitle(p.name).setItems(arrayOf(t("📞 Phone call", "📞 ఫోన్ కాల్"), t("💬 Invite on WhatsApp", "💬 వాట్సాప్‌లో ఆహ్వానించండి"), t("✉️ Invite by SMS", "✉️ SMS తో ఆహ్వానించండి"))) { _, i ->
            when (i) {
                0 -> phoneCall(number)
                1 -> { ChatContacts.invite(this, store, p); finish() }
                else -> { try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", ChatContacts.inviteText(this, store, p.name))) } catch (e: Exception) { toast("No SMS app.") }; finish() }
            }
        }.setOnCancelListener { finish() }.show()
    }
    /** A normal phone call (to anyone, with Habit or not). */
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
            REQ_ONCE -> if (ok) data?.data?.let { u -> sendPhoto(once = true) { contentResolver.openInputStream(u)?.use { it.readBytes() } } }
            REQ_STICKER -> if (ok) data?.data?.let { makeSticker(it) }
            REQ_DOODLE -> if (ok) data?.data?.let { u -> Thread { val raw = try { contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (e: Exception) { null }
                val b = raw?.let { scaled(it, 1600)?.let { x -> rotate(raw, x) } }; runOnUiThread { if (b != null) doodle(b) else toast(t("Couldn't read that photo.", "ఆ ఫోటో చదవలేకపోయాం.")) } }.start() }
            REQ_CAMERA -> if (ok) sendPhoto { CameraFiles.file(this).takeIf { it.exists() }?.readBytes() }
            REQ_VIDEO -> if (ok) data?.data?.let { sendVideo(it) }
            REQ_DOC, REQ_AUDIO -> if (ok) data?.data?.let { sendDoc(it) }
            REQ_SHARE_CONTACT -> if (ok) data?.data?.let { onSharedContact(it) }
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
            REQ_LOC -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) shareLocation() else toast(t("Habit needs the location permission to send where you are.", "మీ లొకేషన్ పంపడానికి Habit కి అనుమతి కావాలి."))
            REQ_CALL_PERMS -> if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) CallManager.startOutgoing(this, cid, callVideo)
                else toast(t("Habit needs the microphone" + (if (callVideo) " and camera" else "") + " for calls.", "కాల్స్ కోసం Habit కి మైక్" + (if (callVideo) ", కెమెరా" else "") + " అనుమతి కావాలి."))
            REQ_CAMERA, REQ_QR_CAM -> if (granted) camera(requestCode) else if (requestCode == REQ_QR_CAM) finish()
            REQ_CALL -> if (granted) phoneCall(pickedNumber) else { try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(pickedNumber)))) } catch (e: Exception) { }; finish() }
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Finding Habit people by name or @name (for a new chat, a new group, or adding to a group). */
object ChatPeople {
    /** Shows a search box; [done] gets the people picked. [multi]: tick several (groups); otherwise one tap picks. */
    fun pick(a: Activity, store: Store, title: String, skip: Set<String> = emptySet(), multi: Boolean = true, done: (List<JSONObject>) -> Unit) {
        val ui = Ui(a)
        val te = store.lang == "te"
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) }
        val dl = ChatTheme.dlg(a)
        val q = ChatTheme.input(a, if (te) "పేరు లేదా @పేరు టైప్ చేయండి" else "Type a name or @name")
        val chosen = LinkedHashMap<String, JSONObject>()
        val chosenView = ui.text("", 13.5f, dl.accent, bold = true)
        val results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(q); if (multi) box.addView(chosenView); box.addView(results)
        var dlg: AlertDialog? = null
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var seq = 0
        fun row(p: JSONObject) {
            val uid = p.optString("uid")
            if (uid in skip) return
            val label = { "${p.optString("emoji", "🐾")}  ${p.optString("name")}" + (if (p.optString("handle").isNotEmpty()) "  @${p.optString("handle")}" else "") + if (chosen.containsKey(uid)) "  ✓" else "" }
            val r = ui.text(label(), 15.5f, dl.txt).apply { setPadding(0, ui.dp(11), 0, ui.dp(11)) }
            r.setOnClickListener {
                if (!multi) { dlg?.dismiss(); done(listOf(p)); return@setOnClickListener }
                if (chosen.containsKey(uid)) chosen.remove(uid) else chosen[uid] = p
                chosenView.text = if (chosen.isEmpty()) "" else "✓ " + chosen.values.joinToString(", ") { it.optString("name") }
                r.text = label()
            }
            results.addView(r)
        }
        // first: the people I already chat with (no typing needed)
        fun known() {
            results.removeAllViews()
            val me = ChatSync.myUid(a)
            val seen = HashSet<String>()
            ChatDb.get(a).convs().filter { it.isDm && it.accepted && !it.blocked }.forEach { c ->
                c.members().firstOrNull { it.optString("uid") != me }?.let { p -> if (seen.add(p.optString("uid"))) row(p) }
            }
            if (results.childCount > 0) results.addView(ui.text(if (te) "ఇంకెవరైనా: పైన పేరు టైప్ చేయండి" else "Anyone else: type their name above", 12.5f, dl.sub).apply { setPadding(0, ui.dp(8), 0, 0) }, 0)
        }
        fun search(s: String) {
            val my = ++seq
            if (s.trim().length < 2) { known(); return }
            Thread {
                val r = ChatSync.api(store, "GET", "/api/chat/find?q=" + Uri.encode(s.trim()))
                main.post {
                    if (my != seq) return@post
                    results.removeAllViews()
                    val ppl = r.json.optJSONArray("people") ?: JSONArray()
                    if (!r.ok || ppl.length() == 0) results.addView(ui.text(if (!r.ok) r.error("Couldn't search.") else if (te) "ఎవరూ దొరకలేదు. వాళ్ళు Habit చాట్ తెరిచారా? మీ ఆహ్వాన లింక్ పంపండి." else "No one found. Have they opened Habit Chat? Send them your invite link.", 13.5f, dl.sub).apply { setPadding(0, ui.dp(10), 0, 0) })
                    for (i in 0 until ppl.length()) row(ppl.getJSONObject(i))
                }
            }.start()
        }
        known()
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
