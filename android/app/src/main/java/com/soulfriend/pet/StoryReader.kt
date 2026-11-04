package com.soulfriend.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Media story reader: one post per screen, like a news-cards app.
 * The picture or video is on top; below it the headline and the whole text, in a box that scrolls by itself.
 * Swipe up for the next post and down for the previous one; news, people's posts and videos all come one after another,
 * and videos play right at the top. More posts load at the end.
 */
class StoryReader : Activity() {

    /** What the Media page does for the reader. */
    interface Host {
        fun abs(u: String): String
        fun like(p: SPost, paint: () -> Unit)
        fun comments(p: SPost)
        fun profile(p: SPost)
        fun report(p: SPost, reason: String)
        fun shareText(p: SPost): String
        fun seen(key: String)
        /** Loads more posts. [done] gets the new ones, and true when there are no more at all. */
        fun more(done: (List<SPost>, Boolean) -> Unit)
        fun speak(text: String, done: () -> Unit)
        fun stopSpeaking()
    }

    companion object {
        /** What to show (set just before opening). */
        var posts: List<SPost> = emptyList()
        var start = 0
        var server = "https://bigdai.tech"
        var host: Host? = null
        /** Open as news reels: each post is read aloud and the next one comes by itself, like a video. */
        var autoplay = false

        private val CAT = mapOf(
            "news" to "📰 వార్తలు · News", "movies" to "🎬 సినిమా · Movies", "jobs" to "💼 ఉద్యోగాలు · Jobs",
            "devotional" to "🛕 భక్తి · Devotional", "kids" to "🧒 పిల్లలు · Kids", "health" to "🩺 ఆరోగ్యం · Health",
            "farming" to "🌾 వ్యవసాయం · Farming", "realestate" to "🏠 రియల్ ఎస్టేట్ · Real estate", "crypto" to "🪙 క్రిప్టో · Crypto")
        private val CAT_COLOR = mapOf("news" to "#3B82F6", "movies" to "#F97316", "jobs" to "#6366F1", "devotional" to "#FB923C", "kids" to "#F472B6",
            "health" to "#14B8A6", "farming" to "#65A30D", "realestate" to "#10B981", "crypto" to "#F59E0B")
        private val PINK = Color.parseColor("#F91880")
        private val BLUE = Color.parseColor("#1D9BF0")
        private val BOX = Color.parseColor("#F3F6F5")
    }

    /** The parts of one page that change after it is made. */
    private class Holder(val area: FrameLayout, val text: ScrollView, val like: TextView, val listen: TextView) {
        var yt: FrameLayout? = null
        var video: VideoView? = null
        var poster: ImageView? = null
        var sign: TextView? = null
    }

    private val list = ArrayList<SPost>()
    private lateinit var ui: Ui
    private fun dp(v: Int) = ui.dp(v)
    private lateinit var root: FrameLayout
    private lateinit var pager: SnapPager
    private val pages = ArrayList<FrameLayout>()
    private lateinit var endPage: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var count: TextView
    private var at = -1
    private var insetTop = 0
    private var insetBottom = 0
    private var asking = false
    private var noMore = false
    private var speakingKey = ""
    private var auto = false
    private lateinit var autoBtn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val autoTask = Runnable { readAndNext() }
    private val spot = IntArray(2)
    private val seenTask = Runnable { list.getOrNull(at)?.let { host?.seen(it.key) } }
    // videos
    private var web: WebView? = null
    private var webAt = -1
    private var fileAt = -1
    private var full: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var backCb: Any? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (posts.isEmpty() || host == null) { finish(); return }
        ui = Ui(this)
        list.addAll(posts)
        edgeToEdge()
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B1415")) }
        pager = SnapPager(this)
        val h0 = resources.displayMetrics.heightPixels
        endPage = FrameLayout(this)
        pager.box.addView(endPage, LinearLayout.LayoutParams(-1, h0))
        for (i in list.indices) addPage(h0)
        buildEnd()
        pager.onPage = { show(it) }
        pager.childScrolls = { x, y, up -> textCanScroll(x, y, up) }
        pager.addOnLayoutChangeListener { _, _, t, _, bt, _, ot, _, ob -> if (bt - t != ob - ot && ob - ot > 0 && at >= 0) handler.post { refill() } }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(round("✕", "Close") { finish() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        auto = autoplay; autoplay = false
        autoBtn = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { setAuto(!auto) }
        }
        topBar.addView(autoBtn, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(10) })
        paintAuto()
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        count = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13.5f; setPadding(dp(12), dp(6), dp(12), dp(6)); background = ui.round(Color.parseColor("#77000000"), 16) }
        topBar.addView(count)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        if (list.size > 1 || !noMore) {
            val tip = TextView(this).apply {
                text = if (auto) "▶  వార్తలు వీడియోలా · News reels: I read each one, then the next comes" else "⬆  పైకి స్వైప్ చేయండి · Swipe up for the next"; setTextColor(Color.WHITE); textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8)); background = ui.round(Color.parseColor("#CC000000"), 18)
            }
            root.addView(tip, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            tip.animate().alpha(0f).setStartDelay(2400).setDuration(700).withEndAction { root.removeView(tip) }.start()
        }

        root.setOnApplyWindowInsetsListener { _, ins -> insets(ins); ins }
        setContentView(root)
        // after the first layout: the page size and the phone's bars are known
        root.post { pager.jump(start.coerceIn(0, list.size - 1)) }
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Suppress("DEPRECATION")
    private fun insets(ins: WindowInsets) {
        val t: Int; val b: Int
        if (Build.VERSION.SDK_INT >= 30) { val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); t = s.top; b = s.bottom }
        else { t = ins.systemWindowInsetTop; b = ins.systemWindowInsetBottom }
        topBar.setPadding(dp(10), t + dp(6), dp(12), dp(10))
        if (t != insetTop || b != insetBottom) { insetTop = t; insetBottom = b; if (at >= 0) handler.post { refill() } }
    }

    private fun round(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; contentDescription = desc
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#77000000")) }
        setOnClickListener { onClick() }
    }

    // ============================== pages ==============================
    private fun pageHeight() = if (pager.height > 0) pager.height else resources.displayMetrics.heightPixels

    /** Adds an empty page for the last post in [list], just before the "all caught up" page. */
    private fun addPage(h: Int) {
        val pg = FrameLayout(this)
        pages.add(pg)
        pager.box.addView(pg, pages.size - 1, LinearLayout.LayoutParams(-1, h))
    }

    private fun show(i: Int) {
        at = i
        handler.removeCallbacks(seenTask)
        if (webAt >= 0 && webAt != i) stopVideo()
        if (fileAt >= 0 && fileAt != i) stopFile()
        if (speakingKey.isNotEmpty() && list.getOrNull(i)?.key != speakingKey) stopListening()
        for (k in pages.indices) { if (k >= i - 1 && k <= i + 1) fill(k) else if (k < i - 2 || k > i + 2) clear(k) }
        count.text = if (i < list.size) "${i + 1} / ${list.size}" else "✓"
        if (i >= list.size - 3) askMore()
        val p = list.getOrNull(i) ?: return
        handler.postDelayed(seenTask, 1500)
        if (p.ytId.isNotBlank()) play(i) else if (p.videoUrl.isNotBlank()) playFile(i)
        handler.removeCallbacks(autoTask)
        if (auto) handler.postDelayed(autoTask, 600)
    }

    // ============================== news reels ==============================
    private fun paintAuto() {
        autoBtn.text = if (auto) "⏸  Reels" else "▶  Reels"
        autoBtn.background = ui.round(Color.parseColor(if (auto) "#E8336D" else "#77000000"), 21)
        autoBtn.contentDescription = if (auto) "Stop the news reels" else "Play the news like a video"
        if (auto) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setAuto(on: Boolean) {
        auto = on; paintAuto()
        handler.removeCallbacks(autoTask)
        if (on) handler.post(autoTask) else if (speakingKey.isNotEmpty()) stopListening()
    }

    /** Reels: read the post on screen aloud (the picture slowly zooms like a video), then slide to the next one. */
    private fun readAndNext() {
        if (!auto) return
        val i = at; val p = list.getOrNull(i) ?: return
        if (p.ytId.isNotBlank() || p.videoUrl.isNotBlank()) return          // a video plays by itself; swipe for the next
        val h = host ?: return
        val hd = pages.getOrNull(i)?.tag as? Holder
        if (speakingKey.isNotEmpty()) stopListening()
        speakingKey = p.key; hd?.listen?.text = "⏹"
        val text = listOf(p.title, p.text).filter { it.isNotBlank() }.joinToString(". ")
        (hd?.area?.getChildAt(0) as? ImageView)?.let { img ->
            img.scaleX = 1f; img.scaleY = 1f
            img.animate().scaleX(1.12f).scaleY(1.12f).setDuration((text.length * 70L).coerceIn(6000, 40_000)).start()
        }
        h.speak(text) {
            if (speakingKey != p.key) return@speak
            speakingKey = ""; hd?.listen?.text = "🔊"
            if (auto && at == i) handler.postDelayed({ if (auto && at == i && !pager.next()) setAuto(false) }, 900)
        }
    }

    /** Makes the pages around the one on screen again (after the screen size changed). */
    private fun refill() {
        if (at < 0) return
        for (k in at - 1..at + 1) if (k in pages.indices) { clear(k); fill(k) }
        val p = list.getOrNull(at) ?: return
        if (p.ytId.isNotBlank()) play(at) else if (p.videoUrl.isNotBlank()) playFile(at)
    }

    private fun clear(k: Int) {
        val pg = pages[k]
        if (pg.childCount == 0) return
        if (webAt == k) stopVideo()
        if (fileAt == k) stopFile()
        pg.tag = null
        pg.removeAllViews()
    }

    private fun pictureOf(p: SPost) = when {
        p.photos.isNotEmpty() -> host?.abs(p.photos[0]) ?: p.photos[0]
        p.linkImage.isNotBlank() -> p.linkImage
        else -> ""
    }

    private fun fill(k: Int) {
        val pg = pages[k]
        if (pg.childCount > 0) return
        val p = list[k]
        val h = pageHeight()
        val pic = pictureOf(p)
        val tall = p.videoUrl.isNotBlank() && p.videoH > p.videoW
        val hasMedia = p.ytId.isNotBlank() || p.videoUrl.isNotBlank() || pic.isNotBlank()
        val mediaH = (h * when { tall -> 0.56f; hasMedia -> 0.42f; else -> 0.26f }).toInt()

        // ---- top: picture, video, or the section's colours ----
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pg.addView(stage, FrameLayout.LayoutParams(-1, mediaH + dp(22), Gravity.TOP))   // goes a little under the card's round corners
        val area = FrameLayout(this)
        stage.addView(area, FrameLayout.LayoutParams(-1, mediaH, Gravity.TOP))
        var yt: FrameLayout? = null
        var vv: VideoView? = null; var poster: ImageView? = null; var sign: TextView? = null
        when {
            p.ytId.isNotBlank() -> {
                val box = Box169(this).apply { setBackgroundColor(Color.BLACK); contentDescription = "Play video" }
                box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load("https://i.ytimg.com/vi/${p.ytId}/hqdefault.jpg", this, 640) },
                    FrameLayout.LayoutParams(-1, -1))
                box.addView(TextView(this).apply { text = "▶"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#E62117"), 14) },
                    FrameLayout.LayoutParams(dp(70), dp(48), Gravity.CENTER))
                box.setOnClickListener { play(k) }
                area.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { topMargin = insetTop + dp(50) })
                yt = box
            }
            p.videoUrl.isNotBlank() -> {
                val pv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; Images.load(host?.abs(p.videoPoster) ?: p.videoPoster, this, 1080) }
                area.addView(pv, FrameLayout.LayoutParams(-1, -1))
                val vid = VideoView(this).apply { contentDescription = "Video" }
                area.addView(vid, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                val sg = TextView(this).apply {
                    text = "▶"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#88000000")) }
                }
                area.addView(sg, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
                // tap: pause or play again
                area.setOnClickListener { if (vid.isPlaying) { vid.pause(); sg.visibility = View.VISIBLE } else if (fileAt == k) { vid.start(); sg.visibility = View.GONE } else playFile(k) }
                poster = pv; vv = vid; sign = sg
            }
            pic.isNotBlank() -> {
                area.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "Picture. Tap to see it full screen"
                    Images.load(pic, this, 1080); setOnClickListener { openPictures(p) }
                }, FrameLayout.LayoutParams(-1, -1))
                if (p.photos.size > 1) area.addView(TextView(this).apply {
                    text = "📷 1 / ${p.photos.size}"; textSize = 12.5f; setTextColor(Color.WHITE); setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = ui.round(Color.parseColor("#99000000"), 10)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(12)) })
            }
            else -> {
                val c = Color.parseColor(CAT_COLOR[p.cat] ?: "#19A39A")
                area.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.TEAL_DARK, c))
                area.addView(TextView(this).apply { text = p.emoji.ifBlank { p.author.emoji.ifBlank { "🐾" } }; textSize = 46f; gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(-1, -1).apply { topMargin = insetTop })
            }
        }
        if (p.author.official && CAT.containsKey(p.cat) && p.ytId.isBlank()) area.addView(TextView(this).apply {
            text = CAT[p.cat]; textSize = 12.5f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4)); background = ui.round(Color.parseColor(CAT_COLOR[p.cat] ?: "#19A39A"), 10)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(12)) })

        // ---- the card: who, headline, the text in its own scrolling box, buttons ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); val r = dp(22).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(dp(16), dp(14), dp(16), insetBottom + dp(4))
        }
        pg.addView(card, FrameLayout.LayoutParams(-1, h - mediaH, Gravity.BOTTOM))

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Open ${p.author.name}'s profile"
            setOnClickListener { host?.profile(p); finish() }
        }
        meta.addView(avatar(p.author), LinearLayout.LayoutParams(dp(28), dp(28)))
        meta.addView(ui.text(p.author.name, 14f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        if (p.author.verified) meta.addView(TextView(this).apply {
            text = "✓"; textSize = 9.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BLUE) }
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(4) })
        meta.addView(ui.text(" · " + ago(p.at) + (if (p.views > 0) "  ·  👁 ${num(p.views)}" else ""), 13f, Ui.MUTED).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(meta)

        if (p.title.isNotBlank()) card.addView(ui.text(p.title, 20f, Ui.INK, bold = true).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(16)) }
        fun line(v: View, top: Int = 10) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (body.childCount > 0) topMargin = dp(top) })
        if (p.text.isNotBlank()) line(ui.text(p.text, if (p.title.isBlank()) 17.5f else 16.5f, Ui.INK).apply { setLineSpacing(0f, 1.3f) })
        if (p.ytId.isNotBlank()) {
            val ch = p.ytCh.removePrefix("YouTube · ").removePrefix("YouTube").trim()
            if (p.linkTitle.isNotBlank()) line(ui.text("▶ " + p.linkTitle, 15f, Ui.INK, bold = true))
            line(ui.text("YouTube" + (if (ch.isNotBlank()) " · $ch" else "") + "  ↗", 13.5f, BLUE).apply {
                setOnClickListener { stopVideo(); openOut("https://www.youtube.com/watch?v=${p.ytId}") }
            }, 6)
        } else if (p.linkUrl.isNotBlank()) {
            val site = p.linkSite.ifBlank { hostOf(p.linkUrl) }
            line(ui.button(if (p.author.official) "📖 పూర్తి వార్త చదవండి · Read the full story ↗" else "🔗 " + p.linkTitle.ifBlank { site }.take(60) + " ↗", "soft") {
                openOut(p.linkUrl)
            }, 14)
            if (site.isNotBlank()) line(ui.text("🔗 $site", 12f, Ui.MUTED), 4)
        }
        if (p.sourceUrl.isNotBlank() && p.sourceUrl != p.linkUrl)
            line(ui.text("మూలం · Source: ${p.sourceName.ifBlank { hostOf(p.sourceUrl) }} ↗", 13.5f, BLUE).apply { setOnClickListener { openOut(p.sourceUrl) } }, 12)
        if (p.author.official) {
            when (p.cat) {
                "crypto", "realestate" -> line(ui.text("ఆర్థిక సలహా కాదు · Not financial advice", 12f, Ui.MUTED), 10)
                "health" -> line(ui.text("వైద్య సలహా కాదు · Not medical advice", 12f, Ui.MUTED), 10)
            }
            if (CAT.containsKey(p.cat)) line(ui.text("🤖 AI రాసిన సారాంశం · Short summary written by AI. Check the source for full details.", 12f, Ui.MUTED), 10)
        }
        if (body.childCount == 0) line(ui.text(" ", 14f, Ui.MUTED))
        val box = ScrollView(this).apply {
            background = ui.round(BOX, 16); clipToOutline = true
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(28))
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        card.addView(box, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val likeV = act(likeLabel(p), if (p.liked) PINK else Ui.MUTED) { }
        likeV.contentDescription = "Like"
        likeV.setOnClickListener { host?.like(p) { paintLike(p, likeV) } }
        val listenV = act(if (speakingKey == p.key) "⏹" else "🔊") { }
        listenV.contentDescription = "Listen"
        listenV.setOnClickListener { listen(p, listenV) }
        listOfNotNull(likeV, act(counted("💬", p.comments)) { host?.comments(p); finish() }.apply { contentDescription = "Comments" },
            listenV, act("📤") { share(p) }.apply { contentDescription = "Share" },
            if (p.reel.isNotBlank()) act("🎬", PINK) { shareVideo(p) }.apply { contentDescription = "Share as a video (WhatsApp status…)" } else null,
            act("⋯") { menu(p) }.apply { contentDescription = "More" })
            .forEach { acts.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        card.addView(acts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        pg.tag = Holder(area, box, likeV, listenV).apply { this.yt = yt; video = vv; this.poster = poster; this.sign = sign }
    }

    /** The "you're all caught up" page at the end (or "loading" while more posts come). */
    private fun buildEnd() {
        endPage.removeAllViews()
        endPage.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@StoryReader).apply { text = if (asking) "⏳" else "🐾"; textSize = 48f; gravity = Gravity.CENTER })
            addView(TextView(this@StoryReader).apply {
                text = if (asking) "ఇంకా పోస్టులు వస్తున్నాయి… · Loading more…" else "అంతే! · You're all caught up"
                textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            if (!asking) addView(ui.button("⬅  Back to Media", "sun") { finish() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun askMore() {
        if (asking || noMore) return
        val h = host ?: return
        asking = true; buildEnd()
        h.more { got, end ->
            asking = false
            if (end) noMore = true
            val fresh = got.filter { g -> list.none { it.key == g.key } }
            if (fresh.isNotEmpty()) {
                val ph = pageHeight()
                fresh.forEach { list.add(it); addPage(ph) }
                if (at >= 0) show(at)
            }
            buildEnd()
        }
    }

    /** True when the finger went down on the current page's text box and the text can still scroll that way. */
    private fun textCanScroll(x: Float, y: Float, fingerUp: Boolean): Boolean {
        val box = (pages.getOrNull(at)?.tag as? Holder)?.text ?: return false
        box.getLocationOnScreen(spot)
        if (x < spot[0] || x > spot[0] + box.width || y < spot[1] || y > spot[1] + box.height) return false
        return box.canScrollVertically(if (fingerUp) 1 else -1)
    }

    // ============================== small parts ==============================
    private fun avatar(u: SUser): View {
        val f = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (u.official) Color.parseColor("#E6F0FF") else Ui.TEAL_SOFT) }
            clipToOutline = true
        }
        if (u.avatar.isNotBlank()) f.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load(host?.abs(u.avatar) ?: u.avatar, this, 160) },
            FrameLayout.LayoutParams(-1, -1))
        else f.addView(TextView(this).apply { text = u.emoji.ifBlank { "🐾" }; textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false }, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun act(label: String, color: Int = Ui.MUTED, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14.5f; setTextColor(color); gravity = Gravity.CENTER
        setPadding(dp(2), dp(11), dp(2), dp(11))
        setOnClickListener { onClick() }
    }

    private fun num(n: Int) = when {
        n < 1000 -> "$n"
        n < 1_000_000 -> String.format(Locale.ENGLISH, "%.1fK", n / 1000f).replace(".0K", "K")
        else -> String.format(Locale.ENGLISH, "%.1fM", n / 1_000_000f).replace(".0M", "M")
    }
    private fun counted(icon: String, n: Int) = if (n > 0) "$icon ${num(n)}" else icon
    private fun likeLabel(p: SPost) = counted(if (p.liked) "❤️" else "🤍", p.likes)
    private fun paintLike(p: SPost, v: TextView) { v.text = likeLabel(p); v.setTextColor(if (p.liked) PINK else Ui.MUTED) }

    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; s < 7 * 86400 -> "${s / 86400}d"
            else -> DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        }
    }

    private fun hostOf(u: String) = try { Uri.parse(u).host?.removePrefix("www.")?.removePrefix("m.") ?: "" } catch (e: Exception) { "" }

    // ============================== buttons ==============================
    private fun listen(p: SPost, v: TextView) {
        val h = host ?: return
        if (speakingKey == p.key) { stopListening(); return }
        if (speakingKey.isNotEmpty()) stopListening()
        speakingKey = p.key; v.text = "⏹"
        h.speak(listOf(p.title, p.text).filter { it.isNotBlank() }.joinToString(". ")) {
            if (speakingKey == p.key) { speakingKey = ""; v.text = "🔊" }
        }
    }

    private fun stopListening() {
        val key = speakingKey
        speakingKey = ""
        host?.stopSpeaking()
        val k = list.indexOfFirst { it.key == key }
        (pages.getOrNull(k)?.tag as? Holder)?.listen?.text = "🔊"
    }

    private fun share(p: SPost) {
        val t = host?.shareText(p) ?: return
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")) } catch (e: Exception) { }
    }

    /** The short video of this news (made on the server): download it, then share it to WhatsApp, status, anywhere. */
    private fun shareVideo(p: SPost) {
        val url = host?.abs(p.reel) ?: return
        android.widget.Toast.makeText(this, "🎬 Getting the video ready…", android.widget.Toast.LENGTH_SHORT).show()
        val name = "BigD-" + p.key.filter { it.isLetterOrDigit() } + ".mp4"
        Thread {
            val f = ShareFiles.download(this, url, name)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (f == null) { android.widget.Toast.makeText(this, "Couldn't get the video. Please try again.", android.widget.Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val uri = ShareFiles.uri(this, name)
                val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TEXT, (p.title.ifBlank { "BigD AI" }) + "\n📲 BigD AI · bigdai.tech").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = android.content.ClipData.newRawUri("video", uri)
                try { startActivity(Intent.createChooser(send, "Share the video").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { }
            }
        }.start()
    }

    private fun menu(p: SPost) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("👤 Open profile" to { host?.profile(p); finish() })
        items.add("💬 Comments" to { host?.comments(p); finish() })
        val link = p.linkUrl.ifBlank { p.sourceUrl }
        if (link.isNotBlank()) items.add("🔗 Open the link" to { openOut(link) })
        if (!p.mine) items.add("🚩 Report post" to { report(p) })
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun report(p: SPost) {
        val reasons = arrayOf("Bad words or insults", "Adult or violent content", "Harm to a child", "Spam, ads or a scam", "Fake news", "Personal details of someone", "Something else")
        AlertDialog.Builder(this).setTitle("Why are you reporting it?").setItems(reasons) { _, i -> host?.report(p, reasons[i]) }.show()
    }

    /** The post's photos (or its link picture), full screen with zoom. */
    private fun openPictures(p: SPost) {
        val urls = if (p.photos.isNotEmpty()) p.photos.map { host?.abs(it) ?: it } else listOf(p.linkImage).filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val words = listOf(p.title, p.text).filter { it.isNotBlank() }.joinToString("\n")
        MediaViewer.items = urls.map { MediaViewer.Item("photo", it, "", p.author.name, words, 0, false) }
        MediaViewer.start = 0; MediaViewer.server = server; MediaViewer.onLike = null; MediaViewer.onComments = null
        startActivity(Intent(this, MediaViewer::class.java))
    }

    private fun openOut(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { }
    }

    // ============================== videos ==============================
    private fun makeWeb() = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val u = request.url.toString()
                if (u.startsWith("about:")) return false
                openOut(u); return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { showFull(view, callback) }
            override fun onHideCustomView() { hideFull(false) }
        }
    }

    /** YouTube video of page [k], played at the top of the page. */
    private fun play(k: Int) {
        if (k != at) return
        val p = list.getOrNull(k) ?: return
        val box = (pages[k].tag as? Holder)?.yt ?: return
        val w = web ?: makeWeb().also { web = it }
        if (webAt == k && w.parent === box) return
        (w.parent as? ViewGroup)?.removeView(w)
        box.addView(w, FrameLayout.LayoutParams(-1, -1))
        w.onResume()
        val id = p.ytId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(11)
        val origin = server.trimEnd('/')
        val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head><body>" +
            "<iframe src=\"https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&origin=$origin\" " +
            "allow=\"autoplay; encrypted-media; picture-in-picture; fullscreen\" allowfullscreen></iframe></body></html>"
        w.loadDataWithBaseURL("$origin/", html, "text/html", "utf-8", null)
        webAt = k
    }

    private fun stopVideo() {
        if (full != null) hideFull(true)
        val w = web ?: return
        w.loadUrl("about:blank")   // stops the sound at once
        (w.parent as? ViewGroup)?.removeView(w)
        webAt = -1
    }

    /** A video posted from a phone, played at the top of page [k] (looping). */
    private fun playFile(k: Int) {
        if (k != at || fileAt == k) return
        val hd = pages[k].tag as? Holder ?: return
        val vv = hd.video ?: return
        val p = list[k]
        fileAt = k
        hd.sign?.visibility = View.GONE
        vv.setOnPreparedListener { mp -> mp.isLooping = true; if (fileAt == k) { vv.start(); hd.poster?.visibility = View.INVISIBLE } }
        vv.setOnErrorListener { _, _, _ -> if (fileAt == k) { hd.sign?.text = "⚠"; hd.sign?.visibility = View.VISIBLE; hd.poster?.visibility = View.VISIBLE }; true }
        vv.setVideoURI(Uri.parse(host?.abs(p.videoUrl) ?: p.videoUrl))
    }

    private fun stopFile() {
        val k = fileAt; fileAt = -1
        val hd = pages.getOrNull(k)?.tag as? Holder ?: return
        try { hd.video?.stopPlayback() } catch (e: Exception) { }
        hd.poster?.visibility = View.VISIBLE
    }

    private fun showFull(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (full != null) { cb.onCustomViewHidden(); return }
        full = v; fullCb = cb
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        topBar.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        catchBack(true)
    }

    private fun hideFull(tellWeb: Boolean) {
        val v = full ?: return
        full = null
        root.removeView(v)
        if (tellWeb) try { fullCb?.onCustomViewHidden() } catch (e: Exception) { }
        fullCb = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        topBar.visibility = View.VISIBLE
        catchBack(false)
    }

    /** While a video is full screen, Back leaves full screen instead of closing the reader (Android 13+ way). */
    private fun catchBack(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        if (on && backCb == null) {
            val cb = android.window.OnBackInvokedCallback { hideFull(true) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        } else if (!on) {
            (backCb as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCb = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (full != null) hideFull(true) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(seenTask)
        if (webAt >= 0) stopVideo()
        if (fileAt >= 0) stopFile()
        if (speakingKey.isNotEmpty()) stopListening()
        handler.removeCallbacks(autoTask)
    }

    override fun onResume() {
        super.onResume()
        val p = list.getOrNull(at) ?: return
        if (p.videoUrl.isNotBlank()) playFile(at)
        if (auto) handler.postDelayed(autoTask, 600)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); try { w.destroy() } catch (e: Exception) { } }
        web = null
        if (isFinishing) { posts = emptyList(); host = null }
        super.onDestroy()
    }
}
