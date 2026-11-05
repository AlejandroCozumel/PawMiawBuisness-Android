package com.pawmiaw.business.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.services.SvcSessionFormSheet
import com.pawmiaw.business.services.SvcSessionScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.ceil

//
//  ScheduleScreen.kt (iOS ScheduleScreen.swift)
//
//  The Agenda (web provider.schedule.tsx): one place's visits by day (by
//  person or by room), by week, or as a list to find any visit. Its numbers
//  (visits, the next free time, families asking for a change, online
//  bookings to confirm, double bookings), the families asking for a change,
//  the online requests inbox with its three answers, booking a visit or a
//  walk-in, a group session (Paseos y clases' own form), and moving a visit
//  through its edit sheet (no dragging here). Opened from another screen it
//  starts on that screen's day, person, room and view (`ScheduleFilter`).
//

@Composable
fun ScheduleScreen(
    /** Opened on a day, a person or a room, in a view (`ProviderRoute.schedule`). */
    filter: ScheduleFilter = ScheduleFilter(),
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    providerPage(L("Schedule"), root = true, modifier = modifier) {
        if (workspace.can(Permission.appointmentsView)) {
            RequireLocation { key(filter) { SchedAgendaPage(filter = filter) } }
        } else {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see visits.")) }
        }
    }
}

/** iOS `@AppStorage`: a remembered setting of this device. */
private class SchedStored<T>(private val read: () -> T, private val write: (T) -> Unit) {
    private val state = mutableStateOf(read())
    var value: T
        get() = state.value
        set(v) {
            state.value = v
            write(v)
        }
}

private fun storedString(key: String, default: String) = SchedStored(
    read = { AppContext.defaults.getString(key, default) ?: default },
    write = { AppContext.defaults.edit().putString(key, it).apply() },
)

private fun storedBool(key: String, default: Boolean) = SchedStored(
    read = { AppContext.defaults.getBoolean(key, default) },
    write = { AppContext.defaults.edit().putBoolean(key, it).apply() },
)

private class SchedAgendaModel(val filter: ScheduleFilter) {
    val workspace = Workspace.shared

    private val storedViewSetting = storedString("pawmiaw.negocios.agenda.view", "day")
    private val bySetting = storedString("pawmiaw.negocios.agenda.by", "staff")
    private val showCancelledSetting = storedBool("pawmiaw.negocios.agenda.cancelled", false)
    var storedView: String
        get() = storedViewSetting.value
        set(v) { storedViewSetting.value = v }
    var by: String
        get() = bySetting.value
        set(v) { bySetting.value = v }
    var showCancelled: Boolean
        get() = showCancelledSetting.value
        set(v) { showCancelledSetting.value = v }

    /** A view the asking screen chose (web: the URL's), not remembered as the person's own. */
    var chosenView by mutableStateOf<String?>(null)
    var day by mutableStateOf(SchedDay.today)
    var person by mutableStateOf("")

    var appointments by mutableStateOf<List<SchedAppointment>>(emptyList())
    var sessions by mutableStateOf<List<SchedSession>>(emptyList())
    var load by mutableStateOf<SchedLoad?>(null)
    var loadedRange by mutableStateOf<String?>(null)
    var rangeError by mutableStateOf<String?>(null)
    var members by mutableStateOf<List<SchedMember>>(emptyList())
    var rooms by mutableStateOf<List<SchedRoom>>(emptyList())
    var availability by mutableStateOf<SchedAvailability?>(null)
    var asking by mutableStateOf<List<SchedAppointment>>(emptyList())
    var online by mutableStateOf<SchedOnlineRequestList?>(null)
    var hasClients by mutableStateOf<Boolean?>(null)

    var listFilters by mutableStateOf(SchedListFilters())
    val listPager = SchedVisitList.makePager()
    var listTotal by mutableStateOf<Int?>(null)
    var listDoubled by mutableStateOf<Int?>(null)

    var booking by mutableStateOf<SchedBookingDraft?>(null)
    var openRequest by mutableStateOf<SchedOnlineRequest?>(null)
    var gate by mutableStateOf<SchedGatePrompt?>(null)
    var planningSession by mutableStateOf(false)
    var openedSession by mutableStateOf<String?>(null)

    init {
        applyFilter()
    }

    // MARK: What is shown

    val view: String get() = chosenView ?: storedView
    val today: String get() = SchedDay.today
    val rangeDays: List<String> get() = if (view == "week") (0 until 7).map { SchedDay.addDays(day, it) } else listOf(day)
    val rangeKey: String get() = "$view|${rangeDays.firstOrNull() ?: ""}|$person|${workspace.locationId ?: ""}|${workspace.scopeVersion}"
    val canAssignAnyone: Boolean get() = workspace.can(Permission.appointmentsAssignAnyone)
    val canCreate: Boolean get() = workspace.can(Permission.appointmentsCreate) && (canAssignAnyone || workspace.can(Permission.appointmentsAssignSelf))
    val me: SchedMember? get() = members.firstOrNull { it.isMe }

    /** People who take visits here; someone who sees only their own sees just themselves. */
    val people: List<SchedMember>
        get() {
            val takers = SchedData.visitTakers(members, locationId = workspace.locationId)
            return if (canAssignAnyone) takers else takers.filter { it.isMe }
        }

    val blocks: List<SchedBlock>
        get() {
            val shownSessions = sessions.filter { s ->
                person.isEmpty() || (if (person == "none") s.assignedMember == null else s.assignedMember?.id == person)
            }
            val days = rangeDays.toSet()
            return SchedBlock.fold(appointments, sessions = shownSessions).filter { days.contains(it.day) }
        }
    val shownBlocks: List<SchedBlock> get() = if (showCancelled) blocks else blocks.filter { !it.gone }
    val cancelledCount: Int get() = blocks.count { it.gone }
    val activeCount: Int get() = appointments.count { !it.isGone }
    val doubledCount: Int get() = appointments.count { !it.isGone && it.doubleBooked == true }
    val onlineWaiting: Int get() = online?.count ?: 0

    fun openFor(day: String): Pair<List<SchedRange>, Pair<String?, Boolean>?> =
        SchedHours.day(day, load = load, closures = null, availability = availability)

    /** A person's working ranges that day (null = the place's) and why they don't work. */
    fun workFor(memberId: String, day: String): Pair<List<SchedMinuteRange>?, String?> {
        val entry = load?.people?.firstOrNull { it.id == memberId }?.day(day) ?: return Pair(null, null)
        val note = entry.timeOff?.let { off -> off.reason?.let { LF("Off · %@", it) } ?: L("Day off") } ?: L("Not working")
        return Pair(if (entry.timeOff != null) emptyList() else SchedHours.minutes(entry.ranges ?: emptyList()), note)
    }

    fun closedNote(day: String): String? {
        val hours = openFor(day)
        val closure = hours.second
        if (closure != null) {
            if (closure.second) return closure.first?.let { LF("Closed · %@", it) } ?: L("Closed")
            return closure.first?.let { LF("Special hours · %@", it) } ?: L("Special hours")
        }
        return if (hours.first.isEmpty()) L("Closed") else null
    }

    val byRooms: Boolean get() = view == "day" && by == "rooms" && rooms.isNotEmpty()
    /** Who a visit booked from the grid is for, unless the column says. */
    val creator: String? get() = if (person.isNotEmpty() && person != "none") person else me?.id

    val columns: List<SchedColumn>
        get() {
            if (view == "week") {
                return rangeDays.map { key ->
                    SchedColumn(
                        id = "day:$key", title = SchedDay.weekColumnLabel(key), detail = closedNote(key), kind = SchedColumn.Kind.day, day = key,
                        open = SchedHours.minutes(openFor(key).first), createMemberId = creator,
                    )
                }
            }
            val open = SchedHours.minutes(openFor(day).first)
            if (byRooms) {
                val list = rooms.map { room ->
                    SchedColumn(
                        id = "room:${room.id}", title = room.name, detail = if ((room.capacity ?: 1) > 1) LF("%@ at once", (room.capacity ?: 1).toString()) else null,
                        kind = SchedColumn.Kind.room, roomId = room.id, day = day, open = open, createMemberId = creator,
                    )
                }.toMutableList()
                if (appointments.any { it.roomId == null }) {
                    list.add(SchedColumn(id = "room:none", title = L("No room"), kind = SchedColumn.Kind.room, roomId = null, day = day, open = open, createMemberId = creator))
                }
                return list
            }
            val shown = if (person.isNotEmpty() && person != "none") people.filter { it.id == person } else people
            val list = shown.map { member ->
                val work = workFor(member.id, day)
                val detail = if ((member.maxConcurrentVisits ?: 1) > 1) LF("%@ at once", (member.maxConcurrentVisits ?: 1).toString()) else member.professionalTitle
                SchedColumn(
                    id = "person:${member.id}", title = member.label, detail = detail, kind = SchedColumn.Kind.person, memberId = member.id, day = day, open = open,
                    working = work.first, offNote = work.second, image = member.image, createMemberId = member.id,
                )
            }.toMutableList()
            if (canAssignAnyone && (person == "none" || (person.isEmpty() && appointments.any { it.assignedMember?.id == null }))) {
                list.add(SchedColumn(id = "person:none", title = L("Nobody yet"), detail = L("Whoever is free"), kind = SchedColumn.Kind.unassigned, day = day, open = open, createMemberId = creator))
            }
            return list
        }

    /** The rows the grid draws: the days' opening hours, stretched to the visits shown. */
    val gridRange: SchedGrid.Range
        get() = SchedGrid.range(shownBlocks, open = rangeDays.flatMap { SchedHours.minutes(openFor(it).first) })

    /** The first start, inside the open ranges (the chosen person's working ones), where someone is free. */
    val nextFree: Int?
        get() {
            val loaded = load
            if (view != "day" || loaded == null || day < today) return null
            val open = SchedHours.minutes(openFor(day).first)
            if (open.isEmpty()) return null
            val named = if (person.isNotEmpty() && person != "none") person else null
            val ranges = named?.let { workFor(it, day).first } ?: open
            val length = availability?.defaultAppointmentDurationMinutes ?: 30
            val earliest = if (day == today) (ceil(SchedDay.minuteOfDay(Instant.now()) / 15.0) * 15).toInt() else 0
            for (range in ranges) {
                var minute = maxOf(range.start, earliest)
                while (minute + length <= range.end) {
                    val start = SchedDay.at(day, minute = minute)
                    val slot = SchedSlot(id = null, start = start, end = start.plusSeconds(length * 60L), memberId = named, roomId = null)
                    if (SchedConflicts.find(slot, loaded).isEmpty()) return minute
                    minute += 15
                }
            }
            return -1
        }

    val nextFreeValue: String
        get() {
            if (view == "day" && day < today) return "—"
            if (view == "day" && load != null && openFor(day).first.isEmpty()) return L("Closed")
            val free = nextFree ?: return "—"
            return if (free == -1) L("Full") else SchedDay.timeLabel(free)
        }

    val nextFreeHint: String?
        get() {
            val free = nextFree
            if (free != null && free >= 0) return L("Tap to book it")
            if (view != "day") return L("On the day view")
            if (day < today) return L("This day is over")
            if (load != null && openFor(day).first.isEmpty()) return L("Pick another day")
            return null
        }

    /** The asking screen's day, view, person and room, once. */
    fun applyFilter() {
        val wanted = filter.day
        if (wanted != null && wanted.length == 10) day = wanted
        val chosen = filter.view
        if (chosen != null && listOf("day", "week", "list").contains(chosen)) chosenView = chosen
        val grouping = filter.by
        if (grouping != null && listOf("staff", "rooms").contains(grouping) && view != "list") by = grouping
        val room = filter.roomId
        if (room != null) {
            if (view == "list") listFilters = listFilters.copy(room = room) else by = "rooms"
        }
        val member = filter.memberId
        if (member != null) {
            if (view == "list") listFilters = listFilters.copy(person = member)
            if (canAssignAnyone) person = member
            if (filter.roomId == null && view != "list") by = "staff"
        }
    }

    fun switchView(next: String) {
        chosenView = null
        storedView = next
        if (next == "list") {
            listTotal = null
            listDoubled = null
        }
    }

    fun openList(only: String? = null, status: String? = null) {
        var filters = SchedListFilters()
        if (only != null) filters = filters.copy(only = if (view == "list" && listFilters.only == only) "" else only)
        if (status != null) filters = filters.copy(status = if (view == "list" && listFilters.status == status) "" else status)
        filters = filters.copy(`when` = "upcoming")
        listFilters = filters
        switchView("list")
    }

    // MARK: Booking

    fun book(day: String, minute: Int? = null, memberId: String? = null, roomId: String? = null) {
        if (!canCreate) return
        val creator = if (person.isNotEmpty() && person != "none") person else me?.id
        booking = SchedBookingDraft(day = day, startMinute = minute, memberId = memberId ?: creator, roomId = roomId)
    }

    // MARK: Loading

    suspend fun reloadAll() {
        if (!workspace.can(Permission.appointmentsView)) return
        val location = workspace.locationId
        val readsClients = workspace.can(Permission.clientsView)
        coroutineScope {
            val team = async { SchedData.members() }
            val places = async { SchedData.rooms(locationId = location) }
            val week = async { SchedData.availability(locationId = null) }
            val clientsTotal = async { if (readsClients) SchedData.clientsTotal() else null }
            val inbox = async { loadInbox() }
            members = team.await()
            rooms = places.await()
            availability = week.await()
            hasClients = if (readsClients) ((clientsTotal.await() ?: 1) > 0) else null
            inbox.await()
        }
        // The first load is the range task's; after that (another screen changed something) it is read again here.
        if (loadedRange == rangeKey) loadRange()
        if (view == "list" && listPager.loadedOnce) listPager.reload()
    }

    suspend fun loadInbox() {
        val location = workspace.locationId
        coroutineScope {
            val changes = async { SchedData.familyAsking(locationId = location) }
            val requests = async { schedTry { API.get<SchedOnlineRequestList>("/provider-online-booking/requests", mapOf("locationId" to location)) } }
            asking = changes.await()
            online = requests.await()
        }
    }

    suspend fun loadRange() {
        if (view == "list") return
        val key = rangeKey
        val days = rangeDays
        val from = SchedDay.start(days.firstOrNull() ?: day)
        val to = SchedDay.start(SchedDay.addDays(days.lastOrNull() ?: day, 1))
        val location = workspace.locationId
        val who = person
        val groups = workspace.offers(BusinessCategory.walker, BusinessCategory.trainer)
        try {
            coroutineScope {
                val visits = async { visits(from = from, to = to, person = who, locationId = location) }
                val groupSessions = async {
                    if (groups) schedTry { API.get<List<SchedSession>>("/provider-sessions", mapOf("from" to DateText.iso(from), "to" to DateText.iso(to), "locationId" to location)) } ?: emptyList()
                    else emptyList()
                }
                val taken = async { schedTry { SchedData.load(from = from, to = to, locationId = location) } }
                val loadedVisits = visits.await()
                val loadedSessions = groupSessions.await()
                val loadedLoad = taken.await()
                if (key != rangeKey) return@coroutineScope
                appointments = loadedVisits
                sessions = loadedSessions
                load = loadedLoad
                loadedRange = key
                rangeError = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            rangeError = errorText(error)
            if (loadedRange != null) Toast.error(error)
        }
    }

    /** Every visit between two times, a hundred at a time until there are no more. */
    private suspend fun visits(from: Instant, to: Instant, person: String, locationId: String?): List<SchedAppointment> {
        val items = mutableListOf<SchedAppointment>()
        var offset = 0
        while (offset < 2000) {
            val query = mutableMapOf<String, String?>("limit" to "100", "offset" to "$offset", "from" to DateText.iso(from), "to" to DateText.iso(to), "locationId" to locationId)
            if (person == "none") query["unassigned"] = "true" else if (person.isNotEmpty()) query["assignedOrganizationMemberId"] = person
            val page: Page<SchedAppointment> = API.get("/provider-appointments", query)
            items += page.items
            if (page.hasMore != true || page.items.size < 100) break
            offset += 100
        }
        return items
    }
}

@Composable
private fun SchedAgendaPage(filter: ScheduleFilter) {
    val model = remember { SchedAgendaModel(filter) }
    val workspace = Workspace.shared
    val regular = LocalSizeClass.current == SizeClass.regular
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val view = model.view

    ReloadsOnScope { model.reloadAll() }
    val rangeKey = model.rangeKey
    LaunchedEffect(rangeKey) { model.loadRange() }

    Box(Modifier.fillMaxSize()) {
        PageScroll(maxWidth = if (regular) 1600.dp else 820.dp, refresh = { model.reloadAll() }) {
            SchedAgendaHeader(model)
            SchedAgendaControls(model)
            if (view != "list" && model.asking.isNotEmpty()) {
                SchedAskingCard(visits = model.asking, onOpen = { nav.push(ProviderRoute.appointment(it.id)) }, onSeeAll = { model.openList(only = "requests") })
            }
            val online = model.online
            if (view != "list" && online != null && (online.count ?: 0) > 0) {
                SchedRequestsInbox(list = online) { model.openRequest = it }
            }
            if (model.hasClients == false) {
                EmptyState(
                    systemImage = "paw-print",
                    title = L("Add your first client and pet"),
                    message = L("Visits are booked for a client’s pet. Add one in Clients first."),
                    actionTitle = if (workspace.can(Permission.clientsView)) L("Go to Clients") else null,
                    action = { nav.push(ProviderRoute.section(ProviderSection.clients)) },
                    accent = Theme.lime,
                    panel = true,
                )
            } else if (view == "list") {
                SchedVisitList(
                    pager = model.listPager,
                    filters = stateOf({ model.listFilters }, { model.listFilters = it }),
                    members = model.people,
                    rooms = model.rooms,
                ) { total, doubled ->
                    model.listTotal = total
                    model.listDoubled = doubled
                }
            } else {
                SchedAgendaGridCard(model)
            }
        }

        // A session opens as its own page (iOS `.navigationDestination(item:)`; it has no route).
        val nav = LocalNavigator.current
        LaunchedEffect(model.openedSession) {
            val id = model.openedSession ?: return@LaunchedEffect
            model.openedSession = null
            nav.push(ProviderRoute.page("session:$id") { SvcSessionScreen(sessionId = id) })
        }
    }

    model.booking?.let { draft ->
        AppSheet(onDismissRequest = { model.booking = null }) {
            key(draft.id) {
                SchedBookingSheet(draft = draft, onGate = { prompt ->
                    scope.launch {
                        delay(450)
                        model.gate = prompt
                    }
                })
            }
        }
    }
    model.openRequest?.let { request ->
        AppSheet(onDismissRequest = { model.openRequest = null }) {
            SchedRequestSheet(request = request, onOpenVisit = { id ->
                scope.launch {
                    delay(400)
                    nav.push(ProviderRoute.appointment(id))
                }
            })
        }
    }
    model.gate?.let { prompt ->
        AppSheet(onDismissRequest = { model.gate = null }) { key(prompt.id) { SchedGateSheet(prompt = prompt) } }
    }
    if (model.planningSession) {
        AppSheet(onDismissRequest = { model.planningSession = false }) {
            SvcSessionFormSheet(session = null, people = emptyList()) { id ->
                scope.launch {
                    delay(450)
                    model.openedSession = id
                }
            }
        }
    }
}

/** A page pushed over the Agenda that has no route of its own (a group session). */

// MARK: Header and numbers

@Composable
private fun SchedAgendaHeader(model: SchedAgendaModel) {
    val workspace = Workspace.shared
    val view = model.view
    val day = model.day
    val days = model.rangeDays
    val dateText = when (view) {
        "list" -> L("Every visit, upcoming and past.")
        "week" -> SchedDay.weekLabel(days.firstOrNull() ?: day, days.lastOrNull() ?: day)
        else -> SchedDay.longLabel(day)
    }
    val place = if (workspace.multiLocation) workspace.selectedLocation?.name else null
    ModuleHeader(
        icon = "calendar-days",
        title = L("Schedule"),
        subtitle = listOf(dateText, place).mapNotNull { it.nonEmpty }.joinToString(" · "),
        stats = schedAgendaStats(model),
        storageKey = "schedule",
    ) {
        if (model.canCreate) {
            if (workspace.offers(BusinessCategory.walker, BusinessCategory.trainer)) {
                PMButton(L("Group session"), systemImage = "users-round", variant = ButtonVariant.outline) { model.planningSession = true }
            }
            if (model.hasClients != false) {
                PMButton(L("New visit"), systemImage = "plus") { model.book(day = if (view == "list") model.today else day) }
            }
        }
    }
}

@Composable
private fun schedAgendaStats(model: SchedAgendaModel): List<ModuleStat> {
    val view = model.view
    val day = model.day
    val today = model.today
    val asking = model.asking
    val listFilters = model.listFilters
    val stats = mutableListOf(
        ModuleStat(
            "visits",
            label = if (view == "week") L("Visits in these 7 days") else if (view == "day") (if (day == today) L("Visits today") else L("Visits that day")) else L("Visits"),
            value = if (view == "list") (model.listTotal?.toString() ?: "—") else model.activeCount.toString(),
            icon = "calendar-days",
            accent = Theme.aqua,
        ),
    )
    if (view != "list") {
        val free = model.nextFree
        stats.add(
            ModuleStat(
                "free",
                label = L("Next free time"),
                value = model.nextFreeValue,
                hint = model.nextFreeHint,
                icon = "clock-3",
                accent = Theme.lime,
                attention = free == -1,
                action = if (free != null && free >= 0 && model.canCreate) ({
                    model.book(day = day, minute = free, memberId = if (model.person.isNotEmpty() && model.person != "none") model.person else null)
                }) else null,
            )
        )
    }
    stats.add(
        ModuleStat(
            "requests",
            label = L("Asked for a change"),
            value = asking.size.toString(),
            hint = if (asking.isEmpty()) null else L("Families waiting on you"),
            icon = "message-circle-question",
            accent = Theme.pink,
            attention = asking.isNotEmpty(),
            active = view == "list" && listFilters.only == "requests",
            action = { model.openList(only = "requests") },
        )
    )
    val waiting = model.onlineWaiting
    if (waiting > 0 || (view == "list" && listFilters.status == "requested")) {
        stats.add(
            ModuleStat(
                "online",
                label = L("To confirm"),
                value = waiting.toString(),
                hint = L("Booked online, waiting for you"),
                icon = "globe",
                accent = ProviderState.waiting.color,
                attention = waiting > 0,
                active = view == "list" && listFilters.status == "requested",
                action = { model.openList(status = "requested") },
            )
        )
    }
    val doubled = if (view == "list") model.listDoubled else model.doubledCount
    if (doubled != null) {
        stats.add(
            ModuleStat(
                "double",
                label = L("Double booked"),
                value = doubled.toString(),
                hint = L("Booked over another on purpose"),
                icon = "triangle-alert",
                accent = Theme.mango,
                attention = doubled > 0,
                active = view == "list" && listFilters.only == "double",
                action = { model.openList(only = "double") },
            )
        )
    }
    return stats
}

// MARK: Controls

@Composable
private fun SchedAgendaControls(model: SchedAgendaModel) {
    val view = model.view
    val day = model.day
    val today = model.today
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(
            options = listOf(
                Segmented.Option("day", L("Day"), icon = "rows-3"),
                Segmented.Option("week", L("Week"), icon = "calendar-days"),
                Segmented.Option("list", L("List"), icon = "list"),
            ),
            selection = stateOf({ model.view }, { model.switchView(it) }),
            label = L("View"),
        )
        if (view != "list") {
            val days = model.rangeDays
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DayStepper(
                    isToday = day == today,
                    previous = { model.day = SchedDay.addDays(model.day, if (model.view == "week") -7 else -1) },
                    today = { model.day = SchedDay.today },
                    next = { model.day = SchedDay.addDays(model.day, if (model.view == "week") 7 else 1) },
                )
                PillDatePicker(
                    text = if (view == "week") SchedDay.weekLabel(days.firstOrNull() ?: day, days.lastOrNull() ?: day) else SchedDay.shortLabel(day),
                    date = stateOf({ SchedDay.date(fromKey = model.day) }, { model.day = SchedDay.key(it) }),
                    zone = SchedDay.zone,
                )
            }
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val people = model.people
                if (model.canAssignAnyone && people.size > 1) {
                    val person = model.person
                    AppMenu(label = {
                        PillField(
                            text = if (person.isEmpty()) L("Everyone") else if (person == "none") L("Nobody yet") else (people.firstOrNull { it.id == person }?.label ?: L("Someone")),
                            height = 40.dp,
                        )
                    }) {
                        item(L("Everyone")) { model.person = "" }
                        people.forEach { member -> item(member.label) { model.person = member.id } }
                        item(L("Nobody yet")) { model.person = "none" }
                    }
                }
                if (view == "day" && model.rooms.isNotEmpty()) {
                    Segmented(
                        options = listOf(Segmented.Option("staff", L("Staff"), icon = "users-round"), Segmented.Option("rooms", L("Rooms"), icon = "door-open")),
                        selection = stateOf({ model.by }, { model.by = it }),
                        label = L("Show the day by"),
                    )
                }
                val cancelled = model.cancelledCount
                if (cancelled > 0 || model.showCancelled) {
                    PMButton(
                        if (model.showCancelled) L("Hide cancelled") else LF("Show cancelled (%@)", cancelled.toString()),
                        systemImage = if (model.showCancelled) "eye-off" else "eye",
                        variant = ButtonVariant.outline,
                        size = ButtonSize.compact,
                    ) { model.showCancelled = !model.showCancelled }
                }
            }
        }
    }
}

// MARK: The day and the week

/**
 * The grid's card (web: a standard card around `AgendaGrid`): the day's closure or
 * special hours as a strip on top, then the grid.
 */
@Composable
private fun SchedAgendaGridCard(model: SchedAgendaModel) {
    val nav = LocalNavigator.current
    val loadedRange = model.loadedRange
    val rangeError = model.rangeError
    val columns = model.columns
    val current = loadedRange == model.rangeKey
    val fade by animateFloatAsState(if (current) 1f else 0.6f, tween(200, easing = EaseOut), label = "grid")
    Column(Modifier.fillMaxWidth().listCard()) {
        if (rangeError != null && loadedRange == null) {
            ErrorState(message = rangeError) { model.loadRange() }
        } else if (loadedRange == null) {
            LoadingState()
        } else if (columns.isEmpty()) {
            EmptyState(systemImage = "users-round", title = L("Nobody takes visits in this workspace yet."), accent = Theme.blue)
        } else {
            SchedAgendaDayStrip(model)
            val byRooms = model.byRooms
            SchedAgendaGrid(
                columns = columns,
                blocks = model.shownBlocks,
                range = model.gridRange,
                compact = model.view == "week",
                canCreate = model.canCreate && model.hasClients != false,
                secondary = { block -> if (byRooms) block.memberName else block.room },
                onOpen = { block ->
                    // A visit opens its page; a session opens as one (its pets, its places, its waitlist).
                    val appointmentId = block.appointmentId
                    val sessionId = block.sessionId
                    if (appointmentId != null) nav.push(ProviderRoute.appointment(appointmentId))
                    else if (sessionId != null) model.openedSession = sessionId
                    else nav.push(ProviderRoute.section(ProviderSection.groups))
                },
                onCreate = { column, minute ->
                    model.book(day = column.day, minute = minute, memberId = column.createMemberId, roomId = column.bookRoomId)
                },
                modifier = Modifier.alpha(fade),
            )
        }
    }
}

@Composable
private fun SchedAgendaDayStrip(model: SchedAgendaModel) {
    if (model.view != "day") return
    val day = model.day
    val hours = model.openFor(day)
    val closure = hours.second
    if (closure != null && !closure.second) {
        SchedDayStrip(tone = SchedDayStrip.Tone.special, text = LF("%@: %@.", model.closedNote(day) ?: "", SchedHours.rangesText(hours.first)))
    } else if (closure?.second == true) {
        SchedDayStrip(tone = SchedDayStrip.Tone.closed, text = LF("%@ — families can’t book online, and a visit booked now asks to confirm.", model.closedNote(day) ?: ""))
    } else if (model.load != null && hours.first.isEmpty()) {
        SchedDayStrip(tone = SchedDayStrip.Tone.quiet, text = L("Closed on this day — a visit booked now is marked outside opening hours."))
    }
}
