package com.pawmiaw.business

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  Shared pieces several modules draw (iOS Pieces.swift): a sheet's context pills, a file
//  tile, the settings card of a service page (its tinted head, its rules, its save bar).
//  Also `Modifier.softShadow`, SwiftUI's `.shadow(color:radius:x:y:)` (a coloured, blurred
//  shadow under a shape: the toast, a chosen segment, a menu), drawn outside the shape only.
//

/** SwiftUI `.shadow(color:radius:x:y:)` under a shape: a soft coloured shadow outside it. */
fun Modifier.softShadow(shape: Shape, color: Color, radius: Dp, x: Dp = 0.dp, y: Dp = 0.dp): Modifier = drawBehind {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
    val native = path.asAndroidPath()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.BLACK
        setShadowLayer(radius.toPx().coerceAtLeast(0.1f), x.toPx(), y.toPx(), color.toArgb())
    }
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        c.save()
        c.clipOutPath(native)
        c.drawPath(native, paint)
        c.restore()
    }
}

// MARK: - Context pills

/**
 * Whose visit or record a sheet is about (web `ModalContextPills`): the client and each pet
 * as soft pills (32 tall, 12 semibold, the muted fill with a hairline), the client's link
 * badge after the client, then any other pill (a room, the record's kind) in `extra`.
 */
@Composable
fun ContextPills(
    client: String?,
    /** The client's link badge: true "FAMILIA VINCULADA", false "SOLO EN LA CLÍNICA", null none. */
    linked: Boolean? = null,
    pets: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    FlowLayout(spacing = 8.dp, modifier = modifier) {
        client.nonEmpty?.let { ContextPill(it, "user-round") }
        if (linked != null) {
            TagChip(
                text = if (linked) L("Linked household") else L("Clinic-only"),
                icon = if (linked) "link-2" else "building-2",
                linked = linked,
                size = TagChip.Size.header,
            )
        }
        pets.forEach { ContextPill(it, "paw-print") }
        extra()
    }
}

@Composable
private fun ContextPill(text: String, icon: String) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .background(Theme.surfaceMuted, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 14.dp, tint = Theme.inkStrong)
        Text(text, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - A file

/**
 * A file as a square tile (web chart-bits `AttachmentTiles`, the record page's files): a photo
 * shows itself (the `thumbnail`), any other file its icon on a pale wash with its name. With
 * `caption` the name and size sit under the tile (the record page); without, inside it (a
 * chart's history).
 */
@Composable
fun AttachmentTile(
    fileName: String,
    sizeText: String? = null,
    isImage: Boolean = false,
    isPDF: Boolean = false,
    /** The file's kind on a white tag ("PDF") in a document tile. */
    fileExtension: String? = null,
    caption: Boolean = true,
    isOpening: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
    thumbnail: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(if (caption) Theme.boxRadius else Theme.chipRadius)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(Theme.surfaceMuted)
                .border(1.dp, Theme.borderSoft, shape)
                .plainButton(label = LF("Open %@", fileName), onClick = action),
        ) {
            when {
                isImage -> Box(Modifier.fillMaxSize(), content = thumbnail)
                caption -> Column(
                    Modifier.fillMaxSize().background(Theme.wash(Theme.pink, 16.0)),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Glyph("file-text", 28.dp, tint = Theme.inkStrong)
                    fileExtension.nonEmpty?.let { ext ->
                        Text(
                            ext,
                            style = Theme.text(9.6, FontWeight.ExtraBold).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.4f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            color = Theme.inkStrong,
                            modifier = Modifier.background(Theme.surface, RoundedCornerShape(50)).padding(horizontal = 6.dp),
                        )
                    }
                }
                else -> Column(
                    Modifier.fillMaxSize().background(Theme.wash(Theme.aqua, 10.0)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                        Glyph(if (isPDF) "file-text" else "file", 24.dp)
                        Text(fileName, style = Theme.text(9.9, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        if (sizeText != null) Text(sizeText, style = Theme.text(9.3))
                    }
                }
            }
            if (isOpening) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (caption) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isImage) Glyph("file-image", 12.dp, tint = Theme.inkSoft)
                MiddleTruncatedText(fileName, Theme.text(12, FontWeight.Bold), Theme.inkStrong, Modifier.weight(1f, fill = false))
            }
            if (sizeText != null) Text(sizeText, style = Theme.text(11.2), color = Theme.inkSoft)
        }
    }
}

/** SwiftUI `.lineLimit(1).truncationMode(.middle)`: a file name's start, "…", its end (the extension). */
@Composable
private fun MiddleTruncatedText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val shown = remember(text, room, style) {
            fun fits(value: String) = measurer.measure(value, style, maxLines = 1, softWrap = false).size.width <= room
            fun cut(keep: Int): String {
                val head = (keep + 1) / 2
                return text.take(head) + "…" + text.takeLast(keep - head)
            }
            if (!bounded || fits(text)) text else {
                var low = 0
                var high = text.length - 1
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (fits(cut(mid))) low = mid else high = mid - 1
                }
                cut(low)
            }
        }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

// MARK: - A service's settings

/**
 * How the business does a service (web grooming-settings / home-visits-settings /
 * stays-rooms-settings): one card with a tinted head (a 44 pebble at the tone's 38%, Fredoka
 * 24, a line), a 1 pt dashed foot, the rules under it and the save bar at its foot.
 */
@Composable
fun SettingsCard(
    icon: String,
    tone: Color,
    title: String,
    hint: String,
    note: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        modifier
            .cardShadow(shape)
            .background(Theme.surface, shape)
            .clip(shape)
            .border(1.dp, Theme.borderSoft, shape),
    ) {
        Box(Modifier.fillMaxWidth().background(Theme.wash(tone, 16.0))) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(44.dp).background(Theme.wash(tone, 38.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(icon, 20.dp, tint = Theme.inkStrong)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Theme.heading(24), color = Theme.inkStrong)
                    Text(hint, style = Theme.text(14), color = Theme.inkSoft)
                    if (note != null) Text(note, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                }
            }
            StitchedDivider(lineWidth = 1.dp, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
        footer()
    }
}

/**
 * One rule of a settings card (web `Rule`): a box washed in its tone (8%, a 1.5 pt edge at
 * 55%), a 28 disc and a Fredoka 18 title, the line that explains it, then its control.
 */
@Composable
fun SettingsRule(
    icon: String,
    tone: Color,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(tone, 8.0), shape)
            .border(1.5.dp, Theme.mix(tone, 55.0, Theme.borderSoft), shape)
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Theme.wash(tone, 45.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(icon, 16.dp, tint = Theme.inkStrong)
            }
            Text(title, style = Theme.heading(18), color = Theme.inkStrong)
        }
        Text(hint, style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(top = 4.dp))
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/**
 * A settings card's foot (web: grooming / home visits / stays / hospital settings): "Unsaved
 * changes" on a mango wash with Discard, or "Saved"; the save at the right, both `size="sm"`.
 * `floating`: its own card under the settings (the hospital's one save for two cards).
 */
@Composable
fun SaveBar(
    dirty: Boolean,
    working: Boolean,
    saveTitle: String = L("Save settings"),
    floating: Boolean = false,
    modifier: Modifier = Modifier,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val shape: Shape = if (floating) RoundedCornerShape(Theme.cardRadius) else RectangleShape
    val edge = if (dirty) Theme.mix(Theme.mango, 55.0, Theme.borderSoft) else Theme.borderSoft
    val divider = Theme.borderSoft
    Row(
        modifier
            .fillMaxWidth()
            .then(if (floating) Modifier.cardShadow(shape) else Modifier)
            .background(if (dirty) Theme.wash(Theme.mango, 14.0) else Theme.surface, shape)
            .then(if (floating) Modifier.border(1.dp, edge, shape) else Modifier)
            .then(
                if (floating) Modifier
                else Modifier.drawWithContent {
                    drawContent()
                    drawRect(divider, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dirty) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Theme.mango, CircleShape))
                Text(L("Unsaved changes"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("check", 14.dp, tint = Theme.blue)
                Text(L("Saved"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
            }
        }
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        if (dirty) PMButton(L("Discard"), variant = ButtonVariant.outline, size = ButtonSize.small, enabled = !working, action = onDiscard)
        PMButton(saveTitle, systemImage = "save", size = ButtonSize.small, isWorking = working, enabled = dirty, action = onSave)
    }
}
