package com.pawmiaw.business.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*

//
//  HomeBoard.kt (iOS HomeBoard.swift)
//
//  The day's board (web components/app/today/today-board.tsx): families on
//  their way with an emergency first; ‹ Hoy › on a phone's own line; the
//  "Hoy" header with the day's activities as tiles (or, for a business that
//  only sees visits, the visits' four numbers as the agenda's filter); the
//  Agenda card — Todos | Mías and "⋯", the state tabs, a service filter, the
//  sections ("AHORA", "POR COBRAR", "PRÓXIMAS", grooms, stays, the hospital,
//  home visits) and "Listo · 3" — and "Te necesita" beside it (below it on a
//  phone). Home is a summary: each part shows a few rows and "Ver todo"
//  opens its page.
//

/** Rows each part of the board shows before "Ver todo". */
object HomeCap {
    const val rows = 5
}

@Composable
fun HomeBoard(
    today: HomeToday,
    model: HomeModel,
    open: (ProviderRoute) -> Unit,
    /** ‹ Hoy ›: another day opens in place; null when this role can't see the agenda. */
    changeDay: ((String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val actions = LocalHomeActions.current
    val nav = LocalNavigator.current
    val lens = remember { mutableStateOf(HomeLens.everyone) }
    var filter by remember { mutableStateOf<HomeLane?>(null) }
    var saved by remember { mutableStateOf<HomeActivity?>(null) }
    var service by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }

    val permissions = HomePermissions(workspace)
    val categories = workspace.current?.enabledCategories ?: emptyList()
    val memberId = today.viewer?.memberId
    val activityKey = HomeActivityStore.key(workspace.current?.clinicId, memberId)
    val phone = LocalSizeClass.current != SizeClass.regular

    /** Booking takes creating visits and seeing clients; a walk-in also checking in. */
    val canBook = permissions.create && permissions.clients
    val canWalkIn = canBook && permissions.checkIn

    // MARK: Modules and activity

    val modules = (today.hub?.modules ?: listOf("appointments")).mapNotNull { HomeActivity.from(it) }
        .filter { !(it == HomeActivity.grooming && model.groomingFailed) }
    val options = HomeActivity.options(modules)
    /** With more than one activity the header chooses the activity and the agenda has its own state tabs. */
    val hubHeader = options.size > 1

    val activity: HomeActivity = run {
        val chosen = saved
        if (chosen != null && options.contains(chosen)) return@run chosen
        val stored = HomeActivityStore.read(activityKey)
        if (stored != null && options.contains(stored)) return@run stored
        options.firstOrNull() ?: HomeActivity.all
    }

    val showsVisits = modules.contains(HomeActivity.appointments) && activity.shows(HomeActivity.appointments)
    val entries = today.hub?.entries ?: emptyList()
    val allVisits = today.allVisits

    /** Under Todos, a patient whose visit is still on the agenda shows once — as the visit. */
    fun hubEntries(activity: HomeActivity): List<HomeHubEntry> {
        val onAgenda = allVisits.filter { it.laneValue in listOf(HomeLane.upcoming, HomeLane.waiting, HomeLane.inService) }.map { it.id }.toSet()
        return entries.filter { entry ->
            val module = HomeActivity.from(entry.module ?: "") ?: return@filter false
            if (!activity.shows(module)) return@filter false
            val source = entry.sourceAppointmentId
            if (activity == HomeActivity.all && source != null && onAgenda.contains(source)) return@filter false
            true
        }
    }

    val hospitalized = entries.filter { it.module == "hospital" }.mapNotNull { it.sourceAppointmentId }.toSet()

    val groomsNeeding = (model.grooming?.items ?: emptyList()).filter { it.attention != null }
    val lateHomeVisits = (model.homeVisits?.items ?: emptyList()).filter { it.late == true }
    val longestWait = today.lane(HomeLane.waiting).mapNotNull { it.waitingMinutes }.maxOrNull()

    fun count(option: HomeActivity): Int {
        val seen = allVisits.filter { it.laneValue != HomeLane.missed }
        return when (option) {
            HomeActivity.all -> seen.size + hubEntries(HomeActivity.all).size
            HomeActivity.appointments -> seen.size
            HomeActivity.grooming -> seen.count { it.serviceCategory == "groomer" }
            HomeActivity.home -> model.homeVisits?.counts?.total ?: entries.count { it.module == "home" }
            else -> entries.count { it.module == option.rawValue }
        }
    }

    /** The tile's second line: the one thing about that activity worth knowing at a glance. */
    fun hint(option: HomeActivity): String? {
        val counts = today.counts
        return when (option) {
            HomeActivity.appointments -> {
                val late = counts?.late
                val missed = counts?.missed
                if (late != null && late > 0) LF("%d running late", late)
                else if (missed != null && missed > 0) LF("%d missed@home", missed)
                else null
            }
            HomeActivity.grooming -> {
                val needing = groomsNeeding.size
                if (needing > 0) return LF("%d need you", needing)
                val ready = (model.grooming?.items ?: emptyList()).count { it.groomStage == "ready" }
                if (ready > 0) LF("%d ready for pickup", ready) else null
            }
            HomeActivity.stays -> {
                val of = entries.filter { it.module == "stays" }
                val late = of.count { it.late == true }
                val leaving = of.count { it.status == "leaving" }
                if (late > 0) return LF("%d late@stays", late)
                if (leaving > 0) LF("%d leaving today", leaving) else null
            }
            HomeActivity.hospital -> {
                val due = entries.filter { it.module == "hospital" }.sumOf { it.attention ?: 0 }
                if (due == 0) return null
                if (due == 1) L("1 treatment needs someone") else LF("%d treatments need someone", due)
            }
            HomeActivity.home -> {
                val late = model.homeVisits?.counts?.late ?: entries.count { it.module == "home" && it.late == true }
                if (late > 0) LF("%d late@stays", late) else null
            }
            HomeActivity.all -> null
        }
    }

    fun attention(option: HomeActivity): Boolean = when (option) {
        HomeActivity.appointments -> (today.counts?.late ?: 0) > 0
        HomeActivity.grooming -> groomsNeeding.isNotEmpty()
        HomeActivity.all -> false
        HomeActivity.home -> {
            val late = model.homeVisits?.counts?.late
            if (late != null) late > 0 else entries.any { it.module == "home" && it.late == true }
        }
        else -> entries.any { it.module == option.rawValue && it.late == true }
    }

    /** Only a tile the person picks is remembered; a switch made for them (a visit filter) lasts until they leave. */
    fun pick(option: HomeActivity, remember: Boolean = true) {
        saved = option
        if (remember) HomeActivityStore.save(activityKey, option)
        if (option != HomeActivity.appointments) service = null
    }

    fun pickFilter(next: HomeLane?) {
        filter = next
        if (!showsVisits) pick(HomeActivity.appointments, remember = false)
    }

    // MARK: Visits

    val showsLens = today.viewer?.seesAllVisits == true && permissions.start && memberId != null

    val serviceOptions = buildList {
        for (visit in allVisits) {
            val category = visit.serviceCategory
            if (category != null && !contains(category)) add(category)
        }
    }

    val activeService: String? = service?.takeIf { activity == HomeActivity.appointments && serviceOptions.contains(it) }

    fun visible(visit: HomeVisit): Boolean {
        val mine = lens.value == HomeLens.everyone || visit.assigned?.id == null || visit.assigned?.id == memberId
        return mine && (activeService == null || visit.serviceCategory == activeService)
    }

    fun lane(key: HomeLane): List<HomeVisit> = today.lane(key).filter { visible(it) }

    // MARK: Body

    val needs = HomeNeedsYou(today = today, model = model, permissions = permissions, grooms = groomsNeeding, lateHome = lateHomeVisits, open = open)

    val switcher: (@Composable () -> Unit)? = run {
        val change = changeDay ?: return@run null
        val date = today.date ?: return@run null
        ({ homeDayStepper(date = date, today = date, onChange = change) })
    }

    // MARK: The "Hoy" header

    val stats: List<ModuleStat> = if (hubHeader) {
        // What the day holds, by activity. The visit states live in the agenda.
        options.map { option ->
            ModuleStat(
                option.rawValue, label = option.label, value = "${count(option)}", hint = hint(option), icon = option.icon,
                accent = option.accent, attention = attention(option), active = activity == option,
                action = { pick(option); filter = null },
            )
        }
    } else if (!modules.contains(HomeActivity.appointments)) {
        emptyList()
    } else {
        // Only visits here: the header's numbers are the agenda's filter.
        val counts = today.counts
        val late = counts?.late ?: 0
        val missed = counts?.missed ?: 0
        listOf(
            ModuleStat(
                "visits", label = L("Visits today"), value = "${counts?.total ?: 0}",
                hint = if (late > 0) LF("%d running late", late) else (if (missed > 0) LF("%d missed@home", missed) else null),
                icon = "calendar-clock", accent = Theme.aqua, attention = late > 0, active = filter == null, action = { pickFilter(null) },
            ),
            ModuleStat(
                "waiting", label = L("Waiting@home"), value = "${counts?.waiting ?: 0}", hint = longestWait?.let { LF("Longest %d min", it) },
                icon = "clock-3", accent = Theme.mango, attention = (longestWait ?: 0) >= 20, active = filter == HomeLane.waiting,
                action = { pickFilter(if (filter == HomeLane.waiting) null else HomeLane.waiting) },
            ),
            ModuleStat(
                "in", label = HomeText.inServiceTitle(categories), value = "${counts?.inService ?: 0}", icon = "stethoscope", accent = Theme.blue,
                active = filter == HomeLane.inService, action = { pickFilter(if (filter == HomeLane.inService) null else HomeLane.inService) },
            ),
            ModuleStat(
                "pay", label = L("To pay"), value = "${counts?.toPay ?: 0}",
                hint = today.collectedToday?.let { LF("%@ collected today", Money.format(it, today.currency)) },
                icon = "hand-coins", accent = Theme.pink, active = filter == HomeLane.toPay,
                action = { pickFilter(if (filter == HomeLane.toPay) null else HomeLane.toPay) },
            ),
        )
    }

    @Composable
    fun header() {
        val hasActions = canBook || (!phone && switcher != null)
        if (hasActions) {
            ModuleHeader(
                icon = "calendar-clock", title = L("Today"), subtitle = HomeDayKey.long(today.date), accent = Theme.blue, stats = stats, storageKey = "home",
                actions = {
                    if (!phone && switcher != null) switcher()
                    if (canWalkIn && actions != null) {
                        PMButton(L("Walk-in"), systemImage = "zap", variant = ButtonVariant.outline) { actions.walkIn() }
                    }
                    if (canBook && actions != null) {
                        PMButton(L("New appointment"), systemImage = "calendar-plus") { actions.newAppointment() }
                    }
                },
            )
        } else {
            ModuleHeader(icon = "calendar-clock", title = L("Today"), subtitle = HomeDayKey.long(today.date), accent = Theme.blue, stats = stats, storageKey = "home")
        }
    }

    // MARK: The Agenda card

    val currentFilter = filter
    val boardTitle = if (currentFilter != null) {
        if (currentFilter == HomeLane.inService) HomeText.inServiceTitle(categories) else (if (currentFilter == HomeLane.waiting) L("Waiting@home") else L("To pay"))
    } else {
        if (activity == HomeActivity.all || activity == HomeActivity.appointments) L("Schedule@home") else activity.label
    }

    fun sessionLane(session: HomeSession): HomeLane {
        val staying = session.staying
        if (staying.any { it.status == "in_progress" }) return HomeLane.inService
        if (staying.any { it.status == "checked_in" }) return HomeLane.waiting
        return HomeLane.upcoming
    }

    /** The row's one-word state (a groom under way says its stage itself, in the row). */
    fun stateLabel(visit: HomeVisit): String = HomeText.laneLabel(visit.laneValue, categories)

    /** The agenda in the order the day needs it: who is here now, who pays, who comes next. */
    @Composable
    fun groups(): List<HomeBoardVisitGroup> {
        val inSession: (HomeVisit) -> Boolean = { visit ->
            visit.group?.kind == "session" && visit.laneValue in listOf(HomeLane.upcoming, HomeLane.waiting, HomeLane.inService)
        }
        val sessions = (today.sessions ?: emptyList()).filter { it.status == "active" }
        fun sessionKey(session: HomeSession): Pair<String, HomeLane>? {
            val staying = session.staying
            val live = staying.any { it.status == "in_progress" || it.status == "checked_in" }
            val ahead = staying.any { it.status == "scheduled" } || staying.isEmpty()
            val lane = if (staying.any { it.status == "in_progress" }) HomeLane.inService else (if (live) HomeLane.waiting else HomeLane.upcoming)
            if (currentFilter != null && currentFilter != lane) return null
            return if (live) "now" to lane else (if (ahead) "upcoming" to lane else null)
        }
        val definitions: List<HomeBoardGroupDefinition> = listOf(
            HomeBoardGroupDefinition("now", L("Now@home"), Theme.blue, listOf(HomeLane.inService, HomeLane.waiting)),
            HomeBoardGroupDefinition("to_pay", L("To pay"), Theme.pink, listOf(HomeLane.toPay)),
            HomeBoardGroupDefinition("upcoming", L("Upcoming@home"), Theme.aqua, listOf(HomeLane.upcoming)),
        )
        return definitions.mapNotNull { definition ->
            if (currentFilter != null && !definition.lanes.contains(currentFilter)) return@mapNotNull null
            val visits = definition.lanes.flatMap { lane(it) }.filter { (currentFilter == null || it.laneValue == currentFilter) && !inSession(it) }
            val folded = HomeFold.together(HomeFold.sorted(visits))
            val groupSessions = sessions.filter { sessionKey(it)?.first == definition.key }
            if (folded.isEmpty() && groupSessions.isEmpty()) return@mapNotNull null
            HomeBoardVisitGroup(id = definition.key, title = definition.title, color = definition.color, visits = folded, sessions = groupSessions)
        }
    }

    @Composable
    fun visitGroups() {
        val groups = groups()
        if (groups.isEmpty()) {
            // Under Todos, the other activities below speak for the day.
            val hubShown = activity == HomeActivity.all && hubEntries(HomeActivity.all).isNotEmpty()
            if (!hubShown) {
                val nothingToday = currentFilter == null && lane(HomeLane.done).size + lane(HomeLane.missed).size == 0
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        systemImage = if (nothingToday) "calendar-clock" else "badge-check",
                        title = if (nothingToday) L("Nothing booked for today") else (if (currentFilter == null) L("Everyone has been seen") else HomeBoardText.emptyText(currentFilter)),
                        message = if (nothingToday) L("Walk-ins and new bookings show up here as they come.") else L("Finished visits are below."),
                        accent = Theme.blue,
                    )
                    if (nothingToday) {
                        FlowLayout(spacing = 8.dp, modifier = Modifier.homeBoardPullUp(12.dp).padding(bottom = 24.dp)) {
                            if (canWalkIn && actions != null) {
                                PMButton(L("Walk-in"), systemImage = "zap", variant = ButtonVariant.outline, size = ButtonSize.header) { actions.walkIn() }
                            }
                            if (canBook && actions != null) {
                                PMButton(L("New appointment"), systemImage = "calendar-plus", size = ButtonSize.header) { actions.newAppointment() }
                            }
                            PMButtonStyle(ButtonVariant.link, ButtonSize.slim, onClick = { nav.push(ProviderRoute.section(ProviderSection.schedule)) }) {
                                PMButtonContent(title = L("Open the schedule"), icon = "calendar-days", variant = ButtonVariant.link, size = ButtonSize.slim)
                            }
                        }
                    }
                }
            }
        } else {
            for (group in groups) {
                key(group.id) {
                    val rows: List<HomeBoardGroupRow> = group.sessions.map { HomeBoardGroupRow.session(it) } + group.visits.map { HomeBoardGroupRow.visit(it) }
                    HomeStrip(storageKey = "board:${group.id}", title = group.title, count = rows.size, dot = group.color) {
                        RowList(items = rows.take(HomeCap.rows)) { row ->
                            when (row) {
                                is HomeBoardGroupRow.session ->
                                    HomeSessionRow(session = row.session, stateLabel = HomeText.laneLabel(sessionLane(row.session), categories))
                                is HomeBoardGroupRow.visit -> HomeVisitRow(
                                    visit = row.folded.visit,
                                    together = row.folded.together,
                                    stateLabel = stateLabel(row.folded.visit),
                                    hospitalized = hospitalized.contains(row.folded.visit.id),
                                    compact = false,
                                    permissions = permissions,
                                    categories = categories,
                                    open = open,
                                )
                            }
                        }
                        if (rows.size > HomeCap.rows) {
                            HomeStripMore(more = rows.size - HomeCap.rows, route = ProviderRoute.section(ProviderSection.schedule))
                        }
                    }
                }
            }
        }
    }

    /** "Listo · 3 · Sin realizar: 1": the day's finished and missed visits, folded. */
    @Composable
    fun doneSection() {
        val done = lane(HomeLane.done)
        val missed = lane(HomeLane.missed)
        if (done.size + missed.size > 0) {
            val turn by animateFloatAsState(if (showDone) 180f else 0f, tween(200), label = "done chevron")
            Column(Modifier.fillMaxWidth()) {
                RowDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainButton { showDone = !showDone }
                        .semantics { selected = showDone }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val inkStrong = Theme.inkStrong
                    val inkSoft = Theme.inkSoft
                    val strong = Theme.text(14, FontWeight.ExtraBold)
                    val bold = Theme.text(14, FontWeight.Bold)
                    Text(
                        buildAnnotatedString {
                            withStyle(strong.toSpanStyle().copy(color = inkStrong)) { append(L("Done@home") + " · ${done.size}") }
                            withStyle(bold.toSpanStyle().copy(color = inkSoft)) {
                                append(if (missed.isEmpty()) "" else " · " + LF("%d missed@home", missed.size))
                            }
                        },
                        style = strong,
                    )
                    Spacer(Modifier.weight(1f))
                    Glyph("chevron-down", 16.dp, tint = inkSoft, modifier = Modifier.rotate(turn))
                }
                AnimatedVisibility(
                    visible = showDone,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        val rows = HomeFold.together(HomeFold.sorted(done + missed))
                        RowDivider()
                        RowList(items = rows.take(HomeCap.rows * 2)) { folded ->
                            HomeVisitRow(
                                visit = folded.visit, together = folded.together, stateLabel = stateLabel(folded.visit), hospitalized = false,
                                compact = true, permissions = permissions, categories = categories, open = open,
                            )
                        }
                        if (rows.size > HomeCap.rows * 2) {
                            HomeStripMore(more = rows.size - HomeCap.rows * 2, route = ProviderRoute.section(ProviderSection.appointments))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun board(modifier: Modifier = Modifier) {
        SectionCard(
            title = boardTitle,
            modifier = modifier,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (filter != null && !hubHeader) {
                        Chip(text = L("See everything"), tone = Chip.Tone.muted, icon = "x", modifier = Modifier.plainButton { filter = null })
                    }
                    if (showsLens && showsVisits) {
                        Segmented(
                            options = listOf(Segmented.Option(HomeLens.everyone, L("Everyone@home")), Segmented.Option(HomeLens.mine, L("Mine"))),
                            selection = lens, look = Segmented.Look.raised, label = L("Whose"),
                        )
                    }
                    val date = today.date
                    if (permissions.reassign && showsVisits && actions != null && date != null) {
                        AppMenu(label = { IconCircleLabel(size = 32.dp, bordered = false) }) {
                            item(L("Someone is off? Move their day"), icon = "users") { actions.reassignDay(date) }
                        }
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (hubHeader && showsVisits) {
                    // Where each visit is: the agenda's own tabs, under its title.
                    StatusTabs(
                        options = listOf(
                            Triple(HomeLaneFilter.all, L("Everything"), today.counts?.total ?: 0),
                            Triple(HomeLaneFilter.waiting, L("Waiting@home"), today.counts?.waiting ?: 0),
                            Triple(HomeLaneFilter.inService, HomeText.inServiceTitle(categories), today.counts?.inService ?: 0),
                            Triple(HomeLaneFilter.toPay, L("To pay"), today.counts?.toPay ?: 0),
                        ),
                        selection = stateOf({ HomeLaneFilter.from(filter) }, { filter = it.lane }),
                    )
                    RowDivider()
                }
                if (activity == HomeActivity.appointments && serviceOptions.size > 1) {
                    StatusTabs(
                        options = listOf(Triple<String?, String, Int?>(null, L("Every service"), null)) +
                            serviceOptions.map { Triple<String?, String, Int?>(it, HomeServiceName.label(it), null) },
                        selection = stateOf({ activeService }, { service = it }),
                    )
                    RowDivider()
                }
                if (showsVisits) visitGroups()
                if (modules.contains(HomeActivity.grooming) && activity.shows(HomeActivity.grooming) && filter == null) {
                    // Under Todos the grooms are agenda rows already: only their stage counts. Under Estética, each one with its next step.
                    HomeGroomingSection(data = model.grooming, full = activity == HomeActivity.grooming)
                }
                for (module in listOf(HomeActivity.stays, HomeActivity.hospital, HomeActivity.home)) {
                    if (modules.contains(module) && activity.shows(module) && filter == null) {
                        key(module.rawValue) {
                            // Under its own tile Domicilio is the Home visits day list, with "Llegué" and "Terminar".
                            if (module == HomeActivity.home && activity == HomeActivity.home && model.homeVisitsDay != null) {
                                HomeHomeVisitsSection(day = model.homeVisitsDay)
                            } else {
                                HomeHubSection(
                                    module = module, entries = hubEntries(activity).filter { it.module == module.rawValue },
                                    memberId = memberId, showEmpty = activity == module,
                                )
                            }
                        }
                    }
                }
                if (activity == HomeActivity.all && !showsVisits && hubEntries(HomeActivity.all).isEmpty()) {
                    EmptyState(systemImage = "badge-check", title = L("Nothing here right now."), accent = Theme.blue)
                }
                if (filter == null && showsVisits) {
                    doneSection()
                }
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().alpha(if (model.loading && model.today == null) 0.7f else 1f)) {
        /** The agenda and "Te necesita" side by side once there is room (web `xl`). */
        val sideBySide = maxWidth >= 940.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val arrivals = (today.arrivals ?: emptyList()).filter { it.isOpen }
            if (arrivals.isNotEmpty()) {
                HomeArrivalsBlock(arrivals = arrivals, canAct = permissions.checkIn)
            }
            // A phone: the day switcher on its own line (the header's buttons stay on one).
            if (phone && switcher != null) switcher()
            header()
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    board(Modifier.weight(1f))
                    if (needs.hasAnything) needs.body(Modifier.width(352.dp))
                }
            } else {
                board()
                if (needs.hasAnything) needs.body()
            }
            // "All clear" only when it is: never under a late visit or a groom that needs someone.
            if (!needs.hasAnything) {
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("badge-check", 16.dp, tint = Theme.blue)
                    Text(L("All clear. Nothing else needs you today."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
        }
    }
}

/** HomeBoard's `VisitGroup`. */
class HomeBoardVisitGroup(
    val id: String,
    val title: String,
    val color: Color,
    val visits: List<HomeFoldedVisit>,
    val sessions: List<HomeSession>,
)

private class HomeBoardGroupDefinition(val key: String, val title: String, val color: Color, val lanes: List<HomeLane>)

/** HomeBoard's `GroupRow`: a group session or a (folded) visit. */
sealed class HomeBoardGroupRow {
    data class session(val session: HomeSession) : HomeBoardGroupRow()
    data class visit(val folded: HomeFoldedVisit) : HomeBoardGroupRow()

    val id: String
        get() = when (this) {
            is session -> "session-${session.id}"
            is visit -> folded.id
        }
}

/** HomeBoard's static words (iOS `HomeBoard.emptyText`). */
object HomeBoardText {
    fun emptyText(lane: HomeLane?): String = when (lane) {
        HomeLane.upcoming -> L("Nobody else is booked today.")
        HomeLane.waiting -> L("Nobody is waiting.")
        HomeLane.inService -> L("Nobody is in right now.")
        HomeLane.toPay -> L("Nothing waiting at the desk.")
        else -> L("Nothing here right now.")
    }
}

enum class HomeLens { everyone, mine }

/** The agenda's state tabs: everything, or one lane. */
enum class HomeLaneFilter {
    all, waiting, inService, toPay;

    val lane: HomeLane?
        get() = when (this) {
            all -> null
            waiting -> HomeLane.waiting
            inService -> HomeLane.inService
            toPay -> HomeLane.toPay
        }

    companion object {
        /** Swift `init(_ lane:)`. */
        fun from(lane: HomeLane?): HomeLaneFilter = when (lane) {
            HomeLane.waiting -> waiting
            HomeLane.inService -> inService
            HomeLane.toPay -> toPay
            else -> all
        }
    }
}

/** A visit and the family's other pets booked with it in the same step of the day: one row. */
data class HomeFoldedVisit(
    val visit: HomeVisit,
    var together: List<HomeVisit>,
) {
    val id: String get() = visit.id
}

object HomeFold {
    private fun triageRank(priority: String?): Int = when (priority) {
        "critical" -> 0
        "urgent" -> 1
        "stable" -> 2
        else -> 3
    }

    /** In a group: the visit under way first, then the longest wait (by triage), then by time. */
    fun sorted(visits: List<HomeVisit>): List<HomeVisit> = visits.sortedWith { left, right ->
        if (left.laneValue.order != right.laneValue.order) return@sortedWith left.laneValue.order.compareTo(right.laneValue.order)
        if (left.laneValue == HomeLane.waiting) {
            val a = triageRank(left.triagePriority)
            val b = triageRank(right.triagePriority)
            if (a != b) return@sortedWith a.compareTo(b)
            return@sortedWith (right.waitingMinutes ?: 0).compareTo(left.waitingMinutes ?: 0)
        }
        (left.startsAt ?: "").compareTo(right.startsAt ?: "")
    }

    fun together(visits: List<HomeVisit>): List<HomeFoldedVisit> {
        val out = mutableListOf<HomeFoldedVisit>()
        val index = HashMap<String, Int>()
        for (visit in visits) {
            val group = visit.group
            if (group == null || group.kind != "household" || visit.laneValue == HomeLane.missed) {
                out.add(HomeFoldedVisit(visit = visit, together = emptyList()))
                continue
            }
            val key = "${group.id}:${visit.laneValue.rawValue}"
            val lead = index[key]
            if (lead != null) {
                out[lead].together = out[lead].together + visit
            } else {
                index[key] = out.size
                out.add(HomeFoldedVisit(visit = visit, together = emptyList()))
            }
        }
        return out
    }
}

/** A service category's name (lib/service-provider-display.ts). */
object HomeServiceName {
    fun label(category: String): String = when (category) {
        "vet" -> L("Veterinary clinic")
        "emergency" -> L("Emergency")
        "groomer" -> L("Grooming")
        "boarding" -> L("Boarding")
        "daycare" -> L("Daycare")
        "sitter" -> L("Pet sitting")
        "walker" -> L("Dog walking")
        "trainer" -> L("Training")
        else -> L("Other")
    }
}

/** SwiftUI `.padding(.top, -x)`: drawn `x` higher, its frame `x` shorter at the top. */
private fun Modifier.homeBoardPullUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
}
