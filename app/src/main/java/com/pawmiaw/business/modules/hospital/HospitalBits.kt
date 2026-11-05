package com.pawmiaw.business.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

//
//  HospitalBits.kt (iOS HospitalBits.swift)
//
//  The hospital's small shared pieces (web hospital-bits.tsx, vitals-fields.tsx):
//  the triage, condition and status chips, the attention chips, a dose cell
//  on the sheet and its legend, the quick vitals fields, the pill choices and
//  the date fields its sheets use. Built on the Design pieces.
//

// MARK: - Chips

object HospTone {
    /** --triage-critical / urgent / stable, on the provider status scale. */
    @Composable @ReadOnlyComposable
    fun triage(priority: String?): Color = when (priority) {
        "critical" -> ProviderState.problem.color
        "urgent" -> ProviderState.waiting.color
        else -> ProviderState.done.color
    }

    /** conditionColor (lib/hospital.ts). */
    @Composable @ReadOnlyComposable
    fun condition(condition: String?): Color = when (condition) {
        "improving" -> Theme.blue
        "stable" -> Theme.aqua
        "guarded" -> Theme.mango
        else -> Theme.destructive
    }

    /** STATUS_TONE: the hospitalization's state colour. */
    @Composable @ReadOnlyComposable
    fun status(status: String?): Color = ProviderState.hospital(status).color
}

@Composable
fun HospTriageBadge(priority: String, modifier: Modifier = Modifier) {
    Chip(
        text = HospWords.triage(priority),
        tone = Chip.Tone.custom(HospTone.triage(priority)),
        icon = "siren",
        percent = if (priority == "critical") 14.0 else 22.0,
        ink = if (priority == "critical") Theme.destructive else Theme.inkStrong,
        ring = true,
        modifier = modifier,
    )
}

@Composable
fun HospConditionBadge(condition: String, modifier: Modifier = Modifier) {
    Chip(text = HospWords.condition(condition), tone = Chip.Tone.custom(HospTone.condition(condition)), dot = HospTone.condition(condition), percent = 24.0, modifier = modifier)
}

@Composable
fun HospStatusChip(status: String?, modifier: Modifier = Modifier) {
    Chip(text = HospWords.status(status), tone = Chip.Tone.custom(HospTone.status(status)), dot = HospTone.status(status), percent = 22.0, modifier = modifier)
}

@Composable
fun HospAllergyChip(allergies: List<String>, modifier: Modifier = Modifier) {
    if (allergies.isNotEmpty()) {
        Chip(text = LF("Allergy: %@", allergies.joinToString(", ")), tone = Chip.Tone.danger, icon = "triangle-alert", modifier = modifier)
    }
}

/** Due / late / missed counts as small chips (nothing when all are zero). */
@Composable
fun HospAttentionChips(attention: HospAttention?) {
    val a = attention
    if (a != null && a.m > 0) Chip(text = LP(a.m, "1 missed", "%d missed"), tone = Chip.Tone.danger, icon = "triangle-alert")
    if (a != null && a.l > 0) Chip(text = LP(a.l, "1 late", "%d late"), tone = Chip.Tone.warn, icon = "clock-3")
    if (a != null && a.d > 0) Chip(text = LP(a.d, "1 due", "%d due"), tone = Chip.Tone.info, icon = "syringe")
}

/** Rounds signed today (green) or still to sign (muted). */
@Composable
fun HospRoundsChip(signed: Boolean, modifier: Modifier = Modifier) {
    if (signed) {
        Chip(text = L("Rounds signed"), tone = Chip.Tone.custom(ProviderState.done.color), icon = "shield-check", percent = 18.0, modifier = modifier)
    } else {
        Chip(text = L("Rounds to sign"), tone = Chip.Tone.muted, icon = "shield-question-mark", modifier = modifier)
    }
}

// MARK: - The sheet's cells

/** A dose on the sheet: its time and where it stands (ProviderState.slot). */
@Composable
fun HospSlotCell(
    state: String,
    time: String,
    detail: String? = null,
    dashed: Boolean = false,
    modifier: Modifier = Modifier,
    action: (() -> Unit)? = null,
) {
    val tone = ProviderState.slot(state)
    val done = state == "given" || state == "given_late"
    val background = when (state) {
        "upcoming" -> Theme.surface
        "given", "given_late" -> tone.color
        "due" -> Theme.wash(tone.color, 38.0)
        "late" -> Theme.wash(tone.color, 40.0)
        "missed" -> Theme.wash(tone.color, 18.0)
        else -> Theme.wash(tone.color, 16.0)
    }
    val border = when (state) {
        "upcoming" -> Theme.borderSoft
        "given_late" -> ProviderState.waiting.color
        else -> tone.color
    }
    val shape = RoundedCornerShape(Theme.chipRadius)
    val lineWidth = if (state == "given_late") 3.dp else 2.dp
    val spoken = "$time · ${HospWords.slot(state)}"
    Column(
        modifier
            .defaultMinSize(minWidth = 68.dp, minHeight = 48.dp)
            .background(background, shape)
            // SwiftUI `.stroke`: the line sits on the edge, half of it outside.
            .drawWithContent {
                drawContent()
                val r = Theme.chipRadius.toPx()
                drawRoundRect(
                    color = border,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(
                        width = lineWidth.toPx(),
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
                    ),
                )
            }
            .plainButton(enabled = action != null, label = spoken) { action?.invoke() }
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            time,
            style = Theme.heading(14).copy(textDecoration = if (state == "not_given") TextDecoration.LineThrough else null),
            color = if (done) tone.ink else Theme.inkStrong,
        )
        FittedText(
            (detail ?: HospWords.slot(state)).uppercase(),
            style = brandText(9.9, FontWeight.ExtraBold).tracking(9.9 * 0.06),
            color = if (done) tone.ink else (if (state == "missed") Theme.destructive else Theme.inkSoft),
            minimumScaleFactor = 0.7f,
        )
    }
}

/** The legend under the sheet. */
@Composable
fun HospSlotLegend(modifier: Modifier = Modifier) {
    FlowLayout(spacing = 12.dp, modifier = modifier) {
        HospWords.slotStates.forEach { state ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val tone = ProviderState.slot(state)
                val shape = RoundedCornerShape(Theme.tickRadius)
                val fill = when (state) {
                    "upcoming" -> Theme.surface
                    "given", "given_late" -> tone.color
                    else -> Theme.wash(tone.color, 30.0)
                }
                val edge = when (state) {
                    "upcoming" -> Theme.borderSoft
                    "given_late" -> ProviderState.waiting.color
                    else -> tone.color
                }
                Box(
                    Modifier
                        .size(14.dp)
                        .background(fill, shape)
                        .border(2.dp, edge, shape)
                )
                Text(HospWords.slot(state), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - Vitals fields

/** Quick vitals: temperature, heart and breathing rate, pain, weight, capillary refill and gums. */
@Composable
fun HospVitalsFields(draft: MutableState<HospVitalsDraft>, invalid: HospVitalsDraft.Field? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val fields = listOf(
            HospVitalsInput(HospVitalsDraft.Field.temperatureC, draft.map({ it.temperatureC }, { d, v -> d.copy(temperatureC = v) }), "38.5", KeyboardType.Decimal),
            HospVitalsInput(HospVitalsDraft.Field.heartRate, draft.map({ it.heartRate }, { d, v -> d.copy(heartRate = v) }), "110", KeyboardType.Number),
            HospVitalsInput(HospVitalsDraft.Field.respiratoryRate, draft.map({ it.respiratoryRate }, { d, v -> d.copy(respiratoryRate = v) }), "24", KeyboardType.Number),
            HospVitalsInput(HospVitalsDraft.Field.painScore, draft.map({ it.painScore }, { d, v -> d.copy(painScore = v) }), "0–10", KeyboardType.Number),
            HospVitalsInput(HospVitalsDraft.Field.weightKg, draft.map({ it.weightKg }, { d, v -> d.copy(weightKg = v) }), "12.4", KeyboardType.Decimal),
            HospVitalsInput(HospVitalsDraft.Field.crtSeconds, draft.map({ it.crtSeconds }, { d, v -> d.copy(crtSeconds = v) }), "< 2", KeyboardType.Decimal),
        )
        HospGrid(fields, minimum = 140.dp, spacing = 12.dp) { field ->
            FormField(label = HospVitalsDraft.label(field.key)) {
                FieldChrome(invalid = invalid == field.key) {
                    PlainTextField(field.text, field.prompt, Modifier.weight(1f), keyboard = field.keyboard)
                }
            }
        }
        FormField(label = L("Gums")) {
            PillPicker(
                options = HospWords.mucousOptions.map { it to HospWords.mucous(it) },
                choice = stateOf({ draft.value.mucousMembranes.nonEmpty }, { draft.value = draft.value.copy(mucousMembranes = it ?: "") }),
                allowsNone = true,
            )
        }
    }
}

private class HospVitalsInput(val key: HospVitalsDraft.Field, val text: MutableState<String>, val prompt: String, val keyboard: KeyboardType)

// MARK: - Small helpers

/** A line of quiet text (a hint under a group). */
@Composable
fun HospHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = Theme.text(13), color = Theme.inkSoft)
}

/**
 * A group's heading on the board (web `SectionTitle`): a 28 disc of the
 * tone at 22% with the icon, the words in 14 extrabold, how many in a pill.
 */
@Composable
fun HospGroupTitle(
    title: String,
    systemImage: String? = null,
    count: Int? = null,
    tone: Color = Theme.blue,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (systemImage != null) {
            Box(Modifier.size(28.dp).background(Theme.wash(tone, 22.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(systemImage, 16.dp, tint = Theme.inkStrong)
            }
        }
        Text(title, style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong)
        if (count != null) {
            Text(
                "$count",
                Modifier.background(Theme.surfaceMuted, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                style = Theme.text(12, FontWeight.ExtraBold).monospacedDigit(),
                color = Theme.inkStrong,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * A titled group inside a settings card (web hospital-admin `GroupTitle`):
 * a 16 blue icon, the title in Fredoka 18, an optional hint and aside.
 */
@Composable
fun HospCardGroup(
    title: String,
    icon: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    aside: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, 16.dp, tint = Theme.blue)
            Text(title, style = Theme.heading(18), color = Theme.inkStrong)
            HospSpacer(8.dp)
            aside()
        }
        if (hint != null) Text(hint, style = Theme.text(12), color = Theme.inkSoft)
    }
}

/** The hospital's 60-second refresh while a ward screen is open (doses fall due). iOS `HospEveryMinute`. */
@Composable
fun HospEveryMinute(action: suspend () -> Unit) {
    val current by rememberUpdatedState(action)
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000)
            if (!isActive) return@LaunchedEffect
            current()
        }
    }
}

/**
 * One more pill after a sheet's client and pet (web `MedicalPill tone="soft"` inside
 * `ModalContextPills`): the triage, the place, the vet, an order.
 */
@Composable
fun hospContextPill(text: String, icon: String) {
    Chip(text = text, tone = Chip.Tone.muted, icon = icon, weight = FontWeight.SemiBold, size = Chip.Size.large)
}

// MARK: - Catalog search

/**
 * The Catalog searched inside a sheet (an order, a medicine to take home), by the one search
 * rule: the server searches 250 ms after typing stops, from 2 characters, 20 at a time as the
 * list scrolls, with the server's count. A pick clears the search.
 */
@Composable
fun HospCatalogSearch(
    prompt: String,
    itemType: String? = null,
    showsPrice: Boolean = true,
    /** Said when nothing matches (null: nothing is said). */
    emptyText: String? = null,
    modifier: Modifier = Modifier,
    onPick: (HospCatalogItem) -> Unit,
) {
    val text = remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<HospCatalogItem>() }
    var total by remember { mutableStateOf<Int?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val query = ClientSearch.query(text.value)

    suspend fun load(reset: Boolean) {
        val current = ClientSearch.query(text.value)
        if (current == null) {
            found.clear()
            total = null
            hasMore = false
            asked = null
            return
        }
        loading = true
        try {
            val offset = if (reset) 0 else found.size
            val page: Page<HospCatalogItem>? = try {
                HospAPI.catalogPage(search = current, itemType = itemType, offset = offset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            // A slower answer for an older search never replaces a newer one.
            if (current != ClientSearch.query(text.value)) return
            val items = page?.items ?: emptyList()
            if (reset) {
                found.clear()
                found.addAll(items)
            } else {
                found.addAll(items.filter { item -> found.none { it.id == item.id } })
            }
            total = page?.total
            hasMore = page?.hasMore ?: (items.size == ClientSearch.pageSize)
            asked = current
        } finally {
            loading = false
        }
    }

    Debounced(text.value) { load(reset = true) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(prompt = prompt, text = text, shape = SearchField.Shape.field)
        if (query != null) {
            if (asked != query || (loading && found.isEmpty())) {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Theme.blue, strokeWidth = 2.dp)
                }
            } else if (found.isEmpty()) {
                if (emptyText != null) HospHint(text = emptyText)
            } else {
                val count = total ?: found.size
                Text(
                    (if (count == 1) LF("%@ result", "1") else LF("%@ results", count.toString())).uppercase(),
                    style = Theme.text(12).tracking(1.4),
                    color = Theme.inkSoft,
                )
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    found.forEach { item ->
                        HospCatalogRow(item, showsPrice) {
                            onPick(item)
                            text.value = ""
                        }
                    }
                    LoadMoreRow(hasMore = hasMore, busy = loading) { scope.launch { load(reset = false) } }
                }
            }
        }
    }
}

@Composable
private fun HospCatalogRow(item: HospCatalogItem, showsPrice: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .plainButton(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.name, Modifier.weight(1f), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (showsPrice) {
            Text(
                if ((item.defaultUnitAmount ?: 0) > 0) Money.format(item.defaultUnitAmount, item.currency) else L("No price"),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
        }
    }
}

// MARK: - Layout helpers shared by the hospital's screens

/**
 * SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)], spacing:)`
 * inside a scrolling page or sheet: as many equal columns as fit at `minimum` wide, rows
 * `spacing` apart, each cell at the top of its row.
 */
@Composable
fun <T> HospGrid(
    items: List<T>,
    minimum: Dp,
    spacing: Dp,
    lineSpacing: Dp = spacing,
    modifier: Modifier = Modifier,
    cell: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(lineSpacing)) {
            items.chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    line.forEach { item -> Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) { cell(item) } }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** SwiftUI `.grayscale(1)`: the view drawn without colour. */
fun Modifier.hospGrayscale(on: Boolean): Modifier = if (!on) this else drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        canvas.restore()
    }
}

/** SwiftUI `.onChange(of: value)`: runs on each change after the first composition. */
@Composable
fun <T> HospOnChange(value: T, action: suspend (T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val previous = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (previous[0] != value) {
            previous[0] = value
            latest(value)
        }
    }
}

/** Swift `dict["key"] = optional`: a nil value leaves the key out. Only for optional extras (a note, a reason); a field the person cleared is sent as null. */
fun MutableMap<String, Any?>.hospSet(key: String, value: Any?) {
    if (value == null) remove(key) else this[key] = value
}

/** An accessibility label on a control (SwiftUI `.accessibilityLabel`). */
fun Modifier.hospLabel(text: String): Modifier = semantics { contentDescription = text }

/** SwiftUI `ProgressView().controlSize(.mini)`: a small spinner. */
@Composable
fun HospMiniSpinner(tint: Color = Theme.inkSoft, size: Dp = 12.dp) {
    CircularProgressIndicator(Modifier.size(size), color = tint, strokeWidth = 1.5.dp)
}

/** `Spacer(minLength:)` in a row: takes the free room, at least `min`. */
@Composable
fun RowScope.HospSpacer(min: Dp = 8.dp) {
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.width(min))
}
