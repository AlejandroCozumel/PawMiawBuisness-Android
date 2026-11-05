package com.pawmiaw.business.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.hospital.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

//
//  SchedVisitMoney.kt (iOS SchedVisitMoney.swift)
//
//  The hospital from a visit (the emergency deposit is the shared
//  DepositSheet): hospitalizing the visit's patient
//  (web hospital/admit-sheet.tsx with the visit's preset): why and where,
//  the estimate the family signs and the deposit, admitted now or waiting.
//

// MARK: - Hospitalize from the visit

/** The visit's patient, with what the triage said: why and where, then the estimate and the deposit. */
data class SchedAdmitPreset(
    val appointmentId: String,
    val clientName: String,
    val familyOnApp: Boolean,
    val petId: String,
    val petName: String,
    val priority: String? = null,
    val reason: String? = null,
) {
    val id: UUID = UUID.randomUUID()
}

@Composable
fun SchedAdmitSheet(
    preset: SchedAdmitPreset,
    modifier: Modifier = Modifier,
    /** The hospitalization made: its page opens next. */
    onCreated: (String) -> Unit,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    // What the visit already knows (the triage's reason and priority) starts the form.
    val reason = remember { mutableStateOf(preset.reason ?: "") }
    val emergency = remember { mutableStateOf(preset.priority != null) }
    val priority = remember { mutableStateOf(preset.priority) }
    val roomId = remember { mutableStateOf("") }
    val cageLabel = remember { mutableStateOf("") }
    val attendingId = remember { mutableStateOf("") }
    val estimate = remember { mutableStateOf<Int?>(null) }
    val deposit = remember { mutableStateOf<Int?>(null) }
    var signHere by remember { mutableStateOf(!preset.familyOnApp) }
    val admitNow = remember { mutableStateOf(true) }
    val internalNote = remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<HospMember>>(emptyList()) }
    var wards by remember { mutableStateOf<List<HospRoom>>(emptyList()) }
    var occupied by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var roomFull by remember { mutableStateOf<String?>(null) }
    var overriding by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    /** The web's two steps for a visit's patient (the patient is already known): why and where, then the money. */
    var step by remember { mutableIntStateOf(0) }

    val vets = members.filter { it.can(Permission.hospitalManage) }
    val me = members.firstOrNull { it.isCurrentMember == true }
    val hasEstimate = (estimate.value ?: 0) > 0
    val owesDeposit = (deposit.value ?: 0) > 0 && !(emergency.value && priority.value == "critical")
    val gated = hasEstimate || owesDeposit
    val room = wards.firstOrNull { it.id == roomId.value }

    val primaryTitle = when {
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

    LaunchedEffect(Unit) {
        if (prepared) return@LaunchedEffect
        prepared = true
        coroutineScope {
            val loadedMembers = async { HospAPI.members() }
            val loadedWards = async { HospAPI.wards(Workspace.shared.locationId) }
            val board: HospBoard? = try {
                API.get("/provider-hospital/board", mapOf("locationId" to Workspace.shared.locationId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            members = loadedMembers.await()
            wards = loadedWards.await().filter { it.status == null || it.status == "active" }
            occupied = (board?.rooms ?: emptyList()).groupBy { it.id }.mapValues { (_, rooms) -> rooms.first().occupied ?: 0 }
        }
        val mine = members.firstOrNull { it.isCurrentMember == true }
        if (attendingId.value.isEmpty() && mine != null && mine.can(Permission.hospitalManage)) attendingId.value = mine.id
    }

    fun validate(): Boolean = form.check(listOf(
        "reason" to if (reason.value.nonEmpty == null) L("Say why the patient is hospitalized.") else null,
        "deposit" to if (hasEstimate && (deposit.value ?: 0) > (estimate.value ?: 0)) L("The deposit can’t be more than the estimate.") else null,
    ))

    /** Creates it; a ConfirmSheet's action rethrows so it stays open on a refusal. */
    suspend fun submit(extra: Map<String, Any?>) {
        val body = mutableMapOf<String, Any?>(
            "providerClientPetId" to preset.petId,
            "appointmentId" to preset.appointmentId,
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
        if (hasEstimate) body["notifyFamily"] = !signHere
        for ((key, value) in extra) body[key] = value
        saving = true
        try {
            val created: SchedAdmitted = SchedAPI.send("POST", "/provider-hospital/hospitalizations", body)
            announceDataChanged()
            roomFull = null
            overriding = false
            val name = created.patient?.name ?: preset.petName
            created.consentError?.let { Toast.error(LF("The estimate could not be sent to sign: %@", it)) }
            if (created.status == "admitted") {
                Toast.show(if (created.client?.onPawMiaw == true) LF("%@ is in the hospital. The family has been told.", name) else LF("%@ is in the hospital.", name))
            } else if (created.admitError?.code == "room_full") {
                Toast.show(LF("%@ is waiting for admission: the ward is full.", name))
            } else {
                Toast.show(LF("%@ is waiting for admission.", name))
            }
            dismiss()
            onCreated(created.id)
        } catch (e: CancellationException) {
            throw e
        } catch (failure: SchedFailure) {
            if (failure.code == "room_full") {
                if (roomFull == null) {
                    roomFull = failure.api.message
                    return
                }
                throw failure.api
            }
            val existing = failure.body["hospitalizationId"].string
            if (failure.code == "already_hospitalized" && existing != null) {
                Toast.error(failure.api.message)
                dismiss()
                onCreated(existing)
                return
            }
            if (!overriding && roomFull == null) {
                if (form.apply(failure.api, mapOf("reason" to "reason", "estimateAmount" to "estimate", "depositAmount" to "deposit")) && form.error("reason") != null) step = 0
                return
            }
            throw failure.api
        } catch (e: Throwable) {
            if (!overriding && roomFull == null) {
                Toast.error(e)
                return
            }
            throw e
        } finally {
            saving = false
        }
    }

    ProviderModal(
        title = LF("Hospitalize %@", preset.petName), subtitle = L("It waits for the signed estimate and the deposit, then is admitted."),
        systemImage = "bed-double", accent = ModalAccent.pink,
        isDirty = prepared && (reason.value.isNotEmpty() || estimate.value != null || deposit.value != null), form = form,
        primaryTitle = if (step == 0) L("Continue") else primaryTitle, isWorking = saving,
        steps = ModalSteps(listOf(L("Why and where") to "stethoscope", L("Estimate and deposit") to "wallet"), current = step),
        onBack = if (step > 0) ({ step = 0 }) else null,
        secondaryActions = if (step == 1 && admitNow.value && workspace.isOwnerOrAdmin && gated) {
            listOf(ModalAction(L("Admit without the gate"), tint = Theme.destructive) { if (validate()) overriding = true })
        } else emptyList(),
        primaryIcon = if (step == 0) null else "bed-double",
        onPrimary = {
            if (step == 0) {
                if (form.check(listOf("reason" to if (reason.value.nonEmpty == null) L("Say why the patient is hospitalized.") else null))) step = 1
            } else if (validate()) {
                scope.launch {
                    try {
                        submit(emptyMap())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        ContextPills(client = preset.clientName, pets = listOf(preset.petName)) {
            if (step == 1) {
                if (emergency.value) ContextPill(priority.value?.let { HospWords.triage(it) } ?: L("Emergency"), icon = "siren")
                listOf(room?.name, cageLabel.value.nonEmpty).mapNotNull { it.nonEmpty }.joinToString(" · ").nonEmpty?.let { ContextPill(it, icon = "bed-double") }
                vets.firstOrNull { it.id == attendingId.value }?.let { ContextPill(it.label, icon = "stethoscope") }
            }
        }
        if (step == 0) {
            ModalSection(title = L("Why"), required = true) {
                SchedBareField(prompt = L("e.g. Gastroenteritis, dehydrated — IV fluids and monitoring"), text = reason, key = "reason", form = form)
                ToggleRow(title = L("Emergency"), subtitle = L("A critical emergency is never held back by the deposit."), isOn = emergency)
                if (emergency.value) {
                    PillPicker(options = HospWords.priorities.map { it to HospWords.triage(it) }, choice = priority)
                }
            }
            ModalSection(title = L("Where and who")) {
                if (wards.isEmpty()) {
                    NoticeCard(
                        tone = NoticeCard.Tone.info, title = L("No wards set up yet"),
                        message = L("Admit them without a place and place them from the ward later. Write the cage or bed below if you know it."),
                        icon = "bed-double",
                    )
                } else {
                    MenuField(
                        label = L("Ward or cage"),
                        options = listOf("" to L("Not placed yet")) + wards.map { ward ->
                            ward.id to LF("%@ · %@ of %@", ward.name ?: "", (occupied[ward.id] ?: 0).toString(), (ward.capacity ?: 0).toString())
                        },
                        selection = roomId,
                    )
                    if (room != null && (occupied[room.id] ?: 0) >= (room.capacity ?: 0)) {
                        Text(LF("%@ is full.", room.name ?: ""), style = Theme.text(12, FontWeight.Bold), color = Theme.goldText)
                    }
                }
                PMTextField(L("Cage or bed"), text = cageLabel, prompt = L("e.g. C3"))
                MenuField(label = L("Attending vet"), options = attendingOptions, selection = attendingId)
            }
        } else {
            ModalSection(
                title = L("Estimate and deposit"),
                subtitle = L("The family signs the estimate before the patient is admitted; the deposit stays as credit and is spent on the bill."),
            ) {
                MoneyField(label = L("Estimate"), cents = estimate, key = "estimate", form = form)
                MoneyField(label = L("Deposit"), cents = deposit, key = "deposit", form = form)
                if (hasEstimate) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(LF("The family signs an estimate of %@.", Money.format(estimate.value ?: 0, null)), style = Theme.text(12), color = Theme.inkSoft)
                        PillPicker(
                            options = (if (preset.familyOnApp) listOf("family" to L("Send it to the family’s app")) else emptyList()) + listOf("here" to L("Sign here, on this device")),
                            choice = stateOf<String?>({ if (signHere) "here" else "family" }) { signHere = it == "here" },
                        )
                    }
                } else {
                    Text(L("No estimate: nothing to sign."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
            ToggleRow(title = L("Admit now"), subtitle = admitNowHint, isOn = admitNow)
            PMTextField(L("Internal note"), text = internalNote, prompt = L("Only the team sees it."), multiline = true)
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
                confirmTitle = L("Admit anyway"), destructive = true, askReason = true, reasonRequired = true, cancelTitle = L("Not now"),
            ) { why ->
                submit(mapOf("admitNow" to true, "overrideConsent" to true, "overrideDeposit" to true, "overrideReason" to (why ?: "")))
            }
        }
    }
}

/** A soft pill after the client and the pet (the triage, the place, the vet), as ContextPills draws them. */
@Composable
private fun ContextPill(text: String, icon: String) {
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .background(Theme.surfaceMuted, CapsuleShape)
            .border(1.dp, Theme.borderSoft, CapsuleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 14.dp, tint = Theme.inkStrong)
        Text(text, style = brandText(12, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
