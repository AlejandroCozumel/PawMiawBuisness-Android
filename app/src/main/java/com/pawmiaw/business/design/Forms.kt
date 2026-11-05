package com.pawmiaw.business

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

//
//  Every form fails the same way (the form error standard, iOS Forms.swift): each invalid
//  field is marked (red border and its sentence), one toast says what to fix, and the form
//  scrolls to the FIRST invalid field.
//
//      val form = remember { FormState() }
//      …
//      PMTextField(L("Name"), text = name, key = "name", form = form)
//      …
//      if (!form.check(listOf("name" to (if (name.value.nonEmpty == null) L("Write the client's name.") else null)))) return
//      try { … } catch (e: Throwable) { form.apply(e, known = mapOf("name" to "name")) }
//
//  A field scrolls itself into view when the form names it (`FormField` does it; any other
//  view: `Modifier.formScrollTarget(key, form)`, iOS `.id(key)`), inside whatever scrolls
//  (a page's `verticalScroll`, a ProviderModal's body). `FormScroll(form)` is kept for the
//  Swift shape and adds nothing.
//
//  SwiftUI → Kotlin for the rest of a form:
//      TextField(prompt, text: $x)                   → PlainTextField(x, prompt)
//      x.fieldChrome(invalid:)                       → FieldChrome(invalid) { … } (a row), or Modifier.fieldChrome(…)
//      Toggle(isOn: $x) { … }.toggleStyle(PMSwitchStyle()) → PMSwitchStyle(isOn = x) { … }
//      UIKeyboardType .emailAddress/.phonePad/.numberPad/.decimalPad → KeyboardType.Email/Phone/Number/Decimal
//      TextInputAutocapitalization .never/.words/.sentences → KeyboardCapitalization.None/Words/Sentences
//

@Stable
class FormState {
    var errors: Map<String, String> by mutableStateOf(emptyMap())
    var scrollTarget: String? by mutableStateOf(null)
    /** Bumped on every scroll request, so the same first field scrolls again on a second try. */
    var scrollRequest: Int by mutableIntStateOf(0)
        private set

    fun error(key: String): String? = errors[key]
    fun clear(key: String) {
        if (errors[key] != null) errors = errors - key
    }

    /** Runs the rules in the order the fields appear; false (with toast and scroll) when any fails. */
    fun check(rules: List<Pair<String, String?>>): Boolean {
        val found = LinkedHashMap<String, String>()
        var first: String? = null
        for ((key, message) in rules) {
            if (message == null) continue
            if (found[key] == null) found[key] = message
            if (first == null) first = key
        }
        errors = found
        if (first == null) return true
        Toast.error(if (found.size == 1) (found[first] ?: "") else LF("Check the %d marked fields.", found.size))
        scrollTo(first)
        return false
    }

    /**
     * A refused save: the server's sentence for each field it named, under the form's own key
     * (`known` maps the request's name to the form's key). Returns true when it placed at least
     * one; the toast says the first.
     */
    fun apply(error: Throwable, known: Map<String, String> = emptyMap()): Boolean {
        val api = error as? APIError
        if (api == null || api.fields.isEmpty()) {
            Toast.error(error)
            return false
        }
        val placed = LinkedHashMap<String, String>()
        for ((path, sentence) in api.fields.toSortedMap()) {
            val key = known[path] ?: known.entries.firstOrNull { path.startsWith(it.key + ".") }?.value
            if (key != null && placed[key] == null) placed[key] = sentence
        }
        Toast.error(api.message)
        val first = placed.keys.firstOrNull() ?: return false
        errors = placed
        scrollTo(first)
        return true
    }

    private fun scrollTo(key: String) {
        scrollTarget = key
        scrollRequest += 1
    }
}

/** Scrolls to a form's first invalid field when it fails (iOS `FormScroll`): the fields do it themselves. */
@Composable
fun FormScroll(form: FormState, content: @Composable () -> Unit) {
    content()
}

/**
 * Marks a view as the place a form scrolls to for `id` (iOS `.id(key)` inside a `FormScroll`):
 * when the form fails on that key, it is brought to the middle of whatever scrolls around it.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.formScrollTarget(id: String, form: FormState?): Modifier = if (form == null) this else composed {
    val requester = remember { BringIntoViewRequester() }
    val seen = remember { intArrayOf(form.scrollRequest) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val request = form.scrollRequest
    LaunchedEffect(request) {
        if (request == seen[0]) return@LaunchedEffect
        seen[0] = request
        if (form.scrollTarget == id) {
            // Room above and below, so the field lands near the middle (iOS `anchor: .center`).
            val room = with(density) { 160.dp.toPx() }
            requester.bringIntoView(Rect(0f, -room, size.width.toFloat(), size.height + room))
        }
    }
    this.bringIntoViewRequester(requester).onSizeChanged { size = it }
}

/**
 * A field's label (web `Label`, §6.5): 14 medium, sentence case, "(optional)" after it; with
 * `caps` the Store/Inventory/Pharmacy modals' `FieldLabel` — 12 bold uppercase tracked 0.12em,
 * grey, "· optional" in normal case. The required mark is coral.
 */
@Composable
fun FieldLabel(
    title: String,
    caps: Boolean = false,
    required: Boolean = false,
    optional: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (caps) {
            Text(title.uppercase(), style = Theme.text(12, FontWeight.Bold).copy(letterSpacing = (12 * 0.12).sp), color = Theme.inkSoft)
        } else {
            Text(title, style = Theme.text(14, FontWeight.Medium), color = Theme.inkStrong)
        }
        if (required) Text("*", style = Theme.text(if (caps) 12 else 14, FontWeight.Bold), color = Theme.coral)
        if (optional) {
            if (caps) {
                Text("· ${L("optional")}", style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
            } else {
                Text(L("(optional)"), style = Theme.text(13), color = Theme.inkSoft)
            }
        }
    }
}

/**
 * A field's label, its control and (when invalid) its sentence. `caps`: the uppercase label
 * of the Store/Inventory/Pharmacy modals (6 to the control); `strongHint`: a hint that says a
 * result (14 semibold navy) rather than a help line.
 */
@Composable
fun FormField(
    label: String,
    key: String? = null,
    hint: String? = null,
    form: FormState? = null,
    optional: Boolean = false,
    caps: Boolean = false,
    required: Boolean = false,
    strongHint: Boolean = false,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val error = key?.let { form?.error(it) }
    // The web's Label: 14 medium, sentence case; 8 to the control; errors 12 medium coral.
    Column(
        modifier.formScrollTarget(key ?: label, form),
        verticalArrangement = Arrangement.spacedBy(if (caps) 6.dp else 8.dp),
    ) {
        FieldLabel(label, caps = caps, required = required, optional = optional)
        control()
        if (error != null) {
            Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
        } else {
            hint.nonEmpty?.let {
                Text(
                    it,
                    style = Theme.text(if (strongHint) 14 else 12, if (strongHint) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (strongHint) Theme.inkStrong else Theme.inkSoft,
                )
            }
        }
    }
}

/**
 * The field chrome (web `Input`) as a modifier: 48 tall (112 multiline), 16 corners, a 1 grey
 * border; while anything inside has focus the border turns mango with a 3 mango ring; invalid
 * is coral with a coral ring. Prefer `FieldChrome { … }`, which also sets the text (16, navy).
 */
fun Modifier.fieldChrome(invalid: Boolean = false, multiline: Boolean = false): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Theme.fieldRadius)
    val motion = tween<Color>(180, easing = EaseOut)
    val edge by animateColorAsState(if (invalid) Theme.coral else if (focused) Theme.mango else Theme.borderSoft, motion, label = "edge")
    val ring = if (invalid) Theme.coral.copy(alpha = 0.2f) else Theme.mango.copy(alpha = 0.5f)
    val ringShown by animateFloatAsState(if (focused || invalid) 1f else 0f, tween(180, easing = EaseOut), label = "ring")
    this
        .onFocusChanged { focused = it.hasFocus }
        .heightIn(min = if (multiline) 112.dp else 48.dp)
        .drawBehind {
            if (ringShown > 0f) {
                val w = 3.dp.toPx()
                val radius = Theme.fieldRadius.toPx() + w / 2
                drawRoundRect(
                    color = ring.copy(alpha = ring.alpha * ringShown),
                    topLeft = Offset(-w / 2, -w / 2),
                    size = Size(size.width + w, size.height + w),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(w),
                )
            }
        }
        .background(Theme.inputFill, shape)
        .border(1.dp, edge, shape)
        .padding(horizontal = 16.dp, vertical = if (multiline) 12.dp else 0.dp)
}

/** iOS `.fieldChrome(invalid:multiline:)` on an HStack: the chrome as a full-width row, its text 16 navy. */
@Composable
fun FieldChrome(
    invalid: Boolean = false,
    multiline: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().fieldChrome(invalid, multiline),
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
    ) {
        val row = this
        CompositionLocalProvider(LocalTextStyle provides Theme.bodyFont, LocalContentColor provides Theme.inkStrong) { row.content() }
    }
}

/** The grey of a field's prompt (iOS `placeholderText`). */
val FieldPrompt = SchemeColor(Color(0x4D3C3C43), Color(0x4DEBEBF5))

/**
 * SwiftUI's bare `TextField(prompt, text:)` (and `SecureField`, and `axis: .vertical` with
 * `lines`): the words and the prompt, no chrome (put it in `FieldChrome`). Text style from
 * `LocalTextStyle`, colour from `LocalContentColor`, the caret PAW Blue. `onChange` runs after
 * each edit (iOS `.onChange(of: text)`), `onSubmit` on the keyboard's action.
 */
@Composable
fun PlainTextField(
    text: MutableState<String>,
    prompt: String = "",
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    autocorrect: Boolean = true,
    secure: Boolean = false,
    lines: IntRange = 1..1,
    style: TextStyle = LocalTextStyle.current,
    imeAction: ImeAction = ImeAction.Default,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
    onChange: ((String) -> Unit)? = null,
) {
    val ink = style.color.takeOrElse { LocalContentColor.current }
    val textStyle = style.copy(color = ink)
    val promptColor = FieldPrompt.resolve()
    val single = lines.last <= 1
    val isEnabled = enabled && LocalIsEnabled.current
    val submit = onSubmit
    BasicTextField(
        value = text.value,
        onValueChange = { value ->
            text.value = value
            onChange?.invoke(value)
        },
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        enabled = isEnabled,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = autocorrect,
            keyboardType = keyboard,
            imeAction = imeAction,
        ),
        keyboardActions = if (submit == null) KeyboardActions.Default else KeyboardActions(
            onDone = { submit() }, onGo = { submit() }, onSearch = { submit() }, onSend = { submit() },
        ),
        singleLine = single,
        minLines = if (single) 1 else lines.first.coerceAtLeast(1),
        maxLines = if (single) 1 else lines.last,
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(Theme.blue),
        decorationBox = { inner ->
            Box(contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart) {
                if (text.value.isEmpty() && prompt.isNotEmpty()) Text(prompt, style = textStyle.copy(color = promptColor), maxLines = if (single) 1 else lines.last)
                inner()
            }
        },
    )
}

/** iOS `PMTextField.Number`. */
object PMTextField {
    /** A quantity box: `large` bold 18 (a count, a delivery), `plain` bold 16; `none` a text. */
    enum class Number { none, large, plain }
}

/**
 * A text field under its label. `caps`/`required`: the Store/Inventory/Pharmacy modals'
 * uppercase label. `number`: a quantity box — bold and tabular (18 for a count or a delivery,
 * 16 with `.plain`), the number pad, a unit after it (`suffix`). `lines`: how many lines a
 * multiline box shows before it scrolls.
 */
@Composable
fun PMTextField(
    label: String,
    text: MutableState<String>,
    key: String? = null,
    form: FormState? = null,
    prompt: String? = null,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    optional: Boolean = false,
    multiline: Boolean = false,
    secure: Boolean = false,
    caps: Boolean = false,
    required: Boolean = false,
    strongHint: Boolean = false,
    number: PMTextField.Number = PMTextField.Number.none,
    suffix: String? = null,
    lines: IntRange = 3..8,
    modifier: Modifier = Modifier,
) {
    val invalid = key?.let { form?.error(it) } != null
    val numberKeyboard = if (keyboard == KeyboardType.Text) KeyboardType.Number else keyboard
    val plainKeyboard = keyboard == KeyboardType.Email || keyboard == KeyboardType.Phone
    OnChange(text.value) { if (key != null) form?.clear(key) }
    FormField(label, key = key, hint = hint, form = form, optional = optional, caps = caps, required = required, strongHint = strongHint, modifier = modifier) {
        val requester = remember { FocusRequester() }
        FieldChrome(
            invalid = invalid,
            multiline = multiline,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { runCatching { requester.requestFocus() } } },
        ) {
            when {
                number != PMTextField.Number.none -> {
                    PlainTextField(
                        text,
                        prompt ?: "0",
                        Modifier.weight(1f),
                        keyboard = numberKeyboard,
                        capitalization = KeyboardCapitalization.None,
                        autocorrect = false,
                        style = Theme.text(if (number == PMTextField.Number.large) 18 else 16, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                        focusRequester = requester,
                    )
                    if (suffix != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(suffix, style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                else -> PlainTextField(
                    text,
                    prompt ?: "",
                    Modifier.weight(1f),
                    keyboard = keyboard,
                    capitalization = if (keyboard == KeyboardType.Email) KeyboardCapitalization.None else capitalization,
                    autocorrect = !plainKeyboard,
                    secure = secure,
                    lines = if (multiline && !secure) lines else 1..1,
                    focusRequester = requester,
                )
            }
        }
    }
}

/** An amount in the business's currency, typed as text and read as cents. */
@Composable
fun MoneyField(
    label: String,
    cents: MutableState<Int?>,
    key: String? = null,
    form: FormState? = null,
    hint: String? = null,
    optional: Boolean = false,
    required: Boolean = false,
    /** The Store / Inventory / Pharmacy small-caps label. */
    caps: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val text = remember { mutableStateOf("") }
    // iOS `.onChange(of: text)`: every change of the words, typed or set here, reads the cents.
    OnChange(text.value) { value ->
        cents.value = Money.parse(value)
        if (key != null) form?.clear(key)
    }
    LaunchedEffect(Unit) { if (text.value.isEmpty()) cents.value?.let { text.value = Money.input(it) } }
    // An amount set by code (a total, a suggested price) shows; typing never loops back here.
    OnChange(cents.value) { value -> if (Money.parse(text.value) != value) text.value = Money.input(value) }
    FormField(label, key = key, hint = hint, form = form, optional = optional, caps = caps, required = required, modifier = modifier) {
        val requester = remember { FocusRequester() }
        FieldChrome(
            invalid = key?.let { form?.error(it) } != null,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { runCatching { requester.requestFocus() } } },
        ) {
            Text("$", style = Theme.text(16, FontWeight.Bold), color = Theme.inkSoft)
            Spacer(Modifier.width(6.dp))
            PlainTextField(text, "0.00", Modifier.weight(1f), keyboard = KeyboardType.Decimal, autocorrect = false, focusRequester = requester)
            Spacer(Modifier.width(6.dp))
            Text(Workspace.shared.currency, style = Theme.captionFont, color = Theme.inkSoft)
        }
    }
}

/** A row with a label and a switch (the web's SwitchCard). */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    isOn: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    // The web's SwitchCard: a bordered row, the switch at the right.
    PMSwitchStyle(
        isOn = isOn,
        modifier = modifier
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong)
            if (subtitle != null) Text(subtitle, style = Theme.text(14), color = Theme.inkSoft)
        }
    }
}

/** iOS `ChoiceCards.Layout` and the tiles' option. */
object ChoiceCards {
    enum class Layout { stack, tiles }

    /** A tile side by side with the others (web `PetSexButton`, kg/lb), with an optional icon. */
    data class Tile<Value>(val value: Value, val title: String, val icon: String? = null)
}

/**
 * A choice among a few values drawn as cards, one under another (the modal's yes/no rows,
 * `.provider-modal-toggle`): radio cards, the chosen one on an aqua wash. Options are
 * (value, title, subtitle).
 */
@Composable
fun <Value> ChoiceCards(
    options: List<Triple<Value, String, String?>>,
    selection: MutableState<Value>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, title, subtitle) ->
            val chosen = selection.value == value
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (chosen) Theme.wash(Theme.aqua, 18.0) else Theme.surface, shape)
                    .border(1.5.dp, if (chosen) Theme.aqua.copy(alpha = 0.7f) else Theme.borderSoft, shape)
                    .selectable(selected = chosen, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { selection.value = value }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph(if (chosen) "circle-dot" else "circle", 20.dp, tint = if (chosen) Theme.blue else Theme.inkSoft)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                    if (subtitle != null) Text(subtitle, style = Theme.text(13), color = Theme.inkSoft)
                }
            }
        }
    }
}

/**
 * Tiles side by side (web `PetSexButton`, the kg/lb buttons): bordered, 48 tall, an optional
 * icon, the chosen one PAW Blue-edged on MIAW yellow at 24%.
 */
@JvmName("ChoiceCardsTiles")
@Composable
fun <Value> ChoiceCards(
    tiles: List<ChoiceCards.Tile<Value>>,
    selection: MutableState<Value>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEach { tile ->
            val chosen = tile.value == selection.value
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(if (chosen) Theme.wash(Theme.lime, 24.0) else Theme.surface, shape)
                    .border(2.dp, if (chosen) Theme.blue else Theme.borderSoft, shape)
                    .selectable(selected = chosen, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { selection.value = tile.value }
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
                    if (tile.icon != null) Glyph(tile.icon, 16.dp)
                    FittedText(tile.title, Modifier.weight(1f, fill = false), style = Theme.text(14, FontWeight.SemiBold), minimumScaleFactor = 0.8f)
                }
            }
        }
    }
}

/** A picker drawn as a field (a menu of the options). */
@Composable
fun <Value> MenuField(
    label: String,
    options: List<Pair<Value, String>>,
    selection: MutableState<Value>,
    key: String? = null,
    form: FormState? = null,
    optional: Boolean = false,
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FormField(label, key = key, form = form, optional = optional, required = required, modifier = modifier) {
        AppMenu(
            label = {
                FieldChrome(invalid = key?.let { form?.error(it) } != null) {
                    Text(
                        options.firstOrNull { it.first == selection.value }?.second ?: L("Choose"),
                        style = Theme.text(14),
                        color = Theme.inkStrong,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            options.forEach { (value, title) ->
                item(title) {
                    selection.value = value
                    if (key != null) form?.clear(key)
                }
            }
        }
    }
}

/**
 * The web's switch (ui/switch.tsx): a 32 × 18.4 track, emerald when on, pale red when off, a
 * white 16 thumb. SwiftUI `Toggle(isOn: $x) { label }.toggleStyle(PMSwitchStyle())`: the label
 * leads and the switch sits at the end; without a label (`{ EmptyView() }.fixedSize()`), only
 * the switch. Read out as "On"/"Off".
 */
@Composable
fun PMSwitchStyle(
    isOn: MutableState<Boolean>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable RowScope.() -> Unit)? = null,
) {
    val isEnabled = enabled && LocalIsEnabled.current
    val on = isOn.value
    val motion = tween<Color>(180, easing = EaseOut)
    val track by animateColorAsState(if (on) Theme.switchOn else Theme.switchOff, motion, label = "track")
    val thumbX by animateDpAsState(if (on) 14.8.dp else 1.2.dp, tween(180, easing = EaseOut), label = "thumb")
    val stateWord = if (on) L("On") else L("Off")
    Row(
        modifier
            .then(if (label != null) Modifier.fillMaxWidth() else Modifier)
            .toggleable(value = on, enabled = isEnabled, role = Role.Switch, interactionSource = remember { MutableInteractionSource() }, indication = null) { isOn.value = it }
            .semantics(mergeDescendants = true) { stateDescription = stateWord },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SwiftUI `Spacer(minLength: 0)`: the label takes the room the switch leaves.
        if (label != null) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { label() }
        Box(
            Modifier
                .graphicsLayer { alpha = if (isEnabled) 1f else 0.5f }
                .size(32.dp, 18.4.dp)
                .background(track, RoundedCornerShape(50)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset(x = thumbX)
                    .size(16.dp)
                    .softShadow(CircleShape, Color.Black.copy(alpha = 0.15f), radius = 1.dp, y = 0.5.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

/** SwiftUI `.onChange(of: value)`: runs on each change after the first composition. */
@Composable
private fun <T> OnChange(value: T, action: (T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val previous = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (previous[0] != value) {
            previous[0] = value
            latest(value)
        }
    }
}
