package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.pawmiaw.business.money.CashAccount
import com.pawmiaw.business.money.CashCollectSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

//
//  ClientsClientSections.kt (iOS ClientsClientSections.swift)
//
//  The parts of a client's page below the pets (web: client-page/,
//  addresses/client-home-section.tsx, online-booking/client-online-booking.tsx):
//  the link to the family on PawMiaw with "Puede reservar en línea", the
//  visits, the account (money level 1 or more) and the home & contact.
//

/** One optional read (Swift `try?`): null when it fails; a cancel still cancels. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    null
}

// MARK: - The family on PawMiaw

/**
 * "Familia vinculada" / "Invitación pendiente" (web `ClientLinkStatus`): who is
 * linked, "Puede reservar en línea", the open invitation's link and email,
 * unlinking a pet. The name the family uses on PawMiaw, when it differs,
 * opens the card with "Usar este nombre" (the web shows it under the
 * header's title, which takes text only here).
 */
@Composable
fun ClientsLinkCard(
    page: ClientsClientPage,
    client: ClientsClient,
    canManage: Boolean,
    renaming: Boolean = false,
    /** "Usar este nombre": the family's own name from PawMiaw becomes the client's name. */
    onUseLinkedName: ((String) -> Unit)? = null,
    onOpenInvitations: () -> Unit,
    /** "Desvincular a {mascota}": the unlink sheet for one linked pet. */
    onUnlink: ((ClientsPet) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var savingBooking by remember { mutableStateOf<Boolean?>(null) }
    var resending by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }

    /** The family calls themselves something else in PawMiaw ("José" here, "Alejandro Pérez" there). */
    val linkedName: String? = run {
        val name = client.linkedAccountName.nonEmpty
        if (!client.isLinked || name == null) return@run null
        val fold = { text: String -> Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim() }
        if (fold(name) == fold(client.ownerName)) null else name
    }

    suspend fun reload() {
        attempt { API.get<Page<ClientsInvitation>>("/provider-client-invitations", mapOf("limit" to "10", "offset" to "0", "providerClientId" to client.id)) }
            ?.let { page.invitations = it.items }
        announceDataChanged()
    }

    fun resend(invitation: ClientsInvitation) {
        resending = true
        scope.launch {
            try {
                API.post<ClientsInvitation>("/provider-client-invitations/${invitation.id}/resend")
                // "Again" only when it had gone out before.
                Toast.show(if (invitation.emailStatus == "sent") L("Invitation email sent again.") else L("Invitation email sent."))
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure, fallback = L("Could not resend the invitation."))
            } finally {
                resending = false
            }
        }
    }

    fun setOnlineBooking(allowed: Boolean) {
        savingBooking = allowed
        scope.launch {
            try {
                val answer: ClientsOnlineBookingAnswer = API.put("/provider-clients/${client.id}/online-booking", mapOf("allowed" to allowed))
                answer.client?.let { page.client = it }
                Toast.show(if (allowed) LF("%@ can book online now.", client.ownerName) else LF("%@ can’t book online anymore.", client.ownerName))
                announceDataChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure)
            } finally {
                savingBooking = null
            }
        }
    }

    val pending = page.pendingInvitation
    if (client.isLinked || pending != null) {
        Card(spacing = 12.dp, modifier = modifier) {
            if (linkedName != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val soft = Theme.inkSoft
                    val strong = Theme.inkStrong
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = soft)) { append("${L("On PawMiaw:")} ") }
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append(linkedName) }
                        },
                        Modifier.weight(1f, fill = false),
                        style = Theme.text(14),
                    )
                    if (canManage && onUseLinkedName != null) {
                        PMButton(L("Use this name"), variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = renaming) { onUseLinkedName(linkedName) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (client.isLinked) L("Family linked") else L("Invitation pending"), style = Theme.heading(18), color = Theme.inkStrong)
                    if (client.isLinked) {
                        val linkedPets = client.allPets.filter { it.isActive && it.isLinked }.joinToString(", ") { it.petName }
                        Text(LF("Linked pets: %@", linkedPets.nonEmpty ?: "—"), style = Theme.text(14), color = Theme.inkSoft)
                    } else if (pending != null) {
                        val sent = if (pending.emailStatus == "sent") LF("Invitation sent to %@", pending.recipientEmail ?: "—") else LF("Invitation link created for %@", pending.recipientEmail ?: "—")
                        Text("$sent · ${LF("Expires %@", ClientsText.dayTime(pending.expiresAt))}", style = Theme.text(14), color = Theme.inkSoft)
                    }
                }
                Spacer(Modifier.width(8.dp))
                PMButton(L("Invitation history"), variant = ButtonVariant.outline, size = ButtonSize.compact, action = onOpenInvitations)
            }
            // "Puede reservar en línea": a family that connected by itself books only once you allow it (docs/online-booking.md §1).
            if (client.isLinked && !client.isArchived) {
                val allowed = savingBooking ?: client.canBookOnline ?: (client.businessLinkedAt != null)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Disabled(!canManage || savingBooking != null) {
                        ToggleRow(
                            title = L("Can book online"),
                            subtitle = if (allowed) L("They book visits with you from the app, inside your hours.") else L("A family that connected by itself can’t book until you allow it."),
                            isOn = stateOf({ allowed }, { setOnlineBooking(it) }),
                        )
                    }
                    if (!canManage) {
                        Text(L("Only someone who edits clients changes this."), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
            }
            if (client.isLinked && canManage && onUnlink != null) {
                val linked = client.allPets.filter { it.isActive && it.isLinked }
                if (linked.isNotEmpty()) {
                    FlowLayout(spacing = 8.dp) {
                        linked.forEach { pet ->
                            PMButton(LF("Unlink %@", pet.petName), systemImage = "link-2-off", variant = ButtonVariant.outline, size = ButtonSize.compact) { onUnlink(pet) }
                        }
                    }
                }
            }
            if (pending != null && canManage) {
                FlowLayout(spacing = 8.dp) {
                    PMButton(L("Copy invitation link"), systemImage = "copy", variant = ButtonVariant.outline, size = ButtonSize.compact) { clientsCopy(pending.url, done = L("Invitation link copied.")) }
                    pending.url.nonEmpty?.let { url ->
                        ShareLink(item = url) {
                            PMButtonStyle(ButtonVariant.outline, ButtonSize.compact) { PMButtonContent(L("Share link"), icon = "share", variant = ButtonVariant.outline, size = ButtonSize.compact) }
                        }
                    }
                    PMButton(
                        if (pending.emailStatus == "sent") L("Resend email") else L("Send by email"),
                        systemImage = if (pending.emailStatus == "sent") "rotate-cw" else "mail-plus",
                        variant = ButtonVariant.outline,
                        size = ButtonSize.compact,
                        isWorking = resending,
                    ) { resend(pending) }
                    PMButton(L("Revoke invitation"), variant = ButtonVariant.outline, size = ButtonSize.compact) { confirmRevoke = true }
                }
            }
        }
        if (confirmRevoke && pending != null) {
            AppSheet(onDismissRequest = { confirmRevoke = false }) {
                ConfirmSheet(
                    title = L("Revoke invitation"),
                    message = L("The link stops working. The family can be invited again."),
                    confirmTitle = L("Confirm revoke"),
                    destructive = true,
                ) { _ ->
                    API.delete<ClientsInvitation>("/provider-client-invitations/${pending.id}")
                    Toast.show(L("Invitation revoked."))
                    reload()
                }
            }
        }
    }
}

// MARK: - A section card's band and figures

/** A band across a section card (web: the grey sub-heading strip, "Próximas", "Últimas cuentas"): 14 bold on the muted surface. */
@Composable
private fun ClientsCardBand(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme.surfaceMuted)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            trailing()
        }
        RowDivider()
    }
}

/**
 * A section card's header actions on their own row: beside the title on an
 * iPad, under it on a phone (the web's header row wraps).
 */
@Composable
private fun ClientsSectionActions(actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
        RowDivider()
    }
}

// MARK: - Visits

/**
 * The client's visits across all their pets (web `ClientVisits`): what is
 * coming and the last few that happened. Each row opens its visit.
 */
@Composable
fun ClientsVisitsSection(page: ClientsClientPage, clientName: String = "", onBook: (() -> Unit)?, modifier: Modifier = Modifier) {
    val empty = page.visitsLoaded && !page.visitsFailed && page.upcoming.isEmpty() && page.earlier.isEmpty()
    SectionCard(
        title = L("Visits"),
        subtitle = L("Upcoming and recent visits of all their pets."),
        icon = "calendar-days",
        accent = Theme.aqua,
        modifier = modifier,
        trailing = {
            if (!empty && onBook != null) PMButton(L("Book"), systemImage = "calendar-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onBook)
        },
    ) {
        when {
            !page.visitsLoaded -> LoadingState()
            page.visitsFailed -> Text(L("Could not load the visits."), Modifier.padding(16.dp), style = Theme.text(14), color = Theme.inkSoft)
            empty -> EmptyState(
                systemImage = "calendar-days",
                title = L("No visits yet"),
                message = if (onBook != null) L("Book the first visit and it will show up here.") else null,
                actionTitle = if (onBook != null) L("Book a visit") else null,
                action = onBook,
                accent = Theme.mango,
            )
            else -> {
                ClientsVisitGroup(L("Upcoming"), page.upcoming, empty = L("Nothing booked ahead."), last = page.earlier.isEmpty())
                if (page.earlier.isNotEmpty()) ClientsVisitGroup(L("Earlier"), page.earlier, empty = null, last = true)
            }
        }
    }
}

@Composable
private fun ClientsVisitGroup(title: String, visits: List<ClientsAppointment>, empty: String?, last: Boolean) {
    val nav = LocalNavigator.current
    ClientsCardBand(title = title)
    if (visits.isEmpty() && empty != null) {
        Text(empty, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = Theme.text(14), color = Theme.inkSoft)
        if (!last) RowDivider()
    }
    visits.forEach { visit ->
        key(visit.id) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton { nav.push(ProviderRoute.appointment(visit.id)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(64.dp)) {
                    Text(ClientsText.relativeShortDay(visit.startsAt), style = Theme.heading(15.2), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(DateText.time(visit.startsAt), style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft)
                }
                PetAvatar(name = visit.petName, species = visit.petSpecies, photoURL = visit.petPhotoUrl, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(visit.petName ?: "—", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${visit.serviceLabel ?: "—"} · ${visit.assignedMember?.name.nonEmpty ?: L("Nobody assigned")}",
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // On a phone the pills go under the visit.
                    FlowLayout(spacing = 4.dp, modifier = Modifier.padding(top = 4.dp)) {
                        StatusChip(text = ClientsText.visitStatus(visit.status), state = ProviderState.appointment(visit.status))
                        if (visit.onlineBooking == true) Chip(text = L("Online"), tone = Chip.Tone.online, icon = "globe")
                    }
                }
                Spacer(Modifier.width(8.dp))
                Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
            }
            if (!last || visit.id != visits.lastOrNull()?.id) RowDivider()
        }
    }
}

// MARK: - Account

/**
 * The client's money in one place (web `ClientAccountSection`): what they
 * owe, what is late, their credit and their latest bills. "Cobrar" only when
 * something is owed.
 */
@Composable
fun ClientsAccountSection(page: ClientsClientPage, client: ClientsClient, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val push = LocalClientsPush.current
    val scope = rememberCoroutineScope()
    val wide = LocalSizeClass.current == SizeClass.regular
    var collecting by remember { mutableStateOf<CashAccount?>(null) }
    var openingAccount by remember { mutableStateOf(false) }

    val account = page.account
    val money = { cents: Int -> Money.format(cents, currency = account?.currency) }
    val owed = account?.owed ?: 0
    val overdue = account?.overdue ?: 0
    val credit = account?.credit ?: 0
    val loading = account == null && !page.billsLoaded

    fun collect() {
        openingAccount = true
        scope.launch {
            try {
                collecting = API.get<CashAccount>("/provider-finance/clients/${client.id}/account")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure, fallback = L("This account could not be loaded."))
            } finally {
                openingAccount = false
            }
        }
    }

    suspend fun reload() {
        page.account = attempt { API.get<ClientsAccount>("/provider-finance/clients/${client.id}/account") }
        announceDataChanged()
    }

    @Composable
    fun actions() {
        if (owed > 0 && workspace.can(Permission.salesSell)) {
            // The Store's counter collects the client's open bills.
            PMButtonStyle(ButtonVariant.primary, ButtonSize.compact, onClick = { nav.push(ProviderRoute.storeCheckout(StorePrefill(clientId = client.id))) }) {
                PMButtonContent(L("Collect"), icon = "hand-coins", size = ButtonSize.compact)
            }
        } else if (owed > 0 && workspace.can(Permission.billingRecordPayment)) {
            // Without the Store: one payment, applied to the oldest debt first (the account's own sheet).
            PMButton(L("Collect"), systemImage = "hand-coins", size = ButtonSize.compact, isWorking = openingAccount) { collect() }
        }
        PMButtonStyle(ButtonVariant.outline, ButtonSize.compact, onClick = { push?.invoke(ClientsPush.account(client.id)) }) {
            PMButtonContent(L("See account"), variant = ButtonVariant.outline, size = ButtonSize.compact)
        }
    }

    SectionCard(
        title = L("Account"),
        subtitle = L("What they owe, their credit and their latest bills."),
        icon = "badge-dollar-sign",
        accent = Theme.aqua,
        modifier = modifier,
        trailing = { if (wide) actions() },
    ) {
        if (!wide) ClientsSectionActions { actions() }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ClientsAccountFigure(L("Owes"), loading = loading, tone = if (owed > 0) Theme.pink else null) {
                if (owed > 0) ClientsAccountAmount(money(owed)) else ClientsAccountQuiet(L("Nothing owed"))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.borderSoft))
            ClientsAccountFigure(L("Overdue"), loading = loading, tone = if (overdue > 0) Theme.destructive else null) {
                if (overdue > 0) ClientsAccountAmount(money(overdue), danger = true) else ClientsAccountQuiet(L("Nothing overdue"))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.borderSoft))
            ClientsAccountFigure(L("Credit"), loading = loading, tone = if (credit > 0) Theme.lime else null) {
                if (credit > 0) ClientsAccountAmount(money(credit)) else ClientsAccountQuiet(L("No credit"))
            }
        }
        RowDivider()
        ClientsCardBand(title = L("Latest bills")) {
            if (page.billsTotal > page.bills.size && workspace.moneyLevel >= MoneyLevel.frontDesk) {
                Text(
                    L("See all bills"),
                    Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.invoices)) },
                    style = Theme.text(14, FontWeight.Bold),
                    color = Theme.blue,
                )
            }
        }
        if (!page.billsLoaded) {
            LoadingState()
        } else if (page.bills.isEmpty()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("receipt-text", 16.dp, tint = Theme.inkSoft)
                Text(L("No bills yet"), style = Theme.text(14), color = Theme.inkSoft)
            }
        } else {
            page.bills.forEachIndexed { index, invoice ->
                key(invoice.id) {
                    if (index > 0) RowDivider()
                    ClientsBillRow(invoice = invoice, modifier = Modifier.plainButton { nav.push(ProviderRoute.invoice(invoice.id)) })
                }
            }
        }
    }
    collecting?.let { target ->
        AppSheet(onDismissRequest = { collecting = null }) {
            CashCollectSheet(account = target, onDone = { scope.launch { reload() } })
        }
    }
}

/**
 * One of the three figures (web `Figure`): its uppercase label and the amount in
 * Fredoka, washed in its colour when there is something; quiet words when not.
 */
@Composable
private fun RowScope.ClientsAccountFigure(label: String, loading: Boolean, tone: Color?, value: @Composable () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(tone?.let { Theme.wash(it, 9.0) } ?: Theme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.06), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
        } else {
            value()
        }
    }
}

@Composable
private fun ClientsAccountAmount(text: String, danger: Boolean = false) {
    Text(text, style = Theme.heading(18), color = if (danger) Theme.destructive else Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ClientsAccountQuiet(text: String) {
    Text(text, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
}

/**
 * One bill (web `BillRow`): its number and state, the day and pet, the total
 * in Fredoka and what is left to pay.
 */
@Composable
fun ClientsBillRow(invoice: ClientsInvoice, modifier: Modifier = Modifier) {
    val pending = (invoice.balanceDueAmount ?: 0) > 0 && (invoice.status ?: "") !in listOf("paid", "canceled", "draft")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowLayout(spacing = 8.dp) {
                Text(invoice.invoiceNumber ?: "—", style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusChip(text = ClientsText.invoiceStatus(invoice.status), state = ProviderState.invoice(invoice.status))
            }
            Text(
                listOfNotNull(ClientsText.shortDay(invoice.issuedAt ?: invoice.createdAt), invoice.petName).joinToString(" · "),
                style = Theme.text(12),
                color = Theme.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Money.format(invoice.totalAmount, currency = invoice.currency), style = Theme.heading(15.2).monospacedDigit(), color = Theme.inkStrong)
            if (pending && invoice.balanceDueAmount != invoice.totalAmount) {
                Text(LF("%@ to pay", Money.format(invoice.balanceDueAmount, currency = invoice.currency)), style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft)
            }
        }
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

// MARK: - Home & contact

/** SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)])` over a few cells. */
@Composable
private fun ClientsHomeGrid(minimum: Dp, spacing: Dp, cells: List<@Composable () -> Unit>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            cells.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    row.forEach { cell -> Box(Modifier.weight(1f)) { cell() } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A client's home on their page (web `ClientHomeSection`): the addresses home
 * visits go to (the family's, read-only and tagged, and the business's own),
 * which access details each has, and the emergency contacts.
 */
@Composable
fun ClientsHomeSection(page: ClientsClientPage, modifier: Modifier = Modifier) {
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ClientsAddressEdit?>(null) }
    var removing by remember { mutableStateOf<ClientsAddress?>(null) }
    var editingContact by remember { mutableStateOf(false) }
    var revealing by remember { mutableStateOf<ClientsVaultRevealTarget?>(null) }
    var writingVault by remember { mutableStateOf<ClientsVaultRevealTarget?>(null) }
    /** Opened access details, by address: on screen only, never kept. */
    var revealed by remember { mutableStateOf<Map<String, ClientsRevealedVault>>(emptyMap()) }

    val home = page.home
    val addresses = (home?.addresses ?: emptyList()).filter { it.status != "archived" }
        .sortedWith(compareBy<ClientsAddress>({ if (it.isDefault == true) 0 else 1 }, { it.name }))
    val canEdit = home?.canEdit ?: false

    suspend fun reload() {
        attempt { API.get<ClientsHome>("/provider-clients/${page.clientId}/home") }?.let { answer ->
            page.home = answer
            page.homeFailed = false
        }
    }

    fun makeDefault(address: ClientsAddress) {
        scope.launch {
            try {
                API.patch<ClientsAddress>("/provider-clients/${page.clientId}/addresses/${address.id}", ClientsAddressWords.body(address, isDefault = true))
                Toast.show(LF("%@ is the default address now.", address.name))
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure)
            }
        }
    }

    SectionCard(
        title = L("Home & contact"),
        subtitle = L("Where home visits go, how to get in, and who to call."),
        icon = "map-pin",
        accent = Theme.aqua,
        modifier = modifier,
        trailing = {
            if (canEdit && addresses.isNotEmpty() && addresses.count { it.isBusiness } < 20) {
                PMButton(L("Add address"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.compact) { editing = ClientsAddressEdit(address = null, first = false) }
            }
        },
    ) {
        if (page.homeFailed && home == null) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("Couldn’t load the addresses."), Modifier.weight(1f), style = Theme.text(14), color = Theme.inkSoft)
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { reload() } }
            }
        } else if (home != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClientsHomeGrid(
                    minimum = 300.dp,
                    spacing = 12.dp,
                    cells = listOf(
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (addresses.isEmpty()) {
                                    ClientsHomeTile(
                                        icon = "map-pin",
                                        tone = Theme.lime,
                                        empty = true,
                                        action = {
                                            if (canEdit) PMButton(L("Add"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.compact) { editing = ClientsAddressEdit(address = null, first = true) }
                                        },
                                        content = {
                                            ClientsTileLabel(L("Address"))
                                            Text(L("No address yet"), style = Theme.text(14), color = Theme.inkSoft)
                                            if (home.linked == true) {
                                                Text(L("Add one here, or the family can add theirs in PawMiaw — it shows here as soon as they do."), style = Theme.text(12), color = Theme.inkSoft)
                                            }
                                        },
                                    )
                                }
                                addresses.forEach { address ->
                                    key(address.id) {
                                        ClientsAddressTile(
                                            address = address,
                                            home = home,
                                            canEdit = canEdit,
                                            clientId = page.clientId,
                                            revealed = revealed[address.id],
                                            onEdit = { editing = ClientsAddressEdit(address = address, first = false) },
                                            onMakeDefault = { makeDefault(address) },
                                            onRemove = { removing = address },
                                            onOpenMaps = { url -> openURL(url) },
                                            onReveal = { revealing = ClientsVaultRevealTarget(address = address) },
                                            onEditVault = { writingVault = ClientsVaultRevealTarget(address = address) },
                                            onHide = { revealed = revealed - address.id },
                                        )
                                    }
                                }
                                if (home.linked == true && addresses.any { it.source == "family" }) {
                                    Text(L("Addresses from the family are theirs: they change them in PawMiaw, and this page follows."), style = Theme.text(12), color = Theme.inkSoft)
                                }
                            }
                        },
                        { ClientsEmergencyTile(home, canEdit = canEdit, onEdit = { editingContact = true }, openURL = openURL) },
                    ),
                )
            }
            // Only where there is something to open; "the family can see who" only when the family is on PawMiaw.
            if (home.canManageVault == true && addresses.any { it.vault?.hasVault == true }) {
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Glyph("shield-check", 14.dp, tint = Theme.blue, modifier = Modifier.padding(top = 1.dp))
                    Text(
                        if (home.linked == true) L("Opening these is recorded and the family is told each time.") else L("Opening these is recorded."),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                }
            }
        } else {
            LoadingState()
        }
    }
    editing?.let { edit ->
        AppSheet(onDismissRequest = { editing = null }) {
            ClientsAddressSheet(clientId = page.clientId, address = edit.address, makeDefault = edit.first) { reload() }
        }
    }
    removing?.let { address ->
        AppSheet(onDismissRequest = { removing = null }) {
            ClientsRemoveAddressSheet(address = address) {
                val result: ClientsArchivedAddress = API.delete("/provider-clients/${page.clientId}/addresses/${address.id}")
                revealed = revealed - address.id
                Toast.show("${L("Address removed.")} ${ClientsAddressWords.upcoming(result.upcomingVisits ?: 0)}")
                reload()
            }
        }
    }
    if (editingContact) {
        AppSheet(onDismissRequest = { editingContact = false }) {
            ClientsEmergencySheet(clientId = page.clientId, contact = home?.emergencyContact) { reload() }
        }
    }
    revealing?.let { target ->
        AppSheet(onDismissRequest = { revealing = null }) {
            ClientsVaultRevealSheet(clientId = page.clientId, address = target.address, onRevealed = { vault -> revealed = revealed + (target.address.id to vault) })
        }
    }
    writingVault?.let { target ->
        AppSheet(onDismissRequest = { writingVault = null }) {
            ClientsVaultEditorSheet(clientId = page.clientId, address = target.address, onSaved = {
                revealed = revealed - target.address.id
                reload()
            })
        }
    }
}

/**
 * One address (web: the address tile): its name with Default / From the
 * family, the full line, the reference, Maps, the ⋯ for the business's
 * own; under a dashed rule, which access details it has.
 */
@Composable
private fun ClientsAddressTile(
    address: ClientsAddress,
    home: ClientsHome,
    canEdit: Boolean,
    clientId: String,
    revealed: ClientsRevealedVault?,
    onEdit: () -> Unit,
    onMakeDefault: () -> Unit,
    onRemove: () -> Unit,
    onOpenMaps: (String) -> Unit,
    onReveal: () -> Unit,
    onEditVault: () -> Unit,
    onHide: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowLayout(spacing = 8.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("map-pin", 16.dp, tint = Theme.blue)
                        Text(address.label.nonEmpty ?: L("Address"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (address.isDefault == true) Chip(text = L("Default"), tone = Chip.Tone.good, icon = "star")
                    if (address.source == "family") Chip(text = L("From the family"), tone = Chip.Tone.online, icon = "heart-handshake")
                }
                Text(address.formatted ?: address.line1 ?: "—", style = Theme.text(14), color = Theme.inkStrong)
                address.reference.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
                address.mapsUrl.nonEmpty?.let { maps ->
                    Row(
                        Modifier.padding(top = 4.dp).plainButton { onOpenMaps(maps) },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("navigation", 14.dp, tint = Theme.blue)
                        Text(L("Open in Maps"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
                    }
                }
            }
            if (canEdit && address.isBusiness) {
                AppMenu(label = { IconCircleLabel() }, accessibilityLabel = LF("More for %@", address.name)) {
                    item(L("Edit address"), icon = "pencil") { onEdit() }
                    if (address.isDefault != true) {
                        item(L("Make default"), icon = "star") { onMakeDefault() }
                    }
                    divider()
                    item(L("Remove address"), icon = "archive", destructive = true) { onRemove() }
                }
            }
        }
        StitchedDivider()
        ClientsVaultStrip(clientId = clientId, address = address, home = home, revealed = revealed, onReveal = onReveal, onEdit = onEditVault, onHide = onHide)
    }
}

/** Who to call (web: the emergency tile): the business's contact, and the family's under a dashed rule. */
@Composable
private fun ClientsEmergencyTile(home: ClientsHome, canEdit: Boolean, onEdit: () -> Unit, openURL: (String) -> Unit) {
    val has = home.emergencyContact?.let { !it.isEmpty } ?: false
    val familyHas = home.linked == true && (home.familyEmergencyContact?.let { !it.isEmpty } ?: false)
    val action: @Composable () -> Unit = {
        if (canEdit) PMButton(if (has) L("Edit") else L("Add"), systemImage = if (has) "pencil" else "plus", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onEdit)
    }
    if (home.linked == true) {
        ClientsHomeTile(
            icon = "phone-call",
            tone = Theme.blue,
            empty = !has && !familyHas,
            action = action,
            content = { ClientsContactView(L("Emergency contact"), home.emergencyContact, empty = L("None yet"), family = false, openURL = openURL) },
            below = { ClientsContactView(L("The family’s emergency contact"), home.familyEmergencyContact, empty = L("The family hasn’t added one"), family = true, openURL = openURL) },
        )
    } else {
        ClientsHomeTile(
            icon = "phone-call",
            tone = Theme.blue,
            empty = !has,
            action = action,
            content = { ClientsContactView(L("Emergency contact"), home.emergencyContact, empty = L("None yet"), family = false, openURL = openURL) },
        )
    }
}

@Composable
private fun ClientsContactView(title: String, contact: ClientsEmergencyContact?, empty: String, family: Boolean, openURL: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ClientsTileLabel(title)
            if (family) Chip(text = L("From the family"), tone = Chip.Tone.online, icon = "heart-handshake")
        }
        if (contact != null && !contact.isEmpty) {
            FlowLayout(spacing = 8.dp) {
                contact.name.nonEmpty?.let { Text(it, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong) }
                contact.relationship.nonEmpty?.let { Text("· $it", style = Theme.text(14), color = Theme.inkSoft) }
                val phone = contact.phone.nonEmpty
                val tel = ContactLinks.phone(phone)
                if (phone != null && tel != null) {
                    PMButton(phone, systemImage = "phone", variant = ButtonVariant.outline, size = ButtonSize.compact) { openURL(tel) }
                }
            }
        } else {
            Text(empty, style = Theme.text(14), color = Theme.inkSoft)
        }
    }
}

/**
 * One half of the home card (web `HomeTile`): a 36 disc of its colour at 30%,
 * what it holds, its one action top right; dashed while it is empty; an
 * optional part under a dashed rule.
 */
@Composable
private fun ClientsHomeTile(
    icon: String,
    tone: Color,
    empty: Boolean = false,
    action: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    below: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .then(
                if (empty) Modifier.dashedBorder(1.dp, Theme.mix(Theme.blue, 30.0, Theme.borderSoft), Theme.boxRadius, 5.dp, 4.dp)
                else Modifier.border(1.dp, Theme.borderSoft, shape)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            IconBadge(icon = icon, accent = tone, size = 36.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 30.0)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            action()
        }
        if (below != null) {
            Column {
                StitchedDivider(modifier = Modifier.padding(bottom = 12.dp))
                Box(Modifier.padding(start = 48.dp)) { below() }
            }
        }
    }
}

/** A tile's uppercase label (web `TILE_LABEL`): 10.6 extrabold, tracked, grey. */
@Composable
private fun ClientsTileLabel(text: String) {
    Text(text.uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.06), color = Theme.inkSoft)
}

/** An address sheet to open: the address (null for a new one) and whether it is the first. */
data class ClientsAddressEdit(val address: ClientsAddress?, val first: Boolean)

@Serializable
data class ClientsArchivedAddress(val upcomingVisits: Int? = null)

object ClientsAddressWords {
    fun vaultField(field: String): String = when (field) {
        "keys" -> L("Keys")
        "gateCode" -> L("Gate code")
        "alarmCode" -> L("Alarm code")
        "wifiName" -> L("Wi-Fi network")
        "wifiPassword" -> L("Wi-Fi password")
        "parking" -> L("Parking")
        "houseRules" -> L("House rules")
        "hidingSpots" -> L("Where the pets hide")
        else -> L("Anything else")
    }

    fun upcoming(count: Int): String {
        if (count <= 0) return L("No upcoming visits are booked there.")
        return if (count == 1) L("1 upcoming visit is booked there. It keeps the address as it was booked.") else LF("%d upcoming visits are booked there. They keep the address as they were booked.", count)
    }

    fun body(address: ClientsAddress, isDefault: Boolean): Map<String, Any?> = mapOf(
        "label" to address.label, "line1" to (address.line1 ?: ""), "line2" to address.line2, "city" to address.city, "state" to address.state,
        "postalCode" to address.postalCode, "country" to address.country, "reference" to address.reference,
        "latitude" to address.latitude, "longitude" to address.longitude, "isDefault" to isDefault,
    )
}

/** The business's own address for a client (the family's come from PawMiaw). */
@Composable
fun ClientsAddressSheet(
    clientId: String,
    address: ClientsAddress?,
    makeDefault: Boolean,
    modifier: Modifier = Modifier,
    onSaved: suspend () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val label = remember { mutableStateOf(address?.label ?: "") }
    val line1 = remember { mutableStateOf(address?.line1 ?: "") }
    val line2 = remember { mutableStateOf(address?.line2 ?: "") }
    val city = remember { mutableStateOf(address?.city ?: "") }
    val state = remember { mutableStateOf(address?.state ?: "") }
    val postalCode = remember { mutableStateOf(address?.postalCode ?: "") }
    val country = remember { mutableStateOf(address?.country ?: (Workspace.shared.current?.country ?: "MX")) }
    val reference = remember { mutableStateOf(address?.reference ?: "") }
    val isDefault = remember { mutableStateOf(address?.isDefault ?: makeDefault) }
    var saving by remember { mutableStateOf(false) }

    val dirty = label.value != (address?.label ?: "") || line1.value != (address?.line1 ?: "") || line2.value != (address?.line2 ?: "") || city.value != (address?.city ?: "") ||
        state.value != (address?.state ?: "") || postalCode.value != (address?.postalCode ?: "") || reference.value != (address?.reference ?: "")

    fun save() {
        val code = country.value.trim()
        if (!form.check(listOf(
                "line1" to (if (line1.value.nonEmpty == null) L("Add the street and number.") else null),
                "country" to (if (code.isNotEmpty() && code.length != 2) L("Use the two-letter country code (MX, US…).") else null),
            ))) return
        saving = true
        scope.launch {
            val body: Map<String, Any?> = mapOf(
                "label" to label.value.nonEmpty, "line1" to line1.value.trim(), "line2" to line2.value.nonEmpty,
                "city" to city.value.nonEmpty, "state" to state.value.nonEmpty, "postalCode" to postalCode.value.nonEmpty, "country" to code.nonEmpty?.uppercase(),
                "reference" to reference.value.nonEmpty, "latitude" to address?.latitude, "longitude" to address?.longitude, "isDefault" to isDefault.value,
            )
            try {
                if (address != null) {
                    API.patch<ClientsAddress>("/provider-clients/$clientId/addresses/${address.id}", body)
                    Toast.show(L("Address saved."))
                } else {
                    API.post<ClientsAddress>("/provider-clients/$clientId/addresses", body)
                    Toast.show(L("Address added."))
                }
                onSaved()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                form.apply(failure, known = mapOf("label" to "label", "line1" to "line1", "line2" to "line2", "city" to "city", "state" to "state", "postalCode" to "postalCode", "country" to "country", "reference" to "reference"))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = if (address == null) L("Add address") else L("Edit address"),
        subtitle = L("The business’s own address for this client. The family’s come from PawMiaw."),
        systemImage = "map-pin",
        accent = ModalAccent.aqua,
        isDirty = dirty,
        form = form,
        primaryTitle = if (address == null) L("Add address") else L("Save address"),
        isWorking = saving,
        primaryIcon = "save",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        ModalSection(title = L("Address"), systemImage = "map-pin") {
            PMTextField(L("Name"), text = label, key = "label", form = form, prompt = L("Home, Office, Grandma’s…"), capitalization = KeyboardCapitalization.Words)
            PMTextField(L("Street and number"), text = line1, key = "line1", form = form, prompt = L("Av. Juárez 120"), capitalization = KeyboardCapitalization.Words, required = true)
            PMTextField(L("Apartment, neighbourhood"), text = line2, key = "line2", form = form, prompt = L("Int. 4, Col. Centro"), capitalization = KeyboardCapitalization.Words)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                PMTextField(L("City"), text = city, key = "city", form = form, capitalization = KeyboardCapitalization.Words, modifier = Modifier.weight(1f))
                PMTextField(L("State"), text = state, key = "state", form = form, capitalization = KeyboardCapitalization.Words, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                PMTextField(L("Postal code"), text = postalCode, key = "postalCode", form = form, keyboard = KeyboardType.Ascii, modifier = Modifier.weight(1f))
                PMTextField(L("Country"), text = country, key = "country", form = form, prompt = "MX", capitalization = KeyboardCapitalization.Characters, modifier = Modifier.weight(1f))
            }
            PMTextField(L("How to recognise it"), text = reference, key = "reference", form = form, prompt = L("Green gate next to the pharmacy, ring twice"), multiline = true)
        }
        // The default one stays the default until another is made it.
        Disabled(address?.isDefault == true) {
            ToggleRow(title = L("Default address"), subtitle = L("Home visits start from it."), isOn = isDefault)
        }
        // Not ported yet (iOS neither): placing the spot (a maps link or the current location); an edited address keeps its spot.
    }
}

/** Who to call if something happens and the client can't be reached. */
@Composable
fun ClientsEmergencySheet(
    clientId: String,
    contact: ClientsEmergencyContact?,
    modifier: Modifier = Modifier,
    onSaved: suspend () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val name = remember { mutableStateOf(contact?.name ?: "") }
    val phone = remember { mutableStateOf(contact?.phone ?: "") }
    val relationship = remember { mutableStateOf(contact?.relationship ?: "") }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        val hadOne = !(contact?.isEmpty ?: true)
        if (!form.check(listOf(
                "name" to (if (!hadOne && name.value.nonEmpty == null && phone.value.nonEmpty == null) L("Write who to call: a name or a phone.") else null),
                "phone" to (if (ClientsText.phoneLooksValid(phone.value)) null else L("That phone number doesn’t look complete.")),
            ))) return
        saving = true
        scope.launch {
            try {
                API.put<EmptyResponse>(
                    "/provider-clients/$clientId/emergency-contact",
                    mapOf("name" to name.value.nonEmpty, "phone" to phone.value.nonEmpty, "relationship" to relationship.value.nonEmpty),
                )
                Toast.show(if (name.value.nonEmpty != null || phone.value.nonEmpty != null) L("Emergency contact saved.") else L("Emergency contact removed."))
                onSaved()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                form.apply(failure, known = mapOf("name" to "name", "phone" to "phone", "relationship" to "relationship"))
            } finally {
                saving = false
            }
        }
    }

    fun remove() {
        name.value = ""
        phone.value = ""
        relationship.value = ""
        form.errors = emptyMap()
        save()
    }

    ProviderModal(
        title = L("Emergency contact"),
        subtitle = L("Who to call if something happens and the client can’t be reached."),
        systemImage = "phone-call",
        accent = ModalAccent.aqua,
        isDirty = name.value != (contact?.name ?: "") || phone.value != (contact?.phone ?: "") || relationship.value != (contact?.relationship ?: ""),
        form = form,
        primaryTitle = L("Save"),
        isWorking = saving,
        // Web: "Quitar" in the footer once there is one to remove.
        secondaryActions = if (!(contact?.isEmpty ?: true)) listOf(ModalAction(L("Remove"), variant = ButtonVariant.ghost, tint = Theme.destructive) { remove() }) else emptyList(),
        primaryIcon = "save",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        PMTextField(L("Name"), text = name, key = "name", form = form, capitalization = KeyboardCapitalization.Words)
        PMTextField(L("Phone"), text = phone, key = "phone", form = form, keyboard = KeyboardType.Phone)
        PMTextField(L("Relationship"), text = relationship, key = "relationship", form = form, prompt = L("Sister, neighbour, friend…"))
    }
}

/**
 * "¿Quitar esta dirección?" (web: the coral sheet with the address under the title): it leaves the
 * list, its access details are erased, visits booked there keep it.
 */
@Composable
private fun ClientsRemoveAddressSheet(address: ClientsAddress, remove: suspend () -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    ProviderModal(
        title = L("Remove this address?"),
        subtitle = address.formatted ?: address.name,
        systemImage = "archive",
        accent = ModalAccent.coral,
        primaryTitle = L("Remove address"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Keep it"),
        primaryIcon = "archive",
        onPrimary = {
            working = true
            scope.launch {
                try {
                    remove()
                    dismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Toast.error(failure)
                } finally {
                    working = false
                }
            }
        },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Text(
            L("It leaves the list and can’t be chosen for new visits. Its access details are erased. Visits already booked there keep the address they were booked with."),
            style = Theme.text(14),
            color = Theme.inkStrong,
        )
    }
}
