package com.pawmiaw.business.search

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

//
//  SearchScreen.kt (iOS SearchScreen.swift)
//
//  The workspace's global search (web components/app/search/provider-search.tsx,
//  the palette): the search box with Cerrar on top, then — before typing — what
//  this device remembers, the quick actions and the pages this member may open;
//  once typed, patients, patients in the hospital, clients, visits and bills
//  from `GET /provider-search` (each group only when the role opens it, with
//  "see all" when there are more), then the actions that match. Under two
//  characters nothing is asked; the last answer stays while the next loads.
//  Return opens the highlighted row (the first one); a hardware keyboard's
//  ↑ ↓ move it and Esc closes.
//

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    /** Where the chosen row leads; null = closed without choosing. */
    onLeave: (SearchDestination?) -> Unit,
) {
    val workspace = Workspace.shared
    val session = SessionStore.shared
    var text by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var response by remember { mutableStateOf<SearchResponse?>(null) }
    // Whose answer it is: another business's never shows.
    var responseClinic by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    // The words whose search failed (after one retry).
    var failedQuery by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf(SearchRecents()) }
    // The highlighted row; null = the first.
    var activeKey by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val rows = remember { HashMap<String, BringIntoViewRequester>() }

    val clinicId = workspace.current?.clinicId
    val storageKey = SearchRecents.key(userId = session.user?.id, clinicId = clinicId)
    val allowed = SearchRules.allowedGroups(workspace)
    val canSearch = allowed.isNotEmpty()
    val typed = text.trim()
    val debouncedQuery = SearchText.searchable(debounced)
    val fetchKey = "${clinicId ?: ""}|${debouncedQuery ?: ""}|$retries"

    // An answer for other words (kept while the next one loads) still shows; one for no words doesn't.
    val results: SearchResponse? = if (SearchText.searchable(text) != null && canSearch && responseClinic == clinicId) response else null
    val waiting = SearchText.searchable(text) != null && canSearch && (typed != debounced.trim() || fetching)
    val isError = failedQuery != null && failedQuery == debouncedQuery

    val visible = SearchRecents(items = recents.items.filter { allowed.contains(it.kind.group) }, queries = recents.queries)
    val groups = SearchRules.groups(
        text = text,
        results = results,
        actions = SearchRules.availableActions(workspace),
        recents = visible,
        moreGroups = SearchRules.moreGroups(workspace),
        translated = { L(it) },
    )
    val options = groups.flatMap { it.options }
    val activeID = options.firstOrNull { it.id == activeKey }?.id ?: options.firstOrNull()?.id

    fun setText(value: String) {
        val next = if (value.length > 100) value.take(100) else value
        if (next == text) return
        text = next
        // A new text starts at the top.
        activeKey = null
    }

    fun move(step: Int) {
        if (options.isEmpty()) return
        val current = options.indexOfFirst { it.id == activeKey }.takeIf { it >= 0 } ?: 0
        activeKey = options[(current + step + options.size) % options.size].id
    }

    fun rememberChoice(option: SearchOption) {
        var next = recents
        option.recent?.let { next = next.remembering(it) }
        val query = debouncedQuery
        if (query != null) {
            when (option) {
                is SearchOption.recentItem, is SearchOption.action -> {}
                else -> next = next.remembering(query = query)
            }
        }
        if (next == recents) return
        recents = next
        next.write(storageKey)
    }

    fun choose(option: SearchOption) {
        val destination = SearchRules.destination(option, workspace = workspace) ?: return
        if (destination is SearchDestination.query) {
            setText(destination.query)
            return
        }
        rememberChoice(option)
        onLeave(destination)
    }

    // MARK: Asking the server

    suspend fun fetch() {
        val query = debouncedQuery
        if (!canSearch || query == null) {
            fetching = false
            return
        }
        fetching = true
        failedQuery = null
        // One retry, as the web's query does.
        for (attempt in 0 until 2) {
            try {
                val answer: SearchResponse = API.get("/provider-search", mapOf("q" to query, "limit" to SearchText.limit.toString()))
                response = answer
                responseClinic = clinicId
                fetching = false
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0) delay(1000)
            }
        }
        response = null
        failedQuery = query
        fetching = false
    }

    // Keeps the highlighted row when the list changes under it (results arriving), else the first.
    val ids = options.map { it.id }
    LaunchedEffect(ids) {
        val key = activeKey
        if (key != null && !ids.contains(key)) activeKey = null
    }
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        rows[key]?.bringIntoView()
    }
    LaunchedEffect(text) {
        delay(180)
        debounced = text
    }
    LaunchedEffect(fetchKey) { fetch() }
    LaunchedEffect(storageKey) { recents = SearchRecents.read(storageKey) }
    LaunchedEffect(Unit) {
        // After the cover has slid in, so the keyboard comes up with it.
        delay(350)
        runCatching { focus.requestFocus() }
    }

    // SwiftUI `.scrollDismissesKeyboard(.interactively)`: a drag of the list puts the keyboard away.
    val dismissKeyboard = remember(keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) keyboard?.hide()
                return Offset.Zero
            }
        }
    }

    Column(modifier.fillMaxSize().background(Theme.surface)) {
        // MARK: The box
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SearchField(
                        prompt = L("Search patients, clients, visits, invoices…"),
                        text = stateOf({ text }, { setText(it) }),
                        modifier = Modifier
                            .focusRequester(focus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> { move(1); true }
                                    Key.DirectionUp -> { move(-1); true }
                                    Key.Enter, Key.NumPadEnter -> {
                                        options.firstOrNull { it.id == activeID }?.let { choose(it) }
                                        true
                                    }
                                    Key.Escape -> { onLeave(null); true }
                                    else -> false
                                }
                            },
                    )
                    // While it looks, a spinner stands where the magnifier is.
                    if (waiting) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 15.dp)
                                .size(18.dp)
                                .background(Theme.inputFill, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Theme.blue, strokeWidth = 2.dp)
                        }
                    }
                }
                Text(
                    L("Close"),
                    style = Theme.text(14, FontWeight.Bold),
                    color = Theme.blueText,
                    modifier = Modifier
                        .plainButton { onLeave(null) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Theme.borderSoft))
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(dismissKeyboard)
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
        ) {
            // MARK: Above the rows
            val query = SearchText.searchable(typed)
            if (!canSearch && typed.isNotEmpty()) {
                SearchNotice(L("Your role can’t search patients, clients, visits or invoices. Actions and pages still show here."))
            } else if (typed.isNotEmpty() && query == null) {
                SearchNotice(L("Type at least 2 characters to search patients, clients, visits and invoices."))
            } else if (query != null && isError && results == null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(L("Search isn’t available right now."), style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center)
                    Text(L("Try again"), style = Theme.text(14, FontWeight.Bold), color = Theme.blueText, modifier = Modifier.plainButton { retries += 1 })
                }
            } else if (waiting && results == null) {
                SearchLoadingRows()
            } else if (query != null && canSearch && !waiting && !isError && !(results?.hasResults ?: false)) {
                EmptyState(
                    systemImage = "search",
                    title = LF("No results for “%@”", typed),
                    message = L("Try a name, a phone number, a microchip or a folio."),
                    accent = Theme.aqua,
                )
            }

            // MARK: The groups
            groups.forEach { group ->
                Column(Modifier.padding(top = 8.dp)) {
                    Row(
                        Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.id.title.uppercase(appLocale),
                            style = Theme.text(10.9, FontWeight.ExtraBold).copy(letterSpacing = (10.9 * 0.14).sp),
                            color = Theme.inkSoft,
                        )
                        Spacer(Modifier.weight(1f))
                        if (group.id == SearchGroupID.recent) {
                            Text(
                                L("Clear"),
                                style = Theme.text(12, FontWeight.Bold),
                                color = Theme.inkSoft,
                                modifier = Modifier
                                    .plainButton {
                                        recents = SearchRecents()
                                        recents.write(storageKey)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    group.options.forEach { option ->
                        key(option.id) {
                            val requester = remember { BringIntoViewRequester() }
                            DisposableEffect(requester) {
                                rows[option.id] = requester
                                onDispose { if (rows[option.id] === requester) rows.remove(option.id) }
                            }
                            SearchOptionRow(
                                option = option,
                                active = option.id == activeID,
                                modifier = Modifier.bringIntoViewRequester(requester),
                            ) { choose(option) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNotice(message: String) {
    Text(
        message,
        style = Theme.text(14),
        color = Theme.inkSoft,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
    )
}

// MARK: - One row

@Composable
private fun SearchOptionRow(option: SearchOption, active: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    val wash = Theme.wash(Theme.blue, 12.0)
    Box(
        modifier
            .fillMaxWidth()
            .semantics { selected = active }
            .plainButton { action() }
            .then(if (active) Modifier.background(wash, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        when (option) {
            is SearchOption.pet -> PetRow(option.pet)
            is SearchOption.hospital -> HospitalRow(option.patient)
            is SearchOption.client -> ClientRow(option.client)
            is SearchOption.visit -> VisitRow(option.visit)
            is SearchOption.invoice -> InvoiceRow(option.invoice)
            is SearchOption.more -> SearchRow(title = moreTitle(option.group, option.query), icon = { Tile("arrow-right") })
            is SearchOption.recentQuery -> SearchRow(title = option.query, icon = { Tile("history") })
            is SearchOption.recentItem -> RecentRow(option.item)
            is SearchOption.action -> {
                val action = option.action
                SearchRow(
                    title = L(action.label),
                    icon = { Tile(action.icon) },
                    trailing = if (action.kind == SearchAction.Kind.page) {
                        { Glyph("arrow-right", 16.dp, tint = Theme.inkSoft) }
                    } else null,
                )
            }
        }
    }
}

// A patient: the face, "Perro · Mestizo · Dueño: Ana", the microchip when it matched.
@Composable
private fun PetRow(pet: SearchPet) {
    val subtitle = listOf(species(pet.species), pet.breed, pet.ownerName.nonEmpty?.let { LF("Owner: %@", it) })
    val chip = if (pet.matchedBy == "microchip") pet.microchip.nonEmpty else null
    val archived = pet.status == "archived"
    SearchRow(
        title = pet.name,
        subtitle = joined(subtitle),
        icon = { PetAvatar(name = pet.name, species = pet.species, photoURL = pet.photoUrl, size = 36.dp) },
        trailing = if (chip != null || archived) {
            {
                if (chip != null) Chip(text = LF("Microchip %@", chip), tone = Chip.Tone.info)
                if (archived) Chip(text = L("Archived"))
            }
        } else null,
    )
}

// A patient waiting for admission or in the hospital.
@Composable
private fun HospitalRow(patient: SearchHospitalPatient) {
    val place = joined(listOf(patient.roomName, patient.cageLabel.nonEmpty?.let { LF("Cage %@", it) }))
    val state = if (patient.status == "pending") L("Waiting for admission") else L("In the hospital")
    val triage: Pair<String, Chip.Tone>? = when (patient.triagePriority) {
        "critical" -> L("Critical") to Chip.Tone.danger
        "urgent" -> L("Urgent") to Chip.Tone.danger
        "stable" -> L("Stable") to Chip.Tone.muted
        else -> if (patient.emergency == true) L("Emergency") to Chip.Tone.danger else null
    }
    val pending = patient.status == "pending"
    SearchRow(
        title = patient.petName,
        subtitle = joined(listOf(state, place, patient.clientName, patient.locationName)),
        icon = { PetAvatar(name = patient.petName, species = patient.petSpecies, photoURL = patient.petPhotoUrl, size = 36.dp) },
        trailing = if (triage != null || pending) {
            {
                if (triage != null) Chip(text = triage.first, tone = triage.second)
                if (pending) Chip(text = L("To admit"))
            }
        } else null,
    )
}

// A client: initials, the contact that matched, their pets.
@Composable
private fun ClientRow(client: SearchClient) {
    val contact = if (client.matchedBy == "email") (client.email.nonEmpty ?: client.phone.nonEmpty) else (client.phone.nonEmpty ?: client.email.nonEmpty)
    val names = (client.pets ?: emptyList()).map { it.name }
    val extra = (client.petCount ?: names.size) - names.size
    val pets = when {
        names.isEmpty() -> L("No pets yet")
        extra > 0 -> LF("%@ +%d", names.joinToString(", "), extra)
        else -> names.joinToString(", ")
    }
    SearchRow(
        title = client.name,
        icon = { Avatar(name = client.name, imageURL = client.photoUrl, size = 36.dp, tint = Theme.aqua) },
        subtitle = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (contact != null) Text("$contact · ", style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Glyph("paw-print", 12.dp, tint = Theme.inkSoft)
                Text(pets, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailing = if (client.status == "archived") {
            { Chip(text = L("Archived")) }
        } else null,
    )
}

// A visit: "Kira · Consulta", when (in its place's clock), who, where.
@Composable
private fun VisitRow(visit: SearchVisit) {
    val whenText = SearchText.visitWhen(visit.startsAt, timeZone = visit.timeZone)
    val status = visitStatus(visit.status)
    val upcoming = visit.upcoming == true
    SearchRow(
        title = joined(listOf(visit.petName, visit.serviceLabel)),
        subtitle = joined(listOf(whenText, visit.clientName, visit.locationName)),
        icon = { PetAvatar(name = visit.petName, species = visit.petSpecies, photoURL = visit.petPhotoUrl, size = 36.dp) },
        trailing = if (status != null || upcoming) {
            {
                if (status != null) {
                    Chip(text = status.first, tone = status.second)
                } else {
                    Chip(text = L("Upcoming"), tone = Chip.Tone.info)
                }
            }
        } else null,
    )
}

// A bill: folio and client, its kind and day, the total, what is still owed or its state.
@Composable
private fun InvoiceRow(invoice: SearchInvoice) {
    val kind = when (invoice.kind) {
        "sale" -> L("Store sale")
        "deposit" -> L("Deposit")
        else -> L("Bill")
    }
    val day = invoice.date?.let { DateText.parse(it) }?.let { DateText.date(it) }
    val balance = invoice.balanceDueAmount ?: 0
    val owes = balance > 0 && (invoice.status ?: "") !in listOf("paid", "canceled", "draft")
    val status = invoiceStatus(invoice.status)
    SearchRow(
        title = joined(listOf(invoice.folio, invoice.clientName)),
        subtitle = joined(listOf(kind, day, invoice.petName, invoice.locationName)),
        icon = { Tile("receipt") },
        trailing = {
            Text(
                Money.format(invoice.totalAmount, currency = invoice.currency),
                style = Theme.text(14, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                color = Theme.inkStrong,
                maxLines = 1,
            )
            if (owes) {
                Chip(text = LF("Owes %@", Money.format(balance, currency = invoice.currency)), tone = Chip.Tone.danger)
            } else if (status != null) {
                Chip(text = status.first, tone = status.second)
            }
        },
    )
}

// Something opened before on this device.
@Composable
private fun RecentRow(item: SearchRecentItem) {
    val kind = when (item.kind) {
        SearchRecentKind.pet -> L("Patient")
        SearchRecentKind.hospital -> L("In the hospital")
        SearchRecentKind.client -> L("Client")
        SearchRecentKind.visit -> L("Visit")
        SearchRecentKind.invoice -> L("Invoice")
    }
    SearchRow(
        title = item.title,
        subtitle = joined(listOf(kind, item.subtitle)),
        icon = {
            when (item.kind) {
                SearchRecentKind.pet, SearchRecentKind.hospital -> PetAvatar(name = item.title, species = item.species, photoURL = item.photoUrl, size = 36.dp)
                SearchRecentKind.client -> Avatar(name = item.title, imageURL = item.photoUrl, size = 36.dp, tint = Theme.aqua)
                SearchRecentKind.visit -> Tile("calendar-days")
                SearchRecentKind.invoice -> Tile("receipt")
            }
        },
    )
}

/** The web's icon tile: the icon in PAW Blue on a 10% wash of it, 36 round. */
@Composable
private fun Tile(icon: String) {
    IconBadge(icon = icon, accent = Theme.blue, size = 36.dp, iconSize = 16.dp, ink = IconBadge.Ink.accent, washPercent = 10.0)
}

// MARK: Words

private fun joined(parts: List<String?>): String =
    parts.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

private fun moreTitle(group: SearchResultGroup, query: String): String = when (group) {
    SearchResultGroup.pets -> LF("All patients matching “%@”", query)
    SearchResultGroup.hospital -> L("Everyone in the hospital")
    SearchResultGroup.clients -> LF("All clients matching “%@”", query)
    SearchResultGroup.visits -> LF("All visits matching “%@”", query)
    SearchResultGroup.invoices -> LF("All invoices matching “%@”", query)
}

private fun species(value: String?): String = when (value) {
    "dog" -> L("Dog")
    "cat" -> L("Cat")
    "bird" -> L("Bird")
    "rabbit" -> L("Rabbit")
    "fish" -> L("Fish")
    "reptile" -> L("Reptile")
    "small_pet" -> L("Small pet")
    else -> L("Other")
}

private fun visitStatus(status: String?): Pair<String, Chip.Tone>? = when (status) {
    "checked_in" -> L("Arrived") to Chip.Tone.info
    "in_progress" -> L("In progress") to Chip.Tone.info
    "completed" -> L("Completed") to Chip.Tone.muted
    "cancelled" -> L("Cancelled") to Chip.Tone.danger
    "no_show" -> L("No-show") to Chip.Tone.danger
    else -> null
}

/** As Cuentas names a bill's state: the client hasn't paid it yet ("Te deben" counts it). */
private fun invoiceStatus(status: String?): Pair<String, Chip.Tone>? = when (status) {
    "draft" -> L("Draft") to Chip.Tone.muted
    "ready_for_payment", "open" -> L("Unpaid") to Chip.Tone.danger
    "partially_paid" -> L("Part paid") to Chip.Tone.danger
    "overdue" -> L("Overdue") to Chip.Tone.danger
    "paid" -> L("Paid") to Chip.Tone.info
    "canceled" -> L("Canceled") to Chip.Tone.muted
    else -> null
}

/** A row's frame (web `Row`): the 36 icon, the title (14 bold) over its line (12), the chips at the end. */
@Composable
private fun SearchRow(
    title: String,
    icon: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.invoke()
        }
        if (trailing != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), content = trailing)
        }
    }
}

/** A row with a line of words under its title; none when it says nothing. */
@Composable
private fun SearchRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    trailing: (@Composable ColumnScope.() -> Unit)? = null,
) {
    SearchRow(
        title = title,
        icon = icon,
        subtitle = if (subtitle.isEmpty()) null else {
            { Text(subtitle, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailing = trailing,
    )
}

/** While the first answer loads (web `LoadingRows`): three rows of soft blue shapes. */
@Composable
private fun SearchLoadingRows() {
    val pulse = rememberInfiniteTransition(label = "search loading")
    val dim by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "search loading dim",
    )
    val strong = Theme.wash(Theme.blue, 10.0)
    val soft = Theme.wash(Theme.blue, 7.0)
    val capsule = RoundedCornerShape(50)
    Column(
        Modifier
            .padding(horizontal = 4.dp)
            .padding(top = 12.dp)
            .alpha(dim)
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(strong, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(width = 120.dp, height = 12.dp).background(strong, capsule))
                    Box(Modifier.size(width = 180.dp, height = 10.dp).background(soft, capsule))
                }
            }
        }
    }
}
