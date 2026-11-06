package com.pawmiaw.business.clients

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.money.CashAccountScreen
import com.pawmiaw.business.services.SvcCareCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

//
//  ClientDetailScreen.kt (iOS ClientDetailScreen.swift)
//
//  A client's page (web: routes/provider.clients.$clientId.tsx): who they
//  are and how to reach them, the numbers that matter, the link to their
//  family on PawMiaw, their pets (each opens its chart), their visits, their
//  account (by money level), where they live and who to call. Delete means
//  archive: the client is archived with its pets and can be restored.
//

/** What a client's page holds (iOS an `ObservableObject`), read by its sections. */
@Stable
class ClientsClientPage(val clientId: String) {
    var client by mutableStateOf<ClientsClient?>(null)
    var error by mutableStateOf<String?>(null)
    var notFound by mutableStateOf(false)
    var petActivity by mutableStateOf<Map<String, ClientsPetActivity>>(emptyMap())
    var activityKnown by mutableStateOf(false)
    var account by mutableStateOf<ClientsAccount?>(null)
    var invitations by mutableStateOf<List<ClientsInvitation>>(emptyList())
    var upcoming by mutableStateOf<List<ClientsAppointment>>(emptyList())
    var earlier by mutableStateOf<List<ClientsAppointment>>(emptyList())
    var visitsLoaded by mutableStateOf(false)
    var visitsFailed by mutableStateOf(false)
    var bills by mutableStateOf<List<ClientsInvoice>>(emptyList())
    var billsTotal by mutableIntStateOf(0)
    var billsLoaded by mutableStateOf(false)
    var home by mutableStateOf<ClientsHome?>(null)
    var homeFailed by mutableStateOf(false)
    /** The open match suggestions that touch this client or one of its pets. */
    var suggestions by mutableStateOf<List<ClientsMatchSuggestion>>(emptyList())
    /** The client's stays (with "stays.view"). */
    var stays by mutableStateOf(ClientsStaySplit())

    suspend fun loadSuggestions() {
        suggestions = ClientsMatches.related(ClientsMatches.load(), clientId = clientId)
    }

    suspend fun load(canUseDirectory: Boolean, money: Boolean, visits: Boolean) = coroutineScope {
        val id = clientId
        try {
            client = API.get<ClientsClient>("/provider-clients/$id")
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if ((failure as? APIError)?.status == 404) notFound = true
            error = errorText(failure)
            return@coroutineScope
        }
        val directory = async { fetch<Page<ClientsPatientRow>>(canUseDirectory, "/provider-directory/pets", mapOf("limit" to "50", "offset" to "0", "clientId" to id)) }
        val accountAnswer = async { fetch<ClientsAccount>(money, "/provider-finance/clients/$id/account") }
        val invites = async { fetch<Page<ClientsInvitation>>(true, "/provider-client-invitations", mapOf("limit" to "10", "offset" to "0", "providerClientId" to id)) }
        val homeAnswer = async { fetch<ClientsHome>(true, "/provider-clients/$id/home") }
        val billsAnswer = async { fetch<ClientsInvoicePage>(money, "/provider-invoices", mapOf("limit" to "5", "offset" to "0", "providerClientId" to id)) }
        if (visits) loadVisits()

        directory.await()?.let { page ->
            val byPet = LinkedHashMap<String, ClientsPetActivity>()
            for (row in page.items) if (row.client.id == id) row.activity?.let { byPet[row.id] = it }
            petActivity = byPet
            activityKnown = true
        }
        account = accountAnswer.await()
        invitations = invites.await()?.items ?: emptyList()
        val answer = homeAnswer.await()
        if (answer != null) {
            home = answer
            homeFailed = false
        } else {
            homeFailed = true
        }
        billsAnswer.await()?.let { page ->
            bills = page.items
            billsTotal = page.total ?: page.items.size
        }
        billsLoaded = true
    }

    /** Upcoming starts with today, earlier ends now (the Agenda's list). */
    suspend fun loadVisits() {
        val id = clientId
        val todayStart = DateText.iso(DateText.today().atStartOfDay(Workspace.shared.timeZone).toInstant())
        val now = DateText.iso(Instant.now())
        try {
            coroutineScope {
                val ahead = async { API.get<Page<ClientsAppointment>>("/provider-appointments", mapOf("limit" to "12", "offset" to "0", "providerClientId" to id, "from" to todayStart)) }
                val before = async { API.get<Page<ClientsAppointment>>("/provider-appointments", mapOf("limit" to "12", "offset" to "0", "providerClientId" to id, "to" to now, "order" to "desc")) }
                val over = setOf("completed", "cancelled", "no_show")
                val next = ahead.await().items.filter { (it.status ?: "") !in over }.take(5)
                val shown = next.map { it.id }.toSet()
                upcoming = next
                earlier = before.await().items.filter { it.id !in shown }.take(5)
            }
            visitsFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            visitsFailed = true
        }
        visitsLoaded = true
    }

    /** The open invitation: waiting on the family (not run out). */
    val pendingInvitation: ClientsInvitation?
        get() = invitations.firstOrNull { it.status == "pending" && (DateText.parse(it.expiresAt)?.let { at -> at > Instant.now() } ?: true) }

    companion object {
        /** One optional read: null when it isn't asked for or it fails. */
        suspend inline fun <reified T> fetch(enabled: Boolean, path: String, query: Map<String, String?> = emptyMap()): T? {
            if (!enabled) return null
            return try {
                API.get<T>(path, query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
        }
    }
}

/** Where a client page goes from its own buttons. */
sealed class ClientsPush {
    data class section(val section: ProviderSection) : ClientsPush()
    data class invitations(val clientId: String) : ClientsPush()
    /** The client's account (iOS `NavigationLink { CashAccountScreen(clientId:) }` in the Account section). */
    data class account(val clientId: String) : ClientsPush()

    @Composable
    fun view() {
        when (this) {
            is section -> SectionView(section = section)
            is invitations -> InvitationsScreen(clientId = clientId)
            is account -> CashAccountScreen(clientId = clientId)
        }
    }
}

/** A client page's sections push their own pages through it (the Account section's "See account"). */
val LocalClientsPush = compositionLocalOf<((ClientsPush) -> Unit)?> { null }

/**
 * iOS `.navigationDestination(item: $pushed) { push in push.view }`: the page is pushed on the
 * stack as any page (`ProviderRoute.page`), sliding in with its back arrow, and the trigger is
 * cleared at once.
 */
@Composable
fun ClientsPushDestination(pushed: ClientsPush?, onBack: () -> Unit) {
    val navigator = LocalNavigator.current
    val clear by rememberUpdatedState(onBack)
    LaunchedEffect(pushed) {
        val push = pushed ?: return@LaunchedEffect
        clear()
        navigator.push(ProviderRoute.page("clients:$push") { push.view() })
    }
}

/** Scrolls a part of the page to the top of the screen (iOS `proxy.scrollTo(id, anchor: .top)`). */
@OptIn(ExperimentalFoundationApi::class)
private suspend fun BringIntoViewRequester.toTop() {
    // A rect far taller than the screen, starting at the part's top: the scroll puts that top first.
    bringIntoView(Rect(0f, 0f, 1f, 100_000f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClientDetailScreen(clientId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val page = remember(clientId) { ClientsClientPage(clientId) }

    var editing by remember { mutableStateOf(false) }
    var addingPet by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var editingPet by remember { mutableStateOf<ClientsPet?>(null) }
    var closing by remember { mutableStateOf<ClientsClosingTarget?>(null) }
    var restoring by remember { mutableStateOf<ClientsRestoreTarget?>(null) }
    var pushed by remember { mutableStateOf<ClientsPush?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var reviewingMatches by remember { mutableStateOf(false) }
    var mergingClient by remember { mutableStateOf(false) }
    var mergingPet by remember { mutableStateOf<ClientsMergePetSource?>(null) }
    var movingPet by remember { mutableStateOf<ClientsMoveTarget?>(null) }
    var unlinkingPet by remember { mutableStateOf<ClientsUnlinkTarget?>(null) }

    val canUseDirectory = workspace.can(Permission.clientsView) && (workspace.can(Permission.clientsAssignSelf) || workspace.can(Permission.clientsAssignAnyone))
    val canBook = workspace.can(Permission.appointmentsCreate) && (workspace.can(Permission.appointmentsAssignSelf) || workspace.can(Permission.appointmentsAssignAnyone))
    val canAddRecords = workspace.can(Permission.recordsCreate) && (workspace.can(Permission.recordsAssignSelf) || workspace.can(Permission.recordsAssignAnyone))
    val seesMoney = workspace.moneyLevel >= MoneyLevel.charging
    val wide = LocalSizeClass.current == SizeClass.regular

    suspend fun load() {
        page.load(canUseDirectory = canUseDirectory, money = seesMoney, visits = workspace.can(Permission.appointmentsView))
        if (workspace.can(Permission.clientsView)) page.loadSuggestions()
        if (workspace.can(Permission.staysView)) page.stays = ClientsStaysSection.load(clientId = clientId)
    }

    /** The pet a combine starts from. */
    fun mergeSource(client: ClientsClient, pet: ClientsPet) = ClientsMergePetSource(
        clientId = client.id, clientName = client.ownerName, petId = pet.id, name = pet.petName, species = pet.petSpecies, breed = pet.petBreed,
        photoUrl = pet.photoUrl, linked = pet.isLinked, householdPetId = pet.petId, details = ClientsPetDetails.of(pet, createdAt = null),
    )

    fun useLinkedName(client: ClientsClient, name: String) {
        val parts = name.trim().split(" ", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
        renaming = true
        scope.launch {
            try {
                val saved: ClientsClient = API.patch(
                    "/provider-clients/${client.id}",
                    mapOf(
                        "ownerFirstName" to (parts.firstOrNull() ?: name),
                        "ownerLastName" to (if (parts.size > 1) parts[1] else null),
                        "ownerEmail" to client.ownerEmail,
                        "recipientLanguage" to client.recipientLanguage,
                        "ownerPhone" to client.ownerPhone,
                        "notes" to client.notes,
                    ),
                )
                page.client = saved
                Toast.show(LF("The client is now called %@.", name))
                announceDataChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure, fallback = L("Could not change the name."))
            } finally {
                renaming = false
            }
        }
    }

    // The parts of the page the header's numbers jump to.
    val petsTarget = remember { BringIntoViewRequester() }
    val visitsTarget = remember { BringIntoViewRequester() }
    val accountTarget = remember { BringIntoViewRequester() }

    Box(modifier.fillMaxSize()) {
        providerPage(page.client?.ownerName ?: L("Client")) {
            val client = page.client
            val error = page.error
            when {
                !workspace.can(Permission.clientsView) -> PageScroll {
                    NoAccessState(message = L("This provider workspace is active, but your current role does not include the client detail surface."))
                }
                client != null -> CompositionLocalProvider(LocalClientsPush provides { pushed = it }) {
                    /*
                     * Laid out as the web's client page: the header (who they are, how to
                     * reach them, the numbers that matter, each one jumping to its part), the
                     * archive stamp, the family on PawMiaw, possible duplicates, the pets,
                     * the visits, the account, stays, home & contact, care cards, history.
                     */
                    PageScroll(refresh = { load() }) {
                        ClientDetailHeader(
                            client = client,
                            page = page,
                            canBook = canBook,
                            seesMoney = seesMoney,
                            wide = wide,
                            jump = { part ->
                                scope.launch {
                                    when (part) {
                                        "client-pets" -> petsTarget.toTop()
                                        "client-visits" -> visitsTarget.toTop()
                                        "client-account" -> accountTarget.toTop()
                                    }
                                }
                            },
                            onBook = { nav.push(ProviderRoute.bookVisit(BookingPrefill(clientId = client.id))) },
                            onAddPet = { addingPet = true },
                            onLink = { inviting = true },
                            onEdit = { editing = true },
                            onInvitations = { pushed = ClientsPush.invitations(client.id) },
                            onCombine = { mergingClient = true },
                            onRestore = { restoring = ClientsRestoreTarget.client(id = client.id, name = client.ownerName) },
                            onArchive = { owed -> closing = ClientsClosingTarget.archiveClient(id = client.id, name = client.ownerName, firstName = client.ownerFirstName.nonEmpty ?: client.ownerName, owed = owed) },
                        )
                        if (client.isArchived) {
                            ClientsClosingBanner(
                                name = client.ownerName,
                                stamp = client.closing,
                                deceasedAt = null,
                                hint = null,
                                onRestore = if (workspace.can(Permission.clientsEdit)) ({ restoring = ClientsRestoreTarget.client(id = client.id, name = client.ownerName) }) else null,
                            )
                        }
                        ClientsLinkCard(
                            page = page,
                            client = client,
                            canManage = workspace.can(Permission.clientsEdit),
                            renaming = renaming,
                            onUseLinkedName = { name -> useLinkedName(client, name) },
                            onOpenInvitations = { pushed = ClientsPush.invitations(client.id) },
                            onUnlink = { pet -> unlinkingPet = ClientsUnlinkTarget(clientId = client.id, clientName = client.ownerName, petId = pet.id, petName = pet.petName) },
                        )
                        if (page.suggestions.isNotEmpty()) {
                            ClientsMatchBanner(suggestions = page.suggestions, clientId = client.id, canEdit = workspace.can(Permission.clientsEdit)) { reviewingMatches = true }
                        }
                        ClientsPetsCard(
                            client = client,
                            page = page,
                            wide = wide,
                            canBook = canBook,
                            canAddRecords = canAddRecords,
                            modifier = Modifier.bringIntoViewRequester(petsTarget),
                            onAddPet = { addingPet = true },
                            onEditPet = { editingPet = it },
                            onCombine = { pet -> mergingPet = mergeSource(client, pet) },
                            onMove = { pet -> movingPet = ClientsMoveTarget(clientId = client.id, clientName = client.ownerName, petId = pet.id, name = pet.petName, linked = pet.isLinked) },
                            onUnlink = { pet -> unlinkingPet = ClientsUnlinkTarget(clientId = client.id, clientName = client.ownerName, petId = pet.id, petName = pet.petName) },
                            onClose = { pet, kind ->
                                closing = if (kind == ClientsPetRow.CloseKind.death) ClientsClosingTarget.petDeath(clientId = client.id, petId = pet.id, name = pet.petName)
                                else ClientsClosingTarget.archivePet(clientId = client.id, petId = pet.id, name = pet.petName)
                            },
                            onRestore = { pet -> restoring = ClientsRestoreTarget.pet(id = pet.id, name = pet.petName, deceased = pet.deceasedAt != null) },
                        )
                        if (workspace.can(Permission.appointmentsView)) {
                            ClientsVisitsSection(
                                page = page,
                                clientName = client.ownerName,
                                onBook = if (canBook && !client.isArchived) ({ nav.push(ProviderRoute.bookVisit(BookingPrefill(clientId = client.id))) }) else null,
                                modifier = Modifier.bringIntoViewRequester(visitsTarget),
                            )
                        }
                        if (seesMoney) {
                            ClientsAccountSection(page = page, client = client, modifier = Modifier.bringIntoViewRequester(accountTarget))
                        }
                        if (workspace.can(Permission.staysView)) {
                            ClientsStaysSection(stays = page.stays)
                        }
                        ClientsHomeSection(page = page)
                        ClientsCareCards(client)
                        AuditHistoryCard(entityType = "provider_client", entityId = client.id, description = L("Secondary audit trail for this client, linked pets, records, billing, and staff actions."))
                    }
                }
                page.notFound -> PageScroll {
                    EmptyState(
                        systemImage = "users-round",
                        title = L("Client not found"),
                        message = L("This provider client either does not exist anymore or belongs to a different provider workspace."),
                        actionTitle = L("Back to clients"),
                        action = { nav.push(ProviderRoute.section(ProviderSection.clients)) },
                        accent = Theme.lime,
                        panel = true,
                    )
                }
                error != null -> PageScroll { ErrorState(message = error) { load() } }
                else -> PageScroll { LoadingState() }
            }
        }
        ClientsPushDestination(pushed = pushed, onBack = { pushed = null })
    }
    ReloadsOnScope { load() }

    val client = page.client
    if (editing && client != null) {
        AppSheet(onDismissRequest = { editing = false }) {
            ClientsEditClientSheet(client = client, onOpenClient = { id ->
                scope.launch { delay(350); nav.push(ProviderRoute.client(id)) }
            }) { page.client = it }
        }
    }
    if (addingPet && client != null) {
        AppSheet(onDismissRequest = { addingPet = false }) { ClientsAddPetSheet(client = client) { page.client = it } }
    }
    if (inviting && client != null) {
        AppSheet(onDismissRequest = { inviting = false }) { ClientsInviteSheet(client = client) }
    }
    editingPet?.let { pet ->
        AppSheet(onDismissRequest = { editingPet = null }) { ClientsEditPetSheet(clientName = page.client?.ownerName ?: "", pet = pet) }
    }
    closing?.let { target ->
        AppSheet(onDismissRequest = { closing = null }) {
            ClientsClosingSheet(target = target, onDone = { result -> result.client?.let { page.client = it } })
        }
    }
    restoring?.let { target ->
        AppSheet(onDismissRequest = { restoring = null }) {
            ClientsRestoreSheet(target = target, onDone = { result -> result.client?.let { page.client = it } })
        }
    }
    if (reviewingMatches && client != null) {
        AppSheet(onDismissRequest = { reviewingMatches = false }) {
            ClientsMatchReviewSheet(
                suggestions = page.suggestions,
                canEdit = workspace.can(Permission.clientsEdit),
                subtitle = LF("Records that may be the same as %@ or one of their pets.", client.ownerName),
                onMerged = { suggestion, targetId ->
                    // This page's client went into the other one: open the one that stayed.
                    if (!suggestion.isPet && suggestion.source.id == client.id) {
                        reviewingMatches = false
                        scope.launch { delay(400); nav.push(ProviderRoute.client(targetId)) }
                    }
                },
                onAnswered = { load() },
            )
        }
    }
    if (mergingClient && client != null) {
        AppSheet(onDismissRequest = { mergingClient = false }) {
            ClientsMergeClientSheet(client = client, onMerged = { targetId ->
                scope.launch { delay(400); nav.push(ProviderRoute.client(targetId)) }
            })
        }
    }
    mergingPet?.let { source ->
        AppSheet(onDismissRequest = { mergingPet = null }) {
            ClientsMergePetSheet(source = source, ownPets = page.client?.allPets ?: emptyList(), canSearchOthers = canUseDirectory, onMerged = { mergedClientId, petId ->
                scope.launch { delay(400); nav.push(ProviderRoute.clientPet(clientId = mergedClientId, petId = petId)) }
            })
        }
    }
    movingPet?.let { target ->
        AppSheet(onDismissRequest = { movingPet = null }) {
            ClientsMovePetSheet(target = target) { movedClientId, petId ->
                scope.launch { load(); delay(300); nav.push(ProviderRoute.clientPet(clientId = movedClientId, petId = petId)) }
            }
        }
    }
    unlinkingPet?.let { target ->
        AppSheet(onDismissRequest = { unlinkingPet = null }) {
            ClientsUnlinkPetSheet(target = target, onDone = { load() })
        }
    }
}

/** SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)], spacing:)`. */
@Composable
private fun <T> DetailAdaptiveGrid(items: List<T>, minimum: Dp, spacing: Dp, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The care card of each pet, for businesses that groom, walk, sit, train or keep daycare. */
@Composable
private fun ClientsCareCards(client: ClientsClient) {
    val workspace = Workspace.shared
    val pets = client.allPets.filter { !it.isArchived }
    if (workspace.offers(BusinessCategory.groomer, BusinessCategory.walker, BusinessCategory.sitter, BusinessCategory.trainer, BusinessCategory.daycare) && pets.isNotEmpty()) {
        DetailAdaptiveGrid(pets, minimum = 340.dp, spacing = 16.dp) { pet ->
            SvcCareCard(petId = pet.id, petName = pet.petName, canEdit = workspace.can(Permission.petsEdit) || workspace.can(Permission.appointmentsComplete))
        }
    }
}

// MARK: Header

/**
 * The web's `ProviderModuleHeader` for a client: lime, their photo or
 * initials, their name, how to reach them, the actions on their own row
 * (Agendar, Llamar, WhatsApp | Agregar mascota, Vincular familia, Editar, ⋯)
 * and the numbers. The header's subtitle is text only here, so the status
 * pill and the PawMiaw badge open the actions row.
 */
@Composable
private fun ClientDetailHeader(
    client: ClientsClient,
    page: ClientsClientPage,
    canBook: Boolean,
    seesMoney: Boolean,
    wide: Boolean,
    jump: (String) -> Unit,
    onBook: () -> Unit,
    onAddPet: () -> Unit,
    onLink: () -> Unit,
    onEdit: () -> Unit,
    onInvitations: () -> Unit,
    onCombine: () -> Unit,
    onRestore: () -> Unit,
    onArchive: (String?) -> Unit,
) {
    val workspace = Workspace.shared
    val openURL = rememberOpenURL()
    val phone = client.ownerPhone.nonEmpty
    val email = client.ownerEmail.nonEmpty
    val contact = listOfNotNull(phone, email).joinToString(" · ").nonEmpty ?: L("No contact details yet")
    val book = canBook && !client.isArchived
    val tel = ContactLinks.phone(phone)
    val whatsapp = ContactLinks.whatsApp(phone)
    ModuleHeader(
        icon = "users-round",
        title = client.ownerName,
        subtitle = contact,
        accent = Theme.lime,
        media = { Avatar(name = client.ownerName, imageURL = client.photoUrl, size = if (wide) 56.dp else 36.dp) },
        titleAccessory = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if ((client.status ?: "active") != "active") {
                    StatusChip(text = if (client.isArchived) L("Archived") else ClientsText.clientStatus(client.status), brand = Theme.lime)
                }
                ClientsLinkBadge(linked = client.isLinked)
            }
        },
        stats = clientDetailStats(client, page, seesMoney, jump),
        actionsOnRow = true,
        storageKey = "client",
        actions = {
            // Booking is the one filled button; then reaching the family, then what you do with the client.
            if (book) {
                PMButton(L("Book a visit"), systemImage = "calendar-plus", action = onBook)
            }
            if (tel != null) {
                PMButton(L("Call"), systemImage = "phone", variant = ButtonVariant.outline) { openURL(tel) }
            }
            if (whatsapp != null) {
                PMButton(L("WhatsApp"), systemImage = "message-circle", variant = ButtonVariant.outline) { openURL(whatsapp) }
            }
            if (tel != null || whatsapp != null) ChipRule()
            if (workspace.can(Permission.petsCreate) && !client.isArchived) {
                PMButton(L("Add pet"), systemImage = "paw-print", variant = ButtonVariant.outline, action = onAddPet)
            }
            if (workspace.can(Permission.clientsEdit) && !client.isLinked && !client.isArchived) {
                PMButton(L("Link household"), systemImage = "link-2", variant = ButtonVariant.outline, action = onLink)
            }
            if (workspace.can(Permission.clientsEdit)) {
                PMButton(L("Edit client"), systemImage = "pencil", variant = ButtonVariant.outline, action = onEdit)
            }
            AppMenu(label = { IconCircleLabel() }, accessibilityLabel = LF("More for %@", client.ownerName)) {
                // One way to link at a time: the button while the family isn't linked, the menu item after.
                val unlinkedPets = client.allPets.filter { it.isActive && !it.isLinked }
                if (workspace.can(Permission.clientsEdit) && client.isLinked && unlinkedPets.isNotEmpty()) {
                    item(LF("Invite to link %@", unlinkedPets.joinToString(", ") { it.petName }), icon = "link-2") { onLink() }
                }
                item(L("Invitations"), icon = "mail-plus") { onInvitations() }
                if (workspace.can(Permission.clientsEdit)) {
                    divider()
                    item(L("Combine with another client"), icon = "git-merge") { onCombine() }
                    if (client.isArchived) {
                        item(L("Restore"), icon = "rotate-ccw") { onRestore() }
                    } else {
                        item(L("Archive client"), icon = "archive", destructive = true) {
                            val owed = if (seesMoney && (page.account?.owed ?: 0) > 0) Money.format(page.account?.owed, currency = page.account?.currency) else null
                            onArchive(owed)
                        }
                    }
                }
            }
        },
    ) {
        // Unlinked (by the family or by the business): when, and that what they owe from before stays theirs (docs/family-money.md §1).
        ClientsDisconnectedMark(
            disconnectedAt = client.disconnectedAt, disconnectedBy = client.disconnectedBy, withDetail = true,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        )
    }
}

/** The header's numbers (web `stats`): each one jumps to its part of the page. */
@Composable
private fun clientDetailStats(client: ClientsClient, page: ClientsClientPage, seesMoney: Boolean, jump: (String) -> Unit): List<ModuleStat> {
    val workspace = Workspace.shared
    val active = client.allPets.filter { !it.isArchived }
    val archivedCount = client.allPets.size - active.size
    val known = page.activityKnown || active.isEmpty()
    val last = active.mapNotNull { pet -> page.petActivity[pet.id]?.lastVisitAt?.let { it to pet.petName } }.maxByOrNull { it.first }
    val next = active.mapNotNull { pet -> page.petActivity[pet.id]?.nextVisitAt?.let { it to pet.petName } }.minByOrNull { it.first }
    val due = active.mapNotNull { pet ->
        val activity = page.petActivity[pet.id] ?: return@mapNotNull null
        val at = activity.nextDueAt ?: return@mapNotNull null
        Triple(at, pet.petName, activity.nextDueTitle?.let { ClientsText.followUpTitle(it) } ?: L("Follow-up"))
    }.minByOrNull { it.first }
    // Pets that are all archived are still its pets: "none yet" would be untrue.
    val petsHint = if (active.isEmpty()) {
        if (archivedCount > 0) LP(archivedCount, "%d archived", "%d archived") else L("No pets yet")
    } else {
        listOfNotNull(active.joinToString(", ") { it.petName }, if (archivedCount > 0) LP(archivedCount, "%d archived", "%d archived") else null).joinToString(" · ")
    }
    val visitsSection = if (workspace.can(Permission.appointmentsView)) "client-visits" else "client-pets"

    val stats = mutableListOf(
        ModuleStat("pets", label = L("Pets"), value = "${active.size}", hint = petsHint, icon = "paw-print", accent = Theme.aqua, action = { jump("client-pets") }),
        ModuleStat(
            "last", label = L("Last visit"), value = if (known) (last?.let { ClientsText.shortDay(it.first) } ?: L("None yet")) else "—",
            hint = if (known) (last?.second ?: L("First visit still to come")) else null, icon = "stethoscope", accent = Theme.lime, action = { jump(visitsSection) },
        ),
        ModuleStat(
            "next", label = L("Next visit"), value = if (known) (next?.let { ClientsText.shortDay(it.first) } ?: L("Not booked")) else "—",
            hint = if (known) {
                next?.let { "${it.second} · ${DateText.time(it.first)}" }
                    ?: due?.let { LF("Due %@: %@", ClientsText.shortDay(it.first), "${it.third} (${it.second})") }
                    ?: L("Nothing on the calendar")
            } else null,
            icon = "calendar-days", accent = Theme.mango, attention = known && next == null && ClientsText.isPast(due?.first), action = { jump(visitsSection) },
        ),
    )
    if (seesMoney) {
        val account = page.account
        val owed = account?.owed
        val money = { cents: Int -> Money.format(cents, currency = account?.currency) }
        val hint: String? = when {
            account == null -> null
            (account.overdue ?: 0) > 0 -> LF("%@ overdue", money(account.overdue ?: 0))
            (account.credit ?: 0) > 0 -> LF("%@ in credit", money(account.credit ?: 0))
            else -> if ((owed ?: 0) > 0) L("Nothing overdue") else L("Nothing owed")
        }
        stats.add(ModuleStat("balance", label = L("Balance"), value = owed?.let { money(it) } ?: "—", hint = hint, icon = "badge-dollar-sign", accent = Theme.pink,
            attention = (owed ?: 0) > 0, action = { jump("client-account") }))
    } else {
        stats.add(ModuleStat("balance", label = L("Balance"), value = "—", hint = L("Billing permission required"), icon = "badge-dollar-sign", accent = Theme.pink))
    }
    return stats
}

// MARK: Pets

/**
 * The pets card (web: the "Mascotas" section): the client's notes on a
 * mango band, then one row per pet; archived ones last and faded.
 */
@Composable
private fun ClientsPetsCard(
    client: ClientsClient,
    page: ClientsClientPage,
    wide: Boolean,
    canBook: Boolean,
    canAddRecords: Boolean,
    modifier: Modifier,
    onAddPet: () -> Unit,
    onEditPet: (ClientsPet) -> Unit,
    onCombine: (ClientsPet) -> Unit,
    onMove: (ClientsPet) -> Unit,
    onUnlink: (ClientsPet) -> Unit,
    onClose: (ClientsPet, ClientsPetRow.CloseKind) -> Unit,
    onRestore: (ClientsPet) -> Unit,
) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val pets = client.allPets.sortedBy { if (it.isArchived) 1 else 0 }
    val activeCount = pets.count { !it.isArchived }
    SectionCard(
        title = L("Pets"),
        subtitle = L("Each pet has its own chart: records, visits and follow-ups."),
        icon = "paw-print",
        accent = Theme.aqua,
        modifier = modifier,
        trailing = { Text("$activeCount", style = Theme.heading(16).monospacedDigit(), color = Theme.inkSoft) },
    ) {
        client.notes.nonEmpty?.let { notes ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.mango, 10.0))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph("sticky-note", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${L("Client notes")}: ") }
                        append(notes)
                    },
                    style = Theme.text(14),
                    color = Theme.inkStrong,
                )
            }
            RowDivider()
        }
        if (pets.isEmpty()) {
            EmptyState(
                systemImage = "paw-print",
                title = L("No pets yet"),
                message = L("Add the first pet so visits, records and care stay with the right pet."),
                actionTitle = if (workspace.can(Permission.petsCreate)) L("Add pet") else null,
                action = onAddPet,
                accent = Theme.lime,
            )
        } else {
            pets.forEachIndexed { index, pet ->
                key(pet.id) {
                    if (index > 0) RowDivider()
                    ClientsPetRow(
                        client = client,
                        pet = pet,
                        activity = page.petActivity[pet.id],
                        wide = wide,
                        canBook = canBook && pet.isActive && !client.isArchived,
                        canAddRecord = canAddRecords,
                        canEdit = workspace.can(Permission.petsEdit),
                        onOpen = { nav.push(ProviderRoute.clientPet(clientId = client.id, petId = pet.id)) },
                        onBook = { nav.push(ProviderRoute.bookVisit(BookingPrefill(clientId = client.id, petId = pet.id))) },
                        onRecord = { nav.push(ProviderRoute.newRecord(RecordPrefill(clientId = client.id, petId = pet.id))) },
                        onEdit = { onEditPet(pet) },
                        onCombine = if (workspace.can(Permission.petsEdit)) ({ onCombine(pet) }) else null,
                        onMove = if (workspace.can(Permission.petsEdit) && workspace.isOwnerOrAdmin && pet.isActive && !client.isArchived) ({ onMove(pet) }) else null,
                        onUnlink = if (workspace.can(Permission.clientsEdit) && pet.isLinked) ({ onUnlink(pet) }) else null,
                        onClose = { kind -> onClose(pet, kind) },
                        // A pet archived with its client comes back with the client; one that changed family is moved back from its new record.
                        onRestore = if (client.isArchived || pet.closing?.reason == "client_archived" || pet.movedTo != null) null else ({ onRestore(pet) }),
                    )
                }
            }
        }
    }
}

// MARK: - A pet on the client's page

/** `ClientsPetRow.CloseKind`. */
private object ClientsPetRow {
    enum class CloseKind { archive, death }
}

/**
 * One pet on the client's page (web `ClientPetRow`): its 48 face, the name
 * with the allergy, its state and the PawMiaw link, the profile line, the
 * vet and chip, where it moved, its notes and what's next; Agendar,
 * Registro and Editar on their own line (sharing it), ⋯ for the rest.
 * The row opens the chart.
 */
@Composable
private fun ClientsPetRow(
    client: ClientsClient,
    pet: ClientsPet,
    activity: ClientsPetActivity?,
    wide: Boolean,
    canBook: Boolean,
    canAddRecord: Boolean,
    canEdit: Boolean,
    onOpen: () -> Unit,
    onBook: () -> Unit,
    onRecord: () -> Unit,
    onEdit: () -> Unit,
    onCombine: (() -> Unit)?,
    onMove: (() -> Unit)?,
    onUnlink: (() -> Unit)?,
    onClose: (ClientsPetRow.CloseKind) -> Unit,
    onRestore: (() -> Unit)?,
) {
    val nav = LocalNavigator.current
    val next = ClientsNext.of(activity)
    val allergies = activity?.allergies ?: emptyList()
    val died = pet.deceasedAt != null
    val meta = listOfNotNull(
        pet.preferredMember?.name.nonEmpty?.let { LF("Vet: %@", it) },
        pet.microchipNumber.nonEmpty?.let { LF("Chip ··%@", it.takeLast(6)) },
    ).joinToString(" · ").nonEmpty
    val openLabel = LF("Open %@ chart", pet.petName)
    val source = remember { MutableInteractionSource() }

    /** Book, Record, Edit (labelled, sharing the line on a phone) and ⋯. */
    @Composable
    fun actions(modifier: Modifier = Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val fill = !wide
            if (canBook) {
                val label = LF("Book a visit for %@", pet.petName)
                PMButton(L("Book"), systemImage = "calendar-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = fill,
                    modifier = (if (fill) Modifier.weight(1f) else Modifier).semantics { contentDescription = label }, action = onBook)
            }
            if (canAddRecord) {
                val label = LF("Add record for %@", pet.petName)
                PMButton(L("Record"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = fill,
                    modifier = (if (fill) Modifier.weight(1f) else Modifier).semantics { contentDescription = label }, action = onRecord)
            }
            if (canEdit) {
                val label = LF("Edit %@", pet.petName)
                PMButton(L("Edit"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = fill,
                    modifier = (if (fill) Modifier.weight(1f) else Modifier).semantics { contentDescription = label }, action = onEdit)
            }
            if (canEdit || onUnlink != null) {
                AppMenu(label = { IconCircleLabel() }, accessibilityLabel = LF("More for %@", pet.petName)) {
                    if (onCombine != null) item(L("Combine with another pet"), icon = "git-merge") { onCombine() }
                    if (onMove != null) item(L("Move to another client"), icon = "arrow-right-left") { onMove() }
                    if (canEdit && pet.isArchived && onRestore != null) item(L("Restore"), icon = "rotate-ccw") { onRestore() }
                    if (onUnlink != null) {
                        if (canEdit) divider()
                        item(L("Unlink from the family"), icon = "link-2-off", destructive = true) { onUnlink() }
                    }
                    if (canEdit && !died && pet.movedTo == null) {
                        divider()
                        if (!pet.isArchived) {
                            item(L("Archive pet"), icon = "archive", destructive = true) { onClose(ClientsPetRow.CloseKind.archive) }
                        }
                        item(L("Record that it died"), icon = "flower-2", destructive = true) { onClose(ClientsPetRow.CloseKind.death) }
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (pet.isArchived) 0.6f else 1f)
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .semantics { customActions = listOf(CustomAccessibilityAction(openLabel) { onOpen(); true }) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PetAvatar(name = pet.petName, species = pet.petSpecies, photoURL = pet.photoUrl, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowLayout(spacing = 6.dp) {
                    Text(pet.petName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (allergies.isNotEmpty()) {
                        // What it is allergic to is read here, not behind a hover.
                        Chip(text = "${L("Allergy")}: ${allergies.joinToString(", ")}", tone = Chip.Tone.danger, icon = "triangle-alert")
                    }
                    if (!pet.isActive) {
                        StatusChip(text = if (died) L("Deceased") else if (pet.isArchived) L("Archived") else ClientsText.clientStatus(pet.status), brand = Theme.lime, small = true)
                    }
                    if (pet.isLinked) {
                        val label = L("Linked to the family’s PawMiaw profile")
                        Glyph("link-2", 14.dp, tint = Theme.blue, modifier = Modifier.semantics { contentDescription = label })
                    }
                }
                Text(ClientsText.profileLine(pet).nonEmpty ?: L("No profile details yet"), style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta != null) Text(meta, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // It changed family: where it is now, or where it came from.
                val moved = pet.movedTo
                val from = pet.movedFrom
                if (moved != null) {
                    Row(
                        Modifier.padding(top = 2.dp).plainButton { nav.push(ProviderRoute.clientPet(clientId = moved.clientId, petId = moved.petId)) },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("arrow-right-left", 12.dp, tint = Theme.inkStrong)
                        Text(LF("Now with %@", moved.clientName ?: "—"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else if (from != null && !pet.isArchived) {
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("arrow-right-left", 12.dp, tint = Theme.inkSoft)
                        Text(LF("Came from %@", from.clientName ?: "—"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                pet.notes.nonEmpty?.let { notes ->
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("sticky-note", 12.dp, tint = Theme.inkSoft)
                        Text(notes, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // On a phone, what's next goes under the pet; wider screens give it a column.
                if (!pet.isArchived && !wide && next != null) {
                    val strong = Theme.inkStrong
                    val late = if (ClientsText.isPast(next.at)) Theme.destructive else Theme.inkStrong
                    val soft = Theme.inkSoft
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = strong)) { append("${L("Next")}: ") }
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = late)) { append(ClientsText.shortDay(next.at)) }
                            withStyle(SpanStyle(color = soft)) { append(" · ${next.what}") }
                        },
                        Modifier.padding(top = 4.dp),
                        style = Theme.text(12),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (wide && !pet.isArchived) {
                ClientsPetColumn(L("Last visit"), width = 112.dp) {
                    Text(if (activity?.lastVisitAt == null) "—" else ClientsText.shortDay(activity?.lastVisitAt), style = Theme.text(14), color = Theme.inkStrong)
                }
                ClientsPetColumn(L("Next"), width = 176.dp) {
                    if (next != null) {
                        val late = if (ClientsText.isPast(next.at)) Theme.destructive else Theme.inkStrong
                        val soft = Theme.inkSoft
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = late)) { append(ClientsText.shortDay(next.at)) }
                                withStyle(SpanStyle(color = soft)) { append(" · ${next.what}") }
                            },
                            style = Theme.text(14),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text("—", style = Theme.text(14), color = Theme.inkSoft)
                    }
                }
            }
            if (wide) actions()
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
        if (!wide && (canBook || canAddRecord || canEdit || onUnlink != null)) {
            actions(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ClientsPetColumn(label: String, width: Dp, content: @Composable () -> Unit) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.06), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}
