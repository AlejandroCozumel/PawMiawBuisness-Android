package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

//
//  ClientsInviteSheets.kt (iOS ClientsInviteSheets.swift)
//
//  Inviting a client's family to PawMiaw (web: provider-client-household-
//  invite-sheet.tsx, invitations/bulk-invite-sheet.tsx). One client: the
//  email, the pets and how long the link lasts; sent by email, or the link
//  only, to share by WhatsApp ("Su enlace"). Many at once ("Invitar a
//  PawMiaw"): the clients never invited, 20 at a time, each its own
//  invitation; then who was sent and who was skipped and why.
//

/** One optional read (Swift `try?`): null when it fails; a cancel still cancels. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    null
}

/** SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)])`: as many columns as fit, rows `spacing` apart. */
@Composable
private fun <T> InviteAdaptiveGrid(items: List<T>, minimum: Dp, spacing: Dp, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun ClientsInviteSheet(client: ClientsClient, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val form = remember { FormState() }
    val name = remember { mutableStateOf(client.ownerName) }
    val email = remember { mutableStateOf(client.ownerEmail ?: "") }
    val language = remember { mutableStateOf(client.recipientLanguage ?: "") }
    val days = remember { mutableIntStateOf(7) }
    val message = remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(client.allPets.filter { it.isActive && !it.isLinked }.map { it.id }.toSet()) }
    var invitations by remember { mutableStateOf<List<ClientsInvitation>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<ClientsInvitation?>(null) }
    var reviewing by remember { mutableStateOf<ClientsInvitation?>(null) }
    var readdressing by remember { mutableStateOf<ClientsInvitation?>(null) }

    /** Active pets are offered; one already linked to a family's pet is shown but can't be chosen (unlink it first). */
    val activePets = client.allPets.filter { it.isActive }
    val selectable = activePets.filter { !it.isLinked }
    val nothingToOffer = selectable.isEmpty()
    /** Still open: waiting on the family (not run out), or on the clinic's confirmation. */
    val open = invitations.firstOrNull { invitation ->
        invitation.status == "awaiting_clinic" ||
            (invitation.status == "pending" && (DateText.parse(invitation.expiresAt)?.let { it > Instant.now() } ?: false))
    }
    val activePending = if (open?.status == "pending") open else null
    val awaiting = if (open?.status == "awaiting_clinic") open else null
    /** Nothing to send: someone accepted and waits on the clinic, or no pet is left to offer. */
    val onlyClose = awaiting != null || (activePending == null && nothingToOffer)

    suspend fun loadInvitations() {
        attempt { API.get<Page<ClientsInvitation>>("/provider-client-invitations", mapOf("limit" to "10", "offset" to "0", "providerClientId" to client.id)) }
            ?.let { invitations = it.items }
        loaded = true
    }

    fun create(sendEmail: Boolean) {
        if (nothingToOffer) {
            Toast.show(L("Every active pet of this client is already linked."))
            return
        }
        if (open != null) {
            Toast.show(L("This client already has an active invite."))
            return
        }
        val trimmedEmail = email.value.trim()
        val chosen = selectable.map { it.id }.filter { it in selected }
        if (!form.check(listOf(
                "recipientName" to (if (name.value.nonEmpty == null) L("Add the customer name.") else null),
                "recipientEmail" to (if (trimmedEmail.isEmpty()) L("Add the customer email.") else if (!ClientsText.emailLooksValid(trimmedEmail)) L("Write a valid email.") else null),
                "pets" to (if (chosen.isEmpty()) L("Choose at least one pet to include.") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                val invitation: ClientsInvitation = API.post(
                    "/provider-clients/${client.id}/household-link-invitations",
                    mapOf(
                        "recipientEmail" to trimmedEmail,
                        "recipientLanguage" to language.value.nonEmpty,
                        "recipientName" to name.value.trim(),
                        "providerClientPetIds" to chosen,
                        "expiresInDays" to days.intValue,
                        "message" to message.value.nonEmpty,
                        "sendEmail" to sendEmail,
                    ),
                )
                invitation.url?.let { clipboard.setText(AnnotatedString(it)) }
                Toast.show(if (sendEmail) L("Invite email sent and link copied.") else L("Invite link created and copied."))
                announceDataChanged()
                created = invitation
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                when ((failure as? APIError)?.code) {
                    "already_pending" -> {
                        // Someone sent one meanwhile: show it instead of a second invitation.
                        loadInvitations()
                        Toast.error(L("This client already has an active invite."))
                    }
                    "pets_already_linked" -> form.check(listOf("pets" to L("One of these pets is already linked to a family in PawMiaw. Leave it out, or unlink it first.")))
                    else -> form.apply(failure, known = mapOf("recipientEmail" to "recipientEmail", "recipientName" to "recipientName", "providerClientPetIds" to "pets"))
                }
            } finally {
                saving = false
            }
        }
    }

    fun resend(invitation: ClientsInvitation) {
        resending = true
        scope.launch {
            try {
                API.post<ClientsInvitation>("/provider-client-invitations/${invitation.id}/resend")
                Toast.show(L("Existing invite email resent."))
                announceDataChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure, fallback = L("Could not resend the existing invitation."))
            } finally {
                resending = false
            }
        }
    }

    val done = created
    if (done != null) {
        ProviderModal(title = L("Invite customer to connect"), systemImage = "link-2", accent = ModalAccent.aqua, cancelTitle = L("Done"), modifier = modifier) {
            NoticeCard(
                tone = NoticeCard.Tone.success,
                title = if (done.emailStatus == "sent" || done.emailSentAt != null) L("Invite email sent and link copied.") else L("Invite link created and copied."),
                message = LF("Invitation for %@", done.recipientEmail ?: ""),
            )
            done.url.nonEmpty?.let { url ->
                Text(L("Send their link by WhatsApp or any app."), style = Theme.text(14), color = Theme.inkSoft)
                ShareLink(item = url, modifier = Modifier.fillMaxWidth()) {
                    PMButtonStyle(ButtonVariant.primary, fullWidth = true) { PMButtonContent(L("Their link"), icon = "share") }
                }
            }
        }
    } else {
        /** The web footer's other way: the link only (copied, to share by hand), or the open one's link. */
        val footerActions: List<ModalAction> = when {
            onlyClose -> emptyList()
            activePending != null -> listOf(ModalAction(L("Copy existing link"), icon = "copy", disabled = resending) { clientsCopy(activePending.url, done = L("Existing invite link copied.")) })
            else -> listOf(ModalAction(L("Copy link only"), icon = "copy", disabled = saving || !loaded) { create(sendEmail = false) })
        }
        ProviderModal(
            title = L("Invite customer to connect"),
            subtitle = L("Send a secure link so the customer can approve the household and pet match."),
            systemImage = "link-2",
            accent = ModalAccent.aqua,
            isDirty = message.value.isNotEmpty() || email.value != (client.ownerEmail ?: ""),
            form = form,
            primaryTitle = if (onlyClose) null else if (activePending != null) L("Resend invite") else L("Send email invite"),
            // The duplicate check runs first: nothing is sent before it answers.
            isWorking = saving || resending || !loaded,
            cancelTitle = if (onlyClose) L("Close") else L("Cancel"),
            secondaryActions = footerActions,
            primaryIcon = "mail-plus",
            onPrimary = if (onlyClose) null else ({ if (activePending != null) resend(activePending) else create(sendEmail = true) }),
            modifier = modifier,
        ) {
            // Whose invitation this is, as every create modal says it; the pets are chosen below.
            ContextPills(client = client.ownerName, linked = client.isLinked)
            if (client.isLinked) {
                clientsQuietNote(
                    title = L("Already linked"),
                    text = if (nothingToOffer) L("Every pet of this client is already linked. To link a different pet, unlink one first, then invite again.")
                    else L("This client’s family is already linked. Send another invitation only to add a pet to the same household."),
                )
            }
            if (open != null) {
                ClientsOpenInviteNotice(open, onReview = { reviewing = open }, onReaddress = { readdressing = open })
            }
            ModalSection(title = L("Recipient"), subtitle = L("Email locked on accept"), systemImage = "user-round") {
                PMTextField(L("Customer name"), text = name, key = "recipientName", form = form, prompt = L("Customer name"), capitalization = KeyboardCapitalization.Words, required = true)
                PMTextField(L("Customer email"), text = email, key = "recipientEmail", form = form, prompt = "customer@example.com", keyboard = KeyboardType.Email, required = true)
                MenuField(label = L("Expiration"), options = listOf(3, 7, 14, 30).map { it to LF("%d days", it) }, selection = stateOf({ days.intValue }, { days.intValue = it }))
            }
            ClientsInvitePetSelector(
                activePets = activePets,
                selected = selected,
                form = form,
                toggle = { pet ->
                    selected = if (pet.id in selected) selected - pet.id else selected + pet.id
                    form.clear("pets")
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuField(label = L("Recipient email language"), options = listOf("" to L("Automatic"), "en" to L("English"), "es" to L("Spanish")), selection = language)
                Text(L("For people without an account. Existing accounts keep their saved language."), style = Theme.text(12), color = Theme.inkSoft)
            }
            PMTextField(L("Message"), text = message, prompt = L("Optional note shown on the customer approval page."), multiline = true)
        }
        LaunchedEffect(Unit) { loadInvitations() }
        reviewing?.let { invitation ->
            AppSheet(onDismissRequest = { reviewing = null }) {
                ClientsReviewAcceptanceSheet(invitation = invitation, canManage = true, onDone = { loadInvitations(); announceDataChanged() })
            }
        }
        readdressing?.let { invitation ->
            AppSheet(onDismissRequest = { readdressing = null }) {
                ClientsReaddressSheet(invitation = invitation, onDone = { loadInvitations(); announceDataChanged() })
            }
        }
    }
}

/**
 * The open invitation (web: the blue-edged box): what to do with it instead of a second one.
 * Its link is copied from the footer ("Copy existing link"), as on the web.
 */
@Composable
private fun ClientsOpenInviteNotice(invitation: ClientsInvitation, onReview: () -> Unit, onReaddress: () -> Unit) {
    val awaitingClinic = invitation.status == "awaiting_clinic"
    NoticeCard(
        tone = NoticeCard.Tone.custom(Theme.aqua),
        title = if (awaitingClinic) L("Someone accepted the open invitation") else L("Active invite already exists"),
        icon = null,
        content = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (awaitingClinic) LF("They used another account than %@. Confirm or decline it in Invitations before inviting again.", invitation.recipientEmail ?: "")
                else LF("Sent to %@. It expires %@. Use the existing link or resend it instead of creating a duplicate.", invitation.recipientEmail ?: "", ClientsText.dayTime(invitation.expiresAt)),
                style = Theme.text(14),
                color = Theme.inkSoft,
            )
            FlowLayout(spacing = 8.dp) {
                if (awaitingClinic) {
                    PMButton(L("Review it"), systemImage = "shield-check", size = ButtonSize.compact, action = onReview)
                } else {
                    PMButton(L("Change its email"), systemImage = "at-sign", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onReaddress)
                    invitation.url.nonEmpty?.let { url ->
                        ShareLink(item = url) {
                            PMButtonStyle(ButtonVariant.outline, ButtonSize.compact) { PMButtonContent(L("Their link"), icon = "share", variant = ButtonVariant.outline, size = ButtonSize.compact) }
                        }
                    }
                }
            }
        }
        },
    )
}

/** "Mascotas a incluir" (web `ProviderClientPetSelector`): a card per active pet, a linked one shown but not choosable. */
@Composable
private fun ClientsInvitePetSelector(activePets: List<ClientsPet>, selected: Set<String>, form: FormState, toggle: (ClientsPet) -> Unit) {
    val error = form.error("pets")
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .formScrollTarget("pets", form)
            .fillMaxWidth()
            .then(if (error != null) Modifier.border(1.dp, Theme.destructive.copy(alpha = 0.7f), shape) else Modifier)
            .padding(if (error == null) 0.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(L("Pets to include"), required = true)
            Text(L("Choose which provider-side pet profiles this customer should approve."), style = Theme.text(12), color = Theme.inkSoft)
        }
        if (activePets.isEmpty()) {
            Text(
                L("This client has no pets available for invitation."),
                Modifier
                    .fillMaxWidth()
                    .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                style = Theme.text(14),
                color = Theme.inkSoft,
            )
        } else {
            InviteAdaptiveGrid(activePets, minimum = 300.dp, spacing = 12.dp) { pet ->
                ClientsInvitePetCard(pet, active = !pet.isLinked && pet.id in selected) { toggle(pet) }
            }
        }
        if (error != null) Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
    }
}

@Composable
private fun ClientsInvitePetCard(pet: ClientsPet, active: Boolean, toggle: () -> Unit) {
    val linked = pet.isLinked
    val kind = listOfNotNull(ClientsText.species(pet.petSpecies).nonEmpty, pet.petBreed.nonEmpty).joinToString(" · ").nonEmpty ?: L("Pet profile")
    val note = if (linked) L("Already linked@pet") else pet.microchipNumber.nonEmpty?.let { LF("Microchip %@", it) } ?: L("No microchip saved")
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton(enabled = !linked, onClick = toggle)
            .semantics { this.selected = active }
            .alpha(if (linked) 0.6f else 1f)
            .background(if (active) Theme.wash(Theme.aqua, 18.0) else Theme.surface, shape)
            .then(
                if (linked) Modifier.dashedBorder(2.dp, if (active) Theme.blue else Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                else Modifier.border(2.dp, if (active) Theme.blue else Theme.borderSoft, shape)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetAvatar(name = pet.petName, species = pet.petSpecies, photoURL = pet.photoUrl, size = 48.dp, modifier = Modifier.border(2.dp, Theme.blue, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(pet.petName, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind, style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (linked) {
            Glyph("link-2", 20.dp, tint = Theme.blue)
        } else {
            Tick(on = active)
        }
    }
}

// MARK: - "Invitar a PawMiaw" for many

private const val ClientsBulkInviteLimit = 100

@Composable
fun ClientsBulkInviteSheet(
    modifier: Modifier = Modifier,
    /** One client's own invitation (its link to share): the sheet closes and that one opens. */
    onInviteOne: (ClientsClient) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val pager = remember { Pager<ClientsClient>(fetch = { _, _ -> Page(emptyList(), 0) }) }
    val query = remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Map<String, ClientsClient>>(emptyMap()) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<ClientsBulkResult, Map<String, ClientsClient>>?>(null) }

    fun submit() {
        if (!form.check(listOf(
                "list" to (if (chosen.isEmpty()) L("Tick who to invite.") else if (chosen.size > ClientsBulkInviteLimit) LF("Up to %d at a time: untick some.", ClientsBulkInviteLimit) else null),
            ))) return
        val picked = chosen
        sending = true
        scope.launch {
            try {
                val outcome: ClientsBulkResult = API.post("/provider-client-invitations/bulk", mapOf("clientIds" to picked.keys.toList()))
                result = outcome to picked
                chosen = emptyMap()
                if (outcome.sent.isNotEmpty()) Toast.show(if (outcome.sent.size == 1) L("1 invitation sent.") else LF("%d invitations sent.", outcome.sent.size))
                announceDataChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure)
            } finally {
                sending = false
            }
        }
    }

    val shown = result
    if (shown != null) {
        // The web's result footer: one "Done", no Cancel.
        ProviderModal(
            title = L("Invite to PawMiaw"),
            systemImage = "mail-plus",
            accent = ModalAccent.aqua,
            primaryTitle = L("Done"),
            primaryIcon = "check",
            showsCancel = false,
            onPrimary = { dismiss() },
            modifier = modifier,
        ) {
            ClientsBulkOutcome(shown.first, clients = shown.second, onInviteOne = { client -> dismiss(); onInviteOne(client) })
        }
    } else {
        ProviderModal(
            title = L("Invite to PawMiaw"),
            subtitle = L("Each family gets its own invitation by email. Once they link, they see what you write and can book online with you."),
            systemImage = "mail-plus",
            accent = ModalAccent.aqua,
            isDirty = chosen.isNotEmpty(),
            form = form,
            primaryTitle = if (chosen.isEmpty()) L("Invite") else if (chosen.size == 1) L("Invite 1 client") else LF("Invite %d clients", chosen.size),
            isWorking = sending,
            primaryIcon = "send",
            onPrimary = { submit() },
            modifier = modifier,
        ) {
            val withEmail = pager.items.filter { it.ownerEmail.nonEmpty != null }
            val allShown = withEmail.isNotEmpty() && withEmail.all { chosen[it.id] != null }
            val error = form.error("list")
            SearchField(prompt = L("Name, email or phone"), text = query, shape = SearchField.Shape.field)
            FlowLayout(spacing = 8.dp) {
                val total = pager.total
                val strong = Theme.inkStrong
                val soft = Theme.inkSoft
                Text(
                    buildAnnotatedString {
                        if (total != null) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) {
                                append(if (total == 1) L("1 client not invited yet") else LF("%d clients not invited yet", total))
                            }
                            withStyle(SpanStyle(color = soft)) {
                                append(if (chosen.isEmpty()) "" else " · ${if (chosen.size == 1) L("1 ticked") else LF("%d ticked", chosen.size)}")
                            }
                        } else {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append("—") }
                        }
                    },
                    style = Theme.text(14),
                )
                if (withEmail.isNotEmpty()) {
                    PMButton(
                        if (allShown) (if (withEmail.size == 1) L("Untick the one shown") else LF("Untick the %d shown", withEmail.size))
                        else (if (withEmail.size == 1) L("Tick the one shown") else LF("Tick the %d shown", withEmail.size)),
                        variant = ButtonVariant.outline,
                        size = ButtonSize.compact,
                    ) {
                        form.clear("list")
                        chosen = if (allShown) chosen - withEmail.map { it.id }.toSet() else chosen + withEmail.associateBy { it.id }
                    }
                }
            }
            if (error != null) Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
            ClientsBulkInviteList(
                pager = pager,
                query = query.value,
                chosen = chosen,
                invalid = error != null,
                modifier = Modifier.formScrollTarget("list", form),
                toggle = { client ->
                    form.clear("list")
                    chosen = if (chosen[client.id] != null) chosen - client.id else chosen + (client.id to client)
                },
                onInviteOne = { client -> dismiss(); onInviteOne(client) },
            )
            Text(LF("Up to %d at a time. Clients with no email are invited one by one: copy their link and send it by WhatsApp.", ClientsBulkInviteLimit), style = Theme.text(12), color = Theme.inkSoft)
        }
        Debounced(query.value) { value ->
            // The clients list's rule: the server searches from two letters.
            val q = ClientSearch.query(value)
            pager.fetch = { offset, limit ->
                API.get<Page<ClientsClient>>(
                    "/provider-clients",
                    mapOf("limit" to "$limit", "offset" to "$offset", "unlinked" to "true", "invitation" to "none", "sort" to "name", "search" to q),
                )
            }
            pager.reload()
        }
    }
}

/** The clients never invited, in one bordered box, 20 at a time. */
@Composable
private fun ClientsBulkInviteList(
    pager: Pager<ClientsClient>,
    query: String,
    chosen: Map<String, ClientsClient>,
    invalid: Boolean,
    modifier: Modifier,
    toggle: (ClientsClient) -> Unit,
    onInviteOne: (ClientsClient) -> Unit,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    val error = pager.error
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (invalid) Theme.coral else Theme.borderSoft, shape),
    ) {
        when {
            !pager.loadedOnce && pager.isLoading -> LoadingState()
            error != null && pager.items.isEmpty() -> ErrorState(message = error) { pager.reload() }
            pager.items.isEmpty() -> {
                val searched = ClientSearch.query(query)
                EmptyState(
                    systemImage = if (searched == null) "users-round" else "search",
                    title = searched?.let { LF("Nobody not invited matches “%@”", it) } ?: L("Everyone has been invited"),
                    message = if (searched == null) L("Clients whose family isn’t on PawMiaw and was never invited show here.") else null,
                    accent = Theme.aqua,
                )
            }
            else -> {
                pager.items.forEachIndexed { index, client ->
                    key(client.id) {
                        Column(Modifier.fillMaxWidth().onScrolledIntoView { pager.loadMoreIfNeeded(client) }) {
                            if (index > 0) RowDivider()
                            ClientsBulkInviteRow(client, on = chosen[client.id] != null, invalid = invalid, toggle = { toggle(client) }, onInviteOne = { onInviteOne(client) })
                        }
                    }
                }
                PagerFooter(pager)
            }
        }
    }
}

@Composable
private fun ClientsBulkInviteRow(client: ClientsClient, on: Boolean, invalid: Boolean, toggle: () -> Unit, onInviteOne: () -> Unit) {
    val email = client.ownerEmail.nonEmpty
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (on) Theme.wash(Theme.blue, 6.0) else Theme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .plainButton(enabled = email != null) { if (email != null) toggle() }
                .semantics { selected = on },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tick(on = on, invalid = invalid, modifier = Modifier.alpha(if (email == null) 0.5f else 1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(client.ownerName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(email ?: L("No email"), client.allPets.joinToString(", ") { it.petName }.nonEmpty).joinToString(" · "),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (email == null) {
            PMButton(L("Their link"), systemImage = "link-2", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onInviteOne)
        }
    }
}

/** Who was sent and who was skipped, and why; a client with no email can still get their link. */
@Composable
private fun ClientsBulkOutcome(outcome: ClientsBulkResult, clients: Map<String, ClientsClient>, onInviteOne: (ClientsClient) -> Unit) {
    val name = { id: String -> clients[id]?.ownerName ?: L("A client") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("send", 16.dp, tint = Theme.blue)
            Text(
                if (outcome.sent.size == 1) L("1 invitation sent") else LF("%d invitations sent", outcome.sent.size),
                style = Theme.text(14, FontWeight.ExtraBold),
                color = Theme.inkStrong,
            )
        }
        if (outcome.sent.isEmpty()) {
            Text(L("None went out."), style = Theme.text(14), color = Theme.inkSoft)
        } else {
            FlowLayout(spacing = 6.dp) {
                outcome.sent.forEach {
                    Chip(text = name(it.clientId), tone = Chip.Tone.custom(Theme.blue), percent = 10.0, ink = Theme.inkStrong, weight = FontWeight.SemiBold, size = Chip.Size.wide)
                }
            }
        }
    }
    if (outcome.skipped.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("circle-slash", 16.dp, tint = Theme.inkSoft)
                Text(
                    if (outcome.skipped.size == 1) L("1 not sent") else LF("%d not sent", outcome.skipped.size),
                    style = Theme.text(14, FontWeight.ExtraBold),
                    color = Theme.inkStrong,
                )
            }
            val shape = RoundedCornerShape(Theme.boxRadius)
            Column(Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, shape)) {
                outcome.skipped.forEachIndexed { index, entry ->
                    key(entry.clientId) {
                        if (index > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(name(entry.clientId), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(clientsBulkSkipReason(entry.reason), style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            val client = clients[entry.clientId]
                            if (entry.reason == "no_email" && client != null) {
                                PMButton(L("Their link"), systemImage = "link-2", variant = ButtonVariant.outline, size = ButtonSize.compact) { onInviteOne(client) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun clientsBulkSkipReason(reason: String): String = when (reason) {
    "no_email" -> L("No email: send their link by WhatsApp")
    "already_linked" -> L("Already on PawMiaw with you")
    "pending_invitation" -> L("Already invited, waiting for them")
    "archived" -> L("Archived client")
    else -> reason
}
