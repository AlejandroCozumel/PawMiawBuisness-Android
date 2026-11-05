package com.pawmiaw.business

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

//
//  The web's pill controls (iOS Segmented.swift): the segmented switch (a view: Día/Semana/
//  Lista, chosen = PAW Blue; page tabs: Clientes/Pacientes, chosen = raised white), a page's
//  tab bar, the day stepper (‹ Hoy ›) and a pill that opens a picker (a date, a person).
//  Never a system segmented control on a business screen.
//

private val Capsule = RoundedCornerShape(50)
private val ChosenShadow = Theme.navy.copy(alpha = 0.1f)

/** iOS `Segmented.Look` and `Segmented.Option`. */
object Segmented {
    enum class Look {
        /** A view switch (provider.schedule.tsx): a 42 track, the chosen option in PAW Blue. */
        solid,
        /**
         * Page tabs and switches beside a 40 search (provider.clients.tsx, record-bits
         * `Segmented`, catalog-bits `CatalogSegmented`): a 40 track, the chosen option raised
         * in white, or filled with its tone's deep shade when it has one.
         */
        raised,
    }

    data class Option<Value>(
        val value: Value,
        val title: String,
        val icon: String? = null,
        /** A quiet count after the title (web `CatalogSegmented` count; hidden on a phone). */
        val count: Int? = null,
        /** A count bubble that stays on a phone (web record-bits `badge`): red when `attention`, a mango wash when it only waits. */
        val badge: Int? = null,
        val attention: Boolean = false,
        /** The option wears its own colour (web `tonedTabClass`): its icon on a tinted chip, filled with the tone's deep shade under white ink while chosen. */
        val tone: ChipTone? = null,
    )
}

@Composable
fun <Value> Segmented(
    options: List<Segmented.Option<Value>>,
    selection: MutableState<Value>,
    look: Segmented.Look = Segmented.Look.solid,
    label: String = "",
    /** Phones: the options share the full width and drop their plain icons (web `fill`). */
    fill: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val wide = LocalSizeClass.current == SizeClass.regular
    val stretches = fill && !wide
    Row(
        modifier
            .then(if (stretches) Modifier.fillMaxWidth() else Modifier)
            .background(Theme.surfaceMuted, Capsule)
            .border(1.dp, Theme.borderSoft, Capsule)
            .padding(4.dp)
            .semantics {
                isTraversalGroup = true
                if (label.isNotEmpty()) contentDescription = label
            },
        horizontalArrangement = Arrangement.spacedBy(if (look == Segmented.Look.solid) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val chosen = option.value == selection.value
            SegmentLabel(
                option = option,
                chosen = chosen,
                look = look,
                wide = wide,
                stretches = stretches,
                modifier = Modifier
                    .then(if (stretches) Modifier.weight(1f) else Modifier)
                    .plainSelectable(chosen, if (option.title.isEmpty()) (option.icon ?: "") else null) { selection.value = option.value },
            )
        }
    }
}

@Composable
private fun <Value> SegmentLabel(
    option: Segmented.Option<Value>,
    chosen: Boolean,
    look: Segmented.Look,
    wide: Boolean,
    stretches: Boolean,
    modifier: Modifier,
) {
    val solid = look == Segmented.Look.solid
    val tone = option.tone
    val ink = when {
        !chosen -> Theme.inkSoft
        tone != null -> Color.White
        solid -> Theme.onBlue
        else -> Theme.inkStrong
    }
    val fill = tone?.fill ?: if (solid) Theme.blue else Theme.surface
    val shown by animateFloatAsState(if (chosen) 1f else 0f, tween(150, easing = EaseOut), label = "chosen")
    val inkShown by animateColorAsState(ink, tween(150, easing = EaseOut), label = "ink")
    /** 14 in a view switch, 16 in page tabs, 10 when the options share a phone's width. */
    val gutter = if (stretches) 10.dp else if (solid) 14.dp else 16.dp
    val countFill = if (chosen) (if (tone != null) Color.White.copy(alpha = 0.2f) else Theme.wash(Theme.blue, 14.0)) else Theme.inkSoft.copy(alpha = 0.12f)
    Row(
        modifier
            .height(32.dp)
            .softShadow(Capsule, ChosenShadow.copy(alpha = ChosenShadow.alpha * shown), radius = 1.5.dp, y = 1.dp)
            .background(fill.copy(alpha = fill.alpha * shown), Capsule)
            .padding(start = if (tone != null && option.icon != null && wide) 8.dp else gutter, end = gutter),
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (stretches) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides inkShown, LocalTextStyle provides Theme.text(14, FontWeight.Bold)) {
            val icon = option.icon
            if (icon != null) {
                if (tone != null) ToneIcon(icon = icon, tone = tone, selected = chosen)
                else if (!stretches) Glyph(icon, 16.dp)
            }
            if (option.title.isNotEmpty()) {
                FittedText(option.title, Modifier.weight(1f, fill = false), minimumScaleFactor = if (stretches) 0.8f else 1f)
            }
            val count = option.count
            if (wide && count != null) {
                Text(
                    "$count",
                    style = Theme.text(11.2, FontWeight.ExtraBold).copy(fontFeatureSettings = "tnum"),
                    color = if (chosen && tone != null) Color.White else Theme.inkStrong,
                    modifier = Modifier.background(countFill, Capsule).padding(horizontal = 6.dp),
                )
            }
            val badge = option.badge
            if (badge != null && badge > 0) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .background(if (option.attention) Theme.destructive else Theme.wash(Theme.mango, 40.0), Capsule)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        style = Theme.text(10.9, FontWeight.ExtraBold).copy(fontFeatureSettings = "tnum"),
                        color = if (option.attention) Color.White else Theme.inkStrong,
                    )
                }
            }
        }
    }
}

/** iOS `ToneTabs.Look` and `ToneTabs.Tab`. */
object ToneTabs {
    enum class Look { solid, toned }

    data class Tab<Value>(
        val value: Value,
        val title: String,
        val icon: String,
        val tone: ChipTone = ChipTone.page,
        val count: Int? = null,
    )
}

/**
 * A page's tab bar (web: the tab bars of Estancias, Hospital, Estética, Paseos, Domicilio —
 * `.solid` — and of the Store, Pharmacy, Finance — `.toned`, store-tabs.tsx / `PharmacyTabs`):
 * a white capsule with a 2 pt edge hugging its tabs, scrolling sideways when they don't fit.
 * `.solid`: the open tab PAW Blue under its ink, a pink count bubble. `.toned`: each tab's icon
 * on a 28 chip of its tone, the open one filled with the tone's deep shade under white, the
 * count bubble swapping colours. On a phone the tabs that aren't open show only their icon
 * (and count).
 */
@Composable
fun <Value> ToneTabs(
    tabs: List<ToneTabs.Tab<Value>>,
    selection: MutableState<Value>,
    look: ToneTabs.Look = ToneTabs.Look.toned,
    /** `.solid` tabs: 40 (Estética, Paseos, Hospital, Estancias) or 44 (Domicilio, read on the street). */
    height: Dp = 40.dp,
    label: String = "",
    modifier: Modifier = Modifier,
) {
    val wide = LocalSizeClass.current == SizeClass.regular
    Box(
        modifier
            .horizontalScroll(rememberScrollState())
            .semantics {
                isTraversalGroup = true
                if (label.isNotEmpty()) contentDescription = label
            },
    ) {
        Row(
            Modifier
                .padding(1.dp)
                .background(Theme.surface, Capsule)
                .border(2.dp, Theme.borderSoft, Capsule)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (look == ToneTabs.Look.toned) 2.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val chosen = tab.value == selection.value
                val iconOnly = !chosen && !wide
                val spoken = tab.count?.let { if (it > 0) LF("%@, %d waiting", tab.title, it) else tab.title } ?: tab.title
                val tap = Modifier.plainSelectable(chosen, spoken) { selection.value = tab.value }
                if (look == ToneTabs.Look.toned) TonedTab(tab, chosen, iconOnly, tap) else SolidTab(tab, chosen, iconOnly, height, tap)
            }
        }
    }
}

@Composable
private fun <Value> TonedTab(tab: ToneTabs.Tab<Value>, chosen: Boolean, iconOnly: Boolean, modifier: Modifier) {
    val shown by animateFloatAsState(if (chosen) 1f else 0f, tween(150, easing = EaseOut), label = "chosen")
    val fill = tab.tone.fill
    Row(
        modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .softShadow(Capsule, ChosenShadow.copy(alpha = ChosenShadow.alpha * shown), radius = 1.5.dp, y = 1.dp)
            .background(fill.copy(alpha = fill.alpha * shown), Capsule)
            .padding(start = 10.dp, end = if (iconOnly) 10.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (chosen) Color.White else Theme.inkSoft, LocalTextStyle provides Theme.text(14, FontWeight.Bold)) {
            ToneIcon(icon = tab.icon, tone = tab.tone, selected = chosen, size = 28.dp, glyph = 16.dp)
            if (!iconOnly) Text(tab.title, maxLines = 1)
            val count = tab.count
            if (count != null && count > 0) ToneCount(count = count, tone = tab.tone, selected = chosen)
        }
    }
}

@Composable
private fun <Value> SolidTab(tab: ToneTabs.Tab<Value>, chosen: Boolean, iconOnly: Boolean, height: Dp, modifier: Modifier) {
    val shown by animateFloatAsState(if (chosen) 1f else 0f, tween(150, easing = EaseOut), label = "chosen")
    val fill = Theme.blue
    Row(
        modifier
            .heightIn(min = height)
            .softShadow(Capsule, ChosenShadow.copy(alpha = ChosenShadow.alpha * shown), radius = 1.5.dp, y = 1.dp)
            .background(fill.copy(alpha = fill.alpha * shown), Capsule)
            .padding(horizontal = if (iconOnly) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (chosen) Theme.onBlue else Theme.inkSoft, LocalTextStyle provides Theme.text(14, FontWeight.Bold)) {
            Glyph(tab.icon, 16.dp)
            if (!iconOnly) Text(tab.title, maxLines = 1)
            val count = tab.count
            if (count != null && count > 0) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .background(Theme.pink, Capsule)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", style = Theme.text(12, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), color = Theme.inkOnBright)
                }
            }
        }
    }
}

/**
 * ‹ Hoy › (web `HomeDaySwitcher`, home-day.tsx, and the Schedule's date navigator): one 42
 * pill, 32 blue arrows, the middle "Today" grey while it is today. Away from today the middle
 * names the day (`todayTitle`) and takes you back; with `washesAway` (Home) it sits on a PAW
 * Blue wash, as the web's Home does, else it is plain blue text (Agenda).
 */
@Composable
fun DayStepper(
    isToday: Boolean,
    todayTitle: String = L("Today"),
    /** Home: away from today the middle is a blue-washed capsule, at least 88 wide. */
    washesAway: Boolean = false,
    modifier: Modifier = Modifier,
    previous: () -> Unit,
    today: () -> Unit,
    next: () -> Unit,
) {
    Row(
        modifier
            .background(Theme.surface, Capsule)
            .border(1.dp, Theme.borderSoft, Capsule)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperArrow("chevron-left", L("Previous day"), previous)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            Modifier
                .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
                .height(32.dp)
                .then(if (washesAway) Modifier.defaultMinSize(minWidth = 88.dp) else Modifier)
                .background(if (washesAway && !isToday) Theme.wash(Theme.blue, 12.0) else Color.Transparent, Capsule)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !isToday && LocalIsEnabled.current,
                    onClickLabel = if (isToday) null else L("Back to today"),
                    role = Role.Button,
                    onClick = today,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(todayTitle, style = Theme.text(14, FontWeight.Bold), color = if (isToday) Theme.inkSoft else Theme.blue, maxLines = 1)
        }
        StepperArrow("chevron-right", L("Next day"), next)
    }
}

@Composable
private fun StepperArrow(icon: String, label: String, action: () -> Unit) {
    Box(Modifier.size(32.dp).plainButton(label = label, onClick = action), contentAlignment = Alignment.Center) {
        Glyph(icon, 16.dp, tint = Theme.blue)
    }
}

/**
 * A pill that shows a value and opens a picker: a date, a person, a sort (web: the
 * rounded-full select and date triggers). Use as an `AppMenu` label, or `PillDatePicker` for a
 * day.
 */
@Composable
fun PillField(
    text: String,
    icon: String? = "chevron-down",
    leadingIcon: String? = null,
    placeholder: Boolean = false,
    height: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(height)
            .background(Theme.surface, Capsule)
            .border(1.dp, Theme.borderSoft, Capsule)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Glyph(leadingIcon, 16.dp, tint = Theme.inkSoft)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = Theme.text(15, FontWeight.SemiBold),
            color = if (placeholder) Theme.inkSoft else Theme.inkStrong,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        if (icon != null) Glyph(icon, 16.dp, tint = Theme.inkSoft)
    }
}

/**
 * A day chosen from a pill: the pill shows the day (`text`), a tap opens the calendar
 * (`DayPickerSheet`) on `zone`'s clock (the device's unless given); a new day keeps the time.
 */
@Composable
fun PillDatePicker(
    text: String,
    date: MutableState<Instant>,
    zone: ZoneId? = null,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val clock = zone ?: ZoneId.systemDefault()
    PillField(text = text, icon = "calendar", modifier = modifier.plainButton { picking = true })
    if (picking) {
        val local = date.value.atZone(clock)
        DayPickerSheet(
            initial = local.toLocalDate(),
            onPick = { day -> date.value = local.with(day).toInstant() },
            onDismiss = { picking = false },
        )
    }
}

/** A `.buttonStyle(.plain)` choice: dims while pressed, says whether it is the chosen one. */
private fun Modifier.plainSelectable(selected: Boolean, label: String?, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    this
        .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
        .then(if (!label.isNullOrEmpty()) Modifier.semantics { contentDescription = label } else Modifier)
        .selectable(selected = selected, interactionSource = interaction, indication = null, enabled = LocalIsEnabled.current, role = Role.Tab, onClick = onClick)
}
