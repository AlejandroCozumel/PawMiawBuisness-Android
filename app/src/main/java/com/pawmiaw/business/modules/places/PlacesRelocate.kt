package com.pawmiaw.business.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

//
//  PlacesRelocate.kt (iOS PlacesRelocate.swift)
//
//  "Por reubicar" (web provider.relocate.tsx → components/app/locations/relocate-page.tsx):
//  visits still to come at locations the plan paused. Each moves to an open
//  location at the same time — checked like a booking (the server names who or
//  which room is taken, or the hours, and it moves only on purpose) — or is
//  cancelled with a notice to the family. Several move at once. Never cancelled
//  silently.
//

@Composable
fun RelocateScreen(modifier: Modifier = Modifier) {
    providerPage(L("To relocate"), root = true, modifier = modifier) {
        PlacesRelocatePage()
    }
}

private class PlacesMoveBatch(val visits: List<PlacesRelocationVisit>) {
    val id: String = UUID.randomUUID().toString()
}

/** The page's state (the Swift struct's `@State`s and the values read from them). */
@Stable
private class PlacesRelocateState {
    var visits by mutableStateOf<List<PlacesRelocationVisit>?>(null)
    var loadError by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var moving by mutableStateOf<PlacesMoveBatch?>(null)
    var cancelling by mutableStateOf<PlacesRelocationVisit?>(null)

    val list: List<PlacesRelocationVisit> get() = visits ?: emptyList()
    val chosen: List<PlacesRelocationVisit> get() = list.filter { selected.contains(it.id) }
}

/** The clock of a location, by its id. */
private fun relocateZone(id: String?): String? {
    if (id == null) return null
    val place = Workspace.shared.allLocations.firstOrNull { it.id == id } ?: return null
    return place.timezone.nonEmpty ?: place.availability?.timezone
}

@Composable
private fun PlacesRelocatePage() {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val s = remember { PlacesRelocateState() }

    // Owners and admins move or cancel visits at a paused location (the web's "Todas las sedes" people).
    val canSee = workspace.isOwnerOrAdmin && workspace.can(Permission.appointmentsView)
    val canMove = workspace.can(Permission.appointmentsEdit)
    val canCancel = workspace.can(Permission.appointmentsCancel)

    suspend fun load() {
        if (workspace.current == null || !(workspace.isOwnerOrAdmin && workspace.can(Permission.appointmentsView))) return
        try {
            val answer: PlacesRelocations = API.get("/provider-locations/relocate")
            s.visits = answer.visits ?: emptyList()
            s.loadError = null
            s.selected = s.selected.intersect((answer.visits ?: emptyList()).map { it.id }.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (s.visits == null) s.loadError = errorText(e) else Toast.error(e)
        }
    }

    if (workspace.current != null && !canSee) {
        PageScroll {
            EmptyState(
                systemImage = "lock",
                title = L("Only owners and admins relocate visits."),
                message = L("Visits at a paused location are moved or cancelled by the workspace owner or an admin."),
                panel = true,
            )
        }
    } else {
        PageScroll(refresh = { load() }) {
            RelocateHeader(s)

            if (s.chosen.isNotEmpty() && canMove) SelectionBar(s)

            val loadError = s.loadError
            if (s.visits == null && loadError == null) {
                LoadingState()
            } else if (loadError != null && s.visits == null) {
                ErrorState(message = loadError) { load() }
            } else if (s.list.isEmpty()) {
                EmptyState(systemImage = "check", title = L("Nothing to relocate"), message = L("Every visit to come is at an open location."), accent = Theme.mango, panel = true)
            } else {
                val list = s.list
                val chosen = s.chosen
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canMove) {
                        Row(
                            Modifier
                                .heightIn(min = 40.dp)
                                .plainButton(label = L("Select every visit")) {
                                    s.selected = if (chosen.size == list.size) emptySet() else list.map { it.id }.toSet()
                                }
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tick(on = chosen.size == list.size, mixed = chosen.isNotEmpty() && chosen.size < list.size)
                            Text(L("Select every visit").uppercase(appLocale), style = brandText(12, FontWeight.ExtraBold).copy(letterSpacing = 1.6.sp), color = Theme.inkSoft)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { visit ->
                            key(visit.id) { RelocationRow(s, visit, canMove = canMove, canCancel = canCancel) }
                        }
                    }
                }
            }
        }
    }

    ReloadsOnScope { load() }

    s.moving?.let { batch ->
        key(batch.id) {
            AppSheet(onDismissRequest = { s.moving = null }) {
                PlacesMoveSheet(visits = batch.visits, targets = workspace.memberLocations, zoneOf = ::relocateZone) { moved ->
                    s.selected = s.selected - moved.toSet()
                    scope.launch { load() }
                }
            }
        }
    }
    s.cancelling?.let { visit ->
        key(visit.id) {
            AppSheet(onDismissRequest = { s.cancelling = null }) {
                PlacesCancelRelocationSheet(visit = visit, `when` = PlacesWhen.text(visit.startsAt, zone = relocateZone(visit.locationId))) {
                    s.selected = s.selected - visit.id
                    scope.launch { load() }
                }
            }
        }
    }
}

/** The web's `ProviderModuleHeader`: ArrowRightLeft on mango, the visits and the paused places. */
@Composable
private fun RelocateHeader(s: PlacesRelocateState) {
    val workspace = Workspace.shared
    val list = s.list
    val places = list.mapNotNull { it.locationName.nonEmpty }.toSet().sorted()
    val pausedCount = workspace.allLocations.count { it.status == "paused" }
    ModuleHeader(
        icon = "arrow-right-left",
        title = L("To relocate"),
        subtitle = L("Visits still to come at locations the plan paused. Move each to an open location at the same time, or cancel it and the family is told."),
        accent = Theme.mango,
        stats = listOf(
            ModuleStat("visits", label = L("Visits"), value = if (s.visits == null) "—" else "${list.size}", icon = "calendar-x-2", accent = Theme.mango, attention = list.isNotEmpty()),
            ModuleStat(
                "paused", label = L("Paused"), value = if (s.visits == null) "—" else "$pausedCount",
                hint = if (places.isEmpty()) null else places.joinToString(", "), icon = "circle-pause", accent = Theme.pink,
            ),
        ),
        storageKey = "relocate",
    )
}

/** What is chosen, and what to do with it (web: a white capsule bar with the card shadow). */
@Composable
private fun SelectionBar(s: PlacesRelocateState) {
    val chosen = s.chosen
    val capsule = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .cardShadow(capsule)
            .background(Theme.surface, capsule)
            .border(1.dp, Theme.borderSoft, capsule)
            .padding(6.dp)
            .padding(start = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (chosen.size == 1) L("1 selected") else LF("%d selected", chosen.size), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        Spacer(Modifier.weight(1f))
        PMButton(L("Clear"), systemImage = "x", variant = ButtonVariant.outline, size = ButtonSize.compact) { s.selected = emptySet() }
        PMButton(L("Move to…"), systemImage = "arrow-right-left", size = ButtonSize.compact) { s.moving = PlacesMoveBatch(chosen) }
    }
}

@Composable
private fun RelocationRow(s: PlacesRelocateState, visit: PlacesRelocationVisit, canMove: Boolean, canCancel: Boolean) {
    val on = s.selected.contains(visit.id)
    val cardShape = RoundedCornerShape(Theme.cardRadius)
    Card(padding = 12.dp, spacing = 12.dp, modifier = if (on) Modifier.border(1.dp, Theme.blue, cardShape) else Modifier) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canMove) {
                Box(
                    Modifier
                        .semantics { selected = on }
                        .plainButton(label = LF("Select %@", visit.pet)) {
                            s.selected = if (on) s.selected - visit.id else s.selected + visit.id
                        }
                        .sizeIn(minWidth = 28.dp, minHeight = 28.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Tick(on = on, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PlacesWhen.text(visit.startsAt, zone = relocateZone(visit.locationId)), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.blue)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(visit.pet, style = Theme.heading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
                    visit.clientName.nonEmpty?.let { client ->
                        Text("· $client", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
                    }
                }
                FlowLayout(spacing = 12.dp, lineSpacing = 2.dp, modifier = Modifier.padding(top = 2.dp)) {
                    visit.serviceLabel.nonEmpty?.let { service ->
                        Text(service, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1)
                    }
                    visit.assignedName.nonEmpty?.let { person ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("user-round", 12.dp, tint = Theme.inkSoft)
                            Text(person, style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                    visit.locationName.nonEmpty?.let { place ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("circle-pause", 12.dp, tint = Theme.inkStrong)
                            Text(place, style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong)
                        }
                    }
                }
            }
        }
        if (canCancel || canMove) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canCancel) {
                    PMButton(L("Cancel and notify"), systemImage = "calendar-x-2", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true, modifier = Modifier.weight(1f)) {
                        s.cancelling = visit
                    }
                }
                if (canMove) {
                    PMButton(L("Move to…"), systemImage = "arrow-right-left", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true, modifier = Modifier.weight(1f)) {
                        s.moving = PlacesMoveBatch(listOf(visit))
                    }
                }
            }
        }
    }
}

// MARK: - Move

/** What stopped one visit from moving. */
private data class PlacesMoveRefusal(
    val visit: PlacesRelocationVisit,
    val conflicts: List<PlacesConflict>?,
    val hours: PlacesHoursProblem?,
    val message: String?,
) {
    val id: String get() = visit.id
}

/**
 * Where the visits go: an open location, same time. What the server refuses
 * (a person or room taken, the new place's hours) is listed per visit and
 * moves only on purpose.
 */
@Composable
private fun PlacesMoveSheet(
    visits: List<PlacesRelocationVisit>,
    targets: List<ProviderLocation>,
    zoneOf: (String?) -> String?,
    onMoved: (List<String>) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<String?>(null) }
    var refusals by remember { mutableStateOf<List<PlacesMoveRefusal>>(emptyList()) }
    val confirmed = remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    val destination = targets.firstOrNull { it.id == target }
    val pending = if (refusals.isEmpty()) visits else refusals.map { it.visit }
    val overridable = refusals.isNotEmpty() && refusals.all { it.conflicts != null || it.hours != null }
    val needsConfirm = refusals.any { !(it.conflicts ?: emptyList()).isEmpty() }

    val title = if (pending.size == 1) LF("Move %@ to…", pending.firstOrNull()?.pet ?: "") else LF("Move %d visits to…", pending.size)
    val primaryTitle: String? = if (refusals.isNotEmpty()) {
        if (overridable) L("Move anyway") else null
    } else {
        destination?.let { LF("Move to %@", it.name) } ?: L("Choose a location")
    }

    fun primary() {
        val place = destination
        if (place == null) {
            Toast.error(L("Choose a location"))
            return
        }
        val anyway = refusals.isNotEmpty()
        if (anyway && needsConfirm && !confirmed.value) {
            Toast.error(L("Double booking — read the warning and confirm to book anyway."))
            return
        }
        running = true
        val toMove = pending
        scope.launch {
            try {
                val failed = mutableListOf<PlacesMoveRefusal>()
                val moved = mutableListOf<String>()
                val elsewhere = mutableListOf<String>()
                for (visit in toMove) {
                    val body = mutableMapOf<String, Any?>("appointmentId" to visit.id, "locationId" to place.id)
                    if (anyway) {
                        body["allowDoubleBooking"] = true
                        body["outsideHours"] = true
                    }
                    try {
                        val answer: PlacesRelocateAnswer = PlacesAPI.post("/provider-locations/relocate", body)
                        moved.add(visit.id)
                        val person = visit.assignedName.nonEmpty
                        if (answer.memberWorksThere == false && person != null) elsewhere.add(person)
                    } catch (refusal: PlacesRefusal) {
                        val conflicts = refusal.conflicts
                        val hours = refusal.hours
                        failed.add(
                            PlacesMoveRefusal(
                                visit = visit, conflicts = conflicts, hours = hours,
                                message = if (conflicts != null || hours != null) null else PlacesLocationRules.errorText(refusal.api, name = place.name),
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.add(PlacesMoveRefusal(visit = visit, conflicts = null, hours = null, message = PlacesLocationRules.errorText(e, name = place.name)))
                    }
                }
                if (moved.isNotEmpty()) {
                    onMoved(moved)
                    announceDataChanged()
                    Toast.show(if (moved.size == 1) LF("1 visit moved to %@. The family was told.", place.name) else LF("%d visits moved to %@. The families were told.", moved.size, place.name))
                }
                for (person in elsewhere.toSet().sorted()) {
                    Toast.show(LF("%@ doesn’t work at %@. Change who takes it from the Schedule if needed.", person, place.name))
                }
                if (failed.isEmpty()) {
                    dismiss()
                    return@launch
                }
                refusals = failed
                confirmed.value = false
            } finally {
                running = false
            }
        }
    }

    ProviderModal(
        title = title,
        subtitle = L("Same day and time, at an open location."),
        systemImage = "arrow-right-left",
        accent = ModalAccent.aqua,
        isDirty = target != null && refusals.isEmpty(),
        primaryTitle = primaryTitle,
        isWorking = running,
        primaryIcon = "arrow-right-left",
        onPrimary = { primary() },
    ) {
        if (refusals.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (targets.isEmpty()) {
                    Text(L("There is no open location to move them to."), style = Theme.text(14), color = Theme.inkSoft)
                }
                val cardShape = RoundedCornerShape(Theme.cardRadius)
                targets.forEach { place ->
                    val on = place.id == target
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .semantics { selected = on }
                            .plainButton(enabled = !running) { target = place.id }
                            .background(if (on) Theme.wash(Theme.blue, 8.0) else Theme.surface, cardShape)
                            .border(2.dp, if (on) Theme.blue else Theme.borderSoft, cardShape)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlacesLocationThumb(photoUrl = place.photoUrl, isPrimary = place.isPrimary, size = 48.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(place.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph("map-pin", 12.dp, tint = Theme.inkSoft)
                                Text(place.address.nonEmpty ?: L("No address yet"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val first = visits.firstOrNull()
                            val there = place.timezone
                            if (visits.size == 1 && first != null && there != null && there != zoneOf(first.locationId)) {
                                Text(LF("There: %@", PlacesWhen.text(first.startsAt, zone = there)), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
                            }
                        }
                        if (on) Glyph("check", 20.dp, tint = Theme.blue)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (refusals.size == 1) LF("This visit couldn’t move to %@:", destination?.name ?: "") else LF("These visits couldn’t move to %@:", destination?.name ?: ""),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
                val box = RoundedCornerShape(Theme.boxRadius)
                refusals.forEach { refusal ->
                    key(refusal.id) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, Theme.borderSoft, box)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "${refusal.visit.pet} · ${PlacesWhen.text(refusal.visit.startsAt, zone = destination?.timezone ?: zoneOf(refusal.visit.locationId))}",
                                style = Theme.text(14, FontWeight.Bold),
                                color = Theme.inkStrong,
                            )
                            refusal.hours?.let { hours ->
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Glyph("moon-star", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp))
                                    Text(hours.title(person = refusal.visit.assignedName), style = Theme.text(14), color = Theme.inkSoft)
                                }
                            }
                            refusal.message?.let { message ->
                                Text(message, style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive)
                            }
                            val conflicts = refusal.conflicts
                            if (conflicts != null && conflicts.isNotEmpty()) {
                                PlacesConflictWarning(conflicts = conflicts, zone = destination?.timezone, confirmed = confirmed)
                            }
                        }
                    }
                }
                if (overridable) {
                    Text(L("Moved anyway, each visit is marked as double-booked or outside opening hours."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
    }
}

// MARK: - Cancel

/** Cancel one visit at a paused location: the usual cancel (no late fee), with a notice to the family. */
@Composable
private fun PlacesCancelRelocationSheet(
    visit: PlacesRelocationVisit,
    `when`: String,
    onDone: () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    fun confirm() {
        working = true
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>("appointmentId" to visit.id)
                reason.value.nonEmpty?.let { body["reason"] = it }
                API.send("POST", "/provider-locations/relocate/cancel", body)
                Toast.show(LF("%@’s visit was cancelled. The family was told.", visit.pet))
                announceDataChanged()
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(PlacesLocationRules.errorText(e, name = visit.locationName))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Cancel %@’s visit?", visit.pet),
        subtitle = LF("%@ at %@. The family is told the location no longer takes visits. No late-cancellation fee.", `when`, visit.locationName ?: ""),
        systemImage = "triangle-alert",
        accent = ModalAccent.pink,
        isDirty = reason.value.isNotEmpty(),
        primaryTitle = L("Cancel and notify"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Keep it@location"),
        primaryIcon = "triangle-alert",
        onPrimary = { confirm() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        PMTextField(
            L("Message to the family"),
            text = reason,
            prompt = L("e.g. We moved to our Polanco location — call us to book again."),
            optional = true,
            multiline = true,
        )
    }
}
