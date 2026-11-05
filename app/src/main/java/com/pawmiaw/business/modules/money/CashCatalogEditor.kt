@file:OptIn(ExperimentalFoundationApi::class)

package com.pawmiaw.business.money

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

//
//  CashCatalogEditor.kt (iOS CashCatalogEditor.swift)
//
//  Add or edit one catalog item (web: provider-catalog-sheet.tsx,
//  catalog-page/catalog-form.ts, catalog-sheet-sections.tsx): its photo,
//  name, category, SKU, kind of product (and, for a medicine, its group or
//  controlled class), price and tax, and — for a product — its stock: counted
//  on the shelf, or drawn from another product as a pack. A new product and
//  its stock go in one request with one request id per sheet, so a save whose
//  answer was lost never makes the item twice; a later save carries on with
//  the item this sheet already made.
//
//  The SKU and the barcode can be scanned (design/BarcodeScanner.kt); a
//  code another item already has is said before saving, and a scanned SKU can
//  be the barcode too. A delivery opens it to create the product that just
//  arrived (the `initialBarcode`/`onCreated` overload, web receive-stock-sheet.tsx →
//  ProviderCatalogSheet preset "product"): it starts with the scanned code as
//  its barcode, loads its own categories and settings, and hands the saved
//  item back so the delivery carries on receiving it.
//

private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

/** One rule of the editor's form: the step it belongs to, the field, and what is wrong (null when fine). */
private class CashCatalogRule(val section: String, val key: String, val message: String?)

/** The editor's state and its work (iOS the sheet's `@State`s and functions). */
@Stable
private class CashCatalogEditorModel(
    val item: CashCatalogItem?,
    /** The kind it opens on; null asks first (web: the "New item" button's first step). */
    val kind: String?,
    val duplicateOf: CashCatalogItem?,
    /** A new product starts with this barcode (a scanned code no product had). */
    val initialBarcode: String?,
    /** Opened from a delivery: a product, its lists loaded here, and words about carrying on receiving it. */
    val forDelivery: Boolean,
) {
    var categories: List<CashCategory> = emptyList()
    var settings: CashStoreSettings? = null
    var onSaved: () -> Unit = {}
    var onCreated: ((CashCatalogItem) -> Unit)? = null
    var dismiss: () -> Unit = {}
    lateinit var scope: CoroutineScope

    val form = FormState()
    val name = mutableStateOf("")
    var categoryId by mutableStateOf("")
    val sku = mutableStateOf("")
    val price = mutableStateOf<Int?>(null)
    val description = mutableStateOf("")
    val active = mutableStateOf(true)
    val taxTreatment = mutableStateOf("default")
    val taxRate = mutableStateOf("")
    var productKind by mutableStateOf("")
    var requiresPrescription by mutableStateOf(false)
    var controlSchedule by mutableStateOf<String?>(null)
    var trackInventory by mutableStateOf(false)
    val barcode = mutableStateOf("")
    val storageLocation = mutableStateOf("")
    val unitCost = mutableStateOf<Int?>(null)
    val quantity = mutableStateOf("")
    var countedFrom by mutableStateOf<Int?>(null)
    var lowStockAlert by mutableStateOf(true)
    val reorderPoint = mutableStateOf("3")
    val reorderQuantity = mutableStateOf("")
    val allowBackorder = mutableStateOf(false)
    val packId = mutableStateOf<String?>(null)
    val packName = mutableStateOf<String?>(null)
    val packUnits = mutableStateOf(1)
    val pickingPack = mutableStateOf(false)
    val photo = mutableStateOf(PhotoDraft())
    val newCategory = mutableStateOf("")
    var addingCategory by mutableStateOf(false)
    var extraCategories by mutableStateOf(listOf<CashCategory>())
    var working by mutableStateOf(false)
    var started by mutableStateOf(false)
    /** A SKU the camera read: offered as the barcode too. */
    var skuScanned by mutableStateOf<String?>(null)
    /** Another item with the SKU or the barcode typed or scanned here (code, its name), said before saving. */
    var skuTwin by mutableStateOf<Pair<String, String>?>(null)
    var barcodeTwin by mutableStateOf<Pair<String, String>?>(null)
    var loadedCategories by mutableStateOf(listOf<CashCategory>())
    var loadedSettings by mutableStateOf<CashStoreSettings?>(null)
    val requestId = "catalog-${cashRequestId()}"
    /** (key, id): the same figures keep their request id. */
    var stockRequest: Pair<String, String>? = null
    /** The item this sheet already made: a second save carries on with it. */
    var madeId by mutableStateOf<String?>(null)
    /** The kind chosen on the first step of a "New item". */
    var chosenKind by mutableStateOf<String?>(null)
    /** The part of a new item shown ("kind", "details", "price", "stock"). */
    var step by mutableStateOf("details")
    /** The item is saved but its stock settings are not: the button tries that part again. */
    var partlySaved by mutableStateOf(false)
    /** "Jump to a section" (iOS `form.scrollTarget = "section-…"`). */
    var jumpTarget by mutableStateOf<String?>(null)
    var jumpRequest by mutableIntStateOf(0)

    private val workspace get() = Workspace.shared

    /** The kind being written: the one it opened on, or the one chosen. */
    val itemKind: String get() = kind ?: chosenKind ?: "service"
    val kindChosen: Boolean get() = kind != null || chosenKind != null
    val isProduct: Boolean get() = itemKind == "product"
    val canEditInventory: Boolean get() = workspace.can(Permission.inventoryEdit)
    val isPack: Boolean get() = isProduct && packId.value != null
    val packPending: Boolean get() = isProduct && pickingPack.value && packId.value == null
    val tracksOwnStock: Boolean get() = isProduct && trackInventory && !isPack && !packPending
    val medication: Boolean get() = isProduct && productKind == "medication"
    val controlled: Boolean get() = medication && controlSchedule != null
    val mexico: Boolean get() = workspace.current?.country?.uppercase() == "MX"
    val seesCost: Boolean get() = workspace.moneyLevel >= MoneyLevel.finances || workspace.can(Permission.catalogEdit)
    val storeSettings: CashStoreSettings? get() = settings ?: loadedSettings
    val typeCategories: List<CashCategory>
        get() = (categories + loadedCategories + extraCategories).filter { it.itemType == itemKind && (it.active || it.id == categoryId) }

    /** A new item's steps (web: "kind" unless the caller fixed it, then its parts); an item being edited has none. */
    val sections: List<String> get() = if (isProduct) listOf("details", "price", "stock") else listOf("details", "price")
    val steps: List<String> get() = if (item != null) emptyList() else (if (kind == null) listOf("kind") else emptyList()) + sections
    val stepIndex: Int get() = max(0, steps.indexOf(step))
    val isLastStep: Boolean get() = item != null || stepIndex == steps.size - 1
    val choosing: Boolean get() = item == null && step == "kind"

    val primaryTitle: String
        get() {
            if (!(isLastStep && !choosing)) return L("Continue")
            if (partlySaved) return L("Try again")
            return if (item == null && madeId == null) (if (isProduct) L("Add product") else L("Add service")) else L("Save changes")
        }

    val dirty: Boolean
        get() = name.value != (item?.name ?: "") ||
            price.value != item?.let { if (it.defaultUnitAmount > 0) it.defaultUnitAmount else null } ||
            description.value.isNotEmpty() && description.value != (item?.description ?: "") ||
            sku.value != (item?.sku ?: "") || !photo.value.isEmpty || packId.value != item?.stockCatalogItemId ||
            barcode.value != (item?.inventory?.barcode ?: (if (item == null) initialBarcode ?: "" else ""))

    fun jump(section: String) {
        jumpTarget = "section-$section"
        jumpRequest += 1
    }

    /** What is checked, part by part (web `validateCatalogForm` + `SECTION_OF_ERROR`), in the order the fields appear. */
    val rules: List<CashCatalogRule>
        get() {
            val ownRate = if (taxTreatment.value == "standard") CashCatalogRules.rateBps(taxRate.value) else null
            val stockChecked = tracksOwnStock && canEditInventory
            return listOf(
                CashCatalogRule("details", "name", if (name.value.nonEmpty == null) L("Add the name first.") else null),
                CashCatalogRule(
                    "details", "categoryName",
                    if (addingCategory) (if (newCategory.value.nonEmpty == null) L("Add the category name first.") else L("Save the new category first or cancel it before saving this item.")) else null,
                ),
                CashCatalogRule("price", "price", CashCatalogRules.priceError(price.value, item = item)),
                CashCatalogRule("price", "taxRate", if (taxTreatment.value == "standard" && ownRate == null) L("Enter a tax rate from 0 to 100.") else null),
                CashCatalogRule("stock", "packProduct", if (packPending) L("Choose the product this pack draws on, or turn the pack off.") else null),
                CashCatalogRule(
                    "stock", "quantity",
                    if (stockChecked && !controlled) (if (quantity.value.isEmpty()) L("Add the starting quantity on hand.") else if (quantity.value.toIntOrNull() == null) L("Add a valid stock count.") else null) else null,
                ),
                CashCatalogRule("stock", "reorderPoint", if (stockChecked && lowStockAlert && reorderPoint.value.toIntOrNull() == null) L("Add the reorder point.") else null),
                CashCatalogRule("stock", "reorderQuantity", if (stockChecked && reorderQuantity.value.isNotEmpty() && reorderQuantity.value.toIntOrNull() == null) L("Add a valid reorder quantity.") else null),
            )
        }

    /** Next (web `goNext`): the step's own fields are checked before the next one opens. */
    fun primary() {
        if (choosing) {
            if (kindChosen) step = "details"
            return
        }
        if (isLastStep) {
            save()
            return
        }
        if (!form.check(rules.filter { it.section == step }.map { it.key to it.message })) return
        step = steps[stepIndex + 1]
    }

    fun chooseKind(type: String) {
        if (chosenKind != null && chosenKind != type) {
            // Categories belong to services or to products.
            categoryId = ""
            addingCategory = false
            newCategory.value = ""
        }
        if (type != "product") pickingPack.value = false
        chosenKind = type
        if (type == "product") trackInventory = true
        step = "details"
    }

    fun start() {
        if (started) return
        step = if (kind == null && item == null) "kind" else "details"
        val source = item ?: duplicateOf
        name.value = item?.name ?: duplicateOf?.let { LF("%@ (copy)", it.name).take(140) } ?: ""
        categoryId = source?.category?.id ?: ""
        sku.value = item?.sku ?: ""
        price.value = source?.let { if (it.defaultUnitAmount > 0) it.defaultUnitAmount else null }
        description.value = source?.description ?: ""
        active.value = item?.active ?: true
        taxTreatment.value = source?.taxTreatment ?: "default"
        taxRate.value = if (source?.taxTreatment == "standard") CashCatalogRules.rateText(source.taxRateBps) else ""
        productKind = source?.productKind ?: ""
        requiresPrescription = source?.requiresPrescription ?: false
        controlSchedule = source?.controlSchedule
        trackInventory = source?.trackInventory ?: (kind == "product")
        packId.value = source?.stockCatalogItemId
        packUnits.value = max(1, source?.stockUnits ?: 1)
        val inventory = item?.inventory
        val firstBarcode = initialBarcode.nonEmpty
        if (inventory != null) {
            barcode.value = inventory.barcode ?: ""
            storageLocation.value = inventory.storageLocation ?: ""
            unitCost.value = inventory.unitCostAmount
            quantity.value = "${inventory.quantityOnHand}"
            countedFrom = inventory.quantityOnHand
            lowStockAlert = inventory.lowStockAlert != false
            reorderPoint.value = "${if (inventory.reorderPoint > 0) inventory.reorderPoint else 3}"
            reorderQuantity.value = inventory.reorderQuantity?.toString() ?: ""
            allowBackorder.value = inventory.allowBackorder
        } else if (item == null && firstBarcode != null) {
            barcode.value = firstBarcode
            scope.launch { checkTwin(firstBarcode, format = null, isSku = false) }
        }
        started = true
    }

    /** Opened from a delivery: the categories and the Store's tax settings, which the Catalog would have handed over. */
    suspend fun loadLists() {
        if (!forDelivery) return
        if (categories.isEmpty()) attempt { API.get<CashCategoryList>("/provider-catalog/categories") }?.let { loadedCategories = it.items }
        if (settings == null) loadedSettings = attempt { API.get<CashStoreSettings>("/provider-store/settings") }
    }

    /**
     * Another item already has this code: said here, before the server refuses it on save
     * (web useDuplicateCode). A barcode is looked up in each of its spellings; no warning is better than a wrong one.
     */
    suspend fun checkTwin(code: String, format: String?, isSku: Boolean) {
        if (isSku) skuTwin = null else barcodeTwin = null
        val own = item?.id ?: madeId
        suspend fun search(text: String): List<CashProduct> = API.get("/provider-store/products", mapOf("q" to text))
        if (isSku) {
            val found = attempt { search(code) }?.firstOrNull { it.catalogItemId != own && it.sku?.trim() == code } ?: return
            if (sku.value == code) skuTwin = code to found.name
            return
        }
        for (variant in Barcode.variants(code, format)) {
            val rows = attempt { search(variant) } ?: return
            val found = rows.firstOrNull { it.catalogItemId != own && Barcode.same(it.barcode, variant) }
            if (found != null) {
                if (barcode.value == code) barcodeTwin = code to found.name
                return
            }
        }
    }

    fun saveCategory() {
        val text = newCategory.value.nonEmpty
        if (text == null) {
            form.check(listOf("categoryName" to L("Add the category name first.")))
            return
        }
        scope.launch {
            try {
                val created: CashCategory = API.post("/provider-catalog/categories", mapOf("itemType" to itemKind, "name" to text, "active" to true))
                extraCategories = extraCategories + created
                categoryId = created.id
                addingCategory = false
                newCategory.value = ""
                Toast.show(LF("%@ is ready to use.", created.name))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                form.apply(failure, known = mapOf("name" to "categoryName"))
            }
        }
    }

    fun save() {
        val ownRate = if (taxTreatment.value == "standard") CashCatalogRules.rateBps(taxRate.value) else null
        val stockChecked = tracksOwnStock && canEditInventory
        val existing = item?.id ?: madeId
        val checks = rules
        // A new item goes back to the first step that has something to fix.
        if (item == null) checks.firstOrNull { it.message != null }?.let { first -> if (first.section in sections) step = first.section }
        if (!form.check(checks.map { it.key to it.message })) return
        val payload = linkedMapOf<String, Any?>(
            "itemType" to itemKind,
            "name" to name.value.trim(),
            "providerCatalogCategoryId" to categoryId.nonEmpty,
            "sku" to sku.value.nonEmpty,
            "defaultUnitAmount" to (price.value ?: 0),
            "description" to description.value.nonEmpty,
            "active" to active.value,
            "trackInventory" to (if (isProduct && !isPack) trackInventory else false),
            "productKind" to (if (isProduct) productKind.nonEmpty else null),
            "taxTreatment" to taxTreatment.value,
            "taxRateBps" to ownRate,
            "requiresPrescription" to (if (medication) (requiresPrescription || controlled) else false),
            "controlSchedule" to (if (medication) controlSchedule else null),
            "stockCatalogItemId" to (if (isProduct) packId.value else null),
            "stockUnits" to (if (isPack) packUnits.value else 1),
        )
        var inventory: Map<String, Any?>? = null
        if (stockChecked) {
            val stock = linkedMapOf<String, Any?>(
                "barcode" to barcode.value.nonEmpty,
                "storageLocation" to storageLocation.value.nonEmpty,
                "quantityOnHand" to (if (controlled) (item?.inventory?.quantityOnHand ?: 0) else quantity.value.toIntOrNull() ?: 0),
                "reorderPoint" to (if (lowStockAlert) reorderPoint.value.toIntOrNull() ?: 0 else 0),
                "lowStockAlert" to lowStockAlert,
                "reorderQuantity" to reorderQuantity.value.toIntOrNull(),
                "allowBackorder" to allowBackorder.value,
            )
            if (seesCost) stock["unitCostAmount"] = unitCost.value
            val saved = item?.inventory
            if (saved != null) {
                // With stock set up, a count is what differs from what the form showed; the same figures keep their id.
                stock["countedFrom"] = countedFrom ?: saved.quantityOnHand
                val key = "${stock["quantityOnHand"] as? Int ?: 0}|${countedFrom ?: 0}"
                if (stockRequest?.first != key) stockRequest = key to "catalog-${cashRequestId()}"
                stock["clientRequestId"] = stockRequest?.second
            }
            inventory = stock
        }
        working = true
        scope.launch {
            try {
                val savedJSON: JSONValue
                if (existing != null) {
                    savedJSON = API.patch("/provider-catalog/$existing", payload)
                } else {
                    val body = LinkedHashMap(payload)
                    body["clientRequestId"] = requestId
                    if (inventory != null) body["inventory"] = inventory
                    savedJSON = API.post("/provider-catalog", body)
                    madeId = savedJSON["id"].string
                }
                val savedId = savedJSON["id"].string ?: existing ?: throw APIError.offline()
                if (existing != null && inventory != null) {
                    try {
                        API.patch<EmptyResponse>("/provider-catalog/$savedId/inventory", inventory)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        if (showStockRefusal(failure)) return@launch
                        // The item is saved; only its stock settings are not. Trying again carries on from here.
                        partlySaved = true
                        announceDataChanged()
                        onSaved()
                        Toast.error(L("Saved the product, but its stock could not be set. Try again."))
                        return@launch
                    }
                }
                partlySaved = false
                applyPhoto(itemId = savedId)
                Toast.show(if (item == null) LF("%@ was added to the catalog.", name.value) else LF("%@ was updated.", name.value))
                onCreated?.let { created ->
                    // As saved, photo included; the save's own answer when that can't be read.
                    val fresh: CashCatalogItem? = attempt { API.get<CashCatalogItem>("/provider-catalog/$savedId") }
                        ?: attempt { API.json.decodeFromJsonElement(CashCatalogItem.serializer(), savedJSON) }
                    if (fresh != null) created(fresh)
                }
                announceDataChanged()
                onSaved()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (showStockRefusal(failure)) return@launch
                if (cashErrorCode(failure) == "sku_taken") {
                    if (item == null) step = "details"
                    form.check(listOf("sku" to errorText(failure)))
                    return@launch
                }
                if (existing == null && (failure as? APIError)?.isOffline == true) {
                    // The request id makes a second try safe.
                    Toast.error(L("We could not reach PawMiaw, so this may not be saved. Try again — it is never added twice."))
                    return@launch
                }
                form.apply(
                    failure,
                    known = mapOf(
                        "name" to "name", "sku" to "sku", "defaultUnitAmount" to "price", "taxRateBps" to "taxRate", "barcode" to "barcode",
                        "quantityOnHand" to "quantity", "reorderPoint" to "reorderPoint", "stockCatalogItemId" to "packProduct",
                    ),
                )
            } finally {
                working = false
            }
        }
    }

    /** Sends the photo picked (or takes the saved one away) once the item exists. */
    private suspend fun applyPhoto(itemId: String) {
        val data = photo.value.data
        if (data != null) {
            try {
                ImageUpload.upload<EmptyResponse>(data, to = "/provider-catalog/$itemId/photo")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Toast.error(L("The item was saved, but the photo did not upload. You can add it again later."))
            }
        } else if (photo.value.removed && item?.photoUrl != null) {
            try {
                API.send("DELETE", "/provider-catalog/$itemId/photo")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Toast.error(L("The item was saved, but its photo could not be removed. Try again later."))
            }
        }
    }

    /**
     * A refusal about the stock part, under its field: a barcode another product has, a
     * count from a figure the shelf no longer holds (the new one is counted from next), units still on the shelf.
     */
    private fun showStockRefusal(error: Throwable): Boolean {
        val refusal = CashCatalogRules.stockRefusal(error) ?: return false
        if (item == null && "stock" in sections) step = "stock"
        var field = refusal.first
        if (field == "trackInventory" && (pickingPack.value || packId.value != null)) field = "packProduct"
        val id = item?.id ?: madeId
        if (field == "quantity" && id != null) {
            stockRequest = null
            scope.launch {
                attempt { API.get<CashCatalogItem>("/provider-catalog/$id") }?.let { fresh -> countedFrom = fresh.inventory?.quantityOnHand }
            }
        }
        form.check(listOf(field to refusal.second))
        return true
    }
}

/** Where "Jump to a section" brings the page (iOS `.id("section-…")`). */
private fun Modifier.jumpTarget(id: String, m: CashCatalogEditorModel): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val request = m.jumpRequest
    val seen = remember { intArrayOf(request) }
    LaunchedEffect(request) {
        if (request == seen[0]) return@LaunchedEffect
        seen[0] = request
        if (m.jumpTarget == id) requester.bringIntoView()
    }
    this.bringIntoViewRequester(requester)
}

@Composable
fun CashCatalogEditorSheet(
    item: CashCatalogItem?,
    /** The kind it opens on; null asks first (web: the "New item" button's first step). */
    kind: String?,
    duplicateOf: CashCatalogItem?,
    categories: List<CashCategory>,
    settings: CashStoreSettings?,
    onSaved: () -> Unit,
    /** A new product starts with this barcode (a scanned code no product had). */
    initialBarcode: String? = null,
    /** The item as the server saved it, before the sheet closes (for whoever carries on with it). */
    onCreated: ((CashCatalogItem) -> Unit)? = null,
    /** Opened from a delivery: a product, its lists loaded here, and words about carrying on receiving it. */
    forDelivery: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val m = remember { CashCatalogEditorModel(item = item, kind = kind, duplicateOf = duplicateOf, initialBarcode = initialBarcode, forDelivery = forDelivery) }
    m.categories = categories
    m.settings = settings
    m.onSaved = onSaved
    m.onCreated = onCreated
    m.dismiss = LocalDismiss.current
    m.scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        m.start()
        m.loadLists()
    }

    val steps = m.steps
    val stepIndex = m.stepIndex
    ProviderModal(
        title = item?.let { LF("Edit %@", it.name) } ?: (if (!m.kindChosen) L("New item") else if (m.isProduct) L("New product") else L("New service")),
        subtitle = if (!m.kindChosen) L("A service or a product: choose which, then fill in its details.")
        else if (m.isProduct) L("Its price and tax, what kind of product it is, and the stock behind it.")
        else L("What a visit, a groom or a night costs, ready for invoices and the counter."),
        systemImage = if (m.kindChosen) CashCatalogLook.kindIcon(m.itemKind) else "plus",
        accent = if (m.kindChosen) ModalAccent.hue(CashCatalogLook.hue(m.itemKind)) else ModalAccent.butter,
        isDirty = m.started && (m.dirty || m.chosenKind != null || (item == null && stepIndex > 0)),
        form = m.form,
        primaryTitle = m.primaryTitle,
        isWorking = m.working,
        cancelTitle = if (m.partlySaved) L("Close") else L("Cancel"),
        steps = if (steps.size > 1) ModalSteps(steps = steps.map { stepName(it) }, icons = steps.map { stepIcon(it) }, current = stepIndex) else null,
        onBack = if (item == null && stepIndex > 0) ({ m.step = steps[stepIndex - 1] }) else null,
        primaryIcon = if (m.isLastStep && !m.choosing) (if (m.partlySaved) "rotate-cw" else if (item != null) "save" else "plus") else null,
        primaryDisabled = m.choosing && !m.kindChosen,
        onPrimary = { m.primary() },
        modifier = modifier,
    ) {
        if (!m.started) {
            LoadingState()
        } else if (m.choosing) {
            KindStep(m)
        } else {
            ContextPills(m)
            if (item != null) Jumps(m)
            if (forDelivery) {
                Text(L("It is added to the Catalog and you carry on receiving it."), style = Theme.text(14), color = Theme.inkSoft)
            }
            if (item != null) {
                DetailsSection(m)
                PriceSection(m)
                if (m.isProduct) StockSection(m)
                AvailabilitySection(m)
            } else {
                when (m.step) {
                    "price" -> PriceSection(m)
                    "stock" -> StockSection(m)
                    else -> DetailsSection(m)
                }
                if (m.isLastStep) AvailabilitySection(m)
            }
        }
    }
}

/**
 * "Crear el producto" from a delivery: a new product starting with the code that matched
 * nothing, handed back once saved so the delivery carries on receiving it (iOS `init(initialBarcode:onCreated:)`).
 */
@Composable
fun CashCatalogEditorSheet(initialBarcode: String?, modifier: Modifier = Modifier, onCreated: (CashCatalogItem) -> Unit) {
    CashCatalogEditorSheet(
        item = null, kind = "product", duplicateOf = null, categories = emptyList(), settings = null, onSaved = {},
        initialBarcode = initialBarcode, onCreated = onCreated, forDelivery = true, modifier = modifier,
    )
}

private fun stepName(value: String): String = when (value) {
    "kind" -> L("Kind")
    "details" -> L("What it is")
    "price" -> L("Price and tax")
    else -> L("Stock")
}

private fun stepIcon(value: String): String = when (value) {
    "kind" -> "shapes"
    "details" -> "tag"
    "price" -> "banknote"
    else -> "boxes"
}

/** Editing is one page: these bring each part into view (web: the row of jumps). */
@Composable
private fun Jumps(m: CashCatalogEditorModel) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = L("Jump to a section") },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (m.sections + "availability").forEach { section ->
            FilterChip(title = if (section == "availability") L("Availability") else stepName(section), isSelected = false) { m.jump(section) }
        }
    }
}

/** "What are you adding?" (web `CatalogKindStep`, an `OptionCardGrid`): a service or a product; a tap opens its form. */
@Composable
private fun KindStep(m: CashCatalogEditorModel) {
    ModalSection(title = L("What are you adding?"), subtitle = L("Each kind has its own details: a product has stock.")) {
        OptionCards(
            options = listOf("service", "product").map { type ->
                OptionCard(
                    type,
                    title = if (type == "product") L("Product") else L("Service"),
                    description = if (type == "product") L("Food, medicine or supplies, with stock") else L("A visit, a groom, a night — what you charge for"),
                    icon = CashCatalogLook.kindIcon(type),
                    accent = CashCatalogLook.hue(type).color,
                )
            },
            selection = stateOf<String?>({ if (m.kindChosen) m.itemKind else null }) { next -> if (next != null) m.chooseKind(next) },
        )
    }
}

/** What it is about (web `ModalContextPills`): the kind and, when editing or copying, the item. */
@Composable
private fun ContextPills(m: CashCatalogEditorModel) {
    FlowLayout(spacing = 6.dp) {
        Chip(text = if (m.isProduct) L("Product") else L("Service"), tone = Chip.Tone.muted, icon = CashCatalogLook.kindIcon(m.itemKind))
        val item = m.item
        val typed = m.name.value.nonEmpty
        if (item != null) {
            Chip(text = item.name, tone = Chip.Tone.muted)
        } else if (typed != null && m.step != "details") {
            Chip(text = typed, tone = Chip.Tone.muted)
        }
        m.duplicateOf?.let { Chip(text = LF("Copy of %@", it.name), tone = Chip.Tone.muted, icon = "copy") }
    }
}

@Composable
private fun DetailsSection(m: CashCatalogEditorModel) {
    val form = m.form
    ModalSection(title = L("What it is"), modifier = Modifier.jumpTarget("section-details", m)) {
        PhotoField(
            label = L("Photo"), hint = L("JPG, PNG or WebP, up to 3 MB."), currentURL = m.item?.photoUrl, draft = m.photo,
            removeTitle = L("Remove the new photo"), removeSavedTitle = L("Remove photo"), removedNote = L("The photo is removed when you save."),
        )
        PMTextField(
            L("Name"), text = m.name, key = "name", form = form,
            prompt = if (m.isProduct) L("e.g. Hypoallergenic dog food 2 kg") else L("e.g. Annual wellness exam"), required = true,
        )
        val typeCategories = m.typeCategories
        FormField(label = L("Category"), key = "category", form = form) {
            AppMenu(
                label = {
                    FieldChrome {
                        Text(typeCategories.firstOrNull { it.id == m.categoryId }?.name ?: L("Uncategorized"), style = Theme.text(14), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(L("Uncategorized")) { m.categoryId = "" }
                typeCategories.forEach { entry ->
                    item(if (entry.active) entry.name else "${entry.name} (${L("inactive")})") { m.categoryId = entry.id }
                }
                divider()
                item(L("+ New category"), icon = "plus") { m.addingCategory = true }
            }
        }
        if (m.addingCategory) {
            PMTextField(
                if (m.isProduct) L("New product category") else L("New service category"), text = m.newCategory, key = "categoryName", form = form,
                prompt = if (m.isProduct) L("e.g. Food") else L("e.g. Grooming"), required = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PMButton(L("Cancel"), variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true, modifier = Modifier.weight(1f)) {
                    m.addingCategory = false
                    m.newCategory.value = ""
                }
                PMButton(L("Save category"), systemImage = "plus", size = ButtonSize.compact, fullWidth = true, modifier = Modifier.weight(1f)) { m.saveCategory() }
            }
        }
        if (m.isProduct) {
            BarcodeField(
                label = L("SKU"), text = m.sku, key = "sku", form = form, prompt = L("e.g. DF-2KG"), hint = L("Optional. Search finds it too."),
                capitalization = KeyboardCapitalization.Characters,
            ) { scan ->
                m.skuScanned = scan.code
                m.scope.launch { m.checkTwin(scan.code, format = scan.format, isSku = true) }
            }
            m.skuTwin?.takeIf { it.first == m.sku.value }?.let { twin ->
                NoticeCard(tone = NoticeCard.Tone.warning, title = LF("%@ already uses this SKU.", twin.second), message = L("Two items cannot share a SKU: change it, or it will not be saved."))
            }
            val scanned = m.skuScanned
            if (scanned != null && scanned == m.sku.value && m.tracksOwnStock && m.canEditInventory) {
                if (m.barcode.value.trim().isEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(LF("Scanned %@.", scanned), style = Theme.text(12), color = Theme.inkSoft)
                        Box(
                            Modifier.heightIn(min = 40.dp).plainButton(label = L("Also use it as the barcode")) {
                                m.barcode.value = scanned
                                m.scope.launch { m.checkTwin(scanned, format = null, isSku = false) }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text(L("Also use it as the barcode"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue) }
                    }
                } else if (m.barcode.value == scanned) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("check", 14.dp, tint = Theme.inkSoft)
                        Text(L("It is the barcode too."), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
            }
        } else {
            PMTextField(
                L("Internal code"), text = m.sku, key = "sku", form = form, prompt = L("e.g. CONS-01"), hint = L("Optional. Search finds it too."),
                capitalization = KeyboardCapitalization.Characters,
            )
        }
        if (m.isProduct) {
            CashProductKindField(kind = stateOf({ m.productKind }) { value ->
                m.productKind = value
                if (value != "medication") {
                    m.controlSchedule = null
                    m.requiresPrescription = false
                }
            })
            if (m.medication) {
                if (m.mexico) MexicoGroups(m) else ControlledField(m)
            }
        }
        PMTextField(L("Description"), text = m.description, prompt = L("Optional notes for the team: what it includes, when to offer it."), multiline = true)
    }
}

/** The price, in the business currency, and how it is taxed (web `CatalogPriceSection`). */
@Composable
private fun PriceSection(m: CashCatalogEditorModel) {
    val keepsNoPrice = m.item?.defaultUnitAmount == 0
    val lead = if (keepsNoPrice && m.price.value == null) L("No price yet: it will be asked for when charging. Add one here to stop asking.")
    else L("What staff see first on an invoice or at the counter. It can be changed on each sale.")
    ModalSection(title = L("Price and tax"), modifier = Modifier.jumpTarget("section-price", m)) {
        MoneyField(
            label = L("Price"), cents = m.price, key = "price", form = m.form,
            hint = lead + " " + LF("In %@, the business currency, set in Settings.", m.storeSettings?.currency ?: Workspace.shared.currency),
            required = !keepsNoPrice,
        )
        CashTaxField(treatment = m.taxTreatment, rate = m.taxRate, price = m.price.value, settings = m.storeSettings, form = m.form)
    }
}

/** Whether it is offered on new sales (web `CatalogAvailabilitySection`). */
@Composable
private fun AvailabilitySection(m: CashCatalogEditorModel) {
    ModalSection(title = L("Availability"), modifier = Modifier.jumpTarget("section-availability", m)) {
        ToggleRow(
            title = if (m.active.value) L("Active") else L("Archived"),
            subtitle = if (m.active.value) L("Offered on new invoices, visits and at the counter.") else L("Hidden from new sales. Past invoices keep it, and you can turn it back on any time."),
            isOn = m.active,
        )
    }
}

/** Mexico: the box says Grupo I, II or III — one choice sets both rules (web `MexicoGroupField`). */
@Composable
private fun MexicoGroups(m: CashCatalogEditorModel) {
    val current = if (m.controlSchedule == "mx_group_1") "group_1" else if (m.requiresPrescription) "group_2" else "group_3"
    class Group(val value: String, val title: String, val body: String, val tone: Color)
    val groups = listOf(
        Group("group_1", L("Group I · quantified prescription"), L("Controlled: sold only against its own prescription on the official pad, and every unit goes to the control log."), Theme.destructive),
        Group("group_2", L("Group II · simple prescription"), L("Prescription only: sold to a client for a specific pet, never over the counter to a walk-in."), Theme.pink),
        Group("group_3", L("Group III · free sale"), L("Sold over the counter, with no prescription."), ProviderState.done.color),
    )
    FormField(label = L("Group (NOM-064)"), hint = L("It is printed on the box: it decides whether it needs a prescription and whether it is controlled.")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                val chosen = current == group.value
                val shape = RoundedCornerShape(Theme.boxRadius)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (chosen) Theme.wash(group.tone, 12.0) else Theme.surface, shape)
                        .border(2.dp, if (chosen) Theme.mix(group.tone, 60.0, Theme.inkStrong) else Theme.borderSoft, shape)
                        .clip(shape)
                        .plainButton(label = group.title) {
                            m.controlSchedule = if (group.value == "group_1") "mx_group_1" else null
                            m.requiresPrescription = group.value != "group_3"
                        }
                        .semantics { selected = chosen }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(group.tone, CircleShape))
                        Text(group.title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    }
                    Text(group.body, style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun ControlledField(m: CashCatalogEditorModel) {
    ToggleRow(
        title = L("Prescription only"),
        subtitle = if (m.controlled) L("A controlled medicine: always prescription-only.") else L("Only sold to a client for a specific pet, never over the counter to a walk-in."),
        isOn = stateOf({ m.requiresPrescription || m.controlled }) { m.requiresPrescription = it },
    )
    MenuField(
        label = L("Controlled medicine"),
        options = listOf("" to L("Not controlled")) + CashCatalogText.schedules.map { it to CashCatalogText.schedule(it) },
        selection = stateOf({ m.controlSchedule ?: "" }) { value ->
            m.controlSchedule = value.nonEmpty
            if (value.nonEmpty != null) m.requiresPrescription = true
        },
    )
    Text(L("Sold only against a controlled prescription; every unit goes to the control log."), style = Theme.text(12), color = Theme.inkSoft)
}

@Composable
private fun StockSection(m: CashCatalogEditorModel) {
    val form = m.form
    val item = m.item
    ModalSection(title = L("Stock"), modifier = Modifier.jumpTarget("section-stock", m)) {
        CashPackField(stockItemId = m.packId, stockItemName = m.packName, units = m.packUnits, picking = m.pickingPack, excludeId = item?.id, form = form)
        if (!m.isPack && !m.pickingPack.value) {
            Box(Modifier.formScrollTarget("trackInventory", form)) {
                ToggleRow(
                    title = L("Track stock"),
                    subtitle = L("Count what is on the shelf, get a warning at the reorder point, and log every sale and delivery."),
                    isOn = stateOf({ m.trackInventory }) { value ->
                        m.trackInventory = value
                        form.clear("trackInventory")
                    },
                )
            }
            form.error("trackInventory")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
        }
        if (m.forDelivery && m.tracksOwnStock && !m.canEditInventory) {
            Text(L("Its stock settings are set by someone who can change Inventory. Receiving this delivery starts counting it."), style = Theme.text(12), color = Theme.inkSoft)
        }
        if (m.tracksOwnStock && m.canEditInventory) {
            if (m.controlled) {
                NoticeCard(
                    tone = NoticeCard.Tone.custom(Theme.pink), title = L("Controlled medicine: its stock is not typed here."),
                    message = if (item?.inventory != null) L("It is received, counted and written off in Inventory, where each unit has its lot and expiry for the controlled register.")
                    else L("Save it, then receive it in Inventory with its lot and expiry: that is what opens its controlled register."),
                    icon = null,
                )
                Disabled(true) {
                    PMTextField(
                        if (item?.inventory == null) L("Starting quantity") else L("Quantity on hand"),
                        text = constantState("${item?.inventory?.quantityOnHand ?: 0}"),
                    )
                }
            } else {
                PMTextField(
                    if (item?.inventory == null) L("Starting quantity") else L("Quantity on hand"), text = m.quantity, key = "quantity", form = form, prompt = "12",
                    hint = if (m.forDelivery) L("What is on the shelf before this delivery: usually 0.") else if (item?.inventory != null) L("Changing it records a count.") else null,
                    keyboard = KeyboardType.Number, required = true,
                )
            }
            if (m.lowStockAlert) {
                PMTextField(
                    L("Reorder point"), text = m.reorderPoint, key = "reorderPoint", form = form, prompt = "3",
                    hint = L("At or below this, it shows as needing restocking."), keyboard = KeyboardType.Number, required = true,
                )
            }
            PMTextField(L("Reorder quantity"), text = m.reorderQuantity, key = "reorderQuantity", form = form, prompt = "12", hint = L("How many you usually order."), keyboard = KeyboardType.Number)
            if (m.seesCost) MoneyField(label = L("Unit cost"), cents = m.unitCost, key = "unitCost", form = form, hint = L("What you pay per unit; it values your stock."))
            BarcodeField(label = L("Barcode"), text = m.barcode, key = "barcode", form = form, prompt = L("Scan or type it")) { scan ->
                m.scope.launch { m.checkTwin(scan.code, format = scan.format, isSku = false) }
            }
            m.barcodeTwin?.takeIf { it.first == m.barcode.value && form.error("barcode") == null }?.let { twin ->
                NoticeCard(tone = NoticeCard.Tone.warning, title = LF("%@ already has this barcode.", twin.second), message = L("Two products cannot share a barcode: change it, or it will not be saved."))
            }
            PMTextField(L("Where it is kept"), text = m.storageLocation, prompt = L("e.g. Front shelf A2"))
            ToggleRow(
                title = L("Mark it low on Home and Inventory"),
                subtitle = if (m.lowStockAlert) L("It shows as needing restocking at the reorder point, here, at the counter and on Home.") else L("Never flagged as low: it only shows once it is out of stock."),
                isOn = stateOf({ m.lowStockAlert }) { on ->
                    m.lowStockAlert = on
                    if (on && m.reorderPoint.value.trim().isEmpty()) m.reorderPoint.value = "3"
                    form.clear("reorderPoint")
                },
            )
            ToggleRow(title = L("Allow backorders"), subtitle = L("Keep selling when the count reaches zero. Leave it off to stop at zero."), isOn = m.allowBackorder)
        } else if (m.tracksOwnStock && !m.forDelivery) {
            Text(
                L("Stock is tracked for this product, but your role cannot change stock settings here."),
                style = Theme.text(14), color = Theme.inkSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/** The catalog's words for controlled classes. */
object CashCatalogText {
    val schedules = listOf("mx_group_1", "us_c2", "us_c3", "us_c4", "us_c5", "controlled_other")

    fun schedule(value: String): String = when (value) {
        "mx_group_1" -> L("Group I (quantified prescription)")
        "us_c2" -> L("Schedule II (C-II)")
        "us_c3" -> L("Schedule III (C-III)")
        "us_c4" -> L("Schedule IV (C-IV)")
        "us_c5" -> L("Schedule V (C-V)")
        else -> L("Controlled")
    }
}

/**
 * What kind of product it is (web `ProductKindField`): one of a row of
 * choices, each with its icon on a chip in its colour; a second tap clears it.
 */
@Composable
private fun CashProductKindField(kind: MutableState<String>) {
    val kinds = listOf("medication", "food", "supply", "accessory", "other")
    FormField(
        label = L("Kind of product"),
        hint = if (kind.value == "medication") L("Sold to a PawMiaw family for their pet, a medication lands in the pet’s cabinet with its lot and expiry.") else null,
        optional = true,
    ) {
        FlowLayout(spacing = 8.dp) {
            kinds.forEach { value ->
                val chosen = kind.value == value
                val tone = CashCatalogLook.productHue(value).color
                val title = productKindTitle(value)
                Row(
                    Modifier
                        .height(48.dp)
                        .background(if (chosen) Theme.wash(tone, 18.0) else Theme.surface, CapsuleShape)
                        .border(2.dp, if (chosen) Theme.mix(tone, 60.0, Theme.inkStrong) else Theme.borderSoft, CapsuleShape)
                        .clip(CapsuleShape)
                        .plainButton(label = title) { kind.value = if (chosen) "" else value }
                        .semantics { selected = chosen }
                        .padding(start = 8.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp).background(if (chosen) tone else Theme.wash(tone, 26.0), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(CashCatalogLook.productIcon(value), 16.dp, tint = if (chosen) Theme.inkOnBright else Theme.inkStrong)
                    }
                    Text(title, style = Theme.text(14, FontWeight.Bold), color = if (chosen) Theme.inkStrong else Theme.inkSoft, maxLines = 1)
                    if (chosen) Glyph("check", 16.dp, tint = Theme.inkStrong)
                }
            }
        }
    }
}

private fun productKindTitle(value: String): String = when (value) {
    "medication" -> L("Medication")
    "food" -> L("Food")
    "supply" -> L("Supply")
    "accessory" -> L("Accessory")
    else -> L("Other@product")
}
