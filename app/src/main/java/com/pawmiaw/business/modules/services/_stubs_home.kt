package com.pawmiaw.business.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import java.time.Instant
import java.util.UUID

//
//  Stand-ins for the Services symbols Home uses (ported in parallel by the Services job).
//  Signatures only; deleted at merge.
//




// STUB: Modules/Services/SvcCommon.swift
object SvcRefusals {
    val grooming: Map<String, String> = emptyMap()
    val homeVisits: Map<String, String> = emptyMap()
}









// STUB: Modules/Services/SvcCommon.swift
@Composable
fun SvcStartGateSheet(request: SvcStartGateRequest, onDone: () -> Unit, modifier: Modifier = Modifier) {}




// STUB: Modules/Services/SvcVisitSheets.swift
data class SvcReportPet(val id: String, val name: String)


// STUB: Modules/Services/SvcVisitSheets.swift
@Composable
fun SvcReportSheet(target: SvcReportTarget, onSaved: () -> Unit = {}, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/HomeVisitsSheets.swift
@Composable
fun SvcStayMoveSheet(item: SvcHomeVisitItem, move: String, timeZone: String?, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/HomeVisitsSheets.swift
@Composable
fun SvcMoveDaySheet(date: String, people: List<SvcHomePerson>, members: List<SvcMember>, initialFrom: String?, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/HomeVisitsScreen.swift
object SvcHomeRules {
    fun late(item: SvcHomeVisitItem, now: Instant, lateAfter: Int, isToday: Boolean): Pair<Boolean, Int> = false to 0
    fun runningLong(item: SvcHomeVisitItem, now: Instant, lateAfter: Int): Boolean = false
    fun finishesHere(category: String?): Boolean = false
}

