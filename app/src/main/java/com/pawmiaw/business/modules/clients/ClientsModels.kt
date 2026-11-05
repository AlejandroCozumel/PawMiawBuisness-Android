package com.pawmiaw.business.clients

import com.pawmiaw.business.*
import kotlinx.serialization.Serializable

//
//  ClientsModels.kt (iOS ClientsModels.swift)
//
//  The shapes the Clients module reads, mirroring the web's hooks
//  (use-provider-clients.ts, use-patient-chart.ts, use-provider-client-
//  invitations.ts, use-provider-care.ts, use-client-addresses.ts,
//  use-client-closing.ts). Everything that can be absent is nullable, so an
//  older or newer server never breaks a screen.
//

// MARK: - People and pets

@Serializable
data class ClientsActor(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ClientsClosingStamp(
    val at: String? = null,
    val byName: String? = null,
    val reason: String? = null,
    val note: String? = null,
    val signatureId: String? = null,
)

@Serializable
data class ClientsMoveLink(
    val petId: String,
    val clientId: String,
    val clientName: String? = null,
)

@Serializable
data class ClientsPet(
    val id: String,
    val providerClientId: String? = null,
    /** The family's pet it is linked to, when the family is on PawMiaw. */
    val petId: String? = null,
    val preferredMember: ClientsActor? = null,
    val petName: String,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val sex: String? = null,
    val birthDate: String? = null,
    val photoUrl: String? = null,
    /** Grams. */
    val weight: Double? = null,
    val isNeutered: Boolean? = null,
    val microchipNumber: String? = null,
    val notes: String? = null,
    val profileSource: String? = null,
    val profileLastSyncedAt: String? = null,
    val status: String? = null,
    val deceasedAt: String? = null,
    val closing: ClientsClosingStamp? = null,
    val movedTo: ClientsMoveLink? = null,
    val movedFrom: ClientsMoveLink? = null,
) {
    val isArchived: Boolean get() = status == "archived"
    val isActive: Boolean get() = (status ?: "active") == "active"
    val isLinked: Boolean get() = petId.nonEmpty != null
}

@Serializable
data class ClientsClientActivity(
    val lastVisitAt: String? = null,
    val nextVisitAt: String? = null,
    /** Null without money level 1. */
    val owedAmount: Int? = null,
    val currency: String? = null,
)

@Serializable
data class ClientsClient(
    val id: String,
    val householdId: String? = null,
    val selfLinkDeclined: Boolean? = null,
    val ownerFirstName: String? = null,
    val ownerLastName: String? = null,
    val ownerName: String,
    val linkedAccountName: String? = null,
    val businessLinkedAt: String? = null,
    val canBookOnline: Boolean? = null,
    val ownerEmail: String? = null,
    val recipientLanguage: String? = null,
    val ownerPhone: String? = null,
    val photoUrl: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val closing: ClientsClosingStamp? = null,
    val petCount: Int? = null,
    val pets: List<ClientsPet>? = null,
    val assignedMember: ClientsActor? = null,
    val activity: ClientsClientActivity? = null,
    val createdAt: String? = null,
) {
    val allPets: List<ClientsPet> get() = pets ?: emptyList()
    val isArchived: Boolean get() = status == "archived"
    val isLinked: Boolean get() = householdId.nonEmpty != null
}

@Serializable
data class ClientsDirectorySummary(
    val clients: Int? = null,
    val pets: Int? = null,
    val dueThisWeek: Int? = null,
    val owing: Int? = null,
    val archived: Int? = null,
)

@Serializable
data class ClientsRowClient(
    val id: String,
    val name: String? = null,
    val phone: String? = null,
    val photoUrl: String? = null,
    val onPawMiaw: Boolean? = null,
)

@Serializable
data class ClientsPetActivity(
    val lastVisitAt: String? = null,
    val nextVisitAt: String? = null,
    val nextDueAt: String? = null,
    val nextDueTitle: String? = null,
    val allergies: List<String>? = null,
)

/** A pet with its owner on the row: the Patients view and the directory's pets. */
@Serializable
data class ClientsPatientRow(
    val id: String,
    val petName: String,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val sex: String? = null,
    val birthDate: String? = null,
    val photoUrl: String? = null,
    val microchipNumber: String? = null,
    val linked: Boolean? = null,
    val vaccinesOverdue: Boolean? = null,
    val status: String? = null,
    val deceasedAt: String? = null,
    val client: ClientsRowClient,
    val activity: ClientsPetActivity? = null,
)

@Serializable
data class ClientsPatientsSummary(
    val pets: Int? = null,
    val vaccinesOverdue: Int? = null,
    val seenLast30Days: Int? = null,
    val linked: Int? = null,
    val archived: Int? = null,
)

@Serializable
data class ClientsMember(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val isCurrentMember: Boolean? = null,
) {
    val label: String get() = name.nonEmpty ?: email.nonEmpty ?: L("Unnamed staff")
}

// MARK: - The client page's other parts

@Serializable
data class ClientsAppointment(
    val id: String,
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    val serviceLabel: String? = null,
    val startsAt: String? = null,
    val status: String? = null,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petPhotoUrl: String? = null,
    val assignedMember: ClientsActor? = null,
    val onlineBooking: Boolean? = null,
)

@Serializable
data class ClientsInvoice(
    val id: String,
    val invoiceNumber: String? = null,
    val status: String? = null,
    val currency: String? = null,
    val totalAmount: Int? = null,
    val balanceDueAmount: Int? = null,
    val issuedAt: String? = null,
    val createdAt: String? = null,
    val petName: String? = null,
)

@Serializable
data class ClientsInvoicePage(
    val items: List<ClientsInvoice>,
    val total: Int? = null,
)

@Serializable
data class ClientsAccount(
    val credit: Int? = null,
    val currency: String? = null,
    val owed: Int? = null,
    val overdue: Int? = null,
)

@Serializable
data class ClientsFollowUp(
    val id: String,
    val title: String? = null,
    val followUpType: String? = null,
    val status: String? = null,
    val dueAt: String? = null,
    val assignedMember: ClientsActor? = null,
)

// MARK: - Addresses

@Serializable
data class ClientsVaultState(
    val hasVault: Boolean? = null,
    val fields: List<String>? = null,
)

@Serializable
data class ClientsAddress(
    val id: String,
    val source: String? = null,
    val label: String? = null,
    val line1: String? = null,
    val line2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val reference: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isDefault: Boolean? = null,
    val status: String? = null,
    val formatted: String? = null,
    val mapsUrl: String? = null,
    val vault: ClientsVaultState? = null,
) {
    val isBusiness: Boolean get() = source == "business"
    val name: String get() = label.nonEmpty ?: line1 ?: ""
}

@Serializable
data class ClientsEmergencyContact(
    val name: String? = null,
    val phone: String? = null,
    val relationship: String? = null,
) {
    val isEmpty: Boolean get() = name.nonEmpty == null && phone.nonEmpty == null
}

@Serializable
data class ClientsHome(
    val addresses: List<ClientsAddress>? = null,
    val emergencyContact: ClientsEmergencyContact? = null,
    val familyEmergencyContact: ClientsEmergencyContact? = null,
    val linked: Boolean? = null,
    val familyVaultShared: Boolean? = null,
    val canEdit: Boolean? = null,
    val canManageVault: Boolean? = null,
)

// MARK: - The patient chart

@Serializable
data class ClientsChartPet(
    val id: String,
    val clientId: String? = null,
    val name: String,
    val species: String? = null,
    val breed: String? = null,
    val sex: String? = null,
    val birthDate: String? = null,
    val photoUrl: String? = null,
    val weightGrams: Double? = null,
    val isNeutered: Boolean? = null,
    val microchipNumber: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val deceasedAt: String? = null,
    val closing: ClientsClosingStamp? = null,
    val movedTo: ClientsMoveLink? = null,
    val movedFrom: ClientsMoveLink? = null,
    val clientArchived: Boolean? = null,
    val linked: Boolean? = null,
)

@Serializable
data class ClientsChartClient(
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val onPawMiaw: Boolean? = null,
    val householdId: String? = null,
)

@Serializable
data class ClientsChartAlert(
    val kind: String,
    val label: String,
    val detail: String? = null,
    val source: String? = null,
    val recordId: String? = null,
)

@Serializable
data class ClientsSummaryEntry(
    val label: String? = null,
    val name: String? = null,
    val detail: String? = null,
    val dose: String? = null,
    val endDay: String? = null,
    val source: String? = null,
    val recordId: String? = null,
    val at: String? = null,
    val draft: Boolean? = null,
)

@Serializable
data class ClientsVaccineLine(
    val name: String,
    val lastGivenAt: String? = null,
    val nextDueDay: String? = null,
    val status: String? = null,
    val source: String? = null,
    val recordId: String? = null,
    val doses: Int? = null,
)

@Serializable
data class ClientsLabLine(
    val id: String,
    val name: String? = null,
    val result: String? = null,
    val at: String? = null,
    val source: String? = null,
    val draft: Boolean? = null,
)

@Serializable
data class ClientsChartSummary(
    val allergies: List<ClientsSummaryEntry>? = null,
    val problems: List<ClientsSummaryEntry>? = null,
    val medications: List<ClientsSummaryEntry>? = null,
    val vaccines: List<ClientsVaccineLine>? = null,
    val labs: List<ClientsLabLine>? = null,
)

@Serializable
data class ClientsWeightPoint(
    val id: String? = null,
    val at: String,
    val grams: Double,
    val used: Boolean? = null,
)

@Serializable
data class ClientsHomeWeights(
    val latestId: String? = null,
    val latestGrams: Double? = null,
    val latestAt: String? = null,
    val latestUsed: Boolean? = null,
    val trend: List<ClientsWeightPoint>? = null,
)

@Serializable
data class ClientsChartWeights(
    val latestGrams: Double? = null,
    val latestAt: String? = null,
    val trend: List<ClientsWeightPoint>? = null,
    val home: ClientsHomeWeights? = null,
    val differs: Boolean? = null,
)

@Serializable
data class ClientsCare(
    val handlingTags: List<String>? = null,
    val careNotes: String? = null,
    val groomStyle: String? = null,
    val rebookEveryWeeks: Int? = null,
    val nextGroomDueAt: String? = null,
)

@Serializable
data class ClientsNextVisit(
    val id: String,
    val startsAt: String? = null,
    val serviceLabel: String? = null,
    val status: String? = null,
)

@Serializable
data class ClientsBalance(
    val client: Int? = null,
    val pet: Int? = null,
    val currency: String? = null,
)

@Serializable
data class ClientsChartActivity(
    val lastVisitAt: String? = null,
    val nextVisit: ClientsNextVisit? = null,
    val balance: ClientsBalance? = null,
)

@Serializable
data class ClientsChartCounts(
    val toSign: Int? = null,
    val openFollowUps: Int? = null,
)

@Serializable
data class ClientsShareAccess(
    val canView: Boolean? = null,
    val shareScope: String? = null,
    val sharedSince: String? = null,
)

@Serializable
data class ClientsChartViewer(
    val seesRecords: Boolean? = null,
    val seesVisits: Boolean? = null,
    val seesDrafts: Boolean? = null,
    val seesInternalNotes: Boolean? = null,
    val canSign: Boolean? = null,
    val seesBilling: Boolean? = null,
    val seesFollowUps: Boolean? = null,
)

@Serializable
data class ClientsChart(
    val pet: ClientsChartPet,
    val client: ClientsChartClient,
    val alerts: List<ClientsChartAlert>? = null,
    val summary: ClientsChartSummary? = null,
    val weights: ClientsChartWeights? = null,
    val care: ClientsCare? = null,
    val activity: ClientsChartActivity? = null,
    val counts: ClientsChartCounts? = null,
    val sharedHistoryAccess: ClientsShareAccess? = null,
    val viewer: ClientsChartViewer? = null,
    val today: String? = null,
) {
    val isArchived: Boolean get() = pet.status == "archived"
    val died: Boolean get() = pet.deceasedAt.nonEmpty != null
}

// MARK: - The history

@Serializable
data class ClientsAttachment(
    val id: String,
    val fileName: String? = null,
    val url: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class ClientsPersonRef(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ClientsChartRecord(
    val id: String,
    val appointmentId: String? = null,
    val recordType: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val details: String? = null,
    val internalNote: String? = null,
    val payload: Map<String, JSONValue>? = null,
    val eventAt: String? = null,
    val status: String? = null,
    val signedAt: String? = null,
    val signedBy: ClientsPersonRef? = null,
    val createdBy: ClientsPersonRef? = null,
    val amendedAt: String? = null,
    val attachments: List<ClientsAttachment>? = null,
    val actionable: Boolean? = null,
    val carriedOver: Boolean? = null,
)

@Serializable
data class ClientsChartWeight(
    val id: String,
    val grams: Double,
    val recordedAt: String? = null,
    val source: String? = null,
    val notes: String? = null,
    val loggedBy: String? = null,
    val sharedWithOwner: Boolean? = null,
    val from: String? = null,
)

@Serializable
data class ClientsPlaceRef(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ClientsChartVisit(
    val id: String,
    val serviceLabel: String? = null,
    val serviceCategory: String? = null,
    val status: String? = null,
    val startsAt: String? = null,
    val notes: String? = null,
    val arrivalNote: String? = null,
    val groomRequestSummary: String? = null,
    val report: JSONValue? = null,
    val assigned: ClientsPersonRef? = null,
    val location: ClientsPlaceRef? = null,
    val actionable: Boolean? = null,
    val carriedOver: Boolean? = null,
    val records: List<ClientsChartRecord>? = null,
    val weights: List<ClientsChartWeight>? = null,
)

@Serializable
data class ClientsFamilyRecord(
    val id: String,
    val recordType: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val details: String? = null,
    val payload: Map<String, JSONValue>? = null,
    val eventAt: String? = null,
    val nextDueAt: String? = null,
    val createdAt: String? = null,
    val by: String? = null,
    val attachments: List<ClientsAttachment>? = null,
)

@Serializable
data class ClientsTimelineItem(
    val key: String,
    val kind: String,
    val at: String? = null,
    val visit: ClientsChartVisit? = null,
    val record: ClientsChartRecord? = null,
    val familyRecord: ClientsFamilyRecord? = null,
    val weight: ClientsChartWeight? = null,
    val incident: JSONValue? = null,
    val consent: JSONValue? = null,
) {
    val id: String get() = key
}

@Serializable
data class ClientsTimelineSources(
    val visits: Boolean? = null,
    val clinicRecords: Boolean? = null,
    val family: Boolean? = null,
    val weights: Boolean? = null,
    val grooming: Boolean? = null,
)

@Serializable
data class ClientsTimelinePage(
    val items: List<ClientsTimelineItem>,
    val nextCursor: String? = null,
    val filters: List<String>? = null,
    val sources: ClientsTimelineSources? = null,
)

// MARK: - Invitations

@Serializable
data class ClientsInvitationPet(
    val providerClientPetId: String,
    val petName: String? = null,
    val petSpecies: String? = null,
    val householdPetId: String? = null,
    val consentDecision: String? = null,
)

@Serializable
data class ClientsInvitationClient(
    val id: String,
    val ownerName: String? = null,
    val ownerEmail: String? = null,
    val ownerPhone: String? = null,
    val householdId: String? = null,
)

@Serializable
data class ClientsPendingAccount(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
)

@Serializable
data class ClientsPendingHousehold(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ClientsPendingDecision(
    val providerClientPetId: String? = null,
    val decision: String? = null,
    val householdPetName: String? = null,
)

@Serializable
data class ClientsPendingAcceptance(
    val account: ClientsPendingAccount? = null,
    val household: ClientsPendingHousehold? = null,
    val requestedAt: String? = null,
    val decisions: List<ClientsPendingDecision>? = null,
)

@Serializable
data class ClientsInvitation(
    val id: String,
    val providerClientId: String,
    val householdId: String? = null,
    val recipientEmail: String? = null,
    val recipientName: String? = null,
    val message: String? = null,
    val status: String,
    val emailStatus: String? = null,
    val emailSentAt: String? = null,
    val url: String? = null,
    val expiresAt: String? = null,
    val acceptedAt: String? = null,
    val revokedAt: String? = null,
    val expiredAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val latestForClient: Boolean? = null,
    val createdBy: ClientsActor? = null,
    val revokedBy: ClientsActor? = null,
    val client: ClientsInvitationClient? = null,
    val pets: List<ClientsInvitationPet>? = null,
    val pendingAcceptance: ClientsPendingAcceptance? = null,
)

@Serializable
data class ClientsBulkSent(
    val clientId: String,
    val invitationId: String? = null,
)

@Serializable
data class ClientsBulkSkipped(
    val clientId: String,
    val reason: String,
)

@Serializable
data class ClientsBulkResult(
    val sent: List<ClientsBulkSent>,
    val skipped: List<ClientsBulkSkipped>,
)

// MARK: - Due

@Serializable
data class ClientsDuePet(
    val id: String,
    val name: String,
    val species: String? = null,
    val photoUrl: String? = null,
)

@Serializable
data class ClientsDueClient(
    val id: String,
    val name: String,
    val firstName: String? = null,
    val phone: String? = null,
    val onPawMiaw: Boolean? = null,
)

@Serializable
data class ClientsDueService(
    val category: String? = null,
    val id: String? = null,
    val label: String? = null,
)

@Serializable
data class ClientsDueContact(
    val channel: String? = null,
    val at: String? = null,
)

@Serializable
data class ClientsFamilyNotice(
    val asked: Boolean? = null,
    val sentAt: String? = null,
)

@Serializable
data class ClientsDueItem(
    val key: String,
    val reason: String,
    val title: String? = null,
    val dueAt: String,
    val days: Int? = null,
    val bucket: String,
    val followUpId: String? = null,
    val nextVisit: ClientsNextVisit? = null,
    val missedVisitAt: String? = null,
    val service: ClientsDueService? = null,
    val snoozedUntil: String? = null,
    val familyNotice: ClientsFamilyNotice? = null,
    val pet: ClientsDuePet,
    val client: ClientsDueClient,
    val lastVisitAt: String? = null,
    val lastContact: ClientsDueContact? = null,
) {
    val id: String get() = key
}

@Serializable
data class ClientsDueCounts(
    val overdue: Int? = null,
    val thisWeek: Int? = null,
    val later: Int? = null,
    val total: Int? = null,
)

@Serializable
data class ClientsDueList(
    val now: String? = null,
    val windowDays: Int? = null,
    val counts: ClientsDueCounts? = null,
    val items: List<ClientsDueItem>,
)

@Serializable
data class ClientsRemindResult(
    val channel: String? = null,
    val message: String? = null,
    val link: String? = null,
    val sentTo: Int? = null,
)

// MARK: - Archiving

@Serializable
data class ClientsClosingPreviewPet(
    val id: String,
    val name: String? = null,
    val blockers: List<String>? = null,
)

@Serializable
data class ClientsClosingVisit(
    val id: String,
    val petName: String? = null,
    val startsAt: String? = null,
    val serviceLabel: String? = null,
)

@Serializable
data class ClientsClosingPreview(
    val pets: List<ClientsClosingPreviewPet>? = null,
    val upcomingVisits: List<ClientsClosingVisit>? = null,
    val openFollowUps: Int? = null,
    val invitationsRevoked: Int? = null,
)

@Serializable
data class ClientsClosingResult(
    val client: ClientsClient? = null,
    val visitsCancelled: Int? = null,
    val followUpsCancelled: Int? = null,
    val invitationsRevoked: Int? = null,
    val followUpsReopened: Int? = null,
)

@Serializable
data class ClientsOnlineBookingAnswer(
    val clientId: String? = null,
    val allowed: Boolean? = null,
    val client: ClientsClient? = null,
)

/** One pet of a client, resolved for the chart (a route that only carries the pet). */
@Serializable
data class ClientsPetOwnerRef(
    val providerClientId: String? = null,
)
