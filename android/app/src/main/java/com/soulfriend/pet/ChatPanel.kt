package com.soulfriend.pet

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** A drawing pad: draw with a finger in colours, on white or on a photo (✏️ Draw in a chat). */
class DoodleView(ctx: android.content.Context, private val bg: android.graphics.Bitmap?) : View(ctx) {
    var colour = Color.parseColor("#E53935")
    var thick = 10f
    private val strokes = ArrayList<Pair<android.graphics.Path, android.graphics.Paint>>()
    private var cur: android.graphics.Path? = null
    private val dst = android.graphics.RectF()
    private fun pen() = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colour; style = android.graphics.Paint.Style.STROKE; strokeWidth = thick * resources.displayMetrics.density / 2f
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { val p = android.graphics.Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.1f, e.y + 0.1f) }; cur = p; strokes.add(p to pen()) }
            android.view.MotionEvent.ACTION_MOVE -> { for (i in 0 until e.historySize) cur?.lineTo(e.getHistoricalX(i), e.getHistoricalY(i)); cur?.lineTo(e.x, e.y) }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> cur = null
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
        return true
    }
    override fun onDraw(c: android.graphics.Canvas) {
        c.drawColor(Color.WHITE)
        bg?.let { b ->
            val k = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * k; val h = b.height * k
            dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            c.drawBitmap(b, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        for ((p, paint) in strokes) c.drawPath(p, paint)
    }
    fun undo() { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.size - 1); invalidate() } }
    /** The drawing as a picture (only the photo's part, if drawn on a photo). */
    fun picture(): android.graphics.Bitmap {
        val full = android.graphics.Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), android.graphics.Bitmap.Config.ARGB_8888)
        draw(android.graphics.Canvas(full))
        if (bg == null || dst.width() < 2 || dst.height() < 2) return full
        val l = dst.left.toInt().coerceIn(0, full.width - 1); val t = dst.top.toInt().coerceIn(0, full.height - 1)
        return android.graphics.Bitmap.createBitmap(full, l, t, dst.width().toInt().coerceAtMost(full.width - l), dst.height().toInt().coerceAtMost(full.height - t))
    }
}

/** Moving pictures (GIFs): they move on Android 9 and newer; on Android 8 the first picture shows. */
object AnimImg {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val bytes = object : LruCache<String, ByteArray>(8 * 1024 * 1024) { override fun sizeOf(key: String, value: ByteArray) = value.size }
    /** Shows the GIF at [src] (a file on the phone, or an https address) in [iv]. */
    fun show(iv: ImageView, src: String) {
        iv.tag = src
        if (src.isBlank()) return
        pool.execute {
            val b = bytes.get(src) ?: (if (src.startsWith("https://")) Gifs.download(src) else try { File(src).readBytes() } catch (e: Exception) { null })?.also { bytes.put(src, it) } ?: return@execute
            val d: Drawable? = if (Build.VERSION.SDK_INT >= 28) try { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(b))) } catch (e: Exception) { null } else null
            val still = if (d == null) try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch (e: Exception) { null } else null
            main.post {
                if (iv.tag != src) return@post
                if (d != null) { iv.setImageDrawable(d); if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) { d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; d.start() } }
                else if (still != null) iv.setImageBitmap(still)
            }
        }
    }
}

/**
 * The 😊 panel under the typing bar (instead of the keyboard), like WhatsApp: emojis (by kind, recent ones first, and
 * 🔍 search in English or Telugu), GIFs (search, from KLIPY or GIPHY) and stickers (Habit's own packs, ⭐ favourites,
 * recent ones, my stickers, and ✏️ "Create sticker" from a photo). Hold a sticker to add it to (or take it off) ⭐.
 */
class ChatPanel(
    private val a: Activity, private val store: Store,
    private val onEmoji: (String) -> Unit, private val onBackspace: () -> Unit,
    private val onSticker: (String) -> Unit, private val onGif: (Gifs.Gif) -> Unit, private val onCreateSticker: () -> Unit
) {
    private val ui = Ui(a)
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)
    private val C = ChatTheme
    val view = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BAR) }
    private var tab = "emoji"
    private var cat = -1
    private var pack = ""
    private var query = ""
    private var searching = false
    private val content = FrameLayout(a)
    private val packBar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val topRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(2)) }
    private val search = EditText(a).apply {
        textSize = 15f; setTextColor(C.TXT); setHintTextColor(C.SUB); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = C.round(C.CARD, dp(20).toFloat()); visibility = View.GONE
    }
    private val main = Handler(Looper.getMainLooper())
    private var gifs: List<Gifs.Gif> = emptyList()
    private var gifNote = ""
    private var gifSeq = 0

    init {
        view.addView(topRow, LinearLayout.LayoutParams(-1, -2))
        view.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(2), dp(10), dp(4)) })
        view.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(packBar) }, LinearLayout.LayoutParams(-1, dp(46)))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                main.removeCallbacksAndMessages("gif")
                if (tab == "gif") main.postAtTime({ loadGifs() }, "gif", android.os.SystemClock.uptimeMillis() + 600) else fill()
            }
        })
        cat = if (Emojis.recent(a).isEmpty()) 0 else -1
        draw()
    }

    /** Shows the panel on [which] tab ("emoji", "gif" or "sticker"). */
    fun open(which: String = tab) { tab = which; draw() }

    private fun draw() {
        topRow.removeAllViews()
        topRow.addView(icon("🔍") { searching = !searching; if (!searching) { query = ""; search.setText("") }; search.visibility = if (searching) View.VISIBLE else View.GONE; if (searching) search.requestFocus(); fill() })
        val seg = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = C.round(C.CARD, dp(18).toFloat()); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        listOf("emoji" to "😊", "gif" to "GIF", "sticker" to "🏷️").forEach { (k, label) ->
            seg.addView(TextView(a).apply {
                text = label; textSize = if (k == "gif") 13f else 17f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (tab == k) C.NAVY else C.TXT); background = if (tab == k) C.round(C.GOLD, dp(15).toFloat()) else null
                setOnClickListener { tab = k; query = ""; search.setText(""); draw() }
            }, LinearLayout.LayoutParams(dp(64), dp(34)))
        }
        topRow.addView(LinearLayout(a).apply { gravity = Gravity.CENTER; addView(seg) }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(if (tab == "emoji") icon("⌫") { onBackspace() } else View(a).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        search.hint = when (tab) { "gif" -> "🔍 " + t("Search GIFs", "GIF వెతకండి"); "sticker" -> "🔍 " + t("Search stickers", "స్టిక్కర్లు వెతకండి"); else -> "🔍 " + t("Search emoji (e.g. love, నవ్వు)", "ఎమోజీ వెతకండి (ఉదా: నవ్వు, love)") }
        bottomBar()
        if (tab == "gif" && gifs.isEmpty()) loadGifs() else fill()
    }
    private fun icon(label: String, onClick: () -> Unit) = TextView(a).apply {
        text = label; textSize = 19f; gravity = Gravity.CENTER; setTextColor(C.TXT); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }
    private fun tabIcon(label: String, on: Boolean, desc: String, onClick: () -> Unit) = TextView(a).apply {
        text = label; textSize = 19f; gravity = Gravity.CENTER; contentDescription = desc
        background = if (on) C.round(C.CARD, dp(10).toFloat(), C.GOLD) else null; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(40)).apply { marginStart = dp(2) }
    }

    private fun bottomBar() {
        packBar.removeAllViews()
        when (tab) {
            "emoji" -> {
                packBar.addView(tabIcon("🕘", cat == -1, t("Recent", "ఇటీవలివి")) { cat = -1; bottomBar(); fill() })
                Emojis.CATS.forEachIndexed { i, c -> packBar.addView(tabIcon(c.icon, cat == i, if (te) c.te else c.en) { cat = i; bottomBar(); fill() }) }
            }
            "sticker" -> {
                if (pack.isEmpty()) pack = if (Stickers.recent(a).isNotEmpty()) "recent" else "pet"
                packBar.addView(tabIcon("🕘", pack == "recent", t("Recent", "ఇటీవలివి")) { pack = "recent"; bottomBar(); fill() })
                packBar.addView(tabIcon("⭐", pack == "fav", t("Favourites", "ఇష్టమైనవి")) { pack = "fav"; bottomBar(); fill() })
                packBar.addView(tabIcon("🖼️", pack == "mine", t("My stickers", "నా స్టిక్కర్లు")) { pack = "mine"; bottomBar(); fill() })
                Stickers.packs(a).forEach { p -> packBar.addView(tabIcon(p.icon, pack == p.id, if (te) p.te else p.en) { pack = p.id; bottomBar(); fill() }) }
            }
            else -> packBar.addView(TextView(a).apply {
                text = "  " + t("Powered by ", "అందిస్తున్నది: ") + Gifs.name() + "  "
                textSize = 12f; setTextColor(C.SUB); gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(-2, dp(40)))
        }
    }

    private fun grid(cols: Int, adapter: BaseAdapter, onPick: (Int) -> Unit, onHold: ((Int) -> Unit)? = null): GridView = GridView(a).apply {
        numColumns = cols; stretchMode = GridView.STRETCH_COLUMN_WIDTH; verticalSpacing = dp(4); horizontalSpacing = dp(4)
        setPadding(dp(6), dp(4), dp(6), dp(4)); clipToPadding = false; this.adapter = adapter; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        setOnItemClickListener { _, _, pos, _ -> onPick(pos) }
        if (onHold != null) setOnItemLongClickListener { _, _, pos, _ -> onHold(pos); true }
    }
    private fun note(s: String) { content.removeAllViews(); content.addView(ui.text(s, 14f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(16), dp(24), dp(16)) }, FrameLayout.LayoutParams(-1, -1)) }

    private fun fill() {
        content.removeAllViews()
        when (tab) {
            "emoji" -> {
                val list = if (searching && query.isNotEmpty()) Emojis.search(query) else if (cat == -1) Emojis.recent(a).ifEmpty { Emojis.CATS[0].list.take(40) } else Emojis.CATS[cat].list
                if (list.isEmpty()) { note(t("No emoji for \"$query\". Try another word.", "\"$query\" కి ఎమోజీ లేదు. వేరే మాట ప్రయత్నించండి.")); return }
                content.addView(grid(8, object : BaseAdapter() {
                    override fun getCount() = list.size
                    override fun getItem(p: Int) = list[p]
                    override fun getItemId(p: Int) = p.toLong()
                    override fun getView(p: Int, old: View?, parent: ViewGroup?): View = ((old as? TextView) ?: TextView(a).apply {
                        textSize = 27f; gravity = Gravity.CENTER; layoutParams = AbsListView.LayoutParams(-1, dp(46)) }).apply { text = list[p] }
                }, { p -> Emojis.used(a, list[p]); onEmoji(list[p]) }), FrameLayout.LayoutParams(-1, -1))
            }
            "sticker" -> {
                val refs: List<String> = when {
                    searching && query.isNotEmpty() -> Stickers.packs(a).flatMap { it.items }.filter { it.alt.lowercase().contains(query.lowercase()) || it.n.lowercase().contains(query.lowercase()) }.map { it.pack + ":" + it.n }
                    pack == "recent" -> Stickers.recent(a)
                    pack == "fav" -> Stickers.favs(a)
                    pack == "mine" -> Stickers.mine(a).map { "file:" + it.absolutePath }
                    else -> Stickers.packs(a).firstOrNull { it.id == pack }?.items?.map { it.pack + ":" + it.n } ?: emptyList()
                }
                val create = pack == "mine" || (pack == "recent" && !searching)
                if (refs.isEmpty() && !create) { note(when (pack) { "fav" -> t("Hold a sticker to add it to ⭐ favourites.", "స్టిక్కర్‌ని నొక్కి పట్టి ⭐ ఇష్టమైనవిలో చేర్చండి."); else -> t("No stickers here yet.", "ఇక్కడ ఇంకా స్టిక్కర్లు లేవు.") }); return }
                val n = refs.size + if (create) 1 else 0
                content.addView(grid(4, object : BaseAdapter() {
                    override fun getCount() = n
                    override fun getItem(p: Int) = p
                    override fun getItemId(p: Int) = p.toLong()
                    override fun getView(p: Int, old: View?, parent: ViewGroup?): View {
                        if (create && p == 0) return LinearLayout(a).apply {
                            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = AbsListView.LayoutParams(-1, dp(84)); background = C.round(C.CARD, dp(14).toFloat(), C.GOLD)
                            addView(ui.text("✏️", 24f).apply { gravity = Gravity.CENTER })
                            addView(ui.text(t("Create sticker", "స్టిక్కర్ చేయండి"), 11.5f, C.GOLD, bold = true).apply { gravity = Gravity.CENTER })
                        }
                        val ref = refs[p - if (create) 1 else 0]
                        val iv = (old as? ImageView) ?: ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = AbsListView.LayoutParams(-1, dp(84)); setPadding(dp(4), dp(4), dp(4), dp(4)) }
                        iv.setImageBitmap(Stickers.picture(a, ref, 192))
                        iv.contentDescription = Stickers.altOf(a, ref)
                        return iv
                    }
                }, { p -> if (create && p == 0) onCreateSticker() else { val ref = refs[p - if (create) 1 else 0]; Stickers.used(a, ref); onSticker(ref) } },
                    { p -> if (!(create && p == 0)) { val ref = refs[p - if (create) 1 else 0]; val on = !Stickers.isFav(a, ref); Stickers.fav(a, ref, on)
                        android.widget.Toast.makeText(a, if (on) t("Added to ⭐ favourites", "⭐ ఇష్టమైనవిలో చేర్చాం") else t("Taken off ⭐ favourites", "⭐ ఇష్టమైనవి నుంచి తీసేశాం"), android.widget.Toast.LENGTH_SHORT).show(); if (pack == "fav") fill() } }), FrameLayout.LayoutParams(-1, -1))
            }
            else -> {
                if (gifs.isEmpty()) { note(gifNote.ifBlank { "⏳" }); return }
                val list = gifs
                content.addView(grid(2, object : BaseAdapter() {
                    override fun getCount() = list.size
                    override fun getItem(p: Int) = list[p]
                    override fun getItemId(p: Int) = p.toLong()
                    override fun getView(p: Int, old: View?, parent: ViewGroup?): View {
                        val iv = (old as? ImageView) ?: ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = AbsListView.LayoutParams(-1, dp(110)); setBackgroundColor(C.CARD) }
                        iv.setImageDrawable(null); iv.contentDescription = list[p].desc
                        AnimImg.show(iv, list[p].preview)
                        return iv
                    }
                }, { p -> onGif(list[p]) }), FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    private fun loadGifs() {
        val my = ++gifSeq
        gifNote = "⏳"; if (tab == "gif") fill()
        Thread {
            val conf = Gifs.config(store)
            val res = if (conf?.optString("key").isNullOrEmpty()) null else Gifs.search(store, if (searching) query else "")
            main.post {
                if (my != gifSeq) return@post
                gifs = res?.first ?: emptyList()
                gifNote = when {
                    conf == null -> t("No connection. Check your internet.", "కనెక్షన్ లేదు. ఇంటర్నెట్ చూడండి.")
                    conf.optString("key").isEmpty() -> t("GIF search turns on when the Habit team adds a free GIF key. Stickers work now: tap 🏷️.", "Habit టీమ్ ఉచిత GIF కీ పెట్టాక GIF వెతకడం పనిచేస్తుంది. స్టిక్కర్లు ఇప్పుడే పనిచేస్తాయి: 🏷️ నొక్కండి.")
                    res == null -> t("Couldn't get GIFs right now. Try again.", "ఇప్పుడు GIF లు రాలేదు. మళ్ళీ ప్రయత్నించండి.")
                    else -> t("No GIFs for \"$query\".", "\"$query\" కి GIF లు లేవు.")
                }
                if (tab == "gif") { bottomBar(); fill() }
            }
        }.start()
    }
}
