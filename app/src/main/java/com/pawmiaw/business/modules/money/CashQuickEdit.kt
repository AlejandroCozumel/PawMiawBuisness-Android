package com.pawmiaw.business.money

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

//
//  CashQuickEdit.kt (iOS CashQuickEdit.swift)
//
//  Fix an item without leaving the counter (web: store/store-quick-edit.tsx):
//  its name, price and tax and — for a product whose stock is counted — the
//  barcode and how many are on the shelf. The Catalog's rules and requests (a
//  price is above 0; a stock change is a correction counted from the figure
//  shown); everything else stays in the Catalog. Only for who edits the catalog.
//

/** What changed, for a line of this item already in the sale. */
data class CashQuickEditSaved(
    val catalogItemId: String,
    val name: String,
    val unitAmount: Int,
    val taxTreatment: String,
    val taxRateBps: Int,
    val barcode: String?,
)

/** The same product with what the quick edit saved. */
private fun CashProduct.updated(saved: CashQuickEditSaved): CashProduct =
    copy(name = saved.name, barcode = saved.barcode, unitAmount = saved.unitAmount, taxTreatment = saved.taxTreatment, taxRateBps = saved.taxRateBps)

object CashQuickEdit {
    fun apply(saved: CashQuickEditSaved, to: CashProduct): CashProduct = to.updated(saved)
}

private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
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
fun CashQuickEditSheet(
    product: CashProduct,
    settings: CashStoreSettings,
    onSaved: (CashQuickEditSaved) -> Unit,
    /** The Catalog opened on this item (the sheet closes first). */
    onOpenCatalog: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var item by remember { mutableStateOf<CashCatalogItem?>(null) }
    var failed by remember { mutableStateOf(false) }
    val name = remember { mutableStateOf("") }
    val price = remember { mutableStateOf<Int?>(null) }
    val treatment = remember { mutableStateOf("default") }
    val rate = remember { mutableStateOf("") }
    val barcode = remember { mutableStateOf("") }
    val quantity = remember { mutableStateOf("") }
    var countedFrom by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }

    val canEditInventory = workspace.can(Permission.inventoryEdit)
    val countsStock = item?.let { it.isProduct && it.trackInventory && !it.isPack && it.inventory != null } ?: false
    val keepsNoPrice = item?.defaultUnitAmount == 0
    val owes = min(0, item?.inventory?.quantityOnHand ?: 0)

    val dirty: Boolean = item?.let { loaded ->
        name.value != loaded.name || (price.value ?: 0) != loaded.defaultUnitAmount || treatment.value != loaded.taxTreatment ||
            barcode.value != (loaded.inventory?.barcode ?: "") ||
            (quantity.value.isNotEmpty() && quantity.value.toIntOrNull() != loaded.inventory?.quantityOnHand)
    } ?: false

    suspend fun load() {
        try {
            val loaded: CashCatalogItem = API.get("/provider-catalog/${product.catalogItemId}")
            name.value = loaded.name
            price.value = if (loaded.defaultUnitAmount > 0) loaded.defaultUnitAmount else null
            treatment.value = loaded.taxTreatment
            rate.value = if (loaded.taxTreatment == "standard") CashCatalogRules.rateText(loaded.taxRateBps) else ""
            barcode.value = loaded.inventory?.barcode ?: ""
            // Below zero is not a count to show: the box starts empty, for what is really there.
            quantity.value = loaded.inventory?.quantityOnHand?.let { if (it >= 0) "$it" else "" } ?: ""
            countedFrom = loaded.inventory?.quantityOnHand ?: 0
            item = loaded
            seed += 1
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }

    fun save() {
        val loaded = item ?: return
        val trimmed = name.value.trim()
        val count = quantity.value.toIntOrNull()
        val owesCount = (loaded.inventory?.quantityOnHand ?: 0) < 0
        val ownRate = if (treatment.value == "standard") CashCatalogRules.rateBps(rate.value) else null
        if (!form.check(listOf(
                "name" to (if (trimmed.isEmpty()) L("Add the name first.") else null),
                "price" to CashCatalogRules.priceError(price.value, item = loaded),
                "taxRate" to (if (treatment.value == "standard" && ownRate == null) L("Enter a tax rate from 0 to 100.") else null),
                "quantity" to (if (countsStock && canEditInventory && count == null && !owesCount) L("Enter how many are on the shelf.") else null),
            ))) return

        // Only what changed is sent, so nothing else on the item is touched.
        val amount = price.value ?: 0
        val itemPatch = LinkedHashMap<String, Any?>()
        if (trimmed != loaded.name) itemPatch["name"] = trimmed
        if (amount != loaded.defaultUnitAmount) itemPatch["defaultUnitAmount"] = amount
        if (treatment.value != loaded.taxTreatment || ownRate != (if (loaded.taxTreatment == "standard") loaded.taxRateBps else null)) {
            itemPatch["taxTreatment"] = treatment.value
            itemPatch["taxRateBps"] = ownRate
        }
        val stockPatch = LinkedHashMap<String, Any?>()
        val inventory = loaded.inventory
        if (countsStock && canEditInventory && inventory != null) {
            if (barcode.value.nonEmpty != inventory.barcode.nonEmpty) stockPatch["barcode"] = barcode.value.nonEmpty
            if (count != null && count != countedFrom) {
                stockPatch["quantityOnHand"] = count
                stockPatch["countedFrom"] = countedFrom
                stockPatch["clientRequestId"] = "catalog-${cashRequestId()}"
            }
        }
        if (itemPatch.isEmpty() && stockPatch.isEmpty()) {
            dismiss()
            return
        }
        working = true
        scope.launch {
            try {
                if (itemPatch.isNotEmpty()) API.patch<EmptyResponse>("/provider-catalog/${loaded.id}", itemPatch)
                if (stockPatch.isNotEmpty()) API.patch<EmptyResponse>("/provider-catalog/${loaded.id}/inventory", stockPatch)
                onSaved(CashQuickEditSaved(
                    catalogItemId = loaded.id, name = trimmed, unitAmount = amount, taxTreatment = treatment.value,
                    taxRateBps = CashCatalogRules.effectiveRate(treatment = treatment.value, rateBps = ownRate, settings = settings),
                    barcode = if (countsStock && canEditInventory) barcode.value.nonEmpty else loaded.inventory?.barcode,
                ))
                Toast.show(LF("%@ was updated.", trimmed))
                announceDataChanged()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val refusal = CashCatalogRules.stockRefusal(error)
                if (refusal != null && (refusal.first == "barcode" || refusal.first == "quantity")) {
                    if (refusal.first == "quantity") {
                        attempt { API.get<CashCatalogItem>("/provider-catalog/${loaded.id}") }?.let { fresh ->
                            countedFrom = fresh.inventory?.quantityOnHand ?: countedFrom
                        }
                    }
                    form.check(listOf(refusal.first to refusal.second))
                    return@launch
                }
                Toast.error(error, fallback = if (loaded.isProduct) L("Could not save this product.") else L("Could not save this service."))
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val loaded = item
    ProviderModal(
        title = LF("Edit %@", loaded?.name ?: product.name),
        subtitle = if (loaded != null) L("The name, price and tax, right from the counter.") else null,
        systemImage = "pencil",
        accent = ModalAccent.mango,
        isDirty = loaded != null && dirty,
        form = form,
        primaryTitle = if (loaded != null) L("Save changes") else null,
        isWorking = working,
        primaryIcon = "save",
        onPrimary = if (loaded != null) ({ save() }) else null,
        modifier = modifier,
    ) {
        if (loaded != null) {
            PMTextField(
                L("Name"),
                text = stateOf({ name.value }) { name.value = it.take(140) },
                key = "name", form = form, required = true,
            )
            key(seed) {
                MoneyField(
                    label = LF("Price (%@)", settings.currency), cents = price, key = "price", form = form,
                    hint = if (keepsNoPrice && price.value == null) L("No price yet: it is asked for when charging. Type one here to stop asking.") else null,
                    required = !keepsNoPrice,
                )
            }
            CashTaxField(treatment = treatment, rate = rate, price = price.value, settings = settings, form = form)
            if (countsStock) {
                if (canEditInventory) {
                    PMTextField(
                        L("On the shelf"),
                        text = stateOf({ quantity.value }) { typed ->
                            quantity.value = typed.filter { it.isDigit() }
                            form.clear("quantity")
                        },
                        key = "quantity", form = form,
                        hint = if (owes < 0) LF("%d short: type what is really there to set it right.", -owes) else L("A change is recorded as a stock correction."),
                        keyboard = KeyboardType.Number,
                    )
                    BarcodeField(label = L("Barcode"), text = barcode, key = "barcode", form = form, hint = L("Scan it here to save it."))
                } else {
                    Text(
                        if (owes < 0) LF("%d short on the shelf: it needs a count. Your role cannot change stock from here.", -owes)
                        else LF("%d on the shelf. Your role cannot change stock from here.", loaded.inventory?.quantityOnHand ?: 0),
                        style = Theme.text(14), color = Theme.inkSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            // Web: the rest of the item lives in the Catalog, under a dashed rule.
            StitchedDivider()
            ViewThatFits(
                Modifier.fillMaxWidth(),
                first = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(L("Photo, category, description and the rest are in the Catalog."), style = Theme.text(14), color = Theme.inkSoft)
                        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                        CatalogLink(onOpenCatalog, dismiss)
                    }
                },
                second = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(L("Photo, category, description and the rest are in the Catalog."), style = Theme.text(14), color = Theme.inkSoft)
                        CatalogLink(onOpenCatalog, dismiss)
                    }
                },
            )
        } else if (failed) {
            Text(L("This item could not be opened here."), style = Theme.text(14), color = Theme.inkSoft)
            CatalogLink(onOpenCatalog, dismiss)
        } else {
            LoadingState()
        }
    }
}

/** "Open in the Catalog": closes the sheet, then opens the Catalog on the item. */
@Composable
private fun CatalogLink(onOpenCatalog: (() -> Unit)?, dismiss: () -> Unit) {
    if (Workspace.shared.can(Permission.catalogView) && onOpenCatalog != null) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .plainButton(label = L("Open in the Catalog")) {
                    dismiss()
                    onOpenCatalog()
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L("Open in the Catalog"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            Glyph("arrow-up-right", 16.dp, tint = Theme.blue)
        }
    }
}
