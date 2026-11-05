package com.pawmiaw.business.stock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Stock (Pharmacy) symbols the patient chart uses (ported in wave 2).
//  Deleted at merge.
//

// STUB: Modules/Stock/StockPharmacy.swift
data class StockRxTarget(val id: String)

// STUB: Modules/Stock/StockPharmacy.swift
class StockRxRights(w: Workspace, counts: StockPharmacyCounts? = null) {
    val canRead: Boolean = TODO("STUB: StockPharmacy.swift")
    val canWrite: Boolean = TODO("STUB: StockPharmacy.swift")
}

// STUB: Modules/Stock/StockPharmacy.swift
object StockRxWords {
    fun state(state: String): String = TODO("STUB: StockPharmacy.swift")
    fun stateState(state: String): ProviderState = TODO("STUB: StockPharmacy.swift")
}

// STUB: Modules/Stock/StockPharmacy.swift
@Composable
fun StockControlledChip(label: String? = null, modifier: Modifier = Modifier) {}

