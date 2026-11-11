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
 * Habit Chat's look: dark navy and gold (the colours of the app icon), like WhatsApp's dark mode.
 * Also the chat wallpapers (drawn on the phone, nothing to download) and the chat settings kept on this phone.
 */
object ChatTheme {
    val NAVY = Color.parseColor("#0F1330")        // page background
    val BAR = Color.parseColor("#161B3D")         // top bar, typing bar, search box
    val CARD = Color.parseColor("#1E2550")        // their bubbles, chips, panels
    val LINE = Color.parseColor("#2A3266")
    val GOLD = Color.parseColor("#F6C453")
    val GOLD_DARK = Color.parseColor("#C8962C")
    val TXT = Color.parseColor("#ECEFF7")
    val SUB = Color.parseColor("#9AA3C7")
    val MINE = Color.parseColor("#0F5E5A")        // my bubbles: teal
    val READ = Color.parseColor("#53BDEB")        // ✓✓ read
    val GOOD = Color.parseColor("#4CC38A")
    val DANGER = Color.parseColor("#FF6B6B")
    val NAMES = intArrayOf(Color.parseColor("#F6C453"), Color.parseColor("#7FD4C9"), Color.parseColor("#F29A76"), Color.parseColor("#B79CF2"),
        Color.parseColor("#7CC4F2"), Color.parseColor("#F28AB3"))

    fun round(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 2) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx; if (stroke != null) setStroke(strokePx, stroke)
    }
    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    // ---------- wallpapers ----------
    class Wall(val id: Int, val en: String, val te: String, val base: Int, val ink: Int, val style: Int)
    val WALLS = listOf(
        Wall(0, "Gold doodles", "బంగారు బొమ్మలు", Color.parseColor("#0F1330"), Color.parseColor("#22F6C453"), 0),
        Wall(1, "Night stars", "రాత్రి నక్షత్రాలు", Color.parseColor("#0B0F26"), Color.parseColor("#33FFFFFF"), 1),
        Wall(2, "Teal waves", "నీలి అలలు", Color.parseColor("#0C2A2E"), Color.parseColor("#2647D1C4"), 2),
        Wall(3, "Rangoli", "ముగ్గు", Color.parseColor("#1A1236"), Color.parseColor("#26F29A76"), 3),
        Wall(4, "Plain navy", "సాదా నేవీ", Color.parseColor("#0F1330"), Color.TRANSPARENT, 4),
        Wall(5, "Plain black", "సాదా నలుపు", Color.parseColor("#05060D"), Color.TRANSPARENT, 4),
    )
    fun wall(id: Int) = WALLS.firstOrNull { it.id == id } ?: WALLS[0]

    /** A tiled wallpaper drawn on the canvas: small gold bars and stars, stars, waves or rangoli dots. */
    class WallDrawable(private val w: Wall, private val density: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = w.ink; style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND }
        private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = w.ink; style = Paint.Style.FILL }
        override fun draw(c: Canvas) {
            val b = bounds
            c.drawColor(w.base)
            if (w.style == 4) return
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
    fun wallOf(ctx: Context, cid: String): Int = db(ctx).kv("wall:$cid").toIntOrNull() ?: db(ctx).kv("set:wall").toIntOrNull() ?: 0
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
