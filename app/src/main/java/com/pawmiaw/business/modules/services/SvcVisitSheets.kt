package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlin.math.min

//
//  SvcVisitSheets.kt (iOS SvcVisitSheets.swift)
//
//  The visit's own sheets as the Services pages use them: finishing with the
//  charge choice (docs/charging-flow.md), the report at the end of a groom,
//  walk or class (with its photos), "On my way", and "didn't come" / taking
//  a pet out (with the fee the server asks a decision on).
//

// MARK: - Finish, with the charge choice

data class SvcFinishTarget(
    val id: String,
    val petName: String,
    val serviceLabel: String,
    val serviceCategory: String?,
    var reportMissing: List<String> = emptyList(),
    /** For "¿Cuándo vuelve?" and the signature: who the family is and whether PawMiaw reaches them. */
    var clientName: String = "",
    var familyOnApp: Boolean = false,
    /** Who the visit is assigned to — the vet who signs what is left unsigned. */
    var assignedId: String? = null,
    var assignedName: String? = null,
    /** The family's other pets finished with it: they get the same return day. */
    var together: List<String> = emptyList(),
    /** Where it is: the return day counts by that location's hours. */
    var locationId: String? = null,
)

/** What the visit page already knows when it opens "Terminar": nothing is read again. */
data class SvcFinishKnown(
    val drafts: Int,
    val signed: Int,
    /** The person finishing, when they can sign (owner, admin, vet): (memberId, name). */
    val signer: Pair<String, String>?,
)

/**
 * "Terminar" (web: visits/visit-sheets.tsx VisitFinishSheet), the one finish sheet — the visit page
 * (through SchedFinishSheet), Domicilio, Paseos y clases and Home: where the bill goes (the desk, charged now,
 * priced by me, or not charged with the reason), signing what is written when the person can sign,
 * and "¿Cuándo vuelve?" (docs/come-back.md) — the charge choice and the return are the Agenda's own pieces.
 *
 * - `extraBody`: a home visit's press time.
 * - `onOpenReport`: "Fill it in now": the report.
 * - `onOpenVisit`: where this screen has the visit's bill (charge now / price it), and where an earlier day's visit is closed.
 * - `prepare`: taken as Finish is pressed (a home visit's time, where the phone was, the last of a walk's route): it never stops the finish.
 * - `invoice`: the visit's bill as the page has it: whether it is ready to charge, and the hints under each choice.
 * - `known`: the drafts, signed records and signer, when the page already read them.
 * - `collects`: whether "Charge now" / "Put the price myself" are offered (default: when there is somewhere to open the bill).
 * - `onAfter`: after finishing with "Charge now" or "Put the price myself" (default: `onOpenVisit`).
 * - `onEarlierDay`: left open since an earlier day: "Sí vino" instead (default: `onOpenVisit`).
 */
@Composable
fun SvcFinishSheet(
    target: SvcFinishTarget,
    extraBody: Map<String, Any?> = emptyMap(),
    onOpenReport: (() -> Unit)? = null,
    onOpenVisit: (() -> Unit)? = null,
    onFinished: () -> Unit,
    prepare: (suspend () -> Map<String, Any?>)? = null,
    invoice: SchedInvoice? = null,
    known: SvcFinishKnown? = null,
    collects: Boolean? = null,
    onAfter: ((SchedChargeMode) -> Unit)? = null,
    onEarlierDay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val mode = remember { mutableStateOf(SchedChargeMode.desk) }
    val reason = remember { mutableStateOf("") }
    val sign = remember { mutableStateOf(true) }
    val comeBack = remember { mutableStateOf(SchedComeBack()) }
    var availability by remember { mutableStateOf<SchedAvailability?>(null) }
    var closures by remember { mutableStateOf<List<SchedClosure>>(emptyList()) }
    var drafts by remember { mutableIntStateOf(0) }
    var signed by remember { mutableIntStateOf(0) }
    var signerId by remember { mutableStateOf<String?>(null) }
    var signerName by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val form = remember { FormState() }

    val ready: Boolean? = invoice?.let { bill -> !(bill.lineItems ?: emptyList()).isEmpty() && !(bill.lineItems ?: emptyList()).any { it.needsPrice } }
    val modes = SchedChargeMode.modes(
        pay = workspace.can(Permission.billingRecordPayment), price = workspace.can(Permission.billingEditInvoice),
        waive = workspace.can(Permission.billingWaiveVisit), collects = collects ?: (onOpenVisit != null), billReady = ready,
    )
    val canSign = signerName != null
    val willSign = canSign && sign.value && drafts > 0
    val assignedToOther = signerId != null && target.assignedId != null && target.assignedId != signerId
    val asksReturn = SchedComeBack.asks(target.serviceCategory)
    val clinical = target.serviceCategory == null || target.serviceCategory == "vet" || target.serviceCategory == "emergency"
    val reportVisit = SvcReportText.kind(target.serviceCategory) != null
    val whatSigns = if (drafts == 1) L("the note") else LF("the note and %d records", drafts - 1)
    val reach = if (target.familyOnApp) LF("%@ will see it in PawMiaw.", target.clientName) else L("It stays in the chart.")
    val reminder = target.assignedName?.let { LF("You can finish now: it stays in %@’s “To complete” until it’s done, by the end of today.", it) }
        ?: L("You can finish now: it stays in “To complete” until it’s done, by the end of today.")

    /** What is written on the visit (to sign), who is finishing (when they can sign), and the place's open days. */
    suspend fun setUp() {
        if (known != null) {
            drafts = known.drafts
            signed = known.signed
            signerId = known.signer?.first
            signerName = known.signer?.second
        } else {
            val id = target.id
            val canApprove = workspace.can(Permission.recordsApprove)
            val page: Page<SchedRecord>? = try {
                API.get("/provider-records", mapOf("limit" to "100", "offset" to "0", "appointmentId" to id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val records = page?.items ?: emptyList()
            drafts = records.count { it.isDraft && it.hasWords }
            signed = records.count { it.isSigned }
            if (canApprove && drafts > 0) {
                svcLoadMembers(workspace).firstOrNull { it.isCurrentMember == true }?.let { me ->
                    signerId = me.id
                    signerName = me.shownName
                }
            }
        }
        if (asksReturn && availability == null) {
            val place = target.locationId ?: workspace.locationId
            availability = SchedData.availability(locationId = target.locationId)
            closures = SchedData.closures(locationId = place)
        }
    }

    fun submit() {
        if (!form.check(listOf("noChargeReason" to SchedChargeChoice.missingReason(mode.value, reason.value)))) return
        val body = mode.value.body(reason.value).toMutableMap()
        if (willSign) body["sign"] = true
        if (asksReturn) {
            comeBack.value.input(category = target.serviceCategory, availability = availability, closures = closures, familyOnApp = target.familyOnApp)?.let {
                body["comeBack"] = it.value
            }
        }
        for ((key, value) in extraBody) body[key] = value
        val chosen = mode.value
        val signs = willSign
        working = true
        scope.launch {
            try {
                prepare?.let { ask -> for ((key, value) in ask()) body[key] = value }
                val result: SchedMoveResponse = SvcAPI.send("POST", "/provider-appointments/${target.id}/finish", body = body)
                Toast.show((if (signs) L("Visit finished and signed.") else L("Visit finished.")) + " " + chosen.outcome(result.bill))
                announceDataChanged()
                dismiss()
                onFinished()
                if (chosen == SchedChargeMode.collect || chosen == SchedChargeMode.price) {
                    if (onAfter != null) onAfter(chosen) else onOpenVisit?.invoke()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (svcCode(e)) {
                    "reason_required" -> form.check(listOf("noChargeReason" to L("Write why it is finished without charging.")))
                    "no_charge_paid" -> form.check(listOf("noChargeReason" to L("A payment is already on this visit’s bill: give it back before finishing without charging.")))
                    "no_charge_forbidden" -> form.check(listOf("noChargeReason" to L("You can’t finish a visit without charging. Send it to the desk instead.")))
                    "visit_from_earlier_day" -> {
                        Toast.error(svcMessage(e))
                        if (onEarlierDay != null) onEarlierDay() else {
                            dismiss()
                            onOpenVisit?.invoke()
                        }
                    }
                    else -> form.apply(svcAPIError(e), known = mapOf("noChargeReason" to "noChargeReason"))
                }
            } finally {
                working = false
            }
        }
    }

    /** Signing is the moment the visit becomes the record: a switch, and the signature itself under it. */
    @Composable
    fun Signature() {
        val name = signerName
        if (name != null) {
            if (assignedToOther) {
                val vet = target.assignedName ?: L("the assigned vet")
                ChoiceCards(
                    options = listOf(
                        Triple(true, L("Sign it myself"), LF("Signed in your name (%@), not %@’s. %@", name, vet, reach)),
                        Triple(false, LF("Leave it for %@", vet), L("It waits in their “To sign” and they get a reminder.")),
                    ),
                    selection = sign,
                )
            } else {
                ToggleRow(
                    title = LF("Sign %@ when finishing", whatSigns),
                    subtitle = if (sign.value) "${L("Your signature confirms what is written.")} $reach ${L("Any change afterwards is marked as a correction.")}" else L("It stays in your “To sign”, with a reminder."),
                    isOn = sign,
                )
                if (sign.value) {
                    val rule = Theme.inkStrong.copy(alpha = 0.25f)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                val h = 1.5.dp.toPx()
                                drawRect(rule, topLeft = Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
                            }
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        // The name takes the room the date leaves (8 between them), shrinking only when it must.
                        FittedText(name, Modifier.alignBy(LastBaseline).weight(1f).padding(end = 8.dp), style = signatureScript(30), color = Theme.inkStrong, minimumScaleFactor = 0.6f)
                        Text(DateText.date(Instant.now()), Modifier.alignBy(LastBaseline), style = Theme.captionFont, color = Theme.inkSoft)
                    }
                }
            }
        } else {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = LF("%@ stays to sign", svcCapitalized(whatSigns)),
                message = target.assignedName?.let { LF("We let %@ know; the family sees it once it’s signed.", it) } ?: L("A vet signs it; the family sees it once it’s signed."),
            )
        }
    }

    ProviderModal(
        title = L("Finish the visit"),
        subtitle = "${target.petName} · ${target.serviceLabel}",
        systemImage = "badge-check",
        accent = ModalAccent.lime,
        isDirty = reason.value.isNotEmpty() || comeBack.value.touched,
        form = form,
        primaryTitle = mode.value.actionTitle(record = false, signs = willSign),
        isWorking = working,
        cancelTitle = L("Back"),
        primaryIcon = when (mode.value) {
            SchedChargeMode.collect -> "hand-coins"
            SchedChargeMode.price -> "tag"
            else -> "badge-check"
        },
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        SchedChargeChoice(modes = modes, mode = mode, reason = reason, invoice = invoice, form = form)
        if (drafts > 0) {
            ModalSection(title = L("Signature")) { Signature() }
        } else if (reportVisit && target.reportMissing.isNotEmpty()) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = LF("The report is missing %@", SvcReportText.fieldsText(target.reportMissing)),
                message = reminder,
                actionTitle = if (onOpenReport == null) null else L("Fill it in now"),
            ) {
                dismiss()
                onOpenReport?.invoke()
            }
        } else if (clinical && drafts + signed == 0) {
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("The visit’s note isn’t written yet"), message = reminder)
        } else if (signed == 0 && !reportVisit && !clinical) {
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("No note written yet"), message = L("You can finish now: it stays in “To complete” until it’s done, by the end of today."))
        }
        if (asksReturn) {
            // Last, once the visit itself is settled: when this pet should come back.
            ModalSection(title = L("When does it come back?")) {
                SchedComeBackFields(
                    value = comeBack, category = target.serviceCategory, availability = availability, closures = closures,
                    familyOnApp = target.familyOnApp, clientName = target.clientName, alsoFor = target.together,
                )
            }
        }
    }
    LaunchedEffect(Unit) { setUp() }
}

// MARK: - The report

data class SvcReportPet(
    val id: String,
    val name: String,
)

class SvcReportTarget(
    val subtitle: String,
    val pets: List<SvcReportPet>,
    val canWrite: Boolean,
) {
    val id: UUID = UUID.randomUUID()
}

/**
 * The visit's report, written from the list: the same form the visit page has.
 * A family's pets booked together each have their own report. A groom starts
 * from what the owner asked for and says where it differs (lib/groom-request.ts).
 */
@Composable
fun SvcReportSheet(target: SvcReportTarget, onSaved: () -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val open = rememberOpenURL()
    var chosen by remember { mutableStateOf(target.pets.firstOrNull()?.id ?: "") }
    var appointment by remember { mutableStateOf<SvcAppointment?>(null) }
    var report by remember { mutableStateOf<SvcReport?>(null) }
    var original by remember { mutableStateOf<SvcReport?>(null) }
    /** The report as the server has it (null: none yet). */
    var saved by remember { mutableStateOf<SvcReport?>(null) }
    var groomView by remember { mutableStateOf<SvcGroomRequestView?>(null) }
    var prefilled by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val dirty = report != null && report != original
    val editable = appointment?.let { target.canWrite && listOf("in_progress", "completed").contains(it.status) } ?: false

    suspend fun loadGroomView(appointmentId: String) {
        val view: SvcGroomRequestView? = try {
            API.get("/provider-appointments/$appointmentId/groom-request")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (view != null) groomView = view
    }

    suspend fun load() {
        if (chosen.isEmpty()) return
        loadError = null
        appointment = null
        groomView = null
        prefilled = false
        try {
            val loaded: SvcAppointment = API.get("/provider-appointments/$chosen")
            var start = loaded.report ?: loaded.reportKind?.let { SvcReport(kind = it) }
            saved = loaded.report
            if (loaded.reportKind == "groom") {
                loadGroomView(loaded.id)
                // No report yet: it starts from what was asked (saved only once someone saves it).
                val canWrite = target.canWrite && listOf("in_progress", "completed").contains(loaded.status)
                val prefill = groomView?.prefill
                if (loaded.report == null && canWrite && prefill != null) {
                    start = prefill.copy(kind = "groom")
                    prefilled = true
                }
            }
            report = start
            original = start
            appointment = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = errorText(e)
        }
    }

    suspend fun save(close: Boolean): Boolean {
        val current = report ?: return false
        val visit = appointment ?: return false
        saving = true
        try {
            API.putJson<SvcIgnored, SvcReport>("/provider-appointments/${visit.id}/report", current)
            original = current
            saved = current
            Toast.show(L("Report saved."))
            announceDataChanged()
            onSaved()
            if (close) dismiss()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(e)
            return false
        } finally {
            saving = false
        }
    }

    /** The report as a WhatsApp message, for a family that isn't on PawMiaw. */
    fun message(appointment: SvcAppointment, report: SvcReport): String {
        val lines = mutableListOf(LF("Hi %@, here is %@’s report from today:", NameText.firstName(appointment.clientName), appointment.petName ?: ""), "")
        lines += SvcReportText.rows(report).map { "• ${it.first}: ${it.second}" }
        report.note.nonEmpty?.let { lines += listOf("", it) }
        return lines.joinToString("\n")
    }

    /** Whether the family gets it in the app, or how to send it to one that isn't on PawMiaw. */
    @Composable
    fun Reach(appointment: SvcAppointment) {
        val name = NameText.firstName(appointment.clientName)
        val current = report
        if (appointment.clientHouseholdId != null) {
            Text(L("The family sees this when the visit ends."), style = Theme.text(12), color = Theme.inkSoft)
        } else if (appointment.status == "completed" && current != null && SvcReportText.hasContent(current)) {
            val link = ContactLinks.whatsApp(appointment.clientPhone, text = message(appointment, current))
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = LF("%@ isn’t on PawMiaw", name),
                message = L("The report won’t reach them in the app — send it by WhatsApp.") + (if (link == null) " " + L("Add a phone number to message them by WhatsApp.") else ""),
                actionTitle = if (link == null) null else L("WhatsApp"),
            ) {
                if (link != null) open(link)
            }
        } else {
            Text(LF("%@ isn’t on PawMiaw — you can send the report by WhatsApp when the visit ends.", name), style = Theme.text(12), color = Theme.inkSoft)
        }
    }

    ProviderModal(
        title = L("Report"),
        subtitle = target.subtitle,
        systemImage = "clipboard-pen",
        accent = ModalAccent.aqua,
        isDirty = dirty,
        primaryTitle = if (editable) L("Save report") else null,
        isWorking = saving,
        cancelTitle = if (editable) L("Cancel") else L("Close"),
        onPrimary = if (editable) ({ scope.launch { save(close = true) } }) else null,
        modifier = modifier,
    ) {
        if (target.pets.size > 1) {
            // A family's pets booked together: each has its own report.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                target.pets.forEach { pet ->
                    PetChip(
                        name = pet.name,
                        highlighted = chosen == pet.id,
                        modifier = Modifier
                            .semantics { selected = chosen == pet.id }
                            .plainButton {
                                scope.launch {
                                    if (dirty && !save(close = false)) return@launch
                                    chosen = pet.id
                                }
                            },
                    )
                }
            }
        }
        val visit = appointment
        val kind = visit?.reportKind
        if (visit != null && kind != null && report != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("clipboard-check", 20.dp, tint = Theme.blue)
                Text(SvcReportText.title(kind, category = visit.serviceCategory), style = Theme.heading(18), color = Theme.inkStrong)
            }
            if (!listOf("in_progress", "completed").contains(visit.status)) {
                Text(L("The report opens when the visit starts."), style = Theme.text(14), color = Theme.inkSoft)
            }
            if (prefilled && saved == null && report?.kind == "groom") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.blue, 10.0), RoundedCornerShape(Theme.chipRadius))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("sparkles", 14.dp, tint = Theme.blue)
                    Text(L("Filled in from what the owner asked for"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong)
                }
            }
            val view = groomView
            SvcReportForm(
                report = stateOf({ report ?: SvcReport(kind = kind) }) { report = it },
                category = visit.serviceCategory,
                appointmentId = visit.id,
                editable = editable,
                wanted = if (view?.hasRequest == true) view.wanted else null,
                startedAt = visit.startedAt,
            )
            val current = report
            if (view != null && current != null && current.kind == "groom" && editable) {
                SvcUsualStyleOffer(
                    view = view,
                    report = current,
                    unsaved = current != saved,
                    saveFirst = { save(close = false) },
                    onSaved = { loadGroomView(visit.id) },
                )
            }
            Reach(visit)
        } else if (appointment != null) {
            EmptyState(systemImage = "clipboard-list", title = L("This visit has no report."))
        } else if (loadError != null) {
            SvcLoadError(message = L("The report couldn’t be loaded."), retry = { load() })
        } else {
            LoadingState()
        }
    }
    LaunchedEffect(chosen) { load() }
}

/** The report's fields for a groom, a walk (or a sitter's visit) or a class. */
@Composable
fun SvcReportForm(
    report: MutableState<SvcReport>,
    category: String?,
    appointmentId: String,
    editable: Boolean,
    /** A groom: what the owner asked for (the report says where it differs). */
    wanted: SvcGroomWanted? = null,
    /** When the visit started: a walk's time can come from the clock. */
    startedAt: String? = null,
    modifier: Modifier = Modifier,
) {
    val skill = remember { mutableStateOf("") }
    val current = report.value
    val required = SvcReportText.required(category, current)

    fun update(change: SvcReport.() -> SvcReport) {
        report.value = report.value.change()
    }

    fun toggled(list: List<String>, value: String): List<String> = if (list.contains(value)) list.filter { it != value } else list + value

    /** A text of the report: empty is none. */
    fun text(get: (SvcReport) -> String?, set: SvcReport.(String?) -> SvcReport): MutableState<String> =
        stateOf({ get(report.value) ?: "" }) { value -> report.value = report.value.set(value.ifEmpty { null }) }

    /** A report field (web `Field`): its name 12 bold over it, the coral mark on the ones the report must have. */
    @Composable
    fun Field(key: String?, label: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                if (key != null && required.contains(key)) Text("*", style = Theme.text(12, FontWeight.Bold), color = Theme.coral)
            }
            content()
        }
    }

    @Composable
    fun Chips(options: List<String>, selected: List<String>, worry: String? = null, toggle: (String) -> Unit) {
        Disabled(!editable) {
            FlowLayout(spacing = 6.dp) {
                options.forEach { option ->
                    SvcChoiceChip(title = SvcReportText.option(option), isSelected = selected.contains(option), danger = option == worry, action = { toggle(option) })
                }
            }
        }
    }

    @Composable
    fun YesNo(value: Boolean?, yes: String, no: String, set: (Boolean?) -> Unit) {
        Disabled(!editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SvcChoiceChip(title = yes, isSelected = value == true, action = { set(if (value == true) null else true) })
                SvcChoiceChip(title = no, isSelected = value == false, action = { set(if (value == false) null else false) })
            }
        }
    }

    @Composable
    fun BehaviourField() {
        Field("behaviour", L("Behaviour")) {
            Chips(SvcReportText.behaviours, current.behaviour?.let { listOf(it) } ?: emptyList()) { value ->
                update { copy(behaviour = if (behaviour == value) null else value) }
            }
        }
    }

    @Composable
    fun Photo(label: String, url: String?, modifier: Modifier = Modifier, onChange: (String?) -> Unit) {
        SvcPhotoSlot(label = label, url = url, disabled = !editable, appointmentId = appointmentId, onChange = onChange, modifier = modifier)
    }

    @Composable
    fun GroomFields() {
        wanted?.let { SvcGroomAskedLine(wanted = it) }
        Field("services", L("Services")) {
            Chips(SvcReportText.groomServices, current.services) { value -> update { copy(services = toggled(services, value)) } }
        }
        Field("style", L("Style")) {
            Disabled(!editable) {
                FlowLayout(spacing = 6.dp) {
                    SvcReportText.styles.forEach { style ->
                        SvcChoiceChip(title = SvcReportText.style(style), isSelected = current.styleKind == style, action = {
                            update { copy(styleKind = if (styleKind == style) null else style) }
                        })
                    }
                }
            }
            FieldChrome {
                PlainTextField(text({ it.style }) { copy(style = it) }, L("Puppy cut, round ears…"), Modifier.weight(1f), enabled = editable)
            }
        }
        Field(null, L("Report · Length")) {
            FieldChrome {
                PlainTextField(text({ it.length }) { copy(length = it) }, L("Blade number or cm, e.g. #7 or 2 cm"), Modifier.weight(1f), enabled = editable)
            }
        }
        // Where the report differs from what was asked, live as it is written.
        val differences = SvcGroomAsk.compare(wanted, current)
        if (differences != null && differences.any) {
            SvcGroomDifferenceBox(differences = differences, note = text({ it.differenceNote }) { copy(differenceNote = it) }, editable = editable)
        }
        Field(null, L("Coat")) {
            Chips(SvcReportText.coats, current.coat?.let { listOf(it) } ?: emptyList()) { value -> update { copy(coat = if (coat == value) null else value) } }
        }
        Field(null, L("Skin")) {
            Chips(SvcReportText.skins, current.skin) { value ->
                update {
                    if (value == "ok") copy(skin = if (skin.contains("ok")) emptyList() else listOf("ok"))
                    else copy(skin = toggled(skin.filter { it != "ok" }, value))
                }
            }
        }
        BehaviourField()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Photo(L("Before"), current.beforePhotoUrl, Modifier.weight(1f)) { url -> update { copy(beforePhotoUrl = url) } }
            Photo(L("After"), current.afterPhotoUrl, Modifier.weight(1f)) { url -> update { copy(afterPhotoUrl = url) } }
        }
    }

    @Composable
    fun WalkFields() {
        // Drawn by the server when the walk was finished: read here, never edited.
        current.route?.let { WalkRouteView(route = it) }
        // The distance as typed ("2." while writing 2.5), read into the report on each change.
        val distance = remember { mutableStateOf(current.distanceKm?.let { svcNumber(it) } ?: "") }
        LaunchedEffect(current.distanceKm) {
            val typed = distance.value.replace(",", ".").toDoubleOrNull()?.let { min(it, 100.0) }
            if (typed != current.distanceKm) distance.value = current.distanceKm?.let { svcNumber(it) } ?: ""
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                Field(null, L("Time")) {
                    FieldChrome {
                        PlainTextField(
                            stateOf({ current.minutes?.toString() ?: "" }) { value ->
                                update { copy(minutes = value.filter { it.isDigit() }.toIntOrNull()?.let { min(it, 1440) }) }
                            },
                            if (startedAt != null) L("From the clock") else "min",
                            Modifier.weight(1f),
                            keyboard = KeyboardType.Number,
                            enabled = editable,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Field(null, L("Distance")) {
                    FieldChrome {
                        PlainTextField(
                            distance,
                            "km",
                            Modifier.weight(1f),
                            keyboard = KeyboardType.Decimal,
                            enabled = editable,
                            onChange = { value -> update { copy(distanceKm = value.replace(",", ".").toDoubleOrNull()?.let { min(it, 100.0) }) } },
                        )
                    }
                }
            }
        }
        Field("pee", L("Pee")) { YesNo(current.pee, yes = L("Yes"), no = L("No")) { value -> update { copy(pee = value) } } }
        Field("water", L("Water")) { YesNo(current.water, yes = L("Yes"), no = L("No")) { value -> update { copy(water = value) } } }
        Field(null, L("Poop")) {
            Chips(SvcReportText.stool, current.poop?.let { listOf(it) } ?: emptyList(), worry = "diarrhea") { value -> update { copy(poop = if (poop == value) null else value) } }
        }
        Field("ate", L("Food")) {
            Chips(SvcReportText.eating, current.ate?.let { listOf(it) } ?: emptyList(), worry = "none") { value -> update { copy(ate = if (ate == value) null else value) } }
        }
        Field(null, L("Medicine")) { YesNo(current.medsGiven, yes = L("Given"), no = L("Not given")) { value -> update { copy(medsGiven = value) } } }
        BehaviourField()
        Photo(L("Photo"), current.photoUrl) { url -> update { copy(photoUrl = url) } }
    }

    fun addSkill() {
        val value = skill.value.nonEmpty ?: return
        if (report.value.skills.contains(value) || report.value.skills.size >= 20) return
        update { copy(skills = skills + value) }
        skill.value = ""
    }

    @Composable
    fun TrainingFields() {
        Field("skills", L("Practised")) {
            if (current.skills.isNotEmpty()) {
                FlowLayout(spacing = 6.dp) {
                    current.skills.forEach { entry ->
                        Row(
                            Modifier
                                .background(Theme.surfaceMuted, RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                            if (editable) {
                                Box(Modifier.plainButton(label = L("Remove")) { update { copy(skills = skills.filter { it != entry }) } }) {
                                    Glyph("x", 14.dp, tint = Theme.inkStrong)
                                }
                            }
                        }
                    }
                }
            }
            if (editable) {
                FieldChrome {
                    PlainTextField(skill, L("Sit, stay, loose leash…"), Modifier.weight(1f), imeAction = ImeAction.Done, onSubmit = { addSkill() })
                }
            }
        }
        Field("progress", L("Progress")) {
            Chips(SvcReportText.progress, current.progress?.let { listOf(it) } ?: emptyList()) { value -> update { copy(progress = if (progress == value) null else value) } }
        }
        BehaviourField()
        Field(null, L("Homework")) {
            FieldChrome(multiline = true) {
                PlainTextField(text({ it.homework }) { copy(homework = it) }, L("Five minutes of “stay” twice a day…"), Modifier.weight(1f), lines = 2..8, enabled = editable)
            }
        }
        Photo(L("Photo"), current.photoUrl) { url -> update { copy(photoUrl = url) } }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (current.kind) {
            "groom" -> GroomFields()
            "walk" -> WalkFields()
            else -> TrainingFields()
        }
        // Web: every part under a 12 bold name, none marked optional.
        Field(null, L("Note")) {
            FieldChrome(multiline = true) {
                PlainTextField(text({ it.note }) { copy(note = it) }, L("Anything the family should know…"), Modifier.weight(1f), lines = 2..8, enabled = editable)
            }
        }
    }
}

/**
 * A report photo: the shared PhotoField, uploaded for this visit as soon as it is chosen
 * (the server keeps it under the visit) and taken off at once.
 */
@Composable
fun SvcPhotoSlot(
    label: String,
    url: String?,
    disabled: Boolean,
    appointmentId: String,
    modifier: Modifier = Modifier,
    onChange: (String?) -> Unit,
) {
    val draft = remember { mutableStateOf(PhotoDraft()) }
    var uploading by remember { mutableStateOf(false) }

    LaunchedEffect(draft.value) {
        val next = draft.value
        val data = next.data
        if (data != null) {
            uploading = true
            try {
                val result: SvcUploadResult = ImageUpload.upload(data, to = "/provider-appointments/$appointmentId/report-photo")
                onChange(result.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                uploading = false
                draft.value = PhotoDraft()
            }
        } else if (next.removed) {
            draft.value = PhotoDraft()
            onChange(null)
        }
    }

    Box(modifier) {
        Disabled(disabled || uploading) {
            PhotoField(label = label, currentURL = url, draft = draft, allowsRemove = !disabled)
        }
        if (uploading) {
            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp), color = Theme.blue, strokeWidth = 2.dp)
        }
    }
}

// MARK: - On my way

data class SvcOnMyWayTarget(
    val id: String,
    val appointmentId: String,
    val client: SvcClientRef,
    val petNames: String,
)

/** "On my way": tell the family in PawMiaw, or open WhatsApp with the message ready. The visit doesn't move. */
@Composable
fun SvcOnMyWaySheet(target: SvcOnMyWayTarget, onSent: () -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val channel = remember { mutableStateOf("app") }
    var minutes by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }

    val canApp = target.client.onPawMiaw == true
    val canWhatsApp = target.client.phone.nonEmpty != null
    val available = if (channel.value == "app") canApp else canWhatsApp
    val client = target.client.shortName

    val channelOptions: List<Triple<String, String, String?>> = run {
        val app = if (canApp) LF("%@ gets a notice in PawMiaw.", client) else LF("%@ is not on PawMiaw.", client)
        val whatsapp = if (canWhatsApp) L("WhatsApp opens with the message ready. You send it there.") else LF("%@ has no phone for WhatsApp.", client)
        listOf(Triple("app", L("In PawMiaw"), app), Triple("whatsapp", L("By WhatsApp"), whatsapp))
    }

    val minuteOptions: List<Pair<Int, String>> = listOf(0 to L("Don’t say when")) + listOf(5, 10, 15, 20, 30).map { it to LF("%d min", it) }

    fun send() {
        working = true
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>("channel" to channel.value, "locale" to svcLocale)
                if (minutes > 0) body["minutes"] = minutes
                val result: SvcOnMyWayResult = SvcAPI.send("POST", "/provider-appointments/${target.appointmentId}/home-on-my-way", body = body)
                val link = result.link
                if (channel.value == "whatsapp" && link != null) {
                    svcOpen(link)
                    Toast.show(L("WhatsApp opened with the message ready."))
                } else {
                    Toast.show(LF("%@ was told you’re on the way.", client))
                }
                announceDataChanged()
                onSent()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(svcMessage(e, codes = SvcRefusals.homeVisits))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("On my way"),
        subtitle = LF("Tell %@ you’re on the way", client),
        systemImage = "navigation",
        accent = ModalAccent.aqua,
        primaryTitle = if (channel.value == "whatsapp") L("Open WhatsApp") else L("Send"),
        isWorking = working,
        primaryIcon = if (channel.value == "whatsapp") "message-circle" else "send",
        primaryDisabled = !available,
        onPrimary = { send() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ContextPills(client = target.client.name, pets = target.petNames.split(", ").filter { it.isNotEmpty() && it != "—" })
        ModalSection(title = L("How to tell them")) {
            ChoiceCards(options = channelOptions, selection = channel)
        }
        ModalSection(title = L("Arriving in about")) {
            FlowLayout(spacing = 8.dp) {
                minuteOptions.forEach { (value, title) ->
                    SvcChoiceChip(title = title, isSelected = minutes == value, height = 48.dp, action = { minutes = value })
                }
            }
        }
    }
    LaunchedEffect(Unit) { channel.value = if (canApp) "app" else "whatsapp" }
}

// MARK: - Didn't come / take the pet out

data class SvcMissedTarget(
    val id: String,
    val petName: String,
    val clientName: String,
    val subtitle: String,
    /** "no_show" or "cancel". */
    val mode: String,
)
