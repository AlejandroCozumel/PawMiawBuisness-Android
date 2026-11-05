package com.pawmiaw.business.clients

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.pawmiaw.business.*
import java.text.Normalizer
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

//
//  ClientsText.kt (iOS ClientsText.swift)
//
//  The words and small rules the Clients screens share, as the web's copy
//  files say them (patient-chart-copy.ts, records-copy.ts, due-words.ts,
//  copy/client-closing.ts, lib/phone.ts).
//

object ClientsText {
    // MARK: Pets

    fun species(species: String?): String = when (species) {
        "dog" -> L("Dog")
        "cat" -> L("Cat")
        "bird" -> L("Bird")
        "rabbit" -> L("Rabbit")
        "fish" -> L("Fish")
        "reptile" -> L("Reptile")
        "small_pet" -> L("Small pet")
        "other" -> L("Other@species")
        null -> "—"
        else -> species
    }

    /** Swift `[(value:, title:)]`. */
    val speciesOptions: List<Pair<String, String>>
        get() = listOf("dog", "cat", "bird", "rabbit", "fish", "reptile", "small_pet", "other").map { it to species(it) }

    fun sex(sex: String?): String? = when (sex) {
        "male" -> L("Male")
        "female" -> L("Female")
        else -> null
    }

    /** "3 años", "8 meses" from a birth date. */
    fun age(birthDate: String?): String? {
        if (birthDate == null) return null
        val born = DateText.parse(birthDate.take(10)) ?: return null
        val now = Instant.now()
        if (born > now) return null
        val zone = Workspace.shared.timeZone
        val months = ChronoUnit.MONTHS.between(born.atZone(zone), now.atZone(zone)).toInt()
        if (months < 1) return L("Newborn")
        if (months < 12) return LP(months, "%d month", "%d months")
        val years = months / 12
        return LP(years, "%d year", "%d years")
    }

    /** "12.4 kg". */
    fun weight(grams: Double?): String? {
        if (grams == null || grams <= 0) return null
        val kilograms = grams / 1000
        val formatter = NumberFormat.getNumberInstance(appLocale).apply {
            maximumFractionDigits = if (kilograms >= 10) 1 else 2
        }
        val text = runCatching { formatter.format(kilograms) }.getOrNull() ?: String.format(Locale.US, "%.1f", kilograms)
        return "$text kg"
    }

    /** Kilograms typed by a person ("12.5", "12,5") to grams. */
    fun grams(fromKilograms: String): Int? {
        val cleaned = fromKilograms.trim().replace(",", ".")
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value <= 0) return null
        return Math.round(value * 1000).toInt()
    }

    fun kilogramsInput(grams: Double?): String {
        if (grams == null || grams <= 0) return ""
        val kilograms = grams / 1000
        return if (kilograms % 1.0 == 0.0) kilograms.toInt().toString()
        else String.format(Locale.US, "%.2f", kilograms).replace(Regex("0+$"), "")
    }

    /** "Perro · Beagle · Hembra · 12.4 kg". */
    fun profileLine(pet: ClientsPet): String =
        listOfNotNull(species(pet.petSpecies), pet.petBreed.nonEmpty, sex(pet.sex), weight(pet.weight)).joinToString(" · ")

    // MARK: Days

    /** "3 oct", with the year when it is not this one. */
    fun shortDay(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        val zone = Workspace.shared.timeZone
        val sameYear = date.atZone(zone).year == LocalDate.now(zone).year
        return DateText.format(date, if (sameYear) "dMMM" else "dMMMyyyy", zone)
    }

    /** "25 sep 2026, 10:30". */
    fun dayTime(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        return DateText.format(date, "dMMMyyyyjmm", Workspace.shared.timeZone)
    }

    /** "Hoy", "Mañana", or the short day. */
    fun relativeShortDay(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        val zone = Workspace.shared.timeZone
        val day = date.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        if (day == today) return L("Today")
        if (day == today.plusDays(1)) return L("Tomorrow")
        return shortDay(iso)
    }

    fun isPast(iso: String?): Boolean {
        val date = DateText.parse(iso) ?: return false
        return date < Instant.now()
    }

    // MARK: Contact

    fun mailURL(email: String?): String? {
        val address = email.nonEmpty ?: return null
        return "mailto:$address"
    }

    /** A phone loosely checked: empty, or enough digits to be dialled. */
    fun phoneLooksValid(phone: String): Boolean {
        val trimmed = phone.trim()
        if (trimmed.isEmpty()) return true
        val digits = trimmed.filter { it.isDigit() }
        val allowed = trimmed.all { it.isDigit() || " +-().".contains(it) }
        return allowed && digits.length >= 8 && digits.length <= 15
    }

    fun emailLooksValid(email: String): Boolean =
        Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").containsMatchIn(email.trim())

    // MARK: Follow-ups and records

    private class TitlePattern(val en: String, val es: String, val toEnglish: (String) -> String, val toSpanish: (String) -> String)

    /** A follow-up's title in the reader's language ("Rabies booster due" ↔ "Toca: Rabies booster"). */
    fun followUpTitle(title: String?): String {
        val text = (title ?: "").trim()
        val spanish = LocalizationManager.currentLanguage == AppLanguage.spanish
        val patterns = listOf(
            TitlePattern("""^Documentation needed: (.+)$""", """^Falta documentar: (.+)$""", { "Documentation needed: $it" }, { "Falta documentar: $it" }),
            TitlePattern("""^Report to finish: (.+)$""", """^Reporte por terminar: (.+)$""", { "Report to finish: $it" }, { "Reporte por terminar: $it" }),
            TitlePattern("""^Follow up: (.+)$""", """^Seguimiento: (.+)$""", { "Follow up: $it" }, { "Seguimiento: $it" }),
            TitlePattern("""^(.+) due$""", """^Toca: (.+)$""", { "$it due" }, { "Toca: $it" }),
        )
        for (pattern in patterns) {
            for (expression in listOf(pattern.en, pattern.es)) {
                val match = Regex(expression, RegexOption.IGNORE_CASE).find(text) ?: continue
                val subject = match.groupValues[1].trim()
                return if (spanish) pattern.toSpanish(subject) else pattern.toEnglish(subject)
            }
        }
        if (text.equals("Post-hospitalization recheck", ignoreCase = true) || text.equals("Revisión tras la hospitalización", ignoreCase = true)) {
            return if (spanish) "Revisión tras la hospitalización" else "Post-hospitalization recheck"
        }
        return text
    }

    /** "Due today", "3 days overdue", "Due in 5 days". */
    fun dueWords(days: Int): String = when (days) {
        0 -> L("Due today")
        -1 -> L("Due yesterday")
        1 -> L("Due tomorrow")
        else -> if (days < 0) LF("%d days overdue", -days) else LF("Due in %d days", days)
    }

    fun daysUntil(iso: String): Int {
        val date = DateText.parse(iso) ?: return 0
        val zone = Workspace.shared.timeZone
        return ChronoUnit.DAYS.between(LocalDate.now(zone), date.atZone(zone).toLocalDate()).toInt()
    }

    fun recordType(type: String?): String = when (type) {
        "vaccination" -> L("Vaccine")
        "vet_visit" -> L("Vet visit")
        "medication" -> L("Medicine")
        "surgery" -> L("Surgery")
        "diagnosis" -> L("Diagnosis")
        "lab_result" -> L("Lab result")
        "deworming" -> L("Deworming")
        "weight_check" -> L("Weight")
        "allergy" -> L("Allergy")
        else -> L("Note")
    }

    fun followUpType(type: String?): String = when (type) {
        "care_follow_up" -> L("Care follow-up")
        "callback" -> L("Callback")
        "recheck" -> L("Recheck")
        "lab_review" -> L("Lab review")
        "medication_review" -> L("Medication review")
        "vaccine_due" -> L("Vaccine due")
        "documentation_needed" -> L("Documentation needed")
        "billing_follow_up" -> L("Billing follow-up")
        "return_visit" -> L("Return visit")
        else -> L("Other")
    }

    fun followUpStatus(status: String?): String = when (status) {
        "open" -> L("Open@followup")
        "contacted" -> L("Reminder sent")
        "scheduled" -> L("Visit booked")
        "done" -> L("Done@followup")
        "snoozed" -> L("Snoozed")
        "canceled" -> L("Canceled")
        else -> status ?: "—"
    }

    fun visitStatus(status: String?): String = when (status) {
        "requested" -> L("To confirm")
        "checked_in" -> L("Waiting")
        "in_progress" -> L("In progress")
        "completed" -> L("Done@visit")
        "no_show" -> L("No-show")
        "scheduled" -> L("Booked")
        "cancelled" -> L("Cancelled")
        else -> status ?: "—"
    }

    fun invoiceStatus(status: String?): String = when (status) {
        "draft" -> L("Draft")
        "ready_for_payment" -> L("To pay")
        "open" -> L("Open@invoice")
        "partially_paid" -> L("Partly paid")
        "paid" -> L("Paid")
        "overdue" -> L("Overdue@status")
        "canceled" -> L("Cancelled")
        else -> status ?: "—"
    }

    fun vaccineStatus(status: String?): String = when (status) {
        "overdue" -> L("Overdue@status")
        "due_soon" -> L("Due soon")
        "current" -> L("Up to date")
        else -> L("No next dose")
    }

    /** A chart vaccine's state in the six status colours. */
    fun vaccineState(status: String?): ProviderState = when (status) {
        "due_soon" -> ProviderState.waiting
        "no_date", null -> ProviderState.closed
        else -> ProviderState.vaccine(status)
    }

    // MARK: Archiving

    /** A client's or pet's state word where it is not simply active (web `statusLabel`). */
    fun clientStatus(status: String?): String = when (status) {
        "lead" -> L("Lead")
        "active" -> L("Active")
        "archived" -> L("Archived")
        "inactive" -> L("Inactive")
        "deceased" -> L("Deceased")
        else -> {
            if (status.isNullOrEmpty()) "" else (status.first().uppercase() + status.drop(1)).replace("_", " ")
        }
    }

    fun closingReason(reason: String?): String? = when (reason) {
        null -> null
        "rehomed" -> L("Has a new family")
        "moved" -> L("Moved away")
        "no_longer_comes" -> L("No longer comes")
        "other_provider" -> L("Goes somewhere else")
        "other" -> L("Other")
        "deceased" -> L("Died")
        "client_archived" -> L("Archived with the client")
        else -> reason
    }

    /** The name as typed is the record's own, whatever the case, accents or spaces. */
    fun typedNameMatches(typed: String, name: String): Boolean {
        fun fold(value: String): String {
            val plain = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            val letters = plain.map { c -> if ((c.isLetter() && c.code < 128) || c.isDigit() || c == ' ') c.toString() else " " }.joinToString("")
            return letters.split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        }
        val wanted = fold(name)
        if (wanted.isEmpty()) {
            return name.trim().isNotEmpty() && typed.trim().lowercase() == name.trim().lowercase()
        }
        return fold(typed) == wanted
    }

    fun blocker(code: String, name: String): String = when (code) {
        "visit_in_progress" -> LF("%@ has a visit in progress. Finish it first.", name)
        "in_hospital" -> LF("%@ is in the hospital. Discharge it first; a death there is recorded on the hospitalization.", name)
        "on_stay" -> LF("%@ is on a stay. Check it out first.", name)
        "stay_booked" -> LF("%@ has a stay booked. Cancel it first.", name)
        else -> code
    }
}

/** The members of the business (for "Anyone / Unassigned" and the preferred vet). */
object ClientsMembers {
    suspend fun load(): List<ClientsMember> {
        val clinic = Workspace.shared.current?.clinicId ?: return emptyList()
        return try {
            API.get<List<ClientsMember>>("/provider-workspaces/$clinic/members")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Copies text to the clipboard with the toast the web shows. */
fun clientsCopy(text: String?, done: String) {
    val value = text.nonEmpty ?: return
    val clipboard = AppContext.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(value, value))
    Toast.show(done)
}
