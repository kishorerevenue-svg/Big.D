package com.soulfriend.pet

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** BigD AI look: peacock teal + sunflower, soft mint-grey background, white cards. */
class Ui(private val ctx: Context) {
    companion object {
        val BG = Color.parseColor("#EEF3F2")
        val SURFACE = Color.WHITE
        val INK = Color.parseColor("#132A2C")
        val MUTED = Color.parseColor("#5B7073")
        val TEAL = Color.parseColor("#0E6B6B")
        val TEAL_DARK = Color.parseColor("#0A4F50")
        val TEAL_SOFT = Color.parseColor("#D7EDEA")
        val SUN = Color.parseColor("#F6B53D")
        val SUN_SOFT = Color.parseColor("#FFF1D2")
        val LINE = Color.parseColor("#DCE5E3")
        val DANGER = Color.parseColor("#C63B3F")
        val DANGER_SOFT = Color.parseColor("#FBE3E3")
        val GOOD = Color.parseColor("#1D8A5B")
    }

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun dpf(v: Int) = dp(v).toFloat()

    fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius); if (stroke != null) setStroke(dp(1), stroke)
    }

    fun text(s: String, size: Float = 15f, color: Int = INK, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); setLineSpacing(0f, 1.18f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun title(s: String) = text(s, 24f, INK, bold = true).apply { letterSpacing = -0.01f }
    fun label(s: String) = text(s.uppercase(), 11.5f, MUTED, bold = true).apply { letterSpacing = 0.08f }

    /** kind: "primary" (teal), "sun" (yellow), "soft" (light teal), "ghost" (outline), "danger" */
    fun button(label: String, kind: String = "soft", onClick: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 14.5f; stateListAnimator = null
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val (bg, fg, line) = when (kind) {
            "primary" -> Triple(TEAL, Color.WHITE, null)
            "sun" -> Triple(SUN, INK, null)
            "ghost" -> Triple(SURFACE, TEAL, LINE)
            "danger" -> Triple(DANGER_SOFT, DANGER, null)
            else -> Triple(TEAL_SOFT, TEAL_DARK, null)
        }
        background = round(bg, 24, line); setTextColor(fg)
        setOnClickListener { onClick() }
    }

    fun card(color: Int = SURFACE): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = round(color, 20, if (color == SURFACE) LINE else null)
    }

    fun section(title: String, subtitle: String? = null): LinearLayout = card().apply {
        addView(text(title, 17f, INK, bold = true))
        if (subtitle != null) put(text(subtitle, 13f, MUTED), 4)
    }

    fun row(vararg views: View, gap: Int = 8) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(gap) }) }
    }

    /** A row with text on the left (taking the space) and [end] on the right. */
    fun line(main: View, end: View?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(main, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) addView(end, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    fun stack(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; views.forEach { addView(it) }
    }

    fun input(hint: String, value: String = "", multi: Boolean = false, number: Boolean = false) = EditText(ctx).apply {
        this.hint = hint; setText(value); textSize = 15f; setTextColor(INK); setHintTextColor(MUTED)
        background = round(Color.parseColor("#F5F8F7"), 14, LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = when {
            number -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (multi) { setSingleLine(false); maxLines = 6; minLines = 2 } else setSingleLine()
    }

    fun check(label: String, on: Boolean, change: (Boolean) -> Unit) = CheckBox(ctx).apply {
        text = label; isChecked = on; textSize = 15f; setTextColor(INK)
        buttonTintList = android.content.res.ColorStateList.valueOf(TEAL)
        setOnCheckedChangeListener { _, c -> change(c) }
    }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (selected) Color.WHITE else TEAL_DARK)
        background = round(if (selected) TEAL else TEAL_SOFT, 18)
        setOnClickListener { onClick() }
    }

    fun divider() = View(ctx).apply { setBackgroundColor(LINE) }

    fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
}
