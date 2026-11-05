package com.pawmiaw.business.team

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Team (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Team/StaffMemberScreen.swift
@Composable
fun StaffMemberScreen(memberId: String, modifier: Modifier = Modifier) = ShellStub("StaffMemberScreen", modifier)

// STUB: Modules/Team/SettingsScreen.swift
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) = ShellStub("SettingsScreen", modifier)

// STUB: Modules/Team/StaffScreen.swift
@Composable
fun StaffScreen(modifier: Modifier = Modifier) = ShellStub("StaffScreen", modifier)

// STUB: Modules/Team/AvailabilityScreen.swift
@Composable
fun AvailabilityScreen(modifier: Modifier = Modifier) = ShellStub("AvailabilityScreen", modifier)

// STUB: Modules/Team/NotificationSettingsScreen.swift
@Composable
fun NotificationSettingsScreen(modifier: Modifier = Modifier) = ShellStub("NotificationSettingsScreen", modifier)

// STUB: Modules/Team/AccountSecurityScreen.swift
@Composable
fun AccountSecurityScreen(modifier: Modifier = Modifier) = ShellStub("AccountSecurityScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
