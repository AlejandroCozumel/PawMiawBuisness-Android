package com.pawmiaw.business

import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

//
//  The app's date and time fields, on the business's clock (iOS DateFields.swift):
//  - `DayField`: a calendar day as "yyyy-MM-dd" (a stay's nights, a lot's expiry),
//    optionally empty and clearable.
//  - `DateTimeField`: a moment (a dose given, an admission).
//  - `OptionalDateField`: a moment that may stay empty until chosen.
//  - `TimeField`: an "HH:mm" picked from the day's quarter hours.
//
//  iOS draws the compact system DatePicker: the date and the time as small grey pills that
//  open a calendar / a clock. Here the pills are the same (`CompactDatePicker`, SwiftUI's
//  `DatePicker("", selection:, in:, displayedComponents:)` for any screen) and open the
//  Material calendar or clock in a sheet (`DayPickerSheet`, `TimePickerSheet`), in the app's
//  colours, type and language. A day applies as it is tapped; a time with "Done".
//

/** SwiftUI `DatePickerComponents`: what a date picker asks for (`setOf(date, hourAndMinute)`). */
enum class DatePickerComponent { date, hourAndMinute }

/** "yyyy-MM-dd" ↔ a calendar day, read without a time zone shift. */
private object DayKey {
    fun date(day: String): LocalDate? = runCatching { LocalDate.parse(day.take(10)) }.getOrNull()
    fun key(date: LocalDate): String = date.toString()
    val today: String get() = DateText.dayKey()
}

@Composable
fun DayField(
    label: String,
    day: MutableState<String>,
    min: String? = null,
    max: String? = null,
    key: String? = null,
    form: FormState? = null,
    hint: String? = null,
    optional: Boolean = false,
    /** Empty is allowed: shows "Choose the date" and a clear button. */
    clearable: Boolean = false,
    emptyTitle: String = L("Choose the date"),
    /** With `clearable`, the ✕ that empties it again (off for a required day that starts empty). */
    showsClear: Boolean = true,
    /** The red * (web RequiredMark). */
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lower = min?.let(DayKey::date)
    val upperRaw = max?.let(DayKey::date)
    val upper = if (lower != null && upperRaw != null && upperRaw < lower) lower else upperRaw
    var picking by remember { mutableStateOf(false) }
    FormField(label, key = key, hint = hint, form = form, optional = optional, required = required, modifier = modifier) {
        FieldChrome(invalid = key?.let { form?.error(it) } != null) {
            Glyph("calendar", 16.dp, tint = Theme.inkSoft)
            Spacer(Modifier.width(8.dp))
            if (day.value.isEmpty() && clearable) {
                Text(
                    emptyTitle,
                    color = Theme.blueText,
                    modifier = Modifier.plainButton {
                        val today = DayKey.today
                        day.value = min?.let { maxOf(it, today) } ?: today
                        if (key != null) form?.clear(key)
                    },
                )
                Spacer(Modifier.weight(1f))
            } else {
                DatePill(dayText(DayKey.date(day.value) ?: DateText.today()), active = picking) { picking = true }
                Spacer(Modifier.weight(1f))
                if (clearable && showsClear) ClearMark { day.value = "" }
            }
        }
    }
    if (picking) {
        DayPickerSheet(
            initial = DayKey.date(day.value) ?: DateText.today(),
            min = lower,
            max = upper,
            onPick = {
                day.value = DayKey.key(it)
                if (key != null) form?.clear(key)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
fun DateTimeField(
    label: String,
    date: MutableState<Instant>,
    zone: ZoneId? = null,
    min: Instant? = null,
    max: Instant? = null,
    components: Set<DatePickerComponent> = setOf(DatePickerComponent.date, DatePickerComponent.hourAndMinute),
    key: String? = null,
    form: FormState? = null,
    hint: String? = null,
    /** The red * (web RequiredMark). */
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FormField(label, key = key, hint = hint, form = form, required = required, modifier = modifier) {
        FieldChrome(invalid = key?.let { form?.error(it) } != null) {
            CompactDatePicker(
                selection = stateOf({ date.value }, { date.value = it; if (key != null) form?.clear(key) }),
                min = min,
                max = max,
                components = components,
                zone = zone ?: Workspace.shared.timeZone,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun OptionalDateField(
    label: String,
    date: MutableState<Instant?>,
    placeholder: String = L("Choose the date"),
    zone: ZoneId? = null,
    min: Instant? = null,
    max: Instant? = null,
    components: Set<DatePickerComponent> = setOf(DatePickerComponent.date),
    /** Where the picker starts when chosen (clamped to min/max). */
    defaultDate: Instant = Instant.now(),
    key: String? = null,
    form: FormState? = null,
    hint: String? = null,
    optional: Boolean = true,
    /** The red * (web RequiredMark). */
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FormField(label, key = key, hint = hint, form = form, optional = optional, required = required, modifier = modifier) {
        FieldChrome(invalid = key?.let { form?.error(it) } != null) {
            val value = date.value
            if (value != null) {
                CompactDatePicker(
                    selection = stateOf({ value }, { date.value = it; if (key != null) form?.clear(key) }),
                    min = min,
                    max = max,
                    components = components,
                    zone = zone ?: Workspace.shared.timeZone,
                )
                Spacer(Modifier.weight(1f))
                ClearMark { date.value = null }
            } else {
                Row(
                    Modifier.weight(1f).plainButton {
                        date.value = defaultDate.clamped(min, max)
                        if (key != null) form?.clear(key)
                    },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("calendar", 16.dp, tint = Theme.blueText)
                    Text(placeholder, color = Theme.inkSoft)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** iOS `TimeField`'s helpers. */
object TimeField {
    fun minutes(hhmm: String): Int? {
        val parts = hhmm.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return null
        return parts[0] * 60 + parts[1]
    }

    fun hhmm(minutes: Int): String = String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    fun times(from: String, to: String, step: Int = 15): List<String> {
        val start = minutes(from) ?: return emptyList()
        val end = minutes(to) ?: return emptyList()
        if (step <= 0 || start > end) return emptyList()
        return (start..end step step).map(::hhmm)
    }

    /** "14:30" → "2:30 p. m." in the app's language. */
    fun clock(hhmm: String): String {
        val total = minutes(hhmm) ?: return hhmm
        val moment = LocalDate.of(2000, 1, 1).atStartOfDay().plusMinutes(total.toLong()).toInstant(ZoneOffset.UTC)
        return DateText.time(moment, ZoneOffset.UTC)
    }
}

/** An "HH:mm" from the day's quarter hours (`from`…`to`), shown on the app's clock style. */
@Composable
fun TimeField(
    label: String,
    value: MutableState<String>,
    from: String = "06:00",
    to: String = "22:00",
    stepMinutes: Int = 15,
    allowsEmpty: Boolean = false,
    emptyTitle: String = L("No set time"),
    key: String? = null,
    form: FormState? = null,
    optional: Boolean = false,
    /** The red * (web RequiredMark). */
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val options = TimeField.times(from, to, stepMinutes).map { it to TimeField.clock(it) }.toMutableList()
    if (value.value.isNotEmpty() && options.none { it.first == value.value }) options.add(0, value.value to TimeField.clock(value.value))
    if (allowsEmpty) options.add(0, "" to emptyTitle)
    MenuField(label, options = options, selection = value, key = key, form = form, optional = optional, required = required, modifier = modifier)
}

/**
 * SwiftUI's compact `DatePicker("", selection:, in: min...max, displayedComponents:)`: the
 * date and/or the time as grey pills on `zone`'s clock; each opens its picker in a sheet.
 * A new day keeps the time of day, a new time keeps the day; both stay within min…max.
 */
@Composable
fun CompactDatePicker(
    selection: MutableState<Instant>,
    min: Instant? = null,
    max: Instant? = null,
    components: Set<DatePickerComponent> = setOf(DatePickerComponent.date, DatePickerComponent.hourAndMinute),
    zone: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
) {
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val moment = selection.value
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (DatePickerComponent.date in components) {
            DatePill(DateText.format(moment, "dMMMyyyy", zone), active = pickingDay) { pickingDay = true }
        }
        if (DatePickerComponent.hourAndMinute in components) {
            DatePill(DateText.time(moment, zone), active = pickingTime) { pickingTime = true }
        }
    }
    if (pickingDay) {
        val local = moment.atZone(zone)
        DayPickerSheet(
            initial = local.toLocalDate(),
            min = min?.atZone(zone)?.toLocalDate(),
            max = max?.atZone(zone)?.toLocalDate(),
            onPick = { day -> selection.value = local.with(day).toInstant().clamped(min, max) },
            onDismiss = { pickingDay = false },
        )
    }
    if (pickingTime) {
        val local = moment.atZone(zone)
        TimePickerSheet(
            initial = local.toLocalTime(),
            onPick = { time -> selection.value = local.with(time).toInstant().clamped(min, max) },
            onDismiss = { pickingTime = false },
        )
    }
}

/**
 * The calendar in a sheet (iOS's date popover): the month in the app's language, days outside
 * min…max greyed; a tap on a day picks it and closes the sheet.
 */
@Composable
fun DayPickerSheet(
    initial: LocalDate,
    min: LocalDate? = null,
    max: LocalDate? = null,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismissRequest = onDismiss) {
        val dismiss = LocalDismiss.current
        val state = remember {
            val firstYear = maxOf(DatePickerDefaults.YearRange.first, min?.year ?: DatePickerDefaults.YearRange.first)
            val lastYear = maxOf(firstYear, minOf(DatePickerDefaults.YearRange.last, max?.year ?: DatePickerDefaults.YearRange.last))
            DatePickerState(
                locale = appLocale,
                initialSelectedDateMillis = initial.toEpochDay() * DAY_MILLIS,
                yearRange = firstYear..lastYear,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val day = LocalDate.ofEpochDay(Math.floorDiv(utcTimeMillis, DAY_MILLIS))
                        return (min == null || day >= min) && (max == null || day <= max)
                    }

                    override fun isSelectableYear(year: Int): Boolean =
                        (min == null || year >= min.year) && (max == null || year <= max.year)
                },
            )
        }
        LaunchedEffect(state) {
            snapshotFlow { state.selectedDateMillis }.drop(1).collect { millis ->
                if (millis != null) {
                    onPick(LocalDate.ofEpochDay(Math.floorDiv(millis, DAY_MILLIS)))
                    dismiss()
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 20.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PickerTheme {
                DatePicker(
                    state = state,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    colors = DatePickerDefaults.colors(
                        containerColor = Color.Transparent,
                        weekdayContentColor = Theme.inkSoft,
                        navigationContentColor = Theme.inkStrong,
                        yearContentColor = Theme.inkStrong,
                        currentYearContentColor = Theme.blue,
                        selectedYearContentColor = Theme.onBlue,
                        selectedYearContainerColor = Theme.blue,
                        dayContentColor = Theme.inkStrong,
                        disabledDayContentColor = Theme.inkSoft.copy(alpha = 0.38f),
                        selectedDayContentColor = Theme.onBlue,
                        selectedDayContainerColor = Theme.blue,
                        todayContentColor = Theme.blue,
                        todayDateBorderColor = Theme.blue,
                        dividerColor = Theme.borderSoft,
                    ),
                )
            }
        }
    }
}

/** The clock in a sheet (iOS's time popover), on the device's 12/24-hour style; "Done" picks it. */
@Composable
fun TimePickerSheet(
    initial: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismissRequest = onDismiss) {
        val dismiss = LocalDismiss.current
        val context = LocalContext.current
        val state = remember { TimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context)) }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PickerTheme {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Theme.surfaceMuted,
                        clockDialSelectedContentColor = Theme.onBlue,
                        clockDialUnselectedContentColor = Theme.inkStrong,
                        selectorColor = Theme.blue,
                        containerColor = Color.Transparent,
                        periodSelectorBorderColor = Theme.borderSoft,
                        periodSelectorSelectedContainerColor = Theme.blueWash,
                        periodSelectorUnselectedContainerColor = Color.Transparent,
                        periodSelectorSelectedContentColor = Theme.blueStrong,
                        periodSelectorUnselectedContentColor = Theme.inkSoft,
                        timeSelectorSelectedContainerColor = Theme.blueWash,
                        timeSelectorUnselectedContainerColor = Theme.surfaceMuted,
                        timeSelectorSelectedContentColor = Theme.blueStrong,
                        timeSelectorUnselectedContentColor = Theme.inkStrong,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PMButton(L("Cancel"), variant = ButtonVariant.outline, size = ButtonSize.modal, fullWidth = true, modifier = Modifier.weight(1f)) { dismiss() }
                PMButton(L("Done"), size = ButtonSize.modal, fullWidth = true, modifier = Modifier.weight(1f)) {
                    onPick(LocalTime.of(state.hour, state.minute))
                    dismiss()
                }
            }
        }
    }
}

private const val DAY_MILLIS = 86_400_000L

private fun Instant.clamped(min: Instant?, max: Instant?): Instant {
    var value = this
    if (min != null && value < min) value = min
    if (max != null && value > max) value = max
    return value
}

/** "4 nov 2026" (iOS's medium date). */
private fun dayText(day: LocalDate): String =
    DateText.format(day.atTime(12, 0).toInstant(ZoneOffset.UTC), "dMMMyyyy", ZoneOffset.UTC)

/** The compact picker's grey pill (iOS `tertiarySystemFill`); blue while its picker is open. */
private val DatePillFill = SchemeColor(Color(118, 118, 128).copy(alpha = 0.12f), Color(118, 118, 128).copy(alpha = 0.24f))

@Composable
private fun DatePill(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Theme.text(16),
        color = if (active) Theme.blue else Theme.inkStrong,
        maxLines = 1,
        modifier = Modifier
            .background(DatePillFill.resolve(), RoundedCornerShape(Theme.tickRadius))
            .plainButton(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** iOS `xmark.circle.fill` in ink-soft: "Clear the date". */
@Composable
private fun ClearMark(onClick: () -> Unit) {
    Box(
        Modifier
            .size(18.dp)
            .background(Theme.inkSoft, CircleShape)
            .plainButton(label = L("Clear the date"), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph("x", 11.dp, tint = Theme.inputFill)
    }
}

/** Material's pickers in the app's colours, type (Nunito Sans) and language. */
@Composable
private fun PickerTheme(content: @Composable () -> Unit) {
    val dark = LocalIsDark.current
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = Theme.blue,
        onPrimary = Theme.onBlue,
        primaryContainer = Theme.blueWash,
        onPrimaryContainer = Theme.inkStrong,
        secondaryContainer = Theme.blueWash,
        onSecondaryContainer = Theme.inkStrong,
        surface = Theme.canvas,
        onSurface = Theme.inkStrong,
        onSurfaceVariant = Theme.inkSoft,
        surfaceContainerHigh = Theme.surface,
        surfaceContainerHighest = Theme.surfaceMuted,
        outline = Theme.borderSoft,
        outlineVariant = Theme.borderSoft,
    )
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val language = appLocale
    val localized = remember(context, configuration, language) {
        val config = Configuration(configuration).apply { setLocale(language) }
        config to context.createConfigurationContext(config)
    }
    CompositionLocalProvider(LocalConfiguration provides localized.first, LocalContext provides localized.second) {
        MaterialTheme(colorScheme = scheme, typography = brandTypography, content = content)
    }
}

private val brandTypography: Typography by lazy {
    val base = Typography()
    val f = BrandFonts.text
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = f),
        displayMedium = base.displayMedium.copy(fontFamily = f),
        displaySmall = base.displaySmall.copy(fontFamily = f),
        headlineLarge = base.headlineLarge.copy(fontFamily = f),
        headlineMedium = base.headlineMedium.copy(fontFamily = f),
        headlineSmall = base.headlineSmall.copy(fontFamily = f),
        titleLarge = base.titleLarge.copy(fontFamily = f),
        titleMedium = base.titleMedium.copy(fontFamily = f),
        titleSmall = base.titleSmall.copy(fontFamily = f),
        bodyLarge = base.bodyLarge.copy(fontFamily = f),
        bodyMedium = base.bodyMedium.copy(fontFamily = f),
        bodySmall = base.bodySmall.copy(fontFamily = f),
        labelLarge = base.labelLarge.copy(fontFamily = f),
        labelMedium = base.labelMedium.copy(fontFamily = f),
        labelSmall = base.labelSmall.copy(fontFamily = f),
    )
}
