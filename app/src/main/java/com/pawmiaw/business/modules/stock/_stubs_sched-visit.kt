package com.pawmiaw.business.stock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

// Stand-ins for the Stock pieces the visit page (sched-visit) uses; deleted at merge.

// STUB: Modules/Stock/StockPharmacy.swift
@Suppress("UNUSED_PARAMETER")
class StockRxRights(w: Workspace, counts: StockPharmacyCounts? = null) {
    val canRead: Boolean get() = TODO("STUB: StockPharmacy.swift")
    val canWrite: Boolean get() = TODO("STUB: StockPharmacy.swift")
}

// STUB: Modules/Stock/StockPharmacyLists.swift
@Composable
fun StockPrescriptionRow(prescription: StockPrescription, modifier: Modifier = Modifier) {}

// STUB: Modules/Stock/StockPrescriptionForm.swift
data class StockRxPet(
    val clientId: String,
    val petId: String,
    val petName: String,
    val species: String? = null,
    val photoUrl: String? = null,
    val clientName: String? = null,
)

// STUB: Modules/Stock/StockPrescriptionSheet.swift
@Composable
fun StockPrescriptionSheet(
    pet: StockRxPet?,
    draft: StockPrescription?,
    appointmentId: String? = null,
    modifier: Modifier = Modifier,
    onDone: (StockPrescription) -> Unit,
) {}

// STUB: Modules/Stock/StockPrescriptionDetail.swift
@Composable
fun StockPrescriptionScreen(prescriptionId: String, modifier: Modifier = Modifier) {}
