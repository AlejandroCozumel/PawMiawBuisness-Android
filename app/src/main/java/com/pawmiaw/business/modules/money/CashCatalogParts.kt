package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.roundToInt

//
//  CashCatalogParts.kt (iOS CashCatalogParts.swift)
//
//  Pieces the Catalog's editor and the counter's quick edit share (web:
//  catalog-page/catalog-sheet-fields.tsx, catalog-model.ts): the price rule,
//  the tax field with what a client pays, the item's photo, and "sold as a
//  pack of another product".
//

object CashCatalogRules {
    /** A price is above 0; only an item already saved without one may keep none by leaving it empty. */
    fun priceError(price: Int?, item: CashCatalogItem?): String? {
        if (price == null) return if (item?.defaultUnitAmount == 0) null else L("Add a valid price first.")
        return if (price <= 0) L("The price must be above 0.") else null
    }

    /** The rate a sale charges for an item: its own, none, or the store's standard one. */
    fun effectiveRate(treatment: String, rateBps: Int?, settings: CashStoreSettings?): Int = when (treatment) {
        "zero", "exempt" -> 0
        "standard" -> rateBps ?: 0
        else -> settings?.defaultTaxRateBps ?: 0
    }

    /** "16" → 1600 basis points; null when it is not a rate from 0 to 100. */
    fun rateBps(text: String): Int? {
        val percent = text.replace(",", ".").replace("%", "").trim().toDoubleOrNull() ?: return null
        if (percent < 0 || percent > 100) return null
        return (percent * 100).roundToInt()
    }

    /** Swift `String(format: "%g", bps / 100)`: "16", "16.5", "0". */
    fun rateText(bps: Int?): String =
        bps?.let { BigDecimal.valueOf(it.toLong(), 2).stripTrailingZeros().toPlainString() } ?: ""

    /** What a client pays at this price, taxed at `bps` (web `clientPrice` / the TaxField preview). */
    fun payText(price: Int, bps: Int, settings: CashStoreSettings?): String {
        val currency = settings?.currency
        if (bps <= 0) return LF("A client pays %@. No tax is charged.", cashMoney(price, currency))
        if (settings?.pricesIncludeTax ?: true) {
            val tax = (price.toDouble() * bps / (10_000 + bps).toDouble()).roundToInt()
            return LF("A client pays %@, which includes %@ of tax.", cashMoney(price, currency), cashMoney(tax, currency))
        }
        val tax = (price.toDouble() * bps / 10_000.0).roundToInt()
        return LF("A client pays %@: %@ plus %@ of tax.", cashMoney(price + tax, currency), cashMoney(price, currency), cashMoney(tax, currency))
    }

    /** A refused stock save the form can put under a field: (form key, sentence). */
    fun stockRefusal(error: Throwable): Pair<String, String>? {
        val api = error as? APIError ?: return null
        return when (api.code) {
            "barcode_taken" -> {
                // The refusal names the product that has it.
                val owner = api.body["itemName"].string.nonEmpty
                "barcode" to (owner?.let { LF("%@ already has this barcode.", it) } ?: api.message.nonEmpty ?: L("Another product already has this barcode."))
            }
            "stock_changed", "counted_from_required" -> "quantity" to (api.message.nonEmpty ?: L("The stock changed since you opened it. Check the count and save again."))
            "stock_on_hand" -> "trackInventory" to (api.message.nonEmpty ?: L("This product still has stock on the shelf. Count it to 0 in Inventory first."))
            else -> null
        }
    }
}

/**
 * How an item is taxed, and what a client pays at that price (web `TaxField`):
 * the four treatments as choices, the own rate beside them, the preview on aqua.
 * Like the Swift view it draws two siblings in the parent's column: the field and the preview.
 */
@Composable
fun CashTaxField(
    treatment: MutableState<String>,
    rate: MutableState<String>,
    price: Int?,
    settings: CashStoreSettings?,
    key: String = "taxRate",
    form: FormState? = null,
    modifier: Modifier = Modifier,
) {
    val standard = settings?.defaultTaxRateBps ?: 0
    FormField(label = L("Tax"), key = key, form = form, modifier = modifier) {
        FlowLayout(spacing = 8.dp) {
            listOf(
                "default" to (if (settings != null) LF("Standard (%@)", cashRate(standard)) else L("Standard")),
                "standard" to L("Own rate"),
                "zero" to L("Zero-rated (0%)"),
                "exempt" to L("Exempt"),
            ).forEach { (value, title) ->
                CashCatalogChoicePill(title = title, isOn = treatment.value == value) {
                    if (value == "standard" && rate.value.isEmpty()) rate.value = CashCatalogRules.rateText(standard)
                    treatment.value = value
                    form?.clear(key)
                }
            }
            if (treatment.value == "standard") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldChrome(invalid = form?.error(key) != null, modifier = Modifier.width(80.dp)) {
                        PlainTextField(
                            rate,
                            keyboard = KeyboardType.Decimal,
                            style = Theme.text(16, FontWeight.Bold).monospacedDigit().copy(textAlign = TextAlign.End),
                            modifier = Modifier.weight(1f).semantics { contentDescription = L("Own tax rate") },
                            onChange = { form?.clear(key) },
                        )
                    }
                    Text("%", style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
                }
            }
        }
    }
    val preview: String? = run {
        if (price == null || price <= 0 || settings == null) return@run null
        val bps = if (treatment.value == "standard") CashCatalogRules.rateBps(rate.value) ?: 0
        else CashCatalogRules.effectiveRate(treatment = treatment.value, rateBps = null, settings = settings)
        CashCatalogRules.payText(price = price, bps = bps, settings = settings)
    }
    if (preview != null) {
        Text(
            preview,
            style = Theme.text(14),
            color = Theme.inkStrong,
            modifier = Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.aqua, 14.0), RoundedCornerShape(Theme.boxRadius))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * One of a row of choices where one is picked (web catalog-sheet-fields `Choice`):
 * 40 tall, a 2 pt edge; chosen = the tone's 18% wash, a deeper edge and a tick.
 */
@Composable
fun CashCatalogChoicePill(
    title: String,
    isOn: Boolean,
    tone: Color = Theme.blue,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val shape = CapsuleShape
    Row(
        modifier
            .heightIn(min = 40.dp)
            .background(if (isOn) Theme.wash(tone, 18.0) else Theme.surface, shape)
            .border(2.dp, if (isOn) Theme.mix(tone, 60.0, Theme.inkStrong) else Theme.borderSoft, shape)
            .clip(shape)
            .plainButton(label = title, onClick = action)
            .semantics { selected = isOn }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Theme.text(14, FontWeight.Bold), color = if (isOn) Theme.inkStrong else Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isOn) Glyph("check", 16.dp, tint = Theme.inkStrong)
    }
}

/** "Sold as a pack of another product": a box of 30 draws 30 from the tablet's stock (web `PackField`). */
@Composable
fun CashPackField(
    /** The product it draws on; null when it is not a pack. */
    stockItemId: MutableState<String?>,
    stockItemName: MutableState<String?>,
    units: MutableState<Int>,
    /** On, with the product still to choose. */
    picking: MutableState<Boolean>,
    excludeId: String?,
    key: String = "packProduct",
    form: FormState? = null,
    modifier: Modifier = Modifier,
) {
    val query = remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<CashProduct>()) }
    var searching by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToggleRow(
            title = L("Sold as a pack of another product"),
            subtitle = L("A box of 30 tablets draws 30 from the tablet’s stock, so stock lives in one place."),
            isOn = stateOf({ stockItemId.value != null || picking.value }) { on ->
                form?.clear(key)
                if (on) {
                    picking.value = true
                } else {
                    picking.value = false
                    stockItemId.value = null
                    stockItemName.value = null
                    units.value = 1
                }
            },
        )
        if (stockItemId.value != null) {
            FlowLayout(spacing = 8.dp) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    Text(L("Each one takes"), style = Theme.text(14), color = Theme.inkSoft)
                }
                FieldChrome(modifier = Modifier.width(80.dp)) {
                    PlainTextField(
                        stateOf({ "${units.value}" }) { typed -> units.value = max(1, typed.filter { it.isDigit() }.toIntOrNull() ?: 1) },
                        prompt = "1",
                        keyboard = KeyboardType.Number,
                        style = Theme.text(16, FontWeight.Bold).monospacedDigit().copy(textAlign = TextAlign.Center),
                        modifier = Modifier.weight(1f).semantics { contentDescription = L("Units per pack") },
                    )
                }
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    Text(L("of"), style = Theme.text(14), color = Theme.inkSoft)
                }
                Row(
                    Modifier
                        .padding(vertical = 4.dp)
                        .heightIn(min = 40.dp)
                        .background(Theme.wash(Theme.blue, 8.0), CapsuleShape)
                        .border(2.dp, Theme.mix(Theme.blue, 55.0, Theme.borderSoft), CapsuleShape)
                        .padding(start = 14.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stockItemName.value ?: L("the product"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Box(
                        Modifier
                            .size(32.dp)
                            .plainButton(label = L("Change")) {
                                stockItemId.value = null
                                stockItemName.value = null
                                picking.value = true
                            },
                        contentAlignment = Alignment.Center,
                    ) { Glyph("x", 14.dp, tint = Theme.inkStrong) }
                }
            }
        } else if (picking.value) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .formScrollTarget(key, form)
                    .background(Theme.surface, RoundedCornerShape(Theme.boxRadius))
                    .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SearchField(prompt = L("Which product holds the stock?"), text = query, shape = SearchField.Shape.field)
                if (results.isEmpty()) {
                    Text(
                        if (searching) L("Searching…") else L("Nothing found."),
                        style = Theme.text(14), color = Theme.inkSoft,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                results.forEach { product ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .plainButton(label = product.name) {
                                form?.clear(key)
                                stockItemId.value = product.catalogItemId
                                stockItemName.value = product.name
                                units.value = if (units.value > 1) units.value else 10
                                picking.value = false
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(product.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(cashMoney(product.unitAmount, product.currency), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                    }
                }
            }
            form?.error(key)?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
        }
    }
    // The one search rule: the server searches 250 ms after typing stops, from two characters.
    val searched = ClientSearch.query(query.value)
    LaunchedEffect(picking.value, searched) {
        if (!picking.value || stockItemId.value != null) return@LaunchedEffect
        delay(ClientSearch.debounce)
        searching = true
        try {
            val found: List<CashProduct> = try {
                API.get("/provider-store/products", mapOf("q" to searched, "type" to "product"))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
            // Only products that can hold stock for a pack: not packs themselves.
            results = found.filter { it.itemType == "product" && it.catalogItemId != excludeId && (it.stock == null || it.stock.unitsPerSale == 1) }.take(ClientSearch.pageSize)
        } finally {
            searching = false
        }
    }
    LaunchedEffect(stockItemId.value) {
        val id = stockItemId.value ?: return@LaunchedEffect
        if (stockItemName.value != null) return@LaunchedEffect
        val item: CashCatalogItem? = try {
            API.get("/provider-catalog/$id")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        stockItemName.value = item?.name
    }
}
