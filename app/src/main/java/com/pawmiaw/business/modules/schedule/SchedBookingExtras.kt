package com.pawmiaw.business.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.clients.ClientsAddress
import com.pawmiaw.business.clients.ClientsAddressSheet
import com.pawmiaw.business.clients.ClientsHome
import com.pawmiaw.business.hospital.HospVitalsDraft
import com.pawmiaw.business.hospital.HospVitalsError
import com.pawmiaw.business.hospital.HospVitalsFields
import com.pawmiaw.business.hospital.HospWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  SchedBookingExtras.kt (iOS SchedBookingExtras.swift)
//
//  The booking sheet's optional parts (web provider-appointment-sheet.tsx):
//  "Does this visit repeat?" (lib/visit-repeat.ts, booking-repeat-fields.tsx),
//  where a home visit happens (addresses/visit-address-picker.tsx), and the
//  emergency triage at arrival (hospital/triage-fields.tsx), which the
//  walk-in, the check-in and the visit's triage card share.
//

// MARK: - Repeating bookings

/** A repeating booking: every N weeks a number of times, or on chosen weekdays until a day. */
data class SchedRepeatDraft(
    var on: Boolean = false,
    /** A preset's id, or "custom" for "Other…". */
    var choice: String = "custom",
    /** "weeks" or "weekdays". */
    var mode: String = "weeks",
    var everyWeeks: Int = 1,
    var occurrences: Int = 8,
    /** ISO weekdays, 1 (Monday) … 7 (Sunday). */
    var weekdays: List<Int> = emptyList(),
    /** "yyyy-MM-dd", inclusive; null = four weeks after the first visit. */
    var until: String? = null,
)

object SchedRepeat {
    const val maxWeeks = 26
    const val maxVisits = 120

    data class Preset(val id: String, val label: String, val mode: String, val everyWeeks: Int, val occurrences: Int, val weekdays: List<Int> = emptyList())

    /** Only a walk, sitting, class or daycare repeats; anyone else says when it comes back on finishing. */
    fun repeats(category: String?): Boolean = listOf("walker", "sitter", "trainer", "daycare").contains(category ?: "")

    fun presets(category: String?): List<Preset> {
        val weekly = { n: Int -> Preset(id = "weekly", label = L("Every week"), mode = "weeks", everyWeeks = 1, occurrences = n) }
        val every = { weeks: Int, n: Int -> Preset(id = "every-$weeks", label = if (weeks == 2) L("Every 2 weeks") else L("Every 4 weeks"), mode = "weeks", everyWeeks = weeks, occurrences = n) }
        val weekdays = Preset(id = "weekdays", label = L("Monday to Friday"), mode = "weekdays", everyWeeks = 1, occurrences = 0, weekdays = listOf(1, 2, 3, 4, 5))
        return when (category) {
            "walker", "sitter", "daycare" -> listOf(weekdays, weekly(8), every(2, 6))
            "trainer" -> listOf(weekly(6), every(2, 6))
            else -> listOf(weekly(4), every(2, 4), every(4, 3))
        }
    }

    /** 1 Monday … 7 Sunday. */
    fun isoWeekday(day: String): Int = SchedDay.start(day).atZone(SchedDay.zone).dayOfWeek.value

    fun defaultUntil(first: String): String = SchedDay.addDays(first, 28)
    fun lastUntil(first: String): String = SchedDay.addDays(first, maxWeeks * 7)

    /** The chosen weekdays with the first visit's day always in, in week order. */
    fun weekdays(chosen: List<Int>, first: String): List<Int> =
        (chosen + isoWeekday(first)).toSet().filter { it in 1..7 }.sorted()

    fun untilProblem(first: String, until: String): Boolean = until < first || until > lastUntil(first)

    /** The days the repeat books, the first visit included (as the server counts them). */
    fun dates(draft: SchedRepeatDraft, first: String): List<String> {
        if (!draft.on) return emptyList()
        val every = maxOf(1, minOf(maxWeeks, draft.everyWeeks))
        if (draft.mode == "weeks") {
            val count = maxOf(2, minOf(52, draft.occurrences))
            return (0 until count).map { SchedDay.addDays(first, it * every * 7) }
        }
        val until = draft.until ?: defaultUntil(first)
        if (untilProblem(first, until)) return emptyList()
        val chosen = weekdays(draft.weekdays, first)
        val monday = SchedDay.addDays(first, 1 - isoWeekday(first))
        val days = mutableListOf<String>()
        var week = 0
        while (days.size < maxVisits) {
            val start = SchedDay.addDays(monday, week * 7)
            if (start > until) break
            for (weekday in chosen) {
                val day = SchedDay.addDays(start, weekday - 1)
                if (day < first || day > until) continue
                if (days.size < maxVisits) days.add(day)
            }
            week += every
        }
        return days
    }

    /** What `repeat` carries on the booking; null when it doesn't repeat. */
    fun body(draft: SchedRepeatDraft, first: String): Map<String, Any>? {
        if (!draft.on) return null
        val every = maxOf(1, minOf(maxWeeks, draft.everyWeeks))
        if (draft.mode == "weekdays") {
            return mapOf("everyWeeks" to every, "weekdays" to weekdays(draft.weekdays, first), "until" to (draft.until ?: defaultUntil(first)))
        }
        return mapOf("everyWeeks" to every, "occurrences" to maxOf(2, minOf(52, draft.occurrences)))
    }

    fun weekdayLabel(weekday: Int): String = when (weekday) {
        1 -> L("Mon")
        2 -> L("Tue")
        3 -> L("Wed")
        4 -> L("Thu")
        5 -> L("Fri")
        6 -> L("Sat")
        else -> L("Sun")
    }
}

/** "Does this visit repeat?": no, the service's usual rhythms, or "Other…" with every option. The dates it books are listed. */
@Composable
fun SchedRepeatFields(
    draft: MutableState<SchedRepeatDraft>,
    /** The first visit's day. */
    firstDay: String,
    category: String?,
    invalid: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shown = 8
    val value = draft.value
    val presets = SchedRepeat.presets(category)
    val choice = if (!value.on) "none" else if (presets.any { it.id == value.choice }) value.choice else "custom"
    val until = value.until ?: SchedRepeat.defaultUntil(firstDay)
    val dates = SchedRepeat.dates(value, first = firstDay)

    fun pick(preset: SchedRepeat.Preset) {
        draft.value = draft.value.copy(
            on = true,
            choice = preset.id,
            mode = preset.mode,
            everyWeeks = preset.everyWeeks,
            occurrences = if (preset.mode == "weeks") preset.occurrences else draft.value.occurrences,
            weekdays = preset.weekdays,
            until = if (preset.mode == "weekdays" && draft.value.until == null) SchedRepeat.defaultUntil(firstDay) else draft.value.until,
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowLayout(spacing = 8.dp) {
            FilterChip(title = L("Does not repeat"), isSelected = choice == "none") { draft.value = draft.value.copy(on = false) }
            presets.forEach { preset ->
                FilterChip(title = preset.label, isSelected = choice == preset.id) { pick(preset) }
            }
            FilterChip(title = L("Other…"), isSelected = choice == "custom") { draft.value = draft.value.copy(on = true, choice = "custom") }
        }
        if (value.on) {
            val shape = RoundedCornerShape(Theme.boxRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(if (invalid) 2.dp else 1.dp, if (invalid) Theme.coral else Theme.borderSoft, shape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (choice == "custom") {
                    Segmented(
                        options = listOf(
                            Segmented.Option("weeks", L("Every N weeks")),
                            Segmented.Option("weekdays", L("On these days")),
                        ),
                        selection = stateOf({ draft.value.mode }, { draft.value = draft.value.copy(mode = it) }),
                        look = Segmented.Look.raised,
                        label = L("Repeat"),
                    )
                    if (value.mode == "weekdays") SchedWeekdayChips(draft, firstDay)
                    SchedStepper(value = value.everyWeeks, range = 1..SchedRepeat.maxWeeks, onChange = { draft.value = draft.value.copy(everyWeeks = it) }) {
                        Text("${L("Every")} ${value.everyWeeks} ${L("week(s)")}", style = Theme.text(15, FontWeight.SemiBold), color = Theme.inkStrong)
                    }
                }
                if (value.mode == "weeks") {
                    SchedStepper(value = value.occurrences, range = 2..52, onChange = { draft.value = draft.value.copy(occurrences = it) }) {
                        Text(
                            if (choice == "custom") "${value.occurrences} ${L("visits in all")}" else "${L("How many times")}: ${value.occurrences}",
                            style = Theme.text(15, FontWeight.SemiBold),
                            color = Theme.inkStrong,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayField(
                            label = L("Until"),
                            day = stateOf({ until }, { draft.value = draft.value.copy(until = it) }),
                            min = firstDay,
                            max = SchedRepeat.lastUntil(firstDay),
                        )
                        if (SchedRepeat.untilProblem(firstDay, until)) {
                            Text(L("Choose an end date within 26 weeks of the first visit"), style = Theme.text(13, FontWeight.SemiBold), color = Theme.coralText)
                        } else if (value.mode == "weekdays") {
                            val days = SchedRepeat.weekdays(value.weekdays, first = firstDay).joinToString(", ") { SchedRepeat.weekdayLabel(it) }
                            Text(LF("%@ visits · %@ · until %@", dates.size.toString(), days, SchedDay.shortLabel(until)), style = Theme.captionFont, color = Theme.inkSoft)
                        }
                    }
                }
                if (dates.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowLayout(spacing = 6.dp) {
                            dates.take(shown).forEach { day ->
                                Text(
                                    SchedDay.shortLabel(day),
                                    style = Theme.text(12, FontWeight.SemiBold),
                                    color = Theme.inkStrong,
                                    modifier = Modifier
                                        .background(Theme.blueWash, RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            if (dates.size > shown) {
                                Text(LF("and %@ more", (dates.size - shown).toString()), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                            }
                        }
                        Text(LF("%@ visits are booked now. Days that are closed or already taken are skipped.", dates.size.toString()), style = Theme.captionFont, color = Theme.inkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedWeekdayChips(draft: MutableState<SchedRepeatDraft>, firstDay: String) {
    val chosen = SchedRepeat.weekdays(draft.value.weekdays, first = firstDay)
    val locked = SchedRepeat.isoWeekday(firstDay)
    FlowLayout(spacing = 6.dp) {
        for (weekday in 1..7) {
            FilterChip(title = SchedRepeat.weekdayLabel(weekday), isSelected = chosen.contains(weekday)) {
                if (weekday != locked) {
                    val next = if (chosen.contains(weekday)) chosen - weekday else chosen + weekday
                    draft.value = draft.value.copy(weekdays = SchedRepeat.weekdays(next, first = firstDay))
                }
            }
        }
    }
}

/** SwiftUI's `Stepper`: the label, then − and + in one rounded pill. */
@Composable
private fun SchedStepper(value: Int, range: IntRange, onChange: (Int) -> Unit, label: @Composable () -> Unit) {
    val pill = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { label() }
        Row(
            Modifier
                .height(32.dp)
                .background(Theme.surfaceMuted, pill)
                .border(1.dp, Theme.borderSoft, pill),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canLower = value > range.first
            val canRaise = value < range.last
            Box(
                Modifier
                    .width(47.dp)
                    .height(32.dp)
                    .alpha(if (canLower) 1f else 0.35f)
                    .plainButton(enabled = canLower, label = "−") { onChange(maxOf(range.first, value - 1)) },
                contentAlignment = Alignment.Center,
            ) { Glyph("minus", 16.dp, tint = Theme.inkStrong) }
            Box(Modifier.width(1.dp).height(18.dp).background(Theme.borderSoft))
            Box(
                Modifier
                    .width(47.dp)
                    .height(32.dp)
                    .alpha(if (canRaise) 1f else 0.35f)
                    .plainButton(enabled = canRaise, label = "+") { onChange(minOf(range.last, value + 1)) },
                contentAlignment = Alignment.Center,
            ) { Glyph("plus", 16.dp, tint = Theme.inkStrong) }
        }
    }
}

// MARK: - Where a home visit happens

/**
 * The client's addresses (the family's and the business's) as cards, the default first; a new
 * one added without leaving the booking; and the arrival notes that travel with the visit.
 */
@Composable
fun SchedAddressPicker(
    clientId: String,
    addressId: MutableState<String?>,
    accessNotes: MutableState<String>,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    var home by remember { mutableStateOf<ClientsHome?>(null) }
    var failed by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun sorted(from: ClientsHome?): List<ClientsAddress> =
        (from?.addresses ?: emptyList()).filter { it.status != "archived" }
            .sortedWith(compareBy<ClientsAddress> { if (it.isDefault == true) 0 else 1 }.thenBy { it.name })
    val addresses = sorted(home)
    val canEdit = workspace.can(Permission.clientsEdit)

    /** The default is chosen first; a choice that is no longer the client's falls back to it. A new one is chosen. */
    suspend fun load(selectNew: Boolean) {
        val before = sorted(home).map { it.id }.toSet()
        try {
            home = API.get<ClientsHome>("/provider-clients/$clientId/home")
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failed = true
            return
        }
        val now = sorted(home)
        if (selectNew) {
            val added = now.firstOrNull { !before.contains(it.id) }
            if (added != null) {
                addressId.value = added.id
                Toast.show(L("Address added."))
                return
            }
        }
        val chosen = addressId.value
        if (chosen != null && now.any { it.id == chosen }) return
        addressId.value = now.firstOrNull { it.isDefault == true }?.id ?: now.firstOrNull()?.id
    }

    LaunchedEffect(clientId) { load(selectNew = false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(L("Where"), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
            if (canEdit && addresses.isNotEmpty()) {
                Row(
                    Modifier.plainButton { adding = true },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("plus", 14.dp, tint = Theme.blue)
                    Text(L("New address"), style = Theme.text(13, FontWeight.Bold), color = Theme.blue)
                }
            }
        }
        if (failed && home == null) {
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("Couldn’t load the addresses."), actionTitle = L("Try again")) {
                scope.launch { load(selectNew = false) }
            }
        } else if (home == null) {
            SchedProgressView()
        } else if (addresses.isEmpty()) {
            NoticeCard(
                tone = NoticeCard.Tone.info,
                title = L("No address yet"),
                message = if (canEdit) L("A home visit needs the client’s address. Add it here.") else L("Ask someone who can edit clients to add the address."),
                actionTitle = if (canEdit) L("Add address") else null,
                action = if (canEdit) ({ adding = true }) else null,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                addresses.forEach { address -> SchedAddressOption(address, active = addressId.value == address.id) { addressId.value = address.id } }
            }
        }
        if (error != null) Text(error, style = Theme.text(13, FontWeight.SemiBold), color = Theme.coral)
        PMTextField(
            L("Arrival & access notes"),
            text = accessNotes,
            prompt = L("Ring the side door; the dog barks but is friendly."),
            hint = L("Travels with this visit. Codes and keys go in the address’s access details."),
            optional = true,
            multiline = true,
        )
    }

    if (adding) {
        AppSheet(onDismissRequest = { adding = false }) {
            ClientsAddressSheet(clientId = clientId, address = null, makeDefault = addresses.isEmpty()) { load(selectNew = true) }
        }
    }
}

@Composable
private fun SchedAddressOption(address: ClientsAddress, active: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) Theme.wash(Theme.aqua, 18.0) else Theme.surface, shape)
            .border(1.5.dp, if (active) Theme.aqua.copy(alpha = 0.7f) else Theme.borderSoft, shape)
            .plainButton(role = Role.RadioButton) { onPick() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph(if (active) "circle-dot" else "circle", 20.dp, tint = if (active) Theme.blue else Theme.inkSoft)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(address.label.nonEmpty ?: L("Address"), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                if (address.isDefault == true) Chip(text = L("Default"), tone = Chip.Tone.good, icon = "star")
                if (address.source == "family") Chip(text = L("From the family"), tone = Chip.Tone.online)
            }
            Text(address.formatted ?: address.line1 ?: "—", style = Theme.smallFont, color = Theme.inkStrong)
            val reference = address.reference.nonEmpty
            if (reference != null) Text(reference, style = Theme.captionFont, color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (address.vault?.hasVault == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("key-round", 12.dp, tint = Theme.inkSoft)
                    Text(L("Access details written"), style = Theme.captionFont, color = Theme.inkSoft)
                }
            }
        }
    }
}

// MARK: - Emergency triage

/** The triage typed at arrival (a walk-in, a check-in) or on the visit. */
data class SchedTriageDraft(
    var on: Boolean = false,
    var priority: String? = null,
    var reason: String = "",
    var vitals: HospVitalsDraft = HospVitalsDraft(),
) {
    sealed class Missing {
        data object priority : Missing()
        data object reason : Missing()
        data class vital(val field: HospVitalsDraft.Field) : Missing()
    }

    /** What is sent (null when it isn't an emergency), or what is missing. The visit's reason stands in for a blank one. */
    fun parsed(fallbackReason: String = ""): Pair<Map<String, Any?>?, Missing?> {
        if (!on) return Pair(null, null)
        val priority = priority ?: return Pair(null, Missing.priority)
        val why = reason.nonEmpty ?: fallbackReason.nonEmpty ?: return Pair(null, Missing.reason)
        val result = vitals.parsed()
        val problem = result.exceptionOrNull()
        if (problem != null) {
            val field = (problem as? HospVitalsError)?.field ?: HospVitalsDraft.Field.temperatureC
            return Pair(null, Missing.vital(field))
        }
        return Pair(mapOf("priority" to priority, "reason" to why, "vitals" to result.getOrNull()), null)
    }

    companion object {
        fun missingText(missing: Missing): String = when (missing) {
            Missing.priority -> L("Choose the triage: critical, urgent or stable.")
            Missing.reason -> L("Say why the patient came in.")
            is Missing.vital -> LF("Check %@.", HospVitalsDraft.label(missing.field))
        }
    }
}

/** "Emergency" at arrival: the switch, then the priority, why they came and quick vitals. */
@Composable
fun SchedTriageFields(
    draft: MutableState<SchedTriageDraft>,
    /** Always an emergency (the triage sheet): no switch. */
    lockOn: Boolean = false,
    invalid: SchedTriageDraft.Missing? = null,
    modifier: Modifier = Modifier,
) {
    var showVitals by remember { mutableStateOf(false) }
    val shown = draft.value.on || lockOn
    val vitalInvalid = (invalid as? SchedTriageDraft.Missing.vital)?.field
    val shape = RoundedCornerShape(Theme.boxRadius)

    Column(
        modifier
            .fillMaxWidth()
            .background(if (shown) Theme.wash(Theme.pink, 14.0) else Color.Transparent, shape)
            .padding(if (shown) 12.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!lockOn) {
            ToggleRow(
                title = L("Emergency"),
                subtitle = L("Triage it: the waiting room goes by priority."),
                isOn = stateOf({ draft.value.on }, { draft.value = draft.value.copy(on = it) }),
            )
        }
        if (shown) {
            FormField(label = L("Triage")) {
                PillPicker(
                    options = HospWords.priorities.map { Pair(it, HospWords.triage(it)) },
                    choice = stateOf({ draft.value.priority }, { draft.value = draft.value.copy(priority = it, on = true) }),
                )
            }
            if (invalid == SchedTriageDraft.Missing.priority) {
                Text(SchedTriageDraft.missingText(SchedTriageDraft.Missing.priority), style = Theme.text(13, FontWeight.SemiBold), color = Theme.coral)
            }
            PMTextField(
                L("Why"),
                text = stateOf({ draft.value.reason }, { draft.value = draft.value.copy(reason = it, on = true) }),
                prompt = L("Why they came, e.g. Hit by a car, breathing fast"),
            )
            if (invalid == SchedTriageDraft.Missing.reason) {
                Text(SchedTriageDraft.missingText(SchedTriageDraft.Missing.reason), style = Theme.text(13, FontWeight.SemiBold), color = Theme.coral)
            }
            SchedDisclosure(
                title = L("Quick vitals"),
                expanded = showVitals || vitalInvalid != null,
                onToggle = { showVitals = it },
            ) {
                HospVitalsFields(
                    draft = stateOf({ draft.value.vitals }, { draft.value = draft.value.copy(vitals = it) }),
                    invalid = vitalInvalid,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** SwiftUI's `DisclosureGroup`: the label and a chevron that turns, the content under it when open. */
@Composable
private fun SchedDisclosure(title: String, expanded: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "disclosure")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton(label = title) { onToggle(!expanded) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
            Glyph("chevron-right", 14.dp, tint = Theme.inkStrong, modifier = Modifier.size(14.dp).rotate(turn))
        }
        AnimatedVisibility(visible = expanded) { content() }
    }
}
