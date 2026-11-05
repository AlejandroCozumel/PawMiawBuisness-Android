package com.pawmiaw.business.stays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Stays (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Stays/StayDetailScreen.swift
@Composable
fun StayDetailScreen(stayId: String, modifier: Modifier = Modifier) = ShellStub("StayDetailScreen", modifier)

// STUB: Modules/Stays/StaysScreen.swift
@Composable
fun StaysScreen(modifier: Modifier = Modifier) = ShellStub("StaysScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
