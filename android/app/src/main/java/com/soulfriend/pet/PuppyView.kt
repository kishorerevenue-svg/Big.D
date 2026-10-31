package com.soulfriend.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** The cartoon pet (puppy, kitten, bunny, panda, bear, fox, robot, parrot, mochi, anime kitty or Rangu the chameleon), drawn in code so it can change its face at any moment. */
class PuppyView(context: Context) : View(context) {

    enum class Kind(val key: String, val label: String, val telugu: String) {
        PUPPY("puppy", "Puppy", "కుక్కపిల్ల"), KITTEN("kitten", "Kitten", "పిల్లి"), BUNNY("bunny", "Bunny", "కుందేలు"),
        PANDA("panda", "Panda", "పాండా"), BEAR("bear", "Teddy bear", "ఎలుగుబంటి"), FOX("fox", "Fox", "నక్క"),
        ROBOT("robot", "Robot", "రోబో"), PARROT("parrot", "Parrot", "చిలుక"), MOCHI("mochi", "Mochi", "మోచి"), KITTY("kitty", "Anime kitty", "కిట్టీ"),
        CHAMELEON("chameleon", "Rangu", "ఊసరవెల్లి")
    }

    var kind: Kind = Kind.PUPPY
        set(v) { field = v; applyColors(); invalidate() }

    enum class Mood { HAPPY, EXCITED, THINKING, SAD, SURPRISED, LOVE, SLEEPY, CONFUSED,
        LAUGHING, ANGRY, CRYING, SHY, COOL, WINK, PROUD, SCARED, YAWNING, DIZZY }

    var mood: Mood = Mood.HAPPY
        set(v) { field = v; moodSince = SystemClock.uptimeMillis(); invalidate() }
    var talking = false
        set(v) { field = v; invalidate() }
    var listening = false
        set(v) { field = v; invalidate() }

    private var moodSince = SystemClock.uptimeMillis()
    private val start = SystemClock.uptimeMillis()

    private var fur = Color.parseColor("#E9B872")
    private var furDark = Color.parseColor("#8A5A2B")
    private var cream = Color.parseColor("#FFF1DC")
    private var patch = Color.parseColor("#D49B52")
    private var nose = Color.parseColor("#2B1D14")
    private val ink = Color.parseColor("#2B1D14")
    private var eyeInk = ink
    private val earPink = Color.parseColor("#FFB3C4")

    private fun applyColors() {
        fun c(s: String) = Color.parseColor(s)
        eyeInk = ink; nose = ink; skinReady = false
        when (kind) {
            Kind.PUPPY -> { fur = c("#E9B872"); furDark = c("#8A5A2B"); cream = c("#FFF1DC"); patch = c("#D49B52") }
            Kind.KITTEN -> { fur = c("#B4BACB"); furDark = c("#7D849C"); cream = c("#F6F7FB"); patch = c("#9AA1B6"); nose = c("#F07C98") }
            Kind.BUNNY -> { fur = c("#EFE8F0"); furDark = c("#D9CCDB"); cream = c("#FFFFFF"); patch = c("#EDE4EE"); nose = c("#F07C98") }
            Kind.PANDA -> { fur = c("#FAFAFA"); furDark = c("#2C2C36"); cream = c("#FFFFFF"); patch = c("#2C2C36"); eyeInk = Color.WHITE }
            Kind.BEAR -> { fur = c("#B07A4F"); furDark = c("#7C5131"); cream = c("#EBCDA8"); patch = c("#9C6A42") }
            Kind.FOX -> { fur = c("#F08A3C"); furDark = c("#3B2A22"); cream = c("#FFF6EC"); patch = c("#E07A2E") }
            else -> {}   // robot, parrot, mochi, kitty and Rangu paint their own colours in drawCharacter()
        }
    }
    private val pink = Color.parseColor("#FF8FA8")
    private val tongue = Color.parseColor("#F2667F")
    private val accent = Color.parseColor("#F5A524")
    private val listen = Color.parseColor("#6E7CF4")
    private val tear = Color.parseColor("#6EC6FF")
    private val angryRed = Color.parseColor("#E5484D")

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
            Mood.LAUGHING -> abs(sin(t * 9f)) * -3f
            Mood.SLEEPY, Mood.YAWNING -> sin(t * 1.2f) * 0.8f
            Mood.PROUD -> -2f + sin(t * 2f) * 0.6f
            else -> sin(t * 2.4f) * 1.4f
        }
        val shake = if (mood == Mood.SCARED) sin(t * 45f) * 0.8f else if (mood == Mood.ANGRY) sin(t * 30f) * 0.4f else 0f
        c.translate(shake, bounce)

        // listening glow
        if (listening) {
            val pulse = 0.5f + 0.5f * sin(t * 6f)
            fill.color = listen; fill.alpha = (60 + 60 * pulse).toInt()
            c.drawCircle(50f, 52f, 46f + 2f * pulse, fill); fill.alpha = 255
        }

        if (kind.ordinal >= Kind.ROBOT.ordinal) {
            drawCharacter(c, t, now)
            drawSymbol(c, t)
            c.restore()
            postInvalidateDelayed(if ((mood == Mood.SLEEPY || mood == Mood.YAWNING) && !talking && !listening) 90L else 33L)
            return
        }

        // tail (behind body)
        val wagSpeed = when (mood) {
            Mood.EXCITED, Mood.LOVE -> 16f; Mood.LAUGHING -> 14f; Mood.HAPPY, Mood.WINK -> 9f; Mood.PROUD -> 8f; Mood.COOL -> 5f
            Mood.SAD, Mood.SLEEPY, Mood.CRYING, Mood.SCARED, Mood.YAWNING, Mood.ANGRY -> 0f; Mood.SHY, Mood.DIZZY -> 3f; else -> 4f
        }
        val wag = when {
            mood == Mood.ANGRY -> 12f
            mood == Mood.SCARED -> -40f
            wagSpeed == 0f -> -25f
            else -> sin(t * wagSpeed) * 28f
        }
        c.save(); c.rotate(wag, 70f, 80f)
        when (kind) {
            Kind.PUPPY -> { line.color = furDark; line.strokeWidth = 6f; path.reset(); path.moveTo(70f, 80f); path.quadTo(84f, 74f, 86f, 60f); c.drawPath(path, line) }
            Kind.KITTEN -> { line.color = fur; line.strokeWidth = 5f; path.reset(); path.moveTo(70f, 82f); path.cubicTo(90f, 82f, 84f, 58f, 94f, 56f); c.drawPath(path, line) }
            Kind.FOX -> { ellipse(c, 82f, 70f, 8f, 16f, fur, 35f); ellipse(c, 88f, 60f, 4.5f, 6f, cream, 35f) }
            Kind.BUNNY -> { fill.color = Color.WHITE; c.drawCircle(73f, 84f, 6f, fill) }
            Kind.PANDA, Kind.BEAR -> { fill.color = furDark; c.drawCircle(73f, 84f, 4.5f, fill) }
            else -> {}
        }
        c.restore()

        // body and paws
        ellipse(c, 50f, 81f, 24f, 15f, fur)
        ellipse(c, 50f, 84f, 12f, 9f, cream)
        val paw = if (kind == Kind.PANDA) furDark else cream
        ellipse(c, 39f, 94f, 7f, 4.5f, paw)
        ellipse(c, 61f, 94f, 7f, 4.5f, paw)

        // ears
        val earTilt = when (mood) {
            Mood.SAD, Mood.SLEEPY, Mood.CRYING -> 34f; Mood.SCARED -> 40f; Mood.YAWNING -> 30f; Mood.SHY -> 26f
            Mood.EXCITED, Mood.SURPRISED -> 6f; Mood.ANGRY -> 8f; Mood.LAUGHING, Mood.PROUD -> 10f
            Mood.THINKING, Mood.CONFUSED, Mood.DIZZY -> 20f; else -> 16f
        }
        val earFlap = if (talking) sin(t * 14f) * 3f else 0f
        drawEars(c, earTilt, earFlap)

        // head (tilts when thinking or confused)
        val headTilt = when (mood) {
            Mood.THINKING -> -8f; Mood.CONFUSED -> 10f; Mood.SHY -> 7f; Mood.WINK -> -6f
            Mood.DIZZY -> sin(t * 3f) * 10f; Mood.LAUGHING -> sin(t * 10f) * 3f; else -> 0f
        }
        c.save(); c.rotate(headTilt, 50f, 60f)
        fill.color = fur; c.drawCircle(50f, 44f, 29f, fill)
        when (kind) {
            Kind.PUPPY -> ellipse(c, 62f, 40f, 9f, 8f, patch)                          // eye patch
            Kind.PANDA -> { ellipse(c, 38f, 42f, 8f, 9.5f, patch, 25f); ellipse(c, 62f, 42f, 8f, 9.5f, patch, -25f) }
            Kind.FOX -> { ellipse(c, 34f, 54f, 13f, 10f, cream, -20f); ellipse(c, 66f, 54f, 13f, 10f, cream, 20f) }
            Kind.KITTEN -> { line.color = patch; line.strokeWidth = 2.4f; for (dx in floatArrayOf(-5f, 0f, 5f)) c.drawLine(50f + dx, 17f, 50f + dx * 0.6f, 24f, line) }
            else -> {}
        }
        ellipse(c, 50f, 58f, if (kind == Kind.BUNNY || kind == Kind.KITTEN) 12f else 15f, if (kind == Kind.BUNNY || kind == Kind.KITTEN) 9f else 11f, cream) // muzzle
        if (kind == Kind.KITTEN || kind == Kind.FOX) {   // whiskers
            line.color = if (kind == Kind.FOX) Color.parseColor("#7A4A2A") else Color.parseColor("#6F7690"); line.strokeWidth = 1f
            for (sgn in floatArrayOf(-1f, 1f)) for (dy in floatArrayOf(-2f, 1.5f)) c.drawLine(50f + sgn * 9f, 56f + dy, 50f + sgn * 22f, 54f + dy * 2f, line)
        }

        // cheeks
        if (mood in setOf(Mood.HAPPY, Mood.LOVE, Mood.EXCITED, Mood.LAUGHING, Mood.WINK, Mood.PROUD, Mood.SHY)) {
            val big = mood == Mood.SHY
            fill.color = pink; fill.alpha = if (big) 230 else 150
            c.drawCircle(31f, 55f, if (big) 6f else 4.5f, fill); c.drawCircle(69f, 55f, if (big) 6f else 4.5f, fill); fill.alpha = 255
            if (big) {
                line.color = Color.parseColor("#E0607E"); line.strokeWidth = 1.2f
                for (cx in floatArrayOf(31f, 69f)) for (i in -1..1) c.drawLine(cx - 2f + i * 2.4f, 57f, cx + i * 2.4f, 53f, line)
            }
        }

        drawEyes(c, now)

        // nose
        if (kind == Kind.KITTEN || kind == Kind.BUNNY) {
            fill.color = nose; path.reset(); path.moveTo(46.5f, 51f); path.lineTo(53.5f, 51f); path.lineTo(50f, 55f); path.close(); c.drawPath(path, fill)
        } else ellipse(c, 50f, 52.5f, 5.2f, 3.8f, nose)
        fill.color = Color.WHITE; c.drawCircle(48.5f, 51.5f, 1.1f, fill)

        drawMouth(c, t)
        c.restore()

        drawSymbol(c, t)
        c.restore()

        postInvalidateDelayed(if ((mood == Mood.SLEEPY || mood == Mood.YAWNING) && !talking && !listening) 90L else 33L)
    }

    /** Eyes in the face space (eyes at 39/61, y 41). [style]: EYE_NORMAL, EYE_ANIME, EYE_GLOW or EYE_RINGED. */
    private fun drawEyes(c: Canvas, now: Long, inkC: Int = eyeInk, style: Int = EYE_NORMAL) {
        val bleyeInk = ((now / 100) % 45) == 0L
        line.color = inkC; line.strokeWidth = 2.6f
        val lx = 39f; val rx = 61f; val ey = 41f
        when {
            mood == Mood.SLEEPY || bleyeInk -> {
                c.drawLine(lx - 4f, ey + 1f, lx + 4f, ey + 1f, line); c.drawLine(rx - 4f, ey + 1f, rx + 4f, ey + 1f, line)
            }
            mood == Mood.HAPPY || mood == Mood.EXCITED -> {
                for (x in floatArrayOf(lx, rx)) { path.reset(); path.moveTo(x - 4.5f, ey + 2f); path.quadTo(x, ey - 5f, x + 4.5f, ey + 2f); c.drawPath(path, line) }
            }
            mood == Mood.LOVE -> { val h = if (style == EYE_GLOW) Color.parseColor("#FF7FB0") else tongue; heart(c, lx, ey, 5.5f, h); heart(c, rx, ey, 5.5f, h) }
            mood == Mood.LAUGHING -> {
                path.reset(); path.moveTo(lx - 4f, ey - 3.5f); path.lineTo(lx + 3f, ey); path.lineTo(lx - 4f, ey + 3.5f); c.drawPath(path, line)
                path.reset(); path.moveTo(rx + 4f, ey - 3.5f); path.lineTo(rx - 3f, ey); path.lineTo(rx + 4f, ey + 3.5f); c.drawPath(path, line)
            }
            mood == Mood.CRYING || mood == Mood.PROUD -> {
                for (x in floatArrayOf(lx, rx)) { path.reset(); path.moveTo(x - 4.5f, ey - 1f); path.quadTo(x, ey + 4f, x + 4.5f, ey - 1f); c.drawPath(path, line) }
                if (mood == Mood.CRYING) {
                    line.strokeWidth = 2f
                    c.drawLine(lx - 5f, ey - 6f, lx + 4f, ey - 9f, line); c.drawLine(rx - 4f, ey - 9f, rx + 5f, ey - 6f, line)
                    val fall = ((now - start) / 1000f * 14f) % 12f
                    drop(c, lx - 1f, ey + 6f + fall, 2.2f, tear); drop(c, rx + 1f, ey + 6f + (fall + 6f) % 12f, 2.2f, tear)
                }
            }
            mood == Mood.COOL -> {
                fill.color = Color.parseColor("#1E1E2A")
                oval.set(lx - 7.5f, ey - 5f, lx + 6f, ey + 4.5f); c.drawRoundRect(oval, 3.5f, 3.5f, fill)
                oval.set(rx - 6f, ey - 5f, rx + 7.5f, ey + 4.5f); c.drawRoundRect(oval, 3.5f, 3.5f, fill)
                line.color = Color.parseColor("#1E1E2A"); line.strokeWidth = 2f; c.drawLine(lx + 6f, ey - 2f, rx - 6f, ey - 2f, line)
                line.color = Color.WHITE; line.strokeWidth = 1.4f; c.drawLine(lx - 4.5f, ey - 2.5f, lx - 2f, ey - 2.5f, line); c.drawLine(rx - 3f, ey - 2.5f, rx - 0.5f, ey - 2.5f, line)
            }
            mood == Mood.WINK -> {
                normalEye(c, lx, ey, 4.8f, inkC, style)
                line.color = inkC; line.strokeWidth = 2.6f
                path.reset(); path.moveTo(rx - 4.5f, ey + 2f); path.quadTo(rx, ey - 5f, rx + 4.5f, ey + 2f); c.drawPath(path, line)
            }
            mood == Mood.SCARED -> {
                for (x in floatArrayOf(lx, rx)) {
                    fill.color = Color.WHITE; c.drawCircle(x, ey, 6.2f, fill)
                    line.color = inkC; line.strokeWidth = 1.4f; c.drawCircle(x, ey, 6.2f, line)
                    fill.color = if (style == EYE_GLOW) Color.parseColor("#0B2B2C") else inkC; c.drawCircle(x, ey + 0.5f, 2.2f, fill)
                }
                line.strokeWidth = 2f
                c.drawLine(lx - 5f, ey - 9f, lx + 4f, ey - 11f, line); c.drawLine(rx - 4f, ey - 11f, rx + 5f, ey - 9f, line)
            }
            mood == Mood.YAWNING -> {
                for (x in floatArrayOf(lx, rx)) { path.reset(); path.moveTo(x - 4.5f, ey); path.quadTo(x, ey + 2.5f, x + 4.5f, ey); c.drawPath(path, line) }
            }
            mood == Mood.DIZZY -> {
                line.strokeWidth = 2.2f
                for (x in floatArrayOf(lx, rx)) { c.drawLine(x - 3.5f, ey - 3.5f, x + 3.5f, ey + 3.5f, line); c.drawLine(x - 3.5f, ey + 3.5f, x + 3.5f, ey - 3.5f, line) }
            }
            else -> {
                val r = when (mood) { Mood.SURPRISED -> 6f; Mood.SAD -> 4f; Mood.ANGRY -> 4f; Mood.SHY -> 4.2f; else -> 4.8f }
                val (dx, dy) = when (mood) { Mood.THINKING -> Pair(1.8f, -2f); Mood.SHY -> Pair(-1f, 2.5f); else -> Pair(0f, 0f) }
                normalEye(c, lx + dx, ey + dy, if (mood == Mood.CONFUSED) r + 1.4f else r, inkC, style)
                normalEye(c, rx + dx, ey + dy, if (mood == Mood.CONFUSED) r - 1f else r, inkC, style)
                line.color = inkC
                if (mood == Mood.SAD) {
                    line.strokeWidth = 2f
                    c.drawLine(lx - 5f, ey - 6f, lx + 4f, ey - 9f, line); c.drawLine(rx - 4f, ey - 9f, rx + 5f, ey - 6f, line)
                }
                if (mood == Mood.ANGRY) {
                    line.strokeWidth = 2.4f
                    c.drawLine(lx - 5f, ey - 10f, lx + 4f, ey - 5.5f, line); c.drawLine(rx - 4f, ey - 5.5f, rx + 5f, ey - 10f, line)
                }
            }
        }
    }

    /** Mouth in the face space (around y 56-70). */
    private fun drawMouth(c: Canvas, t: Float, mInk: Int = ink, tng: Int = tongue, philtrum: Boolean = true) {
        line.color = mInk; line.strokeWidth = 2.2f
        if (philtrum) c.drawLine(50f, 56f, 50f, 59.5f, line)
        if (talking) {
            val open = 2f + abs(sin(t * 18f)) * 4.5f
            ellipse(c, 50f, 63f, 5.5f, open, mInk)
            if (open > 4f) ellipse(c, 50f, 63f + open * 0.45f, 3.5f, open * 0.45f, tng)
            return
        }
        when (mood) {
            Mood.SAD -> { path.reset(); path.moveTo(43f, 65f); path.quadTo(50f, 59f, 57f, 65f); c.drawPath(path, line) }
            Mood.SURPRISED -> ellipse(c, 50f, 64f, 3.5f, 4.2f, mInk)
            Mood.CONFUSED, Mood.THINKING -> { path.reset(); path.moveTo(44f, 63f); path.quadTo(48f, 61f, 50f, 63f); path.quadTo(53f, 65f, 57f, 62f); c.drawPath(path, line) }
            Mood.SLEEPY -> { path.reset(); path.moveTo(45f, 62f); path.quadTo(50f, 64f, 55f, 62f); c.drawPath(path, line) }
            Mood.LAUGHING -> {
                fill.color = mInk; path.reset(); path.moveTo(41f, 60f); path.quadTo(50f, 77f, 59f, 60f); path.close(); c.drawPath(path, fill)
                ellipse(c, 50f, 68f, 4.5f, 3f, tng)
            }
            Mood.ANGRY -> { line.strokeWidth = 2.4f; path.reset(); path.moveTo(44f, 65f); path.quadTo(50f, 61f, 56f, 65f); c.drawPath(path, line) }
            Mood.CRYING -> { ellipse(c, 50f, 64.5f, 4.5f, 3.2f, mInk); path.reset(); path.moveTo(43f, 67f); path.quadTo(50f, 59f, 57f, 67f); c.drawPath(path, line) }
            Mood.SHY -> { path.reset(); path.moveTo(46f, 62f); path.quadTo(50f, 65f, 54f, 62f); c.drawPath(path, line) }
            Mood.COOL -> { path.reset(); path.moveTo(44f, 62f); path.quadTo(51f, 65.5f, 57f, 60f); c.drawPath(path, line) }
            Mood.PROUD -> { line.strokeWidth = 2.4f; path.reset(); path.moveTo(42f, 61f); path.quadTo(50f, 67.5f, 58f, 61f); c.drawPath(path, line) }
            Mood.SCARED -> { ellipse(c, 50f, 64.5f, 5f, 3.5f, mInk); fill.color = Color.WHITE; oval.set(46f, 61.5f, 54f, 63.5f); c.drawRect(oval, fill) }
            Mood.YAWNING -> { ellipse(c, 50f, 66f, 5.5f, 7.5f, mInk); ellipse(c, 50f, 70.5f, 3.8f, 2.8f, tng) }
            Mood.DIZZY -> { path.reset(); path.moveTo(43f, 63f); path.quadTo(46.5f, 60f, 50f, 63f); path.quadTo(53.5f, 66f, 57f, 63f); c.drawPath(path, line) }
            else -> {
                path.reset(); path.moveTo(42f, 60f); path.quadTo(46f, 64.5f, 50f, 59.5f); path.quadTo(54f, 64.5f, 58f, 60f); c.drawPath(path, line)
                if (kind == Kind.BUNNY) {
                    fill.color = Color.WHITE; oval.set(47.3f, 60.5f, 52.7f, 66f); c.drawRoundRect(oval, 1.2f, 1.2f, fill)
                    line.strokeWidth = 0.9f; c.drawRoundRect(oval, 1.2f, 1.2f, line); c.drawLine(50f, 60.5f, 50f, 66f, line)
                    return
                }
                if (mood == Mood.EXCITED || mood == Mood.HAPPY || mood == Mood.LOVE || mood == Mood.WINK) {
                    fill.color = tng; oval.set(46.5f, 61f, 53.5f, 70f); c.drawRoundRect(oval, 3.5f, 3.5f, fill)
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
            Mood.LAUGHING -> { text.textSize = 11f; c.drawText("HA", 84f, 16f + sin(t * 9f) * 1.5f, text); text.textSize = 8f; c.drawText("ha", 91f, 7f, text) }
            Mood.ANGRY -> {
                line.color = angryRed; line.strokeWidth = 2.2f
                val x = 82f; val y = 14f
                c.drawLine(x - 5f, y - 1.5f, x - 1.5f, y - 1.5f, line); c.drawLine(x - 1.5f, y - 5f, x - 1.5f, y - 1.5f, line)
                c.drawLine(x + 1.5f, y - 5f, x + 1.5f, y - 1.5f, line); c.drawLine(x + 1.5f, y - 1.5f, x + 5f, y - 1.5f, line)
                c.drawLine(x - 5f, y + 1.5f, x - 1.5f, y + 1.5f, line); c.drawLine(x - 1.5f, y + 1.5f, x - 1.5f, y + 5f, line)
                c.drawLine(x + 1.5f, y + 1.5f, x + 5f, y + 1.5f, line); c.drawLine(x + 1.5f, y + 1.5f, x + 1.5f, y + 5f, line)
            }
            Mood.COOL -> star(c, 84f, 14f, 4f + sin(t * 4f), accent)
            Mood.WINK -> star(c, 76f, 26f, 3.5f, accent)
            Mood.PROUD -> { star(c, 82f, 12f, 4.5f, accent); star(c, 90f, 22f, 2.8f, accent); star(c, 18f, 14f, 3f, accent) }
            Mood.SCARED -> drop(c, 78f, 22f + (t * 3f % 3f), 3f, tear)
            Mood.DIZZY -> for (i in 0..2) {
                val a = t * 3f + i * 2.094f
                star(c, 50f + kotlin.math.cos(a) * 16f, 12f + sin(a) * 4f, 2.8f, accent)
            }
            else -> {}
        }
    }

    /** Ears differ for each animal; [tilt] is bigger when the pet feels low. */
    private fun drawEars(c: Canvas, tilt: Float, flap: Float) {
        val droop = (tilt - 16f) * 1.1f + flap
        when (kind) {
            Kind.PUPPY -> {
                ellipse(c, 23f, 40f, 9f, 18f, furDark, tilt + flap)
                ellipse(c, 77f, 40f, 9f, 18f, furDark, -tilt - flap)
            }
            Kind.KITTEN, Kind.FOX -> for (sgn in floatArrayOf(-1f, 1f)) {
                c.save(); c.rotate(sgn * (-8f + droop), 50f + sgn * 20f, 28f)
                val bx = 50f + sgn * 20f
                fill.color = fur
                path.reset(); path.moveTo(bx - 12f, 30f); path.lineTo(bx + sgn * 6f, if (kind == Kind.FOX) 2f else 6f); path.lineTo(bx + 12f, 30f); path.close(); c.drawPath(path, fill)
                fill.color = if (kind == Kind.FOX) furDark else earPink
                path.reset(); path.moveTo(bx - 6f, 26f); path.lineTo(bx + sgn * 4.5f, if (kind == Kind.FOX) 7f else 11f); path.lineTo(bx + 6f, 26f); path.close(); c.drawPath(path, fill)
                c.restore()
            }
            Kind.BUNNY -> for (sgn in floatArrayOf(-1f, 1f)) {
                c.save(); c.rotate(sgn * (6f + droop * 1.6f), 50f + sgn * 11f, 22f)
                ellipse(c, 50f + sgn * 11f, 14f, 7f, 15f, fur)
                ellipse(c, 50f + sgn * 11f, 14.5f, 3.8f, 11f, earPink)
                c.restore()
            }
            Kind.PANDA, Kind.BEAR -> for (sgn in floatArrayOf(-1f, 1f)) {
                val ex = 50f + sgn * (23f + droop * 0.15f); val ey = 20f + droop * 0.2f
                fill.color = furDark; c.drawCircle(ex, ey, 9.5f, fill)
                if (kind == Kind.BEAR) { fill.color = cream; c.drawCircle(ex, ey + 1f, 5f, fill) }
            }
            else -> {}
        }
    }

    // ===================== robot, parrot, mochi, anime kitty =====================
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private fun col(hex: String) = Color.parseColor(hex)
    private fun rad(x: Float, y: Float, r: Float, vararg cs: Pair<Float, Int>): Paint {
        gp.shader = RadialGradient(x, y, r, cs.map { it.second }.toIntArray(), cs.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP); return gp
    }
    private fun lin(x1: Float, y1: Float, x2: Float, y2: Float, vararg cs: Pair<Float, Int>): Paint {
        gp.shader = LinearGradient(x1, y1, x2, y2, cs.map { it.second }.toIntArray(), cs.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP); return gp
    }
    private fun ovalP(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, p: Paint, deg: Float = 0f) {
        c.save(); c.rotate(deg, cx, cy); oval.set(cx - rx, cy - ry, cx + rx, cy + ry); c.drawOval(oval, p); c.restore()
    }
    private fun rrect(c: Canvas, l: Float, t: Float, w: Float, h: Float, r: Float, p: Paint) { oval.set(l, t, l + w, t + h); c.drawRoundRect(oval, r, r, p) }
    /** Draw the shared face parts somewhere else: (rx,ry) in face space lands on (cx,cy), scaled by s. */
    private inline fun at(c: Canvas, cx: Float, cy: Float, s: Float, rx: Float, ry: Float, f: () -> Unit) {
        c.save(); c.translate(cx, cy); c.scale(s, s); c.translate(-rx, -ry); f(); c.restore()
    }
    private fun glow(on: Boolean, color: Int = 0, r: Float = 2.5f) {
        if (on) { fill.setShadowLayer(r, 0f, 0f, color); line.setShadowLayer(r, 0f, 0f, color) } else { fill.clearShadowLayer(); line.clearShadowLayer() }
    }

    private fun normalEye(c: Canvas, cx: Float, cy: Float, r: Float, inkC: Int, style: Int) {
        when (style) {
            EYE_ANIME -> {
                val k = r / 4.8f
                ellipse(c, cx, cy, 6.6f * k, 7.6f * k, col("#2A2140"))
                ovalP(c, cx, cy + 0.8f * k, 5.4f * k, 6.2f * k, lin(cx, cy - 6f * k, cx, cy + 6f * k, 0f to col("#2C6CF0"), 0.55f to col("#4FA8FF"), 1f to col("#A6ECFF")))
                ellipse(c, cx, cy + 1.2f * k, 2.4f * k, 3f * k, col("#14102A"))
                ellipse(c, cx - 2.4f * k, cy - 2.6f * k, 2.4f * k, 2.7f * k, Color.WHITE)
                fill.color = Color.WHITE; c.drawCircle(cx + 2.4f * k, cy + 3.4f * k, 1.1f * k, fill)
            }
            EYE_GLOW -> {
                fill.color = inkC; rrect(c, cx - r * 0.9f, cy - r * 1.25f, r * 1.8f, r * 2.5f, r * 0.9f, fill)
                fill.color = Color.argb(230, 255, 255, 255); c.drawCircle(cx - r * 0.3f, cy - r * 0.6f, r * 0.32f, fill)
            }
            EYE_RINGED -> {
                fill.color = Color.WHITE; c.drawCircle(cx, cy, r + 2.4f, fill)
                line.color = col("#F2A93B"); line.strokeWidth = 1.2f; c.drawCircle(cx, cy, r + 2.4f, line)
                fill.color = inkC; c.drawCircle(cx, cy, r, fill)
                fill.color = Color.WHITE; c.drawCircle(cx - 1.4f, cy - 1.6f, 1.4f, fill)
                line.strokeWidth = 2.6f
            }
            else -> {
                fill.color = inkC; c.drawCircle(cx, cy, r, fill)
                fill.color = Color.WHITE; c.drawCircle(cx - 1.4f, cy - 1.6f, 1.4f, fill)
            }
        }
    }

    private fun drawCharacter(c: Canvas, t: Float, now: Long) {
        val low = mood in setOf(Mood.SAD, Mood.SLEEPY, Mood.CRYING, Mood.SCARED, Mood.YAWNING)
        val blush = mood in setOf(Mood.HAPPY, Mood.LOVE, Mood.EXCITED, Mood.LAUGHING, Mood.WINK, Mood.PROUD, Mood.SHY)
        val wagSpeed = when (mood) {
            Mood.EXCITED, Mood.LOVE -> 16f; Mood.LAUGHING -> 14f; Mood.HAPPY, Mood.WINK -> 9f; Mood.PROUD -> 8f; Mood.COOL -> 5f
            Mood.SAD, Mood.SLEEPY, Mood.CRYING, Mood.SCARED, Mood.YAWNING, Mood.ANGRY -> 0f; Mood.SHY, Mood.DIZZY -> 3f; else -> 4f
        }
        val wag = when { mood == Mood.ANGRY -> 12f; mood == Mood.SCARED -> -40f; wagSpeed == 0f -> -25f; else -> sin(t * wagSpeed) * 28f }
        val tilt = when (mood) {
            Mood.SAD, Mood.SLEEPY, Mood.CRYING -> 34f; Mood.SCARED -> 40f; Mood.YAWNING -> 30f; Mood.SHY -> 26f
            Mood.EXCITED, Mood.SURPRISED -> 6f; Mood.ANGRY -> 8f; Mood.LAUGHING, Mood.PROUD -> 10f
            Mood.THINKING, Mood.CONFUSED, Mood.DIZZY -> 20f; else -> 16f
        }
        val ht = when (mood) {
            Mood.THINKING -> -8f; Mood.CONFUSED -> 10f; Mood.SHY -> 7f; Mood.WINK -> -6f
            Mood.DIZZY -> sin(t * 3f) * 10f; Mood.LAUGHING -> sin(t * 10f) * 3f; else -> 0f
        }
        when (kind) {
            Kind.ROBOT -> {
                val pulse = 0.5f + 0.5f * sin(t * 3f)
                ellipse(c, 50f, 95f, 20f, 2.6f, Color.argb(46, 14, 107, 107))
                ellipse(c, 50f, 88f, 10f + pulse * 1.5f, 2.8f, Color.argb(((0.25f + 0.2f * pulse) * 255).toInt(), 80, 230, 220))
                line.color = col("#B9D6D2"); line.strokeWidth = 4.2f
                path.reset(); path.moveTo(35f, 68f); path.quadTo(26f, 70f, 25f, 62f); c.drawPath(path, line)
                fill.color = col("#E9F6F4"); c.drawCircle(24.5f, 60.5f, 3.6f, fill)
                c.save(); c.rotate(if (low) 20f else -10f + wag * 0.5f, 64f, 68f)
                path.reset(); path.moveTo(65f, 68f); path.quadTo(74f, 66f, 77f, 58f); c.drawPath(path, line)
                c.drawCircle(77.5f, 56.5f, 3.6f, fill); c.restore()
                rrect(c, 34f, 61f, 32f, 24f, 10f, lin(34f, 61f, 66f, 85f, 0f to col("#F2FBF9"), 1f to col("#B9D6D2")))
                glow(true, col("#3FE0D0"), 3f)
                fill.color = if (talking) col("#7FFFF2") else col("#3FE0D0"); c.drawCircle(50f, 72f, 4.6f, fill)
                glow(false)
                text.textSize = 5f; text.color = col("#0A4F50"); c.drawText("D", 50f, 74.2f, text)
                c.save(); c.rotate(ht, 50f, 60f)
                line.color = col("#5B7073"); line.strokeWidth = 2f; c.drawLine(50f, 18f, 50f, 9f, line)
                fill.setShadowLayer(4f, 0f, 0f, col("#F6B53D"))
                fill.color = if (talking && (t * 6f).toInt() % 2 == 1) col("#FFF2B0") else col("#FFD36E"); c.drawCircle(50f, 7.5f, 3.6f, fill)
                fill.clearShadowLayer()
                fill.color = col("#0E6B6B"); rrect(c, 15f, 33f, 6f, 16f, 3f, fill); rrect(c, 79f, 33f, 6f, 16f, 3f, fill)
                rrect(c, 19f, 17f, 62f, 47f, 18f, lin(19f, 17f, 81f, 64f, 0f to Color.WHITE, 0.6f to col("#DCEEEB"), 1f to col("#A9CBC6")))
                rrect(c, 25f, 23f, 50f, 35f, 13f, lin(25f, 23f, 75f, 58f, 0f to col("#143A3C"), 1f to col("#071A1B")))
                fill.color = Color.argb(26, 255, 255, 255); rrect(c, 28f, 25f, 24f, 5f, 2.5f, fill)
                val g = if (mood == Mood.SLEEPY) col("#2FA79D") else col("#5FF2E2")
                glow(true, col("#3FE0D0"))
                at(c, 50f, 40.5f, 0.82f, 50f, 41f) { drawEyes(c, now, g, EYE_GLOW) }
                at(c, 50f, 49.5f, 0.62f, 50f, 62f) { drawMouth(c, t, g, col("#B9FFF7"), philtrum = false) }
                glow(false)
                if (blush) {
                    fill.color = Color.argb(204, 255, 130, 160)
                    for ((x, y) in listOf(28.5f to 45f, 31f to 45f, 66.5f to 45f, 69f to 45f)) c.drawRect(x, y, x + 1.8f, y + 1.8f, fill)
                }
                c.restore()
            }
            Kind.PARROT -> {
                line.color = col("#8A5A2B"); line.strokeWidth = 4.2f
                path.reset(); path.moveTo(8f, 90f); path.quadTo(50f, 86f, 92f, 91f); c.drawPath(path, line)
                ellipse(c, 84f, 86f, 5f, 2.4f, col("#5BB55B"), -25f); ellipse(c, 16f, 87.5f, 4f, 2f, col("#5BB55B"), 20f)
                c.save(); c.rotate(wag * 0.25f, 50f, 78f)
                path.reset(); path.moveTo(45f, 78f); path.quadTo(44f, 92f, 40f, 99f); path.lineTo(48f, 99f); path.quadTo(51f, 90f, 55f, 78f); path.close()
                c.drawPath(path, lin(45f, 78f, 45f, 99f, 0f to col("#2E9E5B"), 1f to col("#2F7DB8"))); c.restore()
                line.color = col("#E58A3A"); line.strokeWidth = 1.8f
                for (x in floatArrayOf(44f, 56f)) { c.drawLine(x, 85f, x - 2f, 90f, line); c.drawLine(x, 85f, x + 2f, 90f, line) }
                val flap = if (talking) sin(t * 16f) * 6f else if (mood == Mood.EXCITED) sin(t * 14f) * 10f else 0f
                for (sg in floatArrayOf(-1f, 1f)) {
                    c.save(); c.rotate(sg * (if (low) -4f else flap), 50f + sg * 20f, 50f)
                    path.reset(); path.moveTo(50f + sg * 20f, 46f); path.cubicTo(50f + sg * 34f, 56f, 50f + sg * 32f, 76f, 50f + sg * 20f, 84f)
                    path.cubicTo(50f + sg * 15f, 70f, 50f + sg * 15f, 56f, 50f + sg * 20f, 46f); path.close()
                    c.drawPath(path, lin(50f, 46f, 50f, 84f, 0f to col("#3BAA4E"), 1f to col("#1E7A3E"))); c.restore()
                }
                c.save(); c.rotate(ht, 50f, 60f)
                path.reset(); path.moveTo(50f, 18f); path.cubicTo(74f, 18f, 78f, 56f, 72f, 70f); path.quadTo(64f, 88f, 50f, 88f)
                path.quadTo(36f, 88f, 28f, 70f); path.cubicTo(22f, 56f, 26f, 18f, 50f, 18f); path.close()
                c.drawPath(path, rad(42f, 32f, 64f, 0f to col("#A6F08A"), 0.55f to col("#4FC75E"), 1f to col("#25944C")))
                ellipse(c, 50f, 72f, 13f, 12f, Color.argb(140, 225, 255, 175))
                c.save(); c.rotate(if (low) -15f else sin(t * 2f) * 5f, 50f, 20f); fill.color = col("#7EDB6A")
                path.reset(); path.moveTo(47f, 20f); path.quadTo(43f, 10f, 49f, 7f); path.quadTo(48f, 13f, 53f, 19f); path.close(); c.drawPath(path, fill)
                path.reset(); path.moveTo(51f, 20f); path.quadTo(54f, 12f, 59f, 12f); path.quadTo(55f, 16f, 55f, 21f); path.close(); c.drawPath(path, fill)
                c.restore()
                if (blush) { ellipse(c, 33f, 50f, 4.5f, 3f, Color.argb(140, 255, 143, 168)); ellipse(c, 67f, 50f, 4.5f, 3f, Color.argb(140, 255, 143, 168)) }
                at(c, 50f, 38f, 1.05f, 50f, 41f) { drawEyes(c, now, ink, EYE_RINGED) }
                val open = if (talking) 1.5f + abs(sin(t * 18f)) * 3.5f else when (mood) { Mood.LAUGHING -> 4f; Mood.SURPRISED -> 3f; Mood.YAWNING -> 5f; Mood.SCARED -> 2.5f; else -> 0f }
                fill.color = col("#8E1A12")
                path.reset(); path.moveTo(45f, 52f + open * 0.5f); path.quadTo(50f, 62f + open, 55f, 52f + open * 0.5f); path.close(); c.drawPath(path, fill)
                path.reset(); path.moveTo(43.5f, 48f); path.quadTo(50f, 43.5f, 56.5f, 48f); path.quadTo(57f, 55f, 50f, 60f); path.quadTo(43f, 55f, 43.5f, 48f); path.close()
                c.drawPath(path, lin(44f, 44f, 56f, 60f, 0f to col("#FF6A45"), 1f to col("#C8261A")))
                ellipse(c, 47.5f, 47.5f, 2.2f, 1.2f, Color.argb(128, 255, 255, 255))
                c.restore()
            }
            Kind.MOCHI -> {
                ellipse(c, 50f, 95f, 24f, 3f, Color.argb(36, 19, 42, 44))
                val squash = if (talking) sin(t * 18f) * 0.035f else if (mood == Mood.EXCITED) sin(t * 7f) * 0.05f else sin(t * 2.4f) * 0.015f
                c.save(); c.scale(1f + squash, 1f - squash, 50f, 92f)
                c.save(); c.rotate(ht, 50f, 70f)
                c.save(); c.rotate(if (low) 25f else sin(t * 3f) * 8f, 50f, 25f)
                line.color = col("#4CAF50"); line.strokeWidth = 1.6f
                path.reset(); path.moveTo(50f, 25f); path.quadTo(48.5f, 18f, 51f, 14f); c.drawPath(path, line)
                ellipse(c, 46f, 14f, 4.5f, 2.5f, col("#7BD67E"), -25f); ellipse(c, 55f, 13.5f, 4.5f, 2.5f, col("#5CC062"), 25f)
                c.restore()
                val ed = if (low) 3f else 0f
                ellipse(c, 30f, 32f + ed, 6.6f, 6.2f, col("#F7C9B5")); ellipse(c, 70f, 32f + ed, 6.6f, 6.2f, col("#F7C9B5"))
                ellipse(c, 30f, 32.5f + ed, 3.3f, 3f, col("#FF9FB6")); ellipse(c, 70f, 32.5f + ed, 3.3f, 3f, col("#FF9FB6"))
                path.reset(); path.moveTo(50f, 25f); path.cubicTo(82.5f, 25f, 89.2f, 62.5f, 81.7f, 81.7f); path.quadTo(77.5f, 92.5f, 50f, 92.5f)
                path.quadTo(22.5f, 92.5f, 18.3f, 81.7f); path.cubicTo(10.8f, 62.5f, 17.5f, 25f, 50f, 25f); path.close()
                c.drawPath(path, rad(41f, 41f, 62f, 0f to col("#FFF6EF"), 0.55f to col("#FFDCCB"), 1f to col("#F2B79E")))
                ellipse(c, 38f, 35f, 11f, 5f, Color.argb(153, 255, 255, 255), -18f)
                ellipse(c, 20f, 77f, 4.6f, 3.8f, col("#FFD4C0"), -30f); ellipse(c, 80f, 77f, 4.6f, 3.8f, col("#FFD4C0"), 30f)
                for (x in floatArrayOf(27.5f, 72.5f)) c.drawCircle(x, 69f, 7.5f, rad(x, 69f, 7.5f, 0f to Color.argb(if (blush) 179 else 102, 255, 120, 150), 1f to Color.argb(0, 255, 120, 150)))
                at(c, 50f, 58.5f, 1.15f, 50f, 41f) { drawEyes(c, now, col("#3B2A55"), EYE_ANIME) }
                at(c, 50f, 68f, 0.62f, 50f, 60f) { drawMouth(c, t, col("#5A3A30"), col("#FF7F98"), philtrum = false) }
                c.restore(); c.restore()
            }
            Kind.CHAMELEON -> drawRangu(c, t, now)
            else -> {   // KITTY
                ellipse(c, 50f, 97.5f, 21f, 3.2f, Color.argb(38, 19, 42, 44))
                c.save(); c.rotate(wag * 0.6f, 63f, 89f)
                line.color = col("#D9D3EE"); line.strokeWidth = 5.4f
                path.reset(); path.moveTo(63f, 89f); path.cubicTo(83f, 89f, 82f, 69f, 89f, 67f); c.drawPath(path, line); c.restore()
                ovalP(c, 50f, 85f, 15f, 12.5f, rad(46f, 81f, 18f, 0f to Color.WHITE, 1f to col("#DCD6F0")))
                ellipse(c, 43.3f, 95f, 5.4f, 3.3f, Color.WHITE); ellipse(c, 56.7f, 95f, 5.4f, 3.3f, Color.WHITE)
                c.save(); c.rotate(ht, 50f, 60f)
                for (sg in floatArrayOf(-1f, 1f)) {
                    c.save(); c.rotate(sg * (tilt - 16f) * 0.6f, 50f + sg * 20f, 30f)
                    fill.color = col("#E6E1F5"); path.reset(); path.moveTo(50f + sg * 29.2f, 35f); path.lineTo(50f + sg * 25.8f, 9.2f); path.lineTo(50f + sg * 10.8f, 21.7f); path.close(); c.drawPath(path, fill)
                    fill.color = col("#FFB3C8"); path.reset(); path.moveTo(50f + sg * 25.8f, 30f); path.lineTo(50f + sg * 24.2f, 15f); path.lineTo(50f + sg * 15f, 23.3f); path.close(); c.drawPath(path, fill)
                    c.restore()
                }
                ovalP(c, 50f, 43.3f, 34.2f, 29.2f, rad(41f, 32f, 50f, 0f to Color.WHITE, 0.6f to col("#EEEAF8"), 1f to col("#CFC7EA")))
                fill.color = col("#FF5C8A")
                path.reset(); path.moveTo(66.7f, 19.2f); path.lineTo(77.5f, 13.3f); path.lineTo(76.7f, 25.8f); path.close(); c.drawPath(path, fill)
                path.reset(); path.moveTo(66.7f, 19.2f); path.lineTo(58.3f, 12.5f); path.lineTo(60f, 24.2f); path.close(); c.drawPath(path, fill)
                fill.color = col("#E23D6E"); c.drawCircle(67f, 19.2f, 2.9f, fill)
                line.color = Color.argb(if (blush) 230 else 140, 255, 110, 150); line.strokeWidth = 0.9f
                for (x in floatArrayOf(25.8f, 74.2f)) for (d in floatArrayOf(-2.5f, 0f, 2.5f)) c.drawLine(x + d - 0.8f, 61.7f, x + d + 1.2f, 59.2f, line)
                at(c, 50f, 45.5f, 1.22f, 50f, 41f) { drawEyes(c, now, col("#2A2140"), EYE_ANIME) }
                fill.color = col("#FF7FA0"); path.reset(); path.moveTo(48.3f, 56.3f); path.lineTo(51.7f, 56.3f); path.lineTo(50f, 58.4f); path.close(); c.drawPath(path, fill)
                at(c, 50f, 60.5f, 0.7f, 50f, 60f) { drawMouth(c, t, col("#5A4A6A"), col("#FF7F98"), philtrum = false) }
                line.color = Color.argb(128, 90, 74, 106); line.strokeWidth = 0.6f
                for (sg in floatArrayOf(-1f, 1f)) for (dy in floatArrayOf(-1.2f, 1.6f)) c.drawLine(50f + sg * 12.5f, 58f + dy, 50f + sg * 24f, 56.6f + dy * 2f, line)
                c.restore()
            }
        }
    }

    // ===================== Rangu the chameleon =====================
    // Rangu's skin glides to a new colour whenever his mood changes, his turret eyes look around
    // on their own, and every so often (when he's happy and quiet) he snatches a fly with his tongue.
    private val ranguSkin = mapOf(
        Mood.HAPPY to "#43D16A", Mood.WINK to "#43D16A", Mood.EXCITED to "#FFD02E", Mood.LAUGHING to "#FFAA2E",
        Mood.LOVE to "#FF6FAE", Mood.SHY to "#FF9CC2", Mood.SAD to "#5AA2FF", Mood.CRYING to "#4C8BF5",
        Mood.SLEEPY to "#B3A4F5", Mood.YAWNING to "#B3A4F5", Mood.SCARED to "#DCE8EC", Mood.ANGRY to "#FF5A4F",
        Mood.SURPRISED to "#FF9A3D", Mood.THINKING to "#2ED3C0", Mood.CONFUSED to "#B888FF", Mood.COOL to "#28C0F0",
        Mood.PROUD to "#FFC23A"
    ).mapValues { Color.parseColor(it.value) }
    private val skin = FloatArray(3)
    private var skinReady = false
    private val hsv = FloatArray(3)
    private val eyeWhite = Color.parseColor("#FFF4CF")
    private val pupil = Color.parseColor("#1E1A2E")
    private val mouthIn = Color.parseColor("#7E1626")
    private val tongueTip = Color.parseColor("#FF4F7E")
    private val tongueC = Color.parseColor("#FF6F91")
    private val branch = Color.parseColor("#8A5A2B")
    private val leaf = Color.parseColor("#5BB55B")
    private val tmpA = Path()
    private val tmpB = Path()
    private val spots = arrayOf(floatArrayOf(41f, 31f, 1.8f, 0.75f), floatArrayOf(57.5f, 29f, 1.4f, 0.75f), floatArrayOf(63.5f, 35.5f, 1.9f, 0.6f),
        floatArrayOf(36.5f, 37f, 1.2f, 0.6f), floatArrayOf(60f, 50f, 1.1f, 0.5f))

    private fun mixC(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    private fun alpha(cl: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(cl), Color.green(cl), Color.blue(cl))
    private fun smooth(a: Float, b: Float, x: Float): Float { val k = ((x - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }

    /** Seconds into a fly-snatch (one every 18 s while Rangu is happy and quiet), or -1. */
    private fun snatchPhase(t: Float): Float {
        if (mood != Mood.HAPPY || talking || listening) return -1f
        val p = (t + 8f) % 18f
        return if (p < 4.4f) p else -1f
    }
    private fun flyEase(q: Float): Float { val k = 1f - minOf(q / 2.3f, 1f); return 1f - k * k * k }
    private fun flyX(q: Float) = -6f + 15f * flyEase(q) + sin(q * 21f) * 1.6f
    private fun flyY(q: Float) = 40f + 20f * flyEase(q) + kotlin.math.cos(q * 15f) * 1.8f

    private fun drawFly(c: Canvas, x: Float, y: Float, caught: Boolean, t: Float) {
        val fl = if (caught) 0.4f else abs(sin(t * 70f))
        ellipse(c, x - 1.5f, y - 2f, 2.7f, 1.6f * (0.3f + fl), Color.argb(230, 170, 215, 255), -30f)
        ellipse(c, x + 1.5f, y - 2f, 2.7f, 1.6f * (0.3f + fl), Color.argb(230, 170, 215, 255), 30f)
        ellipse(c, x, y, 2.5f, 1.8f, Color.rgb(42, 42, 51))
        fill.color = Color.rgb(224, 72, 72); c.drawCircle(x + 1.7f, y - 0.5f, 0.75f, fill)
        ellipse(c, x - 0.6f, y - 0.6f, 0.9f, 0.5f, Color.argb(128, 255, 255, 255))
        if (!caught) {
            line.color = Color.argb(90, 42, 42, 51); line.strokeWidth = 0.6f
            c.drawLine(x - 5.5f, y - 1f, x - 3.8f, y - 1f, line); c.drawLine(x - 5f, y + 1.2f, x - 3.6f, y + 0.9f, line)
        }
    }

    private fun drawRangu(c: Canvas, t: Float, now: Long) {
        // skin colour glides towards the colour of his mood (rainbow when dizzy)
        val target = if (mood == Mood.DIZZY) { hsv[0] = (t * 120f) % 360f; hsv[1] = 0.55f; hsv[2] = 0.95f; Color.HSVToColor(hsv) }
            else ranguSkin[mood] ?: ranguSkin.getValue(Mood.HAPPY)
        if (!skinReady) { skin[0] = Color.red(target).toFloat(); skin[1] = Color.green(target).toFloat(); skin[2] = Color.blue(target).toFloat(); skinReady = true }
        else {
            skin[0] += (Color.red(target) - skin[0]) * 0.12f; skin[1] += (Color.green(target) - skin[1]) * 0.12f; skin[2] += (Color.blue(target) - skin[2]) * 0.12f
        }
        val mid = Color.rgb(skin[0].toInt(), skin[1].toInt(), skin[2].toInt())
        val light = mixC(mid, Color.WHITE, 0.5f)
        val dark = mixC(mid, Color.rgb(52, 24, 70), 0.26f)
        val deep = mixC(mid, Color.rgb(20, 10, 30), 0.62f)
        val belly = mixC(mid, Color.rgb(255, 247, 220), 0.62f)
        val low = mood in setOf(Mood.SAD, Mood.SLEEPY, Mood.CRYING, Mood.SCARED, Mood.YAWNING)
        val blush = mood in setOf(Mood.HAPPY, Mood.LOVE, Mood.EXCITED, Mood.LAUGHING, Mood.WINK, Mood.PROUD, Mood.SHY)
        val ht = when (mood) {
            Mood.THINKING -> -8f; Mood.CONFUSED -> 10f; Mood.SHY -> 7f; Mood.WINK -> -6f
            Mood.DIZZY -> sin(t * 3f) * 10f; Mood.LAUGHING -> sin(t * 10f) * 3f; else -> 0f
        }

        // the fly snatch
        val q = snatchPhase(t)
        val mx = 50f; val my = 57.5f
        val tongueOut = q >= 2.35f && q < 2.85f
        var tipX = mx; var tipY = my
        if (tongueOut) {
            if (q < 2.5f) { val k = 1f - (q - 2.35f) / 0.15f; val f = 1f - k * k; tipX = mx + (flyX(q) - mx) * f; tipY = my + (flyY(q) - my) * f }
            else { val k = (q - 2.5f) / 0.35f; val f = k * k; val cx = flyX(2.5f); val cy = flyY(2.5f); tipX = cx + (mx - cx) * f; tipY = cy + (my - cy) * f }
        }
        val chewing = q >= 2.85f && q < 3.8f

        // where each eye looks: -1..1 (the eyes move on their own, like a real chameleon)
        fun wanderX(side: Int) = if (side < 0) kotlin.math.cos(t * 0.8f) * 0.85f else kotlin.math.cos(t * 0.6f + 2f) * 0.85f
        fun wanderY(side: Int) = if (side < 0) sin(t * 1.1f) * 0.7f else sin(t * 0.9f + 1f) * 0.7f
        fun lookX(side: Int, y: Boolean): Float {
            val cx = if (side < 0) 27f else 73f
            if (q >= 0f && q < 2.9f) {
                val fx = if (q < 2.5f) flyX(q) else tipX; val fy = if (q < 2.5f) flyY(q) else tipY
                val dx = fx - cx; val dy = fy - 41f; val n = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
                val w = if (side < 0) smooth(0.2f, 0.6f, q) else smooth(1f, 1.5f, q)
                return if (y) wanderY(side) + (dy / n - wanderY(side)) * w else wanderX(side) + (dx / n - wanderX(side)) * w
            }
            if (talking || listening) return if (y) 0.15f else 0f
            return when (mood) {
                Mood.THINKING -> if (y) -0.65f else 0.65f
                Mood.CONFUSED -> if (side < 0) (if (y) -0.6f else -0.7f) else (if (y) 0.7f else 0.6f)
                Mood.SHY -> if (y) 0.65f else -0.55f
                Mood.SAD, Mood.CRYING -> if (y) 0.8f else 0f
                Mood.HAPPY, Mood.WINK -> {
                    val p = t % 12f; val w = smooth(5f, 5.5f, p) * (1f - smooth(8f, 8.5f, p))   // now and then he looks right at you
                    if (y) wanderY(side) * (1f - w) + 0.12f * w else wanderX(side) * (1f - w)
                }
                else -> if (y) 0.1f else 0f
            }
        }

        fun turret(cx: Float, cy: Float, side: Int) {
            val lx = lookX(side, false); val ly = lookX(side, true)
            ovalP(c, cx, cy, 11.5f, 11f, rad(cx - 3.5f, cy - 4f, 15f, 0f to light, 0.6f to mid, 1f to dark))
            val ox = cx + lx * 4.2f; val oy = cy + ly * 4.2f
            // rings of the eye turret, centred on where it looks
            c.save(); path.reset(); oval.set(cx - 11.5f, cy - 11f, cx + 11.5f, cy + 11f); path.addOval(oval, Path.Direction.CW); c.clipPath(path)
            line.color = alpha(dark, 0.35f); line.strokeWidth = 0.9f; c.drawCircle(ox, oy, 7.8f, line); c.drawCircle(ox, oy, 10.4f, line)
            c.restore()
            line.color = alpha(dark, 0.5f); line.strokeWidth = 0.8f; c.drawOval(oval, line)
            val blink = ((now / 100) % 45) == 0L && mood !in setOf(Mood.EXCITED, Mood.LAUGHING, Mood.PROUD, Mood.CRYING, Mood.SLEEPY, Mood.YAWNING)
            val closedSmile = (chewing || mood == Mood.EXCITED || (mood == Mood.WINK && side > 0)) && !tongueOut
            line.color = ink; line.strokeWidth = 2.2f
            if (mood == Mood.SLEEPY || blink) {
                ellipse(c, ox, oy, 5.6f, 5.6f, alpha(dark, 0.35f)); c.drawLine(ox - 4.2f, oy + 0.5f, ox + 4.2f, oy + 0.5f, line); return
            }
            if (closedSmile) { path.reset(); path.moveTo(ox - 4.2f, oy + 1.8f); path.quadTo(ox, oy - 4.5f, ox + 4.2f, oy + 1.8f); c.drawPath(path, line); return }
            if (mood == Mood.LAUGHING) {
                val s = -side.toFloat()
                path.reset(); path.moveTo(ox - 3.6f * s, oy - 3.2f); path.lineTo(ox + 2.8f * s, oy); path.lineTo(ox - 3.6f * s, oy + 3.2f); c.drawPath(path, line); return
            }
            if (mood == Mood.PROUD || mood == Mood.CRYING || mood == Mood.YAWNING) {
                path.reset(); path.moveTo(ox - 4.2f, oy - 1f); path.quadTo(ox, oy + 3.6f, ox + 4.2f, oy - 1f); c.drawPath(path, line)
                if (mood == Mood.CRYING) { val f = (t * 14f) % 12f; drop(c, ox + side * 2f, oy + 6f + f, 2.1f, tear) }
                return
            }
            if (mood == Mood.COOL) return   // sunglasses are drawn over both eyes later
            val er = if (mood == Mood.SCARED) 6.2f else 5.6f
            fill.color = eyeWhite; c.drawCircle(ox, oy, er, fill)
            line.color = Color.argb(230, 230, 170, 60); line.strokeWidth = 0.9f; c.drawCircle(ox, oy, er, line)
            val pr = when (mood) { Mood.SURPRISED -> 4.2f; Mood.SCARED -> 1.9f; Mood.SAD -> 3.2f; else -> 3.5f }
            val px = ox + lx * 1.2f; val py = oy + ly * 1.2f
            when (mood) {
                Mood.LOVE -> heart(c, px, py + 0.3f, 3.6f, tongue)
                Mood.DIZZY -> {
                    line.color = ink; line.strokeWidth = 1.1f; path.reset()
                    var a = 0f
                    while (a < 13f) {
                        val r = 0.35f * a; val x = px + kotlin.math.cos(a + t * 6f * side) * r; val y = py + sin(a + t * 6f * side) * r
                        if (a == 0f) path.moveTo(x, y) else path.lineTo(x, y); a += 0.3f
                    }
                    c.drawPath(path, line)
                }
                else -> {
                    fill.color = pupil; c.drawCircle(px, py, pr, fill)
                    fill.color = Color.WHITE; c.drawCircle(px - pr * 0.38f, py - pr * 0.42f, maxOf(0.6f, pr * 0.36f), fill); c.drawCircle(px + pr * 0.4f, py + pr * 0.42f, pr * 0.16f, fill)
                }
            }
            // eyelids for feelings: a straight lid edge, tilted for sad or angry
            val (y0, slant) = when (mood) { Mood.SAD -> -2.2f to -1.6f; Mood.ANGRY -> -1.6f to 2.4f; Mood.SHY -> -1.2f to 0f; else -> return }
            val ay = oy + y0 + slant * side; val by = oy + y0 - slant * side
            tmpA.reset(); tmpA.moveTo(ox - 8f, oy - 8f); tmpA.lineTo(ox + 8f, oy - 8f); tmpA.lineTo(ox + 8f, by); tmpA.lineTo(ox - 8f, ay); tmpA.close()
            tmpB.reset(); tmpB.addCircle(ox, oy, 6.1f, Path.Direction.CW)
            tmpA.op(tmpB, Path.Op.INTERSECT); fill.color = mid; c.drawPath(tmpA, fill)
            // lid edge: the part of the line inside the eye
            val dx = 16f; val dy = by - ay; val fx = -8f; val fy = ay - oy
            val qa = dx * dx + dy * dy; val qb = 2f * (fx * dx + fy * dy); val qc = fx * fx + fy * fy - 5.8f * 5.8f
            val disc = qb * qb - 4f * qa * qc
            if (disc > 0f) {
                val sq = kotlin.math.sqrt(disc); val u1 = (-qb - sq) / (2f * qa); val u2 = (-qb + sq) / (2f * qa)
                line.color = ink; line.strokeWidth = 1.3f
                c.drawLine(ox - 8f + dx * u1, ay + dy * u1, ox - 8f + dx * u2, ay + dy * u2, line)
            }
        }

        fun mouth() {
            line.color = ink; line.strokeWidth = 1.8f
            if (tongueOut) {
                path.reset(); path.moveTo(41f, 55f); path.quadTo(50f, 51.5f, 59f, 55f); path.quadTo(50f, 64f, 41f, 55f); path.close()
                fill.color = mouthIn; c.drawPath(path, fill); return
            }
            if (talking || mood == Mood.LAUGHING || mood == Mood.YAWNING || mood == Mood.EXCITED) {
                val open = if (talking) 1.5f + abs(sin(t * 18f)) * 4.2f else when (mood) { Mood.YAWNING -> 7f; Mood.LAUGHING -> 6f; else -> 4.5f }
                tmpB.reset(); tmpB.moveTo(36f, 54f); tmpB.quadTo(50f, 59f, 64f, 54f); tmpB.quadTo(50f, 59f + open * 1.9f, 36f, 54f); tmpB.close()
                fill.color = mouthIn; c.drawPath(tmpB, fill)
                if (open > 3f) {
                    tmpA.reset(); oval.set(44f, 58f + open * 0.55f, 56f, 58f + open * 1.95f); tmpA.addOval(oval, Path.Direction.CW)
                    tmpA.op(tmpB, Path.Op.INTERSECT); fill.color = Color.rgb(255, 127, 152); c.drawPath(tmpA, fill)
                }
                path.reset(); path.moveTo(36f, 54f); path.quadTo(50f, 59f, 64f, 54f); c.drawPath(path, line)
                return
            }
            path.reset()
            when (mood) {
                Mood.SAD, Mood.CRYING -> { path.moveTo(39f, 59f); path.quadTo(50f, 53.5f, 61f, 59f); c.drawPath(path, line) }
                Mood.SURPRISED -> ellipse(c, 50f, 58f, 3f, 3.6f, ink)
                Mood.SCARED -> { ellipse(c, 50f, 58f, 5.5f, 2.8f, ink); fill.color = Color.WHITE; c.drawRect(45.5f, 56.2f, 54.5f, 57.6f, fill) }
                Mood.ANGRY -> { path.moveTo(39f, 57f); path.quadTo(44.5f, 55f, 50f, 57f); path.quadTo(55.5f, 59f, 61f, 57f); c.drawPath(path, line) }
                Mood.SLEEPY -> { path.moveTo(43f, 56.5f); path.quadTo(50f, 58.5f, 57f, 56.5f); c.drawPath(path, line) }
                Mood.DIZZY -> { path.moveTo(38f, 57f); path.quadTo(44f, 54f, 50f, 57f); path.quadTo(56f, 60f, 62f, 57f); c.drawPath(path, line) }
                Mood.THINKING, Mood.CONFUSED -> { path.moveTo(40f, 57f); path.quadTo(48f, 58f, 60f, 54f); c.drawPath(path, line) }
                Mood.COOL -> { path.moveTo(39f, 56f); path.quadTo(52f, 60f, 62f, 53f); c.drawPath(path, line) }
                Mood.SHY -> { path.moveTo(44f, 56f); path.quadTo(50f, 58.5f, 56f, 56f); c.drawPath(path, line) }
                else -> {   // big smile with curled corners (and chewing)
                    val munch = if (chewing) abs(sin(q * 16f)) * 1.6f else 0f
                    path.moveTo(34.5f, 53f); path.quadTo(50f, 61.5f + munch, 65.5f, 53f); c.drawPath(path, line)
                    path.reset(); path.moveTo(34.5f, 53f); path.quadTo(33f, 52.2f, 33.2f, 50.6f); c.drawPath(path, line)
                    path.reset(); path.moveTo(65.5f, 53f); path.quadTo(67f, 52.2f, 66.8f, 50.6f); c.drawPath(path, line)
                    if (mood == Mood.LOVE || mood == Mood.PROUD) { fill.color = Color.rgb(255, 127, 152); oval.set(47.4f, 57.4f, 52.6f, 61.8f); c.drawArc(oval, 0f, 180f, true, fill) }
                }
            }
            if (mood == Mood.COOL) {   // sunglasses over both eye turrets
                ellipse(c, 27f, 41f, 8.4f, 6.6f, Color.rgb(30, 30, 42)); ellipse(c, 73f, 41f, 8.4f, 6.6f, Color.rgb(30, 30, 42))
                line.color = Color.rgb(30, 30, 42); line.strokeWidth = 2.2f; c.drawLine(35f, 39f, 65f, 39f, line)
                line.color = Color.argb(217, 255, 255, 255); line.strokeWidth = 1.3f; c.drawLine(22f, 38.5f, 25.5f, 37f, line); c.drawLine(68f, 38.5f, 71.5f, 37f, line)
            }
        }

        // shadow and branch
        ellipse(c, 50f, 96.5f, 24f, 2.6f, Color.argb(36, 19, 42, 44))
        line.color = branch; line.strokeWidth = 5f
        path.reset(); path.moveTo(4f, 89f); path.quadTo(50f, 84f, 96f, 89.5f); c.drawPath(path, line)
        line.color = Color.argb(140, 80, 48, 20); line.strokeWidth = 1.3f
        path.reset(); path.moveTo(10f, 91f); path.quadTo(50f, 86.6f, 90f, 91.4f); c.drawPath(path, line)
        ellipse(c, 88f, 85.2f, 5f, 2.3f, leaf, -28f); ellipse(c, 12f, 86.2f, 4f, 2f, leaf, 22f)

        // curled tail behind the body: curls tighter when he feels low, swishes when excited
        val ex = mood == Mood.EXCITED || mood == Mood.LAUGHING
        val turns = 1.55f + (if (low) 0.3f else 0f) - (if (ex) 0.18f * abs(sin(t * 6f)) else 0f)
        c.save(); c.rotate(if (ex) sin(t * 9f) * 7f else sin(t * 1.8f) * 4f, 66f, 78f)
        val tcx = 82f; val tcy = 69f; val r0 = 11f; val a0 = 2.55f
        val sx = tcx + kotlin.math.cos(a0) * r0; val sy = tcy + sin(a0) * r0
        for (pass in 0..1) {
            fill.color = if (pass == 0) dark else mid
            val grow = if (pass == 0) 0.7f else 0f
            for (i in 0..10) { val k = i / 10f; c.drawCircle(63f + (sx - 63f) * k, 79f + (sy - 79f) * k, (5.6f - 0.3f * k) / 2f + grow, fill) }
            for (i in 0..80) {
                val s = i / 80f; val a = a0 - s * turns * 6.2832f; val r = r0 * (1f - 0.86f * s)
                c.drawCircle(tcx + kotlin.math.cos(a) * r, tcy + sin(a) * r, (5.3f - 3.9f * s) / 2f + grow, fill)
            }
        }
        fill.color = alpha(light, 0.8f)
        for (i in 3..80 step 9) {
            val s = i / 80f; val a = a0 - s * turns * 6.2832f; val r = r0 * (1f - 0.86f * s)
            c.drawCircle(tcx + kotlin.math.cos(a) * r, tcy + sin(a) * r, (5.3f - 3.9f * s) * 0.18f, fill)
        }
        c.restore()

        // body, belly stripes and the feet gripping the branch
        ovalP(c, 50f, 73f, 19f, 15f, rad(43f, 66f, 28f, 0f to light, 0.55f to mid, 1f to dark))
        ellipse(c, 50f, 77.5f, 10.5f, 8.5f, alpha(belly, 0.9f))
        line.color = alpha(dark, 0.45f); line.strokeWidth = 1.2f
        for (sg in floatArrayOf(-1f, 1f)) for (dy in floatArrayOf(0f, 5f)) {
            path.reset(); path.moveTo(50f + sg * 13f, 64f + dy); path.quadTo(50f + sg * 17f, 68f + dy, 50f + sg * 15.5f, 73f + dy); c.drawPath(path, line)
        }
        for (x in floatArrayOf(39.5f, 60.5f)) { ellipse(c, x, 81.5f, 4.2f, 5.4f, mid); ellipse(c, x - 2.5f, 87.1f, 3f, 2.4f, dark); ellipse(c, x + 2.6f, 87.5f, 3f, 2.4f, mid) }

        // head: helmet crest, spots, eye turrets, face
        c.save(); c.rotate(ht, 50f, 62f)
        path.reset(); path.moveTo(35.5f, 33f); path.cubicTo(35.5f, 19f, 43f, 11f, 50.5f, 11f); path.cubicTo(58f, 11f, 64.5f, 19f, 64.5f, 33f); path.close()
        c.drawPath(path, lin(50f, 11f, 50f, 33f, 0f to dark, 1f to mid))
        fill.color = alpha(light, 0.75f); c.drawCircle(43f, 16.5f, 1.5f, fill); c.drawCircle(50.5f, 12.6f, 1.5f, fill); c.drawCircle(58f, 16.5f, 1.5f, fill)
        line.color = alpha(light, 0.5f); line.strokeWidth = 0.9f
        path.reset(); path.moveTo(40f, 30f); path.quadTo(50.5f, 12f, 61f, 30f); c.drawPath(path, line)
        ovalP(c, 50f, 45f, 26.5f, 21.5f, rad(42f, 35f, 38f, 0f to light, 0.5f to mid, 1f to dark))
        for (sp in spots) { fill.color = alpha(light, sp[3]); c.drawCircle(sp[0], sp[1], sp[2], fill) }
        ellipse(c, 50f, 61f, 15f, 5.2f, alpha(belly, 0.85f))
        turret(27f, 41f, -1); turret(73f, 41f, 1)
        fill.color = alpha(deep, 0.85f); c.drawCircle(46.5f, 48f, 0.85f, fill); c.drawCircle(53.5f, 48f, 0.85f, fill)
        if (blush || chewing) { ellipse(c, 35f, 53.5f, 4.2f, 2.8f, Color.argb(140, 255, 143, 168)); ellipse(c, 65f, 53.5f, 4.2f, 2.8f, Color.argb(140, 255, 143, 168)) }
        if (chewing) { ellipse(c, 37f, 57f, 4.5f, 3.6f, alpha(light, 0.9f)); ellipse(c, 63f, 57f, 4.5f, 3.6f, alpha(light, 0.9f)) }
        mouth()
        c.restore()

        // tongue and fly (only while snatching, so never tilted)
        if (tongueOut) {
            line.color = tongueC; line.strokeWidth = 2.4f; c.drawLine(mx, my, tipX, tipY, line)
            fill.color = tongueTip; c.drawCircle(tipX, tipY, 2.7f, fill)
            fill.color = Color.argb(204, 255, 255, 255); c.drawCircle(tipX - 0.8f, tipY - 0.9f, 0.8f, fill)
            if (q >= 2.5f) drawFly(c, tipX, tipY - 0.5f, true, t)
        }
        if (q >= 0f && q < 2.5f) drawFly(c, flyX(q), flyY(q), false, t)
        if (q >= 3.3f && q < 3.9f) { star(c, 30f, 60f, 2.8f, accent); star(c, 25f, 54f, 1.7f, accent) }
    }

    /** Four-point sparkle. */
    private fun star(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        path.reset()
        path.moveTo(cx, cy - r); path.quadTo(cx, cy, cx + r, cy); path.quadTo(cx, cy, cx, cy + r)
        path.quadTo(cx, cy, cx - r, cy); path.quadTo(cx, cy, cx, cy - r); path.close()
        c.drawPath(path, fill)
    }

    /** Tear or sweat drop. */
    private fun drop(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        path.reset()
        path.moveTo(cx, cy - r * 1.8f)
        path.quadTo(cx + r * 1.2f, cy, cx, cy + r); path.quadTo(cx - r * 1.2f, cy, cx, cy - r * 1.8f); path.close()
        c.drawPath(path, fill)
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        path.reset()
        path.moveTo(cx, cy + r)
        path.cubicTo(cx - r * 2f, cy - r * 0.2f, cx - r * 0.9f, cy - r * 1.6f, cx, cy - r * 0.5f)
        path.cubicTo(cx + r * 0.9f, cy - r * 1.6f, cx + r * 2f, cy - r * 0.2f, cx, cy + r)
        c.drawPath(path, fill)
    }

    init { applyColors() }

    companion object {
        const val EYE_NORMAL = 0
        const val EYE_ANIME = 1
        const val EYE_GLOW = 2
        const val EYE_RINGED = 3
        /** The newest pets, newest first, shown with a "New" badge. */
        val NEW_KINDS = listOf(Kind.CHAMELEON, Kind.ROBOT, Kind.PARROT, Kind.MOCHI, Kind.KITTY)
        /** What the pet says when you pick it. */
        fun hello(k: Kind): String = if (k == Kind.CHAMELEON) "Hi! I'm Rangu, your chameleon! Watch me change colour with my mood 🌈"
            else "Hi! I'm your ${k.label.lowercase()} now!"
        fun kindOf(key: String?): Kind = Kind.values().firstOrNull { it.key == key } ?: Kind.PUPPY
        fun moodOf(name: String?): Mood = when (name) {
            "excited" -> Mood.EXCITED; "thinking" -> Mood.THINKING; "sad" -> Mood.SAD; "surprised" -> Mood.SURPRISED
            "love" -> Mood.LOVE; "sleepy" -> Mood.SLEEPY; "confused" -> Mood.CONFUSED
            "laughing" -> Mood.LAUGHING; "angry" -> Mood.ANGRY; "crying" -> Mood.CRYING; "shy" -> Mood.SHY
            "cool" -> Mood.COOL; "wink" -> Mood.WINK; "proud" -> Mood.PROUD; "scared" -> Mood.SCARED
            "yawning" -> Mood.YAWNING; "dizzy" -> Mood.DIZZY; else -> Mood.HAPPY
        }
    }
}
