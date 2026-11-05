package com.pawmiaw.business.places

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Places symbols Team's settings use (ported in parallel). Deleted at merge.
//

// STUB: Modules/Places/PlacesLocationSheet.swift
@Composable
fun PlacesTimeZonePicker(
    selection: String,
    suggestions: List<String> = emptyList(),
    /** A line under a suggestion ("This device’s time zone"). */
    notes: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {}
