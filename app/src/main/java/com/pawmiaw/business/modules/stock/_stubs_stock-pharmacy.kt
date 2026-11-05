package com.pawmiaw.business.stock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*

//
//  Stand-ins for the Stock symbols the Pharmacy uses that the `stock-rx` job ports
//  (Inventory, its sheets, the prescription document's words). Deleted at merge.
//

// STUB: Modules/Stock/StockInventory.swift
sealed class StockInventorySheet {
    data class receive(val row: StockRow?) : StockInventorySheet()
    data class count(val row: StockRow) : StockInventorySheet()
    data class waste(val row: StockRow, val lotId: String?) : StockInventorySheet()
    data class detail(val row: StockRow, val history: Boolean) : StockInventorySheet()
    data class settings(val row: StockRow) : StockInventorySheet()
}

// STUB: Modules/Stock/StockInventory.swift
@Composable
fun StockInventoryPage(
    initialFilter: StockFilter = StockFilter.all,
    initialMedicinesOnly: Boolean = false,
    root: Boolean = false,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Stock/StockInventory.swift
@Composable
fun StockIconButton(
    icon: String,
    label: String,
    size: Dp = 44.dp,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {}

// STUB: Modules/Stock/StockReceiveSheet.swift
@Composable
fun StockReceiveSheet(
    preset: StockRow?,
    severalLocations: Boolean = false,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
) {}

// STUB: Modules/Stock/StockAdjustSheets.swift
enum class StockAdjustKind { count, waste }

// STUB: Modules/Stock/StockAdjustSheets.swift
@Composable
fun StockAdjustSheet(
    row: StockRow,
    kind: StockAdjustKind,
    lotId: String?,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
) {}

// STUB: Modules/Stock/StockPrescriptionDocument.swift
object StockDocWords {
    fun species(value: String?, language: String): String? = TODO("STUB: StockPrescriptionDocument.swift")
}
