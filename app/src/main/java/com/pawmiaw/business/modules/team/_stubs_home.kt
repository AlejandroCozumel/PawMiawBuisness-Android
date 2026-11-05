package com.pawmiaw.business.team

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Team symbols Home uses (ported in parallel by the Team job).
//  Signatures only; deleted at merge.
//




// STUB: Modules/Team/AvailabilityScreen.swift
@Composable
fun TeamTimeZonePicker(selection: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {}


// STUB: Modules/Team/TeamSettingsSections.swift
object TeamRegion {
    val currencies: List<String> = listOf("MXN", "USD")
    fun defaultLanguage(country: String?): String? = null
    fun percent(bps: Int): String = (bps / 100).toString()
}
