package com.pawmiaw.business.money

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

//
//  Stand-ins for the Money pieces the money-store job calls and another job ports
//  (money-finance: CashShared.swift). Signatures only: this file is deleted at merge.
//  `_stubs_sheets.kt` already stands in for part of CashShared (CashText.methods/method,
//  cashRequestId, CashDeviceRegister.sessionId/load/changed, CashKeepsRegister,
//  CashRegisterGate); the members it lacks are extensions here, so nothing is declared twice.
//

// MARK: - CashShared.swift (money-finance)

// STUB: Modules/Money/CashShared.swift
fun CashText.methodHue(method: String?): Hue = TODO()

// STUB: Modules/Money/CashShared.swift
fun CashText.daysLate(days: Int): String = TODO()

// STUB: Modules/Money/CashShared.swift
object CashDay {
    fun date(key: String): Instant? = TODO()
    fun shift(key: String, days: Int): String = TODO()
    val today: String get() = TODO()
    fun frontDeskFirst(today: String): String = TODO()
    fun short(key: String?): String = TODO()
    fun long(key: String?): String = TODO()
    fun range(from: String, to: String): String = TODO()
    fun `when`(iso: String?, withDay: Boolean = true, zone: ZoneId? = null): String = TODO()
}

// STUB: Modules/Money/CashShared.swift
fun cashMoney(cents: Int?, currency: String? = null): String = TODO()

// STUB: Modules/Money/CashShared.swift
fun cashRate(bps: Int): String = TODO()

// STUB: Modules/Money/CashShared.swift
fun cashAsksReason(error: Throwable): Boolean = TODO()

// STUB: Modules/Money/CashShared.swift
fun cashErrorCode(error: Throwable): String? = TODO()

// STUB: Modules/Money/CashShared.swift
enum class CashReasonKind {
    discount, courtesy, priceLowered, `return`, cancel, refund, cashDifference;

    val label: String get() = TODO()
    val placeholder: String get() = TODO()
    val hint: String? get() = TODO()
    val required: String get() = TODO()
}

// STUB: Modules/Money/CashShared.swift
fun cashDiscountNeedsReason(discount: Int, of: Int): Boolean = TODO()

// STUB: Modules/Money/CashShared.swift
@Composable
fun CashReasonField(
    kind: CashReasonKind,
    text: MutableState<String>,
    key: String = "reason",
    form: FormState? = null,
    hint: String? = null,
    required: Boolean = true,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Money/CashShared.swift
@Composable
fun CashDifferenceText(difference: Int, currency: String?, modifier: Modifier = Modifier) {}

// STUB: Modules/Money/CashShared.swift (CashDeviceRegister's members `_stubs_sheets.kt` lacks)
val CashDeviceRegister.registers: List<CashRegister> get() = TODO()

// STUB: Modules/Money/CashShared.swift
val CashDeviceRegister.session: CashSession? get() = TODO()

// STUB: Modules/Money/CashShared.swift
val CashDeviceRegister.loaded: Boolean get() = TODO()

// STUB: Modules/Money/CashShared.swift
val CashDeviceRegister.register: CashRegister? get() = TODO()

// STUB: Modules/Money/CashShared.swift
val CashDeviceRegister.stale: Boolean get() = TODO()

// STUB: Modules/Money/CashShared.swift
fun CashDeviceRegister.choose(id: String): Unit = TODO()

// STUB: Modules/Money/CashShared.swift
suspend fun CashDeviceRegister.loadSession(): Unit = TODO()

// STUB: Modules/Money/CashShared.swift
fun cashCanRunShift(w: Workspace): Boolean = TODO()

// STUB: Modules/Money/CashShared.swift (iOS ObservableObject: Compose state inside)
class CashTenders {
    data class Tender(
        val id: UUID = UUID.randomUUID(),
        var method: String,
        var amount: Int? = null,
        var tendered: Int? = null,
        var reference: String = "",
        var revision: Int = 0,
    )

    var tenders: List<Tender> by mutableStateOf(emptyList())
    var requestId: String = cashRequestId()
        private set
    var total = 0
    var credit = 0
    var allowBalance = false
    var currency: String? = null

    fun start(total: Int, credit: Int, registerOpen: Boolean, allowBalance: Boolean, currency: String?): Unit = TODO()
    val methods: List<String> get() = TODO()
    val paid: Int get() = TODO()
    val remaining: Int get() = TODO()
    val change: Int get() = TODO()
    val wantsCash: Boolean get() = TODO()
    fun problem(registerOpen: Boolean): String? = TODO()
    fun add(): Unit = TODO()
    fun remove(id: UUID): Unit = TODO()
    fun payments(): List<Map<String, Any?>> = TODO()

    companion object {
        fun quickCash(amount: Int): List<Int> = TODO()
    }
}

// STUB: Modules/Money/CashShared.swift
@Composable
fun CashTendersView(state: CashTenders, registerOpen: Boolean, modifier: Modifier = Modifier) {}

// STUB: Modules/Money/CashShared.swift
@Composable
fun CashMethodPill(title: String, isOn: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {}

// STUB: Modules/Money/CashShared.swift
@Composable
fun CashPrescriptionPicker(
    petId: String?,
    catalogItemId: String,
    selection: MutableState<String?>,
    quantity: Int = 1,
    autoPick: Boolean = true,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Money/CashShared.swift
@Composable
fun CashReceiptEmailField(
    clientEmail: String?,
    automatic: Boolean,
    email: MutableState<String>,
    send: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Money/CashShared.swift (the struct's static `delivery`)
object CashReceiptEmailField {
    fun delivery(clientEmail: String?, typed: String, send: Boolean, canSave: Boolean): Map<String, Any?> = TODO()
}
