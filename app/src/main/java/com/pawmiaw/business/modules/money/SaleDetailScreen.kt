package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

//
//  SaleDetailScreen.kt (iOS SaleDetailScreen.swift)
//
//  One counter sale (web: store-sales.tsx SaleDetailSheet, store-receipt.tsx):
//  its receipt, its returns, and what can be done with it — a return (back on
//  the shelf or written off, the money back the way it came) or a cancel for
//  a sale made by mistake. Both need their reason (docs/money-access-levels.md).
//  A page here: the web sheet's header (Receipt, "Sale F-0001", what is still
//  due) and its footer's actions as the header's buttons.
//

private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

@Composable
fun SaleDetailScreen(saleId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val loader = remember(saleId) { Loader { API.get<CashSale>("/provider-store/sales/$saleId") } }
    var footer by remember { mutableStateOf<String?>(null) }
    var returning by remember { mutableStateOf(false) }
    var canceling by remember { mutableStateOf(false) }

    val title = loader.value?.let { LF("Sale %@", it.folio) } ?: L("Sale")

    providerPage(title, modifier = modifier) {
        ReloadsOnScope {
            loader.load()
            val settings = attempt { API.get<CashStoreSettings>("/provider-store/settings") }
            footer = settings?.receiptFooter
        }
        PageScroll(refresh = { loader.load() }) {
            val sale = loader.value
            if (sale != null) {
                SaleHeader(sale, cancel = { canceling = true }, giveBack = { returning = true })
                SaleContent(sale, footer, openClient = { nav.push(ProviderRoute.client(it)) })
            } else {
                ModuleHeader(icon = "receipt", title = title, accent = Theme.aqua, storageKey = "sale")
                LoadedContent(loader) { }
            }
        }
    }

    val sale = loader.value
    if (returning && sale != null) {
        AppSheet(onDismissRequest = { returning = false }) {
            CashReturnSheet(sale = sale, onDone = { loader.load() })
        }
    }
    if (canceling && sale != null) {
        AppSheet(onDismissRequest = { canceling = false }) {
            ConfirmSheet(
                title = LF("Cancel %@?", sale.folio),
                message = L("For a sale made by mistake: everything goes back on the shelf and its payments are voided. Cash can only be undone while its register is open — after that, make a return."),
                confirmTitle = L("Cancel sale"), destructive = true, askReason = true, reasonRequired = true,
                reasonLabel = CashReasonKind.cancel.label, cancelTitle = L("Keep it"), confirmIcon = "ban",
            ) { reason ->
                API.post<EmptyResponse>("/provider-store/sales/${sale.id}/cancel", mapOf("reason" to reason))
                Toast.show(LF("Sale %@ canceled. Stock is back and its payments voided.", sale.folio))
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                loader.load()
            }
        }
    }
}

// MARK: Header

@Composable
private fun SaleHeader(sale: CashSale, cancel: () -> Unit, giveBack: () -> Unit) {
    val workspace = Workspace.shared
    val canRefund = workspace.can(Permission.salesRefund) && sale.status != "canceled"
    // Returns are for counter sales: a visit's or a stay's bill is refunded from the bill.
    val returnable = sale.kind != "invoice" && sale.status != "draft" && sale.lines.any { it.quantity > it.returnedQuantity }
    val cancelable = sale.returns.isEmpty()
    ModuleHeader(
        icon = "receipt",
        title = LF("Sale %@", sale.folio),
        subtitle = if (sale.balanceDueAmount > 0) LF("%@ still due", cashMoney(sale.balanceDueAmount, sale.currency)) else CashDay.`when`(sale.createdAt),
        accent = Theme.aqua,
        storageKey = "sale",
    ) {
        DocumentButton(title = L("Download PDF"), systemImage = "file-down", path = "/provider-store/sales/${sale.id}/pdf", fileName = sale.folio)
        if (canRefund && cancelable) {
            PMButton(L("Cancel sale"), systemImage = "ban", variant = ButtonVariant.destructive) { cancel() }
        }
        if (canRefund && returnable) {
            PMButton(L("Return items"), systemImage = "undo-2") { giveBack() }
        }
    }
}

// MARK: Content

@Composable
private fun SaleContent(sale: CashSale, footer: String?, openClient: (String) -> Unit) {
    val workspace = Workspace.shared
    sale.cancelReason.nonEmpty?.let { reason ->
        NoticeCard(
            tone = NoticeCard.Tone.problem,
            title = L("Canceled@sale"),
            message = "“$reason”" + (sale.canceledAt?.let { " · " + CashDay.`when`(it) } ?: ""),
        )
    }
    CashReceiptView(sale = sale, footer = footer)
    if (sale.returns.isNotEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 352.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sale.returns.forEach { entry ->
                    key(entry.id) {
                        val shape = RoundedCornerShape(Theme.boxRadius)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Theme.surface, shape)
                                .border(1.dp, Theme.borderSoft, shape)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${L("Return")} ${entry.folio}", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(
                                        if (entry.refundAmount > 0) "−${cashMoney(entry.refundAmount, sale.currency)}" else L("credited"),
                                        entry.reason,
                                        entry.createdAt?.let { CashDay.`when`(it) },
                                    ).mapNotNull { it.nonEmpty }.joinToString(" · "),
                                    style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DocumentButton(title = "PDF", systemImage = "file-down", path = "/provider-store/sales/${sale.id}/returns/${entry.id}/pdf", fileName = entry.folio)
                        }
                    }
                }
            }
        }
    }
    val clientId = sale.providerClientId
    if (clientId != null && !sale.walkIn && workspace.can(Permission.clientsView)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PMButtonStyle(ButtonVariant.outline, ButtonSize.compact, onClick = { openClient(clientId) }) {
                PMButtonContent(L("Open client"), icon = "user-round", variant = ButtonVariant.outline, size = ButtonSize.compact)
            }
        }
    }
}

// MARK: - The receipt

private fun receiptType(style: TextStyle): TextStyle = style.copy(fontFamily = FontFamily.Monospace)

/**
 * A sale as a ticket (web `StoreReceipt`): what was bought, the tax inside
 * it, how it was paid and what came back, in the receipt's fixed-width type.
 */
@Composable
fun CashReceiptView(sale: CashSale, footer: String? = null, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val currency = sale.currency
    val payments = sale.payments.filter { it.kind == "payment" && it.voidedAt == null }
    val rates = sale.lines.filter { (it.taxAmount ?: 0) > 0 }.mapNotNull { it.taxRateBps }.toSet().sorted().joinToString(", ") { cashRate(it) }
    val shape = RoundedCornerShape(Theme.boxRadius)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalTextStyle provides receiptType(Theme.text(12.8)), LocalContentColor provides Theme.inkStrong) {
            Column(
                Modifier
                    .widthIn(max = 352.dp)
                    .fillMaxWidth()
                    .background(Theme.surface, shape)
                    .border(1.dp, Theme.borderSoft, shape)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(workspace.current?.name ?: "", style = receiptType(Theme.heading(18)), textAlign = TextAlign.Center)
                    workspace.current?.phone.nonEmpty?.let { Text(it, color = Theme.inkSoft) }
                    Text(sale.folio, style = receiptType(Theme.text(14, FontWeight.Bold)).tracking(0.35), modifier = Modifier.padding(top = 8.dp))
                    Text(CashDay.`when`(sale.createdAt), color = Theme.inkSoft)
                    if (sale.status == "canceled") {
                        Text(
                            L("Canceled@sale"),
                            style = receiptType(Theme.text(12, FontWeight.Bold)),
                            color = Theme.inkOnBright,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .background(Theme.pink, CapsuleShape)
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
                if (sale.walkIn || sale.customerName != null || sale.petName != null || sale.cashierName != null) {
                    ReceiptSection {
                        if (sale.walkIn || sale.customerName != null) {
                            Text("${L("Customer")}: ${if (sale.walkIn) (sale.customerName ?: L("Walk-in customer")) else (sale.customerName ?: "")}")
                        }
                        sale.petName?.let { Text("${L("Pet")}: $it") }
                        sale.cashierName?.let { Text("${L("Served by")}: $it") }
                    }
                }
                ReceiptSection(spacing = 8) {
                    sale.lines.forEach { line ->
                        key(line.id) {
                            Column(Modifier.fillMaxWidth()) {
                                ReceiptRow("${line.quantity} × ${line.label}", cashMoney(line.quantity * line.unitAmount, currency))
                                if (line.quantity > 1) ReceiptDetail(LF("%@ each", cashMoney(line.unitAmount, currency)))
                                line.discountAmount?.takeIf { it > 0 }?.let { discount ->
                                    ReceiptRow("  ${L("Discount")}", "−${cashMoney(discount, currency)}", muted = true)
                                }
                                line.prescriptionFolio?.let { ReceiptDetail(LF("Prescription %@", it)) }
                                val lots = line.lots ?: emptyList()
                                lots.forEach { lot ->
                                    ReceiptDetail(
                                        "${L("Lot")} ${lot.lotCode ?: "—"}" +
                                            (lot.expiresOn?.let { " · ${L("exp.")} ${CashDay.short(it)}" } ?: "") +
                                            (if (lots.size > 1) " · ${lot.quantity ?: 0}" else ""),
                                    )
                                }
                                if (line.returnedQuantity > 0) ReceiptDetail(LP(line.returnedQuantity, "%d returned", "%d returned"))
                            }
                        }
                    }
                }
                ReceiptSection {
                    if (sale.discountAmount > 0) ReceiptRow(L("Discounts"), "−${cashMoney(sale.discountAmount, currency)}")
                    ReceiptRow(L("Total"), cashMoney(sale.totalAmount, currency), strong = true)
                    if (sale.taxAmount > 0) {
                        ReceiptRow(if (sale.pricesIncludeTax == true) LF("Tax included (%@)", rates) else LF("Tax (%@)", rates), cashMoney(sale.taxAmount, currency))
                    }
                }
                ReceiptSection {
                    payments.forEach { payment ->
                        key(payment.id) {
                            ReceiptRow(CashText.method(payment.method), cashMoney(payment.amount, currency))
                            val tendered = payment.tenderedAmount
                            val change = payment.changeAmount
                            if (tendered != null && change != null && change > 0) {
                                ReceiptRow("  ${L("Handed over")}", cashMoney(tendered, currency), muted = true)
                                ReceiptRow("  ${L("Change")}", cashMoney(change, currency), muted = true)
                            }
                            payment.reference.nonEmpty?.let { ReceiptDetail("${L("Ref.")} $it") }
                        }
                    }
                    sale.returns.forEach { entry ->
                        key(entry.id) {
                            ReceiptRow("${L("Return")} ${entry.folio}", if (entry.refundAmount > 0) "−${cashMoney(entry.refundAmount, currency)}" else L("credited"), muted = true)
                        }
                    }
                    if (sale.balanceDueAmount > 0) ReceiptRow(L("Balance due"), cashMoney(sale.balanceDueAmount, currency), strong = true)
                }
                footer.nonEmpty?.let { words ->
                    ReceiptSection(modifier = Modifier.padding(top = 4.dp)) {
                        Text(words, color = Theme.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** A part of the ticket under a dashed rule. */
@Composable
private fun ReceiptSection(spacing: Int = 4, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StitchedDivider()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
    }
}

@Composable
private fun ReceiptRow(label: String, value: String, strong: Boolean = false, muted: Boolean = false) {
    val style = receiptType(if (strong) Theme.text(14, FontWeight.Bold) else Theme.text(12.8))
    val ink = if (muted) Theme.inkSoft else Theme.inkStrong
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = style, color = ink, modifier = Modifier.weight(1f).alignByBaseline())
        Spacer(Modifier.width(8.dp))
        Text(value, style = style, color = ink, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun ReceiptDetail(text: String) {
    Text("  $text", color = Theme.inkSoft)
}

// MARK: - A return

/**
 * A return from a counter sale (web `ReturnSheet`): what comes back, whether
 * it goes back on the shelf, how the money goes back (the way it came unless
 * said otherwise), and why.
 */
@Composable
fun CashReturnSheet(sale: CashSale, onDone: suspend () -> Unit, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val device = remember { CashDeviceRegister() }
    val form = remember { FormState() }
    var quantities by remember { mutableStateOf(mapOf<String, Int>()) }
    var restock by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var method by remember { mutableStateOf("cash") }
    val reference = remember { mutableStateOf("") }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val paidBy = sale.payments.firstOrNull { it.kind == "payment" && it.voidedAt == null }?.method ?: "cash"
    /** Credit on account only for a client ("Público en general" has no account). */
    val methods = if (sale.walkIn) CashText.methods else CashText.methods + "credit"
    val lines = sale.lines.filter { it.quantity > it.returnedQuantity }
    /** What the units coming back are worth, as the server works it out. */
    val value = lines.fold(0) { sum, line ->
        val count = quantities[line.id] ?: 0
        if (count <= 0 || line.quantity <= 0) return@fold sum
        val before = (line.totalAmount.toDouble() * line.returnedQuantity / line.quantity).roundToInt()
        val after = (line.totalAmount.toDouble() * (line.returnedQuantity + count) / line.quantity).roundToInt()
        sum + after - before
    }
    val refund = run {
        val owedAfter = max(0, sale.totalAmount - sale.returnedAmount - value)
        max(0, min(value, sale.amountPaid - owedAfter))
    }
    val chosen = quantities.filter { it.value > 0 }.map { it.key to it.value }

    fun submit() {
        if (!form.check(listOf(
                "lines" to (if (chosen.isEmpty()) L("Choose what is coming back.") else null),
                "method" to (if (method == "cash" && refund > 0 && device.sessionId == null) L("Open the register to give cash back") else null),
                "reason" to (if (reason.value.nonEmpty == null) CashReasonKind.`return`.required else null),
            ))) return
        working = true
        scope.launch {
            try {
                val body: Map<String, Any?> = linkedMapOf(
                    "lines" to chosen.map { (id, count) -> mapOf("lineItemId" to id, "quantity" to count, "restock" to (restock[id] ?: true)) },
                    "refundMethod" to method,
                    "reference" to reference.value.nonEmpty,
                    "reason" to reason.value.nonEmpty,
                    "registerSessionId" to device.sessionId,
                )
                API.post<EmptyResponse>("/provider-store/sales/${sale.id}/returns", body)
                Toast.show(
                    when {
                        refund <= 0 -> L("Return done. It came off the balance.")
                        method == "cash" -> LF("Return done. Give back %@.", cashMoney(refund, sale.currency))
                        method == "credit" -> LF("Return done. %@ stays as credit on the account.", cashMoney(refund, sale.currency))
                        else -> LF("Return recorded. Refund %@ by %@.", cashMoney(refund, sale.currency), CashText.method(method).lowercase())
                    },
                )
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                onDone()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (cashAsksReason(error)) {
                    form.check(listOf("reason" to errorText(error)))
                } else if (cashErrorCode(error) == "not_enough_cash") {
                    form.check(listOf("method" to errorText(error)))
                } else {
                    form.apply(error, known = mapOf("reason" to "reason"))
                }
            } finally {
                working = false
            }
        }
    }

    CashKeepsRegister(device)
    LaunchedEffect(Unit) {
        restock = sale.lines.associate { it.id to (it.itemType == "product") }
        method = paidBy
    }

    ProviderModal(
        title = LF("Return from %@", sale.folio),
        subtitle = L("Each item comes back at what was paid for it. Opened medicine does not go back on the shelf."),
        systemImage = "rotate-ccw",
        accent = ModalAccent.aqua,
        isDirty = chosen.isNotEmpty() || reason.value.isNotEmpty(),
        form = form,
        primaryTitle = if (refund > 0) LF("Refund %@", cashMoney(refund, sale.currency)) else L("Take the return"),
        isWorking = working,
        cancelTitle = L("Back"),
        primaryIcon = "undo-2",
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        val customer = sale.customerName
        if (!sale.walkIn && customer != null) {
            CashContextPills(client = customer, pets = sale.petName?.let { listOf(it) } ?: emptyList())
        }
        ModalSection(title = L("What comes back"), modifier = Modifier.formScrollTarget("lines", form)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    key(line.id) {
                        val left = line.quantity - line.returnedQuantity
                        val count = quantities[line.id] ?: 0
                        val shape = RoundedCornerShape(Theme.boxRadius)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Theme.surface, shape)
                                .border(2.dp, if (count > 0) Theme.blue else Theme.borderSoft, shape)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(line.label, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(LP(left, "%d can come back", "%d can come back"), style = Theme.text(12), color = Theme.inkSoft)
                                }
                                CashQuantityStepper(
                                    quantity = count, lessDisabled = count <= 0, moreDisabled = count >= left,
                                    less = {
                                        quantities = quantities + (line.id to max(0, count - 1))
                                        form.clear("lines")
                                    },
                                    more = {
                                        quantities = quantities + (line.id to min(left, count + 1))
                                        form.clear("lines")
                                    },
                                )
                            }
                            // Only what was counted out of the stock can go back into it.
                            if (count > 0 && line.itemType == "product" && line.tracksStock != false) {
                                ToggleRow(
                                    title = L("Goes back on the shelf"),
                                    subtitle = if (restock[line.id] ?: true) L("It returns to stock, to be sold again.") else L("Written off (opened or damaged)"),
                                    isOn = stateOf({ restock[line.id] ?: true }) { on -> restock = restock + (line.id to on) },
                                )
                            }
                        }
                    }
                }
            }
            form.error("lines")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
        }
        if (refund > 0) {
            ModalSection(title = LF("Refund %@ by", cashMoney(refund, sale.currency)), modifier = Modifier.formScrollTarget("method", form)) {
                FlowLayout(spacing = 8.dp) {
                    methods.forEach { option ->
                        CashMethodPill(title = CashText.method(option), isOn = method == option) {
                            method = option
                            form.clear("method")
                        }
                    }
                }
                if (method != paidBy) {
                    Text(
                        LF("It was paid by %@: giving it back another way goes on the day’s exceptions.", CashText.method(paidBy).lowercase()),
                        style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft,
                    )
                }
                form.error("method")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
                if (method != "cash" && method != "credit") {
                    PMTextField(L("Terminal reference (optional)"), text = reference, optional = true)
                }
                CashRegisterGate(device = device, wantsCash = method == "cash", giving = true)
            }
        } else if (value > 0) {
            Text(
                L("Nothing to give back: it comes off what the client still owes."),
                style = Theme.text(14), color = Theme.inkSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                    .padding(12.dp),
            )
        }
        ModalSection(title = L("Why")) {
            CashReasonField(kind = CashReasonKind.`return`, text = reason, form = form)
        }
    }
}
