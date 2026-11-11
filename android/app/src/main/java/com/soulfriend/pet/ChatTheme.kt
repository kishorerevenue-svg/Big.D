package com.soulfriend.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlin.math.cos
import kotlin.math.sin

/**
 * Habit Chat's look, in two styles that follow the phone's dark mode (or Chat settings › Theme):
 *  - "Navy and gold" (dark): navy pages, gold buttons, my messages in gold.
 *  - "Clean light": soft grey-white pages, navy buttons with gold, my messages in navy.
 * Every colour below is read when a screen is drawn, so the same code draws both looks.
 * Also the chat wallpapers (drawn on the phone, nothing to download) and the chat settings kept on this phone.
 */
object ChatTheme {
    /** The brand colours (the app icon): the same in both looks. */
    object Brand { val NAVY = Color.parseColor("#0B1026"); val GOLD = Color.parseColor("#F5C451"); val INK = Color.parseColor("#14182E") }

    /** True: "Navy and gold". False: "Clean light". Set by [apply] / [refresh]. */
    var dark = true
        private set
    private fun c(s: String) = Color.parseColor(s)
    private fun pick(d: Int, l: Int) = if (dark) d else l

    // ----- the page -----
    private val dBg = c("#0B1026"); private val lBg = c("#F4F5FA")
    /** Page background (the old name; same as [BG]). */
    val NAVY get() = pick(dBg, lBg)
    val BG get() = pick(dBg, lBg)
    /** Behind the messages, under the typing bar. */
    val CHAT_BG get() = pick(dBg, c("#EEF0F7"))
    /** Top bar and bottom bar. */
    val BAR get() = pick(c("#10152F"), Color.WHITE)
    /** Cards, chips, the search box, panels. */
    val CARD get() = pick(c("#151B3D"), Color.WHITE)
    /** A card inside a card (and the highlighted chat row). */
    val CARD2 get() = pick(c("#1A2148"), c("#F4F5FA"))
    /** Lines and the edges of white cards. */
    val LINE get() = pick(c("#262D57"), c("#E3E6F0"))
    /** The edge around cards and chips: none on navy (they stand out by colour), a light line on white. */
    val EDGE: Int? get() = if (dark) null else c("#E3E6F0")
    val TXT get() = pick(c("#F3F4FA"), c("#10163A"))
    /** Second-level words (the last message in the chat list). */
    val TXT2 get() = pick(c("#C9CEE8"), c("#39405F"))
    val SUB get() = pick(c("#A9B0D3"), c("#5A6286"))

    // ----- gold and the main button -----
    /** Gold as a fill (badges, "Mark done"). */
    val GOLD get() = pick(c("#F5C451"), c("#F2B53A"))
    /** Words and icons on [GOLD]. */
    val ON_GOLD get() = pick(c("#14182E"), c("#10163A"))
    /** Gold as words or icons on the page (dark gold on white, so it can be read). */
    val GOLD_TXT get() = pick(c("#F5C451"), c("#8A5A00"))
    /** A soft gold chip ("2 tasks"). */
    val GOLD_SOFT get() = pick(c("#29F5C451"), c("#FFF1D2"))
    val GOLD_DARK get() = pick(c("#C8962C"), c("#B57C0A"))
    /** The main button: the ✏️ new chat button, 🎤 / ➤, the chosen chip. Gold on navy; navy on white. */
    val PRIMARY get() = pick(c("#F5C451"), c("#10163A"))
    val ON_PRIMARY get() = pick(c("#14182E"), Color.WHITE)
    val ON_PRIMARY_ICON get() = pick(c("#14182E"), c("#F2B53A"))
    /** The chosen tab's pill. */
    val PILL get() = pick(c("#2EF5C451"), c("#FFE7B0"))
    val PILL_TXT get() = pick(c("#F5C451"), c("#10163A"))

    // ----- messages -----
    /** My bubbles: gold on navy; navy on white. */
    val MINE get() = pick(c("#F5C451"), c("#10163A"))
    val THEIRS get() = pick(c("#1A2148"), Color.WHITE)
    /** ✓✓ read (in the chat list and on their side). */
    val READ get() = pick(c("#53BDEB"), c("#1D7FD8"))
    val GOOD get() = pick(c("#4CC38A"), c("#1F7A4D"))
    /** The online dot and "online". */
    val ONLINE get() = pick(c("#4CC38A"), c("#2E9E6A"))
    val ONLINE_TXT get() = pick(c("#4CC38A"), c("#1F7A4D"))
    val DANGER get() = pick(c("#FF6B6B"), c("#D93A3F"))
    val HEART get() = pick(c("#FF6B81"), c("#E5484D"))
    /** The pinned bar under the top bar. */
    val PIN_BG get() = pick(c("#131939"), c("#FFF8E6"))
    val PIN_LINE get() = pick(c("#10FFFFFF"), c("#F1E3BC"))
    val PIN_SUB get() = pick(c("#A9B0D3"), c("#6B5520"))
    /** Names in groups (each person keeps one colour). */
    private val namesD = intArrayOf(c("#F5C451"), c("#7FD4C9"), c("#F29A76"), c("#B79CF2"), c("#7CC4F2"), c("#F28AB3"))
    private val namesL = intArrayOf(c("#8A5A00"), c("#0F7A70"), c("#C2412F"), c("#5B4BC4"), c("#1F6FA8"), c("#A3326A"))
    val NAMES: IntArray get() = if (dark) namesD else namesL

    /** Soft colour pairs (background, ink) for pictures and tiles: gold, teal, lilac, coral, sky, green, pink, orange. */
    private val tintsD = arrayOf(c("#3A3220") to c("#F5C451"), c("#1F5F63") to c("#BFF3EC"), c("#4B3F8F") to c("#E4DEFF"), c("#7A3A33") to c("#FFD9D3"),
        c("#2E6E8E") to c("#DDF1FF"), c("#2F6B3F") to c("#DDF7E3"), c("#8E3B62") to c("#FFE0EE"), c("#9A5B22") to c("#FFE6C9"))
    private val tintsL = arrayOf(c("#FFF1D2") to c("#8A5A00"), c("#D4F0EC") to c("#0F6B63"), c("#E4E0FA") to c("#4B3BA8"), c("#FFE4E0") to c("#C2412F"),
        c("#D3E8F5") to c("#1D5A80"), c("#D7F0DD") to c("#24683A"), c("#F8D9E6") to c("#8E2F58"), c("#FBE3CA") to c("#8C4A12"))
    val TINTS: Array<Pair<Int, Int>> get() = if (dark) tintsD else tintsL
    /** The same colour pair for the same name or id, every time. */
    fun tint(key: String): Pair<Int, Int> = TINTS[Math.floorMod(key.hashCode(), TINTS.size)]

    /** A thin line under the top bar. */
    val DIVIDER get() = pick(c("#12FFFFFF"), c("#E3E6F0"))
    /** The "Today" chip between messages. */
    val DAY_BG get() = pick(c("#1A2148"), Color.WHITE)
    val DAY_TXT get() = pick(c("#C9CEE8"), c("#5A6286"))

    /**
     * The colours inside one message bubble ("ink"): mine (gold or navy) or theirs (and cards on the page).
     * [inner]: a card inside the bubble (a file, a link, a quoted message). [accent]: the voice play button, the bar of a
     * quote, poll bars; [onAccent] sits on it. [btn]/[onBtn]: a filled button; [outline]: the edge of a plain button.
     * [gold]: gold words (mentions, "View votes"). [button]: the small round button on a file.
     */
    class Ink(val bg: Int, val edge: Int?, val txt: Int, val sub: Int, val accent: Int, val onAccent: Int, val link: Int,
              val inner: Int, val read: Int, val tick: Int, val gold: Int, val button: Int, val btn: Int, val onBtn: Int, val outline: Int) {
        /** A voice note's bars not played yet. */
        val waveRest: Int get() = when (accent) {
            c("#14182E") -> c("#5914182E")           // on my gold bubble
            c("#F2B53A") -> c("#59FFFFFF")           // on my navy bubble
            c("#F5C451") -> c("#66A9B0D3")           // their bubble, navy look
            else -> c("#4D10163A")                   // their bubble, light look
        }
    }
    fun ink(mine: Boolean): Ink = when {
        mine && dark -> Ink(c("#F5C451"), null, c("#14182E"), c("#5C4A16"), c("#14182E"), c("#F5C451"), c("#1D4E9E"), c("#1A14182E"), c("#1D5FBF"),
            c("#5C4A16"), c("#14182E"), c("#2414182E"), c("#14182E"), c("#F5C451"), c("#5514182E"))
        mine -> Ink(c("#10163A"), null, Color.WHITE, c("#C7CCE6"), c("#F2B53A"), c("#10163A"), c("#9FD3FF"), c("#1AFFFFFF"), c("#7CC8FF"),
            c("#C7CCE6"), c("#F2B53A"), c("#26FFFFFF"), c("#F2B53A"), c("#10163A"), c("#59FFFFFF"))
        dark -> Ink(c("#1A2148"), null, c("#F3F4FA"), c("#A9B0D3"), c("#F5C451"), c("#14182E"), c("#53BDEB"), c("#0DFFFFFF"), c("#53BDEB"),
            c("#A9B0D3"), c("#F5C451"), c("#1AFFFFFF"), c("#F5C451"), c("#14182E"), c("#33FFFFFF"))
        else -> Ink(Color.WHITE, c("#E3E6F0"), c("#10163A"), c("#5A6286"), c("#10163A"), Color.WHITE, c("#1D6FD1"), c("#F4F5FA"), c("#1D7FD8"),
            c("#5A6286"), c("#8A5A00"), c("#E3E6F0"), c("#F2B53A"), c("#10163A"), c("#C9CEE0"))
    }

    /**
     * Colours for the words inside a pop-up (dialog). Pop-ups follow the screen's Android theme: dark on the chat
     * screens in "Navy and gold", light elsewhere (Home, the chat list's pop-ups).
     */
    class Dlg(val dark: Boolean) {
        val txt = if (dark) Color.parseColor("#F3F4FA") else Color.parseColor("#10163A")
        val sub = if (dark) Color.parseColor("#A9B0D3") else Color.parseColor("#5A6286")
        val accent = if (dark) Color.parseColor("#F5C451") else Color.parseColor("#8A5A00")
        val line = if (dark) Color.parseColor("#262D57") else Color.parseColor("#E3E6F0")
        val field = if (dark) Color.parseColor("#1A2148") else Color.parseColor("#F4F5FA")
        val pill = if (dark) Color.parseColor("#2EF5C451") else Color.parseColor("#FFE7B0")
    }
    /** The pop-up colours for this screen (read from its Android theme). */
    fun dlg(ctx: Context): Dlg = Dlg(themeDark(ctx))
    fun themeDark(ctx: Context): Boolean = try {
        val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val col = a.getColor(0, Color.BLACK); a.recycle(); !isDarkColor(col)
    } catch (e: Exception) { false }
    /** A text box for pop-ups, in the pop-up's colours. */
    fun input(ctx: Context, hint: String, value: String = "", multi: Boolean = false): android.widget.EditText {
        val d = dlg(ctx); val ui = Ui(ctx)
        return ui.input(hint, value, multi).apply {
            setTextColor(d.txt); setHintTextColor(d.sub)
            background = round(d.field, ui.dpf(14), d.line, ui.dp(1))
        }
    }
    /**
     * Shows [content] as a sheet that slides up from the bottom (rounded top corners), like WhatsApp's attach menu.
     * Returns the dialog (to close it from a tap inside).
     */
    fun sheet(act: android.app.Activity, content: android.view.View): android.app.Dialog {
        val d = android.app.Dialog(act)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val dp = act.resources.displayMetrics.density
        val bg = if (themeDark(act)) c("#151B3D") else Color.WHITE
        val wrap = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = corners(bg, 28 * dp, 28 * dp, 0f, 0f)
            // the little handle on top
            addView(android.view.View(act).apply { background = round(if (themeDark(act)) c("#3A4170") else c("#D5D9E6"), 3 * dp) },
                android.widget.LinearLayout.LayoutParams((36 * dp).toInt(), (5 * dp).toInt()).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = (10 * dp).toInt() })
            addView(content, android.widget.LinearLayout.LayoutParams(-1, -2))
            val base = (12 * dp).toInt()
            setPadding(0, 0, 0, base)
            setOnApplyWindowInsetsListener { v, ins -> @Suppress("DEPRECATION") v.setPadding(0, 0, 0, base + ins.systemWindowInsetBottom); ins }
        }
        d.setContentView(wrap)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.attributes = w.attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
        }
        d.show()
        d.window?.let { w ->
            w.setGravity(android.view.Gravity.BOTTOM)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return d
    }

    /** The look to use: "auto" (the phone's dark mode), "dark" or "light" (Chat settings › Theme). */
    fun mode(ctx: Context): String = ChatDb.get(ctx).kv("set:theme").ifEmpty { "auto" }
    private fun want(ctx: Context): Boolean = when (mode(ctx)) {
        "dark" -> true
        "light" -> false
        else -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    /** Works out the look again (for screens that are already open). True if it changed. */
    fun refresh(ctx: Context): Boolean { val w = want(ctx); val changed = w != dark; dark = w; return changed }
    /** Call before super.onCreate(): picks the look and the matching Android theme (dialogs, the keyboard's colours). */
    fun apply(act: android.app.Activity) {
        refresh(act)
        act.setTheme(if (dark) R.style.Habit_Dark else R.style.Habit_Light)
    }
    /** The phone's status bar and navigation bar: their colours and light or dark icons. */
    @Suppress("DEPRECATION")
    fun bars(act: android.app.Activity, top: Int = BAR, bottom: Int = CHAT_BG) {
        val w = act.window
        w.decorView     // (makes the window's views first: the insets controller needs them)
        w.statusBarColor = top; w.navigationBarColor = bottom
        val lightTop = !isDarkColor(top); val lightBottom = !isDarkColor(bottom)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.setSystemBarsAppearance(
                (if (lightTop) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or (if (lightBottom) android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0),
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            var f = w.decorView.systemUiVisibility and (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            if (lightTop) f = f or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (lightBottom) f = f or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = f
        }
    }
    fun isDarkColor(col: Int): Boolean = (Color.red(col) * 299 + Color.green(col) * 587 + Color.blue(col) * 114) / 1000 < 140

    /** A screen's background that paints the status bar strip (its top padding) in [top] and the rest in [rest]. */
    class Backdrop(private val view: android.view.View, private val top: Int, private val rest: Int) : Drawable() {
        private val p = Paint()
        override fun draw(canvas: Canvas) {
            canvas.drawColor(rest)
            if (view.paddingTop > 0) { p.color = top; canvas.drawRect(0f, 0f, bounds.width().toFloat(), view.paddingTop.toFloat(), p) }
        }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(cf: ColorFilter?) { }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    fun round(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 2) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx; if (stroke != null) setStroke(strokePx, stroke)
    }
    /** Different corners: top-left, top-right, bottom-right, bottom-left (a bubble's small corner on the sender's side). */
    fun corners(color: Int, tl: Float, tr: Float, br: Float, bl: Float, stroke: Int? = null, strokePx: Int = 2) = GradientDrawable().apply {
        setColor(color); cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl); if (stroke != null) setStroke(strokePx, stroke)
    }
    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    /** A soft "pressed" ripple in a rounded shape, over [base] (or nothing). */
    fun press(ctx: Context, radiusPx: Float, base: Drawable?): Drawable {
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusPx }
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (dark) c("#22FFFFFF") else c("#1810163A")), base, mask)
    }
    /** The pinned chats' row: a little lighter than the page. */
    val ROW_HI get() = pick(c("#141A3A"), Color.WHITE)

    // ---------- wallpapers ----------
    /** A wallpaper: its look on navy ([baseD], [inkD]) and on white ([baseL], [inkL]). */
    class Wall(val id: Int, private val enD: String, private val teD: String, private val enL: String, private val teL: String,
               private val baseD: Int, private val inkD: Int, private val baseL: Int, private val inkL: Int, val style: Int) {
        val en get() = if (dark) enD else enL
        val te get() = if (dark) teD else teL
        val base get() = if (dark) baseD else baseL
        val ink get() = if (dark) inkD else inkL
    }
    val WALLS = listOf(
        Wall(6, "Soft dots", "చిన్న చుక్కలు", "Soft dots", "చిన్న చుక్కలు", c("#0B1026"), c("#0DFFFFFF"), c("#EEF0F7"), c("#1410163A"), 5),
        Wall(0, "Gold doodles", "బంగారు బొమ్మలు", "Gold doodles", "బంగారు బొమ్మలు", c("#0F1330"), c("#22F6C453"), c("#F3F1EA"), c("#2E8A5A00"), 0),
        Wall(1, "Night stars", "రాత్రి నక్షత్రాలు", "Stars", "నక్షత్రాలు", c("#0B0F26"), c("#33FFFFFF"), c("#EEF0F7"), c("#2410163A"), 1),
        Wall(2, "Teal waves", "నీలి అలలు", "Teal waves", "నీలి అలలు", c("#0C2A2E"), c("#2647D1C4"), c("#E2F2F0"), c("#330F7A70"), 2),
        Wall(3, "Rangoli", "ముగ్గు", "Rangoli", "ముగ్గు", c("#1A1236"), c("#26F29A76"), c("#F8EEEA"), c("#33C2412F"), 3),
        Wall(4, "Plain navy", "సాదా నేవీ", "Plain grey", "సాదా బూడిద", c("#0F1330"), Color.TRANSPARENT, c("#EEF0F7"), Color.TRANSPARENT, 4),
        Wall(5, "Plain black", "సాదా నలుపు", "Plain white", "సాదా తెలుపు", c("#05060D"), Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT, 4),
    )
    /** The new chats' wallpaper: soft dots. */
    const val DEFAULT_WALL = 6
    fun wall(id: Int) = WALLS.firstOrNull { it.id == id } ?: WALLS[0]

    /** A tiled wallpaper drawn on the canvas: soft dots, small gold bars and stars, stars, waves or rangoli dots. */
    class WallDrawable(private val w: Wall, private val density: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = w.ink; style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND }
        private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = w.ink; style = Paint.Style.FILL }
        override fun draw(c: Canvas) {
            val b = bounds
            c.drawColor(w.base)
            if (w.style == 4) return
            if (w.style == 5) { // a fine grid of soft dots
                val step = 18f * density
                var y = b.top + step / 2
                while (y < b.bottom) { var x = b.left + step / 2; while (x < b.right) { c.drawCircle(x, y, 1.1f * density, f); x += step }; y += step }
                return
            }
            val cell = 64f * density
            var row = 0
            var y = b.top - cell / 2
            while (y < b.bottom + cell) {
                var x = b.left - cell / 2 + (if (row % 2 == 1) cell / 2 else 0f)
                var col = 0
                while (x < b.right + cell) {
                    val k = (row * 7 + col * 3) % 6
                    when (w.style) {
                        0 -> doodle(c, x, y, k)
                        1 -> star(c, x + (k * 5 % 17) * density, y + (k * 11 % 13) * density, (2f + k % 3) * density, k % 2 == 0)
                        2 -> wave(c, x, y)
                        else -> rangoli(c, x, y, k)
                    }
                    x += cell; col++
                }
                y += cell * 0.86f; row++
            }
        }
        private fun doodle(c: Canvas, x: Float, y: Float, k: Int) {
            val d = density
            when (k) {
                0, 3 -> { // a small gold bar with an H
                    c.drawRoundRect(RectF(x - 11 * d, y - 7 * d, x + 11 * d, y + 7 * d), 3 * d, 3 * d, p)
                    c.drawLine(x - 4 * d, y - 4 * d, x - 4 * d, y + 4 * d, p); c.drawLine(x + 4 * d, y - 4 * d, x + 4 * d, y + 4 * d, p); c.drawLine(x - 4 * d, y, x + 4 * d, y, p)
                }
                1 -> star(c, x, y, 7 * d, false)
                2 -> { c.drawCircle(x, y, 6 * d, p); c.drawCircle(x, y, 1.6f * d, f) }
                4 -> { // a speech bubble
                    c.drawRoundRect(RectF(x - 10 * d, y - 7 * d, x + 10 * d, y + 5 * d), 5 * d, 5 * d, p)
                    c.drawLine(x - 4 * d, y + 5 * d, x - 7 * d, y + 10 * d, p)
                }
                else -> { // a paw
                    c.drawCircle(x, y + 3 * d, 4 * d, f)
                    for (i in 0..2) c.drawCircle(x - 5 * d + i * 5 * d, y - 4 * d - (if (i == 1) 2 * d else 0f), 1.8f * d, f)
                }
            }
        }
        private fun star(c: Canvas, x: Float, y: Float, r: Float, filled: Boolean) {
            val path = Path()
            for (i in 0 until 8) {
                val a = Math.PI / 4 * i - Math.PI / 2
                val rr = if (i % 2 == 0) r else r * 0.38f
                val px = x + (cos(a) * rr).toFloat(); val py = y + (sin(a) * rr).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            c.drawPath(path, if (filled) f else p)
        }
        private fun wave(c: Canvas, x: Float, y: Float) {
            val d = density
            val path = Path(); path.moveTo(x - 24 * d, y)
            path.cubicTo(x - 12 * d, y - 8 * d, x - 4 * d, y + 8 * d, x + 8 * d, y); path.cubicTo(x + 14 * d, y - 5 * d, x + 20 * d, y - 5 * d, x + 26 * d, y)
            c.drawPath(path, p)
        }
        private fun rangoli(c: Canvas, x: Float, y: Float, k: Int) {
            val d = density
            for (i in 0 until 8) { val a = Math.PI / 4 * i; c.drawCircle(x + (cos(a) * 9 * d).toFloat(), y + (sin(a) * 9 * d).toFloat(), 1.6f * d, f) }
            if (k % 2 == 0) c.drawCircle(x, y, 4 * d, p) else c.drawCircle(x, y, 2 * d, f)
        }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(cf: ColorFilter?) { }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    // ---------- chat settings (kept on this phone) ----------
    private fun db(ctx: Context) = ChatDb.get(ctx)
    fun wallOf(ctx: Context, cid: String): Int = db(ctx).kv("wall:$cid").toIntOrNull() ?: db(ctx).kv("set:wall").toIntOrNull() ?: DEFAULT_WALL
    fun notifyOn(ctx: Context) = db(ctx).kv("set:notify") != "0"
    fun previewOn(ctx: Context) = db(ctx).kv("set:preview") != "0"
    fun receiptsOn(ctx: Context) = db(ctx).kv("set:receipts") != "0"
    /** Videos and documents download by themselves: "wifi" (default), "always" or "never". */
    fun autoDl(ctx: Context) = db(ctx).kv("set:autodl").ifEmpty { "wifi" }
    fun lockHidden(ctx: Context) = db(ctx).kv("set:lock") != "0"
    fun tasksBar(ctx: Context) = db(ctx).kv("set:tasksbar") == "1"
    fun isHidden(ctx: Context, cid: String) = db(ctx).kv("lock:$cid") == "1"
    /** Disappearing messages in this chat: seconds (0 = off). */
    fun disappear(ctx: Context, cid: String): Long = db(ctx).kv("dis:$cid").toLongOrNull() ?: 0L
    /** For new chats: start with disappearing messages on (seconds, 0 = off). */
    fun defaultTimer(ctx: Context): Long = db(ctx).kv("set:timer").toLongOrNull() ?: 0L
    fun reactNotify(ctx: Context) = db(ctx).kv("set:reactnotify") != "0"
    fun linkPreviews(ctx: Context) = db(ctx).kv("set:previews") != "0"
    fun enterSends(ctx: Context) = db(ctx).kv("set:enter") == "1"
    /** Message text size: 0.9 small, 1.0 medium, 1.15 large. */
    fun fontScale(ctx: Context): Float = when (db(ctx).kv("set:font")) { "s" -> 0.9f; "l" -> 1.15f; else -> 1f }
    fun timerText(s: Long, te: Boolean): String = when {
        s <= 0 -> if (te) "ఆఫ్" else "Off"
        s <= 86400 -> if (te) "24 గంటలు" else "24 hours"
        s <= 7 * 86400 -> if (te) "7 రోజులు" else "7 days"
        else -> if (te) "90 రోజులు" else "90 days"
    }
    val TIMERS = longArrayOf(0L, 86400L, 7 * 86400L, 90 * 86400L)
    /** Hidden chats open with the fingerprint or the phone lock (its own prompt: it doesn't open the Vault). */
    fun unlockHidden(act: android.app.Activity, done: (String?) -> Unit) {
        val km = act.getSystemService(android.app.KeyguardManager::class.java)
        if (!lockHidden(act) || km == null || !km.isDeviceSecure || android.os.Build.VERSION.SDK_INT < 28) { done(null); return }
        val te = Store(act).lang == "te"
        val b = android.hardware.biometrics.BiometricPrompt.Builder(act).setTitle(if (te) "దాచిన చాట్‌లు తెరవండి" else "Open hidden chats")
            .setSubtitle(if (te) "వేలిముద్ర లేదా ఫోన్ లాక్" else "Use your fingerprint or phone lock")
        if (android.os.Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        b.build().authenticate(android.os.CancellationSignal(), act.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { done(null) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { done(errString?.toString() ?: "Not unlocked") }
        })
    }

    fun onWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } ?: false
    } catch (e: Exception) { false }
}
