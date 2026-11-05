package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

//
//  GroupsScreen.kt (iOS GroupsScreen.swift)
//
//  Paseos y clases (web: routes/provider.groups.tsx, components/app/groups/):
//  the day's group walks and classes run from here — who has a place, who
//  arrived or was picked up, the start and the finish for everyone, each
//  dog's report — then what is coming and what still needs its reports.
//  Each pet is still its own visit with its own bill.
//

@Composable
fun GroupsScreen(modifier: Modifier = Modifier) {
    providerPage(L("Walks & classes"), root = true, modifier = modifier) { SvcGroupsPage() }
}

private enum class SvcGroupsTab { today, upcoming, past }

@Composable
private fun SvcGroupsPage() {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val tab = remember { mutableStateOf(SvcGroupsTab.today) }
    var today by remember { mutableStateOf<List<SvcSession>?>(null) }
    var upcoming by remember { mutableStateOf<List<SvcSession>?>(null) }
    var past by remember { mutableStateOf<List<SvcSession>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<SvcMember>>(emptyList()) }
    var planning by remember { mutableStateOf(false) }

    val canView = workspace.can(Permission.appointmentsView)
    val canPlan = workspace.can(Permission.appointmentsCreate)
    val push: (SvcSessionRoute) -> Unit = { nav.push(it.providerRoute) }

    if (!canView) {
        PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see appointments.")) }
        return
    }

    /** Today, the next two weeks, the last two weeks (the server takes up to two months at a time). */
    suspend fun load() {
        val zone = workspace.timeZone
        val start = LocalDate.now(zone).atStartOfDay(zone)
        fun day(offset: Int): String = DateText.iso(start.plusDays(offset.toLong()).toInstant())
        suspend fun fetch(from: String, to: String): List<SvcSession> =
            API.get("/provider-sessions", mapOf("from" to from, "to" to to, "locationId" to workspace.locationId))
        try {
            today = fetch(day(0), day(1))
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (today == null) failed = errorText(e)
        }
        upcoming = attempt { fetch(day(1), day(15)) } ?: upcoming
        past = attempt { fetch(day(-14), day(0)) } ?: past
    }

    PageScroll(refresh = { load() }) {
        // MARK: Header
        val todayList = today ?: emptyList()
        val dogs = todayList.sumOf { it.size }
        val waiting = (todayList + (upcoming ?: emptyList())).sumOf { it.waitlistCount ?: 0 }
        val reports = (todayList + (past ?: emptyList())).sumOf { it.reportsMissing ?: 0 }
        ModuleHeader(
            icon = "users-round",
            title = L("Walks & classes"),
            subtitle = L("Group walks and classes: who comes, who arrived and their reports"),
            accent = Theme.lime,
            stats = listOf(
                ModuleStat(
                    id = "today", label = L("Today"), value = today?.let { "${it.size}" } ?: "—",
                    hint = if (today == null) null else LP(dogs, "1 dog", "%d dogs"),
                    icon = "calendar-days", accent = Theme.lime, active = tab.value == SvcGroupsTab.today, action = { tab.value = SvcGroupsTab.today },
                ),
                ModuleStat(
                    id = "waiting", label = L("Waitlist"), value = if (upcoming == null) "—" else "$waiting", icon = "hourglass", accent = Theme.mango,
                    attention = waiting > 0, active = tab.value == SvcGroupsTab.upcoming, action = { tab.value = SvcGroupsTab.upcoming },
                ),
                ModuleStat(
                    id = "reports", label = L("Reports to fill"), value = if (past == null) "—" else "$reports", icon = "clipboard-list", accent = Theme.pink,
                    attention = reports > 0, active = tab.value == SvcGroupsTab.past, action = { tab.value = SvcGroupsTab.past },
                ),
            ),
            storageKey = "groups",
        ) {
            if (canPlan) {
                PMButton(L("New session"), systemImage = "plus") { planning = true }
            }
        }
        ToneTabs(
            tabs = listOf(
                ToneTabs.Tab(SvcGroupsTab.today, L("Today"), icon = "calendar-days"),
                ToneTabs.Tab(SvcGroupsTab.upcoming, L("Coming up"), icon = "calendar-clock"),
                ToneTabs.Tab(SvcGroupsTab.past, L("Past"), icon = "history"),
            ),
            selection = tab,
            look = ToneTabs.Look.solid,
        )

        when (tab.value) {
            SvcGroupsTab.today -> {
                val shown = today
                if (shown != null) {
                    if (shown.isEmpty()) {
                        EmptyState(
                            systemImage = "dog",
                            title = L("No group walks or classes today."),
                            message = L("Plan one: families can take a place from the app, and you add the rest."),
                            actionTitle = if (canPlan) L("New session") else null,
                            action = { planning = true },
                            accent = Theme.lime,
                            panel = true,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (session in shown) {
                                key(session.id) { SvcTodaySessionCard(sessionId = session.id, push = push) }
                            }
                        }
                    }
                } else if (failed != null) {
                    SvcLoadError(message = L("The sessions couldn’t be loaded.")) { load() }
                } else {
                    LoadingState()
                }
            }
            SvcGroupsTab.upcoming -> SvcSessionDays(
                sessions = upcoming, past = false, canPlan = canPlan, onPlan = { planning = true },
                onOpen = { push(SvcSessionRoute.session(it)) },
            )
            SvcGroupsTab.past -> SvcSessionDays(
                sessions = past, past = true, canPlan = false, onPlan = {},
                onOpen = { push(SvcSessionRoute.session(it)) },
            )
        }
    }

    if (planning) {
        AppSheet(onDismissRequest = { planning = false }) {
            SvcSessionFormSheet(session = null, people = svcSessionLeaders(members, workspace = workspace)) { saved -> push(SvcSessionRoute.session(saved)) }
        }
    }
    ReloadsOnScope { load() }
    LaunchedEffect(Unit) { members = svcLoadMembers(workspace) }
}

/** Swift `try?` on an await: the answer, or null when it failed (a cancel still cancels). */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** Where the page pushes: a session of its own, or a visit, a client. */
sealed class SvcSessionRoute {
    data class session(val id: String) : SvcSessionRoute()
    data class provider(val route: ProviderRoute) : SvcSessionRoute()

    /**
     * The page it opens (Kotlin only): a session has no `ProviderRoute` of its own, so it is
     * pushed as a page (iOS `.navigationDestination` → `SvcSessionScreen`).
     */
    val providerRoute: ProviderRoute
        get() = when (this) {
            is session -> {
                val sessionId = id
                ProviderRoute.page("group-session:$sessionId") { SvcSessionScreen(sessionId = sessionId) }
            }
            is provider -> route
        }
}

/** Who can lead a session: the people who take visits here (only oneself for someone who sees just their own). */
fun svcSessionLeaders(members: List<SvcMember>, workspace: Workspace): List<SvcMember> {
    val here = workspace.locationId?.let { location ->
        members.filter { (it.locationIds ?: emptyList()).isEmpty() || (it.locationIds ?: emptyList()).contains(location) }
    } ?: members
    val takers = here.filter { it.can(Permission.appointmentsComplete) }
    val anyone = workspace.can(Permission.appointmentsAssignAnyone)
    return (if (takers.isEmpty()) here else takers).filter { anyone || it.isCurrentMember == true }
}

// MARK: - Sessions by day

/**
 * Sessions by day (web `SessionDays`): what's coming (with places and the
 * waitlist), or what passed (with reports to fill), one card per day.
 */
@Composable
private fun SvcSessionDays(
    sessions: List<SvcSession>?,
    past: Boolean,
    canPlan: Boolean,
    onPlan: () -> Unit,
    onOpen: (String) -> Unit,
) {
    if (sessions == null) {
        LoadingState()
        return
    }
    if (sessions.isEmpty()) {
        EmptyState(
            systemImage = if (past) "history" else "calendar-clock",
            title = if (past) L("No group walks or classes in the last two weeks.") else L("Nothing planned for the next two weeks."),
            actionTitle = if (!past && canPlan) L("New session") else null,
            action = onPlan,
            accent = Theme.lime,
            panel = true,
        )
        return
    }
    val days = sessions.groupBy { DateText.dayKey(DateText.parse(it.startsAt) ?: Instant.now()) }
    val keys = if (past) days.keys.sortedDescending() else days.keys.sorted()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for (dayKey in keys) {
            val list = (days[dayKey] ?: emptyList()).let { group -> if (past) group.sortedByDescending { it.startsAt } else group.sortedBy { it.startsAt } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(SvcTime.shortDay(list.firstOrNull()?.startsAt), size = 12)
                Column(Modifier.listCard()) {
                    val first = list.firstOrNull()?.id
                    for (session in list) {
                        key(session.id) {
                            if (session.id != first) RowDivider()
                            SvcSessionDayRow(session = session, past = past, modifier = Modifier.plainButton { onOpen(session.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SvcSessionDayRow(session: SvcSession, past: Boolean, modifier: Modifier = Modifier) {
    val small = Theme.text(12)
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (session.active) 1f else 0.6f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            DateText.time(session.startsAt),
            style = brandHeading(14).copy(fontFeatureSettings = "tnum"),
            color = Theme.inkStrong,
            modifier = Modifier.width(72.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(session.serviceLabel, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowLayout(spacing = 8.dp) {
                session.assignedMember?.name?.let { SvcSessionMeta("user-round", it, small, Theme.inkSoft, 12) }
                if (session.isPickup) {
                    SvcSessionMeta("house", L("Picked up at home"), small, Theme.inkSoft, 12)
                } else {
                    session.meetingPoint.nonEmpty?.let { SvcSessionMeta("map-pin", it, small, Theme.inkSoft, 12) }
                }
                if (!session.active) Text(L("Session · Cancelled"), style = small, color = Theme.destructive)
            }
        }
        PetStack(pets = session.staying.map { Triple(it.name, it.species, it.photoUrl) }, size = 32.dp, max = 3)
        Column(horizontalAlignment = Alignment.End) {
            val bold = Theme.text(12, FontWeight.Bold)
            Text(session.placesText, style = bold.copy(fontFeatureSettings = "tnum"), color = Theme.inkStrong)
            val missing = session.reportsMissing
            val waitingCount = session.waitlistCount
            if (past && missing != null && missing > 0) {
                Text(LF("%d reports to fill", missing), style = bold, color = Theme.pink)
            } else if (waitingCount != null && waitingCount > 0) {
                Text(LF("%d waiting", waitingCount), style = bold, color = Theme.inkSoft)
            }
        }
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

/** A session's small fact: its icon and one line. */
@Composable
private fun SvcSessionMeta(icon: String, text: String, style: TextStyle, color: Color, iconSize: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, iconSize.dp, tint = color)
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - One of today's sessions, run where it shows

/**
 * One of the day's sessions, run where it shows (web `TodaySessionCard`, on
 * Walks & classes and on Domicilio for a walk that picks its dogs up): its dogs,
 * who arrived or was picked up, the start and finish, and the group report.
 */
@Composable
fun SvcTodaySessionCard(
    sessionId: String,
    push: (SvcSessionRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    var session by remember { mutableStateOf<SvcSessionDetail?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            session = API.get<SvcSessionDetail>("/provider-sessions/$sessionId")
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = errorText(e)
        }
    }

    Box(modifier) {
        val shown = session
        if (shown != null) {
            val meta = Theme.text(12, FontWeight.SemiBold)
            Column(Modifier.alpha(if (shown.active) 1f else 0.7f).listCard()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(44.dp).background(Theme.wash(Theme.lime, 35.0), RoundedCornerShape(Theme.boxRadius)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(if (shown.serviceCategory == "trainer") "graduation-cap" else "dog", 20.dp, tint = Theme.inkStrong)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(shown.serviceLabel, style = brandHeading(18), color = Theme.inkStrong)
                        Text(
                            "${DateText.time(shown.startsAt)} – ${DateText.time(shown.endsAt)}" + (shown.assignedMember?.name?.let { " · $it" } ?: ""),
                            style = Theme.text(14).copy(fontFeatureSettings = "tnum"),
                            color = Theme.inkSoft,
                        )
                        FlowLayout(spacing = 12.dp, modifier = Modifier.padding(top = 2.dp)) {
                            if (shown.isPickup) {
                                SvcSessionMeta("house", LF("Picked up at home · %d stops", shown.staying.size), meta, Theme.inkSoft, 14)
                            } else {
                                shown.meetingPoint.nonEmpty?.let { SvcSessionMeta("map-pin", it, meta, Theme.inkSoft, 14) }
                            }
                            SvcSessionMeta("users-round", shown.placesText, meta, Theme.inkSoft, 14)
                            val waiting = shown.waitlistCount
                            if (waiting != null && waiting > 0) SvcSessionMeta("hourglass", LF("%d waiting", waiting), meta, Theme.inkSoft, 14)
                            if (!shown.active) Text(L("Session · Cancelled"), style = meta, color = Theme.destructive)
                        }
                    }
                    PMButton(L("Details"), systemImage = "chevron-right", variant = ButtonVariant.outline, size = ButtonSize.header) {
                        push(SvcSessionRoute.session(shown.id))
                    }
                }
                RowDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.pets.isEmpty()) {
                        Text(L("No pets yet. Add them from the details, or families take a place from the app."), style = Theme.text(14), color = Theme.inkSoft)
                    } else {
                        if (shown.active && shown.booked > 0 && shown.withThem + shown.doneCount == 0) {
                            Text(
                                if (shown.isPickup) LF("%d of %d picked up", shown.arrived, shown.booked + shown.arrived)
                                else LF("%d of %d here", shown.arrived, shown.booked + shown.arrived),
                                style = Theme.text(14, FontWeight.SemiBold),
                                color = Theme.inkStrong,
                            )
                        }
                        SvcSessionRoster(session = shown, push = push) { load() }
                    }
                }
                if (shown.active && workspace.can(Permission.appointmentsComplete) && (shown.withThem + shown.doneCount + shown.arrived + shown.booked) > 0) {
                    RowDivider()
                    SvcSessionMoveButtons(session = shown, canRun = true, modifier = Modifier.fillMaxWidth().padding(12.dp)) { load() }
                }
            }
        } else if (failed != null) {
            SvcLoadError(message = L("This session couldn’t be loaded.")) { load() }
        } else {
            val shape = RoundedCornerShape(Theme.cardRadius)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 192.dp)
                    .background(Theme.surface, shape)
                    .border(1.dp, Theme.borderSoft, shape),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Theme.inkSoft, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
    }

    LaunchedEffect(sessionId) { load() }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { load() } } }
}

// MARK: - The day's group sessions on Domicilio

/**
 * The day's group walks and classes on Domicilio, the day's route (web: groups/group-sessions-pointer.tsx).
 * A walk that picks its dogs up is part of the route: its stops in order, "on my way", picked up, the start
 * and the finish, right here. One that meets somewhere is a line that opens Walks & classes.
 */
@Composable
fun SvcGroupSessionsPointer(
    dayKey: String,
    timeZone: String?,
    push: (SvcSessionRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    var sessions by remember { mutableStateOf<List<SvcSession>>(emptyList()) }

    /** The business's day, midnight to midnight on its clock. */
    suspend fun load() {
        val zone = SvcTime.zone(timeZone)
        val parts = dayKey.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return
        val start = runCatching { LocalDate.of(parts[0], parts[1], parts[2]).atStartOfDay(zone) }.getOrNull() ?: return
        val end = start.plusDays(1)
        val loaded = attempt {
            API.get<List<SvcSession>>(
                "/provider-sessions",
                mapOf("from" to DateText.iso(start.toInstant()), "to" to DateText.iso(end.toInstant()), "locationId" to workspace.locationId),
            )
        }
        if (loaded != null) sessions = loaded
    }

    val active = sessions.filter { it.active }
    val pickups = active.filter { it.isPickup }
    val meeting = active.filter { !it.isPickup }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pickups.isNotEmpty()) {
            Text(
                L("Group walks with pickup").uppercase(),
                style = brandText(12, FontWeight.ExtraBold).copy(letterSpacing = (12 * 0.14).sp),
                color = Theme.blue,
            )
            for (session in pickups) {
                key(session.id) { SvcTodaySessionCard(sessionId = session.id, push = push) }
            }
        }
        if (meeting.isNotEmpty()) {
            val dogs = meeting.sumOf { it.staying.size }
            val shape = RoundedCornerShape(Theme.boxRadius)
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton { push(SvcSessionRoute.provider(ProviderRoute.section(ProviderSection.groups))) }
                    .background(Theme.surface, shape)
                    .dashedBorder(1.5.dp, Theme.mix(Theme.blue, 45.0, Theme.borderSoft), Theme.boxRadius, 5.dp, 4.dp)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(Theme.wash(Theme.aqua, 22.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph("dog", 20.dp, tint = Theme.blue)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (meeting.size == 1) L("A group session at a meeting point") else LF("%d group sessions at a meeting point", meeting.size),
                        style = Theme.text(16, FontWeight.Bold),
                        color = Theme.inkStrong,
                    )
                    Text(LF("Dogs: %d. Arrivals and reports are in Walks & classes.", dogs), style = Theme.text(14), color = Theme.inkSoft)
                }
                Glyph("chevron-right", 20.dp, tint = Theme.inkSoft)
            }
        }
    }

    LaunchedEffect("$dayKey|${workspace.scopeVersion}") { load() }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { load() } } }
}

// MARK: - A session's next move

/**
 * A session's next move (web `SessionMoveButtons`): start with the pets that
 * arrived (or "everyone came"), finish for every pet with them, and the group report.
 */
@Composable
fun SvcSessionMoveButtons(
    session: SvcSessionDetail,
    canRun: Boolean,
    modifier: Modifier = Modifier,
    reload: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    fun run(action: String, everyone: Boolean = false) {
        working = true
        scope.launch {
            try {
                val result = SvcAPI.send<SvcSessionMoveResult>(
                    "POST", "/provider-sessions/${session.id}/$action",
                    body = if (everyone) mapOf("everyone" to true) else emptyMap(),
                )
                val moved = result.moved?.size ?: 0
                if (action == "start") {
                    Toast.show(if (moved == 1) L("Started with 1 pet.") else LF("Started with %d pets.", moved))
                } else {
                    Toast.show(if (moved == 1) L("Finished for 1 pet. Its family has its bill.") else LF("Finished for %d pets. Each family has its own bill.", moved))
                }
                result.skipped?.firstOrNull()?.error?.let { Toast.error(it) }
                announceDataChanged()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(if (svcCode(e) == "nobody_arrived") L("Mark who arrived first, or start with everyone.") else svcMessage(e))
            } finally {
                working = false
            }
        }
    }

    if (session.active && canRun) {
        FlowLayout(spacing = 8.dp, modifier = modifier) {
            if (session.withThem + session.doneCount > 0) {
                PMButton(L("Group report"), systemImage = "clipboard-check", variant = ButtonVariant.outline) { reporting = true }
            }
            if (session.withThem > 0) {
                // Arrived after the start (marked from somewhere else): bring them in before finishing.
                if (session.arrived > 0) {
                    PMButton(LF("Bring in the late ones (%d)", session.arrived), systemImage = "play", variant = ButtonVariant.outline, enabled = !working) { run("start") }
                }
                PMButton(L("Finish for everyone"), systemImage = "badge-check", isWorking = working) { run("finish") }
            } else if (session.arrived + session.booked > 0) {
                // Starts with whoever arrived; "everyone came" checks the rest in on the way.
                if (session.booked > 0) {
                    val total = session.arrived + session.booked
                    PMButton(
                        if (session.isPickup) {
                            if (session.arrived > 0) LF("All picked up: start all %d", total) else L("All picked up: start")
                        } else {
                            if (session.arrived > 0) LF("Everyone came: start all %d", total) else L("Everyone came: start")
                        },
                        systemImage = "play",
                        variant = ButtonVariant.outline,
                        enabled = !working,
                    ) { run("start", everyone = true) }
                }
                if (session.arrived > 0) {
                    PMButton(
                        if (session.isPickup) LF("Start the walk with %d", session.arrived) else LF("Start with those here (%d)", session.arrived),
                        systemImage = "play",
                        isWorking = working,
                    ) { run("start") }
                }
            }
        }
        if (reporting) {
            AppSheet(onDismissRequest = { reporting = false }) {
                SvcGroupReportSheet(session = session) { scope.launch { reload() } }
            }
        }
    }
}
