package com.pawmiaw.business.places

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
//  PlacesLocations.kt (iOS PlacesLocations.swift)
//
//  Settings → Locations ("Sedes"), web provider.locations.tsx →
//  components/app/locations/locations-page.tsx: the places the business works
//  from. A card says where the place is, its clock, who works there and what it
//  has (each a way in), and what still holds it open. How many can be active is
//  the plan's: past it the add button says what to do (a "Sede adicional",
//  another plan, or talk to us) and opens the web for the plan; locations the
//  plan paused are listed with their state. The main location is never
//  archived, and no location is archived while it still holds something.
//  Opened from the switcher's "Add a location" (`LocationsScreen(add = true)`,
//  PlacesSwitcher.kt), it starts on the new location's sheet — or the
//  plan's message — once the list is known.
//

@Composable
fun LocationsScreen(
    /**
     * Opened from the switcher's "Add a location": the new location's sheet — or the plan's
     * message when the plan holds no more — once the list (and so the plan) is known.
     */
    add: Boolean = false,
    modifier: Modifier = Modifier,
) {
    providerPage(L("Locations"), root = true, modifier = modifier) {
        PlacesLocationsPage(add = add)
    }
}

/** Where each thing a location has or holds is dealt with. */
enum class PlacesDestination {
    agenda, register, stays, hospital, relocate, staff, rooms, hours;

    val route: ProviderRoute
        get() = when (this) {
            agenda -> ProviderRoute.schedule(ScheduleFilter(view = "list"))
            register -> ProviderRoute.section(ProviderSection.store)
            stays -> ProviderRoute.section(ProviderSection.stays)
            hospital -> ProviderRoute.section(ProviderSection.hospital)
            relocate -> ProviderRoute.section(ProviderSection.relocate)
            staff -> ProviderRoute.section(ProviderSection.staff)
            rooms -> ProviderRoute.section(ProviderSection.rooms)
            hours -> ProviderRoute.section(ProviderSection.availability)
        }
}

sealed class PlacesLocationSheetKind {
    data object new : PlacesLocationSheetKind()
    data class edit(val location: PlacesLocation) : PlacesLocationSheetKind()
    data class archive(val location: PlacesLocation) : PlacesLocationSheetKind()
    data class primary(val location: PlacesLocation) : PlacesLocationSheetKind()

    val id: String
        get() = when (this) {
            new -> "new"
            is edit -> "edit-${location.id}"
            is archive -> "archive-${location.id}"
            is primary -> "primary-${location.id}"
        }
}

/** After any change to the locations: the switcher, every screen, this list. */
suspend fun placesLocationsChanged() {
    Workspace.shared.refresh()
    announceDataChanged()
}

/**
 * SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)], spacing:)`
 * over a few cells: as many columns of at least `minimum` as fit, each row top-aligned.
 */
@Composable
fun PlacesGrid(minimum: Dp, spacing: Dp, modifier: Modifier = Modifier, cells: List<@Composable () -> Unit>) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            cells.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    row.forEach { cell -> Box(Modifier.weight(1f)) { cell() } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** SwiftUI `.allowsHitTesting(false)`: the view is drawn as it is, and taps don't reach it. */
fun Modifier.placesNoHitTesting(off: Boolean): Modifier = if (!off) this else this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** The page's state (the Swift struct's `@State`s and the values read from them). */
@Stable
private class PlacesLocationsState {
    var addHandled by mutableStateOf(false)
    var answer by mutableStateOf<PlacesLocationsAnswer?>(null)
    var loadError by mutableStateOf<String?>(null)
    var planNotice by mutableStateOf(false)
    var showArchived by mutableStateOf(false)
    var sheet by mutableStateOf<PlacesLocationSheetKind?>(null)
    var reactivating by mutableStateOf<String?>(null)

    val all: List<PlacesLocation> get() = answer?.locations ?: emptyList()
    val active: List<PlacesLocation> get() = PlacesLocationRules.active(all)
    val paused: List<PlacesLocation> get() = PlacesLocationRules.paused(all)
    val archived: List<PlacesLocation> get() = PlacesLocationRules.archived(all)
    val plan: PlacesLocationPlan? get() = answer?.plan
    val path: PlacesAddPath get() = PlacesLocationRules.addPath(all, plan)
    val limit: Int? get() = plan?.limit ?: (if (plan?.allowsMultiple ?: false) null else 1)

    /** One place: no "main", no "this device", no order to speak of. */
    val several: Boolean get() = active.size + paused.size > 1

    fun stats(id: String): PlacesLocationStats? = answer?.stats?.firstOrNull { it.locationId == id }
}

@Composable
private fun PlacesLocationsPage(add: Boolean = false) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    val s = remember { PlacesLocationsState() }

    val canEdit = workspace.can(Permission.workspaceEdit)
    // The plan is the owner's.
    val canManagePlan = workspace.current?.canManagePlan ?: workspace.can(Permission.billingManage)

    fun startAdding() {
        if (s.path == PlacesAddPath.add) {
            s.planNotice = false
            s.sheet = PlacesLocationSheetKind.new
        } else {
            s.planNotice = true
        }
    }

    suspend fun load() {
        if (workspace.current == null || !workspace.can(Permission.workspaceEdit)) return
        try {
            s.answer = API.get("/provider-locations", mapOf("includeArchived" to "true", "withStats" to "true"))
            s.loadError = null
            // "Add a location" from the switcher: once the list (and so the plan) is known.
            if (add && !s.addHandled) {
                s.addHandled = true
                startAdding()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (s.answer == null) s.loadError = errorText(e) else Toast.error(e)
        }
    }

    suspend fun reactivate(location: PlacesLocation) {
        s.reactivating = location.id
        try {
            API.patch<PlacesLocation>("/provider-locations/${location.id}", mapOf("status" to "active"))
            Toast.show(LF("%@ is active again.@location", location.name))
            placesLocationsChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is APIError && (e.code == "location_plan_required" || e.code == "location_limit_reached")) {
                s.planNotice = true
            }
            Toast.error(PlacesLocationRules.errorText(e, name = location.name, limit = s.limit, talkToSales = s.plan?.talkToSales ?: false, requiredPlan = s.plan?.requiredPlan))
        } finally {
            s.reactivating = null
        }
    }

    /** The pages of one place follow the location this device works at: switch to it, say so, and go. */
    fun go(location: PlacesLocation, to: PlacesDestination) {
        val fromSheet = s.sheet != null
        s.sheet = null
        if (to != PlacesDestination.relocate && to != PlacesDestination.staff && workspace.multiLocation &&
            workspace.selectedLocation?.id != location.id && workspace.memberLocations.any { it.id == location.id }
        ) {
            workspace.setLocation(location.id)
            Toast.show(LF("Now showing %@.", location.name))
        }
        if (fromSheet) {
            scope.launch {
                delay(400)
                nav.push(to.route)
            }
        } else {
            nav.push(to.route)
        }
    }

    if (workspace.current != null && !canEdit) {
        PageScroll {
            EmptyState(systemImage = "lock", title = L("You do not have access to the locations."), message = L("Locations are changed by someone who can edit the workspace. Ask the owner."), panel = true)
        }
    } else {
        LocationsPageBody(
            s = s,
            canEdit = canEdit,
            canManagePlan = canManagePlan,
            load = { load() },
            startAdding = { startAdding() },
            reactivate = { location -> scope.launch { reactivate(location) } },
            go = { location, to -> go(location, to) },
            openURL = openURL,
        )
    }

    ReloadsOnScope { load() }

    s.sheet?.let { which ->
        key(which.id) {
            AppSheet(onDismissRequest = { s.sheet = null }) {
                when (which) {
                    PlacesLocationSheetKind.new ->
                        PlacesLocationSheet(location = null, active = s.active, all = s.all, upcomingVisits = 0, onPlanRequired = { s.planNotice = true })
                    is PlacesLocationSheetKind.edit ->
                        PlacesLocationSheet(location = which.location, active = s.active, all = s.all, upcomingVisits = s.stats(which.location.id)?.futureAppointments ?: 0, onPlanRequired = { s.planNotice = true })
                    is PlacesLocationSheetKind.archive ->
                        PlacesLocationArchiveSheet(
                            location = which.location,
                            locations = s.all,
                            stats = s.stats(which.location.id),
                            statsLoaded = s.answer?.stats != null,
                            onGo = { go(which.location, it) },
                            onPlanRequired = { s.planNotice = true },
                            onRefresh = { scope.launch { load() } },
                        )
                    is PlacesLocationSheetKind.primary -> PlacesMakePrimarySheet(location = which.location)
                }
            }
        }
    }
}

// MARK: Page

@Composable
private fun LocationsPageBody(
    s: PlacesLocationsState,
    canEdit: Boolean,
    canManagePlan: Boolean,
    load: suspend () -> Unit,
    startAdding: () -> Unit,
    reactivate: (PlacesLocation) -> Unit,
    go: (PlacesLocation, PlacesDestination) -> Unit,
    openURL: (String) -> Unit,
) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val active = s.active
    val paused = s.paused
    val archived = s.archived
    val several = s.several

    @Composable
    fun card(location: PlacesLocation, here: Boolean? = null, onArchive: (() -> Unit)? = null, onReactivate: (() -> Unit)? = null, onMakePrimary: (() -> Unit)? = null) {
        PlacesLocationCard(
            location = location,
            stats = s.stats(location.id),
            several = several,
            here = here ?: (workspace.multiLocation && workspace.selectedLocation?.id == location.id),
            busy = s.reactivating == location.id,
            onEdit = { s.sheet = PlacesLocationSheetKind.edit(location) },
            onGo = { go(location, it) },
            onArchive = onArchive,
            onReactivate = onReactivate,
            onMakePrimary = onMakePrimary,
        )
    }

    PageScroll(refresh = load) {
        LocationsHeader(s = s, canEdit = canEdit, startAdding = startAdding)

        AnimatedVisibility(visible = s.planNotice, enter = fadeIn(), exit = fadeOut()) {
            PlanNoticeCard(s = s, canManagePlan = canManagePlan, openURL = openURL)
        }

        if (s.answer == null && s.loadError == null) {
            LoadingState()
        } else if (s.loadError != null && s.answer == null) {
            EmptyState(
                systemImage = "map-pin",
                title = L("The locations couldn’t load."),
                message = L("Check your connection and try again."),
                actionTitle = L("Try again"),
                action = { scope.launch { load() } },
                accent = Theme.mango,
                panel = true,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Two columns either way: several places side by side, or the one place beside what belongs to the whole business.
                val cells = mutableListOf<@Composable () -> Unit>()
                active.forEach { location ->
                    cells.add {
                        card(
                            location,
                            onArchive = { s.sheet = PlacesLocationSheetKind.archive(location) },
                            onMakePrimary = if (location.primary || active.size < 2) null else { { s.sheet = PlacesLocationSheetKind.primary(location) } },
                        )
                    }
                }
                if (!several) cells.add { SharedByAll(several = false) }
                PlacesGrid(minimum = 340.dp, spacing = 12.dp, cells = cells)
                Text(
                    (if (active.size == 1) L("1 active location") else LF("%d active locations", active.size)) + (if (several) " · " + L("The main one comes first.") else ""),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (paused.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("circle-pause", 16.dp, tint = Theme.inkSoft)
                        Text(LF("Paused (%d)", paused.size).uppercase(appLocale), style = brandText(12, FontWeight.ExtraBold).copy(letterSpacing = 1.6.sp), color = Theme.inkSoft)
                    }
                    Text(
                        L("Over the plan’s limit. Their history stays, but they take no new visits, shifts or stays. They come back on their own when the plan holds them again."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    PlacesGrid(
                        minimum = 340.dp,
                        spacing = 12.dp,
                        modifier = Modifier.padding(top = 8.dp),
                        cells = paused.map { location ->
                            @Composable {
                                card(location, here = false, onArchive = { s.sheet = PlacesLocationSheetKind.archive(location) }, onReactivate = { reactivate(location) })
                            }
                        },
                    )
                }
            }

            if (archived.isNotEmpty()) {
                val turn by animateFloatAsState(if (s.showArchived) 0f else -90f, tween(250, easing = FastOutSlowInEasing), label = "archived chevron")
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier
                            .heightIn(min = 40.dp)
                            .semantics { selected = s.showArchived }
                            .plainButton { s.showArchived = !s.showArchived }
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
                        Text(LF("Archived (%d)", archived.size).uppercase(appLocale), style = brandText(12, FontWeight.ExtraBold).copy(letterSpacing = 1.6.sp), color = Theme.inkSoft)
                    }
                    AnimatedVisibility(visible = s.showArchived, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        PlacesGrid(
                            minimum = 340.dp,
                            spacing = 12.dp,
                            cells = archived.map { location ->
                                @Composable { card(location, here = false, onReactivate = { reactivate(location) }) }
                            },
                        )
                    }
                }
            }

            if (several) SharedByAll(several = true)
        }
    }
}

/** The web's `ProviderModuleHeader`: MapPin on the default blue, "Add a location", three numbers. */
@Composable
private fun LocationsHeader(s: PlacesLocationsState, canEdit: Boolean, startAdding: () -> Unit) {
    val answer = s.answer
    val active = s.active
    val paused = s.paused
    val archived = s.archived
    val limit = s.limit
    val path = s.path
    ModuleHeader(
        icon = "map-pin",
        title = L("Locations"),
        subtitle = if (s.several) {
            L("Each location keeps its own hours, rooms, registers, visits and stays. Clients, the catalog, prices and the team are shared by all of them.")
        } else {
            L("Where the business works from: its address, its clock and how to reach it. Add another location when you open a second place.")
        },
        stats = listOf(
            ModuleStat(
                "active", label = L("Active@locations"), value = if (answer == null) "—" else "${active.size}",
                hint = if (paused.isEmpty()) null else LF("%d paused", paused.size), icon = "map-pin", accent = Theme.blue,
            ),
            ModuleStat(
                "archived", label = L("Archived@locations"), value = if (answer == null) "—" else "${archived.size}",
                hint = if (archived.isEmpty()) null else (if (s.showArchived) L("Hide them") else L("Show them")),
                icon = "archive", accent = Theme.mango, active = s.showArchived,
                action = if (archived.isEmpty()) null else { { s.showArchived = !s.showArchived } },
            ),
            ModuleStat(
                "plan", label = L("Your plan"), value = if (answer == null) "—" else (limit?.let { LF("%d of %d", active.size, it) } ?: L("Several")),
                hint = if (answer == null) null else (if (path == PlacesAddPath.add) L("Active locations it holds") else L("All in use")),
                icon = "sparkles", accent = Theme.pink,
                action = if (path == PlacesAddPath.add) null else { { s.planNotice = !s.planNotice } },
            ),
        ),
        storageKey = "locations",
        actions = if (canEdit && answer != null) {
            { PMButton(L("Add a location"), systemImage = "plus", action = startAdding) }
        } else null,
    )
}

/** What a location does not keep for itself, and where that is set (web `SharedByAll`). */
@Composable
private fun SharedByAll(several: Boolean) {
    val nav = LocalNavigator.current
    Card(padding = 20.dp, spacing = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (several) L("Shared by every location") else L("Set for the whole business"), style = Theme.heading(18), color = Theme.inkStrong)
            Text(
                L("The legal name and tax details, the currency and tax, the services you offer, the catalog and its prices are the business’s, not a location’s. They are in Settings and the Catalog."),
                style = Theme.text(14),
                color = Theme.inkSoft,
            )
        }
        PMButton(L("Open Settings"), variant = ButtonVariant.outline, size = ButtonSize.compact) {
            nav.push(ProviderRoute.section(ProviderSection.settings))
        }
    }
}

// MARK: The plan's message

/** What the add button says when the plan holds no more (web `PlanNotice`): pink 10%, a white disc with the sparkles. */
@Composable
private fun PlanNoticeCard(s: PlacesLocationsState, canManagePlan: Boolean, openURL: (String) -> Unit) {
    val path = s.path
    val limit = s.limit
    val title: String
    val body: String
    when (path) {
        PlacesAddPath.buyAddon -> {
            title = L("Add a location to your plan")
            body = LF("Your plan holds %d active locations. An extra location adds one more, with its own users, from Subscription.", limit ?: 1)
        }
        PlacesAddPath.talkToSales -> {
            title = L("Your plan is at its locations")
            body = LF("Your plan holds %d active locations, the most you can add here. For more, Enterprise is a quote from our team.", limit ?: 1)
        }
        else -> {
            title = L("Another location needs the Business plan")
            body = L("Starter is for one place. On Business each location keeps its own hours, rooms, registers and schedule, and every location shares the same clients.")
        }
    }
    val ask = if (canManagePlan || path == PlacesAddPath.talkToSales) "" else " " + L("Ask the workspace owner to change the plan.")
    Card(tint = Theme.pink) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Theme.surface, CircleShape), contentAlignment = Alignment.Center) {
                Glyph("sparkles", 20.dp, tint = Theme.blue)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Theme.heading(18), color = Theme.inkStrong)
                Text(body + ask, style = Theme.text(14), color = Theme.inkSoft)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PMButton(L("Not now"), variant = ButtonVariant.outline, size = ButtonSize.compact) { s.planNotice = false }
            if (path == PlacesAddPath.talkToSales) {
                PMButton(L("Talk to us"), systemImage = "mail", size = ButtonSize.compact) {
                    val subject = Uri.encode(L("PawMiaw Enterprise"))
                    openURL("mailto:${PlacesLocationRules.supportEmail}?subject=$subject")
                }
            } else if (canManagePlan) {
                // Plans are bought on the web: the app opens the business's Subscription page there.
                PMButton(if (path == PlacesAddPath.buyAddon) L("Go to Subscription") else L("See plans"), systemImage = "sparkles", size = ButtonSize.compact) {
                    openURL(APIConfig.webURL("provider/billing").toString())
                }
            }
        }
    }
}

// MARK: - A location's card

/**
 * One location (web `LocationCard`): where it is, its clock and contact, who works
 * there and what it has — each with a way in — what it still holds, and its actions.
 */
@Composable
private fun PlacesLocationCard(
    location: PlacesLocation,
    stats: PlacesLocationStats?,
    /** The business has more than one open location: "Main", "This device" and the links that switch place mean something. */
    several: Boolean,
    here: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onGo: (PlacesDestination) -> Unit,
    onArchive: (() -> Unit)?,
    onReactivate: (() -> Unit)?,
    onMakePrimary: (() -> Unit)?,
) {
    var mainOpen by remember { mutableStateOf(false) }
    val open = location.isActive
    val cardShape = RoundedCornerShape(Theme.cardRadius)

    Card(
        padding = 16.dp,
        spacing = 12.dp,
        modifier = Modifier
            .then(if (here) Modifier.border(1.dp, Theme.blue, cardShape) else Modifier)
            .alpha(if (location.isArchived || location.isPaused) 0.85f else 1f),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val photoLabel = LF("Photo of %@", location.name)
            PlacesLocationThumb(
                photoUrl = location.photoUrl,
                isPrimary = location.primary,
                size = if (location.photoUrl == null) 44.dp else 56.dp,
                modifier = Modifier.semantics { contentDescription = photoLabel },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(location.name, style = Theme.heading(20), color = Theme.inkStrong)
                if ((several && location.primary) || here || location.isArchived || location.isPaused) {
                    FlowLayout(spacing = 6.dp) {
                        if (several && location.primary) TagChip(text = L("Main"), linked = true)
                        if (here) Chip(text = L("This device"), tone = Chip.Tone.info)
                        if (location.isArchived) Chip(text = L("Archived@location-one"), tone = Chip.Tone.muted)
                        if (location.isPaused) Chip(text = L("Paused"), tone = Chip.Tone.warn)
                    }
                }
                // A long address wraps, three lines at most; the whole of it is in the sheet.
                Text(location.address.nonEmpty ?: L("No address yet"), style = Theme.text(14), color = Theme.inkSoft, maxLines = 3)
            }
        }

        FlowLayout(spacing = 16.dp, lineSpacing = 4.dp) {
            LocationFact("earth", PlacesTimeZones.cityName(location.zone) + (if (open) " · " + LF("%@ now", PlacesLocationRules.clock(location.zone)) else ""))
            val days = location.openDays
            if (days != null && !location.isArchived) {
                LocationFact("clock-3", if (days == 1) L("1 day open") else LF("%d days open", days))
            }
            location.phone.nonEmpty?.let { LocationFact("phone", it) }
            location.email.nonEmpty?.let { LocationFact("mail", it) }
        }

        if (stats != null && !location.isArchived) {
            FlowLayout(spacing = 8.dp) {
                CountLink("users-round", LP(stats.staff ?: 0, "%d person", "%d people"), enabled = true) { onGo(PlacesDestination.staff) }
                CountLink("door-open", LP(stats.rooms ?: 0, "%d room", "%d rooms"), enabled = open) { onGo(PlacesDestination.rooms) }
                CountLink("store", LP(stats.registers ?: 0, "%d register", "%d registers"), enabled = open) { onGo(PlacesDestination.register) }
                if ((stats.stayUnits ?: 0) > 0) {
                    CountLink("bed-double", if ((stats.stayUnits ?: 0) == 1) L("1 kennel or suite") else LF("%d kennels and suites", stats.stayUnits ?: 0), enabled = open) { onGo(PlacesDestination.stays) }
                }
            }
        }

        val holds = PlacesLocationRules.blockers(stats)
        if (holds.isNotEmpty()) {
            FlowLayout(spacing = 12.dp, lineSpacing = 4.dp) {
                holds.forEach { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(if (entry.blocker == PlacesLocationBlocker.hospitalPatients) "bed-double" else entry.blocker.systemImage, 14.dp, tint = Theme.inkSoft)
                        Text(holdText(entry.blocker, entry.count), style = brandText(12, FontWeight.Bold), color = Theme.inkSoft)
                    }
                }
            }
        }

        if (several && location.primary && open) {
            val turn by animateFloatAsState(if (mainOpen) 180f else 0f, tween(200), label = "main chevron")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.blue, 7.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .semantics { selected = mainOpen }
                        .plainButton { mainOpen = !mainOpen },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("star", 14.dp, tint = Theme.blue)
                    Text(L("What being the main location means"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                    Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
                }
                AnimatedVisibility(visible = mainOpen, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(200)) + shrinkVertically(tween(200))) {
                    PlacesMainMeans(Modifier.padding(top = 4.dp, bottom = 6.dp))
                }
            }
        }

        // SwiftUI `Spacer(minLength: 0)` between the stack's rows.
        Spacer(Modifier.height(0.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RowDivider()
            FlowLayout(spacing = 8.dp) {
                PMButton(L("Edit"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onEdit)
                if (open) {
                    PMButton(L("Opening hours"), systemImage = "clock-3", variant = ButtonVariant.outline, size = ButtonSize.compact) { onGo(PlacesDestination.hours) }
                }
                if (onMakePrimary != null) {
                    PMButton(L("Make main"), systemImage = "star", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onMakePrimary)
                }
                if (onArchive != null) {
                    PMButton(L("Archive"), systemImage = "archive", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onArchive)
                }
                if (onReactivate != null) {
                    PMButton(L("Make active again"), systemImage = "rotate-ccw", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busy, action = onReactivate)
                }
            }
        }
    }
}

@Composable
private fun LocationFact(icon: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 14.dp, tint = Theme.inkSoft)
        Text(text, style = Theme.text(14), color = Theme.inkSoft, maxLines = 1)
    }
}

/** A number of something a location has, that opens it (or just says it, when the place takes nothing new). */
@Composable
private fun CountLink(icon: String, text: String, enabled: Boolean, onGo: () -> Unit) {
    FilterChip(title = text, isSelected = false, tone = Theme.inkSoft, icon = icon, modifier = Modifier.placesNoHitTesting(!enabled)) {
        if (enabled) onGo()
    }
}

private fun holdText(blocker: PlacesLocationBlocker, count: Int): String = when (blocker) {
    PlacesLocationBlocker.futureAppointments -> if (count == 1) L("1 visit to come") else LF("%d visits to come", count)
    PlacesLocationBlocker.openRegisterSessions -> if (count == 1) L("Register open") else LF("%d registers open", count)
    PlacesLocationBlocker.activeStays -> if (count == 1) L("1 stay booked or in") else LF("%d stays booked or in", count)
    PlacesLocationBlocker.hospitalPatients -> if (count == 1) L("1 hospital patient") else LF("%d hospital patients", count)
}

/** What being the main location drives, said the same way on its card and before changing it. */
@Composable
fun PlacesMainMeans(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            L("Families book here online unless they choose another location."),
            L("Its clock is the business’s day for what belongs to every location: Finance, follow-ups and reminders."),
            L("Its address and phone are the business’s on documents that belong to no single location."),
            L("It always stays active if the plan ever holds fewer locations."),
        ).forEach { line ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph("star", 14.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp))
                Text(line, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
    }
}

// MARK: - Make it the main one

/** The web's ProviderConfirmSheet: pink, the triangle, what the main location drives. */
@Composable
private fun PlacesMakePrimarySheet(location: PlacesLocation) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    fun confirm() {
        working = true
        scope.launch {
            try {
                API.send("POST", "/provider-locations/${location.id}/make-primary")
                Toast.show(LF("%@ is now the main location.", location.name))
                placesLocationsChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(PlacesLocationRules.errorText(e, name = location.name))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Make %@ the main location?", location.name),
        subtitle = L("Everything already booked keeps its place. This changes what the business uses by default:"),
        systemImage = "triangle-alert",
        accent = ModalAccent.pink,
        primaryTitle = L("Make it the main one"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Not now"),
        primaryIcon = "triangle-alert",
        onPrimary = { confirm() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        PlacesMainMeans()
    }
}

// MARK: - Archive, or why it can't be yet

@Composable
private fun PlacesLocationArchiveSheet(
    location: PlacesLocation,
    locations: List<PlacesLocation>,
    stats: PlacesLocationStats?,
    statsLoaded: Boolean,
    onGo: (PlacesDestination) -> Unit,
    onPlanRequired: () -> Unit,
    onRefresh: () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val refusal = PlacesLocationRules.archiveRefusal(location, locations = locations, stats = stats)
    val detents = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large))

    fun confirm() {
        working = true
        scope.launch {
            try {
                API.patch<PlacesLocation>("/provider-locations/${location.id}", mapOf("status" to "archived"))
                Toast.show(LF("%@ archived.", location.name))
                placesLocationsChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is APIError) {
                    if (e.code == "location_plan_required" || e.code == "location_limit_reached") onPlanRequired()
                    // It took something on since the page loaded: read it again, so the sheet says what.
                    if (e.code == "location_in_use") onRefresh()
                }
                Toast.error(PlacesLocationRules.errorText(e, name = location.name))
            } finally {
                working = false
            }
        }
    }

    if (refusal != null) {
        // Why it can't be yet (web: butter, the lock, the way to each thing in the way).
        ProviderModal(
            title = refusalTitle(refusal, location),
            subtitle = refusalText(refusal, location),
            systemImage = "lock",
            accent = ModalAccent.butter,
            cancelTitle = L("Close"),
            modifier = detents,
            content = if (refusal == PlacesArchiveRefusal.inUse) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlacesLocationRules.blockers(stats).forEach { entry ->
                            BlockerRow(location, entry.blocker, entry.count, onGo)
                        }
                    }
                }
            } else null,
        )
    } else {
        // The web's ProviderConfirmSheet: pink, the triangle, the danger action.
        ProviderModal(
            title = LF("Archive %@?", location.name),
            subtitle = L("It leaves the location menu and can’t take new visits, stays or sales. Its history stays, and you can make it active again later."),
            systemImage = "triangle-alert",
            accent = ModalAccent.pink,
            primaryTitle = L("Archive location"),
            primaryVariant = ButtonVariant.destructive,
            isWorking = working,
            cancelTitle = L("Keep it@location"),
            primaryIcon = "triangle-alert",
            onPrimary = { confirm() },
            modifier = detents,
        ) {
            if (stats != null) {
                consequences(stats).forEach { (icon, text) ->
                    PlacesConsequenceRow(systemImage = icon, text = text)
                }
                PlacesConsequenceRow(systemImage = "history", text = L("Its visits, sales and Cortes del día stay in the history of every screen."))
            } else if (!statsLoaded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                    Text(L("Checking what it still holds…"), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
    }
}

private fun refusalTitle(refusal: PlacesArchiveRefusal, location: PlacesLocation): String = when (refusal) {
    PlacesArchiveRefusal.primary -> L("The main location can’t be archived")
    PlacesArchiveRefusal.lastActive -> L("A business needs one active location")
    PlacesArchiveRefusal.inUse -> LF("%@ can’t be archived yet", location.name)
}

private fun refusalText(refusal: PlacesArchiveRefusal, location: PlacesLocation): String = when (refusal) {
    PlacesArchiveRefusal.primary -> LF("Families book here by default and the business’s clock and contact are its own. Make another location the main one first; then %@ can be archived.", location.name)
    PlacesArchiveRefusal.lastActive -> LF("%@ is the only one. Add another location first, or keep working from this one.", location.name)
    PlacesArchiveRefusal.inUse -> L("It still holds what is below. Finish or move each one first: nothing is cancelled or moved for you.")
}

@Composable
private fun BlockerRow(location: PlacesLocation, blocker: PlacesLocationBlocker, count: Int, onGo: (PlacesDestination) -> Unit) {
    // A paused location's visits are moved from "Por reubicar", not its Agenda.
    val to: PlacesDestination
    val title: String
    val action: String
    when (blocker) {
        PlacesLocationBlocker.futureAppointments -> {
            to = if (location.isPaused) PlacesDestination.relocate else PlacesDestination.agenda
            title = if (count == 1) L("1 visit still to come") else LF("%d visits still to come", count)
            action = if (location.isPaused) L("Open “To relocate”") else L("Open the Schedule")
        }
        PlacesLocationBlocker.openRegisterSessions -> {
            to = PlacesDestination.register
            title = if (count == 1) L("1 register shift open") else LF("%d register shifts open", count)
            action = L("Open the register")
        }
        PlacesLocationBlocker.activeStays -> {
            to = PlacesDestination.stays
            title = if (count == 1) L("1 stay booked or in progress") else LF("%d stays booked or in progress", count)
            action = L("Open Stays")
        }
        PlacesLocationBlocker.hospitalPatients -> {
            to = PlacesDestination.hospital
            title = if (count == 1) L("1 patient in the hospital") else LF("%d patients in the hospital", count)
            action = L("Open the Hospital")
        }
    }
    // A row that opens where it is dealt with (web: a 36 lime disc, the count, the way there in blue).
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton { onGo(to) }
            .background(Theme.surface, box)
            .border(1.5.dp, Theme.borderSoft, box)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlacesIconBadge(systemImage = blocker.systemImage, size = 36.dp, washPercent = 24.0, tone = Theme.lime)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            Text(action, style = Theme.text(14), color = Theme.blue)
        }
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

/** What archiving a location that holds nothing takes with it: (icon, text) lines. */
private fun consequences(stats: PlacesLocationStats): List<Pair<String, String>> {
    val lines = mutableListOf<Pair<String, String>>()
    val onlyHere = stats.staffOnlyHere ?: 0
    if (onlyHere > 0) {
        lines.add(
            "users-round" to (if (onlyHere == 1) L("1 person works only here. They will work at every location until you place them at another.")
            else LF("%d people work only here. They will work at every location until you place them at another.", onlyHere))
        )
    }
    val things = mutableListOf<String>()
    stats.rooms?.takeIf { it > 0 }?.let { things.add(LP(it, "%d room", "%d rooms")) }
    stats.registers?.takeIf { it > 0 }?.let { things.add(LP(it, "%d register", "%d registers")) }
    stats.stayUnits?.takeIf { it > 0 }?.let { things.add(if (it == 1) L("1 kennel or suite") else LF("%d kennels and suites", it)) }
    if (things.isNotEmpty()) {
        lines.add("door-open" to LF("What it has (%@) leaves with it, and is there again if you make it active.", things.joinToString(", ")))
    }
    return lines
}
