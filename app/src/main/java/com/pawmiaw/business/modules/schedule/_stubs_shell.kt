package com.pawmiaw.business.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Schedule (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Schedule/AppointmentDetailScreen.swift
@Composable
fun AppointmentDetailScreen(appointmentId: String, modifier: Modifier = Modifier) = ShellStub("AppointmentDetailScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
