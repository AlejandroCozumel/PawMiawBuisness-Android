package com.pawmiaw.business.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

//
//  SchedBookingSheet.kt (iOS SchedBookingSheet.swift)
//
//  Booking a visit, editing (moving) one, or a walk-in ("Sin cita") — the
//  web's provider-appointment-sheet.tsx in three steps: the client and the
//  pet (several of a family booked together), the service from the catalog,
//  then the details — the person, the day, the start times the day's hours
//  leave (taken ones marked), "Outside opening hours", the room, the
//  family's confirmation and notes. A double booking or a time outside the
//  hours is booked only once confirmed, as the server's rule.
//
//  The Swift view's @State lives in `SchedBookingModel` here, so the save and the loads read
//  the values as they are now (a coroutine resumes after the sheet recomposed).
//

data class SchedBookingDraft(
    val mode: Mode = Mode.appointment,
    /** Editing (moving) this visit. */
    val appointment: SchedAppointment? = null,
    val day: String? = null,
    val startMinute: Int? = null,
    val durationMinutes: Int? = null,
    val memberId: String? = null,
    val roomId: String? = null,
    val clientId: String? = null,
    val petId: String? = null,
    /** The catalog service to book (a prefill). */
    val serviceId: String? = null,
    val notes: String? = null,
    /** The Due list's follow-up this visit answers: tied to it once booked. */
    val followUpId: String? = null,
) {
    enum class Mode { appointment, walkIn }

    val id: UUID = UUID.randomUUID()
}

private val SchedDurations: List<Int> = (15..180 step 15).toList()

/** iOS `.onChange(of:)`: runs on a change of the keys, not on the first value. */
@Composable
private fun SchedOnChange(vararg keys: Any?, action: () -> Unit) {
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(*keys) {
        if (first.value) first.value = false else action()
    }
}

private class SchedBookingModel(val draft: SchedBookingDraft, val scope: CoroutineScope) {
    val workspace = Workspace.shared
    val form = FormState()
    var dismiss: () -> Unit = {}
    var onGate: (SchedGatePrompt) -> Unit = {}
    var onBooked: (suspend (SchedAppointment) -> Unit)? = null
    var staysOnGate = false

    var step by mutableIntStateOf(0)
    var client by mutableStateOf<SchedClient?>(null)
    val petIdsState = mutableStateOf<List<String>>(emptyList())
    var petIds by petIdsState
    var catalog by mutableStateOf<SchedCatalog?>(null)
    var category by mutableStateOf("")
    var serviceId by mutableStateOf("")
    var serviceLabel by mutableStateOf("")
    var labelFromService by mutableStateOf(true)
    var date by mutableStateOf(Instant.now())
    val assigneeState = mutableStateOf("")
    var assignee by assigneeState
    val durationState = mutableStateOf(30)
    var duration by durationState
    var startMinute by mutableStateOf<Int?>(null)
    var slotTouched by mutableStateOf(false)
    val afterHoursState = mutableStateOf(false)
    var afterHours by afterHoursState
    var roomId by mutableStateOf<String?>(null)
    var roomTouched by mutableStateOf(false)
    val notesState = mutableStateOf("")
    var notes by notesState
    var requestConfirmation by mutableStateOf(true)
    val doubleBookConfirmedState = mutableStateOf(false)
    var doubleBookConfirmed by doubleBookConfirmedState
    var serverConflicts by mutableStateOf<List<SchedConflict>?>(null)
    var serverHours by mutableStateOf<SchedHoursProblem?>(null)
    val hoursConfirmedState = mutableStateOf(false)
    var hoursConfirmed by hoursConfirmedState
    val togetherConfirmedState = mutableStateOf(false)
    var togetherConfirmed by togetherConfirmedState
    val walkInStartState = mutableStateOf(false)
    var walkInStart by walkInStartState
    val walkInWeightState = mutableStateOf("")
    var walkInWeight by walkInWeightState
    var walkInRequestId = schedRequestId()
    var members by mutableStateOf<List<SchedMember>>(emptyList())
    var availability by mutableStateOf<SchedAvailability?>(null)
    var closures by mutableStateOf<List<SchedClosure>>(emptyList())
    var load by mutableStateOf<SchedLoad?>(null)
    var loadingDay by mutableStateOf(false)
    var prepared = false
    var working by mutableStateOf(false)
    val repeatDraftState = mutableStateOf(SchedRepeatDraft())
    var repeatDraft by repeatDraftState
    val groomRequestsState = mutableStateOf<Map<String, SchedGroomInput>>(emptyMap())
    var groomRequests by groomRequestsState
    val atClientHomeState = mutableStateOf(false)
    var atClientHome by atClientHomeState
    var serviceAddressId by mutableStateOf<String?>(null)
    val accessNotesState = mutableStateOf("")
    var accessNotes by accessNotesState
    var addressError by mutableStateOf<String?>(null)
    var walkInTriage by mutableStateOf(SchedTriageDraft())
    var triageInvalid by mutableStateOf<SchedTriageDraft.Missing?>(null)

    val editing: SchedAppointment? get() = draft.appointment
    val walkIn: Boolean get() = draft.mode == SchedBookingDraft.Mode.walkIn
    val canAssignAnyone: Boolean get() = workspace.can(Permission.appointmentsAssignAnyone)
    val locationId: String? get() = editing?.locationId ?: workspace.locationId
    val dayKey: String get() = SchedDay.key(date)
    val me: SchedMember? get() = members.firstOrNull { it.isMe }
    val pets: List<SchedClientPet> get() = client?.bookablePets ?: emptyList()
    val petId: String? get() = petIds.firstOrNull()
    val extraPetIds: List<String> get() = petIds.drop(1)
    val canBookTogether: Boolean get() = editing == null && !walkIn && pets.size > 1
    val services: List<SchedCatalogService>
        get() = (catalog?.services ?: emptyList()).filter { (it.id != "group_walk" && it.id != "group_class") || it.id == editing?.serviceId }
    val categories: List<SchedCatalogCategory> get() = catalog?.categories ?: emptyList()
    val service: SchedCatalogService? get() = services.firstOrNull { it.id == serviceId }
    val serviceOptional: Boolean get() = editing != null && editing?.serviceId == null
    val homeService: Boolean get() = service?.isHome ?: (category == "walker" || category == "sitter")
    /** Any other service can be made at home too: the switch says so (web lib/visit-repeat.ts `offersAtClientHome`). */
    val canBeAtClientHome: Boolean get() = editing == null && !walkIn && !homeService && listOf("vet", "groomer", "trainer", "other").contains(category)
    val homeVisit: Boolean get() = !walkIn && (homeService || (canBeAtClientHome && atClientHome))
    /** A new home visit asks where it happens. */
    val askAddress: Boolean get() = homeVisit && editing == null
    /** Grooming: what the owner asked for, one per pet booked. */
    val groomBooking: Boolean get() = editing == null && !walkIn && category == "groomer"
    /** An emergency walk-in at a clinic is triaged as it comes in. */
    val walkInVetCare: Boolean get() = walkIn && workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)
    val bookedPets: List<Pair<String, String>> get() = petIds.mapNotNull { id -> pets.firstOrNull { it.id == id }?.let { Pair(id, it.petName) } }
    val clientHasEmail: Boolean get() = client?.ownerEmail.nonEmpty != null

    val petNames: String?
        get() {
            val names = petIds.mapNotNull { id -> pets.firstOrNull { it.id == id }?.petName }
            return if (names.isEmpty()) null else schedNames(names)
        }

    val primaryTitle: String
        get() = when (step) {
            0, 1 -> L("Continue")
            else -> if (walkIn) (if (walkInStart) L("Start now") else L("Add to the waiting room"))
                else if (editing != null) L("Save appointment") else L("Add appointment")
        }

    /** Only a walk, sitting, class or daycare repeats (web lib/visit-repeat.ts). */
    val bookingRepeats: Boolean get() = listOf("walker", "sitter", "trainer", "daycare").contains(category)

    val assigneeOptions: List<Pair<String, String>>
        get() {
            val takers = SchedData.visitTakers(members, locationId = locationId)
            val list = if (takers.any { it.id == assignee } || assignee.isEmpty()) takers else takers + members.filter { it.id == assignee }
            val people = list.map { Pair(it.id, if (it.isMe) LF("%@ (You)", it.label) else it.label) }
            return if (walkIn) listOf(Pair("", L("Whoever is free"))) + people
            else (if (assignee.isEmpty()) listOf(Pair("", L("Choose assignee"))) else emptyList()) + people
        }

    val roomOptions: List<Pair<String?, String>>
        get() {
            val rooms = load?.visitRooms ?: emptyList()
            val slot = currentSlot
            val loaded = load
            return listOf<Pair<String?, String>>(Pair(null, L("No room"))) + rooms.map { room ->
                val free = if (slot != null && loaded != null) SchedConflicts.roomIsFree(slot, room = room, load = loaded) else true
                Pair(room.id, if (free) room.name else "${room.name} · ${L("taken")}")
            }
        }

    // MARK: The time

    val dayHours: Pair<List<SchedRange>, Pair<String?, Boolean>?>
        get() = SchedHours.day(dayKey, load = load, closures = closures, availability = availability)
    val openRanges: List<SchedMinuteRange> get() = SchedHours.minutes(dayHours.first)
    val dayClosed: Boolean get() = !walkIn && openRanges.isEmpty()
    val nowMinuteFloor: Int
        get() = if (dayKey == SchedDay.today) SchedDay.minuteOfDay(Instant.now()) else (if (dayKey < SchedDay.today) 1440 else -1)

    val openStarts: List<Int>
        get() {
            val starts = mutableListOf<Int>()
            val floor = nowMinuteFloor
            val past = editing != null && dayKey < SchedDay.today
            for (range in openRanges) {
                var minute = range.start
                while (minute + duration <= range.end) {
                    if (minute >= floor || past) starts.add(minute)
                    minute += 15
                }
            }
            return starts
        }

    val afterHoursStarts: List<Int>
        get() {
            val open = openRanges
            val first = minOf(6 * 60, open.minOfOrNull { it.start } ?: (6 * 60))
            val last = maxOf(22 * 60, open.maxOfOrNull { it.end - duration } ?: (22 * 60))
            val set = HashSet<Int>()
            val floor = nowMinuteFloor
            var minute = first / 15 * 15
            while (minute <= last) {
                if (minute >= 0 && minute + duration <= 1440 && minute >= floor) set.add(minute)
                minute += 15
            }
            startMinute?.let { set.add(it) }
            return set.sorted()
        }

    fun slot(at: Int): SchedSlot {
        val start = SchedDay.at(dayKey, minute = at)
        val seats = groupSeats
        return SchedSlot(id = editing?.id, start = start, end = start.plusSeconds(duration * 60L), memberId = assignee.nonEmpty, roomId = null, pets = seats.first, together = seats.second, ignoreIds = seats.third)
    }

    val groupSeats: Triple<Int, Boolean, List<String>>
        get() {
            if (walkIn) return Triple(1, false, emptyList())
            val visit = editing
            if (visit != null && visit.group?.kind == "household") {
                val others = (visit.group?.stayingPets ?: emptyList()).filter { it.appointmentId != visit.id }.map { it.appointmentId }
                return Triple(1 + others.size, true, others)
            }
            return Triple(1 + extraPetIds.size, extraPetIds.isNotEmpty(), emptyList())
        }

    /** The start times the load says are taken (worked out once per change, as many rows read them). */
    val busyStarts: Set<Int> by derivedStateOf {
        val loaded = load
        if (loaded == null) emptySet() else openStarts.filter { SchedConflicts.find(slot(at = it), loaded).isNotEmpty() }.toSet()
    }

    val offStarts: Set<Int> by derivedStateOf {
        val person = load?.people?.firstOrNull { it.id == assignee }
        if (person == null) emptySet() else openStarts.filter { minute ->
            val s = slot(at = minute)
            SchedHours.personOff(person, start = s.start, end = s.end)
        }.toSet()
    }

    val currentSlot: SchedSlot?
        get() {
            if (walkIn) {
                val now = Instant.now()
                return SchedSlot(id = null, start = now, end = now.plusSeconds(duration * 60L), memberId = assignee.nonEmpty, roomId = roomId)
            }
            val minute = startMinute ?: return null
            return slot(at = minute).copy(roomId = roomId)
        }

    val localConflicts: List<SchedConflict>
        get() {
            val loaded = load ?: return emptyList()
            val slot = currentSlot ?: return emptyList()
            return SchedConflicts.find(slot, loaded)
        }
    val shownConflicts: List<SchedConflict> get() = localConflicts.ifEmpty { serverConflicts ?: emptyList() }

    val localHoursProblem: SchedHoursProblem?
        get() {
            if (walkIn) return null
            val slot = currentSlot ?: return null
            val closure = load?.hoursDay(dayKey)?.closure
            if (closure != null && closure.closed == true) {
                return SchedHoursProblem(reason = "closed_date", message = "", closureLabel = closure.label, timeOffReason = null, memberId = null)
            }
            val person = load?.people?.firstOrNull { it.id == assignee } ?: return null
            val off = person.day(dayKey)?.timeOff
            if (off != null) {
                return SchedHoursProblem(reason = "time_off", message = "", closureLabel = null, timeOffReason = off.reason, memberId = person.id)
            }
            if (person.ownHours == true && SchedHours.personOff(person, start = slot.start, end = slot.end)) {
                return SchedHoursProblem(reason = "member_hours", message = "", closureLabel = null, timeOffReason = null, memberId = person.id)
            }
            return null
        }
    val shownHoursProblem: SchedHoursProblem? get() = localHoursProblem ?: serverHours

    /** More of the family's pets than the person takes at once (`person`, `capacity`, the pets). */
    val takenTogether: Triple<String, Int, List<String>>?
        get() {
            if (walkIn || editing != null || extraPetIds.isEmpty() || assignee.isEmpty()) return null
            val person = load?.people?.firstOrNull { it.id == assignee } ?: return null
            val capacity = maxOf(1, person.capacity ?: 1)
            val names = petIds.mapNotNull { id -> pets.firstOrNull { it.id == id }?.petName }
            return if (names.size > capacity) Triple(person.name ?: "", capacity, names) else null
        }

    fun personLabel(problem: SchedHoursProblem): String? = members.firstOrNull { it.id == (problem.memberId ?: assignee) }?.label

    fun slotLabel(minute: Int, inHours: Boolean, busy: Set<Int>, off: Set<Int>): String {
        var text = LF("%@ · ends %@", SchedDay.timeLabel(minute), SchedDay.timeLabel(minOf(minute + duration, 1439)))
        if (afterHours && !inHours) text += " · " + L("Outside opening hours").lowercase(appLocale)
        if (busy.contains(minute)) text += " · " + L("taken") else if (off.contains(minute)) text += " · " + L("not working")
        return text
    }

    val summaryText: String
        get() {
            val busy = busyStarts.size
            val all = openStarts.size
            var text = if (busy == all && all > 0) L("Every start time this day is taken — choose one to double book, or another day.")
                else if (busy > 0) LF("%@ free · %@ taken (marked)", (all - busy).toString(), busy.toString())
                else LF("%@ free start times", all.toString())
            val buffer = load?.bufferMinutes ?: availability?.defaultBufferMinutes
            if (buffer != null && buffer > 0) text += LF(" · includes %@ buffer after each visit", SchedDay.duration(buffer))
            return text
        }

    fun pickCategory(value: String) {
        category = value
        serviceId = ""
        form.clear("category")
        form.clear("service")
    }

    fun applyService() {
        val service = service ?: return
        if (serviceLabel.nonEmpty == null || labelFromService) {
            serviceLabel = service.label
            labelFromService = true
        }
        // A new booking takes its service's own length, unless one was dragged in or chosen.
        val minutes = service.minutes
        if (editing == null && !walkIn && draft.durationMinutes == null && !slotTouched && minutes != null) {
            duration = SchedDurations.minByOrNull { abs(it - minutes) } ?: duration
        }
    }

    fun changeDate(value: Instant) {
        if (SchedDay.key(value) == dayKey) return
        date = value
        startMinute = null
        slotTouched = false
        afterHours = false
        form.clear("date")
        form.clear("startTime")
        resetChecks()
    }

    fun resetChecks() {
        doubleBookConfirmed = false
        serverConflicts = null
        serverHours = null
        hoursConfirmed = false
    }

    fun setStart(value: Int?) {
        startMinute = value
        slotTouched = true
        form.clear("startTime")
    }

    // MARK: Loading

    suspend fun prepare() {
        if (prepared) return
        prepared = true
        val location = locationId
        val visit = editing
        coroutineScope {
            val people = async { SchedData.members() }
            val book = async { SchedData.catalog() }
            val week = async { SchedData.availability(locationId = visit?.locationId) }
            val closed = async { SchedData.closures(locationId = location) }
            members = people.await()
            catalog = book.await()
            availability = week.await()
            closures = closed.await()
        }
        if (visit != null) applyEditing(visit) else applyNew()
        if (!canAssignAnyone && assignee.isEmpty()) assignee = editing?.assignedMember?.id ?: me?.id ?: ""
    }

    suspend fun applyEditing(visit: SchedAppointment) {
        step = 2
        schedTry { API.get<SchedClient>("/provider-clients/${visit.providerClientId}") }?.let { client = it }
        petIds = listOf(visit.providerClientPetId)
        category = visit.serviceCategory ?: services.firstOrNull { it.id == visit.serviceId }?.category ?: "other"
        serviceId = visit.serviceId ?: ""
        serviceLabel = visit.serviceLabel
        labelFromService = false
        val start = visit.start
        if (start != null) {
            date = start
            startMinute = SchedDay.minuteOfDay(start)
        }
        duration = if (SchedDurations.contains(visit.lengthMinutes)) visit.lengthMinutes else (availability?.defaultAppointmentDurationMinutes ?: 30)
        afterHours = visit.hoursOverride == true
        roomId = visit.roomId
        roomTouched = true
        slotTouched = true
        notes = visit.notes ?: ""
        assignee = visit.assignedMember?.id ?: ""
    }

    fun applyNew() {
        draft.day?.let { date = SchedDay.date(fromKey = maxOf(it, SchedDay.today)) }
        duration = draft.durationMinutes?.let { d -> SchedDurations.minByOrNull { abs(it - d) } ?: 30 } ?: (availability?.defaultAppointmentDurationMinutes ?: 30)
        val minute = draft.startMinute
        if (minute != null) {
            startMinute = minute
            slotTouched = true
            val open = SchedHours.minutes(SchedHours.day(dayKey, load = null, closures = closures, availability = availability).first)
            afterHours = !SchedHours.fits(minute, minute + duration, open)
        }
        roomId = draft.roomId
        roomTouched = draft.roomId != null
        assignee = draft.memberId ?: (if (walkIn) "" else me?.id ?: "")
        requestConfirmation = availability?.requestAppointmentConfirmationByDefault ?: true
        if (categories.size == 1) category = categories[0].id
        draft.notes.nonEmpty?.let { notes = it }
        // A service the asking screen chose: its category and the service picked.
        val wanted = draft.serviceId
        val found = wanted?.let { id -> services.firstOrNull { it.id == id } }
        if (found != null) {
            category = found.category
            serviceId = found.id
        }
        val clientId = draft.clientId
        if (clientId != null) {
            scope.launch {
                val loaded = schedTry { API.get<SchedClient>("/provider-clients/$clientId") } ?: return@launch
                client = loaded
                petIds = draft.petId?.let { listOf(it) } ?: (if (loaded.bookablePets.size == 1) listOf(loaded.bookablePets[0].id) else emptyList())
                if (petIds.isNotEmpty()) step = if (serviceId.isEmpty()) 1 else 2
            }
        }
    }

    suspend fun loadDay() {
        if (step != 2) return
        loadingDay = true
        try {
            load = schedTry { SchedData.load(day = dayKey, locationId = locationId) }
            pickDefaults()
        } finally {
            loadingDay = false
        }
    }

    /** The sheet's own pick moves to the first free time; a room for the time, the service's first. */
    fun pickDefaults() {
        val loaded = load ?: return
        if (!walkIn && !afterHours && !slotTouched) {
            val starts = openStarts
            val busy = busyStarts
            val off = offStarts
            val free = starts.firstOrNull { !busy.contains(it) && !off.contains(it) } ?: starts.firstOrNull()
            val current = startMinute
            if (current == null || busy.contains(current) || off.contains(current) || !starts.contains(current)) startMinute = free
        }
        val slot = currentSlot
        if (!homeService && !roomTouched && slot != null && loaded.visitRooms.isNotEmpty()) {
            roomId = SchedConflicts.pickFreeRoom(slot, load = loaded, serviceId = serviceId.nonEmpty)?.id
        }
    }

    // MARK: Saving

    fun primary() {
        when (step) {
            0 -> {
                if (!form.check(listOf(
                        "client" to (if (client == null) L("Choose the client first.") else null),
                        "pet" to (if (client != null && petId == null) L("Choose the pet first.") else null),
                    ))) return
                if (category.isEmpty() && categories.size == 1) category = categories[0].id
                step = if (serviceId.isEmpty()) 1 else 2
            }
            1 -> {
                if (!form.check(listOf(
                        "category" to (if (category.isEmpty()) L("Choose the service category first.") else null),
                        "service" to (if (serviceId.isEmpty() && !serviceOptional) L("Choose the service first.") else null),
                    ))) return
                step = 2
            }
            else -> if (walkIn) saveWalkIn() else save()
        }
    }

    fun save() {
        val hoursTitle = shownHoursProblem?.let { SchedHours.problemTitle(it, personName = personLabel(it)) }
        val together = takenTogether
        val start0 = startMinute
        val timeError: String? = when {
            start0 == null -> if (afterHours) L("Choose the start time first.") else L("Choose one of the available start times first.")
            shownConflicts.isNotEmpty() && !doubleBookConfirmed -> L("This time is taken — confirm the double booking below, or choose another time.")
            together != null && !togetherConfirmed -> LF("Confirm that %@ takes the %@ pets together.", together.first, together.third.size.toString())
            hoursTitle != null && !afterHours && !hoursConfirmed -> LF("%@ — confirm below to book it anyway, or choose another time.", hoursTitle)
            SchedDay.at(dayKey, minute = start0) < Instant.now() && editing == null -> L("Choose a current or future appointment time.")
            else -> null
        }
        val repeatProblem: String? = if (editing == null && bookingRepeats && repeatDraft.on && repeatDraft.mode == "weekdays" &&
            SchedRepeat.untilProblem(dayKey, repeatDraft.until ?: SchedRepeat.defaultUntil(dayKey))) L("Choose an end date within 26 weeks of the first visit") else null
        if (!form.check(listOf(
                "serviceLabel" to (if (serviceLabel.nonEmpty == null) L("Add the visit or service name first.") else null),
                "date" to (if (editing == null && dayKey < SchedDay.today) L("Choose today or a future day for a new appointment.") else null),
                "assignee" to (if (assignee.isEmpty()) L("Choose the assignee first.") else null),
                "startTime" to timeError,
                "repeat" to repeatProblem,
            ))) return
        val owner = client ?: return
        val pet = petId ?: return
        val minute = startMinute ?: return
        val start = SchedDay.at(dayKey, minute = minute)
        val end = start.plusSeconds(duration * 60L)
        val body = mutableMapOf<String, Any?>(
            "providerClientId" to owner.id,
            "providerClientPetId" to pet,
            "assignedOrganizationMemberId" to assignee.nonEmpty,
            "serviceCategory" to category.nonEmpty,
            "serviceId" to serviceId.nonEmpty,
            "serviceLabel" to serviceLabel.nonEmpty,
            "startsAt" to DateText.iso(start),
            "endsAt" to DateText.iso(end),
            "notes" to notes.nonEmpty,
            "outsideHours" to (afterHours || hoursConfirmed),
            "allowDoubleBooking" to doubleBookConfirmed,
        )
        val visit = editing
        val editingId = visit?.id
        if (visit != null) {
            if (roomId != visit.roomId) body["roomId"] = roomId
        } else {
            if (extraPetIds.isNotEmpty()) {
                body["providerClientPetIds"] = petIds
                body["petsTogether"] = true
            }
            body["roomId"] = if (homeVisit) null else roomId
            body["status"] = "scheduled"
            body["requestConfirmation"] = requestConfirmation && clientHasEmail
            workspace.locationId?.let { body["locationId"] = it }
            // None chosen (the addresses couldn't be read): the server takes the client's default, or answers address_required.
            if (askAddress) {
                body["serviceAddressId"] = serviceAddressId
                body["accessNotes"] = accessNotes.nonEmpty
            }
            if (bookingRepeats) SchedRepeat.body(repeatDraft, first = dayKey)?.let { body["repeat"] = it }
            if (groomBooking) {
                val requests = SchedBookingGroomRequests.payload(groomRequests, petIds = petIds)
                if (requests.isNotEmpty()) body["groomRequests"] = requests
            }
        }
        val payload = body.toMap()
        val names = petNames
        val followUpId = draft.followUpId
        val booked = onBooked
        val linksFollowUps = workspace.can(Permission.followupsEdit) || workspace.can(Permission.followupsComplete)
        working = true
        scope.launch {
            try {
                if (editingId != null) {
                    SchedAPI.send<SchedAppointment>("PATCH", "/provider-appointments/$editingId", body = payload)
                    Toast.show(L("Appointment updated."))
                } else {
                    val created: SchedAppointment = SchedAPI.send("POST", "/provider-appointments", body = payload)
                    // The visit booked for a follow-up is tied to it: it leaves Due until that visit.
                    var linked: Boolean? = null
                    if (followUpId != null && linksFollowUps) {
                        linked = try {
                            API.patch<EmptyResponse>("/provider-follow-ups/$followUpId", mapOf("providerAppointmentId" to created.id, "status" to "scheduled"))
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            false
                        }
                    }
                    val series = created.series
                    val together = created.together
                    if (series != null) {
                        val skipped = series.skipped ?: emptyList()
                        val count = series.created ?: 0
                        if (skipped.any { (it.reason ?: "").contains("120") }) {
                            Toast.show("${LF("%@ visits booked, %@ skipped", count.toString(), skipped.size.toString())} · ${L("Past the limit of 120 visits")}")
                        } else if (skipped.isNotEmpty()) {
                            Toast.show(LF("%@ visits booked; %@ dates were skipped (closed or taken).", count.toString(), skipped.size.toString()))
                        } else {
                            Toast.show(LF("%@ visits booked.", count.toString()))
                        }
                    } else if (linked == true) {
                        Toast.show(L("Booked. It is off the list until that visit."))
                    } else if (linked == false) {
                        Toast.error(L("The visit is booked, but the follow-up was not linked to it."))
                    } else if (together != null && together.isNotEmpty()) {
                        Toast.show(LF("Booked %@ together.", names ?: created.petName))
                    } else if (created.confirmationStatus == "pending") {
                        Toast.show(L("Appointment created and confirmation sent."))
                    } else if (created.confirmationStatus == "delivery_failed") {
                        Toast.error(L("Appointment created, but the confirmation email failed."))
                    } else {
                        Toast.show(L("Appointment created."))
                    }
                    booked?.invoke(created)
                }
                announceDataChanged()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handle(error)
            } finally {
                working = false
            }
        }
    }

    fun saveWalkIn() {
        val typed = walkInWeight.trim().replace(",", ".")
        val kg = if (typed.isEmpty()) null else typed.toDoubleOrNull()
        if (!form.check(listOf(
                "serviceLabel" to (if (serviceLabel.nonEmpty == null) L("Add the visit or service name first.") else null),
                "weight" to (if (typed.isNotEmpty() && (kg == null || kg <= 0 || kg > 200)) L("Enter the weight in kg, like 12.4") else null),
            ))) return
        // Why they came is asked once: the visit's reason stands in for the triage's when that's left blank.
        val triaged: Pair<Map<String, Any?>?, SchedTriageDraft.Missing?> = if (walkInVetCare) walkInTriage.parsed(fallbackReason = notes) else Pair(null, null)
        val missing = triaged.second
        if (missing != null) {
            triageInvalid = missing
            Toast.error(SchedTriageDraft.missingText(missing))
            return
        }
        val owner = client ?: return
        val petId = petId ?: return
        val body = mutableMapOf<String, Any?>(
            "providerClientId" to owner.id,
            "providerClientPetId" to petId,
            "serviceCategory" to category.nonEmpty,
            "serviceId" to serviceId.nonEmpty,
            "serviceLabel" to serviceLabel.nonEmpty,
            "durationMinutes" to duration,
            "arrivalNote" to notes.nonEmpty,
            "roomId" to roomId,
            "weightGrams" to kg?.let { Math.round(it * 1000).toInt() },
            "clientRequestId" to walkInRequestId,
        )
        triaged.first?.let { body["triage"] = it }
        if (canAssignAnyone) body["assignedOrganizationMemberId"] = assignee.nonEmpty
        workspace.locationId?.let { body["locationId"] = it }
        val base = body.toMap()
        val pet = pets.firstOrNull { it.id == petId }?.petName ?: ""
        suspend fun arrive(start: Boolean, extra: Map<String, Any?>) {
            val payload = base.toMutableMap()
            payload["start"] = start
            for ((key, value) in extra) payload[key] = value
            val result: SchedMoveResponse = SchedAPI.send("POST", "/provider-appointments/walk-in", body = payload)
            announceDataChanged()
            Toast.show(if (result.appointment?.status == "in_progress") LF("%@ is in.", pet) else LF("%@ is waiting.", pet))
        }
        val starts = walkInStart
        working = true
        scope.launch {
            try {
                arrive(starts, emptyMap())
                walkInRequestId = schedRequestId()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A deposit taken here goes with the walk-in: the visit is created checked in, the deposit recorded on it, and it starts.
                val prompt = if (starts) SchedGatePrompt.from(
                    error,
                    petName = pet,
                    verb = "start",
                    attempt = { arrive(true, it) },
                    onDone = {},
                    fallback = { arrive(false, emptyMap()) },
                    clientId = owner.id,
                    clientName = owner.ownerName,
                    takeDeposit = { payment, override ->
                        val extra = override.toMutableMap()
                        extra["deposit"] = payment
                        arrive(true, extra)
                    },
                ) else null
                if (prompt != null) {
                    if (!staysOnGate) dismiss()
                    onGate(prompt)
                } else {
                    handle(error)
                }
            } finally {
                working = false
            }
        }
    }

    fun handle(error: Throwable) {
        val failure = error as? SchedFailure
        if (failure == null) {
            schedToast(error)
            return
        }
        val code = failure.code ?: ""
        if (code == "start_in_past") {
            form.check(listOf("startTime" to (failure.api.field("startsAt") ?: failure.api.message)))
            return
        }
        val problem = failure.hoursProblem
        if (problem != null) {
            serverHours = problem
            hoursConfirmed = false
            Toast.error(SchedHours.problemTitle(problem, personName = personLabel(problem)))
            return
        }
        val conflicts = failure.conflicts
        if (conflicts != null) {
            serverConflicts = conflicts
            doubleBookConfirmed = false
            Toast.error(L("Double booking — read the warning and confirm to book anyway."))
            return
        }
        if (listOf("address_required", "address_not_found", "address_archived").contains(code)) {
            addressError = failure.api.message
            Toast.error(failure.api.message)
            return
        }
        if (listOf("not_grooming", "invalid_groom_request", "empty_request").contains(code) || code.startsWith("groom")) {
            Toast.error(failure.api.message)
            return
        }
        val placed = form.apply(failure.api, known = mapOf(
            "providerClientId" to "client", "providerClientPetId" to "pet", "serviceLabel" to "serviceLabel",
            "startsAt" to "startTime", "endsAt" to "startTime", "assignedOrganizationMemberId" to "assignee", "weightGrams" to "weight",
        ))
        if (placed && (form.errors["client"] != null || form.errors["pet"] != null)) step = 0
    }
}

@Composable
fun SchedBookingSheet(
    draft: SchedBookingDraft,
    /** A walk-in that starts, stopped by a consent or a deposit: shown once this sheet is gone. */
    onGate: (SchedGatePrompt) -> Unit = {},
    /** A new visit booked (the first of a repeating one), before the sheet closes. */
    onBooked: (suspend (SchedAppointment) -> Unit)? = null,
    /** Shown as a page (`BookVisitScreen`): a gate opens over it instead of closing it first. */
    staysOnGate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val model = remember { SchedBookingModel(draft, scope) }
    model.dismiss = LocalDismiss.current
    model.onGate = onGate
    model.onBooked = onBooked
    model.staysOnGate = staysOnGate

    LaunchedEffect(Unit) { model.prepare() }
    LaunchedEffect(model.dayKey, model.step) { model.loadDay() }
    SchedOnChange(model.assignee, model.duration, model.startMinute, model.roomId) { model.resetChecks() }
    SchedOnChange(model.serviceId) { model.applyService() }

    val walkIn = model.walkIn
    val step = model.step
    ProviderModal(
        title = if (walkIn) L("Walk-in") else (if (model.editing != null) L("Edit appointment") else L("Add appointment")),
        subtitle = if (walkIn) L("Here now without an appointment, or an emergency. They go straight to the waiting room.") else L("Choose the client, the pet, the service and the time."),
        systemImage = if (walkIn) "zap" else "calendar-days",
        accent = if (walkIn) ModalAccent.pink else ModalAccent.butter,
        isDirty = model.editing == null && (model.client != null || model.slotTouched || model.roomTouched),
        form = model.form,
        primaryTitle = model.primaryTitle,
        isWorking = model.working,
        steps = ModalSteps(
            steps = listOf(L("Client"), L("Service"), if (walkIn) L("Walk-in") else L("Appointment")),
            icons = listOf("user-round", "clipboard-list", if (walkIn) "zap" else "calendar-days"),
            current = step,
        ),
        onBack = if (step > 0) ({ model.step -= 1 }) else null,
        onPrimary = { model.primary() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        if (step > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val client = model.client
                if (client != null) {
                    Avatar(name = client.ownerName, imageURL = client.photoUrl, size = 30.dp)
                    Text(
                        listOfNotNull(client.ownerName.nonEmpty, model.petNames.nonEmpty).joinToString(" · "),
                        style = Theme.text(14, FontWeight.Bold),
                        color = Theme.inkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when (step) {
            0 -> SchedBookingSelectionStep(model)
            1 -> SchedBookingServiceStep(model)
            else -> SchedBookingDetailsStep(model)
        }
    }
}

// MARK: Step 1: client and pet

@Composable
private fun SchedBookingSelectionStep(model: SchedBookingModel) {
    ModalSection(title = L("Client + pet"), systemImage = "user-round") {
        // The one client-and-pet picker (search from 2 letters, 20 at a time, the chosen one on top).
        ClientPetPicker(
            client = stateOf({ model.client?.let { SchedClientBridge.picked(it) } }, { model.client = it?.let { c -> SchedClientBridge.sched(c) } }),
            petIds = model.petIdsState,
            pets = if (model.canBookTogether) ClientPetPicker.Pets.several else ClientPetPicker.Pets.one,
            petRequired = true,
            key = "client",
            petKey = "pet",
            form = model.form,
            petHint = if (model.canBookTogether) L("Tap more than one to book them together: the same time and person, one bill, a report each.") else null,
        )
    }
}

// MARK: Step 2: service

@Composable
private fun SchedBookingServiceStep(model: SchedBookingModel) {
    val form = model.form
    val workspace = Workspace.shared
    ModalSection(title = L("Service"), systemImage = "paw-print", tint = Theme.butter) {
        val categories = model.categories
        val category = model.category
        if (model.catalog == null) {
            LoadingState(text = L("Loading services…"))
        } else if (category.isEmpty() || !categories.any { it.id == category }) {
            // The web's option cards: each kind of service with its icon and colour.
            FormField(label = L("Service category"), key = "category", form = form, required = true) {
                OptionCards(
                    options = categories.map { OptionCard(it.id, title = it.label, description = it.description, icon = Hue.categoryIcon(it.id), accent = Hue.category(it.id).color) },
                    selection = stateOf<String>({ model.category }, { model.pickCategory(it) }),
                    invalid = form.error("category") != null,
                )
            }
        } else {
            val meta = categories.firstOrNull { it.id == category }
            val options = model.services.filter { it.category == category }
            Column(
                Modifier
                    .fillMaxWidth()
                    .formScrollTarget("service", form),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel(LF("Services · %@", meta?.label ?: ""), required = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    if (categories.size > 1) {
                        PMButton(L("Change"), trailingIcon = "chevron-down", variant = ButtonVariant.link, size = ButtonSize.small) { model.pickCategory("") }
                    }
                }
                val description = meta?.description.nonEmpty
                if (description != null) Text(description, style = Theme.text(14), color = Theme.inkSoft)
                if (options.isEmpty()) {
                    NoticeCard(
                        tone = NoticeCard.Tone.info,
                        title = LF("No %@ services are switched on", meta?.label ?: ""),
                        message = if (workspace.can(Permission.workspaceEdit)) L("Turn services on in Settings › Services, then book.") else L("Ask an owner or admin to turn services on in Settings."),
                    )
                } else {
                    OptionCards(
                        options = options.map { OptionCard(it.id, title = it.label, description = it.description, icon = Hue.categoryIcon(category), accent = Hue.category(category).color) },
                        selection = stateOf<String?>({ model.serviceId.nonEmpty }, { value ->
                            if (value != null) {
                                model.serviceId = value
                                form.clear("service")
                                // A short beat on the chosen card before the next step slides in, as the web's confirm blink.
                                model.scope.launch {
                                    delay(220)
                                    model.step = 2
                                }
                            }
                        }),
                        invalid = form.error("service") != null,
                    )
                }
                val error = form.error("service")
                if (error != null) Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
            }
        }
    }
}

// MARK: Step 3: details

@Composable
private fun SchedBookingDetailsStep(model: SchedBookingModel) {
    val form = model.form
    val walkIn = model.walkIn
    val editing = model.editing
    ModalSection(title = L("Visit details"), systemImage = "calendar-days") {
        PMTextField(
            L("Visit or service"),
            text = stateOf({ model.serviceLabel }, { model.serviceLabel = it; model.labelFromService = false }),
            key = "serviceLabel",
            form = form,
            prompt = model.service?.label ?: L("e.g. Annual checkup"),
            required = true,
        )
        if (!walkIn) {
            DateTimeField(
                label = L("Date"),
                date = stateOf({ model.date }, { model.changeDate(it) }),
                zone = SchedDay.zone,
                min = if (editing == null) SchedDay.start(SchedDay.today) else null,
                components = setOf(DatePickerComponent.date),
                key = "date",
                form = form,
                required = true,
            )
        }
        if (model.canAssignAnyone) {
            MenuField(label = L("Assignee"), options = model.assigneeOptions, selection = model.assigneeState, key = "assignee", form = form, required = true)
        } else {
            val me = model.me
            FactRow(label = L("Assignee"), value = editing?.assignedMember?.name ?: me?.let { LF("%@ (You)", it.label) } ?: L("You"))
            Text(
                if (editing?.assignedMember?.id.let { it != null && it != me?.id }) L("Only admins or scheduling staff can reassign this visit.")
                else L("Your current role books this visit under your own seat."),
                style = Theme.captionFont,
                color = Theme.inkSoft,
            )
        }
        MenuField(
            label = if (walkIn) L("Length") else L("Duration"),
            options = SchedDurations.map { Pair(it, SchedDay.duration(it)) },
            selection = model.durationState,
            required = !walkIn,
        )
    }
    if (walkIn) {
        ModalSection(title = L("Arrival"), systemImage = "zap", tint = Theme.butter) {
            ToggleRow(title = L("Start right away"), subtitle = L("Straight in, without waiting — for an emergency."), isOn = model.walkInStartState)
            PMTextField(L("Weight (kg)"), text = model.walkInWeightState, key = "weight", form = form, prompt = "12.4", keyboard = KeyboardType.Decimal, optional = true)
            if (model.walkInVetCare) {
                SchedTriageFields(
                    draft = stateOf({ model.walkInTriage }, { model.walkInTriage = it; model.triageInvalid = null }),
                    invalid = model.triageInvalid,
                )
            }
        }
    } else {
        SchedBookingTimeSection(model)
    }
    if (model.canBeAtClientHome) {
        ToggleRow(title = L("At the client’s home"), subtitle = L("The visit happens at one of the client’s addresses."), isOn = model.atClientHomeState)
    }
    val client = model.client
    if (!model.homeVisit) {
        MenuField(
            label = L("Room"),
            options = model.roomOptions,
            selection = stateOf({ model.roomId }, { model.roomId = it; model.roomTouched = true }),
            optional = true,
        )
    } else if (model.askAddress && client != null) {
        ModalSection(title = L("At the client’s home"), systemImage = "house") {
            SchedAddressPicker(
                clientId = client.id,
                addressId = stateOf({ model.serviceAddressId }, { model.serviceAddressId = it; model.addressError = null }),
                accessNotes = model.accessNotesState,
                error = model.addressError,
            )
        }
    } else {
        NoticeCard(tone = NoticeCard.Tone.info, title = L("At the client’s home"), message = L("It happens at the client’s default address; change it on the visit if it is another one."))
    }
    val bookedPets = model.bookedPets
    if (model.groomBooking && bookedPets.isNotEmpty()) {
        SchedBookingGroomRequests(pets = bookedPets, values = model.groomRequestsState, familyOnApp = client?.householdId != null)
    }
    val problem = model.shownHoursProblem
    if (problem != null && !model.afterHours) {
        SchedHoursWarning(problem = problem, personName = model.personLabel(problem), confirmed = model.hoursConfirmedState)
    }
    val conflicts = model.shownConflicts
    val together = model.takenTogether
    if (together != null && conflicts.isEmpty()) {
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            title = LF("%@ takes %@ at once", together.first, together.second.toString()),
            message = LF("You are booking %@ together.", schedNames(together.third)),
        )
        ToggleRow(title = LF("Confirm that %@ takes the %@ pets together.", together.first, together.third.size.toString()), isOn = model.togetherConfirmedState)
    }
    SchedConflictWarning(conflicts = conflicts, confirmed = model.doubleBookConfirmedState)
    ModalSection(title = if (walkIn) L("Why they came") else L("Notes"), systemImage = "notebook-pen", tint = Theme.surfaceMuted) {
        PMTextField(
            if (walkIn) L("Why they came") else L("Notes"),
            text = model.notesState,
            prompt = model.categories.firstOrNull { it.id == model.category }?.reasonPlaceholder,
            hint = if (walkIn) null else L("Only your team sees these notes. To tell the family something, send them a note from the visit."),
            multiline = true,
        )
    }
    // Last: a repeat is decided once the first visit is set. Only walks, sitting, classes and daycare repeat.
    if (editing == null && !walkIn && model.bookingRepeats) {
        ModalSection(title = L("Does this visit repeat?"), systemImage = "repeat", tint = Theme.surfaceMuted) {
            FormField(label = "", key = "repeat", form = form) {
                SchedRepeatFields(draft = model.repeatDraftState, firstDay = model.dayKey, category = model.category, invalid = form.error("repeat") != null)
            }
        }
    }
}

// MARK: The time

@Composable
private fun SchedBookingTimeSection(model: SchedBookingModel) {
    val form = model.form
    ModalSection(title = L("Schedule and confirmation"), systemImage = "clock-3", tint = Theme.butter) {
        val dayKey = model.dayKey
        val hours = model.dayHours
        val open = model.openRanges
        val closure = hours.second
        if (closure != null && !closure.second && open.isNotEmpty()) {
            Text(
                "${closure.first?.let { LF("Special hours · %@", it) } ?: L("Special hours")} · ${SchedHours.rangesText(hours.first)}",
                style = Theme.smallFont,
                color = Theme.inkSoft,
            )
        } else if (open.isNotEmpty()) {
            Text(LF("Hours this day: %@", SchedHours.rangesText(hours.first)), style = Theme.smallFont, color = Theme.inkSoft)
        }
        val starts = model.openStarts
        val dayClosed = model.dayClosed
        if ((dayClosed || (starts.isEmpty() && !model.loadingDay && model.assignee.isNotEmpty())) && !model.afterHours) {
            val next = SchedHours.nextOpenDay(after = dayKey, closures = model.closures, availability = model.availability)
            val title = if (!dayClosed) {
                if (open.all { SchedDay.at(dayKey, minute = minOf(it.end, 1439)) <= Instant.now() }) L("Opening hours have ended for this day.") else L("No remaining start times fit this visit.")
            } else {
                closure?.first?.let { LF("The clinic is closed on %@ · %@", SchedDay.shortLabel(dayKey), it) } ?: LF("The clinic is closed on %@", SchedDay.shortLabel(dayKey))
            }
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = title,
                message = if (next == null) L("No open day in the next months.") else null,
                actionTitle = next?.let { LF("Go to %@", SchedDay.shortLabel(it)) },
                action = next?.let { day -> { model.changeDate(SchedDay.date(fromKey = day)) } },
            )
        }
        val startBinding = stateOf({ model.startMinute }, { model.setStart(it) })
        val busy = model.busyStarts
        val off = model.offStarts
        if (model.loadingDay && model.load == null) {
            SchedProgressView()
        } else if (model.afterHours) {
            MenuField(
                label = L("Start time"),
                options = model.afterHoursStarts.map { Pair<Int?, String>(it, model.slotLabel(it, inHours = SchedHours.fits(it, it + model.duration, open), busy = busy, off = off)) },
                selection = startBinding,
                key = "startTime",
                form = form,
            )
        } else if (starts.isNotEmpty()) {
            MenuField(
                label = L("Available start time"),
                options = starts.map { Pair<Int?, String>(it, model.slotLabel(it, inHours = true, busy = busy, off = off)) },
                selection = startBinding,
                key = "startTime",
                form = form,
            )
            Text(model.summaryText, style = Theme.captionFont, color = if (busy.size == starts.size) Theme.coralText else Theme.inkSoft)
        }
        val start = model.startMinute
        if (start != null) {
            Text(LF("Ends at %@", SchedDay.timeLabel(minOf(start + model.duration, 1439))), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        }
        ToggleRow(title = L("Outside opening hours"), subtitle = L("Any time, for an emergency or a special case. It is noted on the visit."), isOn = model.afterHoursState)
        if (model.editing == null) {
            val hasEmail = model.clientHasEmail
            Disabled(!hasEmail) {
                ToggleRow(
                    title = L("Request client confirmation"),
                    subtitle = if (hasEmail) L("Send an email and app notification after this appointment is created.") else L("Add a client email before sending confirmation requests."),
                    isOn = stateOf({ model.requestConfirmation && model.clientHasEmail }, { model.requestConfirmation = it }),
                )
            }
        }
    }
}

/** The booking's client type and the shared picker's, the same fields both ways. */
object SchedClientBridge {
    fun picked(c: SchedClient): PickedClient = PickedClient(
        id = c.id, ownerName = c.ownerName, ownerEmail = c.ownerEmail, ownerPhone = c.ownerPhone, householdId = c.householdId,
        photoUrl = c.photoUrl, status = c.status,
        pets = (c.pets ?: emptyList()).map {
            PickedPet(id = it.id, petName = it.petName, petSpecies = it.petSpecies, petBreed = it.petBreed, photoUrl = it.photoUrl, status = it.status, microchipNumber = it.microchipNumber, weight = it.weight)
        },
    )

    fun sched(c: PickedClient): SchedClient = SchedClient(
        id = c.id, householdId = c.householdId, ownerName = c.name, ownerEmail = c.ownerEmail, ownerPhone = c.ownerPhone, photoUrl = c.photoUrl, status = c.status,
        pets = (c.pets ?: emptyList()).map {
            SchedClientPet(id = it.id, petName = it.name, petSpecies = it.petSpecies, petBreed = it.petBreed, photoUrl = it.photoUrl, status = it.status, microchipNumber = it.microchipNumber, weight = it.weight)
        },
    )
}
