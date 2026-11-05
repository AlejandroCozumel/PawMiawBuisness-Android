package com.pawmiaw.business.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Clients (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Clients/PatientChartScreen.swift
@Composable
fun ClientPetScreen(clientId: String, petId: String, modifier: Modifier = Modifier) = ShellStub("ClientPetScreen", modifier)

// STUB: Modules/Clients/PatientChartScreen.swift
@Composable
fun PatientChartScreen(petId: String, modifier: Modifier = Modifier) = ShellStub("PatientChartScreen", modifier)

// STUB: Modules/Clients/DueScreen.swift
@Composable
fun DueScreen(modifier: Modifier = Modifier) = ShellStub("DueScreen", modifier)

// STUB: Modules/Clients/InvitationsScreen.swift
@Composable
fun InvitationsScreen(clientId: String? = null, modifier: Modifier = Modifier) = ShellStub("InvitationsScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
