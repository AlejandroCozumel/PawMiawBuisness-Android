package com.pawmiaw.business.hospital

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

//
//  HospitalPlacement.kt (iOS HospitalPlacement.swift)
//
//  Where the patient is and who looks after it (web
//  hospital-placement-sheet.tsx). The deposit, signing the estimate or the
//  euthanasia consent and the PDFs are the shared DepositSheet,
//  ConsentSignSheet and Quick Look download.
//

// MARK: - Ward, vet and estimate

@Composable
fun HospPlacementSheet(model: HospDetailModel, detail: HospDetail, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var wards by remember { mutableStateOf<List<HospRoom>>(emptyList()) }
    var occupied by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var members by remember { mutableStateOf<List<HospMember>>(emptyList()) }
    // Filled once from the stay (iOS `prepare()`'s first part).
    val roomId = remember { mutableStateOf(detail.room?.id ?: "") }
    val cageLabel = remember { mutableStateOf(detail.cageLabel ?: "") }
    val attendingId = remember { mutableStateOf(detail.attending?.id ?: "") }
    val reason = remember { mutableStateOf(detail.reason ?: "") }
    val priority = remember { mutableStateOf(detail.triagePriority) }
    val estimate = remember { mutableStateOf(detail.estimateAmount) }
    val deposit = remember { mutableStateOf(if ((detail.depositAmount ?: 0) > 0) detail.depositAmount else null) }
    val note = remember { mutableStateOf(detail.internalNote ?: "") }
    var saving by remember { mutableStateOf(false) }
    var roomFull by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        coroutineScope {
            val loadedWards = async { HospAPI.wards(locationId = detail.locationId) }
            val loadedMembers = async { HospAPI.members() }
            val board: HospBoard? = try {
                API.get("/provider-hospital/board", mapOf("locationId" to Workspace.shared.locationId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            wards = loadedWards.await().filter { it.status == null || it.status == "active" || it.id == detail.room?.id }
            members = loadedMembers.await()
            occupied = hospOccupied(board)
        }
    }

    /**
     * Only what changed. A field cleared here is sent as null, which the server clears (as the
     * web and iOS do); leaving the key out would keep the old value.
     */
    fun patch(): MutableMap<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        if (roomId.value.nonEmpty != detail.room?.id) out["roomId"] = roomId.value.nonEmpty
        if (cageLabel.value.nonEmpty != detail.cageLabel.nonEmpty) out["cageLabel"] = cageLabel.value.nonEmpty
        if (attendingId.value.nonEmpty != detail.attending?.id) out["attendingMemberId"] = attendingId.value.nonEmpty
        if ((reason.value.nonEmpty ?: "") != (detail.reason ?: "")) out["reason"] = reason.value.nonEmpty
        if (priority.value != detail.triagePriority) out["triagePriority"] = priority.value
        if (estimate.value != detail.estimateAmount) out["estimateAmount"] = estimate.value
        if ((deposit.value ?: 0) != (detail.depositAmount ?: 0)) out["depositAmount"] = deposit.value ?: 0
        if (note.value.nonEmpty != detail.internalNote.nonEmpty) out["internalNote"] = note.value.nonEmpty
        return out
    }

    suspend fun save(allowOverCapacity: Boolean) {
        if (!form.check(listOf(
                "reason" to if (reason.value.nonEmpty == null) L("Say why the patient is hospitalized.") else null,
                "deposit" to if ((estimate.value ?: 0) > 0 && (deposit.value ?: 0) > (estimate.value ?: 0)) L("The deposit can’t be more than the estimate.") else null,
            ))) return
        val body = patch()
        if (body.isEmpty()) {
            dismiss()
            return
        }
        if (allowOverCapacity) body["allowOverCapacity"] = true
        saving = true
        try {
            model.write("", method = "PATCH", body = body)
            val raised = (estimate.value ?: 0) > (detail.estimateAmount ?: 0) && body.containsKey("estimateAmount") && detail.isPending
            Toast.show(if (raised) L("Saved. The family signs the new estimate.") else L("Saved."))
            roomFull = null
            dismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            if ((error as? APIError)?.code == "room_full" && !allowOverCapacity) {
                roomFull = hospError(error)
                return
            }
            if (!allowOverCapacity) {
                form.apply(hospRefusal(error, "Could not save."), known = mapOf("reason" to "reason", "estimateAmount" to "estimate", "depositAmount" to "deposit"))
            }
            throw hospRefusal(error, "Could not save.")
        } finally {
            saving = false
        }
    }

    ProviderModal(
        title = LF("Change %@’s stay", detail.patient?.name ?: L("the patient")),
        subtitle = L("Ward, attending vet, reason, triage, estimate and deposit."),
        systemImage = "pencil",
        accent = ModalAccent.pink,
        // iOS `filled && …`: the fields are filled from the stay before the first frame.
        isDirty = patch().isNotEmpty(),
        form = form,
        primaryTitle = L("Save"),
        isWorking = saving,
        primaryIcon = "save",
        onPrimary = {
            scope.launch {
                try {
                    save(allowOverCapacity = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
        },
        modifier = modifier,
    ) {
        MenuField(
            label = L("Ward or cage"),
            options = listOf("" to L("Not placed yet")) + wards.map { ward ->
                ward.id to LF("%@ · %d of %d", ward.name ?: "", occupied[ward.id] ?: 0, ward.capacity ?: 0)
            },
            selection = roomId,
        )
        PMTextField(L("Cage or bed"), text = cageLabel)
        MenuField(
            label = L("Attending vet"),
            options = listOf("" to "—") + members.filter { it.can(Permission.hospitalManage) }.map { it.id to it.label },
            selection = attendingId,
        )
        // The web's "Why" part between stitched rules: the reason, then the triage.
        StitchedDivider(modifier = Modifier.padding(vertical = 4.dp))
        PMTextField(L("Why"), text = reason, key = "reason", form = form, multiline = true)
        FormField(label = L("Triage")) {
            PillPicker(options = HospWords.priorities.map { it to HospWords.triage(it) }, choice = priority, allowsNone = true)
        }
        StitchedDivider(modifier = Modifier.padding(vertical = 4.dp))
        MoneyField(label = L("Estimate"), cents = estimate, key = "estimate", form = form, optional = true)
        MoneyField(label = L("Deposit"), cents = deposit, key = "deposit", form = form, optional = true)
        HospHint(text = L("Raising the estimate after it was signed asks the family to sign the new amount."))
        PMTextField(L("Internal note"), text = note, prompt = L("Only the team sees it."), multiline = true)
    }
    roomFull?.let { message ->
        AppSheet(onDismissRequest = { roomFull = null }) {
            ConfirmSheet(title = L("Place the patient anyway?"), message = message, confirmTitle = L("Place anyway"), destructive = true) {
                save(allowOverCapacity = true)
            }
        }
    }
}
