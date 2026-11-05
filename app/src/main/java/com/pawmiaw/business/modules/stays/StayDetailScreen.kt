package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

//
//  StayDetailScreen.kt (iOS StayDetailScreen.swift)
//
//  One stay (web stay-detail-sheet.tsx; a home stay opens home-stay-sheet.tsx,
//  StaysHomeStay.kt): its status, room and estimate (or bill), a red
//  banner when it is a day late, the actions for its stage (confirm, check in,
//  report card, check out, change the dates, no-show, cancel, the bill), the
//  guests with their allergies, vaccines and care plan, the family, the times,
//  the notes and the report cards.
//

@Composable
fun StayDetailScreen(stayId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    var stay by remember { mutableStateOf<StaysStay?>(null) }
    var home by remember { mutableStateOf<StaysHomeDetail?>(null) }
    var units by remember { mutableStateOf<List<StaysUnit>>(emptyList()) }
    var billTotal by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            val summary = API.get<StaysStay>("/provider-stays/$stayId")
            if (summary.isHome) {
                val (full, detail) = StaysAPI.homeStay(stayId)
                stay = full
                home = detail
            } else {
                stay = summary
                units = runCatching { API.get<List<StaysUnit>>("/provider-stays/units", mapOf("locationId" to workspace.locationId)) }.getOrNull() ?: units
            }
            val invoiceId = stay?.invoiceId
            billTotal = if (invoiceId != null && workspace.can(Permission.billingCreateInvoice)) {
                runCatching { API.get<StaysInvoiceTotal>("/provider-invoices/$invoiceId") }.getOrNull()?.totalAmount
            } else {
                null
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }

    providerPage(stay?.petNames ?: L("Stay"), modifier = modifier) {
        val shown = stay
        val detail = home
        if (shown != null && shown.isHome && detail != null) {
            StaysHomeStayView(stay = shown, home = detail, billTotal = billTotal) { load() }
        } else if (shown != null && !shown.isHome) {
            StaysStayDetailView(stay = shown, units = units, billTotal = billTotal) { load() }
        } else if (error != null) {
            PageScroll { ErrorState(message = error ?: "") { load() } }
        } else {
            PageScroll { LoadingState() }
        }
    }
    ReloadsOnScope { load() }
}

private sealed class StaysDetailSheet {
    data object checkIn : StaysDetailSheet()
    data object checkOut : StaysDetailSheet()
    data object dates : StaysDetailSheet()
    data object report : StaysDetailSheet()
    data object plan : StaysDetailSheet()
    data object room : StaysDetailSheet()
    data class close(val kind: String) : StaysDetailSheet()

    val id: String
        get() = when (this) {
            checkIn -> "in"
            checkOut -> "out"
            dates -> "dates"
            report -> "report"
            plan -> "plan"
            room -> "room"
            is close -> "close-$kind"
        }
}

/**
 * A boarding or daycare stay, in full (web stay-detail-sheet.tsx as a page):
 * the header with the pets' photo, its status, room and estimate (or bill)
 * as numbers and the actions for its stage; a red notice when it is a day
 * late; the guests, consents, incidents, the family and times, the notes
 * and the report cards.
 */
@Composable
private fun StaysStayDetailView(
    stay: StaysStay,
    units: List<StaysUnit>,
    billTotal: Int?,
    reload: suspend () -> Unit,
) {
    val workspace = Workspace.shared
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<StaysDetailSheet?>(null) }
    var working by remember { mutableStateOf(false) }
    /** Counts presses of "Record an incident" in the action row; the incidents card opens its sheet. */
    var incidentRequest by remember { mutableIntStateOf(0) }

    val canManage = workspace.can(Permission.staysManage)
    val canCare = workspace.can(Permission.staysCare)
    val started = stay.status == "checked_in" || stay.status == "checked_out"
    val today = stay.today ?: StaysDay.today()
    val actPets = StaysActs.pets(stay)
    val consentContext = StaysActs.consentContext(stay, businessName = workspace.current?.name)
    /** Its days are over and it never came: change them (or mark the no-show) before checking in. */
    val daysOver = stay.status != "checked_in" && (stay.checkOutDay < today || (stay.isDaycare && stay.checkInDay < today))
    val hasActions = (stay.isOpen && canManage) || (stay.status == "checked_in" && canCare) || (started && canCare) ||
        (stay.invoiceId != null && workspace.moneyLevel >= MoneyLevel.charging)

    fun confirm() {
        working = true
        scope.launch {
            try {
                API.patch<StaysStay>("/provider-stays/${stay.id}", body = mapOf("status" to "confirmed"))
                Toast.show(L("Stay confirmed."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not change this stay."))
            } finally {
                working = false
            }
        }
    }

    // MARK: Header

    val headerStats = run {
        val state = ProviderState.stay(stay.status)
        val unitsText = StaysWords.units(stay.kind, stay.billableUnits ?: 1)
        val rateHint = if (stay.rate > 0) {
            val extras = if (billTotal != null && billTotal != stay.estimatedTotalCents) " + " + L("extras") else ""
            "$unitsText × ${Money.format(stay.rate, currency = stay.stayCurrency)}${if (stay.petList.size > 1) " × ${stay.petList.size}" else ""}$extras"
        } else {
            "$unitsText · ${L("No rate set")}"
        }
        listOf(
            ModuleStat(
                id = "status", label = L("Status"), value = StaysWords.status(stay.status), icon = "circle-dot", accent = state.color,
                attention = stay.late != null && stay.isOpen,
            ),
            ModuleStat(
                id = "room", label = L("Room"), value = if (working) "…" else (stay.unitName ?: L("No room yet")),
                hint = if (stay.isOpen && canManage) L("Tap to change it") else null, icon = "bed-double", accent = Theme.aqua,
                attention = stay.unitName == null && stay.isOpen,
                action = if (stay.isOpen && canManage) ({ sheet = StaysDetailSheet.room }) else null,
            ),
            ModuleStat(
                id = "total", label = if (billTotal != null) L("Bill") else L("Estimated total"),
                value = Money.format(billTotal ?: stay.estimatedTotalCents, currency = stay.stayCurrency),
                hint = rateHint, icon = if (billTotal != null) "receipt" else "wallet", accent = Theme.lime,
            ),
        )
    }

    PageScroll(refresh = { reload() }) {
        val pet = stay.petList.firstOrNull()
        val subtitle = "${stay.clientName ?: ""} · ${StaysDay.short(stay.checkInDay)}${if (stay.kind == "boarding") " – ${StaysDay.short(stay.checkOutDay)}" else ""}"
        val media: (@Composable () -> Unit)? = if (pet?.photoUrl == null) null else {
            { PetAvatar(name = pet.petName, species = pet.petSpecies, photoURL = pet.photoUrl, size = 56.dp) }
        }
        // The actions for this stage on their own line; a stay with none has no empty line.
        if (hasActions) {
            ModuleHeader(
                icon = "bed-double", title = stay.petNames, subtitle = subtitle, accent = Theme.aqua, media = media,
                stats = headerStats, actionsOnRow = true, storageKey = "stay",
            ) {
                // MARK: Actions for this stage
                if (stay.status == "requested" && canManage) {
                    PMButton(L("Confirm"), systemImage = "calendar-check", isWorking = working) { confirm() }
                }
                if ((stay.status == "requested" || stay.status == "confirmed") && !daysOver && canManage) {
                    PMButton(L("Check in"), systemImage = "log-in") { sheet = StaysDetailSheet.checkIn }
                }
                if (stay.status == "checked_in" && canCare) {
                    PMButton(L("Report card"), systemImage = "message-square-heart") { sheet = StaysDetailSheet.report }
                }
                if (stay.status == "checked_in" && canManage) {
                    PMButton(L("Check out"), systemImage = "log-out") { sheet = StaysDetailSheet.checkOut }
                }
                if (stay.isOpen && canManage) {
                    PMButton(
                        if (stay.status == "checked_in") L("Extend or shorten") else L("Change the dates"),
                        systemImage = "calendar-range", variant = ButtonVariant.outline,
                    ) { sheet = StaysDetailSheet.dates }
                }
                if (started && canCare) {
                    PMButton(L("Record an incident"), systemImage = "shield-alert", variant = ButtonVariant.outline) { incidentRequest += 1 }
                }
                if (stay.status == "confirmed" && stay.checkInDay <= today && canManage) {
                    PMButton(L("No-show"), systemImage = "user-x", variant = ButtonVariant.outline) { sheet = StaysDetailSheet.close("no_show") }
                }
                if ((stay.status == "requested" || stay.status == "confirmed") && canManage) {
                    PMButton(L("Cancel stay"), systemImage = "circle-x", variant = ButtonVariant.outline) { sheet = StaysDetailSheet.close("cancelled") }
                }
                val invoiceId = stay.invoiceId
                if (invoiceId != null && workspace.moneyLevel >= MoneyLevel.charging) {
                    PMButton(L("Open the bill"), systemImage = "receipt", variant = ButtonVariant.outline) { navigator.push(ProviderRoute.invoice(invoiceId)) }
                }
            }
        } else {
            ModuleHeader(
                icon = "bed-double", title = stay.petNames, subtitle = subtitle, accent = Theme.aqua, media = media,
                stats = headerStats, storageKey = "stay",
            )
        }

        // MARK: Late
        // A day late either way: what happened and what to do, above everything.
        val late = stay.late
        if (late != null && stay.isOpen) {
            NoticeCard(
                tone = NoticeCard.Tone.problem,
                title = if (late == StaysLate.departure) {
                    LF("%@ was due out %@ at %@ and is still here.", stay.petNames, StaysDay.short(stay.checkOutDay), StaysDay.clock(stay.checkOutTime))
                } else {
                    LF("%@ was due in %@ at %@ and hasn’t arrived.", stay.petNames, StaysDay.short(stay.checkInDay), StaysDay.clock(stay.checkInTime))
                },
                message = when {
                    late == StaysLate.departure -> L("The room stays held until you check it out. If the family is staying longer, extend the stay; the nights are counted when it leaves.")
                    daysOver -> L("Its days are over. Change the dates if the family is still coming, or mark the no-show.")
                    else -> L("Check it in when it comes, change the dates, or mark the no-show.")
                },
            )
        }

        // MARK: Guests
        SectionCard(if (stay.petList.size > 1) L("Guests") else L("Guest")) {
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
                    val checks = guest.vaccineCheck
                    if (!checks.isNullOrEmpty()) {
                        FlowLayout(spacing = 6.dp) {
                            for (check in checks) StaysVaccineChip(key = check.key, status = check.status)
                            guest.vaccinesConfirmedByName?.let { name ->
                                Chip(text = LF("Card checked by %@", name), tone = Chip.Tone.info, icon = "check")
                            }
                        }
                    }
                    StaysCarePlanList(items = guest.carePlan?.items ?: emptyList())
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
            // Once in, the plan can change: a medicine the vet just prescribed, another meal.
            if (stay.status == "checked_in" && canManage) {
                RowDivider()
                PMButton(
                    L("Edit the care plan"), systemImage = "clipboard-list", variant = ButtonVariant.outline, size = ButtonSize.compact,
                    modifier = Modifier.padding(16.dp),
                ) { sheet = StaysDetailSheet.plan }
            }
        }
        // What the family signs before check-in, and anything that happened (docs/consents-incidents.md).
        SectionCard(L("Consents")) {
            ConsentsCard(context = consentContext, bare = true, modifier = Modifier.padding(16.dp))
        }
        SectionCard(L("Incidents")) {
            IncidentsCard(
                act = ConsentAct(stayId = stay.id), pets = actPets, familyName = stay.clientName,
                recordable = started, bare = true, recordRequest = incidentRequest, showsRecordButton = false,
                modifier = Modifier.padding(16.dp),
            )
        }

        // MARK: Family and times
        Card(spacing = 0.dp) {
            StaysGrid(
                items = listOf<@Composable () -> Unit>(
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(L("Owner"), style = brandHeading(16.8), color = Theme.inkStrong)
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
                            val contact = stay.emergencyContact
                            val name = contact?.name.nonEmpty
                            if (contact != null && name != null) {
                                Text("${L("Emergency")}: $name · ${contact.phone ?: ""}", style = Theme.text(14), color = Theme.inkSoft)
                            }
                        }
                    },
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(L("Times"), style = brandHeading(16.8), color = Theme.inkStrong)
                            // When they really came and went, once they did; the booked times until then.
                            Text(
                                if (stay.checkedInAt != null) "${L("Arrived")} ${DateText.dayTime(stay.checkedInAt)}"
                                else "${L("Arrives")} ${StaysDay.short(stay.checkInDay)} · ${StaysDay.clock(stay.checkInTime)}",
                                style = Theme.text(14), color = Theme.inkStrong,
                            )
                            Text(
                                if (stay.checkedOutAt != null) "${L("Left")} ${DateText.dayTime(stay.checkedOutAt)}"
                                else "${L("Leaves")} ${StaysDay.short(stay.checkOutDay)} · ${StaysDay.clock(stay.checkOutTime)}",
                                style = Theme.text(14), color = Theme.inkStrong,
                            )
                        }
                    },
                ),
                minimum = 240.dp,
                spacing = 16.dp,
            ) { it() }
        }

        // Notes
        val belongings = stay.belongings ?: emptyList()
        if (stay.ownerInstructions.nonEmpty != null || stay.internalNotes.nonEmpty != null || belongings.isNotEmpty()) {
            SectionCard(L("Notes")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    stay.ownerInstructions.nonEmpty?.let { StaysNoteBlock(label = L("From the owner"), text = it) }
                    stay.internalNotes.nonEmpty?.let { StaysNoteBlock(label = L("For the team"), text = it) }
                    if (belongings.isNotEmpty()) {
                        FlowLayout(spacing = 6.dp) {
                            for (item in belongings) Chip(text = item, tone = Chip.Tone.muted, icon = "backpack")
                        }
                    }
                }
            }
        }

        // MARK: Report cards
        // Incidents have their own list above; an old report card marked "incident" shows there too.
        val cards = (stay.updates ?: emptyList()).filter { it.kind != "incident" }
        SectionCard(
            L("Report cards"),
            subtitle = if (cards.isEmpty()) L("Nothing yet. Report cards sent during the stay show up here and in the family’s app.") else null,
            icon = null,
        ) {
            if (cards.isNotEmpty()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (update in cards) {
                        val about = stay.petList.firstOrNull { it.id == update.stayPetId }
                        StaysReportCardView(
                            update = update, pets = stay.petList,
                            sentToFamily = about?.let { it.linkedToHousehold == true } ?: stay.familyOnApp,
                            zone = workspace.timeZone,
                        )
                    }
                }
            }
        }
        stay.cancellationReason.nonEmpty?.let { StaysNoteBlock(label = L("Why it closed"), text = it) }
    }

    sheet?.let { which ->
        AppSheet(onDismissRequest = { sheet = null }) {
            when (which) {
                StaysDetailSheet.checkIn -> StaysCheckInSheet(stay = stay) { scope.launch { reload() } }
                StaysDetailSheet.checkOut -> StaysCheckOutSheet(stay = stay) { scope.launch { reload() } }
                StaysDetailSheet.dates -> StaysDatesSheet(stay = stay) { scope.launch { reload() } }
                StaysDetailSheet.report -> StaysReportCardSheet(stayId = stay.id, pets = stay.petList, familyOnApp = stay.familyOnApp) { scope.launch { reload() } }
                StaysDetailSheet.plan -> StaysCarePlanSheet(stay = stay) { scope.launch { reload() } }
                StaysDetailSheet.room -> StaysRoomPickSheet(stay = stay, units = units)
                is StaysDetailSheet.close -> {
                    val kind = which.kind
                    ConfirmSheet(
                        title = if (kind == "no_show") L("Mark as no-show?") else L("Cancel this stay?"),
                        message = L("The room is freed for these nights. This cannot be undone."),
                        confirmTitle = if (kind == "no_show") L("Mark no-show") else L("Cancel stay"),
                        destructive = true, askReason = true, reasonRequired = false, reasonLabel = L("Reason"), cancelTitle = L("Back"),
                    ) { reason ->
                        API.patch<StaysStay>("/provider-stays/${stay.id}", body = mapOf("status" to kind, "cancellationReason" to reason))
                        Toast.show(if (kind == "no_show") L("Marked as no-show.") else L("Stay cancelled."))
                        announceDataChanged()
                    }
                }
            }
        }
    }
}

/**
 * The stay's room (web: the select in the summary): another room that takes
 * this kind of stay, or none yet.
 */
@Composable
private fun StaysRoomPickSheet(stay: StaysStay, units: List<StaysUnit>) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val unitId = remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val choices = units.filter { StaysDay.roomTakes(unitKind = it.unitKind, stayKind = stay.kind) || it.id == stay.unitId }

    fun save() {
        working = true
        scope.launch {
            try {
                API.patch<StaysStay>("/provider-stays/${stay.id}", body = mapOf("unitId" to unitId.value))
                Toast.show(L("Room updated."))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not change this stay."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Room"),
        subtitle = stay.petNames,
        systemImage = "bed-double",
        accent = ModalAccent.aqua,
        isDirty = started && unitId.value != stay.unitId,
        primaryTitle = L("Save"),
        isWorking = working,
        primaryDisabled = unitId.value == stay.unitId,
        onPrimary = { save() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ChoiceCards(
            options = listOf(Triple<String?, String, String?>(null, L("No room yet"), null)) +
                choices.map { Triple<String?, String, String?>(it.id, it.name, StaysWords.unitKind(it.kind)) },
            selection = unitId,
        )
    }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        unitId.value = stay.unitId
        started = true
    }
}

/** A care plan's items: what, at what time. */
@Composable
fun StaysCarePlanList(items: List<StaysCareItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    StaysGrid(items = items, minimum = 220.dp, spacing = 6.dp, modifier = modifier) { item ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(StaysWords.taskIcon(item.kind), 16.dp, tint = Theme.blue)
            Text(item.time?.let { StaysDay.clock(it) } ?: "—", style = Theme.text(14, FontWeight.SemiBold).monospacedDigit(), color = Theme.inkSoft)
            Text(item.label, style = Theme.text(14), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A report card as the stay shows it: the photo, who it went to, the mood, the words. */
@Composable
fun StaysReportCardView(
    update: StaysUpdate,
    pets: List<StaysPet>,
    sentToFamily: Boolean,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val incident = update.kind == "incident"
    val pet = pets.firstOrNull { it.id == update.stayPetId }
    val shape = RoundedCornerShape(Theme.cardRadius)
    val eyebrow = brandText(12, FontWeight.Bold).tracking(12 * 0.12)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.surface, shape)
            .border(2.dp, if (incident) Theme.pink else Theme.borderSoft, shape),
    ) {
        APIConfig.assetURL(update.photoUrl)?.let { url ->
            Box(Modifier.fillMaxWidth().height(220.dp).background(Theme.surfaceMuted)) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowLayout(spacing = 6.dp) {
                Text(
                    (if (incident) L("Incident · team only") else (if (sentToFamily) L("Sent to the family") else L("Report card"))).uppercase(),
                    style = eyebrow, color = if (incident) Theme.coralText else Theme.blue,
                )
                if (pet != null) Text("· ${pet.petName}".uppercase(), style = eyebrow, color = Theme.inkSoft)
                StaysWords.mood(update.mood)?.let { mood -> Text("· ${mood.emoji} ${mood.label}".uppercase(), style = eyebrow, color = Theme.inkSoft) }
            }
            update.message.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkStrong) }
            Text(
                "${update.authorName ?: L("Your team")} · ${DateText.dayTime(DateText.parse(update.createdAt), zone = zone)}",
                style = Theme.text(12), color = Theme.inkSoft,
            )
        }
    }
}
