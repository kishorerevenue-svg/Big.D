package com.soulfriend.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Like WhatsApp's info screens, in navy and gold:
 *  - "info": contact info (photo, @name, about, call buttons, media, ⭐ starred, mute, theme, disappearing messages,
 *    chat lock, the encryption safety number, groups in common, lists, block, report…), group info (description,
 *    group settings, members and admins, invite link, community…) or a broadcast list (its people).
 *  - "media": the chat's photos and videos, documents and links.  - "starred": ⭐ starred messages (one chat, or all).
 */
class ChatInfoActivity : Activity() {
    companion object {
        fun open(a: Activity, cid: String, mode: String) = a.startActivity(Intent(a, ChatInfoActivity::class.java).putExtra("cid", cid).putExtra("mode", mode))
    }
    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var box: LinearLayout
    private lateinit var titleView: TextView
    private val C = ChatTheme
    private val db get() = ChatDb.get(this)
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)
    private var cid = ""
    private var mode = "info"
    private var me = ""
    private var bio: String? = null
    private var mediaTab = "media"
    private val refresh: () -> Unit = { if (!isFinishing) draw() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this)
        cid = intent.getStringExtra("cid") ?: ""
        mode = intent.getStringExtra("mode") ?: "info"
        me = ChatSync.myUid(this)
        window.statusBarColor = C.NAVY
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.NAVY); fitsSystemWindows = true }
        titleView = ui.text("", 19f, C.TXT, bold = true)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(12), dp(8)); setBackgroundColor(C.BAR)
            addView(TextView(this@ChatInfoActivity).apply { text = "←"; textSize = 22f; setTextColor(C.TXT); gravity = Gravity.CENTER; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titleView)
        })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadBio()
    }
    override fun onResume() { super.onResume(); ChatSync.listeners.add(refresh); ChatSync.hold(this, "info"); draw() }
    override fun onPause() { super.onPause(); ChatSync.listeners.remove(refresh); ChatSync.release("info") }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun conv() = if (cid.isEmpty()) null else db.conv(cid)

    private fun draw() {
        box.removeAllViews()
        when (mode) {
            "starred" -> { titleView.text = t("⭐ Starred messages", "⭐ స్టార్ చేసిన మెసేజ్‌లు"); starred() }
            "media" -> { val c = conv() ?: return finish(); titleView.text = c.name; media(c) }
            else -> { val c = conv() ?: return finish()
                titleView.text = when { c.isGroup -> t("Group info", "గ్రూప్ వివరాలు"); c.isBcast -> t("Broadcast list", "బ్రాడ్‌కాస్ట్ లిస్ట్"); else -> t("Contact info", "కాంటాక్ట్ వివరాలు") }
                when { c.isGroup -> groupInfo(c); c.isBcast -> bcastInfo(c); else -> contactInfo(c) } }
        }
    }

    // ---------- little pieces ----------
    private fun head(s: String) = box.addView(ui.text(s, 13.5f, C.GOLD, bold = true).apply { setPadding(dp(4), dp(18), dp(4), dp(4)) })
    private fun line() = box.addView(View(this).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(8) })
    private fun item(icon: String, title: String, sub: String = "", color: Int = C.TXT, onClick: (() -> Unit)?) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(12))
            addView(ui.text(icon, 19f, color).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), -2))
            addView(LinearLayout(this@ChatInfoActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(ui.text(title, 16f, color))
                if (sub.isNotBlank()) addView(ui.text(sub, 13f, C.SUB).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (onClick != null) { addView(ui.text("›", 20f, C.SUB)); setOnClickListener { onClick() } }
        })
    }
    private fun toggle(icon: String, title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(ui.text(icon, 18f, C.TXT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), -2))
            addView(LinearLayout(this@ChatInfoActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0)
                addView(ui.text(title, 16f, C.TXT)); if (sub.isNotBlank()) addView(ui.text(sub, 13f, C.SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this@ChatInfoActivity).apply {
                isChecked = on
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.GOLD, Color.parseColor("#C9CEE6")))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.GOLD_DARK, Color.parseColor("#4A5288")))
                setOnCheckedChangeListener { _, c -> change(c) }
            }
            addView(sw); setOnClickListener { sw.isChecked = !sw.isChecked }
        })
    }
    private fun bigButton(icon: String, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(10), dp(6), dp(10)); background = C.round(C.CARD, dp(14).toFloat(), C.LINE)
        addView(ui.text(icon, 20f).apply { gravity = Gravity.CENTER })
        addView(ui.text(label, 13f, C.GOLD, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
        setOnClickListener { onClick() }
    }
    private fun buttons(vararg b: View) = box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(4))
        b.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) }) }
    })
    private fun post(path: String, body: JSONObject, ok: (JSONObject) -> Unit = {}) {
        Thread {
            val r = ChatSync.api(store, "POST", path, body.put("cid", cid).toString())
            runOnUiThread { if (r.ok) { r.json.optJSONObject("conv")?.let { db.putConv(it) }; ok(r.json); draw() } else toast(r.error("Couldn't do it.")) }
        }.start()
    }
    private fun loadBio() {
        val c = conv() ?: return
        if (!c.isDm) return
        Thread {
            val r = ChatSync.api(store, "GET", "/api/social/profile?uid=" + c.otherUid(me), timeoutMs = 15_000)
            runOnUiThread { if (r.ok) { bio = r.json.optJSONObject("user")?.optString("bio") ?: ""; if (!isFinishing && mode == "info") draw() } }
        }.start()
    }
    private fun top(c: ChatConv, line2: String, line3: String) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(14), 0, dp(4))
            addView(ChatDraw.avatar(this@ChatInfoActivity, store, c.emoji, if (c.isDm) c.avatar else "", dp(110)), LinearLayout.LayoutParams(dp(110), dp(110)))
            addView(ui.text(c.name, 22f, C.TXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
            if (line2.isNotBlank()) addView(ui.text(line2, 14f, C.SUB).apply { gravity = Gravity.CENTER })
            if (line3.isNotBlank()) addView(ui.text(line3, 13.5f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(4), dp(10), 0) })
        }, LinearLayout.LayoutParams(-1, -2))
    }
    /** The latest photos, as a strip (tap: all media). */
    private fun mediaStrip(c: ChatConv) {
        val media = db.ofKinds(cid, listOf("photo", "video", "gif"), 12).filter { !it.body.optBoolean("once") }
        val docs = db.ofKinds(cid, listOf("doc"), 500).size
        val links = db.withLinks(cid).size
        item("🖼️", t("Media, links and docs", "మీడియా, లింక్స్, ఫైల్స్"), "${media.size}${if (media.size >= 12) "+" else ""} " + t("media", "మీడియా") + " · $docs " + t("docs", "ఫైల్స్") + " · $links " + t("links", "లింక్స్")) { open(this, cid, "media") }
        if (media.isEmpty()) return
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@ChatInfoActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                media.take(8).forEach { m -> addView(thumb(m, dp(84)), LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(6) }) }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, 0, dp(6)) })
    }
    private fun thumb(m: ChatMsg, size: Int): View = FrameLayout(this).apply {
        val iv = ImageView(this@ChatInfoActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(C.CARD) }
        val local = m.body.optString("local")
        val bmp = if (m.kind == "photo" && local.isNotEmpty() && File(local).exists()) decode(File(local), size)
            else m.body.optString("thumb").takeIf { it.isNotEmpty() }?.let { try { val b = ChatCrypto.unb64(it); BitmapFactory.decodeByteArray(b, 0, b.size) } catch (e: Exception) { null } }
        if (bmp != null) iv.setImageBitmap(bmp)
        addView(iv, FrameLayout.LayoutParams(-1, -1))
        if (m.kind != "photo") addView(ui.text(if (m.kind == "gif") "GIF" else "▶", 12f, Color.WHITE, bold = true).apply { setPadding(dp(5), dp(1), dp(5), dp(1)); background = C.round(Color.parseColor("#99000000"), dp(6).toFloat()) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(4), 0, 0, dp(4)) })
        clipToOutline = true; background = C.round(C.CARD, dp(8).toFloat())
        setOnClickListener { openMedia(m) }
        setOnLongClickListener { ChatActivity.openAt(this@ChatInfoActivity, m.cid, m.id); true }
    }
    private fun decode(f: File, w: Int) = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1; while (o.outWidth / (s * 2) >= w) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }
    private fun openMedia(m: ChatMsg) {
        val local = m.body.optString("local")
        if (local.isEmpty() || !File(local).exists()) { ChatSync.fetchFile(this, store, m); toast(t("Downloading… try again in a moment.", "డౌన్‌లోడ్ అవుతోంది… కాసేపట్లో మళ్ళీ ప్రయత్నించండి.")); return }
        val f = File(local)
        val name = (if (m.kind == "doc") m.body.optString("name") else "Habit-" + m.kind + "-" + m.id + "." + f.extension).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "file" }
        if (name.lowercase().endsWith(".apk")) return
        try { f.copyTo(File(ShareFiles.dir(this), name), overwrite = true) } catch (e: Exception) { return }
        val uri = ShareFiles.uri(this, name)
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { toast(t("No app on this phone can open this file.", "ఈ ఫైల్ తెరిచే యాప్ ఫోన్‌లో లేదు.")) }
    }
    /** The rows every chat has: media, ⭐, mute, theme, disappearing, lock. */
    private fun commonRows(c: ChatConv) {
        mediaStrip(c)
        item("⭐", t("Starred messages", "స్టార్ చేసిన మెసేజ్‌లు"), db.starred(cid).size.let { if (it == 0) t("None", "ఏవీ లేవు") else "$it" }) { open(this, cid, "starred") }
        line()
        if (!c.isLocal) toggle("🔕", t("Mute notifications", "నోటిఫికేషన్లు మ్యూట్"), "", c.muted) { db.setMuted(cid, it) }
        val wall = ChatTheme.wall(ChatTheme.wallOf(this, cid))
        item("🎨", t("Chat theme", "చాట్ థీమ్"), if (te) wall.te else wall.en) { pickWall() }
        if (!c.isLocal && c.member) {
            val canTimer = !(c.isGroup && c.adminsEdit && !c.isAdmin(me))
            item("⏱", t("Disappearing messages", "కనిపించకుండా పోయే మెసేజ్‌లు"), ChatTheme.timerText(ChatTheme.disappear(this, cid), te), onClick = if (canTimer) ({ pickTimer() }) else null)
        }
        if (!c.isSelf) toggle("🔒", t("Chat lock", "చాట్ లాక్"), t("Hide it in 🔒 Locked chats; opens with your fingerprint", "🔒 లాక్ చేసిన చాట్‌లలో దాచు; వేలిముద్రతో తెరుచుకుంటుంది"), ChatTheme.isHidden(this, cid)) { db.setKv("lock:$cid", if (it) "1" else ""); ChatSync.changed() }
    }
    private fun endRows(c: ChatConv) {
        line()
        item("📋", t("Add to list", "లిస్ట్‌లో చేర్చు"), ChatLists.all(this).filter { cid in it.cids }.joinToString(", ") { it.name }) { ChatLists.pick(this, cid) { draw() } }
        toggle("⭐", t("Favourite", "ఇష్టమైనది"), t("Shows under ⭐ Favourites", "⭐ ఇష్టమైనవిలో కనిపిస్తుంది"), db.kv("fav:$cid") == "1") { db.setKv("fav:$cid", if (it) "1" else ""); ChatSync.changed() }
        item("📲", t("Add shortcut", "షార్ట్‌కట్ చేర్చు"), t("Open this chat from your home screen", "హోమ్ స్క్రీన్ నుంచి ఈ చాట్ తెరవండి")) { ChatShortcut.add(this, c) }
        item("📤", t("Export chat", "చాట్ ఎగుమతి"), "") {
            AlertDialog.Builder(this).setTitle(t("📤 Export chat", "📤 చాట్ ఎగుమతి")).setMessage(t("Photos and files too?", "ఫోటోలు, ఫైల్స్ కూడా కావాలా?"))
                .setPositiveButton(t("Include media", "మీడియాతో")) { _, _ -> ChatExport.export(this, c, true) }.setNeutralButton(t("Without media", "మీడియా లేకుండా")) { _, _ -> ChatExport.export(this, c, false) }
                .setNegativeButton(t("Cancel", "వద్దు"), null).show()
        }
        item("🧹", t("Clear chat", "చాట్ క్లియర్"), t("On this phone", "ఈ ఫోన్‌లో"), C.DANGER) {
            AlertDialog.Builder(this).setMessage(t("Clear all messages in this chat on this phone?", "ఈ ఫోన్‌లో ఈ చాట్ మెసేజ్‌లు అన్నీ తొలగించాలా?"))
                .setPositiveButton(t("Clear", "తొలగించు")) { _, _ -> db.clear(cid, false); ChatSync.changed() }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
        }
    }

    // =================== contact info ===================
    private fun contactInfo(c: ChatConv) {
        top(c, c.data.optString("handle").let { if (it.isNotEmpty()) "@$it" else "" }, "")
        if (c.accepted && !c.blocked && c.member) buttons(
            bigButton("📞", t("Voice", "వాయిస్")) { startActivity(Intent(this, ChatActivity::class.java).putExtra("cid", cid).putExtra("call", "voice").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
            bigButton("🎥", t("Video", "వీడియో")) { startActivity(Intent(this, ChatActivity::class.java).putExtra("cid", cid).putExtra("call", "video").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
            bigButton("🔍", t("Search", "వెతకండి")) { startActivity(Intent(this, ChatActivity::class.java).putExtra("cid", cid).putExtra("search", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) })
        bio?.takeIf { it.isNotBlank() }?.let { head(t("About", "గురించి")); box.addView(ui.text(it, 15f, C.TXT).apply { setPadding(dp(4), 0, dp(4), dp(4)) }) }
        line()
        commonRows(c)
        item("🛡️", t("Encryption", "ఎన్‌క్రిప్షన్"), t("Messages and calls are locked end to end. Tap to check the safety number.", "మెసేజ్‌లు, కాల్స్ ఎండ్ టు ఎండ్ లాక్. సేఫ్టీ నంబర్ చూడటానికి నొక్కండి.")) { safety(c) }
        // groups we're both in
        val other = c.otherUid(me)
        val common = db.convs().filter { it.isGroup && it.member && it.members().any { m -> m.optString("uid") == other } }
        if (common.isNotEmpty()) {
            head(t("${common.size} groups in common", "${common.size} గ్రూప్‌లలో ఇద్దరూ ఉన్నారు"))
            common.forEach { g -> item(g.emoji, g.name, g.members().size.toString() + t(" people", " మంది")) { ChatActivity.open(this, g.cid) } }
        }
        endRows(c)
        line()
        item("🚫", if (c.blocked) t("Unblock ${c.name}", "${c.name} ని అన్‌బ్లాక్") else t("Block ${c.name}", "${c.name} ని బ్లాక్"), "", C.DANGER) {
            post("/api/chat/block", JSONObject().put("uid", other).put("on", !c.blocked)) { toast(if (c.blocked) t("Unblocked", "అన్‌బ్లాక్ చేశాం") else t("Blocked", "బ్లాక్ చేశాం")) }
        }
        item("⚠️", t("Report ${c.name}", "${c.name} ని రిపోర్ట్"), t("The last messages go to the Habit team", "చివరి మెసేజ్‌లు Habit టీమ్‌కి వెళ్తాయి"), C.DANGER) { report(c, other) }
    }
    /** The safety number: the same 60 digits on both phones means the chat is locked for the right person. */
    private fun safety(c: ChatConv) {
        val other = c.otherUid(me)
        fun show(theirs: String?) {
            if (theirs.isNullOrEmpty()) { toast(t("Couldn't get their key. Check your internet.", "వాళ్ళ కీ రాలేదు. ఇంటర్నెట్ చూడండి.")); return }
            val code = SafetyCode.of(ChatKeys.mine(this).second, theirs)
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(10), dp(20), dp(4))
                addView(ui.text(code, 19f, Ui.INK).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; letterSpacing = 0.05f })
                addView(ui.text(t("Compare these numbers with ${c.name}'s phone (their Contact info › Encryption). If they are the same, your chat is locked for the right person, and nobody in between can read it.",
                    "ఈ నంబర్లను ${c.name} ఫోన్‌లో (కాంటాక్ట్ వివరాలు › ఎన్‌క్రిప్షన్) ఉన్నవాటితో పోల్చండి. ఒకటే అయితే, మీ చాట్ సరైన వ్యక్తికే లాక్ అయింది, మధ్యలో ఎవరూ చదవలేరు."), 13.5f, Ui.MUTED).apply { setPadding(0, dp(14), 0, 0) })
            }
            AlertDialog.Builder(this).setTitle(t("🛡️ Safety number", "🛡️ సేఫ్టీ నంబర్")).setView(v)
                .setNeutralButton(t("📋 Copy", "📋 కాపీ")) { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Habit safety number", code.replace("\n", " "))) }
                .setPositiveButton(t("Close", "మూసివేయి"), null).show()
        }
        val cached = try { JSONObject(db.kv("keys:$cid").ifEmpty { "{}" }).optJSONObject(other)?.optString("pub") } catch (e: Exception) { null }
        if (!cached.isNullOrEmpty()) { show(cached); return }
        Thread {
            val r = ChatSync.api(store, "GET", "/api/chat/keys?cid=$cid")
            if (r.ok) db.setKv("keys:$cid", r.json.optJSONObject("keys")?.toString() ?: "{}")
            runOnUiThread { show(r.json.optJSONObject("keys")?.optJSONObject(other)?.optString("pub")) }
        }.start()
    }
    private fun report(c: ChatConv, who: String) {
        val reasons = arrayOf(t("Spam or fraud", "స్పామ్ / మోసం"), t("Bad or abusive words", "చెడ్డ మాటలు"), t("Troubling a child", "పిల్లలను ఇబ్బంది పెట్టడం"), t("Something else", "ఇంకేదైనా"))
        AlertDialog.Builder(this).setTitle(t("⚠️ Report: why?", "⚠️ రిపోర్ట్: ఎందుకు?")).setItems(reasons) { _, i ->
            val arr = JSONArray()
            for (m in db.msgs(cid).filter { it.sender != me && it.kind !in ChatDb.HIDDEN }.takeLast(10)) arr.put(JSONObject().put("from", m.sender).put("text", ChatSync.preview(this, m)).put("at", m.at))
            post("/api/chat/report", JSONObject().put("uid", who).put("reason", reasons[i]).put("msgs", arr)) { toast(t("Reported. Thank you: the Habit team will look at it.", "రిపోర్ట్ చేశాం. ధన్యవాదాలు.")) }
        }.show()
    }

    // =================== group info ===================
    private fun groupInfo(c: ChatConv) {
        val admin = c.isAdmin(me)
        val canEdit = c.member && (admin || !c.adminsEdit)
        top(c, t("Group · ${c.members().size} members", "గ్రూప్ · ${c.members().size} మంది"), "")
        if (canEdit) box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(ui.text("✏️ " + t("Name", "పేరు"), 13.5f, C.GOLD, bold = true).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { rename(c) } })
            addView(ui.text("🖼️ " + t("Picture", "చిత్రం"), 13.5f, C.GOLD, bold = true).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { pickEmoji(c) } })
        })
        c.comm?.let { k -> box.addView(ui.text("🏘️ " + t("Part of the community ", "కమ్యూనిటీ: ") + k.optString("name") + " ›", 14f, C.GOLD, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); setOnClickListener { Communities.show(this@ChatInfoActivity, store, k.optString("id")) } }) }
        buttons(
            bigButton("🔍", t("Search", "వెతకండి")) { startActivity(Intent(this, ChatActivity::class.java).putExtra("cid", cid).putExtra("search", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
            *(if (admin && c.member) arrayOf<View>(bigButton("➕", t("Add", "చేర్చు")) { addPeople(c) }, bigButton("🔗", t("Invite link", "ఆహ్వాన లింక్")) { inviteLink(c) }) else emptyArray<View>()))
        // the description
        head(t("Description", "వివరణ"))
        box.addView(ui.text(c.desc.ifBlank { if (canEdit) t("Add a group description", "గ్రూప్ వివరణ చేర్చండి") else t("No description", "వివరణ లేదు") }, 15f, if (c.desc.isBlank()) C.SUB else C.TXT).apply {
            setPadding(dp(4), dp(2), dp(4), dp(6)); if (canEdit) setOnClickListener { editDesc(c) } })
        box.addView(ui.text(t("Made by ", "చేసినవారు: ") + (if (c.data.optString("by") == me) t("you", "మీరు") else c.nameOf(c.data.optString("by"))) + " · " +
            Instant.ofEpochMilli(c.data.optLong("at")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)), 12.5f, C.SUB).apply { setPadding(dp(4), 0, dp(4), dp(4)) })
        line()
        commonRows(c)
        if (admin && c.member) {
            head(t("Group settings (admins)", "గ్రూప్ సెట్టింగ్స్ (అడ్మిన్లు)"))
            item("💬", t("Send messages", "మెసేజ్‌లు పంపేది"), if (c.onlyAdmins) t("Only admins", "అడ్మిన్లు మాత్రమే") else t("All members", "అందరూ")) {
                choose(t("Who can send messages?", "ఎవరు మెసేజ్ పంపగలరు?"), c.onlyAdmins) { only -> post("/api/chat/group/edit", JSONObject().put("onlyAdmins", only)) }
            }
            item("✏️", t("Edit group info and settings", "గ్రూప్ వివరాలు, సెట్టింగ్స్ మార్చేది"), (if (c.adminsEdit) t("Only admins", "అడ్మిన్లు మాత్రమే") else t("All members", "అందరూ")) + t(" · name, picture, description, disappearing messages, pins", " · పేరు, చిత్రం, వివరణ, కనిపించకుండా పోయే మెసేజ్‌లు, పిన్‌లు")) {
                choose(t("Who can edit the group info?", "గ్రూప్ వివరాలు ఎవరు మార్చగలరు?"), c.adminsEdit) { only -> post("/api/chat/group/edit", JSONObject().put("adminsEdit", only)) }
            }
        }
        // the members
        head(t("${c.members().size} members", "${c.members().size} మంది"))
        if (admin && c.member) {
            item("➕", t("Add members", "వ్యక్తులను చేర్చు"), "") { addPeople(c) }
            item("🔗", t("Invite via link", "లింక్‌తో ఆహ్వానించు"), if (c.data.optString("link").isNotEmpty()) t("On", "ఆన్") else "") { inviteLink(c) }
        }
        val members = c.members().sortedWith(compareBy<JSONObject> { if (it.optString("uid") == me) 0 else 1 }.thenByDescending { it.optBoolean("admin") }.thenBy { it.optString("name").lowercase() })
        for (p in members) {
            val uid = p.optString("uid"); val isA = p.optBoolean("admin")
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(9), dp(4), dp(9))
                addView(ChatDraw.avatar(this@ChatInfoActivity, store, p.optString("emoji", "🐾"), p.optString("avatar"), dp(42)), LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(LinearLayout(this@ChatInfoActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0)
                    addView(ui.text(if (uid == me) t("You", "మీరు") else p.optString("name"), 15.5f, C.TXT, bold = true))
                    if (p.optString("handle").isNotEmpty()) addView(ui.text("@" + p.optString("handle"), 12.5f, C.SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (isA) addView(ui.text(t("Group admin", "అడ్మిన్"), 11.5f, C.GOLD, bold = true).apply { setPadding(dp(8), dp(2), dp(8), dp(2)); background = C.round(C.CARD, dp(8).toFloat(), C.GOLD) })
                if (uid != me) setOnClickListener { memberMenu(c, p, admin && c.member) }
            })
        }
        endRows(c)
        line()
        if (c.member) item("🚪", if (c.isAnn) t("Exit the community", "కమ్యూనిటీ నుంచి వెళ్ళిపోండి") else t("Exit group", "గ్రూప్ వదిలేయండి"), "", C.DANGER) {
            AlertDialog.Builder(this).setMessage(t("Exit \"${c.name}\"?", "\"${c.name}\" నుంచి వెళ్ళిపోవాలా?")).setPositiveButton(t("Exit", "వెళ్ళిపోండి")) { _, _ ->
                if (c.isAnn && c.comm != null) {
                    Thread { val r = ChatSync.api(store, "POST", "/api/chat/community/leave", JSONObject().put("id", c.comm?.optString("id")).toString()); runOnUiThread { if (r.ok) { db.patchConv(cid) { it.put("member", false) }; db.clear(cid, true); ChatSync.changed(); finish() } else toast(r.error("Couldn't do it.")) } }.start()
                } else post("/api/chat/leave", JSONObject()) { db.patchConv(cid) { it.put("member", false) }; db.clear(cid, true); ChatSync.changed(); finish() }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
        }
        item("⚠️", t("Report group", "గ్రూప్ రిపోర్ట్"), "", C.DANGER) { report(c, "") }
    }
    private fun choose(title: String, onlyNow: Boolean, done: (Boolean) -> Unit) {
        val opts = arrayOf(t("All members", "అందరూ"), t("Only admins", "అడ్మిన్లు మాత్రమే"))
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(opts, if (onlyNow) 1 else 0) { d, i -> d.dismiss(); if ((i == 1) != onlyNow) done(i == 1) }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
    private fun memberMenu(c: ChatConv, p: JSONObject, admin: Boolean) {
        val uid = p.optString("uid"); val isA = p.optBoolean("admin")
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts.add(t("💬 Message ${p.optString("name")}", "💬 ${p.optString("name")} కి మెసేజ్") to {
            Thread {
                val r = ChatSync.api(store, "POST", "/api/chat/open", JSONObject().put("uid", uid).toString())
                runOnUiThread { val cv = r.json.optJSONObject("conv"); if (r.ok && cv != null) { db.putConv(cv); ChatActivity.open(this, cv.optString("cid")) } else toast(r.error("Couldn't open the chat.")) }
            }.start()
        })
        if (admin) {
            opts.add((if (isA) t("Dismiss as admin", "అడ్మిన్ నుంచి తీసేయి") else t("⭐ Make group admin", "⭐ అడ్మిన్ చేయి")) to { post("/api/chat/group/edit", JSONObject().put(if (isA) "unadmin" else "admin", JSONArray().put(uid))) })
            opts.add(t("🚫 Remove from group", "🚫 గ్రూప్ నుంచి తీసేయి") to {
                AlertDialog.Builder(this).setMessage(t("Remove ${p.optString("name")} from the group?", "${p.optString("name")} ని గ్రూప్ నుంచి తీసేయాలా?"))
                    .setPositiveButton(t("Remove", "తీసేయి")) { _, _ -> post("/api/chat/group/edit", JSONObject().put("remove", JSONArray().put(uid))) }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
            })
        }
        AlertDialog.Builder(this).setTitle(p.optString("name")).setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }
    private fun addPeople(c: ChatConv) {
        ChatPeople.pick(this, store, t("Add to the group", "గ్రూప్‌లో చేర్చండి"), c.members().map { it.optString("uid") }.toSet()) { picked ->
            post("/api/chat/group/edit", JSONObject().put("add", JSONArray(picked.map { it.optString("uid") })))
        }
    }
    private fun rename(c: ChatConv) {
        val inp = ui.input(t("Group name", "గ్రూప్ పేరు"), c.name)
        AlertDialog.Builder(this).setTitle(t("✏️ Group name", "✏️ గ్రూప్ పేరు")).setView(FrameLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(inp) })
            .setPositiveButton(t("Save", "సేవ్")) { _, _ -> val n = inp.text.toString().trim(); if (n.length >= 2) post("/api/chat/group/edit", JSONObject().put("name", n)) }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun editDesc(c: ChatConv) {
        val inp = ui.input(t("What is this group for?", "ఈ గ్రూప్ దేనికి?"), c.desc, multi = true)
        AlertDialog.Builder(this).setTitle(t("📝 Group description", "📝 గ్రూప్ వివరణ")).setView(FrameLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(inp) })
            .setPositiveButton(t("Save", "సేవ్")) { _, _ -> post("/api/chat/group/edit", JSONObject().put("desc", inp.text.toString().trim().take(500))) }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun pickEmoji(c: ChatConv) {
        val list = listOf("👥", "🏛️", "🏢", "💼", "📋", "🏠", "👨‍👩‍👧‍👦", "❤️", "🎉", "🏏", "📚", "🙏", "🌾", "🚜", "🏥", "🚓", "🎓", "⭐", "🌻", "🐾", "🍛", "✈️", "🛕", "🇮🇳")
        AlertDialog.Builder(this).setTitle(t("🖼️ Group picture", "🖼️ గ్రూప్ చిత్రం")).setItems(list.toTypedArray()) { _, i -> post("/api/chat/group/edit", JSONObject().put("emoji", list[i])) }.show()
    }
    /** The group's invite link: share it, copy it, make a new one (the old stops working), or turn it off. */
    private fun inviteLink(c: ChatConv) {
        Thread {
            val r = ChatSync.api(store, "POST", "/api/chat/group/link", JSONObject().put("cid", cid).toString())
            runOnUiThread {
                if (!r.ok) { toast(r.error("Couldn't make the link.")); return@runOnUiThread }
                r.json.optJSONObject("conv")?.let { db.putConv(it) }
                val url = r.json.optString("url")
                val text = t("Join my Habit Chat group \"${c.name}\": $url", "నా Habit చాట్ గ్రూప్ \"${c.name}\" లో చేరండి: $url")
                AlertDialog.Builder(this).setTitle(t("🔗 Invite link", "🔗 ఆహ్వాన లింక్"))
                    .setMessage(url + "\n\n" + t("Anyone with Habit AI can join with this link. Share it only with people you trust.", "ఈ లింక్‌తో Habit AI ఉన్న ఎవరైనా చేరవచ్చు. నమ్మకమైనవాళ్ళకే పంపండి."))
                    .setPositiveButton(t("📤 Share", "📤 పంపండి")) { _, _ -> try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), t("Share link", "లింక్ పంపండి"))) } catch (e: Exception) { } }
                    .setNeutralButton(t("More…", "ఇంకా…")) { _, _ ->
                        val more = arrayOf(t("📋 Copy link", "📋 లింక్ కాపీ"), t("🔄 Reset link (the old one stops)", "🔄 కొత్త లింక్ (పాతది పనిచేయదు)"), t("⛔ Turn the link off", "⛔ లింక్ ఆపేయి"))
                        AlertDialog.Builder(this).setItems(more) { _, i ->
                            when (i) {
                                0 -> { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Habit group link", url)); toast(t("Copied", "కాపీ అయింది")) }
                                1 -> post("/api/chat/group/link", JSONObject().put("reset", true)) { toast(t("New link made. The old one doesn't work now.", "కొత్త లింక్ చేశాం. పాతది ఇప్పుడు పనిచేయదు.")) }
                                else -> post("/api/chat/group/link", JSONObject().put("off", true)) { toast(t("The invite link is off.", "ఆహ్వాన లింక్ ఆపేశాం.")) }
                            }
                        }.show()
                    }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
            }
        }.start()
    }

    // =================== broadcast list ===================
    private fun bcastInfo(c: ChatConv) {
        top(c, t("${c.members().size} recipients", "${c.members().size} మంది"), t("Only people who have chatted with you get your broadcasts, each in their own chat.", "మీతో చాట్ చేసినవాళ్ళకు మాత్రమే, వాళ్ళ సొంత చాట్‌లో వెళ్తాయి."))
        item("✏️", t("List name", "లిస్ట్ పేరు"), c.name) {
            val inp = ui.input(t("List name", "లిస్ట్ పేరు"), c.name)
            AlertDialog.Builder(this).setView(FrameLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(inp) })
                .setPositiveButton(t("Save", "సేవ్")) { _, _ -> val n = inp.text.toString().trim(); if (n.isNotEmpty()) { db.patchConv(cid) { it.put("name", n.take(40)) }; ChatSync.changed() } }
                .setNegativeButton(t("Cancel", "వద్దు"), null).show()
        }
        item("➕", t("Edit recipients", "వ్యక్తులను మార్చు"), "") { Broadcasts.edit(this, store, cid) { draw() } }
        head(t("Recipients", "వ్యక్తులు"))
        for (p in c.members()) box.addView(ui.text(p.optString("emoji", "🐾") + "   " + p.optString("name"), 15.5f, C.TXT).apply { setPadding(dp(8), dp(9), dp(8), dp(9)) })
        line()
        mediaStrip(c)
        val wall = ChatTheme.wall(ChatTheme.wallOf(this, cid))
        item("🎨", t("Chat theme", "చాట్ థీమ్"), if (te) wall.te else wall.en) { pickWall() }
        item("🗑️", t("Delete broadcast list", "బ్రాడ్‌కాస్ట్ లిస్ట్ తొలగించు"), "", C.DANGER) {
            AlertDialog.Builder(this).setMessage(t("Delete this broadcast list? Messages already sent stay in each person's chat.", "ఈ బ్రాడ్‌కాస్ట్ లిస్ట్ తొలగించాలా? పంపిన మెసేజ్‌లు వాళ్ళ చాట్‌లలో ఉంటాయి."))
                .setPositiveButton(t("Delete", "తొలగించు")) { _, _ -> db.dropConv(cid); ChatSync.changed(); finish() }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
        }
    }

    // =================== media, links and docs ===================
    private fun media(c: ChatConv) {
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(10)) }
        listOf("media" to t("Media", "మీడియా"), "docs" to t("Docs", "ఫైల్స్"), "links" to t("Links", "లింక్స్")).forEach { (k, l) ->
            tabs.addView(ui.text(l, 14.5f, if (mediaTab == k) C.NAVY else C.TXT, bold = mediaTab == k).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); background = C.round(if (mediaTab == k) C.GOLD else C.CARD, dp(18).toFloat())
                setOnClickListener { mediaTab = k; draw() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        box.addView(tabs)
        when (mediaTab) {
            "docs" -> {
                val docs = db.ofKinds(cid, listOf("doc"), 500)
                if (docs.isEmpty()) box.addView(ui.text(t("No documents yet.", "ఇంకా ఫైల్స్ లేవు."), 14f, C.SUB).apply { setPadding(dp(6), dp(16), dp(6), dp(6)) })
                for (m in docs) item(if (m.body.optString("mime").startsWith("audio")) "🎵" else if (m.body.optString("name").lowercase().endsWith(".pdf")) "📕" else "📄", m.body.optString("name").ifBlank { t("Document", "ఫైల్") },
                    when (val n = m.body.optLong("size")) { in 1_000_000..Long.MAX_VALUE -> "%.1f MB".format(Locale.ENGLISH, n / 1_000_000.0); in 1..999_999 -> "${n / 1000} KB"; else -> "" } + " · " + date(m.at)) { openMedia(m) }
            }
            "links" -> {
                val links = db.withLinks(cid)
                if (links.isEmpty()) box.addView(ui.text(t("No links yet.", "ఇంకా లింక్స్ లేవు."), 14f, C.SUB).apply { setPadding(dp(6), dp(16), dp(6), dp(6)) })
                for (m in links) {
                    val u = LinkPreview.firstUrl(m.body.optString("text")) ?: continue
                    val lp = m.body.optJSONObject("lp")
                    item("🔗", lp?.optString("t")?.ifBlank { null } ?: u, (if (lp != null) u + "\n" else "") + date(m.at)) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { } }
                }
            }
            else -> {
                val media = db.ofKinds(cid, listOf("photo", "video", "gif"), 600).filter { !it.body.optBoolean("once") }
                if (media.isEmpty()) { box.addView(ui.text(t("No photos or videos yet.", "ఇంకా ఫోటోలు, వీడియోలు లేవు."), 14f, C.SUB).apply { setPadding(dp(6), dp(16), dp(6), dp(6)) }); return }
                val size = (resources.displayMetrics.widthPixels - dp(32) - dp(12)) / 3
                media.chunked(3).forEach { row ->
                    box.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        row.forEach { m -> addView(thumb(m, size), LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(6); bottomMargin = dp(6) }) }
                    })
                }
                box.addView(ui.text(t("Hold a picture to see it in the chat.", "చాట్‌లో చూడటానికి ఫోటోని నొక్కి పట్టండి."), 12.5f, C.SUB).apply { setPadding(dp(4), dp(6), dp(4), 0) })
            }
        }
    }
    private fun date(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

    // =================== ⭐ starred messages ===================
    private fun starred() {
        val list = db.starred(cid)
        if (list.isEmpty()) { box.addView(ui.text(t("⭐ No starred messages. Hold a message › ⭐ Star to keep it here.", "⭐ స్టార్ చేసిన మెసేజ్‌లు లేవు. మెసేజ్‌ని నొక్కి పట్టి › ⭐ స్టార్ నొక్కండి."), 14.5f, C.SUB).apply { setPadding(dp(6), dp(20), dp(6), dp(6)) }); return }
        for (m in list) {
            val c = db.conv(m.cid) ?: continue
            val who = if (m.sender == me) t("You", "మీరు") else c.nameOf(m.sender)
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = C.round(C.CARD, dp(12).toFloat())
                addView(ui.text("$who  ›  ${c.name}", 12.5f, C.GOLD, bold = true))
                addView(ui.text(ChatSync.preview(this@ChatInfoActivity, m), 15f, C.TXT).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, dp(3)) })
                addView(ui.text("⭐ " + date(m.at), 12f, C.SUB))
                setOnClickListener { ChatActivity.openAt(this@ChatInfoActivity, m.cid, m.id) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@ChatInfoActivity).setItems(arrayOf(t("⭐ Unstar", "⭐ స్టార్ తీసేయి"))) { _, _ -> ChatSync.star(this@ChatInfoActivity, m, false); draw() }.show(); true
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun pickWall() {
        val walls = ChatTheme.WALLS
        val cur = ChatTheme.wallOf(this, cid)
        AlertDialog.Builder(this).setTitle(t("🎨 Chat theme", "🎨 చాట్ థీమ్"))
            .setSingleChoiceItems(walls.map { if (te) it.te else it.en }.toTypedArray(), walls.indexOfFirst { it.id == cur }) { d, i -> db.setKv("wall:$cid", walls[i].id.toString()); d.dismiss(); draw() }
            .setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
    private fun pickTimer() {
        val cur = ChatTheme.disappear(this, cid)
        AlertDialog.Builder(this).setTitle(t("⏱ Disappearing messages", "⏱ కనిపించకుండా పోయే మెసేజ్‌లు"))
            .setSingleChoiceItems(ChatTheme.TIMERS.map { ChatTheme.timerText(it, te) }.toTypedArray(), ChatTheme.TIMERS.indexOfFirst { it == cur }.coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (ChatTheme.TIMERS[i] != cur) ChatSync.setTimer(this, store, cid, ChatTheme.TIMERS[i]) { e -> if (e != null && !e.startsWith("No connection")) toast(e) }
                draw()
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
}
