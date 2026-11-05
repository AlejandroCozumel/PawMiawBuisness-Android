package com.pawmiaw.business.stock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*

//
//  Stand-ins for the Stock symbols the stock-rx job (prescriptions, Inventory and its sheets)
//  calls and the stock-pharmacy job ports (StockShared, StockPharmacy, StockPharmacyLists,
//  StockPharmacyTodo, StockAllergyMatch). Signatures only: this file is deleted at merge.
//  `_stubs_clients-chart.kt` already stands in for part of StockPharmacy (StockRxTarget,
//  StockRxRights' canRead/canWrite, StockRxWords.state/stateState, StockControlledChip);
//  the members it lacks are extensions here, so nothing is declared twice.
//

// MARK: - StockShared.swift

// STUB: Modules/Stock/StockShared.swift
class StockRefusal(val api: APIError, val body: Map<String, JSONValue>) : Exception(api.message) {
    val code: String? get() = TODO()
    val status: Int get() = TODO()
}

// STUB: Modules/Stock/StockShared.swift
fun stockErrorText(error: Throwable, fallback: String = L("Something went wrong. Try again.")): String = TODO()

// STUB: Modules/Stock/StockShared.swift
fun stockIsConnectionError(error: Throwable): Boolean = TODO()

// STUB: Modules/Stock/StockShared.swift
object StockAPI {
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T = TODO()
    fun newRequestId(): String = TODO()
}

// STUB: Modules/Stock/StockShared.swift
enum class StockState { untracked, count, `out`, expired, low, inStock }

// STUB: Modules/Stock/StockShared.swift
enum class StockExpiry { none, expired, soon, ok }

// STUB: Modules/Stock/StockShared.swift
enum class StockFilter(val rawValue: String) { all("all"), low("low"), `out`("out"), expiring("expiring"), expired("expired") }

// STUB: Modules/Stock/StockShared.swift
enum class StockQuantityProblem { required, whole, negative, zero, invalid, tooLarge }

// STUB: Modules/Stock/StockShared.swift
object StockRules {
    val expiringDays: Int get() = TODO()
    val maxQuantity: Int get() = TODO()
    val maxCostCents: Int get() = TODO()
    val noteMax: Int get() = TODO()
    val lotCodeMax: Int get() = TODO()
    val defaultLowPoint: Int get() = TODO()
    val today: String get() = TODO()
    fun addDays(day: String, days: Int): String = TODO()
    fun daysBetween(from: String, to: String): Int = TODO()
    fun expiry(expiresOn: String?, today: String): StockExpiry = TODO()
    fun expiredUnits(lots: List<StockLot>, today: String): Int = TODO()
    fun hasExpiringLot(lots: List<StockLot>, today: String): Boolean = TODO()
    fun sellable(row: StockRow, today: String): Int = TODO()
    fun isOut(row: StockRow, today: String): Boolean = TODO()
    fun isLow(row: StockRow, today: String): Boolean = TODO()
    fun state(row: StockRow, today: String): StockState = TODO()
    fun matches(row: StockRow, filter: StockFilter, today: String): Boolean = TODO()
    fun isMedicine(row: StockRow): Boolean = TODO()
    fun requiresLotAndExpiry(row: StockRow): Boolean = TODO()
    fun parseQuantity(text: String, allowZero: Boolean = false, max: Int = maxQuantity): Pair<Int?, StockQuantityProblem?> = TODO()
    fun isLargeCountDifference(current: Int, counted: Int): Boolean = TODO()
    fun averageCostAfter(onHand: Int, currentCost: Int, received: Int, receivedCost: Int): Int = TODO()
    fun lotsWithCode(lots: List<StockLot>, code: String): List<StockLot> = TODO()
}

// STUB: Modules/Stock/StockShared.swift
object StockText {
    fun count(value: Int): String = TODO()
    fun units(value: Int): String = TODO()
    fun short(onHand: Int, withCount: Boolean = true): String = TODO()
    fun onHand(value: Int): String = TODO()
    fun day(value: String?): String = TODO()
    fun dateTime(value: String?): String = TODO()
    fun kind(kind: String?): String? = TODO()
    fun detail(row: StockRow): String = TODO()
    fun money(cents: Int?): String = TODO()
    fun signed(value: Int): String = TODO()
}

// STUB: Modules/Stock/StockShared.swift
val StockState.providerState: ProviderState get() = TODO()

// STUB: Modules/Stock/StockShared.swift
val StockState.tone: Color @Composable get() = TODO()

// STUB: Modules/Stock/StockShared.swift
val StockState.label: String get() = TODO()

// STUB: Modules/Stock/StockShared.swift
val StockExpiry.providerState: ProviderState get() = TODO()

// STUB: Modules/Stock/StockShared.swift
@Composable
fun stockStateChip(row: StockRow, today: String = StockRules.today, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockShared.swift
@Composable
fun stockLotChip(code: String?, quantity: Int, expiresOn: String?, today: String = StockRules.today, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockShared.swift
@Composable
fun StockProductThumb(photoUrl: String?, productKind: String?, size: Dp = 48.dp, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockShared.swift
@Composable
fun StockProductCard(row: StockRow, after: Int? = null, afterLabel: String? = null, today: String = StockRules.today, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockShared.swift
@Composable
fun StockMetaLine(parts: List<String?>, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockShared.swift
@Composable
fun StockDayControl(
    day: MutableState<String>,
    invalid: Boolean = false,
    placeholder: String = L("Choose the date"),
    modifier: Modifier = Modifier,
    onChange: () -> Unit = {},
) {}

// STUB: Modules/Stock/StockShared.swift
@Composable
fun StockReviewRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {}

// STUB: Modules/Stock/StockShared.swift
val stockPageSize: Int get() = TODO()

// MARK: - StockPharmacy.swift (the members `_stubs_clients-chart.kt` lacks)

// STUB: Modules/Stock/StockPharmacy.swift
val StockRxRights.canSign: Boolean get() = TODO()

// STUB: Modules/Stock/StockPharmacy.swift
val StockRxRights.canDispenseAtVisit: Boolean get() = TODO()

// STUB: Modules/Stock/StockPharmacy.swift
fun StockRxWords.schedule(schedule: String?): String? = TODO()

// STUB: Modules/Stock/StockPharmacy.swift
fun StockRxWords.block(block: String?): String? = TODO()

// STUB: Modules/Stock/StockPharmacy.swift
fun StockRxWords.error(error: Throwable, fallback: String = L("Something went wrong. Try again.")): String = TODO()

// STUB: Modules/Stock/StockPharmacy.swift
@Composable
fun StockRxStatePill(state: String, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockPharmacy.swift
@Composable
fun StockRefillPill(status: String, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockPharmacy.swift
@Composable
fun StockRxChip(text: String, tone: Color = Theme.inkSoft, icon: String? = null, strong: Boolean = false, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockPharmacy.swift
@Composable
fun StockRxControlledChip(label: String? = null, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockPharmacy.swift
@Composable
fun StockRxNumberField(
    label: String,
    text: MutableState<String>,
    key: String? = null,
    form: FormState? = null,
    prompt: String = "",
    hint: String? = null,
    optional: Boolean = false,
    suffix: String? = null,
    decimal: Boolean = false,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Stock/StockPharmacy.swift
@Composable
fun StockChartLink(clientId: String?, petId: String?, canViewClients: Boolean, modifier: Modifier = Modifier) {}

// MARK: - StockPharmacyLists.swift

// STUB: Modules/Stock/StockPharmacyLists.swift
data class StockCounselTarget(val dispenseId: String, val label: String, val petName: String?) {
    val id: String get() = TODO()
}

// STUB: Modules/Stock/StockPharmacyLists.swift
@Composable
fun StockCounselSheet(target: StockCounselTarget, onSaved: (() -> Unit)? = null, modifier: Modifier = Modifier) {}

// MARK: - StockPharmacyTodo.swift

// STUB: Modules/Stock/StockPharmacyTodo.swift
object StockHandOver {
    fun prefill(p: StockPrescription): StorePrefill? = TODO()
    fun route(prefill: StorePrefill?): ProviderRoute = TODO()
}

// STUB: Modules/Stock/StockPharmacyTodo.swift
@Composable
fun StockLogRefillSheet(prescription: StockPrescription?, onSaved: (() -> Unit)? = null, modifier: Modifier = Modifier) {}

// MARK: - StockAllergyMatch.swift

// STUB: Modules/Stock/StockAllergyMatch.swift
data class StockAllergySource(
    val trigger: String,
    val reaction: String? = null,
    val petName: String? = null,
    val recordId: String? = null,
)

// STUB: Modules/Stock/StockAllergyMatch.swift
data class StockAllergyConflict(val trigger: String, val reaction: String?)

// STUB: Modules/Stock/StockAllergyMatch.swift
object StockAllergyMatch {
    fun findConflicts(candidateName: String?, allergies: List<StockAllergySource>): List<StockAllergyConflict> = TODO()
}
