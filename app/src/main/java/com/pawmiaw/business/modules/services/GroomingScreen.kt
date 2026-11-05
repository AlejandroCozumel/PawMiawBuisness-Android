package com.pawmiaw.business.services

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.max

//
//  GroomingScreen.kt (iOS GroomingScreen.swift)
//
//  Estética (web: routes/provider.grooming.tsx, components/app/grooming/):
//  the day's grooms by stage, the pets groomed here, who is due back and how
//  this business grooms. A groom is a visit: arriving, handing over and
//  charging stay on the visit's page (`ProviderRoute.appointment`).
//  What needs someone (backend docs/grooming-board.md) is flagged on the card.
//

@Composable
fun GroomingScreen(modifier: Modifier = Modifier) {
    providerPage(L("Grooming"), root = true, modifier = modifier) {
        RequireLocation { SvcGroomingPage() }
    }
}

private enum class SvcGroomTab { today, pets, due, settings }

@Composable
private fun SvcGroomingPage() {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val tab = remember { mutableStateOf(SvcGroomTab.today) }
    var today by remember { mutableStateOf<SvcGroomingToday?>(null) }
    var todayError by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf<SvcGroomingSettings?>(null) }
    var overdue by remember { mutableStateOf<Int?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var gate by remember { mutableStateOf<SvcStartGateRequest?>(null) }
    var reportTarget by remember { mutableStateOf<SvcReportTarget?>(null) }

    val canView = workspace.can(Permission.appointmentsView)
    val canSeePets = workspace.can(Permission.clientsView)
    val canSeeDue = workspace.canAny(Permission.followupsView, Permission.appointmentsCreate)
    val canStage = workspace.can(Permission.appointmentsComplete)
    val canBook = workspace.canAll(Permission.appointmentsCreate, Permission.clientsView)
    val canRemind = workspace.canAny(Permission.followupsEdit, Permission.appointmentsCreate)
    val canEditCard = workspace.canAny(Permission.petsEdit, Permission.appointmentsComplete)
    val simple = settings?.simpleStages ?: false
    val push: (ProviderRoute) -> Unit = { nav.push(it) }

    if (!canView) {
        PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see appointments.")) }
        return
    }

    val tabs = buildList {
        add(ToneTabs.Tab(SvcGroomTab.today, L("Today"), icon = "calendar-days"))
        if (canSeePets) add(ToneTabs.Tab(SvcGroomTab.pets, L("Pets"), icon = "paw-print"))
        if (canSeeDue) add(ToneTabs.Tab(SvcGroomTab.due, L("Due back"), icon = "bell-ring", count = overdue))
        add(ToneTabs.Tab(SvcGroomTab.settings, L("Settings"), icon = "settings-2"))
    }

    // MARK: Loading and moves

    suspend fun load() {
        try {
            today = API.get<SvcGroomingToday>("/provider-grooming/today", mapOf("locationId" to workspace.locationId))
            todayError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (today == null) todayError = errorText(e)
        }
        attempt { API.get<SvcGroomingSettings>("/provider-grooming/settings") }?.let { settings = it }
        if (canBook) SvcServices.load("provider")
        if (canSeePets) {
            attempt { API.get<SvcGroomPetsPage>("/provider-grooming/pets", mapOf("size" to "1")) }?.let { overdue = it.counts?.overdue }
        }
    }

    /** Moving out of the waiting room starts the visit: a missing consent or deposit asks here (owners and admins can start anyway). */
    suspend fun move(item: SvcGroomItem, stage: String) {
        busyId = item.id
        try {
            SvcAPI.send<SvcIgnored>("PATCH", "/provider-appointments/${item.id}/groom-stage", body = mapOf("stage" to stage))
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = svcIsStartGate(e)
            if (code != null) {
                if (workspace.isOwnerOrAdmin) {
                    val id = item.id
                    gate = SvcStartGateRequest(code = code, petName = item.pet.name) { override ->
                        API.post<SvcIgnored>("/provider-appointments/$id/start", override)
                        // A started groom is in the bath already.
                        if (stage != "bath") API.patch<SvcIgnored>("/provider-appointments/$id/groom-stage", mapOf("stage" to stage))
                    }
                } else {
                    Toast.error(svcMessage(e) + " " + L("Open the visit to see what it needs."))
                }
                return
            }
            Toast.error(svcMessage(e, codes = SvcRefusals.grooming))
        } finally {
            busyId = null
        }
    }

    /** "Ready" told to the family once per Ready (the server keeps the stamp): in PawMiaw, or WhatsApp opened with the message. */
    suspend fun tell(item: SvcGroomItem, channel: String) {
        busyId = item.id
        try {
            val result = SvcAPI.send<SvcGroomReadyResult>(
                "POST", "/provider-appointments/${item.id}/groom-ready",
                body = mapOf("channel" to channel, "locale" to svcLocale),
            )
            val link = result.link
            if (channel == "whatsapp" && link != null) {
                svcOpen(link)
                Toast.show(L("WhatsApp is open with the message: send it from there."))
            } else {
                Toast.show(LF("%@ was told in PawMiaw that %@ is ready.", item.client.shortName, item.pet.name))
            }
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(svcMessage(e, codes = SvcRefusals.grooming))
        } finally {
            busyId = null
        }
    }

    // MARK: Header

    val stats = run {
        val shown = today
        val done = shown?.count("done") ?: 0
        val waiting = shown?.count("waiting") ?: 0
        val out = mutableListOf(
            ModuleStat(
                id = "today", label = L("Today’s grooms"), value = shown?.let { "${it.total}" } ?: "—",
                hint = if (done > 0) LF("%d done", done) else null, icon = "scissors", accent = Theme.pink,
            ),
            ModuleStat(
                id = "progress", label = L("In progress"), value = shown?.let { "${it.inProgress}" } ?: "—",
                hint = if (waiting > 0) LF("%d waiting", waiting) else null, icon = "shower-head", accent = Theme.aqua,
            ),
            ModuleStat(
                id = "ready", label = L("Ready for pickup"), value = shown?.let { "${it.ready}" } ?: "—",
                icon = "package-check", accent = Theme.lime, attention = (shown?.ready ?: 0) > 0,
            ),
        )
        if (canSeePets) {
            out.add(
                ModuleStat(
                    id = "due", label = L("Due back"), value = overdue?.toString() ?: "—",
                    hint = if ((overdue ?: 0) > 0) L("Overdue") else null, icon = "bell-ring", accent = Theme.mango,
                    attention = (overdue ?: 0) > 0, active = canSeeDue && tab.value == SvcGroomTab.due,
                    action = if (canSeeDue) ({ tab.value = SvcGroomTab.due }) else null,
                ),
            )
        }
        out
    }

    PageScroll(refresh = { load() }) {
        ModuleHeader(
            icon = "scissors",
            title = L("Grooming"),
            subtitle = today?.let { svcCapitalized(SvcTime.dayLong(it.date)) } ?: L("The day’s grooms, the pets you groom and who is due back"),
            accent = Theme.pink,
            stats = stats,
            storageKey = "grooming",
        ) {
            if (canBook) {
                // The Agenda's booking, on grooming and today.
                PMButton(L("New groom"), systemImage = "plus") { push(SvcBook.groom(day = today?.date)) }
            }
        }
        today?.let { shown ->
            val needs = shown.needsSomeone ?: 0
            if (needs > 0) {
                NoticeCard(
                    tone = NoticeCard.Tone.problem,
                    title = LP(needs, "%d groom needs someone", "%d grooms need someone"),
                    message = L("Here from an earlier day, ready with nobody told, or a pickup running late: each card says which."),
                )
            }
        }
        ToneTabs(tabs = tabs, selection = tab, look = ToneTabs.Look.solid)

        when (tab.value) {
            SvcGroomTab.today -> {
                val shown = today
                if (shown != null) {
                    SvcGroomBoard(
                        today = shown, simple = simple, canStage = canStage, busyId = busyId, push = push,
                        onNewGroom = if (canBook) ({ push(SvcBook.groom(day = shown.date)) }) else null,
                        onReport = { item ->
                            reportTarget = SvcReportTarget(
                                subtitle = "${item.pet.name} · ${item.serviceLabel ?: ""}",
                                pets = listOf(SvcReportPet(id = item.id, name = item.pet.name)),
                                canWrite = canStage,
                            )
                        },
                        onMove = { item, stage -> scope.launch { move(item, stage) } },
                        onTell = { item, channel -> scope.launch { tell(item, channel) } },
                    )
                } else if (todayError != null) {
                    SvcLoadError(message = L("Today’s grooms couldn’t be loaded.")) { load() }
                } else {
                    LoadingState()
                }
            }
            SvcGroomTab.pets -> SvcGroomPetsTab(canBook = canBook, canEditCard = canEditCard, canRemind = canRemind, push = push)
            SvcGroomTab.due -> SvcGroomDueTab(canRemind = canRemind, canBook = canBook, push = push)
            SvcGroomTab.settings -> SvcGroomSettingsTab(canManage = workspace.can(Permission.workspaceEdit), businessName = workspace.current?.name ?: "")
        }
    }

    gate?.let { request ->
        AppSheet(onDismissRequest = { gate = null }) {
            SvcStartGateSheet(request = request, onDone = { announceDataChanged() })
        }
    }
    reportTarget?.let { target ->
        AppSheet(onDismissRequest = { reportTarget = null }) { SvcReportSheet(target = target) }
    }
    ReloadsOnScope { load() }
    LaunchedEffect(workspace.scopeVersion) {
        // The board follows the day as the web's does (every minute).
        while (true) {
            delay(60_000)
            load()
        }
    }
}

/** Swift `try?` on an await: the answer, or null when it failed (a cancel still cancels). */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

// MARK: - The board

/**
 * The day's grooms by stage (web grooming-board.tsx): each stage a muted column
 * with its dot and count, its grooms as cards; done folds at the end, one line each.
 * `onNewGroom`: "New groom" in the empty state; null when the member can't book.
 */
@Composable
private fun SvcGroomBoard(
    today: SvcGroomingToday,
    simple: Boolean,
    canStage: Boolean,
    busyId: String?,
    push: (ProviderRoute) -> Unit,
    onNewGroom: (() -> Unit)?,
    onReport: (SvcGroomItem) -> Unit,
    onMove: (SvcGroomItem, String) -> Unit,
    onTell: (SvcGroomItem, String) -> Unit,
) {
    val showDone = remember { mutableStateOf(false) }

    val columns: List<Pair<String, List<SvcGroomItem>>> = run {
        val stages = SvcGroomStage.visible(simple = simple)
        val grouped = mutableMapOf<String, MutableList<SvcGroomItem>>()
        for (item in today.items) {
            val stage = SvcGroomStage.shown(item.groomStage, simple = simple)
            grouped.getOrPut(if (stage in stages) stage else "scheduled") { mutableListOf() }.add(item)
        }
        stages.map { stage ->
            stage to (grouped[stage] ?: emptyList<SvcGroomItem>()).sortedWith(compareBy({ it.startsAt }, { it.pet.name }))
        }
    }

    if (today.items.isEmpty()) {
        EmptyState(
            systemImage = "scissors",
            title = L("No grooms today"),
            message = L("Today’s baths and haircuts appear here, each in its stage: waiting, in the bath, ready for pickup."),
            actionTitle = if (onNewGroom == null) null else L("New groom"),
            action = onNewGroom,
            accent = Theme.pink,
            panel = true,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((stage, items) in columns.filter { it.first != "done" }) {
                    SvcStageColumn(stage, items, simple, today.timeZone, canStage, busyId, push, onReport, onMove, onTell)
                }
            }
            val done = columns.firstOrNull { it.first == "done" }?.second ?: emptyList()
            if (done.isNotEmpty()) SvcDoneFold(done, simple, showDone, push)
        }
    }
}

@Composable
private fun SvcStageColumn(
    stage: String,
    items: List<SvcGroomItem>,
    simple: Boolean,
    timeZone: String?,
    canStage: Boolean,
    busyId: String?,
    push: (ProviderRoute) -> Unit,
    onReport: (SvcGroomItem) -> Unit,
    onMove: (SvcGroomItem, String) -> Unit,
    onTell: (SvcGroomItem, String) -> Unit,
) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    val title = SvcGroomStage.label(stage, simple = simple)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = title }
            .background(Theme.wash(Theme.surfaceMuted, 45.0), shape)
            .border(1.dp, Theme.borderSoft, shape),
    ) {
        SectionHeading(
            title = title,
            count = items.size,
            dot = ProviderState.groom(stage).color,
            style = SectionHeading.Style.label,
            bubble = Theme.surface,
            showsZero = true,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
        )
        SvcAdaptiveGrid(
            items = if (items.isEmpty()) listOf<SvcGroomItem?>(null) else items,
            minimum = 300.dp,
            spacing = 10.dp,
            modifier = Modifier.padding(10.dp),
        ) { item ->
            if (item == null) {
                Text(
                    L("Nobody here"),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkSoft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedBorder(1.5.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                SvcGroomCard(
                    item = item, simple = simple, timeZone = timeZone, canStage = canStage, busy = busyId == item.id, push = push,
                    onReport = { onReport(item) }, onMove = { onMove(item, it) }, onTell = { onTell(item, it) },
                )
            }
        }
    }
}

/** Done is the quiet end of the day: folded, one line each. */
@Composable
private fun SvcDoneFold(done: List<SvcGroomItem>, simple: Boolean, showDone: MutableState<Boolean>, push: (ProviderRoute) -> Unit) {
    val turn by animateFloatAsState(if (showDone.value) 180f else 0f, tween(200, easing = EaseOut), label = "done chevron")
    Column(Modifier.listCard()) {
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton { showDone.value = !showDone.value }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading(
                title = SvcGroomStage.label("done", simple = simple),
                count = done.size,
                dot = ProviderState.groom("done").color,
                style = SectionHeading.Style.label,
                bubble = Theme.surfaceMuted,
                showsZero = true,
                modifier = Modifier.weight(1f),
            )
            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(
            visible = showDone.value,
            enter = fadeIn(tween(200, easing = EaseOut)) + expandVertically(tween(200, easing = EaseOut)),
            exit = fadeOut(tween(200, easing = EaseOut)) + shrinkVertically(tween(200, easing = EaseOut)),
        ) {
            Column {
                for (item in done) {
                    RowDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PetAvatar(
                            name = item.pet.name, species = item.pet.species, photoURL = item.pet.photoUrl, size = 32.dp,
                            modifier = Modifier.plainButton { push(ProviderRoute.patientChart(item.pet.id)) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.pet.name,
                                style = Theme.text(14, FontWeight.Bold),
                                color = Theme.inkStrong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.plainButton { push(ProviderRoute.patientChart(item.pet.id)) },
                            )
                            Text(
                                listOfNotNull(item.serviceLabel.nonEmpty, item.client.name.nonEmpty, item.groomer?.name.nonEmpty).joinToString(" · "),
                                style = Theme.text(12),
                                color = Theme.inkSoft,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SvcGroomBillingChip(billing = item.billing)
                        Text(
                            L("Open visit"),
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.blue,
                            modifier = Modifier.plainButton { push(ProviderRoute.appointment(item.id)) },
                        )
                    }
                }
            }
        }
    }
}

/** Where a groom's bill stands (web `BillingChip`): its state's colour at 24%, a receipt. */
@Composable
private fun SvcGroomBillingChip(billing: String?) {
    val label = SvcBilling.label(billing) ?: return
    Chip(text = label, tone = Chip.Tone.custom(SvcBilling.state(billing).color), icon = "receipt", percent = 24.0)
}

// MARK: - One groom

/**
 * A groom's card (web `GroomCard`): a 4 pt edge in its stage's colour, the pet
 * (photo and name open the chart), when and who, what needs someone, what was
 * asked, how to handle the pet, and one button for the next stage.
 */
@Composable
private fun SvcGroomCard(
    item: SvcGroomItem,
    simple: Boolean,
    timeZone: String?,
    canStage: Boolean,
    busy: Boolean,
    push: (ProviderRoute) -> Unit,
    onReport: () -> Unit,
    onMove: (String) -> Unit,
    onTell: (String) -> Unit,
) {
    val stage = SvcGroomStage.shown(item.groomStage, simple = simple)
    val next = SvcGroomStage.next(stage, simple = simple)
    val previous = SvcGroomStage.previous(stage, simple = simple)
    val shape = RoundedCornerShape(Theme.boxRadius)
    val edge = ProviderState.groom(stage).color
    val shadow = Color.Black.copy(alpha = 0.08f)

    fun attentionText(attention: String): String = when (attention) {
        "carried_over" -> LF("Here since %@ · never handed over", SvcTime.day(item.startsAt))
        "not_told" -> LF("Ready %@ ago · family not told", SvcTime.waited(item.readySince))
        else -> LF("Ready %@ ago · not picked up", SvcTime.waited(item.readySince))
    }

    /** When the family was told: the time, with its day when that wasn't today. */
    fun stamp(iso: String): String {
        val time = SvcTime.clock(iso, timeZone)
        val date = DateText.parse(iso) ?: return time
        val zone = SvcTime.zone(timeZone)
        return if (DateText.dayKey(date, zone) == DateText.dayKey(Instant.now(), zone)) time else "${SvcTime.day(iso)}, $time"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (busy) 0.85f else 1f)
            .shadow(2.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .background(Theme.surface)
            .drawBehind { drawRect(edge, size = Size(4.dp.toPx(), size.height)) }
            .border(1.5.dp, Theme.borderSoft, shape)
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top: the pet's photo and name open its chart, as everywhere.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            PetAvatar(
                name = item.pet.name, species = item.pet.species, photoURL = item.pet.photoUrl, size = 40.dp,
                modifier = Modifier.plainButton(label = LF("Open %@’s chart", item.pet.name)) { push(ProviderRoute.patientChart(item.pet.id)) },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.pet.name,
                    style = brandHeading(18),
                    color = Theme.inkStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.plainButton { push(ProviderRoute.patientChart(item.pet.id)) },
                )
                Text(
                    listOfNotNull(item.client.name.nonEmpty, item.pet.breed.nonEmpty).joinToString(" · "),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppMenu(
                label = { IconCircleLabel(size = 32.dp, bordered = false) },
                accessibilityLabel = LF("More for %@", item.pet.name),
                modifier = Modifier.svcOutset(top = 4.dp, trailing = 4.dp),
            ) {
                if (canStage && previous != null) {
                    item(LF("Back to %@", SvcGroomStage.label(previous, simple = simple)), icon = "undo-2") { onMove(previous) }
                }
                if (stage in listOf("bath", "styling", "ready")) {
                    // The report the family gets at the end of the groom (notes, before and after photos).
                    item(if (item.hasAfterPhoto == true) L("Report") else L("Write report"), icon = "clipboard-pen") { onReport() }
                }
                item(L("Open visit"), icon = "external-link") { push(ProviderRoute.appointment(item.id)) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SvcGroomMeta(
                "clock-3",
                (if (item.carriedOver == true) SvcTime.day(item.startsAt) + " · " else "") + SvcTime.clock(item.startsAt, timeZone) + " · " + (item.serviceLabel ?: ""),
                strong = true,
            )
            SvcGroomMeta("user-round", item.groomer?.name.nonEmpty ?: L("No groomer yet"))
        }

        val attention = item.attention
        if (attention != null) {
            // What needs someone now: it stays until the pet goes home or the family is told.
            Chip(text = attentionText(attention), tone = Chip.Tone.danger, icon = "triangle-alert")
        } else if (item.carriedOver == true) {
            // Booked for an earlier day and never handed over: it stays on the board until it goes home.
            Chip(text = L("Here since an earlier day"), tone = Chip.Tone.custom(Theme.mango), icon = "clock-3", percent = 28.0)
        }

        val asked = item.requestSummary.nonEmpty
        val usual = item.usualStyle.nonEmpty
        if (asked != null) {
            // What the owner asked for, in their words: the groomer reads it before the first cut.
            SvcAskedBox(text = asked)
        } else if (usual != null) {
            val soft = Theme.inkSoft
            val strong = Theme.inkStrong
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = soft)) { append(L("Usual:") + " ") }
                    withStyle(SpanStyle(color = strong)) { append(usual) }
                },
                style = Theme.text(14),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SvcHandlingChips(tags = item.handlingTags ?: emptyList())
        item.careNotes.nonEmpty?.let { notes ->
            Text(notes, style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        if (stage == "ready") {
            FlowLayout(spacing = 6.dp) {
                val told = item.readyNotifiedAt
                if (told != null) {
                    // Already told: so nobody messages the same family twice. The app delivers its notice;
                    // WhatsApp only opens with the message written — sending it is the person's.
                    Chip(
                        text = if (item.readyChannel == "app") LF("Ready · told %@ · %@", stamp(told), "PawMiaw") else LF("Ready · WhatsApp opened %@", stamp(told)),
                        tone = Chip.Tone.custom(ProviderState.done.color),
                        icon = "check-check",
                        percent = 16.0,
                    )
                } else {
                    Chip(text = L("Not told yet"), tone = Chip.Tone.custom(Theme.mango), percent = 28.0)
                }
                SvcGroomBillingChip(billing = item.billing)
            }
        }

        // The next stage, and at Ready telling the family and handing over (where it's charged).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowDivider()
            FlowLayout(spacing = 8.dp) {
                if (stage == "scheduled") {
                    // Arrival (and its consents) is the visit's own step.
                    PMButton(L("Check in"), systemImage = "door-open", size = ButtonSize.header) { push(ProviderRoute.appointment(item.id)) }
                }
                if (next != null && canStage) {
                    PMButton(
                        SvcGroomStage.moveLabel(to = next, from = stage, simple = simple),
                        systemImage = "arrow-right",
                        size = ButtonSize.header,
                        isWorking = busy,
                    ) { onMove(next) }
                }
                if (stage == "ready" && !canStage) {
                    PMButton(L("Hand over & charge"), systemImage = "receipt", size = ButtonSize.header) { push(ProviderRoute.appointment(item.id)) }
                }
            }
            if (stage == "ready" && canStage) {
                FlowLayout(spacing = 8.dp) {
                    Eyebrow(if (item.readyNotifiedAt != null) L("Tell again") else L("Tell them"), size = 12)
                    if (item.client.phone.nonEmpty != null) {
                        PMButton(L("WhatsApp"), systemImage = "message-circle", variant = ButtonVariant.outline, size = ButtonSize.header, enabled = !busy) { onTell("whatsapp") }
                    }
                    if (item.client.onPawMiaw == true) {
                        PMButton("PawMiaw", systemImage = "smartphone", variant = ButtonVariant.outline, size = ButtonSize.header, enabled = !busy) { onTell("app") }
                    }
                    if (item.client.phone.nonEmpty == null && item.client.onPawMiaw != true) {
                        Text(L("No phone, and not on PawMiaw."), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                // Handing the pet over is finishing the visit, and that is where it's charged.
                PMButton(L("Hand over & charge"), systemImage = "receipt", size = ButtonSize.header) { push(ProviderRoute.appointment(item.id)) }
            }
            if (!canStage && next != null) {
                Text(L("Your role can’t move grooms between stages."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

@Composable
private fun SvcGroomMeta(icon: String, text: String, strong: Boolean = false) {
    val ink = if (strong) Theme.inkStrong else Theme.inkSoft
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 14.dp, tint = ink)
        Text(
            text,
            style = Theme.text(12, if (strong) FontWeight.Bold else FontWeight.Normal),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Layout helpers (Kotlin only)

/**
 * SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)], spacing:)`
 * inside a scrolling page: as many equal columns as fit at `minimum` wide, each cell at the top of its row.
 */
@Composable
private fun <T> SvcAdaptiveGrid(
    items: List<T>,
    minimum: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
    cell: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    chunk.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** SwiftUI `.padding(.top, -top).padding(.trailing, -trailing)`: the view sits higher and further out, taking that much less room. */
private fun Modifier.svcOutset(top: Dp = 0.dp, trailing: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = top.roundToPx()
    val out = trailing.roundToPx()
    layout((placeable.width - out).coerceAtLeast(0), (placeable.height - up).coerceAtLeast(0)) {
        placeable.placeRelative(0, -up)
    }
}
