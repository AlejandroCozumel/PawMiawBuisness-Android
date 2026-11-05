package com.pawmiaw.business

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  Two small shared pieces (iOS Pills.swift): a choice among a few values drawn as pills that
//  wrap (a mood, a severity, a kind), and a row of overlapping pet faces with "+N" (a group, a
//  family's pets on one visit).
//

/**
 * A choice among a few values as wrapping pills (one shared FilterChip each): the web's
 * Segmented. The pills of Stays, Hospital, Schedule and Home's requests. A second tap on the
 * chosen pill sets `cleared` (nothing chosen); null keeps it chosen.
 */
@Composable
fun <Value> PillPicker(
    options: List<Pair<Value, String>>,
    selection: MutableState<Value>,
    cleared: Value? = null,
    modifier: Modifier = Modifier,
) {
    FlowLayout(spacing = 8.dp, modifier = modifier) {
        options.forEach { (value, title) ->
            FilterChip(title = title, isSelected = selection.value == value) {
                if (cleared != null && selection.value == value) selection.value = cleared else selection.value = value
            }
        }
    }
}

/**
 * Over a choice that may be empty (iOS `PillPicker(options:choice:allowsNone:)`); with
 * `allowsNone` a second tap on the chosen pill clears it. Call it with `choice =`.
 */
@JvmName("PillPickerChoice")
@Composable
fun <Wrapped> PillPicker(
    options: List<Pair<Wrapped, String>>,
    choice: MutableState<Wrapped?>,
    allowsNone: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FlowLayout(spacing = 8.dp, modifier = modifier) {
        options.forEach { (value, title) ->
            FilterChip(title = title, isSelected = choice.value == value) {
                if (allowsNone && choice.value == value) choice.value = null else choice.value = value
            }
        }
    }
}

/**
 * A few pets' faces, overlapping, and "+N" for the rest: the one stack of faces of Services,
 * Stays, the Schedule's requests and Home. Each pet is (name, species, photo).
 */
@Composable
fun PetStack(
    pets: List<Triple<String, String?, String?>>,
    size: Dp = 30.dp,
    /** How many faces show before "+N". */
    max: Int = 4,
    modifier: Modifier = Modifier,
) {
    val ring = Theme.surface
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-size / 3), verticalAlignment = Alignment.CenterVertically) {
        pets.take(max).forEach { (name, species, photo) ->
            PetAvatar(
                name = name,
                species = species,
                photoURL = photo,
                size = size,
                // SwiftUI `Circle().stroke(surface, lineWidth: 2)`: the ring sits on the face's edge.
                modifier = Modifier.drawWithContent {
                    drawContent()
                    drawCircle(ring, radius = this.size.minDimension / 2, style = Stroke(2.dp.toPx()))
                },
            )
        }
        if (pets.size > max) {
            Box(Modifier.size(size).background(Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
                Text("+${pets.size - max}", style = Theme.text(11, FontWeight.ExtraBold), color = Theme.inkStrong)
            }
        }
    }
}
