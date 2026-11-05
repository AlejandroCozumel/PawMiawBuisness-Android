package com.pawmiaw.business.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier

//
//  Stand-ins for the Schedule symbols the agenda job uses from the visit job's files (ported in
//  parallel). Signatures only; deleted at merge.
//

// STUB: Modules/Schedule/SchedGroomRequest.swift
@Composable
fun SchedBookingGroomRequests(
    pets: List<Pair<String, String>>,
    values: MutableState<Map<String, SchedGroomInput>>,
    familyOnApp: Boolean,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Schedule/SchedGroomRequest.swift (`static func payload`)
object SchedBookingGroomRequests {
    fun payload(values: Map<String, SchedGroomInput>, petIds: List<String>): List<Map<String, Any?>> = emptyList()
}

// STUB: Modules/Schedule/SchedVisitSheets.swift
@Composable
fun SchedCheckInSheet(
    appointment: SchedAppointment,
    canStart: Boolean,
    onGate: (SchedGatePrompt) -> Unit = {},
    modifier: Modifier = Modifier,
) {}
