package com.pawmiaw.business.money

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID

//
//  Stand-ins for the Money module's shared pieces (iOS Modules/Money/CashShared.swift) that
//  the DepositSheet uses. Signatures only: deleted when CashShared.kt is ported.
//

// STUB: Modules/Money/CashShared.swift
object CashText {
    val methods: List<String> = listOf("cash", "card", "bank_transfer", "digital_wallet", "other")
    fun method(method: String?): String = method ?: "—"
}

// STUB: Modules/Money/CashShared.swift
fun cashRequestId(): String = UUID.randomUUID().toString().lowercase()

// STUB: Modules/Money/CashShared.swift (`changed`: iOS `Notification.Name`, posted with `tryEmit(Unit)`)
class CashDeviceRegister {
    val sessionId: String? get() = null
    suspend fun load() {}

    companion object {
        val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    }
}

// STUB: Modules/Money/CashShared.swift (the `CashKeepsRegister` view modifier: its reloads only)
@Composable
fun CashKeepsRegister(device: CashDeviceRegister) {}

// STUB: Modules/Money/CashShared.swift
@Composable
fun CashRegisterGate(device: CashDeviceRegister, wantsCash: Boolean, giving: Boolean = false, modifier: Modifier = Modifier) {}
