package com.pawmiaw.business.services

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.SchedMissedSheet
import com.pawmiaw.business.stays.StaysPlainField
import com.pawmiaw.business.stays.StaysPlainMenu
import com.pawmiaw.business.stays.StaysPlainMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.max

//
//  GroupsSession.kt (iOS GroupsSession.swift)
//
//  One group walk or class (web: groups/session-sheet.tsx, session-roster.tsx,
//  session-form-sheet.tsx, group-report-sheet.tsx): who has a place and who
//  waits, the roster run from here (arrival or pickup, "didn't come", the
//  pickup order), adding pets, the waitlist, planning or changing it,
//  cancelling it (each family is told) and every dog's report on one screen.
//

// MARK: - The session

/**
 * A group walk or class (web `SessionSheet`, a page here): its facts, who has a
 * place (run from here), adding pets and the waitlist; changing it or cancelling
 * it (each family is told) from its header.
 */
@Composable
fun SvcSessionScreen(sessionId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<SvcSessionDetail?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<SvcMember>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    val push: (SvcSessionRoute) -> Unit = { nav.push(it.providerRoute) }

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

    providerPage(session?.serviceLabel ?: L("Group session"), modifier = modifier) {
        PageScroll(refresh = { load() }) {
            val shown = session
            if (shown != null) {
                // The sheet's head as the page's: the session, its day and time, and what can be done to it before it starts.
                ModuleHeader(
                    icon = "users-round",
                    title = shown.serviceLabel,
                    subtitle = "${DateText.dayTime(shown.startsAt)} – ${DateText.time(shown.endsAt)}",
                    accent = Theme.aqua,
                    storageKey = "group-session",
                ) {
                    if (shown.active && !shown.started) {
                        if (workspace.can(Permission.appointmentsEdit)) {
                            PMButton(L("Edit"), systemImage = "pencil", variant = ButtonVariant.outline) { editing = true }
                        }
                        if (workspace.can(Permission.appointmentsCancel)) {
                            PMButton(L("Cancel the session"), systemImage = "calendar-x-2", variant = ButtonVariant.destructive) { cancelling = true }
                        }
                    }
                }
                SvcSessionFacts(session = shown)
                // "Pets · 3/6": the roster, and the session's next move under it.
                SectionCard(
                    L("Pets") + " · " + shown.placesText,
                    subtitle = if (shown.active && shown.booked > 0 && !shown.started) {
                        if (shown.isPickup) L("Mark each dog as picked up; the walk starts with them.") else L("Mark who arrived; the session starts with them.")
                    } else null,
                    icon = "users-round",
                    accent = Theme.aqua,
                ) {
                    if (shown.pets.isEmpty()) {
                        EmptyState(systemImage = "users-round", title = L("No pets yet."), accent = Theme.blue)
                    } else {
                        SvcSessionRoster(session = shown, boxed = false, push = push) { load() }
                    }
                    if (shown.active && workspace.can(Permission.appointmentsComplete) && (shown.withThem + shown.doneCount + shown.arrived + shown.booked) > 0) {
                        RowDivider()
                        SvcSessionMoveButtons(session = shown, canRun = true, modifier = Modifier.fillMaxWidth().padding(12.dp)) { load() }
                    }
                }
                if (shown.active && !shown.started && workspace.can(Permission.appointmentsCreate)) {
                    SvcAddPets(session = shown) { load() }
                }
                val waitlist = shown.waitlist
                if (!waitlist.isNullOrEmpty()) {
                    SvcWaitlist(session = shown, entries = waitlist, canManage = shown.active && workspace.can(Permission.appointmentsCreate)) { load() }
                }
            } else if (failed != null) {
                SvcLoadError(message = L("This session couldn’t be loaded.")) { load() }
            } else {
                LoadingState()
            }
        }

        val current = session
        if (editing && current != null) {
            AppSheet(onDismissRequest = { editing = false }) {
                SvcSessionFormSheet(session = current, people = svcSessionLeaders(members, workspace = workspace)) { scope.launch { load() } }
            }
        }
        if (cancelling && current != null) {
            AppSheet(onDismissRequest = { cancelling = false }) {
                ConfirmSheet(
                    title = LF("Cancel %@?", current.serviceLabel),
                    message = L("Every pet’s visit is cancelled and each family is told, with the reason."),
                    confirmTitle = L("Cancel the session"),
                    destructive = true,
                    askReason = true,
                    reasonRequired = true,
                    reasonLabel = L("Why is it cancelled?"),
                    cancelTitle = L("Keep it"),
                    confirmIcon = "calendar-x-2",
                ) { reason ->
                    val result = API.post<SvcSessionCancelResult>("/provider-sessions/${current.id}/cancel", mapOf("reason" to (reason ?: "")))
                    val told = result.notifiedFamilies ?: 0
                    Toast.show(if (told > 0) LF("Session cancelled. %d families were told.", told) else L("Session cancelled."))
                    announceDataChanged()
                    load()
                }
            }
        }
    }

    ReloadsOnScope { load() }
    LaunchedEffect(Unit) { members = svcLoadMembers(workspace) }
}

/**
 * The session at a glance (web `SessionFacts`): who is in it and how many places
 * are left, then who leads it, where, the price, whether families book it online.
 */
@Composable
private fun SvcSessionFacts(session: SvcSessionDetail) {
    val workspace = Workspace.shared
    val placesLeftText = run {
        if (session.capacity == null) return@run LP(session.size, "1 pet", "%d pets")
        val left = session.placesLeft ?: 0
        if (left <= 0) L("Full") else if (left == 1) L("1 place left") else LF("%d places left", left)
    }

    @Composable
    fun Fact(text: String, icon: String? = null) {
        Chip(text = text, tone = Chip.Tone.custom(Theme.surfaceMuted), icon = icon, percent = 100.0, ink = Theme.inkSoft, size = Chip.Size.medium)
    }

    Card(padding = 16.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.aqua, 16.0), RoundedCornerShape(Theme.boxRadius))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session.staying.isEmpty()) {
                Box(Modifier.size(40.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
                    Glyph("users-round", 20.dp, tint = Theme.inkSoft)
                }
            } else {
                PetStack(pets = session.staying.map { Triple(it.name, it.species, it.photoUrl) }, size = 40.dp, max = 3)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session.placesText, style = brandHeading(24).copy(fontFeatureSettings = "tnum"), color = Theme.inkStrong)
                Eyebrow(placesLeftText, size = 12)
            }
            Spacer(Modifier.weight(1f))
        }
        FlowLayout(spacing = 6.dp) {
            session.assignedMember?.name?.let { Fact(it, icon = "user-round") }
            session.room?.let { Fact(it, icon = "door-open") }
            if (session.isPickup) {
                Fact(L("Picked up at home"), icon = "house")
            } else {
                session.meetingPoint.nonEmpty?.let { Fact(it, icon = "map-pin") }
            }
            session.priceAmount?.let { Fact(LF("%@ per pet", Money.format(it, workspace.currency))) }
            // "Online" only when families really can: the session's switch and its location's online booking.
            if (session.onlineBookingOpen ?: session.onlineBooking ?: false) {
                Chip(text = L("Online"), tone = Chip.Tone.custom(Theme.lime), percent = 30.0, size = Chip.Size.medium)
            } else {
                Fact(L("Only here"))
            }
            val waiting = session.waitlistCount
            if (waiting != null && waiting > 0) {
                Chip(text = LF("%d waiting", waiting), tone = Chip.Tone.custom(Theme.mango), icon = "hourglass", percent = 25.0, size = Chip.Size.medium)
            }
            if (!session.active) {
                Chip(
                    text = L("Session · Cancelled") + (session.cancelReason?.let { " · $it" } ?: ""),
                    tone = Chip.Tone.custom(Theme.destructive), percent = 12.0, ink = Theme.destructive, size = Chip.Size.medium,
                )
            }
        }
        session.notes.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
    }
}

// MARK: - The roster

/**
 * Who has a place, run from here (web `SessionRoster`): arrival (or the pickup, with
 * "on my way" and the address), the ones who didn't come, the pickup order and each
 * pet's report. The name opens its visit. `boxed`: its own bordered box (a card's body).
 */
@Composable
fun SvcSessionRoster(
    session: SvcSessionDetail,
    boxed: Boolean = true,
    push: (SvcSessionRoute) -> Unit,
    modifier: Modifier = Modifier,
    reload: suspend () -> Unit,
) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    var missed by remember { mutableStateOf<SvcMissedTarget?>(null) }
    var onMyWay by remember { mutableStateOf<SvcOnMyWayTarget?>(null) }
    var working by remember { mutableStateOf<String?>(null) }

    val rows = session.rosterRows
    val stops = rows.filter { !it.gone }

    fun statusLabel(status: String): String = when (status) {
        "checked_in" -> if (session.isPickup) L("Picked up") else L("Roster · Here")
        "in_progress" -> if (session.isPickup) L("On the walk") else L("In class")
        "completed" -> L("Roster · Done")
        "no_show" -> L("Didn’t come")
        "cancelled", "canceled" -> L("Session · Cancelled")
        else -> L("Roster · Booked")
    }

    fun arrived(row: SvcSessionPet) {
        working = row.id
        val underWay = rows.any { it.status == "in_progress" }
        scope.launch {
            try {
                API.post<SvcIgnored>("/provider-appointments/${row.appointmentId}/check-in", emptyMap())
                // Late, once the others started: they join the walk or the class right away.
                if (underWay && workspace.can(Permission.appointmentsComplete)) {
                    API.post<SvcIgnored>("/provider-sessions/${session.id}/start", emptyMap())
                    Toast.show(if (session.isPickup) LF("%@ picked up and on the walk.", row.name) else LF("%@ is here and in the class.", row.name))
                } else {
                    Toast.show(if (session.isPickup) LF("%@ picked up.", row.name) else LF("%@ is here.", row.name))
                }
                announceDataChanged()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                working = null
            }
        }
    }

    fun move(row: SvcSessionPet, step: Int) {
        val order = stops.map { it.appointmentId }.toMutableList()
        val at = order.indexOf(row.appointmentId)
        if (at < 0 || (at + step) !in order.indices) return
        val other = order[at + step]
        order[at + step] = order[at]
        order[at] = other
        scope.launch {
            try {
                API.post<SvcIgnored>("/provider-sessions/${session.id}/pickups", mapOf("order" to order))
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            }
        }
    }

    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (boxed) Modifier.background(Theme.surface, shape).border(1.dp, Theme.borderSoft, shape) else Modifier),
    ) {
        val first = rows.firstOrNull()?.id
        for (row in rows) {
            key(row.id) {
                if (row.id != first) RowDivider()
                val entry = session.entry(row.appointmentId)
                val stop = if (session.isPickup && !row.gone) stops.indexOfFirst { it.id == row.id }.takeIf { it >= 0 }?.plus(1) else null
                val withThem = row.status == "in_progress" || row.status == "completed"
                val gaps = entry?.reportMissing ?: emptyList()
                val canCheckIn = session.active && workspace.can(Permission.appointmentsCheckIn) && row.status == "scheduled"
                val started = (DateText.parse(session.startsAt) ?: Instant.MAX) <= Instant.now()
                val canMenu = session.active && !row.gone &&
                    (workspace.can(Permission.appointmentsCancel) || (session.isPickup && workspace.can(Permission.appointmentsEdit))) &&
                    (row.status == "scheduled" || row.status == "checked_in")
                Row(
                    Modifier.fillMaxWidth().alpha(if (row.gone) 0.6f else 1f).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box {
                        PetAvatar(name = row.name, species = row.species, photoURL = row.photoUrl, size = 40.dp)
                        if (stop != null) {
                            val stopLabel = LF("Stop %d", stop)
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 4.dp, y = 4.dp)
                                    .size(20.dp)
                                    .background(Theme.blueFixed, CircleShape)
                                    .border(2.dp, Theme.surface, CircleShape)
                                    .semantics { contentDescription = stopLabel },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$stop", style = brandText(10.4, FontWeight.ExtraBold).copy(fontFeatureSettings = "tnum"), color = Color.White)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        FlowLayout(spacing = 8.dp) {
                            Text(
                                row.name,
                                style = Theme.text(16, FontWeight.Bold).copy(textDecoration = if (row.gone) TextDecoration.LineThrough else null),
                                color = Theme.inkStrong,
                                modifier = Modifier.plainButton { push(SvcSessionRoute.provider(ProviderRoute.appointment(row.appointmentId))) },
                            )
                            Row(
                                Modifier
                                    .background(Theme.surfaceMuted, CapsuleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).background(ProviderState.appointment(row.status).color, CircleShape))
                                Text(
                                    statusLabel(row.status) + (if (row.status == "checked_in") (entry?.arrivedAt?.let { " · " + DateText.time(it) } ?: "") else ""),
                                    style = brandText(11.2, FontWeight.Bold),
                                    color = Theme.inkStrong,
                                )
                            }
                        }
                        Text(row.clientName, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val allergies = entry?.allergies ?: emptyList()
                        val onMyWayAt = entry?.onMyWayAt
                        if (allergies.isNotEmpty() || withThem || (onMyWayAt != null && row.status == "scheduled")) {
                            FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
                                if (allergies.isNotEmpty()) {
                                    Chip(text = LF("Allergy: %@", allergies.joinToString(", ")), tone = Chip.Tone.danger, icon = "triangle-alert")
                                }
                                if (withThem) {
                                    if (gaps.isEmpty()) Chip(text = L("Report done"), tone = Chip.Tone.good, icon = "clipboard-check")
                                    else Chip(text = LF("Report: %@", SvcReportText.fieldsText(gaps)), tone = Chip.Tone.warn, icon = "clipboard-list")
                                }
                                if (onMyWayAt != null && row.status == "scheduled") {
                                    Chip(text = LF("On the way since %@", DateText.time(onMyWayAt)), tone = Chip.Tone.muted, icon = "navigation")
                                }
                            }
                        }
                        if (session.isPickup && !row.gone) {
                            val address = entry?.address.nonEmpty
                            if (address != null) {
                                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                                    Glyph("map-pin", 14.dp, tint = Theme.blue, modifier = Modifier.padding(top = 1.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(address, style = Theme.text(12), color = Theme.inkStrong)
                                        entry?.accessNotes.nonEmpty?.let { access ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Glyph("key-round", 12.dp, tint = Theme.inkSoft)
                                                Text(access, style = Theme.text(12), color = Theme.inkSoft)
                                            }
                                        }
                                    }
                                }
                            } else {
                                FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
                                    Glyph("map-pin", 14.dp, tint = Theme.mango)
                                    Text(L("No address to pick up from."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
                                    Text(
                                        L("Add it in the client"),
                                        style = Theme.text(12, FontWeight.Bold),
                                        color = Theme.blue,
                                        modifier = Modifier.plainButton { push(SvcSessionRoute.provider(ProviderRoute.client(row.providerClientId))) },
                                    )
                                }
                            }
                        }
                        if (canCheckIn) {
                            FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 8.dp)) {
                                if (session.isPickup && entry?.address.nonEmpty != null && entry?.onMyWayAt == null) {
                                    PMButton(L("On my way"), systemImage = "navigation", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                                        val client = SvcClientRef(
                                            id = row.providerClientId, name = row.clientName, firstName = NameText.firstName(row.clientName),
                                            phone = entry?.clientPhone, onPawMiaw = entry?.familyOnApp,
                                        )
                                        onMyWay = SvcOnMyWayTarget(id = row.appointmentId, appointmentId = row.appointmentId, client = client, petNames = row.name)
                                    }
                                }
                                PMButton(
                                    if (session.isPickup) L("Picked up") else L("Arrived"),
                                    systemImage = if (session.isPickup) "badge-check" else "door-open",
                                    size = ButtonSize.compact,
                                    isWorking = working == row.id,
                                    enabled = working == null,
                                ) { arrived(row) }
                                if (started) {
                                    PMButton(L("Didn’t come"), systemImage = "circle-slash", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                                        missed = SvcMissedTarget(
                                            id = row.appointmentId, petName = row.name, clientName = row.clientName,
                                            subtitle = "${session.serviceLabel} · ${row.clientName}", mode = "no_show",
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (canMenu) {
                        AppMenu(
                            label = { IconCircleLabel(size = 32.dp, bordered = false) },
                            accessibilityLabel = LF("More for %@", row.name),
                        ) {
                            if (session.isPickup && workspace.can(Permission.appointmentsEdit) && stop != null) {
                                if (stop > 1) item(L("Pick up earlier"), icon = "arrow-up") { move(row, -1) }
                                if (stop < stops.size) item(L("Pick up later"), icon = "arrow-down") { move(row, 1) }
                            }
                            if (workspace.can(Permission.appointmentsCancel)) {
                                item(L("Take the pet out"), icon = "x", destructive = true) {
                                    missed = SvcMissedTarget(
                                        id = row.appointmentId, petName = row.name, clientName = row.clientName,
                                        subtitle = LF("Take %@ out of %@?", row.name, session.serviceLabel), mode = "cancel",
                                        startsAt = session.startsAt,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    missed?.let { target ->
        AppSheet(onDismissRequest = { missed = null }) {
            SchedMissedSheet(
                appointmentId = target.id, petName = target.petName, clientName = target.clientName, subtitle = target.subtitle, mode = target.mode,
                startsAt = target.startsAt,
                cancelledText = L("Taken out. The place is open."),
                onDone = { scope.launch { reload() } },
            )
        }
    }
    onMyWay?.let { target ->
        AppSheet(onDismissRequest = { onMyWay = null }) {
            SvcOnMyWaySheet(target = target, onSent = { scope.launch { reload() } })
        }
    }
}

// MARK: - Add pets

/**
 * Give a client's pets a place (web `AddPets`): find the client, tick the pets — with the one
 * client-and-pet picker (server search from 2 letters, 20 at a time). Full → add anyway, or the waitlist.
 */
@Composable
private fun SvcAddPets(session: SvcSessionDetail, reload: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<PickedClient?>(null) }
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    var full by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }

    val dogsOnly = session.serviceCategory == "walker" || session.serviceCategory == "trainer"

    /** Why a pet can't be given a place, or that it waits already (web: the check, the hourglass). */
    fun note(pet: PickedPet): Pair<String, Boolean>? {
        if (session.staying.any { it.providerClientPetId == pet.id }) return L("Already in this session") to true
        if (dogsOnly && pet.petSpecies?.trim()?.lowercase() != "dog") return L("Group walks and classes are for dogs.") to true
        if ((session.waitlist ?: emptyList()).any { it.providerClientPetId == pet.id }) return L("On the waitlist") to false
        return null
    }

    fun reset() {
        client = null
        picked = emptyList()
        full = null
    }

    suspend fun give(allowOver: Boolean) {
        val chosen = client ?: return
        if (picked.isEmpty()) return
        working = true
        try {
            val body = mutableMapOf<String, Any?>("providerClientId" to chosen.id, "providerClientPetIds" to picked)
            if (allowOver) body["allowOverCapacity"] = true
            SvcAPI.send<SvcIgnored>("POST", "/provider-sessions/${session.id}/pets", body = body)
            Toast.show(LF("Added to %@.", session.serviceLabel))
            reset()
            announceDataChanged()
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (svcCode(e) == "session_full") {
                full = (e as? SvcRefusal)?.body["placesLeft"].int ?: 0
                return
            }
            Toast.error(svcMessage(e))
        } finally {
            working = false
        }
    }

    suspend fun waitlist() {
        val chosen = client ?: return
        working = true
        try {
            for (pet in picked) {
                API.post<SvcIgnored>("/provider-sessions/${session.id}/waitlist", mapOf("providerClientId" to chosen.id, "providerClientPetId" to pet))
            }
            // Only a family on the app with online booking gets told; the rest are called.
            Toast.show(
                if (chosen.householdId != null && (session.onlineBookingOpen ?: session.onlineBooking ?: false)) L("On the waitlist. They are told when a place opens.")
                else L("On the waitlist. Call them when a place opens."),
            )
            reset()
            announceDataChanged()
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(e)
        } finally {
            working = false
        }
    }

    SectionCard(L("Add pets"), icon = "plus", accent = Theme.aqua) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ClientPetPicker(
                client = stateOf({ client }, { client = it; full = null }),
                petIds = stateOf({ picked }, { picked = it; full = null }),
                pets = ClientPetPicker.Pets.several,
                label = L("Find a client"),
                petNote = { note(it) },
            )
            val chosen = client
            if (chosen != null && chosen.activePets.isNotEmpty()) {
                val left = full
                if (left != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.wash(Theme.mango, 18.0), RoundedCornerShape(Theme.boxRadius))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (left > 0) LF("Only %d places left.", left) else LF("%@ is full — add them anyway, or put them on the waitlist.", session.serviceLabel),
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.inkStrong,
                        )
                        FlowLayout(spacing = 8.dp) {
                            PMButton(L("Put on the waitlist"), systemImage = "list-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = working) {
                                scope.launch { waitlist() }
                            }
                            PMButton(L("Add anyway"), systemImage = "plus", size = ButtonSize.compact, isWorking = working) {
                                scope.launch { give(allowOver = true) }
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        PMButton(L("Give them a place"), systemImage = "plus", size = ButtonSize.compact, isWorking = working, enabled = picked.isNotEmpty()) {
                            scope.launch { give(allowOver = false) }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Waitlist

/** Who waits for a place (web `Waitlist`): give it, or take them off the list. */
@Composable
private fun SvcWaitlist(
    session: SvcSessionDetail,
    entries: List<SvcWaitEntry>,
    canManage: Boolean,
    reload: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }

    suspend fun act(entry: SvcWaitEntry, give: Boolean) {
        working = entry.id
        try {
            if (give) {
                API.post<SvcIgnored>(
                    "/provider-sessions/${session.id}/pets",
                    mapOf("providerClientId" to entry.providerClientId, "providerClientPetIds" to listOf(entry.providerClientPetId)),
                )
                Toast.show(LF("Added to %@.", session.serviceLabel))
            } else {
                API.delete<SvcIgnored>("/provider-sessions/${session.id}/waitlist/${entry.id}")
            }
            announceDataChanged()
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(e)
        } finally {
            working = null
        }
    }

    SectionCard(L("Waitlist") + " · ${entries.size}", icon = "hourglass", accent = Theme.mango) {
        Column {
            val first = entries.firstOrNull()?.id
            for (entry in entries) {
                key(entry.id) {
                    if (entry.id != first) RowDivider()
                    FlowLayout(
                        spacing = 12.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PetAvatar(name = entry.petName, photoURL = entry.petPhotoUrl, size = 32.dp)
                            Column {
                                Text(entry.petName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Glyph("clock-3", 12.dp, tint = Theme.inkSoft)
                                    Text(
                                        (if (entry.status == "notified") L("Told a place opened")
                                        else if ((session.placesLeft ?: 0) > 0) L("A place is free: give it, or call the family")
                                        else LF("Waiting since %@", DateText.dayTime(entry.createdAt))) + " · " + entry.clientName,
                                        style = Theme.text(12),
                                        color = Theme.inkSoft,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (canManage) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                PMButton(L("Take off the list"), variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = working == null) {
                                    scope.launch { act(entry, give = false) }
                                }
                                PMButton(L("Give the place"), size = ButtonSize.compact, isWorking = working == entry.id, enabled = session.full != true) {
                                    scope.launch { act(entry, give = true) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Plan or change a session

private val svcSessionLengths = listOf(30, 45, 60, 90, 120, 180)

/** One family's services — a drop-in at home, an assessment, a consult — aren't run as a group. */
private val svcNotGrouped = setOf("walker_drop_in", "training_assessment", "behavior_consult")

/**
 * Its time, who leads it, the room, how many places, the price per pet, and whether families book a
 * place from the app (web `SessionFormSheet`). A clash or the hours are said and saved only on purpose.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SvcSessionFormSheet(
    session: SvcSessionDetail?,
    people: List<SvcMember>,
    modifier: Modifier = Modifier,
    onSaved: (String) -> Unit,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("walker") }
    /** The catalog service it charges (a group walk or class, another of the business's, or "another" named by hand). */
    var serviceId by remember { mutableStateOf("group_walk") }
    var catalog by remember { mutableStateOf<List<SvcCatalogService>>(emptyList()) }
    var everyService by remember { mutableStateOf<List<SvcCatalogService>>(emptyList()) }
    var priced by remember { mutableStateOf<List<SvcPricedService>>(emptyList()) }
    /** Whether this location takes online bookings (null: not known). */
    var onlineOpen by remember { mutableStateOf<Boolean?>(null) }
    val label = remember { mutableStateOf("") }
    val startMode = remember { mutableStateOf("meeting_point") }
    val meetingPoint = remember { mutableStateOf("") }
    val date = remember { mutableStateOf(Instant.now()) }
    val start = remember { mutableStateOf(Instant.now()) }
    val length = remember { mutableStateOf(60) }
    val memberId = remember { mutableStateOf("") }
    val roomId = remember { mutableStateOf("") }
    var rooms by remember { mutableStateOf<List<SvcRoom>>(emptyList()) }
    val capacity = remember { mutableStateOf("6") }
    val price = remember { mutableStateOf<Int?>(null) }
    val online = remember { mutableStateOf(true) }
    val notes = remember { mutableStateOf("") }
    var clash by remember { mutableStateOf<String?>(null) }
    val clashOk = remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf<String?>(null) }
    val hoursOk = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var loadedPeople by remember { mutableStateOf<List<SvcMember>>(emptyList()) }
    val form = remember { FormState() }
    // `form.scrollTarget = "start"` on a refused start: the field is brought into view.
    val startRequester = remember { BringIntoViewRequester() }
    var startScroll by remember { mutableIntStateOf(0) }

    /** The leaders given, else the ones loaded here (the page's list may still be on its way). */
    val leaders = if (people.isEmpty()) loadedPeople else people
    val leadService = if (category == "trainer") "group_class" else "group_walk"
    val otherService = if (category == "trainer") "training_other" else "walk_other"

    /**
     * The services of this kind: the group walk or class first, the business's own, "another" last —
     * the group service and "another" stay on offer even if the business never picked them.
     */
    val services: List<SvcCatalogService> = run {
        val own = catalog.filter { it.category == category && it.id !in svcNotGrouped }
        val always = listOf(leadService, otherService).filter { id -> own.none { it.id == id } }.mapNotNull { id -> everyService.firstOrNull { it.id == id } }
        fun rank(id: String): Int = if (id == leadService) 0 else if (id == otherService) 2 else 1
        (own + always).withIndex().sortedWith(compareBy({ rank(it.value.id) }, { it.index })).map { it.value }
    }

    /** As the bill prices it: the service's Catalog price, else a Catalog service with the session's or the service's name. */
    fun priceOf(service: SvcCatalogService?, name: String): Int? {
        val billing = service?.defaultBillingItem
        if (billing != null && billing.active == true) return billing.defaultUnitAmount ?: 0
        val names = listOf(name, service?.label ?: "").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (entry in names) {
            priced.firstOrNull { it.name.trim().lowercase() == entry }?.let { return it.defaultUnitAmount ?: 0 }
        }
        return null
    }

    val catalogAmount = priceOf(services.firstOrNull { it.id == serviceId }, label.value)

    /** A walk or a class: the group service of that kind, its name when it wasn't named by hand, an hour long. */
    fun choose(value: String) {
        val wasDefault = label.value.isEmpty() || services.any { it.label == label.value } || label.value == L("Group walk") || label.value == L("Group class")
        category = value
        serviceId = if (value == "trainer") "group_class" else "group_walk"
        if (value != "walker") startMode.value = "meeting_point"
        if (wasDefault) label.value = if (value == "trainer") L("Group class") else L("Group walk")
        length.value = 60
    }

    /** Choosing a service names the session after it (unless it was named by hand) and sets its usual length. */
    fun chooseService(id: String) {
        val next = services.firstOrNull { it.id == id }
        val named = label.value.trim().isEmpty() || services.any { it.label == label.value } || label.value == L("Group walk") || label.value == L("Group class")
        serviceId = id
        // "Another" is named by hand: its name field starts empty, the length stays.
        if (id == otherService) {
            if (named) label.value = ""
            return
        }
        if (next != null && named) label.value = next.label
        next?.minutes?.let { if (it >= 15) length.value = it }
    }

    val serviceOptions = services.map { service ->
        val other = service.id == otherService
        // "Another" is priced by the name typed for it, once it is the one chosen.
        val amount = priceOf(service, if (other) (if (serviceId == service.id) label.value else "") else service.label)
        val title = if (other) (if (category == "trainer") L("Another class") else L("Another walk")) else service.label
        val firstPart = if (other) L("You name it") else LF("%d min", service.minutes ?: 60)
        val secondPart = amount?.let { LF("%@ per pet", Money.format(it, workspace.currency)) } ?: L("No price in the Catalog")
        OptionCard(
            service.id,
            title = title,
            description = "$firstPart · $secondPart",
            icon = if (category == "trainer") "graduation-cap" else "dog",
            accent = if (category == "trainer") Theme.lavender else Theme.aqua,
        )
    }

    val lengthOptions = (if (length.value in svcSessionLengths) svcSessionLengths else (svcSessionLengths + length.value).sorted())
        .map { minutes -> minutes to LF("%d min", minutes) }
    val leaderOptions = leaders.map { member -> member.id to member.shownName }
    val roomOptions = listOf("" to L("No room")) +
        rooms.filter { (it.status ?: "active") == "active" || it.id == roomId.value }.map { room -> room.id to room.name }
    val startOptions = buildList<Triple<String, String, String?>> {
        add(Triple("meeting_point", L("At a meeting point"), L("Families bring their dogs to the place and time.")))
        if (category == "walker") add(Triple("pickup", L("Picked up at home"), L("The walker collects each dog at its address, in an order you set.")))
    }

    suspend fun setUp() {
        if (ready) return
        val zone = workspace.timeZone
        if (session != null) {
            category = session.serviceCategory ?: "walker"
            serviceId = session.serviceId ?: ""
            label.value = session.serviceLabel
            startMode.value = session.startMode ?: "meeting_point"
            meetingPoint.value = session.meetingPoint ?: ""
            val startDate = DateText.parse(session.startsAt) ?: Instant.now()
            date.value = startDate
            start.value = startDate
            length.value = max(15, SvcTime.minutesBetween(session.startsAt, session.endsAt))
            memberId.value = session.assignedMember?.id ?: ""
            roomId.value = session.roomId ?: ""
            capacity.value = (session.capacity ?: 1).toString()
            price.value = session.priceAmount
            online.value = session.onlineBooking ?: true
            notes.value = session.notes ?: ""
        } else {
            label.value = L("Group walk")
            start.value = LocalDate.now(zone).atTime(9, 0).atZone(zone).toInstant()
            if (people.isEmpty()) loadedPeople = svcSessionLeaders(svcLoadMembers(workspace), workspace = workspace)
            val list = if (people.isEmpty()) loadedPeople else people
            memberId.value = list.firstOrNull { it.isCurrentMember == true && !workspace.can(Permission.appointmentsAssignAnyone) }?.id ?: list.firstOrNull()?.id ?: ""
        }
        val loaded = attempt { API.get<List<SvcRoom>>("/provider-rooms", mapOf("locationId" to (session?.locationId ?: workspace.locationId))) } ?: emptyList()
        rooms = loaded.filter { it.kind != "ward" && it.kind != "cage" }
        ready = true
        // Every service there is (a group walk or class stays on offer even if the business never picked it),
        // the Catalog's priced services, and whether this location takes online bookings.
        catalog = SvcServices.load("provider")
        everyService = SvcServices.load("all")
        val page = attempt {
            API.get<Page<SvcPricedService>>("/provider-catalog", mapOf("limit" to "100", "offset" to "0", "itemType" to "service", "active" to "true"))
        }
        priced = page?.items ?: emptyList()
        onlineOpen = svcLocationTakesOnlineBookings(session?.locationId ?: workspace.locationId)
    }

    /** The day from the date picker and the clock from the time picker, on the business's calendar. */
    fun startsAt(): Instant? {
        val zone = workspace.timeZone
        val day = date.value.atZone(zone).toLocalDate()
        val time = start.value.atZone(zone).toLocalTime()
        return ZonedDateTime.of(day, LocalTime.of(time.hour, time.minute), zone).toInstant()
    }

    fun submit() {
        val places = capacity.value.trim().toIntOrNull()
        if (!form.check(
                listOf(
                    "label" to (if (label.value.nonEmpty == null) L("Name the session first.") else null),
                    "start" to (if (startsAt() == null) L("Choose a date and time.") else null),
                    "member" to (if (memberId.value.isEmpty()) L("Choose a person") else null),
                    "capacity" to (if ((places ?: 0) < 1) L("Choose how many places it has.") else null),
                    // A clash or the hours are saved only once confirmed: said, and scrolled to.
                    "hours" to (if (hours != null && !hoursOk.value) L("Confirm below to plan it anyway, or change the time.") else null),
                    "clash" to (if (clash != null && !clashOk.value) L("Confirm below to plan it anyway, or change the time.") else null),
                ),
            )
        ) return
        val begins = startsAt() ?: return
        working = true
        val isPickup = startMode.value == "pickup" && category == "walker"
        val body = mutableMapOf<String, Any?>(
            "serviceCategory" to category,
            "serviceLabel" to label.value.nonEmpty,
            "startMode" to (if (isPickup) "pickup" else "meeting_point"),
            "meetingPoint" to (if (isPickup) null else meetingPoint.value.nonEmpty),
            "startsAt" to DateText.iso(begins),
            "endsAt" to DateText.iso(begins.plusSeconds(length.value.toLong() * 60)),
            "assignedOrganizationMemberId" to memberId.value.ifEmpty { null },
            "roomId" to roomId.value.ifEmpty { null },
            "capacity" to places,
            "priceAmount" to price.value,
            "onlineBooking" to online.value,
            "notes" to notes.value.nonEmpty,
        )
        serviceId.nonEmpty?.let { body["serviceId"] = it }
        if (clash != null && clashOk.value) body["allowDoubleBooking"] = true
        if (hours != null && hoursOk.value) body["outsideHours"] = true
        val location = workspace.locationId
        if (session == null && location != null) body["locationId"] = location
        scope.launch {
            try {
                val saved = SvcAPI.send<SvcSessionDetail>(
                    if (session == null) "POST" else "PATCH",
                    session?.let { "/provider-sessions/${it.id}" } ?: "/provider-sessions",
                    body = body,
                )
                Toast.show(if (session == null) L("Session planned.") else L("Session saved."))
                announceDataChanged()
                dismiss()
                onSaved(saved.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (svcCode(e)) {
                    "start_in_past" -> {
                        form.errors = mapOf("start" to ((e as? SvcRefusal)?.api?.field("startsAt") ?: svcMessage(e)))
                        form.scrollTarget = "start"
                        startScroll += 1
                        Toast.error(svcMessage(e))
                    }
                    "visit_conflict" -> {
                        clash = svcMessage(e)
                        clashOk.value = false
                    }
                    "outside_hours" -> {
                        hours = svcMessage(e)
                        hoursOk.value = false
                    }
                    else -> form.apply(
                        svcAPIError(e),
                        known = mapOf("serviceLabel" to "label", "startsAt" to "start", "capacity" to "capacity", "priceAmount" to "price", "assignedOrganizationMemberId" to "member"),
                    )
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = session?.serviceLabel ?: L("New group session"),
        subtitle = session?.let { DateText.dayTime(it.startsAt) } ?: L("A group walk or class at one time, with places that pets of different families take."),
        systemImage = "users-round",
        accent = ModalAccent.aqua,
        isDirty = ready,
        form = form,
        primaryTitle = if (clash != null && clashOk.value) L("Plan it anyway") else (if (session == null) L("Plan the session") else L("Save changes")),
        isWorking = working,
        cancelTitle = L("Back"),
        primaryIcon = "calendar-plus",
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        ModalSection(title = L("What it is"), required = true) {
            FlowLayout(spacing = 8.dp) {
                SvcChoiceChip(title = L("A walk"), isSelected = category == "walker", wash = true, height = 36.dp) { choose("walker") }
                SvcChoiceChip(title = L("A class"), isSelected = category == "trainer", wash = true, height = 36.dp) { choose("trainer") }
            }
            if (services.isNotEmpty()) {
                // Web OptionCardGrid: each service a card, a dog for a walk, a cap for a class.
                FormField(label = L("Service it charges"), required = true) {
                    OptionCards(options = serviceOptions, selection = stateOf<String>({ serviceId }, { chooseService(it) }))
                }
            }
            PMTextField(
                L("Name families see"),
                text = label,
                key = "label",
                form = form,
                prompt = if (category == "trainer") L("Puppy class, agility…") else L("Beach walk, hike…"),
                required = true,
            )
        }
        ModalSection(title = L("How it starts"), required = true) {
            ChoiceCards(options = startOptions, selection = startMode)
            if (startMode.value == "meeting_point" || category != "walker") {
                val meetingLabel = L("Meeting point")
                FieldChrome(modifier = Modifier.semantics { contentDescription = meetingLabel }) {
                    PlainTextField(meetingPoint, prompt = L("Parque México, north gate"), modifier = Modifier.weight(1f))
                }
            } else {
                Text(L("Each dog is picked up at its client’s address; a client with none is marked so you can add it."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        ModalSection(title = L("When")) {
            DateTimeField(label = L("Date"), date = date, components = setOf(DatePickerComponent.date), required = true)
            DateTimeField(
                label = L("Starts"),
                date = start,
                components = setOf(DatePickerComponent.hourAndMinute),
                key = "start",
                form = form,
                required = true,
                modifier = Modifier.bringIntoViewRequester(startRequester),
            )
            MenuField(label = L("Length"), options = lengthOptions, selection = length, required = true)
        }
        // Web: each its own section, the select under the section's title.
        ModalSection(title = L("Who leads it"), required = true) {
            StaysPlainMenu(options = leaderOptions, selection = memberId, accessibility = L("Who leads it"), key = "member", form = form)
        }
        ModalSection(title = L("Room or place")) {
            StaysPlainMenu(options = roomOptions, selection = roomId, accessibility = L("Room or place"))
        }
        ModalSection(title = L("Places"), subtitle = L("How many pets it takes"), required = true) {
            val placesLabel = L("Places")
            StaysPlainField(
                prompt = "6",
                text = stateOf({ capacity.value }, { value -> capacity.value = value.filter { it.isDigit() }.take(3) }),
                key = "capacity",
                form = form,
                keyboard = KeyboardType.Number,
                modifier = Modifier.width(112.dp).semantics { contentDescription = placesLabel },
            )
        }
        ModalSection(
            title = L("Price per pet"),
            subtitle = catalogAmount?.let { LF("Empty: %@, the service’s price in the Catalog.", Money.format(it, workspace.currency)) }
                ?: L("This service has no price in the Catalog: set one here, or it is charged at 0."),
        ) {
            val priceLabel = L("Price per pet")
            StaysPlainMoney(
                cents = price,
                prompt = "0.00",
                currency = workspace.currency,
                key = "price",
                form = form,
                modifier = Modifier.widthIn(max = 220.dp).semantics { contentDescription = priceLabel },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleRow(title = L("Families book a place from the app"), subtitle = L("Families linked to you see it and take a place until it’s full."), isOn = online)
            if (online.value && onlineOpen == false) {
                Text(
                    L("This location doesn’t take online bookings yet: families see it once it is on in Availability."),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkStrong,
                )
            }
        }
        ModalSection(title = L("Notes")) {
            val notesLabel = L("Notes")
            StaysPlainField(prompt = "", text = notes, multiline = true, lines = 2..6, modifier = Modifier.semantics { contentDescription = notesLabel })
        }
        hours?.let { problem ->
            Column(Modifier.formScrollTarget("hours", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoticeCard(tone = NoticeCard.Tone.warning, title = problem)
                ToggleRow(title = L("Plan it anyway"), isOn = hoursOk)
            }
        }
        clash?.let { problem ->
            Column(Modifier.formScrollTarget("clash", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoticeCard(tone = NoticeCard.Tone.warning, title = L("Double booking"), message = problem)
                ToggleRow(title = L("Book it anyway — I know they overlap"), isOn = clashOk)
            }
        }
    }

    LaunchedEffect(Unit) { setUp() }
    LaunchedEffect(startScroll) { if (startScroll > 0) runCatching { startRequester.bringIntoView() } }
}

/** Swift `try?` on an await: the answer, or null when it failed (a cancel still cancels). */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

// MARK: - Every dog's report on one screen

/**
 * Every dog's report on one screen (web `GroupReportSheet`): what was the same for
 * the whole group set once for everyone, then each dog's own. Each report is still
 * the pet's own and reaches its family on its visit.
 */
@Composable
fun SvcGroupReportSheet(
    session: SvcSessionDetail,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<Map<String, SvcReport>>(emptyMap()) }
    var originals by remember { mutableStateOf<Map<String, SvcReport>>(emptyMap()) }
    val skillsForAll = remember { mutableStateOf("") }
    var homeworkForAll by remember { mutableStateOf("") }
    var groupPhoto by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf<SvcReportTarget?>(null) }

    val kind = SvcReportText.kind(session.serviceCategory)
    val pets = session.rosterRows.filter { it.status == "in_progress" || it.status == "completed" }

    fun report(pet: SvcSessionPet): SvcReport = drafts[pet.appointmentId] ?: SvcReport(kind = kind ?: "walk")

    fun edit(pet: SvcSessionPet, change: (SvcReport) -> Unit) {
        val next = report(pet).copy()
        change(next)
        drafts = drafts + (pet.appointmentId to next)
    }

    fun forAll(change: (SvcReport) -> Unit) {
        drafts = drafts.mapValues { (_, value) -> value.copy().also(change) }
    }

    fun setUp() {
        if (kind == null || drafts.isNotEmpty()) return
        val next = LinkedHashMap<String, SvcReport>()
        for (pet in pets) {
            val stored = session.entry(pet.appointmentId)?.report
            next[pet.appointmentId] = if (stored?.kind == kind) stored else SvcReport(kind = kind)
        }
        drafts = next
        originals = next
        // Opening on saved reports: the homework they share shows here.
        val homeworks = next.values.map { it.homework ?: "" }.toSet()
        homeworkForAll = if (homeworks.size == 1) homeworks.first() else ""
    }

    fun save() {
        saving = true
        scope.launch {
            var count = 0
            try {
                for (pet in pets) {
                    val draft = drafts[pet.appointmentId] ?: continue
                    if (draft == originals[pet.appointmentId]) continue
                    API.putJson<SvcIgnored, SvcReport>("/provider-appointments/${pet.appointmentId}/report", draft)
                    originals = originals + (pet.appointmentId to draft)
                    count += 1
                }
                Toast.show(if (count > 0) LF("Reports saved for %d pets.", count) else L("Nothing changed."))
                announceDataChanged()
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("Group report"),
        subtitle = "${session.serviceLabel} · " + LP(pets.size, "1 pet", "%d pets"),
        systemImage = "clipboard-check",
        accent = ModalAccent.aqua,
        isDirty = drafts != originals,
        primaryTitle = L("Save the reports"),
        isWorking = saving,
        cancelTitle = L("Back"),
        primaryIcon = "clipboard-check",
        primaryDisabled = pets.isEmpty(),
        onPrimary = { save() },
        modifier = modifier,
    ) {
        if (pets.isEmpty() || kind == null || kind == "groom") {
            Text(L("Reports open once the session starts."), style = Theme.text(14), color = Theme.inkSoft)
        } else {
            ModalSection(title = L("For everyone"), subtitle = L("Fills every dog’s report; change any dog below.")) {
                MenuField(
                    label = L("Behaviour"),
                    options = listOf("" to L("Choose for everyone")) + SvcReportText.behaviours.map { it to SvcReportText.option(it) },
                    selection = stateOf({ "" }, { value -> if (value.isNotEmpty()) forAll { it.behaviour = value } }),
                )
                if (kind == "walk") {
                    PMButton(L("Everyone peed"), variant = ButtonVariant.outline, fullWidth = true) { forAll { it.pee = true } }
                } else {
                    MenuField(
                        label = L("Progress"),
                        options = listOf("" to L("Choose for everyone")) + SvcReportText.progress.map { it to SvcReportText.option(it) },
                        selection = stateOf({ "" }, { value -> if (value.isNotEmpty()) forAll { it.progress = value } }),
                    )
                    FormField(label = L("Skills practised (comma between them)")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FieldChrome(modifier = Modifier.weight(1f)) {
                                PlainTextField(skillsForAll, prompt = L("Sit, stay, recall"), modifier = Modifier.weight(1f))
                            }
                            PMButton(L("Add to all"), variant = ButtonVariant.outline, enabled = skillsForAll.value.nonEmpty != null) {
                                val skills = svcSplitSkills(skillsForAll.value)
                                forAll { report -> for (skill in skills) if (skill !in report.skills) report.skills = report.skills + skill }
                                skillsForAll.value = ""
                            }
                        }
                    }
                    // Every dog's homework follows what is typed here.
                    PMTextField(
                        L("Homework"),
                        text = stateOf({ homeworkForAll }, { value ->
                            homeworkForAll = value
                            forAll { it.homework = value.nonEmpty }
                        }),
                        prompt = L("The same for everyone"),
                    )
                }
                pets.firstOrNull()?.let { first ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SvcPhotoSlot(
                            label = L("Group photo"),
                            url = groupPhoto,
                            disabled = false,
                            appointmentId = first.appointmentId,
                            modifier = Modifier.widthIn(max = 224.dp),
                            onChange = { url ->
                                // Removing it takes it off the dogs that still have it, not their own photos.
                                val previous = groupPhoto
                                groupPhoto = url
                                if (url != null) forAll { it.photoUrl = url }
                                else if (previous != null) forAll { if (it.photoUrl == previous) it.photoUrl = null }
                            },
                        )
                        Text(L("One photo for every dog’s report."), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
            }
            ModalSection(title = L("Each dog")) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (pet in pets) {
                        key(pet.id) {
                            SvcDogReportCard(
                                pet = pet,
                                session = session,
                                report = report(pet),
                                edit = { change -> edit(pet, change) },
                                onFull = {
                                    full = SvcReportTarget(
                                        subtitle = "${pet.name} · ${session.serviceLabel}",
                                        pets = listOf(SvcReportPet(id = pet.appointmentId, name = pet.name)),
                                        canWrite = true,
                                    )
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("users-round", 14.dp, tint = Theme.inkSoft)
                    Text(L("Each family sees only their own dog’s report."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    }

    LaunchedEffect(Unit) { setUp() }
    full?.let { target ->
        AppSheet(onDismissRequest = { full = null }) { SvcReportSheet(target = target, onSaved = onSaved) }
    }
}

/** One dog (web: the "Each dog" item): its fields as selects, a note and its photo; mango-edged while something it must have is missing. */
@Composable
private fun SvcDogReportCard(
    pet: SvcSessionPet,
    session: SvcSessionDetail,
    report: SvcReport,
    edit: ((SvcReport) -> Unit) -> Unit,
    onFull: () -> Unit,
) {
    val missing = SvcReportText.missing(session.serviceCategory, report)
    val required = SvcReportText.required(session.serviceCategory, report)
    val shape = RoundedCornerShape(Theme.boxRadius)
    val current by rememberUpdatedState(report)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (missing.isEmpty()) Theme.borderSoft else Theme.mix(Theme.mango, 55.0, Theme.borderSoft), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PetAvatar(name = pet.name, species = pet.species, photoURL = pet.photoUrl, size = 32.dp)
            Text(
                pet.name,
                style = Theme.text(16, FontWeight.Bold),
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (missing.isNotEmpty()) {
                Text(LF("Missing: %@", SvcReportText.fieldsText(missing)), style = Theme.text(11.2, FontWeight.Bold), color = Theme.inkSoft)
            }
        }
        // The fields the report must have carry the red mark (web `Field required`).
        if (report.kind == "walk") {
            FormField(label = L("Pee"), required = required.contains("pee")) {
                StaysPlainMenu(
                    options = listOf("" to "—", "yes" to L("Yes"), "no" to L("No")),
                    selection = stateOf({ current.pee?.let { if (it) "yes" else "no" } ?: "" }, { value -> edit { it.pee = if (value.isEmpty()) null else value == "yes" } }),
                    accessibility = L("Pee"),
                )
            }
            FormField(label = L("Poop")) {
                StaysPlainMenu(
                    options = listOf("" to "—") + SvcReportText.stool.map { it to SvcReportText.option(it) },
                    selection = stateOf({ current.poop ?: "" }, { value -> edit { it.poop = value.nonEmpty } }),
                    accessibility = L("Poop"),
                )
            }
        } else {
            FormField(label = L("Skills"), required = required.contains("skills")) {
                SvcSkillsField(skills = report.skills) { skills -> edit { it.skills = skills } }
            }
            FormField(label = L("Progress"), required = required.contains("progress")) {
                StaysPlainMenu(
                    options = listOf("" to "—") + SvcReportText.progress.map { it to SvcReportText.option(it) },
                    selection = stateOf({ current.progress ?: "" }, { value -> edit { it.progress = value.nonEmpty } }),
                    accessibility = L("Progress"),
                )
            }
        }
        FormField(label = L("Behaviour"), required = required.contains("behaviour")) {
            StaysPlainMenu(
                options = listOf("" to "—") + SvcReportText.behaviours.map { it to SvcReportText.option(it) },
                selection = stateOf({ current.behaviour ?: "" }, { value -> edit { it.behaviour = value.nonEmpty } }),
                accessibility = L("Behaviour"),
            )
        }
        FieldChrome {
            PlainTextField(
                stateOf({ current.note ?: "" }, { value -> edit { it.note = if (value.isEmpty()) null else value } }),
                prompt = L("A note for the family"),
                modifier = Modifier.weight(1f),
            )
        }
        SvcPhotoSlot(
            label = L("Photo"),
            url = report.photoUrl,
            disabled = false,
            appointmentId = pet.appointmentId,
            modifier = Modifier.widthIn(max = 224.dp),
            onChange = { url -> edit { it.photoUrl = url } },
        )
        PMButton(L("Full report"), systemImage = "clipboard-pen", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onFull)
    }
}

/** A dog's skills typed with commas between them: the text stays as typed, the report gets the list. */
@Composable
private fun SvcSkillsField(skills: List<String>, onChange: (List<String>) -> Unit) {
    val text = remember { mutableStateOf(skills.joinToString(", ")) }
    val latestSkills by rememberUpdatedState(skills)
    val latestChange by rememberUpdatedState(onChange)
    FieldChrome {
        PlainTextField(
            text,
            prompt = L("Sit, stay, recall"),
            modifier = Modifier.weight(1f),
            onChange = { value ->
                val next = svcSplitSkills(value)
                if (next != latestSkills) latestChange(next)
            },
        )
    }
    // "Add to all" changed the list from outside.
    LaunchedEffect(skills) {
        if (svcSplitSkills(text.value) != skills) text.value = skills.joinToString(", ")
    }
}

/** "Sit, stay , recall" → ["Sit", "stay", "recall"]: no blanks, no repeats, 20 at most. */
fun svcSplitSkills(text: String): List<String> {
    val out = mutableListOf<String>()
    for (part in text.split(",")) {
        val skill = part.trim()
        if (skill.isNotEmpty() && skill !in out) out.add(skill)
    }
    return out.take(20)
}
