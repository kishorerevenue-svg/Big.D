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

/**
 * "Your channels": the YouTube channels someone watches or follows inside Habit Media, so their new videos come to the
 * Media page without a YouTube login. The list stays on this phone; to bring the new videos the app asks the Habit server
 * for those channels' newest videos (the server keeps nothing about who asked).
 */
object MyChannels {
    class Ch(val id: String, val name: String, val n: Int, val follow: Boolean, val at: Long)
    private val ID = Regex("^UC[\\w-]{22}$")
    fun valid(id: String) = ID.matches(id)
    private fun load(store: Store) = try { JSONObject(store.myCh) } catch (e: Exception) { JSONObject() }

    fun list(store: Store): List<Ch> {
        val j = load(store)
        return j.keys().asSequence().toList().mapNotNull { id -> j.optJSONObject(id)?.let { Ch(id, it.optString("n"), it.optInt("c"), it.optBoolean("f"), it.optLong("at")) } }
            .sortedWith(compareByDescending<Ch> { it.follow }.thenByDescending { it.n }.thenByDescending { it.at })
    }
    /** The channels whose new videos are shown: the followed ones, and those watched at least twice. */
    fun ids(store: Store): List<String> = list(store).filter { it.follow || it.n >= 2 }.take(20).map { it.id }
    fun following(store: Store, id: String) = load(store).optJSONObject(id)?.optBoolean("f") == true

    private fun put(store: Store, id: String, name: String, change: (JSONObject) -> Unit) {
        if (!valid(id)) return
        try {
            val j = load(store)
            val o = j.optJSONObject(id) ?: JSONObject()
            if (name.isNotBlank()) o.put("n", name.trim().take(60))
            change(o); o.put("at", System.currentTimeMillis())
            j.put(id, o)
            if (j.length() > 60) {                                   // the least watched (not followed) are forgotten
                val drop = j.keys().asSequence().toList().filter { j.optJSONObject(it)?.optBoolean("f") != true }
                    .sortedBy { j.optJSONObject(it)?.optInt("c") ?: 0 }.take(j.length() - 50)
                drop.forEach { j.remove(it) }
            }
            store.myCh = j.toString()
        } catch (e: Exception) { }
    }
    /** A good part of a video of this channel was watched in Habit. */
    fun watched(store: Store, id: String, name: String) { if (store.myChAuto) put(store, id, name) { it.put("c", it.optInt("c") + 1) } }
    fun setFollow(store: Store, id: String, name: String, on: Boolean) = put(store, id, name) { it.put("f", on) }
    fun remove(store: Store, id: String) { try { val j = load(store); j.remove(id); store.myCh = j.toString() } catch (e: Exception) { } }
}

/** YouTube refused to play a video inside the app: the Habit server is told, so it stops showing it (and, after a few,
 *  that channel) to everyone. Only the video's id and its channel's id are sent. */
object NoEmbed {
    private val told = HashSet<String>()
    fun tell(store: Store, id: String, chId: String = "") {
        if (id.length != 11 || !told.add(id)) return
        Thread { try { Net.post(store, "/api/videos/blocked", JSONObject().put("id", id).put("chId", chId), timeoutMs = 10_000) } catch (e: Exception) { } }.start()
    }
}
