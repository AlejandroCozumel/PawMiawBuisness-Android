package com.pawmiaw.business.stays

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

//
//  StaysRequests.kt (iOS StaysRequests.swift)
//
//  The Requests tab (web provider.stays.tsx HeldStays + requests/
//  provider-requests.tsx): stays held as a request or a quote, visits
//  families booked online waiting for an answer (answered on the visit), and
//  families' requests for a hotel stay, daycare or a home stay — waiting for
//  you, waiting for the family, dates passed, and the answered ones. One
//  request opens to confirm it (booking the stay, or planning the home stay),
//  propose other dates or decline it with a reason; the family is told.
//

private val stayRequestKinds: Set<String> = setOf("boarding", "daycare", "homestay")

@Composable
fun StaysRequestsView(held: List<StaysStay>, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val navigator = LocalNavigator.current
    var open by remember { mutableStateOf<List<StaysRequest>>(emptyList()) }
    var answered by remember { mutableStateOf<List<StaysRequest>>(emptyList()) }
    var online by remember { mutableStateOf<StaysOnlineList?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAnswered by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<StaysRequest?>(null) }

    val waiting = open
        .filter { it.shownStatus == "pending" }
        .sortedWith { a, b ->
            if ((a.familyAcceptedAt != null) != (b.familyAcceptedAt != null)) (if (a.familyAcceptedAt != null) -1 else 1)
            else a.startDay.compareTo(b.startDay)
        }

    suspend fun load() {
        val location = workspace.locationId
        try {
            val list = API.get<StaysRequestList>("/provider-requests", mapOf("scope" to "open", "locationId" to location))
            open = (list.items ?: emptyList()).filter { it.kind in stayRequestKinds }
            if (showAnswered) {
                val all = runCatching { API.get<StaysRequestList>("/provider-requests", mapOf("scope" to "all", "locationId" to location)) }.getOrNull()
                answered = (all?.items ?: emptyList()).filter { it.kind in stayRequestKinds && it.status !in listOf("pending", "proposed") }
            }
            if (workspace.can(Permission.appointmentsView)) {
                online = runCatching { API.get<StaysOnlineList>("/provider-online-booking/requests", mapOf("locationId" to location)) }.getOrNull()
            }
            focused?.let { current -> focused = (open + answered).firstOrNull { it.id == current.id } ?: current }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
        loaded = true
    }

    fun range(stay: StaysStay): String {
        if (stay.isDaycare || stay.checkInDay == stay.checkOutDay) return StaysDay.short(stay.checkInDay)
        return "${StaysDay.dayMonth(stay.checkInDay)} – ${StaysDay.dayMonth(stay.checkOutDay)}"
    }

    /** A visit a family booked online (web `OnlineRequestCard`): its time held, a dashed waiting edge.
     *  Their ask to move one reads "Cambio de hora: {now} → {asked for}"; the visit's page answers it. */
    @Composable
    fun OnlineCard(request: StaysOnlineRequest, modifier: Modifier) {
        val soon = DateText.parse(request.releasesAt)?.let { it.toEpochMilli() - System.currentTimeMillis() < 2 * 3_600_000L } ?: false
        Card(
            spacing = 12.dp,
            modifier = modifier.staysDashedOverlay(2.dp, Theme.mix(ProviderState.waiting.color, 70.0, Theme.borderSoft), Theme.cardRadius, on = 6.dp, off = 4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                PetStack(pets = (request.pets ?: emptyList()).map { Triple(it.name ?: "", it.species, it.photoUrl) }, size = 40.dp, max = 3)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        (request.pets ?: emptyList()).mapNotNull { it.name }.joinToString(", "),
                        style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${request.client?.name ?: ""} · ${request.serviceLabel ?: ""}",
                        style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (request.isReschedule) SchedRescheduleMark() else Chip(text = L("To confirm"), icon = "clock-3", pending = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Glyph("calendar-clock", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp))
                val now = request.startsAt
                if (request.isReschedule && now != null) {
                    Text(SchedOnlineText.change(now, request.rescheduleProposal?.startsAt), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                } else {
                    Text(DateText.dayTime(request.startsAt), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FlowLayout(spacing = 12.dp) {
                Chip(text = L("Online"), tone = Chip.Tone.online, icon = "globe")
                if (request.atHome == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("map-pin", 12.dp, tint = Theme.inkSoft)
                        Text(L("At the family’s home"), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                StaysWords.releaseIn(request.releasesAt)?.let { release ->
                    val tint = if (soon) Theme.destructive else Theme.inkSoft
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("clock-3", 12.dp, tint = tint)
                        Text(
                            if (request.isReschedule) LF("Expires %@@ask", release) else LF("Released %@", release),
                            style = Theme.text(12, if (soon) FontWeight.Bold else FontWeight.Normal), color = tint,
                        )
                    }
                }
            }
            request.notes.nonEmpty?.let { Text("“$it”", style = Theme.text(14), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }

    // MARK: Families' requests

    @Composable
    fun Group(title: String, hint: String?, requests: List<StaysRequest>) {
        if (requests.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StaysEyebrow(text = title)
                if (hint != null) Text(hint, style = Theme.text(14), color = Theme.inkSoft)
            }
            StaysGrid(items = requests, minimum = 300.dp, spacing = 12.dp) { request ->
                StaysRequestCard(request = request, modifier = Modifier.plainButton { focused = request })
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // MARK: Held stays
        // Stays held as a request or sent as a quote, still to confirm: each opens to confirm or cancel it.
        if (held.isNotEmpty()) {
            Card(spacing = 8.dp) {
                StaysEyebrow(text = L("Held, not confirmed yet"), modifier = Modifier.padding(bottom = 4.dp))
                for (stay in held) {
                    StaysTintedRow(tint = Theme.mango, percent = 14.0, modifier = Modifier.plainButton { navigator.push(ProviderRoute.stay(stay.id)) }) {
                        PetStack(pets = stay.petList.map { Triple(it.petName, it.petSpecies, it.photoUrl) }, size = 40.dp, max = 3)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                "${stay.petNames} · ${StaysWords.kind(stay.kind)}",
                                style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${range(stay)} · ${stay.clientName ?: ""}${stay.unitName?.let { " · $it" } ?: ""}",
                                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(L("Open"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
                    }
                }
            }
        }
        if (!loaded) {
            LoadingState()
        } else if (error != null) {
            ErrorState(message = error ?: "") { load() }
        } else {
            val items = online?.items ?: emptyList()
            if (open.isEmpty() && items.isEmpty()) {
                EmptyState(
                    systemImage = "mail-question-mark", title = L("No requests waiting"),
                    message = L("Families linked to you can ask for stays, home stays and programs from their app. Their requests show up here."),
                    accent = Theme.aqua, panel = true,
                )
            }
            // MARK: Online bookings
            if (items.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val count = online?.count ?: items.size
                    val asks = items.count { it.isReschedule }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // The server's count holds the families' asks to move a booking too ("Cambio de hora").
                        StaysEyebrow(
                            text = if (asks == 0) LP(count, "%d online booking to confirm", "%d online bookings to confirm")
                            else SchedOnlineText.inboxTitle(count = count, asks = asks),
                        )
                        Text(L("Their time is held until you answer. Pets booked together are answered together."), style = Theme.text(14), color = Theme.inkSoft)
                    }
                    StaysGrid(items = items, minimum = 300.dp, spacing = 12.dp) { request ->
                        // The visit's own page answers it (Confirmar, Cambiar hora y confirmar, Rechazar).
                        OnlineCard(request, Modifier.plainButton { navigator.push(ProviderRoute.appointment(request.appointmentId)) })
                    }
                }
            }
            Group(L("Waiting for you"), null, waiting)
            Group(L("Waiting for the family"), null, open.filter { it.shownStatus == "proposed" })
            Group(L("Dates passed"), L("Propose new dates or decline them."), open.filter { it.shownStatus == "expired" })
            PMButton(if (showAnswered) L("Hide answered") else L("Show answered"), variant = ButtonVariant.outline) { showAnswered = !showAnswered }
            if (showAnswered) {
                if (answered.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StaysEyebrow(text = L("Answered"))
                        EmptyState(systemImage = "check", title = L("Nothing answered yet."), accent = Theme.aqua)
                    }
                }
                Group(L("Answered"), null, answered)
            }
        }
    }

    ReloadsOnScope { load() }
    StaysOnChanged(showAnswered) { load() }
    focused?.let { request ->
        AppSheet(onDismissRequest = { focused = null }) {
            StaysRequestSheet(request = request) { stayId -> navigator.push(ProviderRoute.stay(stayId)) }
        }
    }
}

@Composable
private fun StaysRequestCard(request: StaysRequest, modifier: Modifier = Modifier) {
    val status = StaysWords.requestStatus(request)
    val vaccineIssues = (request.pets ?: emptyList()).sumOf { pet -> (pet.vaccines ?: emptyList()).count { it.status != "current" } }
    Card(spacing = 12.dp, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            IconBadge(icon = StaysWords.requestKindIcon(request.kind), accent = Theme.blue, size = 40.dp, iconSize = 20.dp, ink = IconBadge.Ink.accent, washPercent = 14.0)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(request.petNames, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${request.clientName ?: L("A family")} · ${StaysWords.requestKind(request.kind)}",
                    style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            StatusChip(text = status.first, state = status.second)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("calendar-range", 16.dp, tint = Theme.inkSoft)
            Text(StaysWords.requestRange(request.kind, request.startDay, request.endDay), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            StaysWords.requestLength(request.kind, request.startDay, request.endDay)?.let { length ->
                Text("· $length", style = Theme.text(14), color = Theme.inkSoft)
            }
        }
        val start = request.proposedStartDay
        if (request.status == "proposed" && start != null) {
            Text(
                LF("You proposed %@", StaysWords.requestRange(request.kind, start, request.proposedEndDay ?: start)),
                style = Theme.text(12), color = Theme.inkSoft,
            )
        }
        request.notes.nonEmpty?.let { Text("“$it”", style = Theme.text(14), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        val deposit = request.depositPercent ?: 0
        if (vaccineIssues > 0 || deposit > 0) {
            FlowLayout(spacing = 8.dp) {
                if (vaccineIssues > 0) Chip(text = LP(vaccineIssues, "%d vaccine to check", "%d vaccines to check"), tone = Chip.Tone.danger, icon = "syringe")
                if (deposit > 0) Chip(text = LF("Deposit %d%%", deposit), tone = Chip.Tone.warn, icon = "wallet")
            }
        }
    }
}

// MARK: - One request

private enum class StaysAnswerMode { confirm, propose, decline }

private class StaysRoomChoice(val unit: StaysUnit, val detail: String, val available: Boolean) {
    val id: String get() = unit.id
}

@Composable
private fun StaysRequestSheet(request: StaysRequest, onOpenStay: (String) -> Unit) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val mode = remember { mutableStateOf(StaysAnswerMode.confirm) }
    var working by remember { mutableStateOf(false) }
    // Confirm a stay
    var units by remember { mutableStateOf<List<StaysUnit>>(emptyList()) }
    var rangeStays by remember { mutableStateOf<List<StaysStay>>(emptyList()) }
    var settings by remember { mutableStateOf<StaysSettings?>(null) }
    var unitId by remember { mutableStateOf<String?>(null) }
    val rate = remember { mutableStateOf<Int?>(null) }
    val message = remember { mutableStateOf("") }
    // Confirm a home stay
    val plan = remember { mutableStateOf(StaysHomePlan()) }
    val sitterId = remember { mutableStateOf("") }
    var sitters by remember { mutableStateOf<List<StaysMember>>(emptyList()) }
    val sendQuote = remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    val bookAnyway = remember { mutableStateOf(false) }
    // Confirm a program's sessions
    var services by remember { mutableStateOf<List<StaysProgramService>>(emptyList()) }
    var serviceId by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<StaysMember>>(emptyList()) }
    val memberId = remember { mutableStateOf("") }
    val firstDay = remember { mutableStateOf("") }
    val sessionTime = remember { mutableStateOf("09:00") }
    val sessions = remember { mutableIntStateOf(1) }
    var duration by remember { mutableIntStateOf(60) }
    // Other dates
    var startDay by remember { mutableStateOf("") }
    val endDay = remember { mutableStateOf("") }
    // Decline
    val reason = remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }

    val expired = request.shownStatus == "expired"
    val canAnswer = request.status == "pending" &&
        (if (request.kind == "program") workspace.can(Permission.appointmentsCreate) else workspace.can(Permission.staysManage))

    val primaryTitle = when (mode.value) {
        StaysAnswerMode.confirm -> if (request.kind == "homestay") {
            if (conflict) L("Book anyway") else if (sendQuote.value) L("Send quote") else L("Confirm and book")
        } else {
            L("Confirm and book")
        }
        StaysAnswerMode.propose -> L("Send these dates")
        StaysAnswerMode.decline -> L("Decline request")
    }
    val answerTitle: String? = if (canAnswer) primaryTitle else null
    /** Web: a check on every answer, a send on a quote. */
    val answerIcon: String? = if (!canAnswer) null else {
        if (mode.value == StaysAnswerMode.confirm && request.kind == "homestay" && sendQuote.value && !conflict) "send" else "check"
    }

    val roomChoices: List<StaysRoomChoice> = run {
        val kind = request.kind
        val species = (request.pets ?: emptyList()).map { (it.species ?: "").lowercase() }
        val petCount = max(1, (request.pets ?: emptyList()).size)
        units.map { unit ->
            val wrongSpecies = species.any { unit.species != "any" && it.isNotEmpty() && it != unit.species }
            val tooSmall = (request.pets ?: emptyList()).size > unit.seats
            val days: List<String> = if (kind == "daycare") {
                (0..max(0, min(30, StaysDay.between(request.startDay, request.endDay)))).map { StaysDay.add(request.startDay, it) }
            } else {
                listOf(request.startDay)
            }
            val fullOn = days.asSequence().mapNotNull { day ->
                StaysDay.firstFullDay(
                    capacity = unit.seats, stays = rangeStays, unitId = unit.id, kind = kind, from = day,
                    to = if (kind == "daycare") day else request.endDay, pets = petCount,
                )
            }.firstOrNull()
            val unitRate = if (kind == "daycare") unit.dayRateCents else unit.nightlyRateCents
            val detail = when {
                wrongSpecies -> L("Not for this species")
                tooSmall -> LF("Holds %d", unit.seats)
                fullOn != null -> LF("Full on %@", StaysDay.short(fullOn))
                else -> "${StaysWords.unitKind(unit.kind)} · ${Money.format(unitRate, currency = settings?.currency)}"
            }
            StaysRoomChoice(unit = unit, detail = detail, available = !wrongSpecies && !tooSmall && fullOn == null)
        }
    }

    /** A program's sessions: the first, then one a week (family-requests.ts programVisits). */
    fun programVisits(): List<Map<String, Any?>> =
        (0 until max(1, min(12, sessions.intValue))).map { index ->
            mapOf("date" to StaysDay.add(firstDay.value, index * 7), "startTime" to sessionTime.value, "durationMinutes" to duration)
        }

    // MARK: Work

    suspend fun start() = coroutineScope {
        if (started) return@coroutineScope
        started = true
        mode.value = if (expired) StaysAnswerMode.propose else StaysAnswerMode.confirm
        val today = StaysDay.today()
        startDay = if (request.startDay < today) today else request.startDay
        endDay.value = if (request.endDay < today) StaysDay.add(today, if (request.kind == "boarding") 1 else 0) else request.endDay
        // An overnight stay needs a night: a one-day request is planned as visits.
        plan.value = StaysHomePlan(day = request.startDay, mode = if (request.endDay > request.startDay) "overnight" else "visits")
            .copy(checkInDay = request.startDay, checkOutDay = request.endDay)
        if (!canAnswer) return@coroutineScope
        val clinic = workspace.current?.clinicId
        if (request.isStayKind) {
            val unitsCall = async { runCatching { API.get<List<StaysUnit>>("/provider-stays/units", mapOf("locationId" to workspace.locationId)) }.getOrNull() }
            val settingsCall = async { runCatching { API.get<StaysSettings>("/provider-stays/settings") }.getOrNull() }
            val rangeCall = async {
                runCatching { API.get<StaysPage>("/provider-stays", mapOf("from" to request.startDay, "to" to request.endDay, "locationId" to workspace.locationId)) }.getOrNull()
            }
            units = unitsCall.await() ?: emptyList()
            settings = settingsCall.await()
            rangeStays = rangeCall.await()?.items ?: emptyList()
        } else if (request.kind == "homestay" && clinic != null) {
            val list = runCatching { API.get<List<StaysMember>>("/provider-workspaces/$clinic/members") }.getOrNull()
            sitters = (list ?: emptyList()).filter { it.canCare }
        } else if (request.kind == "program") {
            firstDay.value = startDay
            val catalog = runCatching { API.get<StaysProgramCatalog>("/appointment-services", mapOf("scope" to "provider")) }.getOrNull()
            services = (catalog?.services ?: emptyList()).filter { it.category == "trainer" }
            services.firstOrNull()?.let { first ->
                serviceId = first.id
                val minutes = first.defaultDurationMinutes.int
                if (minutes != null && minutes > 0) duration = minutes
            }
            members = StaysHomeTeam.members().filter { it.status != "suspended" }
        }
    }

    fun run(work: suspend () -> Unit) {
        working = true
        scope.launch {
            try {
                work()
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is APIError && e.code == "visit_conflict") {
                    conflict = true
                    bookAnyway.value = false
                } else {
                    val text = if (request.kind == "homestay" && mode.value == StaysAnswerMode.confirm) {
                        StaysWords.homeError(e, fallback = "Something went wrong. Try again.")
                    } else {
                        StaysWords.requestError(e)
                    }
                    Toast.error(text)
                }
            } finally {
                working = false
            }
        }
    }

    fun confirmProgram() {
        if (!form.check(listOf("firstSession" to (if (firstDay.value < StaysDay.today()) L("Choose a date from today on.") else null)))) return
        run {
            API.send(
                "POST", "/provider-requests/${request.id}/confirm",
                body = mapOf("serviceId" to serviceId.nonEmpty, "memberId" to memberId.value.nonEmpty, "visits" to programVisits(), "message" to message.value.nonEmpty),
            )
            Toast.show(L("Confirmed. The visits are in the schedule and the family has been told."))
        }
    }

    fun confirmStay() {
        run {
            API.send("POST", "/provider-requests/${request.id}/confirm", body = mapOf("unitId" to unitId, "rateCents" to rate.value, "message" to message.value.nonEmpty))
            Toast.show(L("Confirmed. The family has been told."))
        }
    }

    fun confirmHome() {
        if (conflict && !bookAnyway.value) {
            Toast.error(L("The sitter is busy then. Check the warning and book it anyway if you meant to."))
            return
        }
        if (!form.check(plan.value.problems)) return
        run {
            val p = plan.value
            val home = mutableMapOf<String, Any?>(
                "mode" to p.mode, "plan" to p.planBody, "checkInTime" to p.checkInTime, "checkOutTime" to p.checkOutTime,
                "priceUnit" to p.priceUnit, "catalogItemId" to p.catalogItemId, "vetAuthorizationCents" to p.vetAuthorization,
                "houseRules" to p.houseRules.nonEmpty, "houseTasks" to p.houseTasksBody, "meetGreet" to p.meetGreetBody(sitterId = sitterId.value.nonEmpty),
                "sendQuote" to sendQuote.value,
            )
            p.deposit?.let { home["depositCents"] = it }
            if (bookAnyway.value) home["allowDoubleBooking"] = true
            val body = mutableMapOf<String, Any?>("memberId" to sitterId.value.nonEmpty, "message" to message.value.nonEmpty, "homestay" to home)
            p.rate?.let { body["rateCents"] = it }
            API.send("POST", "/provider-requests/${request.id}/confirm", body = body)
            Toast.show(if (sendQuote.value) L("Quote sent. The family answers it in the app.") else L("Confirmed. The home stay is booked and the family has been told."))
        }
    }

    fun propose() {
        val end = if (request.kind == "program") startDay else endDay.value
        val problem: String? = when {
            startDay < StaysDay.today() -> L("Choose a date from today on.")
            end < startDay -> L("Choose valid dates, the first one before the last.")
            request.kind == "boarding" && end == startDay -> L("A hotel stay needs at least one night.")
            else -> null
        }
        if (!form.check(listOf("dates" to problem))) return
        run {
            API.send("POST", "/provider-requests/${request.id}/propose", body = mapOf("startDay" to startDay, "endDay" to end, "message" to message.value.nonEmpty))
            Toast.show(L("Dates sent to the family."))
        }
    }

    fun decline() {
        if (!form.check(listOf("reason" to (if (reason.value.trim().length < 2) L("Tell the family why.") else null)))) return
        run {
            API.send("POST", "/provider-requests/${request.id}/decline", body = mapOf("reason" to reason.value.trim()))
            Toast.show(L("Declined. The family has been told why."))
        }
    }

    fun submit() {
        when (mode.value) {
            StaysAnswerMode.confirm -> if (request.isStayKind) confirmStay() else if (request.kind == "homestay") confirmHome() else confirmProgram()
            StaysAnswerMode.propose -> propose()
            StaysAnswerMode.decline -> decline()
        }
    }

    val status = StaysWords.requestStatus(request)
    ProviderModal(
        title = "${StaysWords.requestKind(request.kind)} · ${request.petNames}",
        subtitle = "${request.clientName ?: L("A family")} · ${StaysWords.requestRange(request.kind, request.startDay, request.endDay)}",
        systemImage = StaysWords.requestKindIcon(request.kind),
        accent = ModalAccent.aqua,
        isDirty = message.value.isNotEmpty() || reason.value.isNotEmpty(),
        form = form,
        primaryTitle = answerTitle,
        primaryVariant = if (mode.value == StaysAnswerMode.decline) ButtonVariant.destructive else ButtonVariant.primary,
        isWorking = working,
        cancelTitle = if (canAnswer) L("Cancel") else L("Close"),
        primaryIcon = answerIcon,
        onPrimary = if (canAnswer) ({ submit() }) else null,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(text = status.first, state = status.second)
            request.createdAt?.let { created ->
                Text(LF("Asked %@", StaysDay.short(created.take(10))), style = Theme.text(12), color = Theme.inkSoft)
            }
        }

        // MARK: What was asked
        ModalSection(title = L("What they asked for")) {
            FactRow(
                label = if (request.kind == "program") L("Start") else L("Dates"),
                value = StaysWords.requestRange(request.kind, request.startDay, request.endDay) +
                    (StaysWords.requestLength(request.kind, request.startDay, request.endDay)?.let { " · $it" } ?: ""),
            )
            if (request.familyAcceptedAt != null) {
                Text(L("The family accepted these dates you proposed."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.blueText)
            }
            FactRow(label = L("Family"), value = request.clientName ?: L("A family"))
            request.clientPhone.nonEmpty?.let { PhoneLink(phone = it) }
            request.address.nonEmpty?.let { FactRow(label = L("Where"), value = it) }
            request.program.nonEmpty?.let { FactRow(label = L("What they are looking for"), value = it) }
            val deposit = request.depositPercent
            if (deposit != null && deposit > 0) {
                FactRow(label = L("Deposit"), value = LF("%d%% of the stay, as your store settings ask. Take it from the stay once it is confirmed.", deposit))
            }
            request.location?.name?.let { FactRow(label = L("Location"), value = it) }
            request.notes.nonEmpty?.let { StaysNoteBlock(label = L("Notes"), text = "“$it”") }
        }

        if (request.isStayKind && (request.pets ?: emptyList()).any { !(it.vaccines ?: emptyList()).isEmpty() }) {
            ModalSection(
                title = L("Vaccines"),
                subtitle = L("Against your stay rules, from what the family and your records show. You can confirm a paper certificate at check-in."),
            ) {
                for (pet in request.pets ?: emptyList()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pet.name ?: "", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        if ((pet.vaccines ?: emptyList()).isEmpty()) Text(L("None required for this pet."), style = Theme.text(12), color = Theme.inkSoft)
                        for (vaccine in pet.vaccines ?: emptyList()) {
                            val shape = RoundedCornerShape(Theme.boxRadius)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Theme.borderSoft, shape)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(StaysWords.vaccine(vaccine.key), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                                    Text(
                                        vaccine.matchedName?.let { name ->
                                            name + (vaccine.validUntil?.let { " · " + LF("valid until %@", StaysDay.format(it.take(10), "dMMMyyyy")) } ?: "")
                                        } ?: L("No record found"),
                                        style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                StatusChip(text = StaysWords.vaccineStatus(vaccine.status), state = ProviderState.vaccine(vaccine.status))
                            }
                        }
                    }
                }
            }
        }

        // The outcome
        when (request.status) {
            "proposed" -> {
                val start = request.proposedStartDay ?: request.startDay
                NoticeCard(
                    tone = NoticeCard.Tone.info,
                    title = LF("You proposed %@", StaysWords.requestRange(request.kind, start, request.proposedEndDay ?: start)),
                    message = (request.providerMessage.nonEmpty?.let { "“$it” " } ?: "") + L("Waiting for the family to accept or cancel."),
                )
            }
            "declined" -> NoticeCard(tone = NoticeCard.Tone.problem, title = L("Declined"), message = request.providerMessage?.let { "“$it”" })
            "cancelled" -> NoticeCard(tone = NoticeCard.Tone.info, title = L("The family cancelled this request"))
            "confirmed" -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val appointments = request.appointmentIds ?: emptyList()
                    NoticeCard(
                        tone = NoticeCard.Tone.success, title = L("Confirmed"),
                        message = if (appointments.isEmpty()) null else LP(appointments.size, "%d visit booked.", "%d visits booked."),
                    )
                    val stayIds = request.stayIds ?: emptyList()
                    FlowLayout(spacing = 6.dp) {
                        stayIds.forEachIndexed { index, stayId ->
                            PMButton(
                                if (stayIds.size == 1) L("Open the stay") else LF("Day %d", index + 1),
                                systemImage = "bed-double", variant = ButtonVariant.outline, size = ButtonSize.compact,
                            ) {
                                dismiss()
                                onOpenStay(stayId)
                            }
                        }
                    }
                }
            }
            else -> {}
        }

        if (canAnswer) {
            // The answer in its own bordered box (web: a card under the facts), the three answers as one
            // switch (web AnswerModeSwitch): icons on a phone, words beside them on the chosen one.
            Card(padding = 16.dp, spacing = 20.dp, flat = true) {
                Segmented(
                    options = (if (expired) emptyList() else listOf(Segmented.Option(StaysAnswerMode.confirm, if (mode.value == StaysAnswerMode.confirm) L("Confirm") else "", icon = "check"))) +
                        listOf(
                            Segmented.Option(StaysAnswerMode.propose, if (mode.value == StaysAnswerMode.propose) L("Other dates") else "", icon = "calendar-clock"),
                            Segmented.Option(StaysAnswerMode.decline, if (mode.value == StaysAnswerMode.decline) L("Decline") else "", icon = "circle-x"),
                        ),
                    selection = mode,
                    label = L("Answer"),
                )
                when (mode.value) {
                    StaysAnswerMode.confirm -> {
                        if (request.isStayKind) {
                            FormField(label = L("Room")) {
                                // A room that doesn't fit says why and stays unchosen.
                                ChoiceCards(
                                    options = listOf(Triple<String?, String, String?>(null, L("Decide later"), L("Assign a room before arrival"))) +
                                        roomChoices.map { Triple<String?, String, String?>(it.unit.id, it.unit.name, it.detail) },
                                    selection = stateOf({ unitId }, { id ->
                                        val choice = if (id != null) roomChoices.firstOrNull { it.unit.id == id } else null
                                        if (choice != null && !choice.available) Toast.error(choice.detail) else unitId = id
                                    }),
                                )
                            }
                            MoneyField(
                                label = if (request.kind == "daycare") L("Price per pet per day") else L("Price per pet per night"),
                                cents = rate, hint = L("The room’s rate"),
                            )
                            PMTextField(L("A line for the family (optional)"), text = message, prompt = L("e.g. See you on Friday!"))
                            if (request.kind == "daycare" && request.startDay != request.endDay) {
                                Text(L("Daycare is booked as one day stay per day."), style = Theme.text(12), color = Theme.inkSoft)
                            }
                        } else if (request.kind == "homestay") {
                            MenuField(
                                label = L("Sitter"), options = listOf("" to L("Decide later")) + sitters.map { it.id to it.label },
                                selection = sitterId, key = "sitter", form = form,
                            )
                            if (request.address.nonEmpty == null) {
                                NoticeCard(
                                    tone = NoticeCard.Tone.warning,
                                    title = L("The family didn’t say where. The home stay uses the family’s default address; add one on the client if they have none."),
                                )
                            }
                            StaysHomePlanForm(plan = plan, lockDays = true, form = form)
                            PMTextField(L("A line for the family (optional)"), text = message)
                            if (conflict) StaysHomeTeam.conflictNotice(confirmed = bookAnyway)
                            ToggleRow(title = L("Send as a quote"), subtitle = L("The family accepts it in the app"), isOn = sendQuote)
                        } else {
                            MenuField(
                                label = L("Service"),
                                options = if (services.isEmpty()) listOf("" to StaysWords.requestKind(request.kind)) else services.map { it.id to (it.label ?: "") },
                                selection = stateOf({ serviceId }, { id ->
                                    serviceId = id
                                    val minutes = services.firstOrNull { it.id == id }?.defaultDurationMinutes.int
                                    if (minutes != null && minutes > 0) duration = minutes
                                }),
                            )
                            MenuField(label = L("Who goes"), options = listOf("" to L("Decide later")) + members.map { it.id to it.label }, selection = memberId)
                            DayField(label = L("First session"), day = firstDay, min = StaysDay.today(), key = "firstSession", form = form)
                            TimeField(label = L("Time"), value = sessionTime, from = "06:00", to = "22:00")
                            MenuField(
                                label = L("Weekly sessions"), options = (1..12).map { it to "$it" },
                                selection = stateOf({ sessions.intValue }, { sessions.intValue = it }),
                            )
                            MenuField(
                                label = L("Length of each visit"),
                                options = (listOf(30, 45, 60, 90, 120, 180, 240, 480, 720) + duration).toSortedSet().map { it to StaysHomePlanForm.minutesText(it) },
                                selection = stateOf({ duration }, { duration = it }),
                            )
                            PMTextField(L("A line for the family (optional)"), text = message)
                            val total = programVisits().size * max(1, (request.pets ?: emptyList()).size)
                            val soft = Theme.inkSoft
                            Text(
                                buildAnnotatedString {
                                    append(if (total == 1) L("1 visit will be booked.") else LF("%d visits will be booked.", total))
                                    withStyle(SpanStyle(color = soft)) {
                                        append(if ((request.pets ?: emptyList()).size > 1) " " + L("One per pet, at the same time.") else "")
                                    }
                                },
                                style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong,
                            )
                        }
                    }
                    StaysAnswerMode.propose -> {
                        val single = request.kind == "program"
                        DayField(
                            label = if (single) L("Start") else if (request.kind == "boarding") L("Check-in") else L("From"),
                            day = stateOf({ startDay }, { value ->
                                startDay = value
                                val gap = if (request.kind == "boarding") 1 else 0
                                if (endDay.value < value || (request.kind == "boarding" && endDay.value <= value)) endDay.value = StaysDay.add(value, gap)
                                form.clear("dates")
                            }),
                            min = StaysDay.today(), key = "dates", form = form,
                        )
                        if (!single) {
                            DayField(label = if (request.kind == "boarding") L("Check-out") else L("Last day"), day = endDay, min = startDay)
                        }
                        PMTextField(L("A line for the family (optional)"), text = message, prompt = L("e.g. We are full that weekend, the next one is free."), multiline = true)
                    }
                    StaysAnswerMode.decline -> {
                        FlowLayout(spacing = 6.dp) {
                            for (quick in listOf("We are full on those dates.", "We can’t take this pet.", "We don’t offer that at the moment.")) {
                                FilterChip(title = L(quick), isSelected = reason.value == L(quick)) {
                                    reason.value = L(quick)
                                    form.clear("reason")
                                }
                            }
                        }
                        PMTextField(L("Why (the family reads this)"), text = reason, key = "reason", form = form, multiline = true)
                    }
                }
            }
        } else if (request.status == "pending") {
            Text(L("Someone with access to book this can answer it."), style = Theme.text(14), color = Theme.inkSoft)
        }
    }

    LaunchedEffect(Unit) { start() }
    StaysOnChanged(sitterId.value) {
        conflict = false
        bookAnyway.value = false
    }
}

/** SwiftUI `.overlay(RoundedRectangle(…).strokeBorder(color, style: StrokeStyle(lineWidth:, dash: [on, off])))`: drawn over the content. */
fun Modifier.staysDashedOverlay(width: Dp, color: Color, cornerRadius: Dp, on: Dp = 4.dp, off: Dp = 3.dp): Modifier = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    val inset = stroke / 2
    val r = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()))),
    )
}
