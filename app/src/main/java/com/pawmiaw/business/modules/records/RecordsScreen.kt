package com.pawmiaw.business.records

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.launch
import java.util.UUID

//
//  RecordsScreen.kt (iOS RecordsScreen.swift)
//
//  Expedientes (web routes/provider.records.tsx): every vaccine, visit,
//  medicine, diagnosis and result the team writes, the drafts still waiting
//  for a signature, and the follow-ups those records leave behind. As on the
//  web: the module header (FileText on aqua, "Nuevo registro", the four
//  numbers), then one card — the view switch and the search, the filters on
//  one line, the list under its day bands, how many under it. Three views:
//  "Por completar" (a finished visit's notes and reports, the notes to sign,
//  drafts left empty), Records and Follow-ups, 20 at a time with infinite
//  scroll, counts from the server.
//

/** The record sheet's target: a new record, or one to edit. */
class RecordsSheetTarget(val record: RecordsRecord?) {
    val id: UUID = UUID.randomUUID()
}

/** Few enough people to show as chips; a bigger team gets a list. */
private const val maxPeopleChips = 4

@Composable
fun RecordsScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember { RecordsListModel(scope) }
    val query = remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<RecordsSheetTarget?>(null) }
    var signTarget by remember { mutableStateOf<RecordsRecord?>(null) }
    var discardTarget by remember { mutableStateOf<RecordsRecord?>(null) }
    var waiveTarget by remember { mutableStateOf<RecordsFollowUp?>(null) }
    var rangeOpen by remember { mutableStateOf(false) }

    val access = RecordsAccess(workspace)
    val open: (ProviderRoute) -> Unit = { nav.push(it) }

    // MARK: Actions

    fun openCreate() {
        if (!access.writeRecords) return
        if (model.clientsTotal == 0) {
            if (access.viewClients) nav.push(ProviderRoute.section(ProviderSection.clients))
            return
        }
        sheet = RecordsSheetTarget(record = null)
    }

    fun edit(record: RecordsRecord) {
        sheet = RecordsSheetTarget(record = record)
    }

    fun clearFilters() {
        query.value = ""
        model.clearFilters()
    }

    fun positive(value: Int?): Int? = if ((value ?: 0) > 0) value else null

    // MARK: Header

    @Composable
    fun headerStats(): List<ModuleStat> {
        val stats = mutableListOf(
            ModuleStat(
                "to-complete", label = L("To complete"), value = "${model.toCompleteCount}",
                hint = L("Notes, reports and signatures from finished visits"), icon = "clipboard-pen", accent = Theme.pink,
                attention = model.toCompleteCount > 0, active = model.view == RecordsListView.toComplete,
                action = { model.switchView(RecordsListView.toComplete) },
            ),
        )
        if (access.records) {
            stats.add(ModuleStat(
                "signed", label = L("Signed@plural"), value = model.signedTotal?.let { "$it" } ?: "—",
                hint = L("Already part of the chart"), icon = "badge-check", accent = ProviderState.done.color,
                active = model.view == RecordsListView.records && model.signChoice == RecordsSignChoice.signed,
                action = { model.showSigned() },
            ))
        }
        if (access.followUps) {
            stats.add(ModuleStat(
                "overdue", label = L("Follow-ups overdue"), value = model.overdue?.let { "$it" } ?: "—",
                hint = L("Past their date and still open"), icon = "alarm-clock", accent = Theme.pink,
                attention = (model.overdue ?: 0) > 0, active = model.view == RecordsListView.followUps && model.due == RecordsDue.overdue,
                action = { model.showFollowUps(due = RecordsDue.overdue) },
            ))
            stats.add(ModuleStat(
                "week", label = L("Due this week"), value = model.week?.let { "$it" } ?: "—",
                hint = L("Rechecks, calls and vaccines"), icon = "calendar-clock", accent = ProviderState.waiting.color,
                active = model.view == RecordsListView.followUps && model.due == RecordsDue.week,
                action = { model.showFollowUps(due = RecordsDue.week) },
            ))
        }
        return stats
    }

    @Composable
    fun Header() {
        ModuleHeader(
            icon = "file-text",
            title = L("Records"),
            subtitle = L("Vaccines, visits, medicines and results — what still needs a signature and what is due next."),
            accent = Theme.aqua,
            stats = headerStats(),
            storageKey = "records",
        ) {
            if (access.writeRecords) {
                PMButton(L("New record"), systemImage = "plus") { openCreate() }
            }
        }
    }

    // MARK: Filters

    @Composable
    fun Separator() {
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(Theme.borderSoft))
    }

    @Composable
    fun Who() {
        if (!model.seesAnyone) {
            Disabled(true) {
                FilterChip(title = L("Assigned to you"), isSelected = false, tone = Theme.inkSoft, icon = "user-round") {}
            }
        } else if (model.members.isNotEmpty() && model.members.size <= maxPeopleChips) {
            FilterChip(title = L("Anyone"), isSelected = model.who == null, icon = "user-round") { model.setWho(null) }
            model.members.forEach { member ->
                key(member.id) {
                    FilterChip(title = member.label, isSelected = model.who == member.id, tone = Theme.aqua) {
                        model.setWho(if (model.who == member.id) null else member.id)
                    }
                }
            }
            FilterChip(title = L("Unassigned"), isSelected = model.who == "unassigned", tone = Theme.inkSoft) {
                model.setWho(if (model.who == "unassigned") null else "unassigned")
            }
        } else {
            val who = model.who
            val title = when {
                who == null -> L("Anyone")
                who == "unassigned" -> L("Unassigned")
                else -> model.members.firstOrNull { it.id == who }?.label ?: L("Assigned to")
            }
            AppMenu(
                label = { FilterMenuLabel(title = title, selected = model.who != null) },
                accessibilityLabel = L("Assigned to"),
            ) {
                item(L("Anyone")) { model.setWho(null) }
                item(L("Unassigned")) { model.setWho("unassigned") }
                model.members.forEach { member -> item(member.label) { model.setWho(member.id) } }
            }
        }
    }

    /** One line of filters, one height (36): what state, when, which type, whose. */
    @Composable
    fun Filters() {
        Row(
            Modifier
                .padding(vertical = 10.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            model.pet?.let { pet ->
                // The toolbar's "only this pet" chip (web `PetFilterChip`), with "Abrir expediente" beside it.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val only = L("Only this pet")
                    FilterChip(
                        title = listOf(pet.name, pet.clientName).filter { it.isNotEmpty() }.joinToString(" · "),
                        face = { PetAvatar(name = pet.name, species = pet.species, photoURL = pet.photoUrl, size = 28.dp) },
                        removeLabel = L("Show every pet again"),
                        modifier = Modifier.widthIn(max = 288.dp).semantics { contentDescription = only },
                    ) { model.setPet(null) }
                    if (access.viewClients) {
                        val label = LF("Open %@’s chart", pet.name)
                        PMButton(
                            L("Open chart"), variant = ButtonVariant.link, size = ButtonSize.header,
                            modifier = Modifier.semantics { contentDescription = label },
                        ) { nav.push(ProviderRoute.patientChart(petId = pet.id)) }
                    }
                }
                Separator()
            }
            if (model.view == RecordsListView.records) {
                FilterChip(title = L("All@records"), isSelected = model.signChoice == RecordsSignChoice.all) { model.setSign(RecordsSignChoice.all) }
                FilterChip(
                    title = L("To sign"), count = positive(model.drafts), isSelected = model.signChoice == RecordsSignChoice.draft,
                    tone = ProviderState.waiting.color, dot = ProviderState.waiting.color,
                ) {
                    model.setSign(if (model.signChoice == RecordsSignChoice.draft) RecordsSignChoice.all else RecordsSignChoice.draft)
                }
                FilterChip(
                    title = L("Signed@plural"), isSelected = model.signChoice == RecordsSignChoice.signed,
                    tone = ProviderState.done.color, dot = ProviderState.done.color,
                ) {
                    model.setSign(if (model.signChoice == RecordsSignChoice.signed) RecordsSignChoice.all else RecordsSignChoice.signed)
                }
            } else {
                FilterChip(
                    title = L("To do"), isSelected = model.followUpState == null,
                    tone = ProviderState.waiting.color, dot = ProviderState.waiting.color,
                ) { model.setFollowUpState(null) }
                FilterChip(
                    title = L("Reminded"), isSelected = model.followUpState == "contacted",
                    tone = ProviderState.active.color, dot = ProviderState.active.color,
                ) {
                    model.setFollowUpState(if (model.followUpState == "contacted") null else "contacted")
                }
                FilterChip(
                    title = L("Done@plural"), isSelected = model.followUpState == "done",
                    tone = ProviderState.done.color, dot = ProviderState.done.color,
                ) {
                    model.setFollowUpState(if (model.followUpState == "done") null else "done")
                }
                FilterChip(title = L("All@records"), isSelected = model.followUpState == "all") {
                    model.setFollowUpState(if (model.followUpState == "all") null else "all")
                }
                Separator()
                FilterChip(
                    title = L("Overdue"), count = positive(model.overdue), isSelected = model.due == RecordsDue.overdue,
                    tone = ProviderState.problem.color, icon = "alarm-clock",
                ) {
                    model.setDue(if (model.due == RecordsDue.overdue) null else RecordsDue.overdue)
                }
                FilterChip(
                    title = L("This week"), count = positive(model.week), isSelected = model.due == RecordsDue.week,
                    tone = ProviderState.waiting.color, icon = "calendar-clock",
                ) {
                    model.setDue(if (model.due == RecordsDue.week) null else RecordsDue.week)
                }
                FilterChip(title = L("Later"), isSelected = model.due == RecordsDue.later, tone = ProviderState.upcoming.color, icon = "calendar-range") {
                    model.setDue(if (model.due == RecordsDue.later) null else RecordsDue.later)
                }
            }
            Separator()
            val rangeFrom = if (model.view == RecordsListView.followUps && model.due != null) null else model.from
            val rangeTo = if (model.view == RecordsListView.followUps && model.due != null) null else model.to
            val rangeHint = if (model.view == RecordsListView.records) L("Record date") else L("Due date")
            FilterChip(
                title = RecordsRange.label(from = rangeFrom, to = rangeTo) ?: L("Any date"), isSelected = rangeFrom != null || rangeTo != null,
                tone = if (model.view == RecordsListView.records) Theme.aqua else Theme.lavender, icon = "calendar-range",
                modifier = Modifier.semantics { onClick(label = rangeHint) { rangeOpen = true; true } },
            ) { rangeOpen = true }
            AppMenu(
                label = { FilterMenuLabel(title = model.type?.label ?: L("Every type"), selected = model.type != null) },
                accessibilityLabel = L("Record type"),
            ) {
                item(L("Every type")) { model.setType(null) }
                RecordsKind.allCases.forEach { kind -> item(kind.label, icon = kind.systemImage) { model.setType(kind) } }
            }
            Who()
            if (model.filtered) {
                Row(
                    Modifier.plainButton { clearFilters() }.height(36.dp).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("x", 14.dp, tint = Theme.blue)
                    Text(L("Clear filters"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                }
            }
        }
    }

    // MARK: To complete

    @Composable
    fun ToComplete() {
        if (!model.toCompleteLoaded) {
            LoadingState()
        } else if (model.toCompleteCount == 0) {
            EmptyState(
                systemImage = "clipboard-pen", title = L("Everything is complete"),
                message = L("Notes, reports and signatures from finished visits wait here until they are done."), accent = Theme.aqua,
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (model.paperwork.isNotEmpty()) {
                    DayBand(title = RecordsText.capitalizedFirst(L("Notes and reports to finish")), style = DayBand.Style.records)
                    model.paperwork.forEachIndexed { index, followUp ->
                        key(followUp.id) {
                            if (index > 0) RowDivider()
                            RecordsFollowUpRow(
                                followUp = followUp, access = access, canWaive = access.isManager,
                                onWaive = { waiveTarget = followUp }, onOpen = open,
                            )
                        }
                    }
                }
                if (model.toSign.isNotEmpty()) {
                    if (model.paperwork.isNotEmpty()) RowDivider()
                    DayBand(title = RecordsText.capitalizedFirst(L("Notes to sign")), style = DayBand.Style.records)
                    model.toSign.forEachIndexed { index, record ->
                        key(record.id) {
                            if (index > 0) RowDivider()
                            RecordsRecordRow(record = record, access = access, onSign = { signTarget = record }, onEdit = { edit(record) }, onOpen = open)
                        }
                    }
                }
                if (model.unfinished.isNotEmpty()) {
                    if (model.paperwork.isNotEmpty() || model.toSign.isNotEmpty()) RowDivider()
                    DayBand(title = RecordsText.capitalizedFirst(L("Drafts left unfinished")), style = DayBand.Style.records)
                    model.unfinished.forEachIndexed { index, record ->
                        key(record.id) {
                            if (index > 0) RowDivider()
                            RecordsRecordRow(
                                record = record, mode = RecordsRecordRow.Mode.unfinished, access = access, onEdit = { edit(record) },
                                onDiscard = if (access.editRecords) ({ discardTarget = record }) else null, onOpen = open,
                            )
                        }
                    }
                }
                if (model.hasMoreToComplete) {
                    RowDivider()
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        PMButton(L("Show more"), variant = ButtonVariant.outline, isWorking = model.loadingMore) {
                            scope.launch { model.loadMoreToComplete() }
                        }
                    }
                }
            }
        }
    }

    // MARK: Records and follow-ups

    /** Nothing to list, and the way out: clear the filters, write a record, or go to the records. */
    @Composable
    fun EmptyList() {
        val isRecords = model.view == RecordsListView.records
        val needle = model.search
        val drafts = isRecords && model.onlyDrafts
        val filtered = model.filtered
        val title: String = when {
            needle.isNotEmpty() -> LF("Nothing matches “%@”", needle)
            isRecords -> if (drafts) L("Nothing waiting for a signature") else if (filtered) L("No records match these filters") else L("No records yet")
            else -> if (filtered) L("No follow-ups match these filters") else L("No follow-ups to do")
        }
        val message: String = if (isRecords) {
            if (drafts) L("Every record is signed.") else if (filtered) L("Try another search, or clear the filters.") else L("Vaccines, visits, diagnoses and results your team writes show up here.")
        } else {
            if (filtered) L("Try another search, or clear the filters.") else L("When a record has a follow-up date, it waits here until someone closes it.")
        }
        val create = access.writeRecords && !filtered
        val seeRecords = !isRecords && access.records && !filtered
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(systemImage = if (isRecords) "file-text" else "bell-ring", title = title, message = message, accent = if (isRecords) Theme.aqua else Theme.pink)
            if (filtered || create || seeRecords) {
                FlowLayout(spacing = 8.dp, modifier = Modifier.recordsPullUp(12.dp).padding(bottom = 28.dp)) {
                    if (filtered) {
                        val seeAll = drafts && needle.isEmpty()
                        PMButton(
                            if (seeAll) L("See all records") else L("Clear filters"), systemImage = if (seeAll) null else "x",
                            variant = ButtonVariant.outline, size = ButtonSize.header,
                        ) { clearFilters() }
                    }
                    if (create) {
                        PMButton(L("New record"), systemImage = "plus", size = ButtonSize.header) { openCreate() }
                    }
                    if (seeRecords) {
                        PMButton(L("See records"), systemImage = "file-text", variant = ButtonVariant.outline, size = ButtonSize.header) {
                            model.switchView(RecordsListView.records)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun RecordsList() {
        Column(Modifier.fillMaxWidth()) {
            RecordsDayList(pager = model.recordsPager, dayOf = { RecordsText.dayKey(it.eventAt) }, empty = { EmptyList() }) { record ->
                RecordsRecordRow(
                    record = record,
                    access = access,
                    needle = model.search,
                    onSign = { signTarget = record },
                    onEdit = { edit(record) },
                    onFilterPet = if (model.pet?.id == record.providerClientPetId) null else ({ model.setPet(record.petFilter) }),
                    onOpen = open,
                )
            }
            RecordsListFooter(pager = model.recordsPager, one = "%d record", other = "%d records", range = RecordsRange.label(from = model.from, to = model.to))
        }
    }

    @Composable
    fun FollowUpsList() {
        Column(Modifier.fillMaxWidth()) {
            RecordsDayList(pager = model.followUpsPager, dayOf = { RecordsText.dayKey(it.dueAt) }, empty = { EmptyList() }) { followUp ->
                RecordsFollowUpRow(
                    followUp = followUp,
                    access = access,
                    grouped = true,
                    needle = model.search,
                    reach = model.reach[followUp.providerClientId],
                    onFilterPet = if (model.pet?.id == followUp.providerClientPetId) null else ({ model.setPet(followUp.petFilter) }),
                    onOpen = open,
                )
            }
            RecordsListFooter(
                pager = model.followUpsPager, one = "%d follow-up", other = "%d follow-ups",
                range = if (model.due == null) RecordsRange.label(from = model.from, to = model.to) else null,
            )
        }
    }

    // MARK: The card

    @Composable
    fun ListCard() {
        val viewOptions = buildList {
            add(Segmented.Option(RecordsListView.toComplete, L("To complete"), icon = "clipboard-pen", badge = model.toCompleteCount, attention = true))
            if (access.records) {
                add(Segmented.Option(RecordsListView.records, L("Records"), icon = "file-text", badge = if (access.signRecords) model.drafts else null, attention = false))
            }
            if (access.followUps) {
                add(Segmented.Option(RecordsListView.followUps, L("Follow-ups"), icon = "bell-ring", badge = model.overdue, attention = true))
            }
        }
        Card(padding = 0.dp, spacing = 0.dp) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Theme.cardRadius))) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (viewOptions.size > 1) {
                        Segmented(
                            options = viewOptions,
                            selection = stateOf({ model.view }, { model.switchView(it) }),
                            look = Segmented.Look.raised,
                            label = L("View"),
                            fill = true,
                        )
                    }
                    if (model.view != RecordsListView.toComplete) {
                        SearchField(
                            prompt = if (model.view == RecordsListView.records) L("Pet, client or record") else L("Pet, client or task"),
                            text = query,
                            height = 40.dp,
                        )
                    }
                }
                RowDivider()

                if (model.view == RecordsListView.records && workspace.multiLocation) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { LocationMenu() }
                    RowDivider()
                }
                if (model.view != RecordsListView.toComplete) {
                    Filters()
                    RowDivider()
                }

                when (model.view) {
                    RecordsListView.toComplete -> ToComplete()
                    RecordsListView.records -> RecordsList()
                    RecordsListView.followUps -> FollowUpsList()
                }
            }
        }
    }

    providerPage(L("Records"), root = true, modifier = modifier) {
        PageScroll(refresh = { model.load(workspace) }) {
            if (!access.seesPage) {
                NoAccessState(message = L("Ask the workspace owner to let your role see records or follow-ups."))
            } else {
                Header()
                if (model.clientsTotal == 0 && !model.filtered) {
                    EmptyState(
                        systemImage = "paw-print",
                        title = L("Add your first client and pet"),
                        message = L("Create the first provider client and pet here. After that, appointments, records, invoices, and care history can start from the same profile."),
                        actionTitle = if (access.viewClients) L("Add first client and pet") else null,
                        action = if (access.viewClients) ({ nav.push(ProviderRoute.section(ProviderSection.clients)) }) else null,
                        panel = true,
                    )
                } else {
                    ListCard()
                }
            }
        }
    }
    ReloadsOnScope { model.load(workspace) }
    Debounced(query.value) { model.applySearch(it) }

    sheet?.let { target ->
        key(target.id) {
            AppSheet(onDismissRequest = { sheet = null }) {
                RecordsRecordSheet(record = target.record, members = model.members, access = access)
            }
        }
    }
    signTarget?.let { record ->
        AppSheet(onDismissRequest = { signTarget = null }) { RecordsSignSheet(record = record) }
    }
    waiveTarget?.let { followUp ->
        AppSheet(onDismissRequest = { waiveTarget = null }) { RecordsWaiveSheet(followUp = followUp) }
    }
    discardTarget?.let { record ->
        AppSheet(onDismissRequest = { discardTarget = null }) {
            ConfirmSheet(
                title = L("Discard this empty draft?"),
                message = LF("%@ for %@ has nothing written in it. It goes away; the history keeps that it was discarded.", record.titleText, record.petText),
                confirmTitle = L("Discard"),
                destructive = true,
                cancelTitle = L("Keep it"),
                icon = "file-text",
            ) {
                RecordsAPI.discard(record.id)
                Toast.show(L("Draft discarded."))
                announceDataChanged()
            }
        }
    }
    if (rangeOpen) {
        AppSheet(onDismissRequest = { rangeOpen = false }) {
            RecordsRangeSheet(
                title = if (model.view == RecordsListView.records) L("Record date") else L("Due date"),
                presets = if (model.view == RecordsListView.records) listOf(RecordsRange.Preset.today, RecordsRange.Preset.week, RecordsRange.Preset.month)
                else listOf(RecordsRange.Preset.today, RecordsRange.Preset.month),
                from = if (model.view == RecordsListView.followUps && model.due != null) null else model.from,
                to = if (model.view == RecordsListView.followUps && model.due != null) null else model.to,
            ) { from, to -> model.setRange(from = from, to = to) }
        }
    }
}

/** SwiftUI `.padding(.top, -12)`: the view drawn and measured that much higher. */
private fun Modifier.recordsPullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout(placeable.width, maxOf(0, placeable.height - shift)) { placeable.place(0, -shift) }
}

/** How many, under the list, from the server's total: "**24 registros** · Esta semana". */
@Composable
fun <Item> RecordsListFooter(
    pager: Pager<Item>,
    one: String,
    other: String,
    range: String? = null,
    modifier: Modifier = Modifier,
) {
    val total = pager.total
    if (!pager.loadedOnce || total == null || total <= 0) return
    Column(modifier.fillMaxWidth()) {
        RowDivider()
        val strong = Theme.inkStrong
        val soft = Theme.inkSoft
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) {
                    append(if (pager.isLoading) L("Updating…") else LP(total, one, other))
                }
                if (range != null) withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = soft)) { append(" · $range") }
            },
            style = Theme.text(14).monospacedDigit(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
