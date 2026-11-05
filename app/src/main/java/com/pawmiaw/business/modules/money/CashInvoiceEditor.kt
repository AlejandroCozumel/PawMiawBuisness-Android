package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlin.math.max

//
//  CashInvoiceEditor.kt (iOS CashInvoiceEditor.swift)
//
//  A bill made or changed by hand (web: provider-invoice-sheet.tsx,
//  invoices-page/invoice-editor-model.ts, bill-item-search.tsx): who it is
//  for, what it charges (from the catalog or typed), its discount and date,
//  its notes and where it goes (charge now, pay later, keep as a draft).
//  A courtesy or a price below the catalog's, and a discount of 10 % or more,
//  need their reason. Tax is the server's, line by line. A prescription-only
//  product's line chooses the pet's prescription it is dispensed against; when
//  the server asks for one the editor finds which lines need it.
//

private data class CashLineDraft(
    val key: String,
    val id: String?,
    val catalogId: String?,
    val itemType: String,
    val label: String,
    val description: String,
    val quantity: Int?,
    val unitAmount: Int?,
    val sourceType: String?,
    val sourceId: String?,
    val courtesy: Boolean,
    val prescriptionItemId: String?,
    val keepsPrescription: Boolean,
    /** A prescription-only (or controlled) product: the line picks the pet's prescription. */
    val needsPrescription: Boolean = false,
    val listUnitAmount: Int?,
    val reason: String,
    val saved: Saved?,
) {
    data class Saved(val unit: Int, val courtesy: Boolean, val catalogId: String?, val reason: String?)

    val unit: Int get() = unitAmount ?: 0
    val total: Int get() = max(0, quantity ?: 0) * unit
    val unpriced: Boolean get() = unit <= 0 && !courtesy

    /** Why this line needs a reason now: given free on purpose, or priced below the catalog's — when that is new. */
    val reasonKind: CashReasonKind?
        get() {
            val free = courtesy && unit <= 0
            val kind = if (free) CashReasonKind.courtesy else if (listUnitAmount?.let { unit > 0 && unit < it } == true) CashReasonKind.priceLowered else null
            kind ?: return null
            val before = saved
            if (before != null && before.catalogId == catalogId && before.unit == unit && before.courtesy == free) return null
            return kind
        }

    val reasonTyped: Boolean get() = reason.nonEmpty != null && reason.nonEmpty != saved?.reason.nonEmpty

    fun payload(index: Int): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "providerCatalogItemId" to catalogId,
            "itemType" to itemType,
            "label" to label.trim(),
            "description" to description.nonEmpty,
            "quantity" to (quantity ?: 1),
            "unitAmount" to unit,
            "sortOrder" to index,
            "sourceType" to sourceType,
            "sourceId" to sourceId,
            "courtesy" to (courtesy && unit == 0),
        )
        id?.let { entry["id"] = it }
        if (!keepsPrescription || prescriptionItemId != null || needsPrescription) entry["prescriptionItemId"] = prescriptionItemId
        if (reasonKind != null || reasonTyped) entry["adjustmentReason"] = reason.nonEmpty
        return entry
    }

    companion object {
        fun empty(type: String = "service"): CashLineDraft = CashLineDraft(
            key = UUID.randomUUID().toString(), id = null, catalogId = null, itemType = type, label = "", description = "", quantity = 1, unitAmount = null,
            sourceType = null, sourceId = null, courtesy = false, prescriptionItemId = null, keepsPrescription = false, listUnitAmount = null, reason = "", saved = null,
        )

        fun from(line: CashInvoiceLine, copy: Boolean): CashLineDraft {
            val needs = (line.requiresPrescription ?: false) || line.controlSchedule != null
            if (copy) {
                return CashLineDraft(
                    key = UUID.randomUUID().toString(), id = null, catalogId = line.providerCatalogItemId, itemType = line.itemType, label = line.label,
                    description = line.description ?: "", quantity = line.quantity, unitAmount = if (line.unitAmount > 0) line.unitAmount else null,
                    sourceType = null, sourceId = null, courtesy = line.courtesy ?: false, prescriptionItemId = null, keepsPrescription = false, needsPrescription = needs,
                    listUnitAmount = line.catalogItem?.defaultUnitAmount ?: line.listUnitAmount, reason = "", saved = null,
                )
            }
            return CashLineDraft(
                key = line.id, id = line.id, catalogId = line.providerCatalogItemId, itemType = line.itemType, label = line.label, description = line.description ?: "",
                quantity = line.quantity, unitAmount = if (line.unitAmount > 0 || line.courtesy == true) line.unitAmount else null, sourceType = line.sourceType,
                sourceId = line.sourceId, courtesy = line.courtesy ?: false, prescriptionItemId = line.prescriptionItemId, keepsPrescription = true, needsPrescription = needs,
                listUnitAmount = line.listUnitAmount, reason = line.adjustmentReason ?: "",
                saved = Saved(unit = line.unitAmount, courtesy = (line.courtesy ?: false) && line.unitAmount <= 0, catalogId = line.providerCatalogItemId, reason = line.adjustmentReason),
            )
        }
    }
}

/** A service or product found for a bill line. */
private data class CashFoundItem(
    val id: String,
    val itemType: String,
    val name: String,
    val unitAmount: Int,
    val needsPrescription: Boolean,
    val sku: String? = null,
    /** Units on the shelf, for a tracked product. */
    val available: Int? = null,
)

/** Swift `try?`. */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

/** `CashInvoiceEditorSheet.Step` and its page size. */
object CashInvoiceEditorSheet {
    /** The web's three steps: who it is for, the bill, where it goes. */
    enum class Step { who, bill, next }

    const val pageSize = ClientSearch.pageSize
}

private class CashInvoiceEditorModel(val bill: CashInvoice?, val template: CashInvoice?) {
    val form = FormState()
    var client by mutableStateOf<PickedClient?>(null)
    var petId by mutableStateOf<String?>(null)
    var lines by mutableStateOf(listOf<CashLineDraft>())
    var discount by mutableStateOf<Int?>(null)
    val discountReason = mutableStateOf("")
    val billingNote = mutableStateOf("")
    val notes = mutableStateOf("")
    var issuedAt by mutableStateOf(Instant.now())
    val dueAt = mutableStateOf(Instant.now())
    var destination by mutableStateOf("draft")
    val search = mutableStateOf("")
    var found by mutableStateOf(listOf<CashFoundItem>())
    var foundTotal by mutableStateOf<Int?>(null)
    var foundMore by mutableStateOf(false)
    var finding by mutableStateOf(false)
    var searchFailed by mutableStateOf(false)
    var shownFound by mutableIntStateOf(CashInvoiceEditorSheet.pageSize)
    var working by mutableStateOf(false)
    var changed by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var step by mutableStateOf(CashInvoiceEditorSheet.Step.who)

    // The view's environment, refreshed on every composition.
    lateinit var scope: CoroutineScope
    var dismiss: () -> Unit = {}
    var onSaved: (CashInvoice, Boolean) -> Unit = { _, _ -> }

    private val workspace: Workspace get() = Workspace.shared
    val isEdit: Boolean get() = bill != null
    val canDiscount: Boolean get() = workspace.can(Permission.billingApplyDiscount)
    val canPay: Boolean get() = workspace.can(Permission.billingRecordPayment)
    val clientLocked: Boolean get() = bill?.let { it.amountPaid > 0 || it.refunded > 0 } ?: false
    val dateLocked: Boolean get() = bill?.let { it.status != "draft" } ?: false
    /** A visit's, a stay's or the hospital's bill keeps its client, and so does one money moved on. */
    val canChangeWho: Boolean get() = bill?.let { CashBillRules.kind(it) == "other" && !clientLocked } ?: true
    /** Money already on it settles its status by itself. */
    val statusSettled: Boolean get() = bill?.let { it.status !in listOf("draft", "ready_for_payment", "open") } ?: false
    /** Where it goes is asked of whoever may take a bill out of draft, while its status isn't settled. */
    val asksDestination: Boolean get() = workspace.can(Permission.billingFinalizeInvoice) && !statusSettled
    val steps: List<CashInvoiceEditorSheet.Step>
        get() = (if (canChangeWho) listOf(CashInvoiceEditorSheet.Step.who) else emptyList()) + CashInvoiceEditorSheet.Step.bill +
            (if (asksDestination) listOf(CashInvoiceEditorSheet.Step.next) else emptyList())
    val stepIndex: Int get() = steps.indexOf(step).takeIf { it >= 0 } ?: 0
    val lastStep: CashInvoiceEditorSheet.Step get() = steps.lastOrNull() ?: CashInvoiceEditorSheet.Step.bill
    val subtotal: Int get() = lines.sumOf { it.total }
    val total: Int get() = max(0, subtotal - (discount ?: 0))
    val unpriced: Int get() = lines.count { it.unpriced }
    val chargeable: Boolean get() = lines.isNotEmpty() && unpriced == 0 && total > 0
    val discountNeedsReason: Boolean get() = cashDiscountNeedsReason(discount ?: 0, subtotal)

    val petName: String?
        get() {
            val id = petId ?: return null
            return client?.activePets?.firstOrNull { it.id == id }?.name.nonEmpty ?: (if (id == bill?.providerClientPetId) bill.petName else null)
        }

    val primaryTitle: String
        get() {
            if (step != lastStep) return L("Continue")
            if (!asksDestination) return if (statusSettled) L("Save the bill") else L("Save as a draft")
            return when (destination) {
                "ready_for_payment" -> if (canPay) L("Save and charge") else L("Save and send to the desk")
                "open" -> L("Save to pay later")
                else -> L("Save as a draft")
            }
        }

    fun line(key: String): CashLineDraft? = lines.firstOrNull { it.key == key }

    fun updateLine(key: String, change: (CashLineDraft) -> CashLineDraft) {
        lines = lines.map { if (it.key == key) change(it) else it }
    }

    // MARK: Loading

    suspend fun start() {
        if (loaded) return
        val source = bill ?: template
        if (bill != null) {
            lines = bill.lines.map { CashLineDraft.from(it, copy = false) }
            discount = if (bill.discountAmount > 0) bill.discountAmount else null
            discountReason.value = bill.discountReason ?: ""
            billingNote.value = bill.billingNote ?: ""
            notes.value = bill.notes ?: ""
            issuedAt = DateText.parse(bill.issuedAt) ?: Instant.now()
            dueAt.value = DateText.parse(bill.dueAt) ?: issuedAt
            destination = if (bill.status in listOf("ready_for_payment", "open")) bill.status else "draft"
            petId = bill.providerClientPetId
        } else if (template != null) {
            lines = template.lines.map { CashLineDraft.from(it, copy = true) }
            petId = template.providerClientPetId
        }
        if (source != null && !CashBillRules.isWalkIn(source)) {
            client = attempt { API.get<PickedClient>("/provider-clients/${source.providerClientId}") }
        }
        step = steps.firstOrNull() ?: CashInvoiceEditorSheet.Step.bill
        loaded = true
    }

    /**
     * The search (web `BillItemSearch`), 20 at a time: whoever sells searches the Store (barcode,
     * stock; one list, shown 20 at a time), anyone else the Catalog (pages of 20 with its count).
     */
    suspend fun find(text: String, reset: Boolean) {
        finding = true
        try {
            if (workspace.can(Permission.salesSell)) {
                try {
                    val products: List<CashProduct> = API.get("/provider-store/products", mapOf("q" to text))
                    if (ClientSearch.query(search.value) != text) return
                    found = products.map {
                        CashFoundItem(id = it.catalogItemId, itemType = it.itemType, name = it.name, unitAmount = it.unitAmount, needsPrescription = it.needsPrescription || it.controlSchedule != null, sku = it.sku, available = it.stock?.available)
                    }
                    foundTotal = products.size
                    foundMore = false
                    shownFound = CashInvoiceEditorSheet.pageSize
                    searchFailed = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    searchFailed = true
                }
            } else {
                val offset = if (reset) 0 else found.size
                try {
                    val page: Page<CashCatalogItem> = API.get(
                        "/provider-catalog",
                        mapOf("limit" to CashInvoiceEditorSheet.pageSize.toString(), "offset" to offset.toString(), "search" to text, "active" to "true"),
                    )
                    if (ClientSearch.query(search.value) != text) return
                    val items = page.items.map {
                        CashFoundItem(
                            id = it.id, itemType = it.itemType, name = it.name, unitAmount = it.defaultUnitAmount, needsPrescription = it.requiresPrescription || it.controlSchedule != null,
                            sku = it.sku, available = if (it.trackInventory) it.inventory?.quantityOnHand else null,
                        )
                    }
                    found = if (reset) items else found + items.filter { item -> found.none { it.id == item.id } }
                    foundTotal = page.total
                    foundMore = page.hasMore ?: (page.total?.let { found.size < it } ?: (items.size == CashInvoiceEditorSheet.pageSize))
                    shownFound = found.size
                    searchFailed = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (reset) searchFailed = true
                }
            }
        } finally {
            finding = false
        }
    }

    /** The next 20: more of the Store's list on screen, or the Catalog's next page. */
    suspend fun more() {
        if (finding) return
        if (found.size > shownFound) {
            shownFound += CashInvoiceEditorSheet.pageSize
        } else {
            val query = ClientSearch.query(search.value)
            if (foundMore && query != null) find(query, reset = false)
        }
    }

    fun clearPrescriptions() {
        lines = lines.map { if (it.needsPrescription) it.copy(prescriptionItemId = null) else it }
    }

    /**
     * The server asked for a prescription: the lines that need one show their picker,
     * and the first without a choice is marked (its products are read again to know which).
     */
    suspend fun markPrescriptions(message: String) {
        val unknown = lines.filter { !it.needsPrescription && it.itemType == "product" }.mapNotNull { it.catalogId }
        if (unknown.isNotEmpty()) {
            val products = attempt { API.get<List<CashProduct>>("/provider-store/products", mapOf("ids" to unknown.joinToString(","))) }
            if (products != null) {
                lines = lines.map { line ->
                    val id = line.catalogId
                    val product = id?.let { wanted -> products.firstOrNull { it.catalogItemId == wanted } }
                    if (product != null && (product.needsPrescription || product.controlSchedule != null)) line.copy(needsPrescription = true) else line
                }
            }
        }
        val target = lines.firstOrNull { it.needsPrescription && it.prescriptionItemId == null } ?: lines.firstOrNull { it.needsPrescription }
        form.check(listOf((target?.let { "rx-${it.key}" } ?: "lines") to message))
    }

    fun addFound(item: CashFoundItem) {
        // The same catalog item again is one more of it, not a second line.
        val same = lines.firstOrNull { it.catalogId == item.id && !it.courtesy }
        val quantity = same?.quantity
        if (same != null && quantity != null && quantity > 0) {
            updateLine(same.key) { it.copy(quantity = quantity + 1) }
            search.value = ""
            found = emptyList()
            changed = true
            form.clear("lines")
            return
        }
        val line = CashLineDraft.empty(item.itemType).copy(
            catalogId = item.id,
            label = item.name,
            unitAmount = if (item.unitAmount > 0) item.unitAmount else null,
            listUnitAmount = item.unitAmount,
            needsPrescription = item.needsPrescription && item.itemType == "product",
        )
        lines = lines + line
        search.value = ""
        found = emptyList()
        changed = true
        form.clear("lines")
    }

    // MARK: Saving

    /** The footer's main button: on to the next step, or the save on the last one. */
    fun next() {
        when {
            step == CashInvoiceEditorSheet.Step.who -> {
                if (!passes(listOf("client" to (if (client == null && bill == null) L("Choose the client first.") else null)))) return
                step = CashInvoiceEditorSheet.Step.bill
            }
            step == CashInvoiceEditorSheet.Step.bill && lastStep == CashInvoiceEditorSheet.Step.next -> {
                if (!passes(billRules(null))) return
                // Nothing to charge yet: it can only stay a draft.
                if (unpriced > 0 || total <= 0) destination = "draft" else if (destination == "draft" && !isEdit) destination = "ready_for_payment"
                step = CashInvoiceEditorSheet.Step.next
            }
            else -> save()
        }
    }

    /** The bill step's own checks, in the order its fields show; `target` also holds it to what leaving draft needs. */
    fun billRules(target: String?): List<Pair<String, String?>> {
        val rules = mutableListOf<Pair<String, String?>>()
        if (lines.isEmpty() && (!isEdit || (target != null && target != "draft"))) rules.add("lines" to L("Add at least one line to the bill."))
        for (line in lines) {
            rules.add("label-${line.key}" to (if (line.label.nonEmpty == null) L("Give the line a name.") else null))
            rules.add("qty-${line.key}" to (if ((line.quantity ?: 0) < 1 || (line.quantity ?: 0) > 9999) L("A whole number, 1 or more.") else null))
        }
        if (target != null && target != "draft" && unpriced > 0) rules.add("lines" to L("Put a price on every line, or mark it as a courtesy, before charging."))
        for (line in lines) {
            val kind = line.reasonKind ?: continue
            rules.add("reason-${line.key}" to (if (line.reason.nonEmpty == null) kind.required else null))
        }
        if ((discount ?: 0) > subtotal) rules.add("discount" to LF("The discount is more than the bill adds up to (%@).", cashMoney(subtotal)))
        if (canDiscount && discountNeedsReason) rules.add("discountReason" to (if (discountReason.value.nonEmpty == null) CashReasonKind.discount.required else null))
        if (target != null && target != "draft" && total <= 0) rules.add("lines" to L("A bill that adds up to nothing stays a draft: there is nothing to charge."))
        if (target == "open" && DateText.dayKey(dueAt.value) < DateText.dayKey(issuedAt)) rules.add("dueAt" to L("The day to pay by can’t be before the bill’s date."))
        return rules
    }

    /** Which step shows a field. */
    fun stepOf(key: String): CashInvoiceEditorSheet.Step = when {
        key == "client" -> CashInvoiceEditorSheet.Step.who
        key == "dueAt" || key == "destination" -> CashInvoiceEditorSheet.Step.next
        else -> CashInvoiceEditorSheet.Step.bill
    }

    /**
     * The form rule across steps: every invalid field marked, one toast, and the step with the
     * first one opened and scrolled to.
     */
    fun passes(rules: List<Pair<String, String?>>): Boolean {
        val first = rules.firstOrNull { it.second != null }?.first ?: return form.check(rules)
        val target = stepOf(first)
        if (target != step && target in steps) {
            step = target
            // Once that step is drawn, so the scroll finds its field.
            scope.launch {
                withFrameNanos { }
                form.check(rules)
            }
        } else {
            form.check(rules)
        }
        return false
    }

    /** A refusal the server placed on a field: its step opens and scrolls to it. */
    fun reveal() {
        val target = form.scrollTarget ?: return
        val shown = stepOf(target)
        if (shown !in steps || shown == step) return
        step = shown
        // The scroll runs again once the step is drawn (FormState scrolls on a check).
        val marked = form.errors.map { it.key to it.value as String? }
        scope.launch {
            withFrameNanos { }
            form.check(marked)
        }
    }

    fun save() {
        val target: String? = if (asksDestination) destination else if (statusSettled) null else "draft"
        val rules = mutableListOf<Pair<String, String?>>()
        if (canChangeWho) rules.add("client" to (if (client == null && bill == null) L("Choose the client first.") else null))
        rules += billRules(if (statusSettled) "ready_for_payment" else target)
        if (!passes(rules)) return

        val input = linkedMapOf<String, Any?>(
            "providerClientId" to (client?.id ?: bill?.providerClientId),
            "providerClientPetId" to petId,
            "billingNote" to billingNote.value.nonEmpty,
            "notes" to notes.value.nonEmpty,
            "lineItems" to lines.mapIndexed { index, line -> line.payload(index) },
        )
        if (target != null) input["status"] = target
        if (!dateLocked) input["issuedAt"] = DateText.dayKey(issuedAt)
        if (target == "ready_for_payment") input["dueAt"] = CashDay.today else if (target == "open") input["dueAt"] = DateText.dayKey(dueAt.value)
        bill?.revision?.let { input["expectedRevision"] = it }
        if (bill == null) workspace.locationId?.let { input["locationId"] = it }
        if (canDiscount) {
            input["discountAmount"] = discount ?: 0
            input["discountReason"] = if ((discount ?: 0) > 0) discountReason.value.nonEmpty else null
        }
        working = true
        scope.launch {
            try {
                val saved: CashInvoice = if (bill != null) API.patch("/provider-invoices/${bill.id}", input) else API.post("/provider-invoices", input)
                if (target != null && target != "draft" && saved.status == "draft") {
                    Toast.show(L("Saved as a draft: a line still needs a price."))
                } else {
                    Toast.show(if (bill != null) L("Bill saved.") else L("Bill created."))
                }
                announceDataChanged()
                onSaved(saved, target == "ready_for_payment" && canPay && saved.status != "draft")
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val code = cashErrorCode(error)
                when {
                    code == "stale_bill" ->
                        Toast.error(L("Someone changed this bill while you were editing. Here it is as it stands now — make your change again."))
                    code == "reason_required" -> {
                        val key = lines.firstOrNull { it.reasonKind != null }?.let { "reason-${it.key}" } ?: "discountReason"
                        passes(listOf(key to errorText(error)))
                    }
                    code == "discount_too_big" ->
                        passes(listOf("discount" to LF("The discount is more than the bill adds up to (%@).", cashMoney(subtotal))))
                    code != null && code.startsWith("prescription_") -> {
                        if (step != CashInvoiceEditorSheet.Step.bill) step = CashInvoiceEditorSheet.Step.bill
                        markPrescriptions(errorText(error))
                    }
                    else -> {
                        val known = mapOf(
                            "providerClientId" to "client", "discountAmount" to "discount", "discountReason" to "discountReason",
                            "issuedAt" to "issuedAt", "dueAt" to "dueAt", "lineItems" to "lines",
                        )
                        if (form.apply(error, known)) reveal()
                    }
                }
            } finally {
                working = false
            }
        }
    }
}

@Composable
fun CashInvoiceEditorSheet(
    bill: CashInvoice?,
    template: CashInvoice?,
    modifier: Modifier = Modifier,
    /** The saved bill, and whether to go straight to taking its payment. */
    onSaved: (CashInvoice, Boolean) -> Unit,
) {
    val m = remember { CashInvoiceEditorModel(bill, template) }
    m.scope = rememberCoroutineScope()
    m.dismiss = LocalDismiss.current
    m.onSaved = onSaved

    LaunchedEffect(Unit) { m.start() }
    val query = ClientSearch.query(m.search.value)
    val onBill = m.step == CashInvoiceEditorSheet.Step.bill
    LaunchedEffect(query ?: "", onBill) {
        // From two letters, 250 ms after typing stops; under that nothing is searched.
        if (!onBill || query == null) {
            m.found = emptyList()
            m.foundTotal = null
            m.foundMore = false
            m.searchFailed = false
            return@LaunchedEffect
        }
        delay(ClientSearch.debounce)
        m.find(query, reset = true)
    }

    val steps = m.steps
    val stepIndex = m.stepIndex
    ProviderModal(
        title = if (!m.isEdit) (if (template != null) L("Bill again") else L("New bill")) else if (bill?.status == "draft") L("Finish the bill") else L("Edit the bill"),
        subtitle = if (!m.isEdit) L("A bill made by hand, for what isn’t a visit, a stay or a counter sale.") else LF("%@ · what it charges, its discount and where it goes.", bill?.invoiceNumber ?: ""),
        systemImage = "file-text", accent = ModalAccent.aqua, isDirty = m.changed, form = m.form,
        primaryTitle = m.primaryTitle, isWorking = m.working,
        steps = if (steps.size > 1) ModalSteps(steps = steps.map { stepName(it).first }, icons = steps.map { stepName(it).second }, current = stepIndex) else null,
        onBack = if (stepIndex > 0) ({ m.step = steps[stepIndex - 1] }) else null,
        onPrimary = { m.next() },
        modifier = modifier,
    ) {
        if (!m.loaded) {
            LoadingState(text = L("Loading the bill…"))
        } else {
            val name = m.client?.name.nonEmpty ?: bill?.clientName
            if (m.step != CashInvoiceEditorSheet.Step.who && name != null) {
                ContextPills(client = name, linked = m.client?.let { it.householdId != null }, pets = m.petName?.let { listOf(it) } ?: emptyList())
            }
            when (m.step) {
                CashInvoiceEditorSheet.Step.who -> WhoStep(m)
                CashInvoiceEditorSheet.Step.bill -> BillStep(m)
                CashInvoiceEditorSheet.Step.next -> DestinationStep(m)
            }
        }
    }
}

private fun stepName(entry: CashInvoiceEditorSheet.Step): Pair<String, String> = when (entry) {
    CashInvoiceEditorSheet.Step.who -> L("Client and pet") to "user-round"
    CashInvoiceEditorSheet.Step.bill -> L("The bill") to "file-text"
    CashInvoiceEditorSheet.Step.next -> L("Where it goes") to "circle-dollar-sign"
}

// MARK: Step 1: who

@Composable
private fun WhoStep(m: CashInvoiceEditorModel) {
    ClientPetPicker(
        client = stateOf({ m.client }, { next ->
            // A prescription belongs to a pet: another client's lines choose theirs again.
            if (next?.id != m.client?.id) m.clearPrescriptions()
            m.client = next
            m.changed = true
        }),
        petIds = stateOf({ listOfNotNull(m.petId) }, { ids ->
            val next = ids.firstOrNull()
            if (next != m.petId) m.clearPrescriptions()
            m.petId = next
            m.changed = true
        }),
        form = m.form,
        petHint = L("Leave the pet out for a charge that isn’t for one in particular."),
    )
}

// MARK: Step 2: the bill

@Composable
private fun BillStep(m: CashInvoiceEditorModel) {
    val workspace = Workspace.shared
    ModalSection(title = L("What it charges"), systemImage = "file-text", tint = Theme.aqua, modifier = Modifier.formScrollTarget("lines", m.form)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (m.lines.isEmpty()) {
                Text(
                    L("Nothing on the bill yet. Find a service or product below, or type a line by hand."),
                    style = Theme.text(14), color = Theme.inkSoft,
                    modifier = Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(12.dp),
                )
            }
            m.lines.forEach { line -> key(line.key) { LineEditor(m, line.key) } }
            ItemSearch(m)
            m.form.error("lines")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
        }
    }
    ModalSection(title = L("Discount and total"), systemImage = "circle-dollar-sign", tint = Theme.butter) {
        if (m.canDiscount) {
            MoneyField(label = L("Discount"), cents = stateOf({ m.discount }, { m.discount = it; m.changed = true }), key = "discount", form = m.form)
            if (m.discountNeedsReason) CashReasonField(kind = CashReasonKind.discount, text = m.discountReason, key = "discountReason", form = m.form)
        } else if ((m.discount ?: 0) > 0) {
            FactRow(label = L("Discount"), value = cashMoney(m.discount))
            Text(L("You can see the discount here, but your role cannot change it."), style = Theme.text(12), color = Theme.inkSoft)
        }
        if (m.dateLocked) {
            FactRow(label = L("Bill date"), value = DateText.date(m.issuedAt))
            Text(L("Fixed once the bill left draft: it is the day its money counts."), style = Theme.text(12), color = Theme.inkSoft)
        } else {
            DateTimeField(
                label = L("Bill date"), date = stateOf({ m.issuedAt }, { m.issuedAt = it; m.changed = true }),
                components = setOf(DatePickerComponent.date), key = "issuedAt", form = m.form, required = true,
            )
        }
        Column(
            Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CashBillTotalRow(label = L("Subtotal"), value = cashMoney(m.subtotal))
            if ((m.discount ?: 0) > 0) CashBillTotalRow(label = L("Discount"), value = "−${cashMoney(m.discount)}")
            CashBillTotalRow(label = L("Before tax"), value = cashMoney(m.total), strong = true)
            val bill = m.bill
            if (bill != null && bill.refunded > 0) CashBillTotalRow(label = L("Refunded or credited"), value = "−${cashMoney(bill.refunded)}")
            if (bill != null && bill.amountPaid > 0) CashBillTotalRow(label = L("Paid@amount"), value = cashMoney(bill.amountPaid))
            Text(
                L("Tax is added line by line when it is saved, at each item’s rate.") + " " + LF("In %@, the business currency.", workspace.currency) +
                    (if (m.unpriced > 0) " " + LP(m.unpriced, "%d line still needs a price.", "%d lines still need a price.") else ""),
                style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    ModalSection(title = L("Notes"), systemImage = "file-text", tint = Theme.surfaceMuted) {
        PMTextField(L("Billing note"), text = m.billingNote, prompt = L("For the desk: what to check, how to follow up."), multiline = true)
        PMTextField(L("Internal notes"), text = m.notes, prompt = L("Anything the clinic should keep on the bill itself."), multiline = true)
        Text(L("Neither note is printed on the client’s receipt."), style = Theme.text(12), color = Theme.inkSoft)
    }
}

// MARK: Step 3: where it goes

@Composable
private fun DestinationStep(m: CashInvoiceEditorModel) {
    // The question with its red mark, what it comes to, then the three ways (web `OptionCardGrid`).
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(L("Where does this bill go?"), required = true)
        Text(
            LF("%@ before tax for %@.", cashMoney(m.total), m.client?.name.nonEmpty ?: m.bill?.clientName ?: "") + " " +
                L("Once it leaves draft, its products leave the shelf and its medicines count against their prescription."),
            style = Theme.text(14), color = Theme.inkSoft,
        )
    }
    OptionCards(options = destinationOptions(m), selection = stateOf<String>({ m.destination }, { next ->
        m.destination = next
        m.form.clear("dueAt")
        m.changed = true
    }))
    if (m.destination == "open") {
        DateTimeField(label = L("Pay by"), date = m.dueAt, min = m.issuedAt, components = setOf(DatePickerComponent.date), key = "dueAt", form = m.form, required = true)
    }
}

/** The three ways (web `destinationOptions`): charging and paying later need a priced bill with something to charge. */
@Composable
private fun destinationOptions(m: CashInvoiceEditorModel): List<OptionCard<String>> {
    val unavailable: String? = if (m.chargeable) null else if (m.unpriced > 0) L("A line still needs a price.") else L("There is nothing to charge yet.")
    val canPay = m.canPay
    return listOf(
        OptionCard(
            "ready_for_payment", title = if (canPay) L("Charge it now") else L("Send to the desk"),
            description = if (canPay) L("Save the bill and go straight to taking the payment.") else L("Save it ready for the desk to charge."),
            icon = if (canPay) "coins" else "send", accent = Theme.pink, unavailableReason = unavailable,
        ),
        OptionCard(
            "open", title = L("Pay later"), description = L("Save it with its balance owed, and choose the day it should be paid by."),
            icon = "circle-dollar-sign", accent = Theme.mango, unavailableReason = unavailable,
        ),
        OptionCard(
            "draft", title = L("Keep as a draft"), description = L("Only your team sees it. Nothing is charged, and its products stay on the shelf."),
            icon = "file-text", accent = Theme.aqua,
        ),
    )
}

/**
 * Finding what to put on the bill (web `BillItemSearch`): a dashed box with the
 * search (scan too, for whoever sells), "Type a line by hand", and what was found.
 */
@Composable
private fun ItemSearch(m: CashInvoiceEditorModel) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .dashedBorder(1.5.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 4.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (Workspace.shared.can(Permission.salesSell)) {
            // What the Store sells can be scanned: the code goes in the search.
            BarcodeSearchField(prompt = L("Service or product: name, SKU or barcode"), text = m.search, onScan = { scan -> m.search.value = scan.code })
        } else {
            SearchField(prompt = L("Service or product: name or SKU"), text = m.search)
        }
        PMButton(L("Type a line by hand"), systemImage = "pen-line", variant = ButtonVariant.outline, fullWidth = true) {
            m.lines = m.lines + CashLineDraft.empty()
            m.changed = true
            m.form.clear("lines")
        }
        if (ClientSearch.query(m.search.value) != null) {
            if (m.searchFailed) {
                Text(
                    L("The search didn’t answer. Try again, or type the line by hand."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else if (m.found.isEmpty() && !m.finding) {
                Text(
                    L("Nothing in the catalog matches. Type the line by hand, or add it to the Catalog first."), style = Theme.text(14), color = Theme.inkSoft,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else if (m.found.isNotEmpty()) {
                val count = m.foundTotal ?: m.found.size
                Eyebrow(if (count == 1) LF("%@ result", "1") else LF("%@ results", count.toString()))
                val shown = m.found.take(m.shownFound)
                Column(Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))) {
                    shown.forEachIndexed { index, item ->
                        key(item.id) {
                            if (index > 0) RowDivider()
                            FoundRow(
                                item,
                                Modifier
                                    .plainButton { m.addFound(item) }
                                    .onScrolledIntoView { if (item.id == shown.lastOrNull()?.id) scope.launch { m.more() } },
                            )
                        }
                    }
                    LoadMoreRow(hasMore = m.found.size > m.shownFound || m.foundMore, busy = m.finding) { scope.launch { m.more() } }
                }
            }
        }
    }
}

@Composable
private fun FoundRow(item: CashFoundItem, modifier: Modifier) {
    val details = listOfNotNull(
        if (item.itemType == "product") L("Product") else L("Service"),
        item.sku.nonEmpty,
        item.available?.let { LP(it, "%d on the shelf", "%d on the shelf") },
        if (item.needsPrescription) L("Needs a prescription") else null,
    )
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).background(Theme.wash(Theme.aqua, 22.0), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(if (item.itemType == "product") "package" else "briefcase-medical", 16.dp, tint = Theme.inkStrong)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(details.joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (item.unitAmount > 0) cashMoney(item.unitAmount) else L("No price"), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
    }
}

/**
 * One line (web `LineEditor`): what is charged, how many, at what price; then its
 * kind, a courtesy, its total and ✕; the reason and prescription it may need; a note.
 */
@Composable
private fun LineEditor(m: CashInvoiceEditorModel, key: String) {
    val value = m.line(key) ?: return
    val form = m.form
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PMTextField(
            L("What is charged"), text = stateOf({ m.line(key)?.label ?: "" }, { text -> m.updateLine(key) { it.copy(label = text) } }),
            key = "label-$key", form = form, prompt = L("e.g. Annual wellness exam"), required = true,
        )
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(label = L("Qty"), key = "qty-$key", form = form, required = true, modifier = Modifier.width(88.dp)) {
                PlainTextField(
                    stateOf({ m.line(key)?.quantity?.toString() ?: "" }, { text ->
                        m.updateLine(key) { it.copy(quantity = text.filter(Char::isDigit).toIntOrNull()) }
                        m.changed = true
                        form.clear("qty-$key")
                    }),
                    "1",
                    Modifier.fillMaxWidth().fieldChrome(invalid = form.error("qty-$key") != null),
                    keyboard = KeyboardType.Number,
                    style = Theme.bodyFont.monospacedDigit().copy(textAlign = TextAlign.Center, color = Theme.inkStrong),
                )
            }
            Disabled(value.courtesy && value.unit <= 0) {
                MoneyField(
                    label = L("Unit price"), cents = stateOf({ m.line(key)?.unitAmount }, { cents -> m.updateLine(key) { it.copy(unitAmount = cents) }; m.changed = true }),
                    key = "price-$key", form = form, modifier = Modifier.weight(1f),
                )
            }
        }
        FlowLayout(spacing = 8.dp, lineSpacing = 8.dp) {
            if (value.catalogId != null) {
                Row(
                    Modifier.height(24.dp).background(Theme.wash(Theme.aqua, 18.0), CapsuleShape).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Glyph(if (value.itemType == "product") "package" else "stethoscope", 12.dp, tint = Theme.inkStrong)
                    Text(if (value.itemType == "product") L("Catalog product") else L("Catalog service"), style = Theme.text(11.2, FontWeight.ExtraBold), color = Theme.inkStrong)
                }
            } else {
                Segmented(
                    options = listOf(Segmented.Option("service", L("Service")), Segmented.Option("product", L("Product"))),
                    selection = stateOf({ m.line(key)?.itemType ?: "service" }, { type -> m.updateLine(key) { it.copy(itemType = type) }; m.changed = true }),
                    look = Segmented.Look.raised, label = L("Type"),
                )
            }
            val listed = value.listUnitAmount
            if (listed != null && listed > 0 && value.unit > 0 && listed != value.unit) {
                Text(LF("Catalog price %@", cashMoney(listed)), style = Theme.text(12), color = Theme.inkSoft)
            }
            if (value.unpriced) {
                Text(L("Needs a price"), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
            }
            if (value.unit <= 0 || value.courtesy) {
                val hint = L("Given on purpose for $0: it doesn’t hold the bill for a price.")
                PMSwitchStyle(
                    isOn = stateOf({ m.line(key)?.courtesy ?: false }, { on ->
                        m.updateLine(key) { it.copy(courtesy = on, unitAmount = if (on) 0 else null) }
                        m.changed = true
                    }),
                    modifier = Modifier.semantics { onClick(label = hint, action = null) },
                ) {
                    Text(L("Courtesy"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.weight(1f))
            val totalLabel = L("Line total")
            Text(cashMoney(value.total), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong, modifier = Modifier.semantics { contentDescription = totalLabel })
            IconCircleButton(systemImage = "trash-2", label = L("Remove")) {
                m.lines = m.lines.filter { it.key != key }
                m.changed = true
            }
        }
        value.reasonKind?.let { kind ->
            CashReasonField(kind = kind, text = stateOf({ m.line(key)?.reason ?: "" }, { text -> m.updateLine(key) { it.copy(reason = text) } }), key = "reason-$key", form = form)
        }
        val catalogId = value.catalogId
        if (value.needsPrescription && catalogId != null) {
            FormField(label = L("Prescription"), key = "rx-$key", form = form) {
                CashPrescriptionPicker(
                    petId = m.petId, catalogItemId = catalogId,
                    selection = stateOf({ m.line(key)?.prescriptionItemId }, { id ->
                        m.updateLine(key) { it.copy(prescriptionItemId = id) }
                        m.changed = true
                        form.clear("rx-$key")
                    }),
                    quantity = value.quantity ?: 1, autoPick = false,
                )
            }
        }
        PlainTextField(
            stateOf({ m.line(key)?.description ?: "" }, { text -> m.updateLine(key) { it.copy(description = text) }; m.changed = true }),
            L("A note on this line (optional)"),
            Modifier.fillMaxWidth().fieldChrome(),
            style = Theme.text(14).copy(color = Theme.inkStrong),
        )
    }
}
