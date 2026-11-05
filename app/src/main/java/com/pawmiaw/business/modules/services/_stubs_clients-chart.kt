package com.pawmiaw.business.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Services symbols the patient chart uses (ported in wave 2). Deleted at
//  merge. `SvcReportText` is already stood in by `_stubs_models_a.kt`, so its other words are
//  extensions here (`SvcReportText.rows(r)` reads the same as the member it becomes).
//

// STUB: Modules/Services/SvcCommon.swift (Swift `[(label:, value:, concern:)]`)
fun SvcReportText.rows(report: SvcReport): List<Triple<String, String, Boolean>> = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
fun SvcReportText.title(kind: String, category: String?): String = TODO("STUB: SvcCommon.swift")

// STUB: Modules/Services/SvcCommon.swift
@Composable
fun SvcReportView(report: SvcReport, compact: Boolean = false, modifier: Modifier = Modifier) {}

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
