package com.pawmiaw.business

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch

//
//  SwiftUI's NavigationStack on Android. A `Navigator` holds the pages pushed over a root;
//  `NavStack` draws them, the top one sliding in from the right, and the back gesture pops.
//  Pages underneath stay composed (hidden), so their `remember` state is kept as iOS keeps
//  `@State` under a pushed page.
//
//      val nav = LocalNavigator.current
//      Row(Modifier.clickable { nav.push(ProviderRoute.client(id)) }) { … }   // NavigationLink(value:)
//      LocalDismiss.current()                                                // dismiss() on a pushed page pops it
//
//  Swift `@Environment(\.horizontalSizeClass)` → `LocalSizeClass.current` (compact / regular),
//  `@Environment(\.openURL)` → `openURL(context, url)`.
//

@Stable
class Navigator {
    internal class Entry(val route: ProviderRoute, val id: Long)

    internal val entries = mutableStateListOf<Entry>()
    private var nextId = 1L
    /** The page being popped (still drawn while it slides out). */
    internal var popping: Entry? = null
    internal var onPop: ((Entry) -> Unit)? = null

    val routes: List<ProviderRoute> get() = entries.map { it.route }
    val depth: Int get() = entries.size

    /** iOS `NavigationLink(value:)` / `path.append`. */
    fun push(route: ProviderRoute) {
        entries.add(Entry(route, nextId++))
    }

    /** The back button / `dismiss()` on a pushed page. */
    fun pop() {
        val top = entries.lastOrNull() ?: return
        val handler = onPop
        if (handler != null) handler(top) else entries.remove(top)
    }

    fun popToRoot() {
        entries.clear()
    }

    /** Replaces the whole stack (a deep link). */
    fun reset(routes: List<ProviderRoute>) {
        entries.clear()
        routes.forEach { push(it) }
    }
}

/** The stack a page is on. Outside a stack (a sheet's content) pushes go to the shell's stack. */
val LocalNavigator = staticCompositionLocalOf { Navigator() }

/** Whether the page is the root of its stack (a section's own page) or pushed over another. */
val LocalIsPushedPage = compositionLocalOf { false }

/** iOS `UserInterfaceSizeClass` for the window's width. */
enum class SizeClass { compact, regular }

/** iOS `@Environment(\.horizontalSizeClass)`: regular from 600 dp wide (an iPad), compact on a phone. */
val LocalSizeClass = compositionLocalOf { SizeClass.compact }

@Composable
fun currentSizeClass(): SizeClass =
    if (LocalConfiguration.current.screenWidthDp >= 600) SizeClass.regular else SizeClass.compact

/** iOS `openURL(url)`: a web page, a phone call (`tel:`), a mail (`mailto:`), a map. */
fun openURL(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** `openURL` from a composable. */
@Composable
fun rememberOpenURL(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { url: String -> openURL(context, url) } }
}

private val pushEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Draws a navigator: [root] under every pushed page, [page] for each route. The top page slides
 * in from the trailing edge and out again on back (the system back gesture, `dismiss()`).
 */
@Composable
fun NavStack(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    root: @Composable () -> Unit,
    page: @Composable (ProviderRoute) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsets = remember { HashMap<Long, Animatable<Float, *>>() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        navigator.onPop = { entry ->
            val anim = offsets[entry.id]
            if (anim == null) navigator.entries.remove(entry) else scope.launch {
                anim.animateTo(width, tween(260, easing = pushEasing))
                navigator.entries.remove(entry)
                offsets.remove(entry.id)
            }
        }
        BackHandler(enabled = navigator.entries.isNotEmpty()) { navigator.pop() }

        val entries = navigator.entries.toList()
        val topIndex = entries.lastIndex
        CompositionLocalProvider(LocalNavigator provides navigator, LocalIsPushedPage provides false) {
            // The root: hidden (not removed) while a page is over it.
            Box(Modifier.fillMaxSize().hiddenUnder(topIndex >= 0 && offsets[entries[topIndex].id]?.value == 0f)) { root() }
        }
        entries.forEachIndexed { index, entry ->
            key(entry.id) {
                val anim = remember { Animatable(width).also { offsets[entry.id] = it } }
                LaunchedEffect(Unit) { anim.animateTo(0f, tween(320, easing = pushEasing)) }
                val covered = index < topIndex && offsets[entries[index + 1].id]?.value == 0f
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalIsPushedPage provides true,
                    LocalDismiss provides { navigator.pop() },
                    LocalInSheet provides false,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = anim.value }
                            .background(Theme.canvas)
                            .hiddenUnder(covered)
                            // A page sliding in takes the touches meant for the one under it.
                            .pointerInput(Unit) {}
                    ) { page(entry.route) }
                }
            }
        }
    }
}

/** A page under another: not drawn, not touched, not read out. */
private fun Modifier.hiddenUnder(covered: Boolean): Modifier =
    if (!covered) this else this
        .graphicsLayer { alpha = 0f }
        .clearAndSetSemantics {}
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }
