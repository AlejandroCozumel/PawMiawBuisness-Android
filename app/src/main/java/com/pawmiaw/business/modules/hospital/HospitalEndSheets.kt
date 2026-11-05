package com.pawmiaw.business.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

//
//  HospitalEndSheets.kt (iOS HospitalEndSheets.swift)
//
//  The end of a hospitalization (web hospital-end-sheets.tsx). Discharge
//  waits for the sheet: every dose whose time came is marked first
//  (`openDoses`, with "Open the sheet"); it writes the summary, the recheck
//  and the medicines to give at home, and sends the running bill to the
//  front desk unless kept a draft (docs/charging-flow.md). A transfer or a
//  death does not wait. Euthanasia needs the family's signed consent, or an
//  owner's or admin's reason.
//

// MARK: - Discharge

private data class HospTakeHomeDraft(
    val id: String = UUID.randomUUID().toString(),
    val item: HospCatalogRef? = null,
    val name: String = "",
    val dose: String = "",
    val unit: String = "",
    val route: String = "oral",
    val everyHours: String = "12",
    val days: String = "7",
    val quantity: String = "",
    val instructions: String = "",
    val addToBill: Boolean = true,
)

private class HospDischargeDone(
    val prescription: HospPrescriptionResult?,
    val records: Int,
    val cabinetItems: Int,
    val billAtDesk: Boolean,
)

@Composable
fun HospDischargeSheet(
    model: HospDetailModel,
    detail: HospDetail,
    onGoToSheet: (String) -> Unit,
    onPDF: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val summary = remember { mutableStateOf("") }
    val diagnosis = remember { mutableStateOf("") }
    val instructions = remember { mutableStateOf("") }
    val followUpAt = remember { mutableStateOf<Instant?>(null) }
    val followUpNote = remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf<HospTakeHomeDraft>() }
    var prescribe by remember { mutableStateOf(false) }
    var keepDraft by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<HospDischargeDone?>(null) }

    val onApp = detail.client?.onPawMiaw == true
    val openDoses = detail.openDoses ?: emptyList()
    val unpriced = (detail.bill?.lines ?: emptyList()).any { it.needsPrice }

    fun submit() {
        if (openDoses.isNotEmpty()) {
            Toast.error(L("Some doses on the sheet aren’t marked yet. Mark each as given or not given before the patient leaves."))
            return
        }
        if (!form.check(listOf("summary" to if (summary.value.nonEmpty == null) L("Write the discharge summary.") else null))) return
        val takeHome = mutableListOf<Map<String, Any?>>()
        for (row in rows) {
            val name = row.name.nonEmpty ?: row.item?.name ?: ""
            val dose = HospFmt.read(row.dose)
            val every = row.everyHours.nonEmpty?.let { it.toIntOrNull() ?: -1 }
            val days = row.days.nonEmpty?.let { it.toIntOrNull() ?: -1 }
            val quantity = row.quantity.nonEmpty?.let { it.toIntOrNull() ?: -1 }
            val problem: String? = when {
                name.isEmpty() -> L("Name each medicine to take home.")
                dose?.let { it.isNaN() || it <= 0 } == true -> L("Enter a dose greater than zero.")
                every?.let { it !in 1..168 } == true -> L("Every 1 to 168 hours.")
                days?.let { it !in 1..365 } == true -> L("For 1 to 365 days.")
                quantity?.let { it !in 0..1000 } == true -> L("A quantity from 0 to 1000.")
                else -> null
            }
            if (problem != null) {
                Toast.error(problem)
                return
            }
            takeHome.add(mapOf(
                "catalogItemId" to row.item?.id, "name" to name, "doseAmount" to dose, "doseUnit" to row.unit.nonEmpty, "route" to row.route.nonEmpty,
                "everyHours" to every, "durationDays" to days, "quantity" to quantity, "instructions" to row.instructions.nonEmpty,
                "addToBill" to (row.item != null && row.addToBill),
            ))
        }
        // The recheck at 9:00 of its day on the hospital's clock.
        val followUp: String? = followUpAt.value?.let { day ->
            val zone = detail.zone
            DateText.iso(day.atZone(zone).toLocalDate().atTime(LocalTime.of(9, 0)).atZone(zone).toInstant())
        }
        saving = true
        scope.launch {
            try {
                val answer = model.write("/discharge", body = mapOf(
                    "summary" to summary.value.nonEmpty,
                    "diagnosis" to diagnosis.value.nonEmpty,
                    "instructions" to instructions.value.nonEmpty,
                    "followUpAt" to followUp,
                    "followUpNote" to if (followUp != null) followUpNote.value.nonEmpty else null,
                    "takeHome" to takeHome,
                    "prescribe" to (prescribe && takeHome.isNotEmpty()),
                    "keepDraft" to keepDraft,
                ))
                Toast.show(if (onApp) LF("%@ is discharged. The family has been told.", detail.petName) else LF("%@ is discharged.", detail.petName))
                done = HospDischargeDone(
                    prescription = answer.discharge?.prescription, records = answer.discharge?.records ?: 0,
                    cabinetItems = answer.discharge?.cabinetItems ?: 0, billAtDesk = answer.bill?.status == "ready_for_payment",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(hospError(e, "Could not discharge."))
            } finally {
                saving = false
            }
        }
    }

    fun primary() {
        if (done != null) {
            dismiss()
            onPDF()
        } else {
            submit()
        }
    }

    val finished = done
    ProviderModal(
        title = if (finished != null) LF("%@ is going home", detail.petName) else LF("Discharge %@", detail.petName),
        subtitle = if (finished != null) L("What the discharge created")
        else if (onApp) L("The family reads the summary and the medicines in their app.") else L("The family isn’t on PawMiaw: print the summary or send them the PDF."),
        systemImage = "heart-handshake",
        accent = ModalAccent.lime,
        isDirty = finished == null && (summary.value.isNotEmpty() || diagnosis.value.isNotEmpty() || instructions.value.isNotEmpty() || rows.isNotEmpty()),
        form = form,
        primaryTitle = if (finished != null) L("Discharge PDF") else L("Discharge"),
        isWorking = saving,
        cancelTitle = if (finished != null) L("Close") else L("Cancel"),
        primaryIcon = if (finished != null) "file-down" else "heart-handshake",
        onPrimary = { primary() },
        modifier = modifier,
    ) {
        if (finished != null) {
            HospDischargeResult(finished, onApp)
        } else {
            if (openDoses.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoticeCard(
                        tone = NoticeCard.Tone.problem,
                        title = if (openDoses.size == 1) L("1 dose on the sheet isn’t marked yet") else LF("%d doses on the sheet aren’t marked yet", openDoses.size),
                        message = (listOf(LF("Mark each as given, or not given with the reason, before %@ goes home.", detail.petName)) +
                            openDoses.take(6).map { "· ${it.label ?: ""} — ${HospFmt.dateTime(it.scheduledFor, detail.zone)}" } +
                            (if (openDoses.size > 6) listOf(LF("and %d more", openDoses.size - 6)) else emptyList())).joinToString("\n"),
                        actionTitle = L("Open the sheet"),
                        action = {
                            val day = HospFmt.dayKey(openDoses[0].scheduledFor, detail.zone) ?: detail.sheet?.today ?: ""
                            dismiss()
                            onGoToSheet(day)
                        },
                    )
                }
            }
            ModalSection(title = L("For the family")) {
                PMTextField(
                    L("Summary"), text = summary, key = "summary", form = form,
                    prompt = LF("e.g. %@ came in dehydrated with vomiting. After two days of fluids and anti-nausea medicine she is eating and drinking well.", detail.petName),
                    multiline = true, required = true,
                )
                PMTextField(L("Diagnosis"), text = diagnosis, prompt = L("e.g. Acute gastroenteritis"))
                val device = ZoneId.systemDefault()
                OptionalDateField(
                    label = L("Recheck on"), date = followUpAt, placeholder = L("No recheck"), zone = detail.zone,
                    min = LocalDate.now(device).atStartOfDay(device).toInstant(), components = setOf(DatePickerComponent.date),
                    defaultDate = Instant.now().plusSeconds(7 * 86_400L), optional = false,
                )
                if (followUpAt.value != null) {
                    // Web: a field with only its placeholder, under the day.
                    FieldChrome {
                        PlainTextField(followUpNote, L("What the recheck is for"), Modifier.weight(1f))
                    }
                }
                PMTextField(
                    L("Care at home"), text = instructions,
                    prompt = L("e.g. Small bland meals for 3 days, fresh water, short walks. Call us if the vomiting comes back."),
                    multiline = true,
                )
            }
            ModalSection(
                title = L("Medicines to give at home"),
                subtitle = if (onApp) L("Each one reaches the family’s medicines and cabinet, with its directions.") else L("Each one goes on the chart and the discharge summary, with its directions."),
            ) {
                rows.toList().forEach { row ->
                    key(row.id) {
                        HospTakeHomeRow(
                            row = stateOf(
                                { rows.firstOrNull { it.id == row.id } ?: row },
                                { next -> val index = rows.indexOfFirst { it.id == row.id }; if (index >= 0) rows[index] = next },
                            ),
                        ) { rows.removeAll { it.id == row.id } }
                    }
                }
                PMButton(L("Add a medicine"), systemImage = "plus", variant = ButtonVariant.outline, enabled = rows.size < 20) { rows.add(HospTakeHomeDraft()) }
                if (rows.isNotEmpty()) {
                    // Web: a checkbox row, not a switch.
                    val shape = RoundedCornerShape(Theme.boxRadius)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Theme.borderSoft, shape)
                            .semantics { selected = prescribe }
                            .plainButton { prescribe = !prescribe }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Tick(on = prescribe, modifier = Modifier.padding(top = 2.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(L("Write the prescription"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                            Text(
                                L("Signs the discharge prescription with these medicines. If it can’t, the discharge still goes through."),
                                style = Theme.text(12), color = Theme.inkSoft,
                            )
                        }
                    }
                }
            }
            // Only whoever sees the bill (money level 1) chooses where it goes; a $0 line keeps it a draft.
            if (detail.bill != null) {
                ModalSection(title = L("The bill")) {
                    Disabled(unpriced) {
                        ToggleRow(
                            title = L("Keep it a draft to adjust"),
                            subtitle = if (unpriced) L("Something has no price yet: it stays a draft until it has one.")
                            else if (keepDraft) L("It waits in Invoices › To charge until someone sends it to the front desk.") else L("Off, it goes to the front desk to be charged."),
                            isOn = stateOf({ keepDraft || unpriced }, { keepDraft = it }),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HospDischargeResult(done: HospDischargeDone, onApp: Boolean) {
    NoticeCard(
        tone = NoticeCard.Tone.success,
        title = listOfNotNull(
            if (done.records == 0) null else if (done.records == 1) L("1 medicine is on the chart.") else LF("%d medicines are on the chart.", done.records),
            if (done.cabinetItems == 0) null else if (done.cabinetItems == 1) L("1 is in the family’s cabinet.") else LF("%d are in the family’s cabinet.", done.cabinetItems),
            if (done.billAtDesk) L("Active orders stopped, and the bill is at the front desk to be charged.") else L("Active orders stopped, and the bill stays a draft for the desk."),
        ).joinToString(" "),
    )
    if (!onApp) NoticeCard(tone = NoticeCard.Tone.info, title = L("The family isn’t on PawMiaw: print the summary or send them the PDF."))
    val prescription = done.prescription
    if (prescription != null) {
        if (prescription.ok == true) {
            val folios = (prescription.prescriptions ?: emptyList()).mapNotNull { it.folio.nonEmpty }.joinToString(", ")
            NoticeCard(tone = NoticeCard.Tone.success, title = L("The discharge prescription is signed.") + (if (folios.isEmpty()) "" else " $folios"))
        } else {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = L("The prescription isn’t signed."),
                message = hospRefusalText(code = prescription.code, error = prescription.error, fallback = "The prescription could not be written.") +
                    "\n" + L("The discharge went through. Finish the prescription from the patient’s chart, under Prescriptions."),
            )
        }
    }
}

@Composable
private fun HospTakeHomeRow(row: MutableState<HospTakeHomeDraft>, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    val draft = row.value
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.aqua, 8.0), shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            val item = draft.item
            if (item != null) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name ?: "", Modifier.weight(1f), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    IconCircleButton(systemImage = "x", label = L("Remove item")) { row.value = row.value.copy(item = null) }
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HospCatalogSearch(prompt = L("From the Catalog"), itemType = "product", showsPrice = false) { entry ->
                        row.value = row.value.copy(
                            item = HospCatalogRef(id = entry.id, name = entry.name, itemType = entry.itemType, unitAmount = entry.defaultUnitAmount),
                            name = "",
                        )
                    }
                    FieldChrome {
                        PlainTextField(row.map({ it.name }, { d, v -> d.copy(name = v) }), L("Or write its name"), Modifier.weight(1f))
                    }
                }
            }
            IconCircleButton(systemImage = "trash-2", label = L("Remove medicine"), tint = Theme.destructive, action = onRemove)
        }
        HospGrid((0..5).toList(), minimum = 120.dp, spacing = 8.dp) { cell ->
            when (cell) {
                0 -> HospSmallField(L("Dose"), row.map({ it.dose }, { d, v -> d.copy(dose = v) }), KeyboardType.Decimal)
                1 -> HospSmallField(L("Unit"), row.map({ it.unit }, { d, v -> d.copy(unit = v) }), KeyboardType.Text)
                2 -> MenuField(
                    label = L("Route"),
                    options = listOf("" to "—") + HospWords.homeRoutes.map { it to HospWords.route(it) },
                    selection = row.map({ it.route }, { d, v -> d.copy(route = v) }),
                )
                3 -> HospSmallField(L("Every (hours)"), row.map({ it.everyHours }, { d, v -> d.copy(everyHours = v) }), KeyboardType.Number)
                4 -> HospSmallField(L("For (days)"), row.map({ it.days }, { d, v -> d.copy(days = v) }), KeyboardType.Number)
                else -> HospSmallField(L("Qty"), row.map({ it.quantity }, { d, v -> d.copy(quantity = v) }), KeyboardType.Number)
            }
        }
        FieldChrome {
            PlainTextField(row.map({ it.instructions }, { d, v -> d.copy(instructions = v) }), L("e.g. With food; finish the whole course"), Modifier.weight(1f))
        }
        if (draft.item != null) {
            ToggleRow(title = L("Add to the bill (and take it from stock)"), isOn = row.map({ it.addToBill }, { d, v -> d.copy(addToBill = v) }))
        }
    }
}

@Composable
private fun HospSmallField(label: String, text: MutableState<String>, keyboard: KeyboardType) {
    FormField(label = label) {
        FieldChrome {
            PlainTextField(text, "", Modifier.weight(1f), keyboard = keyboard)
        }
    }
}

// MARK: - Transfer

@Composable
fun HospTransferSheet(model: HospDetailModel, detail: HospDetail, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val destination = remember { mutableStateOf("") }
    val reason = remember { mutableStateOf("") }
    val summary = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    ProviderModal(
        title = LF("Transfer %@", detail.petName),
        subtitle = L("The stay here ends; active orders stop and the bill stays a draft."),
        systemImage = "arrow-right-left",
        accent = ModalAccent.aqua,
        isDirty = destination.value.isNotEmpty() || reason.value.isNotEmpty() || summary.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Transfer"),
        isWorking = saving,
        primaryIcon = "arrow-right-left",
        onPrimary = onPrimary@{
            if (!form.check(listOf("destination" to if (destination.value.nonEmpty == null) L("Say where the patient goes.") else null))) return@onPrimary
            saving = true
            scope.launch {
                try {
                    model.write("/transfer", body = mapOf("destination" to destination.value.nonEmpty, "reason" to reason.value.nonEmpty, "summary" to summary.value.nonEmpty))
                    Toast.show(LF("%@ is transferred to %@.", detail.petName, destination.value.nonEmpty ?: ""))
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Toast.error(hospError(e, "Could not transfer."))
                } finally {
                    saving = false
                }
            }
        },
        modifier = modifier,
    ) {
        PMTextField(L("To"), text = destination, key = "destination", form = form, prompt = L("e.g. Hospital Veterinario del Norte, ICU"), required = true)
        PMTextField(L("Why"), text = reason, prompt = L("e.g. Needs 24-hour intensive care and a CT scan"), multiline = true)
        PMTextField(L("Summary for the receiving team"), text = summary, multiline = true)
    }
}

// MARK: - Death

@Composable
fun HospDeathSheet(model: HospDetailModel, detail: HospDetail, canOverride: Boolean, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val cause = remember { mutableStateOf<String?>("natural") }
    val diedAt = remember { mutableStateOf(Instant.now()) }
    val aftercare = remember { mutableStateOf("") }
    val aftercareNote = remember { mutableStateOf("") }
    val notes = remember { mutableStateOf("") }
    var consent by remember { mutableStateOf<HospConsent?>(null) }
    var needsConsent by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var signing by remember { mutableStateOf<HospSigning?>(null) }
    var overriding by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val euthanasia = cause.value == "euthanasia"
    val signed = consent?.status == "signed"
    val onApp = detail.client?.onPawMiaw == true

    suspend fun fetchConsent(id: String): HospConsent? = try {
        API.get("/provider-consents/$id")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    suspend fun loadConsent() {
        if (!euthanasia) return
        val id = detail.deathConsentId ?: model.detail?.deathConsentId ?: return
        consent = fetchConsent(id)
    }

    suspend fun ask(notify: Boolean) {
        asking = true
        try {
            val answer = HospAPI.askConsent(detail.id, kind = "euthanasia", notify = notify)
            model.load()
            answer.consentId?.let { consent = fetchConsent(it) }
            if (notify) {
                Toast.show(if (answer.notified == true) L("Sent to the family to sign.") else L("Asked. The family isn’t on the app: sign it here with them."))
            } else {
                answer.consentId?.let { signing = HospSigning(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(hospError(e, "Could not ask for the signature."))
        } finally {
            asking = false
        }
    }

    suspend fun submit(override: String?) {
        if (!form.check(listOf("diedAt" to if (diedAt.value.toEpochMilli() - System.currentTimeMillis() > 60_000) L("That time is in the future.") else null))) return
        saving = true
        try {
            val body = mutableMapOf<String, Any?>(
                "cause" to (cause.value ?: "natural"),
                "diedAt" to DateText.iso(diedAt.value),
                "notes" to notes.value.nonEmpty,
                "aftercare" to aftercare.value.nonEmpty,
                "aftercareNote" to aftercareNote.value.nonEmpty,
            )
            if (override != null) {
                body["overrideConsent"] = true
                body["overrideReason"] = override
            }
            model.write("/death", body = body)
            Toast.show(if (onApp) L("Recorded. The family receives a gentle notice.") else L("Recorded."))
            overriding = false
            dismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if ((e as? APIError)?.code == "consent_required") needsConsent = true
            if (override == null) Toast.error(hospError(e, "Could not record it."))
            throw hospRefusal(e, "Could not record it.")
        } finally {
            saving = false
        }
    }

    LaunchedEffect(euthanasia) { loadConsent() }

    ProviderModal(
        title = LF("Record %@’s passing", detail.petName),
        subtitle = if (onApp) L("Take the time you need. The family is told with care, and upcoming reminders and visits are cancelled.")
        else L("Take the time you need. Upcoming reminders and visits are cancelled; the family isn’t on PawMiaw, so tell them yourself."),
        systemImage = "feather",
        accent = ModalAccent.grey,
        isDirty = notes.value.isNotEmpty() || aftercareNote.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Record"),
        isWorking = saving,
        // Web: beside Record in the footer, its words in red.
        secondaryActions = if (euthanasia && !signed && canOverride) {
            listOf(ModalAction(L("Record without the signed consent"), tint = Theme.destructive) { overriding = true })
        } else emptyList(),
        primaryIcon = "feather",
        onPrimary = onPrimary@{
            if (euthanasia && !signed) {
                Toast.error(L("The family’s signed euthanasia consent is needed first."))
                return@onPrimary
            }
            scope.launch {
                try {
                    submit(override = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
        },
        modifier = modifier,
    ) {
        // Web: the "Cause *" part, its choice under the title.
        ModalSection(title = L("Cause"), required = true) {
            PillPicker(
                options = HospWords.causes.map { it to HospWords.deathCause(it) },
                choice = stateOf({ cause.value }, { cause.value = it; needsConsent = false }),
                modifier = Modifier.hospLabel(L("Cause")),
            )
        }
        if (euthanasia) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoticeCard(
                    tone = if (signed) NoticeCard.Tone.success else NoticeCard.Tone.warning,
                    title = when {
                        signed -> L("The family signed the euthanasia consent.")
                        consent?.status == "pending" -> L("Waiting for the family’s signature on the euthanasia consent.")
                        consent?.status == "declined" -> L("The family declined the euthanasia consent.")
                        else -> L("The family’s signed euthanasia consent is needed first.")
                    },
                    message = if (needsConsent && !signed) L("No signed euthanasia consent was found for this hospitalization.") else null,
                )
                if (!signed) {
                    FlowLayout(spacing = 8.dp) {
                        PMButton(L("Sign here"), systemImage = "pen-line", size = ButtonSize.compact, isWorking = asking) {
                            val id = detail.deathConsentId
                            if (consent?.status == "pending" && id != null) signing = HospSigning(id) else scope.launch { ask(false) }
                        }
                        if (onApp) {
                            PMButton(
                                if (consent?.status == "pending") L("Send again") else L("Send to the family"), systemImage = "send",
                                variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = asking,
                            ) { scope.launch { ask(true) } }
                        }
                    }
                }
            }
        }
        DateTimeField(label = L("Time"), date = diedAt, zone = detail.zone, max = Instant.now(), key = "diedAt", form = form, required = true)
        MenuField(
            label = L("Aftercare"),
            options = listOf("" to L("Not decided yet")) + HospWords.aftercares.map { it to HospWords.aftercare(it) },
            selection = aftercare,
        )
        PMTextField(L("Aftercare note"), text = aftercareNote, prompt = L("e.g. Paw print kept for the family"))
        PMTextField(L("Notes for the chart"), text = notes, multiline = true)
    }
    signing?.let { item ->
        AppSheet(onDismissRequest = { signing = null }) {
            ConsentSignSheet(consentId = item.id, successMessage = L("Signed. It is kept with the hospitalization.")) {
                scope.launch {
                    loadConsent()
                    model.load()
                }
            }
        }
    }
    if (overriding) {
        AppSheet(onDismissRequest = { overriding = false }) {
            ConfirmSheet(
                title = L("Record the euthanasia without the signed consent?"),
                message = L("Only owners and admins can. It is written down with your name and the reason."),
                confirmTitle = L("Record"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                cancelTitle = L("Not now"),
            ) { reason ->
                submit(override = reason ?: "")
            }
        }
    }
}

/** A consent id to sign, as a sheet item. */
data class HospSigning(val id: String)
