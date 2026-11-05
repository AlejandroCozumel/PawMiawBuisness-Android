package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

//
//  StaysHomeStaySheets.kt (iOS StaysHomeStaySheets.swift)
//
//  A home stay's own sheets (web home-stay-action-sheets.tsx, home-stay-sheet.tsx
//  AccessCard; docs/homestays.md): give the stay to another sitter, change its
//  dates and plan (the price or a catalog service, the house's rules and tasks,
//  the address and notes), record the keys, add a visit, and the access card —
//  where it happens, the arrival notes, the access details opened on purpose
//  (an audited reveal, hidden again after five minutes) and whom to call.
//

// MARK: - The team

object StaysHomeTeam {
    /** The business's team (to choose a sitter or who has the keys). */
    suspend fun members(): List<StaysMember> {
        val clinic = Workspace.shared.current?.clinicId ?: return emptyList()
        return runCatching { API.get<List<StaysMember>>("/provider-workspaces/$clinic/members") }.getOrNull() ?: emptyList()
    }

    /**
     * "Double booking": the sitter already has visits then (web `HomeConflictNotice`: the red
     * notice with its "Book it anyway — I know" box inside). `form`: where a check on "conflict"
     * scrolls to (Swift `.id("conflict")`).
     */
    @Composable
    fun conflictNotice(confirmed: MutableState<Boolean>, form: FormState? = null, modifier: Modifier = Modifier) {
        NoticeCard(
            tone = NoticeCard.Tone.problem, title = L("Double booking"), icon = "triangle-alert", look = NoticeCard.Look.inline,
            modifier = modifier.formScrollTarget("conflict", form),
            content = {
                Text(L("The sitter already has visits then, or can’t get there in time."), style = Theme.text(14), color = Theme.inkStrong)
                StaysTickRow(
                    title = L("Book it anyway — I know"), isOn = confirmed,
                    modifier = Modifier.background(Theme.surface, RoundedCornerShape(Theme.boxRadius)),
                )
            },
        )
    }
}

@Serializable
private data class StaysMoved(val moved: Int? = null)

// MARK: - Give the stay to another sitter

@Composable
fun StaysHomeReassignSheet(stay: StaysStay, home: StaysHomeDetail, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var members by remember { mutableStateOf<List<StaysMember>>(emptyList()) }
    var memberId by remember { mutableStateOf("") }
    val reason = remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    val overlapOk = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val sitters = members.filter { it.canCare && it.id != home.sitter?.memberId }

    fun submit() {
        if (!form.check(listOf("member" to (if (memberId.isEmpty()) L("Choose who takes over.") else null)))) return
        if (conflict && !overlapOk.value) {
            Toast.error(L("The sitter is busy then. Check the warning and book it anyway if you meant to."))
            return
        }
        working = true
        scope.launch {
            val body = mutableMapOf<String, Any?>("memberId" to memberId, "reason" to reason.value.nonEmpty)
            if (overlapOk.value) body["allowDoubleBooking"] = true
            try {
                val result = API.post<StaysMoved>("/provider-homestays/${stay.id}/reassign", body = body)
                val moved = result.moved ?: 0
                Toast.show(
                    if (stay.familyOnApp) {
                        if (moved == 1) L("1 visit passed on. The family and the new sitter have been told.") else LF("%d visits passed on. The family and the new sitter have been told.", moved)
                    } else {
                        if (moved == 1) L("1 visit passed on. The new sitter has been told.") else LF("%d visits passed on. The new sitter has been told.", moved)
                    },
                )
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                when {
                    e.code == "visit_conflict" -> {
                        conflict = true
                        overlapOk.value = false
                    }
                    (e.code ?: "") in listOf("same_sitter", "invalid_member", "member_cannot_care", "member_not_at_location") ->
                        form.check(listOf("member" to StaysWords.homeError(e, fallback = "Could not change the sitter.")))
                    else -> Toast.error(StaysWords.homeError(e, fallback = "Could not change the sitter."))
                }
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not change the sitter."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Give the stay to another sitter"),
        subtitle = home.sitter?.name?.let { LF("Now: %@", it) } ?: L("Nobody assigned yet"),
        systemImage = "user-round-cog",
        accent = ModalAccent.aqua,
        isDirty = memberId.isNotEmpty() || reason.value.isNotEmpty(),
        form = form,
        primaryTitle = if (conflict) L("Give it anyway") else L("Give the stay"),
        isWorking = working,
        primaryIcon = "user-round-cog",
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Text(L("Every visit still to make passes to them. A visit under way stays with whoever is there."), style = Theme.text(14), color = Theme.inkSoft)
        MenuField(
            label = L("New sitter"),
            options = listOf("" to L("Choose someone")) + sitters.map { it.id to it.label },
            selection = stateOf({ memberId }, { value ->
                memberId = value
                conflict = false
                overlapOk.value = false
            }),
            key = "member", form = form,
        )
        PMTextField(L("Why (the family reads it)"), text = reason, prompt = L("e.g. Laura is sick"))
        if (conflict) StaysHomeTeam.conflictNotice(confirmed = overlapOk)
    }
    LaunchedEffect(Unit) { if (members.isEmpty()) members = StaysHomeTeam.members() }
}

// MARK: - Change the home stay

@Composable
fun StaysHomeEditSheet(stay: StaysStay, home: StaysHomeDetail, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val plan = remember { mutableStateOf(StaysHomePlan()) }
    var original by remember { mutableStateOf(StaysHomePlan()) }
    var addressId by remember { mutableStateOf<String?>(null) }
    val accessNotes = remember { mutableStateOf("") }
    val ownerInstructions = remember { mutableStateOf("") }
    val internalNotes = remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    val overlapOk = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val hasStarted = stay.status == "checked_in"

    fun submit() {
        val rules = plan.value.problems.filter { it.first != "meetGreet" }
        if (!form.check(rules)) return
        if (conflict && !overlapOk.value) {
            Toast.error(L("The sitter is busy then. Check the warning and book it anyway if you meant to."))
            return
        }
        working = true
        scope.launch {
            val body = plan.value.body(sitterId = null).toMutableMap()
            body.remove("meetGreet")
            body["depositCents"] = plan.value.deposit
            val chosen = addressId
            if (!hasStarted && chosen != null && chosen != home.addressId) body["addressId"] = chosen
            body["accessNotes"] = accessNotes.value.nonEmpty
            body["ownerInstructions"] = ownerInstructions.value.nonEmpty
            body["internalNotes"] = internalNotes.value.nonEmpty
            if (overlapOk.value) body["allowDoubleBooking"] = true
            try {
                val data = API.data("PATCH", "/provider-homestays/${stay.id}", emptyMap(), API.jsonBody(body))
                val saved = runCatching { API.decode<StaysHomeEnvelope>(data) }.getOrNull()
                Toast.show(
                    if (stay.status == "requested" && home.quoteSentAt != null && saved?.home != null && saved.home.quoteSentAt == null) {
                        L("Saved. The quote was withdrawn — send it again.")
                    } else {
                        L("Home stay updated. The visits were laid out again.")
                    },
                )
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                val code = e.code ?: ""
                when {
                    code == "visit_conflict" -> {
                        conflict = true
                        overlapOk.value = false
                    }
                    code in listOf("invalid_dates", "too_long", "overnight_needs_a_night", "already_started", "visits_outside") ->
                        form.check(listOf("dates" to StaysWords.homeError(e, fallback = "Could not change this home stay.")))
                    code in listOf("times_required", "too_many_times", "invalid_time", "invalid_duration", "invalid_overnight", "visits_overlap", "no_visits") ->
                        form.check(listOf("plan" to StaysWords.homeError(e, fallback = "Could not change this home stay.")))
                    code in listOf("address_required", "address_not_found", "address_archived") ->
                        form.check(listOf("address" to StaysWords.homeError(e, fallback = "Could not change this home stay.")))
                    else -> Toast.error(StaysWords.homeError(e, fallback = "Could not change this home stay."))
                }
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not change this home stay."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Change the home stay"),
        subtitle = L("Extend or shorten it, change the plan, the price or the house. Visits already made stay."),
        systemImage = "calendar-plus",
        accent = ModalAccent.aqua,
        isDirty = started && plan.value != original,
        form = form,
        primaryTitle = if (conflict) L("Save anyway") else L("Save"),
        isWorking = working,
        primaryIcon = "save",
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        if (stay.status == "requested" && home.quoteSentAt != null) {
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("The quote was sent. Changing the price, dates or plan withdraws it; send it again after saving."))
        }
        StaysHomePlanForm(plan = plan, form = form, showMeetGreet = false, startedDay = if (hasStarted) stay.checkInDay else null, editing = true)
        ModalSection(title = L("At their home"), subtitle = if (hasStarted) L("The stay has started at this address.") else null) {
            val clientId = stay.providerClientId
            if (!hasStarted && clientId != null) {
                // The one address picker of every visit at a client's home (with its arrival notes).
                SchedAddressPicker(
                    clientId = clientId,
                    addressId = stateOf({ addressId }, { value ->
                        addressId = value
                        conflict = false
                        form.clear("address")
                    }),
                    accessNotes = accessNotes,
                    error = form.error("address"),
                    modifier = Modifier.formScrollTarget("address", form),
                )
            } else {
                PMTextField(L("Arrival & access notes"), text = accessNotes, multiline = true)
            }
        }
        ModalSection(title = L("Notes")) {
            PMTextField(L("What the family asked for"), text = ownerInstructions, multiline = true)
            PMTextField(L("Note for the team"), text = internalNotes, multiline = true)
        }
        if (conflict) StaysHomeTeam.conflictNotice(confirmed = overlapOk)
    }

    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        plan.value = StaysHomePlan(stay = stay, home = home)
        original = plan.value
        addressId = home.addressId
        accessNotes.value = home.accessNotes ?: ""
        ownerInstructions.value = stay.ownerInstructions ?: ""
        internalNotes.value = stay.internalNotes ?: ""
    }
    StaysOnChanged(plan.value) {
        conflict = false
        overlapOk.value = false
    }
}

// MARK: - The keys

@Composable
fun StaysHomeKeysSheet(stay: StaysStay, home: StaysHomeDetail, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var members by remember { mutableStateOf<List<StaysMember>>(emptyList()) }
    var action by remember { mutableStateOf("") }
    val holder = remember { mutableStateOf("") }
    val label = remember { mutableStateOf("") }
    val note = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val out = home.keys?.state == "with_sitter" || home.keys?.state == "lockbox"
    val actions = if (out) listOf("transferred", "lockbox", "returned") else listOf("received")
    val needsHolder = action == "received" || action == "transferred"

    fun title(action: String): String = when (action) {
        "received" -> L("Received")
        "transferred" -> L("Passed on")
        "lockbox" -> L("In the lockbox")
        else -> L("Returned")
    }

    fun submit() {
        if (!form.check(listOf("holder" to (if (needsHolder && holder.value.isEmpty()) L("Say who has the keys.") else null)))) return
        working = true
        scope.launch {
            val body = mutableMapOf<String, Any?>("action" to action, "label" to label.value.nonEmpty, "note" to note.value.nonEmpty)
            if (needsHolder) body["holderMemberId"] = holder.value
            try {
                API.send("POST", "/provider-homestays/${stay.id}/keys", body = body)
                Toast.show(L("Keys recorded."))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                if ((e.code ?: "") in listOf("holder_required", "same_holder", "invalid_member", "keys_not_out")) {
                    form.check(listOf("holder" to StaysWords.homeError(e, fallback = "Could not record the keys.")))
                } else {
                    Toast.error(StaysWords.homeError(e, fallback = "Could not record the keys."))
                }
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not record the keys."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("The keys"),
        subtitle = StaysWords.keysState(home.keys),
        systemImage = "key-round",
        accent = ModalAccent.aqua,
        isDirty = note.value.isNotEmpty() || label.value != (home.keys?.label ?: ""),
        form = form,
        primaryTitle = L("Record"),
        isWorking = working,
        primaryIcon = "key-round",
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ChoiceCards(
            options = actions.map { Triple<String, String, String?>(it, title(it), null) },
            selection = stateOf({ action }, { value ->
                action = value
                if (value == "transferred") holder.value = ""
                form.clear("holder")
            }),
        )
        if (needsHolder) {
            MenuField(
                label = if (action == "received") L("Who has them") else L("Passed on to"),
                options = listOf("" to L("Choose someone")) +
                    members.filter { action != "transferred" || it.id != home.keys?.holder?.memberId }.map { it.id to it.label },
                selection = holder, key = "holder", form = form,
            )
        }
        PMTextField(L("Which keys"), text = label, prompt = L("e.g. Front door + gate, blue tag"))
        PMTextField(L("Note (optional)"), text = note)
    }

    LaunchedEffect(Unit) {
        if (members.isEmpty()) members = StaysHomeTeam.members().filter { it.status != "suspended" }
        if (started) return@LaunchedEffect
        started = true
        action = if (out) "returned" else "received"
        holder.value = if (out) "" else (home.sitter?.memberId ?: members.firstOrNull { it.isCurrentMember == true }?.id ?: "")
        label.value = home.keys?.label ?: ""
    }
}

// MARK: - Add a visit

@Composable
fun StaysHomeAddVisitSheet(stay: StaysStay, home: StaysHomeDetail, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var members by remember { mutableStateOf<List<StaysMember>>(emptyList()) }
    var kind by remember { mutableStateOf("visit") }
    var date by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("12:00") }
    var minutes by remember { mutableStateOf(45) }
    var memberId by remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    val overlapOk = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    fun reset() {
        conflict = false
        overlapOk.value = false
        form.clear("date")
    }

    fun submit() {
        if (conflict && !overlapOk.value) {
            Toast.error(L("The sitter is busy then. Check the warning and book it anyway if you meant to."))
            return
        }
        working = true
        scope.launch {
            val body = mutableMapOf<String, Any?>("kind" to kind, "date" to date, "startTime" to startTime, "memberId" to memberId.nonEmpty)
            if (kind == "overnight") body["endTime"] = home.plan?.overnightEnd ?: "08:00" else body["durationMinutes"] = minutes
            if (overlapOk.value) body["allowDoubleBooking"] = true
            try {
                API.send("POST", "/provider-homestays/${stay.id}/visits", body = body)
                Toast.show(LF("%@ added.", StaysWords.homeVisitKind(kind)))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                if (e.code == "visit_conflict") {
                    conflict = true
                    overlapOk.value = false
                } else {
                    form.check(listOf("date" to StaysWords.homeError(e, fallback = "Could not add this visit.")))
                }
            } catch (e: Exception) {
                form.check(listOf("date" to StaysWords.homeError(e, fallback = "Could not add this visit.")))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Add a visit"),
        subtitle = stay.petNames,
        systemImage = "calendar-plus",
        accent = ModalAccent.aqua,
        form = form,
        primaryTitle = if (conflict) L("Add anyway") else L("Add"),
        isWorking = working,
        primaryIcon = "plus",
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ChoiceCards(
            options = listOf("visit", "overnight", "meet_greet").map { Triple<String, String, String?>(it, StaysWords.homeVisitKind(it), null) },
            selection = stateOf({ kind }, { value ->
                kind = value
                if (value == "overnight") startTime = home.plan?.overnightStart ?: "20:00"
                reset()
            }),
        )
        DayField(label = L("Day"), day = stateOf({ date }, { date = it; reset() }), key = "date", form = form)
        TimeField(
            label = if (kind == "overnight") L("Arrives in the evening") else L("Time"),
            value = stateOf({ startTime }, { startTime = it; reset() }), from = "05:00", to = "23:45",
        )
        if (kind == "overnight") {
            FactRow(label = L("Leaves the next morning"), value = StaysDay.clock(home.plan?.overnightEnd ?: "08:00"))
        } else {
            MenuField(
                label = L("Length"),
                options = (listOf(15, 20, 30, 45, 60, 90, 120, 180, 240) + minutes).toSortedSet().map { it to StaysHomePlanForm.minutesText(it) },
                selection = stateOf({ minutes }, { minutes = it; reset() }),
            )
        }
        MenuField(
            label = L("Sitter"),
            options = listOf("" to L("Decide later")) + members.filter { it.canCare }.map { it.id to it.label },
            selection = stateOf({ memberId }, { memberId = it; reset() }),
        )
        if (conflict) StaysHomeTeam.conflictNotice(confirmed = overlapOk)
    }

    LaunchedEffect(Unit) {
        if (date.isEmpty()) {
            val today = home.today?.dayKey ?: StaysDay.today()
            date = if (today > stay.checkInDay && today <= stay.checkOutDay) today else stay.checkInDay
            minutes = home.plan?.durationMinutes ?: 45
            memberId = home.sitter?.memberId ?: ""
        }
        if (members.isEmpty()) members = StaysHomeTeam.members()
    }
}

// MARK: - Where, and getting in

/**
 * Where the stay happens, the arrival notes, the access details (when this person may
 * open them: an audited reveal) and whom to call.
 */
@Composable
fun StaysHomeAccessCard(stay: StaysStay, home: StaysHomeDetail, modifier: Modifier = Modifier) {
    val openURL = rememberOpenURL()
    var asking by remember { mutableStateOf<Boolean?>(null) }
    var revealed by remember { mutableStateOf<SvcVaultReveal?>(null) }
    var revealedAt by remember { mutableStateOf(Instant.now()) }
    var now by remember { mutableStateOf(Instant.now()) }

    val family = home.address?.source == "family"

    @Composable
    fun Quiet(text: String) {
        Text(text, style = Theme.text(12), color = Theme.inkSoft)
    }

    @Composable
    fun RevealedView(reveal: SvcVaultReveal) {
        val left = max(1, ceil((revealedAt.plusSeconds(300).toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (field in SvcVault.fields.filter { reveal.vault[it].nonEmpty != null }) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(SvcVault.label(field), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                    SelectionContainer {
                        Text(reveal.vault[field] ?: "", style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LF("Hides by itself in %d min (at %@).", left, DateText.time(revealedAt.plusSeconds(300), zone = home.zone)),
                    style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.weight(1f),
                )
                PMButton(L("Hide"), systemImage = "eye-off", variant = ButtonVariant.outline, size = ButtonSize.compact) { revealed = null }
            }
        }
        LaunchedEffect(revealedAt) {
            // Counted on this device from when they arrived; they hide themselves after five minutes.
            while (isActive && Instant.now() < revealedAt.plusSeconds(300)) {
                delay(15_000)
                now = Instant.now()
            }
            if (isActive) revealed = null
        }
    }

    @Composable
    fun VaultState() {
        val vault = home.vault
        if (vault?.canReveal == true) {
            PMButton(L("Show access details"), systemImage = "eye", size = ButtonSize.compact) { asking = false }
            val closes = vault.window?.closesAt
            if (vault.reason == "assigned_in_window" && closes != null) {
                Text(
                    LF("Open for the stay’s sitters until %@.", DateText.dayTime(DateText.parse(closes), zone = home.zone)),
                    style = Theme.text(12), color = Theme.inkSoft,
                )
            }
            Text(
                if (family) L("Opening these is recorded and the family is told each time.") else L("Opening these is recorded."),
                style = Theme.text(12), color = Theme.inkSoft,
            )
        } else {
            when (vault?.reason) {
                "window_closed" -> {
                    val next = (if (vault.window?.open == false) vault.window else null)
                        ?: (vault.windows ?: emptyList()).firstOrNull { (DateText.parse(it.opensAt) ?: Instant.MIN) > Instant.now() }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Glyph("clock-3", 14.dp, tint = Theme.blue, modifier = Modifier.padding(top = 1.dp))
                        Text(
                            next?.opensAt?.let { LF("They open %@ — 30 minutes before the first visit.", DateText.dayTime(DateText.parse(it), zone = home.zone)) }
                                ?: L("Closed — they open 30 minutes before the first visit and close 30 minutes after the stay ends."),
                            style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong,
                        )
                    }
                    if (vault.canEmergency == true) {
                        PMButton(L("Emergency opening"), systemImage = "triangle-alert", variant = ButtonVariant.destructive, size = ButtonSize.compact) { asking = true }
                    }
                }
                "family_off" -> Quiet(L("The family hasn’t shared its access details with your business."))
                "not_assigned" -> Quiet(L("Only the stay’s sitters see them."))
                "no_vault" -> Quiet(if (family) L("The family hasn’t written access details.") else L("No access details were written for this address."))
                "not_shared" -> Quiet(L("This address is no longer shared."))
                "unavailable" -> Quiet(L("Not available right now."))
                else -> {}
            }
        }
    }

    @Composable
    fun Contact(title: String, contact: StaysContact?, empty: String) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StaysEyebrow(text = title, tint = Theme.inkSoft)
            val name = contact?.name.nonEmpty
            if (contact != null && name != null) {
                Text(name + (contact.relationship.nonEmpty?.let { " · $it" } ?: ""), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                contact.phone.nonEmpty?.let { PhoneLink(phone = it) }
            } else {
                Text(empty, style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val address = home.address
        if (address != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    address.label.nonEmpty?.let { Text(it, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong) }
                    if (address.source == "family") TagChip(text = L("From the family"), icon = "link-2", linked = true)
                }
                Text(address.formatted ?: "", style = Theme.text(14), color = Theme.inkStrong)
                address.reference.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
                address.mapsUrl.nonEmpty?.let { maps ->
                    Row(
                        Modifier.plainButton(role = androidx.compose.ui.semantics.Role.Button) { openURL(maps) },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("map-pin", 14.dp, tint = Theme.blue)
                        Text(L("Open in Maps"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
                    }
                }
            }
            if (address.shared == false) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.mango, 18.0), RoundedCornerShape(Theme.chipRadius))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Glyph("triangle-alert", 14.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 1.dp))
                    Text(L("This address is no longer shared. This is how it was when the stay was booked."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("map-pin", 16.dp, tint = Theme.blue)
                Text(L("No address on this stay"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            }
        }
        home.accessNotes.nonEmpty?.let { notes ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.mango, 12.0), RoundedCornerShape(Theme.chipRadius))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(L("Arrival & access notes").uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(10.6 * 0.1), color = Theme.inkSoft)
                Text(notes, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
        if (home.address != null) {
            // Access details
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.chipRadius))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("key-round", 16.dp, tint = Theme.blue)
                    Text(L("Access details"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                }
                val shown = revealed
                if (shown != null) {
                    RevealedView(shown)
                } else {
                    val fields = home.vault?.fields ?: emptyList()
                    if (fields.isNotEmpty()) {
                        FlowLayout(spacing = 6.dp) {
                            for (field in fields) Chip(text = SvcVault.label(field), tone = Chip.Tone.muted, icon = "lock")
                        }
                    }
                    VaultState()
                }
            }
        }
        RowDivider()
        val familyContact = home.familyEmergencyContact
        val cells = buildList<@Composable () -> Unit> {
            add { Contact(L("Emergency contact"), home.emergencyContact, empty = L("None yet")) }
            if (familyContact != null && familyContact.name.nonEmpty != null) {
                add { Contact(L("The family’s emergency contact"), familyContact, empty = "") }
            }
        }
        StaysGrid(items = cells, minimum = 220.dp, spacing = 12.dp) { it() }
    }

    asking?.let { emergency ->
        AppSheet(onDismissRequest = { asking = null }) {
            ConfirmSheet(
                title = if (emergency) L("Emergency opening") else L("Show access details?"),
                message = (
                    if (emergency) L("There is no visit now. Open them only if something is wrong, and say why: the family is told right away, with your words.")
                    else if (family) L("Opening these is recorded and the family is told each time.") else L("Opening these is recorded.")
                    ) + " " + L("They stay on screen for 5 minutes at most, then hide again."),
                confirmTitle = if (emergency) L("Open in an emergency") else L("Show them"),
                destructive = emergency,
                askReason = emergency,
                reasonRequired = emergency,
                reasonLabel = L("What happened?"),
                // Web RevealConfirmSheet: teal with the key, coral with the triangle in an emergency.
                accent = if (emergency) ModalAccent.coral else ModalAccent.aqua,
                icon = if (emergency) "triangle-alert" else "key-round",
                confirmIcon = "eye",
            ) { reason ->
                val body: Map<String, Any?> = if (emergency) mapOf("emergencyReason" to reason) else emptyMap()
                try {
                    val result = API.post<SvcVaultReveal>("/provider-homestays/${stay.id}/vault/reveal", body = body)
                    revealed = result
                    revealedAt = Instant.now()
                    now = Instant.now()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw APIError(
                        status = (e as? APIError)?.status ?: 400,
                        message = StaysWords.homeError(e, fallback = "Could not open the access details."),
                        code = null, fields = emptyMap(),
                    )
                }
            }
        }
    }
}
