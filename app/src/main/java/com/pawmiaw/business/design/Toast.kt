package com.pawmiaw.business

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

//
//  One toast at a time, over everything (sheets included): `Toast.show("Saved.")`,
//  `Toast.error("…")` (iOS Toast.swift). The root view hosts it once with
//  `Modifier.toastHost()`. It is the app's own banner (never an Android Toast): the web's
//  sonner look at the top, 2.6 s (4 s for an error), a tap closes it.
//
//  iOS shows it in a window of its own above the alert level so it sits over sheets. Here a
//  sheet is a dialog window, so the toast is a small window of its own too, added again
//  (on top) each time a toast shows; it takes no focus and only the toast's own strip takes
//  touches.
//

class ToastCenter private constructor() {
    data class Item(val id: UUID = UUID.randomUUID(), val text: String, val isError: Boolean)

    var current: Item? by mutableStateOf(null)

    private val scope = MainScope()
    private var hideTask: Job? = null

    fun show(text: String, isError: Boolean = false) {
        scope.launch(Dispatchers.Main.immediate) {
            current = Item(text = text, isError = isError)
            hideTask?.cancel()
            hideTask = scope.launch {
                delay(if (isError) 4_000 else 2_600)
                current = null
            }
        }
    }

    companion object {
        val shared = ToastCenter()
    }
}

object Toast {
    fun show(text: String) = ToastCenter.shared.show(text)
    fun error(text: String) = ToastCenter.shared.show(text, isError = true)

    /** The failed action's sentence (the server's, when it refused). */
    fun error(error: Throwable, fallback: String = L("Something went wrong. Try again.")) =
        ToastCenter.shared.show(errorText(error, fallback), isError = true)
}

/** Hosts the toast (iOS `.toastHost()`): once, on the root view. */
fun Modifier.toastHost(): Modifier = composed {
    val view = LocalView.current
    val dark = LocalIsDark.current
    val host = remember(view) { ToastWindow(view) }
    SideEffect { host.dark = dark }
    DisposableEffect(host) { onDispose { host.remove() } }
    LaunchedEffect(host) {
        snapshotFlow { ToastCenter.shared.current?.id }.collect { id -> if (id != null) host.showOnTop() }
    }
    this
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The toast's own window, over the app's window and any sheet open when it shows. */
private class ToastWindow(private val anchor: View) {
    var dark by mutableStateOf(false)
    private var view: ComposeView? = null

    private val activity: Activity? get() = anchor.context.findActivity()

    fun showOnTop() {
        val activity = activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val manager = activity.windowManager
        // Added again so it sits above a sheet opened since the last toast.
        view?.let { runCatching { manager.removeViewImmediate(it) } }
        val compose = ComposeView(activity).apply {
            (activity as? LifecycleOwner)?.let { setViewTreeLifecycleOwner(it) }
            (activity as? SavedStateRegistryOwner)?.let { setViewTreeSavedStateRegistryOwner(it) }
        }
        // Gone once its toast has left; posted, so the window isn't taken down from inside its own
        // composition, and only if no newer toast window replaced it meanwhile.
        compose.setContent { ToastOverlay(dark = dark, onGone = { compose.post { if (view === compose) remove() } }) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            token = activity.window.decorView.windowToken
            windowAnimations = 0
            title = "PawMiawToast"
            // Under the status bar, as iOS keeps it in the safe area.
            fitInsetsTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
        }
        runCatching {
            manager.addView(compose, params)
            view = compose
        }
    }

    fun remove() {
        val current = view ?: return
        view = null
        val manager = activity?.windowManager ?: return
        runCatching { manager.removeViewImmediate(current) }
    }
}

@Composable
private fun ToastOverlay(dark: Boolean, onGone: () -> Unit) {
    CompositionLocalProvider(LocalIsDark provides dark) {
        val center = ToastCenter.shared
        val item = center.current
        // A fresh window starts empty, so its toast slides in (iOS `.transition(.move(edge: .top))`).
        var target by remember { mutableStateOf<ToastCenter.Item?>(null) }
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(item) {
            target = item
            if (item != null) {
                shown = true
            } else if (shown) {
                // The last toast has left: the window goes.
                delay(450)
                if (center.current == null) onGone()
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = target,
                contentKey = { it?.id },
                transitionSpec = {
                    val motion = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                    (slideInVertically(motion) { -it } + fadeIn()) togetherWith (slideOutVertically(motion) { -it } + fadeOut())
                },
                label = "toast",
            ) { shownItem ->
                if (shownItem != null) ToastCard(shownItem) { center.current = null }
            }
        }
    }
}

@Composable
private fun ToastCard(item: ToastCenter.Item, onTap: () -> Unit) {
    // The web's sonner toasts: rich colours, 20 corners, a 16 icon.
    val tone = if (item.isError) Theme.destructive else Theme.blue
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .softShadow(shape, Theme.navy.copy(alpha = 0.14f), radius = 24.dp, y = 12.dp)
            .background(Theme.wash(tone, 14.0), shape)
            .border(1.dp, tone.copy(alpha = 0.45f), shape)
            .plainButton(role = null, onClick = onTap)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph(if (item.isError) "octagon-x" else "circle-check", 16.dp, tint = tone, modifier = Modifier.padding(top = 2.dp))
        Text(item.text, style = Theme.text(14, FontWeight.SemiBold), color = tone, modifier = Modifier.weight(1f))
    }
}
