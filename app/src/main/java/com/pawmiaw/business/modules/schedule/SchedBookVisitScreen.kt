package com.pawmiaw.business.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  SchedBookVisitScreen.kt (iOS SchedBookVisitScreen.swift)
//
//  Booking a visit from another screen (`ProviderRoute.bookVisit`): a client's
//  page, a pet's chart, the Due list, a record, a groom. The same booking
//  flow as the Agenda's (SchedBookingSheet) as a page, with everything the
//  asking screen knows already chosen — the client, the pet, the service,
//  the day, the start, the person, the room, the notes, a walk-in. A visit
//  booked for a Due follow-up is tied to it, as the web's Due list does.
//  Booked, it goes back to the asking screen (and every screen reloads).
//

@Composable
fun BookVisitScreen(prefill: BookingPrefill, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val draft = remember {
        SchedBookingDraft(
            mode = if (prefill.walkIn) SchedBookingDraft.Mode.walkIn else SchedBookingDraft.Mode.appointment,
            day = prefill.day,
            startMinute = prefill.startMinute,
            memberId = prefill.memberId,
            roomId = prefill.roomId,
            clientId = prefill.clientId,
            petId = prefill.petId,
            serviceId = prefill.serviceId,
            notes = prefill.notes,
            followUpId = prefill.followUpId,
        )
    }
    var gate by remember { mutableStateOf<SchedGatePrompt?>(null) }
    var finished by remember { mutableStateOf(false) }

    val canBook = workspace.can(Permission.appointmentsCreate) &&
        (workspace.can(Permission.appointmentsAssignAnyone) || workspace.can(Permission.appointmentsAssignSelf)) &&
        (!prefill.walkIn || workspace.can(Permission.clientsView))

    /** The same prompt, noting when the walk-in went through (started, or checked in only). */
    fun wrapped(p: SchedGatePrompt): SchedGatePrompt {
        val fallback = p.fallback
        return SchedGatePrompt(
            petName = p.petName, verb = p.verb, kind = p.kind, message = p.message,
            attempt = p.attempt,
            onDone = { p.onDone(); finished = true },
            fallback = fallback?.let { run -> { run(); finished = true } },
            appointmentId = p.appointmentId, clientId = p.clientId, clientName = p.clientName, familyOnApp = p.familyOnApp,
            deposit = p.deposit, missing = p.missing,
            takeDeposit = p.takeDeposit,
        )
    }

    // The booking's own sheet, open as the page (its tinted header is the page's header).
    providerPage(if (prefill.walkIn) L("Walk-in") else L("New visit"), hidesBackButton = canBook, modifier = modifier) {
        if (canBook) {
            RequireLocation {
                CompositionLocalProvider(LocalModalAsPage provides true) {
                    SchedBookingSheet(draft = draft, onGate = { prompt -> gate = wrapped(prompt) }, staysOnGate = true)
                }
            }
        } else {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role book visits.")) }
        }
    }
    // A walk-in stopped by a consent or a deposit: the gate opens over the form; once it is in, back.
    gate?.let { prompt ->
        AppSheet(onDismissRequest = {
            gate = null
            if (finished) dismiss()
        }) { key(prompt.id) { SchedGateSheet(prompt = prompt) } }
    }
}
