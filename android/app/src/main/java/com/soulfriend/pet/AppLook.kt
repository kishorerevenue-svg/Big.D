package com.soulfriend.pet

import android.content.Context
import android.graphics.Color

/**
 * The colours of Habit AI outside the chats (Home, Tasks, Files, Vault, Translate, Family & SOS, Govt, Media): one of six
 * colour choices (Home › your picture › App colour). Habit Chat keeps its own "Navy and gold" / "Clean light" look.
 * Every colour is read when a page is drawn, so changing the choice redraws the app in the new colour.
 */
object AppLook {
    /** One colour choice: the main colour, the two ends of the big card's gradient, a second colour, and the page. */
    class Palette(
        val id: String, val en: String, val te: String,
        primary: String, primaryTxt: String, g1: String, g2: String, accent: String, accentTxt: String,
        bg: String, line: String, txt: String, sub: String,
        soft: String, soft2: String, ink2: String, soft3: String, ink3: String
    ) {
        private fun c(s: String) = Color.parseColor(s)
        val primary = c(primary); val primaryTxt = c(primaryTxt); val g1 = c(g1); val g2 = c(g2)
        val accent = c(accent); val accentTxt = c(accentTxt)
        val bg = c(bg); val line = c(line); val txt = c(txt); val sub = c(sub)
        val soft = c(soft); val soft2 = c(soft2); val ink2 = c(ink2); val soft3 = c(soft3); val ink3 = c(ink3)
    }

    val PALETTES = listOf(
        Palette("teal", "Ocean Teal", "సముద్రం", "#0F766E", "#0B5E58", "#0F766E", "#16A394", "#F97360", "#C2412F",
            "#EEF6F5", "#DCE9E7", "#0B2B28", "#5A726F", "#D6F0EC", "#FFE4DE", "#C2412F", "#E5ECFB", "#2F54B8"),
        Palette("purple", "Royal Purple", "రాయల్ పర్పుల్", "#6D28D9", "#5B21B6", "#5B21B6", "#8B5CF6", "#F59E0B", "#A15F00",
            "#F5F2FD", "#E6E0F7", "#1D1238", "#675F82", "#EAE2FD", "#FFEFD0", "#A15F00", "#DDF3EE", "#0F7A68"),
        Palette("orange", "Sunrise Orange", "సూర్యోదయం", "#EA580C", "#C2410C", "#EA580C", "#F43F5E", "#0EA5E9", "#0B6E9E",
            "#FFF6EF", "#F6E3D6", "#3A1B0B", "#866656", "#FFE6D5", "#DDF2FC", "#0B6E9E", "#FCE1E7", "#B4234A"),
        Palette("green", "Emerald Green", "పచ్చ", "#047857", "#04654A", "#065F46", "#10B981", "#EAB308", "#8A6500",
            "#EFF7F2", "#D9EBE1", "#0B281C", "#5A7569", "#D5F1E3", "#FDF2C7", "#8A6500", "#E3EAFB", "#3049A8"),
        Palette("blue", "Sky Blue", "ఆకాశ నీలం", "#1D4ED8", "#1E40AF", "#1E40AF", "#38A3F5", "#F97316", "#B4500D",
            "#F1F5FE", "#DFE6F7", "#0E1B3D", "#5D6987", "#DDE7FD", "#FFE6D3", "#B4500D", "#DCF4EA", "#14794F"),
        Palette("maroon", "Maroon & Saffron", "మెరూన్ & కాషాయం", "#9F1239", "#881337", "#881337", "#E0434F", "#F59E0B", "#9A5A00",
            "#FDF3F2", "#F2DEDC", "#3A0A15", "#85595F", "#FBE0E3", "#FFEFCF", "#9A5A00", "#E2EEFB", "#2B5BA0"),
    )
    /** The colour before the user picks one: Royal Purple (it goes with the chats' navy and gold). */
    const val DEFAULT = "purple"

    var p: Palette = PALETTES.first { it.id == DEFAULT }
        private set
    /** True: the dark look (the phone's dark mode, or Theme › Dark, the same setting as the chats). */
    var dark = false
        private set
    private var dk = Dark(p)

    /** Reads the saved choice and the dark / light look (call before drawing a page). */
    fun refresh(ctx: Context) {
        val id = try { ChatDb.get(ctx).kv("set:appColour") } catch (e: Exception) { "" }
        val np = PALETTES.firstOrNull { it.id == id } ?: PALETTES.first { it.id == DEFAULT }
        if (np !== p) { p = np; dk = Dark(np) }
        ChatTheme.refresh(ctx); dark = ChatTheme.dark
    }
    fun choose(ctx: Context, id: String) { ChatDb.get(ctx).setKv("set:appColour", id); refresh(ctx) }

    /** The dark look of a colour choice: deep pages tinted with the main colour, lighter words and icons. */
    private class Dark(p: Palette) {
        val hue = hueOf(p.primary)
        val bg = hsl(hue, 0.30f, 0.075f)
        val card = hsl(hue, 0.25f, 0.12f)
        val field = hsl(hue, 0.22f, 0.16f)
        val line = hsl(hue, 0.20f, 0.21f)
        val txt = hsl(hue, 0.25f, 0.95f)
        val sub = hsl(hue, 0.14f, 0.70f)
        val primary = mix(p.primary, Color.WHITE, 0.06f)
        val primaryTxt = mix(p.primary, Color.WHITE, 0.55f)
        val soft = mix(card, p.primary, 0.32f)
        val soft2 = mix(card, p.accent, 0.24f); val ink2 = mix(p.accent, Color.WHITE, 0.35f)
        val soft3 = mix(card, p.ink3, 0.34f); val ink3 = mix(p.ink3, Color.WHITE, 0.55f)
        val accentTxt = mix(p.accent, Color.WHITE, 0.30f)
        val g1 = mix(p.g1, Color.BLACK, 0.12f); val g2 = mix(p.g2, Color.BLACK, 0.20f)
    }
    private fun pk(light: Int, darkC: Int) = if (dark) darkC else light
    /** [light] in the light look, [darkC] in the dark look. */
    fun lt(light: Int, darkC: Int) = pk(light, darkC)

    // ----- the tokens the pages use -----
    val BG get() = pk(p.bg, dk.bg)
    val CARD get() = pk(Color.WHITE, dk.card)
    /** A text box or a card inside a card. */
    val FIELD get() = pk(mix(p.bg, Color.WHITE, 0.45f), dk.field)
    val LINE get() = pk(p.line, dk.line)
    val TXT get() = pk(p.txt, dk.txt)
    val SUB get() = pk(p.sub, dk.sub)
    /** The main colour: buttons, the chosen chip, the Talk button. */
    val PRIMARY get() = pk(p.primary, dk.primary)
    val ON_PRIMARY get() = Color.WHITE
    /** The main colour as words and icons (deeper on white, lighter on the dark look). */
    val PRIMARY_TXT get() = pk(p.primaryTxt, dk.primaryTxt)
    /** A soft tint of the main colour (icon squares, soft buttons, the tip card). */
    val SOFT get() = pk(p.soft, dk.soft)
    val ACCENT get() = p.accent
    val ACCENT_TXT get() = pk(p.accentTxt, dk.accentTxt)
    val ACCENT_SOFT get() = pk(p.soft2, dk.soft2)
    /** Two more soft colour pairs (background, ink) for icon squares. */
    val TINT2 get() = if (dark) dk.soft2 to dk.ink2 else p.soft2 to p.ink2
    val TINT3 get() = if (dark) dk.soft3 to dk.ink3 else p.soft3 to p.ink3
    /** The big card on Home: a gradient between these. */
    val HERO get() = if (dark) intArrayOf(dk.g1, dk.g2) else intArrayOf(p.g1, p.g2)
    /** The chosen tab's pill in the bottom bar. */
    val PILL get() = SOFT
    val PILL_TXT get() = PRIMARY_TXT
    val DANGER get() = pk(Color.parseColor("#C63B3F"), Color.parseColor("#FF7B7B"))
    val DANGER_SOFT get() = pk(Color.parseColor("#FBE3E3"), mix(dk.card, Color.parseColor("#FF6B6B"), 0.22f))
    val GOOD get() = pk(Color.parseColor("#1D8A5B"), Color.parseColor("#4CC38A"))
    val GOOD_SOFT get() = pk(Color.parseColor("#DFF3E8"), mix(dk.card, Color.parseColor("#4CC38A"), 0.22f))
    /** "Be careful" (between safe and danger). */
    val WARN get() = pk(Color.parseColor("#B7791F"), Color.parseColor("#F2B84B"))
    val WARN_SOFT get() = pk(Color.parseColor("#FFF3D6"), mix(dk.card, Color.parseColor("#F2B84B"), 0.22f))

    // ----- colour helpers -----
    private fun hueOf(c: Int): Float { val hsv = FloatArray(3); Color.colorToHSV(c, hsv); return hsv[0] }
    /** A colour from hue (0..360), saturation and lightness (0..1). */
    fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - Math.abs(2 * l - 1)) * s
        val x = c * (1 - Math.abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f); h < 120 -> Triple(x, c, 0f); h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c); h < 300 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Color.rgb(((r + m) * 255).toInt().coerceIn(0, 255), ((g + m) * 255).toInt().coerceIn(0, 255), ((b + m) * 255).toInt().coerceIn(0, 255))
    }

    /** [a] moved toward [b] by [t] (0..1). */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
}
