package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min

//
//  InvoiceDetailScreen.kt (iOS InvoiceDetailScreen.swift)
//
//  One bill (web: provider-invoice-detail-sheet.tsx, provider-invoice-payment-sheet.tsx,
//  visits/visit-money-sheets RefundSheet, invoices-page/cancel-invoice-sheet.tsx):
//  its lines, tax, discount, payments and credit notes, and what can be done
//  with it — charge it, send it to the desk, edit it, refund or credit it,
//  forgive what is left ("Perdonar saldo", docs/family-money.md §1 in
//  paw-miaw-backend), cancel it, bill it again, open or email its receipt. A
//  forgiven bill says what was forgiven, when, by whom and why, and takes no
//  payment, edit or cancel. Level 1 (the bill in front of you) reads it; each
//  action follows its own permission.
//

/** Swift `try?`. */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
fun InvoiceDetailScreen(invoiceId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val loader = remember(invoiceId) { Loader { API.get<CashInvoice>("/provider-invoices/$invoiceId") } }
    var clientEmail by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var again by remember { mutableStateOf(false) }
    var refunding by remember { mutableStateOf(false) }
    var canceling by remember { mutableStateOf(false) }
    var forgiving by remember { mutableStateOf(false) }
    var emailing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val pendingPay = remember { booleanArrayOf(false) }

    suspend fun load() {
        loader.load()
        val bill = loader.value
        if (bill != null && !CashBillRules.isWalkIn(bill) && workspace.can(Permission.clientsView)) {
            val client = attempt { API.get<PickedClient>("/provider-clients/${bill.providerClientId}") }
            clientEmail = client?.ownerEmail
        }
    }

    fun sendToDesk(bill: CashInvoice) {
        sending = true
        scope.launch {
            try {
                val sent = CashBillRules.sendToDesk(bill)
                Toast.show(LF("%@ is at the desk to be charged.", sent.invoiceNumber))
                announceDataChanged()
                loader.load()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.error(error)
            } finally {
                sending = false
            }
        }
    }

    providerPage(loader.value?.invoiceNumber ?: L("Bill"), modifier = modifier) {
        ReloadsOnScope { load() }
        PageScroll(refresh = { loader.load() }) {
            if (workspace.moneyLevel < MoneyLevel.charging) {
                NoAccessState()
            } else {
                LoadedContent(loader) { bill ->
                    InvoiceContent(
                        bill,
                        sending = sending,
                        edit = { editing = true },
                        send = { sendToDesk(bill) },
                        pay = { paying = true },
                        billAgain = { again = true },
                        refund = { refunding = true },
                        cancel = { canceling = true },
                        forgive = { forgiving = true },
                        email = { emailing = true },
                    )
                }
            }
        }
    }

    val bill = loader.value
    if (paying && bill != null) {
        AppSheet(onDismissRequest = { paying = false }) { CashInvoicePaySheet(bill = bill) { scope.launch { loader.load() } } }
    }
    if (editing && bill != null) {
        AppSheet(onDismissRequest = {
            editing = false
            if (pendingPay[0]) {
                pendingPay[0] = false
                paying = true
            }
        }) {
            CashInvoiceEditorSheet(bill = bill, template = null) { _, charge ->
                pendingPay[0] = charge
                scope.launch { loader.load() }
            }
        }
    }
    if (again && bill != null) {
        AppSheet(onDismissRequest = { again = false }) {
            CashInvoiceEditorSheet(bill = null, template = bill) { _, _ -> scope.launch { loader.load() } }
        }
    }
    if (refunding && bill != null) {
        AppSheet(onDismissRequest = { refunding = false }) { CashRefundSheet(bill = bill) { scope.launch { loader.load() } } }
    }
    if (emailing && bill != null) {
        AppSheet(onDismissRequest = { emailing = false }) { CashSendReceiptSheet(bill = bill, clientEmail = clientEmail) }
    }
    if (canceling && bill != null) {
        AppSheet(onDismissRequest = { canceling = false }) { CashCancelBillSheet(bill = bill) { scope.launch { loader.load() } } }
    }
    if (forgiving && bill != null) {
        AppSheet(onDismissRequest = { forgiving = false }) { CashForgiveBillSheet(invoiceId = bill.id, listed = bill) { scope.launch { loader.load() } } }
    }
}

@Composable
private fun InvoiceContent(
    bill: CashInvoice,
    sending: Boolean,
    edit: () -> Unit,
    send: () -> Unit,
    pay: () -> Unit,
    billAgain: () -> Unit,
    refund: () -> Unit,
    cancel: () -> Unit,
    forgive: () -> Unit,
    email: () -> Unit,
) {
    InvoiceHeader(bill, sending, edit, send, pay)
    InvoiceSummary(bill, billAgain, refund, cancel, forgive)
    InvoiceLines(bill)
    InvoicePayments(bill)
    val notes = bill.creditNotes
    if (!notes.isNullOrEmpty()) {
        // A credit note hands no money back, so it is not among the payments: each is listed here with why.
        SectionCard(L("Credit notes"), subtitle = L("Taken off what was owed; no money was handed back."), icon = "receipt-text", accent = Theme.aqua) {
            notes.forEachIndexed { index, note ->
                key(note.id) {
                    if (index > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(L("Credit note"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                            Text(listOfNotNull(note.createdAt?.let { CashBillRules.day(it) }, note.byName.nonEmpty).joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft)
                            note.reason.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp)) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("−${cashMoney(note.amount, bill.currency)}", style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                    }
                }
            }
        }
    }
    if (bill.status != "canceled") InvoiceReceipt(bill, email)
    if (bill.billingNote.nonEmpty != null || bill.notes.nonEmpty != null) {
        SectionCard(L("Notes"), icon = "notebook-text", accent = Theme.aqua) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bill.billingNote.nonEmpty?.let { NoteBlock(L("Billing note"), it) }
                bill.notes.nonEmpty?.let { NoteBlock(L("Internal notes"), it) }
            }
        }
    }
    InvoiceDetails(bill)
    AuditHistoryCard(entityType = "provider_invoice", entityId = bill.id, title = L("History"), description = L("Every change to this bill, its PDF and its payments."))
}

// MARK: Header (the web sheet's title and footer actions)

@Composable
private fun InvoiceHeader(bill: CashInvoice, sending: Boolean, edit: () -> Unit, send: () -> Unit, pay: () -> Unit) {
    val workspace = Workspace.shared
    val kind = CashBillRules.kind(bill)
    ModuleHeader(
        icon = CashBillRules.kindIcon(kind),
        title = bill.invoiceNumber,
        subtitle = LF("%@ · issued %@", CashBillRules.kindLabel(kind), CashBillRules.day(bill.issuedAt ?: bill.createdAt)),
        accent = CashBillRules.kindHue(kind).color,
        storageKey = "invoice",
    ) {
        if (CashBillRules.canEdit(bill, workspace)) PMButton(L("Edit the bill"), systemImage = "pencil", variant = ButtonVariant.outline, action = edit)
        if (CashBillRules.canSendToDesk(bill, workspace)) PMButton(L("Send to the desk"), systemImage = "send", isWorking = sending, action = send)
        if (CashBillRules.canRecordPayment(bill, workspace)) PMButton(LF("Charge %@", cashMoney(bill.balanceDueAmount, bill.currency)), systemImage = "coins", action = pay)
    }
}

// MARK: Who, where it came from, where it stands

@Composable
private fun InvoiceSummary(bill: CashInvoice, billAgain: () -> Unit, refund: () -> Unit, cancel: () -> Unit, forgive: () -> Unit) {
    val workspace = Workspace.shared
    val kind = CashBillRules.kind(bill)
    val today = CashDay.today
    val late = CashBillRules.isOverdue(bill, today)
    val draft = bill.status == "draft"
    val canceled = bill.status == "canceled"
    val forgiven = bill.status == "forgiven"
    val canFinalize = workspace.can(Permission.billingFinalizeInvoice)
    val chip = CashBillRules.chip(bill, today, canFinalize)
    val walkIn = CashBillRules.isWalkIn(bill)
    val money: (Int) -> String = { cashMoney(it, bill.currency) }
    Card(padding = 16.dp, spacing = 16.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CashBillAvatar(petName = bill.petName, photoURL = bill.petPhotoUrl, species = bill.petSpecies, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (walkIn) (bill.customerName.nonEmpty ?: L("Walk-in customer")) else bill.clientName, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1)
                Text(bill.petName.nonEmpty ?: (if (walkIn) L("Counter sale") else L("No pet on it")), style = Theme.text(14), color = Theme.inkSoft, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CashInvoiceStatusChip(text = chip.first, state = chip.second)
                CashBillKindChip(kind = kind)
            }
        }
        CashMoneyCells(
            cells = listOf(
                CashMoneyCells.Cell(label = L("Total"), value = money(if (canceled) bill.totalAmount else CashBillRules.netTotal(bill)), tone = if (canceled) CashMoneyCells.Tone.soft else CashMoneyCells.Tone.strong, struck = canceled),
                CashMoneyCells.Cell(label = L("Paid@amount"), value = money(bill.amountPaid), tone = if (bill.amountPaid > 0) CashMoneyCells.Tone.strong else CashMoneyCells.Tone.soft),
                CashMoneyCells.Cell(
                    label = L("Balance"), value = if (draft || canceled) "—" else money(bill.balanceDueAmount),
                    tone = if (draft || canceled || bill.balanceDueAmount <= 0) CashMoneyCells.Tone.soft else if (late) CashMoneyCells.Tone.late else CashMoneyCells.Tone.strong,
                ),
            ),
            stacksOnPhone = true,
        )
        if (canceled) {
            Column(
                Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    (bill.canceledAt?.let { LF("Canceled %@", CashBillRules.dateTime(it)) } ?: L("Canceled@status")) + (bill.canceledBy?.name.nonEmpty?.let { " · $it" } ?: ""),
                    style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                )
                bill.cancelReason.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
            }
        } else if (forgiven) {
            // "Saldo perdonado {monto} · {fecha} · {quién}", then why: the business's own record of it.
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.chipRadius))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    listOfNotNull(
                        LF("Balance forgiven %@", money(bill.forgivenAmount ?: 0)),
                        bill.forgivenAt?.let { CashBillRules.dateTime(it) }?.nonEmpty,
                        bill.forgivenBy?.name.nonEmpty,
                    ).joinToString(" · "),
                    style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                )
                bill.forgivenReason.nonEmpty?.let { Text("“$it”", style = Theme.text(14), color = Theme.inkStrong) }
                Text(L("The family sees “Balance forgiven” in its receipts; the reason stays with your team."), style = Theme.text(12), color = Theme.inkSoft)
            }
        } else if (late) {
            val days = CashBillRules.daysLate(bill, today)
            Text(
                (if (days == 0) L("Marked overdue") else CashText.daysLate(days)) + (bill.dueAt?.let { " · " + LF("Due %@", CashBillRules.day(it)) } ?: ""),
                style = Theme.text(14, FontWeight.Bold), color = Theme.destructive,
            )
        } else if (draft) {
            SummaryNote(
                if (bill.lines.any { it.unitAmount <= 0 && it.courtesy != true } || bill.lines.isEmpty()) L("A draft waiting for a price: every line needs one, or to be marked as a courtesy, before it can be charged.")
                else if (canFinalize) L("A draft — only your team sees it. Send it to the desk when it is ready to charge.") else L("A draft — someone who can finalize bills sends it to the desk."),
            )
        } else if (kind == "deposit") {
            SummaryNote(L("A deposit becomes credit on the client’s account and is spent at checkout."))
        } else if (kind == "sale") {
            SummaryNote(L("A counter sale changes with a return or a cancel in the Store, not here."))
        } else if (workspace.can(Permission.billingEditInvoice) && bill.amountPaid > 0) {
            SummaryNote(L("It has payments: to cancel it, give the money back first with a refund."))
        }
        InvoiceLinks(bill, billAgain, refund, cancel, forgive)
    }
}

@Composable
private fun SummaryNote(text: String) {
    Text(text, style = Theme.text(14), color = Theme.inkSoft)
}

/** Where the bill goes from here (web: the sheet's link chips). */
@Composable
private fun InvoiceLinks(bill: CashInvoice, billAgain: () -> Unit, refund: () -> Unit, cancel: () -> Unit, forgive: () -> Unit) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val source = CashBillRules.source(bill)?.takeIf { CashBillRules.sourceAllowed(it, workspace) }
    val openClient = workspace.can(Permission.clientsView) && !CashBillRules.isWalkIn(bill)
    val store = CashBillRules.canCollectAtStore(bill, workspace)
    val again = CashBillRules.canBillAgain(bill, workspace)
    val canRefund = CashBillRules.canRefund(bill, workspace)
    val canCancel = CashBillRules.canCancel(bill, workspace)
    val canForgive = CashBillRules.canForgive(bill, workspace)
    if (source != null || openClient || store || again || canRefund || canForgive || canCancel) {
        FlowLayout(spacing = 6.dp) {
            if (source != null) CashLinkChip(title = CashBillRules.sourceLabel(source), icon = "external-link", modifier = Modifier.plainButton { nav.push(source) })
            if (openClient) CashLinkChip(title = L("Open client"), icon = "user-round", modifier = Modifier.plainButton { nav.push(ProviderRoute.client(bill.providerClientId)) })
            if (store) CashLinkChip(title = L("Charge at the Store, with products"), icon = "shopping-cart", modifier = Modifier.plainButton { nav.push(CashBillRules.storeCheckout(bill)) })
            if (again) CashLinkChip(title = L("Bill again"), icon = "copy", modifier = Modifier.plainButton(onClick = billAgain))
            if (canRefund) CashLinkChip(title = L("Refund or credit note"), icon = "undo-2", danger = true, modifier = Modifier.plainButton(onClick = refund))
            if (canForgive) CashLinkChip(title = L("Forgive the balance"), icon = "hand-heart", danger = true, modifier = Modifier.plainButton(onClick = forgive))
            if (canCancel) CashLinkChip(title = L("Cancel the bill"), icon = "ban", danger = true, modifier = Modifier.plainButton(onClick = cancel))
        }
    }
}

// MARK: What it charges

@Composable
private fun InvoiceLines(bill: CashInvoice) {
    val money: (Int) -> String = { cashMoney(it, bill.currency) }
    val draft = bill.status == "draft"
    val isSale = CashBillRules.kind(bill) == "sale"
    SectionCard(L("What it charges"), icon = "file-text", accent = Theme.aqua) {
        if (bill.lines.isEmpty()) {
            Text(L("No items yet."), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(16.dp))
        }
        bill.lines.forEachIndexed { index, line ->
            key(line.id) {
                if (index > 0) RowDivider()
                LineRow(line, draft, money)
            }
        }
        if (bill.lines.isNotEmpty()) RowDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CashBillTotalRow(label = L("Subtotal"), value = money(bill.subtotalAmount))
            if (bill.discountAmount > 0) CashBillTotalRow(label = L("Discount"), value = "−${money(bill.discountAmount)}")
            if (bill.taxAmount > 0) CashBillTotalRow(label = if (bill.pricesIncludeTax == true) L("Tax (included in the prices)") else L("Tax"), value = money(bill.taxAmount))
            CashBillTotalRow(label = L("Total"), value = money(bill.totalAmount), strong = true)
            val returned = bill.refunded
            if (returned > 0) {
                // Money given back shows as a payment below; a credit note does not, so each is said by name when a bill has both.
                val moneyBack = min(returned, bill.allPayments.filter { it.isRefund && !it.voided }.sumOf { it.amount })
                val credited = max(0, returned - moneyBack)
                if (!isSale && moneyBack > 0 && credited > 0) {
                    CashBillTotalRow(label = L("Refunded"), value = "−${money(moneyBack)}")
                    CashBillTotalRow(label = L("Credit notes"), value = "−${money(credited)}")
                } else {
                    CashBillTotalRow(
                        label = if (isSale) L("Returned") else if (moneyBack > 0) L("Refunded") else if (credited > 0) L("Credit notes") else L("Refunded or credited"),
                        value = "−${money(returned)}",
                    )
                }
            }
            val forgivenAmount = bill.forgivenAmount ?: 0
            if (forgivenAmount > 0) CashBillTotalRow(label = L("Forgiven@amount"), value = "−${money(forgivenAmount)}")
            if (!draft && bill.status != "canceled") {
                CashBillTotalRow(label = L("Paid@amount"), value = money(bill.amountPaid))
                CashBillTotalRow(label = L("Balance"), value = money(bill.balanceDueAmount), strong = true)
            }
            bill.discountReason.nonEmpty?.let { reason ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${L("Why the discount")}: ") }
                        append(reason)
                    },
                    style = Theme.text(14), color = Theme.inkStrong,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.mango, 14.0), RoundedCornerShape(Theme.chipRadius))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** One line: what it is, how many at what price, and what stands out about it (web `LineRow`). */
@Composable
private fun LineRow(line: CashInvoiceLine, draft: Boolean, money: (Int) -> String) {
    val listed = line.listUnitAmount ?: line.catalogItem?.defaultUnitAmount
    val overridden = listed != null && listed != line.unitAmount && line.unitAmount > 0
    val unpriced = draft && line.unitAmount <= 0 && line.courtesy != true
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 2.dp).size(28.dp).background(Theme.wash(Theme.aqua, 18.0), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(if (line.itemType == "product") "package" else "stethoscope", 14.dp, tint = Theme.inkStrong)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.label, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(
                LF("%d × %@", line.quantity, money(line.unitAmount)) +
                    (if (overridden) " · " + LF("Catalog price %@", money(listed ?: 0)) + (line.priceOverriddenBy?.name.nonEmpty?.let { " · $it" } ?: "") else ""),
                style = Theme.text(12), color = Theme.inkSoft,
            )
            line.description.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp)) }
            if (unpriced || line.courtesy == true || line.changedAfterSent == true) {
                Box(Modifier.padding(top = 4.dp)) {
                    FlowLayout(spacing = 4.dp) {
                        if (unpriced) CashLineFlag(text = L("Needs a price"), tone = Theme.destructive, danger = true)
                        if (line.courtesy == true) CashLineFlag(text = L("Courtesy"), tone = Theme.pink)
                        if (line.changedAfterSent == true) CashLineFlag(text = L("Changed after it went to the desk"), tone = Theme.mango)
                    }
                }
            }
            line.adjustmentReason.nonEmpty?.let { Text("“$it”", style = Theme.text(12), color = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp)) }
        }
        Spacer(Modifier.width(8.dp))
        Text(money(line.quantity * line.unitAmount), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
    }
}

// MARK: Payments

@Composable
private fun InvoicePayments(bill: CashInvoice) {
    SectionCard(L("Payments"), icon = "coins", accent = Theme.pink) {
        if (bill.allPayments.isEmpty()) {
            Text(L("No payments yet."), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(16.dp))
        }
        bill.allPayments.forEachIndexed { index, payment ->
            key(payment.id) {
                if (index > 0) RowDivider()
                val struck = if (payment.voided) TextDecoration.LineThrough else null
                val ink = if (payment.voided) Theme.inkSoft else Theme.inkStrong
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                (if (payment.isRefund) "${L("Refund")} · " else "") + (if (payment.method == "credit") L("From their deposit") else CashText.method(payment.method)),
                                style = Theme.text(14, FontWeight.Bold).copy(textDecoration = struck), color = ink,
                            )
                            if (payment.voided) {
                                Text(
                                    L("Voided"), style = Theme.text(11.2, FontWeight.Bold), color = Theme.inkSoft,
                                    modifier = Modifier.background(Theme.inkSoft.copy(alpha = 0.14f), CapsuleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            listOfNotNull(payment.paidAt?.let { CashBillRules.day(it) }, payment.reference.nonEmpty, payment.collectedBy?.name.nonEmpty).joinToString(" · "),
                            style = Theme.text(12), color = Theme.inkSoft,
                        )
                        payment.notes.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${if (payment.isRefund) "−" else ""}${cashMoney(payment.amount, payment.currency ?: bill.currency)}",
                        style = Theme.text(14, FontWeight.Bold).monospacedDigit().copy(textDecoration = struck), color = ink,
                    )
                }
            }
        }
    }
}

// MARK: Receipt

@Composable
private fun InvoiceReceipt(bill: CashInvoice, email: () -> Unit) {
    val hasReceipt = bill.status != "draft" && bill.amountPaid > 0
    SectionCard(
        if (hasReceipt) L("Receipt") else L("The bill as a PDF"),
        subtitle = if (hasReceipt) L("What the client is handed: the bill with its payments. It is not a tax invoice (CFDI).") else L("The bill as it stands, to show or send before it is paid. The receipt comes with the first payment."),
        icon = "receipt-text", accent = Theme.aqua,
    ) {
        Box(Modifier.padding(16.dp)) {
            FlowLayout(spacing = 8.dp) {
                DocumentButton(
                    title = L("Open or print the PDF"), systemImage = "external-link", path = "/provider-invoices/${bill.id}/pdf", fileName = bill.invoiceNumber,
                    // The server drops the stored PDF whenever the bill changes: none kept = make a fresh one.
                    prepare = { if (bill.pdfUrl == null) API.post<EmptyResponse>("/provider-invoices/${bill.id}/pdf") },
                )
                if (hasReceipt) PMButton(L("Email the receipt"), systemImage = "mail", variant = ButtonVariant.outline, size = ButtonSize.compact, action = email)
                if (CashBillRules.kind(bill) == "deposit") {
                    DocumentButton(title = L("Deposit receipt"), systemImage = "file-down", path = "/provider-finance/deposits/${bill.id}/pdf", fileName = bill.invoiceNumber)
                }
            }
        }
    }
}

// MARK: Notes and details

@Composable
private fun NoteBlock(label: String, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
        Text(text, style = Theme.text(14), color = Theme.inkStrong)
    }
}

@Composable
private fun InvoiceDetails(bill: CashInvoice) {
    val rows = mutableListOf(
        L("Issued") to (bill.issuedAt?.let { CashBillRules.day(it) } ?: L("Not set")),
        L("Due") to (bill.dueAt?.let { CashBillRules.day(it) } ?: L("Not set")),
        L("Made by") to (bill.createdBy?.name.nonEmpty ?: L("Someone on the team")),
    )
    bill.finalizedBy?.name.nonEmpty?.let { rows.add(L("Sent to the desk by") to it) }
    bill.assignedMember?.name.nonEmpty?.let { rows.add(L("Assigned to") to it) }
    rows.add(L("Last changed") to listOfNotNull(bill.updatedAt?.let { CashBillRules.dateTime(it) }, bill.updatedBy?.name.nonEmpty).joinToString(" · "))
    SectionCard(L("Details"), icon = "info", accent = Theme.aqua) {
        rows.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.first.uppercase(), style = Theme.text(12, FontWeight.Bold).tracking(0.96), color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.weight(1f))
                Text(row.second.nonEmpty ?: "—", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, textAlign = TextAlign.End, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

// MARK: - Pieces of a bill

/** `CashMoneyCells.Tone` / `CashMoneyCells.Cell`. */
object CashMoneyCells {
    enum class Tone { strong, soft, late }
    data class Cell(val label: String, val value: String, val tone: Tone = Tone.strong, val struck: Boolean = false)
}

/**
 * Total · Paid · Balance in hairline cells (web `MoneyCell` / `SheetMoney`):
 * a micro-label and the amount in Fredoka; on a phone the bill's page reads
 * them one per row (label left, amount right).
 */
@Composable
fun CashMoneyCells(cells: List<CashMoneyCells.Cell>, stacksOnPhone: Boolean = false, modifier: Modifier = Modifier) {
    val stacked = stacksOnPhone && LocalSizeClass.current != SizeClass.regular
    val shape = RoundedCornerShape(Theme.boxRadius)
    val frame = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Theme.borderSoft)
        .border(1.dp, Theme.borderSoft, shape)
    if (stacked) {
        Column(frame, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            cells.forEach { MoneyCell(it, row = true, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(frame.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            cells.forEach { MoneyCell(it, row = false, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@Composable
private fun MoneyCell(cell: CashMoneyCells.Cell, row: Boolean, modifier: Modifier) {
    val labelStyle = Theme.text(10.6, FontWeight.ExtraBold).tracking(1.06)
    val valueStyle = Theme.heading(if (row) 16 else 18).monospacedDigit().copy(textDecoration = if (cell.struck) TextDecoration.LineThrough else null)
    val valueInk = when (cell.tone) {
        CashMoneyCells.Tone.late -> Theme.destructive
        CashMoneyCells.Tone.soft -> Theme.inkSoft
        CashMoneyCells.Tone.strong -> Theme.inkStrong
    }
    Box(modifier.background(Theme.surface).padding(horizontal = 12.dp)) {
        if (row) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(cell.label.uppercase(), style = labelStyle, color = Theme.inkSoft, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.weight(1f))
                FittedText(cell.value, style = valueStyle, color = valueInk, minimumScaleFactor = 0.7f, modifier = Modifier.alignByBaseline())
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cell.label.uppercase(), style = labelStyle, color = Theme.inkSoft, maxLines = 1)
                FittedText(cell.value, style = valueStyle, color = valueInk, minimumScaleFactor = 0.7f)
            }
        }
    }
}

/**
 * A label and an amount in a bill's totals (web `TotalRow`): grey and semibold,
 * or bold with the amount in Fredoka 18 for the total and the balance.
 */
@Composable
fun CashBillTotalRow(label: String, value: String, strong: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = Theme.text(14, if (strong) FontWeight.Bold else FontWeight.Normal), color = if (strong) Theme.inkStrong else Theme.inkSoft, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.weight(1f))
        Text(value, style = (if (strong) Theme.heading(18) else Theme.text(14, FontWeight.SemiBold)).monospacedDigit(), color = Theme.inkStrong, modifier = Modifier.alignByBaseline())
    }
}

/** What stands out about a line (web `LineFlag`): a 20 capsule with the tone's edge and wash. */
@Composable
private fun CashLineFlag(text: String, tone: androidx.compose.ui.graphics.Color, danger: Boolean = false) {
    Box(
        Modifier
            .height(20.dp)
            .background(Theme.wash(tone, 12.0), CapsuleShape)
            .border(1.dp, Theme.mix(tone, 45.0, Theme.borderSoft), CapsuleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Theme.text(10.9, FontWeight.ExtraBold), color = if (danger) Theme.destructive else Theme.inkStrong, maxLines = 1)
    }
}

/** A place a bill links to (web `LINK_CHIP`): a 36 capsule with a grey edge, 12 bold; red for a refund or a cancel. */
@Composable
private fun CashLinkChip(title: String, icon: String, danger: Boolean = false, modifier: Modifier = Modifier) {
    val ink = if (danger) Theme.destructive else Theme.inkStrong
    Row(
        modifier
            .height(36.dp)
            .background(Theme.surface, CapsuleShape)
            .border(1.dp, Theme.borderSoft, CapsuleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(icon, 14.dp, tint = ink)
        Text(title, style = Theme.text(12, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

// MARK: - Canceling a bill

/**
 * Canceling a bill, asked with its reason (web `CancelInvoiceSheet`): always
 * POST /provider-invoices/{id}/cancel; a canceled bill is never opened again.
 */
@Composable
fun CashCancelBillSheet(bill: CashInvoice, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    /** What else the cancel undoes (web: the list under the reason). */
    val consequences = buildList {
        if (bill.status != "draft" && bill.lines.any { it.itemType == "product" }) add(L("Its products go back on the shelf."))
        if (bill.lines.any { it.prescriptionItemId != null }) add(L("What it dispensed goes back to the prescription."))
        if (CashBillRules.kind(bill) == "visit") add(L("The visit itself stays as it is."))
    }
    val lead = if (bill.status == "draft") {
        L("This draft leaves the list of bills to charge and stays in the list as canceled. It can’t be opened again: to charge it again, use “Bill again”.")
    } else {
        L("It leaves what the client owes and stays in the list as canceled. It can’t be opened again: to charge it again, use “Bill again”.")
    }
    ConfirmSheet(
        title = LF("Cancel %@?", bill.invoiceNumber),
        message = (listOf(lead) + consequences).joinToString("\n\n"),
        confirmTitle = L("Cancel the bill"), destructive = true, askReason = true, reasonRequired = true,
        reasonLabel = L("Why is it canceled?"), cancelTitle = L("Keep it"),
        modifier = modifier,
    ) { reason ->
        val body = linkedMapOf<String, Any?>("reason" to reason)
        bill.revision?.let { body["expectedRevision"] = it }
        try {
            val canceled: CashInvoice = API.post("/provider-invoices/${bill.id}/cancel", body)
            Toast.show(LF("%@ was canceled.", canceled.invoiceNumber))
            announceDataChanged()
            onDone()
        } catch (error: APIError) {
            if (error.code != "stale_bill") throw error
            onDone()
            throw APIError(status = 409, message = L("Someone changed this bill a moment ago — look at it again before canceling."), code = error.code, fields = emptyMap())
        }
    }
}

// MARK: - Forgiving what is left

/**
 * "Perdonar saldo" (web invoices-page/forgive-invoice-sheet.tsx; docs/family-money.md §1 and §6 in
 * paw-miaw-backend): what is left on a bill out of draft is forgiven, with a reason the business
 * keeps (the family only reads "Saldo perdonado"). Said before it is done: the amount, that it is
 * an exception in Cortes, that it leaves Te deben and is not income, and that what was paid stays
 * paid. Permission `billing.waive_visit`; callers only offer it to those who have it. Opened from
 * a bill (Cuentas' row and the bill's page), from a visit whose fee the family disputes and from
 * Home's "Te necesita": the bill is read in full here, so its id is enough (`listed` shows while
 * it is read). Always POST /provider-invoices/{id}/forgive with the bill's revision.
 */
@Composable
fun CashForgiveBillSheet(invoiceId: String, listed: CashInvoice? = null, modifier: Modifier = Modifier, onDone: (CashInvoice) -> Unit = {}) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<CashInvoice?>(null) }
    LaunchedEffect(invoiceId) {
        detail = attempt { API.get<CashInvoice>("/provider-invoices/$invoiceId") }
    }
    val bill = detail?.takeIf { it.id == invoiceId } ?: listed?.takeIf { it.id == invoiceId }
    val money: (Int) -> String = { cashMoney(it, bill?.currency ?: "MXN") }
    val amount = bill?.let { money(it.balanceDueAmount) } ?: ""
    val blocker = bill?.let { CashBillRules.forgiveBlocker(it) }
    val asks = bill != null && blocker == null

    fun confirm() {
        val current = bill ?: return
        if (blocker != null) {
            Toast.error(CashBillRules.forgiveRefusalText(blocker) ?: L("This bill can’t be forgiven now."))
            return
        }
        if (!form.check(listOf("reason" to if (reason.value.nonEmpty == null) L("Say why the balance is forgiven.") else null))) return
        val body = linkedMapOf<String, Any?>("reason" to reason.value.trim())
        current.revision?.let { body["expectedRevision"] = it }
        working = true
        scope.launch {
            try {
                val forgiven: CashInvoice = API.post("/provider-invoices/${current.id}/forgive", body)
                Toast.show(LF("%1\$@ of %2\$@ forgiven.", money(forgiven.forgivenAmount ?: current.balanceDueAmount), current.invoiceNumber))
                announceDataChanged()
                onDone(forgiven)
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                val code = (failure as? APIError)?.code
                val words = CashBillRules.forgiveRefusalText(code)
                when {
                    code == "reason_required" -> form.check(listOf("reason" to (words ?: "")))
                    code == "stale_bill" -> {
                        Toast.error(words ?: errorText(failure))
                        dismiss()
                    }
                    words != null -> Toast.error(words)
                    else -> Toast.error(errorText(failure, L("Could not forgive the balance.")))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (asks) LF("Forgive %1\$@ of %2\$@?", amount, bill?.invoiceNumber ?: "") else L("Forgive the balance"),
        subtitle = L("It is recorded as an exception in Cortes. The family sees “Balance forgiven”."),
        systemImage = "hand-heart",
        accent = ModalAccent.pink,
        isDirty = reason.value.isNotEmpty(),
        form = form,
        primaryTitle = if (asks) LF("Forgive %@", amount) else L("Forgive the balance"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Keep charging"),
        primaryIcon = "hand-heart",
        primaryDisabled = bill == null,
        onPrimary = { confirm() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        if (bill == null) {
            LoadingState()
        } else if (blocker != null) {
            Text(CashBillRules.forgiveRefusalText(blocker) ?: L("This bill can’t be forgiven now."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive)
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(L("It leaves what the client owes, Te deben and To charge, and doesn’t count as income."), style = Theme.text(14), color = Theme.inkStrong)
                if (bill.amountPaid > 0) Text(LF("What was paid (%@) stays paid.", money(bill.amountPaid)), style = Theme.text(14), color = Theme.inkStrong)
            }
            PMTextField(
                L("Why is it forgiven?"), text = reason, key = "reason", form = form,
                prompt = L("e.g. The family moved away, a courtesy, a dispute settled"),
                hint = L("Only your team sees the reason."),
                multiline = true, required = true,
            )
        }
    }
}

// MARK: - Charging a bill

/** What a payment left: (paid, change, left, emailedTo). */
private data class CashPayDone(val paid: Int, val change: Int, val left: Int, val emailedTo: String?)

/**
 * Taking money on one bill: today through the one checkout (methods split,
 * change, credit on account, the register), or a payment that arrived on an
 * earlier day (recorded on that day, out of today's shift).
 */
@Composable
fun CashInvoicePaySheet(bill: CashInvoice, modifier: Modifier = Modifier, onSaved: () -> Unit = {}) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val device = remember { CashDeviceRegister() }
    val tenders = remember { CashTenders() }
    val form = remember { FormState() }
    var account by remember { mutableStateOf<CashAccount?>(null) }
    var clientEmail by remember { mutableStateOf<String?>(null) }
    var clientLinked by remember { mutableStateOf<Boolean?>(null) }
    val earlier = remember { mutableStateOf(false) }
    val amount = remember { mutableStateOf<Int?>(null) }
    var method by remember { mutableStateOf("bank_transfer") }
    val zone = workspace.timeZone
    val yesterday = remember { ZonedDateTime.now(zone).minusDays(1).toInstant() }
    val paidOn = remember { mutableStateOf<Instant>(Instant.now()) }
    val reference = remember { mutableStateOf("") }
    val notes = remember { mutableStateOf("") }
    val email = remember { mutableStateOf("") }
    val sendReceipt = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<CashPayDone?>(null) }
    val requestId = remember { cashRequestId() }
    var seed by remember { mutableIntStateOf(0) }

    val balance = bill.balanceDueAmount
    val registerOpen = device.sessionId != null && !device.stale
    val walkIn = CashBillRules.isWalkIn(bill)
    val firstDay: Instant? = if (workspace.moneyLevel >= MoneyLevel.finances) null else ZonedDateTime.now(zone).minusDays(MoneyLevel.frontDeskDays.toLong()).toInstant()

    CashKeepsRegister(device)
    LaunchedEffect(Unit) {
        device.load()
        if (!walkIn) {
            account = attempt { API.get<CashAccount>("/provider-finance/clients/${bill.providerClientId}/account") }
            if (workspace.can(Permission.clientsView)) {
                val client = attempt { API.get<PickedClient>("/provider-clients/${bill.providerClientId}") }
                clientEmail = client?.ownerEmail
                clientLinked = client?.let { it.householdId != null }
            }
        }
        tenders.start(total = balance, credit = account?.credit ?: 0, registerOpen = device.sessionId != null, allowBalance = true, currency = bill.currency)
        amount.value = balance
        seed += 1
        paidOn.value = yesterday
    }

    fun chargeToday() {
        tenders.problem(registerOpen)?.let { problem ->
            Toast.error(problem)
            return
        }
        val payments = tenders.payments()
        if (payments.isEmpty()) {
            Toast.error(L("Enter the amount paid."))
            return
        }
        working = true
        scope.launch {
            try {
                val body = linkedMapOf<String, Any?>(
                    "clientRequestId" to tenders.requestId,
                    "providerClientId" to bill.providerClientId,
                    "providerClientPetId" to bill.providerClientPetId,
                    "invoiceIds" to listOf(bill.id),
                    "lines" to emptyList<Any>(),
                    "ticketDiscount" to null,
                    "discountReason" to null,
                    "payments" to payments,
                    "registerSessionId" to device.sessionId,
                    "note" to null,
                )
                body.putAll(CashReceiptEmailField.delivery(clientEmail = clientEmail, typed = email.value, send = sendReceipt.value, canSave = !walkIn))
                val result: CashCheckoutResult = API.post("/provider-store/checkout", body)
                val left = result.invoices.firstOrNull { it.id == bill.id }?.balance ?: result.balanceLeft
                done = CashPayDone(paid = result.paid, change = result.change, left = left, emailedTo = result.receipt?.emailedTo)
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.error(error, L("Could not record this payment."))
            } finally {
                working = false
            }
        }
    }

    fun recordEarlier() {
        val value = amount.value ?: 0
        val passes = form.check(listOf(
            "amount" to (if (value <= 0) L("Enter the amount paid.") else if (value > balance) LF("That is more than the %@ still owed.", cashMoney(balance, bill.currency)) else null),
            "paidAt" to (if (DateText.dayKey(paidOn.value) >= CashDay.today) L("Choose a day before today — today’s payments are taken above.") else null),
        ))
        if (!passes) return
        working = true
        scope.launch {
            try {
                val updated: CashInvoice = API.post("/provider-invoices/${bill.id}/payments", mapOf(
                    "amount" to value,
                    "currency" to bill.currency,
                    "method" to method,
                    "reference" to reference.value.nonEmpty,
                    "notes" to notes.value.nonEmpty,
                    "paidAt" to DateText.dayKey(paidOn.value),
                    "registerSessionId" to null,
                    "clientRequestId" to requestId,
                ))
                done = CashPayDone(paid = value, change = 0, left = updated.balanceDueAmount, emailedTo = updated.receiptEmailedTo)
                announceDataChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                form.apply(error, mapOf("amount" to "amount", "paidAt" to "paidAt"))
            } finally {
                working = false
            }
        }
    }

    val paid = done
    ProviderModal(
        title = LF("Charge %@", bill.invoiceNumber),
        subtitle = if (paid == null) LF("%@ to collect", cashMoney(balance, bill.currency)) else null,
        systemImage = "coins", accent = ModalAccent.pink, isDirty = paid == null && (earlier.value || tenders.paid != balance), form = form,
        primaryTitle = when {
            paid != null -> L("Done")
            earlier.value -> L("Record the payment")
            tenders.remaining > 0 && tenders.paid > 0 -> LF("Charge %@ · %@ stays owed", cashMoney(tenders.paid, bill.currency), cashMoney(tenders.remaining, bill.currency))
            else -> LF("Charge %@", cashMoney(tenders.paid, bill.currency))
        },
        // Paid: the web's footer is only "Done".
        isWorking = working, cancelTitle = L("Back"), primaryIcon = if (paid != null) null else "banknote", showsCancel = paid == null,
        onPrimary = {
            if (done != null) {
                onSaved()
                dismiss()
            } else if (earlier.value) recordEarlier() else chargeToday()
        },
        modifier = modifier,
    ) {
        // Who it is for (web `ModalContextPills`).
        ContextPills(
            client = if (walkIn) (bill.customerName.nonEmpty ?: L("Walk-in customer")) else bill.clientName,
            linked = if (walkIn) null else clientLinked,
            pets = bill.petName.nonEmpty?.let { listOf(it) } ?: emptyList(),
        )
        if (paid != null) {
            Column(
                Modifier.fillMaxWidth().background(Theme.wash(ProviderState.done.color, 14.0), RoundedCornerShape(Theme.boxRadius)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Glyph("circle-check", 20.dp, tint = ProviderState.done.color)
                    Text(LF("Paid %@", cashMoney(paid.paid, bill.currency)), style = Theme.heading(20), color = Theme.inkStrong)
                }
                if (paid.change > 0) {
                    Text(LF("Change to hand back: %@", cashMoney(paid.change, bill.currency)), style = Theme.heading(24), color = Theme.inkStrong)
                }
                Text(
                    if (paid.left > 0) LF("%@ still owed on %@.", cashMoney(paid.left, bill.currency), bill.invoiceNumber) else LF("%@ is paid in full.", bill.invoiceNumber),
                    style = Theme.text(14), color = Theme.inkSoft,
                )
                // The receipt, ready (web `ReceiptReady`): where it went, its PDF, sending it now.
                CashReceiptReady(documents = listOf(CashReceiptDocument(id = bill.id, folio = bill.invoiceNumber)), emailedTo = paid.emailedTo, clientEmail = clientEmail)
            }
        } else if (balance <= 0) {
            Text(
                L("Nothing is owed on this bill any more."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong,
                modifier = Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            CashMoneyCells(cells = listOf(
                CashMoneyCells.Cell(label = L("Total"), value = cashMoney(CashBillRules.netTotal(bill), bill.currency)),
                CashMoneyCells.Cell(label = L("Paid@amount"), value = cashMoney(bill.amountPaid, bill.currency), tone = if (bill.amountPaid == 0) CashMoneyCells.Tone.soft else CashMoneyCells.Tone.strong),
                CashMoneyCells.Cell(label = L("Balance"), value = cashMoney(balance, bill.currency)),
            ))
            if (earlier.value) {
                ModalSection(title = L("How much")) {
                    key(seed) { MoneyField(label = L("Amount"), cents = amount, key = "amount", form = form) }
                }
                ModalSection(title = L("How they paid")) {
                    FlowLayout(spacing = 6.dp) {
                        listOf("card", "bank_transfer", "digital_wallet", "other").forEach { option ->
                            CashMethodPill(title = CashText.method(option), isOn = method == option) { method = option }
                        }
                    }
                    DateTimeField(
                        label = L("Paid on"), date = paidOn, min = firstDay, max = yesterday, components = setOf(DatePickerComponent.date), key = "paidAt", form = form,
                        hint = if (workspace.moneyLevel < MoneyLevel.finances) L("Up to 7 days back: older days need business finances.") else null, required = true,
                    )
                    PMTextField(L("Reference"), text = reference, prompt = L("Authorization or transfer number"))
                    Text(
                        L("It is recorded on that day and stays out of today’s register shift. Cash is always recorded on the day it is taken."),
                        style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft,
                        modifier = Modifier.fillMaxWidth().background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                ModalSection(title = L("Notes")) {
                    PMTextField(L("Notes"), text = notes, prompt = L("Anything the team should know about this payment."), multiline = true)
                }
            } else {
                ModalSection(title = L("How they pay"), subtitle = L("Split it across methods, or take a part now: the rest stays owed on this bill.")) {
                    CashTendersView(state = tenders, registerOpen = registerOpen)
                    CashRegisterGate(device = device, wantsCash = tenders.wantsCash)
                }
            }
            CashReceiptEmailField(clientEmail = clientEmail, automatic = true, email = email, send = sendReceipt)
            ToggleRow(title = L("It was paid on an earlier day"), subtitle = L("A transfer or a card payment that arrived before today."), isOn = earlier)
        }
    }
}

// MARK: - Refund or credit note

@Serializable
private data class CashRefundDone(val id: String)

@Composable
fun CashRefundSheet(bill: CashInvoice, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val device = remember { CashDeviceRegister() }
    val form = remember { FormState() }
    // `.onAppear`: money back when something was paid; the method it was paid with.
    var moneyBack by remember { mutableStateOf(bill.amountPaid > 0) }
    val amount = remember { mutableStateOf<Int?>(if (bill.amountPaid > 0) bill.amountPaid else bill.balanceDueAmount) }
    var method by remember {
        val paidWith = bill.allPayments.lastOrNull { !it.isRefund && !it.voided && it.method != "credit" }?.method
        mutableStateOf(paidWith?.takeIf { it in CashText.methods } ?: "cash")
    }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }

    CashKeepsRegister(device)

    fun submit() {
        val value = amount.value ?: 0
        val passes = form.check(listOf(
            "amount" to (if (value <= 0) L("Enter the amount") else null),
            "reason" to (if (reason.value.nonEmpty == null) CashReasonKind.refund.required else null),
        ))
        if (!passes) return
        if (moneyBack && method == "cash" && device.sessionId == null) {
            Toast.error(L("Open the register to give cash back"))
            return
        }
        working = true
        scope.launch {
            try {
                API.post<CashRefundDone>("/provider-finance/invoices/${bill.id}/refund", mapOf(
                    "amount" to value,
                    "moneyBack" to moneyBack,
                    "method" to (if (moneyBack) method else null),
                    "reason" to reason.value.nonEmpty,
                    "registerSessionId" to (if (moneyBack) device.sessionId else null),
                ))
                Toast.show(if (moneyBack) LF("%@ given back.", cashMoney(value, bill.currency)) else LF("%@ taken off the bill.", cashMoney(value, bill.currency)))
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                onDone()
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (cashAsksReason(error)) form.check(listOf("reason" to errorText(error))) else form.apply(error, mapOf("amount" to "amount", "reason" to "reason"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Refund on %@", bill.invoiceNumber),
        subtitle = LF("Paid %@ · owes %@", cashMoney(bill.amountPaid, bill.currency), cashMoney(bill.balanceDueAmount, bill.currency)),
        systemImage = "undo-2", accent = ModalAccent.pink, isDirty = reason.value.isNotEmpty(), form = form,
        primaryTitle = if (moneyBack) L("Give money back") else L("Take it off the bill"), primaryVariant = ButtonVariant.destructive,
        isWorking = working, cancelTitle = L("Back"),
        onPrimary = { submit() },
        modifier = modifier,
    ) {
        ChoiceCards(
            options = listOf(
                Triple(true, L("Money back"), L("They get money back from what they paid.")),
                Triple(false, L("Credit note"), L("They owe less; nothing is handed back.")),
            ),
            selection = stateOf({ moneyBack }, { next ->
                if (next && bill.amountPaid == 0) return@stateOf
                if (!next && bill.balanceDueAmount == 0) return@stateOf
                moneyBack = next
                amount.value = if (next) bill.amountPaid else bill.balanceDueAmount
                seed += 1
            }),
        )
        key(seed) { MoneyField(label = L("Amount"), cents = amount, key = "amount", form = form) }
        if (moneyBack) {
            val methodLabel = L("Method")
            Box(Modifier.semantics { contentDescription = methodLabel }) {
                FlowLayout(spacing = 8.dp) {
                    CashText.methods.forEach { option ->
                        CashMethodPill(title = CashText.method(option), isOn = method == option) { method = option }
                    }
                }
            }
            CashRegisterGate(device = device, wantsCash = method == "cash", giving = true)
        }
        CashReasonField(kind = CashReasonKind.refund, text = reason, form = form)
    }
}

// MARK: - Email the receipt

@Serializable
private data class CashReceiptSent(val sentTo: String)

@Composable
fun CashSendReceiptSheet(bill: CashInvoice, clientEmail: String?, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val email = remember { mutableStateOf(clientEmail ?: "") }
    var working by remember { mutableStateOf(false) }

    fun send() {
        val to = email.value.trim()
        val valid = emailPattern.matches(to)
        if (!form.check(listOf("email" to (if (valid) null else L("That email doesn’t look right."))))) return
        working = true
        scope.launch {
            try {
                val sent: CashReceiptSent = API.post("/provider-invoices/${bill.id}/send-receipt", mapOf("email" to to, "save" to (clientEmail.nonEmpty == null && !CashBillRules.isWalkIn(bill))))
                Toast.show(LF("Receipt sent to %@.", sent.sentTo))
                dismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                form.apply(error, mapOf("email" to "email"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Email the receipt"), subtitle = bill.invoiceNumber, systemImage = "mail", accent = ModalAccent.aqua,
        isDirty = email.value != (clientEmail ?: ""), form = form, primaryTitle = L("Send"), isWorking = working, primaryIcon = "send",
        onPrimary = { send() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium)),
    ) {
        PMTextField(L("Email for the receipt"), text = email, key = "email", form = form, prompt = L("name@example.com"), keyboard = KeyboardType.Email)
        if (clientEmail.nonEmpty == null && !CashBillRules.isWalkIn(bill)) {
            Text(L("This client has no email: the one you write is saved on the client."), style = Theme.text(12), color = Theme.inkSoft)
        }
    }
}
