package com.pawmiaw.business

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  The top of every business page, the web's `ProviderModuleHeader` (iOS ModuleHeader.swift,
//  provider-module-header.tsx): one card with the page's icon on its colour, the title, one
//  line of context, the page's main actions as 36 pt buttons, and the numbers that matter
//  today as tiles, two by two on a phone, in one row on an iPad. A wash of the page's colour
//  runs in from the left. It folds into one slim row (the numbers become chips): a phone
//  starts folded, a tablet open, and once someone folds or opens a page's header that choice
//  is remembered for the page (`storageKey`).
//
//  A page never draws its own title block or stat tiles: it passes them here.
//

/** `IconBadge.Ink`. */
object IconBadge {
    enum class Ink { onAccent, strong, accent }
}

/**
 * The round icon well every provider card and header uses (web `IconBadge`; the provider
 * workspace draws them round). `washPercent`: a wash of the accent (an empty state's well, a
 * card's icon) instead of the solid fill.
 */
@Composable
fun IconBadge(
    icon: String,
    accent: Color = Theme.blue,
    size: Dp = 44.dp,
    iconSize: Dp? = null,
    ink: IconBadge.Ink = IconBadge.Ink.onAccent,
    washPercent: Double? = null,
    modifier: Modifier = Modifier,
) {
    val inkColor = when (ink) {
        IconBadge.Ink.onAccent -> Theme.onBlue
        IconBadge.Ink.strong -> Theme.inkOnBright
        IconBadge.Ink.accent -> accent
    }
    Box(
        modifier
            .size(size)
            .then(
                if (washPercent != null) Modifier.background(Theme.wash(accent, washPercent), CircleShape)
                else Modifier.background(accent, CircleShape).border(1.dp, accent, CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, iconSize ?: (size * 0.45f), tint = inkColor)
    }
}

/**
 * One number in a module header (web `ProviderModuleStat`). `attention`: the number needs
 * acting on (low stock, requests), it reads red. `active`: a filter that is on. `muted`:
 * quieter, a step still to come. `action` makes the stat a filter. `accent`
 * `Color.Unspecified` is PAW Blue (iOS `Theme.blue`, which follows the theme).
 */
class ModuleStat(
    val id: String,
    val label: String,
    val value: String,
    val hint: String? = null,
    val icon: String,
    val accent: Color = Color.Unspecified,
    val attention: Boolean = false,
    val active: Boolean = false,
    val muted: Boolean = false,
    val action: (() -> Unit)? = null,
)

/** The stat's accent, PAW Blue unless it has its own. */
private val ModuleStat.tone: Color
    @Composable get() = if (accent == Color.Unspecified) Theme.blue else accent

/** Inside a module header: every `PMButton` is the header's 36 pt size (iOS `\.headerButtons`). */
val LocalHeaderButtons = compositionLocalOf { false }

private val foldEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * The page header. `media`: a photo in place of the icon (a pet, a person). `titleAccessory`:
 * badges beside the title (a client's status, a record's state, a patient's triage), wrapping
 * under the title on a phone. `actionsOnRow`: the actions get a line of their own under the
 * title (a page with many). `storageKey`: the key the folded state is remembered under (the page).
 * `actions`: the page's `PMButton`s (the trailing lambda).
 */
@Composable
fun ModuleHeader(
    icon: String,
    title: String,
    subtitle: String? = null,
    accent: Color = Theme.blue,
    media: (@Composable () -> Unit)? = null,
    titleAccessory: (@Composable () -> Unit)? = null,
    stats: List<ModuleStat> = emptyList(),
    actionsOnRow: Boolean = false,
    storageKey: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    ModuleHeaderBody(icon, title, subtitle, accent, media, titleAccessory, stats, actionsOnRow, storageKey, modifier, actions, null)
}

/** The page header with a `footer` under the stats, inside the same card (`actions` may be null). */
@Composable
fun ModuleHeader(
    icon: String,
    title: String,
    subtitle: String? = null,
    accent: Color = Theme.blue,
    media: (@Composable () -> Unit)? = null,
    titleAccessory: (@Composable () -> Unit)? = null,
    stats: List<ModuleStat> = emptyList(),
    actionsOnRow: Boolean = false,
    storageKey: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)?,
    footer: @Composable () -> Unit,
) {
    ModuleHeaderBody(icon, title, subtitle, accent, media, titleAccessory, stats, actionsOnRow, storageKey, modifier, actions, footer)
}

@Composable
private fun ModuleHeaderBody(
    icon: String,
    title: String,
    subtitle: String?,
    accent: Color,
    media: (@Composable () -> Unit)?,
    titleAccessory: (@Composable () -> Unit)?,
    stats: List<ModuleStat>,
    actionsOnRow: Boolean,
    storageKey: String?,
    modifier: Modifier,
    actions: (@Composable RowScope.() -> Unit)?,
    footer: (@Composable () -> Unit)?,
) {
    val wide = LocalSizeClass.current == SizeClass.regular
    val foldKey = storageKey?.let { "header-collapsed:$it" }
    // The fold chosen by a tap on this screen; null until then.
    var tapped by remember { mutableStateOf<Boolean?>(null) }

    // Folded or open (web provider-module-header.tsx): what the person chose for this page
    // (remembered once they fold or open it), else the screen's default — folded on a phone,
    // open on a tablet. Read while drawing, so a phone never shows it open first.
    val collapsed: Boolean = tapped
        ?: foldKey?.let { key -> if (AppContext.defaults.contains(key)) AppContext.defaults.getBoolean(key, false) else null }
        ?: !wide

    val setCollapsed: (Boolean) -> Unit = { value ->
        tapped = value
        if (foldKey != null) AppContext.defaults.edit().putBoolean(foldKey, value).apply()
    }

    val parts = HeaderParts(icon, title, subtitle, accent, media, titleAccessory, stats, actionsOnRow, actions, wide, collapsed, setCollapsed)
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .cardShadow(shape)
            .clip(shape)
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape),
    ) {
        AnimatedContent(
            targetState = collapsed,
            transitionSpec = {
                fadeIn(tween(340, easing = foldEasing)) togetherWith fadeOut(tween(200, easing = foldEasing)) using
                    SizeTransform(clip = true) { _, _ -> tween(340, easing = foldEasing) }
            },
            label = "module header fold",
        ) { folded ->
            if (folded) parts.Folded() else parts.Open()
        }
        footer?.invoke()
    }
}

/** What the open and folded faces of a header draw from. */
private class HeaderParts(
    val icon: String,
    val title: String,
    val subtitle: String?,
    val accent: Color,
    val media: (@Composable () -> Unit)?,
    val titleAccessory: (@Composable () -> Unit)?,
    val stats: List<ModuleStat>,
    val actionsOnRow: Boolean,
    val actions: (@Composable RowScope.() -> Unit)?,
    val wide: Boolean,
    val collapsed: Boolean,
    val setCollapsed: (Boolean) -> Unit,
) {
    // MARK: Open

    @Composable
    fun Open() {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .openWash(accent)
                    .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = if (wide) 16.dp else 12.dp),
            ) {
                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lead(56.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f, fill = false), style = Theme.heading(24), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                titleAccessory?.invoke()
                            }
                            subtitle.nonEmpty?.let { Text(it, style = brandText(14, FontWeight.Medium), color = Theme.inkSoft) }
                        }
                        Spacer(Modifier.width(12.dp))
                        if (!actionsOnRow && actions != null) ActionRow(scrolls = false)
                        ToggleButton()
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lead(36.dp)
                        Text(title, Modifier.weight(1f), style = Theme.heading(20), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        ToggleButton()
                    }
                    // iOS: 10 between the lines, less 4 for the accessory and the subtitle.
                    if (titleAccessory != null) {
                        Spacer(Modifier.height(6.dp))
                        FlowLayout(spacing = 6.dp) { titleAccessory.invoke() }
                    }
                    subtitle.nonEmpty?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = brandText(13, FontWeight.Medium), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!actionsOnRow && actions != null) {
                        Spacer(Modifier.height(10.dp))
                        ActionRow(scrolls = true, Modifier.bleed(16.dp))
                    }
                }
            }

            if (actionsOnRow && actions != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.borderSoft))
                ActionRow(scrolls = true, Modifier.fillMaxWidth().background(Theme.surface).padding(vertical = 10.dp))
            }

            if (stats.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.borderSoft))
                StatGrid()
            }
        }
    }

    @Composable
    fun Lead(size: Dp) {
        if (media != null) {
            // The photo clipped round, a ring of the surface over it.
            Box(Modifier.size(size).clip(CircleShape).border(if (wide) 4.dp else 2.dp, Theme.surface, CircleShape)) {
                media.invoke()
            }
        } else {
            IconBadge(
                icon = icon,
                accent = accent,
                size = size,
                iconSize = if (size >= 56.dp) 24.dp else 18.dp,
                modifier = Modifier.shadow(1.dp, CircleShape, clip = false, ambientColor = Theme.navy.copy(alpha = 0.08f), spotColor = Theme.navy.copy(alpha = 0.08f)),
            )
        }
    }

    @Composable
    fun ActionRow(scrolls: Boolean, modifier: Modifier = Modifier) {
        val content = actions ?: return
        CompositionLocalProvider(LocalHeaderButtons provides true) {
            if (scrolls) {
                Row(
                    modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            } else {
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
    }

    @Composable
    fun ToggleButton() {
        val turn by animateFloatAsState(if (collapsed) 180f else 0f, tween(340, easing = foldEasing), label = "header chevron")
        val label = if (collapsed) L("Show summary") else L("Hide summary")
        Box(
            Modifier
                .plainButton { setCollapsed(!collapsed) }
                .semantics { contentDescription = label }
                .size(36.dp)
                .background(Theme.surface, CircleShape)
                .border(1.dp, Theme.borderSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Glyph("chevron-up", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
    }

    // MARK: Numbers

    @Composable
    fun StatGrid() {
        val columns = if (wide) (if (stats.size >= 4) minOf(stats.size, 5) else stats.size) else (if (stats.size == 3) 3 else 2)
        val rows = stats.chunked(columns.coerceAtLeast(1))
        Column(Modifier.fillMaxWidth().background(Theme.borderSoft), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (stat in row) {
                        StatCell(stat = stat, dense = stats.size == 3 && !wide, wide = wide, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }

    // MARK: Folded

    @Composable
    fun Folded() {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        Brush.linearGradient(
                            0f to accent.copy(alpha = 0.10f),
                            0.35f to accent.copy(alpha = 0f),
                            start = Offset(0f, size.height * 0.37f),
                            end = Offset(size.width, size.height * 0.63f),
                        )
                    )
                }
                .padding(horizontal = if (wide) 20.dp else 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Row(
                        Modifier.plainButton { setCollapsed(false) },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Lead(if (wide) 40.dp else 36.dp)
                        Text(title, style = Theme.heading(20), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (wide && actions != null) ActionRow(scrolls = false)
                ToggleButton()
            }
            if (stats.isNotEmpty()) {
                Row(
                    Modifier.bleed(16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (stat in stats) StatChip(stat)
                }
            }
            if (!wide && actions != null) ActionRow(scrolls = true, Modifier.bleed(16.dp))
        }
    }
}

/** linear-gradient(105deg, accent 13% → accent 4% at 45% → surface at 80%). */
private fun Modifier.openWash(accent: Color): Modifier = drawBehind {
    drawRect(
        Brush.linearGradient(
            0f to accent.copy(alpha = 0.13f),
            0.45f to accent.copy(alpha = 0.04f),
            0.8f to accent.copy(alpha = 0f),
            start = Offset(0f, size.height * 0.37f),
            end = Offset(size.width, size.height * 0.63f),
        )
    )
}

/** A stat tile in the open header. */
@Composable
private fun StatCell(stat: ModuleStat, dense: Boolean, wide: Boolean, modifier: Modifier = Modifier) {
    val tone = stat.tone
    val action = stat.action
    val muted = if (stat.muted) 0.55f else 1f
    Column(
        modifier
            .then(if (action != null) Modifier.plainButton(onClick = action).semantics { selected = stat.active } else Modifier)
            .background(if (stat.active) Theme.wash(Theme.surfaceMuted, 70.0) else Theme.surface)
            .drawWithContent {
                drawContent()
                if (stat.active) {
                    val bar = 3.dp.toPx()
                    drawRect(tone, topLeft = Offset(0f, size.height - bar), size = Size(size.width, bar))
                }
            }
            .padding(horizontal = if (wide) 16.dp else 12.dp, vertical = if (wide) 14.dp else 8.dp),
    ) {
        Row(Modifier.alpha(muted), horizontalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 6.dp), verticalAlignment = Alignment.Top) {
            if (!dense) {
                IconBadge(icon = stat.icon, accent = tone, size = if (wide) 28.dp else 20.dp, iconSize = if (wide) 14.dp else 12.dp, ink = IconBadge.Ink.strong)
            }
            Text(
                stat.label.uppercase(),
                Modifier.padding(top = if (wide) 6.dp else 2.dp),
                style = brandText(if (wide) 10.5 else 9.6, FontWeight.ExtraBold).tracking(if (wide) 1.05 else 0.4),
                color = Theme.inkSoft,
                maxLines = if (wide) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FittedText(
            stat.value,
            modifier = Modifier.padding(top = if (wide) 8.dp else 4.dp).alpha(muted),
            style = Theme.heading(if (wide) 24 else 18),
            color = if (stat.attention) Theme.coral else Theme.inkStrong,
            minimumScaleFactor = 0.6f,
        )
        stat.hint.nonEmpty?.let {
            Text(
                it,
                Modifier.padding(top = 2.dp).alpha(muted),
                style = brandText(if (wide) 12 else 11.2),
                color = Theme.inkSoft,
                maxLines = if (wide) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A stat folded to one line: its dot, label and value. */
@Composable
private fun StatChip(stat: ModuleStat) {
    val tone = stat.tone
    val action = stat.action
    Row(
        Modifier
            .then(if (action != null) Modifier.plainButton(onClick = action) else Modifier)
            .alpha(if (stat.muted) 0.6f else 1f)
            .height(32.dp)
            .background(if (stat.active) Theme.wash(Theme.blue, 10.0) else Theme.surface, CapsuleShape)
            .border(1.dp, if (stat.active) Theme.blue else Theme.borderSoft, CapsuleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(tone, CircleShape))
        Text(stat.label, style = brandText(12, FontWeight.Bold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stat.value, style = Theme.heading(14), color = if (stat.attention) Theme.coral else Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
