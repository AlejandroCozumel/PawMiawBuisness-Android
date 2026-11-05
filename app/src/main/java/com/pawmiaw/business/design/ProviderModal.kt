package com.pawmiaw.business

import androidx.activity.compose.BackHandler

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

//
//  Every sheet of the business side (iOS ProviderModal.swift, web components/app/provider-modal):
//  the header takes the modal's accent colour with its icon in a pebble, the body is white with
//  stitched dividers, there is no ✕. Cancel closes; a pull down (or the back button) closes at
//  once when nothing changed, and while something did the sheet stays and asks
//  "¿Descartar los cambios?" — Cancel asks too.
//
//      if (adding) AppSheet(onDismissRequest = { adding = false }) {
//          ProviderModal(title = L("New client"), systemImage = "person.badge.plus", accent = ModalAccent.aqua,
//                        isDirty = name.value.isNotEmpty(), form = form, primaryTitle = L("Save"), isWorking = saving,
//                        onPrimary = { save() }) { … fields … }
//      }
//
//  A sheet with nothing between its header and its answer (a confirm, DiscardChangesSheet)
//  passes no content (or `showsBody = false`): it is only as tall as its header and footer.
//

/** The colour a sheet's header takes (web `accent` of `ProviderModal`). */
sealed class ModalAccent {
    data object butter : ModalAccent()
    data object aqua : ModalAccent()
    data object pink : ModalAccent()
    data object lavender : ModalAccent()
    data object mint : ModalAccent()
    data object coral : ModalAccent()
    data object mango : ModalAccent()
    data object lime : ModalAccent()
    /** `--brand-sky` (#eaf4ff): the settings history's pale blue. */
    data object sky : ModalAccent()
    /** `--ink-soft`: a quiet, grey sheet (a death recorded, a stay ended). */
    data object grey : ModalAccent()
    /** `--hue-peach`: an orange category (a surgery room, a sale). */
    data object peach : ModalAccent()
    /** Any category hue (a room's kind, a role): `ROOM_KIND_HUE`, `ROLE_HUE`. */
    data class hue(val hue: Hue) : ModalAccent()
    /** `--brand-teal`: PAW Blue (a visit's "Sí vino", a sheet about the business itself). */
    data object blue : ModalAccent()

    val color: Color
        @Composable get() = when (this) {
            butter -> Theme.butter
            aqua -> Theme.aqua
            pink -> Theme.pink
            lavender -> Theme.lavender
            mint -> Hue.mint.color
            coral -> Theme.coral
            sky -> Theme.blueWash
            mango -> Theme.mango
            lime -> Theme.lime
            grey -> Theme.inkSoft
            peach -> Hue.peach.color
            is hue -> hue.color
            blue -> Theme.blue
        }

    val ink: Color get() = if (this == coral) Color.White else Theme.inkOnBright
}

/**
 * A button of a sheet's footer besides its primary action and Cancel (web
 * `ProviderModalAction intent="secondary" | "danger"`): "Archive", "Admit without the gate".
 * `tint`: the words' ink (web: a secondary action written in red, "Admit without the gate").
 */
class ModalAction(
    val title: String,
    val icon: String? = null,
    val variant: ButtonVariant = ButtonVariant.outline,
    val isWorking: Boolean = false,
    val disabled: Boolean = false,
    val tint: Color? = null,
    val action: () -> Unit,
) {
    val id: UUID = UUID.randomUUID()
}

private val stepEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * A multi-step sheet's steps (web `ProviderModalSteps`): a 27 dot per step — "01" on white
 * (or the step's icon), PAW Blue with a check once done — the current one blue inside a 34
 * white capsule with its name; 10 pt connectors between them, blue once done. Pass it to
 * `ProviderModal(steps =)`, which shows it on its own row under the title; `steps()` draws it.
 * `icons`: icons in place of the numbers (web `icons`).
 */
class ModalSteps(val steps: List<String>, val icons: List<String>? = null, val current: Int) {
    /** Steps given as (title, icon) pairs. */
    constructor(steps: List<Pair<String, String>>, current: Int) : this(steps.map { it.first }, steps.map { it.second }, current)

    @Composable
    operator fun invoke(modifier: Modifier = Modifier) {
        ModalStepsRow(this, modifier)
    }
}

@Composable
private fun ModalStepsRow(model: ModalSteps, modifier: Modifier) {
    val steps = model.steps
    val current = model.current
    val label = LF("Step %d of %d", current + 1, steps.size) + (if (current in steps.indices) ". " + steps[current] else "")
    Row(
        modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.indices.forEach { index ->
            if (index > 0) {
                val rule by animateColorAsState(
                    if (index <= current) Theme.blue else Theme.inkStrong.copy(alpha = 0.18f),
                    tween(420, easing = stepEasing),
                    label = "step rule",
                )
                Box(Modifier.size(10.4.dp, 2.dp).background(rule, CapsuleShape))
            }
            ModalStep(model, index)
        }
    }
}

@Composable
private fun ModalStep(model: ModalSteps, index: Int) {
    val current = model.current
    val isCurrent = index == current
    val lit = index <= current
    val fill by animateColorAsState(if (lit) Theme.blue else Theme.surface, tween(420, easing = stepEasing), label = "step dot")
    Row(
        Modifier
            .animateContentSize(tween(420, easing = stepEasing))
            .height(33.6.dp)
            .background(if (isCurrent) Theme.surface else Color.Transparent, CapsuleShape)
            .then(if (isCurrent) Modifier.border(1.5.dp, Theme.inkStrong.copy(alpha = 0.1f), CapsuleShape) else Modifier)
            .padding(start = 3.2.dp, end = if (isCurrent) 13.6.dp else 3.2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(27.2.dp)
                .background(fill, CircleShape)
                .then(if (!lit) Modifier.border(1.5.dp, Theme.inkStrong.copy(alpha = 0.14f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val icon = model.icons?.getOrNull(index)
            when {
                index < current -> Glyph("check", 16.dp, tint = Theme.onBlue)
                icon != null -> Glyph(icon, 14.4.dp, tint = if (lit) Theme.onBlue else Theme.inkSoft)
                else -> Text(
                    String.format(Locale.US, "%02d", index + 1),
                    style = brandText(10.9, FontWeight.ExtraBold),
                    color = if (lit) Theme.onBlue else Theme.inkSoft,
                )
            }
        }
        if (isCurrent) {
            Text(model.steps[index], style = brandText(12.5, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A `ProviderModal` shown as a pushed page (a booking, a record or the checkout opened from
 * another screen): its header runs under the status bar and has no grab handle
 * (iOS `\.modalAsPage`). Provide `true` around the page.
 */
val LocalModalAsPage = compositionLocalOf { false }

/**
 * The sheet shell every form uses.
 *
 * - `steps`: a multi-step sheet's steps, on their own row under the title, as on the web; a
 *   step change slides the body (in from 20 pt on the side it comes from, out 12 pt the other way).
 * - `onBack`: past the first step the footer's Cancel becomes "‹ Back" and calls this.
 * - `secondaryActions`: more footer buttons, between the primary action and Cancel.
 * - `primaryIcon`: the primary action's icon (a check, a save disk), when the web's has one.
 * - `footerSummary`: a line over the footer's buttons (a total, the vaccines' state).
 * - `showsCancel`: false where the web's footer has no Cancel (a sheet whose work is done).
 * - `pebbleImageURL`: a photo in the pebble instead of the icon (a pet's sheet).
 * - `showsBody`: false for a question with nothing between its header and its answer; no
 *   `content` means the same (Swift `EmptyView`). Such a sheet fits its header and footer.
 *
 * The root takes `modifier` first, so a caller's `Modifier.sheetPresentation(…)` (iOS
 * `.presentationDetents`) wins over the default whole height of a sheet with a body.
 */
@Composable
fun ProviderModal(
    title: String,
    subtitle: String? = null,
    systemImage: String = "square.and.pencil",
    accent: ModalAccent = ModalAccent.aqua,
    isDirty: Boolean = false,
    form: FormState? = null,
    primaryTitle: String? = null,
    primaryVariant: ButtonVariant = ButtonVariant.primary,
    isWorking: Boolean = false,
    cancelTitle: String = L("Cancel"),
    steps: ModalSteps? = null,
    onBack: (() -> Unit)? = null,
    secondaryActions: List<ModalAction> = emptyList(),
    primaryIcon: String? = null,
    primaryDisabled: Boolean = false,
    footerSummary: (@Composable () -> Unit)? = null,
    showsCancel: Boolean = true,
    pebbleImageURL: String? = null,
    showsBody: Boolean = true,
    onPrimary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val asPage = LocalModalAsPage.current
    val dismiss = LocalDismiss.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val fallbackForm = remember { FormState() }
    val sheet = LocalAppSheet.current
    val inCard = sheet != null && AppSheetSizing.presentationIsRoomy()

    // A question with nothing between its header and its answer (a confirm, "Discard the changes?").
    val body = content?.takeIf { showsBody }
    val hasBody = body != null

    // The sheet's ground and corners (iOS `.presentationBackground`, `.presentationCornerRadius`).
    if (sheet != null) {
        PresentationBackground(Theme.surface)
        SideEffect { sheet.cornerRadius = Theme.cardRadius }
    }
    // A pull or a back press is refused while something changed or a save runs; a refused one
    // with changes asks, as the web does (iOS `.interactiveDismissDisabled` + DismissAttemptReader).
    if (!asPage) {
        DismissAttemptReader(blocks = isDirty || isWorking) { if (isDirty && !isWorking) confirmDiscard = true }
    } else {
        // Opened as a page (no back button, as iOS): the back gesture does what Cancel does —
        // asks first when something changed, waits while a save runs.
        BackHandler(enabled = !confirmDiscard) {
            if (isWorking) Unit else if (isDirty) confirmDiscard = true else dismiss()
        }
    }

    Column(
        modifier
            .then(
                if (hasBody) Modifier.sheetPresentation(setOf(PresentationDetent.large))
                else Modifier.sheetPresentation(hugsContent = true)
            )
            .fillMaxWidth()
            .background(Theme.surface),
    ) {
        // The header draws the iOS handle (41.6×4.8, ink 22%) in place of the sheet's generic one.
        PresentationDragIndicator(false)
        ModalHeader(title, subtitle, systemImage, accent, steps, pebbleImageURL, asPage, drawsHandle = true)
        if (body != null) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Theme.surface)
                    .scrollDismissesKeyboard()
                    .verticalScroll(rememberScrollState()),
            ) {
                FormScroll(form ?: fallbackForm) {
                    StepSlide(steps?.current ?: 0) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Column(
                                Modifier
                                    .widthIn(max = 704.dp)
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                content = body,
                            )
                        }
                    }
                }
            }
        }
        ModalFooter(
            hasBody = hasBody,
            inCard = inCard,
            footerSummary = footerSummary,
            primaryTitle = primaryTitle,
            onPrimary = onPrimary,
            primaryIcon = primaryIcon,
            primaryVariant = primaryVariant,
            primaryDisabled = primaryDisabled,
            isWorking = isWorking,
            secondaryActions = secondaryActions,
            onBack = onBack,
            showsCancel = showsCancel,
            cancelTitle = cancelTitle,
            onCancel = { if (isDirty) confirmDiscard = true else dismiss() },
        )
    }

    // The web's discard card: pink, the eraser, "Keep editing" beside "Discard".
    if (confirmDiscard) {
        AppSheet(onDismissRequest = { confirmDiscard = false }) {
            DiscardChangesSheet(discard = { confirmDiscard = false; dismiss() })
        }
    }
}

/**
 * The web's header: the accent at 26% over the surface, a grab handle, a tilted pebble
 * (accent 72%) with the icon, the title in Fredoka 20, the steps on their own row, a 1.5 pt
 * rule of the accent under it.
 */
@Composable
private fun ModalHeader(
    title: String,
    subtitle: String?,
    systemImage: String,
    accent: ModalAccent,
    steps: ModalSteps?,
    pebbleImageURL: String?,
    asPage: Boolean,
    drawsHandle: Boolean,
) {
    val tone = accent.color
    val rule = Theme.mix(tone, 45.0, Theme.borderSoft)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(tone, 26.0))
            .drawWithContent {
                drawContent()
                val line = 1.5.dp.toPx()
                drawRect(rule, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            }
            // On a page the tint runs under the status bar; the words stay below it.
            .then(if (asPage) Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)) else Modifier),
    ) {
        // A sheet opened as a page can't be pulled away: no handle.
        if (!asPage) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.8.dp), contentAlignment = Alignment.Center) {
                if (drawsHandle) Box(Modifier.size(41.6.dp, 4.8.dp).background(Theme.inkStrong.copy(alpha = 0.22f), CapsuleShape))
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.4.dp).rotate(-4f), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().background(Theme.wash(tone, 72.0), CircleShape))
                    Glyph(
                        systemImage,
                        20.8.dp,
                        tint = if (accent == ModalAccent.coral) Theme.destructive else Theme.inkStrong,
                        modifier = Modifier.rotate(4f),
                    )
                    val url = APIConfig.assetURL(pebbleImageURL)
                    if (url != null) {
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().clip(CircleShape).rotate(4f),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.8.dp)) {
                    Text(title, style = Theme.heading(20).tracking(-0.2), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = Theme.text(13.1), color = Theme.inkStrong.copy(alpha = 0.72f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            steps?.invoke()
        }
    }
}

/**
 * The footer (owner, 2026-11-04): two buttons share one row, half each — Cancel or Back on
 * the left, the action on the right; three or more stack, the action on top, then the other
 * actions, then Cancel (or "‹ Back" past the first step), over a 1.5 pt rule. The bar runs to
 * the sheet's bottom edge with 24 under the buttons (plus the navigation bar), as the family
 * app's sheets do; a sheet without a body keeps 16 under them.
 */
@Composable
private fun ModalFooter(
    hasBody: Boolean,
    inCard: Boolean,
    footerSummary: (@Composable () -> Unit)?,
    primaryTitle: String?,
    onPrimary: (() -> Unit)?,
    primaryIcon: String?,
    primaryVariant: ButtonVariant,
    primaryDisabled: Boolean,
    isWorking: Boolean,
    secondaryActions: List<ModalAction>,
    onBack: (() -> Unit)?,
    showsCancel: Boolean,
    cancelTitle: String,
    onCancel: () -> Unit,
) {
    val hasPrimary = primaryTitle != null && onPrimary != null
    val leadingCount = if (onBack != null || showsCancel) 1 else 0
    val buttonCount = (if (hasPrimary) 1 else 0) + secondaryActions.size + leadingCount
    val border = Theme.borderSoft
    val fitted = fitsWithoutBodyInset(fits = !hasBody && !inCard)

    @Composable
    fun Primary(modifier: Modifier) {
        if (primaryTitle != null && onPrimary != null) {
            PMButton(
                primaryTitle,
                systemImage = primaryIcon,
                variant = primaryVariant,
                size = ButtonSize.modal,
                fullWidth = true,
                isWorking = isWorking,
                enabled = !primaryDisabled,
                modifier = modifier,
                action = onPrimary,
            )
        }
    }

    @Composable
    fun Extra(extra: ModalAction, modifier: Modifier) {
        PMButton(
            extra.title,
            systemImage = extra.icon,
            variant = extra.variant,
            size = ButtonSize.modal,
            fullWidth = true,
            isWorking = extra.isWorking,
            tint = extra.tint,
            enabled = !(extra.disabled || isWorking),
            modifier = modifier,
            action = extra.action,
        )
    }

    // Back on a later step, else Cancel (which asks before dropping changes).
    @Composable
    fun Leading(modifier: Modifier) {
        if (onBack != null) {
            PMButton(L("Back"), systemImage = "chevron-left", variant = ButtonVariant.outline, size = ButtonSize.modal, fullWidth = true, enabled = !isWorking, modifier = modifier, action = onBack)
        } else if (showsCancel) {
            PMButton(cancelTitle, variant = ButtonVariant.outline, size = ButtonSize.modal, fullWidth = true, enabled = !isWorking, modifier = modifier, action = onCancel)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface)
            .drawWithContent {
                drawContent()
                drawRect(border, size = Size(size.width, 1.5.dp.toPx()))
            }
            .then(
                when {
                    inCard -> Modifier.padding(bottom = if (hasBody) 24.dp else 16.dp)
                    hasBody -> Modifier.padding(bottom = 24.dp).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    else -> Modifier.padding(bottom = 16.dp + fitted)
                }
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 704.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            footerSummary?.invoke()
            if (buttonCount == 2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Cancel or Back on the left; with neither, the one other action takes its place.
                    if (leadingCount == 1) {
                        Leading(Modifier.weight(1f))
                    } else {
                        secondaryActions.firstOrNull()?.let { Extra(it, Modifier.weight(1f)) }
                    }
                    if (hasPrimary) Primary(Modifier.weight(1f))
                }
            } else if (buttonCount > 0) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Primary(Modifier.fillMaxWidth())
                    secondaryActions.forEach { extra -> Extra(extra, Modifier.fillMaxWidth()) }
                    Leading(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * A titled part inside a modal (web `ProviderModalSection`): the title in Fredoka 16.8 beside
 * a 30 pt rounded icon chip, the fields, and a 2 pt dashed rule before the next part.
 * `required`: the red * after the title (a part the web marks required, "Why *").
 */
@Composable
fun ModalSection(
    title: String,
    subtitle: String? = null,
    systemImage: String? = null,
    tint: Color = Theme.aqua,
    required: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (systemImage != null) {
                Box(
                    Modifier.size(30.4.dp).background(Theme.wash(tint, 45.0), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Glyph(systemImage, 16.dp, tint = Theme.inkStrong) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val coral = Theme.coral
                Text(
                    buildAnnotatedString {
                        append(title)
                        if (required) withStyle(SpanStyle(color = coral)) { append(" *") }
                    },
                    style = Theme.heading(16.8),
                    color = Theme.inkStrong,
                )
                if (subtitle != null) Text(subtitle, style = Theme.text(14), color = Theme.inkSoft)
            }
        }
        content()
        StitchedDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Every plain yes/no (web `ProviderConfirmSheet`): a compact sheet with the pink header and
 * the triangle, the question's words under the title, Cancel (or the sheet's own words:
 * "Keep it", "Not now") beside the red action. Optionally a reason (money actions while the
 * owner is away; `reasonRequired` refuses an empty one) or a word to type before the action
 * unlocks (`confirmWord`, web "Enter DELETE to continue"). `accent`/`icon`: the header's
 * colour and icon, the web's pink and triangle unless the sheet says otherwise.
 */
@Composable
fun ConfirmSheet(
    title: String,
    message: String? = null,
    confirmTitle: String,
    destructive: Boolean = false,
    askReason: Boolean = false,
    reasonRequired: Boolean = false,
    reasonLabel: String = L("Reason"),
    cancelTitle: String = L("Cancel"),
    accent: ModalAccent = ModalAccent.pink,
    icon: String = "triangle-alert",
    confirmIcon: String? = null,
    confirmWord: String? = null,
    confirmHint: String? = null,
    modifier: Modifier = Modifier,
    action: suspend (String?) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val reason = remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val form = remember { FormState() }
    val scope = rememberCoroutineScope()
    val act by rememberUpdatedState(action)

    val typedMatches = confirmWord == null || typed.trim().uppercase() == confirmWord.uppercase()
    val hasBody = askReason || confirmWord != null

    ProviderModal(
        title = title,
        subtitle = message,
        systemImage = icon,
        accent = accent,
        isDirty = reason.value.isNotEmpty(),
        form = form,
        primaryTitle = confirmTitle,
        primaryVariant = if (destructive) ButtonVariant.destructive else ButtonVariant.primary,
        isWorking = working,
        cancelTitle = cancelTitle,
        primaryIcon = confirmIcon ?: (if (destructive) "triangle-alert" else null),
        primaryDisabled = !typedMatches,
        showsBody = hasBody,
        onPrimary = onPrimary@{
            if (askReason && reasonRequired) {
                if (!form.check(listOf("reason" to (if (reason.value.nonEmpty == null) L("Write the reason.") else null)))) return@onPrimary
            }
            working = true
            scope.launch {
                try {
                    act(reason.value.nonEmpty)
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    form.apply(e, mapOf("reason" to "reason"))
                } finally {
                    working = false
                }
            }
        },
        modifier = modifier.confirmDetents(hasBody),
    ) {
        if (askReason) {
            PMTextField(reasonLabel, text = reason, key = "reason", form = form, optional = !reasonRequired, multiline = true)
        }
        if (confirmWord != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LF("Enter %@ to continue", confirmWord), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                if (confirmHint != null) Text(confirmHint, style = Theme.text(14), color = Theme.inkSoft)
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth().fieldChrome(),
                    textStyle = Theme.bodyFont.copy(color = Theme.inkStrong),
                    singleLine = true,
                    cursorBrush = SolidColor(Theme.blue),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (typed.isEmpty()) Text(confirmWord, style = Theme.bodyFont, color = Theme.inkSoft)
                            inner()
                        }
                    },
                )
            }
        }
    }
}

/** A confirm with a reason or a typed word takes room; a plain one fits itself (ProviderModal). iOS `ConfirmDetents`. */
private fun Modifier.confirmDetents(hasBody: Boolean): Modifier =
    if (hasBody) this.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)) else this

/**
 * The bottom inset a fitted sheet had last time (the navigation bar's strip), so the next one
 * opens at its height straight away, before the window has told this one its insets.
 */
object SheetFit {
    var lastInset: Dp = 0.dp
}

/**
 * iOS `FitsWithoutBody`: a sheet with no body is as tall as its header and footer, and what sits
 * under the buttons is their own 16 pt above the navigation bar. Compose sizes the sheet to
 * its content, so only the bar's inset is needed: the live one, else the one last seen.
 */
@Composable
private fun fitsWithoutBodyInset(fits: Boolean): Dp {
    if (!fits) return 0.dp
    val density = LocalDensity.current
    val live = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    SideEffect { if (live > 0.dp) SheetFit.lastInset = live }
    return if (live > 0.dp) live else SheetFit.lastInset
}

/**
 * "¿Descartar los cambios?" (web `ProviderConfirmModal` for discarding): a compact pink sheet
 * over the one being left; Keep editing closes it, Discard closes both.
 */
@Composable
fun DiscardChangesSheet(discard: () -> Unit, modifier: Modifier = Modifier) {
    ProviderModal(
        title = L("Discard the changes?"),
        subtitle = L("What you wrote here won't be saved."),
        systemImage = "eraser",
        accent = ModalAccent.pink,
        primaryTitle = L("Discard"),
        primaryVariant = ButtonVariant.destructive,
        cancelTitle = L("Keep editing"),
        onPrimary = discard,
        modifier = modifier,
    )
}

/**
 * Hears a pull down or a back press the sheet refused (iOS `DismissAttemptReader`, which
 * stands in as the UIKit presentation controller's delegate): registers with the sheet's
 * `SheetDismissAttempts` for as long as the modal is shown. `blocks` refuses the pull.
 */
@Composable
private fun DismissAttemptReader(blocks: Boolean, onAttempt: () -> Unit) {
    val attempts = LocalSheetDismissAttempts.current ?: return
    val blocksNow by rememberUpdatedState(blocks)
    val attempted by rememberUpdatedState(onAttempt)
    val key = remember { Any() }
    DisposableEffect(attempts) {
        attempts.register(key, blocks = { blocksNow }, onAttempt = { attempted() })
        onDispose { attempts.unregister(key) }
    }
}

/** What a step change keeps between frames: the live layer, the last step's picture, the side it came from. */
private class StepStage {
    var step: Int? = null
    var direction = 1f
    var liveIsA = true
    var hasFrozen = false
}

/**
 * A step change slides, as the web's ProviderModalStage (iOS `.id(step)` + an asymmetric
 * transition): the new step comes in from 20 pt on the side it comes from while the last
 * step's picture leaves 12 pt the other way, both fading, 220 ms. The body is drawn through
 * a graphics layer so the step being left can be shown as it last looked.
 */
@Composable
private fun StepSlide(step: Int, content: @Composable () -> Unit) {
    val layerA = rememberGraphicsLayer()
    val layerB = rememberGraphicsLayer()
    val stage = remember { StepStage() }
    val progress = remember { mutableFloatStateOf(1f) }
    val previous = stage.step
    if (previous != step) {
        if (previous != null) {
            stage.direction = if (step >= previous) 1f else -1f
            stage.liveIsA = !stage.liveIsA
            stage.hasFrozen = true
            progress.floatValue = 0f
        }
        stage.step = step
    }
    LaunchedEffect(step) {
        if (progress.floatValue < 1f) {
            animate(progress.floatValue, 1f, animationSpec = tween(220, easing = stepEasing)) { value, _ -> progress.floatValue = value }
        }
        stage.hasFrozen = false
    }
    Box(
        Modifier.drawWithContent {
            val live = if (stage.liveIsA) layerA else layerB
            val frozen = if (stage.liveIsA) layerB else layerA
            val p = progress.floatValue
            live.record { this@drawWithContent.drawContent() }
            if (stage.hasFrozen && p < 1f) {
                frozen.alpha = 1f - p
                translate(left = -12.dp.toPx() * stage.direction * p) { drawLayer(frozen) }
            }
            live.alpha = p
            translate(left = 20.dp.toPx() * stage.direction * (1f - p)) { drawLayer(live) }
        }
    ) { content() }
}
