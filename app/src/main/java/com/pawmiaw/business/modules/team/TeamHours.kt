package com.pawmiaw.business.team

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

//
//  TeamHours.kt (iOS TeamHours.swift)
//
//  Opening hours as the web reasons about them (lib/hours.ts,
//  components/app/availability/hours-model.ts): a day has any number of
//  ranges ("HH:MM", "23:59" = midnight); the week in a few words; the
//  labels for lengths, notice and how far ahead; and the week editor the
//  location's Availability and a person's own hours both use.
//  Swift's `(start:, end:)` minute tuples are `Pair(start, end)` here.
//

object TeamHours {
    val dayKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday")
    val weekend = listOf("saturday", "sunday")

    // MARK: Days

    fun ranges(day: TeamDay?): List<TeamTimeRange> {
        if (day == null || !day.enabled) return emptyList()
        val ranges = day.ranges
        if (ranges != null && ranges.isNotEmpty()) return ranges
        val start = day.startTime
        val end = day.endTime
        if (start != null && end != null) return listOf(TeamTimeRange(start = start, end = end))
        return emptyList()
    }

    fun day(key: String, ranges: List<TeamTimeRange>): TeamDay {
        val first = ranges.firstOrNull()
        val last = ranges.lastOrNull()
        if (first == null || last == null) {
            return TeamDay(day = key, enabled = false, startTime = null, endTime = null, ranges = emptyList())
        }
        return TeamDay(day = key, enabled = true, startTime = first.start, endTime = last.end, ranges = ranges)
    }

    /** The seven days in order, each with its ranges (a missing day is closed). */
    fun ordered(schedule: List<TeamDay>): List<TeamDay> =
        dayKeys.map { key -> day(key, ranges(schedule.firstOrNull { it.day == key })) }

    val nineToFive: List<TeamTimeRange> get() = listOf(TeamTimeRange(start = "09:00", end = "17:00"))

    val defaultWeek: List<TeamDay>
        get() = dayKeys.map { if (weekend.contains(it)) day(it, emptyList()) else day(it, nineToFive) }

    /** The week as the server takes it: ranges in order, the span filled in. */
    fun forSave(schedule: List<TeamDay>): List<TeamDay> =
        ordered(schedule).map { day(it.day, sorted(ranges(it))) }

    fun body(schedule: List<TeamDay>): List<Map<String, Any?>> =
        forSave(schedule).map { day ->
            mapOf(
                "day" to day.day,
                "enabled" to day.enabled,
                "ranges" to (day.ranges ?: emptyList()).map { mapOf("start" to it.start, "end" to it.end) },
                "startTime" to day.startTime,
                "endTime" to day.endTime,
            )
        }

    // MARK: Minutes

    fun isTime(value: String): Boolean {
        val parts = value.split(":")
        if (parts.size != 2 || parts[0].length != 2 || parts[1].length != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return h in 0..23 && m in 0..59
    }

    fun startMinutes(value: String): Int {
        val parts = value.split(":").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        return if (parts.size == 2) parts[0] * 60 + parts[1] else 0
    }

    fun endMinutes(value: String): Int = if (value == "23:59") 1440 else startMinutes(value)

    fun time(minutes: Int): String {
        if (minutes >= 1440) return "23:59"
        val m = maxOf(0, minutes)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    fun minuteRanges(ranges: List<TeamTimeRange>): List<Pair<Int, Int>> =
        ranges.filter { isTime(it.start) && isTime(it.end) }.map { startMinutes(it.start) to endMinutes(it.end) }

    fun sorted(ranges: List<TeamTimeRange>): List<TeamTimeRange> = ranges.sortedBy { it.start }

    fun minutes(ranges: List<TeamTimeRange>): Int =
        minuteRanges(ranges).fold(0) { sum, range -> sum + maxOf(0, range.second - range.first) }

    fun intersect(a: List<Pair<Int, Int>>, b: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (left in a) for (right in b) {
            val start = maxOf(left.first, right.first)
            val end = minOf(left.second, right.second)
            if (end > start) out.add(start to end)
        }
        return out.sortedBy { it.first }
    }

    enum class Problem { empty, invalidTime, endBeforeStart, overlap }

    fun problem(ranges: List<TeamTimeRange>): Problem? {
        val list = sorted(ranges)
        if (list.isEmpty()) return Problem.empty
        var previousEnd = -1
        for (range in list) {
            if (!isTime(range.start) || !isTime(range.end)) return Problem.invalidTime
            val start = startMinutes(range.start)
            val end = endMinutes(range.end)
            if (end <= start) return Problem.endBeforeStart
            if (start < previousEnd) return Problem.overlap
            previousEnd = end
        }
        return null
    }

    fun problemText(problem: Problem): String = when (problem) {
        Problem.empty -> L("Add hours, or close the day.")
        Problem.invalidTime -> L("Pick each time from the list.")
        Problem.endBeforeStart -> L("A range ends before it starts. Pick a later end.")
        Problem.overlap -> L("Two ranges overlap. Move one so they don’t.")
    }

    /** Which rows of a day are wrong. */
    fun flags(ranges: List<TeamTimeRange>): List<Boolean> =
        ranges.mapIndexed { index, range ->
            if (!isTime(range.start) || !isTime(range.end)) return@mapIndexed true
            val start = startMinutes(range.start)
            val end = endMinutes(range.end)
            if (end <= start) return@mapIndexed true
            ranges.withIndex().any { (other, o) ->
                if (other == index || !isTime(o.start) || !isTime(o.end)) return@any false
                val os = startMinutes(o.start)
                val oe = endMinutes(o.end)
                oe > os && start < oe && os < end
            }
        }

    fun dayIsValid(day: TeamDay): Boolean = !day.enabled || problem(ranges(day)) == null

    fun dayMinutes(day: TeamDay): Int = if (day.enabled && dayIsValid(day)) minutes(ranges(day)) else 0

    fun sameRanges(a: List<TeamTimeRange>, b: List<TeamTimeRange>): Boolean = sorted(a) == sorted(b)

    fun sameWeek(a: List<TeamDay>, b: List<TeamDay>): Boolean =
        ordered(a).zip(ordered(b)).all { (x, y) -> sameRanges(ranges(x), ranges(y)) }

    /** (open days, hours a week to one decimal). */
    fun stats(schedule: List<TeamDay>): Pair<Int, Double> {
        val week = ordered(schedule)
        val minutes = week.fold(0) { sum, day -> sum + dayMinutes(day) }
        return week.count { it.enabled } to (minutes.toDouble() / 60 * 10).roundToInt() / 10.0
    }

    /** The next range to add to a day: after the last, keeping a gap for a break. */
    fun next(ranges: List<TeamTimeRange>): TeamTimeRange? {
        val list = minuteRanges(sorted(ranges))
        val last = list.lastOrNull()
        val first = list.firstOrNull()
        if (last == null || first == null) return TeamTimeRange(start = "09:00", end = "17:00")
        val start = last.second + 120
        if (start + 60 <= 1440) return TeamTimeRange(start = time(start), end = time(minOf(start + 240, 1440)))
        if (first.first >= 120) return TeamTimeRange(start = time(maxOf(0, first.first - 180)), end = time(first.first - 60))
        return null
    }

    /** A break in the widest range: "9–20" becomes "9–14" and "16–20". */
    fun addBreak(ranges: List<TeamTimeRange>): List<TeamTimeRange>? {
        val list = minuteRanges(sorted(ranges))
        val widest = list.maxByOrNull { it.second - it.first } ?: return null
        if (widest.second - widest.first < 180) return null
        val middle = ((widest.first + widest.second).toDouble() / 2 / 15).roundToInt() * 15
        val out = ArrayList<Pair<Int, Int>>()
        var split = false
        for (range in list) {
            if (!split && range.first == widest.first && range.second == widest.second) {
                out.add(range.first to middle - 60)
                out.add(middle + 60 to range.second)
                split = true
            } else {
                out.add(range)
            }
        }
        return out.map { TeamTimeRange(start = time(it.first), end = time(it.second)) }
    }

    // MARK: Words

    /** Monday 5 January 2026, noon UTC: the week the day names are read from. */
    private val reference: Instant = LocalDateTime.of(2026, 1, 5, 12, 0).toInstant(ZoneOffset.UTC)

    private fun weekdayText(key: String, template: String): String {
        val index = dayKeys.indexOf(key).coerceAtLeast(0)
        val text = DateText.format(reference.plusSeconds(index * 86_400L), template, ZoneOffset.UTC).replace(".", "")
        return text.take(1).uppercase(appLocale) + text.drop(1)
    }

    fun dayName(key: String): String = weekdayText(key, "EEEE")
    fun dayShort(key: String): String = weekdayText(key, "EEE")

    /** "9:00 a. m." */
    fun clock(value: String): String {
        if (!isTime(value)) return value
        val m = startMinutes(value)
        val date = LocalDateTime.of(2000, 1, 1, m / 60, m % 60).toInstant(ZoneOffset.UTC)
        return DateText.format(date, "jmm", ZoneOffset.UTC)
    }

    fun endClock(value: String): String = if (value == "23:59") L("Midnight") else clock(value)

    /** "9:00 a. m.–2:00 p. m., 4:00 p. m.–8:00 p. m." */
    fun rangesLabel(ranges: List<TeamTimeRange>): String =
        sorted(ranges).joinToString(", ") { "${clock(it.start)}–${endClock(it.end)}" }

    /** Runs of days with the same hours: "Lun–Vie 9:00–17:00 · Sáb–Dom cerrado" as (days, hours, open). */
    fun summary(schedule: List<TeamDay>): List<Triple<String, String, Boolean>> {
        val week = ordered(schedule)
        fun key(day: TeamDay): String = if (day.enabled) sorted(ranges(day)).joinToString(",") { "${it.start}-${it.end}" } else "closed"
        data class Run(val from: String, var to: String, val day: TeamDay)
        val runs = ArrayList<Run>()
        for (day in week) {
            val last = runs.lastOrNull()
            if (last != null && key(last.day) == key(day)) last.to = day.day
            else runs.add(Run(day.day, day.day, day))
        }
        return runs.map { run ->
            val days = if (run.from == run.to) dayShort(run.from) else "${dayShort(run.from)}–${dayShort(run.to)}"
            Triple(days, if (run.day.enabled) rangesLabel(ranges(run.day)) else L("closed"), run.day.enabled)
        }
    }

    fun duration(minutes: Int): String {
        if (minutes == 0) return L("None")
        if (minutes < 60) return LF("%d min", minutes)
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) return LP(hours, "1 hour", "%d hours")
        return LF("%1\$d h %2\$d min", hours, rest)
    }

    fun notice(minutes: Int): String {
        if (minutes == 0) return L("None")
        if (minutes % 1440 == 0) return LP(minutes / 1440, "1 day", "%d days")
        return duration(minutes)
    }

    fun horizon(days: Int?): String {
        if (days == null) return L("No limit")
        if (days % 365 == 0) return LP(days / 365, "1 year", "%d years")
        if (days % 30 == 0) return LP(days / 30, "1 month", "%d months")
        if (days % 7 == 0) return LP(days / 7, "1 week", "%d weeks")
        return LP(days, "1 day", "%d days")
    }

    val durationOptions = listOf(15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180)
    val bufferOptions = listOf(0, 15, 30, 45, 60)
    val noticeOptions = listOf(0, 30, 60, 120, 180, 240, 720, 1440, 2880)
    val horizonOptions = listOf(7, 14, 30, 60, 90, 180, 365)
    val lateCancelOptions = listOf(2, 4, 12, 24, 48, 72)

    /** The options, with a saved value that isn't one of them kept in order. */
    fun withSaved(options: List<Int>, value: Int): List<Int> =
        if (options.contains(value)) options else (options + value).sorted()

    /** Every quarter hour of the day. */
    val startTimes: List<String> by lazy { (0 until 96).map { time(it * 15) } }
    /** Every quarter hour after midnight, and "Midnight". */
    val endTimes: List<String> by lazy { (1 until 96).map { time(it * 15) } + "23:59" }

    // MARK: Time zones

    /** "Ciudad de México (UTC−6)": the web's `timeZoneLabel`, the same words as the time zone picker. */
    fun zoneLabel(identifier: String): String = com.pawmiaw.business.places.PlacesTimeZones.label(identifier)
}

// MARK: - The week editor

/**
 * A week of hours: each day open or closed, one or more ranges when open,
 * a bar over the day, and "Copy to…" to give other days the same hours.
 */
@Composable
fun TeamWeekEditor(
    schedule: MutableState<List<TeamDay>>,
    guide: List<TeamDay>? = null,
    disabled: Boolean = false,
    closedHint: String,
    openWord: String = L("Open"),
    closedWord: String = L("Closed"),
    modifier: Modifier = Modifier,
) {
    var copyFrom by remember { mutableStateOf<String?>(null) }
    var remembered by remember { mutableStateOf<Map<String, List<TeamTimeRange>>>(emptyMap()) }
    val week = TeamHours.ordered(schedule.value)
    val box = RoundedCornerShape(Theme.boxRadius)

    fun setRanges(key: String, ranges: List<TeamTimeRange>) {
        schedule.value = week.map { if (it.day == key) TeamHours.day(key, ranges) else it }
    }

    fun guideRanges(key: String): List<TeamTimeRange> =
        TeamHours.ranges(guide?.let { g -> TeamHours.ordered(g).firstOrNull { it.day == key } })

    fun toggle(day: TeamDay, open: Boolean) {
        if (!open) {
            remembered = remembered + (day.day to TeamHours.ranges(day))
            setRanges(day.day, emptyList())
            return
        }
        val fromGuide = guideRanges(day.day)
        val back = remembered[day.day] ?: emptyList()
        setRanges(day.day, if (back.isNotEmpty()) back else (if (fromGuide.isNotEmpty()) fromGuide else TeamHours.nineToFive))
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(box)
            .background(Theme.surface)
            .border(1.dp, Theme.borderSoft, box),
    ) {
        week.forEach { day ->
            androidx.compose.runtime.key(day.day) {
                if (day.day != week.firstOrNull()?.day) RowDivider()
                val valid = TeamHours.dayIsValid(day)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (day.enabled) androidx.compose.ui.graphics.Color.Transparent else Theme.wash(Theme.surfaceMuted, 45.0))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(TeamHours.dayName(day.day), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                            Text(
                                if (day.enabled) (if (valid) TeamHours.duration(TeamHours.dayMinutes(day)) else L("Check the times")) else closedWord,
                                style = Theme.text(12),
                                color = if (valid) Theme.inkSoft else Theme.destructive,
                            )
                        }
                        val openLabel = LF("%@ open", TeamHours.dayName(day.day))
                        PMSwitchStyle(
                            isOn = stateOf({ day.enabled }, { toggle(day, it) }),
                            enabled = !disabled,
                            modifier = Modifier.semantics { contentDescription = openLabel },
                        )
                        Text(
                            if (day.enabled) openWord else closedWord,
                            Modifier.widthIn(min = 56.dp),
                            style = Theme.text(14, FontWeight.SemiBold),
                            color = Theme.inkStrong,
                        )
                    }
                    if (day.enabled) {
                        TeamRangesEditor(
                            ranges = stateOf({ TeamHours.ranges(day) }, { setRanges(day.day, it) }),
                            guide = guideRanges(day.day),
                            disabled = disabled,
                        )
                    } else {
                        Text(closedHint, style = Theme.text(14), color = Theme.inkSoft)
                    }
                    if (!disabled) {
                        val open = copyFrom == day.day
                        val turn by animateFloatAsState(if (open) 180f else 0f, label = "copy")
                        Row(
                            Modifier
                                .height(36.dp)
                                .plainButton { copyFrom = if (open) null else day.day },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("copy", 14.dp, tint = Theme.blue)
                            Text(L("Copy to…"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                            Glyph("chevron-down", 14.dp, tint = Theme.blue, modifier = Modifier.rotate(turn))
                        }
                        AnimatedVisibility(visible = open) {
                            TeamCopyPanel(
                                from = day,
                                onApply = { targets ->
                                    val ranges = TeamHours.ranges(day)
                                    schedule.value = week.map { if (targets.contains(it.day)) TeamHours.day(it.day, ranges) else it }
                                    copyFrom = null
                                },
                                onCancel = { copyFrom = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamCopyPanel(
    from: TeamDay,
    onApply: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var targets by remember { mutableStateOf<Set<String>>(emptySet()) }
    val others = TeamHours.dayKeys.filter { it != from.day }
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            LF("Give these days %1\$@’s hours (%2\$@):", TeamHours.dayName(from.day), if (from.enabled) TeamHours.rangesLabel(TeamHours.ranges(from)) else L("Closed")),
            style = Theme.text(14, FontWeight.SemiBold),
            color = Theme.inkStrong,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(title = L("Weekdays"), isSelected = false) { targets = TeamHours.weekdays.filter { it != from.day }.toSet() }
            FilterChip(title = L("Every day"), isSelected = false) { targets = others.toSet() }
            FilterChip(title = L("None"), isSelected = false) { targets = emptySet() }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEach { day ->
                FilterChip(title = TeamHours.dayShort(day), isSelected = targets.contains(day)) {
                    targets = if (targets.contains(day)) targets - day else targets + day
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            PMButton(L("Cancel"), variant = ButtonVariant.outline, size = ButtonSize.compact, action = onCancel)
            PMButton(
                if (targets.isEmpty()) L("Copy") else LP(targets.size, "Copy to 1 day", "Copy to %d days"),
                size = ButtonSize.compact,
                enabled = targets.isNotEmpty(),
            ) { onApply(targets) }
        }
    }
}

/** One day's hours: a start–end pair per range, add a break or more hours, × to take one away. */
@Composable
fun TeamRangesEditor(
    ranges: MutableState<List<TeamTimeRange>>,
    guide: List<TeamTimeRange>? = null,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val list = ranges.value
    val flags = TeamHours.flags(list)
    val problem = TeamHours.problem(list)
    val split = TeamHours.addBreak(list)
    val more = if (list.size < 12) TeamHours.next(list) else null

    fun update(index: Int, start: String? = null, end: String? = null) {
        val current = ranges.value
        if (index !in current.indices) return
        var range = current[index]
        if (start != null) range = range.copy(start = start)
        if (end != null) range = range.copy(end = end)
        ranges.value = current.toMutableList().also { it[index] = range }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEachIndexed { index, range ->
            Disabled(disabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamTimeMenu(
                        value = stateOf({ range.start }, { update(index, start = it) }),
                        options = TeamHours.startTimes,
                        invalid = flags.getOrNull(index) ?: false,
                        modifier = Modifier.weight(1f, fill = false),
                    ) { TeamHours.clock(it) }
                    Text("–", style = Theme.text(14), color = Theme.inkSoft)
                    TeamTimeMenu(
                        value = stateOf({ range.end }, { update(index, end = it) }),
                        options = TeamHours.endTimes,
                        invalid = flags.getOrNull(index) ?: false,
                        modifier = Modifier.weight(1f, fill = false),
                    ) { TeamHours.endClock(it) }
                    if (list.size > 1 && !disabled) {
                        IconCircleButton(systemImage = "x", label = L("Remove these hours"), bordered = false) {
                            ranges.value = ranges.value.toMutableList().also { if (index in it.indices) it.removeAt(index) }
                        }
                    }
                }
            }
        }
        if (!disabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamRangesAction(L("Add a break"), "coffee", enabled = split != null) { split?.let { ranges.value = it } }
                TeamRangesAction(L("Add hours"), "plus", enabled = more != null) { more?.let { ranges.value = TeamHours.sorted(ranges.value + it) } }
            }
        }
        if (problem != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(TeamHours.problemText(problem), style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
                if (problem == TeamHours.Problem.endBeforeStart) {
                    Text(L("Open past midnight? End this day at Midnight and start the next one at 12:00 AM."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
        TeamRangesBar(ranges = list, guide = guide)
    }
}

/** "Add a break" / "Add hours": a blue 14 bold label with its icon, dimmed when it can't. */
@Composable
private fun TeamRangesAction(title: String, icon: String, enabled: Boolean, action: () -> Unit) {
    val isEnabled = enabled && LocalIsEnabled.current
    Row(
        Modifier
            .plainButton(enabled = enabled, onClick = action)
            .alpha(if (isEnabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 14.dp, tint = Theme.blue)
        Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
    }
}

/** A time picked from the day's quarter hours, drawn as a field. */
@Composable
fun TeamTimeMenu(
    value: MutableState<String>,
    options: List<String>,
    invalid: Boolean = false,
    modifier: Modifier = Modifier,
    label: (String) -> String,
) {
    val shape = RoundedCornerShape(Theme.fieldRadius)
    AppMenu(
        label = {
            // The web's NativeSelect: 16 corners, a grey border (coral when wrong), the chevron at the right.
            Row(
                Modifier
                    .widthIn(max = 160.dp)
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .background(Theme.inputFill, shape)
                    .border(1.dp, if (invalid) Theme.coral else Theme.borderSoft, shape)
                    .padding(start = 12.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FittedText(label(value.value), Modifier.weight(1f), style = Theme.text(14), color = Theme.inkStrong, minimumScaleFactor = 0.8f)
                Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
            }
        },
        modifier = modifier.widthIn(max = 160.dp),
    ) {
        picker(null, options = options.map { it to label(it) }, selection = value)
    }
}

/** Where the open hours sit in the day, 0 to 24, the guide drawn faintly under them. */
@Composable
fun TeamRangesBar(
    ranges: List<TeamTimeRange>,
    guide: List<TeamTimeRange>? = null,
    showHours: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val track = Theme.inkSoft.copy(alpha = 0.12f)
    val guideFill = Theme.blue.copy(alpha = 0.22f)
    val mark = Theme.borderSoft
    val fill = Theme.blue
    val guideMinutes = TeamHours.minuteRanges(guide ?: emptyList())
    val minutes = TeamHours.minuteRanges(ranges).filter { it.second > it.first }
    Column(modifier.fillMaxWidth().clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50)),
        ) {
            val width = size.width
            val height = size.height
            val radius = CornerRadius(height / 2, height / 2)
            drawRoundRect(track, cornerRadius = radius)
            guideMinutes.forEach { range ->
                drawRect(
                    guideFill,
                    topLeft = Offset(range.first / 1440f * width, 0f),
                    size = Size(maxOf(0f, (range.second - range.first) / 1440f * width), height),
                )
            }
            listOf(0.25f, 0.5f, 0.75f).forEach { at ->
                drawRect(mark, topLeft = Offset(at * width, 0f), size = Size(1.dp.toPx(), height))
            }
            minutes.forEach { range ->
                drawRoundRect(
                    fill,
                    topLeft = Offset(range.first / 1440f * width, 0f),
                    size = Size(maxOf(2.dp.toPx(), (range.second - range.first) / 1440f * width), height),
                    cornerRadius = radius,
                )
            }
        }
        if (showHours) {
            Row(Modifier.fillMaxWidth()) {
                listOf(0, 6, 12, 18, 24).forEach { hour ->
                    Text("$hour", style = Theme.text(10, FontWeight.SemiBold), color = Theme.inkSoft)
                    if (hour != 24) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The week in runs ("Lun–Vie 9:00–17:00"), for reading. */
@Composable
fun TeamWeekList(week: List<TeamDay>, closedWord: String = L("Day off"), modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TeamHours.summary(week).forEach { (days, hours, open) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(days, Modifier.width(90.dp).alignByBaseline(), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                Text(if (open) hours else closedWord, Modifier.alignByBaseline(), style = Theme.text(14), color = if (open) Theme.inkStrong else Theme.inkSoft)
            }
        }
    }
}
