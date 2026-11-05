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

// STUB: Modules/Schedule/SchedBookVisitScreen.swift
@Composable
fun BookVisitScreen(prefill: BookingPrefill, modifier: Modifier = Modifier) = ShellStub("BookVisitScreen", modifier)

// STUB: Modules/Schedule/ScheduleScreen.swift
@Composable
fun ScheduleScreen(filter: ScheduleFilter = ScheduleFilter(), modifier: Modifier = Modifier) = ShellStub("ScheduleScreen", modifier)

// STUB: Modules/Schedule/AppointmentsListScreen.swift
@Composable
fun AppointmentsListScreen(modifier: Modifier = Modifier) = ShellStub("AppointmentsListScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
