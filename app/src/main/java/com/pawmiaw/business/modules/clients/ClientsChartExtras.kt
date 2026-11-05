package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.hospital.*
import com.pawmiaw.business.services.*
import com.pawmiaw.business.stock.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

//
//  ClientsChartExtras.kt (iOS ClientsChartExtras.swift)
//
//  The smaller cards of a pet's chart and a client's page (web:
//  prescriptions/prescriptions-card.tsx, hospital/patient-hospital-card.tsx,
//  consents/consent-document.tsx, care/report-view.tsx, care/walk-route-
//  drawing.tsx, stays/client-stays-section.tsx): the pet's prescriptions,
//  its hospitalizations, a consent to read and print, a visit's full report
//  and the client's stays.
//

// MARK: - Prescriptions

/** The latest five prescriptions of the pet (web `PrescriptionsCard` compact); each opens in the Pharmacy's page. */
@Composable
fun ClientsPrescriptionsCard(chart: ClientsChart, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    var items by remember { mutableStateOf<List<StockPrescription>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    /** The prescription's own page (iOS `NavigationLink` / `.navigationDestination(item:)`), pushed. */
    fun open(target: StockRxTarget) {
        val id = target.id
        nav.push(ProviderRoute.page("rx:$id") { StockPrescriptionScreen(prescriptionId = id) })
    }

    suspend fun load() {
        try {
            val page: StockPrescriptionPage = API.get("/provider-prescriptions", mapOf("providerClientPetId" to chart.pet.id, "limit" to "5"))
            items = page.items
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    val rights = StockRxRights(workspace)
    if (!rights.canRead) return
    ReloadsOnScope { load() }
    SectionCard(
        title = L("Prescriptions"),
        subtitle = L("Signed, printable, and in the family’s app"),
        icon = "pill",
        accent = Theme.lime,
        modifier = modifier,
        trailing = {
            // Empty, the button waits in the empty state; once there is one, "another" sits up here.
            if (rights.canWrite && !(items ?: emptyList()).isEmpty()) {
                PMButton(L("Prescribe"), systemImage = "plus", size = ButtonSize.compact) { writing = true }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            val list = items
            if (list != null) {
                if (list.isEmpty()) {
                    EmptyState(
                        systemImage = "pill", title = L("No prescriptions yet."),
                        actionTitle = if (rights.canWrite) L("Prescribe") else null, action = { writing = true },
                        accent = Theme.mango,
                    )
                } else {
                    list.forEach { prescription ->
                        Box(Modifier.plainButton { open(StockRxTarget(id = prescription.id)) }) { PrescriptionRow(prescription) }
                    }
                }
            } else if (failed) {
                ErrorState(message = L("Something went wrong. Try again.")) { load() }
            } else {
                LoadingState()
            }
        }
    }
    if (writing) {
        AppSheet(onDismissRequest = { writing = false }) {
            StockPrescriptionSheet(
                pet = StockRxPet(
                    clientId = chart.client.id, petId = chart.pet.id, petName = chart.pet.name,
                    species = chart.pet.species, photoUrl = chart.pet.photoUrl, clientName = chart.client.name,
                ),
                draft = null,
            ) { saved ->
                scope.launch { load() }
                open(StockRxTarget(id = saved.id))
            }
        }
    }
}

@Composable
private fun PrescriptionRow(p: StockPrescription) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.medicines.nonEmpty ?: "—", style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(p.folio, p.prescriberName, ClientsMatches.fullDay(p.signedAt ?: p.createdAt)).mapNotNull { it.nonEmpty }.joinToString(" · "),
                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (p.controlled == true) StockControlledChip()
        StatusChip(text = StockRxWords.state(p.state), state = StockRxWords.stateState(p.state))
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

// MARK: - Hospitalizations

@Serializable
private data class ClientsHospitalList(
    val hospitalizations: List<HospSummary>? = null,
)

/**
 * The pet's stays in the hospital (web `PatientHospitalCard`; vets and emergencies only): the current one
 * first, each with its status edge, opening its page. Nothing when it has none.
 */
@Composable
fun ClientsHospitalCard(items: List<HospSummary>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Card(padding = 16.dp, spacing = 8.dp, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("bed-double", 16.dp, tint = Theme.blue)
            Text(L("Hospitalizations"), style = Theme.heading(18), color = Theme.inkStrong)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.take(8).forEach { stay -> HospitalRow(stay) }
        }
    }
}

object ClientsHospitalCard {
    /** Every stay but the cancelled ones (the page reads them with the chart). */
    suspend fun load(petId: String): List<HospSummary> {
        val answer: ClientsHospitalList? = try {
            API.get("/provider-hospital/hospitalizations", mapOf("providerClientPetId" to petId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return (answer?.hospitalizations ?: emptyList()).filter { it.status != "cancelled" }
    }
}

@Composable
private fun HospitalRow(stay: HospSummary) {
    val nav = LocalNavigator.current
    val active = stay.status == "admitted" || stay.status == "pending"
    val shape = RoundedCornerShape(Theme.boxRadius)
    val edge = ProviderState.hospital(stay.status).color
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) Theme.wash(Theme.pink, 8.0) else Theme.surface)
            .clientsLeadingEdge(edge, 4.dp)
            .border(1.5.dp, Theme.borderSoft, shape)
            .plainButton { nav.push(ProviderRoute.hospitalization(stay.id)) }
            .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowLayout(spacing = 6.dp) {
                HospStatusChip(status = stay.status)
                val day = stay.dayNumber
                if (active && day != null) Text(LF("Day %d", day), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                val triage = stay.triagePriority
                if (active && triage != null) HospTriageBadge(priority = triage)
            }
            Text(
                listOfNotNull(ClientsText.dayTime(stay.admittedAt ?: stay.requestedAt), stay.reason.nonEmpty).joinToString(" · "),
                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

// MARK: - A consent to read and print

data class ClientsConsentTarget(
    val id: String,
    val petName: String?,
)

object ClientsConsentWords {
    fun status(status: String?): String = when (status) {
        "draft" -> L("Needs an amount")
        "pending" -> L("Waiting for signature")
        "signed" -> L("Signed@consent")
        "declined" -> L("Declined@consent")
        "cancelled" -> L("Withdrawn")
        "revoked" -> L("Revoked@consent")
        else -> status ?: "—"
    }

    fun state(status: String?): ProviderState = when (status) {
        "signed" -> ProviderState.done
        "pending" -> ProviderState.active
        "draft" -> ProviderState.waiting
        "declined", "revoked" -> ProviderState.problem
        else -> ProviderState.closed
    }

    fun side(side: String?): String = if (side == "family") L("the family") else L("the business")

    /** The line under a consent in the history. */
    fun line(consent: JSONValue): String? {
        val date = { key: String -> ClientsText.dayTime(consent[key].string) }
        val reason = { key: String -> consent[key].string.nonEmpty?.let { " — $it" } ?: "" }
        val name = consent["signerName"].string.nonEmpty ?: "—"
        return when (consent["status"].string) {
            "draft" -> L("Waiting for the amount before it can be signed.")
            "pending" -> {
                if (consent["sentAt"].string.nonEmpty != null) return LF("Sent %@@consent", date("sentAt"))
                val by = if (consent["origin"].string == "auto") " · ${L("required by the service")}"
                else consent["requestedByName"].string.nonEmpty?.let { " · $it" } ?: ""
                LF("Asked %1\$@%2\$@", date("requestedAt"), by)
            }
            "signed" -> when (consent["signedVia"].string) {
                "clinic_device" -> LF("Signed by %1\$@ here · %2\$@", name, date("signedAt"))
                "email_link" -> LF("Signed by %1\$@ through a link · %2\$@", name, date("signedAt"))
                else -> LF("Signed by %1\$@ in the app · %2\$@", name, date("signedAt"))
            }
            "declined" -> LF("Declined %1\$@%2\$@", date("declinedAt"), reason("declineReason"))
            "cancelled" -> LF("Withdrawn %1\$@%2\$@", date("cancelledAt"), reason("cancelReason"))
            "revoked" -> LF("Revoked by %1\$@ %2\$@%3\$@", side(consent["revokedBySide"].string), date("revokedAt"), reason("revokeReason"))
            else -> null
        }
    }
}

// MARK: - A visit's full report

/** A groom, walk or training report as the chart shows it (web `ReportView`): what is worth a look first, the walk's route, the rows, what changed, photos and the note. */
@Composable
fun ClientsReportBlock(report: SvcReport, modifier: Modifier = Modifier) {
    val concerns = SvcReportText.rows(report).any { it.third }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ClientsChartSectionTitle(text = SvcReportText.title(report.kind, category = null))
        if (concerns) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("triangle-alert", 14.dp, tint = Theme.destructive)
                Text(L("Worth a look").uppercase(), style = Theme.text(12, FontWeight.ExtraBold).tracking(1.2), color = Theme.destructive)
            }
        }
        val route = report.route
        if (report.kind == "walk" && route != null) {
            WalkRouteView(route = route)
        }
        SvcReportView(report = report)
        val difference = report.differenceNote.nonEmpty
        if (difference != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.mango, 16.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("shuffle", 14.dp, tint = Theme.inkStrong)
                    Text(L("What changed").uppercase(), style = Theme.text(12, FontWeight.ExtraBold).tracking(1.2), color = Theme.inkStrong)
                }
                Text(difference, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
    }
}

object ClientsReportBlock {
    /** The report of a timeline visit (kept as plain JSON there). */
    fun decode(value: JSONValue?): SvcReport? {
        if (value == null || value.isNull) return null
        return runCatching { API.json.decodeFromJsonElement(SvcReport.serializer(), value) }.getOrNull()
    }
}

// MARK: - The client's stays

@Serializable
data class ClientsStayPet(val petName: String? = null)

@Serializable
data class ClientsStay(
    val id: String,
    val kind: String? = null,
    val status: String? = null,
    val checkInDay: String? = null,
    val checkOutDay: String? = null,
    val unitName: String? = null,
    val pets: List<ClientsStayPet>? = null,
)

@Serializable
private data class ClientsStayList(
    val today: String? = null,
    val items: List<ClientsStay>,
)

/** A client's stays as the page shows them: those on now or still ahead, then the last three. */
data class ClientsStaySplit(
    val active: List<ClientsStay> = emptyList(),
    val past: List<ClientsStay> = emptyList(),
)

/**
 * The client's stays (web `ClientStaysSection`): staying now and coming first, then the last few that
 * ended, each opening its stay. Nothing at all when they have none (the page reads them with the client).
 */
@Composable
fun ClientsStaysSection(stays: ClientsStaySplit, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val active = stays.active
    val past = stays.past
    if (active.isEmpty() && past.isEmpty()) return
    SectionCard(
        title = L("Stays"), icon = "bed-double", accent = Theme.aqua, modifier = modifier,
        trailing = {
            Text(
                L("Open stays"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue,
                modifier = Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.stays)) },
            )
        },
    ) {
        val all = active + past
        Column {
            all.forEachIndexed { index, stay ->
                if (index > 0) RowDivider()
                Box(Modifier.plainButton { nav.push(ProviderRoute.stay(stay.id)) }) { StayRow(stay) }
            }
        }
    }
}

object ClientsStaysSection {
    suspend fun load(clientId: String): ClientsStaySplit {
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays(120).toString()
        val to = today.plusDays(365).toString()
        val list: ClientsStayList = try {
            API.get("/provider-stays", mapOf("from" to from, "to" to to, "clientId" to clientId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ClientsStaySplit()
        }
        val day = list.today ?: DateText.dayKey()
        val isActive = { stay: ClientsStay ->
            stay.status == "checked_in" || ((stay.status == "confirmed" || stay.status == "requested") && (stay.checkOutDay ?: "") >= day)
        }
        return ClientsStaySplit(
            active = list.items.filter(isActive),
            past = list.items.filter { !isActive(it) }.sortedByDescending { it.checkInDay ?: "" }.take(3),
        )
    }
}

@Composable
private fun StayRow(stay: ClientsStay) {
    val names = (stay.pets ?: emptyList()).mapNotNull { it.petName }.joinToString(", ")
    val kind = when (stay.kind) {
        "home_sitting" -> L("Home sitting")
        "daycare" -> L("Daycare")
        else -> L("Boarding")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(Theme.wash(Theme.aqua, 22.0), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(
                when (stay.kind) {
                    "home_sitting" -> "house"
                    "daycare" -> "sun"
                    else -> "bed-double"
                },
                16.dp, tint = Theme.blue,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(names.nonEmpty, kind).joinToString(" · "),
                style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(stayRange(stay), stay.unitName.nonEmpty).joinToString(" · "),
                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        StatusChip(text = stayStatus(stay.status), state = ProviderState.stay(stay.status))
        Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

private fun stayRange(stay: ClientsStay): String {
    val parse = { day: String? ->
        day?.let { runCatching { LocalDate.parse(it.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull() }
    }
    val start: Instant = parse(stay.checkInDay) ?: return "—"
    val end = parse(stay.checkOutDay)
    if (stay.kind == "daycare" || end == null || stay.checkInDay == stay.checkOutDay) {
        return DateText.format(start, "EEEdMMM", ZoneOffset.UTC).capitalizedFirst()
    }
    return "${DateText.format(start, "dMMM", ZoneOffset.UTC)} – ${DateText.format(end, "dMMM", ZoneOffset.UTC)}".capitalizedFirst()
}

private fun stayStatus(status: String?): String = when (status) {
    "requested" -> L("Requested@stay")
    "confirmed" -> L("Confirmed@stay")
    "checked_in" -> L("Staying")
    "checked_out" -> L("Checked out")
    "cancelled" -> L("Cancelled@stay")
    "no_show" -> L("No-show")
    else -> status ?: "—"
}

private fun String.capitalizedFirst(): String = take(1).uppercase() + drop(1)
