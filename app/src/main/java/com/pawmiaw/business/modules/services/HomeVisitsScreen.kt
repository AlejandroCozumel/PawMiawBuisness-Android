package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.stays.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

//
//  HomeVisitsScreen.kt (iOS HomeVisitsScreen.swift)
//
//  Domicilio (web: routes/provider.home-visits.tsx, components/app/home-visits/):
//  the day's visits to families' homes in the order they are made — walks,
//  drop-ins, home appointments and the visits of home stays — read on a phone
//  at the door. One route, one runner: arriving is the visit's start and
//  finishing its finish, with its report and its bill. Late visits per
//  backend docs/home-visits-late-and-notices.md (the notices clear on the
//  server when someone arrives, the visit moves or is undone).
//  Arrive and Finish go through this phone's outbox (SvcOutbox.kt), so
//  they work with no signal; the place at those presses and a walk's route
//  are recorded only when the business turned that on (SvcLocation.kt).
//

@Composable
fun HomeVisitsScreen(modifier: Modifier = Modifier) {
    providerPage(L("Home visits"), root = true, modifier = modifier) {
        RequireLocation { SvcHomeVisitsPage() }
    }
}

enum class SvcHomeTab { today, team, settings }

/** One sheet of the page at a time. */
sealed class SvcHomeSheet {
    data class onMyWay(val target: SvcOnMyWayTarget) : SvcHomeSheet()
    data class report(val target: SvcReportTarget) : SvcHomeSheet()
    data class finish(val target: SvcFinishTarget) : SvcHomeSheet()
    data class reassign(val item: SvcHomeVisitItem) : SvcHomeSheet()
    data class check(val item: SvcHomeVisitItem) : SvcHomeSheet()
    data class reveal(val item: SvcHomeVisitItem, val emergency: Boolean) : SvcHomeSheet()
    data class vault(val item: SvcHomeVisitItem, val reveal: SvcVaultReveal) : SvcHomeSheet()
    data class stayMove(val item: SvcHomeVisitItem, val move: String) : SvcHomeSheet()
    data object moveDay : SvcHomeSheet()
    data class gate(val request: SvcStartGateRequest) : SvcHomeSheet()
    /** A home stay's visit, run in its own sheet (optionally opening on "leave" or the report card). */
    data class stayVisit(val item: SvcHomeVisitItem, val start: String?) : SvcHomeSheet()

    val id: String
        get() = when (this) {
            is onMyWay -> "omw-${target.id}"
            is report -> "report-${target.id}"
            is finish -> "finish-${target.id}"
            is reassign -> "reassign-${item.id}"
            is check -> "check-${item.id}"
            is reveal -> "reveal-${item.id}-$emergency"
            is vault -> "vault-${item.id}"
            is stayMove -> "stay-${item.id}-$move"
            moveDay -> "move-day"
            is gate -> "gate-${request.id}"
            is stayVisit -> "stay-visit-${item.id}-${start ?: ""}"
        }
}

/** The outbox belongs to the business and the person: found here, then the page. */
@Composable
private fun SvcHomeVisitsPage() {
    val clinicId = Workspace.shared.current?.clinicId
    val userId = SessionStore.shared.user?.id
    key(clinicId, userId) {
        val outbox = remember { SvcOutbox.store(clinicId = clinicId, userId = userId) }
        SvcHomeVisitsContent(outbox = outbox, clinicId = clinicId, userId = userId)
    }
}

/** The page's state and actions (Swift `SvcHomeVisitsContent`'s `@State` and its private funcs). */
@Stable
private class SvcHomeVisitsModel(
    val outbox: SvcOutbox,
    val clinicId: String?,
    val userId: String?,
    val scope: CoroutineScope,
) {
    val workspace = Workspace.shared
    val tracker = SvcWalkTracker()
    val tab = mutableStateOf(SvcHomeTab.today)
    var dayKey by mutableStateOf<String?>(null)
    var member by mutableStateOf<String?>(null)
    var day by mutableStateOf<SvcHomeVisitsDay?>(null)
    /** The time of the list shown from this phone's copy (no signal); null when it is live. */
    var cachedAt by mutableStateOf<String?>(null)
    var failed by mutableStateOf<String?>(null)
    var members by mutableStateOf<List<SvcMember>>(emptyList())
    var lastPosition by mutableStateOf<Map<String, VisitPosition>>(emptyMap())
    var sheet by mutableStateOf<SvcHomeSheet?>(null)
    /** The sheet to show once the open one has gone (SwiftUI swaps a `.sheet(item:)` by itself). */
    var queued: SvcHomeSheet? = null
    var noticeSeen by mutableStateOf(AppContext.defaults.getBoolean(NOTICE_KEY, false))
        private set

    /** Pages pushed from here (Swift `pushed` / `sessionPushed`): the page's navigator. */
    var push: (ProviderRoute) -> Unit = {}

    companion object {
        const val NOTICE_KEY = "pawmiaw_negocios_home_location_notice"
    }

    fun markNoticeSeen() {
        noticeSeen = true
        AppContext.defaults.edit().putBoolean(NOTICE_KEY, true).apply()
    }

    val canView: Boolean get() = workspace.canAny(Permission.appointmentsView, Permission.staysView)
    val canBook: Boolean get() = workspace.canAll(Permission.appointmentsCreate, Permission.clientsView)
    val seesAll: Boolean get() = day?.viewer?.seesAll ?: false
    val lateAfter: Int get() = day?.settings?.lateAfterMinutes ?: 15
    val isToday: Boolean get() = day?.isToday ?: false
    val stamps: Boolean get() = day?.settings?.locationStamps ?: false
    val walkRoutes: Boolean get() = day?.settings?.walkRoutes ?: false
    val online: Boolean get() = SvcConnectivity.shared.online
    /** The plain day (today, everyone): the one kept on the phone. */
    val plain: Boolean get() = dayKey == null && member == null

    /** The day's visits with this phone's unsent presses already shown on them. */
    val items: List<SvcHomeVisitItem> get() = outbox.apply(day?.items ?: emptyList())

    /** A sheet now, or (one is open and closing) right after it has gone. */
    fun present(next: SvcHomeSheet) {
        if (sheet == null) sheet = next else queued = next
    }

    /** The open sheet has gone: the one waiting, if any, comes up. */
    fun sheetGone(id: String) {
        if (sheet?.id != id) return
        sheet = null
        val next = queued ?: return
        queued = null
        scope.launch {
            delay(150)
            sheet = next
        }
    }

    // MARK: Loading

    suspend fun load() {
        try {
            val loaded: SvcHomeVisitsDay = API.get("/provider-home-visits/today", mapOf("locationId" to workspace.locationId, "day" to dayKey, "memberId" to member))
            day = loaded
            cachedAt = null
            failed = null
            // Today's list as it last loaded is kept on this phone, for when the signal drops.
            if (plain) SvcDayCache.save(loaded, clinicId = clinicId, userId = userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val offline = (e as? APIError)?.isOffline == true
            if ((day == null || cachedAt != null) && offline && plain) {
                val kept = SvcDayCache.read(clinicId = clinicId, userId = userId)
                if (kept != null) {
                    day = kept.day
                    cachedAt = SvcTime.clock(kept.savedAt, kept.day.timeZone)
                    failed = null
                    return
                }
            }
            if (day == null) {
                failed = if (offline) errorText(e) + " " + L("Open this page with a connection before you head out.") else errorText(e)
            } else if (!offline) {
                Toast.error(e)
            }
        }
    }

    // MARK: The walk's route

    val trackedItem: SvcHomeVisitItem?
        get() {
            if (!walkRoutes) return null
            return items.firstOrNull { item ->
                val id = item.appointmentId
                item.phase == "in_progress" && SvcHomeRules.isWalk(item) && id != null && SvcWalkTracking.isTracking(id)
            }
        }

    val trackedKey: String
        get() = "${trackedItem?.appointmentId ?: "none"}|" + items.joinToString(",") { "${it.id}:${it.phase}" }

    fun followWalk() {
        // A walk that is no longer open stops being this phone's to record (one still waiting keeps its flag a moment).
        for (item in items) {
            val id = item.appointmentId ?: continue
            if (item.phase == "in_progress") continue
            val age = SvcWalkTracking.age(id) ?: continue
            if (item.closed || age > 120) SvcWalkTracking.clear(id)
        }
        val tracked = trackedItem
        tracker.follow(appointmentId = tracked?.appointmentId, itemId = tracked?.id, outbox = outbox)
    }

    // MARK: Actions

    val handlers: SvcVisitHandlers
        get() = SvcVisitHandlers(
            arrive = { arrive(it) }, finish = { finish(it) }, onMyWay = { onMyWay(it) }, report = { report(it) },
            reassign = { item -> connected { sheet = SvcHomeSheet.reassign(item) } },
            check = { item -> connected { sheet = SvcHomeSheet.check(item) } },
            reveal = { item -> connected { sheet = SvcHomeSheet.reveal(item, emergency = false) } },
            emergency = { item -> connected { sheet = SvcHomeSheet.reveal(item, emergency = true) } },
            open = { open(it) },
            stayMove = { item, move ->
                if (move == "undo") scope.launch { stayMove(item, "undo", body = emptyMap()) } else sheet = SvcHomeSheet.stayMove(item, move)
            },
            push = { push(it) },
            retry = { outbox.retry(it) },
            discard = { id ->
                // An arrival that is forgotten is no longer this phone's walk to record.
                val entry = outbox.entries.firstOrNull { it.id == id }
                if (entry != null && entry.kind == "arrive") {
                    day?.items?.firstOrNull { it.id == entry.itemId }?.appointmentId?.let { SvcWalkTracking.clear(it) }
                }
                outbox.discard(id)
            },
            reviewConsents = { entry, item -> reviewConsents(entry, item) },
        )

    /** Everything but Arrive and Finish needs a connection: said once, nothing opens. */
    fun connected(action: () -> Unit) {
        if (!online) {
            Toast.error(L("This needs a connection."))
            return
        }
        action()
    }

    fun open(item: SvcHomeVisitItem) {
        if (item.isStay) {
            val stay = item.stayId
            if (item.stayVisitId != null) connected { sheet = SvcHomeSheet.stayVisit(item, null) }
            else if (stay != null) push(ProviderRoute.stay(stay))
        } else {
            item.appointmentId?.let { push(ProviderRoute.appointment(it)) }
        }
    }

    /** The place of this press, when the business records it: asked now, awaited 8 s at most, never in the way. */
    fun stampPosition(itemId: String): (suspend () -> VisitPosition)? {
        if (!stamps) return null
        return {
            val answer = VisitLocator.shared.position()
            lastPosition = lastPosition + (itemId to answer)
            answer
        }
    }

    /** "I've arrived": the visit's start, from the door, with the time it was pressed — saved on the phone first, sent in order. */
    fun arrive(item: SvcHomeVisitItem) {
        if (item.isStay) {
            val stay = item.stayId ?: return
            val visit = item.stayVisitId ?: return
            outbox.add(kind = "stay_arrive", itemId = item.id, path = "/provider-homestays/$stay/visits/$visit/arrive", position = stampPosition(item.id))
            return
        }
        val id = item.appointmentId ?: return
        // This phone records the walk's route from here on.
        if (walkRoutes && SvcHomeRules.isWalk(item)) SvcWalkTracking.mark(id)
        outbox.add(kind = "arrive", itemId = item.id, path = "/provider-appointments/$id/home-arrive", position = stampPosition(item.id))
    }

    /**
     * Finish. With signal: a stay's visit leaves with its note; a walk, sitting, groom or class closes with the charge choice;
     * a vet visit on its page. Without: saved now and sent to the desk with the time it was pressed.
     */
    fun finish(item: SvcHomeVisitItem) {
        val waiting = outbox.pending[item.id] != null
        if (item.isStay) {
            val stay = item.stayId ?: return
            val visit = item.stayVisitId ?: return
            if (online && !waiting) {
                sheet = SvcHomeSheet.stayMove(item, "leave")
                return
            }
            outbox.add(kind = "stay_leave", itemId = item.id, path = "/provider-homestays/$stay/visits/$visit/leave", position = stampPosition(item.id))
            return
        }
        val id = item.appointmentId ?: return
        // A clinical visit closes with its note, on its page.
        if (!SvcHomeRules.finishesHere(item.serviceCategory)) {
            connected { open(item) }
            return
        }
        if (online && !waiting) {
            sheet = SvcHomeSheet.finish(finishTarget(item, id))
            return
        }
        if (tracker.appointmentId == id) tracker.handOverNow()
        outbox.add(kind = "finish", itemId = item.id, path = "/provider-appointments/$id/finish", position = stampPosition(item.id))
    }

    fun finishTarget(item: SvcHomeVisitItem, id: String): SvcFinishTarget {
        val lead = item.pets.firstOrNull { it.appointmentId == item.appointmentId } ?: item.pets.firstOrNull()
        return SvcFinishTarget(
            id = id,
            petName = lead?.name ?: item.petNames,
            serviceLabel = item.serviceText,
            serviceCategory = item.serviceCategory,
            reportMissing = item.reportMissing ?: emptyList(),
            clientName = item.client.name,
            familyOnApp = item.client.onPawMiaw == true,
            assignedId = item.assigned?.id,
            assignedName = item.assigned?.name,
            // The family's other pets booked with it finish with it.
            together = item.pets.filter { it.appointmentId != null && it.appointmentId != item.appointmentId }.map { it.name },
            locationId = workspace.locationId,
        )
    }

    fun onMyWay(item: SvcHomeVisitItem) {
        val id = item.appointmentId ?: return
        connected { sheet = SvcHomeSheet.onMyWay(SvcOnMyWayTarget(id = item.id, appointmentId = id, client = item.client, petNames = item.petNames)) }
    }

    fun report(item: SvcHomeVisitItem) {
        connected {
            if (item.isStay) {
                // A home stay's report card is written in its visit's own sheet.
                present(SvcHomeSheet.stayVisit(item, "report"))
                return@connected
            }
            val pets = item.pets.mapNotNull { pet -> pet.appointmentId?.let { SvcReportPet(id = it, name = pet.name) } }
            val list = if (pets.isEmpty()) (item.appointmentId?.let { listOf(SvcReportPet(id = it, name = item.petNames)) } ?: emptyList()) else pets
            present(SvcHomeSheet.report(SvcReportTarget(subtitle = "${item.petNames} · ${item.serviceText}", pets = list, canWrite = day?.viewer?.canAct ?: false)))
        }
    }

    /** A consent or a deposit stopped a saved arrival: owners and admins start anyway with a reason (the press time kept); others open the visit. */
    fun reviewConsents(entry: SvcOutboxEntry, item: SvcHomeVisitItem) {
        if (!online) {
            Toast.error(L("This needs a connection."))
            return
        }
        if (!workspace.isOwnerOrAdmin) {
            open(item)
            return
        }
        val box = outbox
        sheet = SvcHomeSheet.gate(SvcStartGateRequest(code = entry.errorCode ?: "consent_required", petName = item.petNames) { override ->
            val body = entry.body.toMutableMap()
            for ((key, value) in override) body[key] = svcJSON(value)
            API.data("POST", entry.path, emptyMap(), JsonObject(body).toString().toByteArray())
            box.resolve(entry.id)
            announceDataChanged()
        })
    }

    suspend fun stayMove(item: SvcHomeVisitItem, move: String, body: Map<String, Any?>) {
        val stay = item.stayId ?: return
        val visit = item.stayVisitId ?: return
        try {
            API.post<SvcIgnored>("/provider-homestays/$stay/visits/$visit/$move", body)
            Toast.show(if (move == "undo") L("Undone.") else L("Saved"))
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(e)
        }
    }

    /** The visit under way, else the next one this person can arrive at (never one whose press was refused). */
    fun barVisit(items: List<SvcHomeVisitItem>): SvcHomeVisitItem? {
        val blocked = outbox.failed.map { it.itemId }.toSet()
        val open = items.filter { it.id !in blocked }
        return open.firstOrNull { it.phase == "in_progress" && it.actions?.finish == true }
            ?: open.filter { it.actions?.arrive == true }.minByOrNull { it.startsAt }
    }

    /** "New visit": the kind this business does at home, on the day shown, for the person shown. */
    fun newVisit(): ProviderRoute {
        val person = member?.let { if (it == "unassigned") null else it }
        return ProviderRoute.bookVisit(BookingPrefill(serviceId = SvcServices.pick(category = SvcBook.homeCategory(workspace)), day = day?.date ?: dayKey, memberId = person))
    }

    /** Swift `pushed` / `sessionPushed`: a group session opens as a page of its own. */
    fun pushSession(route: SvcSessionRoute) {
        when (route) {
            is SvcSessionRoute.session -> push(ProviderRoute.page("session:${route.id}") { SvcSessionScreen(sessionId = route.id) })
            is SvcSessionRoute.provider -> push(route.route)
        }
    }
}

@Composable
private fun SvcHomeVisitsContent(outbox: SvcOutbox, clinicId: String?, userId: String?) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val model = remember { SvcHomeVisitsModel(outbox = outbox, clinicId = clinicId, userId = userId, scope = scope) }
    val nav = LocalNavigator.current
    model.push = { nav.push(it) }

    if (!model.canView) {
        PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see appointments or stays.")) }
    } else {
        SvcHomeVisitsPageBody(model)
    }
}

@Composable
private fun SvcHomeVisitsPageBody(model: SvcHomeVisitsModel) {
    val workspace = Workspace.shared
    val regular = LocalSizeClass.current == SizeClass.regular
    val tracker = model.tracker
    val outbox = model.outbox
    val density = LocalDensity.current

    // TimelineView(.periodic(from: .now, by: 30)): the page's clock.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000)
            now = Instant.now()
        }
    }
    var barHeight by remember { mutableIntStateOf(0) }

    val tabs = buildList {
        add(ToneTabs.Tab(SvcHomeTab.today, L("Today"), icon = "calendar-days"))
        if (model.seesAll || model.tab.value == SvcHomeTab.team) add(ToneTabs.Tab(SvcHomeTab.team, L("Team"), icon = "users-round"))
        add(ToneTabs.Tab(SvcHomeTab.settings, L("Settings"), icon = "settings-2"))
    }

    @Composable
    fun Header() {
        val day = model.day
        val shown = model.items
        val done = shown.count { it.phase == "done" }
        val inProgress = shown.count { it.phase == "in_progress" }
        val late = shown.count { SvcHomeRules.late(it, now = now, lateAfter = model.lateAfter, isToday = model.isToday).first }
        val next = shown.filter { it.phase == "upcoming" || it.phase == "on_my_way" }.minByOrNull { it.startsAt }
        ModuleHeader(
            icon = "house",
            title = L("Home visits"),
            subtitle = day?.let { svcCapitalized(SvcTime.dayLong(it.date)) } ?: L("The day’s visits to families’ homes, in order"),
            accent = Theme.aqua,
            stats = listOf(
                ModuleStat("visits", label = L("Visits"), value = if (day == null) "—" else "$done/${shown.size}", hint = if (inProgress > 0) LF("%d in progress", inProgress) else null, icon = "list-checks", accent = Theme.aqua),
                ModuleStat("next", label = L("Next"), value = next?.let { SvcTime.clock(it.startsAt, day?.timeZone) } ?: "—", hint = next?.petNames, icon = "clock-3", accent = Theme.blue),
                ModuleStat("late", label = L("Home visit · Late"), value = if (day == null) "—" else "$late", icon = "triangle-alert", accent = Theme.pink, attention = late > 0),
            ),
            storageKey = "home-visits",
            // Phones keep the header to the three numbers: "New visit" is in the page's overflow there.
            actions = if (model.canBook && regular) ({ PMButton(L("New visit"), systemImage = "plus") { model.push(model.newVisit()) } }) else null,
        )
    }

    /** The tabs, and on a phone the page's overflow with "New visit" (the header keeps to its three numbers there). */
    @Composable
    fun TabRow() {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ToneTabs(tabs = tabs, selection = model.tab, look = ToneTabs.Look.solid, height = 44.dp, label = L("Home visits"))
            }
            if (model.canBook && !regular) {
                AppMenu(
                    label = {
                        Box(
                            Modifier
                                .size(48.dp)
                                .background(Theme.surface, CircleShape)
                                .border(2.dp, Theme.borderSoft, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Glyph("ellipsis", 20.dp, tint = Theme.inkStrong) }
                    },
                    accessibilityLabel = L("More options"),
                ) {
                    item(L("New visit"), icon = "plus") { model.push(model.newVisit()) }
                }
            }
        }
    }

    /** Whose visits (web `PersonChip`s): everyone, only mine, then each person with how far they are. */
    @Composable
    fun PersonChips(day: SvcHomeVisitsDay, people: List<SvcHomePerson>) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(title = L("Everyone"), isSelected = model.member == null, tone = Theme.blue, icon = "users-round") { model.member = null }
            val me = day.viewer?.memberId
            if (me != null) {
                FilterChip(title = L("Only mine"), isSelected = model.member == me, tone = Theme.aqua, icon = "user-round") { model.member = me }
            }
            people.filter { it.memberId != day.viewer?.memberId }.forEach { person ->
                FilterChip(
                    title = (if (person.memberId == null) L("Unassigned") else (person.name ?: "—")) + "  " + LF("%d of %d", person.done, person.total),
                    isSelected = model.member == person.key,
                    tone = if (person.memberId == null) Theme.mango else Theme.lavender,
                    icon = "user-round",
                ) { model.member = person.key }
            }
        }
    }

    @Composable
    fun TodayView() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SvcOfflineBanner(online = model.online, waiting = outbox.waiting.size, failed = outbox.failed.size, cachedAt = model.cachedAt)
            val day = model.day
            if (day != null) {
                val today = SvcHomeRules.todayKey(day)
                val shown = model.items
                SvcDaySwitcher(day = day.date, today = today) { next -> model.dayKey = if (next == today) null else next }
                val people = if (model.seesAll) (day.people ?: emptyList()) else emptyList()
                if (people.isNotEmpty() || model.member != null) PersonChips(day, people)
                if ((day.settings?.locationStamps == true || day.settings?.walkRoutes == true) && day.viewer?.canAct == true && !model.noticeSeen) {
                    NoticeCard(
                        tone = NoticeCard.Tone.info,
                        title = L("Location is recorded on home visits"),
                        message = L("Your location is saved when you arrive and finish. Only owners and admins see it."),
                        actionTitle = L("Got it"),
                    ) { model.markNoticeSeen() }
                }
                if (shown.isEmpty()) {
                    EmptyState(
                        systemImage = "house",
                        title = if (model.seesAll && model.member == null) L("No home visits this day") else L("No home visits for you this day"),
                        message = L("Walks, drop-ins and home appointments show up here in the order you’ll make them."),
                        actionTitle = if (model.canBook) L("New visit") else null,
                        action = { model.push(model.newVisit()) },
                        accent = Theme.aqua,
                        panel = true,
                    )
                } else {
                    val next = shown.firstOrNull { it.actions?.arrive == true }
                    val pending = outbox.pending
                    val refused = outbox.failed
                    val handlers = model.handlers
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        shown.forEach { item ->
                            key(item.id) {
                                SvcVisitCard(
                                    item = item, day = day, now = now, highlight = model.isToday && next?.id == item.id,
                                    pressing = pending[item.id]?.holdsNextPress == true, handlers = handlers,
                                    pending = pending[item.id], failed = refused.filter { it.itemId == item.id },
                                    tracker = if (tracker.appointmentId != null && tracker.appointmentId == item.appointmentId) tracker else null,
                                    lastPosition = model.lastPosition[item.id],
                                )
                            }
                        }
                    }
                    // The count sits under the list, as on Home.
                    Text(
                        LF("%d of %d done", shown.count { it.phase == "done" }, shown.size),
                        style = Theme.text(14, FontWeight.Bold),
                        color = Theme.inkSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Below the list: the day's group walks and classes, which live on their own page.
                if (workspace.offers(BusinessCategory.walker, BusinessCategory.trainer)) {
                    SvcGroupSessionsPointer(dayKey = day.date, timeZone = day.timeZone, push = { model.pushSession(it) })
                }
            } else if (model.failed != null) {
                SvcLoadError(
                    message = if (model.online) L("The home visits couldn’t be loaded.") else L("The home visits couldn’t be loaded.") + " " + L("Open this page with a connection before you head out."),
                    compact = false,
                    retry = { model.load() },
                )
            } else {
                LoadingState()
            }
        }
    }

    val day = model.day
    val bar = if (model.tab.value == SvcHomeTab.today && day != null && model.isToday) model.barVisit(model.items) else null

    Box(Modifier.fillMaxSize()) {
        PageScroll(refresh = { model.load() }) {
            Header()
            TabRow()
            when (model.tab.value) {
                SvcHomeTab.today -> TodayView()
                SvcHomeTab.team -> {
                    if (model.seesAll) {
                        SvcTeamProgress(
                            people = day?.people ?: emptyList(),
                            selected = model.member,
                            canMoveDay = day?.viewer?.canReassign ?: false,
                            onSelect = { key ->
                                model.member = key
                                model.tab.value = SvcHomeTab.today
                            },
                            onMoveDay = { model.sheet = SvcHomeSheet.moveDay },
                        )
                    } else {
                        EmptyState(systemImage = "users-round", title = L("Your role can’t see this."), message = L("Only whoever sees everyone’s visits has the team’s day."), accent = Theme.aqua, panel = true)
                    }
                }
                SvcHomeTab.settings -> SvcHomeVisitSettingsTab(canManage = workspace.can(Permission.workspaceEdit), businessName = workspace.current?.name ?: "")
            }
            // SwiftUI `.safeAreaInset(edge: .bottom)`: the list scrolls clear of the bar.
            if (bar != null && barHeight > 0) Spacer(Modifier.height(with(density) { barHeight.toDp() }))
        }
        if (bar != null && day != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { barHeight = it.height },
                contentAlignment = Alignment.BottomCenter,
            ) {
                SvcNextVisitBar(
                    item = bar, timeZone = day.timeZone, pressing = outbox.pending[bar.id]?.holdsNextPress == true,
                    onArrive = { model.arrive(bar) }, onFinish = { model.finish(bar) }, onOnMyWay = { model.onMyWay(bar) }, onOpenVisit = { model.open(bar) },
                )
            }
        }
    }

    model.sheet?.let { current ->
        key(current.id) {
            AppSheet(onDismissRequest = { model.sheetGone(current.id) }) { SheetView(model, current) }
        }
    }

    // The screen stays on while this phone records a walk (iOS `isIdleTimerDisabled`).
    val view = LocalView.current
    val keepOn = tracker.keepsScreenOn
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { model.load() } } }
    // The signal is back: the day is read again at once (what was pressed meanwhile goes on its own).
    val online = model.online
    val seenOnline = remember { booleanArrayOf(online) }
    LaunchedEffect(online) {
        if (online == seenOnline[0]) return@LaunchedEffect
        seenOnline[0] = online
        if (online) model.load()
    }
    LaunchedEffect(model.dayKey, model.member, workspace.scopeVersion) {
        model.load()
        while (isActive) {
            delay(60_000)
            model.load()
        }
    }
    LaunchedEffect(Unit) {
        model.members = svcLoadMembers(workspace)
        SvcServices.load("provider")
    }
    // The walk this phone records: open, routes on, and Arrive was pressed here.
    val trackedKey = model.trackedKey
    LaunchedEffect(trackedKey) { model.followWalk() }
    DisposableEffect(Unit) { onDispose { tracker.stop() } }
}

/** Whoever takes payment goes on to the visit, where its bill is; "Fill it in now" opens the report here. */
@Composable
private fun SheetView(model: SvcHomeVisitsModel, current: SvcHomeSheet) {
    val day = model.day
    when (current) {
        is SvcHomeSheet.onMyWay -> SvcOnMyWaySheet(target = current.target)
        is SvcHomeSheet.report -> SvcReportSheet(target = current.target)
        is SvcHomeSheet.finish -> FinishSheet(model, current.target)
        is SvcHomeSheet.reassign -> SvcReassignSheet(item = current.item, members = model.members)
        is SvcHomeSheet.check -> SvcVisitCheckSheet(
            item = current.item, timeZone = day?.timeZone,
            locationOn = day?.settings?.locationStamps == true || day?.settings?.walkRoutes == true,
        )
        is SvcHomeSheet.reveal -> SvcRevealConfirmSheet(item = current.item, emergency = current.emergency) { result ->
            // The confirm closes itself; the details come up once it has gone.
            model.present(SvcHomeSheet.vault(current.item, result))
        }
        is SvcHomeSheet.vault -> SvcVaultSheet(item = current.item, reveal = current.reveal)
        is SvcHomeSheet.stayMove -> SvcStayMoveSheet(
            item = current.item, move = current.move, timeZone = day?.timeZone,
            locate = if (current.move == "leave") model.stampPosition(current.item.id) else null,
        )
        SvcHomeSheet.moveDay -> SvcMoveDaySheet(
            date = day?.date ?: DateText.dayKey(), people = day?.people ?: emptyList(), members = model.members,
            initialFrom = if (model.member == "unassigned") null else model.member,
        )
        is SvcHomeSheet.gate -> SvcStartGateSheet(request = current.request, onDone = {})
        is SvcHomeSheet.stayVisit -> {
            val item = current.item
            StaysHomeVisitSheet(
                stayId = item.stayId ?: "", visitId = item.stayVisitId ?: "", startWith = current.start,
                onArrive = { model.arrive(item) },
                locate = model.stampPosition(item.id),
                onOpenStay = {
                    val stay = item.stayId
                    if (stay != null) {
                        model.scope.launch {
                            delay(350)
                            model.push(ProviderRoute.stay(stay))
                        }
                    }
                },
            )
        }
    }
}

/** The finish carries the press time, where the phone was and the last of a walk's route. */
@Composable
private fun FinishSheet(model: SvcHomeVisitsModel, target: SvcFinishTarget) {
    val workspace = Workspace.shared
    val item: SvcHomeVisitItem? = model.items.firstOrNull { it.appointmentId == target.id }
    val openReport: (() -> Unit)? = item?.let { { model.report(it) } }
    val openVisit: (() -> Unit)? = if ((model.day?.viewer?.seesBilling ?: false) || workspace.moneyLevel >= MoneyLevel.charging) {
        val id = target.id
        ({ model.push(ProviderRoute.appointment(id)) })
    } else null
    val itemId = item?.id ?: target.id
    val locate = model.stampPosition(itemId)
    val walk = if (model.tracker.appointmentId == target.id) model.tracker else null
    SvcFinishSheet(
        target = target,
        onOpenReport = openReport,
        onOpenVisit = openVisit,
        onFinished = {},
        prepare = {
            coroutineScope {
                val extra = mutableMapOf<String, Any?>("at" to DateText.iso(Instant.now()))
                val place = locate?.let { ask -> async { ask() } }
                walk?.flushNow(timeout = 5.0)
                place?.await()?.let { extra["position"] = it.body }
                extra
            }
        },
    )
}

/** A simple value as JSON, for a press's body. */
fun svcJSON(value: Any?): JSONValue = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is kotlinx.serialization.json.JsonElement -> value
    else -> JsonPrimitive(value.toString())
}

// MARK: - Rules (lib/home-visits.ts)

object SvcHomeRules {
    /** Not arrived, and the start passed by more than the business allows. Only today's visits are late. (late, minutes) */
    fun late(item: SvcHomeVisitItem, now: Instant, lateAfter: Int, isToday: Boolean): Pair<Boolean, Int> {
        if (!isToday || !(item.phase == "upcoming" || item.phase == "on_my_way")) return false to 0
        val start = DateText.parse(item.startsAt) ?: return false to 0
        val late = now.isAfter(start.plusSeconds(lateAfter.toLong() * 60))
        return late to (if (late) ((now.toEpochMilli() - start.toEpochMilli()) / 60_000).toInt() else 0)
    }

    /** There longer than booked plus the allowance, counted from the arrival: a late arrival is late, not long. */
    fun runningLong(item: SvcHomeVisitItem, now: Instant, lateAfter: Int): Boolean {
        if (item.phase != "in_progress") return false
        val start = DateText.parse(item.startsAt) ?: return false
        val booked = DateText.parse(item.endsAt)?.let { max(0L, it.toEpochMilli() - start.toEpochMilli()) } ?: (30L * 60 * 1000)
        val from = DateText.parse(item.arrivedAt) ?: start
        return now.toEpochMilli() > from.toEpochMilli() + booked + lateAfter.toLong() * 60 * 1000
    }

    /** A walk: the only visit whose route is recorded. */
    fun isWalk(item: SvcHomeVisitItem): Boolean = item.source == "appointment" && item.serviceCategory == "walker"

    /** Finishing without the visit page is for visits that close with a report, not a clinical note. */
    fun finishesHere(category: String?): Boolean = listOf("walker", "sitter", "groomer", "trainer").contains(category ?: "")

    /** Today's key on the business's clock, when the server shows another day. */
    fun todayKey(day: SvcHomeVisitsDay): String {
        if (day.isToday == true) return day.date
        return DateText.dayKey(DateText.parse(day.now) ?: Instant.now(), SvcTime.zone(day.timeZone))
    }

    /** "0:07", "1:25 h": the clock a walker glances at. */
    fun timer(arrivedAt: String?, now: Instant): String {
        val arrived = DateText.parse(arrivedAt) ?: return "0:00"
        val seconds = max(0L, (now.toEpochMilli() - arrived.toEpochMilli()) / 1000).toInt()
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d h", hours, minutes) else String.format(Locale.US, "%d:%02d", minutes, seconds % 60)
    }

    fun window(item: SvcHomeVisitItem, timeZone: String?): String {
        val start = SvcTime.clock(item.startsAt, timeZone)
        return item.endsAt?.let { "$start – ${SvcTime.clock(it, timeZone)}" } ?: start
    }

    /**
     * A stamp in a line (text, warn, muted): a manager reads the distance; the person who recorded it only whether it
     * was saved. Never a coordinate. `warn`: far from the address (mango, strong); `muted`: nothing usable was saved (grey).
     */
    fun stampLine(kind: String, stamp: SvcStampSummary?, isManager: Boolean): Triple<String, Boolean, Boolean>? {
        if (stamp == null) return null
        if (!isManager) return if (stamp.status == "ok") Triple(L("Location saved"), false, false) else Triple(L("No location was saved"), false, true)
        if (stamp.status == "expired") return Triple(LF("Location deleted after %d days", 90), false, true)
        if (stamp.status != "ok") return Triple(noLocation(stamp.status), false, true)
        val distance = stamp.distanceM ?: return Triple(L("Location saved"), false, false)
        val text = formatDistance(distance)
        if (stamp.far == true) return Triple(LF("Far from the address · %@", text), true, false)
        return Triple(if (kind == "arrive") LF("Arrived %@ from the address", text) else LF("Finished %@ from the address", text), false, false)
    }

    /** The colour of a stamp's line (web `STAMP_TONE`). */
    @Composable
    fun stampTone(warn: Boolean, muted: Boolean): Color = if (warn) Theme.mango else if (muted) Theme.borderStrong else ProviderState.done.color

    fun noLocation(status: String): String = when (status) {
        "denied" -> L("No location: permission denied")
        "unavailable" -> L("No location: not available")
        "timeout" -> L("No location: it took too long")
        "unsupported" -> L("No location: this browser can’t give it")
        "inaccurate", "stale" -> L("No location: too imprecise")
        "not_sent" -> L("No location: recorded from another app")
        else -> L("No location was saved")
    }

    fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.US, "%.1f km", Math.round(meters / 100).toDouble() / 10)
}

// MARK: - Day switcher

/** ‹ Yesterday · Today · Tomorrow › (web `DaySwitcher`), within the week back and the 30 days ahead the server lists. */
@Composable
private fun SvcDaySwitcher(day: String, today: String, onDay: (String) -> Unit) {
    fun label(key: String): String = when (SvcTime.daysBetween(today, key)) {
        0 -> L("Today")
        -1 -> L("Yesterday")
        1 -> L("Tomorrow")
        else -> DateText.day(DateText.parse(key))
    }

    /** The server lists a week back and 30 days ahead. */
    fun inRange(key: String): Boolean = SvcTime.daysBetween(today, key) in -7..30

    /** A 48 outline button with only its chevron (web: `Button variant="outline" size-12 px-0`). */
    @Composable
    fun Arrow(icon: String, label: String, enabled: Boolean, action: () -> Unit) {
        PMButtonStyle(ButtonVariant.outline, modifier = Modifier.semantics { contentDescription = label }, onClick = action, enabled = enabled) {
            Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                Glyph(icon, 20.dp, modifier = Modifier.wrapContentWidth(unbounded = true))
            }
        }
    }

    val previous = SvcTime.shift(day, by = -1)
    val next = SvcTime.shift(day, by = 1)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Arrow("chevron-left", L("Previous day") + ": " + label(previous), enabled = inRange(previous)) { onDay(previous) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label(day), style = brandHeading(21.6), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(svcCapitalized(SvcTime.dayLong(day)), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (day != today) PMButton(L("Today"), variant = ButtonVariant.outline) { onDay(today) }
        Arrow("chevron-right", L("Next day") + ": " + label(next), enabled = inRange(next)) { onDay(next) }
    }
}

// MARK: - The team's day

/**
 * The team's day (web `TeamProgress`), for whoever sees everyone's visits: one row per
 * person with how far they are, who is at a visit now and who is late. A tap shows only
 * that person's visits; "Move the day" hands someone's whole day to another person.
 */
@Composable
private fun SvcTeamProgress(
    people: List<SvcHomePerson>,
    selected: String?,
    canMoveDay: Boolean,
    onSelect: (String?) -> Unit,
    onMoveDay: () -> Unit,
) {
    @Composable
    fun PersonRow(person: SvcHomePerson) {
        val active = selected == person.key
        val share = if (person.total > 0) person.done.toFloat() / person.total.toFloat() else 0f
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { this.selected = active }
                .plainButton { onSelect(if (active) null else person.key) }
                .background(if (active) Theme.wash(Theme.blue, 10.0) else Color.Transparent)
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (person.image != null) {
                Avatar(name = person.name, imageURL = person.image, size = 40.dp)
            } else {
                Box(Modifier.size(40.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
                    Glyph("user-round", 20.dp, tint = Theme.inkSoft)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (person.memberId == null) L("Unassigned") else (person.name ?: "—"),
                        style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alignByBaseline(),
                    )
                    Text(
                        LF("%d of %d", person.done, person.total),
                        style = Theme.text(14, FontWeight.ExtraBold).copy(fontFeatureSettings = "tnum"), color = Theme.inkStrong,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(Theme.surfaceMuted, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth(share).height(8.dp).background(ProviderState.done.color, RoundedCornerShape(50)))
                }
                if (person.inProgress > 0 || person.late > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (person.inProgress > 0) Text(LF("%d in progress", person.inProgress), style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
                        if (person.late > 0) Text(LF("%d late", person.late), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
                    }
                }
            }
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
    }

    Column(Modifier.listCard()) {
        Box(Modifier.fillMaxWidth().background(Theme.wash(Theme.lavender, 22.0))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(40.dp).background(Theme.wash(Theme.lavender, 55.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph("users-round", 20.dp, tint = Theme.inkStrong)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("The team’s day"), style = brandHeading(20), color = Theme.inkStrong)
                    Text(L("Tap a person to see only their visits."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
            StitchedDivider(lineWidth = 1.dp, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        if (people.isEmpty()) {
            Text(
                L("Nobody has home visits this day."),
                style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
            )
        } else {
            people.forEachIndexed { index, person ->
                if (index > 0) RowDivider()
                PersonRow(person)
            }
            if (canMoveDay) {
                RowDivider()
                PMButton(L("Move the day to someone else"), systemImage = "arrow-right-left", variant = ButtonVariant.outline, fullWidth = true, modifier = Modifier.padding(12.dp), action = onMoveDay)
            }
        }
    }
}

// MARK: - The bar that never scrolls away

/**
 * The action that never scrolls away (web `NextVisitBar`): Finish for the visit under
 * way (with its clock), else Arrive for the next one (and "On my way" when it is offered).
 */
@Composable
private fun SvcNextVisitBar(
    item: SvcHomeVisitItem,
    timeZone: String?,
    pressing: Boolean,
    onArrive: () -> Unit,
    onFinish: () -> Unit,
    onOnMyWay: () -> Unit,
    onOpenVisit: () -> Unit,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            now = Instant.now()
        }
    }
    val inProgress = item.phase == "in_progress"
    val elapsed = SvcTime.minutesBetween(item.arrivedAt, DateText.iso(now))
    val over = inProgress && (item.bookedMinutes?.let { elapsed > it } ?: false)
    val shape = RoundedCornerShape(Theme.cardRadius)
    Row(
        Modifier
            .widthIn(max = 820.dp)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .softShadow(shape, Theme.navy.copy(alpha = 0.14f), radius = 24.dp, y = 24.dp)
            .background(Theme.surface, shape)
            .border(2.dp, if (over) Theme.mango else Theme.blue, shape)
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.pets.firstOrNull()?.let { lead ->
            PetAvatar(name = lead.name, species = lead.species, photoURL = lead.photoUrl, size = 40.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(item.petNames, style = brandHeading(16.8), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (inProgress) SvcHomeRules.timer(item.arrivedAt, now = now) + " · " + (item.bookedMinutes?.let { LF("%d of %d min", elapsed, it) } ?: L("Home visit · In progress"))
                else L("Next") + " · " + SvcTime.clock(item.startsAt, timeZone),
                style = Theme.text(12, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                color = if (over) Theme.inkStrong else Theme.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!inProgress && item.actions?.onMyWay == true && item.onMyWay?.at == null) {
            val label = L("On my way")
            PMButtonStyle(ButtonVariant.outline, modifier = Modifier.semantics { contentDescription = label }, onClick = onOnMyWay) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { Glyph("navigation", 20.dp) }
            }
        }
        if (inProgress) {
            if (item.isStay || SvcHomeRules.finishesHere(item.serviceCategory)) {
                PMButton(if (item.isStay) L("Leave") else L("Finish"), systemImage = if (item.isStay) "log-out" else "badge-check", isWorking = pressing, action = onFinish)
            } else {
                PMButton(L("Open visit"), systemImage = "external-link", action = onOpenVisit)
            }
        } else {
            PMButton(L("I’ve arrived"), systemImage = "door-open", isWorking = pressing, action = onArrive)
        }
    }
}
