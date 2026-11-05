package com.pawmiaw.business.hospital

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Hospital (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Hospital/HospitalDetailScreen.swift
@Composable
fun HospitalDetailScreen(hospitalizationId: String, modifier: Modifier = Modifier) = ShellStub("HospitalDetailScreen", modifier)

// STUB: Modules/Hospital/HospitalScreen.swift
@Composable
fun HospitalScreen(modifier: Modifier = Modifier) = ShellStub("HospitalScreen", modifier)

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
