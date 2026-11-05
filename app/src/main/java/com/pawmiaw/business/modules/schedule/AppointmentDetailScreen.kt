package com.pawmiaw.business.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

//
//  AppointmentDetailScreen.kt (iOS AppointmentDetailScreen.swift)
//
//  One visit (web provider.appointments.$appointmentId.tsx), built around
//  its next step: the pet (its chart one tap away), what it came for and
//  when, who it is with and where, the visit's four steps, what the family
//  asked, then — by status — the online request's three answers, the
//  arrival, the waiting room, or the note, what else was recorded, the
//  closing card and the bill (money level 1 and up). Every move the web
//  offers: check in, start, finish with the charge choice, "Sí vino", no-show,
//  cancel with its reason, edit and move, book again, undo, reopen. Around
//  it: the owner's grooming request, the groom/walk/training report, the
//  care card, the triage, the chart with "+" to record into the visit, the
//  consents (signed here) and the emergency deposit, the incidents, the
//  prescriptions, the notes to the family, the history of changes,
//  hospitalizing and "Add to calendar".
//

private sealed class SchedVisitSheet {
    data object edit : SchedVisitSheet()
    data object checkIn : SchedVisitSheet()
    data object otherDay : SchedVisitSheet()
    data object finish : SchedVisitSheet()
    data object recordPast : SchedVisitSheet()
    data object noShow : SchedVisitSheet()
    data object cancel : SchedVisitSheet()
    data object rebook : SchedVisitSheet()
    data object walkIn : SchedVisitSheet()
    data object stopSeries : SchedVisitSheet()
    data object calendar : SchedVisitSheet()
    data class gate(val prompt: SchedGatePrompt) : SchedVisitSheet()
    data class admit(val preset: SchedAdmitPreset) : SchedVisitSheet()
    data class deposit(val due: SchedDepositDue) : SchedVisitSheet()

    val id: String
        get() = when (this) {
            is gate -> "gate-${prompt.id}"
            is admit -> "admit-${preset.id}"
            is deposit -> "deposit"
            calendar -> "calendar"
            edit -> "edit"
            checkIn -> "checkIn"
            otherDay -> "otherDay"
            finish -> "finish"
            recordPast -> "recordPast"
            noShow -> "noShow"
            cancel -> "cancel"
            rebook -> "rebook"
            walkIn -> "walkIn"
            stopSeries -> "stopSeries"
        }
}

// MARK: What the visit is now

private data class Facts(
    val status: String,
    val writable: Boolean,
    val waiting: Boolean,
    val missed: Boolean,
    val drafts: List<SchedRecord>,
    val signedCount: Int,
    val written: Boolean,
    /** (memberId, name) of the person here when they sign. */
    val signer: Pair<String, String>?,
    val dayWhen: String,
    val late: Boolean,
    val stage: SchedBillStage,
    val collectAmount: Int,
    val canCollect: Boolean,
    val collects: Boolean,
    val readsBills: Boolean,
    val checkIn: Boolean,
    val start: Boolean,
    val cancel: Boolean,
    val edit: Boolean,
    val checksOut: Boolean,
    val openSinceEarlierDay: Boolean,
    val theyCame: Boolean,
    val paid: Boolean,
    val reportMissing: List<String>,
    val openStay: SchedHospStay?,
    val canHospitalize: Boolean,
)

private class Primary(val title: String, val systemImage: String, val action: () -> Unit)

/** What `closing(_:_:)` gives the closing card (iOS returns the `SchedVisitClosing` view). */
private class Closing(val title: String, val checks: List<SchedClosingCheck>, val actionTitle: String, val actionImage: String, val action: () -> Unit)

/** Swift's `try?` for a read: null on any failure, cancellation passed on. */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

/** The visit's bill, whole: its latest from the bills list, then read on its own. */
private suspend fun visitBill(appointmentId: String): SchedInvoice? {
    val page = orNull {
        API.get<Page<SchedInvoiceSummary>>("/provider-invoices", mapOf("limit" to "10", "offset" to "0", "providerAppointmentId" to appointmentId))
    }
    val latest = page?.items?.sortedByDescending { it.createdAt ?: "" }?.firstOrNull() ?: return null
    return orNull { API.get<SchedInvoice>("/provider-invoices/${latest.id}") }
}

@Composable
fun AppointmentDetailScreen(appointmentId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val open = rememberOpenURL()
    val scope = rememberCoroutineScope()
    val regular = LocalSizeClass.current == SizeClass.regular

    var appointment by remember { mutableStateOf<SchedAppointment?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<SchedRecord>>(emptyList()) }
    var invoice by remember { mutableStateOf<SchedInvoice?>(null) }
    var members by remember { mutableStateOf<List<SchedMember>>(emptyList()) }
    var sheet by remember { mutableStateOf<SchedVisitSheet?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(Instant.now()) }
    var simpleStages by remember { mutableStateOf(false) }
    var reportMissing by remember { mutableStateOf<List<String>?>(null) }
    var hospStays by remember { mutableStateOf<List<SchedHospStay>>(emptyList()) }

    // MARK: Loading

    suspend fun load() {
        if (!workspace.can(Permission.appointmentsView)) return
        val id = appointmentId
        val readsRecords = workspace.can(Permission.recordsView)
        val readsBills = workspace.moneyLevel >= MoneyLevel.charging
        try {
            coroutineScope {
                val visit = async { SchedData.visit(id) }
                val notes = async { if (readsRecords) SchedData.visitRecords(id) else null }
                val team = async { SchedData.members() }
                val bill = async { if (readsBills) visitBill(id) else null }
                appointment = visit.await()
                records = notes.await() ?: emptyList()
                members = team.await()
                invoice = bill.await()
            }
            loadError = null
            val loaded = appointment
            if (loaded?.serviceCategory == "groomer" && loaded.groomStage != null) {
                val settings = orNull { API.get<SchedGroomSettings>("/provider-grooming/settings") }
                simpleStages = settings?.simpleStages ?: false
            }
            // A pet already in the hospital (or waiting for a bed) isn't hospitalized twice: the visit opens that stay.
            val pet = loaded?.providerClientPetId
            if (pet != null && workspace.can(Permission.hospitalView) && workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)) {
                val stays = orNull { API.get<SchedHospStays>("/provider-hospital/hospitalizations", mapOf("providerClientPetId" to pet)) }
                hospStays = stays?.hospitalizations ?: emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (appointment == null) loadError = if ((e as? APIError)?.status == 404) L("This visit was not found.") else errorText(e)
            else Toast.error(e)
        }
    }

    suspend fun reloadRecords() {
        if (!workspace.can(Permission.recordsView)) return
        val page = orNull { API.get<Page<SchedRecord>>("/provider-records", mapOf("limit" to "100", "offset" to "0", "appointmentId" to appointmentId)) }
        records = page?.items ?: records
    }

    // MARK: What the visit is now

    fun facts(a: SchedAppointment): Facts {
        val w = workspace
        val now = Instant.now()
        val drafts = records.filter { it.isDraft && it.hasWords }
        val me = members.firstOrNull { it.isMe }
        val readsBills = w.moneyLevel >= MoneyLevel.charging
        val stage = SchedBillStage.of(visitStatus = a.status, noCharge = a.noCharge == true, invoice = invoice)
        val collect = invoice?.let { if (it.status == "draft") (it.totalAmount ?: 0) - (it.amountPaid ?: 0) else (it.balanceDueAmount ?: 0) } ?: 0
        val dayWhen = SchedDay.`when`(a.startsAt, SchedData.zone(a.locationId))
        val start = w.can(Permission.appointmentsComplete)
        val openEarlier = dayWhen == "past" && (a.status == "checked_in" || a.status == "in_progress")
        val within = (a.start?.let { now.epochSecond - it.epochSecond } ?: 0L) <= 31L * 86_400
        return Facts(
            status = a.status,
            writable = a.status == "in_progress" || a.status == "completed",
            waiting = a.status == "checked_in",
            missed = a.isGone,
            drafts = drafts,
            signedCount = records.count { it.isSigned },
            written = records.any { it.hasWords },
            signer = if (w.can(Permission.recordsApprove)) me?.let { it.id to it.label } else null,
            dayWhen = dayWhen,
            late = a.status == "scheduled" && (a.start ?: Instant.MAX) < now,
            stage = stage,
            collectAmount = collect,
            canCollect = w.can(Permission.billingRecordPayment) && readsBills &&
                listOf(SchedBillStage.building, SchedBillStage.ready, SchedBillStage.partPaid).contains(stage) && collect > 0,
            collects = (w.can(Permission.billingRecordPayment) || w.can(Permission.billingEditInvoice)) && readsBills,
            readsBills = readsBills,
            checkIn = w.can(Permission.appointmentsCheckIn),
            start = start,
            cancel = w.can(Permission.appointmentsCancel),
            edit = w.can(Permission.appointmentsEdit),
            checksOut = !start && w.can(Permission.billingRecordPayment),
            openSinceEarlierDay = openEarlier,
            theyCame = openEarlier && start && within,
            paid = (invoice?.amountPaid ?: 0) > 0,
            reportMissing = if (a.reportKind != null) (reportMissing ?: SvcReportText.missing(a.serviceCategory, a.report)) else emptyList(),
            openStay = hospStays.firstOrNull { it.open },
            canHospitalize = !hospStays.any { it.open } && w.can(Permission.hospitalManage) && w.offers(BusinessCategory.vet, BusinessCategory.emergency) &&
                listOf("checked_in", "in_progress", "completed").contains(a.status),
        )
    }

    // MARK: Actions

    /** Only today's visits check in: on another day, "Check in" first offers to move it to today. */
    fun arrive(f: Facts) {
        sheet = if (f.dayWhen == "today") SchedVisitSheet.checkIn else SchedVisitSheet.otherDay
    }

    fun startVisit(a: SchedAppointment) {
        busy = true
        scope.launch {
            try {
                SchedVisitOps.startWithGate(a) { prompt -> sheet = SchedVisitSheet.gate(prompt) }
                load()
            } finally {
                busy = false
            }
        }
    }

    fun signAll(a: SchedAppointment, drafts: List<SchedRecord>) {
        busy = true
        scope.launch {
            try {
                for (draft in drafts) API.send("POST", "/provider-records/${draft.id}/sign", emptyMap())
                Toast.show(if (a.clientHouseholdId != null) L("Signed. The family can see it now.") else L("Signed. It is part of the chart now."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                busy = false
            }
        }
    }

    fun keepAsBooked(a: SchedAppointment) {
        scope.launch {
            try {
                API.patch<SchedAppointment>("/provider-appointments/${a.id}", mapOf("confirmationStatus" to "confirmed"))
                Toast.show(L("Kept as booked."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            }
        }
    }

    fun askToConfirm(a: SchedAppointment) {
        scope.launch {
            try {
                val result: SchedConfirmationResult = API.post("/provider-appointments/${a.id}/confirmation-request")
                if (result.emailStatus == "failed") Toast.error(result.errorMessage ?: L("The confirmation email failed."))
                else Toast.show(L("Confirmation request sent."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            }
        }
    }

    fun move(a: SchedAppointment, path: String, success: String) {
        scope.launch {
            try {
                SchedAPI.send<SchedMoveResponse>("POST", "/provider-appointments/${a.id}/$path", emptyMap())
                Toast.show(success)
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                schedToast(e)
            }
        }
    }

    /** Hospitalize the visit's patient, with what the triage said (docs/hospitalization.md). */
    fun hospitalize(a: SchedAppointment, triage: SchedTriage?) {
        sheet = SchedVisitSheet.admit(
            SchedAdmitPreset(
                appointmentId = a.id, clientName = a.clientName, familyOnApp = a.clientHouseholdId != null,
                petId = a.providerClientPetId, petName = a.petName, priority = triage?.triagePriority, reason = triage?.triageReason,
            )
        )
    }

    /** A record into this visit (a vaccine, an allergy, a diagnosis, a medicine, or any). */
    fun addRecord(a: SchedAppointment, type: String?) {
        nav.push(ProviderRoute.newRecord(RecordPrefill(clientId = a.providerClientId, petId = a.providerClientPetId, appointmentId = a.id, recordType = type)))
    }

    fun showGate(prompt: SchedGatePrompt) {
        scope.launch {
            delay(450)
            sheet = SchedVisitSheet.gate(prompt)
        }
    }

    fun reopenAs(next: SchedVisitSheet) {
        scope.launch {
            delay(450)
            sheet = next
        }
    }

    /** After "Charge now" or "Put the price myself": the bill, in Cuentas. */
    fun openBillAfterClosing() {
        scope.launch {
            delay(450)
            val bill = visitBill(appointmentId)
            invoice = bill
            if (bill != null) nav.push(ProviderRoute.invoice(bill.id))
        }
    }

    /** The header always holds the visit's next step: arrive, start, finish, sign, collect. */
    fun primary(a: SchedAppointment, f: Facts): Primary? {
        if (f.openSinceEarlierDay) {
            return if (f.theyCame) Primary(L("They came"), "badge-check") { sheet = SchedVisitSheet.recordPast } else null
        }
        if (f.status == "scheduled" && f.checkIn) return Primary(L("Check in"), "log-in") { arrive(f) }
        if (f.status == "checked_in" && f.start) return Primary(L("Start"), "play") { startVisit(a) }
        if (f.status == "in_progress" && (f.start || f.checksOut)) {
            return Primary(if (f.checksOut) L("Check out") else L("Finish the visit"), if (f.checksOut) "log-out" else "badge-check") { sheet = SchedVisitSheet.finish }
        }
        if (f.status == "completed" && f.drafts.isNotEmpty() && f.signer != null) return Primary(L("Sign now"), "pen-line") { signAll(a, f.drafts) }
        val bill = invoice
        if (f.status == "completed" && f.canCollect && bill != null) {
            return Primary(LF("Collect %@", Money.format(f.collectAmount, bill.currency)), "hand-coins") { nav.push(ProviderRoute.invoice(bill.id)) }
        }
        if (f.missed && f.edit) return Primary(L("Book again"), "calendar-plus") { sheet = SchedVisitSheet.rebook }
        return null
    }

    /** The closing card under the note: the same next step as the header, with what it waits for. */
    fun closing(a: SchedAppointment, f: Facts): Closing? {
        val checks = mutableListOf<SchedClosingCheck>()
        val canRead = workspace.can(Permission.recordsView)
        if (a.reportKind != null && (f.status == "in_progress" || f.status == "completed")) {
            checks.add(
                if (f.reportMissing.isEmpty()) SchedClosingCheck(id = "report", label = L("Report"), value = L("Filled in"), state = SchedClosingCheck.State.ok)
                else SchedClosingCheck(id = "report", label = L("Report"), value = LF("Missing: %@", SvcReportText.fieldsText(f.reportMissing)), state = SchedClosingCheck.State.warn)
            )
        }
        if (canRead && a.reportKind == null) {
            checks.add(
                if (f.written) SchedClosingCheck(id = "note", label = L("Note"), value = L("Written"), state = SchedClosingCheck.State.ok)
                else SchedClosingCheck(
                    id = "note", label = L("Note"), value = if (a.isClinical) L("Not written yet") else L("No note yet"),
                    state = if (a.isClinical) SchedClosingCheck.State.warn else SchedClosingCheck.State.pending,
                )
            )
        }
        if (f.readsBills) {
            val currency = invoice?.currency
            checks.add(
                when (f.stage) {
                    SchedBillStage.needsPrice -> SchedClosingCheck(id = "bill", label = L("Bill"), value = L("Needs a price"), state = SchedClosingCheck.State.pending)
                    SchedBillStage.noBill -> SchedClosingCheck(id = "bill", label = L("Bill"), value = L("No bill yet"), state = SchedClosingCheck.State.pending)
                    SchedBillStage.noCharge -> SchedClosingCheck(id = "bill", label = L("Bill"), value = L("No charge"), state = SchedClosingCheck.State.ok)
                    SchedBillStage.paid, SchedBillStage.covered -> SchedClosingCheck(id = "bill", label = L("Bill"), value = L("Paid"), state = SchedClosingCheck.State.ok)
                    else -> SchedClosingCheck(
                        id = "bill", label = L("Bill"),
                        value = Money.format(if (f.stage == SchedBillStage.building) invoice?.totalAmount else f.collectAmount, currency),
                        state = SchedClosingCheck.State.ok,
                    )
                }
            )
        }
        if (canRead && a.reportKind == null && (f.written || f.drafts.isNotEmpty())) {
            if (f.drafts.isEmpty()) {
                checks.add(SchedClosingCheck(id = "sign", label = L("Signature"), value = L("Notes signed"), state = SchedClosingCheck.State.ok))
            } else {
                val value = if (f.signer != null) (if (f.status == "completed") L("Sign it now") else L("Signed when you finish"))
                    else (a.assignedMember?.name?.let { LF("Waiting for %@", it) } ?: L("Waiting for a vet"))
                checks.add(SchedClosingCheck(id = "sign", label = L("Signature"), value = value, state = if (f.status == "completed") SchedClosingCheck.State.missing else SchedClosingCheck.State.pending))
            }
        }
        if (f.openSinceEarlierDay) {
            if (!(f.theyCame && f.status == "in_progress")) return null
            return Closing(L("Ready to finish?"), checks, L("They came"), "badge-check") { sheet = SchedVisitSheet.recordPast }
        }
        if (f.status == "in_progress" && (f.start || f.checksOut)) {
            return Closing(L("Ready to finish?"), checks, if (f.checksOut) L("Check out") else L("Finish the visit"), if (f.checksOut) "log-out" else "badge-check") {
                sheet = SchedVisitSheet.finish
            }
        }
        if (f.status == "completed") {
            val next = primary(a, f) ?: return null
            return Closing(L("Still to do"), checks, next.title, next.systemImage, next.action)
        }
        return null
    }

    /** A groom's stage as the journey's middle step ("Estética · En baño"). */
    fun groomStageText(a: SchedAppointment): String? {
        val stage = a.groomStage
        if (a.serviceCategory != "groomer" || stage == null) return null
        return SchedGroomStageBar.label(if (simpleStages && stage == "styling") "bath" else stage, simpleStages)
    }

    // MARK: The journey

    fun journey(a: SchedAppointment, f: Facts): List<SchedJourneyStep> {
        val steps = mutableListOf<SchedJourneyStep>()
        val now = Instant.now()
        val since = { iso: String? -> maxOf(0L, (now.epochSecond - (DateText.parse(iso) ?: now).epochSecond) / 60).toInt() }
        val booked = DateText.time(a.start)
        val arrived = a.arrivedAt
        val door = "door-open"
        steps.add(
            when {
                a.isRequested -> SchedJourneyStep(id = "arrival", label = L("Arrival"), value = booked, extra = L("To confirm"), state = SchedJourneyStep.State.next, systemImage = door)
                arrived != null -> SchedJourneyStep(id = "arrival", label = L("Arrival"), value = DateText.time(arrived), extra = LF("Booked %@", booked), state = SchedJourneyStep.State.done, systemImage = door)
                f.missed -> SchedJourneyStep(id = "arrival", label = L("Arrival"), value = if (a.status == "no_show") L("No-show") else L("Cancelled"), state = SchedJourneyStep.State.attention, systemImage = door)
                f.dayWhen != "today" && f.status == "scheduled" -> SchedJourneyStep(
                    id = "arrival", label = L("Arrival"), value = DateText.day(a.start),
                    extra = if (f.dayWhen == "past") L("Never checked in") else LF("Booked %@", booked),
                    state = if (f.dayWhen == "past") SchedJourneyStep.State.attention else SchedJourneyStep.State.next, systemImage = door,
                )
                f.late -> SchedJourneyStep(id = "arrival", label = L("Arrival"), value = LF("%@ late", SchedDay.duration(since(a.startsAt))), extra = LF("Booked %@", booked), state = SchedJourneyStep.State.attention, systemImage = door)
                else -> SchedJourneyStep(id = "arrival", label = L("Arrival"), value = booked, extra = L("Booked"), state = SchedJourneyStep.State.now, systemImage = door)
            }
        )
        val vetCare = workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)
        val visitLabel = if (a.serviceCategory == "groomer") L("Grooming") else if (a.isClinical && vetCare) L("Consult") else L("Service")
        val visit = "stethoscope"
        val finished = a.finishedAt
        if (f.status == "completed" && finished != null) {
            val length = a.startedAt?.let { started ->
                SchedDay.duration(maxOf(0L, ((DateText.parse(finished) ?: now).epochSecond - (DateText.parse(started) ?: now).epochSecond) / 60).toInt())
            }
            steps.add(SchedJourneyStep(id = "visit", label = visitLabel, value = DateText.time(finished), extra = length, state = SchedJourneyStep.State.done, systemImage = visit))
        } else if (f.status == "in_progress") {
            val stage = groomStageText(a)
            if (stage != null) {
                steps.add(SchedJourneyStep(id = "visit", label = visitLabel, value = stage, extra = LF("In for %@", SchedDay.duration(since(a.startedAt))), state = SchedJourneyStep.State.now, systemImage = visit))
            } else {
                steps.add(SchedJourneyStep(
                    id = "visit", label = visitLabel, value = LF("In for %@", SchedDay.duration(since(a.startedAt))),
                    extra = a.startedAt?.let { LF("Started %@", DateText.time(it)) }, state = SchedJourneyStep.State.now, systemImage = visit,
                ))
            }
        } else if (f.waiting) {
            val stage = groomStageText(a)
            if (stage != null) {
                steps.add(SchedJourneyStep(id = "visit", label = visitLabel, value = stage, extra = LF("Waits %@", SchedDay.duration(since(a.arrivedAt))), state = SchedJourneyStep.State.now, systemImage = visit))
            } else {
                steps.add(SchedJourneyStep(id = "visit", label = visitLabel, value = LF("Waits %@", SchedDay.duration(since(a.arrivedAt))), state = SchedJourneyStep.State.now, systemImage = visit))
            }
        } else {
            steps.add(SchedJourneyStep(id = "visit", label = visitLabel, value = "—", state = if (f.missed) SchedJourneyStep.State.skip else SchedJourneyStep.State.next, systemImage = visit))
        }
        if (workspace.can(Permission.recordsView) && a.reportKind == null) {
            val signer = f.signer != null
            val pen = "pen-line"
            steps.add(
                when {
                    f.drafts.isNotEmpty() && f.status == "completed" -> SchedJourneyStep(
                        id = "sign", label = L("Signature"), value = LF("%@ to sign", f.drafts.size.toString()),
                        extra = if (signer) L("Sign it now") else (a.assignedMember?.name?.let { LF("Waiting for %@", it) } ?: L("Waiting for a vet")),
                        state = SchedJourneyStep.State.attention, systemImage = pen,
                    )
                    f.drafts.isNotEmpty() -> SchedJourneyStep(
                        id = "sign", label = L("Signature"), value = L("Draft saved"),
                        extra = if (signer) L("Signed when you finish") else L("A vet signs it"), state = SchedJourneyStep.State.next, systemImage = pen,
                    )
                    f.signedCount > 0 -> SchedJourneyStep(id = "sign", label = L("Signature"), value = L("Notes signed"), state = SchedJourneyStep.State.done, systemImage = pen)
                    else -> SchedJourneyStep(
                        id = "sign", label = L("Signature"), value = L("No note yet"),
                        extra = if (a.isClinical && f.writable) L("Best written before finishing") else null,
                        state = if (f.missed) SchedJourneyStep.State.skip else SchedJourneyStep.State.next, systemImage = pen,
                    )
                }
            )
        }
        if (f.readsBills) {
            val currency = invoice?.currency
            val completed = f.status == "completed"
            val coin = "circle-dollar-sign"
            steps.add(
                when (f.stage) {
                    SchedBillStage.paid -> SchedJourneyStep(id = "pay", label = L("Payment"), value = L("Paid"), extra = invoice?.let { Money.format(it.totalAmount, currency) }, state = SchedJourneyStep.State.done, systemImage = coin)
                    SchedBillStage.covered -> SchedJourneyStep(id = "pay", label = L("Payment"), value = L("Nothing to charge"), state = SchedJourneyStep.State.done, systemImage = coin)
                    SchedBillStage.noCharge -> SchedJourneyStep(id = "pay", label = L("Payment"), value = L("No charge"), state = SchedJourneyStep.State.done, systemImage = coin)
                    SchedBillStage.ready, SchedBillStage.partPaid -> SchedJourneyStep(id = "pay", label = L("Payment"), value = Money.format(f.collectAmount, currency), extra = L("To pay"), state = SchedJourneyStep.State.attention, systemImage = coin)
                    SchedBillStage.building -> SchedJourneyStep(
                        id = "pay", label = L("Payment"), value = Money.format(invoice?.totalAmount, currency),
                        extra = if (completed) L("To pay") else L("On the bill"),
                        state = if (completed) SchedJourneyStep.State.attention else SchedJourneyStep.State.next, systemImage = coin,
                    )
                    SchedBillStage.needsPrice -> SchedJourneyStep(
                        id = "pay", label = L("Payment"), value = L("Needs a price"),
                        state = if (completed) SchedJourneyStep.State.attention else SchedJourneyStep.State.next, systemImage = coin,
                    )
                    SchedBillStage.noBill -> SchedJourneyStep(id = "pay", label = L("Payment"), value = L("No bill yet"), state = SchedJourneyStep.State.next, systemImage = coin)
                    else -> SchedJourneyStep(id = "pay", label = L("Payment"), value = "—", state = if (f.missed) SchedJourneyStep.State.skip else SchedJourneyStep.State.next, systemImage = coin)
                }
            )
        }
        return steps
    }

    // MARK: The menu

    /** The header's ⋯ (web: the visit's dropdown): the less common moves, the risky ones last in red. */
    @Composable
    fun VisitMenu(a: SchedAppointment) {
        val f = facts(a)
        val late = f.status == "scheduled" && f.late && f.checkIn
        val cancels = (f.status == "scheduled" || f.status == "checked_in") && f.cancel
        val stops = a.seriesId != null && f.status == "scheduled" && f.cancel
        AppMenu(label = { IconCircleLabel() }, accessibilityLabel = L("More")) {
            if (f.edit && (f.status == "scheduled" || f.status == "checked_in")) {
                item(L("Edit booking"), icon = "pencil") { sheet = SchedVisitSheet.edit }
            }
            if (f.edit && f.status == "scheduled" && a.clientEmail.nonEmpty != null) {
                item(L("Ask them to confirm"), icon = "mail-check") { askToConfirm(a) }
            }
            item(L("Add to calendar"), icon = "download") { sheet = SchedVisitSheet.calendar }
            if (f.status == "checked_in" && f.checkIn) {
                item(L("Undo arrival"), icon = "undo-2") { move(a, "undo-arrival", L("Back to upcoming.")) }
            }
            if (f.status == "completed" && f.start && !f.paid) {
                item(L("Reopen visit"), icon = "rotate-ccw") { move(a, "reopen", L("Back with the vet.")) }
            }
            // Hospitalizing is rare on a routine visit: it lives here unless triage makes it urgent.
            if (f.canHospitalize) {
                item(L("Hospitalize"), icon = "bed-double") { hospitalize(a, null) }
            }
            f.openStay?.let { stay ->
                item(L("Open the hospitalization"), icon = "bed-double") { nav.push(ProviderRoute.hospitalization(stay.id)) }
            }
            if (workspace.can(Permission.appointmentsCreate) && workspace.can(Permission.clientsView) && !a.isRequested) {
                item(L("Walk-in"), icon = "zap") { sheet = SchedVisitSheet.walkIn }
            }
            if (late || cancels || stops) divider()
            if (late) {
                item(L("They didn’t come"), icon = "circle-slash", destructive = true) { sheet = SchedVisitSheet.noShow }
            }
            if (cancels) {
                item(L("Cancel visit"), icon = "calendar-x-2", destructive = true) { sheet = SchedVisitSheet.cancel }
            }
            if (stops) {
                item(L("Stop repeating from here"), icon = "repeat", destructive = true) { sheet = SchedVisitSheet.stopSeries }
            }
        }
    }

    // MARK: Header

    @Composable
    fun PetMedia(a: SchedAppointment) {
        // The header draws its lead 36 round on a phone, 56 on an iPad.
        val size = if (regular) 56.dp else 36.dp
        if (workspace.can(Permission.clientsView)) {
            Box(Modifier.plainButton(label = LF("Open %@’s chart", a.petName)) { nav.push(ProviderRoute.patientChart(a.providerClientPetId)) }) {
                PetAvatar(name = a.petName, species = a.petSpecies, photoURL = a.petPhotoUrl, size = size)
            }
        } else {
            PetAvatar(name = a.petName, species = a.petSpecies, photoURL = a.petPhotoUrl, size = size)
        }
    }

    @Composable
    fun ClientName(a: SchedAppointment) {
        val label = @Composable {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("user-round", 16.dp, tint = Theme.inkSoft)
                Text(a.clientName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (workspace.can(Permission.clientsView)) {
            Box(Modifier.plainButton { nav.push(ProviderRoute.client(a.providerClientId)) }) { label() }
        } else {
            label()
        }
    }

    /** A link for the family (iOS `Link { Glyph(14) + Text }.buttonStyle(.pm(.outline, size: .header))`). */
    @Composable
    fun ReachButton(title: String, icon: String, url: String) {
        PMButtonStyle(variant = ButtonVariant.outline, size = ButtonSize.header, onClick = { open(url) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(icon, 14.dp)
                Text(title)
            }
        }
    }

    @Composable
    fun HeaderFooter(a: SchedAppointment, f: Facts) {
        // A groom that is here: where it is, moved in one tap, and telling the family once it is ready.
        if (a.serviceCategory == "groomer" && a.groomStage != null && (f.waiting || f.status == "in_progress") && f.start) {
            RowDivider()
            SchedGroomStageBar(appointment = a, simple = simpleStages, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { prompt ->
                sheet = SchedVisitSheet.gate(prompt)
            }
        }
        RowDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val about = listOf(schedSpeciesLabel(a.petSpecies), a.petBreed, schedAge(a.petBirthDate), schedSexLabel(a.petSex)).mapNotNull { it.nonEmpty }
            if (about.isNotEmpty()) {
                Text(about.joinToString(" · "), style = Theme.text(13, FontWeight.Medium), color = Theme.inkSoft)
            }
            FlowLayout(spacing = 12.dp, lineSpacing = 8.dp) {
                ClientName(a)
                a.clientPhone?.let { Text(it, style = Theme.text(14).monospacedDigit(), color = Theme.inkSoft) }
                // Reaching the family: two equal buttons, not tags.
                if (a.clientPhone != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        schedPhoneURL(a.clientPhone)?.let { ReachButton(L("Call"), "phone", it.toString()) }
                        schedWhatsAppURL(a.clientPhone)?.let { ReachButton("WhatsApp", "message-circle", it.toString()) }
                    }
                }
                if (!(a.assignedMember?.name?.let { schedSameName(it, a.clientName) } ?: false)) {
                    Text("· " + (a.assignedMember?.name?.let { LF("with %@", it) } ?: L("Whoever is free")), style = Theme.text(14), color = Theme.inkSoft)
                }
                a.room?.let { Text("· $it", style = Theme.text(14), color = Theme.inkSoft) }
                if (a.isRequested) SchedPendingMark() else if (a.rescheduleProposal != null) SchedRescheduleMark()
                if (a.bookedOnline == true) SchedOnlineMark()
                a.movedFromStartsAt?.let { Text("· " + LF("You moved it from %@", DateText.time(it)), style = Theme.text(14), color = Theme.inkSoft) }
                if (a.hoursOverride == true) Chip(text = L("After hours"), icon = "moon-star")
                if (a.seriesId != null) Chip(text = L("Repeats"), icon = "repeat")
                if (a.doubleBooked == true) Chip(text = L("Double booked"), tone = Chip.Tone.danger, icon = "triangle-alert")
                if (a.atHome == true) Chip(text = L("At the client’s home"), tone = Chip.Tone.info, icon = "house")
            }
        }
        if (SchedVisitBanners.any(a)) {
            RowDivider()
            SchedVisitBanners(
                appointment = a,
                onEdit = if (f.edit) ({ sheet = SchedVisitSheet.edit }) else null,
                onCancel = if (f.cancel) ({ sheet = SchedVisitSheet.cancel }) else null,
                onKeep = if (f.edit) ({ keepAsBooked(a) }) else null,
            )
        }
    }

    /**
     * The shared page header (web `ProviderModuleHeader` on the visit): the pet (its photo
     * opens the chart), what it came for, the next step and ⋯; the visit's steps as its
     * numbers; the groom's stage bar, the family's line and the banners as its footer.
     */
    @Composable
    fun Header(a: SchedAppointment, f: Facts, steps: List<SchedJourneyStep>) {
        val dateLabel = SchedDay.upperFirst(DateText.longDay(a.start))
        ModuleHeader(
            icon = "paw-print",
            title = a.petName,
            subtitle = "${a.serviceLabel} · $dateLabel",
            accent = Theme.aqua,
            media = { PetMedia(a) },
            stats = steps.map { it.stat },
            actionsOnRow = false,
            storageKey = "visit",
            actions = {
                primary(a, f)?.let { next -> PMButton(next.title, systemImage = next.systemImage, isWorking = busy, action = next.action) }
                VisitMenu(a)
            },
            footer = { HeaderFooter(a, f) },
        )
    }

    // MARK: The page

    @Composable
    fun ClosingCard(closing: Closing) {
        SchedVisitClosing(title = closing.title, checks = closing.checks, actionTitle = closing.actionTitle, actionImage = closing.actionImage, action = closing.action)
    }

    @Composable
    fun Work(a: SchedAppointment, f: Facts) {
        if (f.status == "completed" && f.drafts.isNotEmpty() && f.signer == null) {
            SchedToSignBanner(count = f.drafts.size, vetName = a.assignedMember?.name)
        }
        val canRead = workspace.can(Permission.recordsView)
        if (a.reportKind != null) {
            SchedVisitReportCard(appointment = a, canWrite = workspace.can(Permission.appointmentsComplete) && !f.missed) { reportMissing = it }
            closing(a, f)?.let { ClosingCard(it) }
        } else if (canRead) {
            SchedVisitNote(
                appointment = a, records = records,
                canWrite = (workspace.can(Permission.recordsCreate) || workspace.can(Permission.recordsEdit)) && !f.missed,
                canSign = workspace.can(Permission.recordsApprove) && !f.missed && f.status == "completed",
                signsOnFinish = f.signer != null,
                noteRequired = a.isClinical,
                onChanged = { reloadRecords() },
            )
            closing(a, f)?.let { ClosingCard(it) }
        } else {
            closing(a, f)?.let { ClosingCard(it) }
        }
        if (canRead) {
            val noteType = a.serviceCategory?.let { if (listOf("vet", "emergency", "pharmacy").contains(it)) "vet_visit" else "note" } ?: "vet_visit"
            val note = records.filter { it.recordType == noteType }.sortedBy { it.createdAt ?: "" }.firstOrNull()
            val others = records.filter { it.id != note?.id || a.reportKind != null }
            if (others.isNotEmpty() || f.writable) {
                SchedVisitRecords(
                    records = others, canSign = workspace.can(Permission.recordsApprove) && !f.missed, onChanged = { reloadRecords() },
                    onAdd = if (f.writable && workspace.can(Permission.recordsCreate)) ({ addRecord(a, null) }) else null,
                )
            }
        }
        // Prescriptions written at this visit (docs/prescriptions.md).
        if (a.isClinical) {
            SchedVisitPrescriptions(appointment = a)
        }
        SchedVisitUpdatesCard(appointment = a, canSend = workspace.can(Permission.appointmentsComplete))
        if (f.readsBills) {
            SchedVisitBillCard(appointment = a, invoice = invoice, stage = f.stage, collectAmount = f.collectAmount, canCollect = f.canCollect)
        }
    }

    /**
     * The visit's work by status: the online request's answers, the arrival, the waiting room, or the note and the bill.
     * A family's ask to move it ("Cambio de hora") is answered first; the visit stays booked meanwhile.
     */
    @Composable
    fun Center(a: SchedAppointment, f: Facts) {
        if (!a.isRequested && a.rescheduleProposal != null) {
            SchedRequestPanel(target = SchedAnswerTarget(appointment = a))
        }
        if (a.isRequested) {
            SchedRequestPanel(target = SchedAnswerTarget(appointment = a))
        } else if (f.status == "scheduled") {
            SchedArrivalLock(appointment = a, late = f.late, dayWhen = f.dayWhen, canStart = f.start, onArrive = if (f.checkIn) ({ arrive(f) }) else null)
        } else if (f.waiting) {
            SchedWaitingLock(appointment = a, onStart = if (f.start) ({ startVisit(a) }) else null, starting = busy)
        } else {
            Work(a, f)
        }
    }

    /**
     * What the web shows beside the visit: the request, the group, the care card, the triage,
     * the chart, the consents and the incidents.
     */
    @Composable
    fun SideCards(a: SchedAppointment, f: Facts) {
        val w = workspace
        if (a.reportKind == "groom") {
            SchedGroomRequestCard(appointmentId = a.id, petName = a.petName, canEdit = w.canAny(Permission.appointmentsCreate, Permission.appointmentsCheckIn, Permission.appointmentsComplete))
        }
        SchedVisitGroupCard(appointment = a)
        if (a.reportKind != null) {
            SvcCareCard(petId = a.providerClientPetId, petName = a.petName, canEdit = w.canAny(Permission.petsEdit, Permission.appointmentsComplete))
        }
        // Only a clinical visit is triaged: a groom, a walk or a class isn't, even where the workspace also does vet care.
        if (w.offers(BusinessCategory.vet, BusinessCategory.emergency) && a.isClinical && w.canAny(Permission.appointmentsCheckIn, Permission.hospitalView)) {
            SchedTriageCard(
                appointmentId = a.id,
                canEdit = w.can(Permission.appointmentsCheckIn) && listOf("scheduled", "checked_in", "in_progress").contains(a.status),
                onHospitalize = if (f.canHospitalize) ({ triage -> hospitalize(a, triage) }) else null,
                hospitalization = f.openStay,
                onTakeDeposit = if (f.readsBills && w.can(Permission.billingRecordPayment) && !a.isGone) ({ due -> sheet = SchedVisitSheet.deposit(due) }) else null,
            )
        }
        if (w.canAny(Permission.recordsView, Permission.clientsView)) {
            SchedVisitChart(
                appointmentId = a.id, petId = a.providerClientPetId, petWeightGrams = a.petWeight,
                canLogWeight = w.can(Permission.appointmentsEdit) && (f.writable || f.waiting),
                onAdd = if (f.writable && w.can(Permission.recordsCreate)) ({ type -> addRecord(a, type) }) else null,
            )
        }
        ConsentsCard(
            context = ConsentContext(
                act = ConsentAct(appointmentId = a.id), businessName = w.current?.name, clientName = a.clientName,
                familyOnApp = a.clientHouseholdId != null, clientEmail = a.clientEmail, clientPhone = a.clientPhone,
                pets = listOf(ActPet(id = a.providerClientPetId, name = a.petName)),
                actLabel = "${a.serviceLabel} · ${DateText.dayTime(a.startsAt)}", started = f.writable,
            )
        )
        IncidentsCard(
            act = ConsentAct(appointmentId = a.id), pets = listOf(ActPet(id = a.providerClientPetId, name = a.petName)), familyName = a.clientName,
            recordable = listOf("checked_in", "in_progress", "completed").contains(a.status),
        )
    }

    @Composable
    fun LinkRow(hint: String, title: String, route: ProviderRoute, lead: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().plainButton { nav.push(route) }.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lead()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
    }

    /** What was booked, and the client, pet and chart one tap away (not on the web's page; kept). */
    @Composable
    fun BookingCard(a: SchedAppointment, f: Facts) {
        SectionCard(
            title = L("The booking"), icon = "calendar-days", accent = Theme.aqua,
            trailing = {
                if (f.edit && (f.status == "scheduled" || f.status == "checked_in")) {
                    PMButton(L("Edit booking"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.header) { sheet = SchedVisitSheet.edit }
                }
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FactRow(label = L("When"), value = "${SchedDay.upperFirst(DateText.longDay(a.start))} · ${DateText.time(a.start)}${a.end?.let { "–" + DateText.time(it) } ?: ""}")
                FactRow(label = L("Service"), value = a.serviceLabel)
                FactRow(label = L("Person"), value = a.assignedMember?.name ?: L("Whoever is free"))
                FactRow(label = L("Room"), value = a.room)
                FactRow(label = L("Status"), value = SchedStatus.label(a.status))
                SchedStatus.confirmationLabel(a.confirmationStatus)?.let { FactRow(label = L("Confirmation"), value = it) }
                if (a.bookedOnline == true) FactRow(label = L("Booked"), value = L("Booked online"))
                else a.createdBy?.name?.let { FactRow(label = L("Booked by"), value = it) }
                a.notes.nonEmpty?.let { notes ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(L("Notes"), style = Theme.smallFont, color = Theme.inkSoft)
                        Text(notes, style = Theme.text(14), color = Theme.inkStrong)
                    }
                }
                a.accessNotes.nonEmpty?.let { FactRow(label = L("Access notes"), value = it) }
            }
            if (workspace.can(Permission.clientsView)) {
                RowDivider()
                LinkRow(L("Client"), a.clientName, ProviderRoute.client(a.providerClientId)) {
                    Avatar(name = a.clientName, imageURL = a.clientPhotoUrl, size = 32.dp)
                }
                RowDivider()
                LinkRow(L("Pet"), a.petName, ProviderRoute.clientPet(clientId = a.providerClientId, petId = a.providerClientPetId)) {
                    PetAvatar(name = a.petName, species = a.petSpecies, photoURL = a.petPhotoUrl, size = 32.dp)
                }
                RowDivider()
                LinkRow(L("Allergies, weight, vaccines and history"), L("Patient chart"), ProviderRoute.patientChart(a.providerClientPetId)) {
                    IconBadge(icon = "file-text", accent = Theme.aqua, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 35.0)
                }
            }
        }
    }

    // MARK: Sheets

    @Composable
    fun SheetView(item: SchedVisitSheet, a: SchedAppointment) {
        val f = facts(a)
        when (item) {
            SchedVisitSheet.edit -> SchedBookingSheet(draft = SchedBookingDraft(appointment = a))
            SchedVisitSheet.walkIn -> SchedBookingSheet(
                draft = SchedBookingDraft(mode = SchedBookingDraft.Mode.walkIn, clientId = a.providerClientId, petId = a.providerClientPetId),
                onGate = { prompt -> showGate(prompt) },
            )
            SchedVisitSheet.checkIn -> SchedCheckInSheet(appointment = a, canStart = f.start) { prompt -> showGate(prompt) }
            SchedVisitSheet.otherDay -> SchedOtherDaySheet(
                appointment = a,
                `when` = if (f.dayWhen == "past") "past" else "future",
                canMove = f.edit,
                onMoved = {
                    scope.launch {
                        load()
                        sheet = SchedVisitSheet.checkIn
                    }
                },
                onNoShow = if (f.checkIn) ({ sheet = SchedVisitSheet.noShow }) else null,
                onWalkIn = if (workspace.can(Permission.appointmentsCreate) && workspace.can(Permission.clientsView)) ({ sheet = SchedVisitSheet.walkIn }) else null,
                onRecordPast = if (f.theyCame || (f.start && f.dayWhen == "past" && (a.start?.let { Instant.now().epochSecond - it.epochSecond } ?: 0L) <= 31L * 86_400)) {
                    { sheet = SchedVisitSheet.recordPast }
                } else null,
            )
            SchedVisitSheet.finish -> SchedFinishSheet(
                appointment = a, drafts = f.drafts.size, signed = f.signedCount, invoice = invoice, signer = f.signer, collects = f.collects,
                onAfter = { openBillAfterClosing() },
                onEarlierDay = if (f.start) ({ reopenAs(SchedVisitSheet.recordPast) }) else null,
            )
            SchedVisitSheet.recordPast -> SchedRecordPastSheet(appointment = a, invoice = invoice, collects = f.collects) { openBillAfterClosing() }
            SchedVisitSheet.noShow -> SchedMissedSheet(appointment = a, mode = "no_show")
            SchedVisitSheet.cancel -> SchedMissedSheet(appointment = a, mode = "cancel")
            SchedVisitSheet.rebook -> SchedRebookSheet(appointment = a)
            SchedVisitSheet.stopSeries -> ConfirmSheet(
                title = L("Stop repeating from here"),
                message = L("Cancel this visit and every later one of the repeating booking?"),
                confirmTitle = L("Stop repeating"), destructive = true,
            ) {
                val result: SchedStopSeriesResult = API.post("/provider-appointments/${a.id}/stop-series", mapOf("reason" to null))
                announceDataChanged()
                Toast.show(LF("%@ visits cancelled.", (result.cancelled ?: 0).toString()))
            }
            is SchedVisitSheet.gate -> SchedGateSheet(prompt = item.prompt)
            SchedVisitSheet.calendar -> SchedCalendarSheet(appointment = a)
            is SchedVisitSheet.admit -> SchedAdmitSheet(preset = item.preset) { id ->
                scope.launch {
                    delay(450)
                    nav.push(ProviderRoute.hospitalization(id))
                }
            }
            is SchedVisitSheet.deposit -> DepositSheet(
                clientId = a.providerClientId, clientName = a.clientName, appointmentId = a.id, owed = item.due.owed, currency = item.due.currency,
                label = LF("Emergency deposit · %@", a.petName), explanation = L("It stays as credit on their account and is used when they pay the visit."),
            )
        }
    }

    ReloadsOnScope { load() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick = Instant.now()
        }
    }

    val current = appointment
    providerPage(title = current?.petName ?: L("Visit"), modifier = modifier) {
        val error = loadError
        when {
            !workspace.can(Permission.appointmentsView) -> PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see visits.")) }
            current != null -> {
                // Read every 30 s so the times (late, waiting, in for) move on.
                @Suppress("UNUSED_VARIABLE") val now = tick
                val f = facts(current)
                val steps = journey(current, f)
                // The web's order: the header (the next step, the four steps, the family, the
                // banners); on a phone the work first (the request, the arrival, the waiting room,
                // or the note and the bill), the chart after; on an iPad the chart beside the work.
                PageScroll(maxWidth = if (regular) 1600.dp else 820.dp, refresh = { load() }) {
                    Header(current, f, steps)
                    if (regular) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.width(272.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { SideCards(current, f) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { Center(current, f) }
                        }
                    } else {
                        Center(current, f)
                        SideCards(current, f)
                    }
                    BookingCard(current, f)
                    AuditHistoryCard(
                        entityType = "provider_appointment", entityId = current.id,
                        title = L("History of changes"), description = L("Every change to this visit, who made it and when."),
                    )
                }
            }
            error != null -> PageScroll { ErrorState(message = error) { load() } }
            else -> PageScroll { LoadingState() }
        }
    }

    val presented = sheet
    if (presented != null && current != null) {
        key(presented.id) {
            AppSheet(onDismissRequest = { if (sheet?.id == presented.id) sheet = null }) { SheetView(presented, current) }
        }
    }
}
