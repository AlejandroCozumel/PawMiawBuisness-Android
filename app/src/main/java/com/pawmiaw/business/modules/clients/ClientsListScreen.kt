package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

//
//  ClientsListScreen.kt (iOS ClientsListScreen.swift)
//
//  Clientes y pacientes (web: routes/provider.clients.tsx): the families and
//  their pets on one page, seen by client (every pet on the row) or by
//  patient (every pet, its owner on the row). Patients is the same page in
//  its Patients view (web: provider.patients.tsx redirects there).
//
//  As on the web: the module header (lime, its numbers are the filters),
//  then one card — Clientes/Pacientes tabs, the search and the sort, the
//  wash filter chips, the "Vencen esta semana" strip — and the rows. A phone
//  reads the web's table as rows: avatar, name and tags, the pets as chips
//  under them, then the table's other columns in its uppercase head style.
//  20 at a time with infinite scroll (not the web's pages); every number is
//  the server's.
//

enum class ClientsDirectoryView(val rawValue: String) {
    clients("clients"), patients("patients");

    companion object {
        fun from(raw: String?): ClientsDirectoryView? = entries.firstOrNull { it.rawValue == raw }
    }
}

private enum class ClientsQuickFilter { leads, active, owing, due, archived }

private enum class ClientsSort(val rawValue: String) { recent("recent"), byName("name"), last_visit("last_visit") }  // Swift `.name` (an enum entry can't be called `name` in Kotlin)

/** One optional read (Swift `try?`): null when it fails; a cancel still cancels. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    null
}

private const val ClientsViewKey = "pawmiaw.provider.clients.view"

@Composable
fun ClientsScreen(modifier: Modifier = Modifier) {
    // iOS `@AppStorage("pawmiaw.provider.clients.view")`.
    val stored = remember { AppContext.defaults.getString(ClientsViewKey, ClientsDirectoryView.clients.rawValue) }
    ClientsDirectory(
        initialView = ClientsDirectoryView.from(stored) ?: ClientsDirectoryView.clients,
        rememberView = { AppContext.defaults.edit().putString(ClientsViewKey, it.rawValue).apply() },
        modifier = modifier,
    )
}

/** Pacientes: the directory in its Patients view. */
@Composable
fun PatientsScreen(modifier: Modifier = Modifier) {
    ClientsDirectory(initialView = ClientsDirectoryView.patients, rememberView = {}, modifier = modifier)
}

/** The directory's state (iOS the view's `@State`s and its loading functions). */
@Stable
private class ClientsDirectoryModel(
    initialView: ClientsDirectoryView,
    /** iOS `remember`: keeps the view chosen for next time. */
    val rememberView: (ClientsDirectoryView) -> Unit,
    val scope: CoroutineScope,
) {
    var view by mutableStateOf(initialView)
    val query = mutableStateOf("")
    var search by mutableStateOf("")
    var sort by mutableStateOf(ClientsSort.recent)
    // Clients: one quick filter at a time, and who looks after them.
    var filter by mutableStateOf<ClientsQuickFilter?>(null)
    var assignee by mutableStateOf("all")
    // Patients: the filters add up.
    var species by mutableStateOf<String?>(null)
    var lastVisit by mutableStateOf<String?>(null)
    var vaccinesOverdue by mutableStateOf(false)
    var linked by mutableStateOf<Boolean?>(null)
    var archivedPets by mutableStateOf(false)

    var summary by mutableStateOf<ClientsDirectorySummary?>(null)
    var patientSummary by mutableStateOf<ClientsPatientsSummary?>(null)
    var members by mutableStateOf<List<ClientsMember>>(emptyList())
    var creating by mutableStateOf(false)
    var bulkInviting by mutableStateOf(false)
    var inviteOne by mutableStateOf<ClientsClient?>(null)
    var suggestions by mutableStateOf<List<ClientsMatchSuggestion>>(emptyList())
    var reviewingMatches by mutableStateOf(false)

    val clients = Pager<ClientsClient>(fetch = { _, _ -> Page(emptyList(), 0) })
    val patients = Pager<ClientsPatientRow>(fetch = { _, _ -> Page(emptyList(), 0) })

    private val workspace get() = Workspace.shared
    val canAssignAnyone: Boolean get() = workspace.can(Permission.clientsAssignAnyone)
    val canView: Boolean get() = workspace.can(Permission.clientsView) && (workspace.can(Permission.clientsAssignSelf) || canAssignAnyone)
    val canCreate: Boolean get() = workspace.can(Permission.clientsCreate) && workspace.can(Permission.petsCreate) && (workspace.can(Permission.clientsAssignSelf) || canAssignAnyone)
    val canEditClients: Boolean get() = workspace.can(Permission.clientsEdit)
    /** What a client owes is money, level 1 (docs/money-access-levels.md). */
    val canSeeOwed: Boolean get() = workspace.moneyLevel >= MoneyLevel.charging
    val needle: String get() = search.trim()
    val patientQuickFiltered: Boolean get() = lastVisit != null || vaccinesOverdue || linked != null || archivedPets
    val patientFiltered: Boolean get() = patientQuickFiltered || species != null
    val noClientsYet: Boolean get() = summary?.clients == 0

    // MARK: Filters

    fun toggle(next: ClientsQuickFilter) = setFilter(if (filter == next) null else next)

    @JvmName("chooseFilter") // the `filter` property's setter is `setFilter` on the JVM
    fun setFilter(next: ClientsQuickFilter?) {
        filter = next
        reload()
    }

    fun switchView(next: ClientsDirectoryView) {
        if (next == view) return
        view = next
        rememberView(next)
        if (next == ClientsDirectoryView.patients) {
            filter = null
        } else {
            species = null; lastVisit = null; vaccinesOverdue = false; linked = null; archivedPets = false
        }
        scope.launch { reloadAll() }
    }

    fun clearPatientFilters() {
        query.value = ""; search = ""
        species = null; lastVisit = null; vaccinesOverdue = false; linked = null; archivedPets = false
        reload()
    }

    fun reload() {
        scope.launch { reloadList() }
    }

    // MARK: Loading

    suspend fun reloadAll() = coroutineScope {
        if (!canView || workspace.inSetup) return@coroutineScope
        val list = async { reloadList() }
        if (view == ClientsDirectoryView.clients) {
            summary = attempt { API.get<ClientsDirectorySummary>("/provider-directory/summary") }
        } else {
            if (summary == null) summary = attempt { API.get<ClientsDirectorySummary>("/provider-directory/summary") }
            patientSummary = attempt { API.get<ClientsPatientsSummary>("/provider-patients/summary") }
        }
        if (canAssignAnyone && members.isEmpty()) members = ClientsMembers.load()
        suggestions = ClientsMatches.load()
        list.await()
    }

    suspend fun reloadList() {
        val location = workspace.listLocationFilter
        val q = needle.nonEmpty
        val sortValue = sort.rawValue
        if (view == ClientsDirectoryView.clients) {
            val status: String? = when (filter) {
                ClientsQuickFilter.leads -> "lead"
                ClientsQuickFilter.active -> "active"
                ClientsQuickFilter.archived -> "archived"
                else -> null
            }
            val owing = filter == ClientsQuickFilter.owing && canSeeOwed
            val due = filter == ClientsQuickFilter.due
            val assigned = if (canAssignAnyone && assignee != "all" && assignee != "unassigned") assignee else null
            val unassigned = canAssignAnyone && assignee == "unassigned"
            clients.fetch = { offset, limit ->
                API.get<Page<ClientsClient>>(
                    "/provider-clients",
                    mapOf(
                        "limit" to "$limit", "offset" to "$offset", "search" to q, "sort" to sortValue,
                        "owing" to (if (owing) "true" else null), "due" to (if (due) "true" else null), "status" to status,
                        "includeArchived" to (if (q != null) "true" else null),
                        "assignedOrganizationMemberId" to assigned, "unassigned" to (if (unassigned) "true" else null),
                        "locationId" to location,
                    ),
                )
            }
            clients.reload()
        } else {
            val speciesValue = species
            val lastVisitValue = lastVisit
            val vaccines = vaccinesOverdue
            val archived = archivedPets
            val linkedValue = linked
            patients.fetch = { offset, limit ->
                API.get<Page<ClientsPatientRow>>(
                    "/provider-patients",
                    mapOf(
                        "limit" to "$limit", "offset" to "$offset", "search" to q, "sort" to sortValue,
                        "species" to speciesValue, "lastVisit" to lastVisitValue,
                        "vaccines" to (if (vaccines) "overdue" else null), "archived" to (if (archived) "true" else null),
                        "linked" to linkedValue?.let { if (it) "true" else "false" }, "locationId" to location,
                    ),
                )
            }
            patients.reload()
        }
    }
}

@Composable
private fun ClientsDirectory(
    initialView: ClientsDirectoryView,
    rememberView: (ClientsDirectoryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val m = remember { ClientsDirectoryModel(initialView, rememberView, scope) }

    providerPage(L("Clients & patients"), root = true, modifier = modifier) {
        when {
            workspace.inSetup -> PageScroll {
                EmptyState(
                    systemImage = "loader-circle",
                    title = L("Finish workspace setup before opening clients."),
                    message = L("The client directory belongs to a workspace. Finish its basics first."),
                    accent = Theme.lime,
                    panel = true,
                )
            }
            !m.canView -> PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see clients.")) }
            else -> ClientsDirectoryContent(m)
        }
    }
    if (m.creating) {
        AppSheet(onDismissRequest = { m.creating = false }) {
            ClientsCreateSheet(onOpenClient = { id ->
                scope.launch { delay(350); nav.push(ProviderRoute.client(id)) }
            }) { client -> nav.push(ProviderRoute.client(client.id)) }
        }
    }
    if (m.bulkInviting) {
        AppSheet(onDismissRequest = { m.bulkInviting = false }) {
            ClientsBulkInviteSheet { client ->
                m.bulkInviting = false
                scope.launch { delay(450); m.inviteOne = client }
            }
        }
    }
    m.inviteOne?.let { client ->
        AppSheet(onDismissRequest = { m.inviteOne = null }) { ClientsInviteSheet(client = client) }
    }
    if (m.reviewingMatches) {
        AppSheet(onDismissRequest = { m.reviewingMatches = false }) {
            ClientsMatchReviewSheet(suggestions = m.suggestions, canEdit = m.canEditClients, onAnswered = { m.suggestions = ClientsMatches.load() })
        }
    }
    ReloadsOnScope { m.reloadAll() }
    // The client search's one rule: the server searches from two letters, 250 ms after typing.
    Debounced(m.query.value) { value ->
        val next = ClientSearch.query(value) ?: ""
        if (next == m.search) return@Debounced
        m.search = next
        m.reloadList()
    }
}

// MARK: Page

@Composable
private fun ClientsDirectoryContent(m: ClientsDirectoryModel) {
    PageScroll(refresh = { m.reloadAll() }) {
        ClientsDirectoryHeader(m)
        if (m.noClientsYet) {
            EmptyState(
                systemImage = "paw-print",
                title = L("Add your first client and pet"),
                message = L("Create the first provider client and pet here. After that, appointments, records, invoices, and care history can start from the same profile."),
                actionTitle = if (m.canCreate) L("Add client and pet") else null,
                action = { m.creating = true },
                accent = Theme.lime,
                panel = true,
            )
        } else {
            ClientsDirectoryCard(m)
        }
    }
}

/**
 * The web's `ProviderModuleHeader`: lime, "Invitar a PawMiaw" and "Agregar cliente y mascota",
 * and the numbers that follow the view (each one is the filter it names).
 */
@Composable
private fun ClientsDirectoryHeader(m: ClientsDirectoryModel) {
    ModuleHeader(
        icon = "users-round",
        title = L("Clients & patients"),
        subtitle = L("Families and their pets — look them up by either."),
        accent = Theme.lime,
        stats = clientsHeaderStats(m),
        storageKey = "clients",
    ) {
        if (m.canEditClients && !m.noClientsYet) {
            PMButton(L("Invite to PawMiaw"), systemImage = "mail-plus", variant = ButtonVariant.outline) { m.bulkInviting = true }
        }
        if (m.canCreate) {
            PMButton(L("Add client and pet"), systemImage = "users-round") { m.creating = true }
        }
    }
}

@Composable
private fun clientsHeaderStats(m: ClientsDirectoryModel): List<ModuleStat> {
    val stats = mutableListOf<ModuleStat>()
    val summary = m.summary
    val patientSummary = m.patientSummary
    if (m.view == ClientsDirectoryView.clients && summary != null) {
        stats.add(ModuleStat("clients", label = L("Clients"), value = "${summary.clients ?: 0}", icon = "users-round", accent = Theme.lime,
            active = m.filter == null, action = { m.setFilter(null) }))
        stats.add(ModuleStat("patients", label = L("Patients"), value = "${summary.pets ?: 0}", icon = "paw-print", accent = Theme.aqua,
            action = { m.switchView(ClientsDirectoryView.patients) }))
        val due = summary.dueThisWeek ?: 0
        stats.add(ModuleStat("due", label = L("Due this week"), value = "$due", hint = L("Vaccines, rechecks and follow-ups"), icon = "bell-ring", accent = Theme.mango,
            attention = due > 0, active = m.filter == ClientsQuickFilter.due, action = { m.toggle(ClientsQuickFilter.due) }))
        val owing = summary.owing
        if (m.canSeeOwed && owing != null) {
            stats.add(ModuleStat("owing", label = L("Owe money"), value = "$owing", icon = "circle-dollar-sign", accent = Theme.pink,
                attention = owing > 0, active = m.filter == ClientsQuickFilter.owing, action = { m.toggle(ClientsQuickFilter.owing) }))
        }
    } else if (m.view == ClientsDirectoryView.patients && patientSummary != null) {
        val pets = patientSummary.pets ?: 0
        stats.add(ModuleStat("patients", label = L("Patients"), value = "$pets", icon = "paw-print", accent = Theme.aqua,
            active = !m.patientFiltered && m.needle.isEmpty(), action = { m.clearPatientFilters() }))
        val overdue = patientSummary.vaccinesOverdue ?: 0
        stats.add(ModuleStat("vaccines", label = L("Vaccines overdue"), value = "$overdue", icon = "syringe", accent = Theme.mango,
            attention = overdue > 0, active = m.vaccinesOverdue, action = { m.vaccinesOverdue = !m.vaccinesOverdue; m.reload() }))
        stats.add(ModuleStat("recent", label = L("Seen in 30 days"), value = "${patientSummary.seenLast30Days ?: 0}", icon = "calendar-check", accent = Theme.blue,
            active = m.lastVisit == "30d", action = { m.lastVisit = if (m.lastVisit == "30d") null else "30d"; m.reload() }))
        val onPawMiaw = patientSummary.linked ?: 0
        stats.add(ModuleStat("linked", label = L("On PawMiaw"), value = "$onPawMiaw",
            hint = if (pets > 0) LF("%d%% of patients", (onPawMiaw.toDouble() / pets.toDouble() * 100).roundToInt()) else null,
            icon = "link-2", accent = Theme.lime, active = m.linked == true, action = { m.linked = if (m.linked == true) null else true; m.reload() }))
    }
    if (m.suggestions.isNotEmpty() && stats.isNotEmpty()) {
        // The same family or pet entered twice: the whole business's open suggestions.
        stats.add(ModuleStat("matches", label = L("Possible matches"), value = "${m.suggestions.size}",
            hint = if (m.canEditClients) L("Same family or pet twice? Review them") else L("Same family or pet twice?"),
            icon = "git-merge", accent = Theme.coral, attention = true, active = m.reviewingMatches, action = { m.reviewingMatches = true }))
    }
    return stats
}

// MARK: The card: toolbar, filters, rows, count

@Composable
private fun ClientsDirectoryCard(m: ClientsDirectoryModel) {
    Card(padding = 0.dp, spacing = 0.dp) {
        ClientsToolbarRow(m)
        RowDivider()
        ClientsFilterRow(m)
        RowDivider()
        if (m.filter == ClientsQuickFilter.due && m.view == ClientsDirectoryView.clients) {
            ClientsDueStrip()
            RowDivider()
        }
        ClientsDirectoryList(m)
    }
}

/** Clientes/Pacientes, the search, the sort (stacked on a phone, one line on an iPad). */
@Composable
private fun ClientsToolbarRow(m: ClientsDirectoryModel) {
    val wide = LocalSizeClass.current == SizeClass.regular
    val tabs = @Composable {
        Segmented(
            options = listOf(
                Segmented.Option(ClientsDirectoryView.clients, L("Clients"), icon = "users-round"),
                Segmented.Option(ClientsDirectoryView.patients, L("Patients"), icon = "paw-print"),
            ),
            selection = stateOf({ m.view }, { m.switchView(it) }),
            look = Segmented.Look.raised,
            label = L("View"),
        )
    }
    val prompt = if (m.view == ClientsDirectoryView.clients) L("Client, pet, email or phone") else L("Pet, owner, phone or microchip")
    val padded = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    if (wide) {
        Row(padded, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs()
            SearchField(prompt = prompt, text = m.query, height = 40.dp, modifier = Modifier.weight(1f))
            ClientsSortMenu(m)
        }
    } else {
        Column(padded, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tabs()
            SearchField(prompt = prompt, text = m.query, height = 40.dp)
            ClientsSortMenu(m)
        }
    }
}

@Composable
private fun ClientsSortMenu(m: ClientsDirectoryModel) {
    val title = when (m.sort) {
        ClientsSort.recent -> L("Recently added")
        ClientsSort.byName -> L("Name")
        ClientsSort.last_visit -> L("Last visit")
    }
    AppMenu(
        label = { PillField(text = title, height = 40.dp) },
        modifier = Modifier.alpha(if (m.needle.isEmpty()) 1f else 0.5f),
        enabled = m.needle.isEmpty(),
        accessibilityLabel = L("Sort"),
    ) {
        picker(
            L("Sort"),
            options = listOf(ClientsSort.recent to L("Recently added"), ClientsSort.byName to L("Name"), ClientsSort.last_visit to L("Last visit")),
            selection = stateOf({ m.sort }, { m.sort = it; m.reload() }),
        )
    }
}

/**
 * The quick filters in the colours the rows and the header's numbers use;
 * then who looks after them (clients) or which pets (patients).
 */
@Composable
private fun ClientsFilterRow(m: ClientsDirectoryModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.view == ClientsDirectoryView.clients) ClientsClientChips(m) else ClientsPatientChips(m)
    }
}

@Composable
private fun ClientsClientChips(m: ClientsDirectoryModel) {
    FilterChip(title = L("All"), isSelected = m.filter == null, tone = Theme.blue) { m.setFilter(null) }
    FilterChip(title = L("Leads"), isSelected = m.filter == ClientsQuickFilter.leads, tone = Theme.lavender, dot = Theme.lavender) { m.toggle(ClientsQuickFilter.leads) }
    FilterChip(title = L("Active clients"), isSelected = m.filter == ClientsQuickFilter.active, tone = Theme.lime, dot = Theme.lime) { m.toggle(ClientsQuickFilter.active) }
    if (m.canSeeOwed) {
        FilterChip(title = L("Owe money"), isSelected = m.filter == ClientsQuickFilter.owing, tone = Theme.pink, icon = "circle-dollar-sign") { m.toggle(ClientsQuickFilter.owing) }
    }
    FilterChip(title = L("Due this week"), isSelected = m.filter == ClientsQuickFilter.due, tone = Theme.mango, icon = "bell-ring") { m.toggle(ClientsQuickFilter.due) }
    // The archived ones are off every other filter: this is where they are found (and restored from their page).
    if ((m.summary?.archived ?: 0) > 0 || m.filter == ClientsQuickFilter.archived) {
        FilterChip(title = L("Archived"), isSelected = m.filter == ClientsQuickFilter.archived, tone = Theme.inkSoft, icon = "archive") { m.toggle(ClientsQuickFilter.archived) }
    }
    if (m.canAssignAnyone) {
        ChipRule()
        val assigneeTitle = when (m.assignee) {
            "all" -> L("Anyone")
            "unassigned" -> L("Unassigned")
            else -> m.members.firstOrNull { it.id == m.assignee }?.label ?: L("Anyone")
        }
        AppMenu(
            label = { FilterMenuLabel(title = assigneeTitle, selected = m.assignee != "all") },
            accessibilityLabel = L("Assignee"),
        ) {
            picker(
                L("Assignee"),
                options = listOf("all" to L("Anyone"), "unassigned" to L("Unassigned")) +
                    m.members.map { member -> member.id to (if (member.isCurrentMember == true) "${member.label} (${L("You")})" else member.label) },
                selection = stateOf({ m.assignee }, { m.assignee = it; m.reload() }),
            )
        }
    }
}

/** The other last-visit ranges ("over a year ago", "never seen") are rarer than a chip each deserves. */
private val clientsLastVisitRanges: List<Pair<String, String>>
    get() = listOf("30d" to L("Last 30 days"), "90d" to L("Last 3 months"), "1y" to L("Last year"), "over_1y" to L("Over a year ago"), "never" to L("Never seen"))

@Composable
private fun ClientsPatientChips(m: ClientsDirectoryModel) {
    FilterChip(title = L("All"), isSelected = !m.patientQuickFiltered, tone = Theme.aqua) {
        m.lastVisit = null; m.vaccinesOverdue = false; m.linked = null; m.archivedPets = false; m.reload()
    }
    FilterChip(title = L("Vaccines overdue"), isSelected = m.vaccinesOverdue, tone = Theme.mango, icon = "syringe") { m.vaccinesOverdue = !m.vaccinesOverdue; m.reload() }
    FilterChip(title = L("Seen in 30 days"), isSelected = m.lastVisit == "30d", tone = Theme.blue, icon = "calendar-check") { m.lastVisit = if (m.lastVisit == "30d") null else "30d"; m.reload() }
    FilterChip(title = L("On PawMiaw"), isSelected = m.linked == true, tone = Theme.lime, icon = "link-2") { m.linked = if (m.linked == true) null else true; m.reload() }
    FilterChip(title = L("Not on PawMiaw"), isSelected = m.linked == false, tone = Theme.lavender, dot = Theme.lavender) { m.linked = if (m.linked == false) null else false; m.reload() }
    if ((m.patientSummary?.archived ?: 0) > 0 || m.archivedPets) {
        FilterChip(title = L("Archived"), isSelected = m.archivedPets, tone = Theme.inkSoft, icon = "archive") { m.archivedPets = !m.archivedPets; m.reload() }
    }
    ChipRule()
    Segmented(
        options = listOf(
            Segmented.Option("", L("All")),
            Segmented.Option("dog", L("Dogs")),
            Segmented.Option("cat", L("Cats")),
            Segmented.Option("other", L("Others")),
        ),
        selection = stateOf({ m.species ?: "" }, { m.species = it.ifEmpty { null }; m.reload() }),
        look = Segmented.Look.raised,
        label = L("Species"),
    )
    val ranges = clientsLastVisitRanges
    AppMenu(
        label = {
            FilterMenuLabel(
                title = m.lastVisit?.let { key -> ranges.firstOrNull { it.first == key }?.second } ?: L("Any last visit"),
                selected = m.lastVisit != null,
            )
        },
        accessibilityLabel = L("Last visit"),
    ) {
        picker(
            L("Last visit"),
            options = listOf("" to L("Any last visit")) + ranges,
            selection = stateOf({ m.lastVisit ?: "" }, { m.lastVisit = it.ifEmpty { null }; m.reload() }),
        )
    }
}

/** "Vencen esta semana": a mango band with the way to Due. */
@Composable
private fun ClientsDueStrip() {
    val nav = LocalNavigator.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.mango, 12.0))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(L("Clients with a vaccine, recheck or follow-up due within a week, or overdue."), style = Theme.text(14), color = Theme.inkStrong)
        Row(
            Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.due)) },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L("Remind and book from Due"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            Glyph("chevron-right", 16.dp, tint = Theme.blue)
        }
    }
}

// MARK: Rows

@Composable
private fun ClientsDirectoryList(m: ClientsDirectoryModel) {
    val nav = LocalNavigator.current
    if (m.view == ClientsDirectoryView.clients) {
        val pager = m.clients
        ClientsCardRows(pager = pager, empty = { ClientsEmpty(m) }) { client ->
            ClientsClientRow(client = client, needle = m.needle.lowercase(), showOwed = m.canSeeOwed) { nav.push(ProviderRoute.client(client.id)) }
        }
        ClientsCountFooter(pager.total, isLoading = pager.isLoading, loaded = pager.loadedOnce && pager.items.isNotEmpty()) { LP(it, "%d client", "%d clients") }
    } else {
        val pager = m.patients
        ClientsCardRows(pager = pager, empty = { ClientsPatientsEmpty(m) }) { pet ->
            ClientsPatientRowView(pet = pet) { nav.push(ProviderRoute.clientPet(clientId = pet.client.id, petId = pet.id)) }
        }
        ClientsCountFooter(pager.total, isLoading = pager.isLoading, loaded = pager.loadedOnce && pager.items.isNotEmpty()) { LP(it, "%d patient", "%d patients") }
    }
}

/** How many, under the list (as the Agenda does): the server's total. */
@Composable
private fun ClientsCountFooter(total: Int?, isLoading: Boolean, loaded: Boolean, text: (Int) -> String) {
    if (loaded && total != null && total > 0) {
        RowDivider()
        Text(
            if (isLoading) L("Updating…") else text(total),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = Theme.text(14, FontWeight.Bold).monospacedDigit(),
            color = Theme.inkStrong,
        )
    }
}

@Composable
private fun ClientsEmpty(m: ClientsDirectoryModel) {
    val needle = m.needle
    val filter = m.filter
    val title = if (needle.isNotEmpty()) LF("Nothing matches “%@”", needle) else when (filter) {
        ClientsQuickFilter.owing -> L("Nobody owes money")
        ClientsQuickFilter.due -> L("Nobody is due this week")
        ClientsQuickFilter.leads -> L("No leads right now")
        ClientsQuickFilter.active -> L("No active clients yet")
        ClientsQuickFilter.archived -> L("Nobody is archived")
        null -> L("Nobody here yet")
    }
    val message = when {
        (filter == ClientsQuickFilter.leads || filter == ClientsQuickFilter.active) && needle.isEmpty() -> L("A lead becomes active after a first visit, stay or payment.")
        filter == ClientsQuickFilter.archived && needle.isEmpty() -> L("Archived clients and pets show here. They can be restored from their page.")
        filter != null -> L("Try another filter, or show everyone.")
        else -> L("Try the pet’s name, or switch to Patients.")
    }
    val icon = when {
        needle.isNotEmpty() -> "search"
        filter == ClientsQuickFilter.owing -> "circle-dollar-sign"
        filter == ClientsQuickFilter.due -> "bell-ring"
        else -> "users-round"
    }
    EmptyState(systemImage = icon, title = title, message = message, actionTitle = if (filter != null) L("Show everyone") else null, action = { m.setFilter(null) }, accent = Theme.lime)
}

@Composable
private fun ClientsPatientsEmpty(m: ClientsDirectoryModel) {
    val needle = m.needle
    val searching = needle.isNotEmpty() || m.patientFiltered
    EmptyState(
        systemImage = if (needle.isEmpty()) "paw-print" else "search",
        title = if (needle.isNotEmpty()) LF("Nothing matches “%@”", needle) else if (m.patientFiltered) L("No pets match these filters") else L("No patients yet"),
        message = if (searching) L("Try the owner’s name, a phone or the microchip, or clear the filters.") else L("Pets appear here when you add a client or a family connects."),
        actionTitle = if (searching) L("Clear filters") else null,
        action = { m.clearPatientFilters() },
        accent = Theme.lime,
    )
}

// MARK: - Rows inside a card

/**
 * A paged list drawn inside a card: rows edge to edge with hairlines between
 * them, 20 at a time with infinite scroll (the kit's `PagedContent` spaces
 * its rows apart as cards; this one is the web's table body).
 */
@Composable
private fun <Item> ClientsCardRows(
    pager: Pager<Item>,
    empty: @Composable () -> Unit,
    row: @Composable (Item) -> Unit,
) {
    val error = pager.error
    when {
        !pager.loadedOnce && pager.isLoading -> LoadingState()
        error != null && pager.items.isEmpty() -> ErrorState(message = error) { pager.reload() }
        pager.items.isEmpty() -> empty()
        else -> Column(Modifier.fillMaxWidth()) {
            pager.items.forEachIndexed { index, item ->
                key(pager.id(item)) {
                    if (index > 0) RowDivider()
                    Box(Modifier.fillMaxWidth().onScrolledIntoView { pager.loadMoreIfNeeded(item) }) { row(item) }
                }
            }
            PagerFooter(pager)
        }
    }
}

/** One of the table's columns under a phone row: its uppercase head, then the value. */
@Composable
private fun ClientsRowFact(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = brandText(10.6, FontWeight.ExtraBold).tracking(1.27),
            color = Theme.inkSoft,
            maxLines = 1,
            softWrap = false,
        )
        value()
    }
}

/** A whole row that opens something on a tap (SwiftUI `.contentShape(Rectangle()).onTapGesture`), with its named action. */
@Composable
private fun Modifier.clientsRowTap(actionLabel: String, open: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .clickable(interactionSource = source, indication = null, onClick = open)
        .semantics { customActions = listOf(CustomAccessibilityAction(actionLabel) { open(); true }) }
}

// MARK: - Shared pieces (the client's status, the PawMiaw badge, a pet chip)

/**
 * "Prospecto" / "Archivado": shown only when a client is not simply active (web
 * `ClientStatusPill`): the kit's tag in the status's tone, a lead lavender, the rest grey.
 * (Swift returns the `TagChip?`; here it draws it, or nothing.)
 */
@Composable
fun clientsStatusTag(status: String?, deceased: Boolean = false, modifier: Modifier = Modifier) {
    val text: String = when (status) {
        "lead" -> L("Lead")
        "archived" -> if (deceased) L("Deceased") else L("Archived")
        "inactive" -> L("Inactive")
        else -> null
    } ?: return
    TagChip(text = text, tone = if (status == "lead") Theme.lavender else Theme.borderSoft, modifier = modifier)
}

/**
 * "Familia vinculada" / "Solo en la clínica" (web provider-client-link-badge, xs): the one badge
 * for whether a client's family is on PawMiaw, on Clients' pages and on Records'.
 */
@Composable
fun ClientsLinkBadge(linked: Boolean, modifier: Modifier = Modifier) {
    // iOS `.accessibilityHint`: read after the badge.
    val hint = if (linked) L("This client is attached to a real Paw Miaw household.") else L("This client only exists inside this clinic workspace.")
    TagChip(
        text = if (linked) L("Linked household") else L("Clinic-only"),
        icon = if (linked) "link-2" else "building-2",
        linked = linked,
        modifier = modifier.semantics(mergeDescendants = true) { stateDescription = hint },
    )
}

// MARK: - A client row

/**
 * One row per family (web `ClientRows`): the 40 initials, the name in 14
 * bold, the status pill and the PawMiaw badge; every pet as a chip that opens
 * its chart; then the table's other columns. The whole row opens the client.
 */
@Composable
private fun ClientsClientRow(client: ClientsClient, needle: String, showOwed: Boolean, open: () -> Unit) {
    val nav = LocalNavigator.current
    // An archived client's pets are archived with it: they are still its pets.
    val pets = if (client.isArchived) client.allPets else client.allPets.filter { !it.isArchived }
    Column(
        Modifier
            .fillMaxWidth()
            .clientsRowTap(LF("Open %@", client.ownerName), open)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = client.ownerName, imageURL = client.photoUrl, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(client.ownerName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowLayout(spacing = 4.dp) {
                    clientsStatusTag(client.status)
                    ClientsLinkBadge(linked = client.isLinked)
                    // "Se desconectó de PawMiaw el {fecha}": an unlink cleared the family (docs/family-money.md §1).
                    ClientsDisconnectedMark(disconnectedAt = client.disconnectedAt, disconnectedBy = client.disconnectedBy)
                }
            }
            Spacer(Modifier.width(8.dp))
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
        Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pets.isEmpty()) {
                Text("—", style = Theme.text(14), color = Theme.inkSoft)
            } else {
                FlowLayout(spacing = 6.dp) {
                    pets.forEach { pet ->
                        val hint = listOfNotNull(ClientsText.species(pet.petSpecies), pet.petBreed.nonEmpty).joinToString(" · ")
                        val openPet = { nav.push(ProviderRoute.clientPet(clientId = client.id, petId = pet.id)) }
                        PetChip(
                            name = pet.petName,
                            species = pet.petSpecies,
                            photoURL = pet.photoUrl,
                            highlighted = needle.isNotEmpty() && pet.petName.lowercase().contains(needle),
                            modifier = Modifier
                                .plainButton(onClick = openPet)
                                .semantics { onClick(label = hint) { openPet(); true } },
                        )
                    }
                }
            }
            // The table's columns: each head reads in full ("ÚLTIMA VISITA"); they wrap rather than cut.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                FlowLayout(spacing = 16.dp, lineSpacing = 8.dp, modifier = Modifier.weight(1f)) {
                    ClientsRowFact(L("Last visit")) {
                        Text(
                            if (client.activity?.lastVisitAt == null) "—" else ClientsText.shortDay(client.activity?.lastVisitAt),
                            style = Theme.text(14),
                            color = Theme.inkSoft,
                        )
                    }
                    ClientsRowFact(L("Next visit")) {
                        val next = client.activity?.nextVisitAt
                        if (next != null) {
                            Text(ClientsText.shortDay(next), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        } else {
                            Text("—", style = Theme.text(14), color = Theme.inkSoft)
                        }
                    }
                    if (showOwed) {
                        ClientsRowFact(L("Owes")) {
                            val owed = client.activity?.owedAmount
                            if (owed != null && owed > 0) {
                                Text(Money.format(owed, currency = client.activity?.currency), style = Theme.text(14, FontWeight.Bold), color = Theme.destructive)
                            } else {
                                Text("—", style = Theme.text(14), color = Theme.inkSoft)
                            }
                        }
                    }
                }
                ContactButtons(phone = client.ownerPhone)
            }
            client.notes.nonEmpty?.let { notes ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Glyph("sticky-note", 12.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                    Text(notes, style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// MARK: - A patient row

/**
 * One row per patient, its owner under it (web `PatientRows`): the 40 face,
 * the name in 14 bold with "Alergia", "Archivada" and "Vacuna vencida", the
 * profile line; then the owner and the table's other columns. The row opens
 * the chart; the owner opens the client.
 */
@Composable
private fun ClientsPatientRowView(pet: ClientsPatientRow, open: () -> Unit) {
    val nav = LocalNavigator.current
    val activity = pet.activity
    val next = ClientsNext.of(activity)
    val allergies = activity?.allergies ?: emptyList()
    Column(
        Modifier
            .fillMaxWidth()
            .clientsRowTap(LF("Open %@", pet.petName), open)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PetAvatar(name = pet.petName, species = pet.petSpecies, photoURL = pet.photoUrl, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowLayout(spacing = 6.dp) {
                    Text(pet.petName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (allergies.isNotEmpty()) {
                        val listed = allergies.joinToString(", ")
                        Chip(text = L("Allergy"), tone = Chip.Tone.danger, icon = "triangle-alert", modifier = Modifier.semantics { stateDescription = listed })
                    }
                    if (pet.status == "archived") {
                        Chip(text = if (pet.deceasedAt != null) L("Deceased") else L("Archived"), tone = Chip.Tone.muted, icon = "archive")
                    }
                    if (pet.vaccinesOverdue == true) {
                        Chip(text = L("Vaccine overdue"), tone = Chip.Tone.warn, icon = "syringe")
                    }
                }
                val line = listOfNotNull(
                    ClientsText.species(pet.petSpecies),
                    pet.petBreed.nonEmpty,
                    ClientsText.age(pet.birthDate),
                    pet.microchipNumber?.let { LF("Chip %@", it) },
                ).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
        Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                Row(
                    Modifier.plainButton { nav.push(ProviderRoute.client(pet.client.id)) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = pet.client.name, imageURL = pet.client.photoUrl, size = 28.dp)
                    Column {
                        Text(pet.client.name ?: "—", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (pet.linked == true) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph("link-2", 12.dp, tint = Theme.blue)
                                Text("PawMiaw", style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
                            }
                        }
                    }
                }
                }
                ContactButtons(phone = pet.client.phone)
            }
            FlowLayout(spacing = 16.dp, lineSpacing = 8.dp) {
                ClientsRowFact(L("Last visit")) {
                    Text(
                        if (activity?.lastVisitAt == null) L("Never") else ClientsText.shortDay(activity?.lastVisitAt),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
                ClientsRowFact(L("Coming up")) {
                    if (next != null) {
                        val strong = if (next.late) Theme.destructive else Theme.inkStrong
                        val soft = Theme.inkSoft
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append(ClientsText.shortDay(next.at)) }
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
        }
    }
}

/** What comes next for a pet: its next visit or the next thing due, whichever is sooner. */
object ClientsNext {
    data class Entry(val at: String, val what: String, val isVisit: Boolean, val late: Boolean)

    fun of(activity: ClientsPetActivity?): Entry? {
        if (activity == null) return null
        val visit = activity.nextVisitAt
        if (visit != null && (activity.nextDueAt == null || visit < (activity.nextDueAt ?: ""))) {
            return Entry(at = visit, what = L("Visit"), isVisit = true, late = false)
        }
        val due = activity.nextDueAt
        if (due != null) {
            val what = activity.nextDueTitle?.let { ClientsText.followUpTitle(it) } ?: L("Follow-up")
            return Entry(at = due, what = what, isVisit = false, late = ClientsText.isPast(due))
        }
        return null
    }
}
