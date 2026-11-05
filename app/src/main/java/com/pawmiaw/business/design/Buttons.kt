package com.pawmiaw.business

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import kotlin.math.abs

//
//  The one button of the business side (iOS Buttons.swift; web: the shared `Button`, variants
//  default/outline/destructive, sizes default 48 / compact 40). Never repaint a button's
//  height, corners or colour on a screen: pick a variant and a size. Small round icon controls
//  are not action buttons.
//
//  Swift → Kotlin:
//      PMButton(L("Save"), systemImage: "save") { save() }.disabled(!valid)
//          → PMButton(L("Save"), systemImage = "save", enabled = valid) { save() }
//      NavigationLink(value: r) { PMButtonContent(title: t, icon: i) }.buttonStyle(.pm(.outline, size: .compact))
//          → PMButtonStyle(ButtonVariant.outline, ButtonSize.compact, onClick = { nav.push(r) }) { PMButtonContent(t, icon = i) }
//      Menu { … } label: { PMButtonContent(…) }.buttonStyle(.pm(.outline, size: .compact))
//          → AppMenu(label = { PMButtonStyle(ButtonVariant.outline, ButtonSize.compact) { PMButtonContent(…) } }) { … }
//      Button { … } label: { … }.buttonStyle(.plain)  → the label with `Modifier.plainButton { … }`
//      .disabled(x) on a group (a row of buttons)    → Disabled(x) { … }  (controls read LocalIsEnabled)
//      .lineLimit(1).minimumScaleFactor(0.8)         → FittedText(text, minimumScaleFactor = 0.8f)
//

/** SwiftUI `@Environment(\.isEnabled)`: false under a `.disabled(true)` (`Disabled`, a disabled control). */
val LocalIsEnabled = compositionLocalOf { true }

/** SwiftUI `.disabled(x)` on a group: every control inside reads it (`LocalIsEnabled`). */
@Composable
fun Disabled(disabled: Boolean = true, content: @Composable () -> Unit) {
    val enabled = LocalIsEnabled.current && !disabled
    CompositionLocalProvider(LocalIsEnabled provides enabled, content = content)
}

/**
 * The press of a control drawn around a label it doesn't own (an `AppMenu`'s label): a
 * `PMButtonStyle` inside without its own `onClick` shows this press, and says it did
 * (`styled`), so the owner doesn't dim the label a second time.
 */
@Stable
class PressSource(val interaction: MutableInteractionSource) {
    var styled by mutableStateOf(false)
}

/** The press of the control around a label (see `PressSource`); null outside one. */
val LocalPressSource = compositionLocalOf<PressSource?> { null }

/**
 * SwiftUI `Button { } label: { }.buttonStyle(.plain)`: the label as it is, tappable, dimmed
 * while pressed and when disabled (`enabled` and `LocalIsEnabled`). `label` replaces what is
 * read out (`.accessibilityLabel`).
 */
fun Modifier.plainButton(
    enabled: Boolean = true,
    label: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isEnabled = enabled && LocalIsEnabled.current
    this
        .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        .clickable(interactionSource = interaction, indication = null, enabled = isEnabled, role = role, onClick = onClick)
}

/**
 * A line of text that shrinks (down to `minimumScaleFactor`) before it would cut, then ends in
 * "…" (SwiftUI `.lineLimit(1).minimumScaleFactor(x)`). Style and colour as `Text`'s.
 */
@Composable
fun FittedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    minimumScaleFactor: Float = 1f,
    textAlign: TextAlign? = null,
) {
    val base = style.fontSize
    val scales = minimumScaleFactor < 1f && !base.isUnspecified
    var scale by remember(text, base, minimumScaleFactor) { mutableFloatStateOf(1f) }
    var fitted by remember(text, base, minimumScaleFactor) { mutableStateOf(!scales) }
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (fitted) drawContent() },
        color = ink,
        style = if (scales) style.copy(fontSize = base * scale) else style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        onTextLayout = { result ->
            if (scales) {
                // The words' width at full size, against the room offered: full size when they
                // fit, else just small enough (never under the minimum). Settles in a pass or two.
                val constraints = result.layoutInput.constraints
                val room = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.MAX_VALUE
                val natural = result.multiParagraph.maxIntrinsicWidth / scale
                val target = if (natural <= room + 0.5f) 1f else (room / natural * 0.98f).coerceIn(minimumScaleFactor, 1f)
                if (abs(target - scale) > 0.01f) {
                    scale = target
                    fitted = false
                } else {
                    fitted = true
                }
            }
        },
    )
}

/**
 * primary (solid PAW Blue), outline (2 pt blue edge), destructive (2 pt red edge on its
 * wash), ghost (no edge, no fill, ink-strong: web `variant="ghost"`), link (blue bold words,
 * no edge, no fill: the web's inline links, "Abrir la agenda", "Ver todo").
 */
enum class ButtonVariant { primary, outline, destructive, ghost, link }

/**
 * regular 48 (h-12), modal 44 (a sheet's footer, h-11), compact 40 (a toolbar's or a row's,
 * h-10), header 36 (a module header's actions, an empty state's on a phone), slim 32 (a
 * board's links, h-8), small 28 (web `size="sm"`, h-7: 12.8 text, 14 icon — a settings
 * card's save bar).
 */
enum class ButtonSize { regular, compact, header, modal, slim, small }

/**
 * iOS `PMButtonStyle` / `.buttonStyle(.pm(variant, size:, fullWidth:))`: the button's frame,
 * fill, edge, words' font and colour around any label (`PMButtonContent`). With `onClick` it is
 * the button; without, it shows the press of the control around it (an `AppMenu`, via
 * `LocalPressSource`).
 */
@Composable
fun PMButtonStyle(
    variant: ButtonVariant = ButtonVariant.primary,
    size: ButtonSize = ButtonSize.regular,
    fullWidth: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    val isEnabled = enabled && LocalIsEnabled.current
    val press = if (onClick == null) LocalPressSource.current else null
    if (press != null) SideEffect { press.styled = true }
    val interaction = press?.interaction ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Only the press animates (iOS `.animation(.easeOut(duration: 0.12), value: isPressed)`).
    val pressAlpha by animateFloatAsState(if (pressed) 0.85f else 1f, tween(120, easing = EaseOut), label = "press")
    val pressOffset by animateDpAsState(if (pressed) 1.dp else 0.dp, tween(120, easing = EaseOut), label = "press-offset")

    val height = when (size) {
        ButtonSize.regular -> 48.dp
        ButtonSize.modal -> 44.dp
        ButtonSize.compact -> 40.dp
        ButtonSize.header -> 36.dp
        ButtonSize.slim -> 32.dp
        ButtonSize.small -> 28.dp
    }
    // A link sits in the text's line: no inset, except the board's 32 links (web `h-8 px-3`).
    val padding = if (variant == ButtonVariant.link) (if (size == ButtonSize.slim) 12.dp else 0.dp) else when (size) {
        ButtonSize.header -> 14.dp
        ButtonSize.modal -> 20.dp
        ButtonSize.slim -> 12.dp
        ButtonSize.small -> 10.dp
        else -> 16.dp
    }
    val foreground = when (variant) {
        ButtonVariant.primary -> Theme.onBlue
        ButtonVariant.outline, ButtonVariant.link -> Theme.blue
        ButtonVariant.destructive -> Theme.coral
        ButtonVariant.ghost -> Theme.inkStrong
    }
    val background = when (variant) {
        ButtonVariant.primary -> Theme.blue
        ButtonVariant.outline -> Theme.surface
        ButtonVariant.destructive -> Theme.coral.copy(alpha = 0.1f)
        ButtonVariant.ghost, ButtonVariant.link -> Color.Transparent
    }
    val edge = when (variant) {
        ButtonVariant.outline -> Theme.blue
        ButtonVariant.destructive -> Theme.coral
        else -> null
    }
    val shape = RoundedCornerShape(Theme.buttonRadius)
    // text-sm font-medium, as on the web (sm: 0.8rem); a link is bold.
    val font = Theme.text(if (size == ButtonSize.small) 12.8 else 14, if (variant == ButtonVariant.link) FontWeight.Bold else FontWeight.SemiBold)
    Box(
        modifier
            .offset(y = pressOffset)
            .graphicsLayer { alpha = (if (isEnabled) 1f else 0.5f) * pressAlpha }
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .background(background, shape)
            .then(if (edge != null) Modifier.border(2.dp, edge, shape) else Modifier)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, enabled = isEnabled, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = padding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground, LocalTextStyle provides font) { label() }
    }
}

/**
 * A button with a title, an optional icon (leading, or trailing with `trailingIcon`) and a
 * working state (a spinner in place of the icon; the button waits, dimmed). `tint` paints a
 * ghost or link button's words (a destructive ghost: "Rechazar"; a grey link). Inside a module
 * header every button but a link or a ghost is the header's 36 size (`LocalHeaderButtons`).
 */
@Composable
fun PMButton(
    title: String,
    systemImage: String? = null,
    trailingIcon: String? = null,
    variant: ButtonVariant = ButtonVariant.primary,
    size: ButtonSize = ButtonSize.regular,
    fullWidth: Boolean = false,
    isWorking: Boolean = false,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val inHeader = LocalHeaderButtons.current
    val frame = if (inHeader && variant != ButtonVariant.link && variant != ButtonVariant.ghost) ButtonSize.header else size
    PMButtonStyle(variant, frame, fullWidth, modifier, onClick = action, enabled = enabled && !isWorking) {
        PMButtonContent(title, icon = systemImage, trailingIcon = trailingIcon, isWorking = isWorking, variant = variant, size = size, tint = tint)
    }
}

/**
 * A button's inside (its icon, words and working spinner): also the label of a pushed page's
 * link or of a menu drawn with `PMButtonStyle`.
 */
@Composable
fun PMButtonContent(
    title: String,
    icon: String? = null,
    trailingIcon: String? = null,
    isWorking: Boolean = false,
    variant: ButtonVariant = ButtonVariant.primary,
    size: ButtonSize = ButtonSize.regular,
    tint: Color? = null,
    modifier: Modifier = Modifier,
) {
    val spinnerTint = when (variant) {
        ButtonVariant.primary -> Theme.onBlue
        ButtonVariant.outline, ButtonVariant.link -> Theme.blue
        ButtonVariant.destructive -> Theme.coral
        ButtonVariant.ghost -> tint ?: Theme.inkStrong
    }
    val glyph = if (size == ButtonSize.small) 14.dp else 16.dp
    val inside = @Composable {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isWorking) {
                CircularProgressIndicator(color = spinnerTint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else if (icon != null) {
                Glyph(icon, glyph)
            }
            // A long label in a half-width footer button shrinks a little before it would cut.
            FittedText(title, Modifier.weight(1f, fill = false), minimumScaleFactor = 0.8f)
            if (trailingIcon != null) Glyph(trailingIcon, glyph)
        }
    }
    if (tint != null) CompositionLocalProvider(LocalContentColor provides tint, content = inside) else inside()
}

/**
 * The round icon control's look (web: the 36 round "More" trigger, a row's +, a ghost icon
 * button): the glyph in ink-soft on a white circle with a hairline, or bare with
 * `bordered = false`. The label of an `AppMenu` (⋯) as well as of `IconCircleButton`.
 */
@Composable
fun IconCircleLabel(
    systemImage: String = "ellipsis",
    size: Dp = 36.dp,
    tint: Color = Theme.inkSoft,
    /** false: no ring and no fill (web `variant="ghost" size="icon"`, Home's ⋯). */
    bordered: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .then(if (bordered) Modifier.background(Theme.surface, CircleShape).border(1.dp, Theme.borderSoft, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(systemImage, 16.dp, tint = tint)
    }
}

/** A small round icon control (a row's +, a corner ✕): not an action button. `label` is read out. */
@Composable
fun IconCircleButton(
    systemImage: String,
    label: String,
    tint: Color = Theme.inkSoft,
    size: Dp = 36.dp,
    bordered: Boolean = true,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val isEnabled = enabled && LocalIsEnabled.current
    IconCircleLabel(
        systemImage = systemImage,
        size = size,
        tint = tint,
        bordered = bordered,
        modifier = modifier
            .graphicsLayer { alpha = if (isEnabled) 1f else 0.5f }
            .plainButton(enabled = isEnabled, label = label, onClick = action),
    )
}
