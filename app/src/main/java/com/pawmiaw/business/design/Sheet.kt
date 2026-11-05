package com.pawmiaw.business

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.launch

//
//  SwiftUI's `.sheet` on Android (the family Android app's AppSheet, dressed in this app's
//  Theme). A sheet is shown by composing it:
//
//      var showing by remember { mutableStateOf(false) }
//      if (showing) AppSheet(onDismissRequest = { showing = false }) { MySheet() }
//
//  (iOS `.sheet(item:)` → `item?.let { AppSheet(onDismissRequest = { item = null }) { … } }`).
//  Inside, `LocalDismiss.current()` closes it animated (iOS `@Environment(\.dismiss)`).
//  A pull down or the back gesture closes it unless a guard inside refuses
//  (`InteractiveDismissDisabled`, the ProviderModal's discard question), as iOS
//  `.interactiveDismissDisabled` + the DismissAttemptReader.
//
//  On a phone (and a tablet held upright) the sheet comes up from the bottom, the window's
//  width, short of the status bar. From 840 dp wide (a tablet sideways) it is the iPad's
//  form sheet: a card in the middle of the window.
//

/** SwiftUI `PresentationDetent`. */
sealed class PresentationDetent {
    data object large : PresentationDetent()
    data object medium : PresentationDetent()
    data class height(val value: Dp) : PresentationDetent()
    data class fraction(val value: Float) : PresentationDetent()
}

/** What a sheet's content can tell the sheet it is in, and `dismiss()`. */
@Stable
class AppSheetController internal constructor() {
    var dismissDisabled by mutableStateOf(false)
    var background by mutableStateOf<Color?>(null)
    var cornerRadius by mutableStateOf<Dp?>(null)
    /** `.presentationDetents([.medium, .large])` on a phone: opens at half and pulls up to the whole. */
    var twoHeights by mutableStateOf(false)
        internal set
    /** A small presentation (a picker, a confirm): in a wide window, a narrow card. */
    var isSmall by mutableStateOf(false)
        internal set
    internal var hide: () -> Unit = {}
    /** Set while the app itself closes the sheet; only the person's pull and back press are guarded. */
    internal var closingOnPurpose = false

    /** iOS `@Environment(\.dismiss)`: closes the sheet, animated. */
    fun dismiss() {
        closingOnPurpose = true
        hide()
    }
}

/** The sheet the content is in, or null outside one. */
val LocalAppSheet = staticCompositionLocalOf<AppSheetController?> { null }

/** iOS `@Environment(\.dismiss)`: closes the sheet (or pops the page) the view is in. */
val LocalDismiss = compositionLocalOf<() -> Unit> { {} }

/** Whether a view sits inside a sheet (iOS reads `presentationMode`); a page pushed on the stack is not. */
val LocalInSheet = compositionLocalOf { false }

/** SwiftUI `.interactiveDismissDisabled(_:)`, from inside a sheet's content. */
@Composable
fun InteractiveDismissDisabled(disabled: Boolean = true) {
    val sheet = LocalAppSheet.current ?: return
    SideEffect { sheet.dismissDisabled = disabled }
}

/** SwiftUI `.presentationBackground(_:)`, from inside a sheet's content. */
@Composable
fun PresentationBackground(color: Color) {
    val sheet = LocalAppSheet.current ?: return
    SideEffect { sheet.background = color }
}

/**
 * Hears a refused pull on the sheet it sits in (iOS `DismissAttemptReader`). The sheet's host
 * provides one; every guard inside registers with it.
 */
@Stable
class SheetDismissAttempts {
    private class Listener(val blocks: () -> Boolean, val onAttempt: () -> Unit)

    private val listeners = mutableStateMapOf<Any, Listener>()

    /** Whether a guard inside refuses the pull now. */
    val blocksInteractiveDismiss: Boolean get() = listeners.values.any { it.blocks() }

    /** The host refused a pull (or a back press): every guard hears it. */
    fun attempted() {
        listeners.values.toList().forEach { it.onAttempt() }
    }

    fun register(key: Any, blocks: () -> Boolean, onAttempt: () -> Unit) {
        listeners[key] = Listener(blocks, onAttempt)
    }

    fun unregister(key: Any) {
        listeners.remove(key)
    }
}

/** The dismiss attempts of the sheet a view is in; null outside a sheet. */
val LocalSheetDismissAttempts = compositionLocalOf<SheetDismissAttempts?> { null }

/** How big a sheet is in a wide window (iOS form sheet on an iPad). */
object AppSheetSizing {
    val margin = 56.dp
    val smallWidth = 480.dp
    val pageWidth = 640.dp
    val minimumSmallHeight = 220.dp
    /** From here a sheet is a card: Material's expanded width, a tablet held sideways. */
    val cardThreshold = 840.dp

    @Composable
    fun presentationIsRoomy(): Boolean = LocalConfiguration.current.screenWidthDp.dp >= cardThreshold

    @Composable
    fun sheetMaxWidth(small: Boolean = false): Dp {
        val width = LocalConfiguration.current.screenWidthDp.dp
        val measure = if (small) smallWidth else pageWidth
        return if (width >= cardThreshold) min(measure, width - margin * 2) else Dp.Unspecified
    }
}

/**
 * SwiftUI `.sheet { … }`: a Material bottom sheet dressed as the iOS one (the canvas ground,
 * a rounded top, the small grey drag indicator, no Material handle). It opens to its content's
 * height; the content sizes itself (`Modifier.sheetPresentation`, ProviderModal).
 */
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dragIndicator: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = remember { AppSheetController() }
    val attempts = remember { SheetDismissAttempts() }
    fun pullIsRefused(): Boolean = !controller.closingOnPurpose &&
        (controller.dismissDisabled || attempts.blocksInteractiveDismiss)
    val confirm: (SheetValue) -> Boolean = { target ->
        if (target != SheetValue.Hidden || !pullIsRefused()) true else {
            attempts.attempted()
            false
        }
    }
    val single = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
    val double = rememberModalBottomSheetState(skipPartiallyExpanded = false, confirmValueChange = confirm)
    val state = if (controller.twoHeights) double else single
    val scope = rememberCoroutineScope()
    val onDismiss by rememberUpdatedState(onDismissRequest)
    controller.hide = {
        scope.launch { state.hide() }.invokeOnCompletion {
            controller.closingOnPurpose = false
            if (!state.isVisible) onDismiss()
        }
    }
    val radius = controller.cornerRadius ?: Theme.cardRadius
    val ground = controller.background ?: Theme.canvas
    val topGap = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp
    val card = AppSheetSizing.presentationIsRoomy()
    val dark = LocalIsDark.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = state,
        sheetMaxWidth = AppSheetSizing.sheetMaxWidth(small = controller.isSmall),
        shape = if (card) RectangleShape else RoundedCornerShape(topStart = radius, topEnd = radius),
        containerColor = if (card) Color.Transparent else ground,
        contentColor = Theme.inkStrong,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler {
            if (pullIsRefused()) attempts.attempted() else controller.dismiss()
        }
        CompositionLocalProvider(
            LocalAppSheet provides controller,
            LocalDismiss provides controller::dismiss,
            LocalSheetDismissAttempts provides attempts,
            LocalInSheet provides true,
            LocalIsDark provides dark,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .layout { measurable, constraints ->
                        if (card && constraints.hasBoundedHeight) {
                            val margin = AppSheetSizing.margin.roundToPx()
                            val max = (constraints.maxHeight - margin * 2).coerceAtLeast(0)
                            val placeable = measurable.measure(constraints.copy(minHeight = minOf(constraints.minHeight, max), maxHeight = max))
                            val below = ((constraints.maxHeight - placeable.height) / 2).coerceAtLeast(margin)
                            layout(placeable.width, placeable.height + below) { placeable.place(0, 0) }
                        } else {
                            val gap = topGap.roundToPx()
                            val capped = if (constraints.hasBoundedHeight) {
                                val max = (constraints.maxHeight - gap).coerceAtLeast(0)
                                constraints.copy(minHeight = minOf(constraints.minHeight, max), maxHeight = max)
                            } else constraints
                            val placeable = measurable.measure(capped)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                    }
                    .then(if (card) Modifier.clip(RoundedCornerShape(radius)).background(ground) else Modifier)
            ) {
                Column(Modifier.fillMaxWidth(), content = content)
                if (dragIndicator && !card) {
                    // iOS `.presentationDragIndicator(.visible)`: 36×5, 5 from the top.
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 5.dp)
                            .size(36.dp, 5.dp)
                            .background(Color(0xFF7F7F7F).copy(alpha = 0.4f), RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}

/**
 * iOS `.presentationDetents(_:)`, on a sheet's root: `.large` the whole sheet, `.medium` half the
 * window, `.height(x)` that; in a wide window the window less margins (or its content's when
 * [hugsContent]). `[.medium, .large]` opens at half and pulls up to the whole.
 */
fun Modifier.sheetPresentation(
    detents: Set<PresentationDetent> = setOf(PresentationDetent.large),
    hugsContent: Boolean = false,
): Modifier = composed {
    val window = LocalConfiguration.current.screenHeightDp.dp
    val sheet = LocalAppSheet.current
    if (AppSheetSizing.presentationIsRoomy()) {
        val maxHeight = window - AppSheetSizing.margin * 2
        if (hugsContent && sheet != null) SideEffect { sheet.isSmall = true }
        return@composed if (hugsContent) {
            this.heightIn(min = min(AppSheetSizing.minimumSmallHeight, maxHeight), max = maxHeight)
        } else this.height(maxHeight)
    }
    if (hugsContent) return@composed this.heightIn(max = window * 0.92f)
    val twoHeights = PresentationDetent.large in detents && PresentationDetent.medium in detents
    if (twoHeights && sheet != null) SideEffect { sheet.twoHeights = true }
    when {
        PresentationDetent.large in detents -> this.fillMaxHeight()
        PresentationDetent.medium in detents -> this.height(window * 0.5f)
        else -> when (val first = detents.firstOrNull()) {
            is PresentationDetent.height -> this.height(min(first.value, window))
            is PresentationDetent.fraction -> this.fillMaxHeight(first.value)
            else -> this.fillMaxHeight()
        }
    }
}
