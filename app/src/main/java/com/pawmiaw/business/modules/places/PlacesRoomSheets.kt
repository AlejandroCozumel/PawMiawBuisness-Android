package com.pawmiaw.business.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

//
//  PlacesRoomSheets.kt (iOS PlacesRoomSheets.swift)
//
//  The Rooms page's sheets (web components/app/rooms/room-sheet.tsx,
//  room-out-sheet.tsx, room-archive-sheet.tsx): add or change a room (a new one
//  is two steps, its kind then its details, and may add several numbered at
//  once), take a visit room out of service for some days, and archive or delete.
//

/** The sheet's colour: the kind's own hue, as on the web (`ROOM_KIND_HUE`). */
private val PlacesRoomKind.accent: ModalAccent
    get() = when (this) {
        PlacesRoomKind.consult -> ModalAccent.hue(Hue.sky)
        PlacesRoomKind.surgery -> ModalAccent.peach
        PlacesRoomKind.treatment -> ModalAccent.hue(Hue.butter)
        PlacesRoomKind.ward, PlacesRoomKind.cage -> ModalAccent.hue(Hue.periwinkle)
        PlacesRoomKind.grooming -> ModalAccent.hue(Hue.lavender)
        PlacesRoomKind.bath -> ModalAccent.hue(Hue.teal)
        PlacesRoomKind.other -> ModalAccent.hue(Hue.grey)
    }

// MARK: - Add or change a room

private enum class PlacesRoomStep { kind, details }

private data class PlacesRoomSnapshot(
    val kind: PlacesRoomKind?,
    val name: String,
    val count: Int,
    val capacity: Int,
    val serviceIds: List<String>,
    val notes: String,
)

@Composable
fun PlacesRoomSheetView(
    room: PlacesRoom?,
    template: PlacesRoom?,
    templateName: String?,
    rooms: List<PlacesRoom>,
    services: List<PlacesService>,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val initial = remember {
        val from = room ?: template
        PlacesRoomSnapshot(
            kind = from?.kindEntry,
            name = room?.name ?: templateName ?: "",
            count = 1,
            capacity = from?.places ?: 1,
            serviceIds = from?.services ?: emptyList(),
            notes = from?.notes ?: "",
        )
    }
    var step by remember { mutableStateOf(if (room != null || template != null) PlacesRoomStep.details else PlacesRoomStep.kind) }
    var draft by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }

    val editing = room != null
    val hospital = draft.kind?.isHospital ?: false
    val names = PlacesRoomRules.numberedNames(draft.name, count = if (editing) 1 else draft.count, existing = rooms)
    val dirty = draft != initial || (!editing && template == null && step == PlacesRoomStep.details)
    // Services it was tied to that the business no longer offers: still listed, so they can be unticked.
    val gone = draft.serviceIds.filter { id -> services.none { it.id == id } }

    val title = when {
        room != null -> LF("Edit %@", room.name)
        template != null -> LF("Copy of %@", template.name)
        else -> L("Add a room")
    }
    val subtitle = if (hospital) {
        L("A place for hospitalized patients. It never shows in the Schedule or when placing a visit.")
    } else {
        L("A place visits happen in. Two visits never share it unless it holds more than one.")
    }
    val primaryTitle = when {
        step == PlacesRoomStep.kind -> L("Continue")
        editing -> L("Save room")
        else -> if (names.size > 1) LF("Add %d rooms", names.size) else L("Add room")
    }

    fun choose(kind: PlacesRoomKind) {
        draft = draft.copy(
            kind = kind,
            // A cage holds one patient; wards and cages are never "for" a service.
            capacity = if (kind == PlacesRoomKind.cage) 1 else draft.capacity,
            serviceIds = if (kind.isHospital) emptyList() else draft.serviceIds,
        )
        form.clear("kind")
        if (step == PlacesRoomStep.kind) step = PlacesRoomStep.details
    }

    fun primary() {
        if (step == PlacesRoomStep.kind) {
            // Nothing chosen yet: said like any invalid field, never a greyed button.
            if (!form.check(listOf("kind" to (if (draft.kind == null) L("What kind of place is it?") else null)))) return
            step = PlacesRoomStep.details
            return
        }
        val kind = draft.kind
        if (kind == null) {
            step = PlacesRoomStep.kind
            return
        }
        val name = draft.name.trim()
        val list = names
        val nameProblem: String? = if (name.isEmpty()) {
            L("Give the room a name.")
        } else if (list.size == 1 && PlacesRoomRules.nameTaken(list[0], rooms = rooms, except = room?.id)) {
            LF("There is already a room called %@ here.", list[0])
        } else null
        if (!form.check(listOf("name" to nameProblem))) return

        val values = mutableMapOf<String, Any?>(
            "kind" to kind.rawValue,
            "capacity" to draft.capacity,
            "serviceIds" to (if (kind.isHospital) emptyList() else draft.serviceIds),
            "notes" to draft.notes.nonEmpty,
        )
        saving = true
        scope.launch {
            try {
                if (room != null) {
                    values["name"] = name
                    API.patch<PlacesRoomSaved>("/provider-rooms/${room.id}", values)
                    Toast.show(L("Room saved."))
                } else if (list.size > 1) {
                    val rows: List<Map<String, Any?>> = list.map { entry -> values.toMutableMap().apply { put("name", entry) } }
                    val body = mutableMapOf<String, Any?>("rooms" to rows)
                    Workspace.shared.locationId?.let { body["locationId"] = it }
                    val made: List<PlacesRoomSaved> = API.post("/provider-rooms/batch", body)
                    Toast.show(LF("%d rooms added.", made.size))
                } else {
                    values["name"] = name
                    Workspace.shared.locationId?.let { values["locationId"] = it }
                    val saved: PlacesRoomSaved = API.post("/provider-rooms", values)
                    Toast.show(LF("%@ added.@room", saved.name))
                }
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is APIError && e.code == "room_name_taken") {
                    form.check(listOf("name" to LF("There is already a room called %@ here.", name)))
                    return@launch
                }
                Toast.error(PlacesRoomRules.errorText(e, fallback = L("Could not save the room.")))
            } finally {
                saving = false
            }
        }
    }

    // MARK: Steps

    /** What kind of place (web `OptionCardGrid`): a card per kind with its icon, name and line, in the web's order. */
    @Composable
    fun kindStep() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.formScrollTarget("kind", form), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("What kind of place is it?"), style = Theme.heading(16.8), color = Theme.inkStrong)
                Text(L("Visits are booked into consult rooms, tables and baths. Wards and cages hold hospitalized patients only."), style = Theme.text(14), color = Theme.inkSoft)
            }
            OptionCards(
                options = PlacesRoomKind.entries.map { kind ->
                    OptionCard(kind, title = kind.label, description = kind.hint, icon = kind.systemImage, accent = kind.hue.color)
                },
                selection = stateOf<PlacesRoomKind?>({ draft.kind }, { next -> if (next != null) choose(next) }),
                invalid = form.error("kind") != null,
            )
        }
    }

    val namesPreview: String = run {
        if (draft.count == 1) return@run L("One room. Raise it to add several at once, numbered for you.")
        val list = names
        if (list.isEmpty()) return@run L("Type the name and they are numbered for you.")
        val shown = if (list.size > 4) "${list.take(3).joinToString(", ")} … ${list[list.size - 1]}" else list.joinToString(", ")
        LF("It adds: %@", shown)
    }

    @Composable
    fun details() {
        val chosenKind = draft.kind
        if (!editing && chosenKind != null) {
            // What it is about, once chosen: the kind (tap to change it), where it goes, what it copies.
            val back = L("Back")
            FlowLayout(spacing = 6.dp) {
                Box(
                    Modifier
                        .plainButton { step = PlacesRoomStep.kind }
                        .semantics { onClick(label = back) { step = PlacesRoomStep.kind; true } },
                ) {
                    Chip(text = chosenKind.label, tone = Chip.Tone.muted, icon = chosenKind.systemImage)
                }
                val place = Workspace.shared.selectedLocation
                if (Workspace.shared.multiLocation && place != null) {
                    Chip(text = place.name, tone = Chip.Tone.muted, icon = "map-pin")
                }
                if (template != null) Chip(text = LF("Copy of %@", template.name), tone = Chip.Tone.muted, icon = "copy")
            }
        }

        if (editing) {
            ModalSection(title = L("What kind of place"), systemImage = "door-open", tint = Theme.borderSoft) {
                PlacesGrid(
                    minimum = 140.dp,
                    spacing = 8.dp,
                    cells = PlacesRoomKind.entries.map { kind ->
                        @Composable {
                            val on = draft.kind == kind
                            val box = RoundedCornerShape(Theme.boxRadius)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .semantics { selected = on }
                                    .plainButton(enabled = !saving) { choose(kind) }
                                    .background(Theme.surface, box)
                                    .border(2.dp, if (on) Theme.inkStrong else Theme.borderSoft, box)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlacesIconBadge(systemImage = kind.systemImage, hue = kind.hue, size = 32.dp, washPercent = if (on) 38.0 else 20.0)
                                Text(kind.label, style = Theme.text(14, FontWeight.Bold), color = if (on) Theme.inkStrong else Theme.inkSoft, maxLines = 2, modifier = Modifier.weight(1f))
                            }
                        }
                    },
                )
            }
        }

        PMTextField(
            L("Name"), text = stateOf({ draft.name }, { draft = draft.copy(name = it) }), key = "name", form = form,
            prompt = if (hospital) L("e.g. Ward A, Cage 3") else L("e.g. Consult room 1, Grooming table"),
            capitalization = KeyboardCapitalization.Words, required = true,
        )

        FormField(
            label = if (hospital) L("Patients at once") else L("Visits at once"),
            hint = if (hospital) {
                L("How many hospitalized patients it holds. A cage holds one; a ward holds as many as it has places.")
            } else {
                L("How many pets can be seen in it at the same time. A consult room holds one; a big grooming area may hold two or three.")
            },
        ) {
            PlacesCountStepper(
                label = if (hospital) L("Patients at once") else L("Visits at once"),
                value = stateOf({ draft.capacity }, { draft = draft.copy(capacity = it) }),
                range = 1..PlacesRoomRules.capacityMax,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        if (!editing) {
            ModalSection(title = L("How many like this"), systemImage = "users-round", tint = Theme.butter) {
                PlacesCountStepper(
                    label = L("How many like this"),
                    value = stateOf({ draft.count }, { draft = draft.copy(count = it) }),
                    range = 1..PlacesRoomRules.copiesMax,
                )
                Text(namesPreview, style = Theme.text(14), color = Theme.inkSoft)
            }
        }

        if (hospital) {
            Text(
                L("Wards and cages hold hospitalized patients only: they never show in the schedule or when placing a visit."),
                style = Theme.text(14),
                color = Theme.inkStrong,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.pink, 14.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else if (services.isNotEmpty() || gone.isNotEmpty()) {
            ModalSection(
                title = L("Used for"),
                subtitle = L("Pick the services that happen here and it is chosen first for them. Leave it empty if anything can happen here."),
                systemImage = "stethoscope",
                tint = Theme.borderSoft,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (services + gone.map { PlacesService(id = it, label = L("A service you no longer offer")) }).forEach { service ->
                        val id = service.id
                        val on = draft.serviceIds.contains(id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .semantics { selected = on }
                                .plainButton(enabled = !saving) {
                                    draft = draft.copy(serviceIds = if (on) draft.serviceIds.filter { it != id } else draft.serviceIds + id)
                                }
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tick(on = on)
                            Text(service.label, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(
                    if (draft.serviceIds.isEmpty()) L("Any service") else LP(draft.serviceIds.size, "%d service chosen", "%d services chosen"),
                    style = Theme.text(12, FontWeight.Bold),
                    color = Theme.inkSoft,
                )
            }
        }

        PMTextField(
            L("Note for the team"), text = stateOf({ draft.notes }, { draft = draft.copy(notes = it) }), key = "notes", form = form,
            prompt = L("e.g. The big scale is here. Keep the door closed for cats."), multiline = true,
        )
    }

    ProviderModal(
        title = title,
        subtitle = subtitle,
        systemImage = draft.kind?.systemImage ?: "door-open",
        accent = draft.kind?.accent ?: ModalAccent.aqua,
        isDirty = dirty,
        form = form,
        primaryTitle = primaryTitle,
        isWorking = saving,
        steps = if (editing) null else ModalSteps(listOf(L("Kind"), L("Details")), current = if (step == PlacesRoomStep.kind) 0 else 1),
        // A new room past its kind goes back to it; a copy starts on its details.
        onBack = if (!editing && template == null && step == PlacesRoomStep.details) { { step = PlacesRoomStep.kind } } else null,
        primaryIcon = if (step == PlacesRoomStep.kind) null else (if (editing) "save" else "plus"),
        onPrimary = { primary() },
        modifier = modifier,
    ) {
        if (step == PlacesRoomStep.kind) kindStep() else details()
    }
}

// MARK: - Out of service

@Composable
fun PlacesRoomOutSheet(
    room: PlacesRoom,
    today: String,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
    onGo: (ProviderRoute) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val current = remember { PlacesRoomRules.outState(room, today = today) }
    var fromDate by remember { mutableStateOf(PlacesDay.date(current?.fromDay ?: today)) }
    var untilDate by remember { mutableStateOf(PlacesDay.date(current?.untilDay ?: current?.fromDay ?: today)) }
    var openEnded by remember { mutableStateOf(current != null && current.untilDay == null) }
    var reason by remember { mutableStateOf(current?.reason ?: "") }
    var touched by remember { mutableStateOf(false) }
    var visits by remember { mutableStateOf<PlacesRoomOutVisits?>(null) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val fromDay = PlacesDay.key(fromDate)
    val untilDay: String? = if (openEnded) null else PlacesDay.key(maxOf(untilDate, fromDate))
    val minFrom: Instant = if (current != null && current.state == PlacesRoomOutState.State.out && current.fromDay < today) PlacesDay.date(current.fromDay) else PlacesDay.date(today)
    val dayCount: Int = run {
        val zone = Workspace.shared.timeZone
        val start = fromDate.atZone(zone).toLocalDate()
        val end = maxOf(untilDate, fromDate).atZone(zone).toLocalDate()
        ChronoUnit.DAYS.between(start, end).toInt() + 1
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                val out: Map<String, Any?> = mapOf("fromDay" to fromDay, "untilDay" to untilDay, "reason" to reason.nonEmpty)
                val saved: PlacesRoomSaved = API.patch("/provider-rooms/${room.id}", mapOf("outOfService" to out))
                val left = saved.visitsWhileOut?.count ?: 0
                Toast.show(
                    if (left > 0) {
                        if (left == 1) LF("%@ is out of service. 1 visit is still booked in it then.", room.name) else LF("%@ is out of service. %d visits are still booked in it then.", room.name, left)
                    } else {
                        LF("%@ is out of service.", room.name)
                    }
                )
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(PlacesRoomRules.errorText(e, fallback = L("Could not save the room.")))
            } finally {
                saving = false
            }
        }
    }

    fun putBack() {
        saving = true
        scope.launch {
            try {
                API.patch<PlacesRoomSaved>("/provider-rooms/${room.id}", mapOf("outOfService" to null))
                Toast.show(LF("%@ is back in service.", room.name))
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(PlacesRoomRules.errorText(e, fallback = L("Could not save the room.")))
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect("$fromDay|${untilDay ?: "open"}") {
        delay(250)
        checking = true
        try {
            val answer: PlacesRoomOutVisits? = try {
                API.get<PlacesRoomOutVisits>("/provider-rooms/${room.id}/out-of-service-visits", mapOf("fromDay" to fromDay, "untilDay" to untilDay))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            visits = answer
        } finally {
            checking = false
        }
    }

    ProviderModal(
        title = if (current != null) LF("%@ is out of service", room.name) else LF("Take %@ out of service", room.name),
        subtitle = L("For maintenance or a repair. Nothing is cancelled: visits already booked in it stay, and booking into it those days asks first."),
        systemImage = "wrench",
        accent = ModalAccent.lime,
        isDirty = touched,
        primaryTitle = if (current != null) L("Save days") else L("Take out of service"),
        isWorking = saving,
        secondaryActions = if (current != null) listOf(ModalAction(L("Put back in service"), icon = "rotate-ccw", isWorking = saving) { putBack() }) else emptyList(),
        primaryIcon = "wrench",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        DateTimeField(
            label = L("From"),
            date = stateOf({ fromDate }, {
                fromDate = it
                if (untilDate < it) untilDate = it
                touched = true
            }),
            min = minFrom,
            components = setOf(DatePickerComponent.date),
        )
        if (!openEnded) {
            DateTimeField(
                label = L("Until"),
                date = stateOf({ untilDate }, {
                    untilDate = it
                    touched = true
                }),
                min = fromDate,
                components = setOf(DatePickerComponent.date),
                hint = if (dayCount == 1) L("1 day") else LF("%d days", dayCount),
            )
        }

        ToggleRow(
            title = L("No return date yet"),
            subtitle = L("It stays out until you put it back in service."),
            isOn = stateOf({ openEnded }, {
                openEnded = it
                touched = true
            }),
        )

        PMTextField(
            L("Why (optional)"),
            text = stateOf({ reason }, {
                reason = it
                touched = true
            }),
            prompt = L("e.g. Painting, the table is being repaired"),
        )

        // What is still booked in it then: said and linked, never moved for anyone.
        val found = visits
        val count = found?.count
        if (checking && found == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                Text(L("Checking what is booked in it…"), style = Theme.text(14), color = Theme.inkSoft)
            }
        } else if (found != null && count != null && count > 0) {
            PlacesConsequenceRow(
                systemImage = "calendar-clock",
                text = found.firstDay?.let { LF("The first is on %@. They keep their room until you move them from the Schedule.", PlacesDay.label(it)) }
                    ?: L("They keep their room until you move them from the Schedule."),
                title = if (count == 1) L("1 visit is still booked in it those days.") else LF("%d visits are still booked in it those days.", count),
                actionTitle = L("See them in the Schedule"),
                action = { onGo(ProviderRoute.schedule(ScheduleFilter(roomId = room.id, view = "list"))) },
            )
        } else if (found != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("calendar-check-2", 16.dp, tint = ProviderState.done.color)
                Text(L("Nothing is booked in it those days."), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - Archive or delete

@Composable
fun PlacesRoomRemoveSheet(
    room: PlacesRoom,
    deleting: Boolean,
    usage: PlacesRoomUsage?,
    usageLoaded: Boolean,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
    onGo: (ProviderRoute) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val patients = usage?.patients ?: 0

    /**
     * Deleting, for the plain confirm: it was used since the page loaded — say so and offer
     * archiving instead (the sheet closes); any other refusal stays on the sheet in the room's words.
     */
    suspend fun remove() {
        try {
            API.send("DELETE", "/provider-rooms/${room.id}")
            Toast.show(LF("%@ was deleted.", room.name))
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is APIError && e.code == "room_has_history") {
                Toast.error(LF("%@ has visits or patients in its history, so it can’t be deleted. Archive it instead.", room.name))
                onDone()
                return
            }
            throw APIError(status = (e as? APIError)?.status ?: 0, message = PlacesRoomRules.errorText(e, fallback = L("Could not delete the room.")), code = null, fields = emptyMap())
        }
    }

    fun confirm() {
        working = true
        scope.launch {
            try {
                API.patch<PlacesRoomSaved>("/provider-rooms/${room.id}", mapOf("status" to "archived"))
                Toast.show(LF("%@ was archived.", room.name))
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(PlacesRoomRules.errorText(e, fallback = L("Could not archive the room.")))
            } finally {
                working = false
            }
        }
    }

    if (patients > 0) {
        // Patients in it: neither archived nor deleted — one thing to do first, with the way there.
        // A question with nothing under it: the sheet fits its header and buttons.
        ProviderModal(
            title = LF("%@ has patients in it", room.name),
            subtitle = if (patients == 1) {
                L("1 hospitalized patient is in it. Move the patient to another ward or cage first; then it can be archived.")
            } else {
                LF("%d hospitalized patients are in it. Move them to another ward or cage first; then it can be archived.", patients)
            },
            systemImage = "bed-double",
            accent = ModalAccent.pink,
            primaryTitle = L("Open the Hospital"),
            cancelTitle = L("Close"),
            primaryIcon = "bed-double",
            onPrimary = { onGo(ProviderRoute.section(ProviderSection.hospital)) },
            modifier = modifier,
        )
    } else if (deleting) {
        // Never used, so nothing to read before: the kit's plain confirm.
        ConfirmSheet(
            title = LF("Delete %@?", room.name),
            message = L("Nothing was ever booked in it, so it is removed for good. This can’t be undone."),
            confirmTitle = L("Delete room"),
            destructive = true,
            cancelTitle = L("Keep it@room"),
            modifier = modifier,
        ) { _ ->
            remove()
        }
    } else {
        // The web's ProviderConfirmSheet with what archiving leaves behind under it.
        ProviderModal(
            title = LF("Archive %@?", room.name),
            subtitle = L("It leaves the booking sheet and the Schedule’s columns, and can’t be booked."),
            systemImage = "triangle-alert",
            accent = ModalAccent.pink,
            primaryTitle = L("Archive room"),
            primaryVariant = ButtonVariant.destructive,
            isWorking = working,
            cancelTitle = L("Keep it@room"),
            primaryIcon = "triangle-alert",
            onPrimary = { confirm() },
            modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
        ) {
            if (usage != null) {
                val upcoming = usage.upcomingVisits
                if (upcoming != null && upcoming > 0) {
                    PlacesConsequenceRow(
                        systemImage = "calendar-clock",
                        text = if (upcoming == 1) {
                            L("1 visit is still booked in it. It keeps the room’s name; nothing is cancelled or moved for you.")
                        } else {
                            LF("%d visits are still booked in it. They keep the room’s name; nothing is cancelled or moved for you.", upcoming)
                        },
                        actionTitle = L("See them in the Schedule"),
                        action = { onGo(ProviderRoute.schedule(ScheduleFilter(roomId = room.id, view = "list"))) },
                    )
                }
                if (usage.hasHistory == true) {
                    PlacesConsequenceRow(systemImage = "history", text = L("Past visits keep it, and you can make it active again any time."))
                }
            } else if (!usageLoaded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                    Text(L("Checking what is booked in it…"), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
    }
}
