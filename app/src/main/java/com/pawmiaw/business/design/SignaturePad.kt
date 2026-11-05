package com.pawmiaw.business

import android.content.res.Resources
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

//
//  SignaturePad.kt (iOS SignaturePad.swift)
//
//  Sign with a finger: the strokes become the PNG data URL the server keeps
//  (consents, closing a file, a prescription, the person's own signature).
//  The strokes are kept in the drawing's own 600 × 200 space (iOS `CGPoint`s → `Offset`s),
//  whatever size the pad is drawn at; the PNG is made at that size.
//

/**
 * The pad (iOS `SignaturePad`): a white box with a strong border, "Sign here" while empty,
 * the strokes in navy. [strokes] is the Swift `@Binding var strokes: [[CGPoint]]`.
 */
@Composable
fun SignaturePad(
    strokes: MutableState<List<List<Offset>>>,
    placeholder: String = L("Sign here"),
    height: Dp = 160.dp,
    /** A "Clear" link under the pad (screens with their own clear button leave it off). */
    showsClear: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val binding by rememberUpdatedState(strokes)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(Color.White, shape)
                .border(1.5.dp, Theme.borderStrong, shape)
                .semantics { contentDescription = L("Your signature") }
                .pointerInput(Unit) {
                    // DragGesture(minimumDistance: 0): a touch starts a stroke, a move adds to it.
                    fun point(at: Offset): Offset = Offset(
                        at.x / max(size.width.toFloat(), 1f) * SignaturePad.size.width,
                        at.y / max(size.height.toFloat(), 1f) * SignaturePad.size.height,
                    )
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        binding.value = binding.value + listOf(listOf(point(down.position)))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.position != change.previousPosition) {
                                val all = binding.value
                                val last = all.lastOrNull() ?: emptyList()
                                binding.value = all.dropLast(1) + listOf(last + point(change.position))
                            }
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (strokes.value.isEmpty()) {
                Text(placeholder, style = Theme.text(14), color = Color(102, 117, 138))
            }
            Canvas(Modifier.fillMaxSize()) {
                val path = Path()
                SignaturePad.draw(strokes.value, into = path, scale = Size(size.width / SignaturePad.size.width, size.height / SignaturePad.size.height))
                drawPath(path, color = Theme.navy, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (showsClear && strokes.value.isNotEmpty()) {
            Text(
                L("Clear"),
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blueText,
                modifier = Modifier.padPlainPress { strokes.value = emptyList() },
            )
        }
    }
}

/** The pad's drawing rules and its PNG (iOS `SignaturePad`'s statics). */
object SignaturePad {
    /** The drawing's own size; the PNG is made at it. */
    val size = Size(600f, 200f)

    /** The strokes as one path, each point scaled; a single touch is a dot. */
    fun draw(strokes: List<List<Offset>>, into: Path, scale: Size) {
        for (stroke in strokes) {
            val first = stroke.firstOrNull() ?: continue
            into.moveTo(first.x * scale.width, first.y * scale.height)
            if (stroke.size == 1) into.lineTo(first.x * scale.width + 0.5f, first.y * scale.height + 0.5f)
            for (point in stroke.drop(1)) into.lineTo(point.x * scale.width, point.y * scale.height)
        }
    }

    /** The signature as the server takes it: a PNG data URL (null when nothing is drawn). */
    fun pngDataURL(strokes: List<List<Offset>>): String? {
        val data = pngData(strokes) ?: return null
        return "data:image/png;base64,${Base64.encodeToString(data, Base64.NO_WRAP)}"
    }

    /**
     * The signature as PNG bytes, for a multipart upload: navy (16, 34, 59) on white, a 3 pt
     * line, 600 × 200 points at the screen's scale (iOS `UIGraphicsImageRenderer(size:)`: 2× or 3×).
     */
    fun pngData(strokes: List<List<Offset>>): ByteArray? {
        val points = strokes.sumOf { it.size }
        if (points < 2) return null
        val scale = screenScale()
        val bitmap = Bitmap.createBitmap((size.width * scale).roundToInt(), (size.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(scale, scale)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = android.graphics.Color.rgb(16, 34, 59)
        }
        canvas.drawPath(androidPath(strokes), paint)
        return png(bitmap)
    }

    /** iOS `UIScreen.main.scale`: the density, as the whole 2 or 3 iOS renders at. */
    fun screenScale(): Float = Resources.getSystem().displayMetrics.density.roundToInt().coerceIn(2, 3).toFloat()

    /** The same path as `draw`, for an Android canvas (the PNGs). */
    fun androidPath(strokes: List<List<Offset>>): android.graphics.Path {
        val path = android.graphics.Path()
        for (stroke in strokes) {
            val first = stroke.firstOrNull() ?: continue
            path.moveTo(first.x, first.y)
            if (stroke.size == 1) path.lineTo(first.x + 0.5f, first.y + 0.5f)
            for (point in stroke.drop(1)) path.lineTo(point.x, point.y)
        }
        return path
    }

    fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}

/** A plain text button (SwiftUI's default `Button` style): the words dim while pressed. */
@Composable
private fun Modifier.padPlainPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .alpha(if (pressed) 0.35f else 1f)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}
