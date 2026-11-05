package com.pawmiaw.business.team

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentWidth
import coil.compose.rememberAsyncImagePainter
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

//
//  TeamShared.kt (iOS TeamShared.swift)
//
//  Pieces the Team module's pages share, built from the Design components:
//  a card that saves on its own (the web's SaveSection, "Cambios sin
//  guardar" until saved), a titled panel, a confirmation with content, the
//  "where they work" list, a stat with its hint, and opening a page of the
//  web. (The change history is the shared AuditHistoryCard.)
//

// MARK: - A card that saves on its own

enum class TeamSaveState { clean, dirty, saving, saved, invalid }

/** "Saved" for a few seconds after a save, then quiet again. `@StateObject` → `remember { TeamSavedFlash() }`. */
@Stable
class TeamSavedFlash {
    var on by mutableStateOf(false)
        private set
    private var task: Job? = null

    fun mark() {
        on = true
        task?.cancel()
        task = scope.launch {
            delay(3_000)
            on = false
        }
    }

    fun clear() {
        on = false
        task?.cancel()
    }

    fun state(saving: Boolean, dirty: Boolean, valid: Boolean = true): TeamSaveState {
        if (saving) return TeamSaveState.saving
        if (dirty) return if (valid) TeamSaveState.dirty else TeamSaveState.invalid
        return if (on) TeamSaveState.saved else TeamSaveState.clean
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

/**
 * The look a Team card wears. `card` is the web's `SaveSection` / `MemberPanel`
 * (Availability, a staff member's page, the account): a solid 40 icon badge,
 * a Fredoka 20 title, a hairline under the header. `settings` is the web's
 * `SettingsPanel` (Settings' sections): the header washed with the accent from
 * the left, the icon on a 24% wash, a "Cambios sin guardar" chip by the title,
 * and the Save / Discard bar only while something is unsaved.
 */
enum class TeamCardLook { card, settings }

/** SwiftUI `.overlay(alignment: .bottom) { RowDivider() }` (or `.top`): a 1 pt rule over the view's edge. */
fun Modifier.teamEdgeRule(color: Color, top: Boolean = false): Modifier = drawWithContent {
    drawContent()
    val h = 1.dp.toPx()
    drawRect(color, topLeft = Offset(0f, if (top) 0f else size.height - h), size = Size(size.width, h))
}

/** The Olfato AI corgi in a vet coat (iOS `Image("OlfatoFace")`). */
@Composable
fun TeamOlfatoFace(modifier: Modifier = Modifier) {
    Image(
        painter = rememberAsyncImagePainter("file:///android_asset/img/OlfatoFace.svg"),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/**
 * The header every Team card wears: the icon badge, the title, what it is for,
 * and something small on the right (stacked under it on a phone).
 */
@Composable
fun TeamCardHeader(
    title: String,
    subtitle: String? = null,
    systemImage: String,
    tint: Color = Theme.blue,
    look: TeamCardLook = TeamCardLook.card,
    /** Settings only: "Cambios sin guardar" / "Guardado" beside the title. */
    saveState: TeamSaveState? = null,
    modifier: Modifier = Modifier,
    aside: @Composable () -> Unit = {},
) {
    val wide = LocalSizeClass.current == SizeClass.regular
    val rule = Theme.borderSoft
    val frame = modifier
        .fillMaxWidth()
        .then(
            if (look == TeamCardLook.settings) Modifier.drawBehind {
                // linear-gradient(105deg, accent 9% → surface 60%)
                drawRect(
                    Brush.linearGradient(
                        0f to tint.copy(alpha = 0.09f),
                        0.6f to tint.copy(alpha = 0f),
                        start = Offset(0f, size.height * 0.37f),
                        end = Offset(size.width, size.height * 0.63f),
                    )
                )
            } else Modifier
        )
        .teamEdgeRule(rule)
        .padding(horizontal = if (wide) (if (look == TeamCardLook.settings) 24.dp else 20.dp) else 16.dp, vertical = 16.dp)
    if (wide) {
        // SwiftUI `HStack(alignment: .top, spacing: 12) { lead; Spacer(minLength: 8); aside() }`: the
        // aside keeps its size (a full-width control takes at most half the row), the lead the rest.
        Layout(
            content = {
                Box { TeamCardHeaderLead(title, subtitle, systemImage, tint, look, saveState) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) { aside() }
            },
            modifier = frame,
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val gap = 12.dp.roundToPx()
            val spacer = 8.dp.roundToPx()
            val asidePlaced = measurables[1].measure(Constraints(maxWidth = width / 2))
            val used = if (asidePlaced.width > 0) asidePlaced.width + gap * 2 + spacer else gap + spacer
            val leadPlaced = measurables[0].measure(Constraints(maxWidth = (width - used).coerceAtLeast(0)))
            layout(width, maxOf(leadPlaced.height, asidePlaced.height)) {
                leadPlaced.place(0, 0)
                asidePlaced.place(width - asidePlaced.width, 0)
            }
        }
    } else {
        Column(frame, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TeamCardHeaderLead(title, subtitle, systemImage, tint, look, saveState)
            aside()
        }
    }
}

@Composable
private fun TeamCardHeaderLead(title: String, subtitle: String?, systemImage: String, tint: Color, look: TeamCardLook, saveState: TeamSaveState?) {
    if (look == TeamCardLook.settings) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Theme.wash(tint, 24.0), CircleShape), contentAlignment = Alignment.Center) {
                    // Olfato AI wears its corgi in a vet coat, as on the web.
                    if (systemImage == "olfato") {
                        TeamOlfatoFace(Modifier.size(40.dp).padding(4.dp))
                    } else {
                        Glyph(systemImage, 20.dp, tint = Theme.inkStrong)
                    }
                }
                Text(title, Modifier.weight(1f, fill = false), style = Theme.heading(20), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (saveState != null) TeamSaveStateChip(state = saveState)
            }
            subtitle.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            val ring = Modifier.border(1.dp, Theme.blue, CircleShape)
            if (systemImage == "olfato") {
                // Olfato AI wears its corgi in a vet coat, on its colour.
                Box(Modifier.size(40.dp).background(tint, CircleShape).then(ring), contentAlignment = Alignment.Center) {
                    TeamOlfatoFace(Modifier.size(40.dp).padding(5.dp))
                }
            } else {
                IconBadge(
                    icon = systemImage,
                    accent = tint,
                    size = 40.dp,
                    iconSize = 16.dp,
                    ink = if (tint == Theme.blue) IconBadge.Ink.onAccent else IconBadge.Ink.strong,
                    modifier = ring,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Theme.heading(20), color = Theme.inkStrong)
                subtitle.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
            }
        }
    }
}

/** "Cambios sin guardar" / "Guardado" beside a Settings section's title (web `SaveStateChip`). */
@Composable
fun TeamSaveStateChip(state: TeamSaveState, modifier: Modifier = Modifier) {
    @Composable
    fun Pill(fill: Color, percent: Double, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContentColor provides Theme.inkStrong, LocalTextStyle provides Theme.text(12, FontWeight.ExtraBold)) {
            Row(
                modifier
                    .height(28.dp)
                    .background(Theme.wash(fill, percent), CapsuleShape)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        }
    }
    when (state) {
        TeamSaveState.dirty, TeamSaveState.invalid, TeamSaveState.saving -> Pill(Theme.mango, 22.0) {
            Box(Modifier.size(8.dp).background(Theme.mango, CircleShape))
            Text(L("Unsaved changes"), maxLines = 1)
        }
        TeamSaveState.saved -> Pill(ProviderState.done.color, 20.0) {
            Glyph("circle-check", 14.dp, tint = ProviderState.done.color)
            Text(L("Saved"), maxLines = 1)
        }
        TeamSaveState.clean -> {}
    }
}

/** The card frame every Team card shares: the standard card (28 corners, a hairline border, the card shadow), clipped so its strips run edge to edge. */
private fun Modifier.teamCardFrame(): Modifier = this.fillMaxWidth().listCard()

/**
 * A titled card for the parts that don't save in place (the web's
 * `MemberPanel`, or a `SettingsPanel` with no save bar).
 */
@Composable
fun TeamPanel(
    title: String,
    subtitle: String? = null,
    systemImage: String,
    tint: Color = Theme.blue,
    look: TeamCardLook = TeamCardLook.card,
    /** Settings: why this person can only look, in a strip under the header. */
    readOnlyNote: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val wide = LocalSizeClass.current == SizeClass.regular
    Column(modifier.teamCardFrame()) {
        TeamCardHeader(title = title, subtitle = subtitle, systemImage = systemImage, tint = tint, look = look, aside = action)
        if (readOnlyNote != null) TeamReadOnlyStrip(text = readOnlyNote)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (wide) (if (look == TeamCardLook.settings) 24.dp else 20.dp) else 16.dp,
                    vertical = if (look == TeamCardLook.settings) 20.dp else 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** The read-only strip under a Settings card's header (web `data-read-only-note`). */
@Composable
fun TeamReadOnlyStrip(text: String, modifier: Modifier = Modifier) {
    val wide = LocalSizeClass.current == SizeClass.regular
    Row(
        modifier
            .fillMaxWidth()
            .background(Theme.surfaceMuted)
            .teamEdgeRule(Theme.borderSoft)
            .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("lock", 16.dp, tint = Theme.inkSoft)
        Text(text, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
    }
}

// MARK: - Unsaved cards (iOS `TeamUnsavedKey`, a PreferenceKey)

/**
 * The titles of the save-in-place cards on a page that have unsaved changes (iOS
 * `TeamUnsavedKey`). A page that guards leaving holds one and provides it to its cards:
 *
 *     val unsavedKey = remember { TeamUnsavedKey() }
 *     CompositionLocalProvider(LocalTeamUnsaved provides unsavedKey) { … cards … }
 *     val unsaved = unsavedKey.value            // iOS `.onPreferenceChange(TeamUnsavedKey.self)`
 */
@Stable
class TeamUnsavedKey {
    private val entries = mutableStateListOf<Pair<Any, String>>()

    /** The titles, in the order the cards said so. */
    val value: List<String> get() = entries.map { it.second }

    fun set(owner: Any, title: String?) {
        val index = entries.indexOfFirst { it.first === owner }
        if (title == null) {
            if (index >= 0) entries.removeAt(index)
        } else if (index >= 0) {
            if (entries[index].second != title) entries[index] = owner to title
        } else {
            entries.add(owner to title)
        }
    }

    companion object {
        val defaultValue: List<String> = emptyList()
    }
}

/** The page's `TeamUnsavedKey` its `TeamSaveSection`s report to (null: nobody listens). */
val LocalTeamUnsaved = compositionLocalOf<TeamUnsavedKey?> { null }

/**
 * A card that saves on its own. `card` (the web's `SaveSection`): the fields,
 * then a footer saying whether there is something to save ("Cambios sin
 * guardar") with Discard and Save; read-only readers see why instead.
 * `settings` (the web's `SettingsPanel`): the read-only note under the title,
 * and the Save / Discard bar only while something is unsaved (or always,
 * waiting disabled, with `floatingBar = false`).
 */
@Composable
fun TeamSaveSection(
    title: String,
    subtitle: String? = null,
    systemImage: String,
    tint: Color = Theme.blue,
    look: TeamCardLook = TeamCardLook.card,
    /** Settings: the bar shows only while something is unsaved (the web's `saveBar="when-dirty"`). */
    floatingBar: Boolean = true,
    state: TeamSaveState,
    saveLabel: String = L("Save"),
    invalidText: String? = null,
    readOnlyText: String? = null,
    onSave: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    aside: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val wide = LocalSizeClass.current == SizeClass.regular
    val gutter = if (wide) (if (look == TeamCardLook.settings) 24.dp else 20.dp) else 16.dp

    // A page that guards leaving reads which of its cards have unsaved changes.
    val unsaved = LocalTeamUnsaved.current
    val owner = remember { Any() }
    val flagged = readOnlyText == null && onSave != null && (state == TeamSaveState.dirty || state == TeamSaveState.invalid)
    DisposableEffect(unsaved, flagged, title) {
        unsaved?.set(owner, if (flagged) title else null)
        onDispose { unsaved?.set(owner, null) }
    }

    Column(modifier.teamCardFrame()) {
        TeamCardHeader(
            title = title, subtitle = subtitle, systemImage = systemImage, tint = tint, look = look,
            saveState = if (look == TeamCardLook.settings && readOnlyText == null) state else null,
            aside = aside,
        )
        if (look == TeamCardLook.settings && readOnlyText != null) TeamReadOnlyStrip(text = readOnlyText)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter, vertical = if (look == TeamCardLook.settings) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (look == TeamCardLook.settings) {
            if (readOnlyText == null && onSave != null &&
                (!floatingBar || state == TeamSaveState.dirty || state == TeamSaveState.invalid || state == TeamSaveState.saving)
            ) {
                TeamSettingsBar(state, floatingBar, saveLabel, gutter, wide, onSave, onDiscard)
            }
        } else if (readOnlyText != null) {
            Text(
                readOnlyText,
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.surfaceMuted, 50.0))
                    .teamEdgeRule(Theme.borderSoft, top = true)
                    .padding(horizontal = gutter, vertical = 12.dp),
                style = Theme.text(14),
                color = Theme.inkSoft,
            )
        } else if (onSave != null) {
            TeamCardFooter(state, saveLabel, invalidText, gutter, wide, onSave, onDiscard)
        }
    }
}

// MARK: SaveSection's footer

@Composable
private fun TeamCardFooter(
    state: TeamSaveState,
    saveLabel: String,
    invalidText: String?,
    gutter: Dp,
    wide: Boolean,
    onSave: () -> Unit,
    onDiscard: (() -> Unit)?,
) {
    val fill = when (state) {
        TeamSaveState.dirty, TeamSaveState.saving -> Theme.wash(Theme.mango, 10.0)
        TeamSaveState.invalid -> Theme.wash(Theme.destructive, 6.0)
        else -> Theme.wash(Theme.surfaceMuted, 50.0)
    }
    val border = when (state) {
        TeamSaveState.dirty, TeamSaveState.saving -> Theme.mix(Theme.mango, 45.0, Theme.borderSoft)
        TeamSaveState.invalid -> Theme.mix(Theme.destructive, 35.0, Theme.borderSoft)
        else -> Theme.borderSoft
    }
    val frame = Modifier
        .fillMaxWidth()
        .background(fill)
        .teamEdgeRule(border, top = true)
        .padding(horizontal = gutter, vertical = 12.dp)
    val showsDiscard = onDiscard != null && (state == TeamSaveState.dirty || state == TeamSaveState.invalid)
    if (wide) {
        Row(frame, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamSaveStatus(state, invalidText, Modifier.weight(1f))
            if (showsDiscard) PMButton(L("Discard"), variant = ButtonVariant.outline, size = ButtonSize.compact, action = onDiscard!!)
            PMButton(saveLabel, systemImage = "save", size = ButtonSize.compact, isWorking = state == TeamSaveState.saving, enabled = state == TeamSaveState.dirty, action = onSave)
        }
    } else {
        Column(frame, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamSaveStatus(state, invalidText)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showsDiscard) PMButton(L("Discard"), variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true, modifier = Modifier.weight(1f), action = onDiscard!!)
                PMButton(saveLabel, systemImage = "save", size = ButtonSize.compact, fullWidth = true, isWorking = state == TeamSaveState.saving, enabled = state == TeamSaveState.dirty, modifier = Modifier.weight(1f), action = onSave)
            }
        }
    }
}

@Composable
private fun TeamSaveStatus(state: TeamSaveState, invalidText: String?, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides Theme.inkStrong, LocalTextStyle provides Theme.text(14, FontWeight.SemiBold)) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                TeamSaveState.saved -> {
                    Glyph("check", 16.dp, tint = Theme.blue)
                    Text(L("Saved"))
                }
                TeamSaveState.dirty -> {
                    Box(Modifier.size(8.dp).background(Theme.mango, CircleShape))
                    Text(L("Unsaved changes"))
                }
                TeamSaveState.saving -> {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
                    Text(L("Saving…"))
                }
                TeamSaveState.invalid -> Text(invalidText ?: L("Fix the marked fields to save."), color = Theme.destructive)
                TeamSaveState.clean -> Text(L("Up to date"), style = Theme.text(14, FontWeight.Medium), color = Theme.inkSoft)
            }
        }
    }
}

// MARK: SettingsPanel's bar

@Composable
private fun TeamSettingsBar(
    state: TeamSaveState,
    floatingBar: Boolean,
    saveLabel: String,
    gutter: Dp,
    wide: Boolean,
    onSave: () -> Unit,
    onDiscard: (() -> Unit)?,
) {
    val busy = state == TeamSaveState.saving
    val enabled = if (floatingBar) !busy else (state == TeamSaveState.dirty && !busy)
    val frame = Modifier
        .fillMaxWidth()
        .background(if (floatingBar) Theme.wash(Theme.mango, 12.0) else Theme.surfaceMuted)
        .teamEdgeRule(if (floatingBar) Theme.mix(Theme.mango, 45.0, Theme.borderSoft) else Theme.borderSoft, top = true)
        .padding(horizontal = gutter, vertical = 12.dp)
    if (wide) {
        Row(frame, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (floatingBar) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Theme.mango, CircleShape))
                        Text(if (busy) L("Saving…") else L("Unsaved changes"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                    }
                }
            }
            if (onDiscard != null) PMButton(L("Discard"), variant = ButtonVariant.outline, enabled = enabled, action = onDiscard)
            PMButton(if (busy) L("Saving…") else saveLabel, systemImage = "save", isWorking = busy, enabled = enabled, action = onSave)
        }
    } else {
        // A phone: the buttons stacked, Save on top.
        Column(frame, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PMButton(if (busy) L("Saving…") else saveLabel, systemImage = "save", fullWidth = true, isWorking = busy, enabled = enabled, action = onSave)
            if (onDiscard != null) PMButton(L("Discard"), variant = ButtonVariant.outline, fullWidth = true, enabled = enabled, action = onDiscard)
        }
    }
}

// MARK: - A spinner (SwiftUI `ProgressView()`)

/** SwiftUI `ProgressView().frame(maxWidth: .infinity, minHeight: x)`: the small grey spinner, centred. */
@Composable
fun TeamProgress(minHeight: Dp = 0.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
    }
}

// MARK: - Confirmations with content

/**
 * A serious yes/no with something to read in it (web `ProviderConfirmSheet` with children):
 * the pink header with the warning, the description under the title, a person line or the
 * consequences in the body, the red action beside the sheet's own cancel words. A plain
 * question with nothing in its body is the kit's `ConfirmSheet`.
 */
@Composable
fun TeamConfirmSheet(
    title: String,
    message: String? = null,
    confirmTitle: String,
    cancelTitle: String = L("Cancel"),
    modifier: Modifier = Modifier,
    action: suspend () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss = LocalDismiss.current
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ProviderModal(
        title = title,
        subtitle = message,
        systemImage = "triangle-alert",
        accent = ModalAccent.pink,
        primaryTitle = confirmTitle,
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = cancelTitle,
        primaryIcon = "triangle-alert",
        onPrimary = {
            working = true
            scope.launch {
                try {
                    action()
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.error(e)
                } finally {
                    working = false
                }
            }
        },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
        content = content,
    )
}

/** What an action does, as a bulleted list (14, soft). */
@Composable
fun TeamConsequences(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", Modifier.alignByBaseline(), style = Theme.text(14), color = Theme.inkSoft)
                Text(line, Modifier.alignByBaseline(), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }
}

/** A person in a sheet (web `PersonLine`): their 44 face, name and a line, in a bordered box. */
@Composable
fun TeamPersonLine(name: String, image: String? = null, detail: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, Theme.borderSoft, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = name, imageURL = image, size = 44.dp, tint = Theme.aqua)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// MARK: - Where someone works

/** "Every location" (which includes any added later), or the ones ticked. Empty means everywhere. */
@Composable
fun TeamLocationChoiceList(
    locations: List<ProviderLocation>,
    value: MutableState<List<String>>,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Disabled(disabled) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamLocationChoiceRow(on = value.value.isEmpty(), title = L("Every location"), subtitle = L("Including any added later.")) { value.value = emptyList() }
            locations.forEach { location ->
                val on = value.value.contains(location.id)
                TeamLocationChoiceRow(on = on, title = location.name, subtitle = location.address) {
                    value.value = if (on) value.value.filter { it != location.id } else value.value + location.id
                }
            }
        }
    }
}

@Composable
private fun TeamLocationChoiceRow(on: Boolean, title: String, subtitle: String?, action: () -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton(onClick = action)
            .background(if (on) Theme.wash(Theme.blue, 10.0) else Theme.surface, shape)
            .border(2.dp, if (on) Theme.blue else Theme.borderSoft, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(on = on, style = Tick.Style.choice)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// MARK: - A number with − and +

@Composable
fun TeamCountStepper(
    value: MutableState<Int>,
    range: IntRange = 1..10,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The web's stepper: one bordered capsule, two 36 round buttons, the number in Fredoka 24.
    Row(
        modifier
            .border(1.dp, Theme.borderSoft, CapsuleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamStepButton("minus", L("One less"), enabled = !(disabled || value.value <= range.first)) {
            value.value = maxOf(range.first, value.value - 1)
        }
        val label = L("Pets at once")
        Text(
            "${value.value}",
            Modifier.widthIn(min = 32.dp).semantics { contentDescription = label },
            style = Theme.heading(24),
            color = Theme.inkStrong,
            textAlign = TextAlign.Center,
        )
        TeamStepButton("plus", L("One more"), enabled = !(disabled || value.value >= range.last)) {
            value.value = minOf(range.last, value.value + 1)
        }
    }
}

/** iOS `TeamStepButtonStyle`: a 36 round button, muted while pressed, 40% when disabled. */
@Composable
private fun TeamStepButton(icon: String, label: String, enabled: Boolean, action: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val isEnabled = enabled && LocalIsEnabled.current
    Box(
        Modifier
            .size(36.dp)
            .graphicsLayer { alpha = if (isEnabled) 1f else 0.4f }
            .background(if (pressed) Theme.surfaceMuted else Color.Transparent, CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = isEnabled, role = Role.Button, onClick = action)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, 16.dp, tint = Theme.inkStrong)
    }
}

// MARK: - An adaptive grid (SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:, alignment: .top)])`)

/** As many columns of at least `minimum` as fit, `spacing` between them and between rows, cells top-aligned. */
@Composable
fun <T> TeamAdaptiveGrid(
    items: List<T>,
    minimum: Dp = 220.dp,
    spacing: Dp = 8.dp,
    modifier: Modifier = Modifier,
    cell: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// MARK: - The web

object TeamWeb {
    /** A page of the web workspace, for what is done there. */
    fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val builder = APIConfig.webURL(path).buildUpon()
        query.toSortedMap().forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build().toString()
    }
}

/** A row that opens a page of the web, saying so. */
@Composable
fun TeamWebRow(
    title: String,
    detail: String,
    systemImage: String = "globe",
    url: String,
    modifier: Modifier = Modifier,
) {
    val openURL = rememberOpenURL()
    Row(
        modifier
            .fillMaxWidth()
            .plainButton { openURL(url) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(systemImage, 16.dp, tint = Theme.blue)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(detail, style = Theme.text(12), color = Theme.inkSoft)
        }
        Spacer(Modifier.width(8.dp))
        Glyph("external-link", 16.dp, tint = Theme.inkSoft)
    }
}

// MARK: - Days ("YYYY-MM-DD")

object TeamDays {
    private val utcZone: ZoneId = ZoneOffset.UTC

    private fun local(key: String): LocalDate? {
        val parts = key.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return runCatching { LocalDate.of(parts[0], parts[1], parts[2]) }.getOrNull()
    }

    /** Noon UTC of a day key, for formatting and arithmetic. */
    fun date(key: String): Instant? = local(key)?.atTime(12, 0)?.toInstant(ZoneOffset.UTC)

    fun key(utc: Instant): String = utc.atZone(utcZone).toLocalDate().let { String.format(java.util.Locale.US, "%04d-%02d-%02d", it.year, it.monthValue, it.dayOfMonth) }

    /** The day a date picker shows (the device's calendar) as a key (iOS `key(picked:)`). */
    fun keyPicked(picked: Instant): String = picked.atZone(ZoneId.systemDefault()).toLocalDate().let { String.format(java.util.Locale.US, "%04d-%02d-%02d", it.year, it.monthValue, it.dayOfMonth) }

    /** A key as the date a date picker should show. */
    fun picked(key: String): Instant {
        val day = local(key) ?: return Instant.now()
        return day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
    }

    fun add(key: String, days: Int): String {
        val date = date(key) ?: return key
        return key(utc = date.plus(days.toLong(), ChronoUnit.DAYS))
    }

    /** Days from `start` to `end`, both counted. */
    fun count(start: String, end: String): Int {
        val a = date(start) ?: return 1
        val b = date(end) ?: return 1
        return ((b.epochSecond - a.epochSecond) / 86_400.0).roundToLong().toInt() + 1
    }

    /** Today in a time zone. */
    fun today(zone: String?): String =
        DateText.dayKey(Instant.now(), zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Workspace.shared.timeZone)

    /** "monday"… of a day key. */
    fun weekday(key: String): String {
        val date = date(key) ?: return "monday"
        val index = date.atZone(utcZone).dayOfWeek.value % 7 // 0 = Sunday
        return listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")[index]
    }

    private fun format(key: String, template: String): String {
        val date = date(key) ?: return key
        return DateText.format(date, template, utcZone).replace(".", "")
    }

    /** "Jue 25 dic" · "Mié 24 dic – Vie 26 dic" (the year only when it isn't this one). */
    fun rangeLabel(start: String, end: String?, today: String): String {
        val last = if ((end ?: start) > start) (end ?: start) else start
        val showYear = start.take(4) != today.take(4) || last.take(4) != today.take(4)
        val template = if (showYear) "EEEdMMMyyyy" else "EEEdMMM"
        val text = if (last == start) format(start, template) else "${format(start, template)} – ${format(last, template)}"
        return text.take(1).uppercase(appLocale) + text.drop(1)
    }

    /** "DIC" over "25" (month, day). */
    fun tile(key: String): Pair<String, String> =
        format(key, "MMM").uppercase(appLocale) to (key.takeLast(2).toIntOrNull() ?: 1).toString()

    /** "25 dic". */
    fun short(key: String): String = format(key, "dMMM")

    /** "Mié 4 nov". */
    fun weekdayLabel(key: String): String {
        val text = format(key, "EEEdMMM")
        return text.take(1).uppercase(appLocale) + text.drop(1)
    }

    /** Upcoming (soonest first) and past (most recent first): (current, past). */
    fun <T> sortSpans(spans: List<T>, today: String, start: (T) -> String, end: (T) -> String): Pair<List<T>, List<T>> {
        val current = spans.filter { end(it) >= today }.sortedWith(compareBy<T>({ start(it) }, { end(it) }))
        val past = spans.filter { end(it) < today }.sortedByDescending { start(it) }
        return current to past
    }
}

/** Swift `String.folding(options: [.diacriticInsensitive, .caseInsensitive], locale:)`. */
fun teamPlain(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(appLocale)
