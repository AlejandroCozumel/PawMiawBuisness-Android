package com.pawmiaw.business.home

import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import kotlinx.serialization.Serializable

//
//  HomeModels.kt (iOS HomeModels.swift)
//
//  What Home reads, as the web's hooks type it: the day's board
//  (`GET /provider-today`, hooks/use-provider-visits.ts), another day
//  (`GET /provider-day`), the grooming and home-visits day lists, the setup
//  checklist, an invited member's reminders, visits to relocate and families'
//  requests. Every field that may be absent is nullable, so a missing one
//  never breaks the board.
//

// MARK: - The day (GET /provider-today)

@Serializable
data class HomeToday(
    val date: String? = null,
    val timeZone: String? = null,
    val now: String? = null,
    val currency: String? = null,
    val viewer: HomeViewer? = null,
    val counts: HomeCounts? = null,
    /** finance.desk: what was collected today; null below. */
    val collectedToday: Int? = null,
    /** finance.view: how many exceptions yesterday had; null below. */
    val exceptionsYesterday: Int? = null,
    val registers: List<HomeRegisterShift>? = null,
    val lanes: Map<String, List<HomeVisit>>? = null,
    val sessions: List<HomeSession>? = null,
    val needsYou: HomeNeeds? = null,
    val arrivals: List<HomeArrival>? = null,
    val hospital: HomeHospitalCounts? = null,
    val hub: HomeHub? = null,
) {
    fun lane(key: HomeLane): List<HomeVisit> = lanes?.get(key.rawValue) ?: emptyList()
    val allVisits: List<HomeVisit> get() = HomeLane.entries.flatMap { lane(it) }
}

enum class HomeLane(val rawValue: String) {
    upcoming("upcoming"), waiting("waiting"), inService("in_service"), toPay("to_pay"), done("done"), missed("missed");

    val order: Int
        get() = when (this) {
            inService -> 0
            waiting -> 1
            toPay -> 2
            upcoming -> 3
            done -> 4
            missed -> 5
        }

    companion object {
        fun from(raw: String?): HomeLane? = entries.firstOrNull { it.rawValue == raw }
    }
}

@Serializable
data class HomeViewer(
    val memberId: String? = null,
    val seesAllVisits: Boolean? = null,
)

@Serializable
data class HomeCounts(
    val total: Int? = null,
    val upcoming: Int? = null,
    val late: Int? = null,
    val waiting: Int? = null,
    val inService: Int? = null,
    val toPay: Int? = null,
    val done: Int? = null,
    val missed: Int? = null,
)

@Serializable
data class HomeRegisterShift(
    val sessionId: String,
    val registerId: String? = null,
    val registerName: String? = null,
    val openedAt: String? = null,
    val openedByName: String? = null,
    val stale: Boolean? = null,
) {
    val id: String get() = sessionId
}

@Serializable
data class HomePetRef(
    val id: String? = null,
    val name: String? = null,
    val species: String? = null,
    val breed: String? = null,
    val photoUrl: String? = null,
    val allergies: List<String>? = null,
)

@Serializable
data class HomeClientRef(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val onPawMiaw: Boolean? = null,
)

@Serializable
data class HomePersonRef(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class HomeVisitFee(
    val kind: String? = null,
    val decision: String? = null,
)

@Serializable
data class HomeVisitInvoice(
    val id: String,
    val status: String? = null,
    val totalAmount: Int? = null,
    val amountPaid: Int? = null,
    val balanceDueAmount: Int? = null,
    val currency: String? = null,
    val lineCount: Int? = null,
    val unpricedLines: Int? = null,
    val sentBy: HomePersonRef? = null,
)

@Serializable
data class HomeNotesCount(
    val drafts: Int? = null,
    val signed: Int? = null,
)

@Serializable
data class HomeVisitGroup(
    val id: String,
    val kind: String? = null,
    val status: String? = null,
    val pets: List<HomeGroupPet>? = null,
)

@Serializable
data class HomeGroupPet(
    val appointmentId: String? = null,
    val name: String? = null,
    val photoUrl: String? = null,
    val species: String? = null,
    val status: String? = null,
)

/** A visit's card on Home's board (`TodayVisit`). */
@Serializable
data class HomeVisit(
    val id: String,
    val lane: String? = null,
    val billing: String? = null,
    val status: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val lateMinutes: Int? = null,
    val waitingMinutes: Int? = null,
    val inServiceMinutes: Int? = null,
    val pickupPassedMinutes: Int? = null,
    val familyAttendance: String? = null,
    val serviceLabel: String? = null,
    val serviceCategory: String? = null,
    val room: String? = null,
    val notes: String? = null,
    val arrivalNote: String? = null,
    val hoursOverride: Boolean? = null,
    val confirmationStatus: String? = null,
    val fee: HomeVisitFee? = null,
    val pet: HomePetRef? = null,
    val client: HomeClientRef? = null,
    val assigned: HomePersonRef? = null,
    val invoice: HomeVisitInvoice? = null,
    val notesCount: HomeNotesCount? = null,
    val group: HomeVisitGroup? = null,
    val triagePriority: String? = null,
    val triageReason: String? = null,
    val afterHours: Boolean? = null,
    val groomStage: String? = null,
    val bookedOnline: Boolean? = null,
) {
    val laneValue: HomeLane get() = HomeLane.from(lane ?: "") ?: HomeLane.upcoming
    val petName: String get() = pet?.name ?: "—"
}

/** A group walk or class on Home (`AgendaSession`). */
@Serializable
data class HomeSession(
    val id: String,
    val status: String? = null,
    val startsAt: String? = null,
    val serviceLabel: String? = null,
    val size: Int? = null,
    val capacity: Int? = null,
    val room: String? = null,
    val meetingPoint: String? = null,
    val startMode: String? = null,
    val assignedMember: HomePersonRef? = null,
    val pets: List<HomeGroupPet>? = null,
) {
    /** Everyone still booked (not cancelled, not a no-show). */
    val staying: List<HomeGroupPet>
        get() = (pets ?: emptyList()).filter { (it.status ?: "") !in listOf("cancelled", "canceled", "no_show") }
}

@Serializable
data class HomeHospitalCounts(
    val admitted: Int? = null,
    val pending: Int? = null,
    val due: Int? = null,
    val late: Int? = null,
    val missed: Int? = null,
)

@Serializable
data class HomeHub(
    val modules: List<String>? = null,
    val entries: List<HomeHubEntry>? = null,
)

@Serializable
data class HomeHubPet(
    val name: String? = null,
    val species: String? = null,
    val photoUrl: String? = null,
)

/** A stay, home-stay visit or hospital patient on Home. */
@Serializable
data class HomeHubEntry(
    val id: String,
    val entityId: String? = null,
    val module: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val pets: List<HomeHubPet>? = null,
    val clientName: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val room: String? = null,
    val assignedId: String? = null,
    val attention: Int? = null,
    val late: Boolean? = null,
    val sourceAppointmentId: String? = null,
)

/** A family on its way with an emergency ("voy en camino"). */
@Serializable
data class HomeArrival(
    val id: String,
    val status: String? = null,
    val etaAt: String? = null,
    val minutesAway: Int? = null,
    val message: String? = null,
    val contactPhone: String? = null,
    val afterHours: Boolean? = null,
    val routedTo: String? = null,
    val pet: HomeArrivalPet? = null,
    val client: HomePersonRef? = null,
    val linked: Boolean? = null,
    val emergencyCard: HomeArrivalCard? = null,
    val acknowledgedByName: String? = null,
    val replyMessage: String? = null,
) {
    val isOpen: Boolean get() = status == "on_the_way" || status == "acknowledged"
}

@Serializable
data class HomeArrivalPet(
    val petId: String? = null,
    val name: String? = null,
    val species: String? = null,
    val photoUrl: String? = null,
    val providerClientPetId: String? = null,
)

@Serializable
data class HomeArrivalCard(
    val url: String? = null,
)

// MARK: - Needs you

@Serializable
data class HomeNeeds(
    val notesToSign: HomeNotesToSign? = null,
    val unfinished: HomeUnfinished? = null,
    val requests: HomeVisitRequests? = null,
    val followUps: HomeFollowUps? = null,
    val charging: HomeChargingSummary? = null,
    val money: HomeMoneyOwed? = null,
    val stays: HomeStayCounts? = null,
    val stock: HomeStockCounts? = null,
    val due: HomeDueCounts? = null,
    val onlineRequests: HomeOnlineRequestList? = null,
    /**
     * Families who said "Sí fuimos" to a visit recorded as a no-show (docs/family-money.md §5): up to 8
     * items, `count` all. Null without appointments.view; absent from an older server. An item leaves
     * once its fee bill is forgiven, cancelled or paid, the business dismisses it, or 14 days after.
     */
    val disputes: HomeDisputes? = null,
)

@Serializable
data class HomeDisputes(
    val count: Int? = null,
    val items: List<HomeDispute>? = null,
)

/** One family's "Sí fuimos" on Home. Amounts are null without billing.view. */
@Serializable
data class HomeDispute(
    val appointmentId: String,
    val petName: String? = null,
    val clientName: String? = null,
    val serviceLabel: String? = null,
    val startsAt: String? = null,
    val disputedAt: String? = null,
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    /** The fee bill to forgive ("Perdonar el cargo"), or null when none was charged. */
    val feeInvoiceId: String? = null,
    val fee: Fee? = null,
) {
    val id: String get() = appointmentId

    @Serializable
    data class Fee(
        val invoiceId: String? = null,
        val status: String? = null,
        val kind: String? = null,
        val amount: Int? = null,
        val balance: Int? = null,
        val currency: String? = null,
    )
}

@Serializable
data class HomeNotesToSign(
    val count: Int? = null,
    val items: List<HomeNoteToSign>? = null,
)

@Serializable
data class HomeNoteToSign(
    val recordId: String,
    val title: String? = null,
    val petName: String? = null,
    val appointmentId: String? = null,
    val author: String? = null,
    val updatedAt: String? = null,
) {
    val id: String get() = recordId
}

@Serializable
data class HomeUnfinished(
    val count: Int? = null,
    val items: List<HomeUnfinishedVisit>? = null,
)

@Serializable
data class HomeUnfinishedVisit(
    val id: String,
    val status: String? = null,
    val startsAt: String? = null,
    val petName: String? = null,
    val clientName: String? = null,
    val serviceLabel: String? = null,
    val familyAttendance: String? = null,
)

@Serializable
data class HomeVisitRequests(
    val count: Int? = null,
    val items: List<HomeVisitRequest>? = null,
)

@Serializable
data class HomeVisitRequest(
    val id: String,
    val kind: String? = null,
    val startsAt: String? = null,
    val petName: String? = null,
    val clientName: String? = null,
    val preferredTime: String? = null,
)

@Serializable
data class HomeFollowUps(
    val dueToday: Int? = null,
    val overdue: Int? = null,
    val documentation: Int? = null,
    val later: Int? = null,
    val items: List<HomeFollowUp>? = null,
)

@Serializable
data class HomeFollowUp(
    val id: String,
    val title: String? = null,
    val type: String? = null,
    val dueAt: String? = null,
    val petName: String? = null,
    val clientName: String? = null,
    val overdue: Boolean? = null,
)

/** "Por cobrar" (hooks/use-charging-queue.ts): the front desk's queue, or what this person prices. */
@Serializable
data class HomeChargingSummary(
    val scope: String? = null,
    val count: Int? = null,
    val amount: Int? = null,
    val earlierDays: HomeEarlierDays? = null,
    val items: List<HomeChargingItem>? = null,
)

@Serializable
data class HomeEarlierDays(
    val count: Int? = null,
    val oldestDay: String? = null,
)

@Serializable
data class HomeChargingItem(
    val source: String? = null,
    val sourceId: String? = null,
    val appointmentId: String? = null,
    val invoiceId: String? = null,
    val state: String? = null,
    val petName: String? = null,
    val petNames: List<String>? = null,
    val clientName: String? = null,
    val serviceLabel: String? = null,
    val at: String? = null,
    val day: String? = null,
    val earlierDay: Boolean? = null,
    val vetName: String? = null,
    val amount: Int? = null,
    val currency: String? = null,
) {
    val id: String get() = invoiceId ?: sourceId ?: "${petName ?: ""}-${at ?: ""}"
}

@Serializable
data class HomeMoneyOwed(
    val owed: Int? = null,
    val overdue: Int? = null,
    val clients: Int? = null,
    val promisesDue: Int? = null,
    val promisesBroken: Int? = null,
    val atDesk: HomeAtDesk? = null,
)

@Serializable
data class HomeAtDesk(
    val count: Int? = null,
    val amount: Int? = null,
)

@Serializable
data class HomeStayCounts(
    val arriving: Int? = null,
    val leaving: Int? = null,
    val inHouse: Int? = null,
    val lateArrivals: Int? = null,
    val lateDepartures: Int? = null,
)

@Serializable
data class HomeStockCounts(
    val low: Int? = null,
    val out: Int? = null,
    val expiring: Int? = null,
    val expired: Int? = null,
)

@Serializable
data class HomeDueCounts(
    val overdue: Int? = null,
    val thisWeek: Int? = null,
)

/** Families' online bookings waiting for an answer (`GET /provider-online-booking/requests`). */
@Serializable
data class HomeOnlineRequestList(
    val count: Int? = null,
    val items: List<HomeOnlineRequest>? = null,
)

@Serializable
data class HomeOnlineRequest(
    /** "request" or "reschedule" (a family asks to move a confirmed booking); none from an older server. */
    val kind: String? = null,
    val appointmentId: String,
    /** A reschedule: the visit's time now (the time asked for is in `rescheduleProposal`). */
    val startsAt: String? = null,
    val endsAt: String? = null,
    val serviceLabel: String? = null,
    val notes: String? = null,
    /** A reschedule: when the ask expires. */
    val releasesAt: String? = null,
    val atHome: Boolean? = null,
    val client: HomeClientRef? = null,
    val pets: List<HomeOnlinePet>? = null,
    val rescheduleProposal: SchedRescheduleProposal? = null,
) {
    val id: String get() = appointmentId
    val isReschedule: Boolean get() = kind == "reschedule"
}

@Serializable
data class HomeOnlinePet(
    val appointmentId: String? = null,
    val name: String? = null,
    val species: String? = null,
    val photoUrl: String? = null,
)

// MARK: - Grooming and home visits (their day lists)

@Serializable
data class HomeGroomingToday(
    val items: List<HomeGroomItem>? = null,
    val needsSomeone: Int? = null,
)

@Serializable
data class HomeGroomItem(
    val appointmentId: String,
    val groomStage: String? = null,
    val startsAt: String? = null,
    val serviceLabel: String? = null,
    val pet: HomePetRef? = null,
    val client: HomeClientRef? = null,
    val groomer: HomePersonRef? = null,
    val requestSummary: String? = null,
    val readyNotifiedAt: String? = null,
    val attention: String? = null,
) {
    val id: String get() = appointmentId
}

@Serializable
data class HomeVisitsDay(
    val counts: HomeVisitsDayCounts? = null,
    val items: List<HomeHomeVisit>? = null,
)

@Serializable
data class HomeVisitsDayCounts(
    val total: Int? = null,
    val late: Int? = null,
)

@Serializable
data class HomeHomeVisit(
    val id: String,
    val late: Boolean? = null,
    val lateMinutes: Int? = null,
    val pets: List<HomeHubPet>? = null,
)

// MARK: - Another day (GET /provider-day)

@Serializable
data class HomeOtherDay(
    val date: String? = null,
    val today: String? = null,
    val mode: String? = null,
    val currency: String? = null,
    val counts: HomeOtherDayCounts? = null,
    val collected: Int? = null,
    val visits: List<HomeDayVisit>? = null,
    val stays: HomeDayStays? = null,
)

@Serializable
data class HomeOtherDayCounts(
    val total: Int? = null,
    val missed: Int? = null,
    val unconfirmed: Int? = null,
    val consentsMissing: Int? = null,
    val needsPrice: Int? = null,
    val firstVisits: Int? = null,
    val doubleBooked: Int? = null,
    val done: Int? = null,
    val unpaid: Int? = null,
    val unpaidAmount: Int? = null,
    val uncharged: Int? = null,
    val notesToSign: Int? = null,
    val reportsMissing: Int? = null,
)

@Serializable
data class HomeDayVisit(
    val id: String,
    val status: String? = null,
    val startsAt: String? = null,
    val serviceLabel: String? = null,
    val room: String? = null,
    val pet: HomePetRef? = null,
    val client: HomeClientRef? = null,
    val assigned: HomePersonRef? = null,
    val confirmationStatus: String? = null,
    val doubleBooked: Boolean? = null,
    val bookedOnline: Boolean? = null,
    val prep: HomeDayPrep? = null,
    val recap: HomeDayRecap? = null,
)

@Serializable
data class HomeDayPrep(
    val confirmation: String? = null,
    val consentsMissing: List<String>? = null,
    val needsPrice: Boolean? = null,
    val groomRequest: Boolean? = null,
    val firstVisit: Boolean? = null,
)

@Serializable
data class HomeDayRecap(
    val balanceDue: Int? = null,
    val billStatus: String? = null,
    val currency: String? = null,
    val notesToSign: Int? = null,
    val reportMissing: List<String>? = null,
)

@Serializable
data class HomeDayStays(
    val arriving: List<HomeDayStay>? = null,
    val leaving: List<HomeDayStay>? = null,
)

@Serializable
data class HomeDayStay(
    val id: String,
    val kind: String? = null,
    val status: String? = null,
    val clientName: String? = null,
    val pets: List<String>? = null,
    val unitName: String? = null,
    val checkInDay: String? = null,
    val checkInTime: String? = null,
    val checkOutDay: String? = null,
    val checkOutTime: String? = null,
    val late: Boolean? = null,
)

// MARK: - Setup, reminders, relocations, requests

/** `GET /provider-setup/checklist`. */
@Serializable
data class HomeChecklist(
    val percent: Int? = null,
    val complete: Boolean? = null,
    val items: List<HomeChecklistItem>? = null,
)

@Serializable
data class HomeChecklistItem(
    val id: String,
    val done: Boolean? = null,
    val dismissed: Boolean? = null,
    val why: String? = null,
    val count: Int? = null,
    val members: List<HomeChecklistMember>? = null,
    val mine: Boolean? = null,
)

@Serializable
data class HomeChecklistMember(
    val memberId: String,
    val name: String? = null,
    val role: String? = null,
    val gaps: List<String>? = null,
) {
    val id: String get() = memberId
}

/** `GET /provider-member-onboarding` (only the parts Home reads). */
@Serializable
data class HomeMemberOnboarding(
    val applicable: Boolean? = null,
    val show: Boolean? = null,
    val reminders: List<HomeMemberReminder>? = null,
)

@Serializable
data class HomeMemberReminder(
    val id: String,
    val credentialType: String? = null,
)

/** `GET /provider-locations/relocate`. */
@Serializable
data class HomeRelocations(
    val visits: List<HomeRelocationVisit>? = null,
)

@Serializable
data class HomeRelocationVisit(
    val id: String,
    val locationName: String? = null,
)

/** `GET /provider-requests?scope=open`: families asking for stays, home stays and programs. */
@Serializable
data class HomeFamilyRequestList(
    val items: List<HomeFamilyRequest>? = null,
)

@Serializable
data class HomeFamilyRequest(
    val id: String,
    val kind: String? = null,
    val status: String? = null,
    val displayStatus: String? = null,
    val startDay: String? = null,
    val endDay: String? = null,
    val proposedStartDay: String? = null,
    val proposedEndDay: String? = null,
    val notes: String? = null,
    val program: String? = null,
    val depositPercent: Int? = null,
    val familyAcceptedAt: String? = null,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val providerClientId: String? = null,
    val stayIds: List<String>? = null,
    val appointmentIds: List<String>? = null,
    val pets: List<HomeFamilyRequestPet>? = null,
)

@Serializable
data class HomeFamilyRequestPet(
    val name: String? = null,
    val species: String? = null,
    val photoUrl: String? = null,
    val providerClientPetId: String? = null,
)
