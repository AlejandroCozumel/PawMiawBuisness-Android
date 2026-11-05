package com.pawmiaw.business

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

//
//  Stand-ins for what other ports own, used by the cards (ConsentsCard, SignatureCapture,
//  SignaturePad, AuditHistoryCard, IncidentsCard, WalkRouteView). Signatures only: the merge
//  deletes this file and the real ports take their place. Parameters these files don't pass
//  are left out where their Kotlin type isn't settled (said on each).
//

// MARK: - Buttons.swift

// STUB: Buttons.swift
enum class ButtonVariant { primary, outline, destructive, ghost, link }

// STUB: Buttons.swift
enum class ButtonSize { regular, compact, header, modal, slim, small }

// STUB: Buttons.swift
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
) {}

// STUB: Buttons.swift
@Composable
fun IconCircleButton(
    systemImage: String,
    label: String,
    tint: Color = Theme.inkSoft,
    size: Dp = 36.dp,
    bordered: Boolean = true,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {}

// MARK: - Forms.swift

// STUB: Forms.swift
class FormState {
    var errors: Map<String, String> by mutableStateOf(emptyMap())
    var scrollTarget: String? by mutableStateOf(null)
    fun error(key: String): String? = errors[key]
    fun clear(key: String) { if (errors[key] != null) errors = errors - key }
    fun check(rules: List<Pair<String, String?>>): Boolean = rules.all { it.second == null }
    fun apply(error: Throwable, known: Map<String, String> = emptyMap()): Boolean = false
}

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
) { control() }

// STUB: Forms.swift (`fieldChrome(invalid:multiline:)`, the FieldChrome view modifier)
@Composable
fun Modifier.fieldChrome(invalid: Boolean = false, multiline: Boolean = false): Modifier = this

// STUB: Forms.swift (`number`, `suffix`, `lines` left out: not passed here)
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
) {}

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
) {}

// STUB: Forms.swift
@Composable
fun ToggleRow(title: String, subtitle: String? = null, isOn: MutableState<Boolean>, modifier: Modifier = Modifier) {}

// STUB: Forms.swift (`init(options:selection:)`; the Swift tuple `(value:, title:, subtitle:)` as a Triple)
@Composable
fun <Value> ChoiceCards(options: List<Triple<Value, String, String?>>, selection: MutableState<Value>, modifier: Modifier = Modifier) {}

// MARK: - DateFields.swift

// STUB: DateFields.swift (`components` left out: not passed here)
@Composable
fun DateTimeField(
    label: String,
    date: MutableState<Instant>,
    zone: ZoneId? = null,
    min: Instant? = null,
    max: Instant? = null,
    key: String? = null,
    form: FormState? = null,
    hint: String? = null,
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {}

// MARK: - FlowLayout.swift

// STUB: FlowLayout.swift
@Composable
fun FlowLayout(spacing: Dp = 8.dp, lineSpacing: Dp? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) { content() }

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
    modifier: Modifier = Modifier,
    prepare: (suspend () -> Unit)? = null,
) {}

// MARK: - ProviderModal.swift

// STUB: ProviderModal.swift
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

// STUB: ProviderModal.swift (`steps`, `secondaryActions`, `footerSummary` left out: not passed here)
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
    modifier: Modifier = Modifier,
    onPrimary: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) { content() }

// STUB: ProviderModal.swift
@Composable
fun ModalSection(
    title: String,
    subtitle: String? = null,
    systemImage: String? = null,
    tint: Color = Theme.aqua,
    required: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) { content() }

// STUB: ProviderModal.swift
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
) {}

// MARK: - Components.swift

// STUB: Components.swift
@Composable
fun Card(
    padding: Dp = 16.dp,
    tint: Color? = null,
    spacing: Dp = 12.dp,
    flat: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) { content() }

// STUB: Components.swift
@Composable
fun FactRow(label: String, value: String?, modifier: Modifier = Modifier) {}

// STUB: Components.swift (`init(text:state:outlined:small:)`)
@Composable
fun StatusChip(text: String, state: ProviderState, outlined: Boolean = false, small: Boolean = false, modifier: Modifier = Modifier) {}

// STUB: Components.swift
@Composable
fun HueChip(text: String, hue: Hue = Hue.grey, systemImage: String? = null, modifier: Modifier = Modifier) {}

// STUB: Components.swift (only `text` and `weight` are passed here; `tone` and the rest left out)
@Composable
fun Chip(text: String, weight: FontWeight = FontWeight.Bold, modifier: Modifier = Modifier) {}

// STUB: Components.swift (`TagChip.Size`)
object TagChip {
    enum class Size { xs, sm, header }
}

// STUB: Components.swift
@Composable
fun TagChip(
    text: String,
    icon: String? = null,
    linked: Boolean = false,
    tone: Color? = null,
    size: TagChip.Size = TagChip.Size.xs,
    modifier: Modifier = Modifier,
) {}

// STUB: Components.swift (`init(title:count:isSelected:tone:dot:icon:solid:action:)`)
@Composable
fun FilterChip(
    title: String,
    count: Int? = null,
    isSelected: Boolean,
    tone: Color = Theme.blue,
    dot: Color? = null,
    icon: String? = null,
    solid: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {}

// STUB: Components.swift
@Composable
fun LoadingState(text: String? = null, modifier: Modifier = Modifier) {}

// STUB: Components.swift
@Composable
fun EmptyState(
    systemImage: String = "tray",
    title: String,
    message: String? = null,
    actionTitle: String? = null,
    action: (() -> Unit)? = null,
    accent: Color = Theme.aqua,
    panel: Boolean = false,
    modifier: Modifier = Modifier,
) {}

// STUB: Components.swift
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, retry: (suspend () -> Unit)? = null) {}

// STUB: Components.swift (`NoticeCard.Tone`, `NoticeCard.Look`)
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

// STUB: Components.swift (`init(tone:title:message:icon:look:byline:actionTitle:actionVariant:isWorking:action:)`)
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
    modifier: Modifier = Modifier,
    action: (() -> Unit)? = null,
) {}

// STUB: Components.swift
@Composable
fun RowDivider(color: Color = Theme.borderSoft, modifier: Modifier = Modifier) {}

// STUB: Components.swift (`.reloadsOnScope { … }`: the work on appear, on a new scope and on `announceDataChanged()`)
@Composable
fun ReloadsOnScope(action: suspend () -> Unit) {}

// MARK: - Lists.swift

// STUB: Lists.swift
enum class ListCardStyle { card, panel, box }

// STUB: Lists.swift (`.listCard(_:)`)
@Composable
fun Modifier.listCard(style: ListCardStyle = ListCardStyle.card): Modifier = this

// STUB: Lists.swift
@Composable
fun CardToolbar(spacing: Dp = 12.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) { content() }

// MARK: - Core/ImageUpload.swift

// STUB: ImageUpload.swift (a picked photo as upload-ready JPEG; the multipart upload)
object ImageUpload {
    const val maxBytes = 3 * 1024 * 1024

    suspend fun load(item: Uri?): ByteArray? = null

    suspend inline fun <reified T> upload(
        data: ByteArray,
        path: String,
        field: String = "photo",
        method: String = "POST",
        fields: Map<String, String> = emptyMap(),
    ): T = TODO("STUB")
}
