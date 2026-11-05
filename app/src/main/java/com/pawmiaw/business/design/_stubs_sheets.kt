package com.pawmiaw.business

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  Stand-ins for what other ports own, used by the sheets (ProviderModal, ModuleHeader,
//  ClientPetPicker, DepositSheet, PhotoField, Components). Signatures only: the merge deletes
//  this file and the real ports take their place. A SwiftUI `.disabled(x)` on a control is the
//  extra `enabled` parameter here (said on each).
//

// MARK: - Buttons.swift

// STUB: Buttons.swift
enum class ButtonVariant { primary, outline, destructive, ghost, link }

// STUB: Buttons.swift
enum class ButtonSize { regular, compact, header, modal, slim, small }

// STUB: Buttons.swift (`enabled` = SwiftUI `.disabled(!enabled)` on the button)
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
    enabled: Boolean = true,
    action: () -> Unit,
) {
    Text(title, modifier.clickable(enabled = enabled && !isWorking, onClick = action))
}

// STUB: Buttons.swift (SwiftUI `@Environment(\.isEnabled)`)
val LocalIsEnabled = compositionLocalOf { true }

// STUB: Buttons.swift (`Button { } label: { }.buttonStyle(.plain)`; `label` = `.accessibilityLabel`)
fun Modifier.plainButton(
    enabled: Boolean = true,
    label: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = this.clickable(enabled = enabled, role = role, onClick = onClick)

// STUB: Buttons.swift (`.lineLimit(1).minimumScaleFactor(x)`)
@Composable
fun FittedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    minimumScaleFactor: Float = 1f,
    textAlign: TextAlign? = null,
) {
    Text(text, modifier, color = color, style = style, maxLines = 1, textAlign = textAlign)
}

// MARK: - Forms.swift

// STUB: Forms.swift
class FormState {
    var errors: Map<String, String> by mutableStateOf(emptyMap())
    var scrollTarget: String? by mutableStateOf(null)
    var scrollRequest: Int by mutableIntStateOf(0)
        private set
    fun error(key: String): String? = errors[key]
    fun clear(key: String) { if (errors[key] != null) errors = errors - key }
    fun check(rules: List<Pair<String, String?>>): Boolean = rules.all { it.second == null }
    fun apply(error: Throwable, known: Map<String, String> = emptyMap()): Boolean = false
}

// STUB: Forms.swift
@Composable
fun FormScroll(form: FormState, content: @Composable () -> Unit) { content() }

// STUB: Forms.swift (iOS `.id(key)` inside a `FormScroll`: the place a failed form scrolls to)
fun Modifier.formScrollTarget(id: String, form: FormState?): Modifier = this

// STUB: Forms.swift
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
) { Column(modifier) { Text(label); control() } }

// STUB: Forms.swift (`fieldChrome(invalid:multiline:)`, the FieldChrome view modifier)
fun Modifier.fieldChrome(invalid: Boolean = false, multiline: Boolean = false): Modifier = this

// STUB: Forms.swift (`number`, `suffix`, `lines` left out: their Kotlin types are the Forms port's)
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
    modifier: Modifier = Modifier,
) { Text(label, modifier) }

// STUB: Forms.swift
@Composable
fun MoneyField(
    label: String,
    cents: MutableState<Int?>,
    key: String? = null,
    form: FormState? = null,
    hint: String? = null,
    optional: Boolean = false,
    required: Boolean = false,
    caps: Boolean = false,
    modifier: Modifier = Modifier,
) { Text(label, modifier) }

// MARK: - FlowLayout.swift

// STUB: FlowLayout.swift
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowLayout(spacing: Dp = 8.dp, lineSpacing: Dp? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(lineSpacing ?: spacing)) { content() }
}

// MARK: - Toast.swift

// STUB: Toast.swift
object Toast {
    fun show(text: String) {}
    fun error(text: String) {}
    fun error(error: Throwable, fallback: String = L("Something went wrong. Try again.")) {}
}

// MARK: - DocumentButton.swift

// STUB: DocumentButton.swift
@Composable
fun DocumentButton(
    title: String,
    systemImage: String = "doc.richtext",
    path: String,
    query: Map<String, String?> = emptyMap(),
    fileName: String,
    ext: String = "pdf",
    variant: ButtonVariant = ButtonVariant.outline,
    size: ButtonSize = ButtonSize.compact,
    fullWidth: Boolean = false,
    prepare: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier,
) { Text(title, modifier) }

// MARK: - Core/ImageUpload.swift

// STUB: Core/ImageUpload.swift (a picked photo as upload-ready JPEG)
object ImageUpload {
    const val maxBytes = 3 * 1024 * 1024

    suspend fun load(item: Uri?): ByteArray? = null
}

// STUB: Core/ImageUpload.swift (iOS `UIImage` preview → `ImageBitmap`; a value type, changed with `copy`)
data class PhotoDraft(
    val data: ByteArray? = null,
    val preview: ImageBitmap? = null,
    /** The saved photo is to be removed on save. */
    val removed: Boolean = false,
) {
    val isEmpty: Boolean get() = data == null && !removed
}
