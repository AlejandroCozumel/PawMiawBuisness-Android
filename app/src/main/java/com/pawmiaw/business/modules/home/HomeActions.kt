package com.pawmiaw.business.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pawmiaw.business.*
import com.pawmiaw.business.money.*
import com.pawmiaw.business.schedule.*
import com.pawmiaw.business.services.*
import com.pawmiaw.business.stays.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

//
//  HomeActions.kt (iOS HomeActions.swift)
//
//  What Home's rows and cards do in place (web today-board.tsx `SheetState`,
//  home-hub.tsx, hospital/arrivals-block.tsx): a visit's steps open the
//  Agenda's own sheets over Home — check in (or move a visit of another day
//  to now), start with the consent and deposit gate, finish with the one
//  charge choice, "Sí vino", no-show, cancel, book again — and the bill opens
//  in Cuentas. The same place answers an online booking, moves someone's day,
//  starts or finishes a group session, moves a groom's stage, a home visit's
//  "Llegué" and "Terminar", and "Ya llegaron" on an emergency arrival.
//  Rows reach it through `LocalHomeActions.current` (iOS `@Environment(HomeActions.self)`).
//

/** A visit made ready for "Terminar" or "Sí vino": what the sheet needs to know. */
data class HomeFinishContext(
    val appointment: SchedAppointment,
    val drafts: Int,
    val signed: Int,
    val invoice: SchedInvoice?,
    /** (memberId, name). */
    val signer: Pair<String, String>?,
    val collects: Boolean,
)

/** One sheet over Home at a time. */
sealed class HomeSheet {
    data class checkIn(val appointment: SchedAppointment) : HomeSheet()
    data class otherDay(val appointment: SchedAppointment, val `when`: String) : HomeSheet()
    data class finish(val context: HomeFinishContext) : HomeSheet()
    data class recordPast(val context: HomeFinishContext) : HomeSheet()
    data class missed(val appointment: SchedAppointment, val mode: String) : HomeSheet()
    data class rebook(val appointment: SchedAppointment) : HomeSheet()
    data class gate(val prompt: SchedGatePrompt) : HomeSheet()
    data class svcGate(val request: SvcStartGateRequest) : HomeSheet()
    data class online(val request: SchedOnlineRequest) : HomeSheet()
    data class reassign(val date: String, val people: List<SvcHomePerson>, val members: List<SvcMember>) : HomeSheet()
    data class homeFinish(val target: SvcFinishTarget) : HomeSheet()
    data class homeReport(val target: SvcReportTarget) : HomeSheet()
    data class stayLeave(val item: SvcHomeVisitItem, val timeZone: String?) : HomeSheet()
    data class program(val request: StaysRequest) : HomeSheet()
    data class setup(val sheet: HomeSetupSheet) : HomeSheet()
    data class closeShift(val session: CashSession) : HomeSheet()

    val id: String
        get() = when (this) {
            is checkIn -> "check-in-${appointment.id}"
            is otherDay -> "other-day-${appointment.id}-${`when`}"
            is finish -> "finish-${context.appointment.id}"
            is recordPast -> "record-past-${context.appointment.id}"
            is missed -> "$mode-${appointment.id}"
            is rebook -> "rebook-${appointment.id}"
            is gate -> "gate-${prompt.id}"
            is svcGate -> "svc-gate-${request.id}"
            is online -> "online-${request.appointmentId}"
            is reassign -> "reassign-$date"
            is homeFinish -> "home-finish-${target.id}"
            is homeReport -> "home-report-${target.id}"
            is stayLeave -> "stay-leave-${item.id}"
            is program -> "program-${request.id}"
            is setup -> "setup-${sheet.id}"
            is closeShift -> "close-shift-${session.id}"
        }
}

/** Home's actions for every row below its host (iOS `@Environment(HomeActions.self)`; nil off Home). */
val LocalHomeActions = compositionLocalOf<HomeActions?> { null }

@Stable
class HomeActions(private val scope: CoroutineScope) {
    /** The sheet open over Home. */
    var sheet: HomeSheet? by mutableStateOf(null)
    /** Where Home goes next (a bill after "Charge now", a visit, the booking). */
    var route: ProviderRoute? by mutableStateOf(null)
    /** Rows waiting on their visit to load or a step to answer. */
    val busy = mutableStateListOf<String>()
    /** The grooming settings' "simple stages" (no separate cut & dry). */
    var simpleStages: Boolean by mutableStateOf(false)
    /** An invited member's welcome, shown over everything until they leave it. */
    var welcome: HomeWelcomeState? by mutableStateOf(null)
    var showsWelcome: Boolean by mutableStateOf(false)

    private var members: List<SchedMember>? = null

    private val workspace: Workspace get() = Workspace.shared

    fun isBusy(id: String): Boolean = busy.contains(id)

    private fun begin(id: String): Boolean {
        if (busy.contains(id)) return false
        busy.add(id)
        return true
    }

    // MARK: Loading a visit

    private fun withVisit(id: String, then: suspend (SchedAppointment) -> Unit) {
        if (!begin(id)) return
        scope.launch {
            try {
                val appointment = SchedData.visit(id)
                then(appointment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                busy.remove(id)
            }
        }
    }

    companion object {
        /** The visit's bill, whole: its latest from the bills list, then read on its own. */
        suspend fun bill(appointmentId: String): SchedInvoice? {
            val page = runCatching {
                API.get<Page<SchedInvoiceSummary>>("/provider-invoices", mapOf("limit" to "10", "offset" to "0", "providerAppointmentId" to appointmentId))
            }.getOrNull()
            val latest = page?.items?.sortedByDescending { it.createdAt ?: "" }?.firstOrNull() ?: return null
            return runCatching { API.get<SchedInvoice>("/provider-invoices/${latest.id}") }.getOrNull()
        }
    }

    private suspend fun context(appointment: SchedAppointment): HomeFinishContext = coroutineScope {
        val w = workspace
        val readsRecords = w.can(Permission.recordsView)
        val readsBills = w.moneyLevel >= MoneyLevel.charging
        val notes = async { if (readsRecords) SchedData.visitRecords(appointment.id) else null }
        val bill = async { if (readsBills) bill(appointment.id) else null }
        if (members == null) members = SchedData.members()
        val records = notes.await() ?: emptyList()
        val me = members?.firstOrNull { it.isMe }
        HomeFinishContext(
            appointment = appointment,
            drafts = records.count { it.isDraft && it.hasWords },
            signed = records.count { it.isSigned },
            invoice = bill.await(),
            signer = if (w.can(Permission.recordsApprove)) me?.let { it.id to it.label } else null,
            collects = (w.can(Permission.billingRecordPayment) || w.can(Permission.billingEditInvoice)) && readsBills,
        )
    }

    // MARK: A visit's steps

    /** Only today's visits check in: on another day, it first offers to move the visit to now. */
    fun checkIn(id: String) {
        withVisit(id) { a ->
            val `when` = SchedDay.`when`(a.startsAt, SchedData.zone(a.locationId))
            sheet = if (`when` == "today") HomeSheet.checkIn(a) else HomeSheet.otherDay(a, if (`when` == "past") "past" else "future")
        }
    }

    /** "Empezar": a missing consent or deposit asks for its reason here. */
    fun start(id: String, petName: String) {
        if (!begin(id)) return
        scope.launch {
            try {
                SchedVisitOps.start(id)
                Toast.show(LF("%@ is in.", petName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val prompt = SchedGatePrompt.from(
                    e, petName = petName, verb = "start",
                    attempt = { extra -> SchedVisitOps.start(id, extra) },
                    onDone = { Toast.show(LF("%@ is in.", petName)) },
                )
                if (prompt != null) sheet = HomeSheet.gate(prompt) else schedToast(e)
            } finally {
                busy.remove(id)
            }
        }
    }

    /** "Terminar" (or "Dar salida" for whoever charges): the charge choice, the signature and "¿Cuándo vuelve?". */
    fun finish(id: String) {
        withVisit(id) { a ->
            val context = context(a)
            sheet = HomeSheet.finish(context)
        }
    }

    /** "Sí vino": a visit nobody closed on its day, recorded with the times it had. */
    fun recordPast(id: String) {
        withVisit(id) { a ->
            val context = context(a)
            sheet = HomeSheet.recordPast(context)
        }
    }

    fun noShow(id: String) = withVisit(id) { a -> sheet = HomeSheet.missed(a, "no_show") }
    fun cancel(id: String) = withVisit(id) { a -> sheet = HomeSheet.missed(a, "cancel") }
    fun rebook(id: String) = withVisit(id) { a -> sheet = HomeSheet.rebook(a) }

    /** A move that only needs a press (undo the arrival, reopen, send to the desk). */
    fun run(id: String, path: String, success: String) {
        if (!begin(id)) return
        scope.launch {
            try {
                SchedAPI.send<SchedMoveResponse>("POST", path, emptyMap())
                Toast.show(success)
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                schedToast(e)
            } finally {
                busy.remove(id)
            }
        }
    }

    /** A family's online booking answered over Home (Confirmar, Cambiar hora y confirmar, Rechazar). */
    fun answerOnline(appointmentId: String) {
        if (!begin(appointmentId)) return
        scope.launch {
            try {
                val list = runCatching {
                    API.get<SchedOnlineRequestList>("/provider-online-booking/requests", mapOf("locationId" to workspace.locationId))
                }.getOrNull()
                val found = (list?.items ?: emptyList()).firstOrNull { request ->
                    request.appointmentId == appointmentId || request.pets.any { it.appointmentId == appointmentId }
                }
                if (found != null) sheet = HomeSheet.online(found) else route = ProviderRoute.appointment(appointmentId)
            } finally {
                busy.remove(appointmentId)
            }
        }
    }

    /** The welcome, at the step the server says (a reminder resumes it at its own step). */
    fun openWelcome(state: HomeWelcomeState) {
        welcome = state
        showsWelcome = true
    }

    /** "Adopt my signature", "Add it", "Continue": the welcome reopened at that step. */
    fun resumeWelcome(step: String?) {
        if (!begin("welcome")) return
        scope.launch {
            try {
                val state: HomeWelcomeState = API.post("/provider-member-onboarding/resume", mapOf("step" to step))
                openWelcome(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(errorText(e, L("Could not open it. Try again.")))
            } finally {
                busy.remove("welcome")
            }
        }
    }

    /** "Contar y cerrar" on a register shift left open from an earlier day: the register's own close, over Home. */
    fun closeShift(sessionId: String) {
        val key = "shift-$sessionId"
        if (!begin(key)) return
        scope.launch {
            try {
                val session: CashSession = API.get("/provider-store/register-sessions/$sessionId")
                sheet = HomeSheet.closeShift(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                busy.remove(key)
            }
        }
    }

    // MARK: New visits

    fun newAppointment(day: String? = null) {
        route = ProviderRoute.bookVisit(BookingPrefill(day = day))
    }

    fun walkIn(clientId: String? = null, petId: String? = null, notes: String? = null) {
        route = ProviderRoute.bookVisit(BookingPrefill(clientId = clientId, petId = petId, notes = notes, walkIn = true))
    }

    /** "Ya llegaron" on a family's "voy en camino": the warning leaves Home and the walk-in opens with what is known. */
    fun arrived(arrival: HomeArrival) {
        if (!begin(arrival.id)) return
        scope.launch {
            try {
                API.send("POST", "/provider-hospital/arrivals/${arrival.id}/arrived", mapOf("appointmentId" to null))
                announceDataChanged()
                // Not ported yet: the booking hand-off carries no `onTheWayId`, so the warning is closed here first.
                val linked = arrival.linked == true
                walkIn(
                    clientId = if (linked) arrival.client?.id else null,
                    petId = if (linked) arrival.pet?.providerClientPetId else null,
                    notes = arrival.message.nonEmpty,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                busy.remove(arrival.id)
            }
        }
    }

    // MARK: The day, sessions, grooms and home visits

    /** "¿Alguien faltó? Mueve su día": everyone who takes visits, from one person to another or whoever is free. */
    fun reassignDay(date: String) {
        if (!begin("reassign")) return
        scope.launch {
            try {
                val team = svcLoadMembers(workspace)
                val people = team.map { SvcHomePerson(memberId = it.id, name = it.shownName, image = it.image, total = 0, done = 0, inProgress = 0, late = 0) }
                sheet = HomeSheet.reassign(date, people, team)
            } finally {
                busy.remove("reassign")
            }
        }
    }

    /** A session's start with whoever arrived, or its finish for everyone with them. */
    fun moveSession(id: String, action: String) {
        if (!begin(id)) return
        scope.launch {
            try {
                val result: SvcSessionMoveResult = SvcAPI.send("POST", "/provider-sessions/$id/$action", emptyMap())
                val moved = result.moved?.size ?: 0
                if (action == "start") {
                    Toast.show(if (moved == 1) L("Started with 1 pet.") else LF("Started with %d pets.", moved))
                } else {
                    Toast.show(if (moved == 1) L("Finished for 1 pet. Its family has its bill.") else LF("Finished for %d pets. Each family has its own bill.", moved))
                }
                result.skipped?.firstOrNull()?.error?.let { Toast.error(it) }
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(if (svcCode(e) == "nobody_arrived") L("Mark who arrived first, or start with everyone.") else svcMessage(e))
            } finally {
                busy.remove(id)
            }
        }
    }

    /** A groom to its next stage. Out of the waiting room it starts the visit: a missing consent or deposit asks here. */
    fun moveGroom(id: String, petName: String, to: String) {
        val stage = to
        if (!begin(id)) return
        scope.launch {
            try {
                SvcAPI.send<SvcIgnored>("PATCH", "/provider-appointments/$id/groom-stage", mapOf("stage" to stage))
                Toast.show("$petName · ${SvcGroomStage.label(stage, simpleStages)}")
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val code = svcIsStartGate(e)
                if (code != null) {
                    if (workspace.isOwnerOrAdmin) {
                        sheet = HomeSheet.svcGate(SvcStartGateRequest(code, petName) { override ->
                            API.post<SvcIgnored>("/provider-appointments/$id/start", override)
                            // A started groom is in the bath already.
                            if (stage != "bath") API.patch<SvcIgnored>("/provider-appointments/$id/groom-stage", mapOf("stage" to stage))
                            announceDataChanged()
                        })
                    } else {
                        Toast.error(svcMessage(e) + " " + L("Open the visit to see what it needs."))
                    }
                } else {
                    Toast.error(svcMessage(e, SvcRefusals.grooming))
                }
            } finally {
                busy.remove(id)
            }
        }
    }

    /** "Ready" told to the family once per Ready: in PawMiaw, or WhatsApp opened with the message. */
    fun tellReady(id: String, petName: String, clientName: String?, channel: String) {
        if (!begin(id)) return
        scope.launch {
            try {
                val result: SvcGroomReadyResult = SvcAPI.send(
                    "POST", "/provider-appointments/$id/groom-ready",
                    mapOf("channel" to channel, "locale" to LocalizationManager.currentLanguage.rawValue),
                )
                val link = result.link
                if (channel == "whatsapp" && link != null) {
                    svcOpen(link)
                    Toast.show(L("WhatsApp is open with the message: send it from there."))
                } else {
                    Toast.show(LF("%@ was told in PawMiaw that %@ is ready.", NameText.firstName(clientName), petName))
                }
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(svcMessage(e, SvcRefusals.grooming))
            } finally {
                busy.remove(id)
            }
        }
    }

    /** "Llegué": the home visit's start, from the door, with the time it was pressed. */
    fun arriveHome(item: SvcHomeVisitItem) {
        val at = DateText.iso(Instant.now())
        val path: String = if (item.isStay) {
            val stay = item.stayId ?: return
            val visit = item.stayVisitId ?: return
            "/provider-homestays/$stay/visits/$visit/arrive"
        } else {
            val id = item.appointmentId ?: return
            "/provider-appointments/$id/home-arrive"
        }
        if (!begin(item.id)) return
        scope.launch {
            try {
                SvcAPI.send<SvcIgnored>("POST", path, mapOf("at" to at))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val code = svcIsStartGate(e)
                if (code != null) {
                    if (workspace.isOwnerOrAdmin) {
                        sheet = HomeSheet.svcGate(SvcStartGateRequest(code, item.petNames) { override ->
                            val body = override.toMutableMap()
                            body["at"] = at
                            API.post<SvcIgnored>(path, body)
                            announceDataChanged()
                        })
                    } else {
                        Toast.error(svcMessage(e) + " " + L("Open the visit to see what it needs."))
                    }
                } else {
                    Toast.error(svcMessage(e, SvcRefusals.homeVisits))
                }
            } finally {
                busy.remove(item.id)
            }
        }
    }

    /** "Terminar": a home stay's visit leaves the home; a walk, sitting, groom or class closes here; a vet visit on its page. */
    fun finishHome(item: SvcHomeVisitItem, timeZone: String?, canWrite: Boolean) {
        if (item.isStay) {
            sheet = HomeSheet.stayLeave(item, timeZone)
            return
        }
        val id = item.appointmentId ?: return
        if (!SvcHomeRules.finishesHere(item.serviceCategory)) {
            route = ProviderRoute.appointment(id)
            return
        }
        val lead = item.pets.firstOrNull { it.appointmentId == item.appointmentId } ?: item.pets.firstOrNull()
        homeItems[id] = item to canWrite
        sheet = HomeSheet.homeFinish(
            SvcFinishTarget(
                id = id, petName = lead?.name ?: item.petNames, serviceLabel = item.serviceText,
                serviceCategory = item.serviceCategory, reportMissing = item.reportMissing ?: emptyList(),
            )
        )
    }

    private val homeItems = HashMap<String, Pair<SvcHomeVisitItem, Boolean>>()

    private fun report(appointmentId: String): SvcReportTarget? {
        val entry = homeItems[appointmentId] ?: return null
        val item = entry.first
        val pets = item.pets.mapNotNull { pet -> pet.appointmentId?.let { SvcReportPet(id = it, name = pet.name) } }
        val list = if (pets.isEmpty()) listOf(SvcReportPet(id = appointmentId, name = item.petNames)) else pets
        return SvcReportTarget(subtitle = "${item.petNames} · ${item.serviceText}", pets = list, canWrite = entry.second)
    }

    // MARK: Moving between sheets

    /** The next sheet once this one has gone. */
    fun next(sheet: HomeSheet) {
        this.sheet = null
        scope.launch {
            delay(450)
            this@HomeActions.sheet = sheet
        }
    }

    /** Somewhere to go once the sheet has gone. */
    fun go(route: ProviderRoute) {
        sheet = null
        scope.launch {
            delay(450)
            this@HomeActions.route = route
        }
    }

    /** After "Charge now" or "Put the price myself": the visit's bill, in Cuentas. */
    fun openBill(after: String) {
        val appointmentId = after
        scope.launch {
            delay(450)
            val bill = bill(appointmentId)
            route = if (bill != null) ProviderRoute.invoice(bill.id) else ProviderRoute.appointment(appointmentId)
        }
    }

    // MARK: The sheets

    @Composable
    fun view(sheet: HomeSheet) {
        val w = workspace
        when (sheet) {
            is HomeSheet.checkIn -> SchedCheckInSheet(
                appointment = sheet.appointment,
                canStart = w.can(Permission.appointmentsComplete),
                onGate = { prompt -> next(HomeSheet.gate(prompt)) },
            )
            is HomeSheet.otherDay -> {
                val a = sheet.appointment
                val `when` = sheet.`when`
                val within = (a.start?.let { Duration.between(it, Instant.now()).seconds } ?: 0) <= 31 * 86_400
                SchedOtherDaySheet(
                    appointment = a,
                    `when` = `when`,
                    canMove = w.can(Permission.appointmentsEdit),
                    onMoved = { this.sheet = null; checkInAfterMove(a.id) },
                    onNoShow = if (w.can(Permission.appointmentsCheckIn)) ({ this.sheet = HomeSheet.missed(a, "no_show") }) else null,
                    onWalkIn = if (w.canAll(Permission.appointmentsCreate, Permission.clientsView)) ({ this.sheet = null; walkInLater(a) }) else null,
                    onRecordPast = if (`when` == "past" && within && w.can(Permission.appointmentsComplete)) ({ this.sheet = null; recordPastLater(a.id) }) else null,
                )
            }
            is HomeSheet.finish -> {
                val c = sheet.context
                SchedFinishSheet(
                    appointment = c.appointment, drafts = c.drafts, signed = c.signed, invoice = c.invoice, signer = c.signer, collects = c.collects,
                    onAfter = { openBill(c.appointment.id) },
                    onEarlierDay = if (w.can(Permission.appointmentsComplete)) ({ recordPastLater(c.appointment.id) }) else null,
                )
            }
            is HomeSheet.recordPast -> {
                val c = sheet.context
                SchedRecordPastSheet(appointment = c.appointment, invoice = c.invoice, collects = c.collects, onAfter = { openBill(c.appointment.id) })
            }
            is HomeSheet.missed -> SchedMissedSheet(appointment = sheet.appointment, mode = sheet.mode)
            is HomeSheet.rebook -> SchedRebookSheet(appointment = sheet.appointment)
            is HomeSheet.gate -> SchedGateSheet(prompt = sheet.prompt)
            is HomeSheet.svcGate -> SvcStartGateSheet(request = sheet.request, onDone = {})
            is HomeSheet.online -> SchedRequestSheet(request = sheet.request, onOpenVisit = { id -> go(ProviderRoute.appointment(id)) })
            is HomeSheet.reassign -> SvcMoveDaySheet(date = sheet.date, people = sheet.people, members = sheet.members, initialFrom = null)
            is HomeSheet.homeFinish -> {
                val target = sheet.target
                val report = report(target.id)
                SvcFinishSheet(
                    target = target,
                    extraBody = mapOf("at" to DateText.iso(Instant.now())),
                    onOpenReport = if (report == null) null else ({ next(HomeSheet.homeReport(report)) }),
                    onOpenVisit = if (w.moneyLevel >= MoneyLevel.charging) ({ openBill(target.id) }) else null,
                    onFinished = {},
                )
            }
            is HomeSheet.homeReport -> SvcReportSheet(target = sheet.target)
            is HomeSheet.stayLeave -> SvcStayMoveSheet(item = sheet.item, move = "leave", timeZone = sheet.timeZone)
            is HomeSheet.program -> HomeProgramRequestSheet(request = sheet.request)
            is HomeSheet.setup -> sheet.sheet.view(actions = this)
            is HomeSheet.closeShift -> CashCloseShiftSheet(session = sheet.session, onClosed = { announceDataChanged() })
        }
    }

    private fun checkInAfterMove(id: String) {
        scope.launch {
            delay(450)
            checkIn(id)
        }
    }

    private fun recordPastLater(id: String) {
        scope.launch {
            delay(450)
            recordPast(id)
        }
    }

    private fun walkInLater(a: SchedAppointment) {
        scope.launch {
            delay(450)
            walkIn(clientId = a.providerClientId, petId = a.providerClientPetId)
        }
    }
}

/**
 * Hosts Home's sheets and where they lead, and hands the actions to every row below
 * (iOS `HomeActionsHost` view modifier).
 */
@Composable
fun HomeActionsHost(actions: HomeActions, content: @Composable () -> Unit) {
    val navigator = LocalNavigator.current
    CompositionLocalProvider(LocalHomeActions provides actions) {
        content()
        actions.sheet?.let { sheet ->
            key(sheet.id) {
                AppSheet(onDismissRequest = { if (actions.sheet?.id == sheet.id) actions.sheet = null }) {
                    actions.view(sheet)
                }
            }
        }
        // `.fullScreenCover(isPresented: $actions.showsWelcome, onDismiss: { announceDataChanged() })`.
        val welcome = actions.welcome
        if (actions.showsWelcome && welcome != null) {
            val close = {
                actions.showsWelcome = false
                announceDataChanged()
            }
            HomeFullScreenCover(onDismiss = close) {
                HomeWelcomeScreen(state = welcome, onClose = close)
            }
        }
    }
    // `.navigationDestination(item: $actions.route)`: the route is pushed on Home's stack.
    LaunchedEffect(actions.route) {
        val route = actions.route ?: return@LaunchedEffect
        actions.route = null
        navigator.push(route)
    }
}
