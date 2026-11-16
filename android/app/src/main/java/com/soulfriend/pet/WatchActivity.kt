package com.soulfriend.pet

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A long YouTube video, the way YouTube shows it: the video at the top (YouTube's own player, so its bar, double-tap
 * and full screen work), its name and a row of small buttons under it, and under those the other videos (the list it
 * came from, then more from the same channel). Tap one and it plays here; Back goes to the video before, and after the
 * first one to the page it was opened from.
 */
class WatchActivity : Activity() {
    companion object {
        var items: List<MediaViewer.Item> = emptyList()
        var start = 0
        var server = "https://bigdai.tech"
    }

    private lateinit var ui: Ui
    private fun dp(v: Int) = ui.dp(v)
    private val prefs by lazy { Store(this) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var outer: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var videoBox: Box169
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private var web: WebView? = null
    private var list: List<MediaViewer.Item> = emptyList()      // the list it was opened from
    private var more: List<MediaViewer.Item> = emptyList()      // more from the playing video's channel
    private var moreFor = ""
    private lateinit var cur: MediaViewer.Item
    private val before = ArrayList<MediaViewer.Item>()          // the videos watched before this one (Back goes through them)
    private var now = 0.0
    private var length = 0.0
    private var counted = false
    private var endedFor = ""
    private var full: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var backCb: Any? = null
    private var wide = false
    private var followChip: TextView? = null

    private val BG = Color.parseColor("#0F0F0F")
    private val GREY = Color.parseColor("#AAAAAA")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        list = items.filter { it.kind == "video" && it.src.isNotBlank() }
        if (list.isEmpty()) { finish(); return }
        ui = Ui(this)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = BG
        outer = FrameLayout(this).apply { setBackgroundColor(BG) }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        videoBox = Box169(this).apply { setBackgroundColor(Color.BLACK) }
        column.addView(videoBox, LinearLayout.LayoutParams(-1, -2))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body, ViewGroup.LayoutParams(-1, -2)) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(column, FrameLayout.LayoutParams(-1, -1))
        outer.setOnApplyWindowInsetsListener { _, ins -> pad(ins); ins }
        setContentView(outer)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = android.window.OnBackInvokedCallback { back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        }
        play(list[start.coerceIn(0, list.size - 1)], remember = false)
    }

    @Suppress("DEPRECATION")
    private fun pad(ins: WindowInsets) {
        val top: Int; val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) { val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); top = s.top; bottom = s.bottom }
        else { top = ins.systemWindowInsetTop; bottom = ins.systemWindowInsetBottom }
        column.setPadding(0, if (wide) 0 else top, 0, if (wide) 0 else bottom)
    }

    // ---------- the player ----------
    inner class Tube {
        @android.webkit.JavascriptInterface fun time(t: Double, d: Double) { runOnUiThread { showTime(t, d) } }
        @android.webkit.JavascriptInterface fun err(code: Int) { runOnUiThread { cantPlay(code) } }
        @android.webkit.JavascriptInterface fun ended() { runOnUiThread { if (endedFor != cur.src) { endedFor = cur.src; next() } } }
    }

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
        addJavascriptInterface(Tube(), "HabitTube")
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { showFull(view, callback) }
            override fun onHideCustomView() { hideFull(false) }
        }
    }

    /** Plays [item] here. [remember]: the one playing now is kept for Back. */
    private fun play(item: MediaViewer.Item, remember: Boolean = true) {
        if (remember && ::cur.isInitialized && cur.src != item.src) { before.add(cur); if (before.size > 50) before.removeAt(0) }
        cur = item
        now = 0.0; length = 0.0; counted = false; endedFor = ""
        Know.watched(prefs, item.who)
        val w = web ?: makeWeb().also { web = it; videoBox.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        w.onResume()
        val id = item.src.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(11)
        val origin = server.trimEnd('/')
        val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head><body>" +
            "<iframe id=\"p\" src=\"https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=$origin\" " +
            "allow=\"autoplay; encrypted-media; picture-in-picture; fullscreen\" allowfullscreen></iframe>" +
            "<script>var f=document.getElementById('p'),ok=false;" +
            "function post(o){try{o.id=1;o.channel='widget';f.contentWindow.postMessage(JSON.stringify(o),'*');}catch(e){}}" +
            "function hello(){post({event:'listening'});post({event:'command',func:'addEventListener',args:['onError']});post({event:'command',func:'addEventListener',args:['onStateChange']});}" +
            "function seek(s){post({event:'command',func:'seekTo',args:[s,true]});}" +
            "function go(){post({event:'command',func:'playVideo',args:''});}" +
            "f.onload=function(){hello();var n=0,iv=setInterval(function(){if(ok||++n>30)clearInterval(iv);else hello();},400);};" +
            "window.addEventListener('message',function(e){if(String(e.origin).indexOf('youtube')<0)return;var m;try{m=typeof e.data=='string'?JSON.parse(e.data):e.data;}catch(x){return;}" +
            "if(!m||!window.HabitTube)return;if(m.event=='onError'){HabitTube.err(+m.info||0);return;}" +
            "if(m.event=='onStateChange'){if(+m.info===0)HabitTube.ended();return;}" +
            "if((m.event=='infoDelivery'||m.event=='initialDelivery')&&m.info){ok=true;var i=m.info;if(i.playerState===0)HabitTube.ended();if(i.currentTime!=null||i.duration)HabitTube.time(+(i.currentTime||0),+(i.duration||0));}});</script>" +
            "</body></html>"
        w.loadDataWithBaseURL("$origin/", html, "text/html", "utf-8", null)
        page()
        scroll.scrollTo(0, 0)
        if (MyChannels.valid(item.chId) && moreFor != item.chId) loadMore(item.chId)
    }

    private fun showTime(t: Double, d: Double) {
        now = t
        if (d > 0) length = d
        if (!counted && (t >= 15 || (length > 0 && t >= length * 0.6))) {
            counted = true
            if (MyChannels.valid(cur.chId)) MyChannels.watched(prefs, cur.chId, cur.who)
        }
    }

    private fun skip(by: Int) {
        val to = (now + by).coerceAtLeast(0.0).let { if (length > 0) it.coerceAtMost(length - 1) else it }
        now = to
        web?.evaluateJavascript("seek($to)", null)
    }

    /** YouTube won't play this one inside other apps (or it's gone): the next one by itself. */
    private fun cantPlay(code: Int) {
        if (code !in listOf(2, 5, 100, 101, 150)) return
        if (code == 101 || code == 150) NoEmbed.tell(prefs, cur.src, cur.chId)
        android.widget.Toast.makeText(this, "This one plays only on YouTube · next ▶", android.widget.Toast.LENGTH_SHORT).show()
        if (!next()) openOut("https://www.youtube.com/watch?v=${cur.src}")
    }

    /** The videos under the player, in order: the rest of the list (from the one after this), then the channel's. */
    private fun others(): List<MediaViewer.Item> {
        val i = list.indexOfFirst { it.src == cur.src }
        val rest = if (i >= 0) list.drop(i + 1) + list.take(i) else list
        val seen = HashSet<String>().apply { add(cur.src) }
        return (rest + more).filter { seen.add(it.src) }
    }

    private fun next(): Boolean {
        val n = others().firstOrNull { o -> before.none { it.src == o.src } } ?: return false
        play(n); return true
    }

    private fun loadMore(chId: String) {
        moreFor = chId
        io.execute {
            val r = try { Net.get(prefs, "/api/videos/mine?ch=" + Uri.encode(chId)) } catch (e: Exception) { null }
            val a = if (r != null && r.ok) r.json.optJSONArray("latest") else null
            val got = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").length == 11 }
                .map { MediaViewer.Item("video", it.optString("id"), "", it.optString("ch"), it.optString("title"), 0, false, chId = it.optString("chId")) }
            runOnUiThread { if (!isFinishing && moreFor == chId) { more = got; if (::cur.isInitialized && cur.chId == chId) page() } }
        }
    }

    // ---------- the page under the player ----------
    private fun page() {
        body.removeAllViews()
        val pad = dp(14)
        body.addView(TextView(this).apply {
            text = cur.words.ifBlank { cur.who }; setTextColor(Color.WHITE); textSize = 16.5f; typeface = Fonts.bold(context)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f); setPadding(pad, dp(12), pad, 0)
            setOnClickListener { maxLines = if (maxLines == 2) 12 else 2 }
        })
        body.addView(TextView(this).apply { text = cur.who; setTextColor(GREY); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(pad, dp(4), pad, 0) })

        // small buttons in one row (slide sideways for more)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad - dp(4), dp(10), pad, dp(6)) }
        fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
            setPadding(dp(13), dp(8), dp(13), dp(8)); background = ui.round(Color.parseColor("#272727"), 18)
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        chip("⏪ 10") { skip(-10) }
        chip("10 ⏩") { skip(10) }
        if (MyChannels.valid(cur.chId)) { followChip = chip("") { follow() }; paintFollow() } else followChip = null
        chip("📤 Share") { share() }
        chip("👍 Like · 💬 · 🔔 on YouTube") { openOut("https://www.youtube.com/watch?v=${cur.src}") }
        if (before.isNotEmpty()) chip("⏮ Before") { back() }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })

        val rest = others().take(40)
        if (rest.isNotEmpty()) body.addView(TextView(this).apply { text = "తర్వాత · Up next"; setTextColor(GREY); textSize = 13f; typeface = Fonts.bold(context); setPadding(pad, dp(10), pad, dp(2)) })
        for (o in rest) body.addView(card(o))
        body.addView(View(this), LinearLayout.LayoutParams(-1, dp(90)))       // room for the pet in the corner
    }

    /** One video in the list: a big picture, its name and its channel (like YouTube). */
    private fun card(o: MediaViewer.Item): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(6)); contentDescription = o.words; setOnClickListener { play(o) } }
        val pic = Box169(this).apply { setBackgroundColor(Color.parseColor("#1C1C1C")) }
        pic.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load("https://i.ytimg.com/vi/${o.src}/hqdefault.jpg", this, 720) }, FrameLayout.LayoutParams(-1, -1))
        c.addView(pic, LinearLayout.LayoutParams(-1, -2))
        c.addView(TextView(this).apply { text = o.words.ifBlank { o.who }; setTextColor(Color.WHITE); textSize = 14.5f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f); setPadding(dp(14), dp(8), dp(14), 0) })
        c.addView(TextView(this).apply { text = o.who; setTextColor(GREY); textSize = 12.5f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(14), dp(2), dp(14), 0) })
        return c
    }

    private fun paintFollow() { followChip?.text = if (MyChannels.following(prefs, cur.chId)) "⭐ Following ✓" else "⭐ Follow" }
    private fun follow() {
        val on = !MyChannels.following(prefs, cur.chId)
        MyChannels.setFollow(prefs, cur.chId, cur.who, on)
        paintFollow()
        if (on) android.widget.Toast.makeText(this, "⭐ ${cur.who}: new videos will come to your Media page", android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun share() {
        val t = listOf(cur.words.take(1200), "https://youtu.be/${cur.src}", "— ${cur.who} · Habit AI 🐾 $server").filter { it.isNotBlank() }.joinToString("\n\n")
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")) } catch (e: Exception) { }
    }
    private fun openOut(u: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { } }

    // ---------- Back: out of full screen, then the video before, then the page it was opened from ----------
    private fun back() {
        if (full != null) { hideFull(true); return }
        if (before.isNotEmpty()) { play(before.removeAt(before.lastIndex), remember = false); return }
        finish()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { back() }

    // ---------- full screen (YouTube's own button) and turning the phone sideways ----------
    private fun showFull(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (full != null) { cb.onCustomViewHidden(); return }
        full = v; fullCb = cb
        outer.addView(v, FrameLayout.LayoutParams(-1, -1))
        column.visibility = View.INVISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars(false)
    }
    private fun hideFull(tellWeb: Boolean) {
        val v = full ?: return
        full = null
        outer.removeView(v)
        if (tellWeb) try { fullCb?.onCustomViewHidden() } catch (e: Exception) { }
        fullCb = null
        column.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (!wide) bars(true)
        web?.postDelayed({ web?.evaluateJavascript("go()", null) }, 700)
    }
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val land = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land == wide) return
        wide = land
        scroll.visibility = if (land) View.GONE else View.VISIBLE
        videoBox.layoutParams = LinearLayout.LayoutParams(-1, if (land) -1 else -2)
        column.gravity = if (land) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
        if (full == null) bars(!land)
        outer.requestApplyInsets()
    }
    @Suppress("DEPRECATION")
    private fun bars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let { c ->
            if (show) c.show(WindowInsets.Type.systemBars())
            else { c.hide(WindowInsets.Type.systemBars()); c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else window.decorView.systemUiVisibility = if (show) 0 else View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun petSmall(on: Boolean) {
        if (PetService.running) try { startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_GAME).putExtra("on", on)) } catch (e: Exception) { }
    }
    override fun onPause() { super.onPause(); petSmall(false); web?.onPause() }
    override fun onResume() { super.onResume(); petSmall(true); web?.onResume() }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) (backCb as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); try { w.loadUrl("about:blank"); w.destroy() } catch (e: Exception) { } }
        web = null
        io.shutdown()
        if (isFinishing) items = emptyList()
        super.onDestroy()
    }
}
