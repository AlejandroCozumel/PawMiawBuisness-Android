package com.pawmiaw.business.money

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

//
//  FinanceScreen.kt (iOS FinanceScreen.swift)
//
//  Finanzas (web: routes/provider.finance.tsx, finance/*): money across the
//  store, visits and stays. The page is the front desk's (money level 2: who
//  owes and the day close); the period's figures, payments and reports are
//  business finances (level 3). Business-wide: it reads the list's location
//  filter (`workspace.listLocationFilter`). Who owes, the payments and the
//  reports export as CSV (CashFinanceExports.kt) to the share sheet.
//
//  Drawn as the web draws it: the module header with the period's four
//  figures, the toned page tabs, the period pill and the source chips, then
//  each tab's cards (finance-overview, -collections, -payments, -cortes,
//  -reports).
//

enum class CashFinanceTab { overview, collections, payments, cortes, reports }

@Composable
fun FinanceScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    var tab by remember { mutableStateOf<CashFinanceTab?>(null) }
    val period = remember { mutableStateOf("this_month") }
    val source = remember { mutableStateOf<String?>(null) }
    var dashboard by remember { mutableStateOf<CashDashboard?>(null) }
    var dashboardError by remember { mutableStateOf<String?>(null) }
    val paymentKind = remember { mutableStateOf<String?>(null) }
    val paymentMethod = remember { mutableStateOf<String?>(null) }
    val reportGroup = remember { mutableStateOf("item") }
    val owing = remember { mutableStateOf("all") }
    val corteDay = remember { mutableStateOf<String?>(null) }

    val level = workspace.moneyLevel
    val tabs: List<CashFinanceTab> = when {
        level >= MoneyLevel.finances -> listOf(CashFinanceTab.overview, CashFinanceTab.collections, CashFinanceTab.payments, CashFinanceTab.cortes, CashFinanceTab.reports)
        level >= MoneyLevel.frontDesk -> listOf(CashFinanceTab.collections, CashFinanceTab.cortes)
        else -> emptyList()
    }
    val current: CashFinanceTab = tab?.takeIf { it in tabs } ?: tabs.firstOrNull() ?: CashFinanceTab.collections
    /** Receivables are what is owed today, and the day's close has its own day: neither follows the period. */
    val periodMatters = current != CashFinanceTab.collections && current != CashFinanceTab.cortes

    /** The account a figure or a row opens; `collect` goes straight to "Collect" (iOS `.navigationDestination(item: $openClient)`). */
    fun openClient(target: CashFinAccountTarget) {
        nav.push(ProviderRoute.page("cash-account:${target.id}${if (target.collect) ":collect" else ""}") {
            CashAccountScreen(clientId = target.id, collect = target.collect)
        })
    }

    suspend fun loadDashboard() {
        if (workspace.moneyLevel < MoneyLevel.finances) return
        try {
            dashboard = API.get("/provider-finance/dashboard", mapOf("period" to period.value, "source" to source.value, "locationId" to workspace.listLocationFilter))
            dashboardError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (dashboard == null) dashboardError = errorText(failure)
        }
    }

    fun follow(next: CashFinanceTab, kind: String?) {
        paymentKind.value = kind
        paymentMethod.value = null
        tab = next
    }

    fun tabTitle(tab: CashFinanceTab): String = when (tab) {
        CashFinanceTab.overview -> L("Overview")
        CashFinanceTab.collections -> L("Owed to you")
        CashFinanceTab.payments -> L("Payments")
        CashFinanceTab.cortes -> L("Day close")
        CashFinanceTab.reports -> L("Reports")
    }

    fun tabIcon(tab: CashFinanceTab): String = when (tab) {
        CashFinanceTab.overview -> "layout-dashboard"
        CashFinanceTab.collections -> "hand-coins"
        CashFinanceTab.payments -> "wallet"
        CashFinanceTab.cortes -> "scale"
        CashFinanceTab.reports -> "chart-column"
    }

    providerPage(L("Finance"), root = true, modifier = modifier) {
        if (level < MoneyLevel.frontDesk) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to give you front desk money or business finances.")) }
        } else {
            PageScroll(maxWidth = 1000.dp, refresh = { loadDashboard() }) {
                // MARK: Header (web ProviderModuleHeader: Banknote on the stable green)
                val subtitle: String = run {
                    if (level < MoneyLevel.finances) return@run L("Who owes you and the day close")
                    val resolved = dashboard?.period ?: return@run L("What came in, what was sold and what you are owed")
                    val range = CashDay.range(resolved.from, resolved.to)
                    if (!periodMatters) return@run range
                    val days = dashboard?.days?.size ?: 0
                    if (days == 1) LF("%@ · compared with the day before", range) else LF("%@ · compared with the %d days before", range, days)
                }
                /** The month's tiles are business finances: the front desk sees no period figures. */
                val totals = dashboard?.totals
                val stats: List<ModuleStat> = if (level < MoneyLevel.finances || totals == null) emptyList() else {
                    val money = { cents: Int -> cashMoney(cents, dashboard?.currency) }
                    listOf(
                        ModuleStat(
                            "collected", label = L("Collected"), value = money(totals.collected),
                            hint = CashFinDelta.hint(CashFinDelta.percent(totals.collected, totals.previousCollected), L("Money in, less refunds")),
                            icon = "banknote", accent = ProviderState.done.color,
                            active = current == CashFinanceTab.payments && paymentKind.value == null,
                            action = { follow(CashFinanceTab.payments, kind = null) },
                        ),
                        ModuleStat(
                            "sold", label = L("Sold"), value = money(totals.invoiced),
                            hint = CashFinDelta.hint(CashFinDelta.percent(totals.invoiced, totals.previousInvoiced), L("Made, paid or not")),
                            icon = "receipt", accent = Theme.aqua,
                            active = current == CashFinanceTab.reports,
                            action = { tab = CashFinanceTab.reports },
                        ),
                        ModuleStat(
                            "owed", label = L("Owed to you"), value = money(totals.owed),
                            hint = if (totals.overdue > 0) LF("%@ past due", money(totals.overdue)) else LP(totals.clientsOwing, "%d client · as of today", "%d clients · as of today"),
                            icon = "calendar-clock", accent = Theme.pink, attention = totals.overdue > 0,
                            active = current == CashFinanceTab.collections,
                            action = { owing.value = "all"; tab = CashFinanceTab.collections },
                        ),
                        ModuleStat(
                            "refunds", label = L("Refunds"), value = money(totals.refunded),
                            hint = totals.refundCount?.let { LP(it, "%d refund, already taken off", "%d refunds, already taken off") } ?: L("Given back, already taken off"),
                            icon = "undo-2", accent = Theme.mango,
                            active = current == CashFinanceTab.payments && paymentKind.value == "refund",
                            action = { follow(CashFinanceTab.payments, kind = "refund") },
                        ),
                    )
                }
                ModuleHeader(
                    icon = "banknote",
                    title = L("Finance"),
                    subtitle = subtitle,
                    accent = ProviderState.done.color,
                    stats = stats,
                    storageKey = "finance",
                )

                // MARK: Tabs, period and sources
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToneTabs(
                        tabs = tabs.map { ToneTabs.Tab(it, tabTitle(it), tabIcon(it)) },
                        selection = stateOf({ current }, { tab = it }),
                        label = L("Finance"),
                    )
                    if (periodMatters) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CashFinPeriodMenu(period = period, resolved = dashboard?.period)
                            dashboard?.period?.let { resolved ->
                                // The period on one page, for the owner or the accountant.
                                DocumentButton(
                                    title = L("Summary PDF"),
                                    systemImage = "file-down",
                                    path = "/provider-finance/summary/pdf",
                                    query = mapOf("from" to resolved.from, "to" to resolved.to, "source" to source.value, "locationId" to workspace.listLocationFilter),
                                    fileName = "${L("finance summary")} ${resolved.from}",
                                )
                            }
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (entry in listOf("store", "visit", "stay", "hospital", "other")) {
                                ToneChip(CashText.source(entry), tone = ChipTone.hue(CashText.sourceHue(entry)), isOn = source.value == entry) {
                                    source.value = if (source.value == entry) null else entry
                                }
                            }
                        }
                    }
                    LocationMenu()
                }

                // MARK: The tab
                when (current) {
                    CashFinanceTab.overview -> {
                        val loaded = dashboard
                        val failed = dashboardError
                        if (loaded != null) {
                            CashFinanceOverview(
                                dashboard = loaded,
                                source = source,
                                onTab = { tab = it },
                                onReport = { reportGroup.value = it; tab = CashFinanceTab.reports },
                                onPayments = { method -> paymentMethod.value = method; paymentKind.value = null; tab = CashFinanceTab.payments },
                                onOwing = { bucket -> owing.value = bucket; tab = CashFinanceTab.collections },
                                onCorte = { day -> corteDay.value = day; tab = CashFinanceTab.cortes },
                                onClient = { openClient(CashFinAccountTarget(id = it)) },
                            )
                        } else if (failed != null) {
                            CashFinError(title = L("Finance could not be loaded."), message = failed) { loadDashboard() }
                        } else {
                            LoadingState()
                        }
                    }
                    CashFinanceTab.collections -> {
                        CashFinanceCollections(filter = owing) { id, collect -> openClient(CashFinAccountTarget(id = id, collect = collect)) }
                    }
                    CashFinanceTab.payments -> {
                        val resolved = dashboard?.period
                        val failed = dashboardError
                        if (resolved != null) {
                            CashFinancePayments(
                                from = resolved.from,
                                to = resolved.to,
                                source = source.value,
                                zone = dashboard?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
                                kind = paymentKind,
                                method = paymentMethod,
                                onClient = { openClient(CashFinAccountTarget(id = it)) },
                            )
                        } else if (failed != null) {
                            CashFinError(title = L("Finance could not be loaded."), message = failed) { loadDashboard() }
                        } else {
                            LoadingState()
                        }
                    }
                    CashFinanceTab.cortes -> CashFinanceCortes(day = corteDay)
                    CashFinanceTab.reports -> {
                        val resolved = dashboard?.period
                        val failed = dashboardError
                        if (resolved != null) {
                            CashFinanceReports(from = resolved.from, to = resolved.to, source = source.value, group = reportGroup)
                        } else if (failed != null) {
                            CashFinError(title = L("Finance could not be loaded."), message = failed) { loadDashboard() }
                        } else {
                            LoadingState()
                        }
                    }
                }
            }
            ReloadsOnScope { loadDashboard() }
            FinanceOnChange(period.value) { loadDashboard() }
            FinanceOnChange(source.value) { loadDashboard() }
        }
    }
}

/** Swift `.onChange(of:)`: runs on a change of `value`, not on the first composition. */
@Composable
private fun <T> FinanceOnChange(value: T, action: suspend (T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(value) {
        if (first[0]) first[0] = false else latest(value)
    }
}

/** Swift `.rounded()`: halves away from zero. */
private fun finRounded(value: Double): Int = (if (value >= 0) floor(value + 0.5) else ceil(value - 0.5)).toInt()

/** The account a figure or a row opens; `collect` goes straight to "Collect" (a row's button). */
data class CashFinAccountTarget(val id: String, val collect: Boolean = false)

// MARK: - Small pieces (web finance-bits.tsx)

object CashFinDelta {
    fun percent(current: Int, previous: Int): Int? {
        if (previous == 0) return if (current == 0) 0 else null
        return finRounded((current - previous).toDouble() / abs(previous).toDouble() * 100)
    }

    /**
     * The change as the header's hint reads it ("+12% · Money in, less refunds"): the header's
     * hint is one line of text, so the web's DeltaPill becomes its words.
     */
    fun hint(change: Int?, text: String): String = "${label(change)} · $text"

    fun label(change: Int?): String {
        if (change == null) return L("New")
        if (change == 0) return "0%"
        return "${if (change > 0) "+" else "−"}${abs(change)}%"
    }
}

/**
 * A change against the period before, as a pill (web `DeltaPill`): the arrow and the sign say the
 * direction; `neutral` takes no side (tax).
 */
@Composable
fun CashFinDeltaPill(change: Int?, neutral: Boolean = false, modifier: Modifier = Modifier) {
    val good: Boolean? = if (change == null || change == 0 || neutral) null else change > 0
    val fill = when (good) {
        true -> Theme.wash(ProviderState.done.color, 22.0)
        false -> Theme.wash(Theme.destructive, 14.0)
        null -> Theme.surfaceMuted
    }
    val ink = when {
        change == null -> Theme.inkSoft
        good == true -> Theme.inkStrong
        good == false -> Theme.mix(Theme.destructive, 78.0, Theme.inkStrong)
        else -> Theme.mix(Theme.inkSoft, 75.0, Theme.inkStrong)
    }
    CompositionLocalProvider(LocalContentColor provides ink) {
        Row(
            modifier.background(fill, CapsuleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (change != null) {
                Glyph(if (change == 0) "minus" else if (change > 0) "arrow-up-right" else "arrow-down-right", 14.dp)
            }
            Text(CashFinDelta.label(change), style = brandText(12, if (change == null) FontWeight.Bold else FontWeight.ExtraBold).monospacedDigit(), color = ink)
        }
    }
}

/**
 * A titled card on the overview (web `FinanceCard`): a quiet icon, the title in Fredoka 18, one
 * line on how to read it, and something to do with it at the right.
 */
@Composable
fun CashFinCard(
    title: String,
    hint: String? = null,
    icon: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(padding = 16.dp, spacing = 16.dp, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Glyph(icon, 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 3.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Theme.heading(18), color = Theme.inkStrong)
                    hint.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
                }
            }
            action?.invoke()
        }
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** "Ver todo →": a card's quiet link (web `CardLink`). */
@Composable
fun CashFinCardLink(title: String, modifier: Modifier = Modifier, action: () -> Unit) {
    CashFinCardLinkLabel(title = title, modifier = modifier.clip(CapsuleShape).plainButton(onClick = action))
}

@Composable
fun CashFinCardLinkLabel(title: String, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
        Row(
            modifier.height(32.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            Glyph("arrow-right", 14.dp)
        }
    }
}

/** A figure inside a card (web `MiniStat`): on a phone the name at the left and the figure at the right. */
@Composable
fun CashFinMiniStat(label: String, value: String, attention: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Text(label.uppercase(), modifier = Modifier.weight(1f).alignByBaseline(), style = brandText(10.2, FontWeight.ExtraBold).tracking(1), color = Theme.inkSoft)
        Spacer(Modifier.width(12.dp))
        Text(value, modifier = Modifier.alignByBaseline(), style = Theme.heading(18).monospacedDigit(), color = if (attention) Theme.destructive else Theme.inkStrong)
    }
}

/** A breakdown's last line: what the rows add up to (web `TotalRow`). */
@Composable
fun CashFinTotalRow(label: String, detail: String? = null, value: String, modifier: Modifier = Modifier) {
    val soft = Theme.inkSoft
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(label) }
                    if (detail != null) withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = soft)) { append(" · $detail") }
                },
                modifier = Modifier.weight(1f).alignByBaseline(),
                style = Theme.text(14),
                color = Theme.inkStrong,
            )
            Spacer(Modifier.width(12.dp))
            Text(value, modifier = Modifier.alignByBaseline(), style = Theme.text(14, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkStrong)
        }
    }
}

/** A figure in a bordered box (web finance `Stat`: payments' and the account's figures). */
@Composable
fun CashFinStatBox(label: String, value: String, hint: String? = null, attention: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(12.dp),
    ) {
        Text(label.uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.06), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FittedText(value, Modifier.padding(top = 4.dp), style = Theme.heading(18).monospacedDigit(), color = if (attention) Theme.destructive else Theme.inkStrong, minimumScaleFactor = 0.6f)
        hint.nonEmpty?.let { Text(it, modifier = Modifier.padding(top = 2.dp), style = Theme.text(12), color = Theme.inkSoft) }
    }
}

/** Views two by two (four in a row on an iPad), each row as tall as its tallest. */
@Composable
fun CashFinGrid(items: List<@Composable () -> Unit>, columns: Int = 2, spacing: Dp = 12.dp, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                for (item in row) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { item() }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)])`: as many columns as fit. */
@Composable
fun CashFinAdaptiveGrid(count: Int, minimum: Dp = 220.dp, spacing: Dp = 8.dp, modifier: Modifier = Modifier, item: @Composable (Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (start in 0 until count step columns) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (index in start until min(start + columns, count)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { item(index) }
                    }
                    repeat(columns - (min(start + columns, count) - start)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** "12 pagos" / "Mostrando 25 de 140" under a list (web `ListCount`), with an optional total at the right. */
@Composable
fun CashFinListCount(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(text, modifier = Modifier.weight(1f).alignByBaseline(), style = Theme.text(14), color = Theme.inkSoft)
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                trailing,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                style = Theme.text(14, FontWeight.Bold).monospacedDigit(),
                color = Theme.inkStrong,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A list that could not be loaded (web `FinanceError`): what happened and a way to try again. */
@Composable
fun CashFinError(title: String = L("This could not be loaded."), message: String? = null, modifier: Modifier = Modifier, retry: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    ListCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(systemImage = "circle-alert", title = title, message = L("Check your connection and try again. Nothing was changed."), accent = Theme.pink)
            PMButton(L("Try again"), variant = ButtonVariant.outline, modifier = Modifier.padding(bottom = 24.dp)) { scope.launch { retry() } }
        }
    }
}

/** Finance's periods (iOS `CashFinPeriodMenu.periods`). */
object CashFinPeriodMenu {
    val periods: List<Pair<String, String>>
        get() = listOf(
            "today" to L("Today"), "yesterday" to L("Yesterday"), "this_week" to L("This week"), "this_month" to L("This month"),
            "last_month" to L("Last month"), "last_30" to L("Last 30 days"), "this_year" to L("This year"),
        )
}

/**
 * Finance's period, one compact control (web `FinancePeriodMenu`): a 40 capsule in a tint of the
 * page blue, the calendar-range glyph, the period's name and a chevron.
 */
@Composable
fun CashFinPeriodMenu(period: MutableState<String>, resolved: CashResolvedPeriod? = null, modifier: Modifier = Modifier) {
    AppMenu(
        label = {
            CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
                Row(
                    Modifier
                        .height(40.dp)
                        .background(Theme.wash(Theme.blue, 14.0), CapsuleShape)
                        .border(1.5.dp, Theme.mix(Theme.blue, 75.0, Theme.inkStrong), CapsuleShape)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("calendar-range", 16.dp)
                    Text(CashFinPeriodMenu.periods.firstOrNull { it.first == period.value }?.second ?: L("This month"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
                    Glyph("chevron-down", 14.dp)
                }
            }
        },
        modifier = modifier,
        accessibilityLabel = resolved?.let { "${L("Period")}: ${CashDay.range(it.from, it.to)}" } ?: L("Period"),
    ) {
        for (entry in CashFinPeriodMenu.periods) {
            item(entry.second, checked = period.value == entry.first) { period.value = entry.first }
        }
    }
}

/** A row with a bar for its share of the total (web: the overview's method and source rows). */
@Composable
fun CashBarRow(
    title: String,
    detail: String? = null,
    amount: String,
    share: Double,
    tone: Color = Theme.blue,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.chipRadius)
    val soft = Theme.inkSoft
    Column(
        modifier
            .fillMaxWidth()
            .background(if (active) Theme.surfaceMuted else Color.Transparent, shape)
            .then(if (active) Modifier.border(2.dp, Theme.inkStrong, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                    if (detail != null) withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = soft)) { append(" · $detail") }
                },
                modifier = Modifier.weight(1f).alignByBaseline(),
                style = Theme.text(14),
                color = Theme.inkStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(amount, modifier = Modifier.alignByBaseline(), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
        }
        ProgressBar(value = share, height = 6.dp, track = Theme.surfaceMuted, fill = tone)
    }
}

// MARK: - Overview (level 3)

private data class FinPersonRow(val name: String?, val image: String?, val amount: Int, val count: Int)

@Composable
fun CashFinanceOverview(
    dashboard: CashDashboard,
    source: MutableState<String?>,
    onTab: (CashFinanceTab) -> Unit,
    onReport: (String) -> Unit,
    onPayments: (String?) -> Unit,
    onOwing: (String) -> Unit,
    onCorte: (String?) -> Unit,
    onClient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val wide = LocalSizeClass.current == SizeClass.regular
    val money = { cents: Int -> cashMoney(cents, dashboard.currency) }
    val totals = dashboard.totals

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // MARK: The four tiles that explain the header
        CashFinGrid(items = overviewTiles(dashboard, money, onReport, onCorte), columns = if (wide) 4 else 2)

        // MARK: By location
        val places = dashboard.byLocation
        if (dashboard.locationId == null && places != null && places.size >= 2) {
            CashFinCard(L("By location"), hint = L("Collected and sold at each location"), icon = "map-pin") {
                OverviewLocations(places, dashboard, money)
            }
        }

        // MARK: Trend
        CashFinTrend(dashboard = dashboard)

        // MARK: Methods and sources
        CashFinCard(L("How they paid"), hint = L("Collected by method, refunds taken off. Tap one to see its payments."), icon = "wallet") {
            if (dashboard.methods.isEmpty()) {
                EmptyState(systemImage = "banknote", title = L("No payments in this period."), accent = Theme.lime)
            } else {
                val top = max(1, dashboard.methods.maxOfOrNull { max(0, it.amount) } ?: 1).toDouble()
                val count = dashboard.methods.sumOf { it.count }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in dashboard.methods) {
                        Column(Modifier.fillMaxWidth().plainButton { onPayments(row.method) }) {
                            CashBarRow(
                                title = CashText.method(row.method),
                                detail = LP(row.count, "%d payment", "%d payments"),
                                amount = money(row.amount),
                                share = max(0, row.amount).toDouble() / top,
                            )
                            val refunds = row.refunds
                            if (refunds != null && refunds > 0) {
                                Text(
                                    LF("%@ refunded, already taken off", money(refunds)),
                                    modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 2.dp).offset(y = (-4).dp),
                                    style = Theme.text(12),
                                    color = Theme.inkSoft,
                                )
                            }
                        }
                    }
                }
                CashFinTotalRow(label = L("Collected"), detail = LP(count, "%d payment", "%d payments"), value = money(totals.collected))
            }
        }
        CashFinCard(
            L("Where it comes from"),
            hint = source.value?.let { LF("Showing %@ only", CashText.source(it)) } ?: L("Sold by area. Tap one to see only that."),
            icon = "receipt",
        ) {
            val rows = dashboard.sources.filter { it.invoiced > 0 || it.source == source.value }
            val total = dashboard.sources.sumOf { it.invoiced }
            if (total == 0) {
                EmptyState(systemImage = "receipt", title = L("No sales yet in this period."), accent = Theme.lime)
            } else {
                val documents = dashboard.sources.sumOf { it.documents }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in rows) {
                        val on = source.value == row.source
                        CashBarRow(
                            title = CashText.source(row.source),
                            detail = LP(row.documents, "%d sale or bill", "%d sales and bills"),
                            amount = money(row.invoiced),
                            share = if (total > 0) row.invoiced.toDouble() / total.toDouble() else 0.0,
                            tone = CashText.sourceHue(row.source).color,
                            active = on,
                            modifier = Modifier.semantics { selected = on }.plainButton { source.value = if (on) null else row.source },
                        )
                    }
                }
                CashFinTotalRow(label = L("Sold"), detail = LP(documents, "%d sale or bill", "%d sales and bills"), value = money(total))
            }
        }

        // MARK: Owed to you (as of today)
        CashFinCard(
            L("Owed to you"),
            hint = L("As of today, whatever the period: sales and bills not paid in full."),
            icon = "calendar-clock",
            action = { CashFinCardLink(title = L("See who")) { onOwing("all") } },
        ) {
            if (totals.owed == 0) {
                EmptyState(systemImage = "calendar-clock", title = L("Nobody owes you anything."), accent = Theme.lime)
            } else {
                val aging = dashboard.aging
                if (aging != null) {
                    CashFinGrid(
                        items = CashText.agingBuckets.map { bucket -> @Composable { OverviewAgingTile(bucket, aging[bucket] ?: 0, money) { onOwing(bucket) } } },
                        columns = 2,
                        spacing = 8.dp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CashFinMiniStat(label = L("Owed today"), value = money(totals.owed))
                        CashFinMiniStat(label = L("Overdue"), value = money(totals.overdue), attention = totals.overdue > 0)
                    }
                }
                CashFinTotalRow(label = L("Owed to you"), detail = LP(totals.clientsOwing, "%d client", "%d clients"), value = money(totals.owed))
            }
        }

        // MARK: Register
        OverviewRegister(dashboard, money, onCorte)

        // MARK: Best sellers, top clients, people
        CashFinCard(
            L("Best sellers"),
            hint = L("Products and services by what they brought in"),
            icon = "trophy",
            action = { CashFinCardLink(title = L("See all")) { onReport("item") } },
        ) {
            if (dashboard.topItems.isEmpty()) {
                EmptyState(systemImage = "trophy", title = L("No sales yet in this period."), accent = Theme.lime)
            } else {
                Column {
                    dashboard.topItems.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", style = Theme.text(12, FontWeight.ExtraBold), color = Theme.inkStrong)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(item.label, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (item.itemType == "product") Glyph("package", 12.dp)
                                        Text(LF("%d sold", item.quantity) + (item.margin?.let { " · " + LF("margin %@", money(it)) } ?: ""), style = Theme.text(12), color = Theme.inkSoft)
                                    }
                                }
                            }
                            Text(money(item.revenue), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                        }
                    }
                }
            }
        }
        CashFinCard(L("Top clients"), hint = L("Who bought the most in this period, and what they have paid"), icon = "user-round") {
            val rows = dashboard.topClients ?: emptyList()
            if (rows.isEmpty()) {
                EmptyState(systemImage = "user-round", title = L("No sales yet in this period."), accent = Theme.lime)
            } else {
                Column {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        val name = if (row.walkIn) L("Walk-in sales") else row.name
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // The counter's public is not one person: no account to open.
                                .then(if (row.walkIn) Modifier else Modifier.plainButton { onClient(row.providerClientId) })
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(name = name, imageURL = if (row.walkIn) null else row.photoUrl, size = 36.dp, tint = if (row.walkIn) Theme.surfaceMuted else Theme.aqua)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    LP(row.documents, "%d sale or bill", "%d sales and bills") + " · " + LF("%@ paid in the period", money(row.collected)),
                                    style = Theme.text(12),
                                    color = Theme.inkSoft,
                                )
                            }
                            Text(money(row.invoiced), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                        }
                    }
                }
            }
        }
        CashFinCard(
            L("Who sold"),
            hint = L("By who made each sale or bill — not who performed the service"),
            icon = "users",
            action = { CashFinCardLink(title = L("See all")) { onReport("staff") } },
        ) {
            OverviewPeople(
                rows = dashboard.staff.map { FinPersonRow(it.name, it.image, it.revenue ?: 0, it.documents ?: 0) },
                total = totals.invoiced,
                count = { LP(it, "%d sale or bill", "%d sales and bills") },
                empty = L("No sales yet in this period."),
                totalLabel = L("Sold"),
                money = money,
            )
        }
        CashFinCard(L("Who collected"), hint = L("By who took each payment, refunds taken off"), icon = "banknote") {
            OverviewPeople(
                rows = (dashboard.collectors ?: emptyList()).map { FinPersonRow(it.name, it.image, it.collected ?: 0, it.payments ?: 0) },
                total = totals.collected,
                count = { LP(it, "%d payment", "%d payments") },
                empty = L("No payments in this period."),
                totalLabel = L("Collected"),
                money = money,
            )
        }

        // MARK: Deposits
        val received = totals.depositsReceived
        if (received != null) {
            CashFinCard(
                L("Deposits"),
                hint = L("A deposit counts as collected the day it comes in. Spending it later pays the bill without adding to Collected."),
                icon = "piggy-bank",
                action = if ((totals.creditHeld ?: 0) > 0) ({ CashFinCardLink(title = L("See who")) { onOwing("all") } }) else null,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CashFinMiniStat(label = L("Received in the period"), value = money(received))
                    CashFinMiniStat(label = L("Spent on bills"), value = money(totals.creditApplied ?: 0))
                    CashFinMiniStat(label = L("Held today"), value = money(totals.creditHeld ?: 0))
                }
                val clients = totals.creditClients
                if (clients != null && clients > 0) {
                    Text(LP(clients, "%d client holds credit", "%d clients hold credit"), modifier = Modifier.padding(top = 12.dp), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }

        // MARK: On the shelf
        val inventory = dashboard.inventory
        if (workspace.can(Permission.inventoryView) && inventory != null) {
            Card(padding = 16.dp, spacing = 16.dp, modifier = Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.inventory)) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon = "boxes", accent = Theme.aqua, size = 40.dp, iconSize = 18.dp, ink = IconBadge.Ink.strong)
                    Text(L("On the shelf"), modifier = Modifier.weight(1f), style = Theme.heading(18), color = Theme.inkStrong)
                    Glyph("arrow-right", 16.dp, tint = Theme.inkSoft)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CashFinMiniStat(label = L("Stock at cost"), value = money(inventory.valueAmount))
                    CashFinMiniStat(label = L("Low or out"), value = "${inventory.lowStock + inventory.outOfStock}", attention = inventory.outOfStock > 0)
                    val expired = inventory.expired ?: 0
                    CashFinMiniStat(
                        label = if (expired > 0) L("Expired or expiring") else LF("Expiring in %d days", inventory.expiringDays ?: 30),
                        value = "${inventory.expiringSoon + expired}",
                        attention = inventory.expiringSoon + expired > 0,
                    )
                }
            }
        }
    }
}

/** The four tiles that explain the header. */
private fun overviewTiles(
    dashboard: CashDashboard,
    money: (Int) -> String,
    onReport: (String) -> Unit,
    onCorte: (String?) -> Unit,
): List<@Composable () -> Unit> {
    val totals = dashboard.totals
    val cash = dashboard.cash
    val off = (cash?.short ?: 0) + (cash?.over ?: 0)
    val cashValue: String = cash?.let { c -> if (off == 0) L("None") else if (c.short > 0) "−${money(c.short)}" else "+${money(c.over)}" } ?: "—"
    val cashHint: String = when {
        cash == null -> L("Counts that did not match the drawer")
        cash.closedShifts == 0 -> L("No shift was closed in this period")
        off == 0 -> if (cash.closedShifts == 1) L("1 shift closed, counted exact") else LF("%d shifts closed, all exact", cash.closedShifts)
        cash.short > 0 && cash.over > 0 -> LF("Short in total; %@ over on other shifts", money(cash.over))
        cash.short > 0 -> if (cash.closedShifts == 1) L("Cash missing at close, on the one shift") else LF("Cash missing at close, %d of %d shifts", cash.shiftsWithDifference, cash.closedShifts)
        else -> if (cash.closedShifts == 1) L("Cash left over at close, on the one shift") else LF("Cash left over at close, %d of %d shifts", cash.shiftsWithDifference, cash.closedShifts)
    }
    val marginRate: Int? = if (totals.marginRevenue > 0) finRounded(totals.margin.toDouble() / totals.marginRevenue.toDouble() * 100) else null
    val tax = totals.tax
    val previousTax = totals.previousTax
    return listOf(
        {
            CashFinTile(
                icon = "percent", accent = Theme.butter, label = L("Tax in what was sold"), value = tax?.let(money) ?: "—",
                change = if (tax != null && previousTax != null) CashFinDelta.percent(tax, previousTax) else null,
                hasChange = tax != null && previousTax != null, neutral = true,
                hint = totals.subtotal?.let { LF("%@ before tax", money(it)) } ?: L("Tax inside the period’s sales"),
                action = { onReport("item") },
            )
        },
        {
            CashFinTile(
                icon = "scale", accent = Theme.mango, label = L("Register differences"), value = cashValue, attention = (cash?.short ?: 0) > 0,
                hint = cashHint, action = { onCorte(cash?.items?.firstOrNull()?.day) },
            )
        },
        {
            CashFinTile(
                icon = "coins", accent = Theme.aqua, label = L("Average ticket"), value = if (totals.documents > 0) money(totals.averageTicket) else "—",
                change = if (totals.documents > 0) CashFinDelta.percent(totals.averageTicket, totals.previousAverageTicket) else null, hasChange = totals.documents > 0,
                hint = LP(totals.documents, "Sold ÷ %d sale or bill", "Sold ÷ %d sales and bills"),
            )
        },
        {
            CashFinTile(
                icon = "trophy", accent = Theme.pink, label = L("Margin"), value = if (totals.marginRevenue > 0) money(totals.margin) else "—",
                change = if (totals.marginRevenue > 0) CashFinDelta.percent(totals.margin, totals.previousMargin) else null, hasChange = totals.marginRevenue > 0,
                hint = marginRate?.let { LF("%d%% on products with a cost, before tax", it) } ?: L("Needs products with a cost"),
            )
        },
    )
}

@Composable
private fun OverviewLocations(places: List<CashDashboard.Place>, dashboard: CashDashboard, money: (Int) -> String) {
    val total = places.sumOf { max(0, it.invoiced) }
    val box = RoundedCornerShape(Theme.boxRadius)
    val soft = Theme.inkSoft
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CashFinAdaptiveGrid(count = places.size, minimum = 220.dp, spacing = 8.dp) { index ->
            val place = places[index]
            val share = if (total > 0) max(0, place.invoiced).toDouble() / total.toDouble() else 0.0
            Column(
                Modifier.fillMaxSize().background(Theme.surface, box).border(1.dp, Theme.borderSoft, box).padding(12.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        buildAnnotatedString {
                            withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle()) { append(place.name) }
                            if (place.status == "paused") withStyle(Theme.text(12, FontWeight.SemiBold).toSpanStyle().copy(color = soft)) { append("  · ${L("Paused")}") }
                        },
                        modifier = Modifier.weight(1f).alignByBaseline(),
                        color = Theme.inkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${finRounded(share * 100)}%", modifier = Modifier.alignByBaseline(), style = Theme.text(12, FontWeight.Bold).monospacedDigit(), color = Theme.inkSoft)
                }
                ProgressBar(value = share, height = 8.dp, track = Theme.surfaceMuted, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    OverviewFigure(L("Collected"), money(place.collected), Modifier.weight(1f))
                    OverviewFigure(L("Sold"), money(place.invoiced), Modifier.weight(1f))
                }
            }
        }
        Text(
            LF("%d locations · %@ collected · %@ sold", places.size, money(dashboard.totals.collected), money(dashboard.totals.invoiced)),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = Theme.text(14),
            color = Theme.inkSoft,
        )
    }
}

@Composable
private fun OverviewFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Theme.text(12), color = Theme.inkSoft)
        Text(value, style = Theme.text(12, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One aging bucket on the receivables card: its dot, name and amount; a filter. */
@Composable
private fun OverviewAgingTile(bucket: String, amount: Int, money: (Int) -> String, onTap: () -> Unit) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxSize()
            .alpha(if (amount == 0) 0.6f else 1f)
            .clip(box)
            .plainButton(enabled = amount != 0, onClick = onTap)
            .background(Theme.surface, box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(CashFinAging.tone(bucket), CircleShape))
            Text(CashText.aging(bucket), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(money(amount), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
    }
}

/** Shifts closed in the period and what their count was off by. */
@Composable
private fun OverviewRegister(dashboard: CashDashboard, money: (Int) -> String, onCorte: (String?) -> Unit) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val cash = dashboard.cash
    CashFinCard(
        L("Register"),
        hint = L("Shifts closed in this period and what their count was off by."),
        icon = "scale",
        action = if (cashCanRunShift(workspace)) ({
            CashFinCardLinkLabel(title = L("Open the register"), modifier = Modifier.clip(CapsuleShape).plainButton { nav.push(ProviderRoute.section(ProviderSection.store)) })
        }) else null,
    ) {
        if (cash != null && cash.closedShifts > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CashFinMiniStat(label = L("Shifts closed"), value = "${cash.closedShifts}")
                CashFinMiniStat(label = L("Cash missing"), value = if (cash.short > 0) "−${money(cash.short)}" else money(0), attention = cash.short > 0)
                CashFinMiniStat(label = L("Cash left over"), value = if (cash.over > 0) "+${money(cash.over)}" else money(0))
            }
            if (cash.items.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp)) {
                    RowDivider()
                    cash.items.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().plainButton { onCorte(item.day) }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(item.registerName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(item.day?.let { CashDay.long(it) }.nonEmpty, item.closedByName.nonEmpty).joinToString(" · "),
                                    style = Theme.text(12),
                                    color = Theme.inkSoft,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (item.difference < 0) LF("%@ short", money(abs(item.difference))) else LF("%@ over", money(item.difference)),
                                style = Theme.text(14, FontWeight.Bold).monospacedDigit(),
                                color = if (item.difference < 0) Theme.destructive else Theme.inkStrong,
                            )
                        }
                    }
                }
            }
            Text(
                if (cash.shiftsWithDifference == 0) L("Every count matched the drawer.") else LP(cash.shiftsWithDifference, "%d shift did not match", "%d shifts did not match"),
                modifier = Modifier.padding(top = 12.dp),
                style = Theme.text(14),
                color = Theme.inkSoft,
            )
        } else {
            val open = cash?.openShifts ?: 0
            EmptyState(
                systemImage = "scale",
                title = L("No shift was closed in this period"),
                message = if (open > 0) LP(open, "%d shift is open now.", "%d shifts are open now.") else null,
                accent = Theme.mango,
            )
        }
    }
}

@Composable
private fun OverviewPeople(
    rows: List<FinPersonRow>,
    total: Int,
    count: (Int) -> String,
    empty: String,
    totalLabel: String,
    money: (Int) -> String,
) {
    if (rows.isEmpty()) {
        EmptyState(systemImage = "users", title = empty, accent = Theme.lime)
        return
    }
    val top = max(1, rows.maxOfOrNull { max(0, it.amount) } ?: 1).toDouble()
    val listed = rows.sumOf { it.amount }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rows) {
            val name = row.name ?: L("Someone who left")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = name, imageURL = row.image, size = 36.dp, tint = Theme.aqua)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, modifier = Modifier.weight(1f).alignByBaseline(), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(12.dp))
                        Text(money(row.amount), modifier = Modifier.alignByBaseline(), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressBar(value = max(0, row.amount).toDouble() / top, height = 6.dp, track = Theme.surfaceMuted, modifier = Modifier.weight(1f))
                        Text(count(row.count), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1)
                    }
                }
            }
        }
    }
    CashFinTotalRow(label = totalLabel, detail = if (listed != total) LF("others not listed: %@", money(total - listed)) else null, value = money(total))
}

/** How late a balance is: green to red (web `AGING_TONE`). */
object CashFinAging {
    @Composable
    fun tone(bucket: String): Color = when (bucket) {
        "current" -> ProviderState.done.color
        "d1_30" -> Hue.butter.color
        "d31_60" -> ProviderState.waiting.color
        else -> ProviderState.problem.color
    }
}

/**
 * A figure on the overview (web `Tile`): its badge, how it moved, its name, one line on how to read
 * it, and where it leads.
 */
@Composable
private fun CashFinTile(
    icon: String,
    accent: Color,
    label: String,
    value: String,
    change: Int? = null,
    /** The figure has a comparison (a nil `change` then reads "New"). */
    hasChange: Boolean = false,
    neutral: Boolean = false,
    attention: Boolean = false,
    hint: String,
    action: (() -> Unit)? = null,
) {
    val wide = LocalSizeClass.current == SizeClass.regular
    val tap = if (action != null) Modifier.plainButton(onClick = action) else Modifier
    Card(padding = 16.dp, spacing = 0.dp, modifier = Modifier.fillMaxHeight().then(tap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = icon, accent = accent, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong)
            Spacer(Modifier.weight(1f).width(4.dp))
            if (hasChange) {
                CashFinDeltaPill(change = change, neutral = neutral)
            } else if (action != null) {
                Glyph("arrow-right", 16.dp, tint = Theme.inkSoft)
            }
        }
        Text(
            label.uppercase(),
            modifier = Modifier.padding(top = 12.dp),
            style = brandText(10.9, FontWeight.ExtraBold).tracking(1.09),
            color = Theme.inkSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FittedText(
            value,
            Modifier.padding(top = 4.dp),
            style = Theme.heading(if (wide) 24 else 18).monospacedDigit(),
            color = if (attention) Theme.destructive else Theme.inkStrong,
            minimumScaleFactor = 0.6f,
        )
        Text(hint, modifier = Modifier.padding(top = 4.dp), style = Theme.text(12), color = Theme.inkSoft)
    }
}

// MARK: - Trend

private data class CashFinTrendRow(val key: String, val label: String, val date: Instant, var collected: Int, var sold: Int)

private val finCollectedTone = scheme(Triple(26, 127, 79), Triple(48, 164, 108))
private val finSoldTone = scheme(Triple(27, 111, 179), Triple(74, 147, 224))

/** Collected (bars) and sold (a line) by day, week or month, or the same as a table (web `Trend`). */
@Composable
private fun CashFinTrend(dashboard: CashDashboard) {
    var asTable by remember { mutableStateOf(false) }

    /** Days as they are, or folded into weeks or months when there are too many to read as bars. */
    val (bucket, rows) = remember(dashboard.days, Workspace.shared.timeZone, LocalizationManager.currentLanguage) {
        val days = dashboard.days
        val bucket = if (days.size > 120) "month" else if (days.size > 45) "week" else "day"
        val order = ArrayList<String>()
        val map = HashMap<String, CashFinTrendRow>()
        days.forEachIndexed { index, day ->
            val key = when (bucket) {
                "month" -> day.day.take(7)
                "week" -> days[index - (index % 7)].day
                else -> day.day
            }
            if (map[key] == null) {
                val first = if (bucket == "month") "$key-15" else key
                val date = CashDay.date(first) ?: Instant.now()
                val label = DateText.format(date, if (bucket == "month") "MMMyy" else "dMMM")
                map[key] = CashFinTrendRow(key = key, label = label, date = date, collected = 0, sold = 0)
                order.add(key)
            }
            map[key]?.let { it.collected += day.collected; it.sold += day.invoiced }
        }
        bucket to order.mapNotNull { map[it]?.copy() }
    }
    val empty = rows.all { it.collected == 0 && it.sold == 0 }
    val title = when (bucket) {
        "month" -> L("Collected and sold by month")
        "week" -> L("Collected and sold by week")
        else -> L("Collected and sold by day")
    }
    val money = { cents: Int -> cashMoney(cents, dashboard.currency) }
    val collectedTone = finCollectedTone.resolve()
    val soldTone = finSoldTone.resolve()

    CashFinCard(
        title,
        hint = L("Bars: money that came in. Line: what was sold. A gap between them is money still owed, or an older debt paid."),
        icon = "chart-column",
        action = if (empty) null else ({
            CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                Row(
                    Modifier
                        .height(36.dp)
                        .border(1.dp, Theme.borderSoft, CapsuleShape)
                        .clip(CapsuleShape)
                        .semantics { selected = asTable }
                        .plainButton { asTable = !asTable }
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(if (asTable) "chart-column" else "table-2", 14.dp)
                    Text(if (asTable) L("See as a chart") else L("See as a table"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                }
            }
        }),
    ) {
        if (empty) {
            EmptyState(systemImage = "chart-column", title = L("Nothing sold or collected in this period."), accent = Theme.lime)
        } else if (asTable) {
            TrendTable(rows, bucket, dashboard, money)
        } else {
            TrendChart(rows, collectedTone, soldTone, description = "$title. ${L("Collected")} · ${money(dashboard.totals.collected)}. ${L("Sold")} · ${money(dashboard.totals.invoiced)}.")
            FlowLayout(spacing = 16.dp, modifier = Modifier.padding(top = 12.dp)) {
                TrendLegend(bar = true, tone = collectedTone, text = L("Collected"), value = money(dashboard.totals.collected))
                TrendLegend(bar = false, tone = soldTone, text = L("Sold"), value = money(dashboard.totals.invoiced))
            }
        }
    }
}

@Composable
private fun TrendLegend(bar: Boolean, tone: Color, text: String, value: String) {
    val strong = Theme.inkStrong
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = if (bar) 8.dp else 16.dp, height = if (bar) 12.dp else 2.dp).background(tone, RoundedCornerShape(Theme.tickRadius / 2)))
        Text(
            buildAnnotatedString {
                append("$text · ")
                withStyle(SpanStyle(color = strong)) { append(value) }
            },
            style = Theme.text(12, FontWeight.Bold).monospacedDigit(),
            color = Theme.inkSoft,
        )
    }
}

/** Round steps for the money axis (Swift Charts picks its own; these read the same). */
private fun trendTicks(low: Double, high: Double): List<Double> {
    var lo = min(0.0, low)
    var hi = max(0.0, high)
    if (hi - lo <= 0.0) hi = lo + 1.0
    val rough = (hi - lo) / 4.0
    val magnitude = 10.0.pow(floor(log10(rough)))
    val residual = rough / magnitude
    val nice = when {
        residual > 5 -> 10.0
        residual > 2 -> 5.0
        residual > 1 -> 2.0
        else -> 1.0
    }
    val step = nice * magnitude
    lo = floor(lo / step) * step
    hi = ceil(hi / step) * step
    val ticks = ArrayList<Double>()
    var value = lo
    while (value <= hi + step / 2) {
        ticks.add(value)
        value += step
    }
    return ticks
}

/** The chart (iOS Swift Charts): collected as bars (60% of each slot, 4 corners), sold as a 2 pt line with points up to 31 slots. */
@Composable
private fun TrendChart(rows: List<CashFinTrendRow>, collectedTone: Color, soldTone: Color, description: String) {
    val measurer = rememberTextMeasurer()
    val labelStyle = Theme.text(11).copy(color = Theme.inkSoft)
    val gridColor = Theme.borderSoft
    val values = rows.flatMap { listOf(it.collected / 100.0, it.sold / 100.0) }
    val ticks = trendTicks(values.minOrNull() ?: 0.0, values.maxOrNull() ?: 0.0)
    val step = if (ticks.size >= 2) ticks[1] - ticks[0] else 1.0
    val format = remember(step, LocalizationManager.currentLanguage) {
        NumberFormat.getNumberInstance(appLocale).apply {
            maximumFractionDigits = if (step < 1) 2 else 0
            minimumFractionDigits = 0
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(288.dp)
            .semantics { contentDescription = description },
    ) {
        val yLabels = ticks.map { measurer.measure(format.format(it), labelStyle) }
        val labelWidth = yLabels.maxOfOrNull { it.size.width } ?: 0
        val labelHeight = yLabels.maxOfOrNull { it.size.height } ?: 0
        val gap = 6.dp.toPx()
        val plotLeft = labelWidth + gap
        val plotRight = size.width
        val plotTop = labelHeight / 2f
        val plotBottom = size.height - labelHeight - gap
        val lo = ticks.first()
        val hi = ticks.last()
        fun y(value: Double): Float = (plotBottom - (value - lo) / (hi - lo) * (plotBottom - plotTop)).toFloat()

        // The grid and the money at the leading side.
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        ticks.forEachIndexed { index, tick ->
            val ty = y(tick)
            drawLine(gridColor, Offset(plotLeft, ty), Offset(plotRight, ty), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val label = yLabels[index]
            drawText(label, topLeft = Offset((labelWidth - label.size.width).toFloat(), ty - label.size.height / 2f))
        }

        val count = max(1, rows.size)
        val band = (plotRight - plotLeft) / count
        val base = y(0.0)
        // Bars: money that came in.
        rows.forEachIndexed { index, row ->
            val cx = plotLeft + band * (index + 0.5f)
            val width = band * 0.6f
            val top = y(row.collected / 100.0)
            val height = abs(base - top)
            if (height > 0f) {
                val radius = min(4.dp.toPx(), min(width / 2f, height / 2f))
                drawRoundRect(collectedTone, topLeft = Offset(cx - width / 2f, min(top, base)), size = Size(width, height), cornerRadius = CornerRadius(radius, radius))
            }
        }
        // The line: what was sold.
        val path = Path()
        rows.forEachIndexed { index, row ->
            val cx = plotLeft + band * (index + 0.5f)
            val sy = y(row.sold / 100.0)
            if (index == 0) path.moveTo(cx, sy) else path.lineTo(cx, sy)
        }
        drawPath(path, soldTone, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (rows.size <= 31) {
            // symbolSize 28: a dot of that area.
            val radius = sqrt(28.0 / PI).toFloat() * density
            rows.forEachIndexed { index, row ->
                drawCircle(soldTone, radius = radius, center = Offset(plotLeft + band * (index + 0.5f), y(row.sold / 100.0)))
            }
        }
        // The days (or weeks, months) under the plot, thinned so they never touch.
        val xLabels = rows.map { measurer.measure(it.label, labelStyle) }
        val widest = xLabels.maxOfOrNull { it.size.width } ?: 0
        val every = max(1, ceil((widest + 8.dp.toPx()) / band).toInt())
        rows.forEachIndexed { index, _ ->
            if (index % every != 0) return@forEachIndexed
            val label = xLabels[index]
            val cx = plotLeft + band * (index + 0.5f)
            val x = (cx - label.size.width / 2f).coerceIn(plotLeft, max(plotLeft, plotRight - label.size.width))
            drawText(label, topLeft = Offset(x, plotBottom + gap))
        }
    }
}

@Composable
private fun TrendTable(rows: List<CashFinTrendRow>, bucket: String, dashboard: CashDashboard, money: (Int) -> String) {
    val first = when (bucket) {
        "month" -> L("Month")
        "week" -> L("Week of")
        else -> L("Day")
    }
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(Modifier.fillMaxWidth().clip(box).border(1.dp, Theme.borderSoft, box)) {
        TrendLine(first, L("Collected"), L("Sold"), head = true)
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            for (row in rows) {
                RowDivider()
                TrendLine(row.label, money(row.collected), money(row.sold))
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Theme.borderSoft))
        TrendLine(L("Total"), money(dashboard.totals.collected), money(dashboard.totals.invoiced), total = true)
    }
}

@Composable
private fun TrendLine(a: String, b: String, c: String, head: Boolean = false, total: Boolean = false) {
    val base = (if (head) brandText(10.6).tracking(1.06) else Theme.text(14)).monospacedDigit()
    val ink = if (head) Theme.inkSoft else Theme.inkStrong
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (head || total) 8.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (head) a.uppercase() else a, modifier = Modifier.weight(1f), style = base.copy(fontWeight = if (head) FontWeight.ExtraBold else FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (head) b.uppercase() else b, modifier = Modifier.weight(1f),
            style = base.copy(fontWeight = if (head || total) FontWeight.ExtraBold else FontWeight.Normal), color = ink, maxLines = 1, textAlign = TextAlign.End,
        )
        Text(
            if (head) c.uppercase() else c, modifier = Modifier.weight(1f),
            style = base.copy(fontWeight = if (head || total) FontWeight.ExtraBold else FontWeight.Normal), color = ink, maxLines = 1, textAlign = TextAlign.End,
        )
    }
}

// MARK: - Payments (level 3)

/** The methods' icons (iOS `CashFinancePayments.methodIcon`). */
object CashFinancePayments {
    fun methodIcon(method: String): String = when (method) {
        "cash" -> "banknote"
        "card" -> "credit-card"
        "bank_transfer" -> "landmark"
        "digital_wallet" -> "smartphone"
        "credit" -> "piggy-bank"
        else -> "sparkles"
    }
}

@Composable
fun CashFinancePayments(
    from: String,
    to: String,
    source: String?,
    zone: ZoneId? = null,
    kind: MutableState<String?>,
    method: MutableState<String?>,
    onClient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val wide = LocalSizeClass.current == SizeClass.regular
    val search = remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<CashLedgerPage.Summary?>(null) }
    val pager = remember { Pager<CashLedgerItem> { _, _ -> Page(items = emptyList(), total = 0) } }
    val filtered = method.value != null || kind.value != null || search.value.nonEmpty != null

    LaunchedEffect(from, to, source, kind.value, method.value, search.value, workspace.scopeVersion) {
        delay(250)
        val query: Map<String, String?> = mapOf(
            "search" to search.value.nonEmpty, "method" to method.value, "source" to source, "kind" to kind.value,
            "startDate" to from, "endDate" to to, "locationId" to workspace.listLocationFilter,
        )
        pager.fetch = { offset, limit ->
            val q = query + mapOf("limit" to "$limit", "offset" to "$offset")
            val page: CashLedgerPage = API.get("/provider-finance/payments", q)
            summary = page.summary
            Page(items = page.items, total = page.total, hasMore = page.hasMore)
        }
        pager.reload()
    }

    /** Every row of the period under these filters, 100 at a time. */
    suspend fun exportPayments(): Pair<String, String> {
        val rows = ArrayList<CashLedgerItem>()
        var offset = 0
        while (offset < 20_000) {
            val query: Map<String, String?> = mapOf(
                "limit" to "100", "offset" to "$offset", "startDate" to from, "endDate" to to, "search" to search.value.nonEmpty,
                "method" to method.value, "source" to source, "kind" to kind.value, "locationId" to workspace.listLocationFilter,
            )
            val chunk: CashLedgerPage = API.get("/provider-finance/payments", query)
            rows += chunk.items
            if (!chunk.hasMore || chunk.items.isEmpty()) break
            offset += 100
        }
        return CashCSV.fileName(L("payments"), from = from, to = to) to CashFinanceCSV.payments(rows, zone = workspace.timeZone)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        summary?.let { figures ->
            val money = { cents: Int -> cashMoney(cents) }
            CashFinGrid(
                items = listOf(
                    { CashFinStatBox(label = L("Net collected"), value = money(figures.totalAmount), hint = if (filtered) L("With these filters") else L("Payments less refunds")) },
                    { CashFinStatBox(label = L("Payments"), value = figures.paymentCount?.toString() ?: "—", hint = LF("%@ came in", money(figures.totalAmount + figures.refundedAmount))) },
                    {
                        CashFinStatBox(
                            label = L("Refunds"), value = figures.refundCount?.toString() ?: money(figures.refundedAmount),
                            hint = if (figures.refundedAmount > 0) LF("%@ given back", money(figures.refundedAmount)) else L("Nothing given back"),
                        )
                    },
                    { CashFinStatBox(label = L("Credit on account applied"), value = money(figures.creditAppliedAmount ?: 0), hint = L("Deposits spent on bills. Not added: counted when they came in.")) },
                ),
                columns = if (wide) 4 else 2,
            )
        }
        SearchField(prompt = L("Client, pet, folio or reference"), text = search)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in CashText.methods + "credit") {
                ToneChip(CashText.method(entry), icon = CashFinancePayments.methodIcon(entry), tone = ChipTone.hue(CashText.methodHue(entry)), isOn = method.value == entry) {
                    method.value = if (method.value == entry) null else entry
                }
            }
            ToneChip(L("Refunds only"), icon = "undo-2", tone = ChipTone.state(ProviderState.problem), isOn = kind.value == "refund") {
                kind.value = if (kind.value == "refund") null else "refund"
            }
        }
        Row {
            CashCSVButton(disabled = (pager.total ?: 0) == 0) { exportPayments() }
        }
        val error = pager.error
        when {
            !pager.loadedOnce && pager.isLoading -> LoadingState()
            error != null && pager.items.isEmpty() -> CashFinError(title = L("The payments could not be loaded."), message = error) { pager.reload() }
            pager.items.isEmpty() -> ListCard {
                EmptyState(
                    systemImage = "wallet",
                    title = if (filtered) L("No payments match these filters.") else L("No payments in this period."),
                    message = if (filtered) null else L("Try a longer period, or another location."),
                    actionTitle = if (filtered) L("Clear filters") else null,
                    action = { search.value = ""; method.value = null; kind.value = null },
                    accent = Theme.lime,
                )
            }
            else -> ListCard {
                pager.items.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    val clientId = row.providerClientId
                    PaymentRow(
                        row,
                        zone,
                        Modifier
                            .plainButton(enabled = clientId != null) { if (clientId != null) onClient(clientId) }
                            .onScrolledIntoView { pager.loadMoreIfNeeded(row) },
                    )
                }
                PagerFooter(pager)
            }
        }
        val total = pager.total
        if (total != null && total > 0) {
            CashFinListCount(text = if (pager.items.size < total) LF("Showing %d of %d", pager.items.size, total) else LP(total, "%d movement", "%d movements"))
        }
    }
}

@Composable
private fun PaymentRow(row: CashLedgerItem, zone: ZoneId?, modifier: Modifier = Modifier) {
    val refund = row.kind == "refund"
    val credit = row.method == "credit"
    val soft = Theme.inkSoft
    val details: List<String> = listOfNotNull(
        if (refund) L("Refund") else if (credit) L("Credit on account applied") else L("Payment"),
        row.invoiceNumber,
        row.source?.let { CashText.source(it) },
        CashText.method(row.method) + (row.reference.nonEmpty?.let { " · $it" } ?: ""),
        row.paidAt?.let { CashDay.`when`(it, zone = zone) },
        row.collectedBy?.name,
    ).mapNotNull { it.nonEmpty }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(
                    when {
                        refund -> Theme.wash(Theme.destructive, 12.0)
                        credit -> Theme.surfaceMuted
                        else -> Theme.wash(ProviderState.done.color, 22.0)
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                if (refund) "undo-2" else if (credit) "piggy-bank" else "wallet",
                16.dp,
                tint = if (refund) Theme.destructive else if (credit) Theme.inkSoft else Theme.inkStrong,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(row.clientName) }
                    row.petName.nonEmpty?.let { withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = soft)) { append(" · $it") } }
                },
                style = Theme.text(14),
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(details.joinToString("  "), style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "${if (refund) "−" else ""}${cashMoney(row.amount)}",
                style = Theme.text(14, FontWeight.Bold).monospacedDigit(),
                color = if (refund && !credit) Theme.destructive else if (credit) Theme.inkSoft else Theme.inkStrong,
            )
            val late = row.daysLate
            if (credit) {
                Text(L("Not added to the total"), style = Theme.text(12), color = Theme.inkSoft)
            } else if (!refund && late != null && late > 0) {
                Text(CashText.daysLate(late), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - Reports (level 3)

@Composable
fun CashFinanceReports(from: String, to: String, source: String?, group: MutableState<String>, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    var report by remember { mutableStateOf<CashSalesReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val groups: List<Triple<String, String, String>> = buildList {
        add(Triple("item", L("Product or service"), "package"))
        add(Triple("category", L("Category"), "shapes"))
        add(Triple("staff", L("Who sold"), "users"))
        add(Triple("day", L("Day"), "calendar-days"))
        add(Triple("source", L("Source"), "layers"))
        if (workspace.listLocationFilter == Workspace.allLocationsId) add(Triple("location", L("Location"), "map-pin"))
    }
    val groupLabel = groups.firstOrNull { it.first == group.value }?.second ?: L("Product or service")

    suspend fun load() {
        loading = true
        try {
            report = API.get("/provider-finance/reports/sales", mapOf("from" to from, "to" to to, "groupBy" to group.value, "source" to source, "locationId" to workspace.listLocationFilter))
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = errorText(failure)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(from, to, source, group.value, workspace.scopeVersion) { load() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // "For your accountant": every sale and bill of the period, one per row.
        Card(padding = 16.dp, spacing = 12.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Glyph("file-spreadsheet", 20.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("For your accountant"), style = Theme.heading(18), color = Theme.inkStrong)
                    Text(
                        L("Every sale and bill of the period, one per row: date, folio, client, subtotal, tax, total, method and reference."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
            }
            CashCSVButton(title = L("Export sales and bills"), disabled = (report?.totals?.documents ?: 0) == 0) {
                val documents: CashDocumentsReport = API.get("/provider-finance/reports/documents", mapOf("from" to from, "to" to to, "source" to source, "locationId" to workspace.listLocationFilter))
                CashCSV.fileName(L("sales and bills"), from = from, to = to) to CashFinanceCSV.documents(documents)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in groups) {
                ToneChip(entry.second, icon = entry.third, tone = ChipTone.page, isOn = group.value == entry.first) { group.value = entry.first }
            }
        }
        Row {
            CashCSVButton(disabled = report?.rows?.isEmpty() ?: true) {
                val made = report ?: throw IllegalStateException("No report to export")
                CashCSV.fileName("${L("sales")} $groupLabel", from = from, to = to) to
                    CashFinanceCSV.sales(made, group = groupLabel, label = { reportLabel(it, made) }, margin = workspace.moneyLevel >= MoneyLevel.finances)
            }
        }
        val loaded = report
        val failed = error
        if (loaded != null) {
            ReportTable(loaded, group.value)
            if (loaded.rows.isNotEmpty()) {
                CashFinListCount(
                    text = LP(loaded.rows.size, "%d row", "%d rows"),
                    trailing = if (loaded.totals.documents == 1) {
                        LF("%@ sold in 1 sale or bill", cashMoney(loaded.totals.revenue, loaded.currency))
                    } else {
                        LF("%@ sold in %d sales and bills", cashMoney(loaded.totals.revenue, loaded.currency), loaded.totals.documents)
                    },
                )
            }
            Text(
                L("Sold includes tax and leaves out returns and credit notes; a bill’s discount and tax are shared over its lines, so the rows add up to the total. Cost and margin count only products with a cost, before tax."),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
        } else if (failed != null) {
            CashFinError(title = L("The report could not be loaded."), message = failed) { load() }
        } else {
            LoadingState()
        }
    }
}

@Composable
private fun ReportTable(report: CashSalesReport, group: String) {
    ListCard {
        if (report.rows.isEmpty()) {
            EmptyState(systemImage = "chart-column", title = L("No sales yet in this period."), message = L("Try a longer period, or another location."), accent = Theme.lime)
        } else {
            val top = max(1, report.rows.maxOfOrNull { it.revenue } ?: 1).toDouble()
            val showQuantity = group == "item" || group == "category"
            val money = { cents: Int -> cashMoney(cents, report.currency) }
            report.rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(reportLabel(row, report), modifier = Modifier.weight(1f).alignByBaseline(), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(12.dp))
                        Text(money(row.revenue), modifier = Modifier.alignByBaseline(), style = Theme.text(16, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                    }
                    ProgressBar(value = row.revenue.toDouble() / top, height = 6.dp, track = Theme.surfaceMuted, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        listOfNotNull(
                            LP(row.documents, "%d sale or bill", "%d sales and bills"),
                            if (showQuantity && row.key != "unitemized") LF("%d sold", row.quantity) else null,
                            if ((row.tax ?: 0) > 0) LF("%@ tax", money(row.tax ?: 0)) else null,
                            row.margin?.let { LF("margin %@", money(it)) },
                        ).joinToString(" · "),
                        modifier = Modifier.padding(top = 4.dp),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Theme.borderSoft))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportTotalLine(L("Total"), money(report.totals.revenue))
                if (report.totals.costed == true || report.totals.cost > 0) {
                    ReportTotalLine(L("Cost"), money(report.totals.cost), soft = true)
                    ReportTotalLine(L("Margin"), money(report.totals.margin), soft = true)
                }
            }
        }
    }
}

@Composable
private fun ReportTotalLine(label: String, value: String, soft: Boolean = false) {
    val style = Theme.text(if (soft) 14 else 16, if (soft) FontWeight.Bold else FontWeight.ExtraBold)
    val ink = if (soft) Theme.inkSoft else Theme.inkStrong
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = style, color = ink)
        Spacer(Modifier.width(8.dp))
        Text(value, style = style.monospacedDigit(), color = ink)
    }
}

private fun reportLabel(row: CashSalesReport.Row, report: CashSalesReport): String {
    if (row.key == "unitemized") return L("Bills without line items")
    if (report.groupBy == "source") return CashText.source(row.key)
    if (report.groupBy == "day") return CashDay.long(row.key)
    if (report.groupBy == "category" && row.label.isEmpty()) return if (row.detail == "product") L("Products without a category") else L("Services without a category")
    if (report.groupBy == "staff" && row.label.isEmpty()) return L("Someone who left")
    if (report.groupBy == "location" && row.label.isEmpty()) return L("Main location")
    return row.label
}
