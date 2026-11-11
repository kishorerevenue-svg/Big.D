package com.soulfriend.pet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * The app's letters: Plus Jakarta Sans (free, SIL Open Font License) in five weights.
 * Telugu letters and emojis come from the phone's own fonts automatically.
 */
object Fonts {
    private var family: Typeface? = null
    private val byWeight = HashMap<Int, Typeface>()
    private fun family(ctx: Context): Typeface = family ?: (try { ctx.resources.getFont(R.font.jakarta) } catch (e: Exception) { Typeface.DEFAULT }).also { family = it }
    /** 400 regular, 500 medium, 600 semi-bold, 700 bold, 800 extra bold. */
    fun of(ctx: Context, weight: Int): Typeface = byWeight.getOrPut(weight) {
        val f = family(ctx)
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(f, weight, false)
        else Typeface.create(f, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
    fun regular(ctx: Context) = of(ctx, 400)
    fun medium(ctx: Context) = of(ctx, 500)
    fun semi(ctx: Context) = of(ctx, 600)
    fun bold(ctx: Context) = of(ctx, 700)
    fun heavy(ctx: Context) = of(ctx, 800)
}

/** Habit AI's line icons (res/drawable/ic_*.xml), coloured in code so one icon works on navy and on white. */
object Ico {
    fun d(ctx: Context, res: Int, color: Int, sizePx: Int = 0): Drawable {
        val dr = ctx.getDrawable(res)!!.mutate()
        dr.setTint(color)
        if (sizePx > 0) dr.setBounds(0, 0, sizePx, sizePx)
        return dr
    }
    /** An icon [iconDp] big in the middle of a [boxDp] square (a round tap area). */
    fun view(ctx: Context, res: Int, color: Int, iconDp: Int = 22, boxDp: Int = 44, desc: String = "", bg: Drawable? = null, onClick: ((View) -> Unit)? = null): ImageView {
        val dp = ctx.resources.displayMetrics.density
        return ImageView(ctx).apply {
            setImageDrawable(ctx.getDrawable(res)!!.mutate())
            imageTintList = ColorStateList.valueOf(color)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = ((boxDp - iconDp) * dp / 2).toInt()
            setPadding(pad, pad, pad, pad)
            if (desc.isNotEmpty()) contentDescription = desc
            if (bg != null) background = bg
            if (onClick != null) {
                setOnClickListener { onClick(it) }
                if (bg == null) background = ripple(ctx)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams((boxDp * dp).toInt(), (boxDp * dp).toInt())
        }
    }
    fun tint(v: ImageView, res: Int, color: Int) { v.setImageDrawable(v.context.getDrawable(res)!!.mutate()); v.imageTintList = ColorStateList.valueOf(color) }
    /** The round "pressed" ripple of the phone's theme (borderless). */
    fun ripple(ctx: Context): Drawable? = try {
        val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val d = a.getDrawable(0); a.recycle(); d
    } catch (e: Exception) { null }
    /** The square "pressed" ripple (for rows). */
    fun rowRipple(ctx: Context): Drawable? = try {
        val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = a.getDrawable(0); a.recycle(); d
    } catch (e: Exception) { null }
    /** Words with an icon in front (or after), in one TextView: "[icon] words". */
    fun withIcon(ctx: Context, res: Int, color: Int, sizePx: Int, words: CharSequence, after: Boolean = false): CharSequence {
        val sb = SpannableStringBuilder()
        val span = ImageSpan(d(ctx, res, color, sizePx), if (Build.VERSION.SDK_INT >= 29) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BASELINE)
        if (after) { sb.append(words).append(" ⁠"); sb.setSpan(span, sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        else { sb.append("⁠ "); sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.append(words) }
        return sb
    }
    /** Sets a TextView's words with a small icon before them. */
    fun label(tv: TextView, res: Int, color: Int, words: CharSequence, sizeDp: Int = 16) {
        tv.text = withIcon(tv.context, res, color, (sizeDp * tv.resources.displayMetrics.density).toInt(), words)
    }
}

/** A voice note's sound bars (the same pattern every time for the same message); the part already played is darker. */
class Wave(ctx: Context, id: String, private val color: Int) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val seed = id.hashCode()
    private val faded = (color and 0x00FFFFFF) or (0x66 shl 24)
    override fun onDraw(c: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val bw = 3f * d; val gap = 2.2f * d
        val n = ((width + gap) / (bw + gap)).toInt().coerceAtLeast(1)
        val h = height.toFloat()
        var r = seed
        for (i in 0 until n) {
            r = r * 1103515245 + 12345
            val k = ((r ushr 16) and 0x7fff) / 32767f
            val bh = (0.22f + 0.78f * k) * h
            val x = i * (bw + gap)
            p.color = if ((i + 0.5f) / n <= progress) color else faded
            c.drawRoundRect(x, (h - bh) / 2, x + bw, (h + bh) / 2, bw / 2, bw / 2, p)
        }
    }
}
