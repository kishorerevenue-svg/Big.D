package com.soulfriend.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Status updates, like WhatsApp's "Updates": a text, photo or video that stays 24 hours. Locked end to end on my phone
 * for the people I chat with one-to-one (all of them, all except some, or only some: Status privacy); the server keeps
 * only the locked update and who has seen it.
 */
object ChatStatus {
    class Item(val owner: String, val id: String, val at: Long, val kind: String, val body: JSONObject, var seen: Boolean)
    class Owner(val uid: String, val name: String, val emoji: String, val avatar: String, val items: List<Item>) {
        val unseen get() = items.any { !it.seen }
        val last get() = items.maxOfOrNull { it.at } ?: 0L
    }
    class Mine(val item: Item, val views: List<JSONObject>)
    @Volatile var others: List<Owner> = emptyList()
    @Volatile var mine: List<Mine> = emptyList()
    @Volatile private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private const val DAY = 86_400_000L

    private fun parseItem(owner: String, o: JSONObject, seen: Boolean) = Item(owner, o.optString("id"), o.optLong("at"), o.optString("kind", "text"), o.optJSONObject("body") ?: JSONObject(), seen)
    private fun itemJson(i: Item) = JSONObject().put("id", i.id).put("at", i.at).put("kind", i.kind).put("body", i.body).put("seen", i.seen)

    /** What was last fetched (kept on the phone, so Updates shows at once). */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val j = JSONObject(ChatDb.get(ctx).kv("status:cache").ifEmpty { return })
            val now = System.currentTimeMillis()
            others = (j.optJSONArray("others") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.map { o ->
                val items = (o.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.map { parseItem(o.optString("uid"), it, it.optBoolean("seen")) }.filter { now - it.at < DAY }
                Owner(o.optString("uid"), o.optString("name"), o.optString("emoji", "🐾"), o.optString("avatar"), items)
            }.filter { it.items.isNotEmpty() }
            mine = (j.optJSONArray("mine") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.map { o ->
                Mine(parseItem("me", o, true), (o.optJSONArray("views") ?: JSONArray()).let { v -> (0 until v.length()).mapNotNull { v.optJSONObject(it) } })
            }.filter { now - it.item.at < DAY }
        } catch (e: Exception) { }
    }
    private fun save(ctx: Context) {
        val j = JSONObject()
        j.put("others", JSONArray(others.map { o -> JSONObject().put("uid", o.uid).put("name", o.name).put("emoji", o.emoji).put("avatar", o.avatar).put("items", JSONArray(o.items.map { itemJson(it) })) }))
        j.put("mine", JSONArray(mine.map { itemJson(it.item).put("views", JSONArray(it.views)) }))
        ChatDb.get(ctx).setKv("status:cache", j.toString())
    }
    private fun seenSet(ctx: Context): MutableSet<String> = try { JSONArray(ChatDb.get(ctx).kv("status:seen").ifEmpty { "[]" }).let { a -> (0 until a.length()).map { a.optString(it) }.toMutableSet() } } catch (e: Exception) { mutableSetOf() }

    /** Asks the server for the updates I can see (and mine, with who has seen them), and opens them here. */
    fun refresh(ctx: Context, store: Store): Boolean {
        load(ctx)
        val r = ChatSync.api(store, "GET", "/api/chat/status", timeoutMs = 20_000)
        if (!r.ok) return false
        val priv = ChatKeys.mine(ctx).first
        fun open(env: JSONObject?): JSONObject? = env?.let { e -> ChatCrypto.open(e.optString("epk"), e.optString("iv"), e.optString("ct"), e.optString("key"), priv) }?.let { try { JSONObject(String(it, Charsets.UTF_8)) } catch (x: Exception) { null } }
        val seenHere = seenSet(ctx)
        val old = (others.flatMap { it.items } + mine.map { it.item }).associateBy { it.owner + "/" + it.id }
        others = (r.json.optJSONArray("updates") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.mapNotNull { u ->
            val owner = u.optString("owner"); val p = u.optJSONObject("person") ?: JSONObject()
            val items = (u.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.mapNotNull { o ->
                val prev = old["$owner/" + o.optString("id")]
                val body = prev?.body ?: open(o.optJSONObject("env")) ?: return@mapNotNull null
                Item(owner, o.optString("id"), o.optLong("at"), o.optString("kind", "text"), body, o.optBoolean("seen") || "$owner/${o.optString("id")}" in seenHere)
            }.sortedBy { it.at }
            if (items.isEmpty()) null else Owner(owner, p.optString("name", "Habit friend"), p.optString("emoji", "🐾"), p.optString("avatar"), items)
        }
        mine = (r.json.optJSONArray("mine") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.mapNotNull { o ->
            val prev = old["me/" + o.optString("id")]
            val body = prev?.body ?: open(o.optJSONObject("env")) ?: return@mapNotNull null
            ChatDb.get(ctx).kv("status:local:" + o.optString("id")).takeIf { it.isNotEmpty() && File(it).exists() }?.let { body.put("local", it) }
            Mine(Item("me", o.optString("id"), o.optLong("at"), o.optString("kind", "text"), body, true), (o.optJSONArray("views") ?: JSONArray()).let { v -> (0 until v.length()).mapNotNull { v.optJSONObject(it) } })
        }.sortedBy { it.item.at }
        save(ctx)
        ChatSync.changed()
        // photos and videos come in the background; old ones go
        for (i in others.flatMap { it.items }) if (i.body.optString("file").isNotEmpty() && i.body.optString("local").let { it.isEmpty() || !File(it).exists() }) fetch(ctx, store, i)
        try { ChatSync.dir(ctx).listFiles()?.filter { it.name.startsWith("st_") && System.currentTimeMillis() - it.lastModified() > DAY + 3_600_000L }?.forEach { it.delete() } } catch (e: Exception) { }
        return true
    }
    private fun fetch(ctx: Context, store: Store, i: Item) {
        val locked = ChatSync.downloadLocked(store, i.body.optString("file")) ?: return
        val bytes = ChatCrypto.openFile(locked, i.body.optString("key"), i.body.optString("iv")) ?: return
        val f = File(ChatSync.dir(ctx), "st_" + i.id + if (i.kind == "video") ".mp4" else ".jpg")
        f.writeBytes(bytes)
        i.body.put("local", f.absolutePath)
        save(ctx); ChatSync.changed()
    }

    /** People who see my updates: "all" (everyone I chat with), "except" (all but [uids]), "only" ([uids]). */
    fun privacy(ctx: Context): Pair<String, Set<String>> = try {
        val j = JSONObject(ChatDb.get(ctx).kv("status:privacy").ifEmpty { "{}" })
        Pair(j.optString("mode", "all"), (j.optJSONArray("uids") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() })
    } catch (e: Exception) { Pair("all", emptySet()) }
    fun setPrivacy(ctx: Context, mode: String, uids: Set<String>) { ChatDb.get(ctx).setKv("status:privacy", JSONObject().put("mode", mode).put("uids", JSONArray(uids.toList())).toString()) }
    fun muted(ctx: Context): Set<String> = try { JSONArray(ChatDb.get(ctx).kv("status:muted").ifEmpty { "[]" }).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } } catch (e: Exception) { emptySet() }
    fun mute(ctx: Context, uid: String, on: Boolean) { val s = muted(ctx).toMutableSet(); if (on) s.add(uid) else s.remove(uid); ChatDb.get(ctx).setKv("status:muted", JSONArray(s.toList()).toString()); ChatSync.changed() }
    /** New updates I haven't seen (not counting muted people). */
    fun unseenCount(ctx: Context): Int { load(ctx); val m = muted(ctx); return others.count { it.unseen && it.uid !in m } }

    /** Shares a status: {t:"text", text, bg, font} or {t:"photo"/"video", text, w, h, dur, thumb} with the file's [bytes]. */
    fun post(ctx: Context, store: Store, body: JSONObject, bytes: ByteArray?, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val err = try { postNow(app, store, body, bytes) } catch (e: Exception) { "Couldn't share it. Please try again." }
            main.post { done(err) }
        }.start()
    }
    private fun postNow(ctx: Context, store: Store, body: JSONObject, bytes: ByteArray?): String? {
        val r = ChatSync.api(store, "GET", "/api/chat/contacts")
        if (!r.ok) return r.error("No connection. Check your internet.")
        val me = r.json.optJSONObject("me") ?: return "Couldn't share it."
        val (mode, uids) = privacy(ctx)
        val keys = HashMap<String, String>()
        keys[me.optString("uid")] = me.optString("pub")
        r.json.optJSONArray("people")?.let { a -> for (i in 0 until a.length()) { val p = a.optJSONObject(i) ?: continue; val u = p.optString("uid")
            if (mode == "all" || (mode == "except" && u !in uids) || (mode == "only" && u in uids)) keys[u] = p.optString("pub") } }
        val files = JSONArray()
        if (bytes != null) {
            val (locked, key, iv) = ChatCrypto.lockFile(bytes)
            val up = ChatSync.api(store, "POST", "/api/chat/file", raw = locked, timeoutMs = 90_000)
            if (!up.ok) return up.error("Couldn't upload it.")
            body.put("file", up.json.optString("id")).put("key", key).put("iv", iv)
            files.put(up.json.optString("id"))
        }
        val s = ChatCrypto.seal(ChatSync.cleanBody(body).toString().toByteArray(Charsets.UTF_8), keys)
        val env = JSONObject().put("v", 1).put("epk", s.epk).put("iv", s.iv).put("ct", s.ct).put("keys", JSONObject(s.keys)).put("kid", JSONObject(s.kids))
        val id = "s" + System.currentTimeMillis().toString(36) + (100..999).random()
        val res = ChatSync.api(store, "POST", "/api/chat/status", JSONObject().put("id", id).put("kind", body.optString("t", "text")).put("env", env).put("files", files).toString())
        if (!res.ok) return res.error("Couldn't share it.")
        if (bytes != null) try {
            val f = File(ChatSync.dir(ctx), "st_" + id + if (body.optString("t") == "video") ".mp4" else ".jpg"); f.writeBytes(bytes)
            ChatDb.get(ctx).setKv("status:local:$id", f.absolutePath)
        } catch (e: Exception) { }
        refresh(ctx, store)
        return null
    }
    fun markSeen(ctx: Context, store: Store, i: Item) {
        if (i.owner == "me" || i.seen) return
        i.seen = true
        val s = seenSet(ctx); s.add(i.owner + "/" + i.id)
        ChatDb.get(ctx).setKv("status:seen", JSONArray(s.toList().takeLast(400)).toString())
        save(ctx)
        Thread { ChatSync.api(store, "POST", "/api/chat/status/seen", JSONObject().put("owner", i.owner).put("id", i.id).toString()) }.start()
    }
    fun delete(ctx: Context, store: Store, i: Item, done: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            ChatSync.api(store, "POST", "/api/chat/status/delete", JSONObject().put("id", i.id).toString())
            ChatDb.get(app).kv("status:local:" + i.id).takeIf { it.isNotEmpty() }?.let { try { File(it).delete() } catch (e: Exception) { } }
            mine = mine.filter { it.item.id != i.id }; save(app)
            refresh(app, store)
            main.post { done() }
        }.start()
    }

    /** 🔒 Status privacy: who sees my updates. */
    fun privacyDialog(a: Activity, store: Store) {
        val te = store.lang == "te"
        val (mode, uids) = privacy(a)
        val labels = arrayOf(if (te) "నేను చాట్ చేసే అందరూ" else "Everyone I chat with", if (te) "అందరూ, వీళ్ళు తప్ప…" else "Everyone except…", if (te) "వీళ్ళకు మాత్రమే…" else "Only share with…")
        val keys = listOf("all", "except", "only")
        AlertDialog.Builder(a).setTitle(if (te) "🔒 నా స్టేటస్ ఎవరు చూడాలి?" else "🔒 Who can see my status updates?")
            .setSingleChoiceItems(labels, keys.indexOf(mode).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (i == 0) { setPrivacy(a, "all", emptySet()); return@setSingleChoiceItems }
                Thread {
                    val r = ChatSync.api(store, "GET", "/api/chat/contacts")
                    a.runOnUiThread {
                        val ppl = r.json.optJSONArray("people")?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } } ?: emptyList()
                        if (ppl.isEmpty()) { Toast.makeText(a, if (te) "ముందు ఎవరితోనైనా చాట్ మొదలుపెట్టండి." else "Start a chat with someone first.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                        val on = BooleanArray(ppl.size) { k -> ppl[k].optString("uid") in uids && keys[i] == mode }
                        AlertDialog.Builder(a).setTitle(labels[i])
                            .setMultiChoiceItems(ppl.map { it.optString("emoji", "🐾") + "  " + it.optString("name") }.toTypedArray(), on) { _, k, c -> on[k] = c }
                            .setPositiveButton(if (te) "సరే" else "Done") { _, _ -> setPrivacy(a, keys[i], ppl.filterIndexed { k, _ -> on[k] }.map { it.optString("uid") }.toSet()) }
                            .setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
                    }
                }.start()
            }.setNegativeButton(if (te) "మూసివేయి" else "Close", null).show()
    }
    fun ago(at: Long, te: Boolean): String {
        val m = (System.currentTimeMillis() - at) / 60_000
        return when {
            m < 1 -> if (te) "ఇప్పుడే" else "Just now"
            m < 60 -> if (te) "$m నిమిషాల క్రితం" else "$m minutes ago"
            else -> { val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()); (if (z.toLocalDate() == LocalDate.now()) (if (te) "ఈరోజు " else "Today, ") else (if (te) "నిన్న " else "Yesterday, ")) + z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) }
        }
    }
    val BGS = intArrayOf(Color.parseColor("#1E2550"), Color.parseColor("#0F5E5A"), Color.parseColor("#6A1B9A"), Color.parseColor("#C62828"), Color.parseColor("#EF6C00"),
        Color.parseColor("#2E7D32"), Color.parseColor("#AD1457"), Color.parseColor("#37474F"), Color.parseColor("#8D6E63"), Color.parseColor("#B8860B"))
    fun font(i: Int): Typeface = when (i % 4) { 1 -> Typeface.create("serif", Typeface.BOLD); 2 -> Typeface.create("casual", Typeface.NORMAL); 3 -> Typeface.create("sans-serif-condensed", Typeface.BOLD); else -> Typeface.DEFAULT_BOLD }
}

/**
 * Status updates on the full screen: watch someone's (tap right: next, left: back, hold: pause; reply below), watch mine
 * (👁 who has seen it, delete), or make one: a text on a colour, or a photo / video with words.
 */
class StatusActivity : Activity() {
    private lateinit var store: Store
    private lateinit var ui: Ui
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout

    // watching
    private var items: List<ChatStatus.Item> = emptyList()
    private var owner = ""
    private var ownerName = ""
    private var ownerEmoji = "🐾"
    private var idx = 0
    private var progress = 0f
    private var paused = false
    private var duration = 6000L
    private val bars = ArrayList<ProgressBar>()
    private var video: VideoView? = null
    private var lastTick = 0L

    // making one
    private var bg = 0
    private var fontIdx = 0
    private var picked: ByteArray? = null
    private var pickedKind = "photo"
    private var pickedMeta = JSONObject()

    companion object {
        private const val REQ_MEDIA = 7601
        fun watch(a: Activity, owner: String) = a.startActivity(Intent(a, StatusActivity::class.java).putExtra("mode", "view").putExtra("owner", owner))
        fun write(a: Activity) = a.startActivity(Intent(a, StatusActivity::class.java).putExtra("mode", "text"))
        fun media(a: Activity) = a.startActivity(Intent(a, StatusActivity::class.java).putExtra("mode", "media"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this)
        window.statusBarColor = Color.BLACK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); fitsSystemWindows = true }
        setContentView(root)
        ChatStatus.load(this)
        when (intent.getStringExtra("mode")) {
            "text" -> writeText()
            "media" -> pickMedia()
            else -> startWatching(intent.getStringExtra("owner") ?: "me")
        }
    }
    override fun onResume() { super.onResume(); ChatSync.hold(this, "status"); paused = false }
    override fun onPause() { super.onPause(); ChatSync.release("status"); paused = true; video?.pause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // =================== watching ===================
    private fun startWatching(o: String) {
        owner = o
        if (o == "me") { items = ChatStatus.mine.map { it.item }; ownerName = t("My status", "నా స్టేటస్"); ownerEmoji = ChatSync.me(this).optString("emoji", "🐾") }
        else ChatStatus.others.firstOrNull { it.uid == o }?.let { p -> items = p.items; ownerName = p.name; ownerEmoji = p.emoji }
        if (items.isEmpty()) { finish(); return }
        idx = if (o == "me") 0 else items.indexOfFirst { !it.seen }.let { if (it < 0) 0 else it }
        show()
        handler.post(tick)
    }
    private val tick: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (!paused && lastTick > 0) {
                progress += (now - lastTick).toFloat() / duration
                bars.getOrNull(idx)?.progress = (progress * 1000).toInt().coerceAtMost(1000)
                if (progress >= 1f) next()
            }
            lastTick = now
            handler.postDelayed(this, 50)
        }
    }
    private fun next() { if (idx < items.size - 1) { idx++; show() } else finish() }
    private fun prev() { if (idx > 0) idx--; show() }

    private fun show() {
        val it0 = items.getOrNull(idx) ?: return finish()
        progress = 0f; duration = 6000L
        video?.let { try { it.stopPlayback() } catch (e: Exception) { } }; video = null
        root.removeAllViews()
        val b = it0.body
        // the update itself
        when (it0.kind) {
            "photo", "video" -> {
                val local = b.optString("local")
                if (local.isNotEmpty() && File(local).exists()) {
                    if (it0.kind == "video") {
                        val v = VideoView(this)
                        root.addView(v, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                        v.setVideoURI(Uri.fromFile(File(local)))
                        v.setOnPreparedListener { mp -> duration = mp.duration.toLong().coerceIn(3000L, 60_000L); mp.start() }
                        v.setOnErrorListener { _, _, _ -> true }
                        video = v
                        duration = 30_000L
                    } else root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(decode(File(local), resources.displayMetrics.widthPixels)) }, FrameLayout.LayoutParams(-1, -1))
                } else {
                    // not here yet: the small preview, and a try to fetch it
                    b.optString("thumb").takeIf { it.isNotEmpty() }?.let { th -> try { val x = ChatCrypto.unb64(th); root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(BitmapFactory.decodeByteArray(x, 0, x.size)) }, FrameLayout.LayoutParams(-1, -1)) } catch (e: Exception) { } }
                    root.addView(ui.text("⏳ " + t("Getting it…", "వస్తోంది…"), 15f, Color.WHITE).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    Thread { ChatStatus.refresh(this, store); runOnUiThread { if (!isFinishing && items.getOrNull(idx)?.id == it0.id) show() } }.start()
                }
                if (b.optString("text").isNotBlank()) root.addView(ui.text(b.optString("text"), 16f, Color.WHITE).apply {
                    gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(Color.parseColor("#88000000")) }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(84) })
            }
            else -> root.addView(TextView(this).apply {
                text = b.optString("text"); textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = ChatStatus.font(b.optInt("font"))
                setPadding(dp(28), dp(28), dp(28), dp(28)); setBackgroundColor(b.optInt("bg", ChatStatus.BGS[0]))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        // tap: back / next; hold: pause
        root.addView(View(this).apply {
            var down = 0L
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { down = System.currentTimeMillis(); paused = true; video?.pause() }
                    MotionEvent.ACTION_UP -> { paused = false; video?.start(); if (System.currentTimeMillis() - down < 300) { if (e.x < v.width / 3f) prev() else next() } }
                    MotionEvent.ACTION_CANCEL -> { paused = false; video?.start() }
                }
                true
            }
        }, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(70); bottomMargin = dp(80) })
        // the top: the progress bars, who and when, ⋮ and ✕
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4)); background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bars.clear()
        items.forEachIndexed { i, _ ->
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = if (i < idx) 1000 else 0
                progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#66FFFFFF"))
            }
            bars.add(pb)
            row.addView(pb, LinearLayout.LayoutParams(0, dp(3), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        top.addView(row)
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
            addView(ui.text(ownerEmoji, 22f).apply { gravity = Gravity.CENTER; background = ChatTheme.circle(ChatTheme.CARD) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@StatusActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(ui.text(ownerName, 15.5f, Color.WHITE, bold = true))
                addView(ui.text(ChatStatus.ago(it0.at, te), 12.5f, Color.parseColor("#DDDDDD")))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text("⋮", 22f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { more(it0) } }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(ui.text("✕", 20f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(40), dp(40)))
        })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // the bottom: reply (someone's) or who has seen it (mine)
        if (owner == "me") {
            val views = ChatStatus.mine.firstOrNull { it.item.id == it0.id }?.views ?: emptyList()
            root.addView(ui.text("👁  " + views.size + "  " + t("views", "మంది చూశారు") + "  ⌃", 15f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(14), dp(12), dp(18)); setOnClickListener { viewers(views) }
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        } else {
            ChatStatus.markSeen(this, store, it0)
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(12)) }
            val inp = EditText(this).apply {
                hint = t("Reply…", "జవాబు…"); textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#BBBBBB")); setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding(dp(16), dp(10), dp(16), dp(10)); background = ui.round(Color.parseColor("#66000000"), 24, Color.parseColor("#88FFFFFF"))
                setOnFocusChangeListener { _, f -> paused = f; if (f) video?.pause() else video?.start() }
            }
            bar.addView(inp, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(ui.text("➤", 20f, ChatTheme.NAVY).apply { gravity = Gravity.CENTER; background = ChatTheme.circle(ChatTheme.GOLD); setOnClickListener { reply(it0, inp.text.toString().trim()) } },
                LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
            root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
    }

    private fun reply(i: ChatStatus.Item, text: String) {
        if (text.isEmpty()) return
        val db = ChatDb.get(this)
        val cid = db.convs().firstOrNull { it.isDm && it.otherUid(ChatSync.myUid(this)) == i.owner }?.cid
        if (cid == null) { toast(t("Open a chat with them first.", "ముందు వాళ్ళతో చాట్ తెరవండి.")); return }
        val p = "📷 " + t("Status", "స్టేటస్") + ": " + (i.body.optString("text").ifBlank { if (i.kind == "video") "🎬" else if (i.kind == "photo") "📷" else "" }).take(80)
        ChatSync.send(this, store, cid, JSONObject().put("t", "text").put("text", text.take(4000)).put("re", JSONObject().put("id", "").put("who", i.owner).put("p", p)))
        toast(t("Reply sent ✓", "జవాబు పంపాం ✓"))
        finish()
    }
    private fun viewers(views: List<JSONObject>) {
        paused = true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        if (views.isEmpty()) box.addView(ui.text(t("No one has seen it yet.", "ఇంకా ఎవరూ చూడలేదు."), 14.5f, Color.GRAY))
        for (v in views) box.addView(ui.text(v.optString("emoji", "🐾") + "  " + v.optString("name") + "   · " + ChatStatus.ago(v.optLong("at"), te), 15f, Color.WHITE).apply { setPadding(0, dp(9), 0, dp(9)) })
        AlertDialog.Builder(this).setTitle("👁 " + t("Seen by ${views.size}", "${views.size} మంది చూశారు")).setView(ScrollView(this).apply { addView(box) })
            .setOnDismissListener { paused = false }.setPositiveButton(t("Close", "మూసివేయి"), null).show()
    }
    private fun more(i: ChatStatus.Item) {
        paused = true
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (owner == "me") {
            opts.add(t("🗑️ Delete this update", "🗑️ ఈ స్టేటస్ తొలగించు") to { ChatStatus.delete(this, store, i) { toast(t("Deleted", "తొలగించాం")); finish() } })
            opts.add(t("🔒 Status privacy", "🔒 స్టేటస్ గోప్యత") to { ChatStatus.privacyDialog(this, store) })
        } else {
            val muted = owner in ChatStatus.muted(this)
            opts.add((if (muted) t("🔔 Unmute their updates", "🔔 వాళ్ళ స్టేటస్ మళ్ళీ చూపించు") else t("🔕 Mute their updates", "🔕 వాళ్ళ స్టేటస్ మ్యూట్")) to { ChatStatus.mute(this, owner, !muted); finish() })
        }
        AlertDialog.Builder(this).setItems(opts.map { it.first }.toTypedArray()) { _, k -> opts[k].second() }.setOnDismissListener { paused = false }.show()
    }
    private fun decode(f: File, w: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1; while (o.outWidth / (s * 2) >= w) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }

    // =================== making one: words on a colour ===================
    private fun writeText() {
        bg = (0 until ChatStatus.BGS.size).random()
        val page = FrameLayout(this).apply { setBackgroundColor(ChatStatus.BGS[bg]) }
        val inp = EditText(this).apply {
            hint = t("Type a status", "స్టేటస్ రాయండి"); textSize = 30f; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#AAFFFFFF")); gravity = Gravity.CENTER
            background = null; typeface = ChatStatus.font(fontIdx); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; maxLines = 8
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        page.addView(inp, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        tools.addView(ui.text("✕", 22f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        tools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tools.addView(ui.text("🎨", 22f).apply { gravity = Gravity.CENTER; contentDescription = t("Colour", "రంగు"); setOnClickListener { bg = (bg + 1) % ChatStatus.BGS.size; page.setBackgroundColor(ChatStatus.BGS[bg]) } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        tools.addView(ui.text("Aa", 20f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setOnClickListener { fontIdx = (fontIdx + 1) % 4; inp.typeface = ChatStatus.font(fontIdx) } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(tools, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val send = ui.text("➤  " + t("Share", "పంచుకో"), 16f, ChatTheme.NAVY, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(22), dp(13), dp(22), dp(13)); background = ui.round(ChatTheme.GOLD, 26)
            setOnClickListener {
                val s = inp.text.toString().trim()
                if (s.isEmpty()) { toast(t("Type something first.", "ముందు ఏదైనా రాయండి.")); return@setOnClickListener }
                share(JSONObject().put("t", "text").put("text", s.take(700)).put("bg", ChatStatus.BGS[bg]).put("font", fontIdx), null)
            }
        }
        page.addView(send, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(20)) })
        page.addView(ui.text(privacyLine(), 12.5f, Color.parseColor("#DDFFFFFF")).apply { setPadding(dp(16), 0, dp(160), dp(30)); setOnClickListener { ChatStatus.privacyDialog(this@StatusActivity, store) } },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        inp.requestFocus()
    }
    private fun privacyLine(): String = "🔒 " + when (ChatStatus.privacy(this).first) {
        "except" -> t("Everyone I chat with, except some", "అందరూ, కొందరు తప్ప"); "only" -> t("Only the people I chose", "నేను ఎంచుకున్నవాళ్ళు మాత్రమే"); else -> t("Everyone I chat with", "నేను చాట్ చేసే అందరూ") } +
        t(" · locked end to end · 24 hours", " · ఎండ్ టు ఎండ్ లాక్ · 24 గంటలు")

    // =================== making one: a photo or a video ===================
    private fun pickMedia() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            else Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")).addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_MEDIA) } catch (e: Exception) { toast(t("Couldn't open your photos.", "ఫోటోలు తెరవలేకపోయాం.")); finish() }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MEDIA) return
        val u = data?.data
        if (resultCode != RESULT_OK || u == null) { finish(); return }
        val type = contentResolver.getType(u) ?: ""
        root.addView(ui.text("⏳", 30f, Color.WHITE).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        Thread {
            val ok = if (type.startsWith("video")) readVideo(u) else readPhoto(u)
            runOnUiThread { if (ok) mediaPage(u) else { toast(if (type.startsWith("video")) t("Videos for status: up to 60 seconds and 16 MB.", "స్టేటస్ వీడియో: 60 సెకన్లు, 16 MB వరకు.") else t("Couldn't read that photo.", "ఆ ఫోటో చదవలేకపోయాం.")); finish() } }
        }.start()
    }
    private fun readPhoto(u: Uri): Boolean {
        val raw = try { contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (e: Exception) { null } ?: return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2
        var b = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return false
        val big = maxOf(b.width, b.height); if (big > 1600) b = Bitmap.createScaledBitmap(b, b.width * 1600 / big, b.height * 1600 / big, true)
        try {
            val deg = when (android.media.ExifInterface(raw.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
            if (deg != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
        } catch (e: Exception) { }
        val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val th = ByteArrayOutputStream(); Bitmap.createScaledBitmap(b, 32, maxOf(1, 32 * b.height / maxOf(1, b.width)), true).compress(Bitmap.CompressFormat.JPEG, 50, th)
        picked = out.toByteArray(); pickedKind = "photo"
        pickedMeta = JSONObject().put("w", b.width).put("h", b.height).put("thumb", ChatCrypto.b64(th.toByteArray()))
        return true
    }
    private fun readVideo(u: Uri): Boolean {
        return try {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(this, u)
            val dur = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
            val meta = JSONObject().put("dur", dur)
            r.getFrameAtTime(300_000)?.let { f -> val th = ByteArrayOutputStream(); Bitmap.createScaledBitmap(f, 160, maxOf(1, 160 * f.height / maxOf(1, f.width)), true).compress(Bitmap.CompressFormat.JPEG, 60, th); meta.put("thumb", ChatCrypto.b64(th.toByteArray())) }
            r.release()
            if (dur > 61) return false
            val bytes = contentResolver.openInputStream(u)?.use { inp -> val out = ByteArrayOutputStream(); val buf = ByteArray(65536); var n = 0L; while (true) { val k = inp.read(buf); if (k < 0) break; n += k; if (n > 16L * 1024 * 1024) return false; out.write(buf, 0, k) }; out.toByteArray() } ?: return false
            picked = bytes; pickedKind = "video"; pickedMeta = meta
            true
        } catch (e: Exception) { false }
    }
    private fun mediaPage(u: Uri) {
        root.removeAllViews()
        if (pickedKind == "video") { val v = VideoView(this); root.addView(v, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)); v.setVideoURI(u); v.setOnPreparedListener { it.isLooping = true; it.start() }; v.setOnErrorListener { _, _, _ -> true } }
        else picked?.let { p -> root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(BitmapFactory.decodeByteArray(p, 0, p.size)) }, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(ui.text("✕", 22f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { finish() } }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(12)); setBackgroundColor(Color.parseColor("#99000000")) }
        bar.addView(ui.text(privacyLine(), 12.5f, Color.parseColor("#DDFFFFFF")).apply { setPadding(dp(6), 0, dp(6), dp(6)); setOnClickListener { ChatStatus.privacyDialog(this@StatusActivity, store) } })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cap = EditText(this).apply {
            hint = t("Add a caption…", "క్యాప్షన్ రాయండి…"); textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#BBBBBB")); maxLines = 3
            setPadding(dp(16), dp(10), dp(16), dp(10)); background = ui.round(Color.parseColor("#66000000"), 24, Color.parseColor("#88FFFFFF"))
        }
        row.addView(cap, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.text("➤", 20f, ChatTheme.NAVY).apply { gravity = Gravity.CENTER; background = ChatTheme.circle(ChatTheme.GOLD)
            setOnClickListener { share(JSONObject(pickedMeta.toString()).put("t", pickedKind).put("text", cap.text.toString().trim().take(700)), picked) } },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        bar.addView(row)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private var sharing = false
    private fun share(body: JSONObject, bytes: ByteArray?) {
        if (sharing) return
        sharing = true
        toast(t("Sharing your status…", "మీ స్టేటస్ పంచుకుంటున్నాం…"))
        ChatStatus.post(this, store, body, bytes) { e ->
            sharing = false
            if (e != null) toast(e) else { toast(t("Status shared ✓ (for 24 hours)", "స్టేటస్ పంచుకున్నాం ✓ (24 గంటలు)")); finish() }
        }
    }
}
