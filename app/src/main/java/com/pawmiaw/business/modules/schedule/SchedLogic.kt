package com.pawmiaw.business.schedule

import android.net.Uri
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.UUID

//
//  SchedLogic.kt (iOS SchedLogic.swift)
//
//  The Schedule's plumbing: requests whose refusals carry more than a
//  sentence (the conflicts of a double booking, the hours problem, the fee to
//  decide), the business's days and minutes, and the same booking rules the
//  web runs before saving (lib/hours.ts, lib/visit-conflicts.ts,
//  lib/booking-hours.ts). The server checks everything again.
//

// MARK: - Requests that read the refusal's body

/** A refused request with its whole body (the conflicts, the hours problem, the fee). */
class SchedFailure(val api: APIError, val body: JSONValue?) : Exception(api.message) {
    val errorDescription: String? get() = api.message
    val code: String? get() = api.code

    val conflicts: List<SchedConflict>?
        get() {
            if (code != "visit_conflict") return null
            val raw = body["conflicts"] ?: return emptyList()
            return runCatching { API.json.decodeFromJsonElement(ListSerializer(SchedConflict.serializer()), raw) }.getOrNull() ?: emptyList()
        }

    val hoursProblem: SchedHoursProblem?
        get() {
            if (code != "outside_hours") return null
            val reasons = listOf("closed_date", "closed_day", "outside_hours", "break", "crosses_days", "time_off", "member_hours")
            val reason = body["reason"].string ?: ""
            return SchedHoursProblem(
                reason = if (reasons.contains(reason)) reason else "outside_hours",
                message = api.message,
                closureLabel = body["closure"]["label"].string,
                timeOffReason = body["timeOff"]["reason"].string,
                memberId = body["memberId"].string,
            )
        }

    val fee: SchedFeeRequired?
        get() {
            if (code != "fee_decision_required") return null
            val fee = body["fee"] ?: return null
            val amount = fee["amount"].int ?: return null
            return SchedFeeRequired(
                kind = fee["kind"].string ?: "no_show", amount = amount, currency = fee["currency"].string,
                familyAskedAt = fee["familyAskedAt"].string, measuredFrom = fee["measuredFrom"].string,
            )
        }

    /** The emergency deposit a start waits on (409 `deposit_required`). */
    val depositGap: SchedDepositDue?
        get() {
            if (code != "deposit_required") return null
            val deposit = body["deposit"]
            val amount = deposit["amount"].int
            if (deposit != null && amount != null) {
                return SchedDepositDue(amount = amount, paid = deposit["paid"].int ?: 0, currency = deposit["currency"].string)
            }
            val gate = body["gate"]["deposit"]
            if (gate != null) {
                return SchedDepositDue(amount = gate["amount"].int ?: 0, paid = gate["paid"].int ?: 0, currency = null)
            }
            return SchedDepositDue(amount = 0, paid = 0, currency = null)
        }

    /** The consents a start was refused for (409 `consent_required`). */
    val consentGap: List<ConsentLine>
        get() {
            if (code != "consent_required") return emptyList()
            val lines = body["consents"].array ?: return emptyList()
            return lines.mapNotNull { line ->
                val title = line["title"].string ?: return@mapNotNull null
                ConsentLine(templateId = line["templateId"].string, title = title, satisfied = false, consentId = line["consentId"].string, status = line["status"].string)
            }
        }
}

/** The APIError inside any failure of this module (for forms and toasts). */
fun schedAPIError(error: Throwable): Throwable = (error as? SchedFailure)?.api ?: error

fun schedErrorText(error: Throwable): String = errorText(schedAPIError(error))

fun schedToast(error: Throwable) {
    Toast.error(schedErrorText(error))
}

/** Swift `try?`: the value, or null when it fails (a cancellation still cancels). */
suspend fun <T> schedTry(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    null
}

object SchedAPI {
    /** Like `API.post/patch`, but a refusal keeps its body (`SchedFailure`, from `APIError.body`). */
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T {
        try {
            return API.decode(API.data(method, path, query, body?.let { API.jsonBody(it) }))
        } catch (api: APIError) {
            if (api.status >= 300) throw SchedFailure(api, api.body)
            throw api
        }
    }
}

// MARK: - The business's days

object SchedDay {
    val zone: ZoneId get() = Workspace.shared.timeZone

    /** The business's calendar: its clock, the app's language, weeks from Monday. */
    val calendar: Calendar
        get() {
            val calendar = GregorianCalendar(TimeZone.getTimeZone(zone), appLocale)
            calendar.firstDayOfWeek = Calendar.MONDAY
            return calendar
        }

    val today: String get() = DateText.dayKey(Instant.now(), zone)

    fun key(date: Instant, zone: ZoneId? = null): String = DateText.dayKey(date, zone ?: this.zone)

    fun key(iso: String?): String? = DateText.parse(iso)?.let { key(it) }

    private fun localDate(day: String): LocalDate? {
        val parts = day.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return runCatching { LocalDate.of(parts[0], parts[1], parts[2]) }.getOrNull()
    }

    /** Midnight of a "yyyy-MM-dd" on the business's clock. */
    fun start(day: String, zone: ZoneId? = null): Instant {
        val date = localDate(day) ?: return Instant.now()
        return date.atStartOfDay(zone ?: this.zone).toInstant()
    }

    /** A day at a minute of that day, as a date. */
    fun at(day: String, minute: Int, zone: ZoneId? = null): Instant = start(day, zone).plusSeconds(minute * 60L)

    fun addDays(day: String, count: Int): String {
        val date = localDate(day) ?: LocalDate.now(zone)
        return date.plusDays(count.toLong()).toString()
    }

    fun minuteOfDay(date: Instant, zone: ZoneId? = null): Int {
        val time = date.atZone(zone ?: this.zone)
        return time.hour * 60 + time.minute
    }

    fun weekday(day: String): String = when (start(day).atZone(zone).dayOfWeek) {
        DayOfWeek.SUNDAY -> "sunday"
        DayOfWeek.MONDAY -> "monday"
        DayOfWeek.TUESDAY -> "tuesday"
        DayOfWeek.WEDNESDAY -> "wednesday"
        DayOfWeek.THURSDAY -> "thursday"
        DayOfWeek.FRIDAY -> "friday"
        else -> "saturday"
    }

    fun date(fromKey: String): Instant = start(fromKey).plusSeconds(12 * 3600L)

    /** "10:30 a. m." for a minute of the day. */
    fun timeLabel(minute: Int): String =
        DateText.format(Instant.ofEpochSecond(maxOf(0, minOf(minute, 1439)) * 60L), "jmm", ZoneOffset.UTC)

    /** "Miércoles 4 de noviembre" (the year when it isn't this one). */
    fun longLabel(day: String): String {
        val template = if (day.take(4) == today.take(4)) "EEEEdMMMM" else "EEEEdMMMMyyyy"
        return upperFirst(DateText.format(date(fromKey = day), template, zone))
    }

    /** The day of the month, "5" (a week column's disc). */
    fun dayNumber(day: String): String = (day.takeLast(2).toIntOrNull() ?: 0).toString()

    /** "mié 5" (a week column's head). */
    fun weekColumnLabel(day: String): String = DateText.format(date(fromKey = day), "EEEd", zone)

    /** "mié 4 nov". */
    fun shortLabel(day: String): String = DateText.day(date(fromKey = day))

    /** "4 nov – 10 nov 2026". */
    fun weekLabel(first: String, last: String): String =
        "${DateText.format(date(fromKey = first), "dMMM", zone)} – ${DateText.format(date(fromKey = last), "dMMMyyyy", zone)}"

    fun relative(day: String): String? {
        if (day == today) return L("Today")
        if (day == addDays(today, 1)) return L("Tomorrow")
        if (day == addDays(today, -1)) return L("Yesterday")
        return null
    }

    fun upperFirst(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, 1).uppercase(appLocale) + text.substring(1)
    }

    /** A short duration: "45 min", "1 h 20 min". */
    fun duration(minutes: Int): String {
        val m = maxOf(0, minutes)
        if (m < 60) return LF("%@ min", m.toString())
        val h = m / 60
        val rest = m % 60
        return if (rest == 0) LF("%@ h", h.toString()) else LF("%@ h %@ min", h.toString(), rest.toString())
    }

    /** The visit's day in its location's clock: before, today or after. */
    fun `when`(startsAt: String, zone: ZoneId): String {
        val date = DateText.parse(startsAt) ?: return "today"
        val day = key(date, zone)
        val now = key(Instant.now(), zone)
        return if (day < now) "past" else if (day > now) "future" else "today"
    }
}

// MARK: - Hours

data class SchedMinuteRange(
    val start: Int,
    val end: Int,
)

object SchedHours {
    fun startMinutes(value: String): Int? {
        val parts = value.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return null
        return parts[0] * 60 + parts[1]
    }

    /** "23:59" closes at midnight. */
    fun endMinutes(value: String): Int? = if (value == "23:59") 1440 else startMinutes(value)

    fun minutes(ranges: List<SchedRange>): List<SchedMinuteRange> = ranges.mapNotNull { range ->
        val start = startMinutes(range.start) ?: return@mapNotNull null
        val end = endMinutes(range.end) ?: return@mapNotNull null
        SchedMinuteRange(start = start, end = end)
    }

    fun fits(start: Int, end: Int, ranges: List<SchedMinuteRange>): Boolean = ranges.any { start >= it.start && end <= it.end }

    /**
     * A day's open ranges and its closure (`label`, `closed`): the server's load first, then the
     * closures, then the week.
     */
    fun day(day: String, load: SchedLoad?, closures: List<SchedClosure>?, availability: SchedAvailability?): Pair<List<SchedRange>, Pair<String?, Boolean>?> {
        val loaded = load?.hoursDay(day)
        if (loaded != null) {
            val closure = loaded.closure?.let { Pair(it.label, it.closed ?: false) }
            return Pair(loaded.ranges ?: emptyList(), closure)
        }
        val covering = (closures ?: emptyList()).filter { it.covers(day) }
        val closure = covering.firstOrNull { it.ranges == null } ?: covering.lastOrNull()
        if (closure != null) {
            val ranges = closure.ranges
            if (ranges != null) return Pair(ranges, Pair(closure.label, false))
            return Pair(emptyList(), Pair(closure.label, true))
        }
        return Pair(availability?.week(SchedDay.weekday(day)) ?: emptyList(), null)
    }

    /** Whether a person is off during a time for a reason of their own (a day off, their own hours). */
    fun personOff(person: SchedLoadPerson, start: Instant, end: Instant): Boolean {
        val day = person.day(SchedDay.key(start)) ?: return false
        if (day.timeOff != null) return true
        if (person.ownHours != true) return false
        val endMinute = if (SchedDay.key(end) == day.date) SchedDay.minuteOfDay(end) else 1440
        return !fits(SchedDay.minuteOfDay(start), endMinute, minutes(day.ranges ?: emptyList()))
    }

    /** The first open day after `day` (the week and the closures), within 120 days. */
    fun nextOpenDay(after: String, closures: List<SchedClosure>?, availability: SchedAvailability?): String? {
        for (offset in 1..120) {
            val next = SchedDay.addDays(after, offset)
            if (day(next, load = null, closures = closures, availability = availability).first.isNotEmpty()) return next
        }
        return null
    }

    /** The open day closest to `day` ("¿Cuándo vuelve?"): it, a day after, a day before (never before `notBefore`). */
    fun nearestOpenDay(day: String, closures: List<SchedClosure>?, availability: SchedAvailability?, notBefore: String?): String? {
        fun opens(d: String): Boolean = day(d, load = null, closures = closures, availability = availability).first.isNotEmpty()
        if (opens(day)) return day
        for (offset in 1..3) {
            val after = SchedDay.addDays(day, offset)
            if (opens(after)) return after
            val before = SchedDay.addDays(day, -offset)
            if ((notBefore == null || before >= notBefore) && opens(before)) return before
        }
        return null
    }

    fun rangesText(ranges: List<SchedRange>): String {
        val parts = minutes(ranges).map { LF("%@ to %@", SchedDay.timeLabel(it.start), SchedDay.timeLabel(minOf(it.end, 1439))) }
        if (parts.size <= 1) return parts.joinToString("")
        return LF("%@ and %@", parts.dropLast(1).joinToString(", "), parts.last())
    }

    /** One line naming the problem ("Closed that day · Navidad", "Ana is off that day"). */
    fun problemTitle(problem: SchedHoursProblem, personName: String?): String = when (problem.reason) {
        "closed_date" -> problem.closureLabel?.let { LF("Closed that day · %@", it) } ?: L("Closed that day")
        "closed_day" -> L("Closed that day of the week")
        "break" -> L("In a break between opening hours")
        "time_off" ->
            if (personName != null) {
                problem.timeOffReason?.let { LF("%@ is off that day · %@", personName, it) } ?: LF("%@ is off that day", personName)
            } else {
                problem.timeOffReason?.let { LF("That person is off that day · %@", it) } ?: L("That person is off that day")
            }
        "member_hours" -> personName?.let { LF("%@ doesn’t work then", it) } ?: L("That person doesn’t work then")
        else -> L("Outside opening hours")
    }

    fun problemBody(problem: SchedHoursProblem): String {
        if (problem.reason == "closed_date") return "${L("Families can’t book that day online.")} ${L("Booked anyway, the visit is marked as outside opening hours.")}"
        if (problem.reason == "time_off" || problem.reason == "member_hours") {
            return "${L("They aren’t offered for online booking then, and aren’t counted for whoever is free.")} ${L("Booked anyway, the visit is marked as outside opening hours.")}"
        }
        return L("Booked anyway, the visit is marked as outside opening hours.")
    }
}

// MARK: - Double bookings (the server's lib/visit-conflicts.ts)

data class SchedSlot(
    var id: String?,
    var start: Instant,
    var end: Instant,
    var memberId: String?,
    var roomId: String?,
    var pets: Int = 1,
    var together: Boolean = false,
    var ignoreIds: List<String> = emptyList(),
)

object SchedConflicts {
    private fun span(visit: SchedLoadVisit): Pair<Instant, Instant>? {
        val start = DateText.parse(visit.startsAt) ?: return null
        val end = DateText.parse(visit.endsAt) ?: start.plusSeconds(30 * 60L)
        return Pair(start, end)
    }

    private fun overlaps(slot: SchedSlot, visit: SchedLoadVisit, buffer: Int): Boolean {
        val (start, end) = span(visit) ?: return false
        val b = maxOf(0, buffer) * 60L
        return slot.start < end.plusSeconds(b) && start < slot.end.plusSeconds(b)
    }

    private fun peak(slot: SchedSlot, visits: List<SchedLoadVisit>, buffer: Int): Int {
        val inside = visits.filter { overlaps(slot, it, buffer) }
        if (inside.size <= 1) return inside.size
        val b = maxOf(0, buffer) * 60L
        val points = mutableListOf<Pair<Instant, Int>>()
        for (visit in inside) {
            val (start, end) = span(visit) ?: continue
            points.add(Pair(maxOf(start.minusSeconds(b), slot.start), 1))
            points.add(Pair(minOf(end.plusSeconds(b), slot.end.plusSeconds(b)), -1))
        }
        points.sortWith(compareBy<Pair<Instant, Int>> { it.first }.thenBy { it.second })
        var current = 0
        var best = 0
        for ((_, delta) in points) {
            current += delta
            best = maxOf(best, current)
        }
        return best
    }

    /** Every pet of a session counts once: the session is one booking. */
    private fun collapseSessions(visits: List<SchedLoadVisit>): List<SchedLoadVisit> {
        val seen = HashSet<String>()
        return visits.filter { visit ->
            val session = visit.sessionId ?: return@filter true
            seen.add(session)
        }
    }

    private fun asConflict(v: SchedLoadVisit): SchedConflictVisit =
        SchedConflictVisit(id = v.id, petName = v.petName, startsAt = v.startsAt, endsAt = v.endsAt)

    fun find(slot: SchedSlot, load: SchedLoad): List<SchedConflict> {
        val buffer = load.bufferMinutes ?: 0
        val ignored = (listOf(slot.id) + slot.ignoreIds).filterNotNull().toSet()
        val others = collapseSessions((load.visits ?: emptyList()).filter { visit ->
            !ignored.contains(visit.id) && SchedStatus.holding.contains(visit.status) && overlaps(slot, visit, buffer)
        })
        val extra = maxOf(1, slot.pets) - 1
        fun over(peak: Int, capacity: Int): Boolean = if (slot.together && peak == 0) false else peak + extra >= capacity
        val conflicts = mutableListOf<SchedConflict>()
        val people = load.people ?: emptyList()
        val memberId = slot.memberId
        if (memberId != null) {
            val person = people.firstOrNull { it.id == memberId }
            val capacity = maxOf(1, person?.capacity ?: 1)
            val theirs = others.filter { it.memberId == memberId }
            if (over(peak(slot, theirs, buffer), capacity)) {
                conflicts.add(SchedConflict(kind = "person", memberId = memberId, name = person?.name, capacity = capacity, visits = theirs.map { asConflict(it) }))
            }
        } else if (people.isNotEmpty()) {
            val seats = people.filter { !SchedHours.personOff(it, start = slot.start, end = slot.end) }
            val capacity = seats.sumOf { maxOf(1, it.capacity ?: 1) }
            if (over(peak(slot, others, buffer), capacity)) {
                conflicts.add(SchedConflict(kind = "everyone", capacity = capacity, visits = others.map { asConflict(it) }))
            }
        }
        val roomId = slot.roomId
        if (roomId != null) {
            val room = load.visitRooms.firstOrNull { it.id == roomId }
            val capacity = maxOf(1, room?.capacity ?: 1)
            val inRoom = others.filter { it.roomId == roomId }
            val out = room?.outOn(SchedDay.key(slot.start))
            if (room != null && out != null) {
                conflicts.add(SchedConflict(kind = "room", roomId = roomId, name = room.name, capacity = capacity, visits = emptyList(), outOfService = SchedOutOfServiceNote(untilDay = out.untilDay, reason = out.reason)))
            } else if (over(peak(slot, inRoom, buffer), capacity)) {
                conflicts.add(SchedConflict(kind = "room", roomId = roomId, name = room?.name, capacity = capacity, visits = inRoom.map { asConflict(it) }))
            }
        }
        return conflicts
    }

    fun roomIsFree(slot: SchedSlot, room: SchedRoom, load: SchedLoad): Boolean {
        val probe = slot.copy(roomId = room.id, memberId = null)
        val only = SchedLoad(visits = load.visits, people = emptyList(), rooms = listOf(room), bufferMinutes = load.bufferMinutes, hours = null)
        return !find(probe, only).any { it.kind == "room" }
    }

    /** The first free room meant for the service, then the first free general room. */
    fun pickFreeRoom(slot: SchedSlot, load: SchedLoad, serviceId: String?): SchedRoom? {
        val free = load.visitRooms.filter { roomIsFree(slot, it, load) }
        if (serviceId != null) {
            free.firstOrNull { (it.serviceIds ?: emptyList()).contains(serviceId) }?.let { return it }
        }
        return free.firstOrNull { (it.serviceIds ?: emptyList()).isEmpty() }
    }

    /** A conflict in one sentence (web rooms/room-picker `conflictLine`). */
    fun line(conflict: SchedConflict): String {
        val visits = conflict.visits ?: emptyList()
        val out = conflict.outOfService
        if (conflict.kind == "room" && out != null) {
            val name = conflict.name ?: L("That room")
            val base = out.untilDay?.let { LF("%@ is out of service until %@", name, SchedDay.shortLabel(it)) } ?: LF("%@ is out of service", name)
            return out.reason?.let { "$base ($it)" } ?: base
        }
        val capacity = conflict.capacity ?: 1
        if (visits.isEmpty() && conflict.kind == "person") return LF("%@ takes %@ at once — these pets are more than that", conflict.name ?: L("That person"), capacity.toString())
        if (visits.isEmpty() && conflict.kind == "room") return LF("%@ holds %@ at once — these pets are more than that", conflict.name ?: L("That room"), capacity.toString())
        var list = visits.take(2).joinToString(", ") { visit ->
            val time = DateText.time(visit.startsAt)
            visit.petName?.let { "$it ($time)" } ?: time
        }
        if (visits.size > 2) list += " " + LF("and %@ more", (visits.size - 2).toString())
        return when (conflict.kind) {
            "person" ->
                if (capacity > 1) LF("%@ already has %@ pets then (%@) and takes %@ at once", conflict.name ?: L("That person"), visits.size.toString(), list, capacity.toString())
                else LF("%@ is already with %@", conflict.name ?: L("That person"), list)
            "room" ->
                if (capacity > 1) LF("%@ is full then (%@); it holds %@.", conflict.name ?: L("That room"), list, capacity.toString())
                else LF("%@ is taken by %@", conflict.name ?: L("That room"), list)
            "travel", "service_area" -> L("The home visit doesn’t leave time to get there, or is outside their area.")
            else -> if (capacity == 0) L("Nobody who takes visits works then (days off, their own hours).") else L("Everyone who takes visits is busy then.")
        }
    }
}

// MARK: - Shared reads

object SchedData {
    /** Everyone on the team (`/provider-workspaces/{clinicId}/members`). */
    suspend fun members(): List<SchedMember> {
        val clinic = Workspace.shared.current?.clinicId ?: return emptyList()
        return schedTry { API.get<List<SchedMember>>("/provider-workspaces/$clinic/members") } ?: emptyList()
    }

    /** The rooms visits go in at this device's place (wards and cages left out). */
    suspend fun rooms(locationId: String?): List<SchedRoom> {
        val rooms: List<SchedRoom> = schedTry { API.get<List<SchedRoom>>("/provider-rooms", mapOf("locationId" to locationId)) } ?: emptyList()
        return rooms.filter { it.holdsVisits && it.status != "archived" }.sortedBy { it.sortOrder ?: 0 }
    }

    /** The booking settings and week of a place: the location's with several, else the workspace's. */
    suspend fun availability(locationId: String?): SchedAvailability? {
        val w = Workspace.shared
        val wanted = locationId ?: (if (w.multiLocation) w.selectedLocation?.id else null)
        if (wanted != null) {
            val answer = schedTry { API.get<SchedLocationsAvailability>("/provider-locations", mapOf("includeArchived" to "true")) }
            answer?.locations?.firstOrNull { it.id == wanted }?.availability?.let { return it }
        }
        val active = schedTry { API.get<SchedWorkspaceAvailability>("/provider-workspaces/active") }
        return active?.availability
    }

    suspend fun visit(id: String): SchedAppointment = API.get("/provider-appointments/$id")

    suspend fun visitRecords(appointmentId: String): List<SchedRecord>? {
        val page = schedTry { API.get<Page<SchedRecord>>("/provider-records", mapOf("limit" to "100", "offset" to "0", "appointmentId" to appointmentId)) }
        return page?.items
    }

    suspend fun catalog(): SchedCatalog? = schedTry { API.get<SchedCatalog>("/appointment-services", mapOf("scope" to "provider")) }

    /** How many clients the business has (null when it can't be read). */
    suspend fun clientsTotal(): Int? {
        val page = schedTry { API.get<Page<SchedClient>>("/provider-clients", mapOf("limit" to "1", "offset" to "0")) }
        return page?.total
    }

    /** Visits whose family asked to move or cancel, from today on. */
    suspend fun familyAsking(locationId: String?): List<SchedAppointment> {
        val from = DateText.iso(SchedDay.start(SchedDay.today))
        val page = schedTry {
            API.get<Page<SchedAppointment>>("/provider-appointments", mapOf("limit" to "20", "offset" to "0", "requests" to "true", "from" to from, "locationId" to locationId))
        }
        // Checked here too: an older API ignores the filter and would send every visit.
        return (page?.items ?: emptyList()).filter { it.familyAsking }
    }

    suspend fun closures(locationId: String?): List<SchedClosure> {
        val answer = schedTry { API.get<SchedClosures>("/provider-hours/closures", mapOf("locationId" to locationId)) }
        return answer?.closures ?: emptyList()
    }

    suspend fun load(from: Instant, to: Instant, locationId: String?): SchedLoad =
        API.get("/provider-schedule/load", mapOf("from" to DateText.iso(from), "to" to DateText.iso(to), "locationId" to locationId))

    suspend fun load(day: String, locationId: String?): SchedLoad =
        load(from = SchedDay.start(day), to = SchedDay.start(SchedDay.addDays(day, 1)), locationId = locationId)

    /** The clock of a visit's location, else the business's. */
    fun zone(of: String?): ZoneId {
        val w = Workspace.shared
        if (of != null) {
            val place = w.allLocations.firstOrNull { it.id == of }
            val id = place?.timezone ?: place?.availability?.timezone
            val zone = id?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            if (zone != null) return zone
        }
        return w.timeZone
    }

    /** The people who take visits at a place (nobody given locations = everywhere). */
    fun visitTakers(members: List<SchedMember>, locationId: String?): List<SchedMember> {
        val here = members.filter { it.worksAt(locationId) && it.status != "suspended" }
        val takers = here.filter { it.takesVisits }
        return if (takers.isEmpty()) here else takers
    }
}

// MARK: - Small helpers

/** "52" + 10 digits for a Mexican number, else the digits as they are; null below 10 digits. */
fun schedWhatsAppURL(phone: String?, message: String? = null): String? {
    val digits = (phone ?: "").filter { it.isDigit() }
    if (digits.length < 10) return null
    val number = if (digits.length == 10) "52$digits" else digits
    val base = Uri.parse("https://wa.me/$number")
    return (if (message != null) base.buildUpon().appendQueryParameter("text", message).build() else base).toString()
}

fun schedPhoneURL(phone: String?): String? {
    val cleaned = (phone ?: "").filter { it.isDigit() || it == '+' }
    return if (cleaned.isEmpty()) null else "tel:$cleaned"
}

/** "3 años", "5 meses" from a birth date. */
fun schedAge(birthDate: String?): String? {
    val born = DateText.parse(birthDate) ?: return null
    val zone = ZoneId.systemDefault()
    val months = maxOf(0L, ChronoUnit.MONTHS.between(born.atZone(zone), Instant.now().atZone(zone))).toInt()
    if (months < 12) return LF("%@ mo", months.toString())
    return LF("%@ yr", (months / 12).toString())
}

fun schedSpeciesLabel(species: String?): String? = when (species?.lowercase()) {
    "dog" -> L("Dog")
    "cat" -> L("Cat")
    "bird" -> L("Bird")
    "rabbit" -> L("Rabbit")
    "fish" -> L("Fish")
    "reptile" -> L("Reptile")
    "other" -> L("Other")
    null, "" -> null
    else -> species
}

fun schedSexLabel(sex: String?): String? = when (sex?.lowercase()) {
    "male" -> L("Male")
    "female" -> L("Female")
    else -> null
}

/** A fresh id for a request that must never run twice (a walk-in). */
fun schedRequestId(): String = UUID.randomUUID().toString().lowercase()
