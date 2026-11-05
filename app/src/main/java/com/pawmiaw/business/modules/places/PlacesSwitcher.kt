package com.pawmiaw.business.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.team.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  PlacesSwitcher.kt (iOS PlacesSwitcher.swift)
//
//  The place switcher's whole menu (web locations/workspace-switcher.tsx), as
//  a drop-in for the shell's `LocationMenu`: the business's locations — with
//  one, that location as the current place; with several, "All locations"
//  for owners and admins, each location (paused ones shown but not chosen,
//  "Register open" while a register is open there) and "To relocate (n)" —
//  then "Add a location" for owners and admins (or what the plan asks for
//  instead: an extra location, the Business plan, or talk to us), "See
//  locations" and "Business settings" for whoever the sidebar lets into those
//  pages, and the other businesses this person belongs to.
//
//  The pages it opens go through `onOpen` when the host navigates itself (the
//  shell pushes `PlacesSwitcherDestination.route` or shows `.view`);
//  without one they open in a sheet with their own navigation.
//

/** Where the switcher takes someone. */
enum class PlacesSwitcherDestination {
    /** Locations, opened on its "add a location" sheet (or the plan's message). */
    addLocation,
    locations,
    settings,
    relocate;

    val id: PlacesSwitcherDestination get() = this

    /** The route for a host that pushes routes (adding a location has none: use `view`). */
    val route: ProviderRoute
        get() = when (this) {
            addLocation, locations -> ProviderRoute.section(ProviderSection.locations)
            settings -> ProviderRoute.section(ProviderSection.settings)
            relocate -> ProviderRoute.section(ProviderSection.relocate)
        }

    /** The screen itself. */
    @Composable
    fun view() {
        when (this) {
            addLocation -> LocationsScreen(add = true)
            locations -> LocationsScreen()
            settings -> SettingsScreen()
            relocate -> RelocateScreen()
        }
    }
}

private suspend fun <T> switcherAttempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

@Composable
fun PlacesSwitcherMenu(
    /** The host opens the page; null = the switcher opens it in a sheet. */
    onOpen: ((PlacesSwitcherDestination) -> Unit)? = null,
    /** The sidebar's header (web workspace-switcher): the business's mark, its name, the place. */
    sidebar: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    var toRelocate by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<PlacesSwitcherDestination?>(null) }
    val scope = rememberCoroutineScope()

    val name = workspace.current?.name.nonEmpty ?: L("Provider workspace")
    val others = workspace.workspaces.filter { it.clinicId != workspace.current?.clinicId }
    // Owners and admins who edit the business add locations, whatever how many it has today.
    val canAddLocations = workspace.can(Permission.workspaceEdit) && workspace.isOwnerOrAdmin
    val canRelocate = workspace.canSeeAllLocations && workspace.can(Permission.appointmentsView)
    // The pages the sidebar lets this person into.
    val showsLocations = ProviderSection.locations.isVisible(workspace, olfatoShown = false, pharmacyShown = false)
    val showsSettings = ProviderSection.settings.isVisible(workspace, olfatoShown = false, pharmacyShown = false)
    // Owners and admins see every open (and paused) place; everyone else theirs.
    val choices = if (workspace.canSeeAllLocations) {
        Workspace.sorted(workspace.allLocations.filter { it.status == "active" || it.status == "paused" })
    } else workspace.memberLocations
    // A real choice of place: several, and this person may move between them.
    val chooser = workspace.multiLocation && workspace.canSwitchLocation
    val current = if (workspace.allLocationsChosen) null else workspace.selectedLocation
    val placeLabel = if (workspace.allLocationsChosen) L("All locations") else current?.name
    val openShift = (workspace.locationsResponse?.openShiftLocationIds ?: emptyList()).toSet()
    val actionable = chooser || canAddLocations || showsLocations || showsSettings || others.isNotEmpty()

    fun open(destination: PlacesSwitcherDestination) {
        if (onOpen != null) onOpen(destination) else opened = destination
    }

    fun choose(location: ProviderLocation) {
        if (location.status != "active") return
        if (!workspace.allLocationsChosen && location.id == workspace.selectedLocation?.id) return
        workspace.setLocation(location.id)
        Toast.show(LF("Now showing %@.", location.name))
    }

    fun chooseAll() {
        if (workspace.allLocationsChosen) return
        workspace.setAllLocations()
        Toast.show(L("Now showing every location."))
    }

    fun chooseWorkspace(other: ProviderWorkspace) {
        scope.launch {
            workspace.choose(other)
            if (workspace.current?.clinicId == other.clinicId) {
                Toast.show(LF("Now working in %@.", other.name))
            } else {
                Toast.error(L("That workspace couldn’t open. Try again."))
            }
        }
    }

    // MARK: The plan's way to another location
    val addPath: PlacesAddPath = run {
        val active = workspace.activeLocations
        val plan = workspace.locationsResponse?.plan
        val canAdd = active.isEmpty() || (plan?.let { it.canAdd ?: it.allowsMultiple ?: false } ?: false)
        when {
            canAdd -> PlacesAddPath.add
            plan?.addonAvailable == true -> PlacesAddPath.buyAddon
            plan?.talkToSales == true -> PlacesAddPath.talkToSales
            else -> PlacesAddPath.upgrade
        }
    }

    val menu: @Composable AppMenuScope.() -> Unit = {
        section(if (chooser) LF("Locations of %@", name) else LF("Location of %@", name))
        if (chooser) {
            if (workspace.canSeeAllLocations) {
                SwitcherMenuRow(
                    title = L("All locations"),
                    subtitle = L("Finance, invoices and clients across the business"),
                    icon = if (workspace.allLocationsChosen) "check" else "layers",
                ) {
                    dismiss()
                    chooseAll()
                }
            }
            choices.forEach { location ->
                val chosen = !workspace.allLocationsChosen && location.id == workspace.selectedLocation?.id
                SwitcherLocationRow(location, chosen = chosen, openShift = openShift, enabled = location.status != "paused") {
                    dismiss()
                    choose(location)
                }
            }
            if (canRelocate && toRelocate > 0) {
                item(LF("To relocate (%d)", toRelocate), icon = "arrow-right-left") { open(PlacesSwitcherDestination.relocate) }
            }
        } else if (current != null) {
            // The one place: where this device works, not a choice.
            SwitcherLocationRow(current, chosen = true, openShift = openShift, enabled = false) {}
        }
        if (canAddLocations) {
            val path = addPath
            if (path == PlacesAddPath.add) {
                item(L("Add a location"), icon = "plus") { open(PlacesSwitcherDestination.addLocation) }
            } else {
                SwitcherMenuRow(title = addPathTitle(path), subtitle = addPathHint(path), icon = "sparkles") {
                    dismiss()
                    open(PlacesSwitcherDestination.addLocation)
                }
            }
        }
        if (showsLocations) {
            item(L("See locations"), icon = "map") { open(PlacesSwitcherDestination.locations) }
        }
        if (showsSettings) {
            item(L("Business settings"), icon = "settings") { open(PlacesSwitcherDestination.settings) }
        }
        if (others.isNotEmpty()) {
            section(L("Your other businesses"))
            others.forEach { other ->
                item(other.name.nonEmpty ?: L("Provider workspace"), icon = "building-2") { chooseWorkspace(other) }
            }
        }
    }

    if (sidebar) {
        if (actionable) {
            AppMenu(label = { SwitcherSidebarFace(name, placeLabel, chevron = true) }, modifier = modifier.fillMaxWidth(), content = menu)
        } else {
            SwitcherSidebarFace(name, placeLabel, chevron = false, modifier = modifier)
        }
    } else if (actionable) {
        AppMenu(
            label = { SwitcherFace(name, placeLabel, chevron = true) },
            modifier = modifier,
            accessibilityLabel = LF("%@. Change location or workspace", placeLabel?.let { "$name · $it" } ?: name),
            content = menu,
        )
    } else if (placeLabel != null) {
        SwitcherFace(name, placeLabel, chevron = false, modifier = modifier)
    }

    LaunchedEffect("${workspace.current?.clinicId ?: ""}-$canRelocate-${workspace.scopeVersion}") {
        if (!(canRelocate && workspace.multiLocation)) {
            toRelocate = 0
            return@LaunchedEffect
        }
        switcherAttempt { API.get<PlacesRelocations>("/provider-locations/relocate") }?.let { answer ->
            toRelocate = answer.visits?.size ?: 0
        }
    }

    opened?.let { destination ->
        AppSheet(onDismissRequest = { opened = null }) {
            // iOS: a NavigationStack with the page and its routes; its root's bar says "Close".
            Box(Modifier.sheetPresentation()) {
                val navigator = remember { Navigator() }
                NavStack(navigator = navigator, root = { destination.view() }, page = { RouteView(it) })
            }
        }
    }
}

private fun addPathTitle(path: PlacesAddPath): String = when (path) {
    PlacesAddPath.buyAddon -> L("Add a location to your plan")
    PlacesAddPath.talkToSales -> L("More locations: talk to us")
    else -> L("Available on the Business plan")
}

private fun addPathHint(path: PlacesAddPath): String = when (path) {
    PlacesAddPath.buyAddon -> L("An extra location, from Subscription")
    PlacesAddPath.talkToSales -> L("Enterprise is a quote from our team")
    else -> L("Starter is for one place")
}

/** The place as a pill (the top bar), web `MobileSwitcherRow`. */
@Composable
private fun SwitcherFace(name: String, placeLabel: String?, chevron: Boolean, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val capsule = RoundedCornerShape(50)
    Row(
        modifier
            .height(36.dp)
            .background(Theme.surface, capsule)
            .border(1.dp, Theme.borderSoft, capsule)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (workspace.allLocationsChosen) "layers" else "map-pin", 14.dp, tint = Theme.inkSoft)
        Text(
            placeLabel ?: name,
            style = Theme.text(12, FontWeight.SemiBold),
            color = Theme.inkStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (chevron) Glyph("chevrons-up-down", 14.dp, tint = Theme.inkSoft.copy(alpha = 0.7f))
    }
}

/** The sidebar's header row: the mark (40), the name in Fredoka 16, the place under it. */
@Composable
private fun SwitcherSidebarFace(name: String, placeLabel: String?, chevron: Boolean, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Theme.boxRadius))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BusinessMarkView(name = name, logoURL = workspace.current?.logoUrl, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = Theme.heading(16), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (placeLabel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (workspace.allLocationsChosen) "layers" else "map-pin", 12.dp, tint = Theme.inkSoft)
                    Text(placeLabel, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        if (chevron) Glyph("chevrons-up-down", 16.dp, tint = Theme.inkSoft.copy(alpha = 0.7f))
    }
}

/** A location in the menu: its name, "Main", "Paused", its address, and "Register open". */
@Composable
private fun SwitcherLocationRow(location: ProviderLocation, chosen: Boolean, openShift: Set<String>, enabled: Boolean, onClick: () -> Unit) {
    val workspace = Workspace.shared
    val paused = location.status == "paused"
    val tags = listOfNotNull(if (location.isPrimary && workspace.multiLocation) L("Main") else null, if (paused) L("Paused") else null)
    val detail = listOfNotNull(
        if (paused) L("Takes no new visits") else (location.address.nonEmpty ?: L("No address yet")),
        if (openShift.contains(location.id)) L("Register open") else null,
    ).joinToString(" · ")
    SwitcherMenuRow(
        title = (listOf(location.name) + tags).joinToString(" · "),
        subtitle = detail,
        icon = if (chosen) "check" else (if (openShift.contains(location.id)) "circle-dot" else "map-pin"),
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * A menu row with a second line (SwiftUI `Label { Text(title); Text(subtitle) } icon: { … }` in a
 * `Menu`): the kit's row (44 tall, 14 semibold, a 16 icon, the mango press) with the line under
 * the words in 12. Disabled rows fade as the kit's do.
 */
@Composable
private fun SwitcherMenuRow(title: String, subtitle: String, icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = if (pressed) Theme.cocoa else Theme.inkStrong
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Theme.chipRadius))
            .background(if (pressed) Theme.mango else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 16.dp, tint = ink)
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme.text(14, FontWeight.SemiBold), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Theme.text(12), color = if (pressed) Theme.cocoa else Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
