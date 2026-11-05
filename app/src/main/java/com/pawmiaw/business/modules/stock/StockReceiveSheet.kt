package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.money.CashCatalogEditorSheet
import com.pawmiaw.business.money.CashCatalogItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

//
//  StockReceiveSheet.kt (iOS StockReceiveSheet.swift)
//
//  Recibir mercancía (web inventory/receive-stock-sheet.tsx + stock-product-picker.tsx):
//  which product (skipped when it opens from a product's row), what arrived —
//  how many, by unit or by pack, what it cost, its lot and expiry, who
//  delivered it — and a review of what it does to the shelf and its cost.
//  "Save and receive another" keeps the supplier and the invoice for the next
//  product of the same delivery. One request id per delivery typed: a retry
//  never receives it twice. The product is searched by name, SKU or barcode,
//  or scanned (a hardware scanner types its code and presses Return; the scan
//  mark reads it with the camera): a GS1 label also fills the lot and the
//  expiry. A code no product has can become a new Catalog product, with that
//  barcode already on it, and the delivery carries on with it.
//

private enum class ReceiveStep { product, receipt, review }
private enum class CostMode { each, total }

/** Swift `.whitespaces`: spaces and tabs. */
private fun String.trimSpaces(): String = trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** Swift `.whitespacesAndNewlines`, without the GS1 group separator Kotlin's `trim()` would also take. */
private fun String.trimWhitespace(): String = trim {
    it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000B' || it == '\u000C' || it == '\u0085' || it == ' ' || it == ' ' ||
        Character.getType(it) == Character.SPACE_SEPARATOR.toInt()
}

/** A new product to create from the delivery: the code that matched nothing and what its label said. */
data class StockCreateTarget(
    val barcode: String? = null,
    val lot: String? = null,
    val expiresOn: String? = null,
) {
    val id: UUID = UUID.randomUUID()
}

/** One product received in this delivery. */
private data class ReceivedEntry(val key: String, val name: String, val units: Int, val onHand: Int)

private data class ReceiveFigures(
    val units: Int,
    val unitCost: Int?,
    val totalCost: Int?,
    val onHandAfter: Int,
    val costAfter: Int?,
)

/** The cost of one unit in cents: null when not said (the current cost stays); `invalid` when it can't be read. */
private data class ReceiveUnitCost(val value: Int?, val invalid: Boolean, val tooLarge: Boolean)

/** A refused or lost save. */
private data class ReceiveError(val message: String, val gone: Boolean, val connection: Boolean)

/** A code that was scanned (or typed and entered) and belongs to no product, with what else its label said. */
private data class UnknownCode(val code: String, val lot: String?, val expiresOn: String?)

private const val receivePageSize = 20
private const val receivePageMax = 100

@Stable
private class ReceiveModel(val preset: StockRow?, val severalLocations: Boolean) {
    val form = FormState()
    var product by mutableStateOf<StockRow?>(null)
    var fixed by mutableStateOf(false)
    var step by mutableStateOf(ReceiveStep.product)

    var quantity by mutableStateOf("")
    var unitsPerPack by mutableIntStateOf(1)
    var costText by mutableStateOf("")
    var costMode by mutableStateOf(CostMode.each)
    var lotCode by mutableStateOf("")
    var expiresOn by mutableStateOf("")
    var expiredConfirmed by mutableStateOf(false)
    var supplier by mutableStateOf("")
    var invoiceRef by mutableStateOf("")
    var note by mutableStateOf("")

    var saving by mutableStateOf(false)
    var saveError by mutableStateOf<ReceiveError?>(null)
    var received by mutableStateOf<List<ReceivedEntry>>(emptyList())
    var requestId: String = StockAPI.newRequestId()

    // The picker.
    var search by mutableStateOf("")
    var products by mutableStateOf<List<StockRow>?>(null)
    var productsError by mutableStateOf<String?>(null)
    /** How many the list asks for: 20 more each time its last row shows (the server sends up to 100). */
    var productLimit by mutableIntStateOf(receivePageSize)
    var loadingMore by mutableStateOf(false)
    var unknownCode by mutableStateOf<UnknownCode?>(null)
    var looking by mutableStateOf(false)
    /** A new product: with the code that matched nothing (and its label's lot and expiry) when there is one. */
    var creating by mutableStateOf<StockCreateTarget?>(null)
    /** The lot and expiry a scanned label filled in (lot, expiry): said so next to each, until it is changed. */
    var fromLabel by mutableStateOf<Pair<String?, String?>>(null to null)

    var onSaved: () -> Unit = {}
    var dismiss: () -> Unit = {}
    var scope: CoroutineScope? = null

    init {
        // Swift `.onAppear` (once).
        if (preset != null) {
            product = preset
            fixed = true
            step = ReceiveStep.receipt
        }
    }

    fun launch(block: suspend () -> Unit) {
        scope?.launch { block() }
    }

    val canCreate: Boolean get() = Workspace.shared.can(Permission.catalogCreate)
    val today: String get() = StockRules.today
    val byPack: Boolean get() = unitsPerPack > 1
    val mustHaveLot: Boolean get() = product?.let { StockRules.requiresLotAndExpiry(it) } ?: false

    val title: String
        get() {
            val product = product
            if (product != null && step != ReceiveStep.product) return LF("Receive %@", product.name)
            return L("Receive delivery")
        }

    /** The product step is left out when the sheet opened on a product. */
    val stepList: List<Pair<String, String>>
        get() {
            val all = listOf(L("Product") to "package-search", L("What arrived") to "package-plus", L("Review") to "clipboard-check")
            return if (fixed) all.drop(1) else all
        }

    val stepIndex: Int get() = if (fixed) step.ordinal - 1 else step.ordinal

    val primaryTitle: String get() = if (step == ReceiveStep.review) L("Receive stock") else L("Continue")

    fun primary() {
        when (step) {
            ReceiveStep.product -> {
                if (!form.check(listOf("product" to (if (product == null) L("Choose the product that arrived.") else null)))) return
                step = ReceiveStep.receipt
            }
            ReceiveStep.receipt -> goReview()
            ReceiveStep.review -> launch { submit(another = false) }
        }
    }

    // MARK: The product

    suspend fun loadProducts() {
        try {
            products = searchProducts(ClientSearch.query(search) ?: "", limit = productLimit)
            productsError = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            productsError = errorText(error)
        }
    }

    /** The next 20, once the list's last row shows (the server answers up to 100 at once). */
    val hasMoreProducts: Boolean
        get() {
            val products = products ?: return false
            return products.size >= productLimit && productLimit < receivePageMax
        }

    suspend fun loadMoreProducts() {
        if (!hasMoreProducts || loadingMore) return
        loadingMore = true
        try {
            productLimit = min(receivePageMax, productLimit + receivePageSize)
            loadProducts()
        } finally {
            loadingMore = false
        }
    }

    /** Every product that keeps stock, by name, SKU or exact barcode. */
    suspend fun searchProducts(text: String, limit: Int? = null): List<StockRow> {
        return try {
            API.get("/provider-stock/products", mapOf("q" to text.nonEmpty, "limit" to limit?.toString()))
        } catch (error: APIError) {
            if (error.status != 404) throw error
            API.get("/provider-stock", mapOf("q" to text.nonEmpty))
        }
    }

    /**
     * A scanner types the code and presses Return faster than the list can follow: the code is looked
     * up right then. The camera hands its code over (`scan`) and it is looked up the same way. A code is
     * the same code with or without its leading 0 (GTIN-14, EAN-13, UPC-A): each spelling is tried.
     */
    suspend fun lookUp(scan: BarcodeScan?) {
        val code = (scan?.code ?: search).trimWhitespace()
        if (code.isEmpty() || looking) return
        looking = true
        try {
            if (scan != null) search = code
            val found = Barcode.findByCode<StockRow>(code, format = scan?.format, barcode = { it.barcode }, sku = { it.sku }, key = { it.catalogItemId }) { text ->
                searchProducts(text)
            }
            val match = found.exact ?: (if (found.matches.isEmpty() && found.found.size == 1) found.found[0] else null)
            if (match != null) {
                unknownCode = null
                search = ""
                choose(match, scannedLot = scan?.lot, scannedExpiry = scan?.expiresOn)
            } else if (found.matches.size > 1) {
                // The code is on more than one product: the list shows them all, and the person picks.
                unknownCode = null
                search = found.code
                products = found.matches
                Toast.show(LF("%d products have this code. Choose which one arrived.", found.matches.size))
            } else if (found.found.isEmpty()) {
                unknownCode = UnknownCode(code, scan?.lot, scan?.expiresOn)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // The list below says what went wrong.
        } finally {
            looking = false
        }
    }

    fun choose(next: StockRow) = choose(next, scannedLot = null, scannedExpiry = null)

    /**
     * The product chosen and, when its label was scanned, the lot and expiry it carried —
     * filled in only where nothing was entered.
     */
    fun choose(next: StockRow, scannedLot: String?, scannedExpiry: String?) {
        val other = next.catalogItemId != product?.catalogItemId
        if (other) {
            requestId = StockAPI.newRequestId()
            // The supplier and its invoice belong to the delivery, not to the product.
            quantity = ""; unitsPerPack = 1; costText = ""; costMode = CostMode.each
            lotCode = ""; expiresOn = ""; expiredConfirmed = false; note = ""
            fromLabel = null to null
        }
        val lot = scannedLot.nonEmpty?.take(StockRules.lotCodeMax)
        if (lot != null && lotCode.trimSpaces().isEmpty()) {
            lotCode = lot
            fromLabel = lot to fromLabel.second
        }
        val expiry = scannedExpiry.nonEmpty
        if (expiry != null && expiresOn.isEmpty()) {
            expiresOn = expiry
            expiredConfirmed = false
            fromLabel = fromLabel.first to expiry
        }
        unknownCode = null
        product = next
        form.errors = emptyMap()
        saveError = null
        step = ReceiveStep.receipt
    }

    // MARK: What arrived (web lib/inventory.ts readReceipt)

    val parsedQuantity: Pair<Int?, StockQuantityProblem?>
        get() = StockRules.parseQuantity(quantity, max = StockRules.maxQuantity / max(1, unitsPerPack))

    val units: Int get() = (parsedQuantity.first ?: 0) * max(1, unitsPerPack)

    val unitCost: ReceiveUnitCost
        get() {
            if (costText.nonEmpty == null) return ReceiveUnitCost(null, invalid = false, tooLarge = false)
            val amount = Money.parse(costText) ?: return ReceiveUnitCost(null, invalid = true, tooLarge = false)
            val cost: Int? = if (costMode == CostMode.total) {
                if (units > 0) (amount.toDouble() / units).roundToInt() else null
            } else {
                (amount.toDouble() / max(1, unitsPerPack)).roundToInt()
            }
            return ReceiveUnitCost(cost, invalid = false, tooLarge = (cost ?: 0) > StockRules.maxCostCents)
        }

    val receiptNote: String
        get() = listOfNotNull(
            supplier.nonEmpty?.let { "${L("Supplier")}: $it" },
            invoiceRef.nonEmpty?.let { "${L("Supplier invoice")}: $it" },
            note.nonEmpty,
        ).joinToString(" · ")

    val figures: ReceiveFigures?
        get() {
            val product = product ?: return null
            val units = units
            if (units <= 0) return null
            val cost = unitCost.value
            val current = product.unitCostAmount
            val costAfter: Int? = if (current != null) {
                StockRules.averageCostAfter(onHand = product.quantityOnHand, currentCost = current, received = units, receivedCost = cost ?: current)
            } else {
                if (max(0, product.quantityOnHand) == 0) cost else null
            }
            return ReceiveFigures(units = units, unitCost = cost, totalCost = cost?.let { it * units }, onHandAfter = product.quantityOnHand + units, costAfter = costAfter)
        }

    val expiresIn: Int? get() = if (expiresOn.isEmpty()) null else StockRules.daysBetween(today, expiresOn)

    fun quantityProblemText(problem: StockQuantityProblem): String = when (problem) {
        StockQuantityProblem.required, StockQuantityProblem.zero -> if (byPack) L("Enter how many packs arrived.") else L("Enter how many arrived.")
        StockQuantityProblem.whole -> L("Stock is counted in whole units: no decimals.")
        StockQuantityProblem.negative -> L("A delivery adds stock. To take stock out, write it off.")
        StockQuantityProblem.tooLarge -> LF("That is more than one delivery can hold (%@ units). Check the number.", StockText.count(StockRules.maxQuantity / max(1, unitsPerPack) * max(1, unitsPerPack)))
        StockQuantityProblem.invalid -> L("Enter the quantity as a number.")
    }

    fun checkReceipt(): Boolean {
        val cost = unitCost
        return form.check(
            listOf(
                "quantity" to parsedQuantity.second?.let { quantityProblemText(it) },
                "cost" to (if (cost.invalid) L("Enter the cost as an amount, like 125.50.") else if (cost.tooLarge) L("That cost is too high to be right. Check it.") else null),
                "lotCode" to (
                    if (lotCode.length > StockRules.lotCodeMax) LF("Up to %d characters.", StockRules.lotCodeMax)
                    else if (mustHaveLot && lotCode.nonEmpty == null) L("Enter the lot number printed on the box.") else null
                    ),
                "expiresOn" to (
                    if (mustHaveLot && expiresOn.isEmpty()) L("Choose the expiry date on the box.")
                    else if (expiresOn.isNotEmpty() && expiresOn < today && !expiredConfirmed) L("Confirm that it arrived expired, or correct the date.") else null
                    ),
                "note" to (if (receiptNote.length > StockRules.noteMax) L("The supplier, the invoice and the note are too long together. Shorten the note.") else null),
            ),
        )
    }

    fun goReview() {
        if (!checkReceipt() || figures == null) return
        saveError = null
        step = ReceiveStep.review
    }

    /** The cost's line says a result (per unit · in all) rather than help. */
    val costHintIsResult: Boolean
        get() {
            if (costText.nonEmpty == null) return false
            val figures = figures ?: return false
            val unit = figures.unitCost ?: return false
            if (unit <= 0) return false
            return figures.totalCost != null
        }

    fun costHint(product: StockRow): String? {
        if (costText.nonEmpty == null) {
            val cost = product.unitCostAmount
            if (cost != null) {
                return if (cost > 0) LF("Left empty, the cost stays at %@ per unit.", Money.format(cost)) else L("Left empty, this stock is kept without a cost. You can receive the next one with it.")
            }
            return L("Left empty, the cost on file stays as it is.")
        }
        val figures = figures
        if (figures != null && figures.unitCost == 0) return L("No cost: a sample, a bonus or a donation. It brings the average cost of the stock down.")
        if (figures?.unitCost != null && figures.totalCost != null) return LF("%@ per unit · %@ in all", Money.format(figures.unitCost), Money.format(figures.totalCost))
        if (costMode == CostMode.total) return L("Enter the quantity to work out the cost of each unit.")
        return null
    }

    fun packsLabel(count: Int): String = if (count == 1) L("1 pack") else LF("%@ packs", StockText.count(count))

    /** The footer's "‹ Back" past the first step (web: `steps[index - 1]`). */
    val stepBack: (() -> Unit)?
        get() {
            if (step == ReceiveStep.product || (fixed && step == ReceiveStep.receipt)) return null
            return {
                saveError = null
                step = if (step == ReceiveStep.review) ReceiveStep.receipt else ReceiveStep.product
            }
        }

    // MARK: Review

    fun costLine(product: StockRow, figures: ReceiveFigures): String {
        val unit = figures.unitCost
        if (unit == null) {
            val current = product.unitCostAmount
            if (current != null && current > 0) return LF("Not entered — stays at %@ per unit", Money.format(current))
            return L("Not entered")
        }
        if (unit == 0) return L("No cost (sample or donation)")
        return LF("%@ per unit", Money.format(unit))
    }

    val lotLine: String
        get() {
            val lot = lotCode.nonEmpty
            if (lot == null && expiresOn.isEmpty()) return L("None — sold with the rest of the stock, in any order")
            val expiry: String? = if (expiresOn.isEmpty()) null else (if ((expiresIn ?: 0) < 0) LF("expired on %@", StockText.day(expiresOn)) else LF("expires %@", StockText.day(expiresOn)))
            return listOfNotNull(lot?.let { LF("Lot %@", it) }, expiry).joinToString(" · ")
        }

    suspend fun submit(another: Boolean) {
        val product = product ?: return
        val figures = figures ?: return
        if (saving) return
        saving = true
        saveError = null
        try {
            val row: StockRow = StockAPI.send(
                "POST",
                "/provider-stock/receive",
                body = mapOf(
                    "catalogItemId" to product.catalogItemId,
                    "quantity" to figures.units,
                    "unitCostAmount" to figures.unitCost,
                    "lotCode" to lotCode.nonEmpty,
                    "expiresOn" to expiresOn.nonEmpty,
                    "note" to receiptNote.nonEmpty,
                    "clientRequestId" to requestId,
                ),
            )
            Toast.show(
                if (row.quantityOnHand < 0) LF("%@ of %@ received. %@.", StockText.units(figures.units), product.name, StockText.short(row.quantityOnHand))
                else if (figures.units == 1) LF("1 unit of %@ received. %@ on hand now.", product.name, StockText.count(row.quantityOnHand))
                else LF("%@ of %@ received. %@ on hand now.", StockText.units(figures.units), product.name, StockText.count(row.quantityOnHand)),
            )
            onSaved()
            if (!another) {
                dismiss()
                return
            }
            received = received + ReceivedEntry(requestId, product.name, figures.units, row.quantityOnHand)
            quantity = ""; unitsPerPack = 1; costText = ""; costMode = CostMode.each
            lotCode = ""; expiresOn = ""; expiredConfirmed = false; note = ""
            this.product = null
            fixed = false
            search = ""
            products = null
            productLimit = receivePageSize
            unknownCode = null
            fromLabel = null to null
            step = ReceiveStep.product
            requestId = StockAPI.newRequestId()
            loadProducts()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (refusal: StockRefusal) {
            if (refusal.code == "controlled_lot_required") {
                // It became a controlled medicine while this was open: its lot and expiry are asked for, under their fields.
                this.product = this.product?.copy(controlSchedule = product.controlSchedule.nonEmpty ?: "controlled")
                step = ReceiveStep.receipt
                val message = refusal.api.message.nonEmpty ?: L("A controlled medicine is received with its lot number and expiry date.")
                val field = if (refusal.body["field"].string == "expiresOn") "expiresOn" else "lotCode"
                form.check(listOf(field to message))
            } else {
                failed(refusal)
            }
        } catch (error: Exception) {
            failed(error)
        } finally {
            saving = false
        }
    }

    private fun failed(error: Throwable) {
        val gone = (error as? StockRefusal)?.let { it.code == "product_archived" || it.status == 404 } ?: false
        val connection = stockIsConnectionError(error)
        val message = if (connection) L("We could not reach PawMiaw, so this may not be saved. Your delivery is still here: try again — it is never added twice.")
        else stockErrorText(error, fallback = L("Could not record the delivery."))
        saveError = ReceiveError(message, gone, connection)
        Toast.error(message)
    }

    companion object {
        /** A product just added to the Catalog, as the picker would have listed it (nothing on the shelf yet). */
        fun receivable(item: CashCatalogItem): StockRow {
            val inventory = item.inventory
            return StockRow(
                inventoryItemId = inventory?.id,
                catalogItemId = item.id,
                name = item.name,
                sku = item.sku,
                barcode = inventory?.barcode,
                photoUrl = item.photoUrl,
                productKind = item.productKind,
                quantityOnHand = inventory?.quantityOnHand ?: 0,
                reorderPoint = inventory?.reorderPoint ?: 0,
                // Null below business finances: the cost on file is not shown.
                unitCostAmount = if (inventory == null) 0 else inventory.unitCostAmount,
                lowStock = inventory?.lowStock ?: false,
                lots = emptyList(),
                packs = emptyList(),
                tracked = inventory != null && item.trackInventory,
                controlSchedule = item.controlSchedule,
                unitAmount = item.defaultUnitAmount,
                allowBackorder = inventory?.allowBackorder ?: false,
                requiresPrescription = item.requiresPrescription || item.controlSchedule.nonEmpty != null,
                storageLocation = inventory?.storageLocation,
                reorderQuantity = inventory?.reorderQuantity,
                lowStockAlert = inventory?.lowStockAlert,
                trackLots = false,
            )
        }
    }
}

@Composable
fun StockReceiveSheet(
    preset: StockRow?,
    severalLocations: Boolean = false,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
) {
    val model = remember { ReceiveModel(preset, severalLocations) }
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    model.onSaved = onSaved
    model.dismiss = dismiss
    model.scope = scope
    val step = model.step

    ProviderModal(
        title = model.title,
        subtitle = L("What arrived, what it cost, and the lot and expiry date on the box."),
        systemImage = "package-check",
        accent = ModalAccent.aqua,
        isDirty = step != ReceiveStep.product || (model.product != null && !model.fixed) || model.received.isNotEmpty(),
        form = model.form,
        primaryTitle = model.primaryTitle,
        isWorking = model.saving,
        cancelTitle = if (model.received.isEmpty()) L("Cancel") else L("Done"),
        steps = ModalSteps(model.stepList, current = model.stepIndex),
        onBack = model.stepBack,
        secondaryActions = if (step == ReceiveStep.review) {
            listOf(ModalAction(L("Save and receive another"), isWorking = model.saving) { model.launch { model.submit(another = true) } })
        } else emptyList(),
        primaryIcon = if (step == ReceiveStep.review) "package-plus" else null,
        onPrimary = { model.primary() },
        modifier = modifier,
    ) {
        when (step) {
            ReceiveStep.product -> ProductStep(model)
            ReceiveStep.receipt -> ReceiptStep(model)
            ReceiveStep.review -> ReviewStep(model)
        }
    }

    // The server searches from two characters, 250 ms after typing stops; under that, every product.
    LaunchedEffect("${ClientSearch.query(model.search) ?: ""}|${step == ReceiveStep.product}") {
        if (model.step != ReceiveStep.product) return@LaunchedEffect
        if (model.products != null) delay(ClientSearch.debounce)
        model.productLimit = receivePageSize
        model.loadProducts()
    }

    model.creating?.let { target ->
        AppSheet(onDismissRequest = { model.creating = null }) {
            CashCatalogEditorSheet(initialBarcode = target.barcode, onCreated = { created ->
                // A pack or a service has no stock of its own to receive.
                if (created.itemType == "product" && created.stockCatalogItemId == null) {
                    model.choose(ReceiveModel.receivable(created), scannedLot = target.lot, scannedExpiry = target.expiresOn)
                }
            })
        }
    }
}

@Composable
private fun ProductStep(model: ReceiveModel) {
    val received = model.received
    if (received.isNotEmpty()) {
        NoticeCard(tone = NoticeCard.Tone.success, icon = "package-check", content = {
            Text(if (received.size == 1) L("1 product received in this delivery") else LF("%d products received in this delivery", received.size), fontWeight = FontWeight.Bold)
            received.forEach { entry ->
                Text(
                    if (entry.onHand < 0) LF("%@: +%@ · %@", entry.name, StockText.count(entry.units), StockText.short(entry.onHand, withCount = false))
                    else LF("%@: +%@ · %@ on hand", entry.name, StockText.count(entry.units), StockText.count(entry.onHand)),
                    color = Theme.inkSoft,
                )
            }
        })
    }
    BarcodeSearchField(
        prompt = L("Search by name, SKU or barcode — or scan it"),
        text = stateOf({ model.search }, { model.search = it; model.unknownCode = null }),
        onSubmit = { model.launch { model.lookUp(null) } },
        modifier = Modifier.formScrollTarget("product", model.form),
        onScan = { scan -> model.launch { model.lookUp(scan) } },
    )
    model.form.error("product")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
    model.unknownCode?.let { unknown ->
        val creatable = model.canCreate && Barcode.looksLikeCode(unknown.code)
        NoticeCard(tone = NoticeCard.Tone.lime, icon = null, content = {
            Text(LF("No product has the code %@.", unknown.code), fontWeight = FontWeight.Bold)
            Text(
                if (model.canCreate) L("Search it by name — and add its barcode in the Catalog so the next scan finds it — or create it.")
                else L("Search it by name. Someone who manages the Catalog can add its barcode."),
                color = Theme.inkSoft,
            )
            if (creatable) {
                PMButton(L("Create the product with this barcode"), systemImage = "plus", variant = ButtonVariant.outline, modifier = Modifier.padding(top = 8.dp)) {
                    model.creating = StockCreateTarget(barcode = unknown.code, lot = unknown.lot, expiresOn = unknown.expiresOn)
                }
            }
        })
    }
    val products = model.products
    val failed = model.productsError
    if (products == null && failed == null) {
        LoadingState()
    } else if (failed != null && products.isNullOrEmpty()) {
        EmptyState(
            systemImage = "package-search",
            title = L("The products could not be loaded."),
            message = failed,
            actionTitle = L("Try again"),
            action = { model.launch { model.loadProducts() } },
            accent = Theme.pink,
        )
    } else if (products.isNullOrEmpty()) {
        val canCreate = model.canCreate
        if (model.search.trimSpaces().isEmpty()) {
            EmptyState(
                systemImage = "package-plus",
                title = L("There are no products yet"),
                message = if (canCreate) L("Stock is kept for the products of your Catalog. Create the first one to receive it.") else L("Stock is kept for the products of the Catalog. Ask someone who manages it to add them."),
                actionTitle = if (canCreate) L("Create the product") else null,
                action = if (canCreate) ({ model.creating = StockCreateTarget() }) else null,
                accent = Theme.mango,
            )
        } else {
            EmptyState(
                systemImage = "package-search",
                title = LF("No product matches “%@”.", model.search.trimSpaces()),
                message = if (canCreate) L("Check the spelling, or create it: it is added to the Catalog and you carry on here.") else L("Check the spelling. Someone who manages the Catalog can add it."),
                actionTitle = if (canCreate) L("Create the product") else null,
                action = if (canCreate) ({ model.creating = StockCreateTarget() }) else null,
                accent = Theme.mango,
            )
        }
    } else {
        ListCard(style = ListCardStyle.box) {
            products.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                ProductRow(model, row)
            }
            LoadMoreRow(hasMore = model.hasMoreProducts, busy = model.loadingMore) { model.launch { model.loadMoreProducts() } }
        }
        if (model.canCreate) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("Can’t find it?"), style = Theme.text(14), color = Theme.inkSoft)
                PMButton(L("Create the product"), variant = ButtonVariant.link, size = ButtonSize.modal) { model.creating = StockCreateTarget() }
            }
        }
    }
}

@Composable
private fun ProductRow(model: ReceiveModel, row: StockRow) {
    val selected = row.catalogItemId == model.product?.catalogItemId
    val detail = (listOf(StockText.detail(row).nonEmpty) + row.packs.map { LF("pack of %d", it.stockUnits) }).filterNotNull().joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) Theme.wash(Theme.aqua, 22.0) else Theme.surface)
            .plainButton { model.choose(row) }
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockProductThumb(photoUrl = row.photoUrl, productKind = row.productKind, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(row.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail.nonEmpty ?: L("No SKU"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.tracked != false) Text(StockText.onHand(row.quantityOnHand), style = Theme.heading(18).monospacedDigit(), color = Theme.inkStrong)
            stockStateChip(row, today = model.today)
        }
        if (selected) Glyph("check", 16.dp, tint = Theme.blue)
    }
}

/** Says a value came from the scanned label, so it is checked against the box rather than trusted blindly. */
@Composable
private fun FromLabelHint() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph("scan-line", 14.dp, tint = Theme.blue)
        Text(L("From the label"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
    }
}

@Composable
private fun ReceiptStep(model: ReceiveModel) {
    val product = model.product ?: return
    val figures = model.figures
    val byPack = model.byPack
    val units = model.units
    val unitsPerPack = model.unitsPerPack
    val form = model.form
    StockProductCard(row = product, after = figures?.onHandAfter, afterLabel = L("after this delivery"), today = model.today)
    if (product.tracked == false) {
        NoticeCard(tone = NoticeCard.Tone.info, message = L("The stock of this product is not counted yet. Receiving this delivery starts counting it."), icon = "info")
    }
    if (product.quantityOnHand < 0) {
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            message = if (product.quantityOnHand == -1) L("1 more was sold than the system had. A count after this delivery sets it right.")
            else LF("%@ more were sold than the system had. A count after this delivery sets it right.", StockText.count(-product.quantityOnHand)),
            icon = "triangle-alert",
        )
    }

    ModalSection(title = L("What arrived")) {
        val packs = product.packs.filter { it.stockUnits > 1 }
        if (packs.isNotEmpty()) {
            FormField(label = L("Receive by"), caps = true) {
                FlowLayout(spacing = 8.dp) {
                    FilterChip(title = L("Single units"), isSelected = unitsPerPack == 1) { model.unitsPerPack = 1 }
                    packs.forEach { pack ->
                        FilterChip(title = LF("%@ (×%d)", pack.name, pack.stockUnits), isSelected = unitsPerPack == pack.stockUnits) { model.unitsPerPack = pack.stockUnits }
                    }
                }
            }
        }
        PMTextField(
            if (byPack) L("How many packs") else L("Quantity"),
            text = stateOf({ model.quantity }, { model.quantity = it }),
            key = "quantity",
            form = form,
            prompt = "0",
            hint = if (byPack && figures != null) LF("%@ × %d = %@", model.packsLabel(units / max(1, unitsPerPack)), unitsPerPack, StockText.units(units))
            else (if (byPack) LF("Each pack adds %d units to the stock.", unitsPerPack) else L("Whole units, as they are sold or used.")),
            caps = true,
            required = true,
            strongHint = byPack && figures != null,
            number = PMTextField.Number.large,
        )
        FormField(
            label = if (model.costMode == CostMode.total) L("Total cost") else if (byPack) L("Cost per pack") else L("Cost per unit"),
            key = "cost",
            hint = model.costHint(product),
            form = form,
            optional = true,
            caps = true,
            strongHint = model.costHintIsResult,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldChrome(invalid = form.error("cost") != null) {
                    Text("$", style = Theme.text(16, FontWeight.Bold), color = Theme.inkSoft)
                    Spacer(Modifier.width(6.dp))
                    PlainTextField(
                        text = stateOf({ model.costText }, { if (it != model.costText) { model.costText = it; form.clear("cost") } }),
                        prompt = "0.00",
                        modifier = Modifier.weight(1f),
                        keyboard = KeyboardType.Decimal,
                        autocorrect = false,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(Workspace.shared.currency, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
        }
        val costIsLabel = L("The cost is")
        PillPicker(
            options = listOf(CostMode.each to (if (byPack) L("Per pack") else L("Per unit")), CostMode.total to L("Whole delivery")),
            selection = stateOf({ model.costMode }, { model.costMode = it }),
            modifier = Modifier.semantics { contentDescription = costIsLabel },
        )
    }

    val mustHaveLot = model.mustHaveLot
    ModalSection(title = L("Lot and expiry"), subtitle = L("With a lot or an expiry, the store sells the soonest-expiring stock first and never sells it once expired.")) {
        if (mustHaveLot) {
            NoticeCard(
                tone = NoticeCard.Tone.info,
                title = L("Controlled medicine: its lot and its expiry date are required."),
                message = L("What is dispensed is recorded in the Pharmacy log"),
                icon = "shield-alert",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PMTextField(
                L("Lot number"),
                text = stateOf({ model.lotCode }, { model.lotCode = it }),
                key = "lotCode",
                form = form,
                prompt = L("As printed on the box"),
                capitalization = KeyboardCapitalization.Characters,
                optional = !mustHaveLot,
                caps = true,
                required = mustHaveLot,
            )
            val lot = model.fromLabel.first
            if (lot != null && lot == model.lotCode) FromLabelHint()
        }
        FormField(label = L("Expiry date"), key = "expiresOn", form = form, optional = !mustHaveLot, caps = true, required = mustHaveLot) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Empty until chosen ("Choose the date"), even where it is required.
                StockDayControl(
                    day = stateOf({ model.expiresOn }, { if (it != model.expiresOn) { model.expiresOn = it; model.expiredConfirmed = false } }),
                    invalid = form.error("expiresOn") != null,
                ) { form.clear("expiresOn") }
                val expiry = model.fromLabel.second
                if (expiry != null && expiry == model.expiresOn) FromLabelHint()
                if (model.expiresOn.isNotEmpty() && !mustHaveLot) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            L("Clear the date"),
                            style = Theme.text(12, FontWeight.Bold),
                            color = Theme.blue,
                            modifier = Modifier.plainButton {
                                model.expiresOn = ""
                                model.expiredConfirmed = false
                            },
                        )
                    }
                }
            }
        }
        val days = model.expiresIn
        if (days != null && days < 0) {
            NoticeCard(tone = NoticeCard.Tone.problem, icon = "triangle-alert", content = {
                Text(LF("This date has already passed (%@).", StockText.day(model.expiresOn)), fontWeight = FontWeight.Bold)
                Text(L("Expired stock is counted but never sold: it waits to be written off or sent back."), color = Theme.inkSoft)
                // The web's checkbox row.
                val confirmed = model.expiredConfirmed
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .plainButton {
                            model.expiredConfirmed = !model.expiredConfirmed
                            form.clear("expiresOn")
                        }
                        .semantics { selected = confirmed },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tick(on = confirmed, invalid = form.error("expiresOn") != null)
                    Text(L("It arrived expired — receive it anyway"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                }
            })
        } else if (days != null && days <= StockRules.expiringDays) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                message = if (days == 0) L("It expires today: it can be sold only today.") else if (days == 1) L("It expires tomorrow.") else LF("It expires in %d days: it will show as expiring soon.", days),
                icon = "calendar-clock",
            )
        } else if (model.expiresOn.isEmpty() && !mustHaveLot && StockRules.isMedicine(product)) {
            Text(L("Medicines should carry their expiry date: without it the store cannot stop selling them once expired."), style = Theme.text(12), color = Theme.inkSoft)
        }
        val sameLot = StockRules.lotsWithCode(product.lots, model.lotCode)
        if (sameLot.isNotEmpty()) {
            NoticeCard(
                tone = NoticeCard.Tone.info,
                message = LF(
                    "Lot %@ is already on the shelf (%@ left). This delivery is kept as its own entry under the same number.",
                    model.lotCode.trimSpaces(),
                    StockText.count(sameLot.sumOf { it.quantityOnHand }),
                ),
                icon = "info",
            )
        }
    }

    ModalSection(title = L("Who delivered it"), subtitle = L("Kept with the movement, so the history says where each delivery came from.")) {
        PMTextField(L("Supplier"), text = stateOf({ model.supplier }, { model.supplier = it }), optional = true, caps = true)
        PMTextField(L("Supplier invoice"), text = stateOf({ model.invoiceRef }, { model.invoiceRef = it }), prompt = L("Its number or folio"), optional = true, caps = true)
        PMTextField(
            L("Note"),
            text = stateOf({ model.note }, { model.note = it }),
            key = "note",
            form = form,
            prompt = L("Anything worth remembering about this delivery"),
            optional = true,
            multiline = true,
            caps = true,
            lines = 2..8,
        )
    }
}

@Composable
private fun ReviewStep(model: ReceiveModel) {
    val product = model.product ?: return
    val figures = model.figures ?: return
    val byPack = model.byPack
    val unitsPerPack = model.unitsPerPack
    FlowLayout(spacing = 8.dp) {
        Chip(text = product.name, tone = Chip.Tone.muted, icon = "package")
        model.supplier.nonEmpty?.let { Chip(text = it, tone = Chip.Tone.muted) }
    }
    model.saveError?.let { error ->
        NoticeCard(tone = NoticeCard.Tone.problem, icon = if (error.connection) "wifi-off" else "triangle-alert", content = {
            Text(error.message, fontWeight = FontWeight.Bold)
            if (error.gone) {
                PMButton(L("Choose another product"), variant = ButtonVariant.link, size = ButtonSize.modal) {
                    model.saveError = null
                    model.fixed = false
                    model.product = null
                    model.step = ReceiveStep.product
                }
            }
        })
    }
    StockProductCard(row = product, after = figures.onHandAfter, afterLabel = L("after this delivery"), today = model.today)
    ListCard(style = ListCardStyle.box) {
        StockReviewRow(label = L("Arrives")) {
            Text("+" + StockText.units(figures.units), fontWeight = FontWeight.Bold)
            if (byPack) {
                Text(
                    LF("%@ × %d = %@", model.packsLabel(figures.units / max(1, unitsPerPack)), unitsPerPack, StockText.units(figures.units)),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
            }
        }
        RowDivider()
        StockReviewRow(label = L("On hand")) {
            Text(LF("%@ → %@", StockText.onHand(product.quantityOnHand), StockText.onHand(figures.onHandAfter)))
        }
        RowDivider()
        StockReviewRow(label = L("Cost")) {
            val unit = figures.unitCost
            if (unit != null && unit > 0) {
                Text(Money.format(figures.totalCost ?: 0), fontWeight = FontWeight.Bold)
                Text(LF("%@ per unit", Money.format(unit)), style = Theme.text(12), color = Theme.inkSoft)
            } else {
                Text(model.costLine(product, figures))
            }
        }
        val current = product.unitCostAmount
        val after = figures.costAfter
        if (figures.unitCost != null && current != null && after != null && after != current && product.quantityOnHand > 0) {
            RowDivider()
            StockReviewRow(label = L("Average cost")) {
                Text(LF("%@ → %@", Money.format(current), Money.format(after)))
                Text(L("What each unit on the shelf costs, on average, after this delivery."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        RowDivider()
        StockReviewRow(label = L("Lot and expiry")) {
            Text(model.lotLine)
            val days = model.expiresIn
            if (days != null && days < 0) {
                Text(L("Already expired: it will not be sold."), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
        }
        model.receiptNote.nonEmpty?.let { line ->
            RowDivider()
            StockReviewRow(label = L("Delivery")) { Text(line) }
        }
    }
    if (product.tracked == false) {
        NoticeCard(tone = NoticeCard.Tone.info, message = L("From now on, the stock of this product is counted."), icon = "info")
    }
    if (model.severalLocations) {
        CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Glyph("map-pin", 14.dp, modifier = Modifier.padding(top = 1.dp))
                Text(L("Stock is counted for the whole business, not for each location."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}
