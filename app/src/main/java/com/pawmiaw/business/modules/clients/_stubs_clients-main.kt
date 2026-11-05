package com.pawmiaw.business.clients

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pawmiaw.business.*
import java.time.Instant

//
//  Stand-ins for the Clients symbols the clients-chart job ports (ClientsText, ClientsChartExtras,
//  ClientsVault, InvitationsScreen), used by the client list and page. Deleted at merge.
//  `ClientsText` itself is already stood in by `_stubs_models_a.kt`, so its other words are
//  extensions here (`ClientsText.species(x)` reads the same as the member it becomes).
//

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.species(species: String?): String = species ?: "—"

// STUB: Modules/Clients/ClientsText.swift
val ClientsText.speciesOptions: List<Pair<String, String>> get() = emptyList()

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.sex(sex: String?): String? = sex

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.age(birthDate: String?): String? = null

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.kilogramsInput(grams: Double?): String = ""

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.profileLine(pet: ClientsPet): String = ""

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.dayTime(iso: String?): String = iso ?: "—"

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.relativeShortDay(iso: String?): String = iso ?: "—"

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.isPast(iso: String?): Boolean = false

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.phoneLooksValid(phone: String): Boolean = true

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.emailLooksValid(email: String): Boolean = true

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.followUpTitle(title: String?): String = title ?: ""

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.visitStatus(status: String?): String = status ?: "—"

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.invoiceStatus(status: String?): String = status ?: "—"

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.clientStatus(status: String?): String = status ?: ""

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.closingReason(reason: String?): String? = reason

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.typedNameMatches(typed: String, name: String): Boolean = typed == name

// STUB: Modules/Clients/ClientsText.swift
fun ClientsText.blocker(code: String, name: String): String = code

// STUB: Modules/Clients/ClientsText.swift
object ClientsMembers {
    suspend fun load(): List<ClientsMember> = emptyList()
}

// STUB: Modules/Clients/ClientsText.swift
fun clientsCopy(text: String?, done: String) {}

// STUB: Modules/Clients/ClientsChartExtras.swift
data class ClientsStay(val id: String)

// STUB: Modules/Clients/ClientsChartExtras.swift
data class ClientsStaySplit(val active: List<ClientsStay> = emptyList(), val past: List<ClientsStay> = emptyList())

// STUB: Modules/Clients/ClientsChartExtras.swift
@Composable
fun ClientsStaysSection(stays: ClientsStaySplit, modifier: Modifier = Modifier) {}

// STUB: Modules/Clients/ClientsChartExtras.swift
object ClientsStaysSection {
    suspend fun load(clientId: String): ClientsStaySplit = ClientsStaySplit()
}

// STUB: Modules/Clients/ClientsVault.swift
data class ClientsRevealedVault(val values: Map<String, String>, val revealedAt: Instant)

// STUB: Modules/Clients/ClientsVault.swift
data class ClientsVaultRevealTarget(val address: ClientsAddress) {
    val id: String get() = address.id
}

// STUB: Modules/Clients/ClientsVault.swift
@Composable
fun ClientsVaultStrip(
    clientId: String,
    address: ClientsAddress,
    home: ClientsHome,
    revealed: ClientsRevealedVault?,
    onReveal: () -> Unit,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {}

// STUB: Modules/Clients/ClientsVault.swift
@Composable
fun ClientsVaultRevealSheet(clientId: String, address: ClientsAddress, modifier: Modifier = Modifier, onRevealed: (ClientsRevealedVault) -> Unit) {}

// STUB: Modules/Clients/ClientsVault.swift
@Composable
fun ClientsVaultEditorSheet(clientId: String, address: ClientsAddress, modifier: Modifier = Modifier, onSaved: suspend () -> Unit) {}

// STUB: Modules/Clients/InvitationsScreen.swift
@Composable
fun ClientsReviewAcceptanceSheet(invitation: ClientsInvitation, canManage: Boolean, modifier: Modifier = Modifier, onDone: suspend () -> Unit) {}

// STUB: Modules/Clients/InvitationsScreen.swift
@Composable
fun ClientsReaddressSheet(invitation: ClientsInvitation, modifier: Modifier = Modifier, onDone: suspend () -> Unit) {}
