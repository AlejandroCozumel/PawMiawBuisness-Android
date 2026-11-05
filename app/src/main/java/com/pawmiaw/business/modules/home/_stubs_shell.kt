package com.pawmiaw.business.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for what the shell opens in Home (being ported in parallel). Deleted at merge:
//  each shows only its name.
//

// STUB: Modules/Home/HomeScreen.swift
@Composable
fun HomeScreen(modifier: Modifier = Modifier) = ShellStub("HomeScreen", modifier)

// STUB: Modules/Home/HomeScreen.swift
/** Whether the plan lets the business use its tools (stand-in: always). */
object HomeAccess {
    fun canUse(w: ProviderWorkspace): Boolean = true
    fun lockedTitle(w: ProviderWorkspace): String = ""
}

@Composable
private fun ShellStub(name: String, modifier: Modifier) {
    providerPage(name) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) } }
}
