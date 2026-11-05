package com.pawmiaw.business.hospital

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

//
//  HospitalVitalsTab.kt (iOS HospitalVitalsTab.swift)
//
//  The vitals (web hospital-vitals-tab.tsx): one sign over time (the
//  triage's quick vitals included), every reading with what is out of the
//  usual range for a dog or cat in red, a new reading, and striking one out.
//  The chart (Swift Charts on iOS) is drawn here on a Canvas: the line, the
//  points (the triage's bigger, in mango), the hour marks under it.
//

private class HospVitalPoint(val id: String, val at: Instant, val value: Double, val triage: Boolean)

@Composable
fun HospVitalsTab(model: HospDetailModel, detail: HospDetail, canCare: Boolean, modifier: Modifier = Modifier) {
    var metric by remember { mutableStateOf(HospMetric.temperatureC) }
    var adding by remember { mutableStateOf(false) }
    var striking by remember { mutableStateOf<HospVitals?>(null) }

    val zone = detail.zone
    val readings = (detail.vitals ?: emptyList()).filter { it.voided != true }
    val newestFirst = readings.sortedByDescending { it.recordedAt ?: "" }

    fun series(metric: HospMetric): List<HospVitalPoint> = readings.mapNotNull { entry ->
        val value = metric.value(entry) ?: return@mapNotNull null
        val at = DateText.parse(entry.recordedAt) ?: return@mapNotNull null
        HospVitalPoint(entry.id, at, value, entry.source == "triage")
    }.sortedBy { it.at }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HospMetric.entries.forEach { entry ->
                FilterChip(title = entry.title, count = series(entry).size, isSelected = metric == entry, tone = entry.hue.color) { metric = entry }
            }
        }
        if (canCare && detail.isAdmitted) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                PMButton(L("Record vitals"), systemImage = "plus", size = ButtonSize.compact) { adding = true }
            }
        }
        // No readings at all: one empty state, not an empty chart over an empty list.
        if (newestFirst.isEmpty()) {
            EmptyState(systemImage = "activity", title = L("No vitals yet."), accent = Theme.pink, panel = true)
        } else {
            HospVitalsChartCard(points = series(metric), metric = metric, zone = zone)
            Card(padding = 0.dp, spacing = 0.dp) {
                newestFirst.forEachIndexed { index, entry ->
                    if (index > 0) RowDivider()
                    HospReading(entry = entry, detail = detail, canCare = canCare) { striking = entry }
                }
            }
        }
    }
    if (adding) {
        AppSheet(onDismissRequest = { adding = false }) { HospAddVitalsSheet(model = model, detail = detail) }
    }
    striking?.let { entry ->
        AppSheet(onDismissRequest = { striking = null }) {
            ConfirmSheet(
                title = L("Strike out these vitals?"),
                message = LF("The reading of %@ leaves the chart. It stays on record as struck out.", HospFmt.dateTime(entry.recordedAt, zone)),
                confirmTitle = L("Strike out"),
                destructive = true,
            ) {
                try {
                    model.write("/vitals/${entry.id}/void")
                    Toast.show(L("Struck out."))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw hospRefusal(e, "Could not strike it out.")
                }
            }
        }
    }
}

@Composable
private fun HospVitalsChartCard(points: List<HospVitalPoint>, metric: HospMetric, zone: ZoneId) {
    Card {
        Row(Modifier.fillMaxWidth()) {
            Text(metric.title, Modifier.alignByBaseline(), style = Theme.heading(18), color = Theme.inkStrong)
            Spacer(Modifier.weight(1f))
            val last = points.lastOrNull()
            if (last != null) {
                val strong = Theme.inkStrong
                val soft = Theme.inkSoft
                Text(
                    buildAnnotatedString {
                        withStyle(Theme.heading(24).toSpanStyle().copy(color = strong)) { append(HospFmt.number(last.value, digits = 2)) }
                        withStyle(Theme.text(14).toSpanStyle().copy(color = soft)) { append(" ${metric.unit}") }
                    },
                    Modifier.alignByBaseline(),
                )
            }
        }
        if (points.isEmpty()) {
            EmptyState(systemImage = "activity", title = LF("No %@ readings yet.", metric.title.lowercase()), accent = Theme.pink)
        } else {
            val values = points.map { it.value }
            val low = values.minOrNull() ?: 0.0
            val high = values.maxOrNull() ?: 0.0
            val pad = if (metric == HospMetric.painScore) 1.0 else max(0.5, (high - low) * 0.2)
            val domain = if (metric == HospMetric.painScore) 0.0..10.0 else (low - pad)..(high + pad)
            HospVitalsChart(
                points = points,
                metric = metric,
                domain = domain,
                zone = zone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .semantics {
                        contentDescription = metric.title + ": " + points.joinToString(", ") { "${HospFmt.clock(DateText.iso(it.at), zone)} ${HospFmt.number(it.value, digits = 2)} ${metric.unit}" }
                    },
            )
            if (points.any { it.triage }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(Theme.mango, CircleShape))
                    Text(L("Triage"), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    }
}

/**
 * Swift Charts' `LineMark` (3 pt, monotone) and `PointMark` (symbol size 40, the triage's 80 in
 * mango) over the y domain, with `AxisMarks(desiredCount: 4)` of hours under it (grid line and
 * the clock) and the default value marks at the trailing edge.
 */
@Composable
private fun HospVitalsChart(points: List<HospVitalPoint>, metric: HospMetric, domain: ClosedFloatingPointRange<Double>, zone: ZoneId, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val line = metric.hue.color
    val mango = Theme.mango
    val grid = Theme.borderSoft
    val labelStyle = Theme.text(12).copy(color = Theme.inkSoft)
    val axisStyle = Theme.text(11).copy(color = Theme.inkSoft)
    Canvas(modifier) {
        val first = points.first().at.epochSecond.toDouble()
        val last = points.last().at.epochSecond.toDouble()
        // One reading: a short span around it, so it sits in the middle.
        val (start, end) = if (last > first) first to last else (first - 1800) to (first + 1800)
        val yTicks = hospNiceTicks(domain.start, domain.endInclusive, 5)
        val yLabels = yTicks.map { measurer.measure(HospFmt.number(it, digits = 2), axisStyle) }
        val trailing = (yLabels.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx()
        val xLabelHeight = measurer.measure("0", labelStyle).size.height + 6.dp.toPx()
        val plotLeft = 0f
        val plotRight = size.width - trailing
        val plotTop = 6.dp.toPx()
        val plotBottom = size.height - xLabelHeight
        val inset = 6.dp.toPx()
        fun x(seconds: Double): Float = plotLeft + inset + ((seconds - start) / (end - start)).toFloat() * (plotRight - plotLeft - inset * 2)
        fun y(value: Double): Float {
            val span = domain.endInclusive - domain.start
            val t = if (span == 0.0) 0.5 else (value - domain.start) / span
            return plotBottom - t.toFloat() * (plotBottom - plotTop)
        }

        // The value marks: a grid line and the number at the trailing edge.
        yTicks.forEachIndexed { index, tick ->
            val yy = y(tick)
            if (yy < plotTop - 0.5f || yy > plotBottom + 0.5f) return@forEachIndexed
            drawLine(grid, Offset(plotLeft, yy), Offset(plotRight, yy), strokeWidth = 0.5.dp.toPx())
            val label = yLabels[index]
            drawText(label, topLeft = Offset(plotRight + 6.dp.toPx(), yy - label.size.height / 2f))
        }
        // The hour marks under the chart.
        hospTimeTicks(start, end, zone, 4).forEach { tick ->
            val xx = x(tick.toDouble())
            if (xx < plotLeft || xx > plotRight) return@forEach
            drawLine(grid, Offset(xx, plotTop), Offset(xx, plotBottom), strokeWidth = 0.5.dp.toPx())
            val label = measurer.measure(HospFmt.clock(DateText.iso(Instant.ofEpochSecond(tick)), zone), labelStyle)
            val left = (xx - label.size.width / 2f).coerceIn(0f, max(0f, size.width - label.size.width))
            drawText(label, topLeft = Offset(left, plotBottom + 4.dp.toPx()))
        }

        val offsets = points.map { Offset(x(it.at.epochSecond.toDouble()), y(it.value)) }
        if (offsets.size > 1) {
            drawPath(hospMonotonePath(offsets), line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.forEachIndexed { index, point ->
            // Symbol size is the symbol's area in points: its side is the square root.
            val side = sqrt(if (point.triage) 80.0 else 40.0).toFloat()
            drawCircle(if (point.triage) mango else line, radius = side.dp.toPx() / 2f, center = offsets[index])
        }
    }
}

/** A monotone cubic through the points (Swift Charts `.interpolationMethod(.monotone)`, Fritsch–Carlson). */
private fun hospMonotonePath(p: List<Offset>): Path {
    val n = p.size
    val path = Path()
    path.moveTo(p[0].x, p[0].y)
    if (n == 2) {
        path.lineTo(p[1].x, p[1].y)
        return path
    }
    val dx = FloatArray(n - 1) { p[it + 1].x - p[it].x }
    val slope = FloatArray(n - 1) { if (dx[it] == 0f) 0f else (p[it + 1].y - p[it].y) / dx[it] }
    val tangent = FloatArray(n)
    tangent[0] = slope[0]
    tangent[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) {
        tangent[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) {
            tangent[i] = 0f
            tangent[i + 1] = 0f
            continue
        }
        val a = tangent[i] / slope[i]
        val b = tangent[i + 1] / slope[i]
        val h = a * a + b * b
        if (h > 9f) {
            val t = 3f / sqrt(h)
            tangent[i] = t * a * slope[i]
            tangent[i + 1] = t * b * slope[i]
        }
    }
    for (i in 0 until n - 1) {
        val third = dx[i] / 3f
        path.cubicTo(
            p[i].x + third, p[i].y + tangent[i] * third,
            p[i + 1].x - third, p[i + 1].y - tangent[i + 1] * third,
            p[i + 1].x, p[i + 1].y,
        )
    }
    return path
}

/** About `count` round values across a range (1, 2, 5 × 10ⁿ apart). */
private fun hospNiceTicks(low: Double, high: Double, count: Int): List<Double> {
    val span = high - low
    if (span <= 0 || span.isNaN()) return listOf(low)
    val raw = span / max(1, count - 1)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val out = mutableListOf<Double>()
    var tick = ceil(low / step) * step
    while (tick <= high + step * 1e-9) {
        out.add(if (abs(tick) < step * 1e-9) 0.0 else tick)
        tick += step
    }
    return out
}

/** About `count` hour marks across a span of seconds, on the clock's own hours and days. */
private fun hospTimeTicks(start: Double, end: Double, zone: ZoneId, count: Int): List<Long> {
    val span = end - start
    val steps = listOf(15 * 60L, 30 * 60L, 3600L, 2 * 3600L, 3 * 3600L, 6 * 3600L, 12 * 3600L, 86_400L, 2 * 86_400L, 7 * 86_400L, 14 * 86_400L, 30 * 86_400L)
    val step = steps.firstOrNull { span / it <= count } ?: steps.last()
    val dayStart = Instant.ofEpochSecond(start.toLong()).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
    val out = mutableListOf<Long>()
    var tick = dayStart
    if (step < 86_400L) {
        while (tick < start) tick += step
        while (tick <= end) {
            out.add(tick)
            tick += step
        }
    } else {
        // Whole days on the clock (a day can be 23 or 25 hours long).
        var date = Instant.ofEpochSecond(dayStart).atZone(zone).toLocalDate()
        val days = step / 86_400L
        while (date.atStartOfDay(zone).toEpochSecond() < start) date = date.plusDays(days)
        while (date.atStartOfDay(zone).toEpochSecond() <= end) {
            out.add(date.atStartOfDay(zone).toEpochSecond())
            date = date.plusDays(days)
        }
    }
    return out
}

@Composable
private fun HospReading(entry: HospVitals, detail: HospDetail, canCare: Boolean, onStrike: () -> Unit) {
    val species = detail.patient?.species
    val zone = detail.zone
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(HospFmt.dateTime(entry.recordedAt, zone), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                if (entry.source == "triage") Chip(text = L("Triage"), tone = Chip.Tone.custom(Theme.mango), percent = 26.0)
            }
            FlowLayout(spacing = 6.dp) {
                HospValue("°C", entry.temperatureC, warn = entry.temperatureC?.let { HospFmt.outOfRange(HospMetric.temperatureC, it, species) } ?: false)
                HospValue(L("HR"), entry.heartRate, warn = entry.heartRate?.let { HospFmt.outOfRange(HospMetric.heartRate, it, species) } ?: false)
                HospValue(L("RR"), entry.respiratoryRate, warn = entry.respiratoryRate?.let { HospFmt.outOfRange(HospMetric.respiratoryRate, it, species) } ?: false)
                HospValue(L("Pain"), entry.painScore, warn = entry.painScore?.let { HospFmt.outOfRange(HospMetric.painScore, it, species) } ?: false)
                HospValue("kg", entry.weightGrams?.let { hospRoundedHalfAway(it / 10) / 100 }, warn = false)
                entry.mucousMembranes?.let { gums -> HospValueText(L("Gums"), HospWords.mucous(gums), warn = false) }
                HospValue(L("CRT"), entry.crtSeconds, warn = false)
            }
            val by = listOfNotNull(entry.recordedByName, entry.notes).mapNotNull { it.nonEmpty }.joinToString(" · ")
            if (by.isNotEmpty()) Text(by, style = Theme.text(12), color = Theme.inkSoft)
        }
        if (canCare && detail.isAdmitted && entry.source != "triage") {
            IconCircleButton(systemImage = "undo-2", label = L("Strike out")) { onStrike() }
        }
    }
}

@Composable
private fun HospValue(label: String, number: Double?, warn: Boolean) {
    if (number != null) HospValueText(label, HospFmt.number(number, digits = 2), warn)
}

@Composable
private fun HospValueText(label: String, text: String, warn: Boolean) {
    val soft = Theme.inkSoft
    val ink = if (warn) Theme.destructive else Theme.inkStrong
    Text(
        buildAnnotatedString {
            withStyle(Theme.text(12, FontWeight.SemiBold).toSpanStyle().copy(color = soft)) { append("$label ") }
            withStyle(Theme.text(14, if (warn) FontWeight.ExtraBold else FontWeight.Bold).monospacedDigit().toSpanStyle().copy(color = ink)) { append(text) }
        },
        Modifier
            .background(if (warn) Theme.wash(Theme.destructive, 12.0) else Theme.surfaceMuted, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Swift `.rounded()`: to the nearest, halves away from zero. */
private fun hospRoundedHalfAway(value: Double): Double = if (value < 0) -Math.round(-value).toDouble() else Math.round(value).toDouble()

// MARK: - A new reading

@Composable
private fun HospAddVitalsSheet(model: HospDetailModel, detail: HospDetail) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val draft = remember { mutableStateOf(HospVitalsDraft()) }
    var invalid by remember { mutableStateOf<HospVitalsDraft.Field?>(null) }
    val recordedAt = remember { mutableStateOf(Instant.now()) }
    val notes = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    HospOnChange(draft.value) { invalid = null }

    fun save() {
        val result = draft.value.parsed()
        val problem = result.exceptionOrNull() as? HospVitalsError
        if (problem != null) {
            invalid = problem.field
            Toast.error(LF("Check %@.", HospVitalsDraft.label(problem.field)))
            return
        }
        val parsed = result.getOrNull()
        if (parsed == null) {
            Toast.error(L("Enter at least one value."))
            return
        }
        if (!form.check(listOf("recordedAt" to if (recordedAt.value.toEpochMilli() - System.currentTimeMillis() > 60_000) L("That time is in the future.") else null))) return
        val body: MutableMap<String, Any?> = parsed.toMutableMap()
        body["recordedAt"] = DateText.iso(recordedAt.value)
        body.hospSet("notes", notes.value.nonEmpty)
        saving = true
        scope.launch {
            try {
                model.write("/vitals", body = body)
                Toast.show(L("Vitals recorded."))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(hospError(e, "Could not record them."))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("Record vitals"),
        subtitle = L("Any of them; the rest can stay empty."),
        systemImage = "activity",
        accent = ModalAccent.pink,
        isDirty = draft.value != HospVitalsDraft() || notes.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Save vitals"),
        isWorking = saving,
        primaryIcon = "save",
        onPrimary = { save() },
    ) {
        HospVitalsFields(draft = draft, invalid = invalid)
        DateTimeField(label = L("Taken at"), date = recordedAt, zone = detail.zone, max = Instant.now(), key = "recordedAt", form = form)
        PMTextField(L("Note"), text = notes, multiline = true)
    }
}
