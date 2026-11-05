package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.Collator
import kotlin.math.roundToInt

//
//  CashCatalogBulk.kt (iOS CashCatalogBulk.swift)
//
//  Changes to several catalog items at once (web: catalog-page/catalog-bulk.tsx,
//  use-provider-catalog.ts `useBulkUpdateCatalogItems`): move them to a
//  category, raise or lower their prices by a percentage, archive or restore
//  them. The server has no bulk endpoint: one request per item, each logged on
//  its own; what fails is listed with why and does not stop the rest. Also the
//  quick price of one item (catalog-price-sheet.tsx) and the categories
//  (catalog-category-manager.tsx).
//

/** What a run over several items left undone, with why: (name, message). */
class CashBulkOutcome {
    var done: Int = 0
    val failed: MutableList<Pair<String, String>> = mutableListOf()
}

object CashCatalogBulk {
    /** The same change to each item, one request after another; a dropped connection stops the run. */
    suspend fun run(changes: List<Pair<CashCatalogItem, Map<String, Any?>>>, progress: (Int) -> Unit): CashBulkOutcome {
        val outcome = CashBulkOutcome()
        for ((index, change) in changes.withIndex()) {
            val (item, body) = change
            try {
                API.patch<EmptyResponse>("/provider-catalog/${item.id}", body)
                outcome.done += 1
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                outcome.failed.add(item.name to errorText(error, fallback = ""))
                val api = error as? APIError
                if (api != null && api.isOffline) {
                    for (rest in changes.drop(index + 1)) outcome.failed.add(rest.first.name to api.message)
                    break
                }
            }
            progress(index + 1)
        }
        if (outcome.done > 0) announceDataChanged()
        return outcome
    }

    /** A price moved by a percentage (10 raises it 10 %, −10 lowers it); null when it would not be a price. */
    fun movedPrice(amount: Int, percent: Double, whole: Boolean): Int? {
        if (amount <= 0) return null
        val raw = amount.toDouble() * (100 + percent) / 100
        val next = if (whole) (raw / 100).roundToInt() * 100 else raw.roundToInt()
        return if (next > 0) next else null
    }
}

/** The failures of a run. */
@Composable
private fun CashBulkFailures(outcome: CashBulkOutcome) {
    val failed = outcome.failed
    NoticeCard(
        tone = NoticeCard.Tone.problem,
        title = if (outcome.done > 0) LF("%d were changed; %d could not be.", outcome.done, failed.size)
        else LP(failed.size, "%d item could not be changed.", "%d items could not be changed."),
        message = (failed.take(6).map { (name, message) -> "$name${if (message.isEmpty()) "" else " — $message"}" } +
            (if (failed.size > 6) listOf(LF("…and %d more", failed.size - 6)) else emptyList())).joinToString("\n"),
    )
}

private fun cashBusyTitle(progress: Pair<Int, Int>?): String =
    progress?.let { LF("Saving %d of %d…", it.first, it.second) } ?: L("Saving…")

/** What the sheet is about (web `ModalContextPills`): how many items, and their names when one or two. */
@Composable
private fun CashCountPills(items: List<CashCatalogItem>) {
    FlowLayout(spacing = 6.dp) {
        Chip(text = LP(items.size, "%d item", "%d items"), tone = Chip.Tone.muted)
        if (items.size <= 2) items.forEach { key(it.id) { Chip(text = it.name, tone = Chip.Tone.muted) } }
    }
}

/** Swift `localizedCaseInsensitiveCompare`. */
private fun caseInsensitive(): Collator = Collator.getInstance(appLocale).apply { strength = Collator.SECONDARY }

/** The categories' order: offered first, then by name. */
private val categoryOrder: Comparator<CashCategory> = compareBy<CashCategory>({ if (it.active) 0 else 1 }, { it.name.lowercase() })

// MARK: - Category

@Composable
fun CashBulkCategorySheet(
    items: List<CashCatalogItem>,
    categories: List<CashCategory>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var categoryId by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var outcome by remember { mutableStateOf<CashBulkOutcome?>(null) }

    val itemType: String? = if (items.map { it.itemType }.toSet().size == 1) items.firstOrNull()?.itemType else null
    val collator = remember { caseInsensitive() }
    val offered = categories.filter { it.itemType == itemType && it.active }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    val changes = items.filter { (it.category?.id ?: "") != categoryId }

    fun apply() {
        val targets = changes
        val chosen = offered.firstOrNull { it.id == categoryId }
        progress = 0 to targets.size
        scope.launch {
            val result = CashCatalogBulk.run(targets.map { it to mapOf<String, Any?>("providerCatalogCategoryId" to categoryId.nonEmpty) }) { done -> progress = done to targets.size }
            progress = null
            if (result.failed.isEmpty()) {
                Toast.show(
                    chosen?.let { LF(if (result.done == 1) "%d item moved to %@." else "%d items moved to %@.", result.done, it.name) }
                        ?: LP(result.done, "%d item is now uncategorized.", "%d items are now uncategorized."),
                )
                onDone()
                dismiss()
            } else {
                outcome = result
            }
        }
    }

    ProviderModal(
        title = L("Change category"),
        subtitle = L("Every selected item moves to the category you choose."),
        systemImage = "tags",
        accent = ModalAccent.aqua,
        isDirty = touched,
        primaryTitle = if (progress != null) cashBusyTitle(progress) else LP(changes.size, "Move %d item", "Move %d items"),
        isWorking = progress != null,
        cancelTitle = if (outcome != null) L("Close") else L("Cancel"),
        primaryIcon = "tags",
        primaryDisabled = itemType == null || changes.isEmpty(),
        onPrimary = { apply() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        CashCountPills(items)
        if (itemType == null) {
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("You selected services and products. A category belongs to one or the other: select only services, or only products."))
        } else {
            MenuField(
                label = if (itemType == "product") L("Product category") else L("Service category"),
                options = listOf("" to L("Uncategorized")) + offered.map { it.id to it.name },
                selection = stateOf({ categoryId }) {
                    categoryId = it
                    touched = true
                },
            )
        }
        outcome?.let { CashBulkFailures(it) }
    }
}

// MARK: - Prices

private const val maxRaise = 500.0

@Composable
fun CashBulkPriceSheet(
    items: List<CashCatalogItem>,
    settings: CashStoreSettings?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val direction = remember { mutableStateOf("raise") }
    var percentText by remember { mutableStateOf("") }
    val whole = remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var outcome by remember { mutableStateOf<CashBulkOutcome?>(null) }

    val percent: Double? = percentText.replace("%", "").replace(",", ".").trim().toDoubleOrNull()
    val problem: String? = when {
        percent == null -> L("Type the percentage first.")
        !percent.isFinite() || percent <= 0 -> L("The percentage must be above 0.")
        direction.value == "lower" && percent >= 100 -> L("A price cannot be lowered by 100% or more.")
        direction.value == "raise" && percent > maxRaise -> LF("Raise prices by %d%% at most.", maxRaise.toInt())
        else -> null
    }
    val signed: Double? = if (problem == null) percent?.let { if (direction.value == "raise") it else -it } else null
    val preview: List<Pair<CashCatalogItem, Int?>> = items.map { item -> item to signed?.let { s -> CashCatalogBulk.movedPrice(item.defaultUnitAmount, percent = s, whole = whole.value) } }
    val priced = preview.filter { it.first.defaultUnitAmount > 0 }
    val changes: List<Pair<CashCatalogItem, Int>> = priced.mapNotNull { (item, next) -> next?.let { if (it != item.defaultUnitAmount) item to it else null } }

    fun apply() {
        if (!form.check(listOf("percent" to problem)) || changes.isEmpty()) return
        val targets = changes
        progress = 0 to targets.size
        scope.launch {
            val result = CashCatalogBulk.run(targets.map { (item, next) -> item to mapOf<String, Any?>("defaultUnitAmount" to next) }) { done -> progress = done to targets.size }
            progress = null
            if (result.failed.isEmpty()) {
                Toast.show(LP(result.done, "%d price changed.", "%d prices changed."))
                onDone()
                dismiss()
            } else {
                outcome = result
            }
        }
    }

    ProviderModal(
        title = L("Change prices"),
        subtitle = L("Raise or lower the price of every selected item by a percentage. You see each new price before it is saved."),
        systemImage = "percent",
        accent = ModalAccent.mango,
        isDirty = direction.value != "raise" || whole.value || percentText.isNotEmpty(),
        form = form,
        primaryTitle = if (progress != null) cashBusyTitle(progress) else if (signed == null) L("Change prices") else LP(changes.size, "Change %d price", "Change %d prices"),
        isWorking = progress != null,
        cancelTitle = if (outcome != null) L("Close") else L("Cancel"),
        primaryIcon = "percent",
        primaryDisabled = priced.isEmpty(),
        onPrimary = { apply() },
        modifier = modifier,
    ) {
        CashCountPills(items)
        FormField(label = L("What to do")) {
            Segmented(
                options = listOf(
                    Segmented.Option("raise", L("Raise"), icon = "trending-up", tone = ChipTone.page),
                    Segmented.Option("lower", L("Lower"), icon = "trending-down", tone = ChipTone.page),
                ),
                selection = direction,
                look = Segmented.Look.raised,
                label = L("What to do"),
                fill = true,
            )
        }
        FormField(label = L("By how much (%)"), key = "percent", form = form, required = true) {
            FieldChrome(invalid = form.error("percent") != null) {
                PlainTextField(
                    stateOf({ percentText }) {
                        percentText = it
                        form.clear("percent")
                    },
                    prompt = "10",
                    keyboard = KeyboardType.Decimal,
                    style = Theme.heading(18).monospacedDigit(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("%", style = Theme.text(16, FontWeight.Bold), color = Theme.inkSoft)
            }
        }
        ToggleRow(
            title = L("Round to whole amounts"),
            subtitle = LF("%@ instead of cents: prices end in .00.", cashMoney(11_000, settings?.currency)),
            isOn = whole,
        )
        val box = RoundedCornerShape(Theme.boxRadius)
        Column(Modifier.fillMaxWidth().clip(box).border(1.5.dp, Theme.borderSoft, box)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.surfaceMuted, 55.0))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val head = Theme.text(10.6, FontWeight.ExtraBold).tracking(1.27)
                Text(L("Item").uppercase(), style = head, color = Theme.inkSoft, maxLines = 1, modifier = Modifier.weight(1f))
                Text(L("Now").uppercase(), style = head, color = Theme.inkSoft, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
                Spacer(Modifier.width(14.dp))
                Text(L("After").uppercase(), style = head, color = Theme.inkSoft, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
            }
            preview.forEach { (item, next) ->
                key(item.id) {
                    RowDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (item.defaultUnitAmount > 0) {
                            Text(cashMoney(item.defaultUnitAmount, item.currency), style = Theme.text(14).monospacedDigit(), color = Theme.inkSoft, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
                            Glyph("arrow-right", 14.dp, tint = Theme.inkSoft)
                            Text(next?.let { cashMoney(it, item.currency) } ?: "—", style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
                        } else {
                            Text(L("No price: stays as it is"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                        }
                    }
                }
            }
        }
        val unpriced = preview.size - priced.size
        Text(
            (listOf(if (signed == null) LP(priced.size, "%d price will change.", "%d prices will change.") else LP(changes.size, "%d price changes.", "%d prices change.")) +
                (if (unpriced > 0) listOf(LP(unpriced, "%d item has no price and stays as it is.", "%d items have no price and stay as they are.")) else emptyList())).joinToString(" "),
            style = Theme.text(14), color = Theme.inkSoft,
        )
        outcome?.let { CashBulkFailures(it) }
    }
}

// MARK: - Archive and restore

@Composable
fun CashBulkArchiveSheet(
    items: List<CashCatalogItem>,
    /** Items a visit bills by default or a pack draws on, by id. */
    leanedOn: Set<String>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<CashBulkOutcome?>(null) }

    val targets = items.filter { it.active }
    val used = targets.filter { it.id in leanedOn }

    fun apply() {
        working = true
        scope.launch {
            val result = CashCatalogBulk.run(targets.map { it to mapOf<String, Any?>("active" to false) }) { }
            working = false
            if (result.failed.isEmpty()) {
                Toast.show(LP(result.done, "%d item archived.", "%d items archived."))
                onDone()
                dismiss()
            } else {
                outcome = result
            }
        }
    }

    ProviderModal(
        title = LP(targets.size, "Archive %d item?", "Archive %d items?"),
        subtitle = L("They stop being offered on new bills, visits and at the counter. Past bills keep them, and you can restore them any time."),
        systemImage = "triangle-alert",
        accent = ModalAccent.pink,
        primaryTitle = L("Archive"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = if (outcome != null) L("Close") else L("Cancel"),
        primaryIcon = "triangle-alert",
        onPrimary = if (targets.isEmpty()) null else ({ apply() }),
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        val result = outcome
        if (result != null) {
            CashBulkFailures(result)
        } else if (used.isNotEmpty()) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = LP(used.size, "%d of them is used by a visit or a pack:", "%d of them are used by visits or packs:"),
                message = used.take(5).joinToString(", ") { it.name } + (if (used.size > 5) " " + LF("…and %d more", used.size - 5) else "") +
                    "\n" + L("Those visits start without a price and those packs stop working until you restore the item or point them at another."),
            )
        }
    }
}

// MARK: - One item's price

/** "Cambiar precio": the price staff see first on a bill or at the counter, and its tax. */
@Composable
fun CashCatalogPriceSheet(
    item: CashCatalogItem,
    settings: CashStoreSettings?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    // iOS `.onAppear`: the box starts on the saved price.
    val price = remember { mutableStateOf<Int?>(if (item.defaultUnitAmount > 0) item.defaultUnitAmount else null) }
    var working by remember { mutableStateOf(false) }

    /** What a client pays at the typed price, with the item's own tax (web `clientPrice`). */
    val pays: String? = price.value?.takeIf { it > 0 }?.let { typed ->
        val bps = CashCatalogRules.effectiveRate(treatment = item.taxTreatment, rateBps = item.taxRateBps, settings = settings)
        CashCatalogRules.payText(price = typed, bps = bps, settings = settings)
    }

    /** Only the price is sent, so nothing else on the item is touched; here an empty box is not "leave it without one". */
    fun save() {
        if (!form.check(listOf("price" to CashCatalogRules.priceError(price.value, item = null)))) return
        if (price.value == item.defaultUnitAmount) {
            dismiss()
            return
        }
        working = true
        scope.launch {
            try {
                API.patch<EmptyResponse>("/provider-catalog/${item.id}", mapOf("defaultUnitAmount" to (price.value ?: 0)))
                Toast.show(LF("%@ now costs %@.", item.name, cashMoney(price.value ?: 0, item.currency)))
                announceDataChanged()
                onSaved()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                form.apply(error, known = mapOf("defaultUnitAmount" to "price"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (item.defaultUnitAmount > 0) L("Change price") else L("Set price"),
        subtitle = L("What staff see first on an invoice or at the counter. It can be changed on each sale."),
        systemImage = "tag",
        accent = ModalAccent.hue(CashCatalogLook.hue(item.itemType)),
        isDirty = price.value != null && price.value != item.defaultUnitAmount,
        form = form,
        primaryTitle = L("Save price"),
        isWorking = working,
        primaryIcon = "save",
        onPrimary = { save() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        FlowLayout(spacing = 6.dp) {
            Chip(text = item.name, tone = Chip.Tone.muted)
            if (item.defaultUnitAmount > 0) {
                Chip(text = LF("Now %@", cashMoney(item.defaultUnitAmount, item.currency)), tone = Chip.Tone.muted)
            }
        }
        MoneyField(
            label = LF("Price (%@)", settings?.currency ?: item.currency ?: ""),
            cents = price,
            key = "price",
            form = form,
            hint = pays ?: (if (item.defaultUnitAmount == 0) L("No price yet: it will be asked for when charging. Add one here to stop asking.") else null),
            required = true,
        )
    }
}

// MARK: - Categories

/**
 * The categories of services and of products: add, rename, stop offering, remove
 * (web `CatalogCategoryManager`). Changes are saved as they are made.
 */
@Composable
fun CashCategoryManagerSheet(
    categories: List<CashCategory>,
    items: List<CashCatalogItem>,
    startType: String = "service",
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    // iOS `.onAppear { list = categories; type = startType }`.
    var list by remember { mutableStateOf(categories) }
    var type by remember { mutableStateOf(startType) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<CashCategory?>(null) }
    var renameText by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<CashCategory?>(null) }
    var working by remember { mutableStateOf(false) }
    var savingId by remember { mutableStateOf<String?>(null) }

    val canCreate = workspace.can(Permission.catalogCreate)
    val canEdit = workspace.can(Permission.catalogEdit)
    val shown = list.filter { it.itemType == type }.sortedWith(categoryOrder)
    fun count(category: CashCategory): Int = items.count { it.category?.id == category.id }

    fun add() {
        val duplicate = list.any { it.itemType == type && it.name.lowercase() == newName.trim().lowercase() }
        if (!form.check(listOf("name" to (if (newName.nonEmpty == null) L("Add the category name first.") else if (duplicate) L("There is already a category with that name.") else null)))) return
        val text = newName.nonEmpty ?: return
        working = true
        scope.launch {
            try {
                val created: CashCategory = API.post("/provider-catalog/categories", mapOf("itemType" to type, "name" to text, "active" to true))
                list = list + created
                newName = ""
                Toast.show(LF("%@ is ready to use.", created.name))
                onChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                form.apply(error, known = mapOf("name" to "name"))
            } finally {
                working = false
            }
        }
    }

    fun rename(category: CashCategory) {
        if (!form.check(listOf("rename" to (if (renameText.nonEmpty == null) L("Add the category name first.") else null)))) return
        val text = renameText.nonEmpty ?: return
        working = true
        savingId = category.id
        scope.launch {
            try {
                val saved: CashCategory = API.patch("/provider-catalog/categories/${category.id}", mapOf("name" to text))
                list = list.map { if (it.id == category.id) saved else it }
                Toast.show(LF("%@ is now %@.", category.name, saved.name))
                renaming = null
                onChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                form.apply(error, known = mapOf("name" to "rename"))
            } finally {
                working = false
                savingId = null
            }
        }
    }

    fun toggle(category: CashCategory, active: Boolean) {
        if (active == category.active) return
        savingId = category.id
        scope.launch {
            try {
                val saved: CashCategory = API.patch("/provider-catalog/categories/${category.id}", mapOf("active" to active))
                list = list.map { if (it.id == category.id) saved else it }
                Toast.show(
                    if (saved.active) LF("%@ is offered for new items again.", saved.name)
                    else LF("%@ is no longer offered for new items. The items in it keep it.", saved.name),
                )
                onChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.error(error, fallback = L("Could not change this category."))
            } finally {
                savingId = null
            }
        }
    }

    @Composable
    fun iconButton(icon: String, label: String, action: () -> Unit) {
        IconCircleButton(systemImage = icon, label = label, size = 40.dp, bordered = false, action = action)
    }

    @Composable
    fun row(category: CashCategory) {
        val inIt = count(category)
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (renaming?.id == category.id) {
                Column(Modifier.fillMaxWidth().formScrollTarget("rename", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FieldChrome(invalid = form.error("rename") != null, modifier = Modifier.weight(1f)) {
                            PlainTextField(
                                stateOf({ renameText }) {
                                    renameText = it.take(80)
                                    form.clear("rename")
                                },
                                imeAction = ImeAction.Done,
                                onSubmit = { rename(category) },
                                modifier = Modifier.weight(1f).semantics { contentDescription = LF("New name for %@", category.name) },
                            )
                        }
                        PMButton(L("Save"), systemImage = "check", isWorking = working && savingId == category.id) { rename(category) }
                        iconButton("x", L("Cancel the rename")) {
                            renaming = null
                            form.clear("rename")
                        }
                    }
                    form.error("rename")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(category.name, style = Theme.text(14, FontWeight.Bold), color = if (category.active) Theme.inkStrong else Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(if (inIt == 0) L("No items") else LP(inIt, "%d item", "%d items"), if (category.active) null else L("Not offered for new items")).joinToString(" · "),
                            style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (canEdit) {
                        if (savingId == category.id && renaming == null) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                        }
                        PMSwitchStyle(
                            isOn = stateOf({ category.active }) { toggle(category, it) },
                            enabled = savingId == null,
                            modifier = Modifier.semantics { contentDescription = LF("Offer %@ for new items", category.name) },
                        )
                        iconButton("pencil", LF("Rename %@", category.name)) {
                            renameText = category.name
                            form.clear("rename")
                            renaming = category
                        }
                        iconButton("trash-2", LF("Remove %@", category.name)) { removing = category }
                    }
                }
            }
        }
    }

    // Changes save as they are made: the footer is only "Done" (web `CatalogCategoryManager`).
    ProviderModal(
        title = L("Categories"),
        subtitle = L("Group what you charge for and what you sell. Services and products have their own categories; changes are saved as you make them."),
        systemImage = "tags",
        accent = ModalAccent.aqua,
        form = form,
        primaryTitle = L("Done"),
        showsCancel = false,
        onPrimary = { dismiss() },
        modifier = modifier,
    ) {
        Segmented(
            options = listOf(
                Segmented.Option("service", L("Services"), icon = "briefcase-medical", count = list.count { it.itemType == "service" }, tone = CashCatalogLook.tone("service")),
                Segmented.Option("product", L("Products"), icon = "package", count = list.count { it.itemType == "product" }, tone = CashCatalogLook.tone("product")),
            ),
            selection = stateOf({ type }) {
                type = it
                renaming = null
                form.clear("name")
            },
            look = Segmented.Look.raised,
            label = L("Kind of category"),
            fill = true,
        )
        if (canCreate) {
            Column(Modifier.fillMaxWidth().formScrollTarget("name", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldChrome(invalid = form.error("name") != null, modifier = Modifier.weight(1f)) {
                        PlainTextField(
                            stateOf({ newName }) {
                                newName = it.take(80)
                                form.clear("name")
                            },
                            prompt = if (type == "product") L("New product category, e.g. Food") else L("New service category, e.g. Grooming"),
                            imeAction = ImeAction.Done,
                            onSubmit = { add() },
                            modifier = Modifier.weight(1f).semantics { contentDescription = if (type == "product") L("New product category") else L("New service category") },
                        )
                    }
                    PMButton(L("Add"), systemImage = "plus", isWorking = working && renaming == null) { add() }
                }
                form.error("name")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
            }
        }
        if (shown.isEmpty()) {
            EmptyState(
                systemImage = "tags",
                title = if (type == "product") L("No product categories yet") else L("No service categories yet"),
                message = if (type == "product") L("Add categories such as Food, Medicines or Accessories. Items without one show as Uncategorized.")
                else L("Add categories such as Consults, Grooming or Boarding. Items without one show as Uncategorized."),
                accent = Theme.mango,
            )
        } else {
            val box = RoundedCornerShape(Theme.boxRadius)
            Column(Modifier.fillMaxWidth().clip(box).border(1.5.dp, Theme.borderSoft, box)) {
                shown.forEachIndexed { index, category ->
                    key(category.id) {
                        if (index > 0) RowDivider()
                        row(category)
                    }
                }
            }
        }
        if (!canEdit && shown.isNotEmpty()) {
            Text(L("Your role can add categories; renaming and removing them takes permission to edit the catalog."), style = Theme.text(12), color = Theme.inkSoft)
        }
    }

    removing?.let { category ->
        val inIt = count(category)
        AppSheet(onDismissRequest = { removing = null }) {
            ConfirmSheet(
                title = LF("Remove %@?", category.name),
                message = if (inIt > 0) LP(inIt, "%d item is in it. It stays in the catalog, without a category. This cannot be undone.", "%d items are in it. They stay in the catalog, without a category. This cannot be undone.")
                else L("No item uses it. This cannot be undone."),
                confirmTitle = L("Remove category"),
                destructive = true,
            ) { _ ->
                API.delete<EmptyResponse>("/provider-catalog/categories/${category.id}")
                list = list.filter { it.id != category.id }
                Toast.show(
                    if (inIt > 0) LF(if (inIt == 1) "%@ was removed. %d item is now uncategorized." else "%@ was removed. %d items are now uncategorized.", category.name, inIt)
                    else LF("%@ was removed.", category.name),
                )
                onChanged()
            }
        }
    }
}
