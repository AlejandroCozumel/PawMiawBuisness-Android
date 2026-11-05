package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.Collator
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.min

//
//  CatalogScreen.kt (iOS CatalogScreen.swift)
//
//  Catálogo (web: routes/provider.catalog.tsx, catalog-page/catalog-screen.tsx,
//  catalog-rows.tsx, catalog-categories-panel.tsx): the services and products
//  the business charges for — their prices, tax, categories and stock. The
//  module header counts each kind and what needs restocking (those numbers
//  filter the list); one card holds the kind switch, the search, the filter
//  chips and sort, the bulk bar and the rows; below, the categories and what
//  each kind of visit charges by default (CashServiceDefaults.kt). The
//  editor is CashCatalogEditor.kt; bulk changes CashCatalogBulk.kt;
//  CSV import CashCatalogCSV.kt.
//

/** Swift's `.name` case is `byName` here: an enum entry can't be called `name` in Kotlin. */
private enum class CashCatalogSort { byName, recent, price, priceDesc, stock }

/**
 * What a catalog item looks like everywhere (web catalog-bits.tsx): its
 * kind's colour (services sky, products peach), its icon, its stock.
 */
object CashCatalogLook {
    fun hue(itemType: String): Hue = if (itemType == "product") Hue.peach else Hue.sky
    fun tone(itemType: String): ChipTone = ChipTone.hue(hue(itemType))

    /** A product kind's icon (web `KIND_ICONS`); a service's briefcase. */
    fun productIcon(kind: String?): String = when (kind) {
        "medication" -> "pill"
        "food" -> "paw-print"
        "supply" -> "boxes"
        "accessory" -> "shopping-bag"
        "other" -> "sparkles"
        else -> "package"
    }

    fun icon(item: CashCatalogItem): String = if (item.isProduct) productIcon(item.productKind) else "briefcase-medical"

    fun kindIcon(itemType: String): String = if (itemType == "product") "package" else "briefcase-medical"

    /** A product kind's colour (web `PRODUCT_KIND_HUE`). */
    fun productHue(kind: String): Hue = when (kind) {
        "medication" -> Hue.sky
        "food" -> Hue.butter
        "supply" -> Hue.teal
        "accessory" -> Hue.lavender
        else -> Hue.grey
    }

    /** Where an item's stock stands (web `stockState`). */
    sealed class Stock {
        data object none : Stock()
        data object untracked : Stock()
        data object setup : Stock()
        data class pack(val units: Int) : Stock()
        data class out(val quantity: Int, val reorderPoint: Int, val backorder: Boolean) : Stock()
        data class expired(val quantity: Int) : Stock()
        data class low(val quantity: Int, val reorderPoint: Int, val expired: Int) : Stock()
        data class ok(val quantity: Int, val location: String?, val expired: Int) : Stock()
    }

    fun stock(item: CashCatalogItem): Stock {
        if (!item.isProduct) return Stock.none
        if (item.isPack) return Stock.pack(units = item.stockUnits ?: 1)
        if (!item.trackInventory) return Stock.untracked
        val inventory = item.inventory ?: return Stock.setup
        if (inventory.quantityOnHand <= 0) {
            return Stock.out(
                quantity = inventory.quantityOnHand,
                reorderPoint = if (inventory.lowStockAlert == false) 0 else inventory.reorderPoint,
                backorder = inventory.allowBackorder,
            )
        }
        val expired = maxOf(0, inventory.expiredUnits ?: 0)
        if (expired >= inventory.quantityOnHand) return Stock.expired(quantity = inventory.quantityOnHand)
        if (inventory.lowStock && inventory.reorderPoint > 0 && inventory.lowStockAlert != false) {
            return Stock.low(quantity = inventory.quantityOnHand, reorderPoint = inventory.reorderPoint, expired = expired)
        }
        return Stock.ok(quantity = inventory.quantityOnHand, location = inventory.storageLocation.nonEmpty, expired = expired)
    }

    /** Nothing left to sell, or at the reorder point: a reason to reorder. */
    fun needsRestock(item: CashCatalogItem): Boolean = when (stock(item)) {
        is Stock.low, is Stock.out, is Stock.expired -> true
        else -> false
    }

    /** The short line under a price: tax in it, on top of it, or not charged (web `taxNote`). */
    fun taxNote(item: CashCatalogItem, settings: CashStoreSettings?): String {
        if (item.taxTreatment == "exempt") return L("Tax exempt")
        val rate: Int? = when (item.taxTreatment) {
            "zero" -> 0
            "standard" -> item.taxRateBps ?: 0
            else -> settings?.defaultTaxRateBps
        }
        if (rate == null) return L("Standard tax")
        if (rate == 0) return L("0% tax")
        if (settings?.pricesIncludeTax ?: true) return LF("Incl. %@ tax", cashRate(rate))
        return LF("+ %@ tax", cashRate(rate))
    }
}

/** Lower-case and accent-free, trimmed: how the search compares (Swift `folding([.diacriticInsensitive, .caseInsensitive])`). */
private fun cashCatalogFold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Swift `localizedStandardCompare`: the app's language, case ignored. */
private val cashCatalogNames: Collator get() = Collator.getInstance(appLocale).apply { strength = Collator.SECONDARY }

/** A category as the list endpoint returns it, with when and by whom it was added. */
@Serializable
private data class CashCategoryEntry(
    val id: String,
    val itemType: String,
    val name: String,
    val active: Boolean,
    val createdAt: String? = null,
    val createdBy: Person? = null,
) {
    @Serializable
    data class Person(val name: String? = null)
}

@Serializable
private data class CashCategoryEntryList(val items: List<CashCategoryEntry>)

private data class CashCategoryMeta(val createdAt: String?, val createdBy: String?)

private data class CashCatalogSummaryCounts(
    var services: Int = 0,
    var products: Int = 0,
    var restock: Int = 0,
    var out: Int = 0,
    var tracked: Int = 0,
    var archived: Int = 0,
    var archivedServices: Int = 0,
    var noPrice: Int = 0,
)

private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

/** The Catalog page's state (iOS the screen's `@State`s and the work on them). */
@Stable
private class CashCatalogModel(val openItemId: String?) {
    var items by mutableStateOf(listOf<CashCatalogItem>())
    var total by mutableIntStateOf(0)
    var categories by mutableStateOf(listOf<CashCategory>())
    var categoryMeta by mutableStateOf(mapOf<String, CashCategoryMeta>())
    var categoriesLoaded by mutableStateOf(false)
    var settings by mutableStateOf<CashStoreSettings?>(null)
    var services by mutableStateOf<CashServiceCatalog?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var view by mutableStateOf("all")
    val search = mutableStateOf("")
    var category by mutableStateOf<String?>(null)
    var archived by mutableStateOf(false)
    var lowOnly by mutableStateOf(false)
    var noPrice by mutableStateOf(false)
    /** The sort asked for; null is the usual one (name, or the emptiest shelf on "Needs restocking"). */
    var sortChoice by mutableStateOf<CashCatalogSort?>(null)
    var shown by mutableIntStateOf(20)
    var creating by mutableStateOf<String?>(null)
    var editing by mutableStateOf<CashCatalogItem?>(null)
    var duplicating by mutableStateOf<CashCatalogItem?>(null)
    var archiving by mutableStateOf<CashCatalogItem?>(null)
    var pricing by mutableStateOf<CashCatalogItem?>(null)
    /** On a phone the ticks show only while choosing (a tablet always shows them). */
    var selecting by mutableStateOf(false)
    var picked by mutableStateOf(setOf<String>())
    var bulk by mutableStateOf<String?>(null)
    var managing by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var exported by mutableStateOf<CashSharedFile?>(null)
    var openedItem = false
    /** The one item a link opened, for someone who cannot edit it: listed alone. */
    var focusId by mutableStateOf<String?>(null)

    var wide = false
    lateinit var scope: CoroutineScope

    private val workspace get() = Workspace.shared
    val canEdit: Boolean get() = workspace.can(Permission.catalogEdit)
    val canCreate: Boolean get() = workspace.can(Permission.catalogCreate)
    val canViewInventory: Boolean get() = workspace.can(Permission.inventoryView)
    val canReceive: Boolean get() = canViewInventory && workspace.can(Permission.inventoryReceive)
    val ticks: Boolean get() = canEdit && (wide || selecting)

    val sort: CashCatalogSort get() = sortChoice ?: (if (lowOnly) CashCatalogSort.stock else CashCatalogSort.byName)
    val needle: String get() = cashCatalogFold(search.value).trim()
    val viewType: String? get() = if (view == "products") "product" else if (view == "services") "service" else null
    val focusItem: CashCatalogItem? get() = focusId?.let { id -> items.firstOrNull { it.id == id } }

    val listed: List<CashCatalogItem>
        get() {
            focusItem?.let { return listOf(it) }
            val needle = this.needle
            val viewType = this.viewType
            val rows = items.filter { item ->
                (if (archived) !item.active else item.active) &&
                    (view == "all" || item.itemType == viewType) &&
                    (!lowOnly || CashCatalogLook.needsRestock(item)) &&
                    (!noPrice || (item.active && item.defaultUnitAmount == 0)) &&
                    (category == null || (if (category == "none") item.category == null else item.category?.id == category)) &&
                    (needle.isEmpty() || listOfNotNull(item.name, item.description, item.sku, item.inventory?.barcode, item.inventory?.storageLocation, item.category?.name)
                        .any { cashCatalogFold(it).trim().contains(needle) })
            }
            val names = cashCatalogNames
            val byName = Comparator<CashCatalogItem> { a, b -> names.compare(a.name, b.name) }
            return when (sort) {
                CashCatalogSort.byName -> rows.sortedWith(byName)
                CashCatalogSort.recent -> rows.sortedWith { a, b ->
                    val x = a.updatedAt ?: ""
                    val y = b.updatedAt ?: ""
                    if (x == y) byName.compare(a, b) else y.compareTo(x)
                }
                CashCatalogSort.price -> rows.sortedWith { a, b ->
                    if (a.defaultUnitAmount == b.defaultUnitAmount) byName.compare(a, b) else a.defaultUnitAmount.compareTo(b.defaultUnitAmount)
                }
                CashCatalogSort.priceDesc -> rows.sortedWith { a, b ->
                    if (a.defaultUnitAmount == b.defaultUnitAmount) byName.compare(a, b) else b.defaultUnitAmount.compareTo(a.defaultUnitAmount)
                }
                CashCatalogSort.stock -> {
                    fun key(item: CashCatalogItem): Int = when (val state = CashCatalogLook.stock(item)) {
                        is CashCatalogLook.Stock.expired -> 0
                        is CashCatalogLook.Stock.out -> state.quantity
                        is CashCatalogLook.Stock.low -> state.quantity
                        is CashCatalogLook.Stock.ok -> state.quantity
                        CashCatalogLook.Stock.setup -> -1
                        else -> Int.MAX_VALUE
                    }
                    rows.sortedWith { a, b -> if (key(a) == key(b)) byName.compare(a, b) else key(a).compareTo(key(b)) }
                }
            }
        }

    val selected: List<CashCatalogItem> get() = if (canEdit) listed.filter { it.id in picked } else emptyList()

    /** The kinds of visit that bill each item by default. */
    val visitsByItem: Map<String, List<String>>
        get() {
            val map = LinkedHashMap<String, MutableList<String>>()
            for (service in services?.services ?: emptyList()) {
                service.defaultBillingItem?.id?.let { map.getOrPut(it) { mutableListOf() }.add(service.label) }
            }
            return map
        }

    /** The active packs that draw on each product. */
    val packsByItem: Map<String, Int>
        get() {
            val map = HashMap<String, Int>()
            for (item in items) if (item.active && item.isPack) item.stockCatalogItemId?.let { map[it] = (map[it] ?: 0) + 1 }
            return map
        }

    /** Items a visit bills by default or an active pack draws on. */
    val leanedOn: Set<String> get() = visitsByItem.keys + packsByItem.keys
    val hasFilters: Boolean get() = narrowed || sortChoice != null
    val narrowed: Boolean get() = search.value.isNotEmpty() || category != null || lowOnly || noPrice || archived || view != "all"
    val filterKey: String get() = "$view|${search.value}|${category ?: ""}|$archived|$lowOnly|$noPrice|${sort.name}|${focusId ?: ""}"

    val summary: CashCatalogSummaryCounts
        get() {
            val s = CashCatalogSummaryCounts()
            for (item in items) {
                if (!item.active) {
                    s.archived += 1
                    if (item.itemType == "service") s.archivedServices += 1
                    continue
                }
                if (item.defaultUnitAmount == 0) s.noPrice += 1
                if (item.itemType == "service") {
                    s.services += 1
                    continue
                }
                s.products += 1
                if (item.trackInventory && !item.isPack) s.tracked += 1
                if (CashCatalogLook.needsRestock(item)) {
                    s.restock += 1
                    if (CashCatalogLook.stock(item) is CashCatalogLook.Stock.out) s.out += 1
                }
            }
            return s
        }

    val categoryLabel: String
        get() = if (category == "none") L("Uncategorized") else categories.firstOrNull { it.id == category }?.name ?: L("Category")

    /** Items per category id; "none:service" and "none:product" count the uncategorized ones. */
    val categoryCounts: Map<String, Int>
        get() {
            val counts = HashMap<String, Int>()
            for (item in items) if (item.active) {
                val key = item.category?.id ?: "none:${item.itemType}"
                counts[key] = (counts[key] ?: 0) + 1
            }
            return counts
        }

    val detailHeading: String
        get() = if (view == "services") L("Charged on") else if (view == "products") L("Stock") else L("Stock or visits")

    // MARK: Filters (web catalog-screen.tsx)

    fun changeView(next: String, clearStock: Boolean = false) {
        val nextType: String? = if (next == "products") "product" else if (next == "services") "service" else null
        // A category of the other kind would empty the list; drop it.
        val current = category
        if (current != null && current != "none") {
            val type = categories.firstOrNull { it.id == current }?.itemType
            if (type != null && nextType != null && type != nextType) category = null
        }
        val keepStock = !clearStock && (next == "products" || next == "all")
        if (!keepStock) {
            lowOnly = false
            if (sortChoice == CashCatalogSort.stock) sortChoice = null
        }
        focusId = null
        view = next
    }

    fun toggleView(next: String) {
        if (view == next && !lowOnly) changeView("all") else changeView(next, clearStock = true)
    }

    fun toggleRestock() {
        focusId = null
        if (lowOnly) {
            lowOnly = false
        } else {
            lowOnly = true
            view = "products"
            archived = false
            noPrice = false
        }
        sortChoice = null
    }

    fun toggleNoPrice() {
        focusId = null
        noPrice = !noPrice
        if (noPrice) archived = false
    }

    fun toggleArchived() {
        focusId = null
        archived = !archived
        if (archived) {
            lowOnly = false
            noPrice = false
            if (sortChoice == CashCatalogSort.stock) sortChoice = null
        }
    }

    fun changeSort(next: CashCatalogSort) {
        // The usual order needs no choice of its own.
        sortChoice = if (next == (if (lowOnly) CashCatalogSort.stock else CashCatalogSort.byName)) null else next
    }

    fun clearFilters() {
        search.value = ""
        category = null
        lowOnly = false
        noPrice = false
        archived = false
        view = "all"
        sortChoice = null
        focusId = null
    }

    // MARK: Loading and actions

    suspend fun load() {
        loading = true
        try {
            try {
                val first: Page<CashCatalogItem> = API.get("/provider-catalog", mapOf("limit" to "100", "offset" to "0"))
                var all = first.items
                val pages = min(20, ceil((first.total ?: all.size).toDouble() / 100).toInt())
                if (pages > 1) {
                    for (page in 1 until pages) {
                        val more: Page<CashCatalogItem> = API.get("/provider-catalog", mapOf("limit" to "100", "offset" to "${page * 100}"))
                        val known = all.map { it.id }.toSet()
                        all = all + more.items.filter { it.id !in known }
                    }
                }
                items = all
                total = first.total ?: all.size
                picked = picked.filter { id -> all.any { it.id == id } }.toSet()
                error = null
                if (openItemId != null && !openedItem) {
                    openedItem = true
                    val item = all.firstOrNull { it.id == openItemId }
                    if (canEdit && item != null) {
                        editing = item
                    } else if (item != null) {
                        focusId = openItemId
                    } else {
                        Toast.error(L("That item is no longer in the catalog."))
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = errorText(failure, fallback = L("Could not load the catalog. Try again."))
            }
            attempt { API.get<CashCategoryEntryList>("/provider-catalog/categories") }?.let { list ->
                categories = list.items.map { CashCategory(id = it.id, itemType = it.itemType, name = it.name, active = it.active) }
                val meta = LinkedHashMap<String, CashCategoryMeta>()
                for (entry in list.items) if (entry.id !in meta) meta[entry.id] = CashCategoryMeta(createdAt = entry.createdAt, createdBy = entry.createdBy?.name)
                categoryMeta = meta
            }
            categoriesLoaded = true
            settings = attempt { API.get<CashStoreSettings>("/provider-store/settings") } ?: settings
            loadServices()
        } finally {
            loading = false
        }
    }

    suspend fun loadServices() {
        services = attempt { API.get<CashServiceCatalog>("/appointment-services", mapOf("scope" to "provider")) } ?: services
    }

    fun restore(item: CashCatalogItem) {
        scope.launch {
            try {
                API.patch<EmptyResponse>("/provider-catalog/${item.id}", mapOf("active" to true))
                Toast.show(LF("%@ is on sale again.", item.name))
                announceDataChanged()
                load()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Toast.error(failure, fallback = L("Could not restore this item. Try again."))
            }
        }
    }

    fun restoreSelected() {
        val targets = selected.filter { !it.active }
        scope.launch {
            val outcome = CashCatalogBulk.run(targets.map { it to mapOf<String, Any?>("active" to true) }) { }
            if (outcome.done > 0) Toast.show(LP(outcome.done, "%d item restored.", "%d items restored."))
            if (outcome.failed.isNotEmpty()) {
                Toast.error(LP(outcome.failed.size, "%d item could not be changed.", "%d items could not be changed."))
            } else {
                picked = emptySet()
                selecting = false
            }
            load()
        }
    }

    fun export(list: List<CashCatalogItem>) {
        try {
            val url = CashCSV.write(CashCatalogCSV.export(list, all = items), name = CashCatalogCSV.exportName())
            Toast.show(LP(list.size, "%d item exported.", "%d items exported."))
            exported = CashSharedFile(url)
        } catch (_: Exception) {
            Toast.error(L("The file could not be made. Try again."))
        }
    }

    fun reload() {
        scope.launch { load() }
    }
}

private fun sortTitle(sort: CashCatalogSort): String = when (sort) {
    CashCatalogSort.byName -> L("Name A–Z")
    CashCatalogSort.recent -> L("Recently updated")
    CashCatalogSort.price -> L("Price, low to high")
    CashCatalogSort.priceDesc -> L("Price, high to low")
    CashCatalogSort.stock -> L("Stock, lowest first")
}

@Composable
fun CatalogScreen(
    /** Open this item's editor once the catalog is read (the counter's "Open in the Catalog"). */
    openItemId: String? = null,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val m = remember(openItemId) { CashCatalogModel(openItemId) }
    m.wide = LocalSizeClass.current == SizeClass.regular
    m.scope = rememberCoroutineScope()

    providerPage(L("Catalog"), root = true, modifier = modifier) {
        if (!workspace.can(Permission.catalogView)) {
            PageScroll {
                ModuleHeader(
                    icon = "package", title = L("Catalog"),
                    subtitle = L("What you charge for and what you sell — prices, tax and stock in one place."),
                    accent = Theme.mango, storageKey = "catalog",
                )
                NoAccessState(message = L("This workspace is active, but your role does not include the catalog. Ask the workspace owner for access."))
            }
        } else {
            CatalogPage(m)
        }
    }

    m.creating?.let { kind ->
        AppSheet(onDismissRequest = { m.creating = null }) {
            CashCatalogEditorSheet(item = null, kind = if (kind == "ask") null else kind, duplicateOf = null, categories = m.categories, settings = m.settings, onSaved = { m.reload() })
        }
    }
    m.editing?.let { item ->
        AppSheet(onDismissRequest = { m.editing = null }) {
            CashCatalogEditorSheet(item = item, kind = item.itemType, duplicateOf = null, categories = m.categories, settings = m.settings, onSaved = { m.reload() })
        }
    }
    m.duplicating?.let { item ->
        AppSheet(onDismissRequest = { m.duplicating = null }) {
            CashCatalogEditorSheet(item = null, kind = item.itemType, duplicateOf = item, categories = m.categories, settings = m.settings, onSaved = { m.reload() })
        }
    }
    m.archiving?.let { item ->
        AppSheet(onDismissRequest = { m.archiving = null }) {
            CashArchiveSheet(item = item, visits = m.visitsByItem[item.id] ?: emptyList(), onDone = { m.reload() })
        }
    }
    m.pricing?.let { item ->
        AppSheet(onDismissRequest = { m.pricing = null }) {
            CashCatalogPriceSheet(item = item, settings = m.settings, onSaved = { m.reload() })
        }
    }
    m.bulk?.let { action ->
        AppSheet(onDismissRequest = { m.bulk = null }) {
            val done = {
                m.picked = emptySet()
                m.selecting = false
                m.reload()
            }
            when (action) {
                "category" -> CashBulkCategorySheet(items = m.selected, categories = m.categories, onDone = done)
                "price" -> CashBulkPriceSheet(items = m.selected, settings = m.settings, onDone = done)
                else -> CashBulkArchiveSheet(items = m.selected, leanedOn = m.leanedOn, onDone = done)
            }
        }
    }
    if (m.managing) {
        AppSheet(onDismissRequest = { m.managing = false }) {
            CashCategoryManagerSheet(categories = m.categories, items = m.items, startType = m.viewType ?: "service", onChanged = { m.reload() })
        }
    }
    if (m.importing) {
        AppSheet(onDismissRequest = { m.importing = false }) {
            CashCatalogImportSheet(items = m.items, categories = m.categories, onDone = { m.reload() })
        }
    }
    m.exported?.let { file -> key(file.id) { ShareSheet(listOf(file.url), onDismiss = { m.exported = null }) } }
}

// MARK: Page

@Composable
private fun CatalogPage(m: CashCatalogModel) {
    ReloadsOnScope { m.load() }
    val filterKey = m.filterKey
    LaunchedEffect(filterKey) { m.shown = 20 }
    PageScroll(maxWidth = if (m.wide) 1180.dp else 820.dp, refresh = { m.load() }) {
        CatalogHeader(m)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!m.canEdit && !m.canCreate) ReadOnlyNote()
            CatalogListCard(m)
            val rows = m.listed.size
            if (!m.loading && rows > 0) {
                Text(
                    if (rows > m.shown) LF("Showing %d of %d", min(m.shown, rows), rows) else LP(rows, "%d item", "%d items"),
                    style = Theme.text(14), color = Theme.inkSoft,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (m.wide) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(336.dp)) { CategoriesPanel(m) }
                Box(Modifier.weight(1f)) { DefaultsPanel(m) }
            }
        } else {
            CategoriesPanel(m)
            DefaultsPanel(m)
        }
    }
}

// MARK: Header

@Composable
private fun CatalogHeader(m: CashCatalogModel) {
    val nav = LocalNavigator.current
    val s = m.summary
    val blank = m.loading && m.items.isEmpty()
    ModuleHeader(
        icon = "package",
        title = L("Catalog"),
        subtitle = L("What you charge for and what you sell — prices, tax and stock in one place."),
        accent = Theme.mango,
        stats = listOf(
            ModuleStat(
                "services", label = L("Services"), value = if (blank) "—" else "${s.services}",
                hint = if (s.archivedServices > 0) LP(s.archivedServices, "%d archived@one", "%d archived") else L("Visits, grooming, stays…"),
                icon = "briefcase-medical", accent = Hue.sky.color,
                active = m.view == "services" && !m.lowOnly, action = { m.toggleView("services") },
            ),
            ModuleStat(
                "products", label = L("Products"), value = if (blank) "—" else "${s.products}",
                hint = if (s.tracked > 0) LF("%d tracked in stock", s.tracked) else L("None tracked in stock yet"),
                icon = "package", accent = Hue.peach.color,
                active = m.view == "products" && !m.lowOnly, action = { m.toggleView("products") },
            ),
            ModuleStat(
                "restock", label = L("Needs restocking"), value = if (blank) "—" else "${s.restock}",
                hint = if (s.out > 0) LP(s.out, "%d out of stock@one", "%d out of stock")
                else if (s.restock > 0) L("Running low")
                else if (s.tracked > 0) L("Stock looks healthy")
                else L("No products track stock"),
                icon = "triangle-alert", accent = Theme.lime, attention = s.restock > 0,
                active = m.lowOnly, action = { m.toggleRestock() },
            ),
        ),
        storageKey = "catalog",
    ) {
        if (m.canViewInventory) {
            PMButton(L("Inventory"), systemImage = "warehouse", variant = ButtonVariant.outline) { nav.push(ProviderRoute.section(ProviderSection.inventory)) }
        }
        if (m.items.isNotEmpty() || m.canCreate) {
            AppMenu(
                label = {
                    PMButtonStyle(ButtonVariant.outline, ButtonSize.header) {
                        PMButtonContent(L("Spreadsheet"), icon = "file-spreadsheet", variant = ButtonVariant.outline, size = ButtonSize.header)
                    }
                },
                accessibilityLabel = L("Import or export the catalog"),
            ) {
                if (m.items.isNotEmpty()) {
                    item(LF("Export the whole catalog (%d)", m.items.size), icon = "download") { m.export(m.items) }
                }
                val listed = m.listed
                if (m.narrowed && listed.isNotEmpty() && listed.size < m.items.size) {
                    item(LF("Export this list (%d)", listed.size), icon = "download") { m.export(listed) }
                }
                if (m.canCreate) {
                    item(L("Import from a CSV file"), icon = "file-up") { m.importing = true }
                }
            }
        }
        if (m.canCreate) {
            // The editor's first step asks what it is: a service or a product.
            PMButton(L("New item"), systemImage = "plus") { m.creating = "ask" }
        }
    }
}

@Composable
private fun ReadOnlyNote() {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.surfaceMuted, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("eye", 16.dp, tint = Theme.inkSoft)
        Text(L("You can see the catalog. Your role cannot add or change items."), style = Theme.text(14), color = Theme.inkSoft)
    }
}

// MARK: The list card

@Composable
private fun CatalogListCard(m: CashCatalogModel) {
    val nav = LocalNavigator.current
    val rows = m.listed
    ListCard {
        CardToolbar {
            KindSwitch(m)
            SearchField(
                prompt = L("Name, SKU, barcode or category"), text = m.search,
                modifier = Modifier.semantics { contentDescription = L("Search the catalog") },
            )
        }
        FilterRow(m)
        if (m.focusItem != null) {
            Band(fill = Theme.wash(Theme.butter, 22.0)) {
                Text(L("Showing the item you opened."), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                LinkButton(L("Show the whole catalog")) { m.focusId = null }
            }
        }
        if (m.selected.isNotEmpty()) BulkBar(m)
        if (m.lowOnly && rows.isNotEmpty()) {
            Band(fill = Theme.wash(Theme.mango, 12.0)) {
                Text(L("Products at or below their reorder point"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                if (m.canViewInventory) LinkButton(L("See them in Inventory")) { nav.push(ProviderRoute.section(ProviderSection.inventory)) }
            }
        }
        if (m.total > m.items.size) {
            Band(fill = Theme.wash(Theme.lime, 14.0)) {
                Text(LF("Showing the first %d of %d items.", m.items.size, m.total), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
            }
        }
        if (m.loading && m.items.isEmpty()) {
            LoadingState()
        } else if (rows.isEmpty()) {
            CatalogEmptyState(m)
        } else {
            RowsList(m, rows)
        }
    }
}

@Composable
private fun KindSwitch(m: CashCatalogModel) {
    val scoped = m.items.filter { if (m.archived) !it.active else it.active }
    Segmented(
        options = listOf(
            Segmented.Option("all", L("All"), count = scoped.size),
            Segmented.Option("services", L("Services"), icon = "briefcase-medical", count = scoped.count { it.itemType == "service" }, tone = CashCatalogLook.tone("service")),
            Segmented.Option("products", L("Products"), icon = "package", count = scoped.count { it.itemType == "product" }, tone = CashCatalogLook.tone("product")),
        ),
        selection = stateOf({ m.view }) { m.changeView(it) },
        look = Segmented.Look.raised,
        label = L("Show"),
        fill = !m.wide,
    )
}

@Composable
private fun FilterRow(m: CashCatalogModel) {
    val s = m.summary
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppMenu(
                    label = { ToneMenuLabel(title = if (m.category == null) L("Category") else m.categoryLabel, icon = "tags", tone = ChipTone.page, isOn = m.category != null) },
                    accessibilityLabel = if (m.category == null) L("Category") else "${L("Category")}: ${m.categoryLabel}",
                ) { CategoryMenu(m) }
                if (m.view != "services") {
                    ToneChip(L("Needs restocking"), icon = "triangle-alert", tone = ChipTone.state(ProviderState.waiting), count = s.restock, isOn = m.lowOnly) { m.toggleRestock() }
                }
                if (s.noPrice > 0 || m.noPrice) {
                    ToneChip(L("No price"), icon = "tag", tone = ChipTone.state(ProviderState.waiting), count = s.noPrice, isOn = m.noPrice) { m.toggleNoPrice() }
                }
                if (s.archived > 0 || m.archived) {
                    ToneChip(L("Archived@filter"), icon = "archive", tone = ChipTone.state(ProviderState.closed), count = s.archived, isOn = m.archived) { m.toggleArchived() }
                }
                Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(Theme.borderSoft))
                val sort = m.sort
                AppMenu(
                    label = { ToneMenuLabel(title = if (sort != CashCatalogSort.byName) sortTitle(sort) else L("Sort"), icon = "arrow-up-down", tone = ChipTone.page, isOn = sort != CashCatalogSort.byName) },
                    accessibilityLabel = L("Sort"),
                ) {
                    picker(L("Sort"), options = CashCatalogSort.entries.map { it to sortTitle(it) }, selection = stateOf({ m.sort }) { m.changeSort(it) })
                }
            }
            if (m.canEdit && !m.wide && m.items.isNotEmpty()) {
                TextLink(if (m.selecting) L("Done@select") else L("Select")) {
                    m.selecting = !m.selecting
                    if (!m.selecting) m.picked = emptySet()
                }
            }
            if (m.hasFilters) {
                TextLink(L("Clear filters")) { m.clearFilters() }
            }
        }
        RowDivider()
    }
}

@Composable
private fun AppMenuScope.CategoryMenu(m: CashCatalogModel) {
    val viewType = m.viewType
    val shownCategories = m.categories
        .filter { viewType == null || it.itemType == viewType }
        .sortedWith(compareBy<CashCategory>({ if (it.active) 0 else 1 }, { it.name.lowercase() }))
    val counts = m.categoryCounts
    val binding = stateOf({ m.category ?: "" }) { m.category = it.nonEmpty }
    picker(L("Category"), options = listOf("" to L("Every category"), "none" to L("Uncategorized")), selection = binding)
    listOf("service", "product").forEach { type ->
        val group = shownCategories.filter { it.itemType == type }
        if (group.isNotEmpty()) {
            // `Section(type) { Picker(…).pickerStyle(.inline).labelsHidden() }`: the header, then its options with a ✓ on the chosen one.
            section(if (type == "product") L("Products") else L("Services"))
            group.forEach { entry ->
                item("${entry.name} · ${if (entry.active) (counts[entry.id] ?: 0).toString() else L("inactive")}", checked = binding.value == entry.id) { binding.value = entry.id }
            }
        }
    }
}

@Composable
private fun BulkBar(m: CashCatalogModel) {
    val chosen = m.selected
    val active = chosen.count { it.active }
    val archivedCount = chosen.size - active
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.blue, 9.0))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LP(chosen.size, "%d selected@one", "%d selected"), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.heightIn(min = 36.dp).plainButton(label = L("Clear selection")) { m.picked = emptySet() },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("x", 16.dp, tint = Theme.blue)
                    Text(L("Clear selection"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PMButton(L("Change category"), systemImage = "tags", variant = ButtonVariant.outline, size = ButtonSize.header) { m.bulk = "category" }
                PMButton(L("Change prices"), systemImage = "percent", variant = ButtonVariant.outline, size = ButtonSize.header) { m.bulk = "price" }
                if (active > 0) PMButton(L("Archive"), systemImage = "archive", variant = ButtonVariant.outline, size = ButtonSize.header) { m.bulk = "archive" }
                if (archivedCount > 0) PMButton(L("Restore"), systemImage = "archive-restore", variant = ButtonVariant.outline, size = ButtonSize.header) { m.restoreSelected() }
                PMButton(L("Export CSV"), systemImage = "download", variant = ButtonVariant.outline, size = ButtonSize.header) { m.export(chosen) }
            }
        }
        RowDivider()
    }
}

@Composable
private fun RowsList(m: CashCatalogModel, rows: List<CashCatalogItem>) {
    val nav = LocalNavigator.current
    val all = rows.size
    val ticked = rows.count { it.id in m.picked }
    val byId = HashMap<String, String>().also { map -> m.items.forEach { if (it.id !in map) map[it.id] = it.name } }
    val visits = m.visitsByItem
    val packs = m.packsByItem
    val needle = m.needle
    if (m.wide) {
        WideHead(m, allTicked = if (ticked == all) true else if (ticked > 0) null else false, rows = rows)
    } else if (m.ticks) {
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton(label = if (ticked == all) L("Clear selection") else L("Select every item listed")) {
                    m.picked = if (ticked == all) emptySet() else rows.map { it.id }.toSet()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tick(on = ticked == all, mixed = ticked > 0 && ticked != all)
            Text(if (ticked == all) L("Clear selection") else L("Select every item listed"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
        }
        RowDivider()
    }
    Column(Modifier.fillMaxWidth()) {
        rows.take(m.shown).forEachIndexed { index, item ->
            key(item.id) {
                if (index > 0) RowDivider()
                val loadsMore = index >= m.shown - 3 && m.shown < rows.size
                CashCatalogRow(
                    item = item, settings = m.settings, needle = needle, showKind = m.view == "all", wide = m.wide,
                    packSourceName = item.stockCatalogItemId?.let { byId[it] },
                    visits = visits[item.id] ?: emptyList(), packs = packs[item.id] ?: 0,
                    selected = if (m.ticks) item.id in m.picked else null,
                    highlighted = m.focusId == item.id,
                    onSelect = { m.picked = if (item.id in m.picked) m.picked - item.id else m.picked + item.id },
                    onOpen = if (m.canEdit) ({ m.editing = item }) else null,
                    onPrice = if (m.canEdit) ({ m.pricing = item }) else null,
                    onDuplicate = if (m.canCreate) ({ m.duplicating = item }) else null,
                    onArchive = if (m.canEdit) ({ m.archiving = item }) else null,
                    onRestore = if (m.canEdit) ({ m.restore(item) }) else null,
                    onReceive = if (m.canReceive) ({ nav.push(ProviderRoute.section(ProviderSection.inventory)) }) else null,
                    onHistory = if (m.canViewInventory) ({ nav.push(ProviderRoute.section(ProviderSection.inventory)) }) else null,
                    modifier = if (loadsMore) Modifier.onScrolledIntoView { if (m.shown < rows.size) m.shown += 20 } else Modifier,
                )
            }
        }
    }
}

/** The column heads on a wide screen (web: md+ grid head). */
@Composable
private fun WideHead(m: CashCatalogModel, allTicked: Boolean?, rows: List<CashCatalogItem>) {
    val sort = m.sort
    val headStyle = brandText(10.6, FontWeight.ExtraBold).tracking(1.27)
    Column(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.surfaceMuted, 55.0))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (m.ticks) {
                    Box(Modifier.plainButton(label = L("Select every item listed")) {
                        m.picked = if (allTicked == true) emptySet() else rows.map { it.id }.toSet()
                    }) { Tick(on = allTicked == true, mixed = allTicked == null) }
                }
                Box(Modifier.weight(1f)) {
                    HeadButton(L("Item"), active = sort == CashCatalogSort.byName, style = headStyle) { m.changeSort(CashCatalogSort.byName) }
                }
                Text(L("Category").uppercase(), style = headStyle, maxLines = 1, modifier = Modifier.width(CashCatalogRow.categoryWidth))
                Box(Modifier.width(CashCatalogRow.priceWidth), contentAlignment = Alignment.CenterEnd) {
                    HeadButton(
                        L("Price"), active = sort == CashCatalogSort.price || sort == CashCatalogSort.priceDesc, down = sort == CashCatalogSort.priceDesc, style = headStyle,
                    ) { m.changeSort(if (sort == CashCatalogSort.price) CashCatalogSort.priceDesc else CashCatalogSort.price) }
                }
                Box(Modifier.width(CashCatalogRow.detailWidth)) {
                    if (m.view != "services") {
                        HeadButton(m.detailHeading, active = sort == CashCatalogSort.stock, style = headStyle) { m.changeSort(CashCatalogSort.stock) }
                    } else {
                        Text(m.detailHeading.uppercase(), style = headStyle, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(40.dp).height(1.dp))
            }
        }
        RowDivider()
    }
}

@Composable
private fun HeadButton(title: String, active: Boolean, down: Boolean = false, style: androidx.compose.ui.text.TextStyle, action: () -> Unit) {
    val ink = if (active) Theme.inkStrong else Theme.inkSoft
    Row(
        Modifier.plainButton(label = LF("Sort by %@", title.lowercase()), onClick = action),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = style, color = ink, maxLines = 1)
        Glyph(if (down) "arrow-down" else "arrow-up", 12.dp, tint = ink, modifier = Modifier.alpha(if (active) 1f else 0f))
    }
}

// MARK: Empty

@Composable
private fun CatalogEmptyState(m: CashCatalogModel) {
    val error = m.error
    if (error != null) {
        EmptyState(systemImage = "triangle-alert", title = L("The catalog did not load"), message = error, actionTitle = L("Try again"), action = { m.reload() }, accent = Theme.destructive)
    } else if (m.items.isEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            EmptyState(
                systemImage = "package", title = L("Your catalog is empty"),
                message = L("Add what you charge for — consults, grooms, nights — and what you sell, so visits, invoices and the store start from the same prices."),
                accent = Theme.mango,
            )
            if (m.canCreate) {
                FlowLayout(
                    spacing = 8.dp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp)
                        .liftedBy(12.dp),
                ) {
                    PMButton(L("New service"), systemImage = "briefcase-medical", size = ButtonSize.header) { m.creating = "service" }
                    PMButton(L("New product"), systemImage = "package", variant = ButtonVariant.outline, size = ButtonSize.header) { m.creating = "product" }
                    PMButton(L("Import from a CSV file"), systemImage = "file-up", variant = ButtonVariant.outline, size = ButtonSize.header) { m.importing = true }
                }
            }
        }
    } else if (m.needle.isNotEmpty()) {
        EmptyState(
            systemImage = "search", title = LF("Nothing matches “%@”", m.search.value.trim()), message = L("Try a name, SKU or barcode, or clear the filters."),
            actionTitle = L("Clear filters"), action = { m.clearFilters() }, accent = Theme.mango,
        )
    } else if (m.lowOnly) {
        EmptyState(
            systemImage = "package-check", title = L("Nothing needs restocking"), message = L("Every tracked product is above its reorder point."),
            actionTitle = L("Show all products"), action = { m.toggleRestock() }, accent = Theme.lime,
        )
    } else if (m.noPrice) {
        EmptyState(
            systemImage = "tag", title = L("Everything has a price"), message = L("No item on sale is waiting for one."),
            actionTitle = L("Show every item"), action = { m.toggleNoPrice() }, accent = Theme.lime,
        )
    } else if (m.archived) {
        EmptyState(
            systemImage = "archive", title = L("Nothing archived"), message = L("Archived items stay on past invoices but are not offered for new ones."),
            actionTitle = L("Show active items"), action = { m.toggleArchived() }, accent = Theme.mango,
        )
    } else if (m.category == null && m.view == "services") {
        EmptyState(
            systemImage = "briefcase-medical", title = L("No services yet"),
            message = L("A service is what you charge for a visit: a consult, a groom, a night. Each kind of visit can start its bill with one."),
            actionTitle = if (m.canCreate) L("New service") else null, action = if (m.canCreate) ({ m.creating = "service" }) else null, accent = Theme.aqua,
        )
    } else if (m.category == null && m.view == "products") {
        EmptyState(
            systemImage = "package", title = L("No products yet"),
            message = L("A product is what you sell over the counter or use on a visit: food, medicine, supplies. Its stock can be counted."),
            actionTitle = if (m.canCreate) L("New product") else null, action = if (m.canCreate) ({ m.creating = "product" }) else null, accent = Theme.mango,
        )
    } else {
        EmptyState(
            systemImage = "layout-grid", title = L("No items match these filters"), message = L("Try another category or view, or clear the filters."),
            actionTitle = L("Clear filters"), action = { m.clearFilters() }, accent = Theme.mango,
        )
    }
}

/** Swift `.padding(.trailing, -8)`: laid out 8 narrower, drawn into the row's own padding. */
private fun Modifier.overhangEnd(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout((placeable.width - amount.roundToPx()).coerceAtLeast(0), placeable.height) { placeable.placeRelative(0, 0) }
}

/** Swift `.padding(.top, -12)`: drawn 12 higher and 12 shorter. */
private fun Modifier.liftedBy(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lift = amount.roundToPx()
    layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) { placeable.place(0, -lift) }
}

// MARK: Below the list

@Composable
private fun CategoriesPanel(m: CashCatalogModel) {
    CashCatalogCategoriesPanel(
        categories = m.categories, meta = m.categoryMeta, counts = m.categoryCounts, loading = !m.categoriesLoaded,
        activeCategory = m.category, activeType = m.viewType, canManage = m.canCreate || m.canEdit,
        onPick = { id, type ->
            if (m.category == id && (id != "none" || m.viewType == type)) {
                m.category = null
            } else {
                val next = if (type == "product") "products" else "services"
                if (next != "products") m.lowOnly = false
                m.focusId = null
                m.view = next
                m.category = id
            }
        },
        onManage = { m.managing = true },
    )
}

@Composable
private fun DefaultsPanel(m: CashCatalogModel) {
    CashServiceDefaultsPanel(
        catalog = m.services,
        billingItems = m.items.filter { it.itemType == "service" && it.active },
        canEdit = m.canEdit,
        reload = { m.loadServices() },
    )
}

// MARK: Small pieces

@Composable
private fun Band(fill: Color, content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(fill)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        RowDivider()
    }
}

@Composable
private fun LinkButton(title: String, action: () -> Unit) {
    Row(
        Modifier.plainButton(label = title, onClick = action),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
        Glyph("chevron-right", 16.dp, tint = Theme.blue)
    }
}

@Composable
private fun TextLink(title: String, action: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).plainButton(label = title, onClick = action), contentAlignment = Alignment.Center) {
        Text(title, style = Theme.text(12, FontWeight.Bold), color = Theme.blue, maxLines = 1, softWrap = false)
    }
}

// MARK: - A row

/** One row of a catalog row's ⋯ menu. */
private class CashCatalogMenuEntry(val title: String, val icon: String, val separated: Boolean, val run: () -> Unit)

/** Swift `CashCatalogRow`'s static widths. */
object CashCatalogRow {
    val categoryWidth: Dp = 170.dp
    val priceWidth: Dp = 152.dp
    val detailWidth: Dp = 220.dp
}

/**
 * One catalog item (web `CatalogRow`): its tile, name and code, its flags,
 * its price and tax, and — by kind — its stock or the visits that charge it,
 * with a ⋯ menu of quick actions. A phone stacks it; a tablet lays columns.
 */
@Composable
fun CashCatalogRow(
    item: CashCatalogItem,
    settings: CashStoreSettings?,
    needle: String = "",
    showKind: Boolean = false,
    wide: Boolean = false,
    packSourceName: String? = null,
    visits: List<String> = emptyList(),
    packs: Int = 0,
    /** Ticked or not; null when there are no ticks. */
    selected: Boolean? = null,
    highlighted: Boolean = false,
    onSelect: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onPrice: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onReceive: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val noPrice = item.active && item.defaultUnitAmount == 0
    val ownStock = item.isProduct && item.trackInventory && !item.isPack
    val code = item.sku.nonEmpty ?: item.inventory?.barcode.nonEmpty
    val fill = when {
        highlighted -> Theme.wash(Theme.butter, 22.0)
        selected == true -> Theme.wash(Theme.blue, 7.0)
        else -> Color.Transparent
    }
    val editLabel = LF("Edit %@", item.name)

    val tick: @Composable () -> Unit = {
        val isTicked = selected
        if (isTicked != null) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .requiredSize(32.dp)
                        .plainButton(label = LF("Select %@", item.name)) { onSelect?.invoke() }
                        .semantics { this.selected = isTicked },
                    contentAlignment = Alignment.Center,
                ) { Tick(on = isTicked) }
            }
        }
    }

    val flags: @Composable () -> Unit = {
        if (showKind || !item.active || item.requiresPrescription) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showKind) CashCatalogKindChip(itemType = item.itemType)
                if (!item.active) CashCatalogFlag(text = L("Archived"))
                if (item.requiresPrescription) {
                    CashCatalogFlag(text = L("Rx"), tone = Theme.pink, modifier = Modifier.clearAndSetSemantics { contentDescription = L("Prescription only") })
                }
            }
        }
    }

    val identity: @Composable (Modifier) -> Unit = { identityModifier ->
        Row(identityModifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CashCatalogThumb(item = item)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    cashCatalogHighlight(item.name, needle),
                    style = Theme.text(16, FontWeight.Bold),
                    color = if (item.active) Theme.inkStrong else Theme.inkSoft,
                    maxLines = if (wide) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val second = item.description?.trim().nonEmpty
                if (code != null || second != null) {
                    val line = buildAnnotatedString {
                        if (code != null) {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)) { append(cashCatalogHighlight(code.uppercase(), needle)) }
                        }
                        append(if (code != null && second != null) " · " else "")
                        if (second != null) append(cashCatalogHighlight(second, needle))
                    }
                    Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!wide) Box(Modifier.padding(top = 2.dp)) { flags() }
            }
        }
    }

    val categoryText: @Composable (Modifier) -> Unit = { textModifier ->
        val category = item.category
        Text(
            category?.let { cashCatalogHighlight(it.name, needle) } ?: AnnotatedString(L("Uncategorized")),
            style = Theme.text(14, if (category != null) FontWeight.SemiBold else FontWeight.Normal),
            color = if (category != null) Theme.inkStrong else Theme.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = textModifier,
        )
    }

    val price: @Composable () -> Unit = {
        if (noPrice) {
            if (onPrice != null) {
                Row(
                    Modifier
                        .height(if (wide) 32.dp else 36.dp)
                        .background(Theme.wash(Theme.mango, 14.0), CapsuleShape)
                        .border(1.dp, Theme.mix(Theme.mango, 55.0, Theme.borderSoft), CapsuleShape)
                        .clip(CapsuleShape)
                        .plainButton(label = LF("Set the price of %@", item.name), onClick = onPrice)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("tag", 14.dp, tint = Theme.inkStrong)
                    Text(L("Set price"), style = Theme.text(12, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 1, softWrap = false)
                }
            } else {
                Text(L("No price"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                Text(L("Asked when charging"), style = Theme.text(12), color = Theme.inkSoft)
            }
        } else {
            Text(
                cashMoney(item.defaultUnitAmount, item.currency),
                style = Theme.heading(16).monospacedDigit(),
                color = if (item.active) Theme.inkStrong else Theme.inkSoft,
                maxLines = 1,
            )
            Text(CashCatalogLook.taxNote(item, settings), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    val detail: @Composable () -> Unit = {
        val align = if (wide) TextAlign.Start else TextAlign.End
        if (item.isProduct) {
            Column(horizontalAlignment = if (wide) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CashCatalogStockPill(state = CashCatalogLook.stock(item), sourceName = packSourceName, alignTrailing = !wide)
                if (packs > 0) {
                    Text(LP(packs, "Also sold as %d pack", "Also sold as %d packs"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, textAlign = align)
                }
            }
        } else if (visits.isNotEmpty()) {
            Column(horizontalAlignment = if (wide) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("calendar-check-2", 14.dp, tint = Theme.inkSoft)
                    Text(
                        if (visits.size > 2) LF("%@ and %d more", visits[0], visits.size - 1) else visits.joinToString(", "),
                        style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (visits.size == 1) L("This visit starts its bill with it") else L("These visits start their bill with it"),
                    style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
                )
            }
        } else if (wide) {
            Text("—", style = Theme.text(14), color = Theme.inkSoft)
        }
    }

    val menu: @Composable (Modifier) -> Unit = { menuModifier ->
        val entries = buildList {
            if (onOpen != null) add(CashCatalogMenuEntry(L("Edit"), "pencil", false, onOpen))
            if (onPrice != null) add(CashCatalogMenuEntry(if (noPrice) L("Set price") else L("Change price"), "tag", false, onPrice))
            if (onDuplicate != null) add(CashCatalogMenuEntry(L("Duplicate"), "copy", false, onDuplicate))
            val receives = ownStock && item.active && onReceive != null
            if (receives && onReceive != null) add(CashCatalogMenuEntry(L("Receive stock"), "package-plus", true, onReceive))
            if (ownStock && item.inventory != null && onHistory != null) add(CashCatalogMenuEntry(L("Stock history"), "history", !receives, onHistory))
            if (item.active && onArchive != null) add(CashCatalogMenuEntry(L("Archive"), "archive", true, onArchive))
            if (!item.active && onRestore != null) add(CashCatalogMenuEntry(L("Restore"), "archive-restore", true, onRestore))
        }
        if (entries.isEmpty()) {
            Spacer(menuModifier.size(40.dp))
        } else {
            AppMenu(
                label = { IconCircleLabel(size = 40.dp, bordered = false) },
                accessibilityLabel = LF("Actions for %@", item.name),
                modifier = menuModifier,
            ) {
                entries.forEachIndexed { index, entry ->
                    if (entry.separated && index > 0) divider()
                    item(entry.title, icon = entry.icon) { entry.run() }
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .background(fill)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = onOpen != null) { onOpen?.invoke() }
            .semantics {
                if (onOpen != null) customActions = listOf(CustomAccessibilityAction(editLabel) { onOpen(); true })
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                tick()
                identity(Modifier.weight(1f))
                Column(Modifier.width(CashCatalogRow.categoryWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categoryText(Modifier)
                    flags()
                }
                Column(Modifier.width(CashCatalogRow.priceWidth), horizontalAlignment = Alignment.End) { price() }
                Box(Modifier.width(CashCatalogRow.detailWidth)) { detail() }
                menu(Modifier.width(40.dp))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selected != null) Box(Modifier.padding(top = 12.dp)) { tick() }
                    identity(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) { price() }
                }
                Row(
                    Modifier.padding(start = 56.dp + (if (selected != null) 32.dp else 0.dp)),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    categoryText(Modifier.weight(1f))
                    detail()
                    menu(Modifier.overhangEnd(8.dp))
                }
            }
        }
    }
}

/**
 * The text with what was searched for marked in MIAW yellow (web `Highlight`).
 * Matching ignores case and accents.
 */
fun cashCatalogHighlight(text: String, needle: String): AnnotatedString {
    if (needle.isEmpty()) return AnnotatedString(text)
    val folded = cashCatalogFold(text)
    // Folding can change the length of a string with combining marks; only mark when it didn't.
    if (folded.length != text.length) return AnnotatedString(text)
    val start = folded.indexOf(needle)
    if (start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(background = Theme.lime.copy(alpha = 0.55f)), start, start + needle.length)
    }
}

/** SwiftUI `.grayscale(1)`: the view drawn without colour. */
private fun Modifier.grayscale(on: Boolean): Modifier = if (!on) this else drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}

/** The item's photo, or its kind's icon on a tile tinted with the kind's colour (web `ItemThumb`). */
@Composable
fun CashCatalogThumb(item: CashCatalogItem, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { }
            .alpha(if (item.active) 1f else 0.55f)
            .grayscale(!item.active)
            .clip(shape)
            .background(Theme.wash(CashCatalogLook.hue(item.itemType).color, 24.0), shape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(CashCatalogLook.icon(item), 20.dp, tint = Theme.inkStrong)
        APIConfig.assetURL(item.photoUrl)?.let { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Service or Product, with its colour (web `KindChip`): 20 tall, a 6 dot. */
@Composable
fun CashCatalogKindChip(itemType: String, modifier: Modifier = Modifier) {
    val hue = CashCatalogLook.hue(itemType).color
    Row(
        modifier
            .height(20.dp)
            .background(Theme.wash(hue, 22.0), CapsuleShape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(hue, CircleShape))
        Text(if (itemType == "product") L("Product") else L("Service"), style = brandText(10.9, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 1)
    }
}

/** A small flag next to a name (web `Flag`): Archived, Rx. */
@Composable
fun CashCatalogFlag(text: String, tone: Color = Theme.inkSoft, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(20.dp)
            .background(Theme.wash(tone, 10.0), CapsuleShape)
            .border(1.dp, Theme.mix(tone, 45.0, Theme.borderSoft), CapsuleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = brandText(10.9, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 1)
    }
}

/**
 * Where the stock stands (web `StockPill`): a coloured pill for tracked
 * products with a line under it, a quiet line otherwise.
 */
@Composable
fun CashCatalogStockPill(state: CashCatalogLook.Stock, sourceName: String? = null, alignTrailing: Boolean = false, modifier: Modifier = Modifier) {
    val horizontal = if (alignTrailing) Alignment.End else Alignment.Start
    val align = if (alignTrailing) TextAlign.End else TextAlign.Start
    when (state) {
        CashCatalogLook.Stock.none -> Unit
        CashCatalogLook.Stock.untracked -> Text(L("Not tracked"), style = Theme.text(14), color = Theme.inkSoft, modifier = modifier)
        is CashCatalogLook.Stock.pack -> Column(modifier, horizontalAlignment = horizontal) {
            Text(LF("Pack of %d", state.units), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(sourceName?.let { LF("Draws on %@", it) } ?: L("Draws on another product"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
        }
        else -> {
            val isOut = state is CashCatalogLook.Stock.out
            val tone = when (state) {
                is CashCatalogLook.Stock.out -> ProviderState.problem.color
                is CashCatalogLook.Stock.expired -> ChipTone.expired.color
                is CashCatalogLook.Stock.low, CashCatalogLook.Stock.setup -> ProviderState.waiting.color
                else -> ProviderState.done.color
            }
            val label = when (state) {
                CashCatalogLook.Stock.setup -> L("Stock not set up")
                is CashCatalogLook.Stock.out -> L("Out of stock")
                is CashCatalogLook.Stock.expired -> L("All expired")
                is CashCatalogLook.Stock.low -> if (state.quantity == 1) LF("%d left@one", state.quantity) else LF("%d left", state.quantity)
                is CashCatalogLook.Stock.ok -> LF("%d in stock", state.quantity)
                else -> ""
            }
            val hint: String? = when (state) {
                is CashCatalogLook.Stock.out ->
                    if (state.quantity < 0) LP(-state.quantity, "%d short — it needs a count@one", "%d short — it needs a count")
                    else if (state.backorder) L("Backorders allowed")
                    else if (state.reorderPoint > 0) LF("Reorder at %d", state.reorderPoint) else null
                is CashCatalogLook.Stock.expired -> LP(state.quantity, "%d on the shelf, none to sell@one", "%d on the shelf, none to sell")
                is CashCatalogLook.Stock.low ->
                    listOfNotNull(if (state.expired > 0) LF("%d to sell", state.quantity - state.expired) else null, LF("Reorder at %d", state.reorderPoint)).joinToString(" · ")
                is CashCatalogLook.Stock.ok ->
                    listOfNotNull(if (state.expired > 0) LF("%d to sell", state.quantity - state.expired) else null, state.location).joinToString(" · ").nonEmpty
                CashCatalogLook.Stock.setup -> L("Add the count to start tracking")
                else -> null
            }
            Column(modifier, horizontalAlignment = horizontal, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier
                        .height(24.dp)
                        .background(Theme.wash(tone, 20.0), CapsuleShape)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(tone, CircleShape))
                    Text(label, style = Theme.text(12, FontWeight.ExtraBold), color = if (isOut) Theme.destructive else Theme.inkStrong, maxLines = 1)
                }
                if (hint != null) Text(hint, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
            }
        }
    }
}

// MARK: - Categories

/**
 * The categories that group the catalog, services and products apart, with
 * how many items each holds (web `CatalogCategoriesPanel`). Picking one
 * filters the list above; "Manage" opens the manager.
 */
@Composable
private fun CashCatalogCategoriesPanel(
    categories: List<CashCategory>,
    meta: Map<String, CashCategoryMeta>,
    counts: Map<String, Int>,
    loading: Boolean,
    activeCategory: String?,
    activeType: String?,
    canManage: Boolean,
    onPick: (String, String) -> Unit,
    onManage: () -> Unit,
) {
    SectionCard(
        title = L("Categories"), subtitle = L("Group items so the list, invoices and the store stay tidy."), icon = "tags", accent = Theme.blue,
        trailing = {
            if (canManage) PMButton(L("Manage"), systemImage = "settings-2", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onManage)
        },
    ) {
        if (loading) {
            LoadingState()
        } else if (categories.isEmpty()) {
            EmptyState(
                systemImage = "tags", title = L("No categories yet"),
                message = L("Items without one show as Uncategorized. Add categories such as Grooming, Food or Medicines."),
                actionTitle = if (canManage) L("Add categories") else null, action = if (canManage) onManage else null, accent = Theme.mango,
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("service", "product").forEach { type ->
                    val list = categories.filter { it.itemType == type }.sortedWith(compareBy<CashCategory>({ if (it.active) 0 else 1 }, { it.name.lowercase() }))
                    val uncategorized = counts["none:$type"] ?: 0
                    if (list.isNotEmpty() || uncategorized > 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(CashCatalogLook.hue(type).color, CircleShape))
                                Text(
                                    (if (type == "product") L("Product categories") else L("Service categories")).uppercase(),
                                    style = brandText(10.6, FontWeight.ExtraBold).tracking(1.27), color = Theme.inkSoft,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                list.forEach { category ->
                                    CategoryRow(
                                        name = category.name, count = counts[category.id] ?: 0, active = activeCategory == category.id, archived = !category.active,
                                        meta = metaLine(meta, category.id),
                                    ) { onPick(category.id, type) }
                                }
                                if (uncategorized > 0) {
                                    CategoryRow(
                                        name = L("Uncategorized"), count = uncategorized,
                                        active = activeCategory == "none" && (activeType == null || activeType == type), quiet = true,
                                    ) { onPick("none", type) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun metaLine(meta: Map<String, CashCategoryMeta>, id: String): String? {
    val entry = meta[id] ?: return null
    val created = entry.createdAt ?: return null
    return LF("Added %@", DateText.date(created)) + (entry.createdBy.nonEmpty?.let { " · $it" } ?: "")
}

@Composable
private fun CategoryRow(
    name: String,
    count: Int,
    active: Boolean,
    archived: Boolean = false,
    quiet: Boolean = false,
    meta: String? = null,
    action: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme.chipRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) Theme.wash(Theme.blue, 10.0) else Color.Transparent, shape)
            .border(1.dp, if (active) Theme.blue else Color.Transparent, shape)
            .clip(shape)
            .plainButton(label = name, onClick = action)
            .semantics { selected = active }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = Theme.text(14, FontWeight.Bold).copy(fontStyle = if (quiet) FontStyle.Italic else FontStyle.Normal),
                color = if (quiet || archived) Theme.inkSoft else Theme.inkStrong,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) Text(meta, style = Theme.text(11.2), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (archived) {
            Text(
                L("Inactive"), style = brandText(10.6, FontWeight.ExtraBold), color = Theme.inkSoft,
                modifier = Modifier.border(1.dp, Theme.borderSoft, CapsuleShape).padding(horizontal = 8.dp),
            )
        }
        Box(
            Modifier
                .heightIn(min = 24.dp)
                .widthIn(min = 24.dp)
                .background(Theme.inkSoft.copy(alpha = 0.12f), CapsuleShape)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$count", style = Theme.text(12, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkStrong)
        }
    }
}

// MARK: - Archive

/** Archiving an item: what still leans on it, then the yes (web `CatalogArchiveSheet`). */
@Composable
fun CashArchiveSheet(
    item: CashCatalogItem,
    /** The kinds of visit that start their bill with it. */
    visits: List<String> = emptyList(),
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<CashCatalogUsage?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        usage = attempt { API.get<CashCatalogUsage>("/provider-catalog/${item.id}/usage") }
        loaded = true
    }

    /** Up to three names, then "and 2 more". */
    fun names(list: List<String>): String =
        if (list.size <= 3) list.joinToString(", ") else LF("%@ and %d more", list.take(3).joinToString(", "), list.size - 3)

    val warnings: List<Pair<String, String>> = buildList {
        if (visits.isNotEmpty()) {
            add("calendar-check-2" to (if (visits.size == 1)
                LF("%@ starts its bill with it. That visit will start without a price, and families will not see one, until you choose another item under “What each visit charges”.", names(visits))
            else
                LF("%@ start their bill with it. Those visits will start without a price, and families will not see one, until you choose another item under “What each visit charges”.", names(visits))))
        }
        val known = usage
        if (known != null) {
            val packs = known.packs.map { it.name }
            if (packs.isNotEmpty()) {
                add("boxes" to (if (packs.size == 1)
                    LF("The pack %@ draws on its stock. Archive that pack first, or change the product it draws on.", names(packs))
                else
                    LF("The packs %@ draw on its stock. Archive those packs first, or change the product they draw on.", names(packs))))
            }
            if (known.stockOnHand > 0) {
                add("boxes" to LP(known.stockOnHand, "%d is still on the shelf. It leaves Inventory with the product and comes back when you restore it.", "%d are still on the shelf. They leave Inventory with the product and come back when you restore it."))
            }
            val rx = known.prescriptions
            if (rx != null && rx > 0) {
                add("file-text" to LP(rx, "%d signed prescription still has fills of it left. Archived, it is no longer offered to fill them.", "%d signed prescriptions still have fills of it left. Archived, it is no longer offered to fill them."))
            }
            val orders = known.hospitalOrders
            if (orders != null && orders > 0) {
                add("bed-double" to LP(orders, "%d active hospital order gives it. Check that order before you archive it.", "%d active hospital orders give it. Check those orders before you archive it."))
            }
            if (known.openBills > 0) {
                add("receipt-text" to LP(known.openBills, "%d bill in progress has it. The bill keeps its line.", "%d bills in progress have it. Those bills keep their line."))
            }
        } else {
            val onShelf = item.inventory?.quantityOnHand
            if (item.trackInventory && !item.isPack && onShelf != null && onShelf > 0) {
                add("boxes" to LP(onShelf, "%d is still on the shelf. It leaves Inventory with the product and comes back when you restore it.", "%d are still on the shelf. They leave Inventory with the product and come back when you restore it."))
            }
        }
    }

    fun archive() {
        val known = usage
        if (known != null && known.packs.isNotEmpty()) {
            Toast.error(L("Packs draw on this product: archive them or change what they draw on first."))
            return
        }
        working = true
        scope.launch {
            try {
                API.patch<EmptyResponse>("/provider-catalog/${item.id}", mapOf("active" to false))
                Toast.show(LF("%@ was archived.", item.name))
                announceDataChanged()
                onDone()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Toast.error(failure, fallback = L("Could not archive this item. Try again."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Archive %@?", item.name),
        subtitle = L("It stops being offered on new bills, visits and at the counter. Past bills keep it, and you can restore it any time."),
        systemImage = "triangle-alert",
        accent = ModalAccent.pink,
        primaryTitle = L("Archive"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        primaryIcon = "triangle-alert",
        onPrimary = { archive() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        if (warnings.isNotEmpty() || !loaded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (warnings.isNotEmpty()) Text(L("Before you archive it"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                warnings.forEach { (icon, text) ->
                    NoticeCard(tone = NoticeCard.Tone.custom(Theme.lime), message = text, icon = icon)
                }
                if (!loaded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                        Text(L("Checking where it is used…"), style = Theme.text(14), color = Theme.inkSoft)
                    }
                }
            }
        }
    }
}
