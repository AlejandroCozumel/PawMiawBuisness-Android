package com.pawmiaw.business.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import java.time.ZoneId
import java.util.UUID

//
//  Stand-ins for the Services symbols Estética and Paseos y clases read from the files the
//  svc-homevisits job ports (SvcCommon.swift, SvcVisitSheets.swift). Deleted at merge.
//  `SvcReportText` and `SvcGroomStage` are already stood in (`_stubs_models_a.kt`,
//  `_stubs_models_b.kt`), so their other members are extensions here: `SvcGroomStage.label(…)`
//  reads the same as the member it becomes.
//

// MARK: - SvcCommon.swift

// STUB: Modules/Services/SvcCommon.swift
class SvcRefusal(val api: APIError, val body: JSONValue?) : Exception(api.message) {
    val code: String? get() = api.code
}

// STUB: Modules/Services/SvcCommon.swift
object SvcAPI {
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T =
        TODO("STUB: SvcCommon.swift")
}

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER")
fun svcCode(error: Throwable): String? = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER")
fun svcMessage(error: Throwable, codes: Map<String, String> = emptyMap()): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER")
fun svcAPIError(error: Throwable): Throwable = TODO("STUB: SvcCommon.swift")


// STUB: Modules/Services/SvcCommon.swift
val svcLocale: String get() = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER")
fun svcCapitalized(text: String): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER")
fun svcOpen(link: String?) {}

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER")
object SvcTime {
    fun zone(id: String?): ZoneId = TODO("STUB: SvcCommon.swift")
    fun clock(iso: String?, zoneId: String?): String = TODO("STUB: SvcCommon.swift")
    fun day(iso: String?, withYear: Boolean = false): String = TODO("STUB: SvcCommon.swift")
    fun shortDay(iso: String?): String = TODO("STUB: SvcCommon.swift")
    fun dayLong(dayKey: String): String = TODO("STUB: SvcCommon.swift")
    fun waited(since: String?): String = TODO("STUB: SvcCommon.swift")
    fun minutesBetween(from: String?, to: String?): Int = TODO("STUB: SvcCommon.swift")
    fun dueWords(days: Int): String = TODO("STUB: SvcCommon.swift")
    fun daysUntil(iso: String?): Int? = TODO("STUB: SvcCommon.swift")
    fun cadence(weeks: Int): String = TODO("STUB: SvcCommon.swift")
}

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcGroomStage.shown(stage: String, simple: Boolean): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcGroomStage.visible(simple: Boolean): List<String> = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcGroomStage.label(stage: String, simple: Boolean): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcGroomStage.next(stage: String, simple: Boolean): String? = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcGroomStage.previous(stage: String, simple: Boolean): String? = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcGroomStage.moveLabel(to: String, from: String, simple: Boolean): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
object SvcHandling {
    val warn: Set<String> get() = TODO("STUB: SvcCommon.swift")
    val all: List<String> get() = TODO("STUB: SvcCommon.swift")
}

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcReportText.style(value: String): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcReportText.kind(category: String?): String? = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcReportText.required(category: String?, report: SvcReport?): Set<String> = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcReportText.hasContent(report: SvcReport?): Boolean = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter")
val SvcReportText.behaviours: List<String> get() = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter")
val SvcReportText.progress: List<String> get() = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter")
val SvcReportText.stool: List<String> get() = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
suspend fun svcLoadMembers(workspace: Workspace): List<SvcMember> = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
class SvcStartGateRequest(
    val code: String,
    val petName: String,
    /** The start again, with the override in the body. */
    val run: suspend (Map<String, Any?>) -> Unit,
) {
    val id: UUID = UUID.randomUUID()
}


// STUB: Modules/Services/SvcCommon.swift
@Suppress("UNUSED_PARAMETER")
fun svcIsStartGate(error: Throwable): String? = TODO("STUB: SvcCommon.swift")

// MARK: - SvcVisitSheets.swift


// STUB: Modules/Services/SvcVisitSheets.swift
class SvcReportTarget(
    val subtitle: String,
    val pets: List<SvcReportPet>,
    val canWrite: Boolean,
) {
    val id: UUID = UUID.randomUUID()
}


// STUB: Modules/Services/SvcVisitSheets.swift
@Composable
fun SvcPhotoSlot(
    label: String,
    url: String?,
    disabled: Boolean,
    appointmentId: String,
    modifier: Modifier = Modifier,
    onChange: (String?) -> Unit,
) {}

// STUB: Modules/Services/SvcVisitSheets.swift
data class SvcOnMyWayTarget(
    val id: String,
    val appointmentId: String,
    val client: SvcClientRef,
    val petNames: String,
)

// STUB: Modules/Services/SvcVisitSheets.swift
@Composable
fun SvcOnMyWaySheet(target: SvcOnMyWayTarget, onSent: () -> Unit = {}, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/SvcVisitSheets.swift
data class SvcMissedTarget(
    val id: String,
    val petName: String,
    val clientName: String,
    val subtitle: String,
    /** "no_show" or "cancel". */
    val mode: String,
)
