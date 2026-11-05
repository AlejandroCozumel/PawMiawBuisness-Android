package com.pawmiaw.business.money

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import java.io.File
import java.time.ZoneId

//
//  Stand-ins for the Money pieces money-finance calls that other jobs port (money-store:
//  CashRegisterViews.swift; money-catalog: CashCSV.swift). Signatures only: this file is
//  deleted at merge.
//

// STUB: Modules/Money/CashRegisterViews.swift
@Composable
fun CashCloseShiftSheet(session: CashSession, modifier: Modifier = Modifier, onClosed: () -> Unit = {}) {}

// STUB: Modules/Money/CashRegisterViews.swift
@Composable
fun CashShiftDetail(session: CashSession, modifier: Modifier = Modifier) {}

// STUB: Modules/Money/CashCSV.swift
object CashCSV {
    fun cell(value: String?): String = TODO()
    fun money(cents: Int?): String = TODO()
    fun make(header: List<String>, rows: List<List<String?>>): String = TODO()
    fun fileName(what: String, from: String, to: String, ext: String = "csv"): String = TODO()
    fun dateTime(iso: String?, zone: ZoneId? = null): String = TODO()
    fun write(csv: String, name: String): File = TODO()
}

// STUB: Modules/Money/CashCSV.swift (`make`: the file's name and its text)
@Composable
fun CashCSVButton(
    title: String = L("Export CSV"),
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
    make: suspend () -> Pair<String, String>,
) {}
