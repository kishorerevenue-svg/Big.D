package com.soulfriend.pet

import android.Manifest
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Messages tab: Habit Chat, like WhatsApp, in dark navy and gold.
 *  - The top: "Habit Chat", 📷 scan a QR code, ⋮ (new group, my QR / invite, read all, hidden chats, chat settings).
 *  - A search bar for chats, Habit people and phone contacts; chips: All · Unread · Groups · ⭐ Favourites · 📞 Calls.
 *  - 📌 Pinned chats first; long-press a chat: pin, favourite, mute, read / unread, hide (🔒 hidden chats open with the
 *    fingerprint), wallpaper, delete, block.
 *  - "Invite your people": ⭐ favourite contacts and people you called or invited from Habit; everyone else by search.
 *  - The round ➕ (bottom right, MainActivity's button): new chat, new group, my QR, scan QR, call or invite a contact.
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
    private var starting = false
    private var filter = "all"
    private var query = ""
    private var hiddenOpen = false
    private var people: JSONArray? = null      // Habit people found for [peopleQ]
    private var peopleQ = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { if (listBox?.isAttachedToWindow == true) { chipsBox?.let { fillChips(it) }; listBox?.let { fillList(it) } } }

    init { ChatSync.listeners.add(refresh) }

    fun build() {
        val s = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
        section = s
        draw(s)
        m.add(s, 0)
    }
    private fun redraw() { section?.let { if (it.isAttachedToWindow) draw(it) } }

    private fun txt(s: String, size: Float, color: Int = C.TXT, bold: Boolean = false) = ui.text(s, size, color, bold)

    private fun draw(s: LinearLayout) {
        s.removeAllViews()
        // the top: title, 📷, ⋮
        s.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(4), 0, dp(6))
            addView(txt("Habit Chat", 25f, C.TXT, true).apply { letterSpacing = -0.01f }, LinearLayout.LayoutParams(0, -2, 1f))
            if (ChatSync.isSetUp(m)) {
                addView(icon("📷", t("Scan a QR code", "QR స్కాన్")) { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "scan")) })
                addView(icon("⋮", t("More", "ఇంకా")) { topMenu(it) })
            }
        })
        if (!ChatSync.isSetUp(m)) { intro(s); return }
        // the search bar
        val search = EditText(m).apply {
            hint = "🔍  " + t("Search chats, people or contacts", "చాట్‌లు, వ్యక్తులు, కాంటాక్ట్‌లు వెతకండి"); setText(query)
            textSize = 15.5f; setTextColor(C.TXT); setHintTextColor(C.SUB); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            background = C.round(C.BAR, dp(26).toFloat()); setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        search.onText { q ->
            query = q.trim(); listBox?.let { fillList(it) }
            handler.removeCallbacksAndMessages("people")
            if (query.length >= 2) handler.postAtTime({ findPeople(query) }, "people", android.os.SystemClock.uptimeMillis() + 450)
        }
        s.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // the chips
        val chips = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
        chipsBox = chips
        fillChips(chips)
        s.addView(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val list = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
        listBox = list
        fillList(list)
        s.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        s.addView(View(m), LinearLayout.LayoutParams(-1, dp(70)))      // room for the ➕ button
    }

    private fun icon(label: String, desc: String, onClick: (View) -> Unit) = TextView(m).apply {
        text = label; textSize = 21f; gravity = Gravity.CENTER; setTextColor(C.TXT); contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)); setOnClickListener { onClick(it) }
    }

    private fun intro(s: LinearLayout) {
        s.addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = C.round(C.CARD, dp(20).toFloat())
            addView(txt("💬 " + t("Chat, call and share, like WhatsApp", "వాట్సాప్ లాగా చాట్, కాల్, షేర్"), 17f, C.GOLD, true))
            addView(txt(t("Chat with family and friends who use Habit AI: messages, groups, voice and video calls, photos, videos, documents, voice notes, and reminders and work tasks. " +
                "🔒 Locked end to end: only the people in a chat can read it, not even Habit's server.",
                "Habit AI వాడే కుటుంబం, స్నేహితులతో: మెసేజ్‌లు, గ్రూప్‌లు, వాయిస్, వీడియో కాల్స్, ఫోటోలు, వీడియోలు, ఫైల్స్, వాయిస్ మెసేజ్‌లు, రిమైండర్లు, పనులు. " +
                "🔒 ఎండ్ టు ఎండ్ లాక్: చాట్‌లో ఉన్నవాళ్ళు మాత్రమే చదవగలరు, Habit సర్వర్ కూడా చదవలేదు."), 14.5f, C.SUB).apply { setPadding(0, dp(8), 0, 0) })
            addView(goldButton(if (starting) t("Starting…", "మొదలవుతోంది…") else t("💬 Start Habit Chat", "💬 Habit చాట్ మొదలుపెట్టండి")) { if (!starting) start() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun goldButton(label: String, onClick: () -> Unit) = TextView(m).apply {
        text = label; textSize = 15.5f; setTextColor(C.NAVY); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(16), dp(13), dp(16), dp(13)); background = C.round(C.GOLD, dp(26).toFloat()); setOnClickListener { onClick() }
    }
    private fun pill(label: String, on: Boolean, onClick: () -> Unit) = TextView(m).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (on) C.NAVY else C.TXT); setPadding(dp(15), dp(8), dp(15), dp(8))
        background = C.round(if (on) C.GOLD else C.CARD, dp(18).toFloat()); setOnClickListener { onClick() }
    }

    // ---------- chips ----------
    private fun fillChips(box: LinearLayout) {
        box.removeAllViews()
        val unread = db.convs().count { !hidden(it.cid) && (it.unread > 0 || markedUnread(it.cid)) }
        listOf("all" to t("All", "అన్నీ"), "unread" to t("Unread", "చదవనివి") + if (unread > 0) " $unread" else "", "groups" to t("Groups", "గ్రూప్‌లు"),
            "fav" to "⭐ " + t("Favourites", "ఇష్టమైనవి"), "calls" to "📞 " + t("Calls", "కాల్స్")).forEach { (k, label) ->
            box.addView(pill(label, filter == k) { filter = k; hiddenOpen = false; chipsBox?.let { fillChips(it) }; listBox?.let { fillList(it) } },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
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
        if (filter == "calls") { calls(box); return }
        val all = db.convs()
        val locked = all.filter { hidden(it.cid) }
        var pool = all.filter { !hidden(it.cid) }
        if (hiddenOpen) {
            pool = locked
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(6))
                addView(txt("🔒 " + t("Hidden chats", "దాచిన చాట్‌లు"), 16f, C.GOLD, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(pill(t("Close", "మూసివేయి"), false) { hiddenOpen = false; listBox?.let { fillList(it) } })
            })
        } else if (locked.isNotEmpty() && query.isEmpty() && filter == "all") {
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(8), dp(12))
                addView(txt("🔒", 20f), LinearLayout.LayoutParams(dp(48), -2))
                addView(txt(t("Hidden chats", "దాచిన చాట్‌లు") + "  (${locked.size})", 15.5f, C.SUB), LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { openHidden() }
            })
        }
        val q = query.lowercase()
        val requests = pool.filter { !it.accepted && it.member }
        var chats = pool.filter { it.accepted || !it.member }
        chats = when (filter) {
            "unread" -> chats.filter { it.unread > 0 || markedUnread(it.cid) }
            "groups" -> chats.filter { it.isGroup }
            "fav" -> chats.filter { fav(it.cid) }
            else -> chats
        }
        if (q.isNotEmpty()) chats = chats.filter { it.name.lowercase().contains(q) || it.preview.lowercase().contains(q) || it.data.optString("handle").lowercase().contains(q) }
        chats = chats.sortedWith(compareByDescending<ChatConv> { pinned(it.cid) }.thenByDescending { if (pinned(it.cid)) pinAt(it.cid) else 0L }.thenByDescending { it.last })
        if (requests.isNotEmpty() && q.isEmpty() && filter == "all" && !hiddenOpen) {
            box.addView(txt("📨 " + t("Requests", "రిక్వెస్ట్‌లు") + " (${requests.size})", 14f, C.GOLD, true).apply { setPadding(dp(4), dp(12), 0, dp(2)) })
            requests.forEach { box.addView(row(it, true)) }
            box.addView(View(m).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(6); bottomMargin = dp(4) })
        }
        chats.forEach { box.addView(row(it, false)) }
        if (chats.isEmpty()) box.addView(txt(when {
            q.isNotEmpty() -> t("No chats with \"$query\".", "\"$query\" తో చాట్‌లు లేవు.")
            filter == "unread" -> t("No unread chats. 🎉", "చదవని చాట్‌లు లేవు. 🎉")
            filter == "groups" -> t("No groups yet. Tap ➕ › New group.", "ఇంకా గ్రూప్‌లు లేవు. ➕ › కొత్త గ్రూప్ నొక్కండి.")
            filter == "fav" -> t("No favourites yet. Long-press a chat › ⭐ Add to favourites.", "ఇంకా ఇష్టమైనవి లేవు. చాట్‌ని నొక్కి పట్టి › ⭐ ఎంచుకోండి.")
            else -> t("No chats yet. Tap ➕ to start one, or invite your people below.", "ఇంకా చాట్‌లు లేవు. ➕ నొక్కి మొదలుపెట్టండి, లేదా కింద మీ వాళ్ళను ఆహ్వానించండి.")
        }, 14f, C.SUB).apply { setPadding(dp(6), dp(16), dp(6), dp(10)) })
        if (hiddenOpen) return
        if (q.length >= 2) { searchMore(box); return }
        if (filter == "all") invites(box)
    }

    private fun avatar(emoji: String, gold: Boolean = false) = TextView(m).apply {
        text = emoji; textSize = 22f; gravity = Gravity.CENTER
        background = ChatTheme.circle(if (gold) Color.parseColor("#3A3320") else C.CARD)
    }

    private fun row(c: ChatConv, request: Boolean): View {
        val r = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(11), dp(4), dp(11)) }
        r.addView(avatar(c.emoji, c.isSelf), LinearLayout.LayoutParams(dp(50), dp(50)))
        val mid = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, dp(8), 0) }
        val tasks = db.tasks(c.cid).size
        mid.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(txt(c.name, 16.5f, C.TXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f).apply { weight = 1f })
            if (tasks > 0) addView(txt("📋 $tasks", 12f, C.NAVY, true).apply { setPadding(dp(7), dp(1), dp(7), dp(1)); background = C.round(C.GOLD, dp(9).toFloat()) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-1, -2))
        val last = if (!request && c.member && !c.isSelf) db.lastMsg(c.cid) else null
        val me = ChatSync.myUid(m)
        val tick = if (last != null && last.sender == me && last.kind != "sys" && last.kind != "clog") when (c.ticks(last.seq, me)) { 3 -> "✓✓ "; 2 -> "✓✓ "; 1 -> "✓ "; else -> "🕓 " } else ""
        val pv = when {
            !c.member -> t("You left this group", "మీరు ఈ గ్రూప్ వదిలేశారు")
            request && c.isGroup -> t("Added you to a group · tap to see", "మిమ్మల్ని గ్రూప్‌లో చేర్చారు · చూడండి")
            request -> t("Wants to chat · tap to see", "చాట్ చేయాలనుకుంటున్నారు · చూడండి")
            c.preview.isNotEmpty() -> c.preview
            c.isSelf -> t("Notes and reminders for yourself", "మీ కోసం నోట్స్, రిమైండర్లు")
            else -> t("🔒 Say hi!", "🔒 హాయ్ చెప్పండి!")
        }
        mid.addView(TextView(m).apply {
            val sp = android.text.SpannableString(tick + pv)
            if (tick.isNotEmpty()) sp.setSpan(android.text.style.ForegroundColorSpan(if (last != null && c.ticks(last.seq, me) == 3) C.READ else C.SUB), 0, tick.length, 0)
            text = sp; textSize = 14f; setTextColor(C.SUB); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val unread = c.unread > 0 || markedUnread(c.cid)
        val end = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (c.last > 10 && !c.isSelf) end.addView(txt(whenText(c.last), 12f, if (unread && !c.muted) C.GOLD else C.SUB).apply { gravity = Gravity.END })
        end.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            if (c.muted) addView(txt("🔕", 12.5f, C.SUB))
            if (pinned(c.cid)) addView(txt(" 📌", 12.5f, C.SUB))
            if (c.unread > 0) addView(txt(if (c.unread > 99) "99+" else c.unread.toString(), 11.5f, C.NAVY, true).apply {
                gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(6), dp(2), dp(6), dp(2)); background = C.round(if (c.muted) C.SUB else C.GOLD, dp(11).toFloat())
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
            else if (markedUnread(c.cid)) addView(View(m).apply { background = ChatTheme.circle(C.GOLD) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        r.addView(end)
        r.setOnClickListener { db.setKv("unr:${c.cid}", ""); ChatActivity.open(m, c.cid) }
        r.setOnLongClickListener { chatMenu(c); true }
        return r
    }

    private fun whenText(at: Long): String {
        val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return when (z.toLocalDate()) {
            LocalDate.now() -> z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            LocalDate.now().minusDays(1) -> t("Yesterday", "నిన్న")
            else -> z.format(DateTimeFormatter.ofPattern("d/M/yy", Locale.ENGLISH))
        }
    }

    // ---------- long-press a chat ----------
    private fun chatMenu(c: ChatConv) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val pin = pinned(c.cid)
        items.add((if (pin) t("📌 Unpin", "📌 పిన్ తీసేయి") else t("📌 Pin to the top", "📌 పైన పిన్ చేయి")) to {
            if (!pin && db.convs().count { pinned(it.cid) } >= 5) m.toast(t("You can pin up to 5 chats.", "5 చాట్‌ల వరకు పిన్ చేయవచ్చు."))
            else { db.setKv("pin:${c.cid}", if (pin) "0" else System.currentTimeMillis().toString()); redraw() }
        })
        items.add((if (fav(c.cid)) t("⭐ Remove from favourites", "⭐ ఇష్టమైనవి నుంచి తీసేయి") else t("⭐ Add to favourites", "⭐ ఇష్టమైనవిలో చేర్చు")) to {
            db.setKv("fav:${c.cid}", if (fav(c.cid)) "" else "1"); redraw() })
        if (!c.isSelf) items.add((if (c.muted) t("🔔 Unmute", "🔔 శబ్దం ఆన్") else t("🔕 Mute", "🔕 శబ్దం ఆఫ్")) to { db.setMuted(c.cid, !c.muted); redraw() })
        if (c.unread > 0 || markedUnread(c.cid)) items.add(t("✓ Mark as read", "✓ చదివినట్టు") to { db.readAll(c.cid); db.setKv("unr:${c.cid}", ""); redraw() })
        else items.add(t("● Mark as unread", "● చదవనట్టు") to { db.setKv("unr:${c.cid}", "1"); redraw() })
        items.add((if (hidden(c.cid)) t("👁 Show in the list again", "👁 మళ్ళీ లిస్ట్‌లో చూపించు") else t("🙈 Hide (🔒 hidden chats)", "🙈 దాచు (🔒 దాచిన చాట్‌లు)")) to {
            val on = !hidden(c.cid)
            db.setKv("lock:${c.cid}", if (on) "1" else "")
            if (on) m.toast(t("Hidden. Open it from 🔒 Hidden chats at the top of the list.", "దాచాం. లిస్ట్ పైన 🔒 దాచిన చాట్‌లు నుంచి తెరవండి."))
            redraw()
        })
        items.add(t("🎨 Wallpaper", "🎨 వాల్‌పేపర్") to { pickWall(c.cid) })
        if (!c.isGroup && !c.isSelf) {
            val other = c.members().map { it.optString("uid") }.firstOrNull { it != ChatSync.myUid(m) } ?: ""
            items.add((if (c.blocked) t("✅ Unblock", "✅ అన్‌బ్లాక్") else t("🚫 Block", "🚫 బ్లాక్")) to {
                Thread {
                    val r = ChatSync.api(store, "POST", "/api/chat/block", JSONObject().put("uid", other).put("on", !c.blocked).toString())
                    m.runOnUiThread { if (r.ok) { db.patchConv(c.cid) { it.put("blocked", !c.blocked) }; m.toast(if (c.blocked) t("Unblocked", "అన్‌బ్లాక్ చేశాం") else t("Blocked", "బ్లాక్ చేశాం")); redraw() } else m.toast(r.error("Couldn't do it.")) }
                }.start()
            })
        }
        items.add((if (c.isGroup && c.member) t("🚪 Exit group and delete", "🚪 గ్రూప్ వదిలి తొలగించు") else if (c.isSelf) t("🧹 Clear my notes chat", "🧹 నా నోట్స్ చాట్ క్లియర్") else t("🗑️ Delete chat", "🗑️ చాట్ తొలగించు")) to { deleteChat(c) })
        AlertDialog.Builder(m).setTitle(c.emoji + "  " + c.name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun deleteChat(c: ChatConv) {
        val msg = when {
            c.isGroup && c.member -> t("Exit \"${c.name}\" and delete it from this phone?", "\"${c.name}\" నుంచి వెళ్ళిపోయి, ఈ ఫోన్ నుంచి తొలగించాలా?")
            c.isSelf -> t("Clear all your notes here? Open reminders stay on your phone's alarms.", "ఇక్కడి నోట్స్ అన్నీ క్లియర్ చేయాలా?")
            else -> t("Delete this chat from this phone? (A new message from them brings it back.)", "ఈ ఫోన్ నుంచి ఈ చాట్ తొలగించాలా? (వాళ్ళ కొత్త మెసేజ్ వస్తే మళ్ళీ వస్తుంది.)")
        }
        AlertDialog.Builder(m).setMessage(msg).setPositiveButton(t("Delete", "తొలగించు")) { _, _ ->
            if (c.isGroup && c.member) Thread {
                val r = ChatSync.api(store, "POST", "/api/chat/leave", JSONObject().put("cid", c.cid).toString())
                m.runOnUiThread { if (r.ok) { db.patchConv(c.cid) { it.put("member", false) }; db.clear(c.cid, true); redraw() } else m.toast(r.error("Couldn't leave the group.")) }
            }.start()
            else { db.clear(c.cid, !c.isSelf); redraw() }
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    fun pickWall(cid: String) {
        val walls = ChatTheme.WALLS
        val cur = if (cid.isEmpty()) db.kv("set:wall").toIntOrNull() ?: 0 else ChatTheme.wallOf(m, cid)
        AlertDialog.Builder(m).setTitle(t("🎨 Chat wallpaper", "🎨 చాట్ వాల్‌పేపర్"))
            .setSingleChoiceItems(walls.map { (if (it.id == cur) "✓ " else "") + if (te) it.te else it.en }.toTypedArray(), walls.indexOfFirst { it.id == cur }) { d, i ->
                db.setKv(if (cid.isEmpty()) "set:wall" else "wall:$cid", walls[i].id.toString()); d.dismiss(); m.toast(t("Wallpaper changed 🎨", "వాల్‌పేపర్ మార్చాం 🎨"))
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }

    /** Hidden chats open with the fingerprint or phone lock (if that's on in chat settings). */
    private fun openHidden() {
        ChatTheme.unlockHidden(m) { err -> if (err == null) { hiddenOpen = true; filter = "all"; chipsBox?.let { fillChips(it) }; listBox?.let { fillList(it) } } else m.toast(err) }
    }

    // ---------- 📞 Calls: every Habit call, newest first ----------
    private fun calls(box: LinearLayout) {
        val logs = db.callLogs(150)
        if (logs.isEmpty()) { box.addView(txt(t("No calls yet. Open a chat and tap 📞 or 🎥.", "ఇంకా కాల్స్ లేవు. చాట్ తెరిచి 📞 లేదా 🎥 నొక్కండి."), 14f, C.SUB).apply { setPadding(dp(6), dp(16), dp(6), dp(10)) }); return }
        val me = ChatSync.myUid(m)
        for (l in logs) {
            val c = db.conv(l.cid) ?: continue
            if (hidden(c.cid) && !hiddenOpen) continue
            val b = l.body
            val missed = b.optString("st") == "missed"
            val out = b.optBoolean("out") || l.sender == me
            box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(avatar(c.emoji), LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(LinearLayout(m).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, dp(8), 0)
                    addView(txt(c.name, 16f, if (missed) C.DANGER else C.TXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(txt((if (missed) "↙ " else if (out) "↗ " else "↙ ") + CallManager.logText(m, l).substringAfter(' ') + " · " + whenText(l.at), 13f, if (missed) C.DANGER else C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (!c.isGroup && c.member && c.accepted && !c.blocked) addView(icon(if (b.optBoolean("video")) "🎥" else "📞", t("Call back", "మళ్ళీ కాల్")) {
                    m.startActivity(Intent(m, ChatActivity::class.java).putExtra("cid", c.cid).putExtra("call", if (b.optBoolean("video")) "video" else "voice")) })
                setOnClickListener { ChatActivity.open(m, c.cid) }
            })
        }
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
        if (found.isEmpty()) box.addView(txt(t("No contact with \"$query\".", "\"$query\" తో కాంటాక్ట్ లేదు."), 14f, C.SUB).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) })
        for (p in found) box.addView(contactRow(p))
    }

    // ---------- invite your people ----------
    private fun invites(box: LinearLayout) {
        box.addView(heading(t("Invite your people", "మీ వాళ్ళను ఆహ్వానించండి")))
        if (!ChatContacts.allowed(m)) { allowContacts(box); return }
        val ppl = ChatContacts.suggested(m, 8)
        if (ppl.isEmpty()) box.addView(txt(t("Star (⭐) your close people in the phone's Contacts app and they show here. Find anyone else with the search bar above.",
            "ఫోన్ కాంటాక్ట్స్ యాప్‌లో మీ దగ్గరి వాళ్ళకు ⭐ పెట్టండి, వాళ్ళు ఇక్కడ కనిపిస్తారు. మిగతావాళ్ళను పైన సెర్చ్‌లో వెతకండి."), 13.5f, C.SUB).apply { setPadding(dp(6), dp(4), dp(6), dp(6)) })
        for (p in ppl) box.addView(contactRow(p))
        box.addView(txt(t("🔍 Anyone else: type their name in the search bar.", "🔍 మిగతావాళ్ళు: పైన సెర్చ్‌లో పేరు టైప్ చేయండి."), 13f, C.SUB).apply { setPadding(dp(6), dp(8), dp(6), 0) })
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
            addView(pill("📤 " + t("Share my invite link", "నా ఆహ్వాన లింక్ పంపు"), false) { shareInvite() })
        })
    }
    private fun allowContacts(box: LinearLayout) {
        box.addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = C.round(C.CARD, dp(16).toFloat())
            addView(txt(t("See your contacts here to invite or call them. They stay on your phone: nothing is uploaded.",
                "ఆహ్వానించడానికి, కాల్ చేయడానికి మీ కాంటాక్ట్‌లు ఇక్కడ చూడండి. అవి మీ ఫోన్‌లోనే ఉంటాయి: ఏదీ అప్‌లోడ్ కాదు."), 14f, C.TXT))
            addView(goldButton(t("Allow contacts", "కాంటాక్ట్‌లకు అనుమతి")) { m.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), MainActivity.REQ_CHAT_CONTACTS) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    private fun heading(s: String) = txt(s.uppercase(Locale.getDefault()), 12f, C.GOLD, true).apply { letterSpacing = 0.06f; setPadding(dp(6), dp(18), dp(6), dp(4)) }
    private fun goldMini(label: String, onClick: () -> Unit) = TextView(m).apply {
        text = label; textSize = 13.5f; setTextColor(C.NAVY); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7)); background = C.round(C.GOLD, dp(16).toFloat()); setOnClickListener { onClick() }
    }
    private fun personRow(emoji: String, name: String, sub: String, button: View?, second: View?, onClick: (() -> Unit)?) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(9), dp(4), dp(9))
        addView(avatar(emoji), LinearLayout.LayoutParams(dp(44), dp(44)))
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
        val first = p.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "👤"
        return personRow(first, (if (p.starred) "⭐ " else "") + p.name, p.number,
            goldMini(t("Invite", "ఆహ్వానించు")) { ChatContacts.invite(m, store, p) },
            icon("📞", t("Phone call", "ఫోన్ కాల్")) { ChatContacts.call(m, p) }, null)
    }

    // ---------- the ⋮ menu and the ➕ button ----------
    private fun topMenu(anchor: View) {
        val pm = android.widget.PopupMenu(m, anchor)
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, f: () -> Unit) { pm.menu.add(0, acts.size, acts.size, label); acts.add(f) }
        add(t("👥 New group", "👥 కొత్త గ్రూప్")) { newGroup() }
        add(t("🔳 My QR code / invite link", "🔳 నా QR కోడ్ / ఆహ్వాన లింక్")) { myQr() }
        add(t("✓ Read all", "✓ అన్నీ చదివినట్టు")) { db.convs().forEach { db.readAll(it.cid); db.setKv("unr:${it.cid}", "") }; redraw() }
        add("🔒 " + t("Hidden chats", "దాచిన చాట్‌లు")) { openHidden() }
        add(t("⚙️ Chat settings", "⚙️ చాట్ సెట్టింగ్స్")) { m.startActivity(Intent(m, ChatSettingsActivity::class.java)) }
        pm.setOnMenuItemClickListener { acts[it.itemId](); true }
        pm.show()
    }

    /** The round ➕ button (bottom right). */
    fun plusMenu() {
        if (!ChatSync.isSetUp(m)) { start(); return }
        val items = listOf<Pair<String, () -> Unit>>(
            t("👤 New chat (Habit people)", "👤 కొత్త చాట్ (Habit వ్యక్తులు)") to { newChat() },
            t("👥 New group", "👥 కొత్త గ్రూప్") to { newGroup() },
            t("📞 Call or invite a phone contact", "📞 ఫోన్ కాంటాక్ట్‌కి కాల్ / ఆహ్వానం") to { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "contact")) },
            t("🔳 My QR code / invite link", "🔳 నా QR కోడ్ / ఆహ్వాన లింక్") to { myQr() },
            t("📷 Scan a friend's QR code", "📷 స్నేహితుడి QR స్కాన్") to { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "scan")) },
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
                ChatDb.get(m).putConv(conv); ChatActivity.open(m, conv.optString("cid"))
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
