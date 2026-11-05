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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max

//
//  InvoicesScreen.kt (iOS InvoicesScreen.swift)
//
//  Cuentas (web: routes/provider.invoices.tsx, invoices-page/*): every bill —
//  visits, stays, hospital, counter sales, deposits and bills made by hand —
//  in one ledger, and "Por cobrar", the finished work still to charge. Every
//  filter, count and total is the server's. The page is the front desk's
//  (money level 2); "Cobrado este mes" and a list's totals are level 3.
//

// MARK: - The rules of a bill (invoices-page/invoice-model.ts)

object CashBillRules {
    val kinds = listOf("visit", "stay", "hospital", "sale", "deposit", "other")
    val collectible = listOf("ready_for_payment", "open", "partially_paid", "overdue")

    fun kind(bill: CashInvoice): String {
        val origin = bill.origin
        if (origin != null && origin in kinds) return origin
        val source = bill.createdFromSourceType ?: ""
        if (bill.kind == "sale" || source == "store") return "sale"
        if (bill.kind == "deposit" || source == "deposit" || source.endsWith("_deposit")) return "deposit"
        if (source == "stay") return "stay"
        if (source == "hospitalization") return "hospital"
        if (bill.providerAppointmentId != null || source == "provider_appointment" || source == "appointment_fee") return "visit"
        return "other"
    }

    fun kindLabel(kind: String): String = when (kind) {
        "visit" -> L("Visit")
        "stay" -> L("Stay")
        "hospital" -> L("Hospital")
        "sale" -> L("Store sale")
        "deposit" -> L("Deposit")
        else -> L("Manual bill")
    }

    fun kindFilter(kind: String): String = when (kind) {
        "visit" -> L("Visits")
        "stay" -> L("Stays")
        "hospital" -> L("Hospital")
        "sale" -> L("Store")
        "deposit" -> L("Deposits")
        else -> L("Manual")
    }

    fun kindHue(kind: String): Hue = when (kind) {
        "visit" -> Hue.sky
        "stay" -> Hue.periwinkle
        "hospital" -> Hue.teal
        "sale" -> Hue.peach
        "deposit" -> Hue.mint
        else -> Hue.grey
    }

    /** The kind's Lucide icon (web invoice-bits `KIND_ICONS`). */
    fun kindIcon(kind: String): String = when (kind) {
        "visit" -> "stethoscope"
        "stay" -> "bed-double"
        "hospital" -> "siren"
        "sale" -> "shopping-bag"
        "deposit" -> "piggy-bank"
        else -> "file-text"
    }

    /** The kind's toned-chip colour (web `KIND_META[kind].accent`). */
    fun kindTone(kind: String): ChipTone = ChipTone.hue(kindHue(kind))

    /** What made the bill, to open it. */
    fun source(bill: CashInvoice): ProviderRoute? {
        val kind = kind(bill)
        val from = bill.createdFromSourceType ?: ""
        val id = bill.createdFromSourceId
        if (kind == "sale") return ProviderRoute.sale(bill.id)
        if (kind == "stay" || from == "stay_deposit") return id?.let { ProviderRoute.stay(it) }
        if (kind == "hospital" || from == "hospitalization_deposit") return id?.let { ProviderRoute.hospitalization(it) }
        if (from == "appointment_deposit") return id?.let { ProviderRoute.appointment(it) }
        bill.providerAppointmentId?.let { return ProviderRoute.appointment(it) }
        return null
    }

    /**
     * Which sources this person may open (web `sourceAllowed`): a sale is the Store's
     * (sales.sell or any money level); a visit, stay or hospitalization whoever sees visits.
     */
    fun sourceAllowed(route: ProviderRoute, w: Workspace): Boolean {
        if (route is ProviderRoute.sale) return w.can(Permission.salesSell) || w.moneyLevel >= MoneyLevel.charging
        return w.can(Permission.appointmentsView)
    }

    fun sourceLabel(route: ProviderRoute): String = when (route) {
        is ProviderRoute.stay -> L("Open the stay")
        is ProviderRoute.hospitalization -> L("Open the hospitalization")
        is ProviderRoute.sale -> L("Open the sale in the Store")
        else -> L("Open the visit")
    }

    fun isWalkIn(bill: CashInvoice): Boolean = kind(bill) == "sale" && bill.clientName.trim() == "Público en general"
    fun isCollectible(bill: CashInvoice): Boolean = bill.balanceDueAmount > 0 && bill.status in collectible
    fun dueDay(bill: CashInvoice): String = DateText.dayKey(DateText.parse(bill.dueAt ?: bill.issuedAt ?: bill.createdAt) ?: Instant.now())
    fun billDay(bill: CashInvoice): String = DateText.dayKey(DateText.parse(bill.issuedAt ?: bill.createdAt) ?: Instant.now())
    fun isOverdue(bill: CashInvoice, today: String): Boolean = isCollectible(bill) && dueDay(bill) < today

    fun daysLate(bill: CashInvoice, today: String): Int {
        val due = CashDay.date(dueDay(bill)) ?: return 0
        val now = CashDay.date(today) ?: return 0
        val zone = Workspace.shared.timeZone
        return max(0, ChronoUnit.DAYS.between(due.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate()).toInt())
    }

    /** Paid, and then all of it came back (a sale returned in full, a bill refunded in full). */
    fun fullyGivenBack(bill: CashInvoice): Boolean = bill.status == "paid" && bill.totalAmount > 0 && bill.refunded >= bill.totalAmount

    private fun sameYear(date: Instant): Boolean {
        val zone = Workspace.shared.timeZone
        return date.atZone(zone).year == ZonedDateTime.now(zone).year
    }

    /** "25 sep" — with the year when it isn't this year's (web `invoiceDay`). */
    fun day(iso: String?): String {
        val date = DateText.parse(iso) ?: return ""
        return DateText.format(date, if (sameYear(date)) "dMMM" else "dMMMyyyy")
    }

    /** "Martes, 4 de noviembre" — the day a group of bills belongs to (web `invoiceWeekday`). */
    fun weekday(key: String): String {
        val date = CashDay.date(key) ?: return key
        val text = DateText.format(date, if (sameYear(date)) "EEEEdMMMM" else "EEEEdMMMMyyyy")
        return text.take(1).uppercase() + text.drop(1)
    }

    /** "25 sep 2026, 4:30 p. m." (web `invoiceDateTime`). */
    fun dateTime(iso: String?): String {
        val date = DateText.parse(iso) ?: return ""
        return DateText.format(date, "dMMMyyyyjmm")
    }

    /** The line under the status: how late, when it is due, what a draft still needs (web `statusHint`); (text, late). */
    fun statusHint(bill: CashInvoice, today: String, canFinalize: Boolean): Pair<String, Boolean>? {
        if (isOverdue(bill, today)) {
            val late = daysLate(bill, today)
            return (if (late == 0) L("Marked overdue") else CashText.daysLate(late)) to true
        }
        if (isCollectible(bill)) return bill.dueAt?.let { LF("Due %@", day(it)) to false }
        if (bill.status == "draft") {
            if (lineCount(bill) == 0) return L("No items yet") to false
            if (bill.totalAmount <= 0) return L("No price yet") to false
            return (if (canFinalize) L("Ready to send") else L("Waiting for review")) to false
        }
        if (fullyGivenBack(bill)) return null
        if (bill.status == "paid") {
            val paid = bill.allPayments.filter { !it.voided && !it.isRefund }.mapNotNull { it.paidAt }.sorted().lastOrNull()
            return paid?.let { LF("Paid %@@date", day(it)) to false }
        }
        return null
    }

    fun lineCount(bill: CashInvoice): Int = if (bill.lines.isEmpty()) bill.lineItemCount ?: 0 else bill.lines.size
    fun draftNeedsPrice(bill: CashInvoice): Boolean = bill.status == "draft" && (lineCount(bill) == 0 || bill.totalAmount <= 0)
    fun netTotal(bill: CashInvoice): Int = max(0, bill.totalAmount - bill.refunded)

    /** The chip a bill wears: late wins; a draft says when it lacks a price. */
    fun chip(bill: CashInvoice, today: String, canFinalize: Boolean): Pair<String, ProviderState> {
        if (isOverdue(bill, today)) return L("Overdue@status") to ProviderState.problem
        if (draftNeedsPrice(bill)) return L("Needs a price@status") to ProviderState.waiting
        if (bill.status == "draft") return (if (canFinalize) L("Draft@status") else L("In review@status")) to ProviderState.upcoming
        if (fullyGivenBack(bill)) {
            return (if (kind(bill) == "sale") L("Returned@status") else L("Refunded@status")) to ProviderState.closed
        }
        return when (bill.status) {
            "ready_for_payment", "open", "overdue" -> L("Unpaid@status") to ProviderState.waiting
            "partially_paid" -> L("Part paid@status") to ProviderState.waiting
            "paid" -> L("Paid@status") to ProviderState.done
            else -> L("Canceled@status") to ProviderState.closed
        }
    }

    // What this person may do with a bill.
    fun canEdit(bill: CashInvoice, w: Workspace): Boolean {
        if (!w.can(Permission.billingEditInvoice)) return false
        val kind = kind(bill)
        if (kind == "sale" || kind == "deposit" || bill.status == "canceled") return false
        if (bill.status == "paid" && !w.isOwnerOrAdmin) return false
        if (bill.status != "draft" && !w.can(Permission.billingFinalizeInvoice)) return false
        return true
    }

    fun canSendToDesk(bill: CashInvoice, w: Workspace): Boolean =
        w.can(Permission.billingFinalizeInvoice) && bill.status == "draft" && kind(bill) != "sale" && lineCount(bill) > 0 && bill.totalAmount > 0

    fun canRecordPayment(bill: CashInvoice, w: Workspace): Boolean =
        w.can(Permission.billingRecordPayment) && bill.balanceDueAmount > 0 && bill.status != "canceled" && bill.status != "draft" && kind(bill) != "deposit"

    /** "Cobrar en la Tienda, con productos": the bill goes to the counter, to charge it with products from the shelf. */
    fun canCollectAtStore(bill: CashInvoice, w: Workspace): Boolean =
        w.can(Permission.salesSell) && w.can(Permission.billingRecordPayment) && isCollectible(bill) && kind(bill) != "deposit"

    fun storeCheckout(bill: CashInvoice): ProviderRoute =
        ProviderRoute.storeCheckout(StorePrefill(clientId = bill.providerClientId, petId = bill.providerClientPetId, invoiceId = bill.id))

    fun canRefund(bill: CashInvoice, w: Workspace): Boolean =
        w.canAny(Permission.salesRefund, Permission.billingManage) && kind(bill) != "sale" && bill.status != "draft" && bill.status != "canceled" && (bill.amountPaid > 0 || bill.balanceDueAmount > 0)

    fun canCancel(bill: CashInvoice, w: Workspace): Boolean {
        if (!w.can(Permission.billingEditInvoice)) return false
        val kind = kind(bill)
        if (kind == "sale" || kind == "deposit" || bill.status == "canceled" || bill.amountPaid > 0) return false
        return bill.status == "draft" || w.can(Permission.billingFinalizeInvoice)
    }

    fun canBillAgain(bill: CashInvoice, w: Workspace): Boolean {
        val kind = kind(bill)
        return w.can(Permission.billingCreateInvoice) && kind != "sale" && kind != "deposit" && lineCount(bill) > 0 && !isWalkIn(bill)
    }

    enum class Next { collect, send, edit }

    fun next(bill: CashInvoice, w: Workspace): Next? {
        if (isCollectible(bill) && canRecordPayment(bill, w)) return Next.collect
        if (canSendToDesk(bill, w)) return Next.send
        if (bill.status == "draft" && canEdit(bill, w)) return Next.edit
        return null
    }

    /** Draft → at the desk. */
    suspend fun sendToDesk(bill: CashInvoice): CashInvoice =
        API.post("/provider-invoices/${bill.id}/send-to-desk", bill.revision?.let { mapOf("expectedRevision" to it) } ?: emptyMap())
}

// MARK: - The page

private enum class CashInvoiceFilter {
    due, overdue, desk, partial, draft, unpriced, paid, canceled;

    val label: String
        get() = when (this) {
            due -> L("With a balance")
            overdue -> L("Overdue")
            desk -> L("Unpaid")
            partial -> L("Part paid")
            draft -> L("Drafts")
            unpriced -> L("Needs a price")
            paid -> L("Paid@filter")
            canceled -> L("Canceled@filter")
        }

    /** The chip's colour while quiet and its fill while on (web `FILTER_META`). */
    val tone: ChipTone
        get() = when (this) {
            due, desk, partial, unpriced -> ChipTone.state(ProviderState.waiting)
            overdue -> ChipTone.state(ProviderState.problem)
            draft -> ChipTone.state(ProviderState.upcoming)
            paid -> ChipTone.state(ProviderState.done)
            canceled -> ChipTone.state(ProviderState.closed)
        }

    /** Oldest debt first rather than newest bill first: the rows are not grouped by day. */
    val byDebt: Boolean get() = this in listOf(due, overdue, desk, partial)

    val query: Map<String, String?>
        get() = when (this) {
            due -> mapOf("withBalance" to "true", "sort" to "due")
            overdue -> mapOf("overdue" to "true", "sort" to "due")
            desk -> mapOf("statuses" to "ready_for_payment,open", "withBalance" to "true", "sort" to "due")
            partial -> mapOf("statuses" to "partially_paid", "sort" to "due")
            draft -> mapOf("statuses" to "draft")
            unpriced -> mapOf("needsPrice" to "true")
            paid -> mapOf("statuses" to "paid")
            canceled -> mapOf("statuses" to "canceled")
        }

    val empty: Pair<String, String>
        get() = when (this) {
            due -> L("Nothing waiting to be paid") to L("Every bill that went to the desk has been paid.")
            overdue -> L("Nothing is late") to L("No bill is past its due date.")
            draft -> L("No drafts") to L("Bills still being built show up here until they go to the desk.")
            unpriced -> L("Every draft has its prices") to L("A draft with a line at $0 that isn’t a courtesy waits here for its price.")
            desk -> L("No unpaid bills") to L("Bills sent to be charged, with nothing paid yet, wait here.")
            partial -> L("No part-paid bills") to L("Bills with some of the money in show up here.")
            paid -> L("No paid bills here") to L("Paid bills show up here once the money is in.")
            canceled -> L("No canceled bills") to L("Bills canceled by mistake or on purpose show up here.")
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

/** SwiftUI `.onChange(of:)`: runs when the value changes, not on the first composition. */
@Composable
private fun <T> OnChange(value: T, action: suspend (T) -> Unit) {
    val seen = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (seen[0] != value) {
            seen[0] = value
            action(value)
        }
    }
}

private class CashInvoicesModel {
    val search = mutableStateOf("")
    var filter by mutableStateOf<CashInvoiceFilter?>(null)
    var kind by mutableStateOf<String?>(null)
    var period by mutableStateOf<String?>(null)
    var charging by mutableStateOf(false)
    var summary by mutableStateOf<CashInvoiceSummary?>(null)
    var totals by mutableStateOf<CashInvoiceTotals?>(null)
    var today by mutableStateOf(CashDay.today)
    var queue by mutableStateOf<CashChargingQueue?>(null)
    var outsideDays by mutableStateOf(false)
    var collected by mutableStateOf<Int?>(null)
    var openedOnQueue = false
    var withTotals = false

    val pager = Pager<CashInvoice> { offset, limit -> fetch(offset, limit) }

    val chargingPager = Pager<CashChargingItem> { offset, limit ->
        val loaded: CashChargingQueue = API.get("/provider-charging", mapOf("limit" to "$limit", "offset" to "$offset", "locationId" to Workspace.shared.listLocationFilter))
        queue = loaded
        Page(items = loaded.items, total = loaded.count)
    }

    private suspend fun fetch(offset: Int, limit: Int): Page<CashInvoice> {
        val query = linkedMapOf<String, String?>(
            "limit" to "$limit", "offset" to "$offset", "search" to search.value.nonEmpty, "origin" to kind,
            "locationId" to Workspace.shared.listLocationFilter,
        )
        filter?.let { query.putAll(it.query) }
        period?.let { if (filter == CashInvoiceFilter.paid) query["paidPeriod"] = it else query["period"] = it }
        if (withTotals) query["totals"] = "true"
        return try {
            val page: CashInvoicePage = API.get("/provider-invoices", query)
            outsideDays = false
            totals = page.totals
            page.today?.let { today = it }
            Page(items = page.items, total = page.total, hasMore = page.hasMore)
        } catch (error: APIError) {
            if (error.code != "outside_front_desk_days") throw error
            outsideDays = true
            Page(items = emptyList(), total = 0)
        }
    }

    suspend fun loadNumbers() {
        val numbers = attempt { API.get<CashInvoicePage>("/provider-invoices", mapOf("limit" to "1", "offset" to "0", "summary" to "true", "locationId" to Workspace.shared.listLocationFilter)) }
        summary = numbers?.summary
        numbers?.today?.let { today = it }
        val loaded = attempt { API.get<CashChargingQueue>("/provider-charging", mapOf("limit" to "0", "offset" to "0", "locationId" to Workspace.shared.listLocationFilter)) }
        if (loaded != null) queue = loaded
        // Open on "Por cobrar" when there is something to charge (and nothing else was chosen).
        if (!openedOnQueue) {
            openedOnQueue = true
            if ((loaded?.count ?: 0) > 0 && filter == null && kind == null && search.value.isEmpty() && period == null) charging = true
        }
    }
}

/** What a bill's row (or its ⋯ menu) asks the page to do. */
sealed class CashBillAction {
    data object open : CashBillAction()
    data object pay : CashBillAction()
    data object storeCollect : CashBillAction()
    data object send : CashBillAction()
    data object edit : CashBillAction()
    data class source(val route: ProviderRoute) : CashBillAction()
    data object client : CashBillAction()
    data object again : CashBillAction()
    data object refund : CashBillAction()
    data object cancel : CashBillAction()
}

@Composable
fun InvoicesScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val model = remember { CashInvoicesModel() }
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<CashInvoice?>(null) }
    var editing by remember { mutableStateOf<CashInvoice?>(null) }
    var template by remember { mutableStateOf<CashInvoice?>(null) }
    var refunding by remember { mutableStateOf<CashInvoice?>(null) }
    var canceling by remember { mutableStateOf<CashInvoice?>(null) }
    var sendingId by remember { mutableStateOf<String?>(null) }
    /** A bill saved with "Charge it now": its payment opens once the editor has left. */
    val pendingPay = remember { arrayOfNulls<CashInvoice>(1) }
    /** A bill saved without charging: it opens once the editor has left (the web opens its sheet). */
    val pendingOpen = remember { arrayOfNulls<String>(1) }

    val level = workspace.moneyLevel
    val canSell = workspace.can(Permission.salesSell)
    val canCreate = workspace.can(Permission.billingCreateInvoice)

    suspend fun reload() {
        val reach = Workspace.shared.moneyLevel
        model.withTotals = reach >= MoneyLevel.finances
        model.loadNumbers()
        if (model.charging) model.chargingPager.reload() else model.pager.reload()
        if (reach >= MoneyLevel.finances) {
            val dashboard = attempt { API.get<CashDashboard>("/provider-finance/dashboard", mapOf("period" to "this_month", "locationId" to Workspace.shared.listLocationFilter)) }
            model.collected = dashboard?.totals?.collected
        } else {
            val close = attempt { API.get<CashDayClose>("/provider-finance/day-close", mapOf("day" to "today", "locationId" to Workspace.shared.listLocationFilter)) }
            model.collected = close?.totals?.net
        }
    }

    fun afterSave(bill: CashInvoice, charge: Boolean) {
        if (charge) pendingPay[0] = bill else pendingOpen[0] = bill.id
        scope.launch { reload() }
    }

    fun presentPending() {
        val bill = pendingPay[0]
        val id = pendingOpen[0]
        if (bill != null) {
            pendingPay[0] = null
            paying = bill
        } else if (id != null) {
            pendingOpen[0] = null
            nav.push(ProviderRoute.invoice(id))
        }
    }

    fun clear() {
        model.search.value = ""
        model.filter = null
        model.kind = null
        model.period = null
        model.charging = false
    }

    fun toggle(filter: CashInvoiceFilter) {
        model.charging = false
        model.filter = if (model.filter == filter) null else filter
    }

    /** A visit opens its visit; a bill opens the bill (or, asked to charge, its payment); else the stay or hospital stay. */
    fun openCharging(item: CashChargingItem, pay: Boolean) {
        val id = item.invoiceId
        if (pay && id != null) {
            scope.launch {
                val full = attempt { API.get<CashInvoice>("/provider-invoices/$id") }
                if (full != null) paying = full else Toast.error(L("That bill could not be found."))
            }
            return
        }
        nav.push(CashChargingRow.route(item))
    }

    // MARK: A bill's actions
    fun act(action: CashBillAction, bill: CashInvoice) {
        when (action) {
            CashBillAction.open -> nav.push(ProviderRoute.invoice(bill.id))
            CashBillAction.pay -> paying = bill
            CashBillAction.storeCollect -> nav.push(CashBillRules.storeCheckout(bill))
            is CashBillAction.source -> nav.push(action.route)
            CashBillAction.client -> nav.push(ProviderRoute.client(bill.providerClientId))
            CashBillAction.refund -> refunding = bill
            CashBillAction.cancel -> canceling = bill
            CashBillAction.send -> {
                sendingId = bill.id
                scope.launch {
                    try {
                        val sent = CashBillRules.sendToDesk(bill)
                        Toast.show(LF("%@ is at the desk to be charged.", sent.invoiceNumber))
                        announceDataChanged()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        Toast.error(error)
                    } finally {
                        sendingId = null
                    }
                }
            }
            CashBillAction.edit, CashBillAction.again -> {
                // A list row carries no lines: the editor reads the whole bill.
                scope.launch {
                    val full = attempt { API.get<CashInvoice>("/provider-invoices/${bill.id}") }
                    if (full == null) {
                        Toast.error(L("That bill could not be found."))
                        return@launch
                    }
                    if (action == CashBillAction.edit) editing = full else template = full
                }
            }
        }
    }

    providerPage(L("Invoices"), root = true, modifier = modifier) {
        if (level < MoneyLevel.frontDesk) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to give you front desk money or business finances.")) }
        } else {
            ReloadsOnScope { reload() }
            Debounced(model.search.value) { model.pager.reload() }
            OnChange(model.filter) { model.pager.reload() }
            OnChange(model.kind) { model.pager.reload() }
            OnChange(model.period) { model.pager.reload() }
            OnChange(model.charging) { on -> if (on) model.chargingPager.reload() else model.pager.reload() }
            PageScroll(maxWidth = 980.dp, refresh = { reload() }) {
                InvoicesHeader(model, canSell = canSell, canCreate = canCreate, toggle = { toggle(it) }, sell = { nav.push(ProviderRoute.section(ProviderSection.store)) }, create = { creating = true })
                if (!model.charging) {
                    SearchField(prompt = L("Folio, client or pet"), text = model.search)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp)) {
                        val options = buildList {
                            add(Segmented.Option("all", L("All@kind")))
                            for (kind in CashBillRules.kinds) {
                                add(Segmented.Option(kind, CashBillRules.kindFilter(kind), icon = CashBillRules.kindIcon(kind), tone = CashBillRules.kindTone(kind)))
                            }
                        }
                        Segmented(
                            options = options,
                            selection = stateOf({ model.kind ?: "all" }, { model.kind = if (it == "all") null else it }),
                            look = Segmented.Look.raised, label = L("Where the bill came from"),
                        )
                    }
                }
                InvoiceFilters(model, toggle = { toggle(it) }, clear = { clear() })
                if (workspace.multiLocation) LocationMenu()
                InvoiceNotes(model, canSell = canSell, openStore = { nav.push(ProviderRoute.section(ProviderSection.store)) })
                if (model.charging) {
                    ChargingCard(model, canCharge = workspace.can(Permission.billingRecordPayment), clear = { clear() }, open = { item, pay -> openCharging(item, pay) })
                } else {
                    BillsCard(model, sendingId = sendingId, canCreate = canCreate, onAction = { action, bill -> act(action, bill) }, clear = { clear() }, create = { creating = true })
                }
            }
        }
    }

    if (creating) {
        AppSheet(onDismissRequest = { creating = false; presentPending() }) {
            CashInvoiceEditorSheet(bill = null, template = null) { saved, charge -> afterSave(saved, charge) }
        }
    }
    editing?.let { bill ->
        AppSheet(onDismissRequest = { editing = null; presentPending() }) {
            CashInvoiceEditorSheet(bill = bill, template = null) { saved, charge -> afterSave(saved, charge) }
        }
    }
    template?.let { bill ->
        AppSheet(onDismissRequest = { template = null; presentPending() }) {
            CashInvoiceEditorSheet(bill = null, template = bill) { saved, charge -> afterSave(saved, charge) }
        }
    }
    paying?.let { bill ->
        AppSheet(onDismissRequest = { paying = null }) { CashInvoicePaySheet(bill = bill) { scope.launch { reload() } } }
    }
    refunding?.let { bill ->
        AppSheet(onDismissRequest = { refunding = null }) { CashRefundSheet(bill = bill) { scope.launch { reload() } } }
    }
    canceling?.let { bill ->
        AppSheet(onDismissRequest = { canceling = null }) { CashCancelBillSheet(bill = bill) { scope.launch { reload() } } }
    }
}

// MARK: Header

@Composable
private fun InvoicesHeader(
    model: CashInvoicesModel,
    canSell: Boolean,
    canCreate: Boolean,
    toggle: (CashInvoiceFilter) -> Unit,
    sell: () -> Unit,
    create: () -> Unit,
) {
    val workspace = Workspace.shared
    val level = workspace.moneyLevel
    val summary = model.summary
    val named = if (level >= MoneyLevel.finances) "this_month" else "today"
    val collectedOn = model.filter == CashInvoiceFilter.paid && model.period == named && !model.charging
    val needsPrice = summary?.draft?.needsPrice ?: 0
    val stats = listOf(
        ModuleStat(
            "due", label = L("Owed to you"), value = summary?.let { cashMoney(it.due.amount) } ?: "—",
            hint = summary?.let { if (it.due.count == 0) L("Nothing waiting to be paid") else LP(it.due.count, "%d bill with a balance", "%d bills with a balance") } ?: L("Sent and waiting to be paid"),
            icon = "hand-coins", accent = Theme.pink, active = model.filter == CashInvoiceFilter.due && !model.charging,
        ) { toggle(CashInvoiceFilter.due) },
        ModuleStat(
            "overdue", label = L("Overdue"), value = summary?.let { cashMoney(it.overdue.amount) } ?: "—",
            hint = summary?.let { if (it.overdue.count > 0) LP(it.overdue.count, "%d bill past its day", "%d bills past their day") else L("Nothing late") } ?: L("Past their due date"),
            icon = "alarm-clock", accent = Theme.destructive, attention = (summary?.overdue?.amount ?: 0) > 0,
            active = model.filter == CashInvoiceFilter.overdue && !model.charging,
        ) { toggle(CashInvoiceFilter.overdue) },
        ModuleStat(
            "drafts", label = L("Drafts"), value = summary?.let { "${it.draft.count}" } ?: "—",
            hint = if (needsPrice > 0) LP(needsPrice, "%d still needs a price", "%d still need a price")
            else if (workspace.can(Permission.billingFinalizeInvoice)) L("Send them to the desk when ready") else L("Waiting for someone to review"),
            icon = "pen-line", accent = Theme.aqua, attention = needsPrice > 0,
            active = (model.filter == CashInvoiceFilter.draft || model.filter == CashInvoiceFilter.unpriced) && !model.charging,
        ) {
            // With drafts still to price, the number opens those: they are what holds a charge back.
            toggle(if (needsPrice > 0 && model.filter != CashInvoiceFilter.draft) CashInvoiceFilter.unpriced else CashInvoiceFilter.draft)
        },
        ModuleStat(
            "collected", label = if (level >= MoneyLevel.finances) L("Collected this month") else L("Collected today"),
            value = model.collected?.let { cashMoney(it) } ?: "—",
            hint = L("Payments less refunds · see who paid"), icon = "banknote", accent = ProviderState.done.color, active = collectedOn,
        ) {
            if (collectedOn) {
                model.filter = null
                model.period = null
            } else {
                model.charging = false
                model.filter = CashInvoiceFilter.paid
                model.period = named
            }
        },
    )
    ModuleHeader(
        icon = "receipt",
        title = L("Invoices"),
        subtitle = L("Visit bills, stays, store sales and deposits — what is waiting to be paid and what came in."),
        stats = stats,
        storageKey = "invoices",
    ) {
        if (canSell) PMButton(L("Sell at the counter"), systemImage = "shopping-cart", variant = ButtonVariant.outline, action = sell)
        if (canCreate) PMButton(L("New bill"), systemImage = "plus", action = create)
    }
}

// MARK: Filters

@Composable
private fun InvoiceFilters(model: CashInvoicesModel, toggle: (CashInvoiceFilter) -> Unit, clear: () -> Unit) {
    val summary = model.summary
    val counts: Map<CashInvoiceFilter, Int?> = mapOf(
        CashInvoiceFilter.due to summary?.due?.count, CashInvoiceFilter.overdue to summary?.overdue?.count,
        CashInvoiceFilter.desk to summary?.desk?.count, CashInvoiceFilter.partial to summary?.partial?.count,
        CashInvoiceFilter.draft to summary?.draft?.count, CashInvoiceFilter.unpriced to summary?.draft?.needsPrice,
    )
    val chargingLate = (model.queue?.earlierDays?.count ?: 0) > 0
    val filtered = model.filter != null || model.kind != null || model.search.value.nonEmpty != null || model.period != null || model.charging
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!model.charging) PeriodMenu(model)
        // "Por cobrar": finished work of any day still to charge, red while one is from an earlier day.
        ToneChip(
            L("To charge"), icon = "receipt", tone = ChipTone.state(if (chargingLate) ProviderState.problem else ProviderState.waiting),
            count = model.queue?.count, isOn = model.charging,
        ) {
            model.charging = !model.charging
            if (model.charging) model.filter = null
        }
        CashInvoiceFilter.entries.forEach { filter ->
            ToneChip(filter.label, tone = filter.tone, count = counts[filter], dot = true, isOn = model.filter == filter && !model.charging) { toggle(filter) }
        }
        if (filtered) {
            Text(
                L("Clear filters"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue,
                modifier = Modifier.plainButton(onClick = clear).padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PeriodMenu(model: CashInvoicesModel) {
    val finances = Workspace.shared.moneyLevel >= MoneyLevel.finances
    val named: List<Pair<String, String>> = if (finances) {
        listOf("today" to L("Today"), "yesterday" to L("Yesterday"), "this_week" to L("This week"), "this_month" to L("This month"), "last_30" to L("Last 30 days"), "last_month" to L("Last month"), "this_year" to L("This year"))
    } else {
        listOf("today" to L("Today"), "yesterday" to L("Yesterday"))
    }
    val title = if (model.filter == CashInvoiceFilter.paid) L("Day it was paid") else L("Bill date")
    AppMenu(
        label = {
            // The chip is the menu's look (iOS `.allowsHitTesting(false)`): the menu takes the tap.
            Disabled { FilterChip(title = named.firstOrNull { it.first == model.period }?.second ?: L("Any date"), isSelected = model.period != null, icon = "calendar-range") {} }
        },
        accessibilityLabel = title,
    ) {
        section(title)
        item(L("Any date")) { model.period = null }
        named.forEach { entry -> item(entry.second) { model.period = entry.first } }
        if (!finances) {
            divider()
            Text(
                L("Only the last 7 days: older days are for business finances."),
                style = Theme.text(12), color = Theme.inkSoft,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// MARK: Notes over the list

@Composable
private fun InvoiceNotes(model: CashInvoicesModel, canSell: Boolean, openStore: () -> Unit) {
    if (model.charging) {
        val late = model.queue?.earlierDays?.count ?: 0
        val destructive = Theme.destructive
        Text(
            buildAnnotatedString {
                append(L("Finished visits, stays, hospital stays and home stays of any day not charged yet: no bill, a draft, or at the desk and not charged. Once charged, what clients still owe is in “Owed to you”."))
                if (late > 0) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = destructive)) { append(" " + LF("%d from earlier days", late)) }
            },
            style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    } else {
        if (model.period != null) {
            InvoiceNote(if (model.filter == CashInvoiceFilter.paid) L("Bills paid on these days.") else L("Bills dated on these days."))
        }
        if (model.kind == "sale" && (canSell || Workspace.shared.moneyLevel >= MoneyLevel.charging)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InvoiceNote(L("Counter sales are the Store’s: its Sales tab has each day’s sales, their returns and receipts."))
                Text(
                    L("Open the Store’s sales"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue,
                    modifier = Modifier.padding(horizontal = 4.dp).plainButton(onClick = openStore),
                )
            }
        }
    }
}

@Composable
private fun InvoiceNote(text: String) {
    Text(text, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, modifier = Modifier.padding(horizontal = 4.dp))
}

// MARK: The bills

@Composable
private fun ColumnScope.BillsCard(
    model: CashInvoicesModel,
    sendingId: String?,
    canCreate: Boolean,
    onAction: (CashBillAction, CashInvoice) -> Unit,
    clear: () -> Unit,
    create: () -> Unit,
) {
    val pager = model.pager
    // (iOS CashPagerObserver redraws this part as its pager loads; Compose reads the pager's state itself.)
    ListCard {
        if (model.outsideDays) {
            EmptyState(systemImage = "triangle-alert", title = L("Only the last 7 days: older days are for business finances."), actionTitle = L("Clear filters"), action = clear, accent = Theme.pink)
        } else {
            CashBillRowsView(pager = pager, today = model.today, byDebt = model.filter?.byDebt ?: false, sendingId = sendingId, onAction = onAction) {
                val needle = model.search.value.nonEmpty
                val empty = if (needle == null) model.filter?.empty else null
                val listFiltered = model.filter != null || model.kind != null || needle != null || model.period != null
                EmptyState(
                    systemImage = "receipt",
                    title = needle?.let { LF("Nothing matches “%@”", it) } ?: empty?.first ?: (if (listFiltered) L("No bills match these filters") else L("No bills yet")),
                    message = if (needle != null) L("Try another folio, client or pet, or clear the filters.") else empty?.second
                        ?: (if (listFiltered) L("Try another search, or clear the filters.") else L("Visit bills, store sales and deposits land here. Start one by hand, or finish a visit and send it to the desk.")),
                    actionTitle = if (listFiltered) L("Show every bill") else if (canCreate) L("New bill") else null,
                    action = if (listFiltered) clear else if (canCreate) create else null,
                    accent = Theme.lime,
                )
            }
        }
    }
    val total = pager.total
    if (!model.outsideDays && total != null && total > 0) ListFooterTotals(model, total)
}

/** The count and, for business finances, what the whole filtered list adds up to (the server's). */
@Composable
private fun ListFooterTotals(model: CashInvoicesModel, total: Int) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(LP(total, "%d bill", "%d bills"), style = Theme.text(14), color = Theme.inkSoft)
        val totals = model.totals
        if (Workspace.shared.moneyLevel >= MoneyLevel.finances && totals != null && totals.standingCount > 0) {
            FlowLayout(spacing = 12.dp, lineSpacing = 4.dp) {
                FooterFigure(L("Billed"), totals.billedAmount)
                FooterFigure(L("Paid@amount"), totals.paidAmount)
                FooterFigure(L("Balance"), totals.balanceAmount)
                // Drafts are billed but neither paid nor owed yet: said apart, so the figures add up.
                val drafts = totals.draftAmount
                if (drafts != null && drafts > 0) FooterFigure(L("In drafts"), drafts)
            }
        }
    }
}

@Composable
private fun FooterFigure(label: String, amount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Theme.text(14), color = Theme.inkSoft)
        Text(cashMoney(amount), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
    }
}

// MARK: Por cobrar

@Composable
private fun ColumnScope.ChargingCard(model: CashInvoicesModel, canCharge: Boolean, clear: () -> Unit, open: (CashChargingItem, Boolean) -> Unit) {
    val pager = model.chargingPager
    val scope = rememberCoroutineScope()
    val error = pager.error
    ListCard {
        when {
            !pager.loadedOnce && pager.isLoading -> LoadingState()
            error != null && pager.items.isEmpty() -> EmptyState(
                systemImage = "triangle-alert", title = L("What is left to charge could not be loaded"), message = error,
                actionTitle = L("Try again"), action = { scope.launch { pager.reload() } }, accent = Theme.pink,
            )
            pager.items.isEmpty() -> EmptyState(
                systemImage = "receipt", title = L("Nothing left to charge"), message = L("Everything finished is charged, given without charging, or owed on an account."),
                actionTitle = L("Show every bill"), action = clear, accent = Theme.lime,
            )
            else -> Column(Modifier.fillMaxWidth()) {
                pager.items.forEachIndexed { index, item ->
                    key(item.id) {
                        if (index > 0) RowDivider()
                        CashChargingRow(item = item, today = model.today, canCharge = canCharge, modifier = Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(item) }) { pay -> open(item, pay) }
                    }
                }
                PagerFooter(pager)
            }
        }
    }
    val total = model.queue?.count
    if (total != null && total > 0 && pager.items.isNotEmpty()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(LP(total, "%d to charge", "%d to charge"), style = Theme.text(14), color = Theme.inkSoft)
            val amount = model.queue?.amount
            if (amount != null && amount > 0) {
                Text("·", style = Theme.text(14), color = Theme.inkSoft)
                Text(cashMoney(amount, pager.items.firstOrNull { it.currency != null }?.currency), style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
            }
        }
    }
}

/**
 * The bills of the list in their card: under their day (newest first), or one
 * after another with their day when the list reads debts oldest first.
 */
@Composable
private fun CashBillRowsView(
    pager: Pager<CashInvoice>,
    today: String,
    byDebt: Boolean,
    sendingId: String?,
    onAction: (CashBillAction, CashInvoice) -> Unit,
    empty: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val error = pager.error
    when {
        !pager.loadedOnce && pager.isLoading -> LoadingState()
        error != null && pager.items.isEmpty() -> EmptyState(
            systemImage = "triangle-alert", title = L("The bills could not be loaded"), message = error.nonEmpty ?: L("Check the connection and try again."),
            actionTitle = L("Try again"), action = { scope.launch { pager.reload() } }, accent = Theme.pink,
        )
        pager.items.isEmpty() -> empty()
        else -> Column(Modifier.fillMaxWidth()) {
            val items = pager.items
            items.forEachIndexed { index, bill ->
                key(bill.id) {
                    val day = CashBillRules.billDay(bill)
                    val newDay = !byDebt && (index == 0 || CashBillRules.billDay(items[index - 1]) != day)
                    if (index > 0) RowDivider()
                    if (newDay) {
                        DayBand(
                            title = if (day == today) L("Today") else CashBillRules.weekday(day),
                            trailing = LP(items.count { CashBillRules.billDay(it) == day }, "%d bill", "%d bills"),
                            style = DayBand.Style.bills,
                        )
                        RowDivider()
                    }
                    CashInvoiceRow(
                        bill = bill, today = today, showDay = byDebt, sending = sendingId == bill.id,
                        modifier = Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(bill) },
                    ) { onAction(it, bill) }
                }
            }
            PagerFooter(pager)
        }
    }
}

// MARK: - A bill's row

/**
 * One bill (web `InvoiceRow`, its phone card): who it is for and what it comes
 * to, its folio, where it came from and its day, where it stands, and the next
 * thing to do with the ⋯ menu. The row opens the bill.
 */
@Composable
fun CashInvoiceRow(
    bill: CashInvoice,
    today: String,
    showDay: Boolean = false,
    sending: Boolean = false,
    modifier: Modifier = Modifier,
    onAction: (CashBillAction) -> Unit,
) {
    val workspace = Workspace.shared
    val money: (Int) -> String = { cashMoney(it, bill.currency) }
    val kind = CashBillRules.kind(bill)
    val walkIn = CashBillRules.isWalkIn(bill)
    val late = CashBillRules.isOverdue(bill, today)
    val canceled = bill.status == "canceled"
    val draft = bill.status == "draft"
    val owes = !canceled && !draft && bill.balanceDueAmount > 0
    val canFinalize = workspace.can(Permission.billingFinalizeInvoice)
    val chip = CashBillRules.chip(bill, today, canFinalize)
    val hint = CashBillRules.statusHint(bill, today, canFinalize)
    val items = CashBillRules.lineCount(bill)
    Column(
        modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = LF("Open %@", bill.invoiceNumber)) { onAction(CashBillAction.open) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CashBillAvatar(petName = bill.petName, photoURL = bill.petPhotoUrl, species = bill.petSpecies)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (walkIn) (bill.customerName.nonEmpty ?: L("Walk-in customer")) else bill.clientName, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
                Text(bill.petName.nonEmpty ?: (if (walkIn) L("Counter sale") else L("No pet on it")), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    money(if (canceled) bill.totalAmount else CashBillRules.netTotal(bill)),
                    style = Theme.heading(16).monospacedDigit().copy(textDecoration = if (canceled) TextDecoration.LineThrough else null),
                    color = if (canceled) Theme.inkSoft else Theme.inkStrong, maxLines = 1,
                )
                if (owes) {
                    Text(LF("Owes %@", money(bill.balanceDueAmount)), style = Theme.text(12, FontWeight.Bold).monospacedDigit(), color = if (late) Theme.destructive else Theme.inkStrong, maxLines = 1)
                    if (bill.amountPaid > 0) {
                        Text(LF("Paid %@", money(bill.amountPaid)), style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft, maxLines = 1)
                    }
                }
                if (!canceled && bill.refunded > 0) {
                    Text(LF("%@ returned", money(bill.refunded)), style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft, maxLines = 1)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bill.invoiceNumber, style = Theme.text(12.8, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong, maxLines = 1)
                CashBillKindChip(kind = kind)
            }
            Text(
                (if (showDay) CashBillRules.day(bill.issuedAt ?: bill.createdAt) + " · " else "") + LP(items, "%d item@bill", "%d items@bill"),
                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CashInvoiceStatusChip(text = chip.first, state = chip.second)
            if (hint != null) {
                Text(hint.first, style = Theme.text(12, if (hint.second) FontWeight.Bold else FontWeight.Normal), color = if (hint.second) Theme.destructive else Theme.inkSoft, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            }
            when (CashBillRules.next(bill, workspace)) {
                CashBillRules.Next.collect -> PMButton(L("Charge"), systemImage = "hand-coins", size = ButtonSize.compact) { onAction(CashBillAction.pay) }
                CashBillRules.Next.send -> PMButton(L("To the desk"), systemImage = "send", size = ButtonSize.compact, isWorking = sending) { onAction(CashBillAction.send) }
                CashBillRules.Next.edit -> PMButton(L("Edit"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact) { onAction(CashBillAction.edit) }
                null -> {}
            }
            CashInvoiceRowMenu(bill = bill, walkIn = walkIn, onAction = onAction)
        }
    }
}

@Composable
private fun CashInvoiceRowMenu(bill: CashInvoice, walkIn: Boolean, onAction: (CashBillAction) -> Unit) {
    val workspace = Workspace.shared
    val source = CashBillRules.source(bill)?.takeIf { CashBillRules.sourceAllowed(it, workspace) }
    val refund = CashBillRules.canRefund(bill, workspace)
    val cancel = CashBillRules.canCancel(bill, workspace)
    AppMenu(label = { IconCircleLabel(size = 32.dp) }, accessibilityLabel = LF("More for %@", bill.invoiceNumber)) {
        item(L("Open the bill"), icon = "file-text") { onAction(CashBillAction.open) }
        if (CashBillRules.canRecordPayment(bill, workspace)) item(L("Take a payment"), icon = "coins") { onAction(CashBillAction.pay) }
        if (CashBillRules.canCollectAtStore(bill, workspace)) item(L("Charge at the Store, with products"), icon = "shopping-cart") { onAction(CashBillAction.storeCollect) }
        if (CashBillRules.canSendToDesk(bill, workspace)) item(L("Send to the desk"), icon = "send") { onAction(CashBillAction.send) }
        if (CashBillRules.canEdit(bill, workspace)) item(L("Edit the bill"), icon = "pencil") { onAction(CashBillAction.edit) }
        if (source != null) item(CashBillRules.sourceLabel(source), icon = "external-link") { onAction(CashBillAction.source(source)) }
        if (workspace.can(Permission.clientsView) && !walkIn) item(L("Open client"), icon = "user-round") { onAction(CashBillAction.client) }
        if (CashBillRules.canBillAgain(bill, workspace)) item(L("Bill again"), icon = "copy") { onAction(CashBillAction.again) }
        if (refund || cancel) divider()
        if (refund) item(L("Refund or credit note"), icon = "undo-2", destructive = true) { onAction(CashBillAction.refund) }
        if (cancel) item(L("Cancel the bill"), icon = "ban", destructive = true) { onAction(CashBillAction.cancel) }
    }
}

/**
 * One bill's status (web `InvoiceStatusChip`): a 24 capsule tinted with the
 * state, its edge at 55%, a 6 dot, 12 bold; late reads red.
 */
@Composable
fun CashInvoiceStatusChip(text: String, state: ProviderState, modifier: Modifier = Modifier) {
    val late = state == ProviderState.problem
    Row(
        modifier
            .wrapContentWidth()
            .height(24.dp)
            .background(Theme.wash(state.color, if (late) 10.0 else 18.0), CapsuleShape)
            .border(1.dp, Theme.mix(state.color, 55.0, Theme.borderSoft), CapsuleShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(state.color, CircleShape))
        Text(text, style = Theme.text(12, FontWeight.Bold), color = if (late) Theme.destructive else Theme.inkStrong, maxLines = 1, softWrap = false)
    }
}

/** Where a bill came from (web `KindChip`): its icon in the kind's hue and its name, on the hue's wash. */
@Composable
fun CashBillKindChip(kind: String, modifier: Modifier = Modifier) {
    val hue = CashBillRules.kindHue(kind)
    Row(
        modifier
            .background(Theme.wash(hue.color, 18.0), CapsuleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Glyph(CashBillRules.kindIcon(kind), 14.dp, tint = Theme.mix(hue.color, 60.0, Theme.inkStrong))
        Text(CashBillRules.kindLabel(kind), style = Theme.text(11.2, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 1)
    }
}

/** Who a bill is for, as a picture (web `BillAvatar`): the pet, or a person on butter for a bill with no pet. */
@Composable
fun CashBillAvatar(petName: String?, photoURL: String? = null, species: String? = null, size: androidx.compose.ui.unit.Dp = 40.dp, modifier: Modifier = Modifier) {
    val name = petName.nonEmpty
    if (name != null) {
        PetAvatar(name = name, species = species, photoURL = photoURL, size = size, modifier = modifier)
    } else {
        Box(modifier.size(size).background(Theme.wash(Theme.butter, 45.0), CircleShape), contentAlignment = Alignment.Center) {
            Glyph("user-round", size / 2, tint = Theme.inkStrong)
        }
    }
}

// MARK: - Por cobrar

/** `CashChargingRow.route`: where an item opens. */
object CashChargingRow {
    fun route(item: CashChargingItem): ProviderRoute {
        val visit = item.appointmentId
        if (item.source == "visit" && visit != null) return ProviderRoute.appointment(visit)
        item.invoiceId?.let { return ProviderRoute.invoice(it) }
        return if (item.source == "hospital") ProviderRoute.hospitalization(item.sourceId) else ProviderRoute.stay(item.sourceId)
    }
}

/**
 * One finished visit, stay, hospital stay or home stay still to charge (web
 * `ChargingRow`): what it was and for whom, its day (red from an earlier day),
 * where its bill stands and what is left. A bill at the desk offers "Charge".
 */
@Composable
fun CashChargingRow(
    item: CashChargingItem,
    today: String,
    canCharge: Boolean = false,
    modifier: Modifier = Modifier,
    onOpen: (pay: Boolean) -> Unit,
) {
    val what = when (item.source) {
        "visit" -> item.serviceLabel ?: ""
        "stay" -> L("Stay")
        "hospital" -> L("Hospital")
        else -> L("Home stay")
    }
    val stage: Pair<String, ProviderState> = when (item.state) {
        "needs_price" -> L("Needs a price") to ProviderState.waiting
        "unsent" -> L("Being built") to ProviderState.active
        "ready" -> L("Ready to charge") to ProviderState.waiting
        else -> L("No bill yet") to ProviderState.closed
    }
    Column(
        modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(false) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CashBillAvatar(petName = item.petName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${(item.petNames ?: listOf(item.petName)).joinToString(", ")} · $what", style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
                Text(listOf(item.clientName, item.vetName).mapNotNull { it.nonEmpty }.joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (item.earlierDay) "${CashBillRules.day(item.day)} · ${DateText.time(item.at)}" else "${L("Today")} · ${DateText.time(item.at)}",
                style = Theme.text(12, if (item.earlierDay) FontWeight.Bold else FontWeight.Normal).monospacedDigit(),
                color = if (item.earlierDay) Theme.destructive else Theme.inkSoft, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CashBillStageBadge(text = stage.first, state = stage.second)
            Spacer(Modifier.weight(1f))
            Text(item.amount?.let { cashMoney(it, item.currency) } ?: "—", style = Theme.heading(16).monospacedDigit(), color = Theme.inkStrong)
            if (canCharge && item.state == "ready") {
                PMButton(L("Charge"), systemImage = "hand-coins", variant = ButtonVariant.outline, size = ButtonSize.compact) { onOpen(true) }
            }
        }
    }
}

/** Where a bill stands, in a few words (web `StageBadge`): a capsule on the state's 22% wash, 12 bold navy. */
@Composable
fun CashBillStageBadge(text: String, state: ProviderState, modifier: Modifier = Modifier) {
    Text(
        text, style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1,
        modifier = modifier.background(Theme.wash(state.color, 22.0), CapsuleShape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
