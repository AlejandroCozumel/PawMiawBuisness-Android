package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

//
//  StaysHomeStay.kt (iOS StaysHomeStay.swift)
//
//  Home stays — pet sitting at the client's home (web home-stay-sheet.tsx,
//  home-stay-action-sheets.tsx, home-stay-booking-sheet.tsx,
//  home-stay-plan-fields.tsx): one home stay with its status, sitter, money,
//  where it happens, the keys, the visits planned day by day, the pets and
//  their care, the house; confirming (with its deposit), the quote,
//  cancelling and finishing it into a bill. Visits are run from Domicilio.
//  Also the plan form a new home stay and a family's homestay request share.
//

private sealed class StaysHomeSheet {
    data object confirm : StaysHomeSheet()
    data object finish : StaysHomeSheet()
    data object report : StaysHomeSheet()
    data object plan : StaysHomeSheet()
    data object cancel : StaysHomeSheet()
    data object reassign : StaysHomeSheet()
    data object edit : StaysHomeSheet()
    data object keys : StaysHomeSheet()
    data object addVisit : StaysHomeSheet()
    data class visit(val id: String) : StaysHomeSheet()
}

@Serializable
private data class StaysQuoted(val notified: Int? = null)

@Composable
fun StaysHomeStayView(
    stay: StaysStay,
    home: StaysHomeDetail,
    billTotal: Int? = null,
    modifier: Modifier = Modifier,
    reload: suspend () -> Unit,
) {
    val workspace = Workspace.shared
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<StaysHomeSheet?>(null) }
    var showPast by remember { mutableStateOf(false) }
    var quoting by remember { mutableStateOf(false) }
    var showAllKeys by remember { mutableStateOf(false) }
    /** Counts presses of "Record an incident" in the action row; the incidents card opens its sheet. */
    var incidentRequest by remember { mutableIntStateOf(0) }

    val canManage = workspace.can(Permission.staysManage)
    val canCare = workspace.can(Permission.staysCare)
    val started = stay.status == "checked_in" || stay.status == "checked_out"
    val today = home.today?.dayKey ?: StaysDay.today()
    val days: List<Pair<String, List<StaysHomeVisit>>> = (home.visits ?: emptyList())
        .groupBy { it.dayKey }
        .toSortedMap()
        .map { (day, visits) -> Pair(day, visits.sortedBy { it.startsAt }) }

    /** Something to do from the header (the web's "what happens next" row). */
    val hasActions = (stay.isOpen && canManage) || (stay.status == "checked_in" && canCare) || (started && canCare) ||
        (stay.invoiceId != null && workspace.moneyLevel >= MoneyLevel.charging) || (stay.isOpen && workspace.offersHomeVisits)

    fun sendQuote() {
        quoting = true
        scope.launch {
            try {
                val result = API.post<StaysQuoted>("/provider-homestays/${stay.id}/quote", body = emptyMap())
                Toast.show(if ((result.notified ?: 0) > 0) L("Quote sent. The family answers it in the app.") else L("Quote saved. The family isn’t on PawMiaw, so share it with them yourself."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not send the quote."))
            } finally {
                quoting = false
            }
        }
    }

    fun visitTime(visit: StaysHomeVisit): String {
        val start = StaysDay.clockAt(visit.startsAt, zone = home.zone)
        val end = StaysDay.clockAt(visit.endsAt, zone = home.zone)
        val a = DateText.parse(visit.startsAt)
        val b = DateText.parse(visit.endsAt)
        if (visit.kind == "overnight" && a != null && b != null && DateText.dayKey(a, zone = home.zone) != DateText.dayKey(b, zone = home.zone)) {
            return LF("%@ – %@ next morning", start, end)
        }
        return "$start – $end"
    }

    val headerStats = run {
        val units = home.units ?: 0
        val unitText = if (home.priceUnit == "night") LP(units, "%d night", "%d nights") else LP(units, "%d visit", "%d visits")
        listOf(
            ModuleStat(
                id = "status", label = L("Status"), value = StaysWords.homeStatus(stay, quoteSent = home.quoteSentAt != null),
                hint = when {
                    home.quoteAcceptedAt != null -> L("The family accepted the quote")
                    home.quoteSentAt != null && stay.status == "requested" -> L("Waiting for the family")
                    home.mode == "overnight" -> L("Overnight")
                    else -> L("Drop-in visits")
                },
                icon = "circle-dot", accent = ProviderState.stay(stay.status).color,
            ),
            ModuleStat(
                id = "sitter", label = L("Sitter"), value = home.sitter?.name ?: L("Nobody yet"),
                hint = if (stay.isOpen && canManage) (if (home.sitter != null) L("Give to another sitter") else L("Choose a sitter")) else null,
                icon = "user-round-cog", accent = Theme.aqua, attention = home.sitter == null && stay.isOpen,
                action = if (stay.isOpen && canManage) ({ sheet = StaysHomeSheet.reassign }) else null,
            ),
            ModuleStat(
                id = "total", label = if (billTotal != null) L("Bill") else L("Estimated total"),
                value = Money.format(billTotal ?: home.estimatedTotalCents, currency = stay.stayCurrency),
                hint = if ((home.rateCents ?: 0) > 0) "$unitText × ${Money.format(home.rateCents, currency = stay.stayCurrency)}" else "$unitText · ${L("No rate set")}",
                icon = if (billTotal != null) "receipt" else "wallet", accent = Theme.lime,
            ),
        )
    }

    @Composable
    fun VisitRow(visit: StaysHomeVisit) {
        val status = StaysWords.homeVisitStatus(visit.status)
        val shape = RoundedCornerShape(Theme.boxRadius)
        val soft = Theme.inkSoft
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (visit.status == "cancelled") 0.6f else 1f)
                .background(Theme.surface, shape)
                .border(1.dp, Theme.borderSoft, shape)
                .plainButton { sheet = StaysHomeSheet.visit(visit.id) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = if (visit.kind == "overnight") "moon-star" else if (visit.kind == "meet_greet") "key-round" else "house",
                accent = Theme.aqua, size = 36.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 22.0,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(visitTime(visit)) }
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = soft)) { append(" · " + StaysWords.homeVisitKind(visit.kind)) }
                    },
                    style = Theme.text(14), color = Theme.inkStrong,
                )
                FlowLayout(spacing = 6.dp) {
                    Text(visit.sitter?.name ?: L("No sitter yet"), style = Theme.text(12), color = Theme.inkSoft)
                    StaysHomeVisitChips(visit = visit, status = status)
                }
            }
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
    }

    PageScroll(refresh = { reload() }, modifier = modifier) {
        // MARK: Header
        val pet = stay.petList.firstOrNull()
        val subtitle = "${stay.clientName ?: ""} · ${StaysDay.short(stay.checkInDay)} – ${StaysDay.short(stay.checkOutDay)}"
        val media: (@Composable () -> Unit)? = if (pet?.photoUrl == null) null else {
            { PetAvatar(name = pet.petName, species = pet.petSpecies, photoURL = pet.photoUrl, size = 56.dp) }
        }
        if (hasActions) {
            ModuleHeader(
                icon = "house", title = stay.petNames, subtitle = subtitle, accent = Theme.aqua, media = media,
                stats = headerStats, actionsOnRow = true, storageKey = "home-stay",
            ) {
                // MARK: What happens next
                if (stay.status == "requested" && canManage) {
                    PMButton(L("Confirm"), systemImage = "calendar-check") { sheet = StaysHomeSheet.confirm }
                    PMButton(
                        if (home.quoteSentAt != null) L("Send the quote again") else L("Send as a quote"),
                        systemImage = "send", variant = ButtonVariant.outline, isWorking = quoting,
                    ) { sendQuote() }
                }
                if (stay.status == "checked_in" && canCare) {
                    PMButton(L("Report card"), systemImage = "message-square-heart") { sheet = StaysHomeSheet.report }
                }
                if (stay.status == "checked_in" && canManage) {
                    PMButton(L("Finish"), systemImage = "log-out") { sheet = StaysHomeSheet.finish }
                }
                if (stay.isOpen && canManage) {
                    PMButton(L("Change dates or plan"), systemImage = "calendar-plus", variant = ButtonVariant.outline) { sheet = StaysHomeSheet.edit }
                    PMButton(L("Add a visit"), systemImage = "calendar-plus", variant = ButtonVariant.outline) { sheet = StaysHomeSheet.addVisit }
                }
                if (started && canCare) {
                    PMButton(L("Record an incident"), systemImage = "shield-alert", variant = ButtonVariant.outline) { incidentRequest += 1 }
                }
                if ((stay.status == "requested" || stay.status == "confirmed") && canManage) {
                    PMButton(
                        if (stay.status == "requested" && home.quoteSentAt != null) L("Withdraw the quote") else L("Cancel home stay"),
                        systemImage = "circle-x", variant = ButtonVariant.outline,
                    ) { sheet = StaysHomeSheet.cancel }
                }
                val invoiceId = stay.invoiceId
                if (invoiceId != null && workspace.moneyLevel >= MoneyLevel.charging) {
                    PMButton(L("Open the bill"), systemImage = "receipt", variant = ButtonVariant.outline) { navigator.push(ProviderRoute.invoice(invoiceId)) }
                }
                if (stay.isOpen && workspace.offersHomeVisits) {
                    PMButton(L("Run the visits in Domicilio"), systemImage = "house", variant = ButtonVariant.outline) {
                        navigator.push(ProviderRoute.section(ProviderSection.homeVisits))
                    }
                }
            }
        } else {
            ModuleHeader(
                icon = "house", title = stay.petNames, subtitle = subtitle, accent = Theme.aqua, media = media,
                stats = headerStats, storageKey = "home-stay",
            )
        }

        // Money beyond the estimate.
        if ((home.depositCents ?: 0) > 0 || (home.vetAuthorizationCents ?: 0) > 0 || home.catalogItem != null) {
            FlowLayout(spacing = 8.dp) {
                home.catalogItem?.name?.let { Chip(text = it, tone = Chip.Tone.info) }
                val deposit = home.depositCents
                if (deposit != null && deposit > 0) {
                    Chip(
                        text = if (home.depositOwed > 0) {
                            LF("Deposit %@ · %@ paid", Money.format(deposit, currency = stay.stayCurrency), Money.format(home.depositPaidCents, currency = stay.stayCurrency))
                        } else {
                            LF("Deposit %@ paid", Money.format(deposit, currency = stay.stayCurrency))
                        },
                        tone = if (home.depositOwed > 0) Chip.Tone.warn else Chip.Tone.good, icon = "wallet",
                    )
                }
                val vet = home.vetAuthorizationCents
                if (vet != null && vet > 0) {
                    Chip(text = LF("Vet authorized up to %@", Money.format(vet, currency = stay.stayCurrency)), tone = Chip.Tone.online, icon = "stethoscope")
                }
            }
        }

        // Today's visits at a glance: done, under way, missed.
        val day = home.today
        if (day != null && (day.total ?: 0) > 0 && (stay.status == "checked_in" || stay.status == "confirmed")) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.blue, 10.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                FlowLayout(spacing = 12.dp) {
                    Text(L("Today"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    ProgressBar(value = (day.done ?: 0).toDouble() / max(1, day.total ?: 1).toDouble(), height = 10.dp, width = 128.dp, track = Theme.surface)
                    Text(LF("%d of %d visits done", day.done ?: 0, day.total ?: 0), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
                    if ((day.inProgress ?: 0) > 0) Chip(text = L("Someone is there now"), tone = Chip.Tone.good)
                    if ((day.missed ?: 0) > 0) Chip(text = LP(day.missed ?: 0, "%d visit missed", "%d visits missed"), tone = Chip.Tone.danger)
                }
            }
        }

        SectionCard(L("Where")) { StaysHomeAccessCard(stay = stay, home = home, modifier = Modifier.padding(16.dp)) }

        // MARK: The keys
        // Who has the family's keys now, and their history.
        run {
            val out = home.keys?.state == "with_sitter" || home.keys?.state == "lockbox"
            val events = home.keys?.events ?: emptyList()
            SectionCard(L("The keys")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            icon = "key-round", accent = if (out) Theme.mango else Theme.surfaceMuted, size = 40.dp, iconSize = 20.dp,
                            ink = IconBadge.Ink.strong, washPercent = if (out) 30.0 else 100.0,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(StaysWords.keysState(home.keys), style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong)
                            val line = listOfNotNull(home.keys?.label, home.keys?.since?.let { DateText.dayTime(DateText.parse(it), zone = home.zone) }).joinToString(" · ")
                            Text(if (line.isEmpty()) L("Record them when the family hands them over.") else line, style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                    if (canCare && (stay.isOpen || out)) {
                        PMButton(
                            if (out) L("Update") else L("Record keys"), systemImage = "key-round", variant = ButtonVariant.outline, size = ButtonSize.compact,
                        ) { sheet = StaysHomeSheet.keys }
                    }
                    if (events.isNotEmpty()) {
                        RowDivider()
                        val soft = Theme.inkSoft
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (event in if (showAllKeys) events else events.take(3)) {
                                key(event.id) {
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(
                                            buildAnnotatedString {
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(StaysWords.keyAction(event)) }
                                                withStyle(SpanStyle(color = soft)) {
                                                    append(" · " + listOfNotNull(event.occurredAt?.let { DateText.dayTime(DateText.parse(it), zone = home.zone) }, event.byName).joinToString(" · "))
                                                }
                                            },
                                            style = Theme.text(14), color = Theme.inkStrong,
                                        )
                                        event.note.nonEmpty?.let { Text("“$it”", style = Theme.text(12), color = Theme.inkSoft) }
                                    }
                                }
                            }
                            if (events.size > 3) {
                                Text(
                                    if (showAllKeys) L("Show less") else LF("Show all (%d)", events.size),
                                    style = Theme.text(12, FontWeight.Bold), color = Theme.blue,
                                    modifier = Modifier.plainButton { showAllKeys = !showAllKeys },
                                )
                            }
                        }
                    }
                }
            }
        }

        // MARK: Visits
        run {
            val past = days.filter { it.first < today }
            val shown = if (showPast) days else days.filter { it.first >= today }
            SectionCard(
                if (home.mode == "overnight") L("Nights and visits") else L("Visits"),
                subtitle = if ((home.visits ?: emptyList()).isEmpty()) L("No visits planned.") else null,
            ) {
                if (past.isNotEmpty() || shown.isNotEmpty()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (past.isNotEmpty()) {
                            Text(
                                if (showPast) L("Hide past days") else LP(past.size, "Show %d past day", "Show %d past days"),
                                style = Theme.text(12, FontWeight.Bold), color = Theme.blue,
                                modifier = Modifier.plainButton { showPast = !showPast },
                            )
                        }
                        for ((groupDay, visits) in shown) {
                            key(groupDay) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        ((if (groupDay == today) L("Today") + " · " else "") + StaysDay.format(groupDay, "EEEEdMMM")).uppercase(),
                                        style = brandText(12, FontWeight.Bold).tracking(12 * 0.12),
                                        color = if (groupDay == today) Theme.blue else Theme.inkSoft,
                                    )
                                    for (visit in visits) key(visit.id) { VisitRow(visit) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // MARK: Pets and the house
        SectionCard(if (stay.petList.size > 1) L("Pets") else L("Pet")) {
            stay.petList.forEachIndexed { index, guest ->
                if (index > 0) RowDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PetAvatar(name = guest.petName, species = guest.petSpecies, photoURL = guest.photoUrl, size = 48.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(guest.petName, style = brandHeading(20), color = Theme.inkStrong)
                            Text(guest.petBreed ?: StaysWords.petSpecies(guest.petSpecies), style = Theme.text(14), color = Theme.inkSoft)
                        }
                        val petId = guest.providerClientPetId
                        if (petId != null) {
                            IconCircleButton(systemImage = "file-text", label = L("Open the chart"), tint = Theme.blue) {
                                navigator.push(ProviderRoute.patientChart(petId = petId))
                            }
                        }
                    }
                    StaysAllergies(allergies = guest.allergies ?: emptyList())
                    if ((guest.carePlan?.items ?: emptyList()).isEmpty()) {
                        Text(
                            if (stay.isOpen && canManage) L("No care plan yet: write what the sitter does at each visit.")
                            else L("No care plan yet. When the family shares their routines, they show up here."),
                            style = Theme.text(14), color = Theme.inkSoft,
                        )
                    } else {
                        StaysCarePlanList(items = guest.carePlan?.items ?: emptyList())
                    }
                    guest.carePlan?.notes.nonEmpty?.let { notes ->
                        Text(
                            notes, style = Theme.text(14), color = Theme.inkStrong,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (stay.isOpen && canManage) {
                RowDivider()
                PMButton(
                    L("Edit the care plan"), systemImage = "clipboard-list", variant = ButtonVariant.outline, size = ButtonSize.compact,
                    modifier = Modifier.padding(16.dp),
                ) { sheet = StaysHomeSheet.plan }
            }
        }

        val tasks = home.houseTasks ?: emptyList()
        if (home.houseRules.nonEmpty != null || tasks.isNotEmpty()) {
            SectionCard(L("The house")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    home.houseRules.nonEmpty?.let { StaysNoteBlock(label = L("House rules"), text = it, tint = Theme.mango) }
                    if (tasks.isNotEmpty()) {
                        FlowLayout(spacing = 6.dp) {
                            for (task in tasks) {
                                Chip(text = (task.time?.let { StaysDay.clock(it) + " " } ?: "") + (task.label ?: ""), tone = Chip.Tone.muted)
                            }
                        }
                    }
                }
            }
        }

        // The vet authorization and anything that happened (docs/consents-incidents.md).
        SectionCard(L("Consents")) {
            ConsentsCard(context = StaysActs.consentContext(stay, businessName = workspace.current?.name), bare = true, modifier = Modifier.padding(16.dp))
        }
        SectionCard(L("Incidents")) {
            IncidentsCard(
                act = ConsentAct(stayId = stay.id), pets = StaysActs.pets(stay), familyName = stay.clientName,
                recordable = started, bare = true, recordRequest = incidentRequest, showsRecordButton = false,
                modifier = Modifier.padding(16.dp),
            )
        }

        // Family and times
        Card(spacing = 0.dp) {
            StaysGrid(
                items = listOf<@Composable () -> Unit>(
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(L("Family"), style = brandHeading(16.8), color = Theme.inkStrong)
                            val clientId = stay.providerClientId
                            if (clientId != null) {
                                Row(
                                    Modifier.plainButton { navigator.push(ProviderRoute.client(clientId)) },
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stay.clientName ?: "", style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong)
                                    Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                                }
                            } else {
                                Text(stay.clientName ?: "", style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong)
                            }
                            stay.clientPhone.nonEmpty?.let { PhoneLink(phone = it) }
                        }
                    },
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(L("Times"), style = brandHeading(16.8), color = Theme.inkStrong)
                            Text("${L("They leave")} ${StaysDay.short(stay.checkInDay)} · ${StaysDay.clock(stay.checkInTime)}", style = Theme.text(14), color = Theme.inkStrong)
                            Text("${L("They come back")} ${StaysDay.short(stay.checkOutDay)} · ${StaysDay.clock(stay.checkOutTime)}", style = Theme.text(14), color = Theme.inkStrong)
                            if (home.mode == "overnight") {
                                Text(
                                    LF("Nights %@ – %@", StaysDay.clock(home.plan?.overnightStart), StaysDay.clock(home.plan?.overnightEnd)),
                                    style = Theme.text(12), color = Theme.inkSoft,
                                )
                            }
                            val times = home.plan?.times
                            if (!times.isNullOrEmpty()) {
                                Text(
                                    LF("Visits at %@ · %@", times.joinToString(", ") { StaysDay.clock(it) }, StaysHomePlanForm.minutesText(home.plan?.durationMinutes ?: 45)),
                                    style = Theme.text(12), color = Theme.inkSoft,
                                )
                            }
                        }
                    },
                ),
                minimum = 240.dp,
                spacing = 16.dp,
            ) { it() }
        }

        if (stay.ownerInstructions.nonEmpty != null || stay.internalNotes.nonEmpty != null) {
            SectionCard(L("Notes")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    stay.ownerInstructions.nonEmpty?.let { StaysNoteBlock(label = L("From the family"), text = it) }
                    stay.internalNotes.nonEmpty?.let { StaysNoteBlock(label = L("For the team"), text = it) }
                }
            }
        }

        val cards = (stay.updates ?: emptyList()).filter { it.kind != "incident" }
        SectionCard(
            L("Report cards"),
            subtitle = if (cards.isEmpty()) L("Nothing yet. Report cards written at a visit show up here and in the family’s app.") else null,
        ) {
            if (cards.isNotEmpty()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (update in cards) StaysReportCardView(update = update, pets = stay.petList, sentToFamily = true, zone = home.zone)
                }
            }
        }
        stay.cancellationReason.nonEmpty?.let { StaysNoteBlock(label = L("Why it closed"), text = it) }
    }

    sheet?.let { which ->
        AppSheet(onDismissRequest = { sheet = null }) {
            when (which) {
                StaysHomeSheet.confirm -> StaysHomeConfirmSheet(stay = stay, home = home)
                StaysHomeSheet.finish -> StaysHomeFinishSheet(stay = stay, home = home)
                StaysHomeSheet.report -> StaysReportCardSheet(
                    stayId = stay.id, pets = stay.petList, familyOnApp = stay.familyOnApp,
                    visitId = home.visits?.firstOrNull { it.status == "in_progress" }?.id,
                ) { scope.launch { reload() } }
                StaysHomeSheet.plan -> StaysCarePlanSheet(stay = stay) { scope.launch { reload() } }
                StaysHomeSheet.reassign -> StaysHomeReassignSheet(stay = stay, home = home)
                StaysHomeSheet.edit -> StaysHomeEditSheet(stay = stay, home = home)
                StaysHomeSheet.keys -> StaysHomeKeysSheet(stay = stay, home = home)
                StaysHomeSheet.addVisit -> StaysHomeAddVisitSheet(stay = stay, home = home)
                is StaysHomeSheet.visit -> StaysHomeVisitSheet(stay = stay, home = home, visitId = which.id)
                StaysHomeSheet.cancel -> {
                    val withdraw = stay.status == "requested" && home.quoteSentAt != null
                    ConfirmSheet(
                        title = if (withdraw) L("Withdraw the quote?") else L("Cancel this home stay?"),
                        message = L("Its visits are cancelled and the family is told. This cannot be undone."),
                        confirmTitle = if (withdraw) L("Withdraw the quote") else L("Cancel home stay"),
                        destructive = true, askReason = true, reasonLabel = L("Reason"), accent = ModalAccent.coral, icon = "circle-x",
                    ) { reason ->
                        try {
                            API.send("POST", "/provider-homestays/${stay.id}/cancel", body = mapOf("reason" to reason))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw APIError(
                                status = (e as? APIError)?.status ?: 0,
                                message = StaysWords.homeError(e, fallback = "Could not cancel this home stay."),
                                code = null, fields = emptyMap(),
                            )
                        }
                        Toast.show(if (stay.familyOnApp) L("Home stay cancelled. The family has been told.") else L("Home stay cancelled. Let the family know."))
                        announceDataChanged()
                    }
                }
            }
        }
    }
}

/**
 * A home visit's chips (web home-stay-bits `VisitChips`): its status, late,
 * double booked, no access.
 */
@Composable
fun StaysHomeVisitChips(visit: StaysHomeVisit, status: Pair<String, ProviderState>) {
    StatusChip(text = status.first, state = status.second)
    if (visit.late == true && visit.status == "planned") Chip(text = L("Late"), tone = Chip.Tone.warn, icon = "clock-3")
    if (visit.doubleBooked == true && visit.status != "cancelled") Chip(text = L("Double booked"), tone = Chip.Tone.danger, icon = "split")
    if (visit.noAccessAt != null) Chip(text = L("No access"), tone = Chip.Tone.danger, icon = "door-closed")
}

// MARK: - Confirm

@Composable
private fun StaysHomeConfirmSheet(stay: StaysStay, home: StaysHomeDetail) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val waive = remember { mutableStateOf(false) }
    var refusedForDeposit by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var takingDeposit by remember { mutableStateOf(false) }

    val owedShown = home.depositOwed > 0 || refusedForDeposit

    fun confirm() {
        working = true
        scope.launch {
            try {
                API.send("POST", "/provider-homestays/${stay.id}/confirm", body = if (waive.value) mapOf("waiveDeposit" to true) else emptyMap())
                Toast.show(
                    if (stay.familyOnApp) {
                        if (waive.value) L("Confirmed without the deposit. The family has been told.") else L("Home stay confirmed. The family has been told.")
                    } else {
                        if (waive.value) L("Confirmed without the deposit.") else L("Home stay confirmed.")
                    },
                )
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                if (e.code == "deposit_required") refusedForDeposit = true
                else Toast.error(StaysWords.homeError(e, fallback = "Could not confirm this home stay."))
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not confirm this home stay."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Confirm the home stay"),
        subtitle = "${stay.petNames} · ${StaysDay.short(stay.checkInDay)} – ${StaysDay.short(stay.checkOutDay)}",
        systemImage = "calendar-check",
        accent = ModalAccent.aqua,
        primaryTitle = if (waive.value) L("Confirm without the deposit") else L("Confirm"),
        isWorking = working,
        primaryIcon = "calendar-check",
        primaryDisabled = owedShown && !waive.value,
        onPrimary = { confirm() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Text(
            when {
                home.quoteAcceptedAt != null -> L("The family accepted the quote. Confirming books the visits and tells them.")
                home.quoteSentAt != null -> L("The family hasn’t answered the quote yet. You can confirm it for them.")
                else -> L("Confirming books the visits and tells the family.")
            },
            style = Theme.text(14), color = Theme.inkStrong,
        )
        val deposit = home.depositCents
        if (deposit != null && deposit > 0) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Deposit asked"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                    Text(Money.format(deposit, currency = stay.stayCurrency), style = brandHeading(18), color = Theme.inkStrong)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Paid"), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.weight(1f))
                    Text(Money.format(home.depositPaidCents, currency = stay.stayCurrency), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                }
                if (owedShown) {
                    NoticeCard(
                        tone = NoticeCard.Tone.warning,
                        title = LF("The deposit isn’t in yet (%@ to go). Take it first, or confirm without it.", Money.format(home.depositOwed, currency = stay.stayCurrency)),
                    )
                }
                if (owedShown && workspace.can(Permission.billingRecordPayment) && stay.providerClientId != null) {
                    // Web: a blue bold link with a plus.
                    PMButton(L("Take the deposit"), systemImage = "plus", variant = ButtonVariant.link, size = ButtonSize.small) { takingDeposit = true }
                }
            }
        }
        if (owedShown) {
            if (workspace.isOwnerOrAdmin) {
                ToggleRow(title = L("Confirm without the deposit"), subtitle = L("Owners and admins only. It is written down."), isOn = waive)
            } else {
                Text(L("Only owners and admins can confirm without the deposit."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }

    if (takingDeposit) {
        AppSheet(onDismissRequest = { takingDeposit = false }) {
            val clientId = stay.providerClientId
            if (clientId != null) {
                DepositSheet(
                    clientId = clientId, clientName = stay.clientName ?: "", stayId = stay.id, owed = home.depositOwed,
                    currency = stay.stayCurrency, label = L("Home stay deposit"),
                    explanation = L("It stays as credit on their account and is used when they pay the bill."),
                    onTaken = { refusedForDeposit = false },
                )
            }
        }
    }
}

// MARK: - Finish

@Composable
private fun StaysHomeFinishSheet(stay: StaysStay, home: StaysHomeDetail) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val checkOutDay = remember { mutableStateOf("") }
    val extras = remember { mutableStateOf<List<StaysExtraLine>>(emptyList()) }
    val createInvoice = remember { mutableStateOf(true) }
    val keepDraft = remember { mutableStateOf(false) }
    val noBillReason = remember { mutableStateOf("") }
    var keysChoice by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val keysOut = home.keys?.state == "with_sitter" || home.keys?.state == "lockbox"
    val noRate = (home.rateCents ?: 0) <= 0
    /** What the bill charges, as the server does: the nights up to the last day, or the visits made. */
    val charged = if (home.priceUnit == "night") {
        max(1, StaysDay.between(stay.checkInDay, if (checkOutDay.value.isEmpty()) stay.checkOutDay else checkOutDay.value))
    } else {
        (home.visits ?: emptyList()).count { it.kind != "meet_greet" && (it.status == "done" || it.status == "in_progress") }
    }
    val extrasCents = extras.value.sumOf { it.cents ?: 0 }

    fun finish() {
        if (!form.check(
                listOf(
                    "keys" to (if (keysOut && keysChoice == null) L("The keys are still out. Say whether they were returned or will be later.") else null),
                    "noBillReason" to (if (!createInvoice.value && noBillReason.value.nonEmpty == null) L("Write why it leaves without a bill.") else null),
                ),
            )
        ) return
        working = true
        scope.launch {
            try {
                if (keysOut && keysChoice == "returned") {
                    API.send("POST", "/provider-homestays/${stay.id}/keys", body = mapOf("action" to "returned"))
                }
                val body = mutableMapOf<String, Any?>("checkOutDay" to checkOutDay.value, "extras" to StaysExtrasSection.payload(extras.value), "createInvoice" to createInvoice.value)
                if (createInvoice.value) body["keepDraft"] = keepDraft.value else body["noBillReason"] = noBillReason.value.nonEmpty
                if (keysOut && keysChoice == "later") body["keysHandled"] = true
                API.send("POST", "/provider-homestays/${stay.id}/finish", body = body)
                Toast.show(
                    if (createInvoice.value) {
                        if (StaysBillChoice.goesToDesk(createInvoice = createInvoice.value, keepDraft = keepDraft.value, unpriced = noRate)) {
                            L("Home stay finished. The bill is at the front desk to be charged.")
                        } else {
                            L("Home stay finished. The bill waits as a draft in Invoices.")
                        }
                    } else {
                        if (stay.familyOnApp) L("Home stay finished. The family has been told.") else L("Home stay finished.")
                    },
                )
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                when (e.code) {
                    "keys_out" -> form.check(listOf("keys" to StaysWords.homeError(e, fallback = "Could not finish this home stay.")))
                    "reason_required" -> form.check(listOf("noBillReason" to L("Write why it leaves without a bill.")))
                    else -> Toast.error(StaysWords.homeError(e, fallback = "Could not finish this home stay."))
                }
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not finish this home stay."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Finish the home stay"),
        subtitle = stay.petNames,
        systemImage = "log-out",
        accent = ModalAccent.aqua,
        isDirty = extras.value.isNotEmpty() || noBillReason.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Finish"),
        isWorking = working,
        primaryIcon = "log-out",
        // The web footer's line: the total, the nights or visits charged, and where the bill goes.
        footerSummary = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Money.format(charged * (home.rateCents ?: 0) + extrasCents, currency = stay.stayCurrency), style = brandHeading(24), color = Theme.inkStrong)
                Text(
                    (if (home.priceUnit == "night") LP(charged, "%d night", "%d nights") else LP(charged, "%d visit made", "%d visits made")) +
                        (if (extrasCents > 0) " + " + L("extras") else "") + " · " +
                        StaysBillChoice.outcome(createInvoice = createInvoice.value, keepDraft = keepDraft.value, unpriced = noRate),
                    style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft,
                )
            }
        },
        onPrimary = { finish() },
    ) {
        DayField(label = L("Last day"), day = checkOutDay, min = stay.checkInDay)
        if (noRate) {
            // No rate bills the nights or visits at $0: the server keeps that bill a draft until it has a price.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Glyph("triangle-alert", 16.dp, tint = Theme.goldText, modifier = Modifier.padding(top = 1.dp))
                Text(
                    if (createInvoice.value) L("No rate: the bill stays a draft until you put a price on it.") else L("No rate: nothing to charge for the nights or visits."),
                    style = Theme.text(14, FontWeight.SemiBold), color = Theme.goldText,
                )
            }
        } else {
            Text(
                if (home.priceUnit == "night") LF("Billed per night, %@ each.", Money.format(home.rateCents, currency = stay.stayCurrency))
                else LF("Billed per visit made, %@ each. Missed and cancelled visits aren’t charged.", Money.format(home.rateCents, currency = stay.stayCurrency)),
                style = Theme.text(14), color = Theme.inkSoft,
            )
        }
        StaysExtrasSection(
            extras = extras, hint = L("An extra pet, a bath, keys copied — anything added during the stay."), prompt = L("What"),
            removeIcon = "x", addAsLink = true,
        )
        if (keysOut) {
            ModalSection(title = L("The keys"), subtitle = StaysWords.keysState(home.keys)) {
                FormField(label = L("The keys"), key = "keys", form = form) {
                    ChoiceCards(
                        options = listOf(
                            Triple<String, String, String?>("returned", L("Returned to the family"), L("Recorded now")),
                            Triple<String, String, String?>("later", L("They’ll be returned later"), L("Record it on the stay when they are")),
                        ),
                        selection = stateOf({ keysChoice ?: "" }, { value ->
                            keysChoice = value
                            form.clear("keys")
                        }),
                    )
                }
            }
        }
        StaysBillChoice(
            createInvoice = createInvoice, canInvoice = workspace.can(Permission.billingCreateInvoice), keepDraft = keepDraft,
            noBillReason = noBillReason, unpriced = noRate, form = form,
        )
    }

    LaunchedEffect(Unit) {
        if (checkOutDay.value.isNotEmpty()) return@LaunchedEffect
        val today = home.today?.dayKey ?: StaysDay.today()
        checkOutDay.value = if (today < stay.checkOutDay && today >= stay.checkInDay) today else stay.checkOutDay
        createInvoice.value = workspace.can(Permission.billingCreateInvoice)
    }
}

// MARK: - The plan of a home stay

/** A house task of a plan being written: its label and an optional time ("" = none). */
data class StaysHouseTaskDraft(
    val id: UUID = UUID.randomUUID(),
    /** The server's key, kept when changing a booked stay so its checklist stays linked. */
    var key: String? = null,
    var label: String = "",
    var time: String = "",
)

/**
 * What a home stay plans: the days and times, overnight or drop-in visits,
 * the price and its unit (or a catalog service), the deposit, the vet
 * authorization, the house (rules and tasks) and the meet & greet.
 * Made with `StaysHomePlan(day:, mode:)` or `StaysHomePlan(stay:, home:)` (Swift's two inits).
 */
data class StaysHomePlan(
    var checkInDay: String,
    var checkInTime: String,
    var checkOutDay: String,
    var checkOutTime: String,
    var mode: String,
    var times: List<String>,
    var durationMinutes: Int,
    var overnightStart: String = "20:00",
    var overnightEnd: String = "08:00",
    var priceUnit: String,
    var rate: Int? = null,
    var deposit: Int? = null,
    var vetAuthorization: Int? = null,
    var houseRules: String = "",
    var catalogItemId: String? = null,
    var houseTasks: List<StaysHouseTaskDraft> = emptyList(),
    var meetGreetOn: Boolean = false,
    var meetGreetDate: String = "",
    var meetGreetTime: String = "18:00",
    var meetGreetMinutes: Int = 30,
) {
    companion object {
        operator fun invoke(day: String = StaysDay.today(), mode: String = "visits"): StaysHomePlan {
            val twoBefore = StaysDay.add(day, -2)
            return StaysHomePlan(
                checkInDay = day,
                checkInTime = if (mode == "overnight") "18:00" else "07:00",
                checkOutDay = StaysDay.add(day, if (mode == "overnight") 1 else 2),
                checkOutTime = if (mode == "overnight") "10:00" else "21:00",
                mode = mode,
                times = if (mode == "overnight") emptyList() else listOf("08:00", "19:00"),
                durationMinutes = 45,
                priceUnit = if (mode == "overnight") "night" else "visit",
                meetGreetDate = if (twoBefore >= StaysDay.today()) twoBefore else StaysDay.today(),
            )
        }

        /** A booked home stay's plan, to change it (web draftFromStay). */
        operator fun invoke(stay: StaysStay, home: StaysHomeDetail): StaysHomePlan {
            val plan = invoke(day = stay.checkInDay, mode = home.mode ?: "visits")
            return plan.copy(
                checkInTime = stay.checkInTime ?: plan.checkInTime,
                checkOutDay = stay.checkOutDay,
                checkOutTime = stay.checkOutTime ?: plan.checkOutTime,
                times = home.plan?.times ?: emptyList(),
                durationMinutes = home.plan?.durationMinutes ?: 45,
                overnightStart = home.plan?.overnightStart ?: "20:00",
                overnightEnd = home.plan?.overnightEnd ?: "08:00",
                priceUnit = home.priceUnit ?: plan.priceUnit,
                catalogItemId = home.catalogItem?.id,
                rate = home.rateCents,
                deposit = home.depositCents,
                vetAuthorization = home.vetAuthorizationCents,
                houseRules = home.houseRules ?: "",
                houseTasks = (home.houseTasks ?: emptyList()).map { StaysHouseTaskDraft(key = it.key, label = it.label ?: "", time = it.time ?: "") },
            )
        }
    }

    val planBody: Map<String, Any?>
        get() = mapOf("times" to times, "durationMinutes" to durationMinutes, "overnightStart" to overnightStart, "overnightEnd" to overnightEnd)

    /** The house tasks as the server takes them: labelled ones, a time only when set, up to 20. */
    val houseTasksBody: List<Map<String, Any?>>
        get() = houseTasks.mapNotNull { task ->
            val label = task.label.nonEmpty ?: return@mapNotNull null
            val item = mutableMapOf<String, Any?>("label" to label.take(120), "time" to (if (task.time.isEmpty()) null else task.time))
            task.key?.let { item["key"] = it }
            item
        }.take(20)

    /** The meet & greet as the server takes it, with who goes. */
    fun meetGreetBody(sitterId: String?): Map<String, Any?>? =
        if (meetGreetOn) mapOf("date" to meetGreetDate, "startTime" to meetGreetTime, "durationMinutes" to meetGreetMinutes, "memberId" to sitterId) else null

    /** The fields a create sends (dates, plan, price, house, meet & greet). */
    fun body(sitterId: String?): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>(
            "checkInDay" to checkInDay, "checkInTime" to checkInTime, "checkOutDay" to checkOutDay, "checkOutTime" to checkOutTime,
            "mode" to mode, "plan" to planBody, "priceUnit" to priceUnit, "catalogItemId" to catalogItemId,
            "vetAuthorizationCents" to vetAuthorization, "houseRules" to houseRules.nonEmpty, "houseTasks" to houseTasksBody,
            "meetGreet" to meetGreetBody(sitterId = sitterId),
        )
        rate?.let { body["rateCents"] = it }
        deposit?.let { body["depositCents"] = it }
        return body
    }

    /** The fields a create or a request's confirm sends. */
    val body: Map<String, Any?> get() = body(sitterId = null)

    /** The server's rules, for errors before sending (homestays.ts). */
    val problems: List<Pair<String, String?>>
        get() {
            val dates: String? = when {
                checkOutDay < checkInDay || (checkOutDay == checkInDay && checkOutTime <= checkInTime) -> L("Choose valid dates: the family comes back after they leave.")
                StaysDay.between(checkInDay, checkOutDay) > 60 -> L("A home stay lasts 60 days at most.")
                mode == "overnight" && checkOutDay <= checkInDay -> L("An overnight stay needs at least one night. Choose a later return day, or plan visits instead.")
                else -> null
            }
            val plan: String? = when {
                mode == "visits" && times.isEmpty() -> L("Add at least one visit time.")
                times.size > 6 -> L("Up to six visits a day.")
                times.toSet().size != times.size || overlaps -> L("Two visits of the same day overlap, or a daytime visit runs into the night.")
                mode == "overnight" && StaysDay.minutes(overnightEnd) >= StaysDay.minutes(overnightStart) -> L("A night starts in the evening and ends the next morning.")
                else -> null
            }
            val meet: String? = if (meetGreetOn && (meetGreetDate.isEmpty() || meetGreetDate > checkInDay)) L("Choose a valid day and time for the meet & greet.") else null
            return listOf("dates" to dates, "plan" to plan, "meetGreet" to meet)
        }

    /** Two drop-ins closer than a visit's length, or a drop-in inside the night. */
    private val overlaps: Boolean
        get() {
            val sorted = times.sorted().map { StaysDay.minutes(it) }
            for (index in 1 until sorted.size) if (sorted[index] < sorted[index - 1] + durationMinutes) return true
            if (mode == "overnight") {
                val start = StaysDay.minutes(overnightStart)
                val end = StaysDay.minutes(overnightEnd)
                return sorted.any { it < end || it + durationMinutes > start }
            }
            return false
        }
}

/** Swift `plan.x = …` through a binding: a changed copy (the plan is a value). */
fun MutableState<StaysHomePlan>.edit(change: StaysHomePlan.() -> Unit) {
    value = value.copy().apply(change)
}

/** A service from the catalog a home stay can be billed as. */
@Serializable
data class StaysCatalogService(
    val id: String,
    val name: String,
    val defaultUnitAmount: Int? = null,
    val currency: String? = null,
)

/**
 * The plan's fields (web HomePlanFields): mode, days and times, visits, price
 * (or a catalog service), the house's rules and tasks, and the meet & greet.
 *
 * - `showMeetGreet`: the meet & greet section (not when changing a booked stay).
 * - `startedDay`: the stay started: its first day stays.
 * - `editing`: changing a booked stay: an empty deposit means none.
 */
@Composable
fun StaysHomePlanForm(
    plan: MutableState<StaysHomePlan>,
    lockDays: Boolean = false,
    form: FormState,
    showMeetGreet: Boolean = true,
    parts: Set<StaysHomePlanForm.Part> = StaysHomePlanForm.Part.entries.toSet(),
    startedDay: String? = null,
    editing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var services by remember { mutableStateOf<List<StaysCatalogService>>(emptyList()) }
    val p = plan.value
    val chosen = services.firstOrNull { it.id == p.catalogItemId }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (StaysHomePlanForm.Part.mode in parts) {
            ModalSection(title = L("How")) {
                ChoiceCards(
                    options = listOf(
                        Triple<String, String, String?>("overnight", L("Overnight"), L("The sitter sleeps at the home, evening to morning")),
                        Triple<String, String, String?>("visits", L("Drop-in visits"), L("Visits at set times each day")),
                    ),
                    selection = stateOf({ plan.value.mode }, { mode ->
                        if (mode != plan.value.mode) {
                            plan.edit {
                                this.mode = mode
                                priceUnit = if (mode == "overnight") "night" else "visit"
                                if (mode == "overnight") times = emptyList() else if (times.isEmpty()) times = listOf("08:00", "19:00")
                                if (mode == "overnight" && checkOutDay <= checkInDay) checkOutDay = StaysDay.add(checkInDay, 1)
                            }
                            form.clear("plan")
                        }
                    }),
                )
            }
        }
        if (StaysHomePlanForm.Part.`when` in parts) {
            ModalSection(
                title = L("When"),
                subtitle = if (lockDays) L("The family asked for these days; choose the times.") else L("From when the family leaves until they come back."),
            ) {
                if (lockDays) {
                    Text("${StaysDay.short(p.checkInDay)} – ${StaysDay.short(p.checkOutDay)}", style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                } else {
                    if (startedDay != null) {
                        FactRow(label = L("They leave"), value = StaysDay.short(startedDay))
                        Text(L("The stay has started, so its first day stays."), style = Theme.text(12), color = Theme.inkSoft)
                    } else {
                        DayField(
                            label = L("They leave"),
                            day = stateOf({ plan.value.checkInDay }, { value ->
                                plan.edit {
                                    checkInDay = value
                                    if (checkOutDay < value || (mode == "overnight" && checkOutDay <= value)) checkOutDay = StaysDay.add(value, 1)
                                    if (meetGreetDate > value) meetGreetDate = value
                                }
                            }),
                            min = if (editing) null else StaysDay.today(), key = "dates", form = form,
                        )
                    }
                    DayField(label = L("They come back"), day = stateOf({ plan.value.checkOutDay }, { v -> plan.edit { checkOutDay = v } }), min = p.checkInDay)
                }
                TimeField(label = L("Time they leave"), value = stateOf({ plan.value.checkInTime }, { v -> plan.edit { checkInTime = v } }), from = "00:00", to = "23:45")
                TimeField(label = L("Time they come back"), value = stateOf({ plan.value.checkOutTime }, { v -> plan.edit { checkOutTime = v } }), from = "00:00", to = "23:45")
            }
        }
        if (StaysHomePlanForm.Part.visits in parts) {
            ModalSection(title = if (p.mode == "overnight") L("Nights and daytime visits") else L("Visits each day")) {
                if (p.mode == "overnight") {
                    TimeField(label = L("Arrives in the evening"), value = stateOf({ plan.value.overnightStart }, { v -> plan.edit { overnightStart = v } }), from = "15:00", to = "23:45")
                    TimeField(label = L("Leaves the next morning"), value = stateOf({ plan.value.overnightEnd }, { v -> plan.edit { overnightEnd = v } }), from = "05:00", to = "12:00")
                }
                FormField(label = if (p.mode == "overnight") L("Daytime drop-ins (optional)") else L("Times"), key = "plan", form = form) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.times.forEachIndexed { index, time ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppMenu(
                                    label = {
                                        FieldChrome {
                                            Glyph("clock-3", 16.dp, tint = Theme.inkSoft)
                                            Text(StaysDay.clock(time), style = Theme.text(14), color = Theme.inkStrong, modifier = Modifier.padding(start = 8.dp).weight(1f))
                                            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    for (option in StaysDay.times(from = "05:00", to = "23:00")) {
                                        item(StaysDay.clock(option)) {
                                            plan.edit { times = times.toMutableList().also { if (index in it.indices) it[index] = option }.sorted() }
                                            form.clear("plan")
                                        }
                                    }
                                }
                                IconCircleButton(systemImage = "x", label = L("Remove this time"), tint = Theme.inkSoft) {
                                    plan.edit { times = times.toMutableList().also { if (index in it.indices) it.removeAt(index) } }
                                }
                            }
                        }
                        if (p.times.size < 6) {
                            PMButton(L("Add a time"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                                plan.edit { times = (times + (if (times.isEmpty()) "08:00" else if (times.size == 1) "18:00" else "13:00")).sorted() }
                                form.clear("plan")
                            }
                        }
                    }
                }
                if (p.times.isNotEmpty()) {
                    MenuField(
                        label = L("Length of each visit"),
                        options = (listOf(15, 20, 30, 45, 60, 90, 120, 180, 240) + p.durationMinutes).toSortedSet().map { it to StaysHomePlanForm.minutesText(it) },
                        selection = stateOf({ plan.value.durationMinutes }, { v -> plan.edit { durationMinutes = v } }),
                    )
                }
            }
        }
        if (StaysHomePlanForm.Part.price in parts) {
            ModalSection(title = L("Price"), subtitle = L("Not per pet: the family’s pets share the sitter. Add an extra pet as an extra when finishing.")) {
                ChoiceCards(
                    options = listOf(
                        Triple<String, String, String?>("night", L("Per night"), null),
                        Triple<String, String, String?>("visit", L("Per visit"), L("Visits and nights made; never the meet & greet")),
                    ),
                    selection = stateOf({ plan.value.priceUnit }, { v -> plan.edit { priceUnit = v } }),
                )
                MenuField(
                    label = L("Service from your catalog"),
                    options = listOf("" to L("None — type the price")) + services.map { it.id to "${it.name} · ${Money.format(it.defaultUnitAmount, currency = it.currency)}" },
                    selection = stateOf({ plan.value.catalogItemId ?: "" }, { id ->
                        val item = services.firstOrNull { it.id == id }
                        plan.edit {
                            catalogItemId = id.nonEmpty
                            if (item != null) rate = item.defaultUnitAmount
                        }
                    }),
                )
                MoneyField(
                    label = if (p.priceUnit == "night") L("Price per night") else L("Price per visit"),
                    cents = stateOf({ plan.value.rate }, { v -> plan.edit { rate = v } }), key = "rate", form = form,
                    hint = chosen?.let { LF("Empty: %@ from the catalog.", Money.format(it.defaultUnitAmount, currency = it.currency)) },
                )
                MoneyField(
                    label = L("Deposit"), cents = stateOf({ plan.value.deposit }, { v -> plan.edit { deposit = v } }),
                    hint = if (editing) L("Empty or 0: no deposit.") else L("Empty: your usual stay deposit. 0: none."),
                )
                MoneyField(
                    label = L("Vet authorization up to"), cents = stateOf({ plan.value.vetAuthorization }, { v -> plan.edit { vetAuthorization = v } }),
                    hint = L("The family signs it before the first visit."),
                )
            }
        }
        if (StaysHomePlanForm.Part.house in parts) {
            ModalSection(title = L("The house"), subtitle = L("Rules the sitter follows, and chores laid out each day (plants, mail, trash).")) {
                PMTextField(
                    L("House rules"), text = stateOf({ plan.value.houseRules }, { v -> plan.edit { houseRules = v } }),
                    prompt = L("e.g. Shoes off inside; Luna isn’t allowed on the sofa."), multiline = true,
                )
                FormField(label = L("House tasks")) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (task in p.houseTasks) {
                            key(task.id) {
                                fun change(transform: StaysHouseTaskDraft.() -> Unit) {
                                    plan.edit { houseTasks = houseTasks.map { if (it.id == task.id) it.copy().apply(transform) else it } }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FieldChrome(modifier = Modifier.weight(1f)) {
                                        PlainTextField(
                                            stateOf({ plan.value.houseTasks.firstOrNull { it.id == task.id }?.label ?: "" }, { v -> change { label = v } }),
                                            prompt = L("Water the plants"), modifier = Modifier.weight(1f),
                                        )
                                    }
                                    AppMenu(
                                        label = {
                                            Box(Modifier.fieldChrome(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    if (task.time.isEmpty()) L("Time") else StaysDay.clock(task.time),
                                                    style = Theme.text(14, FontWeight.SemiBold),
                                                    color = if (task.time.isEmpty()) Theme.inkSoft else Theme.inkStrong,
                                                    modifier = Modifier.widthIn(min = 72.dp),
                                                )
                                            }
                                        },
                                    ) {
                                        item(L("No set time")) { change { time = "" } }
                                        for (option in StaysDay.times(from = "00:00", to = "23:45")) {
                                            item(StaysDay.clock(option)) { change { time = option } }
                                        }
                                    }
                                    IconCircleButton(systemImage = "x", label = L("Remove this task"), tint = Theme.inkSoft) {
                                        plan.edit { houseTasks = houseTasks.filter { it.id != task.id } }
                                    }
                                }
                            }
                        }
                        if (p.houseTasks.size < 20) {
                            // Web: a blue bold link with a plus.
                            PMButton(L("Add a task"), systemImage = "plus", variant = ButtonVariant.link, size = ButtonSize.small) {
                                plan.edit { houseTasks = houseTasks + StaysHouseTaskDraft() }
                            }
                        }
                    }
                }
            }
        }
        if (showMeetGreet && StaysHomePlanForm.Part.meetGreet in parts) {
            ModalSection(title = L("Meet & greet")) {
                ToggleRow(
                    title = L("Meet the family first"),
                    subtitle = L("A short visit before the stay to meet the pets and get the keys. Not charged."),
                    isOn = stateOf({ plan.value.meetGreetOn }, { v -> plan.edit { meetGreetOn = v } }),
                )
                if (p.meetGreetOn) {
                    DayField(
                        label = L("Day"), day = stateOf({ plan.value.meetGreetDate }, { v -> plan.edit { meetGreetDate = v } }),
                        min = StaysDay.today(), max = p.checkInDay, key = "meetGreet", form = form,
                    )
                    TimeField(label = L("Time"), value = stateOf({ plan.value.meetGreetTime }, { v -> plan.edit { meetGreetTime = v } }), from = "07:00", to = "21:00")
                    MenuField(
                        label = L("Length"), options = listOf(15, 20, 30, 45, 60, 90).map { it to StaysHomePlanForm.minutesText(it) },
                        selection = stateOf({ plan.value.meetGreetMinutes }, { v -> plan.edit { meetGreetMinutes = v } }),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (services.isNotEmpty() || StaysHomePlanForm.Part.price !in parts) return@LaunchedEffect
        val page = runCatching {
            API.get<Page<StaysCatalogService>>("/provider-catalog", mapOf("limit" to "100", "offset" to "0", "itemType" to "service", "active" to "true"))
        }.getOrNull()
        services = page?.items ?: emptyList()
    }
}

object StaysHomePlanForm {
    /** The form's parts, in the web's order (HomePlanFields): How, When, the visits, Price, The house, Meet & greet.
     *  The new home stay sheet lays them over its steps (PlanModeSection, PlanVisitsSection…). */
    enum class Part { mode, `when`, visits, price, house, meetGreet }

    fun minutesText(minutes: Int): String {
        if (minutes < 60) return LF("%d min", minutes)
        val hours = minutes / 60.0
        if (hours == Math.rint(hours)) return LP(hours.roundToInt(), "%d hour", "%d hours")
        return LF("%@ h", String.format(Locale.US, "%.1f", hours))
    }
}

// MARK: - New home stay

private enum class StaysHomeBookingStep { who, plan, confirm }

@Composable
fun StaysHomeBookingSheet(presetDay: String, modifier: Modifier = Modifier, onBooked: () -> Unit) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var step by remember { mutableStateOf(StaysHomeBookingStep.who) }
    val client = remember { mutableStateOf<PickedClient?>(null) }
    val petIds = remember { mutableStateOf<List<String>>(emptyList()) }
    val plan = remember { mutableStateOf(StaysHomePlan()) }
    val sitterId = remember { mutableStateOf("") }
    var sitters by remember { mutableStateOf<List<StaysMember>>(emptyList()) }
    val addressId = remember { mutableStateOf<String?>(null) }
    val accessNotes = remember { mutableStateOf("") }
    val sendQuote = remember { mutableStateOf(false) }
    val ownerInstructions = remember { mutableStateOf("") }
    val internalNotes = remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    val bookAnyway = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val chosenPets = client.value?.activePets?.filter { it.id in petIds.value } ?: emptyList()

    fun book() {
        val picked = client.value ?: return
        working = true
        scope.launch {
            val body = plan.value.body(sitterId = sitterId.value.nonEmpty).toMutableMap()
            body["providerClientId"] = picked.id
            body["providerClientPetIds"] = petIds.value
            body["sendQuote"] = sendQuote.value
            body["status"] = if (sendQuote.value) "requested" else "confirmed"
            body["memberId"] = sitterId.value.nonEmpty
            body["addressId"] = addressId.value
            body["accessNotes"] = accessNotes.value.nonEmpty
            body["ownerInstructions"] = ownerInstructions.value.nonEmpty
            body["internalNotes"] = internalNotes.value.nonEmpty
            body["locationId"] = workspace.locationId
            if (bookAnyway.value) body["allowDoubleBooking"] = true
            try {
                val stay = API.post<StaysStay>("/provider-homestays", body = body)
                Toast.show(
                    if (sendQuote.value) {
                        if (picked.householdId != null) LF("Quote sent to the family · %@", stay.petNames)
                        else LF("Quote saved · %@. The family isn’t on PawMiaw, so share it with them yourself.", stay.petNames)
                    } else {
                        LF("%@ booked · %@", stay.petNames, "${StaysDay.short(stay.checkInDay)} – ${StaysDay.short(stay.checkOutDay)}")
                    },
                )
                announceDataChanged()
                onBooked()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                when {
                    e.code == "visit_conflict" -> {
                        conflict = true
                        bookAnyway.value = false
                    }
                    (e.code ?: "") in listOf("invalid_member", "member_cannot_care", "member_not_at_location") -> {
                        step = StaysHomeBookingStep.plan
                        form.check(listOf("sitter" to StaysWords.homeError(e, fallback = "Could not book this home stay.")))
                    }
                    else -> Toast.error(StaysWords.homeError(e, fallback = "Could not book this home stay."))
                }
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not book this home stay."))
            } finally {
                working = false
            }
        }
    }

    fun next() {
        when (step) {
            StaysHomeBookingStep.who -> {
                if (!form.check(
                        listOf(
                            "client" to (if (client.value == null) L("Choose the client first.") else null),
                            "pets" to (if (client.value != null && petIds.value.isEmpty()) L("Choose at least one pet.") else null),
                        ),
                    )
                ) return
                step = StaysHomeBookingStep.plan
            }
            StaysHomeBookingStep.plan -> {
                if (!form.check(plan.value.problems)) return
                step = StaysHomeBookingStep.confirm
            }
            StaysHomeBookingStep.confirm -> {
                if (conflict && !bookAnyway.value) {
                    form.check(listOf("conflict" to L("The sitter is busy then. Check the warning and book it anyway if you meant to.")))
                    return
                }
                book()
            }
        }
    }

    ProviderModal(
        title = L("New home stay"),
        subtitle = L("Pet sitting at the client’s home: overnight or drop-in visits."),
        systemImage = "house",
        accent = ModalAccent.aqua,
        isDirty = client.value != null || step != StaysHomeBookingStep.who,
        form = form,
        primaryTitle = if (step == StaysHomeBookingStep.confirm) {
            if (conflict) L("Book anyway") else if (sendQuote.value) L("Send quote") else L("Book home stay")
        } else {
            L("Continue")
        },
        isWorking = working,
        steps = ModalSteps(steps = listOf(L("Who") to "user-round", L("The plan") to "calendar-days", L("Confirm") to "check"), current = step.ordinal),
        onBack = if (step != StaysHomeBookingStep.who) ({ step = StaysHomeBookingStep.entries.getOrElse(step.ordinal - 1) { StaysHomeBookingStep.who } }) else null,
        primaryIcon = if (step == StaysHomeBookingStep.confirm) (if (sendQuote.value && !conflict) "send" else "check") else null,
        onPrimary = { next() },
        modifier = modifier,
    ) {
        val picked = client.value
        if (step != StaysHomeBookingStep.who && picked != null) {
            // What it's about, once chosen — the same pills as the other create modals; Back changes it.
            ContextPills(client = picked.ownerName, linked = picked.householdId != null, pets = chosenPets.map { it.name })
        }
        when (step) {
            StaysHomeBookingStep.who ->
                ClientPetPicker(client = client, petIds = petIds, pets = ClientPetPicker.Pets.several, petRequired = true, petKey = "pets", form = form)
            StaysHomeBookingStep.plan -> {
                // Web order: How, When, the visits, At their home, Who goes, Meet & greet.
                StaysHomePlanForm(plan = plan, form = form, parts = setOf(StaysHomePlanForm.Part.mode, StaysHomePlanForm.Part.`when`, StaysHomePlanForm.Part.visits))
                if (picked != null) {
                    ModalSection(title = L("At their home")) {
                        // The one address picker of every visit at a client's home.
                        SchedAddressPicker(clientId = picked.id, addressId = addressId, accessNotes = accessNotes)
                    }
                }
                ModalSection(title = L("Who goes")) {
                    MenuField(
                        label = L("Sitter"), options = listOf("" to L("Decide later")) + sitters.map { it.id to it.label },
                        selection = sitterId, key = "sitter", form = form,
                    )
                    if (sitters.isEmpty()) {
                        Text(
                            L("Nobody on your team looks after stays yet. Give someone “Care and report cards” in Staff."),
                            style = Theme.text(12), color = Theme.inkSoft,
                        )
                    }
                }
                StaysHomePlanForm(plan = plan, form = form, parts = setOf(StaysHomePlanForm.Part.meetGreet))
            }
            StaysHomeBookingStep.confirm -> {
                // Web `SummaryTile`s: who, when and who goes, before the last choices.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StaysSummaryTile(label = L("Who")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PetStack(pets = chosenPets.map { Triple(it.name, it.petSpecies, it.photoUrl) }, size = 28.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(chosenPets.joinToString(", ") { it.name }, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
                                Text(picked?.name ?: "", style = Theme.text(12), color = Theme.inkSoft, maxLines = 1)
                            }
                        }
                    }
                    val p = plan.value
                    StaysSummaryTile(label = L("When")) {
                        Text(
                            "${StaysDay.short(p.checkInDay)} ${StaysDay.clock(p.checkInTime)} – ${StaysDay.short(p.checkOutDay)} ${StaysDay.clock(p.checkOutTime)}",
                            style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                        )
                    }
                    StaysSummaryTile(label = L("Who goes")) {
                        Text(sitters.firstOrNull { it.id == sitterId.value }?.label ?: L("Decide later"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
                    }
                }
                StaysHomePlanForm(plan = plan, form = form, parts = setOf(StaysHomePlanForm.Part.house))
                ModalSection(title = L("Notes")) {
                    PMTextField(L("What the family asked for"), text = ownerInstructions, prompt = L("e.g. Luna hides under the bed when it thunders."), multiline = true)
                    PMTextField(L("Note for the team"), text = internalNotes, prompt = L("Only your team sees this"), multiline = true)
                }
                StaysHomePlanForm(plan = plan, form = form, parts = setOf(StaysHomePlanForm.Part.price))
                ModalSection(title = L("How to save it")) {
                    ChoiceCards(
                        options = listOf(
                            Triple(false, L("Book now"), L("Confirmed straight away; the visits go on the calendar.")),
                            Triple(
                                true, L("Send as a quote"),
                                if (picked?.householdId != null) L("The family accepts it in the app") else L("The family isn’t on PawMiaw: you share it and confirm it for them"),
                            ),
                        ),
                        selection = sendQuote,
                    )
                }
                if (conflict) StaysHomeTeam.conflictNotice(confirmed = bookAnyway, form = form)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        plan.value = StaysHomePlan(day = if (presetDay < StaysDay.today()) StaysDay.today() else presetDay)
        val clinic = workspace.current?.clinicId
        if (clinic != null) {
            val members = runCatching { API.get<List<StaysMember>>("/provider-workspaces/$clinic/members") }.getOrNull()
            sitters = (members ?: emptyList()).filter { it.canCare }
        }
    }
    StaysOnChanged(client.value?.id) {
        // Another family: their own address (the picker chooses its default) and no notes.
        addressId.value = null
        accessNotes.value = ""
    }
    StaysOnChanged(sitterId.value) {
        conflict = false
        bookAnyway.value = false
    }
}
