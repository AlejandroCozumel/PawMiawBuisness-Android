package com.pawmiaw.business

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  The one icon view (iOS Glyph.swift). The web draws Lucide line icons (2 px stroke at 24);
//  screens keep naming the SF Symbols the iOS code names, and `Glyph` draws the Lucide icon the
//  name maps to (GlyphNames.kt), or a Lucide name directly ("calendar-days"). Its colour is the
//  `tint`, else the surrounding `LocalContentColor` (iOS `.foregroundStyle`). A name with no
//  Lucide icon draws a small circle so a missing mapping is visible, never a crash.
//

object Glyphs {
    private val cache = HashMap<String, ImageVector>()

    /** The Lucide icon for an SF Symbol name (".fill" ignored) or a Lucide name. */
    fun lucide(name: String): String? {
        if (LucideData.icons.containsKey(name)) return name
        GlyphNames.aliases[name]?.let { return it }
        val base = name.removeSuffix(".fill")
        return GlyphNames.sf[base] ?: GlyphNames.sf[name]
    }

    fun vector(name: String): ImageVector {
        val key = lucide(name) ?: "circle-dashed"
        return cache.getOrPut(key) {
            val builder = ImageVector.Builder(name = key, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            for (d in LucideData.icons[key] ?: LucideData.icons["circle-dashed"] ?: emptyArray()) {
                builder.addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            builder.build()
        }
    }
}

/** iOS `Glyph(name, size:)`. */
@Composable
fun Glyph(name: String, size: Dp = 16.dp, tint: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val vector = remember(name) { Glyphs.vector(name) }
    Icon(
        imageVector = vector,
        contentDescription = null,
        tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
        modifier = modifier.size(size),
    )
}

/** iOS `GlyphLabel(title, icon:)`: the icon and the words on one line (menus, rows). */
@Composable
fun GlyphLabel(title: String, icon: String, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, size)
        Text(title)
    }
}
