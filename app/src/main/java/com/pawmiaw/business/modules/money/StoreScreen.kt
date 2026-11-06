package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

//
//  StoreScreen.kt (iOS StoreScreen.swift)
//
//  Tienda (web: routes/provider.store.tsx): sell at the counter, the sales
//  of the day (or the person's reach), the cash register and the store's
//  settings. One place's page: with "Todas las sedes" it asks which first.
//  Money levels (docs/money-access-levels.md): selling reads today's sales,
//  the front desk the last 7 days and the shifts, business finances any day.
//  The page is the web's: the module header (the register at this device and
//  today's numbers), the Store's toned tabs, then the open tab.
//

private enum class CashStoreTab { sell, sales, register, settings }

/** Swift `try?`. */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

/** SwiftUI `.onChange(of:)`: runs when the value changes, not on the first composition. */
@Composable
private fun <T> OnChange(value: T, action: suspend (T) -> Unit) {
    val seen = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (seen[0] != value) {
            seen[0] = value
            action(value)
        }
    }
}

/** SwiftUI `ViewThatFits(in: .horizontal)`: the first that fits the width, else the second. */
@Composable
private fun ViewThatFits(modifier: Modifier = Modifier, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val probe = subcompose("probe", first).map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val fits = (probe.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth
        val placeables = subcompose(if (fits) "first" else "second", if (fits) first else second).map { it.measure(constraints.copy(minHeight = 0)) }
        val width = max(constraints.minWidth, placeables.maxOfOrNull { it.width } ?: 0)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

@Composable
fun StoreScreen(modifier: Modifier = Modifier) {
    providerPage(L("Store"), root = true, modifier = modifier) {
        RequireLocation { CashStorePage() }
    }
}

@Composable
private fun CashStorePage() {
    val workspace = Workspace.shared
    val device = remember { CashDeviceRegister() }
    var tab by remember { mutableStateOf<CashStoreTab?>(null) }
    var settings by remember { mutableStateOf<CashStoreSettings?>(null) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var todaysSales by remember { mutableStateOf(listOf<CashSaleSummary>()) }
    val owingOnly = remember { mutableStateOf(false) }
    /** "Owed on account" counts today's sales: opening it starts the list on today again. */
    var salesView by remember { mutableIntStateOf(0) }

    val canSell = workspace.can(Permission.salesSell)
    val level = workspace.moneyLevel
    val canSeeSales = canSell || level >= MoneyLevel.frontDesk
    val tabs = buildList {
        if (canSell) add(CashStoreTab.sell)
        if (canSeeSales) add(CashStoreTab.sales)
        if (cashCanRunShift(workspace) || workspace.can(Permission.registerManage) || level >= MoneyLevel.frontDesk) add(CashStoreTab.register)
        add(CashStoreTab.settings)
    }
    val current = tab?.takeIf { it in tabs } ?: tabs.firstOrNull() ?: CashStoreTab.settings

    suspend fun load() {
        try {
            settings = API.get<CashStoreSettings>("/provider-store/settings")
            settingsError = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (settings == null) settingsError = errorText(error)
        }
        todaysSales = attempt { API.get<List<CashSaleSummary>>("/provider-store/sales", mapOf("period" to "today", "limit" to "500", "locationId" to Workspace.shared.locationId)) } ?: todaysSales
    }

    if (!canSeeSales) {
        PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role sell.")) }
    } else {
        CashKeepsRegister(device)
        ReloadsOnScope { load() }
        PageScroll(maxWidth = 1180.dp, refresh = { load() }) {
            StoreHeader(
                device = device, settings = settings, todaysSales = todaysSales, current = current, owingOnly = owingOnly.value,
                openTab = { next, owing ->
                    owingOnly.value = owing
                    tab = next
                },
                openOwing = {
                    salesView += 1
                    owingOnly.value = true
                    tab = CashStoreTab.sales
                },
            )
            ToneTabs(
                tabs = tabs.map { ToneTabs.Tab(it, storeTabTitle(it), icon = storeTabIcon(it)) },
                selection = stateOf({ current }, { next ->
                    // The "Balance due" filter belongs to the Sales tab: leaving it takes it off.
                    if (next != CashStoreTab.sales) owingOnly.value = false
                    tab = next
                }),
                label = L("Store"),
            )
            val loaded = settings
            val error = settingsError
            if (loaded != null) {
                when (current) {
                    CashStoreTab.sell -> CashCounter(settings = loaded, device = device, openRegister = { tab = CashStoreTab.register })
                    CashStoreTab.sales -> key(salesView) { CashSalesTab(owingOnly = owingOnly) }
                    CashStoreTab.register -> CashRegisterTab(device = device)
                    CashStoreTab.settings -> CashStoreSettingsTab(settings = loaded, onSaved = { settings = it })
                }
            } else if (error != null) {
                ErrorState(message = error) { load() }
            } else {
                LoadingState()
            }
        }
    }
}

private fun storeTabTitle(tab: CashStoreTab): String = when (tab) {
    CashStoreTab.sell -> L("Sell")
    CashStoreTab.sales -> L("Sales")
    CashStoreTab.register -> L("Register")
    CashStoreTab.settings -> L("Settings")
}

private fun storeTabIcon(tab: CashStoreTab): String = when (tab) {
    CashStoreTab.sell -> "shopping-cart"
    CashStoreTab.sales -> "receipt"
    CashStoreTab.register -> "wallet"
    CashStoreTab.settings -> "settings-2"
}

// MARK: Header

@Composable
private fun StoreHeader(
    device: CashDeviceRegister,
    settings: CashStoreSettings?,
    todaysSales: List<CashSaleSummary>,
    current: CashStoreTab,
    owingOnly: Boolean,
    openTab: (CashStoreTab, Boolean) -> Unit,
    openOwing: () -> Unit,
) {
    val workspace = Workspace.shared
    val standing = todaysSales.filter { !it.canceled }
    val todayTotal = standing.sumOf { it.totalAmount - it.returnedAmount }
    val todayReturned = standing.sumOf { it.returnedAmount }
    val todayDue = standing.sumOf { it.balanceDueAmount }

    /** The web's `storeStats`, one to one. */
    val stats = if (settings == null) emptyList() else buildList {
        val currency = settings.currency
        add(ModuleStat(
            "count", label = L("Sales today"), value = "${standing.size}",
            hint = if (standing.isEmpty()) null else LF("Average %@", cashMoney((todayTotal.toDouble() / standing.size).roundToInt(), currency)),
            icon = "receipt", accent = Theme.aqua, active = current == CashStoreTab.sales && !owingOnly,
            action = { openTab(CashStoreTab.sales, false) },
        ))
        add(ModuleStat(
            "total", label = L("Sold today"), value = cashMoney(todayTotal, currency),
            hint = if (todayReturned > 0) LF("%@ returned", cashMoney(todayReturned, currency)) else null,
            icon = "banknote", accent = ProviderState.done.color,
        ))
        // What the drawer should hold is for register managers only.
        if (workspace.can(Permission.registerManage)) {
            val session = device.session
            add(ModuleStat(
                "cash", label = L("Cash in the drawer"),
                value = session?.let { cashMoney(it.expectedCash, currency) } ?: L("Closed@register"),
                hint = session?.let { LF("Float %@", cashMoney(it.openingCash, currency)) } ?: L("Open it to take cash"),
                icon = "wallet", accent = Theme.mango, attention = session?.stale == true,
                active = current == CashStoreTab.register, action = { openTab(CashStoreTab.register, false) },
            ))
        }
        // "Owed on account" is the front desk's (docs/money-access-levels.md).
        if (workspace.moneyLevel >= MoneyLevel.frontDesk) {
            if (todayDue > 0) {
                add(ModuleStat(
                    "due", label = L("Owed on account"), value = cashMoney(todayDue, currency),
                    hint = L("From today’s sales — see which"), icon = "coins", accent = Theme.pink,
                    attention = true, active = current == CashStoreTab.sales && owingOnly,
                    action = openOwing,
                ))
            } else {
                add(ModuleStat(
                    "due", label = L("Owed on account"), value = cashMoney(todayDue, currency),
                    hint = L("From today’s sales"), icon = "coins", accent = Theme.pink,
                ))
            }
        }
    }

    ModuleHeader(
        icon = "shopping-cart",
        title = L("Store"),
        subtitle = L("Sell at the counter — stock, tax and the receipt take care of themselves."),
        stats = stats,
        storageKey = "store",
    ) {
        val register = device.register
        if (register != null) {
            if (device.registers.size > 1) {
                AppMenu(label = { FilterMenuLabel(title = register.name) }, accessibilityLabel = L("Register at this device")) {
                    device.registers.forEach { entry ->
                        item(entry.name, checked = entry.id == register.id) { device.choose(entry.id) }
                    }
                }
            }
            val open = register.openSessionId != null
            Chip(
                text = if (open) LF("%@ open", register.name) else LF("%@ closed", register.name),
                tone = if (open) Chip.Tone.custom(ProviderState.done.color) else Chip.Tone.muted,
                icon = if (open) "lock-open" else "lock", percent = 16.0,
                ink = if (open) Theme.inkStrong else Theme.inkSoft, border = 60.0, size = Chip.Size.header,
                modifier = Modifier.plainButton { openTab(CashStoreTab.register, false) },
            )
        }
    }
}

// MARK: - The Sales tab

private const val salesLimit = 500
private val methodOrder = listOf("cash", "card", "bank_transfer", "digital_wallet", "other", "credit")

/**
 * The sales of a day, a stretch of days or the person's reach (web:
 * store-sales.tsx): find one, open its receipt, take a return or undo a
 * mistake (in the sale's own screen). A folio or a name is looked for in every day.
 */
@Composable
fun CashSalesTab(owingOnly: MutableState<Boolean>, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    var sales by remember { mutableStateOf(listOf<CashSaleSummary>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var outsideReach by remember { mutableStateOf<String?>(null) }
    val query = remember { mutableStateOf("") }
    var start by remember { mutableStateOf(Instant.now()) }
    var end by remember { mutableStateOf(Instant.now()) }
    var method by remember { mutableStateOf<String?>(null) }
    var seller by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(20) }

    val level = workspace.moneyLevel
    val reachesDays = level >= MoneyLevel.frontDesk
    val zone = workspace.timeZone
    val firstDay: Instant? = if (level >= MoneyLevel.finances) null else ZonedDateTime.now(zone).minusDays((MoneyLevel.frontDeskDays - 1).toLong()).toInstant()
    val startKey = DateText.dayKey(start)
    val endKey = DateText.dayKey(end)
    val onToday = startKey == DateText.dayKey() && endKey == DateText.dayKey()
    val searching = query.value.nonEmpty != null

    val methods = methodOrder.filter { entry -> entry == method || sales.any { entry in it.methods } }
    val sellers = sales.mapNotNull { it.cashierName.nonEmpty }.toSet().sorted()
    val activeSeller = if (seller in sellers) seller else ""
    val owingCount = sales.count { !it.canceled && it.balanceDueAmount > 0 }
    val filtering = method != null || activeSeller.isNotEmpty() || owingOnly.value
    val filtered = sales.filter { sale ->
        (method == null || method in sale.methods) &&
            (activeSeller.isEmpty() || sale.cashierName == activeSeller) &&
            (!owingOnly.value || (!sale.canceled && sale.balanceDueAmount > 0))
    }

    fun clamp(day: Instant): Instant = minOf(maxOf(day, firstDay ?: Instant.MIN), Instant.now())

    suspend fun load() {
        loading = true
        try {
            val from = DateText.dayKey(start)
            val to = DateText.dayKey(end)
            val today = from == DateText.dayKey() && to == DateText.dayKey()
            val params = linkedMapOf<String, String?>("limit" to "$salesLimit", "locationId" to Workspace.shared.locationId)
            val q = query.value.nonEmpty
            if (q != null) {
                params["q"] = q
            } else if (today) {
                params["period"] = "today"
            } else {
                params["from"] = from
                params["to"] = to
            }
            try {
                sales = API.get("/provider-store/sales", params)
                error = null
                outsideReach = null
                shown = 20
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: APIError) {
                if (failure.code == "sales_today_only" || failure.code == "outside_front_desk_days") {
                    sales = emptyList()
                    outsideReach = if (failure.code == "sales_today_only") L("You only see today’s sales.") else L("You only see the sales of the last 7 days.")
                } else {
                    error = errorText(failure)
                }
            } catch (failure: Exception) {
                error = errorText(failure)
            }
        } finally {
            loading = false
        }
    }

    Debounced(query.value) { load() }
    OnChange(startKey) { load() }
    OnChange(endKey) { load() }
    OnChange(owingOnly.value) { shown = 20 }
    OnChange(method) { shown = 20 }
    OnChange(seller) { shown = 20 }
    ReloadsOnScope { load() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // MARK: Days and search
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (reachesDays) {
                // The web's From – To field: two days, the second never before the first.
                Disabled(searching) {
                    Row(Modifier.fillMaxWidth().alpha(if (searching) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillDatePicker(text = CashDay.short(startKey), date = stateOf({ start }, { day ->
                            start = clamp(day)
                            if (end < start) end = start
                        }))
                        Text("–", style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
                        PillDatePicker(text = CashDay.short(endKey), date = stateOf({ end }, { day ->
                            end = clamp(day)
                            if (start > end) start = end
                        }))
                        if (startKey != endKey) {
                            val days = ChronoUnit.DAYS.between(start.atZone(zone).toLocalDate(), end.atZone(zone).toLocalDate()).toInt() + 1
                            Text(LP(days, "%d day", "%d days"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(prompt = L("Folio or customer"), text = query, modifier = Modifier.weight(1f))
                if (!onToday && !searching) {
                    PMButton(L("Today"), systemImage = "calendar-check", variant = ButtonVariant.outline) {
                        start = Instant.now()
                        end = Instant.now()
                    }
                }
            }
        }
        if (searching) {
            Text(
                if (level >= MoneyLevel.finances) L("Looking in every day, not only the ones chosen.") else if (level >= MoneyLevel.frontDesk) L("Looking in the last 7 days, or a whole folio on any day.") else L("Looking in today’s sales, or a whole folio on any day."),
                style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft,
            )
        }
        outsideReach?.let { NoticeCard(tone = NoticeCard.Tone.warning, title = it) }
        // MARK: Filters
        if (methods.isNotEmpty() || sellers.size > 1 || owingCount > 0 || owingOnly.value) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                methods.forEach { entry ->
                    val hue = CashText.methodHue(entry).color
                    ToneChip(CashText.method(entry), plain = hue, dot = hue, isOn = method == entry) { method = if (method == entry) null else entry }
                }
                if (owingCount > 0 || owingOnly.value) {
                    ToneChip(L("Balance due"), icon = "coins", plain = Theme.mango, isOn = owingOnly.value) { owingOnly.value = !owingOnly.value }
                }
                if (sellers.size > 1) {
                    AppMenu(label = { PillField(text = if (activeSeller.isEmpty()) L("Everyone who sold") else activeSeller) }, accessibilityLabel = L("Sold by")) {
                        item(L("Everyone who sold")) { seller = "" }
                        sellers.forEach { name -> item(name) { seller = name } }
                    }
                }
            }
        }
        // MARK: The list
        val failed = error
        ListCard(modifier = Modifier.alpha(if (loading && sales.isNotEmpty()) 0.7f else 1f)) {
            when {
                loading && sales.isEmpty() -> LoadingState()
                failed != null && sales.isEmpty() -> ErrorState(message = failed) { load() }
                filtered.isEmpty() -> EmptyState(
                    systemImage = "receipt",
                    title = outsideReach ?: (if (searching || filtering) L("No sales match.") else if (onToday) L("No sales yet today.") else if (startKey == endKey) L("No sales this day.") else L("No sales on these days.")),
                    message = if (filtering && sales.isNotEmpty()) L("Take a filter off to see the rest.") else null,
                    accent = Theme.mango,
                )
                else -> Column(Modifier.fillMaxWidth()) {
                    // Twenty at a time, more as the list scrolls (the owner's rule for full lists).
                    filtered.take(shown).forEachIndexed { index, sale ->
                        key(sale.id) {
                            if (index > 0) RowDivider()
                            CashSaleRow(
                                sale = sale, withDay = searching || startKey != endKey,
                                modifier = Modifier
                                    .plainButton { nav.push(ProviderRoute.sale(sale.id)) }
                                    .onScrolledIntoView { if (index >= shown - 3 && shown < filtered.size) shown += 20 },
                            )
                        }
                    }
                }
            }
        }
        if (!loading && filtered.isNotEmpty()) SalesTotals(filtered)
        if (sales.size >= salesLimit) {
            NoticeCard(tone = NoticeCard.Tone.warning, title = LF("These are the latest %d sales; the totals count only them. Choose fewer days to see the rest.", salesLimit))
        }
    }
}

/** The count and the totals go under the list, as on Home. */
@Composable
private fun SalesTotals(filtered: List<CashSaleSummary>) {
    val standing = filtered.filter { !it.canceled }
    val returned = standing.sumOf { it.returnedAmount }
    val sold = standing.sumOf { it.totalAmount - it.returnedAmount }
    val count: @Composable () -> Unit = { Text(LP(filtered.size, "%d sale", "%d sales"), style = Theme.text(14), color = Theme.inkSoft) }
    val amounts: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("Sold"), style = Theme.text(14), color = Theme.inkSoft)
                Text(cashMoney(sold), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
            }
            if (returned > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(L("Returned"), style = Theme.text(14), color = Theme.inkSoft)
                    Text(cashMoney(returned), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
                }
            }
        }
    }
    ViewThatFits(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        first = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                count()
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                amounts()
            }
        },
        second = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                count()
                amounts()
            }
        },
    )
}

/** `CashSaleRow.state`: what happened to a sale after it was rung up. */
object CashSaleRow {
    /** What happened to the sale after it was rung up; nothing for a plain paid one. */
    fun state(sale: CashSaleSummary): Pair<String, Chip.Tone>? {
        if (sale.canceled) return L("Canceled@sale") to Chip.Tone.danger
        // A sale left on account whose balance the business forgave ("Perdonar saldo", docs/family-money.md §1).
        if (sale.status == "forgiven") return L("Balance forgiven") to Chip.Tone.online
        if (sale.returnedAmount >= sale.totalAmount && sale.totalAmount > 0) return L("Returned@sale") to Chip.Tone.online
        if (sale.returnedAmount > 0) return L("Part returned") to Chip.Tone.online
        if (sale.balanceDueAmount > 0) return L("Balance due") to Chip.Tone.warn
        return null
    }
}

/**
 * One sale in the list (web `SaleRow`): the folio and its time, who bought
 * and what, how it was paid (each way in its colour), the amount and what
 * happened to it after.
 */
@Composable
fun CashSaleRow(sale: CashSaleSummary, withDay: Boolean = false, modifier: Modifier = Modifier) {
    val regular = LocalSizeClass.current == SizeClass.regular
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(88.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sale.folio, style = Theme.text(14, FontWeight.Bold).copy(fontFamily = FontFamily.Monospace), color = Theme.inkStrong, maxLines = 1)
            Text(
                if (withDay) "${CashDay.short(sale.createdAt.take(10))} · ${DateText.time(sale.createdAt)}" else DateText.time(sale.createdAt),
                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val inkStrong = Theme.inkStrong
            val inkSoft = Theme.inkSoft
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = inkStrong)) {
                        append(if (sale.walkIn) (sale.customerName ?: L("Walk-in customer")) else (sale.customerName ?: ""))
                    }
                    withStyle(SpanStyle(color = inkSoft)) { append(sale.petName.nonEmpty?.let { " · $it" } ?: "") }
                },
                style = Theme.text(14), maxLines = 1,
            )
            CompositionLocalProvider(LocalTextStyle provides Theme.text(12), LocalContentColor provides Theme.inkSoft) {
                FlowLayout(spacing = 8.dp, lineSpacing = 2.dp) {
                    val units = sale.unitCount ?: sale.lineCount ?: 0
                    Text(LP(units, "%d item", "%d items"))
                    sale.methods.forEach { method ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(6.dp).background(CashText.methodHue(method).color, CircleShape))
                            Text(CashText.method(method))
                        }
                    }
                    val cashier = sale.cashierName.nonEmpty
                    if (regular && cashier != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Glyph("user-round", 12.dp)
                            Text(cashier)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                cashMoney(sale.totalAmount, sale.currency),
                style = Theme.heading(18).monospacedDigit().copy(textDecoration = if (sale.canceled) TextDecoration.LineThrough else null),
                color = if (sale.canceled) Theme.inkSoft else Theme.inkStrong,
            )
            CashSaleRow.state(sale)?.let { state -> Chip(text = state.first, tone = state.second) }
        }
    }
}
