package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import java.text.Collator
import kotlin.math.max
import kotlin.math.min

//
//  StaysBoard.kt (iOS StaysBoard.swift)
//
//  The board (web stays-board.tsx): a row per room (and "No room yet", and a
//  row per sitter for home stays), a column per day, each stay a bar from
//  mid-arrival to mid-departure in the first lane free for its days. A pet
//  still here past its day runs on to the night its room is still held, with
//  a red edge and "Debía salir el …". Tapping an empty day books it.
//

// The web's grid: a 8.5rem room column, 3.6rem days, 2.9rem lanes (a 2.4rem bar).
private val dayWidth: Dp = 57.6.dp
private val laneHeight: Dp = 46.4.dp
private val barHeight: Dp = 38.4.dp
private val labelWidth: Dp = 136.dp
private val headerHeight: Dp = 64.dp

private class StaysPlaced(
    val stay: StaysStay,
    val lane: Int,
    val start: Double,
    val end: Double,
) {
    val id: String get() = stay.id
}

private class StaysBoardRow(
    val id: String,
    val unit: StaysUnit?,
    /** A home stay row: the sitter's id ("" for nobody yet) and name. */
    val home: Pair<String, String?>?,
    val placed: List<StaysPlaced>,
    val lanes: Int,
) {
    val height: Dp get() = laneHeight * lanes + 17.6.dp
}

@Composable
fun StaysBoardView(
    units: List<StaysUnit>,
    stays: List<StaysStay>,
    fromDay: String,
    days: Int,
    today: String,
    canBook: Boolean,
    offersRooms: Boolean,
    offersSitting: Boolean,
    modifier: Modifier = Modifier,
    onBook: (StaysBookingPreset) -> Unit,
) {
    val navigator = LocalNavigator.current
    val dayKeys = (0 until days).map { StaysDay.add(fromDay, it) }

    /** Stays in one row as bars, each in the first lane free for its days. */
    fun place(list: List<StaysStay>): Pair<List<StaysPlaced>, Int> {
        val sorted = list.sortedWith(compareBy<StaysStay>({ it.checkInDay }, { it.checkInTime ?: "" }))
        val laneEnds = mutableListOf<Double>()
        val placed = mutableListOf<StaysPlaced>()
        for (stay in sorted) {
            val startIndex = StaysDay.between(fromDay, stay.checkInDay).toDouble()
            val start = if (stay.isDaycare) startIndex + 0.08 else startIndex + 0.5
            val endDay = if (stay.lateDeparture == true) (stay.heldUntilDay ?: stay.checkOutDay) else stay.checkOutDay
            val end = if (stay.isDaycare) startIndex + 0.92
            else max(StaysDay.between(fromDay, endDay), startIndex.toInt() + 1).toDouble() + 0.5
            if (end <= 0 || start >= days.toDouble()) continue
            var lane = laneEnds.indexOfFirst { it <= start + 0.001 }
            if (lane == -1) {
                lane = laneEnds.size
                laneEnds.add(end)
            } else {
                laneEnds[lane] = end
            }
            placed.add(StaysPlaced(stay = stay, lane = lane, start = max(0.0, start), end = min(days.toDouble(), end)))
        }
        return Pair(placed, max(1, laneEnds.size))
    }

    val rows = run {
        val visible = stays.filter { it.status != "cancelled" }
        val byUnit = LinkedHashMap<String, MutableList<StaysStay>>()
        val unassigned = mutableListOf<StaysStay>()
        val bySitter = LinkedHashMap<String, Pair<String?, MutableList<StaysStay>>>()
        val unitIds = units.map { it.id }.toSet()
        for (stay in visible) {
            if (stay.isHome) {
                val sitter = stay.home?.sitterMemberId ?: ""
                val entry = bySitter.getOrPut(sitter) { Pair(stay.home?.sitterName, mutableListOf()) }
                entry.second.add(stay)
                continue
            }
            val unit = stay.unitId
            if (unit != null && unit in unitIds) byUnit.getOrPut(unit) { mutableListOf() }.add(stay) else unassigned.add(stay)
        }
        val result = mutableListOf<StaysBoardRow>()
        if (offersRooms) {
            for (unit in units) {
                val (placed, lanes) = place(byUnit[unit.id] ?: emptyList())
                result.add(StaysBoardRow(id = unit.id, unit = unit, home = null, placed = placed, lanes = lanes))
            }
        }
        if (unassigned.isNotEmpty()) {
            val (placed, lanes) = place(unassigned)
            result.add(StaysBoardRow(id = "unassigned", unit = null, home = null, placed = placed, lanes = lanes))
        }
        val collator = Collator.getInstance(appLocale)
        val sitters = bySitter.entries.sortedWith { a, b ->
            when {
                a.key.isEmpty() && b.key.isEmpty() -> 0
                a.key.isEmpty() -> 1
                b.key.isEmpty() -> -1
                else -> collator.compare(a.value.first ?: "", b.value.first ?: "")
            }
        }
        for ((sitter, entry) in sitters) {
            val (placed, lanes) = place(entry.second)
            result.add(StaysBoardRow(id = "home-$sitter", unit = null, home = Pair(sitter, entry.first), placed = placed, lanes = lanes))
        }
        if (sitters.isEmpty() && offersSitting) {
            result.add(StaysBoardRow(id = "home-", unit = null, home = Pair("", null), placed = emptyList(), lanes = 1))
        }
        result
    }

    val rule = Theme.borderSoft

    @Composable
    fun Label(row: StaysBoardRow) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val home = row.home
            val unit = row.unit
            if (home != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("house", 16.dp, tint = Theme.blue)
                    Text(
                        if (home.first.isEmpty()) L("No sitter yet") else (home.second ?: L("Sitter")),
                        style = brandHeading(16), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(L("Home sitting"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (unit != null) {
                Text(unit.name, style = brandHeading(16), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    StaysWords.unitKind(unit.kind) + (if (unit.seats > 1) " · " + LF("%d pets", unit.seats) else ""),
                    style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(L("No room yet"), style = brandHeading(16), color = Theme.mango)
                Text(L("Assign one from the stay"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    fun Header() {
        Row(Modifier.height(headerHeight)) {
            for (day in dayKeys) {
                Column(
                    Modifier
                        .width(dayWidth)
                        .height(headerHeight)
                        .background(if (StaysDay.isWeekend(day)) Theme.aqua.copy(alpha = 0.07f) else Color.Transparent)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        StaysDay.format(day, "EEE").uppercase(),
                        style = brandText(10.9, FontWeight.Bold).tracking(10.9 * 0.1), color = Theme.inkSoft,
                    )
                    Box(
                        Modifier.size(32.dp).background(if (day == today) Theme.blue else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(StaysDay.format(day, "d"), style = brandHeading(16), color = if (day == today) Theme.onBlue else Theme.inkStrong)
                    }
                }
            }
        }
    }

    @Composable
    fun Lane(row: StaysBoardRow) {
        val dayRule = Theme.borderSoft.copy(alpha = 0.6f)
        Box(Modifier.width(dayWidth * days).height(row.height).staysTopRule(rule)) {
            Row {
                for (day in dayKeys) {
                    Box(
                        Modifier
                            .width(dayWidth)
                            .height(row.height)
                            .background(if (day == today) Theme.blue.copy(alpha = 0.06f) else Color.Transparent)
                            .staysTrailingRule(dayRule)
                            // A day already past can't take a new stay.
                            .plainButton(
                                enabled = canBook && day >= today,
                                label = if (row.home != null) LF("Book a home stay on %@", StaysDay.short(day))
                                else LF("Book %@ on %@", row.unit?.name ?: L("a room"), StaysDay.short(day)),
                            ) {
                                onBook(StaysBookingPreset(unitId = row.unit?.id, day = day, home = row.home != null))
                            },
                    )
                }
            }
            for (placed in row.placed) {
                val width = dayWidth * (placed.end - placed.start).toFloat()
                StaysBar(
                    stay = placed.stay,
                    modifier = Modifier
                        .offset(x = dayWidth * placed.start.toFloat(), y = 8.8.dp + laneHeight * placed.lane)
                        .width(if (width > 30.dp) width else 30.dp)
                        .height(barHeight)
                        .plainButton { navigator.push(ProviderRoute.stay(placed.stay.id)) },
                )
            }
        }
    }

    Column(modifier.listCard()) {
        Row(verticalAlignment = Alignment.Top) {
            // The rooms' column stays put while the days scroll.
            Column(Modifier.width(labelWidth).background(Theme.surface).staysTrailingRule(rule)) {
                Box(Modifier.width(labelWidth).height(headerHeight), contentAlignment = Alignment.BottomStart) {
                    Text(
                        L("Rooms").uppercase(),
                        style = brandText(12, FontWeight.Bold).tracking(12 * 0.14), color = Theme.inkSoft,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
                for (row in rows) {
                    Box(
                        Modifier.width(labelWidth).height(row.height).staysTopRule(rule),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Label(row) }
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Column {
                    Header()
                    for (row in rows) Lane(row)
                }
            }
        }
        if (units.isEmpty() && offersRooms) {
            EmptyState(
                systemImage = "bed-double", title = L("Add your rooms to see the board"),
                message = L("Kennels, suites and cat condos, with how many pets each holds and what a night costs."), accent = Theme.aqua,
            )
        }
    }
}

/**
 * One stay on the board (web `StayBar`): its pets' faces, their names (a sun
 * for daycare, a house for a home stay) and either the family and the
 * estimate or the late line; the status at 30% (48% once in), a 2 pt edge,
 * dashed while it is a request, faded once it is over.
 */
@Composable
private fun StaysBar(stay: StaysStay, modifier: Modifier = Modifier) {
    val state = ProviderState.stay(stay.status)
    val faded = stay.status == "checked_out" || stay.status == "no_show"
    val edge = if (stay.late != null) Theme.destructive else (if (stay.status == "checked_in") Theme.blue else state.color)
    val late = stay.late
    Row(
        modifier
            .alpha(if (faded) 0.55f else 1f)
            .softShadow(CapsuleShape, Theme.navy.copy(alpha = 0.08f), 1.5.dp, y = 1.dp)
            .clip(CapsuleShape)
            .background(Theme.wash(state.color, if (stay.status == "checked_in") 48.0 else 30.0))
            .then(
                if (stay.status == "requested") Modifier.dashedBorder(2.dp, edge, null, on = 5.dp, off = 3.dp)
                else Modifier.border(2.dp, edge, CapsuleShape),
            )
            .semantics(mergeDescendants = true) { contentDescription = "${stay.petNames} · ${stay.clientName ?: ""}" }
            .padding(horizontal = 6.dp)
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetStack(pets = stay.petList.map { Triple(it.petName, it.petSpecies, it.photoUrl) }, size = 28.dp, max = 3)
        Column(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (stay.isDaycare) Glyph("sun", 12.dp)
                    if (stay.isHome) Glyph("house", 12.dp)
                    Text(stay.petNames, style = Theme.text(12.8, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (late != null) {
                Text(
                    if (late == StaysLate.departure) LF("Was due out %@", StaysDay.dayMonth(stay.checkOutDay)) else LF("Was due in %@", StaysDay.dayMonth(stay.checkInDay)),
                    style = Theme.text(10.9, FontWeight.Bold), color = Theme.destructive, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "${stay.clientName ?: ""} · ${if (stay.isHome) (stay.home?.addressText ?: "") else Money.short(stay.estimatedTotalCents)}",
                    style = Theme.text(10.9, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `.overlay(alignment: .top) { Rectangle().fill(color).frame(height: 1) }`. */
fun Modifier.staysTopRule(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(color, size = Size(size.width, 1.dp.toPx()))
}

/** `.overlay(alignment: .trailing) { Rectangle().fill(color).frame(width: 1) }`. */
fun Modifier.staysTrailingRule(color: Color): Modifier = drawWithContent {
    drawContent()
    val w = 1.dp.toPx()
    drawRect(color, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
}
