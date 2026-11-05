package com.pawmiaw.business.services

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*

// Stand-ins for the Services pieces the visit page (sched-visit) uses; deleted at merge.
// SvcReportText itself is stood in by _stubs_models_a.kt: these add the members the visit reads.

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcReportText.missing(category: String?, report: SvcReport?): List<String> = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcReportText.fieldsText(fields: List<String>): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun SvcReportText.title(kind: String, category: String?): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter")
val SvcReportText.groomServices: List<String> get() = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Suppress("UnusedReceiverParameter")
val SvcReportText.styles: List<String> get() = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/GroomingTabs.swift
@Composable
fun SvcCareCard(
    petId: String,
    petName: String,
    canEdit: Boolean,
    grooming: Boolean = true,
    photoURL: String? = null,
    species: String? = null,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Services/SvcVisitSheets.swift (`wanted:` left out: SvcGroomWanted isn't ported yet)
@Composable
fun SvcReportForm(
    report: MutableState<SvcReport>,
    category: String?,
    appointmentId: String,
    editable: Boolean,
    startedAt: String? = null,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Services/SvcVisitSheets.swift
data class SvcFinishTarget(
    val id: String,
    val petName: String,
    val serviceLabel: String,
    val serviceCategory: String?,
    var reportMissing: List<String> = emptyList(),
    var clientName: String = "",
    var familyOnApp: Boolean = false,
    var assignedId: String? = null,
    var assignedName: String? = null,
    var together: List<String> = emptyList(),
    var locationId: String? = null,
)

// STUB: Modules/Services/SvcVisitSheets.swift
data class SvcFinishKnown(
    val drafts: Int,
    val signed: Int,
    /** The person finishing, when they can sign: (memberId, name). */
    val signer: Pair<String, String>?,
)

// STUB: Modules/Services/SvcVisitSheets.swift
@Composable
fun SvcFinishSheet(
    target: SvcFinishTarget,
    extraBody: Map<String, Any?> = emptyMap(),
    onOpenReport: (() -> Unit)? = null,
    onOpenVisit: (() -> Unit)? = null,
    onFinished: () -> Unit,
    prepare: (suspend () -> Map<String, Any?>)? = null,
    invoice: SchedInvoice? = null,
    known: SvcFinishKnown? = null,
    collects: Boolean? = null,
    onAfter: ((SchedChargeMode) -> Unit)? = null,
    onEarlierDay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {}

