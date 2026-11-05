package com.pawmiaw.business.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.UUID

//
//  Stand-ins for the Schedule symbols Home uses (ported in parallel by the Schedule job).
//  Signatures only; deleted at merge.
//

// STUB: Modules/Schedule/SchedComponents.swift
class SchedGatePrompt {
    val id: UUID = UUID.randomUUID()

    companion object {
        fun from(
            error: Throwable,
            petName: String,
            verb: String,
            attempt: suspend (Map<String, Any?>) -> Unit,
            onDone: () -> Unit,
            fallback: (suspend () -> Unit)? = null,
            appointmentId: String? = null,
            clientId: String? = null,
            clientName: String? = null,
            familyOnApp: Boolean = false,
        ): SchedGatePrompt? = null
    }
}

// STUB: Modules/Schedule/SchedComponents.swift
object SchedVisitOps {
    suspend fun start(appointmentId: String, extra: Map<String, Any?> = emptyMap()) {}
}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedGateSheet(prompt: SchedGatePrompt, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedLogic.swift
fun schedToast(error: Throwable) {}

// STUB: Modules/Schedule/SchedLogic.swift
object SchedAPI {
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T =
        TODO("STUB: SchedLogic.swift")
}

// STUB: Modules/Schedule/SchedLogic.swift
object SchedDay {
    fun `when`(startsAt: String, zone: ZoneId): String = "today"
}

// STUB: Modules/Schedule/SchedLogic.swift
object SchedData {
    suspend fun members(): List<SchedMember> = emptyList()
    suspend fun visit(id: String): SchedAppointment = TODO("STUB: SchedLogic.swift")
    suspend fun visitRecords(appointmentId: String): List<SchedRecord>? = null
    suspend fun catalog(): SchedCatalog? = null
    fun zone(of: String?): ZoneId = Workspace.shared.timeZone
}

// STUB: Modules/Schedule/SchedVisitSheets.swift
enum class SchedChargeMode(val rawValue: String) { desk("desk"), collect("collect"), price("price"), noCharge("noCharge") }

// STUB: Modules/Schedule/SchedVisitSheets.swift
@Composable
fun SchedCheckInSheet(appointment: SchedAppointment, canStart: Boolean, modifier: Modifier = Modifier, onGate: (SchedGatePrompt) -> Unit = {}) {}

// STUB: Modules/Schedule/SchedVisitSheets.swift
@Composable
fun SchedOtherDaySheet(
    appointment: SchedAppointment,
    `when`: String,
    canMove: Boolean,
    onMoved: () -> Unit,
    onNoShow: (() -> Unit)? = null,
    onWalkIn: (() -> Unit)? = null,
    onRecordPast: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Schedule/SchedVisitSheets.swift
@Composable
fun SchedFinishSheet(
    appointment: SchedAppointment,
    drafts: Int,
    signed: Int,
    invoice: SchedInvoice?,
    signer: Pair<String, String>?,
    collects: Boolean,
    onAfter: (SchedChargeMode) -> Unit,
    onEarlierDay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Schedule/SchedVisitSheets.swift
@Composable
fun SchedRecordPastSheet(appointment: SchedAppointment, invoice: SchedInvoice?, collects: Boolean, modifier: Modifier = Modifier, onAfter: (SchedChargeMode) -> Unit) {}

// STUB: Modules/Schedule/SchedVisitSheets.swift (the `init(appointment:mode:)`)
@Composable
fun SchedMissedSheet(appointment: SchedAppointment, mode: String, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedVisitSheets.swift
@Composable
fun SchedRebookSheet(appointment: SchedAppointment, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedVisitPanels.swift
@Serializable
data class SchedGroomSettings(val simpleStages: Boolean? = null)

// STUB: Modules/Schedule/SchedOnlineBooking.swift
object SchedOnlineText {
    fun change(from: String, to: String?): String = from
}

// STUB: Modules/Schedule/SchedOnlineBooking.swift
@Composable
fun SchedRequestSheet(request: SchedOnlineRequest, onOpenVisit: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {}
