package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

//
//  StaysModels.kt (iOS StaysModels.swift)
//
//  Stays (boarding, daycare and home stays): the shapes the web's hooks read
//  (use-provider-stays.ts, use-provider-homestays.ts, use-family-requests.ts,
//  use-provider-online-booking.ts), the requests they send, and the small
//  helpers every stays screen shares (days, clock times, words). The rules
//  live on the server (stay-rules.ts); backend docs/stays-late-and-dates.md
//  and docs/charging-flow.md.
//

// MARK: - Settings and rooms

@Serializable
data class StaysSettings(
    var checkInTime: String? = null,
    var checkOutTime: String? = null,
    var daycareStartTime: String? = null,
    var daycareEndTime: String? = null,
    var requiredVaccines: List<String>? = null,
    var currency: String? = null,
)

@Serializable
data class StaysUnit(
    val id: String,
    val name: String,
    val kind: String? = null,
    val species: String? = null,
    val capacity: Int? = null,
    val nightlyRateCents: Int? = null,
    val dayRateCents: Int? = null,
    val notes: String? = null,
    val photoUrl: String? = null,
    val sortOrder: Int? = null,
    val status: String? = null,
    val locationId: String? = null,
) {
    val unitKind: String get() = kind ?: "kennel"
    val seats: Int get() = maxOf(1, capacity ?: 1)
}

// MARK: - A stay

@Serializable
data class StaysCareItem(
    var key: String,
    var kind: String,
    var label: String,
    var time: String? = null,
    var source: String? = null,
    var sourceRoutineId: String? = null,
) {
    val id: String get() = key

    /** The JSON the server takes (`null` for an empty time). */
    val body: Map<String, Any?>
        get() = mapOf("key" to key, "kind" to kind, "label" to label, "time" to time.nonEmpty, "source" to (source ?: "front_desk"), "sourceRoutineId" to sourceRoutineId)
}

@Serializable
data class StaysCarePlan(
    var items: List<StaysCareItem>? = null,
    var notes: String? = null,
)

@Serializable
data class StaysVaccineCheck(
    val key: String,
    val status: String,
    val matchedName: String? = null,
    val validUntil: String? = null,
    val assumedOneYear: Boolean? = null,
)

@Serializable
data class StaysPet(
    val id: String,
    val providerClientPetId: String? = null,
    val petName: String,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val photoUrl: String? = null,
    val linkedToHousehold: Boolean? = null,
    val carePlan: StaysCarePlan? = null,
    val vaccineCheck: List<StaysVaccineCheck>? = null,
    val vaccinesConfirmedAt: String? = null,
    val vaccinesConfirmedByName: String? = null,
    val allergies: List<String>? = null,
)

@Serializable
data class StaysUpdate(
    val id: String,
    val stayPetId: String? = null,
    val kind: String? = null,
    val visibility: String? = null,
    val message: String? = null,
    val mood: String? = null,
    val photoUrl: String? = null,
    val authorName: String? = null,
    val visitId: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class StaysContact(
    val name: String? = null,
    val phone: String? = null,
    val relationship: String? = null,
)

/** A home stay on the board and in lists (`home` on a summary). */
@Serializable
data class StaysHomeSummary(
    val sitterMemberId: String? = null,
    val sitterName: String? = null,
    /** A line on the list; an object on the full home stay (read with `addressText`). */
    val address: JSONValue? = null,
    val mode: String? = null,
    val priceUnit: String? = null,
    val units: Int? = null,
) {
    val addressText: String? get() = address.string ?: address["formatted"].string
}

/** A stay as the list and the detail send it (the detail adds `updates`). */
@Serializable
data class StaysStay(
    val id: String,
    val kind: String,
    val status: String,
    val providerClientId: String? = null,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val clientEmail: String? = null,
    val unitId: String? = null,
    val unitName: String? = null,
    val unitKind: String? = null,
    val checkInDay: String,
    val checkInTime: String? = null,
    val checkOutDay: String,
    val checkOutTime: String? = null,
    val checkedInAt: String? = null,
    val checkedOutAt: String? = null,
    val today: String? = null,
    val lateArrival: Boolean? = null,
    val lateDeparture: Boolean? = null,
    val heldUntilDay: String? = null,
    val rateCents: Int? = null,
    val currency: String? = null,
    val billableUnits: Int? = null,
    val estimatedTotalCents: Int? = null,
    val ownerInstructions: String? = null,
    val internalNotes: String? = null,
    val belongings: List<String>? = null,
    val emergencyContact: StaysContact? = null,
    val cancellationReason: String? = null,
    val invoiceId: String? = null,
    val createdAt: String? = null,
    val pets: List<StaysPet>? = null,
    val home: StaysHomeSummary? = null,
    val updates: List<StaysUpdate>? = null,
) {
    val petList: List<StaysPet> get() = pets ?: emptyList()
    val petNames: String get() = petList.joinToString(", ") { it.petName }
    val petCount: Int get() = maxOf(1, petList.size)
    val isHome: Boolean get() = kind == "home_sitting"
    val isDaycare: Boolean get() = kind == "daycare"
    val isOpen: Boolean get() = status in listOf("requested", "confirmed", "checked_in")
    val familyOnApp: Boolean get() = petList.any { it.linkedToHousehold == true }
    val rate: Int get() = rateCents ?: 0
    val stayCurrency: String get() = currency ?: Workspace.shared.currency

    /** A day late either way (stays-late-and-dates.md). */
    val late: StaysLate?
        get() {
            if (lateDeparture == true) return StaysLate.departure
            if (lateArrival == true) return StaysLate.arrival
            return null
        }
}

enum class StaysLate { arrival, departure }

@Serializable
data class StaysPage(
    val today: String? = null,
    val items: List<StaysStay>? = null,
)

@Serializable
data class StaysIntakePet(
    val stayPetId: String,
    val petName: String? = null,
    val linkedToHousehold: Boolean? = null,
    val canReadRoutines: Boolean? = null,
    val canReadVaccines: Boolean? = null,
    val vaccines: List<StaysVaccineCheck>? = null,
    val suggestedCare: List<StaysCareItem>? = null,
    val allergies: List<String>? = null,
    val carePlan: StaysCarePlan? = null,
) {
    val id: String get() = stayPetId
}

@Serializable
data class StaysIntake(
    val stayId: String? = null,
    val pets: List<StaysIntakePet>? = null,
    val suggestedEmergencyContact: StaysContact? = null,
)

// MARK: - The day's care

@Serializable
data class StaysCareTask(
    val id: String,
    val kind: String? = null,
    val label: String? = null,
    val time: String? = null,
    var status: String,
    val doneAt: String? = null,
    val doneByName: String? = null,
    val note: String? = null,
)

@Serializable
data class StaysVaccineIssue(
    val key: String,
    val status: String,
)

@Serializable
data class StaysCarePet(
    val stayId: String,
    val stayPetId: String,
    val petName: String,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val photoUrl: String? = null,
    val kind: String? = null,
    val unitName: String? = null,
    val checkInDay: String? = null,
    val checkOutDay: String? = null,
    val checkOutTime: String? = null,
    val lateDeparture: Boolean? = null,
    val nightNumber: Int? = null,
    val totalNights: Int? = null,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val rateCents: Int? = null,
    val currency: String? = null,
    val estimatedTotalCents: Int? = null,
    val ownerInstructions: String? = null,
    val belongings: List<String>? = null,
    val vaccineIssues: List<StaysVaccineIssue>? = null,
    val allergies: List<String>? = null,
    val lastReportAt: String? = null,
    val careNotes: String? = null,
    var tasks: List<StaysCareTask>? = null,
) {
    val id: String get() = stayPetId
}

@Serializable
data class StaysCareBoard(
    val dayKey: String? = null,
    var pets: List<StaysCarePet>? = null,
)

// MARK: - Home stays

@Serializable
data class StaysHomeMember(
    val memberId: String? = null,
    val name: String? = null,
    val photoUrl: String? = null,
)

@Serializable
data class StaysHomeVisit(
    val id: String,
    val stayId: String? = null,
    val kind: String? = null,
    val dayKey: String,
    val startsAt: String,
    val endsAt: String? = null,
    val status: String? = null,
    val late: Boolean? = null,
    val sitter: StaysHomeMember? = null,
    val arrivedAt: String? = null,
    val arrivedByName: String? = null,
    val leftAt: String? = null,
    val leftByName: String? = null,
    val missedAt: String? = null,
    val noAccessAt: String? = null,
    val note: String? = null,
    val cancelReason: String? = null,
    val doubleBooked: Boolean? = null,
)

@Serializable
data class StaysHomeAddress(
    val label: String? = null,
    val formatted: String? = null,
    val reference: String? = null,
    val mapsUrl: String? = null,
    val source: String? = null,
    val shared: Boolean? = null,
)

@Serializable
data class StaysHomeToday(
    val dayKey: String? = null,
    val total: Int? = null,
    val done: Int? = null,
    val inProgress: Int? = null,
    val missed: Int? = null,
)

@Serializable
data class StaysKeyHolder(val memberId: String? = null, val name: String? = null)

@Serializable
data class StaysKeyEvent(
    val id: String,
    val action: String? = null,
    val holderName: String? = null,
    val label: String? = null,
    val note: String? = null,
    val occurredAt: String? = null,
    val byName: String? = null,
)

@Serializable
data class StaysHomeKeys(
    val state: String? = null,
    val holder: StaysKeyHolder? = null,
    val label: String? = null,
    val since: String? = null,
    val events: List<StaysKeyEvent>? = null,
)

@Serializable
data class StaysVisitPlan(
    val times: List<String>? = null,
    val durationMinutes: Int? = null,
    val overnightStart: String? = null,
    val overnightEnd: String? = null,
)

@Serializable
data class StaysHouseTask(
    val key: String? = null,
    val label: String? = null,
    val time: String? = null,
)

@Serializable
data class StaysCatalogRef(val id: String? = null, val name: String? = null, val priceCents: Int? = null)

@Serializable
data class StaysHomeDetail(
    val address: StaysHomeAddress? = null,
    val addressId: String? = null,
    val accessNotes: String? = null,
    val sitter: StaysHomeMember? = null,
    val mode: String? = null,
    val plan: StaysVisitPlan? = null,
    val priceUnit: String? = null,
    val rateCents: Int? = null,
    val catalogItem: StaysCatalogRef? = null,
    val units: Int? = null,
    val estimatedTotalCents: Int? = null,
    val depositCents: Int? = null,
    val depositPaidCents: Int? = null,
    val vetAuthorizationCents: Int? = null,
    val houseRules: String? = null,
    val houseTasks: List<StaysHouseTask>? = null,
    val quoteSentAt: String? = null,
    val quoteAcceptedAt: String? = null,
    val timeZone: String? = null,
    val today: StaysHomeToday? = null,
    val visits: List<StaysHomeVisit>? = null,
    val keys: StaysHomeKeys? = null,
    val emergencyContact: StaysContact? = null,
    val familyEmergencyContact: StaysContact? = null,
    /** The family's access details for the address: what they hold and whether this person may open them. */
    val vault: StaysHomeVault? = null,
    val serviceId: String? = null,
) {
    val zone: ZoneId get() = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Workspace.shared.timeZone
    val depositOwed: Int get() = maxOf(0, (depositCents ?: 0) - (depositPaidCents ?: 0))
}

@Serializable
data class StaysVaultWindow(
    val opensAt: String? = null,
    val closesAt: String? = null,
    val open: Boolean? = null,
)

/** The access details of a home stay's address (never their contents: those come from the audited reveal). */
@Serializable
data class StaysHomeVault(
    val hasVault: Boolean? = null,
    val fields: List<String>? = null,
    val window: StaysVaultWindow? = null,
    val windows: List<StaysVaultWindow>? = null,
    val canReveal: Boolean? = null,
    /** An owner or admin outside the stay may open them, saying why (the family is shown it). */
    val canEmergency: Boolean? = null,
    val reason: String? = null,
    val assigned: Boolean? = null,
    val open: Boolean? = null,
)

/** One item of a visit's checklist: a pet's care or a house task. */
@Serializable
data class StaysChecklistTask(
    val id: String,
    val label: String? = null,
    val time: String? = null,
    val status: String? = null,
    val doneAt: String? = null,
    val doneByName: String? = null,
    val note: String? = null,
    val kind: String? = null,
)

@Serializable
data class StaysVisitPet(
    val stayPetId: String? = null,
    val petName: String? = null,
    val photoUrl: String? = null,
    val careNotes: String? = null,
    val tasks: List<StaysChecklistTask>? = null,
)

@Serializable
data class StaysVisitReport(
    val id: String,
    val message: String? = null,
    val mood: String? = null,
    val photoUrl: String? = null,
    val stayPetId: String? = null,
    val createdAt: String? = null,
)

/** What a visit's detail adds to the visit (`/provider-homestays/{id}/visits/{visitId}`). */
@Serializable
data class StaysVisitExtras(
    val pets: List<StaysVisitPet>? = null,
    val houseTasks: List<StaysChecklistTask>? = null,
    val houseRules: String? = null,
    val reports: List<StaysVisitReport>? = null,
    val pendingTasks: Int? = null,
)

/** The home stay's own `home` (from `/provider-homestays/{id}`), read beside the stay itself. */
@Serializable
data class StaysHomeEnvelope(val home: StaysHomeDetail? = null)

/** A day's home visits (the header's "Visitas de cuidado en casa hoy"). */
@Serializable
data class StaysHomeDayVisit(
    val id: String,
    val status: String? = null,
    val late: Boolean? = null,
)

@Serializable
data class StaysHomeDay(
    val dayKey: String? = null,
    val visits: List<StaysHomeDayVisit>? = null,
)

// MARK: - Families' requests and online bookings

@Serializable
data class StaysNamed(val id: String? = null, val name: String? = null)

@Serializable
data class StaysRequestPet(
    val petId: String? = null,
    val providerClientPetId: String? = null,
    val name: String? = null,
    val species: String? = null,
    val photoUrl: String? = null,
    val vaccines: List<StaysVaccineCheck>? = null,
)

@Serializable
data class StaysRequest(
    val id: String,
    val kind: String,
    val status: String,
    val displayStatus: String? = null,
    val startDay: String,
    val endDay: String,
    val proposedStartDay: String? = null,
    val proposedEndDay: String? = null,
    val notes: String? = null,
    val address: String? = null,
    val program: String? = null,
    val depositPercent: Int? = null,
    val providerMessage: String? = null,
    val familyAcceptedAt: String? = null,
    val respondedAt: String? = null,
    val cancelledAt: String? = null,
    val stayIds: List<String>? = null,
    val appointmentIds: List<String>? = null,
    val providerClientId: String? = null,
    val locationId: String? = null,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val location: StaysNamed? = null,
    val createdAt: String? = null,
    val pets: List<StaysRequestPet>? = null,
) {
    val shownStatus: String get() = displayStatus ?: status
    val petNames: String get() = (pets ?: emptyList()).mapNotNull { it.name }.joinToString(", ")
    val isStayKind: Boolean get() = kind == "boarding" || kind == "daycare"
}

@Serializable
data class StaysRequestList(val items: List<StaysRequest>? = null)

/** A service of the business's visit catalog (`/appointment-services?scope=provider`), for a program's sessions. */
@Serializable
data class StaysProgramService(
    val id: String,
    val label: String? = null,
    val category: String? = null,
    val defaultDurationMinutes: JSONValue? = null,
)

@Serializable
data class StaysProgramCatalog(val services: List<StaysProgramService>? = null)

@Serializable
data class StaysOnlineClient(val id: String? = null, val name: String? = null, val phone: String? = null)

@Serializable
data class StaysOnlinePet(
    val appointmentId: String? = null,
    val providerClientPetId: String? = null,
    val name: String? = null,
    val species: String? = null,
    val photoUrl: String? = null,
)

@Serializable
data class StaysOnlineRequest(
    /** "request" or "reschedule" (a family asks to move a confirmed booking); none from an older server. */
    val kind: String? = null,
    val appointmentId: String,
    /** A reschedule: the visit's time now (the time asked for is in `rescheduleProposal`). */
    val startsAt: String? = null,
    val serviceLabel: String? = null,
    val atHome: Boolean? = null,
    val notes: String? = null,
    /** A reschedule: when the ask expires. */
    val releasesAt: String? = null,
    val client: StaysOnlineClient? = null,
    val pets: List<StaysOnlinePet>? = null,
    val rescheduleProposal: SchedRescheduleProposal? = null,
) {
    val id: String get() = appointmentId
    val isReschedule: Boolean get() = kind == "reschedule"
}

@Serializable
data class StaysOnlineList(
    val count: Int? = null,
    val items: List<StaysOnlineRequest>? = null,
)

// MARK: - Pickers

@Serializable
data class StaysMember(
    val id: String,
    val name: String? = null,
    val userName: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val image: String? = null,
    val effectivePermissions: List<String>? = null,
    val status: String? = null,
    val isCurrentMember: Boolean? = null,
) {
    val label: String get() = displayName.nonEmpty ?: name.nonEmpty ?: userName.nonEmpty ?: email ?: ""
    val canCare: Boolean get() = (effectivePermissions ?: emptyList()).contains(Permission.staysCare.rawValue) && status != "suspended"
}

@Serializable
data class StaysInvoiceTotal(val totalAmount: Int? = null)

@Serializable
data class StaysClientAddress(
    val id: String,
    val label: String? = null,
    val formatted: String? = null,
    val isDefault: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class StaysClientHome(val addresses: List<StaysClientAddress>? = null)

// MARK: - A stay as consents and incidents see it

object StaysActs {
    /** A stay's pets as consents and incidents name them (their client-pet ids). */
    fun pets(stay: StaysStay): List<ActPet> =
        stay.petList.mapNotNull { pet -> pet.providerClientPetId?.let { ActPet(id = it, name = pet.petName) } }

    /** What a stay's Consents card knows: boarding, daycare or a home stay. */
    fun consentContext(stay: StaysStay, businessName: String?): ConsentContext {
        val kind = if (stay.isHome) L("Home sitting") else if (stay.isDaycare) L("Daycare") else L("Boarding")
        val days = if (stay.isDaycare) StaysDay.short(stay.checkInDay) else "${StaysDay.short(stay.checkInDay)} – ${StaysDay.short(stay.checkOutDay)}"
        return ConsentContext(
            act = ConsentAct(stayId = stay.id),
            businessName = businessName,
            clientName = stay.clientName ?: "",
            familyOnApp = stay.familyOnApp,
            clientEmail = stay.clientEmail,
            clientPhone = stay.clientPhone,
            pets = pets(stay),
            actLabel = "$kind · $days",
            started = stay.status == "checked_in" || stay.status == "checked_out",
            currency = stay.stayCurrency,
        )
    }
}

// MARK: - Requests to the server

object StaysAPI {
    /** A home stay's full detail: the stay and its `home`, from one answer. */
    suspend fun homeStay(id: String): Pair<StaysStay, StaysHomeDetail?> {
        val data = API.data("GET", "/provider-homestays/$id", emptyMap(), null)
        val stay: StaysStay = API.decode(data)
        val envelope: StaysHomeEnvelope = runCatching { API.decode<StaysHomeEnvelope>(data) }.getOrNull() ?: StaysHomeEnvelope(home = null)
        return Pair(stay, envelope.home)
    }
}

// MARK: - Days and clock times

object StaysDay {
    private val utc: ZoneId = ZoneOffset.UTC

    /** Noon UTC of a "yyyy-MM-dd" day: the calendar day, never shifted by a clock. */
    fun date(key: String): Instant? =
        runCatching { LocalDate.parse(key.take(10)) }.getOrNull()?.atTime(12, 0)?.toInstant(ZoneOffset.UTC)

    fun key(date: Instant): String = date.atZone(utc).toLocalDate().toString()

    /** Today on the business's clock. */
    fun today(): String = DateText.dayKey()

    fun add(key: String, days: Int): String {
        val date = date(key) ?: return key
        return key(date.plusSeconds(days * 86_400L))
    }

    fun between(from: String, to: String): Int {
        val a = date(from) ?: return 0
        val b = date(to) ?: return 0
        return swiftRounded((b.epochSecond - a.epochSecond) / 86_400.0).toInt()
    }

    fun format(key: String?, template: String = "EEEdMMM"): String {
        val date = key?.let { date(it) } ?: return "—"
        return DateText.format(date, template, utc)
    }

    /** "jue 1 oct". */
    fun short(key: String?): String = format(key)
    /** "1 oct". */
    fun dayMonth(key: String?): String = format(key, "dMMM")
    /** "jueves 1 de octubre". */
    fun long(key: String?): String = format(key, "EEEEdMMMM")

    fun isWeekend(key: String): Boolean {
        val date = date(key) ?: return false
        val weekday = date.atZone(utc).dayOfWeek
        return weekday == DayOfWeek.SUNDAY || weekday == DayOfWeek.SATURDAY
    }

    /** Minutes after midnight of a "HH:mm" clock time. */
    fun minutes(value: String): Int {
        val parts = value.split(":").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        return if (parts.size >= 2) parts[0] * 60 + parts[1] else 0
    }

    /** "2:00 p.m." from "14:00". */
    fun clock(value: String?): String {
        if (value == null || value.length < 4) return ""
        val parts = value.split(":").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return value
        val date = LocalDateTime.of(2000, 1, 1, 0, 0).plusHours(parts[0].toLong()).plusMinutes(parts[1].toLong()).toInstant(ZoneOffset.UTC)
        return DateText.format(date, "jmm", utc)
    }

    /** "HH:mm" of a moment on a place's clock (the value a time field holds). */
    fun clockKey(iso: String?, zone: ZoneId): String {
        val date = DateText.parse(iso) ?: return ""
        return DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone).format(date)
    }

    /** "18:30" on a clock, from an instant. */
    fun clockAt(iso: String?, zone: ZoneId): String = DateText.time(DateText.parse(iso), zone)

    /** Clock times every `step` minutes between two "HH:MM". */
    fun times(from: String = "00:00", to: String = "23:45", step: Int = 15): List<String> {
        fun minutes(s: String): Int {
            val p = s.split(":").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
            return if (p.size == 2) p[0] * 60 + p[1] else 0
        }
        return (minutes(from)..minutes(to) step step).map { String.format(Locale.US, "%02d:%02d", it / 60, it % 60) }
    }

    /** Nights (or days of daycare) a stay counts, as on the server. */
    fun nights(kind: String, from: String, to: String): Int =
        if (kind == "daycare") maxOf(1, between(from, to) + 1) else maxOf(1, between(from, to))

    /** Nights (or the day) a stay holds its room. */
    fun occupied(kind: String, from: String, to: String): List<String> {
        val length = if (kind == "daycare") 1 else maxOf(between(from, to), 1)
        return (0 until length).map { add(from, it) }
    }

    fun isRoomHolding(status: String): Boolean = status in listOf("requested", "confirmed", "checked_in")

    /** A playroom takes daycare, never a night. */
    fun roomTakes(unitKind: String, stayKind: String): Boolean = stayKind != "boarding" || unitKind != "playroom"

    /** The first day a room would be over capacity with this booking, or null. */
    fun firstFullDay(capacity: Int, stays: List<StaysStay>, unitId: String, kind: String, from: String, to: String, pets: Int, ignore: String? = null): String? {
        val load = mutableMapOf<String, Int>()
        for (stay in stays) {
            if (stay.unitId != unitId || stay.id == ignore || !isRoomHolding(stay.status)) continue
            for (day in occupied(kind = stay.kind, from = stay.checkInDay, to = stay.heldUntilDay ?: stay.checkOutDay)) {
                load[day] = (load[day] ?: 0) + stay.petCount
            }
        }
        for (day in occupied(kind = kind, from = from, to = to)) if ((load[day] ?: 0) + pets > capacity) return day
        return null
    }
}

// MARK: - Words

/** One of `StaysWords.moods` (Swift's `(value:, emoji:, label:)` tuple); `label` is the English key. */
data class StaysMood(val value: String, val emoji: String, val label: String)

object StaysWords {
    fun kind(kind: String): String = when (kind) {
        "daycare" -> L("Daycare")
        "home_sitting" -> L("Home sitting")
        else -> L("Boarding")
    }

    fun status(status: String): String = when (status) {
        "requested" -> L("Requested")
        "confirmed" -> L("Confirmed")
        "checked_in" -> L("Staying")
        "checked_out" -> L("Checked out")
        "cancelled" -> L("Cancelled (stay)")
        "no_show" -> L("No-show")
        else -> status
    }

    fun unitKind(kind: String?): String = when (kind) {
        "suite" -> L("Suite")
        "condo" -> L("Cat condo")
        "run" -> L("Run")
        "playroom" -> L("Playroom")
        else -> L("Kennel")
    }

    val unitKinds = listOf("kennel", "suite", "condo", "run", "playroom")

    fun species(species: String?): String = when (species) {
        "any" -> L("All pet types")
        "dog" -> L("Dogs")
        "cat" -> L("Cats")
        "bird" -> L("Birds")
        "rabbit" -> L("Rabbits")
        "fish" -> L("Fish")
        "reptile" -> L("Reptiles")
        "small_pet" -> L("Small pets (hamsters, guinea pigs, etc.)")
        else -> L("Other animals")
    }

    val speciesKeys = listOf("any", "dog", "cat", "bird", "rabbit", "fish", "reptile", "small_pet", "other")

    fun petSpecies(species: String?): String = when (species?.lowercase()) {
        "cat" -> L("Cat")
        "dog" -> L("Dog")
        // Swift `.capitalized`: each word's first letter up, the rest down.
        else -> (species ?: "").split(" ").joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.titlecase() } }
    }

    fun vaccine(key: String): String = when (key) {
        "rabies" -> L("Rabies")
        "dhpp" -> L("DHPP (distemper, parvo)")
        "bordetella" -> L("Bordetella (kennel cough)")
        "canine_influenza" -> L("Canine influenza")
        "fvrcp" -> L("FVRCP (feline triple)")
        "felv" -> L("FeLV (feline leukemia)")
        else -> key
    }

    /** "Rabia" without the explanation in brackets. */
    fun vaccineShort(key: String): String = vaccine(key).split(" (").firstOrNull() ?: vaccine(key)

    val vaccineKeys = listOf("rabies", "dhpp", "bordetella", "canine_influenza", "fvrcp", "felv")

    fun vaccineSpecies(key: String): String = when (key) {
        "rabies" -> "both"
        "fvrcp", "felv" -> "cat"
        else -> "dog"
    }

    fun vaccineStatus(status: String): String = when (status) {
        "current" -> L("Up to date")
        "expires_during_stay" -> L("Expires during the stay")
        "expired" -> L("Expired")
        else -> L("Not on file")
    }

    fun task(kind: String): String = when (kind) {
        "meal" -> L("Meal")
        "medication" -> L("Medication")
        "walk" -> L("Walk")
        "play" -> L("Play")
        else -> L("Care")
    }

    val taskKinds = listOf("meal", "medication", "walk", "play", "custom")

    fun taskIcon(kind: String?): String = when (kind) {
        // The web's TASK_ICONS (care-plan-editor.tsx).
        "meal" -> "utensils-crossed"
        "medication" -> "pill"
        "walk" -> "footprints"
        "play" -> "bone"
        else -> "heart-handshake"
    }

    val moods: List<StaysMood> = listOf(
        StaysMood("happy", "😊", "Happy"), StaysMood("playful", "🎾", "Playful"), StaysMood("calm", "😌", "Calm"),
        StaysMood("shy", "🙈", "Shy"), StaysMood("tired", "😴", "Sleepy"),
    )

    /** The mood's emoji and its words in the screen's language. */
    fun mood(value: String?): StaysMood? = moods.firstOrNull { it.value == value }?.let { it.copy(label = L(it.label)) }

    /** "in 3 hours", "in 40 min", "now": how long until an online booking is released. */
    fun releaseIn(releasesAt: String?): String? {
        val date = DateText.parse(releasesAt) ?: return null
        val minutes = swiftRounded((date.toEpochMilli() - System.currentTimeMillis()) / 60_000.0).toInt()
        if (minutes <= 1) return L("now")
        if (minutes < 60) return LF("in %@ min", minutes.toString())
        val hours = swiftRounded(minutes / 60.0).toInt()
        return if (hours == 1) L("in 1 hour") else LF("in %@ hours", hours.toString())
    }

    /** "3 nights" / "1 day". */
    fun units(kind: String, count: Int): String =
        if (kind == "daycare") LP(count, "%d day", "%d days") else LP(count, "%d night", "%d nights")

    fun requestKind(kind: String): String = when (kind) {
        "boarding" -> L("Hotel stay")
        "daycare" -> L("Daycare")
        "homestay" -> L("Home stay")
        else -> L("Program")
    }

    fun requestKindIcon(kind: String): String = when (kind) {
        "daycare" -> "sun.max.fill"
        "homestay" -> "house.fill"
        "program" -> "graduationcap.fill"
        else -> "bed.double.fill"
    }

    /** The request's status as the business reads it, in one of the six states. */
    fun requestStatus(request: StaysRequest): Pair<String, ProviderState> = when (request.shownStatus) {
        "pending" -> if (request.familyAcceptedAt != null) Pair(L("Dates accepted"), ProviderState.done) else Pair(L("New"), ProviderState.waiting)
        "proposed" -> Pair(L("Waiting for the family"), ProviderState.upcoming)
        "confirmed" -> Pair(L("Confirmed"), ProviderState.done)
        "declined" -> Pair(L("Declined"), ProviderState.closed)
        "cancelled" -> Pair(L("Cancelled (stay)"), ProviderState.closed)
        "expired" -> Pair(L("Dates passed"), ProviderState.closed)
        else -> Pair(request.shownStatus, ProviderState.closed)
    }

    /** "jue 1 oct – dom 4 oct", or one day. */
    fun requestRange(kind: String, start: String, end: String): String =
        if (kind == "program" || start == end) StaysDay.short(start) else "${StaysDay.short(start)} – ${StaysDay.short(end)}"

    fun requestLength(kind: String, start: String, end: String): String? {
        if (kind == "program") return null
        if (kind == "boarding") return LP(maxOf(1, StaysDay.between(start, end)), "%d night", "%d nights")
        return LP(StaysDay.between(start, end) + 1, "%d day", "%d days")
    }

    fun homeVisitKind(kind: String?): String = when (kind) {
        "overnight" -> L("Night")
        "meet_greet" -> L("Meet & greet")
        else -> L("Visit")
    }

    fun homeVisitStatus(status: String?): Pair<String, ProviderState> = when (status) {
        "in_progress" -> Pair(L("In progress"), ProviderState.active)
        "done" -> Pair(L("Done"), ProviderState.done)
        "missed" -> Pair(L("Missed visit"), ProviderState.problem)
        "cancelled" -> Pair(L("Cancelled (stay)"), ProviderState.closed)
        else -> Pair(L("Planned"), ProviderState.upcoming)
    }

    fun homeStatus(stay: StaysStay, quoteSent: Boolean): String = when (stay.status) {
        "requested" -> if (quoteSent) L("Quote sent") else L("Not confirmed")
        "confirmed" -> L("Confirmed")
        "checked_in" -> L("Under way")
        "checked_out" -> L("Finished")
        "cancelled" -> L("Cancelled (stay)")
        "no_show" -> L("No-show")
        else -> L("Not confirmed")
    }

    fun keysState(keys: StaysHomeKeys?): String = when (keys?.state) {
        "with_sitter" -> LF("With %@", keys?.holder?.name ?: L("the sitter"))
        "lockbox" -> L("In the lockbox")
        "returned" -> L("Returned to the family")
        else -> L("Not received yet")
    }

    fun keyAction(event: StaysKeyEvent): String = when (event.action) {
        "received" -> LF("Received by %@", event.holderName ?: L("the sitter"))
        "transferred" -> LF("Passed on to %@", event.holderName ?: L("the sitter"))
        "lockbox" -> L("Left in the lockbox")
        else -> L("Returned to the family")
    }

    /** A refusal from the home stays routes, in the reader's words. */
    fun homeError(error: Throwable, fallback: String): String {
        val api = error as? APIError ?: return errorText(error, fallback = L(fallback))
        val known = mapOf(
            "address_required" to "Choose the client’s address. Add one if they have none yet.",
            "pets_required" to "Choose at least one pet.",
            "invalid_dates" to "Choose valid dates: the family comes back after they leave.",
            "too_long" to "A home stay lasts 60 days at most.",
            "overnight_needs_a_night" to "An overnight stay needs at least one night. Choose a later return day, or plan visits instead.",
            "times_required" to "Add at least one visit time.",
            "too_many_times" to "Up to six visits a day.",
            "invalid_duration" to "Visits last between 15 minutes and 4 hours.",
            "invalid_overnight" to "A night starts in the evening and ends the next morning.",
            "visits_overlap" to "Two visits of the same day overlap, or a daytime visit runs into the night.",
            "no_visits" to "Those dates and times leave no visit to make. Check the times against when the family leaves and comes back.",
            "invalid_member" to "Choose someone from your team.",
            "member_cannot_care" to "That person can’t look after stays. Give them “Care and report cards” in Staff first.",
            "member_not_at_location" to "That person doesn’t work at this location.",
            "visit_conflict" to "The sitter is busy then. Check the warning and book it anyway if you meant to.",
            "stay_closed" to "This home stay is closed.",
            "not_a_quote" to "Only a home stay not yet confirmed can be sent as a quote.",
            "price_required" to "Set the price before sending the quote.",
            "deposit_required" to "The deposit isn’t in yet. Take it first, or confirm without it.",
            "deposit_waive_forbidden" to "Only owners and admins can confirm without the deposit.",
            "stale" to "Someone else just changed this. It has been refreshed — try again.",
            "keys_out" to "The keys are still out. Record them returned, or say they will be returned later.",
            "not_started" to "The stay hasn’t started yet. Mark the first visit’s arrival, or cancel it.",
            "quote_closed" to "This quote was already answered.",
        )
        api.code?.let { code -> known[code]?.let { return L(it) } }
        if (api.status >= 500) return L(fallback)
        return if (api.message.isEmpty()) L(fallback) else api.message
    }

    /** A family request refusal, in the reader's words (lib/family-requests.ts). */
    fun requestError(error: Throwable): String {
        val api = error as? APIError ?: return errorText(error)
        val known = mapOf(
            "already_booked" to "There is already a stay booked for these dates.",
            "request_closed" to "This request already changed. Refresh to see it.",
            "dates_passed" to "These dates have passed. Propose new dates instead.",
            "same_dates" to "Those are the dates the family asked for. Confirm the request instead.",
            "visits_required" to "Add at least one visit.",
            "client_missing" to "This family’s pets are not on a client file yet.",
            "service_not_offered" to "That service is not offered. Choose another.",
            "reason_required" to "Tell the family why.",
            "past_date" to "Choose a date from today on.",
            "too_long" to "That is longer than can be asked for at once.",
            "same_day_boarding" to "A hotel stay needs at least one night.",
            "invalid_dates" to "Choose valid dates, the first one before the last.",
        )
        api.code?.let { code -> known[code]?.let { return L(it) } }
        return errorText(error)
    }
}

/** Swift `Double.rounded()`: half away from zero. */
private fun swiftRounded(value: Double): Double = if (value >= 0) Math.floor(value + 0.5) else Math.ceil(value - 0.5)

// MARK: - Small shared views

/** Swift `.onChange(of:)`: runs on a change of `value`, not on the first composition. */
@Composable
private fun <T> StaysOnChange(value: T, action: (T) -> Unit) {
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(value) {
        if (first[0]) first[0] = false else action(value)
    }
}

/** An uppercase label over a group (the web's small teal eyebrow: 12 bold, tracked 0.14em). */
@Composable
fun StaysEyebrow(text: String, tint: Color = Theme.blueText, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = brandText(12, FontWeight.Bold).copy(letterSpacing = (12 * 0.14).sp),
        color = tint,
        modifier = modifier,
    )
}

/** A red line for a pet a day late (stays-late-and-dates.md). */
@Composable
fun StaysLateLine(stay: StaysStay, modifier: Modifier = Modifier) {
    val late = stay.late ?: return
    Text(
        if (late == StaysLate.departure) LF("Was due out %@", StaysDay.short(stay.checkOutDay)) else LF("Was due in %@", StaysDay.short(stay.checkInDay)),
        style = Theme.text(12, FontWeight.Bold),
        color = Theme.destructive,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A row inside a card washed in a colour (web: a `rounded-box` row at the
 * accent's 16%, with a 3 pt left edge when it needs someone).
 */
@Composable
fun StaysTintedRow(
    tint: Color,
    percent: Double = 16.0,
    edge: Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.wash(tint, percent))
            .drawWithContent {
                drawContent()
                if (edge != null) drawRect(edge, size = Size(3.dp.toPx(), size.height))
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A sheet's field with no label of its own (web: an `Input`/`Textarea` whose placeholder says
 * what goes in, under a section title that names it): the input chrome and, when invalid, its
 * sentence under it.
 */
@Composable
fun StaysPlainField(
    prompt: String,
    text: MutableState<String>,
    key: String? = null,
    form: FormState? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    lines: IntRange = 3..8,
    modifier: Modifier = Modifier,
) {
    val error = key?.let { form?.error(it) }
    StaysOnChange(text.value) { if (key != null) form?.clear(key) }
    Column(modifier.scrollID(key ?: prompt), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = text.value,
            onValueChange = { text.value = it },
            textStyle = Theme.bodyFont.copy(color = Theme.inkStrong),
            singleLine = !multiline,
            minLines = if (multiline) lines.first else 1,
            maxLines = if (multiline) lines.last else 1,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            cursorBrush = SolidColor(Theme.inkStrong),
            modifier = Modifier.fillMaxWidth().fieldChrome(invalid = error != null, multiline = multiline),
            decorationBox = { inner ->
                Box(contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                    if (text.value.isEmpty()) Text(prompt, style = Theme.bodyFont, color = Theme.inkSoft)
                    inner()
                }
            },
        )
        if (error != null) Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
    }
}

/**
 * A sheet's select with no label of its own (web: a `NativeSelect` under a section title that
 * names it): the input chrome with a chevron, a menu of the options (`value` to `title`).
 */
@Composable
fun <Value> StaysPlainMenu(
    options: List<Pair<Value, String>>,
    selection: MutableState<Value>,
    accessibility: String = "",
    key: String? = null,
    form: FormState? = null,
    modifier: Modifier = Modifier,
) {
    val error = key?.let { form?.error(it) }
    var open by remember { mutableStateOf(false) }
    Column(modifier.scrollID(key ?: accessibility), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Theme.fieldRadius))
                    .clickable(role = Role.Button) { open = true }
                    .then(if (accessibility.isNotEmpty()) Modifier.semantics { contentDescription = accessibility } else Modifier)
                    .fieldChrome(invalid = error != null),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    options.firstOrNull { it.first == selection.value }?.second ?: L("Choose"),
                    style = Theme.text(14),
                    color = Theme.inkStrong,
                )
                Spacer(Modifier.weight(1f))
                Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.second) },
                        onClick = {
                            selection.value = option.first
                            if (key != null) form?.clear(key)
                            open = false
                        },
                    )
                }
            }
        }
        if (error != null) Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
    }
}

/**
 * An amount with no label of its own (web: a bare decimal input, "0.00"), typed as text and read
 * as cents; the currency after it when given.
 */
@Composable
fun StaysPlainMoney(
    cents: MutableState<Int?>,
    prompt: String = "0",
    currency: String? = null,
    key: String? = null,
    form: FormState? = null,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val error = key?.let { form?.error(it) }
    LaunchedEffect(Unit) { if (text.isEmpty()) cents.value?.let { text = Money.input(it) } }
    StaysOnChange(cents.value) { value -> if (Money.parse(text) != value) text = Money.input(value) }
    StaysOnChange(text) { value ->
        cents.value = Money.parse(value)
        if (key != null) form?.clear(key)
    }
    Column(modifier.scrollID(key ?: "money"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = Theme.bodyFont.copy(color = Theme.inkStrong),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(Theme.inkStrong),
                modifier = Modifier.weight(1f).fieldChrome(invalid = error != null),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(prompt, style = Theme.bodyFont, color = Theme.inkSoft)
                        inner()
                    }
                },
            )
            if (currency != null) Text(currency, style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
        }
        if (error != null) Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
    }
}

/**
 * A checkbox line in a sheet (web: a `label` with the `Checkbox`, an optional teal icon and the
 * words): bordered, or on the muted fill (`muted`), the whole line toggles it.
 */
@Composable
fun StaysTickRow(
    title: String,
    icon: String? = null,
    muted: Boolean = false,
    isOn: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (muted) Modifier.background(Theme.surfaceMuted, shape) else Modifier.border(1.dp, Theme.borderSoft, shape))
            .clickable(role = Role.Button) { isOn.value = !isOn.value }
            .semantics { selected = isOn.value }
            .padding(horizontal = 16.dp, vertical = if (muted) 12.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(on = isOn.value)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Glyph(icon, 16.dp, tint = Theme.blueText)
            Text(title, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** A fact in a bordered box with a small uppercase label (web home-stay-bits `SummaryTile`). */
@Composable
fun StaysSummaryTile(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label.uppercase(), style = Theme.text(10.9, FontWeight.Bold).copy(letterSpacing = (10.9 * 0.14).sp), color = Theme.inkSoft)
        content()
    }
}

/** A vaccine against the stay rules, in its state's colour. */
@Composable
fun StaysVaccineChip(key: String, status: String, modifier: Modifier = Modifier) {
    StatusChip(
        text = "${StaysWords.vaccineShort(key)}: ${StaysWords.vaccineStatus(status)}",
        state = ProviderState.vaccine(status),
        outlined = status != "current",
        modifier = modifier,
    )
}

/**
 * The clinic's allergies on file (web: a chip-cornered box, destructive 9%,
 * TriangleAlert in red, "Alergias:" bold).
 */
@Composable
fun StaysAllergies(allergies: List<String>, modifier: Modifier = Modifier) {
    if (allergies.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.destructive, 9.0), RoundedCornerShape(Theme.chipRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph("triangle-alert", 16.dp, tint = Theme.destructive, modifier = Modifier.padding(top = 2.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(L("Allergies") + ": ") }
                append(allergies.joinToString(", "))
            },
            style = Theme.text(14),
            color = Theme.inkStrong,
        )
    }
}

/**
 * A labelled block of text (web `NoteBlock`: a bordered box, the label in
 * 12 bold uppercase). With a tint it is the care board's washed note (the
 * owner's words in aqua with its 4 pt edge, the plan's notes in mango).
 */
@Composable
fun StaysNoteBlock(label: String, text: String, tint: Color? = null, modifier: Modifier = Modifier) {
    if (tint != null) {
        val shape = RoundedCornerShape(Theme.chipRadius)
        val showsEdge = tint == Theme.aqua
        Text(
            buildAnnotatedString {
                if (label.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$label: ") }
                append(text)
            },
            style = Theme.text(14),
            color = Theme.inkStrong,
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Theme.wash(tint, 14.0))
                .drawWithContent {
                    drawContent()
                    if (showsEdge) drawRect(tint, size = Size(4.dp.toPx(), size.height))
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    } else {
        val shape = RoundedCornerShape(Theme.boxRadius)
        Column(
            modifier
                .fillMaxWidth()
                .background(Theme.surface, shape)
                .border(1.dp, Theme.borderSoft, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label.uppercase(), style = brandText(12, FontWeight.Bold).copy(letterSpacing = (12 * 0.12).sp), color = Theme.inkSoft)
            Text(text, style = Theme.text(14), color = Theme.inkStrong)
        }
    }
}
