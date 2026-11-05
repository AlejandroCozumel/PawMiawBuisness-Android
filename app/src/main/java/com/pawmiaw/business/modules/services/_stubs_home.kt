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
object SvcAPI {
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T =
        TODO("STUB: SvcCommon.swift")
}

// STUB: Modules/Services/SvcCommon.swift
fun svcCode(error: Throwable): String? = (error as? APIError)?.code

// STUB: Modules/Services/SvcCommon.swift
fun svcMessage(error: Throwable, codes: Map<String, String> = emptyMap()): String = errorText(error)

// STUB: Modules/Services/SvcCommon.swift
object SvcRefusals {
    val grooming: Map<String, String> = emptyMap()
    val homeVisits: Map<String, String> = emptyMap()
}

// STUB: Modules/Services/SvcCommon.swift
fun svcOpen(link: String?) {}

// STUB: Modules/Services/SvcCommon.swift (members of `SvcGroomStage`; the object itself is `_stubs_models_b.kt`'s)
fun SvcGroomStage.shown(stage: String, simple: Boolean): String = stage

// STUB: Modules/Services/SvcCommon.swift
fun SvcGroomStage.visible(simple: Boolean): List<String> = emptyList()

// STUB: Modules/Services/SvcCommon.swift
fun SvcGroomStage.label(stage: String, simple: Boolean): String = stage

// STUB: Modules/Services/SvcCommon.swift
fun SvcGroomStage.next(stage: String, simple: Boolean): String? = null

// STUB: Modules/Services/SvcCommon.swift
fun SvcGroomStage.moveLabel(to: String, from: String, simple: Boolean): String = to

// STUB: Modules/Services/SvcCommon.swift
suspend fun svcLoadMembers(workspace: Workspace): List<SvcMember> = emptyList()

// STUB: Modules/Services/SvcCommon.swift
class SvcStartGateRequest(val code: String, val petName: String, val run: suspend (Map<String, Any?>) -> Unit) {
    val id: UUID = UUID.randomUUID()
}

// STUB: Modules/Services/SvcCommon.swift
@Composable
fun SvcStartGateSheet(request: SvcStartGateRequest, onDone: () -> Unit, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/SvcCommon.swift
fun svcIsStartGate(error: Throwable): String? = null



// STUB: Modules/Services/SvcVisitSheets.swift
data class SvcReportPet(val id: String, val name: String)

// STUB: Modules/Services/SvcVisitSheets.swift
class SvcReportTarget(val subtitle: String, val pets: List<SvcReportPet>, val canWrite: Boolean) {
    val id: UUID = UUID.randomUUID()
}

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

// STUB: Modules/Services/GroupsSession.swift
@Composable
fun SvcSessionScreen(sessionId: String, modifier: Modifier = Modifier) {}
