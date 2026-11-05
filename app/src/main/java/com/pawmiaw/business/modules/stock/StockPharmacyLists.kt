package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max

//
//  StockPharmacyLists.kt (iOS StockPharmacyLists.swift)
//
//  The Pharmacy's lists (web pharmacy/prescriptions-tab.tsx, register-tab.tsx,
//  report-tab.tsx):
//  - every prescription of the business, by state (each chip with how many),
//    controlled only, about to expire, by the days they were signed, by folio,
//    medicine, pet or client — 20 at a time, under the day they were signed;
//  - the controlled register: each controlled medicine with its balance
//    against Inventory, one medicine's register for a period (opening, in,
//    out, closing, each line with the balance after it), every movement, and
//    the plain log of everything dispensed against a prescription, with
//    "Owner counselled", the PDF and the CSV;
//  - the period's report.
//  The web's "Print" is the PDF here: the share sheet prints it. Every list
//  sits in one panel, as on the web, and adds 20 rows as it is scrolled.
//

/** Swift's `try?` for a read: null on any failure, cancellation passed on. */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

/** `.onChange(of: value)`: runs on a change, not on the first composition. */
@Composable
private fun OnChange(value: Any?, action: suspend () -> Unit) {
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(value) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        action()
    }
}

/** Foundation's `.whitespaces`: spaces and tabs, not line breaks. */
private fun String.trimmingWhitespaces(): String =
    trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

// MARK: - Prescriptions

data class StockRxFilters(
    val state: String = "active",
    val controlled: Boolean = false,
    val expiring: Boolean = false,
    val q: String = "",
    val from: String = "",
    val to: String = "",
) {
    val narrowed: Boolean get() = q.isNotEmpty() || controlled || expiring || from.isNotEmpty() || to.isNotEmpty()
}

/** Keeps what the first page of a list said besides its rows (the state counts). */
@Stable
class StockCountsBox {
    var counts: Map<String, Int>? by mutableStateOf(null)
}

/** One colour and icon per state chip: the one its pill wears. */
private fun rxStateChip(state: String): Pair<ChipTone, String> = when (state) {
    "active" -> Pair(ChipTone.active, "badge-check")
    "draft" -> Pair(ChipTone.upcoming, "clock-3")
    "expired" -> Pair(ChipTone.closed, "hourglass")
    "used_up" -> Pair(ChipTone.done, "package-check")
    "cancelled" -> Pair(ChipTone.closed, "circle-slash")
    else -> Pair(ChipTone.page, "layout-grid")
}

private val rxStates = listOf("active", "draft", "expired", "used_up", "cancelled", "all")

@Composable
fun StockPrescriptionsTab(
    filters: MutableState<StockRxFilters>,
    rights: StockRxRights,
    onOpen: (String) -> Unit,
    onNew: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // `.onAppear { typed = filters.q }`
    val typed = remember { mutableStateOf(filters.value.q) }
    val box = remember { StockCountsBox() }
    val pager = remember { Pager<StockPrescription>(pageSize = stockPageSize) { _, _ -> Page(emptyList(), 0, false) } }

    suspend fun reload() {
        val f = filters.value
        pager.fetch = { offset, limit ->
            val page = API.get<StockPrescriptionPage>(
                "/provider-prescriptions",
                mapOf(
                    "state" to (if (f.state == "all") null else f.state),
                    "controlled" to (if (f.controlled) "true" else null),
                    "expiring" to (if (f.expiring) "true" else null),
                    "q" to f.q.nonEmpty,
                    "from" to f.from.nonEmpty,
                    "to" to f.to.nonEmpty,
                    "counts" to "true",
                    "limit" to "$limit",
                    "offset" to (if (offset > 0) "$offset" else null),
                ),
            )
            if (offset == 0) box.counts = page.stateCounts
            Page(items = page.items, total = page.total, hasMore = page.hasMore)
        }
        pager.reload()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(prompt = L("Folio, medicine, pet or client"), text = typed)
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rxStates.forEach { state ->
                    val chip = rxStateChip(state)
                    ToneChip(StockRxWords.state(state), icon = chip.second, tone = chip.first, count = box.counts?.get(state), isOn = filters.value.state == state) {
                        filters.value = filters.value.copy(state = state)
                    }
                }
                ChipRule()
                ToneChip(L("Controlled only"), icon = "scale", tone = ChipTone.sky, isOn = filters.value.controlled) {
                    filters.value = filters.value.copy(controlled = !filters.value.controlled)
                }
                ToneChip(L("Expiring soon@rx"), icon = "calendar-clock", tone = ChipTone.waiting, isOn = filters.value.expiring) {
                    val expiring = !filters.value.expiring
                    filters.value = filters.value.copy(expiring = expiring, state = if (expiring) "all" else filters.value.state)
                }
                StockPeriodButton(
                    label = L("Signed on"),
                    from = stateOf({ filters.value.from }, { filters.value = filters.value.copy(from = it) }),
                    to = stateOf({ filters.value.to }, { filters.value = filters.value.copy(to = it) }),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListCard(style = ListCardStyle.panel) {
                val f = filters.value
                when {
                    !pager.loadedOnce && pager.isLoading -> LoadingState()
                    pager.error != null && pager.items.isEmpty() -> EmptyState(
                        systemImage = "pill", title = L("The prescriptions could not be loaded."),
                        actionTitle = L("Try again"), action = { scope.launch { pager.reload() } }, accent = Theme.pink,
                    )
                    pager.items.isEmpty() -> {
                        if (f.narrowed || (box.counts?.get("all") ?: 0) > 0) {
                            EmptyState(
                                systemImage = "pill",
                                title = if (f.narrowed) L("No prescription matches these filters.") else L("No prescriptions in this state."),
                                actionTitle = if (f.narrowed) L("Clear the filters") else null,
                                action = if (f.narrowed) ({ filters.value = StockRxFilters(state = f.state); typed.value = "" }) else null,
                                accent = Theme.mango,
                            )
                        } else {
                            EmptyState(
                                systemImage = "pill", title = L("No prescriptions yet"),
                                message = if (onNew != null) L("Prescriptions appear here when a vet signs one from a visit or a pet’s chart. Start one now.")
                                else L("Prescriptions appear here when a vet signs one from a visit or a pet’s chart."),
                                actionTitle = if (onNew != null) L("New prescription") else null, action = onNew, accent = Theme.mango,
                            )
                        }
                    }
                    else -> Column {
                        rxGroups(pager.items).forEach { group ->
                            DayBand(title = StockText.day(group.second), style = DayBand.Style.caps)
                            group.third.forEachIndexed { index, prescription ->
                                if (index > 0) RowDivider()
                                StockRxListRow(
                                    prescription = prescription,
                                    canViewClients = rights.canViewClients,
                                    modifier = Modifier
                                        .plainButton(label = LF("Open %@: %@", prescription.folio ?: L("the draft"), prescription.medicines)) { onOpen(prescription.id) }
                                        .onScrolledIntoView { pager.loadMoreIfNeeded(prescription) },
                                )
                            }
                            RowDivider()
                        }
                        PagerFooter(pager)
                    }
                }
            }
            val total = pager.total
            if (pager.loadedOnce && total != null) {
                ListCount(shown = pager.items.size, total = total, one = L("1 prescription"), many = L("%d prescriptions"))
            }
        }
    }
    Debounced(typed.value) { text ->
        val trimmed = text.trimmingWhitespaces()
        if (trimmed != filters.value.q) filters.value = filters.value.copy(q = trimmed)
    }
    ReloadsOnScope { reload() }
    OnChange(filters.value) { reload() }
}

/** Rows under the day they were signed (made, for a draft), in the order they came: (key, day, items). */
private fun rxGroups(items: List<StockPrescription>): List<Triple<String, String, List<StockPrescription>>> {
    val groups = mutableListOf<Triple<String, String, MutableList<StockPrescription>>>()
    for (item in items) {
        val day = DateText.parse(item.signedAt ?: item.createdAt)?.let { DateText.dayKey(it, Workspace.shared.timeZone) } ?: ""
        val last = groups.lastOrNull()
        if (last != null && last.second == day) {
            last.third.add(item)
        } else {
            groups.add(Triple("$day-${groups.size}", day, mutableListOf(item)))
        }
    }
    return groups
}

/**
 * One prescription in the Pharmacy's list (web `PrescriptionRow`): whose, which medicines, what is
 * left, then its pills and its state.
 */
@Composable
private fun StockRxListRow(prescription: StockPrescription, canViewClients: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StockPetLine(
                petName = prescription.petName, species = prescription.petSpecies, photoUrl = prescription.petPhotoUrl,
                clientName = prescription.clientName, breed = prescription.petBreed, modifier = Modifier.weight(1f),
            )
            // HStack(spacing: 8) around a Spacer(minLength: 4).
            Spacer(Modifier.width(20.dp))
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
        StockRxSummary(prescription)
    }
}

/** What a prescription row says under whose it is: its medicines, folio, prescriber, what is left, and its pills. */
@Composable
private fun StockRxSummary(prescription: StockPrescription) {
    val left = if (prescription.isSigned) prescription.items.sumOf { it.fill?.remaining ?: 0 } else null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column {
            Text(prescription.medicines, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StockMetaLine(
                listOf(
                    prescription.folio,
                    prescription.prescriberName,
                    if (left != null && prescription.state == "active") LF("%d left", left) else null,
                    if (prescription.validUntil != null && prescription.state == "active") LF("valid until %@", StockText.day(prescription.validUntil)) else null,
                ),
            )
        }
        FlowLayout(spacing = 8.dp) {
            if (prescription.controlled == true) StockControlledChip()
            if (prescription.expiresSoon == true) StockRxChip(text = L("Expires soon"), tone = Theme.lime, strong = true)
            StockRxStatePill(state = prescription.state)
        }
    }
}

/** One prescription as a card of its own (a visit's prescriptions): the same row, on a card. */
@Composable
fun StockPrescriptionRow(prescription: StockPrescription, modifier: Modifier = Modifier) {
    Card(padding = 14.dp, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StockPetLine(
                petName = prescription.petName, species = prescription.petSpecies, photoUrl = prescription.petPhotoUrl,
                clientName = prescription.clientName, breed = prescription.petBreed, modifier = Modifier.weight(1f),
            )
            // HStack's 8 around a Spacer(minLength: 6).
            Spacer(Modifier.width(22.dp))
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
        StockRxSummary(prescription)
    }
}

// MARK: - The controlled register

private enum class RegisterView { medicines, movements, dispensed }

private val registerTypes = listOf("received", "dispensed", "administered", "returned", "waste", "count")
private val rareRegisterTypes = listOf("sold", "opening", "adjustment")

private fun registerTypeLabel(type: String): String = when (type) {
    "received" -> L("Received@register")
    "dispensed" -> L("Dispensed")
    "administered" -> L("Given in the clinic")
    "returned" -> L("Returned@register")
    "waste" -> L("Written off@register")
    "count" -> L("Count@register")
    "sold" -> L("Sold, no prescription on file")
    "opening" -> L("Opening count")
    "adjustment" -> L("Adjustment")
    else -> type
}

/** What each movement was: its colour and icon (web `TYPE_META`, `REGISTER_MOVEMENT_TONE`). */
private fun registerTypeMeta(type: String): Pair<ChipTone, String> = when (type) {
    "received" -> Pair(ChipTone.hue(Hue.mint), "arrow-down-to-line")
    "dispensed" -> Pair(ChipTone.hue(Hue.sky), "pill")
    "administered" -> Pair(ChipTone.hue(Hue.teal), "syringe")
    "returned" -> Pair(ChipTone.hue(Hue.periwinkle), "rotate-ccw")
    "waste" -> Pair(ChipTone.problem, "trash-2")
    "count", "opening" -> Pair(ChipTone.hue(Hue.butter), "clipboard-list")
    "sold" -> Pair(ChipTone.hue(Hue.peach), "arrow-up-from-line")
    else -> Pair(ChipTone.hue(Hue.grey), "clipboard-list")
}

@Composable
fun StockRegisterTab(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    var view by remember { mutableStateOf(RegisterView.medicines) }
    var medicine by remember { mutableStateOf<StockRegisterMedicine?>(null) }
    var medicines by remember { mutableStateOf<StockRegisterMedicines?>(null) }
    var medicinesError by remember { mutableStateOf<String?>(null) }
    val typed = remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val from = remember { mutableStateOf("") }
    val to = remember { mutableStateOf("") }
    val types = remember { mutableStateOf<Set<String>>(emptySet()) }
    val locationId = remember { mutableStateOf<String?>(null) }
    var stock by remember { mutableStateOf<List<StockRow>>(emptyList()) }
    var sheet by remember { mutableStateOf<StockInventorySheet?>(null) }
    var counselling by remember { mutableStateOf<StockCounselTarget?>(null) }

    val canReceive = workspace.can(Permission.inventoryReceive)
    val canCount = workspace.can(Permission.inventoryCount)
    val canCounsel = workspace.can(Permission.salesSell) || workspace.can(Permission.recordsCreate)
    val places = workspace.activeLocations

    fun saved() {
        announceDataChanged()
    }

    suspend fun loadMedicines() {
        try {
            val fresh = API.get<StockRegisterMedicines>(
                "/provider-pharmacy/register/medicines",
                mapOf("q" to (if (view == RegisterView.medicines && medicine == null) q.nonEmpty else null)),
            )
            medicines = fresh
            medicinesError = null
            medicine?.let { chosen -> medicine = fresh.items.firstOrNull { it.catalogItemId == chosen.catalogItemId } ?: chosen }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            medicinesError = errorText(e)
        }
        val w = Workspace.shared
        if (w.can(Permission.inventoryView) && (w.can(Permission.inventoryReceive) || w.can(Permission.inventoryCount))) {
            orNull { API.get<List<StockRow>>("/provider-stock") }?.let { stock = it }
        }
    }

    /** A medicine as Inventory's sheets want it, when the stock list hasn't got it (its stock isn't counted yet). */
    fun asRow(medicine: StockRegisterMedicine): StockRow {
        stock.firstOrNull { it.inventoryItemId != null && it.inventoryItemId == medicine.inventoryItemId }?.let { return it }
        return StockRow(
            inventoryItemId = medicine.inventoryItemId, catalogItemId = medicine.catalogItemId, name = medicine.name, sku = medicine.sku, barcode = null,
            photoUrl = medicine.photoUrl, productKind = "medication", quantityOnHand = medicine.onHand ?: 0, reorderPoint = medicine.reorderPoint ?: 0,
            unitCostAmount = null, lowStock = medicine.lowStock ?: false,
            lots = (medicine.lots ?: emptyList()).map { StockLot(id = it.id, lotCode = it.lotCode, expiresOn = it.expiresOn, quantityOnHand = it.quantityOnHand ?: 0, unitCostAmount = null, receivedAt = null) },
            packs = medicine.packs ?: emptyList(), tracked = medicine.tracked, controlSchedule = medicine.controlSchedule,
        )
    }

    fun actions(medicine: StockRegisterMedicine): StockRegisterActions {
        val ready = medicine.inventoryItemId == null || stock.any { it.inventoryItemId == medicine.inventoryItemId }
        return StockRegisterActions(
            name = medicine.name,
            receive = if (canReceive && medicine.active != false) ({ sheet = StockInventorySheet.receive(asRow(medicine)) }) else null,
            count = if (canCount && medicine.inventoryItemId != null && ready) ({ sheet = StockInventorySheet.count(asRow(medicine)) }) else null,
            waste = if (canCount && medicine.inventoryItemId != null && ready) ({ sheet = StockInventorySheet.waste(asRow(medicine), lotId = null) }) else null,
        )
    }

    @Composable
    fun medicineRow(entry: StockRegisterMedicine) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .plainButton(label = LF("Open the register of %@", entry.name)) {
                        medicine = entry; types.value = emptySet(); typed.value = ""; q = ""
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FlowLayout(spacing = 8.dp) {
                    Text(entry.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    StockRxWords.schedule(entry.controlSchedule)?.let { StockControlledChip(label = it) }
                    entry.discrepancy?.let { discrepancy ->
                        Chip(LF("Doesn’t match Inventory: %@", StockText.signed(discrepancy.difference ?: 0)), color = Theme.destructive, percent = 22.0, dot = true)
                    }
                    if (entry.active == false) Chip(L("Archived@medicine"), color = Theme.borderStrong, percent = 22.0, dot = true)
                }
                val movements = entry.movements ?: 0
                Text(
                    if (entry.tracked != false) {
                        listOf(
                            entry.sku,
                            entry.lastMovementAt?.let { LF("last movement %@", StockText.dateTime(it)) } ?: L("no movements yet"),
                            if (movements == 0) null else if (movements == 1) L("1 movement") else LF("%d movements", movements),
                        ).mapNotNull { it.nonEmpty }.joinToString(" · ")
                    } else {
                        L("Its stock isn’t counted yet: the register starts with its first delivery.")
                    },
                    style = Theme.text(12), color = Theme.inkSoft,
                )
                val lots = entry.lots
                if (!lots.isNullOrEmpty()) {
                    FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 2.dp)) {
                        lots.take(3).forEach { lot ->
                            stockLotChip(code = lot.lotCode, quantity = lot.quantityOnHand ?: 0, expiresOn = lot.expiresOn, today = medicines?.today ?: StockRules.today)
                        }
                        if (lots.size > 3) Text(LF("+%d more", lots.size - 3), style = brandText(11.2, FontWeight.Bold), color = Theme.inkSoft)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(StockText.count(entry.registerBalance ?: 0), style = Theme.heading(24).monospacedDigit(), color = Theme.inkStrong)
                    Text(
                        if (entry.discrepancy != null) LF("in the register · %@ in Inventory", StockText.count(entry.onHand ?: 0)) else L("balance"),
                        style = Theme.text(12), color = Theme.inkSoft,
                    )
                }
                StockRegisterActionButtons(actions = actions(entry), compact = true)
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(
                options = listOf(
                    Segmented.Option(RegisterView.medicines, L("By medicine"), icon = "scale", tone = ChipTone.page),
                    Segmented.Option(RegisterView.movements, L("All movements"), icon = "clipboard-list", tone = ChipTone.page),
                    Segmented.Option(RegisterView.dispensed, L("Everything dispensed"), icon = "pill", tone = ChipTone.page),
                ),
                selection = stateOf({ view }, { next ->
                    if (next != view) {
                        view = next
                        // `.onChange(of: view)`
                        medicine = null; types.value = emptySet(); locationId.value = null; typed.value = ""; q = ""
                    }
                }),
                look = Segmented.Look.raised, label = L("What the register shows"), fill = true,
            )
            if (view != RegisterView.dispensed && medicines?.stockIsBusinessWide == true && ((medicines?.locations ?: 0) > 1 || places.size > 1)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("info", 16.dp, tint = Theme.inkSoft)
                    Text(L("Stock is counted for the whole business, not per location."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
        val chosen = medicine
        when {
            view == RegisterView.dispensed -> StockDispensedLog(
                typed = typed, q = q, from = from, to = to, canCounsel = canCounsel,
                onCounsel = { counselling = it }, onOpen = onOpen,
            )
            view == RegisterView.medicines && chosen == null -> {
                // The medicines and their balances.
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SearchField(prompt = L("Medicine or SKU"), text = typed)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ListCard(style = ListCardStyle.panel) {
                            val list = medicines
                            when {
                                list == null -> {
                                    val error = medicinesError
                                    if (error != null) ErrorState(message = error) { loadMedicines() } else LoadingState()
                                }
                                list.items.isEmpty() -> EmptyState(
                                    systemImage = "scale",
                                    title = if (q.isEmpty()) L("No controlled medicines yet") else LF("No controlled medicine matches “%@”.", q),
                                    message = if (q.isEmpty()) L("A medicine is controlled when its Catalog item has a control class. Its register starts with its first delivery in Inventory.") else null,
                                    accent = Theme.mango,
                                )
                                else -> list.items.forEachIndexed { index, entry ->
                                    if (index > 0) RowDivider()
                                    medicineRow(entry)
                                }
                            }
                        }
                        medicines?.items?.let { items ->
                            ListCount(shown = items.size, total = items.size, one = L("1 controlled medicine"), many = L("%d controlled medicines"))
                        }
                    }
                }
            }
            else -> StockRegisterMovements(
                medicine = chosen, medicines = medicines?.items ?: emptyList(), typed = typed, q = q, from = from, to = to, types = types,
                locationId = locationId, places = places, canCounsel = canCounsel, actions = chosen?.let { actions(it) },
                onBack = { medicine = null; types.value = emptySet(); typed.value = ""; q = "" },
                onPick = { picked -> medicine = picked; types.value = emptySet(); typed.value = ""; q = "" },
                onCounsel = { counselling = it }, onOpen = onOpen,
            )
        }
        Text(L("Format based on public sources; a professional must review it before use."), style = Theme.text(12), color = Theme.inkSoft)
    }
    Debounced(typed.value) { text ->
        val trimmed = text.trimmingWhitespaces()
        if (trimmed != q) q = trimmed
    }
    ReloadsOnScope { loadMedicines() }
    OnChange(q) { if (view == RegisterView.medicines && medicine == null) loadMedicines() }
    sheet?.let { which ->
        AppSheet(onDismissRequest = { sheet = null }) {
            when (which) {
                is StockInventorySheet.receive -> StockReceiveSheet(preset = which.row, severalLocations = places.size > 1, onSaved = ::saved)
                is StockInventorySheet.count -> StockAdjustSheet(row = which.row, kind = StockAdjustKind.count, lotId = null, onSaved = ::saved)
                is StockInventorySheet.waste -> StockAdjustSheet(row = which.row, kind = StockAdjustKind.waste, lotId = which.lotId, onSaved = ::saved)
                else -> {}
            }
        }
    }
    counselling?.let { target ->
        AppSheet(onDismissRequest = { counselling = null }) { StockCounselSheet(target = target) }
    }
}

class StockRegisterActions(
    val name: String,
    val receive: (() -> Unit)?,
    val count: (() -> Unit)?,
    val waste: (() -> Unit)?,
)

/** Receive, count, write off: round icons on a list's row, labelled buttons over one medicine's register. */
@Composable
private fun StockRegisterActionButtons(actions: StockRegisterActions, compact: Boolean = false) {
    if (actions.receive != null || actions.count != null || actions.waste != null) {
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.receive?.let { StockIconButton(icon = "package-plus", label = LF("Receive %@", actions.name), action = it) }
                actions.count?.let { StockIconButton(icon = "clipboard-list", label = LF("Count %@", actions.name), action = it) }
                actions.waste?.let { StockIconButton(icon = "trash-2", label = LF("Write off %@", actions.name), action = it) }
            }
        } else {
            FlowLayout(spacing = 8.dp) {
                actions.receive?.let { PMButton(L("Receive"), systemImage = "package-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, action = it) }
                actions.count?.let { PMButton(L("Count"), systemImage = "clipboard-list", variant = ButtonVariant.outline, size = ButtonSize.compact, action = it) }
                actions.waste?.let { PMButton(L("Write off"), systemImage = "trash-2", variant = ButtonVariant.outline, size = ButtonSize.compact, action = it) }
            }
        }
    }
}

/**
 * `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)], spacing:)`: as many equal columns
 * as fit at `minimum` wide.
 */
@Composable
private fun AdaptiveGrid(minimum: Dp, spacing: Dp, modifier: Modifier = Modifier, cells: List<@Composable () -> Unit>) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        FixedGrid(columns = columns, spacing = spacing, cells = cells)
    }
}

/** `LazyVGrid` of `count` flexible columns: a row is as tall as its tallest cell. */
@Composable
private fun FixedGrid(columns: Int, spacing: Dp, modifier: Modifier = Modifier, cells: List<@Composable () -> Unit>) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        cells.chunked(columns).forEach { chunk ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                chunk.forEach { cell -> Box(Modifier.weight(1f).fillMaxHeight()) { cell() } }
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One medicine's register, or every controlled medicine's movements, for a period. */
@Composable
private fun StockRegisterMovements(
    medicine: StockRegisterMedicine?,
    medicines: List<StockRegisterMedicine>,
    typed: MutableState<String>,
    q: String,
    from: MutableState<String>,
    to: MutableState<String>,
    types: MutableState<Set<String>>,
    locationId: MutableState<String?>,
    places: List<ProviderLocation>,
    canCounsel: Boolean,
    actions: StockRegisterActions?,
    onBack: () -> Unit,
    onPick: (StockRegisterMedicine) -> Unit,
    onCounsel: (StockCounselTarget) -> Unit,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var limit by remember { mutableIntStateOf(stockPageSize) }
    var data by remember { mutableStateOf<StockControlledRegister?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }

    val single = medicine != null
    val query: Map<String, String?> = mapOf(
        "catalogItemId" to medicine?.catalogItemId,
        "from" to from.value.nonEmpty,
        "to" to to.value.nonEmpty,
        "types" to (if (types.value.isEmpty()) null else types.value.sorted().joinToString(",")),
        "locationId" to locationId.value,
        "q" to q.nonEmpty,
    )
    val key = listOf(medicine?.catalogItemId ?: "", from.value, to.value, types.value.sorted().joinToString(","), locationId.value ?: "", q).joinToString("|")
    val title = if (single) (data?.medicine?.name ?: medicine?.name ?: "") else L("Every controlled medicine")
    val period = data?.let { "${StockText.day(it.from)} – ${StockText.day(it.to)}" } ?: ""
    // The latest query, for a load started by an older composition (Swift reads its computed property).
    val latestQuery = rememberUpdatedState(query)

    suspend fun load() {
        val sent = latestQuery.value + ("limit" to "$limit")
        try {
            data = API.get<StockControlledRegister>("/provider-pharmacy/register", sent)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadError = errorText(e)
        }
    }

    /** One of the four balances (web: a muted box, 12 bold label, Fredoka 24). */
    @Composable
    fun balance(label: String, value: String) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            Text(value, style = Theme.heading(24).monospacedDigit(), color = Theme.inkStrong)
        }
    }

    /** The medicine's name, its opening, in, out and closing, and whether Inventory agrees. */
    @Composable
    fun summary(medicine: StockRegisterMedicine) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowLayout(spacing = 12.dp) {
                PMButton(L("All medicines"), systemImage = "arrow-left", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onBack)
                if (actions != null) StockRegisterActionButtons(actions = actions)
            }
            val shape = RoundedCornerShape(Theme.cardRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surface, shape)
                    .border(2.dp, Theme.borderSoft, shape)
                    .padding(16.dp),
            ) {
                FlowLayout(spacing = 8.dp) {
                    Text(title, style = Theme.heading(20), color = Theme.inkStrong)
                    StockRxWords.schedule(data?.medicine?.controlSchedule ?: medicine.controlSchedule)?.let { StockControlledChip(label = it) }
                }
                val d = data
                val opening = d?.openingBalance
                val closing = d?.closingBalance
                if (d != null && opening != null && closing != null) {
                    AdaptiveGrid(
                        minimum = 130.dp, spacing = 12.dp, modifier = Modifier.padding(top = 12.dp),
                        cells = listOf(
                            { balance(L("Opening balance"), StockText.count(opening)) },
                            { balance(L("In"), "+${StockText.count(d.unitsIn ?: 0)}") },
                            { balance(L("Out"), "−${StockText.count(d.unitsOut ?: 0)}") },
                            { balance(L("Closing balance"), StockText.count(closing)) },
                        ),
                    )
                }
                data?.medicine?.onHand?.let { onHand ->
                    Text(
                        LF("%@ · Inventory has %@ on hand today.", period, StockText.count(onHand)),
                        style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            data?.discrepancy?.let { discrepancy ->
                NoticeCard(
                    tone = NoticeCard.Tone.problem, title = L("The register and Inventory don’t match."),
                    message = LF(
                        "The register adds up to %@; Inventory has %@ on hand: a difference of %@. Count the medicine to set it right.",
                        StockText.count(discrepancy.registerBalance ?: 0), StockText.count(discrepancy.onHand ?: 0), StockText.signed(discrepancy.difference ?: 0),
                    ),
                    icon = "triangle-alert",
                )
            }
        }
    }

    @Composable
    fun lineRow(line: StockRegisterLine) {
        val lot = if (line.lotCode != null || line.lotExpiresOn != null) {
            listOfNotNull(LF("Lot %@", line.lotCode ?: "—"), line.lotExpiresOn?.let { LF("exp. %@", StockText.day(it)) }).joinToString(" · ")
        } else {
            null
        }
        val who = listOf(line.petName, line.clientName).mapNotNull { it.nonEmpty }.joinToString(" · ")
        val prescriber = listOf(line.prescriberName, line.prescriberCredential).mapNotNull { it.nonEmpty }.joinToString(" · ")
        val actor: String? = line.actorName?.let { name ->
            if (line.type == "dispensed") LF("handed over by %@", name) else if (line.type == "administered") LF("given by %@", name) else LF("recorded by %@", name)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowLayout(spacing = 8.dp) {
                    Chip(registerTypeLabel(line.type), color = registerTypeMeta(line.type).first.color, percent = 22.0, dot = true)
                    Text(StockText.dateTime(line.occurredAt), style = Theme.text(12), color = Theme.inkSoft)
                    if (!single) line.productName?.let { Text(it, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong) }
                }
                if (who.isNotEmpty()) Text(who, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                if (prescriber.isNotEmpty()) Text(LF("Prescribed by %@", prescriber), style = Theme.text(12), color = Theme.inkSoft)
                line.doseText.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
                line.note.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkStrong) }
                StockMetaLine(listOf(lot, line.prescriptionFolio, line.documentFolio, actor, line.witnessName?.let { LF("witness: %@", it) }, line.locationName))
                if (line.type == "dispensed" || line.prescriptionId != null) {
                    FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 2.dp)) {
                        val dispenseId = line.dispenseId
                        if (line.counselledAt != null) {
                            StockRxChip(text = L("Counselled") + (line.counselNote.nonEmpty?.let { ": $it" } ?: ""), tone = Theme.lime, icon = "hand-heart")
                        } else if (line.type == "dispensed" && dispenseId != null && canCounsel) {
                            PMButton(L("Record counselling"), systemImage = "hand-heart", variant = ButtonVariant.ghost, size = ButtonSize.modal) {
                                onCounsel(StockCounselTarget(dispenseId = dispenseId, label = line.productName ?: "", petName = line.petName))
                            }
                        }
                        line.prescriptionId?.let { prescriptionId ->
                            PMButton(L("Open prescription"), systemImage = "file-text", variant = ButtonVariant.ghost, size = ButtonSize.modal) { onOpen(prescriptionId) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val quantity = line.quantity ?: 0
                Text(
                    StockText.signed(quantity),
                    style = Theme.heading(20).monospacedDigit(),
                    color = if (quantity < 0) Theme.inkStrong else Theme.mix(ProviderState.done.color, 70.0, Theme.inkStrong),
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.weight(1f))
                Text(LF("balance %@", StockText.count(line.balanceAfter ?: 0)), style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (single && medicine != null) summary(medicine)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(prompt = L("Patient, folio, lot or note"), text = typed)
            FlowLayout(spacing = 8.dp) {
                StockPeriodButton(label = L("Period"), noneLabel = L("Last 30 days"), from = from, to = to)
                if (!single && medicines.isNotEmpty()) {
                    AppMenu(label = { FilterMenuLabel(title = L("Every medicine")) }, accessibilityLabel = L("Open one medicine’s register")) {
                        medicines.filter { it.tracked != false }.forEach { entry -> item(entry.name) { onPick(entry) } }
                    }
                }
                if (places.size > 1) {
                    AppMenu(label = {
                        FilterMenuLabel(title = places.firstOrNull { it.id == locationId.value }?.name ?: L("Every location"), selected = locationId.value != null)
                    }) {
                        item(L("Every location")) { locationId.value = null }
                        places.forEach { place -> item(place.name) { locationId.value = place.id } }
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                (registerTypes + rareRegisterTypes.filter { (data?.typeCounts?.get(it) ?: 0) > 0 || it in types.value }).forEach { type ->
                    val meta = registerTypeMeta(type)
                    ToneChip(registerTypeLabel(type), icon = meta.second, tone = meta.first, count = data?.typeCounts?.get(type), isOn = type in types.value) {
                        types.value = if (type in types.value) types.value - type else types.value + type
                    }
                }
            }
            if (places.size > 1 && locationId.value != null) {
                Text(L("Showing the movements recorded at this location. The balance is the whole business’s."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowLayout(spacing = 8.dp) {
                if (single) {
                    Disabled(data == null) {
                        DocumentButton(
                            title = L("Download PDF"), systemImage = "file-down", path = "/provider-pharmacy/register/pdf", query = query,
                            fileName = "${L("Controlled register")} $title", size = ButtonSize.regular,
                        )
                    }
                }
                Disabled((data?.total ?: 0) == 0) {
                    DocumentButton(
                        title = L("Download CSV"), systemImage = "file-spreadsheet", path = "/provider-pharmacy/register/csv", query = query,
                        fileName = "${L("Controlled register")} $title", ext = "csv", size = ButtonSize.regular,
                    )
                }
            }
            ListCard(style = ListCardStyle.panel) {
                val d = data
                when {
                    d == null -> {
                        val error = loadError
                        if (error != null) ErrorState(message = error) { load() } else LoadingState()
                    }
                    d.items.isEmpty() -> {
                        val filtered = types.value.isNotEmpty() || q.isNotEmpty() || locationId.value != null
                        EmptyState(
                            systemImage = "clipboard-list",
                            title = if (filtered) L("No movement matches these filters.") else L("No movements in this period"),
                            message = if (filtered) null else L("Deliveries, sales against a prescription, doses given in the clinic, write-offs and counts of controlled medicines show here as they happen."),
                            accent = Theme.mango,
                        )
                    }
                    else -> Column {
                        d.items.forEachIndexed { index, line ->
                            if (index > 0) RowDivider()
                            lineRow(line)
                        }
                        LoadMoreRow(hasMore = (d.total ?: d.items.size) > d.items.size, busy = loadingMore) {
                            if (loadingMore) return@LoadMoreRow
                            limit += stockPageSize
                            loadingMore = true
                            scope.launch { load(); loadingMore = false }
                        }
                    }
                }
            }
            data?.let { d ->
                if (d.items.isNotEmpty()) ListCount(shown = d.items.size, total = d.total ?: d.items.size, one = L("1 movement"), many = L("%d movements"))
            }
            if (data?.truncated == true) {
                Text(L("This period has more movements than can be listed at once: shorten it to see them all."), style = Theme.text(14), color = Theme.inkSoft)
            }
            Text(L("Read from Inventory: it can’t be edited here. A correction is a new movement — a count, a write-off, a return."), style = Theme.text(12), color = Theme.inkSoft)
        }
    }
    ReloadsOnScope { load() }
    OnChange(key) {
        limit = stockPageSize
        load()
    }
}

/** "Everything dispensed": the dispensing log of every prescription medicine, controlled or not. */
@Composable
private fun StockDispensedLog(
    typed: MutableState<String>,
    q: String,
    from: MutableState<String>,
    to: MutableState<String>,
    canCounsel: Boolean,
    onCounsel: (StockCounselTarget) -> Unit,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var limit by remember { mutableIntStateOf(stockPageSize) }
    var data by remember { mutableStateOf<StockDispenseLog?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }

    val key = listOf(q, from.value, to.value).joinToString("|")
    val query: Map<String, String?> = mapOf("from" to from.value.nonEmpty, "to" to to.value.nonEmpty, "q" to q.nonEmpty)
    val period = data?.let { "${StockText.day(it.from)} – ${StockText.day(it.to)}" } ?: ""
    // The latest query, for a load started by an older composition (Swift reads its computed property).
    val latestQuery = rememberUpdatedState(query)

    suspend fun load() {
        val sent = latestQuery.value + ("limit" to "$limit")
        try {
            data = API.get<StockDispenseLog>("/provider-pharmacy/dispenses", sent)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadError = errorText(e)
        }
    }

    @Composable
    fun row(entry: StockDispense, mayCounsel: Boolean) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowLayout(spacing = 8.dp) {
                    val returned = entry.kind == "return"
                    Chip(if (returned) L("Returned@register") else L("Dispensed"), color = if (returned) Theme.butter else Theme.aqua, percent = 22.0, dot = true)
                    Text(StockText.dateTime(entry.occurredAt), style = Theme.text(12), color = Theme.inkSoft)
                    Text(entry.label ?: "", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    StockRxWords.schedule(entry.controlSchedule)?.let { StockControlledChip(label = it) }
                }
                Text(
                    listOf(entry.petName, entry.clientName).mapNotNull { it.nonEmpty }.joinToString(" · ").nonEmpty ?: "—",
                    style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong,
                )
                val lots = entry.lots ?: emptyList()
                StockMetaLine(
                    listOf(
                        entry.prescriberName?.let { LF("Prescribed by %@", listOf(it, entry.prescriberCredential).mapNotNull { part -> part.nonEmpty }.joinToString(" · ")) },
                        if (lots.isEmpty()) null else lots.joinToString(", ") { lot ->
                            LF("Lot %@", lot.code ?: "—") + (lot.expiresOn?.let { " (${LF("exp. %@", StockText.day(it))})" } ?: "")
                        },
                        entry.prescriptionFolio,
                        entry.documentFolio,
                        entry.dispensedByName?.let { LF("handed over by %@", it) },
                    ),
                )
                FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 2.dp)) {
                    if (entry.kind != "return") {
                        if (entry.counselledAt != null) {
                            StockRxChip(text = L("Counselled") + (entry.counselNote.nonEmpty?.let { ": $it" } ?: ""), tone = Theme.lime, icon = "hand-heart")
                        } else if (mayCounsel) {
                            PMButton(L("Record counselling"), systemImage = "hand-heart", variant = ButtonVariant.ghost, size = ButtonSize.modal) {
                                onCounsel(StockCounselTarget(dispenseId = entry.id, label = entry.label ?: "", petName = entry.petName))
                            }
                        }
                    }
                    entry.prescriptionId?.let { prescriptionId ->
                        PMButton(L("Open prescription"), systemImage = "file-text", variant = ButtonVariant.ghost, size = ButtonSize.modal) { onOpen(prescriptionId) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("${entry.quantity ?: 0}", style = Theme.heading(20).monospacedDigit(), color = Theme.inkStrong, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.weight(1f))
                entry.sold.nonEmpty?.let { Text(LF("sold %@", it), style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.alignByBaseline()) }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(prompt = L("Medicine, patient, client or folio"), text = typed)
            FlowLayout(spacing = 8.dp) {
                StockPeriodButton(label = L("Period"), noneLabel = L("Last 30 days"), from = from, to = to)
                Disabled((data?.total ?: 0) == 0) {
                    DocumentButton(
                        title = L("Download PDF"), systemImage = "file-down", path = "/provider-pharmacy/dispenses/pdf", query = query,
                        fileName = "${L("Dispensing log")} $period", size = ButtonSize.regular,
                    )
                }
                Disabled((data?.total ?: 0) == 0) {
                    DocumentButton(
                        title = L("Download CSV"), systemImage = "file-spreadsheet", path = "/provider-pharmacy/dispenses/csv", query = query,
                        fileName = "${L("Dispensing log")} $period", ext = "csv", size = ButtonSize.regular,
                    )
                }
            }
        }
        Text(
            L("Written by every sale, visit and stay bill that hands a medicine out against a prescription. It can’t be edited: a return is a new line."),
            style = Theme.text(12), color = Theme.inkSoft,
        )
        ListCard(style = ListCardStyle.panel) {
            val d = data
            when {
                d == null -> {
                    val error = loadError
                    if (error != null) ErrorState(message = error) { load() } else LoadingState()
                }
                d.items.isEmpty() -> EmptyState(
                    systemImage = "pill",
                    title = if (q.isEmpty()) L("Nothing dispensed in this period.") else L("Nothing dispensed matches that."),
                    message = if (q.isEmpty()) L("Each medicine handed out against a prescription — at the Store, on a visit’s bill or an invoice — is listed here.") else null,
                    accent = Theme.mango,
                )
                else -> {
                    val mayCounsel = d.canCounsel ?: canCounsel
                    Column {
                        d.items.forEachIndexed { index, entry ->
                            if (index > 0) RowDivider()
                            row(entry, mayCounsel)
                        }
                        LoadMoreRow(hasMore = (d.total ?: d.items.size) > d.items.size, busy = loadingMore) {
                            if (loadingMore) return@LoadMoreRow
                            limit += stockPageSize
                            loadingMore = true
                            scope.launch { load(); loadingMore = false }
                        }
                    }
                }
            }
        }
        data?.let { d ->
            if (d.items.isNotEmpty()) ListCount(shown = d.items.size, total = d.total ?: d.items.size, one = L("1 line"), many = L("%d lines"))
        }
    }
    ReloadsOnScope { load() }
    OnChange(key) {
        limit = stockPageSize
        load()
    }
}

// MARK: - Owner counselled

data class StockCounselTarget(
    val dispenseId: String,
    val label: String,
    val petName: String?,
) {
    val id: String get() = dispenseId
}

/** "Owner counselled" on a dispense, with what was explained (optional). */
@Composable
fun StockCounselSheet(target: StockCounselTarget, onSaved: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val note = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    suspend fun save() {
        saving = true
        try {
            API.send("POST", "/provider-pharmacy/dispenses/${target.dispenseId}/counsel", mapOf("note" to note.value.nonEmpty))
            Toast.show(L("Counselling recorded."))
            onSaved?.invoke()
            announceDataChanged()
            dismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(StockRxWords.error(e))
        } finally {
            saving = false
        }
    }

    ProviderModal(
        title = L("Owner counselled"),
        subtitle = listOf(target.label, target.petName).mapNotNull { it.nonEmpty }.joinToString(" · "),
        systemImage = "hand-heart", accent = ModalAccent.aqua, isDirty = note.value.isNotEmpty(), primaryTitle = L("Record counselling"), isWorking = saving,
        primaryIcon = "hand-heart", onPrimary = { scope.launch { save() } },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        PMTextField(L("What was explained"), text = note, hint = L("How to give it, what to watch for, when to come back."), optional = true, multiline = true)
    }
}

// MARK: - Report

@Composable
fun StockReportTab(modifier: Modifier = Modifier) {
    val sizeClass = LocalSizeClass.current
    val scope = rememberCoroutineScope()
    val from = remember { mutableStateOf("") }
    val to = remember { mutableStateOf("") }
    var data by remember { mutableStateOf<StockPharmacyReport?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val query: Map<String, String?> = mapOf("from" to from.value.nonEmpty, "to" to to.value.nonEmpty)
    val period = data?.let { "${StockText.day(it.from)} – ${StockText.day(it.to)}" } ?: ""
    val empty = data?.let {
        (it.totals.dispenses ?: 0) == 0 && (it.totals.prescriptionsSigned ?: 0) == 0 && (it.totals.units ?: 0) == 0 && it.refills.isEmpty()
    } ?: false

    suspend fun load() {
        try {
            data = API.get<StockPharmacyReport>("/provider-pharmacy/report", mapOf("from" to from.value.nonEmpty, "to" to to.value.nonEmpty))
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadError = errorText(e)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowLayout(spacing = 8.dp) {
                StockPeriodButton(label = L("Period"), noneLabel = L("Last 30 days"), from = from, to = to)
                if (period.isNotEmpty()) {
                    Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
                        Text(period, style = Theme.text(14), color = Theme.inkSoft)
                    }
                }
            }
            FlowLayout(spacing = 8.dp) {
                Disabled(data == null || empty) {
                    DocumentButton(
                        title = L("Download PDF"), systemImage = "file-down", path = "/provider-pharmacy/report/pdf", query = query,
                        fileName = "${L("Pharmacy report")} $period", size = ButtonSize.regular,
                    )
                }
                Disabled(data == null || empty) {
                    DocumentButton(
                        title = L("Download CSV"), systemImage = "file-spreadsheet", path = "/provider-pharmacy/report/csv", query = query,
                        fileName = "${L("Pharmacy report")} $period", ext = "csv", size = ButtonSize.regular,
                    )
                }
            }
        }
        val report = data
        when {
            report == null -> {
                val error = loadError
                if (error != null) {
                    ListCard(style = ListCardStyle.panel) {
                        EmptyState(
                            systemImage = "clipboard-list", title = L("The report could not be loaded."), message = error,
                            actionTitle = L("Try again"), action = { scope.launch { load() } }, accent = Theme.pink,
                        )
                    }
                } else {
                    LoadingState()
                }
            }
            empty -> ListCard(style = ListCardStyle.panel) {
                EmptyState(
                    systemImage = "clipboard-list", title = L("Nothing in this period."),
                    message = L("Once prescriptions are signed and their medicines handed out, the period’s numbers show here. Try a longer period."),
                    accent = Theme.mango,
                )
            }
            else -> ReportBody(report, regular = sizeClass == SizeClass.regular)
        }
    }
    ReloadsOnScope { load() }
    OnChange("${from.value}|${to.value}") { load() }
}

@Composable
private fun ReportBody(data: StockPharmacyReport, regular: Boolean) {
    // Revenue is business finances: null for anyone else, and then not shown.
    val money: (Int?) -> String? = { amount -> amount?.let { Money.format(it, currency = data.currency) } }
    val totals = data.totals
    val tiles = mutableListOf<@Composable () -> Unit>(
        { ReportTile(L("Prescriptions signed"), StockText.count(totals.prescriptionsSigned ?: 0), null) },
        {
            ReportTile(
                L("Dispenses"), StockText.count(totals.dispenses ?: 0),
                if ((totals.patients ?: 0) == 1) L("1 patient") else LF("%@ patients", StockText.count(totals.patients ?: 0)),
            )
        },
        { ReportTile(L("Units dispensed"), StockText.count(totals.units ?: 0), LF("%@ controlled", StockText.count(totals.controlledUnits ?: 0))) },
    )
    money(totals.revenueAmount)?.let { revenue ->
        tiles.add { ReportTile(L("Revenue from prescription medicines"), revenue, L("What the bills charged for them, less returns")) }
    }
    tiles.add {
        ReportTile(
            L("Owners counselled"), StockText.count(totals.counselled ?: 0),
            if ((totals.dispenses ?: 0) == 1) L("of 1 dispense") else LF("of %@ dispenses", StockText.count(totals.dispenses ?: 0)),
        )
    }
    FixedGrid(columns = if (regular) 5 else 2, spacing = 12.dp, cells = tiles)

    ReportSection(L("By medicine")) {
        if (data.byProduct.isEmpty()) {
            ReportNone(L("Nothing dispensed in this period."))
        } else {
            data.byProduct.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowLayout(spacing = 8.dp) {
                        Text(row.label, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                        if (row.controlled == true) StockControlledChip()
                    }
                    FlowLayout(spacing = 16.dp) {
                        val units = row.units ?: 0
                        val heading = Theme.heading(16).toSpanStyle().copy(color = Theme.inkStrong)
                        Text(
                            buildAnnotatedString {
                                withStyle(heading) { append(StockText.count(units)) }
                                append(" " + (if (units == 1) L("unit") else L("units")))
                            },
                            style = Theme.text(14), color = Theme.inkSoft,
                        )
                        Text(
                            if ((row.dispenses ?: 0) == 1) L("1 dispense") else LF("%@ dispenses", StockText.count(row.dispenses ?: 0)),
                            style = Theme.text(14), color = Theme.inkSoft,
                        )
                        Text(LF("%@ counselled", StockText.count(row.counselled ?: 0)), style = Theme.text(14), color = Theme.inkSoft)
                        money(row.revenueAmount)?.let { Text(it, style = Theme.heading(16), color = Theme.inkStrong) }
                    }
                }
            }
        }
    }
    ReportSection(L("By prescriber")) {
        if (data.byPrescriber.isEmpty()) {
            ReportNone(L("Nothing dispensed in this period."))
        } else {
            data.byPrescriber.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(row.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.weight(1f))
                    Text(
                        if ((row.dispenses ?: 0) == 1) L("1 dispense") else LF("%@ dispenses", StockText.count(row.dispenses ?: 0)),
                        style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.alignByBaseline(),
                    )
                    money(row.revenueAmount)?.let { Text(it, style = Theme.heading(16), color = Theme.inkStrong, modifier = Modifier.alignByBaseline()) }
                }
            }
        }
    }
    ReportSection(L("Refill requests")) {
        if (data.refills.isEmpty()) {
            ReportNone(L("No refill requests in this period."))
        } else {
            data.refills.keys.sorted().forEachIndexed { index, status ->
                if (index > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(StockRxWords.refill(status), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                    Text(StockText.count(data.refills[status] ?: 0), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
                }
            }
        }
    }
}

/** A section of the report: its name in Fredoka 18 over its panel. */
@Composable
private fun ReportSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Theme.heading(18), color = Theme.inkStrong)
        ListCard(style = ListCardStyle.panel) { content() }
    }
}

@Composable
private fun ReportNone(text: String) {
    Text(text, style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
}

/** A number of the period (web: a 2 pt bordered card, 12 bold label, Fredoka 24, a 12 hint). */
@Composable
private fun ReportTile(label: String, value: String, hint: String?) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Theme.surface, shape)
            .border(2.dp, Theme.borderSoft, shape)
            .padding(14.dp),
    ) {
        Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
        FittedText(value, modifier = Modifier.padding(top = 4.dp), style = Theme.heading(24).monospacedDigit(), color = Theme.inkStrong, minimumScaleFactor = 0.6f)
        if (hint != null) Text(hint, style = Theme.text(12), color = Theme.inkSoft)
    }
}
