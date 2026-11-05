package com.pawmiaw.business

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

//
//  Components.kt + Components2.kt (iOS Components.swift, split in two for its length):
//  the shared pieces every business screen is built from. One component per control,
//  one look: a screen never draws its own card, chip or empty state.
//
//  This file: the page, cards, rows, chips, filters, toned chips and the states.
//  Components2.kt: notices, headings, bars, ticks, people and pets, the location gate,
//  search, the reload rules and the cards of a page.
//
//  Swift nested types live on an object of the view's name (`Chip.Tone.warn`,
//  `TagChip.Size.sm`), so a name searched finds both sides.
//

// MARK: - SwiftUI pieces on Compose (shared by the design kit)

/** SwiftUI `.tracking(x)`: extra space between letters, in points. */
fun TextStyle.tracking(points: Number): TextStyle = copy(letterSpacing = points.toFloat().sp)

/** SwiftUI `.monospacedDigit()`: tabular figures. */
fun TextStyle.monospacedDigit(): TextStyle = copy(fontFeatureSettings = "tnum")

/** The capsule every pill and chip is drawn in (SwiftUI `Capsule()`). */
val CapsuleShape = RoundedCornerShape(50)

/**
 * SwiftUI `.strokeBorder(color, style: StrokeStyle(lineWidth:, dash: [on, off]))` on a rounded
 * rectangle (`cornerRadius`) or a capsule (`cornerRadius == null`): drawn inside the bounds.
 */
fun Modifier.dashedBorder(width: Dp, color: Color, cornerRadius: Dp? = null, on: Dp = 4.dp, off: Dp = 3.dp): Modifier =
    drawBehind {
        val stroke = width.toPx()
        val inset = stroke / 2
        val radius = cornerRadius?.toPx() ?: (size.minDimension / 2)
        val r = (radius - inset).coerceAtLeast(0f)
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()))),
        )
    }

/**
 * SwiftUI `.padding(.horizontal, -x)`: the view runs `x` past both sides of its parent (a
 * scrolling row of a card that bleeds to the card's edges) while taking its parent's width.
 */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
    val placeable = measurable.measure(wide)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-horizontal.roundToPx(), 0) }
}

/**
 * SwiftUI `.scrollDismissesKeyboard(.interactively)` on the scroll view this modifier sits
 * over (put it before `verticalScroll`): pulling the content down puts the keyboard away.
 */
fun Modifier.scrollDismissesKeyboard(): Modifier = composed {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val connection = remember(keyboard, focus) { KeyboardDismissal(keyboard, focus) }
    nestedScroll(connection)
}

private class KeyboardDismissal(val keyboard: SoftwareKeyboardController?, val focus: FocusManager) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) {
            keyboard?.hide()
            focus.clearFocus()
        }
        return Offset.Zero
    }
}

// MARK: - Page

/**
 * A page's scrolling column: the provider canvas, a readable width on iPad, pull to refresh.
 */
@Composable
fun PageScroll(
    spacing: Dp = 20.dp,
    maxWidth: Dp = 820.dp,
    refresh: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try { refresh?.invoke() } finally { refreshing = false }
            }
        },
        modifier = modifier.fillMaxSize().background(Theme.canvas),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Theme.surface,
                color = Theme.inkSoft,
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().scrollDismissesKeyboard().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = maxWidth + 32.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

// MARK: - Card

@Composable
fun Card(
    padding: Dp = 16.dp,
    /** An attention card (web: "Por cobrar" coral, "Quedaron abiertas" mango): a wash of the tone and its border. */
    tint: Color? = null,
    spacing: Dp = 12.dp,
    /** No shadow (a card inside a card, a sheet's box; web `rounded-card border` without `shadow-card`). */
    flat: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .cardShadowUnlessFlat(flat, shape)
            .background(tint?.let { Theme.wash(it, 14.0) } ?: Theme.surface, shape)
            .border(if (tint == null) 1.dp else 2.dp, tint?.copy(alpha = 0.75f) ?: Theme.borderSoft, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** iOS `CardShadowUnlessFlat`. */
@Composable
private fun Modifier.cardShadowUnlessFlat(flat: Boolean, shape: RoundedCornerShape): Modifier =
    if (flat) this else this.cardShadow(shape)

/** A titled group of a page: the title, an optional count or action, then its card(s). */
@Composable
fun PageSection(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).alignBy(FirstBaseline), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Theme.heading(18), color = Theme.inkStrong)
                if (subtitle != null) Text(subtitle, style = Theme.text(13), color = Theme.inkSoft)
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.alignBy(FirstBaseline), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        content()
    }
}

/** A row inside a card: leading view, title + lines, trailing view, chevron when it opens something. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    detail: String? = null,
    showsChevron: Boolean = false,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme.rowTitleFont, color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = Theme.smallFont, color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = Theme.captionFont, color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
        if (showsChevron) Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

/**
 * A dashed divider: the provider modal's "stitched" rule (2 pt), or a 1 pt `border-dashed`
 * hairline (a tinted head's foot) with `lineWidth = 1.dp`.
 */
@Composable
fun StitchedDivider(lineWidth: Dp = 2.dp, color: Color = Theme.borderSoft, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(lineWidth)) {
        val dash = if (lineWidth < 2.dp) floatArrayOf(4.dp.toPx(), 3.dp.toPx()) else floatArrayOf(6.dp.toPx(), 5.dp.toPx())
        val y = size.height / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = lineWidth.toPx(), pathEffect = PathEffect.dashPathEffect(dash))
    }
}

/** "Label: value" in a fact list. */
@Composable
fun FactRow(label: String, value: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.alignByBaseline(), style = Theme.smallFont, color = Theme.inkSoft)
        Spacer(Modifier.width(8.dp))
        Text(
            value.nonEmpty ?: "—",
            Modifier.weight(1f).alignByBaseline(),
            style = Theme.text(15, FontWeight.SemiBold),
            color = Theme.inkStrong,
            textAlign = TextAlign.End,
        )
    }
}

// MARK: - Chips

/**
 * A status (web `ProviderStatusChip`): uppercase, tracked, a 2 pt edge. One of the six
 * states (fill = edge = the state's colour, its ink), or a brand tone (lime, mango, aqua,
 * pink: the brand fill with a 2 pt PAW Blue edge and navy ink — a client "ARCHIVADO").
 * `outlined`: drawn dashed, not a visit yet (a requested booking). `small`: a row's tag (9.9 pt).
 */
@Composable
fun StatusChip(text: String, state: ProviderState, outlined: Boolean = false, small: Boolean = false, modifier: Modifier = Modifier) {
    StatusChipBody(text, state, outlined, null, small, modifier)
}

/** A brand fill instead of a state (`tone="lime"` on the web). */
@Composable
fun StatusChip(text: String, brand: Color, small: Boolean = false, modifier: Modifier = Modifier) {
    StatusChipBody(text, ProviderState.closed, false, brand, small, modifier)
}

@Composable
private fun StatusChipBody(text: String, state: ProviderState, outlined: Boolean, brand: Color?, small: Boolean, modifier: Modifier) {
    val fill = brand ?: if (outlined) state.wash else state.color
    val ink = if (brand != null) Theme.inkOnBright else if (outlined) Theme.inkStrong else state.ink
    val edge = when {
        brand != null -> Modifier.border(2.dp, if (brand == Theme.blue) Theme.lime else Theme.blueFixed, CapsuleShape)
        outlined -> Modifier.dashedBorder(2.dp, state.color, null, 4.dp, 3.dp)
        else -> Modifier.border(2.dp, state.color, CapsuleShape)
    }
    Text(
        text.uppercase(),
        modifier
            .background(fill, CapsuleShape)
            .then(edge)
            .padding(horizontal = if (small) 8.dp else 12.dp, vertical = if (small) 1.dp else 4.dp),
        style = brandText(if (small) 9.9 else 11.5, FontWeight.Bold).tracking(if (small) 1.2 else 1.4),
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A category (a service, a room, a role): a hue, never a status colour. The icon is the
 * web's Lucide one (an SF name maps to it).
 */
@Composable
fun HueChip(text: String, hue: Hue = Hue.grey, systemImage: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.background(hue.color.copy(alpha = 0.22f), CapsuleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (systemImage != null) Glyph(systemImage, 12.dp, tint = hue.ink)
        Text(text, style = brandText(12, FontWeight.Bold), color = hue.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `Chip.Tone`, `Chip.Size`. */
object Chip {
    sealed class Tone {
        data object muted : Tone()
        data object danger : Tone()
        data object warn : Tone()
        data object good : Tone()
        data object info : Tone()
        data object online : Tone()
        /** Any colour (a status, a hue, a stage) washed at `percent`. */
        data class custom(val color: Color) : Tone()
    }

    enum class Size { small, medium, large, wide, header }
}

/**
 * The one small pill of a row, in any tone (web visit-row `Chip`, `HubChip`, agenda-list
 * `Pill`, inventory `TonePill`, record `StatusPill`/`RecordTypeChip`, hospital `PILL`,
 * grooming `StagePill`, `RxChip`, `StatusTag`, home-visit `StatePill`, `RoleChip`): a capsule,
 * the tone's wash, navy words. The fixed tones are §9.3's; `.custom` takes any colour at
 * `percent` (24 unless told). A 6 dot, an icon, a count, a 1 pt edge, a dashed "pending"
 * edge, or the triage ring may join it. Sizes: `.small` 11.2 (a row's chip), `.medium` 12
 * at 24 tall (a tag beside a title), `.large` 12.8 at 28 (a state pill), `.wide` 14 at 28,
 * `.header` 14 at 36 (a state shown among a module header's actions: the Store's register).
 */
@Composable
fun Chip(
    text: String,
    tone: Chip.Tone = Chip.Tone.muted,
    icon: String? = null,
    /** Dashed and unfilled: waiting for an answer (a requested booking). */
    pending: Boolean = false,
    /** A 6 pt dot before the words. */
    dot: Color? = null,
    /** A number after the words. */
    count: Int? = null,
    /** The wash of a custom tone (100 = the solid colour). */
    percent: Double? = null,
    /** The words' ink (navy unless the tone says otherwise). */
    ink: Color? = null,
    /** The icon's ink and size when it wears the tone (a record's kind). */
    iconInk: Color? = null,
    iconSize: Dp? = null,
    weight: FontWeight = FontWeight.Bold,
    /** A 1 pt edge of the tone at this percent over the border colour. */
    border: Double? = null,
    /** No fill, a 1 pt grey outline (an email that is only a link). */
    outlined: Boolean = false,
    /** The triage badge: a 1 pt ring of the tone, extrabold uppercase, tracked. */
    ring: Boolean = false,
    size: Chip.Size = Chip.Size.small,
    modifier: Modifier = Modifier,
) {
    val fontSize = when (size) {
        Chip.Size.small -> 11.2
        Chip.Size.medium -> 12.0
        Chip.Size.large -> 12.8
        Chip.Size.wide, Chip.Size.header -> 14.0
    }
    // The tone's own colour (its dot, edge and ring).
    val color = when (tone) {
        Chip.Tone.muted -> Theme.inkSoft
        Chip.Tone.danger -> Theme.destructive
        Chip.Tone.warn -> Theme.mango
        Chip.Tone.good -> Theme.lime
        Chip.Tone.info -> Theme.aqua
        Chip.Tone.online -> Theme.lavender
        is Chip.Tone.custom -> tone.color
    }
    val fill = when (tone) {
        Chip.Tone.muted -> Theme.surfaceMuted
        Chip.Tone.danger -> Theme.wash(Theme.destructive, 12.0)
        Chip.Tone.warn -> Theme.wash(Theme.mango, 25.0)
        Chip.Tone.good -> Theme.wash(Theme.lime, 30.0)
        Chip.Tone.info -> Theme.wash(Theme.aqua, 22.0)
        Chip.Tone.online -> Theme.wash(Theme.lavender, 30.0)
        is Chip.Tone.custom -> if ((percent ?: 24.0) >= 100) tone.color else Theme.wash(tone.color, percent ?: 24.0)
    }
    val defaultInk = when (tone) {
        Chip.Tone.muted -> Theme.inkSoft
        Chip.Tone.danger -> Theme.destructive
        is Chip.Tone.custom -> if ((percent ?: 24.0) >= 100) Theme.inkOnBright else Theme.inkStrong
        else -> Theme.inkStrong
    }
    val edge = when {
        pending -> Modifier.dashedBorder(1.5.dp, ProviderState.waiting.color, null, 4.dp, 3.dp)
        outlined -> Modifier.border(1.dp, Theme.borderSoft, CapsuleShape)
        ring -> Modifier.border(1.dp, color, CapsuleShape)
        border != null -> Modifier.border(
            if (size == Chip.Size.large || size == Chip.Size.header) 1.5.dp else 1.dp,
            Theme.mix(color, border, Theme.borderSoft),
            CapsuleShape,
        )
        else -> Modifier
    }
    val minHeight = when (size) {
        Chip.Size.medium -> 24.dp
        Chip.Size.small -> null
        Chip.Size.header -> 36.dp
        else -> 28.dp
    }
    val words = ink ?: defaultInk
    val style = brandText(fontSize, if (ring) FontWeight.ExtraBold else weight).tracking(if (ring) fontSize * 0.08 else 0)
    CompositionLocalProvider(LocalContentColor provides words, LocalTextStyle provides style) {
        Row(
            modifier
                .then(if (minHeight != null) Modifier.heightIn(min = minHeight) else Modifier)
                .background(if (pending || outlined) Color.Transparent else fill, CapsuleShape)
                .then(edge)
                .padding(
                    horizontal = when (size) {
                        Chip.Size.small -> 8.dp
                        Chip.Size.wide -> 12.dp
                        Chip.Size.header -> 14.dp
                        else -> 10.dp
                    },
                    vertical = if (size == Chip.Size.small) 2.dp else 0.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(
                when (size) {
                    Chip.Size.small -> 4.dp
                    Chip.Size.header -> 8.dp
                    else -> 6.dp
                }
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot != null) Box(Modifier.size(6.dp).background(dot, CircleShape))
            if (icon != null) {
                Glyph(
                    icon,
                    iconSize ?: when (size) {
                        Chip.Size.small -> 12.dp
                        Chip.Size.header -> 16.dp
                        else -> 14.dp
                    },
                    tint = iconInk ?: Color.Unspecified,
                )
            }
            Text(if (ring) text.uppercase() else text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null) Text("$count", style = style.monospacedDigit())
        }
    }
}

/** A chip of a status or hue colour (`.custom(color)`). */
@Composable
fun Chip(
    text: String,
    color: Color,
    percent: Double = 24.0,
    icon: String? = null,
    dot: Boolean = false,
    size: Chip.Size = Chip.Size.small,
    modifier: Modifier = Modifier,
) {
    Chip(text = text, tone = Chip.Tone.custom(color), icon = icon, dot = if (dot) color else null, percent = percent, size = size, modifier = modifier)
}

/** `TagChip.Size`. */
object TagChip {
    enum class Size { xs, sm, header }
}

/**
 * An outlined tag on a row (web `ProviderClientLinkBadge`, `ClientStatusPill`): uppercase,
 * tracked. "SOLO EN LA CLÍNICA" is grey and empty, "FAMILIA VINCULADA" blue; with a `tone`
 * it is a status pill ("PROSPECTO" lavender, "ARCHIVADO" grey: the tone at 24% with a 70%
 * edge). Sizes: `.xs` 9.9 (a row), `.sm` 10.9, `.header` 12 at 32 tall (a sheet's context).
 */
@Composable
fun TagChip(
    text: String,
    icon: String? = null,
    linked: Boolean = false,
    /** A status pill's colour (lavender for a lead, the border colour for the rest). */
    tone: Color? = null,
    size: TagChip.Size = TagChip.Size.xs,
    modifier: Modifier = Modifier,
) {
    val fontSize = when (size) {
        TagChip.Size.xs -> 9.9
        TagChip.Size.sm -> 10.9
        TagChip.Size.header -> 12.0
    }
    val fill = when {
        tone != null -> Theme.wash(tone, 24.0)
        linked -> Theme.wash(Theme.blue, 12.0)
        else -> Color.Transparent
    }
    val edge = when {
        tone != null -> Theme.mix(tone, 70.0, Theme.borderSoft)
        linked -> Theme.mix(Theme.blue, 55.0, Theme.borderSoft)
        else -> Theme.borderSoft
    }
    val ink = if (tone != null) Theme.inkStrong else if (linked) Theme.blueStrong else Theme.inkSoft
    Row(
        modifier
            .then(if (size == TagChip.Size.header) Modifier.heightIn(min = 32.dp) else Modifier)
            .background(fill, CapsuleShape)
            .border(1.dp, edge, CapsuleShape)
            .padding(
                horizontal = when (size) {
                    TagChip.Size.xs -> 8.dp
                    TagChip.Size.sm -> 10.dp
                    TagChip.Size.header -> 12.dp
                },
                vertical = when (size) {
                    TagChip.Size.xs -> 2.dp
                    TagChip.Size.sm -> 4.dp
                    TagChip.Size.header -> 0.dp
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(if (tone == null) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Glyph(icon, if (size == TagChip.Size.xs) 12.dp else 14.dp, tint = ink)
        Text(
            text.uppercase(),
            style = brandText(fontSize, if (linked || tone != null) FontWeight.Bold else FontWeight.SemiBold).tracking(fontSize * 0.12),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A count badge (a sidebar number, a tab's waiting work). The bell caps at "9+", the sidebar at "99+". */
@Composable
fun CountBadge(count: Int, color: Color = Theme.destructive, cap: Int = 99, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier.background(color, CapsuleShape).sizeIn(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > cap) "$cap+" else "$count", style = brandText(10.9, FontWeight.ExtraBold), color = Color.White)
    }
}

/**
 * A filter in a row of filters (web `FilterChip`, the wash chip): 36 tall, 1.5 pt border,
 * 14 bold. Off: white, grey border, grey text. On: the chip's colour at 26% with a deeper
 * border of it and navy text. A dot or an icon may lead; a count (12 extrabold, grey) may
 * follow. `solid` is the notification feed's filter (a 2 pt edge, solid PAW Blue when on).
 */
@Composable
fun FilterChip(
    title: String,
    count: Int? = null,
    isSelected: Boolean,
    tone: Color = Theme.blue,
    dot: Color? = null,
    icon: String? = null,
    solid: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val ink = if (solid) (if (isSelected) Theme.onBlue else Theme.inkSoft) else (if (isSelected) Theme.inkStrong else Theme.inkSoft)
    val fill = if (solid) (if (isSelected) Theme.blue else Theme.surface) else (if (isSelected) Theme.wash(tone, 26.0) else Theme.surface)
    val edge = if (solid) (if (isSelected) Theme.blue else Theme.borderSoft) else (if (isSelected) Theme.mix(tone, 75.0, Theme.inkStrong) else Theme.borderSoft)
    Row(
        modifier
            .plainButton(onClick = action)
            .semantics { selected = isSelected }
            .height(36.dp)
            .background(fill, CapsuleShape)
            .border(if (solid) 2.dp else 1.5.dp, edge, CapsuleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        if (icon != null) Glyph(icon, 14.dp, tint = if (solid) ink else Theme.mix(tone, 70.0, Theme.inkStrong))
        Text(title, style = brandText(14, FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) {
            if (solid) {
                Text(
                    "$count",
                    Modifier
                        .background(if (isSelected) Theme.onBlue.copy(alpha = 0.2f) else Theme.inkSoft.copy(alpha = 0.12f), CapsuleShape)
                        .padding(horizontal = 6.dp),
                    style = brandText(12, FontWeight.Bold).monospacedDigit(),
                    color = if (isSelected) Theme.onBlue else Theme.inkStrong,
                )
            } else if (count > 0) {
                Text(if (count > 99) "99+" else "$count", style = brandText(12, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkSoft)
            }
        }
    }
}

/** The chip of a choice made elsewhere: a face, the words and a ✕ that undoes it ("Luna · Carmen ✕"), always on. */
@Composable
fun FilterChip(
    title: String,
    face: (@Composable () -> Unit)? = null,
    removeLabel: String,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
) {
    val tone = Theme.blue
    Row(
        modifier
            .height(36.dp)
            .background(Theme.wash(tone, 26.0), CapsuleShape)
            .border(1.5.dp, Theme.mix(tone, 75.0, Theme.inkStrong), CapsuleShape)
            .padding(start = if (face == null) 12.dp else 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (face != null) Box(Modifier.size(28.dp).clip(CircleShape)) { face() }
        Text(title, style = brandText(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier
                .size(28.dp)
                .plainButton(label = removeLabel, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Glyph("x", 14.dp, tint = Theme.inkStrong)
        }
    }
}

// MARK: - Toned chips (web catalog-bits.tsx, §5.3 B and C)

/**
 * The icon of a toned tab or chip on its small round chip (web `TonedTabIcon`): quiet, the
 * tone's 24% wash with the deep shade (dark: the tone lightened); chosen, a white veil on
 * the fill. `dot` draws an 8 pt dot instead of an icon.
 */
@Composable
fun ToneIcon(
    icon: String,
    tone: ChipTone,
    selected: Boolean,
    size: Dp = 24.dp,
    glyph: Dp = 14.dp,
    dot: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val quiet = if (LocalIsDark.current) Theme.mix(tone.color, 70.0, Theme.inkStrong) else tone.fill
    val ink = if (selected) Color.White else quiet
    Box(
        modifier.size(size).background(if (selected) Color.White.copy(alpha = 0.2f) else Theme.wash(tone.color, 24.0), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (dot) Box(Modifier.size(8.dp).background(ink, CircleShape)) else Glyph(icon, glyph, tint = ink)
    }
}

/**
 * The count on a toned chip or tab: white on the deep shade while quiet, the deep shade on
 * white while on (20 tall, 10.9 extrabold).
 */
@Composable
fun ToneCount(count: Int, tone: ChipTone, selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(if (selected) Color.White else tone.fill, CapsuleShape)
            .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = brandText(10.9, FontWeight.ExtraBold).monospacedDigit(),
            color = if (selected) tone.fill else Color.White,
        )
    }
}

/**
 * A filter that is on or off in its own colour (web `CatalogFilterChip`, §5.3), toned (B):
 * 40 tall, 1 pt edge, 14 bold; quiet it is white with only its icon chip tinted; on, the
 * tone's deep shade under white. `dot`: an 8 pt dot in the icon chip instead of an icon
 * (the bills' status chips).
 */
@Composable
fun ToneChip(
    title: String,
    icon: String? = null,
    tone: ChipTone = ChipTone.page,
    count: Int? = null,
    dot: Boolean = false,
    isOn: Boolean,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val shownIcon = if (dot) (icon ?: "circle") else icon
    Row(
        modifier
            .plainButton(onClick = action)
            .semantics { selected = isOn }
            .shadow(if (isOn) 1.5.dp else 0.dp, CapsuleShape, clip = false, ambientColor = Theme.navy.copy(alpha = 0.1f), spotColor = Theme.navy.copy(alpha = 0.1f))
            .height(40.dp)
            .background(if (isOn) tone.fill else Theme.surface, CapsuleShape)
            .border(1.dp, if (isOn) tone.fill else Theme.borderSoft, CapsuleShape)
            .padding(start = if (shownIcon == null) 14.dp else 8.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (shownIcon != null) ToneIcon(icon = shownIcon, tone = tone, selected = isOn, dot = dot)
        Text(title, style = brandText(14, FontWeight.Bold), color = if (isOn) Color.White else Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null && count > 0) ToneCount(count = count, tone = tone, selected = isOn)
    }
}

/**
 * The plain filter chip (C, §5.3): on = the colour's edge, its 12% wash and navy words, the
 * count on the colour. `dot`: a leading dot (a payment method); `countInk`: the count's ink
 * on a bright colour (mango, lime: navy instead of white).
 */
@Composable
fun ToneChip(
    title: String,
    icon: String? = null,
    plain: Color,
    dot: Color? = null,
    count: Int? = null,
    countInk: Color = Color.White,
    isOn: Boolean,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val ink = if (isOn) Theme.inkStrong else Theme.inkSoft
    Row(
        modifier
            .plainButton(onClick = action)
            .semantics { selected = isOn }
            .height(40.dp)
            .background(if (isOn) Theme.wash(plain, 12.0) else Theme.surface, CapsuleShape)
            .border(1.dp, if (isOn) plain else Theme.borderSoft, CapsuleShape)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        if (icon != null) Glyph(icon, 16.dp, tint = ink)
        Text(title, style = brandText(14, FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null && count > 0) {
            Box(
                Modifier.background(plain, CapsuleShape).sizeIn(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", style = brandText(10.9, FontWeight.ExtraBold).monospacedDigit(), color = countInk)
            }
        }
    }
}

/** A menu that filters, drawn as a toned chip (web `CatalogMenuChip`): an `AppMenu`'s label. */
@Composable
fun ToneMenuLabel(title: String, icon: String? = null, tone: ChipTone = ChipTone.page, isOn: Boolean = false, modifier: Modifier = Modifier) {
    val ink = if (isOn) Color.White else Theme.inkSoft
    Row(
        modifier
            .height(40.dp)
            .background(if (isOn) tone.fill else Theme.surface, CapsuleShape)
            .border(1.dp, if (isOn) tone.fill else Theme.borderSoft, CapsuleShape)
            .padding(start = if (icon == null) 14.dp else 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) ToneIcon(icon = icon, tone = tone, selected = isOn)
        Text(title, style = brandText(14, FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Glyph("chevron-down", 14.dp, tint = ink.copy(alpha = ink.alpha * 0.8f))
    }
}

/** The 1 × 24 rule between a chip row's groups (web: before a select in a chip row). */
@Composable
fun ChipRule(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 4.dp).size(1.dp, 24.dp).background(Theme.borderSoft))
}

/**
 * A filter that opens a menu (a person, a room, a location, a sort): a 36 pt bold capsule
 * with a chevron, used as an `AppMenu`'s label.
 */
@Composable
fun FilterMenuLabel(title: String, selected: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(36.dp)
            .background(if (selected) Theme.wash(Theme.blue, 12.0) else Theme.surface, CapsuleShape)
            .border(1.dp, if (selected) Theme.blue else Theme.borderSoft, CapsuleShape)
            .padding(start = 14.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = brandText(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
    }
}

/**
 * The status tabs of a board (web: "Todo 0 · En espera 0 …"): 32 tall, 12 bold; the chosen
 * one filled navy (white in dark) with the surface's ink. Options are (value, title, count).
 */
@Composable
fun <Value> StatusTabs(options: List<Triple<Value, String, Int?>>, selection: MutableState<Value>, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, title, count) in options) {
            val chosen = value == selection.value
            Row(
                Modifier
                    .plainButton { selection.value = value }
                    .semantics { selected = chosen }
                    .height(32.dp)
                    .background(if (chosen) Theme.inkStrong else Color.Transparent, CapsuleShape)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val style = brandText(12, FontWeight.Bold)
                Text(title, style = style, color = if (chosen) Theme.surface else Theme.inkSoft)
                if (count != null) {
                    Text("$count", style = style.monospacedDigit(), color = if (chosen) Theme.surface.copy(alpha = 0.8f) else Theme.inkStrong)
                }
            }
        }
    }
}

/** A horizontal, scrolling row of filter chips over a value. Options are (value, title, count). */
@Composable
fun <Value> FilterBar(options: List<Triple<Value, String, Int?>>, selection: MutableState<Value>, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, title, count) in options) {
            FilterChip(title = title, count = count, isSelected = selection.value == value) { selection.value = value }
        }
    }
}

// MARK: - States

@Composable
fun LoadingState(text: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().heightIn(min = 220.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(36.dp), color = Theme.blue, strokeWidth = 3.dp)
        if (text != null) Text(text, style = Theme.smallFont, color = Theme.inkSoft)
    }
}

/**
 * The one empty state (web `ProviderEmptyState`): a round tinted well with the icon, a
 * heading, a line of help and the actions. `panel` is a dashed card for a page or section
 * that is all empty; plain it sits inside a card.
 */
@Composable
fun EmptyState(
    systemImage: String = "tray",
    title: String,
    message: String? = null,
    actionTitle: String? = null,
    action: (() -> Unit)? = null,
    accent: Color = Theme.aqua,
    panel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (panel) Modifier.background(Theme.surface, shape).dashedBorder(2.dp, Theme.borderSoft, Theme.cardRadius, 6.dp, 5.dp)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = if (panel) 36.dp else 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = systemImage, accent = accent, size = 56.dp, iconSize = 28.dp, ink = IconBadge.Ink.strong, washPercent = 22.0)
        Text(title, Modifier.padding(top = 12.dp), style = Theme.heading(20), color = Theme.inkStrong, textAlign = TextAlign.Center)
        val shown = message.nonEmpty
        if (shown != null) {
            Text(shown, Modifier.widthIn(max = 512.dp).padding(top = 4.dp), style = brandText(14), color = Theme.inkSoft, textAlign = TextAlign.Center)
        }
        if (actionTitle != null && action != null) {
            PMButton(actionTitle, size = ButtonSize.header, modifier = Modifier.padding(top = 16.dp), action = action)
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, retry: (suspend () -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph("triangle-alert", 24.dp, tint = Theme.coral)
        Text(message, style = Theme.bodyFont, color = Theme.inkStrong, textAlign = TextAlign.Center)
        if (retry != null) {
            PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { retry() } }
        }
    }
}

/** What a person sees where their role can't open something. */
@Composable
fun NoAccessState(message: String = L("Your role can't see this. Ask the owner of the business."), modifier: Modifier = Modifier) {
    EmptyState(systemImage = "lock.fill", title = L("Not for your role"), message = message, modifier = modifier)
}
