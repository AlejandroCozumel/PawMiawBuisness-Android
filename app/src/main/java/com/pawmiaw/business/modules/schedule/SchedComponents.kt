package com.pawmiaw.business.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.UUID

//
//  SchedComponents.kt (iOS SchedComponents.swift)
//
//  The Schedule's own small pieces, built from the shared design: the
//  "En línea" and "Por confirmar" marks, a visit's status pills, the
//  double-booking and hours warnings, a visit row, and the consent gate a
//  start may run into.
//

// MARK: - Marks

/** "En línea": a family booked it from the app (web `OnlineMark`). */
@Composable
fun SchedOnlineMark(modifier: Modifier = Modifier) {
    Chip(text = L("Online"), tone = Chip.Tone.online, icon = "globe", modifier = modifier)
}

/** "Por confirmar": a requested visit, dashed (it holds the time, it isn't a visit yet; web `PendingMark`). */
@Composable
fun SchedPendingMark(modifier: Modifier = Modifier) {
    Chip(text = L("To confirm"), icon = "clock-3", pending = true, modifier = modifier)
}

/** "Cambio de hora": the family asks to move a confirmed online booking; dashed like a request (it waits on you). */
@Composable
fun SchedRescheduleMark(modifier: Modifier = Modifier) {
    Chip(text = L("Change of time"), icon = "arrow-right-left", pending = true, modifier = modifier)
}

/**
 * The visit's state, and what happened to its bill or fee when the list knows (web `StatusPills`).
 * Like the Swift view builder, the pills go straight into the caller's layout (a flow, a row).
 */
@Composable
fun SchedStatusPills(appointment: SchedAppointment) {
    val missed = appointment.isGone
    if (appointment.isRequested) {
        SchedPendingMark()
    } else {
        Chip(
            text = SchedStatus.label(appointment.status),
            tone = if (missed) Chip.Tone.danger else Chip.Tone.custom(ProviderState.appointment(appointment.status).color),
        )
    }
    if (appointment.bookedOnline == true) SchedOnlineMark()
    if (appointment.status == "completed" && appointment.noCharge == true) Chip(text = L("No charge"), tone = Chip.Tone.custom(Theme.lime))
    if (missed && appointment.feeDecision == "charged") Chip(text = L("Fee charged"), tone = Chip.Tone.custom(Theme.mango))
    if (missed && appointment.feeDecision == "waived") Chip(text = L("Fee waived"), tone = Chip.Tone.custom(Theme.inkSoft))
}

// MARK: - Warnings

/** A double booking: who or what is taken, and the switch to book it anyway. */
@Composable
fun SchedConflictWarning(conflicts: List<SchedConflict>, confirmed: MutableState<Boolean>, modifier: Modifier = Modifier) {
    if (conflicts.isEmpty()) return
    val onlyOut = conflicts.all { it.kind == "room" && it.outOfService != null }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoticeCard(
            tone = NoticeCard.Tone.problem,
            title = if (onlyOut) L("Room out of service") else L("Double booking"),
            message = conflicts.joinToString("\n") { SchedConflicts.line(it) },
        )
        ToggleRow(
            title = if (onlyOut) L("Book it anyway — I know it is out of service") else L("Book it anyway — I know they overlap"),
            isOn = confirmed,
        )
    }
}

/** Outside the hours (a closed date, someone's day off): said, and booked only on purpose. */
@Composable
fun SchedHoursWarning(problem: SchedHoursProblem, personName: String? = null, confirmed: MutableState<Boolean>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoticeCard(tone = NoticeCard.Tone.warning, title = SchedHours.problemTitle(problem, personName = personName), message = SchedHours.problemBody(problem))
        ToggleRow(title = L("Book it anyway"), isOn = confirmed)
    }
}

// MARK: - A day heading

/**
 * The list's day band (web agenda-list `DayHeading`): "Hoy · miércoles 4 de noviembre",
 * "Ayer · …", or just "Viernes 6 de noviembre" — the kit's `DayBand`.
 */
@Composable
fun schedDayBand(day: String, detail: String? = null, modifier: Modifier = Modifier) {
    val relative = SchedDay.relative(day)
    if (relative != null) {
        DayBand(title = SchedDay.longLabel(day).lowercase(appLocale), lead = relative, highlighted = day == SchedDay.today, trailing = detail, modifier = modifier)
    } else {
        DayBand(title = SchedDay.longLabel(day), trailing = detail, modifier = modifier)
    }
}

// MARK: - A visit in a list

/** The next step a row's button takes (as on Home and the visit page). */
enum class SchedRowAction {
    open, answer, checkIn, start, finish;

    val title: String
        get() = when (this) {
            open -> L("Open")
            answer -> L("Answer")
            checkIn -> L("Check in")
            start -> L("Start")
            finish -> L("Finish")
        }

    val systemImage: String
        get() = when (this) {
            open -> "chevron-right"
            answer -> "message-square-reply"
            checkIn -> "arrow-right-to-line"
            start -> "play"
            finish -> "badge-check"
        }

    companion object {
        fun next(`for`: SchedAppointment, canCheckIn: Boolean, canStart: Boolean): SchedRowAction {
            val appointment = `for`
            if (appointment.group?.isSession == true) return open
            if (appointment.isRequested) return answer
            if (appointment.status == "scheduled" && canCheckIn && SchedDay.key(appointment.startsAt) == SchedDay.today) return checkIn
            if (appointment.status == "checked_in" && canStart) return start
            if (appointment.status == "in_progress" && canStart) return finish
            return open
        }
    }
}

/**
 * One visit in the list (web agenda-list `ListRow`): the time in Fredoka, the pet
 * (its face opens the chart), "service · client", who and where, its pills, and the
 * next step's button. The row opens the visit.
 */
@Composable
fun SchedVisitRow(
    appointment: SchedAppointment,
    action: SchedRowAction = SchedRowAction.open,
    canOpenChart: Boolean = false,
    onAction: ((SchedRowAction) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val regular = LocalSizeClass.current == SizeClass.regular
    val asking = appointment.familyAsking
    val openVisit = { nav.push(ProviderRoute.appointment(appointment.id)) }

    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row opens the visit; the pet's face opens its chart (kept outside the row's link).
        Row(
            Modifier
                .width(60.dp)
                .fillMaxHeight()
                .plainButton(label = null) { openVisit() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FittedText(
                DateText.time(appointment.start),
                style = Theme.heading(15.2).monospacedDigit(),
                color = Theme.inkStrong,
                minimumScaleFactor = 0.8f,
            )
        }
        val avatar: @Composable (Modifier) -> Unit = { m ->
            PetAvatar(name = appointment.petName, species = appointment.petSpecies, photoURL = appointment.petPhotoUrl, size = 40.dp, modifier = m)
        }
        if (canOpenChart) {
            avatar(Modifier.plainButton(label = LF("Open %@’s chart", appointment.petName)) {
                nav.push(ProviderRoute.patientChart(petId = appointment.providerClientPetId))
            })
        } else {
            avatar(Modifier)
        }
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .plainButton(label = null) { openVisit() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        appointment.petName,
                        style = Theme.text(16, FontWeight.Bold),
                        color = Theme.inkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (appointment.doubleBooked == true) {
                        val label = L("Double booked")
                        Glyph("triangle-alert", 14.dp, tint = Theme.destructive, modifier = Modifier.semantics { contentDescription = label })
                    }
                    if (asking) {
                        val label = L("The family asked for a change")
                        Glyph("message-circle-question", 14.dp, tint = Theme.pink, modifier = Modifier.semantics { contentDescription = label })
                    }
                }
                Text(
                    "${appointment.serviceLabel} · ${appointment.clientName}",
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = schedRowGroupLine(appointment)
                if (line != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("users-round", 12.dp, tint = Theme.blue)
                        Text(line, style = Theme.text(12, FontWeight.Bold), color = Theme.blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                CompositionLocalProvider(LocalTextStyle provides Theme.text(12), LocalContentColor provides Theme.inkSoft) {
                    FlowLayout(spacing = 6.dp, lineSpacing = 4.dp, modifier = Modifier.padding(top = 2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("user-round", 12.dp)
                            Text(appointment.assignedMember?.name ?: L("Nobody yet"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val room = appointment.room
                        if (room != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph("door-open", 12.dp)
                                Text(room, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        SchedStatusPills(appointment = appointment)
                    }
                }
            }
            Spacer(Modifier.width(0.dp))
        }
        // On a phone, as on the web's, the row itself opens the visit: only check-in and start keep a button.
        if (onAction != null) {
            if (regular) {
                PMButton(action.title, systemImage = action.systemImage, variant = ButtonVariant.outline, size = ButtonSize.compact) { onAction(action) }
            } else if (action == SchedRowAction.checkIn || action == SchedRowAction.start) {
                IconCircleButton(systemImage = action.systemImage, label = action.title, tint = Theme.blue) { onAction(action) }
            }
        }
    }
}

/** The row's group line: a session and its places, or "Together with …" for a family's other pets. */
private fun schedRowGroupLine(appointment: SchedAppointment): String? {
    val group = appointment.group ?: return null
    if (group.isSession) return "${group.serviceLabel ?: ""} · ${group.placesLabel}"
    val others = group.stayingPets.filter { it.appointmentId != appointment.id }.mapNotNull { it.name }
    if (others.isEmpty()) return null
    return LF("Together with %@", schedNames(others))
}

/** The same person written two ways ("Hernandez" and "Hernández"). */
fun schedSameName(left: String, right: String): Boolean {
    val collator = Collator.getInstance(appLocale).apply { strength = Collator.PRIMARY }
    return collator.compare(left.trim(), right.trim()) == 0
}

/** "Luna, Coco y Max". */
fun schedNames(names: List<String>): String {
    if (names.size <= 1) return names.firstOrNull() ?: ""
    return LF("%@ and %@", names.dropLast(1).joinToString(", "), names.last())
}

// MARK: - The consent and deposit gate

/**
 * A start (or a check-in that starts) refused for a consent nobody signed, or for an
 * emergency deposit not taken yet (409 `consent_required` / `deposit_required`).
 *
 * - `verb`: "start" or "check_in". `kind`: "consent" or "deposit".
 * - `attempt`: tries again with the override's body (`overrideConsent`/`overrideDeposit` and the reason).
 * - `fallback`: "Check in without starting".
 * - `appointmentId`: the visit, when it exists (a walk-in doesn't until it is created): its consents are signed here.
 * - `clientId`: whose account takes the deposit.
 * - `deposit`: the emergency deposit owed (deposit_required).
 * - `missing`: the consents missing (consent_required), for a walk-in with no visit yet.
 * - `takeDeposit`: a walk-in: the deposit goes with it (`payment`, then the override already given).
 * - `consents`: another act's consents (a home stay's first arrival); a visit's come from `appointmentId`.
 */
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
            takeDeposit: (suspend (Map<String, Any?>, Map<String, Any?>) -> Unit)? = null,
        ): SchedGatePrompt? {
            val failure = error as? SchedFailure ?: return null
            val kind = when (failure.code) {
                "consent_required" -> "consent"
                "deposit_required" -> "deposit"
                else -> return null
            }
            return SchedGatePrompt(
                petName = petName, verb = verb, kind = kind, message = failure.api.message, attempt = attempt, onDone = onDone, fallback = fallback,
                appointmentId = appointmentId, clientId = clientId, clientName = clientName, familyOnApp = familyOnApp,
                deposit = failure.depositGap, missing = failure.consentGap, takeDeposit = takeDeposit,
            )
        }

        /** The same prompt for a visit: who it is for, where its consents and deposit go. */
        fun from(
            error: Throwable,
            visit: SchedAppointment,
            verb: String,
            attempt: suspend (Map<String, Any?>) -> Unit,
            onDone: () -> Unit,
            fallback: (suspend () -> Unit)? = null,
        ): SchedGatePrompt? = from(
            error, petName = visit.petName, verb = verb, attempt = attempt, onDone = onDone, fallback = fallback,
            appointmentId = visit.id, clientId = visit.providerClientId, clientName = visit.clientName, familyOnApp = visit.clientHouseholdId != null,
        )
    }
}

private enum class SchedGateStep { override, deposit }

/**
 * The sheet for a gate: what is missing — the consents to sign here (or send) or the emergency
 * deposit to take — then the start; owners and admins may start anyway with a reason; or check in only.
 */
@Composable
fun SchedGateSheet(prompt: SchedGatePrompt, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("") }
    var owed by remember { mutableStateOf<SchedDepositDue?>(null) }
    /** An override already given on the way (a consent), sent again with the deposit's. */
    var base by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var satisfied by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<SchedGateStep?>(null) }
    var working by remember { mutableStateOf(false) }

    val canOverride = workspace.isOwnerOrAdmin
    val checkIn = prompt.verb == "check_in"
    val isDeposit = (if (kind.isEmpty()) prompt.kind else kind) == "deposit"
    val canTake = prompt.clientId != null && (prompt.appointmentId != null || prompt.takeDeposit != null)
    val due = owed ?: prompt.deposit

    /** The start again; a deposit asked after a consent turns this into the deposit's gate. */
    suspend fun go(body: Map<String, Any?>) {
        try {
            prompt.attempt(body)
            prompt.onDone()
            dismiss()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is SchedFailure && failure.code == "deposit_required") {
                base = body.filterKeys { it != "overrideDeposit" }
                owed = failure.depositGap
                kind = "deposit"
                Toast.show(L("Take the deposit, then start."))
            } else if (failure is SchedFailure && failure.code == "consent_required") {
                Toast.error(L("A required consent is still missing."))
                announceDataChanged()
            } else {
                schedToast(failure)
                throw schedAPIError(failure)
            }
        }
    }

    // The footer's other ways (web: the fallback, then "Start anyway" in red), above "Not now".
    val footerActions = buildList {
        val fallback = prompt.fallback
        if (fallback != null) {
            add(ModalAction(L("Check in without starting"), isWorking = working) {
                working = true
                scope.launch {
                    try {
                        fallback()
                        dismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        schedToast(failure)
                    } finally {
                        working = false
                    }
                }
            })
        }
        if (canOverride) {
            add(ModalAction(if (checkIn) L("Check in anyway") else L("Start anyway"), tint = Theme.destructive) { step = SchedGateStep.override })
        }
    }

    val primaryTitle: String? = if (isDeposit) {
        if (canTake) L("Take the deposit") else null
    } else {
        if (prompt.appointmentId != null || prompt.consents != null) (if (checkIn) L("Check in now") else L("Start now")) else null
    }

    val primaryAction: (() -> Unit)? = if (isDeposit) {
        if (canTake) ({ step = SchedGateStep.deposit }) else null
    } else if (prompt.appointmentId == null && prompt.consents == null) {
        null
    } else {
        {
            if (!satisfied) {
                Toast.error(L("A required consent is still missing."))
            } else {
                working = true
                scope.launch {
                    try { go(base) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) {} finally { working = false }
                }
            }
        }
    }

    ProviderModal(
        title = if (isDeposit) LF("%@ needs the emergency deposit first", prompt.petName)
            else if (checkIn) LF("%@ can’t check in yet", prompt.petName) else LF("%@ can’t start yet", prompt.petName),
        subtitle = if (isDeposit) L("Treatment starts once it is paid. A critical patient never waits for it.") else L("The service needs these consents signed first."),
        systemImage = if (isDeposit) "siren" else "shield-alert",
        accent = ModalAccent.mango,
        primaryTitle = primaryTitle,
        isWorking = working,
        cancelTitle = L("Not now"),
        secondaryActions = footerActions,
        primaryIcon = if (isDeposit) "hand-coins" else "play",
        onPrimary = primaryAction,
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        if (isDeposit) {
            // The deposit owed, and what happens with it.
            val box = RoundedCornerShape(Theme.boxRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.mango, 10.0), box)
                    .border(1.dp, Theme.mix(Theme.mango, 60.0, Theme.borderSoft), box)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(Money.format(due?.owed ?: 0, due?.currency), style = Theme.heading(24), color = Theme.inkStrong)
                if (due != null && (due.paid ?: 0) > 0) {
                    Text(
                        LF("%@ paid of %@.", Money.format(due.paid, due.currency), Money.format(due.amount, due.currency)),
                        style = Theme.smallFont,
                        color = Theme.inkSoft,
                    )
                } else {
                    Text(L("The store’s emergency deposit (Store → Settings)."), style = Theme.smallFont, color = Theme.inkSoft)
                }
            }
            Text(
                if (canTake) L("Take it now: it stays as credit on their account, is used when they pay, and treatment starts right after.")
                else L("Check the pet in first: the deposit is taken on the visit, then treatment starts."),
                style = Theme.smallFont,
                color = Theme.inkSoft,
            )
        } else {
            // Where the consents to sign are: the act given, else the visit.
            val context = prompt.consents ?: prompt.appointmentId?.let {
                ConsentContext(act = ConsentAct(appointmentId = it), businessName = workspace.current?.name, clientName = prompt.clientName ?: "", familyOnApp = prompt.familyOnApp)
            }
            if (context != null) {
                if (satisfied) {
                    NoticeCard(tone = NoticeCard.Tone.success, title = if (checkIn) L("Everything is signed. You can check in now.") else L("Everything is signed. You can start now."))
                } else {
                    Text(L("Sign them here with the client, or send them to the family and start once they sign."), style = Theme.text(14), color = Theme.inkSoft)
                }
                ConsentsCard(context = context, bare = true) { list -> satisfied = list.requirement?.satisfied ?: (list.requirement == null) }
            } else {
                if (prompt.missing.isNotEmpty()) {
                    val box = RoundedCornerShape(Theme.boxRadius)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.wash(Theme.mango, 10.0), box)
                            .border(1.dp, Theme.mix(Theme.mango, 60.0, Theme.borderSoft), box)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        prompt.missing.forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph("circle-alert", 16.dp, tint = Theme.inkStrong)
                                Text(line.title ?: "", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                            }
                        }
                    }
                } else {
                    Text(prompt.message, style = Theme.text(14), color = Theme.inkStrong)
                }
                Text(
                    L("Check the pet in: the visit asks for the consents, and the client can sign them there on this device. Then start."),
                    style = Theme.smallFont,
                    color = Theme.inkSoft,
                )
            }
        }
    }

    when (step) {
        SchedGateStep.override -> AppSheet(onDismissRequest = { step = null }) {
            ConfirmSheet(
                title = if (isDeposit) (if (checkIn) L("Check in before the deposit is paid?") else L("Start before the deposit is paid?"))
                    else (if (checkIn) L("Check in without the signed consent?") else L("Start without the signed consent?")),
                message = L("Only owners and admins can. It is written down with your name and the reason."),
                confirmTitle = if (checkIn) L("Check in anyway") else L("Start anyway"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                cancelTitle = L("Not now"),
            ) { reason ->
                val body = base.toMutableMap()
                if (isDeposit) body["overrideDeposit"] = true else body["overrideConsent"] = true
                body["overrideReason"] = reason ?: ""
                go(body)
            }
        }
        SchedGateStep.deposit -> AppSheet(onDismissRequest = { step = null }) {
            val take = prompt.takeDeposit
            DepositSheet(
                clientId = prompt.clientId ?: "",
                clientName = prompt.clientName ?: "",
                appointmentId = prompt.appointmentId,
                owed = due?.owed ?: 0,
                currency = due?.currency,
                label = LF("Emergency deposit · %@", prompt.petName),
                explanation = if (take == null) L("It stays as credit on their account and is used when they pay the visit.") else null,
                takeWith = take?.let { t ->
                    val with: suspend (Map<String, Any?>) -> Unit = { payment ->
                        try {
                            t(payment, base)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            throw schedAPIError(failure)
                        }
                    }
                    with
                },
                closesWhenTaken = true,
                onTaken = {
                    // Once the deposit's sheet is gone: done (a walk-in), or the start again.
                    scope.launch {
                        delay(450)
                        if (prompt.takeDeposit != null) {
                            prompt.onDone()
                            dismiss()
                        } else {
                            try { go(base) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) {}
                        }
                    }
                },
            )
        }
        null -> {}
    }
}

// MARK: - Starting a visit

object SchedVisitOps {
    /** `POST …/start`, with the override's body when there is one. */
    suspend fun start(appointmentId: String, extra: Map<String, Any?> = emptyMap()) {
        SchedAPI.send<SchedMoveResponse>("POST", "/provider-appointments/$appointmentId/start", body = extra)
        announceDataChanged()
    }

    /** Start, or the gate's prompt when a consent or deposit stops it. */
    suspend fun startWithGate(appointment: SchedAppointment, onGate: (SchedGatePrompt) -> Unit) {
        val id = appointment.id
        val pet = appointment.petName
        try {
            start(id)
            Toast.show(LF("%@ is in.", pet))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val prompt = SchedGatePrompt.from(error, visit = appointment, verb = "start", attempt = { extra -> start(id, extra = extra) }, onDone = { Toast.show(LF("%@ is in.", pet)) })
            if (prompt != null) onGate(prompt) else schedToast(error)
        }
    }
}

// MARK: - A spinner

/** SwiftUI's plain `ProgressView()`: a small grey spinner, centred in the width. */
@Composable
fun SchedProgressView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
    }
}

// MARK: - A sheet's bare text box

/**
 * A text box with no label of its own: the `ModalSection` above it names it (web: a
 * `ProviderSheetSection` over a bare `Textarea`, the check-in's arrival notes). Marked,
 * said and scrolled to like any field of the form.
 */
@Composable
fun SchedBareField(
    prompt: String,
    text: MutableState<String>,
    key: String? = null,
    form: FormState,
    multiline: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val error = key?.let { form.error(it) }
    // iOS `.onChange(of: text)`: a change, not the first value.
    var seen by remember { mutableStateOf(text.value) }
    LaunchedEffect(text.value) {
        if (text.value != seen) {
            seen = text.value
            if (key != null) form.clear(key)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .formScrollTarget(key ?: prompt, form),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FieldChrome(invalid = error != null, multiline = multiline, modifier = Modifier.semantics { contentDescription = prompt }) {
            PlainTextField(text, prompt, Modifier.weight(1f), lines = if (multiline) 3..8 else 1..1)
        }
        if (error != null) {
            Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
        }
    }
}

/**
 * One of a sheet's other ways out, as a bordered row: what it is, a line, and its button
 * (web visit-sheets: "Here for something else?", "It did happen?", "They never came?").
 */
@Composable
fun SchedWayRow(title: String, hint: String, actionTitle: String, icon: String, modifier: Modifier = Modifier, action: () -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, Theme.borderSoft, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(hint, style = Theme.text(12), color = Theme.inkSoft)
        }
        PMButton(actionTitle, systemImage = icon, variant = ButtonVariant.outline, size = ButtonSize.small, action = action)
    }
}
