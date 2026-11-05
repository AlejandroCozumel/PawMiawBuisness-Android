package com.pawmiaw.business

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/** The one activity: the app is drawn in Compose (iOS `PawMiawNegociosApp`). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // A local design review signs in and opens a page from the launch extras (debug builds only).
        if (BuildConfig.DEBUG) DebugLaunch.read(intent?.extras)
        super.onCreate(savedInstanceState)
        val dark = ThemeMode.value == "dark"
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CompositionLocalProvider(LocalSizeClass provides currentSizeClass()) {
                RootView()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
