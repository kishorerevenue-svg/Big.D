package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Messages tab: Habit Chat, like WhatsApp, in dark navy and gold.
 *  - The top: "Habit Chat", 📷 scan a QR code, ⋮ (new group, community, broadcast, ⭐ starred, read all, locked chats,
 *    settings); then 💬 Chats · ⭕ Updates · 🏘️ Communities · 📞 Calls.
 *  - Chats: "Ask Habit AI or search" (chats, Habit people, phone contacts, or ask the AI); chips: All · Unread · ⭐
 *    Favourites · Groups · my own lists · ＋. 📌 Pinned chats first; long-press a chat to pick it (like WhatsApp: a bar at
 *    the top with pin, delete, mute and ⋮ favourite, add to list, read / unread, lock (🔒 locked chats open with the
 *    fingerprint), theme, block, select all; tap more chats to pick them too).
 *  - "Invite your people": ⭐ favourite contacts and people you called or invited; everyone else by search.
 *  - Updates: status updates for 24 hours (text, photo, video), locked end to end. Communities: groups together under
 *    one announcements group. Calls: every Habit call, a new call, favourites.
 *  - The round ➕ (bottom right, MainActivity's button) does what fits the open tab.
 */
class ChatPage(private val m: MainActivity) {
    private val store get() = m.store
    private val ui get() = m.ui
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = m.dp(v)
    private val db get() = ChatDb.get(m)
    private val C = ChatTheme
    private var section: LinearLayout? = null
    private var chipsBox: LinearLayout? = null
    private var listBox: LinearLayout? = null
    private var tabsBox: LinearLayout? = null
    private var starting = false
    private var page = "chats"
    private var filter = "all"
    private var query = ""
    private var hiddenOpen = false
    private var people: JSONArray? = null      // Habit people found for [peopleQ]
    private var peopleQ = ""
    private var comms: List<JSONObject>? = null
    private var commsAt = 0L
    private var statusAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { if (listBox?.isAttachedToWindow == true) { tabsBox?.let { fillTabs(it) }; chipsBox?.let { fillChips(it) }; listBox?.let { fillList(it) } } }
    private val typingRefresh: () -> Unit = { if (page == "chats" && query.isEmpty() && listBox?.isAttachedToWindow == true) listBox?.let { fillList(it) } }

    init { ChatSync.listeners.add(refresh); ChatSync.typingListeners.add(typingRefresh) }

    fun build() {
        val s = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
        section = s
        draw(s)
        m.add(s, 0)
    }
    private fun redraw() { section?.let { if (it.isAttachedToWindow) draw(it) } }

    private fun txt(s: String, size: Float, color: Int = C.TXT, bold: Boolean = false) = ui.text(s, size, color, bold)

    private fun draw(s: LinearLayout) {
        ChatTheme.refresh(m)
        s.removeAllViews()
        // the top: the big title, then round buttons: scan a QR code, more (MainActivity gives Habit Chat 8dp at the sides;
        // the top part keeps 16dp, the rows of chats reach out to 8dp)
        val side = LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(8); marginEnd = dp(8) }
        s.addView(LinearLayout(m).apply {
            layoutParams = LinearLayout.LayoutParams(side)
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), 0, dp(4))
            addView(txt(t("Chats", "చాట్‌లు"), 30f, C.TXT).apply { typeface = Fonts.heavy(m); letterSpacing = -0.02f; setLineSpacing(0f, 1f) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (ChatSync.isSetUp(m)) {
                addView(roundIcon(R.drawable.ic_camera, t("Scan a QR code", "QR స్కాన్")) { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "scan")) })
                addView(roundIcon(R.drawable.ic_more, t("More", "ఇంకా")) { topMenu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
            }
        })
        if (!ChatSync.isSetUp(m)) { intro(s); return }
        if (pickedChats.isNotEmpty()) m.setTopBar(pickBar())
        db.ensureAi()
        // Chats · Updates · Communities · Calls
        val tabs = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; background = C.round(C.CARD, dp(24).toFloat(), C.EDGE, dp(1)); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        tabsBox = tabs
        fillTabs(tabs)
        s.addView(tabs, LinearLayout.LayoutParams(side).apply { topMargin = dp(8) })
        if (page == "chats") {
            // the search bar: chats, people, contacts, or ask the AI
            val search = EditText(m).apply {
                hint = t("Ask Habit AI or search", "Habit AI ని అడగండి లేదా వెతకండి"); setText(query)
                textSize = 15f; setTextColor(C.TXT); setHintTextColor(C.SUB); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
                background = C.round(C.CARD, dp(25).toFloat(), C.EDGE, dp(1)); setPadding(dp(18), dp(13), dp(18), dp(13)); minHeight = dp(50)
                setCompoundDrawablesRelative(Ico.d(m, R.drawable.ic_search, C.SUB, dp(20)), null, null, null); compoundDrawablePadding = dp(10)
            }
            search.onText { q ->
                query = q.trim(); listBox?.let { fillList(it) }
                handler.removeCallbacksAndMessages("people")
                if (query.length >= 2) handler.postAtTime({ findPeople(query) }, "people", android.os.SystemClock.uptimeMillis() + 450)
            }
            s.addView(search, LinearLayout.LayoutParams(side).apply { topMargin = dp(12) })
            val chips = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
            chipsBox = chips
            fillChips(chips)
            s.addView(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        } else chipsBox = null
        val list = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
        listBox = list
        fillList(list)
        // the rows reach a little closer to the screen's edges than the rest
        s.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        s.addView(View(m), LinearLayout.LayoutParams(-1, dp(80)))      // room for the new-chat button
    }

    private fun fillTabs(box: LinearLayout) {
        box.removeAllViews()
        val unread = db.convs().count { !hidden(it.cid) && (it.unread > 0 || markedUnread(it.cid)) && !it.muted }
        val newStatus = ChatStatus.unseenCount(m)
        val missed = db.callLogs(30).count { it.body.optString("st") == "missed" && it.at > (db.kv("callsSeen").toLongOrNull() ?: 0L) }
        listOf(Triple("chats", R.drawable.ic_chat, t("Chats", "చాట్‌లు") to unread), Triple("updates", R.drawable.ic_status, t("Updates", "అప్‌డేట్స్") to newStatus),
            Triple("comms", R.drawable.ic_community, t("Communities", "కమ్యూనిటీలు") to 0), Triple("calls", R.drawable.ic_phone, t("Calls", "కాల్స్") to missed)).forEach { (k, icon, p) ->
            val on = page == k
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(2), dp(7), dp(2), dp(6))
                background = if (on) C.round(C.PRIMARY, dp(20).toFloat()) else null
                addView(FrameLayout(m).apply {
                    addView(ImageView(m).apply { setImageDrawable(m.getDrawable(icon)!!.mutate()); imageTintList = android.content.res.ColorStateList.valueOf(if (on) C.ON_PRIMARY_ICON else C.SUB) }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    if (p.second > 0) addView(txt(if (p.second > 99) "99+" else p.second.toString(), 10f, if (on) C.PRIMARY else C.ON_GOLD).apply {
                        typeface = Fonts.heavy(m); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(dp(4), 0, dp(4), 0); minWidth = dp(16)
                        background = C.round(if (on) C.ON_PRIMARY else C.GOLD, dp(8).toFloat())
                    }, FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP or Gravity.END))
                }, LinearLayout.LayoutParams(dp(46), dp(22)))
                addView(txt(p.first, 11.5f, if (on) C.ON_PRIMARY else C.SUB).apply { typeface = if (on) Fonts.heavy(m) else Fonts.semi(m); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0); setLineSpacing(0f, 1f) })
                setOnClickListener {
                    page = k; hiddenOpen = false
                    if (k == "calls") db.setKv("callsSeen", System.currentTimeMillis().toString())
                    redraw()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** A line icon to tap (no circle behind it). */
    private fun icon(res: Int, desc: String, color: Int = C.TXT, onClick: (View) -> Unit) = Ico.view(m, res, color, 22, 44, desc, null, onClick)
    /** A round button at the top: an icon on a soft circle. */
    private fun roundIcon(res: Int, desc: String, onClick: (View) -> Unit) = Ico.view(m, res, C.TXT, 20, 44, desc, C.round(C.CARD, dp(22).toFloat(), C.EDGE, dp(1)), onClick)

    private fun intro(s: LinearLayout) {
        s.addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = C.round(C.CARD, dp(24).toFloat(), C.EDGE, dp(1))
            addView(txt(t("Chat, call and share, like WhatsApp", "వాట్సాప్ లాగా చాట్, కాల్, షేర్"), 18f, C.TXT).apply { typeface = Fonts.heavy(m) })
            addView(txt(t("Chat with family and friends who use Habit AI: messages, groups, communities, voice and video calls, status updates, photos, videos, documents, voice notes, stickers, polls, and reminders and work tasks. " +
                "🔒 Locked end to end: only the people in a chat can read it, not even Habit's server.",
                "Habit AI వాడే కుటుంబం, స్నేహితులతో: మెసేజ్‌లు, గ్రూప్‌లు, కమ్యూనిటీలు, వాయిస్, వీడియో కాల్స్, స్టేటస్, ఫోటోలు, వీడియోలు, ఫైల్స్, వాయిస్ మెసేజ్‌లు, స్టిక్కర్లు, పోల్స్, రిమైండర్లు, పనులు. " +
                "🔒 ఎండ్ టు ఎండ్ లాక్: చాట్‌లో ఉన్నవాళ్ళు మాత్రమే చదవగలరు, Habit సర్వర్ కూడా చదవలేదు."), 14.5f, C.SUB).apply { setPadding(0, dp(8), 0, 0) })
            addView(goldButton(if (starting) t("Starting…", "మొదలవుతోంది…") else t("💬 Start Habit Chat", "💬 Habit చాట్ మొదలుపెట్టండి")) { if (!starting) start() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); marginStart = dp(8); marginEnd = dp(8) })
    }

    private fun goldButton(label: String, onClick: () -> Unit) = TextView(m).apply {
        text = label; textSize = 15.5f; setTextColor(C.ON_PRIMARY); typeface = Fonts.heavy(context); gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14)); background = C.round(C.PRIMARY, dp(26).toFloat()); setOnClickListener { onClick() }
    }
    /** A chip: the chosen one filled (gold on navy, navy on white), the others soft with a thin edge. */
    private fun pill(label: String, on: Boolean, onClick: () -> Unit) = TextView(m).apply {
        text = label; textSize = 13.5f; gravity = Gravity.CENTER; typeface = if (on) Fonts.heavy(context) else Fonts.semi(context); includeFontPadding = false
        setTextColor(if (on) C.ON_PRIMARY else C.TXT); setPadding(dp(16), 0, dp(16), 0); minHeight = dp(36); minimumHeight = dp(36)
        background = if (on) C.round(C.PRIMARY, dp(18).toFloat()) else C.round(C.CARD, dp(18).toFloat(), if (C.dark) Color.parseColor("#1AFFFFFF") else C.LINE, dp(1))
        setOnClickListener { onClick() }
    }

    // ---------- chips: All · Unread · ⭐ Favourites · Groups · my lists · ＋ ----------
    private fun fillChips(box: LinearLayout) {
        box.removeAllViews()
        val all = db.convs().filter { !hidden(it.cid) }
        val unread = all.count { it.unread > 0 || markedUnread(it.cid) }
        val groups = all.count { it.isGroup }
        val chips = mutableListOf("all" to t("All", "అన్నీ"), "unread" to t("Unread", "చదవనివి") + if (unread > 0) " $unread" else "",
            "fav" to t("Favourites", "ఇష్టమైనవి"), "groups" to t("Groups", "గ్రూప్‌లు") + if (groups > 0) " $groups" else "")
        ChatLists.all(m).forEach { chips.add("list:" + it.id to it.name) }
        chips.forEach { (k, label) ->
            box.addView(pill(label, filter == k) { filter = k; hiddenOpen = false; chipsBox?.let { fillChips(it) }; listBox?.let { fillList(it) } }.apply {
                if (k.startsWith("list:")) setOnLongClickListener { listMenu(k.removePrefix("list:")); true }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        box.addView(Ico.view(m, R.drawable.ic_plus, C.TXT, 18, 36, t("New list", "కొత్త లిస్ట్"), C.round(C.CARD, dp(18).toFloat(), if (C.dark) Color.parseColor("#1AFFFFFF") else C.LINE, dp(1))) { ChatLists.newList(m, emptySet()) { redraw() } },
            LinearLayout.LayoutParams(dp(36), dp(36)))
    }
    private fun listMenu(id: String) {
        val l = ChatLists.all(m).firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(m).setTitle("📋 " + l.name).setItems(arrayOf(t("✏️ Rename", "✏️ పేరు మార్చు"), t("🗑️ Delete this list", "🗑️ ఈ లిస్ట్ తొలగించు"))) { _, i ->
            val lists = ChatLists.all(m)
            if (i == 0) {
                val inp = ui.input(t("List name", "లిస్ట్ పేరు"), l.name)
                AlertDialog.Builder(m).setView(FrameLayout(m).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(inp) }).setPositiveButton(t("Save", "సేవ్")) { _, _ ->
                    val n = inp.text.toString().trim(); if (n.isNotEmpty()) ChatLists.save(m, lists.map { if (it.id == id) ChatLists.L(it.id, n.take(24), it.cids) else it }); redraw()
                }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
            } else { ChatLists.save(m, lists.filter { it.id != id }); if (filter == "list:$id") filter = "all"; redraw() }
        }.show()
    }

    // ---------- small flags kept on this phone ----------
    private fun pinned(cid: String) = db.kv("pin:$cid").let { if (cid == ChatDb.SELF && it.isEmpty()) true else it.isNotEmpty() && it != "0" }
    private fun pinAt(cid: String) = db.kv("pin:$cid").toLongOrNull() ?: 1L
    private fun fav(cid: String) = db.kv("fav:$cid") == "1"
    private fun hidden(cid: String) = db.kv("lock:$cid") == "1"
    private fun markedUnread(cid: String) = db.kv("unr:$cid") == "1"

    // ---------- the list ----------
    private fun fillList(box: LinearLayout) {
        box.removeAllViews()
        when (page) {
            "calls" -> { calls(box); return }
            "updates" -> { updates(box); return }
            "comms" -> { communities(box); return }
        }
        val all = db.convs()
        val locked = all.filter { hidden(it.cid) }
        var pool = all.filter { !hidden(it.cid) }
        if (hiddenOpen) {
            pool = locked
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(6))
                addView(txt("", 16f, C.GOLD_TXT, true).apply { text = Ico.withIcon(m, R.drawable.ic_lock, C.GOLD_TXT, dp(18), t("Locked chats", "లాక్ చేసిన చాట్‌లు")) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(pill(t("Close", "మూసివేయి"), false) { hiddenOpen = false; listBox?.let { fillList(it) } })
            })
        } else if (locked.isNotEmpty() && query.isEmpty() && filter == "all") {
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); background = C.press(m, dp(22).toFloat(), null)
                addView(Ico.view(m, R.drawable.ic_lock, C.SUB, 20, 52, "", C.circle(C.CARD2)), LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(txt(t("Locked chats", "లాక్ చేసిన చాట్‌లు"), 15.5f, C.TXT, true).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(txt(locked.size.toString(), 13f, C.SUB, true))
                addView(Ico.view(m, R.drawable.ic_chevron_right, C.SUB, 18, 28, ""), LinearLayout.LayoutParams(dp(28), dp(28)))
                setOnClickListener { openHidden() }
            })
        }
        val q = query.lowercase()
        if (q.length >= 2 && !hiddenOpen) box.addView(personRow("🐾", t("Ask Habit AI", "Habit AI ని అడగండి"), "\"$query\"", goldMini(t("Ask", "అడుగు")) { askAi(query) }, null, R.drawable.ic_sparkle) { askAi(query) })
        val requests = pool.filter { !it.accepted && it.member && !it.isLocal }
        var chats = pool.filter { it.accepted || !it.member || it.isLocal }
        chats = when {
            filter == "unread" -> chats.filter { it.unread > 0 || markedUnread(it.cid) }
            filter == "groups" -> chats.filter { it.isGroup }
            filter == "fav" -> chats.filter { fav(it.cid) }
            filter.startsWith("list:") -> ChatLists.all(m).firstOrNull { "list:" + it.id == filter }?.let { l -> chats.filter { it.cid in l.cids } } ?: chats
            else -> chats
        }
        if (q.isNotEmpty()) chats = chats.filter { it.name.lowercase().contains(q) || it.preview.lowercase().contains(q) || it.data.optString("handle").lowercase().contains(q) }
        chats = chats.sortedWith(compareByDescending<ChatConv> { pinned(it.cid) }.thenByDescending { if (pinned(it.cid)) pinAt(it.cid) else 0L }.thenByDescending { it.last })
        shownCids = chats.map { it.cid }
        if (requests.isNotEmpty() && q.isEmpty() && filter == "all" && !hiddenOpen) {
            box.addView(heading(t("Requests", "రిక్వెస్ట్‌లు") + " · ${requests.size}"))
            requests.forEach { box.addView(row(it, true)) }
            box.addView(View(m).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(6); bottomMargin = dp(4); marginStart = dp(12); marginEnd = dp(12) })
        }
        chats.forEach { box.addView(row(it, false)) }
        if (chats.isEmpty()) box.addView(txt(when {
            q.isNotEmpty() -> t("No chats with \"$query\".", "\"$query\" తో చాట్‌లు లేవు.")
            filter == "unread" -> t("No unread chats. 🎉", "చదవని చాట్‌లు లేవు. 🎉")
            filter == "groups" -> t("No groups yet. Tap ➕ › New group.", "ఇంకా గ్రూప్‌లు లేవు. ➕ › కొత్త గ్రూప్ నొక్కండి.")
            filter == "fav" -> t("No favourites yet. Long-press a chat, then ⋮ › Add to favourites.", "ఇంకా ఇష్టమైనవి లేవు. చాట్‌ని నొక్కి పట్టి, ⋮ › ఇష్టమైనవిలో చేర్చు నొక్కండి.")
            filter.startsWith("list:") -> t("No chats in this list. Long-press a chat, then ⋮ › Add to list.", "ఈ లిస్ట్‌లో చాట్‌లు లేవు. చాట్‌ని నొక్కి పట్టి, ⋮ › లిస్ట్‌లో చేర్చు నొక్కండి.")
            else -> t("No chats yet. Tap ➕ to start one, or invite your people below.", "ఇంకా చాట్‌లు లేవు. ➕ నొక్కి మొదలుపెట్టండి, లేదా కింద మీ వాళ్ళను ఆహ్వానించండి.")
        }, 14f, C.SUB).apply { setPadding(dp(14), dp(16), dp(14), dp(10)) })
        if (hiddenOpen) return
        if (q.length >= 2) { searchMore(box); return }
        if (filter == "all") invites(box)
    }

    private fun row(c: ChatConv, request: Boolean): View {
        val pin = pinned(c.cid) && !request
        val picked = c.cid in pickedChats
        val r = LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = if (picked) C.round(C.PICK, dp(22).toFloat()) else C.press(m, dp(22).toFloat(), if (pin) C.round(C.ROW_HI, dp(22).toFloat()) else null)
            if (pin && !C.dark && !picked) elevation = dp(1).toFloat()
        }
        r.addView(FrameLayout(m).apply {
            addView(ChatDraw.avatar(m, store, c.emoji, if (c.isDm) c.avatar else "", dp(52), null, c.name, c.isGroup || c.isBcast, c.cid, c.isAi), FrameLayout.LayoutParams(dp(52), dp(52)))
            if (picked) addView(Ico.view(m, R.drawable.ic_tick, C.ON_GOLD, 13, 22, "", C.round(C.GOLD, dp(11).toFloat(), C.BG, dp(2))), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val mid = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        c.comm?.let { k -> mid.addView(txt(k.optString("name") + "  ›", 11.5f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
        val tasks = db.tasks(c.cid).size
        // the name, then "2 tasks" right after it (the name gets shorter, not the chip)
        mid.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(txt((if (c.isAnn) "📢 " else "") + c.name, 16f, C.TXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1f) }, LinearLayout.LayoutParams(-2, -2, 1f))
            if (tasks > 0) addView(txt(if (tasks == 1) t("1 task", "1 పని") else t("$tasks tasks", "$tasks పనులు"), 11.5f, C.GOLD_TXT).apply {
                typeface = Fonts.heavy(m); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(dp(8), 0, dp(8), 0); background = C.round(C.GOLD_SOFT, dp(10).toFloat())
            }, LinearLayout.LayoutParams(-2, dp(20)).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-2, -2))
        val last = if (!request && c.member && !c.isSelf && !c.isAi) db.lastMsg(c.cid) else null
        val me = ChatSync.myUid(m)
        val tick = if (last != null && last.sender == me && !c.isLocal && last.kind != "sys" && last.kind != "clog") when (c.ticks(last.seq, me)) { 3 -> 3; 2 -> 2; 1 -> 1; else -> -1 } else 0
        val typing = ChatSync.typingIn(c.cid).firstOrNull()
        val busy = typing != null || (c.isAi && ChatSync.aiBusy)
        val pv = when {
            typing != null -> (if (c.isGroup) c.nameOf(typing.first).substringBefore(' ') + " " else "") + (if (typing.second == "rec") t("recording audio…", "ఆడియో రికార్డ్ చేస్తున్నారు…") else t("typing…", "టైప్ చేస్తున్నారు…"))
            c.isAi && ChatSync.aiBusy -> t("typing…", "టైప్ చేస్తోంది…")
            !c.member -> t("You left this group", "మీరు ఈ గ్రూప్ వదిలేశారు")
            request && c.isGroup -> t("Added you to a group · tap to see", "మిమ్మల్ని గ్రూప్‌లో చేర్చారు · చూడండి")
            request -> t("Wants to chat · tap to see", "చాట్ చేయాలనుకుంటున్నారు · చూడండి")
            c.preview.isNotEmpty() -> (if (c.isGroup && last != null && last.sender != me && last.kind !in setOf("sys", "clog", "timer")) c.nameOf(last.sender).substringBefore(' ') + ": " else "") + c.preview
            c.isSelf -> t("Notes and reminders for yourself", "మీ కోసం నోట్స్, రిమైండర్లు")
            c.isAi -> t("Ask me anything · reminders, letters, answers", "ఏదైనా అడగండి · రిమైండర్లు, లెటర్లు, జవాబులు")
            c.isBcast -> "📢 " + t("${c.members().size} recipients", "${c.members().size} మందికి")
            else -> t("Say hi!", "హాయ్ చెప్పండి!")
        }
        val unread = c.unread > 0 || markedUnread(c.cid)
        val pvColor = if (unread) C.TXT2 else C.SUB
        mid.addView(TextView(m).apply {
            textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1f); setPadding(0, dp(4), 0, 0)
            if (busy) { text = pv; setTextColor(C.GOLD_TXT); typeface = Fonts.semi(m) }
            else { text = previewLine(pv, if (busy) 0 else tick, pvColor); setTextColor(pvColor); typeface = Fonts.regular(m) }
        })
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val end = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val hot = unread && !c.muted
        if (c.last > 10 && !c.isSelf) end.addView(txt(whenText(c.last), 12f, if (hot) C.GOLD_TXT else C.SUB).apply { gravity = Gravity.END; maxLines = 1; typeface = if (hot) Fonts.bold(m) else Fonts.regular(m); setLineSpacing(0f, 1f) },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        end.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            fun mark(res: Int) = addView(ImageView(m).apply { setImageDrawable(m.getDrawable(res)!!.mutate()); imageTintList = android.content.res.ColorStateList.valueOf(C.SUB) }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(5) })
            if (ChatTheme.disappear(m, c.cid) > 0) mark(R.drawable.ic_timer)
            if (c.muted) mark(R.drawable.ic_bell_off)
            if (pinned(c.cid)) mark(R.drawable.ic_pin)
            if (c.unread > 0) addView(txt(if (c.unread > 99) "99+" else c.unread.toString(), 12f, C.ON_GOLD).apply {
                typeface = Fonts.heavy(m); gravity = Gravity.CENTER; includeFontPadding = false; minWidth = dp(22); setPadding(dp(6), 0, dp(6), 0)
                background = C.round(if (c.muted) C.SUB else C.GOLD, dp(11).toFloat())
            }, LinearLayout.LayoutParams(-2, dp(22)).apply { marginStart = dp(6) })
            else if (markedUnread(c.cid)) addView(View(m).apply { background = ChatTheme.circle(C.GOLD) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-2, dp(22)).apply { topMargin = dp(6) })
        r.addView(end)
        r.setOnClickListener { if (pickedChats.isNotEmpty()) togglePick(c.cid) else { db.setKv("unr:${c.cid}", ""); ChatActivity.open(m, c.cid) } }
        r.setOnLongClickListener { togglePick(c.cid); true }
        return r
    }
    /** The last message in a row: ticks, then an icon for photos, voice notes, places… instead of the emoji. */
    private val pvIcons = listOf("📷 " to R.drawable.ic_image, "🎤 " to R.drawable.ic_mic, "📍 " to R.drawable.ic_map_pin, "📄 " to R.drawable.ic_doc,
        "🎬 " to R.drawable.ic_video, "👤 " to R.drawable.ic_person, "📅 " to R.drawable.ic_calendar, "📊 " to R.drawable.ic_poll, "🏷️ " to R.drawable.ic_sticker,
        "👁 " to R.drawable.ic_eye, "🎞️ " to R.drawable.ic_image, "🚫 " to R.drawable.ic_close, "⏱ " to R.drawable.ic_timer, "📌 " to R.drawable.ic_tasks)
    private fun previewLine(pv: String, tick: Int, color: Int): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        if (tick != 0) sb.append(Ico.withIcon(m, when (tick) { -1 -> R.drawable.ic_clock; 1 -> R.drawable.ic_tick; else -> R.drawable.ic_ticks }, if (tick == 3) C.READ else C.SUB, dp(16), ""))
        var who = ""; var body = pv
        val colon = pv.indexOf(": ")
        if (colon in 1..30 && pvIcons.any { pv.startsWith(it.first, colon + 2) }) { who = pv.substring(0, colon + 2); body = pv.substring(colon + 2) }
        val hit = pvIcons.firstOrNull { body.startsWith(it.first) }
        if (hit != null) { sb.append(Ico.withIcon(m, hit.second, if (hit.second == R.drawable.ic_mic) C.GOLD_TXT else color, dp(15), "")); sb.append(who).append(body.removePrefix(hit.first)) }
        else sb.append(pv)
        return sb
    }

    private fun whenText(at: Long): String {
        val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return when (z.toLocalDate()) {
            LocalDate.now() -> z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            LocalDate.now().minusDays(1) -> t("Yesterday", "నిన్న")
            else -> z.format(DateTimeFormatter.ofPattern("d/M/yy", Locale.ENGLISH))
        }
    }

    // ---------- picking chats with a long-press, like WhatsApp ----------
    // The picked chats light up (✓ on the picture); a bar stays at the top of the screen with what to do:
    // ← · how many · 📌 pin · 🗑 delete · 🔕 mute · ⋮ more. Tap other chats to pick them too; ← or Back stops.
    private val pickedChats = LinkedHashSet<String>()
    private var shownCids: List<String> = emptyList()
    /** True while chats are picked (Back stops picking first). */
    val picking get() = pickedChats.isNotEmpty()
    private fun togglePick(cid: String) { if (!pickedChats.remove(cid)) pickedChats.add(cid); afterPick() }
    private fun afterPick() {
        listBox?.let { fillList(it) }
        m.setTopBar(if (pickedChats.isEmpty()) null else pickBar())
        m.backChanged()
    }
    /** Back while picking: stop picking (true). */
    fun back(): Boolean { if (pickedChats.isEmpty()) return false; pickedChats.clear(); afterPick(); return true }
    /** Leaving Habit Chat for another page: stop picking. */
    fun leave() { if (pickedChats.isNotEmpty()) { pickedChats.clear(); m.setTopBar(null) } }
    private fun donePicking() { pickedChats.clear(); m.setTopBar(null); m.backChanged(); redraw() }
    private fun pickedConvs() = pickedChats.mapNotNull { db.conv(it) }

    private fun pickBar(): View {
        val cs = pickedConvs()
        val bar = LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(2), dp(4)); minimumHeight = dp(62)
            setBackgroundColor(C.BAR); elevation = dp(3).toFloat(); isClickable = true
        }
        bar.addView(icon(R.drawable.ic_back, t("Stop picking", "ఎంచుకోవడం ఆపు")) { back() })
        bar.addView(txt(cs.size.toString(), 20f, C.TXT, true).apply { setPadding(dp(12), 0, 0, 0); setLineSpacing(0f, 1f) }, LinearLayout.LayoutParams(0, -2, 1f))
        val allPinned = cs.isNotEmpty() && cs.all { pinned(it.cid) }
        bar.addView(icon(if (allPinned) R.drawable.ic_pin_off else R.drawable.ic_pin, if (allPinned) t("Unpin", "పిన్ తీసేయి") else t("Pin", "పిన్ చేయి")) { pinPicked(!allPinned) })
        bar.addView(icon(R.drawable.ic_trash, t("Delete", "తొలగించు")) { deletePicked() })
        val canMute = cs.filter { !it.isLocal }
        if (canMute.isNotEmpty()) {
            val allMuted = canMute.all { it.muted }
            bar.addView(icon(if (allMuted) R.drawable.ic_bell else R.drawable.ic_bell_off, if (allMuted) t("Unmute", "శబ్దం ఆన్") else t("Mute", "శబ్దం ఆఫ్")) {
                canMute.forEach { db.setMuted(it.cid, !allMuted) }; donePicking() })
        }
        bar.addView(icon(R.drawable.ic_more, t("More", "ఇంకా")) { pickMore(it) })
        return bar
    }
    private fun pinPicked(on: Boolean) {
        val cs = pickedConvs()
        if (on) {
            val add = cs.filter { !pinned(it.cid) }
            if (db.convs().count { pinned(it.cid) } + add.size > 5) { m.toast(t("You can pin up to 5 chats.", "5 చాట్‌ల వరకు పిన్ చేయవచ్చు.")); return }
            var at = System.currentTimeMillis()
            add.forEach { db.setKv("pin:${it.cid}", (at++).toString()) }
        } else cs.forEach { db.setKv("pin:${it.cid}", "0") }
        donePicking()
    }
    private fun deletePicked() {
        val cs = pickedConvs()
        if (cs.isEmpty()) return
        if (cs.size == 1) { donePicking(); deleteChat(cs[0]); return }
        AlertDialog.Builder(m).setMessage(t("Delete these ${cs.size} chats from this phone? (You also leave the groups among them.)", "ఈ ${cs.size} చాట్‌లు ఈ ఫోన్ నుంచి తొలగించాలా? (వాటిలోని గ్రూప్‌ల నుంచి కూడా వెళ్ళిపోతారు.)"))
            .setPositiveButton(t("Delete", "తొలగించు")) { _, _ -> donePicking(); cs.forEach { removeChat(it) } }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** ⋮ while picking: favourites, lists, read / unread, lock, theme, block, select all. */
    private fun pickMore(anchor: View) {
        val cs = pickedConvs()
        if (cs.isEmpty()) return
        val one = cs.singleOrNull()
        val items = ArrayList<Pair<String, () -> Unit>>()
        val allFav = cs.all { fav(it.cid) }
        items.add((if (allFav) t("Remove from favourites", "ఇష్టమైనవి నుంచి తీసేయి") else t("Add to favourites", "ఇష్టమైనవిలో చేర్చు")) to {
            cs.forEach { db.setKv("fav:${it.cid}", if (allFav) "" else "1") }; donePicking() })
        if (one != null) items.add(t("Add to list", "లిస్ట్‌లో చేర్చు") to { donePicking(); ChatLists.pick(m, one.cid) { redraw() } })
        val anyUnread = cs.any { it.unread > 0 || markedUnread(it.cid) }
        items.add((if (anyUnread) t("Mark as read", "చదివినట్టు") else t("Mark as unread", "చదవనట్టు")) to {
            cs.forEach { if (anyUnread) { db.readAll(it.cid); db.setKv("unr:${it.cid}", "") } else db.setKv("unr:${it.cid}", "1") }; donePicking() })
        val lockable = cs.filter { !it.isSelf && !it.isAi }
        if (lockable.isNotEmpty()) {
            val allHidden = lockable.all { hidden(it.cid) }
            items.add((if (allHidden) t("Show in the list again", "మళ్ళీ లిస్ట్‌లో చూపించు") else t("Lock chat (hide)", "చాట్ లాక్ (దాచు)")) to {
                lockable.forEach { db.setKv("lock:${it.cid}", if (allHidden) "" else "1") }
                if (!allHidden) m.toast(t("Locked. Open it from 🔒 Locked chats at the top of the list.", "లాక్ చేశాం. లిస్ట్ పైన 🔒 లాక్ చేసిన చాట్‌లు నుంచి తెరవండి."))
                donePicking()
            })
        }
        if (one != null) items.add(t("Chat theme", "చాట్ థీమ్") to { donePicking(); pickWall(one.cid) })
        if (one != null && one.isDm) items.add((if (one.blocked) t("Unblock", "అన్‌బ్లాక్") else t("Block", "బ్లాక్")) to { donePicking(); block(one) })
        if (shownCids.any { it !in pickedChats }) items.add(t("Select all", "అన్నీ ఎంచుకో") to { pickedChats.addAll(shownCids); afterPick() })
        val pm = android.widget.PopupMenu(m, anchor)
        items.forEachIndexed { i, it -> pm.menu.add(0, i, i, it.first) }
        pm.setOnMenuItemClickListener { mi -> items[mi.itemId].second(); true }
        pm.show()
    }
    private fun block(c: ChatConv) {
        val other = c.otherUid(ChatSync.myUid(m))
        Thread {
            val r = ChatSync.api(store, "POST", "/api/chat/block", JSONObject().put("uid", other).put("on", !c.blocked).toString())
            m.runOnUiThread { if (r.ok) { db.patchConv(c.cid) { it.put("blocked", !c.blocked) }; m.toast(if (c.blocked) t("Unblocked", "అన్‌బ్లాక్ చేశాం") else t("Blocked", "బ్లాక్ చేశాం")); redraw() } else m.toast(r.error("Couldn't do it.")) }
        }.start()
    }

    private fun deleteChat(c: ChatConv) {
        val msg = when {
            c.isGroup && c.member -> t("Exit \"${c.name}\" and delete it from this phone?", "\"${c.name}\" నుంచి వెళ్ళిపోయి, ఈ ఫోన్ నుంచి తొలగించాలా?")
            c.isSelf -> t("Clear all your notes here? Open reminders stay on your phone's alarms.", "ఇక్కడి నోట్స్ అన్నీ క్లియర్ చేయాలా?")
            c.isAi -> t("Clear your chat with Habit AI?", "Habit AI తో చాట్ క్లియర్ చేయాలా?")
            c.isBcast -> t("Delete this broadcast list? Messages already sent stay in each person's chat.", "ఈ బ్రాడ్‌కాస్ట్ లిస్ట్ తొలగించాలా? పంపినవి వాళ్ళ చాట్‌లలో ఉంటాయి.")
            else -> t("Delete this chat from this phone? (A new message from them brings it back.)", "ఈ ఫోన్ నుంచి ఈ చాట్ తొలగించాలా? (వాళ్ళ కొత్త మెసేజ్ వస్తే మళ్ళీ వస్తుంది.)")
        }
        AlertDialog.Builder(m).setMessage(msg).setPositiveButton(t("Delete", "తొలగించు")) { _, _ -> removeChat(c) }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** Deletes a chat from this phone (and leaves a group first). */
    private fun removeChat(c: ChatConv) {
        when {
            c.isGroup && c.member -> Thread {
                val r = ChatSync.api(store, "POST", "/api/chat/leave", JSONObject().put("cid", c.cid).toString())
                m.runOnUiThread { if (r.ok) { db.patchConv(c.cid) { it.put("member", false) }; db.clear(c.cid, true); redraw() } else m.toast(r.error("Couldn't leave the group.")) }
            }.start()
            c.isBcast -> { db.dropConv(c.cid); redraw() }
            c.isSelf || c.isAi -> { db.clear(c.cid, false); if (c.isAi) ChatSync.aiForget(); redraw() }
            else -> { db.clear(c.cid, true); redraw() }
        }
    }

    fun pickWall(cid: String) {
        val walls = ChatTheme.WALLS
        val cur = if (cid.isEmpty()) db.kv("set:wall").toIntOrNull() ?: ChatTheme.DEFAULT_WALL else ChatTheme.wallOf(m, cid)
        AlertDialog.Builder(m).setTitle(t("🎨 Chat theme", "🎨 చాట్ థీమ్"))
            .setSingleChoiceItems(walls.map { (if (it.id == cur) "✓ " else "") + if (te) it.te else it.en }.toTypedArray(), walls.indexOfFirst { it.id == cur }) { d, i ->
                db.setKv(if (cid.isEmpty()) "set:wall" else "wall:$cid", walls[i].id.toString()); d.dismiss(); m.toast(t("Theme changed 🎨", "థీమ్ మార్చాం 🎨"))
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }

    /** Locked chats open with the fingerprint or phone lock (if that's on in chat settings). */
    private fun openHidden() {
        ChatTheme.unlockHidden(m) { err -> if (err == null) { hiddenOpen = true; page = "chats"; filter = "all"; redraw() } else m.toast(err) }
    }

    // ---------- 🐾 Ask Habit AI ----------
    private fun askAi(q: String) {
        db.ensureAi()
        if (q.isNotBlank()) ChatSync.send(m, store, ChatDb.AI, JSONObject().put("t", "text").put("text", q.take(2000)))
        query = ""
        ChatActivity.open(m, ChatDb.AI)
    }

    // ---------- ⭕ Updates: status updates ----------
    private fun updates(box: LinearLayout) {
        ChatStatus.load(m)
        if (System.currentTimeMillis() - statusAt > 60_000) { statusAt = System.currentTimeMillis(); Thread { ChatStatus.refresh(m, store) }.start() }
        box.addView(heading(t("Status", "స్టేటస్")))
        val mine = ChatStatus.mine
        val me = ChatSync.me(m)
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(8), dp(10)); background = C.press(m, dp(22).toFloat(), null)
            addView(FrameLayout(m).apply {
                addView(ChatDraw.avatar(m, store, me.optString("emoji", "🐾"), me.optString("avatar"), dp(54), if (mine.isNotEmpty()) C.GOLD else null, me.optString("name"), key = me.optString("uid")), FrameLayout.LayoutParams(dp(54), dp(54)))
                if (mine.isEmpty()) addView(Ico.view(m, R.drawable.ic_plus, C.ON_PRIMARY_ICON, 14, 22, "", C.circle(C.PRIMARY)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END))
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0)
                addView(txt(t("My status", "నా స్టేటస్"), 16f, C.TXT, true))
                addView(txt(if (mine.isEmpty()) t("Tap to add a status update", "స్టేటస్ పెట్టడానికి నొక్కండి") else t("${mine.size} updates · ", "${mine.size} అప్‌డేట్స్ · ") + ChatStatus.ago(mine.last().item.at, te) + " · 👁 " + mine.sumOf { it.views.size }, 13f, C.SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_edit, t("Text status", "టెక్స్ట్ స్టేటస్")) { StatusActivity.write(m) })
            addView(icon(R.drawable.ic_camera, t("Photo or video status", "ఫోటో / వీడియో స్టేటస్")) { StatusActivity.media(m) })
            setOnClickListener { if (mine.isEmpty()) newStatus() else StatusActivity.watch(m, "me") }
        })
        val muted = ChatStatus.muted(m)
        val all = ChatStatus.others.sortedByDescending { it.last }
        val recent = all.filter { it.unseen && it.uid !in muted }
        val viewed = all.filter { !it.unseen && it.uid !in muted }
        val mutedList = all.filter { it.uid in muted }
        if (recent.isNotEmpty()) { box.addView(heading(t("Recent updates", "కొత్త అప్‌డేట్స్"))); recent.forEach { box.addView(statusRow(it, C.GOLD)) } }
        if (viewed.isNotEmpty()) { box.addView(heading(t("Viewed updates", "చూసిన అప్‌డేట్స్"))); viewed.forEach { box.addView(statusRow(it, C.LINE)) } }
        if (mutedList.isNotEmpty()) { box.addView(heading(t("Muted updates", "మ్యూట్ చేసినవి"))); mutedList.forEach { box.addView(statusRow(it, C.LINE).apply { alpha = 0.55f }) } }
        if (all.isEmpty()) box.addView(txt(t("No updates from your people yet. Status updates stay for 24 hours and are locked end to end.", "మీ వాళ్ళ నుంచి ఇంకా అప్‌డేట్స్ లేవు. స్టేటస్ 24 గంటలు ఉంటుంది, ఎండ్ టు ఎండ్ లాక్."), 13.5f, C.SUB).apply { setPadding(dp(14), dp(14), dp(14), dp(6)) })
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), 0, 0)
            addView(pill(t("Status privacy", "స్టేటస్ గోప్యత"), false) { ChatStatus.privacyDialog(m, store) }.apply { setCompoundDrawablesRelative(Ico.d(m, R.drawable.ic_lock, C.TXT, dp(16)), null, null, null); compoundDrawablePadding = dp(6) })
        })
        // channels: Habit Media is where people post publicly
        box.addView(heading(t("Channels", "ఛానెల్స్")))
        box.addView(personRow("📺", "Habit Media", t("Posts, videos and news from people you follow", "మీరు ఫాలో అయ్యేవాళ్ళ పోస్ట్‌లు, వీడియోలు, వార్తలు"), goldMini(t("Open", "తెరువు")) { m.go("feedback") }, null, R.drawable.ic_media) { m.go("feedback") })
    }
    private fun statusRow(o: ChatStatus.Owner, ring: Int): LinearLayout = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9)); background = C.press(m, dp(22).toFloat(), null)
        addView(ChatDraw.avatar(m, store, o.emoji, o.avatar, dp(52), ring, o.name, key = o.uid), LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0)
            addView(txt(o.name, 16f, C.TXT, true))
            addView(txt(ChatStatus.ago(o.last, te) + if (o.items.size > 1) " · ${o.items.size}" else "", 13f, C.SUB))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { StatusActivity.watch(m, o.uid) }
        setOnLongClickListener {
            val muted = o.uid in ChatStatus.muted(m)
            AlertDialog.Builder(m).setItems(arrayOf(if (muted) t("🔔 Unmute their updates", "🔔 అన్‌మ్యూట్") else t("🔕 Mute their updates", "🔕 మ్యూట్"))) { _, _ -> ChatStatus.mute(m, o.uid, !muted); redraw() }.show(); true
        }
    }
    private fun newStatus() {
        AlertDialog.Builder(m).setTitle(t("⭕ New status (24 hours)", "⭕ కొత్త స్టేటస్ (24 గంటలు)"))
            .setItems(arrayOf(t("✏️ Text on a colour", "✏️ రంగు మీద మాటలు"), t("🖼️ Photo or video", "🖼️ ఫోటో లేదా వీడియో"), t("🔒 Status privacy", "🔒 స్టేటస్ గోప్యత"))) { _, i ->
                when (i) { 0 -> StatusActivity.write(m); 1 -> StatusActivity.media(m); else -> ChatStatus.privacyDialog(m, store) }
            }.show()
    }

    // ---------- 🏘️ Communities ----------
    private fun communities(box: LinearLayout) {
        if (comms == null || System.currentTimeMillis() - commsAt > 60_000 || db.kv("comms:fresh") == "0") {
            commsAt = System.currentTimeMillis(); db.setKv("comms:fresh", "1")
            Thread { val l = Communities.load(store); m.runOnUiThread { if (l != null) { comms = l; if (page == "comms") listBox?.let { fillList(it) } } } }.start()
        }
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = C.press(m, dp(22).toFloat(), C.round(C.CARD, dp(22).toFloat(), C.EDGE, dp(1)))
            val (tb, tf) = C.TINTS[1]
            addView(Ico.view(m, R.drawable.ic_community, tf, 24, 48, "", C.round(tb, dp(16).toFloat())), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                addView(txt(t("New community", "కొత్త కమ్యూనిటీ"), 16f, C.TXT, true))
                addView(txt(t("Bring your groups together, with one announcements group for everyone (e.g. all your office groups).", "మీ గ్రూప్‌లను ఒకచోట చేర్చండి, అందరికీ ఒక ప్రకటనల గ్రూప్‌తో (ఉదా: మీ ఆఫీస్ గ్రూప్‌లు అన్నీ)."), 13f, C.SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { Communities.create(m, store) { redraw() } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); marginStart = dp(8); marginEnd = dp(8) })
        val list = comms
        if (list == null) { box.addView(txt("⏳", 18f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) }); return }
        if (list.isEmpty()) { box.addView(txt(t("You're not in any community yet.", "మీరు ఇంకా ఏ కమ్యూనిటీలో లేరు."), 14f, C.SUB).apply { setPadding(dp(14), dp(16), dp(14), dp(6)) }); return }
        for (k in list) {
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(10), dp(8)); background = C.round(C.CARD, dp(22).toFloat(), C.EDGE, dp(1))
                addView(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(txt(k.optString("emoji", "🏘️"), 24f).apply { gravity = Gravity.CENTER; background = C.round(C.tint(k.optString("id")).first, dp(16).toFloat()) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(LinearLayout(m).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                        addView(txt(k.optString("name"), 17f, C.TXT, true))
                        addView(txt(t("${k.optInt("people")} people · ${k.optJSONArray("groups")?.length() ?: 0} groups", "${k.optInt("people")} మంది · ${k.optJSONArray("groups")?.length() ?: 0} గ్రూప్‌లు") + if (k.optBoolean("admin")) t(" · you're an admin", " · మీరు అడ్మిన్") else "", 12.5f, C.SUB))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(icon(R.drawable.ic_more, t("Community", "కమ్యూనిటీ"), C.SUB) { Communities.show(m, store, k.optString("id")) })
                })
                val ann = k.optString("ann")
                if (ann.isNotEmpty()) addView(subRow("📢", t("Announcements", "ప్రకటనలు"), db.conv(ann)?.preview ?: "") { if (db.conv(ann) != null) ChatActivity.open(m, ann) else m.toast(t("It's coming… try again in a moment.", "వస్తోంది… కాసేపట్లో మళ్ళీ ప్రయత్నించండి.")) })
                val gs = k.optJSONArray("groups") ?: JSONArray()
                for (i in 0 until gs.length()) {
                    val g = gs.optJSONObject(i) ?: continue
                    addView(subRow(g.optString("emoji", "👥"), g.optString("name"), if (g.optBoolean("joined")) (db.conv(g.optString("cid"))?.preview ?: "") else t("${g.optInt("n")} people · tap to join", "${g.optInt("n")} మంది · చేరడానికి నొక్కండి")) {
                        if (g.optBoolean("joined") && db.conv(g.optString("cid")) != null) ChatActivity.open(m, g.optString("cid")) else Communities.join(m, store, k.optString("id"), g)
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); marginStart = dp(8); marginEnd = dp(8) })
        }
    }
    private fun subRow(icon: String, name: String, sub: String, onClick: () -> Unit) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(6))
        addView(txt(icon, 19f).apply { gravity = Gravity.CENTER; background = C.round(C.CARD2, dp(14).toFloat()) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(txt(name, 15f, C.TXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (sub.isNotBlank()) addView(txt(sub, 12.5f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }
    }

    // ---------- 📞 Calls: every Habit call, newest first ----------
    private fun calls(box: LinearLayout) {
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), 0, dp(4))
            addView(pill(t("New call", "కొత్త కాల్"), true) { newCall() }.apply { setCompoundDrawablesRelative(Ico.d(m, R.drawable.ic_phone, C.ON_PRIMARY, dp(16)), null, null, null); compoundDrawablePadding = dp(6) })
            addView(pill(t("Phone contact", "ఫోన్ కాంటాక్ట్"), false) { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "contact")) }.apply { setCompoundDrawablesRelative(Ico.d(m, R.drawable.ic_person, C.TXT, dp(16)), null, null, null); compoundDrawablePadding = dp(6) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })
        val favs = db.convs().filter { it.isDm && fav(it.cid) && it.accepted && !it.blocked }
        if (favs.isNotEmpty()) {
            box.addView(heading(t("Favourites", "ఇష్టమైనవి")))
            for (c in favs) box.addView(personRow(c.emoji, c.name, "", icon(R.drawable.ic_video, t("Video call", "వీడియో కాల్")) { callChat(c.cid, true) }, icon(R.drawable.ic_phone, t("Voice call", "వాయిస్ కాల్")) { callChat(c.cid, false) }) { ChatActivity.open(m, c.cid) })
        }
        box.addView(heading(t("Recent", "ఇటీవలివి")))
        val logs = db.callLogs(150)
        if (logs.isEmpty()) { box.addView(txt(t("No calls yet. Tap New call, or open a chat and tap the phone or video button.", "ఇంకా కాల్స్ లేవు. కొత్త కాల్ నొక్కండి, లేదా చాట్ తెరిచి ఫోన్ / వీడియో బటన్ నొక్కండి."), 14f, C.SUB).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }); return }
        val me = ChatSync.myUid(m)
        for (l in logs) {
            val c = db.conv(l.cid) ?: continue
            if (hidden(c.cid) && !hiddenOpen) continue
            val b = l.body
            val missed = b.optString("st") == "missed"
            val out = b.optBoolean("out") || l.sender == me
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(9), dp(8), dp(9)); background = C.press(m, dp(22).toFloat(), null)
                addView(ChatDraw.avatar(m, store, c.emoji, if (c.isDm) c.avatar else "", dp(48), null, c.name, c.isGroup || c.isBcast, c.cid, c.isAi), LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(LinearLayout(m).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, dp(8), 0)
                    addView(txt(c.name, 16f, if (missed) C.DANGER else C.TXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(txt((if (missed) "↙ " else if (out) "↗ " else "↙ ") + CallManager.logText(m, l).substringAfter(' ') + " · " + whenText(l.at), 13f, if (missed) C.DANGER else C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (c.isDm && c.member && c.accepted && !c.blocked) addView(icon(if (b.optBoolean("video")) R.drawable.ic_video else R.drawable.ic_phone, t("Call back", "మళ్ళీ కాల్"), C.GOLD_TXT) { callChat(c.cid, b.optBoolean("video")) })
                setOnClickListener { ChatActivity.open(m, c.cid) }
            })
        }
    }
    private fun callChat(cid: String, video: Boolean) = m.startActivity(Intent(m, ChatActivity::class.java).putExtra("cid", cid).putExtra("call", if (video) "video" else "voice"))
    private fun newCall() {
        val dms = db.convs().filter { it.isDm && it.accepted && !it.blocked && it.member }.sortedBy { it.name.lowercase() }
        if (dms.isEmpty()) { m.toast(t("Start a chat first: calls are with people you chat with.", "ముందు చాట్ మొదలుపెట్టండి: మీరు చాట్ చేసేవాళ్ళకే కాల్ చేయవచ్చు.")); return }
        AlertDialog.Builder(m).setTitle(t("📞 Call…", "📞 ఎవరికి కాల్?")).setItems(dms.map { it.emoji + "  " + it.name }.toTypedArray()) { _, i ->
            AlertDialog.Builder(m).setTitle(dms[i].name).setItems(arrayOf(t("📞 Voice call", "📞 వాయిస్ కాల్"), t("🎥 Video call", "🎥 వీడియో కాల్"))) { _, k -> callChat(dms[i].cid, k == 1) }.show()
        }.show()
    }

    // ---------- search: Habit people and phone contacts ----------
    private fun findPeople(q: String) {
        Thread {
            val r = ChatSync.api(store, "GET", "/api/chat/find?q=" + android.net.Uri.encode(q))
            m.runOnUiThread { if (q == query) { people = r.json.optJSONArray("people") ?: JSONArray(); peopleQ = q; listBox?.let { fillList(it) } } }
        }.start()
    }

    private fun searchMore(box: LinearLayout) {
        val known = db.convs().flatMap { c -> c.members().map { it.optString("uid") } }.toSet()
        val ppl = if (peopleQ == query) people else null
        val list = (0 until (ppl?.length() ?: 0)).mapNotNull { ppl?.optJSONObject(it) }.filter { it.optString("uid") !in known }
        if (list.isNotEmpty()) {
            box.addView(heading(t("On Habit", "Habit లో ఉన్నవాళ్ళు")))
            for (p in list) box.addView(personRow(p.optString("emoji", "🐾"), p.optString("name"), "@" + p.optString("handle"),
                goldMini(t("Chat", "చాట్")) { openWith(p.optString("uid")) }, null) { openWith(p.optString("uid")) })
        }
        box.addView(heading(t("Phone contacts", "ఫోన్ కాంటాక్ట్‌లు")))
        if (!ChatContacts.allowed(m)) { allowContacts(box); return }
        val found = ChatContacts.search(m, query)
        if (found.isEmpty()) box.addView(txt(t("No contact with \"$query\".", "\"$query\" తో కాంటాక్ట్ లేదు."), 14f, C.SUB).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) })
        for (p in found) box.addView(contactRow(p))
    }

    // ---------- invite your people ----------
    private fun invites(box: LinearLayout) {
        box.addView(heading(t("Invite your people", "మీ వాళ్ళను ఆహ్వానించండి")))
        if (!ChatContacts.allowed(m)) { allowContacts(box); return }
        val ppl = ChatContacts.suggested(m, 8)
        if (ppl.isEmpty()) box.addView(txt(t("Star (⭐) your close people in the phone's Contacts app and they show here. Find anyone else with the search bar above.",
            "ఫోన్ కాంటాక్ట్స్ యాప్‌లో మీ దగ్గరి వాళ్ళకు ⭐ పెట్టండి, వాళ్ళు ఇక్కడ కనిపిస్తారు. మిగతావాళ్ళను పైన సెర్చ్‌లో వెతకండి."), 13.5f, C.SUB).apply { setPadding(dp(14), dp(4), dp(14), dp(6)) })
        for (p in ppl) box.addView(contactRow(p))
        box.addView(txt("", 13f, C.SUB).apply { text = Ico.withIcon(m, R.drawable.ic_search, C.SUB, dp(15), t("Anyone else: type their name in the search bar.", "మిగతావాళ్ళు: పైన సెర్చ్‌లో పేరు టైప్ చేయండి.")); setPadding(dp(14), dp(8), dp(14), 0) })
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), 0, 0)
            addView(pill(t("Share my invite link", "నా ఆహ్వాన లింక్ పంపు"), false) { shareInvite() }.apply { setCompoundDrawablesRelative(Ico.d(m, R.drawable.ic_forward, C.TXT, dp(16)), null, null, null); compoundDrawablePadding = dp(6) })
        })
    }
    private fun allowContacts(box: LinearLayout) {
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = C.round(C.CARD, dp(22).toFloat(), C.EDGE, dp(1))
            addView(txt(t("See your contacts here to invite or call them. They stay on your phone: nothing is uploaded.",
                "ఆహ్వానించడానికి, కాల్ చేయడానికి మీ కాంటాక్ట్‌లు ఇక్కడ చూడండి. అవి మీ ఫోన్‌లోనే ఉంటాయి: ఏదీ అప్‌లోడ్ కాదు."), 14f, C.TXT))
            addView(goldButton(t("Allow contacts", "కాంటాక్ట్‌లకు అనుమతి")) { m.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), MainActivity.REQ_CHAT_CONTACTS) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); marginStart = dp(8); marginEnd = dp(8) })
    }
    private fun heading(s: String) = txt(s.uppercase(Locale.getDefault()), 12f, C.GOLD_TXT).apply { typeface = Fonts.heavy(m); letterSpacing = 0.07f; setPadding(dp(14), dp(18), dp(14), dp(6)) }
    private fun goldMini(label: String, onClick: () -> Unit) = TextView(m).apply {
        text = label; textSize = 13.5f; setTextColor(C.ON_PRIMARY); typeface = Fonts.heavy(context); gravity = Gravity.CENTER; includeFontPadding = false
        setPadding(dp(16), dp(9), dp(16), dp(9)); background = C.round(C.PRIMARY, dp(18).toFloat()); setOnClickListener { onClick() }
    }
    private fun personRow(emoji: String, name: String, sub: String, button: View?, second: View?, iconRes: Int = 0, onClick: (() -> Unit)?) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(9), dp(10), dp(9)); if (onClick != null) background = C.press(m, dp(22).toFloat(), null)
        if (iconRes != 0) { val (tb, tf) = C.TINTS[4]; addView(Ico.view(m, iconRes, tf, 22, 46, "", C.circle(tb)), LinearLayout.LayoutParams(dp(46), dp(46))) }
        else addView(ChatDraw.avatar(m, store, emoji, "", dp(46), null, name.removePrefix("⭐ ")), LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
            addView(txt(name, 15.5f, C.TXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (sub.isNotBlank()) addView(txt(sub, 12.5f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (second != null) addView(second)
        if (button != null) addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        if (onClick != null) setOnClickListener { onClick() }
    }
    private fun contactRow(p: ChatContacts.Person): View {
        return personRow("", (if (p.starred) "⭐ " else "") + p.name, p.number,
            goldMini(t("Invite", "ఆహ్వానించు")) { ChatContacts.invite(m, store, p) },
            icon(R.drawable.ic_phone, t("Phone call", "ఫోన్ కాల్")) { ChatContacts.call(m, p) }, onClick = null)
    }

    // ---------- the ⋮ menu and the ➕ button ----------
    private fun topMenu(anchor: View) {
        val pm = android.widget.PopupMenu(m, anchor)
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, f: () -> Unit) { pm.menu.add(0, acts.size, acts.size, label); acts.add(f) }
        add(t("👥 New group", "👥 కొత్త గ్రూప్")) { newGroup() }
        add(t("🏘️ New community", "🏘️ కొత్త కమ్యూనిటీ")) { Communities.create(m, store) { page = "comms"; redraw() } }
        add(t("📢 New broadcast", "📢 కొత్త బ్రాడ్‌కాస్ట్")) { Broadcasts.create(m, store) }
        add(t("⭐ Starred messages", "⭐ స్టార్ చేసిన మెసేజ్‌లు")) { ChatInfoActivity.open(m, "", "starred") }
        add(t("🔳 My QR code / invite link", "🔳 నా QR కోడ్ / ఆహ్వాన లింక్")) { myQr() }
        add(t("💻 Linked devices (computer)", "💻 లింక్ చేసిన పరికరాలు (కంప్యూటర్)")) { LinkedDevicesActivity.open(m) }
        add(t("✓ Read all", "✓ అన్నీ చదివినట్టు")) { db.convs().forEach { db.readAll(it.cid); db.setKv("unr:${it.cid}", "") }; redraw() }
        add("🔒 " + t("Locked chats", "లాక్ చేసిన చాట్‌లు")) { openHidden() }
        add(t("⚙️ Settings", "⚙️ సెట్టింగ్స్")) { m.startActivity(Intent(m, ChatSettingsActivity::class.java)) }
        pm.setOnMenuItemClickListener { acts[it.itemId](); true }
        pm.show()
    }

    /** The round ➕ button (bottom right): what fits the open tab. */
    fun plusMenu() {
        if (!ChatSync.isSetUp(m)) { start(); return }
        when (page) {
            "updates" -> { newStatus(); return }
            "comms" -> { Communities.create(m, store) { redraw() }; return }
            "calls" -> { newCall(); return }
        }
        val items = listOf<Pair<String, () -> Unit>>(
            t("👤 New chat (Habit people)", "👤 కొత్త చాట్ (Habit వ్యక్తులు)") to { newChat() },
            t("👥 New group", "👥 కొత్త గ్రూప్") to { newGroup() },
            t("🏘️ New community", "🏘️ కొత్త కమ్యూనిటీ") to { Communities.create(m, store) { page = "comms"; redraw() } },
            t("📢 New broadcast", "📢 కొత్త బ్రాడ్‌కాస్ట్") to { Broadcasts.create(m, store) },
            t("🐾 Ask Habit AI", "🐾 Habit AI ని అడగండి") to { askAi("") },
            t("📞 Call or invite a phone contact", "📞 ఫోన్ కాంటాక్ట్‌కి కాల్ / ఆహ్వానం") to { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "contact")) },
            t("🔳 My QR code / invite link", "🔳 నా QR కోడ్ / ఆహ్వాన లింక్") to { myQr() },
            t("📷 Scan a QR code", "📷 QR కోడ్ స్కాన్") to { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "scan")) },
        )
        AlertDialog.Builder(m).setTitle(t("➕ New", "➕ కొత్తది")).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun shareInvite() {
        val text = ChatContacts.inviteText(m, store, "")
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), t("Share invite", "ఆహ్వానం పంపండి"))) } catch (e: Exception) { }
    }

    // ---------- starting: a name (the Habit profile) and this phone's key ----------
    private fun start() {
        starting = true; redraw()
        Thread {
            val e = ChatSync.setup(m, store)
            m.runOnUiThread {
                starting = false
                when (e) {
                    null -> { ChatAlarm.schedule(m); ChatSync.hold(m, "main"); m.render(); askNotifications() }
                    "profile" -> askName()
                    else -> { m.toast(e); redraw() }
                }
            }
        }.start()
    }
    private fun askName() {
        val box = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        box.addView(ui.text(t("Your name, as your friends will see it. You also get an @name to share.", "మీ స్నేహితులకు కనిపించే మీ పేరు. పంచుకోవడానికి ఒక @పేరు కూడా వస్తుంది."), 13.5f, Ui.MUTED))
        val name = ui.input(t("Your name", "మీ పేరు"))
        box.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        AlertDialog.Builder(m).setTitle(t("💬 Your name", "💬 మీ పేరు")).setView(box).setPositiveButton(t("Save", "సేవ్")) { _, _ ->
            val n = name.text.toString().trim()
            if (n.length < 2) { m.toast(t("Write your name (at least 2 letters).", "మీ పేరు రాయండి.")); return@setPositiveButton }
            starting = true; redraw()
            Thread {
                val r = ChatSync.api(store, "POST", "/api/social/me", JSONObject().put("name", n).toString())
                m.runOnUiThread { starting = false; if (r.ok) start() else { m.toast(r.error("Couldn't save your name.")); redraw() } }
            }.start()
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !m.granted(android.Manifest.permission.POST_NOTIFICATIONS))
            m.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), MainActivity.REQ_NOTIFY)
    }

    // ---------- new chat, new group ----------
    private fun newChat() = ChatPeople.pick(m, store, t("➕ New chat", "➕ కొత్త చాట్"), multi = false) { ppl -> openWith(ppl.first().optString("uid")) }
    private fun openWith(uid: String) {
        Thread {
            val r = ChatSync.api(store, "POST", "/api/chat/open", JSONObject().put("uid", uid).toString())
            m.runOnUiThread {
                val conv = r.json.optJSONObject("conv")
                if (!r.ok || conv == null) { m.toast(r.error("Couldn't open the chat.")); return@runOnUiThread }
                ChatDb.get(m).putConv(conv)
                // a new chat starts with disappearing messages on, if chosen in settings
                val cid = conv.optString("cid")
                ChatTheme.defaultTimer(m).takeIf { it > 0 && db.kv("dis:$cid").isEmpty() }?.let { ChatSync.setTimer(m, store, cid, it) }
                ChatActivity.open(m, cid)
            }
        }.start()
    }
    private fun newGroup() {
        val name = ui.input(t("Group name, e.g. Family, Office team", "గ్రూప్ పేరు, ఉదా: కుటుంబం, ఆఫీస్ టీమ్"))
        val wrap = LinearLayout(m).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(name, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(m).setTitle(t("👥 New group", "👥 కొత్త గ్రూప్")).setView(wrap).setPositiveButton(t("Next: add people", "తర్వాత: వ్యక్తులను చేర్చండి")) { _, _ ->
            val n = name.text.toString().trim()
            if (n.length < 2) { m.toast(t("Give the group a name.", "గ్రూప్‌కి పేరు పెట్టండి.")); return@setPositiveButton }
            ChatPeople.pick(m, store, t("Add people to \"$n\"", "\"$n\" లో వ్యక్తులను చేర్చండి")) { ppl ->
                Thread {
                    val r = ChatSync.api(store, "POST", "/api/chat/group", JSONObject().put("name", n).put("members", JSONArray(ppl.map { it.optString("uid") })).toString())
                    m.runOnUiThread {
                        val conv = r.json.optJSONObject("conv")
                        if (!r.ok || conv == null) { m.toast(r.error("Couldn't make the group.")); return@runOnUiThread }
                        ChatDb.get(m).putConv(conv); ChatActivity.open(m, conv.optString("cid"))
                    }
                }.start()
            }
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    // ---------- my QR code and invite link ----------
    private fun myQr() {
        val me = ChatSync.me(m)
        val handle = me.optString("handle")
        if (handle.isEmpty()) { m.toast(t("Set your @name first (Media › 👤).", "ముందు మీ @పేరు పెట్టండి (మీడియా › 👤).")); return }
        val link = store.server.trimEnd('/') + "/c/" + handle
        val box = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(8), dp(20), 0) }
        qr(link, 720)?.let { b -> box.addView(ImageView(m).apply { setImageBitmap(b) }, LinearLayout.LayoutParams(dp(240), dp(240))) }
        box.addView(ui.text(me.optString("name") + "  @" + handle, 16f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        box.addView(ui.text(t("Friends scan this in Habit (Messages › 📷), or open your link:", "స్నేహితులు Habit లో ఇది స్కాన్ చేయవచ్చు (మెసేజ్‌లు › 📷), లేదా మీ లింక్ తెరవవచ్చు:"), 13f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        box.addView(ui.text(link, 13.5f, Ui.TEAL).apply { gravity = Gravity.CENTER })
        AlertDialog.Builder(m).setTitle(t("🔳 My Habit QR code", "🔳 నా Habit QR కోడ్")).setView(box)
            .setPositiveButton(t("📤 Share invite", "📤 ఆహ్వానం పంపండి")) { _, _ -> shareInvite() }
            .setNeutralButton(t("📋 Copy link", "📋 లింక్ కాపీ")) { _, _ -> m.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Habit", link)); m.toast(t("Copied", "కాపీ అయింది")) }
            .setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
    private fun qr(text: String, size: Int): Bitmap? = try {
        val mx = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (mx.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    } catch (e: Exception) { null }
}

/** 🏘️ Communities (like WhatsApp's): groups together under one announcements group where only admins write. */
object Communities {
    private fun te(store: Store) = store.lang == "te"
    /** The communities I'm in, or null (no connection). */
    fun load(store: Store): List<JSONObject>? {
        val r = ChatSync.api(store, "GET", "/api/chat/communities", timeoutMs = 15_000)
        if (!r.ok) return null
        return r.json.optJSONArray("communities")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    }
    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
    private fun bg(a: Activity, work: () -> ChatSync.Reply, done: (ChatSync.Reply) -> Unit) { Thread { val r = work(); a.runOnUiThread { done(r) } }.start() }

    /** A community: its announcements, its groups (join the ones I'm not in), and for admins, managing it. */
    fun show(a: Activity, store: Store, id: String) {
        val te = te(store)
        bg(a, { ChatSync.api(store, "GET", "/api/chat/communities") }) { r ->
            val k = r.json.optJSONArray("communities")?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } }?.firstOrNull { it.optString("id") == id }
            if (k == null) { toast(a, if (te) "ఈ కమ్యూనిటీ ఇప్పుడు లేదు." else "This community isn't there any more."); return@bg }
            val ui = Ui(a)
            val db = ChatDb.get(a)
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)) }
            if (k.optString("desc").isNotBlank()) box.addView(ui.text(k.optString("desc"), 14f, Ui.MUTED).apply { setPadding(0, 0, 0, ui.dp(8)) })
            var dlg: AlertDialog? = null
            fun row(label: String, sub: String, onClick: () -> Unit) = box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(10), 0, ui.dp(10))
                addView(ui.text(label, 15.5f, Ui.INK, bold = true)); if (sub.isNotBlank()) addView(ui.text(sub, 12.5f, Ui.MUTED))
                setOnClickListener { dlg?.dismiss(); onClick() }
            })
            val ann = k.optString("ann")
            if (ann.isNotEmpty()) row("📢 " + (if (te) "ప్రకటనలు" else "Announcements"), if (te) "అడ్మిన్లు అందరికీ ప్రకటనలు పంపుతారు" else "Admins share news with everyone") { if (db.conv(ann) != null) ChatActivity.open(a, ann) }
            val gs = k.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until gs.length()) {
                val g = gs.optJSONObject(i) ?: continue
                row(g.optString("emoji", "👥") + "  " + g.optString("name"), (if (te) "${g.optInt("n")} మంది" else "${g.optInt("n")} people") + if (g.optBoolean("joined")) "" else (if (te) " · చేరడానికి నొక్కండి" else " · tap to join")) {
                    if (g.optBoolean("joined") && db.conv(g.optString("cid")) != null) ChatActivity.open(a, g.optString("cid")) else join(a, store, id, g)
                }
            }
            val b = AlertDialog.Builder(a).setTitle(k.optString("emoji", "🏘️") + "  " + k.optString("name")).setView(ScrollView(a).apply { addView(box) })
                .setNegativeButton(if (te) "మూసివేయి" else "Close", null)
            if (k.optBoolean("admin")) b.setPositiveButton(if (te) "⚙️ నిర్వహించు" else "⚙️ Manage") { _, _ -> manage(a, store, k) }
            b.setNeutralButton(if (te) "🚪 వెళ్ళిపోండి" else "🚪 Exit") { _, _ ->
                AlertDialog.Builder(a).setMessage(if (te) "\"${k.optString("name")}\" నుంచి, దాని గ్రూప్‌లన్నిటి నుంచి వెళ్ళిపోవాలా?" else "Exit \"${k.optString("name")}\" and all its groups?")
                    .setPositiveButton(if (te) "వెళ్ళిపోండి" else "Exit") { _, _ -> bg(a, { ChatSync.api(store, "POST", "/api/chat/community/leave", JSONObject().put("id", id).toString()) }) { x -> if (x.ok) { ChatChange.after(a, store); toast(a, if (te) "వెళ్ళిపోయారు" else "You left the community") } else toast(a, x.error("Couldn't do it.")) } }
                    .setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
            }
            dlg = b.show()
        }
    }
    fun join(a: Activity, store: Store, id: String, g: JSONObject) {
        val te = te(store)
        AlertDialog.Builder(a).setMessage((if (te) "\"${g.optString("name")}\" గ్రూప్‌లో చేరతారా?" else "Join the group \"${g.optString("name")}\"?"))
            .setPositiveButton(if (te) "చేరండి" else "Join") { _, _ ->
                bg(a, { ChatSync.api(store, "POST", "/api/chat/community/join", JSONObject().put("id", id).put("cid", g.optString("cid")).toString()) }) { r ->
                    val conv = r.json.optJSONObject("conv")
                    if (r.ok && conv != null) { ChatDb.get(a).putConv(conv); ChatChange.after(a, store); ChatActivity.open(a, conv.optString("cid")) } else toast(a, r.error("Couldn't join."))
                }
            }.setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
    }
    /** A new community: a name, a few words, and the groups to put in it (groups where I'm an admin). */
    fun create(a: Activity, store: Store, done: () -> Unit) {
        val te = te(store)
        val ui = Ui(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) }
        val name = ui.input(if (te) "కమ్యూనిటీ పేరు, ఉదా: పెందుర్తి మండల ఆఫీస్" else "Community name, e.g. Mandal office")
        val desc = ui.input(if (te) "ఇది దేనికి? (కావాలంటే)" else "What is it for? (optional)", multi = true)
        box.addView(name); box.addView(desc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val me = ChatSync.myUid(a)
        val mine = ChatDb.get(a).convs().filter { it.isGroup && it.member && it.isAdmin(me) && it.comm == null && !it.isAnn }
        val picked = BooleanArray(mine.size)
        if (mine.isNotEmpty()) {
            box.addView(ui.text(if (te) "ఇందులో చేర్చే గ్రూప్‌లు (మీరు అడ్మిన్‌గా ఉన్నవి):" else "Groups to add (ones where you're an admin):", 13.5f, Ui.MUTED, bold = true).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
            mine.forEachIndexed { i, g -> box.addView(android.widget.CheckBox(a).apply { text = g.emoji + "  " + g.name; setTextColor(Ui.INK); setOnCheckedChangeListener { _, c -> picked[i] = c } }) }
        } else box.addView(ui.text(if (te) "తర్వాత గ్రూప్‌లు చేర్చవచ్చు." else "You can add groups later.", 13f, Ui.MUTED).apply { setPadding(0, ui.dp(10), 0, 0) })
        AlertDialog.Builder(a).setTitle(if (te) "🏘️ కొత్త కమ్యూనిటీ" else "🏘️ New community").setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(if (te) "చేయి" else "Create") { _, _ ->
                val n = name.text.toString().trim()
                if (n.length < 2) { toast(a, if (te) "కమ్యూనిటీకి పేరు పెట్టండి." else "Give the community a name."); return@setPositiveButton }
                val body = JSONObject().put("name", n).put("desc", desc.text.toString().trim()).put("groups", JSONArray(mine.filterIndexed { i, _ -> picked[i] }.map { it.cid }))
                bg(a, { ChatSync.api(store, "POST", "/api/chat/community", body.toString()) }) { r ->
                    val conv = r.json.optJSONObject("conv")
                    if (r.ok && conv != null) { ChatDb.get(a).putConv(conv); ChatChange.after(a, store); done(); ChatActivity.open(a, conv.optString("cid")) } else toast(a, r.error("Couldn't make the community."))
                }
            }.setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
    }
    /** For admins: rename, describe, add or take out groups, or end the community. */
    fun manage(a: Activity, store: Store, k: JSONObject) {
        val te = te(store)
        val id = k.optString("id")
        val ui = Ui(a)
        val opts = arrayOf(if (te) "✏️ పేరు, వివరణ" else "✏️ Name and description", if (te) "➕ గ్రూప్‌లు చేర్చు" else "➕ Add groups", if (te) "➖ గ్రూప్‌లు తీసేయి" else "➖ Remove groups", if (te) "⛔ కమ్యూనిటీ ముగించు" else "⛔ End the community")
        fun edit(body: JSONObject) = bg(a, { ChatSync.api(store, "POST", "/api/chat/community/edit", body.put("id", id).toString()) }) { r -> if (r.ok) { ChatChange.after(a, store); toast(a, if (te) "అయింది ✓" else "Done ✓") } else toast(a, r.error("Couldn't do it.")) }
        AlertDialog.Builder(a).setTitle("⚙️ " + k.optString("name")).setItems(opts) { _, i ->
            when (i) {
                0 -> {
                    val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) }
                    val n = ui.input(if (te) "పేరు" else "Name", k.optString("name")); val d = ui.input(if (te) "వివరణ" else "Description", k.optString("desc"), multi = true)
                    box.addView(n); box.addView(d, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
                    AlertDialog.Builder(a).setView(box).setPositiveButton(if (te) "సేవ్" else "Save") { _, _ -> edit(JSONObject().put("name", n.text.toString().trim()).put("desc", d.text.toString().trim())) }.setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
                }
                1 -> {
                    val me = ChatSync.myUid(a)
                    val mine = ChatDb.get(a).convs().filter { it.isGroup && it.member && it.isAdmin(me) && it.comm == null && !it.isAnn }
                    if (mine.isEmpty()) { toast(a, if (te) "చేర్చడానికి మీ గ్రూప్‌లు లేవు (మీరు అడ్మిన్‌గా ఉన్నవి)." else "No groups to add (groups where you're an admin)."); return@setItems }
                    val on = BooleanArray(mine.size)
                    AlertDialog.Builder(a).setTitle(opts[1]).setMultiChoiceItems(mine.map { it.emoji + "  " + it.name }.toTypedArray(), on) { _, x, c -> on[x] = c }
                        .setPositiveButton(if (te) "చేర్చు" else "Add") { _, _ -> edit(JSONObject().put("add", JSONArray(mine.filterIndexed { x, _ -> on[x] }.map { it.cid }))) }.setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
                }
                2 -> {
                    val gs = k.optJSONArray("groups")?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } } ?: emptyList()
                    if (gs.isEmpty()) return@setItems
                    val on = BooleanArray(gs.size)
                    AlertDialog.Builder(a).setTitle(opts[2]).setMultiChoiceItems(gs.map { it.optString("name") }.toTypedArray(), on) { _, x, c -> on[x] = c }
                        .setPositiveButton(if (te) "తీసేయి" else "Remove") { _, _ -> edit(JSONObject().put("remove", JSONArray(gs.filterIndexed { x, _ -> on[x] }.map { it.optString("cid") }))) }.setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
                }
                else -> AlertDialog.Builder(a).setMessage(if (te) "ఈ కమ్యూనిటీ ముగించాలా? గ్రూప్‌లు మామూలు గ్రూప్‌లుగా ఉంటాయి; ప్రకటనల గ్రూప్ పోతుంది." else "End this community? Its groups stay as normal groups; the announcements group goes.")
                    .setPositiveButton(if (te) "ముగించు" else "End it") { _, _ -> bg(a, { ChatSync.api(store, "POST", "/api/chat/community/delete", JSONObject().put("id", id).toString()) }) { r -> if (r.ok) { ChatChange.after(a, store); toast(a, if (te) "ముగించాం" else "Ended") } else toast(a, r.error("Couldn't do it.")) } }
                    .setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
            }
        }.show()
    }
}

/** 📢 Broadcast lists: one message goes to many people, each in their own chat with me (only on this phone). */
object Broadcasts {
    private fun choose(a: Activity, store: Store, title: String, current: Set<String>, done: (List<ChatConv>) -> Unit) {
        val te = store.lang == "te"
        val me = ChatSync.myUid(a)
        val dms = ChatDb.get(a).convs().filter { it.isDm && it.accepted && !it.blocked && it.member }.sortedBy { it.name.lowercase() }
        if (dms.isEmpty()) { Toast.makeText(a, if (te) "ముందు ఎవరితోనైనా చాట్ మొదలుపెట్టండి." else "Start chats with people first.", Toast.LENGTH_LONG).show(); return }
        val on = BooleanArray(dms.size) { dms[it].otherUid(me) in current }
        AlertDialog.Builder(a).setTitle(title).setMultiChoiceItems(dms.map { it.emoji + "  " + it.name }.toTypedArray(), on) { _, i, c -> on[i] = c }
            .setPositiveButton(if (te) "సరే" else "Done") { _, _ -> val l = dms.filterIndexed { i, _ -> on[i] }.take(50); if (l.isNotEmpty()) done(l) }
            .setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
    }
    private fun data(me: String, list: List<ChatConv>, base: JSONObject): JSONObject {
        base.put("members", JSONArray(list.map { c -> c.members().firstOrNull { it.optString("uid") != me } ?: JSONObject().put("uid", c.otherUid(me)).put("name", c.name).put("emoji", c.emoji) }))
        base.put("cids", JSONArray(list.map { it.cid }))
        return base
    }
    fun create(a: Activity, store: Store) {
        val te = store.lang == "te"
        val me = ChatSync.myUid(a)
        choose(a, store, if (te) "📢 ఎవరికి పంపాలి?" else "📢 Send to…", emptySet()) { list ->
            val cid = "b" + System.currentTimeMillis().toString(36)
            val name = list.take(3).joinToString(", ") { it.name.substringBefore(' ') } + if (list.size > 3) " +${list.size - 3}" else ""
            val d = data(me, list, JSONObject().put("cid", cid).put("kind", "bcast").put("name", name).put("emoji", "📢").put("accepted", true).put("member", true)
                .put("at", System.currentTimeMillis()).put("last", System.currentTimeMillis()))
            ChatDb.get(a).putConv(d)
            ChatSync.changed()
            ChatActivity.open(a, cid)
        }
    }
    fun edit(a: Activity, store: Store, cid: String, done: () -> Unit) {
        val te = store.lang == "te"
        val me = ChatSync.myUid(a)
        val c = ChatDb.get(a).conv(cid) ?: return
        choose(a, store, if (te) "📢 వ్యక్తులు" else "📢 Recipients", c.members().map { it.optString("uid") }.toSet()) { list ->
            ChatDb.get(a).patchConv(cid) { data(me, list, it) }
            ChatSync.changed(); done()
        }
    }
}

/** After a community change: the server's view of the chats comes again (new groups, the announcements group). */
object ChatChange {
    fun after(a: Activity, store: Store) {
        val app = a.applicationContext
        Thread {
            val r = ChatSync.api(store, "GET", "/api/chat/convs")
            if (r.ok) r.json.optJSONArray("convs")?.let { x -> val db = ChatDb.get(app); for (i in 0 until x.length()) db.putConv(x.getJSONObject(i)) }
            ChatDb.get(app).setKv("comms:fresh", "0")
            ChatSync.changed()
        }.start()
    }
}
