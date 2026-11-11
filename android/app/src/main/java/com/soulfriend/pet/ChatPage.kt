package com.soulfriend.pet

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
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
 * Habit Chat in the Messages tab: the list of chats and requests, a new chat or group, my QR code and invite link,
 * scanning a friend's QR code, and a normal phone call (or an invite) to anyone in the phone's contacts.
 */
class ChatPage(private val m: MainActivity) {
    private val store get() = m.store
    private val ui get() = m.ui
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = m.dp(v)
    private var section: LinearLayout? = null
    private var starting = false
    private val refresh: () -> Unit = { section?.let { if (it.isAttachedToWindow) fill(it) } }

    init { ChatSync.listeners.add(refresh) }

    fun build() {
        val s = ui.card().apply { orientation = LinearLayout.VERTICAL }
        section = s
        fill(s)
        m.add(s, 12)
    }

    private fun fill(s: LinearLayout) {
        s.removeAllViews()
        val db = ChatDb.get(m)
        val unread = if (ChatSync.isSetUp(m)) db.unreadTotal() else 0
        s.addView(ui.text("💬 Habit Chat" + if (unread > 0) "  ·  $unread " + t("new", "కొత్తవి") else "", 18f, Ui.INK, bold = true))
        if (!ChatSync.isSetUp(m)) {
            s.addView(ui.text(t("Chat with family and friends who use Habit AI, like WhatsApp: messages, groups, photos, voice notes, and reminders and work tasks pinned at the top. " +
                "🔒 Locked end to end: only the people in a chat can read it, not even Habit's server.",
                "Habit AI వాడే కుటుంబం, స్నేహితులతో వాట్సాప్ లాగా చాట్ చేయండి: మెసేజ్‌లు, గ్రూప్‌లు, ఫోటోలు, వాయిస్ మెసేజ్‌లు, పైన ఉండే రిమైండర్లు, పనులు. " +
                "🔒 ఎండ్ టు ఎండ్ లాక్: చాట్‌లో ఉన్నవాళ్ళు మాత్రమే చదవగలరు, Habit సర్వర్ కూడా చదవలేదు."), 14f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
            s.addView(ui.button(if (starting) t("Starting…", "మొదలవుతోంది…") else t("💬 Start Habit Chat", "💬 Habit చాట్ మొదలుపెట్టండి"), "primary") { if (!starting) start() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return
        }
        // what you can do
        val acts = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
        val sc = android.widget.HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(acts) }
        fun chip(label: String, f: () -> Unit) = acts.addView(TextView(m).apply {
            text = label; textSize = 14f; setTextColor(Ui.TEAL_DARK); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(9), dp(14), dp(9)); background = ui.round(Ui.TEAL_SOFT, 18); setOnClickListener { f() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        chip(t("➕ New chat", "➕ కొత్త చాట్")) { newChat() }
        chip(t("👥 New group", "👥 కొత్త గ్రూప్")) { newGroup() }
        chip(t("🔳 My QR / invite", "🔳 నా QR / ఆహ్వానం")) { myQr() }
        chip(t("📷 Scan QR", "📷 QR స్కాన్")) { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "scan")) }
        chip(t("📞 Call / invite a contact", "📞 కాంటాక్ట్‌కి కాల్ / ఆహ్వానం")) { m.startActivity(Intent(m, ChatActivity::class.java).putExtra("action", "contact")) }
        s.addView(sc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val convs = db.convs()
        val requests = convs.filter { !it.accepted && it.member }
        val chats = convs.filter { it.accepted || !it.member }.sortedWith(compareByDescending<ChatConv> { it.isSelf }.thenByDescending { it.last })
        if (requests.isNotEmpty()) {
            s.addView(ui.text("📨 " + t("Requests", "రిక్వెస్ట్‌లు") + " (${requests.size})", 14f, Ui.SUN, bold = true).apply { setPadding(0, dp(14), 0, 0) })
            requests.forEach { s.addView(row(it, true)) }
        }
        s.addView(ui.divider(), LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(12) })
        chats.forEach { s.addView(row(it, false)) }
        if (chats.size <= 1) s.addView(ui.text(t("No chats yet. Tap ➕ New chat and type a friend's name, or send them your invite link (🔳).",
            "ఇంకా చాట్‌లు లేవు. ➕ కొత్త చాట్ నొక్కి స్నేహితుడి పేరు టైప్ చేయండి, లేదా మీ ఆహ్వాన లింక్ పంపండి (🔳)."), 13.5f, Ui.MUTED).apply { setPadding(0, dp(10), 0, 0) })
    }

    private fun row(c: ChatConv, request: Boolean): View {
        val db = ChatDb.get(m)
        val r = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        r.addView(TextView(m).apply { text = c.emoji; textSize = 22f; gravity = Gravity.CENTER; background = ui.round(if (c.isSelf) Ui.SUN_SOFT else Ui.TEAL_SOFT, 23) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val mid = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        val open = db.tasks(c.cid).size
        mid.addView(ui.text(c.name + (if (c.muted) "  🔕" else "") + (if (open > 0) "  📌$open" else ""), 16f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val pv = when {
            !c.member -> t("You left this group", "మీరు ఈ గ్రూప్ వదిలేశారు")
            request && c.isGroup -> t("Added you to a group · tap to see", "మిమ్మల్ని గ్రూప్‌లో చేర్చారు · చూడండి")
            request -> t("Wants to chat · tap to see", "చాట్ చేయాలనుకుంటున్నారు · చూడండి")
            c.preview.isNotEmpty() -> c.preview
            c.isSelf -> t("Notes and reminders for yourself", "మీ కోసం నోట్స్, రిమైండర్లు")
            else -> t("🔒 Say hi!", "🔒 హాయ్ చెప్పండి!")
        }
        mid.addView(ui.text(pv, 13.5f, Ui.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val end = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (c.last > 10 && !c.isSelf) end.addView(ui.text(whenText(c.last), 11.5f, if (c.unread > 0) Ui.GOOD else Ui.MUTED))
        if (c.unread > 0) end.addView(TextView(m).apply { text = c.unread.toString(); textSize = 11.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(6), dp(1), dp(6), dp(1)); background = ui.round(Ui.GOOD, 11) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); gravity = Gravity.END })
        r.addView(end)
        r.setOnClickListener { ChatActivity.open(m, c.cid) }
        return r
    }
    private fun whenText(at: Long): String {
        val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return when (z.toLocalDate()) {
            LocalDate.now() -> z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            LocalDate.now().minusDays(1) -> t("Yesterday", "నిన్న")
            else -> z.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        }
    }

    // ---------- starting: a name (the Habit profile) and this phone's key ----------
    private fun start() {
        starting = true; section?.let { fill(it) }
        Thread {
            val e = ChatSync.setup(m, store)
            m.runOnUiThread {
                starting = false
                when (e) {
                    null -> { ChatAlarm.schedule(m); ChatSync.hold(m, "main"); section?.let { fill(it) }; askNotifications() }
                    "profile" -> askName()
                    else -> { m.toast(e); section?.let { fill(it) } }
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
            starting = true; section?.let { fill(it) }
            Thread {
                val r = ChatSync.api(store, "POST", "/api/social/me", JSONObject().put("name", n).toString())
                m.runOnUiThread { starting = false; if (r.ok) start() else { m.toast(r.error("Couldn't save your name.")); section?.let { fill(it) } } }
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
        box.addView(ui.text(t("Friends scan this in Habit (Messages › 📷 Scan QR), or open your link:", "స్నేహితులు Habit లో ఇది స్కాన్ చేయవచ్చు (మెసేజ్‌లు › 📷 QR స్కాన్), లేదా మీ లింక్ తెరవవచ్చు:"), 13f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        box.addView(ui.text(link, 13.5f, Ui.TEAL).apply { gravity = Gravity.CENTER })
        AlertDialog.Builder(m).setTitle(t("🔳 My Habit QR code", "🔳 నా Habit QR కోడ్")).setView(box)
            .setPositiveButton(t("📤 Share invite", "📤 ఆహ్వానం పంపండి")) { _, _ ->
                val msg = t("Hi! Let's chat on Habit AI (free, locked end to end, Telugu and English): $link", "హాయ్! Habit AI లో చాట్ చేద్దాం (ఉచితం, సురక్షితం): $link")
                m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), t("Share invite", "ఆహ్వానం పంపండి")))
            }
            .setNeutralButton(t("📋 Copy link", "📋 లింక్ కాపీ")) { _, _ -> m.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Habit", link)); m.toast(t("Copied", "కాపీ అయింది")) }
            .setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
    private fun qr(text: String, size: Int): Bitmap? = try {
        val mx = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (mx.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    } catch (e: Exception) { null }
}
