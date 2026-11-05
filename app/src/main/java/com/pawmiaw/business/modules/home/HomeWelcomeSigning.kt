package com.pawmiaw.business.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.team.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max

//
//  HomeWelcomeSigning.kt (iOS HomeWelcomeSigning.swift)
//
//  The welcome's last two steps (web member-onboarding/signature-step.tsx,
//  credentials-step.tsx): the signature adopted once — drawn, typed or a
//  scan, with the professional e-sign terms accepted one time, so every
//  later "Firmar" is one tap (backend docs/e-signatures.md, "Adopt once") —
//  and the credentials by the business's country: the one the law asks to
//  prescribe first, the optional ones after, each added with Team's
//  credential sheet. The capture, the terms and the save are the shared
//  `SignatureCapture` pieces. Also used from Home's reminders.
//

// MARK: - 4. The signature, adopted once

@Composable
fun HomeWelcomeSignatureStep(
    state: HomeWelcomeState,
    onDone: suspend () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = remember { FormState() }
    var terms by remember { mutableStateOf<EsignTerms?>(null) }
    var termsFailed by remember { mutableStateOf(false) }
    var termsChanged by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var termsError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val saved: SignatureMark? = state.signature?.saved
    val adopted = state.signature?.adopted == true
    val language = LocalizationManager.currentLanguage.rawValue
    val name = HomeWelcomeState.printedName(state.profile?.displayName ?: "", state.profile?.accountName)

    // The Swift sets these once in `.task` (`started`): a new signature unless one is saved, typed as the name.
    var replacing by remember { mutableStateOf(saved == null) }
    val draft = remember { mutableStateOf(SignatureDraft(typed = name)) }
    var busy by remember { mutableStateOf(false) }

    suspend fun loadTerms() {
        termsFailed = false
        try {
            terms = ProfessionalSignature.terms(language = language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            termsFailed = true
        }
    }

    fun next() {
        // Already adopted and nothing new: on to the next step.
        if (adopted && !replacing) {
            busy = true
            scope.launch {
                onDone()
                busy = false
            }
            return
        }
        if (!accepted) {
            termsError = L("Accept signing electronically to adopt your signature.")
            Toast.error(L("Accept signing electronically to adopt your signature."))
            return
        }
        val shown = terms ?: return
        val signature: CapturedSignature? = if (replacing || saved == null) {
            if (!form.check(listOf("signature" to draft.value.problem()))) return
            draft.value.captured()
        } else {
            null
        }
        busy = true
        scope.launch {
            try {
                if (signature != null) {
                    ProfessionalSignature.save(signature, terms = shown, language = language)
                } else {
                    ProfessionalSignature.adopt(terms = shown, language = language)
                }
                Toast.show(L("Your signature is adopted. Signing is one tap from now on."))
                announceDataChanged()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val api = e as? APIError
                if (api != null && api.code == "terms_outdated") {
                    termsChanged = true
                    accepted = false
                    loadTerms()
                } else if (api != null && (api.code?.startsWith("signature_") == true || api.code == "typed_signature_required")) {
                    form.check(listOf("signature" to SignatureRules.message(api, fallback = api.message)))
                } else {
                    Toast.error(errorText(e, L("Could not save your signature.")))
                }
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { loadTerms() }

    HomeWelcomeStepFrame(
        state = state, step = "signature", title = L("Your signature, adopted once"),
        description = L("Draw it, type it or upload a scan, and accept signing electronically. You do this once."),
        nextLabel = if (adopted && !replacing) L("Continue") else L("Adopt my signature"), busy = busy,
        nextDisabled = !adopted && terms == null,
        note = if (adopted) null else L("Skipping it is fine: you can still sign, but your documents carry no signature image until you adopt one."),
        onNext = { next() }, onBack = onBack, onSkip = if (adopted) null else onSkip,
        modifier = modifier,
    ) {
        HomeWelcomeBlock(
            systemImage = "pencil.and.scribble", title = L("Your signature"),
            hint = L("As you sign on paper. A scan on white paper works too: PawMiaw trims it and clears the background."),
        ) {
            if (saved != null && !replacing) {
                SignatureMarkView(mark = saved, name = name, height = 110.dp)
                Text(name, style = Theme.captionFont, color = Theme.inkSoft)
                saved.createdAt?.let { created -> Text(LF("Saved %@", DateText.date(created)), style = Theme.captionFont, color = Theme.inkSoft) }
                PMButton(L("Change"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                    replacing = true
                    form.clear("signature")
                }
            } else {
                SignatureCapture(modes = SignatureCaptureMode.professional, draft = draft, suggestedText = name, label = null, form = form)
                if (saved != null) {
                    PMButton(L("Keep the saved one"), variant = ButtonVariant.outline, size = ButtonSize.compact) {
                        replacing = false
                        form.clear("signature")
                    }
                }
            }
        }
        if (adopted && !replacing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.blueWash, RoundedCornerShape(Theme.cardRadius))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph("badge-check", 16.dp, tint = Theme.blue)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("Adopted"), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                    state.signature?.adoption?.acceptedAt?.let { at ->
                        Text(LF("You accepted signing electronically on %@. Signing is one tap.", DateText.date(at)), style = Theme.smallFont, color = Theme.inkStrong)
                    }
                }
            }
        } else {
            HomeWelcomeBlock(systemImage = "doc.text.fill", title = L("Signing electronically"), hint = L("Read it once. It is what makes one tap your signature.")) {
                EsignTermsPanel(
                    terms = terms, failed = termsFailed, changed = termsChanged,
                    accepted = stateOf({ accepted }, { accepted = it; termsError = null }),
                    error = termsError,
                ) {
                    scope.launch { loadTerms() }
                }
            }
        }
        Card {
            HomeSigningGlyphLabel(L("Then, one tap"), icon = "zap", style = Theme.sectionFont, color = Theme.inkStrong)
            Text(
                L("Prescriptions, signed records and discharges carry this signature when you press Sign. No drawing and no terms each time; each document keeps its own copy and the record of this acceptance."),
                style = Theme.smallFont, color = Theme.inkSoft,
            )
        }
    }
}

// MARK: - 5. Credentials

@Composable
fun HomeWelcomeCredentialsStep(
    state: HomeWelcomeState,
    last: Boolean,
    onReload: suspend () -> Unit,
    onDone: suspend () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val credentials = state.credentials
    val prescriber = credentials?.kind == "prescriber"
    val required = credentials?.required
    val items = credentials?.items ?: emptyList()
    val requiredItems = required?.let { type -> items.filter { it.type == type } } ?: emptyList()
    val otherItems = items.filter { item -> requiredItems.none { it.id == item.id } }
    val optional = (credentials?.offered ?: emptyList()).filter { it != required }
    val canPrescribe = credentials?.canPrescribe == true
    val title = if (prescriber) L("Your credentials") else (if (credentials?.kind == "vet_tech") L("Your license and certifications") else L("Your certifications"))
    val description = if (prescriber) {
        required?.let { LF("Add %@ to sign prescriptions here. The rest is optional.", HomeWelcomeText.credentialNoun(it)) }
            ?: L("Add your professional license to sign prescriptions. The rest is optional.")
    } else {
        L("Optional. What you add shows on your profile in this workspace.")
    }

    HomeWelcomeStepFrame(
        state = state, step = "credentials", title = title, description = description,
        nextLabel = if (last) L("Finish") else L("Save and continue"), busy = busy,
        note = if (prescriber && !canPrescribe) L("Without it you work as usual; the first prescription you sign asks for it right there.") else null,
        onNext = {
            busy = true
            scope.launch {
                onDone()
                busy = false
            }
        },
        onBack = onBack,
        onSkip = if (prescriber && !canPrescribe) onSkip else null,
        modifier = modifier,
    ) {
        if (prescriber && required != null) {
            Card(tint = if (canPrescribe) Theme.blue else null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    if (canPrescribe) {
                        HomeDisc(icon = "badge-check", size = 40.dp, iconSize = 20.dp, fill = Theme.blueFixed, ink = Color.White)
                    } else {
                        IconBadge(icon = "id-card", accent = Theme.blue, size = 40.dp, iconSize = 20.dp, ink = IconBadge.Ink.accent, washPercent = 10.0)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(TeamCredentialRules.label(required), style = brandHeading(18), color = Theme.inkStrong)
                        Text(if (canPrescribe) L("You can sign prescriptions here.") else L("Needed to prescribe in this country."), style = Theme.smallFont, color = Theme.inkSoft)
                    }
                    if (requiredItems.isEmpty()) {
                        PMButton(L("Add"), systemImage = "plus", size = ButtonSize.compact) { adding = required }
                    }
                }
                for (item in requiredItems) key(item.id) { HomeCredentialRow(credential = item) }
            }
        }
        if (prescriber && required == null) {
            NoticeCard(tone = NoticeCard.Tone.info, title = L("The workspace has no country yet, so any professional license lets you prescribe."))
        }
        HomeWelcomeBlock(
            systemImage = "award", title = if (prescriber) L("Optional") else L("What you can add"),
            hint = if (prescriber) L("Only if they apply to you.") else null,
        ) {
            HomeSigningGrid(items = optional, minimum = 240.dp, spacing = 8.dp) { type ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainButton { adding = type }
                        .dashedBorder(1.5.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 5.dp)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    HomeDisc(icon = if (type == "certification") "award" else "plus", size = 30.dp, iconSize = 16.dp, ink = Theme.blue)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(TeamCredentialRules.label(type), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                        HomeWelcomeText.optionalWhy(type)?.let { why -> Text(why, style = Theme.text(13), color = Theme.inkSoft) }
                    }
                }
            }
            for (item in otherItems) key(item.id) { HomeCredentialRow(credential = item) }
        }
        Text(
            L("The prescription fields and wording follow public sources (NOM-064-ZOO-2000 in Mexico; the AVMA, the DEA and state boards in the United States). Have a professional review them before you rely on them."),
            style = Theme.captionFont, color = Theme.inkSoft,
        )
    }

    // `.sheet(item:)` with the type being added.
    adding?.let { HomeCredentialAsk(it) }?.let { ask ->
        key(ask.id) {
            AppSheet(onDismissRequest = { adding = null }) {
                TeamCredentialSheet(
                    memberId = "me", isSelf = true, country = credentials?.country, businessAddress = credentials?.businessAddress,
                    types = listOf(ask.type),
                ) {
                    onReload()
                }
            }
        }
    }
}

/** The credential type a sheet adds (the sheet's item). */
data class HomeCredentialAsk(val type: String) {
    val id: String get() = type
}

@Composable
private fun HomeCredentialRow(credential: HomeWelcomeState.Credential) {
    val name = if (credential.type == "certification") (credential.title.nonEmpty ?: TeamCredentialRules.label(credential.type)) else TeamCredentialRules.label(credential.type)
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("badge-check", 16.dp, tint = Theme.blue)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            val line = listOfNotNull(
                listOfNotNull(credential.region.nonEmpty, credential.number.nonEmpty).joinToString(" ").nonEmpty,
                credential.issuer.nonEmpty,
                credential.expiresOn?.let { LF("Expires %@", DateText.date(it)) },
            )
            if (line.isNotEmpty()) Text(line.joinToString(" · "), style = Theme.text(12), color = Theme.inkSoft)
        }
        TeamCredentialStatusChip(status = credential.status, verified = credential.verified)
    }
}

// MARK: - Pieces of this file

/** `GlyphLabel(…).font(f).foregroundStyle(c)`: the icon and the words in that font and ink. */
@Composable
private fun HomeSigningGlyphLabel(title: String, icon: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides color, LocalTextStyle provides style) {
        GlyphLabel(title, icon, modifier = modifier)
    }
}

/**
 * `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)], spacing:)` inside a scrolling
 * page: as many equal columns as fit at `minimum` wide, rows `spacing` apart.
 */
@Composable
private fun <T> HomeSigningGrid(items: List<T>, minimum: Dp, spacing: Dp, cell: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
                    chunk.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
