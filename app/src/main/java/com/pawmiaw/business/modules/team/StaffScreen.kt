package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.Collator

//
//  StaffScreen.kt (iOS StaffScreen.swift)
//
//  The team (web: routes/provider.staff.tsx): who works here and whose access
//  is paused, their role and what was changed for them, where they work and
//  how many pets they take at once, when they were last here; the
//  invitations still waiting (send again, copy a message, cancel); and what
//  each role can do. The numbers on top are filters.
//

private enum class StaffView { people, invitations, roles }

@Composable
fun StaffScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val wide = LocalSizeClass.current == SizeClass.regular
    val nav = LocalNavigator.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var members by remember { mutableStateOf<List<TeamMember>>(emptyList()) }
    var invitations by remember { mutableStateOf<List<TeamInvitation>>(emptyList()) }
    var roles by remember { mutableStateOf<List<TeamRolePermissions>?>(null) }
    var membersLoaded by remember { mutableStateOf(false) }
    var invitationsLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(StaffView.people) }
    val query = remember { mutableStateOf("") }
    var roleFilter by remember { mutableStateOf<String?>(null) }
    var visitsOnly by remember { mutableStateOf(false) }
    var suspendedOnly by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<TeamOpenInvitation?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var shownRole by remember { mutableStateOf("admin") }

    val canInvite = workspace.can(Permission.staffInvite)

    // MARK: Loading

    suspend fun load() {
        val clinicId = workspace.current?.clinicId ?: return
        if (!workspace.can(Permission.staffView) || workspace.inSetup) return
        try {
            members = API.get("/provider-workspaces/$clinicId/members")
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (members.isEmpty()) error = errorText(e)
        }
        membersLoaded = true
        runCatching { API.get<List<TeamInvitation>>("/provider-workspaces/$clinicId/invitations") }.getOrNull()?.let { invitations = it }
        invitationsLoaded = true
        if (roles == null) {
            val answer = runCatching { API.get<TeamRolesResponse>("/provider-workspace-roles") }.getOrNull()
            roles = answer?.roles
        }
    }

    // MARK: Numbers

    val memberEmails = members.mapNotNull { it.email?.trim()?.lowercase() }.filter { it.isNotEmpty() }.toSet()
    val open = TeamText.openInvitations(invitations, memberEmails = memberEmails)
    val waitingEmails = open.filter { !it.expired }.map { it.invitation.email.trim().lowercase() }.toSet()
    val waiting = waitingEmails.size
    val ranOut = open.size - waiting
    val takingVisits = members.filter { it.takesVisits }
    val suspended = members.filter { it.isSuspended }
    val seatsAtOnce = takingVisits.sumOf { it.atOnce }
    val seatLimit = workspace.current?.seatLimit
    /** The plan counts invitations still waiting, and suspended people keep their seat. */
    val seatsUsed = members.size + waiting
    val seatsLeft = seatLimit?.let { maxOf(0, it - seatsUsed) }
    val roleCounts = members.groupingBy { it.roleKey }.eachCount()
    val needle = query.value.trim().lowercase()
    val filtered = roleFilter != null || visitsOnly || suspendedOnly || needle.isNotEmpty()
    val collator = remember(appLocale) { Collator.getInstance(appLocale) }

    val shownMembers = members.filter { member ->
        if (roleFilter != null && member.roleKey != roleFilter) return@filter false
        if (visitsOnly && !member.takesVisits) return@filter false
        if (suspendedOnly && !member.isSuspended) return@filter false
        if (needle.isEmpty()) return@filter true
        listOf(member.shownName, member.userName, member.email, member.professionalTitle, member.specialty, TeamRole.label(member.role))
            .any { it?.lowercase()?.contains(needle) ?: false }
    }.sortedWith { a, b ->
        when {
            a.isSuspended != b.isSuspended -> if (!a.isSuspended) -1 else 1
            a.isMe != b.isMe -> if (a.isMe) -1 else 1
            (a.roleKey == "owner") != (b.roleKey == "owner") -> if (a.roleKey == "owner") -1 else 1
            else -> collator.compare(a.shownName, b.shownName)
        }
    }

    val shownInvitations = open.filter { item ->
        if (roleFilter != null && item.invitation.role != roleFilter) return@filter false
        if (needle.isEmpty()) return@filter true
        listOf(item.invitation.inviteeName, item.invitation.email).any { it?.lowercase()?.contains(needle) ?: false }
    }

    fun showEveryone() {
        query.value = ""; roleFilter = null; visitsOnly = false; suspendedOnly = false
    }

    fun copyMessage(item: TeamOpenInvitation) {
        clipboard.setText(AnnotatedString(TeamText.invitationMessage(name = item.invitation.inviteeName, email = item.invitation.email, workspace = workspace.current?.name ?: "PawMiaw")))
        Toast.show(LF("Message copied. Send it to %@.", item.invitation.email))
    }

    fun sendAgain(item: TeamOpenInvitation) {
        val clinicId = workspace.current?.clinicId ?: return
        busyId = item.invitation.id
        scope.launch {
            try {
                API.send("POST", "/provider-workspaces/$clinicId/invitations/${item.invitation.id}/resend")
                Toast.show(LF("Sent again to %@. They have 48 more hours.", item.invitation.email))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                busyId = null
            }
        }
    }

    suspend fun cancel(item: TeamOpenInvitation) {
        val clinicId = workspace.current?.clinicId ?: return
        API.send("POST", "/provider-workspaces/$clinicId/invitations/${item.invitation.id}/cancel")
        Toast.show(if (item.expired) L("Invitation removed.") else L("Invitation cancelled."))
        load()
    }

    // MARK: Page

    @Composable
    fun FootLine(content: @Composable () -> Unit) {
        Box(
            Modifier
                .fillMaxWidth()
                .teamEdgeRule(Theme.borderSoft, top = true)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { content() }
    }

    @Composable
    fun LoadFailed(title: String) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = Theme.heading(20), color = Theme.inkStrong, textAlign = TextAlign.Center)
            Text(L("Check your connection and try again."), style = Theme.text(14), color = Theme.inkSoft, textAlign = TextAlign.Center)
            PMButton(L("Try again"), systemImage = "refresh-cw", variant = ButtonVariant.outline, size = ButtonSize.compact, modifier = Modifier.padding(top = 12.dp)) {
                scope.launch { load() }
            }
        }
    }

    @Composable
    fun PeopleList() {
        if (!membersLoaded) {
            TeamProgress(minHeight = 200.dp)
        } else if (error != null && members.isEmpty()) {
            LoadFailed(L("The team couldn’t load."))
        } else if (shownMembers.isEmpty()) {
            EmptyState(
                systemImage = if (filtered) "search" else "users-round",
                title = if (filtered) (if (needle.isEmpty()) L("Nobody here with these filters") else LF("Nobody matches “%@”", query.value.trim())) else L("It’s just you so far"),
                message = if (filtered) L("Try another name, or show everyone.") else L("Invite the people you work with. Each joins with their own account and a role."),
                actionTitle = if (filtered) L("Show everyone") else (if (canInvite) L("Invite someone") else null),
                action = if (filtered) ({ showEveryone() }) else (if (canInvite) ({ inviting = true }) else null),
                accent = Theme.pink,
            )
        } else {
            shownMembers.forEach { member ->
                if (member.id != shownMembers.firstOrNull()?.id) RowDivider()
                TeamMemberRow(
                    member = member,
                    locations = workspace.activeLocations,
                    modifier = Modifier.plainButton { nav.push(ProviderRoute.staffMember(member.id)) },
                )
            }
            FootLine {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (shownMembers.size == members.size) LP(members.size, "1 person", "%d people") else LF("%1\$d of %2\$d people", shownMembers.size, members.size),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                    if (waiting > 0) {
                        Text("·", style = Theme.text(14), color = Theme.inkSoft)
                        Text(
                            LP(waiting, "1 invitation waiting", "%d invitations waiting"),
                            Modifier.plainButton { view = StaffView.invitations },
                            style = Theme.text(14, FontWeight.SemiBold),
                            color = Theme.blue,
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun InvitationRow(item: TeamOpenInvitation) {
        val invitation = item.invitation
        val locations = workspace.activeLocations
        val place = if (locations.size >= 2) TeamText.locationNames(role = invitation.role, ids = invitation.locationIds, locations = locations) else null
        val whenText = DateText.parse(invitation.expiresAt)?.let { TeamText.relative(it) } ?: ""
        val tone = if (item.expired) Theme.mango else Theme.aqua
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = item.shownName, size = 40.dp, tint = tone)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(item.shownName, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(invitation.email, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                teamRoleChip(invitation.role)
                if (place != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("map-pin", 12.dp, tint = Theme.inkSoft)
                        Text(place, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier
                        .height(24.dp)
                        .background(Theme.wash(tone, 18.0), CapsuleShape)
                        .border(1.dp, Theme.mix(tone, 55.0, Theme.borderSoft), CapsuleShape)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(tone, CircleShape))
                    Text(if (item.expired) L("Ran out") else L("Waiting"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong)
                }
                Text(
                    if (item.expired) LF("Ran out %@", whenText) else LF("Ends %@", whenText),
                    style = Theme.text(14, if (item.expired) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (item.expired) Theme.destructive else Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                invitation.inviterName.nonEmpty?.let { inviter ->
                    Text(LF("Invited by %@", inviter), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canInvite) {
                    PMButton(L("Send again"), systemImage = "send", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busyId == invitation.id) { sendAgain(item) }
                }
                AppMenu(label = { IconCircleLabel(bordered = false) }, accessibilityLabel = LF("More for %@", item.shownName)) {
                    item(L("Copy a message for them"), icon = "copy") { copyMessage(item) }
                    if (canInvite) {
                        divider()
                        item(if (item.expired) L("Remove from the list") else L("Cancel invitation"), icon = "trash-2", destructive = true) { cancelTarget = item }
                    }
                }
            }
        }
    }

    @Composable
    fun InvitationsList() {
        if (!invitationsLoaded) {
            TeamProgress(minHeight = 200.dp)
        } else if (shownInvitations.isEmpty()) {
            EmptyState(
                systemImage = if (filtered) "search" else "mail-plus",
                title = if (filtered) L("No invitation matches") else L("No invitations waiting"),
                message = if (filtered) L("Try another name, or show every invitation.") else L("When you invite someone, they wait here until they accept. Invitations last 48 hours."),
                actionTitle = if (filtered) L("Show every invitation") else (if (canInvite) L("Invite someone") else null),
                action = if (filtered) ({ query.value = ""; roleFilter = null }) else (if (canInvite) ({ inviting = true }) else null),
                accent = Theme.pink,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.aqua, 8.0))
                    .teamEdgeRule(Theme.borderSoft)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph("mail-check", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                Text(
                    L("Each person got an email with a button to accept. They join by signing in — or making their account — with that same email. An invitation holds a seat for 48 hours."),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            shownInvitations.forEach { item ->
                if (item.id != shownInvitations.firstOrNull()?.id) RowDivider()
                InvitationRow(item)
            }
            FootLine {
                Text(
                    if (shownInvitations.size == open.size) LP(open.size, "1 invitation", "%d invitations") else LF("%1\$d of %2\$d invitations", shownInvitations.size, open.size),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
        }
    }

    @Composable
    fun RoleCard(role: String) {
        val count = roleCounts[role] ?: 0
        val box = RoundedCornerShape(Theme.boxRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Theme.borderSoft, box)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(Theme.wash(TeamRole.hue(role).color, 30.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(TeamRole.icon(role), 14.dp, tint = Theme.inkStrong)
                }
                Text(TeamRole.label(role), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(TeamRole.summary(role), Modifier.fillMaxWidth(), style = Theme.text(12), color = Theme.inkSoft)
            if (count > 0) {
                Text(
                    LP(count, "1 person", "%d people"),
                    Modifier
                        .padding(top = 2.dp)
                        .plainButton { query.value = ""; view = StaffView.people; roleFilter = role; visitsOnly = false; suspendedOnly = false },
                    style = Theme.text(12, FontWeight.Bold),
                    color = Theme.blue,
                )
            } else {
                Text(L("Nobody yet"), Modifier.padding(top = 2.dp), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }

    /** Yes in the app's green, no as a quiet dash — never by colour alone. */
    @Composable
    fun Mark(on: Boolean, modifier: Modifier) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (on) {
                Box(Modifier.size(24.dp).background(Theme.wash(ProviderState.done.color, 30.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph("check", 14.dp, tint = Theme.inkStrong)
                }
            } else {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Glyph("minus", 14.dp, tint = Theme.inkSoft.copy(alpha = 0.55f))
                }
            }
        }
    }

    @Composable
    fun RolesPanel() {
        val loadedRoles = roles
        if (loadedRoles == null && !membersLoaded) {
            TeamProgress(minHeight = 200.dp)
        } else if (loadedRoles != null) {
            val has = loadedRoles.associate { it.role to it.permissions.toSet() }
            val groups = TeamPermissions.search(query.value)
            val columns = if (wide) TeamRole.all else listOf(shownRole)
            Column(
                Modifier
                    .fillMaxWidth()
                    .teamEdgeRule(Theme.borderSoft)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    L("Five roles come with PawMiaw. A role is where each person starts; what one person needs differently is turned on or off on their page."),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
                TeamAdaptiveGrid(items = TeamRole.all, minimum = 220.dp, spacing = 8.dp) { role -> RoleCard(role) }
            }
            if (!wide) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .teamEdgeRule(Theme.borderSoft)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TeamRole.all.forEach { role ->
                        FilterChip(title = TeamRole.label(role), isSelected = shownRole == role, tone = TeamRole.hue(role).color, icon = TeamRole.icon(role)) { shownRole = role }
                    }
                }
            }
            if (groups.isEmpty()) {
                EmptyState(
                    systemImage = "search",
                    title = LF("No permission matches “%@”.", query.value.trim()),
                    message = L("Try another word, like “refund”, “records” or “hours”."),
                    actionTitle = L("Show every permission"),
                    action = { query.value = "" },
                    accent = Theme.pink,
                )
            } else {
                if (columns.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .teamEdgeRule(Theme.borderSoft)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(L("Permission").uppercase(appLocale), Modifier.weight(1f), style = brandText(10.6, FontWeight.ExtraBold).tracking(1.3), color = Theme.inkSoft)
                        columns.forEach { role ->
                            Text(TeamRole.label(role), Modifier.width(88.dp), style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong, textAlign = TextAlign.Center)
                        }
                    }
                }
                groups.forEach { group ->
                    Text(
                        L(group.title).uppercase(appLocale),
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.wash(Theme.surfaceMuted, 55.0))
                            .teamEdgeRule(Theme.borderSoft)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        style = brandText(11.2, FontWeight.ExtraBold).tracking(1.3),
                        color = Theme.inkSoft,
                    )
                    group.permissions.forEach { permission ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .teamEdgeRule(Theme.borderSoft)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = if (columns.size > 1) Alignment.CenterVertically else Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(L(permission.label), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                                Text(L(permission.description), style = Theme.text(12), color = Theme.inkSoft)
                            }
                            Spacer(Modifier.width(4.dp))
                            columns.forEach { role ->
                                val on = has[role]?.contains(permission.key) ?: false
                                val said = if (on) LF("%@: yes", TeamRole.label(role)) else LF("%@: no", TeamRole.label(role))
                                Mark(on, Modifier.width(if (columns.size > 1) 88.dp else 24.dp).semantics { contentDescription = said })
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(L("The roles couldn’t load."), style = Theme.heading(20), color = Theme.inkStrong)
                Text(L("Check your connection and try again."), style = Theme.text(14), color = Theme.inkSoft)
                PMButton(L("Try again"), systemImage = "refresh-cw", variant = ButtonVariant.outline, size = ButtonSize.compact, modifier = Modifier.padding(top = 12.dp)) {
                    scope.launch {
                        val answer = runCatching { API.get<TeamRolesResponse>("/provider-workspace-roles") }.getOrNull()
                        roles = answer?.roles
                    }
                }
            }
        }
    }

    @Composable
    fun Header() {
        val full = seatLimit != null && seatsLeft == 0
        val stats = listOf(
            ModuleStat(
                "people", label = L("People"), value = if (membersLoaded) "${members.size}" else "—",
                hint = if (suspended.isEmpty()) L("Everyone on the team") else LP(suspended.size, "1 suspended", "%d suspended"),
                icon = "users-round", accent = Theme.pink,
                active = view == StaffView.people && roleFilter == null && !visitsOnly && !suspendedOnly,
                action = { view = StaffView.people; roleFilter = null; visitsOnly = false; suspendedOnly = false },
            ),
            ModuleStat(
                "visits", label = L("Take visits"), value = if (membersLoaded) "${takingVisits.size}" else "—",
                hint = if (takingVisits.isEmpty()) L("Nobody starts visits yet") else (if (seatsAtOnce == 1) L("One pet at a time") else LF("%d pets at once in all", seatsAtOnce)),
                icon = "calendar-check", accent = Theme.lime,
                active = view == StaffView.people && visitsOnly,
                action = { val on = !(visitsOnly && view == StaffView.people); view = StaffView.people; suspendedOnly = false; visitsOnly = on },
            ),
            ModuleStat(
                "invitations", label = L("Invitations"), value = if (invitationsLoaded) "$waiting" else "—",
                hint = if (ranOut > 0) LP(ranOut, "1 ran out", "%d ran out") else (if (waiting > 0) L("Waiting to join") else L("Nobody waiting")),
                icon = "mail-plus", accent = Theme.aqua, attention = ranOut > 0,
                active = view == StaffView.invitations,
                action = { view = if (view == StaffView.invitations) StaffView.people else StaffView.invitations; visitsOnly = false; suspendedOnly = false },
            ),
            ModuleStat(
                "seats", label = L("Seats"), value = seatLimit?.let { LF("%1\$d of %2\$d", seatsUsed, it) } ?: "$seatsUsed",
                hint = if (seatLimit == null) L("No limit on your plan") else (if (full) L("Plan full · invitations count") else LP(seatsLeft ?: 0, "1 free · invitations count", "%d free · invitations count")),
                icon = "armchair", accent = Theme.blue, attention = full,
                action = if (full && (workspace.current?.canManagePlan ?: false)) ({ nav.push(ProviderRoute.section(ProviderSection.billing)) }) else null,
            ),
        )
        ModuleHeader(
            icon = ProviderSection.staff.headerIcon,
            title = L("Staff"),
            subtitle = L("Who works here, what each person can do, and where and when they work."),
            accent = ProviderSection.staff.headerAccent,
            stats = stats,
            storageKey = "staff",
            actions = if (canInvite) ({ PMButton(L("Invite someone"), systemImage = "user-plus") { inviting = true } }) else null,
        )
    }

    /** People | Invitations | Roles, the search, and the filters (web `StaffToolbar`). */
    @Composable
    fun Toolbar() {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(
                options = listOf(
                    Segmented.Option(StaffView.people, L("People"), icon = "users-round"),
                    Segmented.Option(StaffView.invitations, L("Invitations"), icon = "mail-plus"),
                    Segmented.Option(StaffView.roles, L("Roles"), icon = "key-round"),
                ),
                selection = stateOf({ view }, { next ->
                    view = next
                    if (next != StaffView.people) { visitsOnly = false; suspendedOnly = false }
                }),
                look = Segmented.Look.solid,
                label = L("Show"),
            )
            SearchField(
                prompt = when (view) {
                    StaffView.people -> L("Name, email or title")
                    StaffView.invitations -> L("Name or email")
                    StaffView.roles -> L("A permission, like “refund”")
                },
                text = query,
            )
            if (view != StaffView.roles) {
                val shownRoles = TeamRole.all.filter { (roleCounts[it] ?: 0) > 0 || roleFilter == it }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shownRoles.forEach { role ->
                        FilterChip(
                            title = TeamRole.label(role),
                            count = if (view == StaffView.people) roleCounts[role] ?: 0 else null,
                            isSelected = roleFilter == role,
                            tone = TeamRole.hue(role).color,
                            icon = TeamRole.icon(role),
                        ) { roleFilter = if (roleFilter == role) null else role }
                    }
                    if (view == StaffView.people) {
                        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(Theme.borderSoft))
                        FilterChip(title = L("Take visits"), count = takingVisits.size, isSelected = visitsOnly, tone = Hue.mint.color, icon = "calendar-check") { visitsOnly = !visitsOnly }
                        if (suspended.isNotEmpty() || suspendedOnly) {
                            FilterChip(title = L("Suspended"), count = suspended.size, isSelected = suspendedOnly, tone = ProviderState.closed.color, icon = "circle-pause") { suspendedOnly = !suspendedOnly }
                        }
                    }
                }
            }
        }
    }

    providerPage(L("Staff"), root = true, modifier = modifier) {
        ReloadsOnScope { load() }
        if (!workspace.can(Permission.staffView)) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see staff.")) }
        } else if (workspace.inSetup) {
            PageScroll {
                ModuleHeader(
                    icon = ProviderSection.staff.headerIcon,
                    title = L("Staff"),
                    subtitle = L("Who works here and what each person can do."),
                    accent = ProviderSection.staff.headerAccent,
                    storageKey = "staff",
                )
                EmptyState(
                    systemImage = "building-2",
                    title = L("Finish workspace setup before adding your team."),
                    message = L("The team belongs to a workspace, so its basics need to be saved first."),
                    accent = Theme.pink,
                    panel = true,
                )
            }
        } else {
            PageScroll(refresh = { load() }) {
                Header()
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Toolbar()
                    // The list's card: 2 pt border, rows edge to edge, a count line at its foot.
                    Column(Modifier.fillMaxWidth().listCard(ListCardStyle.panel)) {
                        when (view) {
                            StaffView.people -> PeopleList()
                            StaffView.invitations -> InvitationsList()
                            StaffView.roles -> RolesPanel()
                        }
                    }
                }
            }
        }

        if (inviting) {
            AppSheet(onDismissRequest = { inviting = false }) {
                TeamInviteSheet(
                    workspaceName = workspace.current?.name ?: "PawMiaw",
                    seatsLeft = seatsLeft,
                    memberEmails = memberEmails,
                    waitingEmails = waitingEmails,
                    locations = workspace.activeLocations,
                    roles = roles,
                ) { load() }
            }
        }
        cancelTarget?.let { item ->
            AppSheet(onDismissRequest = { cancelTarget = null }) {
                TeamConfirmSheet(
                    title = if (item.expired) L("Remove this invitation?") else L("Cancel this invitation?"),
                    message = if (item.expired) L("It already ran out; this clears it from the list.") else L("The link in their email stops working, and the seat it held is free. You can invite them again later."),
                    confirmTitle = if (item.expired) L("Remove invitation") else L("Cancel invitation"),
                    cancelTitle = L("Keep it"),
                    action = { cancel(item) },
                ) {
                    TeamPersonLine(name = item.shownName, detail = listOf(TeamRole.label(item.invitation.role), item.invitation.email).joinToString(" · "))
                }
            }
        }
    }
}

/** SwiftUI `.grayscale(1)`: the view drawn without colour. */
private fun Modifier.grayscale(on: Boolean): Modifier = if (!on) this else drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}

/**
 * One person of the team (web `MemberRow`, phone layout): who, their role and
 * access, how they take visits and where, when they were here; the row opens them.
 */
@Composable
fun TeamMemberRow(member: TeamMember, locations: List<ProviderLocation>, modifier: Modifier = Modifier) {
    val suspended = member.isSuspended
    val place = if (locations.size >= 2) (TeamText.locationNames(role = member.role, ids = member.locationIds, locations = locations) ?: L("Every location")) else null
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                name = member.shownName,
                imageURL = member.image,
                size = 40.dp,
                tint = TeamRole.hue(member.role).color,
                modifier = Modifier.alpha(if (suspended) 0.6f else 1f).grayscale(suspended),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowLayout(spacing = 8.dp) {
                    Text(member.shownName, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (member.isMe) Chip(text = L("You"), tone = Chip.Tone.custom(Theme.blue), percent = 14.0, ink = Theme.inkOnBright)
                    if (suspended) teamSuspendedChip()
                    if (member.overSeatLimit == true) Chip(text = L("Over the plan"), tone = Chip.Tone.custom(Theme.mango), percent = 24.0, ink = Theme.inkOnBright)
                }
                Text(member.titleLine.nonEmpty ?: member.email.nonEmpty ?: L("No email"), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
        }
        Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            teamRoleChip(member.role)
            Text(
                if (suspended) L("No access while suspended") else TeamPermissions.accessLine(member),
                style = Theme.text(12, if (member.hasCustomAccess) FontWeight.SemiBold else FontWeight.Normal),
                color = if (member.hasCustomAccess) Theme.inkStrong else Theme.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val strong = member.takesVisits && !suspended
            Text(
                if (suspended) L("Not booked while suspended") else (if (member.takesVisits) (if (member.atOnce > 1) LF("%d pets at once", member.atOnce) else L("One pet at a time")) else L("Doesn’t take visits")),
                style = Theme.text(14, if (strong) FontWeight.SemiBold else FontWeight.Normal),
                color = if (strong) Theme.inkStrong else Theme.inkSoft,
            )
            if (place != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("map-pin", 12.dp, tint = Theme.inkSoft)
                    Text(place, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.padding(start = 52.dp)) {
            Text(if (member.isMe) L("Now") else TeamText.lastActive(member.lastActiveAt), style = Theme.text(14), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(LF("Joined %@", TeamText.shortDate(member.createdAt)), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * "Suspendido" beside a name: access paused, still on the team (web `SuspendedChip`): the kit's
 * chip on a red wash with a red pause.
 */
@Composable
fun teamSuspendedChip(modifier: Modifier = Modifier) {
    Chip(
        text = L("Suspended"),
        tone = Chip.Tone.custom(Theme.destructive),
        icon = "circle-pause",
        percent = 10.0,
        ink = Theme.inkStrong,
        iconInk = Theme.destructive,
        border = 45.0,
        size = Chip.Size.medium,
        modifier = modifier,
    )
}
