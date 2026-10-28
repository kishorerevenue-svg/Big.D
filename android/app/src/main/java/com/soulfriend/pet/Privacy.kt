package com.soulfriend.pet

/** Hides Aadhaar, phone and PAN numbers (keeps the last 4 digits) when "Hide personal details" is on. */
object Privacy {
    private val aadhaar = Regex("(?<![\\d])(\\d{4})[ -]?(\\d{4})[ -]?(\\d{4})(?![\\d])")
    private val phone = Regex("(?<![\\d])(?:\\+?91[ -]?)?[6-9](?:[ -]?\\d){9}(?![\\d])")
    private val pan = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")

    fun mask(text: String): String = text
        .replace(aadhaar) { "XXXX XXXX " + it.groupValues[3] }
        .replace(phone) { "XXXXXX" + it.value.filter(Char::isDigit).takeLast(4) }
        .replace(pan) { "XXXXX" + it.value.substring(5, 9) + "X" }

    fun maybe(store: Store, text: String) = if (store.hidePersonal) mask(text) else text
}
