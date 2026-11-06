package com.pawmiaw.business.clients

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

//
//  ClientsLinking.kt (iOS ClientsLinking.swift)
//
//  Two records that are one family or one pet (web: linking/match-review-
//  sheet.tsx, merge-client-sheet.tsx, merge-pet-sheet.tsx, unlink-pet-
//  sheet.tsx, linking-model.ts, use-provider-linking.ts): the possible
//  matches to review, combining two clients or two pets (the one you start
//  from goes, the one you pick stays), and unlinking a pet from its family.
//

// MARK: - Shapes

/** One side of a suggestion: a client or a pet (the fields of both, all optional). */
@Serializable
data class ClientsSuggestedRecord(
    val id: String,
    val name: String? = null,
    // A client.
    val email: String? = null,
    val phone: String? = null,
    val linked: Boolean? = null,
    val linkedAccountName: String? = null,
    val pets: List<String>? = null,
    val createdAt: String? = null,
    // A pet.
    val species: String? = null,
    val breed: String? = null,
    val sex: String? = null,
    val birthDate: String? = null,
    val weightGrams: Double? = null,
    val microchipNumber: String? = null,
    val photoUrl: String? = null,
    val clientId: String? = null,
    val clientName: String? = null,
)

@Serializable
data class ClientsMatchSuggestion(
    val id: String,
    /** "client" or "pet". */
    val kind: String,
    val score: Double? = null,
    val reasons: List<String>? = null,
    val origin: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
    /** The record that would be combined away. */
    val source: ClientsSuggestedRecord,
    /** The record that stays. */
    val target: ClientsSuggestedRecord,
) {
    val isPet: Boolean get() = kind == "pet"
}

@Serializable
data class ClientsMergeResult(
    val status: String? = null,
    val targetId: String? = null,
    /** For two pets: the client of the pet that stays. */
    val providerClientId: String? = null,
)

@Serializable
data class ClientsUnlinkResult(
    val status: String? = null,
    val clientUnlinked: Boolean? = null,
    /** What was done with the bills (`keep`, `forgive`, `not_theirs`); null when the family stays linked through another pet. Absent from an older server. */
    val openBills: String? = null,
    /** `openBills: forgive`: how many bills and how much was forgiven. */
    val forgiven: Forgiven? = null,
) {
    @Serializable
    data class Forgiven(val count: Int = 0, val amount: Int = 0)
}

/**
 * What unlinking a pet would do to the client's bills (GET …/unlink-preview,
 * docs/family-money.md §1 in paw-miaw-backend), read before the sheet asks.
 */
@Serializable
data class ClientsUnlinkPreview(
    /** No other pet of this client is linked: the unlink clears the family and `openBills` applies. */
    val clearsHousehold: Boolean = false,
    /** What the client owes, by currency; empty when nothing. */
    val owed: List<Owed> = emptyList(),
    /** Bills paid, even in part: they stay with the family whatever is chosen. */
    val paidCount: Int = 0,
) {
    @Serializable
    data class Owed(val currency: String? = null, val amount: Int = 0, val count: Int = 0)
}

/** A save that failed (Swift `catch`), a cancel still cancelling. */
private inline fun Throwable.unlessCancelled(handle: (Throwable) -> Unit) {
    if (this is CancellationException) throw this
    handle(this)
}

/** The match suggestions and their words (linking-model.ts, linking-copy.ts). */
object ClientsMatches {
    suspend fun load(): List<ClientsMatchSuggestion> {
        val all: List<ClientsMatchSuggestion> = try {
            API.get("/provider-match-suggestions")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            emptyList()
        }
        return all.filter { (it.status ?: "open") == "open" }
    }

    /** The open suggestions that touch a client: as one of the two clients, or through one of its pets. */
    fun related(suggestions: List<ClientsMatchSuggestion>, clientId: String): List<ClientsMatchSuggestion> =
        suggestions.filter { s ->
            if (s.isPet) (s.source.clientId == clientId || s.target.clientId == clientId) else (s.source.id == clientId || s.target.id == clientId)
        }

    /** The other side's name, as the banner says it. */
    fun otherSideName(s: ClientsMatchSuggestion, clientId: String): String {
        if (!s.isPet) return (if (s.source.id == clientId) s.target.name else s.source.name) ?: "—"
        val mineIsSource = s.source.clientId == clientId
        if (s.source.clientId == clientId && s.target.clientId == clientId) {
            return "${s.source.name ?: "—"} / ${s.target.name ?: "—"}"
        }
        val other = if (mineIsSource) s.target else s.source
        return "${other.name ?: "—"} (${other.clientName ?: "—"})"
    }

    fun reason(code: String): String = when (code) {
        "same_email" -> L("Same email")
        "same_phone" -> L("Same phone")
        "similar_name" -> L("Similar name")
        "same_name" -> L("Same name")
        "same_household" -> L("Same family on PawMiaw")
        "family_confirmed" -> L("The family said it’s the same pet")
        "same_microchip" -> L("Same microchip")
        "same_breed" -> L("Same breed")
        "same_birth_date" -> L("Same birth date")
        else -> code.replace("_", " ")
    }

    fun origin(code: String?): String? = when (code) {
        "family_connected" -> L("Found when the family connected from PawMiaw")
        "invitation_accepted" -> L("Found when the family accepted an invitation")
        "clients_combined" -> L("Found after combining two clients")
        else -> null
    }

    /** A refusal from the linking routes, in the reader's words. */
    fun errorText(error: Throwable): String {
        val api = error as? APIError ?: return L("Something went wrong. Try again.")
        when (api.code) {
            "not_found" -> return L("Not found")
            "closed" -> return L("This suggestion was already answered")
            "same" -> return L("Choose a different record to combine with")
            "walk_in" -> return L("The walk-in client cannot be combined")
            "different_households" -> return L("They are linked to different PawMiaw families")
            "different_household_pets" -> return L("They are linked to different pets in PawMiaw")
            "not_linked" -> return L("This pet is not linked to PawMiaw")
            "reason_required" -> return L("Say why the balance is forgiven.")
            "forgive_forbidden" -> return L("You can’t forgive balances. Ask an owner or admin.")
        }
        if (api.status == 403) return L("You do not have permission to change clients")
        return api.message.nonEmpty ?: L("Something went wrong. Try again.")
    }

    /** Accents, case and punctuation left out, spaces collapsed. */
    fun normalized(value: String?): String {
        val plain = Normalizer.normalize(value ?: "", Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        return plain.map { if (it.isLetter() || it.isDigit()) it.toString() else " " }.joinToString("")
            .split(" ").filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun lastTenDigits(phone: String?): String = (phone ?: "").filter { it.isDigit() }.takeLast(10)

    /** "12 kg", "4.5 kg": the web's pet weight. */
    fun weight(grams: Double?): String? {
        if (grams == null || grams <= 0) return null
        val kilograms = grams / 1000
        val text = if (kilograms >= 10) String.format(Locale.US, "%.0f", kilograms) else String.format(Locale.US, "%.1f", kilograms).replace(".0", "")
        return "$text kg"
    }

    /** "3 oct 2026": the day, month and year. */
    fun fullDay(iso: String?): String {
        val date = DateText.parse(iso?.let { if (it.length == 10) "${it}T12:00:00" else it }) ?: DateText.parse(iso) ?: return "—"
        return DateText.format(date, "dMMMyyyy", Workspace.shared.timeZone)
    }
}

/** "Stays" / "Moves into the other and goes": the two sides of a combine, side by side (stacked on a phone). */
@Composable
private fun ClientsMergeSide(stays: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(if (stays) Theme.wash(Theme.blue, 5.0) else Theme.surface, shape)
            .then(if (stays) Modifier.border(2.dp, Theme.blue, shape) else Modifier.dashedBorder(2.dp, Theme.borderSoft, Theme.boxRadius, 6.dp, 5.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            (if (stays) L("Stays@merge") else L("Moves into the other and goes")).uppercase(),
            style = brandText(10.6, FontWeight.ExtraBold).tracking(1.27),
            color = if (stays) Theme.blue else Theme.inkSoft,
        )
        content()
    }
}

/** The two sides with an arrow between them: down on a phone, right on a wide screen. */
@Composable
private fun ClientsMergePair(source: @Composable () -> Unit, target: @Composable () -> Unit) {
    if (LocalSizeClass.current == SizeClass.regular) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { source() }
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { ClientsMergeArrow("arrow-right") }
            Box(Modifier.weight(1f)) { target() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            source()
            ClientsMergeArrow("arrow-down")
            target()
        }
    }
}

/** The 32 disc between the two sides (web: a bordered circle with the arrow). */
@Composable
private fun ClientsMergeArrow(icon: String) {
    Box(
        Modifier
            .clearAndSetSemantics {}
            .size(32.dp)
            .background(Theme.surface, CircleShape)
            .border(1.dp, Theme.borderSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, 16.dp, tint = Theme.inkSoft) }
}

/** A bulleted line of "What happens". */
@Composable
private fun ClientsWhatHappensLine(text: String, tint: androidx.compose.ui.graphics.Color = Theme.coral) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(tint, CircleShape))
        Text(text, style = Theme.text(14), color = Theme.inkStrong)
    }
}

/** The name of a record with its face and the link to PawMiaw. */
@Composable
private fun ClientsMergeHeading(name: String, photoURL: String? = null, species: String? = null, isPet: Boolean = false, linked: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isPet) {
            PetAvatar(name = name, species = species, photoURL = photoURL, size = 40.dp)
        } else {
            Avatar(name = name, imageURL = photoURL, size = 40.dp)
        }
        Text(name, Modifier.weight(1f, fill = false), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (linked) {
            val label = L("Linked to PawMiaw")
            Box(
                Modifier
                    .semantics { contentDescription = label }
                    .size(28.dp)
                    .background(Theme.wash(Theme.blue, 14.0), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Glyph("link-2", 14.dp, tint = Theme.blue) }
        }
    }
}

/** The link glyph after a name in a pick list. */
@Composable
private fun ClientsLinkedMark() {
    val label = L("Linked to PawMiaw")
    Glyph("link-2", 14.dp, tint = Theme.blue, modifier = Modifier.semantics { contentDescription = label })
}

/**
 * A row to pick (SwiftUI `Button` + a double tap that picks and goes on): dims while pressed,
 * off while blocked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.clientsPickTap(enabled: Boolean, selected: Boolean, onTap: () -> Unit, onDoubleTap: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this
        .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
        .semantics { this.selected = selected }
        .combinedClickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onDoubleClick = onDoubleTap, onClick = onTap)
}

// MARK: - Possible matches

@Composable
fun ClientsMatchReviewSheet(
    suggestions: List<ClientsMatchSuggestion>,
    canEdit: Boolean,
    subtitle: String = L("Records that may be the same family or the same pet. Nothing is combined until you say so."),
    /** After a combine: the suggestion and the record that stayed. */
    onMerged: (ClientsMatchSuggestion, String) -> Unit = { _, _ -> },
    /** After any answer (to read the list again). */
    onAnswered: suspend () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var answered by remember { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun merge(s: ClientsMatchSuggestion) {
        working = "merge:${s.id}"
        scope.launch {
            try {
                val result: ClientsMergeResult = API.post("/provider-match-suggestions/${s.id}/merge")
                answered = answered + s.id
                Toast.show(LF("Combined. %@ keeps everything.", s.target.name ?: ""))
                announceDataChanged()
                onAnswered()
                onMerged(s, result.targetId ?: s.target.id)
            } catch (failure: Throwable) {
                failure.unlessCancelled { Toast.error(ClientsMatches.errorText(it)) }
            } finally {
                working = null
            }
        }
    }

    fun dismissPair(s: ClientsMatchSuggestion) {
        working = "dismiss:${s.id}"
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-match-suggestions/${s.id}/dismiss")
                answered = answered + s.id
                Toast.show(L("Kept apart. We won’t suggest this pair again."))
                onAnswered()
            } catch (failure: Throwable) {
                failure.unlessCancelled { Toast.error(ClientsMatches.errorText(it)) }
            } finally {
                working = null
            }
        }
    }

    ProviderModal(
        title = L("Possible matches"),
        subtitle = subtitle,
        systemImage = "git-merge",
        accent = ModalAccent.coral,
        isWorking = working != null,
        cancelTitle = L("Close"),
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        val open = suggestions.filter { it.id !in answered }
        if (open.isEmpty()) {
            EmptyState(systemImage = "circle-check", title = L("Nothing to review"), message = L("When two records look like the same family or pet, they show up here."), accent = Theme.lime)
        } else {
            open.forEach { suggestion ->
                key(suggestion.id) {
                    ClientsMatchCard(suggestion, canEdit = canEdit, working = working, merge = { merge(it) }, dismissPair = { dismissPair(it) })
                }
            }
        }
    }
}

/**
 * One pair (web `MatchCard`): "¿Mismo cliente?" with why on a coral band,
 * the two sides with the arrow, what combining moves, the two answers.
 */
@Composable
private fun ClientsMatchCard(
    s: ClientsMatchSuggestion,
    canEdit: Boolean,
    working: String?,
    merge: (ClientsMatchSuggestion) -> Unit,
    dismissPair: (ClientsMatchSuggestion) -> Unit,
) {
    Card(padding = 0.dp, spacing = 0.dp) {
        FlowLayout(
            spacing = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.coral, 8.0))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (s.isPet) "paw-print" else "users-round", 16.dp, tint = Theme.inkStrong)
                Text(if (s.isPet) L("Same pet?") else L("Same client?"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            }
            (s.reasons ?: emptyList()).forEach { Chip(text = ClientsMatches.reason(it), tone = Chip.Tone.info, icon = "check") }
        }
        RowDivider()
        Box(Modifier.padding(16.dp)) {
            ClientsMergePair(
                source = { ClientsMergeSide(stays = false) { ClientsMatchColumn(s, s.source, other = s.target) } },
                target = { ClientsMergeSide(stays = true) { ClientsMatchColumn(s, s.target, other = s.source) } },
            )
        }
        RowDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val source = s.source.name ?: "—"
            val target = s.target.name ?: "—"
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (s.isPet) LF("Combining moves %1\$@’s visits, records, invoices, stays, reminders, notes and history to %2\$@. %1\$@ goes.", source, target)
                    else LF("Combining moves %1\$@’s pets, visits, invoices and payments, stays, reminders, notes and history to %2\$@. %1\$@ goes.", source, target),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
                ClientsMatches.origin(s.origin)?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
            }
            if (canEdit) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PMButton(L("Yes, combine"), systemImage = "git-merge", size = ButtonSize.compact, fullWidth = true, isWorking = working == "merge:${s.id}", enabled = working == null) { merge(s) }
                    PMButton(L("No, it’s someone else"), systemImage = "x", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true, isWorking = working == "dismiss:${s.id}", enabled = working == null) { dismissPair(s) }
                }
            } else {
                Text(L("Someone who can edit clients can answer this."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
            }
        }
    }
}

@Composable
private fun ClientsMatchColumn(s: ClientsMatchSuggestion, record: ClientsSuggestedRecord, other: ClientsSuggestedRecord) {
    ClientsMergeHeading(name = record.name ?: "—", photoURL = if (s.isPet) record.photoUrl else null, species = record.species, isPet = s.isPet, linked = record.linked == true)
    clientsMatchRows(s, record, other).forEach { (label, value, same) ->
        val shown = value.nonEmpty
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(label, Modifier.width(84.dp).alignByBaseline(), style = Theme.text(14), color = Theme.inkSoft)
            Text(shown ?: "—", Modifier.weight(1f, fill = false).alignByBaseline(), style = Theme.text(14, FontWeight.SemiBold), color = if (shown == null) Theme.inkSoft else Theme.inkStrong)
            if (same && shown != null) {
                val sameLabel = L("Same on both")
                Glyph("check", 14.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp).semantics { contentDescription = sameLabel })
            }
        }
    }
}

/** Label, value and "same on both" for each line of a side. */
private fun clientsMatchRows(s: ClientsMatchSuggestion, r: ClientsSuggestedRecord, o: ClientsSuggestedRecord): List<Triple<String, String, Boolean>> {
    if (s.isPet) {
        val chip = { value: String? -> (value ?: "").filter { !it.isWhitespace() } }
        return listOf(
            Triple(L("Species"), ClientsText.species(r.species), r.species != null && r.species == o.species),
            Triple(L("Breed"), r.breed ?: "", r.breed.nonEmpty != null && o.breed.nonEmpty != null && ClientsMatches.normalized(r.breed) == ClientsMatches.normalized(o.breed)),
            Triple(L("Born"), r.birthDate?.let { ClientsMatches.fullDay(it) } ?: "", r.birthDate.nonEmpty != null && r.birthDate == o.birthDate),
            Triple(L("Weight"), ClientsMatches.weight(r.weightGrams) ?: "", false),
            Triple(L("Microchip"), r.microchipNumber ?: "", r.microchipNumber.nonEmpty != null && o.microchipNumber.nonEmpty != null && chip(r.microchipNumber) == chip(o.microchipNumber)),
            Triple(L("Client"), r.clientName ?: "", r.clientId != null && r.clientId == o.clientId),
            Triple(L("PawMiaw"), if (r.linked == true) L("Linked@pet") else L("Not linked@pet"), false),
        )
    }
    val email = { value: String? -> (value ?: "").trim().lowercase() }
    return listOf(
        Triple(L("Email"), r.email ?: "", r.email.nonEmpty != null && o.email.nonEmpty != null && email(r.email) == email(o.email)),
        Triple(L("Phone"), r.phone ?: "", r.phone.nonEmpty != null && o.phone.nonEmpty != null && ClientsMatches.lastTenDigits(r.phone) == ClientsMatches.lastTenDigits(o.phone)),
        Triple(L("Pets"), (r.pets ?: emptyList()).joinToString(", "), false),
        Triple(L("PawMiaw"), if (r.linked == true) (r.linkedAccountName.nonEmpty?.let { LF("Linked · %@", it) } ?: L("Linked")) else L("Not linked"), false),
        Triple(L("Added"), ClientsMatches.fullDay(r.createdAt), false),
    )
}

/** "Posible duplicado": the client page's banner for its open suggestions. */
@Composable
fun ClientsMatchBanner(
    suggestions: List<ClientsMatchSuggestion>,
    clientId: String,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
    onReview: () -> Unit,
) {
    val names = suggestions.map { ClientsMatches.otherSideName(it, clientId) }.distinct()
    Card(padding = 14.dp, tint = Theme.coral, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Glyph("git-merge", 16.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(L("Possible duplicate:")) }
                    append(" ${names.joinToString(" · ")}")
                },
                Modifier.weight(1f),
                style = Theme.text(14),
                color = Theme.inkStrong,
            )
        }
        PMButton(if (canEdit) L("Review") else L("See them"), variant = ButtonVariant.outline, size = ButtonSize.compact, action = onReview)
    }
}

// MARK: - Combine with another client

@Composable
fun ClientsMergeClientSheet(
    client: ClientsClient,
    /** The client that stayed. */
    onMerged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val query = remember { mutableStateOf("") }
    val pager = remember { Pager<ClientsClient>(fetch = { _, _ -> Page(emptyList(), 0) }) }
    var picked by remember { mutableStateOf<ClientsClient?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    /** Every other client (not this one), as the server found them. */
    val results = pager.items.filter { it.id != client.id }

    fun merge() {
        val chosen = picked ?: return
        saving = true
        scope.launch {
            try {
                val result: ClientsMergeResult = API.post("/provider-clients/${client.id}/merge", mapOf("intoClientId" to chosen.id))
                Toast.show(LF("Combined into %@.", chosen.ownerName))
                announceDataChanged()
                dismiss()
                onMerged(result.targetId ?: chosen.id)
            } catch (failure: Throwable) {
                failure.unlessCancelled { Toast.error(ClientsMatches.errorText(it)) }
            } finally {
                saving = false
            }
        }
    }

    val target = picked?.ownerName ?: ""
    ProviderModal(
        title = if (confirming) LF("Combine %@ into %@?", client.ownerName, target) else L("Combine with another client"),
        subtitle = if (confirming) L("Check what moves before you combine.") else LF("Pick the record that stays. %@ moves into it.", client.ownerName),
        systemImage = "git-merge",
        accent = ModalAccent.coral,
        isDirty = picked != null,
        primaryTitle = if (confirming) L("Combine") else L("Continue"),
        primaryVariant = if (confirming) ButtonVariant.destructive else ButtonVariant.primary,
        isWorking = saving,
        onBack = if (confirming) ({ confirming = false }) else null,
        primaryIcon = if (confirming) "git-merge" else null,
        onPrimary = if (picked == null) null else ({ if (confirming) merge() else { confirming = true } }),
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        val chosen = picked
        if (confirming && chosen != null) {
            ClientsMergePair(
                source = { ClientsMergeSide(stays = false) { ClientsMergeClientCard(client) } },
                target = { ClientsMergeSide(stays = true) { ClientsMergeClientCard(chosen) } },
            )
            ModalSection(title = L("What happens")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClientsWhatHappensLine(LF("%1\$@ stays. Its pets, visits, invoices and payments, stays, reminders, notes and history are joined by %2\$@’s.", chosen.ownerName, client.ownerName))
                    ClientsWhatHappensLine(LF("Contact details %1\$@ is missing are filled in from %2\$@, and the notes are joined.", chosen.ownerName, client.ownerName))
                    ClientsWhatHappensLine(LF("%@’s record goes. This can’t be undone.", client.ownerName))
                    ClientsWhatHappensLine(L("Two clients linked to different PawMiaw families can’t be combined."))
                }
            }
        } else {
            val searchLabel = L("Search clients")
            SearchField(prompt = L("Client, pet, email or phone"), text = query, shape = SearchField.Shape.field, modifier = Modifier.semantics { contentDescription = searchLabel })
            val error = pager.error
            if (!pager.loadedOnce && pager.isLoading) {
                LoadingState()
            } else if (error != null && results.isEmpty()) {
                ErrorState(message = error) { pager.reload() }
            } else if (results.isEmpty()) {
                val searched = ClientSearch.query(query.value)
                EmptyState(systemImage = if (searched == null) "users-round" else "search", title = searched?.let { LF("No client matches “%@”", it) } ?: L("No other clients yet."), accent = Theme.lime)
            } else {
                val listLabel = L("Clients")
                Column(Modifier.fillMaxWidth().semantics { contentDescription = listLabel }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    results.forEach { other ->
                        key(other.id) {
                            val isBlocked = client.householdId.nonEmpty.let { mine -> val theirs = other.householdId.nonEmpty; mine != null && theirs != null && mine != theirs }
                            val selected = picked?.id == other.id
                            ClientsMergeClientRow(
                                other = other,
                                isBlocked = isBlocked,
                                selected = selected,
                                onTap = { picked = if (selected) null else other },
                                onDoubleTap = { if (!isBlocked) { picked = other; confirming = true } },
                                modifier = Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(other) },
                            )
                        }
                    }
                    PagerFooter(pager)
                }
            }
        }
    }
    // The client search's one rule: the server searches from two letters, 250 ms after typing, 20 at a time.
    Debounced(query.value) { value ->
        val q = ClientSearch.query(value)
        pager.fetch = { offset, limit ->
            API.get<Page<ClientsClient>>("/provider-clients", mapOf("limit" to "$limit", "offset" to "$offset", "search" to q))
        }
        pager.reload()
    }
}

@Composable
private fun ClientsMergeClientRow(
    other: ClientsClient,
    isBlocked: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    val line = listOfNotNull(other.ownerEmail.nonEmpty ?: other.ownerPhone.nonEmpty, other.allPets.filter { !it.isArchived }.joinToString(", ") { it.petName }.nonEmpty)
        .joinToString(" · ").nonEmpty ?: L("No contact details yet")
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (isBlocked) 0.55f else 1f)
            .clientsPickTap(enabled = !isBlocked, selected = selected, onTap = onTap, onDoubleTap = onDoubleTap)
            .background(if (selected) Theme.wash(Theme.blue, 8.0) else Theme.surface, shape)
            .border(2.dp, if (selected) Theme.blue else Theme.borderSoft, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = other.ownerName, imageURL = other.photoUrl, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(other.ownerName, Modifier.weight(1f, fill = false), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                if (other.isLinked) ClientsLinkedMark()
            }
            Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (isBlocked) Text(L("Linked to a different PawMiaw family"), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
        }
        Tick(on = selected, style = Tick.Style.choice)
    }
}

@Composable
private fun ClientsMergeClientCard(c: ClientsClient) {
    ClientsMergeHeading(name = c.ownerName, photoURL = c.photoUrl, linked = c.isLinked)
    listOfNotNull(
        c.ownerEmail.nonEmpty,
        c.ownerPhone.nonEmpty,
        c.allPets.filter { !it.isArchived }.joinToString(", ") { it.petName }.nonEmpty,
    ).forEach { line ->
        Text(line, style = Theme.text(13), color = Theme.inkSoft)
    }
}

// MARK: - Combine with another pet

/** The pet a combine starts from (it goes). */
data class ClientsMergePetSource(
    val clientId: String,
    val clientName: String,
    val petId: String,
    val name: String,
    val species: String?,
    val breed: String?,
    val photoUrl: String?,
    val linked: Boolean,
    /** The family pet it is linked to. */
    val householdPetId: String?,
    val details: String,
) {
    val id: String get() = petId
}

private data class ClientsPetChoice(
    val id: String,
    val name: String,
    val species: String?,
    val breed: String?,
    val photoUrl: String?,
    val clientId: String,
    val clientName: String,
    val linked: Boolean,
    val householdPetId: String?,
    val archived: Boolean,
    val details: String,
)

@Serializable
private data class ClientsDirectoryPet(
    val id: String,
    val petName: String,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val linked: Boolean? = null,
    val client: ClientsRowClient,
    val createdAt: String? = null,
)

@Serializable
private data class ClientsPetDates(val pets: List<Pet>? = null) {
    @Serializable
    data class Pet(val id: String, val createdAt: String? = null)
}

object ClientsPetDetails {
    /** What tells two pets with one name apart: its weight, its chip, when it was added. */
    fun of(pet: ClientsPet, createdAt: String?): String =
        listOfNotNull(
            ClientsMatches.weight(pet.weight),
            pet.microchipNumber.nonEmpty?.let { LF("Chip ··%@", it.takeLast(6)) },
            createdAt?.let { LF("Added %@", ClientsMatches.fullDay(it)) },
        ).joinToString(" · ")
}

@Composable
fun ClientsMergePetSheet(
    source: ClientsMergePetSource,
    /** The client's other pets (this one is left out here). */
    ownPets: List<ClientsPet>,
    canSearchOthers: Boolean,
    /** The client and the pet that stayed. */
    onMerged: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val query = remember { mutableStateOf("") }
    val directory = remember { Pager<ClientsDirectoryPet>(fetch = { _, _ -> Page(emptyList(), 0) }) }
    var dates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var picked by remember { mutableStateOf<ClientsPetChoice?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val own = ownPets.filter { it.id != source.petId }.map { pet ->
        ClientsPetChoice(
            id = pet.id, name = pet.petName, species = pet.petSpecies, breed = pet.petBreed, photoUrl = pet.photoUrl,
            clientId = source.clientId, clientName = source.clientName, linked = pet.isLinked, householdPetId = pet.petId,
            archived = pet.isArchived, details = ClientsPetDetails.of(pet, createdAt = dates[pet.id]),
        )
    }
    /** Other clients' pets the search found (never this client's own). */
    val others = directory.items.filter { it.client.id != source.clientId }.map { entry ->
        entry to ClientsPetChoice(
            id = entry.id, name = entry.petName, species = entry.petSpecies, breed = entry.petBreed, photoUrl = entry.photoUrl,
            clientId = entry.client.id, clientName = entry.client.name ?: "—", linked = entry.linked == true, householdPetId = null,
            archived = entry.status == "archived", details = entry.createdAt?.let { LF("Added %@", ClientsMatches.fullDay(it)) } ?: "",
        )
    }

    fun blocked(choice: ClientsPetChoice): Boolean {
        val mine = source.householdPetId.nonEmpty ?: return false
        val theirs = choice.householdPetId.nonEmpty ?: return false
        return mine != theirs
    }

    fun merge() {
        val chosen = picked ?: return
        saving = true
        scope.launch {
            try {
                val result: ClientsMergeResult = API.post("/provider-clients/${source.clientId}/pets/${source.petId}/merge", mapOf("intoPetId" to chosen.id))
                Toast.show(LF("Combined into %@.", chosen.name))
                announceDataChanged()
                dismiss()
                onMerged(result.providerClientId ?: chosen.clientId, result.targetId ?: chosen.id)
            } catch (failure: Throwable) {
                failure.unlessCancelled { Toast.error(ClientsMatches.errorText(it)) }
            } finally {
                saving = false
            }
        }
    }

    @Composable
    fun row(choice: ClientsPetChoice, otherClient: Boolean, rowModifier: Modifier = Modifier) {
        val isBlocked = blocked(choice)
        val selected = picked?.id == choice.id
        ClientsMergePetRow(
            choice = choice,
            otherClient = otherClient,
            isBlocked = isBlocked,
            selected = selected,
            onTap = { picked = if (selected) null else choice },
            onDoubleTap = { if (!isBlocked) { picked = choice; confirming = true } },
            modifier = rowModifier,
        )
    }

    ProviderModal(
        title = if (confirming) LF("Combine %@ into %@?", source.name, picked?.name ?: "") else L("Combine with another pet"),
        subtitle = if (confirming) L("Check what moves before you combine.") else LF("Pick the pet that stays. %@ moves into it.", source.name),
        systemImage = "git-merge",
        accent = ModalAccent.coral,
        isDirty = picked != null,
        primaryTitle = if (confirming) L("Combine") else L("Continue"),
        primaryVariant = if (confirming) ButtonVariant.destructive else ButtonVariant.primary,
        isWorking = saving,
        onBack = if (confirming) ({ confirming = false }) else null,
        primaryIcon = if (confirming) "git-merge" else null,
        onPrimary = if (picked == null) null else ({ if (confirming) merge() else { confirming = true } }),
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        val chosen = picked
        if (confirming && chosen != null) {
            ClientsMergePair(
                source = {
                    ClientsMergeSide(stays = false) {
                        ClientsMergeHeading(name = source.name, photoURL = source.photoUrl, species = source.species, isPet = true, linked = source.linked)
                        ClientsMergeLines(listOf(listOfNotNull(ClientsText.species(source.species), source.breed.nonEmpty).joinToString(" · "), source.clientName, source.details))
                    }
                },
                target = {
                    ClientsMergeSide(stays = true) {
                        ClientsMergeHeading(name = chosen.name, photoURL = chosen.photoUrl, species = chosen.species, isPet = true, linked = chosen.linked)
                        ClientsMergeLines(listOf(listOfNotNull(ClientsText.species(chosen.species), chosen.breed.nonEmpty).joinToString(" · "), chosen.clientName, chosen.details))
                    }
                },
            )
            ModalSection(title = L("What happens")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClientsWhatHappensLine(LF("%1\$@ stays. %2\$@’s visits, records, invoices, stays, reminders, weight history, notes and history move to it.", chosen.name, source.name))
                    if (chosen.clientId != source.clientId) {
                        ClientsWhatHappensLine(LF("%1\$@ belongs to %2\$@: what moves goes under %2\$@ too.", chosen.name, chosen.clientName))
                    }
                    ClientsWhatHappensLine(LF("Details %1\$@ is missing are filled in from %2\$@, and its care card is completed, not duplicated.", chosen.name, source.name))
                    ClientsWhatHappensLine(LF("%@’s record goes. This can’t be undone.", source.name))
                    ClientsWhatHappensLine(L("Two pets linked to different pets in PawMiaw can’t be combined."))
                }
            }
        } else {
            ModalSection(title = LF("%@’s other pets", source.clientName)) {
                if (own.isEmpty()) {
                    EmptyState(systemImage = "paw-print", title = LF("%@ has no other pets.", source.clientName), accent = Theme.lime)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        own.forEach { choice -> key(choice.id) { row(choice, otherClient = false) } }
                    }
                }
            }
            if (canSearchOthers) {
                ModalSection(title = L("Another client’s pet"), subtitle = L("When the same pet was registered under someone else in the family.")) {
                    val searchLabel = L("Search pets")
                    SearchField(prompt = L("Pet, owner, phone or microchip"), text = query, shape = SearchField.Shape.field, modifier = Modifier.semantics { contentDescription = searchLabel })
                    val searched = ClientSearch.query(query.value)
                    if (searched != null) {
                        if (!directory.loadedOnce || (directory.isLoading && others.isEmpty())) {
                            LoadingState()
                        } else if (others.isEmpty()) {
                            EmptyState(systemImage = "search", title = LF("No other client’s pet matches “%@”", searched), accent = Theme.lime)
                        } else {
                            val listLabel = L("Another client’s pet")
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .alpha(if (directory.isLoading) 0.6f else 1f)
                                    .semantics { contentDescription = listLabel },
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                others.forEach { (entry, choice) ->
                                    key(entry.id) {
                                        row(choice, otherClient = true, rowModifier = Modifier.onScrolledIntoView { directory.loadMoreIfNeeded(entry) })
                                    }
                                }
                                PagerFooter(directory)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        val answer: ClientsPetDates? = try {
            API.get("/provider-clients/${source.clientId}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
        if (answer != null) {
            val found = LinkedHashMap<String, String>()
            (answer.pets ?: emptyList()).forEach { pet -> val at = pet.createdAt; if (at != null && pet.id !in found) found[pet.id] = at }
            dates = found
        }
    }
    // The search's one rule: the server searches from two letters, 250 ms after typing, 20 at a time.
    Debounced(query.value) { value ->
        val q = ClientSearch.query(value)
        if (!canSearchOthers || q == null) {
            directory.fetch = { _, _ -> Page(emptyList(), 0) }
            directory.reload()
            return@Debounced
        }
        directory.fetch = { offset, limit ->
            API.get<Page<ClientsDirectoryPet>>("/provider-directory/pets", mapOf("limit" to "$limit", "offset" to "$offset", "search" to q))
        }
        directory.reload()
    }
}

@Composable
private fun ClientsMergePetRow(
    choice: ClientsPetChoice,
    otherClient: Boolean,
    isBlocked: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    val line = listOfNotNull(
        ClientsText.species(choice.species),
        choice.breed.nonEmpty,
        if (otherClient) choice.clientName else null,
        if (choice.archived) L("Archived@pet") else null,
    ).joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (isBlocked) 0.55f else 1f)
            .clientsPickTap(enabled = !isBlocked, selected = selected, onTap = onTap, onDoubleTap = onDoubleTap)
            .background(if (selected) Theme.wash(Theme.blue, 8.0) else Theme.surface, shape)
            .border(2.dp, if (selected) Theme.blue else Theme.borderSoft, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetAvatar(name = choice.name, species = choice.species, photoURL = choice.photoUrl, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(choice.name, Modifier.weight(1f, fill = false), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                if (choice.linked) ClientsLinkedMark()
            }
            Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            choice.details.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
            if (isBlocked) Text(L("Linked to a different pet in PawMiaw"), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
        }
        Tick(on = selected, style = Tick.Style.choice)
    }
}

@Composable
private fun ClientsMergeLines(values: List<String>) {
    values.filter { it.isNotEmpty() }.forEach { Text(it, style = Theme.text(13), color = Theme.inkSoft) }
}

// MARK: - "Se desconectó"

/**
 * "Se desconectó de PawMiaw el 6 nov" (the family unlinked itself) or "Desvinculado el 6 nov"
 * (the business did), from the client's `disconnectedAt` / `disconnectedBy` (web
 * linking/disconnected-mark.tsx; docs/family-money.md §1 in paw-miaw-backend). Null while the
 * client is linked or was never linked.
 */
object ClientsDisconnected {
    fun text(disconnectedAt: String?, disconnectedBy: String?): String? {
        if (disconnectedAt.nonEmpty == null) return null
        val day = ClientsText.shortDay(disconnectedAt)
        return if (disconnectedBy == "business") LF("Unlinked on %@", day) else LF("Disconnected from PawMiaw on %@", day)
    }

    /** What it means for money, said where the mark has room (the client's page, their account). */
    fun detail(disconnectedAt: String?, disconnectedBy: String?): String? {
        if (disconnectedAt.nonEmpty == null) return null
        return if (disconnectedBy == "business") L("Your business unlinked them. What they owe from before stays theirs, and a reminder in PawMiaw still reaches them.")
        else L("The family unlinked itself. What they owe from before stays theirs, and a reminder in PawMiaw still reaches them.")
    }
}

/**
 * The mark a disconnected client carries on its row, its page, Te deben and its account: a small
 * amber pill with the day; `withDetail` adds the line saying what it means for what they owe.
 * Nothing when it doesn't apply.
 */
@Composable
fun ClientsDisconnectedMark(disconnectedAt: String?, disconnectedBy: String?, withDetail: Boolean = false, modifier: Modifier = Modifier) {
    val text = ClientsDisconnected.text(disconnectedAt, disconnectedBy) ?: return
    val detail = ClientsDisconnected.detail(disconnectedAt, disconnectedBy)
    val pill: @Composable (Modifier) -> Unit = { pillModifier ->
        Row(
            pillModifier
                .background(Theme.wash(Theme.mango, 12.0), CircleShape)
                .border(1.dp, Theme.mix(Theme.mango, 55.0, Theme.borderSoft), CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .semantics(mergeDescendants = true) { if (!withDetail && detail != null) contentDescription = "$text. $detail" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("link-2-off", 12.dp, tint = Theme.inkStrong)
            Text(text, style = Theme.text(11.2, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (!withDetail || detail == null) {
        pill(modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pill(Modifier)
        Text(detail, style = Theme.text(12), color = Theme.inkSoft)
    }
}

// MARK: - Unlink a pet from the family

data class ClientsUnlinkTarget(
    val clientId: String,
    val clientName: String,
    val petId: String,
    val petName: String,
) {
    val id: String get() = petId
}

/**
 * The unlink and the family's bills (web linking-model.ts `unlinkAsksAboutBills`, `unlinkBody`,
 * `unlinkProblem`, `owedCount`; unlink-pet-sheet.tsx `unlinkedMessage`). It asks what happens to
 * the open bills only when the unlink clears the family and something is owed.
 */
object ClientsUnlinkRules {
    /** "Seguir cobrando" (the default), "Perdonar el saldo", "Era otra familia". */
    const val keep = "keep"
    const val forgive = "forgive"
    const val notTheirs = "not_theirs"

    fun asksAboutBills(preview: ClientsUnlinkPreview?): Boolean =
        preview != null && preview.clearsHousehold && preview.owed.any { it.amount > 0 }

    /** What the unlink sends: nothing while it doesn't ask (the server keeps every bill with the family, as older apps), else the choice — with its reason when the balance is forgiven. */
    fun body(asks: Boolean, choice: String, reason: String): Map<String, Any?> {
        if (!asks) return emptyMap()
        if (choice == forgive) return mapOf("openBills" to forgive, "reason" to reason.trim())
        return mapOf("openBills" to choice)
    }

    /** Why the unlink can't go yet: forgiving needs a reason. */
    fun problem(asks: Boolean, choice: String, reason: String): String? =
        if (asks && choice == forgive && reason.trim().isEmpty()) "reason" else null

    /** How many open bills, over every currency. */
    fun owedCount(preview: ClientsUnlinkPreview?): Int = (preview?.owed ?: emptyList()).sumOf { it.count }

    /** "$1,200.00" or, owed in two currencies, "$1,200.00 + US$40.00". */
    fun owedAmounts(preview: ClientsUnlinkPreview): String =
        preview.owed.filter { it.amount > 0 }.joinToString(" + ") { Money.format(it.amount, it.currency) }

    /** Said whatever is chosen: paid bills never leave the family that paid them. */
    fun receiptsLine(preview: ClientsUnlinkPreview?): String {
        if (preview == null || preview.paidCount <= 0) return L("The family keeps its receipts.")
        return if (preview.paidCount == 1) L("The family keeps its receipts: 1 paid bill stays in their PawMiaw.")
        else LF("The family keeps its receipts: %d paid bills stay in their PawMiaw.", preview.paidCount)
    }

    /** The toast: unlinked, and what happened to the bills (forgiven: how much, when one currency says it). */
    fun unlinkedMessage(result: ClientsUnlinkResult, petName: String, clientName: String, preview: ClientsUnlinkPreview?): String {
        val forgiven = result.forgiven
        if (result.openBills == forgive && forgiven != null && forgiven.count > 0) {
            val currencies = (preview?.owed ?: emptyList()).filter { it.amount > 0 }
            if (currencies.size == 1) return LF("%1\$@ unlinked. %2\$@ forgiven.", petName, Money.format(forgiven.amount, currencies[0].currency))
            return if (forgiven.count == 1) LF("%@ unlinked. 1 bill forgiven.", petName) else LF("%1\$@ unlinked. %2\$d bills forgiven.", petName, forgiven.count)
        }
        if (result.openBills == notTheirs) return LF("%@ unlinked. What was never paid left their PawMiaw.", petName)
        return if (result.clientUnlinked == true) LF("%1\$@ unlinked. %2\$@ is no longer linked to a PawMiaw family.", petName, clientName)
        else LF("%@ unlinked.", petName)
    }
}

/**
 * Desvincular: the clinic keeps its pet and all its history, the family's PawMiaw pet stops
 * being shared with the clinic. Before it asks, it reads what the unlink would do to the bills
 * (GET …/unlink-preview, docs/family-money.md §1 in paw-miaw-backend). When this is the client's
 * last linked pet and they owe something, it says how much ("Te debe $1,200 · 2 cuentas
 * pendientes") and asks what happens to it: Seguir cobrando (the default: the family keeps seeing
 * it), Perdonar el saldo (billing.waive_visit, with a reason) or Era otra familia (what was never
 * paid leaves their PawMiaw). Whatever is chosen, the family keeps its receipts. An older server
 * (no preview) unlinks as before, sending nothing.
 */
@Composable
fun ClientsUnlinkPetSheet(target: ClientsUnlinkTarget, onDone: suspend () -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var working by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ClientsUnlinkPreview?>(null) }
    var reading by remember { mutableStateOf(true) }
    val choice = remember { mutableStateOf(ClientsUnlinkRules.keep) }
    val reason = remember { mutableStateOf("") }
    val canForgive = Workspace.shared.can(Permission.billingWaiveVisit)

    LaunchedEffect(target.clientId, target.petId) {
        reading = true
        // An older server answers 404 (no preview), and any failure reads as none: the unlink then sends nothing, as before.
        preview = try {
            API.get<ClientsUnlinkPreview>("/provider-clients/${target.clientId}/pets/${target.petId}/unlink-preview")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
        reading = false
    }

    val asks = ClientsUnlinkRules.asksAboutBills(preview)

    fun unlink() {
        if (!form.check(listOf("reason" to if (ClientsUnlinkRules.problem(asks, choice.value, reason.value) == "reason") L("Say why the balance is forgiven.") else null))) return
        val body = ClientsUnlinkRules.body(asks, choice.value, reason.value)
        val shown = preview
        working = true
        scope.launch {
            try {
                val path = "/provider-clients/${target.clientId}/pets/${target.petId}/unlink"
                val result: ClientsUnlinkResult = if (body.isEmpty()) API.post(path) else API.post(path, body)
                Toast.show(ClientsUnlinkRules.unlinkedMessage(result, target.petName, target.clientName, shown))
                announceDataChanged()
                onDone()
                dismiss()
            } catch (failure: Throwable) {
                failure.unlessCancelled {
                    if ((it as? APIError)?.code == "reason_required") {
                        form.check(listOf("reason" to L("Say why the balance is forgiven.")))
                    } else {
                        Toast.error(ClientsMatches.errorText(it))
                    }
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Unlink %@?", target.petName),
        subtitle = L("From the family’s PawMiaw"),
        systemImage = "link-2-off",
        accent = ModalAccent.pink,
        isDirty = reason.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Unlink"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Keep it linked"),
        primaryIcon = "link-2-off",
        primaryDisabled = reading,
        onPrimary = { unlink() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ClientsWhatHappensLine(LF("Your clinic keeps its copy of %@, with every visit, record and invoice.", target.petName), tint = Theme.pink)
            ClientsWhatHappensLine(LF("The family stops sharing %@ with your clinic: what they add in PawMiaw no longer reaches you, and what you write no longer reaches them.", target.petName), tint = Theme.pink)
            ClientsWhatHappensLine(L("If it was the wrong pet, unlink it and invite the family again to link the right one."), tint = Theme.pink)
            // Said whatever is chosen: paid bills never leave the family that paid them.
            ClientsWhatHappensLine(ClientsUnlinkRules.receiptsLine(preview), tint = Theme.pink)
        }
        val shown = preview
        if (reading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                Text(L("Checking what they owe…"), style = Theme.text(14), color = Theme.inkSoft)
            }
        } else if (asks && shown != null) {
            ModalSection(title = L("What happens to what they owe?")) {
                val count = ClientsUnlinkRules.owedCount(shown)
                val soft = Theme.inkSoft
                Text(
                    buildAnnotatedString {
                        append(LF("They owe you %@", ClientsUnlinkRules.owedAmounts(shown)))
                        withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = soft)) {
                            append(" · " + if (count == 1) L("1 open bill") else LF("%d open bills", count))
                        }
                    },
                    style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                )
                OptionCards(
                    options = listOfNotNull(
                        OptionCard(ClientsUnlinkRules.keep, title = L("Keep charging"), description = L("The family keeps seeing what they owe and their receipts."), icon = "hand-coins", accent = Theme.pink),
                        if (canForgive) OptionCard(ClientsUnlinkRules.forgive, title = L("Forgive the balance"), description = L("Everything pending is forgiven."), icon = "hand-heart", accent = Theme.pink) else null,
                        OptionCard(ClientsUnlinkRules.notTheirs, title = L("It was another family"), description = L("What was never paid stops showing in their PawMiaw; what was paid stays with whoever paid it."), icon = "users", accent = Theme.pink),
                    ),
                    selection = choice,
                )
                if (choice.value == ClientsUnlinkRules.forgive) {
                    PMTextField(
                        L("Why is it forgiven?"), text = reason, key = "reason", form = form,
                        prompt = L("e.g. The family moved away, a courtesy"),
                        hint = L("Only your team sees the reason. It shows in Cortes as an exception."),
                        multiline = true, required = true,
                    )
                }
            }
        }
    }
}
