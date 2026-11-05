package com.pawmiaw.business.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max

//
//  StockAdjustSheets.kt (iOS StockAdjustSheets.swift)
//
//  Inventory's other sheets (web inventory/adjust-stock-sheet.tsx,
//  stock-detail-sheet.tsx, stock-settings-sheet.tsx):
//  - a count ("this is what is really on the shelf") or a write-off ("this
//    left the shelf, and why"), for the whole product or one of its lots. The
//    figure the sheet showed goes with it: when someone sold or counted in the
//    meantime the server answers 409 `stock_changed`, the sheet shows the new
//    figure and the person records again. One request id per press.
//  - one product's lots and every movement of its stock;
//  - its stock settings: the low-stock alert and point, the usual order,
//    where it is kept, its barcode, and whether it keeps selling at zero.
//

/** SwiftUI's negative top padding (`.padding(.top, -8)`): the view and what follows move up. */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout(placeable.width, max(0, placeable.height - shift)) { placeable.place(0, -shift) }
}

// MARK: - Count and write off

enum class StockAdjustKind { count, waste }

private enum class WasteReason(val rawValue: String) {
    expired("expired"), damaged("damaged"), lost("lost"), used("used"), other("other");

    val label: String
        get() = when (this) {
            expired -> L("Expired@lot")
            damaged -> L("Damaged")
            lost -> L("Lost or missing")
            used -> L("Used in the clinic")
            other -> L("Other")
        }
}

private const val wholeProduct = "whole"

/** What the server said the shelf (and the chosen lot) holds now, after a `stock_changed`. */
private data class StockFresh(val whole: Int?, val lotId: String?, val lot: Int?)

@Stable
private class AdjustModel(val row: StockRow, val kind: StockAdjustKind, val lotId: String?) {
    val form = FormState()
    var chosenLot by mutableStateOf(wholeProduct)
    var quantity by mutableStateOf("")
    var reason by mutableStateOf<WasteReason?>(null)
    var note by mutableStateOf("")
    var witness by mutableStateOf("")
    var saving by mutableStateOf(false)
    /** (message, connection). */
    var saveError by mutableStateOf<Pair<String, Boolean>?>(null)
    var fresh by mutableStateOf<StockFresh?>(null)
    /** (key, id). */
    var lastPress: Pair<String, String>? = null

    var onSaved: () -> Unit = {}
    var dismiss: () -> Unit = {}

    val today: String get() = StockRules.today

    init {
        // Swift `start()` (onAppear, once).
        val firstExpired = if (kind == StockAdjustKind.waste) row.lots.firstOrNull { it.quantityOnHand > 0 && StockRules.expiry(it.expiresOn, today = today) == StockExpiry.expired } else null
        val wanted = lotId ?: firstExpired?.id
        val startLot = row.lots.firstOrNull { it.id == wanted }
        val expired = startLot?.let { StockRules.expiry(it.expiresOn, today = today) == StockExpiry.expired } ?: false
        chosenLot = startLot?.id ?: wholeProduct
        // An expired lot is written off whole, unless someone says otherwise.
        if (kind == StockAdjustKind.waste && startLot != null && expired) quantity = startLot.quantityOnHand.toString()
        reason = if (kind == StockAdjustKind.waste && expired) WasteReason.expired else null
    }

    val shown: StockRow
        get() {
            val whole = fresh?.whole ?: return row
            return row.copy(quantityOnHand = whole)
        }
    val lots: List<StockLot> get() = shown.lots.filter { it.quantityOnHand != 0 }
    val lot: StockLot? get() = lots.firstOrNull { it.id == chosenLot }
    val current: Int
        get() {
            val lot = lot ?: return shown.quantityOnHand
            val fresh = fresh
            if (fresh != null && fresh.lotId == lot.id && fresh.lot != null) return fresh.lot
            return lot.quantityOnHand
        }
    val available: Int get() = max(0, current)
    val parsed: Pair<Int?, StockQuantityProblem?> get() = StockRules.parseQuantity(quantity, allowZero = kind == StockAdjustKind.count)
    val delta: Int? get() = parsed.first?.let { if (kind == StockAdjustKind.count) it - current else -it }
    val largeDifference: Boolean get() = kind == StockAdjustKind.count && parsed.first?.let { StockRules.isLargeCountDifference(current = current, counted = it) } == true
    val controlled: Boolean get() = shown.controlSchedule.nonEmpty != null
    val noteRequired: Boolean get() = if (kind == StockAdjustKind.waste) (reason == WasteReason.other || controlled) else largeDifference

    val afterValue: Int?
        get() {
            val delta = delta ?: return null
            if (quantityProblem(force = false) != null) return null
            return shown.quantityOnHand + delta
        }

    val quantityHint: String?
        get() {
            if (quantityProblem(force = false) != null) return null
            val delta = delta
            if (kind == StockAdjustKind.count && delta != null) {
                if (current < 0) {
                    return if (current == -1) L("The system is 1 short: this count sets it right.") else LF("The system is %@ short: this count sets it right.", StockText.count(-current))
                }
                if (delta == 0) return LF("The system has %@: it matches.", StockText.count(current))
                return LF("The system has %@; difference %@.", StockText.count(current), StockText.signed(delta))
            }
            val value = parsed.first
            val cost = lot?.unitCostAmount ?: shown.unitCostAmount
            if (kind == StockAdjustKind.waste && value != null && cost != null && cost > 0 && shown.unitCostAmount != null) {
                return LF("%@ at cost leaves the stock.", Money.format(value * cost))
            }
            if (lot != null) return LF("This lot has %@.", StockText.count(current))
            if (current < 0) return if (current == -1) L("The system is 1 short.") else LF("The system is %@ short.", StockText.count(-current))
            return LF("The system has %@.", StockText.count(current))
        }

    fun quantityProblem(force: Boolean): String? {
        if (kind == StockAdjustKind.waste && available == 0) return null
        val (value, problem) = parsed
        if (problem != null) {
            if (!(force || (quantity.nonEmpty != null && problem != StockQuantityProblem.required))) return null
            return when (problem) {
                StockQuantityProblem.required -> if (kind == StockAdjustKind.count) L("Enter what you counted. Zero if there is none.") else L("Enter how many leave the stock.")
                StockQuantityProblem.zero -> L("Write off at least one.")
                StockQuantityProblem.whole -> L("Stock is counted in whole units: no decimals.")
                StockQuantityProblem.negative -> if (kind == StockAdjustKind.count) L("A count cannot be below zero.") else L("Enter how many leave the stock.")
                StockQuantityProblem.tooLarge -> L("That number is too large to be right. Check it.")
                StockQuantityProblem.invalid -> L("Enter the quantity as a number.")
            }
        }
        if (kind == StockAdjustKind.waste && value != null && value > available) {
            if (lot != null) return if (available == 1) L("Only 1 left in this lot.") else LF("Only %@ left in this lot.", StockText.count(available))
            return LF("Only %@ on hand.", StockText.count(available))
        }
        return null
    }

    val noteProblem: String?
        get() {
            if (!noteRequired || note.nonEmpty != null) return null
            if (kind == StockAdjustKind.waste) {
                return if (controlled && reason != WasteReason.other) L("A controlled medicine’s write-off needs a note: say what happened.") else L("Say what happened.")
            }
            return L("This count is far from what the system has. Say why.")
        }

    suspend fun submit() {
        if (kind == StockAdjustKind.waste && available == 0) return
        val ok = form.check(
            listOf(
                "quantity" to quantityProblem(force = true),
                "reason" to (if (kind == StockAdjustKind.waste && reason == null) L("Choose why it leaves the stock.") else null),
                "note" to noteProblem,
            ),
        )
        val value = parsed.first
        val inventoryItemId = shown.inventoryItemId
        if (!ok || value == null || inventoryItemId == null || saving) return
        saving = true
        saveError = null
        try {
            val chosenReason = reason
            val words = if (kind == StockAdjustKind.waste && chosenReason != null) {
                listOfNotNull(chosenReason.label, note.nonEmpty).joinToString(" — ")
            } else {
                note.nonEmpty ?: L("Stock count")
            }
            val witnessName = if (kind == StockAdjustKind.waste && controlled) witness.nonEmpty else null
            val current = current
            val lot = lot
            // The same press sent again keeps its id; anything changed is a new press.
            val key = listOf(if (kind == StockAdjustKind.count) "count" else "waste", lot?.id ?: "", "$value", words, witnessName ?: "", "$current").joinToString("|")
            val requestId = if (lastPress?.first == key) (lastPress?.second ?: StockAPI.newRequestId()) else StockAPI.newRequestId()
            lastPress = key to requestId
            val body = mutableMapOf<String, Any?>(
                "kind" to (if (kind == StockAdjustKind.count) "count" else "waste"),
                "quantity" to value,
                "lotId" to lot?.id,
                "reason" to words,
                "expectedOnHand" to current,
                "clientRequestId" to requestId,
            )
            if (witnessName != null) body["witnessName"] = witnessName
            try {
                val after: StockRow = StockAPI.send("POST", "/provider-stock/$inventoryItemId/adjust", body = body)
                Toast.show(
                    if (kind == StockAdjustKind.count) LF("Count recorded. %@ on hand now.", StockText.count(after.quantityOnHand))
                    else if (value == 1) LF("1 unit written off. %@ on hand now.", StockText.count(after.quantityOnHand))
                    else LF("%@ written off. %@ on hand now.", StockText.units(value), StockText.count(after.quantityOnHand)),
                )
                onSaved()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (refusal: StockRefusal) {
                if (refusal.code == "stock_changed") stockChanged(refusal, lot) else failed(refusal)
            } catch (error: Exception) {
                failed(error)
            }
        } finally {
            saving = false
        }
    }

    /** Someone sold or counted meanwhile: the new figure is shown, and the person records again from it. */
    private fun stockChanged(refusal: StockRefusal, lot: StockLot?) {
        val whole = refusal.body["quantityOnHand"].int
        val lotNow = refusal.body["lotQuantityOnHand"].int
        fresh = StockFresh(whole, lot?.id, if (lot != null) lotNow else null)
        val now = if (lot != null) (lotNow ?: whole) else whole
        val nowText = now?.let { if (it < 0) (if (it == -1) L("now 1 is short") else LF("now %@ are short", StockText.count(-it))) else LF("now %@ on hand", StockText.count(it)) }
        val message = if (nowText != null) {
            if (kind == StockAdjustKind.count) LF("The stock changed while this was open: %@. Check your count and record it again.", nowText)
            else LF("The stock changed while this was open: %@. Check how many and write it off again.", nowText)
        } else {
            L("The stock changed while this was open. Check it and record it again.")
        }
        saveError = message to false
        Toast.error(message)
    }

    private fun failed(error: Throwable) {
        val connection = stockIsConnectionError(error)
        val message = if (connection) L("We could not reach PawMiaw. Nothing was changed here: check the stock before trying again.")
        else stockErrorText(error, fallback = L("Could not record this."))
        saveError = message to connection
        Toast.error(message)
    }
}

@Composable
fun StockAdjustSheet(
    row: StockRow,
    kind: StockAdjustKind,
    lotId: String?,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
) {
    val model = remember { AdjustModel(row, kind, lotId) }
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    model.onSaved = onSaved
    model.dismiss = dismiss
    val shown = model.shown
    val count = kind == StockAdjustKind.count
    val today = model.today

    ProviderModal(
        title = if (count) LF("Count %@", shown.name) else LF("Write off %@", shown.name),
        subtitle = if (count) L("Enter what is really on the shelf. The difference is recorded.") else L("Expired, damaged or lost — it leaves stock with the reason."),
        systemImage = if (count) "clipboard-list" else "trash-2",
        accent = if (count) ModalAccent.aqua else ModalAccent.pink,
        isDirty = model.reason != null || model.quantity.isNotEmpty() || model.chosenLot != (lotId ?: wholeProduct),
        form = model.form,
        primaryTitle = if (count) L("Record count") else L("Write off"),
        primaryVariant = if (kind == StockAdjustKind.waste) ButtonVariant.destructive else ButtonVariant.primary,
        isWorking = model.saving,
        primaryIcon = if (count) "clipboard-list" else "trash-2",
        primaryDisabled = kind == StockAdjustKind.waste && model.available == 0,
        onPrimary = { scope.launch { model.submit() } },
        modifier = modifier,
    ) {
        model.saveError?.let { (message, connection) ->
            NoticeCard(tone = NoticeCard.Tone.problem, title = message, icon = if (connection) "wifi-off" else "triangle-alert")
        }
        StockProductCard(row = shown, after = model.afterValue, afterLabel = if (count) L("after this count") else L("after the write-off"), today = today)
        if (shown.quantityOnHand < 0 && count) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                message = if (shown.quantityOnHand == -1) L("1 more was sold than the system had. Count what is on the shelf to set it right.")
                else LF("%@ more were sold than the system had. Count what is on the shelf to set it right.", StockText.count(-shown.quantityOnHand)),
                icon = "triangle-alert",
            )
        }
        if (model.lots.isNotEmpty()) {
            FormField(
                label = if (count) L("What are you counting?") else L("Which lot?"),
                hint = if (kind == StockAdjustKind.waste && model.lot == null) L("Taken from what has expired first, then from the lots that expire soonest.") else null,
                caps = true,
            ) {
                FlowLayout(spacing = 8.dp) {
                    LotOptions(model).forEach { (value, title, color) ->
                        FilterChip(title = title, isSelected = model.chosenLot == value, tone = color ?: Theme.blue, dot = color) {
                            if (model.chosenLot != value) {
                                model.chosenLot = value
                                model.saveError = null
                            }
                        }
                    }
                }
            }
        }
        if (kind == StockAdjustKind.waste && model.available == 0) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                message = if (model.lot != null) L("Nothing is left in this lot.") else L("There is nothing on the shelf to write off."),
                icon = "triangle-alert",
            )
        } else {
            PMTextField(
                if (count) L("Counted") else L("How many"),
                text = stateOf({ model.quantity }, { model.quantity = it }),
                key = "quantity",
                form = model.form,
                prompt = "0",
                hint = model.quantityHint,
                caps = true,
                required = true,
                number = PMTextField.Number.large,
            )
        }
        if (kind == StockAdjustKind.waste) {
            FormField(label = L("Why"), key = "reason", form = model.form, caps = true, required = true) {
                PillPicker(
                    options = WasteReason.entries.map { it to it.label },
                    choice = stateOf({ model.reason }, { next ->
                        if (next != model.reason) {
                            model.reason = next
                            model.form.clear("reason")
                            model.saveError = null
                        }
                    }),
                )
            }
        } else if (model.largeDifference) {
            NoticeCard(tone = NoticeCard.Tone.warning, message = L("This count is far from what the system has. Count again, or say why below."), icon = "triangle-alert")
        }
        val noteRequired = model.noteRequired
        PMTextField(
            L("Note"),
            text = stateOf({ model.note }, { model.note = it }),
            key = "note",
            form = model.form,
            prompt = if (count) L("e.g. Monthly count, two boxes found in the back") else L("e.g. Dropped on delivery, returned to the supplier"),
            optional = !noteRequired,
            multiline = true,
            caps = true,
            required = noteRequired,
            lines = 2..8,
        )
        if (kind == StockAdjustKind.waste && model.controlled) {
            PMTextField(
                L("Witness"),
                text = stateOf({ model.witness }, { model.witness = it }),
                prompt = L("Who saw it written off"),
                hint = L("Controlled medicine: the write-off, its reason and its witness go in the controlled register."),
                optional = true,
                caps = true,
            )
        }
    }
}

/** The whole product, then each lot: (value, title, colour). */
@Composable
private fun LotOptions(model: AdjustModel): List<Triple<String, String, Color?>> {
    val shown = model.shown
    val today = model.today
    val first = if (model.kind == StockAdjustKind.count) {
        if (shown.quantityOnHand < 0) LF("Whole product (%@)", StockText.short(shown.quantityOnHand, withCount = false))
        else LF("Whole product (%@)", StockText.count(shown.quantityOnHand))
    } else {
        L("Soonest to expire first")
    }
    val destructive = Theme.destructive
    return listOf(Triple<String, String, Color?>(wholeProduct, first, null)) + model.lots.map { entry ->
        val state = StockRules.expiry(entry.expiresOn, today = today)
        var title = "${entry.lotCode.nonEmpty ?: L("No lot")} (${StockText.count(entry.quantityOnHand)})"
        entry.expiresOn.nonEmpty?.let { expires -> title += " · ${if (state == StockExpiry.expired) L("expired") else L("exp.")} ${StockText.day(expires)}" }
        // Expired red, close to expiry yellow, as the web's options.
        val color: Color? = when (state) {
            StockExpiry.expired -> destructive
            StockExpiry.soon -> Theme.lime
            else -> null
        }
        Triple(entry.id, title, color)
    }
}

// MARK: - One product: lots and history

private enum class MovementGroup { all, came, went, counts }

private const val movementLimit = 100

@Composable
fun StockDetailSheet(
    row: StockRow,
    startOnHistory: Boolean,
    canWriteOff: Boolean,
    onWriteOff: (String) -> Unit,
    onRoute: ((ProviderRoute) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val history = remember { mutableStateOf(startOnHistory) }
    var group by remember { mutableStateOf(MovementGroup.all) }
    var movements by remember { mutableStateOf<List<StockMovement>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val today = StockRules.today
    val lots = row.lots.filter { it.quantityOnHand != 0 }

    suspend fun loadMovements() {
        val id = row.inventoryItemId
        if (id == null) {
            movements = emptyList()
            return
        }
        try {
            movements = API.get<List<StockMovement>>("/provider-stock/$id/movements")
            loadError = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = errorText(error)
        }
    }

    LaunchedEffect(history.value) {
        if (history.value && movements == null) loadMovements()
    }

    ProviderModal(
        title = row.name,
        subtitle = L("Its lots and everything that moved its stock."),
        systemImage = "layers",
        accent = ModalAccent.aqua,
        cancelTitle = L("Close"),
        modifier = modifier,
    ) {
        StockProductCard(row = row, today = today)
        val kept = listOf(
            row.storageLocation.nonEmpty?.let { LF("Kept in %@", it) },
            row.reorderQuantity?.let { if (it > 0) LF("Usual order: %@", StockText.count(it)) else null },
            if (row.allowBackorder) L("Keeps selling at zero") else null,
        )
        if (kept.any { it != null }) {
            StockMetaLine(parts = kept, modifier = Modifier.padding(horizontal = 4.dp).pullUp(8.dp))
        }
        // On a phone the two choices drop their icons, as on the web.
        Segmented(
            options = listOf(
                Segmented.Option(false, L("Lots"), icon = "layers", badge = if (lots.isEmpty()) null else lots.size),
                Segmented.Option(true, L("History"), icon = "history"),
            ),
            selection = history,
            look = Segmented.Look.raised,
            label = L("What to see"),
            fill = true,
        )
        if (history.value) {
            // History
            val failed = loadError
            val list = movements
            if (failed != null && list == null) {
                EmptyState(
                    systemImage = "history",
                    title = L("The history could not be loaded."),
                    message = failed,
                    actionTitle = L("Try again"),
                    action = { scope.launch { loadMovements() } },
                    accent = Theme.pink,
                )
            } else if (list != null) {
                if (list.isEmpty()) {
                    EmptyState(systemImage = "history", title = L("Nothing has moved yet."), accent = Theme.mango)
                } else {
                    val listed = list.filter { inGroup(it, group) }
                    FlowLayout(spacing = 6.dp) {
                        val done = ProviderState.done.color
                        val red = Theme.destructive
                        FilterChip(title = L("Everything"), isSelected = group == MovementGroup.all, tone = Theme.blue) { group = MovementGroup.all }
                        FilterChip(title = L("Came in"), isSelected = group == MovementGroup.came, tone = done, dot = done) { group = MovementGroup.came }
                        FilterChip(title = L("Went out"), isSelected = group == MovementGroup.went, tone = red, dot = red) { group = MovementGroup.went }
                        FilterChip(title = L("Counts"), isSelected = group == MovementGroup.counts, tone = Theme.lime, dot = Theme.lime) { group = MovementGroup.counts }
                    }
                    if (listed.isEmpty()) {
                        Text(
                            L("No movements of this kind."),
                            style = Theme.text(14),
                            color = Theme.inkSoft,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    } else {
                        ListCard(style = ListCardStyle.box) {
                            listed.forEachIndexed { index, movement ->
                                if (index > 0) RowDivider()
                                MovementRow(movement, onRoute)
                            }
                        }
                    }
                    Text(
                        if (list.size >= movementLimit) LF("These are the latest %d movements.", movementLimit) else (if (listed.size == 1) L("1 movement") else LF("%d movements", listed.size)),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                }
            } else {
                LoadingState()
            }
        } else {
            // Lots
            if (lots.isEmpty()) {
                EmptyState(
                    systemImage = "layers",
                    title = L("No lots on the shelf"),
                    message = L("Receive a delivery with its lot number or expiry date and it is listed here, soonest to expire first."),
                    accent = Theme.aqua,
                )
            } else {
                ListCard(style = ListCardStyle.box) {
                    lots.forEachIndexed { index, lot ->
                        if (index > 0) RowDivider()
                        LotRow(lot, today, canWriteOff, onWriteOff)
                    }
                }
                val loose = row.quantityOnHand - lots.sumOf { it.quantityOnHand }
                if (loose != 0) {
                    CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Glyph("triangle-alert", 14.dp, modifier = Modifier.padding(top = 1.dp))
                            Text(
                                if (loose > 0) {
                                    if (loose == 1) L("1 more on hand belongs to no lot: received without a lot number or expiry.")
                                    else LF("%@ more on hand belong to no lot: received without a lot number or expiry.", StockText.count(loose))
                                } else {
                                    LF("The lots add up to %@ more than the product has on hand. A count sets it right.", StockText.count(-loose))
                                },
                                style = Theme.text(12),
                                color = Theme.inkSoft,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun inGroup(movement: StockMovement, group: MovementGroup): Boolean = when (group) {
    MovementGroup.all -> true
    MovementGroup.came -> movement.movementType in listOf("initial", "restock", "return")
    MovementGroup.went -> movement.movementType in listOf("sale", "waste")
    MovementGroup.counts -> movement.movementType in listOf("correction", "adjustment")
}

@Composable
private fun LotRow(lot: StockLot, today: String, canWriteOff: Boolean, onWriteOff: (String) -> Unit) {
    val state = StockRules.expiry(lot.expiresOn, today = today)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowLayout(spacing = 8.dp) {
                    Text(lot.lotCode?.let { LF("Lot %@", it) } ?: L("No lot number"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    if (state == StockExpiry.expired) Chip(L("Expired@lot"), color = Theme.destructive, percent = 22.0, dot = true)
                    if (state == StockExpiry.soon) Chip(L("Expiring soon"), color = Theme.lime, percent = 22.0, dot = true)
                }
                StockMetaLine(
                    parts = listOf(
                        lot.expiresOn?.let { if (state == StockExpiry.expired) LF("Expired on %@", StockText.day(it)) else LF("Expires %@", StockText.day(it)) } ?: L("No expiry date"),
                        lot.receivedAt?.let { LF("received %@", DateText.date(DateText.parse(it))) },
                        lot.unitCostAmount?.let { if (it > 0) LF("%@ each", Money.format(it)) else null },
                    ),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                StockText.count(lot.quantityOnHand),
                style = Theme.heading(20).monospacedDigit(),
                color = if (state == StockExpiry.expired) Theme.destructive else Theme.inkStrong,
            )
        }
        if (canWriteOff && state == StockExpiry.expired && lot.quantityOnHand > 0) {
            PMButton(L("Write off"), systemImage = "trash-2", variant = ButtonVariant.outline) { onWriteOff(lot.id) }
        }
    }
}

@Composable
private fun MovementRow(movement: StockMovement, onRoute: ((ProviderRoute) -> Unit)?) {
    val delta = movement.quantityDelta ?: 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            StockText.signed(delta),
            style = Theme.heading(18).monospacedDigit(),
            color = if (delta < 0) Theme.destructive else ProviderState.done.color,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            FlowLayout(spacing = 0.dp) {
                Text(StockDetailSheet.label(movement), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                val folio = movement.folio.nonEmpty
                if (folio != null) {
                    Text(" · ", style = Theme.text(14), color = Theme.inkSoft)
                    val invoiceId = movement.invoiceId
                    if (invoiceId != null && onRoute != null) {
                        // The sale in the Store, or the visit's bill in Invoices.
                        Text(
                            folio,
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.blue,
                            modifier = Modifier.plainButton(label = LF("Open %@", folio)) {
                                onRoute(if (movement.invoiceKind == "sale") ProviderRoute.sale(invoiceId) else ProviderRoute.invoice(invoiceId))
                            },
                        )
                    } else {
                        Text(folio, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp), color = Theme.inkSoft)
                    }
                }
                movement.lotCode.nonEmpty?.let { Text(" · ${LF("Lot %@", it)}", style = Theme.text(14), color = Theme.inkSoft) }
            }
            StockMetaLine(
                parts = listOf(
                    StockText.dateTime(movement.occurredAt),
                    movement.actorName,
                    if (movement.movementType == "restock") movement.unitCostAmount?.let { if (it > 0) LF("%@ each", Money.format(it)) else null } else null,
                ),
            )
            movement.notes.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp)) }
        }
        Text(LF("→ %@", StockText.count(movement.quantityAfter ?: 0)), style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft)
    }
}

/** `StockDetailSheet`'s static members. */
object StockDetailSheet {
    /** What the movement was. A dose given in the hospital took stock without selling it. */
    fun label(movement: StockMovement): String {
        if (movement.sourceType == "hospital_administration") {
            if (movement.movementType == "sale") return L("Given in the hospital")
            if (movement.movementType == "return") return L("Put back from the hospital")
        }
        return when (movement.movementType) {
            "initial" -> L("Starting stock")
            "restock" -> L("Received")
            "adjustment" -> L("Adjusted")
            "sale" -> L("Sold")
            "return" -> L("Returned")
            "waste" -> L("Written off")
            "correction" -> L("Counted@movement")
            else -> movement.movementType
        }
    }
}

// MARK: - Stock settings

@Stable
private class SettingsModel(val row: StockRow) {
    val form = FormState()
    var reorderPoint by mutableStateOf(if (row.reorderPoint > 0) row.reorderPoint.toString() else "")
    var reorderQuantity by mutableStateOf(row.reorderQuantity?.let { if (it > 0) it.toString() else "" } ?: "")
    var storageLocation by mutableStateOf(row.storageLocation ?: "")
    var barcode by mutableStateOf(row.barcode ?: "")
    var allowBackorder by mutableStateOf(row.allowBackorder)
    var lowStockAlert by mutableStateOf(row.lowStockAlert != false)
    var saving by mutableStateOf(false)
    /** (message, connection). */
    var saveError by mutableStateOf<Pair<String, Boolean>?>(null)

    var onSaved: () -> Unit = {}
    var dismiss: () -> Unit = {}

    val point: Pair<Int?, StockQuantityProblem?>
        get() = if (reorderPoint.nonEmpty == null) (0 to null) else StockRules.parseQuantity(reorderPoint, allowZero = true)
    val usual: Pair<Int?, StockQuantityProblem?>
        get() = if (reorderQuantity.nonEmpty == null) (null to null) else StockRules.parseQuantity(reorderQuantity, allowZero = true)
    val effectivePoint: Int get() = point.first?.takeIf { it > 0 } ?: StockRules.defaultLowPoint
    /** What can still be sold is what the alert goes by. */
    val sellable: Int get() = row.quantityOnHand - max(0, row.expiredUnits ?: 0)
    val changed: Boolean
        get() = allowBackorder != row.allowBackorder || lowStockAlert != (row.lowStockAlert != false) ||
            reorderPoint != (if (row.reorderPoint > 0) row.reorderPoint.toString() else "") ||
            reorderQuantity != (row.reorderQuantity?.let { if (it > 0) it.toString() else "" } ?: "") ||
            storageLocation != (row.storageLocation ?: "") || barcode != (row.barcode ?: "")

    /** With what is left to sell, it would show as low once saved. */
    val lowAfter: Boolean get() = point.second == null && lowStockAlert && point.first != null && sellable <= effectivePoint

    val pointHint: String
        get() {
            if (point.second != null) return L("Enter a whole number, or leave it empty.")
            if (lowAfter) return LF("With %@ to sell, it will show as low stock.", StockText.count(max(0, sellable)))
            if (reorderPoint.nonEmpty != null && (point.first ?: 0) > 0) return LF("Low once %@ or fewer are left.", StockText.count(effectivePoint))
            return LF("Empty: low once %@ or fewer are left.", StockText.count(StockRules.defaultLowPoint))
        }

    suspend fun submit() {
        val wholeNumber = L("Enter a whole number, or leave it empty.")
        val ok = form.check(
            listOf(
                "reorderPoint" to (if (lowStockAlert && point.second != null) wholeNumber else null),
                "reorderQuantity" to (if (usual.second != null) wholeNumber else null),
            ),
        )
        if (!ok || saving) return
        saving = true
        saveError = null
        try {
            API.send(
                "PATCH",
                "/provider-catalog/${row.catalogItemId}/inventory",
                body = mapOf(
                    "lowStockAlert" to lowStockAlert,
                    "reorderPoint" to (point.first ?: 0),
                    "reorderQuantity" to (if ((usual.first ?: 0) > 0) usual.first else null),
                    "storageLocation" to storageLocation.nonEmpty,
                    "barcode" to barcode.nonEmpty,
                    "allowBackorder" to allowBackorder,
                ),
            )
            Toast.show(LF("Stock settings of %@ saved.", row.name))
            onSaved()
            dismiss()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val connection = stockIsConnectionError(error)
            saveError = (
                if (connection) L("We could not reach PawMiaw. Nothing was saved: your changes are still here, try again.")
                else stockErrorText(error, fallback = L("Could not save these settings."))
                ) to connection
            form.apply(error, mapOf("reorderPoint" to "reorderPoint", "reorderQuantity" to "reorderQuantity"))
        } finally {
            saving = false
        }
    }
}

@Composable
fun StockSettingsSheet(row: StockRow, modifier: Modifier = Modifier, onSaved: () -> Unit) {
    val model = remember { SettingsModel(row) }
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    model.onSaved = onSaved
    model.dismiss = dismiss

    ProviderModal(
        title = L("Stock settings"),
        subtitle = L("When it counts as low, where it is kept and how it is found."),
        systemImage = "sliders-horizontal",
        accent = ModalAccent.butter,
        isDirty = model.changed,
        form = model.form,
        primaryTitle = L("Save settings"),
        isWorking = model.saving,
        primaryIcon = "save",
        onPrimary = { scope.launch { model.submit() } },
        modifier = modifier,
    ) {
        model.saveError?.let { (message, connection) ->
            NoticeCard(tone = NoticeCard.Tone.problem, title = message, icon = if (connection) "wifi-off" else "triangle-alert")
        }
        StockProductCard(row = row)
        ModalSection(
            title = L("Low stock"),
            subtitle = L("While it is on, the product shows as low stock here, at the counter and on Home once this few are left to sell."),
        ) {
            ToggleRow(
                title = L("Mark it low on Home and Inventory"),
                subtitle = if (model.lowStockAlert) L("Flagged as low at the count below.") else L("Never flagged as low: it only shows once it is out of stock."),
                isOn = stateOf({ model.lowStockAlert }, { model.lowStockAlert = it }),
            )
            if (model.lowStockAlert) {
                PMTextField(
                    L("Low at"),
                    text = stateOf({ model.reorderPoint }, { model.reorderPoint = it }),
                    key = "reorderPoint",
                    form = model.form,
                    prompt = StockRules.defaultLowPoint.toString(),
                    hint = model.pointHint,
                    optional = true,
                    caps = true,
                    strongHint = model.lowAfter,
                    number = PMTextField.Number.plain,
                )
            }
            PMTextField(
                L("Usual order"),
                text = stateOf({ model.reorderQuantity }, { model.reorderQuantity = it }),
                key = "reorderQuantity",
                form = model.form,
                prompt = L("None"),
                hint = L("How many units you usually ask the supplier for."),
                optional = true,
                caps = true,
                number = PMTextField.Number.plain,
            )
        }
        ModalSection(title = L("Finding it")) {
            FormField(label = L("Barcode"), hint = L("A scan at the counter or in a delivery then finds this product."), optional = true, caps = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldChrome(modifier = Modifier.weight(1f)) {
                        PlainTextField(
                            text = stateOf({ model.barcode }, { model.barcode = it }),
                            prompt = L("Scan it or type it"),
                            modifier = Modifier.weight(1f),
                            keyboard = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.None,
                            autocorrect = false,
                        )
                    }
                    BarcodeScanButton { scan -> model.barcode = scan.code.take(120) }
                }
            }
            PMTextField(L("Where it is kept"), text = stateOf({ model.storageLocation }, { model.storageLocation = it }), prompt = L("e.g. Shelf B, fridge"), optional = true, caps = true)
        }
        ToggleRow(
            title = L("Keep selling when it runs out"),
            subtitle = if (model.allowBackorder) L("The counter sells it at zero; the stock goes negative until the next delivery or count.") else L("The counter stops selling it once none is left."),
            isOn = stateOf({ model.allowBackorder }, { model.allowBackorder = it }),
        )
    }
}
