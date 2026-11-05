package com.pawmiaw.business

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  Chips, pills and tags that wrap onto the next line when the row is full (iOS
//  FlowLayout.swift). The one wrapping layout of the app: `FlowLayout(spacing = 6.dp) { … }`.
//  Each piece keeps its own size (never wider than the row) and lines sit at the top.
//

@Composable
fun FlowLayout(
    spacing: Dp = 8.dp,
    /** Space between lines; the same as `spacing` unless given. */
    lineSpacing: Dp? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val bounded = constraints.hasBoundedWidth
        val width = if (bounded) constraints.maxWidth else Int.MAX_VALUE
        val gap = spacing.roundToPx()
        val lineGap = (lineSpacing ?: spacing).roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = if (bounded) width else Constraints.Infinity)) }

        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        var x = 0
        var y = 0
        var line = 0
        var widest = 0
        for (placeable in placeables) {
            val w = minOf(placeable.width, width)
            if (x > 0 && x + w > width) {
                y += line + lineGap
                x = 0
                line = 0
            }
            positions.add(x to y)
            x += w + gap
            line = maxOf(line, placeable.height)
            widest = maxOf(widest, x - gap)
        }
        val reportedWidth = (if (bounded) constraints.maxWidth else widest).coerceIn(constraints.minWidth, constraints.maxWidth)
        val reportedHeight = (y + line).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(reportedWidth, reportedHeight) {
            placeables.forEachIndexed { index, placeable ->
                val (px, py) = positions[index]
                placeable.placeRelative(px, py)
            }
        }
    }
}
