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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

//
//  CashShared.kt (iOS CashShared.swift)
//
//  Pieces every money screen shares: payment methods and their words, the
//  reasons a risky move needs (docs/money-access-levels.md), the counter this
//  device works at, the payment step, PDFs, the client picker and small rows.
//

// MARK: - Words

object CashText {
    val methods: List<String> = listOf("cash", "card", "bank_transfer", "digital_wallet", "other")

    fun method(method: String?): String = when (method) {
        "cash" -> L("Cash")
        "card" -> L("Card")
        "bank_transfer" -> L("Transfer")
        "digital_wallet" -> L("Wallet")
        "credit" -> L("Credit on account")
        "other" -> L("Other@method")
        else -> method ?: "—"
    }

    fun methodHue(method: String?): Hue = when (method) {
        "cash" -> Hue.mint
        "card" -> Hue.sky
        "bank_transfer" -> Hue.periwinkle
        "digital_wallet" -> Hue.lavender
        "credit" -> Hue.butter
        else -> Hue.grey
    }

    /** Where money came from in Finance (`FinanceSource`, plus deposits on the day close). */
    fun source(source: String?): String = when (source) {
        "store" -> L("Store")
        "visit" -> L("Visits")
        "stay" -> L("Stays")
        "hospital" -> L("Hospital")
        "deposit" -> L("Deposits")
        else -> L("Other@source")
    }

    fun sourceHue(source: String?): Hue = when (source) {
        "visit" -> Hue.sky
        "stay" -> Hue.periwinkle
        "hospital" -> Hue.teal
        "store" -> Hue.peach
        "deposit" -> Hue.mint
        else -> Hue.grey
    }

    fun aging(bucket: String): String = when (bucket) {
        "current" -> L("Not due yet")
        "d1_30" -> L("1–30 days late")
        "d31_60" -> L("31–60 days late")
        else -> L("Over 60 days late")
    }

    val agingBuckets: List<String> = listOf("current", "d1_30", "d31_60", "d61_plus")

    fun daysLate(days: Int): String = LP(days, "%d day late", "%d days late")
}

/** Days as the server names them ("yyyy-MM-dd", the business's calendar). */
object CashDay {
    fun date(key: String): Instant? = DateText.parse(key)

    fun shift(key: String, days: Int): String {
        val day = runCatching { LocalDate.parse(key.take(10)) }.getOrNull() ?: return key
        return day.plusDays(days.toLong()).toString()
    }

    val today: String get() = DateText.dayKey()

    /** The first day the front desk still reads (the last 7 days, today included). */
    fun frontDeskFirst(today: String): String = shift(today, -(MoneyLevel.frontDeskDays - 1))

    /** "4 nov" / "mié 4 nov 2026". */
    fun short(key: String?): String {
        val date = key?.let { date(it) } ?: return "—"
        return DateText.format(date, "dMMM")
    }

    fun long(key: String?): String {
        val date = key?.let { date(it) } ?: return "—"
        return DateText.format(date, "EEEdMMMyyyy")
    }

    fun range(from: String, to: String): String =
        if (from == to) long(from) else "${short(from)} – ${long(to)}"

    /** A moment on a clock: "mié 4 nov, 9:02 a.m." or only the time. */
    fun `when`(iso: String?, withDay: Boolean = true, zone: ZoneId? = null): String {
        val date = DateText.parse(iso) ?: return "—"
        return DateText.format(date, if (withDay) "EEEdMMMjmm" else "jmm", zone)
    }
}

fun cashRequestId(): String = UUID.randomUUID().toString().lowercase()

/** "$1,234.50 MXN" in a document's own currency. */
fun cashMoney(cents: Int?, currency: String? = null): String = Money.format(cents, currency)

/** "16%" from basis points. */
fun cashRate(bps: Int): String {
    val percent = bps / 100.0
    return if (Math.rint(percent) == percent) "${percent.toInt()}%" else String.format(Locale.US, "%.2f%%", percent)
}

fun cashAsksReason(error: Throwable): Boolean = (error as? APIError)?.code == "reason_required"
fun cashErrorCode(error: Throwable): String? = (error as? APIError)?.code

// MARK: - Reasons (docs/money-access-levels.md)

/** Why a money move needs its reason: the shared "why" field's words. */
enum class CashReasonKind {
    discount, courtesy, priceLowered, `return`, cancel, refund, cashDifference;

    val label: String
        get() = when (this) {
            discount -> L("Why the discount")
            courtesy -> L("Why it is free")
            priceLowered -> L("Why the lower price")
            `return` -> L("Why it comes back")
            cancel -> L("Why it is canceled")
            refund -> L("Why the money goes back")
            cashDifference -> L("Why the drawer does not match")
        }

    val placeholder: String
        get() = when (this) {
            discount -> L("e.g. Rescue, a loyal client")
            courtesy -> L("e.g. Follow-up included, a staff pet")
            priceLowered -> L("e.g. Price agreed with the owner")
            `return` -> L("e.g. Wrong size, the pet did not like it")
            cancel -> L("e.g. Charged twice, wrong product")
            refund -> L("e.g. Charged twice, service not given")
            cashDifference -> L("e.g. Change given wrong, a payment not recorded")
        }

    val hint: String?
        get() = when (this) {
            discount -> L("It is 10% or more, so the owner sees why.")
            courtesy -> L("Given for free on purpose: the owner sees why.")
            priceLowered -> L("Below the catalog’s price: the owner sees why.")
            else -> null
        }

    /** The sentence when the field is left empty (the server says the same). */
    val required: String
        get() = when (this) {
            discount -> L("Write why this discount is given: it is 10% or more.")
            courtesy -> L("Write why this line is given for free.")
            priceLowered -> L("Write why the price is below the catalog’s.")
            `return` -> L("Write why it is coming back.")
            cancel -> L("Write why the sale is canceled.")
            refund -> L("Write why the money goes back.")
            cashDifference -> L("Write why the drawer does not match.")
        }
}

/** A discount of 10% or more of what it is taken from needs a reason. */
fun cashDiscountNeedsReason(discount: Int, of: Int): Boolean {
    if (discount <= 0 || of <= 0) return false
    return discount.toLong() * 100 >= of.toLong() * 10
}

/**
 * The one "why" field of every money move (web `ReasonField`): one line, the label with
 * its red mark (a required reason, as the web's default), the kind's hint under it.
 */
@Composable
fun CashReasonField(
    kind: CashReasonKind,
    text: MutableState<String>,
    key: String = "reason",
    form: FormState? = null,
    hint: String? = null,
    required: Boolean = true,
    modifier: Modifier = Modifier,
) {
    PMTextField(kind.label, text = text, key = key, form = form, prompt = kind.placeholder, hint = hint ?: kind.hint, required = required, modifier = modifier)
}

// MARK: - Small rows

/** A label and an amount on one line (totals, summaries). */
@Composable
fun CashTotalLine(label: String, value: String, strong: Boolean = false, tone: Color? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f).alignByBaseline(),
            style = if (strong) Theme.text(15, FontWeight.Bold) else Theme.smallFont,
            color = if (strong) Theme.inkStrong else Theme.inkSoft,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            modifier = Modifier.alignByBaseline(),
            style = (if (strong) Theme.text(17, FontWeight.ExtraBold) else Theme.text(15, FontWeight.SemiBold)).monospacedDigit(),
            color = tone ?: Theme.inkStrong,
        )
    }
}

/** An uppercase caption over a group. */
@Composable
fun CashCaption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = brandText(12, FontWeight.Bold).tracking(1.44), color = Theme.inkSoft)
}

/** A difference of a count: "Exact", "Short $X", "Over $X". */
@Composable
fun CashDifferenceText(difference: Int, currency: String?, modifier: Modifier = Modifier) {
    // Web `DifferenceLabel`: exact in blue, short in red, over in mango.
    when {
        difference == 0 -> Text(L("Exact"), modifier = modifier, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
        difference < 0 -> Text(LF("Short %@", cashMoney(-difference, currency)), modifier = modifier, style = Theme.text(14, FontWeight.Bold), color = Theme.destructive)
        else -> Text(LF("Over %@", cashMoney(difference, currency)), modifier = modifier, style = Theme.text(14, FontWeight.Bold), color = Theme.mango)
    }
}

// MARK: - The counter this device works at

/**
 * The register this device works at (chosen in the Store, remembered on the
 * device) and its open shift. Every place that takes money sends this shift;
 * cash with no shift is refused by the server.
 */
@Stable
class CashDeviceRegister {
    var registers: List<CashRegister> by mutableStateOf(emptyList())
        private set
    var session: CashSession? by mutableStateOf(null)
        private set
    var loaded: Boolean by mutableStateOf(false)
        private set
    var registerId: String? by mutableStateOf(AppContext.defaults.getString(key, null))
        private set

    private val scope = MainScope()

    val register: CashRegister? get() = registers.firstOrNull { it.id == registerId } ?: registers.firstOrNull()
    val sessionId: String? get() = register?.openSessionId
    /** The open shift was opened on an earlier day: it is counted and closed before today's cash. */
    val stale: Boolean get() = register?.openSession?.stale ?: session?.stale ?: false

    fun choose(id: String) {
        registerId = id
        AppContext.defaults.edit().putString(key, id).apply()
        changed.tryEmit(Unit)
        scope.launch { loadSession() }
    }

    suspend fun load() {
        val location = Workspace.shared.locationId
        registers = try {
            val listed: List<CashRegister> = API.get("/provider-store/registers", mapOf("locationId" to location))
            if (location != null) listed.filter { (it.locationId ?: location) == location } else listed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            emptyList()
        }
        registerId = AppContext.defaults.getString(key, null)
        loadSession()
        loaded = true
    }

    suspend fun loadSession() {
        val id = sessionId
        if (id == null) {
            session = null
            return
        }
        session = try {
            API.get<CashSession>("/provider-store/register-sessions/$id")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
    }

    companion object {
        private const val key = "pawmiaw.store.register"
        /** iOS `Notification.Name("pawmiaw.cash.register-changed")`: post with `tryEmit(Unit)`. */
        val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    }
}

/** Keeps a device register fresh: on appear, on scope changes, and when another screen changes it (iOS `CashKeepsRegister` modifier). */
@Composable
fun CashKeepsRegister(device: CashDeviceRegister) {
    ReloadsOnScope { device.load() }
    LaunchedEffect(device) {
        CashDeviceRegister.changed.collect { launch { device.load() } }
    }
}

/** Who may open (and close) a shift: whoever sells or takes payments, or runs the register. */
fun cashCanRunShift(w: Workspace): Boolean = w.canAny(Permission.salesSell, Permission.billingRecordPayment, Permission.registerManage)

/**
 * What stands between a person and taking (or giving back) cash: a shift
 * from an earlier day still open, or no shift open at this counter. It opens
 * the shift in place; the close of an old shift is in the Store's register.
 */
@Composable
fun CashRegisterGate(device: CashDeviceRegister, wantsCash: Boolean, giving: Boolean = false, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val openingCash = remember { mutableStateOf<Int?>(null) }
    var opening by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }

    fun open(register: CashRegister) {
        opening = true
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-store/registers/${register.id}/open", mapOf("openingCash" to (openingCash.value ?: 0), "note" to null))
                Toast.show(LF("%@ is open.", register.name))
                device.load()
                CashDeviceRegister.changed.tryEmit(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.error(failure, fallback = L("Could not open the register."))
            } finally {
                opening = false
            }
        }
    }

    val register = device.register ?: return
    val session = device.session
    if (device.stale && session != null) {
        val canRun = cashCanRunShift(workspace)
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            title = LF("%@ from %@ is still open — count it and close it.", register.name, CashDay.`when`(session.openedAt, zone = session.zone)),
            message = LF("Opened by %@. Today’s cash belongs in today’s shift.", session.openedByName ?: "—"),
            actionTitle = if (canRun) L("Count and close") else null,
            modifier = modifier,
            action = if (canRun) ({ closing = true }) else null,
        )
        if (closing) {
            AppSheet(onDismissRequest = { closing = false }) {
                CashCloseShiftSheet(session = session, onClosed = { scope.launch { device.load() } })
            }
        }
    } else if (wantsCash && device.sessionId == null) {
        if (cashCanRunShift(workspace)) {
            // Web `OpenShiftForm compact`: a mango 16% box, the sentence 12 bold, the amount and Open side by side.
            val shape = RoundedCornerShape(Theme.boxRadius)
            Column(
                modifier.fillMaxWidth().background(Theme.wash(Theme.mango, 16.0), shape).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (giving) LF("%@ is closed. Open it to give cash back:", register.name) else LF("%@ is closed. Open it to take cash:", register.name),
                    style = Theme.text(12, FontWeight.Bold),
                    color = Theme.inkStrong,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    key(seed) {
                        MoneyField(label = L("Starting cash"), cents = openingCash, modifier = Modifier.weight(1f))
                    }
                    PMButton(L("Open@verb"), systemImage = "lock-open", size = ButtonSize.compact, isWorking = opening, modifier = Modifier.padding(bottom = 4.dp)) { open(register) }
                }
            }
            LaunchedEffect(Unit) {
                openingCash.value = register.lastCashLeft ?: register.defaultOpeningCash
                seed += 1
            }
        } else {
            NoticeCard(
                tone = NoticeCard.Tone.info,
                title = if (giving) {
                    L("Cash back comes out of an open register, and it’s closed. Someone who sells or takes payments can open it — card and transfer work now.")
                } else {
                    L("Cash goes into an open register, and it’s closed. Someone who sells or takes payments can open it — card and transfer work now.")
                },
                modifier = modifier,
            )
        }
    }
}

// MARK: - The payment step

/**
 * The tenders being typed (cash, card, transfer, wallet, credit on account),
 * split across methods, the change to hand back and what stays owed. One way
 * to take money: the counter and a bill both use it. The server counts.
 * (iOS ObservableObject: the tenders are Compose state.)
 */
@Stable
class CashTenders {
    data class Tender(
        val id: UUID = UUID.randomUUID(),
        var method: String,
        var amount: Int? = null,
        var tendered: Int? = null,
        var reference: String = "",
        /** Bumped when a quick-cash chip fills "Handed over", so its field shows the new figure. */
        var revision: Int = 0,
    )

    var tenders: List<Tender> by mutableStateOf(emptyList())
    var requestId: String = cashRequestId()
        private set
    var total = 0
    var credit = 0
    var allowBalance = false
    var currency: String? = null

    fun start(total: Int, credit: Int, registerOpen: Boolean, allowBalance: Boolean, currency: String?) {
        this.total = total
        this.credit = credit
        this.allowBalance = allowBalance
        this.currency = currency
        requestId = cashRequestId()
        val fallback = if (registerOpen) "cash" else "card"
        val fromCredit = min(credit, total)
        tenders = if (fromCredit > 0) {
            buildList {
                add(Tender(method = "credit", amount = fromCredit))
                if (total > fromCredit) add(Tender(method = fallback, amount = total - fromCredit))
            }
        } else {
            listOf(Tender(method = fallback, amount = total))
        }
    }

    val methods: List<String> get() = CashText.methods + (if (credit > 0) listOf("credit") else emptyList())
    val paid: Int get() = tenders.sumOf { it.amount ?: 0 }
    val remaining: Int get() = total - paid
    val change: Int
        get() = tenders.fold(0) { sum, tender ->
            val handed = tender.tendered
            val amount = tender.amount
            if (tender.method != "cash" || handed == null || amount == null || handed <= amount) sum else sum + handed - amount
        }
    val wantsCash: Boolean get() = tenders.any { it.method == "cash" && (it.amount ?: 0) > 0 }
    private val creditUsed: Int get() = tenders.filter { it.method == "credit" }.sumOf { it.amount ?: 0 }

    fun problem(registerOpen: Boolean): String? {
        val shortCash = tenders.any { it.method == "cash" && it.tendered != null && (it.tendered ?: 0) < (it.amount ?: 0) }
        if (creditUsed > credit) return LF("They have %@ of credit", cashMoney(credit, currency))
        if (remaining < 0) return L("The payments add up to more than the sale")
        if (remaining > 0 && !allowBalance) return L("Collect the full amount — or choose a client to leave a balance")
        if (shortCash) return L("They handed over less than the cash amount")
        if (wantsCash && !registerOpen) return L("Open the register to take cash")
        return null
    }

    fun add() {
        val method = if (tenders.any { it.method == "card" }) "cash" else "card"
        tenders = tenders + Tender(method = method, amount = maxOf(0, remaining))
    }

    fun remove(id: UUID) {
        tenders = tenders.filterNot { it.id == id }
    }

    /** One tender changed in place (SwiftUI's `$tender` binding). */
    fun update(id: UUID, change: (Tender) -> Tender) {
        tenders = tenders.map { if (it.id == id) change(it.copy()) else it }
    }

    /** What the server takes: `[{ method, amount, tendered, reference }]`. */
    fun payments(): List<Map<String, Any?>> = tenders.mapNotNull { tender ->
        val amount = tender.amount
        if (amount == null || amount <= 0) return@mapNotNull null
        mapOf(
            "method" to tender.method,
            "amount" to amount,
            "tendered" to (if (tender.method == "cash") tender.tendered else null),
            "reference" to (if (tender.method == "credit") null else tender.reference.nonEmpty),
        )
    }

    companion object {
        /** Notes a customer is likely to hand over for this amount: exact, then round figures. */
        fun quickCash(amount: Int): List<Int> {
            if (amount <= 0) return emptyList()
            val options = mutableListOf(amount)
            for (step in listOf(5000, 10_000, 20_000, 50_000, 100_000)) {
                val rounded = ceil(amount.toDouble() / step.toDouble()).toInt() * step
                if (rounded > amount && rounded !in options) options.add(rounded)
                if (options.size >= 4) break
            }
            return options.sorted()
        }
    }
}

/**
 * The tenders, one box each, "add another payment" and the summary with the change
 * (web `PaymentTenders`, payment-step.tsx): method pills 44 tall, the amount and what
 * was handed over side by side, quick-cash pills, then the "Summary" part.
 */
@Composable
fun CashTendersView(state: CashTenders, registerOpen: Boolean, modifier: Modifier = Modifier) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (tender in state.tenders) {
            key(tender.id) {
                val id = tender.id
                Column(
                    Modifier.fillMaxWidth().background(Theme.surface, box).border(2.dp, Theme.borderSoft, box).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowLayout(spacing = 8.dp, modifier = Modifier.weight(1f)) {
                            for (method in state.methods) {
                                CashMethodPill(title = CashText.method(method), isOn = tender.method == method) {
                                    state.update(id) { it.apply { this.method = method } }
                                }
                            }
                        }
                        if (state.tenders.size > 1) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).plainButton(label = L("Remove payment")) { state.remove(id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Glyph("x", 16.dp, tint = Theme.inkSoft)
                            }
                        }
                    }
                    key("$id-${tender.method}-amount") {
                        MoneyField(
                            label = L("Amount"),
                            cents = stateOf({ state.tenders.firstOrNull { it.id == id }?.amount }, { value -> state.update(id) { it.apply { amount = value } } }),
                            caps = true,
                        )
                    }
                    when (tender.method) {
                        "credit" -> Text(LF("From their deposits: %@ available.", cashMoney(state.credit, state.currency)), style = Theme.text(14), color = Theme.inkSoft)
                        "cash" -> {
                            key("$id-tendered-${tender.revision}") {
                                MoneyField(
                                    label = L("Handed over"),
                                    cents = stateOf({ state.tenders.firstOrNull { it.id == id }?.tendered }, { value -> state.update(id) { it.apply { tendered = value } } }),
                                    caps = true,
                                )
                            }
                            val quick = CashTenders.quickCash(tender.amount ?: 0)
                            if (quick.isNotEmpty()) {
                                FlowLayout(spacing = 8.dp) {
                                    for (value in quick) {
                                        Box(
                                            Modifier
                                                .heightIn(min = 44.dp)
                                                .background(if (tender.tendered == value) Theme.wash(Theme.aqua, 30.0) else Theme.surfaceMuted, CapsuleShape)
                                                .clip(CapsuleShape)
                                                .plainButton { state.update(id) { it.apply { tendered = value; revision += 1 } } }
                                                .padding(horizontal = 16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(cashMoney(value, state.currency), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            PMTextField(
                                L("Terminal reference"),
                                text = stateOf({ state.tenders.firstOrNull { it.id == id }?.reference ?: "" }, { value -> state.update(id) { it.apply { reference = value } } }),
                                prompt = L("Optional"),
                                caps = true,
                            )
                        }
                    }
                }
            }
        }
        if (state.remaining > 0) {
            PMButton(LF("Add another payment (%@ left)", cashMoney(state.remaining, state.currency)), systemImage = "plus", variant = ButtonVariant.outline, fullWidth = true) { state.add() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("Summary"), style = brandHeading(16.8), color = Theme.inkStrong)
            Column(
                Modifier.fillMaxWidth().background(Theme.surfaceMuted, box).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TendersSummaryLine(L("Total"), cashMoney(state.total, state.currency), bold = true)
                TendersSummaryLine(L("Paid now"), cashMoney(state.paid, state.currency), bold = true)
                // The web draws what stays owed in ink-soft (payment-step.tsx).
                if (state.remaining > 0) TendersSummaryLine(L("Left on the account"), cashMoney(state.remaining, state.currency), ink = Theme.inkSoft)
                if (state.change > 0) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(L("Change"), modifier = Modifier.weight(1f), style = brandHeading(24), color = Theme.blue)
                        Spacer(Modifier.width(12.dp))
                        Text(cashMoney(state.change, state.currency), style = brandHeading(24).monospacedDigit(), color = Theme.blue)
                    }
                }
            }
            state.problem(registerOpen)?.let { problem ->
                Text(problem, style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive)
            }
        }
    }
}

@Composable
private fun TendersSummaryLine(label: String, value: String, bold: Boolean = false, ink: Color = Theme.inkStrong) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = Theme.text(14), color = ink)
        Spacer(Modifier.width(12.dp))
        Text(value, style = Theme.text(14, if (bold) FontWeight.Bold else FontWeight.Normal).monospacedDigit(), color = ink)
    }
}

/**
 * A payment method to choose (web: the payment step's method buttons): 44
 * tall for a counter, a 2 pt edge; chosen = PAW Blue filled.
 */
@Composable
fun CashMethodPill(title: String, isOn: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .background(if (isOn) Theme.blue else Color.Transparent, CapsuleShape)
            .border(2.dp, if (isOn) Theme.blue else Theme.borderSoft, CapsuleShape)
            .clip(CapsuleShape)
            .semantics { selected = isOn }
            .plainButton(onClick = action)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = Theme.text(14, FontWeight.Bold), color = if (isOn) Theme.onBlue else Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - PDFs

// MARK: - Who is it for

/** The pet's prescription a prescription-only product is sold against. */
@Composable
fun CashPrescriptionPicker(
    petId: String?,
    catalogItemId: String,
    selection: MutableState<String?>,
    quantity: Int = 1,
    /**
     * A cart line (nothing handed out yet) picks the only exact match by itself and drops a
     * choice that no longer fits; a bill line never does: this very bill may have used it up.
     */
    autoPick: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var items by remember { mutableStateOf<List<CashDispensable>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(petId) {
        if (petId == null) {
            items = emptyList()
            loaded = true
            return@LaunchedEffect
        }
        val list: CashDispensableList? = try {
            API.get("/provider-prescriptions/dispensable", mapOf("providerClientPetId" to petId, "catalogItemId" to catalogItemId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
        items = list?.items ?: emptyList()
        loaded = true
        if (!autoPick) return@LaunchedEffect
        val exact = items.filter { it.exactProduct == true }
        if (selection.value == null && exact.size == 1) selection.value = exact[0].prescriptionItemId
        // A choice that no longer fits (another pet, used up) is dropped.
        val chosen = selection.value
        if (chosen != null && list != null && items.none { it.prescriptionItemId == chosen }) selection.value = null
    }

    val chosenTitle: String = when {
        !loaded -> L("Loading prescriptions…")
        selection.value == null -> L("Choose the prescription")
        else -> items.firstOrNull { it.prescriptionItemId == selection.value }
            ?.let { item -> listOfNotNull(item.folio.nonEmpty, item.medicineName.nonEmpty).joinToString(" · ") }
            ?: L("The prescription it was dispensed against")
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (petId == null) {
            PrescriptionWarning(L("Needs a prescription — choose the client and the pet."))
        } else if (loaded && items.isEmpty() && selection.value == null) {
            PrescriptionWarning(L("No valid prescription for this pet — a vet prescribes it first."))
        } else {
            AppMenu(label = {
                // Web: a small NativeSelect (40, radius 16, grey edge, chevron at the right).
                val shape = RoundedCornerShape(Theme.fieldRadius)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Theme.inputFill, shape)
                        .border(1.dp, Theme.borderSoft, shape)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chosenTitle,
                        modifier = Modifier.weight(1f),
                        style = Theme.text(14),
                        color = if (selection.value == null) Theme.inkSoft else Theme.inkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
                }
            }) {
                item(L("Choose the prescription")) { selection.value = null }
                for (item in items) {
                    val title = listOfNotNull(item.folio.nonEmpty, item.medicineName.nonEmpty, item.remaining?.let { LF("%d left", it) }.nonEmpty, item.prescriberName.nonEmpty).joinToString(" · ")
                    item(title) { selection.value = item.prescriptionItemId }
                }
            }
            val chosen = items.firstOrNull { it.prescriptionItemId == selection.value }
            if (chosen != null) {
                // A pack sold against a line written for what it holds counts its size.
                val perSale = maxOf(1, chosen.unitsPerSale ?: 1)
                val left = chosen.remaining
                val valid = chosen.validUntil
                if (left != null && quantity * perSale > left) {
                    Text(
                        if (perSale > 1) LF("Only %d left on this prescription, and each one sold counts %d.", left, perSale) else LF("Only %d left on this prescription.", left),
                        style = Theme.text(12, FontWeight.SemiBold),
                        color = Theme.destructive,
                    )
                } else if (perSale > 1 && left != null) {
                    Text(LF("Each one sold counts %d of the %d left.", perSale, left), style = Theme.text(12), color = Theme.inkSoft)
                } else if (valid != null) {
                    Text(LF("Valid until %@", CashDay.long(valid.take(10))), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun PrescriptionWarning(text: String) {
    CompositionLocalProvider(LocalContentColor provides Theme.destructive) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("file-exclamation-point", 14.dp)
            Text(text, style = Theme.text(12, FontWeight.SemiBold), color = Theme.destructive)
        }
    }
}

/** The email a receipt goes to: the client's, or one typed here. */
@Composable
fun CashReceiptEmailField(
    clientEmail: String?,
    automatic: Boolean,
    email: MutableState<String>,
    send: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { send.value = automatic && clientEmail.nonEmpty != null }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow(
            title = L("Email the receipt"),
            subtitle = clientEmail.nonEmpty?.let { LF("To %@", it) } ?: L("Type an email to send it once."),
            isOn = send,
        )
        if (send.value && clientEmail.nonEmpty == null) {
            PMTextField(
                L("Email for the receipt"),
                text = email,
                prompt = L("name@example.com"),
                keyboard = KeyboardType.Email,
                capitalization = KeyboardCapitalization.None,
            )
        }
    }
}

/** The struct's static `delivery` (iOS `CashReceiptEmailField.delivery`). */
object CashReceiptEmailField {
    /** `receiptEmail` / `saveReceiptEmail` as the server takes them. */
    fun delivery(clientEmail: String?, typed: String, send: Boolean, canSave: Boolean): Map<String, Any?> {
        if (!send) return mapOf("receiptEmail" to null)
        clientEmail.nonEmpty?.let { return mapOf("receiptEmail" to it) }
        val written = typed.nonEmpty ?: return mapOf("receiptEmail" to null)
        return mapOf("receiptEmail" to written, "saveReceiptEmail" to canSave)
    }
}
