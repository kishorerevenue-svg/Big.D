package com.soulfriend.pet

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Link previews, made on the sender's phone (like WhatsApp): the page's title, a few words and a small picture. */
object LinkPreview {
    private val cache = LruCache<String, JSONObject>(40)
    private val URL_RE = Regex("""(?i)\b(https?://[^\s<>"']+|www\.[^\s<>"']+)""")
    fun firstUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ':', '"', '\'')
        ?.let { if (it.startsWith("www.", true)) "https://$it" else it }
    fun cached(url: String): JSONObject? = cache.get(url)

    /** The preview of [url], or null (nothing to show, or the page didn't answer within a few seconds). */
    fun get(url: String): JSONObject? {
        cache.get(url)?.let { return it }
        val u = try { URL(url) } catch (e: Exception) { return null }
        if (u.protocol != "http" && u.protocol != "https") return null
        val host = (u.host ?: "").lowercase()
        if (host.isEmpty() || host == "localhost" || host.endsWith(".local") || Regex("""^(127|10|0)\.|^192\.168\.|^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)) return null
        val (finalUrl, page) = fetchPage(url) ?: return null
        val metas = HashMap<String, String>()
        for (tag in Regex("""(?is)<meta\s[^>]*>""").findAll(page.take(300_000))) {
            val attrs = HashMap<String, String>()
            for (a in Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""").findAll(tag.value)) attrs[a.groupValues[1].lowercase()] = a.groupValues[2].ifEmpty { a.groupValues[3] }.ifEmpty { a.groupValues[4] }
            val key = (attrs["property"] ?: attrs["name"] ?: continue).lowercase()
            val v = attrs["content"] ?: continue
            if (!metas.containsKey(key)) metas[key] = v
        }
        fun meta(vararg k: String) = k.firstNotNullOfOrNull { metas[it]?.takeIf { s -> s.isNotBlank() } } ?: ""
        val title = decode(meta("og:title", "twitter:title").ifBlank { Regex("""(?is)<title[^>]*>(.{1,300}?)</title>""").find(page)?.groupValues?.get(1) ?: "" }).trim().take(140)
        val desc = decode(meta("og:description", "twitter:description", "description")).trim().take(220)
        if (title.isBlank() && desc.isBlank()) return null
        val site = decode(meta("og:site_name")).ifBlank { host.removePrefix("www.").removePrefix("m.") }.take(60)
        val out = JSONObject().put("u", url).put("t", title).put("d", desc).put("s", site)
        val img = meta("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
        if (img.isNotBlank()) try { thumb(URL(URL(finalUrl), decode(img)).toString())?.let { out.put("img", it.first).put("w", it.second).put("h", it.third) } } catch (e: Exception) { }
        cache.put(url, out)
        return out
    }

    private fun open(url: String, timeout: Int): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HabitAI/1.0 (link preview)")
        setRequestProperty("Accept-Language", "te-IN,te;q=0.9,en-IN;q=0.8,en;q=0.7")
    }
    /** (the page's address after redirects, its text): at most 400 KB, 6 seconds. */
    private fun fetchPage(url: String): Pair<String, String>? = try {
        val c = open(url, 6000)
        try {
            if (c.responseCode !in 200..299) null
            else if (c.contentType?.lowercase()?.let { !it.contains("html") && !it.contains("xml") } == true) null
            else {
                val bytes = c.inputStream.use { inp -> val out = ByteArrayOutputStream(); val buf = ByteArray(16384); var n = 0; while (n < 400_000) { val r = inp.read(buf); if (r < 0) break; out.write(buf, 0, r); n += r }; out.toByteArray() }
                val cs = Regex("""charset=([\w-]+)""", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1)
                    ?: Regex("""(?i)<meta[^>]+charset=["']?([\w-]+)""").find(String(bytes, 0, minOf(bytes.size, 4000), Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "UTF-8"
                Pair(c.url.toString(), try { String(bytes, charset(cs)) } catch (e: Exception) { String(bytes, Charsets.UTF_8) })
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { null }
    /** A small picture (at most 320 px, JPEG) in base64, with its width and height. */
    private fun thumb(url: String): Triple<String, Int, Int>? = try {
        val c = open(url, 6000)
        val bytes = try { if (c.responseCode !in 200..299) null else c.inputStream.use { inp -> val out = ByteArrayOutputStream(); val buf = ByteArray(16384); var n = 0; while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > 3_000_000) return null; out.write(buf, 0, r) }; out.toByteArray() } } finally { c.disconnect() }
        if (bytes == null) null else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth < 40 || o.outHeight < 40) null else {
                var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 320) s *= 2
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                if (b == null) null else {
                    val k = 320f / maxOf(b.width, b.height)
                    val sb = if (k < 1f) Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true) else b
                    val out = ByteArrayOutputStream(); sb.compress(Bitmap.CompressFormat.JPEG, 65, out)
                    Triple(ChatCrypto.b64(out.toByteArray()), sb.width, sb.height)
                }
            }
        }
    } catch (e: Exception) { null }
    private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("\\s+"), " ")
}

/** GIFs from KLIPY or GIPHY (Tenor-style search). The phone asks them itself, as their rules say; the key comes from the
 *  Habit server (it stays in the server's .env, not in the app). */
object Gifs {
    class Gif(val id: String, val preview: String, val url: String, val w: Int, val h: Int, val desc: String)
    @Volatile private var conf: JSONObject? = null
    @Volatile private var confAt = 0L
    /** Who gives the GIFs (for "Powered by …"), from what's known already (no internet here). */
    fun name(): String = conf?.optString("name")?.ifBlank { null } ?: "KLIPY"
    /** {key, base, name}; key "" when the team hasn't added a GIF key yet. null: no connection. */
    fun config(store: Store): JSONObject? {
        conf?.let { if (System.currentTimeMillis() - confAt < 3 * 3600_000L && it.optString("key").isNotEmpty()) return it }
        val r = ChatSync.api(store, "GET", "/api/chat/gifkey", timeoutMs = 10_000)
        if (r.ok) { conf = r.json; confAt = System.currentTimeMillis() }
        return if (r.ok) r.json else conf
    }
    /** (the GIFs, the "next page" mark), or null. An empty [q]: the popular ones. */
    fun search(store: Store, q: String, pos: String = ""): Pair<List<Gif>, String>? {
        val c = config(store) ?: return null
        val key = c.optString("key"); if (key.isEmpty()) return null
        val e = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val url = c.optString("base").trimEnd('/') + (if (q.isBlank()) "/featured" else "/search") + "?key=" + e(key) + "&client_key=habit_ai&limit=24&contentfilter=high" +
            "&media_filter=" + e("nanogif,tinygif,gif") + "&locale=" + (if (store.lang == "te") "te_IN" else "en_IN") + (if (q.isNotBlank()) "&q=" + e(q.trim()) else "") + (if (pos.isNotEmpty()) "&pos=" + e(pos) else "")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            val text = try { if (conn.responseCode !in 200..299) return null; conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
            val j = JSONObject(text)
            val list = ArrayList<Gif>()
            val a = j.optJSONArray("results") ?: JSONArray()
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                val mf = r.optJSONObject("media_formats") ?: continue
                fun f(n: String) = mf.optJSONObject(n)?.takeIf { it.optString("url").startsWith("https://") }
                val big = f("gif")?.takeIf { it.optLong("size") in 1..2_500_000L } ?: f("tinygif") ?: f("gif") ?: continue
                val small = f("nanogif") ?: f("tinygif") ?: big
                val dims = big.optJSONArray("dims")
                list.add(Gif(r.optString("id"), small.optString("url"), big.optString("url"), dims?.optInt(0) ?: 220, dims?.optInt(1) ?: 160, r.optString("content_description")))
            }
            Pair(list, j.optString("next"))
        } catch (e: Exception) { null }
    }
    fun download(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        try { if (c.responseCode !in 200..299) null else c.inputStream.use { inp -> val out = ByteArrayOutputStream(); val buf = ByteArray(32768); var n = 0; while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > 5_000_000) return null; out.write(buf, 0, r) }; out.toByteArray() } } finally { c.disconnect() }
    } catch (e: Exception) { null }
}

/** The emoji panel's emojis (by kind), and a few words to find them (English and Telugu). */
object Emojis {
    class Cat(val icon: String, val en: String, val te: String, val list: List<String>)
    private fun l(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val CATS = listOf(
        Cat("😀", "Smileys and people", "నవ్వులు, మనుషులు", l("""
😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺️ 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴
😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬
😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊 💋 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💯 💢 💥 💫 💦 💨 💬 💭 💤
👋 🤚 🖐️ ✋ 🖖 👌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 👂 👃 🧠 👀 👅 👄
👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🙇 🤦 🤷 👮 🕵️ 💂 👷 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🤱 👼 🎅 🦸 🧙 🧚 🧞 💆 💇 🚶 🏃 💃 🕺 👯 🧘 🛌 👭 👫 👬 💑 👪 🗣️ 👤 👥 👣""")),
        Cat("🐶", "Animals and nature", "జంతువులు, ప్రకృతి", l("""
🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🦗 🕷️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊
🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🕊️ 🐇 🐁 🐿️ 🦔 🐾 🐉 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🎋 🍃 🍂 🍁 🍄 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻
🌞 🌝 🌛 🌜 🌚 🌕 🌙 🌎 🌍 🌏 ⭐ 🌟 ✨ ⚡ ☄️ 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ ⛄ 🌬️ 💧 ☔ 🌊 🌫️""")),
        Cat("🍔", "Food and drink", "తిండి, పానీయాలు", l("""
🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🍞 🥖 🧀 🥚 🍳 🧈 🥞 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🥘
🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🧃 🥤 🧉 🧊 🥄 🍴 🍽️ 🥣 🥡 🥢 🧂""")),
        Cat("⚽", "Activities and festivals", "ఆటలు, పండుగలు", l("""
⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🏏 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 ⛸️ 🎿 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🏄 🏊 🚣 🧗 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🎗️ 🎫 🎟️
🎪 🤹 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🧩 🪁 🎉 🎊 🎈 🎁 🎀 🪔 🧨 🎆 🎇 🎐 🎑 🧧""")),
        Cat("🚗", "Travel and places", "ప్రయాణం, ప్రదేశాలు", l("""
🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🚃 🚋 🚞 🚄 🚅 🚈 🚂 🚆 🚇 🚉 ✈️ 🛫 🛬 💺 🚀 🚁 🛶 ⛵ 🚤 🛳️ 🚢 ⚓ ⛽ 🚧 🚦 🚏
🗺️ 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🏕️ ⛺ 🏠 🏡 🏘️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 💒 🏛️ ⛪ 🕌 🛕 🕋 🛤️ 🛣️ 🏞️ 🌅 🌄 🌠 🌇 🌆 🏙️ 🌃 🌌 🌉""")),
        Cat("💡", "Objects", "వస్తువులు", l("""
⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 🎞️ 📞 ☎️ 📺 📻 🎙️ ⏱️ ⏰ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💰 💳 💎 ⚖️ 🔧 🔨 🛠️ 🔩 ⚙️ 🧱 🧲 🛡️ 🔮 📿 🧿 🔭 🔬
💊 💉 🌡️ 🧹 🧺 🧼 🧴 🛎️ 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 ✉️ 📩 📧 📥 📤 📦 🏷️ 📮 📜 📃 📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📅 📆 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 📰
📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓""")),
        Cat("❤️", "Symbols", "గుర్తులు", l("""
❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 🕉️ ☮️ ✝️ ☪️ ☸️ ✡️ ☯️ 🛐 ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🆚 💮 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢
♨️ 🚷 🚯 🚳 🚱 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 💹 ❇️ ✳️ ❎ 🌐 💠 🌀 🏧 🚾 ♿ 🅿️ 🚹 🚺 🚼 🚻 🚮 📶 ℹ️ 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 #️⃣ *️⃣
▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ ↪️ ↩️ 🔀 🔁 🔄 🎵 🎶 ➕ ➖ ➗ ✖️ ♾️ 💲 ™️ ©️ ®️ 〰️ ➰ 🔚 🔙 🔛 🔝 🔜 ✔️ ☑️ 🔘
🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 🗯️ ♠️ ♣️ ♥️ ♦️ 🃏 🀄 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛""")),
        Cat("🏳️", "Flags", "జెండాలు", l("""
🇮🇳 🏁 🚩 🏳️ 🏴 🇺🇸 🇬🇧 🇦🇪 🇸🇦 🇶🇦 🇰🇼 🇴🇲 🇧🇭 🇸🇬 🇲🇾 🇦🇺 🇨🇦 🇳🇿 🇩🇪 🇫🇷 🇮🇹 🇪🇸 🇳🇱 🇨🇭 🇸🇪 🇳🇴 🇮🇪 🇯🇵 🇨🇳 🇰🇷 🇳🇵 🇧🇩 🇱🇰 🇧🇹 🇲🇻 🇵🇰 🇹🇭 🇮🇩 🇵🇭 🇻🇳 🇿🇦 🇰🇪 🇳🇬 🇪🇬 🇧🇷 🇷🇺 🇺🇦"""))
    )
    /** A few words for each emoji (English and Telugu), for the 🔍 search. */
    private val WORDS = listOf(
        "love ప్రేమ లవ్ heart గుండె" to "❤️ 😍 🥰 😘 💕 💖 💘 💗 💞 🧡 💛 💚 💙 💜",
        "laugh haha lol నవ్వు హహ funny" to "😂 🤣 😆 😄 😁 😹",
        "smile happy సంతోషం నవ్వు" to "😊 🙂 😀 😃 😄 🥳 😇",
        "sad cry బాధ ఏడుపు" to "😢 😭 😞 😔 ☹️ 💔 🥺",
        "angry కోపం mad" to "😡 😠 🤬 😤",
        "ok okay సరే fine" to "👌 👍 🆗 ✅",
        "yes అవును done పూర్తి check" to "👍 ✅ ✔️ ☑️",
        "no కాదు వద్దు" to "👎 ❌ 🚫 🙅",
        "thanks thank ధన్యవాదాలు థాంక్స్" to "🙏 😊 💐",
        "namaste నమస్తే pray దేవుడు god" to "🙏 🛕 🕉️ 🪔 📿",
        "party celebrate పండుగ congrats అభినందనలు" to "🎉 🥳 🎊 🎈 🏆 👏",
        "birthday పుట్టినరోజు cake" to "🎂 🎉 🎁 🥳 🎈 🧁",
        "food భోజనం అన్నం eat తిను" to "🍛 🍚 🍲 🥘 🍽️ 🍜 🥗",
        "tea chai టీ coffee కాఫీ" to "☕ 🍵",
        "sleep night నిద్ర రాత్రి" to "😴 💤 🌙 🛌 🌃",
        "morning ఉదయం శుభోదయం sun" to "🌞 ☀️ 🌅 ☕ 🌻",
        "flower పువ్వు" to "🌸 🌹 🌺 🌻 🌼 💐 🌷",
        "fire hot వేడి" to "🔥 🥵",
        "cool" to "😎 🆒",
        "wow surprise ఆశ్చర్యం" to "😮 😲 🤩 😳",
        "think ఆలోచన" to "🤔 🧐",
        "money డబ్బు rupee" to "💰 💵 💸 💳",
        "phone call కాల్ ఫోన్" to "📞 📱 ☎️",
        "travel car bus auto ప్రయాణం" to "🚗 🚌 🛺 ✈️ 🚆 🏍️",
        "home ఇల్లు house" to "🏠 🏡",
        "work office ఆఫీస్ పని" to "💼 🏢 💻 📋 📝 📁",
        "school study చదువు book" to "🏫 📚 ✏️ 📖",
        "temple గుడి" to "🛕 🙏 🪔 🕉️",
        "diwali దీపావళి" to "🪔 🎆 🎇 🧨",
        "sankranti సంక్రాంతి kite గాలిపటం" to "🪁 🌾 🐄",
        "rain వర్షం" to "🌧️ ☔ ⛈️ 🌦️",
        "cricket క్రికెట్" to "🏏",
        "star నక్షత్రం" to "⭐ 🌟 ✨",
        "hug కౌగిలి" to "🤗",
        "kiss ముద్దు" to "😘 💋",
        "baby పాప బాబు" to "👶 🍼",
        "dog కుక్క" to "🐶 🐕",
        "cat పిల్లి" to "🐱 🐈",
        "cow ఆవు" to "🐄 🐮",
        "wait time సమయం" to "⏳ ⏰ ⌛",
        "sorry క్షమించండి సారీ" to "🙏 😔 🥺",
        "clap చప్పట్లు" to "👏 🙌",
        "strong power బలం" to "💪",
        "look eyes చూడు" to "👀",
        "music song పాట" to "🎵 🎶 🎤 🎧",
        "movie సినిమా" to "🎬 🍿 🎥",
        "sick health ఆరోగ్యం జ్వరం" to "🤒 🤧 😷 💊",
        "india భారత్ ఇండియా" to "🇮🇳",
    )
    fun search(q: String): List<String> {
        val s = q.trim().lowercase(); if (s.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for ((words, list) in WORDS) if (words.split(" ").any { it.startsWith(s) || s.startsWith(it) && it.length >= 3 }) out.addAll(l(list))
        return out.toList()
    }
    /** The emojis used lately (newest first). */
    fun recent(ctx: Context): List<String> = try { JSONArray(ChatDb.get(ctx).kv("recentEmoji").ifEmpty { "[]" }).let { a -> (0 until a.length()).map { a.optString(it) } } } catch (e: Exception) { emptyList() }
    fun used(ctx: Context, e: String) {
        val l = (listOf(e) + recent(ctx).filter { it != e }).take(32)
        ChatDb.get(ctx).setKv("recentEmoji", JSONArray(l).toString())
    }
    /** The quick reactions (long-press a message). */
    val QUICK = listOf("❤️", "😂", "😮", "😢", "🙏", "👍")
}

/**
 * Habit's own sticker packs, drawn on the phone (nothing to download, and nothing copied from anyone):
 * your Habit pet in 18 moods, its friends, Telugu and English words, office replies and festival wishes.
 * A sticker from a pack travels as just its name ("pet/puppy.HAPPY"); the other phone draws the same one.
 * A sticker made from a photo ("Create sticker") travels as a small locked picture, like a photo.
 */
object Stickers {
    class Item(val pack: String, val n: String, val alt: String)
    class Pack(val id: String, val icon: String, val en: String, val te: String, val items: List<Item>)
    private class Word(val text: String, val emoji: String, val c1: String, val c2: String)
    private val cache = LruCache<String, Bitmap>(80)

    private val MOOD_WORDS = mapOf(
        "HAPPY" to ("Hi!" to "హాయ్!"), "EXCITED" to ("Yay!" to "యే!"), "THINKING" to ("Hmm…" to "అమ్మో…"), "SAD" to ("Sad" to "బాధగా ఉంది"),
        "SURPRISED" to ("Wow!" to "అవునా?!"), "LOVE" to ("Love you" to "లవ్ యూ"), "SLEEPY" to ("Good night" to "శుభరాత్రి"), "CONFUSED" to ("Huh?" to "ఏంటి?"),
        "LAUGHING" to ("Ha ha!" to "హహహ"), "ANGRY" to ("Grr!" to "కోపం!"), "CRYING" to ("Miss you" to "మిస్ యూ"), "SHY" to ("Thank you" to "థాంక్యూ"),
        "COOL" to ("Cool!" to "కూల్!"), "WINK" to ("Okay 😉" to "సరే 😉"), "PROUD" to ("Super!" to "సూపర్!"), "SCARED" to ("Oh no!" to "అయ్యో!"),
        "YAWNING" to ("Sleepy" to "నిద్ర వస్తోంది"), "DIZZY" to ("So tired" to "అలసిపోయా"))
    private val WORDS_TE = listOf(
        Word("సూపర్!", "⭐", "#FFB300", "#FF6F00"), Word("థాంక్యూ", "🙏", "#00A99D", "#00695C"), Word("శుభోదయం", "🌞", "#FFC107", "#FF7043"),
        Word("శుభరాత్రి", "🌙", "#3949AB", "#1A237E"), Word("హహహ", "😂", "#FDD835", "#F9A825"), Word("సరే", "👍", "#43A047", "#1B5E20"),
        Word("అవునా?!", "😲", "#EC407A", "#AD1457"), Word("వస్తున్నా", "🏃", "#1E88E5", "#0D47A1"), Word("తిన్నారా?", "🍛", "#FB8C00", "#E65100"),
        Word("బాగున్నారా?", "😊", "#26A69A", "#00796B"), Word("అభినందనలు", "🎉", "#8E24AA", "#4A148C"), Word("మిస్ యూ", "💕", "#F06292", "#C2185B"),
        Word("లవ్ యూ", "❤️", "#E53935", "#B71C1C"), Word("ఆల్ ది బెస్ట్", "👍", "#7CB342", "#33691E"), Word("చాలా బాగుంది", "😍", "#FF7043", "#D84315"),
        Word("ఏంటి ఇది?!", "🤔", "#78909C", "#37474F"), Word("పర్లేదు", "😌", "#4DB6AC", "#00897B"), Word("నమస్తే", "🙏", "#FF9933", "#E65100"),
        Word("జై హో!", "🇮🇳", "#FF9933", "#138808"), Word("జాగ్రత్త", "⚠️", "#F4511E", "#BF360C"))
    private val WORDS_EN = listOf(
        Word("Super!", "⭐", "#FFB300", "#FF6F00"), Word("Thank you", "🙏", "#00A99D", "#00695C"), Word("Good morning", "🌞", "#FFC107", "#FF7043"),
        Word("Good night", "🌙", "#3949AB", "#1A237E"), Word("LOL", "😂", "#FDD835", "#F9A825"), Word("OK", "👍", "#43A047", "#1B5E20"),
        Word("Wow!", "😲", "#EC407A", "#AD1457"), Word("On my way", "🏃", "#1E88E5", "#0D47A1"), Word("Congrats!", "🎉", "#8E24AA", "#4A148C"),
        Word("Miss you", "💕", "#F06292", "#C2185B"), Word("Love you", "❤️", "#E53935", "#B71C1C"), Word("All the best", "👍", "#7CB342", "#33691E"),
        Word("Happy birthday", "🎂", "#D81B60", "#880E4F"), Word("Sorry", "🥺", "#90A4AE", "#455A64"), Word("Yes!", "✅", "#2E7D32", "#1B5E20"), Word("No!", "❌", "#E53935", "#B71C1C"))
    private val OFFICE = listOf(
        Word("Noted", "✅", "#00897B", "#004D40"), Word("Done", "✔️", "#43A047", "#1B5E20"), Word("Will do", "👍", "#1E88E5", "#0D47A1"),
        Word("In a meeting", "📵", "#5E35B1", "#311B92"), Word("Urgent!", "⚡", "#E53935", "#B71C1C"), Word("On leave", "🌴", "#26A69A", "#00695C"),
        Word("Report sent", "📄", "#3949AB", "#1A237E"), Word("Call me", "📞", "#FB8C00", "#E65100"), Word("Received", "📥", "#00ACC1", "#006064"),
        Word("Approved", "✅", "#2E7D32", "#1B5E20"), Word("Pending", "⏳", "#FFA000", "#E65100"), Word("Field visit", "🚗", "#6D4C41", "#3E2723"),
        Word("Thank you sir", "🙏", "#00A99D", "#00695C"), Word("Thank you madam", "🙏", "#AB47BC", "#6A1B9A"), Word("Reached", "📍", "#F4511E", "#BF360C"),
        Word("అందింది", "📥", "#00ACC1", "#006064"), Word("చేస్తాను", "👍", "#1E88E5", "#0D47A1"), Word("మీటింగ్‌లో ఉన్నాను", "📵", "#5E35B1", "#311B92"),
        Word("అత్యవసరం!", "⚡", "#E53935", "#B71C1C"), Word("సెలవులో ఉన్నాను", "🌴", "#26A69A", "#00695C"), Word("ధన్యవాదాలు సార్", "🙏", "#00A99D", "#00695C"),
        Word("పని పూర్తయింది", "✅", "#43A047", "#1B5E20"), Word("ఫీల్డ్‌లో ఉన్నాను", "🚗", "#6D4C41", "#3E2723"), Word("వస్తున్నాను", "🏃", "#FB8C00", "#E65100"))
    private val FEST = listOf(
        Word("సంక్రాంతి శుభాకాంక్షలు", "🪁", "#FF9800", "#E65100"), Word("Happy Sankranti", "🪁", "#FF9800", "#E65100"),
        Word("ఉగాది శుభాకాంక్షలు", "🌿", "#7CB342", "#33691E"), Word("Happy Ugadi", "🥭", "#7CB342", "#33691E"),
        Word("దీపావళి శుభాకాంక్షలు", "🪔", "#FFB300", "#BF360C"), Word("Happy Diwali", "🪔", "#FFB300", "#BF360C"),
        Word("దసరా శుభాకాంక్షలు", "🏹", "#E53935", "#880E4F"), Word("Happy Dasara", "🏹", "#E53935", "#880E4F"),
        Word("వినాయక చవితి శుభాకాంక్షలు", "🐘", "#FB8C00", "#BF360C"), Word("బతుకమ్మ శుభాకాంక్షలు", "🌼", "#EC407A", "#6A1B9A"),
        Word("హోలీ శుభాకాంక్షలు", "🎨", "#AB47BC", "#00ACC1"), Word("Happy Holi", "🎨", "#AB47BC", "#00ACC1"),
        Word("రంజాన్ శుభాకాంక్షలు", "🌙", "#00897B", "#004D40"), Word("Eid Mubarak", "🌙", "#00897B", "#004D40"),
        Word("క్రిస్మస్ శుభాకాంక్షలు", "🎄", "#43A047", "#B71C1C"), Word("Merry Christmas", "🎄", "#43A047", "#B71C1C"),
        Word("నూతన సంవత్సర శుభాకాంక్షలు", "🎆", "#3949AB", "#8E24AA"), Word("Happy New Year", "🎆", "#3949AB", "#8E24AA"),
        Word("స్వాతంత్ర్య దినోత్సవ శుభాకాంక్షలు", "🇮🇳", "#FF9933", "#138808"), Word("Happy Independence Day", "🇮🇳", "#FF9933", "#138808"))
    private val FRIENDS = listOf("kitten", "panda", "bunny", "bear", "fox", "robot", "parrot", "mochi", "chameleon")

    fun packs(ctx: Context): List<Pack> {
        val te = Store(ctx).lang == "te"
        val pet = Store(ctx).petKind.ifBlank { "puppy" }
        val moods = PuppyView.Mood.values().map { it.name }
        return listOf(
            Pack("pet", "🐾", "Your Habit pet", "మీ Habit పెట్", moods.map { Item("pet", "$pet.$it", MOOD_WORDS[it]?.let { w -> if (te) w.second else w.first } ?: it) }),
            Pack("friends", "🐼", "Pet friends", "పెట్ స్నేహితులు", FRIENDS.flatMap { k -> listOf("HAPPY", "LOVE", "LAUGHING").map { md -> Item("pet", "$k.$md", MOOD_WORDS[md]?.let { w -> if (te) w.second else w.first } ?: md) } }),
            Pack("words", "💬", "Words", "మాటలు", (if (te) WORDS_TE + WORDS_EN else WORDS_EN + WORDS_TE).map { Item("words", it.text, it.text) }),
            Pack("office", "💼", "Office", "ఆఫీస్", OFFICE.map { Item("office", it.text, it.text) }),
            Pack("fest", "🎉", "Festivals", "పండుగలు", FEST.map { Item("fest", it.text, it.text) }))
    }
    /** A pack sticker's picture ([size] px square, transparent around it). null for one this version doesn't know. */
    fun draw(ctx: Context, pack: String, n: String, size: Int = 320): Bitmap? {
        val key = "$pack|$n|$size"
        cache.get(key)?.let { return it }
        val b = try {
            when (pack) {
                "pet" -> pet(ctx, n, size)
                "words" -> (WORDS_TE + WORDS_EN).firstOrNull { it.text == n }?.let { word(it, size, false) }
                "office" -> OFFICE.firstOrNull { it.text == n }?.let { word(it, size, true) }
                "fest" -> FEST.firstOrNull { it.text == n }?.let { word(it, size, false) }
                else -> null
            }
        } catch (e: Exception) { null } ?: return null
        cache.put(key, b)
        return b
    }

    private fun outlined(c: Canvas, layout: StaticLayout, x: Float, y: Float, tp: TextPaint, stroke: Float, strokeColor: Int, fill: Int) {
        c.save(); c.translate(x, y)
        tp.style = Paint.Style.STROKE; tp.strokeWidth = stroke; tp.strokeJoin = Paint.Join.ROUND; tp.color = strokeColor; layout.draw(c)
        tp.style = Paint.Style.FILL; tp.color = fill; layout.draw(c)
        c.restore()
    }
    @Suppress("DEPRECATION")
    private fun layout(text: String, tp: TextPaint, width: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= 23) StaticLayout.Builder.obtain(text, 0, text.length, tp, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 0.95f).setIncludePad(false).build()
        else StaticLayout(text, tp, width, Layout.Alignment.ALIGN_NORMAL, 0.95f, 0f, false)
    /** Text that fits [w] x [h]: as big as it can be. */
    private fun fit(text: String, tp: TextPaint, w: Int, h: Int, start: Float): StaticLayout {
        var s = start
        var l: StaticLayout
        do {
            tp.textSize = s
            l = layout(text, tp, w)
            val widest = (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } ?: 0f
            val ok = (l.height <= h && widest <= w && l.lineCount <= 3) || s < 18f
            s *= 0.9f
        } while (!ok)
        return l
    }

    /** A word sticker: a bright tilted badge with a white edge, the emoji on top and the words in big outlined letters. */
    private fun word(wd: Word, size: Int, square: Boolean): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = size / 320f
        val tilt = ((wd.text.hashCode() % 9) - 4).toFloat()
        c.rotate(tilt, size / 2f, size / 2f)
        val body = RectF(26 * s, 86 * s, size - 26 * s, size - 50 * s)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shape = Path()
        if (square) shape.addRoundRect(body, 34 * s, 34 * s, Path.Direction.CW)
        else {
            // a soft speech bubble with a little tail
            shape.addRoundRect(body, 60 * s, 60 * s, Path.Direction.CW)
            shape.moveTo(body.left + 54 * s, body.bottom - 6 * s); shape.lineTo(body.left + 30 * s, body.bottom + 34 * s); shape.lineTo(body.left + 96 * s, body.bottom - 6 * s); shape.close()
        }
        p.color = Color.parseColor("#55000000"); c.save(); c.translate(0f, 7 * s); c.drawPath(shape, p); c.restore()
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 18 * s; p.strokeJoin = Paint.Join.ROUND; c.drawPath(shape, p)
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, body.top, 0f, body.bottom, Color.parseColor(wd.c1), Color.parseColor(wd.c2), Shader.TileMode.CLAMP); c.drawPath(shape, p); p.shader = null
        // a shine
        p.color = Color.parseColor("#33FFFFFF"); c.drawRoundRect(RectF(body.left + 18 * s, body.top + 12 * s, body.right - 18 * s, body.top + 40 * s), 20 * s, 20 * s, p)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val l = fit(wd.text, tp, (body.width() - 40 * s).toInt(), (body.height() - 40 * s).toInt(), 70 * s)
        val widest = (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } ?: 0f
        outlined(c, l, body.centerX() - widest / 2f, body.centerY() - l.height / 2f + 6 * s, tp, 9 * s, Color.parseColor("#88000000"), Color.WHITE)
        // the emoji, half outside the badge
        val ep = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 92 * s; textAlign = Paint.Align.CENTER }
        c.drawText(wd.emoji, body.right - 58 * s, body.top + 34 * s, ep)
        return b
    }

    /** A pet sticker: the Habit pet (PuppyView) in a mood, with a word under it. */
    private fun pet(ctx: Context, n: String, size: Int): Bitmap? {
        val kindKey = n.substringBefore('.')
        val mood = try { PuppyView.Mood.valueOf(n.substringAfter('.')) } catch (e: Exception) { return null }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val v = PuppyView(ctx).apply { kind = PuppyView.kindOf(kindKey); this.mood = mood }
        val box = (size * 0.86f).toInt()
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(box, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(box, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, box, box)
        c.save(); c.translate((size - box) / 2f, 0f); v.draw(c); c.restore()
        val te = Store(ctx).lang == "te"
        val w = MOOD_WORDS[mood.name]?.let { if (te) it.second else it.first } ?: return b
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val l = fit(w, tp, (size * 0.9f).toInt(), (size * 0.22f).toInt(), size * 0.15f)
        val widest = (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } ?: 0f
        outlined(c, l, size / 2f - widest / 2f, size - l.height - size * 0.02f, tp, size * 0.035f, Color.WHITE, Color.parseColor("#1E2550"))
        return b
    }

    // ---------- my stickers: made from my photos, or saved from chats ----------
    fun dir(ctx: Context) = File(ctx.filesDir, "stickers").also { it.mkdirs() }
    fun mine(ctx: Context): List<File> = (dir(ctx).listFiles()?.filter { it.name.endsWith(".png") } ?: emptyList()).sortedByDescending { it.lastModified() }
    /** Makes a sticker from a photo: cut out as a circle or a rounded square, a white edge, and words if given. PNG, 512 px. */
    fun fromPhoto(src: Bitmap, round: Boolean, words: String): ByteArray {
        val size = 512
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val side = minOf(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val inner = RectF(28f, 28f, size - 28f, size - 28f)
        val clip = Path().apply { if (round) addOval(inner, Path.Direction.CW) else addRoundRect(inner, 64f, 64f, Path.Direction.CW) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#44000000"); c.save(); c.translate(0f, 8f); c.drawPath(clip, p); c.restore()
        c.save(); c.clipPath(clip); c.drawBitmap(crop, null, inner, Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 22f; c.drawPath(clip, p)
        if (words.isNotBlank()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val l = fit(words.trim().take(40), tp, 460, 150, 80f)
            val widest = (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } ?: 0f
            outlined(c, l, size / 2f - widest / 2f, size - l.height - 34f, tp, 14f, Color.BLACK, Color.WHITE)
        }
        val bytes = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return bytes.toByteArray()
    }
    fun saveMine(ctx: Context, bytes: ByteArray): File = File(dir(ctx), "s" + System.currentTimeMillis() + ".png").also { it.writeBytes(bytes) }

    // ---------- ⭐ favourites and 🕘 recent (as "pack:n" or "file:/path") ----------
    private fun refs(ctx: Context, k: String): List<String> = try { JSONArray(ChatDb.get(ctx).kv(k).ifEmpty { "[]" }).let { a -> (0 until a.length()).map { a.optString(it) } } } catch (e: Exception) { emptyList() }
    fun recent(ctx: Context) = refs(ctx, "recentStickers").filter { valid(ctx, it) }
    fun favs(ctx: Context) = refs(ctx, "favStickers").filter { valid(ctx, it) }
    fun used(ctx: Context, ref: String) { ChatDb.get(ctx).setKv("recentStickers", JSONArray((listOf(ref) + refs(ctx, "recentStickers").filter { it != ref }).take(24)).toString()) }
    fun isFav(ctx: Context, ref: String) = ref in refs(ctx, "favStickers")
    fun fav(ctx: Context, ref: String, on: Boolean) {
        val l = refs(ctx, "favStickers").filter { it != ref }
        ChatDb.get(ctx).setKv("favStickers", JSONArray(if (on) (listOf(ref) + l).take(60) else l).toString())
    }
    private fun valid(ctx: Context, ref: String) = if (ref.startsWith("file:")) File(ref.removePrefix("file:")).exists() else ref.contains(':')
    fun picture(ctx: Context, ref: String, size: Int): Bitmap? =
        if (ref.startsWith("file:")) cache.get(ref + size) ?: try { BitmapFactory.decodeFile(ref.removePrefix("file:"))?.let { Bitmap.createScaledBitmap(it, size, size, true) }?.also { cache.put(ref + size, it) } } catch (e: Exception) { null }
        else draw(ctx, ref.substringBefore(':'), ref.substringAfter(':'), size)
    fun altOf(ctx: Context, ref: String): String = if (ref.startsWith("file:")) "" else packs(ctx).flatMap { it.items }.firstOrNull { it.pack + ":" + it.n == ref }?.alt ?: ref.substringAfter(':').substringAfter('.')
}

/** Lists of chats ("Add to list"), shown as chips on the chat list, like WhatsApp: e.g. Family, Office. On this phone. */
object ChatLists {
    class L(val id: String, val name: String, val cids: MutableSet<String>)
    fun all(ctx: Context): List<L> = try {
        val a = JSONArray(ChatDb.get(ctx).kv("lists").ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o -> L(o.optString("id"), o.optString("name"), o.optJSONArray("cids")?.let { c -> (0 until c.length()).map { c.optString(it) }.toMutableSet() } ?: mutableSetOf()) }
    } catch (e: Exception) { emptyList() }
    fun save(ctx: Context, lists: List<L>) {
        ChatDb.get(ctx).setKv("lists", JSONArray(lists.map { JSONObject().put("id", it.id).put("name", it.name).put("cids", JSONArray(it.cids.toList())) }).toString())
        ChatSync.changed()
    }
    fun create(ctx: Context, name: String, cids: Set<String> = emptySet()): L {
        val l = L("l" + System.currentTimeMillis().toString(36), name.trim().take(24), cids.toMutableSet())
        save(ctx, all(ctx) + l); return l
    }
    /** "Add to list": tick the lists this chat should be in (or make a new one). */
    fun pick(a: Activity, cid: String, done: () -> Unit = {}) {
        val te = Store(a).lang == "te"
        val lists = all(a)
        val names = lists.map { it.name } + listOf(if (te) "➕ కొత్త లిస్ట్" else "➕ New list")
        val on = BooleanArray(names.size) { i -> i < lists.size && cid in lists[i].cids }
        AlertDialog.Builder(a).setTitle(if (te) "📋 లిస్ట్‌లో చేర్చు" else "📋 Add to list")
            .setMultiChoiceItems(names.toTypedArray(), on) { d, i, c ->
                if (i == lists.size) { (d as? AlertDialog)?.dismiss(); newList(a, setOf(cid)) { done() } } else on[i] = c
            }
            .setPositiveButton(if (te) "సరే" else "Done") { _, _ -> lists.forEachIndexed { i, l -> if (on[i]) l.cids.add(cid) else l.cids.remove(cid) }; save(a, lists); done() }
            .setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
    }
    fun newList(a: Activity, cids: Set<String>, done: () -> Unit = {}) {
        val te = Store(a).lang == "te"
        val ui = Ui(a)
        val inp = ui.input(if (te) "లిస్ట్ పేరు, ఉదా: కుటుంబం, ఆఫీస్" else "List name, e.g. Family, Office")
        val wrap = android.widget.FrameLayout(a).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0); addView(inp) }
        AlertDialog.Builder(a).setTitle(if (te) "📋 కొత్త లిస్ట్" else "📋 New list").setView(wrap)
            .setPositiveButton(if (te) "చేయి" else "Create") { _, _ -> val n = inp.text.toString().trim(); if (n.isNotEmpty()) { create(a, n, cids); done() } }
            .setNegativeButton(if (te) "వద్దు" else "Cancel", null).show()
    }
}

/** "Add shortcut": the chat opens straight from the phone's home screen. */
object ChatShortcut {
    fun add(a: Activity, conv: ChatConv) {
        val te = Store(a).lang == "te"
        val sm = a.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) { android.widget.Toast.makeText(a, if (te) "ఈ ఫోన్ షార్ట్‌కట్‌లు చేర్చనివ్వదు." else "This phone's home screen doesn't take shortcuts.", android.widget.Toast.LENGTH_LONG).show(); return }
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChatTheme.Brand.NAVY }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = ChatTheme.Brand.GOLD; p.style = Paint.Style.STROKE; p.strokeWidth = 8f; c.drawCircle(size / 2f, size / 2f, size / 2f - 6, p)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 96f; textAlign = Paint.Align.CENTER }
        c.drawText(conv.emoji, size / 2f, size / 2f + 34f, tp)
        val intent = Intent(a, ChatActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("cid", conv.cid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = ShortcutInfo.Builder(a, "chat-" + conv.cid).setShortLabel(conv.name.take(24).ifBlank { "Habit Chat" }).setIcon(Icon.createWithBitmap(bmp)).setIntent(intent).build()
        try { sm.requestPinShortcut(info, null) } catch (e: Exception) { }
    }
}

/** "Export chat": the messages as a text file (and the photos and files, if wanted), to share or save. */
object ChatExport {
    fun export(a: Activity, conv: ChatConv, media: Boolean) {
        val te = Store(a).lang == "te"
        val db = ChatDb.get(a)
        val me = ChatSync.myUid(a)
        val fmt = DateTimeFormatter.ofPattern("d/M/yy, h:mm a", Locale.ENGLISH)
        val msgs = db.msgs(conv.cid, 100_000).filter { it.kind !in ChatDb.HIDDEN }
        val sb = StringBuilder()
        sb.append(if (te) "Habit చాట్: ${conv.name}\n🔒 ఈ ఫైల్‌లో ఉన్న మెసేజ్‌లు ఇప్పుడు లాక్ కావు. జాగ్రత్తగా పంచుకోండి.\n\n" else "Habit Chat: ${conv.name}\n🔒 The messages in this file are not locked any more. Share it carefully.\n\n")
        val files = ArrayList<File>()
        for (m in msgs) {
            val who = when { m.kind == "sys" || m.kind == "timer" -> ""; m.sender == me || conv.isSelf -> if (te) "నేను" else "Me"; else -> conv.nameOf(m.sender) }
            val local = m.body.optString("local")
            var line = ChatSync.preview(a, m)
            if (ChatSync.hasFile(m)) {
                val f = File(local)
                if (media && local.isNotEmpty() && f.exists() && files.size < 60) { files.add(f); line += "  <" + f.name + ">" }
                else if (!media) line += if (te) "  (ఫైల్ వదిలేశాం)" else "  (file left out)"
            }
            sb.append(Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).format(fmt)).append(" - ").append(if (who.isEmpty()) "" else "$who: ").append(line).append('\n')
        }
        val safe = conv.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        val name = "Habit Chat with $safe.txt"
        try { File(ShareFiles.dir(a), name).writeText(sb.toString()) } catch (e: Exception) { return }
        val uris = arrayListOf(ShareFiles.uri(a, name))
        for (f in files) try { f.copyTo(File(ShareFiles.dir(a), f.name), overwrite = true); uris.add(ShareFiles.uri(a, f.name)) } catch (e: Exception) { }
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        i.putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        (a as? MainActivity)?.leavingForSystemScreen = true
        try { a.startActivity(Intent.createChooser(i, if (te) "చాట్ ఎగుమతి" else "Export chat")) } catch (e: Exception) { }
    }
}

/** The "safety number" to check a one-to-one chat is really locked for the right person (both phones show the same). */
object SafetyCode {
    fun of(pubA: String, pubB: String): String {
        val (x, y) = listOf(pubA, pubB).sorted()
        var h = MessageDigest.getInstance("SHA-256").digest("habit-safety-v1|$x|$y".toByteArray())
        val digits = StringBuilder()
        while (digits.length < 60) {
            for (i in 0 until h.size - 1 step 2) { if (digits.length >= 60) break; digits.append("%05d".format(((h[i].toInt() and 0xff) * 256 + (h[i + 1].toInt() and 0xff)) % 100000)) }
            h = MessageDigest.getInstance("SHA-256").digest(h)
        }
        return digits.toString().take(60).chunked(5).chunked(4).joinToString("\n") { it.joinToString("  ") }
    }
}

/** One fresh location (for 📍 Share location in a chat): the last known one if it's new, else asks the phone (15 s). */
object OneLocation {
    @SuppressLint("MissingPermission")
    fun get(ctx: Context, done: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return done(null)
        val all = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val last = all.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000) return done(last)
        val on = all.take(2).filter { p -> try { lm.isProviderEnabled(p) } catch (e: Exception) { false } }
        if (on.isEmpty()) return done(last)
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val cancel = CancellationSignal()
        lateinit var listener: LocationListener
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            try { cancel.cancel() } catch (e: Exception) { }
            if (Build.VERSION.SDK_INT < 30) try { lm.removeUpdates(listener) } catch (e: Exception) { }
            done(l ?: last)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(l: Location) = finish(l)
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        for (p in on) try {
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, cancel, ctx.mainExecutor) { l -> if (l != null) finish(l) }
            else @Suppress("DEPRECATION") lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
        } catch (e: Exception) { }
        main.postDelayed({ finish(null) }, 15_000)
    }
}

/** Little drawing helpers for chat screens. */
object ChatDraw {
    /**
     * The picture of a person or chat: their profile photo if they have one (loaded from the Habit server); else their
     * emoji, or their initials ("RT") when they kept the default 🐾, on a soft colour of their own. Groups are rounded
     * squares; people are circles. Habit AI is a gold circle with a sparkle.
     */
    fun avatar(ctx: Context, store: Store, emoji: String, photo: String, sizePx: Int, ring: Int? = null,
               name: String = "", group: Boolean = false, key: String = "", ai: Boolean = false): android.view.View {
        val f = android.widget.FrameLayout(ctx)
        val dens = ctx.resources.displayMetrics.density
        val radius = if (group) sizePx * 0.34f else sizePx / 2f
        fun shape(color: Int) = if (group) ChatTheme.round(color, radius) else ChatTheme.circle(color)
        val (bg, fg) = ChatTheme.tint(key.ifEmpty { name.ifEmpty { emoji } })
        when {
            ai -> f.addView(android.widget.ImageView(ctx).apply {
                setImageDrawable(ctx.getDrawable(R.drawable.ic_sparkle)!!.mutate()); imageTintList = android.content.res.ColorStateList.valueOf(ChatTheme.Brand.INK)
                val pad = (sizePx * 0.25f).toInt(); setPadding(pad, pad, pad, pad); background = ChatTheme.circle(ChatTheme.Brand.GOLD)
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            group && (emoji.isBlank() || emoji == "👥" || emoji == "🐾") -> f.addView(android.widget.ImageView(ctx).apply {
                setImageDrawable(ctx.getDrawable(R.drawable.ic_users)!!.mutate()); imageTintList = android.content.res.ColorStateList.valueOf(fg)
                val pad = (sizePx * 0.26f).toInt(); setPadding(pad, pad, pad, pad); background = shape(bg)
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            (emoji.isBlank() || emoji == "🐾") && initials(name).isNotEmpty() -> f.addView(android.widget.TextView(ctx).apply {
                text = initials(name); textSize = sizePx / dens * 0.34f; gravity = android.view.Gravity.CENTER; setTextColor(fg)
                typeface = Fonts.heavy(ctx); background = shape(bg); includeFontPadding = false
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            else -> f.addView(android.widget.TextView(ctx).apply {
                text = emoji.ifBlank { "🐾" }; textSize = sizePx / dens * 0.42f; gravity = android.view.Gravity.CENTER; background = shape(bg); includeFontPadding = false
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        if (photo.isNotBlank()) {
            val iv = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = shape(Color.TRANSPARENT); outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
            f.addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1))
            Images.load(if (photo.startsWith("http")) photo else store.server.trimEnd('/') + photo, iv, sizePx)
        }
        if (ring != null) f.foreground = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(maxOf(3, sizePx / 22), ring) }
        return f
    }
    /** "Ravi Teja" → "RT", "Amma" → "A", "రవి" → "ర" (the first letter, with its marks). */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        if (words.isEmpty()) return ""
        fun first(w: String): String {
            val bi = java.text.BreakIterator.getCharacterInstance(); bi.setText(w)
            val end = bi.next(); return if (end > 0) w.substring(0, end) else w.take(1)
        }
        val a = first(words[0])
        val latin = a[0].code < 0x250
        return (if (latin && words.size > 1 && words[1][0].code < 0x250) a + first(words[1]) else a).uppercase()
    }
    /** Angles for little rays (used by the status ring). */
    fun ray(i: Int, n: Int) = Pair(cos(Math.PI * 2 * i / n).toFloat(), sin(Math.PI * 2 * i / n).toFloat())
}
