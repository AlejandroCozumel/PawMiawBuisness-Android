package com.pawmiaw.business.services

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.serialization.Serializable

//
//  Stand-ins for the Services symbols Domicilio uses from the files svc-groups-groom ports
//  (SvcPieces, GroupsScreen, SvcBooking, SvcGroomRequest). Signatures only; deleted at merge.
//

// STUB: Modules/Services/SvcPieces.swift
@Composable
fun SvcLoadError(message: String, compact: Boolean = true, modifier: Modifier = Modifier, retry: suspend () -> Unit) {}

// STUB: Modules/Services/SvcPieces.swift
@Composable
fun SvcHandlingChips(tags: List<String>, large: Boolean = false, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/SvcPieces.swift
@Composable
fun SvcChoiceChip(
    title: String,
    isSelected: Boolean,
    danger: Boolean = false,
    wash: Boolean = false,
    height: Dp = 32.dp,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {}

// STUB: Modules/Services/SvcPieces.swift
@Composable
fun SvcMessagePreview(text: String, standard: Boolean, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/SvcPieces.swift
@Composable
fun SvcPlaceholderRow(
    placeholders: List<String>,
    onAdd: (String) -> Unit,
    trailing: String? = null,
    extra: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Services/GroupsScreen.swift
sealed class SvcSessionRoute {
    data class session(val id: String) : SvcSessionRoute()
    data class provider(val route: ProviderRoute) : SvcSessionRoute()
}

// STUB: Modules/Services/GroupsScreen.swift
@Composable
fun SvcGroupSessionsPointer(dayKey: String, timeZone: String?, push: (SvcSessionRoute) -> Unit, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/SvcBooking.swift
@Serializable
data class SvcCatalogService(val id: String, val category: String, val label: String)

// STUB: Modules/Services/SvcBooking.swift
object SvcServices {
    suspend fun load(scope: String): List<SvcCatalogService> = emptyList()
    fun cached(scope: String): List<SvcCatalogService> = emptyList()
    fun pick(category: String?, label: String? = null): String? = null
}

// STUB: Modules/Services/SvcBooking.swift
object SvcBook {
    fun homeCategory(workspace: Workspace): String? = null
}

// STUB: Modules/Services/SvcGroomRequest.swift
@Serializable
data class SvcGroomWanted(
    val services: List<String>? = null,
    val style: String? = null,
    val styleText: String? = null,
    val length: String? = null,
)

// STUB: Modules/Services/SvcGroomRequest.swift
@Serializable
data class SvcGroomRequestView(
    val appointmentId: String,
    val request: JSONValue? = null,
    val wanted: SvcGroomWanted? = null,
    val prefill: SvcReport? = null,
) {
    val hasRequest: Boolean get() = !request.isNull
}

// STUB: Modules/Services/SvcGroomRequest.swift
class SvcGroomDifferences(val any: Boolean)

// STUB: Modules/Services/SvcGroomRequest.swift
object SvcGroomAsk {
    fun compare(wanted: SvcGroomWanted?, report: SvcReport): SvcGroomDifferences? = null
}

// STUB: Modules/Services/SvcGroomRequest.swift
@Composable
fun SvcGroomAskedLine(wanted: SvcGroomWanted, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/SvcGroomRequest.swift
@Composable
fun SvcGroomDifferenceBox(differences: SvcGroomDifferences, note: MutableState<String>, editable: Boolean, modifier: Modifier = Modifier) {}

// STUB: Modules/Services/SvcGroomRequest.swift
@Composable
fun SvcUsualStyleOffer(
    view: SvcGroomRequestView,
    report: SvcReport,
    unsaved: Boolean,
    modifier: Modifier = Modifier,
    saveFirst: suspend () -> Boolean,
    onSaved: suspend () -> Unit,
) {}
