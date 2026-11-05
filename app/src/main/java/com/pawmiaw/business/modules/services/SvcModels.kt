package com.pawmiaw.business.services

import com.pawmiaw.business.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

//
//  SvcModels.kt (iOS SvcModels.swift)
//
//  The Services module's shapes (Estética, Domicilio, Paseos y clases), from
//  the web's hooks: use-provider-grooming.ts, use-provider-home-visits.ts,
//  use-provider-sessions.ts, use-provider-care.ts. Anything that may be
//  absent is nullable, so an older or newer server never breaks a screen.
//

// MARK: - Shared references

@Serializable
data class SvcPetRef(
    val id: String,
    val name: String,
    val species: String? = null,
    val breed: String? = null,
    val photoUrl: String? = null,
)

@Serializable
data class SvcClientRef(
    val id: String,
    val name: String,
    val firstName: String? = null,
    val phone: String? = null,
    val onPawMiaw: Boolean? = null,
) {
    val shortName: String get() = firstName.nonEmpty ?: name
}

@Serializable
data class SvcPersonRef(
    val id: String,
    val name: String? = null,
)

/** What a groom's or home visit's bill needs: none, needs_price, unpaid, paid, no_charge. */
object SvcBilling {
    fun label(billing: String?): String? = when (billing) {
        "needs_price" -> L("Needs a price")
        "unpaid" -> L("Unpaid")
        "paid" -> L("Paid")
        "no_charge" -> L("No charge")
        else -> null
    }

    fun state(billing: String?): ProviderState = when (billing) {
        "paid" -> ProviderState.done
        "no_charge" -> ProviderState.closed
        else -> ProviderState.waiting
    }
}

// MARK: - Grooming

@Serializable
data class SvcGroomItem(
    val appointmentId: String,
    val status: String? = null,
    val groomStage: String,
    val startsAt: String,
    val carriedOver: Boolean? = null,
    val arrivedAt: String? = null,
    val serviceLabel: String? = null,
    val pet: SvcPetRef,
    val client: SvcClientRef,
    val groomer: SvcPersonRef? = null,
    val requestSummary: String? = null,
    val handlingTags: List<String>? = null,
    val careNotes: String? = null,
    val usualStyle: String? = null,
    val readyNotifiedAt: String? = null,
    val readyChannel: String? = null,
    val readySince: String? = null,
    val attention: String? = null,
    val hasAfterPhoto: Boolean? = null,
    val billing: String? = null,
) {
    val id: String get() = appointmentId
}

@Serializable
data class SvcGroomingToday(
    val date: String,
    val timeZone: String? = null,
    val counts: Map<String, Int>? = null,
    val items: List<SvcGroomItem>,
    val needsSomeone: Int? = null,
) {
    fun count(stage: String): Int = counts?.get(stage) ?: 0
    val total: Int get() = SvcGroomStage.all.fold(0) { sum, stage -> sum + count(stage) }
    val inProgress: Int get() = count("bath") + count("styling")
    val ready: Int get() = count("ready")
}

@Serializable
data class SvcGroomPetRow(
    val pet: SvcPetRef,
    val client: SvcClientRef,
    val lastGroomAt: String? = null,
    val lastServiceLabel: String? = null,
    val groomCount: Int? = null,
    val usualStyle: String? = null,
    val handlingTags: List<String>? = null,
    val rebookEveryWeeks: Int? = null,
    val effectiveRebookWeeks: Int? = null,
    val nextGroomDueAt: String? = null,
    val nextBookedAt: String? = null,
    val lastAfterPhotoUrl: String? = null,
) {
    val id: String get() = pet.id
}

@Serializable
data class SvcGroomPetCounts(
    val pets: Int? = null,
    val overdue: Int? = null,
    val soon: Int? = null,
)

@Serializable
data class SvcGroomPetsPage(
    val total: Int? = null,
    val counts: SvcGroomPetCounts? = null,
    val items: List<SvcGroomPetRow>,
)

@Serializable
data class SvcGroomHistoryItem(
    val appointmentId: String,
    val at: String,
    val status: String? = null,
    val serviceLabel: String? = null,
    val groomerName: String? = null,
    val requestSummary: String? = null,
    val report: SvcReport? = null,
) {
    val id: String get() = appointmentId
}

@Serializable
data class SvcGroomHistory(val items: List<SvcGroomHistoryItem>)

@Serializable
data class SvcGroomingSettings(
    var defaultRebookWeeks: Int? = null,
    var readyMessage: String? = null,
    var simpleStages: Boolean,
)

@Serializable
data class SvcGroomReadyResult(
    val id: String? = null,
    val groomStage: String? = null,
    val link: String? = null,
)

/** The care card a groomer or walker keeps about a pet (`/provider-client-pets/{id}/care`). */
@Serializable
data class SvcCareProfile(
    val providerClientPetId: String,
    val groomStyle: String? = null,
    val groomTools: String? = null,
    val groomProducts: String? = null,
    val handlingTags: List<String>? = null,
    val careNotes: String? = null,
    val rebookEveryWeeks: Int? = null,
    val effectiveRebookWeeks: Int? = null,
    val updatedAt: String? = null,
    val lastGroomAt: String? = null,
    val nextGroomDueAt: String? = null,
    val nextBookedAt: String? = null,
    /** The last visits' reports, newest first (a groom's "last time"). */
    var lastReports: List<SvcCareLastReport>? = null,
)

@Serializable
data class SvcCareLastReport(
    val appointmentId: String? = null,
    val at: String? = null,
    val serviceLabel: String? = null,
    val report: SvcReport? = null,
)

// MARK: - Due back (grooming rows of /provider-due)

@Serializable
data class SvcDueItem(
    val key: String,
    val reason: String,
    val title: String? = null,
    val dueAt: String,
    val days: Int? = null,
    val bucket: String,
    val followUpId: String? = null,
    val nextVisit: SvcDueNextVisit? = null,
    val pet: SvcPetRef,
    val client: SvcClientRef,
    val lastVisitAt: String? = null,
    val lastContact: SvcDueContact? = null,
    /** A visit booked for it that didn't happen (or was cancelled): why it is here again. */
    var missedVisitAt: String? = null,
    /** Put off until this day. */
    var snoozedUntil: String? = null,
    /** PawMiaw tells the family a week before, once. */
    var familyNotice: SvcDueFamilyNotice? = null,
) {
    val id: String get() = key
}

@Serializable
data class SvcDueFamilyNotice(val asked: Boolean? = null, val sentAt: String? = null)

@Serializable
data class SvcDueNextVisit(val id: String, val startsAt: String, val serviceLabel: String? = null)

@Serializable
data class SvcDueContact(val channel: String, val at: String)

@Serializable
data class SvcDueList(
    val now: String? = null,
    val windowDays: Int? = null,
    val items: List<SvcDueItem>,
)

@Serializable
data class SvcRemindResult(val channel: String? = null, val link: String? = null, val sentTo: Int? = null)

// MARK: - Home visits (Domicilio)

@Serializable
data class SvcStampSummary(
    val status: String,
    val recordedAt: String? = null,
    val distanceM: Double? = null,
    val far: Boolean? = null,
)

@Serializable
data class SvcHomeVisitPet(
    val id: String,
    val appointmentId: String? = null,
    val name: String,
    val species: String? = null,
    val breed: String? = null,
    val photoUrl: String? = null,
    val handlingTags: List<String>? = null,
    val careNotes: String? = null,
    val allergies: List<String>? = null,
)

@Serializable
data class SvcMapLinks(val google: String? = null, val apple: String? = null, val waze: String? = null)

@Serializable
data class SvcHomeAddress(
    val label: String? = null,
    val line: String? = null,
    val formatted: String? = null,
    val reference: String? = null,
    val hasCoordinates: Boolean? = null,
    val shared: Boolean? = null,
    val maps: SvcMapLinks? = null,
)

@Serializable
data class SvcHomeAccess(
    val notes: String? = null,
    val hasVault: Boolean? = null,
    val fields: List<String>? = null,
    val canReveal: Boolean? = null,
    val canEmergency: Boolean? = null,
    val reason: String? = null,
)

@Serializable
data class SvcOnMyWayState(
    val offered: Boolean? = null,
    val at: String? = null,
    val channel: String? = null,
    val minutes: Int? = null,
)

@Serializable
data class SvcHomeActions(
    val onMyWay: Boolean? = null,
    val arrive: Boolean? = null,
    val finish: Boolean? = null,
    val reassign: Boolean? = null,
)

@Serializable
data class SvcChecklistCount(val done: Int, val total: Int)

@Serializable
data class SvcRoutePresence(val tracking: Boolean? = null, val points: Int? = null)

@Serializable
data class SvcStamps(val arrive: SvcStampSummary? = null, val finish: SvcStampSummary? = null)

@Serializable
data class SvcHomeVisitItem(
    val id: String,
    val source: String,
    val appointmentId: String? = null,
    val stayId: String? = null,
    val stayVisitId: String? = null,
    val kind: String? = null,
    val status: String,
    var phase: String,
    val startsAt: String,
    val endsAt: String? = null,
    val bookedMinutes: Int? = null,
    var arrivedAt: String? = null,
    var finishedAt: String? = null,
    var late: Boolean? = null,
    var lateMinutes: Int? = null,
    var runningLong: Boolean? = null,
    val shortVisit: Boolean? = null,
    val serviceCategory: String? = null,
    val serviceId: String? = null,
    val serviceLabel: String? = null,
    val reportKind: String? = null,
    val hasReport: Boolean? = null,
    val reportMissing: List<String>? = null,
    val pets: List<SvcHomeVisitPet>,
    val client: SvcClientRef,
    val assigned: SvcPersonRef? = null,
    val address: SvcHomeAddress? = null,
    val access: SvcHomeAccess? = null,
    val instructions: String? = null,
    val checklist: SvcChecklistCount? = null,
    val onMyWay: SvcOnMyWayState? = null,
    val billing: String? = null,
    val stamps: SvcStamps? = null,
    var actions: SvcHomeActions? = null,
    /** Walks with routes on: whether it is recording and how many points it has. */
    var route: SvcRoutePresence? = null,
) {
    val isStay: Boolean get() = source == "stay_visit"
    val petNames: String get() = pets.joinToString(", ") { it.name }.nonEmpty ?: "—"
    val closed: Boolean get() = phase == "done" || phase == "missed"

    /** What the visit is: an appointment's own label; a home stay's visit by its kind. */
    val serviceText: String
        get() {
            if (!isStay) return serviceLabel.nonEmpty ?: "—"
            return when (kind) {
                "meet_greet" -> L("Meet & greet")
                "overnight" -> L("Overnight")
                else -> L("Drop-in")
            }
        }
}

@Serializable
data class SvcHomeViewer(
    val memberId: String? = null,
    val seesAll: Boolean? = null,
    val isManager: Boolean? = null,
    val canAct: Boolean? = null,
    val canStayCare: Boolean? = null,
    val canReassign: Boolean? = null,
    val seesBilling: Boolean? = null,
)

@Serializable
data class SvcHomeDaySettings(
    val lateAfterMinutes: Int? = null,
    val shortVisitPercent: Int? = null,
    val locationStamps: Boolean? = null,
    val walkRoutes: Boolean? = null,
    val onMyWayCategories: List<String>? = null,
)

@Serializable
data class SvcHomeCounts(
    val total: Int? = null,
    val upcoming: Int? = null,
    val inProgress: Int? = null,
    val done: Int? = null,
    val missed: Int? = null,
    val late: Int? = null,
    val runningLong: Int? = null,
)

@Serializable
data class SvcHomePerson(
    val memberId: String? = null,
    val name: String? = null,
    val image: String? = null,
    val total: Int,
    val done: Int,
    val inProgress: Int,
    val late: Int,
) {
    val key: String get() = memberId ?: "unassigned"
    val id: String get() = key
}

@Serializable
data class SvcHomeVisitsDay(
    val date: String,
    val timeZone: String? = null,
    val now: String? = null,
    val isToday: Boolean? = null,
    val viewer: SvcHomeViewer? = null,
    val settings: SvcHomeDaySettings? = null,
    val counts: SvcHomeCounts? = null,
    val people: List<SvcHomePerson>? = null,
    val items: List<SvcHomeVisitItem>,
)

@Serializable
data class SvcHomeVisitSettings(
    var autoStartedNotice: Boolean? = null,
    var onMyWayMessage: String? = null,
    var onMyWayCategories: List<String>? = null,
    var lateAfterMinutes: Int? = null,
    var shortVisitPercent: Int? = null,
    var locationStamps: Boolean? = null,
    var farFromAddressMeters: Int? = null,
    var walkRoutes: Boolean? = null,
    var locationRetentionDays: Int? = null,
    var locationDisclosure: SvcDisclosure? = null,
)

@Serializable
data class SvcDisclosure(val version: Int? = null, val acknowledgedAt: String? = null, val acknowledgedByName: String? = null)

@Serializable
data class SvcStamp(
    val status: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val capturedAt: String? = null,
    val recordedAt: String? = null,
    val recordedByName: String? = null,
    val distanceM: Double? = null,
    val far: Boolean? = null,
    val mapsUrl: String? = null,
)

@Serializable
data class SvcHomeVisitCheck(
    val bookedMinutes: Int? = null,
    val actualMinutes: Int? = null,
    val shortVisit: Boolean? = null,
    val shortVisitPercent: Int? = null,
    val address: SvcCheckAddress? = null,
    val stamps: SvcCheckStamps? = null,
)

@Serializable
data class SvcCheckAddress(val formatted: String? = null, val hasCoordinates: Boolean? = null)

@Serializable
data class SvcCheckStamps(val arrive: SvcStamp? = null, val finish: SvcStamp? = null)

@Serializable
data class SvcOnMyWayResult(val link: String? = null, val sentTo: Int? = null)

@Serializable
data class SvcVaultReveal(
    val vault: Map<String, String>,
    val revealedAt: String? = null,
)

// MARK: - Sessions (Paseos y clases)

@Serializable
data class SvcSessionPet(
    val appointmentId: String,
    val providerClientPetId: String,
    val providerClientId: String,
    val name: String,
    val species: String? = null,
    val photoUrl: String? = null,
    val status: String,
    val clientName: String,
) {
    val id: String get() = appointmentId
    val gone: Boolean get() = status in listOf("cancelled", "canceled", "no_show")
}

@Serializable
data class SvcSession(
    val id: String,
    val kind: String? = null,
    val status: String,
    val serviceLabel: String,
    val serviceCategory: String? = null,
    val serviceId: String? = null,
    val startsAt: String,
    val endsAt: String,
    val capacity: Int? = null,
    val priceAmount: Int? = null,
    val onlineBooking: Boolean? = null,
    val onlineBookingOpen: Boolean? = null,
    val size: Int,
    val pets: List<SvcSessionPet>,
    val locationId: String? = null,
    val assignedMember: SvcPersonRef? = null,
    val roomId: String? = null,
    val room: String? = null,
    val notes: String? = null,
    val cancelReason: String? = null,
    val placesLeft: Int? = null,
    val full: Boolean? = null,
    val waitlistCount: Int? = null,
    val startMode: String? = null,
    val meetingPoint: String? = null,
    val reportsMissing: Int? = null,
) {
    val staying: List<SvcSessionPet> get() = pets.filter { !it.gone }
    val isPickup: Boolean get() = startMode == "pickup"
    val active: Boolean get() = status == "active"
    val placesText: String get() = capacity?.let { "$size/$it" } ?: "$size"
}

@Serializable
data class SvcRosterEntry(
    val appointmentId: String,
    val status: String? = null,
    val arrivedAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val onMyWayAt: String? = null,
    val address: String? = null,
    val serviceAddressId: String? = null,
    val accessNotes: String? = null,
    val pickupOrder: Int? = null,
    val clientPhone: String? = null,
    val familyOnApp: Boolean? = null,
    val allergies: List<String>? = null,
    val report: SvcReport? = null,
    val reportMissing: List<String>? = null,
)

@Serializable
data class SvcWaitEntry(
    val id: String,
    val status: String,
    val createdAt: String,
    val notifiedAt: String? = null,
    val providerClientId: String,
    val providerClientPetId: String,
    val petName: String,
    val petPhotoUrl: String? = null,
    val clientName: String,
)

@Serializable
data class SvcSessionDetail(
    val id: String,
    val status: String,
    val serviceLabel: String,
    val serviceCategory: String? = null,
    val serviceId: String? = null,
    val startsAt: String,
    val endsAt: String,
    val capacity: Int? = null,
    val priceAmount: Int? = null,
    val onlineBooking: Boolean? = null,
    val onlineBookingOpen: Boolean? = null,
    val size: Int,
    val pets: List<SvcSessionPet>,
    val locationId: String? = null,
    val assignedMember: SvcPersonRef? = null,
    val roomId: String? = null,
    val room: String? = null,
    val notes: String? = null,
    val cancelReason: String? = null,
    val placesLeft: Int? = null,
    val full: Boolean? = null,
    val waitlistCount: Int? = null,
    val startMode: String? = null,
    val meetingPoint: String? = null,
    val reportsMissing: Int? = null,
    val roster: List<SvcRosterEntry>? = null,
    val waitlist: List<SvcWaitEntry>? = null,
) {
    val staying: List<SvcSessionPet> get() = pets.filter { !it.gone }
    val isPickup: Boolean get() = startMode == "pickup"
    val active: Boolean get() = status == "active"
    val placesText: String get() = capacity?.let { "$size/$it" } ?: "$size"
    fun entry(appointmentId: String): SvcRosterEntry? = roster?.firstOrNull { it.appointmentId == appointmentId }

    val booked: Int get() = staying.count { it.status == "scheduled" }
    val arrived: Int get() = staying.count { it.status == "checked_in" }
    val withThem: Int get() = staying.count { it.status == "in_progress" }
    val doneCount: Int get() = staying.count { it.status == "completed" }
    val started: Boolean get() = staying.any { it.status == "in_progress" || it.status == "completed" }

    /** The pets in the order they're seen: a pickup walk's stops first, those who left last. */
    val rosterRows: List<SvcSessionPet>
        get() = pets.withIndex().sortedWith { a, b ->
            val goneA = if (a.value.gone) 1 else 0
            val goneB = if (b.value.gone) 1 else 0
            if (goneA != goneB) return@sortedWith goneA.compareTo(goneB)
            if (isPickup) {
                val oa = entry(a.value.appointmentId)?.pickupOrder ?: Int.MAX_VALUE
                val ob = entry(b.value.appointmentId)?.pickupOrder ?: Int.MAX_VALUE
                if (oa != ob) return@sortedWith oa.compareTo(ob)
            }
            a.index.compareTo(b.index)
        }.map { it.value }
}

@Serializable
data class SvcSessionMoveResult(
    val moved: List<String>? = null,
    val skipped: List<SvcSkipped>? = null,
)

@Serializable
data class SvcSkipped(val appointmentId: String? = null, val error: String? = null)

@Serializable
data class SvcSessionCancelResult(val notifiedFamilies: Int? = null)

@Serializable
data class SvcRoom(
    val id: String,
    val name: String,
    val status: String? = null,
    val kind: String? = null,
)

// MARK: - People and clients

@Serializable
data class SvcMember(
    val id: String,
    val name: String? = null,
    val userName: String? = null,
    val email: String? = null,
    val image: String? = null,
    val displayName: String? = null,
    val effectivePermissions: List<String>? = null,
    val locationIds: List<String>? = null,
    val isCurrentMember: Boolean? = null,
    val status: String? = null,
) {
    val shownName: String get() = displayName.nonEmpty ?: name.nonEmpty ?: userName.nonEmpty ?: email.nonEmpty ?: L("Someone")
    fun can(permission: Permission): Boolean = (effectivePermissions ?: emptyList()).contains(permission.rawValue)
}

@Serializable
data class SvcClientPetLite(
    val id: String,
    val petName: String,
    val petSpecies: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
)

@Serializable
data class SvcClientLite(
    val id: String,
    val ownerName: String,
    val ownerPhone: String? = null,
    val householdId: String? = null,
    val pets: List<SvcClientPetLite>? = null,
)

// MARK: - A visit, as its report and its finish need it

@Serializable
data class SvcAppointment(
    val id: String,
    val status: String,
    val serviceCategory: String? = null,
    val serviceLabel: String? = null,
    val petName: String? = null,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val clientHouseholdId: String? = null,
    val startedAt: String? = null,
    val report: SvcReport? = null,
    val reportKind: String? = null,
    val reportUpdatedAt: String? = null,
)

@Serializable
data class SvcUploadResult(val url: String)

/** An answer whose body is not read (iOS `SvcIgnored: Decodable {}`). */
@Serializable
class SvcIgnored

// MARK: - The report at the end of a visit (lib/service-reports.ts)

/**
 * One class for the three kinds (groom, walk, training): only the kind's
 * own fields are sent, and a walk's route (drawn by the server) never is.
 * `SvcReport(kind = "walk")` is Swift's `init(kind:)`.
 */
@Serializable(with = SvcReportSerializer::class)
data class SvcReport(
    var kind: String,
    // Groom
    var services: List<String> = emptyList(),
    var style: String? = null,
    var styleKind: String? = null,
    var length: String? = null,
    var differenceNote: String? = null,
    var coat: String? = null,
    var skin: List<String> = emptyList(),
    var beforePhotoUrl: String? = null,
    var afterPhotoUrl: String? = null,
    // Walk
    var minutes: Int? = null,
    var distanceKm: Double? = null,
    var pee: Boolean? = null,
    var poop: String? = null,
    var water: Boolean? = null,
    var ate: String? = null,
    var medsGiven: Boolean? = null,
    var route: JSONValue? = null,
    // Training
    var skills: List<String> = emptyList(),
    var progress: String? = null,
    var homework: String? = null,
    // All
    var behaviour: String? = null,
    var photoUrl: String? = null,
    var note: String? = null,
)

/** The Swift `init(from:)` / `encode(to:)`: a field of the wrong type reads as absent; only the kind's fields are written. */
object SvcReportSerializer : KSerializer<SvcReport> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SvcReport")

    override fun deserialize(decoder: Decoder): SvcReport {
        val c = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected a JSON object")
        return SvcReport(
            kind = c.text("kind") ?: "groom",
            services = c.texts("services") ?: emptyList(),
            style = c.text("style"),
            styleKind = c.text("styleKind"),
            length = c.text("length"),
            differenceNote = c.text("differenceNote"),
            coat = c.text("coat"),
            skin = c.texts("skin") ?: emptyList(),
            beforePhotoUrl = c.text("beforePhotoUrl"),
            afterPhotoUrl = c.text("afterPhotoUrl"),
            minutes = c.number("minutes")?.toInt(),
            distanceKm = c.number("distanceKm"),
            pee = c.flag("pee"),
            poop = c.text("poop"),
            water = c.flag("water"),
            ate = c.text("ate"),
            medsGiven = c.flag("medsGiven"),
            route = c["route"]?.takeIf { it !is JsonNull },
            skills = c.texts("skills") ?: emptyList(),
            progress = c.text("progress"),
            homework = c.text("homework"),
            behaviour = c.text("behaviour"),
            photoUrl = c.text("photoUrl"),
            note = c.text("note"),
        )
    }

    override fun serialize(encoder: Encoder, value: SvcReport) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("SvcReport is written as JSON only")
        json.encodeJsonElement(buildJsonObject {
            put("kind", value.kind)
            when (value.kind) {
                "groom" -> {
                    putList("services", value.services)
                    putIfPresent("style", value.style)
                    putIfPresent("styleKind", value.styleKind)
                    putIfPresent("length", value.length)
                    putIfPresent("differenceNote", value.differenceNote)
                    putIfPresent("coat", value.coat)
                    putList("skin", value.skin)
                    putIfPresent("beforePhotoUrl", value.beforePhotoUrl)
                    putIfPresent("afterPhotoUrl", value.afterPhotoUrl)
                }
                "walk" -> {
                    value.minutes?.let { put("minutes", it) }
                    value.distanceKm?.let { put("distanceKm", it) }
                    value.pee?.let { put("pee", it) }
                    putIfPresent("poop", value.poop)
                    value.water?.let { put("water", it) }
                    putIfPresent("ate", value.ate)
                    value.medsGiven?.let { put("medsGiven", it) }
                    putIfPresent("photoUrl", value.photoUrl)
                }
                else -> {
                    putList("skills", value.skills)
                    putIfPresent("progress", value.progress)
                    putIfPresent("homework", value.homework)
                    putIfPresent("photoUrl", value.photoUrl)
                }
            }
            putIfPresent("behaviour", value.behaviour)
            putIfPresent("note", value.note)
        })
    }

    private fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private fun JsonObjectBuilder.putList(key: String, value: List<String>) {
        put(key, JsonArray(value.map { JsonPrimitive(it) }))
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A list of strings; anything else in it and the whole list reads as absent. */
    private fun JsonObject.texts(key: String): List<String>? {
        val list = this[key] as? JsonArray ?: return null
        return list.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull()

    private fun JsonObject.flag(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toBooleanStrictOrNull()
}
