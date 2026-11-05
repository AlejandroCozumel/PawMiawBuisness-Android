package com.pawmiaw.business.clients

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.pawmiaw.business.*
import com.pawmiaw.business.inbox.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

//
//  ClientsChartBrief.kt (iOS ClientsChartBrief.swift)
//
//  The Olfato AI brief at the top of a pet's chart (web: patient-chart/
//  chart-ai-summary.tsx, summary-sources.tsx). The server writes it from the
//  pet's records; the app asks for it, asks again while it is being written
//  and shows what the server says: the warnings and dates straight from the
//  records, the brief with numbered sources, what was read and left out,
//  and a thumbs up or down. "Preguntar a Olfato" opens a chat about the pet.
//

/** The brief's state (Swift's `@State`s) and what it derives (chart-ai-summary.tsx §1.4). */
@Stable
private class ClientsBriefModel(language: String) {
    var language by mutableStateOf(language)
    var brief by mutableStateOf<ClientsBrief?>(null)
    var loadFailed by mutableStateOf(false)
    var refreshFailed by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var switching by mutableStateOf(false)
    var kept by mutableStateOf<ClientsBrief?>(null)
    var settings by mutableStateOf<ClientsBriefSettings?>(null)
    val ensured = mutableSetOf<String>()
    val recovered = mutableSetOf<String>()

    val status: String get() = brief?.status ?: ""
    val off: Boolean get() = status == "disabled" || status == "off"
    val paused: Boolean get() = status == "queued" && brief?.errorCode == "daily_limit"
    val held: Boolean get() = status == "queued" && brief?.errorCode == "visit_open"
    val empty: Boolean get() = status == "empty"
    val queuedOrRunning: Boolean get() = status == "queued" || status == "running"
    val busy: Boolean get() = brief != null && !off && !empty && !paused && (refreshing || (!held && queuedOrRunning))
    val delayed: Boolean get() = brief?.delayed == true && !paused && !held && queuedOrRunning
    val current: Boolean get() = !loadFailed && status == "current"
    val writing: Boolean get() = busy || switching
    val rewriting: Boolean get() = !current && kept != null && kept?.sourceRevision == brief?.sourceRevision && writing
    val shown: ClientsBriefText? get() = if (current) brief?.summary else if (rewriting) kept?.summary else null
    val dimmed: Boolean get() = shown != null && (switching || rewriting || (busy && current))
    val failed: Boolean get() = status == "failed"
    val nothing: Boolean get() = status == "nothing_reliable"
    val retryLater: Boolean get() = failed && (DateText.parse(brief?.retryAt)?.let { it > Instant.now() } ?: false)
    val generated: Boolean get() = brief != null && !off && !empty && status != "missing"
    val textless: Boolean get() = brief != null && (failed || nothing || status == "missing")
    val organized: Boolean get() = generated && !textless
    val branded: Boolean get() = brief != null && !off
    /** The brief source for what the screen shows (kept text while rewriting). */
    val source: ClientsBrief? get() = if (current) brief else if (rewriting) kept else brief

    val uncoveredMeds: List<ClientsBriefMedication> get() = (source?.currentMedications ?: emptyList()).filter { it.covered != true && it.origin != "record" }
    val uncoveredTreatment: List<ClientsBriefMedication> get() = (source?.recentTreatment ?: emptyList()).filter { it.covered != true }
    val uncoveredCare: List<ClientsBriefCareFact> get() = (source?.careFacts ?: emptyList()).filter { it.covered != true }

    /** Every cited source, numbered in reading order. */
    val numbered: List<ClientsBriefSource>
        get() {
            val all = source?.sources ?: emptyList()
            val byId = LinkedHashMap<String, ClientsBriefSource>()
            all.forEach { if (!byId.containsKey(it.id)) byId[it.id] = it }
            val order = mutableListOf<String>()
            val add = { ids: List<String> -> ids.forEach { id -> if (byId[id] != null && !order.contains(id)) order.add(id) } }
            (source?.warnings ?: emptyList()).filter { it.covered != true }.forEach { w -> add(listOfNotNull(w.sourceId)) }
            (source?.conflicts ?: emptyList()).forEach { c -> add(c.sourceIds ?: emptyList()) }
            val text = shown
            for (key in listOf("overview", "recent", "pending") + ClientsBriefWords.tabs) {
                if (key == "weight") add(listOfNotNull(source?.weights?.latest?.sourceId, source?.weights?.previous?.sourceId))
                text?.items(key)?.forEach { add(it.sourceIds ?: emptyList()) }
                if (key == "medications") add((uncoveredMeds + uncoveredTreatment).mapNotNull { it.sourceId })
                if (key == "care") add(uncoveredCare.mapNotNull { it.sourceId })
            }
            return order.mapNotNull { byId[it] }
        }
}

@Composable
fun ClientsChartBrief(
    chart: ClientsChart,
    /** Weighing from the weight card the brief shows under its Weight tab. */
    canWeigh: Boolean = false,
    reload: suspend () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Opens the chart's Follow-ups tab (a follow-up source). */
    onFollowUps: () -> Unit,
) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val olfato = remember { InboxOlfatoViewer() }
    val m = remember { ClientsBriefModel(if (LocalizationManager.currentLanguage == AppLanguage.spanish) "es" else "en") }
    val tab = remember { mutableStateOf("problems") }
    var tabChosen by remember { mutableStateOf(false) }
    var readOpen by remember { mutableStateOf<Boolean?>(null) }
    var techOpen by remember { mutableStateOf(false) }
    var openSource by remember { mutableStateOf<ClientsBriefSourceTarget?>(null) }
    var ratingSent by remember { mutableStateOf<String?>(null) }
    var ratingFailed by remember { mutableStateOf(false) }
    var downReasons by remember { mutableStateOf<Set<String>>(emptySet()) }
    val downComment = remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<String?>(null) }

    val path = "/provider-clients/${chart.client.id}/pets/${chart.pet.id}"
    /** "Preguntar a Olfato": the chat's own rule (`InboxOlfatoViewer.showsEntry`) for someone who may chat. */
    val asksOlfato = workspace.can(Permission.olfatoChat) && olfato.showsEntry

    // MARK: Loading

    fun apply(answer: ClientsBrief) {
        m.brief = answer
        if (answer.status == "current") m.kept = answer
    }

    suspend fun generate(retry: Boolean) {
        try {
            val answer: ClientsBrief = API.post("$path/generate-summary", query = mapOf("language" to m.language, "retry" to if (retry) "true" else "false"))
            apply(answer)
            m.refreshFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (retry) m.refreshFailed = true
        }
    }

    /** The first read of a revision asks the server to write it (once); a brief gone missing is asked for once more. */
    suspend fun ensure(answer: ClientsBrief) {
        val revision = answer.sourceRevision ?: return
        if (listOf("disabled", "off", "failed").contains(answer.status)) return
        val key = "${m.language}:$revision"
        if (!m.ensured.contains(key)) {
            m.ensured.add(key)
            generate(retry = false)
        } else if (answer.status == "missing" && !m.recovered.contains(key)) {
            m.recovered.add(key)
            generate(retry = false)
        }
    }

    suspend fun load() {
        if (chart.viewer?.seesRecords != true) return
        try {
            val answer: ClientsBrief = API.get("$path/summary", mapOf("language" to m.language))
            apply(answer)
            m.loadFailed = false
            m.switching = false
            if (answer.status == "off" && m.settings == null) {
                m.settings = try {
                    API.get<ClientsBriefSettings>("/provider-patients/ai-summary-settings")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            ensure(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            m.loadFailed = true
            m.switching = false
        }
    }

    suspend fun poll() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            load()
            val seconds: Long = when {
                m.status == "disabled" -> 0
                m.status == "off" -> 300
                !m.queuedOrRunning -> 30
                m.paused -> 60
                m.held || m.brief?.delayed == true -> 30
                else -> 3
            }
            if (seconds == 0L) return
            delay(seconds * 1000)
        }
    }

    fun refresh() {
        if (m.loadFailed) {
            scope.launch { load() }
            return
        }
        m.refreshing = true
        scope.launch {
            try {
                generate(retry = true)
            } finally {
                m.refreshing = false
            }
        }
    }

    fun switchLanguage(next: String) {
        if (next == m.language) return
        m.switching = true
        m.kept = null
        rating = null
        ratingSent = null
        m.language = next
    }

    fun rate(value: String) {
        val revision = m.brief?.revision ?: return
        rating = value
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>("sourceRevision" to revision, "rating" to value)
                if (value == "down") {
                    body["reasons"] = downReasons.sorted()
                    body["comment"] = downComment.value.trim().nonEmpty?.take(500)
                }
                API.post<EmptyResponse>("$path/summary-rating", body = body, query = mapOf("language" to m.language))
                ratingFailed = false
                ratingSent = L("Thanks. Your rating was saved.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ratingFailed = true
                ratingSent = L("We couldn’t save your rating. Try again.")
            }
        }
    }

    LaunchedEffect(m.language) { poll() }
    LaunchedEffect(Unit) { if (workspace.can(Permission.olfatoChat)) olfato.load() }

    val numbered = m.numbered
    val cite = BriefCitations(numbered) { openSource = it }
    val shown = m.shown

    Card(padding = 16.dp, spacing = 16.dp, modifier = modifier) {
        BriefHeader(
            m = m, chart = chart, asksOlfato = asksOlfato,
            onAsk = { nav.push(ProviderRoute.olfatoPetChat(petId = chart.pet.id)) },
            onLanguage = { switchLanguage(it) },
            onRefresh = { refresh() },
        )
        if (m.branded) {
            BriefWarnings(m, cite)
            BriefConflicts(m, cite)
        }
        BriefFacts(chart)
        if (m.organized || m.writing) BriefStatusLine(m)
        val message = briefStatusMessage(m)
        if (message != null) {
            Text(
                message,
                style = Theme.text(14),
                color = if (m.failed || m.loadFailed || m.refreshFailed) Theme.destructive else Theme.inkSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.blue, 6.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        val diagnostics = m.brief?.diagnostics
        if ((m.failed || m.nothing) && diagnostics != null) BriefTechnical(diagnostics, techOpen) { techOpen = !techOpen }
        BriefOffLines(m) { nav.push(ProviderRoute.section(ProviderSection.settings)) }
        if (m.writing && shown == null && !m.delayed && m.branded) {
            // Nothing written to show yet: the shape of what is coming.
            val bar = Theme.borderSoft.copy(alpha = 0.72f)
            Column(
                Modifier.fillMaxWidth().clientsChartBox().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(width = 128.dp, height = 16.dp).background(bar, CircleShape))
                repeat(3) { i ->
                    Box(Modifier.then(if (i == 2) Modifier.widthIn(max = 220.dp) else Modifier).fillMaxWidth().height(14.dp).background(bar, CircleShape))
                }
            }
        }
        if (shown != null) {
            Box(Modifier.alpha(if (m.dimmed) 0.55f else 1f)) { BriefBody(m, shown, cite) }
        } else if (!(m.brief?.pending ?: emptyList()).isEmpty()) {
            // The chart's own due dates are facts: shown whatever the summary's state, also when it is off.
            PendingBlock(m, null, cite)
        }
        if (m.organized) {
            Box(Modifier.alpha(if (m.dimmed) 0.55f else 1f)) {
                BriefTabs(m, chart, canWeigh, reload, shown, tab, tabChosen, { tabChosen = true }, cite)
            }
        } else {
            // No generated text to organize: the chart's own facts, all of them, with no tabs.
            LooseFacts(m, cite)
            ClientsChartSummaryView(chart = chart, canWeigh = canWeigh, reload = reload)
        }
        SourcesRow(numbered) { openSource = it }
        val read = m.brief?.read
        if ((m.generated || m.textless) && read != null) {
            BriefWhatWasRead(m, read, readOpen) { readOpen = it }
        }
        if (shown != null && m.current && !m.dimmed) {
            BriefRating(
                chosen = rating ?: m.brief?.rating?.rating,
                ratingSent = ratingSent, ratingFailed = ratingFailed,
                downReasons = downReasons, downComment = downComment,
                onToggleReason = { reason -> downReasons = if (downReasons.contains(reason)) downReasons - reason else downReasons + reason },
                onRate = { rate(it) },
            )
        }
        if (m.organized) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                OlfatoFace(12.dp, Modifier.padding(top = 2.dp))
                Text(
                    if (shown != null) L("Written by AI from this pet’s records. Check the record before acting.") else L("A summary is saved and reused until the chart changes."),
                    style = Theme.text(12), color = Theme.inkSoft,
                )
            }
        }
    }

    openSource?.let { target ->
        AppSheet(onDismissRequest = { openSource = null }) {
            ClientsBriefSourceSheet(sources = target.sources, index = target.index, petId = chart.pet.id, onFollowUps = onFollowUps) { next ->
                scope.launch {
                    delay(350)
                    nav.push(next)
                }
            }
        }
    }
}

/** The OlfatoFace mark (iOS `Image("OlfatoFace")`), hidden from VoiceOver/TalkBack. */
@Composable
private fun OlfatoFace(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = rememberAsyncImagePainter("file:///android_asset/img/OlfatoFace.svg"),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size).clearAndSetSemantics {},
    )
}

// MARK: Header

@Composable
private fun BriefHeader(
    m: ClientsBriefModel,
    chart: ClientsChart,
    asksOlfato: Boolean,
    onAsk: () -> Unit,
    onLanguage: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.branded) {
                    // Olfato AI is on for this workspace: the block carries its name.
                    OlfatoFace(32.dp)
                    Text("Olfato AI", style = Theme.heading(20), color = Theme.inkStrong)
                } else {
                    // Off or not available: the pet's own facts, with no AI in them and no AI name on them.
                    IconBadge(icon = "book-open", accent = Theme.blue, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.accent, washPercent = 12.0)
                    Text(L("Patient overview"), style = Theme.heading(20), color = Theme.inkStrong)
                }
            }
            Text(
                if (m.branded) L("AI brief of each pet’s record.") else L("A connected view of the pet’s history."),
                style = Theme.text(14), color = Theme.inkSoft,
            )
        }
        val controls = !(m.off || m.empty) && m.brief != null
        if (asksOlfato || controls) {
            FlowLayout(spacing = 8.dp) {
                if (asksOlfato) {
                    PMButtonStyle(variant = ButtonVariant.outline, size = ButtonSize.compact, onClick = onAsk) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OlfatoFace(20.dp)
                            Text(L("Ask Olfato"))
                        }
                    }
                }
                if (controls) {
                    AppMenu(
                        label = { FilterMenuLabel(title = if (m.language == "es") L("Spanish") else L("English")) },
                        accessibilityLabel = L("Summary language"),
                    ) {
                        picker(
                            L("Summary language"),
                            options = listOf("en" to L("English"), "es" to L("Spanish")),
                            selection = stateOf({ m.language }, { onLanguage(it) }),
                        )
                    }
                    PMButton(
                        if (m.failed || m.loadFailed) L("Retry") else L("Refresh"),
                        systemImage = "refresh-cw", variant = ButtonVariant.outline, size = ButtonSize.header, isWorking = m.writing,
                        enabled = !(m.writing || m.paused || m.retryLater),
                    ) { onRefresh() }
                }
            }
        }
        val at = m.brief?.retryAt
        if (m.retryLater && at != null) {
            Text(LF("You can try again at %@.", DateText.time(at)), style = Theme.text(12), color = Theme.inkSoft)
        }
    }
}

// MARK: Warnings, conflicts, facts

/** A citation's number on its tint (web `SourceChip`'s number): 18 tall, 10.4 extrabold. */
@Composable
private fun SourceNumber(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 18.dp)
            .widthIn(min = 18.dp)
            .background(Theme.wash(Theme.blue, 16.0), CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", style = brandText(10.4, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkStrong)
    }
}

/** The numbered sources and how a number opens its source. */
private class BriefCitations(val list: List<ClientsBriefSource>, val open: (ClientsBriefSourceTarget) -> Unit)

@Composable
private fun Citations(ids: List<String>, cite: BriefCitations, modifier: Modifier = Modifier) {
    val list = cite.list
    val shownIds = ids.filter { id -> list.any { it.id == id } }
    if (shownIds.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
        shownIds.forEach { id ->
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) {
                Box(
                    Modifier
                        .heightIn(min = 24.dp)
                        .widthIn(min = 24.dp)
                        .plainButton(label = LF("Source %1\$d: %2\$@", index + 1, ClientsBriefWords.sourceTitle(list[index]))) {
                            cite.open(ClientsBriefSourceTarget(sources = list, index = index))
                        },
                    contentAlignment = Alignment.Center,
                ) { SourceNumber(index + 1) }
            }
        }
    }
}

/** A text and the citations after it, on one baseline (Swift `HStack(alignment: .firstTextBaseline, spacing: 4)`). */
@Composable
private fun CitedText(text: AnnotatedString, ids: List<String>, cite: BriefCitations, style: TextStyle = Theme.text(14), color: Color = Theme.inkStrong) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = style, color = color, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
        Citations(ids, cite, Modifier.alignByBaseline())
    }
}

private fun plain(text: String): AnnotatedString = AnnotatedString(text)

/** What the overview on screen does not say and nobody should miss (web `WarningStrip`). */
@Composable
private fun BriefWarnings(m: ClientsBriefModel, cite: BriefCitations) {
    val list = (m.brief?.warnings ?: emptyList()).filter { it.covered != true }
    if (list.isEmpty()) return
    val label = L("Warnings")
    Column(
        Modifier
            .fillMaxWidth()
            .clientsChartBox(Theme.wash(Theme.destructive, 8.0), border = Theme.destructive.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowLayout(spacing = 8.dp) {
            Text(L("Warnings"), style = Theme.heading(16), color = Theme.inkStrong)
            Text(L("From the pet’s records, not written by AI"), style = Theme.text(12), color = Theme.inkSoft)
        }
        list.forEach { w ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Glyph(
                    when (w.kind) {
                        "allergy" -> "triangle-alert"
                        "problem" -> "stethoscope"
                        else -> "shield-alert"
                    },
                    16.dp, tint = Theme.destructive, modifier = Modifier.padding(top = 2.dp),
                )
                val prefix = ClientsBriefWords.warningPrefix(w)
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(prefix?.let { "$it: " } ?: "") }
                    withStyle(SpanStyle(fontWeight = if (prefix == null) FontWeight.Bold else FontWeight.Normal)) { append(ClientsBriefWords.warningText(w)) }
                }
                CitedText(text, listOfNotNull(w.sourceId), cite)
            }
        }
    }
}

/** Two records that disagree, said as they are (web `ConflictNotice`). */
@Composable
private fun BriefConflicts(m: ClientsBriefModel, cite: BriefCitations) {
    val list = m.brief?.conflicts ?: emptyList()
    if (list.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .clientsChartBox(Theme.wash(Theme.mango, 12.0), border = Theme.mango.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("file-search", 16.dp, tint = Theme.inkStrong)
            Text(L("Check the record"), style = Theme.heading(16), color = Theme.inkStrong)
        }
        Text(L("These records don’t agree. Nothing was changed: check which one is right."), style = Theme.text(12), color = Theme.inkSoft)
        list.forEach { c ->
            BaselineDotRow(Theme.mango, spacing = 8.dp) {
                CitedText(plain(ClientsBriefWords.conflictText(c)), c.sourceIds ?: emptyList(), cite)
            }
        }
    }
}

/**
 * A 6 pt dot and a text whose first baseline the dot's bottom sits 1 pt under
 * (Swift `Circle().alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 }`).
 */
@Composable
private fun BaselineDotRow(color: Color, spacing: Dp, content: @Composable () -> Unit) {
    val onePoint = with(LocalDensity.current) { 1.dp.roundToPx() }
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        Box(Modifier.size(6.dp).background(color, CircleShape).alignBy { it.measuredHeight - onePoint })
        Box(Modifier.weight(1f).alignBy(FirstBaseline)) { content() }
    }
}

/** The facts everyone looks for first, in one slim row that wraps (web `FactStrip`). */
@Composable
private fun BriefFacts(chart: ClientsChart) {
    val weights = chart.weights
    val trend = (weights?.trend ?: emptyList()).sortedBy { it.at }
    val change: String? = if (trend.size < 2) null else {
        val last = trend[trend.size - 1]
        val before = trend[trend.size - 2]
        val delta = (last.grams - before.grams) / 1000
        if (delta == 0.0) LF("no change since %@", ClientsText.shortDay(before.at))
        else LF("%1\$@ since %2\$@", ClientsBriefWords.change(delta), ClientsText.shortDay(before.at))
    }
    val medications = chart.summary?.medications?.size ?: 0
    val lab = chart.summary?.labs?.firstOrNull()
    val label = L("Live information from the chart")
    FlowLayout(spacing = 6.dp, modifier = Modifier.semantics { contentDescription = label }) {
        FactChip(
            "scale", label = L("Latest weight"), value = ClientsText.weight(weights?.latestGrams),
            detail = listOfNotNull(weights?.latestAt?.let { ClientsText.shortDay(it) }, if (weights?.latestGrams == null) null else change).joinToString(" · "),
            missing = L("No weight"),
        )
        FactChip(
            "pill", label = L("Medications"), value = if (medications == 0) null else LP(medications, "%d medication", "%d medications"),
            detail = null, missing = L("No medications"),
        )
        FactChip("flask-conical", label = L("Latest lab"), value = lab?.name.nonEmpty, detail = lab?.at?.let { ClientsText.shortDay(it) }, missing = L("No lab results"))
    }
}

/** One of the chart's own facts as a small pill (web `FactChip`); nothing recorded is said quietly, dashed. */
@Composable
private fun FactChip(icon: String, label: String, value: String?, detail: String?, missing: String) {
    val ink = Theme.inkStrong
    val soft = Theme.inkSoft
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .then(
                if (value == null) Modifier.dashedBorder(1.dp, Theme.borderSoft, cornerRadius = null, on = 4.dp, off = 3.dp)
                else Modifier.background(Theme.wash(Theme.blue, 9.0), CircleShape)
            )
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: ${value ?: missing}" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 14.dp, tint = if (value == null) Theme.inkSoft else Theme.blue)
        if (value != null) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ink)) { append(value) }
                    withStyle(SpanStyle(color = soft)) { append(detail.nonEmpty?.let { " · $it" } ?: "") }
                },
                style = Theme.text(12), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(missing, style = Theme.text(12), color = Theme.inkSoft)
        }
    }
}

// MARK: Status

@Composable
private fun BriefStatusLine(m: ClientsBriefModel) {
    val brief = m.brief
    val partial = m.current && (brief?.partial == true || ClientsBriefWords.exclusions(brief?.read).isNotEmpty())
    val text: String = when {
        m.writing -> if (brief?.generatedAt == null && m.shown == null) L("Writing the brief…") else L("Updating…")
        m.current -> {
            val at = brief?.generatedAt
            if (at != null) {
                if (partial) LF("Partial summary · updated %@", ClientsBriefWords.stamp(at)) else LF("Updated %@", ClientsBriefWords.stamp(at))
            } else if (partial) L("Partial summary") else L("Up to date")
        }
        m.paused || m.held -> L("Waiting")
        else -> if (brief?.generatedAt != null) L("Outdated") else L("Not yet generated")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowLayout(spacing = 8.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.writing) {
                    CircularProgressIndicator(color = Theme.blue, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                } else {
                    Glyph(if (m.current) "check" else "clock-3", 14.dp, tint = if (m.current) Theme.blue else Theme.inkSoft)
                }
                Text(text, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
            }
            val generated = DateText.parse(brief?.generatedAt)
            val covered = DateText.parse(brief?.coveredThrough)
            if (!m.writing && generated != null && covered != null && abs(generated.epochSecond - covered.epochSecond) > 60) {
                Text(
                    LF("Includes information through %@", ClientsBriefWords.stamp(brief?.coveredThrough)),
                    style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.alpha(0.8f),
                )
            }
        }
        if (m.writing) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Theme.wash(Theme.blue, 16.0), CircleShape)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Theme.blue.copy(alpha = 0.7f), CircleShape))
            }
        }
    }
}

private fun briefStatusMessage(m: ClientsBriefModel): String? {
    if (m.off) return if (m.status == "disabled") L("Olfato AI isn’t available yet. The pet’s records are below.") else null
    if (m.loadFailed) return L("We could not load the summary. Try again.")
    val brief = m.brief ?: return null
    if (m.empty) return L("Nothing to summarize yet.")
    if (m.paused) return L("Today’s limit of Olfato AI summaries has been reached. This one will be written automatically once there is room again.")
    if (m.failed) return ClientsBriefWords.failure(brief.errorCode, time = if (m.retryLater) DateText.time(brief.retryAt) else null)
    if (m.nothing) {
        return if (brief.nothingReason == "unverified_documents") L("There is an attached document that hasn’t been verified; open it in Files.")
        else L("Olfato AI found nothing in this chart it could say reliably, so no summary is shown. The pet’s records are below.")
    }
    if (m.refreshFailed) return L("We could not update the summary. Try again.")
    if (m.held) return L("This chart is being updated. The summary will be rewritten a few minutes after the last change. Press Refresh to rewrite it now.")
    if (m.delayed) return L("Taking longer than usual. The pet’s records are below.")
    if ((brief.coverage?.pending ?: 0) > 0 && m.queuedOrRunning) return L("Reading the attached documents before writing the summary…")
    val code = brief.errorCode
    if (m.status == "queued" && code != null && code != "documents_pending") return L("That didn’t work the first time. Trying again shortly…")
    if (m.current) return null
    if (brief.generatedAt != null) return if (m.rewriting) null else L("The chart changed. A new summary is being prepared.")
    return if (m.shown == null) L("Preparing the first summary…") else null
}

@Composable
private fun BriefOffLines(m: ClientsBriefModel, openSettings: () -> Unit) {
    if (m.status != "off") return
    val outdated = m.settings?.noticeOutdated == true
    val canEnable = m.brief?.canEnable == true
    if (outdated && canEnable) {
        // On, but waiting for the new notice: who can confirm it is taken to it.
        OffNote(L("Olfato AI needs the new notice to be accepted."), link = L("Read the notice"), openSettings)
    } else if (outdated) {
        // Everyone else: one quiet line, nothing to do.
        Text(L("Olfato AI is paused until the business accepts its new notice."), style = Theme.text(14), color = Theme.inkSoft)
    } else if (canEnable) {
        // Only someone who can change the setting is told about it.
        OffNote(L("Olfato AI can write an AI brief of each pet’s record. It is off for this business."), link = L("Turn on in Settings"), openSettings)
    }
}

@Composable
private fun OffNote(text: String, link: String, openSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.blue, 6.0), RoundedCornerShape(Theme.boxRadius))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OlfatoFace(16.dp, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = Theme.text(14), color = Theme.inkSoft)
            Text(
                link, style = Theme.text(14, FontWeight.Bold).copy(textDecoration = TextDecoration.Underline), color = Theme.blue,
                modifier = Modifier.plainButton { openSettings() },
            )
        }
    }
}

/** A failed summary's codes and counts, for support (web `TechnicalDetails`). */
@Composable
private fun BriefTechnical(d: ClientsBriefDiagnostics, techOpen: Boolean, toggle: () -> Unit) {
    val text = listOfNotNull(
        "Olfato AI · ${d.errorCode ?: "—"}",
        d.runId?.let { "run: $it" },
        "version: ${d.version ?: "—"} · model: ${d.model ?: "—"}",
        if (d.inputTokens != null || d.outputTokens != null) "tokens: in ${d.inputTokens ?: 0} · out ${d.outputTokens ?: 0}" else null,
        d.attempts?.let { "attempts: $it" },
    ).joinToString("\n")
    val chevron by animateFloatAsState(if (techOpen) 180f else 0f, tween(200), label = "chevron")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.plainButton { toggle() },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("chevron-down", 14.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(chevron))
            Text(L("Technical details"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
        }
        AnimatedVisibility(
            visible = techOpen,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("For support. It has codes and counts only, nothing from the pet’s records."), style = Theme.text(12), color = Theme.inkSoft)
                SelectionContainer {
                    Text(
                        text,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = Theme.inkStrong,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.chipRadius))
                            .padding(10.dp),
                    )
                }
                PMButton(L("Copy"), variant = ButtonVariant.outline, size = ButtonSize.header) { clientsCopy(text, done = L("Copied")) }
            }
        }
    }
}

// MARK: The brief

@Composable
private fun BriefBody(m: ClientsBriefModel, text: ClientsBriefText, cite: BriefCitations) {
    val overview = text.items("overview")
    val recent = text.items("recent")
    val source = m.source
    val hasPending = !(source?.pending ?: emptyList()).isEmpty() || text.items("pending").isNotEmpty()
    if (overview.isEmpty() && recent.isEmpty() && !hasPending) return
    val blocks = mutableListOf<@Composable () -> Unit>()
    if (overview.isNotEmpty()) blocks.add { Stacked("clipboard-list", L("Overview")) { Bullets(overview, cite) } }
    if (recent.isNotEmpty() || hasPending) blocks.add {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recent.isNotEmpty()) {
                val visit = source?.recent?.basis == "visit"
                Stacked(
                    "history", if (visit) L("Since the last visit") else L("Recent changes"),
                    aside = source?.recent?.since?.let { since ->
                        if (visit) LF("Since %@", ClientsText.shortDay(since)) else LF("Last 90 days · since %@", ClientsText.shortDay(since))
                    },
                ) { Bullets(recent, cite) }
            }
            // The chart's own due dates are facts: shown whatever the summary's state.
            if (hasPending) PendingBlock(m, text, cite)
        }
    }
    ClientsGrid(items = blocks, minimum = 320.dp, spacing = 12.dp) { _, block -> block() }
}

/** A block that is always in view (web `Stacked`): a 24 disc, the title in Fredoka 16, an aside, its content. */
@Composable
private fun Stacked(icon: String, title: String, aside: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clientsChartBox().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowLayout(spacing = 8.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(Theme.wash(Theme.blue, 12.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(icon, 14.dp, tint = Theme.blue)
                }
                Text(title, style = Theme.heading(16), color = Theme.inkStrong)
            }
            if (aside != null) Chip(text = aside, tone = Chip.Tone.muted)
        }
        content()
    }
}

/** What is due: the chart's own dates first, then what the summary found recommended in the notes (web `PendingBlock`). */
@Composable
private fun PendingBlock(m: ClientsBriefModel, text: ClientsBriefText?, cite: BriefCitations) {
    val source = m.source
    val facts = source?.pending ?: emptyList()
    val noted = text?.items("pending") ?: emptyList()
    val ink = Theme.inkStrong
    val soft = Theme.inkSoft
    Stacked("calendar-clock", L("Pending@brief")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (facts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts.forEach { item ->
                        val due = ClientsBriefWords.pendingDue(item.dueDay, today = source?.today)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ink, fontSize = 14.sp)) { append(item.title ?: "—") }
                                    withStyle(SpanStyle(color = soft, fontSize = 12.sp)) {
                                        append(
                                            "  " + listOfNotNull(
                                                ClientsBriefWords.pendingKind(item.kind),
                                                if (item.source == "family") L("From the family") else null,
                                                item.dueDay?.let { ClientsText.shortDay(it) },
                                            ).joinToString(" · "),
                                        )
                                    }
                                },
                                style = Theme.text(14),
                                modifier = Modifier.weight(1f),
                            )
                            when {
                                due.late -> StatusChip(text = due.text, state = ProviderState.problem)
                                due.today -> StatusChip(text = due.text, state = ProviderState.waiting)
                                else -> Chip(text = due.text, tone = Chip.Tone.muted)
                            }
                        }
                    }
                    val more = source?.pendingMore
                    if (more != null && more > 0) Text(LF("and %d more@brief", more), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
            if (noted.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (facts.isNotEmpty()) RowDivider(modifier = Modifier.padding(bottom = 4.dp))
                    Text(L("Noted in the records").uppercase(), style = Theme.text(12, FontWeight.Bold).tracking(0.7), color = Theme.inkSoft)
                    Bullets(noted, cite)
                }
            }
        }
    }
}

/** A section's bullets, each ending in the numbered chips of the records it cites (web `Bullets`). */
@Composable
private fun Bullets(items: List<ClientsBriefItem>, cite: BriefCitations) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            BaselineDotRow(Theme.blue, spacing = 10.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        ClientsBriefWords.datesInText(item.text),
                        style = Theme.text(14).copy(lineHeight = 22.sp), color = Theme.inkStrong,
                    )
                    if (item.unverified == true || !(item.sourceIds ?: emptyList()).isEmpty()) {
                        FlowLayout(spacing = 4.dp) {
                            if (item.unverified == true) {
                                // Set by the server, never by the AI: the line rests on a document nobody has verified.
                                Box(Modifier.clientsHint(L("From an attached document nobody has verified. Check the original."))) {
                                    Chip(text = L("Unverified document"), tone = Chip.Tone.warn, icon = "file-exclamation-point")
                                }
                            }
                            Citations(item.sourceIds ?: emptyList(), cite)
                        }
                    }
                }
            }
        }
    }
}

// MARK: Tabs

/** How many of the chart's own facts a tab has to show (web `liveCount`). */
private fun liveCount(chart: ClientsChart, key: String): Int = when (key) {
    "problems" -> chart.summary?.problems?.size ?: 0
    "medications" -> chart.summary?.medications?.size ?: 0
    "labs" -> chart.summary?.labs?.size ?: 0
    "weight" -> if (chart.weights?.latestGrams == null) 0 else 1
    "milestones" -> chart.summary?.vaccines?.size ?: 0
    else -> 0
}

/** The chart's own facts shown under a tab (the vaccine list sits with the milestones). */
private fun liveSection(key: String): String? = when (key) {
    "problems" -> "problems"
    "medications" -> "medications"
    "labs" -> "labs"
    "weight" -> "weight"
    "milestones" -> "history"
    else -> null
}

private fun hasContent(m: ClientsBriefModel, chart: ClientsChart, key: String, text: ClientsBriefText?): Boolean =
    !(text?.items(key) ?: emptyList()).isEmpty() || liveCount(chart, key) > 0 ||
        (key == "weight" && m.source?.weights?.latest != null) ||
        (key == "medications" && !(m.uncoveredMeds.isEmpty() && m.uncoveredTreatment.isEmpty())) ||
        (key == "care" && m.uncoveredCare.isNotEmpty())

@Composable
private fun BriefTabs(
    m: ClientsBriefModel,
    chart: ClientsChart,
    canWeigh: Boolean,
    reload: suspend () -> Unit,
    text: ClientsBriefText?,
    tab: MutableState<String>,
    tabChosen: Boolean,
    choose: () -> Unit,
    cite: BriefCitations,
) {
    // Care & handling is a tab only when a groomer or the care card has something to say.
    val keys = ClientsBriefWords.tabs.filter { it != "care" || !(text?.items("care") ?: emptyList()).isEmpty() || m.uncoveredCare.isNotEmpty() }
    val active = if (tabChosen && keys.contains(tab.value)) tab.value else (keys.firstOrNull { hasContent(m, chart, it, text) } ?: "problems")
    val items = text?.items(active) ?: emptyList()
    val live = liveSection(active)
    val recorded = active == "weight" && m.source?.weights?.latest != null
    val medicines = active == "medications" && !(m.uncoveredMeds.isEmpty() && m.uncoveredTreatment.isEmpty())
    val care = active == "care" && m.uncoveredCare.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ClientsLineTabs(
            options = keys.map { Triple(it, ClientsBriefWords.tab(it), !hasContent(m, chart, it, text)) },
            selection = stateOf({ active }, { tab.value = it; choose() }),
            label = L("More from the summary"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (recorded) WeightFacts(m, cite)
            if (items.isNotEmpty()) Bullets(items, cite)
            if (active == "medications") MedicationFacts(m, cite)
            if (active == "care") CareFacts(m, cite)
            // The chart's own card says by itself when it is empty; with nothing at all, one quiet line.
            if (live != null && liveCount(chart, active) > 0) {
                ClientsChartSummaryView(chart = chart, canWeigh = canWeigh, section = live, reload = reload)
            } else if (items.isEmpty() && !recorded && !medicines && !care) {
                Text(L("Nothing on record here."), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }
}

/** The latest two recorded weights and how they differ: from the weight logs, not from the AI (web `WeightFacts`). */
@Composable
private fun WeightFacts(m: ClientsBriefModel, cite: BriefCitations) {
    val weights = m.source?.weights ?: return
    val latest = weights.latest ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CitedText(
            plain(latest.day?.let { LF("Latest: %1\$@ on %2\$@", ClientsBriefWords.kg(latest.kg), ClientsText.shortDay(it)) } ?: LF("Latest: %@", ClientsBriefWords.kg(latest.kg))),
            listOfNotNull(latest.sourceId), cite, style = Theme.text(14, FontWeight.Bold),
        )
        val previous = weights.previous
        if (previous != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    previous.day?.let { LF("Before: %1\$@ on %2\$@", ClientsBriefWords.kg(previous.kg), ClientsText.shortDay(it)) } ?: LF("Before: %@", ClientsBriefWords.kg(previous.kg)),
                    style = Theme.text(14), color = Theme.inkStrong, modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                Citations(listOfNotNull(previous.sourceId), cite, Modifier.alignByBaseline())
                val change = weights.changeKg
                if (change != null) {
                    Text(
                        "· " + (if (change == 0.0) L("No change") else LF("Change: %@", ClientsBriefWords.change(change))),
                        style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (weights.outlier != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.mango, 16.0), RoundedCornerShape(Theme.chipRadius))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Glyph("triangle-alert", 16.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            LF("%1\$@ → %2\$@: check this entry", ClientsBriefWords.kg(previous.kg), ClientsBriefWords.kg(latest.kg)),
                            style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                        )
                        Text(L("The newest weight is far from the one before. It is shown as recorded; nothing was corrected."), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicationFacts(m: ClientsBriefModel, cite: BriefCitations) {
    if (m.uncoveredMeds.isNotEmpty()) MedicationList(L("Medications on record"), m.uncoveredMeds, cite)
    if (m.uncoveredTreatment.isNotEmpty()) MedicationList(L("Recent treatment on record"), m.uncoveredTreatment, cite)
}

@Composable
private fun FactHeading(title: String) {
    FlowLayout(spacing = 8.dp) {
        Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        Text(L("From the pet’s records, not written by AI"), style = Theme.text(12), color = Theme.inkSoft)
    }
}

@Composable
private fun MedicationList(title: String, list: List<ClientsBriefMedication>, cite: BriefCitations) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FactHeading(title)
        list.forEach { med ->
            val line = ClientsBriefWords.medicationLine(med)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CitedText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.title) }
                        append(if (line.detail.isEmpty()) "" else " · ${line.detail}")
                    },
                    listOfNotNull(med.sourceId), cite,
                )
                Text(
                    ClientsBriefWords.medicationSource(med) + (med.said.nonEmpty?.let { " · “$it”" } ?: ""),
                    style = Theme.text(12), color = Theme.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun CareFacts(m: ClientsBriefModel, cite: BriefCitations) {
    val care = m.uncoveredCare
    if (care.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FactHeading(L("From the care card"))
        care.forEach { fact ->
            val value = if (fact.key == "rebook_every_weeks") {
                if ((fact.weeks ?: 0) == 1) L("every week") else LF("every %d weeks", fact.weeks ?: 0)
            } else fact.value ?: ""
            CitedText(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(ClientsBriefWords.careLabel(fact.key)) }
                    append(": $value")
                },
                listOfNotNull(fact.sourceId), cite,
            )
        }
    }
}

/** Without the brief's tabs: the facts straight from the records that still matter. */
@Composable
private fun LooseFacts(m: ClientsBriefModel, cite: BriefCitations) {
    if (m.source?.weights?.outlier != null) WeightFacts(m, cite)
    MedicationFacts(m, cite)
}

// MARK: Sources

/** Every source the brief cites, once, in the order of their numbers (web `SourcesRow`). */
@Composable
private fun SourcesRow(list: List<ClientsBriefSource>, open: (ClientsBriefSourceTarget) -> Unit) {
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowDivider(modifier = Modifier.padding(bottom = 4.dp))
        Text(L("Sources").uppercase(), style = Theme.text(12, FontWeight.Bold).tracking(0.7), color = Theme.inkSoft)
        FlowLayout(spacing = 6.dp) {
            list.forEachIndexed { index, item ->
                Row(
                    Modifier
                        .heightIn(min = 32.dp)
                        .background(Theme.surface, CircleShape)
                        .border(1.dp, Theme.borderSoft, CircleShape)
                        .plainButton(label = LF("Source %1\$d: %2\$@", index + 1, ClientsBriefWords.sourceTitle(item))) {
                            open(ClientsBriefSourceTarget(sources = list, index = index))
                        }
                        .padding(start = 6.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceNumber(index + 1)
                    Text(
                        listOfNotNull(
                            ClientsBriefWords.sourceTitle(item),
                            item.page?.let { LF("Page %d", it) },
                            (item.date ?: item.at)?.let { ClientsText.shortDay(it) },
                        ).joinToString(" · "),
                        style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// MARK: What was read, rating

/** What the summary was written from, in one line, and what was left out when opened (web `WhatWasRead`). */
@Composable
private fun BriefWhatWasRead(m: ClientsBriefModel, read: ClientsBriefRead, readOpen: Boolean?, setOpen: (Boolean) -> Unit) {
    val gaps = ClientsBriefWords.exclusions(read).isNotEmpty()
    val exclusions = (if (m.current && m.brief?.partial == true) listOf(L("Some lines were left out because we couldn’t check them against the record.")) else emptyList()) +
        ClientsBriefWords.exclusions(read)
    val isOpen = readOpen ?: gaps
    val chevron by animateFloatAsState(if (isOpen) 180f else 0f, tween(200), label = "chevron")
    val ink = Theme.inkStrong
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowDivider(modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().plainButton { setOpen(!isOpen) },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Glyph("chevron-down", 14.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp).rotate(chevron))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("${L("What was read")}: ") }
                    append(ClientsBriefWords.readLine(read))
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink)) { append(if (gaps) " · ${L("Some things were left out")}" else "") }
                },
                style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            val label = L("What was left out")
            Column(Modifier.padding(start = 20.dp).semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (exclusions.isEmpty()) {
                    Text(L("Nothing was left out."), style = Theme.text(12), color = Theme.inkSoft)
                } else {
                    exclusions.forEach { Text("• $it", style = Theme.text(12), color = Theme.inkSoft) }
                }
                val documents = m.brief?.documents ?: emptyList()
                if (documents.isNotEmpty()) {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        documents.forEach {
                            Text("${it.title ?: "—"} · ${ClientsBriefWords.documentStatus(it.status)}", style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                }
            }
        }
    }
}

/** Thumbs up or down on the summary on screen; a thumbs down can say why (web `SummaryRatingRow`). */
@Composable
private fun BriefRating(
    chosen: String?,
    ratingSent: String?,
    ratingFailed: Boolean,
    downReasons: Set<String>,
    downComment: MutableState<String>,
    onToggleReason: (String) -> Unit,
    onRate: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RowDivider()
        FlowLayout(spacing = 8.dp) {
            Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
                Text(L("Was this summary useful?"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            }
            IconCircleButton(systemImage = "thumbs-up", label = L("Yes, it was useful"), tint = if (chosen == "up") Theme.blue else Theme.inkSoft) { onRate("up") }
            IconCircleButton(systemImage = "thumbs-down", label = L("No, it wasn’t"), tint = if (chosen == "down") Theme.blue else Theme.inkSoft) { onRate("down") }
            if (ratingSent != null) {
                Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
                    Text(ratingSent, style = Theme.text(12), color = if (ratingFailed) Theme.destructive else Theme.inkSoft)
                }
            }
        }
        if (chosen == "down") {
            FlowLayout(spacing = 6.dp) {
                listOf(
                    "missing" to L("Something important is missing"),
                    "wrong" to L("Something is wrong"),
                    "too_long" to L("Too long"),
                    "not_relevant" to L("Not relevant"),
                ).forEach { (key, title) ->
                    FilterChip(title = title, isSelected = downReasons.contains(key)) { onToggleReason(key) }
                }
            }
            PMTextField(
                L("What was missing or wrong?"), text = downComment, prompt = L("What was missing or wrong?"),
                hint = L("Optional. It stays in PawMiaw and is never sent to the AI."), optional = true, multiline = true,
            )
            PMButton(L("Send"), variant = ButtonVariant.outline, size = ButtonSize.compact) { onRate("down") }
        }
    }
}

/**
 * The brief's sections as line tabs (web `TabsList variant="line"`): 14 medium words, the chosen one
 * in ink with a 2 pt rule under it; a tab with nothing on record is dimmed, still a tab like the others.
 * (No kit piece draws line tabs; built from Theme tokens.) `options`: Swift `(value:, title:, dimmed:)`.
 */
@Composable
fun ClientsLineTabs(
    options: List<Triple<String, String, Boolean>>,
    selection: MutableState<String>,
    label: String = "",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, title, dimmed) ->
            val chosen = value == selection.value
            val rule by animateFloatAsState(if (chosen) 1f else 0f, tween(150), label = "rule")
            val ink = Theme.inkStrong
            val hint = if (dimmed) L("Nothing on record") else null
            Box(
                Modifier
                    .alpha(if (dimmed) 0.55f else 1f)
                    .plainButton { selection.value = value }
                    .semantics { selected = chosen }
                    .clientsHint(hint)
                    .height(32.dp)
                    // The 2 pt rule under the chosen tab, 4 pt below its frame (Swift `.offset(y: 4)`).
                    .drawBehind {
                        val line = 2.dp.toPx()
                        drawRect(ink.copy(alpha = rule), topLeft = Offset(0f, size.height - line + 4.dp.toPx()), size = Size(size.width, line))
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, style = Theme.text(14, FontWeight.Medium), color = if (chosen) ink else ink.copy(alpha = 0.6f))
            }
        }
    }
}

// MARK: - A source, opened

class ClientsBriefSourceTarget(
    val sources: List<ClientsBriefSource>,
    val index: Int,
) {
    val id: UUID = UUID.randomUUID()
}

@Composable
private fun ClientsBriefSourceSheet(
    sources: List<ClientsBriefSource>,
    index: Int,
    petId: String,
    onFollowUps: () -> Unit,
    /** Opens a visit, a record or a hospitalization from the page under the sheet. */
    onOpen: (ProviderRoute) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var position by remember { mutableStateOf(index) }
    var opening by remember { mutableStateOf(false) }
    val source = sources[minOf(position, sources.size - 1)]
    val title = ClientsBriefWords.sourceTitle(source)
    val kind = ClientsBriefWords.sourceKind(source.kind)
    val fields = ClientsBriefFields.rows(source.data, title = source.title)

    fun openOriginal(attachmentId: String) {
        opening = true
        scope.launch {
            try {
                val data = API.data("GET", "/provider-client-pets/$petId/chart-attachments/$attachmentId", query = emptyMap(), body = null)
                fun starts(vararg bytes: Int): Boolean = data.size >= bytes.size && bytes.indices.all { data[it] == bytes[it].toByte() }
                val ext = when {
                    starts(0x25, 0x50, 0x44, 0x46) -> "pdf"
                    starts(0x89, 0x50, 0x4E, 0x47) -> "png"
                    starts(0xFF, 0xD8) -> "jpg"
                    else -> "bin"
                }
                val folder = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(folder, "chart-$attachmentId.$ext")
                file.writeBytes(data)
                // iOS `.quickLookPreview($original)`.
                quickLookPreview(context, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Something went wrong. Try again."))
            } finally {
                opening = false
            }
        }
    }

    ProviderModal(
        title = title,
        subtitle = listOfNotNull(
            LF("Source %d", position + 1),
            (source.date ?: source.at)?.let { ClientsText.shortDay(it) },
            source.page?.let { LF("Page %d", it) },
        ).joinToString(" · "),
        systemImage = "file-search", accent = ModalAccent.aqua, cancelTitle = L("Close"),
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        FlowLayout(spacing = 6.dp) {
            if (kind != null && kind != title) Chip(text = kind, tone = Chip.Tone.info)
            if (source.kind == "document" || source.data?.get("unverified").bool == true) {
                Chip(text = L("Unverified document"), tone = Chip.Tone.warn, icon = "file-exclamation-point")
            }
        }
        if (source.kind == "document") {
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("Document text is automatically extracted and unverified. Check the original before using doses or lab values."))
        }
        val lead = ClientsBriefFields.lead(source.data)
        if (lead != null) SelectionContainer { Text(lead, style = Theme.bodyFont, color = Theme.inkStrong) }
        if (fields.isEmpty() && lead == null) {
            Text(L("This source has no more details."), style = Theme.text(14), color = Theme.inkSoft)
        }
        ClientsGrid(items = fields, minimum = 150.dp, spacing = 8.dp) { _, row ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label.uppercase(), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.1), color = Theme.inkSoft)
                    if (row.staffOnly) Chip(text = L("Staff only@brief"), tone = Chip.Tone.warn, icon = "lock")
                }
                Text(row.value, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val attachment = source.attachmentId
            if (attachment != null) {
                PMButton(L("Open original"), systemImage = "file", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = opening) { openOriginal(attachment) }
            }
            val parts = source.id.split(":", limit = 2)
            val kindKey = parts.firstOrNull() ?: ""
            val id = if (parts.size > 1) parts[1] else ""
            when (kindKey) {
                "visit" -> PMButton(L("Open record@brief"), variant = ButtonVariant.outline, size = ButtonSize.compact) { dismiss(); onOpen(ProviderRoute.appointment(id)) }
                "clinic_record" -> PMButton(L("Open record@brief"), variant = ButtonVariant.outline, size = ButtonSize.compact) { dismiss(); onOpen(ProviderRoute.record(id)) }
                "hospitalization" -> PMButton(L("Open record@brief"), variant = ButtonVariant.outline, size = ButtonSize.compact) { dismiss(); onOpen(ProviderRoute.hospitalization(id)) }
                "follow_up" -> PMButton(L("Open follow-ups"), variant = ButtonVariant.outline, size = ButtonSize.compact) { dismiss(); onFollowUps() }
                else -> {}
            }
        }
        if (sources.size > 1) {
            val label = L("Sources")
            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircleButton(systemImage = "chevron-left", label = L("Previous source"), enabled = position > 0) { position = maxOf(0, position - 1) }
                Spacer(Modifier.weight(1f))
                Text(LF("%1\$d of %2\$d", position + 1, sources.size), style = Theme.text(13, FontWeight.Bold), color = Theme.inkSoft)
                Spacer(Modifier.weight(1f))
                IconCircleButton(systemImage = "chevron-right", label = L("Next source"), enabled = position < sources.size - 1) {
                    position = minOf(sources.size - 1, position + 1)
                }
            }
        }
    }
}

/** A source's fields as label and value (summary-sources.tsx `sourceFields`). */
object ClientsBriefFields {
    data class Row(val label: String, val value: String, val staffOnly: Boolean)

    private val leadKeys = listOf("summary", "details", "notes", "note", "text", "message")
    private val internalKeys: Set<String> = setOf("internal_notes", "internalNote", "internal_note")

    fun lead(data: JSONValue?): String? {
        val obj = data as? JsonObject ?: return null
        for (key in leadKeys) obj[key].string.nonEmpty?.let { return it }
        return null
    }

    fun rows(data: JSONValue?, title: String?): List<Row> {
        val obj = data as? JsonObject ?: return emptyList()
        val leadKey = leadKeys.firstOrNull { obj[it].string.nonEmpty != null }
        val rows = mutableListOf<Row>()
        for (key in obj.keys.sortedWith(order)) {
            if (key == leadKey) continue
            collect(key, obj[key] ?: JsonNull, parent = null, into = rows, title = title)
        }
        // Two rows with one label read as one.
        val merged = mutableListOf<Row>()
        for (row in rows) {
            val i = merged.indexOfFirst { it.label == row.label }
            if (i >= 0) merged[i] = Row(row.label, "${merged[i].value} · ${row.value}", merged[i].staffOnly || row.staffOnly)
            else merged.add(row)
        }
        return merged
    }

    private val firstKeys = listOf("name", "title", "status", "recordType", "medication_name", "medicine_name", "vaccine_name", "test_name", "condition", "trigger", "diagnosis")

    private val order = Comparator<String> { a, b ->
        val ia = firstKeys.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it }
        val ib = firstKeys.indexOf(b).let { if (it < 0) Int.MAX_VALUE else it }
        if (ia != ib) ia.compareTo(ib) else a.compareTo(b)
    }

    private fun hidden(key: String): Boolean = key == "id" || key.endsWith("_id") || key.endsWith("Id") || key == "extraction" || key == "unverified"

    private fun collect(key: String, value: JsonElement, parent: String?, into: MutableList<Row>, title: String?) {
        if (hidden(key)) return
        val label = label(key, parent = parent)
        when (value) {
            is JsonNull -> return
            is JsonObject -> for (childKey in value.keys.sortedWith(order)) collect(childKey, value[childKey] ?: JsonNull, parent = key, into = into, title = title)
            is JsonArray -> {
                if (value.all { it !is JsonObject }) {
                    val text = value.mapNotNull { item -> item.string?.let { optionValue(key, it) } }.joinToString(", ")
                    if (text.isNotEmpty()) into.add(Row(label, text, internalKeys.contains(key)))
                } else {
                    value.forEachIndexed { i, item ->
                        val child = item as? JsonObject ?: return@forEachIndexed
                        val inner = mutableListOf<Row>()
                        for (childKey in child.keys.sortedWith(order)) collect(childKey, child[childKey] ?: JsonNull, parent = key, into = inner, title = title)
                        into.addAll(inner.map { Row(if (i == 0) it.label else "${it.label} ${i + 1}", it.value, it.staffOnly) })
                    }
                }
            }
            is JsonPrimitive -> {
                val text = format(key, value) ?: return
                if (text.isEmpty() || text == title) return
                into.add(Row(label, text, internalKeys.contains(key)))
            }
        }
    }

    private fun format(key: String, value: JsonPrimitive): String? {
        if (!value.isString) {
            value.booleanOrNull?.let { return if (it) L("Yes") else L("No") }
            return value.string
        }
        val text = value.content
        if (Regex("""^\d{4}-\d{2}-\d{2}$""").containsMatchIn(text)) return ClientsText.shortDay(text)
        if (Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$""").containsMatchIn(text)) return text
        if (Regex("""^\d{4}-\d{2}-\d{2}T""").containsMatchIn(text)) return ClientsText.dayTime(text)
        return optionValue(key, text)
    }

    private fun spanish(): Boolean = LocalizationManager.currentLanguage == AppLanguage.spanish

    private fun optionValue(key: String, value: String): String {
        if (listOf("handling_tags", "services", "authorized_extras", "coat", "skin", "behaviour", "progress").contains(key)) return SvcReportText.option(value)
        if (listOf("status", "verificationStatus", "clinical_state", "clinicalState").contains(key)) statuses[value]?.let { return if (spanish()) it.second else it.first }
        if (key == "recordType") return ClientsText.recordType(value)
        if (listOf("service_category", "kind", "asked_by", "areas", "poop", "ate", "schedule_kind", "sitting_mode", "mood").contains(key)) {
            codes[value]?.let { return if (spanish()) it.second else it.first }
        }
        return value
    }

    fun label(key: String, parent: String?): String {
        if (parent != null) contextLabels["$parent.$key"]?.let { return if (spanish()) it.second else it.first }
        labels[key]?.let { return if (spanish()) it.second else it.first }
        val words = key.replace("_", " ").replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        return words.take(1).uppercase() + words.drop(1)
    }

    private val labels: Map<String, Pair<String, String>> = mapOf(
        "name" to ("Name" to "Nombre"), "title" to ("Title" to "Título"), "species" to ("Species" to "Especie"), "breed" to ("Breed" to "Raza"), "sex" to ("Sex" to "Sexo"),
        "birthDate" to ("Birth date" to "Fecha de nacimiento"), "isNeutered" to ("Neutered" to "Esterilizado"), "status" to ("Status" to "Estado"),
        "recordType" to ("Record type" to "Tipo de registro"), "verificationStatus" to ("Verification" to "Verificación"), "summary" to ("Summary" to "Resumen"),
        "details" to ("Details" to "Detalles"), "notes" to ("Notes" to "Notas"), "note" to ("Note" to "Nota"), "internalNote" to ("Internal note" to "Nota interna"),
        "internal_note" to ("Internal note" to "Nota interna"), "internal_notes" to ("Internal note" to "Nota interna"), "text" to ("Document text" to "Texto del documento"),
        "message" to ("Update" to "Actualización"), "condition" to ("Condition" to "Condición"), "trigger" to ("Allergen" to "Alérgeno"), "reaction" to ("Reaction" to "Reacción"),
        "diagnosis" to ("Diagnosis" to "Diagnóstico"), "eventAt" to ("Recorded date" to "Fecha del registro"), "event_at" to ("Recorded date" to "Fecha del registro"),
        "recorded_at" to ("Measured at" to "Fecha de medición"), "signed_at" to ("Signed at" to "Fecha de firma"), "created_at" to ("Created at" to "Fecha de creación"),
        "followUpAt" to ("Follow-up date" to "Fecha de seguimiento"), "follow_up_at" to ("Follow-up date" to "Fecha de seguimiento"), "due_at" to ("Due date" to "Fecha límite"),
        "nextDueAt" to ("Next due date" to "Próxima fecha"), "next_due_date" to ("Next due date" to "Próxima fecha"), "admitted_at" to ("Admitted at" to "Fecha de ingreso"),
        "ended_at" to ("Ended at" to "Fecha de salida"), "starts_at" to ("Starts at" to "Inicio"), "ends_at" to ("Ends at" to "Fin"), "day_key" to ("Day" to "Día"),
        "medication_name" to ("Medication" to "Medicamento"), "medicine_name" to ("Medication" to "Medicamento"), "active_ingredient" to ("Active ingredient" to "Principio activo"),
        "strength" to ("Strength" to "Concentración"), "dose_amount" to ("Dose" to "Dosis"), "dose_unit" to ("Dose unit" to "Unidad de dosis"), "route" to ("Route" to "Vía"),
        "every_hours" to ("Interval (hours)" to "Intervalo (horas)"), "frequency_text" to ("Frequency" to "Frecuencia"), "duration_days" to ("Duration (days)" to "Duración (días)"),
        "instructions" to ("Instructions" to "Indicaciones"), "as_needed" to ("As needed" to "Según necesidad"), "valid_until" to ("Prescription valid until" to "Receta válida hasta"),
        "cancelled_at" to ("Cancelled at" to "Fecha de cancelación"), "cancel_reason" to ("Cancellation reason" to "Motivo de cancelación"), "stop_reason" to ("Reason it was stopped" to "Motivo de suspensión"),
        "not_given_reason" to ("Reason not given" to "Motivo por el que no se dio"), "transfer_reason" to ("Transfer reason" to "Motivo del traslado"), "death_cause" to ("Cause of death" to "Causa de muerte"),
        "rate_ml_per_hour" to ("Rate (ml/h)" to "Ritmo (ml/h)"), "schedule_kind" to ("Schedule" to "Horario"), "weight_grams" to ("Weight (g)" to "Peso (g)"), "weightGrams" to ("Weight (g)" to "Peso (g)"),
        "weight_kg" to ("Weight (kg)" to "Peso (kg)"), "test_name" to ("Test" to "Prueba"), "result_summary" to ("Result" to "Resultado"), "vaccine_name" to ("Vaccine" to "Vacuna"),
        "reason" to ("Reason" to "Motivo"), "discharge_diagnosis" to ("Discharge diagnosis" to "Diagnóstico de alta"), "discharge_summary" to ("Discharge summary" to "Resumen de alta"),
        "discharge_instructions" to ("Discharge instructions" to "Indicaciones de alta"), "temperature_c" to ("Temperature (°C)" to "Temperatura (°C)"), "heart_rate" to ("Heart rate" to "Frecuencia cardíaca"),
        "respiratory_rate" to ("Respiratory rate" to "Frecuencia respiratoria"), "pain_score" to ("Pain score" to "Escala de dolor"), "mucous_membranes" to ("Mucous membranes" to "Mucosas"),
        "crt_seconds" to ("Capillary refill (s)" to "Llenado capilar (s)"), "outcome_note" to ("Outcome" to "Resultado"), "check_in_day" to ("Check-in" to "Entrada"), "check_out_day" to ("Check-out" to "Salida"),
        "sitting_mode" to ("Sitting" to "Modalidad"), "owner_instructions" to ("What the family asked" to "Lo que pidió la familia"), "mood" to ("Mood" to "Ánimo"), "time" to ("Time" to "Hora"),
        "service_label" to ("Service" to "Servicio"), "label" to ("Treatment" to "Tratamiento"), "handling_tags" to ("Handling" to "Manejo"), "usual_cut" to ("Usual cut" to "Corte habitual"),
        "blade_or_length" to ("Blade or length" to "Cuchilla o largo"), "products" to ("Products" to "Productos"), "care_notes" to ("Care notes" to "Notas de cuidado"),
        "rebook_every_weeks" to ("Comes back every (weeks)" to "Regresa cada (semanas)"), "service_category" to ("Kind of visit" to "Tipo de visita"), "arrival_note" to ("Arrival note" to "Nota de llegada"),
        "kind" to ("Kind" to "Tipo"), "services" to ("Services" to "Servicios"), "style" to ("Cut" to "Corte"), "length" to ("Length" to "Largo"), "areas" to ("Areas" to "Zonas"),
        "authorized_extras" to ("Extras the owner allows" to "Extras que autoriza el dueño"), "same_as_last_time" to ("Same as last time" to "Igual que la última vez"), "asked_by" to ("Asked by" to "Lo pidió"),
        "coat" to ("Coat" to "Pelaje"), "skin" to ("Skin" to "Piel"), "behaviour" to ("Behaviour" to "Comportamiento"), "differenceNote" to ("Why it differs from the request" to "Por qué difiere de lo pedido"),
        "skills" to ("Practised" to "Practicaron"), "progress" to ("Progress" to "Avance"), "homework" to ("Homework" to "Tarea en casa"), "minutes" to ("Minutes" to "Minutos"),
        "distanceKm" to ("Distance (km)" to "Distancia (km)"), "pee" to ("Pee" to "Pipí"), "poop" to ("Poop" to "Popó"), "water" to ("Water" to "Agua"), "ate" to ("Food" to "Comida"), "medsGiven" to ("Medicine given" to "Medicina dada"),
    )

    private val contextLabels: Map<String, Pair<String, String>> = mapOf(
        "groom_request.services" to ("Services requested" to "Servicios solicitados"), "groom_request.style" to ("Cut requested" to "Corte solicitado"),
        "groom_request.length" to ("Length requested" to "Largo solicitado"), "groom_request.note" to ("Note with the request" to "Nota del pedido"),
        "report.services" to ("Services done" to "Servicios realizados"), "report.style" to ("Cut done" to "Corte realizado"), "report.length" to ("Length done" to "Largo realizado"),
        "report.note" to ("Note in the report" to "Nota del reporte"), "report.kind" to ("Report" to "Reporte"), "care_plan.notes" to ("Care plan notes" to "Notas del plan de cuidado"),
        "items.kind" to ("Planned care" to "Cuidado planeado"), "items.label" to ("Planned item" to "Indicación del plan"), "items.time" to ("Planned time" to "Hora planeada"),
        "report_cards.message" to ("Report card" to "Reporte de la estancia"), "report_cards.created_at" to ("Report card date" to "Fecha del reporte"),
        "home_visits.kind" to ("Home visit" to "Visita en casa"), "home_visits.day_key" to ("Home visit day" to "Día de la visita"), "home_visits.status" to ("Home visit status" to "Estado de la visita"),
        "home_visits.note" to ("Home visit note" to "Nota de la visita"), "care.kind" to ("Care" to "Cuidado"), "care.day_key" to ("Care day" to "Día del cuidado"),
        "care.label" to ("Care item" to "Cuidado indicado"), "care.status" to ("Care status" to "Estado del cuidado"), "care.note" to ("Care note" to "Nota del cuidado"),
    )

    private val codes: Map<String, Pair<String, String>> = mapOf(
        "groomer" to ("Grooming" to "Estética"), "walker" to ("Walk" to "Paseo"), "sitter" to ("Sitting" to "Cuidado"), "daycare" to ("Daycare" to "Guardería"), "trainer" to ("Training" to "Entrenamiento"),
        "vet" to ("Veterinary" to "Veterinaria"), "boarding" to ("Boarding" to "Hospedaje"), "home_sitting" to ("Home sitting" to "Cuidado en casa"), "overnight" to ("Overnight" to "Noche"),
        "visits" to ("Visits" to "Visitas"), "meet_greet" to ("Meet & greet" to "Primer encuentro"), "meal" to ("Meal" to "Comida"), "play" to ("Play" to "Juego"), "custom" to ("Other" to "Otro"),
        "happy" to ("Happy" to "Contento"), "playful" to ("Playful" to "Juguetón"), "calm" to ("Calm" to "Tranquilo"), "shy" to ("Shy" to "Tímido"), "tired" to ("Tired" to "Cansado"),
        "groom" to ("Groom" to "Estética"), "walk" to ("Walk or sitting" to "Paseo o cuidado"), "training" to ("Training" to "Entrenamiento"), "family" to ("The family" to "La familia"),
        "staff" to ("The team" to "El equipo"), "face" to ("Face" to "Cara"), "feet" to ("Feet" to "Patas"), "tail" to ("Tail" to "Cola"), "body" to ("Body" to "Cuerpo"), "sanitary" to ("Sanitary area" to "Área sanitaria"),
        "normal" to ("Normal" to "Normal"), "soft" to ("Soft" to "Blanda"), "diarrhea" to ("Diarrhea" to "Diarrea"), "all" to ("Ate everything" to "Comió todo"), "some" to ("Ate some" to "Comió algo"),
        "none" to ("Didn’t eat" to "No comió"), "medication" to ("Medication" to "Medicamento"), "fluid" to ("Fluids" to "Fluidos"), "fluids" to ("Fluids" to "Fluidos"), "procedure" to ("Procedure" to "Procedimiento"),
        "monitoring" to ("Monitoring" to "Monitoreo"), "feeding" to ("Feeding" to "Alimentación"), "once" to ("Once" to "Una vez"), "recurring" to ("Recurring" to "Recurrente"),
        "continuous" to ("Continuous" to "Continuo"), "prn" to ("As needed" to "Según necesidad"),
    )

    private val statuses: Map<String, Pair<String, String>> = mapOf(
        "completed" to ("Finished" to "Terminada"), "no_show" to ("Didn’t show up" to "No se presentó"), "unfinished" to ("Not finished" to "Sin terminar"), "active" to ("Active" to "Activo"),
        "inactive" to ("Inactive" to "Inactivo"), "archived" to ("Archived" to "Archivado"), "draft" to ("Draft" to "Borrador"), "signed" to ("Signed" to "Firmada"), "cancelled" to ("Cancelled" to "Cancelada"),
        "canceled" to ("Cancelled" to "Cancelada"), "expired" to ("Expired" to "Vencida"), "open" to ("Open" to "Abierto"), "contacted" to ("Reminder sent" to "Recordatorio enviado"),
        "scheduled" to ("Visit booked" to "Visita agendada"), "snoozed" to ("Snoozed" to "Pospuesto"), "done" to ("Done" to "Hecho"), "pending" to ("Pending" to "Pendiente"),
        "admitted" to ("Admitted" to "Hospitalizado"), "discharged" to ("Discharged" to "Dado de alta"), "transferred" to ("Transferred" to "Trasladado"), "deceased" to ("Deceased" to "Fallecido"),
        "given" to ("Given" to "Administrado"), "not_given" to ("Not given" to "No administrado"), "skipped" to ("Skipped" to "Omitido"), "refused" to ("Refused" to "Rechazado"),
        "stopped" to ("Stopped" to "Suspendido"), "resolved" to ("Resolved" to "Resuelto"), "verified" to ("Verified" to "Verificado"), "unverified" to ("Not verified" to "Sin verificar"),
        "rejected" to ("Rejected" to "Rechazado"), "checked_out" to ("Finished" to "Terminada"), "missed" to ("Missed" to "No realizada"),
    )
}
