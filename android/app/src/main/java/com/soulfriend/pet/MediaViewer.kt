package com.soulfriend.pet

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
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

/**
 * Full-screen photos and videos from Media, like X or Instagram: swipe up or down to go through all of them,
 * pinch or double-tap a photo to zoom, and YouTube videos play right here.
 */
class MediaViewer : Activity() {
    /** kind: "photo" (src = picture), "video" (src = YouTube id) or "file" (src = a video posted from a phone, with its [poster]). */
    class Item(val kind: String, val src: String, val key: String, val who: String, val words: String, var likes: Int, var liked: Boolean, val poster: String = "")

    companion object {
        /** What to show (set just before opening). */
        var items: List<Item> = emptyList()
        var start = 0
        var server = "https://bigdai.tech"
        var onLike: ((String) -> Unit)? = null
        var onComments: ((String) -> Unit)? = null
    }

    private lateinit var ui: Ui
    private fun dp(v: Int) = ui.dp(v)
    private lateinit var root: FrameLayout
    private lateinit var pager: SnapPager
    private val pages = ArrayList<FrameLayout>()
    private var at = -1
    private var web: WebView? = null
    private var webAt = -1
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var count: TextView
    private lateinit var who: TextView
    private lateinit var words: TextView
    private lateinit var likeBtn: TextView
    private lateinit var talkBtn: TextView
    private lateinit var ytBtn: TextView
    private var chrome = true
    private var full: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var backCb: Any? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (items.isEmpty()) { finish(); return }
        ui = Ui(this)
        edgeToEdge()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pager = SnapPager(this)
        val h0 = resources.displayMetrics.heightPixels
        for (i in items.indices) { val pg = FrameLayout(this); pages.add(pg); pager.box.addView(pg, LinearLayout.LayoutParams(-1, h0)) }
        pager.onPage = { show(it) }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        // top: close, and "3 / 12"
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT))
        }
        topBar.addView(circle("✕", "Close") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        count = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(12), dp(6), dp(12), dp(6)); background = ui.round(Color.parseColor("#66000000"), 16) }
        topBar.addView(count)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom: who posted it, the words, and buttons
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT))
        }
        who = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        words = TextView(this).apply {
            setTextColor(Color.parseColor("#EDEDED")); textSize = 14f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f)
            setOnClickListener { maxLines = if (maxLines == 3) 14 else 3 }
        }
        bottomBar.addView(who)
        bottomBar.addView(words, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        likeBtn = pill("🤍") { like() }
        acts.addView(likeBtn)
        talkBtn = pill("💬") { comments() }
        acts.addView(talkBtn, gap())
        acts.addView(pill("📤") { share() }, gap())
        ytBtn = pill("YouTube ↗") { openYouTube() }
        acts.addView(ytBtn, gap())
        bottomBar.addView(acts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        if (items.size > 1) {
            val hint = TextView(this).apply {
                text = "⬆  పైకి స్వైప్ చేయండి · Swipe up for more"; setTextColor(Color.WHITE); textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8)); background = ui.round(Color.parseColor("#88000000"), 18)
            }
            root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            hint.animate().alpha(0f).setStartDelay(2200).setDuration(700).withEndAction { root.removeView(hint) }.start()
        }

        root.setOnApplyWindowInsetsListener { _, ins -> pad(ins); ins }
        setContentView(root)
        pager.jump(start.coerceIn(0, items.size - 1))
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Suppress("DEPRECATION")
    private fun pad(ins: WindowInsets) {
        val top: Int; val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) { val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); top = s.top; bottom = s.bottom }
        else { top = ins.systemWindowInsetTop; bottom = ins.systemWindowInsetBottom }
        topBar.setPadding(dp(10), top + dp(6), dp(12), dp(18))
        bottomBar.setPadding(dp(16), dp(40), dp(16), bottom + dp(16))
    }

    private fun circle(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; contentDescription = desc
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#66000000")) }
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = ui.round(Color.parseColor("#33FFFFFF"), 18)
        setOnClickListener { onClick() }
    }

    private fun gap() = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }

    // ---------- pages ----------
    private fun show(i: Int) {
        val prev = at
        at = i
        if (webAt >= 0 && webAt != i) stopVideo()
        if (fileAt >= 0 && fileAt != i) stopFile()
        if (prev >= 0 && prev != i) (pages.getOrNull(prev)?.getChildAt(0) as? ZoomImageView)?.reset()
        pager.locked = false
        for (k in pages.indices) { if (k >= i - 1 && k <= i + 1) fill(k) else if (k < i - 2 || k > i + 2) clear(k) }
        val cur = items[i]
        count.text = "${i + 1} / ${items.size}"
        count.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        who.text = cur.who
        words.text = cur.words; words.maxLines = 3; words.visibility = if (cur.words.isBlank()) View.GONE else View.VISIBLE
        val canAct = cur.key.isNotBlank() && onLike != null
        likeBtn.visibility = if (canAct) View.VISIBLE else View.GONE
        talkBtn.visibility = if (canAct && onComments != null) View.VISIBLE else View.GONE
        paintLike()
        ytBtn.visibility = if (cur.kind == "video" || cur.kind == "short") View.VISIBLE else View.GONE
        if (cur.kind == "video" || cur.kind == "short") play(i)
        if (cur.kind == "file") playFile(i)
    }

    private fun fill(k: Int) {
        val pg = pages[k]
        if (pg.childCount > 0) return
        val item = items[k]
        if (item.kind == "photo") {
            val z = ZoomImageView(this, { on -> pager.locked = on }, { toggleChrome() })
            z.contentDescription = "Photo"
            pg.addView(z, FrameLayout.LayoutParams(-1, -1))
            Images.load(item.src, z, 1600)
        } else if (item.kind == "file") {
            val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; Images.load(item.poster, this, 1080) }
            pg.addView(poster, FrameLayout.LayoutParams(-1, -1))
            val vv = VideoView(this).apply { contentDescription = "Video" }
            pg.addView(vv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            val sign = TextView(this).apply {
                text = "▶"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#88000000")) }
            }
            pg.addView(sign, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            pg.tag = Triple(poster, vv, sign)
            pg.setOnClickListener {
                // tap: pause or play again
                if (vv.isPlaying) { vv.pause(); sign.visibility = View.VISIBLE } else if (fileAt == k) { vv.start(); sign.visibility = View.GONE } else playFile(k)
            }
        } else if (item.kind == "short") {
            // a YouTube Short: tall, the whole screen (like Reels)
            val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription = "Play video" }
            box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load("https://i.ytimg.com/vi/${item.src}/hqdefault.jpg", this, 720) },
                FrameLayout.LayoutParams(-1, -1))
            box.addView(TextView(this).apply { text = "▶"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#E62117"), 16) },
                FrameLayout.LayoutParams(dp(76), dp(52), Gravity.CENTER))
            box.setOnClickListener { play(k) }
            pg.addView(box, FrameLayout.LayoutParams(-1, -1))
        } else {
            val box = Box169(this).apply { setBackgroundColor(Color.BLACK); contentDescription = "Play video" }
            box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load("https://i.ytimg.com/vi/${item.src}/hqdefault.jpg", this, 640) },
                FrameLayout.LayoutParams(-1, -1))
            box.addView(TextView(this).apply { text = "▶"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ui.round(Color.parseColor("#E62117"), 16) },
                FrameLayout.LayoutParams(dp(76), dp(52), Gravity.CENTER))
            box.setOnClickListener { play(k) }
            pg.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            pg.setOnClickListener { toggleChrome() }
        }
    }

    private fun clear(k: Int) {
        val pg = pages[k]
        if (pg.childCount == 0) return
        if (webAt == k) stopVideo()
        if (fileAt == k) stopFile()
        pg.tag = null
        pg.removeAllViews(); pg.setOnClickListener(null)
    }

    private fun toggleChrome() { chrome = !chrome; setChrome(chrome) }

    private fun setChrome(on: Boolean) {
        for (v in listOf<View>(topBar, bottomBar)) {
            v.animate().cancel()
            if (on) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(160).start() }
            else v.animate().alpha(0f).setDuration(160).withEndAction { if (!chrome) v.visibility = View.GONE }.start()
        }
    }

    // ---------- videos (YouTube, played here) ----------
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

    private fun play(k: Int) {
        if (k != at) return
        val item = items[k]
        if (item.kind != "video" && item.kind != "short") return
        val box = pages[k].getChildAt(0) as? FrameLayout ?: return
        val w = web ?: makeWeb().also { web = it }
        if (webAt == k && w.parent === box) return
        (w.parent as? ViewGroup)?.removeView(w)
        box.addView(w, FrameLayout.LayoutParams(-1, -1))
        w.onResume()
        val id = item.src.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(11)
        val origin = server.trimEnd('/')
        val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head><body>" +
            "<iframe src=\"https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=$origin\" " +
            "allow=\"autoplay; encrypted-media; picture-in-picture; fullscreen\" allowfullscreen></iframe></body></html>"
        w.loadDataWithBaseURL("$origin/", html, "text/html", "utf-8", null)
        webAt = k
    }

    // ---------- videos posted from phones (played here, looping) ----------
    private var fileAt = -1

    @Suppress("UNCHECKED_CAST")
    private fun playFile(k: Int) {
        if (k != at) return
        val parts = pages[k].tag as? Triple<ImageView, VideoView, TextView> ?: return
        val (poster, vv, sign) = parts
        if (fileAt == k) return   // already started
        fileAt = k
        sign.visibility = View.GONE
        vv.setOnPreparedListener { mp -> mp.isLooping = true; if (fileAt == k) { vv.start(); poster.visibility = View.INVISIBLE } }
        vv.setOnErrorListener { _, _, _ -> if (fileAt == k) { sign.text = "⚠"; sign.visibility = View.VISIBLE; poster.visibility = View.VISIBLE }; true }
        vv.setVideoURI(Uri.parse(items[k].src))
    }

    @Suppress("UNCHECKED_CAST")
    private fun stopFile() {
        val k = fileAt; fileAt = -1
        val parts = pages.getOrNull(k)?.tag as? Triple<ImageView, VideoView, TextView> ?: return
        try { parts.second.stopPlayback() } catch (e: Exception) { }
        parts.first.visibility = View.VISIBLE
    }

    private fun stopVideo() {
        if (full != null) hideFull(true)
        if (wide) { wide = false; bars(true); pager.locked = false; chrome = true; setChrome(true) }
        val w = web ?: return
        w.loadUrl("about:blank")   // stops the sound at once
        (w.parent as? ViewGroup)?.removeView(w)
        webAt = -1
    }

    private fun showFull(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (full != null) { cb.onCustomViewHidden(); return }
        full = v; fullCb = cb
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        topBar.visibility = View.GONE; bottomBar.visibility = View.GONE
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
        pager.realign(); keepPlaying()
        chrome = true; setChrome(true)
        catchBack(false)
    }

    /** While a video is full screen, Back leaves full screen instead of closing the viewer (Android 13+ way). */
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

    // ---------- turning the phone sideways: the playing video fills the screen; turn it back for the page ----------
    private var wide = false
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        pager.realign()                           // the page exactly on the screen again
        if (full != null) return                  // YouTube's own full screen is on
        val w = web
        if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (wide || w == null || webAt < 0 || w.parent == null) return
            wide = true
            (w.parent as? ViewGroup)?.removeView(w)
            root.addView(w, FrameLayout.LayoutParams(-1, -1))
            topBar.visibility = View.GONE; bottomBar.visibility = View.GONE
            pager.locked = true; bars(false); keepPlaying()
        } else if (wide) {
            wide = false
            if (w != null && w.parent === root) { root.removeView(w); (pages.getOrNull(webAt)?.getChildAt(0) as? FrameLayout)?.addView(w, FrameLayout.LayoutParams(-1, -1)) }
            pager.locked = false; bars(true); chrome = true; setChrome(true); keepPlaying()
        }
    }

    /** Keeps the video playing after full screen or turning the phone (YouTube's player takes this message). */
    private fun keepPlaying() {
        web?.postDelayed({ web?.evaluateJavascript("(function(){var f=document.querySelector('iframe');if(f&&f.contentWindow)f.contentWindow.postMessage(JSON.stringify({event:'command',func:'playVideo',args:''}),'*');})()", null) }, 700)
    }

    /** Hides or shows the phone's own bars (time, battery; back and home). */
    @Suppress("DEPRECATION")
    private fun bars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let { c ->
            if (show) c.show(WindowInsets.Type.systemBars())
            else { c.hide(WindowInsets.Type.systemBars()); c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else window.decorView.systemUiVisibility = if (show) View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            else View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ---------- buttons ----------
    private fun paintLike() {
        val cur = items.getOrNull(at) ?: return
        likeBtn.text = (if (cur.liked) "❤️" else "🤍") + (if (cur.likes > 0) " ${cur.likes}" else "")
    }

    private fun like() {
        val cur = items.getOrNull(at) ?: return
        if (cur.key.isBlank()) return
        val now = !cur.liked
        items.filter { it.key == cur.key }.forEach { it.liked = now; it.likes = maxOf(0, it.likes + if (now) 1 else -1) }
        paintLike()
        onLike?.invoke(cur.key)
    }

    private fun comments() {
        val cur = items.getOrNull(at) ?: return
        if (cur.key.isBlank()) return
        onComments?.invoke(cur.key)
        finish()
    }

    private fun share() {
        val cur = items.getOrNull(at) ?: return
        val link = when (cur.kind) { "video" -> "https://youtu.be/${cur.src}"; "short" -> "https://youtube.com/shorts/${cur.src}"; else -> cur.src }
        val t = listOf(cur.words.take(1200), link, "— ${cur.who.substringBefore("  @")} · BigD AI 🐾 $server").filter { it.isNotBlank() }.joinToString("\n\n")
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")) } catch (e: Exception) { }
    }

    private fun openYouTube() {
        val cur = items.getOrNull(at) ?: return
        if (cur.kind == "video" || cur.kind == "short") { stopVideo(); openOut(if (cur.kind == "short") "https://www.youtube.com/shorts/${cur.src}" else "https://www.youtube.com/watch?v=${cur.src}") }
    }

    private fun openOut(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { }
    }

    override fun onPause() {
        super.onPause()
        if (webAt >= 0) stopVideo()
        if (fileAt >= 0) stopFile()
    }

    override fun onResume() {
        super.onResume()
        if (at >= 0 && items.getOrNull(at)?.kind == "file") playFile(at)
    }

    override fun onDestroy() {
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); try { w.destroy() } catch (e: Exception) { } }
        web = null
        if (isFinishing) { items = emptyList(); onLike = null; onComments = null }
        super.onDestroy()
    }
}

/** Vertical pages, each one full screen; a swipe moves to the next or the previous page. */
internal class SnapPager(ctx: Context) : ScrollView(ctx) {
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    var page = 0
        private set
    /** True while a zoomed photo (or a pinch) is using the touches. */
    var locked = false
    var onPage: ((Int) -> Unit)? = null
    /** Asked once per drag, with where the finger went down (screen x, y) and whether it moves up:
     *  true = a box inside the page scrolls its own text, so the page stays (the story reader uses this). */
    var childScrolls: ((Float, Float, Boolean) -> Boolean)? = null
    private var childOwns = false
    private var decided = false
    private var downRawX = 0f
    private var downRawY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var startPage = 0
    private var flung = false
    private var anim: ObjectAnimator? = null
    private var downX = 0f
    private var downY = 0f

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(box, LayoutParams(-1, -2))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (h <= 0 || h == oh) return
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i); val lp = c.layoutParams
            if (lp.height != h) { lp.height = h; c.layoutParams = lp }
        }
        pendingPage = page
        post { if (pendingPage < 0) return@post; requestLayout() }
    }

    /** The page to scroll to as soon as the pages are laid out at their real size. */
    private var pendingPage = -1

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val p = pendingPage
        if (p >= 0 && height > 0 && box.height >= (p + 1) * height) {
            pendingPage = -1
            anim?.cancel(); scrollTo(0, p * height)
        }
    }

    /** A video inside a page must not stop the swipe; a zoomed photo uses [locked] instead. */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    /** A video (or anything) getting focus must never scroll the pages: they move only by swiping. */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?): Int = 0

    /** Puts the current page exactly on the screen again (after full screen or turning the phone). */
    fun realign() { pendingPage = page; post { if (pendingPage >= 0) { requestLayout(); if (height > 0 && box.height >= (page + 1) * height) { pendingPage = -1; scrollTo(0, page * height) } } } }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { begin(); downX = e.x; downY = e.y; downRawX = e.rawX; downRawY = e.rawY; childOwns = false; decided = false }
            MotionEvent.ACTION_MOVE -> {
                if (childOwns) return false
                if (Math.abs(e.x - downX) > Math.abs(e.y - downY)) return false
                if (!decided && Math.abs(e.y - downY) > slop) {
                    decided = true
                    if (childScrolls?.invoke(downRawX, downRawY, e.y < downY) == true) { childOwns = true; return false }
                }
            }
        }
        if (locked || e.pointerCount > 1) return false
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) begin()
        if (locked) return false
        val r = super.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && !flung && height > 0) settle(Math.round(scrollY.toFloat() / height))
        return r
    }

    private fun begin() { anim?.cancel(); anim = null; startPage = page; flung = false; pendingPage = -1 }

    override fun fling(velocityY: Int) { flung = true; settle(if (velocityY > 0) startPage + 1 else startPage - 1) }

    private fun settle(p: Int) {
        if (height <= 0 || box.childCount == 0) return
        val t = p.coerceIn(maxOf(0, startPage - 1), minOf(box.childCount - 1, startPage + 1))
        anim?.cancel()
        anim = ObjectAnimator.ofInt(this, "scrollY", scrollY, t * height).apply { duration = 230; interpolator = DecelerateInterpolator(1.5f); start() }
        if (t != page) { page = t; onPage?.invoke(t) }
    }

    /** Slides to the next page, as if swiped (news reels). False at the last page. */
    fun next(): Boolean {
        if (height <= 0 || page + 1 >= box.childCount) return false
        anim?.cancel(); startPage = page; settle(page + 1)
        return true
    }

    /** Opens page [p] straight away (no animation). */
    fun jump(p: Int) {
        page = p; startPage = p
        pendingPage = p
        if (height > 0) scrollTo(0, p * height)
        requestLayout()
        onPage?.invoke(p)
    }
}

/** A photo you can pinch or double-tap to zoom; when zoomed in, drag to look around. */
private class ZoomImageView(ctx: Context, private val onLock: (Boolean) -> Unit, private val onTap: () -> Unit) : ImageView(ctx) {
    private val user = Matrix()
    private var zoom = 1f
    private val zoomed: Boolean get() = zoom > 1.02f

    init { scaleType = ScaleType.MATRIX }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { onLock(true); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nz = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            val k = nz / zoom
            if (k != 1f) { user.postScale(k, k, detector.focusX, detector.focusY); zoom = nz; fit() }
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { if (zoomed) onLock(true) else reset() }
    })

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed) reset() else { user.postScale(2.5f, 2.5f, e.x, e.y); zoom = 2.5f; fit(); onLock(true) }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!zoomed) return false
            user.postTranslate(-distanceX, -distanceY); fit(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) taps.onTouchEvent(event)
        return true
    }

    /** Fits the photo in the screen, then applies the zoom, keeping the photo on the screen. */
    private fun fit() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f || width == 0 || height == 0) return
        val s = minOf(width / dw, height / dh)
        val mtx = Matrix()
        mtx.setScale(s, s); mtx.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        mtx.postConcat(user)
        val r = RectF(0f, 0f, dw, dh); mtx.mapRect(r)
        val dx = when { r.width() <= width -> (width - r.width()) / 2f - r.left; r.left > 0f -> -r.left; r.right < width -> width - r.right; else -> 0f }
        val dy = when { r.height() <= height -> (height - r.height()) / 2f - r.top; r.top > 0f -> -r.top; r.bottom < height -> height - r.bottom; else -> 0f }
        if (dx != 0f || dy != 0f) { user.postTranslate(dx, dy); mtx.postTranslate(dx, dy) }
        imageMatrix = mtx
    }

    fun reset() { user.reset(); zoom = 1f; fit(); onLock(false) }

    override fun setImageBitmap(bm: Bitmap?) { super.setImageBitmap(bm); user.reset(); zoom = 1f; fit() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); user.reset(); zoom = 1f; fit() }
}

/** A 16:9 box for videos, as wide as it can be while still fitting. */
internal class Box169(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = MeasureSpec.getSize(widthMeasureSpec)
        var h = w * 9 / 16
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && maxH in 1 until h) { h = maxH; w = h * 16 / 9 }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
