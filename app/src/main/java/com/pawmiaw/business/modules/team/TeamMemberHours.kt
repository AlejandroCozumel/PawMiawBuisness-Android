package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

//
//  TeamMemberHours.kt (iOS TeamMemberHours.swift)
//
//  A person's hours at each location they work at — the location's opening
//  hours or a week of their own, always inside the location's — and their
//  days off (web: components/app/staff/member-hours-card.tsx). Adding days
//  off says first how many of their visits are booked then; nothing is
//  cancelled.
//

/** Their hours, one card per location they work at. */
@Composable
fun TeamMemberHoursCards(
    member: TeamMember,
    hours: TeamMemberHours?,
    error: String?,
    canEdit: Boolean,
    onSaved: (TeamMemberHours) -> Unit,
    modifier: Modifier = Modifier,
    retry: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val locations = hours?.locations ?: emptyList()
    if (locations.isEmpty()) {
        TeamPanel(title = L("Their hours"), subtitle = LF("When %@ takes visits.", member.shownName), systemImage = "clock-3", tint = Theme.aqua, modifier = modifier) {
            if (error != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error, style = Theme.text(14), color = Theme.inkSoft)
                    PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { retry() } }
                }
            } else if (hours == null) {
                TeamProgress(minHeight = 64.dp)
            } else {
                Text(LF("%@ doesn’t work at any open location.", member.shownName), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    } else {
        locations.forEach { entry ->
            androidx.compose.runtime.key(entry.locationId) {
                TeamLocationHoursCard(member = member, entry = entry, multi = locations.size > 1, canEdit = canEdit, onSaved = onSaved, modifier = modifier)
            }
        }
    }
}

@Composable
private fun TeamLocationHoursCard(
    member: TeamMember,
    entry: TeamMemberLocationHours,
    multi: Boolean,
    canEdit: Boolean,
    onSaved: (TeamMemberHours) -> Unit,
    modifier: Modifier,
) {
    var mode by remember { mutableStateOf<Boolean?>(null) } // true = the location's hours
    var draft by remember { mutableStateOf<List<TeamDay>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val flash = remember { TeamSavedFlash() }
    val scope = rememberCoroutineScope()

    val savedUsesLocation = entry.usesLocationHours ?: true
    val usesLocation = mode ?: savedUsesLocation
    val locationWeek = TeamHours.ordered(entry.locationWeeklySchedule ?: emptyList())
    val savedOwn = TeamHours.ordered(if (savedUsesLocation) (entry.locationWeeklySchedule ?: emptyList()) else (entry.weeklySchedule ?: emptyList()))
    val ownWeek = draft ?: savedOwn
    val dirty = usesLocation != savedUsesLocation || (!usesLocation && !TeamHours.sameWeek(ownWeek, entry.weeklySchedule ?: emptyList()))
    val valid = usesLocation || ownWeek.all { TeamHours.dayIsValid(it) }

    /** Days their own hours run outside the location's: they can't be booked online then. */
    val outside: List<String> = if (usesLocation) emptyList() else ownWeek.filter { day ->
        val ranges = TeamHours.ranges(day)
        if (ranges.isEmpty()) return@filter false
        val open = TeamHours.minuteRanges(TeamHours.ranges(locationWeek.firstOrNull { it.day == day.day }))
        val inside = TeamHours.intersect(TeamHours.minuteRanges(ranges), open).sumOf { it.second - it.first }
        TeamHours.minutes(ranges) > inside
    }.map { it.day }

    fun save() {
        saving = true
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>("locationId" to entry.locationId, "usesLocationHours" to usesLocation)
                if (!usesLocation) body["weeklySchedule"] = TeamHours.body(ownWeek)
                val updated: TeamMemberHours = API.put("/provider-hours/members/${member.id}", body)
                mode = null; draft = null
                onSaved(updated)
                flash.mark()
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                saving = false
            }
        }
    }

    val name = member.shownName
    TeamSaveSection(
        title = if (multi) LF("Their hours at %@", entry.name ?: "") else L("Their hours"),
        subtitle = if (usesLocation) LF("%@ works the location’s opening hours.", name) else LF("%@ works their own hours. They only count inside the location’s opening hours.", name),
        systemImage = "clock-3",
        tint = Theme.aqua,
        state = flash.state(saving = saving, dirty = dirty, valid = valid),
        saveLabel = L("Save hours"),
        invalidText = L("Some hours end before they start or overlap. Fix them to save."),
        readOnlyText = if (canEdit) null else L("Only someone who can edit staff roles changes this."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { mode = null; draft = null },
        modifier = modifier,
    ) {
        if (canEdit) {
            ChoiceCards(
                options = listOf(
                    Triple(true, L("Works the location’s hours"), L("Follows the opening hours, and any change to them.")),
                    Triple(false, L("Own hours"), L("A week of their own: part-time, a late shift, a split day.")),
                ),
                // `.disabled(saving)`: the cards don't change while it saves.
                selection = stateOf({ usesLocation }, { if (!saving) { flash.clear(); mode = it } }),
            )
        }
        if (!canEdit || usesLocation) {
            TeamWeekList(week = if (canEdit) locationWeek else (if (usesLocation) locationWeek else ownWeek))
        } else {
            Text(L("The light band is the location’s hours."), style = Theme.text(14), color = Theme.inkSoft)
            PMButton(L("Start from the location’s hours"), systemImage = "rotate-ccw", variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = !saving) {
                flash.clear(); draft = locationWeek
            }
            TeamWeekEditor(
                schedule = stateOf({ ownWeek }, { flash.clear(); draft = it }),
                guide = locationWeek,
                disabled = saving,
                closedHint = L("Not working: families can’t book them online this day."),
                openWord = L("Working"),
                closedWord = L("Day off"),
            )
            if (outside.isNotEmpty()) {
                NoticeCard(
                    tone = NoticeCard.Tone.warning,
                    message = LF("On %@ some of these hours fall outside the location’s. They can’t be booked online then.", outside.joinToString(", ") { TeamHours.dayShort(it) }),
                    icon = "triangle-alert",
                )
            }
        }
    }
}

/** A person's days off from today on, each removable, and a sheet to add more. */
@Composable
fun TeamMemberTimeOffCard(
    member: TeamMember,
    hours: TeamMemberHours?,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
    reload: suspend () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TeamTimeOff?>(null) }
    val zone = hours?.locations?.firstOrNull { it.isPrimary ?: false }?.timezone ?: hours?.locations?.firstOrNull()?.timezone
    val today = TeamDays.today(zone)
    val upcoming = TeamDays.sortSpans(hours?.timeOff ?: emptyList(), today = today, start = { it.startDay }, end = { it.endDay }).first

    TeamPanel(
        title = L("Days off"),
        subtitle = LF("Vacation, sick days, a course. Families can’t book %@ online those days, “whoever is free” skips them, and booking them asks your team to confirm.", member.shownName),
        systemImage = "calendar-minus",
        tint = Theme.pink,
        modifier = modifier,
        action = {
            if (canEdit) {
                PMButton(L("Add days off"), systemImage = "calendar-plus", size = ButtonSize.compact, fullWidth = true) { adding = true }
            }
        },
    ) {
        if (hours == null) {
            TeamProgress(minHeight = 64.dp)
        } else if (upcoming.isEmpty()) {
            EmptyState(systemImage = "calendar-minus", title = L("No days off coming up."), accent = Theme.pink)
        } else {
            upcoming.forEach { item ->
                val days = TeamDays.count(item.startDay, item.endDay)
                TeamDayTileRow(
                    startDay = item.startDay,
                    title = item.reason.nonEmpty ?: L("Day off"),
                    detail = TeamDays.rangeLabel(item.startDay, item.endDay, today = today) + (if (days > 1) " · " + LF("%d days", days) else ""),
                    tint = Theme.pink,
                    badge = if (item.startDay <= today) L("Off today") else null,
                    onRemove = if (canEdit) ({ removing = item }) else null,
                )
            }
        }
    }

    if (adding) {
        AppSheet(onDismissRequest = { adding = false }) {
            TeamTimeOffSheet(member = member, today = today) { reload() }
        }
    }
    removing?.let { item ->
        AppSheet(onDismissRequest = { removing = null }) {
            TeamConfirmSheet(
                title = L("Remove these days off?"),
                message = LF("%@ can be booked those days again.", member.shownName),
                confirmTitle = L("Remove"),
                cancelTitle = L("Keep them"),
                action = {
                    API.send("DELETE", "/provider-hours/time-off/${item.id}")
                    Toast.show(LF("Removed. %@ can be booked those days again.", member.shownName))
                    reload()
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
                    Text(item.reason.nonEmpty ?: L("Day off"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    Text(TeamDays.rangeLabel(item.startDay, item.endDay, today = today), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
    }
}

/** A date as a small calendar tile with its title and line (days off, closed dates). */
@Composable
fun TeamDayTileRow(
    startDay: String,
    title: String,
    detail: String,
    tint: Color = Theme.pink,
    badge: String? = null,
    extraBadge: String? = null,
    faded: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (month, day) = TeamDays.tile(startDay)
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (faded) 0.7f else 1f)
            .border(1.dp, Theme.borderSoft, box)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .width(48.dp)
                .background(Theme.wash(tint, 18.0), RoundedCornerShape(Theme.chipRadius))
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(month, style = brandText(9.9, FontWeight.ExtraBold).tracking(0.8), color = Theme.inkSoft)
            Text(day, style = Theme.heading(20), color = Theme.inkStrong)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowLayout(spacing = 8.dp) {
                Text(title, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (badge != null) Chip(text = badge, tone = Chip.Tone.custom(Theme.blue), percent = 14.0, ink = Theme.inkOnBright)
                if (extraBadge != null) {
                    Row(
                        Modifier
                            .background(Theme.wash(Theme.aqua, 22.0), CapsuleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("repeat", 12.dp, tint = Theme.inkStrong)
                        Text(extraBadge, style = brandText(11.2, FontWeight.Bold), color = Theme.inkStrong)
                    }
                }
            }
            Text(detail, style = Theme.text(14), color = Theme.inkSoft)
        }
        if (onEdit != null) IconCircleButton(systemImage = "pencil", label = LF("Change %@", title), bordered = false, action = onEdit)
        if (onRemove != null) IconCircleButton(systemImage = "trash-2", label = LF("Remove %@", title), bordered = false, action = onRemove)
    }
}

/** What is already booked on the days being closed or given off — said before saving. */
@Composable
fun TeamBookedNotice(
    conflicts: TeamHoursConflicts?,
    loading: Boolean,
    today: String,
    /** Days off: the person's name, to say "their". null for a location's closed date. */
    person: String? = null,
    /** Where "See them in the Schedule" opens: the person's list, or the first day booked. */
    scheduleFilter: ScheduleFilter? = null,
    modifier: Modifier = Modifier,
) {
    var showingSchedule by remember { mutableStateOf(false) }
    val data = conflicts
    if (loading) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
            Text(L("Checking what is booked those days…"), style = Theme.text(14), color = Theme.inkSoft)
        }
    } else if (data != null) {
        val visits = data.visits ?: 0
        val stays = data.stays ?: 0
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visits > 0 || stays > 0) {
                val visitsText = person?.let { name -> if (visits == 1) LF("%@ has 1 visit booked those days.", name) else LF("%1\$@ has %2\$d visits booked those days.", name, visits) }
                    ?: LP(visits, "1 visit is booked those days.", "%d visits are booked those days.")
                val staysText = if (stays > 0) LP(stays, "1 stay arrives or leaves then.", "%d stays arrive or leave then.") else ""
                val box = RoundedCornerShape(Theme.boxRadius)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.mango, 12.0), box)
                        .border(1.dp, Theme.mix(Theme.mango, 55.0, Theme.borderSoft), box)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        listOfNotNull(if (visits > 0) visitsText else null, staysText.nonEmpty).joinToString(" "),
                        style = Theme.text(14, FontWeight.SemiBold),
                        color = Theme.inkStrong,
                    )
                    Text(
                        if (person != null) L("Saving doesn’t cancel or move them: they stay with this person until you give them to someone else.") else L("Saving doesn’t cancel or move them: they stay booked until you move them or tell the family."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                    if (visits > 0 && scheduleFilter != null) {
                        // Over this sheet, like the web's new tab: what is being typed here stays.
                        Row(
                            Modifier.plainButton { showingSchedule = true },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("calendar-days", 16.dp, tint = Theme.blue)
                            Text(if (visits == 1) L("See it in the Schedule") else L("See them in the Schedule"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("circle-check", 16.dp, tint = ProviderState.done.color)
                    Text(person?.let { LF("%@ has nothing booked those days.", it) } ?: L("Nothing is booked those days."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
            val covered = data.covered
            if (!covered.isNullOrEmpty()) {
                val strong = Theme.inkStrong
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(if (person != null) L("Already off:") else L("Already on the list:")) }
                        append(" " + covered.joinToString("; ") { listOfNotNull(it.label.nonEmpty, TeamDays.rangeLabel(it.startDay, it.endDay, today = today)).joinToString(" · ") })
                    },
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
        }
    }

    if (showingSchedule && scheduleFilter != null) {
        AppSheet(onDismissRequest = { showingSchedule = false }) {
            // iOS: a NavigationStack with the Schedule and its routes; its root's bar says "Close".
            Box(Modifier.sheetPresentation()) {
                val navigator = remember { Navigator() }
                NavStack(navigator = navigator, root = { RouteView(ProviderRoute.schedule(scheduleFilter)) }, page = { RouteView(it) })
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

/** Days off for a person: a day or a stretch, why (optional), and what is booked then. */
@Composable
private fun TeamTimeOffSheet(
    member: TeamMember,
    today: String,
    onSaved: suspend () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val scope = rememberCoroutineScope()
    var firstDay by remember { mutableStateOf("") }
    var lastDay by remember { mutableStateOf("") }
    val oneDay = remember { mutableStateOf(true) }
    val reason = remember { mutableStateOf("") }
    var conflicts by remember { mutableStateOf<TeamHoursConflicts?>(null) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val startDay = firstDay.ifEmpty { today }
    val endDay = if (oneDay.value) startDay else lastDay.ifEmpty { startDay }
    val spanError: String? = when {
        endDay < startDay -> L("The last day comes before the first.")
        TeamDays.count(startDay, endDay) > 366 -> L("Choose a year at most.")
        else -> null
    }
    val alreadyOff = (conflicts?.covered ?: emptyList()).any { it.startDay <= startDay && endDay <= it.endDay }

    LaunchedEffect(startDay, endDay) {
        if (spanError != null) {
            conflicts = null
            return@LaunchedEffect
        }
        checking = true
        try {
            delay(250)
            conflicts = runCatching {
                API.get<TeamHoursConflicts>("/provider-hours/conflicts", query = mapOf("startDay" to startDay, "endDay" to endDay, "memberId" to member.id))
            }.getOrNull()
        } finally {
            checking = false
        }
    }
    OnChange(firstDay) { value -> if (lastDay.isNotEmpty() && lastDay < value) lastDay = value }

    fun save() {
        if (!form.check(listOf("dates" to (spanError ?: (if (alreadyOff) L("They are already off those days.") else null))))) return
        saving = true
        scope.launch {
            try {
                val result: TeamTimeOffSaved = API.post("/provider-hours/members/${member.id}/time-off", mapOf("startDay" to startDay, "endDay" to endDay, "reason" to reason.value.nonEmpty))
                val visits = result.visits ?: 0
                if (visits > 0) {
                    Toast.show(LP(visits, "Saved. They have 1 visit booked those days — reassign it from the Schedule.", "Saved. They have %d visits booked those days — reassign them from the Schedule."))
                } else {
                    Toast.show(LF("Days off saved for %@.", member.shownName))
                }
                onSaved()
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("startDay" to "dates", "endDay" to "dates"))
            } finally {
                saving = false
            }
        }
    }

    val name = member.shownName
    ProviderModal(
        title = LF("Days off for %@", name),
        subtitle = L("They won’t be offered online those days."),
        systemImage = "calendar-minus",
        accent = ModalAccent.pink,
        isDirty = true,
        form = form,
        primaryTitle = L("Add days off"),
        isWorking = saving,
        primaryIcon = "calendar-plus",
        onPrimary = { save() },
    ) {
        ModalSection(title = L("Dates"), subtitle = L("Whole days.")) {
            ToggleRow(title = L("1 day"), isOn = oneDay)
            DayField(label = L("First day"), day = stateOf({ startDay }, { firstDay = it; form.clear("dates") }), min = today)
            if (!oneDay.value) {
                DayField(label = L("Last day"), day = stateOf({ endDay }, { lastDay = it; form.clear("dates") }), min = startDay)
                Text(LF("%d days", maxOf(1, TeamDays.count(startDay, endDay))), style = Theme.text(14), color = Theme.inkSoft)
            }
            Column(Modifier.fillMaxWidth().formScrollTarget("dates", form)) {
                form.error("dates")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
            }
            if (spanError == null) {
                TeamBookedNotice(conflicts = conflicts, loading = checking, today = today, person = name, scheduleFilter = ScheduleFilter(memberId = member.id, view = "list"))
            }
        }
        // The web's "Reason (optional)" part: the field under it, its label read aloud only.
        ModalSection(title = L("Reason") + " " + L("(optional)")) {
            val label = L("Reason")
            FieldChrome {
                PlainTextField(reason, L("e.g. Vacation"), Modifier.weight(1f).semantics { contentDescription = label })
            }
            FlowLayout(spacing = 6.dp) {
                listOf("Vacation", "Sick", "Course", "Personal").forEach { key ->
                    val text = L(key)
                    FilterChip(title = text, isSelected = reason.value.trim() == text, solid = true) {
                        reason.value = if (reason.value.trim() == text) "" else text
                    }
                }
            }
            Text(L("The team sees the reason when booking; families never do."), style = Theme.text(14), color = Theme.inkSoft)
        }
    }
}
