@file:OptIn(ExperimentalSerializationApi::class)

package com.pawmiaw.business.team

import com.pawmiaw.business.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

//
//  TeamModels.kt (iOS TeamModels.swift)
//
//  The shapes the Team module reads and writes, mirroring the web's hooks:
//  members and invitations (use-provider-workspaces.ts), what each role can
//  do (use-provider-staff.ts), opening hours, closed dates and each person's
//  hours (use-provider-hours.ts, lib/provider-availability.ts), online
//  booking (use-provider-online-booking.ts), the business's setup and store
//  settings, notification preferences, and the account's sessions and
//  password. Every field that can be missing is nullable.
//

// MARK: - Team

@Serializable
data class TeamMember(
    val id: String,
    val userId: String? = null,
    val name: String? = null,
    val userName: String? = null,
    val email: String? = null,
    val image: String? = null,
    val displayName: String? = null,
    val professionalTitle: String? = null,
    val specialty: String? = null,
    val credentialLabel: String? = null,
    val credentialValue: String? = null,
    val profileNotes: String? = null,
    val maxConcurrentVisits: Int? = null,
    val locationIds: List<String>? = null,
    val role: String? = null,
    val permissionAllow: List<String>? = null,
    val permissionDeny: List<String>? = null,
    val effectivePermissions: List<String>? = null,
    val createdAt: String? = null,
    val isCurrentMember: Boolean? = null,
    val status: String? = null,
    val suspendedAt: String? = null,
    val lastActiveAt: String? = null,
    val overSeatLimit: Boolean? = null,
) {
    val roleKey: String get() = role ?: ""
    val allow: List<String> get() = permissionAllow ?: emptyList()
    val deny: List<String> get() = permissionDeny ?: emptyList()
    val effective: List<String> get() = effectivePermissions ?: emptyList()
    val isMe: Boolean get() = isCurrentMember ?: false
    val atOnce: Int get() = maxOf(1, maxConcurrentVisits ?: 1)

    /** The name shown: the one set for the team, the account's, else the email. */
    val shownName: String
        get() = displayName.nonEmpty ?: name.nonEmpty ?: userName.nonEmpty ?: email.nonEmpty ?: L("Unnamed")

    /** "Veterinarian · Dermatology". */
    val titleLine: String
        get() = listOfNotNull(professionalTitle.nonEmpty, specialty.nonEmpty).joinToString(" · ")

    /** Starts and finishes visits (the Schedule gives them a column). */
    val takesVisits: Boolean get() = effective.contains(Permission.appointmentsComplete.rawValue)
    val isSuspended: Boolean get() = status == "suspended"
    val hasCustomAccess: Boolean get() = roleKey != "owner" && (allow.isNotEmpty() || deny.isNotEmpty())
}

@Serializable
data class TeamInvitation(
    val id: String,
    val inviteeName: String? = null,
    val email: String,
    val role: String? = null,
    val status: String? = null,
    val inviterName: String? = null,
    val expiresAt: String? = null,
    val locationIds: List<String>? = null,
)

/** An invitation still worth showing: waiting, or run out. */
data class TeamOpenInvitation(
    val invitation: TeamInvitation,
    val expired: Boolean,
) {
    val id: String get() = invitation.id
    val shownName: String get() = invitation.inviteeName.nonEmpty ?: invitation.email
}

@Serializable
data class TeamRolePermissions(
    val role: String,
    val permissions: List<String>,
)

@Serializable
data class TeamRolesResponse(
    val roles: List<TeamRolePermissions>,
)

// MARK: - Hours

@Serializable
data class TeamTimeRange(
    var start: String,
    var end: String,
)

/** One weekday: open or closed, with one or more ranges. */
@KeepGeneratedSerializer
@Serializable(with = TeamDaySerializer::class)
data class TeamDay(
    var day: String,
    var enabled: Boolean,
    var startTime: String?,
    var endTime: String?,
    var ranges: List<TeamTimeRange>?,
)

/** The Swift `init(from:)`: a missing or unreadable day is "monday", closed; a field of the wrong type reads as absent. */
object TeamDaySerializer : KSerializer<TeamDay> {
    override val descriptor: SerialDescriptor get() = TeamDay.generatedSerializer().descriptor
    override fun serialize(encoder: Encoder, value: TeamDay) = TeamDay.generatedSerializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): TeamDay {
        val c = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected a JSON object")
        fun text(key: String): String? = (c[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val enabled = (c["enabled"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toBooleanStrictOrNull()
        val ranges = c["ranges"]?.takeIf { it !is JsonNull }?.let {
            runCatching { API.json.decodeFromJsonElement(ListSerializer(TeamTimeRange.serializer()), it) }.getOrNull()
        }
        return TeamDay(
            day = text("day") ?: "monday",
            enabled = enabled ?: false,
            startTime = text("startTime"),
            endTime = text("endTime"),
            ranges = ranges,
        )
    }
}

/** A location's hours and booking rules (`/provider-workspaces/:id/availability`). */
@Serializable
data class TeamAvailability(
    val timezone: String? = null,
    val defaultAppointmentDurationMinutes: Int? = null,
    val allowAfterHoursBooking: Boolean? = null,
    val allowOnlineBooking: Boolean? = null,
    val requestAppointmentConfirmationByDefault: Boolean? = null,
    val defaultBufferMinutes: Int? = null,
    val minBookingNoticeMinutes: Int? = null,
    val bookingHorizonDays: Int? = null,
    val onlineConfirmMode: String? = null,
    val weeklySchedule: List<TeamDay>? = null,
)

@Serializable
data class TeamClosure(
    val id: String,
    val locationId: String? = null,
    val startDay: String,
    val endDay: String,
    val label: String? = null,
    /** null = closed all day; else the special hours of those dates. */
    val ranges: List<TeamTimeRange>? = null,
    val repeatsYearly: Boolean? = null,
    val nextStartDay: String? = null,
    val nextEndDay: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class TeamClosuresResponse(
    val locationId: String? = null,
    val closures: List<TeamClosure>,
)

/** What is already booked on some days (nothing is cancelled). */
@Serializable
data class TeamBookedThen(
    val visits: Int? = null,
    val firstDay: String? = null,
    val stays: Int? = null,
)

@Serializable
data class TeamClosureSaved(
    val closure: TeamClosure,
    val visits: Int? = null,
    val firstDay: String? = null,
    val stays: Int? = null,
)

@Serializable
data class TeamHoursConflicts(
    val visits: Int? = null,
    val firstDay: String? = null,
    val stays: Int? = null,
    val covered: List<Covered>? = null,
) {
    @Serializable
    data class Covered(
        val id: String,
        val startDay: String,
        val endDay: String,
        val label: String? = null,
        val closed: Boolean? = null,
    )
}

@Serializable
data class TeamTimeOff(
    val id: String,
    val memberId: String? = null,
    val startDay: String,
    val endDay: String,
    val reason: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class TeamTimeOffSaved(
    val timeOff: TeamTimeOff,
    val visits: Int? = null,
)

@Serializable
data class TeamMemberLocationHours(
    val locationId: String,
    val name: String? = null,
    val isPrimary: Boolean? = null,
    val timezone: String? = null,
    val usesLocationHours: Boolean? = null,
    val weeklySchedule: List<TeamDay>? = null,
    val locationWeeklySchedule: List<TeamDay>? = null,
)

@Serializable
data class TeamMemberHours(
    val memberId: String,
    val locations: List<TeamMemberLocationHours>? = null,
    val timeOff: List<TeamTimeOff>? = null,
)

// MARK: - Online booking

@Serializable
data class TeamOnlineLocation(
    val id: String,
    val name: String? = null,
    val isPrimary: Boolean? = null,
    val status: String? = null,
    val allowOnlineBooking: Boolean? = null,
    val onlineConfirmMode: String? = null,
    val pendingRequests: Int? = null,
)

@Serializable
data class TeamOnlineService(
    val serviceId: String,
    val label: String,
    val category: String? = null,
    val durationMinutes: Int? = null,
    val onlineBookable: Boolean? = null,
)

@Serializable
data class TeamOnlineMember(
    val memberId: String,
    val name: String? = null,
    val role: String? = null,
    val suspended: Boolean? = null,
    val doesVisits: Boolean? = null,
    /** Their own switch; null = what their role says (`roleDefault`). */
    val takesOnlineBookings: Boolean? = null,
    val roleDefault: Boolean? = null,
    /** Families are offered this person now. */
    val offered: Boolean? = null,
    /** "Servicios que hace"; [] = every bookable service their role can do. */
    val serviceIds: List<String>? = null,
    val signsRecords: Boolean? = null,
)

@Serializable
data class TeamOnlineSettings(
    val locations: List<TeamOnlineLocation>? = null,
    /** The store policy's value; null = not set (families read 24 h). */
    val lateCancelWindowHours: Int? = null,
    val familyCancelWindowHours: Int? = null,
    val services: List<TeamOnlineService>? = null,
    val team: List<TeamOnlineMember>? = null,
    val ready: Boolean? = null,
)

// MARK: - Visits (a person's "Coming up")

@Serializable
data class TeamVisit(
    val id: String,
    val petName: String? = null,
    val serviceLabel: String? = null,
    val clientName: String? = null,
    val startsAt: String? = null,
    val status: String? = null,
)

// MARK: - Business setup and settings

@Serializable
data class TeamSetupService(
    val serviceId: String,
    val category: String? = null,
    val defaultDurationMinutes: Int? = null,
    val offered: Boolean? = null,
    val suggested: Boolean? = null,
    val durationMinutes: Int? = null,
)

@Serializable
data class TeamSetupState(
    val canEdit: Boolean? = null,
    val canEditRegion: Boolean? = null,
    val business: Business? = null,
    val region: Region? = null,
    val legal: Legal? = null,
    val senasicaRegistration: String? = null,
) {
    @Serializable
    data class Business(
        val name: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val logoUrl: String? = null,
        val enabledCategories: List<String>? = null,
        val services: List<TeamSetupService>? = null,
    )

    @Serializable
    data class Region(
        val country: String? = null,
        val currency: String? = null,
        val currencyLocked: Boolean? = null,
        val defaultTaxRateBps: Int? = null,
        val pricesIncludeTax: Boolean? = null,
        val clientLanguage: String? = null,
    )

    @Serializable
    data class Legal(
        val legalName: String? = null,
        val taxId: String? = null,
        val taxRegime: String? = null,
        val receiptLogoUrl: String? = null,
    )
}

/** `/appointment-services`: the kinds of care and their services. */
@Serializable
data class TeamServiceCatalog(
    val categories: List<Category>? = null,
    val services: List<Service>? = null,
) {
    @Serializable
    data class Category(
        val id: String,
        val label: String? = null,
        val description: String? = null,
    )

    @Serializable
    data class Service(
        val id: String,
        val category: String,
        val label: String,
        val description: String? = null,
        val defaultDurationMinutes: JSONValue? = null,
    ) {
        val defaultMinutes: Int get() = defaultDurationMinutes.int ?: 30
    }
}

/**
 * The store's settings (`/provider-store/settings`), kept whole so a save sends back what it
 * did not change. The one model Home's setup sheets and Team's Settings read and save.
 */
data class TeamStoreSettings(
    var raw: Map<String, JSONValue>,
) {
    /**
     * Sends the settings whole with `changes` over them (null clears a value): the receipt line,
     * fees and everything else the Store saves stay as they are.
     */
    suspend fun save(changes: Map<String, JSONValue?>, announce: Boolean = true) {
        val next = raw.toMutableMap()
        next.remove("currencyLocked")
        next.remove("currencyLockedReason")
        for ((key, value) in changes) next[key] = value ?: JsonNull
        API.putJson<EmptyResponse, JsonObject>("/provider-store/settings", JsonObject(next))
        if (announce) announceDataChanged()
    }

    val currency: String get() = raw["currency"].string ?: Workspace.shared.currency
    val currencyLocked: Boolean get() = raw["currencyLocked"].bool ?: false
    val defaultTaxRateBps: Int get() = raw["defaultTaxRateBps"].int ?: 0
    val pricesIncludeTax: Boolean get() = raw["pricesIncludeTax"].bool ?: false
    val cashDifferenceAlertAmount: Int?
        get() {
            val value = raw["cashDifferenceAlertAmount"]
            if (value.isNull) return null
            return value.int
        }

    companion object {
        suspend fun load(): TeamStoreSettings {
            val raw: Map<String, JsonElement> = API.get("/provider-store/settings")
            return TeamStoreSettings(raw = raw)
        }

        /** Cents as the settings carry them (null for none). */
        fun cents(value: Int?): JSONValue = value?.let { JsonPrimitive(it) } ?: JsonNull
    }
}

// MARK: - Notifications

@Serializable
data class TeamNotificationPreference(
    val category: String,
    val inAppEnabled: Boolean? = null,
    val emailEnabled: Boolean? = null,
    val emailLocked: Boolean? = null,
    val pushEnabled: Boolean? = null,
    val timezone: String? = null,
    val reminderLeadTimes: List<Int>? = null,
)

@Serializable
data class TeamNotificationChannel(
    val key: String,
    val label: String? = null,
    val availableNow: Boolean? = null,
)

@Serializable
data class TeamNotificationPreferences(
    val items: List<TeamNotificationPreference>? = null,
    val channels: List<TeamNotificationChannel>? = null,
)

@Serializable
data class TeamMarketingEmail(
    val enabled: Boolean? = null,
)

// MARK: - Account

@Serializable
data class TeamUserProfile(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val image: String? = null,
)

@Serializable
data class TeamUserSession(
    val id: String,
    val deviceLabel: String? = null,
    val browserLabel: String? = null,
    val platformLabel: String? = null,
    val ipAddress: String? = null,
    val locationLabel: String? = null,
    val createdAt: String? = null,
    val lastActiveAt: String? = null,
    val expiresAt: String? = null,
    val isCurrent: Boolean? = null,
)

@Serializable
data class TeamSessionsResponse(
    val sessions: List<TeamUserSession>,
)

@Serializable
data class TeamPasswordStatus(
    val hasPassword: Boolean? = null,
    val socialProviders: List<String>? = null,
)

@Serializable
data class TeamPasswordResult(
    val created: Boolean? = null,
    val otherSessionsSignedOut: Int? = null,
)

@Serializable
data class TeamPasswordCodeSent(
    val email: String? = null,
)
