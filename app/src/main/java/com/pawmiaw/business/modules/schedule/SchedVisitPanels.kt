package com.pawmiaw.business.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Instant

//
//  SchedVisitPanels.kt (iOS SchedVisitPanels.swift)
//
//  The visit page's parts (web provider.appointments.$appointmentId.tsx and
//  visits/visit-note.tsx): the visit's four steps, the banners (a family
//  asking for a change, a missed visit's story), the arrival and waiting
//  locks, the note written during the visit (saved as a draft as you type,
//  signed by whoever signs), what else was recorded, the closing card and the
//  bill's summary.
//

// MARK: - The journey

/** A step of the visit (arrive, the visit, sign, pay), shown as one of the header's numbers. */
data class SchedJourneyStep(
    val id: String,
    val label: String,
    val value: String,
    val extra: String? = null,
    val state: State,
    val systemImage: String,
) {
    enum class State { done, now, next, attention, skip }

    /**
     * The header's stat (web `journeyStat`): done is ticked, where the visit is now is
     * underlined, what needs someone reads red, what is still to come is quieter.
     */
    val stat: ModuleStat
        @Composable get() = ModuleStat(
            id = id, label = label, value = value, hint = extra,
            icon = if (state == State.done) "check" else systemImage,
            accent = when (state) {
                State.done -> Theme.lime
                State.attention -> Theme.pink
                State.now -> Theme.aqua
                else -> Theme.surfaceMuted
            },
            attention = state == State.attention, active = state == State.now, muted = state == State.next || state == State.skip,
        )
}

// MARK: - Banners

/** `SchedVisitBanners.any(_:)`. */
object SchedVisitBanners {
    /** Whether any banner shows (the header draws a rule above them only then). */
    fun any(a: SchedAppointment): Boolean =
        (a.status == "scheduled" && listOf("reschedule_requested", "cancellation_requested", "delivery_failed", "expired", "declined").contains(a.confirmationStatus ?: ""))
            || a.isGone || (a.status == "completed" && a.noCharge == true)
            || (a.arrivalNote.nonEmpty != null && (a.status == "checked_in" || a.status == "in_progress"))
}

private enum class BannerTone { warn, danger, info }

/**
 * What the visit is waiting on: a request from the family, a missed visit's story — who
 * cancelled it, its fee — and the family's "Sí fuimos" after a no-show with what the business
 * can do about it (web `Banners`; docs/family-money.md §5): full-width rows under the header's
 * client line, each tinted by its tone.
 */
@Composable
fun SchedVisitBanners(
    appointment: SchedAppointment,
    onEdit: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onKeep: (() -> Unit)? = null,
    /** "Perdonar el cargo": the no-show fee's bill, forgiven with a reason. */
    onForgiveFee: (() -> Unit)? = null,
    /** "Descartar": off Home's "Te necesita"; the answer stays here. */
    onDismissDispute: (() -> Unit)? = null,
    dismissing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val a = appointment
    // The fee's story and how an online booking ended without a visit.
    val fee: String? = if (a.feeKind == null) null
        else if (a.feeDecision == "charged") L("The fee was charged to their account.") else LF("The fee was waived: %@", a.feeNote ?: "—")
    val outcome: String? = when (a.onlineOutcome) {
        "declined" -> a.declineReason?.let { LF("Declined: %@", it) } ?: L("Declined")
        "expired" -> L("Released: nobody answered in time")
        "cancelled_by_family" -> L("The family cancelled it")
        else -> null
    }
    Column(modifier.fillMaxWidth().background(Theme.borderSoft), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (a.status == "scheduled" && a.confirmationStatus == "reschedule_requested") {
            Banner(
                BannerTone.warn, L("The family asked to move this visit"),
                listOf(a.confirmationPreferredTime, a.confirmationNote).mapNotNull { it.nonEmpty }.joinToString(" · ").nonEmpty,
                showsActions = onEdit != null || onKeep != null,
            ) {
                if (onEdit != null) PMButton(L("Move it"), size = ButtonSize.header, action = onEdit)
                if (onKeep != null) PMButton(L("Keep as booked"), variant = ButtonVariant.outline, size = ButtonSize.header, action = onKeep)
            }
        }
        if (a.status == "scheduled" && a.confirmationStatus == "cancellation_requested") {
            Banner(
                BannerTone.warn, L("The family asked to cancel"), a.confirmationNote,
                showsActions = onCancel != null || onKeep != null,
            ) {
                if (onCancel != null) PMButton(L("Cancel visit"), size = ButtonSize.header, action = onCancel)
                if (onKeep != null) PMButton(L("Keep it"), variant = ButtonVariant.outline, size = ButtonSize.header, action = onKeep)
            }
        }
        if (a.status == "scheduled" && listOf("delivery_failed", "expired", "declined").contains(a.confirmationStatus ?: "")) {
            Banner(
                BannerTone.warn,
                if (a.confirmationStatus == "declined") L("The family declined this visit") else L("The confirmation did not reach them"),
                a.clientPhone?.let { LF("Call them: %@", it) },
            )
        }
        if (a.isGone) {
            // Who cancelled: the family (a fee may follow) or the business (never one). Older visits don't say.
            val cancelledTitle = when (a.cancelledBy) {
                "family" -> L("Cancelled by the family")
                "business" -> L("Cancelled by your business")
                else -> L("Cancelled")
            }
            Banner(
                BannerTone.danger,
                if (a.status == "no_show") L("They didn’t come") else cancelledTitle,
                listOf(outcome ?: a.cancelReason, fee).mapNotNull { it.nonEmpty }.joinToString(" · ").nonEmpty,
            )
        }
        // "Sí fuimos": the family says the pet did come after the no-show was recorded. Nothing changes by itself.
        val disputedAt = a.disputedAt
        if (a.status == "no_show" && disputedAt != null) {
            val dismissedAt = a.disputeDismissedAt
            Banner(
                BannerTone.warn,
                LF("The family says they did come (%@)", DateText.dayTime(disputedAt)),
                if (dismissedAt != null) LF("Dismissed on %@.", DateText.date(dismissedAt))
                else if (onForgiveFee != null) L("You decide: forgive the fee or dismiss it. Nothing changes by itself.")
                else L("Nothing changes by itself."),
                showsActions = onForgiveFee != null || onDismissDispute != null,
            ) {
                if (onForgiveFee != null) PMButton(L("Forgive the fee"), size = ButtonSize.header, action = onForgiveFee)
                if (onDismissDispute != null) PMButton(L("Dismiss"), variant = ButtonVariant.outline, size = ButtonSize.header, isWorking = dismissing, action = onDismissDispute)
            }
        }
        if (a.status == "completed" && a.noCharge == true) {
            Banner(BannerTone.info, L("Finished with no charge."), a.noChargeReason)
        }
        val note = a.arrivalNote.nonEmpty
        if (note != null && (a.status == "checked_in" || a.status == "in_progress")) {
            Banner(BannerTone.info, L("At the desk"), note)
        }
    }
}

@Composable
private fun Banner(tone: BannerTone, title: String, body: String?, showsActions: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    val fill = when (tone) {
        BannerTone.warn -> Theme.wash(Theme.mango, 16.0)
        BannerTone.danger -> Theme.wash(Theme.destructive, 8.0)
        BannerTone.info -> Theme.wash(Theme.aqua, 12.0)
    }
    Column(
        Modifier.fillMaxWidth().background(fill).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Glyph("triangle-alert", 16.dp, tint = if (tone == BannerTone.danger) Theme.destructive else Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                if (body != null) Text(body, style = Theme.text(14), color = Theme.inkSoft)
            }
        }
        // The web draws the actions' row only when there is one (an empty one adds no gap).
        if (showsActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

// MARK: - Before the visit is written

/**
 * A lock in place of the visit's work (web `ArrivalLock`/`WaitingLock`): a dashed card
 * tinted by the step, a 56 well, a Fredoka 24 title, the line, then what comes next.
 */
@Composable
private fun SchedLockCard(icon: String, iconInk: Color, tint: Color, border: Double, fill: Double, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(tint, fill), shape)
            .dashedBorder(1.dp, Theme.mix(if (tint == Theme.aqua) Theme.blue else tint, border, Theme.borderSoft), Theme.cardRadius, on = 5.dp, off = 4.dp)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).cardShadow(CircleShape).background(Theme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(icon, 24.dp, tint = iconInk) }
        content()
    }
}

/**
 * The clock the locks read their minutes from: now, again every 30 s (iOS: the page's `tick`
 * redraws them; here they keep their own so a skipped recomposition never freezes the minutes).
 */
@Composable
private fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    return now
}

/** Before the pet arrives, the visit is read, not written. */
@Composable
fun SchedArrivalLock(
    appointment: SchedAppointment,
    late: Boolean,
    dayWhen: String,
    canStart: Boolean,
    onArrive: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val pet = appointment.petName
    val now = rememberNow()
    val sentence = run {
        val time = DateText.time(appointment.start)
        val text: String
        if (dayWhen != "today") {
            text = LF("Booked for %@ at %@", DateText.day(appointment.start), time)
        } else if (late) {
            val minutes = maxOf(0L, (now.epochSecond - (appointment.start ?: now).epochSecond) / 60)
            return@run LF("Booked for %@, %@ min ago.", time, minutes.toString())
        } else {
            text = LF("Booked for %@", time)
        }
        if (text.endsWith(".")) text else "$text."
    }
    Box(modifier) {
        SchedLockCard(icon = "door-open", iconInk = Theme.blue, tint = Theme.aqua, border = 40.0, fill = 8.0) {
            Text(
                when (dayWhen) {
                    "future" -> LF("%@ comes another day", pet)
                    "past" -> LF("%@ was never checked in", pet)
                    else -> LF("%@ hasn’t arrived yet", pet)
                },
                style = Theme.heading(24), color = Theme.inkStrong, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                sentence + " " + L("The note, the charges and the report open when you register the arrival. Until then, read the chart to prepare."),
                style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 448.dp).padding(top = 4.dp),
            )
            if (onArrive != null) {
                // Whoever can see the pet starts the visit as it arrives; the front desk sends it to the waiting room.
                PMButton(
                    if (canStart) L("Register arrival and start") else L("Register arrival"),
                    systemImage = if (canStart) "play" else "log-in",
                    modifier = Modifier.padding(top = 20.dp),
                    action = onArrive,
                )
            } else {
                Text(L("The front desk registers the arrival."), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

/** Checked in, not started: the pet is in the waiting room. */
@Composable
fun SchedWaitingLock(
    appointment: SchedAppointment,
    onStart: (() -> Unit)?,
    starting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val waited = maxOf(0L, (now.epochSecond - (DateText.parse(appointment.arrivedAt) ?: now).epochSecond) / 60)
    val facts = buildList {
        appointment.arrivalNote.nonEmpty?.let { add(L("Why they came") to it) }
        val grams = appointment.petWeight
        if (grams != null && grams > 0) {
            val number = NumberFormat.getNumberInstance(LocalizationManager.locale).apply { minimumFractionDigits = 0; maximumFractionDigits = 2 }
            add(L("Weight on file") to number.format(grams / 1000) + " kg")
        }
        appointment.room?.let { add(L("Room") to it) }
    }
    Box(modifier) {
        SchedLockCard(icon = "clock-3", iconInk = Theme.inkStrong, tint = Theme.lime, border = 60.0, fill = 10.0) {
            Text(
                LF("%@ is waiting · %@ min", appointment.petName, waited.toString()),
                style = Theme.heading(24), color = Theme.inkStrong, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                L("The note, the records, the report and the charges open when the visit starts."),
                style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 448.dp).padding(top = 4.dp),
            )
            if (facts.isNotEmpty()) {
                Column(Modifier.widthIn(max = 448.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val box = RoundedCornerShape(Theme.boxRadius)
                    facts.forEach { (label, value) ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Theme.surface, box)
                                .border(1.dp, Theme.borderSoft, box)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(label.uppercase(), style = Theme.text(11.2, FontWeight.ExtraBold).tracking(1.1), color = Theme.inkSoft)
                            Text(value, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        }
                    }
                }
            }
            if (onStart != null) {
                PMButton(L("Start the visit"), systemImage = "play", isWorking = starting, modifier = Modifier.padding(top = 20.dp), action = onStart)
            } else {
                Text(L("Waiting for the vet to start the visit."), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

/** A finished visit whose note still waits for a vet's signature (web `ToSignBanner`). */
@Composable
fun SchedToSignBanner(count: Int, vetName: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Row(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.lime, 12.0), shape)
            .border(2.dp, Theme.lime, shape)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).background(Theme.surface, CircleShape), contentAlignment = Alignment.Center) {
            Glyph("pen-line", 20.dp, tint = Theme.inkStrong)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(LF("The visit is finished and %@ still to sign", count.toString()), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            Text(vetName?.let { LF("Waiting for %@ to sign it.", it) } ?: L("Waiting for a vet to sign it."), style = Theme.text(14), color = Theme.inkSoft)
        }
    }
}

// MARK: - The closing card

data class SchedClosingCheck(
    val id: String,
    val label: String,
    val value: String,
    val state: State,
) {
    enum class State { ok, pending, warn, missing }
}

/** Under the note: what the visit still needs, then the button that closes it (web `VisitClosing`). */
@Composable
fun SchedVisitClosing(
    title: String,
    checks: List<SchedClosingCheck>,
    actionTitle: String,
    actionImage: String,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    SectionCard(title = title, icon = "badge-check", accent = Theme.blue, modifier = modifier) {
        checks.forEachIndexed { index, check ->
            if (index > 0) RowDivider()
            val icon = when (check.state) {
                SchedClosingCheck.State.ok -> "check"
                SchedClosingCheck.State.missing, SchedClosingCheck.State.warn -> "triangle-alert"
                SchedClosingCheck.State.pending -> "clock-3"
            }
            val ink = when (check.state) {
                SchedClosingCheck.State.ok -> ProviderState.done.color
                SchedClosingCheck.State.missing -> Theme.destructive
                SchedClosingCheck.State.warn -> Theme.inkStrong
                SchedClosingCheck.State.pending -> Theme.inkSoft
            }
            val disc = when (check.state) {
                SchedClosingCheck.State.ok -> Theme.wash(ProviderState.done.color, 18.0)
                SchedClosingCheck.State.missing -> Theme.wash(Theme.destructive, 12.0)
                SchedClosingCheck.State.warn -> Theme.wash(Theme.mango, 24.0)
                SchedClosingCheck.State.pending -> Theme.surfaceMuted
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) { Glyph(icon, 14.dp, tint = ink) }
                Text(check.label, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.width(96.dp))
                Text(
                    check.value,
                    style = Theme.text(14, if (check.state == SchedClosingCheck.State.ok || check.state == SchedClosingCheck.State.pending) FontWeight.Normal else FontWeight.Bold),
                    color = when (check.state) {
                        SchedClosingCheck.State.missing -> Theme.destructive
                        SchedClosingCheck.State.warn -> Theme.inkStrong
                        else -> Theme.inkSoft
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        RowDivider()
        PMButton(
            actionTitle, systemImage = actionImage, size = ButtonSize.modal, fullWidth = true,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            action = action,
        )
    }
}

// MARK: - The note of the visit

private enum class NoteSaveState { idle, dirty, saving, saved, conflict, error }

/**
 * Written during the visit: what was found (the chart), a summary for the family, a
 * private note for the staff. It saves itself as a draft; signing makes it part of
 * the chart the family sees. Two people editing at once can't overwrite each other.
 */
@Composable
fun SchedVisitNote(
    appointment: SchedAppointment,
    records: List<SchedRecord>,
    canWrite: Boolean,
    canSign: Boolean,
    /** Whoever is here signs the note by finishing the visit. */
    signsOnFinish: Boolean,
    noteRequired: Boolean,
    onChanged: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val open = rememberOpenURL()
    var details by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var internalNote by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(NoteSaveState.idle) }
    var recordId by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var savedAt by remember { mutableStateOf<Instant?>(null) }
    var edits by remember { mutableIntStateOf(0) }
    var signing by remember { mutableStateOf(false) }
    var loadedFrom by remember { mutableStateOf<String?>(null) }
    // One save at a time: a save still on its way is never overtaken by the next one.
    val saves = remember { Mutex() }

    val noteType = appointment.serviceCategory.let { category ->
        if (category != null && !listOf("vet", "emergency", "pharmacy").contains(category)) "note" else "vet_visit"
    }
    val note = records.filter { it.recordType == noteType }.sortedBy { it.createdAt ?: "" }.firstOrNull()
    val signed = note?.isSigned == true
    val editable = canWrite && (!signed || canSign)
    val linked = appointment.clientHouseholdId != null
    val empty = details.nonEmpty == null && summary.nonEmpty == null && internalNote.nonEmpty == null

    /** Someone else's save (or a refresh) replaces the text only when nothing here is unsaved. */
    fun adopt() {
        val current = note ?: return
        if (state == NoteSaveState.dirty || state == NoteSaveState.saving) return
        if (current.id == recordId && (current.revision ?: 0) <= revision && loadedFrom != null) return
        recordId = current.id
        revision = current.revision ?: 0
        details = current.details ?: ""
        summary = current.summary ?: ""
        internalNote = current.internalNote ?: ""
        loadedFrom = current.id
    }

    suspend fun save() = saves.withLock {
        state = NoteSaveState.saving
        try {
            val id = recordId
            if (id != null) {
                val saved: SchedRecord = SchedAPI.send(
                    "PATCH", "/provider-records/$id",
                    mapOf("details" to details.nonEmpty, "summary" to summary.nonEmpty, "internalNote" to internalNote.nonEmpty, "expectedRevision" to revision),
                )
                revision = saved.revision ?: (revision + 1)
            } else {
                val created: SchedRecord = SchedAPI.send(
                    "POST", "/provider-records",
                    mapOf(
                        "appointmentId" to appointment.id,
                        "providerClientId" to appointment.providerClientId,
                        "providerClientPetId" to appointment.providerClientPetId,
                        "recordType" to noteType,
                        "title" to appointment.serviceLabel,
                        "details" to details.nonEmpty,
                        "summary" to summary.nonEmpty,
                        "internalNote" to internalNote.nonEmpty,
                        "eventAt" to appointment.startsAt,
                        "draft" to true,
                    ),
                )
                recordId = created.id
                revision = created.revision ?: 0
                loadedFrom = created.id
            }
            if (state == NoteSaveState.saving) state = NoteSaveState.saved
            savedAt = Instant.now()
            onChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if ((e as? SchedFailure)?.code == "record_changed") {
                state = NoteSaveState.conflict
                return@withLock
            }
            state = NoteSaveState.error
            schedToast(e)
        }
    }

    suspend fun signNow() {
        if (state == NoteSaveState.dirty || (recordId == null && !empty)) save()
        val id = recordId
        if (id == null) {
            Toast.error(L("Write the note before signing it."))
            return
        }
        signing = true
        try {
            API.send("POST", "/provider-records/$id/sign", emptyMap())
            Toast.show(if (linked) L("Signed. The family can see it now.") else L("Signed. It is part of the chart now."))
            announceDataChanged()
            onChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(e)
        } finally {
            signing = false
        }
    }

    // A field that marks the note unsaved and asks for a save in a moment.
    fun field(get: () -> String, set: (String) -> Unit) = stateOf(get) { value ->
        if (value != get()) {
            set(value)
            state = NoteSaveState.dirty
            edits += 1
        }
    }

    LaunchedEffect(note?.revision) { adopt() }
    LaunchedEffect(edits) {
        if (state != NoteSaveState.dirty) return@LaunchedEffect
        delay(1200)
        if (state != NoteSaveState.dirty) return@LaunchedEffect
        scope.launch { save() }
    }

    val badge = when (state) {
        NoteSaveState.saving -> L("Saving…")
        NoteSaveState.dirty -> L("Unsaved")
        NoteSaveState.error -> L("Not saved")
        else -> if (signed) LF("Signed by %@ · %@", note?.signedBy?.name ?: "—", DateText.time(note?.signedAt))
            else if (note != null || state == NoteSaveState.saved) L("Draft saved") else L("Not started")
    }
    val footer = when (state) {
        NoteSaveState.saving -> L("Saving…")
        NoteSaveState.dirty -> L("Saving in a moment…")
        NoteSaveState.error -> L("Not saved. It tries again as you type.")
        else -> if (!(note != null || state == NoteSaveState.saved)) L("It saves on its own as you write: there is no save button.")
            else {
                val time = DateText.time(savedAt ?: DateText.parse(note?.updatedAt) ?: Instant.now())
                "${LF("Saved automatically %@", time)} · ${if (signsOnFinish || canSign) L("A draft until the visit is finished: finishing signs it.") else L("A draft until a vet signs it; the family sees it after.")}"
            }
    }

    SectionCard(
        title = L("Visit note"), icon = "notebook-pen", accent = Theme.blue, modifier = modifier,
        trailing = { StatusChip(text = badge, state = if (signed) ProviderState.done else if (state == NoteSaveState.error) ProviderState.problem else ProviderState.closed) },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canSign && !signed && canWrite) {
                PMButton(L("Sign"), systemImage = "badge-check", size = ButtonSize.compact, isWorking = signing, enabled = !empty) {
                    scope.launch { signNow() }
                }
            }
            if (state == NoteSaveState.conflict) {
                NoticeCard(
                    tone = NoticeCard.Tone.warning,
                    title = L("Someone else saved this note. Reload to see their version — your last changes here were not saved."),
                    actionTitle = L("Reload"),
                ) {
                    scope.launch {
                        recordId = null
                        loadedFrom = null
                        state = NoteSaveState.idle
                        onChanged()
                    }
                }
            }
            if (signed && editable) {
                Text(L("Signed — changes you make reach the family and are marked as a correction."), style = Theme.captionFont, color = Theme.inkSoft)
            }
            Disabled(!editable) {
                PMTextField(L("What you found and did"), text = field({ details }, { details = it }), hint = L("History, exam, diagnosis, treatment. Part of the chart."), multiline = true)
            }
            Disabled(!editable) {
                PMTextField(
                    if (linked) L("For the family") else L("Summary to share"),
                    text = field({ summary }, { summary = it }),
                    hint = if (linked) L("A short summary they will read in their app.") else LF("%@ isn’t on PawMiaw — send this by WhatsApp once the note is signed.", NameText.firstName(appointment.clientName)),
                    multiline = true,
                )
            }
            val shared = summary.nonEmpty
            if (!linked && signed && shared != null) {
                val url = schedWhatsAppURL(
                    appointment.clientPhone,
                    LF("Hi %@, here is the summary of %@’s visit:", NameText.firstName(appointment.clientName), appointment.petName) + "\n\n" + shared,
                )
                if (url != null) {
                    PMButton("WhatsApp", systemImage = "message-circle", variant = ButtonVariant.outline, size = ButtonSize.compact) { open(url.toString()) }
                }
            }
            Disabled(!editable) {
                PMTextField(L("Staff only"), text = field({ internalNote }, { internalNote = it }), hint = L("Never shown to the family."), multiline = true)
            }
            if (canWrite && !signed) {
                val tint = if (state == NoteSaveState.error) Theme.destructive else Theme.inkSoft
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(
                        when (state) {
                            NoteSaveState.saving -> "refresh-cw"
                            NoteSaveState.error -> "triangle-alert"
                            else -> "cloud"
                        },
                        14.dp, tint = tint,
                    )
                    Text(footer, style = Theme.text(12, FontWeight.SemiBold), color = tint)
                }
            }
        }
    }
}

/** "Also recorded in this visit": each opens its record. */
@Composable
fun SchedVisitRecords(
    records: List<SchedRecord>,
    canSign: Boolean,
    onChanged: suspend () -> Unit,
    /** "Add a record": a vaccine, a diagnosis, a lab result… into this visit (Records' sheet). */
    onAdd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var signing by remember { mutableStateOf<String?>(null) }

    fun sign(record: SchedRecord) {
        signing = record.id
        scope.launch {
            try {
                API.send("POST", "/provider-records/${record.id}/sign", emptyMap())
                Toast.show(L("Signed."))
                announceDataChanged()
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                signing = null
            }
        }
    }

    SectionCard(
        title = L("Also recorded in this visit"), icon = "files", accent = Theme.blue, modifier = modifier,
        trailing = { if (onAdd != null) PMButton(L("Add a record"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.header, action = onAdd) },
    ) {
        if (records.isEmpty()) {
            Text(L("Nothing else yet."), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(16.dp))
        }
        records.forEachIndexed { index, record ->
            if (index > 0) RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).plainButton { nav.push(ProviderRoute.record(record.id)) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(record.title ?: L("Record"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(if (record.isDraft) L("Draft") else L("Signed"), DateText.dayTime(record.eventAt ?: record.createdAt)).joinToString(" · "),
                            style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                }
                if (record.isDraft && canSign) {
                    PMButton(L("Sign"), variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = signing == record.id) { sign(record) }
                }
            }
        }
    }
}

// MARK: - The bill

/**
 * "Cuenta de la visita": where it stands, its lines and what has been paid; the bill
 * itself (charging, prices, payments) opens in Cuentas.
 */
@Composable
fun SchedVisitBillCard(
    appointment: SchedAppointment,
    invoice: SchedInvoice?,
    stage: SchedBillStage,
    collectAmount: Int,
    canCollect: Boolean,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    SectionCard(
        title = L("The visit’s bill"), icon = "receipt", accent = Theme.blue, modifier = modifier,
        trailing = { StatusChip(text = stage.label, state = stage.state) },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (invoice != null) {
                val currency = invoice.currency
                invoice.invoiceNumber?.let { Text(it, style = Theme.captionFont, color = Theme.inkSoft) }
                val lines = invoice.lineItems ?: emptyList()
                if (lines.isEmpty()) {
                    Text(L("No charges on the bill yet."), style = Theme.smallFont, color = Theme.inkSoft)
                }
                lines.forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(line.label ?: "—", style = Theme.text(15, FontWeight.SemiBold), color = Theme.inkStrong)
                            val quantity = line.quantity
                            if (quantity != null && quantity != 1.0) {
                                Text("${plainNumber(quantity)} × ${Money.short(line.unitAmount)}", style = Theme.captionFont, color = Theme.inkSoft)
                            }
                        }
                        if (line.needsPrice) {
                            StatusChip(text = L("Needs a price"), state = ProviderState.waiting, modifier = Modifier.alignByBaseline())
                        } else {
                            Text(Money.format(line.lineTotalAmount, currency), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.alignByBaseline())
                        }
                    }
                }
                StitchedDivider()
                if ((invoice.discountAmount ?: 0) > 0) FactRow(label = L("Discount"), value = "−" + Money.format(invoice.discountAmount, currency))
                if ((invoice.taxAmount ?: 0) > 0) FactRow(label = L("Tax"), value = Money.format(invoice.taxAmount, currency))
                FactRow(label = L("Total"), value = Money.format(invoice.totalAmount, currency))
                if ((invoice.amountPaid ?: 0) > 0) FactRow(label = L("Paid"), value = Money.format(invoice.amountPaid, currency))
                if ((invoice.forgivenAmount ?: 0) > 0) FactRow(label = L("Forgiven@amount"), value = "−" + Money.format(invoice.forgivenAmount, currency))
                if (invoice.status != "draft") FactRow(label = L("Balance"), value = Money.format(invoice.balanceDueAmount, currency))
                if (stage == SchedBillStage.forgiven) {
                    // "Perdonar saldo" (docs/family-money.md §1): what was left will not be collected.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            invoice.forgivenAmount?.takeIf { it > 0 }?.let { LF("Balance forgiven: %@", Money.format(it, currency)) } ?: L("Balance forgiven"),
                            style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                        )
                        Text(
                            listOfNotNull(invoice.forgivenBy?.name.nonEmpty, invoice.forgivenReason.nonEmpty).joinToString(" · ").nonEmpty ?: L("Nothing more to charge."),
                            style = Theme.text(14), color = Theme.inkSoft,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canCollect) {
                        PMButton(LF("Collect %@", Money.format(collectAmount, currency)), systemImage = "hand-coins", size = ButtonSize.compact) {
                            nav.push(ProviderRoute.invoice(invoice.id))
                        }
                    }
                    PMButton(L("Open the bill"), systemImage = "receipt-text", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                        nav.push(ProviderRoute.invoice(invoice.id))
                    }
                }
            } else {
                Text(
                    when (stage) {
                        SchedBillStage.notStarted -> L("The bill opens when the visit starts.")
                        SchedBillStage.noCharge -> L("Finished with no charge.")
                        else -> L("No bill yet")
                    },
                    style = Theme.text(14), color = Theme.inkSoft,
                )
            }
        }
    }
}

/** Swift's `Double.formatted()`: the app's locale, no trailing zeros. */
private fun plainNumber(value: Double): String =
    NumberFormat.getNumberInstance(LocalizationManager.locale).apply { maximumFractionDigits = 3 }.format(value)

// MARK: - Booked together

/**
 * The family's pets booked together, or the session it has a place in (web `VisitGroupCard`):
 * a card with a blue eyebrow; a session opens whole, each of the family's pets opens its visit.
 */
@Composable
fun SchedVisitGroupCard(appointment: SchedAppointment, modifier: Modifier = Modifier) {
    val group = appointment.group ?: return
    val others = (group.pets ?: emptyList()).filter { it.appointmentId != appointment.id }
    if (!(group.isSession || others.isNotEmpty())) return
    val nav = LocalNavigator.current
    // A session opens whole (iOS pushes SvcSessionScreen): a page of its own on the stack.
    var session by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        val id = session ?: return@LaunchedEffect
        session = null
        nav.push(ProviderRoute.page("session:$id") { SvcSessionScreen(sessionId = id) })
    }
    val card = RoundedCornerShape(Theme.cardRadius)
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .cardShadow(card)
            .background(Theme.surface, card)
            .border(1.dp, Theme.borderSoft, card)
            .padding(16.dp),
    ) {
        Text(
            (if (group.isSession) LF("A place in %@", group.serviceLabel ?: L("Group session")) else L("Booked with")).uppercase(),
            style = Theme.text(12, FontWeight.Bold).tracking(1.68), color = Theme.blue,
        )
        if (group.isSession) {
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.aqua, 14.0), box)
                    .plainButton { session = group.id }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PetStack(pets = group.stayingPets.map { Triple(it.name ?: "", it.species, it.photoUrl) }, size = 32.dp, max = 4)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(group.placesLabel, style = Theme.heading(18).monospacedDigit(), color = Theme.inkStrong)
                    val capacity = group.capacity
                    if (capacity != null) {
                        Text(
                            LP(maxOf(0, capacity - (group.size ?: group.stayingPets.size)), "1 place left", "%d places left"),
                            style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Open the session"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                    Glyph("chevron-right", 16.dp, tint = Theme.blue)
                }
            }
        } else {
            Text(L("Moves, arrives, starts and finishes with them; one bill for all."), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEach { pet ->
                    val gone = SchedStatus.gone.contains(pet.status ?: "")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (gone) 0.6f else 1f)
                            .border(1.dp, Theme.borderSoft, box)
                            .plainButton { nav.push(ProviderRoute.appointment(pet.appointmentId)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PetAvatar(name = pet.name, species = pet.species, photoURL = pet.photoUrl, size = 32.dp)
                        Text(
                            pet.name ?: "", style = Theme.text(14, FontWeight.Bold).copy(textDecoration = if (gone) TextDecoration.LineThrough else null),
                            color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (gone) {
                            Text(if (pet.status == "no_show") L("Didn’t come") else L("Cancelled"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                        }
                        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                    }
                }
            }
        }
    }
}

// MARK: - A groom's stage

@Serializable
private data class SchedGroomResult(
    val groomReadyNotifiedAt: String? = null,
    val groomReadyChannel: String? = null,
    val link: String? = null,
)

@Serializable
data class SchedGroomSettings(val simpleStages: Boolean? = null)

/** `SchedGroomStageBar.label(_:simple:)`. */
object SchedGroomStageBar {
    /** A stage's word (web `groomStageLabel`). */
    fun label(stage: String, simple: Boolean): String = when (stage) {
        "waiting" -> L("Waiting")
        "bath" -> if (simple) L("In progress") else L("Bath")
        "styling" -> L("Cut & dry")
        "ready" -> L("Ready for pickup")
        else -> stage
    }
}

/**
 * A groom that is here: where it is (Esperando · En baño · Corte y secado · Listo para recoger),
 * moved in one tap, and telling the family once it is ready. Handing the pet over is finishing.
 */
@Composable
fun SchedGroomStageBar(
    appointment: SchedAppointment,
    simple: Boolean,
    modifier: Modifier = Modifier,
    onGate: (SchedGatePrompt) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val openURL = rememberOpenURL()
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<Pair<String, String?>?>(null) }

    val stage = appointment.groomStage ?: "waiting"
    val current = if (simple && stage == "styling") "bath" else stage
    val stages = if (simple) listOf("waiting", "bath", "ready") else listOf("waiting", "bath", "styling", "ready")
    fun label(stage: String) = SchedGroomStageBar.label(stage, simple)

    fun move(next: String) {
        if (next == current) return
        val id = appointment.id
        val pet = appointment.petName
        val nextLabel = label(next)
        suspend fun set() {
            SchedAPI.send<SchedGroomResult>("PATCH", "/provider-appointments/$id/groom-stage", mapOf("stage" to next))
            announceDataChanged()
        }
        busy = true
        scope.launch {
            try {
                set()
                Toast.show("$pet · $nextLabel")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A stage picked while the pet waits starts the visit: a missing consent asks first.
                val prompt = SchedGatePrompt.from(
                    e, visit = appointment, verb = "start",
                    attempt = { extra ->
                        SchedVisitOps.start(id, extra)
                        if (next != "bath") set()
                    },
                    onDone = { Toast.show("$pet · $nextLabel") },
                )
                if (prompt != null) onGate(prompt) else schedToast(e)
            } finally {
                busy = false
            }
        }
    }

    fun notify(channel: String) {
        busy = true
        val id = appointment.id
        val locale = LocalizationManager.currentLanguage.rawValue
        scope.launch {
            try {
                val result: SchedGroomResult = SchedAPI.send("POST", "/provider-appointments/$id/groom-ready", mapOf("channel" to channel, "locale" to locale))
                result.groomReadyNotifiedAt?.let { sent = it to result.groomReadyChannel }
                val link = result.link
                if (channel == "whatsapp" && link != null) {
                    openURL(link)
                    Toast.show(L("WhatsApp is open with the message."))
                } else {
                    Toast.show(L("The family was told in PawMiaw."))
                }
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                schedToast(e)
            } finally {
                busy = false
            }
        }
    }

    // The web's stage switch (a raised white segment on the muted track), then, once ready,
    // "Avisar que está listo" by WhatsApp or in PawMiaw with when it was sent.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Segmented(
                options = stages.map { Segmented.Option(it, label(it)) },
                selection = stateOf({ current }) { next ->
                    // A started groom doesn't go back to waiting: the bath is its start.
                    if (!busy && !(next == "waiting" && appointment.status == "in_progress")) move(next)
                },
                look = Segmented.Look.raised,
                label = L("Grooming stage"),
                modifier = Modifier.alpha(if (busy) 0.6f else 1f),
            )
        }
        if (stage == "ready") {
            FlowLayout(spacing = 8.dp) {
                if (appointment.clientPhone != null || appointment.clientHouseholdId != null) {
                    Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                        Text(L("Tell them it’s ready"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    }
                }
                if (appointment.clientPhone != null) {
                    PMButton("WhatsApp", systemImage = "message-circle", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busy) { notify("whatsapp") }
                }
                if (appointment.clientHouseholdId != null) {
                    PMButton("PawMiaw", systemImage = "smartphone", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busy) { notify("app") }
                }
                val at = appointment.groomReadyNotifiedAtValue ?: sent?.first
                if (at != null) {
                    // Already told: so nobody messages the family twice.
                    val channel = sent?.second ?: appointment.groomReadyChannel
                    Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                        Chip(
                            text = if (channel == "whatsapp") LF("WhatsApp opened %@", DateText.time(at)) else LF("Notified %@ · %@", DateText.time(at), "PawMiaw"),
                            tone = Chip.Tone.good, icon = "check-check",
                        )
                    }
                }
            }
        }
    }
}
