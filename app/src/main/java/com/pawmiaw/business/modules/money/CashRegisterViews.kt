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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

//
//  CashRegisterViews.kt (iOS CashRegisterViews.swift)
//
//  The cash drawer (web: store-register.tsx, register/shift-forms.tsx,
//  register/shift-detail.tsx): open a shift with the cash in the drawer,
//  cash in and out with a reason, close it against a count (bills and coins
//  helper, the card terminal, what stays for the next shift, the reason for
//  any difference), and the shifts before.
//

// MARK: - Bills and coins

object CashDenominations {
    data class Piece(val id: String, val isBill: Boolean, val value: Int)

    private val sets: Map<String, Pair<List<Int>, List<Int>>> = mapOf(
        "MXN" to (listOf(100_000, 50_000, 20_000, 10_000, 5_000, 2_000) to listOf(2_000, 1_000, 500, 200, 100, 50)),
        "USD" to (listOf(10_000, 5_000, 2_000, 1_000, 500, 100) to listOf(25, 10, 5, 1)),
        "CAD" to (listOf(10_000, 5_000, 2_000, 1_000, 500) to listOf(200, 100, 25, 10, 5)),
        "EUR" to (listOf(50_000, 20_000, 10_000, 5_000, 2_000, 1_000, 500) to listOf(200, 100, 50, 20, 10, 5, 2, 1)),
        "COP" to (listOf(10_000_000, 5_000_000, 2_000_000, 1_000_000, 500_000, 200_000) to listOf(100_000, 50_000, 20_000, 10_000, 5_000)),
    )

    fun pieces(currency: String): List<Piece>? {
        val set = sets[currency.uppercase()] ?: return null
        return set.first.map { Piece(id = "bill:$it", isBill = true, value = it) } + set.second.map { Piece(id = "coin:$it", isBill = false, value = it) }
    }

    /** "$500", "$0.50". */
    fun face(cents: Int): String =
        if (cents % 100 == 0) "\$" + NumberFormat.getIntegerInstance().format(cents / 100) else String.format(Locale.US, "\$%.2f", cents / 100.0)
}

// MARK: - Layout helpers (SwiftUI's ViewThatFits and a fixed-column grid)

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

/** A grid of even columns inside a scrolling page (SwiftUI `LazyVGrid` with `.flexible()` / `.adaptive` columns). */
@Composable
private fun EvenGrid(columns: Int, spacing: Dp, cells: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        for (start in cells.indices step columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                for (index in start until start + columns) {
                    Box(Modifier.weight(1f)) { cells.getOrNull(index)?.invoke() }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveGrid(minimum: Dp, spacing: Dp, cells: List<@Composable () -> Unit>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        EvenGrid(columns, spacing, cells)
    }
}

/** Swift `try?`. */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

// MARK: - Small parts (web: shift-forms.tsx, shift-detail.tsx)

private val microCaps = brandText(10.6, FontWeight.ExtraBold).tracking(1.27)

/**
 * A label over a figure in a bordered box (web `Figure`): 10.6 caps, the
 * value in Fredoka 18, a hint; `strong` takes the blue edge.
 */
@Composable
private fun CashFigureBox(label: String, value: String, hint: String? = null, strong: Boolean = false) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, if (strong) Theme.blue else Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = microCaps, color = Theme.inkSoft, maxLines = 1)
        FittedText(value, style = Theme.heading(18).monospacedDigit(), color = Theme.inkStrong, minimumScaleFactor = 0.7f)
        if (hint != null) Text(hint, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1)
    }
}

/** One line of a shift's summary (web `Line`): the label (and a hint under it), the amount right-aligned. */
@Composable
private fun CashShiftLine(label: String, hint: String? = null, strong: Boolean = false, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).alignBy(FirstBaseline), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = Theme.text(14), color = Theme.inkSoft)
            hint.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.alignBy(FirstBaseline)) {
            CompositionLocalProvider(
                LocalTextStyle provides Theme.text(14, if (strong) FontWeight.ExtraBold else FontWeight.Bold).monospacedDigit().copy(textAlign = TextAlign.End),
            ) { value() }
        }
    }
}

@Composable
private fun CashShiftLine(label: String, value: String, hint: String? = null, strong: Boolean = false, tone: Color? = null) {
    CashShiftLine(label = label, hint = hint, strong = strong) { Text(value, color = tone ?: Theme.inkStrong) }
}

/** A caps heading inside a shift's summary, with an optional icon. */
@Composable
private fun CashShiftHeading(text: String, icon: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Glyph(icon, 14.dp, tint = Theme.inkSoft)
        Text(text.uppercase(), style = brandText(11.2, FontWeight.ExtraBold).tracking(1.34), color = Theme.inkSoft)
    }
}

// MARK: - A shift's figures

/**
 * The shift at a glance (web `ShiftFigures`): sales, the cash expected, each other
 * method, cash in and out — two to a row, four on a wide screen.
 */
@Composable
fun CashShiftFigures(session: CashSession, showExpected: Boolean = true, showCash: Boolean = false, modifier: Modifier = Modifier) {
    val currency = Workspace.shared.currency
    val columns = if (LocalSizeClass.current == SizeClass.regular) 4 else 2
    val cells = buildList<@Composable () -> Unit> {
        add { CashFigureBox(label = L("Sales"), value = "${session.saleCount}", hint = cashMoney(session.salesTotal, currency)) }
        if (showExpected) {
            add { CashFigureBox(label = L("Cash expected"), value = cashMoney(session.expectedCash, currency), hint = LF("Starting cash %@", cashMoney(session.openingCash, currency)), strong = true) }
        }
        session.byMethod.filter { showCash || it.method != "cash" }.forEach { entry ->
            add {
                CashFigureBox(
                    label = CashText.method(entry.method), value = cashMoney(entry.payments - entry.refunds, currency),
                    hint = if (entry.refunds > 0) LF("%@ refunded", cashMoney(entry.refunds, currency)) else null,
                )
            }
        }
        if (session.cashIn > 0 || session.cashOut > 0) {
            add { CashFigureBox(label = L("Cash in / out"), value = "+${cashMoney(session.cashIn, currency)}", hint = "−${cashMoney(session.cashOut, currency)}") }
        }
    }
    Box(modifier) { EvenGrid(columns = columns, spacing = 10.dp, cells = cells) }
}

/** Everything a shift holds, as it closed or as it stands (web `ShiftDetail`; Store history, Finance › Cortes). */
@Composable
fun CashShiftDetail(session: CashSession, modifier: Modifier = Modifier) {
    if (LocalSizeClass.current == SizeClass.regular) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { ShiftOpened(session); ShiftCash(session) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { ShiftMethods(session); ShiftTerminal(session) }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ShiftOpened(session)
            ShiftCash(session)
            ShiftMethods(session)
            ShiftTerminal(session)
        }
    }
}

@Composable
private fun ShiftOpened(session: CashSession) {
    val currency = Workspace.shared.currency
    val zone = session.zone
    Column {
        CashShiftHeading(text = L("Opened"), icon = "lock-open")
        CashShiftLine(L("By"), session.openedByName ?: "—")
        CashShiftLine(L("At"), CashDay.`when`(session.openedAt, zone = zone))
        CashShiftLine(L("Starting cash"), cashMoney(session.openingCash, currency), hint = session.openingExpected?.let { LF("The last close left %@", cashMoney(it, currency)) })
        val difference = session.openingDifference
        if (difference != null && difference != 0) {
            CashShiftLine(label = L("Against the last close"), hint = session.openingNote) { CashDifferenceText(difference = difference, currency = currency) }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CashShiftHeading(text = if (session.isOpen) L("Still open") else L("Closed@shift"), icon = "lock")
            if (session.frozen == true) Chip(text = L("Frozen"), tone = Chip.Tone.muted, icon = "snowflake", modifier = Modifier.padding(bottom = 4.dp))
        }
        if (session.isOpen) {
            Text(
                if (session.stale == true) L("Open since an earlier day") else L("Open now"),
                style = Theme.text(14, FontWeight.Bold), color = Theme.mango, modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            CashShiftLine(L("By"), session.closedByName ?: "—")
            CashShiftLine(L("At"), CashDay.`when`(session.closedAt, zone = zone))
        }
    }
}

@Composable
private fun ShiftCash(session: CashSession) {
    val currency = Workspace.shared.currency
    val zone = session.zone
    val cashFigure = session.byMethod.firstOrNull { it.method == "cash" }
    Column {
        CashShiftHeading(text = L("Cash"))
        CashShiftLine(L("Starting cash"), cashMoney(session.openingCash, currency))
        CashShiftLine(L("Cash taken"), "+${cashMoney(cashFigure?.payments ?: 0, currency)}")
        val refunds = cashFigure?.refunds
        if (refunds != null && refunds > 0) CashShiftLine(L("Cash given back"), "−${cashMoney(refunds, currency)}")
        session.cashMovements.forEach { movement ->
            key(movement.id) {
                val isIn = movement.kind == "cash_in"
                CashShiftLine(
                    label = if (isIn) L("Cash in") else L("Cash out"),
                    hint = listOf(movement.reason, movement.actorName, CashDay.`when`(movement.createdAt, withDay = false, zone = zone)).mapNotNull { it.nonEmpty }.joinToString(" · "),
                ) {
                    CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Glyph(if (isIn) "arrow-down-to-line" else "arrow-up-from-line", 14.dp)
                            Text("${if (isIn) "+" else "−"}${cashMoney(movement.amount, currency)}", color = Theme.inkStrong)
                        }
                    }
                }
            }
        }
        StitchedDivider(modifier = Modifier.padding(vertical = 4.dp))
        CashShiftLine(L("Expected in the drawer"), cashMoney(session.expectedCash, currency), strong = true)
        if (!session.isOpen) {
            CashShiftLine(L("Counted"), session.countedCash?.let { cashMoney(it, currency) } ?: "—", strong = true)
            CashShiftLine(label = L("Difference")) { CashDifferenceText(difference = session.difference ?: 0, currency = currency) }
            val left = session.cashLeft
            val counted = session.countedCash
            if (left != null && counted != null && left != counted) {
                CashShiftLine(L("Left for the next shift"), cashMoney(left, currency), hint = LF("%@ to the safe or the bank", cashMoney(counted - left, currency)))
            }
        }
        val pieces = session.denominations
        if (!pieces.isNullOrEmpty()) {
            Text(
                pieces.entries.sortedByDescending { it.key.toIntOrNull() ?: 0 }.joinToString(" · ") { "${CashDenominations.face(it.key.toIntOrNull() ?: 0)} × ${it.value}" },
                style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ShiftMethods(session: CashSession) {
    val currency = Workspace.shared.currency
    Column {
        CashShiftHeading(text = L("By method"))
        if (session.byMethod.isEmpty()) {
            Text(L("Nothing taken in this shift."), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(vertical = 4.dp))
        }
        session.byMethod.forEach { entry ->
            CashShiftLine(
                CashText.method(entry.method), cashMoney(entry.payments - entry.refunds, currency),
                hint = if (entry.refunds > 0) LF("%@ refunded", cashMoney(entry.refunds, currency)) else null,
            )
        }
        CashShiftLine(L("Sales in the shift"), "${session.saleCount} · ${cashMoney(session.salesTotal, currency)}")
        val returns = session.returnCount
        if (returns != null && returns > 0) CashShiftLine(L("Returns"), "$returns")
    }
}

@Composable
private fun ShiftTerminal(session: CashSession) {
    val currency = Workspace.shared.currency
    Column {
        CashShiftHeading(text = L("Card terminal"))
        CashShiftLine(L("Card recorded"), cashMoney(session.cardTotal, currency))
        CashShiftLine(L("Terminal report"), session.cardTerminalTotal?.let { cashMoney(it, currency) } ?: L("Not entered"))
        val gap = session.cardDifference
        if (gap != null) {
            CashShiftLine(
                L("Difference"), if (gap == 0) L("Matches") else "${if (gap > 0) "+" else "−"}${cashMoney(abs(gap), currency)}",
                tone = if (gap == 0) Theme.blue else Theme.mango,
            )
        }
        val people = session.byPerson
        if (!people.isNullOrEmpty()) {
            CashShiftHeading(text = L("Who charged"), modifier = Modifier.padding(top = 12.dp))
            people.forEach { person ->
                CashShiftLine(person.name ?: "—", cashMoney(person.payments - person.refunds, currency), hint = LP(person.count ?: 0, "%d payment", "%d payments"))
            }
        }
        session.closingNote.nonEmpty?.let { note ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${L("Note")}: ") }
                    append(note)
                },
                style = Theme.text(14), color = Theme.inkStrong,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// MARK: - Opening

/**
 * Open a shift (web `OpenShiftSheet`): the starting cash comes filled in with what the
 * last close left; a different figure is kept but flagged, with room to say why.
 */
@Composable
fun CashOpenShiftSheet(register: CashRegister, modifier: Modifier = Modifier, onOpened: () -> Unit = {}) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    // `.onAppear`: what the last close left, or the business's default.
    val amount = remember { mutableStateOf(register.lastCashLeft ?: register.defaultOpeningCash) }
    val note = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val suggested = register.lastCashLeft

    fun save() {
        if (!form.check(listOf("amount" to (if (amount.value == null) L("Count the cash in the drawer to start with.") else null)))) return
        working = true
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-store/registers/${register.id}/open", mapOf("openingCash" to (amount.value ?: 0), "note" to note.value.nonEmpty))
                Toast.show(LF("%@ is open.", register.name))
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                onOpened()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                form.apply(error, mapOf("openingCash" to "amount"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Open %@", register.name), subtitle = L("Count the cash in the drawer to start with."), systemImage = "lock-open", accent = ModalAccent.aqua,
        isDirty = note.value.isNotEmpty(), form = form, primaryTitle = LF("Open with %@", cashMoney(amount.value ?: 0)), isWorking = working, primaryIcon = "lock-open",
        onPrimary = { save() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        MoneyField(
            label = L("Starting cash"), cents = amount, key = "amount", form = form,
            hint = suggested?.let { LF("The last close left %@ in the drawer. Count it and change it if it isn’t there.", cashMoney(it)) } ?: L("Count what is in the drawer now; the close is checked against it."),
            caps = true,
        )
        val typed = amount.value
        if (suggested != null && typed != null && typed != suggested) {
            // The web's mango box: the difference, then why (optional).
            NoticeCard(tone = NoticeCard.Tone.warning, icon = "triangle-alert", content = {
                Text(
                    if (typed < suggested) LF("%@ less than the last close left.", cashMoney(suggested - typed)) else LF("%@ more than the last close left.", cashMoney(typed - suggested)),
                    style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(Theme.surface, CapsuleShape)
                        .border(1.dp, Theme.borderSoft, CapsuleShape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PlainTextField(note, L("Why (optional): taken to the bank, change brought in…"), Modifier.fillMaxWidth(), style = Theme.text(14).copy(color = Theme.inkStrong))
                }
            })
        }
    }
}

// MARK: - Cash in and out

/** Cash in or out with its why (web `CashSheet`): never more out than the drawer should hold. */
@Composable
fun CashMoveSheet(session: CashSession, kind: String, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val amount = remember { mutableStateOf<Int?>(null) }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val out = kind == "cash_out"

    fun drawerHolds(cents: Int): String = LF("The drawer should hold %@ in cash: you cannot take out more.", cashMoney(cents))

    fun save() {
        val value = amount.value ?: 0
        val tooMuch = out && value > max(0, session.expectedCash)
        val passes = form.check(listOf(
            "amount" to (if (value <= 0) L("Enter an amount") else if (tooMuch) drawerHolds(max(0, session.expectedCash)) else null),
            "reason" to (if (reason.value.nonEmpty == null) L("Say why the cash goes in or out") else null),
        ))
        if (!passes) return
        working = true
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-store/register-sessions/${session.id}/cash", mapOf("kind" to kind, "amount" to value, "reason" to reason.value.nonEmpty))
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (cashErrorCode(error) == "not_enough_cash") {
                    form.check(listOf("amount" to errorText(error)))
                } else {
                    form.apply(error, mapOf("amount" to "amount", "reason" to "reason"))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (out) L("Take cash out") else L("Put cash in"),
        subtitle = if (out) L("A supplier paid in cash, or cash taken to the bank.") else L("Change brought to the drawer, for example."),
        systemImage = "wallet", accent = ModalAccent.aqua, isDirty = amount.value != null || reason.value.isNotEmpty(), form = form,
        primaryTitle = LF("Record %@", cashMoney(amount.value ?: 0)), isWorking = working, primaryIcon = if (out) "arrow-up-from-line" else "arrow-down-to-line",
        onPrimary = { save() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        MoneyField(label = L("Amount"), cents = amount, key = "amount", form = form, caps = true)
        PMTextField(L("Why (required)"), text = reason, key = "reason", form = form, prompt = if (out) L("Paid the water delivery") else L("Change from the bank"), caps = true)
        // What the drawer should hold once this is recorded.
        val value = amount.value
        if (value != null && value > 0 && !(out && value > max(0, session.expectedCash))) {
            Row(
                Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(L("Cash expected after this"), style = Theme.text(14), color = Theme.inkSoft)
                Spacer(Modifier.weight(1f))
                Text(cashMoney(session.expectedCash + (if (out) -value else value)), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
            }
        }
    }
}

// MARK: - Closing

/** The helper's total and what the server takes (value → how many). */
private data class CashCountHelper(val total: Int, val byValue: Map<String, Int>, val pieces: Int)

/**
 * Closing a shift in two steps (web `CloseShiftSheet`): count the drawer (typed,
 * or added up with the bills-and-coins helper) and the card terminal; then review
 * it against what the drawer should hold, keep what stays, and say why when the
 * count is off.
 */
@Composable
fun CashCloseShiftSheet(session: CashSession, modifier: Modifier = Modifier, onClosed: () -> Unit = {}) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var reviewing by remember { mutableStateOf(false) }
    val typed = remember { mutableStateOf<Int?>(null) }
    var counts by remember { mutableStateOf(mapOf<String, String>()) }
    var helperOpen by remember { mutableStateOf(false) }
    val terminal = remember { mutableStateOf<Int?>(null) }
    var keepAll by remember { mutableStateOf(true) }
    val left = remember { mutableStateOf<Int?>(null) }
    val note = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val currency = Workspace.shared.currency
    val pieces = CashDenominations.pieces(currency)

    /** The helper's total and what the server takes (value → how many), or null when nothing is counted. */
    val helper: CashCountHelper? = pieces?.let { all ->
        var total = 0
        var count = 0
        val byValue = linkedMapOf<String, Int>()
        for (piece in all) {
            val n = counts[piece.id].nonEmpty?.toIntOrNull() ?: continue
            if (n <= 0) continue
            byValue[piece.value.toString()] = (byValue[piece.value.toString()] ?: 0) + n
            total += piece.value * n
            count += n
        }
        if (count > 0) CashCountHelper(total, byValue, count) else null
    }
    val counted: Int? = helper?.total ?: typed.value
    val difference: Int? = counted?.let { it - session.expectedCash }
    val dirty = helper != null || typed.value != null || terminal.value != null || note.value.isNotEmpty() || !keepAll

    fun review() {
        if (!form.check(listOf("counted" to (if (counted == null) L("Count the drawer first.") else null)))) return
        reviewing = true
    }

    fun submit() {
        if (counted == null) {
            reviewing = false
            return
        }
        val passes = form.check(listOf(
            "left" to (if (!keepAll && left.value == null) L("Enter an amount") else null),
            "note" to (if ((difference ?: 0) != 0 && note.value.nonEmpty == null) CashReasonKind.cashDifference.required else null),
        ))
        if (!passes) return
        working = true
        scope.launch {
            try {
                val body = mapOf<String, Any?>(
                    "countedCash" to counted,
                    "denominations" to helper?.byValue,
                    "cardTerminalTotal" to terminal.value,
                    "cashLeft" to (if (keepAll) null else left.value),
                    "note" to note.value.nonEmpty,
                )
                API.post<EmptyResponse>("/provider-store/register-sessions/${session.id}/close", body)
                Toast.show(LF("%@ is closed.", session.registerName))
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                onClosed()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (cashAsksReason(error)) {
                    form.check(listOf("note" to errorText(error)))
                } else {
                    form.apply(error, mapOf("note" to "note", "countedCash" to "counted"))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Close %@", session.registerName),
        subtitle = if (reviewing) L("Check the count against what the drawer should hold, then close.")
        else LF("Opened %@ by %@. Count the cash in the drawer.", CashDay.`when`(session.openedAt, zone = session.zone), session.openedByName ?: "—"),
        systemImage = "lock", accent = ModalAccent.aqua, isDirty = dirty, form = form,
        primaryTitle = if (reviewing) LF("Close %@", session.registerName) else L("Continue"),
        isWorking = working,
        steps = ModalSteps(steps = listOf(L("Count"), L("Review")), icons = listOf("calculator", "clipboard-check"), current = if (reviewing) 1 else 0),
        onBack = if (reviewing) ({ reviewing = false }) else null,
        primaryIcon = if (reviewing) "lock" else null,
        onPrimary = { if (reviewing) submit() else review() },
        modifier = modifier,
    ) {
        if (reviewing) {
            if (counted != null && difference != null) {
                val threshold = session.alertThreshold ?: 0
                val red = if (threshold > 0) (difference < 0 && abs(difference) > threshold) else difference < 0
                val tone = if (difference == 0) ProviderState.done else if (red) ProviderState.problem else ProviderState.waiting
                val box = RoundedCornerShape(Theme.boxRadius)
                // Expected against counted (web: the review's first box), in the difference's colour.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(tone.color, 10.0), box)
                        .border(1.5.dp, Theme.mix(tone.color, 60.0, Theme.borderSoft), box),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        ReviewFigure(L("Expected in the drawer"), cashMoney(session.expectedCash), Modifier.weight(1f))
                        ReviewFigure(L("Counted"), cashMoney(counted), Modifier.weight(1f))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.mix(tone.color, 40.0, Theme.borderSoft)))
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(L("Difference").uppercase(), style = microCaps, color = Theme.inkSoft)
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(8.dp).background(tone.color, CircleShape))
                        Text(
                            if (difference == 0) L("Exact") else if (difference < 0) LF("Short %@", cashMoney(-difference, currency)) else LF("Over %@", cashMoney(difference, currency)),
                            style = Theme.heading(20).monospacedDigit(), color = if (tone == ProviderState.problem) Theme.destructive else Theme.inkStrong,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel(L("This shift"), caps = true)
                    CashShiftFigures(session = session, showExpected = false, showCash = true)
                    Text(cardLine(session, terminal.value), style = Theme.text(12), color = Theme.inkSoft)
                }
                // The web's checkbox row.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .plainButton { keepAll = !keepAll }
                        .semantics { selected = keepAll },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Tick(on = keepAll, modifier = Modifier.padding(top = 1.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(L("Leave all of it in the drawer for the next shift"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        Text(L("The next shift starts with it. Untick if some goes to the safe or the bank."), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                AnimatedVisibility(visible = !keepAll, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(200)) + fadeOut(tween(200))) {
                    MoneyField(label = L("Left in the drawer"), cents = left, key = "left", form = form, caps = true)
                }
                if (difference != 0) {
                    CashReasonField(kind = CashReasonKind.cashDifference, text = note, key = "note", form = form, hint = LF("The count is off by %@: the owner sees why.", cashMoney(abs(difference))))
                } else {
                    PMTextField(L("Note"), text = note, key = "note", form = form, prompt = L("Anything that explains a difference"), multiline = true, caps = true)
                }
                if (threshold > 0 && abs(difference) > threshold) {
                    Text(LF("The difference is over %@: say what happened. The owner is told.", cashMoney(threshold)), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
                }
            }
        } else {
            if (helper != null) {
                FormField(label = L("Cash counted"), key = "counted", hint = L("From the bills and coins below."), form = form, caps = true) {
                    Text(cashMoney(helper.total), style = Theme.text(20, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong, modifier = Modifier.fillMaxWidth().fieldChrome())
                }
            } else {
                MoneyField(label = L("Cash counted"), cents = typed, key = "counted", form = form, hint = L("What the drawer should hold shows in the next step, after the count."), caps = true)
            }
            if (pieces != null) {
                CountHelper(
                    pieces = pieces, helper = helper, open = helperOpen, counts = counts,
                    toggle = { helperOpen = !helperOpen },
                    setCount = { id, text -> counts = counts + (id to text.filter(Char::isDigit)) },
                    clear = { counts = emptyMap() },
                )
            }
            // The label says "(optional)" itself, as the web's does.
            MoneyField(label = L("Card terminal total (optional)"), cents = terminal, hint = L("What the terminal’s own report says for this shift. It is noted with the close, never blocks it."), caps = true)
        }
    }
}

/** The bills-and-coins helper: folded under its row, a field for each piece, the total. */
@Composable
private fun CountHelper(
    pieces: List<CashDenominations.Piece>,
    helper: CashCountHelper?,
    open: Boolean,
    counts: Map<String, String>,
    toggle: () -> Unit,
    setCount: (String, String) -> Unit,
    clear: () -> Unit,
) {
    val box = RoundedCornerShape(Theme.boxRadius)
    val chip = RoundedCornerShape(Theme.chipRadius)
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "helper chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, box)
            .border(1.dp, Theme.borderSoft, box),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .plainButton(onClick = toggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph("calculator", 16.dp, tint = Theme.blue)
            Text(L("Count bills and coins"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Spacer(Modifier.weight(1f))
            if (helper != null) Text(LP(helper.pieces, "%d piece", "%d pieces"), style = Theme.text(12, FontWeight.Bold).monospacedDigit(), color = Theme.inkSoft)
            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(visible = open, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(200)) + fadeOut(tween(200))) {
            Column {
                RowDivider()
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(true, false).forEach { bills ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text((if (bills) L("Bills") else L("Coins")).uppercase(), style = microCaps, color = Theme.inkSoft)
                            AdaptiveGrid(minimum = 140.dp, spacing = 8.dp, cells = pieces.filter { it.isBill == bills }.map { piece ->
                                @Composable {
                                    val face = CashDenominations.face(piece.value)
                                    val label = LF("How many of %@", face)
                                    Row(
                                        Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, chip).padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(face, style = Theme.text(14, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkStrong, maxLines = 1, modifier = Modifier.width(56.dp))
                                        Text("×", style = Theme.text(12), color = Theme.inkSoft)
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .height(32.dp)
                                                .background(Theme.inputFill, chip)
                                                .border(1.dp, Theme.borderSoft, chip)
                                                .padding(horizontal = 8.dp)
                                                .semantics { contentDescription = label },
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            PlainTextField(
                                                stateOf({ counts[piece.id] ?: "" }, { setCount(piece.id, it) }), "0", Modifier.fillMaxWidth(),
                                                keyboard = KeyboardType.Number,
                                                style = Theme.text(14).monospacedDigit().copy(textAlign = TextAlign.End, color = Theme.inkStrong),
                                            )
                                        }
                                    }
                                }
                            })
                        }
                    }
                    RowDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(L("Total counted"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
                        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                        Text(cashMoney(helper?.total ?: 0), style = Theme.heading(18).monospacedDigit(), color = Theme.inkStrong)
                    }
                    if (helper != null) {
                        Text(L("Clear and type the total instead"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue, modifier = Modifier.plainButton(onClick = clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = microCaps, color = Theme.inkSoft, maxLines = 2)
        Text(value, style = Theme.heading(18).monospacedDigit(), color = Theme.inkStrong)
    }
}

private fun cardLine(session: CashSession, terminal: Int?): String {
    val parts = mutableListOf(LF("Card recorded in this shift: %@", cashMoney(session.cardTotal)))
    if (terminal != null) {
        parts.add(LF("the terminal says %@", cashMoney(terminal)))
        val gap = terminal - session.cardTotal
        parts.add(if (gap == 0) L("matches") else if (gap < 0) LF("the terminal shows %@ less", cashMoney(-gap)) else LF("the terminal shows %@ more", cashMoney(gap)))
    }
    return parts.joinToString(" · ")
}

// MARK: - The Register tab

/**
 * The cash drawer at this counter (web `StoreRegister`): open a shift (anyone who sells or
 * takes payments), cash in and out with a reason (register managers), close it against a
 * count; and the shifts before.
 */
@Composable
fun CashRegisterTab(device: CashDeviceRegister, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val newName = remember { mutableStateOf("") }
    var addingWorking by remember { mutableStateOf(false) }

    val canRun = cashCanRunShift(workspace)
    val canManage = workspace.can(Permission.registerManage)

    fun add() {
        val name = newName.value.nonEmpty ?: return
        addingWorking = true
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-store/registers", mapOf("name" to name, "locationId" to workspace.locationId))
                Toast.show(LF("%@ is ready. Choose it at the top on the device that uses it.", name))
                newName.value = ""
                adding = false
                device.load()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.error(error, L("Could not add the register."))
            } finally {
                addingWorking = false
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val register = device.register
        if (register != null) {
            RegisterStatusCard(register, device.session, canRun = canRun, canManage = canManage, move = { moving = it }, close = { closing = true }, open = { opening = true })
        } else if (device.loaded) {
            // A place with no counter yet (a new location): say so instead of an empty tab.
            EmptyState(
                systemImage = "wallet", title = L("No register here yet"),
                message = if (canManage) L("Add one to open a shift and take cash at this location.") else L("Someone who runs the register can add one for this location."),
                accent = Theme.mango, panel = true,
            )
        } else {
            LoadingState()
        }
        if (canManage) {
            if (adding) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = L("Register name")
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(Theme.inputFill, CapsuleShape)
                            .border(1.dp, Theme.borderSoft, CapsuleShape)
                            .padding(horizontal = 16.dp)
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        PlainTextField(newName, L("Name, e.g. Caja estética"), Modifier.fillMaxWidth(), style = Theme.bodyFont.copy(color = Theme.inkStrong))
                    }
                    PMButton(L("Add"), isWorking = addingWorking, enabled = newName.value.nonEmpty != null) { add() }
                    PMButton(L("Cancel"), variant = ButtonVariant.outline) {
                        adding = false
                        newName.value = ""
                    }
                }
            } else {
                Row(
                    Modifier.heightIn(min = 44.dp).plainButton { adding = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Glyph("plus", 16.dp, tint = Theme.blue)
                    Text(L("Add another register"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                }
            }
        }
        if (workspace.moneyLevel >= MoneyLevel.frontDesk) {
            CashShiftHistory(anyDay = workspace.moneyLevel >= MoneyLevel.finances)
        }
    }

    if (opening) {
        AppSheet(onDismissRequest = { opening = false }) { device.register?.let { CashOpenShiftSheet(register = it) } }
    }
    if (closing) {
        AppSheet(onDismissRequest = { closing = false }) { device.session?.let { CashCloseShiftSheet(session = it) } }
    }
    moving?.let { kind ->
        AppSheet(onDismissRequest = { moving = null }) { device.session?.let { CashMoveSheet(session = it, kind = kind) } }
    }
}

@Composable
private fun RegisterStatusCard(
    register: CashRegister,
    session: CashSession?,
    canRun: Boolean,
    canManage: Boolean,
    move: (String) -> Unit,
    close: () -> Unit,
    open: () -> Unit,
) {
    // Open is green (the stable token); closed is a plain card.
    Card(padding = 16.dp, tint = if (session != null) ProviderState.done.color else null, spacing = 16.dp) {
        ViewThatFits(
            Modifier.fillMaxWidth(),
            first = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusTitle(register, session)
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    StatusActions(session, canRun, canManage, move, close, open)
                }
            },
            second = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusTitle(register, session)
                    StatusActions(session, canRun, canManage, move, close, open)
                }
            },
        )
        if (session != null && session.stale == true) {
            Row(
                Modifier.fillMaxWidth().background(Theme.wash(Theme.mango, 18.0), RoundedCornerShape(Theme.boxRadius)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph("triangle-alert", 16.dp, tint = Theme.mango, modifier = Modifier.padding(top = 2.dp))
                Text(
                    LF("This shift is from %@ and is still open — count it and close it before taking today’s cash.", CashDay.`when`(session.openedAt, zone = session.zone)),
                    style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                )
            }
        }
        if (session != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CashShiftFigures(session = session)
                if (canManage && session.cashMovements.isNotEmpty()) Movements(session)
            }
        } else {
            Text(
                (if (canRun) L("Open the register with the cash in the drawer to take cash. Card and transfer work either way.") else L("The register is closed. Someone who sells or takes payments can open it.")) +
                    (register.lastCashLeft?.let { " " + LF("The last close left %@.", cashMoney(it)) } ?: ""),
                style = Theme.text(14), color = Theme.inkSoft,
            )
        }
    }
}

@Composable
private fun StatusTitle(register: CashRegister, session: CashSession?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon = if (session != null) "lock-open" else "lock", accent = if (session != null) ProviderState.done.color else Theme.aqua, size = 44.dp, iconSize = 20.dp, ink = IconBadge.Ink.strong)
        Column {
            Text(register.name.uppercase(), style = Theme.text(12, FontWeight.Bold).tracking(1.7), color = Theme.inkSoft, maxLines = 1)
            Text(if (session != null) L("Open@register") else L("Closed@register"), style = Theme.heading(24), color = Theme.inkStrong)
            if (session != null) {
                Text(LF("Since %@ · %@", CashDay.`when`(session.openedAt, zone = session.zone), session.openedByName ?: ""), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }
}

@Composable
private fun StatusActions(session: CashSession?, canRun: Boolean, canManage: Boolean, move: (String) -> Unit, close: () -> Unit, open: () -> Unit) {
    if (session != null) {
        FlowLayout(spacing = 8.dp) {
            if (canManage) {
                PMButton(L("Cash in"), systemImage = "arrow-down-to-line", variant = ButtonVariant.outline) { move("cash_in") }
                PMButton(L("Cash out"), systemImage = "arrow-up-from-line", variant = ButtonVariant.outline) { move("cash_out") }
            }
            if (canRun) PMButton(L("Close register"), systemImage = "lock", action = close)
        }
    } else if (canRun) {
        PMButton(L("Open register"), systemImage = "lock-open", action = open)
    }
}

/** Cash put in and taken out during the open shift, newest first (web `CashMovements`). */
@Composable
private fun Movements(session: CashSession) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(Modifier.fillMaxWidth().background(Theme.surface, box).border(1.dp, Theme.borderSoft, box)) {
        Text(L("Cash in / out").uppercase(), style = microCaps, color = Theme.inkSoft, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp))
        session.cashMovements.sortedByDescending { it.createdAt }.forEachIndexed { index, movement ->
            key(movement.id) {
                val out = movement.kind == "cash_out"
                if (index > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).background(Theme.wash(if (out) Theme.mango else ProviderState.done.color, 24.0), CircleShape), contentAlignment = Alignment.Center) {
                        Glyph(if (out) "arrow-up-from-line" else "arrow-down-to-line", 14.dp, tint = Theme.inkStrong)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(movement.reason.nonEmpty ?: (if (out) L("Cash out") else L("Cash in")), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1)
                        Text(
                            listOf(CashDay.`when`(movement.createdAt, withDay = false, zone = session.zone), movement.actorName).mapNotNull { it.nonEmpty }.joinToString(" · "),
                            style = Theme.text(12), color = Theme.inkSoft, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${if (out) "−" else "+"}${cashMoney(movement.amount)}", style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
                }
            }
        }
    }
}

@Serializable
private data class CashSessionPage(
    val items: List<CashSession>,
    val total: Int,
    val hasMore: Boolean? = null,
)

/** Past shifts, newest first, 20 at a time as the list scrolls; a day narrows them to that day (web `ShiftHistory`). */
@Composable
fun CashShiftHistory(anyDay: Boolean, modifier: Modifier = Modifier) {
    var day by remember { mutableStateOf<Instant?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val pager = remember { Pager<CashSession> { _, _ -> Page(items = emptyList(), total = 0) } }
    val zone = Workspace.shared.timeZone
    val firstDay: Instant? = if (anyDay) null else ZonedDateTime.now(zone).minusDays((MoneyLevel.frontDeskDays - 1).toLong()).toInstant()

    LaunchedEffect(day) {
        val period = day?.let { DateText.dayKey(it) }
        pager.fetch = { offset, limit ->
            val page: CashSessionPage = API.get(
                "/provider-store/register-sessions",
                mapOf("limit" to "$limit", "offset" to "$offset", "period" to period, "locationId" to Workspace.shared.locationId),
            )
            Page(items = page.items, total = page.total, hasMore = page.hasMore)
        }
        pager.reload()
    }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { pager.reload() } } }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val heading: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph("history", 20.dp, tint = Theme.blue)
                Text(L("Shifts"), style = Theme.heading(20), color = Theme.inkStrong)
            }
        }
        val dayControls: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chosen = day
                PillDatePicker(
                    text = chosen?.let { CashDay.short(DateText.dayKey(it)) } ?: (if (anyDay) L("Any day") else L("Last 7 days")),
                    date = stateOf({ day ?: Instant.now() }, { picked ->
                        val lower = firstDay ?: Instant.MIN
                        day = minOf(maxOf(picked, lower), Instant.now())
                    }),
                )
                if (chosen != null) {
                    PMButton(if (anyDay) L("Every day") else L("Last 7 days"), systemImage = "x", variant = ButtonVariant.outline, size = ButtonSize.compact) { day = null }
                }
            }
        }
        ViewThatFits(
            Modifier.fillMaxWidth(),
            first = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    heading()
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    dayControls()
                }
            },
            second = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    heading()
                    dayControls()
                }
            },
        )
        val error = pager.error
        ListCard(modifier = Modifier.alpha(if (pager.isLoading && pager.loadedOnce) 0.7f else 1f)) {
            when {
                !pager.loadedOnce && pager.isLoading -> LoadingState()
                error != null && pager.items.isEmpty() -> ErrorState(message = error) { pager.reload() }
                pager.items.isEmpty() -> EmptyState(systemImage = "wallet", title = if (day != null) L("No shifts that day.") else L("No shifts yet."), accent = Theme.mango)
                else -> Column(Modifier.fillMaxWidth()) {
                    pager.items.forEachIndexed { index, session ->
                        key(session.id) {
                            if (index > 0) RowDivider()
                            ShiftRow(
                                session, open = expanded == session.id,
                                toggle = { expanded = if (expanded == session.id) null else session.id },
                                modifier = Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(session) },
                            )
                        }
                    }
                    PagerFooter(pager)
                }
            }
        }
        val total = pager.total
        if (total != null && total > 0) {
            Text(LP(total, "%d shift", "%d shifts"), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ShiftRow(session: CashSession, open: Boolean, toggle: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "shift chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .plainButton(onClick = toggle)
                .semantics { selected = open }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${session.registerName} · ${CashDay.`when`(session.openedAt, zone = session.zone)}", style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
                Text(shiftSummary(session), style = Theme.text(14), color = Theme.inkSoft, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            if (session.isOpen) {
                // Open now is green; left open from an earlier day is the amber that asks for a close.
                val tone = if (session.stale == true) Theme.mango else ProviderState.done.color
                Row(
                    Modifier.height(24.dp).background(Theme.wash(tone, 24.0), CapsuleShape).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).background(tone, CircleShape))
                    Text(L("Open@register"), style = Theme.text(12, FontWeight.ExtraBold), color = Theme.inkStrong)
                }
            } else {
                CashDifferenceText(difference = session.difference ?: 0, currency = Workspace.shared.currency)
            }
            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(visible = open, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(200)) + fadeOut(tween(200))) {
            Column(Modifier.fillMaxWidth().background(Theme.wash(Theme.blue, 4.0))) {
                RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CashShiftDetail(session = session)
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        DocumentButton(title = L("Download PDF"), systemImage = "file-down", path = "/provider-store/register-sessions/${session.id}/pdf", fileName = "${session.registerName} ${session.openedDay ?: ""}")
                    }
                }
            }
        }
    }
}

private fun shiftSummary(session: CashSession): String {
    if (session.isOpen) return if (session.stale == true) L("Still open since an earlier day") else L("Open now")
    val sales = if (session.saleCount == 1) LF("1 sale · closed by %@", session.closedByName ?: "—") else LF("%d sales · closed by %@", session.saleCount, session.closedByName ?: "—")
    return "$sales · " + LF("Counted %@ of %@", cashMoney(session.countedCash ?: 0), cashMoney(session.expectedCash))
}
