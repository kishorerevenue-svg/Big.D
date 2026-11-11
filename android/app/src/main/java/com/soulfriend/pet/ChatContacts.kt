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
 * Phone contacts for Habit Chat's "Invite your people" and search. Read on the phone only, never uploaded.
 * Shown first: ⭐ favourites (starred in the phone's Contacts) and people you called or invited from Habit;
 * everyone else through the search bar.
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

    /** The people shown under "Invite your people": favourites first, then recent ones (no repeats). */
    fun suggested(ctx: Context, n: Int = 8): List<Person> {
        val out = ArrayList<Person>(); val seen = HashSet<String>()
        for (p in favourites(ctx, n) + recent(ctx)) { if (seen.add(p.number.filter { it.isDigit() }.takeLast(10)) && out.size < n) out.add(p) }
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
