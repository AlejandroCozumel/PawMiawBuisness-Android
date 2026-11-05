package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

//
//  ClientsClosingSheets.kt (iOS ClientsClosingSheets.swift)
//
//  Archiving a client or a pet, recording that a pet died, and the way back
//  (web: client-closing/closing-sheets.tsx). Delete means archive: the sheet
//  says what gets cancelled, asks why, shows whose name it stays under and
//  waits for the record's own name to be typed. A death and a whole client
//  are signed as well: the saved signature, or one drawn here.
//

/**
 * What a closing sheet closes. (Swift's `Identifiable.id`, "archive:{petId}", is only what
 * `.sheet(item:)` needs; the client case keeps its own `id` here.)
 */
sealed class ClientsClosingTarget {
    abstract val clientId: String
    abstract val name: String
    open val petId: String? get() = null

    data class archivePet(override val clientId: String, override val petId: String, override val name: String) : ClientsClosingTarget()
    data class petDeath(override val clientId: String, override val petId: String, override val name: String) : ClientsClosingTarget()
    data class archiveClient(val id: String, override val name: String, val firstName: String, val owed: String?) : ClientsClosingTarget() {
        override val clientId: String get() = id
    }

    /** What is typed to confirm ("José Luis"); for a client the whole name works too. */
    val confirmName: String get() = if (this is archiveClient) firstName else name

    val isDeath: Boolean get() = this is petDeath
    val isClient: Boolean get() = this is archiveClient
}

sealed class ClientsRestoreTarget {
    abstract val name: String

    data class pet(val id: String, override val name: String, val deceased: Boolean) : ClientsRestoreTarget()
    data class client(val id: String, override val name: String) : ClientsRestoreTarget()
}

/** A small spinner and its words (SwiftUI `ProgressView().controlSize(.small)` + a label). */
@Composable
private fun ClientsSmallSpinner(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
        Text(text, style = Theme.text(14), color = Theme.inkSoft)
    }
}

/** One line of what happens (web `Consequence`): a 16 grey icon and the sentence; quiet = grey. */
@Composable
private fun ClientsConsequence(icon: String, text: String, quiet: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Glyph(icon, 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = Theme.text(14), color = if (quiet) Theme.inkSoft else Theme.inkStrong)
    }
}

/** A visit still to come under "What happens": its day bold, then the rest grey, indented under the icon. */
@Composable
private fun ClientsCancelledVisitLine(day: String, rest: String) {
    val strong = Theme.inkStrong
    val soft = Theme.inkSoft
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append(day) }
            withStyle(SpanStyle(color = soft)) { append(rest) }
        },
        Modifier.padding(start = 26.dp),
        style = Theme.text(14),
    )
}

@Composable
fun ClientsClosingSheet(target: ClientsClosingTarget, onDone: (ClientsClosingResult) -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var preview by remember { mutableStateOf<ClientsClosingPreview?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    val note = remember { mutableStateOf("") }
    val day = remember { mutableStateOf(Instant.now()) }
    val typed = remember { mutableStateOf("") }
    var savedSignature by remember { mutableStateOf<SignatureMark?>(null) }
    val signature = remember { mutableStateOf(SignatureDraft()) }
    var saving by remember { mutableStateOf(false) }

    val signs = target.isDeath || target.isClient
    val reasons = if (target.isClient) listOf("moved", "no_longer_comes", "other_provider", "other") else listOf("rehomed", "moved", "no_longer_comes", "other")
    val blocked: List<Pair<String, String>> = (preview?.pets ?: emptyList()).flatMap { pet -> (pet.blockers ?: emptyList()).map { (pet.name ?: target.name) to it } }
    val ready = preview != null && blocked.isEmpty()

    suspend fun loadPreview() {
        val path = "/provider-clients/${target.clientId}/closing-preview"
        try {
            preview = API.get<ClientsClosingPreview>(path, mapOf("petId" to target.petId))
            previewFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            previewFailed = true
        }
        if (signs) {
            savedSignature = try {
                ProfessionalSignature.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
        }
    }

    fun refusal(error: Throwable) {
        val api = error as? APIError
        val code = api?.code
        if (api == null || code == null) {
            form.apply(error)
            return
        }
        when (code) {
            "confirm_name" -> form.check(listOf("typed" to LF("That isn’t the name. Type %@.", target.confirmName)))
            "note_required" -> form.check(listOf("note" to L("Say why, in a few words.")))
            "invalid_day" -> form.check(listOf("day" to L("Choose the day it happened: today or earlier.")))
            "already_archived" -> Toast.error(L("It was already archived. Reload the page."))
            "already_deceased" -> Toast.error(L("It was already recorded. Reload the page."))
            "saved_signature_missing" -> {
                savedSignature = null
                form.check(listOf("signature" to L("Your saved signature is gone. Draw it.")))
            }
            "signature_required", "signature_missing", "signature_blank" -> form.check(listOf("signature" to L("Your signature is missing. Sign above.")))
            "signature_invalid", "signature_too_large" -> form.check(listOf("signature" to L("The signature could not be read. Draw it again.")))
            else -> Toast.error(api.message.nonEmpty ?: (if (target.isDeath) L("It could not be saved. Try again.") else L("It could not be archived. Try again.")))
        }
    }

    fun submit() {
        val nameMatches = ClientsText.typedNameMatches(typed.value, target.confirmName) || (target.isClient && ClientsText.typedNameMatches(typed.value, target.name))
        val sign = if (signs) signature.value.captured(saved = savedSignature)?.json else null
        if (!form.check(listOf(
                "reason" to (if (!target.isDeath && reason == null) L("Choose a reason.") else null),
                "note" to (if (!target.isDeath && reason == "other" && note.value.nonEmpty == null) L("Say why, in a few words.") else null),
                "signature" to (if (signs && sign == null) L("Your signature is missing. Sign above.") else null),
                "typed" to (if (nameMatches) null else LF("That isn’t the name. Type %@.", target.confirmName)),
            ))) return
        saving = true
        scope.launch {
            try {
                val result: ClientsClosingResult = when (target) {
                    is ClientsClosingTarget.petDeath -> API.post(
                        "/provider-client-pets/${target.petId}/death",
                        mapOf("diedOn" to DateText.dayKey(day.value, zone = ZoneId.systemDefault()), "note" to note.value.nonEmpty, "confirmName" to typed.value, "signature" to sign),
                    )
                    is ClientsClosingTarget.archivePet -> API.post(
                        "/provider-client-pets/${target.petId}/archive",
                        mapOf("reason" to reason, "note" to note.value.nonEmpty, "confirmName" to typed.value),
                    )
                    is ClientsClosingTarget.archiveClient -> API.post(
                        "/provider-clients/${target.id}/archive",
                        mapOf("reason" to reason, "note" to note.value.nonEmpty, "confirmName" to typed.value, "signature" to sign),
                    )
                }
                val cancelled = result.visitsCancelled ?: 0
                val closed = result.followUpsCancelled ?: 0
                val parts = listOfNotNull(
                    if (cancelled == 1) L("1 visit cancelled") else if (cancelled > 1) LF("%d visits cancelled", cancelled) else null,
                    if (closed == 1) L("1 reminder closed") else if (closed > 1) LF("%d reminders closed", closed) else null,
                    if ((result.invitationsRevoked ?: 0) > 0) L("invitation revoked") else null,
                )
                val headline = if (target.isDeath) LF("The death of %@ is recorded.", target.name) else LF("%@ is archived.", target.name)
                Toast.show(if (parts.isEmpty()) headline else "$headline ${parts.joinToString(" · ")}")
                announceDataChanged()
                onDone(result)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                refusal(failure)
                loadPreview()
            } finally {
                saving = false
            }
        }
    }

    val subtitle = when {
        target.isDeath -> LF("%@’s chart stays as it is. Nothing is sent to the family.", target.name)
        target.isClient -> LF("%@ and their pets leave the active lists. Their history and what they owe stay.", target.name)
        else -> LF("%@ leaves the active lists. The chart stays, and it can be restored.", target.name)
    }

    ProviderModal(
        title = if (target.isDeath) LF("Record that %@ died", target.name) else LF("Archive %@", target.name),
        subtitle = subtitle,
        systemImage = if (target.isDeath) "flower-2" else "archive",
        // The web washes a death in grey (ink-soft).
        accent = if (target.isDeath) ModalAccent.grey else ModalAccent.pink,
        isDirty = note.value.isNotEmpty() || typed.value.isNotEmpty() || signature.value.isDirty,
        form = form,
        primaryTitle = if (ready) {
            if (saving) (if (target.isDeath) L("Saving…") else L("Archiving…"))
            else if (target.isDeath) L("Record the death")
            else LF("Archive %@", if (target.isClient) target.confirmName else target.name)
        } else null,
        primaryVariant = ButtonVariant.destructive,
        isWorking = saving,
        primaryIcon = if (target.isDeath) "flower-2" else "archive",
        onPrimary = if (ready) ({ submit() }) else null,
        modifier = modifier,
    ) {
        ClientsClosingConsequences(target, preview = preview, previewFailed = previewFailed)
        if (blocked.isNotEmpty()) {
            NoticeCard(tone = NoticeCard.Tone.problem, title = L("It can’t be done yet"), message = blocked.joinToString("\n") { ClientsText.blocker(it.second, name = it.first) })
        }
        if (ready) {
            if (target.isDeath) {
                DateTimeField(label = L("Day it happened"), date = day, zone = ZoneId.systemDefault(), max = Instant.now(), components = setOf(DatePickerComponent.date), key = "day", form = form, required = true)
            } else {
                FormField(label = L("Reason"), key = "reason", form = form, required = true) {
                    FlowLayout(spacing = 8.dp) {
                        reasons.forEach { value ->
                            FilterChip(title = ClientsText.closingReason(value) ?: value, isSelected = reason == value, tone = Theme.blue) {
                                reason = value
                                form.clear("reason")
                            }
                        }
                    }
                }
            }
            PMTextField(
                if (reason == "other" && !target.isDeath) L("Note") else L("Note (optional)"),
                text = note,
                key = "note",
                form = form,
                prompt = if (target.isDeath) L("e.g. The family called to let us know") else L("e.g. Moved to Monterrey"),
                multiline = true,
                required = reason == "other" && !target.isDeath,
                lines = 2..6,
            )
            // The stamp: whose name the change stays under, shown before it is made.
            ClientsStamp(signed = signs)
            if (signs) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel(L("Your signature"), required = true)
                    SignatureCapture(modes = SignatureCaptureMode.savedOrDrawn, draft = signature, saved = savedSignature, savedHint = L("Your saved signature goes on this record."), label = null, form = form)
                }
            }
            PMTextField(LF("Type %@ to confirm", target.confirmName), text = typed, key = "typed", form = form, prompt = target.confirmName, capitalization = KeyboardCapitalization.None, required = true)
        }
    }
    LaunchedEffect(Unit) { loadPreview() }
}

@Composable
private fun ClientsClosingConsequences(target: ClientsClosingTarget, preview: ClientsClosingPreview?, previewFailed: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L("What happens").uppercase(), style = brandText(11.2, FontWeight.ExtraBold).tracking(1.34), color = Theme.inkSoft)
        if (preview != null) {
            val visits = preview.upcomingVisits ?: emptyList()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val pets = preview.pets
                if (target.isClient && !pets.isNullOrEmpty()) {
                    ClientsConsequence(
                        "paw-print",
                        if (pets.size == 1) LF("Its pet is archived too: %@.", pets[0].name ?: "")
                        else LF("Their %d pets are archived too: %@.", pets.size, pets.mapNotNull { it.name }.joinToString(", ")),
                    )
                }
                ClientsConsequence(
                    "calendar-x-2",
                    if (visits.isEmpty()) L("No visits still to come.") else if (visits.size == 1) L("1 visit still to come is cancelled:") else LF("%d visits still to come are cancelled:", visits.size),
                    quiet = visits.isEmpty(),
                )
                visits.take(4).forEach { visit ->
                    key(visit.id) {
                        ClientsCancelledVisitLine(
                            ClientsText.dayTime(visit.startsAt),
                            " · ${visit.serviceLabel ?: ""}${if (target.isClient) " · ${visit.petName ?: ""}" else ""}",
                        )
                    }
                }
                if (visits.size > 4) Text(LF("and %d more", visits.size - 4), Modifier.padding(start = 26.dp), style = Theme.text(14), color = Theme.inkSoft)
                val reminders = preview.openFollowUps ?: 0
                ClientsConsequence(
                    "bell-off",
                    if (reminders == 0) L("No open reminders.") else if (reminders == 1) L("1 open reminder is closed.") else LF("%d open reminders are closed.", reminders),
                    quiet = reminders == 0,
                )
                if ((preview.invitationsRevoked ?: 0) > 0) {
                    ClientsConsequence("mail-x", L("The invitation to link that is still open is revoked: its link stops working."))
                }
                val owed = (target as? ClientsClosingTarget.archiveClient)?.owed
                if (owed != null) {
                    ClientsConsequence("circle-dollar-sign", LF("They owe %@. It stays on their account.", owed))
                }
                ClientsConsequence(
                    "message-circle-off",
                    if (target.isDeath) L("Nothing is sent to the family, and nothing changes in their PawMiaw app.")
                    else if (visits.isNotEmpty()) L("The family gets no message. If a visit was already agreed, let them know yourself.")
                    else L("Nothing new can be booked until it is restored."),
                )
            }
        } else if (previewFailed) {
            Text(L("Could not check what is booked. Close and try again."), style = Theme.text(14, FontWeight.Bold), color = Theme.destructive)
        } else {
            ClientsSmallSpinner(L("Checking what is booked…"))
        }
    }
}

/** Undo an archive or a recorded death. Reminders open again; cancelled visits stay cancelled. */
@Composable
fun ClientsRestoreSheet(target: ClientsRestoreTarget, onDone: (ClientsClosingResult) -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    val message = when (target) {
        is ClientsRestoreTarget.client -> LF("%@ goes back to the active lists with the pets that were archived along with the client, and their reminders open again. The visits that were cancelled don’t come back: book them again.", target.name)
        is ClientsRestoreTarget.pet -> if (target.deceased) {
            LF("The record of the death is removed, %@ goes back to the active lists and the reminders that were closed open again. The visits that were cancelled don’t come back: book them again.", target.name)
        } else {
            LF("%@ goes back to the active lists and the reminders that were closed open again. The visits that were cancelled don’t come back: book them again.", target.name)
        }
    }

    fun restore() {
        working = true
        scope.launch {
            try {
                val result: ClientsClosingResult = when (target) {
                    is ClientsRestoreTarget.pet -> API.post("/provider-client-pets/${target.id}/restore")
                    is ClientsRestoreTarget.client -> API.post("/provider-clients/${target.id}/restore")
                }
                val reopened = result.followUpsReopened ?: 0
                val extra = if (reopened == 1) L("1 reminder open again") else if (reopened > 1) LF("%d reminders open again", reopened) else null
                Toast.show(listOfNotNull(LF("%@ is back on the active lists.", target.name), extra).joinToString(" "))
                announceDataChanged()
                onDone(result)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val code = (failure as? APIError)?.code
                Toast.error(
                    when (code) {
                        "client_archived" -> L("Restore the client first.")
                        "pet_moved" -> L("It moved to another client. Move it back from its new record.")
                        "recorded_at_hospital" -> L("This death was recorded at the hospital. Undo it from the hospitalization.")
                        else -> L("It could not be restored. Try again.")
                    }
                )
                dismiss()
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Restore %@?", target.name),
        systemImage = "rotate-ccw",
        accent = ModalAccent.aqua,
        primaryTitle = L("Restore"),
        isWorking = working,
        primaryIcon = "rotate-ccw",
        onPrimary = { restore() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium)),
    ) {
        Text(message, style = Theme.bodyFont, color = Theme.inkStrong)
    }
}

/**
 * The stamp on an archived client or pet (web `ClosingBanner`): a dashed
 * card on the muted surface, a 40 disc with the flower or the box, what it
 * is now, who did it, when and why — and the way back.
 */
@Composable
fun ClientsClosingBanner(
    name: String,
    stamp: ClientsClosingStamp?,
    deceasedAt: String?,
    hint: String?,
    onRestore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val died = deceasedAt != null
    val reason = if (stamp?.reason == "deceased") null else ClientsText.closingReason(stamp?.reason)
    val signed: String? = stamp?.at?.let { at ->
        val by = stamp.byName.nonEmpty
        if (by != null) {
            if (died) LF("Recorded by %@ on %@", by, ClientsText.shortDay(at)) else LF("Archived by %@ on %@", by, ClientsText.shortDay(at))
        } else {
            if (died) LF("Recorded on %@", ClientsText.shortDay(at)) else LF("Archived on %@", ClientsText.shortDay(at))
        }
    }
    val line = listOfNotNull(signed, reason?.let { LF("Reason: %@", it) }).joinToString(" · ")
    val off = L("Off the active lists. Nothing new can be booked; everything recorded stays.")
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.surfaceMuted, 60.0), shape)
            .dashedBorder(2.dp, Theme.mix(Theme.inkSoft, 45.0, Theme.borderSoft), Theme.cardRadius, 6.dp, 5.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .softShadow(CircleShape, Theme.navy.copy(alpha = 0.08f), radius = 1.5.dp, y = 1.dp)
                    .size(40.dp)
                    .background(Theme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Glyph(if (died) "flower-2" else "archive", 20.dp, tint = Theme.inkStrong) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (died) LF("%@ died on %@", name, ClientsText.shortDay(deceasedAt)) else LF("%@ is archived", name),
                    style = Theme.heading(18),
                    color = Theme.inkStrong,
                )
                Text(line.nonEmpty ?: off, style = Theme.text(14), color = Theme.inkSoft)
                stamp?.note.nonEmpty?.let { Text("“$it”", style = Theme.text(14), color = Theme.inkStrong) }
                if (line.isNotEmpty()) Text(off, style = Theme.text(12), color = Theme.inkSoft)
                if (hint != null) Text(hint, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
        if (onRestore != null) PMButton(L("Restore"), systemImage = "rotate-ccw", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onRestore)
    }
}
