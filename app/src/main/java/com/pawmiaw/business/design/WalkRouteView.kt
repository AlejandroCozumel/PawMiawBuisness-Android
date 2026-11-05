package com.pawmiaw.business

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

//
//  WalkRouteView.kt (iOS WalkRouteView.swift)
//
//  The route a walk took, as the server draws it (the report's `route`:
//  a box, line segments, distance, moving time and gaps). Shown on a
//  walk's report in Domicilio and on the pet's chart.
//
//  The iOS view already draws on a plain Canvas (no MapKit), so this is the same drawing on a
//  Compose Canvas: no map SDK, no tiles, no key.
//

/** What the route draws: the box the points arrive fitted to, the lines, km, minutes, gaps (iOS's private `Shape`). */
private data class WalkRouteShape(
    val box: Double,
    val lines: List<List<Offset>>,
    val km: Double,
    val minutes: Int,
    val gaps: Boolean,
)

/**
 * A line on a plain panel, a dot where it started and one where it ended. No tiles, no street map,
 * no coordinates — the shape arrives fitted to a box. A break in the line is a stretch not recorded.
 */
@Composable
fun WalkRouteView(route: JSONValue, modifier: Modifier = Modifier) {
    val shape = remember(route) { walkRouteShape(route) } ?: return
    val panel = RoundedCornerShape(16.dp)
    val blue = Theme.blue
    val surface = Theme.surface
    Column(
        modifier
            .fillMaxWidth()
            .clip(panel)
            .border(1.dp, Theme.borderSoft, panel),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(Theme.surfaceMuted)
                .semantics { contentDescription = L("Route of the walk drawn as a line") },
        ) {
            val side = min(size.width, size.height)
            val scale = (side / shape.box).toFloat()
            val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
            fun place(point: Offset) = Offset(origin.x + point.x * scale, origin.y + point.y * scale)
            for (line in shape.lines) {
                val path = Path()
                line.forEachIndexed { index, point ->
                    val at = place(point)
                    if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                }
                drawPath(path, color = blue, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val radius = max(5.dp.toPx(), side * 0.02f)
            shape.lines.firstOrNull()?.firstOrNull()?.let { first ->
                val at = place(first)
                drawCircle(surface, radius, at)
                drawCircle(blue, radius, at, style = Stroke(width = 4.dp.toPx()))
            }
            shape.lines.lastOrNull()?.lastOrNull()?.let { end ->
                val at = place(end)
                drawCircle(blue, radius, at)
                drawCircle(surface, radius, at, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L("Walk route").uppercase(),
                    style = brandText(11, FontWeight.ExtraBold).copy(letterSpacing = 1.sp),
                    color = Theme.inkSoft,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.weight(1f))
                // `.formatted(.number.precision(.fractionLength(0...2)))`, in the device's locale.
                val km = NumberFormat.getNumberInstance(Locale.getDefault()).apply { minimumFractionDigits = 0; maximumFractionDigits = 2 }.format(shape.km)
                Text(
                    LF("%@ km · %d min", km, shape.minutes),
                    style = Theme.text(14, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                    color = Theme.inkStrong,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            if (shape.gaps) {
                Text(L("Part of the walk wasn’t recorded"), style = Theme.captionFont, color = Theme.inkSoft)
            }
        }
    }
}

/** The route's lines (pairs of finite numbers, two points at least), box, km, minutes and gaps; null with no line. */
private fun walkRouteShape(route: JSONValue): WalkRouteShape? {
    val segments = route["segments"].array ?: return null
    val lines = segments.mapNotNull { segment ->
        val numbers = (segment.array ?: emptyList()).mapNotNull { it.double }
        val points = ArrayList<Offset>()
        var index = 0
        while (index + 1 < numbers.size) {
            if (numbers[index].isFinite() && numbers[index + 1].isFinite()) points.add(Offset(numbers[index].toFloat(), numbers[index + 1].toFloat()))
            index += 2
        }
        if (points.size >= 2) points else null
    }
    if (lines.isEmpty()) return null
    val box = route["box"].double?.takeIf { it > 0 } ?: 1000.0
    val km = ((route["distanceM"].double ?: 0.0) / 10).roundToInt() / 100.0
    val minutes = max(1, ((route["movingSeconds"].double ?: 0.0) / 60).roundToInt())
    return WalkRouteShape(box = box, lines = lines, km = km, minutes = minutes, gaps = route["hasGaps"].bool == true || lines.size > 1)
}
