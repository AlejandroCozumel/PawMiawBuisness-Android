package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  TeamInviteSheet.kt (iOS TeamInviteSheet.swift)
//
//  Invite someone to the team in steps (web: staff-invite-sheet.tsx): who
//  they are, their role, where they work (a business with several
//  locations) and a last look. PawMiaw emails the invitation; once sent the
//  sheet hands over a message to send by another way. They accept with that
//  email within 48 hours.
//

private enum class InviteStep { who, role, place, review }

@Composable
fun TeamInviteSheet(
    workspaceName: String,
    /** Free seats on the plan, counting invitations still waiting; null with no cap. */
    seatsLeft: Int?,
    memberEmails: Set<String>,
    waitingEmails: Set<String>,
    /** Active locations, primary first; with two or more the sheet asks where they work. */
    locations: List<ProviderLocation>,
    roles: List<TeamRolePermissions>?,
    modifier: Modifier = Modifier,
    onSent: suspend () -> Unit,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var step by remember { mutableStateOf(InviteStep.who) }
    val name = remember { mutableStateOf("") }
    val email = remember { mutableStateOf("") }
    var role by remember { mutableStateOf("support_staff") }
    val locationIds = remember { mutableStateOf<List<String>>(emptyList()) }
    var pending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<TeamInvitation?>(null) }
    var copied by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    val asksWhere = locations.size >= 2 && role != "admin"
    val steps = if (asksWhere) listOf(InviteStep.who, InviteStep.role, InviteStep.place, InviteStep.review) else listOf(InviteStep.who, InviteStep.role, InviteStep.review)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val full = (seatsLeft ?: 1) <= 0
    val trimmedName = name.value.trim()
    val trimmedEmail = email.value.trim().lowercase()
    val everywhere = !asksWhere || locationIds.value.isEmpty() || locationIds.value.size == locations.size
    val whereText = if (everywhere) L("Every location") else locations.filter { locationIds.value.contains(it.id) }.joinToString(", ") { it.name }

    fun label(step: InviteStep): String = when (step) {
        InviteStep.who -> L("Who")
        InviteStep.role -> L("Role")
        InviteStep.place -> L("Where")
        InviteStep.review -> L("Review")
    }

    fun stepIcon(step: InviteStep): String = when (step) {
        InviteStep.who -> "user-round"
        InviteStep.role -> "key-round"
        InviteStep.place -> "map-pin"
        InviteStep.review -> "clipboard-check"
    }

    val primaryTitle = if (sent != null) L("Done") else when (step) {
        InviteStep.review -> L("Send invitation")
        // Every step forward is "Continue" (the owner's rule); the last keeps its own words.
        else -> L("Continue")
    }

    /** "3 seats left on your plan." (web `seatsText`): null with no cap. */
    val seatsText: String? = seatsLeft?.let { if (full) L("No seats left on your plan.") else LP(it, "1 seat left on your plan.", "%d seats left on your plan.") }

    fun checkWho(): Boolean {
        val pattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val emailError = when {
            trimmedEmail.isEmpty() -> L("Add their email.")
            !pattern.matches(trimmedEmail) -> L("That doesn’t look like an email.")
            memberEmails.contains(trimmedEmail) -> L("This person is already on the team.")
            waitingEmails.contains(trimmedEmail) -> L("This email already has an invitation waiting. Send it again from Invitations.")
            else -> null
        }
        return form.check(listOf("name" to (if (trimmedName.isEmpty()) L("Add their name.") else null), "email" to emailError))
    }

    fun invite() {
        val clinicId = workspace.current?.clinicId ?: return
        pending = true
        formError = null
        scope.launch {
            try {
                val invitation: TeamInvitation = API.post(
                    "/provider-workspaces/$clinicId/invitations",
                    mapOf(
                        "name" to trimmedName,
                        "email" to trimmedEmail,
                        "role" to role,
                        "locationIds" to (if (everywhere) emptyList() else locationIds.value),
                    ),
                )
                sent = invitation
                onSent()
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                formError = errorText(e)
                Toast.error(e)
            } finally {
                pending = false
            }
        }
    }

    fun primary() {
        if (sent != null) { dismiss(); return }
        if (full) {
            Toast.error(L("Every seat on your plan is taken, counting invitations still waiting. Upgrade the plan, cancel an invitation or remove someone first."))
            return
        }
        if (step == InviteStep.review) { invite(); return }
        if (step == InviteStep.who && !checkWho()) return
        steps.getOrNull(index + 1)?.let { step = it }
    }

    fun startOver() {
        step = InviteStep.who; name.value = ""; email.value = ""; role = "support_staff"; locationIds.value = emptyList()
        sent = null; copied = false; formError = null
        form.errors = emptyMap()
    }

    val done = sent
    ProviderModal(
        title = if (done == null) L("Invite someone") else L("Invitation sent"),
        subtitle = done?.let { LF("We emailed %@.", it.email) } ?: L("They join with their own PawMiaw account and the role you choose."),
        systemImage = if (done == null) "mail-plus" else "circle-check",
        accent = ModalAccent.aqua,
        isDirty = done == null && (index > 0 || trimmedName.isNotEmpty() || trimmedEmail.isNotEmpty()),
        form = form,
        primaryTitle = primaryTitle,
        isWorking = pending,
        steps = if (done == null) ModalSteps(steps.map { label(it) }, icons = steps.map { stepIcon(it) }, current = index) else null,
        onBack = if (done == null && index > 0) ({ step = steps[index - 1] }) else null,
        // Once sent, the web's footer: "Invite someone else" beside "Done", no Cancel.
        secondaryActions = if (done == null) emptyList() else listOf(ModalAction(L("Invite someone else")) { startOver() }),
        primaryIcon = if (done == null && step == InviteStep.review) "send" else null,
        // The seats left sit over the buttons on the first step, as on the web.
        footerSummary = if (done == null && step == InviteStep.who && seatsText != null) ({
            Text(seatsText, style = Theme.text(14), color = Theme.inkSoft)
        }) else null,
        showsCancel = done == null,
        onPrimary = { primary() },
        modifier = modifier,
    ) {
        if (done != null) {
            SentView(done, workspaceName, copied) {
                clipboard.setText(AnnotatedString(it))
                copied = true
                Toast.show(L("Message copied."))
            }
        } else {
            if (index > 0) {
                FlowLayout(spacing = 6.dp) {
                    Chip(text = trimmedName, icon = "user-round")
                    Chip(text = trimmedEmail)
                    if (step == InviteStep.place || step == InviteStep.review) Chip(text = TeamRole.label(role), icon = "key-round")
                }
            }
            when (step) {
                InviteStep.who -> {
                    PMTextField(L("Name"), text = name, key = "name", form = form, prompt = L("e.g. Dr. Ana López"), capitalization = KeyboardCapitalization.Words, required = true)
                    PMTextField(L("Email"), text = email, key = "email", form = form, prompt = "ana@example.com", keyboard = KeyboardType.Email, required = true)
                    Text(
                        L("Use the email they will sign in with. Someone who already uses PawMiaw for their own pets joins with that same account."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                    if (full) DangerBox(L("Every seat on your plan is taken, counting invitations still waiting. Upgrade the plan, cancel an invitation or remove someone first."), Theme.inkStrong)
                }
                InviteStep.role -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TeamRole.assignable.forEach { option ->
                            TeamRoleChoice(role = option, selected = role == option) { role = option }
                        }
                    }
                    TeamRoleAccessSummary(role = role, roles = roles)
                    Text(L("The role sets what they can do. You can fine-tune it on their page once they join."), style = Theme.text(14), color = Theme.inkSoft)
                }
                InviteStep.place -> {
                    Text(
                        L("Their Schedule column, registers and “today” follow where they work. Clients are shared by every location."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                    TeamLocationChoiceList(locations = locations, value = locationIds)
                }
                InviteStep.review -> {
                    val box = RoundedCornerShape(Theme.boxRadius)
                    Column(Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, box)) {
                        ReviewRow(L("Who"), title = trimmedName, detail = trimmedEmail) { step = InviteStep.who }
                        RowDivider()
                        ReviewRow(L("Role"), title = TeamRole.label(role), detail = TeamRole.summary(role)) { step = InviteStep.role }
                        if (locations.size >= 2) {
                            RowDivider()
                            ReviewRow(
                                L("Where"),
                                title = whereText,
                                detail = if (role == "admin") L("Admins work at every location.") else null,
                                change = if (asksWhere) ({ step = InviteStep.place }) else null,
                            )
                        }
                    }
                    Text(
                        L("We email them the invitation. It lasts 48 hours and holds a seat on your plan until they accept or it runs out."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                    formError?.let { DangerBox(it, Theme.destructive) }
                }
            }
        }
    }
}

/** A sentence on the red wash with its edge (the plan is full, the server refused). */
@Composable
private fun DangerBox(text: String, ink: androidx.compose.ui.graphics.Color) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.destructive, 6.0), box)
            .border(1.dp, Theme.mix(Theme.destructive, 40.0, Theme.borderSoft), box)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = Theme.text(14, FontWeight.SemiBold),
        color = ink,
    )
}

@Composable
private fun ReviewRow(label: String, title: String, detail: String?, change: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label.uppercase(appLocale),
            Modifier.width(64.dp).padding(top = 2.dp),
            style = brandText(10.9, FontWeight.ExtraBold).tracking(1.3),
            color = Theme.inkSoft,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            if (detail != null) Text(detail, style = Theme.text(14), color = Theme.inkSoft)
        }
        if (change != null) {
            Text(L("Change"), Modifier.plainButton(onClick = change), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
        }
    }
}

// MARK: Sent

@Composable
private fun SentView(sent: TeamInvitation, workspaceName: String, copied: Boolean, copy: (String) -> Unit) {
    val message = TeamText.invitationMessage(name = sent.inviteeName, email = sent.email, workspace = workspaceName)
    val box = RoundedCornerShape(Theme.boxRadius)
    val done = ProviderState.done.color
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(done, 9.0), box)
            .border(1.dp, Theme.mix(done, 45.0, Theme.borderSoft), box)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(LF("%@ has 48 hours to accept", sent.inviteeName.nonEmpty ?: sent.email), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
        Text(
            LF("The email has a button to accept. With a PawMiaw account under %1\$@ they sign in; without one they make it with that same email. Then they join as %2\$@.", sent.email, TeamRole.label(sent.role)),
            style = Theme.text(14),
            color = Theme.inkSoft,
        )
    }
    ModalSection(title = L("Tell them yourself too"), subtitle = L("Emails get lost. This says the same, to send by WhatsApp or however you talk.")) {
        SelectionContainer {
            Text(
                message,
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, box)
                    .padding(12.dp),
                style = Theme.text(14),
                color = Theme.inkStrong,
            )
        }
        PMButton(
            if (copied) L("Copied") else L("Copy message"),
            systemImage = if (copied) "check" else "copy",
            variant = ButtonVariant.outline,
            size = ButtonSize.modal,
            fullWidth = true,
        ) { copy(message) }
    }
    Text(L("Until they accept, the invitation waits in Invitations, where you can send it again or cancel it."), style = Theme.text(14), color = Theme.inkSoft)
}

/** A role's access area by area ("Agenda y visitas · Todo"), from what the server says the role gives. */
@Composable
fun TeamRoleAccessSummary(role: String, roles: List<TeamRolePermissions>?, modifier: Modifier = Modifier) {
    val defaults = TeamPermissions.roleDefaults(roles, role) ?: return
    val groups = TeamPermissions.byGroup(defaults)
    val some = groups.filter { it.second.isNotEmpty() }
    val none = groups.filter { it.second.isEmpty() }
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.surfaceMuted, 45.0), box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(LF("What %@ can do", TeamRole.label(role)), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        FlowLayout(spacing = 6.dp) {
            some.forEach { (group, on, _) ->
                Row(
                    Modifier
                        .background(Theme.surface, CapsuleShape)
                        .border(1.dp, Theme.borderSoft, CapsuleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val style = Theme.text(12, FontWeight.SemiBold)
                    Text(L(group.title), style = style, color = Theme.inkStrong)
                    Text(if (on.size == group.permissions.size) L("Everything") else LF("%1\$d of %2\$d", on.size, group.permissions.size), style = style, color = Theme.inkSoft)
                }
            }
        }
        if (none.isNotEmpty()) {
            val strong = Theme.inkStrong
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(L("No access to:")) }
                    append(" " + none.joinToString(", ") { L(it.first.title) })
                },
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
        }
    }
}

/**
 * A role to pick (web `RoleChoice`): its icon on the role's hue, the name and
 * what it does, a round tick; blue border and a blue wash when chosen.
 */
@Composable
fun TeamRoleChoice(
    role: String,
    selected: Boolean,
    disabledReason: String? = null,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (disabledReason != null) 0.6f else 1f)
            .plainButton(onClick = onSelect)
            .semantics { this.selected = selected }
            .background(if (selected) Theme.wash(Theme.blue, 8.0) else Theme.surface, box)
            .border(2.dp, if (selected) Theme.blue else Theme.borderSoft, box)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).background(Theme.wash(TeamRole.hue(role).color, 30.0), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(TeamRole.icon(role), 16.dp, tint = Theme.inkStrong)
        }
        Column(Modifier.weight(1f)) {
            Text(TeamRole.label(role), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            Text(TeamRole.summary(role), style = Theme.text(14), color = Theme.inkSoft)
            if (disabledReason != null) {
                Text(disabledReason, Modifier.padding(top = 4.dp), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
            }
        }
        Tick(on = selected, style = Tick.Style.radio, modifier = Modifier.padding(top = 4.dp))
    }
}
