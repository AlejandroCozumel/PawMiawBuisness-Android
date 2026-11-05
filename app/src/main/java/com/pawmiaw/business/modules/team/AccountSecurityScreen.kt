package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

//
//  AccountSecurityScreen.kt (iOS AccountSecurityScreen.swift)
//
//  Your account (web: routes/provider.account.security.tsx): your profile
//  (name, phone, photo; the email you sign in with), where you are signed
//  in (sign a device out, or every device), and your password (change it
//  with the current one or with a code we email), your professional
//  signature, and deleting the account. One account for the business
//  workspace and for your pets. Signing out of this device is in the shell.
//

@Composable
fun AccountSecurityScreen(modifier: Modifier = Modifier) {
    providerPage(L("Your account"), modifier = modifier) {
        PageScroll {
            ModuleHeader(
                icon = ProviderSection.accountSecurity.headerIcon,
                title = L("Your account"),
                subtitle = L("Your profile, where you are signed in, your signature and your account. It is one account: the same for your business workspace and for your pets."),
                accent = ProviderSection.accountSecurity.headerAccent,
                storageKey = "account-security",
            )
            TeamAccountProfileCard()
            TeamSessionsCard()
            TeamPanel(title = L("One account for everything"), systemImage = "paw-print", tint = Theme.pink) {
                Text(
                    L("Signing a device out here closes PawMiaw on it completely: your pets and your business workspace alike. Your business details are in Settings."),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            TeamPasswordCard()
            // The professional signature: printed on prescriptions, records, discharges and certificates.
            MySignatureCard()
            TeamAccountDeletionPanel()
        }
    }
}

// MARK: - Profile

@Composable
private fun TeamAccountProfileCard() {
    val session = SessionStore.shared
    val form = remember { FormState() }
    val flash = remember { TeamSavedFlash() }
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<TeamUserProfile?>(null) }
    var failed by remember { mutableStateOf(false) }
    val name = remember { mutableStateOf("") }
    val phone = remember { mutableStateOf("") }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    var saving by remember { mutableStateOf(false) }

    val dirty = profile?.let { p ->
        name.value.trim() != (p.name ?: "") || phone.value.trim() != (p.phone ?: "") || photo.value.data != null
    } ?: false

    fun fill() {
        name.value = profile?.name ?: ""
        phone.value = profile?.phone ?: ""
        photo.value = PhotoDraft()
        form.errors = emptyMap()
    }

    suspend fun load() {
        try {
            profile = API.get("/users/me")
            failed = false
            fill()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = profile == null
        }
    }

    fun save() {
        val trimmed = name.value.trim()
        val p = phone.value.trim()
        val digits = p.count { it.isDigit() }
        if (!form.check(listOf(
                "name" to (if (trimmed.isEmpty()) L("Add your name.") else (if (trimmed.length > 120) L("Keep the name under 120 characters.") else null)),
                "phone" to (if (p.isNotEmpty() && (digits < 8 || digits > 15)) L("Finish the phone number, or leave it empty.") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                if (trimmed != (profile?.name ?: "") || p != (profile?.phone ?: "")) {
                    // An empty phone clears it.
                    profile = API.patch<TeamUserProfile>("/users/me", mapOf("name" to trimmed, "phone" to p))
                }
                val data = photo.value.data
                if (data != null) {
                    ImageUpload.upload<EmptyResponse>(data, to = "/users/me/photo")
                    profile = runCatching { API.get<TeamUserProfile>("/users/me") }.getOrNull()
                }
                fill()
                flash.mark()
                session.revalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("name" to "name", "phone" to "phone"))
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    TeamSaveSection(
        title = L("Your profile"),
        subtitle = L("Your name and photo as your team and families see them. It is one account: the same in your workspace and with your pets."),
        systemImage = "user-round",
        tint = Theme.blue,
        look = TeamCardLook.settings,
        state = flash.state(saving = saving, dirty = dirty),
        saveLabel = L("Save profile"),
        onSave = if (profile == null) null else ({ save() }),
        onDiscard = { fill() },
    ) {
        if (profile == null) {
            if (failed) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(L("Could not save your profile."), style = Theme.text(14), color = Theme.inkSoft)
                    PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { load() } }
                }
            } else TeamProgress(minHeight = 64.dp)
        } else {
            FormScroll(form) {
                Disabled(saving) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PhotoField(label = L("Photo"), hint = L("JPG, PNG or WebP, up to 3 MB."), currentURL = profile?.image, draft = photo, shape = PhotoField.Shape.circle, allowsRemove = false)
                        PMTextField(L("Name"), text = name, key = "name", form = form, prompt = L("Your name"), capitalization = KeyboardCapitalization.Words)
                        PMTextField(L("Phone"), text = phone, key = "phone", form = form, keyboard = KeyboardType.Phone, optional = true)
                        FormField(L("Email"), hint = L("The address you sign in with.")) {
                            FieldChrome {
                                Text(profile?.email ?: session.user?.email ?: "—", Modifier.weight(1f), style = Theme.bodyFont, color = Theme.inkSoft)
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Sessions

/** SwiftUI `.padding(.horizontal, -x).padding(.bottom, -x)`: the strip runs to the card's sides and foot. */
private fun Modifier.bleedToFoot(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2) else constraints
    val placeable = measurable.measure(wide)
    layout((placeable.width - extra * 2).coerceAtLeast(0), (placeable.height - extra).coerceAtLeast(0)) { placeable.place(-extra, 0) }
}

@Composable
private fun TeamSessionsCard() {
    var sessions by remember { mutableStateOf<List<TeamUserSession>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var revokingId by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val answer: TeamSessionsResponse = API.get("/users/me/sessions")
            sessions = answer.sessions
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
        loaded = true
    }

    fun revoke(item: TeamUserSession) {
        revokingId = item.id
        scope.launch {
            try {
                API.send("DELETE", "/users/me/sessions/${item.id}")
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                revokingId = null
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val ordered = sessions.filter { it.isCurrent ?: false } + sessions.filter { !(it.isCurrent ?: false) }
    TeamPanel(
        title = L("Connected sessions"),
        subtitle = L("Devices currently signed into your account. Sign out any you don’t recognize."),
        systemImage = "shield-check",
        tint = Theme.aqua,
        action = {
            if (loaded && sessions.isNotEmpty()) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                        .background(Theme.blue, CapsuleShape)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${sessions.size}", style = Theme.text(14, FontWeight.Bold), color = Theme.onBlue)
                }
            }
        },
    ) {
        if (!loaded) {
            TeamProgress(minHeight = 64.dp)
        } else if (failed && sessions.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L("Something went wrong. Try again."), style = Theme.text(14), color = Theme.inkSoft)
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { load() } }
            }
        } else if (sessions.isEmpty()) {
            val box = RoundedCornerShape(Theme.boxRadius)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, box)
                    .border(2.dp, Theme.borderSoft, box)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(Theme.surface, CircleShape)
                        .border(1.dp, Theme.borderSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Glyph("shield-check", 18.dp, tint = Theme.blue) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(L("Sessions").uppercase(appLocale), style = Theme.text(12, FontWeight.Bold).tracking(1.4), color = Theme.inkSoft)
                    Text(L("No active sessions were found."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ordered.forEach { item -> SessionRow(item, revoking = revokingId == item.id) { revoke(item) } }
            }
        }
        if (loaded && !(failed && sessions.isEmpty())) {
            // The footer strip: what "all" means, and the coral button.
            val others = sessions.count { !(it.isCurrent ?: false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .bleedToFoot(16.dp)
                    .background(Theme.surfaceMuted)
                    .teamEdgeRule(Theme.borderSoft, top = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (others == 1) L("Sign out 1 other device plus this one.") else if (others > 1) LF("Sign out %d other devices plus this one.", others) else L("Signing out all devices ends every session, including this one."),
                    style = Theme.text(14, FontWeight.Medium),
                    color = Theme.inkSoft,
                )
                PMButton(L("Sign out all devices"), systemImage = "log-out", variant = ButtonVariant.destructive, fullWidth = true, enabled = sessions.isNotEmpty()) { confirmAll = true }
            }
        }
    }

    if (confirmAll) {
        AppSheet(onDismissRequest = { confirmAll = false }) {
            TeamSignOutAllSheet(otherSessions = if (loaded) sessions.count { !(it.isCurrent ?: false) } else null)
        }
    }
}

@Composable
private fun SessionRow(item: TeamUserSession, revoking: Boolean, revoke: () -> Unit) {
    val browser = item.browserLabel ?: ""
    val platform = item.platformLabel ?: ""
    val name = if (item.deviceLabel == "$browser on $platform") LF("%1\$@ on %2\$@", L(browser), L(platform)) else L(item.deviceLabel ?: L("Unknown session"))
    val current = item.isCurrent ?: false
    val icon = run {
        val words = "${item.deviceLabel ?: ""} $platform".lowercase()
        when {
            words.contains("iphone") || words.contains("android phone") -> "smartphone"
            words.contains("ipad") || words.contains("tablet") -> "tablet"
            words.contains("android") || words.contains("phone") -> "smartphone"
            else -> "laptop"
        }
    }
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (current) Theme.wash(Theme.lime, 16.0) else Theme.surfaceMuted, box)
            .border(2.dp, if (current) Theme.blue else Theme.borderSoft, box)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(if (current) Theme.lime else Theme.surfaceMuted, CircleShape)
                    .border(1.dp, if (current) Theme.blue else Theme.borderSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Glyph(icon, 20.dp, tint = if (current) Theme.inkOnBright else Theme.blue) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowLayout(spacing = 8.dp) {
                    Text(name, style = Theme.heading(18), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (current) {
                        Row(
                            Modifier
                                .height(26.dp)
                                .background(Theme.lime, CapsuleShape)
                                .border(1.dp, Theme.blue, CapsuleShape)
                                .padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("shield-check", 14.dp, tint = Theme.inkOnBright)
                            Text(L("This device"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkOnBright)
                        }
                    }
                }
                Text("${L(browser)} · ${L(platform)}".uppercase(appLocale), style = Theme.text(12, FontWeight.SemiBold).tracking(0.5), color = Theme.inkSoft)
            }
        }
        if (!current) {
            PMButton(L("Sign out"), systemImage = "log-out", variant = ButtonVariant.destructive, size = ButtonSize.compact, isWorking = revoking, action = revoke)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SessionMeta("map-pin", L(item.locationLabel.nonEmpty ?: "Unknown location"))
            SessionMeta("earth", item.ipAddress.nonEmpty ?: L("IP unavailable"))
            SessionMeta("clock-3", if (current) L("Now") else "${L("Active")} ${DateText.ago(item.lastActiveAt)}")
            item.createdAt?.let { SessionMeta("calendar-days", "${L("Signed in")} ${DateText.day(it)}") }
        }
        item.expiresAt?.let { expires ->
            Text(
                "${L("Session expires")} ${DateText.date(expires)}",
                Modifier
                    .fillMaxWidth()
                    .teamEdgeRule(Theme.borderSoft, top = true)
                    .padding(top = 10.dp),
                style = Theme.text(11.2, FontWeight.Medium),
                color = Theme.inkSoft,
            )
        }
    }
}

@Composable
private fun SessionMeta(icon: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 14.dp, tint = Theme.blue)
        Text(text, style = Theme.text(12, FontWeight.Medium), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Password

@Composable
private fun TeamPasswordCard() {
    val form = remember { FormState() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TeamPasswordStatus?>(null) }
    var open by remember { mutableStateOf(false) }
    var useCode by remember { mutableStateOf(false) }
    val current = remember { mutableStateOf("") }
    val code = remember { mutableStateOf("") }
    val next = remember { mutableStateOf("") }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val hasPassword = status?.hasPassword ?: true
    val usingCode = !hasPassword || useCode
    val social: String? = run {
        val names = (status?.socialProviders ?: emptyList()).mapNotNull { mapOf("google" to "Google", "apple" to "Apple")[it] }
        if (names.isEmpty()) null else if (names.size == 1) names[0] else LF("%1\$@ or %2\$@", names[0], names[1])
    }
    val summary = if (hasPassword) {
        social?.let { LF("You sign in with your email and password, or with %@. Changing the password signs your other devices out.", it) }
            ?: L("You sign in with your email and password. Changing the password signs your other devices out.")
    } else {
        val provider = social ?: L("another service")
        LF("Today you sign in with %1\$@, so this account has no password. Create one to also sign in with your email; signing in with %2\$@ keeps working.", provider, provider)
    }

    LaunchedEffect(Unit) { status = runCatching { API.get<TeamPasswordStatus>("/users/me/password") }.getOrNull() }
    LaunchedEffect(resendIn) {
        if (resendIn <= 0) return@LaunchedEffect
        delay(1_000)
        resendIn -= 1
    }

    fun reset() {
        open = false; useCode = false; current.value = ""; code.value = ""; next.value = ""; sentTo = null; resendIn = 0
        form.errors = emptyMap()
    }

    fun sendCode() {
        sending = true
        scope.launch {
            try {
                val sent: TeamPasswordCodeSent = API.post("/users/me/password/code")
                sentTo = sent.email ?: ""
                resendIn = 60
                code.value = ""
                form.clear("code")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(errorText(e, fallback = L("The code could not be sent. Try again.")))
            } finally {
                sending = false
            }
        }
    }

    fun save() {
        if (!form.check(listOf(
                "current" to (if (!usingCode && current.value.isEmpty()) L("Type your current password.") else null),
                "code" to (if (usingCode && code.value.count { it.isDigit() } != 6) L("Type the six digits we emailed you.") else null),
                "next" to (if (next.value.length < 8) L("Password must be at least 8 characters.") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                val body: Map<String, Any?> = if (usingCode) mapOf("newPassword" to next.value, "code" to code.value.filter { it.isDigit() }) else mapOf("newPassword" to next.value, "currentPassword" to current.value)
                val result: TeamPasswordResult = API.put("/users/me/password", body)
                val done = if (result.created ?: false) L("Password created.") else L("Password changed.")
                val others = result.otherSessionsSignedOut ?: 0
                val extra = if (others > 0) " " + LP(others, "1 other device was signed out.", "%d other devices were signed out.") else ""
                Toast.show(done + extra)
                status = TeamPasswordStatus(hasPassword = true, socialProviders = status?.socialProviders)
                reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val field = when ((e as? APIError)?.code) {
                    "wrong_password", "no_password" -> "current"
                    "invalid_code", "proof_required" -> "code"
                    "password_too_short", "password_too_long", "same_password", "password_compromised" -> "next"
                    else -> null
                }
                if (field != null) {
                    form.check(listOf(field to errorText(e)))
                } else {
                    Toast.error(errorText(e, fallback = L("The password could not be saved. Try again.")))
                }
            } finally {
                saving = false
            }
        }
    }

    TeamPanel(
        title = if (hasPassword) L("Password") else L("Create a password"),
        subtitle = summary,
        systemImage = "key-round",
        tint = Theme.lime,
        action = {
            if (!open) {
                PMButton(if (hasPassword) L("Change password") else L("Create a password"), systemImage = "key-round", variant = ButtonVariant.outline, fullWidth = true) { open = true }
            }
        },
    ) {
        if (open) {
            FormScroll(form) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (usingCode) {
                        val to = sentTo
                        if (to != null) {
                            PMTextField(LF("Six-digit code we sent to %@", to), text = code, key = "code", form = form, keyboard = KeyboardType.Number)
                            Text(
                                if (resendIn > 0) LF("Send another code in %d s", resendIn) else L("Send another code"),
                                Modifier.plainButton(enabled = !(resendIn > 0 || sending)) { sendCode() },
                                style = Theme.text(14, FontWeight.Bold),
                                color = Theme.blue.copy(alpha = if (resendIn > 0 || sending) 0.5f else 1f),
                            )
                        } else {
                            Text(
                                if (hasPassword) L("We email a six-digit code to your account’s address. Type it here to choose a new password.") else L("We email a six-digit code to your account’s address, to confirm it is you. Type it here to choose your password."),
                                style = Theme.text(14),
                                color = Theme.inkSoft,
                            )
                            PMButton(L("Email me the code"), systemImage = "mail", isWorking = sending) { sendCode() }
                            Column(Modifier.fillMaxWidth().formScrollTarget("code", form)) {
                                form.error("code")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
                            }
                        }
                    } else {
                        PMTextField(L("Current password"), text = current, key = "current", form = form, secure = true)
                        Text(L("I don’t remember it: email me a code"), Modifier.plainButton { useCode = true }, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                    }
                    PMTextField(L("New password"), text = next, key = "next", form = form, prompt = L("At least 8 characters"), secure = true)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PMButton(if (hasPassword) L("Change password") else L("Create a password"), systemImage = "key-round", fullWidth = true, isWorking = saving) { save() }
                        PMButton(L("Cancel"), variant = ButtonVariant.outline, fullWidth = true) { reset() }
                    }
                    if (hasPassword && useCode) {
                        Text(L("Use my current password"), Modifier.plainButton { useCode = false }, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                    }
                }
            }
        }
    }
}

/**
 * "Sign out all devices?" (web `SignOutAllConfirm`): the danger-zone confirmation — the coral
 * band with the log-out mark, what it touches as rows, no word to type.
 */
@Composable
private fun TeamSignOutAllSheet(
    /** The other devices signed in, when the page knows them. */
    otherSessions: Int?,
) {
    val session = SessionStore.shared
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    ProviderModal(
        title = L("Sign out all devices?"),
        subtitle = L("Danger zone"),
        systemImage = "log-out",
        accent = ModalAccent.coral,
        primaryTitle = if (working) L("Signing out…") else L("Sign out all devices"),
        primaryVariant = ButtonVariant.destructive,
        isWorking = working,
        primaryIcon = "log-out",
        onPrimary = {
            working = true
            scope.launch {
                // Signing every device out ends this session too: leave whether the server answered or not.
                runCatching { API.send("POST", "/users/me/sessions/revoke-all") }
                session.signOut()
                working = false
            }
        },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Text(L("This will revoke every session for your account, including this device. You will need to sign in again."), style = Theme.bodyFont, color = Theme.inkStrong)
        if (otherSessions != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FactRow(label = L("Other devices"), value = "$otherSessions")
                FactRow(label = L("This device"), value = L("Signed out too"))
            }
        }
        Text(L("Active app access on web and mobile will stop the next time each device checks the backend."), style = Theme.text(14), color = Theme.inkSoft)
    }
}
