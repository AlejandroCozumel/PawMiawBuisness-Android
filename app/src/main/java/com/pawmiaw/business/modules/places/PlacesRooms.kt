package com.pawmiaw.business.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

//
//  PlacesRooms.kt (iOS PlacesRooms.swift)
//
//  Settings → Rooms ("Espacios"), web provider.rooms.tsx → components/app/rooms/rooms-page.tsx:
//  the places visits happen in (consult rooms, grooming tables, baths, the surgery) and
//  the hospital's wards and cages, of the location this device works at. Added one by
//  one or numbered, copied, put in order, taken out of service for some days, archived
//  or (when nothing ever used them) deleted. Only someone who edits the workspace
//  changes them.
//

@Composable
fun RoomsScreen(modifier: Modifier = Modifier) {
    providerPage(L("Rooms"), root = true, modifier = modifier) {
        RequireLocation { PlacesRoomsPage() }
    }
}

/** The sheets the page opens. */
sealed class PlacesRoomSheet {
    data object new : PlacesRoomSheet()
    data class edit(val room: PlacesRoom) : PlacesRoomSheet()
    data class copy(val room: PlacesRoom) : PlacesRoomSheet()
    data class `out`(val room: PlacesRoom) : PlacesRoomSheet()
    data class archive(val room: PlacesRoom) : PlacesRoomSheet()
    data class delete(val room: PlacesRoom) : PlacesRoomSheet()

    val id: String
        get() = when (this) {
            new -> "new"
            is edit -> "edit-${room.id}"
            is copy -> "copy-${room.id}"
            is `out` -> "out-${room.id}"
            is archive -> "archive-${room.id}"
            is delete -> "delete-${room.id}"
        }
}

/** The filter chip's icon (web rooms-page.tsx `FILTERS`). */
private val PlacesRoomFilter.icon: String
    get() = when (this) {
        PlacesRoomFilter.all -> "door-open"
        PlacesRoomFilter.visits -> "stethoscope"
        PlacesRoomFilter.hospital -> "bed-double"
        PlacesRoomFilter.out -> "wrench"
        PlacesRoomFilter.archived -> "archive"
    }

/** The filter chip's colour (web rooms-page.tsx `FILTERS`). */
private val PlacesRoomFilter.tone: Color
    @Composable get() = when (this) {
        PlacesRoomFilter.all -> Theme.blue
        PlacesRoomFilter.visits -> PlacesRoomKind.consult.hue.color
        PlacesRoomFilter.hospital -> PlacesRoomKind.ward.hue.color
        PlacesRoomFilter.out -> ProviderState.problem.color
        PlacesRoomFilter.archived -> ProviderState.closed.color
    }

private enum class RoomCardAction { edit, copy, moveUp, moveDown, out, putBack, archive, delete, reactivate }

private suspend fun <T> roomsAttempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** The page's state (the Swift struct's `@State`s and the values read from them). */
@Stable
private class PlacesRoomsState {
    var rooms by mutableStateOf<List<PlacesRoom>?>(null)
    var usage by mutableStateOf<Map<String, PlacesRoomUsage>?>(null)
    var services by mutableStateOf<List<PlacesService>>(emptyList())
    var loadError by mutableStateOf<String?>(null)
    var filter by mutableStateOf(PlacesRoomFilter.all)
    var sheet by mutableStateOf<PlacesRoomSheet?>(null)
    var busyRoom by mutableStateOf<String?>(null)
    var addingPreset by mutableStateOf(false)

    val today: String get() = DateText.dayKey()
    val all: List<PlacesRoom> get() = rooms ?: emptyList()

    /** A filter with nothing under it shows everything. */
    fun shownFilter(counts: Map<PlacesRoomFilter, Int>): PlacesRoomFilter =
        if (filter != PlacesRoomFilter.all && (counts[filter] ?: 0) == 0) PlacesRoomFilter.all else filter

    val serviceLabel: Map<String, String>
        get() = buildMap { services.forEach { if (!containsKey(it.id)) put(it.id, it.label) } }
}

@Composable
private fun PlacesRoomsPage() {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val s = remember { PlacesRoomsState() }

    val canView = workspace.can(Permission.appointmentsView)
    val canManage = workspace.can(Permission.workspaceEdit)

    fun saved() {
        announceDataChanged()
    }

    /** From a sheet: close it, then open the page that deals with it. */
    fun go(route: ProviderRoute) {
        s.sheet = null
        scope.launch {
            delay(400)
            nav.push(route)
        }
    }

    suspend fun load() {
        if (workspace.current == null || !workspace.can(Permission.appointmentsView) || workspace.allLocationsChosen) return
        val location = workspace.locationId
        try {
            val list: List<PlacesRoom> = API.get("/provider-rooms", mapOf("includeArchived" to "true", "locationId" to location))
            s.rooms = list
            s.loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A refresh that fails keeps the list that was there.
            if (s.rooms == null) s.loadError = errorText(e) else Toast.error(e)
        }
        // An older server has no usage: the page shows the rooms without it.
        roomsAttempt { API.get<List<PlacesRoomUsage>>("/provider-rooms/usage", mapOf("locationId" to location)) }?.let { answer ->
            s.usage = buildMap { answer.forEach { if (!containsKey(it.roomId)) put(it.roomId, it) } }
        }
        roomsAttempt { API.get<PlacesServiceCatalog>("/appointment-services", mapOf("scope" to "provider")) }?.let { catalog ->
            s.services = catalog.services ?: emptyList()
        }
    }

    suspend fun move(room: PlacesRoom, direction: Int) {
        val order = PlacesRoomRules.move(s.all, roomId = room.id, direction = direction) ?: return
        s.busyRoom = room.id
        try {
            try {
                API.send("PUT", "/provider-rooms/order", mapOf("roomIds" to order))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(PlacesRoomRules.errorText(e, fallback = L("Could not change the order.")))
            }
            load()
            announceDataChanged()
        } finally {
            s.busyRoom = null
        }
    }

    suspend fun patch(room: PlacesRoom, body: Map<String, Any?>, success: String) {
        s.busyRoom = room.id
        try {
            API.patch<PlacesRoomSaved>("/provider-rooms/${room.id}", body)
            Toast.show(success)
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(PlacesRoomRules.errorText(e, fallback = L("Could not save the room.")))
        } finally {
            s.busyRoom = null
        }
    }

    suspend fun addPreset(preset: PlacesRoomRules.Preset) {
        val names = PlacesRoomRules.numberedNames(L(preset.name), count = preset.count, existing = s.all)
        s.addingPreset = true
        try {
            val body = mutableMapOf<String, Any?>(
                "rooms" to names.map { mapOf("name" to it, "kind" to preset.kind.rawValue, "capacity" to preset.capacity, "serviceIds" to emptyList<String>()) },
            )
            workspace.locationId?.let { body["locationId"] = it }
            val made: List<PlacesRoomSaved> = API.post("/provider-rooms/batch", body)
            Toast.show(if (made.size == 1) LF("%@ added.@room", made[0].name) else LF("%d rooms added.", made.size))
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(PlacesRoomRules.errorText(e, fallback = L("Could not save the room.")))
        } finally {
            s.addingPreset = false
        }
    }

    fun handle(action: RoomCardAction, room: PlacesRoom) {
        when (action) {
            RoomCardAction.edit -> s.sheet = PlacesRoomSheet.edit(room)
            RoomCardAction.copy -> s.sheet = PlacesRoomSheet.copy(room)
            RoomCardAction.out -> s.sheet = PlacesRoomSheet.out(room)
            RoomCardAction.archive -> s.sheet = PlacesRoomSheet.archive(room)
            RoomCardAction.delete -> s.sheet = PlacesRoomSheet.delete(room)
            RoomCardAction.moveUp -> scope.launch { move(room, -1) }
            RoomCardAction.moveDown -> scope.launch { move(room, 1) }
            RoomCardAction.putBack -> scope.launch { patch(room, mapOf("outOfService" to null), success = LF("%@ is back in service.", room.name)) }
            RoomCardAction.reactivate -> scope.launch { patch(room, mapOf("status" to "active"), success = LF("%@ is active again.@room", room.name)) }
        }
    }

    if (workspace.current != null && !canView) {
        PageScroll {
            EmptyState(systemImage = "lock", title = L("You do not have access to rooms."), message = L("Ask the workspace owner to let your role see visits."), panel = true)
        }
    } else {
        PageScroll(refresh = { load() }) {
            RoomsHeader(s = s, canManage = canManage)
            if (s.rooms == null && s.loadError == null) {
                LoadingState()
            } else if (s.loadError != null && s.rooms == null) {
                EmptyState(
                    systemImage = "door-open",
                    title = L("The rooms couldn’t load."),
                    message = L("Check your connection and try again."),
                    actionTitle = L("Try again"),
                    action = { scope.launch { load() } },
                    accent = Theme.mango,
                    panel = true,
                )
            } else if (s.all.isEmpty()) {
                EmptyRooms(s = s, canManage = canManage, addPreset = { preset -> scope.launch { addPreset(preset) } })
            } else {
                RoomsList(s = s, canManage = canManage, handle = { action, room -> handle(action, room) })
            }
            if (s.rooms != null) RelatedPlaces(s = s)
        }
    }

    ReloadsOnScope { load() }

    s.sheet?.let { which ->
        key(which.id) {
            AppSheet(onDismissRequest = { s.sheet = null }) {
                val all = s.all
                when (which) {
                    PlacesRoomSheet.new ->
                        PlacesRoomSheetView(room = null, template = null, templateName = null, rooms = all, services = s.services, onSaved = { saved() })
                    is PlacesRoomSheet.edit ->
                        PlacesRoomSheetView(room = which.room, template = null, templateName = null, rooms = all, services = s.services, onSaved = { saved() })
                    is PlacesRoomSheet.copy ->
                        PlacesRoomSheetView(room = null, template = which.room, templateName = PlacesRoomRules.copyName(which.room.name, existing = all), rooms = all, services = s.services, onSaved = { saved() })
                    is PlacesRoomSheet.out ->
                        PlacesRoomOutSheet(room = which.room, today = s.today, onSaved = { saved() }, onGo = { go(it) })
                    is PlacesRoomSheet.archive ->
                        PlacesRoomRemoveSheet(room = which.room, deleting = false, usage = s.usage?.get(which.room.id), usageLoaded = s.usage != null, onDone = { saved() }, onGo = { go(it) })
                    is PlacesRoomSheet.delete ->
                        PlacesRoomRemoveSheet(room = which.room, deleting = true, usage = s.usage?.get(which.room.id), usageLoaded = s.usage != null, onDone = { saved() }, onGo = { go(it) })
                }
            }
        }
    }
}

// MARK: Page

/** The web's `ProviderModuleHeader`: DoorOpen on the default blue, "Add a room", three numbers. */
@Composable
private fun RoomsHeader(s: PlacesRoomsState, canManage: Boolean) {
    val workspace = Workspace.shared
    val all = s.all
    val counts = PlacesRoomRules.counts(all, today = s.today)
    val active = all.filter { !it.isArchived }
    val usage = s.usage
    val visitsToday = active.sumOf { usage?.get(it.id)?.visitsToday ?: 0 }
    val out = counts[PlacesRoomFilter.out] ?: 0
    val hospital = counts[PlacesRoomFilter.hospital] ?: 0
    val subtitle = L("The places visits happen in. Each booking says whether its room is free, and warns before two visits share one.")
    val shownFilter = s.shownFilter(counts)
    ModuleHeader(
        icon = "door-open",
        title = L("Rooms"),
        subtitle = if (workspace.multiLocation) listOf(workspace.selectedLocation?.name, subtitle).mapNotNull { it.nonEmpty }.joinToString(" · ") else subtitle,
        stats = listOf(
            ModuleStat(
                "rooms", label = L("Rooms"), value = if (s.rooms == null) "—" else "${active.size}",
                hint = if (hospital > 0) LF("%d for the hospital", hospital) else null, icon = "door-open", accent = Theme.blue,
            ),
            ModuleStat("today", label = L("Visits today"), value = if (usage == null) "—" else "$visitsToday", hint = L("Booked into a room"), icon = "calendar-days", accent = Theme.aqua),
            ModuleStat(
                "out", label = L("Out of service"), value = if (s.rooms == null) "—" else "$out",
                hint = if (out > 0) L("Now or planned") else L("All in service"), icon = "wrench", accent = Theme.lime,
                active = shownFilter == PlacesRoomFilter.out,
                action = if (out > 0) { { s.filter = if (s.filter == PlacesRoomFilter.out) PlacesRoomFilter.all else PlacesRoomFilter.out } } else null,
            ),
        ),
        storageKey = "rooms",
        actions = if (canManage) {
            { PMButton(L("Add a room"), systemImage = "plus") { s.sheet = PlacesRoomSheet.new } }
        } else null,
    )
}

@Composable
private fun RoomsList(s: PlacesRoomsState, canManage: Boolean, handle: (RoomCardAction, PlacesRoom) -> Unit) {
    val all = s.all
    val today = s.today
    val counts = PlacesRoomRules.counts(all, today = today)
    val shownFilter = s.shownFilter(counts)
    val shown = PlacesRoomRules.filter(all, shownFilter, today = today)
    val groups = PlacesRoomRules.groups(shown)
    val orderable = shownFilter == PlacesRoomFilter.all || shownFilter == PlacesRoomFilter.visits || shownFilter == PlacesRoomFilter.hospital
    val options = PlacesRoomFilter.entries.filter { it == PlacesRoomFilter.all || (counts[it] ?: 0) > 0 }
    val serviceLabel = s.serviceLabel
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val count = counts[option] ?: 0
                FilterChip(title = option.title, count = if (count > 0) count else null, isSelected = shownFilter == option, tone = option.tone, icon = option.icon) {
                    s.filter = if (s.filter == option) PlacesRoomFilter.all else option
                }
            }
        }
        if (groups.isEmpty()) {
            EmptyState(
                systemImage = "door-open",
                title = L("No rooms in use"),
                message = L("Every room is archived. Make one active again, or add a new one."),
                actionTitle = if (canManage) L("Add a room") else null,
                action = { s.sheet = PlacesRoomSheet.new },
                accent = Theme.aqua,
                panel = true,
            )
        } else {
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlacesIconBadge(systemImage = group.kind.systemImage, hue = group.kind.hue, size = 28.dp)
                        Text(group.kind.plural, style = Theme.heading(18), color = Theme.inkStrong)
                        Chip(text = "${group.rooms.size}", tone = Chip.Tone.muted)
                    }
                    PlacesGrid(
                        minimum = 300.dp,
                        spacing = 12.dp,
                        cells = group.rooms.mapIndexed { index, room ->
                            @Composable {
                                PlacesRoomCard(
                                    room = room,
                                    usage = s.usage?.get(room.id),
                                    out = PlacesRoomRules.outState(room, today = today),
                                    serviceLabel = serviceLabel,
                                    canManage = canManage,
                                    busy = s.busyRoom == room.id,
                                    canMoveUp = orderable && index > 0,
                                    canMoveDown = orderable && index < group.rooms.size - 1,
                                    onAction = { action -> handle(action, room) },
                                )
                            }
                        },
                    )
                }
            }
        }
        CountLine(s = s, shown = shown.size, filtered = shownFilter != PlacesRoomFilter.all, canManage = canManage)
    }
}

/** "4 rooms · See all · Only someone who edits the workspace can change them." */
@Composable
private fun CountLine(s: PlacesRoomsState, shown: Int, filtered: Boolean, canManage: Boolean) {
    val style = Theme.text(14)
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(LP(shown, "%d room", "%d rooms"), style = style, color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
        if (filtered) {
            Text("·", style = style, color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
            Text(
                L("See all"),
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blue,
                modifier = Modifier.alignByBaseline().plainButton { s.filter = PlacesRoomFilter.all },
            )
        }
        if (!canManage) {
            Text("· " + L("Only someone who edits the workspace can change them."), style = style, color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
        }
    }
}

// MARK: Empty

/** No rooms yet (web `RoomsEmpty`): the empty panel with "Add a room", and why rooms matter with the quick starts. */
@Composable
private fun EmptyRooms(s: PlacesRoomsState, canManage: Boolean, addPreset: (PlacesRoomRules.Preset) -> Unit) {
    val workspace = Workspace.shared
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EmptyState(
            systemImage = "door-open",
            title = L("No rooms yet"),
            message = if (canManage) {
                L("Add your consult rooms, grooming tables and the surgery. Without rooms, only the person is checked when booking.")
            } else {
                L("Ask whoever runs the workspace to add its rooms in Settings.")
            },
            actionTitle = if (canManage) L("Add a room") else null,
            action = { s.sheet = PlacesRoomSheet.new },
            accent = Theme.aqua,
            panel = true,
        )
        Card(padding = 20.dp, spacing = 8.dp) {
            Text(L("What rooms do"), style = Theme.heading(18), color = Theme.inkStrong)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    L("Booking shows which room is free, and picks one for the service."),
                    L("Two visits never land in the same room by accident: it warns first."),
                    L("The Schedule can show the day room by room."),
                ).forEach { line ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).background(Theme.blue, CircleShape))
                        Text(line, style = Theme.text(14), color = Theme.inkSoft)
                    }
                }
            }
            if (canManage) {
                StitchedDivider(modifier = Modifier.padding(top = 8.dp))
                Text(
                    L("Start with").uppercase(appLocale),
                    style = brandText(11, FontWeight.ExtraBold).copy(letterSpacing = 1.5.sp),
                    color = Theme.inkSoft,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FlowLayout(spacing = 8.dp) {
                    PlacesRoomRules.presets(workspace.current?.enabledCategories ?: emptyList()).forEach { preset ->
                        Disabled(s.addingPreset) {
                            FilterChip(
                                title = presetLabel(preset),
                                isSelected = false,
                                tone = preset.kind.hue.color,
                                icon = preset.kind.systemImage,
                                modifier = Modifier.alpha(if (s.addingPreset) 0.5f else 1f),
                            ) {
                                if (!s.addingPreset) addPreset(preset)
                            }
                        }
                    }
                }
                Text(L("You can rename them, add more or remove them afterwards."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

private fun presetLabel(preset: PlacesRoomRules.Preset): String =
    if (preset.count == 1) {
        LF("1 %@", L(preset.name).lowercase(appLocale))
    } else {
        LF("%d %@", preset.count, preset.kind.plural.lowercase(appLocale))
    }

// MARK: Related places

/** The other places the business manages (web `RelatedPlaces`), so nobody looks for them here. */
@Composable
private fun RelatedPlaces(s: PlacesRoomsState) {
    val workspace = Workspace.shared
    val categories = workspace.current?.enabledCategories ?: emptyList()
    val offersStays = categories.any { it == "boarding" || it == "daycare" } && workspace.can(Permission.staysView)
    val seesHospital = workspace.can(Permission.hospitalView)
    val hasWards = (PlacesRoomRules.counts(s.all, today = s.today)[PlacesRoomFilter.hospital] ?: 0) > 0
    val cells = mutableListOf<@Composable () -> Unit>()
    if (offersStays) {
        cells.add {
            RelatedCard(
                icon = "bed-double", tone = Theme.aqua, title = L("Kennels and suites for stays"),
                body = L("Where pets sleep or spend the day has its own list, with a nightly and a daycare rate each. It is managed in Stays, not here."),
                link = L("Open Stays → Rooms & rules"), route = ProviderRoute.section(ProviderSection.stays),
            )
        }
    }
    if (seesHospital) {
        cells.add {
            RelatedCard(
                icon = "heart-pulse", tone = Theme.pink, title = L("Hospital wards and cages"),
                body = if (hasWards) {
                    L("The wards and cages above are the Hospital’s places: patients are put in them from its board. This page is the only place they are added or changed.")
                } else {
                    L("Add a room of kind Ward or Cage here and it shows on the Hospital’s board, to put patients in. This page is the only place they are added or changed.")
                },
                link = L("Open the Hospital"), route = ProviderRoute.section(ProviderSection.hospital),
            )
        }
    }
    cells.add {
        RelatedCard(
            icon = "calendar-days", tone = Theme.butter, title = L("The day, room by room"),
            body = L("The Schedule’s day view can show one column per room: a visit is booked into a room by clicking its column, and moved by dragging it."),
            link = L("Open the Schedule by room"), route = ProviderRoute.schedule(ScheduleFilter(view = "day", by = "rooms")),
        )
    }
    PlacesGrid(minimum = 300.dp, spacing = 12.dp, cells = cells)
}

@Composable
private fun RelatedCard(icon: String, tone: Color, title: String, body: String, link: String, route: ProviderRoute) {
    val nav = LocalNavigator.current
    Card {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlacesIconBadge(systemImage = icon, size = 40.dp, washPercent = 38.0, tone = tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = Theme.heading(18), color = Theme.inkStrong)
                Text(body, style = Theme.text(14), color = Theme.inkSoft)
                Row(
                    Modifier
                        .heightIn(min = 36.dp)
                        .plainButton { nav.push(route) },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(link, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                    Glyph("chevron-right", 16.dp, tint = Theme.blue)
                }
            }
        }
    }
}

// MARK: - A room's card

/** SwiftUI negative padding (`.padding(.top, -4).padding(.trailing, -6)`): the view takes less room than it draws in. */
private fun Modifier.outset(top: Dp = 0.dp, trailing: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val t = top.roundToPx()
    val e = trailing.roundToPx()
    layout((placeable.width - e).coerceAtLeast(0), (placeable.height - t).coerceAtLeast(0)) { placeable.placeRelative(0, -t) }
}

/**
 * One room (web `RoomCard`): its kind's disc, the name in Fredoka, "KIND · HOLDS",
 * what it is for, the note, and how it stands today over a dashed rule; the ⋯
 * menu for whoever edits the workspace.
 */
@Composable
private fun PlacesRoomCard(
    room: PlacesRoom,
    usage: PlacesRoomUsage?,
    out: PlacesRoomOutState?,
    serviceLabel: Map<String, String>,
    canManage: Boolean,
    busy: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAction: (RoomCardAction) -> Unit,
) {
    val kind = room.kindEntry
    val hospital = room.isHospital
    val archived = room.isArchived

    val capacityLine = if (hospital) LP(room.places, "%d patient", "%d patients") else LP(room.places, "%d visit at a time", "%d visits at a time")

    // What it holds today: visits for a visit room, places taken for a ward or cage.
    val occupancy: String? = if (archived || usage == null) null else if (hospital) {
        val patients = usage.patients ?: 0
        when {
            patients == 0 -> L("Empty now")
            room.places == 1 && patients == 1 -> L("Occupied@ward")
            else -> LF("%d of %d places taken", patients, room.places)
        }
    } else {
        if ((usage.visitsToday ?: 0) == 0) L("No visits today") else LP(usage.visitsToday ?: 0, "%d visit today", "%d visits today")
    }

    val busyNow = if (archived || usage == null) false else if (hospital) (usage.patients ?: 0) > 0 else (usage.visitsToday ?: 0) > 0

    fun outLine(out: PlacesRoomOutState): String {
        if (out.state == PlacesRoomOutState.State.scheduled) {
            return out.untilDay?.let { LF("Out of service %@ – %@", PlacesDay.label(out.fromDay), PlacesDay.label(it)) }
                ?: LF("Out of service from %@", PlacesDay.label(out.fromDay))
        }
        return out.untilDay?.let { LF("Out of service until %@", PlacesDay.label(it)) } ?: L("Out of service · no return date")
    }

    val cardShape = RoundedCornerShape(Theme.cardRadius)
    Card(
        spacing = 12.dp,
        modifier = Modifier
            // Out now: the border takes the amber (web: lime 60% over the border).
            .then(if (out?.state == PlacesRoomOutState.State.out && !archived) Modifier.border(1.dp, Theme.mix(Theme.lime, 60.0, Theme.borderSoft), cardShape) else Modifier)
            .alpha(if (archived) 0.8f else 1f),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = if (canManage) LF("Edit %@", room.name) else room.name
            Row(
                Modifier
                    .weight(1f)
                    .plainButton(enabled = canManage) { if (canManage) onAction(RoomCardAction.edit) }
                    .semantics(mergeDescendants = true) { contentDescription = label },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlacesIconBadge(systemImage = kind.systemImage, hue = kind.hue, size = 44.dp, muted = archived)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(room.name, style = Theme.heading(20), color = Theme.inkStrong)
                    Text(
                        "${kind.label} · $capacityLine".uppercase(appLocale),
                        style = brandText(12, FontWeight.Bold).copy(letterSpacing = 1.4.sp),
                        color = Theme.inkSoft,
                    )
                }
            }
            if (canManage) {
                RoomMenu(room = room, usage = usage, out = out, busy = busy, canMoveUp = canMoveUp, canMoveDown = canMoveDown, onAction = onAction, modifier = Modifier.outset(top = 4.dp, trailing = 6.dp))
            }
        }

        // Up to three services by name, then how many more: a room tied to a dozen stays one card tall.
        val ids = room.services
        FlowLayout(spacing = 6.dp) {
            if (hospital) {
                Chip(text = L("Hospitalized patients only"), tone = Chip.Tone.online, icon = "heart-pulse")
            } else if (ids.isEmpty()) {
                Chip(text = L("Any service"), tone = Chip.Tone.muted)
            } else {
                ids.take(3).forEach { id ->
                    Chip(text = serviceLabel[id] ?: L("A service you no longer offer"), tone = Chip.Tone.info)
                }
                if (ids.size > 3) Chip(text = LF("+%d more", ids.size - 3), tone = Chip.Tone.muted)
            }
        }

        room.notes.nonEmpty?.let { notes ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Glyph("notebook-pen", 14.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 3.dp))
                Text(notes, style = Theme.text(14), color = Theme.inkSoft, maxLines = 2)
            }
        }

        // SwiftUI `Spacer(minLength: 0)` between the stack's rows.
        Spacer(Modifier.height(0.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StitchedDivider()
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (archived) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("archive", 14.dp, tint = Theme.inkSoft)
                            Text(L("Archived@room-one"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
                        }
                    } else if (out != null) {
                        // On now: the amber chip; planned: dashed, unfilled.
                        Chip(text = outLine(out), tone = Chip.Tone.warn, icon = "wrench", pending = out.state == PlacesRoomOutState.State.scheduled)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(ProviderState.done.color, CircleShape))
                            Text(L("In service"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        }
                    }
                }
                if (occupancy != null) {
                    Text(occupancy, style = Theme.text(12, if (busyNow) FontWeight.Bold else FontWeight.Normal), color = if (busyNow) Theme.inkStrong else Theme.inkSoft)
                }
            }
            val reason = out?.reason
            if (reason != null && !archived) {
                Text(reason, style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

@Composable
private fun RoomMenu(
    room: PlacesRoom,
    usage: PlacesRoomUsage?,
    out: PlacesRoomOutState?,
    busy: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAction: (RoomCardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hospital = room.isHospital
    val archived = room.isArchived
    AppMenu(
        modifier = modifier,
        accessibilityLabel = LF("Actions for %@", room.name),
        label = {
            if (busy) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                }
            } else {
                IconCircleLabel(size = 40.dp, bordered = false)
            }
        },
    ) {
        item(L("Edit"), icon = "pencil") { onAction(RoomCardAction.edit) }
        item(L("Duplicate"), icon = "copy") { onAction(RoomCardAction.copy) }
        if (!archived) {
            if (canMoveUp) item(L("Move up"), icon = "arrow-up") { onAction(RoomCardAction.moveUp) }
            if (canMoveDown) item(L("Move down"), icon = "arrow-down") { onAction(RoomCardAction.moveDown) }
            // Visit rooms only: the Hospital's placement doesn't read it yet.
            if (!(hospital && out == null)) {
                divider()
                if (!hospital) {
                    item(if (out != null) L("Change the days out of service") else L("Take out of service"), icon = "wrench") { onAction(RoomCardAction.out) }
                }
                if (out != null) {
                    item(L("Put back in service"), icon = "rotate-ccw") { onAction(RoomCardAction.putBack) }
                }
            }
        }
        divider()
        if (archived) {
            item(L("Make active again"), icon = "archive-restore") { onAction(RoomCardAction.reactivate) }
        } else {
            item(L("Archive"), icon = "archive") { onAction(RoomCardAction.archive) }
        }
        // Only a room nothing ever used: anything with history is archived instead.
        if (usage != null && usage.hasHistory == false) {
            item(L("Delete"), icon = "trash-2", destructive = true) { onAction(RoomCardAction.delete) }
        }
    }
}
