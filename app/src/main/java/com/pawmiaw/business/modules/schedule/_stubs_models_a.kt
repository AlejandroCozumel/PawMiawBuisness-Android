package com.pawmiaw.business.schedule

import kotlinx.serialization.Serializable

// Stand-ins for Schedule types the models-a files use; deleted at merge.

// STUB: Modules/Schedule/SchedModels.swift
@Serializable
data class SchedAssigned(
    val id: String? = null,
    val name: String? = null,
)

// STUB: Modules/Schedule/SchedModels.swift
@Serializable
data class SchedRescheduleProposal(
    val startsAt: String,
    val endsAt: String? = null,
    val assigned: SchedAssigned? = null,
    val room: String? = null,
    val requestedAt: String? = null,
    val releasesAt: String? = null,
)
