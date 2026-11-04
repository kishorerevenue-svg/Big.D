package com.soulfriend.pet

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** The pet's little cartoon sounds (tada, laugh, wow…), made for BigD AI from scratch: in assets/sfx. */
object SoundFx {
    /** name -> how long it plays (ms), so the voice can start right after it. */
    val LENGTH = mapOf("tada" to 1000, "laugh" to 750, "wow" to 800, "oops" to 550, "sad" to 1700, "applause" to 1300,
        "drumroll" to 1600, "boing" to 600, "ding" to 500, "whoosh" to 550)

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private val waiting = HashMap<Int, Float>()

    /** Loads the sounds once (small files, kept in memory). */
    @Synchronized fun load(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        p.setOnLoadCompleteListener { sp, id, status ->
            synchronized(this) {
                if (status == 0) ready.add(id)
                waiting.remove(id)?.let { v -> if (status == 0) sp.play(id, v, v, 1, 0, 1f) }
            }
        }
        pool = p
        for (name in LENGTH.keys) try { ctx.assets.openFd("sfx/$name.wav").use { fd -> ids[name] = p.load(fd, 1) } } catch (e: Exception) { }
    }

    /** Plays [name]; returns how long to wait before speaking (0 when there is no such sound). */
    @Synchronized fun play(ctx: Context, name: String, volume: Float = 0.7f): Long {
        if (name !in LENGTH) return 0
        load(ctx)
        val p = pool ?: return 0
        val id = ids[name] ?: return 0
        if (id in ready) p.play(id, volume, volume, 1, 0, 1f) else waiting[id] = volume
        return (LENGTH[name] ?: 0).toLong()
    }
}

/** Splits long text into pieces the phone's voice can read one after another (it reads at most about 4000 letters at once). */
object Speech {
    fun chunks(text: String, max: Int): List<String> {
        val t = text.replace(Regex("[ \\t]+"), " ").trim()
        if (t.length <= max) return listOf(t).filter { it.isNotBlank() }
        // sentences: after . ! ? । and new lines
        val parts = Regex("(?<=[.!?।\\n])\\s+").split(t).flatMap { s ->
            if (s.length <= max) listOf(s) else s.chunked(max).let { list ->
                // a very long sentence: cut at spaces where possible
                val out = mutableListOf<String>(); var rest = s
                while (rest.length > max) { val cut = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max; out.add(rest.substring(0, cut)); rest = rest.substring(cut).trimStart() }
                if (rest.isNotBlank()) out.add(rest); out.ifEmpty { list }
            }
        }
        val out = mutableListOf<String>(); val cur = StringBuilder()
        for (p in parts) {
            if (cur.isNotEmpty() && cur.length + 1 + p.length > max) { out.add(cur.toString().trim()); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(p)
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }
}
