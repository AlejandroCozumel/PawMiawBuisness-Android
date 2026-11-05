package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

//
//  StockShared.kt (iOS StockShared.swift)
//
//  What Inventory and the Pharmacy share: requests that keep the server's
//  whole refusal (a 409 `stock_changed` says what the shelf holds now; a
//  `credential_required` says which prescription waits), Inventory's rules
//  (web lib/inventory.ts) and the small pieces every stock screen draws (web
//  inventory-bits.tsx).
//

// MARK: - Requests

/** A refused request with the server's whole body, beside the usual `APIError`. */
class StockRefusal(val api: APIError, val body: Map<String, JSONValue>) : Exception(api.message) {
    val errorDescription: String? get() = api.message
    val code: String? get() = api.code
    val status: Int get() = api.status
}

/** The sentence for a failed action, whichever way it failed. */
fun stockErrorText(error: Throwable, fallback: String = L("Something went wrong. Try again.")): String {
    if (error is StockRefusal) return errorText(error.api, fallback)
    return errorText(error, fallback)
}

/** The request never reached the server (offline, timed out): nothing says whether it was kept. */
fun stockIsConnectionError(error: Throwable): Boolean {
    if (error is StockRefusal) return error.api.isOffline
    if (error is APIError) return error.isOffline
    return error !is CancellationException
}

object StockAPI {
    /**
     * A request whose refusal keeps the server's body (`StockRefusal`): `API` with the refusal's
     * `APIError.body` read into the stock screens' shape. Offline stays an `APIError`.
     */
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T {
        val data: ByteArray = try {
            API.data(method, path, query, body?.let { API.jsonBody(it) })
        } catch (error: APIError) {
            if (error.isOffline) throw error
            throw StockRefusal(error, error.body.obj ?: emptyMap())
        }
        return API.decode(data)
    }

    /** One id per press of Save: sent again (a retry), the server never writes twice. */
    fun newRequestId(): String = UUID.randomUUID().toString().lowercase()
}

// MARK: - Inventory rules (web lib/inventory.ts)

enum class StockState { untracked, count, out, expired, low, inStock }
enum class StockExpiry { none, expired, soon, ok }
enum class StockFilter(val rawValue: String) { all("all"), low("low"), `out`("out"), expiring("expiring"), expired("expired") }
enum class StockQuantityProblem { required, whole, negative, zero, invalid, tooLarge }

/** Foundation's `.whitespaces`: spaces and tabs, not line breaks. */
private fun String.trimmingWhitespaces(): String =
    trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

object StockRules {
    /** A lot with this many days left, or fewer, is "expiring soon". */
    const val expiringDays = 60
    /** The most one delivery, count or write-off can hold (the server's limit). */
    const val maxQuantity = 1_000_000
    /** The highest cost per unit, in cents. */
    const val maxCostCents = 1_000_000_000
    const val noteMax = 300
    const val lotCodeMax = 80
    /** The count a product is low at when none was set for it (the server's default). */
    const val defaultLowPoint = 3

    /** The business's day (its clock, as the server judges a lot expired). */
    val today: String get() = DateText.dayKey()

    fun addDays(day: String, days: Int): String {
        val date = runCatching { LocalDate.parse(day.take(10)) }.getOrNull() ?: return day
        return date.plusDays(days.toLong()).toString()
    }

    fun daysBetween(from: String, to: String): Int {
        val a = runCatching { LocalDate.parse(from.take(10)) }.getOrNull() ?: return 0
        val b = runCatching { LocalDate.parse(to.take(10)) }.getOrNull() ?: return 0
        return ChronoUnit.DAYS.between(a, b).toInt()
    }

    /** A lot expires at the end of its day: one dated today is still good today. */
    fun expiry(expiresOn: String?, today: String): StockExpiry {
        val raw = expiresOn.nonEmpty ?: return StockExpiry.none
        val day = raw.take(10)
        if (day < today) return StockExpiry.expired
        return if (day <= addDays(today, expiringDays)) StockExpiry.soon else StockExpiry.ok
    }

    fun expiredUnits(lots: List<StockLot>, today: String): Int =
        lots.fold(0) { sum, lot -> sum + (if (lot.quantityOnHand > 0 && expiry(lot.expiresOn, today) == StockExpiry.expired) lot.quantityOnHand else 0) }

    fun hasExpiringLot(lots: List<StockLot>, today: String): Boolean =
        lots.any { it.quantityOnHand > 0 && expiry(it.expiresOn, today) == StockExpiry.soon }

    /** What can still be sold: the server's `sellableUnits`, or worked out from the lots. */
    fun sellable(row: StockRow, today: String): Int =
        row.sellableUnits ?: (row.quantityOnHand - expiredUnits(row.lots, today))

    /** "Agotado": nothing left to sell. */
    fun isOut(row: StockRow, today: String): Boolean =
        row.outOfStock ?: (sellable(row, today) <= 0)

    /** "Poco inventario": the server's `lowStock` while its alert is on, never while out. */
    fun isLow(row: StockRow, today: String): Boolean =
        row.lowStock && row.lowStockAlert != false && !isOut(row, today)

    fun state(row: StockRow, today: String): StockState {
        if (row.tracked == false) return StockState.untracked
        if (row.quantityOnHand < 0) return StockState.count
        if (row.quantityOnHand == 0) return StockState.out
        if (isOut(row, today)) return StockState.expired
        if (isLow(row, today)) return StockState.low
        return StockState.inStock
    }

    fun matches(row: StockRow, filter: StockFilter, today: String): Boolean = when (filter) {
        StockFilter.all -> true
        StockFilter.out -> isOut(row, today)
        StockFilter.low -> isLow(row, today)
        StockFilter.expiring -> hasExpiringLot(row.lots, today)
        StockFilter.expired -> expiredUnits(row.lots, today) > 0
    }

    /** A medicine: its kind says so, or it needs a prescription, or it is controlled. */
    fun isMedicine(row: StockRow): Boolean =
        row.productKind == "medication" || row.requiresPrescription == true || (row.controlSchedule.nonEmpty != null)

    fun requiresLotAndExpiry(row: StockRow): Boolean = row.controlSchedule.nonEmpty != null

    private val decimalPattern = Regex("^\\d+[.,]\\d+$")
    private val wholePattern = Regex("^\\d+$")

    /** A quantity typed by a person, in whole units: (value, problem). */
    fun parseQuantity(text: String, allowZero: Boolean = false, max: Int = maxQuantity): Pair<Int?, StockQuantityProblem?> {
        val cleaned = text.trimmingWhitespaces().replace(" ", "")
        if (cleaned.isEmpty()) return Pair(null, StockQuantityProblem.required)
        if (cleaned.startsWith("-") && cleaned.drop(1).firstOrNull()?.isDigit() == true) return Pair(null, StockQuantityProblem.negative)
        if (decimalPattern.matches(cleaned)) return Pair(null, StockQuantityProblem.whole)
        if (!wholePattern.matches(cleaned)) return Pair(null, StockQuantityProblem.invalid)
        val value = cleaned.toIntOrNull()
        if (value == null || value > max) return Pair(null, StockQuantityProblem.tooLarge)
        if (value == 0 && !allowZero) return Pair(null, StockQuantityProblem.zero)
        return Pair(value, null)
    }

    /** A count far from what the system has needs a word about why. */
    fun isLargeCountDifference(current: Int, counted: Int): Boolean {
        val difference = abs(counted - current)
        if (difference >= 10) return true
        return difference >= 3 && difference.toDouble() >= abs(current).toDouble() * 0.2
    }

    fun averageCostAfter(onHand: Int, currentCost: Int, received: Int, receivedCost: Int): Int {
        val existing = maxOf(0, onHand)
        if (received <= 0) return currentCost
        if (existing == 0) return receivedCost
        // In Double: units × cents passes Int's range (Swift's Int is 64-bit).
        return Math.round((existing.toDouble() * currentCost + received.toDouble() * receivedCost) / (existing + received).toDouble()).toInt()
    }

    fun lotsWithCode(lots: List<StockLot>, code: String): List<StockLot> {
        val wanted = code.trimmingWhitespaces().lowercase()
        if (wanted.isEmpty()) return emptyList()
        return lots.filter { it.quantityOnHand > 0 && (it.lotCode ?: "").trimmingWhitespaces().lowercase() == wanted }
    }
}

// MARK: - Words

object StockText {
    fun count(value: Int): String = NumberFormat.getNumberInstance(appLocale).format(value.toLong())

    /** "1 unit" / "24 units". */
    fun units(value: Int): String =
        if (abs(value) == 1) L("1 unit") else LF("%@ units", count(value))

    /** A shelf below zero is never shown as stock: "Faltan 2 — necesita un conteo". */
    fun short(onHand: Int, withCount: Boolean = true): String {
        val n = count(abs(onHand))
        if (!withCount) return if (abs(onHand) == 1) L("1 short") else LF("%@ short", n)
        return if (abs(onHand) == 1) L("1 short — it needs a count") else LF("%@ short — it needs a count", n)
    }

    fun onHand(value: Int): String = if (value < 0) short(value, withCount = false) else count(value)

    /** "4 nov 2026" for a "yyyy-MM-dd" day, the same day wherever the reader is. */
    fun day(value: String?): String {
        val raw = value.nonEmpty ?: return "—"
        if (raw.length == 10) {
            val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return raw
            return DateText.date(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        }
        return DateText.date(DateText.parse(raw))
    }

    /** "4 nov 2026, 10:30 a.m." */
    fun dateTime(value: String?): String {
        val date: Instant = DateText.parse(value) ?: return "—"
        return "${DateText.date(date)}, ${DateText.time(date)}"
    }

    fun kind(kind: String?): String? = when (kind) {
        "medication" -> L("Medication")
        "food" -> L("Food")
        "supply" -> L("Supply")
        "accessory" -> L("Accessory")
        "other" -> L("Other")
        else -> null
    }

    /** Kind · SKU · barcode. */
    fun detail(row: StockRow): String =
        listOf(kind(row.productKind), row.sku, row.barcode).mapNotNull { it.nonEmpty }.joinToString(" · ")

    fun money(cents: Int?): String = Money.format(cents)

    fun signed(value: Int): String = if (value > 0) "+${count(value)}" else "−${count(-value)}"
}

// MARK: - Pieces (web inventory-bits.tsx, pharmacy-bits.tsx, catalog-bits.tsx)
//
// Every control is the kit's (ToneChip, Chip, NoticeCard, ListCard, FormField with
// `caps`, PMButton `.ghost` / `.link`); what is here maps Stock's data onto them.

val StockState.providerState: ProviderState
    get() = when (this) {
        StockState.inStock -> ProviderState.done
        StockState.low -> ProviderState.waiting
        StockState.out, StockState.count, StockState.expired -> ProviderState.problem
        StockState.untracked -> ProviderState.closed
    }

/** The pill's colour: the status colours, expired a maroon deeper than "out". */
val StockState.tone: Color
    @Composable get() = if (this == StockState.expired) ChipTone.expired.color else providerState.color

val StockState.label: String
    get() = when (this) {
        StockState.inStock -> L("In stock")
        StockState.low -> L("Low stock")
        StockState.out -> L("Out of stock")
        StockState.expired -> L("All expired")
        StockState.count -> L("Count needed")
        StockState.untracked -> L("Not counted yet")
    }

val StockExpiry.providerState: ProviderState
    get() = when (this) {
        StockExpiry.expired -> ProviderState.problem
        StockExpiry.soon -> ProviderState.waiting
        StockExpiry.ok, StockExpiry.none -> ProviderState.closed
    }

/** A product's stock state as the kit's pill (web `StockStatePill`: `TonePill`, the tone at 22%, its dot). */
@Composable
fun stockStateChip(row: StockRow, today: String = StockRules.today, modifier: Modifier = Modifier) {
    val state = StockRules.state(row, today)
    Chip(state.label, color = state.tone, percent = 22.0, dot = true, modifier = modifier)
}

/** A lot: its number, what is left and when it expires — red once expired, amber when close. */
@Composable
fun stockLotChip(code: String?, quantity: Int, expiresOn: String?, today: String = StockRules.today, modifier: Modifier = Modifier) {
    val state = StockRules.expiry(expiresOn, today)
    var text = "${code.nonEmpty ?: L("No lot")} · ${StockText.count(quantity)}"
    expiresOn.nonEmpty?.let { expires ->
        text += " · ${if (state == StockExpiry.expired) L("expired") else L("exp.")} ${StockText.day(expires)}"
    }
    Chip(text, color = state.providerState.color, percent = 22.0, dot = true, modifier = modifier)
}

/** The product's photo, or its kind's icon on the Catalog's product colour (peach). */
@Composable
fun StockProductThumb(photoUrl: String?, productKind: String?, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val icon = when (productKind) {
        "medication" -> "pill"
        "food" -> "paw-print"
        "supply" -> "boxes"
        "accessory" -> "shopping-bag"
        "other" -> "sparkles"
        else -> "package"
    }
    val shape = RoundedCornerShape(Theme.chipRadius)
    Box(
        modifier.size(size).clip(shape).background(Theme.wash(Hue.peach.color, 24.0), shape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, 20.dp, tint = Theme.mix(Theme.mango, 70.0, Theme.inkStrong))
        APIConfig.assetURL(photoUrl)?.let { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * The product a sheet is about (web `ProductStockCard`): photo, name, its state, what is on the
 * shelf — and what will be once saved.
 */
@Composable
fun StockProductCard(
    row: StockRow,
    after: Int? = null,
    afterLabel: String? = null,
    today: String = StockRules.today,
    modifier: Modifier = Modifier,
) {
    val changes = after != null && after != row.quantityOnHand
    val detail = StockText.detail(row)
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(2.dp, Theme.borderSoft, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockProductThumb(photoUrl = row.photoUrl, productKind = row.productKind, size = 56.dp)
        Column(Modifier.weight(1f)) {
            Text(row.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 4.dp)) {
                stockStateChip(row, today)
                if (row.reorderPoint > 0 && row.lowStockAlert != false) {
                    Text(LF("Reorder at %@", StockText.count(row.reorderPoint)), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val quiet = changes || row.quantityOnHand < 0
                Text(
                    StockText.onHand(row.quantityOnHand),
                    style = Theme.heading(if (quiet) 18 else 24).monospacedDigit(),
                    color = if (quiet) Theme.inkSoft else Theme.inkStrong,
                )
                if (changes && after != null) {
                    Glyph("arrow-right", 16.dp, tint = Theme.inkSoft)
                    Text(StockText.onHand(after), style = Theme.heading(24).monospacedDigit(), color = Theme.inkStrong)
                }
            }
            Text(
                if (changes) (afterLabel ?: L("on hand after")) else if (row.quantityOnHand < 0) L("it needs a count") else L("on hand"),
                style = Theme.text(12), color = Theme.inkSoft, textAlign = TextAlign.End,
            )
        }
    }
}

/** A small line of facts under a row ("Reorder at 3 · 2 expired"). */
@Composable
fun StockMetaLine(parts: List<String?>, modifier: Modifier = Modifier) {
    val text = parts.mapNotNull { it.nonEmpty }.joinToString(" · ")
    if (text.isNotEmpty()) {
        Text(text, style = Theme.text(12), color = Theme.inkSoft, modifier = modifier)
    }
}

/**
 * A day typed in those modals (web `DatePickerField`, 48 tall, the calendar at the right): empty
 * until chosen. The kit's DayField carries the sentence-case label; this one sits in a `FormField(caps: true)`.
 */
@Composable
fun StockDayControl(
    day: MutableState<String>,
    invalid: Boolean = false,
    placeholder: String = L("Choose the date"),
    modifier: Modifier = Modifier,
    onChange: () -> Unit = {},
) {
    val utc = ZoneOffset.UTC
    FieldChrome(invalid = invalid, modifier = modifier) {
        if (day.value.isEmpty()) {
            Row(
                Modifier.weight(1f).plainButton {
                    day.value = StockRules.today
                    onChange()
                },
            ) {
                Text(placeholder, style = Theme.text(14), color = Theme.inkSoft)
            }
        } else {
            CompactDatePicker(
                selection = stateOf(
                    { runCatching { LocalDate.parse(day.value).atStartOfDay(utc).toInstant() }.getOrNull() ?: Instant.now() },
                    { picked ->
                        day.value = picked.atZone(utc).toLocalDate().toString()
                        onChange()
                    },
                ),
                components = setOf(DatePickerComponent.date),
                zone = utc,
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(12.dp))
        Glyph("calendar-days", 16.dp, tint = Theme.inkSoft)
    }
}

/** One line of a review box: what, then its value at the right (web `ReviewRow`). */
@Composable
fun StockReviewRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = Theme.text(14), color = Theme.inkSoft)
        // HStack(spacing: 16) around a Spacer(minLength: 8): at least 40 between them.
        Spacer(Modifier.width(40.dp))
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CompositionLocalProvider(
                LocalContentColor provides Theme.inkStrong,
                LocalTextStyle provides Theme.text(14).copy(textAlign = TextAlign.End),
            ) { value() }
        }
    }
}

/** How many rows a list shows at first, and adds as it is scrolled. */
const val stockPageSize = 20
