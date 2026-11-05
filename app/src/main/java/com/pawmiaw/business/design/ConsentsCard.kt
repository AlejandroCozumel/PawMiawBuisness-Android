package com.pawmiaw.business

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

//
//  ConsentsCard.kt (iOS ConsentsCard.swift, first half; the signing, view · print, send and
//  evidence sheets are in ConsentsCard2.kt)
//
//  The consents of an act — a visit, a stay or a home stay (web
//  consents/consents-card.tsx, consent-request-sheet.tsx, consent-sign-sheet.tsx,
//  consent-document.tsx, esign/send-for-signature-sheet.tsx,
//  esign/consent-evidence-sheet.tsx; backend docs/consents-incidents.md). One
//  set for every place that shows consents:
//  - `ConsentsCard`: what must be signed before it starts and whether it is,
//    every consent with where it stands, and its moves — ask for one, enter the
//    amount it names, sign it here, send it for signature, view and print it,
//    its e-signature evidence, withdraw and revoke. Buttons follow the list's
//    `canManage`; a role that can't read consents doesn't see the card.
//  - `ConsentSignSheet`: signing on this device with the client (the e-sign
//    terms, the text, "I have read and agree", the full name and a drawn or
//    typed signature). The hospital's estimate and euthanasia consent use it.
//  - The request, amount, view · print, send (the family's app, a link with an
//    emailed code and WhatsApp, or this device) and evidence sheets.
//  Used by the visit page and the start gate (Schedule), the stay, its check-in
//  and the home stay with its arrival gate (Stays), and the hospital (Hospital).
//

// MARK: - What the consents belong to

/** The act a consent belongs to: one of a visit, a stay, a record or a pet (the web's ConsentAct). */
data class ConsentAct(
    val appointmentId: String? = null,
    val stayId: String? = null,
    val recordId: String? = null,
    val providerClientPetId: String? = null,
) {
    /** The one key the server reads, in its order. */
    private val pair: Pair<String, String>?
        get() = when {
            appointmentId != null -> "appointmentId" to appointmentId
            stayId != null -> "stayId" to stayId
            recordId != null -> "recordId" to recordId
            providerClientPetId != null -> "providerClientPetId" to providerClientPetId
            else -> null
        }

    val isEmpty: Boolean get() = pair == null
    val query: Map<String, String?> get() = pair?.let { mapOf(it.first to it.second) } ?: emptyMap()
    val body: Map<String, Any?> get() = pair?.let { mapOf(it.first to it.second) } ?: emptyMap()
    /** As multipart fields (an incident's). */
    val fields: List<Pair<String, String>> get() = listOfNotNull(pair)
}

/** A pet of a visit or stay as consents and incidents name it (its client-pet id). */
data class ActPet(val id: String, val name: String)

/** What the Consents card knows about the act it sits on. */
data class ConsentContext(
    val act: ConsentAct,
    val businessName: String? = null,
    val clientName: String,
    /** The family is on PawMiaw, so a consent can be sent to their app. */
    val familyOnApp: Boolean,
    val clientEmail: String? = null,
    val clientPhone: String? = null,
    val pets: List<ActPet> = emptyList(),
    /** "Boarding · 26 sep – 28 sep", printed on the consent. */
    val actLabel: String? = null,
    /** The visit started or the stay checked in: a per-visit consent can't be revoked any more. */
    val started: Boolean = false,
    val currency: String? = null,
)

// MARK: - Models (use-consents.ts, esign.ts)

@Serializable
data class ConsentEsignTerms(
    val version: String? = null,
    val language: String? = null,
    val title: String? = null,
    val text: String? = null,
    val reviewNote: String? = null,
)

/** How a document was signed: drawn, typed or uploaded. */
@Serializable
data class ConsentSignatureMark(
    val kind: String? = null,
    val text: String? = null,
    val font: String? = null,
    val imageDataUrl: String? = null,
)

@Serializable
data class ConsentIntegrity(
    val snapshotSha256: String? = null,
    val signatureSha256: String? = null,
    val verified: Boolean? = null,
)

@Serializable
data class ConsentEvidenceSeal(val status: String? = null, val sealedAt: String? = null, val sha256: String? = null)

@Serializable
data class ConsentEvidenceStamp(val status: String? = null, val at: String? = null)

@Serializable
data class ConsentEvidenceSummary(
    val requestId: String? = null,
    val status: String? = null,
    val verifyCode: String? = null,
    val verifyUrl: String? = null,
    val method: String? = null,
    val signedAt: String? = null,
    val seal: ConsentEvidenceSeal? = null,
    val timestamp: ConsentEvidenceStamp? = null,
    val revokedAt: String? = null,
)

/** A consent on a visit, stay, record or pet; the detail adds the signature, the terms and the evidence. */
@Serializable
data class Consent(
    val id: String,
    val templateId: String? = null,
    val validity: String? = null,
    val validDays: Int? = null,
    val validUntil: String? = null,
    val providerClientPetId: String? = null,
    val petName: String? = null,
    val origin: String? = null,
    val status: String,
    val language: String? = null,
    val title: String,
    val body: String? = null,
    val needsAmount: Boolean? = null,
    val amountCents: Int? = null,
    val currency: String? = null,
    val requestedAt: String? = null,
    val requestedByName: String? = null,
    val sentAt: String? = null,
    val signedAt: String? = null,
    val signedVia: String? = null,
    val signerName: String? = null,
    val signerAccountName: String? = null,
    val witnessName: String? = null,
    val declinedAt: String? = null,
    val declineReason: String? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val revokedAt: String? = null,
    val revokedBySide: String? = null,
    val revokeReason: String? = null,
    val signatureDataUrl: String? = null,
    val mark: ConsentSignatureMark? = null,
    val terms: ConsentEsignTerms? = null,
    val integrity: ConsentIntegrity? = null,
    val evidence: ConsentEvidenceSummary? = null,
    /** After asking or sending: the family was told. */
    val notified: Boolean? = null,
) {
    val amountText: String? get() = if ((amountCents ?: 0) > 0) Money.format(amountCents, currency) else null
}

@Serializable
data class ConsentLine(
    val templateId: String? = null,
    val title: String? = null,
    val validity: String? = null,
    val satisfied: Boolean? = false,
    val consentId: String? = null,
    val status: String? = null,
)

@Serializable
data class ConsentRequirement(val lines: List<ConsentLine>? = null, val satisfied: Boolean? = null)

@Serializable
data class ConsentList(
    val items: List<Consent>? = null,
    val requirement: ConsentRequirement? = null,
    val canManage: Boolean? = null,
    val canOverride: Boolean? = null,
)

@Serializable
data class ConsentTemplate(
    val id: String,
    val title: String,
    val language: String? = null,
    val validity: String? = null,
    val validDays: Int? = null,
    val needsAmount: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class ConsentEvidenceSigner(
    val id: String,
    val role: String? = null,
    val status: String? = null,
    val name: String? = null,
    val maskedEmail: String? = null,
    val method: String? = null,
    val witnessName: String? = null,
    val ip: String? = null,
    val device: String? = null,
    val termsVersion: String? = null,
    val termsAcceptedAt: String? = null,
    val emailVerifiedAt: String? = null,
    val signedAt: String? = null,
    val declinedAt: String? = null,
    val declineReason: String? = null,
    val mark: ConsentSignatureMark? = null,
)

@Serializable
data class ConsentEvidenceEvent(
    val sequence: Int? = null,
    val type: String? = null,
    val at: String? = null,
    val actorType: String? = null,
    val actorName: String? = null,
    val ip: String? = null,
    val hash: String? = null,
)

@Serializable
data class ConsentSigningLink(
    val id: String,
    val maskedEmail: String? = null,
    val phone: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val status: String? = null,
    val openedAt: String? = null,
    val verifiedAt: String? = null,
    val createdByName: String? = null,
)

@Serializable
data class ConsentEvidence(
    val summary: ConsentEvidenceSummary? = null,
    val documentSha256: String? = null,
    val finalEvidenceSha256: String? = null,
    val termsVersion: String? = null,
    val signers: List<ConsentEvidenceSigner>? = null,
    val events: List<ConsentEvidenceEvent>? = null,
    val links: List<ConsentSigningLink>? = null,
    val canSendLink: Boolean? = null,
)

@Serializable
data class ConsentVerifyResult(
    val ok: Boolean? = null,
    val checkedAt: String? = null,
    val checks: Map<String, Boolean?>? = null,
    val errors: List<String>? = null,
)

@Serializable
data class ConsentCreatedLink(
    val link: ConsentSigningLink? = null,
    val url: String? = null,
    val whatsappUrl: String? = null,
    val emailSent: Boolean? = null,
)

// MARK: - Words (consents-copy.ts)

object ConsentWords {
    /** "26 sep 2026, 10:30" on the business's clock. */
    fun date(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        return DateText.format(date, "dMMMyyyyjmm")
    }

    fun status(status: String): String = when (status) {
        "draft" -> L("Needs an amount")
        "pending" -> L("Waiting for signature")
        "signed" -> L("Signed")
        "declined" -> L("Declined")
        "cancelled" -> L("Withdrawn")
        "revoked" -> L("Revoked")
        else -> status
    }

    fun state(status: String): ProviderState = when (status) {
        "draft", "pending" -> ProviderState.waiting
        "signed" -> ProviderState.done
        "declined", "revoked" -> ProviderState.problem
        else -> ProviderState.closed
    }

    /** "Each visit or stay", "Once per pet · 365 days", "Once per pet · until revoked". */
    fun validity(validity: String?, days: Int?): String {
        if (validity != "standing") return L("Each visit or stay")
        if (days == null || days <= 0) return L("Once per pet · until revoked")
        return if (days == 1) L("Once per pet · 1 day") else LF("Once per pet · %d days", days)
    }

    fun via(via: String?): String = when (via) {
        "family_app" -> L("Signed in the PawMiaw app")
        "clinic_device" -> L("Signed at the business, on its device")
        "email_link" -> L("Signed through a link, with a code sent by email")
        else -> "—"
    }

    private fun reasonSuffix(reason: String?): String = reason.nonEmpty?.let { " — $it" } ?: ""

    private fun side(side: String?): String = if (side == "family") L("the family") else L("the business")

    /** One line about where a consent stands: who asked, who signed and how, why it closed. */
    fun line(consent: Consent): String = when (consent.status) {
        "draft" -> L("Waiting for the amount before it can be signed.")
        "pending" -> {
            if (consent.sentAt != null) LF("Sent %@", date(consent.sentAt))
            else {
                val by = consent.requestedByName.nonEmpty?.let { " · $it" } ?: (if (consent.origin == "auto") " · " + L("required by the service") else "")
                LF("Asked %@%@", date(consent.requestedAt), by)
            }
        }
        "signed" -> {
            val name = consent.signerName.nonEmpty ?: "—"
            when (consent.signedVia) {
                "clinic_device" -> LF("Signed by %@ here · %@", name, date(consent.signedAt))
                "email_link" -> LF("Signed by %@ through a link · %@", name, date(consent.signedAt))
                else -> LF("Signed by %@ in the app · %@", name, date(consent.signedAt))
            }
        }
        "declined" -> LF("Declined %@%@", date(consent.declinedAt), reasonSuffix(consent.declineReason))
        "cancelled" -> LF("Withdrawn %@%@", date(consent.cancelledAt), reasonSuffix(consent.cancelReason))
        "revoked" -> LF("Revoked by %@ %@%@", side(consent.revokedBySide), date(consent.revokedAt), reasonSuffix(consent.revokeReason))
        else -> ""
    }

    /** The closing note printed on a consent that no longer counts. */
    fun closedNote(consent: Consent): String? = when (consent.status) {
        "revoked" -> LF("Revoked on %@ by %@%@", date(consent.revokedAt), side(consent.revokedBySide), reasonSuffix(consent.revokeReason))
        "declined" -> LF("Declined on %@%@", date(consent.declinedAt), reasonSuffix(consent.declineReason))
        "cancelled" -> LF("Withdrawn on %@%@", date(consent.cancelledAt), reasonSuffix(consent.cancelReason))
        else -> null
    }

    /** The server's refusals in words (CONSENT_ERROR_MESSAGES). [fallback] is an English key. */
    fun error(error: Throwable, fallback: String = "Something went wrong. Try again."): String {
        val api = error as? APIError
        if (api != null) {
            if (api.isOffline) return api.message
            api.code?.let { code -> known(code)?.let { return it } }
            if (api.status == 403) return L("You don’t have permission to do this.")
            if (api.status == 404) return L("It wasn’t found. It may have been removed.")
            if (api.message.isNotEmpty() && (api.isRefusal || LocalizationManager.currentLanguage != AppLanguage.spanish)) return api.message
        }
        return L(fallback)
    }

    private fun known(code: String): String? = when (code) {
        "amount_required" -> L("This consent names an amount — enter it.")
        "pet_required" -> L("A consent for each pet — choose which one.")
        "pet_not_in_act" -> L("That pet isn’t part of this visit or stay.")
        "act_closed" -> L("This visit or stay is over — ask for the consent from the pet’s chart instead.")
        "act_not_found" -> L("The visit or stay wasn’t found.")
        "not_pending" -> L("This consent is no longer waiting for a signature. Reload to see it.")
        "already_signed" -> L("This consent was already signed.")
        "agreement_required" -> L("Tick that you read and agree.")
        "signer_name_required" -> L("Type the full name of who signs.")
        "signature_missing", "signature_blank" -> L("Draw the signature.")
        "signature_invalid" -> L("The signature couldn’t be read. Clear it and draw it again.")
        "signature_too_large" -> L("The signature image is too large. Clear it and draw it again.")
        "act_started" -> L("It already started — a consent can only be revoked before the visit or stay begins.")
        "not_signed" -> L("Only a signed consent can be revoked.")
        "stale" -> L("Someone else changed this consent. Reload to see it.")
        "template_missing" -> L("The template of this consent is gone — ask for a new one.")
        "cannot_sign" -> L("An owner or a household admin signs consents.")
        "forbidden" -> L("You don’t have permission to do this.")
        else -> null
    }

    /** A refusal carrying these words (and the server's fields), for a ConfirmSheet or `FormState.apply`. */
    fun refusal(error: Throwable, fallback: String): APIError {
        val api = error as? APIError
        return APIError(api?.status ?: 400, error(error, fallback), api?.code, api?.fields ?: emptyMap(), api?.body)
    }

    /** Waiting first (drafts, then pending), then signed, then the closed ones; newest first inside each. */
    fun sorted(items: List<Consent>): List<Consent> {
        val order = mapOf("draft" to 0, "pending" to 1, "signed" to 2, "declined" to 3, "revoked" to 4, "cancelled" to 5)
        return items.sortedWith { a, b ->
            val left = order[a.status] ?: 9
            val right = order[b.status] ?: 9
            if (left != right) left.compareTo(right) else (b.requestedAt ?: "").compareTo(a.requestedAt ?: "")
        }
    }
}

// MARK: - Words for the e-signature (esign-copy.ts)

object ConsentEsignWords {
    fun method(method: String?): String = when (method) {
        "app_session" -> L("In the PawMiaw app, signed in")
        "email_code" -> L("By link, with a code sent by email")
        "in_person" -> L("In person, on the business’s device")
        else -> method ?: "—"
    }

    fun envelope(status: String?): String = when (status) {
        "pending" -> L("Waiting for signature")
        "completed" -> L("Signed")
        "declined" -> L("Declined")
        "voided" -> L("Voided")
        "expired" -> L("Expired")
        else -> status ?: "—"
    }

    fun seal(status: String?): String = when (status) {
        "pending" -> L("Sealing…")
        "sealed" -> L("Sealed")
        "failed" -> L("Sealing failed — it is retried")
        else -> L("Not sealed yet")
    }

    fun signer(status: String?): String = when (status) {
        "signed" -> L("Signed")
        "declined" -> L("Declined")
        else -> L("Waiting")
    }

    fun link(status: String?): String = when (status) {
        "active" -> L("Active")
        "used" -> L("Used")
        "expired" -> L("Expired")
        "revoked" -> L("Stopped")
        else -> status ?: "—"
    }

    fun event(type: String?): String = when (type) {
        "created" -> L("Signature request created")
        "sent" -> L("Sent to the family’s app")
        "link_created" -> L("Signing link made")
        "link_revoked" -> L("Signing link stopped")
        "opened" -> L("Link opened")
        "code_sent" -> L("Code sent by email")
        "code_failed" -> L("Wrong code entered")
        "code_locked" -> L("Too many wrong codes: locked")
        "code_verified" -> L("Email confirmed with the code")
        "viewed" -> L("Document viewed")
        "terms_accepted" -> L("Accepted the terms to sign electronically")
        "signed" -> L("Signed")
        "declined" -> L("Declined")
        "completed" -> L("Every signature in: completed")
        "voided" -> L("Request voided")
        "revoked" -> L("Consent revoked")
        "expired" -> L("Expired")
        "sealed" -> L("PDF sealed")
        "timestamped" -> L("Timestamp added")
        "copy_emailed" -> L("Signed copy emailed")
        "verified" -> L("Evidence verified")
        "signed_before_evidence_v2" -> L("Signed before the evidence trail (carried over)")
        else -> type ?: "—"
    }

    fun actor(type: String?, name: String?): String {
        val role = when (type) {
            "staff" -> L("Staff")
            "signer" -> L("Signer")
            "system" -> "PawMiaw"
            "public" -> L("Visitor")
            else -> type ?: ""
        }
        return name.nonEmpty?.let { "$it ($role)" } ?: role
    }

    fun check(key: String): String = when (key) {
        "chain" -> L("Every event is intact and in order")
        "signers" -> L("Each signature’s evidence matches")
        "final" -> L("The final evidence matches")
        "terms" -> L("The signing terms accepted are intact")
        "document" -> L("The document still matches what was signed")
        "signatureImages" -> L("The signature images are unchanged")
        "sealedFile" -> L("The sealed PDF is unchanged")
        "timestampedFile" -> L("The timestamped PDF is unchanged")
        else -> key
    }

    val checkOrder = listOf("chain", "signers", "final", "terms", "document", "signatureImages", "sealedFile", "timestampedFile")

    /** A hash as people can read it aloud: groups of four. */
    fun grouped(hash: String?): String {
        if (hash.isNullOrEmpty()) return "—"
        val out = StringBuilder()
        hash.forEachIndexed { index, character ->
            if (index > 0 && index % 4 == 0) out.append(' ')
            out.append(character)
        }
        return out.toString()
    }
}

// MARK: - The card

private sealed class ConsentSheet {
    data object request : ConsentSheet()
    data class amount(val consent: Consent) : ConsentSheet()
    data class sign(val consentId: String) : ConsentSheet()
    data class view(val consentId: String) : ConsentSheet()
    data class send(val consent: Consent, val choice: String?) : ConsentSheet()
    data class evidence(val consent: Consent) : ConsentSheet()
    data class cancel(val consent: Consent) : ConsentSheet()
    data class revoke(val consent: Consent) : ConsentSheet()

    val id: String
        get() = when (this) {
            request -> "request"
            is amount -> "amount-${consent.id}"
            is sign -> "sign-$consentId"
            is view -> "view-$consentId"
            is send -> "send-${consent.id}-${choice ?: ""}"
            is evidence -> "evidence-${consent.id}"
            is cancel -> "cancel-${consent.id}"
            is revoke -> "revoke-${consent.id}"
        }
}

/** The consents of a visit, stay or home stay, with their moves. */
@Composable
fun ConsentsCard(
    context: ConsentContext,
    /** Inside a page section or another sheet: no card of its own ("Ask for a consent" on top). */
    bare: Boolean = false,
    /** The toast once one is signed here ("Signed. It is kept with the visit or stay."). */
    signedMessage: String = L("Signed. It is kept with the visit or stay."),
    modifier: Modifier = Modifier,
    /** Every load, so a gate can see whether everything is signed now. */
    onLoaded: (ConsentList) -> Unit = {},
) {
    var list by remember { mutableStateOf<ConsentList?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var forbidden by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ConsentSheet?>(null) }
    /** The sheet that opens once the current one has closed (ask → sign here). */
    var next by remember { mutableStateOf<ConsentSheet?>(null) }
    val scope = rememberCoroutineScope()
    val currentContext by rememberUpdatedState(context)
    val currentOnLoaded by rememberUpdatedState(onLoaded)

    val items = ConsentWords.sorted(list?.items ?: emptyList())
    val lines = list?.requirement?.lines ?: emptyList()
    val canManage = list?.canManage == true
    val missing = lines.count { it.satisfied != true }

    suspend fun load() {
        val act = currentContext.act
        if (act.isEmpty) return
        try {
            val loaded: ConsentList = API.get("/provider-consents", act.query)
            list = loaded
            failed = null
            currentOnLoaded(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Someone whose role doesn't see consents doesn't see the card.
            if ((e as? APIError)?.status == 403) { forbidden = true; return }
            failed = ConsentWords.error(e, "The consents could not be loaded.")
        }
    }

    if (!forbidden) ReloadsOnScope { load() }

    val content: @Composable () -> Unit = {
        val problem = failed
        if (list == null && problem == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) { ConsentSpinner() }
        } else if (problem != null && list == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(problem, style = Theme.smallFont, color = Theme.inkSoft, modifier = Modifier.weight(1f, fill = false))
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { load() } }
            }
        } else {
            if (lines.isNotEmpty()) ConsentRequirementBox(lines, missing)
            if (items.isEmpty()) {
                Text(L("No consents on this one. Ask for one when the service needs the family’s signature."), style = Theme.smallFont, color = Theme.inkSoft)
            } else {
                for (consent in items) {
                    key(consent.id) {
                        ConsentRow(consent, context, canManage) { sheet = it }
                    }
                }
            }
        }
    }

    when {
        forbidden -> {}
        bare -> Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canManage) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    PMButton(L("Ask for a consent"), systemImage = "doc.badge.plus", variant = ButtonVariant.outline, size = ButtonSize.compact) { sheet = ConsentSheet.request }
                }
            }
            content()
        }
        else -> Card(modifier = modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("signature", 18.dp, tint = Theme.inkStrong)
                    Text(L("Consents"), style = Theme.sectionFont, color = Theme.inkStrong)
                }
                if (missing > 0) StatusChip(text = if (missing == 1) L("1 missing") else LF("%d missing", missing), state = ProviderState.waiting)
            }
            content()
            if (canManage) {
                PMButton(L("Ask for a consent"), systemImage = "doc.badge.plus", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true) { sheet = ConsentSheet.request }
            }
        }
    }

    // MARK: Sheets
    sheet?.let { which ->
        key(which.id) {
            AppSheet(onDismissRequest = {
                sheet = null
                next?.let { upcoming ->
                    next = null
                    sheet = upcoming
                }
            }) {
                when (which) {
                    ConsentSheet.request -> ConsentRequestSheet(context = context) { consent, here ->
                        if (here && consent.status == "pending") next = ConsentSheet.sign(consent.id)
                    }
                    is ConsentSheet.amount -> ConsentAmountSheet(consent = which.consent, familyOnApp = context.familyOnApp) { updated, here ->
                        if (here) next = ConsentSheet.sign(updated.id)
                    }
                    is ConsentSheet.sign -> ConsentSignSheet(consentId = which.consentId, businessName = context.businessName, successMessage = signedMessage) {
                        scope.launch { load() }
                    }
                    is ConsentSheet.view -> ConsentViewSheet(consentId = which.consentId, context = context)
                    is ConsentSheet.send -> ConsentSendSheet(consent = which.consent, context = context, initialChoice = which.choice) {
                        next = ConsentSheet.sign(which.consent.id)
                    }
                    is ConsentSheet.evidence -> ConsentEvidenceSheet(consent = which.consent, canManage = canManage) {
                        next = ConsentSheet.send(which.consent, choice = "link")
                    }
                    is ConsentSheet.cancel -> {
                        val consent = which.consent
                        ConfirmSheet(
                            title = L("Withdraw this request?"),
                            message = LF("“%@” stops waiting for a signature. The family no longer sees it to sign.", consent.title) + " " + L("Any signing link sent for it stops working."),
                            confirmTitle = L("Withdraw request"),
                            destructive = true,
                            askReason = true,
                            reasonRequired = false,
                            reasonLabel = L("Reason (optional)"),
                        ) { reason ->
                            try {
                                API.post<Consent>("/provider-consents/${consent.id}/cancel", mapOf("reason" to reason))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                throw ConsentWords.refusal(e, "Could not withdraw it. Try again.")
                            }
                            Toast.show(L("Request withdrawn."))
                            announceDataChanged()
                        }
                    }
                    is ConsentSheet.revoke -> {
                        val consent = which.consent
                        ConfirmSheet(
                            title = L("Revoke this consent?"),
                            message = LF("“%@” stops counting from now on. What was signed stays on record, marked as revoked.", consent.title),
                            confirmTitle = L("Revoke consent"),
                            destructive = true,
                            askReason = true,
                            reasonRequired = true,
                            reasonLabel = L("e.g. The client changed their mind at the desk"),
                        ) { reason ->
                            try {
                                API.post<Consent>("/provider-consents/${consent.id}/revoke", mapOf("reason" to (reason ?: "")))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                throw ConsentWords.refusal(e, "Could not revoke it. Try again.")
                            }
                            Toast.show(L("Consent revoked."))
                            announceDataChanged()
                        }
                    }
                }
            }
        }
    }
}

/** "Required before it starts": each line the service needs, signed or missing. */
@Composable
private fun ConsentRequirementBox(lines: List<ConsentLine>, missing: Int) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background((if (missing > 0) Theme.mango else ProviderState.done.color).copy(alpha = 0.12f), shape)
            .border(1.dp, (if (missing > 0) Theme.mango else Theme.borderSoft).copy(alpha = 0.6f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(L("Required before it starts").uppercase(), style = brandText(11, FontWeight.ExtraBold).copy(letterSpacing = 1.sp), color = Theme.inkSoft)
        for (line in lines) {
            val signed = line.satisfied == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (signed) "checkmark.seal.fill" else "exclamationmark.circle.fill", 13.dp, tint = if (signed) ProviderState.done.color else Theme.goldText)
                Text(line.title ?: "", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(if (signed) L("Signed") else L("Missing"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            }
        }
    }
}

/** A consent: its title, where it stands, and its moves. */
@Composable
private fun ConsentRow(consent: Consent, context: ConsentContext, canManage: Boolean, open: (ConsentSheet) -> Unit) {
    val revocable = consent.status == "signed" && (!context.started || consent.validity == "standing")
    val cancellable = consent.status == "draft" || consent.status == "pending"
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(18.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
                Glyph("signature", 14.dp, tint = Theme.inkSoft)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(consent.title, style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                FlowLayout(spacing = 6.dp) {
                    StatusChip(text = ConsentWords.status(consent.status), state = ConsentWords.state(consent.status), outlined = consent.status == "draft")
                    val pet = consent.petName.nonEmpty
                    if (context.pets.size > 1 && pet != null) HueChip(text = pet, hue = Hue.grey, systemImage = "pawprint.fill")
                    if (consent.validity == "standing") HueChip(text = ConsentWords.validity(consent.validity, consent.validDays), hue = Hue.grey)
                    consent.amountText?.let { HueChip(text = it, hue = Hue.butter, systemImage = "dollarsign.circle") }
                }
                Text(ConsentWords.line(consent), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        Box(Modifier.padding(start = 28.dp)) {
            FlowLayout(spacing = 8.dp) {
                if (canManage && consent.status == "draft") {
                    PMButton(L("Enter amount"), systemImage = "dollarsign.circle", size = ButtonSize.compact) { open(ConsentSheet.amount(consent)) }
                }
                if (canManage && consent.status == "pending") {
                    PMButton(L("Sign here"), systemImage = "signature", size = ButtonSize.compact) { open(ConsentSheet.sign(consent.id)) }
                    PMButton(L("Send for signature"), systemImage = "paperplane", variant = ButtonVariant.outline, size = ButtonSize.compact) { open(ConsentSheet.send(consent, choice = null)) }
                }
                if (consent.status != "draft") {
                    PMButton(if (consent.status == "signed") L("View · Print") else L("View"), systemImage = "eye", variant = ButtonVariant.outline, size = ButtonSize.compact) { open(ConsentSheet.view(consent.id)) }
                    PMButton(L("Evidence"), systemImage = "checkmark.shield", variant = ButtonVariant.outline, size = ButtonSize.compact) { open(ConsentSheet.evidence(consent)) }
                }
                if (canManage && cancellable) {
                    PMButton(L("Withdraw"), systemImage = "xmark.circle", variant = ButtonVariant.destructive, size = ButtonSize.compact) { open(ConsentSheet.cancel(consent)) }
                }
                if (canManage && revocable) {
                    PMButton(L("Revoke"), systemImage = "arrow.uturn.backward", variant = ButtonVariant.destructive, size = ButtonSize.compact) { open(ConsentSheet.revoke(consent)) }
                }
            }
        }
    }
}

// MARK: - Ask for a consent

/**
 * "Ask for a consent": one of the business's active templates, its amount when
 * the text names one, which pet (a once-per-pet consent on an act with several),
 * and whether it goes to the family now or is signed here.
 */
@Composable
fun ConsentRequestSheet(
    context: ConsentContext,
    modifier: Modifier = Modifier,
    /** The new consent, and whether it is signed here next. */
    onRequested: (Consent, Boolean) -> Unit = { _, _ -> },
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var templates by remember { mutableStateOf<List<ConsentTemplate>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var templateId by remember { mutableStateOf("") }
    val amount = remember { mutableStateOf<Int?>(null) }
    var petId by remember { mutableStateOf("") }
    val how = remember { mutableStateOf("send") }
    // The Swift sets `notify` once on appear (`prepared`): remembered once here.
    val notify = remember { mutableStateOf(context.familyOnApp) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val active = (templates ?: emptyList()).filter { it.status == null || it.status == "active" }
    val chosen = active.firstOrNull { it.id == templateId }
    val needsPet = chosen?.validity == "standing" && context.pets.size > 1
    val signsHere = !context.familyOnApp || how.value == "here"

    suspend fun load() {
        try {
            templates = API.get("/provider-consent-templates", mapOf("includeArchived" to "false"))
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = ConsentWords.error(e)
        }
    }

    fun submit() {
        if (!form.check(
                listOf(
                    "template" to (if (chosen == null) L("Choose a consent.") else null),
                    "amount" to (if (chosen?.needsAmount == true && (amount.value ?: 0) <= 0) L("Enter the amount this consent names.") else null),
                    "pet" to (if (needsPet && petId.isEmpty()) L("Choose the pet — this consent is signed once for each pet.") else null),
                )
            )
        ) return
        val template = chosen ?: return
        val body = context.act.body.toMutableMap()
        body["templateId"] = template.id
        body["notify"] = !signsHere && notify.value && context.familyOnApp
        if (template.needsAmount == true) body["amountCents"] = amount.value
        if (needsPet) body["providerClientPetId"] = petId
        val payload = body.toMap()
        val here = signsHere
        working = true
        scope.launch {
            try {
                val created: Consent = API.post("/provider-consents", payload)
                Toast.show(if (created.notified == true) L("Sent to the family to sign.") else L("Consent added."))
                announceDataChanged()
                onRequested(created, here)
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(ConsentWords.refusal(e, "Could not ask for it. Try again."), known = mapOf("templateId" to "template", "amountCents" to "amount", "providerClientPetId" to "pet"))
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (templates == null) load()
    }

    ProviderModal(
        title = L("Ask for a consent"),
        subtitle = if (context.familyOnApp) L("The family signs it in their PawMiaw app, or the client signs it here.") else L("The family isn’t on PawMiaw: the client signs it here, on this device."),
        systemImage = "doc.badge.plus",
        accent = ModalAccent.butter,
        isDirty = templateId.isNotEmpty(),
        form = form,
        primaryTitle = if (signsHere) L("Add and sign here") else if (notify.value) L("Send to the family") else L("Add it"),
        isWorking = working,
        modifier = modifier,
        onPrimary = { submit() },
    ) {
        ModalSection(title = L("Which consent")) {
            val problem = failed
            if (templates != null) {
                if (active.isEmpty()) {
                    NoticeCard(tone = NoticeCard.Tone.info, title = L("There are no active consents yet."), message = L("Set them up in Settings"))
                } else {
                    FormField(label = "", key = "template", form = form) {
                        ChoiceCards(
                            options = active.map { template ->
                                Triple(
                                    template.id,
                                    template.title,
                                    listOfNotNull(
                                        ConsentWords.validity(template.validity, template.validDays),
                                        if (template.needsAmount == true) L("Names an amount") else null,
                                        template.language?.uppercase(),
                                    ).joinToString(" · ") as String?,
                                )
                            },
                            selection = stateOf({ templateId }, { templateId = it; form.clear("template") }),
                        )
                    }
                }
            } else if (problem != null) {
                ErrorState(message = problem) { load() }
            } else {
                LoadingState()
            }
        }
        if (chosen?.needsAmount == true) {
            ModalSection(title = L("Amount"), subtitle = L("It fills {amount} in the text, e.g. the estimate or the deposit.")) {
                MoneyField(L("Amount"), cents = amount, key = "amount", form = form)
            }
        }
        if (needsPet) {
            ModalSection(title = L("For which pet")) {
                FormField(label = "", key = "pet", form = form) {
                    FlowLayout(spacing = 8.dp) {
                        for (pet in context.pets) {
                            FilterChip(title = pet.name, isSelected = petId == pet.id) { petId = pet.id; form.clear("pet") }
                        }
                    }
                }
            }
        }
        if (context.familyOnApp) {
            ModalSection(title = L("How will they sign?")) {
                ChoiceCards(
                    options = listOf(
                        Triple("send", L("In the family’s PawMiaw app"), L("They get a notification and sign in the app.") as String?),
                        Triple("here", L("On this device"), L("The client signs here now, with you as the witness.") as String?),
                    ),
                    selection = how,
                )
                if (how.value == "send") {
                    ToggleRow(title = L("Tell the family now"), subtitle = L("A notification with the consent to read and sign."), isOn = notify)
                }
            }
        }
    }
}

// MARK: - Enter the amount

/**
 * A consent whose text names an amount waits as a draft until the business
 * enters it; then it goes to the family, or is signed here.
 */
@Composable
fun ConsentAmountSheet(
    consent: Consent,
    familyOnApp: Boolean,
    modifier: Modifier = Modifier,
    onReady: (Consent, Boolean) -> Unit = { _, _ -> },
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val amount = remember { mutableStateOf<Int?>(null) }
    val how = remember { mutableStateOf("send") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val signsHere = !familyOnApp || how.value == "here"

    LaunchedEffect(Unit) { if (amount.value == null) amount.value = consent.amountCents }

    fun submit() {
        if (!form.check(listOf("amount" to (if ((amount.value ?: 0) <= 0) L("Enter the amount this consent names.") else null)))) return
        val here = signsHere
        working = true
        scope.launch {
            try {
                val updated: Consent = API.post("/provider-consents/${consent.id}/send", mapOf("amountCents" to amount.value, "notify" to !here))
                Toast.show(if (updated.notified == true) L("Sent to the family to sign.") else L("Amount saved. It can be signed now."))
                announceDataChanged()
                onReady(updated, here)
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(ConsentWords.refusal(e, "Could not save the amount. Try again."), known = mapOf("amountCents" to "amount"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = consent.title,
        subtitle = L("This consent names an amount. Enter it before it is signed."),
        systemImage = "dollarsign.circle.fill",
        accent = ModalAccent.butter,
        isDirty = amount.value != consent.amountCents,
        form = form,
        primaryTitle = if (signsHere) L("Save and sign here") else L("Save and send to the family"),
        isWorking = working,
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
        onPrimary = { submit() },
    ) {
        MoneyField(L("Amount"), cents = amount, key = "amount", form = form)
        if (familyOnApp) {
            ChoiceCards(
                options = listOf(
                    Triple("send", L("In the family’s PawMiaw app"), L("They get a notification and sign in the app.") as String?),
                    Triple("here", L("On this device"), L("The client signs here now, with you as the witness.") as String?),
                ),
                selection = how,
            )
        }
    }
}

/** SwiftUI's `ProgressView()`: a small grey spinner. */
@Composable
private fun ConsentSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.inkSoft)
}
