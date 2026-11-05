package com.pawmiaw.business.records

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Records (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Records/RecordDetailScreen.swift
@Composable
fun RecordDetailScreen(recordId: String, modifier: Modifier = Modifier) = ShellStub("RecordDetailScreen", modifier)

// STUB: Modules/Records/RecordsNewRecordScreen.swift
@Composable
fun NewRecordScreen(prefill: RecordPrefill, modifier: Modifier = Modifier) = ShellStub("NewRecordScreen", modifier)

// STUB: Modules/Records/RecordsScreen.swift
@Composable
fun RecordsScreen(modifier: Modifier = Modifier) = ShellStub("RecordsScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
