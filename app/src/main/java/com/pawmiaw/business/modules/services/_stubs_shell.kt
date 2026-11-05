package com.pawmiaw.business.services

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Services (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Services/HomeVisitsScreen.swift
@Composable
fun HomeVisitsScreen(modifier: Modifier = Modifier) = ShellStub("HomeVisitsScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
