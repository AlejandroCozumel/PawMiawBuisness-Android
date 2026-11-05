package com.pawmiaw.business.team

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.pawmiaw.business.*
import com.pawmiaw.business.inbox.InboxOlfatoArea
import com.pawmiaw.business.inbox.InboxOlfatoPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant

//
//  TeamOlfatoSettings.kt (iOS TeamOlfatoSettings.swift)
//
//  Settings › Olfato AI (web: components/app/settings/ai-summary-section.tsx,
//  components/app/olfato/olfato-consent.tsx and olfato-team-use.tsx; backend
//  docs/patient-summaries.md and docs/olfato-business-chat-plan.md): the
//  business's own switch for the AI brief on a pet's chart and the chat,
//  which only turns on once someone who edits the workspace has read what is
//  sent and to whom and confirmed it; and "Team use", who on the team asked
//  Olfato AI, when, about which pets and what kind of information (never the
//  text), 20 at a time with filters.
//

/** `GET/PUT /provider-patients/ai-summary-settings`. */
@Serializable
data class TeamOlfatoSummarySettings(
    /** The server can write summaries. False: nothing can be turned on yet. */
    val available: Boolean? = null,
    val enabled: Boolean? = null,
    /** The current notice was confirmed. */
    val acknowledged: Boolean? = null,
    /** On, but confirmed on an older notice: the brief and the chat wait until it is confirmed again. */
    val noticeOutdated: Boolean? = null,
    val acknowledgedAt: String? = null,
    val acknowledgedByName: String? = null,
    val disclosureVersion: String? = null,
    val canManage: Boolean? = null,
)

/** Olfato AI's page, opened from Settings and from a staff member's "See the whole team's use". */
@Composable
fun TeamOlfatoSettingsPage(modifier: Modifier = Modifier) {
    providerPage("Olfato AI", modifier = modifier) {
        PageScroll {
            ModuleHeader(
                icon = "settings",
                title = L("Settings"),
                subtitle = L("How your business shows up to families, what it offers, and the clock and money it runs on."),
                accent = ProviderSection.settings.headerAccent,
                storageKey = "settings",
            )
            TeamOlfatoSections()
        }
    }
}

/**
 * The sections this person may see: the switch for whoever reads records or edits the
 * workspace, the team's use for owners, admins and whoever manages roles or money.
 * (Two cards, laid out by the column it sits in.)
 */
@Composable
fun TeamOlfatoSections() {
    val workspace = Workspace.shared
    if (workspace.canAny(Permission.recordsView, Permission.workspaceEdit)) {
        TeamOlfatoSummarySection(canEdit = workspace.can(Permission.workspaceEdit))
    }
    if (TeamOlfatoAccess.seesTeam(workspace)) {
        TeamOlfatoTeamUseSection()
    }
}

// MARK: - The switch

@Composable
fun TeamOlfatoSummarySection(canEdit: Boolean, modifier: Modifier = Modifier) {
    val flash = remember { TeamSavedFlash() }
    var settings by remember { mutableStateOf<TeamOlfatoSummarySettings?>(null) }
    var failed by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val editable = canEdit && (settings?.canManage ?: false)
    val dirty = settings?.let { enabled != (it.enabled ?: false) || acknowledged != (it.acknowledged ?: false) } ?: false

    fun reset(loaded: TeamOlfatoSummarySettings) {
        enabled = loaded.enabled ?: false
        acknowledged = loaded.acknowledged ?: false
    }

    suspend fun load() {
        try {
            val loaded: TeamOlfatoSummarySettings = API.get("/provider-patients/ai-summary-settings")
            settings = loaded
            reset(loaded)
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = settings == null
        }
    }

    fun save(current: TeamOlfatoSummarySettings) {
        // Confirming a notice that changed is the notice alone: the server keeps the switch.
        val onlyTheNotice = current.noticeOutdated == true && acknowledged && enabled == (current.enabled ?: false)
        val body = mutableMapOf<String, Any?>()
        if (onlyTheNotice) {
            body["acknowledged"] = true
        } else {
            body["enabled"] = enabled
            if (acknowledged && current.acknowledged != true) body["acknowledged"] = true
        }
        saving = true
        scope.launch {
            try {
                val saved: TeamOlfatoSummarySettings = API.put("/provider-patients/ai-summary-settings", body)
                settings = saved
                reset(saved)
                flash.mark()
                Workspace.shared.refresh()
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(errorText(e, fallback = L("Could not save the Olfato AI settings.")))
            } finally {
                saving = false
            }
        }
    }

    val current = settings
    if (current != null) {
        val available = current.available ?: false
        val canTurnOn = available && acknowledged
        val box = RoundedCornerShape(Theme.boxRadius)
        TeamSaveSection(
            title = "Olfato AI",
            subtitle = L("AI brief of each pet’s record.") + "\n" + L("Olfato is Spanish for a pet’s sense of smell: it sniffs out what matters in the chart."),
            systemImage = "olfato",
            tint = Theme.aqua,
            look = TeamCardLook.settings,
            state = flash.state(saving = saving, dirty = dirty),
            readOnlyText = if (editable) null else L("Only someone who edits the workspace can change this."),
            onSave = if (editable) ({ save(current) }) else null,
            onDiscard = { reset(current) },
            modifier = modifier,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(L("Before you turn it on"), Modifier.weight(1f), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong)
                    Image(
                        painter = rememberAsyncImagePainter("file:///android_asset/img/OlfatoAI.svg"),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            // SwiftUI `.padding(.top, -4)`: drawn 4 higher than the row it sits in.
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val lift = 4.dp.roundToPx()
                                layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) { placeable.place(0, -lift) }
                            }
                            .size(96.dp),
                    )
                }
                OlfatoNotice()
            }
            if (!available) {
                Text(
                    L("Olfato AI isn’t available on PawMiaw yet. When it is, you can turn it on here."),
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surfaceMuted, box)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkSoft,
                )
            }
            if (current.noticeOutdated == true) {
                Text(
                    if (editable) L("The Olfato AI notice changed: it now covers the chat too. Read it and confirm it to use Olfato AI.")
                    else L("Olfato AI is paused until someone who edits the workspace confirms its new notice."),
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surfaceMuted, box)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkStrong,
                )
            }
            // "I've read this…": once confirmed it stays confirmed (it records who read it, and when).
            val ackLocked = !editable || (current.acknowledged ?: false)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surface, box)
                    .border(1.dp, Theme.borderSoft, box)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainButton(enabled = !ackLocked) {
                            flash.clear()
                            acknowledged = !acknowledged
                            // The switch does not stay on without the confirmation.
                            if (!acknowledged) enabled = false
                        }
                        .alpha(if (ackLocked && !acknowledged) 0.5f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Tick(on = acknowledged)
                    Text(L("I’ve read this and our clients are informed as our policies require"), Modifier.weight(1f), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                }
                val at = current.acknowledgedAt
                if (current.acknowledged == true && at != null) {
                    Text(
                        current.acknowledgedByName.nonEmpty?.let { LF("Confirmed by %1\$@ on %2\$@", it, DateText.date(at)) } ?: LF("Confirmed on %@", DateText.date(at)),
                        Modifier.padding(start = 28.dp),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                }
            }
            // The one choice; it waits for the confirmation. Turning it off is always possible.
            Disabled(!editable || (!enabled && !canTurnOn)) {
                ToggleRow(
                    title = L("Olfato AI on pet charts"),
                    subtitle = when {
                        !available -> L("Not available yet.")
                        !acknowledged -> L("Tick the confirmation above first.")
                        enabled -> L("On: charts show the Olfato AI brief above the pet’s records.")
                        else -> L("Off: charts show the pet’s records as always, with no AI text. Nothing is sent.")
                    },
                    isOn = stateOf({ enabled }, { flash.clear(); enabled = it }),
                )
            }
            // The chat goes with the same switch and notice.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, box)
                    .border(1.dp, Theme.borderSoft, box)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph("message-circle", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("Olfato AI chat"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    Text(
                        (if ((current.enabled ?: false) && (current.acknowledged ?: false))
                            L("Your team asks Olfato AI from “Olfato AI” in the sidebar and from “Ask Olfato” on each pet’s chart. Each person only gets answers from what they can already see.")
                        else
                            L("With Olfato AI on and this notice confirmed, your team can ask it about a pet or about the business: “Olfato AI” in the sidebar and “Ask Olfato” on each pet’s chart."))
                            + " " + L("To leave someone out, turn off “Use Olfato AI” in their access, in Staff."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
            }
        }
    } else if (failed) {
        TeamPanel(title = "Olfato AI", systemImage = "olfato", tint = Theme.aqua, look = TeamCardLook.settings, modifier = modifier) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(L("Could not load the Olfato AI settings."), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, textAlign = TextAlign.Center)
                PMButton(L("Try again"), variant = ButtonVariant.outline) { scope.launch { load() } }
            }
        }
    } else {
        TeamPanel(title = "Olfato AI", systemImage = "olfato", tint = Theme.aqua, look = TeamCardLook.settings, modifier = modifier) {
            TeamProgress(minHeight = 120.dp)
        }
        LaunchedEffect(Unit) { load() }
    }
}

// MARK: - Team use

@Stable
private class TeamOlfatoTeamUseModel {
    var memberId by mutableStateOf<String?>(null)
    var petId by mutableStateOf<String?>(null)
    var petName by mutableStateOf<String?>(null)
    var from by mutableStateOf<Instant?>(null)
    var to by mutableStateOf<Instant?>(null)
    var tool by mutableStateOf<String?>(null)
    var weekly by mutableStateOf<TeamOlfatoWeekly?>(null)
    var weeklyFailed by mutableStateOf(false)
    var areas by mutableStateOf<List<InboxOlfatoArea>>(emptyList())

    val pager: Pager<OlfatoAccessEntry> = Pager { offset, limit ->
        val page: TeamOlfatoTeamPage = API.get("/provider-olfato/access/team", query = query(offset = offset, limit = limit))
        page.weekly?.let { weekly = it }
        Page(items = page.items ?: emptyList(), total = page.total, hasMore = page.hasMore)
    }

    val filtered: Boolean get() = memberId != null || petId != null || from != null || tool != null

    fun query(offset: Int, limit: Int): Map<String, String?> = mapOf(
        "limit" to "$limit",
        "offset" to "$offset",
        "weeks" to "8",
        "memberId" to memberId,
        "petId" to petId,
        "from" to from?.let { TeamDays.keyPicked(it) },
        "to" to to?.let { TeamDays.keyPicked(it) },
        "tool" to tool,
    )

    fun clear() {
        memberId = null; petId = null; petName = null; from = null; to = null; tool = null
    }

    suspend fun loadAreas() {
        runCatching { API.get<InboxOlfatoPanel>("/provider-olfato/viewer") }.getOrNull()?.let { areas = it.areas ?: emptyList() }
    }
}

/**
 * Who on the team asked Olfato AI, how often (questions per person per week, with this week's
 * pets and kinds), and every line, narrowed by person, pet, dates and kind, 20 at a time.
 */
@Composable
fun TeamOlfatoTeamUseSection(modifier: Modifier = Modifier) {
    val model = remember { TeamOlfatoTeamUseModel() }
    val scope = rememberCoroutineScope()
    val people = (model.weekly?.members ?: emptyList()).filter { it.memberId != null }

    fun reload() {
        scope.launch { model.pager.reload() }
    }

    LaunchedEffect(Unit) {
        if (!model.pager.loadedOnce) model.pager.reload()
        if (model.areas.isEmpty()) model.loadAreas()
    }

    TeamPanel(
        title = L("Team use"),
        subtitle = L("Who on the team asked Olfato AI, when, about which pets and what kind of information."),
        systemImage = "users-round",
        tint = Theme.aqua,
        look = TeamCardLook.settings,
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Glyph("lock", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
            Text(L("The text of questions and answers is never kept here: only who asked, when and about what."), style = Theme.text(14), color = Theme.inkStrong)
        }

        val weekly = model.weekly
        if (weekly != null && !(weekly.members ?: emptyList()).isEmpty()) {
            WeeklyCounts(weekly, model) { reload() }
        }

        Text(L("Every question"), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong)
        TeamAdaptiveGrid(items = listOf(0, 1), minimum = 240.dp, spacing = 10.dp) { index ->
            if (index == 0) {
                val chosen = model.memberId
                MenuField(
                    label = L("Person"),
                    options = listOf("" to L("Everyone")) +
                        people.map { (it.memberId ?: "") to (it.name.nonEmpty ?: L("Someone")) } +
                        (chosen?.let { id -> if (people.any { it.memberId == id }) emptyList() else listOf(id to L("Someone")) } ?: emptyList()),
                    selection = stateOf({ model.memberId ?: "" }, { model.memberId = it.nonEmpty; reload() }),
                )
            } else {
                MenuField(
                    label = L("Kind of information"),
                    options = listOf("" to L("Every kind")) + model.areas.map { it.key to (it.label?.text.nonEmpty ?: it.key) },
                    selection = stateOf({ model.tool ?: "" }, { model.tool = it.nonEmpty; reload() }),
                )
            }
        }
        FormField(label = L("Dates")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateFilter(L("From"), model.from) { value ->
                        model.from = value
                        val to = model.to
                        if (to != null && value != null && to < value) model.to = value
                        reload()
                    }
                    DateFilter(L("To"), model.to) { value ->
                        model.to = value
                        val from = model.from
                        if (from != null && value != null && value < from) model.from = value
                        reload()
                    }
                }
                if (model.from != null || model.to != null) {
                    PMButton(L("Clear the dates"), systemImage = "x", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                        model.from = null; model.to = null; reload()
                    }
                }
            }
        }
        if (model.petId != null || model.filtered) {
            FlowLayout(spacing = 8.dp) {
                if (model.petId != null) {
                    Row(
                        Modifier
                            .plainButton(label = L("Show every pet")) { model.petId = null; model.petName = null; reload() }
                            .heightIn(min = 36.dp)
                            .background(Theme.surface, CapsuleShape)
                            .border(1.dp, Theme.borderSoft, CapsuleShape)
                            .padding(start = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("paw-print", 14.dp, tint = Theme.inkStrong)
                        Text(LF("Pet: %@", model.petName.nonEmpty ?: L("a pet")), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Glyph("x", 16.dp, tint = Theme.inkSoft) }
                    }
                }
                if (model.filtered) {
                    PMButton(L("Clear the filters"), variant = ButtonVariant.outline, size = ButtonSize.compact) { model.clear(); reload() }
                }
            }
        }
        TeamOlfatoTeamList(
            pager = model.pager,
            filtered = model.filtered,
            onPickPet = { pet -> model.petId = pet.id; model.petName = pet.name; reload() },
            onPickMember = { id -> model.memberId = id; reload() },
            onClear = { model.clear(); reload() },
        )
    }
}

/** "From" / "To" and the day, or "Choose a day" until one is chosen (no later than today). */
@Composable
private fun DateFilter(label: String, value: Instant?, set: (Instant?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Theme.text(13, FontWeight.Bold), color = Theme.inkSoft)
        if (value != null) {
            CompactDatePicker(
                selection = stateOf({ value }, { set(it) }),
                max = Instant.now(),
                components = setOf(DatePickerComponent.date),
            )
        } else {
            PillField(text = L("Choose a day"), icon = "calendar-days", placeholder = true, modifier = Modifier.plainButton { set(Instant.now()) })
        }
    }
}

@Composable
private fun WeeklyCounts(weekly: TeamOlfatoWeekly, model: TeamOlfatoTeamUseModel, reload: () -> Unit) {
    val weeks = weekly.weeks ?: emptyList()
    val members = weekly.members ?: emptyList()
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(L("Questions per week"), Modifier.weight(1f).alignByBaseline(), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong)
            Text(LF("Last %d weeks", weeks.size), Modifier.alignByBaseline(), style = Theme.text(12), color = Theme.inkSoft)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(box)
                .border(1.dp, Theme.borderSoft, box),
        ) {
            members.forEach { member ->
                androidx.compose.runtime.key(member.key) {
                    WeekRow(member, weeks, model, reload)
                    if (member.key != members.lastOrNull()?.key) RowDivider()
                }
            }
        }
    }
}

@Composable
private fun WeekRow(member: TeamOlfatoWeekly.Member, weeks: List<String>, model: TeamOlfatoTeamUseModel, reload: () -> Unit) {
    val name = member.name?.trim().nonEmpty ?: L("Someone")
    val counts = member.counts ?: emptyList()
    val index = (member.counts?.size ?: 1) - 1
    val questions = if (index >= 0) (counts.getOrNull(index) ?: 0) else 0
    val pets = if (index >= 0 && index < (member.distinctPets?.size ?: 0)) (member.distinctPets?.getOrNull(index) ?: 0) else 0
    val kinds = if (index >= 0 && index < (member.kinds?.size ?: 0)) (member.kinds?.getOrNull(index) ?: emptyMap()) else emptyMap()
    val kindKeys = kinds.filter { it.value > 0 }.entries.sortedByDescending { it.value }.map { it.key }
    val kindNames = kindKeys.joinToString(", ") { key -> model.areas.firstOrNull { it.key == key }?.label?.text.nonEmpty ?: key }
    val selected = member.memberId != null && member.memberId == model.memberId
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Theme.surfaceMuted else Color.Transparent)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val id = member.memberId
            if (id != null) {
                Text(
                    name,
                    Modifier.plainButton(label = LF("Show only %@", name)) { model.memberId = id; reload() },
                    style = Theme.text(14, FontWeight.Bold),
                    color = Theme.inkStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(LF("%@ (left the team)", name), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                L("This week:") + " " + (if (questions == 0) L("No questions this week.") else listOf(
                    LP(questions, "1 question", "%d questions"),
                    LP(pets, "1 pet", "%d pets"),
                    LP(kindKeys.size, "1 kind", "%d kinds"),
                ).joinToString(" · ")),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
            if (questions > 0 && kindNames.isNotEmpty()) {
                Text(kindNames, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TeamOlfatoWeekBars(weeks = weeks, counts = counts)
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.End) {
            Text("${member.total ?: 0}", style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
            if ((member.distinctPetsTotal ?: 0) > 0) {
                Text(LP(member.distinctPetsTotal ?: 0, "1 pet", "%d pets"), style = Theme.text(11), color = Theme.inkSoft)
            }
        }
    }
}

/** Eight small bars: a person's questions week by week, the last one this week. */
@Composable
private fun TeamOlfatoWeekBars(weeks: List<String>, counts: List<Int>) {
    val top = maxOf(1, counts.maxOrNull() ?: 1)
    Row(Modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        counts.forEachIndexed { index, count ->
            val label = LF("Week of %1\$@: %2\$@", if (index < weeks.size) TeamDays.short(weeks[index]) else "", LP(count, "1 question", "%d questions"))
            Box(
                Modifier
                    .semantics { contentDescription = label }
                    .width(7.dp)
                    .height(maxOf(3f, 28f * count / top).dp)
                    .background(if (count > 0) Theme.aqua else Theme.borderSoft, CapsuleShape)
            )
        }
    }
}

/** The team's lines, 20 at a time, with the empty states the filters call for. */
@Composable
private fun TeamOlfatoTeamList(
    pager: Pager<OlfatoAccessEntry>,
    filtered: Boolean,
    onPickPet: (OlfatoAccessEntry.Pet) -> Unit,
    onPickMember: (String) -> Unit,
    onClear: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (!pager.loadedOnce && pager.isLoading) {
        TeamProgress(minHeight = 80.dp)
    } else if (pager.error != null && pager.items.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                .padding(horizontal = 16.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(L("Could not load the team’s use of Olfato AI."), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, textAlign = TextAlign.Center)
            PMButton(L("Try again"), variant = ButtonVariant.outline) { scope.launch { pager.reload() } }
        }
    } else if (pager.items.isEmpty()) {
        EmptyState(
            systemImage = "message-circle-question",
            title = if (filtered) L("No questions match these filters.") else L("No questions to Olfato AI yet."),
            message = if (filtered) L("Change or clear the filters to see more.") else L("When someone on the team asks Olfato AI, a line shows here: who, when and about what. Never the text."),
            actionTitle = if (filtered) L("Clear the filters") else null,
            action = if (filtered) onClear else null,
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            pager.items.forEach { entry ->
                androidx.compose.runtime.key(entry.id) {
                    Box(Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(entry) }) {
                        OlfatoAccessLine(entry = entry, onPickPet = onPickPet, onPickMember = onPickMember)
                    }
                    if (entry.id != pager.items.lastOrNull()?.id) RowDivider()
                }
            }
            PagerFooter(pager)
        }
        Text(
            LF("Showing %1\$d of %2\$d", pager.items.size, maxOf(pager.total ?: 0, pager.items.size)),
            style = Theme.text(12, FontWeight.SemiBold),
            color = Theme.inkSoft,
        )
    }
}
