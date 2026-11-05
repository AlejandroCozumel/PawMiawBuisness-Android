package com.pawmiaw.business.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

//
//  SchedVisitSheets.kt (iOS SchedVisitSheets.swift)
//
//  A visit's moves, each a sheet (web visits/visit-sheets.tsx): check in,
//  a visit on another day moved to now, finish ("Terminar") with the one
//  charge choice of docs/charging-flow.md and "¿Cuándo vuelve?", "Sí vino"
//  for a visit left open (docs/business-visits-left-open.md), no-show and
//  cancel with the fee decision, and booking a missed visit again.
//

// MARK: - Check in

@Composable
fun SchedCheckInSheet(
    appointment: SchedAppointment,
    canStart: Boolean,
    modifier: Modifier = Modifier,
    /** A start stopped by a consent or a deposit: the caller shows the gate once this sheet is gone. */
    onGate: (SchedGatePrompt) -> Unit = {},
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val weight = remember { mutableStateOf("") }
    val note = remember { mutableStateOf("") }
    val roomId = remember { mutableStateOf<String?>(null) }
    val startNow = remember { mutableStateOf(false) }
    var load by remember { mutableStateOf<SchedLoad?>(null) }
    var working by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var triage by remember { mutableStateOf(SchedTriageDraft()) }
    var triageInvalid by remember { mutableStateOf<SchedTriageDraft.Missing?>(null) }
    val now = remember { Instant.now() }

    val booked = (appointment.arrivalNote ?: appointment.notes).nonEmpty
    // Triage is for a clinical visit at a clinic: a groom, a walk or a class checked in there has none.
    val vetCare = Workspace.shared.offers(BusinessCategory.vet, BusinessCategory.emergency) && appointment.isClinical
    val lastKg: String? = appointment.petWeight?.takeIf { it > 0 }?.let { grams ->
        String.format(Locale.US, "%.1f", Math.round(grams / 100) / 10.0)
    }
    val groomVisit = appointment.serviceCategory == "groomer"
    // Today's weight against the last on file: "+0.4 kg", mango from a 10% change.
    val weightChange: Pair<Double, Double>? = run {
        val last = lastKg?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@run null
        val typed = weight.value.trim().replace(",", ".").toDoubleOrNull() ?: return@run null
        Pair(Math.round((typed - last) * 10) / 10.0, (typed - last) / last * 100)
    }

    LaunchedEffect(Unit) {
        if (prepared) return@LaunchedEffect
        prepared = true
        roomId.value = appointment.roomId
        startNow.value = canStart
        load = orNull { SchedData.load(now, now.plusSeconds(2 * 3600), appointment.locationId ?: Workspace.shared.locationId) }
        val name = appointment.room
        if (roomId.value == null && name != null) {
            load?.visitRooms?.firstOrNull { it.name == name }?.let { roomId.value = it.id }
        }
    }

    val roomOptions: List<Pair<String?, String>> = run {
        val rooms = load?.visitRooms ?: emptyList()
        val slot = SchedSlot(id = appointment.id, start = now, end = now.plusSeconds(30 * 60), memberId = null, roomId = null)
        listOf<Pair<String?, String>>(null to L("No room")) + rooms.map { room ->
            val free = load?.let { SchedConflicts.roomIsFree(slot, room, it) } ?: true
            Pair<String?, String>(room.id, if (free) room.name else "${room.name} · ${L("taken")}")
        }
    }

    fun submit() {
        val typed = weight.value.trim().replace(",", ".")
        val kg = if (typed.isEmpty()) null else typed.toDoubleOrNull()
        val badWeight = typed.isNotEmpty() && (kg == null || kg <= 0 || kg > 200)
        if (!form.check(listOf("weight" to if (badWeight) L("Enter the weight in kg, like 12.4") else null))) return
        val arrivalNote = listOfNotNull(booked, note.value.nonEmpty).joinToString("\n").nonEmpty
        // Why they came is asked once: the arrival note stands in for the triage's reason when that's left blank.
        val (value, missing) = if (vetCare) triage.parsed(arrivalNote ?: "") else Pair(null, null)
        if (missing != null) {
            triageInvalid = missing
            Toast.error(SchedTriageDraft.missingText(missing))
            return
        }
        val body = mutableMapOf<String, Any?>("arrivalNote" to arrivalNote, "weightGrams" to kg?.let { Math.round(it * 1000).toInt() })
        if (value != null) body["triage"] = value
        roomId.value?.let { body["roomId"] = it }
        val id = appointment.id
        val pet = appointment.petName
        val base = body.toMap()
        suspend fun send(start: Boolean, extra: Map<String, Any?>) {
            val payload = base.toMutableMap()
            payload["start"] = start
            for ((key, item) in extra) payload[key] = item
            SchedAPI.send<SchedMoveResponse>("POST", "/provider-appointments/$id/check-in", payload)
            announceDataChanged()
        }
        val starting = startNow.value
        working = true
        scope.launch {
            try {
                send(starting, emptyMap())
                Toast.show(if (starting) LF("%@ is in.", pet) else LF("%@ is waiting.", pet))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val prompt = if (starting) SchedGatePrompt.from(
                    e, visit = appointment, verb = "start",
                    attempt = { extra -> send(true, extra) },
                    onDone = { Toast.show(LF("%@ is in.", pet)) },
                    fallback = {
                        send(false, emptyMap())
                        Toast.show(LF("%@ is waiting.", pet))
                    },
                ) else null
                if (prompt != null) {
                    dismiss()
                    onGate(prompt)
                } else {
                    form.apply(schedAPIError(e), mapOf("weightGrams" to "weight"))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Check in"),
        subtitle = "${appointment.petName} · ${appointment.serviceLabel} · ${appointment.clientName}",
        systemImage = "arrow-right-to-line", accent = ModalAccent.butter,
        isDirty = weight.value.isNotEmpty() || note.value.isNotEmpty(), form = form,
        primaryTitle = if (startNow.value) L("Check in and start") else L("Check in"), isWorking = working,
        // A clinical visit shows the visit's journey: arrival, chart, signature, payment.
        steps = if (vetCare) ModalSteps(
            listOf(L("Arrival") to "arrow-right-to-line", L("Chart") to "book-open-text", L("Signature") to "pen-line", L("Payment") to "hand-coins"),
            current = 0,
        ) else null,
        primaryIcon = if (startNow.value) "play" else "arrow-right-to-line",
        pebbleImageURL = appointment.petPhotoUrl,
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        ModalSection(title = L("At the desk"), systemImage = "scale") {
            Text(
                if (vetCare) L("What the vet should know before walking in.") else if (groomVisit) L("What the groomer should know before starting.") else L("What whoever takes the visit should know before starting."),
                style = Theme.text(14), color = Theme.inkSoft,
            )
            PMTextField(L("Weight today (kg)"), text = weight, key = "weight", form = form, prompt = lastKg ?: "12.4", keyboard = KeyboardType.Decimal)
            if (lastKg != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(LF("Last on file: %@ kg", lastKg), style = Theme.text(14), color = Theme.inkSoft)
                    if (weight.value.isEmpty()) {
                        PMButton(L("Same"), variant = ButtonVariant.outline, size = ButtonSize.small) { weight.value = lastKg }
                    } else if (weightChange != null) {
                        val (kg, percent) = weightChange
                        Chip(
                            text = "${if (kg > 0) "+" else ""}${String.format(Locale.US, "%.1f", kg)} kg",
                            tone = if (abs(percent) >= 10) Chip.Tone.custom(Theme.mango) else Chip.Tone.muted,
                            percent = 25.0,
                        )
                    }
                }
            }
            MenuField(label = L("Room"), options = roomOptions, selection = roomId, optional = true)
        }
        ModalSection(title = L("Arrival notes"), systemImage = "notebook-pen", tint = Theme.butter) {
            if (booked != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(L("When booking:")) }
                        append(" $booked")
                    },
                    style = Theme.text(14), color = Theme.inkStrong,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            SchedBareField(prompt = L("Why they came, what they brought…"), text = note, form = form)
            if (vetCare) {
                SchedTriageFields(
                    draft = stateOf({ triage }) { triage = it; triageInvalid = null },
                    invalid = triageInvalid,
                )
            }
        }
        if (canStart) {
            ToggleRow(
                title = L("Start right away"),
                subtitle = if (startNow.value) L("Skip the waiting room — you take the visit now.") else L("They wait in the waiting room until someone starts the visit."),
                isOn = startNow,
            )
        }
    }
}

/** Swift's `try?` for a read: null on any failure, cancellation passed on. */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

// MARK: - A visit on another day

/** "Check in" on a visit that isn't today's: it says which day it is and moves it to now first. */
@Composable
fun SchedOtherDaySheet(
    appointment: SchedAppointment,
    /** "past" or "future". */
    `when`: String,
    canMove: Boolean,
    onMoved: () -> Unit,
    onNoShow: (() -> Unit)? = null,
    onWalkIn: (() -> Unit)? = null,
    onRecordPast: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var conflicts by remember { mutableStateOf<List<SchedConflict>>(emptyList()) }
    val confirmed = remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf<SchedHoursProblem?>(null) }
    val outside = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val now = remember { Instant.now() }

    val day = DateText.day(appointment.start)
    // Now, to the minute (web lib/visit-day.ts `movedToNow`: seconds set to 0), for its booked length.
    val target: Pair<Instant, Instant> = run {
        val start = now.truncatedTo(ChronoUnit.MINUTES)
        Pair(start, start.plusSeconds(appointment.lengthMinutes * 60L))
    }
    val waits = (conflicts.isNotEmpty() && !confirmed.value) || (hours != null && !outside.value)

    fun move() {
        if (waits) {
            Toast.error(L("Confirm the warning below to move it anyway."))
            return
        }
        val body = mutableMapOf<String, Any?>("startsAt" to DateText.iso(target.first), "endsAt" to DateText.iso(target.second))
        if (outside.value) body["outsideHours"] = true
        if (confirmed.value) body["allowDoubleBooking"] = true
        working = true
        scope.launch {
            try {
                SchedAPI.send<SchedAppointment>("PATCH", "/provider-appointments/${appointment.id}", body)
                announceDataChanged()
                Toast.show(LF("Moved to today. Now check %@ in.", appointment.petName))
                onMoved()
            } catch (e: CancellationException) {
                throw e
            } catch (failure: SchedFailure) {
                val taken = failure.conflicts
                val problem = failure.hoursProblem
                if (taken != null) {
                    conflicts = taken
                    confirmed.value = false
                    Toast.error(failure.api.message)
                } else if (problem != null) {
                    hours = problem
                    outside.value = false
                    Toast.error(failure.api.message)
                } else {
                    schedToast(failure)
                }
            } catch (e: Throwable) {
                schedToast(e)
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (`when` == "future") LF("This visit is on %@", day) else LF("This visit was on %@", day),
        subtitle = "${appointment.petName} · ${appointment.serviceLabel} · ${appointment.clientName}",
        systemImage = "calendar-clock", accent = ModalAccent.butter,
        primaryTitle = if (canMove) L("Move to today and check in") else null,
        isWorking = working,
        cancelTitle = if (`when` == "future") LF("Keep it on %@", day) else L("Back"),
        primaryIcon = "arrow-right-to-line",
        onPrimary = if (canMove) ({ move() }) else null,
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DayBox(L("Booked for"), DateText.longDay(appointment.start), DateText.time(appointment.start), moving = false, modifier = Modifier.weight(1f))
            Glyph("arrow-right", 20.dp, tint = Theme.inkSoft)
            DayBox(L("Moves to"), L("Today"), DateText.time(target.first), moving = true, modifier = Modifier.weight(1f))
        }
        Text(
            if (`when` == "future") LF("Only today’s visits check in. If %@ is here for this visit, move it to now: its %@ time is freed.", appointment.petName, day)
            else LF("Nobody checked %@ in that day. If they’re here now, move the visit to today.", appointment.petName),
            style = Theme.text(14), color = Theme.inkStrong,
        )
        if (appointment.togetherPets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("users", 14.dp, tint = Theme.inkSoft)
                Text(LF("%@ move too: they’re booked together.", schedNames(appointment.togetherPets.mapNotNull { it.name })), style = Theme.smallFont, color = Theme.inkSoft)
            }
        }
        if (!canMove) {
            NoticeCard(tone = NoticeCard.Tone.warning, message = L("Moving a visit takes permission to edit bookings. Ask the front desk or an admin."), icon = null)
        }
        if (`when` == "future" && onWalkIn != null) {
            SchedWayRow(title = L("Here for something else, or an emergency?"), hint = LF("Book a walk-in for today. This visit stays on %@.", day), actionTitle = L("Walk-in"), icon = "zap", action = onWalkIn)
        }
        if (`when` == "past" && onRecordPast != null) {
            SchedWayRow(title = L("It did happen?"), hint = L("Record it as done with the times it had."), actionTitle = L("They came"), icon = "badge-check", action = onRecordPast)
        }
        if (`when` == "past" && onNoShow != null) {
            SchedWayRow(title = L("They never came?"), hint = L("Mark it a no-show and the day is closed."), actionTitle = L("They didn’t come"), icon = "circle-slash", action = onNoShow)
        }
        hours?.let { SchedHoursWarning(problem = it, confirmed = outside) }
        SchedConflictWarning(conflicts = conflicts, confirmed = confirmed)
    }
}

/** One side of the move: the booked day, and today (in blue). */
@Composable
private fun DayBox(label: String, day: String, time: String, moving: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .background(if (moving) Theme.surface else Theme.surfaceMuted, shape)
            .border(if (moving) 2.dp else 1.dp, if (moving) Theme.blue else Theme.borderSoft, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), style = Theme.text(11.2, FontWeight.ExtraBold).tracking(1.1), color = if (moving) Theme.blue else Theme.inkSoft)
        Text(day, style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
        Text(time, style = Theme.text(14), color = Theme.inkSoft)
    }
}

// MARK: - The charge choice ("Terminar" and "Sí vino")

enum class SchedChargeMode(val rawValue: String) {
    desk("desk"), collect("collect"), price("price"), noCharge("noCharge");

    fun actionTitle(record: Boolean, signs: Boolean = false): String {
        if (record) {
            return when (this) {
                collect -> L("Record and charge")
                price -> L("Record and price it")
                noCharge -> L("Record, no charge")
                desk -> L("Record and send to the desk")
            }
        }
        if (signs) {
            return when (this) {
                collect -> L("Finish, sign and charge")
                price -> L("Finish, sign and price it")
                noCharge -> L("Finish and sign")
                desk -> L("Finish, sign and send to the desk")
            }
        }
        return when (this) {
            collect -> L("Finish and charge")
            price -> L("Finish and price it")
            noCharge -> L("Finish, no charge")
            desk -> L("Finish and send to the desk")
        }
    }

    /** What happened to the bill, from the move's answer: the toast's second sentence. */
    fun outcome(bill: SchedMoveBill?): String = when (this) {
        noCharge -> L("No charge.")
        collect -> L("Its bill is open to charge.")
        price -> L("Its bill is open to put the price.")
        desk -> when {
            bill == null -> L("The front desk has it to charge.")
            bill.status == "paid" -> L("Nothing to charge.")
            bill.status == "draft" -> L("The bill waits for a price before it is charged.")
            else -> L("The bill went to the front desk to charge.")
        }
    }

    fun body(reason: String): Map<String, Any?> =
        if (this == noCharge) mapOf("noCharge" to true, "noChargeReason" to reason.nonEmpty) else mapOf("noCharge" to false)

    companion object {
        fun modes(pay: Boolean, price: Boolean, waive: Boolean, collects: Boolean, billReady: Boolean?): List<SchedChargeMode> {
            val modes = mutableListOf(desk)
            if (collects && pay && billReady != false) modes.add(collect)
            if (collects && price && billReady != true) modes.add(SchedChargeMode.price)
            if (waive) modes.add(noCharge)
            return modes
        }
    }
}

/** `SchedChargeChoice.missingReason(_:_:)` and `.refusal(_:form:)`. */
object SchedChargeChoice {
    /** "No cobrar" without its reason stops before anything is sent. */
    fun missingReason(mode: SchedChargeMode, reason: String): String? =
        if (mode == SchedChargeMode.noCharge && reason.nonEmpty == null) L("Write why it is finished without charging.") else null

    /** A refusal about not charging, said under the reason; false for any other. */
    fun refusal(error: Throwable, form: FormState): Boolean {
        val failure = error as? SchedFailure ?: return false
        val message = when (failure.code) {
            "reason_required" -> L("Write why it is finished without charging.")
            "no_charge_paid" -> L("A payment is already on this visit’s bill: give it back before finishing without charging.")
            "no_charge_forbidden" -> L("You can’t finish a visit without charging. Send it to the desk instead.")
            else -> null
        } ?: return false
        form.check(listOf("noChargeReason" to message))
        return true
    }
}

/** The charge part of "Terminar" and "Sí vino": one look, one set of rules. */
@Composable
fun SchedChargeChoice(
    modes: List<SchedChargeMode>,
    mode: MutableState<SchedChargeMode>,
    reason: MutableState<String>,
    invoice: SchedInvoice?,
    form: FormState,
    modifier: Modifier = Modifier,
) {
    val lines = invoice?.lineItems ?: emptyList()

    fun title(mode: SchedChargeMode): String = when (mode) {
        SchedChargeMode.desk -> L("Send to the front desk to pay")
        SchedChargeMode.collect -> L("Charge now")
        SchedChargeMode.price -> L("Put the price myself")
        SchedChargeMode.noCharge -> L("No charge")
    }

    fun icon(mode: SchedChargeMode): String = when (mode) {
        SchedChargeMode.desk -> "send"
        SchedChargeMode.collect -> "hand-coins"
        SchedChargeMode.price -> "tag"
        SchedChargeMode.noCharge -> "circle-slash"
    }

    // The web's tones: the desk mango while the bill still needs a price (someone else finishes it).
    fun accent(mode: SchedChargeMode): Color {
        val notReady = (invoice != null && lines.isEmpty()) || lines.any { it.needsPrice }
        return when (mode) {
            SchedChargeMode.desk -> if (notReady) Theme.mango else Theme.aqua
            SchedChargeMode.collect -> Theme.pink
            SchedChargeMode.price -> Theme.mango
            SchedChargeMode.noCharge -> Theme.aqua
        }
    }

    fun hint(mode: SchedChargeMode): String {
        val unpriced = lines.count { it.needsPrice }
        val currency = invoice?.currency
        val total = invoice?.totalAmount ?: 0
        return when (mode) {
            SchedChargeMode.desk -> when {
                invoice != null && lines.isEmpty() -> L("No charges on the bill yet. The front desk adds them and sets the price before charging.")
                unpriced == 1 -> L("1 line has no price yet. The front desk sets it before charging.")
                unpriced > 1 -> LF("%@ lines have no price yet. The front desk sets them before charging.", unpriced.toString())
                total > 0 -> LF("The bill so far: %@. The desk can adjust it.", Money.format(total, currency))
                else -> L("The visit’s charges go to the desk; they can adjust them.")
            }
            SchedChargeMode.collect -> if (total > 0) LF("The payment opens next: %@.", Money.format(total, currency)) else L("The payment opens next.")
            SchedChargeMode.price -> L("Its bill opens next. Once every line has a price it goes to the desk by itself.")
            SchedChargeMode.noCharge -> L("A courtesy or a warranty recheck. Nothing to pay.")
        }
    }

    ModalSection(title = L("Charges"), modifier = modifier) {
        // The web's choice cards: each way with its icon and colour.
        OptionCards(
            options = modes.map { OptionCard(it, title = title(it), description = hint(it), icon = icon(it), accent = accent(it)) },
            selection = mode,
        )
        if (mode.value == SchedChargeMode.noCharge) {
            PMTextField(
                L("Why no charge"), text = reason, key = "noChargeReason", form = form,
                prompt = L("e.g. Recheck included, a rescue"), hint = L("The owner sees why on the day’s exceptions."),
            )
        }
    }
}

// MARK: - "¿Cuándo vuelve?"

data class SchedComeBack(
    /** "none", a preset's id, or "date". */
    var choice: String = "none",
    var date: Instant = Instant.now(),
    var remindFamily: Boolean = true,
    var touched: Boolean = false,
) {
    data class Preset(val id: String, val label: String, val weeks: Int?, val months: Int?)

    data class Resolved(val target: String, val on: String, val moved: Boolean, val noneOpen: Boolean, val weeks: Int, val noticeAhead: Boolean)

    /**
     * What the finish sends as `comeBack` (Swift `Any??`): `null` leaves it out; `Input(null)` sends
     * null ("No need" chosen on purpose); `Input(body)` sends the day.
     */
    data class Input(val value: Map<String, Any?>?)

    fun resolve(category: String?, availability: SchedAvailability?, closures: List<SchedClosure>): Resolved? {
        if (choice == "none") return null
        val today = SchedDay.today
        val base = LocalDate.parse(today.take(10))
        val preset = presets(category).firstOrNull { it.id == choice }
        val target = if (preset != null) {
            val day = preset.weeks?.let { base.plusDays(it * 7L) } ?: base.plusMonths((preset.months ?: 1).toLong())
            day.toString()
        } else {
            SchedDay.key(date)
        }
        val tomorrow = SchedDay.addDays(today, 1)
        val open = SchedHours.nearestOpenDay(target, closures, availability, tomorrow)
        val on = open ?: target
        val days = ChronoUnit.DAYS.between(base, LocalDate.parse(on.take(10))).toInt()
        val weeks = preset?.weeks ?: (preset?.months?.let { Math.round(it * 30.44 / 7).toInt() } ?: minOf(52, maxOf(1, Math.round(days / 7.0).toInt())))
        return Resolved(target = target, on = on, moved = open != null && open != target, noneOpen = open == null, weeks = weeks, noticeAhead = days >= 9)
    }

    /** What the finish sends: a day, null ("No need" chosen on purpose), or nothing (leave it out). */
    fun input(category: String?, availability: SchedAvailability?, closures: List<SchedClosure>, familyOnApp: Boolean): Input? {
        val resolved = resolve(category, availability, closures)
            ?: return if (touched && choice == "none") Input(null) else null
        val body: Map<String, Any?> = mapOf(
            "on" to resolved.on,
            "weeks" to if (category == "groomer") resolved.weeks else null,
            "remindFamily" to (category != "groomer" && familyOnApp && remindFamily && resolved.noticeAhead),
        )
        return Input(body)
    }

    companion object {
        fun presets(category: String?): List<Preset> {
            fun w(n: Int, label: String) = Preset(id = "w$n", label = label, weeks = n, months = null)
            fun m(n: Int, label: String) = Preset(id = "m$n", label = label, weeks = null, months = n)
            if (category == "groomer") return listOf(w(4, L("In 4 weeks")), w(6, L("In 6 weeks")), w(8, L("In 8 weeks")), w(10, L("In 10 weeks")))
            if (category == null || category == "vet" || category == "emergency") {
                return listOf(w(1, L("In 1 week")), w(2, L("In 2 weeks")), w(3, L("In 3 weeks")), m(1, L("In 1 month")), m(3, L("In 3 months")), m(6, L("In 6 months")), m(12, L("In 1 year")))
            }
            return listOf(w(2, L("In 2 weeks")), m(1, L("In 1 month")), m(3, L("In 3 months")), m(6, L("In 6 months")))
        }

        /** Walks, sitting, classes, daycare and boarding repeat instead. */
        fun asks(category: String?): Boolean =
            !listOf("walker", "sitter", "trainer", "daycare", "boarding", "pharmacy").contains(category ?: "")
    }
}

@Composable
fun SchedComeBackFields(
    value: MutableState<SchedComeBack>,
    category: String?,
    availability: SchedAvailability?,
    closures: List<SchedClosure>,
    familyOnApp: Boolean,
    clientName: String,
    alsoFor: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val resolved = value.value.resolve(category, availability, closures)

    fun pick(choice: String) {
        value.value = value.value.copy(choice = choice, touched = true)
    }

    fun sentence(resolved: SchedComeBack.Resolved): String {
        var text = if (resolved.moved) LF("%@ is closed: it comes back on %@.", SchedDay.shortLabel(resolved.target), SchedDay.shortLabel(resolved.on))
            else LF("Comes back on %@.", SchedDay.shortLabel(resolved.on))
        if (resolved.noneOpen) text += " " + L("Nothing is open around that day; pick the day when booking.")
        if (alsoFor.isNotEmpty()) text += " " + LF("Also for %@.", alsoFor.joinToString(", "))
        return text
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(title = L("No need"), isSelected = value.value.choice == "none") { pick("none") }
            SchedComeBack.presets(category).forEach { preset ->
                FilterChip(title = preset.label, isSelected = value.value.choice == preset.id) { pick(preset.id) }
            }
            FilterChip(title = L("Another day…"), isSelected = value.value.choice == "date") { pick("date") }
        }
        if (value.value.choice == "date") {
            DateTimeField(
                label = L("Choose a day"),
                date = stateOf({ value.value.date }) { value.value = value.value.copy(date = it, touched = true) },
                zone = SchedDay.zone,
                min = Instant.now().plusSeconds(86_400),
                components = setOf(DatePickerComponent.date),
            )
        }
        if (resolved != null) {
            val shape = RoundedCornerShape(Theme.boxRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surface, shape)
                    .border(1.dp, Theme.borderSoft, shape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Glyph(if (resolved.moved) "calendar-x" else "calendar-check", 16.dp, tint = if (resolved.moved) Theme.mango else Theme.blue, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sentence(resolved), style = Theme.text(15, FontWeight.SemiBold), color = Theme.inkStrong)
                        Text(
                            if (category == "groomer") L("It becomes this pet’s grooming rhythm: Due shows them when it’s time.")
                            else if (resolved.noticeAhead) L("It goes to Due with this service. You get a notice a week before, and again if nothing is booked 3 days after.")
                            else L("It goes to Due with this service. You get a notice if nothing is booked 3 days after."),
                            style = Theme.captionFont, color = Theme.inkSoft,
                        )
                    }
                }
                if (category != "groomer") {
                    if (!resolved.noticeAhead) {
                        Text(LF("It’s within a week: tell %@ now, before they leave.", clientName), style = Theme.captionFont, color = Theme.inkSoft)
                    } else if (familyOnApp) {
                        ToggleRow(
                            title = LF("Remind %@ a week before", clientName),
                            subtitle = L("Once, in PawMiaw, with a button to pick a time. Not if it’s already booked."),
                            isOn = stateOf({ value.value.remindFamily }) { value.value = value.value.copy(remindFamily = it) },
                        )
                    } else {
                        Text(LF("%@ isn’t on PawMiaw: remind them by WhatsApp from Due.", clientName), style = Theme.captionFont, color = Theme.inkSoft)
                    }
                }
            }
        }
    }
}

// MARK: - Finish ("Terminar")

/**
 * The visit page's (and Home's) "Terminar": the one finish sheet (Services' SvcFinishSheet), with what
 * the page already knows — the drafts and who signs, the bill, and where the charge goes next.
 */
@Composable
fun SchedFinishSheet(
    appointment: SchedAppointment,
    drafts: Int,
    signed: Int,
    invoice: SchedInvoice?,
    /** The person finishing, when they can sign (owner, admin, vet): (memberId, name). */
    signer: Pair<String, String>?,
    /** The bill can be opened from here to charge or to price it. */
    collects: Boolean,
    /** After finishing with "Charge now" or "Put the price myself". */
    onAfter: (SchedChargeMode) -> Unit,
    /** Left open since an earlier day: "Sí vino" instead. */
    onEarlierDay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val a = appointment
    val prepare: (suspend () -> Map<String, Any?>)? = if (a.atHome == true) ({ mapOf("at" to DateText.iso(Instant.now())) }) else null
    SvcFinishSheet(
        target = SvcFinishTarget(
            id = a.id, petName = a.petName, serviceLabel = a.serviceLabel, serviceCategory = a.serviceCategory,
            clientName = a.clientName, familyOnApp = a.clientHouseholdId != null,
            assignedId = a.assignedMember?.id, assignedName = a.assignedMember?.name,
            together = a.togetherPets.mapNotNull { it.name }, locationId = a.locationId,
        ),
        onFinished = {},
        prepare = prepare,
        invoice = invoice,
        known = SvcFinishKnown(drafts = drafts, signed = signed, signer = signer),
        collects = collects,
        onAfter = onAfter,
        onEarlierDay = onEarlierDay,
        modifier = modifier,
    )
}

// MARK: - "Sí vino"

/** Records a visit nobody closed on its day as done, with the times it had (the last 30 days). */
@Composable
fun SchedRecordPastSheet(
    appointment: SchedAppointment,
    invoice: SchedInvoice?,
    collects: Boolean,
    modifier: Modifier = Modifier,
    onAfter: (SchedChargeMode) -> Unit,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val zone = SchedData.zone(appointment.locationId)
    val day = appointment.start?.let { SchedDay.key(it, zone) } ?: SchedDay.today
    val quarters = (0 until 1440 step 15).toList()
    fun at(minute: Int): Instant = SchedDay.at(day, minOf(minute, 1439), zone)

    // Where the times start: when it started (or was booked), to the quarter, for its booked length.
    val first = remember {
        val booked = DateText.parse(appointment.startedAt ?: appointment.startsAt) ?: Instant.now()
        val minute = Math.round(SchedDay.minuteOfDay(booked, zone) / 15.0).toInt() * 15
        val start = minOf(1425, minute)
        Pair(start, minOf(1439, start + maxOf(15, Math.round(appointment.lengthMinutes / 15.0).toInt() * 15)))
    }
    var start by remember { mutableIntStateOf(first.first) }
    val end = remember { mutableIntStateOf(first.second) }
    val mode = remember { mutableStateOf(SchedChargeMode.desk) }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val problem: String? = when {
        end.intValue <= start -> L("The end must be after the start.")
        at(end.intValue) > Instant.now() -> L("The end can't be later than now")
        else -> null
    }
    val modes = run {
        val ready: Boolean? = invoice?.let { bill -> !(bill.lineItems ?: emptyList()).isEmpty() && !(bill.lineItems ?: emptyList()).any { it.needsPrice } }
        SchedChargeMode.modes(
            pay = workspace.can(Permission.billingRecordPayment), price = workspace.can(Permission.billingEditInvoice),
            waive = workspace.can(Permission.billingWaiveVisit), collects = collects, billReady = ready,
        )
    }

    fun submit() {
        problem?.let { Toast.error(it); return }
        if (!form.check(listOf("noChargeReason" to SchedChargeChoice.missingReason(mode.value, reason.value)))) return
        val body = mode.value.body(reason.value).toMutableMap()
        body["startedAt"] = DateText.iso(at(start))
        body["finishedAt"] = DateText.iso(at(end.intValue))
        val chosen = mode.value
        val pet = appointment.petName
        val id = appointment.id
        working = true
        scope.launch {
            try {
                val result: SchedMoveResponse = SchedAPI.send("POST", "/provider-appointments/$id/record-past", body)
                announceDataChanged()
                Toast.show("${LF("Recorded. %@’s family will see it in the history.", pet)} ${chosen.outcome(result.bill)}")
                dismiss()
                if (chosen == SchedChargeMode.collect || chosen == SchedChargeMode.price) onAfter(chosen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!SchedChargeChoice.refusal(e, form)) schedToast(e)
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("%@ came on %@", appointment.petName, DateText.day(appointment.start)),
        subtitle = "${appointment.serviceLabel} · ${appointment.clientName}",
        systemImage = "badge-check", accent = ModalAccent.blue, isDirty = reason.value.isNotEmpty(), form = form,
        primaryTitle = mode.value.actionTitle(record = true), isWorking = working, cancelTitle = L("Back"), primaryIcon = "badge-check",
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        appointment.familyAttendance?.let { answer ->
            Text(
                if (answer == "attended") L("The family says they went.") else L("The family says they didn’t go."),
                style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Text(L("Nobody closed this visit that day. Say when it really was: its bill opens and the family sees it in the history."), style = Theme.text(14), color = Theme.inkStrong)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            MenuField(
                label = L("Started"),
                options = quarters.map { it to DateText.time(at(it), zone) },
                selection = stateOf({ start }) { value ->
                    val length = maxOf(15, end.intValue - start)
                    start = value
                    end.intValue = minOf(1439, value + length)
                },
                modifier = Modifier.weight(1f),
            )
            MenuField(
                label = L("Ended"),
                options = (quarters.filter { it > start } + 1439).map { it to DateText.time(at(it), zone) },
                selection = stateOf({ end.intValue }) { end.intValue = it },
                modifier = Modifier.weight(1f),
            )
        }
        if (problem != null) Text(problem, style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive)
        SchedChargeChoice(modes = modes, mode = mode, reason = reason, invoice = invoice, form = form)
    }
}

// MARK: - No-show and cancel (with the fee choice)

/** The visit page's "didn't come" or "cancel" for one visit (Swift `init(appointment:mode:)`). */
@Composable
fun SchedMissedSheet(appointment: SchedAppointment, mode: String, modifier: Modifier = Modifier) {
    val time = if (SchedDay.key(appointment.startsAt) == SchedDay.today) DateText.time(appointment.start) else DateText.dayTime(appointment.start)
    SchedMissedSheet(
        appointmentId = appointment.id, petName = appointment.petName, clientName = appointment.clientName,
        subtitle = "${appointment.serviceLabel} · $time · ${appointment.clientName}", mode = mode,
        together = appointment.togetherPets.mapNotNull { it.name },
        modifier = modifier,
    )
}

/**
 * "Didn't come" and cancelling — a visit, or a pet taken out of a session — with the reason and the
 * fee when the server asks for a decision; a family's other pets booked with it go too unless only
 * this one is chosen. The visit page, Home and Paseos y clases use it.
 */
@Composable
fun SchedMissedSheet(
    appointmentId: String,
    petName: String,
    clientName: String,
    subtitle: String,
    /** "no_show" or "cancel". */
    mode: String,
    /** The family's other pets booked with it. */
    together: List<String> = emptyList(),
    /** The toast once cancelled ("Visit cancelled.", or a session's "Taken out. The place is open."). */
    cancelledText: String = L("Visit cancelled."),
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val reason = remember { mutableStateOf("") }
    var fee by remember { mutableStateOf<SchedFeeRequired?>(null) }
    val decision = remember { mutableStateOf("charge") }
    val feeNote = remember { mutableStateOf("") }
    val onlyThisPet = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    fun submit() {
        if (!form.check(listOf(
                "reason" to if (mode == "cancel" && reason.value.nonEmpty == null) L("Say why the visit is cancelled") else null,
                "feeNote" to if (fee != null && decision.value == "waive" && feeNote.value.nonEmpty == null) L("Say why the fee is waived") else null,
            ))) return
        val body = mutableMapOf<String, Any?>()
        if (fee != null) body["fee"] = mapOf("decision" to decision.value, "note" to feeNote.value.nonEmpty)
        if (together.isNotEmpty()) body["onlyThisPet"] = onlyThisPet.value
        if (mode == "cancel") body["reason"] = reason.value.nonEmpty
        val path = if (mode == "no_show") "no-show" else "cancel"
        working = true
        scope.launch {
            try {
                val result: SchedMoveResponse = SchedAPI.send("POST", "/provider-appointments/$appointmentId/$path", body)
                announceDataChanged()
                Toast.show(
                    if (result.feeInvoiceId != null) LF("Done. The fee is on %@’s account.", clientName)
                    else if (mode == "no_show") LF("%@ marked as no-show.", petName) else cancelledText
                )
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val wanted = (e as? SchedFailure)?.fee
                if (wanted != null) {
                    fee = wanted
                    Toast.show(L("Decide about the fee, then confirm."))
                } else {
                    form.apply(schedAPIError(e), mapOf("reason" to "reason", "fee.note" to "feeNote"))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (mode == "no_show") LF("%@ didn’t come", petName) else L("Cancel the visit"),
        subtitle = subtitle,
        systemImage = if (mode == "no_show") "circle-slash" else "calendar-x-2", accent = ModalAccent.pink,
        isDirty = reason.value.isNotEmpty() || feeNote.value.isNotEmpty(), form = form,
        primaryTitle = if (mode == "no_show") L("Mark no-show") else L("Cancel visit"),
        primaryVariant = ButtonVariant.destructive, isWorking = working, cancelTitle = L("Back"),
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        if (together.isNotEmpty()) {
            OptionCards(
                options = listOf(
                    OptionCard(false, title = L("The whole family"), description = schedNames(listOf(petName) + together), icon = "users", accent = Theme.pink),
                    OptionCard(true, title = L("Only this pet"), description = L("The others stay as they are."), icon = "circle-slash", accent = Theme.pink),
                ),
                selection = onlyThisPet,
            )
        }
        if (mode == "cancel") {
            ModalSection(title = L("Why"), required = true) {
                SchedBareField(prompt = L("The family called, the pet is sick…"), text = reason, key = "reason", form = form)
            }
        } else {
            Text(L("The visit stays in the history as a no-show. You can rebook it later."), style = Theme.text(14), color = Theme.inkSoft)
        }
        fee?.let { required ->
            val amount = Money.format(required.amount, required.currency)
            ModalSection(
                title = if (required.kind == "no_show") L("No-show fee") else L("Late cancellation fee"),
                subtitle = LF("You decide: charge %@ to their account, or waive it.", amount),
            ) {
                OptionCards(
                    options = listOf(
                        OptionCard("charge", title = LF("Charge %@", amount), description = L("It goes on their account to collect."), icon = "hand-coins", accent = Theme.mango),
                        OptionCard("waive", title = L("Waive it"), description = L("Nothing is charged; the reason is kept."), icon = "badge-check", accent = Theme.aqua),
                    ),
                    selection = decision,
                )
                if (decision.value == "waive") {
                    SchedBareField(prompt = L("Why it is waived (first time, emergency…)"), text = feeNote, key = "feeNote", form = form, multiline = false)
                }
            }
        }
    }
}

// MARK: - Book again

@Composable
fun SchedRebookSheet(appointment: SchedAppointment, modifier: Modifier = Modifier) {
    val lengths = listOf(15, 30, 45, 60, 90, 120)
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var day by remember { mutableStateOf(Instant.now().plusSeconds(86_400)) }
    var minute by remember { mutableIntStateOf(appointment.start?.let { SchedDay.minuteOfDay(it) / 15 * 15 } ?: (10 * 60)) }
    var length by remember { mutableIntStateOf(if (lengths.contains(appointment.lengthMinutes)) appointment.lengthMinutes else 30) }
    val outside = remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf<List<SchedConflict>>(emptyList()) }
    val confirmed = remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf<SchedHoursProblem?>(null) }
    var working by remember { mutableStateOf(false) }

    fun resetWarnings() {
        conflicts = emptyList()
        confirmed.value = false
        hours = null
    }

    fun submit() {
        if (conflicts.isNotEmpty() && !confirmed.value) {
            Toast.error(L("Double booking — read the warning and confirm to book anyway."))
            return
        }
        val start = SchedDay.at(SchedDay.key(day), minute)
        val end = start.plusSeconds(length * 60L)
        val body = mapOf<String, Any?>("startsAt" to DateText.iso(start), "endsAt" to DateText.iso(end), "outsideHours" to outside.value, "allowDoubleBooking" to confirmed.value)
        working = true
        scope.launch {
            try {
                SchedAPI.send<SchedMoveResponse>("POST", "/provider-appointments/${appointment.id}/rebook", body)
                announceDataChanged()
                Toast.show(LF("Booked again: %@", DateText.dayTime(start)))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (failure: SchedFailure) {
                val taken = failure.conflicts
                val problem = failure.hoursProblem
                if (failure.code == "start_in_past") {
                    form.check(listOf("startsAt" to (failure.api.field("startsAt") ?: failure.api.message)))
                } else if (taken != null) {
                    conflicts = taken
                    confirmed.value = false
                    Toast.error(failure.api.message)
                } else if (problem != null) {
                    hours = problem
                    outside.value = false
                    Toast.error(failure.api.message)
                } else {
                    schedToast(failure)
                }
            } catch (e: Throwable) {
                schedToast(e)
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Book %@ again", appointment.petName), subtitle = appointment.serviceLabel,
        systemImage = "calendar-clock", accent = ModalAccent.aqua, form = form,
        primaryTitle = L("Book again"), isWorking = working, cancelTitle = L("Back"),
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        DateTimeField(
            label = L("Day"),
            date = stateOf({ day }) { if (it != day) { day = it; resetWarnings() } },
            zone = SchedDay.zone, min = Instant.now(), components = setOf(DatePickerComponent.date),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            MenuField(
                label = L("Time"),
                options = (0 until 1440 step 15).map { it to SchedDay.timeLabel(it) },
                selection = stateOf({ minute }) { if (it != minute) { minute = it; resetWarnings() } },
                key = "startsAt", form = form,
                modifier = Modifier.weight(1f),
            )
            MenuField(
                label = L("Length"),
                options = lengths.map { it to LF("%@ min", it.toString()) },
                selection = stateOf({ length }) { if (it != length) { length = it; resetWarnings() } },
                modifier = Modifier.weight(1f),
            )
        }
        ToggleRow(title = L("Outside opening hours"), isOn = outside)
        hours?.let { SchedHoursWarning(problem = it, confirmed = outside) }
        SchedConflictWarning(conflicts = conflicts, confirmed = confirmed)
    }
}
