package com.pawmiaw.business.hospital

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.pawmiaw.business.*
import com.pawmiaw.business.stays.StaysPlainField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

//
//  HospitalAdmit.kt (iOS HospitalAdmit.swift)
//
//  Admit a patient (web admit-sheet.tsx), in steps: who (a client and one of
//  their pets), why and where (reason, emergency and triage, ward or cage,
//  the attending vet), then the estimate the family signs and the deposit.
//  It is created waiting for the gate and admitted right away when "Admit
//  now" is on and the gate allows it, or an owner or admin goes ahead with
//  a reason. A ward that is full asks "Place the patient anyway?".
//

/** What the ward opens after an admission: the patient, and the estimate to sign here first. */
data class HospOpened(
    val id: String,
    val signConsentId: String? = null,
    val admitAfterSigning: Boolean = false,
)

private enum class HospAdmitStep(val rawValue: Int) { patient(0), why(1), money(2) }

@Composable
fun HospAdmitSheet(modifier: Modifier = Modifier, onCreated: (HospOpened) -> Unit) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }

    var step by remember { mutableStateOf(HospAdmitStep.patient) }
    val client = remember { mutableStateOf<PickedClient?>(null) }
    val petIds = remember { mutableStateOf<List<String>>(emptyList()) }
    val reason = remember { mutableStateOf("") }
    val emergency = remember { mutableStateOf(false) }
    val priority = remember { mutableStateOf<String?>(null) }
    val roomId = remember { mutableStateOf("") }
    val cageLabel = remember { mutableStateOf("") }
    val attendingId = remember { mutableStateOf("") }
    val estimate = remember { mutableStateOf<Int?>(null) }
    val deposit = remember { mutableStateOf<Int?>(null) }
    var signHere by remember { mutableStateOf(false) }
    val admitNow = remember { mutableStateOf(true) }
    val internalNote = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var members by remember { mutableStateOf<List<HospMember>>(emptyList()) }
    var wards by remember { mutableStateOf<List<HospRoom>>(emptyList()) }
    var occupied by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var roomFull by remember { mutableStateOf<String?>(null) }
    var overriding by remember { mutableStateOf(false) }
    /** The patient is already in the hospital: its hospitalization, to open instead. */
    var existingId by remember { mutableStateOf<String?>(null) }

    val vets = members.filter { it.can(Permission.hospitalManage) }
    val me = members.firstOrNull { it.isCurrentMember == true }
    val petId = petIds.value.firstOrNull()
    val pet = client.value?.activePets?.firstOrNull { it.id == petId }
    val patientName = pet?.name ?: ""
    val attendingName = (attendingId.value.nonEmpty ?: me?.id).let { id -> members.firstOrNull { it.id == id }?.label }
    val hasEstimate = (estimate.value ?: 0) > 0
    val owesDeposit = (deposit.value ?: 0) > 0 && !(emergency.value && priority.value == "critical")
    val gated = hasEstimate || owesDeposit
    val canOverride = workspace.isOwnerOrAdmin
    val clientOnApp = client.value?.householdId != null
    val room = wards.firstOrNull { it.id == roomId.value }

    val primaryTitle = when {
        step != HospAdmitStep.money -> L("Continue")
        !admitNow.value -> L("Save as waiting")
        !gated -> L("Admit")
        hasEstimate && signHere -> L("Save and sign")
        else -> L("Save and wait")
    }

    val admitNowHint = when {
        !admitNow.value -> L("It waits on the board until someone admits it.")
        !gated -> L("Right away: there is nothing to sign or pay first.")
        !owesDeposit -> L("Once the estimate is signed.")
        hasEstimate -> L("Once the estimate is signed and the deposit paid: “Admit” on its page.")
        else -> L("Once the deposit is paid: “Admit” on its page.")
    }

    val attendingOptions: List<Pair<String, String>> = buildList {
        if (!(me?.let { mine -> vets.any { it.id == mine.id } } ?: false)) add("" to L("Me"))
        addAll(vets.map { it.id to if (it.id == me?.id) LF("%@ (you)", it.label) else it.label })
    }

    // MARK: Actions

    LaunchedEffect(Unit) {
        coroutineScope {
            val loadedMembers = async { HospAPI.members() }
            val loadedWards = async { HospAPI.wards(locationId = Workspace.shared.locationId) }
            val board: HospBoard? = try {
                API.get("/provider-hospital/board", mapOf("locationId" to Workspace.shared.locationId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            members = loadedMembers.await()
            wards = loadedWards.await().filter { it.status == null || it.status == "active" }
            occupied = hospOccupied(board)
        }
        val mine = members.firstOrNull { it.isCurrentMember == true }
        if (attendingId.value.isEmpty() && mine != null && mine.can(Permission.hospitalManage)) attendingId.value = mine.id
    }

    HospOnChange(client.value?.id) { signHere = client.value?.let { it.householdId == null } ?: false }

    fun validate(): Boolean {
        if (petId == null) {
            step = HospAdmitStep.patient
            return form.check(listOf("patient" to L("Choose the patient.")))
        }
        if (reason.value.nonEmpty == null) {
            step = HospAdmitStep.why
            return form.check(listOf("reason" to L("Say why the patient is hospitalized.")))
        }
        return form.check(listOf(
            "deposit" to if (hasEstimate && (deposit.value ?: 0) > (estimate.value ?: 0)) L("The deposit can’t be more than the estimate.") else null,
        ))
    }

    /** What a patient saved as waiting still needs, in a line. */
    fun waitsOn(gate: HospGate?): String? {
        val consent = gate?.consent?.required == true && gate?.consent?.satisfied != true
        val owed = gate?.deposit?.satisfied != true
        if (consent && owed) return L("The estimate needs signing and the deposit paying.")
        if (consent) return L("The estimate needs signing.")
        if (owed) return L("The deposit needs paying.")
        return null
    }

    /** Creates it; a ConfirmSheet's action rethrows so it stays open on a refusal. */
    suspend fun submit(extra: Map<String, Any?>) {
        val chosenPet = petId ?: return
        val body = mutableMapOf<String, Any?>(
            "providerClientPetId" to chosenPet,
            "appointmentId" to null,
            "emergency" to emergency.value,
            "triagePriority" to if (emergency.value) priority.value else null,
            "reason" to reason.value.nonEmpty,
            "internalNote" to internalNote.value.nonEmpty,
            "roomId" to roomId.value.nonEmpty,
            "cageLabel" to cageLabel.value.nonEmpty,
            "attendingMemberId" to attendingId.value.nonEmpty,
            "estimateAmount" to if (hasEstimate) estimate.value else null,
            "depositAmount" to (deposit.value ?: 0),
            "askConsent" to hasEstimate,
            "admitNow" to admitNow.value,
        )
        workspace.locationId?.let { body["locationId"] = it }
        if (hasEstimate) body["notifyFamily"] = !signHere
        for ((key, value) in extra) body[key] = value
        saving = true
        try {
            existingId = null
            val detail: HospDetail = API.post("/provider-hospital/hospitalizations", body)
            announceDataChanged()
            roomFull = null
            overriding = false
            detail.consentError?.let { failed -> Toast.error(LF("The estimate could not be sent to sign: %@", failed)) }
            val name = detail.patient?.name ?: patientName
            val consentId = detail.consentRequest?.consentId
            val signsHere = consentId != null && (body["notifyFamily"] as? Boolean) == false && detail.status == "pending"
            val refusal = detail.admitError
            if (detail.status == "admitted") {
                Toast.show(if (detail.client?.onPawMiaw == true) LF("%@ is in the hospital. The family has been told.", name) else LF("%@ is in the hospital.", name))
            } else if (refusal?.code == "room_full") {
                Toast.show(LF("%@ is waiting for admission: the ward is full.", name))
            } else if (refusal?.code == "consent_required" || refusal?.code == "deposit_required") {
                if (!signsHere) Toast.show(listOfNotNull(LF("%@ is waiting for admission.", name), waitsOn(detail.gate)).joinToString(" "))
            } else if (refusal != null) {
                Toast.show(LF("%@ is waiting for admission.", name) + " " + hospRefusalText(code = refusal.code, error = refusal.error, fallback = "Could not admit the patient."))
            } else {
                Toast.show(LF("%@ is waiting for admission.", name))
            }
            dismiss()
            onCreated(HospOpened(id = detail.id, signConsentId = if (signsHere) consentId else null, admitAfterSigning = admitNow.value))
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            // A refusal keeps its body: `already_hospitalized` names the hospitalization to open instead.
            val api = failure as? APIError
            if (api?.code == "room_full") {
                if (roomFull == null) roomFull = hospError(failure) else throw hospRefusal(failure, "Could not admit the patient.")
                return
            }
            val existing = (api?.body)["hospitalizationId"].string
            if (api?.code == "already_hospitalized" && existing != null) {
                existingId = existing
                roomFull = null
                overriding = false
                Toast.error(hospError(failure, "Could not admit the patient."))
                return
            }
            if (!overriding && roomFull == null) {
                form.apply(hospRefusal(failure, "Could not admit the patient."), known = mapOf("reason" to "reason", "estimateAmount" to "estimate", "depositAmount" to "deposit"))
            }
            throw hospRefusal(failure, "Could not admit the patient.")
        } finally {
            saving = false
        }
    }

    fun primary() {
        when (step) {
            HospAdmitStep.patient -> {
                if (!form.check(listOf("patient" to if (petId == null) L("Choose the patient.") else null))) return
                step = HospAdmitStep.why
            }
            HospAdmitStep.why -> {
                if (!form.check(listOf("reason" to if (reason.value.nonEmpty == null) L("Say why the patient is hospitalized.") else null))) return
                step = HospAdmitStep.money
            }
            HospAdmitStep.money -> {
                if (!validate()) return
                scope.launch {
                    try {
                        submit(emptyMap())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    ProviderModal(
        title = if (patientName.isEmpty()) L("Admit a patient") else LF("Hospitalize %@", patientName),
        subtitle = L("It waits for the signed estimate and the deposit, then is admitted."),
        systemImage = "bed-double",
        accent = ModalAccent.pink,
        isDirty = client.value != null || step != HospAdmitStep.patient,
        form = form,
        primaryTitle = primaryTitle,
        isWorking = saving,
        steps = ModalSteps(listOf(L("Patient") to "user-round", L("Why and where") to "stethoscope", L("Estimate and deposit") to "wallet"), current = step.rawValue),
        onBack = if (step != HospAdmitStep.patient) ({ step = HospAdmitStep.entries.firstOrNull { it.rawValue == step.rawValue - 1 } ?: HospAdmitStep.patient }) else null,
        secondaryActions = if (step == HospAdmitStep.money && admitNow.value && canOverride && gated) {
            listOf(ModalAction(L("Admit without the gate"), tint = Theme.destructive) { if (validate()) overriding = true })
        } else emptyList(),
        primaryIcon = if (step == HospAdmitStep.money) "bed-double" else null,
        onPrimary = { primary() },
        modifier = modifier,
    ) {
        val chosen = client.value
        if (step != HospAdmitStep.patient && chosen != null) {
            // Who it is for (Back changes it), then what the second step settled (web ModalContextPills).
            ContextPills(client = chosen.name, linked = chosen.householdId != null, pets = if (patientName.isEmpty()) emptyList() else listOf(patientName)) {
                if (step == HospAdmitStep.money && emergency.value) hospContextPill(priority.value?.let { HospWords.triage(it) } ?: L("Emergency"), icon = "siren")
                if (step == HospAdmitStep.money) {
                    listOfNotNull(room?.name, cageLabel.value.nonEmpty).joinToString(" · ").nonEmpty?.let { place ->
                        hospContextPill(place, icon = "bed-double")
                    }
                }
                if (step == HospAdmitStep.money && attendingName != null) hospContextPill(attendingName, icon = "stethoscope")
            }
        }
        existingId?.let { existing ->
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = L("This patient is already in the hospital."),
                actionTitle = L("Open it"),
                action = {
                    dismiss()
                    onCreated(HospOpened(id = existing))
                },
            )
        }
        when (step) {
            HospAdmitStep.patient -> {
                ClientPetPicker(
                    client = client, petIds = petIds, pets = ClientPetPicker.Pets.one, petRequired = true,
                    label = L("Patient"), key = "patient", petKey = "patient", form = form,
                )
            }
            HospAdmitStep.why -> {
                // The web's "Why *" part: the reason under the title (no label of its own), then the
                // emergency switch with its triage.
                ModalSection(title = L("Why"), required = true) {
                    StaysPlainField(
                        prompt = L("e.g. Gastroenteritis, dehydrated — IV fluids and monitoring"),
                        text = reason, key = "reason", form = form, multiline = true,
                        modifier = Modifier.hospLabel(L("Why")),
                    )
                    ToggleRow(title = L("Emergency"), subtitle = L("A critical emergency is never held back by the deposit."), isOn = emergency)
                    if (emergency.value) {
                        FormField(label = L("Triage")) {
                            PillPicker(options = HospWords.priorities.map { it to HospWords.triage(it) }, choice = priority)
                        }
                    }
                }
                ModalSection(title = L("Where and who")) {
                    if (wards.isEmpty()) {
                        NoticeCard(
                            tone = NoticeCard.Tone.info, title = L("No wards set up yet"),
                            message = L("Admit them without a place and place them from the ward later. Write the cage or bed below if you know it."),
                        )
                    } else {
                        MenuField(
                            label = L("Ward or cage"),
                            options = listOf("" to L("Not placed yet")) + wards.map { ward ->
                                ward.id to LF("%@ · %d of %d", ward.name ?: "", occupied[ward.id] ?: 0, ward.capacity ?: 0)
                            },
                            selection = roomId,
                        )
                        if (room != null && (occupied[room.id] ?: 0) >= (room.capacity ?: 0)) {
                            Text(LF("%@ is full.", room.name ?: ""), style = Theme.text(12, FontWeight.Bold), color = Theme.mango)
                        }
                    }
                    PMTextField(L("Cage or bed"), text = cageLabel, prompt = L("e.g. C3"))
                    MenuField(label = L("Attending vet"), options = attendingOptions, selection = attendingId)
                }
            }
            HospAdmitStep.money -> {
                ModalSection(
                    title = L("Estimate and deposit"),
                    subtitle = L("The family signs the estimate before the patient is admitted; the deposit stays as credit and is spent on the bill."),
                ) {
                    MoneyField(label = L("Estimate"), cents = estimate, key = "estimate", form = form, optional = true)
                    MoneyField(label = L("Deposit"), cents = deposit, key = "deposit", form = form, optional = true)
                    if (hasEstimate) {
                        HospHint(text = LF("The family signs an estimate of %@.", Money.format(estimate.value, workspace.currency)))
                        PillPicker(
                            options = (if (clientOnApp) listOf("family" to L("Send it to the family’s app")) else emptyList()) + listOf("here" to L("Sign here, on this device")),
                            choice = stateOf<String?>({ if (signHere) "here" else "family" }, { signHere = it == "here" }),
                        )
                    } else {
                        HospHint(text = L("No estimate: nothing to sign."))
                    }
                }
                ToggleRow(title = L("Admit now"), subtitle = admitNowHint, isOn = admitNow)
                PMTextField(L("Internal note"), text = internalNote, prompt = L("Only the team sees it."), multiline = true)
            }
        }
    }

    roomFull?.let { message ->
        AppSheet(onDismissRequest = { roomFull = null }) {
            ConfirmSheet(title = L("Place the patient anyway?"), message = message, confirmTitle = L("Place anyway"), destructive = true) {
                submit(mapOf("allowOverCapacity" to true))
            }
        }
    }
    if (overriding) {
        AppSheet(onDismissRequest = { overriding = false }) {
            ConfirmSheet(
                title = L("Admit without the signed estimate or the deposit?"),
                message = L("Only owners and admins can. It is written down with your name and the reason."),
                confirmTitle = L("Admit anyway"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                cancelTitle = L("Not now"),
            ) { why ->
                submit(mapOf("admitNow" to true, "overrideConsent" to true, "overrideDeposit" to true, "overrideReason" to (why ?: "")))
            }
        }
    }
}

/** How many patients each ward holds now, from the board (the first of a repeated id wins). */
fun hospOccupied(board: HospBoard?): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    (board?.rooms ?: emptyList()).forEach { room -> if (!out.containsKey(room.id)) out[room.id] = room.occupied ?: 0 }
    return out
}
