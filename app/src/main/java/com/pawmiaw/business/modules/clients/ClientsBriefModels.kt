package com.pawmiaw.business.clients

import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

//
//  ClientsBriefModels.kt (iOS ClientsBriefModels.swift)
//
//  The Olfato AI brief of a pet's chart (web: hooks/use-patient-summary.ts,
//  patient-chart/chart-ai-summary.tsx, docs/patient-summaries.md): its
//  shapes, the states the server can be in and the words for them. The
//  brief is plain JSON, asked for again while it is being written.
//

@Serializable
data class ClientsBriefItem(
    val text: String,
    val sourceIds: List<String>? = null,
    val unverified: Boolean? = null,
)

@Serializable
data class ClientsBriefSection(
    val key: String,
    val items: List<ClientsBriefItem>? = null,
)

@Serializable
data class ClientsBriefText(
    val language: String? = null,
    val sections: List<ClientsBriefSection>? = null,
) {
    fun items(key: String): List<ClientsBriefItem> = sections?.firstOrNull { it.key == key }?.items ?: emptyList()
}

@Serializable
data class ClientsBriefPending(
    val kind: String? = null,
    val title: String? = null,
    val dueDay: String? = null,
    val source: String? = null,
    val recordId: String? = null,
    val followUpId: String? = null,
)

@Serializable
data class ClientsBriefWarning(
    val id: String,
    val kind: String? = null,
    val origin: String? = null,
    val label: String? = null,
    val detail: String? = null,
    val sourceId: String? = null,
    val covered: Boolean? = null,
)

@Serializable
data class ClientsBriefWeightFact(
    val kg: Double? = null,
    val day: String? = null,
    val sourceId: String? = null,
)

@Serializable
data class ClientsBriefWeights(
    val latest: ClientsBriefWeightFact? = null,
    val previous: ClientsBriefWeightFact? = null,
    val changeKg: Double? = null,
    val outlier: String? = null,
)

@Serializable
data class ClientsBriefMedication(
    val id: String,
    val name: String? = null,
    val dose: String? = null,
    val route: String? = null,
    val everyHours: Double? = null,
    val durationDays: Double? = null,
    val said: String? = null,
    val origin: String? = null,
    val day: String? = null,
    val endsOn: String? = null,
    val sourceId: String? = null,
    val covered: Boolean? = null,
    /** A treatment: "given" or "course". */
    val event: String? = null,
)

@Serializable
data class ClientsBriefCareFact(
    val id: String,
    val key: String? = null,
    val value: String? = null,
    val weeks: Int? = null,
    val sourceId: String? = null,
    val covered: Boolean? = null,
)

@Serializable
data class ClientsBriefConflict(
    val id: String,
    val kind: String? = null,
    val sourceIds: List<String>? = null,
    val values: Map<String, String>? = null,
)

@Serializable
data class ClientsBriefRead(
    val from: String? = null,
    val to: String? = null,
    val counts: Map<String, Int>? = null,
    val excluded: Excluded? = null,
) {
    @Serializable
    data class Documents(val unreadable: Int? = null, val partial: Int? = null, val processing: Int? = null)

    @Serializable
    data class Excluded(
        val omitted: Int? = null,
        val documents: Documents? = null,
        val visitsInProgress: Int? = null,
        val photos: Int? = null,
        val family: String? = null,
        val familySince: String? = null,
        val restricted: Boolean? = null,
    )
}

@Serializable
data class ClientsBriefRating(
    val rating: String? = null,
    val reasons: List<String>? = null,
    val comment: String? = null,
)

@Serializable
data class ClientsBriefSource(
    val id: String,
    val kind: String? = null,
    val title: String? = null,
    val at: String? = null,
    val date: String? = null,
    val data: JSONValue? = null,
    val attachmentId: String? = null,
    val page: Int? = null,
)

@Serializable
data class ClientsBriefDiagnostics(
    val errorCode: String? = null,
    val version: String? = null,
    val attempts: Int? = null,
    val runId: String? = null,
    val model: String? = null,
    val at: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

@Serializable
data class ClientsBriefCoverage(
    val records: Int? = null,
    val included: Int? = null,
    val truncated: Boolean? = null,
    val documents: Int? = null,
    val ready: Int? = null,
    val pending: Int? = null,
    val failed: Int? = null,
)

@Serializable
data class ClientsBriefDocument(
    val id: String,
    val status: String? = null,
    val title: String? = null,
)

@Serializable
data class ClientsBriefRecent(
    val since: String? = null,
    val basis: String? = null,
)

@Serializable
data class ClientsBrief(
    val status: String,
    val language: String? = null,
    val sourceRevision: String? = null,
    val inputRevision: String? = null,
    val generatedAt: String? = null,
    val coveredThrough: String? = null,
    val summary: ClientsBriefText? = null,
    val nothingReason: String? = null,
    val warnings: List<ClientsBriefWarning>? = null,
    val weights: ClientsBriefWeights? = null,
    val conflicts: List<ClientsBriefConflict>? = null,
    val currentMedications: List<ClientsBriefMedication>? = null,
    val recentTreatment: List<ClientsBriefMedication>? = null,
    val careFacts: List<ClientsBriefCareFact>? = null,
    val recent: ClientsBriefRecent? = null,
    val partial: Boolean? = null,
    val diagnostics: ClientsBriefDiagnostics? = null,
    val pending: List<ClientsBriefPending>? = null,
    val pendingMore: Int? = null,
    val today: String? = null,
    val read: ClientsBriefRead? = null,
    val rating: ClientsBriefRating? = null,
    val canEnable: Boolean? = null,
    val sources: List<ClientsBriefSource>? = null,
    val coverage: ClientsBriefCoverage? = null,
    val documents: List<ClientsBriefDocument>? = null,
    val errorCode: String? = null,
    val retryAt: String? = null,
    val delayed: Boolean? = null,
) {
    /** What a rating is about: the summary on screen. */
    val revision: String? get() = inputRevision ?: sourceRevision
}

@Serializable
data class ClientsBriefSettings(
    val noticeOutdated: Boolean? = null,
)

// MARK: - Words

object ClientsBriefWords {
    val tabs = listOf("problems", "medications", "labs", "weight", "milestones", "care")

    fun tab(key: String): String = when (key) {
        "problems" -> L("Problems")
        "medications" -> L("Medications")
        "labs" -> L("Labs")
        "weight" -> L("Weight")
        "milestones" -> L("Milestones")
        else -> L("Care & handling")
    }

    fun pendingKind(kind: String?): String = when (kind) {
        "vaccine" -> L("Vaccine")
        "reminder" -> L("Reminder")
        else -> L("Follow-up")
    }

    /** What `pendingDue` says: the words, and whether the day is past or today (Swift's tuple). */
    data class PendingDue(val text: String, val late: Boolean, val today: Boolean)

    /** "Vence en 3 días", "Vencido hace 2 días": against the business's today. */
    fun pendingDue(day: String?, today: String?): PendingDue {
        val due = day?.let { dayDate(it) }
        val now = today?.let { dayDate(it) } ?: dayDate(DateText.dayKey())
        if (due == null || now == null) return PendingDue("—", false, false)
        val days = ChronoUnit.DAYS.between(now.atZone(ZoneOffset.UTC).toLocalDate(), due.atZone(ZoneOffset.UTC).toLocalDate()).toInt()
        if (days < 0) {
            val late = -days
            if (late == 1) return PendingDue(L("Overdue by 1 day"), true, false)
            if (late <= 60) return PendingDue(LF("Overdue by %d days", late), true, false)
            return PendingDue(LF("Overdue since %@", ClientsText.shortDay(DateText.iso(due))), true, false)
        }
        if (days == 0) return PendingDue(L("Due today@brief"), false, true)
        if (days == 1) return PendingDue(L("Due tomorrow@brief"), false, false)
        return PendingDue(LF("Due in %d days@brief", days), false, false)
    }

    /** A "yyyy-MM-dd" day (its first ten characters) as midnight UTC. */
    fun dayDate(day: String): Instant? =
        runCatching { LocalDate.parse(day.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

    /** "1 oct, 12:27", with the year only when it isn't this one. */
    fun stamp(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        val zone = Workspace.shared.timeZone
        val sameYear = date.atZone(zone).year == ZonedDateTime.now(zone).year
        return DateText.format(date, if (sameYear) "dMMMjmm" else "dMMMyyyyjmm", zone)
    }

    private val datesPattern = Regex("""\b(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}):(\d{2}))?\b""")

    /** ISO days written inside a bullet ("2026-09-27 19:30") read as "27 sep, 19:30". */
    fun datesInText(text: String): String = datesPattern.replace(text) { match ->
        val day = dayDate(match.groupValues[1]) ?: return@replace match.value
        val sameYear = day.atZone(ZoneOffset.UTC).year == LocalDate.now().year
        var shown = DateText.format(day, if (sameYear) "dMMM" else "dMMMyyyy", ZoneOffset.UTC)
        val hours = match.groups[2]?.value?.toIntOrNull()
        val minutes = match.groups[3]?.value?.toIntOrNull()
        if (hours != null && minutes != null) {
            // The time as written: built and read on the same (UTC) clock.
            val time = LocalDate.of(2000, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(hours.toLong()).plusMinutes(minutes.toLong()).toInstant()
            shown += ", ${DateText.format(time, "jmm", ZoneOffset.UTC)}"
        }
        shown
    }

    /** A number with 0 to `max` decimals (Swift `.number.precision(.fractionLength(0...max))`). */
    private fun decimals(value: Double, max: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 0; maximumFractionDigits = max }.format(value)

    fun kg(kg: Double?): String {
        if (kg == null) return "—"
        return "${decimals(kg, 2, appLocale)} kg"
    }

    fun change(kg: Double): String {
        val sign = if (kg > 0) "+" else if (kg < 0) "−" else ""
        return "$sign${decimals(kotlin.math.abs(kg), 2, appLocale)} kg"
    }

    fun warningPrefix(w: ClientsBriefWarning): String? {
        if (w.origin == "note") return L("Noted in a record")
        return when (w.kind) {
            "allergy" -> L("Allergy")
            "problem" -> L("Problem")
            else -> null
        }
    }

    fun warningText(w: ClientsBriefWarning): String {
        if (w.origin == "care_card") return SvcReportText.option(w.label ?: "")
        if (w.origin == "note") return "“${w.label ?: ""}”"
        val detail = w.detail.nonEmpty
        if (w.kind == "allergy" && detail != null) return "${w.label ?: ""} — $detail"
        return w.label ?: "—"
    }

    fun conflictText(c: ClientsBriefConflict): String {
        val v = c.values ?: emptyMap()
        val day = { value: String? -> value?.let { if (it.length >= 10) ClientsText.shortDay(it) else it } ?: "—" }
        return when (c.kind) {
            "neuter" -> LF("The profile says not neutered, and a record notes a spay or neuter: “%@”", v["recorded"] ?: "")
            "allergy_medication" -> {
                val family = v["match"] == "family"
                if (v["from"] == "note") {
                    if (family) LF("A record says “%1\$@”, and %2\$@, of the same family, was recorded after it.", v["allergen"] ?: "", v["medication"] ?: "")
                    else LF("A record says “%1\$@”, and %2\$@ was recorded after it.", v["allergen"] ?: "", v["medication"] ?: "")
                } else if (family) {
                    LF("An allergy to %1\$@ is on record, and %2\$@, of the same family, was recorded after it.", v["allergen"] ?: "", v["medication"] ?: "")
                } else {
                    LF("An allergy to %1\$@ is on record, and %2\$@ was recorded after it.", v["allergen"] ?: "", v["medication"] ?: "")
                }
            }
            "dose" -> LF("Two active records give %1\$@ at different doses: %2\$@ and %3\$@.", v["medication"] ?: "", v["first"] ?: "", v["second"] ?: "")
            "birth_date" -> LF("The profile says born %1\$@; a record says %2\$@.", day(v["profile"]), day(v["recorded"]))
            else -> v.values.joinToString(" · ")
        }
    }

    /** What `medicationLine` says (Swift's tuple). */
    data class MedicationLine(val title: String, val detail: String)

    /** "Amoxicilina 250 mg · oral · cada 12 h · por 7 días". */
    fun medicationLine(m: ClientsBriefMedication): MedicationLine {
        val title = listOf(m.name, m.dose).mapNotNull { it.nonEmpty }.joinToString(" ")
        val days = m.durationDays?.toInt()
        val detail = listOfNotNull(
            m.route.nonEmpty,
            m.everyHours?.let { LF("every %@ h", decimals(it, 1, Locale.getDefault())) },
            days?.let { if (it == 1) L("for 1 day") else LF("for %d days", it) },
        ).joinToString(" · ")
        return MedicationLine(title, detail)
    }

    fun medicationSource(m: ClientsBriefMedication): String {
        val date = m.day?.let { ClientsText.shortDay(it) }
        if (m.event == "given") return date?.let { LF("Dose given on %@", it) } ?: L("Dose given")
        if (m.event == "course") return date?.let { LF("Prescribed %@; the course has ended", it) } ?: L("A course that has ended")
        return when (m.origin) {
            "prescription" -> date?.let { LF("Prescribed %@", it) } ?: L("On a prescription")
            "note" -> date?.let { LF("Noted in a record on %@", it) } ?: L("Noted in a record")
            else -> date?.let { LF("Medication record of %@", it) } ?: L("Medication record")
        }
    }

    fun careLabel(key: String?): String = when (key) {
        "usual_cut" -> L("Usual cut")
        "blade_or_length" -> L("Blade or length")
        "rebook_every_weeks" -> L("Comes back")
        "products" -> L("Products")
        else -> L("Care notes")
    }

    fun failure(code: String?, time: String?): String = when (code) {
        "provider_configuration" -> L("Olfato AI can’t write summaries right now. Everything else in the chart works as usual. If this continues, contact support.")
        "summary_refused", "invalid_citation", "invalid_summary", "ungrounded_summary" ->
            time?.let { LF("We couldn’t write a summary we can stand behind for this chart, so none is shown. You can try again at %@.", it) }
                ?: L("We couldn’t write a summary we can stand behind for this chart, so none is shown. You can try again.")
        else ->
            time?.let { LF("We couldn’t write the summary this time. You can try again at %@.", it) } ?: L("We couldn’t write the summary this time. Try again in a few minutes.")
    }

    /** "1 oct to 3 nov · 4 visits · 12 records · care card". */
    fun readLine(read: ClientsBriefRead): String {
        val from = read.from
        val to = read.to
        val range = when {
            from != null && to != null && from.take(10) != to.take(10) -> LF("%1\$@ to %2\$@", ClientsText.shortDay(from), ClientsText.shortDay(to))
            from != null -> ClientsText.shortDay(from)
            to != null -> ClientsText.shortDay(to)
            else -> L("nothing dated")
        }
        val counts = read.counts ?: emptyMap()
        val parts = listOf(
            Triple("visits", "%d visit", "%d visits"), Triple("records", "%d record", "%d records"), Triple("prescriptions", "%d prescription", "%d prescriptions"),
            Triple("weights", "%d weight", "%d weights"), Triple("documents", "%d document", "%d documents"), Triple("followUps", "%d follow-up", "%d follow-ups"),
            Triple("hospital", "%d hospital entry", "%d hospital entries"), Triple("stays", "%d stay", "%d stays"),
        )
        val words = mutableListOf(range)
        for ((key, one, other) in parts) { val n = counts[key]; if (n != null && n > 0) words.add(LP(n, one, other)) }
        if ((counts["careCard"] ?: 0) > 0) words.add(L("care card"))
        return words.joinToString(" · ")
    }

    fun exclusions(read: ClientsBriefRead?): List<String> {
        val x = read?.excluded ?: return emptyList()
        val lines = mutableListOf<String>()
        x.omitted?.takeIf { it > 0 }?.let { n -> lines.add(if (n == 1) L("1 older entry was left out because this chart is longer than one summary can read. The History has everything.") else LF("%d older entries were left out because this chart is longer than one summary can read. The History has everything.", n)) }
        x.documents?.unreadable?.takeIf { it > 0 }?.let { n -> lines.add(if (n == 1) L("1 attached document couldn’t be read.") else LF("%d attached documents couldn’t be read.", n)) }
        x.documents?.partial?.takeIf { it > 0 }?.let { n -> lines.add(if (n == 1) L("1 attached document could only be read in part.") else LF("%d attached documents could only be read in part.", n)) }
        x.documents?.processing?.takeIf { it > 0 }?.let { n -> lines.add(if (n == 1) L("1 attached document is still being read.") else LF("%d attached documents are still being read.", n)) }
        x.visitsInProgress?.takeIf { it > 0 }?.let { n -> lines.add(if (n == 1) L("1 visit still under way isn’t included until it is finished.") else LF("%d visits still under way aren’t included until they are finished.", n)) }
        if ((x.photos ?: 0) > 0) lines.add(L("Photos attached to visit reports aren’t read."))
        when (x.family) {
            "not_shared" -> lines.add(L("The family’s own records aren’t shared with this business."))
            "partial" -> lines.add(x.familySince?.let { LF("The family shares its records from %@ on; earlier ones aren’t included.", ClientsText.shortDay(it)) } ?: L("The family shares only its newer records; earlier ones aren’t included."))
            else -> {}
        }
        if (x.restricted == true) lines.add(L("Some records aren’t visible to you, so they aren’t in this summary."))
        return lines
    }

    fun documentStatus(status: String?): String = when (status) {
        "ready" -> L("Read@document")
        "review" -> L("Partly readable")
        "pending", "processing" -> L("Processing")
        else -> L("Could not be read")
    }

    fun sourceKind(kind: String?): String? = when (kind) {
        "visit" -> L("Visit")
        "clinic_record" -> L("Record")
        "family_record" -> L("Family record")
        "prescription" -> L("Prescription")
        "weight" -> L("Weight")
        "family_weight" -> L("Weight at home")
        "care_card" -> L("Care card@brief")
        "follow_up" -> L("Follow-up")
        "document" -> L("Document")
        "hospitalization", "hospital_order", "administration", "hospital_note", "hospital_round" -> L("Hospital")
        "vitals" -> L("Vital signs")
        "stay" -> L("Stay")
        "pet" -> L("Pet")
        else -> null
    }

    fun sourceTitle(source: ClientsBriefSource): String = when (source.title) {
        "Care card" -> L("Care card@brief")
        "Prescription" -> L("Prescription")
        "Weight" -> L("Weight")
        "Weight at home" -> L("Weight at home")
        "Hospital update" -> L("Hospital update")
        "Hospital rounds" -> L("Hospital rounds")
        "Vital signs" -> L("Vital signs")
        "Boarding" -> L("Boarding")
        "Daycare" -> L("Daycare")
        "Home sitting" -> L("Home sitting")
        else -> source.title.nonEmpty ?: sourceKind(source.kind) ?: L("Source")
    }
}
