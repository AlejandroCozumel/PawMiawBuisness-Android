package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.clients.ClientsChart
import com.pawmiaw.business.clients.ClientsWeightSheet
import com.pawmiaw.business.team.TeamCredentialSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

//
//  StockPrescriptionSheet.kt (iOS StockPrescriptionSheet.swift)
//
//  Writes a prescription and signs it, or finishes a draft (web
//  pharmacy/new-prescription-flow.tsx + prescriptions/prescription-sheet.tsx,
//  prescription-review.tsx): who it is for (from the Pharmacy), the medicines
//  (a list, one edited at a time), what goes for the whole prescription, and a
//  review of it as it will print with whatever still stops the signature. A
//  draft saves from any step with whatever is filled; signing needs a
//  prescribing credential — without one the credential sheet opens here and
//  the signature goes through after it is saved.
//
//  The pet's chart gives its last weight (the dose by weight is worked out
//  from it; with none on file, "Weigh" opens the chart's weight sheet) and
//  its allergies (each medicine is checked against them by the web's
//  allergy matcher). Written in a visit, the diagnosis made in it is the
//  prescription's unless it is changed.
//

private enum class RxStep { who, medicines, details, review }

/** A record of the visit, as far as the prescription reads it (its diagnosis). */
@Serializable
private data class StockVisitRecord(
    val recordType: String? = null,
    val title: String? = null,
    val payload: JSONValue? = null,
)

/** Swift `try?`. */
private suspend inline fun <T> attempt(crossinline block: suspend () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

private data class RxCredentialAsk(val prescriptionId: String, val type: String?, val reason: String)

private data class RxBlocker(
    val id: String,
    val text: String,
    val itemKey: String?,
    val toDetails: Boolean,
    val about: String?,
)

private data class RxWarning(val id: String, val danger: Boolean, val text: String, val detail: String?)

/** The sheet's state (the Swift view's `@State`s and the properties worked out from them). */
@Stable
private class RxSheetModel(
    val pet: StockRxPet?,
    val draft: StockPrescription?,
    val appointmentId: String?,
) {
    val form = FormState()

    var chosenPet by mutableStateOf<StockRxPet?>(null)
    /** The client chosen in the who step (the shared picker), whose pet `chosenPet` is. */
    var pickedClient by mutableStateOf<PickedClient?>(null)
    val firstStep: RxStep = if (pet == null && draft == null) RxStep.who else RxStep.medicines
    var step by mutableStateOf(RxStep.who)
    var items by mutableStateOf(listOf(StockItemDraft()))
    var editing by mutableStateOf<String?>(null)
    var diagnosis by mutableStateOf("")
    var notes by mutableStateOf("")
    var validDays by mutableStateOf("")
    var dispenseNow by mutableStateOf(false)
    var draftId by mutableStateOf<String?>(null)
    var checked by mutableStateOf(false)
    var signChecked by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var removed by mutableStateOf<Pair<StockItemDraft, Int>?>(null)
    var changed by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var credentialAsk by mutableStateOf<RxCredentialAsk?>(null)

    var chart by mutableStateOf<StockChart?>(null)
    /** The same chart as the Clients module reads it, for its weight sheet. */
    var weighChart by mutableStateOf<ClientsChart?>(null)
    var weighing by mutableStateOf(false)
    var diagnosisFilled by mutableStateOf(false)
    var credentials by mutableStateOf<StockMemberCredentials?>(null)
    var active by mutableStateOf<List<StockPrescription>>(emptyList())

    /** What the sheet hands back and how it closes (set on each composition). */
    var onDone: (StockPrescription) -> Unit = {}
    var dismiss: () -> Unit = {}
    var scope: CoroutineScope? = null

    init {
        // Swift `start()` (onAppear, once).
        chosenPet = pet
        if (draft != null) {
            items = if (draft.items.isEmpty()) listOf(StockItemDraft()) else draft.items.map { StockItemDraft(it) }
            diagnosis = draft.diagnosis ?: ""
            notes = draft.notes ?: ""
            validDays = draft.validDays?.toString() ?: ""
            draftId = draft.id
            editing = if (draft.items.isEmpty()) items.firstOrNull()?.key else null
        } else {
            editing = items.firstOrNull()?.key
        }
        step = firstStep
    }

    val workspace: Workspace get() = Workspace.shared
    val rights: StockRxRights get() = StockRxRights(workspace)
    val canSign: Boolean get() = draft?.canSign ?: rights.canSign
    val canDispenseAtVisit: Boolean get() = draft?.canDispenseAtVisit ?: rights.canDispenseAtVisit
    val visitId: String? get() = appointmentId ?: draft?.appointmentId
    val country: String? get() = workspace.current?.country
    val language: String get() = draft?.language ?: (if (LocalizationManager.currentLanguage == AppLanguage.english) "en" else "es")
    val anyControlled: Boolean get() = items.any { it.controlSchedule.nonEmpty != null }
    val mixed: Boolean get() = items.size > 1 && anyControlled
    val schedule: String? get() = StockRxRules.strictest(items)
    /** (defaultDays, maxDays). */
    val validity: Pair<Int, Int> get() = StockRxRules.validity(schedule)
    val weightGrams: Int? get() = chart?.weights?.latestGrams ?: chart?.pet?.weightGrams
    val allergies: List<String> get() = (chart?.summary?.allergies ?: emptyList()).filter { it.draft != true }.map { it.label }
    val allergySources: List<StockAllergySource>
        get() = (chart?.summary?.allergies ?: emptyList()).filter { it.draft != true }
            .map { StockAllergySource(trigger = it.label, reaction = it.detail, recordId = it.recordId) }
    /** Whoever may weigh the pet (the chart's own rule): "Weigh" is offered when no weight is on file. */
    val canWeigh: Boolean get() = workspace.can(Permission.petsEdit) || workspace.can(Permission.appointmentsEdit)
    val problems: Map<String, List<StockItemProblem>> get() = items.associate { it.key to StockRxRules.problems(it, country = country) }
    val asked: Int? get() = validDays.nonEmpty?.toIntOrNull()
    val validDaysProblem: Boolean get() = asked?.let { it < 1 || it > StockRxRules.validDaysMax } ?: false
    val canAdd: Boolean get() = items.size < StockRxRules.maxItems && !anyControlled

    fun launch(block: suspend () -> Unit) {
        scope?.launch { block() }
    }

    suspend fun loadContext() {
        val who = chosenPet ?: return
        val signs = canSign
        coroutineScope {
            val chartLoad = async { attempt { API.data("GET", "/provider-clients/${who.clientId}/pets/${who.petId}/chart", emptyMap(), null) } }
            val credentialLoad = async { if (signs) attempt { API.get<StockMemberCredentials>("/provider-members/me/credentials") } else null }
            val activeLoad = async {
                attempt { API.get<StockPrescriptionPage>("/provider-prescriptions", mapOf("providerClientPetId" to who.petId, "state" to "active", "limit" to "20")) }
            }
            val data = chartLoad.await()
            if (data != null) {
                chart = attempt { API.decode<StockChart>(data) }
                // The weight sheet is the patient chart's own (Clients): it reads the chart its way.
                weighChart = attempt { API.decode<ClientsChart>(data) }
            }
            credentials = credentialLoad.await()
            active = activeLoad.await()?.items ?: emptyList()
        }
    }

    /** Written in a visit: the diagnosis made in it is the prescription's, unless changed (a draft keeps its own). */
    suspend fun fillVisitDiagnosis() {
        val visit = visitId
        if (draft != null || diagnosisFilled || visit == null) return
        val page = attempt {
            API.get<Page<StockVisitRecord>>("/provider-records", mapOf("limit" to "100", "offset" to "0", "appointmentId" to visit))
        } ?: return
        val seen = mutableListOf<String>()
        for (record in page.items) {
            if (record.recordType != "diagnosis") continue
            val condition = record.payload["condition"].string?.trim().nonEmpty
            val text = condition ?: record.title?.trim().nonEmpty ?: continue
            if (!seen.contains(text)) seen.add(text)
        }
        if (seen.isEmpty()) return
        diagnosisFilled = true
        if (diagnosis.trim { it == ' ' || it == '\t' }.isEmpty()) diagnosis = seen.joinToString(", ")
    }

    // MARK: Steps

    val primaryTitle: String
        get() = when (step) {
            RxStep.who -> L("Write the prescription")
            RxStep.medicines, RxStep.details -> L("Continue")
            RxStep.review -> if (canSign) L("Sign prescription") else L("Save draft")
        }

    fun primary() {
        when (step) {
            RxStep.who -> {
                if (!form.check(
                        listOf(
                            "client" to (if (pickedClient == null && chosenPet == null) L("Choose the client first.") else null),
                            "pet" to (if (pickedClient != null && chosenPet == null) L("Choose the pet it is for.") else null),
                        ),
                    )
                ) return
                step = RxStep.medicines
            }
            RxStep.medicines -> {
                if (!check(forSigning = false)) return
                step = RxStep.details
            }
            RxStep.details -> {
                if (!form.check(listOf("validDays" to (if (validDaysProblem) L("A prescription is valid for 1 to 365 days.") else null)))) return
                step = RxStep.review
            }
            RxStep.review -> launch { if (canSign) handleSign() else handleDraft() }
        }
    }

    fun back() {
        saveError = null
        step = when (step) {
            RxStep.who, RxStep.medicines -> firstStep
            RxStep.details -> RxStep.medicines
            RxStep.review -> RxStep.details
        }
    }

    fun touch() {
        changed = true
        saveError = null
    }

    fun medicineLabel(index: Int): String {
        if (index !in items.indices) return LF("Medicine %d", index + 1)
        return items[index].medicineName.nonEmpty?.let { LF("Medicine %d: %@", index + 1, it) } ?: LF("Medicine %d", index + 1)
    }

    fun binding(key: String): MutableState<StockItemDraft> = stateOf(
        { items.firstOrNull { it.key == key } ?: StockItemDraft() },
        { value ->
            val index = items.indexOfFirst { it.key == key }
            if (index >= 0 && items[index] != value) {
                items = items.toMutableList().also { it[index] = value }
                touch()
            }
        },
    )

    fun addMedicine() {
        val next = StockItemDraft()
        items = items + next
        editing = next.key
        removed = null
        touch()
    }

    fun duplicate(index: Int) {
        val copy = items[index].copy(key = UUID.randomUUID().toString().uppercase())
        items = items.toMutableList().also { it.add(index + 1, copy) }
        editing = copy.key
        touch()
    }

    fun move(index: Int, offset: Int) {
        val target = index + offset
        if (target !in items.indices) return
        items = items.toMutableList().also {
            val a = it[index]
            it[index] = it[target]
            it[target] = a
        }
        touch()
    }

    fun remove(index: Int) {
        val item = items[index]
        items = items.toMutableList().also { it.removeAt(index) }
        removed = item to index
        if (editing == item.key) editing = null
        touch()
    }

    fun undoRemove() {
        val (item, index) = removed ?: return
        items = items.toMutableList().also { it.add(min(index, it.size), item) }
        removed = null
        touch()
    }

    // MARK: Review

    val blockers: List<RxBlocker>
        get() {
            val list = mutableListOf<RxBlocker>()
            if (items.isEmpty()) list.add(RxBlocker("none", L("Add at least one medicine."), null, false, null))
            if (mixed) list.add(RxBlocker("mixed", L("A controlled medicine goes on a prescription of its own."), null, false, null))
            val found = problems
            items.forEachIndexed { index, item ->
                for (problem in found[item.key] ?: emptyList()) {
                    list.add(RxBlocker("${item.key}-${problem.field}", problem.message, item.key, false, medicineLabel(index)))
                }
            }
            if (validDaysProblem) list.add(RxBlocker("valid-days", L("A prescription is valid for 1 to 365 days."), null, true, null))
            return list
        }

    val warnings: List<RxWarning>
        get() {
            val list = mutableListOf<RxWarning>()
            val sources = allergySources
            items.forEachIndexed { index, item ->
                val conflicts = StockRxRules.allergyConflicts(item, sources = sources)
                if (conflicts.isNotEmpty()) {
                    list.add(
                        RxWarning(
                            "allergy-${item.key}", true,
                            LF("%@ looks like an allergy on the chart: %@.", medicineLabel(index), conflicts.joinToString(", ") { it.trigger }),
                            L("Check it before you sign. It can still be prescribed."),
                        ),
                    )
                }
            }
            fun triple(d: StockItemDraft) = Triple(d.catalogItemId, d.medicineName, d.activeIngredient)
            val repeated = items.indices.filter { i -> items.indices.any { j -> j != i && StockRxRules.sameMedicine(triple(items[i]), triple(items[j])) } }
            if (repeated.isNotEmpty()) {
                list.add(RxWarning("repeated", false, LF("The same medicine is written more than once: %@.", repeated.joinToString("; ") { medicineLabel(it) }), null))
            }
            items.forEachIndexed { index, item ->
                if (item.medicineName.nonEmpty == null && item.catalogItemId == null) return@forEachIndexed
                val same = active.filter { p ->
                    p.state == "active" && p.id != draftId &&
                        p.items.any { StockRxRules.sameMedicine(triple(item), Triple(it.catalogItemId, it.medicineName, it.activeIngredient ?: "")) }
                }
                if (same.isNotEmpty()) {
                    list.add(
                        RxWarning(
                            "active-${item.key}", false,
                            LF("%@ is already on an active prescription: %@.", medicineLabel(index), same.joinToString(", ") { it.folio ?: "—" }),
                            L("Both can be filled unless the earlier one is cancelled."),
                        ),
                    )
                }
            }
            val asked = asked
            val (_, maxDays) = validity
            if (asked != null && !validDaysProblem && asked > maxDays) {
                list.add(RxWarning("validity", false, LF("This class is valid for %d days at most: it will be valid for %d, not %d.", maxDays, maxDays, asked), null))
            }
            return list
        }

    fun previewHeader(credential: StockCredential?): StockDocHeader {
        val header = StockDocHeader(language = language, country = country)
        val today = DateText.dayKey()
        val (defaultDays, maxDays) = validity
        val asked = asked
        header.signedAt = today
        header.validUntil = StockRules.addDays(today, min(maxDays, if ((asked ?: 0) > 0 && !validDaysProblem) asked!! else defaultDays) - 1)
        header.controlled = schedule != null
        header.controlLabel = schedule?.let { StockRxWords.schedule(it) }
        val current = workspace.current
        header.businessName = current?.name ?: ""
        header.businessLine = listOf(current?.address, current?.phone, current?.email).mapNotNull { it.nonEmpty }.joinToString(" · ")
        header.credentialLine = credential?.let { c -> listOf(if (language == "en") c.label?.en else c.label?.es, c.number, c.region).mapNotNull { it.nonEmpty }.joinToString(" ") }
        header.clientName = chart?.client?.name ?: chosenPet?.clientName
        header.petName = chart?.pet?.name ?: chosenPet?.petName
        header.species = chart?.pet?.species ?: chosenPet?.species
        header.weightKg = weightGrams?.let { Math.round(it.toDouble() / 10).toDouble() / 100 }
        header.diagnosis = diagnosis.nonEmpty
        header.notes = notes.nonEmpty
        return header
    }

    fun fix(blocker: RxBlocker) {
        checked = true
        signChecked = true
        if (blocker.toDetails) {
            step = RxStep.details
            form.check(listOf("validDays" to L("A prescription is valid for 1 to 365 days.")))
        } else if (blocker.itemKey != null) {
            goToItem(blocker.itemKey, forSigning = true)
        }
    }

    /** Opens a medicine and marks what is wrong with it, scrolling to the first field. */
    fun goToItem(key: String, forSigning: Boolean) {
        step = RxStep.medicines
        editing = key
        val found = (problems[key] ?: emptyList()).filter { it.blocksDraft || forSigning }
        launch {
            delay(150)
            form.check(found.map { "$key-${it.field}" to it.message })
        }
    }

    // MARK: Saving

    /** What stops this save, pointed at; true when nothing does. */
    fun check(forSigning: Boolean): Boolean {
        checked = true
        if (forSigning) signChecked = true
        if (items.isEmpty()) {
            step = RxStep.medicines
            saveError = L("Add at least one medicine.")
            Toast.error(saveError ?: "")
            return false
        }
        if (mixed) {
            step = RxStep.medicines
            saveError = L("A controlled medicine goes on a prescription of its own.")
            Toast.error(saveError ?: "")
            return false
        }
        val found = problems
        for (item in items) {
            if ((found[item.key] ?: emptyList()).any { it.blocksDraft || forSigning }) {
                goToItem(item.key, forSigning = forSigning)
                return false
            }
        }
        if (validDaysProblem) {
            step = RxStep.details
            form.check(listOf("validDays" to L("A prescription is valid for 1 to 365 days.")))
            return false
        }
        return true
    }

    val payload: Map<String, Any?>
        get() = mapOf(
            "diagnosis" to diagnosis.nonEmpty,
            "notes" to notes.nonEmpty,
            "validDays" to asked,
            "items" to items.map { it.input },
        )

    suspend fun saveDraft(): StockPrescription {
        val id = draftId
        if (id != null) {
            return StockAPI.send("PATCH", "/provider-prescriptions/$id", body = payload)
        }
        val who = chosenPet ?: throw APIError(status = 400, message = L("Choose the pet it is for."), code = null, fields = emptyMap())
        val body = payload.toMutableMap()
        body["providerClientPetId"] = who.petId
        body["appointmentId"] = visitId
        val created: StockPrescription = StockAPI.send("POST", "/provider-prescriptions", body = body)
        draftId = created.id
        return created
    }

    fun done(result: StockPrescription, message: String) {
        Toast.show(message)
        announceDataChanged()
        onDone(result)
        dismiss()
    }

    suspend fun handleDraft() {
        if (saving || !check(forSigning = false)) return
        saving = true
        saveError = null
        try {
            done(saveDraft(), L("Saved as a draft. A vet with a credential signs it."))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            showError(error)
        } finally {
            saving = false
        }
    }

    suspend fun signExisting(id: String) {
        val signed: StockPrescription = StockAPI.send("POST", "/provider-prescriptions/$id/sign", body = mapOf("dispenseAtVisit" to dispenseNow))
        done(signed, LF("Signed. %@ is ready to print and share.", signed.folio ?: ""))
    }

    suspend fun handleSign() {
        if (saving || !check(forSigning = true)) return
        saving = true
        saveError = null
        try {
            if (draftId != null) {
                val saved = saveDraft()
                signExisting(saved.id)
            } else {
                val who = chosenPet ?: return
                val body = payload.toMutableMap()
                body["providerClientPetId"] = who.petId
                body["appointmentId"] = visitId
                body["sign"] = true
                body["dispenseAtVisit"] = dispenseNow
                val created: StockPrescription = StockAPI.send("POST", "/provider-prescriptions", body = body)
                done(created, LF("Signed. %@ is ready to print and share.", created.folio ?: ""))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (refusal: StockRefusal) {
            if (refusal.code == "credential_required" || refusal.code == "dea_required") {
                val id = refusal.body["prescriptionId"].string ?: draftId
                if (id == null) {
                    showError(refusal)
                    return
                }
                draftId = id
                val type = if (refusal.code == "dea_required") "dea" else refusal.body["requiredType"].string
                val reason = when {
                    refusal.code == "dea_required" -> L("A controlled medicine needs your DEA number. Add it and the prescription is signed.")
                    type == "cedula_profesional" -> L("To sign, add your cédula profesional. The prescription is kept as a draft meanwhile.")
                    type == "state_license" -> L("To sign, add your state license. The prescription is kept as a draft meanwhile.")
                    else -> L("To sign, add your professional credential. The prescription is kept as a draft meanwhile.")
                }
                credentialAsk = RxCredentialAsk(id, type, reason)
            } else {
                showError(refusal)
            }
        } catch (error: Exception) {
            showError(error)
        } finally {
            saving = false
        }
    }

    suspend fun signAfterCredential(id: String) {
        saving = true
        try {
            signExisting(id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            showError(error)
        } finally {
            saving = false
        }
    }

    /** A refused or lost save: the form stays as typed, the reason is said here, and the medicine it is about opens. */
    suspend fun showError(error: Throwable) {
        val refusal = error as? StockRefusal
        if (refusal?.code == "prescription_changed") {
            val text = L("This prescription changed while it was being signed. Here it is as it stands now: read it again and sign.")
            Toast.error(text)
            val id = refusal.body["prescriptionId"].string ?: draftId
            val fresh = id?.let { attempt { API.get<StockPrescription>("/provider-prescriptions/$it") } }
            if (fresh != null) {
                items = if (fresh.items.isEmpty()) listOf(StockItemDraft()) else fresh.items.map { StockItemDraft(it) }
                diagnosis = fresh.diagnosis ?: ""
                notes = fresh.notes ?: ""
                validDays = fresh.validDays?.toString() ?: ""
                draftId = fresh.id
                editing = null
            }
            step = RxStep.review
            saveError = text
            return
        }
        val text = if (stockIsConnectionError(error)) L("We couldn’t reach PawMiaw, so it isn’t saved. What you wrote is still here: try again.")
        else StockRxWords.error(error, fallback = L("The prescription couldn’t be saved."))
        saveError = text
        Toast.error(text)
        val index = refusal?.body?.get("index").int
        if (index != null && index in items.indices) {
            val key = items[index].key
            step = RxStep.medicines
            editing = key
            if (refusal?.code == "dose_invalid") {
                launch {
                    delay(150)
                    form.check(listOf("$key-doseAmount" to L("The dose must be more than zero.")))
                }
            }
        }
    }
}

@Composable
fun StockPrescriptionSheet(
    /** The pet; null = choose the client and the pet first. */
    pet: StockRxPet?,
    /** A draft to finish. */
    draft: StockPrescription?,
    appointmentId: String? = null,
    modifier: Modifier = Modifier,
    onDone: (StockPrescription) -> Unit,
) {
    val model = remember { RxSheetModel(pet, draft, appointmentId) }
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    // The sheet's callbacks, as this composition has them (plain fields, not state).
    model.onDone = onDone
    model.dismiss = dismiss
    model.scope = scope
    val step = model.step
    val canSign = model.canSign

    ProviderModal(
        title = if (step == RxStep.who) L("New prescription") else if (draft != null) L("Finish the prescription") else L("Write a prescription"),
        subtitle = if (step == RxStep.who) L("Who is it for? Then the medicines.") else L("The medicines, what goes for all of them, then the review to sign."),
        systemImage = "pill",
        accent = ModalAccent.aqua,
        isDirty = model.changed || step != model.firstStep || (model.chosenPet != null && pet == null),
        form = model.form,
        primaryTitle = model.primaryTitle,
        isWorking = model.saving,
        steps = if (step != RxStep.who) {
            ModalSteps(
                listOf(L("Medicines") to "pill", L("Details") to "clipboard-list", (if (canSign) L("Review and sign") else L("Review")) to "pen-line"),
                current = max(0, step.ordinal - 1),
            )
        } else null,
        onBack = if (step != model.firstStep) ({ model.back() }) else null,
        secondaryActions = if (step != RxStep.who && (step != RxStep.review || canSign)) {
            listOf(ModalAction(L("Save draft"), icon = "file-plus-2", isWorking = model.saving) { model.launch { model.handleDraft() } })
        } else emptyList(),
        primaryIcon = if (step == RxStep.review) (if (canSign) "pen-line" else "file-plus-2") else null,
        onPrimary = { model.primary() },
        modifier = modifier,
    ) {
        if (step != RxStep.who) model.chosenPet?.let { RxContext(model, it) }
        model.saveError?.let { NoticeCard(tone = NoticeCard.Tone.problem, title = it, icon = "triangle-alert") }
        when (step) {
            RxStep.who -> RxWhoStep(model)
            RxStep.medicines -> RxMedicinesStep(model)
            RxStep.details -> RxDetailsStep(model)
            RxStep.review -> RxReviewStep(model)
        }
        Text(L("Format based on public sources; a professional must review it before use."), style = Theme.text(12), color = Theme.inkSoft)
    }

    LaunchedEffect(model.chosenPet?.petId) { model.loadContext() }
    LaunchedEffect(model.visitId) { model.fillVisitDiagnosis() }

    if (model.weighing) {
        AppSheet(onDismissRequest = { model.weighing = false }) {
            model.weighChart?.let { ClientsWeightSheet(chart = it, onSaved = { model.loadContext() }) }
        }
    }
    model.credentialAsk?.let { ask ->
        AppSheet(onDismissRequest = { model.credentialAsk = null }) {
            TeamCredentialSheet(
                memberId = "me",
                isSelf = true,
                country = Workspace.shared.current?.country,
                businessAddress = Workspace.shared.current?.address,
                types = ask.type?.let { listOf(it) },
                reason = ask.reason,
                onSaved = {
                    val id = ask.prescriptionId
                    model.credentialAsk = null
                    model.launch { model.signAfterCredential(id) }
                },
            )
        }
    }
}

/**
 * Whose it is, its weight and its class (web `ModalContextPills`: the client with its link
 * badge once the chart says, the pet with its face, the weight, "Weigh", the class).
 */
@Composable
private fun RxContext(model: RxSheetModel, who: StockRxPet) {
    val chart = model.chart
    val weightGrams = model.weightGrams
    ContextPills(client = who.clientName.nonEmpty ?: chart?.client?.name, linked = chart?.let { it.client?.householdId != null }) {
        RxContextPill(who.petName.nonEmpty ?: L("Patient"), face = true) {
            PetAvatar(name = who.petName, species = who.species, photoURL = who.photoUrl, size = 20.dp)
        }
        if (chart != null) {
            val weight = weightGrams?.let { "${StockDocWords.number(it.toDouble() / 1000)} kg" + (chart.weights?.latestAt?.let { at -> " · ${DateText.day(at)}" } ?: "") }
            RxContextPill(weight ?: L("No weight on file")) { Glyph("scale", 14.dp) }
        }
        if (chart != null && weightGrams == null && model.canWeigh && model.weighChart != null) {
            PMButton(L("Weigh"), systemImage = "scale", variant = ButtonVariant.outline, size = ButtonSize.compact) { model.weighing = true }
        }
        val schedule = model.schedule
        if (schedule != null) StockRxWords.schedule(schedule)?.let { StockRxControlledChip(label = it) }
    }
}

/**
 * One more pill in `ContextPills`' look (32 tall, 12 semibold, the muted fill with a hairline):
 * the pet with its face, the weight.
 */
@Composable
private fun RxContextPill(text: String, face: Boolean = false, mark: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .background(Theme.surfaceMuted, shape)
                .border(1.dp, Theme.borderSoft, shape)
                .padding(start = if (face) 6.dp else 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mark()
            Text(text, style = brandText(12, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RxWhoStep(model: RxSheetModel) {
    ClientPetPicker(
        client = stateOf({ model.pickedClient }, { next ->
            model.pickedClient = next
            if (model.chosenPet?.clientId != next?.id) model.chosenPet = null
        }),
        petIds = stateOf({ model.chosenPet?.let { listOf(it.petId) } ?: emptyList() }, { ids ->
            val client = model.pickedClient
            val id = ids.firstOrNull()
            val pet = if (client != null && id != null) client.activePets.firstOrNull { it.id == id } else null
            model.chosenPet = if (client != null && pet != null) {
                StockRxPet(clientId = client.id, petId = pet.id, petName = pet.name, species = pet.petSpecies, photoUrl = pet.photoUrl, clientName = client.ownerName)
            } else null
        }),
        petRequired = true,
        form = model.form,
    )
    NoticeCard(
        tone = NoticeCard.Tone.info,
        message = L("A prescription is written for a client’s pet. A walk-in customer isn’t on the list: add them as a client, with their pet, first."),
        icon = "info",
    )
}

@Composable
private fun RxMedicinesStep(model: RxSheetModel) {
    val items = model.items
    if (items.isEmpty()) {
        NoticeCard(tone = NoticeCard.Tone.info, title = L("No medicines on this prescription."), message = L("Add at least one medicine."), icon = "pill")
    }
    items.forEachIndexed { index, item -> key(item.key) { RxMedicineRow(model, item, index) } }
    model.removed?.let { (item, index) ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(LF("%@ was removed.", item.medicineName.nonEmpty ?: LF("Medicine %d", index + 1)), style = Theme.text(14), color = Theme.inkStrong, modifier = Modifier.weight(1f))
            PMButton(L("Undo"), systemImage = "undo-2", variant = ButtonVariant.ghost, size = ButtonSize.modal) { model.undoRemove() }
        }
    }
    PMButton(
        if (items.isEmpty()) L("Add medicine") else L("Add another medicine"),
        systemImage = "plus",
        variant = ButtonVariant.outline,
        fullWidth = true,
        enabled = model.canAdd,
    ) { model.addMedicine() }
    if (model.anyControlled) {
        Text(L("A controlled medicine goes on a prescription of its own (original and copy)."), style = Theme.text(12), color = Theme.inkSoft)
    } else if (items.size >= StockRxRules.maxItems) {
        Text(L("Up to 10 medicines per prescription."), style = Theme.text(12), color = Theme.inkSoft)
    } else if (items.size > 1) {
        Text(L("They print in this order."), style = Theme.text(12), color = Theme.inkSoft)
    }
    // The medicine being edited, under the list (the web's second column on a phone).
    val index = items.indexOfFirst { it.key == model.editing }
    if (index >= 0) {
        val item = items[index]
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FlowLayout(spacing = 8.dp) {
                Text(LF("Medicine %d", index + 1).uppercase(), style = brandText(12, FontWeight.Bold).tracking(12 * 0.14), color = Theme.inkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        L("Required to sign"),
                        style = brandText(10.4, FontWeight.Bold),
                        color = Theme.inkStrong,
                        modifier = Modifier
                            .background(Theme.wash(Theme.mango, 22.0), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(L("A draft saves without them."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
            key(item.key) {
                StockMedicineForm(
                    item = model.binding(item.key),
                    country = model.country,
                    weightGrams = model.weightGrams,
                    problems = model.problems[item.key] ?: emptyList(),
                    showDraftProblems = model.checked,
                    showSignProblems = model.signChecked,
                    allergies = model.allergies,
                    form = model.form,
                    allergySources = model.allergySources,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PMButton(L("Done with this medicine"), variant = ButtonVariant.outline) { model.editing = null }
            }
        }
    } else if (items.isNotEmpty()) {
        NoticeCard(tone = NoticeCard.Tone.info, message = L("Choose a medicine to edit it, or add another."), icon = "pencil")
    }
}

/** One medicine of the list (web: its number, its summary, its chips, then edit, duplicate, move, remove). */
@Composable
private fun RxMedicineRow(model: RxSheetModel, item: StockItemDraft, index: Int) {
    val on = item.key == model.editing
    val pending = (model.problems[item.key] ?: emptyList()).count { if (it.blocksDraft) (model.checked || !on) else (model.signChecked || !on) }
    val conflicts = StockRxRules.allergyConflicts(item, sources = model.allergySources)
    val summary = item.summary(model.language)
    val box = RoundedCornerShape(Theme.boxRadius)
    val label = model.medicineLabel(index)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (on) Theme.wash(Theme.aqua, 14.0) else Theme.surface, box)
            .border(2.dp, if (on) Theme.blue else Theme.borderSoft, box)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .plainButton { model.editing = if (on) null else item.key }
                .semantics { selected = on },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.padding(top = 2.dp).size(24.dp).background(Theme.wash(Theme.blue, 16.0), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${index + 1}", style = brandText(12, FontWeight.ExtraBold), color = Theme.inkStrong)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary.nonEmpty ?: L("New medicine"), style = Theme.text(14, FontWeight.Bold), color = if (summary.isEmpty()) Theme.inkSoft else Theme.inkStrong)
                FlowLayout(spacing = 6.dp) {
                    if (item.catalogItemId != null) StockRxChip(text = L("from the Catalog"), tone = Theme.blue)
                    if (item.controlSchedule.nonEmpty != null) StockRxControlledChip()
                    if (conflicts.isNotEmpty()) StockRxChip(text = L("Allergy"), tone = Theme.destructive, icon = "triangle-alert", strong = true)
                    if (pending > 0) StockRxChip(text = if (pending == 1) L("1 thing to complete") else LF("%d things to complete", pending), tone = Theme.mango, strong = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            PMButton(if (on) L("Done") else L("Edit"), systemImage = "pencil", variant = ButtonVariant.ghost, size = ButtonSize.modal) { model.editing = if (on) null else item.key }
            IconCircleButton(systemImage = "copy", label = LF("Duplicate %@", label), tint = Theme.inkStrong, size = 40.dp, bordered = false, enabled = model.canAdd) { model.duplicate(index) }
            IconCircleButton(systemImage = "arrow-up", label = LF("Move %@ up", label), tint = Theme.inkStrong, size = 40.dp, bordered = false, enabled = index != 0) { model.move(index, -1) }
            IconCircleButton(systemImage = "arrow-down", label = LF("Move %@ down", label), tint = Theme.inkStrong, size = 40.dp, bordered = false, enabled = index != model.items.size - 1) { model.move(index, 1) }
            Spacer(Modifier.weight(1f))
            IconCircleButton(systemImage = "trash-2", label = LF("Remove %@", label), tint = Theme.destructive, size = 40.dp, bordered = false) { model.remove(index) }
        }
    }
}

@Composable
private fun RxDetailsStep(model: RxSheetModel) {
    val (defaultDays, maxDays) = model.validity
    val schedule = model.schedule
    ModalSection(title = L("For the whole prescription")) {
        PMTextField(
            L("Diagnosis"),
            text = stateOf({ model.diagnosis }, { if (it != model.diagnosis) { model.diagnosis = it; model.touch() } }),
            hint = L("It prints on the prescription."),
            optional = true,
        )
        StockRxNumberField(
            label = L("Valid for"),
            text = stateOf({ model.validDays }, { if (it != model.validDays) { model.validDays = it; model.touch() } }),
            key = "validDays",
            form = model.form,
            prompt = "",
            hint = schedule?.let { LF("Blank: %d days for %@ (at most %d).", defaultDays, StockRxWords.schedule(it) ?: "", maxDays) } ?: L("Blank: 180 days (controlled medicines less)."),
            optional = true,
            suffix = L("days"),
        )
        PMTextField(
            L("Notes"),
            text = stateOf({ model.notes }, { if (it != model.notes) { model.notes = it; model.touch() } }),
            hint = LF("%d of 1000 characters", model.notes.length),
            optional = true,
            multiline = true,
        )
    }
    ModalSection(title = L("Refills"), subtitle = L("How many more times each medicine can be filled. They are changed on each medicine.")) {
        ListCard(style = ListCardStyle.box) {
            model.items.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                val refills = (StockItemDraft.number(item.refills) ?: 0.0).toInt()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.medicineName.nonEmpty ?: LF("Medicine %d", index + 1), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                    Text(if (refills > 0) (if (refills == 1) L("1 refill") else LF("%d refills", refills)) else L("No refills"), style = Theme.text(14), color = Theme.inkSoft)
                    PMButton(L("Change"), variant = ButtonVariant.link, size = ButtonSize.modal) {
                        model.step = RxStep.medicines
                        model.editing = item.key
                    }
                }
            }
        }
    }
    if (model.visitId != null && model.canDispenseAtVisit) {
        ModalSection(title = L("At this visit")) {
            ToggleRow(
                title = L("Hand it out at this visit (adds it to the bill)"),
                subtitle = L("Its Catalog medicines are added to this visit’s bill when you sign."),
                isOn = stateOf({ model.dispenseNow }, { if (it != model.dispenseNow) { model.dispenseNow = it; model.touch() } }),
            )
        }
    }
}

@Composable
private fun RxReviewStep(model: RxSheetModel) {
    val blockers = model.blockers
    val canSign = model.canSign
    val credentials = model.credentials
    val credential = credentials?.items?.firstOrNull { it.prescribesHere == true }
    val country = model.country
    val language = model.language
    if (blockers.isNotEmpty()) {
        NoticeCard(tone = NoticeCard.Tone.problem, icon = "circle-alert", content = {
            Text(if (canSign) L("Before signing, complete this:") else L("Before a vet signs it, this is missing:"), fontWeight = FontWeight.Bold)
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                blockers.forEach { blocker ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(blocker.about?.let { "$it: " } ?: "") }
                                append(blocker.text)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (blocker.itemKey != null || blocker.toDetails) {
                            PMButton(L("Fix"), variant = ButtonVariant.link, size = ButtonSize.modal) { model.fix(blocker) }
                        }
                    }
                }
            }
            Text(L("A draft saves without them."), style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 6.dp))
        })
    } else if (canSign) {
        NoticeCard(tone = NoticeCard.Tone.lime, title = L("Everything a signature needs is here."), icon = "badge-check")
    }
    model.warnings.forEach { warning ->
        NoticeCard(tone = if (warning.danger) NoticeCard.Tone.problem else NoticeCard.Tone.warning, title = warning.text, message = warning.detail, icon = "triangle-alert")
    }
    if (country?.uppercase() == "US" && model.anyControlled) {
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            message = L("Controlled substances can’t be prescribed electronically without EPCS-certified software. Print it and sign it by hand; it can’t be sent as a link."),
            icon = "triangle-alert",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!canSign) {
            RxReviewLine("lock", Theme.inkSoft, L("Your role can’t sign prescriptions. Save it as a draft: a vet with a credential signs it."))
        } else if (credential != null) {
            val words = listOf(if (language == "en") credential.label?.en else credential.label?.es, credential.number, credential.region).mapNotNull { it.nonEmpty }.joinToString(" ")
            RxReviewLine("id-card", Theme.blue, LF("You sign with %@.", words))
        } else if (credentials != null) {
            RxReviewLine("id-card", Theme.mango, L("You have no professional credential here yet. Signing asks for it, and the prescription is kept as a draft meanwhile."))
        }
        if (canSign && model.anyControlled && country?.uppercase() == "US" && credentials != null && credentials.canPrescribeControlled != true) {
            RxReviewLine("id-card", Theme.mango, L("A controlled medicine needs your DEA number. Add it and the prescription is signed."))
        }
        if (canSign) RxReviewLine("lock", Theme.inkSoft, L("A signed prescription can’t change — cancel it and write a new one."))
        if (canSign && model.anyControlled) {
            RxReviewLine("copy", Theme.inkSoft, L("It prints as an original and a copy.") + " " + L("A controlled prescription goes on paper, signed by hand — it can’t be sent as a link."))
        }
        val linked = model.chart?.pet?.linked
        if (canSign && linked != null) {
            RxReviewLine(
                "smartphone",
                Theme.inkSoft,
                if (linked) L("The family gets it in their PawMiaw app as soon as it is signed.") else L("The family isn’t on PawMiaw, so it doesn’t reach their app: print it for them."),
            )
        }
        if (canSign && model.dispenseNow) RxReviewLine("badge-check", Theme.blue, L("Its Catalog medicines are added to this visit’s bill when you sign."))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L("The prescription as it will print"), style = Theme.heading(16.8), color = Theme.inkStrong)
        StockPrescriptionDocument(header = model.previewHeader(credential), items = model.items.map { it.docItem }, preview = true)
    }
}

/** One line of what signing means (web: a 16 icon in its colour, 14 text). */
@Composable
private fun RxReviewLine(icon: String, tint: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Glyph(icon, 16.dp, tint = tint, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = Theme.text(14), color = Theme.inkStrong)
    }
}
