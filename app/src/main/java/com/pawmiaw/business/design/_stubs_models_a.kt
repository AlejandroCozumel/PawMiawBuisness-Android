package com.pawmiaw.business

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Stand-ins for design-kit symbols the models-a files use; deleted at merge.

// STUB: Design/ClientPetPicker.swift
object ClientSearch {
    const val minimumCharacters = 2
    const val pageSize = 20
}

// STUB: Design/ModuleHeader.swift (IconBadge.Ink)
enum class IconBadgeInk { onAccent, strong, accent }

// STUB: Design/ModuleHeader.swift
@Composable
fun IconBadge(
    icon: String,
    accent: Color = Theme.blue,
    size: Dp = 44.dp,
    iconSize: Dp? = null,
    ink: IconBadgeInk = IconBadgeInk.onAccent,
    washPercent: Double? = null,
    modifier: Modifier = Modifier,
) {}

// STUB: Design/Forms.swift
@Composable
fun ToggleRow(title: String, subtitle: String? = null, isOn: MutableState<Boolean>, modifier: Modifier = Modifier) {}

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
) {}
