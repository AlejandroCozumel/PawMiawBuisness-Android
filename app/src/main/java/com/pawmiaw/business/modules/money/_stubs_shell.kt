package com.pawmiaw.business.money

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Money (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Money/FinanceScreen.swift
@Composable
fun FinanceScreen(modifier: Modifier = Modifier) = ShellStub("FinanceScreen", modifier)

// STUB: Modules/Money/BillingScreen.swift
@Composable
fun BillingScreen(modifier: Modifier = Modifier) = ShellStub("BillingScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
