package com.pawmiaw.business

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  Stand-ins for symbols other areas own, so the controls compile on their own. Deleted at
//  merge: never real code here.
//

// STUB: ModuleHeader.swift (EnvironmentValues.headerButtons)
val LocalHeaderButtons = compositionLocalOf { false }

// STUB: Components.swift
@Composable
fun RowDivider(color: Color = Theme.borderSoft, modifier: Modifier = Modifier) {
    Box(modifier)
}

// STUB: Components.swift
@Composable
fun StitchedDivider(lineWidth: Dp = 2.dp, color: Color = Theme.borderSoft, modifier: Modifier = Modifier) {
    Box(modifier)
}

// STUB: Components.swift (TagChip.Size)
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
) {
    Text(text, modifier)
}

// STUB: Components.swift
@Composable
fun ToneIcon(
    icon: String,
    tone: ChipTone,
    selected: Boolean,
    size: Dp = 24.dp,
    glyph: Dp = 14.dp,
    dot: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size))
}

// STUB: Components.swift
@Composable
fun ToneCount(count: Int, tone: ChipTone, selected: Boolean, modifier: Modifier = Modifier) {
    Text("$count", modifier)
}

// STUB: Components.swift
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
) {
    Text(title, modifier.plainButton(onClick = action))
}

// STUB: Components.swift
@Composable
fun PetAvatar(name: String?, species: String? = null, photoURL: String? = null, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size))
}

// STUB: Components.swift
@Composable
fun LoadingState(text: String? = null, modifier: Modifier = Modifier) {
    Box(modifier)
}

// STUB: Components.swift
@Composable
fun ErrorState(message: String, retry: (suspend () -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier) { Text(message) }
}
