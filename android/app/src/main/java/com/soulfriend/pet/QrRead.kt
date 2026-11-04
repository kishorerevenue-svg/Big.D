package com.soulfriend.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Finds a QR code in a photo or screenshot, on the phone itself (nothing is sent anywhere for this). */
object QrRead {
    /** The text inside the QR code in the picture at [uri], or "" when there is none. Slow-ish: call it off the main thread. */
    fun decode(ctx: Context, uri: Uri): String {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return ""
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1400) s *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return ""
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
            try { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))), hints).text ?: "" } catch (e: Exception) { "" }
        } catch (e: Throwable) { "" }
    }

    /** For a UPI payment QR code: (who gets the money, their UPI id, the amount); null for other QR codes. */
    fun upi(q: String): Triple<String, String, String>? {
        if (!q.trim().lowercase().startsWith("upi://pay")) return null
        return try {
            val u = Uri.parse(q.trim())
            Triple(u.getQueryParameter("pn").orEmpty(), u.getQueryParameter("pa").orEmpty(), u.getQueryParameter("am").orEmpty())
        } catch (e: Exception) { Triple("", "", "") }
    }

    /** One line saying what the QR code does, for the person and for the AI check. */
    fun describe(q: String): String {
        if (q.isBlank()) return ""
        val p = upi(q)
        if (p != null) {
            val who = listOf(p.first, p.second.takeIf { it.isNotBlank() }?.let { "($it)" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            return "This QR code is for PAYING money" + (if (p.third.isNotBlank()) " ₹${p.third}" else "") + (if (who.isNotBlank()) " to $who" else "") +
                ". Scanning a QR code never gives you money."
        }
        val host = try { Uri.parse(q.trim()).host.orEmpty() } catch (e: Exception) { "" }
        return if (q.startsWith("http", true) && host.isNotBlank()) "This QR code opens a website: $host" else "This QR code says: " + q.take(120)
    }
}
