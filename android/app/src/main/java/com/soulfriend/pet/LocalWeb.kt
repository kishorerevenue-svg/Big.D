package com.soulfriend.pet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * The phone reads the internet by itself when the AI can't be reached (our server is down or the AI is busy):
 * weather from Open-Meteo, news headlines from Google News and short explanations from Wikipedia (Telugu or English).
 * The phone asks these sites directly; nothing goes through the BigD server. Blocking: call it off the main thread.
 */
object LocalWeb {
    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val TELUGU = Regex("[\\u0C00-\\u0C7F]")

    fun answer(ctx: Context, store: Store, input: String): JSONObject? = answerWith(store.lang, { lastPlace(ctx) }, input)

    internal fun answerWith(lang: String, place: () -> Pair<Double, Double>?, input: String): JSONObject? {
        val t = input.trim().trim('?', '.', '!', ' ').replace(Regex("\\s+"), " ")
        if (t.length < 3 || t.length > 200) return null
        val te = TELUGU.containsMatchIn(t) || lang == "te"
        val out = try { weather(place, t, te) ?: news(t, te) ?: wiki(t, te) } catch (e: Exception) { null } ?: return null
        return out.put("reply", out.optString("reply") + LocalBrain.noteOnce(te))
    }

    private fun reply(text: String, emotion: String, sources: List<Pair<String, String>> = emptyList()) = JSONObject()
        .put("reply", text).put("emotion", emotion).put("actions", JSONArray()).put("offline", true)
        .put("sources", JSONArray().also { a -> sources.forEach { (title, url) -> a.put(JSONObject().put("title", title).put("url", url)) } })

    /** How the phone fetches a page (tests swap it). */
    internal var fetch: (String) -> String? = { url -> download(url) }
    private fun get(url: String): String? = fetch(url)

    private fun download(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "BigD-AI/1.0 (Android app; https://bigdai.tech)")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- weather (Open-Meteo, free, no key) ----------------
    private val WEATHER = re("weather|temperature|forecast|\\brain\\b|raining|వాతావరణం|వర్షం|వాన|ఉష్ణోగ్రత|ఎండ ఎలా|vatavaranam|varsham")

    private fun weather(place: () -> Pair<Double, Double>?, t: String, te: Boolean): JSONObject? {
        if (!WEATHER.containsMatchIn(t)) return null
        val w0 = t.replace(re("^(what('?s| is| will be)|how('?s| is)|tell me|show me|give me|check|will it)\\s+(the\\s+)?"), "")
            .replace(re("\\s+(like|now|today|tomorrow|please|outside)$"), "").trim()
        val city = (re("(?:weather|temperature|forecast|rain)\\s+(?:in|at|of|for)\\s+(.+?)(?:\\s+(?:today|now|tomorrow))?$").find(w0)
            ?: re("^(.+?)\\s+(?:weather|temperature|forecast)(?:\\s+(?:today|now|tomorrow))?$").find(w0)
            ?: re("\\b(?:in|at|for)\\s+([\\p{L} .'-]{2,30})$").find(w0)
            ?: re("^(.+?)\\s*(?:లో|లొ)?\\s*(?:వాతావరణం|వర్షం|వాన|ఉష్ణోగ్రత)").find(t))?.groupValues?.get(1)?.trim()
            ?.replace(re("^(the|today'?s|ఈ ?రోజు|ఇవాళ|ఇప్పుడు)\\s*"), "")?.trim()?.takeIf { it.length in 2..40 && !re("^(what|how|is|will|today|ఈ|ఇవాళ)$").matches(it) }
        var name = city ?: ""
        var lat: Double? = null; var lon: Double? = null
        if (city != null) {
            val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=${if (te) "te" else "en"}&countryCode=IN&name=" + enc(city))
                ?.let { JSONObject(it).optJSONArray("results")?.optJSONObject(0) }
                ?: get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + enc(city))?.let { JSONObject(it).optJSONArray("results")?.optJSONObject(0) }
                ?: return reply(if (te) "🌤️ \"$city\" అనే ఊరు దొరకలేదు. ఊరి పేరు ఇంకోసారి చెప్పండి." else "🌤️ I couldn't find a place called \"$city\". Please say the town's name again.", "confused")
            lat = g.optDouble("latitude"); lon = g.optDouble("longitude"); name = g.optString("name", city)
        } else place()?.let { (a, b) -> lat = a; lon = b; name = if (te) "మీ దగ్గర" else "Near you" }
        if (lat == null || lon == null) return reply(if (te) "🌤️ ఏ ఊరి వాతావరణం? ఉదాహరణకి \"విశాఖపట్నం వాతావరణం\" అని అడగండి." else "🌤️ Which town? Ask for example \"weather in Visakhapatnam\".", "thinking")
        val w = get("https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.ROOT, lat, lon) +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&timezone=Asia%2FKolkata&forecast_days=2")?.let { JSONObject(it) } ?: return null
        val cur = w.optJSONObject("current") ?: return null
        val d = w.optJSONObject("daily")
        fun day(i: Int, k: String) = d?.optJSONArray(k)?.optDouble(i)?.takeIf { !it.isNaN() }
        val now = "${cur.optDouble("temperature_2m").toInt()}°C, ${sky(cur.optInt("weather_code"), te)}"
        val hum = cur.optInt("relative_humidity_2m")
        val today = listOfNotNull(day(0, "temperature_2m_max")?.let { "${it.toInt()}°" }, day(0, "temperature_2m_min")?.let { "${it.toInt()}°" }).joinToString(" / ")
        val rain0 = day(0, "precipitation_probability_max")?.toInt()
        val tomorrow = listOfNotNull(day(1, "temperature_2m_max")?.let { "${it.toInt()}°" }, day(1, "temperature_2m_min")?.let { "${it.toInt()}°" }).joinToString(" / ")
        val rain1 = day(1, "precipitation_probability_max")?.toInt()
        val text = if (te) "🌤️ $name ఇప్పుడు: $now, తేమ $hum%." +
                (if (today.isNotEmpty()) " ఈ రోజు $today" + (rain0?.let { ", వర్షం అవకాశం $it%" } ?: "") + "." else "") +
                (if (tomorrow.isNotEmpty()) " రేపు $tomorrow, ${sky(d?.optJSONArray("weather_code")?.optInt(1) ?: 0, true)}" + (rain1?.let { ", వర్షం అవకాశం $it%" } ?: "") + "." else "")
            else "🌤️ $name now: $now, humidity $hum%." +
                (if (today.isNotEmpty()) " Today $today" + (rain0?.let { ", $it% chance of rain" } ?: "") + "." else "") +
                (if (tomorrow.isNotEmpty()) " Tomorrow $tomorrow, ${sky(d?.optJSONArray("weather_code")?.optInt(1) ?: 0, false)}" + (rain1?.let { ", $it% chance of rain" } ?: "") + "." else "")
        return reply(text, if ((rain0 ?: 0) >= 60) "thinking" else "happy", listOf("Open-Meteo weather" to "https://open-meteo.com/"))
    }

    /** WMO weather codes in simple words. */
    private fun sky(code: Int, te: Boolean): String = when (code) {
        0 -> if (te) "ఆకాశం నిర్మలంగా ఉంది" else "clear sky"
        1, 2 -> if (te) "కొంచెం మేఘాలు" else "partly cloudy"
        3 -> if (te) "మేఘావృతం" else "cloudy"
        45, 48 -> if (te) "పొగమంచు" else "fog"
        51, 53, 55, 56, 57 -> if (te) "చిరుజల్లులు" else "drizzle"
        61, 63, 66, 80, 81 -> if (te) "వర్షం" else "rain"
        65, 67, 82 -> if (te) "భారీ వర్షం" else "heavy rain"
        71, 73, 75, 77, 85, 86 -> if (te) "మంచు" else "snow"
        95, 96, 99 -> if (te) "ఉరుములతో వర్షం" else "thunderstorm"
        else -> if (te) "మారుతున్న వాతావరణం" else "changing weather"
    }

    /** The phone's last known place, only if the person already allowed location (rounded to about 10 km). */
    private fun lastPlace(ctx: Context): Pair<Double, Double>? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val loc = try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } }.maxByOrNull { it.time }
        } catch (e: Exception) { null } ?: return null
        return Math.round(loc.latitude * 10) / 10.0 to Math.round(loc.longitude * 10) / 10.0
    }

    // ---------------- news headlines (Google News) ----------------
    private fun news(t: String, te: Boolean): JSONObject? {
        if (!re("news|headlines|వార్తలు|వార్త|న్యూస్|samacharam|varthalu").containsMatchIn(t)) return null
        val topic = (re("(?:news|headlines)\\s+(?:about|on|of|from|in)\\s+(.+)$").find(t)
            ?: re("^(?:latest |today'?s |top )?(.+?)\\s+(?:news|headlines)$").find(t)
            ?: re("^(.+?)\\s*(?:కి|కు|లో)?\\s*(?:సంబంధించిన\\s*)?(?:వార్తలు|వార్త|న్యూస్)").find(t))?.groupValues?.get(1)?.trim()
            ?.replace(re("^(the|latest|today'?s|top|ఈ ?రోజు|ఇవాళ|తాజా|ముఖ్య|ఈరోజు)\\s*"), "")?.trim()
            ?.takeIf { it.length in 2..60 && !re("^(today|latest|top|the|ఈ ?రోజు|ఇవాళ|తాజా|ముఖ్య|ఈరోజు)$").matches(it) }
        val loc = if (te) "hl=te&gl=IN&ceid=IN:te" else "hl=en-IN&gl=IN&ceid=IN:en"
        val xml = get(if (topic != null) "https://news.google.com/rss/search?q=${enc(topic)}&$loc" else "https://news.google.com/rss?$loc") ?: return null
        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val b = m.groupValues[1]
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { clean(it) } ?: return@mapNotNull null
            val link = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            title to link
        }.take(6).toList()
        if (items.isEmpty()) return reply(if (te) "📰 ఇప్పుడు వార్తలు దొరకలేదు." else "📰 I couldn't find news right now.", "sad")
        val head = if (topic != null) (if (te) "📰 \"$topic\" తాజా వార్తలు:" else "📰 Latest news about \"$topic\":") else (if (te) "📰 ముఖ్య వార్తలు:" else "📰 Top news now:")
        val text = head + "\n" + items.mapIndexed { i, (title, _) -> "${i + 1}. " + title }.joinToString("\n")
        return reply(text, "happy", items.take(3).map { (title, link) -> title.substringAfterLast(" - ").take(60) to link })
    }

    private fun clean(s: String) = s.replace("<![CDATA[", "").replace("]]>", "").replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }.trim()

    // ---------------- explanations (Wikipedia) ----------------
    private fun wiki(t: String, te: Boolean): JSONObject? {
        val q = (re("^(?:what|who|where) (?:is|are|was|were) (?:a |an |the )?(.+)$").find(t)
            ?: re("^(?:tell me about|explain|define|meaning of|information about|info about)\\s+(?:the )?(.+)$").find(t)
            ?: re("^(.+?)\\s+(?:meaning|means)$").find(t)
            ?: re("^(.+?)\\s*(?:అంటే ఏమిటి|అంటే ఏంటి|అంటే ఏమి|ఎవరు|ఎక్కడ ఉంది|గురించి చెప్పు|గురించి చెప్పండి|గురించి వివరించు|గురించి)$").find(t)
            ?: re("^(.+?)\\s+(?:ante enti|ante emiti|evaru|gurinchi cheppu|gurinchi)$").find(t))?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length in 2..80 } ?: return null
        val langs = if (TELUGU.containsMatchIn(q)) listOf("te", "en") else if (te) listOf("te", "en") else listOf("en")
        for (lang in langs) {
            val js = get("https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&prop=extracts%7Cinfo&inprop=url&exintro=1&explaintext=1&exchars=700" +
                "&redirects=1&generator=search&gsrlimit=1&gsrsearch=" + enc(q)) ?: continue
            val page = JSONObject(js).optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: continue
            val extract = page.optString("extract").replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
            if (extract.length < 40) continue
            val title = page.optString("title")
            val url = page.optString("fullurl").ifBlank { "https://$lang.wikipedia.org/wiki/" + enc(title.replace(' ', '_')) }
            return reply("📖 " + extract.take(700) + (if (lang == "en" && te) "\n(వికీపీడియా నుంచి, ఇంగ్లీష్‌లో)" else ""), "happy", listOf("Wikipedia: $title" to url))
        }
        return null
    }
}
