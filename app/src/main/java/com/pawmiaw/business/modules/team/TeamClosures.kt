package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

//
//  TeamClosures.kt (iOS TeamClosures.swift)
//
//  A location's closed dates and special hours, once or every year (web:
//  components/app/availability/closures-card.tsx). A closed date has no
//  online slots and booking it asks the team to confirm; special hours
//  replace the week on those dates. Before saving, the sheet says what is
//  already booked then — nothing is cancelled.
//

object TeamClosureMath {
    private fun isReal(key: String): Boolean {
        val date = TeamDays.date(key) ?: return false
        return TeamDays.key(utc = date) == key
    }

    /** The days a closure next covers from `fromDay`: its own, or a yearly one's next time (startDay, endDay). */
    fun occurrence(closure: TeamClosure, from: String): Pair<String, String> {
        if (!(closure.repeatsYearly ?: false) || closure.endDay >= from) return closure.startDay to closure.endDay
        val length = TeamDays.count(closure.startDay, closure.endDay) - 1
        val firstYear = (from.take(4).toIntOrNull() ?: 2026) - 1
        for (year in firstYear..(firstYear + 9)) {
            val start = String.format(Locale.US, "%04d", year) + closure.startDay.drop(4)
            if (!isReal(start) || start < closure.startDay) continue
            val end = TeamDays.add(start, length)
            if (end >= from) return start to end
        }
        return closure.startDay to closure.endDay
    }

    fun covers(closure: TeamClosure, date: String): Boolean {
        if (!(closure.repeatsYearly ?: false)) return closure.startDay <= date && date <= closure.endDay
        val monthDay = date.drop(5)
        val from = closure.startDay.drop(5)
        val to = closure.endDay.drop(5)
        val inside = if (from <= to) (from <= monthDay && monthDay <= to) else (monthDay >= from || monthDay <= to)
        return date >= closure.startDay && inside
    }

    /** The closure covering a date, as the server picks it: a closed one wins, then the latest listed. */
    fun on(date: String, closures: List<TeamClosure>): TeamClosure? {
        val covering = closures.filter { covers(it, date) }
        return covering.firstOrNull { it.ranges == null } ?: covering.lastOrNull()
    }

    /** "Closed all day" or "Special hours 10:00–14:00". */
    fun hoursText(ranges: List<TeamTimeRange>?): String {
        if (ranges != null && ranges.isNotEmpty()) return LF("Special hours %@", TeamHours.rangesLabel(ranges))
        return L("Closed all day")
    }
}

/** A closed date as it shows: the days it next covers. */
private data class TeamShownClosure(
    val closure: TeamClosure,
    val startDay: String,
    val endDay: String,
) {
    val id: String get() = closure.id
}

@Composable
fun TeamClosuresCard(
    locationId: String?,
    timeZone: String?,
    weeklySchedule: List<TeamDay>,
    canEdit: Boolean,
    otherLocations: List<ProviderLocation>,
    closures: List<TeamClosure>,
    loaded: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    reload: suspend () -> Unit,
) {
    var showPast by remember { mutableStateOf(false) }
    var past by remember { mutableStateOf<List<TeamClosure>?>(null) }
    var editing by remember { mutableStateOf<TeamClosure?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TeamShownClosure?>(null) }
    val scope = rememberCoroutineScope()
    val today = TeamDays.today(timeZone)

    fun shown(list: List<TeamClosure>): List<TeamShownClosure> = list.map { closure ->
        val next = TeamClosureMath.occurrence(closure, from = today)
        TeamShownClosure(closure = closure, startDay = next.first, endDay = next.second)
    }

    suspend fun loadPast() {
        val answer = runCatching {
            API.get<TeamClosuresResponse>("/provider-hours/closures", query = mapOf("locationId" to locationId, "includePast" to "true"))
        }.getOrNull()
        past = answer?.closures ?: emptyList()
    }

    val current = TeamDays.sortSpans(shown(closures), today = today, start = { it.startDay }, end = { it.endDay }).first
    val pastShown = TeamDays.sortSpans(shown((past ?: emptyList()).filter { !(it.repeatsYearly ?: false) }), today = today, start = { it.startDay }, end = { it.endDay }).second

    @Composable
    fun ClosureRow(item: TeamShownClosure, editable: Boolean) {
        val dates = TeamDays.rangeLabel(item.startDay, item.endDay, today = today)
        val days = TeamDays.count(item.startDay, item.endDay)
        val special = !(item.closure.ranges ?: emptyList()).isEmpty()
        val now = item.startDay <= today && today <= item.endDay
        val isPast = item.endDay < today
        val detail = listOfNotNull(
            if (item.closure.label.nonEmpty == null) null else dates,
            if (days > 1) LF("%d days", days) else null,
            TeamClosureMath.hoursText(item.closure.ranges),
        ).joinToString(" · ")
        TeamDayTileRow(
            startDay = item.startDay,
            title = item.closure.label.nonEmpty ?: dates,
            detail = detail,
            tint = if (special) Theme.mango else Theme.pink,
            badge = if (now) L("Today") else null,
            extraBadge = if (item.closure.repeatsYearly ?: false) L("Every year") else null,
            faded = isPast,
            onEdit = if (canEdit && editable) ({ editing = item.closure }) else null,
            onRemove = if (canEdit) ({ removing = item }) else null,
        )
    }

    TeamPanel(
        title = L("Closed dates"),
        subtitle = L("Holidays and days you close, or keep special hours. Families can’t book a closed date online, and booking it asks your team to confirm."),
        systemImage = "calendar-off",
        tint = Theme.pink,
        modifier = modifier,
        action = {
            if (canEdit) {
                PMButton(L("Add a closed date"), systemImage = "calendar-plus", size = ButtonSize.compact, fullWidth = true) { adding = true }
            }
        },
    ) {
        if (!loaded) {
            TeamProgress(minHeight = 64.dp)
        } else if (error != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error, style = Theme.text(14), color = Theme.inkSoft)
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { reload() } }
            }
        } else {
            if (current.isEmpty()) {
                EmptyState(systemImage = "calendar-off", title = L("No closed dates coming up. Add holidays before families book them."))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.forEach { item -> androidx.compose.runtime.key(item.id) { ClosureRow(item, editable = true) } }
                }
                Text(LP(current.size, "1 date coming up", "%d dates coming up"), style = Theme.text(14), color = Theme.inkSoft)
            }
            if (showPast) {
                Text(L("Past").uppercase(appLocale), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.5), color = Theme.inkSoft)
                val pastList = past
                if (pastList == null) {
                    TeamProgress(minHeight = 48.dp)
                } else if (pastShown.isEmpty()) {
                    Text(L("Nothing earlier."), style = Theme.text(14), color = Theme.inkSoft)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pastShown.forEach { item -> androidx.compose.runtime.key(item.id) { ClosureRow(item, editable = false) } }
                    }
                }
            }
            Row(
                Modifier.plainButton {
                    showPast = !showPast
                    if (showPast && past == null) scope.launch { loadPast() }
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("history", 14.dp, tint = Theme.blue)
                Text(if (showPast) L("Hide past dates") else L("Show past dates"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            }
        }
    }

    if (adding) {
        AppSheet(onDismissRequest = { adding = false }) {
            TeamClosureSheet(closure = null, locationId = locationId, today = today, weeklySchedule = weeklySchedule, otherLocations = otherLocations) { reload(); past = null }
        }
    }
    editing?.let { closure ->
        AppSheet(onDismissRequest = { editing = null }) {
            androidx.compose.runtime.key(closure.id) {
                TeamClosureSheet(closure = closure, locationId = locationId, today = today, weeklySchedule = weeklySchedule, otherLocations = otherLocations) { reload(); past = null }
            }
        }
    }
    removing?.let { item ->
        AppSheet(onDismissRequest = { removing = null }) {
            val label = item.closure.label.nonEmpty ?: TeamDays.rangeLabel(item.startDay, item.endDay, today = today)
            TeamConfirmSheet(
                title = L("Remove this date?"),
                message = if (item.closure.repeatsYearly ?: false) L("It stops coming back every year, and those dates keep the usual week.") else L("Those dates go back to the usual week."),
                confirmTitle = L("Remove"),
                cancelTitle = L("Keep it"),
                action = {
                    API.send("DELETE", "/provider-hours/closures/${item.closure.id}")
                    Toast.show(L("Removed. Those dates keep the usual week."))
                    reload()
                    if (past != null) loadPast()
                    announceDataChanged()
                },
            ) {
                val box = RoundedCornerShape(Theme.boxRadius)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Theme.borderSoft, box)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(label, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    val detail = listOfNotNull(
                        if (item.closure.label.nonEmpty == null) null else TeamDays.rangeLabel(item.startDay, item.endDay, today = today),
                        TeamClosureMath.hoursText(item.closure.ranges),
                        if (item.closure.repeatsYearly ?: false) L("Every year") else null,
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
    }
}

/**
 * Add or change a closed date: the days, every year or once, its name,
 * closed or special hours, and the other locations to close too.
 */
@Composable
private fun TeamClosureSheet(
    closure: TeamClosure?,
    locationId: String?,
    today: String,
    weeklySchedule: List<TeamDay>,
    otherLocations: List<ProviderLocation>,
    onSaved: suspend () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val scope = rememberCoroutineScope()
    // iOS `.onAppear { start() }`: an edited date starts from what it has saved.
    var firstDay by remember { mutableStateOf(closure?.startDay ?: "") }
    var lastDay by remember { mutableStateOf(closure?.endDay ?: "") }
    val oneDay = remember { mutableStateOf(closure?.let { it.startDay == it.endDay } ?: true) }
    val label = remember { mutableStateOf(closure?.label ?: "") }
    val yearly = remember { mutableStateOf(closure?.repeatsYearly ?: false) }
    var special by remember { mutableStateOf(!(closure?.ranges ?: emptyList()).isEmpty()) }
    val ranges = remember { mutableStateOf(closure?.ranges ?: emptyList()) }
    var alsoAt by remember { mutableStateOf<List<String>>(emptyList()) }
    var conflicts by remember { mutableStateOf<TeamHoursConflicts?>(null) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val startDay = firstDay.ifEmpty { today }
    val endDay = if (oneDay.value) startDay else lastDay.ifEmpty { startDay }
    val days = TeamDays.count(startDay, endDay)
    val spanError: String? = when {
        endDay < startDay -> L("The last day comes before the first.")
        yearly.value && days > 31 -> L("A date that repeats every year can last a month at most.")
        days > 366 -> L("Choose a year at most.")
        else -> null
    }

    // iOS `.task(id: "\(startDay)|\(endDay)") { await check() }`.
    val currentSpanError by rememberUpdatedState(spanError)
    LaunchedEffect(startDay, endDay) {
        if (currentSpanError != null) {
            conflicts = null
            return@LaunchedEffect
        }
        checking = true
        try {
            delay(250)
            conflicts = runCatching {
                API.get<TeamHoursConflicts>(
                    "/provider-hours/conflicts",
                    query = mapOf("startDay" to startDay, "endDay" to endDay, "locationId" to locationId, "ignoreId" to closure?.id),
                )
            }.getOrNull()
        } finally {
            checking = false
        }
    }
    OnChange(firstDay) { value -> if (lastDay.isNotEmpty() && lastDay < value) lastDay = value }

    fun chooseSpecial(next: Boolean) {
        special = next
        if (next && ranges.value.isEmpty()) {
            // Start from that weekday's hours, when it has some.
            val weekday = TeamHours.ranges(weeklySchedule.firstOrNull { it.day == TeamDays.weekday(startDay) })
            ranges.value = if (weekday.isEmpty()) listOf(TeamTimeRange(start = "10:00", end = "14:00")) else weekday
        }
    }

    fun save() {
        val hoursProblem = if (special) TeamHours.problem(ranges.value)?.let { TeamHours.problemText(it) } else null
        if (!form.check(listOf("dates" to spanError, "ranges" to hoursProblem))) return
        val values = mutableMapOf<String, Any?>(
            "startDay" to startDay,
            "endDay" to endDay,
            "label" to label.value.nonEmpty,
            "ranges" to (if (special) TeamHours.sorted(ranges.value).map { mapOf("start" to it.start, "end" to it.end) } else null),
            "repeatsYearly" to yearly.value,
        )
        saving = true
        scope.launch {
            try {
                val result: TeamClosureSaved = if (closure != null) {
                    API.patch("/provider-hours/closures/${closure.id}", values)
                } else {
                    if (locationId != null) values["locationId"] = locationId
                    API.post("/provider-hours/closures", values)
                }
                // The same days at the other locations chosen; one that fails is said, the rest stand.
                val failed = ArrayList<String>()
                for (other in alsoAt) {
                    val copy = values.toMutableMap()
                    copy["locationId"] = other
                    try {
                        API.post<TeamClosureSaved>("/provider-hours/closures", copy)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.add(otherLocations.firstOrNull { it.id == other }?.name ?: other)
                    }
                }
                onSaved()
                announceDataChanged()
                val visits = result.visits ?: 0
                if (failed.isNotEmpty()) {
                    Toast.error(LF("Saved here, but not at %@. Add it there from that location’s Availability.", failed.joinToString(", ")))
                } else if (visits > 0) {
                    Toast.show(LP(visits, "Saved. 1 visit is still booked on those days — move it from the Schedule.", "Saved. %d visits are still booked on those days — move them from the Schedule."))
                } else {
                    Toast.show(if ((result.closure.ranges ?: emptyList()).isEmpty()) L("Closed date saved.") else L("Special hours saved."))
                }
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("startDay" to "dates", "endDay" to "dates", "ranges" to "ranges"))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = if (closure == null) L("Add a closed date") else L("Change this date"),
        subtitle = L("A holiday, a day you close, or a day with special hours."),
        systemImage = "calendar-off",
        accent = ModalAccent.pink,
        isDirty = closure == null,
        form = form,
        primaryTitle = if (closure == null) L("Add date") else L("Save changes"),
        isWorking = saving,
        primaryIcon = "calendar-off",
        onPrimary = { save() },
    ) {
        ModalSection(title = L("Dates")) {
            ToggleRow(title = L("1 day"), isOn = oneDay)
            DayField(label = L("First day"), day = stateOf({ startDay }, { firstDay = it; form.clear("dates") }), min = if (closure == null) today else null)
            if (!oneDay.value) {
                DayField(label = L("Last day"), day = stateOf({ endDay }, { lastDay = it; form.clear("dates") }), min = startDay)
                Text(LF("%d days", maxOf(1, days)), style = Theme.text(14), color = Theme.inkSoft)
            }
            Column(Modifier.fillMaxWidth().formScrollTarget("dates", form)) {
                form.error("dates")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
            }
            ToggleRow(
                title = L("Every year"),
                subtitle = if (days > 31) L("Only for a month at most.") else L("It comes back on the same days each year: Christmas, New Year. Not for holidays that change date."),
                isOn = yearly,
            )
            if (spanError == null) {
                TeamBookedNotice(conflicts = conflicts, loading = checking, today = today, scheduleFilter = ScheduleFilter(day = conflicts?.firstDay, view = "day"))
            }
        }
        // The web's "Name (optional)" part: the field under it, its label read aloud only.
        ModalSection(title = L("Name") + " " + L("(optional)")) {
            val name = L("Name")
            FieldChrome {
                PlainTextField(label, L("e.g. Christmas"), Modifier.weight(1f).semantics { contentDescription = name })
            }
        }
        ModalSection(title = L("Hours those days")) {
            ChoiceCards(
                options = listOf(
                    Triple(false, L("Closed all day"), L("No online booking; booking a visit asks your team to confirm.")),
                    Triple(true, L("Special hours"), L("These hours replace the usual week on those dates.")),
                ),
                selection = stateOf({ special }, { chooseSpecial(it) }),
            )
            if (special) {
                val box = RoundedCornerShape(Theme.boxRadius)
                TeamRangesEditor(
                    ranges = ranges,
                    modifier = Modifier
                        .border(1.dp, Theme.borderSoft, box)
                        .padding(12.dp),
                )
                Box(Modifier.formScrollTarget("ranges", form))
            }
        }
        if (closure == null && otherLocations.isNotEmpty()) {
            ModalSection(title = L("Also at"), subtitle = L("The same days and hours at your other locations. Each keeps its own copy to change later.")) {
                otherLocations.forEach { location ->
                    val on = alsoAt.contains(location.id)
                    val box = RoundedCornerShape(Theme.boxRadius)
                    // The web's checkbox rows: a 20 tick, the name, a 1 pt box.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainButton { alsoAt = if (on) alsoAt.filter { it != location.id } else alsoAt + location.id }
                            .background(Theme.surface, box)
                            .border(1.dp, Theme.borderSoft, box)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tick(on = on)
                        Text(location.name, Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** SwiftUI `.onChange(of: value)`: runs on each change after the first composition. */
@Composable
private fun <T> OnChange(value: T, action: (T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val previous = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (previous[0] != value) {
            previous[0] = value
            latest(value)
        }
    }
}
