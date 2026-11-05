package com.pawmiaw.business.schedule

import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.serialization.Serializable
import java.time.Instant

//
//  SchedModels.kt (iOS SchedModels.swift)
//
//  The Schedule module's shapes, mirroring the web's hooks
//  (use-provider-appointments.ts, use-provider-visits.ts,
//  use-provider-online-booking.ts, use-provider-rooms.ts, lib/hours.ts,
//  use-provider-sessions.ts, use-provider-invoices.ts). Every field that can
//  be absent or null is nullable, so an older or newer server never breaks a
//  screen.
//

// MARK: - Visits

@Serializable
data class SchedAssigned(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class SchedActor(
    val name: String? = null,
)

@Serializable
data class SchedGroupPet(
    val appointmentId: String,
    val providerClientPetId: String? = null,
    val providerClientId: String? = null,
    val name: String? = null,
    val species: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val clientName: String? = null,
)

/**
 * Pets booked together: a family's (`household`) or a session's places. Sessions
 * (`GET /provider-sessions`) read the same shape with their own extra fields.
 */
@Serializable
data class SchedVisitGroup(
    val id: String,
    val kind: String? = null,
    val status: String? = null,
    val serviceLabel: String? = null,
    val serviceCategory: String? = null,
    val serviceId: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val capacity: Int? = null,
    val priceAmount: Int? = null,
    val onlineBooking: Boolean? = null,
    val onlineBookingOpen: Boolean? = null,
    val size: Int? = null,
    val pets: List<SchedGroupPet>? = null,
    // A session's own fields (AgendaSession).
    val locationId: String? = null,
    val assignedMember: SchedAssigned? = null,
    val roomId: String? = null,
    val room: String? = null,
    val notes: String? = null,
    val doubleBooked: Boolean? = null,
    val placesLeft: Int? = null,
    val full: Boolean? = null,
    val waitlistCount: Int? = null,
) {
    val isSession: Boolean get() = kind == "session"

    /** Everyone still booked (not cancelled, not a no-show), first booked first. */
    val stayingPets: List<SchedGroupPet>
        get() = (pets ?: emptyList()).filter { !SchedStatus.gone.contains(it.status ?: "") }

    /** "3/6" places, or "3". */
    val placesLabel: String
        get() {
            val taken = size ?: stayingPets.size
            capacity?.let { return "$taken/$it" }
            return "$taken"
        }
}

@Serializable
data class SchedOnlineRequestClock(
    val releasesAt: String? = null,
)

/**
 * A family's ask to move its confirmed online booking, waiting for the business
 * (paw-miaw-backend docs/online-booking.md §13): the time asked for, held from
 * everyone else; the visit keeps its own time until staff accept or decline.
 * On a visit it carries the ask's clock; on an inbox item the item has it.
 */
@Serializable
data class SchedRescheduleProposal(
    val startsAt: String,
    val endsAt: String? = null,
    /** Who takes it at the new time. */
    val assigned: SchedAssigned? = null,
    val room: String? = null,
    val requestedAt: String? = null,
    /** When the new time is let go if nobody answers. */
    val releasesAt: String? = null,
)

@Serializable
data class SchedSeriesResult(
    val id: String? = null,
    val created: Int? = null,
    val skipped: List<SchedSeriesSkip>? = null,
)

@Serializable
data class SchedSeriesSkip(
    val startsAt: String? = null,
    val reason: String? = null,
)

/** A business visit (`ProviderAppointment`). */
@Serializable
data class SchedAppointment(
    val id: String,
    val clinicId: String? = null,
    val providerClientId: String,
    val providerClientPetId: String,
    val serviceCategory: String? = null,
    val serviceId: String? = null,
    val serviceLabel: String,
    val startsAt: String,
    val endsAt: String? = null,
    val room: String? = null,
    val notes: String? = null,
    val status: String,
    val confirmationStatus: String? = null,
    val confirmationNote: String? = null,
    val confirmationPreferredTime: String? = null,
    val clientName: String,
    val clientEmail: String? = null,
    val clientPhone: String? = null,
    val clientHouseholdId: String? = null,
    val clientPhotoUrl: String? = null,
    val petName: String,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val petSex: String? = null,
    val petBirthDate: String? = null,
    /** Grams. */
    val petWeight: Double? = null,
    val petPhotoUrl: String? = null,
    val assignedMember: SchedAssigned? = null,
    val createdBy: SchedActor? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val arrivedAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val arrivalNote: String? = null,
    val hoursOverride: Boolean? = null,
    val roomId: String? = null,
    val doubleBooked: Boolean? = null,
    val locationId: String? = null,
    val noCharge: Boolean? = null,
    val noChargeReason: String? = null,
    val cancelReason: String? = null,
    val feeKind: String? = null,
    val feeDecision: String? = null,
    val feeInvoiceId: String? = null,
    val feeNote: String? = null,
    val reportKind: String? = null,
    val groomStage: String? = null,
    val groomReadyNotifiedAt: String? = null,
    val groomReadyChannel: String? = null,
    val familyAttendance: String? = null,
    val seriesId: String? = null,
    val atHome: Boolean? = null,
    val accessNotes: String? = null,
    val groupId: String? = null,
    val group: SchedVisitGroup? = null,
    val together: List<String>? = null,
    val series: SchedSeriesResult? = null,
    /** A family booked it from the app ("En línea"). */
    val bookedOnline: Boolean? = null,
    /** While it is a request ("Por confirmar"): when its time is released. */
    val onlineRequest: SchedOnlineRequestClock? = null,
    val onlineOutcome: String? = null,
    val declineReason: String? = null,
    val movedFromStartsAt: String? = null,
    /** The family asks to move it ("Cambio de hora"): set only while the ask waits. */
    val rescheduleProposal: SchedRescheduleProposal? = null,
    /** The groom, walk or training report (`reportKind` says which). */
    val report: SvcReport? = null,
    val reportUpdatedAt: String? = null,
    /** The client's address a home visit happens at. */
    val serviceAddressId: String? = null,
) {
    val start: Instant? get() = DateText.parse(startsAt)
    val groomReadyNotifiedAtValue: String? get() = if (groomStage == "ready") groomReadyNotifiedAt else null
    val end: Instant? get() = DateText.parse(endsAt)
    val isRequested: Boolean get() = status == "requested"
    val isGone: Boolean get() = SchedStatus.gone.contains(status)
    /** The family asked to move or cancel it. */
    val familyAsking: Boolean
        get() = status == "scheduled" && (confirmationStatus == "reschedule_requested" || confirmationStatus == "cancellation_requested")
    /** A clinical visit (a vet's or an emergency), as the web reads it. */
    val isClinical: Boolean get() = serviceCategory == null || serviceCategory == "vet" || serviceCategory == "emergency"
    /** The family's other pets booked with it (household) still in the same state. */
    val togetherPets: List<SchedGroupPet>
        get() {
            val group = group ?: return emptyList()
            if (group.kind != "household") return emptyList()
            return (group.pets ?: emptyList()).filter { it.appointmentId != id && it.status == status }
        }
    val lengthMinutes: Int
        get() {
            val start = start ?: return 30
            val end = end ?: return 30
            if (end <= start) return 30
            return maxOf(15, ((end.toEpochMilli() - start.toEpochMilli()) / 60_000.0).toInt())
        }
}

/** What a visit's move answers (check in, start, finish, no-show, cancel, "Sí vino"). */
@Serializable
data class SchedMoveResponse(
    val appointment: SchedAppointment? = null,
    val feeInvoiceId: String? = null,
    val together: List<String>? = null,
    val bill: SchedMoveBill? = null,
)

@Serializable
data class SchedMoveBill(
    val status: String? = null,
    val unpricedLines: Int? = null,
)

@Serializable
data class SchedStopSeriesResult(val cancelled: Int? = null, val kept: Int? = null)

@Serializable
data class SchedConfirmationResult(val emailStatus: String? = null, val errorMessage: String? = null)

// MARK: - Statuses

object SchedStatus {
    val all = listOf("requested", "scheduled", "checked_in", "in_progress", "completed", "cancelled", "no_show")
    val gone = listOf("cancelled", "canceled", "no_show")
    /** What holds its time (the server's `HOLDING_STATUSES`). */
    val holding = listOf("requested", "scheduled", "checked_in", "in_progress")

    /** The list's word for a status (web agenda-list `STATUS_LABELS`). */
    fun label(status: String?): String = when (status) {
        "requested" -> L("To confirm")
        "scheduled" -> L("Booked")
        "checked_in" -> L("Waiting")
        "in_progress" -> L("In the visit")
        "completed" -> L("Finished")
        "cancelled", "canceled" -> L("Cancelled")
        "no_show" -> L("Didn’t come")
        else -> status ?: ""
    }

    fun confirmationLabel(status: String?): String? = when (status) {
        "pending" -> L("Waiting for their answer")
        "confirmed" -> L("Confirmed")
        "reschedule_requested" -> L("Asked to move it")
        "cancellation_requested" -> L("Asked to cancel")
        "delivery_failed" -> L("The confirmation did not reach them")
        "declined" -> L("The family declined this visit")
        "expired" -> L("The confirmation expired")
        else -> null
    }
}

// MARK: - People, rooms, hours

@Serializable
data class SchedMember(
    val id: String,
    val name: String? = null,
    val userName: String? = null,
    val email: String? = null,
    val image: String? = null,
    val displayName: String? = null,
    val professionalTitle: String? = null,
    val maxConcurrentVisits: Int? = null,
    val locationIds: List<String>? = null,
    val role: String? = null,
    val effectivePermissions: List<String>? = null,
    val isCurrentMember: Boolean? = null,
    val status: String? = null,
) {
    val label: String get() = displayName.nonEmpty ?: name.nonEmpty ?: email.nonEmpty ?: L("Someone")
    val takesVisits: Boolean get() = (effectivePermissions ?: emptyList()).contains(Permission.appointmentsComplete.rawValue)
    val isMe: Boolean get() = isCurrentMember == true
    fun worksAt(locationId: String?): Boolean {
        if (locationId == null) return true
        val ids = locationIds
        if (ids.isNullOrEmpty()) return true
        return ids.contains(locationId)
    }
}

@Serializable
data class SchedRoomOut(
    val fromDay: String? = null,
    val untilDay: String? = null,
    val reason: String? = null,
)

@Serializable
data class SchedRoom(
    val id: String,
    val name: String,
    val kind: String? = null,
    val capacity: Int? = null,
    val serviceIds: List<String>? = null,
    val sortOrder: Int? = null,
    val status: String? = null,
    val locationId: String? = null,
    val outOfService: SchedRoomOut? = null,
) {
    /** Wards and cages hold hospitalized patients, never visits. */
    val holdsVisits: Boolean get() = kind != "ward" && kind != "cage"

    fun outOn(day: String): SchedRoomOut? {
        val out = outOfService ?: return null
        val from = out.fromDay ?: return null
        if (day < from) return null
        val until = out.untilDay
        if (until != null && day > until) return null
        return out
    }
}

@Serializable
data class SchedRange(
    val start: String,
    val end: String,
)

@Serializable
data class SchedClosureMark(
    val id: String? = null,
    val label: String? = null,
    val closed: Boolean? = null,
)

@Serializable
data class SchedLoadDay(
    val date: String,
    val ranges: List<SchedRange>? = null,
    val closure: SchedClosureMark? = null,
)

@Serializable
data class SchedLoadHours(
    val timeZone: String? = null,
    val checksHours: Boolean? = null,
    val days: List<SchedLoadDay>? = null,
)

@Serializable
data class SchedTimeOff(
    val id: String? = null,
    val reason: String? = null,
)

@Serializable
data class SchedLoadPersonDay(
    val date: String,
    val ranges: List<SchedRange>? = null,
    val timeOff: SchedTimeOff? = null,
)

@Serializable
data class SchedLoadPerson(
    val id: String,
    val name: String? = null,
    val capacity: Int? = null,
    val ownHours: Boolean? = null,
    val days: List<SchedLoadPersonDay>? = null,
) {
    fun day(key: String): SchedLoadPersonDay? = days?.firstOrNull { it.date == key }
}

@Serializable
data class SchedLoadVisit(
    val id: String,
    val startsAt: String,
    val endsAt: String? = null,
    val status: String,
    val memberId: String? = null,
    val roomId: String? = null,
    val locationId: String? = null,
    val petName: String? = null,
    val sessionId: String? = null,
)

/** Who and what is taken between two times (`GET /provider-schedule/load`). */
@Serializable
data class SchedLoad(
    val visits: List<SchedLoadVisit>? = null,
    val people: List<SchedLoadPerson>? = null,
    val rooms: List<SchedRoom>? = null,
    val bufferMinutes: Int? = null,
    val hours: SchedLoadHours? = null,
) {
    fun hoursDay(key: String): SchedLoadDay? = hours?.days?.firstOrNull { it.date == key }
    val visitRooms: List<SchedRoom> get() = (rooms ?: emptyList()).filter { it.holdsVisits }
}

@Serializable
data class SchedWeekDay(
    val day: String,
    val enabled: Boolean? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val ranges: List<SchedRange>? = null,
) {
    /** A day's ranges: `ranges` when sent, else its one span. */
    val dayRanges: List<SchedRange>
        get() {
            if (enabled != true) return emptyList()
            if (!ranges.isNullOrEmpty()) return ranges
            if (startTime != null && endTime != null) return listOf(SchedRange(start = startTime, end = endTime))
            return emptyList()
        }
}

/** A place's booking settings and week (the workspace's or a location's `availability`). */
@Serializable
data class SchedAvailability(
    val timezone: String? = null,
    val defaultAppointmentDurationMinutes: Int? = null,
    val allowAfterHoursBooking: Boolean? = null,
    val requestAppointmentConfirmationByDefault: Boolean? = null,
    val defaultBufferMinutes: Int? = null,
    val weeklySchedule: List<SchedWeekDay>? = null,
) {
    fun week(weekday: String): List<SchedRange> =
        weeklySchedule?.firstOrNull { it.day == weekday }?.dayRanges ?: emptyList()
}

@Serializable
data class SchedWorkspaceAvailability(val availability: SchedAvailability? = null)

@Serializable
data class SchedLocationAvailability(val id: String, val timezone: String? = null, val availability: SchedAvailability? = null)

@Serializable
data class SchedLocationsAvailability(val locations: List<SchedLocationAvailability>? = null)

@Serializable
data class SchedClosure(
    val id: String? = null,
    val startDay: String,
    val endDay: String,
    val label: String? = null,
    val ranges: List<SchedRange>? = null,
    val repeatsYearly: Boolean? = null,
) {
    fun covers(day: String): Boolean {
        if (repeatsYearly != true) return startDay <= day && day <= endDay
        if (day < startDay) return false
        val from = startDay.drop(5)
        val to = endDay.drop(5)
        val md = day.drop(5)
        return if (from <= to) (from <= md && md <= to) else (md >= from || md <= to)
    }
}

@Serializable
data class SchedClosures(val closures: List<SchedClosure>? = null)

// MARK: - Sessions (group walks and classes)

typealias SchedSession = SchedVisitGroup

// MARK: - Online booking

@Serializable
data class SchedRequestPet(
    val appointmentId: String,
    val providerClientPetId: String? = null,
    val name: String,
    val species: String? = null,
    val photoUrl: String? = null,
)

@Serializable
data class SchedRequestClient(
    val id: String? = null,
    val name: String,
    val phone: String? = null,
)

/**
 * One booking a family made online, waiting for an answer — or (`kind` "reschedule")
 * their ask to move a confirmed one: then `startsAt`/`endsAt` are the visit's time now,
 * `requestedAt`/`releasesAt` the ask's, and `rescheduleProposal` the time asked for.
 */
@Serializable
data class SchedOnlineRequest(
    /** "request" or "reschedule"; an older server sends none (a request). */
    val kind: String? = null,
    val rescheduleProposal: SchedRescheduleProposal? = null,
    val appointmentId: String,
    val groupId: String? = null,
    val startsAt: String,
    val endsAt: String? = null,
    val serviceLabel: String,
    val serviceId: String? = null,
    val serviceCategory: String? = null,
    val locationId: String? = null,
    val assigned: SchedAssigned? = null,
    val room: String? = null,
    val atHome: Boolean? = null,
    val notes: String? = null,
    val requestedAt: String? = null,
    val releasesAt: String? = null,
    val client: SchedRequestClient,
    val pets: List<SchedRequestPet>,
) {
    val id: String get() = appointmentId
    /** A family's ask to move its visit ("Cambio de hora"), answered with Aceptar or Rechazar. */
    val isReschedule: Boolean get() = kind == "reschedule"
}

@Serializable
data class SchedOnlineRequestList(
    val count: Int? = null,
    val items: List<SchedOnlineRequest>? = null,
)

@Serializable
data class SchedOnlineAnswer(
    val appointmentId: String? = null,
    val status: String? = null,
    val startsAt: String? = null,
)

// MARK: - Clients (the booking sheet's picker)

@Serializable
data class SchedClientPet(
    val id: String,
    val petName: String,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val microchipNumber: String? = null,
    val weight: Double? = null,
)

@Serializable
data class SchedClient(
    val id: String,
    val householdId: String? = null,
    val ownerName: String,
    val ownerEmail: String? = null,
    val ownerPhone: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val pets: List<SchedClientPet>? = null,
) {
    val bookablePets: List<SchedClientPet> get() = (pets ?: emptyList()).filter { it.status != "archived" }
}

// MARK: - The service catalog

@Serializable
data class SchedCatalogCategory(
    val id: String,
    val label: String,
    val description: String? = null,
    val reasonLabel: String? = null,
    val reasonPlaceholder: String? = null,
)

@Serializable
data class SchedCatalogService(
    val id: String,
    val category: String,
    val label: String,
    val description: String? = null,
    val formTemplate: String? = null,
    val atHome: Boolean? = null,
    val defaultDurationMinutes: JSONValue? = null,
) {
    val minutes: Int? get() = defaultDurationMinutes.double?.toInt()?.takeIf { it > 0 }
    val isHome: Boolean get() = atHome == true || formTemplate == "home"
}

@Serializable
data class SchedCatalog(
    val categories: List<SchedCatalogCategory>? = null,
    val services: List<SchedCatalogService>? = null,
)

// MARK: - Records and the bill

@Serializable
data class SchedRecord(
    val id: String,
    val appointmentId: String? = null,
    val recordType: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val details: String? = null,
    val internalNote: String? = null,
    val status: String? = null,
    val eventAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val signedAt: String? = null,
    val signedBy: SchedActor? = null,
    val revision: Int? = null,
) {
    /** Every record counts; the visit's own note only once it has words. */
    val hasWords: Boolean
        get() {
            if (!(recordType == "vet_visit" || recordType == "note")) return true
            return listOf(details, summary, internalNote).any { it.nonEmpty != null }
        }
    val isDraft: Boolean get() = status == "draft"
    val isSigned: Boolean get() = status == "active"
}

@Serializable
data class SchedInvoiceLine(
    val id: String,
    val label: String? = null,
    val quantity: Double? = null,
    val unitAmount: Int? = null,
    val lineTotalAmount: Int? = null,
    val courtesy: Boolean? = null,
    val itemType: String? = null,
) {
    /** A line nobody priced: $0 and not given on purpose. */
    val needsPrice: Boolean get() = (unitAmount ?: 0) <= 0 && courtesy != true
}

@Serializable
data class SchedInvoiceSummary(
    val id: String,
    val createdAt: String? = null,
)

@Serializable
data class SchedInvoice(
    val id: String,
    val invoiceNumber: String? = null,
    val status: String,
    val currency: String? = null,
    val subtotalAmount: Int? = null,
    val discountAmount: Int? = null,
    val taxAmount: Int? = null,
    val totalAmount: Int? = null,
    val amountPaid: Int? = null,
    val balanceDueAmount: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lineItems: List<SchedInvoiceLine>? = null,
)

/** Where a visit's bill stands (web lib/visit-bill.ts `billStage`). */
enum class SchedBillStage(val rawValue: String) {
    notStarted("notStarted"), noCharge("noCharge"), canceled("canceled"), noBill("noBill"), needsPrice("needsPrice"),
    building("building"), ready("ready"), partPaid("partPaid"), paid("paid"), covered("covered");

    val label: String
        get() = when (this) {
            notStarted -> L("Not started")
            noCharge -> L("No charge")
            canceled -> L("Canceled")
            noBill -> L("No bill yet")
            needsPrice -> L("Needs a price")
            building -> L("Being built")
            ready -> L("Ready to charge")
            partPaid -> L("Part paid")
            paid -> L("Paid")
            covered -> L("Nothing to charge")
        }

    val state: ProviderState
        get() = when (this) {
            notStarted, noCharge, canceled, noBill -> ProviderState.closed
            needsPrice, ready, partPaid -> ProviderState.waiting
            building -> ProviderState.active
            paid, covered -> ProviderState.done
        }

    companion object {
        fun of(visitStatus: String, noCharge: Boolean, invoice: SchedInvoice?): SchedBillStage {
            if (invoice == null) {
                if (noCharge) return SchedBillStage.noCharge
                if (visitStatus == "scheduled" || visitStatus == "checked_in" || visitStatus == "requested") return notStarted
                if (SchedStatus.gone.contains(visitStatus)) return canceled
                return noBill
            }
            val total = invoice.totalAmount ?: 0
            val paid = invoice.amountPaid ?: 0
            val balance = invoice.balanceDueAmount ?: 0
            if (invoice.status == "canceled") {
                if (noCharge) return SchedBillStage.noCharge
                return if (paid <= 0 && (visitStatus == "in_progress" || visitStatus == "completed")) noBill else canceled
            }
            if (invoice.status == "paid" || (invoice.status != "draft" && balance <= 0)) {
                return if (total == 0) covered else SchedBillStage.paid
            }
            if (invoice.status == "draft") {
                return if (total > 0 && !(invoice.lineItems ?: emptyList()).any { it.needsPrice }) building else needsPrice
            }
            if (paid > 0) return partPaid
            return ready
        }

        fun from(raw: String?): SchedBillStage? = entries.firstOrNull { it.rawValue == raw }
    }
}

// MARK: - Conflicts and hours problems (from a refused save)

@Serializable
data class SchedConflictVisit(
    val id: String? = null,
    val petName: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
)

@Serializable
data class SchedOutOfServiceNote(
    val untilDay: String? = null,
    val reason: String? = null,
)

/** A double booking (the server's 409 `visit_conflict`, or the same rule worked out here). */
@Serializable
data class SchedConflict(
    val kind: String,
    var memberId: String? = null,
    var roomId: String? = null,
    var name: String? = null,
    var capacity: Int? = null,
    var visits: List<SchedConflictVisit>? = null,
    var outOfService: SchedOutOfServiceNote? = null,
    var travelMinutes: Int? = null,
)

/** What the hours say about a visit (the server's 400 `outside_hours`). */
data class SchedHoursProblem(
    val reason: String,
    val message: String,
    val closureLabel: String?,
    val timeOffReason: String?,
    val memberId: String?,
)

/** The fee the server wants a decision on (409 `fee_decision_required`). */
data class SchedFeeRequired(
    val kind: String,
    val amount: Int,
    val currency: String?,
)
