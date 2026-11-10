package com.soulfriend.pet

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Loads pictures from the internet (photos, video thumbnails, link pictures), keeping recent ones in memory. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(minOf(40L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Shows the picture at [url] in [into], at most [maxPx] wide or high. */
    fun load(url: String, into: ImageView, maxPx: Int = 1080) {
        val key = "$maxPx|$url"
        into.tag = key
        if (url.isBlank()) { into.setImageDrawable(null); return }
        cache.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        pool.execute {
            val bmp = try { fetch(url, maxPx) } catch (e: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post { if (into.tag == key && bmp != null) into.setImageBitmap(bmp) }
        }
    }

    private fun fetch(url: String, maxPx: Int): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "BigDAI/1.0 (Android)")
        val bytes = try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    total += n; if (total > 8 * 1024 * 1024) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val k = maxPx.toFloat() / maxOf(raw.width, raw.height)
        return if (k < 0.8f) Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * k).toInt()), maxOf(1, (raw.height * k).toInt()), true) else raw
    }

    /** Makes a photo ready to upload: at most [maxPx] (1280) px, the right way up, saved again as JPEG (this also removes hidden details such as the place it was taken). */
    fun prepare(ctx: Context, uri: Uri, maxPx: Int = 1280): ByteArray? {
        try {
            val r = ctx.contentResolver
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
            val raw = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
            var bmp: Bitmap = raw
            val k = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
            if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * k).toInt()), maxOf(1, (bmp.height * k).toInt()), true)
            val turn = try { r.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: 1 } catch (e: Exception) { 1 }
            val deg = when (turn) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
            if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            return out.toByteArray()
        } catch (e: Throwable) { return null }
    }
}

class SUser(val uid: String, val name: String, val handle: String, val bio: String, val avatar: String, val emoji: String, val verified: Boolean,
            val official: Boolean, var followers: Int, val following: Int, val posts: Int, var followed: Boolean, val me: Boolean) {
    companion object {
        fun from(o: JSONObject?) = if (o == null) SUser("", "BigD friend", "", "", "", "🐾", false, false, 0, 0, 0, false, false)
            else SUser(o.optString("uid"), o.optString("name", "BigD friend"), o.optString("handle"), o.optString("bio"), o.optString("avatar"), o.optString("emoji", "🐾"),
                o.optBoolean("verified"), o.optBoolean("official"), o.optInt("followers"), o.optInt("following"), o.optInt("posts"), o.optBoolean("followed"), o.optBoolean("me"))
    }
}

class SPost(val key: String, val author: SUser, val at: Long, val title: String, val text: String, val photos: List<String>, val ytId: String, val ytCh: String,
            val linkUrl: String, val linkTitle: String, val linkSite: String, val linkImage: String, val sourceName: String, val sourceUrl: String,
            val cat: String, val emoji: String, var likes: Int, var liked: Boolean, val views: Int, var comments: Int, val mine: Boolean,
            val videoUrl: String = "", val videoPoster: String = "", val videoDur: Int = 0, val videoW: Int = 0, val videoH: Int = 0,
            val reel: String = "") {
    companion object {
        fun from(o: JSONObject): SPost {
            val ph = o.optJSONArray("photos"); val yt = o.optJSONObject("yt"); val ln = o.optJSONObject("link"); val src = o.optJSONObject("source"); val vd = o.optJSONObject("video")
            return SPost(o.optString("key"), SUser.from(o.optJSONObject("author")), o.optLong("at"), o.optString("title"), o.optString("text"),
                if (ph == null) emptyList() else (0 until ph.length()).map { ph.optString(it) }.filter { it.isNotBlank() },
                yt?.optString("id") ?: "", yt?.optString("ch") ?: "",
                ln?.optString("url") ?: "", ln?.optString("title") ?: "", ln?.optString("site") ?: "", ln?.optString("image") ?: "",
                src?.optString("name") ?: "", src?.optString("url") ?: "", o.optString("cat"), o.optString("emoji"),
                o.optInt("likes"), o.optBoolean("liked"), o.optInt("views"), o.optInt("comments"), o.optBoolean("mine"),
                vd?.optString("url") ?: "", vd?.optString("poster") ?: "", vd?.optInt("dur") ?: 0, vd?.optInt("w") ?: 0, vd?.optInt("h") ?: 0,
                o.optString("reel"))
        }
    }
}

class SComment(val id: Int, val at: Long, val text: String, val author: SUser, val mine: Boolean)

/**
 * Media · మీడియా, like X (Twitter): For you / Following / People / sections; profiles with ✓ for our official accounts;
 * posts with photos, YouTube videos and links from other apps; comments, likes, views, follow, report and hide;
 * and a full-screen viewer (MediaViewer) to swipe up and down through every photo and video.
 */
class SocialPage(private val m: MainActivity) {
    companion object {
        private val BLUE = Color.parseColor("#1D9BF0")
        private val PINK = Color.parseColor("#F91880")
        private val SOFT = Color.parseColor("#E9EEF0")
        private val CAT_COLOR = mapOf("news" to "#3B82F6", "movies" to "#F97316", "jobs" to "#6366F1", "devotional" to "#FB923C", "kids" to "#F472B6",
            "health" to "#14B8A6", "farming" to "#65A30D", "realestate" to "#10B981", "crypto" to "#F59E0B", "facts" to "#8B5CF6", "fun" to "#EC4899")
    }

    private class Tab(val key: String, val label: String)
    private class Step(val screen: String, val profile: SUser?, val post: SPost?, val y: Int)

    private val tabs = listOf(Tab("foryou", "✨ For you"), Tab("popular", "🔥 Popular"), Tab("following", "Following"), Tab("people", "👥 ప్రజలు"),
        Tab("news", "📰 వార్తలు"), Tab("movies", "🎬 సినిమా"), Tab("jobs", "💼 ఉద్యోగాలు"), Tab("devotional", "🛕 భక్తి"), Tab("kids", "🧒 పిల్లలు"),
        Tab("fun", "😄 వినోదం"), Tab("facts", "💡 విజ్ఞానం"), Tab("health", "🩺 ఆరోగ్యం"), Tab("farming", "🌾 వ్యవసాయం"), Tab("realestate", "🏠 రియల్ ఎస్టేట్"), Tab("crypto", "🪙 క్రిప్టో"))
    private val sectionKeys = setOf("news", "movies", "jobs", "devotional", "kids", "health", "farming", "realestate", "crypto", "facts", "fun")
    // filters: by mood and by issue (the server tags every Media post)
    private val moods = listOf("" to "😊 Mood · మూడ్", "happy" to "😊 Happy · సంతోషం", "inspiring" to "✨ Inspiring · స్ఫూర్తి", "devotional" to "🙏 Devotional · భక్తి",
        "useful" to "💡 Useful · ఉపయోగం", "calm" to "🕊️ Calm · ప్రశాంతం", "funny" to "😄 Funny · నవ్వు", "serious" to "⚠️ Serious · ముఖ్యం", "sad" to "😢 Sad · బాధ")
    private val issues = listOf("" to "🏷️ Issue · విషయం", "farming" to "🌾 Farming · వ్యవసాయం", "water" to "💧 Water · నీరు", "prices" to "💰 Prices · ధరలు",
        "jobs" to "💼 Jobs · ఉద్యోగాలు", "education" to "🎓 Education · చదువు", "health" to "🩺 Health · ఆరోగ్యం", "women" to "👩 Women · మహిళలు",
        "children" to "🧒 Children · పిల్లలు", "weather" to "🌦️ Weather · వాతావరణం", "schemes" to "📜 Schemes · పథకాలు", "roads" to "🛣️ Roads · రోడ్లు",
        "power" to "⚡ Power · కరెంట్", "safety" to "🚨 Safety · భద్రత", "festivals" to "🎉 Festivals · పండుగలు", "cinema" to "🎬 Cinema · సినిమా",
        "sports" to "🏏 Sports · క్రీడలు", "technology" to "📱 Technology · టెక్నాలజీ", "money" to "🏦 Money · డబ్బు", "environment" to "🌳 Environment · పర్యావరణం",
        "politics" to "🏛️ Politics · రాజకీయాలు")
    private var mood = ""
    private var issue = ""
    /** The list being shown: the tab, with its filters. */
    private val filterable get() = tab == "foryou" || tab == "popular" || tab in sectionKeys
    private fun feedKey() = if ((mood.isBlank() && issue.isBlank()) || !filterable) tab else "$tab|$mood|$issue"
    // YouTube for the strips at the top: live now, trending, Shorts, new videos (from /api/videos)
    private val vids = HashMap<String, JSONObject>()
    private val vidsAt = HashMap<String, Long>()

    var screen = "feed"
        private set
    private var tab = "foryou"
    private val history = ArrayList<Step>()
    private val feeds = HashMap<String, MutableList<SPost>>()
    private val more = HashMap<String, Boolean>()
    private val loadedAt = HashMap<String, Long>()
    private val busy = HashSet<String>()
    private var error = ""
    private var admin = false
    private var me: SUser? = null
    private var meCode = ""
    // log in with a phone number or email + password (to open the profile on a new phone)
    private var meLogin: JSONObject? = null     // { kind, shown } when this profile has a login
    private var lId = ""
    private var lPw = ""
    private var lShow = false
    private var lBusy = false
    private var lOpen = false                   // the form to add or change the login is open
    private var focusLogin = false
    private var meState = 0            // 0 not asked yet, 1 asking, 2 known
    private var suggest: List<SUser> = emptyList()
    private var suggestAt = 0L
    private var profile: SUser? = null
    private var profileView = "posts"
    private var commentsFor: SPost? = null
    private val comments = mutableListOf<SComment>()
    private var commentsLoading = false
    private var commentText = ""
    private val opened = HashSet<String>()
    private var chipsX = 0
    private var listening = ""
    private var speaking = ""
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    // views: a post counts as seen when it is really on the screen
    private val rows = ArrayList<Pair<String, View>>()
    private var lastRow: View? = null
    private val viewed = HashSet<String>()
    private val pendingViews = LinkedHashSet<String>()
    private val seenRect = Rect()
    private val flushLater = Runnable { flushViews() }
    // writing a post
    private var cText = ""
    private val cPhotos = mutableListOf<Pair<String, String>>()   // upload id, picture address
    private var cLink = ""
    private var cAs = ""
    private var cPreview: JSONObject? = null
    private var previewFor = ""
    private var previewBox: LinearLayout? = null
    private val previewLater = Runnable { fetchPreview() }
    private var posting = false
    private var uploading = 0
    private class VideoPick(val id: String, val poster: String, val dur: Int, val w: Int, val h: Int)
    private var cVideo: VideoPick? = null
    private var vState = ""            // "" | "uploading" | "checking"
    private var vPct = 0
    private var vStatus: TextView? = null
    private var photoFor = "post"
    // editing the profile
    private var eName = ""
    private var eHandle = ""
    private var eBio = ""
    private var eEmoji = "🐾"
    private var eAvatar = ""
    private var eAvatarUrl = ""
    private var eAvatarChanged = false
    private var saving = false
    // the team's list of reported posts and comments
    private var repPosts: List<Pair<SPost, JSONObject>> = emptyList()
    private var repComments: List<JSONObject> = emptyList()
    private var repLoading = false

    private val ui get() = m.ui
    private val store get() = m.store
    private fun dp(v: Int) = m.dp(v)
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun headers() = if (store.teamKey.isNotBlank()) mapOf("X-Admin-Key" to store.teamKey) else emptyMap()
    private fun abs(u: String) = if (u.startsWith("/")) store.server.trimEnd('/') + u else u
    private fun refreshIf(vararg screens: String) { if (m.tab == "feedback" && screen in screens) m.render() }
    private fun samePosts(key: String): List<SPost> = (feeds.values.flatten() + listOfNotNull(commentsFor)).filter { it.key == key }
    private fun postByKey(key: String) = samePosts(key).firstOrNull()
    private fun placeholderMe() = SUser("", "", "", "", "", "👤", false, false, 0, 0, 0, false, true)

    // ============================== screens ==============================
    fun build() {
        rows.clear(); lastRow = null; previewBox = null
        if (meState == 0) loadMe()
        when (screen) {
            "profile" -> buildProfile()
            "edit" -> buildEdit()
            "compose" -> buildCompose()
            "comments" -> buildComments()
            "reports" -> buildReports()
            "cartoon" -> buildCartoon()
            else -> buildFeed()
        }
    }

    /** The round + button (write a post) shows on the feed and on profiles. */
    val showFab get() = screen == "feed" || screen == "profile"

    private fun go(s: String, set: () -> Unit = {}) {
        if (listening.isNotEmpty()) m.stopListening()
        history.add(Step(screen, profile, commentsFor, m.scrollY))
        if (history.size > 30) history.removeAt(0)
        set(); screen = s
        m.render(); m.scrollTop()
    }

    /** Back to the previous Media screen. Returns false when already on the feed. */
    fun back(): Boolean {
        if (screen == "feed" && history.isEmpty()) return false
        if (listening.isNotEmpty()) m.stopListening()
        val st = if (history.isEmpty()) Step("feed", null, null, 0) else history.removeAt(history.lastIndex)
        screen = st.screen; profile = st.profile
        if (screen == "comments" && st.post != null && st.post.key != commentsFor?.key) { commentsFor = st.post; loadComments(st.post) } else commentsFor = st.post
        if (screen == "reports") loadReports()
        m.render(); m.scrollToY(st.y)
        return true
    }

    /** Tapping the Media tab again: back to the top of the feed. */
    fun home() {
        if (screen != "feed" || history.isNotEmpty()) { if (listening.isNotEmpty()) m.stopListening(); history.clear(); screen = "feed"; m.render() }
        m.scrollTop()
    }

    fun refresh() { feeds.clear(); loadedAt.clear(); meState = 0; suggestAt = 0 }

    private fun backBar(title: String) {
        m.add(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(m).apply {
                text = "←"; textSize = 22f; setTextColor(Ui.INK); gravity = Gravity.CENTER
                background = ui.round(Color.WHITE, 22, Ui.LINE); setOnClickListener { back() }; contentDescription = "Back"
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(ui.text(title, 19f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }, 4)
    }

    // ------------------------------ feed ------------------------------
    private fun buildFeed() {
        val mine = me
        m.add(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ui.stack(ui.title("Media · మీడియా"), ui.text("వార్తలు, వీడియోలు, అందరి పోస్టులు · News, videos and everyone's posts", 13f, Ui.MUTED)),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(avatar(mine ?: placeholderMe(), 44) { if (mine != null) openProfile(mine.uid) else startEdit() },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        }, 4)
        chips()
        if (mine == null && meState == 2) m.add(ui.card(Ui.SUN_SOFT).apply {
            addView(ui.text("👤 మీ ప్రొఫైల్ · Make your profile", 15f, Ui.INK, bold = true))
            put(ui.text("Add your name, a photo and a line about you. Then you can post photos and videos, comment and follow people.", 13.5f, Ui.MUTED), 4)
            put(ui.row(ui.button("Make my profile", "primary") { startEdit() }), 10)
        }, 12)
        note(tab)?.let { m.add(ui.text(it, 12.5f, Ui.MUTED), 10) }
        if (tab == "foryou" || tab == "fun" || tab == "kids") m.add(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#FFF1D6"), Color.parseColor("#FFE0EC"))).apply { cornerRadius = dp(18).toFloat() }
            addView(ui.text("🎬", 30f, Ui.INK))
            addView(ui.stack(ui.text("Make a cartoon · కార్టూన్", 15f, Ui.INK, bold = true), ui.text("Your idea → your own cartoon video", 12.5f, Ui.MUTED)).apply { setPadding(dp(10), 0, dp(6), 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button("Make", "primary") { go("cartoon") })
            setOnClickListener { go("cartoon") }
        }, 10)
        val fk = feedKey()
        if (admin) m.add(ui.row(ui.button("🛡 Reports", "ghost") { openReports() }), 10)
        if (admin && tab in sectionKeys) m.add(ui.row(ui.button("🔄 AI posts now") { generate(tab) }, ui.button("✍️ Post here", "primary") { startCompose(asUid = "s_$tab") }), 10)
        val blocked = store.blockedUsers
        val all = feeds[fk]
        val list = all?.filter { it.author.uid !in blocked }
        if (error.isNotBlank() && list.isNullOrEmpty()) m.add(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 12)
        if (list.isNullOrEmpty()) {
            m.add(ui.text(when {
                all == null || fk in busy -> "Loading…"
                fk != tab -> "No posts with this filter right now. Try another mood or issue."
                tab == "popular" -> "Nothing popular yet today. Like and share the posts you enjoy!"
                tab == "following" -> "You don't follow anyone yet. Follow the profiles you like, and their posts appear here."
                tab == "people" -> "No posts yet. Be the first: tap + to write one."
                else -> "Nothing here yet."
            }, 14f, Ui.MUTED), 14)
            if (tab == "foryou" || tab == "following") whoToFollow()
        }
        if (fk == tab && filterable) videoStrips()
        if (!list.isNullOrEmpty()) m.add(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(m).apply {
                text = "▶  Reels · వీడియోలా"; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#FF7A45"), Color.parseColor("#E8336D"))).apply { cornerRadius = dp(24).toFloat() }
                contentDescription = "Play the news like a video: each post is read aloud and the next comes by itself"
                setOnClickListener { openReader(list[0], auto = true) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(ui.button("⬆  Swipe to read", "soft") { openReader(list[0]) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        }, 12)
        list?.forEachIndexed { i, p ->
            m.add(postRow(p), 10)
            if (i == 3 && tab == "foryou") whoToFollow()
        }
        if (!list.isNullOrEmpty() && list.size <= 3 && tab == "foryou") whoToFollow()
        if (more[fk] == true) m.add(ui.button(if (fk in busy) "Loading…" else "Show more", "ghost") { load(fk, false) }, 12)
        else if (!list.isNullOrEmpty()) m.add(ui.text("· అంతే · You're all caught up ·", 12.5f, Ui.MUTED).apply { gravity = Gravity.CENTER }, 16)
        m.add(ui.text("Posts are public and checked for safety. Be kind; no phone numbers or private details. Rules: ${store.server}/rules", 11.5f, Ui.MUTED), 16)
        m.shareAndAbout()
        val stale = System.currentTimeMillis() - (loadedAt[fk] ?: 0L) > 5 * 60_000L
        if (fk !in busy && (all == null || stale)) load(fk, true)
        if (System.currentTimeMillis() - suggestAt > 10 * 60_000L) loadSuggest()
    }

    private fun note(t: String) = when (t) {
        "news" -> "🤖 Posts with ✓ are short summaries written by AI from news websites. Tap the link for the full story."
        "jobs" -> "💼 Always check the details and apply only on the official website."
        "movies" -> "🎬 What critics say, in short, with links to the reviews."
        "crypto", "realestate" -> "ℹ️ ఇది సమాచారం మాత్రమే, ఆర్థిక సలహా కాదు · Information only, not financial advice."
        "health" -> "ℹ️ ఆరోగ్య సమస్యలకు డాక్టర్‌ను సంప్రదించండి · Simple tips, not medical advice."
        "kids" -> "👨‍👩‍👧 Stories and rhymes for parents to read to children."
        "people" -> "👥 Posts from people using BigD AI."
        "popular" -> "🔥 What people liked, commented on and read most in the last 3 days."
        "fun" -> "😄 Clean jokes, riddles, good thoughts and funny cartoons."
        "facts" -> "💡 మీకు తెలుసా? True, interesting facts, written by our own brain from Wikipedia and trusted sources."
        else -> null
    }

    private fun chips() {
        val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, dp(8), 0) }
        tabs.forEachIndexed { i, t ->
            row.addView(ui.chip(t.label, tab == t.key) { if (tab != t.key) { tab = t.key; error = ""; m.render(); m.scrollTop() } },
                LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
        }
        m.add(HorizontalScrollView(m).apply {
            isHorizontalScrollBarEnabled = false; addView(row)
            setOnScrollChangeListener { _, x, _, _, _ -> chipsX = x }
            post { scrollTo(chipsX, 0) }
        }, 12)
        if (!filterable) return
        val f = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
        f.addView(ui.chip((moods.firstOrNull { it.first == mood } ?: moods[0]).second + " ▾", mood.isNotBlank()) { pick(true) })
        f.addView(ui.chip((issues.firstOrNull { it.first == issue } ?: issues[0]).second + " ▾", issue.isNotBlank()) { pick(false) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        if (mood.isNotBlank() || issue.isNotBlank()) f.addView(ui.chip("✕ Clear", false) { mood = ""; issue = ""; error = ""; m.render() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        m.add(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(f) }, 6)
    }

    /** Choose a mood or an issue; the list shows only those posts. */
    private fun pick(isMood: Boolean) {
        val list = if (isMood) moods else issues
        AlertDialog.Builder(m).setTitle(if (isMood) "ఏ మూడ్? · Which mood?" else "ఏ విషయం? · Which issue?")
            .setItems(list.mapIndexed { i, x -> if (i == 0) (if (isMood) "అన్నీ · All moods" else "అన్నీ · All issues") else x.second }.toTypedArray()) { _, i ->
                if (isMood) mood = list[i].first else issue = list[i].first
                error = ""; m.render(); m.scrollTop()
            }.show()
    }

    // ------------------------------ 🎬 cartoons (anyone can ask for one: our own free maker, or an AI cartoon) ------------------------------
    private val ctKinds = listOf("joke" to "😄 హాస్యం · Joke", "riddle" to "🤔 పొడుపు కథ · Riddle", "story" to "📖 కథ · Story", "fact" to "💡 మీకు తెలుసా · Fact", "thought" to "🌟 మంచి మాట · Good thought")
    private var ctKind = "joke"
    private var ctIdea = ""
    private var ctJob: JSONObject? = null
    private var ctBusy = false
    private var ctError = ""
    private var ctInfo: JSONObject? = null
    private var ctInfoAt = 0L
    private val ctPoll = Runnable { pollCartoon() }

    /** How many free AI cartoons are left today (for everyone, and for this person). */
    private fun loadCartoonInfo() {
        if (System.currentTimeMillis() - ctInfoAt < 30_000L) return
        ctInfoAt = System.currentTimeMillis()
        io.execute {
            val r = Net.get(store, "/api/cartoon/info", headers())
            m.runOnUiThread { if (r.ok) { ctInfo = r.json; refreshIf("cartoon") } }
        }
    }

    private fun buildCartoon() {
        backBar("🎬 Make a cartoon · కార్టూన్")
        loadCartoonInfo()
        val info = ctInfo
        val ai = info?.optJSONObject("ai")
        // older servers have no "ai" part: then only the number left says it
        val aiOn = if (ai != null) ai.optBoolean("on") else (info?.optInt("aiLeftForYou") ?: 0) > 0
        val reason = ai?.optString("reason") ?: ""
        val owner = info?.optBoolean("admin") ?: false
        val banner: String? = when {
            info == null -> null
            aiOn && owner -> "👑 Owner: no daily limit for you. Your idea becomes an AI cartoon in 2 to 5 minutes. (People's free AI cartoons left today: ${info.optInt("aiLeftToday")})"
            aiOn -> "⭐ Free for now! Your idea becomes an AI cartoon, like the trending ones. AI cartoons left today: ${info.optInt("aiLeftToday")}."
            owner && reason == "budget" -> "👑 Owner: this month's money limit for AI videos is reached. Raise it on the Teach page (🤖 AI videos) to make more."
            owner && reason == "off" -> "👑 Owner: AI videos are switched off on the server (AI_VIDEOS=false)."
            owner && (reason == "no_key" || reason.isBlank()) -> "👑 Owner: AI cartoons can't start: the Gemini key isn't on the server yet (it needs billing on). The Teach page › 🤖 AI videos shows it."
            else -> "🎬 " + (ai?.optString("why")?.takeIf { it.isNotBlank() } ?: "AI cartoons from your idea are coming soon!")
        }
        if (banner != null) m.add(ui.text(banner, 14.5f, if (aiOn) Ui.TEAL else Ui.INK, bold = true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = ui.round(if (aiOn) Color.parseColor("#E6F7F4") else Color.parseColor("#FFF6E5"), 14) }, 8)
        m.add(ui.text(if (aiOn) "Tell your idea: the people, the place, what happens and the funny ending. You can write in Telugu or English." else "Tell your idea, or leave it empty.", 14f, Ui.MUTED), 8)
        val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, dp(8), 0) }
        ctKinds.forEachIndexed { i, (k, label) ->
            row.addView(ui.chip(label, ctKind == k) { ctKind = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
        }
        m.add(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 12)
        val box = EditText(m).apply {
            hint = "ఉదా: ఆరు టీ గ్లాసులతో జారిపడిన చాయ్ · e.g. Chai slips with six glasses of tea"; setText(ctIdea); textSize = 15f; minLines = 2; maxLines = 8
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
            background = ui.round(Color.WHITE, 14, Ui.LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { ctIdea = e?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        m.add(box, 10)
        m.add(ui.text("Kind, happy ideas only. If you leave it empty, BigD picks a funny one itself.", 12f, Ui.MUTED), 4)
        val job = ctJob
        val st = job?.optString("status") ?: ""
        val working = ctBusy || st == "waiting" || st == "making"
        when {
            working -> m.add(ui.button("⏳ Making your cartoon…", "primary") { }, 12)
            info == null -> m.add(ui.button("🎬 Make my cartoon", "primary") { makeCartoon("") }, 12)   // couldn't ask the server: it decides
            aiOn -> {
                m.add(ui.button("🎬 Make my AI cartoon", "primary") { makeCartoon("ai") }, 12)
                m.add(ui.button("🎨 Or make it free with our own cartoon maker", "ghost") { makeCartoon("free") }, 6)
            }
            else -> {
                // no AI cartoon now: our own free cartoon maker
                m.add(ui.text("🎨 Our own cartoon maker (free): BigD's cute characters act out your idea: Chai, Idli, Mango, the BigD puppy, Ammamma, Tatayya, the teacher, a cat and a parrot, in a tea shop, market, village, school, on stage and more.${if (owner) "" else " ${info.optInt("leftForYou")} left today."}", 13f, Ui.MUTED), 12)
                m.add(ui.button("🎬 Make my cartoon (free)", "primary") { makeCartoon("free") }, 6)
            }
        }
        if (ctError.isNotBlank()) m.add(ui.text("⚠️ $ctError", 14f, Ui.DANGER), 10)
        if (job != null) when (st) {
            "waiting" -> m.add(ui.text("⏳ Waiting… ${job.optInt("position")} before yours.", 14f, Ui.INK), 12)
            "making" -> m.add(ui.text(if (job.optBoolean("ai")) "⭐ Making your AI cartoon… (2 to 5 minutes). You can use other pages meanwhile; come back here to see it." else "🎨 Our cartoon maker is drawing your cartoon… (1 to 3 minutes)", 14f, Ui.INK), 12)
            "failed" -> m.add(ui.text("⚠️ " + job.optString("error").ifBlank { "Couldn't make it. Please try again." }, 14f, Ui.DANGER), 12)
            "done" -> {
                val v = job.optJSONObject("video")
                m.add(ui.text((if (job.optBoolean("ai")) "⭐ AI cartoon · " else "🎬 ") + job.optString("title"), 17f, Ui.INK, bold = true), 14)
                job.optString("note").takeIf { it.isNotBlank() }?.let { m.add(ui.text(it, 13f, Ui.MUTED), 4) }
                if (v != null) m.add(FrameLayout(m).apply {
                    background = ui.round(Color.BLACK, 16); clipToOutline = true; contentDescription = "Play your cartoon"
                    addView(ImageView(m).apply { scaleType = ImageView.ScaleType.FIT_CENTER; Images.load(abs(v.optString("poster")), this, 720) }, FrameLayout.LayoutParams(-1, dp(360)))
                    addView(TextView(m).apply { text = "▶"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) } }, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
                    setOnClickListener {
                        MediaViewer.items = listOf(MediaViewer.Item("file", abs(v.optString("url")), "", if (job.optBoolean("ai")) "🤖 AI cartoon · AI-made" else "🎬 BigD cartoon", job.optString("title"), 0, false, abs(v.optString("poster"))))
                        MediaViewer.start = 0; MediaViewer.server = store.server; MediaViewer.onLike = null; MediaViewer.onComments = null
                        m.leavingForSystemScreen = true; m.startActivity(Intent(m, MediaViewer::class.java))
                    }
                }, 10)
                m.add(ui.row(ui.button("📤 Share", "primary") { shareCartoon(job) },
                    ui.button(if (job.optBoolean("posted")) "✓ Posted" else "📝 Post on my profile", "ghost") { if (!job.optBoolean("posted")) postCartoon(job) }), 10)
            }
        }
        m.shareAndAbout()
    }

    /** maker: "ai" (an AI cartoon of the idea), "free" (our own cartoon maker), or "" (the server decides). */
    private fun makeCartoon(maker: String) {
        ctBusy = true; ctError = ""; m.render()
        io.execute {
            val body = JSONObject().put("type", ctKind).put("prompt", ctIdea.trim())
            if (maker.isNotBlank()) body.put("maker", maker)
            val r = Net.post(store, "/api/cartoon/make", body, headers(), 20_000)
            m.runOnUiThread {
                ctBusy = false
                if (r.ok) { ctJob = r.json; main.removeCallbacks(ctPoll); main.postDelayed(ctPoll, 4000) } else ctError = r.error("Couldn't start it. Please try again.")
                ctInfoAt = 0L
                refreshIf("cartoon")
            }
        }
    }

    private fun pollCartoon() {
        val id = ctJob?.optString("id") ?: return
        io.execute {
            val r = Net.get(store, "/api/cartoon/status?id=" + Uri.encode(id), headers())
            m.runOnUiThread {
                if (r.ok) ctJob = r.json
                val st = ctJob?.optString("status") ?: ""
                if (st == "waiting" || st == "making" || !r.ok) main.postDelayed(ctPoll, 5000)
                refreshIf("cartoon")
            }
        }
    }

    private fun postCartoon(job: JSONObject) {
        io.execute {
            val r = Net.post(store, "/api/cartoon/post", JSONObject().put("id", job.optString("id")), headers(), 20_000)
            m.runOnUiThread {
                if (r.ok) { ctJob = r.json; feeds.remove("foryou"); feeds.remove("people"); Toast.makeText(m, "Posted on your profile 🎉", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(m, r.error("Couldn't post it."), Toast.LENGTH_LONG).show()
                refreshIf("cartoon")
            }
        }
    }

    /** Downloads the cartoon, then shares the video file (WhatsApp, status, anywhere). */
    private fun shareCartoon(job: JSONObject) {
        val url = abs(job.optJSONObject("video")?.optString("url") ?: return)
        val name = "BigD-cartoon-" + job.optString("id").filter { it.isLetterOrDigit() } + ".mp4"
        Toast.makeText(m, "🎬 Getting the video ready…", Toast.LENGTH_SHORT).show()
        Thread {
            val f = ShareFiles.download(m, url, name)
            m.runOnUiThread {
                if (f == null) { Toast.makeText(m, "Couldn't get the video. Please try again.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val uri = ShareFiles.uri(m, name)
                val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TEXT, (if (job.optBoolean("ai")) "🤖 " else "🎬 ") + job.optString("title") + (if (job.optBoolean("ai")) " (AI-made)" else "") + "\n📲 BigD AI · bigdai.tech").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = android.content.ClipData.newRawUri("video", uri)
                try { m.leavingForSystemScreen = true; m.startActivity(Intent.createChooser(send, "Share the cartoon").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { }
            }
        }.start()
    }

    // ------------------------------ YouTube strips ------------------------------
    private fun videoStrips() {
        val cat = if (tab in sectionKeys) tab else ""
        val k = cat.ifBlank { "all" }
        if (System.currentTimeMillis() - (vidsAt[k] ?: 0L) > 10 * 60_000L) {
            vidsAt[k] = System.currentTimeMillis()
            io.execute {
                val r = Net.get(store, "/api/videos" + (if (cat.isNotBlank()) "?cat=" + Uri.encode(cat) else ""), headers())
                m.runOnUiThread { if (r.ok) { vids[k] = r.json; refreshIf("feed") } else vidsAt[k] = System.currentTimeMillis() - 8 * 60_000L }
            }
        }
        val v = vids[k] ?: return
        fun arr(n: String) = v.optJSONArray(n) ?: JSONArray()
        if (tab != "popular") strip("🔴 ఇప్పుడు లైవ్ · Live now", arr("live"), live = true)
        if (tab == "foryou" || tab == "popular") strip("🔥 ట్రెండింగ్ · Trending today", arr("trending"))
        if (tab != "popular") strip("📱 Shorts", arr("shorts"), tall = true)
        if (cat.isNotBlank()) strip("▶️ కొత్త వీడియోలు · New videos", arr("latest"))
        if (cat.isNotBlank()) strip("⭐ ఎక్కువ మంది చూసినవి · Most watched", arr("popular"))
    }

    private fun strip(title: String, a: JSONArray, live: Boolean = false, tall: Boolean = false) {
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").length == 11 }.map { o ->
            val ch = o.optString("ch")
            SPost("yt:" + o.optString("id"), SUser("", ch.ifBlank { "YouTube" }, "", "", "", if (live) "🔴" else "▶️", false, false, 0, 0, 0, false, false),
                o.optLong("at"), o.optString("title"), "", emptyList(), o.optString("id"), ch, "", "", "", "", "YouTube · $ch", "", o.optString("cat"), "▶️", 0, false, o.optInt("views"), 0, false)
        }
        if (all.isEmpty()) return
        val posts = all.take(20)
        m.add(ui.text(title + (if (all.size > posts.size) "  ·  ${all.size}" else ""), 15f, Ui.INK, bold = true), 14)
        val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, dp(8), 0) }
        posts.forEachIndexed { i, p ->
            val card = LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; contentDescription = "Play " + p.title
                addView(FrameLayout(m).apply {
                    background = ui.round(Color.BLACK, 14); clipToOutline = true
                    addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP
                        Images.load("https://i.ytimg.com/vi/${p.ytId}/${if (tall) "hqdefault" else if (live) "mqdefault_live" else "mqdefault"}.jpg", this, 480) }, FrameLayout.LayoutParams(-1, -1))
                    if (live) addView(TextView(m).apply { text = "● LIVE"; textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(6), dp(2), dp(6), dp(2)); background = ui.round(Color.parseColor("#E62117"), 6) },
                        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
                    else addView(TextView(m).apply { text = "▶"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#B3E62117"), 10) },
                        FrameLayout.LayoutParams(dp(40), dp(28), Gravity.CENTER))
                }, LinearLayout.LayoutParams(-1, dp(if (tall) 200 else 112)))
                addView(ui.text(p.title, 13f, Ui.INK, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
                addView(ui.text(p.author.name + (if (p.views > 0 && !live) " · ${num(p.views)} views" else ""), 11.5f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                setOnClickListener { openVideos(all, i, tall) }
            }
            row.addView(card, LinearLayout.LayoutParams(dp(if (tall) 120 else 200), -2).apply { if (i > 0) marginStart = dp(10) })
        }
        m.add(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 8)
    }

    /** YouTube videos full screen (YouTube's own player); swipe for the next one in the strip. */
    private fun openVideos(list: List<SPost>, start: Int, shorts: Boolean = false) {
        MediaViewer.items = list.map { x -> MediaViewer.Item(if (shorts) "short" else "video", x.ytId, "", x.author.name, x.title, 0, false) }
        MediaViewer.start = start; MediaViewer.server = store.server; MediaViewer.onLike = null; MediaViewer.onComments = null
        m.leavingForSystemScreen = true
        m.startActivity(Intent(m, MediaViewer::class.java))
    }

    // ------------------------------ one post ------------------------------
    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; s < 7 * 86400 -> "${s / 86400}d"
            else -> DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        }
    }

    private fun num(n: Int) = when {
        n < 1000 -> "$n"
        n < 1_000_000 -> String.format(Locale.ENGLISH, "%.1fK", n / 1000f).replace(".0K", "K")
        else -> String.format(Locale.ENGLISH, "%.1fM", n / 1_000_000f).replace(".0M", "M")
    }

    /** A round profile picture: the photo, or the person's emoji on a soft circle. */
    private fun avatar(u: SUser, size: Int, onClick: (() -> Unit)? = null): View {
        val box = FrameLayout(m).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (u.official) Color.parseColor("#E6F0FF") else Ui.TEAL_SOFT) }
            clipToOutline = true
            if (onClick != null) setOnClickListener { onClick() }
        }
        if (u.avatar.isNotBlank()) box.addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load(abs(u.avatar), this, 320) }, FrameLayout.LayoutParams(-1, -1))
        else box.addView(TextView(m).apply { text = u.emoji.ifBlank { "🐾" }; textSize = size * 0.42f; gravity = Gravity.CENTER; includeFontPadding = false }, FrameLayout.LayoutParams(-1, -1))
        return box
    }

    private fun badge() = TextView(m).apply {
        text = "✓"; textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BLUE) }
    }

    private fun nameLine(u: SUser, at: Long, onMore: (() -> Unit)?) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(u.name, 15f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (u.verified) addView(badge(), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) })
        addView(ui.text((if (u.handle.isNotBlank()) " @${u.handle}" else "") + " · ${ago(at)}", 13.5f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (onMore != null) addView(ui.text("⋯", 20f, Ui.MUTED).apply { setPadding(dp(12), 0, dp(4), 0); setOnClickListener { onMore() }; contentDescription = "More" })
    }

    private fun postRow(p: SPost, full: Boolean = false): View {
        val row = LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(4))
            background = ui.round(Color.WHITE, 18, Ui.LINE)
        }
        row.addView(avatar(p.author, 44) { openProfile(p.author.uid) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val col = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        col.addView(nameLine(p.author, p.at) { menu(p) }.apply { setOnClickListener { openProfile(p.author.uid) } })
        if (p.title.isNotBlank()) col.put(ui.text(p.title, 16f, Ui.INK, bold = true), 4)
        if (p.text.isNotBlank()) {
            val open = full || p.key in opened
            col.put(ui.text(p.text, 15f, Ui.INK).apply { if (open) setTextIsSelectable(true) else { maxLines = 6; ellipsize = TextUtils.TruncateAt.END } }, 4)
            if (!open && (p.text.length > 260 || p.text.count { it == '\n' } > 5)) col.put(ui.text("ఇంకా చదవండి · Show more", 13.5f, BLUE, bold = true).apply {
                setOnClickListener { opened.add(p.key); m.render() } }, 2)
        }
        val linkCardShown = mediaBlock(p)?.let { col.put(it, 8); p.ytId.isBlank() && p.linkUrl.isNotBlank() } ?: false
        if (p.sourceUrl.isNotBlank() && !(linkCardShown && p.linkUrl == p.sourceUrl))
            col.put(ui.text("మూలం · Source: ${p.sourceName.ifBlank { "link" }} ↗", 12.5f, BLUE).apply { setOnClickListener { open(p.sourceUrl) } }, 6)
        if (p.author.official) when (p.cat) {
            "crypto", "realestate" -> col.put(ui.text("ఆర్థిక సలహా కాదు · Not financial advice", 11.5f, Ui.MUTED), 4)
            "health" -> col.put(ui.text("వైద్య సలహా కాదు · Not medical advice", 11.5f, Ui.MUTED), 4)
        }
        col.put(actions(p), 2)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (!full) row.setOnClickListener { openReader(p) }
        rows.add(p.key to row); lastRow = row
        return row
    }

    private fun act(label: String, color: Int = Ui.MUTED, onClick: (() -> Unit)?) = TextView(m).apply {
        text = label; textSize = 13.5f; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(9), dp(6), dp(9))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun counted(icon: String, n: Int) = if (n > 0) "$icon ${num(n)}" else icon
    private fun likeLabel(p: SPost) = counted(if (p.liked) "❤️" else "🤍", p.likes)

    private fun actions(p: SPost) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val likeV = act(likeLabel(p), if (p.liked) PINK else Ui.MUTED, null)
        likeV.setOnClickListener { like(p) { likeV.text = likeLabel(p); likeV.setTextColor(if (p.liked) PINK else Ui.MUTED) } }
        listOf(
            act(counted("💬", p.comments)) { openComments(p) },
            likeV,
            act(counted("👁", p.views), Ui.MUTED, null),
            act(if (speaking == p.key) "⏹" else "🔊") { listen(p) },
            act("📤") { share(p) }
        ).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    /** Photos (1 to 4), a YouTube video or a link card, one under the other. */
    private fun mediaBlock(p: SPost): View? {
        val parts = ArrayList<View>()
        if (p.videoUrl.isNotBlank()) parts.add(ownVideoCard(p))
        else if (p.photos.isNotEmpty()) parts.add(photoGrid(p))
        if (p.ytId.isNotBlank()) {
            parts.add(videoCard(p))
            if (p.linkTitle.isNotBlank()) parts.add(ui.text("▶ " + p.linkTitle, 13.5f, Ui.INK, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        } else if (p.linkUrl.isNotBlank()) parts.add(linkCard(p.linkSite, p.linkTitle.ifBlank { if (p.author.official) "పూర్తి వార్త చదవండి · Read the full story" else "" }, p.linkImage, p.linkUrl) {
            if (screen == "comments") open(p.linkUrl) else openReader(p)
        })
        if (parts.isEmpty() && p.author.official) parts.add(sectionArt(p))
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        return LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL
            parts.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(6) }) }
        }
    }

    private fun photoGrid(p: SPost): View {
        fun img(i: Int) = ImageView(m).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(SOFT); contentDescription = "Photo ${i + 1}"
            Images.load(abs(p.photos[i]), this, if (p.photos.size == 1) 1080 else 640)
            setOnClickListener { if (screen == "comments") openViewer(p, i) else openReader(p) }
        }
        val gap = dp(3)
        val box = FrameLayout(m).apply { background = ui.round(SOFT, 16); clipToOutline = true }
        when (p.photos.size) {
            1 -> box.addView(img(0), FrameLayout.LayoutParams(-1, dp(250)))
            2 -> box.addView(LinearLayout(m).apply {
                addView(img(0), LinearLayout.LayoutParams(0, -1, 1f))
                addView(img(1), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = gap })
            }, FrameLayout.LayoutParams(-1, dp(190)))
            3 -> box.addView(LinearLayout(m).apply {
                addView(img(0), LinearLayout.LayoutParams(0, -1, 1f))
                addView(LinearLayout(m).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(img(1), LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(img(2), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = gap })
            }, FrameLayout.LayoutParams(-1, dp(230)))
            else -> box.addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL
                for (r in 0..1) addView(LinearLayout(m).apply {
                    addView(img(r * 2), LinearLayout.LayoutParams(0, -1, 1f))
                    addView(img(r * 2 + 1), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = gap })
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (r == 1) topMargin = gap })
            }, FrameLayout.LayoutParams(-1, dp(250)))
        }
        return box
    }

    /** A video someone posted from their phone: its poster with ▶ and its length; tap to play full screen. */
    private fun ownVideoCard(p: SPost): View = FrameLayout(m).apply {
        background = ui.round(Color.BLACK, 16); clipToOutline = true; contentDescription = "Play video"
        val tall = p.videoH > p.videoW
        addView(ImageView(m).apply { scaleType = if (tall) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP; Images.load(abs(p.videoPoster), this, 720) },
            FrameLayout.LayoutParams(-1, dp(if (tall) 340 else 210)))
        addView(TextView(m).apply {
            text = "▶"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) }
        }, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        addView(TextView(m).apply {
            text = clock(p.videoDur); textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(8), dp(3), dp(8), dp(3)); background = ui.round(Color.parseColor("#99000000"), 8)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
        setOnClickListener { if (screen == "comments") openViewer(p, -2) else openReader(p) }
    }

    private fun ytChannel(ch: String) = ch.removePrefix("YouTube · ").removePrefix("YouTube").trim()

    private fun videoCard(p: SPost): View = FrameLayout(m).apply {
        background = ui.round(Color.BLACK, 16); clipToOutline = true; contentDescription = "Play video"
        addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load("https://i.ytimg.com/vi/${p.ytId}/hqdefault.jpg", this, 640) },
            FrameLayout.LayoutParams(-1, dp(200)))
        addView(TextView(m).apply { text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#E62117"), 14) },
            FrameLayout.LayoutParams(dp(64), dp(44), Gravity.CENTER))
        val ch = ytChannel(p.ytCh)
        addView(TextView(m).apply {
            text = "YouTube" + (if (ch.isNotBlank()) " · $ch" else ""); textSize = 12f; setTextColor(Color.WHITE)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), dp(3), dp(8), dp(3)); background = ui.round(Color.parseColor("#99000000"), 8)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        setOnClickListener { if (screen == "comments") openViewer(p, -1) else openReader(p) }
    }

    private fun hostOf(u: String) = try { Uri.parse(u).host?.removePrefix("www.")?.removePrefix("m.") ?: "" } catch (e: Exception) { "" }

    private fun linkCard(site: String, title: String, image: String, url: String, onClick: (() -> Unit)? = null): View = LinearLayout(m).apply {
        orientation = LinearLayout.VERTICAL; background = ui.round(Color.WHITE, 16, Ui.LINE); clipToOutline = true
        if (image.isNotBlank()) addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(SOFT); Images.load(image, this, 900) },
            LinearLayout.LayoutParams(-1, dp(170)))
        addView(ui.stack(
            ui.text("🔗 " + site.ifBlank { hostOf(url) }, 12f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            ui.text(title.ifBlank { url }, 14f, Ui.INK, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        ).apply { setPadding(dp(12), dp(8), dp(12), dp(10)) })
        setOnClickListener { if (onClick != null) onClick() else open(url) }
    }

    private fun sectionArt(p: SPost): View = FrameLayout(m).apply {
        val c = Color.parseColor(CAT_COLOR[p.cat] ?: "#19A39A")
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.TEAL_DARK, c)).apply { cornerRadius = dp(16).toFloat() }
        addView(TextView(m).apply { text = p.emoji.ifBlank { p.author.emoji }; textSize = 42f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, dp(100)))
    }

    // ------------------------------ who to follow ------------------------------
    private fun followButton(u: SUser) = ui.button(if (u.followed) "Following ✓" else "Follow", if (u.followed) "ghost" else "primary") { follow(u) }

    private fun whoToFollow() {
        val list = suggest.filter { !it.followed && it.uid !in store.blockedUsers }
        if (list.isEmpty()) return
        val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
        list.forEachIndexed { i, u ->
            row.addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(10), dp(14), dp(10), dp(12))
                background = ui.round(Color.WHITE, 18, Ui.LINE)
                setOnClickListener { openProfile(u.uid) }
                addView(avatar(u, 64) { openProfile(u.uid) }, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                    addView(ui.text(u.name, 14f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    if (u.verified) addView(badge(), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(3) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                if (u.handle.isNotBlank()) addView(ui.text("@${u.handle}", 12.5f, Ui.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(ui.text(u.bio, 12f, Ui.MUTED).apply { gravity = Gravity.CENTER; minLines = 2; maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                addView(followButton(u), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(dp(164), -2).apply { if (i > 0) marginStart = dp(8) })
        }
        m.add(ui.text("Who to follow · ఎవరిని ఫాలో అవ్వాలి", 16f, Ui.INK, bold = true), 18)
        m.add(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 8)
    }

    // ------------------------------ profile ------------------------------
    fun openProfile(uid: String) {
        if (uid.isBlank()) return
        if (screen == "profile" && profile?.uid == uid) { m.scrollTop(); return }
        val guess = feeds.values.flatten().firstOrNull { it.author.uid == uid }?.author ?: suggest.firstOrNull { it.uid == uid }
            ?: me?.takeIf { it.uid == uid } ?: SUser(uid, "…", "", "", "", "🐾", false, false, 0, 0, 0, false, false)
        loadedAt.remove("user:$uid")
        go("profile") { profile = guess; profileView = "posts" }
        io.execute {
            val r = Net.get(store, "/api/social/profile?uid=${Uri.encode(uid)}", headers())
            m.runOnUiThread {
                if (r.ok && profile?.uid == uid) { profile = SUser.from(r.json.optJSONObject("user")); refreshIf("profile") }
                else if (!r.ok && profile?.uid == uid && r.code == 404) { m.toast(r.error("This profile is not there.")); if (screen == "profile") back() }
            }
        }
    }

    private fun buildProfile() {
        val u = profile ?: run { screen = "feed"; buildFeed(); return }
        backBar(u.name)
        val head = FrameLayout(m).apply { background = ui.round(Color.WHITE, 20, Ui.LINE); clipToOutline = true }
        val tint = if (u.official) Color.parseColor(CAT_COLOR[u.uid.removePrefix("s_")] ?: "#19A39A") else Ui.TEAL
        head.addView(View(m).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.TEAL_DARK, tint)) }, FrameLayout.LayoutParams(-1, dp(96)))
        head.addView(FrameLayout(m).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(avatar(u, 80) { if (u.avatar.isNotBlank()) openPhoto(abs(u.avatar), u) }, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(52), 0, 0) })
        val btn = when { u.me -> ui.button("✏️ Edit profile", "ghost") { startEdit() }; u.uid.isNotBlank() && u.name != "…" -> followButton(u); else -> null }
        if (btn != null) head.addView(btn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(104), dp(14), 0) })
        head.addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16))
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(u.name, 21f, Ui.INK, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                if (u.verified) addView(badge(), LinearLayout.LayoutParams(dp(19), dp(19)).apply { marginStart = dp(6) })
            })
            if (u.handle.isNotBlank()) addView(ui.text("@${u.handle}", 14f, Ui.MUTED))
            if (u.bio.isNotBlank()) addView(ui.text(u.bio, 15f, Ui.INK).apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (u.official) addView(ui.text("BigD official account · posts written by AI from real websites", 12.5f, Ui.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(ui.text("${num(u.following)} Following  ·  ${num(u.followers)} Followers  ·  ${num(u.posts)} Posts", 13.5f, Ui.MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = dp(148) })
        m.add(head, 8)
        if (u.me) m.add(ui.row(ui.button("✍️ New post", "primary") { startCompose() }, ui.button("🔑 Backup code") { showCode(false) }), 10)
        if (u.uid in store.blockedUsers) m.add(ui.card(Ui.SUN_SOFT).apply {
            addView(ui.text("You hid posts from ${u.name}.", 14.5f, Ui.INK, bold = true))
            put(ui.row(ui.button("Show their posts again") { store.blockedUsers = store.blockedUsers - u.uid; m.render() }), 8)
        }, 10)
        m.add(ui.row(ui.chip("Posts", profileView == "posts") { profileView = "posts"; m.render() },
            ui.chip("📷 Photos & videos", profileView == "media") { profileView = "media"; m.render() }), 16)
        val key = "user:${u.uid}"
        val all = feeds[key]
        val list = if (u.uid in store.blockedUsers) emptyList() else all
        if (profileView == "media") mediaGrid(list ?: emptyList(), all == null || key in busy)
        else {
            if (list.isNullOrEmpty()) m.add(ui.text(if (all == null || key in busy) "Loading…" else "No posts yet.", 14f, Ui.MUTED), 10)
            list?.forEach { m.add(postRow(it), 10) }
        }
        if (more[key] == true) m.add(ui.button(if (key in busy) "Loading…" else "Show more", "ghost") { load(key, false) }, 12)
        if (key !in busy && (all == null || System.currentTimeMillis() - (loadedAt[key] ?: 0L) > 5 * 60_000L)) load(key, true)
    }

    /** All photos and videos of a profile, three in a row; tap one to open the full-screen viewer. */
    private fun mediaGrid(list: List<SPost>, loading: Boolean) {
        val tiles = ArrayList<Triple<SPost, Int, String>>()   // post, photo number (-1 = video), picture
        for (p in list) {
            if (p.videoUrl.isNotBlank()) tiles.add(Triple(p, -2, abs(p.videoPoster)))
            p.photos.forEachIndexed { i, u -> tiles.add(Triple(p, i, abs(u))) }
            if (p.ytId.isNotBlank()) tiles.add(Triple(p, -1, "https://i.ytimg.com/vi/${p.ytId}/hqdefault.jpg"))
        }
        if (tiles.isEmpty()) { m.add(ui.text(if (loading) "Loading…" else "No photos or videos yet.", 14f, Ui.MUTED), 10); return }
        val side = (m.resources.displayMetrics.widthPixels - dp(32) - dp(8)) / 3
        tiles.chunked(3).forEachIndexed { r, chunk ->
            m.add(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                chunk.forEachIndexed { i, (p, which, pic) ->
                    addView(FrameLayout(m).apply {
                        background = ui.round(SOFT, 10); clipToOutline = true
                        addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load(pic, this, 400) }, FrameLayout.LayoutParams(-1, -1))
                        if (which < 0) addView(TextView(m).apply { text = "▶"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#99000000"), 14) },
                            FrameLayout.LayoutParams(dp(34), dp(28), Gravity.CENTER))
                        setOnClickListener { openViewer(p, which) }
                    }, LinearLayout.LayoutParams(side, side).apply { if (i > 0) marginStart = dp(4) })
                }
            }, if (r == 0) 10 else 4)
        }
    }

    private fun showCode(first: Boolean) {
        if (meCode.isBlank()) { m.toast("Make your profile first."); return }
        AlertDialog.Builder(m).setTitle("🔑 Your backup code")
            .setMessage((if (first) "Your profile is ready! " else "") + "Your backup code is:\n\n$meCode\n\nKeep it private, like a password. If you get a new phone, open Media › Make your profile › Move from another phone, and type this code to get your profile back.")
            .setPositiveButton("Copy") { _, _ ->
                try { m.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BigD backup code", meCode)); m.toast("Copied") } catch (e: Exception) { }
            }
            .setNegativeButton("Close", null).show()
    }

    // ------------------------------ edit profile ------------------------------
    private fun startEdit() {
        val u = me
        go("edit") {
            eName = u?.name ?: store.feedName; eHandle = u?.handle ?: ""; eBio = u?.bio ?: ""; eEmoji = u?.emoji ?: "🐾"
            eAvatar = ""; eAvatarUrl = u?.avatar ?: ""; eAvatarChanged = false
        }
    }

    private fun buildEdit() {
        backBar(if (me == null) "Make your profile" else "Edit profile")
        m.add(ui.section("మీ ప్రొఫైల్ · Your profile", "Your photo, name and tagline can be seen by everyone.").apply {
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(avatar(SUser("", eName, "", "", eAvatarUrl, eEmoji, false, false, 0, 0, 0, false, true), 84), LinearLayout.LayoutParams(dp(84), dp(84)))
                addView(LinearLayout(m).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.button(if (uploading > 0 && photoFor == "avatar") "Checking photo…" else "📷 Choose photo", "primary") {
                        if (uploading == 0) { photoFor = "avatar"; m.pickPhotos(1) }
                    })
                    if (eAvatarUrl.isNotBlank()) addView(ui.button("Use an emoji instead", "ghost") { eAvatar = ""; eAvatarUrl = ""; eAvatarChanged = true; m.render() },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            }, 12)
            if (eAvatarUrl.isBlank()) put(HorizontalScrollView(m).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(m).apply {
                    listOf("🐾", "🦎", "🐶", "🐱", "🦜", "🌸", "🌾", "⭐", "🙏", "😎", "🎉", "📚", "🏏", "🎵").forEach { e ->
                        addView(TextView(m).apply {
                            text = e; textSize = 24f; gravity = Gravity.CENTER
                            background = if (e == eEmoji) ui.round(Ui.TEAL_SOFT, 12, Ui.TEAL) else null
                            setOnClickListener { eEmoji = e; m.render() }
                        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(4) })
                    }
                })
            }, 10)
            put(ui.text("Name · పేరు", 14f, Ui.INK, bold = true), 14)
            put(ui.input("Your name", eName).apply { onText { eName = it } }, 6)
            put(ui.text("@name (optional: small English letters, numbers, _)", 14f, Ui.INK, bold = true), 12)
            put(ui.input("e.g. kishore_vizag", eHandle).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; onText { eHandle = it } }, 6)
            put(ui.text("Tagline / status · మీ గురించి ఒక మాట", 14f, Ui.INK, bold = true), 12)
            put(ui.input("e.g. \"Farmer from Pendurthi 🌾\"", eBio, multi = true).apply { onText { eBio = it } }, 6)
            put(ui.button(if (saving) "Saving…" else "Save profile", "primary") { if (!saving) saveProfile() }, 16)
            put(ui.text(if (me == null) "After saving, you can add your phone number or email and a password, to open your profile on a new phone."
                else "Your profile is linked to this phone. To open it on another phone, add a login below.", 12f, Ui.MUTED), 8)
        }, 12)
        if (me != null) {
            val sec = ui.section("🔐 కొత్త ఫోన్‌లో లాగిన్ · Log in on a new phone",
                "Add your phone number or email and a password. Then you can open your profile on any phone. Your number or email is never shown to anyone.")
            val lg = meLogin
            if (lg != null && !lOpen) {
                sec.put(ui.text("✅ Login is on: " + lg.optString("shown"), 15f, Ui.GOOD, bold = true), 10)
                sec.put(ui.row(ui.button("Change", "ghost") { lOpen = true; lId = ""; lPw = ""; m.render() }, ui.button("Remove", "danger") { removeLogin() }), 8)
            } else {
                loginForm(sec, "Save login") { setLogin() }
                if (lg != null) sec.put(ui.button("Cancel", "ghost") { lOpen = false; lPw = ""; m.render() }, 6)
            }
            m.add(sec, 12)
            if (focusLogin) { focusLogin = false; m.scrollToView(sec) }
            m.add(ui.section("Delete my profile · ప్రొఫైల్ తొలగించు", "Deletes your profile, all your posts and photos, your comments, likes and follows, for ever.").apply {
                put(ui.row(ui.button("🗑 Delete my profile", "danger") { deleteProfile() }), 10)
            }, 12)
        } else {
            m.add(ui.section("🔐 ప్రొఫైల్ ఉందా? · Already have a profile?", "Log in with the phone number or email and password you added to it.").apply {
                loginForm(this, "Log in") { doLogin() }
            }, 12)
            m.add(ui.section("Or use your backup code", "Had a BigD profile on another phone? Type its backup code.").apply {
                val code = ui.input("Backup code, e.g. 7F3A9C21B4", "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
                put(code, 10)
                put(ui.row(ui.button("Use this code") { restore(code.text.toString()) }), 8)
            }, 12)
        }
    }

    /** Phone number or email, and a password (with "Show password"). */
    private fun loginForm(sec: LinearLayout, label: String, action: () -> Unit) {
        sec.put(ui.input("Mobile number or email", lId).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; onText { lId = it }
        }, 10)
        val pw = ui.input("Password (6 or more letters or numbers)", lPw)
        fun kind() { pw.inputType = InputType.TYPE_CLASS_TEXT or (if (lShow) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD); pw.typeface = Typeface.DEFAULT }
        kind(); pw.onText { lPw = it }
        sec.put(pw, 8)
        sec.put(ui.check("Show password", lShow) { on -> lShow = on; kind(); pw.setSelection(pw.text.length) }, 4)
        sec.put(ui.button(if (lBusy) "Please wait…" else label, "primary") { if (!lBusy) action() }, 8)
    }

    private fun loginReady(): Boolean {
        if (lId.trim().length < 5) { m.toast("Type your mobile number or email."); return false }
        if (lPw.length < 6) { m.toast("The password needs 6 or more letters or numbers."); return false }
        return true
    }

    private fun setLogin() {
        if (!loginReady()) return
        lBusy = true; m.render()
        io.execute {
            val r = Net.post(store, "/api/social/login-set", JSONObject().put("id", lId.trim()).put("password", lPw), headers(), 20_000)
            m.runOnUiThread {
                lBusy = false
                if (r.ok) { meLogin = r.json.optJSONObject("login"); lOpen = false; lPw = ""; m.toast("Login saved ✓ Please remember your password.") }
                else m.toast(r.error("Couldn't save. Try again."))
                refreshIf("edit")
            }
        }
    }

    /** On a new phone: open the profile with its phone number or email and password. */
    private fun doLogin() {
        if (!loginReady()) return
        lBusy = true; m.render()
        io.execute {
            val r = Net.post(store, "/api/social/login", JSONObject().put("id", lId.trim()).put("password", lPw), headers(), 20_000)
            m.runOnUiThread {
                lBusy = false
                if (r.ok) {
                    me = SUser.from(r.json.optJSONObject("profile")); meCode = r.json.optString("code"); meLogin = r.json.optJSONObject("login"); meState = 2
                    feeds.clear(); loadedAt.clear(); suggestAt = 0; lPw = ""
                    m.toast("Welcome back, ${me?.name}! ✓"); back()
                } else { m.toast(r.error("Couldn't log in. Try again.")); refreshIf("edit") }
            }
        }
    }

    private fun removeLogin() {
        confirm("Remove the phone number / email login? You can still use your backup code.") {
            call("/api/social/login-remove", JSONObject(), "Login removed.") { meLogin = null; refreshIf("edit") }
        }
    }

    /** Right after making a profile: offer to add a login, so the profile can be opened on a new phone. */
    private fun askLogin() {
        AlertDialog.Builder(m).setTitle("✅ Your profile is ready!")
            .setMessage("To open your profile on a new phone later, add your phone number or email and a password. Your number or email is never shown to anyone.")
            .setPositiveButton("Add now") { _, _ -> focusLogin = true; lOpen = true; lId = ""; lPw = ""; startEdit() }
            .setNegativeButton("Later", null).show()
    }

    private fun saveProfile() {
        if (eName.trim().length < 2) { m.toast("Write your name (at least 2 letters)."); return }
        if (uploading > 0) { m.toast("Wait for the photo check to finish."); return }
        if (!rulesOk { saveProfile() }) return
        // a forgiving @name: "Kishore.Kumar" or "kishore kumar" becomes "kishore_kumar"
        val hd = eHandle.trim().removePrefix("@").lowercase().replace(Regex("[\\s.\\-]+"), "_").replace(Regex("[^a-z0-9_]"), "")
            .replace(Regex("_+"), "_").trim('_').take(20)
        if (eHandle.isNotBlank() && hd.length < 3) { m.toast("@name: use 3 to 20 small English letters, numbers or _ (for example kishore_kumar)."); return }
        eHandle = hd
        saving = true; m.render()
        val body = JSONObject().put("name", eName.trim()).put("handle", hd).put("bio", eBio.trim()).put("emoji", eEmoji)
        if (eAvatarChanged) body.put("avatar", eAvatar)
        io.execute {
            val r = Net.post(store, "/api/social/me", body, headers(), 45_000)
            m.runOnUiThread {
                saving = false
                if (r.ok) {
                    val first = me == null
                    me = SUser.from(r.json.optJSONObject("profile")); meCode = r.json.optString("code"); meLogin = r.json.optJSONObject("login"); meState = 2; store.feedName = eName.trim()
                    feeds.clear(); loadedAt.clear(); suggestAt = 0
                    m.toast(if (first) "Profile ready ✓" else "Profile saved ✓")
                    back()
                    if (first) askLogin()
                } else { m.toast(r.error("Couldn't save. Try again.")); refreshIf("edit") }
            }
        }
    }

    private fun restore(code: String) {
        if (code.trim().length < 8) { m.toast("Type the full backup code."); return }
        io.execute {
            val r = Net.post(store, "/api/social/restore", JSONObject().put("code", code.trim()), headers(), 20_000)
            m.runOnUiThread {
                if (r.ok) {
                    me = SUser.from(r.json.optJSONObject("profile")); meCode = r.json.optString("code"); meLogin = r.json.optJSONObject("login"); meState = 2
                    feeds.clear(); loadedAt.clear(); suggestAt = 0
                    m.toast("Welcome back, ${me?.name}! ✓"); back()
                } else m.toast(r.error("That code didn't work."))
            }
        }
    }

    // ------------------------------ writing a post ------------------------------
    /** Opens the composer, as yourself or (team) as an official account. */
    fun startCompose(asUid: String = "") {
        if (me == null && asUid.isBlank() && meState == 2) { m.toast("First make your profile."); startEdit(); return }
        if (screen == "compose") { cAs = asUid; m.render(); return }
        go("compose") { cAs = asUid }
    }

    /** Something shared to "Post on BigD Media" from another app: text, a link, or photos. */
    fun fromShare(text: String, uris: List<Uri>) {
        if (listening.isNotEmpty()) m.stopListening()
        history.clear(); history.add(Step("feed", null, null, 0)); screen = "compose"; cAs = ""
        val url = Regex("https?://\\S+").find(text)?.value ?: ""
        if (url.isNotBlank()) { cLink = url; previewFor = ""; cPreview = null }
        val rest = (if (url.isNotBlank()) text.replace(url, "") else text).trim()
        if (rest.isNotBlank()) cText = (if (cText.isBlank()) rest else cText.trimEnd() + "\n" + rest).take(1000)
        m.render(); m.scrollTop()
        val vids = uris.filter { (try { m.contentResolver.getType(it) } catch (e: Exception) { null })?.startsWith("video/") == true }
        if (vids.isNotEmpty()) onVideo(vids[0])
        else if (uris.isNotEmpty()) { photoFor = "post"; onPhotos(uris) }
        if (cLink.isNotBlank()) main.postDelayed(previewLater, 300)
    }

    private fun buildCompose() {
        backBar("New post · కొత్త పోస్ట్")
        if (me == null && cAs.isBlank()) {
            if (meState != 2) m.add(ui.text("Loading…", 14f, Ui.MUTED), 12)
            else m.add(ui.card(Ui.SUN_SOFT).apply {
                addView(ui.text("👤 First make your profile (just your name). Your post stays here.", 14.5f, Ui.INK, bold = true))
                put(ui.row(ui.button("Make my profile", "primary") { startEdit() }), 10)
            }, 12)
        }
        m.add(ui.card().apply {
            if (admin) {
                put(ui.text("Post as", 13f, Ui.MUTED), 0)
                val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
                (listOf("" to "🙂 Me") + tabs.filter { it.key in sectionKeys }.map { "s_${it.key}" to it.label }).forEachIndexed { i, (k, l) ->
                    row.addView(ui.chip(l, cAs == k) { cAs = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
                }
                put(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 6)
            } else me?.let { u ->
                put(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(avatar(u, 38), LinearLayout.LayoutParams(dp(38), dp(38)))
                    addView(ui.text(u.name, 15f, Ui.INK, bold = true).apply { setPadding(dp(10), 0, 0, 0) })
                }, 0)
            }
            val box = ui.input("What's happening? · ఏం జరుగుతోంది?", cText, multi = true).apply { minLines = 4; maxLines = 12; onText { cText = it } }
            put(box, 12)
            put(ui.row(mic("compose", box)), 6)
            // photos, or one video
            val v = cVideo
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (v != null) addView(FrameLayout(m).apply {
                    background = ui.round(Color.BLACK, 12); clipToOutline = true
                    addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load(abs(v.poster), this, 400) }, FrameLayout.LayoutParams(-1, -1))
                    addView(TextView(m).apply { text = "▶ " + clock(v.dur); setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(8), dp(3), dp(8), dp(3)); background = ui.round(Color.parseColor("#99000000"), 8) },
                        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
                    addView(TextView(m).apply {
                        text = "✕"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#99000000"), 13)
                        contentDescription = "Remove video"; setOnClickListener { cVideo = null; m.render() }
                    }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
                }, LinearLayout.LayoutParams(dp(if (v.h > v.w) 112 else 170), dp(if (v.h > v.w) 200 else 96)))
                if (v == null) cPhotos.forEachIndexed { i, (_, url) ->
                    addView(FrameLayout(m).apply {
                        background = ui.round(SOFT, 10); clipToOutline = true
                        addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load(abs(url), this, 300) }, FrameLayout.LayoutParams(-1, -1))
                        addView(TextView(m).apply {
                            text = "✕"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#99000000"), 11)
                            contentDescription = "Remove photo"; setOnClickListener { cPhotos.removeAt(i); m.render() }
                        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(2), dp(2), 0) })
                    }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(6) })
                }
                if (v == null && vState.isEmpty() && cPhotos.size + uploading < 4) addView(ui.button(if (uploading > 0 && photoFor == "post") "Checking…" else "📷 Photos", "soft") {
                    if (uploading == 0) { photoFor = "post"; m.pickPhotos(4 - cPhotos.size) }
                })
                if (v == null && vState.isEmpty() && cPhotos.isEmpty() && uploading == 0) addView(ui.button("🎬 Video", "soft") { m.pickVideo() },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, 12)
            if (uploading > 0 && photoFor == "post") put(ui.text("🛡️ Checking your photo for safety…", 12.5f, Ui.MUTED), 6)
            if (vState.isNotEmpty()) put(ui.text(videoStatusText(), 13f, Ui.TEAL_DARK, bold = true).also { vStatus = it }, 8)
            if (v == null && vState.isEmpty() && cPhotos.isEmpty()) put(ui.text("Videos: up to 1 minute. They are made smaller and checked for safety before anyone sees them.", 11.5f, Ui.MUTED), 4)
            // a link from another app
            put(ui.text("🔗 Video or post link from YouTube, Instagram, Facebook, X… (optional)", 13f, Ui.MUTED), 14)
            val link = ui.input("https://…", cLink).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                onText { v -> cLink = v; main.removeCallbacks(previewLater); main.postDelayed(previewLater, 900) }
            }
            put(link, 6)
            put(ui.row(ui.button("📋 Paste link", "ghost") {
                val clip = try { m.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(m)?.toString() } catch (e: Exception) { null }
                val u = clip?.let { Regex("https?://\\S+").find(it)?.value }
                if (u == null) m.toast("No link copied. In the other app tap Share › Copy link, then come back.") else link.setText(u)
            }), 6)
            val pv = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
            previewBox = pv
            put(pv, 8)
            showPreview()
            put(ui.button(if (posting) "Posting…" else "📢 Post", "primary") { if (!posting) post() }, 16)
            put(ui.text("Posts are public and checked for safety. No phone numbers or private details. Videos from other apps are shared as links.", 11.5f, Ui.MUTED), 8)
        }, 10)
        if (cLink.isNotBlank() && cLink.trim() != previewFor) main.postDelayed(previewLater, 300)
    }

    private fun fetchPreview() {
        val u = cLink.trim()
        if (!Regex("^https?://\\S+$").matches(u)) { cPreview = null; previewFor = ""; showPreview(); return }
        if (u == previewFor) return
        previewFor = u
        io.execute {
            val r = Net.post(store, "/api/social/preview", JSONObject().put("url", u), headers(), 20_000)
            m.runOnUiThread { if (previewFor == u) { cPreview = if (r.ok) r.json.optJSONObject("link") else null; showPreview() } }
        }
    }

    private fun showPreview() {
        val box = previewBox ?: return
        box.removeAllViews()
        val o = cPreview ?: return
        if (o.optString("url") != cLink.trim()) return
        val title = if (o.optString("yt").isNotBlank()) "▶ " + o.optString("title").ifBlank { "YouTube video" } else o.optString("title")
        box.addView(linkCard(o.optString("site"), title, o.optString("image"), o.optString("url")) { }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Photos chosen in the phone's gallery (or shared from another app). */
    fun onPhotos(uris: List<Uri>) {
        val list = if (photoFor == "avatar") uris.take(1) else uris.take(maxOf(0, 4 - cPhotos.size - uploading))
        if (list.isEmpty()) { if (photoFor != "avatar") m.toast("You can add up to 4 photos."); return }
        val target = photoFor
        uploading += list.size; m.render()
        io.execute {
            for (u in list) {
                val bytes = Images.prepare(m, u)
                val r = if (bytes == null) null else Net.post(store, "/api/social/upload", JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)), headers(), 90_000)
                m.runOnUiThread {
                    uploading = maxOf(0, uploading - 1)
                    when {
                        r == null -> m.toast("Couldn't open that photo.")
                        !r.ok -> m.toast("Photo not added: " + r.error("please try another photo."))
                        target == "avatar" -> { eAvatar = r.json.optString("id"); eAvatarUrl = r.json.optString("url"); eAvatarChanged = true }
                        else -> if (cPhotos.size < 4) cPhotos.add(r.json.optString("id") to r.json.optString("url"))
                    }
                    refreshIf(if (target == "avatar") "edit" else "compose")
                }
            }
        }
    }

    private fun clock(sec: Int) = String.format(Locale.ENGLISH, "%d:%02d", sec / 60, sec % 60)
    private fun videoStatusText() = if (vState == "uploading") "⬆️ Sending your video… $vPct%" else "🛡️ Making your video smaller and checking it for safety (up to a minute)…"

    /** A video chosen in the gallery or shared from another app: checked here, sent to the server, then prepared there. */
    fun onVideo(uri: android.net.Uri) {
        if (vState.isNotEmpty()) { m.toast("Wait for the first video to finish."); return }
        val ms = try { android.media.MediaMetadataRetriever().run { try { setDataSource(m, uri); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { release() } } } catch (e: Exception) { 0L }
        val size = try { m.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (e: Exception) { -1L }
        if (ms > 61_000) { m.toast("Please choose a video up to 1 minute long. You can trim it in your Gallery first."); return }
        if (size > 100L * 1024 * 1024) { m.toast("That video is too big (over 100 MB). Please trim it in your Gallery first."); return }
        val type = (try { m.contentResolver.getType(uri) } catch (e: Exception) { null }) ?: "video/mp4"
        cPhotos.clear(); vState = "uploading"; vPct = 0
        if (screen != "compose") startCompose() else m.render()
        io.execute {
            var id = ""; var err = ""
            try {
                val c = URL(store.server.trimEnd('/') + "/api/social/upload-video").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 120_000; c.doOutput = true
                c.setRequestProperty("Content-Type", if (type.startsWith("video/")) type else "video/mp4")
                c.setRequestProperty("X-Device", store.deviceId)
                headers().forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (size > 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(64 * 1024)
                m.contentResolver.openInputStream(uri)?.use { inp ->
                    c.outputStream.use { out ->
                        val buf = ByteArray(64 * 1024); var sent = 0L; var shown = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); sent += n
                            val pct = if (size > 0) (sent * 100 / size).toInt() else 0
                            if (pct != shown) { shown = pct; main.post { vPct = pct; vStatus?.text = videoStatusText() } }
                        }
                    }
                } ?: throw java.io.IOException("can't open")
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (code in 200..299) id = j.optString("id") else err = j.optString("error").ifBlank { "Couldn't send the video. Please try again." }
            } catch (e: Exception) { err = "The video couldn't be sent. Check your internet and try again." }
            if (id.isBlank()) { main.post { vState = ""; m.toast(err.ifBlank { "Couldn't send the video." }); refreshIf("compose") }; return@execute }
            main.post { vState = "checking"; refreshIf("compose") }
            // wait while the server makes it smaller and checks it
            val until = System.currentTimeMillis() + 6 * 60_000L
            while (System.currentTimeMillis() < until) {
                Thread.sleep(3000)
                val r = Net.get(store, "/api/social/upload-status?id=$id", headers())
                val st = r.json.optString("status")
                if (st == "ready") {
                    val pick = VideoPick(id, r.json.optString("poster"), r.json.optInt("dur"), r.json.optInt("w"), r.json.optInt("h"))
                    main.post { vState = ""; cVideo = pick; refreshIf("compose") }
                    return@execute
                }
                if (st == "failed" || r.code == 404) { main.post { vState = ""; m.toast(r.json.optString("reason").ifBlank { "That video can't be posted." }); refreshIf("compose") }; return@execute }
            }
            main.post { vState = ""; m.toast("The video is taking too long. Please try a shorter video."); refreshIf("compose") }
        }
    }

    private fun post() {
        if (me == null && cAs.isBlank()) { m.toast("First make your profile."); startEdit(); return }
        if (uploading > 0) { m.toast("Wait for the photo check to finish."); return }
        if (vState.isNotEmpty()) { m.toast("Wait for the video to finish."); return }
        if (cText.isBlank() && cPhotos.isEmpty() && cVideo == null && cLink.isBlank()) { m.toast("Write something, or add a photo, video or link."); return }
        if (cLink.isNotBlank() && !Regex("^https?://\\S+$").matches(cLink.trim())) { m.toast("The link must start with https://"); return }
        if (!rulesOk { post() }) return
        if (listening.isNotEmpty()) m.stopListening()
        posting = true; m.render()
        val body = JSONObject().put("text", cText.trim()).put("photos", JSONArray(cPhotos.map { it.first })).put("link", cLink.trim())
        cVideo?.let { body.put("video", it.id) }
        if (cAs.isNotBlank()) body.put("as", cAs)
        io.execute {
            val r = Net.post(store, "/api/social/post", body, headers(), 60_000)
            m.runOnUiThread {
                posting = false
                if (r.ok) {
                    m.toast("Posted ✓")
                    val target = if (cAs.startsWith("s_")) cAs.removePrefix("s_") else if (tab in sectionKeys || tab == "following") "foryou" else tab
                    r.json.optJSONObject("post")?.let { o ->
                        val p = SPost.from(o)
                        val keys = mutableListOf("foryou", "user:${p.author.uid}")
                        if (p.author.official) keys.add(p.cat) else keys.add("people")
                        keys.forEach { k -> feeds[k]?.add(0, p) }
                    }
                    cText = ""; cPhotos.clear(); cVideo = null; cLink = ""; cPreview = null; previewFor = ""; cAs = ""
                    tab = target; history.clear(); screen = "feed"; m.render(); m.scrollTop()
                } else { m.toast(r.error("Couldn't post. Try again.")); refreshIf("compose") }
            }
        }
    }

    /** Deletes this phone's Media profile and everything linked to it, after asking. Works from any page. */
    fun deleteProfile() {
        AlertDialog.Builder(m).setTitle("Delete your profile? · ప్రొఫైల్ తొలగించాలా?")
            .setMessage("This deletes for ever:\n\n• your profile (name, photo, @name, tagline) and backup code\n• all your posts and their photos\n• your comments, likes and follows\n\nThis can't be undone. The rest of BigD AI keeps working as before.")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    val r = Net.post(store, "/api/social/delete-me", JSONObject().put("confirm", "DELETE"), headers(), 30_000)
                    m.runOnUiThread {
                        if (r.ok) {
                            me = null; meCode = ""; meState = 2; store.feedName = ""; store.feedAgreed = false
                            feeds.clear(); loadedAt.clear(); suggestAt = 0; comments.clear(); commentsFor = null; profile = null
                            cText = ""; cPhotos.clear(); cVideo = null; cLink = ""; cPreview = null; previewFor = ""; cAs = ""
                            history.clear(); screen = "feed"
                            m.toast("Your profile and everything in it is deleted.")
                            m.render(); if (m.tab == "feedback") m.scrollTop()
                        } else m.toast(r.error("Couldn't delete. Check your internet and try again."))
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------ team: reports ------------------------------
    private fun openReports() { go("reports") { repLoading = true }; loadReports() }

    private fun loadReports() {
        repLoading = true
        io.execute {
            val r = Net.get(store, "/api/social/reports", headers())
            m.runOnUiThread {
                repLoading = false
                if (r.ok) {
                    val a = r.json.optJSONArray("posts"); val c = r.json.optJSONArray("comments")
                    repPosts = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> SPost.from(o) to o } }
                    repComments = if (c == null) emptyList() else (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                } else m.toast(r.error("Couldn't load the reports."))
                refreshIf("reports")
            }
        }
    }

    private fun reportLine(o: JSONObject): String {
        val a = o.optJSONArray("reasons")
        val why = if (a == null) "" else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.joinToString(", ") { if (it.value > 1) "${it.key} ×${it.value}" else it.key }
        return "🚩 ${o.optInt("reports")} report" + (if (o.optInt("reports") == 1) "" else "s") + (if (o.optBoolean("hidden")) " · hidden now" else "") + (if (why.isNotBlank()) " · $why" else "")
    }

    private fun scrollRow(vararg views: View) = HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(ui.row(*views)) }

    private fun stopPerson(u: SUser) {
        confirm("Stop ${u.name} from posting and commenting? Their posts will be hidden.") {
            call("/api/social/admin", JSONObject().put("action", "ban").put("uid", u.uid), "Done. Their posts are hidden.") { refresh(); if (screen == "reports") loadReports() }
        }
    }

    private fun buildReports() {
        backBar("🛡 Reports · ఫిర్యాదులు")
        m.add(ui.text("Check each one: Keep it if it's fine, or Remove it. Posts and comments with 3 reports stay hidden until you decide.", 13f, Ui.MUTED), 8)
        if (repLoading) { m.add(ui.text("Loading…", 14f, Ui.MUTED), 12); return }
        if (repPosts.isEmpty() && repComments.isEmpty()) { m.add(ui.text("No reports right now 👍", 15f, Ui.INK), 14); return }
        repPosts.forEach { (p, o) ->
            m.add(ui.text(reportLine(o), 13.5f, Ui.DANGER, bold = true), 18)
            m.add(postRow(p, full = true), 6)
            val keep = ui.button("✓ Keep") {
                call("/api/social/admin", JSONObject().put("action", "unhide").put("key", p.key), "Kept. It shows again.") { repPosts = repPosts.filter { it.first.key != p.key }; refresh() }
            }
            val remove = ui.button("🗑 Remove", "danger") {
                confirm("Remove this post for everyone?") { call("/api/social/delete", JSONObject().put("key", p.key), "Removed.") { repPosts = repPosts.filter { it.first.key != p.key }; removePost(p.key) } }
            }
            m.add(if (p.author.official) scrollRow(keep, remove) else scrollRow(keep, remove, ui.button("🛡 Stop person", "ghost") { stopPerson(p.author) }), 6)
        }
        if (repComments.isNotEmpty()) m.add(ui.label("Reported comments"), 20)
        repComments.forEach { c ->
            val id = c.optInt("id"); val author = SUser.from(c.optJSONObject("author"))
            m.add(ui.card().apply {
                addView(ui.text(reportLine(c), 13.5f, Ui.DANGER, bold = true))
                put(nameLine(author, c.optLong("at"), null).apply { setOnClickListener { openProfile(author.uid) } }, 8)
                put(ui.text(c.optString("text"), 14.5f, Ui.INK).apply { setTextIsSelectable(true) }, 4)
                put(scrollRow(
                    ui.button("✓ Keep") { call("/api/social/admin", JSONObject().put("action", "unhide").put("comment", id), "Kept.") { repComments = repComments.filter { it.optInt("id") != id } } },
                    ui.button("🗑 Remove", "danger") { call("/api/social/delete", JSONObject().put("comment", id), "Removed.") { repComments = repComments.filter { it.optInt("id") != id } } },
                    ui.button("🛡 Stop person", "ghost") { stopPerson(author) }), 10)
            }, 10)
        }
    }

    /** The community rules, agreed once before the first post, comment or profile. Returns true if already agreed. */
    private fun rulesOk(then: () -> Unit): Boolean {
        if (store.feedAgreed) return true
        AlertDialog.Builder(m).setTitle("Community rules · నియమాలు")
            .setMessage("Posts, comments and profiles are public: everyone using BigD AI can see them.\n\n• Be kind and respectful.\n• No bad words, hate, adult or violent content.\n• No phone numbers, addresses or ID numbers.\n• No ads, scams or fake news.\n• Share only photos you are allowed to share.\n• Nothing that harms children, ever.\n\nPosts that break the rules are removed and the person can be blocked. You can delete your posts at any time.\n\nFull rules: ${store.server}/rules")
            .setPositiveButton("I agree") { _, _ -> store.feedAgreed = true; then() }.setNegativeButton("Cancel", null).show()
        return false
    }

    // ------------------------------ comments ------------------------------
    private fun openComments(p: SPost) {
        if (screen == "comments" && commentsFor?.key == p.key) return
        go("comments") { commentsFor = p; comments.clear(); commentsLoading = true }
        loadComments(p)
    }

    private fun loadComments(p: SPost) {
        comments.clear(); commentsLoading = true
        io.execute {
            val r = Net.get(store, "/api/social/comments?key=${Uri.encode(p.key)}", headers())
            m.runOnUiThread {
                if (commentsFor?.key != p.key) return@runOnUiThread
                commentsLoading = false; comments.clear()
                if (r.ok) r.json.optJSONArray("comments")?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                        comments.add(SComment(o.optInt("id"), o.optLong("at"), o.optString("text"), SUser.from(o.optJSONObject("author")), o.optBoolean("mine")))
                    }
                } else m.toast(r.error("Couldn't load comments."))
                refreshIf("comments")
            }
        }
    }

    private fun buildComments() {
        val p = commentsFor ?: run { screen = "feed"; buildFeed(); return }
        backBar("Post")
        m.add(postRow(p, full = true), 8)
        m.add(ui.label("Comments · వ్యాఖ్యలు"), 18)
        val shown = comments.filter { it.author.uid !in store.blockedUsers }
        if (commentsLoading) m.add(ui.text("Loading…", 14f, Ui.MUTED), 8)
        else if (shown.isEmpty()) m.add(ui.text("No comments yet. Be the first!", 14f, Ui.MUTED), 8)
        shown.forEach { c ->
            m.add(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = ui.round(Color.WHITE, 16, Ui.LINE)
                addView(avatar(c.author, 36) { openProfile(c.author.uid) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(ui.stack(nameLine(c.author, c.at) { commentMenu(c) }, ui.text(c.text, 14.5f, Ui.INK).apply { setTextIsSelectable(true) }).apply { setPadding(dp(10), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, -2, 1f))
            }, 8)
        }
        if (me == null) { m.add(ui.button("Make your profile to comment", "primary") { startEdit() }, 14); return }
        val box = ui.input("Write a comment… · మీ అభిప్రాయం", commentText, multi = true).apply { onText { commentText = it } }
        m.add(box, 16)
        m.add(ui.row(mic("comment", box), ui.button("Send ➤", "primary") { sendComment() }), 8)
    }

    private fun sendComment() {
        val p = commentsFor ?: return
        val t = commentText.trim(); if (t.isEmpty()) { m.toast("Write a comment first."); return }
        if (!rulesOk { sendComment() }) return
        if (listening.isNotEmpty()) m.stopListening()
        io.execute {
            val r = Net.post(store, "/api/social/comment", JSONObject().put("key", p.key).put("text", t), headers(), 30_000)
            m.runOnUiThread {
                if (r.ok) {
                    r.json.optJSONObject("comment")?.let { o -> comments.add(SComment(o.optInt("id"), o.optLong("at"), o.optString("text"), SUser.from(o.optJSONObject("author")), true)) }
                    samePosts(p.key).forEach { it.comments += 1 }
                    commentText = ""; refreshIf("comments")
                } else m.toast(r.error("Couldn't send. Try again."))
            }
        }
    }

    private fun commentMenu(c: SComment) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("👤 Open profile" to { openProfile(c.author.uid) })
        if (c.mine || admin) items.add("🗑 Delete comment" to {
            call("/api/social/delete", JSONObject().put("comment", c.id), "Deleted.") {
                comments.remove(c); commentsFor?.let { p -> samePosts(p.key).forEach { it.comments = maxOf(0, it.comments - 1) } }
            }
        })
        if (!c.mine) {
            items.add("🚩 Report comment" to { call("/api/social/report", JSONObject().put("comment", c.id), "Thank you. The BigD team will check it.") { comments.remove(c) } })
            items.add("🚫 Hide posts from ${c.author.name}" to { block(c.author) })
        }
        AlertDialog.Builder(m).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    // ------------------------------ actions ------------------------------
    /** 🎤 button: speak instead of typing (easy for elders too). */
    private fun mic(id: String, box: EditText): View {
        val b = ui.button(if (listening == id) "● Listening… tap to stop" else "🎤 Speak", if (listening == id) "danger" else "soft") { }
        fun paint(on: Boolean) {
            b.text = if (on) "● Listening… tap to stop" else "🎤 Speak"
            b.background = ui.round(if (on) Ui.DANGER_SOFT else Ui.TEAL_SOFT, 24); b.setTextColor(if (on) Ui.DANGER else Ui.TEAL_DARK)
        }
        b.setOnClickListener {
            if (listening == id) { m.stopListening(); return@setOnClickListener }
            val before = box.text.toString().trim()
            listening = id; paint(true)
            m.listen(onPartial = { p -> box.setText(if (before.isEmpty()) p else "$before $p"); box.setSelection(box.text.length) }, onDone = { heard ->
                if (listening == id) listening = ""
                if (!heard.isNullOrBlank()) { val v = if (before.isEmpty()) heard else "$before $heard"; box.setText(v); box.setSelection(v.length) }
                paint(false)
            }, continuous = true)
        }
        return b
    }

    private fun like(p: SPost, paint: () -> Unit = {}) {
        val now = !p.liked
        val copies = (samePosts(p.key) + p).distinct()
        copies.forEach { it.liked = now; it.likes = maxOf(0, it.likes + if (now) 1 else -1) }
        paint()
        io.execute {
            val r = Net.post(store, "/api/social/like", JSONObject().put("key", p.key), headers(), 15_000)
            m.runOnUiThread {
                val likes = if (r.ok) r.json.optInt("likes", p.likes) else maxOf(0, p.likes + if (now) -1 else 1)
                val liked = if (r.ok) r.json.optBoolean("liked", p.liked) else !now
                if (!r.ok) m.toast(r.error("Couldn't like. Try again."))
                (samePosts(p.key) + p).distinct().forEach { it.likes = likes; it.liked = liked }
                paint()
            }
        }
    }

    private fun follow(u: SUser) {
        if (me == null && meState == 2) { m.toast("First make your profile."); startEdit(); return }
        val now = !u.followed
        fun mark(f: Boolean, n: Int?) {
            val all = feeds.values.flatten().map { it.author } + suggest + listOfNotNull(profile, commentsFor?.author) + u
            all.filter { it.uid == u.uid }.distinct().forEach { x -> x.followed = f; x.followers = n ?: maxOf(0, x.followers + if (f) 1 else -1) }
        }
        mark(now, null); refreshIf("feed", "profile")
        io.execute {
            val r = Net.post(store, "/api/social/follow", JSONObject().put("uid", u.uid), headers(), 15_000)
            m.runOnUiThread {
                if (r.ok) { mark(r.json.optBoolean("followed", now), r.json.optInt("followers", u.followers)); loadedAt.remove("following"); if (now) m.toast("Following ${u.name} ✓") }
                else { mark(!now, null); m.toast(r.error("Couldn't follow. Try again.")) }
                refreshIf("feed", "profile")
            }
        }
    }

    private fun listen(p: SPost) {
        if (speaking == p.key) { m.stopTalking(); speaking = ""; refreshIf("feed", "profile", "comments"); return }
        speaking = p.key; refreshIf("feed", "profile", "comments")
        m.speakOut(listOf(p.title, p.text).filter { it.isNotBlank() }.joinToString(". ")) {
            if (speaking == p.key) { speaking = ""; refreshIf("feed", "profile", "comments") }
        }
    }

    private fun share(p: SPost) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(p)), "Share")) } catch (e: Exception) { }
    }

    private fun shareText(p: SPost): String {
        val t = listOf(p.title, p.text).filter { it.isNotBlank() }.joinToString("\n\n").take(1500) +
            (if (p.photos.isNotEmpty()) "\n\n" + p.photos.joinToString("\n") { abs(it) } else "") +
            (if (p.ytId.isNotBlank()) "\n\n▶ https://youtu.be/${p.ytId}" else if (p.linkUrl.isNotBlank()) "\n\n${p.linkUrl}" else "") +
            (if (p.sourceUrl.isNotBlank() && p.sourceUrl != p.linkUrl) "\n\n${p.sourceUrl}" else "") +
            "\n\n— ${p.author.name} · BigD AI 🐾 ${store.server}"
        return t.trim()
    }

    private fun open(url: String) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { m.toast("Couldn't open the link.") }
    }

    private fun block(u: SUser) {
        store.blockedUsers = store.blockedUsers + u.uid
        m.toast("You won't see posts from ${u.name}. You can undo this on their profile.")
        refreshIf("feed", "profile", "comments")
    }

    private fun confirm(q: String, yes: () -> Unit) {
        AlertDialog.Builder(m).setMessage(q).setPositiveButton("Yes") { _, _ -> yes() }.setNegativeButton("No", null).show()
    }

    private fun report(p: SPost) {
        val reasons = arrayOf("Bad words or insults", "Adult or violent content", "Harm to a child", "Spam, ads or a scam", "Fake news", "Personal details of someone", "Something else")
        AlertDialog.Builder(m).setTitle("Why are you reporting it?").setItems(reasons) { _, i ->
            call("/api/social/report", JSONObject().put("key", p.key).put("reason", reasons[i]), "Thank you. The BigD team will check it.") { }
        }.show()
    }

    private fun removePost(key: String) {
        feeds.values.forEach { l -> l.removeAll { it.key == key } }
        if (screen == "comments" && commentsFor?.key == key) back()
    }

    private fun menu(p: SPost) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("👤 Open profile" to { openProfile(p.author.uid) })
        if (p.mine || admin) items.add("🗑 Delete post" to { confirm("Delete this post?") { call("/api/social/delete", JSONObject().put("key", p.key), "Deleted.") { removePost(p.key) } } })
        if (!p.mine) {
            items.add("🚩 Report post" to { report(p) })
            if (!p.author.official) items.add("🚫 Hide posts from ${p.author.name}" to { block(p.author) })
        }
        if (admin && !p.author.official && p.author.uid.isNotBlank() && !p.mine) {
            items.add("🛡 Team: stop this person posting" to { stopPerson(p.author) })
            items.add((if (p.author.verified) "Team: remove the ✓" else "Team: give a ✓") to {
                call("/api/social/admin", JSONObject().put("action", if (p.author.verified) "unverify" else "verify").put("uid", p.author.uid), "Done.") { refresh() }
            })
        }
        AlertDialog.Builder(m).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun call(path: String, body: JSONObject, okMsg: String, onOk: () -> Unit) {
        io.execute {
            val r = Net.post(store, path, body, headers(), 20_000)
            m.runOnUiThread {
                if (r.ok) { onOk(); m.toast(okMsg); refreshIf("feed", "profile", "comments", "reports") }
                else m.toast(r.error("Couldn't do that. Try again."))
            }
        }
    }

    private fun generate(cat: String) {
        io.execute {
            val r = Net.post(store, "/api/media/generate", JSONObject().put("cat", cat), headers(), 20_000)
            m.runOnUiThread { m.toast(if (r.ok) "Writing new posts… they appear in a few minutes." else r.error("Couldn't start.")) }
        }
    }

    // ------------------------------ full-screen viewer ------------------------------
    /** Opens [p]'s photo number [which] (-1 = its video) full screen, with every other photo and video of this list above and below it. */
    private fun openViewer(p: SPost, which: Int) {
        val src: List<SPost> = when (screen) { "profile" -> feeds["user:${profile?.uid}"]; "comments" -> null; else -> feeds[feedKey()] }
            ?.takeIf { l -> l.any { it.key == p.key } } ?: listOf(p)
        val blocked = store.blockedUsers
        val items = ArrayList<MediaViewer.Item>(); var start = 0
        for (x in src) {
            if (x.author.uid in blocked) continue
            val who = x.author.name + (if (x.author.verified) " ✓" else "") + (if (x.author.handle.isNotBlank()) "  @${x.author.handle}" else "")
            val words = listOf(x.title, x.text).filter { it.isNotBlank() }.joinToString("\n")
            if (x.videoUrl.isNotBlank()) { if (x.key == p.key && which == -2) start = items.size; items.add(MediaViewer.Item("file", abs(x.videoUrl), x.key, who, words, x.likes, x.liked, abs(x.videoPoster))) }
            x.photos.forEachIndexed { i, u -> if (x.key == p.key && i == which) start = items.size; items.add(MediaViewer.Item("photo", abs(u), x.key, who, words, x.likes, x.liked)) }
            if (x.ytId.isNotBlank()) { if (x.key == p.key && which < 0) start = items.size; items.add(MediaViewer.Item("video", x.ytId, x.key, who, words, x.likes, x.liked)) }
        }
        if (items.isEmpty()) return
        MediaViewer.items = items; MediaViewer.start = start; MediaViewer.server = store.server
        MediaViewer.onLike = { key -> postByKey(key)?.let { like(it) } }
        MediaViewer.onComments = { key -> postByKey(key)?.let { openComments(it) } }
        m.leavingForSystemScreen = true
        m.startActivity(Intent(m, MediaViewer::class.java))
    }

    /**
     * Opens the story reader at [p]: one post per screen (picture or video on top, the full text in a box below);
     * swipe up for the next post of this list. More posts load at the end.
     */
    private fun openReader(p: SPost, auto: Boolean = false) {
        StoryReader.autoplay = auto
        val key = if (screen == "profile") "user:${profile?.uid}" else feedKey()
        val blocked = store.blockedUsers
        val src = (feeds[key]?.takeIf { l -> l.any { it.key == p.key } } ?: listOf(p)).filter { it.author.uid !in blocked || it.key == p.key }
        StoryReader.posts = src.toList()
        StoryReader.start = src.indexOfFirst { it.key == p.key }.coerceAtLeast(0)
        StoryReader.server = store.server
        StoryReader.host = object : StoryReader.Host {
            override fun abs(u: String) = this@SocialPage.abs(u)
            override fun like(p: SPost, paint: () -> Unit) = this@SocialPage.like(p, paint)
            override fun comments(p: SPost) = openComments(p)
            override fun profile(p: SPost) = openProfile(p.author.uid)
            override fun report(p: SPost, reason: String) =
                call("/api/social/report", JSONObject().put("key", p.key).put("reason", reason), "Thank you. The BigD team will check it.") { }
            override fun shareText(p: SPost) = this@SocialPage.shareText(p)
            override fun seen(key: String) {
                if (!viewed.add(key)) return
                pendingViews.add(key); main.removeCallbacks(flushLater); main.postDelayed(flushLater, 4000)
            }
            override fun more(done: (List<SPost>, Boolean) -> Unit) {
                if (this@SocialPage.more[key] != true) { done(emptyList(), true); return }
                load(key, false) { added, end -> done(added.filter { it.author.uid !in store.blockedUsers }, end) }
            }
            override fun speak(text: String, done: () -> Unit) = m.speakOut(text) { done() }
            override fun stopSpeaking() = m.stopTalking()
        }
        m.leavingForSystemScreen = true
        m.startActivity(Intent(m, StoryReader::class.java))
    }

    /** A profile photo, full screen. */
    private fun openPhoto(url: String, u: SUser) {
        MediaViewer.items = listOf(MediaViewer.Item("photo", url, "", u.name + if (u.verified) " ✓" else "", u.bio, 0, false))
        MediaViewer.start = 0; MediaViewer.server = store.server; MediaViewer.onLike = null; MediaViewer.onComments = null
        m.leavingForSystemScreen = true
        m.startActivity(Intent(m, MediaViewer::class.java))
    }

    // ------------------------------ loading ------------------------------
    private fun loadMe() {
        meState = 1
        io.execute {
            val r = Net.get(store, "/api/social/me", headers())
            m.runOnUiThread {
                if (r.ok) {
                    me = r.json.optJSONObject("profile")?.let { SUser.from(it) }; meCode = r.json.optString("code"); meLogin = r.json.optJSONObject("login")
                    admin = r.json.optBoolean("admin"); meState = 2
                    refreshIf("feed", "compose", "profile", "comments")
                } else meState = 0   // offline: ask again the next time the page is shown
            }
        }
    }

    private fun loadSuggest() {
        suggestAt = System.currentTimeMillis()
        io.execute {
            val r = Net.get(store, "/api/social/suggest", headers())
            m.runOnUiThread {
                if (r.ok) {
                    r.json.optJSONArray("users")?.let { a -> suggest = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> SUser.from(o) } } }
                    refreshIf("feed")
                } else suggestAt = 0
            }
        }
    }

    /** [after] (for the story reader) gets the posts that were added, and true when there are no more. */
    private fun load(key: String, fresh: Boolean, after: ((List<SPost>, Boolean) -> Unit)? = null) {
        if (key in busy) { after?.invoke(emptyList(), false); return }
        busy.add(key); if (key == feedKey()) error = ""
        val before = if (fresh) "" else feeds[key]?.minOfOrNull { it.at }?.let { "&before=$it" } ?: ""
        // "tab|mood|issue" when filtered
        val parts = key.split("|")
        val filters = (parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { "&mood=" + Uri.encode(it) } ?: "") + (parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { "&issue=" + Uri.encode(it) } ?: "")
        io.execute {
            val r = Net.get(store, "/api/social/feed?tab=${Uri.encode(parts[0])}$filters&limit=20$before", headers())
            m.runOnUiThread {
                busy.remove(key); loadedAt[key] = System.currentTimeMillis()
                if (r.ok) {
                    val a = r.json.optJSONArray("posts")
                    val got = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> SPost.from(o) } }
                    val now = if (fresh) mutableListOf() else (feeds[key] ?: mutableListOf())
                    val added = ArrayList<SPost>()
                    got.forEach { p -> if (now.none { it.key == p.key }) { now.add(p); added.add(p) } }
                    feeds[key] = now; more[key] = r.json.optBoolean("more"); admin = r.json.optBoolean("admin", admin)
                    after?.invoke(added, more[key] != true)
                } else {
                    if (key == feedKey()) error = r.error("Couldn't load posts.")
                    if (feeds[key] == null) feeds[key] = mutableListOf()
                    after?.invoke(emptyList(), false)
                }
                val showing = (screen == "feed" && key == feedKey()) || (screen == "profile" && key == "user:${profile?.uid}")
                if (showing && m.tab == "feedback") m.render()
            }
        }
    }

    // ------------------------------ views ------------------------------
    /** Called while the page scrolls: counts the posts that are really on the screen, and loads more at the end. */
    fun checkSeen() {
        if (m.tab != "feedback") return
        for ((key, v) in rows) {
            if (key in viewed) continue
            if (v.isShown && v.getGlobalVisibleRect(seenRect) && seenRect.height() >= minOf(v.height, dp(200)) * 0.6f) { viewed.add(key); pendingViews.add(key) }
        }
        if (pendingViews.isNotEmpty()) { main.removeCallbacks(flushLater); main.postDelayed(flushLater, 4000) }
        val last = lastRow
        val key = if (screen == "feed") tab else if (screen == "profile") "user:${profile?.uid}" else ""
        if (key.isNotEmpty() && last != null && more[key] == true && key !in busy && last.isShown && last.getGlobalVisibleRect(seenRect)) load(key, false)
    }

    fun flushViews() {
        main.removeCallbacks(flushLater)
        if (pendingViews.isEmpty()) return
        val keys = pendingViews.take(60); pendingViews.removeAll(keys.toSet())
        io.execute { Net.post(store, "/api/social/views", JSONObject().put("keys", JSONArray(keys)), headers(), 15_000) }
    }
}
