package com.pawmiaw.business.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import kotlinx.serialization.Serializable
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

//
//  PlacesModels.kt (iOS PlacesModels.swift)
//
//  The shapes and rules of the Places module: rooms (web hooks/use-provider-rooms.ts,
//  lib/rooms.ts), locations (hooks/use-provider-locations.ts, lib/provider-locations.ts)
//  and the visits waiting to move from a paused location ("Por reubicar").
//

// MARK: - Rooms

@Serializable
data class PlacesRoomOut(
    val fromDay: String,
    val untilDay: String? = null,
    val reason: String? = null,
)

@Serializable
data class PlacesRoom(
    val id: String,
    val name: String,
    val kind: String,
    val capacity: Int? = null,
    val serviceIds: List<String>? = null,
    val sortOrder: Int? = null,
    val status: String? = null,
    val locationId: String? = null,
    val notes: String? = null,
    val outOfService: PlacesRoomOut? = null,
) {
    val kindEntry: PlacesRoomKind get() = PlacesRoomKind.of(kind)
    val isHospital: Boolean get() = kindEntry.isHospital
    val isArchived: Boolean get() = status == "archived"
    val places: Int get() = maxOf(1, capacity ?: 1)
    val services: List<String> get() = serviceIds ?: emptyList()
}

/** What a room holds (`GET /provider-rooms/usage`). */
@Serializable
data class PlacesRoomUsage(
    val roomId: String,
    val upcomingVisits: Int? = null,
    val visitsToday: Int? = null,
    val patients: Int? = null,
    val hasHistory: Boolean? = null,
)

/** Visits still booked in a room on days it is out of service. */
@Serializable
data class PlacesRoomOutVisits(
    val count: Int? = null,
    val firstDay: String? = null,
)

/** A saved room, with what is still booked in it while out of service. */
@Serializable
data class PlacesRoomSaved(
    val id: String,
    val name: String,
    val visitsWhileOut: PlacesRoomOutVisits? = null,
)

@Serializable
data class PlacesService(
    val id: String,
    val label: String,
)

@Serializable
data class PlacesServiceCatalog(
    val services: List<PlacesService>? = null,
)

/** The kinds of room, in one place: icon, name (one and several), what it is for, its hue. */
enum class PlacesRoomKind(val rawValue: String) {
    consult("consult"), grooming("grooming"), bath("bath"), surgery("surgery"), treatment("treatment"), ward("ward"), cage("cage"), other("other");

    val id: String get() = rawValue

    val isHospital: Boolean get() = this == ward || this == cage

    val label: String
        get() = when (this) {
            consult -> L("Consult room")
            grooming -> L("Grooming table")
            bath -> L("Bath")
            surgery -> L("Surgery")
            treatment -> L("Treatment")
            ward -> L("Ward")
            cage -> L("Cage")
            other -> L("Other@room")
        }

    val plural: String
        get() = when (this) {
            consult -> L("Consult rooms")
            grooming -> L("Grooming tables")
            bath -> L("Baths")
            surgery -> L("Surgeries")
            treatment -> L("Treatment rooms")
            ward -> L("Wards")
            cage -> L("Cages")
            other -> L("Other rooms")
        }

    val hint: String
        get() = when (this) {
            consult -> L("Check-ups, vaccines, any visit with the vet.")
            grooming -> L("Cuts and styling. It can hold two or three at once.")
            bath -> L("The tub or the wash station.")
            surgery -> L("The operating room.")
            treatment -> L("Procedures, X-rays, the lab.")
            ward -> L("Hospitalized patients: as many as it has places.")
            cage -> L("One hospitalized patient.")
            other -> L("A training area, the garden, anything else.")
        }

    /** The kind's Lucide icon (web room-kinds.tsx). */
    val systemImage: String
        get() = when (this) {
            consult -> "stethoscope"
            grooming -> "scissors"
            bath -> "bath"
            surgery -> "heart-pulse"
            treatment -> "syringe"
            ward -> "bed-double"
            cage -> "grid-2x2"
            other -> "door-open"
        }

    val hue: Hue
        get() = when (this) {
            consult -> Hue.sky
            surgery -> Hue.peach
            treatment -> Hue.butter
            ward, cage -> Hue.periwinkle
            grooming -> Hue.lavender
            bath -> Hue.teal
            other -> Hue.grey
        }

    companion object {
        /** The order the groups are listed in. */
        val order: List<PlacesRoomKind> = listOf(consult, surgery, treatment, grooming, bath, other, ward, cage)

        fun of(raw: String?): PlacesRoomKind = entries.firstOrNull { it.rawValue == raw } ?: other
    }
}

enum class PlacesRoomFilter(val rawValue: String) {
    all("all"), visits("visits"), hospital("hospital"), `out`("out"), archived("archived");

    val title: String
        get() = when (this) {
            all -> L("All@rooms")
            visits -> L("For visits")
            hospital -> L("Hospital")
            `out` -> L("Out of service")
            archived -> L("Archived@rooms")
        }
}

/** Where a room's out-of-service stretch stands today: on, still to come, or nothing. */
data class PlacesRoomOutState(
    val state: State,
    val fromDay: String,
    val untilDay: String?,
    val reason: String?,
) {
    enum class State { `out`, scheduled }
}

/** Swift `trimmingCharacters(in: .whitespaces)`: spaces and tabs, not line breaks. */
private fun String.trimSpaces(): String = trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

private fun zoneId(identifier: String?): ZoneId? = identifier?.let { runCatching { ZoneId.of(it) }.getOrNull() }

/** The Rooms page's rules (web lib/rooms.ts), free of views. */
object PlacesRoomRules {
    const val nameMax = 60
    const val notesMax = 500
    const val capacityMax = 20
    const val copiesMax = 12

    fun outState(room: PlacesRoom, today: String): PlacesRoomOutState? {
        val out = room.outOfService ?: return null
        if (out.fromDay.isEmpty()) return null
        val until = out.untilDay
        if (until != null && today > until) return null
        return PlacesRoomOutState(
            state = if (today < out.fromDay) PlacesRoomOutState.State.scheduled else PlacesRoomOutState.State.out,
            fromDay = out.fromDay, untilDay = out.untilDay, reason = out.reason.nonEmpty,
        )
    }

    private val whitespace = Regex("\\p{javaWhitespace}+")

    private fun fold(name: String): String = name.trim().lowercase().split(whitespace).filter { it.isNotEmpty() }.joinToString(" ")

    fun nameTaken(name: String, rooms: List<PlacesRoom>, except: String? = null): Boolean {
        val wanted = fold(name)
        if (wanted.isEmpty()) return false
        return rooms.any { it.id != except && !it.isArchived && fold(it.name) == wanted }
    }

    /** A name split into its stem and its number (Swift's `(stem:number:)` tuple). */
    data class Numbered(val stem: String, val number: Int?)

    private val numberCategories = setOf(CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER)

    /** "Suite 2" → ("Suite", 2); "Suite" → ("Suite", null). */
    fun splitNumbered(name: String): Numbered {
        val trimmed = name.trim()
        var digits = ""
        for (character in trimmed.reversed()) {
            if (character.category in numberCategories && digits.length < 3) digits = character + digits else break
        }
        if (digits.isEmpty()) return Numbered(trimmed, null)
        val stem = trimmed.dropLast(digits.length).trimSpaces()
        // Only ASCII digits read as a number, as Swift's `Int(_:)`.
        val number = if (digits.all { it in '0'..'9' }) digits.toIntOrNull() else null
        if (stem.isEmpty() || number == null) return Numbered(trimmed, null)
        return Numbered(stem, number)
    }

    /** The names of `count` numbered rooms from a name, skipping numbers already taken. */
    fun numberedNames(name: String, count: Int, existing: List<PlacesRoom>): List<String> {
        val total = maxOf(1, minOf(copiesMax, count))
        val typed = name.trim()
        if (typed.isEmpty()) return emptyList()
        if (total == 1) return listOf(typed)
        val (stem, number) = splitNumbered(typed)
        val names = mutableListOf<String>()
        var next = number ?: 1
        var guardCount = 0
        while (names.size < total && guardCount < 2000) {
            val candidate = "$stem $next".take(nameMax)
            if (!nameTaken(candidate, existing) && names.none { fold(it) == fold(candidate) }) names.add(candidate)
            next += 1
            guardCount += 1
        }
        return names
    }

    /** A copy's starting name: the next free number after its own. */
    fun copyName(name: String, existing: List<PlacesRoom>): String {
        val (stem, number) = splitNumbered(name)
        for (next in ((number ?: 1) + 1) until 1000) {
            val candidate = "$stem $next".take(nameMax)
            if (!nameTaken(candidate, existing)) return candidate
        }
        return name
    }

    fun counts(rooms: List<PlacesRoom>, today: String): Map<PlacesRoomFilter, Int> {
        val active = rooms.filter { !it.isArchived }
        return mapOf(
            PlacesRoomFilter.all to active.size,
            PlacesRoomFilter.visits to active.count { !it.isHospital },
            PlacesRoomFilter.hospital to active.count { it.isHospital },
            PlacesRoomFilter.out to active.count { outState(it, today) != null },
            PlacesRoomFilter.archived to rooms.count { it.isArchived },
        )
    }

    fun filter(rooms: List<PlacesRoom>, filter: PlacesRoomFilter, today: String): List<PlacesRoom> {
        if (filter == PlacesRoomFilter.archived) return rooms.filter { it.isArchived }
        val active = rooms.filter { !it.isArchived }
        return when (filter) {
            PlacesRoomFilter.visits -> active.filter { !it.isHospital }
            PlacesRoomFilter.hospital -> active.filter { it.isHospital }
            PlacesRoomFilter.out -> active.filter { outState(it, today) != null }
            else -> active
        }
    }

    fun sorted(rooms: List<PlacesRoom>): List<PlacesRoom> {
        val collator = Collator.getInstance(appLocale)
        return rooms.sortedWith { a, b ->
            if ((a.sortOrder ?: 0) != (b.sortOrder ?: 0)) (a.sortOrder ?: 0).compareTo(b.sortOrder ?: 0)
            else collator.compare(a.name, b.name)
        }
    }

    /** One kind's rooms (Swift's `(kind:rooms:)` tuple). */
    data class Group(val kind: PlacesRoomKind, val rooms: List<PlacesRoom>)

    fun groups(rooms: List<PlacesRoom>): List<Group> {
        val ordered = sorted(rooms)
        return PlacesRoomKind.order.mapNotNull { kind ->
            val inKind = ordered.filter { it.kindEntry == kind }
            if (inKind.isEmpty()) null else Group(kind, inKind)
        }
    }

    /** The whole list's order after one room moves up or down among its kind; null when it can't. */
    fun move(rooms: List<PlacesRoom>, roomId: String, direction: Int): List<String>? {
        val ordered = groups(rooms).flatMap { it.rooms }
        val moving = ordered.firstOrNull { it.id == roomId } ?: return null
        val group = ordered.filter { it.kindEntry == moving.kindEntry && it.isArchived == moving.isArchived }
        val at = group.indexOfFirst { it.id == roomId }.takeIf { it >= 0 } ?: return null
        val target = at + direction
        if (target !in group.indices) return null
        val ids = ordered.map { it.id }.toMutableList()
        val a = ids.indexOf(roomId).takeIf { it >= 0 } ?: return null
        val b = ids.indexOf(group[target].id).takeIf { it >= 0 } ?: return null
        java.util.Collections.swap(ids, a, b)
        return ids
    }

    data class Preset(
        val id: String,
        val kind: PlacesRoomKind,
        /** English name stem, translated by `L`. */
        val name: String,
        val count: Int,
        val capacity: Int,
    )

    /** Quick starts for a business with no rooms, from what it offers. */
    fun presets(categories: List<String>): List<Preset> {
        val presets = mutableListOf<Preset>()
        if (categories.contains("vet") || categories.contains("emergency")) {
            presets.add(Preset(id = "consult-2", kind = PlacesRoomKind.consult, name = "Consult room", count = 2, capacity = 1))
            presets.add(Preset(id = "surgery-1", kind = PlacesRoomKind.surgery, name = "Surgery", count = 1, capacity = 1))
        }
        if (categories.contains("groomer")) {
            presets.add(Preset(id = "grooming-1", kind = PlacesRoomKind.grooming, name = "Grooming table", count = 1, capacity = 1))
            presets.add(Preset(id = "bath-1", kind = PlacesRoomKind.bath, name = "Bath", count = 1, capacity = 1))
        }
        if (presets.isEmpty()) presets.add(Preset(id = "consult-1", kind = PlacesRoomKind.consult, name = "Consult room", count = 1, capacity = 1))
        return presets
    }

    /** The server's refusals about rooms in words. */
    fun errorText(error: Throwable, fallback: String): String {
        if (error is APIError) {
            when (error.code) {
                "room_name_taken" -> return L("There is already a room with that name here.")
                "room_has_patients" -> return L("This room has hospitalized patients in it. Move them to another ward or cage first.")
                "room_kind_in_use" -> return L("This room still has visits to come. Move them to another room before making it a ward or cage.")
                "room_has_history" -> return L("This room has visits or patients in its history, so it can’t be deleted. Archive it instead.")
                "invalid_dates" -> return L("Choose days from today on, the last one after the first, a year at most.")
                "location_paused" -> return L("That location is paused: the plan no longer includes it.")
                else -> {}
            }
        }
        return serverText(error, fallback)
    }

    fun serverText(error: Throwable, fallback: String): String {
        val api = error as? APIError ?: return fallback
        if (api.message.isEmpty()) return fallback
        return L(api.message)
    }
}

// MARK: - Days

object PlacesDay {
    /** "lun 5 oct" for a "YYYY-MM-DD" day, with the year when it isn't this one. */
    fun label(day: String): String {
        val date = DateText.parse(day) ?: return day
        val zone = Workspace.shared.timeZone
        val sameYear = date.atZone(zone).year == ZonedDateTime.now(zone).year
        return DateText.format(date, if (sameYear) "EEEdMMM" else "EEEdMMMyyyy", zone)
    }

    /** A "YYYY-MM-DD" day as a date at noon on the business's clock. */
    fun date(day: String): Instant = DateText.parse(day) ?: Instant.now()

    fun key(date: Instant): String = DateText.dayKey(date)
}

// MARK: - Locations

@Serializable
data class PlacesAvailabilityDay(
    val day: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class PlacesAvailability(
    val timezone: String? = null,
    val weeklySchedule: List<PlacesAvailabilityDay>? = null,
)

@Serializable
data class PlacesLocation(
    val id: String,
    val clinicId: String? = null,
    val name: String,
    val isPrimary: Boolean? = null,
    val status: String? = null,
    val sortOrder: Int? = null,
    val pausedAt: String? = null,
    val timezone: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val receiptLogoUrl: String? = null,
    val receiptName: String? = null,
    val availability: PlacesAvailability? = null,
) {
    val primary: Boolean get() = isPrimary ?: false
    val isActive: Boolean get() = (status ?: "active") == "active"
    val isPaused: Boolean get() = status == "paused"
    val isArchived: Boolean get() = status == "archived"
    val zone: String get() = timezone.nonEmpty ?: availability?.timezone.nonEmpty ?: "UTC"
    val openDays: Int? get() = availability?.weeklySchedule?.count { it.enabled == true }
}

@Serializable
data class PlacesLocationPlan(
    val allowsMultiple: Boolean? = null,
    val requiredPlan: String? = null,
    val plan: String? = null,
    val limit: Int? = null,
    val used: Int? = null,
    val paused: Int? = null,
    val canAdd: Boolean? = null,
    val reason: String? = null,
    val addonAvailable: Boolean? = null,
    val talkToSales: Boolean? = null,
)

/** What a location has and still holds (`withStats=true`). */
@Serializable
data class PlacesLocationStats(
    val locationId: String,
    val staff: Int? = null,
    val staffOnlyHere: Int? = null,
    val rooms: Int? = null,
    val registers: Int? = null,
    val stayUnits: Int? = null,
    val futureAppointments: Int? = null,
    val openRegisterSessions: Int? = null,
    val activeStays: Int? = null,
    val hospitalPatients: Int? = null,
)

@Serializable
data class PlacesLocationsAnswer(
    val stats: List<PlacesLocationStats>? = null,
    val locations: List<PlacesLocation>,
    val memberLocationIds: List<String>? = null,
    val plan: PlacesLocationPlan? = null,
)

enum class PlacesAddPath { add, buyAddon, upgrade, talkToSales }

/** What still holds a location open, in the order it is said. */
enum class PlacesLocationBlocker(val rawValue: String) {
    futureAppointments("futureAppointments"), openRegisterSessions("openRegisterSessions"), activeStays("activeStays"), hospitalPatients("hospitalPatients");

    val id: String get() = rawValue

    fun count(stats: PlacesLocationStats?): Int {
        if (stats == null) return 0
        return when (this) {
            futureAppointments -> stats.futureAppointments ?: 0
            openRegisterSessions -> stats.openRegisterSessions ?: 0
            activeStays -> stats.activeStays ?: 0
            hospitalPatients -> stats.hospitalPatients ?: 0
        }
    }

    /** Its Lucide icon (web location-archive-sheet.tsx `BLOCKERS`). */
    val systemImage: String
        get() = when (this) {
            futureAppointments -> "calendar-clock"
            openRegisterSessions -> "store"
            activeStays -> "bed-double"
            hospitalPatients -> "heart-pulse"
        }
}

enum class PlacesArchiveRefusal { primary, lastActive, inUse }

object PlacesLocationRules {
    const val supportEmail = "contact@pawmiaw.com"

    fun sorted(locations: List<PlacesLocation>): List<PlacesLocation> {
        val collator = Collator.getInstance(appLocale)
        return locations.sortedWith { a, b ->
            when {
                a.primary != b.primary -> if (a.primary) -1 else 1
                (a.sortOrder ?: 0) != (b.sortOrder ?: 0) -> (a.sortOrder ?: 0).compareTo(b.sortOrder ?: 0)
                else -> collator.compare(a.name, b.name)
            }
        }
    }

    fun active(locations: List<PlacesLocation>): List<PlacesLocation> = sorted(locations.filter { it.isActive })
    fun paused(locations: List<PlacesLocation>): List<PlacesLocation> = sorted(locations.filter { it.isPaused })
    fun archived(locations: List<PlacesLocation>): List<PlacesLocation> = sorted(locations.filter { it.isArchived })

    fun canAdd(locations: List<PlacesLocation>, plan: PlacesLocationPlan?): Boolean {
        if (active(locations).isEmpty()) return true
        if (plan == null) return false
        plan.canAdd?.let { return it }
        return plan.allowsMultiple ?: false
    }

    fun addPath(locations: List<PlacesLocation>, plan: PlacesLocationPlan?): PlacesAddPath {
        if (canAdd(locations, plan)) return PlacesAddPath.add
        if (plan?.addonAvailable == true) return PlacesAddPath.buyAddon
        if (plan?.talkToSales == true) return PlacesAddPath.talkToSales
        return PlacesAddPath.upgrade
    }

    fun nameTaken(name: String, locations: List<PlacesLocation>, except: String?): Boolean {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return false
        return locations.any { it.id != except && it.name.trim().lowercase() == wanted }
    }

    data class Hold(val blocker: PlacesLocationBlocker, val count: Int)

    fun blockers(stats: PlacesLocationStats?): List<Hold> =
        PlacesLocationBlocker.entries.map { Hold(it, it.count(stats)) }.filter { it.count > 0 }

    fun archiveRefusal(location: PlacesLocation, locations: List<PlacesLocation>, stats: PlacesLocationStats?): PlacesArchiveRefusal? {
        if (location.isActive && active(locations).size <= 1) return PlacesArchiveRefusal.lastActive
        if (location.primary) return PlacesArchiveRefusal.primary
        return if (blockers(stats).isEmpty()) null else PlacesArchiveRefusal.inUse
    }

    /** "Mexico City" from "America/Mexico_City". */
    fun city(zone: String): String =
        (zone.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: zone).replace("_", " ")

    /** The time it is now at a location, on its own clock. */
    fun clock(zone: String, date: Instant = Instant.now()): String =
        DateText.time(date, zoneId(zone) ?: ZoneId.systemDefault())

    /** Minutes the clock moves (new − old) now. */
    fun shiftMinutes(from: String, to: String, date: Instant = Instant.now()): Int {
        val a = zoneId(from) ?: return 0
        val b = zoneId(to) ?: return 0
        return (b.rules.getOffset(date).totalSeconds - a.rules.getOffset(date).totalSeconds) / 60
    }

    /** "1 hour later", "2 hours earlier", "1 h 30 min later". */
    fun shiftLabel(minutes: Int): String {
        val later = minutes > 0
        val total = abs(minutes)
        val hours = total / 60
        val rest = total % 60
        if (hours > 0 && rest > 0) return LF(if (later) "%d h %d min later" else "%d h %d min earlier", hours, rest)
        if (hours > 0) {
            if (hours == 1) return L(if (later) "1 hour later" else "1 hour earlier")
            return LF(if (later) "%d hours later" else "%d hours earlier", hours)
        }
        return LF(if (later) "%d minutes later" else "%d minutes earlier", rest)
    }

    /** The server's refusals (plan, name, time zone, archiving) in words. */
    fun errorText(error: Throwable, name: String? = null, limit: Int? = null, talkToSales: Boolean = false, requiredPlan: String? = null): String {
        if (error is APIError) {
            when (error.code) {
                "location_plan_required" ->
                    return if (requiredPlan == "pro") L("Another location needs the Business plan.") else L("Add an extra location to your plan in Subscription.")
                "location_limit_reached" ->
                    return if (talkToSales) LF("Your plan holds %d active locations. For more, talk to us about Enterprise.", limit ?: 1)
                    else LF("Your plan holds %d active locations. Add an extra location in Subscription.", limit ?: 1)
                "location_paused" ->
                    return name?.let { LF("%@ is paused: the plan no longer includes it.", it) } ?: L("That location is paused: the plan no longer includes it.")
                "location_archived" -> return L("That location is archived.")
                "keep_primary_required" -> return L("The main location always stays. Make another one the main location first.")
                "location_name_taken" -> return L("Another location already has that name.")
                "invalid_timezone" -> return L("Choose a valid time zone.")
                "location_is_primary" -> return L("The main location can’t be archived.")
                "location_last_active" -> return L("A business needs at least one active location.")
                "invalid_location" -> return L("That location isn’t active in this business.")
                "location_not_assigned" -> return L("You don’t work at that location.")
                else -> {}
            }
        }
        return PlacesRoomRules.serverText(error, L("Something went wrong. Try again."))
    }
}

// MARK: - Time zones

object PlacesTimeZones {
    val common = listOf(
        "America/Mexico_City", "America/Cancun", "America/Merida", "America/Monterrey", "America/Chihuahua", "America/Mazatlan", "America/Hermosillo", "America/Tijuana",
        "America/Bogota", "America/Lima", "America/Santiago", "America/Argentina/Buenos_Aires", "America/Guatemala", "America/Costa_Rica",
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Toronto", "Europe/Madrid",
    )

    /** A city's name in Spanish, and in English where it differs from the zone's (Swift's `(es:en:)` tuple). */
    private data class CityName(val es: String, val en: String?)

    private val cityNames: Map<String, CityName> = mapOf(
        "America/Mexico_City" to CityName("Ciudad de México", null),
        "America/Cancun" to CityName("Cancún", "Cancún"),
        "America/Merida" to CityName("Mérida", "Mérida"),
        "America/Mazatlan" to CityName("Mazatlán", "Mazatlán"),
        "America/Ciudad_Juarez" to CityName("Ciudad Juárez", "Ciudad Juárez"),
        "America/Bahia_Banderas" to CityName("Bahía de Banderas", "Bahía de Banderas"),
        "America/Bogota" to CityName("Bogotá", "Bogotá"),
        "America/Panama" to CityName("Panamá", null),
        "America/Asuncion" to CityName("Asunción", "Asunción"),
        "America/Argentina/Cordoba" to CityName("Córdoba", "Córdoba"),
        "America/Argentina/Tucuman" to CityName("Tucumán", "Tucumán"),
        "America/New_York" to CityName("Nueva York", null),
        "America/Los_Angeles" to CityName("Los Ángeles", null),
        "America/Sao_Paulo" to CityName("São Paulo", "São Paulo"),
        "Europe/London" to CityName("Londres", null),
    )

    fun cityName(zone: String): String {
        val known = cityNames[zone] ?: return PlacesLocationRules.city(zone)
        return if (LocalizationManager.currentLanguage == AppLanguage.spanish) known.es else (known.en ?: PlacesLocationRules.city(zone))
    }

    fun offsetLabel(zone: String, date: Instant = Instant.now()): String? {
        val tz = zoneId(zone) ?: return null
        val minutes = tz.rules.getOffset(date).totalSeconds / 60
        if (minutes == 0) return "UTC"
        val absolute = abs(minutes)
        val rest = absolute % 60
        return "UTC${if (minutes < 0) "−" else "+"}${absolute / 60}${if (rest > 0) String.format(Locale.US, ":%02d", rest) else ""}"
    }

    /** "Cancún (UTC−5)". */
    fun label(zone: String): String {
        if (zone == "UTC") return "UTC"
        val city = cityName(zone)
        return offsetLabel(zone)?.let { "$city ($it)" } ?: city
    }

    /** The zones the system knows, as iOS lists them: "Area/City" names (no "US/…", "SystemV/…" or "Etc/…" aliases) and GMT. */
    private val knownZones: List<String> by lazy {
        val areas = setOf("Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")
        ZoneId.getAvailableZoneIds().filter { it == "GMT" || it.substringBefore("/", "") in areas }.sorted()
    }

    fun search(query: String, limit: Int = 60): List<String> {
        val zones = knownZones
        val words = fold(query).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return common.filter { it in zones }
        return zones.filter { zone ->
            val haystack = fold(zone.replace("_", " ") + " " + cityName(zone) + " " + (cityNames[zone]?.es ?: ""))
            words.all { haystack.contains(it) }
        }.take(limit)
    }

    private val marks = Regex("\\p{Mn}+")

    private fun fold(text: String): String = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
}

// MARK: - Relocations

@Serializable
data class PlacesRelocationVisit(
    val id: String,
    val startsAt: String,
    val endsAt: String? = null,
    val status: String? = null,
    val locationId: String? = null,
    val locationName: String? = null,
    val roomId: String? = null,
    val room: String? = null,
    val serviceLabel: String? = null,
    val assignedOrganizationMemberId: String? = null,
    val assignedName: String? = null,
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    val clientName: String? = null,
    val petName: String? = null,
    val seriesId: String? = null,
) {
    val pet: String get() = petName.nonEmpty ?: L("another visit")
}

@Serializable
data class PlacesRelocations(
    val visits: List<PlacesRelocationVisit>? = null,
)

@Serializable
data class PlacesRelocateAnswer(
    val appointmentId: String? = null,
    val locationId: String? = null,
    val doubleBooked: Boolean? = null,
    val hoursOverride: Boolean? = null,
    val memberWorksThere: Boolean? = null,
)

/** A visit's time on a place's clock ("mar 30 sep, 10:00"). */
object PlacesWhen {
    fun text(iso: String, zone: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        return DateText.format(date, "EEEdMMMjmm", zoneId(zone) ?: Workspace.shared.timeZone)
    }

    fun time(iso: String?, zone: String?): String = DateText.time(DateText.parse(iso), zoneId(zone))
}

// MARK: - Refusals with their whole body

@Serializable
data class PlacesConflictVisit(
    val id: String? = null,
    val petName: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
)

@Serializable
data class PlacesConflictOut(
    val untilDay: String? = null,
    val reason: String? = null,
)

/** One double booking the server named (lib/visit-conflicts.ts). */
@Serializable
data class PlacesConflict(
    val kind: String,
    val name: String? = null,
    val capacity: Int? = null,
    val visits: List<PlacesConflictVisit>? = null,
    val outOfService: PlacesConflictOut? = null,
    val travelMinutes: Int? = null,
    val postalCode: String? = null,
)

@Serializable
data class PlacesRefusalBody(
    val error: String? = null,
    val code: String? = null,
    val conflicts: List<PlacesConflict>? = null,
    val reason: String? = null,
    val closure: Closure? = null,
    val timeOff: TimeOff? = null,
    val memberId: String? = null,
) {
    @Serializable
    data class Closure(val label: String? = null)

    @Serializable
    data class TimeOff(val reason: String? = null)
}

/** A refused request with the server's whole body (conflicts, the hours problem), read for its parts. */
class PlacesRefusal(
    val api: APIError,
    val body: PlacesRefusalBody?,
) : Exception(api.message, api) {
    val conflicts: List<PlacesConflict>? get() = if (api.code == "visit_conflict") (body?.conflicts ?: emptyList()) else null

    val hours: PlacesHoursProblem?
        get() {
            if (api.code != "outside_hours") return null
            return PlacesHoursProblem(
                reason = body?.reason ?: "outside_hours",
                closureLabel = body?.closure?.label.nonEmpty,
                timeOffReason = body?.timeOff?.reason.nonEmpty,
            )
        }
}

object PlacesAPI {
    /**
     * A POST whose refusal keeps the server's whole body: `API.post`, with the refusal's
     * `APIError.body` read as `PlacesRefusalBody`. Offline stays an `APIError`.
     */
    suspend inline fun <reified T> post(path: String, body: Map<String, Any?>): T {
        try {
            return API.post(path, body)
        } catch (error: APIError) {
            if (error.isOffline || error.code == "decode_failed") throw error
            val refusal = error.body?.let { runCatching { API.json.decodeFromJsonElement(PlacesRefusalBody.serializer(), it) }.getOrNull() }
            throw PlacesRefusal(error, refusal)
        }
    }
}

/** What stopped one visit from moving: someone or a room taken, the place's hours, or anything else. */
data class PlacesHoursProblem(
    val reason: String,
    val closureLabel: String?,
    val timeOffReason: String?,
) {
    /** "Closed that day · Navidad", "Dra. Ana is off that day · Vacaciones". */
    fun title(person: String?): String = when (reason) {
        "closed_date" -> closureLabel?.let { LF("Closed that day · %@", it) } ?: L("Closed that day")
        "closed_day" -> L("Closed that day of the week")
        "break" -> L("In a break between opening hours")
        "time_off" -> {
            val who = person.nonEmpty
            if (who != null) {
                timeOffReason?.let { LF("%@ is off that day · %@", who, it) } ?: LF("%@ is off that day", who)
            } else {
                timeOffReason?.let { LF("That person is off that day · %@", it) } ?: L("That person is off that day")
            }
        }
        "member_hours" -> person.nonEmpty?.let { LF("%@ doesn’t work then", it) } ?: L("That person doesn’t work then")
        else -> L("Outside opening hours")
    }
}

/** One line per conflict, in the person's words (web room-picker `conflictLine`). */
object PlacesConflictText {
    private fun visitLine(visit: PlacesConflictVisit, zone: String?): String {
        val pet = visit.petName.nonEmpty ?: L("another visit")
        val start = PlacesWhen.time(visit.startsAt, zone)
        val end = visit.endsAt
        if (end != null) return "$pet $start–${PlacesWhen.time(end, zone)}"
        return "$pet $start"
    }

    fun line(conflict: PlacesConflict, zone: String?): String {
        val visits = conflict.visits ?: emptyList()
        val capacity = conflict.capacity ?: 1
        val out = conflict.outOfService
        if (conflict.kind == "room" && out != null) {
            val name = conflict.name.nonEmpty ?: L("That room")
            val line = out.untilDay?.let { LF("%@ is out of service until %@", name, PlacesDay.label(it)) } ?: LF("%@ is out of service", name)
            return out.reason.nonEmpty?.let { "$line ($it)" } ?: line
        }
        if (visits.isEmpty() && conflict.kind == "person") return LF("%@ takes %d at once — these pets are more than that", conflict.name.nonEmpty ?: L("That person"), capacity)
        if (visits.isEmpty() && conflict.kind == "room") return LF("%@ holds %d at once — these pets are more than that", conflict.name.nonEmpty ?: L("That room"), capacity)
        var listed = visits.take(2).joinToString(", ") { visitLine(it, zone) }
        if (visits.size > 2) listed += " " + LF("and %d more", visits.size - 2)
        return when (conflict.kind) {
            "person" -> {
                val name = conflict.name.nonEmpty ?: L("That person")
                if (capacity > 1) LF("%@ already has %d pets then (%@) and takes %d at once", name, visits.size, listed, capacity) else LF("%@ is already with %@", name, listed)
            }
            "room" -> {
                val name = conflict.name.nonEmpty ?: L("That room")
                if (capacity > 1) LF("%@ is full then (%@); it holds %d.", name, listed, capacity) else LF("%@ is taken by %@", name, listed)
            }
            "service_area" -> {
                val name = conflict.name.nonEmpty ?: L("That person")
                conflict.postalCode.nonEmpty?.let { LF("%@ is outside %@’s service area", it, name) } ?: LF("That address is outside %@’s service area", name)
            }
            "travel" -> {
                val name = conflict.name.nonEmpty ?: L("That person")
                val minutes = conflict.travelMinutes
                if (minutes != null && minutes > 0) {
                    if (listed.isEmpty()) LF("%@ needs %d min to get there from another home visit", name, minutes) else LF("%@ needs %d min to get there from %@", name, minutes, listed)
                } else {
                    if (listed.isEmpty()) LF("%@ is at another address then", name) else LF("%@ is at another address then: %@", name, listed)
                }
            }
            else -> if (capacity == 0) L("Nobody who takes visits works then (days off, their own hours).") else L("Everyone who takes visits is busy then.")
        }
    }
}

// MARK: - Small shared pieces

/** SwiftUI `.grayscale(1)`: the content drawn without colour. */
private fun Modifier.grayscale(on: Boolean): Modifier = if (!on) this else drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}

/**
 * A room kind's (or a related place's) round icon on a wash of its colour
 * (web: `color-mix(tone 26%, surface)`, navy glyph): the kit's `IconBadge`.
 */
@Composable
fun PlacesIconBadge(
    systemImage: String,
    hue: Hue = Hue.sky,
    size: Dp = 44.dp,
    muted: Boolean = false,
    /** The wash of the colour behind the glyph (26 on a room, 38 on a related place). */
    washPercent: Double = 26.0,
    /** Another colour than the hue (a related place's brand tone). */
    tone: Color? = null,
    modifier: Modifier = Modifier,
) {
    IconBadge(
        icon = systemImage, accent = tone ?: hue.color, size = size, iconSize = size * 0.45f, ink = IconBadge.Ink.strong, washPercent = washPercent,
        modifier = modifier.grayscale(muted),
    )
}

/**
 * The double-booking warning with its "book anyway" confirmation (web room-picker
 * `ConflictWarning`): a box of the destructive colour at 8% with a 2 pt border,
 * a red 40 disc with the triangle, the Fredoka title, one line per conflict,
 * and the confirmation on a white row.
 */
@Composable
fun PlacesConflictWarning(
    conflicts: List<PlacesConflict>,
    zone: String?,
    confirmed: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val onlyHome = conflicts.all { it.kind == "travel" || it.kind == "service_area" }
    val onlyOut = conflicts.all { it.kind == "room" && it.outOfService != null }
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.destructive, 8.0), shape)
            .border(2.dp, Theme.mix(Theme.destructive, 55.0, Theme.borderSoft), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon = "triangle-alert", accent = Theme.destructive, size = 40.dp, iconSize = 20.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (onlyHome) L("Check the home visit") else if (onlyOut) L("Room out of service") else L("Double booking"),
                    style = Theme.heading(18), color = Theme.inkStrong,
                )
                conflicts.forEach { conflict ->
                    Text(PlacesConflictText.line(conflict, zone), style = Theme.text(14), color = Theme.inkStrong)
                }
            }
        }
        ToggleRow(
            title = if (onlyHome) L("Book it anyway — I know") else if (onlyOut) L("Book it anyway — I know it is out of service") else L("Book it anyway — I know they overlap"),
            isOn = confirmed,
        )
    }
}

/**
 * A photo of a location (web: `size-14 rounded-card`), or its pin — the store
 * for the main one — in blue on an aqua 25% disc.
 */
@Composable
fun PlacesLocationThumb(
    photoUrl: String?,
    isPrimary: Boolean = false,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val url = APIConfig.assetURL(photoUrl)
    if (url != null) {
        val placeholder = ColorPainter(Theme.wash(Theme.aqua, 25.0))
        AsyncImage(
            model = url,
            contentDescription = null,
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(Theme.cardRadius)),
        )
    } else {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).background(Theme.wash(Theme.aqua, 25.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(if (isPrimary) "store" else "map-pin", 20.dp, tint = Theme.blue)
            }
        }
    }
}

/** A plus/minus count, the web's CountStepper. */
@Composable
fun PlacesCountStepper(
    label: String,
    value: MutableState<Int>,
    range: IntRange = 1..20,
    modifier: Modifier = Modifier,
) {
    val atLowest = value.value <= range.first
    val atHighest = value.value >= range.last
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Swift `.disabled(…)`: the action does nothing at the end of the range, and the button fades.
        IconCircleButton(systemImage = "minus", label = L("One less"), modifier = Modifier.alpha(if (atLowest) 0.4f else 1f)) {
            if (value.value > range.first) value.value -= 1
        }
        Text(
            "${value.value}",
            style = Theme.heading(22),
            color = Theme.inkStrong,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 36.dp).clearAndSetSemantics { contentDescription = LF("%@: %d", label, value.value) },
        )
        IconCircleButton(systemImage = "plus", label = L("One more"), modifier = Modifier.alpha(if (atHighest) 0.4f else 1f)) {
            if (value.value < range.last) value.value += 1
        }
    }
}

/**
 * A line to read before saying yes (what archiving leaves behind, what is
 * still booked), with an optional way there: the web's lime box — 16 corners,
 * a 1.5 pt border of lime 55%, lime 10% fill, a 16 icon, 14 navy text, an
 * underlined bold link.
 */
@Composable
fun PlacesConsequenceRow(
    systemImage: String,
    text: String,
    /** A bold first line (the web's `font-bold` count), the text then reads soft under it. */
    title: String? = null,
    actionTitle: String? = null,
    modifier: Modifier = Modifier,
    action: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.lime, 10.0), shape)
            .border(1.5.dp, Theme.mix(Theme.lime, 55.0, Theme.borderSoft), shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph(systemImage, 16.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(text, style = Theme.text(14), color = if (title == null) Theme.inkStrong else Theme.inkSoft)
            if (actionTitle != null && action != null) {
                Text(
                    actionTitle,
                    style = Theme.text(14, FontWeight.Bold).copy(textDecoration = TextDecoration.Underline),
                    color = Theme.inkStrong,
                    modifier = Modifier.clickable(role = Role.Button, onClick = action),
                )
            }
        }
    }
}
