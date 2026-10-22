package com.soulfriend.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** The cartoon puppy, drawn in code so it can change its face at any moment. */
class PuppyView(context: Context) : View(context) {

    enum class Mood { HAPPY, EXCITED, THINKING, SAD, SURPRISED, LOVE, SLEEPY, CONFUSED }

    var mood: Mood = Mood.HAPPY
        set(v) { field = v; moodSince = SystemClock.uptimeMillis(); invalidate() }
    var talking = false
        set(v) { field = v; invalidate() }
    var listening = false
        set(v) { field = v; invalidate() }

    private var moodSince = SystemClock.uptimeMillis()
    private val start = SystemClock.uptimeMillis()

    private val fur = Color.parseColor("#E9B872")
    private val furDark = Color.parseColor("#8A5A2B")
    private val cream = Color.parseColor("#FFF1DC")
    private val ink = Color.parseColor("#2B1D14")
    private val pink = Color.parseColor("#FF8FA8")
    private val tongue = Color.parseColor("#F2667F")
    private val accent = Color.parseColor("#F5A524")
    private val listen = Color.parseColor("#6E7CF4")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val oval = RectF()

    private fun ellipse(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, deg: Float = 0f) {
        fill.color = color
        c.save(); c.rotate(deg, cx, cy); oval.set(cx - rx, cy - ry, cx + rx, cy + ry); c.drawOval(oval, fill); c.restore()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val s = minOf(width, height) / 100f
        c.save()
        c.translate((width - 100 * s) / 2f, (height - 100 * s) / 2f)
        c.scale(s, s)

        // gentle bounce; bigger when excited
        val bounce = when (mood) {
            Mood.EXCITED -> abs(sin(t * 7f)) * -5f
            Mood.SLEEPY -> sin(t * 1.2f) * 0.8f
            else -> sin(t * 2.4f) * 1.4f
        }
        c.translate(0f, bounce)

        // listening glow
        if (listening) {
            val pulse = 0.5f + 0.5f * sin(t * 6f)
            fill.color = listen; fill.alpha = (60 + 60 * pulse).toInt()
            c.drawCircle(50f, 52f, 46f + 2f * pulse, fill); fill.alpha = 255
        }

        // tail (behind body)
        val wagSpeed = when (mood) { Mood.EXCITED, Mood.LOVE -> 16f; Mood.HAPPY -> 9f; Mood.SAD, Mood.SLEEPY -> 0f; else -> 4f }
        val wag = if (wagSpeed == 0f) -25f else sin(t * wagSpeed) * 28f
        c.save(); c.rotate(wag, 70f, 80f)
        line.color = furDark; line.strokeWidth = 6f
        path.reset(); path.moveTo(70f, 80f); path.quadTo(84f, 74f, 86f, 60f); c.drawPath(path, line)
        c.restore()

        // body and paws
        ellipse(c, 50f, 81f, 24f, 15f, fur)
        ellipse(c, 50f, 84f, 12f, 9f, cream)
        ellipse(c, 39f, 94f, 7f, 4.5f, cream)
        ellipse(c, 61f, 94f, 7f, 4.5f, cream)

        // ears
        val earTilt = when (mood) { Mood.SAD, Mood.SLEEPY -> 34f; Mood.EXCITED, Mood.SURPRISED -> 6f; Mood.THINKING, Mood.CONFUSED -> 18f; else -> 16f }
        val earFlap = if (talking) sin(t * 14f) * 3f else 0f
        ellipse(c, 23f, 40f, 9f, 18f, furDark, earTilt + earFlap)
        ellipse(c, 77f, 40f, 9f, 18f, furDark, -earTilt - earFlap)

        // head (tilts when thinking or confused)
        val headTilt = when (mood) { Mood.THINKING -> -8f; Mood.CONFUSED -> 10f; else -> 0f }
        c.save(); c.rotate(headTilt, 50f, 60f)
        fill.color = fur; c.drawCircle(50f, 44f, 29f, fill)
        ellipse(c, 62f, 40f, 9f, 8f, Color.parseColor("#D49B52"))   // eye patch
        ellipse(c, 50f, 58f, 15f, 11f, cream)                         // muzzle

        // cheeks
        if (mood == Mood.HAPPY || mood == Mood.LOVE || mood == Mood.EXCITED) {
            fill.color = pink; fill.alpha = 150
            c.drawCircle(31f, 55f, 4.5f, fill); c.drawCircle(69f, 55f, 4.5f, fill); fill.alpha = 255
        }

        drawEyes(c, now)

        // nose
        ellipse(c, 50f, 52.5f, 5.2f, 3.8f, ink)
        fill.color = Color.WHITE; c.drawCircle(48.5f, 51.5f, 1.1f, fill)

        drawMouth(c, t)
        c.restore()

        drawSymbol(c, t)
        c.restore()

        postInvalidateDelayed(if (mood == Mood.SLEEPY && !talking && !listening) 90L else 33L)
    }

    private fun drawEyes(c: Canvas, now: Long) {
        val blink = ((now / 100) % 45) == 0L
        line.color = ink; line.strokeWidth = 2.6f
        val lx = 39f; val rx = 61f; val ey = 41f
        when {
            mood == Mood.SLEEPY || blink -> {
                c.drawLine(lx - 4f, ey + 1f, lx + 4f, ey + 1f, line); c.drawLine(rx - 4f, ey + 1f, rx + 4f, ey + 1f, line)
            }
            mood == Mood.HAPPY || mood == Mood.EXCITED -> {
                for (x in floatArrayOf(lx, rx)) { path.reset(); path.moveTo(x - 4.5f, ey + 2f); path.quadTo(x, ey - 5f, x + 4.5f, ey + 2f); c.drawPath(path, line) }
            }
            mood == Mood.LOVE -> { heart(c, lx, ey, 5.5f, tongue); heart(c, rx, ey, 5.5f, tongue) }
            else -> {
                val r = when (mood) { Mood.SURPRISED -> 6f; Mood.SAD -> 4f; else -> 4.8f }
                val (dx, dy) = if (mood == Mood.THINKING) Pair(1.8f, -2f) else Pair(0f, 0f)
                fill.color = ink
                c.drawCircle(lx + dx, ey + dy, if (mood == Mood.CONFUSED) r + 1.4f else r, fill)
                c.drawCircle(rx + dx, ey + dy, if (mood == Mood.CONFUSED) r - 1f else r, fill)
                fill.color = Color.WHITE
                c.drawCircle(lx + dx - 1.4f, ey + dy - 1.6f, 1.4f, fill); c.drawCircle(rx + dx - 1.4f, ey + dy - 1.6f, 1.4f, fill)
                if (mood == Mood.SAD) {
                    line.strokeWidth = 2f
                    c.drawLine(lx - 5f, ey - 6f, lx + 4f, ey - 9f, line); c.drawLine(rx - 4f, ey - 9f, rx + 5f, ey - 6f, line)
                }
            }
        }
    }

    private fun drawMouth(c: Canvas, t: Float) {
        line.color = ink; line.strokeWidth = 2.2f
        c.drawLine(50f, 56f, 50f, 59.5f, line)
        if (talking) {
            val open = 2f + abs(sin(t * 18f)) * 4.5f
            ellipse(c, 50f, 63f, 5.5f, open, ink)
            if (open > 4f) ellipse(c, 50f, 63f + open * 0.45f, 3.5f, open * 0.45f, tongue)
            return
        }
        when (mood) {
            Mood.SAD -> { path.reset(); path.moveTo(43f, 65f); path.quadTo(50f, 59f, 57f, 65f); c.drawPath(path, line) }
            Mood.SURPRISED -> ellipse(c, 50f, 64f, 3.5f, 4.2f, ink)
            Mood.CONFUSED, Mood.THINKING -> { path.reset(); path.moveTo(44f, 63f); path.quadTo(48f, 61f, 50f, 63f); path.quadTo(53f, 65f, 57f, 62f); c.drawPath(path, line) }
            Mood.SLEEPY -> { path.reset(); path.moveTo(45f, 62f); path.quadTo(50f, 64f, 55f, 62f); c.drawPath(path, line) }
            else -> {
                path.reset(); path.moveTo(42f, 60f); path.quadTo(46f, 64.5f, 50f, 59.5f); path.quadTo(54f, 64.5f, 58f, 60f); c.drawPath(path, line)
                if (mood == Mood.EXCITED || mood == Mood.HAPPY || mood == Mood.LOVE) {
                    fill.color = tongue; oval.set(46.5f, 61f, 53.5f, 70f); c.drawRoundRect(oval, 3.5f, 3.5f, fill)
                    line.strokeWidth = 1f; line.color = Color.parseColor("#C94861"); c.drawLine(50f, 63f, 50f, 68f, line)
                }
            }
        }
    }

    private fun drawSymbol(c: Canvas, t: Float) {
        text.textSize = 15f; text.color = accent
        when (mood) {
            Mood.THINKING -> { fill.color = accent; for (i in 0..2) { fill.alpha = if (((t * 3).toInt() % 3) >= i) 255 else 70; c.drawCircle(76f + i * 6f, 12f, 2.4f, fill) }; fill.alpha = 255 }
            Mood.CONFUSED -> c.drawText("?", 84f, 18f, text)
            Mood.SURPRISED -> c.drawText("!", 84f, 18f, text)
            Mood.SLEEPY -> { text.textSize = 12f; val y = 18f - (t * 6f % 8f); c.drawText("z", 80f, y, text); text.textSize = 9f; c.drawText("z", 88f, y - 7f, text) }
            Mood.LOVE -> heart(c, 84f, 12f + sin(t * 3f) * 2f, 5f, tongue)
            else -> {}
        }
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        path.reset()
        path.moveTo(cx, cy + r)
        path.cubicTo(cx - r * 2f, cy - r * 0.2f, cx - r * 0.9f, cy - r * 1.6f, cx, cy - r * 0.5f)
        path.cubicTo(cx + r * 0.9f, cy - r * 1.6f, cx + r * 2f, cy - r * 0.2f, cx, cy + r)
        c.drawPath(path, fill)
    }

    companion object {
        fun moodOf(name: String?): Mood = when (name) {
            "excited" -> Mood.EXCITED; "thinking" -> Mood.THINKING; "sad" -> Mood.SAD; "surprised" -> Mood.SURPRISED
            "love" -> Mood.LOVE; "sleepy" -> Mood.SLEEPY; "confused" -> Mood.CONFUSED; else -> Mood.HAPPY
        }
    }
}
