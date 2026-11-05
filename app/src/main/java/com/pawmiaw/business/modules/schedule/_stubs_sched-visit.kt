package com.pawmiaw.business.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import com.pawmiaw.business.hospital.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

//
//  Stand-ins for what the visit page (sched-visit) reads from the Schedule files the
//  sched-agenda job ports at the same time. Deleted at merge.
//

// STUB: Modules/Schedule/SchedLogic.swift
class SchedFailure(val api: APIError, val body: JSONValue?) : Exception(api.message) {
    val code: String? get() = api.code
    val conflicts: List<SchedConflict>? get() = TODO("STUB: SchedLogic.swift")
    val hoursProblem: SchedHoursProblem? get() = TODO("STUB: SchedLogic.swift")
    val fee: SchedFeeRequired? get() = TODO("STUB: SchedLogic.swift")
    val depositGap: SchedDepositDue? get() = TODO("STUB: SchedLogic.swift")
    val consentGap: List<ConsentLine> get() = TODO("STUB: SchedLogic.swift")
}

// STUB: Modules/Schedule/SchedLogic.swift
fun schedAPIError(error: Throwable): Throwable = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
fun schedErrorText(error: Throwable): String = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
fun schedToast(error: Throwable): Unit = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
object SchedAPI {
    @Suppress("UNUSED_PARAMETER")
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T =
        TODO("STUB: SchedLogic.swift")
}

// STUB: Modules/Schedule/SchedLogic.swift
object SchedDay {
    val zone: ZoneId get() = TODO("STUB: SchedLogic.swift")
    val today: String get() = TODO("STUB: SchedLogic.swift")
    fun key(date: Instant, zone: ZoneId? = null): String = TODO("STUB: SchedLogic.swift")
    fun key(iso: String?): String? = TODO("STUB: SchedLogic.swift")
    fun start(day: String, zone: ZoneId? = null): Instant = TODO("STUB: SchedLogic.swift")
    fun at(day: String, minute: Int, zone: ZoneId? = null): Instant = TODO("STUB: SchedLogic.swift")
    fun addDays(day: String, count: Int): String = TODO("STUB: SchedLogic.swift")
    fun minuteOfDay(date: Instant, zone: ZoneId? = null): Int = TODO("STUB: SchedLogic.swift")
    fun timeLabel(minute: Int): String = TODO("STUB: SchedLogic.swift")
    fun shortLabel(day: String): String = TODO("STUB: SchedLogic.swift")
    fun upperFirst(text: String): String = TODO("STUB: SchedLogic.swift")
    fun duration(minutes: Int): String = TODO("STUB: SchedLogic.swift")
    fun `when`(startsAt: String, zone: ZoneId): String = TODO("STUB: SchedLogic.swift")
}

// STUB: Modules/Schedule/SchedLogic.swift (SchedHours itself is stood in by _stubs_models_b.kt)
@Suppress("UnusedReceiverParameter")
fun SchedHours.nearestOpenDay(day: String, closures: List<SchedClosure>?, availability: SchedAvailability?, notBefore: String?): String? =
    TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
data class SchedSlot(
    var id: String?,
    var start: Instant,
    var end: Instant,
    var memberId: String?,
    var roomId: String?,
    var pets: Int = 1,
    var together: Boolean = false,
    var ignoreIds: List<String> = emptyList(),
)

// STUB: Modules/Schedule/SchedLogic.swift
object SchedConflicts {
    fun roomIsFree(slot: SchedSlot, room: SchedRoom, load: SchedLoad): Boolean = TODO("STUB: SchedLogic.swift")
}

// STUB: Modules/Schedule/SchedLogic.swift
object SchedData {
    suspend fun members(): List<SchedMember> = TODO("STUB: SchedLogic.swift")
    suspend fun visit(id: String): SchedAppointment = TODO("STUB: SchedLogic.swift")
    suspend fun visitRecords(appointmentId: String): List<SchedRecord>? = TODO("STUB: SchedLogic.swift")
    suspend fun load(from: Instant, to: Instant, locationId: String?): SchedLoad = TODO("STUB: SchedLogic.swift")
    fun zone(locationId: String?): ZoneId = TODO("STUB: SchedLogic.swift")
}

// STUB: Modules/Schedule/SchedLogic.swift
fun schedWhatsAppURL(phone: String?, message: String? = null): String? = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
fun schedPhoneURL(phone: String?): String? = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
fun schedAge(birthDate: String?): String? = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
fun schedSpeciesLabel(species: String?): String? = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedLogic.swift
fun schedSexLabel(sex: String?): String? = TODO("STUB: SchedLogic.swift")

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedOnlineMark(modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedPendingMark(modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedRescheduleMark(modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedConflictWarning(conflicts: List<SchedConflict>, confirmed: MutableState<Boolean>, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedHoursWarning(problem: SchedHoursProblem, personName: String? = null, confirmed: MutableState<Boolean>, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
fun schedSameName(left: String, right: String): Boolean = TODO("STUB: SchedComponents.swift")

// STUB: Modules/Schedule/SchedComponents.swift
fun schedNames(names: List<String>): String = TODO("STUB: SchedComponents.swift")

// STUB: Modules/Schedule/SchedComponents.swift
class SchedGatePrompt(
    val petName: String,
    val verb: String,
    val kind: String,
    val message: String,
    val attempt: suspend (Map<String, Any?>) -> Unit,
    val onDone: () -> Unit,
    var fallback: (suspend () -> Unit)? = null,
) {
    val id: UUID = UUID.randomUUID()

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun from(
            error: Throwable,
            visit: SchedAppointment,
            verb: String,
            attempt: suspend (Map<String, Any?>) -> Unit,
            onDone: () -> Unit,
            fallback: (suspend () -> Unit)? = null,
        ): SchedGatePrompt? = TODO("STUB: SchedComponents.swift")
    }
}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedGateSheet(prompt: SchedGatePrompt, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
object SchedVisitOps {
    suspend fun start(appointmentId: String, extra: Map<String, Any?> = emptyMap()): Unit = TODO("STUB: SchedComponents.swift")
    suspend fun startWithGate(appointment: SchedAppointment, onGate: (SchedGatePrompt) -> Unit): Unit = TODO("STUB: SchedComponents.swift")
}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedBareField(prompt: String, text: MutableState<String>, key: String? = null, form: FormState, multiline: Boolean = true, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedWayRow(title: String, hint: String, actionTitle: String, icon: String, modifier: Modifier = Modifier, action: () -> Unit) {}

// STUB: Modules/Schedule/SchedBookingExtras.swift
data class SchedTriageDraft(
    var on: Boolean = false,
    var priority: String? = null,
    var reason: String = "",
    var vitals: HospVitalsDraft = HospVitalsDraft(),
) {
    sealed class Missing {
        data object priority : Missing()
        data object reason : Missing()
        data class vital(val field: HospVitalsDraft.Field) : Missing()
    }

    @Suppress("UNUSED_PARAMETER")
    fun parsed(fallbackReason: String = ""): Pair<Map<String, Any?>?, Missing?> = TODO("STUB: SchedBookingExtras.swift")

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun missingText(missing: Missing): String = TODO("STUB: SchedBookingExtras.swift")
    }
}

// STUB: Modules/Schedule/SchedBookingExtras.swift
@Composable
fun SchedTriageFields(draft: MutableState<SchedTriageDraft>, lockOn: Boolean = false, invalid: SchedTriageDraft.Missing? = null, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedOnlineBooking.swift
class SchedAnswerTarget(appointment: SchedAppointment)

// STUB: Modules/Schedule/SchedOnlineBooking.swift
@Composable
fun SchedRequestPanel(target: SchedAnswerTarget, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedBookingSheet.swift
data class SchedBookingDraft(
    val id: UUID = UUID.randomUUID(),
    var mode: Mode = Mode.appointment,
    var appointment: SchedAppointment? = null,
    var day: String? = null,
    var startMinute: Int? = null,
    var durationMinutes: Int? = null,
    var memberId: String? = null,
    var roomId: String? = null,
    var clientId: String? = null,
    var petId: String? = null,
    var serviceId: String? = null,
    var notes: String? = null,
    var followUpId: String? = null,
) {
    enum class Mode { appointment, walkIn }
}

// STUB: Modules/Schedule/SchedBookingSheet.swift
@Composable
fun SchedBookingSheet(
    draft: SchedBookingDraft,
    onGate: (SchedGatePrompt) -> Unit = {},
    onBooked: (suspend (SchedAppointment) -> Unit)? = null,
    staysOnGate: Boolean = false,
    modifier: Modifier = Modifier,
) {}
