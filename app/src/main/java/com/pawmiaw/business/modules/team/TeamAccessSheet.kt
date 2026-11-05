package com.pawmiaw.business.team

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  TeamAccessSheet.kt (iOS TeamAccessSheet.swift)
//
//  Change someone's role and, when the role is nearly right, single
//  permissions just for them (web: staff-access-sheet.tsx). Every permission
//  starts where the role puts it; one moved away is marked and can be put
//  back. The server refuses what the sheet greys out: more than the person
//  saving has, and "Money settings and the plan" by anyone but the owner.
//

@Composable
fun TeamAccessSheet(
    member: TeamMember,
    roles: List<TeamRolePermissions>?,
    /** What the person saving can do: nobody hands out more than they have. */
    actorPermissions: List<String>,
    actorManagesPlan: Boolean,
    modifier: Modifier = Modifier,
    onSave: suspend (role: String, allow: List<String>, deny: List<String>) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    // iOS `.onAppear { start() }`: the role and what they can do now; the areas with a change open.
    var role by remember { mutableStateOf(member.roleKey) }
    var wanted by remember {
        mutableStateOf(
            TeamPermissions.roleDefaults(roles, member.roleKey)?.let { TeamPermissions.effective(defaults = it, allow = member.allow, deny = member.deny) }
                ?: member.effective
        )
    }
    val query = remember { mutableStateOf("") }
    var openGroups by remember {
        val changed = (member.allow + member.deny).toSet()
        mutableStateOf(TeamPermissions.groups.filter { group -> group.permissions.any { changed.contains(it.key) } }.map { it.key }.toSet())
    }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val name = member.shownName
    val defaults = TeamPermissions.roleDefaults(roles, role)
    val mine = actorPermissions.toSet()
    val had = member.effective.toSet()
    val overrides = defaults?.let { TeamPermissions.overrides(defaults = it, wanted = wanted) } ?: (member.allow to member.deny)
    val changedCount = overrides.first.size + overrides.second.size
    val dirty = role != member.roleKey || overrides.first.toSet() != member.allow.toSet() || overrides.second.toSet() != member.deny.toSet()
    val teamPowers = role != "admin" && overrides.first.any { TeamPermissions.teamAdmin.contains(it) }

    fun chooseRole(next: String) {
        // What was changed for them stays changed under the new role.
        val nextDefaults = TeamPermissions.roleDefaults(roles, next)
        if (defaults != null && nextDefaults != null) {
            val current = overrides
            wanted = TeamPermissions.effective(defaults = nextDefaults, allow = current.first, deny = current.second)
        }
        role = next
    }

    fun roleBlocked(option: String): Boolean {
        if (option == member.roleKey) return false
        val optionDefaults = TeamPermissions.roleDefaults(roles, option) ?: return false
        return optionDefaults.any { !mine.contains(it) && !had.contains(it) }
    }

    /** Why a permission can't be turned on by this person, if it can't. */
    fun blocked(permission: String): String? {
        if (wanted.contains(permission) || had.contains(permission)) return null
        if (permission == "billing.manage" && !actorManagesPlan) return L("Only the owner turns this on for one person: it also lets them change the plan.")
        if (!mine.contains(permission)) return L("You don’t have this permission, so you can’t give it.")
        return null
    }

    fun toggle(permission: String, on: Boolean) {
        wanted = if (on) (if (wanted.contains(permission)) wanted else wanted + permission) else wanted.filter { it != permission }
    }

    fun save() {
        if (!dirty) { dismiss(); return }
        pending = true
        error = null
        val current = overrides
        scope.launch {
            try {
                onSave(role, current.first, current.second)
                Toast.show(LF("Access saved for %@.", name))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
                Toast.error(e)
            } finally {
                pending = false
            }
        }
    }

    ProviderModal(
        title = LF("Access for %@", name),
        subtitle = L("Pick the role that fits. Fine-tune only when the role is nearly right."),
        systemImage = "shield-check",
        accent = ModalAccent.butter,
        isDirty = dirty,
        primaryTitle = L("Save access"),
        isWorking = pending,
        primaryIcon = "shield-check",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        ModalSection(title = L("Role")) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamRole.assignable.forEach { option ->
                    val beyond = roleBlocked(option)
                    TeamRoleChoice(
                        role = option,
                        selected = role == option,
                        disabledReason = if (beyond) L("More access than your own, so you can’t give it.") else null,
                    ) {
                        if (beyond) {
                            Toast.error(L("More access than your own, so you can’t give it."))
                        } else {
                            chooseRole(option)
                        }
                    }
                }
            }
        }
        ModalSection(title = LF("Permissions for %@", name), subtitle = L("Each one starts where the role puts it. Change only what this person needs differently.")) {
            if (defaults != null) {
                Permissions(
                    defaults = defaults,
                    query = query,
                    wanted = wanted,
                    openGroups = openGroups,
                    changedCount = changedCount,
                    teamPowers = teamPowers,
                    pending = pending,
                    blocked = { blocked(it) },
                    resetToRole = { wanted = defaults },
                    toggleGroup = { key -> openGroups = if (openGroups.contains(key)) openGroups - key else openGroups + key },
                    toggle = { key, on -> toggle(key, on) },
                )
            } else {
                NoticeCard(
                    tone = NoticeCard.Tone.warning,
                    message = L("What each role gives couldn’t load, so only the role can be changed now. Close this and try again in a moment."),
                    icon = "triangle-alert",
                )
            }
        }
        error?.let { text ->
            val box = RoundedCornerShape(Theme.boxRadius)
            Text(
                text,
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.destructive, 6.0), box)
                    .border(1.dp, Theme.mix(Theme.destructive, 40.0, Theme.borderSoft), box)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = Theme.text(14, FontWeight.SemiBold),
                color = Theme.destructive,
            )
        }
    }
}

@Composable
private fun Permissions(
    defaults: List<String>,
    query: androidx.compose.runtime.MutableState<String>,
    wanted: List<String>,
    openGroups: Set<String>,
    changedCount: Int,
    teamPowers: Boolean,
    pending: Boolean,
    blocked: (String) -> String?,
    resetToRole: () -> Unit,
    toggleGroup: (String) -> Unit,
    toggle: (String, Boolean) -> Unit,
) {
    val base = defaults.toSet()
    val searching = query.value.trim().isNotEmpty()
    val groups = TeamPermissions.search(query.value)
    val box = RoundedCornerShape(Theme.boxRadius)
    SearchField(prompt = L("A permission, like “refund”"), text = query, shape = SearchField.Shape.field)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (changedCount == 0) {
            Text(L("Exactly what the role gives."), style = Theme.text(14), color = Theme.inkSoft)
        } else {
            Chip(text = LP(changedCount, "1 changed", "%d changed"), tone = Chip.Tone.custom(Theme.lime), percent = 100.0, ink = Theme.inkOnBright, weight = FontWeight.ExtraBold)
            Row(
                Modifier
                    .plainButton(onClick = resetToRole)
                    .height(32.dp)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("rotate-ccw", 14.dp, tint = Theme.blue)
                Text(L("Back to the role’s access"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            }
        }
    }
    if (teamPowers) {
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            message = L("With these, someone who isn’t an Admin can change permissions and suspend people. Inviting, changing a role and removing someone still need an Admin or the Owner."),
            icon = "triangle-alert",
        )
    }
    if (groups.isEmpty()) {
        Text(
            LF("No permission matches “%@”.", query.value.trim()),
            Modifier
                .fillMaxWidth()
                .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            style = Theme.text(14),
            color = Theme.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
    groups.forEach { group ->
        val on = group.permissions.count { wanted.contains(it.key) }
        val groupChanged = group.permissions.count { base.contains(it.key) != wanted.contains(it.key) }
        val groupOpen = searching || openGroups.contains(group.key)
        val turn by animateFloatAsState(if (groupOpen) 0f else -90f, tween(200, easing = EaseOut), label = "group")
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.surface, box)
                .clip(box)
                .border(1.dp, Theme.borderSoft, box),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton(enabled = !searching) { toggleGroup(group.key) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
                Text(L(group.title), Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (groupChanged > 0) Chip(text = LP(groupChanged, "1 changed", "%d changed"), tone = Chip.Tone.custom(Theme.lime), percent = 100.0, ink = Theme.inkOnBright, weight = FontWeight.ExtraBold)
                Text(LF("%1\$d of %2\$d", on, group.permissions.size), style = Theme.text(12, FontWeight.SemiBold).monospacedDigit(), color = Theme.inkSoft)
            }
            AnimatedVisibility(
                visible = groupOpen,
                enter = fadeIn(tween(200, easing = EaseOut)) + expandVertically(tween(200, easing = EaseOut)),
                exit = fadeOut(tween(200, easing = EaseOut)) + shrinkVertically(tween(200, easing = EaseOut)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    group.permissions.forEach { permission ->
                        RowDivider()
                        PermissionRow(permission, base, wanted, pending, blocked, toggle)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    permission: TeamPermissionInfo,
    base: Set<String>,
    wanted: List<String>,
    pending: Boolean,
    blocked: (String) -> String?,
    toggle: (String, Boolean) -> Unit,
) {
    val isOn = wanted.contains(permission.key)
    val fromRole = base.contains(permission.key)
    val changed = isOn != fromRole
    val why = if (isOn) null else blocked(permission.key)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (changed) Theme.wash(Theme.lime, 9.0) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowLayout(spacing = 8.dp) {
                Text(L(permission.label), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                if (changed) {
                    Chip(text = if (isOn) L("Turned on for them") else L("Turned off for them"), tone = Chip.Tone.custom(Theme.lime), percent = 100.0, ink = Theme.inkOnBright, weight = FontWeight.ExtraBold)
                } else {
                    Text(if (fromRole) L("From the role") else L("Not in the role"), style = Theme.text(12, FontWeight.Medium), color = Theme.inkSoft)
                }
            }
            Text(L(permission.description), style = Theme.text(12), color = Theme.inkSoft)
            if (why != null) Text(why, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
        }
        if (changed) {
            Box(
                Modifier
                    .size(32.dp)
                    .plainButton(label = LF("Put “%@” back as the role has it", L(permission.label))) { toggle(permission.key, fromRole) },
                contentAlignment = Alignment.Center,
            ) {
                Glyph("rotate-ccw", 14.dp, tint = Theme.inkSoft)
            }
        }
        val label = L(permission.label)
        PMSwitchStyle(
            isOn = stateOf({ isOn }, { toggle(permission.key, it) }),
            modifier = Modifier.padding(top = 6.dp).semantics { contentDescription = label },
            enabled = !(pending || why != null),
        )
    }
}
