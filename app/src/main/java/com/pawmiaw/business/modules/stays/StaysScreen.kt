package com.pawmiaw.business.stays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

//
//  StaysScreen.kt (iOS StaysScreen.swift)
//
//  Estancias (web provider.stays.tsx): one place's boarding, daycare and home
//  stays. The module header (BedDouble, the brand blue) with "Nueva estancia"
//  / "Nueva estancia en casa" and its numbers (home stay visits today, in
//  tonight of the places, arriving and leaving today with the late ones
//  counted in and flagged, requests waiting), then the page tabs (board,
//  today's care, rooms and house rules, requests) with the board's ‹ Hoy ›.
//  A pet still here past its day keeps its room and shows red everywhere
//  (backend docs/stays-late-and-dates.md).
//

@Composable
fun StaysScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    providerPage(L("Stays"), root = true, modifier = modifier) {
        if (workspace.current != null && !workspace.can(Permission.staysView)) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to give your role access to stays.")) }
        } else {
            RequireLocation { StaysPageView() }
        }
    }
}

enum class StaysTab { board, today, rooms, requests }

/** What a booking sheet starts from: a room and a day (or nothing chosen yet). */
class StaysBookingPreset(
    var unitId: String?,
    var day: String,
    var home: Boolean = false,
) {
    val id: UUID = UUID.randomUUID()
}

@Composable
private fun StaysPageView() {
    val workspace = Workspace.shared
    val sizeClass = LocalSizeClass.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    val tab = remember { mutableStateOf(StaysTab.board) }
    var fromDay by remember { mutableStateOf(StaysDay.add(StaysDay.today(), -1)) }
    var page by remember { mutableStateOf<StaysPage?>(null) }
    var units by remember { mutableStateOf<List<StaysUnit>>(emptyList()) }
    var held by remember { mutableStateOf<List<StaysStay>>(emptyList()) }
    var familyWaiting by remember { mutableIntStateOf(0) }
    var homeVisits by remember { mutableStateOf<List<StaysHomeDayVisit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var booking by remember { mutableStateOf<StaysBookingPreset?>(null) }

    val days = if (sizeClass == SizeClass.regular) 14 else 7
    val compact = sizeClass != SizeClass.regular
    val canManage = workspace.can(Permission.staysManage)
    val offersSitting = workspace.offers(BusinessCategory.sitter)
    // Rooms, check-in and the care board: boarding and daycare (or a stays business without sitting).
    val offersRooms = workspace.offers(BusinessCategory.boarding, BusinessCategory.daycare) || !offersSitting
    val today = page?.today ?: StaysDay.today()
    val stays = page?.items ?: emptyList()

    suspend fun loadBoard() {
        val to = StaysDay.add(fromDay, days - 1)
        try {
            page = API.get<StaysPage>("/provider-stays", mapOf("from" to fromDay, "to" to to, "locationId" to workspace.locationId))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }

    suspend fun load() = coroutineScope {
        val location = workspace.locationId
        val todayKey = StaysDay.today()
        val seesOnline = workspace.can(Permission.appointmentsView)
        val sitting = offersSitting
        val unitsCall = async { runCatching { API.get<List<StaysUnit>>("/provider-stays/units", mapOf("locationId" to location)) }.getOrNull() }
        val heldCall = async {
            runCatching {
                API.get<StaysPage>("/provider-stays", mapOf("from" to StaysDay.add(todayKey, -1), "to" to StaysDay.add(todayKey, 365), "status" to "requested", "locationId" to location))
            }.getOrNull()
        }
        val requestsCall = async { runCatching { API.get<StaysRequestList>("/provider-requests", mapOf("scope" to "open", "locationId" to location)) }.getOrNull() }
        val onlineCall = async {
            if (seesOnline) runCatching { API.get<StaysOnlineList>("/provider-online-booking/requests", mapOf("locationId" to location)) }.getOrNull() else null
        }
        val homeCall = async { if (sitting) runCatching { API.get<StaysHomeDay>("/provider-homestays/visits", mapOf("locationId" to location)) }.getOrNull() else null }
        loadBoard()
        units = unitsCall.await() ?: units
        held = heldCall.await()?.items ?: emptyList()
        val stayKinds = setOf("boarding", "daycare", "homestay")
        val families = (requestsCall.await()?.items ?: emptyList()).count { it.kind in stayKinds && it.shownStatus == "pending" }
        familyWaiting = families + (onlineCall.await()?.count ?: 0)
        homeVisits = (homeCall.await()?.visits ?: emptyList()).filter { it.status != "cancelled" }
    }

    // MARK: Header and numbers

    data class Summary(val tonight: Int, val arriving: Int, val leaving: Int, val lateIn: Int, val lateOut: Int)

    val summary = run {
        var tonight = 0
        var arriving = 0
        var leaving = 0
        var lateIn = 0
        var lateOut = 0
        for (stay in stays) {
            if (!(StaysDay.isRoomHolding(stay.status) && !stay.isHome)) continue
            val pets = stay.petCount
            // A pet still here past its day holds its room tonight too.
            if (stay.kind == "boarding" && today in StaysDay.occupied(kind = stay.kind, from = stay.checkInDay, to = stay.heldUntilDay ?: stay.checkOutDay)) tonight += pets
            if (stay.lateArrival == true) lateIn += pets
            else if (stay.checkInDay == today && stay.status != "checked_in") arriving += pets
            if (stay.lateDeparture == true) lateOut += pets
            else if (stay.checkOutDay == today && stay.status == "checked_in") leaving += pets
        }
        Summary(tonight, arriving, leaving, lateIn, lateOut)
    }

    /** Tonight's places are the rooms that take a night: a playroom is for the day. */
    val capacity = units.filter { StaysDay.roomTakes(unitKind = it.unitKind, stayKind = "boarding") }.sumOf { it.seats }

    val headerStats = run {
        val s = summary
        val requests = held.size + familyWaiting
        val stats = mutableListOf<ModuleStat>()
        if (offersSitting) {
            val done = homeVisits.count { it.status == "done" }
            stats.add(
                ModuleStat(
                    id = "home", label = L("Home stay visits today"), value = homeVisits.size.toString(),
                    hint = if (homeVisits.isEmpty()) null else LP(done, "%d done", "%d done (plural)"),
                    icon = "house", accent = Theme.aqua,
                    attention = homeVisits.any { it.status == "missed" || it.late == true },
                    // They are run from Domicilio.
                    action = { navigator.push(ProviderRoute.section(ProviderSection.homeVisits)) },
                ),
            )
        }
        if (offersRooms) {
            stats.add(
                ModuleStat(
                    id = "tonight", label = L("In tonight"), value = if (capacity > 0) "${s.tonight} / $capacity" else "${s.tonight}",
                    hint = if (capacity > 0) LF("%d places free", max(0, capacity - s.tonight)) else null,
                    icon = "bed-double", accent = Theme.lime,
                ),
            )
            // Late ones are still today's to do: counted in, flagged.
            stats.add(
                ModuleStat(
                    id = "arriving", label = L("Arriving today"), value = (s.arriving + s.lateIn).toString(),
                    hint = if (s.lateIn > 0) LF("%d arriving late", s.lateIn) else null,
                    icon = "log-in", accent = Theme.aqua, attention = s.lateIn > 0,
                ),
            )
            stats.add(
                ModuleStat(
                    id = "leaving", label = L("Leaving today"), value = (s.leaving + s.lateOut).toString(),
                    hint = if (s.lateOut > 0) LF("%d still here past their day", s.lateOut) else null,
                    icon = "log-out", accent = Theme.mango, attention = s.lateOut > 0,
                ),
            )
        }
        stats.add(
            ModuleStat(
                id = "requests", label = L("Requests"), value = requests.toString(),
                hint = if (requests > 0) L("Waiting for an answer") else null,
                icon = "mail-question-mark", accent = Theme.pink, attention = requests > 0,
                active = tab.value == StaysTab.requests, action = { tab.value = StaysTab.requests },
            ),
        )
        stats
    }

    // MARK: Tabs and the board's days

    val tabs = buildList {
        add(ToneTabs.Tab(StaysTab.board, L("Board"), icon = "calendar-range"))
        if (offersRooms) {
            add(ToneTabs.Tab(StaysTab.today, L("Today’s care"), icon = "clipboard-check"))
            add(ToneTabs.Tab(StaysTab.rooms, L("Rooms & rules"), icon = "settings-2"))
        }
        // The requests waiting from families, in the tab's pink bubble.
        add(ToneTabs.Tab(StaysTab.requests, L("Requests"), icon = "mail-question-mark", count = familyWaiting))
    }

    @Composable
    fun TabBar() {
        ToneTabs(tabs = tabs, selection = tab, look = ToneTabs.Look.solid, label = L("Stays"))
    }

    @Composable
    fun DayStepper() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(systemImage = "chevron-left", label = L("Earlier"), tint = Theme.blue) { fromDay = StaysDay.add(fromDay, -days) }
            PMButton(L("Today"), variant = ButtonVariant.outline, size = ButtonSize.compact) { fromDay = StaysDay.add(StaysDay.today(), -1) }
            IconCircleButton(systemImage = "chevron-right", label = L("Later"), tint = Theme.blue) { fromDay = StaysDay.add(fromDay, days) }
        }
    }

    PageScroll(maxWidth = 1180.dp, refresh = { load() }) {
        ModuleHeader(
            icon = "bed-double",
            title = L("Stays"),
            subtitle = StaysDay.long(today).staysCapitalizedFirst,
            stats = headerStats,
            storageKey = "stays",
        ) {
            if (canManage) {
                if (offersRooms) {
                    PMButton(L("New stay"), systemImage = "plus") { booking = StaysBookingPreset(unitId = null, day = today) }
                }
                if (offersSitting) {
                    PMButton(L("New home stay"), systemImage = "house") { booking = StaysBookingPreset(unitId = null, day = today, home = true) }
                }
            }
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TabBar()
                if (tab.value == StaysTab.board) DayStepper()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TabBar() }
                if (tab.value == StaysTab.board) DayStepper()
            }
        }
        when (tab.value) {
            StaysTab.board -> {
                val shown = page
                if (shown != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        StaysBoardView(
                            units = units, stays = shown.items ?: emptyList(), fromDay = fromDay, days = days, today = today,
                            canBook = canManage, offersRooms = offersRooms, offersSitting = offersSitting,
                        ) { preset -> booking = preset }
                        StaysTodayMovements(stays = shown.items ?: emptyList(), today = today)
                    }
                } else if (error != null) {
                    ErrorState(message = error ?: "") { load() }
                } else {
                    LoadingState()
                }
            }
            StaysTab.today -> StaysCareTodayView(canCare = workspace.can(Permission.staysCare))
            StaysTab.rooms -> StaysRoomsView(canManage = canManage)
            StaysTab.requests -> StaysRequestsView(held = held)
        }
    }

    booking?.let { preset ->
        AppSheet(onDismissRequest = { booking = null }) {
            if (preset.home) {
                StaysHomeBookingSheet(presetDay = preset.day) { scope.launch { load() } }
            } else {
                StaysBookingSheet(preset = preset) { scope.launch { load() } }
            }
        }
    }

    ReloadsOnScope { load() }
    StaysOnChanged(fromDay) { loadBoard() }
    StaysOnChanged(days) { loadBoard() }
}

// MARK: - Today's arrivals and departures

/** Who comes and goes today; an earlier day's arrival not in yet, or departure not gone yet, first and in red. */
@Composable
private fun StaysTodayMovements(stays: List<StaysStay>, today: String) {
    val navigator = LocalNavigator.current
    // Home stays have no arrivals at the front desk: their visits are Domicilio's.
    val arrivals = stays
        .filter { !it.isHome && ((it.checkInDay == today && (it.status == "confirmed" || it.status == "requested")) || it.lateArrival == true) }
        .sortedByDescending { if (it.lateArrival == true) 1 else 0 }
    val departures = stays
        .filter { !it.isHome && it.status == "checked_in" && (it.checkOutDay == today || it.lateDeparture == true) }
        .sortedByDescending { if (it.lateDeparture == true) 1 else 0 }

    @Composable
    fun Group(title: String, list: List<StaysStay>, departure: Boolean) {
        Card(spacing = 8.dp) {
            StaysEyebrow(text = title, modifier = Modifier.padding(bottom = 4.dp))
            if (list.isEmpty()) Text(L("No one"), style = Theme.text(14), color = Theme.inkSoft)
            for (stay in list) {
                StaysTintedRow(
                    tint = if (stay.late != null) ProviderState.problem.color else (if (departure) Theme.mango else Theme.aqua),
                    edge = if (stay.late != null) ProviderState.problem.color else null,
                    modifier = Modifier.plainButton { navigator.push(ProviderRoute.stay(stay.id)) },
                ) {
                    PetStack(pets = stay.petList.map { Triple(it.petName, it.petSpecies, it.photoUrl) }, size = 40.dp, max = 3)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(stay.petNames, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${stay.clientName ?: ""} · ${stay.unitName ?: L("No room yet")}",
                            style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        StaysLateLine(stay = stay)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        StaysDay.clock(if (departure) stay.checkOutTime else stay.checkInTime),
                        style = brandHeading(18), color = Theme.inkStrong,
                    )
                }
            }
        }
    }

    if (arrivals.isNotEmpty() || departures.isNotEmpty()) {
        StaysGrid(
            items = listOf<@Composable () -> Unit>(
                { Group(L("Arriving today"), arrivals, departure = false) },
                { Group(L("Leaving today"), departures, departure = true) },
            ),
            minimum = 320.dp,
            spacing = 16.dp,
        ) { it() }
    }
}

/** "Jueves 1 de octubre" from "jueves 1 de octubre". */
private val String.staysCapitalizedFirst: String get() = take(1).uppercase() + drop(1)

// MARK: - Small pieces the Stays screens share (Kotlin only)

/**
 * SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)], spacing:)`
 * inside a scrolling page: as many equal columns as fit at `minimum` wide, each cell at the
 * top of its row, rows `rowSpacing` apart.
 */
@Composable
fun <T> StaysGrid(
    items: List<T>,
    minimum: Dp,
    spacing: Dp,
    rowSpacing: Dp = spacing,
    modifier: Modifier = Modifier,
    cell: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
            items.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    chunk.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** SwiftUI `.onChange(of: value)`: runs on a change of `value`, not on the first composition. */
@Composable
fun <T> StaysOnChanged(value: T, action: suspend (T) -> Unit) {
    val first = remember { booleanArrayOf(true) }
    val latest by rememberUpdatedState(action)
    LaunchedEffect(value) {
        if (first[0]) first[0] = false else latest(value)
    }
}
