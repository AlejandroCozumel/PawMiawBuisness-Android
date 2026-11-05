package com.pawmiaw.business.money

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

//
//  CashFinanceCortes.kt (iOS CashFinanceCortes.swift)
//
//  Cortes del día (web: finance/finance-cortes.tsx): one day's money by
//  method, where it came from, who charged, what went back, every register
//  shift, and "Excepciones del día" — refunds, returns, cancels, big
//  discounts, courtesies, lower prices, cash in and out, drawer differences —
//  who, when, how much and why. The front desk reads the last 7 days; business
//  finances any day (docs/money-access-levels.md).
//
//  The web's print (an 80 mm ticket or a page) is the browser's; here the
//  day's close PDF is the printable copy.
//

private fun cortesMono(size: Int): TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp)

private fun cortesZone(id: String?): ZoneId? = id?.let { runCatching { ZoneId.of(it) }.getOrNull() }

/** The day's statics (iOS `CashFinanceCortes.dayTitle` / `refundReason`). */
object CashFinanceCortes {
    /** "Sábado, 3 de octubre de 2026": only the first letter raised. */
    fun dayTitle(key: String): String {
        val date = CashDay.date(key) ?: return key
        val text = DateText.format(date, "EEEEdMMMMyyyy")
        return text.take(1).uppercase() + text.drop(1)
    }

    /** A return's old note "Refund D-000001" reads by its folio. */
    fun refundReason(reason: String?): String? {
        val text = reason?.trim { it == ' ' || it == '\t' || (it.isWhitespace() && it != '\n' && it != '\r') } ?: return null
        if (text.isEmpty()) return null
        if (text.startsWith("Refund D-") && text.drop(7).all { it == 'D' || it == '-' || it.isDigit() }) return LF("Return %@", text.drop(7))
        if (text.startsWith("D-") && text.drop(2).all { it.isDigit() }) return LF("Return %@", text)
        return text
    }

    val groups: List<Pair<String, String>>
        get() = listOf(
            "refund" to L("Refunds"), "credit_note" to L("Credit notes"), "return" to L("Returns"), "refund_other_method" to L("Refunded another way than paid"),
            "cancel" to L("Cancellations"), "discount" to L("Discounts"), "courtesy" to L("Courtesies"), "price_lowered" to L("Lower prices"),
            "no_charge" to L("Visits without charge"), "stay_no_bill" to L("Check-outs without a bill"), "cash_in" to L("Cash in"), "cash_out" to L("Cash out"),
            "cash_difference" to L("Drawer differences"), "backdated_payment" to L("Backdated payments"),
        )
}

@Composable
fun CashFinanceCortes(
    /** The day shown; nil is today (the overview's register rows set it). */
    day: MutableState<String?>,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val wide = LocalSizeClass.current == SizeClass.regular
    var data by remember { mutableStateOf<CashDayClose?>(null) }
    var exceptions by remember { mutableStateOf<CashExceptions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var exceptionsError by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val today = data?.today ?: CashDay.today
    val current = day.value ?: data?.day ?: today
    val firstDay: String? = if (workspace.moneyLevel >= MoneyLevel.finances) null else CashDay.frontDeskFirst(today)

    fun go(next: String) {
        // Today is the default, and stays "today" as the days turn.
        day.value = if (next >= today) null else next
    }

    suspend fun load() {
        val query: Map<String, String?> = mapOf("day" to (day.value ?: "today"), "locationId" to workspace.listLocationFilter)
        supervisorScope {
            val close = async { API.get<CashDayClose>("/provider-finance/day-close", query) }
            val list = async { API.get<CashExceptions>("/provider-finance/exceptions", query) }
            try {
                data = close.await()
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (data == null || day.value != null) {
                    data = null
                    error = errorText(failure)
                }
            }
            try {
                exceptions = list.await()
                exceptionsError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                exceptions = null
                exceptionsError = errorText(failure)
            }
        }
    }

    val latestLoad by rememberUpdatedState<suspend () -> Unit>({ load() })
    LaunchedEffect(day.value ?: "today", workspace.scopeVersion) { load() }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { latestLoad() } } }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // MARK: Day bar (web: ‹ date › · Today · PDF)
        FlowLayout(spacing = 8.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(systemImage = "chevron-left", label = L("Day before"), enabled = !(firstDay != null && current <= firstDay)) { go(CashDay.shift(current, -1)) }
                CortesDayPill(current, firstDay, today) { go(it) }
                IconCircleButton(systemImage = "chevron-right", label = L("Day after"), enabled = current < today) { go(CashDay.shift(current, 1)) }
                if (data?.isToday == false) {
                    PMButton(L("Today"), variant = ButtonVariant.outline, size = ButtonSize.compact) { day.value = null }
                }
            }
            Disabled(data == null) {
                DocumentButton(
                    title = L("Download PDF"),
                    systemImage = "file-down",
                    path = "/provider-finance/day-close/pdf",
                    query = mapOf("day" to current, "locationId" to workspace.listLocationFilter),
                    fileName = "${L("Day close report")} $current",
                )
            }
        }
        val loaded = data
        val failed = error
        if (loaded != null) {
            Text(
                if (loaded.isToday) "${L("Today")} · ${CashFinanceCortes.dayTitle(loaded.day)}" else CashFinanceCortes.dayTitle(loaded.day),
                modifier = Modifier.padding(horizontal = 4.dp),
                style = Theme.heading(20),
                color = Theme.inkStrong,
            )
            CortesClose(loaded, wide, expanded, onExpand = { expanded = it })
            CortesExceptions(exceptions, exceptionsError, onRetry = { load() }, onShift = { expanded = it })
        } else if (failed != null) {
            CashFinError(title = L("Could not load this day."), message = failed) { load() }
        } else {
            LoadingState()
        }
    }
}

/**
 * The day as a 40 pt pill over the calendar, held to the days this level reads
 * (the kit's `PillDatePicker` takes no range).
 */
@Composable
private fun CortesDayPill(current: String, firstDay: String?, today: String, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val dayLabel = L("Day")
    PillField(
        text = CashDay.short(current),
        icon = "calendar-days",
        modifier = Modifier
            .clip(CapsuleShape)
            .semantics { contentDescription = dayLabel }
            .plainButton { picking = true },
    )
    if (picking) {
        val lower = firstDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val todayDate = runCatching { LocalDate.parse(today) }.getOrNull() ?: DateText.today()
        val upper = if (lower != null && lower > todayDate) lower else todayDate
        DayPickerSheet(
            initial = runCatching { LocalDate.parse(current) }.getOrNull() ?: todayDate,
            min = lower,
            max = upper,
            onPick = { onPick(it.toString()) },
            onDismiss = { picking = false },
        )
    }
}

// MARK: The close

@Composable
private fun CortesClose(data: CashDayClose, wide: Boolean, expanded: String?, onExpand: (String?) -> Unit) {
    val money = { cents: Int -> cashMoney(cents, data.currency) }
    val places = data.byLocation
    if (places != null && places.size >= 2) CortesByLocation(places, money)
    CashFinGrid(items = cortesStats(data, money), columns = if (wide) 4 else 2)
    CortesMethods(data, money, wide)
    CortesSources(data, money)
    CortesPeople(data, money)
    if (data.refundItems.isNotEmpty()) {
        CortesPanel(L("Refunds"), icon = "undo-2") { CortesRefundList(data.refundItems, data, asCredit = false) }
    }
    val credits = data.creditRefundItems
    if (!credits.isNullOrEmpty()) {
        CortesPanel(L("Given back as credit on the account"), icon = "wallet", aside = {
            Text(L("Not money: it stays on the client’s account."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
        }) { CortesRefundList(credits, data, asCredit = true) }
    }
    CortesShifts(data, money, expanded, onExpand)
}

@Composable
private fun cortesStats(data: CashDayClose, money: (Int) -> String): List<@Composable () -> Unit> {
    val totals = data.shiftTotals
    val card = data.byMethod.firstOrNull { it.method == "card" }
    val cashHint: AnnotatedString = when {
        totals.shifts == 0 -> AnnotatedString(L("No register shifts"))
        totals.open > 0 -> AnnotatedString(LP(totals.open, "%d shift still open", "%d shifts still open"))
        else -> buildAnnotatedString {
            append("${L("Counted")} ")
            append(cortesDifference(totals.cashDifference, data.currency))
        }
    }
    val cardHint = totals.cardTerminalTotal?.let { terminal -> if (totals.cardDifference == 0) L("Matches the terminal") else LF("Terminal %@", money(terminal)) } ?: L("Terminal not entered")
    return listOf(
        { CashFinCorteStat(icon = "banknote", accent = Theme.lime, label = L("Money in, net"), value = money(data.totals.net), hint = AnnotatedString(LP(data.totals.payments, "%d payment", "%d payments"))) },
        {
            CashFinCorteStat(
                icon = "wallet", accent = Theme.mango, label = L("Cash"), value = money(data.byMethod.firstOrNull { it.method == "cash" }?.net ?: 0),
                hint = cashHint, bad = totals.shifts > 0 && totals.open == 0 && totals.cashDifference < 0,
            )
        },
        { CashFinCorteStat(icon = "credit-card", accent = Theme.aqua, label = L("Card"), value = money(card?.net ?: 0), hint = AnnotatedString(cardHint)) },
        { CashFinCorteStat(icon = "undo-2", accent = Theme.pink, label = L("Given back"), value = money(data.totals.refunded), hint = AnnotatedString(LP(data.totals.refunds, "%d refund", "%d refunds"))) },
    )
}

/** The web's `DifferenceLabel` inside a line: exact in blue, short in red, over in mango. */
@Composable
private fun cortesDifference(difference: Int, currency: String): AnnotatedString {
    val blue = Theme.blue
    val red = Theme.destructive
    return buildAnnotatedString {
        when {
            difference == 0 -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = blue)) { append(L("Exact")) }
            difference < 0 -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = red)) { append(LF("Short %@", cashMoney(-difference, currency))) }
            else -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Theme.mango)) { append(LF("Over %@", cashMoney(difference, currency))) }
        }
    }
}

// MARK: Panels (web `Panel`: a card with a titled head over a hairline)

@Composable
private fun CortesPanel(title: String, icon: String, aside: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    SectionCard(title = title, icon = icon, accent = Theme.blue, trailing = { aside() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
private fun CortesByLocation(places: List<CashDayClose.Place>, money: (Int) -> String) {
    val box = RoundedCornerShape(Theme.boxRadius)
    CortesPanel(L("By location"), icon = "map-pin") {
        CashFinAdaptiveGrid(count = places.size, minimum = 220.dp, spacing = 8.dp) { index ->
            val place = places[index]
            Column(Modifier.fillMaxSize().background(Theme.surface, box).border(1.dp, Theme.borderSoft, box).padding(12.dp)) {
                Text(place.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(money(place.net), style = Theme.heading(20).monospacedDigit(), color = Theme.inkStrong)
                Text(
                    LP(place.payments, "%d payment", "%d payments") + (if (place.openShifts > 0) " · " + LP(place.openShifts, "%d shift still open", "%d shifts still open") else ""),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CortesMethods(data: CashDayClose, money: (Int) -> String, wide: Boolean) {
    val strong = Theme.inkStrong
    CortesPanel(L("Money in by method"), icon = "scale") {
        Column(Modifier.fillMaxWidth()) {
            CortesMethodLine(L("Method"), L("In"), L("Back@money"), L("Net"), wide, head = true)
            data.byMethod.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                CortesMethodLine(CashText.method(row.method), money(row.payments), if (row.refunds > 0) "−${money(row.refunds)}" else "—", money(row.net), wide, dim = row.count == 0)
            }
            RowDivider()
            CortesMethodLine(L("Total"), money(data.totals.collected), if (data.totals.refunded > 0) "−${money(data.totals.refunded)}" else "—", money(data.totals.net), wide, total = true)
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append("${L("Credit on account")}: ") }
                append(
                    if (data.credit.spent > 0 || data.credit.returned > 0) {
                        LF(
                            "%@ spent from deposits%@ — not new money, it was counted when the deposit came in.",
                            money(data.credit.spent),
                            if (data.credit.returned > 0) LF(", %@ given back as credit", money(data.credit.returned)) else "",
                        )
                    } else {
                        L("none used today.")
                    },
                )
            },
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = Theme.text(12),
            color = Theme.inkSoft,
        )
        val outside = data.outsideShifts
        if (outside != null && outside.count > 0) {
            Text(LF("%@ was taken with no register shift open (card or transfer).", money(outside.net)), modifier = Modifier.padding(top = 8.dp), style = Theme.text(12), color = Theme.inkSoft)
        }
    }
}

@Composable
private fun CortesMethodLine(method: String, paid: String, back: String, net: String, wide: Boolean, head: Boolean = false, total: Boolean = false, dim: Boolean = false) {
    val base = (if (head) brandText(10.6).tracking(1.27) else Theme.text(14)).monospacedDigit()
    val ink = if (head || dim) Theme.inkSoft else Theme.inkStrong
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (head) 0.dp else 6.dp)
            .padding(bottom = if (head) 6.dp else 0.dp)
            .padding(top = if (total) 2.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (head) method.uppercase() else method, modifier = Modifier.weight(1f),
            style = base.copy(fontWeight = if (head || total) FontWeight.ExtraBold else FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (head) paid.uppercase() else paid, modifier = Modifier.weight(1f),
            style = base.copy(fontWeight = if (head) FontWeight.ExtraBold else if (total) FontWeight.Bold else FontWeight.Normal), color = ink, maxLines = 1, textAlign = TextAlign.End,
        )
        if (wide) {
            Text(
                if (head) back.uppercase() else back, modifier = Modifier.weight(1f),
                style = base.copy(fontWeight = if (head) FontWeight.ExtraBold else FontWeight.Normal), color = ink, maxLines = 1, textAlign = TextAlign.End,
            )
        }
        Text(
            if (head) net.uppercase() else net, modifier = Modifier.weight(1f),
            style = base.copy(fontWeight = FontWeight.ExtraBold), color = ink, maxLines = 1, textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun CortesSources(data: CashDayClose, money: (Int) -> String) {
    val soft = Theme.inkSoft
    CortesPanel(L("Where it came from"), icon = "banknote") {
        if (data.bySource.isEmpty()) {
            EmptyState(systemImage = "banknote", title = L("Nothing came in this day."), accent = Theme.lime)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in data.bySource) {
                    val tone = if (row.source == "deposit") Theme.lime else CashText.sourceHue(row.source).color
                    val share = if (data.totals.net > 0) max(0.0, row.net.toDouble() / data.totals.net.toDouble()) else 0.0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(tone, RoundedCornerShape(Theme.tickRadius / 2)))
                            Text(CashText.source(row.source), modifier = Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                            Text(
                                buildAnnotatedString {
                                    withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle()) { append(money(row.net)) }
                                    withStyle(Theme.text(12).toSpanStyle().copy(color = soft)) { append(" · ${row.count}") }
                                },
                                style = Theme.text(14).monospacedDigit(),
                                color = Theme.inkStrong,
                            )
                        }
                        ProgressBar(value = share, height = 6.dp, track = Theme.surfaceMuted, fill = tone)
                    }
                }
            }
        }
    }
}

@Composable
private fun CortesPeople(data: CashDayClose, money: (Int) -> String) {
    val soft = Theme.inkSoft
    CortesPanel(L("Who charged"), icon = "users") {
        if (data.byPerson.isEmpty()) {
            EmptyState(systemImage = "users", title = L("Nobody charged this day."), accent = Theme.lime)
        } else {
            Column {
                data.byPerson.forEachIndexed { index, person ->
                    if (index > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(person.name ?: "—", modifier = Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildAnnotatedString {
                                withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle()) { append(money(person.net ?: (person.payments - person.refunds))) }
                                withStyle(Theme.text(12).toSpanStyle().copy(color = soft)) { append(" · ${LP(person.count ?: 0, "%d movement", "%d movements")}") }
                            },
                            style = Theme.text(14).monospacedDigit(),
                            color = Theme.inkStrong,
                        )
                    }
                }
            }
        }
    }
}

/** Refunds of the day: money handed back (with a minus), or credit left on the account (no money moved). */
@Composable
private fun CortesRefundList(items: List<CashDayClose.Refund>, data: CashDayClose, asCredit: Boolean) {
    val nav = LocalNavigator.current
    val soft = Theme.inkSoft
    val zone = cortesZone(data.timeZone)
    Column(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            if (index > 0) RowDivider()
            Row(
                Modifier.fillMaxWidth().plainButton { nav.push(ProviderRoute.invoice(item.invoiceId)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle()) { append(item.clientName ?: L("Walk-in")) }
                            withStyle(cortesMono(12).toSpanStyle().copy(color = soft)) { append(" ${item.folio}") }
                        },
                        color = Theme.inkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(
                            if (asCredit) null else CashText.method(item.method),
                            item.by,
                            CashDay.`when`(item.at, withDay = false, zone = zone),
                            CashFinanceCortes.refundReason(item.reason),
                        ).mapNotNull { it.nonEmpty }.joinToString(" · "),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (asCredit) cashMoney(item.amount, data.currency) else "−${cashMoney(item.amount, data.currency)}",
                    style = Theme.text(14, FontWeight.Bold).monospacedDigit(),
                    color = if (asCredit) Theme.inkStrong else Theme.destructive,
                )
            }
        }
    }
}

// MARK: Register shifts

@Composable
private fun CortesShifts(data: CashDayClose, money: (Int) -> String, expanded: String?, onExpand: (String?) -> Unit) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val totals = data.shiftTotals
    SectionCard(
        title = L("Register shifts"),
        icon = "wallet",
        accent = Theme.blue,
        trailing = {
            if (cashCanRunShift(workspace)) {
                CashFinCardLinkLabel(title = L("Open the register"), modifier = Modifier.clip(CapsuleShape).plainButton { nav.push(ProviderRoute.section(ProviderSection.store)) })
            }
        },
    ) {
        if (totals.shifts > 0) {
            Text(
                LF("Expected %@ · counted %@", money(totals.cashExpected), money(totals.cashCounted)),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = Theme.text(12, FontWeight.SemiBold),
                color = Theme.inkSoft,
            )
            RowDivider()
        }
        if (data.shifts.isEmpty()) {
            EmptyState(systemImage = "wallet", title = L("No register was open this day."), accent = Theme.lime)
        } else {
            Column(Modifier.fillMaxWidth()) {
                data.shifts.forEachIndexed { index, shift ->
                    if (index > 0) RowDivider()
                    key(shift.id) { CortesShiftRow(shift, data, expanded == shift.id) { onExpand(if (expanded == shift.id) null else shift.id) } }
                }
            }
        }
    }
}

@Composable
private fun CortesShiftRow(shift: CashSession, data: CashDayClose, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, animationSpec = tween(200), label = "shift-chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { selected = open }
                .plainButton(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(shift.registerName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (shift.isOpen) {
                        LF("%@ by %@ · still open", CashDay.`when`(shift.openedAt, zone = shift.zone), shift.openedByName ?: "—")
                    } else {
                        LF("%@ by %@ → %@ by %@", CashDay.`when`(shift.openedAt, zone = shift.zone), shift.openedByName ?: "—", CashDay.`when`(shift.closedAt, zone = shift.zone), shift.closedByName ?: "—")
                    },
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (shift.isOpen) {
                    Text(L("Open@register"), style = Theme.text(14, FontWeight.Bold), color = Theme.mango)
                } else {
                    CashDifferenceText(difference = shift.difference ?: 0, currency = data.currency)
                }
                Text(cashMoney(shift.countedCash ?: shift.expectedCash, data.currency), style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft)
            }
            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
        ) {
            Box(Modifier.fillMaxWidth().background(Theme.wash(Theme.blue, 4.0))) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CashShiftDetail(session = shift)
                    DocumentButton(
                        title = L("Download PDF"),
                        systemImage = "file-down",
                        path = "/provider-store/register-sessions/${shift.id}/pdf",
                        fileName = "${shift.registerName} ${shift.openedDay ?: ""}",
                    )
                }
                RowDivider(modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

// MARK: Excepciones del día

@Composable
private fun CortesExceptions(exceptions: CashExceptions?, exceptionsError: String?, onRetry: suspend () -> Unit, onShift: (String) -> Unit) {
    if (exceptions != null) {
        val present = CashFinanceCortes.groups.mapNotNull { group ->
            val items = exceptions.items.filter { it.kind == group.first }
            if (items.isEmpty()) null else group.second to items
        }
        CortesPanel(L("Exceptions of the day"), icon = "triangle-alert", aside = {
            if (exceptions.withoutReason > 0) {
                Text(LP(exceptions.withoutReason, "%d without a reason", "%d without a reason"), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
        }) {
            if (present.isEmpty()) {
                EmptyState(systemImage = "triangle-alert", title = L("Nothing out of the ordinary this day."), accent = Theme.lime)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        L("Refunds, returns, cancels, big discounts, courtesies, lower prices, cash in and out, drawer differences and backdated payments: who, when, how much and why."),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                    for ((title, items) in present) {
                        key(title) {
                            CashExceptionGroup(title = title, items = items, currency = exceptions.currency, day = exceptions.day, zone = cortesZone(exceptions.timeZone), onShift = onShift)
                        }
                    }
                }
            }
        }
    } else if (exceptionsError != null) {
        CashFinError(title = L("The exceptions could not be loaded."), message = exceptionsError) { onRetry() }
    } else {
        LoadingState()
    }
}

/** A figure of the day (web cortes `Stat`): a tinted 24 icon disc beside the name, the amount, a line. */
@Composable
private fun CashFinCorteStat(icon: String, accent: androidx.compose.ui.graphics.Color, label: String, value: String, hint: AnnotatedString, bad: Boolean = false) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxSize()
            .cardShadow(box)
            .background(Theme.surface, box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(Theme.wash(accent, 24.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(icon, 14.dp, tint = Theme.inkStrong)
            }
            Text(label.uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.27), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FittedText(value, Modifier.padding(top = 6.dp), style = Theme.heading(20).monospacedDigit(), color = if (bad) Theme.destructive else Theme.inkStrong, minimumScaleFactor = 0.6f)
        Text(hint, style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One kind of exception: its first rows, then "Ver N más". */
@Composable
private fun CashExceptionGroup(title: String, items: List<CashException>, currency: String, day: String, zone: ZoneId?, onShift: (String) -> Unit) {
    var all by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title · ${items.size}".uppercase(), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.27), color = Theme.inkSoft)
        Column(Modifier.fillMaxWidth()) {
            (if (all) items else items.take(5)).forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                CortesExceptionRow(item, currency, day, zone, onShift)
            }
        }
        if (!all && items.size > 5) {
            Box(Modifier.height(40.dp).clip(CapsuleShape).plainButton { all = true }.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(LF("Show %d more", items.size - 5), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            }
        }
    }
}

private fun cortesSign(item: CashException): String {
    if (item.direction == "in" || item.direction == "over") return "+"
    if (item.direction == "out" || item.direction == "short") return "−"
    if (item.kind == "backdated_payment") return "+"
    return if (item.kind in listOf("discount", "courtesy", "price_lowered", "no_charge", "stay_no_bill")) "" else "−"
}

private fun cortesRoute(item: CashException): ProviderRoute? = when (item.link.to) {
    "sale" -> ProviderRoute.sale(item.link.id)
    "invoice" -> ProviderRoute.invoice(item.link.id)
    "visit" -> ProviderRoute.appointment(item.link.id)
    "stay" -> ProviderRoute.stay(item.link.id)
    else -> null
}

@Composable
private fun CortesExceptionRow(item: CashException, currency: String, day: String, zone: ZoneId?, onShift: (String) -> Unit) {
    val nav = LocalNavigator.current
    val soft = Theme.inkSoft
    val who = listOfNotNull(item.clientName.nonEmpty, item.petName.nonEmpty).joinToString(" · ")
    val what = listOf(who.nonEmpty ?: item.registerName ?: (if (item.documentKind == "sale") L("Walk-in") else null), item.lineLabel).mapNotNull { it.nonEmpty }.joinToString(" — ")
    val paidWith = item.paidWith ?: emptyList()
    val details: List<String> = listOfNotNull(
        CashDay.`when`(item.at, withDay = false, zone = zone),
        item.actorName?.let { LF("By %@", it) },
        if (item.kind == "refund_other_method" && paidWith.isNotEmpty()) LF("Paid with %@", paidWith.joinToString(", ") { CashText.method(it) }) else null,
        item.method?.let { CashText.method(it) },
        if (item.kind == "backdated_payment") item.paidOn?.let { LF("Dated %@", CashDay.short(it)) } else null,
        item.visitOn?.let { if (it != day) LF("Visit of %@", CashDay.short(it)) else null },
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle()) { append(what.nonEmpty ?: "—") }
                    item.documentFolio?.let { withStyle(cortesMono(12).toSpanStyle().copy(color = soft)) { append(" $it") } }
                },
                color = Theme.inkStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(details.joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val reason = item.reason.nonEmpty
            if (reason != null) {
                Text("“$reason”", style = Theme.text(12), color = Theme.inkStrong)
            } else {
                Text(L("No reason"), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${cortesSign(item)}${cashMoney(item.amount, currency)}", style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
            val target = cortesRoute(item)
            if (target != null) {
                CashFinCardLinkLabel(title = L("Open@link"), modifier = Modifier.clip(CapsuleShape).plainButton { nav.push(target) })
            } else if (item.link.to == "session") {
                CashFinCardLinkLabel(title = L("Open@link"), modifier = Modifier.clip(CapsuleShape).plainButton { onShift(item.link.id) })
            }
        }
    }
}
