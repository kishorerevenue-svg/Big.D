package com.soulfriend.pet

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Habit's own ear for its name, like a smart speaker's wake word ("Habit" / "హ్యాబిట్"). Everything stays on the phone:
 * the sound goes through Google's free speech features (openWakeWord's melspectrogram + speech_embedding, Apache 2.0,
 * in assets/wake) and a small brain made on Kaggle (brain/habit_wake.py), kept in the owner's Hugging Face repo and
 * downloaded once. Nothing is sent anywhere while it waits; only when it hears its name does the voice typing start.
 * The steps are the same as the training's (checked with brain/… stream test): every 80 ms, the mel of the last 1760
 * samples (8 frames, x/10+2), the embedding of the last 76 mel frames, the last 16 embeddings -> the small brain.
 */
object WakeWord {
    private const val FILE = "habit_wake.json"
    @Volatile private var model: Model? = null
    @Volatile private var runtimeOk = false
    @Volatile private var runtimeAsked = false
    @Volatile private var checking = false

    class Model(val threshold: Float, val version: Long, val recall: Double, val wakesPerHour: Double, val made: String,
                private val w1: FloatArray, private val b1: FloatArray, private val w2: FloatArray, private val b2: FloatArray,
                private val w3: FloatArray, private val b3: Float, private val hidden: Int) {
        private val h1 = FloatArray(hidden)
        private val h2 = FloatArray(hidden)

        /** x: the last 16 embeddings (96 each), oldest first. */
        fun score(x: FloatArray): Float {
            val d = x.size
            for (j in 0 until hidden) h1[j] = b1[j]
            for (i in 0 until d) {                                       // w1 is [d][hidden] (row major)
                val v = x[i]; if (v == 0f) continue
                val o = i * hidden
                for (j in 0 until hidden) h1[j] += v * w1[o + j]
            }
            for (j in 0 until hidden) if (h1[j] < 0f) h1[j] = 0f
            for (j in 0 until hidden) h2[j] = b2[j]
            for (i in 0 until hidden) {
                val v = h1[i]; if (v == 0f) continue
                val o = i * hidden
                for (j in 0 until hidden) h2[j] += v * w2[o + j]
            }
            var z = b3
            for (j in 0 until hidden) if (h2[j] > 0f) z += h2[j] * w3[j]
            return (1.0 / (1.0 + Math.exp(-z.toDouble()))).toFloat()
        }

        companion object {
            fun parse(text: String): Model? = try {
                val j = JSONObject(text)
                if (j.optString("kind") != "habit-wake" || j.optInt("format") != 1 || j.optInt("window", 16) != 16) null
                else {
                    val l = j.getJSONObject("layers")
                    fun arr(k: String): FloatArray {
                        val b = Base64.decode(l.getJSONObject(k).getString("data"), Base64.DEFAULT)
                        val f = FloatArray(b.size / 4)
                        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
                        return f
                    }
                    val hidden = l.getJSONObject("b1").getJSONArray("shape").getInt(0)
                    val w1 = arr("w1"); val b1 = arr("b1"); val w2 = arr("w2"); val b2 = arr("b2"); val w3 = arr("w3"); val b3 = arr("b3")
                    if (w1.size != 16 * 96 * hidden || w2.size != hidden * hidden || w3.size != hidden || b3.size != 1) null
                    else Model(j.optDouble("threshold", 0.9).toFloat().coerceIn(0.3f, 0.99f), j.optLong("version"), j.optDouble("recall"),
                        j.optDouble("wakes_per_hour"), j.optString("made"), w1, b1, w2, b2, w3, b3[0], hidden)
                }
            } catch (e: Exception) { null }
        }
    }

    /** At the start: loads the ear if it is on the phone, gets the speech runtime (Google Play services) ready. */
    fun prepare(ctx: Context) {
        if (model == null) model = try { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.let { Model.parse(it) } } catch (e: Exception) { null }
        if (!runtimeAsked) {
            runtimeAsked = true
            try {
                com.google.android.gms.tflite.java.TfLite.initialize(ctx.applicationContext)
                    .addOnSuccessListener { runtimeOk = true }
                    .addOnFailureListener { runtimeOk = false; runtimeAsked = false }
            } catch (e: Throwable) { runtimeAsked = false }
        }
    }

    fun model(): Model? = model

    /** The name it is called by is its own (Habit), so its ear can be used (another name: the voice typing checks). */
    fun forName(wakeName: String): Boolean {
        val n = wakeName.trim().lowercase()
        return n.isEmpty() || Regex("habit|హ్యాబిట్|హాబిట్|हैबिट").containsMatchIn(n)
    }

    /** A new detector (null: no ear yet, or the speech runtime isn't there). One per listening. */
    fun detector(ctx: Context): Detector? {
        val m = model ?: return null
        if (!runtimeOk) { prepare(ctx); return null }
        return try { Detector(ctx, m) } catch (e: Throwable) { null }
    }

    /** Once a day (or [now]): asks the server where Habit's ear is, and downloads it when there is a new one. */
    fun refresh(ctx: Context, store: Store, now: Boolean = false, done: ((String) -> Unit)? = null) {
        val p = ctx.getSharedPreferences("wake_ear", Context.MODE_PRIVATE)
        if (checking || (!now && System.currentTimeMillis() - p.getLong("checked", 0L) < 20 * 3600_000L)) return
        checking = true
        Thread {
            var msg: String
            try {
                val r = Net.get(store, "/brain/wake")
                val url = r.json.optString("url")
                val tag = r.json.optString("etag")
                p.edit().putLong("checked", System.currentTimeMillis()).apply()
                if (!r.ok || url.isBlank() || !r.json.optBoolean("ready")) msg = "not yet"
                else if (tag.isNotBlank() && tag == p.getString("etag", "") && model != null) msg = "same"
                else {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
                    val text = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
                    c.disconnect()
                    val m = Model.parse(text)
                    if (m == null) msg = "bad"
                    else {
                        val f = File(ctx.filesDir, FILE)
                        val tmp = File(ctx.filesDir, "$FILE.tmp")
                        tmp.writeText(text); tmp.renameTo(f)
                        model = m
                        p.edit().putString("etag", tag).apply()
                        msg = "new"
                    }
                }
            } catch (e: Exception) { msg = "no net" }
            checking = false
            done?.let { d -> val m = msg; android.os.Handler(android.os.Looper.getMainLooper()).post { d(m) } }
        }.start()
    }

    /** Listens to 16 kHz 16-bit sound, 80 ms at a time; [feed] says when it heard its name. */
    class Detector(ctx: Context, private val m: Model) {
        private val opts = org.tensorflow.lite.InterpreterApi.Options()
            .setRuntime(org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY).setNumThreads(1)
        private val mel = org.tensorflow.lite.InterpreterApi.create(asset(ctx, "wake/melspectrogram.tflite"), opts)
        private val emb = org.tensorflow.lite.InterpreterApi.create(asset(ctx, "wake/embedding_model.tflite"), opts)
        private val melIn = ByteBuffer.allocateDirect(1760 * 4).order(ByteOrder.nativeOrder())
        private val melOut = ByteBuffer.allocateDirect(8 * 32 * 4).order(ByteOrder.nativeOrder())
        private val embIn = ByteBuffer.allocateDirect(76 * 32 * 4).order(ByteOrder.nativeOrder())
        private val embOut = ByteBuffer.allocateDirect(96 * 4).order(ByteOrder.nativeOrder())
        private val sound = FloatArray(1760)                      // the last 480 samples + the new 1280
        private var have = 0                                      // new samples so far (of 1280)
        private val mels = Array(76) { FloatArray(32) { 1f } }    // the last 76 mel frames (a ring)
        private var melAt = 0
        private val feats = FloatArray(16 * 96)                   // the last 16 embeddings (a ring)
        private var featAt = 0
        private var featN = 0
        private val x = FloatArray(16 * 96)
        @Volatile var last = 0f; private set

        init {
            mel.resizeInput(0, intArrayOf(1, 1760)); mel.allocateTensors()
            emb.allocateTensors()
        }

        val threshold get() = m.threshold

        /** Little-endian 16-bit samples. True: its name, just now. */
        fun feed(b: ByteArray, n: Int): Boolean {
            var heard = false
            var i = 0
            while (i + 1 < n) {
                sound[480 + have] = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toFloat()
                have++; i += 2
                if (have == 1280) {
                    have = 0
                    if (step()) heard = true
                    System.arraycopy(sound, 1280, sound, 0, 480)          // keep the last 480 for the next mel
                }
            }
            return heard
        }

        private fun step(): Boolean {
            melIn.rewind(); for (v in sound) melIn.putFloat(v); melIn.rewind()
            melOut.rewind(); mel.run(melIn, melOut); melOut.rewind()
            for (f in 0 until 8) { val row = mels[melAt]; for (k in 0 until 32) row[k] = melOut.float / 10f + 2f; melAt = (melAt + 1) % 76 }
            embIn.rewind()
            for (f in 0 until 76) { val row = mels[(melAt + f) % 76]; for (k in 0 until 32) embIn.putFloat(row[k]) }   // oldest first
            embIn.rewind(); embOut.rewind(); emb.run(embIn, embOut); embOut.rewind()
            val o = featAt * 96
            for (k in 0 until 96) feats[o + k] = embOut.float
            featAt = (featAt + 1) % 16
            if (featN < 16) { featN++; if (featN < 16) return false }
            for (f in 0 until 16) System.arraycopy(feats, ((featAt + f) % 16) * 96, x, f * 96, 96)
            last = m.score(x)
            return last >= m.threshold
        }

        fun close() { try { mel.close() } catch (e: Exception) { }; try { emb.close() } catch (e: Exception) { } }

        private fun asset(ctx: Context, name: String): ByteBuffer {
            val bytes = ctx.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}
