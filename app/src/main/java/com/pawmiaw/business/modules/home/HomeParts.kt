package com.pawmiaw.business.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pawmiaw.business.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

//
//  HomeParts.kt (iOS HomeParts.swift)
//
//  Home's own arrangements of the kit's parts: a section of the board that
//  folds and remembers it (web `HomeStrip`, over the kit's `BoardStrip`), its
//  "Ver todo" line, a plain disc, the day keys, and ‹ Hoy › (the kit's
//  `DayStepper` with Home's wash away from today).
//
//  Android only, at the end: `HomeFullScreenCover` (`.fullScreenCover`) and `HomeSpinner`
//  (`ProgressView()`).
//

// MARK: - A section of the board

/**
 * One part of Home's day list (web home-strip.tsx): its band with the dot, the
 * label and the count, an optional link to its page, and the rows under it.
 * The band folds the part; the choice is remembered per part on this device.
 */
@Composable
fun HomeStrip(
    /** Names the part for the remembered choice (`board:now`, `grooming`, `stays`…). */
    storageKey: String,
    title: String,
    count: Int,
    dot: Color = Theme.blue,
    linkTitle: String? = null,
    link: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val key = "pawmiaw.provider.home-strip-collapsed:$storageKey"
    val folded = remember(key) { mutableStateOf(AppContext.defaults.getBoolean(key, false)) }
    LaunchedEffect(key) {
        snapshotFlow { folded.value }.drop(1).collect { value ->
            if (value) AppContext.defaults.edit().putBoolean(key, true).apply() else AppContext.defaults.edit().remove(key).apply()
        }
    }
    Column(modifier.fillMaxWidth()) {
        val hint = if (folded.value) LF("Show %@", title) else LF("Hide %@", title)
        BoardStrip(
            title = title, count = count, dot = dot, linkTitle = linkTitle, link = link, folded = folded,
            modifier = Modifier.semantics { onClick(label = hint) { folded.value = !folded.value; true } },
        )
        if (!folded.value) {
            content()
        }
        RowDivider()
    }
}

/** "Ver todo · 3 más" under a part Home shows capped (its page has every row). */
@Composable
fun HomeStripMore(more: Int, route: ProviderRoute, title: String? = null, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Column(modifier.fillMaxWidth()) {
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton { nav.push(route) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((title ?: L("See everything")) + (if (more > 0) " · " + LF("%d more", more) else ""), style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
            Glyph("chevron-right", 14.dp, tint = Theme.blue)
        }
    }
}

// MARK: - Small controls

/** A plain disc with an icon (the checklist's rows, the relocate banner): any fill, the ink the theme gives. */
@Composable
fun HomeDisc(
    icon: String,
    size: Dp = 36.dp,
    iconSize: Dp = 16.dp,
    fill: Color = Theme.surfaceMuted,
    ink: Color = Theme.inkStrong,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Glyph(icon, iconSize, tint = ink)
    }
}

// MARK: - The day

/** Days as the server writes them ("2026-11-04"), moved by whole days on the business's calendar. */
object HomeDayKey {
    fun shift(day: String, days: Int): String =
        runCatching { LocalDate.parse(day.take(10)).plusDays(days.toLong()).toString() }.getOrDefault(day)

    /** "Sáb 3 oct": weekday, day and month, first letter up. */
    fun short(day: String): String {
        val text = DateText.day(DateText.parse(day))
        return text.take(1).uppercase(appLocale) + text.drop(1)
    }

    /** "Martes, 4 de noviembre". */
    fun long(day: String?): String {
        val text = DateText.longDay(DateText.parse(day) ?: Instant.now())
        return text.take(1).uppercase(appLocale) + text.drop(1)
    }

    /** "Hoy", "Mañana", "Ayer", or null for another day. */
    fun name(day: String, today: String): String? {
        if (day == today) return L("Today")
        if (day == shift(today, 1)) return L("Tomorrow")
        if (day == shift(today, -1)) return L("Yesterday")
        return null
    }
}

/**
 * ‹ Hoy › on Home (web `HomeDaySwitcher`): the kit's `DayStepper`, a day back, a day ahead; on
 * another day the middle says which ("Vie 3 oct") on a blue wash and takes you back to today.
 */
@Composable
fun homeDayStepper(date: String, today: String, onChange: (String?) -> Unit, modifier: Modifier = Modifier) {
    fun go(days: Int) {
        val next = HomeDayKey.shift(date, days)
        onChange(if (next == today) null else next)
    }
    DayStepper(
        isToday = date == today,
        todayTitle = if (date == today) L("Today") else HomeDayKey.short(date),
        washesAway = true,
        modifier = modifier,
        previous = { go(-1) },
        today = { onChange(null) },
        next = { go(1) },
    )
}

// MARK: - Android: a cover and a spinner

private val pushEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** SwiftUI `.fullScreenCover`: the content over the whole app, rising from the bottom. */
@Composable
fun HomeFullScreenCover(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(1f) }
    LaunchedEffect(Unit) { offset.animateTo(0f, tween(320, easing = pushEasing)) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val height = constraints.maxHeight.toFloat()
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = offset.value * height }
                    .background(Theme.canvas)
            ) {
                CompositionLocalProvider(LocalDismiss provides onDismiss) { content() }
            }
        }
    }
}

/** SwiftUI's `ProgressView().controlSize(.small)`: a small spinner in the ink given. */
@Composable
fun HomeSpinner(color: Color = Theme.inkSoft, modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(18.dp), strokeWidth = 2.dp, color = color)
}
