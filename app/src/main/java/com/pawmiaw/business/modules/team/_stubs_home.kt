package com.pawmiaw.business.team

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Team symbols Home uses (ported in parallel by the Team job).
//  Signatures only; deleted at merge.
//

// STUB: Modules/Team/TeamMemberCredentials.swift
@Composable
fun TeamCredentialSheet(
    memberId: String,
    memberName: String? = null,
    isSelf: Boolean,
    country: String?,
    businessAddress: String?,
    types: List<String>? = null,
    reason: String? = null,
    modifier: Modifier = Modifier,
    onSaved: suspend () -> Unit,
) {}

// STUB: Modules/Team/TeamMemberCredentials.swift
object TeamCredentialRules {
    fun label(type: String): String = type
}

// STUB: Modules/Team/TeamMemberCredentials.swift
@Composable
fun TeamCredentialStatusChip(status: String?, verified: Boolean? = null, modifier: Modifier = Modifier) {}

// STUB: Modules/Team/AvailabilityScreen.swift
@Composable
fun TeamTimeZonePicker(selection: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {}

// STUB: Modules/Team/TeamHours.swift
object TeamHours {
    fun zoneLabel(identifier: String): String = identifier
}

// STUB: Modules/Team/TeamSettingsSections.swift
object TeamRegion {
    val currencies: List<String> = listOf("MXN", "USD")
    fun defaultLanguage(country: String?): String? = null
    fun percent(bps: Int): String = (bps / 100).toString()
}
