package com.pawmiaw.business.places

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Places (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Places/PlacesRooms.swift
@Composable
fun RoomsScreen(modifier: Modifier = Modifier) = ShellStub("RoomsScreen", modifier)

// STUB: Modules/Places/PlacesLocations.swift
@Composable
fun LocationsScreen(add: Boolean = false, modifier: Modifier = Modifier) = ShellStub("LocationsScreen", modifier)

// STUB: Modules/Places/PlacesRelocate.swift
@Composable
fun RelocateScreen(modifier: Modifier = Modifier) = ShellStub("RelocateScreen", modifier)

// STUB: Modules/Places/PlacesSwitcher.swift
enum class PlacesSwitcherDestination { addLocation, locations, settings, relocate }

// STUB: Modules/Places/PlacesSwitcher.swift
@Composable
fun PlacesSwitcherMenu(
    onOpen: ((PlacesSwitcherDestination) -> Unit)? = null,
    sidebar: Boolean = false,
    modifier: Modifier = Modifier,
) {}

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
