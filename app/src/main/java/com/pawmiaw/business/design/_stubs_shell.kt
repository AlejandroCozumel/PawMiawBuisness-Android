package com.pawmiaw.business

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  Stand-ins for the design pieces other areas port at the same time; the shell, the sign-in
//  and the barcode scanner use them. Deleted at merge: nothing real lives here.
//

// STUB: Design/Buttons.swift
enum class ButtonVariant { primary, outline, destructive, ghost, link }

// STUB: Design/Buttons.swift
enum class ButtonSize { regular, compact, header, modal, slim, small }

// STUB: Design/Buttons.swift
@Composable
fun PMButton(
    title: String,
    systemImage: String? = null,
    trailingIcon: String? = null,
    variant: ButtonVariant = ButtonVariant.primary,
    size: ButtonSize = ButtonSize.regular,
    fullWidth: Boolean = false,
    isWorking: Boolean = false,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    Text(title, modifier.clickable(onClick = action).padding(8.dp))
}

// STUB: Design/Buttons.swift
@Composable
fun IconCircleButton(
    systemImage: String,
    label: String,
    tint: Color = Theme.inkSoft,
    size: Dp = 36.dp,
    bordered: Boolean = true,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    Box(modifier.size(size).clickable(onClick = action), contentAlignment = Alignment.Center) { Glyph(systemImage, 16.dp, tint = tint) }
}

// STUB: Design/Components.swift
@Composable
fun CountBadge(count: Int, color: Color = Theme.destructive, cap: Int = 99, modifier: Modifier = Modifier) {
    if (count > 0) Text(if (count > cap) "$cap+" else "$count", modifier, color = color)
}

// STUB: Design/Components.swift
@Composable
fun Card(
    padding: Dp = 16.dp,
    tint: Color? = null,
    spacing: Dp = 12.dp,
    flat: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
}

// STUB: Design/Components.swift
@Composable
fun PageScroll(
    spacing: Dp = 20.dp,
    maxWidth: Dp = 820.dp,
    refresh: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
}

// STUB: Design/ModuleHeader.swift
object IconBadge {
    enum class Ink { onAccent, strong, accent }
}

// STUB: Design/ModuleHeader.swift
@Composable
fun IconBadge(
    icon: String,
    accent: Color = Theme.blue,
    size: Dp = 44.dp,
    iconSize: Dp? = null,
    ink: IconBadge.Ink = IconBadge.Ink.onAccent,
    washPercent: Double? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) { Glyph(icon, iconSize ?: (size * 0.45f), tint = accent) }
}

// STUB: Design/Components.swift
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    detail: String? = null,
    showsChevron: Boolean = false,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading()
        Column { Text(title); subtitle?.let { Text(it) }; detail?.let { Text(it) } }
        trailing()
    }
}

// STUB: Design/Components.swift
@Composable
fun Avatar(name: String?, imageURL: String? = null, size: Dp = 40.dp, tint: Color? = null, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) { Text(NameText.firstLetters(name)) }
}

// STUB: Design/Components.swift
@Composable
fun ErrorState(message: String, retry: (suspend () -> Unit)? = null, modifier: Modifier = Modifier) {
    Text(message, modifier)
}

// STUB: Design/ProviderModal.swift
sealed class ModalAccent {
    data object butter : ModalAccent()
    data object aqua : ModalAccent()
    data object pink : ModalAccent()
    data object lavender : ModalAccent()
    data object mint : ModalAccent()
    data object coral : ModalAccent()
    data object mango : ModalAccent()
    data object lime : ModalAccent()
    data object sky : ModalAccent()
    data object grey : ModalAccent()
    data object peach : ModalAccent()
    data class hue(val hue: Hue) : ModalAccent()
    data object blue : ModalAccent()
}

// STUB: Design/ProviderModal.swift (steps, secondaryActions and footerSummary left out: the shell passes none)
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
    onBack: (() -> Unit)? = null,
    primaryIcon: String? = null,
    primaryDisabled: Boolean = false,
    showsCancel: Boolean = true,
    pebbleImageURL: String? = null,
    showsBody: Boolean = true,
    onPrimary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title)
        content()
        if (primaryTitle != null && onPrimary != null) Text(primaryTitle, Modifier.clickable(onClick = onPrimary))
    }
}

// STUB: Design/ProviderModal.swift
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
    Column(modifier.padding(16.dp)) { Text(title); Text(confirmTitle) }
}

// STUB: Design/Forms.swift
@Stable
class FormState {
    var errors: Map<String, String> by mutableStateOf(emptyMap())
    var scrollTarget: String? by mutableStateOf(null)

    fun error(key: String): String? = errors[key]
    fun clear(key: String) {}
    fun check(rules: List<Pair<String, String?>>): Boolean = rules.all { it.second == null }
    fun apply(error: Throwable, known: Map<String, String> = emptyMap()): Boolean = false
}

// STUB: Design/Forms.swift
@Composable
fun FormScroll(form: FormState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

// STUB: Design/Forms.swift
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
    Column(modifier) { Text(label); control() }
}

// STUB: Design/Forms.swift
fun Modifier.fieldChrome(invalid: Boolean = false, multiline: Boolean = false): Modifier =
    this.heightIn(min = if (multiline) 112.dp else 48.dp)
        .border(1.dp, if (invalid) Theme.coral else Color.Gray, RoundedCornerShape(16.dp))
        .padding(horizontal = 16.dp)

// STUB: Design/Forms.swift
object PMTextField {
    enum class Number { none, large, plain }
}

// STUB: Design/Forms.swift
@Composable
fun PMTextField(
    label: String,
    text: androidx.compose.runtime.MutableState<String>,
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
    Column(modifier) { Text(label); androidx.compose.foundation.text.BasicTextField(text.value, { text.value = it }) }
}

// STUB: Design/Components.swift
object SearchField {
    enum class Shape { capsule, field }
}

// STUB: Design/Components.swift
@Composable
fun SearchField(
    prompt: String,
    text: androidx.compose.runtime.MutableState<String>,
    height: Dp = 48.dp,
    shape: SearchField.Shape = SearchField.Shape.capsule,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.text.BasicTextField(text.value, { text.value = it }, modifier)
}

// STUB: Design/Components.swift
object NoticeCard {
    sealed class Tone {
        data object info : Tone()
        data object warning : Tone()
        data object problem : Tone()
        data object success : Tone()
        data object lime : Tone()
        data class custom(val color: Color) : Tone()
    }

    enum class Look { inline, banner, page }
}

// STUB: Design/Components.swift
@Composable
fun NoticeCard(
    tone: NoticeCard.Tone,
    title: String? = null,
    message: String? = null,
    icon: String? = "",
    look: NoticeCard.Look = NoticeCard.Look.inline,
    byline: String? = null,
    actionTitle: String? = null,
    actionVariant: ButtonVariant = ButtonVariant.outline,
    isWorking: Boolean = false,
    action: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        title?.let { Text(it) }
        message?.let { Text(it) }
        if (actionTitle != null && action != null) Text(actionTitle, Modifier.clickable(onClick = action))
    }
}

// STUB: Design/Toast.swift
object Toast {
    fun show(text: String) {}
    fun error(text: String) {}
    fun error(error: Throwable, fallback: String = L("Something went wrong. Try again.")) {}
}

// STUB: Design/Toast.swift (`.toastHost()`: the toast drawn over everything, placed once at the root)
@Composable
fun ToastHost() {}

// STUB: design/Menus.kt (SwiftUI `Menu { } label: { }`)
class AppMenuScope {
    fun item(title: String, icon: String? = null, destructive: Boolean = false, checked: Boolean = false, onClick: () -> Unit) {}
    fun section(title: String? = null, content: AppMenuScope.() -> Unit) {}
    fun <T> picker(title: String, icon: String? = null, options: List<Pair<T, String>>, selection: T, onSelect: (T) -> Unit) {}
}

// STUB: design/Menus.kt (SwiftUI `Menu { } label: { }`)
@Composable
fun AppMenu(modifier: Modifier = Modifier, content: AppMenuScope.() -> Unit, label: @Composable () -> Unit) {
    Box(modifier) { label() }
}
