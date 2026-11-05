package com.pawmiaw.business.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max

//
//  ClientsVault.kt (iOS ClientsVault.swift)
//
//  An address's access details (web: addresses/vault-sheets.tsx and the
//  vault strip of client-home-section.tsx; PawMiawIos docs/client-
//  addresses.md). Only owners and admins write the business's and open them
//  outside a visit: the business's any time, a family's only as an emergency
//  with a reason the family is shown. Every opening is recorded; what opens
//  stays on screen five minutes at most, one field at a time for a minute.
//

object ClientsVaultWords {
    val fields = listOf("keys", "gateCode", "alarmCode", "wifiName", "wifiPassword", "parking", "houseRules", "hidingSpots", "notes")
    /** Single-line, monospaced and copyable. */
    val codes: Set<String> = setOf("gateCode", "alarmCode", "wifiName", "wifiPassword")
    /** Swift `TimeInterval` (seconds). */
    const val revealSeconds: Long = 5 * 60
    const val fieldSeconds = 60

    fun placeholder(field: String): String = when (field) {
        "keys" -> L("Where the keys are, a lockbox and its code, who has a copy")
        "gateCode" -> L("The building or gate code")
        "alarmCode" -> L("The code, and how to arm it")
        "wifiName" -> L("The network’s name")
        "wifiPassword" -> L("For sending photos and reports")
        "parking" -> L("Where to park, a visitor spot, the tower")
        "houseRules" -> L("Shoes off, rooms that stay closed, the neighbours")
        "hidingSpots" -> L("Under the bed, behind the washing machine…")
        else -> L("Whatever helps someone get in and find everything")
    }

    /** A refusal from the address routes, in the reader's words. */
    fun error(error: Throwable): String {
        val api = error as? APIError ?: return L("Something went wrong. Try again.")
        return when (api.code) {
            "vault_owner_only" -> L("Only the owners and admins open access details outside a visit. The person assigned sees them from the visit.")
            "vault_family_off" -> L("The family hasn’t shared its access details with your business.")
            "vault_emergency_reason" -> L("Outside a visit, say what happened before opening them. The family is shown it.")
            "vault_empty" -> L("No access details were written for this address.")
            "vault_unavailable" -> L("Access details aren’t available right now. Try again later.")
            "vault_unreadable" -> L("The access details couldn’t be read. Ask for them to be written again.")
            "vault_impersonation" -> L("Support sessions can’t open access details.")
            "address_family_owned" -> L("The family keeps this address; they change it in PawMiaw.")
            "address_archived" -> L("That address was removed. Choose another one.")
            "address_not_found" -> L("That address isn’t there anymore. Choose another one.")
            "client_not_found" -> L("That client isn’t here anymore.")
            "forbidden" -> L("You don’t have permission to do that.")
            else -> {
                if (api.status == 403) L("You don’t have permission to do that.")
                else api.message.nonEmpty ?: L("Something went wrong. Try again.")
            }
        }
    }
}

/** What an opening returns: the filled fields, and when it opened. */
data class ClientsRevealedVault(
    val values: Map<String, String>,
    val revealedAt: Instant,
)

@Serializable
private data class ClientsRevealAnswer(
    val vault: Map<String, String?>? = null,
    val revealedAt: String? = null,
)

object ClientsVaultAPI {
    suspend fun reveal(clientId: String, addressId: String, emergencyReason: String?): ClientsRevealedVault {
        val body = mutableMapOf<String, Any?>()
        if (emergencyReason != null) body["emergencyReason"] = emergencyReason
        val answer: ClientsRevealAnswer = API.post("/provider-clients/$clientId/addresses/$addressId/access/reveal", body = body)
        val values = mutableMapOf<String, String>()
        for ((key, value) in answer.vault ?: emptyMap()) {
            val text = value.nonEmpty
            if (text != null) values[key] = text
        }
        return ClientsRevealedVault(values = values, revealedAt = DateText.parse(answer.revealedAt) ?: Instant.now())
    }
}

// MARK: - The strip under an address

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
) {
    val fields = ClientsVaultWords.fields.filter { (address.vault?.fields ?: emptyList()).contains(it) }
    val hasVault = address.vault?.hasVault == true || fields.isNotEmpty()
    val family = address.source == "family"
    val canManage = home.canManageVault == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fields.isEmpty()) {
            Text(
                if (address.isBusiness) L("No access details yet") else L("The family hasn’t written access details"),
                style = Theme.text(12), color = Theme.inkSoft,
            )
        } else {
            val label = L("Access details written")
            FlowLayout(spacing = 6.dp, modifier = Modifier.semantics { contentDescription = label }) {
                fields.forEach { Chip(text = ClientsAddressWords.vaultField(it), tone = Chip.Tone.muted, icon = "lock") }
            }
        }
        if (canManage && (address.isBusiness || hasVault) && revealed == null) {
            FlowLayout(spacing = 8.dp) {
                if (address.isBusiness) {
                    PMButton(
                        if (hasVault) L("Edit access details") else L("Add access details"),
                        systemImage = "key-round", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onEdit,
                    )
                }
                if (hasVault && (address.isBusiness || home.familyVaultShared == true)) {
                    if (family) {
                        PMButton(L("Emergency opening"), systemImage = "triangle-alert", variant = ButtonVariant.destructive, size = ButtonSize.compact, action = onReveal)
                    } else {
                        PMButton(L("Show access details"), systemImage = "eye", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onReveal)
                    }
                }
            }
        }
        if (family && hasVault) {
            Text(
                if (home.familyVaultShared == false) L("The family hasn’t shared its access details with your business.")
                else L("Whoever does a home visit sees them from the visit, 30 minutes before it starts."),
                style = Theme.text(12), color = Theme.inkSoft,
            )
        }
        if (revealed != null) {
            ClientsRevealedVaultView(vault = revealed, family = family, linked = home.linked == true, onHide = onHide)
        }
    }
}

/** The opened details: each value hidden until its eye is pressed, for a minute; all of it hides after five. */
@Composable
fun ClientsRevealedVaultView(
    vault: ClientsRevealedVault,
    family: Boolean,
    linked: Boolean,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shownField by remember { mutableStateOf<String?>(null) }
    var shownAt by remember { mutableStateOf(Instant.now()) }
    var copied by remember { mutableStateOf<String?>(null) }
    // TimelineView(.periodic(by: 1)): the clock every second.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = Instant.now()
        }
    }
    val hideAt = vault.revealedAt.plusSeconds(ClientsVaultWords.revealSeconds)
    val over = !now.isBefore(hideAt)
    LaunchedEffect(over) { if (over) onHide() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(Theme.boxRadius)

    @Composable
    fun row(field: String) {
        val value = vault.values[field] ?: ""
        val left = ClientsVaultWords.fieldSeconds - Duration.between(shownAt, now).seconds.toInt()
        val visible = shownField == field && left > 0
        val label = ClientsAddressWords.vaultField(field)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (visible) {
                        SelectionContainer {
                            Text(
                                value,
                                style = if (ClientsVaultWords.codes.contains(field)) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp) else Theme.text(15),
                                color = Theme.inkStrong,
                            )
                        }
                    } else {
                        val hidden = L("Hidden")
                        Text("••••", style = Theme.text(15, FontWeight.Bold), color = Theme.inkSoft, modifier = Modifier.semantics { contentDescription = hidden })
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (visible && ClientsVaultWords.codes.contains(field)) {
                    Box(
                        Modifier
                            .heightIn(min = 36.dp)
                            .plainButton(label = LF("Copy %@", label)) {
                                clipboard.setText(AnnotatedString(value))
                                copied = field
                                scope.launch {
                                    delay(1500)
                                    if (copied == field) copied = null
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (copied == field) L("Copied") else L("Copy"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
                    }
                }
                IconCircleButton(
                    systemImage = if (visible) "eye-off" else "eye",
                    label = if (visible) LF("Hide %@", label) else LF("Show %@", label),
                ) {
                    if (visible) shownField = null else {
                        shownField = field
                        shownAt = Instant.now()
                    }
                }
            }
            if (visible) Text(LF("Hides in %d s", left), style = Theme.text(11), color = Theme.inkSoft)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.blue, 6.0), shape)
            .border(2.dp, Theme.mix(Theme.blue, 45.0, Theme.borderSoft), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Row(
                Modifier.heightIn(min = 36.dp).weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("key-round", 16.dp, tint = Theme.blue)
                Text(L("Access details"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            }
            PMButton(L("Hide"), systemImage = "eye-off", size = ButtonSize.header, action = onHide)
        }
        val filled = ClientsVaultWords.fields.filter { vault.values[it] != null }
        if (filled.isEmpty()) {
            Text(L("No access details were written for this address."), style = Theme.text(14), color = Theme.inkSoft)
        }
        filled.forEach { row(it) }
        val minutes = max(1, ceil(Duration.between(now, hideAt).seconds / 60.0).toInt())
        Text(LF("Hides by itself in %1\$d min (at %2\$@).", minutes, DateText.time(hideAt)), style = Theme.text(12), color = Theme.inkSoft)
        Text(
            if (family && linked) L("Opening these is recorded and the family is told each time.") else L("Opening these is recorded."),
            style = Theme.text(12), color = Theme.inkSoft,
        )
    }
}

// MARK: - Opening them

data class ClientsVaultRevealTarget(val address: ClientsAddress) {
    val id: String get() = address.id
}

@Composable
fun ClientsVaultRevealSheet(
    clientId: String,
    address: ClientsAddress,
    onRevealed: (ClientsRevealedVault) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val reason = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val emergency = address.source == "family"

    fun open() {
        val trimmed = reason.value.trim()
        if (emergency) {
            if (!form.check(listOf("reason" to (if (trimmed.length < 10) L("Say what happened, in a sentence.") else null)))) return
        }
        working = true
        scope.launch {
            try {
                val vault = ClientsVaultAPI.reveal(clientId, address.id, if (emergency) trimmed else null)
                onRevealed(vault)
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? APIError)?.code == "vault_emergency_reason") {
                    form.check(listOf("reason" to ClientsVaultWords.error(e)))
                } else {
                    Toast.error(ClientsVaultWords.error(e))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (emergency) L("Emergency opening") else L("Show access details?"),
        subtitle = address.name,
        systemImage = if (emergency) "triangle-alert" else "key-round",
        accent = if (emergency) ModalAccent.coral else ModalAccent.aqua,
        isDirty = reason.value.isNotEmpty(), form = form,
        primaryTitle = if (emergency) L("Open in an emergency") else L("Show them"),
        primaryVariant = if (emergency) ButtonVariant.destructive else ButtonVariant.primary,
        isWorking = working,
        primaryIcon = "eye",
        onPrimary = { open() },
        modifier = modifier.sheetPresentation(
            if (emergency) setOf(PresentationDetent.medium, PresentationDetent.large) else setOf(PresentationDetent.medium),
        ),
    ) {
        if (emergency) {
            NoticeCard(
                tone = NoticeCard.Tone.problem,
                title = L("There is no visit now. Open them only if something is wrong, and say why: the family is told right away, with your words."),
                message = L("They stay on screen for 5 minutes at most, then hide again."),
            )
            PMTextField(L("What happened?"), text = reason, key = "reason", form = form, prompt = L("Luna isn’t answering and we are on our way"), multiline = true)
        } else {
            NoticeCard(
                tone = NoticeCard.Tone.info,
                title = L("Opening these is recorded."),
                message = L("They stay on screen for 5 minutes at most, then hide again."),
                icon = "shield-check",
            )
        }
    }
}

// MARK: - Writing them (the business's own addresses)

@Composable
fun ClientsVaultEditorSheet(
    clientId: String,
    address: ClientsAddress,
    onSaved: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var original by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var needsOpening by remember {
        mutableStateOf(address.vault?.hasVault == true || (address.vault?.fields ?: emptyList()).isNotEmpty())
    }
    var opening by remember { mutableStateOf(false) }
    var openProblem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Editing what is written starts with an opening (recorded as the owner's or admin's). */
    fun openFirst() {
        opening = true
        openProblem = null
        scope.launch {
            try {
                val vault = ClientsVaultAPI.reveal(clientId, address.id, null)
                values = vault.values
                original = vault.values
                needsOpening = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                openProblem = ClientsVaultWords.error(e)
            } finally {
                opening = false
            }
        }
    }

    fun save() {
        val rules = ClientsVaultWords.fields.map { field ->
            field to (if ((values[field] ?: "").length > 1000) LF("Up to %d characters.", 1000) else null)
        }
        if (!form.check(rules)) return
        saving = true
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>()
                for (field in ClientsVaultWords.fields) body[field] = values[field]?.trim().nonEmpty
                API.put<EmptyResponse>("/provider-clients/$clientId/addresses/${address.id}/access", body = body)
                Toast.show(L("Access details saved."))
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!form.apply(e, known = ClientsVaultWords.fields.associateWith { it })) {
                    Toast.error(ClientsVaultWords.error(e))
                }
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("Access details"), subtitle = address.name, systemImage = "lock-keyhole", accent = ModalAccent.aqua,
        isDirty = values != original, form = form,
        primaryTitle = if (needsOpening) L("Open them to edit") else L("Save access details"),
        isWorking = saving || opening,
        primaryIcon = if (needsOpening) "eye" else "save",
        onPrimary = { if (needsOpening) openFirst() else save() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        NoticeCard(
            tone = NoticeCard.Tone.info,
            title = L("Only the person doing a home visit sees these, from 30 minutes before until it ends. Owners and admins can open them too."),
            message = L("They are stored encrypted, and every opening is recorded."),
            icon = "shield-check",
        )
        if (needsOpening) {
            val problem = openProblem
            if (problem != null) {
                NoticeCard(tone = NoticeCard.Tone.problem, title = problem, actionTitle = L("Try again")) { openFirst() }
            } else {
                NoticeCard(tone = NoticeCard.Tone.info, title = L("To change them, open them first."), message = L("Opening these is recorded."))
            }
        } else {
            ClientsVaultWords.fields.forEach { field ->
                PMTextField(
                    ClientsAddressWords.vaultField(field),
                    text = stateOf({ values[field] ?: "" }, { values = values + (field to it); form.clear(field) }),
                    key = field, form = form, prompt = ClientsVaultWords.placeholder(field),
                    capitalization = if (ClientsVaultWords.codes.contains(field)) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                    multiline = !ClientsVaultWords.codes.contains(field),
                )
            }
        }
    }
}
