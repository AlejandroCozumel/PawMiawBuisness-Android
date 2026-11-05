package com.pawmiaw.business.hospital

import com.pawmiaw.business.*
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

//
//  HospitalModels.kt (iOS HospitalModels.swift)
//
//  The hospital's shapes (web hooks/use-provider-hospital.ts): the ward
//  board, one hospitalization with its treatment sheet, orders, vitals,
//  updates, rounds, the running bill and the end; on call and the settings.
//  Every field the server could leave out is nullable.
//

// MARK: - Pieces

@Serializable
data class HospVitals(
    val id: String,
    val recordedAt: String? = null,
    val notes: String? = null,
    /** "triage" (the quick vitals at arrival) or "hospital". */
    val source: String? = null,
    val recordedByName: String? = null,
    val voided: Boolean? = null,
    val temperatureC: Double? = null,
    val heartRate: Double? = null,
    val respiratoryRate: Double? = null,
    val painScore: Double? = null,
    val weightGrams: Double? = null,
    val mucousMembranes: String? = null,
    val crtSeconds: Double? = null,
)

@Serializable
data class HospRoomRef(
    val id: String,
    val name: String? = null,
    val kind: String? = null,
)

@Serializable
data class HospPatient(
    val providerClientPetId: String? = null,
    val name: String? = null,
    val species: String? = null,
    val breed: String? = null,
    val photoUrl: String? = null,
    val weightGrams: Double? = null,
    val allergies: List<String>? = null,
    val petId: String? = null,
    val deceasedAt: String? = null,
)

@Serializable
data class HospClientRef(
    val id: String,
    val name: String? = null,
    val phone: String? = null,
    val onPawMiaw: Boolean? = null,
)

@Serializable
data class HospPersonRef(
    val id: String,
    val name: String? = null,
    val image: String? = null,
)

@Serializable
data class HospAttention(
    val due: Int? = null,
    val late: Int? = null,
    val missed: Int? = null,
) {
    val d: Int get() = due ?: 0
    val l: Int get() = late ?: 0
    val m: Int get() = missed ?: 0
}

@Serializable
data class HospOpenDose(
    val orderId: String? = null,
    val label: String? = null,
    val scheduledFor: String? = null,
    val state: String? = null,
)

@Serializable
data class HospGateConsent(
    val required: Boolean? = null,
    val consentId: String? = null,
    val status: String? = null,
    val title: String? = null,
    val amountCents: Int? = null,
    val satisfied: Boolean? = null,
    /** missing | unsigned | amount_changed */
    val reason: String? = null,
)

@Serializable
data class HospGateDeposit(
    val amount: Int? = null,
    val paid: Int? = null,
    val satisfied: Boolean? = null,
    val waivedByTriage: Boolean? = null,
)

/** What admission waits on: the signed estimate and the deposit. */
@Serializable
data class HospGate(
    val consent: HospGateConsent? = null,
    val deposit: HospGateDeposit? = null,
    val ready: Boolean? = null,
)

/** A board row or a patient's hospitalization (a waiting one carries its gate). */
@Serializable
data class HospSummary(
    val id: String,
    val status: String? = null,
    val emergency: Boolean? = null,
    val triagePriority: String? = null,
    val reason: String? = null,
    val internalNote: String? = null,
    val locationId: String? = null,
    val room: HospRoomRef? = null,
    val cageLabel: String? = null,
    val patient: HospPatient? = null,
    val client: HospClientRef? = null,
    val attending: HospPersonRef? = null,
    val appointmentId: String? = null,
    val requestedAt: String? = null,
    val admittedAt: String? = null,
    val endedAt: String? = null,
    val dayNumber: Int? = null,
    val condition: String? = null,
    val attention: HospAttention? = null,
    val nextDueAt: String? = null,
    val lastVitals: HospVitals? = null,
    val roundsSignedToday: Boolean? = null,
    val estimateAmount: Int? = null,
    val depositAmount: Int? = null,
    val currency: String? = null,
    val billTotal: Int? = null,
    val gate: HospGate? = null,
)

@Serializable
data class HospWardRoom(
    val id: String,
    val name: String? = null,
    val kind: String? = null,
    val capacity: Int? = null,
    val locationId: String? = null,
    val occupied: Int? = null,
)

@Serializable
data class HospBoardCounts(
    val pending: Int? = null,
    val admitted: Int? = null,
    val due: Int? = null,
    val late: Int? = null,
    val missed: Int? = null,
    val roundsPending: Int? = null,
)

@Serializable
data class HospBoard(
    val now: String? = null,
    val patients: List<HospSummary>? = null,
    val recent: List<HospSummary>? = null,
    val rooms: List<HospWardRoom>? = null,
    val counts: HospBoardCounts? = null,
)

// MARK: - The sheet

@Serializable
data class HospAdministration(
    val id: String,
    val orderId: String? = null,
    val scheduledFor: String? = null,
    val dayKey: String? = null,
    /** given | not_given */
    val status: String? = null,
    val givenAt: String? = null,
    val doseAmount: Double? = null,
    val doseUnit: String? = null,
    val notGivenReason: String? = null,
    val initials: String? = null,
    val notes: String? = null,
    val givenByName: String? = null,
    val chargedAmount: Int? = null,
    val voidedAt: String? = null,
    val voidReason: String? = null,
)

@Serializable
data class HospSheetSlot(
    val scheduledFor: String,
    /** upcoming | due | late | missed | given | given_late | not_given */
    val state: String? = null,
    val administration: HospAdministration? = null,
) {
    val id: String get() = scheduledFor
}

@Serializable
data class HospSheetRow(
    val orderId: String,
    val slots: List<HospSheetSlot>? = null,
    val extra: List<HospAdministration>? = null,
    val voided: List<HospAdministration>? = null,
) {
    val id: String get() = orderId
}

@Serializable
data class HospSheet(
    val day: String? = null,
    val days: List<String>? = null,
    val today: String? = null,
    val rows: List<HospSheetRow>? = null,
)

@Serializable
data class HospCatalogRef(
    val id: String,
    val name: String? = null,
    val itemType: String? = null,
    val unitAmount: Int? = null,
)

@Serializable
data class HospOrder(
    val id: String,
    /** medication | fluid | feeding | monitoring | procedure | other */
    val kind: String? = null,
    val label: String? = null,
    val catalogItem: HospCatalogRef? = null,
    val doseAmount: Double? = null,
    val doseUnit: String? = null,
    val route: String? = null,
    val rateMlPerHour: Double? = null,
    /** times | every | once | prn */
    val scheduleKind: String? = null,
    val scheduleTimes: List<String>? = null,
    val everyHours: Int? = null,
    val onceAt: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val chargeable: Boolean? = null,
    val chargeQuantity: Int? = null,
    val instructions: String? = null,
    /** active | stopped */
    val status: String? = null,
    val stoppedAt: String? = null,
    val stopReason: String? = null,
    val revision: Int? = null,
    val createdByName: String? = null,
    val updatedByName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    /** Its label, else its catalog item. */
    val name: String get() = label.nonEmpty ?: catalogItem?.name ?: ""
    val isStopped: Boolean get() = status == "stopped"
}

@Serializable
data class HospUpdate(
    val id: String,
    val message: String? = null,
    val condition: String? = null,
    val photoUrl: String? = null,
    /** owner | internal */
    val visibility: String? = null,
    val authorName: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class HospRound(
    val id: String,
    val day: String? = null,
    val note: String? = null,
    val signedByName: String? = null,
    val signedAt: String? = null,
)

@Serializable
data class HospTakeHome(
    val id: String,
    val name: String? = null,
    val catalogItemId: String? = null,
    val doseAmount: Double? = null,
    val doseUnit: String? = null,
    val route: String? = null,
    val everyHours: Int? = null,
    val durationDays: Int? = null,
    val quantity: Int? = null,
    val instructions: String? = null,
    val directions: String? = null,
)

@Serializable
data class HospBillLine(
    val id: String,
    val label: String? = null,
    val description: String? = null,
    val quantity: Double? = null,
    val unitAmount: Int? = null,
    val total: Int? = null,
    val sourceType: String? = null,
    val courtesy: Boolean? = null,
) {
    /** The zero-price rule: one unpriced line keeps the bill a draft. */
    val needsPrice: Boolean get() = (unitAmount ?: 0) <= 0 && courtesy != true
}

@Serializable
data class HospEarlierBill(
    val id: String,
    val status: String? = null,
    val totalAmount: Int? = null,
)

@Serializable
data class HospDailyFee(
    val itemId: String? = null,
    val name: String? = null,
    val unitAmount: Int? = null,
)

@Serializable
data class HospBill(
    val invoiceId: String? = null,
    val status: String? = null,
    val lines: List<HospBillLine>? = null,
    val totalAmount: Int? = null,
    val taxAmount: Int? = null,
    val pricesIncludeTax: Boolean? = null,
    val earlierBills: List<HospEarlierBill>? = null,
    val depositsPaid: Int? = null,
    val currency: String? = null,
    val dailyFee: HospDailyFee? = null,
)

@Serializable
data class HospEnd(
    /** discharged | transferred | deceased */
    val kind: String? = null,
    val at: String? = null,
    val byName: String? = null,
    val diagnosis: String? = null,
    val summary: String? = null,
    val instructions: String? = null,
    val followUpAt: String? = null,
    val followUpId: String? = null,
    val transferDestination: String? = null,
    val transferReason: String? = null,
    val deathCause: String? = null,
    val deathConsentId: String? = null,
    val deathOverrideReason: String? = null,
    val aftercare: String? = null,
    val aftercareNote: String? = null,
    val undoableUntil: String? = null,
)

@Serializable
data class HospDeathUndone(
    val at: String? = null,
    val reason: String? = null,
)

@Serializable
data class HospSettingsInline(
    val lateGraceMinutes: Int? = null,
    val missedAfterMinutes: Int? = null,
)

@Serializable
data class HospPrescriptionResult(
    val ok: Boolean? = null,
    val code: String? = null,
    val error: String? = null,
    val prescriptions: List<Entry>? = null,
) {
    @Serializable
    data class Entry(val id: String? = null, val folio: String? = null)
}

@Serializable
data class HospDischargeResult(
    val id: String? = null,
    val records: Int? = null,
    val cabinetItems: Int? = null,
    val followUpId: String? = null,
    val prescription: HospPrescriptionResult? = null,
)

@Serializable
data class HospAdmitError(
    val error: String? = null,
    val code: String? = null,
)

@Serializable
data class HospConsentRequest(
    val consentId: String? = null,
    val notified: Boolean? = null,
)

/** One hospitalization: the summary's facts and the whole record. */
@Serializable
data class HospDetail(
    val id: String,
    val status: String? = null,
    val emergency: Boolean? = null,
    val triagePriority: String? = null,
    val reason: String? = null,
    val internalNote: String? = null,
    val locationId: String? = null,
    val room: HospRoomRef? = null,
    val cageLabel: String? = null,
    val patient: HospPatient? = null,
    val client: HospClientRef? = null,
    val attending: HospPersonRef? = null,
    val appointmentId: String? = null,
    val requestedAt: String? = null,
    val admittedAt: String? = null,
    val endedAt: String? = null,
    val dayNumber: Int? = null,
    val condition: String? = null,
    val attention: HospAttention? = null,
    val openDoses: List<HospOpenDose>? = null,
    val nextDueAt: String? = null,
    val lastVitals: HospVitals? = null,
    val roundsSignedToday: Boolean? = null,
    val estimateAmount: Int? = null,
    val depositAmount: Int? = null,
    val currency: String? = null,
    val billTotal: Int? = null,

    val gate: HospGate? = null,
    val consentOverrideReason: String? = null,
    val depositOverrideReason: String? = null,
    val gateOverriddenByName: String? = null,
    val admittedByName: String? = null,
    val cancelReason: String? = null,
    val settings: HospSettingsInline? = null,
    val timeZone: String? = null,
    val orders: List<HospOrder>? = null,
    val sheet: HospSheet? = null,
    val vitals: List<HospVitals>? = null,
    val updates: List<HospUpdate>? = null,
    val rounds: List<HospRound>? = null,
    val takeHome: List<HospTakeHome>? = null,
    val end: HospEnd? = null,
    val deathUndone: HospDeathUndone? = null,
    val estimateConsentId: String? = null,
    val deathConsentId: String? = null,
    val bill: HospBill? = null,
    // Only on the create answer.
    val consentRequest: HospConsentRequest? = null,
    val consentError: String? = null,
    val admitError: HospAdmitError? = null,
    // Only on the discharge answer.
    val discharge: HospDischargeResult? = null,
) {
    val zone: ZoneId get() = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Workspace.shared.timeZone
    val petName: String get() = patient?.name.nonEmpty ?: L("Patient")
    val isAdmitted: Boolean get() = status == "admitted"
    val isPending: Boolean get() = status == "pending"
    val allOrders: List<HospOrder> get() = orders ?: emptyList()
    val money: String get() = currency ?: Workspace.shared.currency
}

@Serializable
data class HospOrderEvent(
    val id: String,
    /** created | changed | stopped */
    val action: String? = null,
    val changes: JSONValue? = null,
    val reason: String? = null,
    val actorName: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class HospOrderEvents(val events: List<HospOrderEvent>? = null)

@Serializable
data class HospSettings(
    val lateGraceMinutes: Int? = null,
    val missedAfterMinutes: Int? = null,
    val feeGraceMinutes: Int? = null,
    val visitingInfo: String? = null,
    val dailyFeeItemId: String? = null,
    val emergencyDepositAmount: Int? = null,
    val afterHoursSurchargeItemId: String? = null,
    val currency: String? = null,
)

@Serializable
data class HospOnCallShift(
    val id: String,
    val locationId: String? = null,
    val locationName: String? = null,
    val member: HospPersonRef? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val note: String? = null,
)

@Serializable
data class HospOnCallList(
    val shifts: List<HospOnCallShift>? = null,
    val now: List<HospOnCallShift>? = null,
)

// MARK: - Shared lookups (members, rooms, catalog, clients, consents)

@Serializable
data class HospMember(
    val id: String,
    val name: String? = null,
    val userName: String? = null,
    val email: String? = null,
    val image: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val effectivePermissions: List<String>? = null,
    val isCurrentMember: Boolean? = null,
    val locationIds: List<String>? = null,
) {
    val label: String get() = displayName.nonEmpty ?: name.nonEmpty ?: userName.nonEmpty ?: email ?: ""
    fun can(permission: Permission): Boolean = (effectivePermissions ?: emptyList()).contains(permission.rawValue)
}

@Serializable
data class HospRoom(
    val id: String,
    val name: String? = null,
    val kind: String? = null,
    val capacity: Int? = null,
    val status: String? = null,
    val locationId: String? = null,
) {
    val isHospital: Boolean get() = kind == "ward" || kind == "cage"
}

@Serializable
data class HospCatalogItem(
    val id: String,
    val name: String,
    val itemType: String? = null,
    val defaultUnitAmount: Int? = null,
    val currency: String? = null,
)

@Serializable
data class HospClientPet(
    val id: String,
    val petName: String? = null,
    val petSpecies: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
)

@Serializable
data class HospClient(
    val id: String,
    val ownerName: String? = null,
    val ownerPhone: String? = null,
    val householdId: String? = null,
    val pets: List<HospClientPet>? = null,
)

@Serializable
data class HospConsentTerms(
    val version: String? = null,
    val title: String? = null,
    val text: String? = null,
)

@Serializable
data class HospConsent(
    val id: String,
    val status: String? = null,
    val title: String? = null,
    val body: String? = null,
    val petName: String? = null,
    val amountCents: Int? = null,
    val currency: String? = null,
    val terms: HospConsentTerms? = null,
)

@Serializable
data class HospConsentAsked(val consentId: String? = null, val notified: Boolean? = null, val status: String? = null)

@Serializable
data class HospRegister(
    val id: String,
    val name: String? = null,
    val openSessionId: String? = null,
    val locationId: String? = null,
)

// MARK: - The API

object HospAPI {
    fun detailPath(id: String): String =
        "/provider-hospital/hospitalizations/${android.net.Uri.encode(id, "/:@!$&'()*+,;=-._~")}"

    /** Every write answers the detail; other screens hear about it. */
    suspend fun write(id: String, path: String, method: String = "POST", body: Map<String, Any?> = emptyMap()): HospDetail {
        val data = API.data(method, detailPath(id) + path, emptyMap(), API.jsonBody(body))
        val detail: HospDetail = API.decode(data)
        announceDataChanged()
        return detail
    }

    suspend fun members(): List<HospMember> {
        val clinic = Workspace.shared.current?.clinicId ?: return emptyList()
        return runCatching { API.get<List<HospMember>>("/provider-workspaces/$clinic/members") }.getOrNull() ?: emptyList()
    }

    suspend fun wards(locationId: String?): List<HospRoom> {
        val rooms: List<HospRoom> = runCatching { API.get<List<HospRoom>>("/provider-rooms", mapOf("locationId" to locationId)) }.getOrNull() ?: emptyList()
        return rooms.filter { it.isHospital }
    }

    /** A page of the Catalog's active items matching `search` (20 at a time, the server's total). */
    suspend fun catalogPage(search: String, itemType: String? = null, offset: Int): Page<HospCatalogItem> =
        API.get("/provider-catalog", mapOf(
            "limit" to ClientSearch.pageSize.toString(), "offset" to offset.toString(), "search" to search, "itemType" to itemType, "active" to "true",
        ))

    suspend fun catalog(search: String, itemType: String? = null, limit: Int = 8): List<HospCatalogItem> {
        val page: Page<HospCatalogItem>? = runCatching {
            API.get<Page<HospCatalogItem>>("/provider-catalog", mapOf(
                "limit" to "$limit", "offset" to "0", "search" to search.nonEmpty, "itemType" to itemType, "active" to "true",
            ))
        }.getOrNull()
        return page?.items ?: emptyList()
    }

    suspend fun askConsent(id: String, kind: String, notify: Boolean): HospConsentAsked {
        val answer: HospConsentAsked = API.post(detailPath(id) + "/consents", mapOf("kind" to kind, "notify" to notify))
        announceDataChanged()
        return answer
    }
}

// MARK: - Words for the server's values (hospital-copy.ts)

object HospWords {
    fun status(value: String?): String = when (value) {
        "pending" -> L("Waiting for admission")
        "admitted" -> L("Hospitalized")
        "discharged" -> L("Discharged")
        "transferred" -> L("Transferred")
        "deceased" -> L("Passed away")
        "cancelled" -> L("Cancelled")
        else -> value ?: ""
    }

    fun triage(value: String?): String = when (value) {
        "critical" -> L("Critical")
        "urgent" -> L("Urgent")
        "stable" -> L("Stable")
        else -> value ?: ""
    }

    fun condition(value: String?): String = when (value) {
        "improving" -> L("Improving")
        "stable" -> L("Stable")
        "guarded" -> L("Guarded")
        "critical" -> L("Critical")
        else -> value ?: ""
    }

    fun kind(value: String?): String = when (value) {
        "medication" -> L("Medicine")
        "fluid" -> L("Fluids")
        "feeding" -> L("Feeding")
        "monitoring" -> L("Monitoring")
        "procedure" -> L("Procedure")
        "other" -> L("Other")
        else -> value ?: ""
    }

    fun route(value: String?): String = when (value) {
        "oral" -> L("Oral")
        "iv" -> L("IV")
        "sc" -> L("Subcutaneous")
        "im" -> L("Intramuscular")
        "topical" -> L("Topical")
        "ophthalmic" -> L("Eye")
        "otic" -> L("Ear")
        "inhaled" -> L("Inhaled")
        "rectal" -> L("Rectal")
        "other" -> L("Other route")
        else -> value ?: ""
    }

    fun schedule(value: String?): String = when (value) {
        "times" -> L("At set times")
        "every" -> L("Every few hours")
        "once" -> L("Once")
        "prn" -> L("As needed")
        else -> value ?: ""
    }

    fun notGiven(value: String?): String = when (value) {
        "refused" -> L("Refused it")
        "vomited" -> L("Vomited")
        "held_by_vet" -> L("Held by the vet")
        "fasting" -> L("Fasting")
        "unavailable" -> L("Not available")
        "other" -> L("Other reason")
        else -> value ?: ""
    }

    fun slot(value: String?): String = when (value) {
        "upcoming" -> L("Upcoming")
        "due" -> L("Due now")
        "late" -> L("Late")
        "missed" -> L("Missed")
        "given" -> L("Given")
        "given_late" -> L("Given late")
        "not_given" -> L("Not given")
        else -> value ?: ""
    }

    fun mucous(value: String?): String = when (value) {
        "pink" -> L("Pink")
        "pale" -> L("Pale")
        "white" -> L("White")
        "cyanotic" -> L("Blue (cyanotic)")
        "icteric" -> L("Yellow (icteric)")
        "injected" -> L("Red (injected)")
        "muddy" -> L("Muddy")
        else -> value ?: ""
    }

    fun deathCause(value: String?): String = when (value) {
        "euthanasia" -> L("Euthanasia")
        "natural" -> L("Natural causes")
        "unknown" -> L("Unknown")
        else -> value ?: ""
    }

    fun aftercare(value: String?): String = when (value) {
        "cremation_individual" -> L("Individual cremation")
        "cremation_communal" -> L("Communal cremation")
        "burial" -> L("Burial")
        "family_takes" -> L("The family takes them home")
        "other" -> L("Other")
        else -> value ?: ""
    }

    /** A bill's status, the Invoices page's words. */
    fun invoiceStatus(value: String?): String = when (value) {
        "draft" -> L("Draft")
        "review" -> L("In review")
        "unpriced" -> L("Needs a price")
        "ready_for_payment", "open" -> L("Unpaid")
        "partially_paid" -> L("Part paid")
        "overdue", "late" -> L("Overdue")
        "paid" -> L("Paid")
        "returned" -> L("Returned")
        "refunded" -> L("Refunded")
        "canceled", "cancelled", "void" -> L("Canceled")
        else -> value ?: ""
    }

    val kinds = listOf("medication", "fluid", "feeding", "monitoring", "procedure", "other")
    val routes = listOf("oral", "iv", "sc", "im", "topical", "ophthalmic", "otic", "inhaled", "rectal", "other")
    val homeRoutes = listOf("oral", "topical", "ophthalmic", "otic", "sc", "im", "iv", "inhaled", "rectal", "other")
    val schedules = listOf("times", "every", "once", "prn")
    val reasons = listOf("refused", "vomited", "held_by_vet", "fasting", "unavailable", "other")
    val slotStates = listOf("upcoming", "due", "late", "missed", "given", "given_late", "not_given")
    val mucousOptions = listOf("pink", "pale", "white", "cyanotic", "icteric", "injected", "muddy")
    val priorities = listOf("critical", "urgent", "stable")
    val conditions = listOf("improving", "stable", "guarded", "critical")
    val causes = listOf("natural", "euthanasia", "unknown")
    val aftercares = listOf("cremation_individual", "cremation_communal", "burial", "family_takes", "other")

    /** Triage in the six states: critical red, urgent mango, stable green. */
    fun triageState(value: String?): ProviderState = when (value) {
        "critical" -> ProviderState.problem
        "urgent" -> ProviderState.waiting
        "stable" -> ProviderState.done
        else -> ProviderState.closed
    }

    fun conditionState(value: String?): ProviderState = when (value) {
        "improving" -> ProviderState.active
        "stable" -> ProviderState.upcoming
        "guarded" -> ProviderState.waiting
        "critical" -> ProviderState.problem
        else -> ProviderState.closed
    }
}

// MARK: - The server's refusals (hospitalError)

/** A refusal from the hospital endpoints, in the reader's language. */
fun hospError(error: Throwable, fallback: String = "Something went wrong. Try again."): String {
    if (error is APIError) {
        val api = error
        if (api.isOffline) return api.message
        if (api.code == "room_full" && LocalizationManager.currentLanguage != AppLanguage.spanish && api.message.isNotEmpty()) return api.message
        if (api.code == "forbidden" && api.message.isNotEmpty()) return L(api.message)
        api.code?.let { code -> hospKnownError(code)?.let { return it } }
        if (api.message.isNotEmpty() && (api.isRefusal || LocalizationManager.currentLanguage != AppLanguage.spanish)) return api.message
    }
    return L(fallback)
}

/** The same words, for a refusal sent back inside an answer (`{ code, error }`). */
fun hospRefusalText(code: String?, error: String?, fallback: String): String {
    code?.let { hospKnownError(it)?.let { known -> return known } }
    val text = error.nonEmpty
    if (text != null && LocalizationManager.currentLanguage != AppLanguage.spanish) return text
    return L(fallback)
}

/** The refusal carrying the hospital's own words (for a ConfirmSheet, which shows the error's message). */
fun hospRefusal(error: Throwable, fallback: String): APIError {
    val api = error as? APIError
    return APIError(api?.status ?: 400, hospError(error, fallback), api?.code, emptyMap())
}

private fun hospKnownError(code: String): String? = when (code) {
    "not_found" -> L("This hospitalization was not found.")
    "patient_not_found" -> L("That patient was not found.")
    "patient_deceased" -> L("This patient is recorded as deceased.")
    "reason_required" -> L("Say why.")
    "invalid_priority" -> L("Choose critical, urgent or stable.")
    "invalid_amount" -> L("Enter a valid amount.")
    "deposit_over_estimate" -> L("The deposit can’t be more than the estimate.")
    "visit_not_found" -> L("That visit was not found.")
    "visit_mismatch" -> L("That visit is for another pet.")
    "visit_closed" -> L("That visit is closed.")
    "room_not_found" -> L("That ward or cage was not found.")
    "not_hospital_room" -> L("That room is not a ward or a cage — mark it as one in Settings → Rooms.")
    "invalid_location" -> L("That location can’t take it.")
    "room_full" -> L("That ward or cage is full.")
    "invalid_member" -> L("That person is not on your team.")
    "already_hospitalized" -> L("This patient is already in the hospital.")
    "estimate_required" -> L("Enter the estimate before asking the family to sign it.")
    "template_missing" -> L("There is no consent template for this — add one in Settings → Consents.")
    "hospitalization_closed" -> L("This hospitalization is over.")
    "not_pending" -> L("Only a hospitalization not admitted yet can be cancelled — discharge the patient instead.")
    "not_admitted" -> L("The patient isn’t admitted.")
    "consent_required" -> L("The family hasn’t signed the consent yet.")
    "deposit_required" -> L("The deposit hasn’t been paid yet.")
    "override_forbidden" -> L("Only owners and admins can go ahead without it.")
    "deposit_override_forbidden" -> L("Only owners and admins can start before the deposit is paid.")
    "override_reason_required" -> L("Say why you go ahead without it.")
    "invalid_kind" -> L("Choose what kind of order it is.")
    "invalid_schedule" -> L("Choose when it is given.")
    "invalid_route" -> L("Choose the route from the list.")
    "invalid_item" -> L("That item is not in your catalog.")
    "label_required" -> L("Name the order.")
    "invalid_dose" -> L("Enter a dose greater than zero.")
    "dose_required" -> L("A medicine needs its dose and unit.")
    "rate_required" -> L("Fluids need their rate (ml/h).")
    "invalid_time" -> L("Use a valid date and time.")
    "too_many_times" -> L("At most 24 times a day.")
    "invalid_quantity" -> L("Charge between 1 and 1000 per dose.")
    "order_not_found" -> L("That order was not found.")
    "order_stopped" -> L("This order was stopped — write a new one.")
    "stale_order" -> L("Someone changed this order in the meantime — review it again.")
    "invalid_status" -> L("Given or not given?")
    "future_time" -> L("That time is in the future.")
    "before_admission" -> L("That time is before the admission.")
    "not_scheduled" -> L("That time is not on this order’s schedule.")
    "already_recorded" -> L("This dose is already on the sheet.")
    "open_doses" -> L("Some doses on the sheet aren’t marked yet. Mark each as given or not given before the patient leaves.")
    "order_has_doses" -> L("This order already has doses on the sheet. Stop it and write the new schedule as a new order.")
    "not_due_yet" -> L("This dose isn’t due yet. Mark it when it is given, or use Extra dose.")
    "administration_not_found" -> L("That dose is not on the sheet.")
    "vitals_not_found" -> L("Those vitals were not found.")
    "invalid_vitals" -> L("Check the vitals: one is out of range.")
    "message_required" -> L("Write the update.")
    "invalid_condition" -> L("Choose the condition from the list.")
    "invalid_photo" -> L("Use a jpg, png or webp photo.")
    "photo_too_large" -> L("The photo is too large (5 MB max).")
    "photo_upload_failed" -> L("The photo could not be uploaded — try again.")
    "invalid_day" -> L("Choose a valid day.")
    "future_day" -> L("Rounds are signed for today or an earlier day.")
    "already_signed" -> L("Rounds for that day are already signed.")
    "summary_required" -> L("Write the discharge summary.")
    "too_many_items" -> L("At most 20 medicines to take home.")
    "take_home_name_required" -> L("Name each medicine to take home.")
    "invalid_cause" -> L("Choose euthanasia, natural or unknown.")
    "invalid_aftercare" -> L("Choose the aftercare from the list.")
    "undo_window_passed" -> L("It can only be undone within 24 hours.")
    "shift_overlap" -> L("That person is already on call then.")
    "invalid_shift" -> L("Check the shift: it ends after it starts and lasts at most 7 days.")
    "shift_past" -> L("That shift is already over.")
    "invalid_range" -> L("Use a valid range.")
    "hospital_room" -> L("Wards and cages hold hospitalized patients only — choose a room for visits.")
    "forbidden" -> L("You don’t have access to this.")
    "member_required" -> L("Only a team member can sign a prescription.")
    "credential_required" -> L("Add your professional license to your profile to sign prescriptions.")
    "prescriptions_unavailable" -> L("Prescriptions are not available yet.")
    "prescription_failed" -> L("The prescription could not be written.")
    else -> null
}

// MARK: - Times, numbers, typed values (lib/hospital.ts)

object HospFmt {
    /** "2:00 p.m." on the hospital's clock. */
    fun clock(iso: String?, zone: ZoneId): String = DateText.time(DateText.parse(iso), zone)

    /** "4 nov, 2:00 p.m." */
    fun dateTime(iso: String?, zone: ZoneId): String {
        val date = DateText.parse(iso) ?: return ""
        return format(date, "dMMMjmm", zone)
    }

    fun dateTime(date: Instant, zone: ZoneId): String = format(date, "dMMMjmm", zone)

    /** A day key (yyyy-MM-dd) as "mié 4 nov" (or the long "miércoles 4 de noviembre", or "4 nov"). */
    fun day(key: String?, style: DayStyle = DayStyle.short, zone: ZoneId): String {
        val date = key?.let { dayDate(it, zone) } ?: return key ?: ""
        return when (style) {
            DayStyle.short -> format(date, "EEEdMMM", zone)
            DayStyle.long -> format(date, "EEEEdMMMM", zone)
            DayStyle.compact -> format(date, "dMMM", zone)
        }
    }

    enum class DayStyle { short, long, compact }

    /** Noon of a day key on a clock. */
    fun dayDate(key: String, zone: ZoneId): Instant? =
        runCatching { LocalDate.parse(key.take(10)).atStartOfDay(zone).toInstant().plusSeconds(12 * 3600) }.getOrNull()

    /** The day key an instant falls on, on a clock. */
    fun dayKey(iso: String?, zone: ZoneId): String? = DateText.parse(iso)?.let { DateText.dayKey(it, zone) }

    /** "14:00" said the way the sheet says times ("2:00 p.m."). */
    fun timeOfDay(value: String): String {
        val parts = value.split(":").filter { it.isNotEmpty() }
        if (parts.size != 2) return value
        val h = parts[0].toIntOrNull() ?: return value
        val m = parts[1].toIntOrNull() ?: return value
        // Like the Swift calendar, hours or minutes past their range roll over.
        val date = LocalDate.of(2000, 1, 1).atStartOfDay(ZoneOffset.UTC).plusHours(h.toLong()).plusMinutes(m.toLong()).toInstant()
        return format(date, "jmm", ZoneOffset.UTC)
    }

    private fun format(date: Instant, template: String, zone: ZoneId): String = DateText.format(date, template, zone)

    /** A number as people write it: "38.5", "110". */
    fun number(value: Double?, digits: Int = 3): String {
        if (value == null) return ""
        if (value == Math.rint(value)) return value.toInt().toString()
        val f = NumberFormat.getNumberInstance(appLocale).apply {
            isGroupingUsed = false
            maximumFractionDigits = digits
            minimumFractionDigits = 0
        }
        return f.format(value)
    }

    /** "5 mg". */
    fun dose(amount: Double?, unit: String?): String {
        if (amount == null) return ""
        val text = number(amount)
        return unit.nonEmpty?.let { "$text $it" } ?: text
    }

    /** A plain decimal, as Swift's `Double(_:)` reads one (Kotlin's parser would also take "5d" or "5f"). */
    private val decimalNumber = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /** A number typed with a comma or a point: null when empty, NaN when not a number. */
    fun read(text: String): Double? {
        val trimmed = text.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }.replace(",", ".")
        if (trimmed.isEmpty()) return null
        if (!decimalNumber.matches(trimmed)) return Double.NaN
        return trimmed.toDoubleOrNull() ?: Double.NaN
    }

    /** Dose, route and rate on one line. */
    fun orderLine(order: HospOrder): String =
        listOfNotNull(
            dose(order.doseAmount, order.doseUnit).nonEmpty,
            order.route?.let { HospWords.route(it) },
            order.rateMlPerHour?.let { LF("%@ ml/h", number(it)) },
        ).joinToString(" · ")

    /** What an order's schedule says, in words. */
    fun schedule(order: HospOrder, zone: ZoneId): String = when (order.scheduleKind) {
        "times" -> {
            val times = order.scheduleTimes ?: emptyList()
            if (times.isEmpty()) L("At set times") else times.joinToString(" · ") { timeOfDay(it) }
        }
        "every" -> LF("Every %@ h", order.everyHours?.toString() ?: "?")
        "once" -> if (order.onceAt != null) LF("Once, %@", dateTime(order.onceAt, zone)) else L("Once")
        "prn" -> L("As needed")
        else -> ""
    }

    private val meridiem = Regex("\\s*([ap])\\.?\\s?m\\.?(?![a-z])")
    private val timePattern = Regex("^(\\d{1,2})(?::?(\\d{2}))?(am|pm)?$")

    /** Times of day typed as "8:00, 20:00", "08 20" or "8 am, 2:30 p.m." → sorted HH:MM; null when one is not a time. */
    fun parseTimesOfDay(text: String): List<String>? {
        val joined = meridiem.replace(text.lowercase(), "$1m")
        val parts = joined.split(' ', ',', ';', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val out = mutableSetOf<String>()
        for (part in parts) {
            val match = timePattern.find(part) ?: return null
            fun group(i: Int): String? = match.groups[i]?.value
            var hours = group(1)?.toIntOrNull() ?: 0
            val minutes = (group(2) ?: "0").toIntOrNull() ?: 0
            val half = group(3)
            if (half != null) {
                if (hours < 1 || hours > 12) return null
                hours = (hours % 12) + (if (half == "pm") 12 else 0)
            }
            if (hours > 23 || minutes > 59) return null
            out.add(String.format(Locale.US, "%02d:%02d", hours, minutes))
        }
        return out.sorted()
    }

    /** Out of the usual range for a dog or cat (a hint on the table, never a diagnosis). */
    fun outOfRange(metric: HospMetric, value: Double, species: String?): Boolean {
        val cat = species?.lowercase() == "cat"
        return when (metric) {
            HospMetric.temperatureC -> value < 37.5 || value > 39.4
            HospMetric.heartRate -> if (cat) (value < 120 || value > 220) else (value < 60 || value > 160)
            HospMetric.respiratoryRate -> if (cat) (value < 16 || value > 40) else (value < 10 || value > 40)
            HospMetric.painScore -> value >= 4
            HospMetric.weightGrams -> false
        }
    }
}

/** Swift `.rounded()`: to the nearest, halves away from zero. */
private fun Double.roundedHalfAway(): Double = if (this < 0) -Math.round(-this).toDouble() else Math.round(this).toDouble()

enum class HospMetric(val rawValue: String) {
    temperatureC("temperatureC"), heartRate("heartRate"), respiratoryRate("respiratoryRate"), painScore("painScore"), weightGrams("weightGrams");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            temperatureC -> L("Temperature")
            heartRate -> L("Heart rate")
            respiratoryRate -> L("Breathing")
            painScore -> L("Pain")
            weightGrams -> L("Weight")
        }

    val unit: String
        get() = when (this) {
            temperatureC -> "°C"
            heartRate -> "bpm"
            respiratoryRate -> "/min"
            painScore -> "/10"
            weightGrams -> "kg"
        }

    val hue: Hue
        get() = when (this) {
            temperatureC -> Hue.peach
            heartRate -> Hue.lavender
            respiratoryRate -> Hue.sky
            painScore -> Hue.butter
            weightGrams -> Hue.mint
        }

    fun value(vitals: HospVitals): Double? = when (this) {
        temperatureC -> vitals.temperatureC
        heartRate -> vitals.heartRate
        respiratoryRate -> vitals.respiratoryRate
        painScore -> vitals.painScore
        weightGrams -> vitals.weightGrams?.let { (it / 10).roundedHalfAway() / 100 }
    }
}

/** Vitals typed in a form (VitalsDraft): every field optional, each checked for its range. */
data class HospVitalsDraft(
    var temperatureC: String = "",
    var heartRate: String = "",
    var respiratoryRate: String = "",
    var painScore: String = "",
    var weightKg: String = "",
    var mucousMembranes: String = "",
    var crtSeconds: String = "",
) {
    enum class Field(val rawValue: String) {
        temperatureC("temperatureC"), heartRate("heartRate"), respiratoryRate("respiratoryRate"), painScore("painScore"),
        weightKg("weightKg"), mucousMembranes("mucousMembranes"), crtSeconds("crtSeconds"),
    }

    companion object {
        fun label(field: Field): String = when (field) {
            Field.temperatureC -> L("Temperature (°C)")
            Field.heartRate -> L("Heart rate (bpm)")
            Field.respiratoryRate -> L("Breaths (/min)")
            Field.painScore -> L("Pain (0–10)")
            Field.weightKg -> L("Weight (kg)")
            Field.mucousMembranes -> L("Gums")
            Field.crtSeconds -> L("CRT (s)")
        }
    }

    /** The vitals to send (null when nothing was typed), or the first wrong field (a failure holding `HospVitalsError`). */
    fun parsed(): Result<Map<String, Any>?> {
        val out = mutableMapOf<String, Any>()
        data class Check(val field: Field, val text: String, val min: Double, val max: Double, val whole: Boolean)
        val checks = listOf(
            Check(Field.temperatureC, temperatureC, 25.0, 45.0, false),
            Check(Field.heartRate, heartRate, 10.0, 400.0, true),
            Check(Field.respiratoryRate, respiratoryRate, 2.0, 200.0, true),
            Check(Field.painScore, painScore, 0.0, 10.0, true),
            Check(Field.crtSeconds, crtSeconds, 0.0, 10.0, false),
        )
        for ((field, text, min, max, whole) in checks) {
            val value = HospFmt.read(text) ?: continue
            if (value.isNaN() || value < min || value > max || (whole && value != Math.rint(value))) return Result.failure(HospVitalsError(field))
            out[field.rawValue] = if (whole) value.toInt() else value
        }
        HospFmt.read(weightKg)?.let { weight ->
            if (weight.isNaN() || weight <= 0 || weight > 200) return Result.failure(HospVitalsError(Field.weightKg))
            out["weightGrams"] = (weight * 1000).roundedHalfAway().toInt()
        }
        mucousMembranes.nonEmpty?.let { out["mucousMembranes"] = it }
        return Result.success(if (out.isEmpty()) null else out)
    }
}

class HospVitalsError(val field: HospVitalsDraft.Field) : Exception(field.rawValue)
