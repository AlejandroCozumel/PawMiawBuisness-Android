package com.pawmiaw.business.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

//
//  AppointmentsListScreen.kt (iOS AppointmentsListScreen.swift)
//
//  Every visit, to find one (web: the Agenda's List view; /provider/appointments
//  lands there): by pet, client or service, upcoming or past, by status (with
//  "Por confirmar"), person or room, and the ones that need a look (asked for
//  a change, double booked, "En línea"). One card: the search and the
//  Upcoming/Past/All switch, the filter chips, then the visits under their
//  day, 20 at a time with infinite scroll, and the server's count at the foot.
//  Each opens its visit page; today's booked ones check in (and waiting ones
//  start) from the row.
//

data class SchedListFilters(
    val q: String = "",
    /** upcoming · past · all */
    val `when`: String = "upcoming",
    /** "" = any status. */
    val status: String = "",
    /** "" = everyone, "none" = nobody yet, else a member id. */
    val person: String = "",
    val room: String = "",
    /** "" · double · requests · online */
    val only: String = "",
)

/** "Citas": the web redirects /provider/appointments to the Agenda's List view; so does this. */
@Composable
fun AppointmentsListScreen(modifier: Modifier = Modifier) {
    ScheduleScreen(filter = ScheduleFilter(view = "list"), modifier = modifier)
}

/** `SchedVisitList.makePager()`. */
object SchedVisitList {
    fun makePager(): Pager<SchedAppointment> = Pager { _, _ -> Page(items = emptyList(), total = 0, hasMore = false) }
}

/** The list itself, shared by the Appointments screen and the Agenda's List view. */
@Composable
fun SchedVisitList(
    pager: Pager<SchedAppointment>,
    filters: MutableState<SchedListFilters>,
    members: List<SchedMember>,
    rooms: List<SchedRoom>,
    modifier: Modifier = Modifier,
    /** The header's numbers for these filters (the Agenda): every visit, and the double booked among them. */
    onCounts: ((Int?, Int?) -> Unit)? = null,
) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var checkingIn by remember { mutableStateOf<SchedAppointment?>(null) }
    var gate by remember { mutableStateOf<SchedGatePrompt?>(null) }
    var loadedSearch by remember { mutableStateOf("") }
    val counts by rememberUpdatedState(onCounts)
    val f = filters.value

    fun update(change: (SchedListFilters) -> SchedListFilters) {
        filters.value = change(filters.value)
    }

    fun baseQuery(): MutableMap<String, String?> {
        val current = filters.value
        val query = mutableMapOf<String, String?>("locationId" to workspace.locationId)
        if (current.`when` == "upcoming") {
            query["from"] = DateText.iso(SchedDay.start(SchedDay.today))
            query["hideMissed"] = "true"
        }
        if (current.`when` == "past") {
            query["to"] = DateText.iso(Instant.now())
            query["order"] = "desc"
        }
        // The search rule of every list: the server searches from two characters.
        ClientSearch.query(current.q)?.let { query["search"] = it }
        if (current.status.isNotEmpty()) query["status"] = current.status
        if (current.person == "none") query["unassigned"] = "true" else if (current.person.isNotEmpty()) query["assignedOrganizationMemberId"] = current.person
        if (current.room.isNotEmpty()) query["roomId"] = current.room
        return query
    }

    suspend fun reload(debounce: Boolean) {
        loadedSearch = filters.value.q
        val query = baseQuery()
        when (filters.value.only) {
            "double" -> query["doubleBooked"] = "true"
            "requests" -> query["requests"] = "true"
            "online" -> query["online"] = "true"
            else -> {}
        }
        val fixed = query.toMap()
        pager.fetch = { offset, limit ->
            val q = fixed.toMutableMap()
            q["limit"] = "$limit"
            q["offset"] = "$offset"
            API.get<Page<SchedAppointment>>("/provider-appointments", q)
        }
        pager.reload()
        val report = counts ?: return
        if (filters.value.only == "double") {
            report(pager.total, pager.total)
        } else {
            val q = baseQuery()
            q["limit"] = "1"
            q["offset"] = "0"
            q["doubleBooked"] = "true"
            val doubled = schedTry { API.get<Page<SchedAppointment>>("/provider-appointments", q) }
            report(pager.total, doubled?.total)
        }
    }

    fun act(visit: SchedAppointment, action: SchedRowAction) {
        when (action) {
            SchedRowAction.checkIn -> checkingIn = visit
            SchedRowAction.start -> scope.launch { SchedVisitOps.startWithGate(visit) { gate = it } }
            // Answering and finishing happen on the visit's page.
            SchedRowAction.open, SchedRowAction.answer, SchedRowAction.finish -> nav.push(ProviderRoute.appointment(visit.id))
        }
    }

    val filterKey = listOf(f.`when`, f.status, f.person, f.room, f.only, workspace.locationId ?: "", workspace.scopeVersion.toString()).joinToString("|")
    LaunchedEffect(filterKey) { reload(debounce = false) }
    Debounced(f.q) { value ->
        if (ClientSearch.query(value) != ClientSearch.query(loadedSearch)) reload(debounce = true)
    }

    Column(modifier.fillMaxWidth().listCard()) {
        // The search and Upcoming · Past · All.
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchField(prompt = L("Pet, client or service"), text = stateOf({ filters.value.q }, { q -> update { it.copy(q = q) } }))
            Segmented(
                options = listOf(
                    Segmented.Option("upcoming", L("Upcoming")),
                    Segmented.Option("past", L("Past")),
                    Segmented.Option("all", L("All")),
                ),
                selection = stateOf({ filters.value.`when` }, { w -> update { it.copy(`when` = w) } }),
                label = L("When"),
            )
        }
        RowDivider()
        // Status in the colours the rows use; then who, where, and what needs a look.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(title = L("Any status"), isSelected = f.status.isEmpty(), tone = Theme.blue) { update { it.copy(status = "") } }
            SchedStatus.all.forEach { status ->
                val tone = ProviderState.appointment(status).color
                FilterChip(title = SchedStatus.label(status), isSelected = f.status == status, tone = tone, dot = tone) {
                    update { it.copy(status = if (it.status == status) "" else status) }
                }
            }
            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(Theme.borderSoft))
            if (members.size > 1) {
                AppMenu(label = {
                    FilterMenuLabel(
                        title = if (f.person.isEmpty()) L("Everyone") else if (f.person == "none") L("Nobody yet") else (members.firstOrNull { it.id == f.person }?.label ?: L("Someone")),
                        selected = f.person.isNotEmpty(),
                    )
                }) {
                    item(L("Everyone")) { update { it.copy(person = "") } }
                    members.forEach { member -> item(member.label) { update { it.copy(person = member.id) } } }
                    item(L("Nobody yet")) { update { it.copy(person = "none") } }
                }
            }
            if (rooms.isNotEmpty()) {
                AppMenu(label = {
                    FilterMenuLabel(
                        title = if (f.room.isEmpty()) L("Any room") else (rooms.firstOrNull { it.id == f.room }?.name ?: L("Room")),
                        selected = f.room.isNotEmpty(),
                    )
                }) {
                    item(L("Any room")) { update { it.copy(room = "") } }
                    rooms.forEach { room -> item(room.name) { update { it.copy(room = room.id) } } }
                }
            }
            FilterChip(title = L("Asked for a change"), isSelected = f.only == "requests", tone = Theme.pink, icon = "message-circle-question") { update { it.copy(only = if (it.only == "requests") "" else "requests") } }
            FilterChip(title = L("Double booked"), isSelected = f.only == "double", tone = Theme.mango, icon = "triangle-alert") { update { it.copy(only = if (it.only == "double") "" else "double") } }
            FilterChip(title = L("Online"), isSelected = f.only == "online", tone = Theme.lavender, icon = "globe") { update { it.copy(only = if (it.only == "online") "" else "online") } }
        }
        RowDivider()

        val items = pager.items
        if (!pager.loadedOnce) {
            LoadingState()
        } else if (pager.error != null && items.isEmpty()) {
            // A failed load is said as one, never as "nothing matches".
            EmptyState(
                systemImage = "triangle-alert",
                title = L("The visits didn’t load"),
                message = L("Check the connection and try again."),
                actionTitle = L("Try again"),
                action = { scope.launch { pager.reload() } },
                accent = Theme.mango,
            )
        } else if (items.isEmpty()) {
            EmptyState(systemImage = "search", title = L("No visits match"), message = L("Try another search, or clear the filters."), accent = Theme.blue)
        } else {
            Column(Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, visit ->
                    key(visit.id) {
                        val day = SchedDay.key(visit.startsAt) ?: ""
                        val startsDay = index == 0 || SchedDay.key(items[index - 1].startsAt) != day
                        if (startsDay) schedDayBand(day) else RowDivider()
                        SchedVisitRow(
                            appointment = visit,
                            action = SchedRowAction.next(visit, canCheckIn = workspace.can(Permission.appointmentsCheckIn), canStart = workspace.can(Permission.appointmentsComplete)),
                            canOpenChart = workspace.can(Permission.clientsView),
                            onAction = { act(visit, it) },
                            modifier = Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(visit) },
                        )
                    }
                }
                PagerFooter(pager)
            }
        }

        val total = pager.total
        if (items.isNotEmpty() && total != null) {
            RowDivider()
            Text(
                if (pager.isLoading) L("Updating…") else LP(total, "1 visit", "%d visits"),
                style = Theme.text(14, FontWeight.Bold).monospacedDigit(),
                color = Theme.inkStrong,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    checkingIn?.let { visit ->
        AppSheet(onDismissRequest = { checkingIn = null }) {
            SchedCheckInSheet(appointment = visit, canStart = workspace.can(Permission.appointmentsComplete), onGate = { prompt ->
                scope.launch {
                    delay(450)
                    gate = prompt
                }
            })
        }
    }
    gate?.let { prompt ->
        AppSheet(onDismissRequest = { gate = null }) { key(prompt.id) { SchedGateSheet(prompt = prompt) } }
    }
}
