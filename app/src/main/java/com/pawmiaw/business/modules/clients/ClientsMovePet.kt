package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant

//
//  ClientsMovePet.kt (iOS ClientsMovePet.swift)
//
//  A pet that has a new family (web: client-closing/move-pet-sheet.tsx):
//  its record with this client is archived and it continues as a new record
//  with the client you choose, which shows the earlier history to read.
//  Only the owner or an admin moves a pet; it is signed and confirmed by
//  typing the pet's name, like archiving.
//

data class ClientsMoveTarget(
    val clientId: String,
    val clientName: String,
    val petId: String,
    val name: String,
    /** The family it is with reads it in PawMiaw: that link stays behind. */
    val linked: Boolean,
) {
    val id: String get() = petId
}

@Serializable
private data class ClientsPetMovedResult(
    val movedTo: Place? = null,
    val visitsCancelled: Int? = null,
    val followUpsMoved: Int? = null,
    val invitationsRevoked: Int? = null,
) {
    @Serializable
    data class Place(val clientId: String, val petId: String)
}

/** "Recorded under your name": whose name the change stays under, shown before it is made. */
@Composable
fun ClientsStamp(signed: Boolean, modifier: Modifier = Modifier) {
    val user = SessionStore.shared.user
    Row(
        modifier
            .fillMaxWidth()
            .dashedBorder(2.dp, Theme.mix(Theme.inkSoft, 45.0, Theme.borderSoft), Theme.boxRadius, 6.dp, 5.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
            Glyph("stamp", 16.dp, tint = Theme.inkStrong)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L("Recorded under your name").uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.27), color = Theme.inkSoft)
            Text(
                "${user?.name.nonEmpty ?: user?.email ?: "—"} · ${ClientsMatches.fullDay(DateText.iso(Instant.now()))}",
                style = Theme.heading(16),
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (signed) L("It shows on the record and in its history, with your signature.") else L("It shows on the record and in its history."),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
        }
    }
}

@Composable
fun ClientsMovePetSheet(
    target: ClientsMoveTarget,
    modifier: Modifier = Modifier,
    /** The new record: its client and pet. */
    onMoved: (String, String) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var preview by remember { mutableStateOf<ClientsClosingPreview?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    val query = remember { mutableStateOf("") }
    val found = remember { Pager<ClientsClient>(fetch = { _, _ -> Page(emptyList(), 0) }) }
    var chosen by remember { mutableStateOf<ClientsClient?>(null) }
    val note = remember { mutableStateOf("") }
    val typed = remember { mutableStateOf("") }
    var savedSignature by remember { mutableStateOf<SignatureMark?>(null) }
    val signature = remember { mutableStateOf(SignatureDraft()) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val blockers = (preview?.pets ?: emptyList()).flatMap { it.blockers ?: emptyList() }
    val ready = preview != null && blockers.isEmpty()
    /** What the search found, never the client it is already with. */
    val results = found.items.filter { it.id != target.clientId }

    suspend fun loadPreview() {
        try {
            preview = API.get<ClientsClosingPreview>("/provider-clients/${target.clientId}/closing-preview", mapOf("petId" to target.petId))
            previewFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            previewFailed = true
        }
        savedSignature = try {
            ProfessionalSignature.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
    }

    fun refusal(error: Throwable): String {
        val api = error as? APIError
        val code = api?.code ?: return L("It could not be moved. Try again.")
        return when (code) {
            "managers_only" -> L("Only the owner or an admin can move a pet to another client.")
            "confirm_name" -> LF("That isn’t the name. Type %@.", target.name)
            "destination_archived" -> L("That client is archived. Restore it first.")
            "same_client" -> L("It already belongs to that client.")
            "client_not_found" -> L("That client was not found. Search again.")
            "already_archived" -> L("It was already archived. Reload the page.")
            "already_deceased" -> L("It was already recorded. Reload the page.")
            "saved_signature_missing" -> {
                savedSignature = null
                L("Your saved signature is gone. Draw it.")
            }
            "signature_required", "signature_missing", "signature_blank" -> L("Your signature is missing. Sign above.")
            "signature_invalid", "signature_too_large" -> L("The signature could not be read. Draw it again.")
            "visit_in_progress", "in_hospital", "on_stay", "stay_booked" -> ClientsText.blocker(code, name = target.name)
            else -> L("It could not be moved. Try again.")
        }
    }

    fun submit() {
        problem = null
        val sign = signature.value.captured(saved = savedSignature)?.json
        val nameMatches = ClientsText.typedNameMatches(typed.value, target.name)
        if (!form.check(listOf(
                "client" to (if (chosen == null) L("Choose the new client.") else null),
                "signature" to (if (sign == null) L("Your signature is missing. Sign above.") else null),
                "typed" to (if (nameMatches) null else LF("That isn’t the name. Type %@.", target.name)),
            ))) return
        val to = chosen ?: return
        if (sign == null) return
        saving = true
        scope.launch {
            try {
                val result: ClientsPetMovedResult = API.post(
                    "/provider-client-pets/${target.petId}/move",
                    mapOf("toClientId" to to.id, "note" to note.value.nonEmpty, "confirmName" to typed.value, "signature" to sign),
                )
                val cancelled = result.visitsCancelled ?: 0
                val moved = result.followUpsMoved ?: 0
                val parts = listOfNotNull(
                    if (cancelled == 1) L("1 visit cancelled") else if (cancelled > 1) LF("%d visits cancelled", cancelled) else null,
                    if (moved == 1) L("1 reminder went along") else if (moved > 1) LF("%d reminders went along", moved) else null,
                    if ((result.invitationsRevoked ?: 0) > 0) L("invitation revoked") else null,
                )
                val headline = LF("%1\$@ is now with %2\$@.", target.name, to.ownerName)
                Toast.show(if (parts.isEmpty()) headline else "$headline ${parts.joinToString(" · ")}")
                announceDataChanged()
                dismiss()
                result.movedTo?.let { place -> onMoved(place.clientId, place.petId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                problem = refusal(failure)
                loadPreview()
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = LF("Move %@ to another client", target.name),
        subtitle = LF("%1\$@ has a new family. Its record with %2\$@ is archived and continues with the new client.", target.name, target.clientName),
        systemImage = "arrow-right-left",
        accent = ModalAccent.mango,
        isDirty = chosen != null || note.value.isNotEmpty() || typed.value.isNotEmpty() || signature.value.isDirty,
        form = form,
        primaryTitle = if (ready) {
            if (saving) L("Moving…") else chosen?.let { LF("Move to %@", it.ownerFirstName.nonEmpty ?: it.ownerName) } ?: L("Move to another client")
        } else null,
        primaryVariant = ButtonVariant.destructive,
        isWorking = saving,
        primaryIcon = "arrow-right-left",
        onPrimary = if (ready) ({ submit() }) else null,
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        FormField(label = L("New client"), key = "client", form = form, required = true) {
            ClientsMoveClientPicker(
                chosen = chosen,
                query = query,
                found = found,
                results = results,
                saving = saving,
                onChoose = { client -> chosen = client; form.clear("client") },
                onChange = { chosen = null; problem = null },
            )
        }
        ClientsMoveWhatHappens(target, preview = preview, previewFailed = previewFailed, chosen = chosen)
        if (blockers.isNotEmpty()) {
            NoticeCard(tone = NoticeCard.Tone.problem, title = L("It can’t be done yet"), message = blockers.joinToString("\n") { ClientsText.blocker(it, name = target.name) })
        }
        if (ready) {
            PMTextField(L("Note (optional)"), text = note, key = "note", form = form, prompt = L("e.g. Her sister adopted her"), multiline = true)
            ClientsStamp(signed = true)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(L("Your signature"), required = true)
                SignatureCapture(modes = SignatureCaptureMode.savedOrDrawn, draft = signature, saved = savedSignature, savedHint = L("Your saved signature goes on this record."), label = null, form = form)
            }
            PMTextField(LF("Type %@ to confirm", target.name), text = typed, key = "typed", form = form, prompt = target.name, capitalization = KeyboardCapitalization.None, required = true)
            problem?.let { Text(it, style = Theme.text(14, FontWeight.Bold), color = Theme.destructive) }
        }
    }
    LaunchedEffect(Unit) { loadPreview() }
    LaunchedEffect(typed.value) { problem = null }
    LaunchedEffect(note.value) { problem = null }
    // The client search's one rule: the server searches from two letters, 250 ms after typing, 20 at a time.
    Debounced(query.value) { value ->
        val q = ClientSearch.query(value)
        found.fetch = { offset, limit ->
            if (q == null) Page(emptyList(), 0)
            else API.get<Page<ClientsClient>>("/provider-clients", mapOf("limit" to "$limit", "offset" to "$offset", "search" to q))
        }
        found.reload()
    }
}

private fun clientsMoveClientLine(client: ClientsClient): String =
    listOfNotNull(client.ownerPhone.nonEmpty, client.allPets.filter { !it.isArchived }.joinToString(", ") { it.petName }.nonEmpty ?: L("No pets yet")).joinToString(" · ")

@Composable
private fun ClientsMoveClientPicker(
    chosen: ClientsClient?,
    query: MutableState<String>,
    found: Pager<ClientsClient>,
    results: List<ClientsClient>,
    saving: Boolean,
    onChoose: (ClientsClient) -> Unit,
    onChange: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    if (chosen != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.blue, 8.0), shape)
                .border(2.dp, Theme.blue, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).background(Theme.surface, CircleShape), contentAlignment = Alignment.Center) {
                Glyph("user-round", 16.dp, tint = Theme.inkStrong)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(chosen.ownerName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(clientsMoveClientLine(chosen), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                L("Change"),
                Modifier.plainButton(enabled = !saving, onClick = onChange).padding(horizontal = 12.dp, vertical = 6.dp),
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blue,
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val searchLabel = L("Find the client")
            SearchField(prompt = L("Search by name, phone or pet"), text = query, shape = SearchField.Shape.field, modifier = Modifier.semantics { contentDescription = searchLabel })
            if (ClientSearch.query(query.value) != null) {
                if ((found.isLoading || !found.loadedOnce) && results.isEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                        Text(L("Searching…"), style = Theme.text(14), color = Theme.inkSoft)
                    }
                } else if (results.isEmpty()) {
                    Text(L("No client matches. Create the client first, then move the pet."), style = Theme.text(14), color = Theme.inkSoft)
                } else {
                    found.total?.let { total ->
                        Text(
                            (if (total == 1) LF("%@ result", "1") else LF("%@ results", total.toString())).uppercase(),
                            style = Theme.text(12).tracking(1.4),
                            color = Theme.inkSoft,
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(shape)
                            .border(1.dp, Theme.borderSoft, shape)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        results.forEachIndexed { index, client ->
                            key(client.id) {
                                if (index > 0) RowDivider()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .onScrolledIntoView { found.loadMoreIfNeeded(client) }
                                        .plainButton { onChoose(client) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(36.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
                                        Glyph("user-round", 16.dp, tint = Theme.inkStrong)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(client.ownerName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(clientsMoveClientLine(client), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        PagerFooter(found)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientsMoveWhatHappens(target: ClientsMoveTarget, preview: ClientsClosingPreview?, previewFailed: Boolean, chosen: ClientsClient?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L("What happens").uppercase(), style = brandText(11.2, FontWeight.ExtraBold).tracking(1.34), color = Theme.inkSoft)
        if (preview != null) {
            val visits = preview.upcomingVisits ?: emptyList()
            val reminders = preview.openFollowUps ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClientsMoveLine("archive", LF("The record with %@ is archived, with everything written on it.", target.clientName))
                ClientsMoveLine(
                    "file-clock",
                    chosen?.let { LF("A new record starts with %@. It shows the earlier history (notes, vaccines, weights, prescriptions) to read, not to change.", it.ownerName) }
                        ?: L("A new record starts with the new client. It shows the earlier history (notes, vaccines, weights, prescriptions) to read, not to change."),
                )
                ClientsMoveLine(
                    "calendar-x-2",
                    if (visits.isEmpty()) L("No visits still to come.") else if (visits.size == 1) L("1 visit still to come is cancelled:") else LF("%d visits still to come are cancelled:", visits.size),
                    quiet = visits.isEmpty(),
                )
                val strong = Theme.inkStrong
                val soft = Theme.inkSoft
                visits.take(4).forEach { visit ->
                    key(visit.id) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append(ClientsText.dayTime(visit.startsAt)) }
                                withStyle(SpanStyle(color = soft)) { append(" · ${visit.serviceLabel ?: ""}") }
                            },
                            Modifier.padding(start = 26.dp),
                            style = Theme.text(14),
                        )
                    }
                }
                if (visits.size > 4) Text(LF("and %d more", visits.size - 4), Modifier.padding(start = 26.dp), style = Theme.text(14), color = Theme.inkSoft)
                ClientsMoveLine(
                    "bell-ring",
                    if (reminders == 0) L("No open reminders to take along.") else if (reminders == 1) LF("1 open reminder goes with %@.", target.name) else LF("%1\$d open reminders go with %2\$@.", reminders, target.name),
                    quiet = reminders == 0,
                )
                ClientsMoveLine("receipt", LF("Bills, payments and signed consents stay with %@.", target.clientName))
                if (target.linked) {
                    ClientsMoveLine("smartphone", LF("The link with %@’s PawMiaw app stays on the archived record. The new record starts without PawMiaw: invite the new family when it is time.", target.clientName))
                }
                if ((preview.invitationsRevoked ?: 0) > 0) {
                    ClientsMoveLine("mail-x", L("The invitation to link that is still open is revoked: its link stops working."))
                }
                ClientsMoveLine(
                    "message-circle-off",
                    if (visits.isEmpty()) L("Nothing is sent to either family.") else L("Nothing is sent to either family. If a visit was already agreed, let them know yourself."),
                )
            }
        } else if (previewFailed) {
            Text(L("Could not check what is booked. Close and try again."), style = Theme.text(14, FontWeight.Bold), color = Theme.destructive)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                Text(L("Checking what is booked…"), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }
}

@Composable
private fun ClientsMoveLine(icon: String, text: String, quiet: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Glyph(icon, 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = Theme.text(14), color = if (quiet) Theme.inkSoft else Theme.inkStrong)
    }
}
