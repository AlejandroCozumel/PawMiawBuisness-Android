package com.pawmiaw.business

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.roundToInt

//
//  SignatureCapture.kt (iOS SignatureCapture.swift)
//
//  One way to capture and keep a signature everywhere (web components/app/
//  esign/signature-capture.tsx, signature-mark.tsx, my-signature-card.tsx,
//  esign-terms.tsx; lib/esign.ts; backend docs/e-signatures.md): draw it on
//  the shared `SignaturePad`, type it (shown in Dancing Script,
//  `signatureScript`, the font the server stamps), use the saved one
//  ("Mi firma"), or upload a scan the server trims and clears. The capture
//  only shows the ways the place allows.
//
//  Used by Home's member welcome (the signature adopted once, with the
//  e-sign terms accepted one time), Clients' closing sheets (the saved
//  signature or one drawn there) and Team's "Mi firma" card (Your account
//  and a staff member's page). Uploads go through `API.multipart`.
//
//  `SystemSegmentedPicker` at the end stands in for SwiftUI's `Picker(…).pickerStyle(.segmented)`
//  (the system segmented control these sheets use), here and in ConsentsCard2.kt.
//

// MARK: - What the server keeps

/**
 * A saved signature (`GET /users/me/signatures`): an image, or typed words.
 * A mark the server sent inside something else (a signed consent, a closing) is built with
 * the Swift `init(kind:text:imageDataUrl:createdAt:)`'s names: `SignatureMark(kind = …, text = …)`.
 */
@Serializable
data class SignatureMark(
    val id: String? = null,
    val purpose: String? = null,
    /** "drawn", "typed", "uploaded" or "none". */
    val kind: String? = null,
    val text: String? = null,
    val font: String? = null,
    val imageDataUrl: String? = null,
    val createdAt: String? = null,
) {
    /** A mark worth drawing: an image, or typed words. */
    val isVisible: Boolean
        get() {
            if (kind == "typed") return text.nonEmpty != null
            if (kind == "none") return false
            return imageDataUrl.nonEmpty != null || (kind == null && text.nonEmpty != null)
        }
}

/** The account's two saved signatures: a family's own, and a professional's. */
@Serializable
data class MySignatures(val personal: SignatureMark? = null, val professional: SignatureMark? = null)

/** The consent to sign electronically (`GET /esign/terms`): its versioned text. */
@Serializable
data class EsignTerms(
    val version: String,
    val language: String? = null,
    val title: String? = null,
    val text: String? = null,
    /** "Must be reviewed by a professional", when the server says so. */
    val reviewNote: String? = null,
)

// MARK: - The rules (web lib/esign.ts)

object SignatureRules {
    const val typedMin = 2
    const val typedMax = 80
    const val uploadMaxBytes = 5 * 1024 * 1024
    /** Dark ink on white "paper", in light and dark mode alike. */
    val ink = Color(27, 36, 51)

    /** A typed signature as the server keeps it (spaces collapsed), or null when too short or too long. */
    fun typed(value: String): String? {
        val text = collapseSpaces(value)
        return if (characterCount(text) in typedMin..typedMax) text else null
    }

    /** An image from a `data:` URL the server sends. */
    fun image(dataURL: String?): ImageBitmap? {
        if (dataURL == null || !dataURL.startsWith("data:")) return null
        val comma = dataURL.indexOf(',')
        if (comma < 0) return null
        // `.ignoreUnknownCharacters`: the MIME decoder skips what isn't base64.
        val data = runCatching { java.util.Base64.getMimeDecoder().decode(dataURL.substring(comma + 1)) }.getOrNull() ?: return null
        return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()
    }

    /** The pad's strokes as the PNG the server keeps: dark ink on a clear background, like the web's pad (2×). */
    fun png(strokes: List<List<Offset>>): ByteArray? {
        if (inkPoints(strokes) < 2) return null
        val size = SignaturePad.size
        val scale = 2f
        val bitmap = Bitmap.createBitmap((size.width * scale).roundToInt(), (size.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(scale, scale)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = android.graphics.Color.rgb(27, 36, 51)
        }
        canvas.drawPath(SignaturePad.androidPath(strokes), paint)
        return SignaturePad.png(bitmap)
    }

    fun inkPoints(strokes: List<List<Offset>>): Int = strokes.sumOf { it.size }

    fun dataURL(png: ByteArray): String = "data:image/png;base64,${android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)}"

    /** The server's refusals about a signature, in the reader's words. */
    fun message(error: Throwable, fallback: String): String = when ((error as? APIError)?.code) {
        "typed_signature_required" -> L("Type the signature (2 to 80 characters).")
        "signature_missing" -> L("Draw the signature.")
        "signature_invalid" -> L("Use a PNG, JPEG or WebP image.")
        "signature_too_large" -> L("The image is larger than 5 MB.")
        "signature_blank" -> L("The image has no signature on it. Choose another one.")
        else -> errorText(error, fallback)
    }

    /** Words split on any white space and joined by one space (Swift `split(whereSeparator: \.isWhitespace)`). */
    fun collapseSpaces(value: String): String {
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (ch in value) {
            if (ch.isWhitespace()) {
                if (word.isNotEmpty()) { words.add(word.toString()); word.clear() }
            } else word.append(ch)
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words.joinToString(" ")
    }

    /** Swift `String.count`: characters as people see them (grapheme clusters). */
    fun characterCount(value: String): Int {
        val it = BreakIterator.getCharacterInstance()
        it.setText(value)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    /** Swift `String(value.prefix(n))`, by characters. */
    fun characterPrefix(value: String, n: Int): String {
        val it = BreakIterator.getCharacterInstance()
        it.setText(value)
        var end = 0
        var count = 0
        while (count < n) {
            val next = it.next()
            if (next == BreakIterator.DONE) return value
            end = next
            count++
        }
        return value.substring(0, end)
    }
}

// MARK: - Capturing one

/** The ways a place lets someone sign. */
enum class SignatureCaptureMode(val rawValue: String) {
    draw("draw"), type("type"), saved("saved"), upload("upload");

    val title: String
        get() = when (this) {
            draw -> L("Draw@signature")
            type -> L("Type@signature")
            saved -> L("Use my signature")
            upload -> L("Upload a scan")
        }

    companion object {
        /** A professional's saved signature: drawn, typed or a scan. */
        val professional: List<SignatureCaptureMode> = listOf(draw, type, upload)
        /** Signing in place (closing a file): the saved signature, or one drawn here. */
        val savedOrDrawn: List<SignatureCaptureMode> = listOf(saved, draw)
    }
}

/** What a capture hands back. */
sealed class CapturedSignature {
    data object saved : CapturedSignature()

    /** A PNG. */
    class drawn(val png: ByteArray) : CapturedSignature() {
        override fun equals(other: Any?): Boolean = other is drawn && other.png.contentEquals(png)
        override fun hashCode(): Int = png.contentHashCode()
    }

    data class typed(val text: String) : CapturedSignature()

    /** A JPEG of the scan. */
    class uploaded(val jpeg: ByteArray) : CapturedSignature() {
        override fun equals(other: Any?): Boolean = other is uploaded && other.jpeg.contentEquals(jpeg)
        override fun hashCode(): Int = jpeg.contentHashCode()
    }

    /** As a JSON body's `signature` (closing a file): the saved one, or the drawing as a data URL. */
    val json: Map<String, Any?>
        get() = when (this) {
            saved -> mapOf("kind" to "saved")
            is drawn -> mapOf("kind" to "drawn", "image" to SignatureRules.dataURL(png))
            is typed -> mapOf("kind" to "typed", "text" to text)
            is uploaded -> mapOf("kind" to "uploaded", "image" to "data:image/jpeg;base64,${android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)}")
        }
}

/**
 * What is being captured: the way chosen and what each way holds. The screen keeps it, so its
 * save reads it; a new one starts clean (the pad starts clean every time a sheet opens).
 * Held in a `MutableState` (the Swift `@Binding`), so it changes by `copy`.
 */
data class SignatureDraft(
    val mode: SignatureCaptureMode = SignatureCaptureMode.draw,
    val strokes: List<List<Offset>> = emptyList(),
    val typed: String = "",
    val scan: ByteArray? = null,
) {
    val isDirty: Boolean get() = strokes.isNotEmpty() || typed.nonEmpty != null || scan != null

    /** The sentence to show while the chosen way has nothing usable; null once it is ready. */
    fun problem(saved: SignatureMark? = null): String? = when (mode) {
        SignatureCaptureMode.saved -> if (saved?.isVisible == true) null else L("There is no saved signature. Draw or type one.")
        SignatureCaptureMode.type -> if (SignatureRules.typed(typed) == null) L("Type the signature (2 to 80 characters).") else null
        SignatureCaptureMode.upload -> {
            val scan = scan
            if (scan == null) L("Choose the scan of your signature.")
            else if (scan.size > SignatureRules.uploadMaxBytes) L("The image is larger than 5 MB.") else null
        }
        SignatureCaptureMode.draw -> if (SignatureRules.inkPoints(strokes) >= 2) null else L("Draw the signature.")
    }

    /** The signature as it stands, or null (`problem` says why). */
    fun captured(saved: SignatureMark? = null): CapturedSignature? {
        if (problem(saved) != null) return null
        return when (mode) {
            SignatureCaptureMode.saved -> CapturedSignature.saved
            SignatureCaptureMode.type -> SignatureRules.typed(typed)?.let { CapturedSignature.typed(it) }
            SignatureCaptureMode.upload -> scan?.let { CapturedSignature.uploaded(it) }
            SignatureCaptureMode.draw -> SignatureRules.png(strokes)?.let { CapturedSignature.drawn(it) }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is SignatureDraft) return false
        val sameScan = if (scan == null) other.scan == null else other.scan != null && scan.contentEquals(other.scan)
        return mode == other.mode && strokes == other.strokes && typed == other.typed && sameScan
    }

    override fun hashCode(): Int = ((mode.hashCode() * 31 + strokes.hashCode()) * 31 + typed.hashCode()) * 31 + (scan?.contentHashCode() ?: 0)
}

/** Draw, type, use the saved one or upload a scan, as one field (its sentence under it when invalid). */
@Composable
fun SignatureCapture(
    modes: List<SignatureCaptureMode>,
    draft: MutableState<SignatureDraft>,
    /** The saved signature shown under "Use my signature". */
    saved: SignatureMark? = null,
    /** Typed text to start from (the signer's name) when Type opens empty. */
    suggestedText: String? = null,
    /** What using the saved signature means here. */
    savedHint: String? = null,
    /** The field's label (null where the block around it already says it). */
    label: String? = L("Your signature"),
    key: String = "signature",
    form: FormState? = null,
    modifier: Modifier = Modifier,
) {
    var offeredSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val available = modes.filter { it != SignatureCaptureMode.saved || saved?.isVisible == true }
    val invalid = form?.error(key) != null

    fun refuse(sentence: String) {
        form?.let { it.errors = it.errors + (key to sentence) }
        Toast.error(sentence)
    }

    /** Any photo becomes a JPEG the server takes (a HEIC from the camera too). */
    fun pick(item: Uri?) {
        if (item == null) return
        scope.launch {
            val jpeg = ImageUpload.load(item)
            if (jpeg == null) { refuse(L("Use a PNG, JPEG or WebP image.")); return@launch }
            if (jpeg.size > SignatureRules.uploadMaxBytes) { refuse(L("The image is larger than 5 MB.")); return@launch }
            draft.value = draft.value.copy(scan = jpeg)
            form?.clear(key)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pick(uri) }

    fun choose(next: SignatureCaptureMode) {
        draft.value = draft.value.copy(mode = next)
        form?.clear(key)
        val name = suggestedText.nonEmpty
        if (next == SignatureCaptureMode.type && draft.value.typed.nonEmpty == null && name != null) draft.value = draft.value.copy(typed = name)
    }

    /** A saved signature that arrives late becomes the first choice once; one that goes away leaves its tab. */
    fun settle() {
        if (saved?.isVisible == true && SignatureCaptureMode.saved in modes && !offeredSaved) {
            offeredSaved = true
            draft.value = draft.value.copy(mode = SignatureCaptureMode.saved)
        }
        if (draft.value.mode !in available) draft.value = draft.value.copy(mode = available.firstOrNull() ?: SignatureCaptureMode.draw)
        val name = suggestedText.nonEmpty
        if (draft.value.mode == SignatureCaptureMode.type && draft.value.typed.nonEmpty == null && name != null) draft.value = draft.value.copy(typed = name)
    }

    // `.onAppear(perform: settle)` and `.onChange(of: saved?.isVisible)`.
    LaunchedEffect(saved?.isVisible) { settle() }

    val control: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (available.size > 1) {
                SystemSegmentedPicker(
                    label = L("How to sign"),
                    options = available.map { it to it.title },
                    selection = stateOf({ draft.value.mode }, { choose(it) }),
                )
            }
            when (draft.value.mode) {
                SignatureCaptureMode.draw -> {
                    SignaturePad(
                        strokes = stateOf({ draft.value.strokes }, { draft.value = draft.value.copy(strokes = it) }),
                        showsClear = true,
                    )
                    CaptureOnChange(draft.value.strokes.size) { form?.clear(key) }
                }
                SignatureCaptureMode.type -> SignatureTyping(draft, invalid, key, form)
                SignatureCaptureMode.saved -> {
                    SignatureMarkView(mark = saved, height = 144.dp, highlighted = true)
                    Text(savedHint ?: L("Your saved signature (Mi firma) is placed on this document."), style = Theme.text(12), color = Theme.inkSoft)
                }
                SignatureCaptureMode.upload -> SignatureUploading(draft.value.scan, invalid) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }

    if (label != null) {
        FormField(label = label, key = key, form = form, modifier = modifier) { control() }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            control()
            form?.error(key)?.let { Text(it, style = Theme.text(13, FontWeight.SemiBold), color = Theme.coralText) }
        }
    }
}

/** "Type@signature": the words, and how they look in the signature font on a signing line. */
@Composable
private fun SignatureTyping(draft: MutableState<SignatureDraft>, invalid: Boolean, key: String, form: FormState?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val typed = draft.value.typed
        BasicTextField(
            value = typed,
            onValueChange = { value ->
                val capped = if (SignatureRules.characterCount(value) > SignatureRules.typedMax) SignatureRules.characterPrefix(value, SignatureRules.typedMax) else value
                draft.value = draft.value.copy(typed = capped)
                form?.clear(key)
            },
            singleLine = true,
            textStyle = Theme.bodyFont.copy(color = Theme.inkStrong),
            cursorBrush = SolidColor(Theme.blue),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .fieldChrome(invalid = invalid)
                .semantics { contentDescription = L("Typed signature") },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    if (typed.isEmpty()) Text(L("Type your signature"), style = Theme.bodyFont, color = Theme.inkSoft)
                    inner()
                }
            },
        )
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .background(Color.White, shape)
                .captureDashedOutline(Theme.borderSoft, 2.dp, 18.dp, floatArrayOf(6f, 5f))
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            val text = typed.nonEmpty
            if (text != null) {
                TypedSignature(text = text, size = 44)
            } else {
                Text(L("Your signature shows here"), style = Theme.text(14, FontWeight.SemiBold), color = Color(138, 150, 168))
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                    .height(1.dp)
                    .background(Color(201, 210, 222))
            )
        }
    }
}

/** "Upload a scan": the photo chosen (or how to choose one) and what PawMiaw does with it. */
@Composable
private fun SignatureUploading(scan: ByteArray?, invalid: Boolean, choose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(18.dp)
        val image = remember(scan) { scan?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
        Box(
            Modifier
                .fillMaxWidth()
                .height(144.dp)
                .background(Color.White, shape)
                .captureDashedOutline(if (invalid) Theme.coral else Theme.borderSoft, 2.dp, 18.dp, floatArrayOf(6f, 5f))
                .capturePlainPress(choose),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(image, contentDescription = L("The scan you chose"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            } else {
                val grey = Color(107, 114, 128)
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Glyph("photo.badge.arrow.down", 22.dp, tint = grey)
                    Text(
                        L("Choose a photo or scan of your signature on white paper"),
                        style = Theme.text(14, FontWeight.SemiBold),
                        color = grey,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(L("PNG, JPEG or WebP up to 5 MB. PawMiaw trims it and makes the paper transparent."), style = Theme.text(12), color = Theme.inkSoft)
    }
}

// MARK: - Showing one

/** Typed words in the signature font, sized to the box (one line, down to 40% before it cuts). */
@Composable
fun TypedSignature(text: String, size: Number = 38, modifier: Modifier = Modifier) {
    CaptureFitText(
        text = text,
        style = signatureScript(size).copy(color = SignatureRules.ink),
        minimumScaleFactor = 0.4f,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}

/**
 * How a signature looks, whatever made it: a drawn or scanned image, or typed words in the
 * signature font. Always on white "paper" so dark ink reads in dark mode too.
 */
@Composable
fun SignatureMarkView(
    mark: SignatureMark?,
    /** Who signed, for the description. */
    name: String? = null,
    height: Dp = 112.dp,
    /** The chosen one (Use my signature): a blue edge. */
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val description = name.nonEmpty?.let { LF("Signature of %@", it) } ?: L("Signature")
    val image = remember(mark?.imageDataUrl) { SignatureRules.image(mark?.imageDataUrl) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Color.White, shape)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) Theme.blue else Theme.borderSoft, shape)
            .clearAndSetSemantics { contentDescription = description }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (mark != null && mark.isVisible && (mark.kind == "typed" || (mark.imageDataUrl.nonEmpty == null && mark.text.nonEmpty != null))) {
            TypedSignature(text = mark.text ?: "", size = height.value * 0.34f)
        } else if (mark != null && mark.isVisible && image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(L("No signature image."), style = Theme.text(12), color = Color(107, 114, 128))
        }
    }
}

// MARK: - The e-sign terms, accepted once

/**
 * The consent to sign electronically: its versioned text, the review note and "I agree to sign
 * electronically". The version shown is the one sent with the signature.
 */
@Composable
fun EsignTermsPanel(
    terms: EsignTerms?,
    failed: Boolean = false,
    /** The server said the terms changed since they were shown. */
    changed: Boolean = false,
    accepted: MutableState<Boolean>,
    error: String? = null,
    modifier: Modifier = Modifier,
    retry: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (changed) {
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("The terms for signing electronically were just updated. Read the new version and accept it to continue."))
        }
        if (terms != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(Theme.surfaceMuted, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                terms.title?.let { Text(it, style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong) }
                for (paragraph in (terms.text ?: "").split("\n\n")) {
                    Text(paragraph, style = Theme.text(13), color = Theme.inkStrong)
                }
                Text(LF("Version %@", terms.version), style = Theme.captionFont, color = Theme.inkSoft)
            }
            terms.reviewNote.nonEmpty?.let { note ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.mango.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("scalemass", 14.dp, tint = Theme.inkStrong)
                    Text(note, style = Theme.text(13, FontWeight.Bold), color = Theme.inkStrong)
                }
            }
            ToggleRow(title = L("I agree to sign electronically"), isOn = accepted)
            if (error != null) Text(error, style = Theme.text(13, FontWeight.SemiBold), color = Theme.coralText)
        } else if (failed) {
            Text(L("The terms for signing electronically could not be loaded."), style = Theme.smallFont, color = Theme.inkSoft)
            if (retry != null) PMButton(L("Try again"), systemImage = "arrow.clockwise", variant = ButtonVariant.outline, size = ButtonSize.compact, action = retry)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CaptureSpinner() }
        }
    }
}

// MARK: - The professional signature on the server

object ProfessionalSignature {
    private const val path = "/users/me/signatures/professional"

    /** The account's saved professional signature, if any. */
    suspend fun load(): SignatureMark? {
        val mine: MySignatures = API.get("/users/me/signatures")
        return mine.professional
    }

    suspend fun terms(language: String = LocalizationManager.currentLanguage.rawValue): EsignTerms =
        API.get("/esign/terms", mapOf("lang" to language, "purpose" to "professional"))

    /**
     * Saves it (`kind` with the `signature` file or the `text`); with `terms`, also adopts it under
     * them, so later signing is one tap (the terms accepted once).
     */
    suspend fun save(signature: CapturedSignature, terms: EsignTerms? = null, language: String = LocalizationManager.currentLanguage.rawValue): SignatureMark {
        var fields: List<Pair<String, String>>
        var files: List<API.UploadFile> = emptyList()
        when (signature) {
            is CapturedSignature.typed -> fields = listOf("kind" to "typed", "text" to signature.text)
            is CapturedSignature.drawn -> {
                fields = listOf("kind" to "drawn")
                files = listOf(API.UploadFile("signature", "signature.png", "image/png", signature.png))
            }
            is CapturedSignature.uploaded -> {
                fields = listOf("kind" to "uploaded")
                files = listOf(API.UploadFile("signature", "signature.jpg", "image/jpeg", signature.jpeg))
            }
            CapturedSignature.saved -> throw APIError(0, L("Draw the signature."), "signature_missing", emptyMap())
        }
        if (terms != null) {
            fields = fields + listOf("termsAccepted" to "true", "termsVersion" to terms.version, "language" to language)
        }
        return API.multipart(path, method = "PUT", fields = fields, files = files)
    }

    /** Adopts the signature already saved, accepting the terms at the version shown. */
    suspend fun adopt(terms: EsignTerms, language: String = LocalizationManager.currentLanguage.rawValue) {
        API.post<EmptyResponse>("$path/adopt", mapOf("termsAccepted" to true, "termsVersion" to terms.version, "language" to language))
    }

    suspend fun delete() {
        API.send("DELETE", path)
    }
}

// MARK: - "Mi firma"

/** `MySignatureCard.explanation`. */
object MySignatureCard {
    val explanation: String
        get() = L("Printed on the prescriptions, signed records, discharge summaries and certificates you sign. Changing it never changes documents already signed.")
}

/**
 * "Mi firma" for a team member: the professional signature they keep, "Replace" or "Add my
 * signature", and "Delete". Printed on the prescriptions, signed records, discharge summaries
 * and certificates they sign; replacing or deleting it never changes what is already signed.
 * Shown on Your account and on the person's own staff page.
 */
@Composable
fun MySignatureCard(
    /** Typed text to start from (the signer's name) when Type opens empty. */
    suggestedName: String? = null,
    modifier: Modifier = Modifier,
) {
    var saved by remember { mutableStateOf<SignatureMark?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            saved = ProfessionalSignature.load()
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (saved == null) failed = errorText(e, L("Your signature could not be loaded."))
        }
        loaded = true
    }

    LaunchedEffect(Unit) { load() }

    Card(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).background(Theme.lavender.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                Glyph("signature", 15.dp, tint = Theme.inkOnBright)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("My professional signature"), style = Theme.sectionFont, color = Theme.inkStrong)
                Text(MySignatureCard.explanation, style = Theme.smallFont, color = Theme.inkSoft)
            }
        }
        val mark = saved
        val problem = failed
        when {
            !loaded -> Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) { CaptureSpinner() }
            problem != null -> ErrorState(message = problem) { load() }
            mark != null && mark.isVisible -> {
                SignatureMarkView(mark = mark, name = suggestedName)
                mark.createdAt?.let { Text(LF("Saved %@", DateText.date(it)), style = Theme.text(12), color = Theme.inkSoft) }
            }
            else -> Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .captureDashedOutline(Theme.borderSoft, 1.5.dp, 16.dp, floatArrayOf(5f, 4f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(L("No signature yet. Draw it, type it or upload a scan."), style = Theme.smallFont, color = Theme.inkSoft)
            }
        }
        FlowLayout(spacing = 8.dp) {
            Box(Modifier.captureDisabled(!loaded)) {
                PMButton(if (saved?.isVisible == true) L("Replace") else L("Add my signature"), systemImage = "pencil.line", size = ButtonSize.compact) { editing = true }
            }
            if (saved?.isVisible == true) {
                PMButton(L("Delete"), systemImage = "trash", variant = ButtonVariant.outline, size = ButtonSize.compact) { deleting = true }
            }
        }
    }

    if (editing) {
        AppSheet(onDismissRequest = { editing = false }) {
            MySignatureSheet(replacing = saved?.isVisible == true, suggestedName = suggestedName) { saved = it }
        }
    }
    if (deleting) {
        AppSheet(onDismissRequest = { deleting = false }) {
            DeleteSignatureSheet { saved = null }
        }
    }
}

/** Drawing, typing or uploading the signature to keep. The pad starts clean every time it opens. */
@Composable
private fun MySignatureSheet(replacing: Boolean, suggestedName: String?, onSaved: (SignatureMark) -> Unit) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val draft = remember { mutableStateOf(SignatureDraft()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (!form.check(listOf("signature" to draft.value.problem()))) return
        val signature = draft.value.captured() ?: return
        saving = true
        scope.launch {
            try {
                val saved = ProfessionalSignature.save(signature)
                Toast.show(L("Your signature is saved."))
                onSaved(saved)
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.check(listOf("signature" to SignatureRules.message(e, L("Could not save the signature. Try again."))))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("My professional signature"),
        subtitle = if (replacing) L("The new one is used from now on. Signed documents keep theirs.") else null,
        systemImage = "signature",
        accent = ModalAccent.lavender,
        isDirty = draft.value.isDirty,
        form = form,
        primaryTitle = L("Save signature"),
        isWorking = saving,
        onPrimary = { save() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        SignatureCapture(modes = SignatureCaptureMode.professional, draft = draft, suggestedText = suggestedName, form = form)
        Text(MySignatureCard.explanation, style = Theme.text(12), color = Theme.inkSoft)
    }
}

/** "Delete your saved signature?", asked the same way wherever it is deleted. */
@Composable
private fun DeleteSignatureSheet(onDeleted: () -> Unit) {
    val dismiss = LocalDismiss.current
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ProviderModal(
        title = L("Delete your saved signature?"),
        systemImage = "trash.fill",
        accent = ModalAccent.coral,
        primaryTitle = L("Delete signature"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        cancelTitle = L("Keep it"),
        onPrimary = {
            working = true
            scope.launch {
                try {
                    ProfessionalSignature.delete()
                    onDeleted()
                    Toast.show(L("Signature deleted."))
                    announceDataChanged()
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.error(errorText(e, L("Could not delete it. Try again.")))
                } finally {
                    working = false
                }
            }
        },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Text(L("New documents won’t carry it until you add one again. Documents already signed keep theirs."), style = Theme.bodyFont, color = Theme.inkStrong)
    }
}

// MARK: - The system segmented control

/**
 * SwiftUI's `Picker(label, selection:) { … }.pickerStyle(.segmented)` (UISegmentedControl): a
 * grey 32 pt track the width of its place, the chosen option on a raised white thumb that
 * slides, 13 pt words (semibold when chosen), hairlines between the options not chosen.
 * Options are `(value, title)` in order.
 */
@Composable
fun <V> SystemSegmentedPicker(label: String, options: List<Pair<V, String>>, selection: MutableState<V>, modifier: Modifier = Modifier) {
    val dark = LocalIsDark.current
    val track = Color(118, 118, 128).copy(alpha = if (dark) 0.24f else 0.12f)
    val thumb = if (dark) Color(99, 99, 102) else Color.White
    val ink = if (dark) Color.White else Color.Black
    val index = options.indexOfFirst { it.first == selection.value }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(track, RoundedCornerShape(9.dp))
            .semantics { contentDescription = label },
    ) {
        val count = options.size.coerceAtLeast(1)
        val segment = maxWidth / count
        val x by animateDpAsState(segment * index.coerceAtLeast(0), tween(220), label = "segment")
        if (index >= 0) {
            Box(
                Modifier
                    .offset(x = x)
                    .width(segment)
                    .fillMaxHeight()
                    .padding(2.dp)
                    .shadow(2.dp, RoundedCornerShape(7.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                    .background(thumb, RoundedCornerShape(7.dp))
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, option ->
                val chosen = i == index
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .drawBehind {
                            // The hairline before an option, unless it or the one before is chosen.
                            if (i > 0 && !chosen && i - 1 != index) {
                                val inset = size.height * 0.25f
                                drawLine(Color(142, 142, 147).copy(alpha = 0.3f), Offset(0f, inset), Offset(0f, size.height - inset), strokeWidth = 1.dp.toPx())
                            }
                        }
                        .selectable(selected = chosen, role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            selection.value = option.first
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.second,
                        style = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

// MARK: - Pieces of this file

/** SwiftUI's `ProgressView()`: a small grey spinner. */
@Composable
private fun CaptureSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.inkSoft)
}

/** `.onChange(of: value)`: runs on a change, not on the first composition. */
@Composable
private fun <T> CaptureOnChange(value: T, action: () -> Unit) {
    val last = remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (last.value != value) {
            last.value = value
            action()
        }
    }
}

/** A dashed `RoundedRectangle().stroke(…, style: StrokeStyle(lineWidth:, dash:))`, centred on the edge. */
private fun Modifier.captureDashedOutline(color: Color, width: Dp, radius: Dp, dash: FloatArray): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(FloatArray(dash.size) { dash[it] * density }, 0f)),
    )
}

/** `.buttonStyle(.plain)`: the whole area taps, dimmed while pressed. */
@Composable
private fun Modifier.capturePlainPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .alpha(if (pressed) 0.7f else 1f)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}

/** `.disabled(true)` on a button: half its strength, and it takes no touch. */
private fun Modifier.captureDisabled(disabled: Boolean): Modifier =
    if (!disabled) this else this
        .alpha(0.5f)
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } }
        }

/** Text on one line that shrinks to fit its width, down to `minimumScaleFactor`, then cuts (SwiftUI `.minimumScaleFactor`). */
@Composable
private fun CaptureFitText(text: String, style: TextStyle, minimumScaleFactor: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxPx = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val scale = remember(text, style, maxPx, bounded) {
            if (!bounded) 1f else {
                val width = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
                if (width > maxPx && width > 0) max(minimumScaleFactor, maxPx.toFloat() / width) else 1f
            }
        }
        Text(text, style = style.copy(fontSize = style.fontSize * scale), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
