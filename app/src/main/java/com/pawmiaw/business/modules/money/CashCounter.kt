@file:OptIn(ExperimentalFoundationApi::class)

package com.pawmiaw.business.money

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

//
//  CashCounter.kt (iOS CashCounter.swift)
//
//  The counter (web: store/store-pos.tsx, store-customer.tsx, payment-step.tsx):
//  find what is bought, say who buys it (a walk-in or a client and their pet),
//  pick the client's open bills to pay with it, take the money however it
//  comes and hand over the receipt. Stock, tax and the folio are the server's.
//  A code is scanned with the camera, typed, or sent by a USB/Bluetooth
//  scanner into the search field (Return); a card's pencil fixes its name,
//  price, tax, barcode and count without leaving the counter. Another screen
//  can send a sale here already filled in (`StorePrefill`): a Pharmacy
//  hand-over, a bill to charge with products, a client.
//

data class CashCartLine(
    val product: CashProduct,
    val quantity: Int,
    /** A price typed at the counter; null keeps the catalog's. */
    val unitAmount: Int? = null,
    val prescriptionItemId: String? = null,
    val reason: String = "",
) {
    val id: String get() = product.catalogItemId
    val unit: Int get() = unitAmount ?: product.unitAmount
    /** Priced below the catalog's: it needs its why. */
    val lowered: Boolean get() = unit < product.unitAmount
}

/** Swift `Double.rounded()`: to the nearest, halves away from zero. */
private fun swiftRounded(value: Double): Int = (if (value >= 0) floor(value + 0.5) else ceil(value - 0.5)).toInt()

/**
 * The counter's arithmetic (the server's `store-rules.ts`): the ticket discount
 * shared over the lines by weight, tax inside or on top of the prices.
 */
object CashSaleMath {
    data class Totals(val lines: List<Int>, val gross: Int, val discount: Int, val tax: Int, val total: Int)

    fun share(amount: Int, weights: List<Int>): List<Int> {
        val total = weights.sumOf { max(0, it) }
        if (amount <= 0 || total <= 0) return weights.map { 0 }
        val exact = weights.map { max(0, it).toDouble() * amount.toDouble() / total.toDouble() }
        val floors = exact.map { floor(it).toInt() }.toMutableList()
        var left = amount - floors.sum()
        val order = exact.indices.sortedByDescending { exact[it] - floors[it].toDouble() }
        for (index in order) {
            if (left <= 0) break
            floors[index] += 1
            left -= 1
        }
        return floors
    }

    fun totals(lines: List<CashCartLine>, ticketDiscount: Int, pricesIncludeTax: Boolean): Totals {
        val gross = lines.map { max(0, it.quantity) * max(0, it.unit) }
        val pool = gross.sum()
        val shares = share(min(pool, max(0, ticketDiscount)), gross)
        val lineTotals = mutableListOf<Int>()
        var tax = 0
        var discount = 0
        var total = 0
        lines.forEachIndexed { index, line ->
            val after = gross[index] - shares[index]
            val rate = line.product.rate
            val lineTax: Int
            val lineTotal: Int
            if (pricesIncludeTax) {
                lineTax = if (rate > 0 && after > 0) swiftRounded(after.toDouble() * rate / (10_000 + rate).toDouble()) else 0
                lineTotal = after
            } else {
                lineTax = if (rate > 0) swiftRounded(after.toDouble() * rate / 10_000.0) else 0
                lineTotal = after + lineTax
            }
            lineTotals.add(lineTotal)
            tax += lineTax
            discount += shares[index]
            total += lineTotal
        }
        return Totals(lines = lineTotals, gross = pool, discount = discount, tax = tax, total = total)
    }
}

/** What a finished charge leaves: the sale's receipt and each bill settled with it. */
data class CashChargeDone(
    val sale: CashSale?,
    val checkout: CashCheckoutResult?,
    val stockMoved: Boolean,
    /** Where the receipt went, and the client's own email (to send it now or again). */
    val emailedTo: String? = null,
    val clientEmail: String? = null,
) {
    val id: UUID = UUID.randomUUID()
}

/** An error the counter already showed (under its field): the sheet stays quiet. */
class CashHandled : Exception()

/** Swift `try?`: the value, or null when it throws (a cancel still cancels). */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

/** The counter's words that other pieces share (Swift `CashCounter`'s statics). */
object CashCounter {
    fun outOfStock(product: CashProduct): String =
        if ((product.stock?.expiredUnits ?: 0) > 0) LF("%@: what is on the shelf has expired", product.name) else LF("%@ is out of stock", product.name)

    fun onlyLeft(count: Int, name: String): String =
        if (count == 1) LF("Only 1 left of %@", name) else LF("Only %d left of %@", count, name)
}

/** The counter's `@State`, as one holder the parts below read and change. */
private class CashCounterModel {
    val query = mutableStateOf("")
    var type by mutableStateOf("product")
    var products by mutableStateOf(listOf<CashProduct>())
    var otherMatches by mutableIntStateOf(0)
    var loadingProducts by mutableStateOf(true)
    var productsError by mutableStateOf<String?>(null)
    var cart by mutableStateOf(listOf<CashCartLine>())
    var client by mutableStateOf<PickedClient?>(null)
    var petId by mutableStateOf<String?>(null)
    val walkInName = mutableStateOf("")
    var account by mutableStateOf<CashAccount?>(null)
    var billIds by mutableStateOf(setOf<String>())
    val ticketDiscount = mutableStateOf<Int?>(null)
    val discountReason = mutableStateOf("")
    val form = FormState()
    var picking by mutableStateOf(false)
    var paying by mutableStateOf(false)
    var clearing by mutableStateOf(false)
    var pricing by mutableStateOf<CashCartLine?>(null)
    var done by mutableStateOf<CashChargeDone?>(null)
    /** The receipt waits for the payment sheet to leave before it shows. */
    var pendingDone: CashChargeDone? = null
    var scrollToSale by mutableIntStateOf(0)
    var resets by mutableIntStateOf(0)
    var prefillApplied = false
    /** Bills sent here to charge: said so when one was paid meanwhile. */
    var pendingBills: List<String>? = null
    var lookingUp = false
    var ambiguous by mutableStateOf<CashAmbiguousCode?>(null)
    var editing by mutableStateOf<CashProduct?>(null)
    var showCatalog by mutableStateOf(false)
    var catalogItemId by mutableStateOf<String?>(null)
    /** A prefilled sale was charged: closing its receipt goes back. */
    var chargedPrefill = false
    /** The charge last sent and its request id: sent again unchanged, it keeps the id so it is never charged twice. */
    var lastCharge: Pair<String, String>? = null

    // The view's `let`s, refreshed on every composition.
    var settings: CashStoreSettings? = null
    lateinit var device: CashDeviceRegister
    var prefill: StorePrefill? = null
    var onCharged: (() -> Unit)? = null

    private val workspace: Workspace get() = Workspace.shared
    val currency: String get() = settings?.currency ?: workspace.currency
    val canOverridePrice: Boolean get() = workspace.can(Permission.salesOverridePrice)
    val canEditCatalog: Boolean get() = workspace.can(Permission.catalogEdit)
    val canDiscount: Boolean get() = workspace.can(Permission.billingApplyDiscount)
    val canCollectBills: Boolean get() = workspace.can(Permission.billingRecordPayment)
    val canDispenseControlled: Boolean get() = workspace.can(Permission.pharmacyDispenseControlled)
    val typeKey: String get() = "pawmiaw.store.sell-type:${workspace.current?.clinicId ?: ""}"

    val openBills: List<CashAccount.Open> get() = if (canCollectBills) account?.open ?: emptyList() else emptyList()
    val selectedBills: List<CashAccount.Open> get() = openBills.filter { it.id in billIds }
    val billsTotal: Int get() = selectedBills.sumOf { it.balanceDueAmount }
    val credit: Int get() = if (client != null) account?.credit ?: 0 else 0
    val totals: CashSaleMath.Totals get() = CashSaleMath.totals(cart, ticketDiscount.value ?: 0, settings?.pricesIncludeTax ?: false)
    val grandTotal: Int get() = totals.total + billsTotal
    val ticketNeedsReason: Boolean get() = cashDiscountNeedsReason(min(ticketDiscount.value ?: 0, totals.gross), totals.gross)
    val saleReason: String? get() = if (ticketNeedsReason) discountReason.value.nonEmpty else null
    val needsPatient: CashCartLine? get() = cart.firstOrNull { it.product.needsPrescription && (client == null || petId == null) }
    val needsPrescription: CashCartLine? get() = if (needsPatient == null) cart.firstOrNull { it.product.needsPrescription && it.prescriptionItemId == null } else null
    val needsVet: CashCartLine? get() = if (canDispenseControlled) null else cart.firstOrNull { it.product.controlSchedule != null }
    val needsPrice: CashCartLine? get() = cart.firstOrNull { it.unit <= 0 }
    val blocked: Boolean
        get() = (cart.isEmpty() && selectedBills.isEmpty()) || needsPatient != null || needsPrescription != null || needsVet != null || needsPrice != null

    fun updateLine(index: Int, change: (CashCartLine) -> CashCartLine) {
        if (index !in cart.indices) return
        cart = cart.toMutableList().also { it[index] = change(it[index]) }
    }

    fun chooseType(value: String) {
        type = value
        AppContext.defaults.edit().putString(typeKey, value).apply()
    }

    // MARK: Actions

    suspend fun loadProducts() {
        loadingProducts = true
        try {
            val listed = if (type == "all") null else type
            try {
                val found: List<CashProduct> = API.get("/provider-store/products", mapOf("q" to query.value.nonEmpty, "type" to listed))
                products = if (listed != null) found.filter { it.itemType == listed } else found
                productsError = null
                // Fresh catalog figures for what is already in the sale (a price typed stays as typed).
                cart = cart.map { line -> found.firstOrNull { it.catalogItemId == line.id }?.let { line.copy(product = it) } ?: line }
                otherMatches = 0
                if (products.isEmpty() && listed != null && query.value.nonEmpty != null) {
                    val other = if (listed == "product") "service" else "product"
                    val more: List<CashProduct>? = attempt { API.get("/provider-store/products", mapOf("q" to query.value.nonEmpty, "type" to other)) }
                    otherMatches = more?.count { it.itemType == other } ?: 0
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                productsError = errorText(error)
            }
        } finally {
            loadingProducts = false
        }
    }

    suspend fun loadAccount() {
        val client = client
        if (client == null || workspace.moneyLevel < MoneyLevel.charging) {
            account = null
            return
        }
        account = attempt { API.get<CashAccount>("/provider-finance/clients/${client.id}/account") }
        // Say so when a bill sent here was paid meanwhile.
        val pending = pendingBills
        val loaded = account
        if (pending != null && loaded != null) {
            val gone = pending.filter { id -> loaded.open.none { it.id == id } }
            if (gone.isNotEmpty()) Toast.show(if (gone.size == 1) L("That bill is already paid or closed.") else L("Some of those bills are already paid or closed."))
            pendingBills = null
        }
    }

    /**
     * A code scanned (the camera hands it over) or typed and Return: looked up in every
     * type, with its other spellings; two products with it ask which; typed by hand,
     * Return adds the only match of the type listed.
     */
    suspend fun handleScan(scanned: String?, format: String?) {
        val code = (scanned ?: query.value).nonEmpty ?: return
        if (lookingUp) return
        lookingUp = true
        try {
            if (scanned != null) query.value = code
            val found = attempt {
                Barcode.findByCode<CashProduct>(
                    code, format,
                    barcode = { it.barcode }, sku = { it.sku }, key = { it.catalogItemId },
                    search = { text -> API.get("/provider-store/products", mapOf("q" to text)) },
                )
            } ?: return
            if (found.matches.size > 1) {
                ambiguous = CashAmbiguousCode(code = code, products = found.matches)
                return
            }
            val listed = if (type == "all") found.found else found.found.filter { it.itemType == type }
            val match = found.exact ?: listed.singleOrNull()
            if (match != null) {
                add(match)
                query.value = ""
            }
        } finally {
            lookingUp = false
        }
    }

    // MARK: A sale sent here filled in

    /**
     * The client and pet chosen, a hand-over's medicines against their prescription
     * lines, the bills sent to charge selected — once. The sale is still what moves
     * stock, money and the log.
     */
    suspend fun applyPrefill() {
        val prefill = prefill ?: return
        if (prefillApplied) return
        prefillApplied = true
        var clientId = prefill.clientId
        var petWanted = prefill.petId
        var lines = prefill.lines
        val rx = prefill.prescriptionId
        if (lines.isEmpty() && rx != null) {
            val handOver = attempt { CashHandOver.load(rx) }
            if (handOver != null) {
                lines = handOver.lines
                clientId = clientId ?: handOver.clientId
                petWanted = petWanted ?: handOver.petId
            }
        }
        if (clientId != null) {
            val loaded = attempt { API.get<PickedClient>("/provider-clients/$clientId") }
            if (loaded == null) {
                Toast.error(L("That client could not be found."))
                return
            }
            val pets = loaded.activePets
            client = loaded
            if (petWanted != null && pets.any { it.id == petWanted }) {
                petId = petWanted
            } else if (pets.size == 1) {
                petId = pets[0].id
            }
        }
        if (lines.isNotEmpty()) {
            addLines(lines)
        } else {
            var bills = prefill.invoiceId?.let { listOf(it) } ?: emptyList()
            val visit = prefill.appointmentId
            if (bills.isEmpty() && visit != null) {
                val page = attempt { API.get<Page<CashInvoice>>("/provider-invoices", mapOf("providerAppointmentId" to visit, "limit" to "20", "offset" to "0")) }
                bills = (page?.items ?: emptyList()).filter { CashBillRules.isCollectible(it) && CashBillRules.kind(it) != "deposit" }.map { it.id }
            }
            if (bills.isNotEmpty()) {
                billIds = bills.toSet()
                pendingBills = bills
                if (account != null) loadAccount()
            }
        }
    }

    /** Each medicine of a hand-over in the sale, against its prescription line, as much as the shelf holds. */
    suspend fun addLines(lines: List<StorePrefill.Line>) {
        val ids = lines.map { it.catalogItemId }.distinct()
        val found = attempt { API.get<List<CashProduct>>("/provider-store/products", mapOf("ids" to ids.joinToString(","))) }
        if (found == null) {
            Toast.error(L("The medicines of that prescription could not be loaded. Add them from the list."))
            return
        }
        val next = mutableListOf<CashCartLine>()
        for (line in lines) {
            val product = found.firstOrNull { it.catalogItemId == line.catalogItemId }
            if (product == null) {
                Toast.error(L("A medicine of that prescription is no longer sold here."))
                continue
            }
            var quantity = max(1, ceil(line.quantity).toInt())
            val available = product.stock?.available
            if (available != null && product.stock?.allowBackorder != true) {
                if (available <= 0) {
                    Toast.error(CashCounter.outOfStock(product))
                    continue
                }
                if (quantity > available) {
                    Toast.show(CashCounter.onlyLeft(available, product.name))
                    quantity = available
                }
            }
            val index = next.indexOfFirst { it.id == product.catalogItemId }
            if (index >= 0) {
                next[index] = next[index].copy(quantity = next[index].quantity + quantity)
            } else {
                next.add(CashCartLine(product = product, quantity = quantity, unitAmount = null, prescriptionItemId = line.prescriptionItemId))
            }
        }
        billIds = emptySet()
        cart = next
    }

    fun add(product: CashProduct) {
        if (product.unitAmount <= 0 && !canOverridePrice) {
            Toast.error(LF("%@ has no price — put one in the Catalog before selling it.", product.name))
            return
        }
        val inCart = cart.firstOrNull { it.id == product.id }?.quantity ?: 0
        val available = product.stock?.available
        if (available != null && product.stock?.allowBackorder != true && inCart + 1 > available) {
            Toast.error(if (available <= 0) CashCounter.outOfStock(product) else CashCounter.onlyLeft(available, product.name))
            return
        }
        val index = cart.indexOfFirst { it.id == product.id }
        if (index >= 0) {
            updateLine(index) { it.copy(quantity = it.quantity + 1) }
        } else {
            cart = cart + CashCartLine(product = product, quantity = 1, unitAmount = null)
            if (product.unitAmount <= 0) pricing = cart.last()
        }
    }

    fun setQuantity(index: Int, quantity: Int) {
        if (index !in cart.indices) return
        if (quantity <= 0) {
            cart = cart.toMutableList().also { it.removeAt(index) }
            return
        }
        val line = cart[index]
        val available = line.product.stock?.available
        if (available != null && line.product.stock?.allowBackorder != true && quantity > available) {
            Toast.error(CashCounter.onlyLeft(available, line.product.name))
            return
        }
        updateLine(index) { it.copy(quantity = quantity) }
    }

    /** Every missing reason marked, the first scrolled to (docs/money-access-levels.md). */
    fun checkReasons(): Boolean {
        val rules = mutableListOf<Pair<String, String?>>()
        for (line in cart) {
            if (!line.lowered) continue
            rules.add("line-${line.id}" to (if (line.reason.nonEmpty == null && saleReason == null) CashReasonKind.priceLowered.required else null))
        }
        rules.add("discount" to (if (ticketNeedsReason && saleReason == null) CashReasonKind.discount.required else null))
        return form.check(rules)
    }

    fun reset() {
        cart = emptyList()
        client = null
        petId = null
        billIds = emptySet()
        walkInName.value = ""
        ticketDiscount.value = null
        discountReason.value = ""
        form.errors = emptyMap()
        query.value = ""
        resets += 1
    }

    suspend fun charge(payments: List<Map<String, Any?>>, delivery: Map<String, Any?>) {
        val lines: List<Map<String, Any?>> = cart.map { line ->
            val entry = linkedMapOf<String, Any?>("catalogItemId" to line.product.catalogItemId, "quantity" to line.quantity, "unitAmount" to line.unitAmount)
            line.prescriptionItemId?.let { entry["prescriptionItemId"] = it }
            if (line.lowered) line.reason.nonEmpty?.let { entry["adjustmentReason"] = it }
            entry
        }
        val key = "${client?.id ?: ""}|${petId ?: ""}|${walkInName.value}|${selectedBills.map { it.id }}|${cart.map { "${it.id}:${it.quantity}:${it.unit}" }}|${ticketDiscount.value ?: 0}|${payments.size}|${device.sessionId ?: ""}"
        val last = lastCharge
        val requestId = if (last != null && last.first == key) last.second else cashRequestId()
        lastCharge = key to requestId
        val stockMoved = cart.any { it.product.stock != null }
        val usesCredit = payments.any { (it["method"] as? String) == "credit" }
        val discount = ticketDiscount.value ?: 0
        try {
            val buyer = client
            if (buyer != null && (selectedBills.isNotEmpty() || usesCredit)) {
                val body = linkedMapOf<String, Any?>(
                    "clientRequestId" to requestId,
                    "providerClientId" to buyer.id,
                    "providerClientPetId" to petId,
                    "invoiceIds" to selectedBills.map { it.id },
                    "lines" to lines,
                    "ticketDiscount" to (if (discount > 0) ticketDiscount.value else null),
                    "discountReason" to saleReason,
                    "payments" to payments,
                    "registerSessionId" to device.sessionId,
                    "note" to null,
                )
                body.putAll(delivery)
                val result: CashCheckoutResult = API.post("/provider-store/checkout", body)
                val sale: CashSale? = result.saleId?.let { saleId -> attempt { API.get<CashSale>("/provider-store/sales/$saleId") } }
                pendingDone = CashChargeDone(sale = sale, checkout = result, stockMoved = stockMoved, emailedTo = result.receipt?.emailedTo, clientEmail = buyer.ownerEmail)
            } else {
                val body = linkedMapOf<String, Any?>(
                    "clientRequestId" to requestId,
                    "registerSessionId" to device.sessionId,
                    "providerClientId" to buyer?.id,
                    "providerClientPetId" to petId,
                    "customerName" to (if (buyer == null) walkInName.value.nonEmpty else null),
                    "lines" to lines,
                    "ticketDiscount" to (if (discount > 0) ticketDiscount.value else null),
                    "discountReason" to saleReason,
                    "payments" to payments,
                    "note" to null,
                )
                body.putAll(delivery)
                if (buyer == null) body["saveReceiptEmail"] = false
                val sale: CashSale = API.post("/provider-store/sales", body)
                pendingDone = CashChargeDone(sale = sale, checkout = null, stockMoved = stockMoved, emailedTo = delivery["receiptEmail"] as? String, clientEmail = buyer?.ownerEmail)
            }
            lastCharge = null
            if (prefill != null && onCharged != null) chargedPrefill = true
            reset()
            CashDeviceRegister.changed.tryEmit(Unit)
            announceDataChanged()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (error is APIError) lastCharge = null
            if (cashAsksReason(error)) {
                // iOS sets `paying = false` here; the sheet's own dismiss() (CashHandled) closes it, animated.
                val message = errorText(error)
                val field = if (ticketNeedsReason) "discount" else (cart.firstOrNull { it.lowered }?.let { "line-${it.id}" } ?: "discount")
                form.check(listOf(field to message))
                throw CashHandled()
            }
            // Refusals the counter can point at; the server's sentence is already in the reader's language.
            when (cashErrorCode(error)) {
                "pet_archived" -> {
                    petId = null
                    Toast.error(error, L("This pet is archived: restore it to sell for it."))
                    throw CashHandled()
                }
                "pack_base_archived" -> {
                    Toast.error(error, L("A pack in the sale draws on a product that is archived: take it out of the sale."))
                    throw CashHandled()
                }
                "location_not_assigned" -> {
                    Toast.error(error, L("You do not work at this register’s location."))
                    throw CashHandled()
                }
                else -> throw error
            }
        }
    }
}

@Composable
fun CashCounter(
    settings: CashStoreSettings,
    device: CashDeviceRegister,
    openRegister: () -> Unit,
    /** A sale another screen sends here already filled in; applied once. */
    prefill: StorePrefill? = null,
    /** After a prefilled sale is charged and its receipt closed. */
    onCharged: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val m = remember { CashCounterModel() }
    m.settings = settings
    m.device = device
    m.prefill = prefill
    m.onCharged = onCharged
    val nav = LocalNavigator.current
    val regular = LocalSizeClass.current == SizeClass.regular
    val saleRequester = remember { BringIntoViewRequester() }

    Box(modifier) {
        if (regular) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { ProductsColumn(m) }
                Box(Modifier.width(400.dp)) { SaleColumn(m, openRegister, saleRequester) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // On a phone the sale is under the list: once it has something, the way down to it is at the top.
                if (m.cart.isNotEmpty() || m.selectedBills.isNotEmpty()) JumpBar(m)
                ProductsColumn(m)
                SaleColumn(m, openRegister, saleRequester)
            }
        }
    }

    LaunchedEffect(m.scrollToSale) { if (m.scrollToSale > 0) saleRequester.bringIntoView() }
    // `.onAppear`: the list kind this business last used here.
    LaunchedEffect(Unit) { m.type = AppContext.defaults.getString(m.typeKey, null) ?: "product" }
    LaunchedEffect(m.query.value, m.type) {
        delay(180)
        m.loadProducts()
    }
    LaunchedEffect(m.client?.id) { m.loadAccount() }
    LaunchedEffect(Unit) { m.applyPrefill() }
    // "Add … in the Catalog": the Catalog page; from a quick edit, the Catalog opened on that item.
    LaunchedEffect(m.showCatalog) {
        if (m.showCatalog && m.catalogItemId == null) {
            m.showCatalog = false
            nav.push(ProviderRoute.section(ProviderSection.catalog))
        }
    }

    m.ambiguous?.let { found ->
        AppSheet(onDismissRequest = { m.ambiguous = null }) {
            CashAmbiguousCodeSheet(found = found, onChoose = { product ->
                m.add(product)
                m.query.value = ""
            })
        }
    }
    m.editing?.let { product ->
        AppSheet(onDismissRequest = {
            m.editing = null
            // The Catalog opens once the quick edit has left.
            if (m.catalogItemId != null && !m.showCatalog) m.showCatalog = true
        }) {
            CashQuickEditSheet(product = product, settings = settings, onSaved = { saved ->
                m.cart = m.cart.map { if (it.id == saved.catalogItemId) it.copy(product = CashQuickEdit.apply(saved, it.product)) else it }
                val index = m.products.indexOfFirst { it.catalogItemId == saved.catalogItemId }
                if (index >= 0) m.products = m.products.toMutableList().also { it[index] = CashQuickEdit.apply(saved, it[index]) }
            }, onOpenCatalog = { m.catalogItemId = product.catalogItemId })
        }
    }
    if (m.showCatalog && m.catalogItemId != null) {
        // iOS pushes CatalogScreen(openItemId:); a route can't carry the item, so the Catalog opens as a page in a sheet.
        AppSheet(onDismissRequest = { m.showCatalog = false; m.catalogItemId = null }) {
            Box(Modifier.sheetPresentation(setOf(PresentationDetent.large))) { CatalogScreen(openItemId = m.catalogItemId) }
        }
    }
    if (m.picking) {
        AppSheet(onDismissRequest = { m.picking = false }) {
            CashWhoIsBuyingSheet(client = m.client, petId = m.petId) { chosen, pet ->
                if (chosen.id != m.client?.id) m.billIds = emptySet()
                m.client = chosen
                m.petId = pet
            }
        }
    }
    if (m.paying) {
        AppSheet(onDismissRequest = {
            m.paying = false
            m.pendingDone?.let { pending ->
                m.pendingDone = null
                m.done = pending
            }
        }) {
            CashCounterPaySheet(
                total = m.grandTotal, credit = m.credit, client = m.client, petId = m.petId, device = device,
                automaticReceipts = settings.emailReceipts != false,
            ) { payments, delivery -> m.charge(payments, delivery) }
        }
    }
    m.pricing?.let { line ->
        AppSheet(onDismissRequest = { m.pricing = null }) {
            CashLinePriceSheet(line = line) { price ->
                val index = m.cart.indexOfFirst { it.id == line.id }
                if (index >= 0) m.updateLine(index) { it.copy(unitAmount = if (price == it.product.unitAmount) null else price) }
            }
        }
    }
    m.done?.let { result ->
        AppSheet(onDismissRequest = {
            m.done = null
            // A sale another screen sent here: once charged and its receipt closed, back to that screen.
            if (m.chargedPrefill) {
                m.chargedPrefill = false
                m.onCharged?.invoke()
            }
        }) { CashChargeDoneSheet(done = result, settings = settings) }
    }
    if (m.clearing) {
        AppSheet(onDismissRequest = { m.clearing = false }) {
            ConfirmSheet(
                title = L("Clear this sale?"),
                message = L("Everything in it comes off, and it goes back to a walk-in customer. Nothing has been charged."),
                confirmTitle = L("Clear the sale"), destructive = true, cancelTitle = L("Keep it"),
            ) { _ -> m.reset() }
        }
    }
}

// MARK: Products

/** An adaptive grid inside a scrolling page (SwiftUI `LazyVGrid(.adaptive(minimum:))`). */
@Composable
private fun AdaptiveGrid(count: Int, minimum: Dp, spacing: Dp, cell: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (start in 0 until count step columns) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    for (index in start until start + columns) {
                        Box(Modifier.weight(1f)) { if (index < count) cell(index) }
                    }
                }
            }
        }
    }
}

/**
 * The search (a scanner types here and presses Return; the scan mark opens the camera),
 * what the list shows, and the cards (web: store-product-grid.tsx).
 */
@Composable
private fun ProductsColumn(m: CashCounterModel) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BarcodeSearchField(
                prompt = L("Search or scan a product"), text = m.query,
                onSubmit = { scope.launch { m.handleScan(null, null) } },
                onScan = { scan -> scope.launch { m.handleScan(scan.code, scan.format) } },
            )
            Segmented(
                options = listOf(
                    Segmented.Option("product", L("Products"), icon = "package", tone = ChipTone.hue(Hue.peach)),
                    Segmented.Option("service", L("Services"), icon = "briefcase-medical", tone = ChipTone.hue(Hue.sky)),
                    Segmented.Option("all", L("All@kind"), icon = "layout-grid", tone = ChipTone.page),
                ),
                selection = stateOf({ m.type }, { m.chooseType(it) }),
                look = Segmented.Look.raised, label = L("What to list"),
            )
        }
        val error = m.productsError
        when {
            m.loadingProducts && m.products.isEmpty() -> LoadingState()
            error != null && m.products.isEmpty() -> ErrorState(message = error) { m.loadProducts() }
            m.products.isEmpty() -> EmptyProducts(m)
            else -> {
                val products = m.products
                AdaptiveGrid(count = products.size, minimum = 256.dp, spacing = 10.dp) { index ->
                    val product = products[index]
                    CashProductCard(
                        product = product, inCart = m.cart.firstOrNull { it.id == product.id }?.quantity ?: 0, canEdit = m.canEditCatalog,
                        onAdd = { m.add(product) }, onEdit = { m.editing = product },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyProducts(m: CashCounterModel) {
    val type = m.type
    val other: String? = if (type == "product") "service" else if (type == "service") "product" else null
    val switchTitle: String? = other?.let { kind ->
        if (m.otherMatches <= 0) return@let null
        if (m.query.value.nonEmpty != null) {
            return@let if (kind == "service") LP(m.otherMatches, "%d service matches", "%d services match") else LP(m.otherMatches, "%d product matches", "%d products match")
        }
        if (kind == "service") L("See services") else L("See products")
    }
    val q = m.query.value.nonEmpty
    if (q != null) {
        EmptyState(
            systemImage = "search",
            title = if (type == "product") LF("No product matches “%@”", q) else if (type == "service") LF("No service matches “%@”", q) else LF("Nothing matches “%@”", q),
            message = if (switchTitle != null) (if (other == "service") L("It is listed under Services.") else L("It is listed under Products.")) else L("Check the spelling, or scan the barcode."),
            actionTitle = switchTitle, action = if (switchTitle != null) ({ if (other != null) m.type = other }) else null,
            accent = Theme.mango, panel = true,
        )
    } else {
        val manages = Workspace.shared.canAny(Permission.catalogCreate, Permission.catalogEdit)
        EmptyState(
            systemImage = if (type == "service") "briefcase-medical" else "package",
            title = if (type == "product") L("No products to sell yet") else if (type == "service") L("No services to sell yet") else L("Your catalog is empty"),
            message = if (manages) {
                if (type == "product") L("Add what you sell at the counter — food, medicine, accessories — in the Catalog, and it shows up here.") else L("Add products and services in the Catalog to sell them here.")
            } else L("Whoever manages the Catalog adds what is sold here."),
            actionTitle = if (manages) (if (type == "service") L("Add services in the Catalog") else L("Add products in the Catalog")) else switchTitle,
            action = if (manages) ({ m.showCatalog = true }) else if (switchTitle != null) ({ if (other != null) m.type = other }) else null,
            accent = Theme.mango, panel = true,
        )
    }
}

/** The way down to the sale on a phone (web: the sticky "N items · total ↓" bar). */
@Composable
private fun JumpBar(m: CashCounterModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .cardShadow(CapsuleShape)
            .background(Theme.surface, CapsuleShape)
            .border(2.dp, Theme.blue, CapsuleShape)
            .plainButton(label = L("See the sale")) { m.scrollToSale += 1 }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph("shopping-cart", 16.dp, tint = Theme.blue)
        Text(LP(m.cart.sumOf { it.quantity }, "%d item", "%d items"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(cashMoney(m.grandTotal, m.currency), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
        Glyph("arrow-down", 16.dp, tint = Theme.inkStrong)
    }
}

// MARK: The sale

@Composable
private fun SaleEyebrow(text: String) {
    Text(text.uppercase(), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.5), color = Theme.inkSoft)
}

/**
 * The sale (web: the counter's aside): who buys, what they already owe,
 * the cart, then the totals and Charge on a pale blue band.
 */
@Composable
private fun SaleColumn(m: CashCounterModel, openRegister: () -> Unit, requester: BringIntoViewRequester) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    FormScroll(form = m.form) {
        Column(
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .cardShadow(shape)
                .clip(shape)
                .background(Theme.surface)
                .border(2.dp, Theme.borderSoft, shape),
        ) {
            Box(Modifier.padding(16.dp)) { CustomerSection(m) }
            if (m.openBills.isNotEmpty()) {
                RowDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaleEyebrow(L("Also owes"))
                    m.openBills.forEach { bill -> key(bill.id) { BillRow(m, bill) } }
                }
            }
            RowDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.cart.isEmpty()) {
                    EmptyState(systemImage = "shopping-cart", title = if (m.selectedBills.isEmpty()) L("Tap a product to add it.") else L("Anything else from the counter? Tap a product."), accent = Theme.mango)
                } else {
                    m.cart.forEachIndexed { index, line -> key(line.id) { CartRow(m, index, line) } }
                }
            }
            RowDivider()
            Column(
                Modifier.fillMaxWidth().background(Theme.wash(Theme.aqua, 10.0)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { TotalsSection(m, openRegister) }
        }
    }
}

/** Who is buying (web `StoreCustomer`): a walk-in with an optional name, or a client and which pet. */
@Composable
private fun CustomerSection(m: CashCounterModel) {
    val client = m.client
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(if (client != null) Theme.wash(Theme.mango, 35.0) else Theme.surfaceMuted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val photo = client?.photoUrl
                if (photo != null && APIConfig.assetURL(photo) != null) {
                    Avatar(name = client.ownerName, imageURL = photo, size = 40.dp)
                } else {
                    Glyph("user-round", 20.dp, tint = Theme.inkStrong)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SaleEyebrow(L("Customer"))
                Text(client?.ownerName ?: L("Walk-in customer"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
            }
            Spacer(Modifier.width(4.dp))
            if (Workspace.shared.can(Permission.clientsView)) {
                if (client == null) {
                    PMButton(L("Choose client"), systemImage = "user-round-search", variant = ButtonVariant.outline) { m.picking = true }
                } else {
                    IconCircleButton(systemImage = "user-round-search", label = L("Change client"), tint = Theme.blue) { m.picking = true }
                }
            }
            if (client != null) {
                IconCircleButton(systemImage = "x", label = L("Back to walk-in")) {
                    m.client = null
                    m.petId = null
                    m.billIds = emptySet()
                }
            }
        }
        if (client != null) {
            val pets = client.activePets
            if (pets.isNotEmpty()) {
                FlowLayout(spacing = 8.dp) {
                    pets.forEach { pet -> PetPill(m, pet) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (client.householdId != null) {
                    TagChip(text = L("Family linked"), icon = "link-2", linked = true)
                    Text(L("Uses PawMiaw: the receipt goes to their app, and medicines to the pet’s cabinet."), style = Theme.text(12), color = Theme.inkSoft)
                } else {
                    TagChip(text = L("Clinic-only"), icon = "building-2")
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Theme.inputFill, CapsuleShape)
                    .border(1.dp, Theme.borderSoft, CapsuleShape)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                PlainTextField(m.walkInName, L("Name on the receipt (optional)"), Modifier.fillMaxWidth(), style = Theme.bodyFont.copy(color = Theme.inkStrong))
            }
        }
    }
}

@Composable
private fun PetPill(m: CashCounterModel, pet: PickedPet) {
    val on = m.petId == pet.id
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .background(if (on) Theme.wash(Theme.aqua, 22.0) else Color.Transparent, CapsuleShape)
            .border(2.dp, if (on) Theme.blue else Theme.borderSoft, CapsuleShape)
            .clip(CapsuleShape)
            .plainButton { m.petId = if (on) null else pet.id }
            .semantics { selected = on }
            .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PetAvatar(name = pet.name, species = pet.petSpecies, photoURL = pet.photoUrl, size = 32.dp)
        Text(pet.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
        if (on) Glyph("check", 16.dp, tint = Theme.blue)
    }
}

/** What the client already owes — visits, stays, fees — picked to pay now (web `OpenBills`). */
@Composable
private fun BillRow(m: CashCounterModel, bill: CashAccount.Open) {
    val on = bill.id in m.billIds
    val shape = RoundedCornerShape(Theme.boxRadius)
    val currency = m.currency
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (on) Theme.wash(Theme.blue, 8.0) else Theme.surface, shape)
            .border(2.dp, if (on) Theme.blue else Theme.borderSoft, shape)
            .plainButton { m.billIds = if (on) m.billIds - bill.id else m.billIds + bill.id }
            .semantics { selected = on }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(if (on) "square-check" else "square", 20.dp, tint = if (on) Theme.blue else Theme.borderSoft)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOf(CashBills.sourceTitle(bill.source), bill.petName).mapNotNull { it.nonEmpty }.joinToString(" · "),
                style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1,
            )
            Text(
                listOfNotNull(
                    bill.folio,
                    if (bill.daysOverdue > 0) CashText.daysLate(bill.daysOverdue) else null,
                    if (bill.amountPaid > 0) LF("%@ paid", cashMoney(bill.amountPaid, currency)) else null,
                ).joinToString(" · "),
                style = Theme.text(12), color = Theme.inkSoft,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(cashMoney(bill.balanceDueAmount, currency), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
    }
}

/** A dotted underline under a price that can be changed (SwiftUI `.underline(pattern: .dot)`). */
private fun Modifier.dottedUnderline(on: Boolean, color: Color): Modifier = if (!on) this else drawBehind {
    val y = size.height - 1.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 2.dp.toPx())))
}

/**
 * A line of the sale (web `CartRow`): the name and its total, the prescription it is
 * sold against, how many, the price each (typed for this sale when allowed), its why.
 */
@Composable
private fun CartRow(m: CashCounterModel, index: Int, line: CashCartLine) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    val currency = m.currency
    val canOverridePrice = m.canOverridePrice
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.product.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(cashMoney(m.totals.lines.getOrNull(index) ?: 0, currency), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
        }
        if (!m.canDispenseControlled && line.product.controlSchedule != null) {
            Text(L("Controlled: a vet hands it over."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.destructive)
        }
        if ((line.product.needsPrescription || line.prescriptionItemId != null) && m.petId != null) {
            CashPrescriptionPicker(
                petId = m.petId, catalogItemId = line.product.catalogItemId,
                selection = stateOf({ m.cart.getOrNull(index)?.prescriptionItemId }, { value -> m.updateLine(index) { it.copy(prescriptionItemId = value) } }),
                quantity = line.quantity,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CashQuantityStepper(
                quantity = line.quantity, lessIcon = if (line.quantity == 1) "trash-2" else "minus",
                lessLabel = if (line.quantity == 1) LF("Remove %@", line.product.name) else L("One less"),
                less = { m.setQuantity(index, line.quantity - 1) }, more = { m.setQuantity(index, line.quantity + 1) },
            )
            Spacer(Modifier.weight(1f))
            val hint = if (canOverridePrice) L("Change the price for this sale") else ""
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .plainButton(enabled = canOverridePrice) { if (canOverridePrice) m.pricing = line }
                    .semantics { if (hint.isNotEmpty()) onClick(label = hint, action = null) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val ink = if (canOverridePrice) Theme.blue else Theme.inkSoft
                val style = Theme.text(12, FontWeight.SemiBold)
                if (line.unit > 0) {
                    Text(LF("%@ each", cashMoney(line.unit, currency)), style = style, color = ink, modifier = Modifier.dottedUnderline(canOverridePrice, ink))
                } else {
                    Text(L("No price"), style = style, color = Theme.destructive)
                }
                if (line.unitAmount != null && line.unitAmount != line.product.unitAmount) {
                    Text(cashMoney(line.product.unitAmount, currency), style = style.copy(textDecoration = TextDecoration.LineThrough), color = Theme.inkSoft)
                }
            }
        }
        if (line.lowered) {
            CashReasonField(
                kind = CashReasonKind.priceLowered,
                text = stateOf({ m.cart.getOrNull(index)?.reason ?: "" }, { value -> m.updateLine(index) { it.copy(reason = value) } }),
                key = "line-${line.id}", form = m.form,
            )
        }
    }
}

@Composable
private fun TotalsSection(m: CashCounterModel, openRegister: () -> Unit) {
    val currency = m.currency
    val totals = m.totals
    if (m.canDiscount && m.cart.isNotEmpty()) {
        key("discount-${m.resets}") {
            MoneyField(label = L("Discount"), cents = m.ticketDiscount, key = "discount-amount", form = m.form, optional = true)
        }
    }
    if (m.ticketNeedsReason) {
        CashReasonField(kind = CashReasonKind.discount, text = m.discountReason, key = "discount", form = m.form)
    }
    if (m.selectedBills.isNotEmpty() && m.cart.isNotEmpty()) {
        TotalRow(L("Bills"), cashMoney(m.billsTotal, currency))
        TotalRow(L("Counter"), cashMoney(totals.total, currency))
    }
    if (totals.discount > 0) TotalRow(L("Discounts"), "−${cashMoney(totals.discount, currency)}")
    if (totals.tax > 0) {
        val rates = m.cart.map { it.product.rate }.filter { it > 0 }.toSet().sorted().joinToString(", ") { cashRate(it) }
        TotalRow(if (m.settings?.pricesIncludeTax == true) LF("Tax included (%@)", rates) else LF("Tax (%@)", rates), cashMoney(totals.tax, currency))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(L("Total").uppercase(), style = Theme.text(14, FontWeight.Bold).tracking(1.7), color = Theme.inkSoft)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        FittedText(cashMoney(m.grandTotal, currency), style = Theme.heading(30).monospacedDigit(), color = Theme.inkStrong, minimumScaleFactor = 0.6f)
    }
    if (m.credit > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph("piggy-bank", 16.dp, tint = Theme.blue)
            Text(LF("%@ of credit on account.", cashMoney(m.credit, currency)), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
        }
    }
    m.needsPatient?.let { NoticeCard(tone = NoticeCard.Tone.warning, title = LF("%@ needs a prescription — choose the client and the pet.", it.product.name)) }
    m.needsPrescription?.let { NoticeCard(tone = NoticeCard.Tone.warning, title = LF("Choose the prescription for %@.", it.product.name)) }
    m.needsVet?.let { NoticeCard(tone = NoticeCard.Tone.warning, title = LF("%@ is a controlled medicine: ask a vet to hand it over.", it.product.name)) }
    m.needsPrice?.let { NoticeCard(tone = NoticeCard.Tone.warning, title = LF("%@ has no price: type one before charging.", it.product.name)) }
    if (m.device.sessionId == null) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph("lock", 16.dp, tint = Theme.inkSoft)
            Text(L("The register is closed: you can take card or transfer, not cash."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, modifier = Modifier.weight(1f))
            if (cashCanRunShift(Workspace.shared)) {
                PMButton(L("Open register"), systemImage = "lock-open", variant = ButtonVariant.outline, size = ButtonSize.compact, action = openRegister)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.cart.isNotEmpty() || m.selectedBills.isNotEmpty()) {
            // One line goes at once; more than that asks first.
            IconCircleButton(systemImage = "trash-2", label = L("Clear the sale"), tint = Theme.blue) {
                if (m.cart.size > 1 || m.selectedBills.isNotEmpty()) m.clearing = true else m.reset()
            }
        }
        PMButton(LF("Charge %@", cashMoney(m.grandTotal, currency)), systemImage = "banknote", fullWidth = true, enabled = !m.blocked, modifier = Modifier.weight(1f)) {
            if (m.checkReasons()) m.paying = true
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Theme.text(14), color = Theme.inkSoft)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        Text(value, style = Theme.text(14, FontWeight.SemiBold).monospacedDigit(), color = Theme.inkSoft)
    }
}

// MARK: - Shared pieces of the Store's sheets

/** − count + in a 2 pt capsule, 44 pt targets (web: the cart line's and the return's quantity). */
@Composable
fun CashQuantityStepper(
    quantity: Int,
    lessIcon: String = "minus",
    lessLabel: String = L("One less"),
    lessDisabled: Boolean = false,
    moreDisabled: Boolean = false,
    modifier: Modifier = Modifier,
    less: () -> Unit,
    more: () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
        Row(
            modifier
                .border(2.dp, Theme.borderSoft, CapsuleShape)
                .semantics(mergeDescendants = false) { contentDescription = L("Quantity") },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).alpha(if (lessDisabled) 0.4f else 1f).plainButton(enabled = !lessDisabled, label = lessLabel, onClick = less),
                contentAlignment = Alignment.Center,
            ) { Glyph(lessIcon, 16.dp) }
            Text("$quantity", style = Theme.text(14, FontWeight.Bold).monospacedDigit(), textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).alpha(if (moreDisabled) 0.4f else 1f).plainButton(enabled = !moreDisabled, label = L("One more"), onClick = more),
                contentAlignment = Alignment.Center,
            ) { Glyph("plus", 16.dp) }
        }
    }
}

/**
 * Who a sheet is about, under its header (web `ModalContextPills`): the client
 * and the pet as soft 32 pt pills, and the client's link to PawMiaw.
 */
@Composable
fun CashContextPills(client: String?, linked: Boolean? = null, pets: List<String> = emptyList(), modifier: Modifier = Modifier) {
    // The shared pills (web `ModalContextPills`).
    ContextPills(client = client, linked = linked, pets = pets, modifier = modifier)
}

/**
 * One thing to sell (web `ProductCard`): its photo or a small tinted icon, the
 * name, the price and — for products — what is left. The whole card adds it;
 * the pencil (people who edit the catalog) opens the quick edit.
 */
@Composable
fun CashProductCard(
    product: CashProduct,
    inCart: Int = 0,
    canEdit: Boolean = false,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
    onEdit: () -> Unit = {},
) {
    val tone = if (product.isService) Hue.sky else Hue.peach
    val icon = if (product.isService) "briefcase-medical" else when (product.productKind) {
        "medication" -> "pill"
        "food" -> "paw-print"
        "supply" -> "boxes"
        "accessory" -> "shopping-bag"
        "other" -> "sparkles"
        else -> "package"
    }
    val stock = product.stock
    val available = stock?.available
    /** Out, low or in stock — Inventory's own rule; null when the stock is not counted. */
    val stockLevel: String? = when {
        stock == null || available == null -> null
        available <= 0 || stock.outOfStock == true -> "out"
        stock.lowStockAlert == false -> "in"
        stock.lowStock != null && ((stock.reorderPoint ?: 0) > 0 || stock.outOfStock != null) -> if (stock.lowStock == true) "low" else "in"
        available <= 3 -> "low"
        else -> "in"
    }
    val stockOut = stockLevel == "out" && stock?.allowBackorder != true
    val box = RoundedCornerShape(Theme.boxRadius)
    val chip = RoundedCornerShape(Theme.chipRadius)
    val editLabel = LF("Edit %@", product.name)
    // What the trailing buttons take: the pencil's slot (the button sits over it) and the plus.
    val trailing = if (canEdit || !stockOut) 12.dp + (if (canEdit) 36.dp else 0.dp) + (if (canEdit && !stockOut) 6.dp else 0.dp) + (if (!stockOut) 32.dp else 0.dp) else 0.dp

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .alpha(if (stockOut) 0.6f else 1f)
                .background(Theme.surface, box)
                .border(1.5.dp, if (inCart > 0) Theme.blue else Theme.borderSoft, box)
                .clip(box)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !stockOut,
                    onClick = onAdd,
                    // iOS `.contextMenu { Edit … }`: a long press edits (people who edit the catalog).
                    onLongClick = if (canEdit) onEdit else null, onLongClickLabel = if (canEdit) editLabel else null,
                )
                .padding(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(end = trailing), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(chip).background(Theme.wash(tone.color, 24.0)), contentAlignment = Alignment.Center) {
                    Glyph(icon, 20.dp, tint = Theme.inkStrong)
                    APIConfig.assetURL(product.photoUrl)?.let { url ->
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(product.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 2)
                    FlowLayout(spacing = 8.dp, lineSpacing = 4.dp) {
                        if (product.unitAmount > 0) {
                            Text(cashMoney(product.unitAmount, product.currency), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
                        } else {
                            Chip(text = L("No price"), tone = Chip.Tone.warn)
                        }
                        if (stockLevel != null && available != null) {
                            // The stock in three tones: red out, mango low, green in stock.
                            when (stockLevel) {
                                "out" -> Chip(text = if (stock.allowBackorder == true) L("On backorder") else if ((stock.expiredUnits ?: 0) > 0) L("Expired") else L("Out of stock"), tone = Chip.Tone.danger)
                                "low" -> Chip(text = LP(available, "%d left", "%d left"), tone = Chip.Tone.warn)
                                else -> HueChip(text = LF("%d in stock", available), hue = Hue.mint)
                            }
                        } else {
                            HueChip(text = if (product.isService) L("Service") else L("Product"), hue = tone)
                        }
                        if (product.needsPrescription) {
                            Chip(text = "Rx", tone = Chip.Tone.warn, modifier = Modifier.clearAndSetSemantics { contentDescription = L("Prescription only") })
                        }
                    }
                }
            }
            if (!stockOut) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .background(if (inCart > 0) Theme.blue else Theme.wash(Theme.blue, 14.0), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph("plus", 16.dp, tint = if (inCart > 0) Theme.onBlue else Theme.inkStrong)
                    if (inCart > 0) {
                        CountBadge(count = inCart, color = Theme.inkStrong, modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
                    }
                }
            }
        }
        if (canEdit) {
            IconCircleButton(
                systemImage = "pencil", label = editLabel,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (stockOut) 10.dp else (10 + 32 + 6).dp, bottom = 10.dp),
                action = onEdit,
            )
        }
    }
}

// MARK: - Sheets

/** "Who is buying?": the client, and the pet when it is for one of them. */
@Composable
fun CashWhoIsBuyingSheet(
    client: PickedClient?,
    petId: String?,
    modifier: Modifier = Modifier,
    onChoose: (PickedClient, String?) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val chosen = remember { mutableStateOf(client) }
    var pet by remember { mutableStateOf(petId) }
    val form = remember { FormState() }
    ProviderModal(
        title = L("Who is buying?"),
        subtitle = L("Choose the client, and the pet when it is for one of them. A prescription needs both."),
        systemImage = "user-round-search", accent = ModalAccent.aqua,
        isDirty = chosen.value?.id != client?.id || pet != petId, form = form,
        primaryTitle = L("Use this client"), primaryIcon = "check",
        onPrimary = onPrimary@{
            val picked = chosen.value
            if (!form.check(listOf("client" to (if (picked == null) L("Choose the client first.") else null))) || picked == null) return@onPrimary
            onChoose(picked, pet)
            dismiss()
        },
        modifier = modifier,
    ) {
        ClientPetPicker(client = chosen, petIds = stateOf({ listOfNotNull(pet) }, { pet = it.firstOrNull() }), form = form)
    }
}

/** A price typed at the counter for this sale only. */
@Composable
fun CashLinePriceSheet(line: CashCartLine, modifier: Modifier = Modifier, onSave: (Int) -> Unit) {
    val dismiss = LocalDismiss.current
    val price = remember { mutableStateOf<Int?>(if (line.unit > 0) line.unit else null) }
    val form = remember { FormState() }
    ProviderModal(
        title = L("Type a price"), subtitle = line.product.name, systemImage = "tag", accent = ModalAccent.butter,
        isDirty = price.value != line.unitAmount, form = form, primaryTitle = L("OK"),
        onPrimary = onPrimary@{
            if (!form.check(listOf("price" to (if ((price.value ?: 0) <= 0) L("Enter an amount") else null)))) return@onPrimary
            onSave(price.value ?: 0)
            dismiss()
        },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium)),
    ) {
        MoneyField(
            label = L("Change the price for this sale"), cents = price, key = "price", form = form,
            hint = if (line.product.unitAmount > 0) LF("Catalog price %@", cashMoney(line.product.unitAmount)) else null,
        )
    }
}

/**
 * The payment step of the counter (web `PaymentSheet`): who pays, the tenders,
 * the register, the receipt.
 */
@Composable
fun CashCounterPaySheet(
    total: Int,
    credit: Int,
    client: PickedClient?,
    petId: String?,
    device: CashDeviceRegister,
    automaticReceipts: Boolean,
    modifier: Modifier = Modifier,
    onSubmit: suspend (List<Map<String, Any?>>, Map<String, Any?>) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val tenders = remember {
        CashTenders().also { it.start(total = total, credit = credit, registerOpen = device.sessionId != null, allowBalance = client != null, currency = null) }
    }
    var working by remember { mutableStateOf(false) }
    val email = remember { mutableStateOf("") }
    val sendReceipt = remember { mutableStateOf(false) }
    val registerOpen = device.sessionId != null && !device.stale

    fun submit() {
        tenders.problem(registerOpen)?.let { problem ->
            Toast.error(problem)
            return
        }
        working = true
        scope.launch {
            try {
                onSubmit(tenders.payments(), CashReceiptEmailField.delivery(clientEmail = client?.ownerEmail, typed = email.value, send = sendReceipt.value, canSave = client != null))
                dismiss()
            } catch (_: CashHandled) {
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.error(error, L("Could not complete the sale."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Charge %@", cashMoney(total)),
        subtitle = L("Split it across methods if the customer pays part in cash and part by card."),
        systemImage = "credit-card", accent = ModalAccent.aqua, isDirty = false,
        primaryTitle = if (tenders.remaining > 0 && client != null) LF("Charge %@ · %@ on account", cashMoney(tenders.paid), cashMoney(tenders.remaining)) else L("Complete sale"),
        isWorking = working, cancelTitle = L("Back"), primaryIcon = "banknote",
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        if (client != null) {
            CashContextPills(client = client.name, linked = client.householdId != null, pets = client.activePets.filter { it.id == petId }.map { it.name })
        }
        CashTendersView(state = tenders, registerOpen = registerOpen)
        CashRegisterGate(device = device, wantsCash = tenders.wantsCash)
        CashReceiptEmailField(clientEmail = client?.ownerEmail, automatic = automaticReceipts, email = email, send = sendReceipt)
    }
}

/**
 * What the family paid (web `DoneSheet`): each bill settled with it, the receipt ready to
 * send, the sale's ticket and its PDF.
 */
@Composable
fun CashChargeDoneSheet(done: CashChargeDone, settings: CashStoreSettings, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    /** The sale and each bill this payment produced a receipt for. */
    val documents = buildList {
        done.sale?.let { add(CashReceiptDocument(id = it.id, folio = it.folio)) }
        for (bill in done.checkout?.invoices ?: emptyList()) {
            if (bill.paid > 0 && bill.id != done.sale?.id) add(CashReceiptDocument(id = bill.id, folio = bill.folio))
        }
    }
    val change = done.checkout?.change ?: (done.sale?.payments?.sumOf { it.changeAmount ?: 0 } ?: 0)
    val checkout = done.checkout
    ProviderModal(
        title = checkout?.let { LF("Paid %@", cashMoney(it.paid, settings.currency)) } ?: LF("Sale %@", done.sale?.folio ?: ""),
        subtitle = when {
            change > 0 -> LF("Change to hand back: %@", cashMoney(change, settings.currency))
            (checkout?.balanceLeft ?: 0) > 0 -> LF("%@ stays on their account.", cashMoney(checkout?.balanceLeft ?: 0, settings.currency))
            done.stockMoved -> L("Paid. Stock is updated.")
            else -> L("Paid.")
        },
        // The web's footer has no Cancel: "New sale" is the way out (a swipe closes it too).
        systemImage = "shopping-cart", accent = ModalAccent.aqua, primaryTitle = L("New sale"), primaryIcon = "plus", showsCancel = false,
        onPrimary = { dismiss() },
        modifier = modifier,
    ) {
        val bills = checkout?.invoices
        if (!bills.isNullOrEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                bills.forEach { bill ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Glyph("file-text", 16.dp, tint = Theme.inkSoft)
                            Text(bill.folio, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            cashMoney(bill.paid, settings.currency) + (if (bill.balance > 0) " · " + LF("%@ left", cashMoney(bill.balance, settings.currency)) else " · " + L("paid")),
                            style = Theme.text(14), color = Theme.inkSoft,
                        )
                    }
                }
            }
        }
        CashReceiptReady(documents = documents, emailedTo = done.emailedTo, clientEmail = done.clientEmail, pdfSkip = done.sale?.id)
        done.sale?.let { sale ->
            CashReceiptView(sale = sale, footer = settings.receiptFooter)
            DocumentButton(title = L("Download PDF"), systemImage = "file-down", path = "/provider-store/sales/${sale.id}/pdf", fileName = sale.folio, fullWidth = true)
        }
    }
}

/** The open bills' words at the counter. */
object CashBills {
    fun sourceTitle(source: String): String = when (source) {
        "visit" -> L("Visit")
        "stay" -> L("Stay")
        "hospital" -> L("Hospitalization")
        "store" -> L("Store")
        else -> L("Other@source")
    }
}

/**
 * After the payment: the receipt is ready — where it was emailed (or that it wasn't),
 * each bill's PDF one tap away, and emailing it now or again (web: receipt-delivery.tsx ReceiptReady).
 */
data class CashReceiptDocument(val id: String, val folio: String)

private val receiptEmailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
fun CashReceiptReady(
    documents: List<CashReceiptDocument>,
    emailedTo: String?,
    clientEmail: String?,
    /** A document whose PDF is shown elsewhere (the sale's, under its receipt). */
    pdfSkip: String? = null,
    modifier: Modifier = Modifier,
) {
    if (documents.isEmpty()) return
    val scope = rememberCoroutineScope()
    // `.onAppear` once: where it went, and the address to send it to now.
    var sentTo by remember { mutableStateOf(emailedTo) }
    var composing by remember { mutableStateOf(false) }
    val address = remember { mutableStateOf(emailedTo ?: clientEmail ?: "") }
    var sending by remember { mutableStateOf(false) }
    val valid = receiptEmailPattern.matches(address.value.trim())

    fun send() {
        val to = address.value.trim()
        if (!valid) return
        sending = true
        scope.launch {
            try {
                for (document in documents) {
                    API.send("POST", "/provider-invoices/${document.id}/send-receipt", mapOf("email" to to, "save" to (clientEmail.nonEmpty == null)))
                }
                sentTo = to
                composing = false
                Toast.show(LF("Receipt sent to %@.", to))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.error(error, L("The receipt couldn’t be sent."))
            } finally {
                sending = false
            }
        }
    }

    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.5.dp, Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(Theme.wash(Theme.butter, 55.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph("receipt-text", 16.dp, tint = Theme.inkStrong)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("Receipt ready"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                val sent = sentTo
                Text(
                    sent?.let { LF("Emailed to %@.", it) } ?: (if (clientEmail.nonEmpty != null) L("Not emailed.") else L("The client has no email.")),
                    style = Theme.text(12, if (sent != null) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (sent != null) Theme.blue else Theme.inkSoft,
                )
            }
        }
        FlowLayout(spacing = 8.dp) {
            // The sale's own PDF is under its receipt; the bills paid with it open here, made fresh.
            documents.filter { it.id != pdfSkip }.forEach { document ->
                DocumentButton(
                    title = if (documents.size > 1) "${L("Open PDF")} · ${document.folio}" else L("Open PDF"),
                    systemImage = "external-link", path = "/provider-invoices/${document.id}/pdf", fileName = document.folio,
                    prepare = { API.post<EmptyResponse>("/provider-invoices/${document.id}/pdf") },
                )
            }
            if (!composing) {
                PMButton(if (sentTo != null) L("Send again") else L("Email it"), systemImage = "mail", variant = ButtonVariant.outline, size = ButtonSize.compact) { composing = true }
            }
        }
        if (composing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = L("Email for the receipt")
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(Theme.inputFill, CapsuleShape)
                        .border(1.dp, if (address.value.isNotEmpty() && !valid) Theme.coral else Theme.borderSoft, CapsuleShape)
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PlainTextField(
                        address, L("name@example.com"), Modifier.fillMaxWidth(),
                        keyboard = KeyboardType.Email, capitalization = KeyboardCapitalization.None, autocorrect = false,
                        style = Theme.bodyFont.copy(color = Theme.inkStrong),
                    )
                }
                PMButton(L("Send"), systemImage = "send", size = ButtonSize.compact, isWorking = sending, enabled = valid) { send() }
                IconCircleButton(systemImage = "x", label = L("Cancel")) { composing = false }
            }
            if (address.value.isNotEmpty() && !valid) {
                Text(L("That email doesn’t look right."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.destructive)
            }
        }
    }
}
