package com.pawmiaw.business

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  The web's OptionCardGrid (option-card-grid.tsx; iOS OptionCards.swift): a choice drawn as
//  cards, each with its icon in a round badge, a Fredoka title and a line under it. Chosen:
//  the card washes in its colour (22%), takes PAW Blue's 2 pt edge, its badge fills, and a
//  blue check sits on its corner. An option that can't be chosen is dashed and muted with the
//  reason under it. One column on a phone, two on a tablet (the cards of a row as tall as the
//  tallest). Services, record types, room kinds, catalog kinds.
//

class OptionCard<Value>(
    val value: Value,
    val title: String,
    val description: String? = null,
    val icon: String = "circle",
    /** The card's colour (web `accentColor`; MIAW yellow when not given). */
    val accent: Color = Theme.lime,
    /** Shown but not choosable, with why. */
    val unavailableReason: String? = null,
) {
    val id: Value get() = value
}

/** A choice that may be empty (`selection` null until a card is tapped). `invalid`: unchosen cards take the coral edge. */
@Composable
fun <Value> OptionCards(
    options: List<OptionCard<Value>>,
    selection: MutableState<Value?>,
    invalid: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val columns = if (LocalSizeClass.current == SizeClass.regular) 2 else 1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { option ->
                    val cell = Modifier.weight(1f).fillMaxHeight()
                    val reason = option.unavailableReason
                    if (reason != null) UnavailableCard(option, reason, cell) else ChoosableCard(option, selection, invalid, cell)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A choice that always has a value (iOS `OptionCards(options:selection:invalid:)` over a non-optional binding). */
@JvmName("OptionCardsRequired")
@Composable
fun <Value : Any> OptionCards(
    options: List<OptionCard<Value>>,
    selection: MutableState<Value>,
    invalid: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val optional = stateOf<Value?>({ selection.value }, { value -> if (value != null) selection.value = value })
    OptionCards(options, optional, invalid, modifier)
}

@Composable
private fun <Value> ChoosableCard(option: OptionCard<Value>, selection: MutableState<Value?>, invalid: Boolean, modifier: Modifier) {
    val chosen = selection.value == option.value
    val shape = RoundedCornerShape(Theme.boxRadius)
    val motion = tween<Color>(180, easing = EaseOut)
    val fill by animateColorAsState(if (chosen) Theme.wash(option.accent, 22.0) else Theme.surface, motion, label = "fill")
    val edge by animateColorAsState(if (chosen) Theme.blue else if (invalid) Theme.coral else Theme.borderSoft, motion, label = "edge")
    val badge by animateColorAsState(if (chosen) option.accent else Theme.wash(option.accent, 22.0), motion, label = "badge")
    Box(
        modifier.selectable(
            selected = chosen,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { selection.value = option.value },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(fill, shape)
                .border(2.dp, edge, shape)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                Glyph(option.icon, 20.dp, tint = if (chosen) Theme.blue else Theme.inkStrong)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(option.title, style = Theme.heading(16.8), color = Theme.inkStrong)
                option.description.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
            }
        }
        AnimatedVisibility(
            visible = chosen,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
            enter = scaleIn(tween(180, easing = EaseOut), initialScale = 0.5f) + fadeIn(tween(180, easing = EaseOut)),
            exit = scaleOut(tween(180, easing = EaseOut), targetScale = 0.5f) + fadeOut(tween(180, easing = EaseOut)),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(Theme.blue, CircleShape)
                    .border(2.dp, Theme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Glyph("check", 14.dp, tint = Theme.onBlue)
            }
        }
    }
}

@Composable
private fun <Value> UnavailableCard(option: OptionCard<Value>, reason: String, modifier: Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    val dash = Theme.borderSoft
    Column(
        modifier
            .background(Theme.surfaceMuted, shape)
            .dashedEdge(2.dp, dash, Theme.boxRadius)
            .semantics(mergeDescendants = true) {}
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.graphicsLayer { alpha = 0.6f },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).background(Theme.surface, CircleShape), contentAlignment = Alignment.Center) {
                Glyph(option.icon, 20.dp, tint = Theme.inkSoft)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(option.title, style = Theme.heading(16.8), color = Theme.inkStrong)
                option.description.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
            }
        }
        Text(reason, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
    }
}

/** A dashed edge drawn inside the bounds (SwiftUI `strokeBorder(style: StrokeStyle(lineWidth:, dash: [6, 5]))`). */
private fun Modifier.dashedEdge(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val w = width.toPx()
    val r = (radius.toPx() - w / 2).coerceAtLeast(0f)
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
    )
}
