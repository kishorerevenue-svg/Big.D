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
    /** The choice before the user picks one. */
    const val DEFAULT = "teal"

    var p: Palette = PALETTES.first { it.id == DEFAULT }
        private set

    /** Reads the saved choice (call before drawing a page). */
    fun refresh(ctx: Context) {
        val id = try { ChatDb.get(ctx).kv("set:appColour") } catch (e: Exception) { "" }
        p = PALETTES.firstOrNull { it.id == id } ?: PALETTES.first { it.id == DEFAULT }
    }
    fun choose(ctx: Context, id: String) { ChatDb.get(ctx).setKv("set:appColour", id); refresh(ctx) }

    // ----- the tokens the pages use -----
    val BG get() = p.bg
    val CARD get() = Color.WHITE
    /** A text box or a card inside a card. */
    val FIELD get() = mix(p.bg, Color.WHITE, 0.45f)
    val LINE get() = p.line
    val TXT get() = p.txt
    val SUB get() = p.sub
    /** The main colour: buttons, the chosen chip, the Talk button. */
    val PRIMARY get() = p.primary
    val ON_PRIMARY get() = Color.WHITE
    /** The main colour as words (a little deeper, so it reads well on white). */
    val PRIMARY_TXT get() = p.primaryTxt
    /** A soft tint of the main colour (icon squares, soft buttons, the tip card). */
    val SOFT get() = p.soft
    val ACCENT get() = p.accent
    val ACCENT_TXT get() = p.accentTxt
    val ACCENT_SOFT get() = p.soft2
    /** Two more soft colour pairs (background, ink) for icon squares. */
    val TINT2 get() = p.soft2 to p.ink2
    val TINT3 get() = p.soft3 to p.ink3
    /** The big card on Home: a gradient between these. */
    val HERO get() = intArrayOf(p.g1, p.g2)
    /** The chosen tab's pill in the bottom bar. */
    val PILL get() = p.soft
    val PILL_TXT get() = p.primaryTxt
    val DANGER get() = Color.parseColor("#C63B3F")
    val DANGER_SOFT get() = Color.parseColor("#FBE3E3")
    val GOOD get() = Color.parseColor("#1D8A5B")

    /** [a] moved toward [b] by [t] (0..1). */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
}
