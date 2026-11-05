package com.pawmiaw.business

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Placeholder until the shell is merged: the signed-in gate, the workspace gate, the shell. */
@Composable
fun RootView() {
    CompositionLocalProvider(LocalIsDark provides (ThemeMode.value == "dark")) {
        Box(Modifier.fillMaxSize().background(Theme.canvas), contentAlignment = Alignment.Center) {
            Text("PawMiaw Business", style = Theme.titleFont, color = Theme.inkStrong)
        }
    }
}
