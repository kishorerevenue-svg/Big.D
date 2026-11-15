package com.soulfriend.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Habit itself notices about its owner, only for what they switched on (Settings › What I know about you): the
 * channels they watch most in Habit Media, and who they chat with most in Habit Chat. Kept on this phone; the lines go
 * to the pet's brain with its memory, like the things they told it themselves. Nothing from outside Habit is read (no
 * call history, SMS or other apps' messages: Android doesn't allow it, and Habit doesn't ask).
 */
object Know {
    /** A video of [channel] was opened in Habit Media. */
    fun watched(store: Store, channel: String) {
        val ch = channel.trim().take(60)
        if (!store.learnWatch || ch.isEmpty()) return
        try {
            val j = try { JSONObject(store.watchLog) } catch (e: Exception) { JSONObject() }
            j.put(ch, j.optInt(ch) + 1)
            if (j.length() > 80) {                                   // the least watched are forgotten
                val keys = j.keys().asSequence().toList().sortedBy { j.optInt(it) }
                keys.take(j.length() - 60).forEach { j.remove(it) }
            }
            store.watchLog = j.toString()
        } catch (e: Exception) { }
    }

    /** The lines for the pet's memory now (kept in the store, where the brain takes them from). */
    fun refresh(ctx: Context, store: Store): List<String> {
        val out = ArrayList<String>()
        if (store.learnWatch) try {
            val j = JSONObject(store.watchLog)
            val top = j.keys().asSequence().toList().filter { j.optInt(it) >= 3 }.sortedByDescending { j.optInt(it) }.take(6)
            if (top.isNotEmpty()) out.add("Watches these most in Habit Media: " + top.joinToString(", "))
        } catch (e: Exception) { }
        if (store.learnPeople) try {
            val db = ChatDb.get(ctx)
            val since = System.currentTimeMillis() - 30L * 86_400_000L
            val top = db.convs().filter { it.isDm && !it.isLocal && !it.blocked }.take(25)
                .map { c -> c.name to db.msgs(c.cid, 200).count { it.at > since } }
                .filter { it.second >= 5 }.sortedByDescending { it.second }.take(4)
            if (top.isNotEmpty()) out.add("Chats most in Habit Chat with: " + top.joinToString(", ") { it.first })
        } catch (e: Exception) { }
        store.noticed = JSONArray(out).toString()
        return out
    }
}
