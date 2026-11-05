package com.pawmiaw.business

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

//
//  The second half of iOS Components.swift (see Components.kt): notices, headings, bars,
//  ticks, people and pets, the location gate, search, the reload rules and the cards of a page.
//

// MARK: - Notices

/** `NoticeCard.Tone`, `NoticeCard.Look`. */
object NoticeCard {
    sealed class Tone {
        data object info : Tone()
        data object warning : Tone()
        data object problem : Tone()
        data object success : Tone()
        /** A prescription's "all good": MIAW yellow. */
        data object lime : Tone()
        data class custom(val color: Color) : Tone()
    }

    enum class Look { inline, banner, page }
}

/**
 * A notice (web `Notice` of inventory-bits / pharmacy, `RxNotice`, Home's page banners,
 * the hospital page's notice). Three looks:
 * - `.inline` (§3.5): 16 corners, a 1.5 pt edge of the state at 55%, its wash at 10%, 14 type.
 *   With a title the icon sits on the title's line and the message runs below; without one
 *   the icon leads the message (or the custom content).
 * - `.banner` (§3.5 page banner, the clock check, families' requests, a legal note): a 1 pt
 *   edge at 45%, the wash at 14%, 16/12 padding, the title's sentence bold and the message
 *   after it, and one primary action.
 * - `.page` (the hospital page's notice): a card-round band, the wash at 10% with a 2 pt edge at
 *   45%, a 40 white disc with the icon, the title bold with its byline, the content under it.
 * `icon`: "" = the tone's own, null = none.
 */
@Composable
fun NoticeCard(
    tone: NoticeCard.Tone,
    title: String? = null,
    message: String? = null,
    icon: String? = "",
    look: NoticeCard.Look = NoticeCard.Look.inline,
    /** Who did it, after the title (`.page`). */
    byline: String? = null,
    actionTitle: String? = null,
    actionVariant: ButtonVariant = ButtonVariant.outline,
    isWorking: Boolean = false,
    modifier: Modifier = Modifier,
    action: (() -> Unit)? = null,
) {
    NoticeCardBody(tone, title, message, icon, look, byline, actionTitle, actionVariant, isWorking, action, null, modifier)
}

/** A notice with its own content (a list, a button row) under or beside the title. */
@Composable
fun NoticeCard(
    tone: NoticeCard.Tone,
    title: String? = null,
    icon: String? = "",
    look: NoticeCard.Look = NoticeCard.Look.inline,
    byline: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    NoticeCardBody(tone, title, null, icon, look, byline, null, ButtonVariant.outline, false, null, content, modifier)
}

@Composable
private fun NoticeCardBody(
    tone: NoticeCard.Tone,
    title: String?,
    message: String?,
    icon: String?,
    look: NoticeCard.Look,
    byline: String?,
    actionTitle: String?,
    actionVariant: ButtonVariant,
    isWorking: Boolean,
    action: (() -> Unit)?,
    content: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val color = when (tone) {
        NoticeCard.Tone.info -> ProviderState.upcoming.color
        NoticeCard.Tone.warning -> ProviderState.waiting.color
        NoticeCard.Tone.problem -> ProviderState.problem.color
        NoticeCard.Tone.success -> ProviderState.done.color
        NoticeCard.Tone.lime -> Theme.lime
        is NoticeCard.Tone.custom -> tone.color
    }
    val glyph: String? = when {
        icon == null -> null
        icon.isNotEmpty() -> icon
        else -> when (tone) {
            NoticeCard.Tone.info, is NoticeCard.Tone.custom -> "info"
            NoticeCard.Tone.warning -> "circle-alert"
            NoticeCard.Tone.problem -> "triangle-alert"
            NoticeCard.Tone.success, NoticeCard.Tone.lime -> "circle-check"
        }
    }
    val iconInk = if (tone == NoticeCard.Tone.problem && look == NoticeCard.Look.inline) Theme.destructive else Theme.inkStrong
    val box = RoundedCornerShape(Theme.boxRadius)
    val body = Theme.text(14)
    val provided: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        CompositionLocalProvider(LocalTextStyle provides body, LocalContentColor provides Theme.inkStrong) { inner() }
    }
    when (look) {
        NoticeCard.Look.inline -> Column(
            modifier
                .fillMaxWidth()
                .background(Theme.wash(color, 10.0), box)
                .border(1.5.dp, Theme.mix(color, 55.0, Theme.borderSoft), box)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val heading = title.nonEmpty
            if (heading != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    if (glyph != null) Glyph(glyph, 16.dp, tint = iconInk, modifier = Modifier.padding(top = 2.dp))
                    Text(heading, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                }
                message.nonEmpty?.let { Text(it, style = body, color = Theme.inkStrong) }
                if (content != null) provided(content)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    if (glyph != null) Glyph(glyph, 16.dp, tint = iconInk, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        message.nonEmpty?.let { Text(it, style = body, color = Theme.inkStrong) }
                        if (content != null) provided(content)
                    }
                }
            }
            if (actionTitle != null && action != null) {
                PMButton(actionTitle, variant = actionVariant, size = ButtonSize.compact, isWorking = isWorking, modifier = Modifier.padding(top = 4.dp), action = action)
            }
        }
        NoticeCard.Look.banner -> Column(
            modifier
                .fillMaxWidth()
                .background(Theme.wash(color, 14.0), box)
                .border(1.dp, Theme.mix(color, 45.0, Theme.borderSoft), box)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                if (glyph != null) Glyph(glyph, 16.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val sentence = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title ?: "") }
                        append((if (title.nonEmpty != null && message.nonEmpty != null) " " else "") + (message ?: ""))
                    }
                    Text(sentence, style = body, color = Theme.inkStrong)
                    if (content != null) provided(content)
                }
            }
            if (actionTitle != null && action != null) {
                PMButton(actionTitle, size = ButtonSize.compact, isWorking = isWorking, action = action)
            }
        }
        NoticeCard.Look.page -> {
            val card = RoundedCornerShape(Theme.cardRadius)
            Row(
                modifier
                    .fillMaxWidth()
                    .background(Theme.wash(color, 10.0), card)
                    .border(2.dp, Theme.mix(color, 45.0, Theme.borderSoft), card)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (glyph != null) {
                    Box(
                        Modifier
                            .shadow(1.5.dp, CircleShape, clip = false, ambientColor = Theme.navy.copy(alpha = 0.08f), spotColor = Theme.navy.copy(alpha = 0.08f))
                            .size(40.dp)
                            .background(Theme.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(glyph, 20.dp, tint = Theme.inkStrong) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val inkSoft = Theme.inkSoft
                    val heading = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Theme.inkStrong)) { append(title ?: "") }
                        if (byline != null) withStyle(SpanStyle(color = inkSoft)) { append(" · $byline") }
                    }
                    Text(heading, style = body)
                    message.nonEmpty?.let { Text(it, style = body, color = Theme.inkStrong) }
                    if (content != null) CompositionLocalProvider(LocalTextStyle provides body) { content() }
                    if (actionTitle != null && action != null) {
                        PMButton(actionTitle, variant = actionVariant, size = ButtonSize.compact, isWorking = isWorking, action = action)
                    }
                }
            }
        }
    }
}

/**
 * A micro-label (web: the eyebrows — 0.62–0.75rem extrabold uppercase, tracked 0.1–0.12em,
 * ink-soft): over a box, a section, a table's column.
 */
@Composable
fun Eyebrow(text: String, size: Number = 10.9, color: Color = Theme.inkSoft, tracking: Double = 0.1, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier,
        style = brandText(size, FontWeight.ExtraBold).tracking(size.toDouble() * tracking),
        color = color,
    )
}

/** `SectionHeading.Style`. */
object SectionHeading {
    enum class Style { heading, label }
}

/**
 * A part's heading on the canvas or in a column (web pharmacy-bits `SectionHeading`, a board's
 * stage, a due bucket): an optional state dot, the title (Fredoka 18, or 14 extrabold as a
 * `.label`), a count bubble, a hint under it, and an action below.
 */
@Composable
fun SectionHeading(
    title: String,
    count: Int? = null,
    hint: String? = null,
    dot: Color? = null,
    style: SectionHeading.Style = SectionHeading.Style.heading,
    /** The bubble's fill: muted on the canvas, white on a muted column. */
    bubble: Color = Theme.surfaceMuted,
    /** Show the bubble at 0 too (a stage column). */
    showsZero: Boolean = false,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) Box(Modifier.size(10.dp).background(dot, CircleShape))
                Text(
                    title,
                    Modifier.weight(1f, fill = false),
                    style = if (style == SectionHeading.Style.heading) Theme.heading(18) else Theme.text(14, FontWeight.ExtraBold),
                    color = Theme.inkStrong,
                    maxLines = if (style == SectionHeading.Style.label) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                if (count != null && (count > 0 || showsZero)) {
                    Box(
                        Modifier
                            .background(bubble, CapsuleShape)
                            .sizeIn(minWidth = 24.dp, minHeight = if (style == SectionHeading.Style.heading) 24.dp else 20.dp)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$count", style = brandText(12, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkStrong)
                    }
                }
            }
            hint.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
        }
        action?.invoke()
    }
}

/**
 * A bar that fills (web: the setup checklist's 8 pt bar, the day's care 10 pt bar, Finance's
 * `ShareBar`): PAW Blue (or a share's tone) over a track (blue 14%, the muted surface or white);
 * a fixed width or the space it gets. `value` is 0…1.
 */
@Composable
fun ProgressBar(
    value: Double,
    height: Dp = 8.dp,
    width: Dp? = null,
    track: Color? = null,
    fill: Color = Theme.blue,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val clamped = value.coerceIn(0.0, 1.0)
    val trackColor = track ?: Theme.wash(Theme.blue, 14.0)
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clearAndSetSemantics {
                contentDescription = label ?: ""
                stateDescription = "${(clamped * 100).roundToInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(clamped.toFloat(), 0f..1f)
            }
            .background(trackColor, CapsuleShape),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(clamped.toFloat()).background(fill, CapsuleShape))
    }
}

/** `Tick.Style`. */
object Tick {
    enum class Style { checkbox, choice, radio }
}

/**
 * A tick. `.checkbox` (web `ui/checkbox`): 20, the tick's corners, a 1.5 pt soft-ink edge
 * (red when invalid); on = PAW Blue with a white check, `mixed` = blue with a dash.
 * `.choice` (web location-choice / services-section `Tick`): 24, a 2 pt grey edge, the box's
 * corners (round at that size), blue with a check when on. `.radio` (web `ChoiceGroup`'s
 * mark, a role choice): a 20 circle with a 2 pt grey edge, blue with a 12 check when on.
 */
@Composable
fun Tick(on: Boolean, mixed: Boolean = false, invalid: Boolean = false, style: Tick.Style = Tick.Style.checkbox, modifier: Modifier = Modifier) {
    val size = if (style == Tick.Style.choice) 24.dp else 20.dp
    val radius = if (style == Tick.Style.checkbox) Theme.tickRadius else minOf(Theme.boxRadius, size / 2)
    val shape = RoundedCornerShape(radius)
    val edge = when {
        on || mixed -> Theme.blue
        invalid -> Theme.destructive
        style == Tick.Style.checkbox -> Theme.inkSoft
        else -> Theme.borderSoft
    }
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(size)
            .background(if (on || mixed) Theme.blue else Theme.surface, shape)
            .border(if (style == Tick.Style.checkbox) 1.5.dp else 2.dp, edge, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Glyph("check", if (style == Tick.Style.radio) 12.dp else 14.dp, tint = Theme.onBlue)
        } else if (mixed) {
            Glyph("minus", 14.dp, tint = Theme.onBlue)
        }
    }
}

// MARK: - People and pets

/**
 * A person or a client: their photo, else the first letters on MIAW yellow at 25% (web: the
 * clients list's 40 pt initials, 14 bold navy). `tint`: the colour washed behind the initials
 * instead of the clients' yellow (a staff role's).
 */
@Composable
fun Avatar(name: String?, imageURL: String? = null, size: Dp = 40.dp, tint: Color? = null, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Theme.wash(tint ?: Theme.lime, 25.0)), contentAlignment = Alignment.Center) {
        Text(NameText.firstLetters(name), style = brandText(size.value * 0.35, FontWeight.Bold), color = Theme.inkStrong)
        val url = APIConfig.assetURL(imageURL)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(CircleShape))
        }
    }
}

/**
 * A pet's face (web `PetAvatar`): its photo, else the species' line icon in cocoa on the
 * warm pet-face circle, half the size. Same in dark.
 */
@Composable
fun PetAvatar(name: String?, species: String? = null, photoURL: String? = null, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val glyph = when (species?.lowercase()) {
        "cat" -> "cat"
        "bird" -> "bird"
        "fish" -> "fish"
        "rabbit" -> "rabbit"
        "reptile", "turtle" -> "turtle"
        "rodent", "hamster", "rat" -> "rat"
        "dog" -> "dog"
        else -> "paw-print"
    }
    Box(modifier.size(size).clip(CircleShape).background(Theme.petFace), contentAlignment = Alignment.Center) {
        Glyph(glyph, size * 0.5f, tint = Theme.petFaceInk)
        val url = APIConfig.assetURL(photoURL)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(CircleShape))
        }
    }
}

/** A pet as a chip (the clients list's pets): its 24 face and its name. */
@Composable
fun PetChip(name: String, species: String? = null, photoURL: String? = null, highlighted: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(if (highlighted) Theme.wash(Theme.blue, 12.0) else Theme.surface, CapsuleShape)
            .border(1.dp, if (highlighted) Theme.blue else Theme.borderSoft, CapsuleShape)
            .padding(start = 2.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetAvatar(name = name, species = species, photoURL = photoURL, size = 24.dp)
        Text(name, style = brandText(12, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Locations

/**
 * Pages that belong to one place (Home, the Agenda, Stays, Rooms, Availability, the register)
 * never mix places: with "Todas las sedes" chosen they ask which one first.
 */
@Composable
fun RequireLocation(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val workspace = Workspace.shared
    if (workspace.allLocationsChosen) {
        PageScroll(modifier = modifier) {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("layers", 20.dp, tint = Theme.blue)
                    Text(L("Choose a location"), style = Theme.titleFont, color = Theme.inkStrong)
                }
                Text(
                    L("You are looking at every location. This page belongs to one place: its day, its schedule and its register."),
                    style = Theme.smallFont,
                    color = Theme.inkSoft,
                )
                for (location in workspace.memberLocations) {
                    ListRow(
                        title = location.name,
                        subtitle = location.address,
                        showsChevron = true,
                        modifier = Modifier.plainButton {
                            workspace.setLocation(location.id)
                            Toast.show(LF("Now showing %@.", location.name))
                        },
                        leading = { Glyph("map-pin", 20.dp, tint = Theme.blue) },
                        trailing = { if (location.isPrimary) HueChip(text = L("Main"), hue = Hue.sky) },
                    )
                }
            }
        }
    } else {
        content()
    }
}

// MARK: - Search

/** `SearchField.Shape`. */
object SearchField {
    /**
     * `capsule`: a page's or a card toolbar's search (web ProviderToolbarSearchField);
     * `field`: inside a sheet, the input's 16 corners (web client-pet-picker).
     */
    enum class Shape { capsule, field }
}

/** The search field used inside pages (lists own their search bar the same way). */
@Composable
fun SearchField(
    prompt: String,
    text: MutableState<String>,
    height: Dp = 48.dp,
    shape: SearchField.Shape = SearchField.Shape.capsule,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val outline = if (shape == SearchField.Shape.capsule) CapsuleShape else RoundedCornerShape(Theme.boxRadius)
    val edge by animateColorAsState(if (focused) Theme.mango else Theme.borderSoft, tween(180), label = "search edge")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "search ring")
    val mango = Theme.mango
    val style = Theme.bodyFont.copy(color = Theme.inkStrong)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawWithContent {
                drawContent()
                if (ring > 0f) {
                    // A 3 pt mango ring 3 outside the field, at half strength (iOS `.padding(-3)`).
                    val grow = 3.dp.toPx()
                    val radius = if (shape == SearchField.Shape.capsule) (size.height / 2 + grow) else (Theme.boxRadius + 3.dp).toPx()
                    drawRoundRect(
                        color = mango.copy(alpha = 0.5f * ring),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            .background(Theme.inputFill, outline)
            .border(1.dp, edge, outline)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("search", 16.dp, tint = Theme.inkSoft)
        BasicTextField(
            value = text.value,
            onValueChange = { text.value = it },
            modifier = Modifier.weight(1f),
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(Theme.blue),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.value.isEmpty()) Text(prompt, style = Theme.bodyFont, color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (text.value.isNotEmpty()) {
            val clear = L("Clear")
            Box(Modifier.plainButton(label = clear) { text.value = "" }) {
                Glyph("x", 16.dp, tint = Theme.inkSoft)
            }
        }
    }
}

/** Waits a moment after typing stops before running `action` (a search). iOS `Debounced`. */
@Composable
fun Debounced(value: String, delay: Duration = 250.milliseconds, action: suspend (String) -> Unit) {
    val current by rememberUpdatedState(action)
    LaunchedEffect(value) {
        delay(delay)
        current(value)
    }
}

/** iOS `.debounced(value) { … }` on a view. */
fun Modifier.debounced(value: String, action: suspend (String) -> Unit): Modifier = composed {
    Debounced(value, action = action)
    this
}

/**
 * Reloads when the business or the place changes, or another screen changed data
 * (iOS `.reloadsOnScope { … }`, `ScopeReload`): runs on appear too.
 */
@Composable
fun ReloadsOnScope(action: suspend () -> Unit) {
    val current by rememberUpdatedState(action)
    val version = Workspace.shared.scopeVersion
    LaunchedEffect(version) { current() }
    LaunchedEffect(Unit) {
        AppEvents.dataChanged.collect { launch { current() } }
    }
}

/** iOS `.reloadsOnScope { … }` on a view. */
fun Modifier.reloadsOnScope(action: suspend () -> Unit): Modifier = composed {
    ReloadsOnScope(action)
    this
}

// MARK: - Cards of a page

/**
 * The standard card with a header row (web: `rounded-card` with a header padded 16/12 over a
 * hairline): an optional icon disc, the title in Fredoka 18, a trailing control; the content
 * below runs edge to edge, so rows and strips can draw their own dividers.
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    icon: String? = null,
    accent: Color = Theme.aqua,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .cardShadow(shape)
            .clip(shape)
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) IconBadge(icon = icon, accent = accent, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 35.0)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Theme.heading(18), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle.nonEmpty?.let { Text(it, style = Theme.text(13), color = Theme.inkSoft) }
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.borderSoft))
        content()
    }
}

/** A hairline between the rows of a card. */
@Composable
fun RowDivider(color: Color = Theme.borderSoft, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** `NeedsCard.Attention`. */
object NeedsCard {
    enum class Attention { none, amber, red }
}

/** The divider colour inside a `NeedsCard` (tinted under attention); null = `Theme.borderSoft`. iOS `needsDivider`. */
val LocalNeedsDivider = compositionLocalOf<Color?> { null }

/**
 * A "Te necesita" block (web `Block`, today-board.tsx): a 28 disc and a 14 extrabold title,
 * the count at the right, rows between tinted hairlines, a "Ver todo · 3 más" link. Amber or
 * red attention washes the card in its colour with a 2 pt border. `more`: the rows not
 * shown; with `seeAll` it reads "Ver todo · 3 más".
 */
@Composable
fun NeedsCard(
    title: String,
    icon: String,
    count: Int? = null,
    hint: String? = null,
    tone: Color = Theme.aqua,
    attention: NeedsCard.Attention = NeedsCard.Attention.none,
    more: Int = 0,
    seeAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val attentionColor: Color? = when (attention) {
        NeedsCard.Attention.none -> null
        NeedsCard.Attention.amber -> Theme.mango
        NeedsCard.Attention.red -> Theme.destructive
    }
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .cardShadow(shape)
            .background(attentionColor?.let { Theme.wash(it, 12.0) } ?: Theme.surface, shape)
            .border(if (attentionColor == null) 1.dp else 2.dp, attentionColor?.copy(alpha = 0.7f) ?: Theme.borderSoft, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(Theme.wash(attentionColor ?: tone, if (attentionColor == null) 20.0 else 35.0), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Glyph(icon, 16.dp, tint = Theme.inkStrong) }
            Text(title, Modifier.weight(1f), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            if (count != null) Text("$count", style = Theme.text(14, FontWeight.ExtraBold).monospacedDigit(), color = Theme.inkStrong)
        }
        hint.nonEmpty?.let {
            Text(
                it,
                style = Theme.text(12, if (attention == NeedsCard.Attention.red) FontWeight.Bold else FontWeight.Normal),
                color = if (attention == NeedsCard.Attention.red) Theme.destructive else Theme.inkSoft,
            )
        }
        CompositionLocalProvider(LocalNeedsDivider provides (attentionColor?.copy(alpha = 0.35f) ?: Theme.borderSoft)) {
            content()
        }
        if (more > 0) {
            if (seeAll != null) {
                Text(
                    "${L("See all")} · ${LF("%@ more", more.toString())}",
                    Modifier.padding(top = 8.dp).plainButton(onClick = seeAll),
                    style = Theme.text(12, FontWeight.Bold),
                    color = Theme.blue,
                )
            } else {
                Text(LF("%@ more", more.toString()), Modifier.padding(top = 8.dp), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

/** One row of a `NeedsCard`: a 14 bold title, a 12 hint, a trailing control, over the card's divider. */
@Composable
fun NeedsRow(
    title: String,
    hint: String? = null,
    first: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val divider = LocalNeedsDivider.current ?: Theme.borderSoft
    Column(modifier.fillMaxWidth()) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hint.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A one-line card that opens a page (web `LinkLine`): a 32 disc, a 14 bold title, a 12 hint (red and bold when it needs someone). */
@Composable
fun LinkLine(title: String, icon: String, hint: String? = null, attention: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Row(
        modifier
            .fillMaxWidth()
            .cardShadow(shape)
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(if (attention) Theme.wash(Theme.destructive, 12.0) else Theme.surfaceMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(icon, 16.dp, tint = if (attention) Theme.destructive else Theme.blue) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            hint.nonEmpty?.let {
                Text(it, style = Theme.text(12, if (attention) FontWeight.Bold else FontWeight.Normal), color = if (attention) Theme.destructive else Theme.inkSoft)
            }
        }
    }
}

/**
 * A band that opens a section of a board (web `HomeStrip`): an 8 dot, the label and count in
 * 11.2 extrabold uppercase, an optional link, a chevron. With `folded`, a tap folds or opens it.
 */
@Composable
fun BoardStrip(
    title: String,
    count: Int? = null,
    dot: Color = Theme.blue,
    linkTitle: String? = null,
    link: (() -> Unit)? = null,
    folded: MutableState<Boolean>? = null,
    modifier: Modifier = Modifier,
) {
    val turn by animateFloatAsState(if (folded?.value == true) -90f else 0f, tween(200), label = "strip chevron")
    val style = brandText(11.2, FontWeight.ExtraBold)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 29.dp)
            .background(Theme.wash(Theme.surfaceMuted, 55.0))
            .then(if (folded != null) Modifier.plainButton { folded.value = !folded.value } else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides Theme.inkSoft, LocalTextStyle provides style) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(title.uppercase(), style = style.tracking(1.3))
            if (count != null) Text("$count", style = style.monospacedDigit())
            if (linkTitle != null && link != null) {
                Row(
                    Modifier.plainButton(onClick = link).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(linkTitle, style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
                    Glyph("chevron-right", 14.dp, tint = Theme.blue)
                }
            }
            Spacer(Modifier.weight(1f))
            if (folded != null) Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
        }
    }
}

/**
 * A row of a board or list with a 3 pt edge in its state's colour (web `VisitRow` geometry):
 * an 84 pt time column (Fredoka 15.2 time, an 11.2 bold state word), then the rest, then an
 * optional chevron.
 */
@Composable
fun EdgeRow(
    edge: Color = Theme.borderSoft,
    time: String? = null,
    timeNote: String? = null,
    timeNoteColor: Color? = null,
    showsChevron: Boolean = false,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.75f else 1f)
            .drawWithContent {
                drawContent()
                drawRect(edge, size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = 13.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (time != null || timeNote != null) {
            Column(Modifier.width(84.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(time ?: "—", style = Theme.heading(15.2).monospacedDigit(), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (timeNote != null) {
                    Text(timeNote, style = Theme.text(11.2, FontWeight.Bold), color = timeNoteColor ?: edge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        content()
        if (showsChevron) Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}
