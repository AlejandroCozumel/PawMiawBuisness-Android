package com.pawmiaw.business.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import com.pawmiaw.business.hospital.*
import com.pawmiaw.business.services.*
import com.pawmiaw.business.stays.*
import com.pawmiaw.business.stock.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.time.Instant

//
//  SchedVisitCards.kt (iOS SchedVisitCards.swift)
//
//  The visit page's side cards (web provider.appointments.$appointmentId.tsx):
//  the triage (hospital/visit-triage-card.tsx), the chart in short with "+"
//  to record into the visit (visits/visit-chart.tsx), the groom, walk or
//  training report that saves itself (care/visit-report.tsx), the notes to
//  the family during the visit (visit-updates-card.tsx), the prescriptions
//  written at it (prescriptions/prescriptions-card.tsx), the history of
//  changes (audit-history-panel.tsx) and "Add to calendar".
//

/** Swift's `try?` for a read: null on any failure, cancellation passed on. */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

/** A number in the app's locale (Swift `.formatted(.number.locale(.app))`). */
private fun appNumber(value: Double, maxFraction: Int = 3): String =
    NumberFormat.getNumberInstance(LocalizationManager.locale).apply { minimumFractionDigits = 0; maximumFractionDigits = maxFraction }.format(value)

/** A photo from the API, cropped to fill, the muted ground until it loads (Swift `AsyncImage` + `scaledToFill`). */
@Composable
private fun AssetPhoto(url: String?, modifier: Modifier, description: String? = null) {
    Box(modifier.background(Theme.surfaceMuted)) {
        val asset = APIConfig.assetURL(url)
        if (asset != null) {
            AsyncImage(model = asset, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

// MARK: - Triage

/**
 * Priority, why, the quick vitals at arrival, after hours and the emergency deposit still owed.
 * Editable by whoever checks visits in; "Hospitalize" one tap away when it is urgent or critical.
 */
@Composable
fun SchedTriageCard(
    appointmentId: String,
    canEdit: Boolean,
    onHospitalize: ((SchedTriage?) -> Unit)? = null,
    /** The pet's open hospitalization (waiting or in): the card opens it instead. */
    hospitalization: SchedHospStay? = null,
    onTakeDeposit: ((SchedDepositDue) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    var triage by remember { mutableStateOf<SchedTriage?>(null) }
    var editing by remember { mutableStateOf(false) }

    ReloadsOnScope {
        orNull { API.get<SchedTriage>("/provider-visits/$appointmentId/triage") }?.let { triage = it }
    }

    fun vitals(v: SchedVitals): List<String> {
        fun n(value: Double?): String? = value?.let { appNumber(it) }
        return listOfNotNull(
            n(v.temperatureC)?.let { "$it °C" },
            n(v.heartRate)?.let { LF("HR %@", it) },
            n(v.respiratoryRate)?.let { LF("RR %@", it) },
            n(v.painScore)?.let { LF("Pain %@", it) },
            v.weightGrams?.let { "${appNumber(it / 1000)} kg" },
            v.mucousMembranes?.let { HospWords.mucous(it) },
            n(v.crtSeconds)?.let { LF("CRT %@ s", it) },
        )
    }

    val shown = triage
    if (shown != null && !(shown.isEmpty && !canEdit && onHospitalize == null && hospitalization == null)) {
        SectionCard(
            title = L("Triage"), icon = "siren", accent = Theme.destructive, modifier = modifier,
            trailing = {
                if (canEdit && !shown.isEmpty) {
                    PMButton(L("Change"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.header) { editing = true }
                }
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown.isEmpty) {
                    Text(L("Not triaged"), style = Theme.smallFont, color = Theme.inkSoft)
                    if (canEdit) {
                        PMButton(L("Triage"), systemImage = "siren", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true) { editing = true }
                    }
                } else {
                    val priority = shown.triagePriority
                    if (priority != null || shown.afterHours == true) {
                        FlowLayout(spacing = 6.dp) {
                            if (priority != null) HospTriageBadge(priority = priority)
                            if (shown.afterHours == true) Chip(text = L("After hours"), icon = "moon-star")
                        }
                    }
                    shown.triageReason.nonEmpty?.let { reason ->
                        Text(
                            reason, style = Theme.text(15, FontWeight.SemiBold), color = Theme.inkStrong,
                            modifier = Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(10.dp),
                        )
                    }
                    shown.vitals?.firstOrNull()?.let { latest ->
                        val values = vitals(latest)
                        if (values.isNotEmpty()) {
                            Text("${L("Quick vitals")} · ${DateText.time(latest.recordedAt)}", style = Theme.captionFont, color = Theme.inkSoft)
                            FlowLayout(spacing = 6.dp) { values.forEach { Chip(text = it, tone = Chip.Tone.info) } }
                        }
                    }
                    val due = shown.depositDue
                    if (due != null && due.owed > 0) {
                        NoticeCard(
                            tone = NoticeCard.Tone.warning,
                            title = LF("Emergency deposit owed: %@", Money.format(due.owed, due.currency)),
                            actionTitle = if (onTakeDeposit != null) L("Take the deposit") else null,
                            action = onTakeDeposit?.let { take -> { take(due) } },
                        )
                    }
                    if (onHospitalize != null && hospitalization == null && (priority == "critical" || priority == "urgent")) {
                        PMButton(L("Hospitalize"), systemImage = "bed-double", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true) { onHospitalize(shown) }
                    }
                }
                if (hospitalization != null) {
                    Row(
                        Modifier.fillMaxWidth().plainButton { nav.push(ProviderRoute.hospitalization(hospitalization.id)) },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("bed-double", 16.dp, tint = Theme.blue)
                        Text(
                            if (hospitalization.status == "pending") L("Waiting for admission to the hospital") else L("In the hospital"),
                            style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f),
                        )
                        Text(L("Open"), style = Theme.text(13, FontWeight.Bold), color = Theme.blue)
                        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                    }
                }
            }
        }
    }
    if (editing) {
        AppSheet(onDismissRequest = { editing = false }) {
            triage?.let { current -> SchedTriageSheet(appointmentId = appointmentId, triage = current) { triage = it } }
        }
    }
}

/** Critical, urgent or stable: the waiting room goes by it. A critical patient never waits for a deposit. */
@Composable
private fun SchedTriageSheet(appointmentId: String, triage: SchedTriage, onSaved: (SchedTriage) -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(SchedTriageDraft(on = true, priority = triage.triagePriority, reason = triage.triageReason ?: "")) }
    var invalid by remember { mutableStateOf<SchedTriageDraft.Missing?>(null) }
    var working by remember { mutableStateOf(false) }
    val vitalInvalid = (invalid as? SchedTriageDraft.Missing.vital)?.field

    fun save(clear: Boolean) {
        var body: Map<String, Any?> = mapOf("priority" to null, "reason" to null, "vitals" to null)
        if (!clear) {
            val (value, missing) = draft.parsed()
            if (missing != null) {
                invalid = missing
                Toast.error(SchedTriageDraft.missingText(missing))
                return
            }
            body = value ?: body
        }
        val payload = body
        working = true
        scope.launch {
            try {
                val saved: SchedTriage = API.put("/provider-visits/$appointmentId/triage", payload)
                onSaved(saved)
                Toast.show(if (clear) L("Triage cleared.") else L("Triage saved. The waiting room goes by it."))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e, fallback = L("Could not save the triage."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Triage"),
        subtitle = L("Critical, urgent or stable: the waiting room goes by it. A critical patient never waits for a deposit."),
        systemImage = "siren", accent = ModalAccent.pink,
        isDirty = draft.priority != triage.triagePriority || draft.reason != (triage.triageReason ?: "") || draft.vitals != HospVitalsDraft(),
        primaryTitle = L("Save triage"), isWorking = working,
        secondaryActions = if (triage.triagePriority != null) listOf(ModalAction(L("Clear the triage"), variant = ButtonVariant.ghost, isWorking = working) { save(clear = true) }) else emptyList(),
        primaryIcon = "save",
        onPrimary = { save(clear = false) },
    ) {
        // The web's triage sheet: the priority, why (required), then the quick vitals, always open.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillPicker(
                options = HospWords.priorities.map { it to HospWords.triage(it) },
                choice = stateOf({ draft.priority }) { draft = draft.copy(priority = it, on = true); invalid = null },
            )
            if (invalid == SchedTriageDraft.Missing.priority) {
                Text(SchedTriageDraft.missingText(SchedTriageDraft.Missing.priority), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PMTextField(
                L("Why"),
                text = stateOf({ draft.reason }) { draft = draft.copy(reason = it); invalid = null },
                prompt = L("Why they came, e.g. Hit by a car, breathing fast"), required = true,
            )
            if (invalid == SchedTriageDraft.Missing.reason) {
                Text(SchedTriageDraft.missingText(SchedTriageDraft.Missing.reason), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
        }
        ModalSection(title = L("Quick vitals"), subtitle = L("A new reading; earlier ones stay on the chart.")) {
            HospVitalsFields(
                draft = stateOf({ draft.vitals }) { if (it != draft.vitals) { draft = draft.copy(vitals = it); invalid = null } },
                invalid = vitalInvalid,
            )
        }
    }
}

// MARK: - The chart in short

private data class SchedAllergyLine(
    val id: String,
    val title: String,
    val detail: String?,
    val family: Boolean,
)

/**
 * What the vet needs in front of them: allergies first, then current problems and medicines,
 * vaccines, the last visits and the weight; "+" records into this visit.
 */
@Composable
fun SchedVisitChart(
    appointmentId: String,
    petId: String,
    petWeightGrams: Double?,
    canLogWeight: Boolean,
    /** Record into this visit: "allergy", "diagnosis", "medication", "vaccination". */
    onAdd: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var chart by remember { mutableStateOf<SchedChartSummary?>(null) }
    var failed by remember { mutableStateOf(false) }
    val weight = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    ReloadsOnScope {
        try {
            chart = API.get("/provider-client-pets/$petId/chart-summary")
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = chart == null
        }
    }

    fun severity(value: String?): String? = when (value) {
        "severe" -> L("Severe")
        "moderate" -> L("Moderate")
        "mild" -> L("Mild")
        else -> null
    }

    fun nextDose(record: SchedChartRecord): String? =
        record.followUpAt?.take(10) ?: record.text("next_due_date")?.take(10)

    fun saveWeight() {
        val kg = weight.value.trim().replace(",", ".").toDoubleOrNull()
        if (kg == null || kg <= 0 || kg > 200) {
            Toast.error(L("Enter the weight in kg, like 12.4"))
            return
        }
        saving = true
        scope.launch {
            try {
                API.post<EmptyResponse>(
                    "/provider-appointments/$appointmentId/weight",
                    mapOf("weightGrams" to Math.round(kg * 1000).toInt(), "recordedAt" to DateText.iso(Instant.now()), "isSharedWithOwner" to true),
                )
                weight.value = ""
                Toast.show(L("Weight saved."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                saving = false
            }
        }
    }

    @Composable
    fun AddButton(type: String, label: String) {
        if (onAdd != null) IconCircleButton(systemImage = "plus", label = label) { onAdd(type) }
    }

    @Composable
    fun RowLabel(icon: String, title: String, count: Int, alert: Boolean, summary: String?, modifier: Modifier = Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, 14.dp, tint = if (alert) Theme.destructive else Theme.inkSoft)
            Text(title, style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
            Spacer(Modifier.weight(1f).width(4.dp))
            Text(
                summary ?: if (count > 0) count.toString() else "—",
                style = Theme.text(13, FontWeight.Bold),
                color = if (alert) Theme.destructive else if (count > 0) Theme.inkStrong else Theme.inkSoft,
            )
        }
    }

    @Composable
    fun ChartRow(
        icon: String, title: String, count: Int, alert: Boolean = false, summary: String? = null, add: Pair<String, String>? = null,
        items: @Composable ColumnScope.() -> Unit,
    ) {
        Column(Modifier.fillMaxWidth()) {
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (count > 0) {
                    Disclosure(label = { RowLabel(icon, title, count, alert, summary, Modifier.weight(1f)) }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = items)
                    }
                } else {
                    RowLabel(icon, title, count, alert, summary, Modifier.weight(1f))
                }
                if (add != null) AddButton(add.first, add.second)
            }
        }
    }

    val loaded = chart
    if (loaded == null) {
        if (!failed) {
            SectionCard(title = L("Chart"), icon = "file-text", accent = Theme.blue, modifier = modifier) { LoadingState() }
        }
        return
    }

    val sections = loaded.sections
    val familyAllergies = (sections?.ownerRecords ?: emptyList()).filter { it.recordType == "allergy" }
    val clinic = (sections?.allergies ?: emptyList()).map { r ->
        SchedAllergyLine(
            id = r.id, title = r.title ?: L("Allergy"),
            detail = listOf(severity(r.text("severity")), r.summary ?: r.text("reaction")).mapNotNull { it.nonEmpty }.joinToString(" · ").nonEmpty,
            family = false,
        )
    }
    val allergies = clinic + familyAllergies.map { SchedAllergyLine(id = it.id, title = it.title ?: L("Allergy"), detail = it.summary, family = true) }
    val today = SchedDay.today
    val vaccines = (sections?.vaccines ?: emptyList()).take(4)
    val overdue = vaccines.any { record -> nextDose(record)?.let { it < today } ?: false }
    val visits = (sections?.recentRecords ?: emptyList())
        .filter { it.appointmentId != appointmentId && listOf("vet_visit", "diagnosis", "surgery").contains(it.recordType ?: "") }
        .take(3)

    SectionCard(
        title = L("Chart"), icon = "file-text", accent = Theme.blue, modifier = modifier,
        trailing = {
            Row(
                Modifier.plainButton { nav.push(ProviderRoute.patientChart(petId)) },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Full chart"), style = Theme.text(13, FontWeight.Bold), color = Theme.blue)
                Glyph("arrow-right", 14.dp, tint = Theme.blue)
            }
        },
    ) {
        // Allergies never fold away: red and listed when there are any.
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (allergies.isEmpty()) Color.Transparent else Theme.wash(Theme.destructive, 12.0))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Glyph("triangle-alert", 16.dp, tint = if (allergies.isEmpty()) Theme.inkSoft else Theme.destructive, modifier = Modifier.padding(top = 1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(L("Allergies"), style = Theme.text(15, FontWeight.Bold), color = if (allergies.isEmpty()) Theme.inkStrong else Theme.destructive)
                if (allergies.isEmpty()) {
                    Text(L("None on record."), style = Theme.captionFont, color = Theme.inkSoft)
                }
                allergies.forEach { allergy ->
                    Text(
                        "${allergy.title}${allergy.detail?.let { " · $it" } ?: ""}${if (allergy.family) " (${L("from the family")})" else ""}",
                        style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive,
                    )
                }
            }
            AddButton("allergy", L("Add an allergy"))
        }
        Column(Modifier.fillMaxWidth()) {
            val problems = sections?.currentProblems ?: emptyList()
            if (problems.isNotEmpty() || onAdd != null) {
                ChartRow("heart-pulse", L("Current problems"), count = problems.size, add = "diagnosis" to L("Add a diagnosis")) {
                    problems.take(5).forEach { Text(it.title ?: "—", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong) }
                }
            }
            val medicines = sections?.currentMedications ?: emptyList()
            ChartRow("pill", L("Current medicines"), count = medicines.size, add = "medication" to L("Add a medicine")) {
                medicines.take(6).forEach { record ->
                    Text("${record.title ?: "—"}${record.text("dosage")?.let { " · $it" } ?: ""}", style = Theme.text(14), color = Theme.inkStrong)
                }
            }
            ChartRow("syringe", L("Vaccines"), count = (sections?.vaccines ?: emptyList()).size, alert = overdue, add = "vaccination" to L("Add a vaccine")) {
                vaccines.forEach { record ->
                    val next = nextDose(record)
                    val late = next?.let { it < today } ?: false
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(record.title ?: "—", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            next?.let { if (late) LF("due %@", SchedDay.shortLabel(it)) else LF("next %@", SchedDay.shortLabel(it)) } ?: DateText.date(record.eventAt),
                            style = Theme.captionFont, color = if (late) Theme.destructive else Theme.inkSoft,
                        )
                    }
                }
            }
            ChartRow("stethoscope", L("Last visits"), count = visits.size, summary = if (visits.isEmpty()) L("First visit here.") else null) {
                visits.forEach { record ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${record.title ?: "—"} · ${DateText.date(record.eventAt)}", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        record.summary.nonEmpty?.let { Text(it, style = Theme.captionFont, color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            // The weight, and today's one logged into the visit.
            val latest = loaded.highlights?.latestWeight
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("scale", 14.dp, tint = Theme.inkSoft)
                    Text(L("Weight"), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                    Spacer(Modifier.weight(1f))
                    val grams = petWeightGrams
                    if (grams != null && grams > 0) {
                        Text(
                            "${appNumber(grams / 1000, 2)} kg${latest?.let { " · ${DateText.date(it.eventAt)}" } ?: ""}",
                            style = Theme.text(13, FontWeight.Bold), color = Theme.inkStrong,
                        )
                    } else {
                        Text(L("Not weighed yet."), style = Theme.text(13, FontWeight.SemiBold), color = Theme.inkSoft)
                    }
                }
                if (canLogWeight) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FieldChrome(modifier = Modifier.weight(1f)) {
                            PlainTextField(weight, L("Today, in kg"), Modifier.weight(1f), keyboard = KeyboardType.Decimal, style = Theme.bodyFont)
                        }
                        PMButton(L("Save"), variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = saving, enabled = weight.value.nonEmpty != null) { saveWeight() }
                    }
                }
            }
            if (loaded.sharedHistoryAccess?.canView == false) {
                RowDivider()
                Text(L("The family’s own records are not shared with you."), style = Theme.captionFont, color = Theme.inkSoft, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

/** SwiftUI's `DisclosureGroup`: the label with a chevron that turns down, the content under it once open. */
@Composable
private fun Disclosure(label: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(200), label = "disclosure chevron")
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().plainButton { open = !open },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            label()
            Glyph("chevron-right", 14.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open) { content() }
    }
}

// MARK: - The report

private enum class ReportSaveState { idle, dirty, saving, saved, error }

/**
 * What the groomer, walker or trainer tells the family about the visit. It saves itself; the family
 * sees it when the visit ends. Its missing fields go to the page's closing card.
 */
@Composable
fun SchedVisitReportCard(
    appointment: SchedAppointment,
    canWrite: Boolean,
    modifier: Modifier = Modifier,
    onMissing: (List<String>) -> Unit = {},
) {
    var report by remember { mutableStateOf<SvcReport?>(null) }
    var state by remember { mutableStateOf(ReportSaveState.idle) }
    var edits by remember { mutableIntStateOf(0) }
    var prefilled by remember { mutableStateOf(false) }
    var adopted by remember { mutableStateOf<String?>(null) }

    val arrived = listOf("in_progress", "completed").contains(appointment.status)
    val editable = canWrite && arrived
    val kind = appointment.reportKind ?: "groom"

    /** A report saved elsewhere replaces ours while nothing here is unsaved. */
    fun adopt() {
        if (!(state == ReportSaveState.idle || state == ReportSaveState.saved)) return
        val key = "${appointment.id}|${appointment.reportUpdatedAt ?: ""}"
        if (adopted == key && report != null) return
        adopted = key
        val next = appointment.report ?: SvcReport(kind = kind)
        report = next
        onMissing(SvcReportText.missing(appointment.serviceCategory, next))
    }

    /** No report yet: start from what the owner asked for (saved only once someone changes it). */
    suspend fun prefill() {
        if (!(kind == "groom" && appointment.report == null && editable && !prefilled)) return
        val view = orNull { API.get<SchedGroomView>("/provider-appointments/${appointment.id}/groom-request") }
        val start = view?.prefill?.copy(kind = "groom") ?: return
        if (state != ReportSaveState.idle || appointment.report != null) return
        report = start
        prefilled = true
        onMissing(SvcReportText.missing(appointment.serviceCategory, start))
    }

    fun change(next: SvcReport) {
        if (next == report) return
        report = next
        state = ReportSaveState.dirty
        edits += 1
        onMissing(SvcReportText.missing(appointment.serviceCategory, next))
    }

    suspend fun save() {
        val current = report ?: return
        state = ReportSaveState.saving
        try {
            API.putJson<SvcIgnored, SvcReport>("/provider-appointments/${appointment.id}/report", current)
            if (state == ReportSaveState.saving) state = ReportSaveState.saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state = ReportSaveState.error
            Toast.error(e)
        }
    }

    LaunchedEffect(appointment.id, appointment.reportUpdatedAt) { adopt() }
    LaunchedEffect(edits) {
        if (state != ReportSaveState.dirty) return@LaunchedEffect
        delay(1000)
        if (state != ReportSaveState.dirty) return@LaunchedEffect
        save()
    }
    LaunchedEffect(appointment.id) { prefill() }

    val badge: String? = when (state) {
        ReportSaveState.saving -> L("Saving…")
        ReportSaveState.saved -> L("Saved")
        ReportSaveState.dirty -> L("Unsaved")
        ReportSaveState.error -> L("Not saved")
        ReportSaveState.idle -> if (appointment.report != null) L("For the family") else null
    }

    SectionCard(
        title = SvcReportText.title(kind, appointment.serviceCategory), icon = "clipboard-check", accent = Theme.blue, modifier = modifier,
        trailing = { if (badge != null) Text(badge, style = Theme.text(12, FontWeight.Bold), color = if (state == ReportSaveState.error) Theme.destructive else Theme.inkSoft) },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!arrived) {
                Text(L("The report opens when the visit starts."), style = Theme.text(14), color = Theme.inkSoft)
            }
            if (prefilled && appointment.report == null) {
                Row(
                    Modifier.background(Theme.wash(Theme.aqua, 22.0), RoundedCornerShape(Theme.chipRadius)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("sparkles", 14.dp, tint = Theme.inkStrong)
                    Text(L("Filled in from what the owner asked for"), style = Theme.captionFont, color = Theme.inkStrong)
                }
            }
            report?.let { current ->
                SvcReportForm(
                    report = stateOf({ report ?: current }) { change(it) },
                    category = appointment.serviceCategory, appointmentId = appointment.id, editable = editable,
                )
            }
            Text(
                if (appointment.clientHouseholdId != null) L("The family sees this when the visit ends.")
                else LF("%@ isn’t on PawMiaw — you can send the report by WhatsApp when the visit ends.", NameText.firstName(appointment.clientName)),
                style = Theme.captionFont, color = Theme.inkSoft,
            )
        }
    }
}

// MARK: - Notes to the family

/** A photo, a mood, a line from the visit: on the family's Home under "Right now", with a notification. */
@Composable
fun SchedVisitUpdatesCard(appointment: SchedAppointment, canSend: Boolean, modifier: Modifier = Modifier) {
    val open = rememberOpenURL()
    var updates by remember { mutableStateOf<List<SchedVisitUpdate>>(emptyList()) }
    var composing by remember { mutableStateOf(false) }

    val linked = appointment.clientHouseholdId != null
    val happened = !appointment.isGone

    ReloadsOnScope {
        orNull { API.get<List<SchedVisitUpdate>>("/provider-appointments/${appointment.id}/updates") }?.let { updates = it }
    }

    if (linked || (canSend && happened) || updates.isNotEmpty()) {
        SectionCard(
            title = L("Notes to the family"),
            subtitle = if (linked) L("Send the family a note from this visit — a photo, a mood, a line. It shows on their Home in PawMiaw, with a notification.") else null,
            icon = "message-square-heart", accent = Theme.pink, modifier = modifier,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (linked && canSend && happened) {
                    PMButton(L("Send an update"), systemImage = "send", size = ButtonSize.compact) { composing = true }
                }
                if (!linked && canSend && happened) {
                    NoticeCard(tone = NoticeCard.Tone.info, title = LF("%@ isn’t on PawMiaw", appointment.clientName), message = L("Send them a note by WhatsApp instead."))
                    val url = schedWhatsAppURL(appointment.clientPhone, LF("Hi %@, a quick note about %@: ", NameText.firstName(appointment.clientName), appointment.petName))
                    if (url != null) {
                        PMButton("WhatsApp", systemImage = "message-circle", variant = ButtonVariant.outline, size = ButtonSize.compact) { open(url.toString()) }
                    }
                }
                if (linked && updates.isEmpty()) {
                    Text(L("No notes sent from this visit yet."), style = Theme.text(14), color = Theme.inkSoft)
                }
                updates.forEach { update ->
                    val shape = RoundedCornerShape(Theme.boxRadius)
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, shape).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (APIConfig.assetURL(update.photoUrl) != null) {
                            AssetPhoto(update.photoUrl, Modifier.size(60.dp).clip(RoundedCornerShape(Theme.chipRadius)))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(update.message ?: "", style = Theme.text(14), color = Theme.inkStrong)
                            Text(
                                listOf(StaysWords.mood(update.mood)?.let { "${it.emoji} ${it.label}" }, update.authorName, DateText.dayTime(update.createdAt))
                                    .mapNotNull { it.nonEmpty }.joinToString(" · "),
                                style = Theme.captionFont, color = Theme.inkSoft,
                            )
                        }
                    }
                }
            }
        }
    }
    if (composing) {
        AppSheet(onDismissRequest = { composing = false }) { SchedVisitUpdateSheet(appointmentId = appointment.id, petName = appointment.petName) }
    }
}

@Composable
private fun SchedVisitUpdateSheet(appointmentId: String, petName: String) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var mood by remember { mutableStateOf<String?>(null) }
    val message = remember { mutableStateOf("") }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    var working by remember { mutableStateOf(false) }

    fun send() {
        if (!form.check(listOf("message" to if (message.value.nonEmpty == null) L("Write a line for the family.") else null))) return
        val fields = mutableListOf("message" to message.value.trim())
        mood?.let { fields.add("mood" to it) }
        val files = photo.value.data?.let { listOf(API.UploadFile(field = "photo", fileName = "update.jpg", mimeType = "image/jpeg", data = it)) } ?: emptyList()
        val parts = fields.toList()
        working = true
        scope.launch {
            try {
                API.multipart<SchedVisitUpdate>("/provider-appointments/$appointmentId/updates", fields = parts, files = files)
                Toast.show(LF("Sent to %@’s family.", petName))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e, mapOf("message" to "message"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Update for the family"), subtitle = L("Goes to the family in their PawMiaw app, with a notification."),
        systemImage = "send", accent = ModalAccent.aqua,
        isDirty = message.value.isNotEmpty() || photo.value.data != null || mood != null, form = form,
        primaryTitle = L("Send to the family"), isWorking = working, primaryIcon = "send",
        onPrimary = { send() },
    ) {
        ModalSection(title = LF("How is %@ doing?", petName)) {
            // Five tiles, an emoji over its word (web visit-updates-card).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StaysWords.moods.forEach { option ->
                    val chosen = mood == option.value
                    val shape = RoundedCornerShape(Theme.boxRadius)
                    Column(
                        Modifier
                            .weight(1f)
                            .background(if (chosen) Theme.wash(Theme.lime, 30.0) else Theme.surface, shape)
                            .border(2.dp, if (chosen) Theme.blue else Theme.borderSoft, shape)
                            .semantics { selected = chosen }
                            .plainButton { mood = if (chosen) null else option.value }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(option.emoji, fontSize = 24.sp)
                        FittedText(
                            StaysWords.mood(option.value)?.label ?: option.label,
                            style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft, minimumScaleFactor = 0.7f,
                        )
                    }
                }
            }
        }
        ModalSection(title = L("A line for the family")) {
            SchedBareField(prompt = LF("e.g. Bath done, drying now — %@ is being a star", petName), text = message, key = "message", form = form)
        }
        PhotoField(label = L("Photo"), hint = L("Families love a photo mid-visit."), draft = photo, allowsRemove = false)
    }
}

// MARK: - Prescriptions written at the visit

@Composable
fun SchedVisitPrescriptions(appointment: SchedAppointment, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<StockPrescription>?>(null) }
    var writing by remember { mutableStateOf(false) }
    // The prescription's page (iOS `navigationDestination(item:)`; no route opens one, so it opens as a page in a sheet).
    var opened by remember { mutableStateOf<String?>(null) }

    val rights = StockRxRights(Workspace.shared)

    suspend fun load() {
        if (!rights.canRead) return
        orNull { API.get<StockPrescriptionPage>("/provider-prescriptions", mapOf("appointmentId" to appointment.id, "limit" to "50")) }?.let { items = it.items }
    }

    ReloadsOnScope { load() }

    if (rights.canRead) {
        SectionCard(
            title = L("Prescriptions"), subtitle = L("Written at this visit"), icon = "pill", accent = Theme.blue, modifier = modifier,
            trailing = {
                if (rights.canWrite && !(items ?: emptyList()).isEmpty()) {
                    PMButton(L("Prescribe"), systemImage = "plus", size = ButtonSize.header) { writing = true }
                }
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = items
                if (list != null) {
                    if (list.isEmpty()) {
                        Text(L("No prescriptions yet."), style = Theme.text(14), color = Theme.inkSoft)
                        if (rights.canWrite) PMButton(L("Prescribe"), systemImage = "plus", size = ButtonSize.compact) { writing = true }
                    }
                    list.forEach { prescription ->
                        Box(Modifier.plainButton { opened = prescription.id }) { StockPrescriptionRow(prescription = prescription) }
                    }
                } else {
                    LoadingState()
                }
            }
        }
    }
    if (writing) {
        AppSheet(onDismissRequest = { writing = false }) {
            StockPrescriptionSheet(
                pet = StockRxPet(
                    clientId = appointment.providerClientId, petId = appointment.providerClientPetId, petName = appointment.petName,
                    species = appointment.petSpecies, photoUrl = appointment.petPhotoUrl, clientName = appointment.clientName,
                ),
                draft = null, appointmentId = appointment.id,
                onDone = { saved ->
                    scope.launch {
                        load()
                        delay(450)
                        opened = saved.id
                    }
                },
            )
        }
    }
    opened?.let { id ->
        AppSheet(onDismissRequest = { opened = null }) {
            Box(Modifier.sheetPresentation(setOf(PresentationDetent.large))) { StockPrescriptionScreen(prescriptionId = id) }
        }
    }
}

// MARK: - Add to calendar

/** The visit as an .ics from the server, handed to the share sheet (no calendar permission asked). */
@Composable
fun SchedCalendarSheet(appointment: SchedAppointment, modifier: Modifier = Modifier) {
    var file by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            val name = "${appointment.petName}-${SchedDay.key(appointment.startsAt) ?: "visit"}"
                .map { if ("/\\:?%*|\"<> ".contains(it)) '-' else it }.joinToString("")
            file = API.download("/provider-appointments/${appointment.id}/calendar.ics", fileName = name, ext = "ics")
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = errorText(e)
        }
    }

    LaunchedEffect(Unit) { load() }

    ProviderModal(
        title = L("Add to calendar"), subtitle = "${appointment.petName} · ${DateText.dayTime(appointment.start)}",
        systemImage = "calendar-plus", accent = ModalAccent.aqua, cancelTitle = L("Close"),
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium)),
    ) {
        val ready = file
        val problem = failed
        if (ready != null) {
            Text(L("Open it with your calendar app, or send it to whoever takes the visit."), style = Theme.smallFont, color = Theme.inkSoft)
            ShareLink(item = ready, preview = "${appointment.petName} · ${appointment.serviceLabel}") {
                PMButtonStyle(fullWidth = true) { PMButtonContent(L("Add to calendar"), icon = "download") }
            }
        } else if (problem != null) {
            ErrorState(message = problem) { load() }
        } else {
            LoadingState()
        }
    }
}
