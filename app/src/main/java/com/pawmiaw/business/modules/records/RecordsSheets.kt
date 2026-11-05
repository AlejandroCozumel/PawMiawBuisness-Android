package com.pawmiaw.business.records

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

//
//  RecordsSheets.kt (iOS RecordsSheets.swift)
//
//  The Records page's small sheets: signing from a list (sign-record-sheet),
//  closing a finished visit's paperwork without a note (paperwork-waive-sheet,
//  owners and admins), and the days filter (date-range-menu).
//

// MARK: - Sign

/**
 * Signing where the record hasn't been read: one look at what the family is about
 * to receive before it goes. The record's own page signs in one tap.
 */
@Composable
fun RecordsSignSheet(record: RecordsRecord, onSigned: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    fun sign() {
        working = true
        scope.launch {
            try {
                RecordsAPI.sign(record.id)
                Toast.show(if (record.onPawMiaw) L("Signed. The family can see it now.") else L("Signed. It is part of the chart now."))
                announceDataChanged()
                onSigned?.invoke()
                dismiss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not sign this record."))
            } finally {
                working = false
            }
        }
    }

    @Composable
    fun Bullet(icon: String, text: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Glyph(icon, 16.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp))
            Text(text, style = Theme.text(14), color = Theme.inkSoft)
        }
    }

    ProviderModal(
        title = L("Sign this record?"),
        subtitle = "${record.kind.label} · ${record.petText}",
        systemImage = "badge-check",
        accent = ModalAccent.aqua,
        primaryTitle = if (working) L("Signing…") else L("Sign"),
        isWorking = working,
        cancelTitle = L("Not yet"),
        primaryIcon = "badge-check",
        onPrimary = { sign() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ContextPills(client = record.clientText, linked = record.onPawMiaw, pets = listOf(record.petText))
        val box = RoundedCornerShape(Theme.boxRadius)
        Row(
            Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, box).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PetAvatar(name = record.petName, species = record.petSpecies, photoURL = record.petPhotoUrl, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    recordsTypeChip(record.kind)
                    Text(RecordsText.day(record.eventAt), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                }
                Text(record.titleText, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        ModalSection(title = if (record.onPawMiaw) L("What the family will see") else L("What goes into the chart")) {
            val summary = RecordsPayload.summaryLine(record)
            if (summary.isEmpty()) {
                Text(L("This record has no summary or notes yet."), style = Theme.text(14), color = Theme.inkSoft)
            } else {
                Text(
                    summary,
                    style = Theme.text(14),
                    color = Theme.inkStrong,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.aqua, 12.0), box)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Bullet(
                "eye",
                if (record.onPawMiaw) LF("%@ sees it in their PawMiaw app as soon as it is signed.", record.clientText)
                else LF("%@ isn’t on PawMiaw — it becomes part of the chart you can share.", record.clientText),
            )
            Bullet("lock", L("The staff-only note stays with your team."))
            Bullet("badge-check", L("Later changes are marked as a correction."))
        }
    }
}

// MARK: - Paperwork needs no note

/** The server takes only these. */
private val waiveReasons = listOf("client_left_before_exam", "service_did_not_require_clinical_documentation", "duplicate_task", "created_in_error", "other")

/**
 * A finished visit's paperwork closes itself when the note is written or signed, or the
 * report filled (docs/visit-paperwork.md). This is the one way out, for an owner or admin:
 * say why the visit needs none. The reason stays on it, in its history.
 */
@Composable
fun RecordsWaiveSheet(followUp: RecordsFollowUp, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val reason = remember { mutableStateOf("") }
    val note = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(reason.value) { form.clear("reason") }

    val reasonOptions: List<Pair<String, String>> = waiveReasons.map { it to L(RecordsText.resolutionReasons[it] ?: it) }

    fun submit() {
        if (!form.check(listOf(
                "reason" to (if (reason.value.isEmpty()) L("Choose why it needs no note.") else null),
                "note" to (if (reason.value == "other" && note.value.nonEmpty == null) L("Write why it needs no note.") else null),
            ))) return
        working = true
        scope.launch {
            try {
                RecordsAPI.updateFollowUp(followUp.id, mapOf(
                    "status" to "canceled",
                    "resolutionType" to "resolved_without_record",
                    "resolutionReason" to reason.value,
                    "outcomeNote" to note.value.nonEmpty,
                ))
                Toast.show(L("Closed without a note. The reason stays on record."))
                announceDataChanged()
                dismiss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("resolutionReason" to "reason", "outcomeNote" to "note"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("This visit needs no note?"),
        subtitle = "${followUp.petName.nonEmpty ?: "—"} · ${RecordsText.followUpTitle(followUp.title)}",
        systemImage = "file-x-2",
        accent = ModalAccent.aqua,
        isDirty = reason.value.isNotEmpty() || note.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Close without a note"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Back"),
        primaryIcon = "file-x-2",
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        Text(L("Only when there is truly nothing to write. Otherwise, write it on the visit: it closes on its own."), style = Theme.text(14), color = Theme.inkSoft)
        // The web's "Why" heading over round chips (ChoiceChips), then the box for "Other".
        ModalSection(title = L("Why")) {
            val why = L("Why")
            PillPicker(
                options = reasonOptions,
                selection = reason,
                modifier = Modifier.semantics { contentDescription = why }.formScrollTarget("reason", form),
            )
            if (reason.value == "other") {
                FieldChrome(invalid = form.error("note") != null, multiline = true, modifier = Modifier.formScrollTarget("note", form)) {
                    PlainTextField(note, prompt = L("What happened"), modifier = Modifier.weight(1f), lines = 3..8, onChange = { form.clear("note") })
                }
            }
            val problem = form.error("reason") ?: form.error("note")
            if (problem != null) {
                Text(problem, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
            }
        }
    }
}

// MARK: - Days

object RecordsRange {
    enum class Preset(val rawValue: String) {
        today("today"), week("week"), month("month");

        companion object {
            fun fromRaw(raw: String): Preset? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    fun name(preset: Preset): String = when (preset) {
        Preset.today -> L("Today")
        Preset.week -> L("This week")
        Preset.month -> L("This month")
    }

    /** Today, this week (Monday to Sunday) and this month, on the business's clock. (from, to) */
    fun days(preset: Preset): Pair<String, String> {
        val today = LocalDate.now(Workspace.shared.timeZone)
        return when (preset) {
            Preset.today -> today.toString() to today.toString()
            Preset.week -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                start.toString() to start.plusDays(6).toString()
            }
            Preset.month -> {
                val start = today.withDayOfMonth(1)
                start.toString() to start.plusMonths(1).minusDays(1).toString()
            }
        }
    }

    fun match(from: String?, to: String?): Preset? =
        Preset.entries.firstOrNull { val d = days(it); d.first == from && d.second == to }

    /** What a range is called: its quick choice, else its days ("1 sep – 15 sep"); null for none. */
    fun label(from: String?, to: String?): String? {
        if (from == null && to == null) return null
        match(from, to)?.let { return name(it) }
        if (from != null && to != null) return if (from == to) RecordsText.day(from) else "${RecordsText.day(from)} – ${RecordsText.day(to)}"
        if (from != null) return LF("From %@@day", RecordsText.day(from))
        return LF("Until %@@day", RecordsText.day(to))
    }
}

/** The days filter: today, this week, this month, any date, or a stretch chosen by hand. */
@Composable
fun RecordsRangeSheet(
    title: String,
    presets: List<RecordsRange.Preset>,
    from: String?,
    to: String?,
    modifier: Modifier = Modifier,
    onChange: (String?, String?) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val today = DateText.dayKey()
    // What the sheet opens on (iOS `.onAppear`).
    val initial = remember {
        val preset = RecordsRange.match(from, to)
        when {
            from == null && to == null -> Triple("all", today, today)
            preset != null && presets.contains(preset) -> Triple(preset.rawValue, today, today)
            else -> {
                val start = from ?: to ?: today
                Triple("custom", start, to ?: start)
            }
        }
    }
    val choice = remember { mutableStateOf(initial.first) }
    val start = remember { mutableStateOf(initial.second) }
    val end = remember { mutableStateOf(initial.third) }

    LaunchedEffect(start.value) { if (end.value < start.value) end.value = start.value }

    /** Days from the first to the last, both counted. */
    val length: Int = run {
        val first = runCatching { LocalDate.parse(start.value) }.getOrNull() ?: return@run 1
        val last = runCatching { LocalDate.parse(maxOf(start.value, end.value)) }.getOrNull() ?: return@run 1
        ChronoUnit.DAYS.between(first, last).toInt() + 1
    }

    val options: List<Triple<String, String, String?>> =
        presets.map { Triple(it.rawValue, RecordsRange.name(it), null) } +
            listOf(Triple("all", L("Any date"), null), Triple("custom", L("Choose the days"), null))

    fun apply() {
        when (choice.value) {
            "all" -> onChange(null, null)
            "custom" -> onChange(start.value, maxOf(start.value, end.value))
            else -> RecordsRange.Preset.fromRaw(choice.value)?.let { preset ->
                val days = RecordsRange.days(preset)
                onChange(days.first, days.second)
            }
        }
        dismiss()
    }

    ProviderModal(
        title = title,
        systemImage = "calendar-range",
        accent = ModalAccent.aqua,
        primaryTitle = L("Show"),
        onPrimary = { apply() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ChoiceCards(options = options, selection = choice)
        if (choice.value == "custom") {
            ModalSection(title = L("Or choose the days"), systemImage = "calendar-range") {
                DayField(label = L("From"), day = start)
                DayField(label = L("Until"), day = end, min = start.value)
                Text(if (length == 1) L("1 day") else LF("%d days", length), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            }
        }
    }
}
