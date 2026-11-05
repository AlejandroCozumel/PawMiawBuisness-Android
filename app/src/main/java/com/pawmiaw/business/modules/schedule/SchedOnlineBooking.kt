package com.pawmiaw.business.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

//
//  SchedOnlineBooking.kt (iOS SchedOnlineBooking.swift)
//
//  A booking a family made online that waits for the business
//  (paw-miaw-backend docs/online-booking.md §2, §9, §12.5; web
//  online-booking/online-request.tsx): its facts and the three answers —
//  Confirmar, Cambiar hora y confirmar (a free time by the staff booking
//  rule, from the schedule's load) and Rechazar with a reason the family
//  reads. Pets booked together are answered together; the server acts on
//  all of them and tells the family.
//
//  A family's ask to move a confirmed online booking ("Cambio de hora", §13)
//  is answered in the same places with two of those answers: Aceptar (the
//  visit moves to the time asked for) and Rechazar with a reason (it keeps
//  its time).
//

/** What the answers need of a request, from the inbox's item or from the visit itself. */
data class SchedAnswerTarget(
    /** A family's ask to move a confirmed visit, not a booking to confirm. */
    val reschedule: Boolean,
    /** The time asked for (an ask); the visit's own time stays in `startsAt`/`endsAt`. */
    val proposal: SchedRescheduleProposal?,
    val appointmentId: String,
    val startsAt: String,
    val endsAt: String?,
    val memberId: String?,
    val memberName: String?,
    val roomId: String?,
    val room: String?,
    val locationId: String?,
    val serviceLabel: String,
    val atHome: Boolean,
    val notes: String?,
    val releasesAt: String?,
    val clientName: String,
    val clientPhone: String?,
    val pets: List<SchedRequestPet>,
) {
    constructor(request: SchedOnlineRequest) : this(
        reschedule = request.isReschedule,
        proposal = if (request.isReschedule) request.rescheduleProposal else null,
        appointmentId = request.appointmentId,
        startsAt = request.startsAt,
        endsAt = request.endsAt,
        memberId = request.assigned?.id,
        memberName = request.assigned?.name,
        roomId = null,
        room = request.room,
        locationId = request.locationId,
        serviceLabel = request.serviceLabel,
        atHome = request.atHome ?: false,
        notes = request.notes,
        releasesAt = request.releasesAt,
        clientName = request.client.name,
        clientPhone = request.client.phone,
        pets = request.pets,
    )

    constructor(appointment: SchedAppointment) : this(
        reschedule = !appointment.isRequested && appointment.rescheduleProposal != null,
        proposal = if (!appointment.isRequested && appointment.rescheduleProposal != null) appointment.rescheduleProposal else null,
        appointmentId = appointment.id,
        startsAt = appointment.startsAt,
        endsAt = appointment.endsAt,
        memberId = appointment.assignedMember?.id,
        memberName = appointment.assignedMember?.name,
        roomId = appointment.roomId,
        room = appointment.room,
        locationId = appointment.locationId,
        serviceLabel = appointment.serviceLabel,
        atHome = appointment.atHome ?: false,
        notes = appointment.notes,
        releasesAt = if (!appointment.isRequested && appointment.rescheduleProposal != null) appointment.rescheduleProposal.releasesAt else appointment.onlineRequest?.releasesAt,
        clientName = appointment.clientName,
        clientPhone = appointment.clientPhone,
        pets = togetherPets(appointment),
    )

    val lengthMinutes: Int
        get() {
            val start = DateText.parse(startsAt) ?: return 30
            val end = DateText.parse(endsAt) ?: return 30
            if (end <= start) return 30
            return maxOf(15, ((end.toEpochMilli() - start.toEpochMilli()) / 60_000).toInt())
        }

    companion object {
        /**
         * A family's pets booked together are answered together: the others still waiting with it
         * (a request's are still requested; an ask's are still booked, and move with it).
         */
        private fun togetherPets(a: SchedAppointment): List<SchedRequestPet> {
            val together = if (a.group?.kind == "household") (a.group?.pets ?: emptyList()).filter { it.status == a.status } else emptyList()
            return if (together.isEmpty()) {
                listOf(SchedRequestPet(appointmentId = a.id, providerClientPetId = a.providerClientPetId, name = a.petName, species = a.petSpecies, photoUrl = a.petPhotoUrl))
            } else {
                together.map { SchedRequestPet(appointmentId = it.appointmentId, providerClientPetId = it.providerClientPetId, name = it.name ?: "", species = it.species, photoUrl = it.photoUrl) }
            }
        }
    }
}

object SchedOnlineText {
    /** "Miércoles 4 de noviembre · 10:00–10:30". */
    fun `when`(startsAt: String, endsAt: String?): String {
        val start = DateText.parse(startsAt)
        val end = DateText.parse(endsAt)?.let { "–" + DateText.time(it) } ?: ""
        return "${SchedDay.upperFirst(DateText.longDay(start))} · ${DateText.time(start)}$end"
    }

    /** "en 3 horas", "en 40 min", "ahora". */
    fun releaseIn(releasesAt: String?): String? {
        val date = DateText.parse(releasesAt) ?: return null
        val minutes = ((date.toEpochMilli() - Instant.now().toEpochMilli()) / 60_000.0).roundToInt()
        if (minutes <= 1) return L("now")
        if (minutes < 60) return LF("in %@ min", minutes.toString())
        val hours = (minutes / 60.0).roundToInt()
        return if (hours == 1) L("in 1 hour") else LF("in %@ hours", hours.toString())
    }

    /** "Hoy, 10:00 a.m. → Mañana, 11:30 a.m."; the day once when both are on it ("Hoy, 10:00 a.m. → 11:30 a.m."). */
    fun move(from: String, to: String?): String {
        val sameDay = SchedDay.key(from) != null && SchedDay.key(from) == SchedDay.key(to)
        return "${DateText.dayTime(from)} → ${if (sameDay) DateText.time(to) else DateText.dayTime(to)}"
    }

    /** "Cambio de hora: Hoy, 10:00 a.m. → Mañana, 11:30 a.m." (the visit's time now → the time asked for). */
    fun change(from: String, to: String?): String = LF("Change of time: %@", move(from, to))

    /**
     * The inbox's title: bookings to confirm, changes of time to answer, or both
     * (`asks`: the changes of time among the items read).
     */
    fun inboxTitle(count: Int, asks: Int): String {
        if (asks == 0) return if (count == 1) L("1 online booking to confirm") else LF("%@ online bookings to confirm", count.toString())
        if (asks == count) return LP(count, "%d change of time to answer", "%d changes of time to answer")
        return LF("%d online bookings and changes of time to answer", count)
    }
}

/** SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)])`: as many equal columns as fit. */
@Composable
private fun <T> SchedAdaptiveGrid(
    items: List<T>,
    minimum: Dp,
    spacing: Dp,
    cellAlignment: Alignment = Alignment.TopStart,
    cell: @Composable (T) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val count = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        SchedFixedGrid(items, count, spacing, cellAlignment, cell)
    }
}

/** A grid of `count` equal columns (SwiftUI `GridItem(.flexible())` repeated). */
@Composable
private fun <T> SchedFixedGrid(
    items: List<T>,
    count: Int,
    spacing: Dp,
    cellAlignment: Alignment = Alignment.TopStart,
    cell: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(count).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                line.forEach { item -> Box(Modifier.weight(1f), contentAlignment = cellAlignment) { cell(item) } }
                repeat(count - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// MARK: - Facts

/**
 * The request as the family sent it: who, which pets, what, when, where, and their note
 * (web `OnlineRequestFacts`: a sheet section of pet chips, fact boxes, the note, the hold).
 */
@Composable
fun SchedRequestFacts(target: SchedAnswerTarget, modifier: Modifier = Modifier) {
    val regular = LocalSizeClass.current == SizeClass.regular
    val open = rememberOpenURL()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (target.reschedule) L("What they ask") else L("What they booked"), style = Theme.heading(16.8), color = Theme.inkStrong)
        FlowLayout(spacing = 8.dp) {
            target.pets.forEach { pet ->
                val capsule = RoundedCornerShape(50)
                Row(
                    Modifier
                        .border(1.dp, Theme.borderSoft, capsule)
                        .padding(vertical = 4.dp)
                        .padding(start = 4.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PetAvatar(name = pet.name, species = pet.species, photoURL = pet.photoUrl, size = 32.dp)
                    Text(pet.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val boxes = buildList<@Composable () -> Unit> {
            if (target.reschedule) {
                // An ask: the visit's time now, then the time asked for (and who would see them then).
                add {
                    SchedFactBox(icon = "calendar-clock", label = L("Now")) {
                        Text(SchedOnlineText.`when`(target.startsAt, target.endsAt))
                        Text(target.serviceLabel, style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                add {
                    SchedFactBox(icon = "calendar-check", label = L("Asks for")) {
                        Text(target.proposal?.let { SchedOnlineText.`when`(it.startsAt, it.endsAt) } ?: "—")
                    }
                }
            } else {
                add {
                    SchedFactBox(icon = "calendar-clock", label = L("When")) {
                        Text(SchedOnlineText.`when`(target.startsAt, target.endsAt))
                        Text(target.serviceLabel, style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
            }
            add {
                SchedFactBox(icon = "phone", label = L("Family")) {
                    Text(target.clientName)
                    val phone = target.clientPhone
                    val url = schedPhoneURL(phone)
                    if (phone != null && url != null) {
                        Text(phone, style = Theme.text(12), color = Theme.blue, modifier = Modifier.plainButton(label = phone) { open(url) })
                    }
                }
            }
            add {
                SchedFactBox(icon = "user-round", label = L("With")) {
                    val who = if (target.reschedule) target.proposal?.assigned?.name else target.memberName
                    val room = if (target.reschedule) target.proposal?.room else target.room
                    Text(who ?: L("Whoever is free"))
                    if (room != null) Text(room, style = Theme.text(12), color = Theme.inkSoft)
                }
            }
            if (target.atHome) {
                add { SchedFactBox(icon = "house", label = L("Where")) { Text(L("At the family’s home")) } }
            }
        }
        SchedFixedGrid(boxes, if (regular) 2 else 1, 12.dp) { it() }
        val notes = target.notes.nonEmpty
        if (notes != null) {
            Text(
                "“$notes”",
                style = Theme.text(14),
                color = Theme.inkStrong,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        val release = SchedOnlineText.releaseIn(target.releasesAt)
        if (release != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Glyph("clock-3", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                Text(
                    if (target.reschedule) LF("The new time is held for them and the visit stays where it is. With no answer the ask expires %@, and the family is told.", release)
                    else LF("The time is held for them. With no answer it is released %@, and the family is told.", release),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
        }
    }
}

/** One fact of a request (web `Fact`): a bordered box, the icon, an uppercase label, the value. */
@Composable
fun SchedFactBox(icon: String, label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, Theme.borderSoft, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph(icon, 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(appLocale), style = Theme.text(12, FontWeight.Bold).tracking(1.44), color = Theme.inkSoft)
            CompositionLocalProvider(LocalTextStyle provides Theme.text(14, FontWeight.SemiBold), LocalContentColor provides Theme.inkStrong) {
                Column(content = content)
            }
        }
    }
}

// MARK: - The three answers

private enum class SchedAnswerMode { confirm, change, decline }

@Composable
fun SchedRequestAnswer(target: SchedAnswerTarget, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val workspace = Workspace.shared
    val form = remember { FormState() }
    val mode = remember { mutableStateOf(SchedAnswerMode.confirm) }
    val startsAt = remember { mutableStateOf<String?>(null) }
    val memberId = remember { mutableStateOf<String?>(null) }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var pickerVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val quickReasons = if (target.reschedule) {
        listOf(L("We are full at that time."), L("Nobody who does it is free then."), L("Call us and we’ll find another time."))
    } else {
        listOf(L("We are full at that time."), L("That service needs a call first."), L("We can’t see this pet for that."))
    }

    /** A request's three answers; an ask's two (Aceptar, Rechazar). */
    val options = if (target.reschedule) {
        listOf(Segmented.Option(SchedAnswerMode.confirm, L("Accept"), icon = "check"), Segmented.Option(SchedAnswerMode.decline, L("Decline"), icon = "circle-x"))
    } else {
        listOf(
            Segmented.Option(SchedAnswerMode.confirm, L("Confirm"), icon = "check"),
            Segmented.Option(SchedAnswerMode.change, L("Other time"), icon = "calendar-clock"),
            Segmented.Option(SchedAnswerMode.decline, L("Decline"), icon = "circle-x"),
        )
    }

    val told = if (target.pets.size > 1) " " + L("Every pet booked with it is answered too.") else ""

    fun run(success: String, action: suspend () -> Unit) {
        working = true
        scope.launch {
            try {
                action()
                announceDataChanged()
                Toast.show(success)
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (mode.value == SchedAnswerMode.change && (error as? SchedFailure)?.conflicts != null) {
                    startsAt.value = null
                    pickerVersion += 1
                    form.check(listOf("startsAt" to L("That time was just taken. Pick another free time.")))
                    return@launch
                }
                val field = (schedAPIError(error) as? APIError)?.field("startsAt")
                if (mode.value == SchedAnswerMode.change && field != null) {
                    form.check(listOf("startsAt" to field))
                    return@launch
                }
                schedToast(error)
                // Nothing waits any more (answered, released or withdrawn meanwhile): the list drops it.
                if ((error as? SchedFailure)?.code == "not_a_reschedule") {
                    announceDataChanged()
                    onDone()
                }
            } finally {
                working = false
            }
        }
    }

    /** One of the three answers (each answers `{ appointmentId, status, startsAt }`). */
    suspend fun answer(path: String, body: Map<String, Any?>) {
        SchedAPI.send<SchedOnlineAnswer>("POST", path, body = body)
    }

    fun confirm() {
        val id = target.appointmentId
        run(L("Confirmed. The family has been told.")) { answer("/provider-appointments/$id/online/confirm", emptyMap()) }
    }

    fun change() {
        val start = startsAt.value?.let { DateText.parse(it) }
        if (start == null) {
            form.check(listOf("startsAt" to L("Pick the new time.")))
            return
        }
        val end = start.plusSeconds(target.lengthMinutes * 60L)
        val body = mutableMapOf<String, Any?>("startsAt" to DateText.iso(start), "endsAt" to DateText.iso(end))
        if (memberId.value != target.memberId) body["assignedOrganizationMemberId"] = memberId.value
        val id = target.appointmentId
        run(LF("Moved to %@ and confirmed. The family has been told.", SchedOnlineText.`when`(DateText.iso(start), DateText.iso(end)))) {
            answer("/provider-appointments/$id/online/change-and-confirm", body)
        }
    }

    fun decline() {
        val id = target.appointmentId
        val why = reason.value.nonEmpty
        run(L("Declined. The family has been told.")) { answer("/provider-appointments/$id/online/decline", mapOf("reason" to why)) }
    }

    /** An ask's Aceptar: the visit (and every pet booked with it) moves to the time asked for. */
    fun accept() {
        val id = target.appointmentId
        val asked = target.proposal?.let { SchedOnlineText.`when`(it.startsAt, it.endsAt) }
        run(asked?.let { LF("Moved to %@. The family has been told.", it) } ?: L("Change accepted. The family has been told.")) {
            answer("/provider-appointments/$id/online/accept-reschedule", emptyMap())
        }
    }

    /** An ask's Rechazar, with the reason the family reads: the visit keeps its time. */
    fun declineChange() {
        val id = target.appointmentId
        val why = reason.value.nonEmpty
        run(L("Change declined. The visit keeps its time and the family has been told.")) {
            answer("/provider-appointments/$id/online/decline-reschedule", mapOf("reason" to why))
        }
    }

    if (!workspace.can(Permission.appointmentsCreate)) {
        Text(L("Someone who books visits can answer it."), style = Theme.smallFont, color = Theme.inkSoft, modifier = modifier)
        return
    }
    LaunchedEffect(Unit) { if (memberId.value == null) memberId.value = target.memberId }

    // The web's answer panel: the answers as one switch, on a pale blue card.
    val shape = RoundedCornerShape(Theme.cardRadius)
    FormScroll(form) {
        Column(
            modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.blue, 5.0), shape)
                .border(1.dp, Theme.borderSoft, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Segmented(options = options, selection = mode, label = L("Answer"))
            when {
                mode.value == SchedAnswerMode.confirm && target.reschedule -> {
                    val asked = target.proposal?.let { SchedOnlineText.`when`(it.startsAt, it.endsAt) } ?: "—"
                    Text(LF("The visit moves to %@, and the family is told.", asked) + told, style = Theme.text(14), color = Theme.inkSoft)
                    PMButton(L("Accept"), systemImage = "check", fullWidth = true, isWorking = working) { accept() }
                }
                mode.value == SchedAnswerMode.confirm -> {
                    Text(L("It becomes a visit in the Schedule at the time they chose, and the family is told it is confirmed.") + told, style = Theme.text(14), color = Theme.inkSoft)
                    PMButton(L("Confirm"), systemImage = "check", fullWidth = true, isWorking = working) { confirm() }
                }
                mode.value == SchedAnswerMode.change -> {
                    Text(L("Pick a free time: the family is told the new time and may cancel it whenever they like.") + told, style = Theme.text(14), color = Theme.inkSoft)
                    key(pickerVersion) {
                        SchedFreeTimePicker(target = target, memberId = memberId, canChooseMember = workspace.can(Permission.appointmentsAssignAnyone), value = startsAt, form = form)
                    }
                    PMButton(L("Change time and confirm"), systemImage = "calendar-clock", fullWidth = true, isWorking = working) { change() }
                }
                else -> {
                    FlowLayout(spacing = 8.dp) {
                        quickReasons.forEach { quick ->
                            FilterChip(title = quick, isSelected = reason.value == quick) { reason.value = quick }
                        }
                    }
                    PMTextField(L("Why (optional, the family reads it)"), text = reason, key = "reason", form = form, multiline = true)
                    Text(
                        if (target.reschedule) L("The visit keeps its time, the time they asked for is freed, and the family is told.")
                        else L("The time is freed and the family is told, with a way to book another time."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                    PMButton(L("Decline"), systemImage = "circle-x", variant = ButtonVariant.destructive, fullWidth = true, isWorking = working) {
                        if (target.reschedule) declineChange() else decline()
                    }
                }
            }
        }
    }
}

// MARK: - Free times

/**
 * The free times of one day for the request, by the rule staff booking uses: opening
 * hours and closures, the person's own hours and days off, pets at once, the room,
 * the buffer. The request's own time (and its pets) never compete with it.
 */
@Composable
fun SchedFreeTimePicker(
    target: SchedAnswerTarget,
    memberId: MutableState<String?>,
    canChooseMember: Boolean,
    value: MutableState<String?>,
    form: FormState,
    modifier: Modifier = Modifier,
) {
    val today = SchedDay.today
    var day by remember { mutableStateOf(SchedDay.today) }
    var load by remember { mutableStateOf<SchedLoad?>(null) }
    var loading by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<SchedMember>>(emptyList()) }
    var prepared by remember { mutableStateOf(false) }

    LaunchedEffect(day) {
        loading = true
        try {
            load = schedTry { SchedData.load(day = day, locationId = target.locationId ?: Workspace.shared.locationId) }
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        if (prepared) return@LaunchedEffect
        prepared = true
        val asked = SchedDay.key(target.startsAt)
        if (asked != null && asked > today) day = asked
        if (canChooseMember) {
            val everyone = SchedData.members()
            people = everyone.filter { it.takesVisits && it.worksAt(target.locationId) }
        }
    }

    val invalid = form.error("startsAt") != null
    val shape = RoundedCornerShape(Theme.boxRadius)
    FormField(label = L("Free times"), key = "startsAt", form = form, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.surface, shape)
                .border(1.dp, if (invalid) Theme.coral else Theme.borderSoft, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                IconCircleButton(
                    systemImage = "chevron-left",
                    label = L("Previous day"),
                    tint = Theme.blue,
                    enabled = day > today,
                    modifier = Modifier.alpha(if (day <= today) 0.4f else 1f).padding(bottom = 6.dp),
                ) {
                    val previous = SchedDay.addDays(day, -1)
                    if (previous >= today) day = previous
                }
                DayField(
                    label = L("Day"),
                    day = stateOf({ day }, { day = maxOf(today, it) }),
                    min = today,
                    modifier = Modifier.weight(1f),
                )
                IconCircleButton(systemImage = "chevron-right", label = L("Next day"), tint = Theme.blue, modifier = Modifier.padding(bottom = 6.dp)) {
                    day = SchedDay.addDays(day, 1)
                }
            }
            if (canChooseMember && people.isNotEmpty()) {
                MenuField(
                    label = L("With"),
                    options = listOf<Pair<String?, String>>(Pair(null, L("Whoever is free"))) + people.map { Pair(it.id, it.label) },
                    selection = stateOf({ memberId.value }, { memberId.value = it; value.value = null }),
                )
            }
            Text(
                LF("Free times · %@", DateText.longDay(SchedDay.date(fromKey = day))).uppercase(appLocale),
                style = Theme.text(12, FontWeight.Bold).tracking(1.44),
                color = Theme.inkSoft,
            )
            val current = load
            if (loading && current == null) {
                SchedProgressView(Modifier.padding(vertical = 12.dp))
            } else if (current != null) {
                val result = schedFreeSlots(current, day, today, target, memberId.value)
                if (result.list.isEmpty()) {
                    Text(
                        if (result.closed) (result.closureLabel?.let { LF("Closed that day: %@.", it) } ?: L("Closed that day."))
                        else if (result.off) L("That person is off that day.") else L("No free time left that day. Try the next one."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Theme.surfaceMuted, shape)
                            .padding(12.dp),
                    )
                } else {
                    SchedAdaptiveGrid(result.list, minimum = 96.dp, spacing = 8.dp) { slot ->
                        FilterChip(title = SchedDay.timeLabel(slot.first), isSelected = value.value == slot.second) {
                            value.value = slot.second
                            form.clear("startsAt")
                        }
                    }
                }
            }
        }
    }
}

private class SchedFreeSlots(val closed: Boolean, val closureLabel: String?, val off: Boolean, val list: List<Pair<Int, String>>)

private fun schedFreeSlots(load: SchedLoad, day: String, today: String, target: SchedAnswerTarget, memberId: String?): SchedFreeSlots {
    val dayHours = load.hoursDay(day)
    val open = SchedHours.minutes(dayHours?.ranges ?: emptyList())
    val person = memberId?.let { id -> load.people?.firstOrNull { it.id == id } }
    val theirDay = person?.day(day)
    val ranges = theirDay?.let { if (it.timeOff != null) emptyList() else SchedHours.minutes(it.ranges ?: emptyList()) } ?: open
    val nowMinute = if (day == today) SchedDay.minuteOfDay(Instant.now()) else -1
    val length = maxOf(15, target.lengthMinutes)
    val ignore = target.pets.map { it.appointmentId }
    val list = mutableListOf<Pair<Int, String>>()
    for (span in ranges) {
        var minute = (ceil(span.start / 15.0) * 15).toInt()
        while (minute + length <= span.end) {
            val current = minute
            minute += 15
            if (current <= nowMinute) continue
            if (!SchedHours.fits(current, current + length, open)) continue
            val start = SchedDay.at(day, minute = current)
            val slot = SchedSlot(id = target.appointmentId, start = start, end = start.plusSeconds(length * 60L), memberId = memberId, roomId = target.roomId, pets = ignore.size, ignoreIds = ignore)
            if (SchedConflicts.find(slot, load).isEmpty()) list.add(Pair(current, DateText.iso(start)))
        }
    }
    return SchedFreeSlots(closed = open.isEmpty(), closureLabel = dayHours?.closure?.label, off = theirDay?.timeOff != null, list = list)
}

// MARK: - The inbox

/**
 * A request in the inbox (web `OnlineRequestCard`): a dashed waiting card with the pets,
 * what and when, who booked, and when it is released. An ask to move a visit reads
 * "Cambio de hora: {now} → {asked for}" and when the ask expires.
 */
@Composable
fun SchedRequestCard(request: SchedOnlineRequest, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val releases = DateText.parse(request.releasesAt)
    val soon = releases != null && releases.toEpochMilli() - Instant.now().toEpochMilli() < 2 * 3600 * 1000L
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .cardShadow(shape)
            .background(Theme.surface, shape)
            .dashedBorder(2.dp, Theme.mix(ProviderState.waiting.color, 70.0, Theme.borderSoft), cornerRadius = Theme.cardRadius, on = 6.dp, off = 4.dp)
            .plainButton { onOpen() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PetStack(pets = request.pets.map { Triple(it.name, it.species, it.photoUrl) }, size = 40.dp, max = 3)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(request.pets.joinToString(", ") { it.name }, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${request.client.name} · ${request.serviceLabel}", style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            if (request.isReschedule) SchedRescheduleMark() else SchedPendingMark()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Glyph("calendar-clock", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp))
            if (request.isReschedule) {
                Text(SchedOnlineText.change(request.startsAt, request.rescheduleProposal?.startsAt), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            } else {
                Text(SchedOnlineText.`when`(request.startsAt, request.endsAt), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        CompositionLocalProvider(LocalTextStyle provides Theme.text(12), LocalContentColor provides Theme.inkSoft) {
            FlowLayout(spacing = 12.dp, lineSpacing = 4.dp) {
                val who = if (request.isReschedule) request.rescheduleProposal?.assigned?.name else request.assigned?.name
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("user-round", 12.dp)
                    Text(who ?: L("Whoever is free"))
                }
                if (request.atHome == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("map-pin", 12.dp)
                        Text(L("At the family’s home"))
                    }
                }
                val release = SchedOnlineText.releaseIn(request.releasesAt)
                if (release != null) {
                    val ink = if (soon) Theme.destructive else Theme.inkSoft
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("clock-3", 12.dp, tint = ink)
                        Text(
                            if (request.isReschedule) LF("Expires %@@ask", release) else LF("Released %@", release),
                            style = Theme.text(12, if (soon) FontWeight.Bold else FontWeight.Normal),
                            color = ink,
                        )
                    }
                }
            }
        }
        val notes = request.notes.nonEmpty
        if (notes != null) {
            Text("“$notes”", style = Theme.text(14), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The visit page of a requested visit (web `OnlineRequestPanel`): what the family booked
 * and the three answers, on a dashed card. No check-in, start, note or bill until it is confirmed.
 */
@Composable
fun SchedRequestPanel(target: SchedAnswerTarget, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .dashedBorder(2.dp, Theme.mix(ProviderState.waiting.color, 70.0, Theme.borderSoft), cornerRadius = Theme.cardRadius, on = 6.dp, off = 4.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (target.reschedule) SchedRescheduleMark() else SchedPendingMark()
                SchedOnlineMark()
            }
            if (target.reschedule) {
                Text(L("The family asks to move this visit"), style = Theme.heading(20), color = Theme.inkStrong)
                Text(SchedOnlineText.change(target.startsAt, target.proposal?.startsAt), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                Text(L("It stays at its time until you answer. Accept and it moves; decline and it stays."), style = Theme.text(14), color = Theme.inkSoft)
            } else {
                Text(L("A family booked this online"), style = Theme.heading(20), color = Theme.inkStrong)
                Text(L("It holds its time but isn’t a visit yet: no check-in, start or bill until you confirm it."), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
        SchedRequestFacts(target = target)
        SchedRequestAnswer(target = target)
    }
}

/** One request in a sheet: its facts and the three answers. */
@Composable
fun SchedRequestSheet(
    request: SchedOnlineRequest,
    /** Opens the visit page (the sheet has no navigation of its own). */
    onOpenVisit: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val target = SchedAnswerTarget(request = request)
    val whenText = if (target.reschedule) SchedOnlineText.change(target.startsAt, target.proposal?.startsAt) else SchedOnlineText.`when`(target.startsAt, target.endsAt)
    ProviderModal(
        title = "${target.serviceLabel} · ${target.pets.joinToString(", ") { it.name }}",
        subtitle = "${target.clientName} · $whenText",
        systemImage = "globe",
        accent = ModalAccent.aqua,
        cancelTitle = L("Close"),
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (target.reschedule) SchedRescheduleMark() else SchedPendingMark()
            SchedOnlineMark()
        }
        SchedRequestFacts(target = target)
        if (onOpenVisit != null) {
            PMButton(L("Open the visit"), systemImage = "square-arrow-out-up-right", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                dismiss()
                onOpenVisit(target.appointmentId)
            }
        }
        SchedRequestAnswer(target = target) { dismiss() }
    }
}

/**
 * Families' online bookings waiting for an answer, and their asks to move one
 * (the server's count of both: pets booked together once).
 */
@Composable
fun SchedRequestsInbox(list: SchedOnlineRequestList, modifier: Modifier = Modifier, onOpen: (SchedOnlineRequest) -> Unit) {
    val count = list.count ?: 0
    if (count <= 0) return
    PageSection(
        SchedOnlineText.inboxTitle(count = count, asks = (list.items ?: emptyList()).count { it.isReschedule }),
        subtitle = L("Booked online, waiting for you"),
        modifier = modifier,
    ) {
        SchedAdaptiveGrid(list.items ?: emptyList(), minimum = 300.dp, spacing = 10.dp) { request ->
            SchedRequestCard(request = request) { onOpen(request) }
        }
    }
}
