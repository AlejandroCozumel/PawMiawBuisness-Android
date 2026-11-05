package com.pawmiaw.business.schedule

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.ceil

//
//  SchedAgendaViews.kt (iOS SchedAgendaViews.swift)
//
//  The Agenda's time grid (web agenda/agenda-grid.tsx): people or rooms side
//  by side for one day, or the seven days of a week, under a 72 pt time
//  gutter in 30-minute slots 64 tall. Closed time is grey, open time a person
//  doesn't work is hatched with why, today has the red "now" line. A visit is
//  a block in its status colour (a 4 pt edge, washed while someone waits, is
//  with them or is done; a requested online booking is dashed as
//  "Por confirmar"); visits that overlap sit side by side, as photos when the
//  column is too narrow, then "+N". Tap empty time to book there. The grid
//  scrolls both ways inside its card with the column heads on top. No drag
//  here: the edit sheet moves a visit.
//

/** A pet drawn on a block (a family's pets or a session's places). */
data class SchedBlockPet(
    val name: String,
    val species: String?,
    val photo: String?,
)

/** One thing on the Agenda: a visit, a family's pets booked together, or a group session. */
data class SchedBlock(
    val id: String,
    val appointmentId: String?,
    val sessionId: String?,
    val start: Instant,
    val end: Instant,
    val status: String,
    val title: String,
    val service: String,
    val client: String?,
    val memberId: String?,
    val memberName: String?,
    val roomId: String?,
    val room: String?,
    val photo: String?,
    val species: String?,
    val online: Boolean,
    val doubleBooked: Boolean,
    val asking: Boolean,
    val repeats: Boolean,
    val places: String?,
    /** Pets booked together (a family's, a session's): drawn as a stack. */
    var pets: List<SchedBlockPet> = emptyList(),
) {
    val requested: Boolean get() = status == "requested"
    val gone: Boolean get() = SchedStatus.gone.contains(status)
    val day: String get() = SchedDay.key(start)
    val isSession: Boolean get() = sessionId != null
    /** What the block says first: the pet(s), or the session and its places. */
    val heading: String get() = if (isSession) listOfNotNull(service, places).joinToString(" · ") else title

    companion object {
        fun visit(a: SchedAppointment, title: String? = null, pets: List<SchedBlockPet> = emptyList()): SchedBlock? {
            val start = a.start ?: return null
            return SchedBlock(
                id = a.id, appointmentId = a.id, sessionId = null, start = start, end = a.end ?: start.plusSeconds(30 * 60L), status = a.status,
                title = title ?: a.petName, service = a.serviceLabel, client = a.clientName, memberId = a.assignedMember?.id, memberName = a.assignedMember?.name,
                roomId = a.roomId, room = a.room, photo = a.petPhotoUrl, species = a.petSpecies, online = a.bookedOnline == true,
                doubleBooked = a.doubleBooked == true, asking = a.familyAsking, repeats = a.seriesId != null, places = null, pets = pets,
            )
        }

        fun session(s: SchedSession, fallback: SchedAppointment? = null): SchedBlock? {
            val start = DateText.parse(s.startsAt) ?: fallback?.start ?: return null
            val staying = s.stayingPets
            val status = when {
                staying.any { it.status == "in_progress" } -> "in_progress"
                staying.isNotEmpty() && staying.all { it.status == "completed" } -> "completed"
                staying.any { it.status == "checked_in" } -> "checked_in"
                else -> "scheduled"
            }
            val label = s.serviceLabel ?: fallback?.serviceLabel ?: L("Group session")
            return SchedBlock(
                id = "session:${s.id}", appointmentId = null, sessionId = s.id, start = start, end = DateText.parse(s.endsAt) ?: start.plusSeconds(60 * 60L), status = status,
                title = label, service = label, client = null, memberId = s.assignedMember?.id ?: fallback?.assignedMember?.id, memberName = s.assignedMember?.name ?: fallback?.assignedMember?.name,
                roomId = s.roomId ?: fallback?.roomId, room = s.room ?: fallback?.room, photo = null, species = null, online = s.onlineBookingOpen == true,
                doubleBooked = s.doubleBooked == true, asking = false, repeats = false, places = s.placesLabel,
                pets = staying.map { SchedBlockPet(name = it.name ?: "", species = it.species, photo = it.photoUrl) },
            )
        }

        /**
         * The visits as the Agenda draws them: a session once, a family's pets booked together once,
         * sessions nobody joined yet still holding their time (web lib/group-visits.ts `foldAgendaVisits`).
         */
        fun fold(appointments: List<SchedAppointment>, sessions: List<SchedSession>): List<SchedBlock> {
            val out = mutableListOf<SchedBlock>()
            val seen = HashSet<String>()
            val sessionById = LinkedHashMap<String, SchedSession>()
            for (s in sessions) if (!sessionById.containsKey(s.id)) sessionById[s.id] = s
            for (a in appointments) {
                val group = a.group
                if (group != null && a.isGone && (group.isSession || group.stayingPets.isNotEmpty())) continue
                if (group == null || a.isGone) {
                    visit(a)?.let { out.add(it) }
                    continue
                }
                if (group.isSession) {
                    if (!seen.add(group.id)) continue
                    session(sessionById[group.id] ?: group, fallback = a)?.let { out.add(it) }
                    continue
                }
                val staying = group.stayingPets
                if (staying.size < 2) {
                    visit(a)?.let { out.add(it) }
                    continue
                }
                if (!seen.add(group.id)) continue
                val pets = staying.map { SchedBlockPet(name = it.name ?: "", species = it.species, photo = it.photoUrl) }
                visit(a, title = schedNames(staying.mapNotNull { it.name }), pets = pets)?.let { out.add(it) }
            }
            for (s in sessions) {
                if (s.status != "active" || seen.contains(s.id)) continue
                seen.add(s.id)
                session(s)?.let { out.add(it) }
            }
            return out.sortedBy { it.start }
        }
    }
}

/** A column of the grid: a person, nobody yet, a room, or one day of the week. */
data class SchedColumn(
    val id: String,
    val title: String,
    val detail: String? = null,
    val kind: Kind,
    val memberId: String? = null,
    val roomId: String? = null,
    val day: String,
    /** The location's open ranges that day. */
    val open: List<SchedMinuteRange>,
    /** A person's working ranges that day (null = the location's hours). */
    val working: List<SchedMinuteRange>? = null,
    val offNote: String? = null,
    val image: String? = null,
    /** Who a visit booked from this column is for (web `createMemberId`); null = nobody can book here. */
    val createMemberId: String? = null,
) {
    enum class Kind { person, unassigned, room, day }

    val isToday: Boolean get() = day == SchedDay.today

    fun holds(block: SchedBlock): Boolean = when (kind) {
        Kind.day -> block.day == day
        Kind.room -> block.roomId == roomId
        Kind.unassigned -> block.memberId == null
        Kind.person -> block.memberId == memberId
    }

    val bookRoomId: String? get() = if (kind == Kind.room) roomId else null
}

// MARK: - The grid's arithmetic (web lib/agenda.ts)

object SchedGrid {
    const val slotMinutes = 30
    val slotHeight: Dp = 64.dp
    val gutter: Dp = 72.dp
    val photoSlot: Dp = 30.dp

    data class Range(val min: Int, val max: Int) {
        val rows: List<Int> get() = (min until max step slotMinutes).toList()
    }

    data class Place(val lane: Int, val lanes: Int, val cluster: Int)

    data class Fit(val photo: Boolean, val slots: Int, val shown: Int)

    fun minutes(block: SchedBlock): Pair<Int, Int> {
        val start = SchedDay.minuteOfDay(block.start)
        val raw = SchedDay.minuteOfDay(block.end)
        return Pair(start, if (raw <= start) start + slotMinutes else raw)
    }

    /** The rows to draw: the opening hours, stretched to fit any visit outside them. */
    fun range(blocks: List<SchedBlock>, open: List<SchedMinuteRange>): Range {
        var low = open.minOfOrNull { it.start } ?: (9 * 60)
        var high = open.maxOfOrNull { minOf(it.end, 1439) } ?: (17 * 60)
        for (block in blocks) {
            val start = SchedDay.minuteOfDay(block.start)
            val end = SchedDay.minuteOfDay(block.end)
            low = minOf(low, start)
            high = maxOf(high, if (end < start) 1440 else end)
        }
        low = (low / slotMinutes) * slotMinutes
        high = minOf(1440, ceil(high.toDouble() / slotMinutes).toInt() * slotMinutes)
        if (high <= low) high = low + slotMinutes
        return Range(min = low, max = high)
    }

    fun position(block: SchedBlock, min: Int): Pair<Dp, Dp> {
        val (start, end) = minutes(block)
        val top = slotHeight * ((start - min).toFloat() / slotMinutes)
        val height = maxOf(slotHeight * ((end - start).toFloat() / slotMinutes), 40.dp)
        return Pair(top, height)
    }

    /** Overlapping visits in one column sit side by side. */
    fun lanes(blocks: List<SchedBlock>): Map<String, Place> {
        val out = HashMap<String, Place>()
        val cluster = mutableListOf<Pair<String, Int>>()
        val laneEnds = mutableListOf<Instant>()
        var clusterEnd = Instant.MIN
        var index = 0
        fun close() {
            for ((id, lane) in cluster) out[id] = Place(lane = lane, lanes = maxOf(1, laneEnds.size), cluster = index)
            if (cluster.isNotEmpty()) index += 1
            cluster.clear()
            laneEnds.clear()
        }
        for (block in blocks.sortedBy { it.start }) {
            if (block.start >= clusterEnd) close()
            val lane = laneEnds.indexOfFirst { it <= block.start }
            if (lane >= 0) {
                laneEnds[lane] = block.end
                cluster.add(Pair(block.id, lane))
            } else {
                cluster.add(Pair(block.id, laneEnds.size))
                laneEnds.add(block.end)
            }
            clusterEnd = maxOf(clusterEnd, block.end)
        }
        close()
        return out
    }

    /** Cards while each has room for a name, else photos, else photos and "+N". */
    fun fit(lanes: Int, width: Dp, cardMin: Dp): Fit {
        val usable = if (width > 0.dp) width - 8.dp else Dp.Infinity
        if (usable / lanes >= cardMin) return Fit(photo = false, slots = lanes, shown = lanes)
        if (usable / lanes >= photoSlot) return Fit(photo = true, slots = lanes, shown = lanes)
        val slots = maxOf(2, (usable / photoSlot).toInt())
        return Fit(photo = true, slots = slots, shown = slots - 1)
    }

    /** The gaps between ranges inside [from, to): what the grid greys out. */
    fun gaps(ranges: List<SchedMinuteRange>, from: Int, to: Int): List<SchedMinuteRange> {
        val out = mutableListOf<SchedMinuteRange>()
        var cursor = from
        for (range in ranges.sortedBy { it.start }) {
            if (range.start > cursor) out.add(SchedMinuteRange(start = cursor, end = minOf(range.start, to)))
            cursor = maxOf(cursor, range.end)
        }
        if (cursor < to) out.add(SchedMinuteRange(start = cursor, end = to))
        return out.filter { it.end > it.start }
    }

    fun intersect(a: List<SchedMinuteRange>, b: List<SchedMinuteRange>): List<SchedMinuteRange> {
        val out = mutableListOf<SchedMinuteRange>()
        for (left in a) {
            for (right in b) {
                val start = maxOf(left.start, right.start)
                val end = minOf(left.end, right.end)
                if (end > start) out.add(SchedMinuteRange(start = start, end = end))
            }
        }
        return out.sortedBy { it.start }
    }

    fun y(minute: Int, range: Range): Dp = slotHeight * ((minute - range.min).toFloat() / slotMinutes)
}

// MARK: - The grid

private val SchedHeadHeight = 57.dp

@Composable
fun SchedAgendaGrid(
    columns: List<SchedColumn>,
    blocks: List<SchedBlock>,
    range: SchedGrid.Range,
    /** The week: narrower columns, a block says less. */
    compact: Boolean = false,
    canCreate: Boolean,
    /** The block's second line: its room on a person's column, its person on a room's. */
    secondary: (SchedBlock) -> String?,
    onOpen: (SchedBlock) -> Unit,
    onCreate: (SchedColumn, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrolledFor by remember { mutableStateOf<String?>(null) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    val screen = LocalConfiguration.current.screenHeightDp.dp

    // The minute, for today's "now" line (iOS `TimelineView(.everyMinute)`).
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val wait = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(wait)
            now = Instant.now()
        }
    }

    /** A closed day with nothing on it (the week's "Cerrado") is narrow, so the open days get the room. */
    val narrow = columns.filter { col -> col.kind == SchedColumn.Kind.day && col.open.isEmpty() && !blocks.any { col.holds(it) } }.map { it.id }.toSet()
    val bodyHeight = SchedGrid.slotHeight * range.rows.size

    /** Where the grid opens: now when today is shown and open, else the first visit. */
    val scrollMinute: Int? = run {
        val minute = SchedDay.minuteOfDay(Instant.now())
        if (columns.any { it.isToday } && minute >= range.min && minute < range.max) minute
        else blocks.filter { !it.gone }.minOfOrNull { SchedDay.minuteOfDay(it.start) }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        val widths: List<Dp> = run {
            val minimum = if (compact) 144.dp else 224.dp
            val weights = columns.map { if (narrow.contains(it.id)) 0.45 else 1.0 }
            val total = maxOf(weights.sum(), 0.01)
            val unit = maxOf(0.dp, width - SchedGrid.gutter) / total.toFloat()
            columns.indices.map { i -> maxOf(if (narrow.contains(columns[i].id)) 96.dp else minimum, unit * weights[i].toFloat()) }
        }
        val height = minOf(SchedHeadHeight + bodyHeight, maxOf(320.dp, minOf(screen * 0.75f, 896.dp)))

        val key = "${range.min}|${columns.joinToString("") { it.id }}"
        LaunchedEffect(key) {
            if (scrolledFor == key) return@LaunchedEffect
            scrolledFor = key
            val minute = scrollMinute ?: return@LaunchedEffect
            val target = (minute / SchedGrid.slotMinutes) * SchedGrid.slotMinutes - SchedGrid.slotMinutes
            if (target <= range.min) return@LaunchedEffect
            delay(150)
            val y = with(density) { SchedGrid.y(target, range).roundToPx() }
            launch { horizontal.animateScrollTo(0, tween(350, easing = EaseOut)) }
            vertical.animateScrollTo(y, tween(350, easing = EaseOut))
        }

        Column(Modifier.fillMaxWidth().height(height)) {
            // The column heads, pinned on top (iOS `pinnedViews: [.sectionHeaders]`), scrolling sideways with the grid.
            Box(Modifier.fillMaxWidth().background(Theme.surface)) {
                Row(Modifier.horizontalScroll(horizontal)) {
                    Spacer(Modifier.width(SchedGrid.gutter).height(SchedHeadHeight))
                    columns.forEachIndexed { index, col ->
                        SchedColumnHead(column = col, compact = compact, narrow = narrow.contains(col.id), modifier = Modifier.width(widths[index]).height(SchedHeadHeight))
                    }
                }
                RowDivider(modifier = Modifier.align(Alignment.BottomStart))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(vertical)
            ) {
                Row(Modifier.horizontalScroll(horizontal), verticalAlignment = Alignment.Top) {
                    SchedGutter(range)
                    columns.forEachIndexed { index, col ->
                        SchedColumnBody(
                            col = col,
                            width = widths[index],
                            blocks = blocks,
                            range = range,
                            compact = compact,
                            canCreate = canCreate,
                            now = now,
                            secondary = secondary,
                            onOpen = onOpen,
                            onCreate = onCreate,
                        )
                    }
                }
            }
        }
    }
}

// MARK: Gutter

@Composable
private fun SchedGutter(range: SchedGrid.Range) {
    Box {
        Column {
            for (minute in range.rows) {
                Box(Modifier.width(SchedGrid.gutter).height(SchedGrid.slotHeight)) {
                    FittedText(
                        if (minute % 60 == 0) SchedDay.timeLabel(minute) else "",
                        style = Theme.text(11.2, FontWeight.SemiBold),
                        color = Theme.inkSoft,
                        minimumScaleFactor = 0.8f,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(horizontal = 8.dp)
                            .padding(top = 4.dp),
                    )
                    RowDivider(modifier = Modifier.align(Alignment.BottomStart))
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).width(1.dp).height(SchedGrid.slotHeight * range.rows.size).background(Theme.borderSoft))
    }
}

// MARK: A column

@Composable
private fun SchedColumnBody(
    col: SchedColumn,
    width: Dp,
    blocks: List<SchedBlock>,
    range: SchedGrid.Range,
    compact: Boolean,
    canCreate: Boolean,
    now: Instant,
    secondary: (SchedBlock) -> String?,
    onOpen: (SchedBlock) -> Unit,
    onCreate: (SchedColumn, Int) -> Unit,
) {
    val inColumn = blocks.filter { col.holds(it) }
    val lanes = SchedGrid.lanes(inColumn)
    val fits = HashMap<Int, SchedGrid.Fit>()
    val hidden = HashMap<Int, MutableList<SchedBlock>>()
    for (block in inColumn) {
        val place = lanes[block.id] ?: continue
        if (fits[place.cluster] == null) fits[place.cluster] = SchedGrid.fit(lanes = place.lanes, width = width, cardMin = if (compact) 84.dp else 96.dp)
        if (place.lane >= fits[place.cluster]!!.shown) hidden.getOrPut(place.cluster) { mutableListOf() }.add(block)
    }
    val closed = SchedGrid.gaps(col.open, from = range.min, to = range.max)
    val off = col.working?.let { SchedGrid.intersect(col.open, SchedGrid.gaps(it, from = range.min, to = range.max)) } ?: emptyList()
    val creatable = canCreate && col.createMemberId != null
    val bodyHeight = SchedGrid.slotHeight * range.rows.size

    Box(
        Modifier
            .width(width)
            .height(bodyHeight)
            .clipToBounds()
    ) {
        for (gap in closed) {
            Box(
                Modifier
                    .offset(y = SchedGrid.y(gap.start, range))
                    .width(width)
                    .height(SchedGrid.y(gap.end, range) - SchedGrid.y(gap.start, range))
                    .background(Theme.wash(Theme.inkSoft, 9.0))
            )
        }
        for (gap in off) {
            SchedOffTime(
                note = if (gap.end - gap.start >= SchedGrid.slotMinutes) col.offNote else null,
                modifier = Modifier
                    .offset(y = SchedGrid.y(gap.start, range))
                    .width(width)
                    .height(SchedGrid.y(gap.end, range) - SchedGrid.y(gap.start, range)),
            )
        }
        for (minute in range.rows) {
            SchedGridSlot(
                minute = minute,
                creatable = creatable,
                modifier = Modifier
                    .offset(y = SchedGrid.y(minute, range))
                    .width(width)
                    .height(SchedGrid.slotHeight),
            ) { onCreate(col, minute) }
        }
        if (col.isToday) {
            val minute = SchedDay.minuteOfDay(now)
            if (minute >= range.min && minute < range.max) {
                val red = Theme.destructive
                Canvas(
                    Modifier
                        .offset(y = SchedGrid.y(minute, range) - 4.dp)
                        .width(width)
                        .height(8.dp)
                ) {
                    drawCircle(red, radius = 4.dp.toPx(), center = Offset(0f, size.height / 2))
                    drawRect(red, topLeft = Offset(0f, size.height / 2 - 0.5.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
                }
            }
        }
        for (block in inColumn) {
            val place = lanes[block.id] ?: SchedGrid.Place(lane = 0, lanes = 1, cluster = -1)
            val fit = fits[place.cluster] ?: SchedGrid.Fit(photo = false, slots = 1, shown = 1)
            if (place.lane < fit.shown) {
                val (top, spotHeight) = SchedGrid.position(block, min = range.min)
                val inset = if (fit.photo) 1.6.dp else 4.dp
                val laneWidth = width / fit.slots
                SchedGridBlock(
                    block = block,
                    height = spotHeight - 4.dp,
                    lanes = fit.slots,
                    photoOnly = fit.photo,
                    compact = compact,
                    secondary = secondary(block),
                    modifier = Modifier
                        .offset(x = laneWidth * place.lane + inset, y = top + 2.dp)
                        .width(maxOf(0.dp, laneWidth - inset * 2))
                        .height(maxOf(0.dp, spotHeight - 4.dp)),
                ) { onOpen(block) }
            }
        }
        for (cluster in hidden.keys.sorted()) {
            val rest = hidden[cluster] ?: emptyList()
            val fit = fits[cluster]!!
            val group = inColumn.filter { lanes[it.id]?.cluster == cluster }
            val top = group.minOfOrNull { SchedGrid.position(it, min = range.min).first } ?: 0.dp
            val laneWidth = width / fit.slots
            val box = maxOf(28.dp, laneWidth - 4.8.dp)
            Box(
                Modifier
                    .offset(x = width - box - 4.dp, y = top + 2.dp)
                    .widthIn(min = box),
                contentAlignment = Alignment.CenterEnd,
            ) {
                SchedMoreVisits(visits = rest, onOpen = onOpen)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).width(1.dp).fillMaxHeight().background(Theme.borderSoft))
    }
}

// MARK: - The grid's pieces

/** A column's head: who or what (a 32 disc), and a small uppercase detail. */
@Composable
private fun SchedColumnHead(column: SchedColumn, compact: Boolean, narrow: Boolean, modifier: Modifier = Modifier) {
    val todayInWeek = column.isToday && compact
    Box(modifier.background(if (todayInWeek) Theme.wash(Theme.blue, 8.0) else Theme.surface)) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (narrow) 8.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!narrow) SchedColumnDisc(column, todayInWeek)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(column.title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = column.detail.nonEmpty
                if (detail != null) {
                    Text(
                        detail.uppercase(appLocale),
                        style = Theme.text(11.2, FontWeight.SemiBold).tracking(0.9),
                        color = Theme.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight().background(Theme.borderSoft))
    }
}

@Composable
private fun SchedColumnDisc(column: SchedColumn, todayInWeek: Boolean) {
    when (column.kind) {
        SchedColumn.Kind.day -> Box(
            Modifier
                .size(32.dp)
                .background(if (todayInWeek) Theme.blue else Theme.wash(Theme.aqua, 25.0), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(SchedDay.dayNumber(column.day), style = Theme.text(12, FontWeight.Bold), color = if (todayInWeek) Theme.onBlue else Theme.inkStrong)
        }
        SchedColumn.Kind.person ->
            if (column.image.nonEmpty != null) {
                Avatar(name = column.title, imageURL = column.image, size = 32.dp)
            } else {
                IconBadge(icon = "user-round", accent = Theme.aqua, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 25.0)
            }
        SchedColumn.Kind.room -> IconBadge(icon = "door-open", accent = Theme.aqua, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 25.0)
        SchedColumn.Kind.unassigned -> IconBadge(icon = "users-round", accent = Theme.aqua, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 25.0)
    }
}

/** A half hour of a column: the line under it (solid or dashed, as on the web) and a tap that books it. */
@Composable
private fun SchedGridSlot(minute: Int, creatable: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = LF("Book at %@", SchedDay.timeLabel(minute))
    val line = Theme.borderSoft
    Box(
        modifier
            .then(if (pressed && creatable) Modifier.background(Theme.wash(Theme.blue, 18.0)) else Modifier)
            .then(
                if (creatable) Modifier
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) { action() }
                    .semantics { contentDescription = label }
                else Modifier.clearAndSetSemantics {}
            )
    ) {
        if (minute % 60 == 0) {
            RowDivider(modifier = Modifier.align(Alignment.BottomStart))
        } else {
            Canvas(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
            ) {
                drawLine(
                    color = line.copy(alpha = line.alpha * 0.7f),
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
            }
        }
    }
}

/** Open time a person doesn't work: pink hatching, and why (a day off, not working). */
@Composable
private fun SchedOffTime(note: String?, modifier: Modifier = Modifier) {
    val ground = Theme.wash(Theme.inkSoft, 5.0)
    val hatch = Theme.wash(Theme.pink, 26.0)
    Box(modifier.clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(ground)
            val step = 14.dp.toPx() * 1.4142f
            val lineWidth = 6.dp.toPx() * 1.4142f
            var x = 0f
            while (x < size.width + size.height) {
                drawLine(hatch, start = Offset(x, 0f), end = Offset(x - size.height, size.height), strokeWidth = lineWidth)
                x += step
            }
        }
        if (note != null) {
            val capsule = RoundedCornerShape(50)
            Text(
                note,
                style = Theme.text(10.9, FontWeight.Bold),
                color = Theme.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(6.dp)
                    .softShadow(capsule, Theme.navy.copy(alpha = 0.1f), radius = 1.5.dp, y = 1.dp)
                    .background(Theme.surface, capsule)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** A visit on the grid (web `VisitBlock`). */
@Composable
private fun SchedGridBlock(
    block: SchedBlock,
    height: Dp,
    lanes: Int,
    photoOnly: Boolean,
    compact: Boolean,
    secondary: String?,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val state = ProviderState.appointment(block.status)
    val short = height + 4.dp < 64.dp
    val showPhoto = height + 4.dp >= 28.dp && lanes <= (if (compact) 2 else 3)
    val edge = if (block.doubleBooked) Theme.destructive else (if (state == ProviderState.closed) Theme.borderSoft else state.color)
    val times = "${DateText.time(block.start)}–${DateText.time(block.end)}"
    val shape = RoundedCornerShape(Theme.chipRadius)
    val background = when {
        block.requested || state == ProviderState.closed -> Theme.surface
        state == ProviderState.waiting || state == ProviderState.active || state == ProviderState.done -> Theme.wash(state.color, 12.0)
        else -> Theme.surface
    }
    val label = "${block.heading} · ${block.service} · $times${if (block.requested) " · " + L("To confirm") else ""}"
    val rim = if (block.doubleBooked) Theme.destructive else Theme.borderSoft

    Box(
        modifier
            .alpha(if (block.gone) 0.5f else 1f)
            .then(if (!block.requested) Modifier.cardShadow(shape) else Modifier)
            .clip(shape)
            .background(background)
            .then(
                if (block.requested) Modifier.dashedBorder(2.dp, edge, cornerRadius = Theme.chipRadius, on = 5.dp, off = 3.dp)
                else Modifier
                    .border(1.dp, rim, shape)
                    .drawWithContent {
                        drawContent()
                        drawRect(edge, size = Size(4.dp.toPx(), size.height))
                    }
            )
            .plainButton(label = label) { action() }
    ) {
        if (photoOnly) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) { SchedBlockPhoto(block, 24.dp, photoOnly, compact) }
        } else {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                verticalAlignment = if (short) Alignment.CenterVertically else Alignment.Top,
            ) {
                if (showPhoto) SchedBlockPhoto(block, if (compact || short) 20.dp else 28.dp, photoOnly, compact)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val inkSoft = Theme.inkSoft
                    val inkStrong = Theme.inkStrong
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = inkStrong)) { append(block.heading) }
                            if (!compact && !block.isSession) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = inkSoft)) { append(" · ${block.service}") }
                            }
                        },
                        style = Theme.text(14).copy(textDecoration = if (block.gone) TextDecoration.LineThrough else null),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!short) {
                        Text(
                            buildAnnotatedString {
                                if (block.requested) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = inkStrong)) { append(L("To confirm") + " · ") }
                                append(times)
                                val second = secondary.nonEmpty
                                if (second != null && !compact) append(" · $second")
                            },
                            style = Theme.text(12),
                            color = inkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (block.doubleBooked) {
                        val a11y = L("Double booked")
                        Glyph("triangle-alert", 14.dp, tint = Theme.destructive, modifier = Modifier.semantics { contentDescription = a11y })
                    }
                    if (block.asking) {
                        val a11y = L("The family asked for a change")
                        Glyph("message-circle-question", 14.dp, tint = Theme.pink, modifier = Modifier.semantics { contentDescription = a11y })
                    }
                    if (block.repeats) {
                        val a11y = L("Repeats")
                        Glyph("repeat", 12.dp, tint = Theme.inkSoft, modifier = Modifier.semantics { contentDescription = a11y })
                    }
                    if (block.requested && short) Glyph("clock-3", 12.dp, tint = Theme.inkStrong)
                    if (block.online) {
                        val a11y = L("Booked online")
                        Glyph("globe", 12.dp, tint = Theme.inkSoft, modifier = Modifier.semantics { contentDescription = a11y })
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedBlockPhoto(block: SchedBlock, size: Dp, photoOnly: Boolean, compact: Boolean) {
    if (block.isSession || block.pets.size > 1) {
        if (block.pets.isEmpty()) {
            Box(Modifier.size(size), contentAlignment = Alignment.Center) { Glyph("users-round", 14.dp, tint = Theme.inkSoft) }
        } else {
            PetStack(pets = block.pets.map { Triple(it.name, it.species, it.photo) }, size = size, max = if (photoOnly) 1 else (if (compact) 2 else 3))
        }
    } else {
        PetAvatar(
            name = block.title,
            species = block.species,
            photoURL = block.photo,
            size = size,
            modifier = Modifier.alpha(if (block.gone && photoOnly) 0.7f else 1f),
        )
    }
}

/** The visits of a crowded group that don't fit even as photos: "+N", opening the list of them. */
@Composable
private fun SchedMoreVisits(visits: List<SchedBlock>, onOpen: (SchedBlock) -> Unit) {
    val capsule = RoundedCornerShape(50)
    AppMenu(
        label = {
            Box(
                Modifier
                    .heightIn(min = 28.dp)
                    .height(28.dp)
                    .widthIn(min = 28.dp)
                    .cardShadow(capsule)
                    .background(Theme.blue, capsule)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+${visits.size}", style = Theme.text(11.5, FontWeight.ExtraBold), color = Theme.onBlue, maxLines = 1)
            }
        },
        accessibilityLabel = LF("%@ more visits", visits.size.toString()),
    ) {
        for (block in visits) {
            item("${block.heading} · ${block.service}\n${DateText.time(block.start)}–${DateText.time(block.end)}${block.client?.let { " · $it" } ?: ""}") { onOpen(block) }
        }
    }
}

// MARK: - Around the grid

/** `SchedDayStrip.Tone`. */
object SchedDayStrip {
    enum class Tone { special, closed, quiet }
}

/** A strip across the top of the grid's card (web: the day's closure or special hours). */
@Composable
fun SchedDayStrip(tone: SchedDayStrip.Tone, text: String, modifier: Modifier = Modifier) {
    val fill = when (tone) {
        SchedDayStrip.Tone.special -> Theme.wash(Theme.mango, 12.0)
        SchedDayStrip.Tone.closed -> Theme.wash(Theme.pink, 12.0)
        SchedDayStrip.Tone.quiet -> Theme.surfaceMuted
    }
    val ink = if (tone == SchedDayStrip.Tone.quiet) Theme.inkSoft else Theme.inkStrong
    Box(modifier.fillMaxWidth().background(fill)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("calendar-plus", 16.dp, tint = ink)
            Text(text, style = Theme.text(14, FontWeight.SemiBold), color = ink, modifier = Modifier.weight(1f))
        }
        RowDivider(modifier = Modifier.align(Alignment.BottomStart))
    }
}

/**
 * "Familias pidiendo un cambio" (web provider.schedule.tsx): a pink card, an
 * eyebrow, then the families' visits as white rows (two across on an iPad).
 */
@Composable
fun SchedAskingCard(visits: List<SchedAppointment>, onOpen: (SchedAppointment) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    val columns = if (LocalSizeClass.current == SizeClass.regular) 2 else 1
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.pink, 8.0), shape)
            .border(1.dp, Theme.mix(Theme.pink, 40.0, Theme.borderSoft), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("message-circle-question", 16.dp, tint = Theme.inkStrong)
            Text(L("Families asking for a change").uppercase(appLocale), style = Theme.text(12, FontWeight.ExtraBold).tracking(1.44), color = Theme.inkStrong)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visits.take(4).chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    line.forEach { visit ->
                        SchedAskingRow(visit, Modifier.weight(1f).plainButton(label = null) { onOpen(visit) })
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (visits.size > 4) {
            Text(
                LF("See all %@", visits.size.toString()),
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blue,
                modifier = Modifier.plainButton { onSeeAll() },
            )
        }
    }
}

@Composable
private fun SchedAskingRow(visit: SchedAppointment, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .softShadow(shape, Theme.navy.copy(alpha = 0.08f), radius = 1.5.dp, y = 1.dp)
            .background(Theme.surface, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetAvatar(name = visit.petName, species = visit.petSpecies, photoURL = visit.petPhotoUrl, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "${visit.petName} · ${if (visit.confirmationStatus == "cancellation_requested") L("wants to cancel") else L("wants to move it")}",
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(DateText.dayTime(visit.start), visit.confirmationPreferredTime?.let { LF("prefers %@", it) }).joinToString(" · "),
                style = Theme.text(12),
                color = Theme.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}
