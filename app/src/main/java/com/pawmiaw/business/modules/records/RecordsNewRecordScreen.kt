package com.pawmiaw.business.records

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import kotlinx.serialization.Serializable

//
//  RecordsNewRecordScreen.kt (iOS RecordsNewRecordScreen.swift)
//
//  A record written from the screen that asks for it (a pet's chart, a visit,
//  a follow-up's paperwork: ProviderRoute.newRecord). It is the record sheet
//  (RecordsRecordSheet) with what that screen knows already chosen, as the web
//  opens it there: a client and its pet skip "Client + pet", a type skips
//  "Record type", a visit is saved with the record and lends its client, pet
//  and the person it is with. Pushed rather than presented; Cancel and saving
//  go back, and the save is announced to every screen.
//

/** What a visit says about the record written at it. */
@Serializable
private data class RecordsVisitContext(
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    val assignedMember: RecordsMemberRef? = null,
)

@Composable
fun NewRecordScreen(prefill: RecordPrefill, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    var context by remember { mutableStateOf<RecordsSheetContext?>(null) }
    var members by remember { mutableStateOf<List<RecordsMember>>(emptyList()) }

    val access = RecordsAccess(workspace)

    /** Reads what the hand-off left out: the visit's client, pet and person, the pet's client, and the team. */
    suspend fun prepare() {
        val clinic = workspace.current?.clinicId
        var next = RecordsSheetContext(
            clientId = prefill.clientId.nonEmpty,
            petId = prefill.petId.nonEmpty,
            appointmentId = prefill.appointmentId.nonEmpty,
            kind = RecordsSheetContext.kind(prefill.recordType),
        )
        val team = recordsStart(clinic != null) { RecordsAPI.members(clinic ?: "") }
        val visitId = next.appointmentId
        if (visitId != null) {
            val path = "/provider-appointments/${Uri.encode(visitId, "/:@-._~")}"
            val visit = recordsStart { API.get<RecordsVisitContext>(path) }.await()
            if (visit != null) {
                if (next.clientId == null) next = next.copy(clientId = visit.providerClientId.nonEmpty)
                if (next.petId == null) next = next.copy(petId = visit.providerClientPetId.nonEmpty)
                next = next.copy(assigneeId = visit.assignedMember?.id.nonEmpty)
            }
        }
        val pet = next.petId
        if (next.clientId == null && pet != null) {
            next = next.copy(clientId = RecordsPetLookup.find(pet).clientId)
        }
        members = team.await() ?: emptyList()
        context = next
    }

    LaunchedEffect(Unit) {
        if (context != null || !access.writeRecords) return@LaunchedEffect
        prepare()
    }

    val ready = context
    when {
        !access.writeRecords -> providerPage(L("New record"), modifier = modifier) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role write records.")) }
        }
        ready != null ->
            // The sheet opened as a page: its header is the top, no bar.
            CompositionLocalProvider(LocalModalAsPage provides true) {
                RecordsRecordSheet(context = ready, members = members, access = access, modifier = modifier)
            }
        else -> providerPage(L("New record"), modifier = modifier) {
            PageScroll { LoadingState() }
        }
    }
}
