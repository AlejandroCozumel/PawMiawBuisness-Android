package com.pawmiaw.business.clients

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*

//
//  Stand-ins for the Clients symbols the clients-main job ports (ClientDetailScreen,
//  ClientsListScreen, ClientsClientSections, ClientsForms, ClientsLinking, ClientsInviteSheets,
//  ClientsClosingSheets, ClientsMovePet), used by the chart, Due and Invitations. Deleted at merge.
//

// STUB: Modules/Clients/ClientsClientSections.swift
object ClientsAddressWords {
    fun vaultField(field: String): String = TODO("STUB: ClientsClientSections.swift")
}

// STUB: Modules/Clients/ClientsLinking.swift
object ClientsMatches {
    fun fullDay(iso: String?): String = TODO("STUB: ClientsLinking.swift")
}

// STUB: Modules/Clients/ClientsLinking.swift
object ClientsPetDetails {
    fun of(pet: ClientsPet, createdAt: String?): String = TODO("STUB: ClientsLinking.swift")
}

// STUB: Modules/Clients/ClientsLinking.swift
data class ClientsMergePetSource(
    val clientId: String,
    val clientName: String,
    val petId: String,
    val name: String,
    val species: String?,
    val breed: String?,
    val photoUrl: String?,
    val linked: Boolean,
    val householdPetId: String?,
    val details: String,
) {
    val id: String get() = petId
}

// STUB: Modules/Clients/ClientsLinking.swift
@Composable
fun ClientsMergePetSheet(source: ClientsMergePetSource, ownPets: List<ClientsPet>, canSearchOthers: Boolean, onMerged: (String, String) -> Unit, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsLinking.swift
data class ClientsUnlinkTarget(val clientId: String, val clientName: String, val petId: String, val petName: String) {
    val id: String get() = petId
}

// STUB: Modules/Clients/ClientsLinking.swift
@Composable
fun ClientsUnlinkPetSheet(target: ClientsUnlinkTarget, onDone: suspend () -> Unit = {}, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsMovePet.swift
data class ClientsMoveTarget(val clientId: String, val clientName: String, val petId: String, val name: String, val linked: Boolean) {
    val id: String get() = petId
}

// STUB: Modules/Clients/ClientsMovePet.swift
@Composable
fun ClientsMovePetSheet(target: ClientsMoveTarget, onMoved: (String, String) -> Unit, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientDetailScreen.swift
class ClientsClientPage(val clientId: String) {
    companion object {
        /** One optional read: null when it isn't asked for or it fails. */
        suspend inline fun <reified T> fetch(enabled: Boolean, path: String, query: Map<String, String?> = emptyMap()): T? =
            TODO("STUB: ClientDetailScreen.swift")
    }
}

// STUB: Modules/Clients/ClientsListScreen.swift
@Composable
fun ClientsLinkBadge(linked: Boolean, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsForms.swift
@Composable
fun ClientsEditPetSheet(clientName: String, pet: ClientsPet, onSaved: () -> Unit = {}, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsClosingSheets.swift
sealed class ClientsClosingTarget {
    data class archivePet(val clientId: String, val petId: String, val name: String) : ClientsClosingTarget()
    data class petDeath(val clientId: String, val petId: String, val name: String) : ClientsClosingTarget()
    data class archiveClient(val id: String, val name: String, val firstName: String, val owed: String?) : ClientsClosingTarget()
}

// STUB: Modules/Clients/ClientsClosingSheets.swift
sealed class ClientsRestoreTarget {
    data class pet(val id: String, val name: String, val deceased: Boolean) : ClientsRestoreTarget()
    data class client(val id: String, val name: String) : ClientsRestoreTarget()
}

// STUB: Modules/Clients/ClientsClosingSheets.swift
@Composable
fun ClientsClosingSheet(target: ClientsClosingTarget, onDone: (ClientsClosingResult) -> Unit = {}, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsClosingSheets.swift
@Composable
fun ClientsRestoreSheet(target: ClientsRestoreTarget, onDone: (ClientsClosingResult) -> Unit = {}, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsClosingSheets.swift
@Composable
fun ClientsClosingBanner(name: String, stamp: ClientsClosingStamp?, deceasedAt: String?, hint: String?, onRestore: (() -> Unit)?, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsInviteSheets.swift
@Composable
fun ClientsInviteSheet(client: ClientsClient, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsInviteSheets.swift
@Composable
fun ClientsBulkInviteSheet(onInviteOne: (ClientsClient) -> Unit, modifier: Modifier = Modifier) {}
