package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

//
//  StockInventory.kt (iOS StockInventory.swift)
//
//  Inventario (web provider.inventory.tsx → components/app/inventory/inventory-page.tsx):
//  stock by product — what is on the shelf, its lots and expiry, what it is
//  worth at cost (business finances only), what is low. Deliveries, counts,
//  write-offs and each product's stock settings are recorded here; sales,
//  returns and visits move the stock on their own. Stock is the whole
//  business's, not a location's. The search takes a name, a SKU or a
//  barcode, typed or read with the camera.
//
//  The page is the web's: the module header with its four numbers (the low
//  and expiring ones filter the list), the search, the toned filter chips,
//  then one panel of rows. The list shows 20 rows and adds 20 as it is
//  scrolled (the owner's rule); the counts are of the whole list.
//

@Composable
fun InventoryScreen(modifier: Modifier = Modifier) {
    StockInventoryPage(root = true, modifier = modifier)
}

/** The sheets Inventory opens. */
sealed class StockInventorySheet {
    data class receive(val row: StockRow?) : StockInventorySheet()
    data class count(val row: StockRow) : StockInventorySheet()
    data class waste(val row: StockRow, val lotId: String?) : StockInventorySheet()
    data class detail(val row: StockRow, val history: Boolean) : StockInventorySheet()
    data class settings(val row: StockRow) : StockInventorySheet()

    val id: String
        get() = when (this) {
            is receive -> "receive-${row?.catalogItemId ?: "new"}"
            is count -> "count-${row.catalogItemId}"
            is waste -> "waste-${row.catalogItemId}-${lotId ?: ""}"
            is detail -> "detail-${row.catalogItemId}-$history"
            is settings -> "settings-${row.catalogItemId}"
        }
}

/** SwiftUI's negative top padding (`.padding(.top, -10)`): the view and what follows move up. */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout(placeable.width, max(0, placeable.height - shift)) { placeable.place(0, -shift) }
}

/** SwiftUI `ViewThatFits(in: .horizontal)`: the first that fits the width, else the second. */
@Composable
private fun ViewThatFits(modifier: Modifier = Modifier, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val probe = subcompose("probe", first).map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val fits = (probe.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth
        val placeables = subcompose(if (fits) "first" else "second", if (fits) first else second).map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = max(constraints.minWidth, placeables.maxOfOrNull { it.width } ?: 0)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** Inventory itself; the Pharmacy opens it on low or expiring medicines. */
@Composable
fun StockInventoryPage(
    initialFilter: StockFilter = StockFilter.all,
    initialMedicinesOnly: Boolean = false,
    /** The section's own page (under the shell's bar), or opened from the Pharmacy. */
    root: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val wide = LocalSizeClass.current == SizeClass.regular
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var whole by remember { mutableStateOf<List<StockRow>?>(null) }
    var found by remember { mutableStateOf<List<StockRow>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val query = remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    // Swift `.onAppear` (once): the filter the page opens on.
    var filter by remember { mutableStateOf(initialFilter) }
    var medicinesOnly by remember { mutableStateOf(initialMedicinesOnly) }
    var shownCount by remember { mutableIntStateOf(stockPageSize) }
    var sheet by remember { mutableStateOf<StockInventorySheet?>(null) }

    val canView = workspace.can(Permission.inventoryView)
    val canReceive = workspace.can(Permission.inventoryReceive)
    val canCount = workspace.can(Permission.inventoryCount)
    val canEdit = workspace.can(Permission.inventoryEdit)
    val readOnly = !canReceive && !canCount && !canEdit
    val today = StockRules.today

    /** A product whose stock is no longer counted keeps its record; it is not Inventory's to list. */
    fun shown(rows: List<StockRow>?): List<StockRow> =
        (rows ?: emptyList()).filter { it.tracked != false && (!medicinesOnly || StockRules.isMedicine(it)) }

    val rows = shown(if (searched.isEmpty()) whole else found)
    val wholeRows = shown(whole)
    val listed = rows.filter { StockRules.matches(it, filter, today = today) }

    /** What rows are worth at cost; null when the cost is not this person's to see. */
    fun value(rows: List<StockRow>): Int? {
        var total = 0
        for (row in rows) {
            val amount = row.stockValueAmount ?: return null
            total += amount
        }
        return total
    }

    val seesCost = workspace.moneyLevel >= MoneyLevel.finances && value(wholeRows) != null

    suspend fun search() {
        if (searched.isEmpty()) {
            found = null
            return
        }
        try {
            found = API.get<List<StockRow>>("/provider-stock", mapOf("q" to searched))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = errorText(error)
        }
    }

    suspend fun load() {
        if (!canView) return
        try {
            whole = API.get<List<StockRow>>("/provider-stock")
            loadError = null
            if (searched.isNotEmpty()) search()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = errorText(error)
        }
    }

    /**
     * A code read by the camera is searched as the product keeps it: the GTIN-14 of a label with a
     * leading 0 is the EAN-13 under the bars, and an EAN-13 with a leading 0 is a UPC-A.
     */
    suspend fun searchScanned(scan: BarcodeScan) {
        query.value = scan.code
        val lookup = try {
            Barcode.findByCode<StockRow>(scan.code, format = scan.format, barcode = { it.barcode }, sku = { it.sku }, key = { it.catalogItemId }) { text ->
                API.get<List<StockRow>>("/provider-stock", mapOf("q" to text))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return
        }
        val barcode = lookup.exact?.barcode
        if (barcode != null && barcode != scan.code && Barcode.same(barcode, scan.code)) query.value = barcode
    }

    /** The sheets read the row as the list has it now, so a save elsewhere shows. */
    fun fresh(row: StockRow): StockRow = (whole ?: emptyList()).firstOrNull { it.catalogItemId == row.catalogItemId } ?: row

    fun saved() {
        announceDataChanged()
    }

    fun toggle(next: StockFilter) {
        filter = if (filter == next) StockFilter.all else next
    }

    // MARK: Header (web ProviderModuleHeader: Boxes, teal)

    @Composable
    fun Header() {
        val all = wholeRows
        val loaded = whole != null
        val out = all.count { StockRules.matches(it, StockFilter.`out`, today = today) }
        val low = all.count { StockRules.matches(it, StockFilter.low, today = today) }
        val expiring = all.count { StockRules.hasExpiringLot(it.lots, today = today) }
        val expired = all.count { StockRules.expiredUnits(it.lots, today = today) > 0 }
        val stats = mutableListOf(
            ModuleStat(
                "products", label = L("Products"), value = if (loaded) "${all.size}" else "—",
                hint = if (out > 0) (if (out == 1) L("1 out of stock") else LF("%d out of stock", out)) else null,
                icon = "boxes", accent = Theme.aqua,
            ),
            ModuleStat(
                "low", label = L("Low stock"), value = if (loaded) "$low" else "—",
                hint = if (loaded) (if (low > 0) L("Tap to see them") else L("All stocked")) else null,
                icon = "triangle-alert", accent = Theme.lime, attention = low > 0, active = filter == StockFilter.low,
                action = { toggle(StockFilter.low) },
            ),
            ModuleStat(
                "expiring", label = LF("Expiring in %d days", StockRules.expiringDays), value = if (loaded) "$expiring" else "—",
                hint = if (loaded) {
                    if (expired > 0) (if (expired == 1) L("1 already expired") else LF("%d already expired", expired))
                    else if (expiring > 0) L("Sell or use these first") else L("Nothing close")
                } else null,
                icon = "calendar-clock", accent = Theme.pink, attention = expired > 0, active = filter == StockFilter.expiring,
                action = { toggle(StockFilter.expiring) },
            ),
        )
        val total = value(all)
        if (seesCost && total != null) {
            stats.add(ModuleStat("value", label = L("Stock value at cost"), value = Money.format(total), icon = "scale", accent = ProviderState.done.color))
        }
        ModuleHeader(
            icon = ProviderSection.inventory.headerIcon,
            title = L("Inventory"),
            subtitle = L("Every sale, return and visit moves this stock."),
            accent = ProviderSection.inventory.headerAccent,
            stats = stats,
            storageKey = "inventory",
        ) {
            if (canReceive) {
                PMButton(L("Receive delivery"), systemImage = "package-plus") { sheet = StockInventorySheet.receive(null) }
            }
        }
    }

    /** The web's quiet note for a role that can only look. */
    @Composable
    fun ReadOnlyNote() {
        val box = RoundedCornerShape(Theme.boxRadius)
        CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, box)
                    .border(1.dp, Theme.borderSoft, box)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("eye", 16.dp)
                Text(L("You can see the stock. Your role cannot receive, count or change it."), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }

    /** Each filter in its own colour, also while it is off: the one its header tile or status pill wears. */
    @Composable
    fun Filters() {
        fun counted(f: StockFilter): Int = rows.count { StockRules.matches(it, f, today = today) }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToneChip(L("All"), icon = "layout-grid", tone = ChipTone.page, isOn = filter == StockFilter.all) { filter = StockFilter.all }
            ToneChip(L("Low stock"), icon = "triangle-alert", tone = ChipTone.waiting, count = counted(StockFilter.low), isOn = filter == StockFilter.low) { toggle(StockFilter.low) }
            ToneChip(L("Out of stock"), icon = "ban", tone = ChipTone.problem, count = counted(StockFilter.`out`), isOn = filter == StockFilter.`out`) { toggle(StockFilter.`out`) }
            ToneChip(L("Expiring soon"), icon = "calendar-clock", tone = ChipTone.waiting, count = counted(StockFilter.expiring), isOn = filter == StockFilter.expiring) { toggle(StockFilter.expiring) }
            ToneChip(L("Expired@lot"), icon = "calendar-x", tone = ChipTone.expired, count = counted(StockFilter.expired), isOn = filter == StockFilter.expired) { toggle(StockFilter.expired) }
            ChipRule()
            ToneChip(L("Medicines only"), icon = "pill", tone = ChipTone.sky, isOn = medicinesOnly) { medicinesOnly = !medicinesOnly }
        }
    }

    val emptyTitle = when (filter) {
        StockFilter.low -> L("Nothing is low on stock.")
        StockFilter.`out` -> L("Nothing is out of stock.")
        StockFilter.expiring -> LF("Nothing expires in the next %d days.", StockRules.expiringDays)
        StockFilter.expired -> L("Nothing on the shelf has expired.")
        StockFilter.all -> L("Nothing here.")
    }

    // MARK: The list

    @Composable
    fun Content() {
        val failed = loadError
        if (whole == null && failed == null) {
            LoadingState()
        } else if (failed != null && whole == null) {
            EmptyState(
                systemImage = "boxes",
                title = L("The stock could not be loaded."),
                message = failed,
                actionTitle = L("Try again"),
                action = { scope.launch { load() } },
                accent = Theme.pink,
            )
        } else if (listed.isEmpty()) {
            if (rows.isEmpty() && searched.isEmpty()) {
                EmptyState(
                    systemImage = "boxes",
                    title = L("No stock yet"),
                    message = if (canReceive) L("Receive a delivery to start counting a product’s stock.") else L("Once someone receives a delivery, the product’s stock is listed here."),
                    actionTitle = if (canReceive) L("Receive delivery") else null,
                    action = if (canReceive) ({ sheet = StockInventorySheet.receive(null) }) else null,
                    accent = Theme.mango,
                )
            } else if (rows.isEmpty()) {
                EmptyState(systemImage = "boxes", title = LF("No product matches “%@”.", searched), message = L("Search by its name, its SKU or its barcode."), accent = Theme.mango)
            } else {
                EmptyState(
                    systemImage = "boxes",
                    title = emptyTitle,
                    actionTitle = if (filter != StockFilter.all) L("Show all") else null,
                    action = if (filter != StockFilter.all) ({ filter = StockFilter.all }) else null,
                    accent = Theme.mango,
                )
            }
        } else {
            val visible = listed.take(shownCount)
            Column(Modifier.fillMaxWidth()) {
                visible.forEachIndexed { index, row ->
                    key(row.id) {
                        if (index > 0) RowDivider()
                        StockLineRow(
                            row = row,
                            today = today,
                            seesCost = seesCost,
                            wide = wide,
                            onOpen = { sheet = StockInventorySheet.detail(row, history = row.lots.isEmpty()) },
                            onReceive = if (canReceive) ({ sheet = StockInventorySheet.receive(row) }) else null,
                            onCount = if (canCount && row.inventoryItemId != null) ({ sheet = StockInventorySheet.count(row) }) else null,
                            onWaste = if (canCount && row.inventoryItemId != null) ({ sheet = StockInventorySheet.waste(row, lotId = null) }) else null,
                            onSettings = if (canEdit) ({ sheet = StockInventorySheet.settings(row) }) else null,
                        )
                    }
                }
                LoadMoreRow(hasMore = visible.size < listed.size) { shownCount += stockPageSize }
            }
        }
    }

    /** The count and the value go under the list, as on Home. */
    @Composable
    fun Footer() {
        if (whole != null && listed.isNotEmpty()) {
            val shownRows = min(shownCount, listed.size)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp).pullUp(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    if (shownRows < listed.size) LF("Showing %d of %d", shownRows, listed.size) else (if (listed.size == 1) L("1 product") else LF("%d products", listed.size)),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                Spacer(Modifier.width(16.dp))
                val listedValue = value(listed)
                if (seesCost && listedValue != null) {
                    Text(L("At cost"), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(4.dp))
                    Text(Money.format(listedValue), style = Theme.heading(16), color = Theme.inkStrong, modifier = Modifier.alignByBaseline())
                }
            }
        }
    }

    // MARK: Page

    providerPage(L("Inventory"), root = root, modifier = modifier) {
        if (workspace.current != null && !canView) {
            PageScroll {
                EmptyState(
                    systemImage = "lock",
                    title = L("You do not have access to inventory."),
                    message = L("Ask the workspace owner to let your role see stock."),
                    panel = true,
                )
            }
        } else {
            PageScroll(refresh = { load() }) {
                Header()
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.4.dp)) {
                    if (readOnly && workspace.current != null) ReadOnlyNote()
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarcodeSearchField(prompt = L("Product, SKU or barcode"), text = query, onScan = { scan -> scope.launch { searchScanned(scan) } })
                        Filters()
                    }
                    ListCard(style = ListCardStyle.panel, modifier = Modifier.padding(top = 16.dp)) { Content() }
                    Footer()
                }
            }
        }
    }

    ReloadsOnScope { load() }
    Debounced(query.value) { text ->
        val trimmed = text.trim { it == ' ' || it == '\t' }
        if (trimmed != searched) {
            searched = trimmed
            search()
        }
    }
    LaunchedEffect(filter, searched, medicinesOnly) { shownCount = stockPageSize }

    sheet?.let { which ->
        key(which.id) {
            AppSheet(onDismissRequest = { sheet = null }) {
                when (which) {
                    is StockInventorySheet.receive ->
                        StockReceiveSheet(preset = which.row?.let { fresh(it) }, severalLocations = workspace.multiLocation, onSaved = { saved() })
                    is StockInventorySheet.count ->
                        StockAdjustSheet(row = fresh(which.row), kind = StockAdjustKind.count, lotId = null, onSaved = { saved() })
                    is StockInventorySheet.waste ->
                        StockAdjustSheet(row = fresh(which.row), kind = StockAdjustKind.waste, lotId = which.lotId, onSaved = { saved() })
                    is StockInventorySheet.detail ->
                        StockDetailSheet(
                            row = fresh(which.row),
                            startOnHistory = which.history,
                            canWriteOff = canCount,
                            onWriteOff = { lot ->
                                sheet = null
                                scope.launch {
                                    delay(450)
                                    sheet = StockInventorySheet.waste(which.row, lotId = lot)
                                }
                            },
                            onRoute = { route ->
                                sheet = null
                                scope.launch {
                                    delay(400)
                                    nav.push(route)
                                }
                            },
                        )
                    is StockInventorySheet.settings ->
                        StockSettingsSheet(row = fresh(which.row), onSaved = { saved() })
                }
            }
        }
    }
}

/**
 * A round outline icon button (web `Button variant="outline" size="icon"`, 44 on a phone, 40 on
 * an iPad): the kit's IconCircleButton is the quiet grey ⋯, not this blue action.
 */
@Composable
fun StockIconButton(
    icon: String,
    label: String,
    size: Dp = 44.dp,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val tint = if (destructive) Theme.destructive else Theme.blue
    Box(
        modifier
            .size(size)
            .plainButton(label = label, onClick = action)
            .background(Theme.surface, CircleShape)
            .border(2.dp, tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, 16.dp, tint = tint)
    }
}

/**
 * One product's row (web `StockLine`): its photo, name, lots; then what is on the shelf and its
 * state, its value at cost (iPad), and the four actions.
 */
@Composable
private fun StockLineRow(
    row: StockRow,
    today: String,
    seesCost: Boolean,
    wide: Boolean,
    onOpen: () -> Unit,
    onReceive: (() -> Unit)?,
    onCount: (() -> Unit)?,
    onWaste: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) {
    val state = StockRules.state(row, today = today)
    val expired = StockRules.expiredUnits(row.lots, today = today)
    val sellable = StockRules.sellable(row, today = today)
    val pack = row.packs.firstOrNull { it.stockUnits > 1 }
    val tone = state.tone
    val onHandColor = if (state == StockState.inStock) Theme.inkStrong else Theme.mix(tone, 70.0, Theme.inkStrong)

    @Composable
    fun OnHand() {
        Text(
            StockText.onHand(row.quantityOnHand),
            style = Theme.heading(24).monospacedDigit(),
            color = onHandColor,
            maxLines = 1,
            softWrap = false,
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton(label = LF("Open %@: lots and history", row.name), onClick = onOpen),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StockProductThumb(photoUrl = row.photoUrl, productKind = row.productKind)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(StockText.detail(row).nonEmpty ?: L("No SKU"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.lots.isNotEmpty()) {
                    FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 4.dp)) {
                        row.lots.take(3).forEach { lot ->
                            stockLotChip(code = lot.lotCode, quantity = lot.quantityOnHand, expiresOn = lot.expiresOn, today = today)
                        }
                        if (row.lots.size > 3) {
                            Text(LF("+%d more", row.lots.size - 3), style = brandText(11.2, FontWeight.Bold), color = Theme.inkSoft)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The number and its pill side by side; stacked when four actions leave no room.
                ViewThatFits(
                    first = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OnHand()
                            stockStateChip(row, today = today)
                        }
                    },
                    second = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OnHand()
                            stockStateChip(row, today = today)
                        }
                    },
                )
                StockMetaLine(
                    parts = listOf(
                        if (row.reorderPoint > 0 && row.lowStockAlert != false) LF("Reorder at %@", StockText.count(row.reorderPoint)) else null,
                        if (expired > 0 && sellable > 0) LF("%@ to sell", StockText.count(sellable)) else null,
                        if (expired > 0 && state != StockState.expired) (if (expired == 1) L("1 expired") else LF("%@ expired", StockText.count(expired))) else null,
                        pack?.let { LF("= %@ × pack of %d", StockText.count(max(0, row.quantityOnHand) / it.stockUnits), it.stockUnits) },
                    ),
                )
            }
            val value = row.stockValueAmount
            val cost = row.unitCostAmount
            if (wide && seesCost && value != null && cost != null) {
                Column(Modifier.width(112.dp), horizontalAlignment = Alignment.End) {
                    Text(Money.format(value), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong, textAlign = TextAlign.End)
                    Text(LF("%@ each", Money.format(cost)), style = Theme.text(12), color = Theme.inkSoft, textAlign = TextAlign.End)
                }
            }
            if (onReceive != null || onCount != null || onWaste != null || onSettings != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val size = if (wide) 40.dp else 44.dp
                    if (onReceive != null) StockIconButton(icon = "package-plus", label = LF("Receive %@", row.name), size = size, action = onReceive)
                    if (onCount != null) StockIconButton(icon = "clipboard-list", label = LF("Count %@", row.name), size = size, action = onCount)
                    if (onWaste != null) StockIconButton(icon = "trash-2", label = LF("Write off %@", row.name), size = size, action = onWaste)
                    if (onSettings != null) StockIconButton(icon = "sliders-horizontal", label = LF("Stock settings of %@", row.name), size = size, action = onSettings)
                }
            }
        }
    }
}
