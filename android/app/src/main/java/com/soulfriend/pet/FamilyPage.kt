package com.soulfriend.pet

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeSet

/**
 * Family & SOS page.
 *  - SOS: hold the red button for 3 seconds → after a 5-second countdown (you can cancel) it calls your first family member,
 *    and you can send where you are (a Google Maps link) by SMS or WhatsApp to the family.
 *    Your location is used only at that moment, stays on the phone and is never sent to the Habit server or the AI.
 *  - Family members (up to 5), emergency numbers, and daily medicine reminders.
 */
class FamilyPage(private val m: MainActivity) {
    companion object {
        /** number, name, what it is for, icon */
        val NUMBERS = listOf(
            listOf("112", "Emergency", "Police, fire, ambulance", "🚨"), listOf("108", "Ambulance", "Free ambulance", "🚑"),
            listOf("100", "Police", "Police help", "👮"), listOf("101", "Fire", "Fire brigade", "🔥"),
            listOf("181", "Women helpline", "Help for women", "👩"), listOf("1098", "Child helpline", "Help for children", "🧒"),
            listOf("1930", "Cyber fraud", "Money lost online? Call at once", "💳"), listOf("14567", "Elder helpline", "Help for senior citizens", "👴")
        )
        private val PRESETS = listOf(LocalTime.of(8, 0) to "🌅 Morning", LocalTime.of(13, 0) to "☀️ Afternoon", LocalTime.of(20, 0) to "🌙 Night")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }

    /** "medicine" = scroll to the medicine reminders when the page opens (from the Home tile). */
    var focus = ""

    private var sos = false
    private var countdown = 0
    private var called = ""
    private var countText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var loc: Location? = null
    private var locState = ""            // "", finding, found, old, off (not allowed), disabled (location turned off), none
    private var locText: TextView? = null
    private var locCancel: CancellationSignal? = null
    private var shareWhenFound = false
    private var afterLocation: (() -> Unit)? = null

    private var medName = ""
    private val medTimes = TreeSet<LocalTime>()
    private val extraTimes = TreeSet<LocalTime>()

    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })
    private fun hasLocation() = m.granted(Manifest.permission.ACCESS_FINE_LOCATION) || m.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun who(c: FamilyContact) = c.name + if (c.relation.isNotBlank()) " (${c.relation})" else ""

    fun build() {
        countText = null; locText = null
        m.pageHeader("Family & SOS · కుటుంబం", "Help in one touch, your family's numbers, and medicine reminders")
        if (sos) sosPanel() else sosCard()
        familySection()
        val med = medicineSection()
        numbersSection()
        m.add(ui.text("Your family's numbers and your medicines stay only on this phone. Your location is used only when you press SOS or Share my location, " +
            "and goes only into the message you send. It is never sent to the Habit server or the AI.", 12.5f, Ui.MUTED), 16)
        if (focus == "medicine") { focus = ""; m.scrollToView(med) }
    }

    /** Back closes the SOS screen first. */
    fun back(): Boolean { if (!sos) return false; close(); return true }

    /** Leaving the app stops the countdown (a call can't start from the background). */
    fun onPause() { if (countdown > 0) { countdown = 0; handler.removeCallbacks(countTick) } }

    // =================== SOS ===================
    @SuppressLint("ClickableViewAccessibility")
    private fun sosCard() {
        val list = store.family()
        m.add(ui.card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val hint = ui.text("Press and hold for 3 seconds", 15.5f, Ui.DANGER, bold = true).apply { gravity = Gravity.CENTER }
            val btn = TextView(m).apply {
                text = "🆘\nSOS"; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.DANGER); setStroke(m.dp(8), Color.parseColor("#F6C3C4")) }
                elevation = m.dp(6).toFloat()
                contentDescription = "SOS. Press and hold for 3 seconds to call your family for help."
            }
            var start = 0L; var fired = false
            val tick = object : Runnable {
                override fun run() {
                    val ms = SystemClock.uptimeMillis() - start
                    if (ms >= 3000) { fired = true; trigger(); return }
                    btn.text = "🆘\n" + (3 - ms / 1000); hint.text = "Keep holding…"
                    handler.postDelayed(this, 100)
                }
            }
            btn.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        fired = false; start = SystemClock.uptimeMillis()
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(160).start()
                        handler.post(tick)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(tick)
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        if (!fired) { btn.text = "🆘\nSOS"; hint.text = "Press and hold for 3 seconds"; if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick() }
                    }
                }
                true
            }
            // a quick tap never calls anyone
            btn.setOnClickListener { m.toast("Hold the SOS button for 3 seconds to call for help.") }
            // TalkBack: "double-tap and hold" starts SOS
            btn.setOnLongClickListener { trigger(); true }
            btn.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "call your family for help"))
                }
            }
            addView(btn, LinearLayout.LayoutParams(m.dp(176), m.dp(176)).apply { topMargin = m.dp(6) })
            put(hint, 14)
            put(ui.text(if (list.isEmpty()) "First add a family member below, so SOS knows whom to call."
                else (if (store.sosAutoCall) "Calls ${who(list[0])} after 5 seconds (you can cancel)" else "Opens a call to ${who(list[0])}") +
                    ", then you can send your location by SMS or WhatsApp.", 13.5f, Ui.MUTED).apply { gravity = Gravity.CENTER }, 4)
            put(equal(ui.button("📍 Share my location") { shareLocation() }, ui.button("🚨 Call 112", "danger") { dial("112") }), 14)
            if (!hasLocation()) put(ui.line(ui.text("📍 Location for SOS is off.", 13f, Ui.MUTED), ui.button("Turn on", "ghost") { askLocation { m.render() } }), 10)
        }, 12)
    }

    /** Views side by side, sharing the width equally (and the same height). */
    private fun equal(vararg views: View) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = m.dp(8) }) }
    }

    private fun trigger() {
        m.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        sos = true; called = ""
        val list = store.family()
        countdown = if (list.isNotEmpty() && store.sosAutoCall) 5 else 0
        handler.removeCallbacks(countTick)
        if (countdown > 0) handler.postDelayed(countTick, 1000)
        if (hasLocation()) findLocation() else locState = "off"
        m.render(); m.scrollTop()
    }

    private val countTick = object : Runnable {
        override fun run() {
            if (!sos || countdown <= 0) return
            countdown--
            if (countdown > 0) { countText?.text = countLine(); handler.postDelayed(this, 1000); return }
            store.family().firstOrNull()?.let { called = who(it); call(it.phone) }
            if (m.tab == "family") m.render()
        }
    }

    private fun countLine() = store.family().firstOrNull()?.let { "📞 Calling ${who(it)} in $countdown…" } ?: ""

    private fun close() {
        sos = false; countdown = 0; called = ""; shareWhenFound = false
        handler.removeCallbacks(countTick); stopLocation()
        m.render()
    }

    private fun sosPanel() {
        val list = store.family()
        m.add(ui.card(Ui.DANGER_SOFT).apply {
            background = ui.round(Ui.DANGER_SOFT, 20, Ui.DANGER)
            addView(ui.text("🆘 Getting help", 24f, Ui.DANGER, bold = true))
            when {
                countdown > 0 -> {
                    val t = ui.text(countLine(), 19f, Ui.INK, bold = true); countText = t
                    put(t, 10)
                    put(ui.button("✕  Cancel the call", "primary") { countdown = 0; handler.removeCallbacks(countTick); m.render() }, 10)
                }
                called.isNotEmpty() -> put(ui.text("📞 Called $called. No answer? Call the next person below.", 15f, Ui.INK), 10)
                list.isEmpty() -> put(ui.text("No family members saved yet. Call 112, or send your location to anyone.", 15f, Ui.INK), 10)
            }
            val lt = ui.text(locLine(), 14f, Ui.INK); locText = lt
            put(lt, 12)
            if (locState == "off") put(ui.button("📍 Allow location", "ghost") { askLocation { findLocation(); m.render() } }, 6)

            fun big(label: String, kind: String, onClick: () -> Unit) = put(ui.button(label, kind, onClick).apply { textSize = 16f; setPadding(m.dp(16), m.dp(14), m.dp(16), m.dp(14)) }, 8)
            if (list.isNotEmpty()) big("💬  Send my location by SMS to family", "sun") { smsFamily(sosMessage()) }
            big("🟢  Send on WhatsApp", "soft") { whatsapp(sosMessage()) }
            list.forEach { c -> big("📞  Call ${who(c)}", "soft") { call(c.phone) } }
            big("🚨  Call 112 · Emergency", "danger") { dial("112") }
            put(ui.button("I'm safe now · Close", "ghost") { close() }, 14)
        }, 12)
    }

    private fun locLine() = when (locState) {
        "found" -> "📍 Your location is ready to send."
        "old" -> "📍 Finding where you are… (a recent location is ready)"
        "finding" -> "📍 Finding where you are…"
        "disabled" -> "📍 Location is turned off on this phone. The message is sent without it (turn on Location in quick settings to add it)."
        "none" -> "📍 Couldn't find your location. The message is sent without it."
        "off" -> "📍 Location isn't allowed, so the message is sent without it."
        else -> ""
    }

    private fun updateLoc() { locText?.text = locLine() }

    private fun mapLink(l: Location) = "https://maps.google.com/?q=" + String.format(Locale.US, "%.6f,%.6f", l.latitude, l.longitude)

    /** The map link; an older position says when it was found, so nobody is misled. */
    private fun whereLine(l: Location): String =
        if (System.currentTimeMillis() - l.time > 10 * 60_000) "📍 My last known location (at " + TIME.format(Instant.ofEpochMilli(l.time).atZone(ZoneId.systemDefault())) + "): " + mapLink(l)
        else "📍 Where I am: " + mapLink(l)

    private fun sosMessage(): String {
        val l = loc
        return "🆘 నాకు సహాయం కావాలి! I need help. Please call me now." +
            (if (l != null) "\n" + whereLine(l) else "") + "\n(sent from Habit AI)"
    }

    // =================== location (only on the phone) ===================
    private fun askLocation(then: () -> Unit) {
        if (hasLocation()) { then(); return }
        AlertDialog.Builder(m).setTitle("Use your location for SOS?")
            .setMessage("When you press SOS or \"Share my location\", Habit AI adds a Google Maps link to where you are in the message " +
                "that you send to your family by SMS or WhatsApp.\n\nYour location is used only at that moment. It stays on your phone: " +
                "it is never sent to the Habit server or to the AI, and it isn't saved.")
            .setPositiveButton("Allow") { _, _ ->
                afterLocation = then
                m.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), MainActivity.REQ_LOCATION)
            }
            .setNegativeButton("Not now", null).show()
    }

    /** Called by MainActivity after Android's location question. */
    fun onLocationAnswer() {
        val then = afterLocation; afterLocation = null
        if (hasLocation()) then?.invoke()
        else m.toast("Without location, SOS still calls your family, but can't send where you are.")
    }

    @SuppressLint("MissingPermission")
    private fun findLocation() {
        if (!hasLocation()) { locState = "off"; return }
        val lm = m.getSystemService(LocationManager::class.java) ?: run { locState = "none"; return }
        val all = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val last = all.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }.maxByOrNull { it.time }
        if (last != null && (loc == null || last.time > loc!!.time)) loc = last
        val fresh = loc?.let { System.currentTimeMillis() - it.time < 2 * 60_000 } == true
        val on = all.take(2).filter { p -> try { lm.isProviderEnabled(p) } catch (e: Exception) { false } }
        if (on.isEmpty()) { locState = if (loc != null) "found" else "disabled"; updateLoc(); return }
        locState = if (fresh) "found" else if (loc != null) "old" else "finding"
        stopLocation()
        val cancel = CancellationSignal(); locCancel = cancel
        on.forEach { p ->
            try {
                if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, cancel, m.mainExecutor) { l -> gotLocation(l) }
                else @Suppress("DEPRECATION") lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
            } catch (e: Exception) { }
        }
        handler.postDelayed(giveUp, 30_000)
        updateLoc()
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = gotLocation(l)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val giveUp = Runnable {
        if (locState == "finding") { locState = "none"; updateLoc() } else if (locState == "old") { locState = "found"; updateLoc() }
        if (shareWhenFound) { shareWhenFound = false; if (loc == null) m.toast("Couldn't find your location. Turn on Location in your phone's quick settings and try again.") else shareNow() }
    }

    private fun gotLocation(l: Location?) {
        if (l != null) {
            val cur = loc
            val curOld = cur == null || System.currentTimeMillis() - cur.time > 2 * 60_000
            if (curOld || l.accuracy <= cur!!.accuracy) loc = l
            locState = "found"
        } else if (loc == null && locState == "finding") locState = "none"
        updateLoc()
        if (shareWhenFound && loc != null && locState == "found") { shareWhenFound = false; handler.removeCallbacks(giveUp); shareNow() }
    }

    private fun stopLocation() {
        try { locCancel?.cancel() } catch (e: Exception) { }
        locCancel = null
        handler.removeCallbacks(giveUp)
        if (Build.VERSION.SDK_INT < 30) try { m.getSystemService(LocationManager::class.java)?.removeUpdates(listener) } catch (e: Exception) { }
    }

    private fun shareLocation() {
        askLocation {
            findLocation()
            if (locState == "found") shareNow()
            else if (locState == "disabled") m.toast("Location is turned off. Turn it on in your phone's quick settings, then try again.")
            else { shareWhenFound = true; m.toast("Finding where you are…") }
        }
    }

    private fun shareNow() {
        val l = loc ?: return
        val text = whereLine(l) + "\n(sent from Habit AI)"
        AlertDialog.Builder(m).setTitle("Send your location")
            .setItems(arrayOf("💬 SMS to family", "🟢 WhatsApp", "↗ Other apps")) { _, w ->
                when (w) { 0 -> smsFamily(text); 1 -> whatsapp(text); else -> share(text) }
            }.show()
    }

    // =================== calling and sending ===================
    private fun clean(n: String) = n.filter { it.isDigit() || it == '+' }

    /** Calls directly when allowed; otherwise the phone app opens with the number ready. */
    private fun call(number: String) {
        val n = clean(number); if (n.isEmpty()) return
        m.leavingForSystemScreen = true
        val direct = m.granted(Manifest.permission.CALL_PHONE)
        try { m.startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }
        catch (e: Exception) { dial(n) }
    }

    private fun dial(n: String) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + clean(n)))) } catch (e: Exception) { m.toast("Couldn't open the phone app. Please dial $n.") }
    }

    private fun smsFamily(text: String) {
        val list = store.family()
        if (list.isEmpty()) { share(text); return }
        val sep = if (Build.MANUFACTURER.equals("samsung", true)) "," else ";"
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + list.joinToString(sep) { clean(it.phone) })).putExtra("sms_body", text)) }
        catch (e: Exception) { share(text) }
    }

    /** WhatsApp: to one person when [phone] is given, otherwise you pick the chats. */
    private fun whatsapp(text: String, phone: String? = null) {
        m.leavingForSystemScreen = true
        if (phone != null) {
            try { m.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(phone) + "?text=" + Uri.encode(text)))); return } catch (e: Exception) { }
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { m.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)); return } catch (e: Exception) { }
        }
        m.toast("WhatsApp isn't installed."); share(text)
    }

    /** wa.me needs the number with the country code and no +: 98480 12345 → 919848012345 */
    private fun waNumber(p: String): String {
        val d = p.filter { it.isDigit() }
        return when {
            p.trim().startsWith("+") -> d
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            else -> d
        }
    }

    private fun share(text: String) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send with")) } catch (e: Exception) { }
    }

    // =================== family members ===================
    private fun familySection() {
        val list = store.family()
        m.add(ui.section("👨‍👩‍👧 Family members", "SOS calls the first person. Up to 5 people.").apply {
            if (list.isEmpty()) put(ui.text("No one added yet.", 14f, Ui.MUTED), 10)
            list.forEachIndexed { i, c ->
                val name = ui.stack(
                    ui.text((if (i == 0) "⭐ " else "") + c.name, 16f, Ui.INK, bold = true),
                    ui.text(listOf(c.relation, c.phone).filter { it.isNotBlank() }.joinToString(" · "), 13f, Ui.MUTED))
                fun small(b: android.widget.Button, what: String) = b.apply { setPadding(m.dp(12), m.dp(8), m.dp(12), m.dp(8)); contentDescription = what }
                put(ui.line(name, ui.row(
                    small(ui.button("📞") { call(c.phone) }, "Call ${c.name}"),
                    small(ui.button("🟢") { whatsapp("", c.phone) }, "WhatsApp ${c.name}"),
                    small(ui.button("⋯", "ghost") { contactMenu(i) }, "More for ${c.name}"), gap = 6)), if (i == 0) 12 else 10)
            }
            if (list.size < 5) put(equal(ui.button("＋ From contacts", "primary") { pickContact() }, ui.button("＋ Type a number") { editContact(-1, null) }), 14)
            if (list.isNotEmpty()) {
                put(ui.check("📞 SOS calls by itself after 5 seconds", store.sosAutoCall) { on ->
                    store.sosAutoCall = on
                    if (on && !m.granted(Manifest.permission.CALL_PHONE)) askCall()
                    m.render()
                }, 12)
                if (store.sosAutoCall && !m.granted(Manifest.permission.CALL_PHONE)) put(ui.line(
                    ui.text("Allow calling, so SOS can call without you tapping. Otherwise the phone app opens with the number ready.", 12.5f, Ui.MUTED),
                    ui.button("Allow", "ghost") { askCall() }), 4)
            }
        }, 14)
    }

    private fun askCall() {
        AlertDialog.Builder(m).setTitle("Let SOS call by itself?")
            .setMessage("After you hold SOS for 3 seconds, Habit AI waits 5 more seconds (you can cancel), then calls your first family member directly.\n\n" +
                "Habit AI only calls when you press SOS or a 📞 button. Without this, the phone app opens with the number ready and you tap Call.")
            .setPositiveButton("Allow") { _, _ -> m.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), MainActivity.REQ_CALL) }
            .setNegativeButton("Not now", null).show()
    }

    private fun contactMenu(i: Int) {
        val list = store.family(); val c = list.getOrNull(i) ?: return
        val opts = mutableListOf("Edit"); if (i > 0) opts.add(0, "⭐ Make first (SOS calls this person)"); opts.add("Remove")
        AlertDialog.Builder(m).setTitle(c.name).setItems(opts.toTypedArray()) { _, w ->
            when (opts[w]) {
                "Edit" -> editContact(i, c)
                "Remove" -> AlertDialog.Builder(m).setMessage("Remove ${c.name}?")
                    .setPositiveButton("Remove") { _, _ -> store.saveFamily(list.filterIndexed { k, _ -> k != i }); m.render() }.setNegativeButton("Keep", null).show()
                else -> { store.saveFamily(listOf(c) + list.filterIndexed { k, _ -> k != i }); m.render() }
            }
        }.show()
    }

    private fun pickContact() {
        m.leavingForSystemScreen = true
        try { m.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), MainActivity.REQ_CONTACT) }
        catch (e: Exception) { m.leavingForSystemScreen = false; editContact(-1, null) }
    }

    /** The person picked in Contacts (Android shares only that one contact with us). */
    fun onContact(uri: Uri) {
        var name = ""; var phone = ""
        try {
            m.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: ""; phone = c.getString(1) ?: "" }
            }
        } catch (e: Exception) { }
        if (phone.isBlank()) { m.toast("That contact has no phone number."); return }
        editContact(-1, FamilyContact(name, phone, ""))
    }

    private fun editContact(index: Int, start: FamilyContact?) {
        val form = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(m.dp(20), m.dp(8), m.dp(20), 0) }
        val name = ui.input("Name, e.g. Ravi", start?.name ?: "")
        val phone = ui.input("Phone number", start?.phone ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val rel = ui.input("Who is this? e.g. Son, Daughter (optional)", start?.relation ?: "")
        listOf(name, phone, rel).forEach { form.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(10) }) }
        val d = AlertDialog.Builder(m).setTitle(if (index >= 0) "Edit family member" else "Add family member").setView(form)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = phone.text.toString().trim()
                if (clean(p).count { it.isDigit() } < 3) { phone.error = "Enter the phone number"; return@setOnClickListener }
                val c = FamilyContact(name.text.toString().trim().ifEmpty { p }.take(40), p.take(20), rel.text.toString().trim().take(30))
                val list = store.family().toMutableList()
                if (index in list.indices) list[index] = c else list.add(c)
                val first = list.size == 1 && index < 0
                store.saveFamily(list); d.dismiss(); m.render()
                if (first && store.sosAutoCall && !m.granted(Manifest.permission.CALL_PHONE)) askCall()
            }
        }
        d.show()
    }

    // =================== medicine reminders ===================
    private fun medicineSection(): View {
        val sec = ui.section("💊 Medicine reminders", "A reminder every day at the times you choose. It rings even when the app is closed.")
        sec.apply {
            val box = ui.input("Medicine, e.g. BP tablet, 1 after food", medName)
            box.onText { medName = it }
            put(box, 12)
            put(ui.text("When?", 13.5f, Ui.MUTED), 12)
            val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
            val chips = PRESETS + extraTimes.filter { t -> PRESETS.none { it.first == t } }.map { it to "⏰" }
            chips.forEachIndexed { i, (t, label) ->
                row.addView(ui.chip(label + " " + TIME.format(t), t in medTimes) { if (!medTimes.remove(t)) medTimes.add(t); m.render() },
                    LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
            }
            row.addView(ui.chip("＋ Other time", false) {
                TimePickerDialog(m, { _, h, min -> val t = LocalTime.of(h, min); extraTimes.add(t); medTimes.add(t); m.render() }, 9, 0, false).show()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = m.dp(6) })
            put(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 6)
            put(ui.button("Save medicine reminder", "primary") { saveMedicine() }, 14)

            val meds = store.reminders().filter { it.text.startsWith("💊") }
                .sortedBy { Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalTime() }
            if (meds.isNotEmpty()) put(ui.label("Your medicines"), 18)
            meds.forEach { r ->
                val time = TIME.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()))
                put(ui.line(ui.stack(ui.text(r.text, 15f, Ui.INK, bold = true),
                    ui.text((if (r.repeat == "daily") "Every day · " else "") + time, 13f, Ui.MUTED)),
                    ui.button("Cancel", "danger") { Reminders.cancel(m, r); m.render() }), 10)
            }
        }
        m.add(sec, 14)
        return sec
    }

    private fun saveMedicine() {
        val name = medName.trim()
        if (name.isEmpty()) { m.toast("Write the medicine name first."); return }
        if (medTimes.isEmpty()) { m.toast("Choose at least one time."); return }
        if (Build.VERSION.SDK_INT >= 33 && !m.granted(Manifest.permission.POST_NOTIFICATIONS))
            m.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), MainActivity.REQ_NOTIFY)
        val zone = ZoneId.systemDefault(); val now = ZonedDateTime.now(zone)
        var allExact = true
        medTimes.forEach { t ->
            var at = ZonedDateTime.of(LocalDate.now(zone), t, zone)
            if (!at.isAfter(now)) at = at.plusDays(1)
            if (!Reminders.add(m, at.toInstant().toEpochMilli(), "💊 $name", "daily").second) allExact = false
        }
        val n = medTimes.size
        medName = ""; medTimes.clear(); extraTimes.clear()
        m.toast("Saved: $n reminder" + (if (n > 1) "s" else "") + " every day" + if (allExact) "" else " (may ring a few minutes late)")
        m.render()
    }

    // =================== emergency numbers ===================
    private fun numbersSection() {
        m.add(ui.label("Emergency numbers · all India"), 20)
        NUMBERS.chunked(2).forEachIndexed { i, pair ->
            m.add(equal(*pair.map { (num, name, what, icon) ->
                LinearLayout(m).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(m.dp(14), m.dp(12), m.dp(14), m.dp(12))
                    background = ui.round(Color.WHITE, 18, Ui.LINE)
                    addView(ui.text("$icon $num", 20f, if (num == "112") Ui.DANGER else Ui.TEAL_DARK, bold = true))
                    addView(ui.text(name, 14f, Ui.INK, bold = true))
                    addView(ui.text(what, 12f, Ui.MUTED))
                    contentDescription = "Call $num, $name"
                    setOnClickListener { dial(num) }
                }
            }.toTypedArray()), if (i == 0) 8 else 8)
        }
    }
}
