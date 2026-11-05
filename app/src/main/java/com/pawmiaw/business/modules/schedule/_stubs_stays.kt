package com.pawmiaw.business.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import java.util.UUID

// Stand-ins for the Schedule symbols the Stays screens use (ported in parallel). Deleted at merge.

// STUB: Modules/Schedule/SchedOnlineBooking.swift
object SchedOnlineText {
    fun change(from: String, to: String?): String = TODO("STUB: SchedOnlineBooking.swift")
    fun inboxTitle(count: Int, asks: Int): String = TODO("STUB: SchedOnlineBooking.swift")
}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedRescheduleMark(modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedComponents.swift
class SchedGatePrompt(
    val petName: String,
    val verb: String,
    val kind: String,
    val message: String,
    val attempt: suspend (Map<String, Any?>) -> Unit,
    val onDone: () -> Unit,
    val fallback: (suspend () -> Unit)? = null,
    val appointmentId: String? = null,
    val clientId: String? = null,
    val clientName: String? = null,
    val familyOnApp: Boolean = false,
    val deposit: SchedDepositDue? = null,
    val missing: List<ConsentLine> = emptyList(),
    val takeDeposit: (suspend (Map<String, Any?>, Map<String, Any?>) -> Unit)? = null,
    val consents: ConsentContext? = null,
) {
    val id: UUID = UUID.randomUUID()
}

// STUB: Modules/Schedule/SchedComponents.swift
@Composable
fun SchedGateSheet(prompt: SchedGatePrompt, modifier: Modifier = Modifier) {}

// STUB: Modules/Schedule/SchedLogic.swift
class SchedFailure(val api: APIError, val body: JSONValue?) : Exception(api.message) {
    val code: String? get() = api.code
}

// STUB: Modules/Schedule/SchedBookingExtras.swift
@Composable
fun SchedAddressPicker(
    clientId: String,
    addressId: MutableState<String?>,
    accessNotes: MutableState<String>,
    error: String? = null,
    modifier: Modifier = Modifier,
) {}
