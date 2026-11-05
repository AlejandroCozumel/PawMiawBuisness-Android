package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

//
//  InvitationsScreen.kt (iOS InvitationsScreen.swift)
//
//  Invitations (web: routes/provider.invitations.tsx, invitations/
//  invitation-list.tsx, invitation-sheets.tsx): the link a business sends so
//  a client's family connects their PawMiaw household to the business's
//  record. Who is waiting, who linked, whose link ran out, and who was never
//  asked; resend, revoke, change the email, confirm an acceptance from
//  another account, and "Invitar a PawMiaw" for many.
//
//  The page as the web draws it: the module header (its numbers are the
//  status filter), then one card: the search, the status chips, the rows
//  (20 at a time, infinite scroll) and how many there are.
//

object ClientsInvitationWords {
    val statuses = listOf("pending", "awaiting_clinic", "accepted", "expired", "revoked")

    /** The filter's name (plural in Spanish: "Pendientes"). */
    fun filterLabel(status: String): String = when (status) {
        "pending" -> L("Pending@filter")
        "awaiting_clinic" -> L("To confirm")
        "accepted" -> L("Linked@filter")
        "expired" -> L("Expired@filter")
        else -> L("Revoked@filter")
    }

    /** One invitation's state (singular in Spanish: "Pendiente"). */
    fun chipLabel(status: String): String = when (status) {
        "pending" -> L("Pending@status")
        "awaiting_clinic" -> L("To confirm@status")
        "accepted" -> L("Linked@status")
        "expired" -> L("Expired@status")
        else -> L("Revoked@status")
    }

    /** The colours the rest of the workspace uses (web INVITATION_STATE): mango waits, green is done, grey ran out or was stopped. */
    fun state(status: String): ProviderState = when (status) {
        "pending", "awaiting_clinic" -> ProviderState.waiting
        "accepted" -> ProviderState.done
        else -> ProviderState.closed
    }

    /** The server marks expiry when it lists; a page left open catches up here. */
    fun effectiveStatus(invitation: ClientsInvitation): String {
        if (invitation.status != "pending") return invitation.status
        val expires = DateText.parse(invitation.expiresAt) ?: return invitation.status
        return if (!expires.isAfter(Instant.now())) "expired" else "pending"
    }

    fun expiringSoon(invitation: ClientsInvitation): Boolean {
        if (effectiveStatus(invitation) != "pending") return false
        val expires = DateText.parse(invitation.expiresAt) ?: return false
        return Duration.between(Instant.now(), expires).seconds <= 48 * 3600
    }

    fun wasEmailed(invitation: ClientsInvitation): Boolean =
        invitation.emailSentAt != null || invitation.emailStatus == "sent"

    fun name(invitation: ClientsInvitation): String =
        invitation.recipientName.nonEmpty ?: invitation.client?.ownerName ?: "—"

    fun acceptedBy(invitation: ClientsInvitation): String? {
        val account = invitation.pendingAcceptance?.account ?: return null
        return listOfNotNull(account.name.nonEmpty, account.email).joinToString(" · ").nonEmpty
    }

    /** The one line that says where the invitation stands in time. */
    fun timing(invitation: ClientsInvitation): String = when (effectiveStatus(invitation)) {
        "accepted" -> LF("Linked %@", ClientsText.shortDay(invitation.acceptedAt))
        "revoked" -> {
            val name = invitation.revokedBy?.name.nonEmpty
            if (name != null) LF("Revoked %@ by %@", ClientsText.shortDay(invitation.revokedAt), name)
            else LF("Revoked %@", ClientsText.shortDay(invitation.revokedAt))
        }
        "awaiting_clinic" -> acceptedBy(invitation)?.let { LF("Accepted by %@", it) } ?: L("Accepted from another account")
        "expired" -> LF("Expired %@", DateText.ago(invitation.expiredAt ?: invitation.expiresAt))
        else -> LF("Expires %@@when", DateText.ago(invitation.expiresAt))
    }

    /** How the timing line reads (web TimingTone): `urgent` a pending one about to run out, `warn` one the clinic confirms. */
    enum class Tone { soft, warn, urgent, good }

    fun timingTone(invitation: ClientsInvitation): Tone = when (effectiveStatus(invitation)) {
        "accepted" -> Tone.good
        "awaiting_clinic" -> Tone.warn
        "pending" -> if (expiringSoon(invitation)) Tone.urgent else Tone.soft
        else -> Tone.soft
    }

    /** "Sent 3 oct by Ana" when an email went out; "Link created 3 oct by Ana" when it never did. */
    fun sent(invitation: ClientsInvitation): String {
        val date = ClientsText.shortDay(invitation.createdAt)
        val name = invitation.createdBy?.name.nonEmpty
        if (!wasEmailed(invitation)) return name?.let { LF("Link created %@ by %@", date, it) } ?: LF("Link created %@", date)
        return name?.let { LF("Sent %@ by %@", date, it) } ?: LF("Sent %@", date)
    }
}

/** One invitation's status as a soft pill with its dot (web `InvitationStatusChip`). */
@Composable
fun ClientsInvitationStatusTag(status: String, modifier: Modifier = Modifier) {
    val state = ClientsInvitationWords.state(status)
    Chip(text = ClientsInvitationWords.chipLabel(status), tone = Chip.Tone.custom(state.color), dot = state.color, percent = 24.0, ink = Theme.inkStrong, modifier = modifier)
}

@Composable
fun InvitationsScreen(clientId: String? = null, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val query = remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var clientFilter by remember { mutableStateOf(clientId) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var notInvited by remember { mutableStateOf<Int?>(null) }
    var expiringSoon by remember { mutableStateOf(0) }
    /** Each client's invitation still open (waiting on the family or on the clinic). */
    var openByClient by remember { mutableStateOf<Map<String, ClientsInvitation>>(emptyMap()) }
    var bulkOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var inviteClient by remember { mutableStateOf<ClientsClient?>(null) }
    var revoking by remember { mutableStateOf<ClientsInvitation?>(null) }
    var reviewing by remember { mutableStateOf<ClientsInvitation?>(null) }
    var readdressing by remember { mutableStateOf<ClientsInvitation?>(null) }
    var history by remember { mutableStateOf<ClientsInvitation?>(null) }
    var resendingId by remember { mutableStateOf<String?>(null) }
    var invitingAgainId by remember { mutableStateOf<String?>(null) }
    val pager = remember { Pager<ClientsInvitation>(fetch = { _, _ -> Page(items = emptyList(), total = 0) }) }

    val canView = workspace.can(Permission.clientsView)
    val canManage = workspace.can(Permission.clientsEdit)
    val filtered = status != null || search.isNotEmpty() || clientFilter != null

    val everCount: Int? = run {
        val values = ClientsInvitationWords.statuses.mapNotNull { counts[it] }
        if (values.size == ClientsInvitationWords.statuses.size) values.sum() else null
    }
    val filterClientName = pager.items.firstOrNull { it.providerClientId == clientFilter }?.client?.ownerName ?: L("this client")

    // MARK: Loading

    suspend fun reloadList() {
        val statusValue = status
        val q = search.nonEmpty
        val client = clientFilter
        pager.fetch = { offset, limit ->
            API.get("/provider-client-invitations", mapOf("limit" to "$limit", "offset" to "$offset", "status" to statusValue, "search" to q, "providerClientId" to client))
        }
        pager.reload()
    }

    suspend fun reloadAll() {
        if (!canView || workspace.inSetup) return
        coroutineScope {
            val list = async { reloadList() }
            val pending = async { ClientsClientPage.fetch<Page<ClientsInvitation>>(true, "/provider-client-invitations", mapOf("limit" to "50", "offset" to "0", "status" to "pending")) }
            val awaiting = async { ClientsClientPage.fetch<Page<ClientsInvitation>>(true, "/provider-client-invitations", mapOf("limit" to "50", "offset" to "0", "status" to "awaiting_clinic")) }
            val accepted = async { ClientsClientPage.fetch<Page<ClientsInvitation>>(true, "/provider-client-invitations", mapOf("limit" to "1", "offset" to "0", "status" to "accepted")) }
            val expired = async { ClientsClientPage.fetch<Page<ClientsInvitation>>(true, "/provider-client-invitations", mapOf("limit" to "1", "offset" to "0", "status" to "expired")) }
            val revoked = async { ClientsClientPage.fetch<Page<ClientsInvitation>>(true, "/provider-client-invitations", mapOf("limit" to "1", "offset" to "0", "status" to "revoked")) }
            val never = async { ClientsClientPage.fetch<Page<ClientsClient>>(true, "/provider-clients", mapOf("limit" to "1", "offset" to "0", "unlinked" to "true", "invitation" to "none")) }
            val pendingPage = pending.await()
            val awaitingPage = awaiting.await()
            val next = mutableMapOf<String, Int>()
            pendingPage?.total?.let { next["pending"] = it }
            awaitingPage?.total?.let { next["awaiting_clinic"] = it }
            accepted.await()?.total?.let { next["accepted"] = it }
            expired.await()?.total?.let { next["expired"] = it }
            revoked.await()?.total?.let { next["revoked"] = it }
            counts = next
            val live = (pendingPage?.items ?: emptyList()).filter { ClientsInvitationWords.effectiveStatus(it) == "pending" }
            expiringSoon = live.count { ClientsInvitationWords.expiringSoon(it) }
            val open = LinkedHashMap<String, ClientsInvitation>()
            for (invitation in live + (awaitingPage?.items ?: emptyList())) {
                if (open[invitation.providerClientId] == null) open[invitation.providerClientId] = invitation
            }
            openByClient = open
            notInvited = never.await()?.total
            list.await()
        }
    }

    fun setStatus(next: String?) {
        status = next
        scope.launch { reloadList() }
    }

    fun toggleStatus(value: String) = setStatus(if (status == value) null else value)

    // MARK: Actions

    fun resend(invitation: ClientsInvitation) {
        resendingId = invitation.id
        scope.launch {
            try {
                API.post<ClientsInvitation>("/provider-client-invitations/${invitation.id}/resend")
                // The first email it ever gets isn't "again".
                Toast.show(
                    if (ClientsInvitationWords.wasEmailed(invitation)) LF("Sent again to %@.", invitation.recipientEmail ?: "")
                    else LF("Sent to %@.", invitation.recipientEmail ?: ""),
                )
                reloadAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                resendingId = null
            }
        }
    }

    fun inviteAgain(invitation: ClientsInvitation) {
        invitingAgainId = invitation.id
        scope.launch {
            try {
                inviteClient = API.get<ClientsClient>("/provider-clients/${invitation.providerClientId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(L("Could not open this client. Try again."))
            } finally {
                invitingAgainId = null
            }
        }
    }

    /** One open invitation per client: an older one that ran out or was revoked offers nothing while a newer one waits. */
    fun offersInviteAgain(invitation: ClientsInvitation): Boolean {
        val state = ClientsInvitationWords.effectiveStatus(invitation)
        if (!canManage || invitation.client?.householdId != null || !(state == "expired" || state == "revoked")) return false
        if (openByClient[invitation.providerClientId] != null) return false
        return invitation.latestForClient != false
    }

    // MARK: Header

    @Composable
    fun headerStats(): List<ModuleStat> {
        val awaiting = counts["awaiting_clinic"] ?: 0
        val stats = mutableListOf(
            ModuleStat(
                "pending", label = L("Pending@filter"), value = counts["pending"]?.toString() ?: "—",
                hint = if (expiringSoon > 0) (if (expiringSoon == 1) L("1 expires within 2 days") else LF("%d expire within 2 days", expiringSoon)) else L("Waiting for the family"),
                icon = "clock-3", accent = ClientsInvitationWords.state("pending").color,
                active = status == "pending", action = { toggleStatus("pending") },
            ),
        )
        if (awaiting > 0 || status == "awaiting_clinic") {
            stats.add(
                ModuleStat(
                    "awaiting", label = L("To confirm"), value = "$awaiting", hint = L("Accepted from another account"),
                    icon = "shield-check", accent = ClientsInvitationWords.state("awaiting_clinic").color, attention = awaiting > 0,
                    active = status == "awaiting_clinic", action = { toggleStatus("awaiting_clinic") },
                ),
            )
        }
        stats.add(
            ModuleStat(
                "accepted", label = L("Linked@filter"), value = counts["accepted"]?.toString() ?: "—", hint = L("Families on PawMiaw"),
                icon = "link-2", accent = ClientsInvitationWords.state("accepted").color,
                active = status == "accepted", action = { toggleStatus("accepted") },
            ),
        )
        stats.add(
            ModuleStat(
                "expired", label = L("Expired@filter"), value = counts["expired"]?.toString() ?: "—",
                hint = if ((counts["expired"] ?: 0) > 0) L("Invite them again") else L("None ran out"),
                icon = "mail-plus", accent = ClientsInvitationWords.state("expired").color,
                active = status == "expired", action = { toggleStatus("expired") },
            ),
        )
        val never = notInvited
        if (never != null) {
            stats.add(
                ModuleStat(
                    "not-invited", label = L("Not invited yet"), value = "$never",
                    hint = if (never > 0) (if (canManage) L("Choose who to invite") else L("Families not on PawMiaw")) else L("Everyone has been asked"),
                    icon = "users-round", accent = Theme.lavender,
                    action = if (never > 0) ({ if (canManage) bulkOpen = true else nav.push(ProviderRoute.section(ProviderSection.clients)) }) else null,
                ),
            )
        }
        return stats
    }

    @Composable
    fun Header() {
        val subtitle = L("When a family links, their own records reach your clinic and what you write reaches them in the app.")
        if (canManage) {
            ModuleHeader(icon = "mail-plus", title = L("Invitations"), subtitle = subtitle, accent = Theme.aqua, stats = headerStats(), storageKey = "invitations") {
                PMButton(L("Invite many"), systemImage = "users-round", variant = ButtonVariant.outline) { bulkOpen = true }
                PMButton(L("Invite a client"), systemImage = "user-plus") { pickerOpen = true }
            }
        } else {
            ModuleHeader(icon = "mail-plus", title = L("Invitations"), subtitle = subtitle, accent = Theme.aqua, stats = headerStats(), storageKey = "invitations")
        }
    }

    // MARK: The card

    @Composable
    fun EmptyList() {
        val title = when {
            search.isNotEmpty() -> LF("Nothing matches “%@”", search)
            status != null -> invitationsEmptyTitle(status!!)
            clientFilter != null -> LF("No invitations for %@ yet", filterClientName)
            else -> L("No invitations here")
        }
        val message = if (search.isNotEmpty()) L("Try another name or email, or look at every invitation.")
        else status?.let { invitationsEmptyBody(it) } ?: L("Invite a client, or look at every invitation.")
        // The web offers "Invite a client" and "Show every invitation"; one action fits here: the way back first.
        val actionTitle: String? = if (filtered) L("Show every invitation") else (if (canManage) L("Invite a client") else L("Open clients"))
        EmptyState(
            systemImage = if (search.isEmpty()) "mail-plus" else "search", title = title, message = message, actionTitle = actionTitle,
            action = {
                if (filtered) {
                    query.value = ""
                    search = ""
                    status = null
                    clientFilter = null
                    scope.launch { reloadList() }
                } else if (canManage) {
                    pickerOpen = true
                } else {
                    nav.push(ProviderRoute.section(ProviderSection.clients))
                }
            },
            accent = Theme.aqua,
        )
    }

    /** The rows, 20 at a time, with a hairline between them. */
    @Composable
    fun Rows() {
        val error = pager.error
        if (!pager.loadedOnce && pager.isLoading) {
            LoadingState()
        } else if (error != null && pager.items.isEmpty()) {
            ErrorState(message = error) { pager.reload() }
        } else if (pager.items.isEmpty()) {
            EmptyList()
        } else {
            Column {
                pager.items.forEachIndexed { index, invitation ->
                    key(invitation.id) {
                        Column(Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(invitation) }) {
                            if (index > 0) RowDivider()
                            ClientsInvitationRow(
                                invitation = invitation, canManage = canManage,
                                resending = resendingId == invitation.id, invitingAgain = invitingAgainId == invitation.id,
                                offersInviteAgain = offersInviteAgain(invitation),
                                onResend = { resend(invitation) }, onRevoke = { revoking = invitation },
                                onReview = { reviewing = invitation }, onChangeEmail = { readdressing = invitation },
                                onInviteAgain = { inviteAgain(invitation) }, onHistory = { history = invitation },
                                onOpenClient = { nav.push(ProviderRoute.client(invitation.providerClientId)) },
                            )
                        }
                    }
                }
                PagerFooter(pager)
            }
        }
    }

    @Composable
    fun ListCard() {
        Card(padding = 0.dp, spacing = 0.dp) {
            Column(Modifier.clip(RoundedCornerShape(Theme.cardRadius))) {
                SearchField(prompt = L("Name or email"), text = query, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                RowDivider()
                // The statuses in the colours the rows and the header's numbers use.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(title = L("All"), count = everCount, isSelected = status == null, tone = Theme.blue) { setStatus(null) }
                    ClientsInvitationWords.statuses.forEach { value ->
                        val tone = ClientsInvitationWords.state(value).color
                        FilterChip(title = ClientsInvitationWords.filterLabel(value), count = counts[value], isSelected = status == value, tone = tone, dot = tone) { toggleStatus(value) }
                    }
                }
                RowDivider()
                if (clientFilter != null) {
                    Row(
                        Modifier.fillMaxWidth().background(Theme.wash(Theme.aqua, 14.0)).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            LF("Only %@’s invitations", filterClientName), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Row(
                            Modifier.plainButton {
                                clientFilter = null
                                scope.launch { reloadList() }
                            },
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("x", 14.dp, tint = Theme.blue)
                            Text(L("Show all"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                        }
                    }
                    RowDivider()
                }
                Rows()
                // How many, under the list (from the server's total).
                val total = pager.total
                if (total != null && total > 0) {
                    RowDivider()
                    Text(
                        if (pager.isLoading) L("Updating…") else LP(total, "%d invitation", "%d invitations"),
                        style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    // From a client's page it is a page pushed over it; from the sidebar, a section.
    providerPage(L("Invitations"), root = clientId == null, modifier = modifier) {
        if (workspace.inSetup) {
            PageScroll {
                ModuleHeader(icon = "mail-plus", title = L("Invitations"), subtitle = L("Link your clients’ families to your clinic on PawMiaw."), accent = Theme.aqua, storageKey = "invitations")
                EmptyState(
                    systemImage = "building-2", title = L("Finish workspace setup before inviting clients."),
                    message = L("Invitations go out in your clinic’s name, so its basics need to be saved first."), accent = Theme.aqua, panel = true,
                )
            }
        } else if (!canView) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see clients.")) }
        } else {
            PageScroll(refresh = { reloadAll() }) {
                Header()
                if (everCount == 0 && !filtered) {
                    EmptyState(
                        systemImage = "mail-plus",
                        title = L("No invitations yet"),
                        message = L("Invite a client and their family can link their PawMiaw household to your record — their pets, vaccines and notes stay in step with yours."),
                        actionTitle = if (canManage) L("Invite a client") else L("Open clients"),
                        action = { if (canManage) pickerOpen = true else nav.push(ProviderRoute.section(ProviderSection.clients)) },
                        accent = Theme.aqua,
                        panel = true,
                    )
                } else {
                    ListCard()
                }
            }
        }
    }
    ReloadsOnScope { reloadAll() }
    Debounced(query.value) { value ->
        val trimmed = value.trim()
        if (trimmed == search) return@Debounced
        search = trimmed
        reloadList()
    }

    if (bulkOpen) {
        AppSheet(onDismissRequest = { bulkOpen = false }) {
            ClientsBulkInviteSheet(onInviteOne = { client ->
                bulkOpen = false
                scope.launch {
                    delay(450)
                    inviteClient = client
                }
            })
        }
    }
    if (pickerOpen) {
        AppSheet(onDismissRequest = { pickerOpen = false }) {
            ClientsInvitePicker(pending = openByClient, onPick = { client ->
                pickerOpen = false
                scope.launch {
                    delay(450)
                    inviteClient = client
                }
            })
        }
    }
    inviteClient?.let { client ->
        AppSheet(onDismissRequest = {
            inviteClient = null
            scope.launch { reloadAll() }
        }) { ClientsInviteSheet(client = client) }
    }
    revoking?.let { invitation ->
        AppSheet(onDismissRequest = { revoking = null }) {
            ClientsRevokeInvitationSheet(invitation = invitation, onRevoked = {
                reloadAll()
                announceDataChanged()
            })
        }
    }
    reviewing?.let { invitation ->
        AppSheet(onDismissRequest = { reviewing = null }) {
            ClientsReviewAcceptanceSheet(invitation = invitation, canManage = canManage, onDone = {
                reloadAll()
                announceDataChanged()
            })
        }
    }
    readdressing?.let { invitation ->
        AppSheet(onDismissRequest = { readdressing = null }) { ClientsReaddressSheet(invitation = invitation, onDone = { reloadAll() }) }
    }
    history?.let { invitation ->
        AppSheet(onDismissRequest = { history = null }) { ClientsInvitationHistorySheet(invitation = invitation) }
    }
}

private fun invitationsEmptyTitle(status: String): String = when (status) {
    "pending" -> L("No pending invitations")
    "awaiting_clinic" -> L("Nothing waiting for your confirmation")
    "accepted" -> L("No linked families yet")
    "expired" -> L("No expired invitations")
    else -> L("No revoked invitations")
}

private fun invitationsEmptyBody(status: String): String = when (status) {
    "pending" -> L("Nobody is waiting to answer. Invite a client and it shows here until their family links.")
    "awaiting_clinic" -> L("When a family accepts from another account than the one you invited, you confirm it here.")
    "accepted" -> L("A family shows here once they accept your invitation.")
    "expired" -> L("Invitations whose link ran out before the family answered show here, to invite them again.")
    else -> L("Invitations you stop before the family answers show here.")
}

// MARK: - A row

/**
 * One invitation (web `InvitationRow`, the phone's stacked card): who it went
 * to, the pets it covers, where it stands, what can be done. The row opens
 * the client (or, waiting for the clinic, the confirmation).
 */
@Composable
private fun ClientsInvitationRow(
    invitation: ClientsInvitation,
    canManage: Boolean,
    resending: Boolean,
    invitingAgain: Boolean,
    offersInviteAgain: Boolean,
    onResend: () -> Unit,
    onRevoke: () -> Unit,
    onReview: () -> Unit,
    onChangeEmail: () -> Unit,
    onInviteAgain: () -> Unit,
    onHistory: () -> Unit,
    onOpenClient: () -> Unit,
) {
    val context = LocalContext.current
    val status = ClientsInvitationWords.effectiveStatus(invitation)
    val name = ClientsInvitationWords.name(invitation)
    val pending = status == "pending"
    val awaiting = status == "awaiting_clinic"
    val tone = ClientsInvitationWords.timingTone(invitation)
    // What needs someone today gets its colour down the left edge, as on Due.
    val stripe: Color? = if (awaiting || tone == ClientsInvitationWords.Tone.urgent) ClientsInvitationWords.state(status).color else null
    val canRevoke = canManage && (pending || awaiting)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                if (awaiting) onReview() else onOpenClient()
            }
            .then(if (stripe != null) Modifier.clientsLeadingEdge(stripe, 6.dp) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = name, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                invitation.recipientEmail.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val clientName = invitation.client?.ownerName.nonEmpty
                if (clientName != null && clientName.lowercase() != name.lowercase()) {
                    Text(LF("Client record: %@", clientName), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InvitationPets(invitation, status)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowLayout(spacing = 4.dp) {
                    ClientsInvitationStatusTag(status = status)
                    if (pending) InvitationDelivery(invitation)
                }
                // What is urgent first, then when it went out.
                if (tone == ClientsInvitationWords.Tone.urgent) {
                    Chip(text = ClientsInvitationWords.timing(invitation), tone = Chip.Tone.custom(Theme.mango), icon = "clock-3", percent = 30.0, ink = Theme.inkStrong)
                } else {
                    Text(
                        ClientsInvitationWords.timing(invitation),
                        style = Theme.text(14, if (tone == ClientsInvitationWords.Tone.warn) FontWeight.Bold else FontWeight.Normal),
                        color = if (tone == ClientsInvitationWords.Tone.soft) Theme.inkSoft else Theme.inkStrong,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (awaiting) LF("Invited: %@", invitation.recipientEmail ?: "") else ClientsInvitationWords.sent(invitation),
                    style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FlowLayout(spacing = 6.dp) {
                if (awaiting) {
                    PMButton(if (canManage) L("Review") else L("See who"), systemImage = "shield-check", size = ButtonSize.compact, action = onReview)
                } else if (offersInviteAgain) {
                    PMButton(L("Invite again"), systemImage = "rotate-cw", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = invitingAgain, action = onInviteAgain)
                }
                if (pending) {
                    PMButton(L("Copy link"), systemImage = "copy", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                        clientsCopy(invitation.url, done = L("Invitation link copied."))
                    }
                }
                if (canManage && pending) {
                    PMButton(
                        if (ClientsInvitationWords.wasEmailed(invitation)) L("Resend") else L("Send by email"),
                        systemImage = "mail-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = resending, action = onResend,
                    )
                }
                if (canRevoke && pending) {
                    PMButton(L("Revoke"), systemImage = "trash-2", variant = ButtonVariant.destructive, size = ButtonSize.compact, action = onRevoke)
                }
                AppMenu(label = { IconCircleLabel() }, accessibilityLabel = LF("More for %@", name)) {
                    if (canManage && pending) item(L("Change email"), icon = "at-sign") { onChangeEmail() }
                    val url = invitation.url.nonEmpty
                    if (pending && url != null) item(L("Share link"), icon = "share") { shareItems(context, listOf(url)) }
                    item(L("Open client"), icon = "user-round") { onOpenClient() }
                    item(L("History@invitation"), icon = "history") { onHistory() }
                    // A pending invitation has Revoke on the row itself.
                    if (canRevoke && !pending) {
                        divider()
                        item(L("Revoke"), icon = "trash-2", destructive = true) { onRevoke() }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitationPets(invitation: ClientsInvitation, status: String) {
    val pets = invitation.pets ?: emptyList()
    if (pets.isEmpty()) {
        Text(L("No pets on it"), style = Theme.text(14), color = Theme.inkSoft)
        return
    }
    FlowLayout(spacing = 6.dp) {
        pets.forEach { pet ->
            key(pet.providerClientPetId) {
                val excluded = status == "accepted" && pet.consentDecision == "exclude"
                val linked = status == "accepted" && (pet.consentDecision == "link_existing" || pet.consentDecision == "create_new")
                val hint = when {
                    excluded -> L("The family chose not to share this pet")
                    linked -> L("Linked to the family’s pet")
                    else -> null
                }
                val ink = Theme.inkStrong
                Row(
                    Modifier.clientsHint(hint),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PetChip(
                        name = pet.petName ?: "—", species = pet.petSpecies,
                        modifier = Modifier
                            .alpha(if (excluded) 0.55f else 1f)
                            // Swift `.strikethrough(excluded)`: a line through the name (after the 24 face and its 6 gap).
                            .then(
                                if (excluded) Modifier.drawWithContent {
                                    drawContent()
                                    val y = size.height / 2
                                    drawLine(ink, Offset(32.dp.toPx(), y), Offset(size.width - 10.dp.toPx(), y), strokeWidth = 1.dp.toPx())
                                } else Modifier
                            ),
                    )
                    if (linked) Glyph("check", 12.dp, tint = ProviderState.done.color)
                }
            }
        }
    }
}

/** Whether the email went out, failed, or the link was only copied. */
@Composable
private fun InvitationDelivery(invitation: ClientsInvitation) {
    when (invitation.emailStatus) {
        "sent" -> Chip(text = L("Email sent"), tone = Chip.Tone.custom(ProviderState.done.color), icon = "mail-check", percent = 16.0, ink = Theme.inkStrong)
        "failed" -> Chip(text = L("Email failed"), tone = Chip.Tone.custom(Theme.destructive), icon = "mail-x", percent = 12.0, ink = Theme.destructive)
        "queued", "sending" -> Chip(text = L("Sending email…"), tone = Chip.Tone.muted, icon = "mail", ink = Theme.inkSoft, weight = FontWeight.SemiBold, outlined = true)
        "not_attempted", "skipped" -> Chip(text = L("Link only"), tone = Chip.Tone.muted, icon = "link-2", ink = Theme.inkSoft, weight = FontWeight.SemiBold, outlined = true)
        else -> {}
    }
}

// MARK: - The invitation in a sheet

/** Who an invitation went to and where it stands (web `InvitationSummary`), at the top of the revoke and history sheets. */
@Composable
private fun ClientsInvitationSummary(invitation: ClientsInvitation) {
    val status = ClientsInvitationWords.effectiveStatus(invitation)
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ClientsInvitationWords.name(invitation), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                invitation.recipientEmail?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            ClientsInvitationStatusTag(status = status)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ClientsTermRow(label = L("Pets"), value = (invitation.pets ?: emptyList()).mapNotNull { it.petName }.joinToString(", ").nonEmpty ?: "—")
            // "Sent" only when an email went out; a link shared by hand was created.
            ClientsTermRow(
                label = if (ClientsInvitationWords.wasEmailed(invitation)) L("Sent@invitation") else L("Created@invitation"),
                value = ClientsText.dayTime(invitation.createdAt) + (invitation.createdBy?.name.nonEmpty?.let { " · $it" } ?: ""),
            )
            if (status == "pending") ClientsTermRow(label = L("Expires@invitation"), value = ClientsText.dayTime(invitation.expiresAt))
        }
    }
}

/** A term and its value in a sheet's box (web `<dl>`): 14 grey label in a fixed column, the value 14 semibold. */
@Composable
private fun ClientsTermRow(label: String, value: String, width: Dp = 80.dp, icon: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.width(width).alignByBaseline())
        Row(Modifier.weight(1f).alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Glyph(icon, 14.dp, tint = Theme.inkSoft)
            Text(value, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
        }
    }
}

// MARK: - Revoke

/** Revoking stops the link from working; the invitation stays in the history. */
@Composable
private fun ClientsRevokeInvitationSheet(invitation: ClientsInvitation, onRevoked: suspend () -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    fun revoke() {
        working = true
        scope.launch {
            try {
                API.delete<ClientsInvitation>("/provider-client-invitations/${invitation.id}")
                Toast.show(L("Invitation revoked."))
                onRevoked()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Revoke this invitation?"),
        subtitle = L("The link stops working. You can invite them again later."),
        systemImage = "triangle-alert",
        accent = ModalAccent.pink,
        primaryTitle = L("Revoke invitation"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Keep it"),
        primaryIcon = "triangle-alert",
        onPrimary = { revoke() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ClientsInvitationSummary(invitation)
        Text(L("If the family opens the link, they’ll see that the invitation is no longer valid."), style = Theme.text(14), color = Theme.inkSoft)
    }
}

// MARK: - Confirm an acceptance from another account

/**
 * Someone accepted from another account than the one invited. Nothing is
 * shared until the clinic says it's the right person: Confirmar links what
 * they chose; "No es esta persona" (a second step) puts it back to waiting.
 */
@Composable
fun ClientsReviewAcceptanceSheet(
    invitation: ClientsInvitation,
    canManage: Boolean,
    onDone: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var approving by remember { mutableStateOf(false) }
    var declining by remember { mutableStateOf(false) }
    var confirmingDecline by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val account = invitation.pendingAcceptance?.account
    val who = account?.name.nonEmpty ?: account?.email ?: L("Someone")

    fun approve() {
        approving = true
        problem = null
        scope.launch {
            try {
                API.post<ClientsInvitation>("/provider-client-invitations/${invitation.id}/approve")
                Toast.show(LF("Confirmed. %@ is linked to your clinic.", who))
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The family's choices no longer fit: say why and suggest starting over.
                if ((e as? APIError)?.status == 409) problem = errorText(e) else Toast.error(e)
            } finally {
                approving = false
            }
        }
    }

    fun decline() {
        declining = true
        scope.launch {
            try {
                API.post<ClientsInvitation>("/provider-client-invitations/${invitation.id}/decline")
                Toast.show(LF("Declined. The invitation is waiting for %@ again.", invitation.recipientEmail ?: ""))
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                declining = false
            }
        }
    }

    val detents = modifier.sheetPresentation(setOf(PresentationDetent.large))
    if (confirmingDecline) {
        ProviderModal(
            title = L("Not this person?"),
            subtitle = LF("Invitation for %@", invitation.recipientEmail ?: ""),
            systemImage = "user-round-x",
            accent = ModalAccent.pink,
            primaryTitle = L("Yes, it’s not them"),
            primaryVariant = ButtonVariant.destructive,
            isWorking = declining,
            onBack = { confirmingDecline = false },
            primaryIcon = "user-round-x",
            onPrimary = { decline() },
            modifier = detents,
        ) {
            Text(LF("Nothing is shared with %@. We’ll tell them the clinic didn’t confirm the connection.", who), style = Theme.text(14), color = Theme.inkStrong)
            Text(LF("The invitation goes back to waiting for %@, and its link keeps working for them.", invitation.recipientEmail ?: ""), style = Theme.text(14), color = Theme.inkStrong)
        }
    } else {
        ProviderModal(
            title = L("Confirm who accepted"),
            subtitle = LF("Invitation for %@", invitation.recipientEmail ?: ""),
            systemImage = "shield-check",
            accent = ModalAccent.coral,
            primaryTitle = if (canManage) L("Confirm") else null,
            isWorking = approving,
            cancelTitle = L("Close"),
            // "No es esta persona" sits in the footer beside Confirmar (a second step asks first); the web has no Cancel there.
            secondaryActions = if (canManage) listOf(ModalAction(L("It’s not this person"), icon = "user-round-x") { confirmingDecline = true }) else emptyList(),
            primaryIcon = "check",
            showsCancel = !canManage,
            onPrimary = if (canManage) ({ approve() }) else null,
            modifier = detents,
        ) {
            Text(
                LF("%@ accepted from an account other than the email you invited. Nothing is shared until you confirm it’s the right person.", who),
                style = Theme.text(14), color = Theme.inkSoft,
            )
            problem?.let {
                NoticeCard(tone = NoticeCard.Tone.problem, title = it, message = L("Choose “It’s not this person”, then invite the family again so they can choose again."))
            }
            AcceptanceAccountBox(invitation)
            ModalSection(title = L("Their choices"), subtitle = L("Your pets on the invitation, and what the family chose for each.")) {
                AcceptanceChoices(invitation)
            }
        }
    }
}

/** Who accepted: their account, their household, whom it was sent to, when. */
@Composable
private fun AcceptanceAccountBox(invitation: ClientsInvitation) {
    val account = invitation.pendingAcceptance?.account
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = account?.name.nonEmpty ?: account?.email ?: "?", size = 40.dp, tint = Theme.coral)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account?.name.nonEmpty ?: L("No name on the account"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(account?.email ?: "—", style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ClientsTermRow(label = L("Household"), value = invitation.pendingAcceptance?.household?.name.nonEmpty ?: "—", width = 96.dp, icon = "house")
            ClientsTermRow(
                label = L("Invited"),
                value = listOfNotNull(ClientsInvitationWords.name(invitation), invitation.recipientEmail).joinToString(" · "),
                width = 96.dp,
            )
            invitation.pendingAcceptance?.requestedAt?.let { at ->
                ClientsTermRow(label = L("Accepted"), value = ClientsText.dayTime(at), width = 96.dp)
            }
        }
    }
}

/** Each pet → what the family chose for it. */
@Composable
private fun AcceptanceChoices(invitation: ClientsInvitation) {
    val pets = invitation.pets ?: emptyList()
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, Theme.borderSoft, shape)) {
        pets.forEachIndexed { index, pet ->
            key(pet.providerClientPetId) {
                if (index > 0) RowDivider()
                val decision = invitation.pendingAcceptance?.decisions?.firstOrNull { it.providerClientPetId == pet.providerClientPetId }
                FlowLayout(spacing = 8.dp, lineSpacing = 4.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(pet.petName ?: "—", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    Glyph("arrow-right", 14.dp, tint = Theme.inkSoft)
                    ChoiceText(decision)
                }
            }
        }
    }
}

@Composable
private fun ChoiceText(decision: ClientsPendingDecision?) {
    val strike = Theme.borderSoft
    // Swift `.strikethrough(color: Theme.borderSoft)`: a soft line through grey words.
    val struck = Modifier.drawWithContent {
        drawContent()
        val y = size.height / 2
        drawLine(strike, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
    when (decision?.decision) {
        "link_existing" -> Text(LF("Their %@", decision.householdPetName ?: L("pet")), style = Theme.text(14, FontWeight.SemiBold), color = Theme.blue)
        "create_new" -> Text(L("A new pet in their PawMiaw"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
        "exclude" -> Text(L("Not shared"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft, modifier = struck)
        else -> Text(L("No answer"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft, modifier = struck)
    }
}

// MARK: - Change the email of a pending invitation

/**
 * Cambiar correo: a pending invitation goes to someone else. A new link is
 * made (the old one stops working) and the invitation runs at least a week.
 */
@Composable
fun ClientsReaddressSheet(invitation: ClientsInvitation, onDone: suspend () -> Unit, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val email = remember { mutableStateOf(invitation.recipientEmail ?: "") }
    val name = remember { mutableStateOf(invitation.recipientName ?: "") }
    // As it was shared the first time: an invitation that went by email goes by email, a copied link is copied again.
    val sendEmail = remember { mutableStateOf(ClientsInvitationWords.wasEmailed(invitation)) }
    var saving by remember { mutableStateOf(false) }
    var shareURL by remember { mutableStateOf<String?>(null) }
    val dirty = email.value != (invitation.recipientEmail ?: "") || name.value != (invitation.recipientName ?: "")

    fun save() {
        val trimmed = email.value.trim()
        val sameName = name.value.nonEmpty?.trim() == invitation.recipientName.nonEmpty?.trim()
        val problem = when {
            !ClientsText.emailLooksValid(trimmed) -> L("Write a valid email.")
            trimmed.lowercase() == invitation.recipientEmail?.lowercase() && sameName -> L("That’s the email it already has.")
            else -> null
        }
        if (!form.check(listOf("recipientEmail" to problem))) return
        saving = true
        scope.launch {
            try {
                val updated: ClientsInvitation = API.patch(
                    "/provider-client-invitations/${invitation.id}",
                    body = mapOf("recipientEmail" to trimmed, "recipientName" to name.value.nonEmpty, "sendEmail" to sendEmail.value),
                )
                if (sendEmail.value) {
                    Toast.show(LF("Sent to %@. The old link no longer works.", updated.recipientEmail ?: trimmed))
                    onDone()
                    dismiss()
                } else {
                    clientsCopy(updated.url, done = L("Saved. The new link is copied; the old one no longer works."))
                    shareURL = updated.url.nonEmpty
                    onDone()
                    if (shareURL == null) dismiss()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("recipientEmail" to "recipientEmail", "recipientName" to "recipientName"))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("Change email"),
        subtitle = LF("Invitation for %@", invitation.client?.ownerName ?: ClientsInvitationWords.name(invitation)),
        systemImage = "at-sign",
        accent = ModalAccent.aqua,
        isDirty = dirty && shareURL == null,
        form = form,
        primaryTitle = if (sendEmail.value) L("Save and send") else L("Save"),
        isWorking = saving,
        primaryIcon = if (sendEmail.value) "mail-plus" else "check",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        PMTextField(L("Email"), text = email, key = "recipientEmail", form = form, keyboard = KeyboardType.Email)
        PMTextField(L("Name"), text = name, key = "recipientName", form = form, prompt = invitation.client?.ownerName, capitalization = KeyboardCapitalization.Words)
        ToggleRow(
            title = L("Send the invitation email"),
            subtitle = if (sendEmail.value) L("We email the new link to this address.") else L("We copy the new link for you to share."),
            isOn = sendEmail,
        )
        NoticeCard(tone = NoticeCard.Tone.custom(Theme.aqua), message = L("A new link is made: the previous one stops working. The invitation stays open at least another week."), icon = null)
        shareURL?.let { url ->
            PMButton(L("Share link"), systemImage = "share", variant = ButtonVariant.outline, fullWidth = true) { shareItems(context, listOf(url)) }
        }
    }
}

// MARK: - Choose a client to invite

/**
 * Choose who to invite (web `InviteClientPicker`): the clients whose family
 * isn't on PawMiaw yet; picking one and Continuar moves on to the
 * invitation itself (email, pets, how long it lasts).
 */
@Composable
fun ClientsInvitePicker(
    /** Each client's invitation still out, to mark them. */
    pending: Map<String, ClientsInvitation> = emptyMap(),
    onPick: (ClientsClient) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = remember { FormState() }
    val query = remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ClientsClient?>(null) }
    val pager = remember { Pager<ClientsClient>(fetch = { _, _ -> Page(items = emptyList(), total = 0) }) }

    Debounced(query.value) { value ->
        // The client search's one rule: the server searches from two letters, 20 at a time.
        val q = ClientSearch.query(value)
        pager.fetch = { offset, limit ->
            API.get("/provider-clients", mapOf("limit" to "$limit", "offset" to "$offset", "unlinked" to "true", "sort" to "name", "search" to q))
        }
        pager.reload()
    }

    ProviderModal(
        title = L("Invite a client"),
        subtitle = L("Choose a client whose family isn’t on PawMiaw yet."),
        systemImage = "mail-plus",
        accent = ModalAccent.aqua,
        form = form,
        primaryTitle = L("Continue"),
        // The web's footer line: what Continue leads to.
        footerSummary = {
            Text(
                if (selected == null) L("Pick a client to continue.") else L("Next: the email, the pets and how long the link lasts."),
                style = Theme.text(14), color = Theme.inkSoft,
            )
        },
        onPrimary = {
            val chosen = selected
            if (form.check(listOf("client" to (if (chosen == null) L("Pick a client to continue.") else null))) && chosen != null) onPick(chosen)
        },
        modifier = modifier,
    ) {
        SearchField(prompt = L("Client, pet, email or phone"), text = query, shape = SearchField.Shape.field)
        Box(Modifier.formScrollTarget("client", form)) {
            val error = pager.error
            if (!pager.loadedOnce && pager.isLoading) {
                LoadingState()
            } else if (error != null && pager.items.isEmpty()) {
                ErrorState(message = error) { pager.reload() }
            } else if (pager.items.isEmpty()) {
                val searched = ClientSearch.query(query.value)
                EmptyState(
                    systemImage = if (searched == null) "house" else "search",
                    title = searched?.let { LF("No client matches “%@”", it) } ?: L("Every client is already on PawMiaw"),
                    message = if (searched == null) L("New clients you add show up here until their family links.") else L("Families already linked don’t show here."),
                    accent = Theme.aqua,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pager.items.forEach { client ->
                        key(client.id) {
                            Box(Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(client) }) {
                                PickerRow(client, active = selected?.id == client.id, open = pending[client.id]) {
                                    selected = if (selected?.id == client.id) null else client
                                    form.clear("client")
                                }
                            }
                        }
                    }
                    PagerFooter(pager)
                }
            }
        }
    }
}

@Composable
private fun PickerRow(client: ClientsClient, active: Boolean, open: ClientsInvitation?, onTap: () -> Unit) {
    val pets = client.allPets.filter { !it.isArchived }
    val contact = client.ownerEmail.nonEmpty ?: client.ownerPhone.nonEmpty ?: L("No email or phone yet")
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) Theme.wash(Theme.blue, 8.0) else Theme.surface, shape)
            .border(2.dp, if (active) Theme.blue else Theme.borderSoft, shape)
            .plainButton { onTap() }
            .semantics { selected = active }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = client.ownerName, imageURL = client.photoUrl, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(client.ownerName, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(contact, pets.joinToString(", ") { it.petName }.nonEmpty).joinToString(" · "),
                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (open != null && open.status == "pending") {
                Chip(
                    text = LF("Invited · expires %@", DateText.ago(open.expiresAt)), tone = Chip.Tone.custom(Theme.mango), percent = 24.0, ink = Theme.inkStrong,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Tick(on = active, style = Tick.Style.choice)
    }
}

// MARK: - One invitation's history

/** Everything that happened to one invitation: sent, resent, opened, accepted, revoked. */
@Composable
private fun ClientsInvitationHistorySheet(invitation: ClientsInvitation) {
    ProviderModal(
        title = L("Invitation history@sheet"), subtitle = invitation.recipientEmail, systemImage = "history", accent = ModalAccent.aqua, cancelTitle = L("Close"),
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        ClientsInvitationSummary(invitation)
        ModalSection(title = L("What happened")) {
            AuditHistoryCard(
                entityType = "provider_client_invitation", entityId = invitation.id, title = L("Activity"),
                description = L("Who sent, resent or revoked it, and when the family answered."), startsOpen = true,
            )
        }
    }
}
