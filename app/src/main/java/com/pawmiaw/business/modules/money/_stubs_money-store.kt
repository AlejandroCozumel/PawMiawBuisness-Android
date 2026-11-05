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
//  Stand-ins for the Money pieces the money-store job calls and other jobs port
//  (money-finance: CashShared.swift; money-catalog: CashQuickEdit, CashStoreCheckout,
//  SaleDetailScreen, CashStoreSettings). Signatures only: this file is deleted at merge.
//  `_stubs_sheets.kt` already stands in for part of CashShared (CashText.methods/method,
//  cashRequestId, CashDeviceRegister.sessionId/load/changed, CashKeepsRegister,
//  CashRegisterGate); the members it lacks are extensions here, so nothing is declared twice.
//

// MARK: - CashShared.swift (money-finance)


























// MARK: - money-catalog

// STUB: Modules/Money/CashQuickEdit.swift
data class CashQuickEditSaved(
    val catalogItemId: String,
    val name: String,
    val unitAmount: Int,
    val taxTreatment: String,
    val taxRateBps: Int,
    val barcode: String?,
)

// STUB: Modules/Money/CashQuickEdit.swift
object CashQuickEdit {
    fun apply(saved: CashQuickEditSaved, to: CashProduct): CashProduct = TODO()
}

// STUB: Modules/Money/CashQuickEdit.swift
@Composable
fun CashQuickEditSheet(
    product: CashProduct,
    settings: CashStoreSettings,
    onSaved: (CashQuickEditSaved) -> Unit,
    onOpenCatalog: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Money/CashStoreCheckout.swift
data class CashAmbiguousCode(val code: String, val products: List<CashProduct>) {
    val id: UUID = UUID.randomUUID()
}

// STUB: Modules/Money/CashStoreCheckout.swift
@Composable
fun CashAmbiguousCodeSheet(found: CashAmbiguousCode, onChoose: (CashProduct) -> Unit, modifier: Modifier = Modifier) {}

// STUB: Modules/Money/CashStoreCheckout.swift
object CashHandOver {
    data class Loaded(val clientId: String, val petId: String, val lines: List<StorePrefill.Line>)

    suspend fun load(prescriptionId: String): Loaded = TODO()
}

// STUB: Modules/Money/SaleDetailScreen.swift
@Composable
fun CashReceiptView(sale: CashSale, footer: String? = null, modifier: Modifier = Modifier) {}

// STUB: Modules/Money/CashStoreSettings.swift
@Composable
fun CashStoreSettingsTab(settings: CashStoreSettings, onSaved: (CashStoreSettings) -> Unit, modifier: Modifier = Modifier) {}
