package com.pawmiaw.business.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

//
//  HospitalScreen.kt (iOS HospitalScreen.swift)
//
//  The hospital (web provider.hospital.tsx + hospital-board.tsx): one place's
//  ward. The module header with its numbers (patients in of the beds, waiting
//  for admission, doses needing someone, rounds to sign) and "Admit a
//  patient", the page tabs, then the board (waiting patients and what they
//  wait on, patients in by ward or cage, who left lately), who is on call, and
//  the hospital's rules. A missed dose stays on the board as one notice per
//  patient until it is marked (backend docs/hospital-sheet-and-notices.md).
//

@Composable
fun HospitalScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val vetCare = workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)
    providerPage(L("Hospital"), root = true, modifier = modifier) {
        if (workspace.current != null && !(workspace.can(Permission.hospitalView) && vetCare)) {
            PageScroll {
                NoAccessState(
                    message = if (vetCare) L("Ask the workspace owner to give your role access to the hospital.")
                    else L("The hospital is for businesses that offer veterinary care."),
                )
            }
        } else {
            RequireLocation { HospWardPage() }
        }
    }
}

private enum class HospWardTab { board, onCall, settings }

@Composable
private fun HospWardPage() {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    var board by remember { mutableStateOf<HospBoard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val tab = remember { mutableStateOf(HospWardTab.board) }
    var admitting by remember { mutableStateOf(false) }

    val canManage = workspace.can(Permission.hospitalManage)

    suspend fun load() {
        try {
            board = API.get("/provider-hospital/board", mapOf("locationId" to workspace.locationId))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (board == null) error = errorText(e)
        }
    }

    val tabs = buildList {
        add(ToneTabs.Tab(HospWardTab.board, L("Ward"), icon = "bed-double"))
        add(ToneTabs.Tab(HospWardTab.onCall, L("On call"), icon = "phone-call"))
        if (canManage) add(ToneTabs.Tab(HospWardTab.settings, L("Settings"), icon = "settings-2"))
    }

    PageScroll(maxWidth = 1100.dp, refresh = { load() }) {
        HospWardHeader(board, canManage) { admitting = true }
        ToneTabs(tabs = tabs, selection = tab, look = ToneTabs.Look.solid)
        when (tab.value) {
            HospWardTab.board -> {
                val loaded = board
                val failed = error
                if (loaded != null) {
                    HospBoardView(board = loaded, zone = workspace.timeZone, canManage = canManage, canSetUpRooms = workspace.can(Permission.workspaceEdit)) { admitting = true }
                } else if (failed != null) {
                    ErrorState(message = failed) { load() }
                } else {
                    LoadingState()
                }
            }
            HospWardTab.onCall -> HospOnCallPanel(canManage = canManage)
            HospWardTab.settings -> HospSettingsPanel(canEdit = canManage)
        }
    }
    ReloadsOnScope { load() }
    HospEveryMinute { load() }
    if (admitting) {
        AppSheet(onDismissRequest = { admitting = false }) {
            HospAdmitSheet { opened ->
                // iOS `.navigationDestination(item: $opened)`: the patient's page, the estimate to sign first.
                nav.push(ProviderRoute.page("hospitalization:${opened.id}:${opened.signConsentId ?: ""}") {
                    HospDetailPage(id = opened.id, signFirst = opened.signConsentId, admitAfterSigning = opened.admitAfterSigning)
                })
            }
        }
    }
}

// MARK: Header

@Composable
private fun HospWardHeader(board: HospBoard?, canManage: Boolean, onAdmit: () -> Unit) {
    val workspace = Workspace.shared
    val subtitle = if (workspace.multiLocation) workspace.selectedLocation?.name else null
    val counts = board?.counts
    val beds = (board?.rooms ?: emptyList()).fold(0) { sum, room -> sum + (room.capacity ?: 0) }
    val admitted = counts?.admitted ?: 0
    val missed = counts?.missed ?: 0
    val late = counts?.late ?: 0
    val needing = (counts?.due ?: 0) + late + missed
    val stats = listOf(
        ModuleStat(
            "in", label = L("In the hospital"), value = if (beds > 0) "$admitted / $beds" else "$admitted",
            hint = if (beds > 0) LP(maxOf(0, beds - admitted), "1 place free", "%d places free") else null,
            icon = "bed-double", accent = Theme.pink,
        ),
        ModuleStat(
            "pending", label = L("Waiting for admission"), value = "${counts?.pending ?: 0}",
            icon = "circle-dashed", accent = Theme.mango, attention = (counts?.pending ?: 0) > 0,
        ),
        ModuleStat(
            "doses", label = L("Doses needing someone"), value = "$needing",
            hint = if (missed > 0) LP(missed, "1 missed", "%d missed") else if (late > 0) LP(late, "1 late", "%d late") else null,
            icon = "syringe", accent = Theme.aqua, attention = missed + late > 0,
        ),
        ModuleStat(
            "rounds", label = L("Rounds to sign today"), value = "${counts?.roundsPending ?: 0}",
            icon = "shield-question-mark", accent = Theme.lime,
        ),
    )
    ModuleHeader(
        icon = "bed-double",
        title = L("Hospital"),
        subtitle = subtitle ?: L("Patients in, their treatment sheet and who is on call"),
        accent = Theme.pink,
        stats = stats,
        storageKey = "hospital",
    ) {
        if (canManage) {
            PMButton(L("Admit a patient"), systemImage = "plus") { onAdmit() }
        }
    }
}

// MARK: - The board

@Composable
private fun HospBoardView(board: HospBoard, zone: ZoneId, canManage: Boolean, canSetUpRooms: Boolean, onAdmit: () -> Unit) {
    val nav = LocalNavigator.current
    val patients = board.patients ?: emptyList()
    val rooms = board.rooms ?: emptyList()
    val pending = patients.filter { it.status == "pending" }
    val admitted = patients.filter { it.status == "admitted" }
    val unplaced = admitted.filter { patient -> patient.room == null || rooms.none { it.id == patient.room.id } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // One notice per patient while a dose is missed: it goes once each is marked.
        val missing = admitted.filter { (it.attention?.m ?: 0) > 0 }
        if (missing.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                missing.forEach { patient ->
                    val missed = patient.attention?.m ?: 0
                    val name = patient.patient?.name.nonEmpty ?: L("A patient")
                    NoticeCard(
                        tone = NoticeCard.Tone.problem,
                        title = if (missed == 1) LF("Missed treatment: %@", name) else LF("%d treatments not marked: %@", missed, name),
                        message = if (missed == 1) L("Mark it or note why it wasn’t given.") else L("Mark them or note why they weren’t given."),
                        modifier = Modifier.plainButton { nav.push(ProviderRoute.hospitalization(patient.id)) },
                    )
                }
            }
        }

        if (pending.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HospGroupTitle(title = L("Waiting for admission"), systemImage = "circle-dashed", count = pending.size, tone = Theme.mango)
                HospGrid(pending, minimum = 300.dp, spacing = 12.dp) { patient ->
                    HospPendingCard(patient, Modifier.plainButton { nav.push(ProviderRoute.hospitalization(patient.id)) })
                }
            }
        }

        if (admitted.isEmpty() && rooms.isEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    systemImage = "bed-double", title = L("No patients in the hospital"),
                    message = L("Admit one from a visit or with “Admit a patient”. Add wards and cages in Settings → Rooms."),
                    actionTitle = if (canManage) L("Admit a patient") else null, action = if (canManage) onAdmit else null,
                    accent = Theme.pink, panel = true,
                )
                if (canSetUpRooms) {
                    PMButtonStyle(variant = ButtonVariant.outline, size = ButtonSize.compact, onClick = { nav.push(ProviderRoute.section(ProviderSection.rooms)) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("settings-2", 16.dp)
                            Text(L("Add wards and cages"))
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HospGroupTitle(title = L("In the hospital"), systemImage = "bed-double", count = admitted.size, tone = Theme.pink)
                rooms.forEach { room ->
                    HospWardGroup(room = room, patients = admitted.filter { it.room?.id == room.id }, zone = zone)
                }
                if (unplaced.isNotEmpty()) {
                    HospWardGroup(room = null, patients = unplaced, zone = zone)
                }
            }
        }

        val recent = board.recent
        if (!recent.isNullOrEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HospGroupTitle(title = L("Recently left"), systemImage = "circle-check", count = recent.size, tone = ProviderState.done.color)
                Card(padding = 0.dp, spacing = 0.dp) {
                    recent.forEachIndexed { index, patient ->
                        if (index > 0) RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .plainButton { nav.push(ProviderRoute.hospitalization(patient.id)) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PetAvatar(
                                name = patient.patient?.name, species = patient.patient?.species, photoURL = patient.patient?.photoUrl, size = 32.dp,
                                modifier = Modifier.hospGrayscale(patient.status == "deceased"),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    patient.patient?.name.nonEmpty ?: L("Patient"),
                                    style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                val line = listOfNotNull(patient.client?.name, patient.endedAt?.let { HospFmt.dateTime(it, zone) })
                                    .mapNotNull { it.nonEmpty }.joinToString(" · ")
                                if (line.isNotEmpty()) Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            HospStatusChip(status = patient.status)
                            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A ward or cage (web `WardGroup`): the page's card, its name and how full it
 * is, the patients in it on a muted band.
 */
@Composable
private fun HospWardGroup(room: HospWardRoom?, patients: List<HospSummary>, zone: ZoneId) {
    val nav = LocalNavigator.current
    SectionCard(
        title = room?.name ?: L("Not placed in a ward"),
        trailing = {
            if (room != null) {
                val occupied = room.occupied ?: 0
                val capacity = room.capacity ?: 0
                val over = occupied > capacity
                val full = occupied >= capacity
                val tone = if (over) Theme.destructive else if (full) Theme.mango else ProviderState.done.color
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (room.kind == "cage") L("Cage") else L("Ward")).uppercase(),
                        style = Theme.text(11.2, FontWeight.ExtraBold).tracking(11.2 * 0.1),
                        color = Theme.inkSoft,
                    )
                    Row(
                        Modifier
                            .background(Theme.wash(tone, if (over) 14.0 else 22.0), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(tone, CircleShape))
                        Text(
                            LF("%d of %d", occupied, capacity),
                            style = Theme.text(12, FontWeight.ExtraBold).monospacedDigit(),
                            color = if (over) Theme.destructive else Theme.inkStrong,
                        )
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Theme.mix(Theme.surfaceMuted, 45.0, Theme.surface))
                .padding(12.dp),
        ) {
            if (patients.isEmpty()) {
                // A free bed, dashed.
                val shape = RoundedCornerShape(Theme.boxRadius)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surface, shape)
                        .dashedBorder(1.5.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 4.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("bed-double", 16.dp, tint = Theme.inkSoft)
                    Text(L("Empty"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            } else {
                HospGrid(patients, minimum = 290.dp, spacing = 12.dp) { patient ->
                    HospPatientCard(patient = patient, zone = zone, modifier = Modifier.plainButton { nav.push(ProviderRoute.hospitalization(patient.id)) })
                }
            }
        }
    }
}

/**
 * One card for every patient on the board (web `CARD`): a box with a
 * 1.5 pt border and a 4 pt left edge in the colour of what it needs.
 */
@Composable
private fun Modifier.hospBoardBox(edge: Color, waiting: Boolean = false): Modifier {
    val shape = RoundedCornerShape(Theme.boxRadius)
    val fill = if (waiting) Theme.wash(Theme.mango, 6.0) else Theme.surface
    val dashed = Theme.mix(Theme.mango, 60.0, Theme.borderSoft)
    val line = Theme.borderSoft
    return this
        .fillMaxWidth()
        .softShadow(shape, Theme.navy.copy(alpha = 0.08f), radius = 1.5.dp, y = 1.dp)
        .clip(shape)
        .background(fill, shape)
        .then(if (waiting) Modifier.dashedBorder(1.5.dp, dashed, Theme.boxRadius, on = 6.dp, off = 4.dp) else Modifier.border(1.5.dp, line, shape))
        .drawWithContent {
            drawContent()
            drawRect(edge, size = Size(4.dp.toPx(), size.height))
        }
        .padding(16.dp)
}

/** The pet, who it belongs to and where it sleeps. */
@Composable
private fun HospCardHead(patient: HospSummary, place: String?, aside: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        PetAvatar(name = patient.patient?.name, species = patient.patient?.species, photoURL = patient.patient?.photoUrl, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(patient.patient?.name.nonEmpty ?: L("Patient"), style = Theme.heading(20), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(patient.client?.name, place).mapNotNull { it.nonEmpty }.joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        aside()
    }
}

/** A patient in: what it is, how it is, the next dose and the last vitals; the edge says what needs someone. */
@Composable
private fun HospPatientCard(patient: HospSummary, zone: ZoneId, modifier: Modifier = Modifier) {
    val a = patient.attention
    val edge = when {
        (a?.m ?: 0) > 0 -> Theme.destructive
        (a?.l ?: 0) > 0 -> Theme.mango
        (a?.d ?: 0) > 0 -> Theme.aqua
        else -> Theme.borderSoft
    }
    val vitalsLine = patient.lastVitals?.let { v ->
        listOfNotNull(
            v.temperatureC?.let { "${HospFmt.number(it)} °C" },
            v.heartRate?.let { LF("HR %@", HospFmt.number(it)) },
            v.respiratoryRate?.let { LF("RR %@", HospFmt.number(it)) },
            v.painScore?.let { LF("Pain %@", HospFmt.number(it)) },
        ).joinToString(" · ")
    } ?: ""

    Column(modifier.hospBoardBox(edge), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HospCardHead(patient = patient, place = patient.cageLabel?.let { LF("Cage %@", it) }) {
            val day = patient.dayNumber
            if (day != null) {
                Column(
                    Modifier
                        .padding(start = 4.dp)
                        .background(Theme.wash(Theme.pink, 16.0), RoundedCornerShape(Theme.chipRadius))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(L("Day").uppercase(), style = Theme.text(9.6, FontWeight.ExtraBold).tracking(0.96), color = Theme.inkSoft)
                    Text("$day", style = Theme.heading(20).monospacedDigit(), color = Theme.inkStrong)
                }
            }
        }
        FlowLayout(spacing = 6.dp) {
            patient.triagePriority?.let { HospTriageBadge(priority = it) }
            patient.condition?.let { HospConditionBadge(condition = it) }
            HospAllergyChip(allergies = patient.patient?.allergies ?: emptyList())
            HospAttentionChips(attention = patient.attention)
            HospRoundsChip(signed = patient.roundsSignedToday == true)
        }
        patient.reason.nonEmpty?.let { reason ->
            Text(reason, style = Theme.text(14), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth()) {
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HospFootLine(icon = "stethoscope", text = patient.attending?.name ?: L("No attending vet"), modifier = Modifier.weight(1f))
                    HospFootLine(
                        icon = "clock-3",
                        text = patient.nextDueAt?.let { LF("Next %@", HospFmt.clock(it, zone)) } ?: L("Nothing scheduled"),
                        strong = patient.nextDueAt != null,
                        modifier = Modifier.weight(1f),
                    )
                }
                val vitals = patient.lastVitals
                if (vitals != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        HospFootLine(icon = "thermometer", text = vitalsLine, modifier = Modifier.weight(1f))
                        Text(HospFmt.clock(vitals.recordedAt, zone), Modifier.padding(start = 4.dp), style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft)
                    }
                } else {
                    HospFootLine(icon = "heart-pulse", text = L("No vitals yet"))
                }
            }
        }
    }
}

/** One line of a card's foot: a 14 icon and 12 words. */
@Composable
private fun HospFootLine(icon: String, text: String, strong: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (strong) Theme.inkStrong else Theme.inkSoft
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 14.dp, tint = color)
        Text(text, style = Theme.text(12, if (strong) FontWeight.Bold else FontWeight.Normal), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A patient waiting for admission, with what it waits on ticked off. */
@Composable
private fun HospPendingCard(patient: HospSummary, modifier: Modifier = Modifier) {
    val money = patient.currency
    val gate = patient.gate
    val estimate = patient.estimateAmount ?: 0
    val deposit = patient.depositAmount ?: 0
    Column(modifier.hospBoardBox(Theme.mango, waiting = true), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HospCardHead(patient = patient, place = patient.room?.name) {}
        if (patient.triagePriority != null || !(patient.patient?.allergies ?: emptyList()).isEmpty()) {
            FlowLayout(spacing = 6.dp) {
                patient.triagePriority?.let { HospTriageBadge(priority = it) }
                HospAllergyChip(allergies = patient.patient?.allergies ?: emptyList())
            }
        }
        patient.reason.nonEmpty?.let { reason ->
            Text(reason, style = Theme.text(14), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.mix(Theme.mango, 35.0, Theme.borderSoft)))
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HospGateLine(
                    ok = gate?.let { it.consent?.satisfied ?: false } ?: (estimate <= 0),
                    loading = gate == null && estimate > 0,
                    systemImage = "shield-check",
                    text = when {
                        estimate <= 0 -> L("No estimate to sign")
                        gate?.consent?.satisfied == true -> L("Estimate signed")
                        gate?.consent?.reason == "amount_changed" -> L("The estimate went up: sign again")
                        else -> L("Estimate not signed yet")
                    },
                )
                HospGateLine(
                    ok = gate?.let { it.deposit?.satisfied ?: false } ?: (deposit <= 0),
                    loading = gate == null && deposit > 0,
                    systemImage = "piggy-bank",
                    text = when {
                        deposit <= 0 -> L("No deposit")
                        gate?.deposit?.waivedByTriage == true -> L("Deposit waived: critical emergency")
                        gate != null -> LF("Deposit %@ of %@", Money.format(gate.deposit?.paid ?: 0, money), Money.format(gate.deposit?.amount ?: 0, money))
                        else -> LF("Deposit %@", Money.format(deposit, money))
                    },
                )
                if (gate?.ready == true) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("bed-double", 16.dp, tint = Theme.blue)
                        Text(L("Ready to admit"), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.blue)
                        Spacer(Modifier.weight(1f))
                        Glyph("chevron-right", 16.dp, tint = Theme.blue)
                    }
                }
            }
        }
    }
}

/** One thing admission waits on, ticked when done (web `GateLine`). */
@Composable
fun HospGateLine(
    ok: Boolean,
    loading: Boolean = false,
    systemImage: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val disc = when {
            loading -> Theme.surfaceMuted
            ok -> Theme.wash(ProviderState.done.color, 18.0)
            else -> Theme.wash(Theme.mango, 28.0)
        }
        Box(Modifier.size(20.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) {
            if (loading) {
                HospMiniSpinner(tint = Theme.inkSoft)
            } else {
                Glyph(if (ok) "check" else systemImage, 12.dp, tint = if (ok) ProviderState.done.color else Theme.inkStrong)
            }
        }
        Text(text, style = Theme.text(12, FontWeight.Bold), color = if (loading) Theme.inkSoft else Theme.inkStrong)
    }
}
