package com.pawmiaw.business

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  ConsentsCard2.kt (iOS ConsentsCard.swift, second half): signing a consent here, a typed
//  signature's preview, View · Print, Send for signature and the e-signature evidence.
//

// MARK: - Sign here

/**
 * Signing a consent on this device with the client, the one sheet every consent
 * uses — a visit's, a stay's, the hospital's estimate and euthanasia consent.
 * Two steps: the terms for signing electronically ("I agree to sign
 * electronically"), then the full text, "I have read and agree", the signer's
 * full name and the signature, drawn on the pad or typed in the signature font.
 * The person signed in is kept as the witness.
 */
@Composable
fun ConsentSignSheet(
    consentId: String,
    businessName: String? = null,
    /** The toast once it is signed. */
    successMessage: String = L("Signed. It is kept with the visit or stay."),
    modifier: Modifier = Modifier,
    onSigned: () -> Unit = {},
) {
    val dismiss = LocalDismiss.current
    val workspace = Workspace.shared
    val form = remember { FormState() }
    var consent by remember { mutableStateOf<Consent?>(null) }
    var terms by remember { mutableStateOf<EsignTerms?>(null) }
    var termsFailed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(0) }
    var termsAccepted by remember { mutableStateOf(false) }
    var termsChanged by remember { mutableStateOf(false) }
    var agree by remember { mutableStateOf(false) }
    val signer = remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("draw") }
    val strokes = remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    val typed = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600

    val meta = listOf(
        businessName ?: workspace.current?.name,
        consent?.petName,
        consent?.amountText,
        consent?.let { ConsentWords.validity(it.validity, it.validDays) },
    ).mapNotNull { it.nonEmpty }.joinToString(" · ")

    // MARK: Loading

    suspend fun loadTerms() {
        val language = consent?.language ?: (if (LocalizationManager.currentLanguage == AppLanguage.spanish) "es" else "en")
        val fetched: EsignTerms? = try {
            API.get("/esign/terms", mapOf("lang" to language))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fetched != null) terms = fetched
        termsFailed = fetched == null
    }

    suspend fun load() {
        try {
            val loaded: Consent = API.get("/provider-consents/$consentId")
            consent = loaded
            failed = null
            val given = loaded.terms
            val version = given?.version
            if (given != null && version != null) {
                terms = EsignTerms(version = version, language = given.language, title = given.title, text = given.text, reviewNote = given.reviewNote)
            } else {
                loadTerms()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = ConsentWords.error(e)
        }
    }

    // MARK: Signing

    fun continueToSign() {
        if (terms == null) return
        if (!form.check(listOf("terms" to (if (termsAccepted) null else L("Tick that you agree to sign electronically."))))) return
        termsChanged = false
        step = 1
    }

    fun normalizedName(value: String): String? {
        val name = SignatureRules.collapseSpaces(value)
        return if (SignatureRules.characterCount(name) in 3..120 && name.count { it.isLetter() } >= 3) name else null
    }

    suspend fun refused(error: Throwable) {
        val code = (error as? APIError)?.code ?: ""
        when {
            code == "terms_outdated" -> {
                // The terms moved on while this was open: show the new ones and ask again.
                load()
                termsAccepted = false
                termsChanged = true
                step = 0
                Toast.error(L("The terms for signing electronically were just updated. Read the new version and accept it to continue."))
            }
            code == "terms_required" -> {
                termsAccepted = false
                step = 0
                form.check(listOf("terms" to L("Tick that you agree to sign electronically.")))
            }
            code == "agreement_required" -> form.check(listOf("agree" to L("Tick that you read and agree.")))
            code == "signer_name_required" -> form.check(listOf("signer" to L("Type the full name of who signs.")))
            code == "typed_signature_required" -> form.check(listOf("signature" to L("Type the signature (2 to 80 characters).")))
            code.startsWith("signature_") -> form.check(listOf("signature" to ConsentWords.error(error, "Could not sign it. Try again.")))
            else -> Toast.error(ConsentWords.error(error, "Could not sign it. Try again."))
        }
    }

    fun sign() {
        val version = terms?.version
        if (version == null || !termsAccepted) {
            step = 0
            form.check(listOf("terms" to L("Tick that you agree to sign electronically.")))
            return
        }
        val name = normalizedName(signer.value)
        val typedText = SignatureRules.collapseSpaces(typed.value)
        val png = if (mode == "draw") SignaturePad.pngData(strokes.value) else null
        val signatureProblem: String? = if (mode == "draw") {
            if (png == null) L("Draw the signature.") else null
        } else {
            if (SignatureRules.characterCount(typedText) in 2..80) null else L("Type the signature (2 to 80 characters).")
        }
        if (!form.check(
                listOf(
                    "agree" to (if (agree) null else L("Tick that you read and agree.")),
                    "signer" to (if (name == null) L("Type the full name of who signs (at least 3 letters).") else null),
                    "signature" to signatureProblem,
                )
            ) || name == null
        ) return
        val fields = mutableListOf(
            "signerName" to name,
            "agree" to "true",
            "termsAccepted" to "true",
            "termsVersion" to version,
            "signatureKind" to (if (mode == "draw") "drawn" else "typed"),
            // iOS sends "iPad" / "iPhone".
            "device" to "PawMiaw Negocios · " + (if (tablet) "Android tablet" else "Android phone"),
        )
        val files = mutableListOf<API.UploadFile>()
        if (mode == "draw" && png != null) {
            files.add(API.UploadFile("signature", "signature.png", "image/png", png))
        } else {
            fields.add("typedSignature" to typedText)
        }
        val parts = fields.toList()
        val uploads = files.toList()
        saving = true
        scope.launch {
            try {
                API.multipart<EmptyResponse>("/provider-consents/$consentId/sign", fields = parts, files = uploads)
                Toast.show(successMessage)
                announceDataChanged()
                onSigned()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refused(e)
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { if (consent == null) load() }

    ProviderModal(
        title = consent?.title ?: L("Consent"),
        subtitle = meta.nonEmpty,
        systemImage = "signature",
        accent = ModalAccent.butter,
        isDirty = termsAccepted || agree || signer.value.isNotEmpty() || strokes.value.isNotEmpty() || typed.value.isNotEmpty(),
        form = form,
        primaryTitle = if (consent == null) null else (if (step == 0) L("Continue") else L("Sign")),
        isWorking = saving,
        modifier = modifier,
        onPrimary = { if (step == 0) continueToSign() else sign() },
    ) {
        val shown = consent
        val problem = failed
        if (shown != null) {
            SystemSegmentedPicker(
                label = L("Step"),
                options = listOf(0 to "1 · " + L("Signing terms"), 1 to "2 · " + L("Read and sign")),
                selection = stateOf({ step }, { if (it == 0) step = 0 else continueToSign() }),
            )
            NoticeCard(tone = NoticeCard.Tone.info, title = L("Hand the device to the client: they read it, tick that they agree, type their full name and sign. Your name is kept as the witness."))
            if (step == 0) {
                // MARK: Step 1: the terms
                EsignTermsPanel(
                    terms = terms,
                    failed = termsFailed,
                    changed = termsChanged,
                    accepted = stateOf({ termsAccepted }, { termsAccepted = it; form.clear("terms") }),
                    error = form.error("terms"),
                ) { scope.launch { loadTerms() } }
            } else {
                // MARK: Step 2: read and sign
                ConsentTextBlock(shown, label = L("The consent’s text"))
                FormField(label = "", key = "agree", form = form) {
                    ToggleRow(title = L("I have read and agree"), isOn = stateOf({ agree }, { agree = it; form.clear("agree") }))
                }
                PMTextField(
                    L("Full name of who signs"),
                    text = signer,
                    key = "signer",
                    form = form,
                    prompt = L("First and last name"),
                    capitalization = KeyboardCapitalization.Words,
                )
                FormField(label = L("Signature"), key = "signature", form = form) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SystemSegmentedPicker(
                            label = L("Signature"),
                            options = listOf("draw" to L("Draw"), "type" to L("Type")),
                            selection = stateOf({ mode }, { value ->
                                mode = value
                                form.clear("signature")
                                if (value == "type" && typed.value.isEmpty()) normalizedName(signer.value)?.let { typed.value = it }
                            }),
                        )
                        if (mode == "draw") {
                            SignaturePad(strokes = stateOf({ strokes.value }, { strokes.value = it; form.clear("signature") }))
                            if (strokes.value.isNotEmpty()) {
                                PMButton(L("Clear"), systemImage = "arrow.counterclockwise", variant = ButtonVariant.outline, size = ButtonSize.compact) { strokes.value = emptyList() }
                            }
                        } else {
                            PMTextField(
                                L("Typed signature"),
                                text = stateOf({ typed.value }, { typed.value = it; form.clear("signature") }),
                                prompt = L("Type your signature"),
                                capitalization = KeyboardCapitalization.Words,
                            )
                            ConsentTypedSignature(text = typed.value.trim())
                        }
                    }
                }
                Text(
                    L("The text as you read it, your name, the signature, the time and this device are kept together and can’t be changed afterwards."),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
            }
        } else if (problem != null) {
            ErrorState(message = problem) { load() }
        } else {
            LoadingState()
        }
    }
}

/** The consent's title and full text in a bordered box, the text selectable. */
@Composable
private fun ConsentTextBlock(consent: Consent, label: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Theme.borderSoft, RoundedCornerShape(18.dp))
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(consent.title, style = Theme.heading(19), color = Theme.inkStrong)
        SelectionContainer {
            Text(consent.body ?: "", style = Theme.text(15), color = Theme.inkStrong)
        }
    }
}

/** A typed signature in the signature font, on a white line (a preview, or a document's mark). */
@Composable
fun ConsentTypedSignature(text: String, height: Dp = 110.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val description = if (text.isEmpty()) L("Your signature shows here") else text
    val border = Theme.borderSoft
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Color.White, shape)
            .drawBehind {
                drawRoundRect(
                    color = border,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)),
                )
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (text.isEmpty()) {
            Text(L("Your signature shows here"), style = Theme.text(14, FontWeight.SemiBold), color = Color(0.55f, 0.55f, 0.55f))
        } else {
            TypedSignature(text = text, size = 34, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 22.dp)
                .height(1.dp)
                .background(Color(0.8f, 0.8f, 0.8f))
        )
    }
}

// MARK: - View · Print

/** The consent as a document: who, what, the text, the signature and its integrity, and the PDF. */
@Composable
fun ConsentViewSheet(consentId: String, context: ConsentContext, modifier: Modifier = Modifier) {
    var consent by remember { mutableStateOf<Consent?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            consent = API.get<Consent>("/provider-consents/$consentId")
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = ConsentWords.error(e)
        }
    }

    LaunchedEffect(Unit) { load() }

    ProviderModal(
        title = consent?.title ?: L("Consent"),
        subtitle = consent?.let { ConsentWords.status(it.status) },
        systemImage = "doc.text.fill",
        accent = ModalAccent.sky,
        cancelTitle = L("Close"),
        modifier = modifier,
    ) {
        val shown = consent
        val problem = failed
        if (shown != null) {
            ConsentDocument(shown, context)
        } else if (problem != null) {
            ErrorState(message = problem) { load() }
        } else {
            LoadingState()
        }
    }
}

/** The document's parts, one under another in the sheet. */
@Composable
private fun ConsentDocument(consent: Consent, context: ConsentContext) {
    val open = rememberOpenURL()
    DocumentButton(
        title = if (consent.status == "signed") L("Print") else L("Download PDF"),
        path = "/provider-consents/${consent.id}/pdf",
        query = mapOf("lang" to LocalizationManager.currentLanguage.rawValue),
        fileName = consent.title,
        variant = ButtonVariant.primary,
        size = ButtonSize.compact,
    )
    Card {
        FactRow(label = L("Business"), value = context.businessName ?: Workspace.shared.current?.name)
        FactRow(label = L("Client"), value = context.clientName)
        consent.petName?.let { FactRow(label = L("Pet"), value = it) }
        context.actLabel?.let { FactRow(label = L("For"), value = it) }
        consent.amountText?.let { FactRow(label = L("Amount"), value = it) }
        FactRow(label = L("Valid"), value = ConsentWords.validity(consent.validity, consent.validDays))
        FactRow(label = L("Asked for"), value = ConsentWords.date(consent.requestedAt))
    }
    Text(ConsentWords.line(consent), style = Theme.smallFont, color = Theme.inkSoft)
    ConsentWords.closedNote(consent)?.let { note -> NoticeCard(tone = NoticeCard.Tone.problem, title = note) }
    ConsentTextBlock(consent)
    ModalSection(title = L("Signature")) {
        if (consent.signedAt != null) {
            val typedText = if (consent.mark?.kind == "typed") consent.mark.text else null
            SignatureMarkView(mark = SignatureMark(kind = if (typedText != null) "typed" else "drawn", text = typedText, imageDataUrl = consent.signatureDataUrl ?: consent.mark?.imageDataUrl))
            consent.signerName?.let { Text(LF("Signature of %@", it), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong) }
            FactRow(label = L("Signed"), value = ConsentWords.date(consent.signedAt))
            FactRow(label = L("How"), value = ConsentWords.via(consent.signedVia))
            consent.signerAccountName?.let { FactRow(label = L("PawMiaw account"), value = it) }
            consent.witnessName?.let { FactRow(label = L("Witness"), value = it) }
            consent.integrity?.let { integrity ->
                val verified = integrity.verified
                val tint = if (verified == false) Theme.coralText else Theme.inkSoft
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (verified == true) "checkmark.shield.fill" else "exclamationmark.shield.fill", 14.dp, tint = tint)
                    Text(
                        when (verified) {
                            true -> L("Integrity verified: the text and the signature match what was signed.")
                            false -> L("Integrity check failed: what is stored no longer matches what was signed.")
                            null -> L("The integrity could not be checked.")
                        },
                        style = Theme.text(13, FontWeight.SemiBold),
                        color = tint,
                    )
                }
                integrity.snapshotSha256?.let { hash ->
                    Text("${L("Fingerprint")}: ${ConsentEsignWords.grouped(hash.take(16))}", style = consentMono(12), color = Theme.inkSoft)
                }
            }
        } else {
            Text(L("Not signed."), style = Theme.smallFont, color = Theme.inkSoft)
        }
    }
    val evidence = consent.evidence
    val code = evidence?.verifyCode
    if (evidence != null && code != null) {
        ModalSection(title = L("Electronic signature")) {
            FactRow(label = L("Verify code"), value = code)
            evidence.method?.let { FactRow(label = L("How"), value = ConsentEsignWords.method(it)) }
            FactRow(label = L("Sealed PDF"), value = ConsentEsignWords.seal(evidence.seal?.status))
            evidence.verifyUrl.nonEmpty?.let { url ->
                ConsentLink(L("Open the verify page"), "checkmark.shield") { open(url) }
            }
        }
    }
}

// MARK: - Send for signature

/**
 * "Send for signature": in the family's PawMiaw app, by a link the client opens
 * with a code emailed to them (and a WhatsApp message), or on this device now.
 */
@Composable
fun ConsentSendSheet(
    consent: Consent,
    context: ConsentContext,
    initialChoice: String? = null,
    modifier: Modifier = Modifier,
    /** "On this device": this closes and the signing sheet opens. */
    onSignHere: () -> Unit = {},
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    // The Swift fills these once on appear (`started`); remembered once here.
    val choice = remember { mutableStateOf(initialChoice ?: (if (context.familyOnApp) "app" else "link")) }
    val email = remember { mutableStateOf(context.clientEmail?.trim() ?: "") }
    val phone = remember { mutableStateOf(context.clientPhone?.trim() ?: "") }
    var days by remember { mutableStateOf(7) }
    val sendEmail = remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ConsentCreatedLink?>(null) }
    val scope = rememberCoroutineScope()

    val primaryTitle = when {
        result != null -> L("Done")
        choice.value == "device" -> L("Sign on this device")
        choice.value == "app" -> if (consent.sentAt != null) L("Remind the family") else L("Send to the app")
        else -> L("Make the link")
    }

    /** "+525512345678" from what was typed, or null when it isn't a phone number. */
    fun cleanPhone(text: String): String? {
        val digits = text.filter { it.isDigit() }
        if (text.trim().startsWith("+")) return if (digits.length in 8..15) "+$digits" else null
        if (digits.length == 10) return "+52$digits"
        return if (digits.length in 11..15) "+$digits" else null
    }

    fun submit() {
        if (result != null) { dismiss(); return }
        when (choice.value) {
            "device" -> {
                onSignHere()
                dismiss()
            }
            "app" -> {
                working = true
                scope.launch {
                    try {
                        val sent: Consent = API.post("/provider-consents/${consent.id}/send", mapOf("notify" to true))
                        Toast.show(if (sent.notified == true) L("Sent to the family’s app.") else L("Already sent today. The family can sign it from the app."))
                        announceDataChanged()
                        dismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.error(ConsentWords.error(e, "Could not send it. Try again."))
                    } finally {
                        working = false
                    }
                }
            }
            else -> {
                val typedEmail = email.value.trim()
                val typedPhone = phone.value.trim()
                val phoneValue = if (typedPhone.isEmpty()) null else cleanPhone(typedPhone)
                if (!form.check(
                        listOf(
                            "email" to (if (typedEmail.isNotEmpty() && !(typedEmail.contains("@") && typedEmail.contains("."))) L("That email doesn’t look right.") else null),
                            "phone" to (if (typedPhone.isNotEmpty() && phoneValue == null) L("That phone number doesn’t look right. Include the country code, e.g. +52.") else null),
                        )
                    )
                ) return
                val body = mutableMapOf<String, Any?>("days" to days, "sendEmail" to sendEmail.value, "language" to (consent.language ?: "es"))
                if (typedEmail.isNotEmpty()) body["email"] = typedEmail
                if (phoneValue != null) body["phone"] = phoneValue
                val payload = body.toMap()
                working = true
                scope.launch {
                    try {
                        result = API.post<ConsentCreatedLink>("/provider-consents/${consent.id}/signing-links", payload)
                        announceDataChanged()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        form.apply(ConsentWords.refusal(e, "Could not make the link. Try again."), known = mapOf("email" to "email", "phone" to "phone"))
                    } finally {
                        working = false
                    }
                }
            }
        }
    }

    ProviderModal(
        title = L("Send for signature"),
        subtitle = consent.title,
        systemImage = "paperplane.fill",
        accent = ModalAccent.aqua,
        form = form,
        primaryTitle = primaryTitle,
        isWorking = working,
        cancelTitle = if (result != null) L("Close") else L("Cancel"),
        modifier = modifier,
        onPrimary = { submit() },
    ) {
        val made = result
        if (made != null) {
            ConsentLinkDone(made)
        } else {
            ModalSection(title = L("How will they sign?")) {
                ChoiceCards(
                    options = (if (context.familyOnApp) listOf(Triple("app", L("In the family’s PawMiaw app"), L("They get a notification and sign in the app.") as String?)) else emptyList()) +
                        listOf(
                            Triple("link", L("By link"), L("An email with a link; they confirm a 6-digit code and sign from any phone. Share it by WhatsApp too.") as String?),
                            Triple("device", L("On this device"), L("The client signs here now, with you as the witness.") as String?),
                        ),
                    selection = choice,
                )
                if (!context.familyOnApp) {
                    Text(L("This family isn’t on PawMiaw."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
            if (choice.value == "link") {
                ModalSection(title = L("Where to send it")) {
                    PMTextField(
                        L("Client’s email"),
                        text = email,
                        key = "email",
                        form = form,
                        prompt = L("name@example.com"),
                        hint = L("The code goes to this email. Empty uses the email on the client’s file."),
                        keyboard = KeyboardType.Email,
                        capitalization = KeyboardCapitalization.None,
                        optional = true,
                    )
                    PMTextField(
                        L("Phone for WhatsApp (optional)"),
                        text = phone,
                        key = "phone",
                        form = form,
                        prompt = "+52 55 1234 5678",
                        hint = L("With a phone you get a “Share by WhatsApp” button. The code still goes by email."),
                        keyboard = KeyboardType.Phone,
                        capitalization = KeyboardCapitalization.None,
                        optional = true,
                    )
                    FormField(label = L("The link works for")) {
                        FlowLayout(spacing = 8.dp) {
                            for (option in listOf(1, 3, 7, 14, 30)) {
                                FilterChip(title = if (option == 1) L("1 day") else LF("%d days", option), isSelected = days == option) { days = option }
                            }
                        }
                    }
                    ToggleRow(title = L("Email the link now"), subtitle = L("Off: you only copy it or share it by WhatsApp."), isOn = sendEmail)
                }
            }
        }
    }
}

/** The link made: where it went, how long it works, and Copy, Share and WhatsApp. */
@Composable
private fun ConsentLinkDone(result: ConsentCreatedLink) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val open = rememberOpenURL()
    val masked = result.link?.maskedEmail ?: ""
    NoticeCard(
        tone = NoticeCard.Tone.success,
        title = if (result.emailSent == true) LF("Link sent to %@.", masked) else L("The link is ready. It wasn’t emailed: share it yourself."),
        message = LF("It works until %@; the client asks for a code sent to %@ before they can read and sign.", ConsentWords.date(result.link?.expiresAt), masked),
    )
    val url = result.url ?: return
    ModalSection(title = L("The link")) {
        SelectionContainer {
            Text(url, style = consentMono(13), color = Theme.inkStrong, maxLines = 2)
        }
        FlowLayout(spacing = 8.dp) {
            PMButton(L("Copy"), systemImage = "doc.on.doc", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                clipboard.setText(AnnotatedString(url))
                Toast.show(L("Link copied."))
            }
            if (runCatching { java.net.URI(url) }.isSuccess) {
                PMButton(L("Share"), systemImage = "square.and.arrow.up", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            val whatsapp = result.whatsappUrl
            if (whatsapp != null && runCatching { Uri.parse(whatsapp).scheme }.getOrNull() == "https") {
                PMButton(L("Share by WhatsApp"), systemImage = "message.fill", variant = ButtonVariant.outline, size = ButtonSize.compact) { open(whatsapp) }
            }
        }
        Text(L("Anyone with the link still needs the code from the client’s email. Making a new link stops this one."), style = Theme.text(12), color = Theme.inkSoft)
    }
}

// MARK: - Evidence

/**
 * A consent's e-signature as the business sees it: where it stands, who signs and
 * how, the links (and stopping one), the fingerprints, the event trail and "Verify".
 */
@Composable
fun ConsentEvidenceSheet(
    consent: Consent,
    canManage: Boolean,
    modifier: Modifier = Modifier,
    /** "Send a link": this closes and the send sheet opens on "By link". */
    onSendLink: () -> Unit = {},
) {
    val dismiss = LocalDismiss.current
    var evidence by remember { mutableStateOf<ConsentEvidence?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ConsentVerifyResult?>(null) }
    var stopping by remember { mutableStateOf<ConsentSigningLink?>(null) }
    val scope = rememberCoroutineScope()

    val canSend = canManage && evidence?.canSendLink == true

    suspend fun load() {
        try {
            evidence = API.get<ConsentEvidence>("/provider-consents/${consent.id}/evidence")
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = ConsentWords.error(e, "The evidence could not be loaded.")
        }
    }

    fun verify(requestId: String) {
        verifying = true
        scope.launch {
            try {
                result = API.post<ConsentVerifyResult>("/provider-signatures/$requestId/verify", emptyMap())
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(ConsentWords.error(e, "Could not run the check. Try again."))
            } finally {
                verifying = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    ProviderModal(
        title = L("Signature evidence"),
        subtitle = consent.title,
        systemImage = "checkmark.shield.fill",
        accent = ModalAccent.lavender,
        primaryTitle = if (canSend) (if ((evidence?.links ?: emptyList()).any { it.status == "active" }) L("Send a new link") else L("Send a link")) else null,
        cancelTitle = L("Close"),
        modifier = modifier,
        onPrimary = if (canSend) ({ onSendLink(); dismiss() }) else null,
    ) {
        val shown = evidence
        val problem = failed
        if (shown != null) {
            ConsentEvidenceContent(shown, canManage, verifying, result, verify = { verify(it) }, stop = { stopping = it })
        } else if (problem != null) {
            ErrorState(message = problem) { load() }
        } else {
            LoadingState()
        }
    }

    stopping?.let { link ->
        AppSheet(onDismissRequest = { stopping = null }) {
            ConfirmSheet(
                title = L("Stop this signing link?"),
                message = LF("The link sent to %@ stops working at once. You can send a new one.", link.maskedEmail ?: ""),
                confirmTitle = L("Stop the link"),
                destructive = true,
                askReason = true,
                reasonRequired = false,
                reasonLabel = L("Reason (optional)"),
            ) { reason ->
                val body = mutableMapOf<String, Any?>()
                if (reason != null) body["reason"] = reason
                try {
                    API.post<EmptyResponse>("/provider-signature-links/${link.id}/revoke", body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ConsentWords.refusal(e, "Could not stop the link. Try again.")
                }
                Toast.show(L("The link was stopped."))
                announceDataChanged()
                load()
            }
        }
    }
}

@Composable
private fun ConsentEvidenceContent(
    evidence: ConsentEvidence,
    canManage: Boolean,
    verifying: Boolean,
    result: ConsentVerifyResult?,
    verify: (String) -> Unit,
    stop: (ConsentSigningLink) -> Unit,
) {
    val open = rememberOpenURL()
    evidence.summary?.let { summary ->
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = ConsentEsignWords.envelope(summary.status),
                    state = when (summary.status) {
                        "completed" -> ProviderState.done
                        "pending" -> ProviderState.waiting
                        else -> ProviderState.problem
                    },
                )
                Spacer(Modifier.weight(1f))
                summary.requestId?.let { requestId ->
                    PMButton(L("Verify"), systemImage = "checkmark.shield", size = ButtonSize.compact, isWorking = verifying) { verify(requestId) }
                }
            }
            summary.method?.let { FactRow(label = L("How"), value = ConsentEsignWords.method(it)) }
            if (summary.signedAt != null) FactRow(label = L("Signed"), value = ConsentWords.date(summary.signedAt))
            summary.verifyCode?.let { FactRow(label = L("Verify code"), value = it) }
            FactRow(label = L("Sealed PDF"), value = ConsentEsignWords.seal(summary.seal?.status))
            if (summary.revokedAt != null) FactRow(label = L("Revoked"), value = ConsentWords.date(summary.revokedAt))
            summary.verifyUrl.nonEmpty?.let { url ->
                ConsentLink(L("Open the verify page"), "safari") { open(url) }
            }
        }
    }
    if (result != null) ConsentVerifyPanel(result)
    ModalSection(title = L("Signers")) {
        val signers = evidence.signers ?: emptyList()
        if (signers.isEmpty()) Text(L("No signer yet."), style = Theme.smallFont, color = Theme.inkSoft)
        for (signer in signers) {
            Card(padding = 12.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(signer.name ?: L("Name not given yet"), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                        Text(
                            (if (signer.role == "professional") L("Professional") else L("Signer")) + (signer.maskedEmail?.let { " · $it" } ?: ""),
                            style = Theme.text(12),
                            color = Theme.inkSoft,
                        )
                    }
                    StatusChip(
                        text = ConsentEsignWords.signer(signer.status),
                        state = when (signer.status) {
                            "signed" -> ProviderState.done
                            "pending" -> ProviderState.waiting
                            else -> ProviderState.problem
                        },
                    )
                }
                val typedText = if (signer.mark?.kind == "typed") signer.mark.text else null
                SignatureMarkView(mark = SignatureMark(kind = if (typedText != null) "typed" else "drawn", text = typedText, imageDataUrl = signer.mark?.imageDataUrl))
                signer.method?.let { FactRow(label = L("How"), value = ConsentEsignWords.method(it)) }
                signer.witnessName?.let { FactRow(label = L("Witness"), value = it) }
                if (signer.emailVerifiedAt != null) FactRow(label = L("Email code confirmed"), value = ConsentWords.date(signer.emailVerifiedAt))
                if (signer.termsAcceptedAt != null) {
                    FactRow(label = L("Accepted the signing terms"), value = ConsentWords.date(signer.termsAcceptedAt) + (signer.termsVersion?.let { " · v$it" } ?: ""))
                }
                if (signer.signedAt != null) FactRow(label = L("Signed"), value = ConsentWords.date(signer.signedAt))
                if (signer.declinedAt != null) FactRow(label = L("Declined"), value = ConsentWords.date(signer.declinedAt) + (signer.declineReason?.let { " — $it" } ?: ""))
                signer.ip?.let { FactRow(label = L("IP address"), value = it) }
                signer.device?.let { FactRow(label = L("Device"), value = it) }
            }
        }
    }
    val links = evidence.links ?: emptyList()
    if (links.isNotEmpty()) {
        ModalSection(title = L("Signing links")) {
            for (link in links) {
                Card(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            listOfNotNull(link.maskedEmail, link.phone).joinToString(" · "),
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.inkStrong,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(
                            text = ConsentEsignWords.link(link.status),
                            state = when (link.status) {
                                "active" -> ProviderState.waiting
                                "used" -> ProviderState.done
                                else -> ProviderState.closed
                            },
                        )
                    }
                    Text(
                        listOfNotNull(
                            LF("Made %@", ConsentWords.date(link.createdAt)) + (link.createdByName?.let { " · $it" } ?: ""),
                            if (link.status == "active") LF("Works until %@", ConsentWords.date(link.expiresAt)) else null,
                            if (link.openedAt != null) LF("Opened %@", ConsentWords.date(link.openedAt)) else null,
                            if (link.verifiedAt != null) LF("Code confirmed %@", ConsentWords.date(link.verifiedAt)) else null,
                        ).joinToString(" · "),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                    if (canManage && link.status == "active") {
                        PMButton(L("Stop this link"), systemImage = "xmark.circle", variant = ButtonVariant.outline, size = ButtonSize.compact) { stop(link) }
                    }
                }
            }
        }
    }
    ModalSection(title = L("Fingerprints"), subtitle = L("SHA-256 of what was signed. Any change to the text or the record changes them.")) {
        ConsentHashRow(L("Document"), evidence.documentSha256)
        evidence.finalEvidenceSha256?.let { ConsentHashRow(L("Final evidence"), it) }
        evidence.summary?.seal?.sha256?.let { ConsentHashRow(L("Sealed PDF"), it) }
        evidence.termsVersion?.let { FactRow(label = L("Signing terms"), value = "v$it") }
    }
    ModalSection(title = L("Event trail"), subtitle = L("Every step, in order. Each one is chained to the one before, so none can be changed or removed unnoticed.")) {
        val events = (evidence.events ?: emptyList()).sortedBy { it.sequence ?: 0 }
        if (events.isEmpty()) Text(L("No events yet."), style = Theme.smallFont, color = Theme.inkSoft)
        for (event in events) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp).size(9.dp).background(Theme.blue, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ConsentEsignWords.event(event.type), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    Text(
                        listOfNotNull(ConsentWords.date(event.at), ConsentEsignWords.actor(event.actorType, event.actorName), event.ip?.let { "IP $it" }).joinToString(" · "),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentHashRow(label: String, hash: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Theme.smallFont, color = Theme.inkSoft)
        SelectionContainer {
            Text(ConsentEsignWords.grouped(hash), style = consentMono(11), color = Theme.inkStrong)
        }
    }
}

/** What "Verify" found: every check with its mark, and the errors. */
@Composable
private fun ConsentVerifyPanel(result: ConsentVerifyResult) {
    val checks = result.checks ?: emptyMap()
    val keys = ConsentEsignWords.checkOrder.filter { it in checks.keys } + checks.keys.filter { it !in ConsentEsignWords.checkOrder }.sorted()
    val ok = result.ok == true
    Column(
        Modifier
            .fillMaxWidth()
            .background((if (ok) ProviderState.done.color else Theme.coral).copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val head = if (ok) ProviderState.done.color else Theme.coralText
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (ok) "checkmark.shield.fill" else "questionmark.diamond.fill", 16.dp, tint = head)
            Text(if (ok) L("Everything matches what was signed.") else L("Something no longer matches what was signed."), style = Theme.text(15, FontWeight.Bold), color = head)
        }
        Text(LF("Checked %@", ConsentWords.date(result.checkedAt)), style = Theme.text(12), color = Theme.inkSoft)
        for (key in keys) {
            val value = checks[key]
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(
                    when (value) {
                        true -> "checkmark.circle.fill"
                        false -> "xmark.circle.fill"
                        null -> "circle.dashed"
                    },
                    16.dp,
                    tint = when (value) {
                        true -> ProviderState.done.color
                        false -> Theme.coralText
                        null -> Theme.inkSoft
                    },
                )
                Text(ConsentEsignWords.check(key), style = Theme.text(14), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                if (value == null) {
                    Spacer(Modifier.width(4.dp))
                    Text(L("Not yet"), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
        for (error in result.errors ?: emptyList()) {
            Text(error, style = Theme.text(12), color = Theme.coralText)
        }
    }
}

// MARK: - Pieces of this file

/** SwiftUI's `Link { Label(…) }`: the accent's words and icon, 14 bold, opening a page. */
@Composable
private fun ConsentLink(title: String, icon: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .alpha(if (pressed) 0.5f else 1f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 15.dp, tint = Theme.blueText)
        Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.blueText)
    }
}

/** `.font(.system(size:, design: .monospaced))`. */
private fun consentMono(size: Int): TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp)
