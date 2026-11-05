package com.pawmiaw.business

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.pawmiaw.business.home.HomeAccess
import kotlinx.coroutines.launch

//
//  PawMiaw Negocios: the business workspace (the web's /provider) as a native app, against
//  the same backend (iOS PawMiawNegociosApp.swift). `PawMiawNegociosApp` is the window's
//  content: the light/dark choice, the language (a change redraws everything), the toast;
//  `RootView` signs in, `WorkspaceGate` picks the business, then the shell.
//

/**
 * As on the web: light unless the person chose dark (the account menu); it doesn't follow the
 * system. Compose state over `ThemeMode` (iOS `@AppStorage("pawmiaw.negocios.theme")`).
 */
object ThemeChoice {
    var theme: String by mutableStateOf(ThemeMode.value)
        private set

    fun set(value: String) {
        ThemeMode.value = value
        theme = value
    }
}

/** The window's content (iOS `PawMiawNegociosApp`'s scene). */
@Composable
fun PawMiawNegociosApp() {
    val dark = ThemeChoice.theme == "dark"
    SystemBarsFollow(dark)
    CompositionLocalProvider(LocalIsDark provides dark) {
        // iOS `.tint(Theme.blue)`: the system controls (spinners, selection, the cursor) in PAW Blue.
        MaterialTheme(colorScheme = brandColorScheme(dark)) {
            // The toast banner sits over everything (Toast.kt).
            Box(Modifier.fillMaxSize().background(Theme.canvas).toastHost()) {
                // iOS `.id(localization.currentLanguage)`: a new language redraws every screen.
                key(LocalizationManager.currentLanguage) {
                    RootView()
                }
            }
        }
    }
}

private fun brandColorScheme(dark: Boolean): ColorScheme {
    fun c(pair: SchemeColor) = pair.resolve(dark)
    return if (dark) darkColorScheme(
        primary = c(Theme.bluePair), onPrimary = c(Theme.onBluePair),
        background = c(Theme.canvasPair), onBackground = c(Theme.inkStrongPair),
        surface = c(Theme.surfacePair), onSurface = c(Theme.inkStrongPair),
        surfaceVariant = c(Theme.surfaceMutedPair), onSurfaceVariant = c(Theme.inkSoftPair),
        surfaceContainer = c(Theme.surfacePair), surfaceContainerHigh = c(Theme.surfacePair), surfaceContainerLow = c(Theme.surfacePair),
        outline = c(Theme.borderStrongPair), outlineVariant = c(Theme.borderSoftPair),
        error = c(Theme.destructivePair),
    ) else lightColorScheme(
        primary = c(Theme.bluePair), onPrimary = c(Theme.onBluePair),
        background = c(Theme.canvasPair), onBackground = c(Theme.inkStrongPair),
        surface = c(Theme.surfacePair), onSurface = c(Theme.inkStrongPair),
        surfaceVariant = c(Theme.surfaceMutedPair), onSurfaceVariant = c(Theme.inkSoftPair),
        surfaceContainer = c(Theme.surfacePair), surfaceContainerHigh = c(Theme.surfacePair), surfaceContainerLow = c(Theme.surfacePair),
        outline = c(Theme.borderStrongPair), outlineVariant = c(Theme.borderSoftPair),
        error = c(Theme.destructivePair),
    )
}

/** The status and navigation bars' icons follow the app's light/dark choice (iOS `.preferredColorScheme`). */
@Composable
private fun SystemBarsFollow(dark: Boolean) {
    val view = LocalView.current
    LaunchedEffect(dark) {
        val activity = view.context.findActivity() as? ComponentActivity ?: return@LaunchedEffect
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Signed out, checking, or signed in. */
@Composable
fun RootView() {
    val session = SessionStore.shared
    val workspace = Workspace.shared
    val phase = session.phase
    val scope = rememberCoroutineScope()

    when (phase) {
        SessionStore.Phase.checking -> SplashView()
        SessionStore.Phase.signedOut -> SignInView()
        is SessionStore.Phase.signedIn -> WorkspaceGate()
    }

    LaunchedEffect(Unit) { session.revalidate() }
    // Back in front after a while: the session may have ended elsewhere (signed out on the web).
    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (first) first = false else scope.launch { session.revalidate() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // iOS `.onChange(of: session.phase)`: signing out forgets the business.
    var seenFirst by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (!seenFirst) {
            seenFirst = true
            return@LaunchedEffect
        }
        if (phase == SessionStore.Phase.signedOut) workspace.reset()
    }
}

@Composable
fun SplashView(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Theme.canvas),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.business_mark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
        )
        CircularProgressIndicator(Modifier.size(20.dp), color = Theme.blue, strokeWidth = 2.dp)
    }
}

/** Signed in: which business, then the workspace. */
@Composable
fun WorkspaceGate(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val session = SessionStore.shared
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxSize()) {
        when (val phase = workspace.phase) {
            Workspace.Phase.idle, Workspace.Phase.loading -> SplashView()
            Workspace.Phase.none -> NoWorkspaceView()
            Workspace.Phase.choosing -> WorkspaceChooserView()
            is Workspace.Phase.failed -> Column(
                Modifier.fillMaxSize().background(Theme.canvas).statusBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ErrorState(message = phase.message, retry = { workspace.load() })
                PMButton(L("Sign out"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { session.signOut() } }
            }
            Workspace.Phase.ready -> {
                val current = workspace.current
                if (current != null && (current.setupCompletedAt == null || !HomeAccess.canUse(current))) {
                    // As on the web: a business whose setup or plan isn't done opens no tools, only what to do next.
                    WorkspaceLockedView(workspace = current)
                } else {
                    AppShell()
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (workspace.phase == Workspace.Phase.idle) workspace.load() }
}

/**
 * The whole workspace while its setup or plan isn't done (web: the shell shows
 * `ProviderActivationPanel` in place of every page). Plans and setup are on the web.
 */
@Composable
fun WorkspaceLockedView(workspace: ProviderWorkspace, modifier: Modifier = Modifier) {
    val store = Workspace.shared
    val session = SessionStore.shared
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    val needsSetup = workspace.setupCompletedAt == null

    Box(modifier.fillMaxSize().background(Theme.canvas).statusBarsPadding()) {
        PageScroll(refresh = { store.load() }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val painter = painterResource(R.drawable.wordmark)
                val size = painter.intrinsicSize
                val ratio = if (size.isSpecified && size.height > 0f) size.width / size.height else 4f
                Image(painter, contentDescription = "PawMiaw", contentScale = ContentScale.Fit, modifier = Modifier.height(28.dp).aspectRatio(ratio))
            }
            Card(padding = 20.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon = if (needsSetup) "wrench" else "lock", accent = Theme.blue, size = 44.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (needsSetup) L("Finish setting up your workspace") else HomeAccess.lockedTitle(workspace),
                            style = Theme.heading(22),
                            color = Theme.inkStrong,
                        )
                        Text(workspace.name, style = Theme.text(14, FontWeight.Medium), color = Theme.inkSoft)
                    }
                }
                Text(
                    if (needsSetup) L("Your business details, hours and services are set up on the web. Home opens here once they are done.")
                    else L("Provider tools are paused until billing is connected. Plans are chosen on the web."),
                    style = Theme.text(15),
                    color = Theme.inkSoft,
                )
                PMButton(
                    if (needsSetup) L("Open setup on the web") else L("Open the plan on the web"),
                    systemImage = "external-link",
                    fullWidth = true,
                ) { openURL(APIConfig.webURL(if (needsSetup) "provider" else "provider/billing").toString()) }
                PMButton(L("I already did: check again"), variant = ButtonVariant.outline, fullWidth = true) { scope.launch { store.load() } }
            }
            if (store.workspaces.size > 1) {
                PMButton(L("Choose another business"), systemImage = "arrow-left-right", variant = ButtonVariant.outline, fullWidth = true) { store.leaveToChooser() }
            }
            PMButton(L("Sign out"), systemImage = "log-out", variant = ButtonVariant.outline, fullWidth = true) { scope.launch { session.signOut() } }
        }
    }
}
