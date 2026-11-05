package com.pawmiaw.business.services

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Services symbols the patient chart uses (ported in wave 2). Deleted at
//  merge.
//

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
