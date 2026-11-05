package com.pawmiaw.business.services

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import com.pawmiaw.business.*
import com.pawmiaw.business.clients.ClientsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.max

//
//  GroomingTabs.kt (iOS GroomingTabs.swift)
//
//  Estética's other tabs (web: grooming-pets.tsx, grooming-pet-sheet.tsx,
//  grooming-due.tsx, grooming-settings.tsx, care/care-card.tsx): every pet
//  groomed here with its care card and history, who is due back (with the
//  reminder), and how the business grooms.
//

// MARK: - Pets

private val svcGroomSorts: List<Pair<String, String>> = listOf("recent" to "Last groom", "name" to "Name", "due" to "Due first")

/**
 * Every pet groomed here (web `GroomingPets`): one card with the search and the
 * sort, the filter chips, the pets (20 at a time) and the server's count.
 */
@Composable
fun SvcGroomPetsTab(
    canBook: Boolean,
    canEditCard: Boolean,
    canRemind: Boolean,
    push: (ProviderRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val query = remember { mutableStateOf("") }
    var due by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("recent") }
    var counts by remember { mutableStateOf<SvcGroomPetCounts?>(null) }
    var open by remember { mutableStateOf<SvcGroomPetRow?>(null) }
    val pager = remember { Pager<SvcGroomPetRow>(id = { it.id }) { _, _ -> Page(emptyList(), 0) } }

    val reloadKey = "${query.value}|$due|$sort|${workspace.scopeVersion}"
    val filtered = query.value.nonEmpty != null || due != "all"

    suspend fun reload() {
        val q = query.value.nonEmpty
        val filter = if (due == "all") null else due
        val order = sort
        pager.fetch = { offset, limit ->
            val page = API.get<SvcGroomPetsPage>(
                "/provider-grooming/pets",
                mapOf("q" to q, "due" to filter, "sort" to order, "page" to "${offset / limit + 1}", "size" to "$limit"),
            )
            counts = page.counts
            Page(page.items, page.total)
        }
        pager.reload()
    }

    Column(modifier.listCard()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchField(prompt = L("Pet, owner or phone"), text = query, height = 40.dp)
            AppMenu(
                label = { PillField(text = L(svcGroomSorts.firstOrNull { it.first == sort }?.second ?: "Last groom"), height = 40.dp) },
                accessibilityLabel = L("Sort"),
            ) {
                for ((value, title) in svcGroomSorts) {
                    item(L(title), checked = sort == value) { sort = value }
                }
            }
        }
        RowDivider()
        Row(
            Modifier
                .padding(vertical = 10.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(title = L("All"), count = counts?.pets, isSelected = due == "all", tone = Theme.blue) { due = "all" }
            FilterChip(title = L("Overdue"), count = counts?.overdue, isSelected = due == "overdue", tone = Theme.pink, icon = "bell-ring") {
                due = if (due == "overdue") "all" else "overdue"
            }
            FilterChip(title = L("Due soon"), count = counts?.soon, isSelected = due == "soon", tone = Theme.mango, icon = "clock-3") {
                due = if (due == "soon") "all" else "soon"
            }
        }
        RowDivider()

        // The rows.
        if (!pager.loadedOnce && pager.isLoading) {
            LoadingState()
        } else if (pager.error != null && pager.items.isEmpty()) {
            SvcLoadError(message = L("The groomed pets couldn’t be loaded."), modifier = Modifier.padding(16.dp)) { pager.reload() }
        } else if (pager.items.isEmpty()) {
            val q = query.value.nonEmpty
            EmptyState(
                systemImage = if (q != null) "search" else "paw-print",
                title = q?.let { LF("Nothing matches “%@”", it) } ?: (if (due != "all") L("No pets match this filter") else L("No groomed pets yet")),
                message = if (filtered) L("Try the owner’s name or a phone, or show every pet.") else L("A pet appears here after its first groom, or once its care card says how it is groomed."),
                actionTitle = if (filtered) L("Show every pet") else null,
                action = {
                    query.value = ""
                    due = "all"
                },
                accent = Theme.pink,
            )
        } else {
            Column {
                val first = pager.items.firstOrNull()?.id
                for (row in pager.items) {
                    key(row.id) {
                        if (row.id != first) RowDivider()
                        // The row opens the pet's grooming; its photo and name open the chart; "Book" books.
                        SvcGroomPetRowView(
                            row = row,
                            push = push,
                            onBook = if (canBook) ({ push(SvcBook.groom(clientId = row.client.id, petId = row.pet.id, lastService = row.lastServiceLabel)) }) else null,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = LF("Open %@’s grooming", row.pet.name),
                                    role = Role.Button,
                                ) { open = row }
                                .onScrolledIntoView { pager.loadMoreIfNeeded(row) },
                        )
                    }
                }
                PagerFooter(pager)
            }
            val total = pager.total
            if (total != null && total > 0) {
                RowDivider()
                Text(
                    if (pager.isLoading) L("Updating…") else LP(total, "1 pet", "%d pets"),
                    style = Theme.text(14, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                    color = Theme.inkStrong,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    LaunchedEffect(reloadKey) {
        if (query.value.isNotEmpty()) delay(300)
        reload()
    }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { reload() } } }

    open?.let { row ->
        AppSheet(onDismissRequest = { open = null }) {
            // The sheet shows the row as it is now (a saved care card changes its cadence and its due date).
            SvcGroomPetSheet(
                row = pager.items.firstOrNull { it.id == row.id } ?: row,
                canBook = canBook, canEditCard = canEditCard, canRemind = canRemind, push = push,
            )
        }
    }
}

/**
 * One pet of the list (web: the table's row): the pet (photo and name open the
 * chart), its owner, the last groom, how often it comes and when it's due, and Book.
 * `onBook`: "Book" on the row; null when the member can't book.
 */
@Composable
private fun SvcGroomPetRowView(
    row: SvcGroomPetRow,
    push: (ProviderRoute) -> Unit,
    onBook: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PetAvatar(
                name = row.pet.name, species = row.pet.species, photoURL = row.pet.photoUrl, size = 40.dp,
                modifier = Modifier.plainButton { push(ProviderRoute.patientChart(row.pet.id)) },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.pet.name,
                    style = Theme.text(14, FontWeight.Bold),
                    color = Theme.inkStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.plainButton { push(ProviderRoute.patientChart(row.pet.id)) },
                )
                Text(
                    listOfNotNull(row.pet.species?.let { ClientsText.species(it) }.nonEmpty, row.pet.breed.nonEmpty).joinToString(" · "),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(row.client.name, style = Theme.text(14), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SvcHandlingChips(tags = row.handlingTags ?: emptyList(), modifier = Modifier.padding(top = 2.dp))
            }
            if (onBook != null) {
                PMButton(L("Book"), systemImage = "calendar-plus", size = ButtonSize.compact, action = onBook)
            }
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Glyph("chevron-right", 16.dp, tint = Theme.inkSoft) }
        }
        Row(Modifier.padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            SvcRowFact(L("Last groom")) {
                val last = row.lastGroomAt
                if (last != null) {
                    Text(SvcTime.day(last), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    listOfNotNull(row.lastServiceLabel.nonEmpty, row.usualStyle.nonEmpty).joinToString(" · ").nonEmpty?.let { line ->
                        Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(L("Not yet"), style = Theme.text(14), color = Theme.inkSoft)
                    row.usualStyle.nonEmpty?.let { usual ->
                        Text(usual, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            SvcRowFact(L("Every")) {
                val own = row.rebookEveryWeeks
                val business = row.effectiveRebookWeeks
                if (own != null) {
                    Text(SvcTime.cadence(own), style = Theme.text(14), color = Theme.inkStrong)
                } else if (business != null) {
                    // No cadence of its own: the business's, said as such.
                    val hint = L("The business’s default")
                    Text(
                        SvcTime.cadence(business),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                        modifier = Modifier.semantics { stateDescription = hint },
                    )
                } else {
                    Text("—", style = Theme.text(14), color = Theme.inkSoft)
                }
            }
            SvcRowFact(L("Due")) { SvcDueCell(nextBookedAt = row.nextBookedAt, nextGroomDueAt = row.nextGroomDueAt) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SvcRowFact(label: String, content: @Composable () -> Unit) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label, size = 10.6)
        content()
    }
}

/**
 * When the pet is due (web `DueCell`): already booked (never late), or the Due
 * list's words with its colour's dot, and the day under them.
 */
@Composable
private fun SvcDueCell(nextBookedAt: String?, nextGroomDueAt: String?) {
    val days = nextGroomDueAt?.let { SvcTime.daysUntil(it) }
    if (nextBookedAt != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("calendar-check", 14.dp, tint = Theme.blue)
            Text(LF("Booked %@", SvcTime.day(nextBookedAt)), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
        }
    } else if (nextGroomDueAt != null && days != null) {
        val state = if (days < 0) ProviderState.problem else if (days <= 6) ProviderState.waiting else ProviderState.upcoming
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(state.color, CircleShape))
                Text(
                    SvcTime.dueWords(days),
                    style = Theme.text(14, FontWeight.Bold),
                    color = if (days < 0) Theme.destructive else Theme.inkStrong,
                )
            }
            Text(SvcTime.day(nextGroomDueAt), style = Theme.text(12), color = Theme.inkSoft)
        }
    } else {
        Text("—", style = Theme.text(14), color = Theme.inkSoft)
    }
}

// MARK: - One pet's grooming

/**
 * One pet's grooming (web `GroomingPetSheet`): how many grooms, how often it
 * comes back and when it's due, its care card, and every groom with its report.
 */
@Composable
private fun SvcGroomPetSheet(
    row: SvcGroomPetRow,
    canBook: Boolean,
    canEditCard: Boolean,
    canRemind: Boolean,
    push: (ProviderRoute) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val openURL = rememberOpenURL()
    var history by remember { mutableStateOf<List<SvcGroomHistoryItem>?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }

    val whatsApp: String? = if (canRemind) ContactLinks.whatsApp(row.client.phone) else null

    suspend fun loadHistory() {
        try {
            val page = API.get<SvcGroomHistory>("/provider-client-pets/${row.pet.id}/grooms", mapOf("limit" to "20"))
            history = page.items
            historyError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            historyError = errorText(e)
        }
    }

    /** The Agenda's booking with this pet, on its last service. */
    fun book() {
        dismiss()
        push(SvcBook.groom(clientId = row.client.id, petId = row.pet.id, lastService = row.lastServiceLabel))
    }

    ProviderModal(
        title = row.pet.name,
        subtitle = listOfNotNull(row.client.name.nonEmpty, row.pet.species?.let { ClientsText.species(it) }.nonEmpty, row.pet.breed.nonEmpty).joinToString(" · "),
        systemImage = "paw-print",
        accent = ModalAccent.pink,
        primaryTitle = if (canBook) L("Book a groom") else null,
        cancelTitle = L("Close"),
        // Web footer: Book a groom, WhatsApp, Close.
        secondaryActions = whatsApp?.let { link -> listOf(ModalAction(L("WhatsApp"), icon = "message-circle") { openURL(link) }) } ?: emptyList(),
        primaryIcon = "calendar-plus",
        // Web: the pet's own photo in the header's pebble.
        pebbleImageURL = row.pet.photoUrl,
        onPrimary = if (canBook) ({ book() }) else null,
    ) {
        FlowLayout(spacing = 16.dp) {
            SvcPetFact("scissors", L("Grooms here")) { Text("${row.groomCount ?: 0}") }
            SvcPetFact("repeat-2", L("Comes back")) {
                val own = row.rebookEveryWeeks
                val business = row.effectiveRebookWeeks
                if (own != null) {
                    Text(SvcTime.cadence(own))
                } else if (business != null) {
                    val soft = Theme.inkSoft
                    Text(
                        buildAnnotatedString {
                            append(SvcTime.cadence(business))
                            withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = soft)) {
                                append(" · " + L("the business’s default"))
                            }
                        },
                    )
                } else {
                    Text(L("No cadence"))
                }
            }
            SvcPetFact("calendar-plus", L("Due")) { SvcDueCell(nextBookedAt = row.nextBookedAt, nextGroomDueAt = row.nextGroomDueAt) }
        }
        // Web: a blue link after the facts.
        PMButton(L("Open chart"), variant = ButtonVariant.link, size = ButtonSize.small) {
            dismiss()
            push(ProviderRoute.patientChart(row.pet.id))
        }

        SvcCareCard(petId = row.pet.id, petName = row.pet.name, canEdit = canEditCard, photoURL = row.pet.photoUrl, species = row.pet.species)

        ModalSection(title = L("Grooming history"), systemImage = "history", tint = Theme.aqua) {
            val shown = history
            if (shown != null) {
                if (shown.isEmpty()) {
                    EmptyState(
                        systemImage = "scissors",
                        title = L("No grooms here yet"),
                        message = L("Each groom is kept here with its report and its before and after photos."),
                        accent = Theme.pink,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        shown.forEachIndexed { index, item ->
                            key(item.id) { SvcGroomHistoryRow(item = item, startsOpen = index == 0) }
                        }
                    }
                }
            } else if (historyError != null) {
                SvcLoadError(message = L("The history couldn’t be loaded.")) { loadHistory() }
            } else {
                LoadingState()
            }
        }
    }

    LaunchedEffect(Unit) { loadHistory() }
}

/** A fact of the pet's grooming (web `Fact`): a pink disc, the micro-label, the value. */
@Composable
private fun SvcPetFact(icon: String, label: String, value: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).background(Theme.wash(Theme.pink, 20.0), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(icon, 16.dp, tint = Theme.inkStrong)
        }
        Column {
            Eyebrow(label, size = 10.6)
            CompositionLocalProvider(LocalTextStyle provides Theme.text(14, FontWeight.Bold), LocalContentColor provides Theme.inkStrong) {
                value()
            }
        }
    }
}

/** One groom: when, what, who, what was asked — and the report the family got, photos included. */
@Composable
private fun SvcGroomHistoryRow(item: SvcGroomHistoryItem, startsOpen: Boolean) {
    var open by remember { mutableStateOf(startsOpen) }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(200, easing = EaseOut), label = "report chevron")
    val statusLabel: String? = when (item.status) {
        "scheduled" -> L("Booked")
        "checked_in" -> L("Here now")
        "in_progress" -> L("In progress")
        "cancelled" -> L("Cancelled")
        "no_show" -> L("No-show")
        else -> null
    }
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(14.dp),
    ) {
        FlowLayout(spacing = 8.dp) {
            Text(SvcTime.day(item.at, withYear = true), style = brandHeading(18), color = Theme.inkStrong)
            if (statusLabel != null) Chip(text = statusLabel, tone = Chip.Tone.muted)
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item.serviceLabel.nonEmpty?.let { Text(it, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong) }
            item.groomerName.nonEmpty?.let { groomer ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("user-round", 14.dp, tint = Theme.inkSoft)
                    Text(groomer, style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
        item.requestSummary.nonEmpty?.let { asked ->
            SvcAskedBox(text = asked, lineLimit = null, modifier = Modifier.padding(top = 8.dp))
        }
        val report = item.report
        if (report != null && SvcReportText.hasContent(report)) {
            val chip = RoundedCornerShape(Theme.chipRadius)
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, chip)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().plainButton { open = !open },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("chevron-right", 14.dp, tint = Theme.inkStrong, modifier = Modifier.rotate(turn))
                    Text(L("Report"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                }
                if (open) SvcReportView(report = report)
            }
        } else if (item.status == "completed") {
            Text(L("No report was written."), style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// MARK: - The care card

/**
 * What a groomer or walker keeps about the pet (web `CareCard`): how to handle
 * them first, then the cut, the tools and products, the notes and how often they
 * come back, and (on a groom) the last groom's report.
 * `grooming`: a groomer's card (the cut, when it's due back); a walker or a sitter keeps the route and the door.
 * `photoURL`/`species`: with either, the head shows the pet's face; without both, a paw.
 */
@Composable
fun SvcCareCard(
    petId: String,
    petName: String,
    canEdit: Boolean,
    grooming: Boolean = true,
    photoURL: String? = null,
    species: String? = null,
    modifier: Modifier = Modifier,
) {
    var profile by remember { mutableStateOf<SvcCareProfile?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var lastOpen by remember { mutableStateOf(false) }
    val lastTurn by animateFloatAsState(if (lastOpen) 180f else 0f, tween(200, easing = EaseOut), label = "last chevron")

    suspend fun load() {
        try {
            profile = API.get<SvcCareProfile>("/provider-client-pets/$petId/care")
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = errorText(e)
        }
    }

    fun isEmpty(profile: SvcCareProfile): Boolean =
        (profile.handlingTags ?: emptyList()).isEmpty() && profile.groomStyle.nonEmpty == null && profile.groomTools.nonEmpty == null &&
            profile.groomProducts.nonEmpty == null && profile.careNotes.nonEmpty == null

    /** On a groom, "last time" is the last groom — not the walk or the class in between. */
    fun lastReport(profile: SvcCareProfile): SvcCareLastReport? {
        val reports = (profile.lastReports ?: emptyList()).filter { it.report != null }
        return if (grooming) reports.firstOrNull { it.report?.kind == "groom" } else reports.firstOrNull()
    }

    Column(modifier.fillMaxWidth().listCard()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (photoURL != null || species != null) {
                PetAvatar(name = petName, species = species, photoURL = photoURL, size = 32.dp)
            } else {
                Glyph("paw-print", 20.dp, tint = Theme.blue)
            }
            Text(
                LF("Care card · %@", petName),
                style = brandHeading(20),
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val shown = profile
            if (canEdit && shown != null && !isEmpty(shown)) {
                PMButton(L("Edit"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact) { editing = true }
            }
        }
        RowDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shown = profile
            if (shown != null) {
                // How to handle them first.
                val tags = shown.handlingTags ?: emptyList()
                val warnings = tags.filter { SvcHandling.warn.contains(it) }
                val others = tags.filter { !SvcHandling.warn.contains(it) }
                if (warnings.isNotEmpty()) {
                    FlowLayout(
                        spacing = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Theme.wash(Theme.destructive, 9.0), RoundedCornerShape(Theme.chipRadius))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Glyph("shield-alert", 16.dp, tint = Theme.destructive)
                        for (tag in warnings) {
                            Text(SvcReportText.option(tag), style = Theme.text(14, FontWeight.Bold), color = Theme.destructive)
                        }
                    }
                }
                if (others.isNotEmpty()) {
                    FlowLayout(spacing = 6.dp) {
                        for (tag in others) {
                            Text(
                                SvcReportText.option(tag),
                                style = Theme.text(12, FontWeight.Bold),
                                color = Theme.inkStrong,
                                modifier = Modifier
                                    .background(Theme.surfaceMuted, CapsuleShape)
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (isEmpty(shown)) {
                    // One line and its button: it reads the same at any width.
                    FlowLayout(
                        spacing = 12.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .dashedBorder(1.dp, Theme.mix(Theme.blue, 30.0, Theme.borderSoft), Theme.boxRadius, 5.dp, 4.dp)
                            .padding(14.dp),
                    ) {
                        Box(
                            Modifier.size(36.dp).background(Theme.wash(Theme.aqua, 30.0), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Glyph(if (grooming) "scissors" else "paw-print", 16.dp, tint = Theme.inkStrong)
                        }
                        Text(
                            if (grooming) LF("Nothing written yet. Note the cut, the tools and how %@ likes to be handled — the next person will thank you.", petName)
                            else LF("Nothing written yet. Note the route, the keys and how %@ likes to be handled — the next person will thank you.", petName),
                            style = Theme.text(14),
                            color = Theme.inkSoft,
                        )
                        if (canEdit) {
                            PMButton(L("Write it"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact) { editing = true }
                        }
                    }
                } else {
                    SvcLabelValueGrid(
                        rows = listOfNotNull(
                            shown.groomStyle.nonEmpty?.let { L("Cut") to it },
                            shown.groomTools.nonEmpty?.let { L("Tools") to it },
                            shown.groomProducts.nonEmpty?.let { L("Products") to it },
                            shown.careNotes.nonEmpty?.let { L("Notes") to it },
                        ),
                        labelMinWidth = 88.dp,
                        horizontalSpacing = 12.dp,
                        verticalSpacing = 6.dp,
                    )
                }
                if (grooming) SvcCareDueLine(shown)
                val last = lastReport(shown)
                val report = last?.report
                if (last != null && report != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.chipRadius))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().plainButton { lastOpen = !lastOpen },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("scissors", 14.dp, tint = Theme.inkSoft)
                            Text(
                                LF("Last time: %@, %@", last.serviceLabel ?: "—", SvcTime.day(last.at)),
                                style = Theme.text(14, FontWeight.Bold),
                                color = Theme.inkStrong,
                                modifier = Modifier.weight(1f),
                            )
                            Glyph("chevron-down", 14.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(lastTurn))
                        }
                        if (lastOpen) SvcReportView(report = report, compact = true)
                    }
                }
            } else if (failed != null) {
                SvcLoadError(message = L("The care card couldn’t be loaded.")) { load() }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Theme.inkSoft, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    LaunchedEffect(petId) { load() }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { load() } } }

    val editingProfile = profile
    if (editing && editingProfile != null) {
        AppSheet(onDismissRequest = { editing = false }) {
            SvcCareCardForm(petId = petId, petName = petName, profile = editingProfile, grooming = grooming) { saved -> profile = saved }
        }
    }
}

/** "Next groom booked for…", "Due back around…": red once the due day has passed with nothing booked. */
@Composable
private fun SvcCareDueLine(profile: SvcCareProfile) {
    val weeks = profile.rebookEveryWeeks ?: profile.effectiveRebookWeeks ?: 6
    val due = DateText.parse(profile.nextGroomDueAt)
    val overdue = profile.nextBookedAt == null && (due?.let { it < Instant.now() } ?: false)
    val booked = profile.nextBookedAt
    val dueAt = profile.nextGroomDueAt
    val own = profile.rebookEveryWeeks
    val text = when {
        booked != null -> LF("Next groom booked for %@", SvcTime.day(booked))
        dueAt != null -> LF("Due back around %@ (every %d weeks)", SvcTime.day(dueAt), weeks)
        own != null -> LF("Comes back every %d weeks", own)
        else -> L("No grooming here yet")
    }
    val ink = if (overdue) Theme.destructive else Theme.inkSoft
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph("calendar-clock", 14.dp, tint = ink)
        Text(text, style = Theme.text(12, FontWeight.Bold), color = ink)
    }
}

/**
 * SwiftUI `Grid(alignment: .leadingFirstTextBaseline)` of label/value rows: the labels in one
 * column as wide as the widest (at least `labelMinWidth`), 12 bold grey; the values 14 navy in
 * the rest, each row on its first baseline.
 */
@Composable
private fun SvcLabelValueGrid(
    rows: List<Pair<String, String>>,
    labelMinWidth: Dp,
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            for ((label, value) in rows) {
                Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                Text(value, style = Theme.text(14), color = Theme.inkStrong)
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = horizontalSpacing.roundToPx()
        val rowGap = verticalSpacing.roundToPx()
        val width = constraints.maxWidth
        val labels = measurables.filterIndexed { i, _ -> i % 2 == 0 }.map { it.measure(Constraints(maxWidth = width)) }
        val column = max(labelMinWidth.roundToPx(), labels.maxOfOrNull { it.width } ?: 0).coerceAtMost(width)
        val valueWidth = (width - column - gap).coerceAtLeast(0)
        val values = measurables.filterIndexed { i, _ -> i % 2 == 1 }.map { it.measure(Constraints(maxWidth = valueWidth)) }
        val placements = ArrayList<Triple<Int, Int, Int>>() // label y, value y, row height
        var y = 0
        labels.indices.forEach { i ->
            val lb = labels[i][FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
            val vb = values[i][FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
            val line = max(lb, vb)
            val labelY = y + (line - lb)
            val valueY = y + (line - vb)
            val bottom = max(labelY + labels[i].height, valueY + values[i].height)
            placements.add(Triple(labelY, valueY, bottom))
            y = bottom + rowGap
        }
        val height = if (labels.isEmpty()) 0 else y - rowGap
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            labels.indices.forEach { i ->
                labels[i].placeRelative(0, placements[i].first)
                values[i].placeRelative(column + gap, placements[i].second)
            }
        }
    }
}

/**
 * The care card's form (web `CareProfileSheet`): how to handle the pet, the cut,
 * the tools and products and how often it comes (a groom), the notes.
 */
@Composable
private fun SvcCareCardForm(
    petId: String,
    petName: String,
    profile: SvcCareProfile,
    grooming: Boolean,
    onSaved: (SvcCareProfile) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val style = remember { mutableStateOf(profile.groomStyle ?: "") }
    val tools = remember { mutableStateOf(profile.groomTools ?: "") }
    val products = remember { mutableStateOf(profile.groomProducts ?: "") }
    val notes = remember { mutableStateOf(profile.careNotes ?: "") }
    var tags by remember { mutableStateOf(profile.handlingTags ?: emptyList()) }
    val weeks = remember { mutableStateOf(profile.rebookEveryWeeks?.toString() ?: "") }
    var working by remember { mutableStateOf(false) }
    val form = remember { FormState() }

    val dirty = style.value != (profile.groomStyle ?: "") || tools.value != (profile.groomTools ?: "") || products.value != (profile.groomProducts ?: "") ||
        notes.value != (profile.careNotes ?: "") || tags.toSet() != (profile.handlingTags ?: emptyList()).toSet() ||
        weeks.value != (profile.rebookEveryWeeks?.toString() ?: "")

    fun save() {
        val parsed = weeks.value.trim().toIntOrNull()
        if (!form.check(listOf("weeks" to (if (weeks.value.nonEmpty != null && (parsed ?: 0) !in 1..52) L("Weeks go from 1 to 52.") else null)))) return
        working = true
        scope.launch {
            try {
                val saved = API.put<SvcCareProfile>(
                    "/provider-client-pets/$petId/care",
                    mapOf(
                        "groomStyle" to style.value.nonEmpty,
                        "groomTools" to tools.value.nonEmpty,
                        "groomProducts" to products.value.nonEmpty,
                        "careNotes" to notes.value.nonEmpty,
                        "handlingTags" to SvcHandling.all.filter { it in tags },
                        "rebookEveryWeeks" to (if (weeks.value.nonEmpty == null) null else parsed),
                    ),
                )
                onSaved(saved)
                Toast.show(L("Care card saved."))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("rebookEveryWeeks" to "weeks"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("%@'s care card", petName),
        subtitle = LF("Kept for everyone in the workspace who works with %@.", petName),
        systemImage = "paw-print",
        accent = ModalAccent.aqua,
        isDirty = dirty,
        form = form,
        primaryTitle = L("Save"),
        isWorking = working,
        cancelTitle = L("Back"),
        onPrimary = { save() },
    ) {
        ModalSection(title = L("Handling")) {
            FlowLayout(spacing = 6.dp) {
                for (tag in SvcHandling.all) {
                    SvcChoiceChip(title = SvcReportText.option(tag), isSelected = tag in tags, danger = SvcHandling.warn.contains(tag)) {
                        tags = if (tag in tags) tags.filter { it != tag } else tags + tag
                    }
                }
            }
        }
        if (grooming) {
            ModalSection(title = L("Grooming")) {
                FieldChrome { PlainTextField(style, prompt = L("Cut: puppy cut, round ears…"), modifier = Modifier.weight(1f)) }
                FieldChrome { PlainTextField(tools, prompt = L("Tools: blade 7F, comb 1/2\"…"), modifier = Modifier.weight(1f)) }
                FieldChrome { PlainTextField(products, prompt = L("Products: oatmeal shampoo…"), modifier = Modifier.weight(1f)) }
                Column(Modifier.formScrollTarget("weeks", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(L("Comes back every"), style = Theme.text(14), color = Theme.inkStrong)
                        FieldChrome(invalid = form.error("weeks") != null, modifier = Modifier.width(80.dp)) {
                            PlainTextField(
                                weeks,
                                prompt = "6",
                                keyboard = KeyboardType.Number,
                                style = Theme.bodyFont.copy(textAlign = TextAlign.Center),
                                modifier = Modifier.weight(1f),
                                onChange = { form.clear("weeks") },
                            )
                        }
                        Text(L("weeks"), style = Theme.text(14), color = Theme.inkStrong)
                    }
                    form.error("weeks")?.let { error ->
                        Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
                    }
                }
            }
        }
        ModalSection(title = LF("Notes for whoever takes %@", petName)) {
            FieldChrome(multiline = true) {
                PlainTextField(notes, prompt = L("Keys, route, feeding, where the leash is…"), lines = 4..10, modifier = Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Due back

private val svcDueBuckets: List<Pair<String, ProviderState>> =
    listOf("overdue" to ProviderState.problem, "this_week" to ProviderState.waiting, "later" to ProviderState.upcoming)

/**
 * Who is due back for grooming (web `GroomingDue`): the Due list's grooming rows
 * in their buckets, with the same reminder and the same booking.
 */
@Composable
fun SvcGroomDueTab(
    canRemind: Boolean,
    canBook: Boolean,
    push: (ProviderRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var windowDays by remember { mutableStateOf(30) }
    var list by remember { mutableStateOf<SvcDueList?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reminding by remember { mutableStateOf<String?>(null) }

    val items = (list?.items ?: emptyList()).filter { it.reason == "grooming" }

    suspend fun load() {
        try {
            list = API.get<SvcDueList>("/provider-due", mapOf("window" to "$windowDays"))
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = errorText(e)
        }
    }

    /** In PawMiaw, or a WhatsApp chat opened with the message; the row then says it was reminded. */
    suspend fun remind(item: SvcDueItem, channel: String) {
        reminding = item.key + channel
        try {
            val followUp = item.followUpId
            val result = if (followUp != null) {
                API.post<SvcRemindResult>("/provider-follow-ups/$followUp/remind", mapOf("channel" to channel, "locale" to svcLocale))
            } else {
                API.post<SvcRemindResult>(
                    "/provider-due/remind",
                    mapOf("providerClientPetId" to item.pet.id, "reason" to "grooming", "channel" to channel, "locale" to svcLocale),
                )
            }
            val link = result.link
            if (channel == "whatsapp" && link != null) {
                svcOpen(link)
                Toast.show(L("WhatsApp is open with the message."))
            } else {
                Toast.show(L("Reminder sent in PawMiaw."))
            }
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(e)
        } finally {
            reminding = null
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("Pets whose next groom is due, by their cadence and their last visit."), style = Theme.text(14), color = Theme.inkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("Due window · Next"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
                AppMenu(label = { FilterMenuLabel(title = LF("%d days", windowDays)) }) {
                    for (days in listOf(7, 30, 60, 90)) {
                        item(LF("%d days", days)) { windowDays = days }
                    }
                }
            }
        }
        if (list == null && failed == null) {
            LoadingState()
        } else if (failed != null && list == null) {
            SvcLoadError(message = L("The list couldn’t be loaded.")) { load() }
        } else if (items.isEmpty()) {
            EmptyState(
                systemImage = "scissors",
                title = L("Nobody is due back in this window."),
                message = L("A pet is due when its cadence (on its care card, or the default in Settings) has passed since its last groom."),
                accent = Theme.pink,
                panel = true,
            )
        } else {
            for ((bucket, state) in svcDueBuckets) {
                val rows = items.filter { it.bucket == bucket }
                if (rows.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeading(
                            title = when (bucket) {
                                "overdue" -> L("Due bucket · Overdue")
                                "this_week" -> L("This week")
                                else -> L("Later")
                            },
                            count = rows.size,
                            dot = state.color,
                            style = SectionHeading.Style.heading,
                            bubble = Theme.surfaceMuted,
                            showsZero = true,
                        )
                        Column(Modifier.listCard()) {
                            val first = rows.first().id
                            for (item in rows) {
                                key(item.id) {
                                    if (item.id != first) RowDivider()
                                    SvcDueRow(
                                        item = item, tone = state.color, canRemind = canRemind, canBook = canBook, reminding = reminding, push = push,
                                        onRemind = { channel -> scope.launch { remind(item, channel) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(windowDays) {
        load()
        if (canBook) SvcServices.load("provider")
    }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { load() } } }
}

/**
 * One row (web `DueBucketList`): the bucket's colour down the left edge, the pet,
 * how late, the chips that say what was done already, and the reminder and booking.
 */
@Composable
private fun SvcDueRow(
    item: SvcDueItem,
    tone: Color,
    canRemind: Boolean,
    canBook: Boolean,
    reminding: String?,
    push: (ProviderRoute) -> Unit,
    onRemind: (String) -> Unit,
) {
    val days = item.days ?: SvcTime.daysUntil(item.dueAt) ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(tone, size = Size(6.dp.toPx(), size.height)) }
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PetAvatar(
                name = item.pet.name, species = item.pet.species, photoURL = item.pet.photoUrl, size = 48.dp,
                modifier = Modifier.plainButton { push(ProviderRoute.patientChart(item.pet.id)) },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowLayout(spacing = 8.dp) {
                    Text(
                        item.pet.name,
                        style = Theme.text(16, FontWeight.Bold),
                        color = Theme.inkStrong,
                        modifier = Modifier.plainButton { push(ProviderRoute.patientChart(item.pet.id)) },
                    )
                    Chip(text = L("Grooming"), tone = Chip.Tone.custom(Hue.lavender.color), icon = "scissors", percent = 26.0)
                }
                val small = Theme.text(12)
                FlowLayout(spacing = 6.dp) {
                    Text(
                        SvcTime.dueWords(days),
                        style = small.copy(fontWeight = FontWeight.Bold),
                        color = if (item.bucket == "overdue") Theme.destructive else Theme.inkStrong,
                    )
                    Text("·", style = small, color = Theme.inkSoft)
                    Text(SvcTime.day(item.dueAt), style = small, color = Theme.inkSoft)
                    Text("·", style = small, color = Theme.inkSoft)
                    Text(
                        item.client.name,
                        style = small,
                        color = Theme.inkSoft,
                        modifier = Modifier.plainButton { push(ProviderRoute.client(item.client.id)) },
                    )
                    item.lastVisitAt?.let { last ->
                        Text("·", style = small, color = Theme.inkSoft)
                        Text(LF("last visit %@", SvcTime.day(last)), style = small, color = Theme.inkSoft)
                    }
                }
                SvcDueChips(item)
            }
        }
        FlowLayout(spacing = 8.dp, modifier = Modifier.padding(start = 60.dp)) {
            if (canRemind && item.client.phone.nonEmpty != null) {
                PMButton(
                    L("WhatsApp"), systemImage = "message-circle", variant = ButtonVariant.outline, size = ButtonSize.header,
                    isWorking = reminding == item.key + "whatsapp", enabled = reminding == null,
                ) { onRemind("whatsapp") }
            }
            if (canRemind && item.client.onPawMiaw == true) {
                PMButton(
                    L("Remind in PawMiaw"), systemImage = "smartphone", variant = ButtonVariant.outline, size = ButtonSize.header,
                    isWorking = reminding == item.key + "app", enabled = reminding == null,
                ) { onRemind("app") }
            }
            if (canBook) {
                // The Agenda's booking for this pet, tied to the Due row it answers; once it has a visit, booking is no longer the row's main thing.
                PMButton(
                    L("Book"), systemImage = "calendar-plus",
                    variant = if (item.nextVisit == null) ButtonVariant.primary else ButtonVariant.outline, size = ButtonSize.header,
                ) {
                    push(SvcBook.groom(clientId = item.client.id, petId = item.pet.id, followUpId = item.followUpId))
                }
            }
        }
    }
}

@Composable
private fun SvcDueChips(item: SvcDueItem) {
    val familyNotice = item.familyNotice?.asked == true && item.familyNotice?.sentAt == null && item.lastContact == null &&
        item.nextVisit == null && item.snoozedUntil == null && item.bucket != "overdue"
    if (item.missedVisitAt != null || item.nextVisit != null || item.snoozedUntil != null || familyNotice || item.lastContact != null) {
        FlowLayout(spacing = 6.dp) {
            item.missedVisitAt?.let { missed ->
                // It was booked and didn't happen: that is why it is here again.
                Chip(
                    text = if ((DateText.parse(missed) ?: Instant.EPOCH) > Instant.now()) LF("The visit on %@ was cancelled", SvcTime.day(missed))
                    else LF("The visit on %@ didn’t happen", SvcTime.day(missed)),
                    tone = Chip.Tone.custom(Theme.mango), icon = "calendar-x-2", percent = 26.0,
                )
            }
            item.nextVisit?.let { next ->
                // Already on the calendar: so nobody calls a family that has booked.
                Chip(
                    text = LF("Has a visit on %@: %@", SvcTime.day(next.startsAt), next.serviceLabel ?: ""),
                    tone = Chip.Tone.custom(Theme.blue), icon = "calendar-check", percent = 14.0,
                )
            }
            item.snoozedUntil?.let { until ->
                Chip(text = LF("Postponed until %@", SvcTime.day(until)), tone = Chip.Tone.custom(Theme.surfaceMuted), icon = "clock-3", percent = 100.0)
            }
            val due = DateText.parse(item.dueAt)
            if (familyNotice && due != null) {
                // PawMiaw tells the family a week before, once: so nobody sends it twice by hand.
                Chip(
                    text = LF("PawMiaw tells the family on %@", SvcTime.day(DateText.iso(due.minus(Duration.ofDays(7))))),
                    tone = Chip.Tone.custom(Theme.blue), icon = "smartphone", percent = 14.0,
                )
            }
            item.lastContact?.let { contact ->
                // Already told: so nobody messages the same family twice.
                Chip(
                    text = if (contact.channel == "whatsapp") LF("WhatsApp opened %@", SvcTime.day(contact.at)) else LF("Reminded in PawMiaw %@", SvcTime.day(contact.at)),
                    tone = Chip.Tone.custom(ProviderState.done.color), icon = "check-check", percent = 16.0,
                )
            }
        }
    }
}

// MARK: - Settings

private const val svcStandardReadyMessage = "Hi {client}, this is {business}: {pet} is ready for pickup."

/**
 * How this business grooms (web `GroomingSettingsPanel`): how often pets come
 * back, which stages the board has, and what "ready" says.
 */
@Composable
fun SvcGroomSettingsTab(
    canManage: Boolean,
    businessName: String,
    modifier: Modifier = Modifier,
) {
    val sizeClass = LocalSizeClass.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<SvcGroomingSettings?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val weeks = remember { mutableStateOf("") }
    val simple = remember { mutableStateOf(false) }
    val message = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val form = remember { FormState() }

    // (ok, weeks): empty is fine (none); otherwise 1 to 52.
    val parsedWeeks: Pair<Boolean, Int?> = run {
        val text = weeks.value.nonEmpty ?: return@run true to null
        val value = text.toIntOrNull()
        if (value == null || value !in 1..52) false to null else true to value
    }

    val dirty = saved?.let { s ->
        parsedWeeks.second != s.defaultRebookWeeks || !parsedWeeks.first || simple.value != s.simpleStages || message.value.nonEmpty != s.readyMessage.nonEmpty
    } ?: false

    val preview = (message.value.nonEmpty ?: L(svcStandardReadyMessage))
        .replace("{pet}", "Rocky")
        .replace("{client}", "Carlos")
        .replace("{business}", businessName.nonEmpty ?: L("your business"))

    fun reset(settings: SvcGroomingSettings) {
        weeks.value = settings.defaultRebookWeeks?.toString() ?: ""
        simple.value = settings.simpleStages
        message.value = settings.readyMessage ?: ""
    }

    suspend fun load() {
        try {
            val loaded = API.get<SvcGroomingSettings>("/provider-grooming/settings")
            saved = loaded
            reset(loaded)
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = errorText(e)
        }
    }

    fun save() {
        if (!form.check(listOf("weeks" to (if (parsedWeeks.first) null else L("Weeks go from 1 to 52. Leave it empty for none."))))) return
        working = true
        scope.launch {
            try {
                val result = API.put<SvcGroomingSettings>(
                    "/provider-grooming/settings",
                    mapOf("defaultRebookWeeks" to parsedWeeks.second, "simpleStages" to simple.value, "readyMessage" to message.value.nonEmpty),
                )
                saved = result
                reset(result)
                Toast.show(L("Grooming settings saved."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("defaultRebookWeeks" to "weeks"))
            } finally {
                working = false
            }
        }
    }

    @Composable
    fun DueRule() {
        SettingsRule(
            icon = "repeat-2", tone = Theme.mango, title = L("Due back"),
            hint = L("When a pet’s care card doesn’t say how often it comes, it is due this many weeks after its last groom. Empty: six weeks."),
        ) {
            FlowLayout(spacing = 8.dp, modifier = Modifier.formScrollTarget("weeks", form)) {
                Text(L("Every"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                FieldChrome(invalid = form.error("weeks") != null || !parsedWeeks.first, modifier = Modifier.width(72.dp)) {
                    PlainTextField(
                        stateOf({ weeks.value }, { value ->
                            weeks.value = value.filter { it.isDigit() }.take(2)
                            form.clear("weeks")
                        }),
                        prompt = "6",
                        keyboard = KeyboardType.Number,
                        style = Theme.bodyFont.copy(textAlign = TextAlign.Center),
                        enabled = canManage,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(L("weeks"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                if (!parsedWeeks.first) Text(L("From 1 to 52"), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
        }
    }

    @Composable
    fun StagesRule() {
        SettingsRule(
            icon = "workflow", tone = Theme.aqua, title = L("Stages on the board"),
            hint = if (simple.value) L("Waiting → In progress → Ready for pickup. One stage for the whole groom.")
            else L("Waiting → Bath → Cut & dry → Ready for pickup. The bath and the table are two stages."),
        ) {
            Disabled(!canManage) {
                ToggleRow(title = L("Simple stages"), subtitle = L("For one groomer, or when the bath and the cut happen in one go."), isOn = simple)
            }
        }
    }

    @Composable
    fun MessageRule() {
        SettingsRule(
            icon = "message-circle", tone = ProviderState.done.color, title = L("The “ready” message"),
            hint = L("What WhatsApp opens with when you tell a family their pet is ready. Leave it empty for the standard one, written in the family’s language."),
        ) {
            FieldChrome(multiline = true) {
                PlainTextField(message, prompt = L(svcStandardReadyMessage), lines = 3..8, enabled = canManage, modifier = Modifier.weight(1f))
            }
            if (canManage) {
                SvcPlaceholderRow(
                    placeholders = listOf("{pet}", "{client}", "{business}"),
                    onAdd = { message.value += it },
                    trailing = L("They become the pet, the client’s first name and your business."),
                )
            }
            SvcMessagePreview(text = preview, standard = message.value.nonEmpty == null)
        }
    }

    val current = saved
    if (current != null) {
        FormScroll(form) {
            SettingsCard(
                icon = "scissors", tone = Theme.pink, title = L("How you groom"),
                hint = if (canManage) L("For the whole business: the board’s stages, when pets are due back and what “ready” says.")
                else L("Set by whoever manages the business. You can read it here."),
                modifier = modifier,
                content = {
                    if (sizeClass == SizeClass.regular) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                DueRule()
                                StagesRule()
                            }
                            Box(Modifier.weight(1f)) { MessageRule() }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            DueRule()
                            StagesRule()
                            MessageRule()
                        }
                    }
                },
                footer = {
                    if (canManage) {
                        SaveBar(dirty = dirty, working = working, onDiscard = { reset(current) }, onSave = { save() })
                    }
                },
            )
        }
    } else if (failed != null) {
        SvcLoadError(message = L("The settings couldn’t be loaded."), modifier = modifier) { load() }
    } else {
        LoadingState(modifier = modifier)
        LaunchedEffect(Unit) { load() }
    }
}
