package com.soulfriend.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Finds ID cards (Aadhaar, PAN, Voter ID, driving licence, passport, ration/rice card) in pictures the person chose,
 * on the phone only: Google's on-phone text reader (ML Kit) reads the words, ZXing reads the Aadhaar QR code.
 * Nothing is sent anywhere. Bank cards are not added this way (a photo of the back would show the CVV).
 */
object CardFinder {
    class Card(val type: String, val number: String, val holder: String)
    sealed class Result {
        class Found(val card: Card, val jpeg: ByteArray) : Result()
        object NotACard : Result()
        class Problem(val message: String) : Result()
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Reads one picture. Blocking: call it off the main thread. */
    fun find(ctx: Context, uri: Uri): Result {
        val bmp = decode(ctx, uri) ?: return Result.Problem("Couldn't open a picture.")
        val qr = try { QrRead.decode(ctx, uri) } catch (e: Throwable) { "" }
        val lines = try {
            val t = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 40, TimeUnit.SECONDS)
            t.textBlocks.flatMap { it.lines }.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })).map { it.text }
        } catch (e: Throwable) {
            if (qr.isBlank()) return Result.Problem("The phone's text reader isn't ready yet (it comes with Google Play services and downloads by itself). Please try again in a few minutes.")
            emptyList()
        }
        val card = classify(lines, qr) ?: return Result.NotACard
        val jpeg = Images.prepare(ctx, uri, 1600) ?: return Result.Problem("Couldn't save a picture.")
        return Result.Found(card, jpeg)
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1800) s *= 2
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
    } catch (e: Throwable) { null }

    // ---------------------------------------------------------------------------------------------
    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val STOP = setOf("government", "govt", "india", "of", "department", "income", "tax", "election", "commission", "card", "licence", "license",
        "union", "driving", "republic", "passport", "male", "female", "date", "birth", "signature", "father", "husband", "mother", "address", "issued",
        "valid", "dob", "yob", "unique", "identification", "authority", "aadhaar", "aadhar", "enrolment", "holder", "ration", "rice", "family", "head",
        "name", "permanent", "account", "number", "identity", "elector", "sex", "year", "the", "and", "state", "andhra", "pradesh", "telangana",
        "transport", "vid", "download", "help", "www", "uidai", "gov", "in", "photo", "blood", "group", "issue", "validity", "till")

    private fun looksLikeName(s: String): Boolean {
        val t = s.trim().trim(':', '-', '/', ' ')
        if (t.length !in 3..40 || !Regex("^[A-Za-z][A-Za-z .']+$").matches(t)) return false
        val words = t.split(Regex("[ .]+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return false
        if (words.count { it.length >= 2 } == 0) return false
        return words.none { it.lowercase() in STOP }
    }

    private fun nice(n: String) = n.trim().trim(':', '-', '/', ' ').split(Regex("\\s+")).joinToString(" ") { w ->
        w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }.replace(Regex("\\.(\\w)")) { m -> "." + m.groupValues[1].uppercase() }
    }

    /** The name after a "Name" label: on the same line after ":" or "/", or on the next line(s). */
    private fun labelled(lines: List<String>, label: Regex, not: Regex? = null): String? {
        for ((i, l) in lines.withIndex()) {
            if (!label.containsMatchIn(l) || (not != null && not.containsMatchIn(l))) continue
            val after = l.substringAfterLast(':', "").ifBlank { l.substringAfterLast('/', "").takeIf { s -> !label.containsMatchIn(s) } ?: "" }
            if (looksLikeName(after)) return nice(after)
            for (k in i + 1..minOf(i + 2, lines.lastIndex)) if (looksLikeName(lines[k])) return nice(lines[k])
        }
        return null
    }

    /** Works out the kind of card, its number and whose it is. */
    fun classify(lines: List<String>, qr: String): Card? {
        val all = lines.joinToString("\n")
        aadhaarQr(qr)?.let { (name, num) ->
            val n = Regex("(?<![\\d ])(\\d{4} ?\\d{4} ?\\d{4})(?! ?\\d)").find(all)?.groupValues?.get(1) ?: num
            return Card("aadhaar", n, name)
        }
        // passport: the machine-readable lines at the bottom
        Regex("P<IND([A-Z<]+)").find(all.replace(" ", ""))?.let { m ->
            val parts = m.groupValues[1].split("<<").map { it.replace("<", " ").trim() }.filter { it.isNotBlank() }
            val name = (parts.drop(1) + parts.take(1)).joinToString(" ")
            val num = Regex("\\b([A-Z]\\d{7})").find(all)?.groupValues?.get(1) ?: ""
            if (name.isNotBlank()) return Card("passport", num, nice(name))
        }
        val pan = Regex("\\b([A-Z]{5}\\d{4}[A-Z])\\b").find(all)?.groupValues?.get(1)
        if (pan != null && re("income ?tax|permanent account|govt\\.? of india").containsMatchIn(all)) {
            val name = labelled(lines, re("\\bname\\b"), re("father|husband|mother")) ?: run {
                val start = lines.indexOfFirst { re("govt|government|income tax").containsMatchIn(it) }
                lines.drop(start + 1).firstOrNull { looksLikeName(it) && it == it.uppercase() }?.let { nice(it) }
            }
            return Card("pan", pan, name ?: "")
        }
        val epic = Regex("\\b([A-Z]{3}\\d{7})\\b").find(all)?.groupValues?.get(1)
        if (epic != null && re("election|elector|identity card").containsMatchIn(all)) {
            return Card("voter", epic, labelled(lines, re("elector'?s name|^name|\\bname\\s*:"), re("father|husband|mother|relation")) ?: "")
        }
        if (re("driving licen[cs]e|\\bDL ?No|licence no|transport department").containsMatchIn(all)) {
            val num = Regex("\\b([A-Z]{2}[ -]?\\d{2}[ -]?\\d{4}[ -]?\\d{7}|[A-Z]{2}\\d{13}|[A-Z]{2}[ -]?\\d{2}[ -]?\\d{9,11})\\b").find(all)?.groupValues?.get(1) ?: ""
            return Card("dl", num, labelled(lines, re("\\bname\\b"), re("father|husband|mother|son|daughter|wife")) ?: "")
        }
        if (re("ration ?card|rice ?card|food security|రేషన్|రైస్ ?కార్డ్|ఆహార భద్రత").containsMatchIn(all)) {
            val num = Regex("\\b([A-Z]{0,4}\\d{8,15})\\b").find(all)?.groupValues?.get(1) ?: ""
            return Card("ration", num, labelled(lines, re("head of (the )?family|card ?holder|\\bname\\b"), re("father|husband")) ?: "")
        }
        // Aadhaar printed on the card or the e-Aadhaar letter
        if (re("aadhaar|aadhar|unique identification|आधार|ఆధార్|uidai|enrolment no").containsMatchIn(all) || re("government of india").containsMatchIn(all)) {
            val num = Regex("(?<![\\d ])(\\d{4} ?\\d{4} ?\\d{4})(?! ?\\d)").find(all)?.groupValues?.get(1)
                ?: Regex("\\b([Xx]{4} ?[Xx]{4} ?\\d{4})\\b").find(all)?.groupValues?.get(1)
            if (num != null) {
                // the name is just above the date of birth or the gender line
                val at = lines.indexOfFirst { re("\\bDOB\\b|D\\.O\\.B|date of birth|year of birth|\\bYoB\\b|జన్మ|पुरुष|महिला|\\bMALE\\b|\\bFEMALE\\b").containsMatchIn(it) }
                val name = if (at > 0) (at - 1 downTo maxOf(0, at - 3)).map { lines[it] }.firstOrNull { looksLikeName(it) } else null
                return Card("aadhaar", num, name?.let { nice(it) } ?: labelled(lines, re("\\bname\\b"), re("father|husband|c/o|s/o|d/o|w/o")) ?: "")
            }
        }
        return null
    }

    /** Aadhaar QR codes: the older XML kind (full number and name) or the newer "Secure QR" (name and the last 4 digits). */
    fun aadhaarQr(q: String): Pair<String, String>? {
        val s = q.trim()
        if (s.contains("PrintLetterBarcodeData")) {
            val name = Regex("name=\"([^\"]+)\"").find(s)?.groupValues?.get(1) ?: return null
            val uid = Regex("uid=\"(\\d{12})\"").find(s)?.groupValues?.get(1) ?: ""
            return nice(name) to uid.chunked(4).joinToString(" ")
        }
        if (s.length < 200 || !s.all { it.isDigit() }) return null
        return try {
            var bytes = java.math.BigInteger(s).toByteArray()
            while (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
            val data = java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            val fields = ArrayList<String>(); var start = 0
            for (i in data.indices) if (data[i] == 255.toByte()) { fields.add(String(data, start, i - start, Charsets.ISO_8859_1)); start = i + 1; if (fields.size > 20) break }
            val off = if (fields.firstOrNull()?.startsWith("V") == true) 1 else 0
            val name = fields.getOrNull(off + 2)?.takeIf { it.isNotBlank() } ?: return null
            val last4 = fields.getOrNull(off + 1)?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } } ?: ""
            nice(name) to (if (last4.isNotEmpty()) "XXXX XXXX $last4" else "")
        } catch (e: Throwable) { null }
    }

    /** Two names are the same person when they share two words, or one long word when one of them is a single word. */
    fun samePerson(a: String, b: String): Boolean {
        fun words(s: String) = s.uppercase(Locale.ROOT).replace(Regex("[^A-Z ]"), " ").split(" ").filter { it.length > 1 }.toSet()
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val common = x intersect y
        return common.size >= 2 || (common.size == 1 && (x.size == 1 || y.size == 1) && common.first().length >= 4) || x == y
    }

    fun key(number: String) = number.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
