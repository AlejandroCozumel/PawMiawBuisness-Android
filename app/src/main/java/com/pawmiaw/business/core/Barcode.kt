package com.pawmiaw.business

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

//
//  What a scan means, with no camera in it (web lib/barcode-scan.ts; iOS Barcode.swift): which
//  reads can be trusted (a check digit that holds, or the same read twice), what a GS1 label
//  says (the product's GTIN, its lot, its expiry), which spellings of one product code are the
//  same code (a GTIN-14 with a leading 0, an EAN-13, a UPC-A), and how a code is looked up when
//  the server matches barcodes exactly — two products with one code come back as `matches`, so
//  the screen asks which. The camera (design/BarcodeScanner.kt) hands its reads here. Used by
//  the Store's counter, the Catalog's product sheet and quick edit, the bill's item search,
//  Inventory's search, stock settings and the delivery's product picker.
//

/** One read (or one typed code): the code to look up or store and what else the label said. */
data class BarcodeScan(
    /** The GTIN of a GS1 label, otherwise what was read. */
    val code: String,
    /** Exactly what the symbol (or the keyboard) said. */
    val raw: String,
    /** The symbol's format by the web's names (`ean_13`, `code_128`, …); null when typed. */
    val format: String?,
    val source: Source,
    /** From a GS1 label: the lot (AI 10). */
    val lot: String?,
    /** From a GS1 label: the expiry (AI 17) as yyyy-MM-dd. */
    val expiresOn: String?,
) {
    enum class Source { camera, photo, typed }
}

/** A GS1 element string, read. */
data class GS1Label(
    val gtin: String?,
    val lot: String?,
    val expiresOn: String?,
    /** Every element read, by its application identifier. */
    val elements: Map<String, String>,
)

// Swift's `.whitespacesAndNewlines` / `.whitespaces`. Kotlin's `trim()` would also take the GS1
// group separator (U+001D counts as whitespace in Java), which is part of a label.
private fun Char.isSwiftSpace(newlines: Boolean): Boolean {
    if (this == '\t') return true
    if (newlines && (this in '\n'..'\r' || this == '\u0085')) return true
    val type = Character.getType(this).toByte()
    return type == Character.SPACE_SEPARATOR ||
        (newlines && (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR))
}

private fun String.trimWS(): String = trim { it.isSwiftSpace(newlines = true) }
private fun String.trimSpaces(): String = trim { it.isSwiftSpace(newlines = false) }

object Barcode {
    /** Formats whose last digit checks the others: one valid read is enough. */
    val checkedFormats: Set<String> = setOf("ean_13", "ean_8", "upc_a", "upc_e")

    /** FNC1 as decoders hand it over: the ASCII group separator. */
    const val groupSeparator: Char = '\u001d'

    private fun isDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

    /** The GS1 mod-10 check of a GTIN-8, UPC-A, EAN-13 or GTIN-14 (weights 3 and 1 from the right). */
    fun hasValidCheckDigit(code: String): Boolean {
        if (!isDigits(code) || code.length !in setOf(8, 12, 13, 14)) return false
        val digits = code.map { it - '0' }
        var sum = 0
        var weight = 3
        for (index in digits.size - 2 downTo 0) {
            sum += digits[index] * weight
            weight = 4 - weight
        }
        return (10 - (sum % 10)) % 10 == digits[digits.size - 1]
    }

    /** A UPC-E (8 digits, with its number system and check digit) written out as the UPC-A it abbreviates. */
    fun expandUpcE(code: String): String? {
        if (code.length != 8 || !isDigits(code)) return null
        val first = code[0]
        if (first != '0' && first != '1') return null
        val system = code[0].toString()
        val a = code[1]; val b = code[2]; val c = code[3]; val d = code[4]; val e = code[5]; val last = code[6]
        val check = code[7].toString()
        val body = when (last) {
            '0', '1', '2' -> "$a$b${last}0000$c$d$e"
            '3' -> "$a$b${c}00000$d$e"
            '4' -> "$a$b$c${d}00000$e"
            else -> "$a$b$c$d${e}0000$last"
        }
        return system + body + check
    }

    /** Whether a text reads as a product code (digits in it, no spaces) rather than a name someone searched for. */
    fun looksLikeCode(text: String): Boolean {
        val trimmed = text.trimWS()
        return trimmed.length >= 4 && trimmed.any { it.isDigit() } && trimmed.none { it.isSwiftSpace(newlines = true) || it == ' ' }
    }

    /** A GTIN of 12 to 14 digits as its 14: what two spellings of one code share. */
    private fun gtin14(code: String): String? {
        val trimmed = code.trimWS()
        if (!isDigits(trimmed) || trimmed.length !in 12..14) return null
        return "0".repeat(14 - trimmed.length) + trimmed
    }

    /** Whether two stored or scanned codes are the same product code (a leading 0 apart, or identical). */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val left = a.trimWS()
        val right = b.trimWS()
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        val padded = gtin14(left) ?: return false
        return padded == gtin14(right)
    }

    /**
     * Every spelling a stored barcode may have for this code, the code itself first.
     * The server matches a barcode exactly, so each is searched in turn.
     */
    fun variants(code: String, format: String? = null): List<String> {
        val trimmed = code.trimWS()
        if (trimmed.isEmpty()) return emptyList()
        val list = mutableListOf(trimmed)
        fun add(value: String) { if (value !in list) list.add(value) }
        val expanded = if (format == "upc_e") expandUpcE(trimmed) else null
        val padded = gtin14(expanded ?: trimmed)
        if (padded != null) {
            if (expanded != null) add(expanded)
            var significant: String = padded
            var dropped = 0
            while (dropped < 2 && significant.firstOrNull() == '0') {
                significant = significant.drop(1)
                dropped += 1
            }
            // Shortest first after the code itself: stores keep what is printed under the bars.
            if (significant.length <= 12) add(padded.drop(2))
            if (significant.length <= 13) add(padded.drop(1))
            add(padded)
        }
        return list
    }

    // MARK: GS1 labels

    /** How many digits an application identifier has, by its first two. */
    private fun identifierLength(firstTwo: String): Int {
        if (firstTwo in setOf("23", "24", "25", "40", "41", "42", "71")) return 3
        if (firstTwo in setOf("31", "32", "33", "34", "35", "36", "39", "43", "70", "72", "80", "81", "82")) return 4
        return 2
    }

    /** Elements of a fixed length (identifier included), by their first two digits: no separator follows them. */
    private val fixedLengths: Map<String, Int> = mapOf(
        "00" to 20, "01" to 16, "02" to 16, "03" to 16, "04" to 18,
        "11" to 8, "12" to 8, "13" to 8, "14" to 8, "15" to 8, "16" to 8, "17" to 8, "18" to 8, "19" to 8,
        "20" to 4, "31" to 10, "32" to 10, "33" to 10, "34" to 10, "35" to 10, "36" to 10, "41" to 16,
    )

    /** The longest value an element without a fixed length may carry. */
    private const val maxVariable = 90

    /** `(01)07501234567893(17)271231(10)L123`: what a label prints, and what some decoders return. */
    private fun parseBracketed(text: String): Map<String, String>? {
        val elements = mutableMapOf<String, String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.first() != '(') return null
            val close = rest.indexOf(')')
            if (close < 0) return null
            val identifier = rest.substring(1, close)
            if (identifier.length !in 2..4 || !isDigits(identifier)) return null
            val afterClose = close + 1
            val nextOpen = rest.indexOf('(', afterClose)
            val end = if (nextOpen >= 0) nextOpen else rest.length
            val value = rest.substring(afterClose, end).filter { it != groupSeparator }.trimSpaces()
            if (value.isEmpty()) return null
            elements[identifier] = value
            rest = rest.substring(end)
        }
        return elements.ifEmpty { null }
    }

    /** `010750123456789317271231` + `10L123<GS>21…`: the element string as it is encoded. */
    private fun parsePlain(text: String): Map<String, String>? {
        val elements = mutableMapOf<String, String>()
        var at = 0
        fun slice(from: Int, to: Int): String {
            val start = from.coerceIn(0, text.length)
            val stop = to.coerceIn(0, text.length)
            return if (stop > start) text.substring(start, stop) else ""
        }
        while (at < text.length) {
            if (text[at] == groupSeparator) { at += 1; continue }
            val firstTwo = slice(at, at + 2)
            if (firstTwo.length != 2 || !isDigits(firstTwo)) return null
            val idLength = identifierLength(firstTwo)
            val identifier = slice(at, at + idLength)
            if (identifier.length != idLength || !isDigits(identifier)) return null
            val value: String
            val fixed = fixedLengths[firstTwo]
            if (fixed != null) {
                value = slice(at + idLength, at + fixed)
                if (value.length != fixed - idLength || value.contains(groupSeparator)) return null
                at += fixed
            } else {
                val end = text.indexOf(groupSeparator, at).takeIf { it >= 0 }
                value = slice(at + idLength, end ?: text.length)
                if (value.isEmpty() || value.length > maxVariable) return null
                at = end?.let { it + 1 } ?: text.length
            }
            elements[identifier] = value
        }
        return elements.ifEmpty { null }
    }

    /**
     * The expiry of a label (YYMMDD) as a date. The century is the one that puts the year
     * closest to now (GS1's rule: 49 years back, 50 ahead); day 00 means the last day of the month.
     */
    fun gs1Date(yymmdd: String, now: Instant = Instant.now()): String? {
        if (yymmdd.length != 6 || !isDigits(yymmdd)) return null
        val yy = yymmdd.substring(0, 2).toInt()
        val month = yymmdd.substring(2, 4).toInt()
        var day = yymmdd.substring(4, 6).toInt()
        if (month !in 1..12) return null
        val current = now.atZone(ZoneId.systemDefault()).year
        val gap = yy - (current % 100)
        val century = current - (current % 100) + (if (gap >= 51) -100 else if (gap <= -50) 100 else 0)
        val year = century + yy
        val lastDay = YearMonth.of(year, month).lengthOfMonth()
        if (day == 0) day = lastDay
        if (day > lastDay) return null
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    /**
     * A GS1 element string, however the decoder spelled it — with the symbology identifier
     * (`]d2`, `]C1`, `]Q3`), a leading FNC1, group separators between elements, or the printed
     * form with parentheses — or null when the text is something else (a plain code, a URL, a name).
     */
    fun parseGS1(raw: String, now: Instant = Instant.now(), format: String? = null): GS1Label? {
        var text = raw.trimWS()
        val flagged = listOf("]d2", "]C1", "]Q3", "]e0", "]J1").any { text.startsWith(it) }
        if (flagged) text = text.drop(3)
        val fnc1 = text.firstOrNull() == groupSeparator
        text = text.trimStart(groupSeparator)
        if (text.isEmpty()) return null

        val bracketed = text.startsWith("(")
        val elements = (if (bracketed) parseBracketed(text) else parsePlain(text)) ?: return null
        val gtin = elements["01"]
        if (gtin != null && !(isDigits(gtin) && gtin.length == 14 && hasValidCheckDigit(gtin))) return null
        // Bare digits with no marker could be any number: they are a label only when they carry a GTIN and
        // more — or a GTIN alone in a symbol that labels use (a decoder may drop the FNC1 that said so).
        if (!flagged && !fnc1 && !bracketed) {
            val labelSymbol = format == "code_128" || format == "data_matrix"
            if (gtin == null || (elements.size < 2 && !labelSymbol)) return null
        }
        return GS1Label(
            gtin = gtin,
            lot = elements["10"]?.trimSpaces().nonEmpty,
            expiresOn = elements["17"]?.let { gs1Date(it, now) },
            elements = elements,
        )
    }

    /** A GTIN-14 as stores keep it: without the leading 0 of an item sold one by one. */
    private fun gtinAsCode(gtin: String): String = if (gtin.startsWith("0")) gtin.drop(1) else gtin

    /** What one read (or one typed code) means: the code to use and what else the label said. */
    fun interpret(raw: String, format: String?, source: BarcodeScan.Source, now: Instant = Instant.now()): BarcodeScan {
        val text = raw.trimWS()
        // Linear retail codes are numbers and nothing else: never read as a label.
        val label = if (format != null && format in checkedFormats) null else parseGS1(text, now, format)
        val gtin = label?.gtin
        if (label != null && gtin != null) {
            return BarcodeScan(code = gtinAsCode(gtin), raw = text, format = format, source = source, lot = label.lot, expiresOn = label.expiresOn)
        }
        return BarcodeScan(code = text.filter { it != groupSeparator }, raw = text, format = format, source = source, lot = null, expiresOn = null)
    }

    // MARK: Looking a code up

    /**
     * What a code lookup found. `matches` is every product the code belongs to exactly: two of
     * them leave `exact` empty, so the screen asks which instead of picking the first. `found` is
     * what the code itself returned (names match too). `code` is the spelling that matched.
     */
    data class Lookup<T>(
        val exact: T?,
        val matches: List<T>,
        val found: List<T>,
        val code: String,
    )

    /**
     * Looks a scanned or typed code up through a search that matches barcodes exactly: the code as
     * given, then — only when nothing has it — its other spellings.
     */
    suspend fun <T> findByCode(
        code: String,
        format: String? = null,
        barcode: (T) -> String?,
        sku: (T) -> String?,
        key: (T) -> String,
        search: suspend (String) -> List<T>,
    ): Lookup<T> {
        val all = variants(code, format)
        val first = all.firstOrNull() ?: return Lookup(exact = null, matches = emptyList(), found = emptyList(), code = code)
        fun distinct(entries: List<T>): List<T> {
            val seen = HashSet<String>()
            return entries.filter { seen.add(key(it)) }
        }
        val lower = first.lowercase()
        val found = search(first)
        val matches = distinct(found.filter { same(barcode(it), first) || sku(it)?.lowercase() == lower })
        if (matches.isNotEmpty()) return Lookup(exact = if (matches.size == 1) matches[0] else null, matches = matches, found = found, code = first)
        for (variant in all.drop(1)) {
            val variantMatches = distinct(search(variant).filter { same(barcode(it), variant) })
            if (variantMatches.isNotEmpty()) {
                return Lookup(exact = if (variantMatches.size == 1) variantMatches[0] else null, matches = variantMatches, found = found, code = variant)
            }
        }
        return Lookup(exact = null, matches = emptyList(), found = found, code = first)
    }
}

/**
 * When to believe the camera. A blurred frame can decode to a wrong number: a code whose own
 * check digit holds (EAN, UPC) is taken at once and one that fails it is thrown away; anything
 * else must be read the same twice in a row.
 */
class BarcodeStableReader {
    private var previous: String? = null

    /** A frame's read; true when it can be trusted. */
    fun push(value: String, format: String?): Boolean {
        val text = value.trimWS()
        if (text.isEmpty()) return false
        if (format != null && format in Barcode.checkedFormats) {
            val digits = if (format == "upc_e") (Barcode.expandUpcE(text) ?: text) else text
            if (Barcode.hasValidCheckDigit(digits)) return true
            previous = null
            return false
        }
        val key = "${format ?: ""}:$text"
        if (previous == key) return true
        previous = key
        return false
    }

    fun reset() {
        previous = null
    }
}
