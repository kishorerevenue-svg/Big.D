package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone contacts for Habit Chat's "Invite your people", search and "Your contacts on Habit". Read on the phone.
 * Shown first: ⭐ favourites (starred in the phone's Contacts) and people you called or invited from Habit;
 * everyone else through the search bar. Only with "Find my contacts on Habit" on (asked once, Chat settings): each
 * contact's number goes to Habit's server as a scrambled code (SHA-256), is compared with the numbers people log in
 * with, and isn't kept there; who was found stays on this phone.
 */
object ChatContacts {
    class Person(val name: String, val number: String, val starred: Boolean = false)

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private val COLS = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.STARRED)

    private fun read(ctx: Context, uri: Uri, sel: String?, n: Int): List<Person> {
        if (!allowed(ctx)) return emptyList()
        val out = ArrayList<Person>(); val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(uri, COLS, sel, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")?.use { c ->
                while (c.moveToNext() && out.size < n) {
                    val id = c.getString(0) ?: continue
                    val num = (c.getString(2) ?: "").trim()
                    if (num.count { it.isDigit() } < 6 || !seen.add(id)) continue
                    out.add(Person(c.getString(1) ?: num, num, c.getInt(3) == 1))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** ⭐ Favourites in the phone's Contacts app. */
    fun favourites(ctx: Context, n: Int = 8) = read(ctx, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.STARRED + "=1", n)

    /** Contacts whose name or number matches [q]. */
    fun search(ctx: Context, q: String, n: Int = 20): List<Person> {
        val s = q.trim(); if (s.length < 2) return emptyList()
        return read(ctx, Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(s)), null, n)
    }

    // ---------- contacts on Habit ----------
    /** "+91 77309-49649", "07730949649" → the 10 digits; "+1 555 123 4567" → "+15551234567" (the way the server keeps the
     *  numbers people log in with); "" when it isn't a mobile number. */
    fun norm(raw: String): String {
        val s = raw.trim()
        var d = s.filter { it.isDigit() }
        val intl = s.startsWith("+") || s.startsWith("00")
        if (!s.startsWith("+") && s.startsWith("00")) d = d.drop(2)
        if (intl && !d.startsWith("91")) return if (d.length in 8..15) "+$d" else ""
        if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        return if (d.length == 10 && d[0] in '6'..'9') d else ""
    }

    /** The scrambled code of a number (the server makes the same from the numbers people log in with). */
    fun code(id: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(("habit-phone:" + id).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(32)

    /** "on": find contacts on Habit; "off": don't; "": not asked yet. */
    fun matchState(ctx: Context) = ChatDb.get(ctx).kv("contactsMatch")
    fun setMatch(ctx: Context, on: Boolean) {
        val db = ChatDb.get(ctx)
        db.setKv("contactsMatch", if (on) "on" else "off")
        if (!on) { db.setKv("onHabit", ""); db.setKv("onHabitAt", ""); db.setKv("onHabitTry", "") }
    }

    /** Every number in the phone's contacts (one entry per number), with the contact's name. */
    private fun everyNumber(ctx: Context): Map<String, Person> {
        val out = LinkedHashMap<String, Person>()
        if (!allowed(ctx)) return out
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, COLS, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 5000) {
                    val num = (c.getString(2) ?: "").trim()
                    val id = norm(num)
                    if (id.isNotEmpty() && !out.containsKey(id)) out[id] = Person(c.getString(1) ?: num, num, c.getInt(3) == 1)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    @Volatile private var matching = false
    /** Asks Habit's server which contacts are on Habit: once a day ([force]: now). [changed] runs (on the main thread) when
     *  the list changed. Nothing happens unless "Find my contacts on Habit" is on. */
    fun matchNow(ctx: Context, store: Store, force: Boolean = false, changed: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val db = ChatDb.get(app)
        if (matching || !allowed(app) || db.kv("contactsMatch") != "on" || !ChatSync.isSetUp(app)) return
        val now = System.currentTimeMillis()
        val last = db.kv("onHabitAt").toLongOrNull() ?: 0L
        val tried = db.kv("onHabitTry").toLongOrNull() ?: 0L
        if (!force && (now - last < 24 * 3_600_000L || now - tried < 30 * 60_000L)) return
        matching = true
        db.setKv("onHabitTry", now.toString())
        Thread {
            var diff = false
            try {
                val nums = everyNumber(app)
                val byCode = HashMap<String, String>()
                for (id in nums.keys) byCode[code(id)] = id
                val found = JSONObject()
                var ok = true
                for (part in byCode.keys.toList().chunked(1000)) {
                    val r = ChatSync.api(store, "POST", "/api/chat/match", JSONObject().put("h", JSONArray(part)).toString())
                    if (!r.ok) { ok = false; break }
                    val arr = r.json.optJSONArray("found") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = byCode[o.optString("h")] ?: continue
                        val who = o.optJSONObject("person") ?: continue
                        val c = nums[id] ?: continue
                        found.put(id, JSONObject(who.toString()).put("cname", c.name).put("num", c.number).put("star", c.starred))
                    }
                }
                if (ok) {
                    val s = found.toString()
                    diff = s != db.kv("onHabit")
                    db.setKv("onHabit", s); db.setKv("onHabitAt", now.toString())
                }
            } catch (e: Exception) { }
            matching = false
            if (diff && changed != null) android.os.Handler(android.os.Looper.getMainLooper()).post(changed)
        }.start()
    }

    private var foundS = ""
    private var foundJ = JSONObject()
    /** What the last check found: number → the Habit person (with the contact's own name as "cname"). */
    private fun found(ctx: Context): JSONObject {
        val s = ChatDb.get(ctx).kv("onHabit")
        if (s != foundS) { foundJ = try { JSONObject(s.ifEmpty { "{}" }) } catch (e: Exception) { JSONObject() }; foundS = s }
        return foundJ
    }

    /** The Habit person with this phone number (from the last check), or null. */
    fun onHabit(ctx: Context, number: String): JSONObject? {
        if (matchState(ctx) != "on") return null
        val id = norm(number)
        return if (id.isEmpty()) null else found(ctx).optJSONObject(id)
    }

    /** Contacts on Habit: (the contact, the Habit person), ⭐ first, then by name. */
    fun onHabitList(ctx: Context): List<Pair<Person, JSONObject>> {
        if (matchState(ctx) != "on" || !allowed(ctx)) return emptyList()
        val f = found(ctx)
        val out = ArrayList<Pair<Person, JSONObject>>(); val seen = HashSet<String>()
        for (k in f.keys()) {
            val p = f.optJSONObject(k) ?: continue
            if (!seen.add(p.optString("uid"))) continue
            out.add(Person(p.optString("cname").ifBlank { p.optString("name") }, p.optString("num"), p.optBoolean("star")) to p)
        }
        return out.sortedWith(compareByDescending<Pair<Person, JSONObject>> { it.first.starred }.thenBy { it.first.name.lowercase() })
    }

    /** People called or invited from Habit (newest first). */
    fun recent(ctx: Context): List<Person> = try {
        val a = JSONArray(ChatDb.get(ctx).kv("recentContacts").ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedByDescending { it.optLong("at") }.map { Person(it.optString("name"), it.optString("number")) }
    } catch (e: Exception) { emptyList() }

    fun remember(ctx: Context, name: String, number: String) {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        val old = try { JSONArray(ChatDb.get(ctx).kv("recentContacts").ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }
        val keep = JSONArray().put(JSONObject().put("name", name).put("number", number).put("at", System.currentTimeMillis()))
        for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("number").filter { it.isDigit() }.takeLast(10) != digits && keep.length() < 20) keep.put(o) }
        ChatDb.get(ctx).setKv("recentContacts", keep.toString())
    }

    /** The people shown under "Invite your people": favourites first, then recent ones (no repeats, nobody already on Habit). */
    fun suggested(ctx: Context, n: Int = 8): List<Person> {
        val out = ArrayList<Person>(); val seen = HashSet<String>()
        for (p in favourites(ctx, n + 4) + recent(ctx)) { if (onHabit(ctx, p.number) == null && seen.add(p.number.filter { it.isDigit() }.takeLast(10)) && out.size < n) out.add(p) }
        return out
    }

    fun inviteText(ctx: Context, store: Store, name: String): String {
        val me = ChatSync.me(ctx)
        val h = me.optString("handle")
        val link = if (h.isNotEmpty()) store.server.trimEnd('/') + "/c/" + h else store.server.trimEnd('/') + "/download"
        // the first real name, not an initial or surname letters ("K. Srinivas", "Ja Srinivas Reddy" → Srinivas)
        val words = name.trim().split(Regex("[\\s.]+")).filter { it.isNotBlank() }
        val first = words.firstOrNull { w -> w.count { it.isLetter() || it.code > 0x7F } >= 3 && !(w.length <= 3 && w.all { it.isUpperCase() }) } ?: words.firstOrNull().orEmpty()
        return if (store.lang == "te") "హాయ్ $first! Habit AI లో చాట్, కాల్ చేద్దాం (ఉచితం, ఎండ్ టు ఎండ్ లాక్, తెలుగు & ఇంగ్లీష్): $link"
            else "Hi $first! Let's chat and call on Habit AI (free, locked end to end, Telugu and English): $link"
    }

    /** WhatsApp to that number with the invite (an SMS if WhatsApp isn't there). */
    fun invite(a: Activity, store: Store, p: Person) {
        remember(a, p.name, p.number)
        val text = inviteText(a, store, p.name)
        val d = p.number.filter { it.isDigit() }
        val n = when { p.number.trim().startsWith("+") -> d; d.length == 10 -> "91$d"; d.length == 11 && d.startsWith("0") -> "91" + d.drop(1); else -> d }
        (a as? MainActivity)?.leavingForSystemScreen = true
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$n&text=" + Uri.encode(text)))); return } catch (e: Exception) { }
        try { a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", text)) } catch (e: Exception) { }
    }

    /** A normal phone call (the phone's dialer opens with the number). */
    fun call(a: Activity, p: Person) {
        remember(a, p.name, p.number)
        (a as? MainActivity)?.leavingForSystemScreen = true
        try { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number)))) } catch (e: Exception) { }
    }
}
