package com.pawmiaw.business

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The one activity: the app is drawn in Compose (iOS `PawMiawNegociosApp`). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // A local design review signs in and opens a page from the launch extras (debug builds only).
        if (BuildConfig.DEBUG) DebugLaunch.read(intent?.extras)
        super.onCreate(savedInstanceState)
        PermissionRequests.register(this)
        val dark = ThemeMode.value == "dark"
        val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            CompositionLocalProvider(LocalSizeClass provides currentSizeClass()) {
                PawMiawNegociosApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

/**
 * Asks for runtime permissions from code with no screen of its own, at the moment they are
 * needed (iOS asks at first use too): the camera when the scanner opens, the location at a
 * home visit's first press. The activity registers it when it is created.
 */
object PermissionRequests {
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Map<String, Boolean>>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pending?.complete(result)
            pending = null
        }
    }

    /** The answer for each permission asked (false when refused or the question was closed); null with no screen to ask on. */
    suspend fun request(permissions: Array<String>): Map<String, Boolean>? {
        val launcher = launcher ?: return null
        pending?.complete(emptyMap())
        val answer = CompletableDeferred<Map<String, Boolean>>()
        pending = answer
        val launched = withContext(Dispatchers.Main) { runCatching { launcher.launch(permissions) }.isSuccess }
        if (!launched) {
            pending = null
            return null
        }
        return answer.await()
    }
}
