package com.pawmiaw.business.clients

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import com.pawmiaw.business.money.*
import com.pawmiaw.business.records.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

//
//  ClientsChartHistory.kt (iOS ClientsChartHistory.swift)
//
//  The chart's tabs (web: patient-chart/chart-timeline.tsx, chart-files.tsx
//  and the tabs of provider.clients.$clientId.pets.$petId.tsx): one line of
//  time of everything that happened, newest first and paged by the server
//  (20 at a time as it scrolls), with filters and a search; the visits,
//  follow-ups, files, bills and the pet's details. And weighing the pet
//  (chart-weight-sheet.tsx).
//

// MARK: - History

private val timelineFilters = listOf("consults", "grooming", "vaccines", "medications", "labs", "surgeries", "family", "notes")

@Composable
fun ClientsTimelineView(clientId: String, petId: String, chart: ClientsChart, modifier: Modifier = Modifier) {
    var filters by remember { mutableStateOf<Set<String>>(emptySet()) }
    val query = remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ClientsTimelineItem>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf<ClientsTimelineSources?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Set<String>>(emptySet()) }
    var signing by remember { mutableStateOf<String?>(null) }
    var consentOpen by remember { mutableStateOf<ClientsConsentTarget?>(null) }
    val scope = rememberCoroutineScope()
    val viewer = chart.viewer

    val choices = timelineFilters.filter { filter ->
        when (filter) {
            "family" -> viewer?.seesRecords == true && (sources?.family ?: true)
            "consults" -> viewer?.seesVisits == true || viewer?.seesRecords == true
            // Only for a pet that has been groomed here; it stays while it is the one picked.
            "grooming" -> viewer?.seesVisits == true && (sources?.grooming == true || filters.contains("grooming"))
            else -> viewer?.seesRecords == true
        }
    }

    suspend fun load(reset: Boolean) {
        if (reset) loading = true else {
            if (loadingMore || cursor == null) return
            loadingMore = true
        }
        try {
            val types = filters.sorted().joinToString(",").nonEmpty
            val page: ClientsTimelinePage = API.get(
                "/provider-clients/$clientId/pets/$petId/timeline",
                mapOf("limit" to "20", "types" to types, "q" to search.nonEmpty, "cursor" to if (reset) null else cursor),
            )
            if (reset) {
                items = page.items
                sources = page.sources
            } else {
                val known = items.map { it.id }.toSet()
                items = items + page.items.filter { !known.contains(it.id) }
            }
            cursor = page.nextCursor
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            if (!reset) Toast.error(e)
        } finally {
            loading = false
            loadingMore = false
        }
        loaded = true
    }

    fun reload() {
        scope.launch { load(reset = true) }
    }

    /** Signing happens inside an opened entry — the note is on screen — so one tap. */
    fun sign(record: ClientsChartRecord) {
        signing = record.id
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-records/${record.id}/sign")
                Toast.show(if (chart.client.householdId != null) L("Signed. The family can see it now.") else L("Signed."))
                announceDataChanged()
                load(reset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not sign it."))
            } finally {
                signing = null
            }
        }
    }

    LaunchedEffect(Unit) { load(reset = true) }
    Debounced(query.value) { value ->
        val trimmed = value.trim()
        if (trimmed == search) return@Debounced
        search = trimmed
        load(reset = true)
    }

    val timeline = ClientsTimelineContext(
        chart = chart, open = open, signing = signing,
        toggle = { key -> open = if (open.contains(key)) open - key else open + key },
        sign = { sign(it) },
        openConsent = { consentOpen = it },
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(prompt = L("Search the history"), text = query)
            if (choices.isNotEmpty()) {
                val show = L("Show")
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp)
                        .semantics { contentDescription = show },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(title = L("All"), isSelected = filters.isEmpty()) { filters = emptySet(); reload() }
                    choices.forEach { filter ->
                        FilterChip(title = timelineFilterLabel(filter), isSelected = filters.contains(filter)) {
                            filters = if (filters.contains(filter)) filters - filter else filters + filter
                            reload()
                        }
                    }
                }
            }
        }
        val searching = search.isNotEmpty() || filters.isNotEmpty()
        if (!loaded && loading) {
            LoadingState()
        } else if (failed && items.isEmpty()) {
            ErrorState(message = L("The history could not be loaded.")) { load(reset = true) }
        } else if (items.isEmpty()) {
            EmptyState(
                systemImage = if (searching) "search" else "history",
                title = if (searching) L("Nothing matches") else L("No history yet"),
                message = if (searching) L("Try another word or clear the filters.") else L("Visits, records and weights will appear here."),
            )
        } else {
            ClientsChartSectionTitle(text = if (searching) L("Results") else L("Newest first"))
            Column(Modifier.alpha(if (loading) 0.6f else 1f)) {
                items.forEach { item ->
                    androidx.compose.runtime.key(item.id) {
                        Box(
                            Modifier.onScrolledIntoView {
                                if (item.id == items.lastOrNull()?.id && cursor != null) scope.launch { load(reset = false) }
                            },
                        ) { TimelineEntry(item, timeline) }
                    }
                }
                if (loadingMore) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Theme.blue, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    consentOpen?.let { target ->
        AppSheet(onDismissRequest = { consentOpen = null }) {
            ConsentViewSheet(
                consentId = target.id,
                context = ConsentContext(
                    act = ConsentAct(providerClientPetId = chart.pet.id),
                    clientName = chart.client.name,
                    familyOnApp = chart.client.onPawMiaw ?: false,
                    clientEmail = chart.client.email,
                    clientPhone = chart.client.phone,
                ),
            )
        }
    }
}

object ClientsTimelineView {
    fun recordTitle(record: ClientsChartRecord): String {
        for (key in listOf("vaccine_name", "medication_name", "test_name", "condition", "trigger", "procedure")) {
            val value = record.payload?.get(key).string.nonEmpty
            if (value != null) return value
        }
        return record.title ?: ClientsText.recordType(record.recordType)
    }

    fun familyTitle(record: ClientsFamilyRecord): String {
        for (key in listOf("vaccine_name", "medication_name", "test_name", "condition", "trigger", "procedure", "product_name")) {
            val value = record.payload?.get(key).string.nonEmpty
            if (value != null) return value
        }
        return record.title ?: L("Record")
    }
}

private fun timelineFilterLabel(filter: String): String = when (filter) {
    "consults" -> L("Consults")
    "grooming" -> L("Grooming")
    "vaccines" -> L("Vaccines")
    "medications" -> L("Medications")
    "labs" -> L("Lab")
    "surgeries" -> L("Surgeries")
    "family" -> L("From the family")
    else -> L("Notes")
}

/** What the entries read from the timeline's state. */
private class ClientsTimelineContext(
    val chart: ClientsChart,
    val open: Set<String>,
    val signing: String?,
    val toggle: (String) -> Unit,
    val sign: (ClientsChartRecord) -> Unit,
    val openConsent: (ClientsConsentTarget) -> Unit,
) {
    val viewer: ClientsChartViewer? get() = chart.viewer
}

// MARK: Entries

@Composable
private fun TimelineEntry(item: ClientsTimelineItem, t: ClientsTimelineContext) {
    when (item.kind) {
        "visit" -> item.visit?.let { VisitEntry(item.key, it, t) }
        "record" -> item.record?.let { RecordEntry(item.key, it, t) }
        "family_record" -> item.familyRecord?.let { FamilyEntry(item.key, it, t) }
        "weight" -> item.weight?.let { WeightEntry(item.key, it, t) }
        "incident" -> item.incident?.let { IncidentEntry(item.key, it, t) }
        "consent" -> item.consent?.let { ConsentEntry(item.key, it, t) }
        // A kind this screen doesn't know yet (a newer server) is left out rather than breaking the list.
        else -> {}
    }
}

/**
 * One entry on the line of time (web `EntryShell`): the rail and its 28 disc of the entry's tone, then a
 * bordered box that opens in place (blue edge while open).
 */
@Composable
private fun EntryShell(
    key: String,
    icon: String,
    tone: androidx.compose.ui.graphics.Color,
    title: AnnotatedString,
    meta: String,
    t: ClientsTimelineContext,
    detail: String? = null,
    badges: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val isOpen = t.open.contains(key)
    val shape = RoundedCornerShape(Theme.boxRadius)
    val rail = Theme.borderSoft
    val chevron by animateFloatAsState(if (isOpen) 180f else 0f, tween(200), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 14.dp.toPx()
                drawLine(rail, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(28.dp).padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(Theme.wash(tone, 25.0), CircleShape)
                    .border(2.dp, Theme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(icon, 14.dp, tint = Theme.mix(tone, 70.0, Theme.inkStrong))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(bottom = 8.dp)
                .softShadow(shape, Theme.navy.copy(alpha = if (isOpen) 0.06f else 0f), 2.dp, y = 1.dp)
                .background(Theme.surface, shape)
                .border(1.dp, if (isOpen) Theme.blue else Theme.borderSoft, shape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton { t.toggle(key) }
                    .semantics { selected = isOpen }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Theme.text(14), color = Theme.inkStrong)
                    Text(meta, style = Theme.text(12), color = Theme.inkSoft)
                    if (detail != null) Text(detail, style = Theme.text(12), color = Theme.inkStrong)
                    FlowLayout(spacing = 4.dp, modifier = Modifier.padding(top = 4.dp)) { badges() }
                }
                Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp).rotate(chevron))
            }
            AnimatedVisibility(
                visible = isOpen,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
            ) {
                Column {
                    RowDivider()
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                }
            }
        }
    }
}

/** A title in bold with what was written there after it, quieter (web: "Consulta — dolor de oído"). */
@Composable
private fun entryTitle(main: String, line: String?): AnnotatedString {
    val soft = Theme.inkSoft
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(main) }
        line.nonEmpty?.let { withStyle(SpanStyle(color = soft)) { append(" — $it") } }
    }
}

private fun boldTitle(text: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
}

@Composable
private fun VisitEntry(key: String, visit: ClientsChartVisit, t: ClientsTimelineContext) {
    val groom = visit.serviceCategory == "groomer"
    val records = visit.records ?: emptyList()
    val note = records.firstOrNull { it.recordType == "vet_visit" } ?: records.firstOrNull()
    val line = note?.summary ?: note?.details ?: (if (visit.report == null || visit.report.isNull) visit.notes else null)
    val drafts = records.count { it.status == "draft" }
    val weights = visit.weights ?: emptyList()
    val hasReport = visit.report != null && !visit.report.isNull
    val report = ClientsReportBlock.decode(visit.report)
    EntryShell(
        key, icon = if (groom) "scissors" else "stethoscope", tone = if (groom) Theme.pink else Theme.blue,
        title = entryTitle(visit.serviceLabel ?: "—", line),
        meta = listOf(ClientsText.dayTime(visit.startsAt), visit.assigned?.name, visit.location?.name).mapNotNull { it.nonEmpty }.joinToString(" · "),
        t = t,
        detail = if (groom) visit.groomRequestSummary?.let { LF("Asked for: %@", it) } else null,
        badges = {
            if (visit.status != "completed") Chip(text = ClientsText.visitStatus(visit.status), tone = if (visit.status == "no_show") Chip.Tone.danger else Chip.Tone.warn)
            if (drafts > 0) Chip(text = L("To sign"), tone = Chip.Tone.warn)
            (report?.skin ?: emptyList()).filter { it != "ok" }.forEach { finding ->
                Chip(text = SvcReportText.option(finding), tone = Chip.Tone.danger, icon = "triangle-alert")
            }
            if (hasReport) Chip(text = L("Report"), tone = Chip.Tone.good)
            weights.firstOrNull()?.let { Chip(text = ClientsText.weight(it.grams) ?: "—", tone = Chip.Tone.good) }
            if (visit.carriedOver == true) Chip(text = L("With its earlier family"), tone = Chip.Tone.warn)
            else if (visit.actionable == false) Chip(text = L("Another location"), tone = Chip.Tone.muted, icon = "map-pin")
        },
    ) {
        val arrival = visit.arrivalNote.nonEmpty
        val notes = visit.notes.nonEmpty
        if (arrival != null) TextBox(L("Reason for the visit"), arrival)
        else if (notes != null) TextBox(L("Booking notes"), notes)
        weights.forEach { weight ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("scale", 16.dp, tint = Theme.inkSoft)
                Text(LF("Weighed %@", ClientsText.weight(weight.grams) ?: "—"), style = Theme.text(14), color = Theme.inkStrong)
            }
        }
        if (report != null) ClientsReportBlock(report = report)
        records.forEach { record ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.surfaceMuted, 60.0), RoundedCornerShape(Theme.chipRadius))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowLayout(spacing = 6.dp) {
                    recordsTypeChip(RecordsKind.of(record.recordType))
                    Text(ClientsTimelineView.recordTitle(record), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    if (record.status == "draft") Chip(text = L("To sign"), tone = Chip.Tone.warn)
                }
                RecordBody(record, t)
            }
        }
        if (records.isEmpty() && !hasReport && t.viewer?.seesRecords == true) {
            Text(L("No note was written for this visit."), style = Theme.text(14), color = Theme.inkSoft)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (visit.actionable != false) {
                OpenLink(L("Open visit"), ProviderRoute.appointment(visit.id))
            } else if (visit.carriedOver == true) {
                // A visit it made with the client it was with before: part of its history, read here.
                Text(L("From its earlier record — read only here."), style = Theme.text(12), color = Theme.inkSoft)
            } else {
                Text(LF("At %@ — only its team can act on this visit.", visit.location?.name ?: L("another location")), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

@Composable
private fun RecordEntry(key: String, record: ClientsChartRecord, t: ClientsTimelineContext) {
    EntryShell(
        key, icon = "calendar-days", tone = Theme.aqua,
        title = entryTitle(ClientsTimelineView.recordTitle(record), record.summary),
        meta = listOf(ClientsText.shortDay(record.eventAt), record.signedBy?.name ?: record.createdBy?.name).mapNotNull { it.nonEmpty }.joinToString(" · "),
        t = t,
        badges = {
            recordsTypeChip(RecordsKind.of(record.recordType))
            if (record.carriedOver == true) Chip(text = L("With its earlier family"), tone = Chip.Tone.warn)
            if (record.status == "draft") Chip(text = L("To sign"), tone = Chip.Tone.warn)
            val count = record.attachments?.size
            if (count != null && count > 0) Chip(text = LP(count, "%d file", "%d files"), tone = Chip.Tone.info)
        },
    ) {
        RecordBody(record, t)
    }
}

/** The written part of a clinic record, with the staff-only note marked as such (web `RecordBody`). */
@Composable
private fun RecordBody(record: ClientsChartRecord, t: ClientsTimelineContext) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        record.summary.nonEmpty?.let { TextBox(L("For the family"), it, tone = Theme.aqua) }
        record.details.nonEmpty?.let { TextBox(L("Clinical notes"), it) }
        record.internalNote.nonEmpty?.let { TextBox(L("Staff only"), it, tone = Theme.mango, icon = "lock") }
        PayloadFields(record.payload)
        ClientsAttachmentTiles(files = record.attachments ?: emptyList())
        FlowLayout(spacing = 8.dp) {
            val signedLine = if (record.status == "draft") {
                LF("Draft by %@", record.createdBy?.name ?: L("someone on the team"))
            } else {
                LF("Signed by %@ · %@", record.signedBy?.name ?: "—", ClientsText.shortDay(record.signedAt ?: record.eventAt)) +
                    (record.amendedAt?.let { " · ${LF("corrected %@", ClientsText.shortDay(it))}" } ?: "")
            }
            Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                Text(signedLine, style = Theme.text(12), color = Theme.inkSoft)
            }
            if (record.status == "draft" && t.viewer?.canSign == true && record.actionable != false) {
                PMButton(L("Sign"), systemImage = "badge-check", size = ButtonSize.compact, isWorking = t.signing == record.id) { t.sign(record) }
            }
            Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                OpenLink(L("Open record"), ProviderRoute.record(record.id))
            }
        }
    }
}

@Composable
private fun FamilyEntry(key: String, record: ClientsFamilyRecord, t: ClientsTimelineContext) {
    EntryShell(
        key, icon = "house", tone = Theme.pink,
        title = entryTitle(ClientsTimelineView.familyTitle(record), record.summary),
        meta = listOf(ClientsText.shortDay(record.eventAt ?: record.createdAt), record.by).mapNotNull { it.nonEmpty }.joinToString(" · "),
        t = t,
        badges = {
            recordsTypeChip(RecordsKind.of(record.recordType))
            Chip(text = L("Registered by the family"), tone = Chip.Tone.online, icon = "user-round")
        },
    ) {
        record.details.nonEmpty?.let { TextBox(L("Notes"), it) }
        PayloadFields(record.payload)
        record.nextDueAt?.let { Text(LF("Next due %@", ClientsText.shortDay(it)), style = Theme.text(14), color = Theme.inkStrong) }
        ClientsAttachmentTiles(files = record.attachments ?: emptyList())
        Text(L("Read-only: the family keeps this record in PawMiaw."), style = Theme.text(12), color = Theme.inkSoft)
    }
}

/** A weight: the clinic's, or one the family logged at home and shares — shown as theirs, never as the pet's weight here. */
@Composable
private fun WeightEntry(key: String, weight: ClientsChartWeight, t: ClientsTimelineContext) {
    val atHome = weight.from == "family"
    val takenFromHome = weight.from == "clinic" && weight.source == "family"
    EntryShell(
        key, icon = "scale", tone = if (atHome) Theme.apricot else Theme.lime,
        title = boldTitle(if (atHome) LF("At home: %@", ClientsText.weight(weight.grams) ?: "—") else LF("Weighed %@", ClientsText.weight(weight.grams) ?: "—")),
        meta = listOf(
            ClientsText.shortDay(weight.recordedAt),
            if (atHome) L("the family") else weight.loggedBy ?: (if (weight.source == "manual" && weight.from == null) L("the family") else null),
        ).mapNotNull { it.nonEmpty }.joinToString(" · "),
        t = t,
        badges = {},
    ) {
        weight.notes.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkStrong) }
        Text(
            when {
                atHome -> L("Logged by the family at home and shared with you.")
                takenFromHome -> L("The family’s weight, taken into the clinic’s log.")
                weight.sharedWithOwner == true -> L("Shared with the family")
                else -> L("Kept in the clinic")
            },
            style = Theme.text(12), color = Theme.inkSoft,
        )
    }
}

@Composable
private fun IncidentEntry(key: String, incident: JSONValue, t: ClientsTimelineContext) {
    EntryShell(
        key, icon = "shield-alert", tone = Theme.pink,
        title = entryTitle(L("Incident"), incident["whatHappened"].string),
        meta = listOf(
            ClientsText.dayTime(incident["occurredAt"].string),
            incident["serviceLabel"].string ?: (if (incident["stayId"].string.nonEmpty != null) L("Stay") else null),
        ).mapNotNull { it.nonEmpty }.joinToString(" · "),
        t = t,
        badges = {
            incident["type"].string?.let { type -> HueChip(text = IncidentWords.type(type), hue = Hue.peach, systemImage = IncidentWords.typeIcon(type)) }
            incident["severity"].string?.let { severity -> StatusChip(text = IncidentWords.severity(severity), state = IncidentWords.state(severity)) }
            incident["status"].string?.let { status ->
                StatusChip(
                    text = if (status == "resolved") L("Resolved") else L("Follow-up open"),
                    state = if (status == "resolved") ProviderState.done else ProviderState.active,
                    outlined = true,
                )
            }
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val appointment = incident["appointmentId"].string.nonEmpty
            val stay = incident["stayId"].string.nonEmpty
            if (appointment != null) OpenLink(L("Open visit"), ProviderRoute.appointment(appointment))
            else if (stay != null) OpenLink(L("Open stay"), ProviderRoute.stay(stay))
        }
    }
}

@Composable
private fun ConsentEntry(key: String, consent: JSONValue, t: ClientsTimelineContext) {
    val at = consent["signedAt"].string ?: consent["revokedAt"].string ?: consent["declinedAt"].string ?: consent["requestedAt"].string
    EntryShell(
        key, icon = "signature", tone = Theme.lavender,
        title = boldTitle(consent["title"].string ?: L("Consent")),
        meta = listOf(ClientsText.dayTime(at), consent["petName"].string).mapNotNull { it.nonEmpty }.joinToString(" · "),
        t = t,
        badges = {
            consent["status"].string?.let { status -> StatusChip(text = ClientsConsentWords.status(status), state = ClientsConsentWords.state(status)) }
        },
    ) {
        ClientsConsentWords.line(consent)?.let { Text(it, style = Theme.text(14), color = Theme.inkStrong) }
        consent["body"].string.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        FlowLayout(spacing = 12.dp) {
            val appointment = consent["appointmentId"].string.nonEmpty
            val stay = consent["stayId"].string.nonEmpty
            if (appointment != null) {
                Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) { OpenLink(L("Open visit"), ProviderRoute.appointment(appointment)) }
            } else if (stay != null) {
                Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) { OpenLink(L("Open stay"), ProviderRoute.stay(stay)) }
            }
            val id = consent["id"].string.nonEmpty
            if (id != null) {
                PMButton(
                    if (consent["status"].string == "signed") L("View and print") else L("View@consent"),
                    systemImage = "eye", variant = ButtonVariant.outline, size = ButtonSize.compact,
                ) {
                    t.openConsent(ClientsConsentTarget(id = id, petName = consent["petName"].string))
                }
            }
        }
    }
}

// MARK: Pieces

/** "Abrir visita ↗": a quiet blue link to the page the entry is about. */
@Composable
private fun OpenLink(text: String, route: ProviderRoute) {
    val nav = LocalNavigator.current
    Row(
        Modifier.plainButton { nav.push(route) },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
        Glyph("external-link", 12.dp, tint = Theme.blue)
    }
}

/** A labelled piece of writing (web `Text`): a bordered box, a wash of its tone, the label small and tracked. */
@Composable
private fun TextBox(label: String, text: String, tone: androidx.compose.ui.graphics.Color? = null, icon: String? = null) {
    val shape = RoundedCornerShape(Theme.chipRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (tone != null) Theme.wash(tone, 8.0) else Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.1), color = Theme.inkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
            if (icon != null) Glyph(icon, 14.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
            Text(text, style = Theme.text(14), color = Theme.inkStrong)
        }
    }
}

/** A record's type-specific fields, as label and value. */
@Composable
private fun PayloadFields(payload: Map<String, JSONValue>?) {
    val rows: List<Pair<String, String>> = (payload ?: emptyMap()).entries.sortedBy { it.key }.mapNotNull { (key, value) ->
        val text: String? = when {
            value is JsonPrimitive && value.isString -> {
                val string = value.content
                if (Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(string)) {
                    if (string.length > 10) ClientsText.dayTime(string) else ClientsText.shortDay(string)
                } else string
            }
            value is JsonPrimitive && value.bool != null -> if (value.bool == true) L("Yes") else L("No")
            value is JsonPrimitive && !value.isNull -> value.content
            value is JsonArray -> value.mapNotNull { it.string }.joinToString(", ")
            else -> null
        }
        val shown = text.nonEmpty ?: return@mapNotNull null
        val label = key.replace("_", " ").replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        L(label.take(1).uppercase() + label.drop(1)) to shown
    }
    if (rows.isEmpty()) return
    ClientsGrid(items = rows, minimum = 150.dp, spacing = 6.dp) { _, row ->
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.first.uppercase(), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.1), color = Theme.inkSoft)
            Text(row.second, style = Theme.text(14), color = Theme.inkStrong)
        }
    }
}

/**
 * A record's files as square tiles (web `AttachmentTiles`): three across, each the kit's
 * `AttachmentTile` with its name inside; a photo shows itself.
 */
@Composable
fun ClientsAttachmentTiles(files: List<ClientsAttachment>, modifier: Modifier = Modifier) {
    val openURL = rememberOpenURL()
    val shown = files.mapNotNull { file -> APIConfig.assetURL(file.url)?.let { file to it } }
    if (shown.isEmpty()) return
    ClientsGrid(items = shown, columns = 3, spacing = 8.dp, modifier = modifier) { _, (file, url) ->
        AttachmentTile(
            fileName = file.fileName ?: L("File"),
            isImage = ClientsFileKind.isImage(mimeType = file.mimeType, name = file.fileName),
            isPDF = file.mimeType == "application/pdf",
            caption = false,
            action = { openURL(url) },
        ) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(Theme.surfaceMuted),
            )
        }
    }
}

/** Whether a file is a photo (web `isImageFile`). */
object ClientsFileKind {
    fun isImage(mimeType: String?, name: String?): Boolean {
        if (mimeType?.startsWith("image/") == true) return true
        return Regex("""\.(jpe?g|png|webp|gif|heic)$""", RegexOption.IGNORE_CASE).containsMatchIn(name ?: "")
    }

    /** "120 KB", "1.4 MB" (web `fileSizeLabel`). */
    fun size(bytes: Int?): String? {
        if (bytes == null || bytes <= 0) return null
        if (bytes < 1024 * 1024) return "${max(1, (bytes.toDouble() / 1024).roundToInt())} KB"
        val formatter = NumberFormat.getNumberInstance(appLocale).apply { maximumFractionDigits = 1 }
        return "${formatter.format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}

// MARK: - A tab's parts

/** A tab's heading (web `h3 font-heading text-lg`), with an optional action at its right. */
@Composable
private fun ClientsTabHeading(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Theme.heading(18), color = Theme.inkStrong, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Rows in one bordered box with hairlines between them (web `divide-y rounded-box border`). */
@Composable
private fun <Item> ClientsRowBox(items: List<Item>, id: (Item) -> Any, onTap: ((Item) -> Unit)? = null, row: @Composable (Item) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Theme.boxRadius)).clientsChartBox()) {
        items.forEachIndexed { index, item ->
            androidx.compose.runtime.key(id(item)) {
                if (index > 0) RowDivider()
                // A row that opens a page (a `NavigationLink` around the row) takes the tap on its whole box.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (onTap != null) Modifier.plainButton { onTap(item) } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) { row(item) }
            }
        }
    }
}

// MARK: - Appointments

@Composable
fun ClientsChartAppointments(chart: ClientsChart, onBook: (() -> Unit)?, modifier: Modifier = Modifier) {
    var items by remember { mutableStateOf<List<ClientsAppointment>?>(null) }
    LaunchedEffect(Unit) {
        val page: Page<ClientsAppointment>? = try {
            API.get(
                "/provider-appointments",
                mapOf("limit" to "100", "offset" to "0", "providerClientId" to chart.client.id, "providerClientPetId" to chart.pet.id, "order" to "desc"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        items = page?.items ?: emptyList()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val all = items
        if (all != null) {
            val now = DateText.iso(Instant.now())
            val active = setOf("scheduled", "checked_in", "in_progress", "requested")
            val upcoming = all.filter { (it.startsAt ?: "") >= now && active.contains(it.status ?: "") }.sortedBy { it.startsAt ?: "" }
            val past = all.filter { item -> upcoming.none { it.id == item.id } }
            ClientsTabHeading(L("Upcoming")) {
                if (onBook != null) PMButton(L("Book"), systemImage = "calendar-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onBook)
            }
            if (upcoming.isEmpty()) EmptyState(systemImage = "calendar-plus", title = L("Nothing booked.")) else AppointmentList(upcoming)
            ClientsTabHeading(L("Past"))
            if (past.isEmpty()) EmptyState(systemImage = "calendar-clock", title = L("No past appointments.")) else AppointmentList(past)
            if (all.size >= 100) Text(L("Showing the latest 100. The Schedule has the rest."), style = Theme.text(12), color = Theme.inkSoft)
        } else {
            LoadingState()
        }
    }
}

@Composable
private fun AppointmentList(entries: List<ClientsAppointment>) {
    val nav = LocalNavigator.current
    ClientsRowBox(entries, id = { it.id }, onTap = { nav.push(ProviderRoute.appointment(it.id)) }) { item ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.serviceLabel ?: "—", style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(ClientsText.dayTime(item.startsAt), item.assignedMember?.name).mapNotNull { it.nonEmpty }.joinToString(" · "),
                    style = Theme.text(12), color = Theme.inkSoft,
                )
            }
            Text(ClientsText.visitStatus(item.status), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
        }
    }
}

// MARK: - Follow-ups

@Composable
fun ClientsChartFollowUps(chart: ClientsChart, modifier: Modifier = Modifier) {
    var items by remember { mutableStateOf<List<ClientsFollowUp>?>(null) }
    LaunchedEffect(Unit) {
        val page: Page<ClientsFollowUp>? = try {
            API.get("/provider-follow-ups", mapOf("limit" to "50", "offset" to "0", "status" to "all", "providerClientPetId" to chart.pet.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        items = page?.items ?: emptyList()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val all = items
        if (all != null) {
            if (all.isEmpty()) {
                EmptyState(systemImage = "bell-ring", title = L("No follow-ups for this pet."))
            } else {
                val openStates = setOf("open", "contacted", "snoozed", "scheduled")
                val open = all.filter { openStates.contains(it.status ?: "") }
                val closed = all.filter { !openStates.contains(it.status ?: "") }
                ClientsTabHeading(L("To do"))
                if (open.isEmpty()) EmptyState(systemImage = "bell-ring", title = L("Nothing to do.")) else FollowUpList(open, late = true)
                if (closed.isNotEmpty()) {
                    ClientsTabHeading(L("Done or closed"))
                    FollowUpList(closed, late = false)
                }
            }
        } else {
            LoadingState()
        }
    }
}

@Composable
private fun FollowUpList(entries: List<ClientsFollowUp>, late: Boolean) {
    ClientsRowBox(entries, id = { it.id }) { item ->
        val isLate = late && ClientsText.isPast(item.dueAt)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ClientsText.followUpTitle(item.title), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${ClientsText.followUpType(item.followUpType)} · ${item.assignedMember?.name ?: L("Unassigned")}",
                    style = Theme.text(12), color = Theme.inkSoft,
                )
            }
            Text(
                "${ClientsText.shortDay(item.dueAt)} · ${ClientsText.followUpStatus(item.status)}",
                style = Theme.text(12, FontWeight.Bold), color = if (isLate) Theme.destructive else Theme.inkSoft,
            )
        }
    }
}

// MARK: - Files

@Serializable
private data class ClientsChartFile(
    val id: String,
    val fileName: String? = null,
    val url: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Int? = null,
    val createdAt: String? = null,
    val source: String? = null,
    val record: ClientsFileRecord? = null,
    val visit: ClientsFileVisit? = null,
)

@Serializable
private data class ClientsFileRecord(
    val id: String? = null,
    val type: String? = null,
    val title: String? = null,
    val at: String? = null,
    val draft: Boolean? = null,
)

@Serializable
private data class ClientsFileVisit(
    val id: String? = null,
    val serviceLabel: String? = null,
)

@Serializable
private data class ClientsFilesAnswer(
    val items: List<ClientsChartFile>,
    val truncated: Boolean? = null,
)

/** Every file of the pet in one gallery (web `ChartFiles`): lab PDFs, x-rays and photos from records, the family's, and visit report photos. */
@Composable
fun ClientsChartFiles(chart: ClientsChart, modifier: Modifier = Modifier) {
    var answer by remember { mutableStateOf<ClientsFilesAnswer?>(null) }
    var failed by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("all") }

    suspend fun load() {
        try {
            answer = API.get("/provider-clients/${chart.client.id}/pets/${chart.pet.id}/files")
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(Unit) { load() }

    val current = answer
    Box(modifier) {
        if (current != null) {
            if (current.items.isEmpty()) {
                EmptyState(systemImage = "file", title = L("No files yet"), message = L("Lab PDFs, x-rays and photos attached to records show up here."))
            } else {
                val shown = current.items.filter { file ->
                    val image = chartFileIsImage(file)
                    when (kind) {
                        "all" -> true
                        "images" -> image
                        else -> !image
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val show = L("Show")
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 2.dp)
                            .semantics { contentDescription = show },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(title = LF("All (%d)", current.items.size), isSelected = kind == "all") { kind = "all" }
                        FilterChip(title = L("Photos"), isSelected = kind == "images") { kind = "images" }
                        FilterChip(title = L("Documents"), isSelected = kind == "documents") { kind = "documents" }
                    }
                    if (shown.isEmpty()) {
                        EmptyState(systemImage = if (kind == "images") "image" else "file-text", title = L("Nothing of this kind."))
                    }
                    ClientsGrid(items = shown, minimum = 150.dp, spacing = 12.dp) { _, file -> FileTile(file) }
                    if (current.truncated == true) Text(L("Showing the newest 300 files."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        } else if (failed) {
            ErrorState(message = L("The files could not be loaded.")) { load() }
        } else {
            LoadingState()
        }
    }
}

private fun chartFileIsImage(file: ClientsChartFile): Boolean =
    file.source == "report" || ClientsFileKind.isImage(mimeType = file.mimeType, name = file.fileName)

private fun chartFileCaption(file: ClientsChartFile): String? {
    if (file.source == "report") return file.visit?.serviceLabel ?: L("Visit report")
    val record = file.record ?: return null
    return listOfNotNull(record.type?.let { ClientsText.recordType(it) }, record.title.nonEmpty).joinToString(" · ").nonEmpty
}

@Composable
private fun FileTile(file: ClientsChartFile) {
    val openURL = rememberOpenURL()
    val shape = RoundedCornerShape(Theme.boxRadius)
    val url = APIConfig.assetURL(file.url)
    val name = file.fileName ?: L("File")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.surface)
            .border(1.dp, Theme.borderSoft, shape)
            .then(if (url != null) Modifier.plainButton(label = LF("Open %@", name)) { openURL(url) } else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.ui.graphics.RectangleShape)) {
            if (chartFileIsImage(file) && url != null) {
                AsyncImage(
                    model = url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Theme.surfaceMuted),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Theme.wash(Theme.aqua, 10.0)), contentAlignment = Alignment.Center) {
                    Glyph(if (file.mimeType == "application/pdf") "file-text" else "file", 40.dp, tint = Theme.inkSoft)
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            chartFileCaption(file)?.let { Text(it, style = brandText(10.9), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(
                    when (file.source) {
                        "family" -> "house"
                        "report" -> "scissors"
                        else -> "image"
                    },
                    12.dp, tint = Theme.inkSoft,
                )
                Text(
                    listOfNotNull(
                        ClientsText.shortDay(file.record?.at ?: file.createdAt),
                        ClientsFileKind.size(file.fileSizeBytes),
                        if (file.source == "family") L("Family") else null,
                        if (file.record?.draft == true) L("To sign") else null,
                    ).joinToString(" · "),
                    style = brandText(10.6), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// MARK: - Billing

@Composable
fun ClientsChartBilling(chart: ClientsChart, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    var bills by remember { mutableStateOf<List<ClientsInvoice>?>(null) }
    var collectingOnAccount by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val page: ClientsInvoicePage? = try {
            API.get("/provider-invoices", mapOf("limit" to "50", "offset" to "0", "providerClientId" to chart.client.id, "providerClientPetId" to chart.pet.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        bills = page?.items ?: emptyList()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val balance = chart.activity?.balance
        if (balance != null) {
            val owes = (balance.client ?: 0) > 0
            val canCollect = owes && (workspace.can(Permission.salesSell) || (workspace.can(Permission.billingRecordPayment) && workspace.moneyLevel >= MoneyLevel.charging))
            FlowLayout(
                spacing = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clientsChartBox(Theme.wash(Theme.pink, 8.0))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(LF("%@ owes", chart.client.name).uppercase(), style = Theme.text(12, FontWeight.ExtraBold).tracking(1.4), color = Theme.inkSoft)
                    Text(Money.format(balance.client, currency = balance.currency), style = Theme.heading(24), color = Theme.inkStrong)
                    val pet = balance.pet
                    if (pet != null && pet > 0 && pet != balance.client) {
                        Text(LF("%@ of it for %@", Money.format(pet, currency = balance.currency), chart.pet.name), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                if (canCollect) {
                    // The Store's checkout collects a client's open bills; without the Store, the client's account takes the payment.
                    PMButton(L("Collect"), systemImage = "circle-dollar-sign") {
                        if (workspace.can(Permission.salesSell)) nav.push(ProviderRoute.storeCheckout(StorePrefill(clientId = chart.client.id, petId = chart.pet.id)))
                        else collectingOnAccount = true
                    }
                }
            }
        }
        val list = bills
        if (list != null) {
            if (list.isEmpty()) {
                EmptyState(systemImage = "circle-dollar-sign", title = L("No bills for this pet."))
            } else {
                ClientsRowBox(list, id = { it.id }, onTap = { nav.push(ProviderRoute.invoice(it.id)) }) { invoice -> InvoiceRow(invoice) }
            }
        } else {
            LoadingState()
        }
    }
    if (collectingOnAccount) {
        ClientsChartPushedPage(onDismissRequest = { collectingOnAccount = false }) { CashAccountScreen(clientId = chart.client.id) }
    }
}

@Composable
private fun InvoiceRow(invoice: ClientsInvoice) {
    val toPay = (invoice.balanceDueAmount ?: 0) > 0 && invoice.status != "draft" && invoice.status != "canceled"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(invoice.invoiceNumber ?: "—", style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${ClientsText.shortDay(invoice.issuedAt ?: invoice.createdAt)} · ${ClientsText.invoiceStatus(invoice.status)}",
                style = Theme.text(12), color = Theme.inkSoft,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Money.format(invoice.totalAmount, currency = invoice.currency), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
            if (toPay) {
                Text(LF("%@ to pay", Money.format(invoice.balanceDueAmount, currency = invoice.currency)), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
        }
    }
}

// MARK: - Details

@Composable
fun ClientsChartDetails(chart: ClientsChart, onEdit: (() -> Unit)?, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val pet = chart.pet
    val rows: List<Pair<String, String?>> = listOf(
        L("Species") to ClientsText.species(pet.species),
        L("Breed") to pet.breed,
        L("Sex") to ClientsText.sex(pet.sex),
        L("Born") to pet.birthDate?.let { ClientsText.shortDay(it) },
        L("Neutered") to pet.isNeutered?.let { if (it) L("Yes") else L("No") },
        L("Microchip") to pet.microchipNumber,
        L("Weight") to ClientsText.weight(pet.weightGrams),
        L("Profile") to (if (pet.linked == true) L("Kept by the family in PawMiaw") else L("Kept by the clinic")),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ClientsTabHeading(L("Pet details")) {
            if (onEdit != null) {
                PMButton(
                    if (pet.linked == true) L("Edit clinic details") else L("Edit pet"),
                    systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onEdit,
                )
            }
        }
        Column(Modifier.fillMaxWidth().clientsChartBox().padding(horizontal = 12.dp, vertical = 4.dp)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                FactRow(label = row.first, value = row.second, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        pet.notes.nonEmpty?.let { notes ->
            Column(
                Modifier.fillMaxWidth().clientsChartBox().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(L("Notes").uppercase(), style = Theme.text(12, FontWeight.ExtraBold).tracking(1.4), color = Theme.inkSoft)
                Text(notes, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
        ClientsTabHeading(L("Care card"))
        SvcCareCard(petId = pet.id, petName = pet.name, canEdit = workspace.can(Permission.petsEdit) || workspace.can(Permission.appointmentsComplete))
        Row(
            Modifier.plainButton { nav.push(ProviderRoute.client(chart.client.id)) },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("users-round", 16.dp, tint = Theme.blue)
            Text(LF("Open %@", chart.client.name), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            Glyph("external-link", 12.dp, tint = Theme.blue)
        }
        AuditHistoryCard(entityType = "provider_client_pet", entityId = pet.id, olfatoPet = pet.id to pet.name)
    }
}

// MARK: - Weigh the pet

@Composable
fun ClientsWeightSheet(chart: ClientsChart, onSaved: suspend () -> Unit, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val kilograms = remember { mutableStateOf("") }
    val day = remember { mutableStateOf(Instant.now()) }
    val notes = remember { mutableStateOf("") }
    val shared = remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val grams = ClientsText.grams(fromKilograms = kilograms.value)
    /** A big jump from the last weight asks for a second look. */
    val change: Double? = run {
        val last = chart.weights?.latestGrams
        if (grams == null || last == null || last <= 0) return@run null
        val value = (grams.toDouble() - last) / last
        if (kotlin.math.abs(value) >= 0.1) value else null
    }

    fun save() {
        if (!form.check(listOf("weight" to (if (grams == null) L("Write the weight in kilograms, e.g. 12.4") else null)))) return
        val weight = grams ?: return
        saving = true
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>(
                    "weightGrams" to weight,
                    "recordedDate" to DateText.dayKey(day.value, ZoneId.systemDefault()),
                    "notes" to notes.value.nonEmpty,
                )
                if (chart.pet.linked == true) body["isSharedWithOwner"] = shared.value
                API.post<EmptyResponse>("/provider-clients/${chart.client.id}/pets/${chart.pet.id}/weights", body = body)
                Toast.show(LF("%@ weighs %@.", chart.pet.name, ClientsText.weight(weight.toDouble()) ?: ""))
                announceDataChanged()
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? APIError)?.code == "future_date") form.check(listOf("day" to L("The day can’t be in the future.")))
                else form.apply(e, known = mapOf("weightGrams" to "weight", "recordedDate" to "day"))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = LF("Weigh %@", chart.pet.name),
        subtitle = chart.weights?.latestGrams?.let { LF("Last weight: %@", ClientsText.weight(it) ?: "—") } ?: L("No weight yet"),
        systemImage = "scale", accent = ModalAccent.aqua,
        isDirty = kilograms.value.isNotEmpty() || notes.value.isNotEmpty(), form = form,
        primaryTitle = if (saving) L("Saving…") else L("Save weight"),
        isWorking = saving, primaryIcon = "scale",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        PMTextField(
            L("Weight (kg)"), text = kilograms, key = "weight", form = form, prompt = "12.4",
            hint = change?.let { LF("That is %d%% from the last weight — check it’s right.", (it * 100).roundToInt()) },
            keyboard = KeyboardType.Decimal, required = true,
        )
        DateTimeField(
            label = L("Day"), date = day, zone = ZoneId.systemDefault(), max = Instant.now(),
            components = setOf(DatePickerComponent.date), key = "day", form = form,
        )
        PMTextField(L("Note (optional)"), text = notes, prompt = L("e.g. Fasting, before surgery"))
        if (chart.pet.linked == true) {
            ToggleRow(title = L("Show it to the family in PawMiaw"), isOn = shared)
        }
    }
}
