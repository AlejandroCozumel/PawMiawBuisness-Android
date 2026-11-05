package com.pawmiaw.business

import android.text.format.DateFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

//
//  Money, dates and names, formatted the way the web's business side shows them (iOS
//  Formatting.swift). Amounts travel in cents; dates as ISO-8601 strings; a business's day
//  is read on its own clock (`Workspace.shared.timeZone`). Swift `Date` is `Instant` here,
//  `TimeZone` is `ZoneId`.
//

object Money {
    /** "$1,234.50 MXN" — the web's `formatMoney`. */
    fun format(cents: Int?, currency: String? = null): String {
        val value = (cents ?: 0) / 100.0
        val locale = if (LocalizationManager.currentLanguage == AppLanguage.spanish) Locale("es", "MX") else Locale.US
        val formatter = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        val code = (currency ?: Workspace.shared.currency).uppercase()
        return "$" + formatter.format(value) + " " + code
    }

    /** "$1,234.50" without the currency, for tight rows. */
    fun short(cents: Int?): String {
        val value = (cents ?: 0) / 100.0
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        return "$" + formatter.format(value)
    }

    /** An amount typed by a person ("123.50", "1,500", "$12") to cents; null when it can't be read. */
    fun parse(text: String): Int? {
        var cleaned = text.trim().replace("$", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val comma = cleaned.lastIndexOf(','); val dot = cleaned.lastIndexOf('.')
        if (comma >= 0 && dot >= 0) {
            cleaned = if (comma > dot) cleaned.replace(".", "").replace(",", ".") else cleaned.replace(",", "")
        } else if (comma >= 0) {
            val parts = cleaned.split(",")
            cleaned = if (parts.size == 2 && parts[1].length <= 2) parts.joinToString(".") else cleaned.replace(",", "")
        }
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value < BigDecimal.ZERO) return null
        return value.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toInt()
    }

    /** Cents as the text a money field starts with ("123.50"). */
    fun input(cents: Int?): String = if (cents == null) "" else String.format(Locale.US, "%.2f", cents / 100.0)
}

object DateText {
    /** An ISO timestamp, a "yyyy-MM-dd" day (noon on the business's clock) or epoch seconds/ms. */
    fun parse(value: String?): Instant? {
        if (value.isNullOrEmpty()) return null
        runCatching { return Instant.parse(value) }
        runCatching { return java.time.OffsetDateTime.parse(value).toInstant() }
        if (value.length == 10) runCatching {
            return LocalDate.parse(value).atTime(12, 0).atZone(Workspace.shared.timeZone).toInstant()
        }
        value.toDoubleOrNull()?.let { n -> return if (n > 1e12) Instant.ofEpochMilli(n.toLong()) else Instant.ofEpochSecond(n.toLong()) }
        // "2026-11-04 10:00:00" (SQLite)
        runCatching { return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC) }
        return null
    }

    fun iso(date: Instant): String = DateTimeFormatter.ISO_INSTANT.format(date.truncatedTo(ChronoUnit.MILLIS))

    /** "2026-11-04" on the business's clock. */
    fun dayKey(date: Instant = Instant.now(), zone: ZoneId? = null): String =
        date.atZone(zone ?: Workspace.shared.timeZone).toLocalDate().toString()

    /** A localized format from a skeleton ("EEEdMMM"), as iOS `setLocalizedDateFormatFromTemplate`. */
    fun format(date: Instant, template: String, zone: ZoneId? = null): String {
        val pattern = DateFormat.getBestDateTimePattern(appLocale, template)
        return DateTimeFormatter.ofPattern(pattern, appLocale).withZone(zone ?: Workspace.shared.timeZone).format(date)
    }

    /** "10:30 a.m." */
    fun time(date: Instant?, zone: ZoneId? = null): String = date?.let { format(it, "jmm", zone) } ?: "—"
    fun time(iso: String?): String = time(parse(iso))

    /** "mié 4 nov" */
    fun day(date: Instant?, zone: ZoneId? = null): String = date?.let { format(it, "EEEdMMM", zone) } ?: "—"
    fun day(iso: String?): String = day(parse(iso))

    /** "miércoles 4 de noviembre" */
    fun longDay(date: Instant?, zone: ZoneId? = null): String = date?.let { format(it, "EEEEdMMMM", zone) } ?: "—"

    /** "4 nov 2026" */
    fun date(date: Instant?, zone: ZoneId? = null): String = date?.let { format(it, "dMMMyyyy", zone) } ?: "—"
    fun date(iso: String?): String = date(parse(iso))

    /** "mié 4 nov, 10:30 a.m." — or "Hoy, 10:30 a.m." / "Mañana, …". */
    fun dayTime(date: Instant?, zone: ZoneId? = null): String =
        if (date == null) "—" else "${relativeDay(date, zone)}, ${time(date, zone)}"
    fun dayTime(iso: String?): String = dayTime(parse(iso))

    /** "Hoy", "Mañana", "Ayer" or the short day. */
    fun relativeDay(date: Instant, zone: ZoneId? = null): String {
        val z = zone ?: Workspace.shared.timeZone
        val day = date.atZone(z).toLocalDate()
        val today = LocalDate.now(z)
        return when (day) {
            today -> L("Today")
            today.plusDays(1) -> L("Tomorrow")
            today.minusDays(1) -> L("Yesterday")
            else -> day(date, zone)
        }
    }

    /** "hace 5 min" / "in 2 h". */
    fun ago(date: Instant?): String {
        if (date == null) return "—"
        return android.text.format.DateUtils.getRelativeTimeSpanString(
            date.toEpochMilli(), System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    fun ago(iso: String?): String = ago(parse(iso))

    /** Today's date on the business's clock. */
    fun today(zone: ZoneId? = null): LocalDate = LocalDate.now(zone ?: Workspace.shared.timeZone)
}

object NameText {
    /** "MG" from "María García". */
    fun initials(name: String?): String {
        val letters = (name ?: "").split(" ").filter { it.isNotEmpty() }.take(2).map { it.first().toString() }.joinToString("")
        return if (letters.isEmpty()) "?" else letters.uppercase()
    }

    /** The first two letters of a name ("Prueba Destino" → "PR"), as the web's client avatars draw them. */
    fun firstLetters(name: String?): String {
        val letters = (name ?: "").trim().filter { it.isLetter() }.take(2)
        return if (letters.isEmpty()) "?" else letters.uppercase()
    }

    fun firstName(name: String?): String = (name ?: "").split(" ").firstOrNull { it.isNotEmpty() } ?: ""
}

/** null for an empty or blank string (Swift `nonEmpty`), trimmed otherwise. */
val String?.nonEmpty: String? get() = this?.trim()?.takeIf { it.isNotEmpty() }
