package com.pawmiaw.business.records

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.pawmiaw.business.*
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

//
//  RecordsText.kt (iOS RecordsText.swift)
//
//  The words of the Records pages that depend on a value (lib/records-copy.ts,
//  lib/due-words.ts): days, follow-up kinds and states, how late something
//  is, and the titles and notes the system writes on a follow-up, read in the
//  language of the screen whichever one they were stored in. Days are read on
//  the business's clock.
//

object RecordsText {
    val isSpanish: Boolean get() = LocalizationManager.currentLanguage == AppLanguage.spanish

    /** "lun 29 sep" → "Lun 29 sep" (the web's `first-letter:uppercase`). */
    fun capitalizedFirst(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, 1).uppercase() + text.substring(1)
    }

    /** The text with what was searched for marked in MIAW Yellow at 55% (web `Highlight`). */
    @Composable
    fun highlight(text: String, needle: String): AnnotatedString {
        val wanted = needle.trim()
        if (wanted.isEmpty()) return AnnotatedString(text)
        val range = foldedRange(text, wanted) ?: return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(background = Theme.lime.copy(alpha = 0.55f)), range.first, range.last + 1)
        }
    }

    /** Where `needle` sits in `text`, case- and accent-insensitive (iOS `[.caseInsensitive, .diacriticInsensitive]`). */
    private fun foldedRange(text: String, needle: String): IntRange? {
        val folded = StringBuilder()
        val origin = ArrayList<Int>()
        text.forEachIndexed { index, char ->
            val plain = fold(char.toString())
            for (c in plain) {
                folded.append(c)
                origin.add(index)
            }
        }
        val wanted = fold(needle)
        if (wanted.isEmpty()) return null
        val at = folded.indexOf(wanted)
        if (at < 0) return null
        return origin[at]..origin[at + wanted.length - 1]
    }

    private val marks = Regex("\\p{Mn}+")
    private fun fold(text: String): String = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    private fun sameYear(date: Instant): Boolean {
        val zone = Workspace.shared.timeZone
        return date.atZone(zone).year == LocalDate.now(zone).year
    }

    /** "25 sep" — with the year when it isn't this year's. */
    fun day(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        return DateText.format(date, if (sameYear(date)) "dMMM" else "dMMMyyyy")
    }

    /** "25 de septiembre de 2026". */
    fun longDate(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        return DateText.format(date, "dMMMMyyyy")
    }

    /** "25 sep 2026, 10:30". */
    fun dateTime(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        return DateText.format(date, "dMMMyyyyjmm")
    }

    /** "10:30": the time a record was written at; null for one saved with only its day (noon sharp). */
    fun time(iso: String?): String? {
        if (iso == null || iso.length <= 10) return null
        val date = DateText.parse(iso) ?: return null
        val parts = date.atZone(Workspace.shared.timeZone)
        if (parts.hour == 12 && parts.minute == 0 && parts.second == 0) return null
        return DateText.time(date)
    }

    /** The day something falls on, as yyyy-MM-dd: what the lists group by. */
    fun dayKey(iso: String?): String {
        val date = DateText.parse(iso) ?: return ""
        return DateText.dayKey(date)
    }

    /** Whole calendar days from today to that day; negative once it is past. */
    fun daysUntil(iso: String?): Int {
        val date = DateText.parse(iso) ?: return 0
        val zone = Workspace.shared.timeZone
        val from = LocalDate.now(zone)
        val to = date.atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(from, to).toInt()
    }

    /** A day's heading in a list: "Hoy", "Ayer", "Mañana", else "lun 29 sep" (with the year when it isn't this year's). */
    fun dayHeading(key: String): String {
        if (key.isEmpty()) return "—"
        val date = DateText.parse(key) ?: return "—"
        return when (daysUntil(key)) {
            0 -> L("Today")
            -1 -> L("Yesterday")
            1 -> L("Tomorrow")
            else -> {
                val text = DateText.format(date, if (sameYear(date)) "EEEdMMM" else "EEEdMMMyyyy")
                if (isSpanish) text.replace(".", "").replace(",", "").replace(" de ", " ") else text
            }
        }
    }

    /** "Toca hoy", "Vencido hace 3 días", "Toca en 5 días" (due-words `dueWords`). */
    fun dueLabel(iso: String?): String {
        val days = daysUntil(iso)
        return when (days) {
            0 -> L("Due today")
            -1 -> L("Due yesterday")
            1 -> L("Due tomorrow")
            else -> if (days < 0) LF("%d days overdue", -days) else LF("Due in %d days", days)
        }
    }

    /** Late, within the week (today and the six days after), or later: red, mango, light blue. */
    fun urgency(iso: String?): ProviderState {
        val days = daysUntil(iso)
        if (days < 0) return ProviderState.problem
        return if (days <= 6) ProviderState.waiting else ProviderState.upcoming
    }

    // MARK: Records

    /** Draft (only the team sees it), signed, corrected after it was signed. */
    fun statusState(record: RecordsRecord): ProviderState = if (record.isDraft) ProviderState.waiting else ProviderState.done

    /** "Perro · Schnauzer": what tells two pets of one name apart. */
    fun petKind(species: String?, breed: String?): String {
        val raw = species?.trim() ?: ""
        val names = mapOf("dog" to "Dog", "cat" to "Cat", "bird" to "Bird", "rabbit" to "Rabbit", "fish" to "Fish", "reptile" to "Reptile", "small_pet" to "Small pet", "other" to "Other")
        val name = names[raw.lowercase()]?.let { L(it) } ?: raw
        return listOf(name, breed.nonEmpty ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** "Rosa · 25 sep 2026, 10:30" — who did something and when. */
    fun actorLine(name: String?, at: String?): String {
        val who = name.nonEmpty ?: L("someone on the team")
        if (at == null || DateText.parse(at) == null) return who
        return LF("%@ · %@", who, dateTime(at))
    }

    fun clinicalStateLabel(state: String): String = when (state) {
        "active" -> L("Active@state")
        "monitoring" -> L("Monitoring")
        "resolved" -> L("Resolved")
        "completed" -> L("Completed@state")
        "stopped" -> L("Stopped")
        "inactive" -> L("Inactive@state")
        else -> state
    }

    // MARK: Follow-ups

    fun followUpTypeLabel(type: String?): String = when (type) {
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

    fun followUpStatusLabel(status: String?): String = when (status) {
        "open" -> L("Open@status")
        "contacted" -> L("Reminder sent")
        "scheduled" -> L("Visit booked")
        "done" -> L("Done")
        "snoozed" -> L("Snoozed")
        "canceled" -> L("Canceled")
        else -> (status ?: "").replace("_", " ")
    }

    /** Where a follow-up stands, in the six states. */
    fun followUpState(status: String?): ProviderState = when (status) {
        "open" -> ProviderState.waiting
        "contacted" -> ProviderState.active
        "scheduled", "snoozed" -> ProviderState.upcoming
        "done" -> ProviderState.done
        else -> ProviderState.closed
    }

    /** The team's own work (a note to write, a bill to chase): nothing in it is for the family. */
    fun isInternalFollowUp(type: String?): Boolean = type == "documentation_needed" || type == "billing_follow_up"

    val resolutionReasons: Map<String, String> = mapOf(
        "linked_record_added" to "Linked record added",
        "client_left_before_exam" to "Client left before exam",
        "duplicate_task" to "Duplicate task",
        "service_did_not_require_clinical_documentation" to "Service did not require clinical documentation",
        "created_in_error" to "Created in error",
        "follow_up_date_cleared" to "Follow-up date cleared",
        "other" to "Other",
    )

    /** How a closed follow-up was closed: "Cancelado: Pendiente duplicado". */
    fun resolutionLabel(followUp: RecordsFollowUp): String? {
        val type = followUp.resolutionType.nonEmpty ?: return null
        val typeText = when (type) {
            "completed" -> L("Completed@state")
            "completed_with_record" -> L("Completed with record")
            "resolved_without_record" -> L("Resolved without record")
            "canceled" -> L("Canceled")
            else -> type.replace("_", " ")
        }
        val reason = followUp.resolutionReason.nonEmpty ?: return typeText
        val reasonText = resolutionReasons[reason]?.let { L(it) } ?: reason.replace("_", " ")
        return LF("%@: %@", typeText, reasonText)
    }

    private class SystemTitle(val patterns: List<String>, val en: (String) -> String, val es: (String) -> String)

    /** The titles the system gives a follow-up, in English and in Spanish (backend lib/follow-up-words.ts). */
    private val systemTitles: List<SystemTitle> = listOf(
        SystemTitle(listOf("^Documentation needed: (.+)$", "^Falta documentar: (.+)$"), { "Documentation needed: $it" }, { "Falta documentar: $it" }),
        SystemTitle(listOf("^Report to finish: (.+)$", "^Reporte por terminar: (.+)$"), { "Report to finish: $it" }, { "Reporte por terminar: $it" }),
        SystemTitle(listOf("^Follow up: (.+)$", "^Seguimiento: (.+)$"), { "Follow up: $it" }, { "Seguimiento: $it" }),
        SystemTitle(listOf("^(.+) due$", "^Toca: (.+)$"), { "$it due" }, { "Toca: $it" }),
        SystemTitle(listOf("^(Post-hospitalization recheck)$", "^(Revisión tras la hospitalización)$"), { "Post-hospitalization recheck" }, { "Revisión tras la hospitalización" }),
    )

    private fun firstMatch(pattern: String, text: String): String? {
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val match = regex.find(text) ?: return null
        val group = match.groups[1] ?: return null
        return group.value.trim()
    }

    /** A follow-up's title in the reader's language ("Rabies booster due" ↔ "Toca: Rabies booster"). */
    fun followUpTitle(title: String?): String {
        val text = (title ?: "").trim()
        for (system in systemTitles) {
            for (pattern in system.patterns) {
                val subject = firstMatch(pattern, text)
                if (subject != null) return if (isSpanish) system.es(subject) else system.en(subject)
            }
        }
        return text
    }

    private val reportGaps: Map<String, String> = mapOf(
        "services" to "servicios", "servicios" to "services", "style" to "corte", "corte" to "style",
        "behaviour" to "comportamiento", "comportamiento" to "behaviour", "skills" to "habilidades", "habilidades" to "skills",
        "progress" to "avance", "avance" to "progress", "pee" to "pipí", "pipí" to "pee", "ate" to "comida", "comida" to "ate",
        "water" to "agua", "agua" to "water",
    )

    /** The note the system writes under a task it makes, in the reader's language; a typed note as typed. */
    fun followUpNote(note: String?): String? {
        val text = note.nonEmpty ?: return null
        val pattern = "^(?:Fill in the visit's report|Completa el reporte de la visita) \\((.+?)\\)(?:: the family reads it)?\\.$"
        val gapsText = firstMatch(pattern, text)
        if (gapsText != null) {
            val english = text.startsWith("Fill")
            val gaps = gapsText.split(", ").joinToString(", ") { gap -> if (english == !isSpanish) gap else (reportGaps[gap] ?: gap) }
            return if (isSpanish) "Completa el reporte de la visita ($gaps)." else "Fill in the visit's report ($gaps)."
        }
        val added = listOf("Add the medical record for this completed visit.", "Add the linked medical record for this completed visit.", "Agrega el registro médico de esta visita terminada.")
        if (added.contains(text)) {
            return if (isSpanish) "Agrega el registro médico de esta visita terminada." else "Add the medical record for this completed visit."
        }
        return text
    }

    // MARK: Visits

    fun visitStatusLabel(status: String?): String = when (status) {
        "requested" -> L("To confirm")
        "scheduled" -> L("Booked@visit")
        "checked_in" -> L("Waiting")
        "in_progress" -> L("In the visit")
        "completed" -> L("Finished@visit")
        "cancelled" -> L("Cancelled@visit")
        "no_show" -> L("Didn’t come")
        else -> (status ?: "").replace("_", " ")
    }

    // MARK: The pet

    fun sexLabel(sex: String?): String? = when (sex) {
        "male" -> L("Male")
        "female" -> L("Female")
        null -> null
        else -> sex.nonEmpty
    }

    fun ageLabel(birthDate: String?): String? {
        val born = DateText.parse(birthDate) ?: return null
        val zone = Workspace.shared.timeZone
        val months = maxOf(0L, ChronoUnit.MONTHS.between(born.atZone(zone), Instant.now().atZone(zone))).toInt()
        if (months < 12) return if (months == 1) L("1 month") else LF("%d months", months)
        val years = months / 12
        return if (years == 1) L("1 year") else LF("%d years", years)
    }

    /** "Perro · Schnauzer · Hembra · 3 años · 6.4 kg". */
    fun petProfile(record: RecordsRecord): String {
        val parts = mutableListOf<String>()
        val species = petKind(species = record.petSpecies, breed = null)
        if (species.isNotEmpty()) parts.add(species)
        record.petBreed.nonEmpty?.let { parts.add(it) }
        sexLabel(record.petSex)?.let { parts.add(it) }
        ageLabel(record.petBirthDate)?.let { parts.add(it) }
        val grams = record.petWeight
        if (grams != null && grams > 0) parts.add(String.format(Locale.US, "%.1f kg", grams / 1000))
        return parts.joinToString(" · ")
    }
}
