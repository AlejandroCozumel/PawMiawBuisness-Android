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

// STUB: Modules/Clients/ClientDetailScreen.swift
@Composable
fun ClientDetailScreen(clientId: String, modifier: Modifier = Modifier) = ShellStub("ClientDetailScreen", modifier)

// STUB: Modules/Clients/ClientsListScreen.swift
@Composable
fun ClientsScreen(modifier: Modifier = Modifier) = ShellStub("ClientsScreen", modifier)

// STUB: Modules/Clients/ClientsListScreen.swift
@Composable
fun PatientsScreen(modifier: Modifier = Modifier) = ShellStub("PatientsScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
