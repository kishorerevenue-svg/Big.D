package com.soulfriend.pet

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Loads video thumbnails from the internet, keeping recent ones in memory. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, into: ImageView) {
        into.tag = url
        cache.get(url)?.let { into.setImageBitmap(it); return }
        pool.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
            } catch (e: Exception) { null }
            if (bmp != null) cache.put(url, bmp)
            main.post { if (into.tag == url && bmp != null) into.setImageBitmap(bmp) }
        }
    }
}

/**
 * Media · మీడియా: short Telugu posts in sections (news, movies, kids, crypto, real estate, jobs, devotional,
 * farming, health) written by the AI from real websites with links, plus YouTube videos, plus "People":
 * everyone's own posts and feedback. New posts arrive every morning. The BigD team can add its own posts.
 */
class MediaPage(private val m: MainActivity) {
    private class Item(val id: Int, val cat: String, val at: Long, val title: String, val body: String, val emoji: String, val source: String,
                       val url: String, val ytId: String, val ytCh: String, val by: String, var likes: Int, var liked: Boolean)

    private class Cat(val key: String, val te: String, val en: String, val icon: String, from: String, to: String) {
        val c1 = Color.parseColor(from); val c2 = Color.parseColor(to)
    }

    private val cats = listOf(
        Cat("all", "అన్నీ", "All", "✨", "#0A4F50", "#19A39A"),
        Cat("news", "వార్తలు", "News", "📰", "#1E3A8A", "#3B82F6"),
        Cat("movies", "సినిమా", "Movies", "🎬", "#7C2D12", "#F97316"),
        Cat("jobs", "ఉద్యోగాలు", "Jobs", "💼", "#312E81", "#6366F1"),
        Cat("devotional", "భక్తి", "Devotional", "🛕", "#9A3412", "#FB923C"),
        Cat("kids", "పిల్లలు", "Kids", "🧒", "#BE185D", "#F472B6"),
        Cat("health", "ఆరోగ్యం", "Health", "🩺", "#0F766E", "#2DD4BF"),
        Cat("farming", "వ్యవసాయం", "Farming", "🌾", "#3F6212", "#84CC16"),
        Cat("realestate", "రియల్ ఎస్టేట్", "Real estate", "🏠", "#065F46", "#10B981"),
        Cat("crypto", "క్రిప్టో", "Crypto", "🪙", "#92400E", "#F59E0B"),
        Cat("people", "ప్రజలు", "People", "👥", "#374151", "#9CA3AF")
    )
    private fun catOf(k: String) = cats.firstOrNull { it.key == k } ?: cats[0]

    private var cat = "all"
    private val items = HashMap<String, MutableList<Item>>()
    private val more = HashMap<String, Boolean>()
    private val loadedAt = HashMap<String, Long>()
    private var loading = ""
    private var error = ""
    private var admin = false
    private val opened = HashSet<Int>()
    private var speakingId = -1
    private var chipsX = 0

    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })
    private fun headers() = if (store.teamKey.isNotBlank()) mapOf("X-Admin-Key" to store.teamKey) else emptyMap()

    fun build() {
        m.pageHeader("Media · మీడియా", "వార్తలు, సినిమా, ఉద్యోగాలు, భక్తి… రోజూ కొత్త పోస్టులు · New posts every day")
        chips()
        if (cat == "people") { m.community.buildPeople(); m.shareAndAbout(); return }
        note(cat)?.let { m.add(ui.text(it, 12.5f, Ui.MUTED), 10) }
        if (admin) teamTools()
        val list = items[cat]
        if (error.isNotBlank() && list.isNullOrEmpty()) m.add(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 10)
        if (list.isNullOrEmpty()) m.add(ui.text(if (loading == cat || list == null) "Loading posts…" else "No posts here yet. New posts are added every morning.", 14f, Ui.MUTED), 12)
        list?.forEach { m.add(card(it), 12) }
        if (more[cat] == true) m.add(ui.button(if (loading == cat) "Loading…" else "Show more posts", "ghost") { if (loading.isEmpty()) load(cat, false) }, 12)
        m.shareAndAbout()
        val stale = System.currentTimeMillis() - (loadedAt[cat] ?: 0L) > 10 * 60_000L
        if (loading.isEmpty() && (list == null || stale)) load(cat, true)
    }

    /** Fresh posts next time (after the team password changes). */
    fun refresh() { items.clear(); loadedAt.clear(); m.community.load(true) }

    private fun note(c: String) = when (c) {
        "all", "news" -> "🤖 These are short summaries written by AI from news websites. Tap ↗ for the full story."
        "jobs" -> "💼 Always check the details and apply only on the official website."
        "movies" -> "🎬 What critics say, in short, with links to the reviews."
        "crypto", "realestate" -> "ℹ️ ఇది సమాచారం మాత్రమే, ఆర్థిక సలహా కాదు · Information only, not financial advice."
        "health" -> "ℹ️ ఆరోగ్య సమస్యలకు డాక్టర్‌ను సంప్రదించండి · Simple tips, not medical advice."
        "kids" -> "👨‍👩‍👧 Stories and rhymes for parents to read to children."
        else -> null
    }

    private fun chips() {
        val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, m.dp(8), 0) }
        cats.forEachIndexed { i, c ->
            row.addView(ui.chip("${c.icon} ${c.te}", cat == c.key) {
                if (cat != c.key) { cat = c.key; error = ""; m.render() }
            }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
        }
        val hs = HorizontalScrollView(m).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            setOnScrollChangeListener { _, x, _, _, _ -> chipsX = x }
            post { scrollTo(chipsX, 0) }
        }
        m.add(hs, 12)
    }

    // ---------------- a post ----------------
    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 3600 -> "${maxOf(1, s / 60)} min ago"; s < 86400 -> "${s / 3600} h ago"; s < 7 * 86400 -> "${s / 86400} d ago"
            else -> DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        }
    }

    private fun card(it: Item): View {
        val c = catOf(it.cat)
        return LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(Color.WHITE, 20, Ui.LINE); clipToOutline = true
            addView(cover(it, c))
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; setPadding(m.dp(16), m.dp(12), m.dp(16), m.dp(14))
                addView(ui.text("${c.icon} ${c.te} · ${ago(it.at)}" + (if (it.by == "team") " · 🐾 BigD team" else ""), 12.5f, Ui.MUTED, bold = true))
                put(ui.text(it.title, 17.5f, Ui.INK, bold = true), 6)
                val open = it.id in opened
                put(ui.text(it.body, 15f, Ui.INK).apply { if (!open) { maxLines = 4; ellipsize = TextUtils.TruncateAt.END }; setTextIsSelectable(open) }, 6)
                if (it.body.length > 140) put(ui.text(if (open) "తక్కువ చూపించు ▴ Show less" else "ఇంకా చదవండి ▾ Read more", 13.5f, Ui.TEAL_DARK, bold = true).apply {
                    setPadding(0, m.dp(4), 0, m.dp(4)); setOnClickListener { _ -> if (it.id in opened) opened.remove(it.id) else opened.add(it.id); m.render() }
                }, 2)
                when (it.cat) {
                    "crypto", "realestate" -> put(ui.text("ఆర్థిక సలహా కాదు · Not financial advice", 11.5f, Ui.MUTED), 6)
                    "health" -> put(ui.text("వైద్య సలహా కాదు · Not medical advice", 11.5f, Ui.MUTED), 6)
                }
                if (it.url.isNotBlank()) put(ui.line(ui.text("మూలం · Source: ${it.source.ifBlank { "link" }}", 12.5f, Ui.MUTED),
                    ui.button("↗ Open", "ghost") { openLink(it.url) }), 8)
                val tools = mutableListOf<View>(
                    ui.button((if (it.liked) "❤️ " else "🤍 ") + it.likes, if (it.liked) "soft" else "ghost") { like(it) },
                    ui.button(if (speakingId == it.id) "⏹" else "🔊", "ghost") { listen(it) },
                    ui.button("📤", "ghost") { share(it) })
                if (admin) tools.add(ui.button("🗑", "danger") { remove(it) })
                put(ui.row(*tools.toTypedArray()), 10)
            })
        }
    }

    /** A YouTube thumbnail with ▶, or a colourful picture card with the post's emoji. */
    private fun cover(it: Item, c: Cat): View {
        val box = FrameLayout(m)
        if (it.ytId.isNotBlank()) {
            box.layoutParams = LinearLayout.LayoutParams(-1, m.dp(190))
            box.setBackgroundColor(Color.parseColor("#111111"))
            box.addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load("https://i.ytimg.com/vi/${it.ytId}/hqdefault.jpg", this) },
                FrameLayout.LayoutParams(-1, -1))
            box.addView(TextView(m).apply {
                text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = ui.round(Color.parseColor("#E62117"), 14)
            }, FrameLayout.LayoutParams(m.dp(64), m.dp(44), Gravity.CENTER))
            box.addView(TextView(m).apply {
                text = "YouTube" + (if (it.ytCh.isNotBlank()) " · ${it.ytCh}" else ""); textSize = 12f; setTextColor(Color.WHITE)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(m.dp(10), m.dp(4), m.dp(10), m.dp(4)); background = ui.round(Color.parseColor("#99000000"), 10)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(m.dp(10), 0, m.dp(10), m.dp(10)) })
            box.setOnClickListener { _ -> openLink("https://www.youtube.com/watch?v=${it.ytId}") }
        } else {
            box.layoutParams = LinearLayout.LayoutParams(-1, m.dp(118))
            box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c.c1, c.c2))
            box.addView(TextView(m).apply { text = it.emoji.ifBlank { c.icon }; textSize = 50f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            box.addView(TextView(m).apply { text = c.icon + " " + c.te; textSize = 13f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD },
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(m.dp(14), 0, 0, m.dp(10)) })
        }
        return box
    }

    private fun openLink(url: String) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { m.toast("Couldn't open the link.") }
    }

    private fun listen(it: Item) {
        if (speakingId == it.id) { m.stopTalking(); speakingId = -1; m.render(); return }
        speakingId = it.id; m.render()
        m.speakOut(it.title + ". " + it.body) { if (speakingId == it.id) { speakingId = -1; if (m.tab == "feedback") m.render() } }
    }

    private fun share(it: Item) {
        val text = it.title + "\n\n" + it.body + (if (it.url.isNotBlank()) "\n\n" + it.url else "") +
            (if (it.ytId.isNotBlank()) "\n\n▶ https://www.youtube.com/watch?v=${it.ytId}" else "") + "\n\n— BigD AI 🐾 ${store.server}"
        m.leavingForSystemScreen = true
        m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }

    private fun like(it: Item) {
        it.liked = !it.liked; it.likes += if (it.liked) 1 else -1; m.render()
        m.io.execute {
            val r = Net.post(store, "/api/media/like", JSONObject().put("id", it.id), headers(), 15_000)
            m.runOnUiThread { if (r.ok) { it.likes = r.json.optInt("likes", it.likes); it.liked = r.json.optBoolean("liked", it.liked) }; if (m.tab == "feedback") m.render() }
        }
    }

    // ---------------- loading ----------------
    private fun parse(o: JSONObject): Item {
        val yt = o.optJSONObject("yt")
        return Item(o.optInt("id"), o.optString("cat"), o.optLong("at"), o.optString("title"), o.optString("body"), o.optString("emoji"),
            o.optString("source"), o.optString("url"), yt?.optString("id") ?: "", yt?.optString("ch") ?: "", o.optString("by"), o.optInt("likes"), o.optBoolean("liked"))
    }

    private fun load(c: String, refresh: Boolean) {
        if (loading.isNotEmpty()) return
        loading = c; error = ""
        val before = if (refresh) "" else items[c]?.minOfOrNull { it.id }?.let { "&before=$it" } ?: ""
        m.io.execute {
            val r = Net.get(store, "/api/media?cat=$c&limit=20$before", headers())
            m.runOnUiThread {
                loading = ""; loadedAt[c] = System.currentTimeMillis()
                if (r.ok) {
                    val a = r.json.optJSONArray("items")
                    val list = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { parse(it) } }
                    val now = if (refresh) mutableListOf() else items[c] ?: mutableListOf()
                    list.forEach { p -> if (now.none { it.id == p.id }) now.add(p) }
                    items[c] = now; more[c] = r.json.optBoolean("more"); admin = r.json.optBoolean("admin")
                } else { error = r.error("Couldn't load posts."); if (items[c] == null) items[c] = mutableListOf() }
                if (m.tab == "feedback" && cat == c) m.render()
            }
        }
    }

    // ---------------- BigD team tools (team password) ----------------
    private fun teamTools() {
        m.add(ui.card(Ui.SUN_SOFT).apply {
            addView(ui.text("🐾 Team tools", 14.5f, Ui.INK, bold = true))
            put(ui.row(ui.button("✍️ New post", "primary") { newPost() }, ui.button("🔄 Add posts now") { generate() }), 8)
        }, 10)
    }

    private fun generate() {
        val body = JSONObject().put("cat", if (cat == "all" || cat == "people") "" else cat)
        m.io.execute {
            val r = Net.post(store, "/api/media/generate", body, headers(), 20_000)
            m.runOnUiThread { m.toast(if (r.ok) "Writing new posts… they appear in a few minutes. Tap the section again to see them." else r.error("Couldn't start.")) }
        }
    }

    private fun newPost() {
        var pick = if (cat == "all" || cat == "people") "news" else cat
        val box = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(m.dp(20), m.dp(8), m.dp(20), 0) }
        val chipRow = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawChips() {
            chipRow.removeAllViews()
            cats.filter { it.key != "all" && it.key != "people" }.forEachIndexed { i, c ->
                chipRow.addView(ui.chip("${c.icon} ${c.te}", pick == c.key) { pick = c.key; drawChips() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
            }
        }
        drawChips()
        box.addView(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        val title = ui.input("Title", ""); val text = ui.input("Write the post (Telugu or English)", "", multi = true)
        val link = ui.input("Link (optional, https://…)", "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val video = ui.input("YouTube link (optional)", "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        listOf(title, text, link, video).forEach { box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(10) }) }
        AlertDialog.Builder(m).setTitle("New post · కొత్త పోస్ట్").setView(box)
            .setPositiveButton("Post") { _, _ ->
                val body = JSONObject().put("cat", pick).put("title", title.text.toString()).put("body", text.text.toString())
                    .put("url", link.text.toString().trim()).put("youtube", video.text.toString().trim())
                m.io.execute {
                    val r = Net.post(store, "/api/media/post", body, headers(), 20_000)
                    m.runOnUiThread {
                        if (r.ok) { m.toast("Posted."); r.json.optJSONObject("item")?.let { o -> val p = parse(o); items[p.cat]?.add(0, p); items["all"]?.add(0, p) }; m.render() }
                        else m.toast(r.error("Couldn't post."))
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun remove(it: Item) {
        AlertDialog.Builder(m).setMessage("Remove this post for everyone?").setPositiveButton("Remove") { _, _ ->
            m.io.execute {
                val r = Net.post(store, "/api/media/delete", JSONObject().put("id", it.id), headers(), 15_000)
                m.runOnUiThread { if (r.ok) { items.values.forEach { l -> l.removeAll { x -> x.id == it.id } }; m.render() } else m.toast(r.error("Couldn't remove.")) }
            }
        }.setNegativeButton("Cancel", null).show()
    }
}
