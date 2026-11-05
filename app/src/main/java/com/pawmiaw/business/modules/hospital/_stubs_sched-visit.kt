package com.pawmiaw.business.hospital

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

// Stand-ins for the Hospital pieces the visit page (sched-visit) uses; deleted at merge.

// STUB: Modules/Hospital/HospitalBits.swift
@Composable
fun HospTriageBadge(priority: String, modifier: Modifier = Modifier) {}

// STUB: Modules/Hospital/HospitalBits.swift
@Composable
fun HospVitalsFields(draft: MutableState<HospVitalsDraft>, invalid: HospVitalsDraft.Field? = null, modifier: Modifier = Modifier) {}
