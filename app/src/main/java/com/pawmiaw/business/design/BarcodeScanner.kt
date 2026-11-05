package com.pawmiaw.business

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.min
import com.google.mlkit.vision.barcode.common.Barcode as MLBarcode

//
//  The camera scanner (web components/app/barcode-scanner.tsx; iOS BarcodeScanner.swift), the
//  one way the app reads a barcode: the scan mark beside a search box or a code field opens it,
//  it reads the code by itself and hands it over — the same code a hardware scanner would have
//  typed. The camera through CameraX with ML Kit's barcode reader (iOS: VisionKit's live
//  scanner or AVFoundation); a photo of the code (ML Kit) or the code typed by hand when the
//  camera can't. A read is only taken when it can be trusted (`BarcodeStableReader`), and a GS1
//  label also gives its lot and expiry (the rules are in core/Barcode.kt). The camera
//  permission is asked when the scanner opens. Used by the Store's counter, the Catalog's
//  product sheet and quick edit, the bill's item search, Inventory's search, stock settings and
//  the delivery's product picker.
//

/** The symbologies asked of the camera, by the web's format names. */
object BarcodeSymbology {
    /** ML Kit's formats (iOS: EAN-13/8, UPC-E, Code 128, Code 39, ITF, QR, Data Matrix; UPC-A, which iOS reads as EAN-13). */
    val formats: IntArray = intArrayOf(
        MLBarcode.FORMAT_EAN_13, MLBarcode.FORMAT_EAN_8, MLBarcode.FORMAT_UPC_E, MLBarcode.FORMAT_UPC_A,
        MLBarcode.FORMAT_CODE_128, MLBarcode.FORMAT_CODE_39, MLBarcode.FORMAT_ITF,
        MLBarcode.FORMAT_QR_CODE, MLBarcode.FORMAT_DATA_MATRIX,
    )

    fun options(): BarcodeScannerOptions =
        BarcodeScannerOptions.Builder().setBarcodeFormats(formats[0], *formats.drop(1).toIntArray()).build()

    fun format(symbology: Int): String? = when (symbology) {
        MLBarcode.FORMAT_EAN_13 -> "ean_13"
        MLBarcode.FORMAT_EAN_8 -> "ean_8"
        MLBarcode.FORMAT_UPC_E -> "upc_e"
        MLBarcode.FORMAT_UPC_A -> "upc_a"
        MLBarcode.FORMAT_CODE_128 -> "code_128"
        MLBarcode.FORMAT_CODE_39 -> "code_39"
        MLBarcode.FORMAT_ITF -> "itf"
        MLBarcode.FORMAT_QR_CODE -> "qr_code"
        MLBarcode.FORMAT_DATA_MATRIX -> "data_matrix"
        else -> null
    }

    /**
     * A read as the iOS camera hands it over: a UPC-A comes as the EAN-13 it is (a leading 0),
     * so a scanned product gets the same code on both apps.
     */
    fun normalized(raw: String, symbology: Int): Pair<String, String?> {
        if (symbology == MLBarcode.FORMAT_UPC_A && raw.length == 12 && raw.all { it in '0'..'9' }) return ("0$raw") to "ean_13"
        return raw to format(symbology)
    }

    /** The first code in a photo, or null when none is found. Throws when the photo can't be opened. */
    suspend fun read(photo: Uri): Pair<String, String?>? {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(AppContext.context, photo) }
        val client = BarcodeScanning.getClient(options())
        return try {
            val found = try {
                client.process(image).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            val first = found.firstOrNull { !it.rawValue.isNullOrEmpty() && it.rawValue!!.trimSpaces().isNotEmpty() } ?: return null
            normalized(first.rawValue!!, first.format)
        } finally {
            client.close()
        }
    }
}

private fun Char.isSwiftSpace(newlines: Boolean): Boolean {
    if (this == '\t') return true
    if (newlines && (this in '\n'..'\r' || this == '\u0085')) return true
    val type = Character.getType(this).toByte()
    return type == Character.SPACE_SEPARATOR ||
        (newlines && (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR))
}

/** Swift `.trimmingCharacters(in: .whitespacesAndNewlines)` (keeps the GS1 separator). */
private fun String.trimWS(): String = trim { it.isSwiftSpace(newlines = true) }

/** Swift `.trimmingCharacters(in: .whitespaces)`. */
private fun String.trimSpaces(): String = trim { it.isSwiftSpace(newlines = false) }

/**
 * The scan mark of a search box or a code field, as a round button: it opens the camera and
 * hands over what it read (a GS1 label's GTIN, otherwise the code, with the rest of the label).
 */
@Composable
fun BarcodeScanButton(
    label: String = L("Scan with the camera"),
    keepScanning: Boolean = false,
    modifier: Modifier = Modifier,
    onScan: (BarcodeScan) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconCircleButton(systemImage = "barcode.viewfinder", label = label, modifier = modifier) { open = true }
    if (open) {
        AppSheet(onDismissRequest = { open = false }) {
            BarcodeScannerSheet(keepScanning = keepScanning, onScan = onScan)
        }
    }
}

/**
 * A code field with the scan mark beside it (a product's barcode or SKU): typed, or read by the
 * camera — a hardware scanner types into it like a keyboard.
 */
@Composable
fun BarcodeField(
    label: String,
    text: MutableState<String>,
    key: String? = null,
    form: FormState? = null,
    prompt: String? = null,
    hint: String? = null,
    optional: Boolean = true,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    /** The longest code the field keeps (the server's limit). */
    maxLength: Int = 120,
    modifier: Modifier = Modifier,
    /** What the camera read, once it is in the field (a twin check, "also use it as the barcode"). */
    onScan: (BarcodeScan) -> Unit = {},
) {
    FormField(label = label, key = key, hint = hint, form = form, optional = optional, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CodeTextField(
                text = text,
                prompt = prompt,
                invalid = key?.let { form?.error(it) } != null,
                capitalization = capitalization,
                onChange = { key?.let { form?.clear(it) } },
                modifier = Modifier.weight(1f),
            )
            BarcodeScanButton { scan ->
                text.value = scan.code.take(maxLength)
                key?.let { form?.clear(it) }
                onScan(scan)
            }
        }
    }
}

/**
 * A search box with the scan mark beside it: typing searches, Return looks the code up (a
 * hardware scanner's Enter included), the camera reads it.
 */
@Composable
fun BarcodeSearchField(
    prompt: String,
    text: MutableState<String>,
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
    onScan: (BarcodeScan) -> Unit,
) {
    val submit by rememberUpdatedState(onSubmit)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SearchField(
            prompt = prompt,
            text = text,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        submit()
                        true
                    } else false
                },
        )
        BarcodeScanButton(onScan = onScan)
    }
}

private enum class BarcodeScannerPhase { starting, scanning, stopped }
private enum class BarcodeScannerProblem { denied, noCamera, failed }
private enum class BarcodePhotoState { idle, reading, none, failed }

/** With nothing read after this long, say so and offer typing (the camera keeps looking). */
private const val slowAfterMillis = 12_000L

/** While several codes are taken in a row, the code still in front of the lens is not taken twice. */
private const val sameCodeGapMillis = 2_500L

/**
 * The camera scanner, in the provider modal's frame. It layers over another sheet (the
 * delivery, the counter) as their own questions do; the camera is on only while it is open.
 */
@Composable
fun BarcodeScannerSheet(
    title: String = L("Scan a code"),
    /** Several codes in a row (a delivery being counted): the sheet stays open until Done. */
    keepScanning: Boolean = false,
    modifier: Modifier = Modifier,
    onScan: (BarcodeScan) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var phase by remember { mutableStateOf(BarcodeScannerPhase.starting) }
    var problem by remember { mutableStateOf<BarcodeScannerProblem?>(null) }
    var slow by remember { mutableStateOf(false) }
    var lastRead by remember { mutableStateOf<BarcodeScan?>(null) }
    var lastTaken by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val reader = remember { BarcodeStableReader() }
    val manual = remember { mutableStateOf("") }
    var photo by remember { mutableStateOf(BarcodePhotoState.idle) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var cameraRun by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val typing = remember { FocusRequester() }

    fun setTorch(on: Boolean) {
        val device = camera ?: return
        if (!device.cameraInfo.hasFlashUnit()) return
        val future = device.cameraControl.enableTorch(on)
        torchOn = on
        future.addListener({
            if (runCatching { future.get() }.isFailure) {
                if (on) torchAvailable = false
                torchOn = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun fail(kind: BarcodeScannerProblem) {
        setTorch(false)
        phase = BarcodeScannerPhase.stopped
        problem = kind
    }

    suspend fun start() {
        problem = null
        phase = BarcodeScannerPhase.starting
        reader.reset()
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            // Asked here, when the scanner opens (iOS asks at the first use too).
            val answer = PermissionRequests.request(arrayOf(permission))
            if (answer?.get(permission) != true) {
                fail(BarcodeScannerProblem.denied)
                return
            }
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            fail(BarcodeScannerProblem.noCamera)
            return
        }
        // The light is known once the camera is open (`onCamera`).
        camera = null
        torchAvailable = false
        torchOn = false
        cameraRun += 1
        phase = BarcodeScannerPhase.scanning
    }

    fun take(result: BarcodeScan) {
        if (result.code.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val last = lastTaken
        if (keepScanning && result.source == BarcodeScan.Source.camera && last != null && last.first == result.code && now - last.second < sameCodeGapMillis) {
            lastTaken = result.code to now
            return
        }
        lastTaken = result.code to now
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        lastRead = result
        slow = false
        reader.reset()
        onScan(result)
        if (!keepScanning) {
            done = true
            setTorch(false)
            dismiss()
        }
    }

    /** A frame's read: taken once it can be trusted. */
    fun frameRead(raw: String, format: String?) {
        if (done && !keepScanning) return
        if (!reader.push(raw, format)) return
        take(Barcode.interpret(raw, format, BarcodeScan.Source.camera))
    }

    fun acceptManual() {
        val typed = manual.value.trimWS()
        if (typed.isEmpty()) return
        manual.value = ""
        take(Barcode.interpret(typed, null, BarcodeScan.Source.typed))
    }

    suspend fun readPhoto(item: Uri) {
        photo = BarcodePhotoState.reading
        val found = try {
            BarcodeSymbology.read(photo = item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            photo = BarcodePhotoState.failed
            return
        }
        if (found == null) {
            photo = BarcodePhotoState.none
            return
        }
        photo = BarcodePhotoState.idle
        take(Barcode.interpret(found.first, found.second, BarcodeScan.Source.photo))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { readPhoto(uri) }
    }

    LaunchedEffect(Unit) { start() }
    LaunchedEffect(phase == BarcodeScannerPhase.scanning, lastRead?.code) {
        slow = false
        if (phase != BarcodeScannerPhase.scanning) return@LaunchedEffect
        delay(slowAfterMillis)
        if (phase == BarcodeScannerPhase.scanning) slow = true
    }
    // The light goes off when the app goes to the background (the camera is let go, and comes back with it) and when the sheet closes.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) setTorch(false) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            setTorch(false)
        }
    }

    ProviderModal(
        title = title,
        subtitle = L("Point the camera at the barcode. It is read by itself."),
        systemImage = "barcode.viewfinder",
        accent = ModalAccent.aqua,
        cancelTitle = if (keepScanning && lastRead != null) L("Done") else L("Cancel"),
        modifier = modifier,
    ) {
        // The camera.
        val shownProblem = problem
        if (shownProblem != null) {
            val copy = problemCopy(shownProblem)
            NoticeCard(
                tone = NoticeCard.Tone.problem,
                title = copy.first,
                message = copy.second,
                actionTitle = L("Try again"),
                action = { scope.launch { start() } },
            )
            if (shownProblem == BarcodeScannerProblem.denied) {
                PMButton(L("Open Settings"), systemImage = "gearshape", variant = ButtonVariant.outline, size = ButtonSize.compact) { openAppSettings(context) }
            }
        } else {
            val cameraLabel = L("Camera")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
                    .semantics { contentDescription = cameraLabel },
                contentAlignment = Alignment.Center,
            ) {
                if (phase == BarcodeScannerPhase.scanning) {
                    key(cameraRun) {
                        BarcodeCameraView(
                            onRead = { raw, format -> frameRead(raw, format) },
                            onFail = { fail(BarcodeScannerProblem.failed) },
                            onNoCamera = { fail(BarcodeScannerProblem.noCamera) },
                            onCamera = { opened ->
                                camera = opened
                                torchAvailable = opened.cameraInfo.hasFlashUnit()
                            },
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    BarcodeScanTarget(Modifier.matchParentSize())
                }
                if (phase == BarcodeScannerPhase.starting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Text(L("Opening the camera…"), style = Theme.text(14, FontWeight.SemiBold), color = Color.White)
                    }
                }
                if (phase == BarcodeScannerPhase.scanning && torchAvailable) {
                    val torchLabel = if (torchOn) L("Turn the light off") else L("Turn the light on")
                    Box(Modifier.matchParentSize().padding(10.dp)) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable(role = Role.Button) { setTorch(!torchOn) }
                                .semantics { contentDescription = torchLabel },
                            contentAlignment = Alignment.Center,
                        ) { Glyph(if (torchOn) "flashlight.off.fill" else "flashlight.on.fill", 16.dp, tint = Color.White) }
                    }
                }
            }
        }

        // What it is doing.
        when {
            problem != null -> {}
            slow -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("Nothing read yet. Hold the code steady and closer, with good light — or type it."), style = Theme.smallFont, color = Theme.inkSoft)
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clickable(role = Role.Button) { runCatching { typing.requestFocus() } },
                    contentAlignment = Alignment.CenterStart,
                ) { Text(L("Type it instead@scan"), style = Theme.text(15, FontWeight.Bold), color = Theme.blueText) }
            }
            phase == BarcodeScannerPhase.scanning && lastRead == null ->
                Text(L("Hold the code inside the frame."), style = Theme.smallFont, color = Theme.inkSoft)
        }

        lastRead?.let { read ->
            val lines = listOfNotNull(read.lot?.let { LF("Lot %@", it) }, read.expiresOn) +
                (if (keepScanning) listOf(L("Scan the next one, or close when you are done.")) else emptyList())
            NoticeCard(tone = NoticeCard.Tone.success, title = LF("Read: %@@scan", read.code), message = if (lines.isEmpty()) null else lines.joinToString("\n"))
        }

        when (photo) {
            BarcodePhotoState.reading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Theme.blue, strokeWidth = 2.dp)
                Text(L("Reading the photo…"), style = Theme.smallFont, color = Theme.inkSoft)
            }
            BarcodePhotoState.none ->
                Text(L("No code was found in that photo. Try one that is closer and sharper, or type the code."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            BarcodePhotoState.failed ->
                Text(L("That photo could not be read. Try another one, or type the code."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            BarcodePhotoState.idle -> {}
        }

        FormField(label = L("Or type the code")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CodeTextField(
                    text = manual,
                    prompt = L("The numbers under the bars"),
                    imeAction = ImeAction.Done,
                    onSubmit = { acceptManual() },
                    modifier = Modifier.weight(1f).focusRequester(typing),
                )
                val empty = manual.value.trimSpaces().isEmpty()
                // iOS `.disabled(…)`: dimmed, and the press does nothing.
                PMButton(L("Use this code"), variant = ButtonVariant.outline, size = ButtonSize.compact, modifier = Modifier.alpha(if (empty) 0.5f else 1f)) {
                    if (!empty) acceptManual()
                }
            }
        }
        val readingPhoto = photo == BarcodePhotoState.reading
        PMButton(
            L("Use a photo"),
            systemImage = "photo.on.rectangle",
            variant = ButtonVariant.outline,
            size = ButtonSize.compact,
            modifier = Modifier.alpha(if (readingPhoto) 0.5f else 1f),
        ) {
            if (!readingPhoto) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

private fun problemCopy(problem: BarcodeScannerProblem): Pair<String, String> = when (problem) {
    BarcodeScannerProblem.denied -> L("The camera is blocked for PawMiaw Business") to
        L("Allow the camera for PawMiaw Business in Settings, then try again. Or type the code, or use a photo of it.")
    BarcodeScannerProblem.noCamera -> L("No camera was found") to
        L("This device has no camera we can use. Type the code, or use a photo of it.")
    BarcodeScannerProblem.failed -> L("The camera could not be opened") to L("Try again, or type the code.")
}

/** iOS `UIApplication.openSettingsURLString`: this app's page in the system settings. */
private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * The code box of the field and of "Or type the code": the field chrome, ASCII keys, no
 * capitals or corrections unless asked, Return to submit.
 */
@Composable
private fun CodeTextField(
    text: MutableState<String>,
    prompt: String?,
    invalid: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Default,
    onSubmit: (() -> Unit)? = null,
    onChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val style = Theme.bodyFont.copy(color = Theme.inkStrong)
    val placeholder = Theme.inkSoft
    BasicTextField(
        value = text.value,
        onValueChange = {
            text.value = it
            onChange(it)
        },
        modifier = modifier.fieldChrome(invalid = invalid),
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(Theme.blue),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }, onGo = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (text.value.isEmpty() && !prompt.isNullOrEmpty()) Text(prompt, style = style, color = placeholder, maxLines = 1)
                inner()
            }
        },
    )
}

/** The frame drawn over the camera: four corners and the line that sweeps while it looks. */
@Composable
private fun BarcodeScanTarget(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), RepeatMode.Reverse),
        label = "sweep",
    )
    val line = Theme.coral.copy(alpha = 0.85f)
    Canvas(modifier) {
        val width = min(size.width * 0.78f, 340.dp.toPx())
        val height = min(size.height * 0.55f, 160.dp.toPx())
        val left = (size.width - width) / 2
        val top = (size.height - height) / 2
        val right = left + width
        val bottom = top + height
        val arm = min(width, height) * 0.22f
        val corners = Path().apply {
            moveTo(left, top + arm); lineTo(left, top); lineTo(left + arm, top)
            moveTo(right - arm, top); lineTo(right, top); lineTo(right, top + arm)
            moveTo(right, bottom - arm); lineTo(right, bottom); lineTo(right - arm, bottom)
            moveTo(left + arm, bottom); lineTo(left, bottom); lineTo(left, bottom - arm)
        }
        drawPath(corners, Color.White, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val travel = height / 2 - 10.dp.toPx()
        val centreY = center.y - travel + (2 * travel) * sweep
        val lineWidth = width - 24.dp.toPx()
        val thickness = 2.dp.toPx()
        drawRect(line, topLeft = Offset(center.x - lineWidth / 2, centreY - thickness / 2), size = Size(lineWidth, thickness))
    }
}

/**
 * The camera through CameraX, every frame read by ML Kit: the back camera (the front one on a
 * device with only that), filling its box. It is let go when it leaves the screen.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun BarcodeCameraView(
    onRead: (String, String?) -> Unit,
    onFail: () -> Unit,
    onNoCamera: () -> Unit,
    onCamera: (Camera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val read by rememberUpdatedState(onRead)
    val failed by rememberUpdatedState(onFail)
    val missing by rememberUpdatedState(onNoCamera)
    val opened by rememberUpdatedState(onCamera)
    val previewView = remember {
        PreviewView(context).apply {
            // A texture, so the rounded box and the sheet's slide clip it.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(BarcodeSymbology.options())
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return@setAnalyzer
            }
            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener(executor) { codes ->
                    for (code in codes) {
                        val raw = code.rawValue ?: continue
                        if (raw.trimSpaces().isEmpty()) continue
                        val (value, format) = BarcodeSymbology.normalized(raw, code.format)
                        read(value, format)
                    }
                }
                .addOnCompleteListener(executor) { proxy.close() }
        }
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameras = future.get()
                provider = cameras
                val selector = when {
                    cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) {
                    missing()
                    return@addListener
                }
                opened(cameras.bindToLifecycle(lifecycleOwner, selector, preview, analysis))
            } catch (e: Exception) {
                failed()
            }
        }, executor)
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
