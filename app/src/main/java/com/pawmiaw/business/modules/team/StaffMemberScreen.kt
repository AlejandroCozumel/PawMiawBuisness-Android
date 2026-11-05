@file:OptIn(ExperimentalFoundationApi::class)

package com.pawmiaw.business.team

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

//
//  StaffMemberScreen.kt (iOS StaffMemberScreen.swift)
//
//  One person on the team (web: routes/provider.staff.$memberId.tsx): who
//  they are, what they can do, how they take visits and where, their hours
//  and days off, what is coming up, and suspending or removing them.
//  Nobody changes their own access, the owner's, or a suspended person's.
//  The server rewrites every profile field and both override lists on each
//  save, so every save sends the whole person with only its part changed.
//

/** iOS `StaffMemberScreen.agenda(_:)`. */
object StaffMemberScreen {
    /** The Schedule's list of what is coming up for this person (the web's `agendaSearch`). */
    fun agenda(memberId: String): ScheduleFilter = ScheduleFilter(memberId = memberId, view = "list")
}

/** What one save changes; the rest is sent as the person is now. */
private data class StaffMemberChanges(
    val role: String? = null,
    val allow: List<String>? = null,
    val deny: List<String>? = null,
    val profile: TeamProfileFields? = null,
    val maxConcurrentVisits: Int? = null,
    val locationIds: List<String>? = null,
)

/** SwiftUI `.padding(.top, -x)`: the view sits `x` higher and takes `x` less room. */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

@Composable
fun StaffMemberScreen(memberId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var member by remember { mutableStateOf<TeamMember?>(null) }
    var roles by remember { mutableStateOf<List<TeamRolePermissions>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var upcoming by remember { mutableStateOf<List<TeamVisit>>(emptyList()) }
    var upcomingTotal by remember { mutableStateOf(0) }
    var upcomingLoading by remember { mutableStateOf(true) }
    var upcomingFailed by remember { mutableStateOf(false) }
    var booked by remember { mutableStateOf<Int?>(null) }
    var online by remember { mutableStateOf<TeamOnlineSettings?>(null) }
    var onlineLoaded by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf<TeamMemberHours?>(null) }
    var hoursError by remember { mutableStateOf<String?>(null) }
    var accessOpen by remember { mutableStateOf(false) }
    var suspendOpen by remember { mutableStateOf(false) }
    var removeOpen by remember { mutableStateOf(false) }
    var reactivating by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }
    /** "See them in the Schedule" from a sheet: the sheet closes, then the Schedule opens with this person. */
    var scheduleAfterSheet by remember { mutableStateOf(false) }

    val clinicId = workspace.current?.clinicId
    val canEdit = workspace.can(Permission.staffEditRoles)
    val canRemove = workspace.can(Permission.staffRemove)
    val canSeeVisits = workspace.can(Permission.appointmentsView)

    // MARK: Loading

    suspend fun loadVisits() {
        if (!canSeeVisits) return
        val from = DateText.iso(Instant.now())
        try {
            val page: Page<TeamVisit> = API.get("/provider-appointments", query = mapOf("limit" to "6", "offset" to "0", "from" to from, "assignedOrganizationMemberId" to memberId))
            upcoming = page.items.filter { it.status != "cancelled" && it.status != "no_show" }.take(5)
            upcomingTotal = page.total ?: page.items.size
            upcomingFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            upcomingFailed = true
        }
        upcomingLoading = false
        val scheduled = runCatching {
            API.get<Page<TeamVisit>>("/provider-appointments", query = mapOf("limit" to "1", "offset" to "0", "from" to from, "status" to "scheduled", "assignedOrganizationMemberId" to memberId))
        }.getOrNull()
        booked = scheduled?.total
    }

    suspend fun loadHours() {
        try {
            hours = API.get("/provider-hours/members/$memberId")
            hoursError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hoursError = errorText(e)
        }
    }

    suspend fun load() {
        val clinic = clinicId ?: return
        if (!workspace.can(Permission.staffView)) return
        try {
            val loaded: TeamMember = API.get("/provider-workspaces/$clinic/members/$memberId")
            member = loaded
            loadError = null
            notFound = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: APIError) {
            if (e.status == 404) {
                notFound = true
            } else if (member == null) {
                loadError = errorText(e)
            }
            return
        } catch (e: Exception) {
            if (member == null) loadError = errorText(e)
            return
        }
        if (roles == null) {
            val answer = runCatching { API.get<TeamRolesResponse>("/provider-workspace-roles") }.getOrNull()
            roles = answer?.roles
        }
        loadVisits()
        online = runCatching { API.get<TeamOnlineSettings>("/provider-online-booking/settings") }.getOrNull()
        onlineLoaded = true
        loadHours()
    }

    // MARK: Saving

    suspend fun save(changes: StaffMemberChanges) {
        val clinic = clinicId ?: return
        val current = member ?: return
        fun text(value: String?): String? = value?.trim()?.nonEmpty
        val body = mutableMapOf<String, Any?>(
            "role" to (changes.role ?: current.roleKey),
            "permissionAllow" to (changes.allow ?: current.allow),
            "permissionDeny" to (changes.deny ?: current.deny),
            "displayName" to text(changes.profile?.displayName ?: current.displayName),
            "professionalTitle" to text(changes.profile?.professionalTitle ?: current.professionalTitle),
            "specialty" to text(changes.profile?.specialty ?: current.specialty),
            // Written before credentials had their own card: kept as it is, never edited here.
            "credentialLabel" to text(current.credentialLabel),
            "credentialValue" to text(current.credentialValue),
            "profileNotes" to text(changes.profile?.profileNotes ?: current.profileNotes),
        )
        changes.maxConcurrentVisits?.let { body["maxConcurrentVisits"] = it }
        // Sent only when changed: left out, the server keeps where they work.
        changes.locationIds?.let { body["locationIds"] = it }
        val updated: TeamMember = API.patch("/provider-workspaces/$clinic/members/${current.id}", body)
        member = updated
        Workspace.shared.refresh()
        announceDataChanged()
    }

    suspend fun change(action: String) {
        val clinic = clinicId ?: return
        val current = member ?: return
        val updated: TeamMember = API.post("/provider-workspaces/$clinic/members/${current.id}/$action")
        member = updated
        Workspace.shared.refresh()
        announceDataChanged()
    }

    fun reactivate() {
        val current = member ?: return
        reactivating = true
        scope.launch {
            try {
                change("reactivate")
                Toast.show(LF("%@ has their access back, as it was.", current.shownName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                reactivating = false
            }
        }
    }

    fun openScheduleIfAsked() {
        if (!scheduleAfterSheet) return
        scheduleAfterSheet = false
        nav.push(ProviderRoute.schedule(StaffMemberScreen.agenda(memberId)))
    }

    /** Their visits still to come, with the way to them (web `BookedVisitsNotice`). */
    @Composable
    fun BookedNotice(kind: String) {
        val visits = booked ?: return
        if (visits <= 0) return
        // The sheet this notice sits in closes first; its dismissal opens the Schedule.
        val closeSheet = LocalDismiss.current
        val box = RoundedCornerShape(Theme.boxRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.mango, 12.0), box)
                .border(1.dp, Theme.mix(Theme.mango, 55.0, Theme.borderSoft), box)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (kind == "remove") LP(visits, "They still have 1 visit coming up. It will be left with nobody: give it to someone else first.", "They still have %d visits coming up. They will be left with nobody: give them to someone else first.")
                else LP(visits, "They still have 1 visit coming up. It stays booked with them: give it to someone else, or keep it for when they are back.", "They still have %d visits coming up. They stay booked with them: give them to someone else, or keep them for when they are back."),
                style = Theme.text(14, FontWeight.SemiBold),
                color = Theme.inkStrong,
            )
            Row(
                Modifier.plainButton {
                    scheduleAfterSheet = true
                    closeSheet()
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("calendar-days", 16.dp, tint = Theme.blue)
                Text(if (visits == 1) L("See it in the Schedule") else L("See them in the Schedule"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            }
        }
    }

    providerPage(member?.shownName ?: L("Team member"), modifier = modifier) {
        ReloadsOnScope { load() }
        val current = member
        if (!workspace.can(Permission.staffView)) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see staff.")) }
        } else if (current != null) {
            val accessAnchor = remember { BringIntoViewRequester() }
            val visitsAnchor = remember { BringIntoViewRequester() }
            val screen = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
            // iOS `proxy.scrollTo(id, anchor: .top)`: a screen-tall rect from the card's top lands it at the top.
            fun scrollTo(anchor: BringIntoViewRequester) {
                scope.launch { anchor.bringIntoView(Rect(0f, 0f, 1f, screen)) }
            }

            val suspended = current.isSuspended
            val isOwner = current.roleKey == "owner"
            // Nobody changes their own access, the owner's, or — until they are back — a suspended person's.
            val canEditAccess = canEdit && !isOwner && !current.isMe && !suspended
            val canPause = canRemove && !current.isMe && !isOwner
            val defaults = TeamPermissions.roleDefaults(roles, current.roleKey)
            val waitingAccess = if (suspended) defaults?.let { TeamPermissions.effective(defaults = it, allow = current.allow, deny = current.deny) } else null
            val wouldTakeVisits = if (suspended) (waitingAccess?.contains(Permission.appointmentsComplete.rawValue) ?: false) else current.takesVisits
            val prescribes = workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)
            val signs = (if (suspended) (waitingAccess ?: emptyList()) else current.effective).contains(Permission.recordsApprove.rawValue)
            val credentialsShown = if (prescribes) (current.roleKey == "vet" || signs) else current.roleKey != "receptionist"
            val locations = workspace.activeLocations

            PageScroll(refresh = { load() }) {
                StaffMemberHeader(
                    member = current,
                    suspended = suspended,
                    isOwner = isOwner,
                    wouldTakeVisits = wouldTakeVisits,
                    canEditAccess = canEditAccess,
                    canPause = canPause,
                    canSeeVisits = canSeeVisits,
                    booked = booked,
                    reactivating = reactivating,
                    onRole = { scrollTo(accessAnchor) },
                    onCapacity = { scrollTo(visitsAnchor) },
                    onBooked = { nav.push(ProviderRoute.schedule(StaffMemberScreen.agenda(current.id))) },
                    onChangeAccess = { accessOpen = true },
                    onReactivate = { reactivate() },
                )
                if (suspended) {
                    SuspendedNotice(current, canPause = canPause, canSeeVisits = canSeeVisits, booked = booked, reactivating = reactivating,
                        onBooked = { nav.push(ProviderRoute.schedule(StaffMemberScreen.agenda(current.id))) },
                        onReactivate = { reactivate() })
                }

                TeamMemberProfileCard(
                    member = current,
                    canEdit = canEdit,
                    credentialsShown = credentialsShown,
                    onSave = { fields -> save(StaffMemberChanges(profile = fields)) },
                    onUploadPhoto = { data ->
                        val clinic = clinicId
                        if (clinic != null) {
                            API.upload<EmptyResponse>("/provider-workspaces/$clinic/members/${current.id}/photo", fileField = "photo", fileName = "photo.jpg", mimeType = "image/jpeg", fileData = data)
                            load()
                        }
                    },
                )

                TeamMemberAccessCard(member = current, canEdit = canEditAccess, roleAccess = waitingAccess, modifier = Modifier.bringIntoViewRequester(accessAnchor)) { accessOpen = true }
                if (canEdit && current.isMe && !isOwner) {
                    Text(
                        L("This is your own access: someone else who can change roles has to change it."),
                        Modifier.pullUp(8.dp).padding(horizontal = 4.dp),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }

                if (credentialsShown) {
                    TeamCredentialsCard(memberId = current.id, isSelf = current.isMe, canEdit = canEdit, prescribing = prescribes, memberName = current.shownName)
                }
                // The professional signature printed on what this person signs: only on their own page.
                if (current.isMe) {
                    MySignatureCard(suggestedName = current.shownName)
                }

                TeamMemberVisitsCard(member = current, canEdit = canEdit, takesVisits = wouldTakeVisits, modifier = Modifier.bringIntoViewRequester(visitsAnchor)) { value ->
                    save(StaffMemberChanges(maxConcurrentVisits = value))
                }

                if (wouldTakeVisits) {
                    TeamMemberOnlineCard(memberId = current.id, name = current.shownName, canEdit = canEdit || workspace.can(Permission.workspaceEdit), settings = online, loaded = onlineLoaded) { online = it }
                    TeamServiceAreaCard(memberId = current.id, name = current.shownName, canEdit = canEdit)
                }
                if (locations.size >= 2) {
                    TeamMemberLocationsCard(member = current, locations = locations, canEdit = canEdit) { ids ->
                        save(StaffMemberChanges(locationIds = ids))
                    }
                }
                if (wouldTakeVisits) {
                    TeamMemberHoursCards(member = current, hours = hours, error = hoursError, canEdit = canEdit, onSaved = { hours = it }, retry = { loadHours() })
                    if (workspace.can(Permission.workspaceEdit)) {
                        Text(
                            L("Change the opening hours"),
                            Modifier.padding(horizontal = 4.dp).plainButton { nav.push(ProviderRoute.section(ProviderSection.availability)) },
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.blue,
                        )
                    }
                    TeamMemberTimeOffCard(member = current, hours = hours, canEdit = canEdit, reload = { loadHours() })
                }
                if (canSeeVisits) {
                    TeamMemberUpcomingCard(name = current.shownName, visits = upcoming, total = upcomingTotal, loading = upcomingLoading, failed = upcomingFailed, memberId = current.id)
                }
                // Their questions to Olfato AI: whoever reads the team's use, and the person themself (the server's rule).
                if (TeamOlfatoAccess.seesTeam(workspace) || current.isMe) {
                    TeamMemberOlfatoCard(memberId = current.id, isSelf = current.isMe, name = current.shownName, seesTeam = TeamOlfatoAccess.seesTeam(workspace))
                }

                if (canPause) {
                    OnTheTeam(suspended = suspended, onSuspend = { suspendOpen = true }, onRemove = { removeOpen = true })
                } else if (current.isMe) {
                    Text(
                        if (isOwner) L("This is you, the owner. The owner can’t be suspended, removed or changed to another role.") else L("This is you. To leave the workspace, ask the owner to remove you."),
                        Modifier.padding(horizontal = 4.dp),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                } else if (isOwner) {
                    Text(L("The owner can’t be suspended, removed or changed to another role."), Modifier.padding(horizontal = 4.dp), style = Theme.text(14), color = Theme.inkSoft)
                }

                AuditHistoryCard(
                    entityType = "clinic_member",
                    entityId = current.id,
                    title = L("History"),
                    description = L("Every change to this person’s profile, role, permissions, hours and days off, and who made it."),
                    collapsible = false,
                    emptyText = L("No changes yet."),
                )
            }

            if (accessOpen) {
                AppSheet(onDismissRequest = { accessOpen = false }) {
                    TeamAccessSheet(
                        member = current,
                        roles = roles,
                        actorPermissions = workspace.current?.effectivePermissions ?: emptyList(),
                        actorManagesPlan = workspace.current?.canManagePlan ?: false,
                    ) { role, allow, deny ->
                        save(StaffMemberChanges(role = role, allow = allow, deny = deny))
                    }
                }
            }
            if (suspendOpen) {
                AppSheet(onDismissRequest = { suspendOpen = false; openScheduleIfAsked() }) {
                    TeamConfirmSheet(
                        title = LF("Suspend %@?", current.shownName),
                        message = L("Their access stops right away. Nothing is erased, and you can give it back whenever you want."),
                        confirmTitle = L("Suspend access"),
                        cancelTitle = L("Keep them active"),
                        action = {
                            change("suspend")
                            Toast.show(LF("%@ is suspended. Reactivate them here whenever you want.", current.shownName))
                        },
                    ) {
                        TeamPersonLine(name = current.shownName, image = current.image, detail = listOfNotNull(TeamRole.label(current.role).nonEmpty, current.email.nonEmpty).joinToString(" · "))
                        TeamConsequences(lines = listOf(
                            L("They can’t open this workspace, and stop getting its notices."),
                            L("Families can’t book them online, and the Schedule stops offering them for new visits."),
                            L("Their role, permissions, hours and days off wait as they are."),
                            L("They keep their seat on the plan."),
                        ))
                        BookedNotice(kind = "suspend")
                    }
                }
            }
            if (removeOpen) {
                AppSheet(onDismissRequest = { removeOpen = false; if (removed) dismiss() else openScheduleIfAsked() }) {
                    TeamConfirmSheet(
                        title = LF("Remove %@?", current.shownName),
                        message = L("They leave the team and lose this workspace right away."),
                        confirmTitle = L("Remove from the team"),
                        cancelTitle = L("Keep them"),
                        action = {
                            val clinic = clinicId
                            if (clinic != null) {
                                API.send("DELETE", "/provider-workspaces/$clinic/members/${current.id}")
                                Toast.show(LF("%@ is no longer on the team.", current.shownName))
                                removed = true
                                Workspace.shared.refresh()
                                announceDataChanged()
                            }
                        },
                    ) {
                        TeamPersonLine(name = current.shownName, image = current.image, detail = listOfNotNull(TeamRole.label(current.role).nonEmpty, current.email.nonEmpty).joinToString(" · "))
                        TeamConsequences(lines = listOf(
                            L("They can no longer open visits, clients, records or money here."),
                            L("What they wrote and signed stays in the records."),
                            L("Their own hours and days off are erased, and their seat on the plan is free again."),
                            L("You can invite them again later; they would start from the role you choose then."),
                        ))
                        BookedNotice(kind = "remove")
                        Text(L("Coming back soon? Suspend them instead: nothing is lost."), style = Theme.text(14), color = Theme.inkSoft)
                    }
                }
            }
        } else if (notFound) {
            PageScroll {
                EmptyState(
                    systemImage = "user-round",
                    title = L("This person isn’t on the team"),
                    message = L("They may have been removed, or the link is from another workspace."),
                    actionTitle = L("Back to the team"),
                    action = { dismiss() },
                    accent = Theme.pink,
                    panel = true,
                )
            }
        } else if (loadError != null) {
            PageScroll {
                EmptyState(
                    systemImage = "user-round",
                    title = L("This person couldn’t load."),
                    message = L("Check your connection and try again."),
                    actionTitle = L("Try again"),
                    action = { scope.launch { load() } },
                    accent = Theme.pink,
                    panel = true,
                )
            }
        } else {
            LoadingState()
        }
    }
}

@Composable
private fun StaffMemberHeader(
    member: TeamMember,
    suspended: Boolean,
    isOwner: Boolean,
    wouldTakeVisits: Boolean,
    canEditAccess: Boolean,
    canPause: Boolean,
    canSeeVisits: Boolean,
    booked: Int?,
    reactivating: Boolean,
    onRole: () -> Unit,
    onCapacity: () -> Unit,
    onBooked: () -> Unit,
    onChangeAccess: () -> Unit,
    onReactivate: () -> Unit,
) {
    val tone = TeamRole.hue(member.role).color
    val stats = mutableListOf(
        ModuleStat(
            "role", label = L("Role"), value = TeamRole.label(member.role),
            hint = if (suspended) L("No access while suspended") else if (isOwner) L("Full access") else (if (member.hasCustomAccess) L("Fine-tuned for them") else L("The role’s access")),
            icon = "shield-check", accent = tone, action = onRole,
        ),
        ModuleStat(
            "capacity", label = L("Pets at once"), value = if (wouldTakeVisits) "${member.atOnce}" else "—",
            hint = if (suspended) L("Not booked while suspended") else (if (member.takesVisits) L("Before a double booking") else L("Doesn’t take visits")),
            icon = "layers", accent = Theme.mango, action = onCapacity,
        ),
    )
    if (canSeeVisits) {
        stats.add(
            ModuleStat(
                "booked", label = L("Coming up"), value = booked?.toString() ?: "—", hint = L("Booked visits"),
                icon = "calendar-days", accent = Theme.aqua, attention = suspended && (booked ?: 0) > 0,
                action = onBooked,
            )
        )
    }
    stats.add(
        ModuleStat(
            "active", label = L("Last active"), value = if (member.isMe) L("Now") else TeamText.lastActive(member.lastActiveAt),
            hint = if (member.isMe) L("This is you") else LF("Joined %@", TeamText.shortDate(member.createdAt)),
            icon = "activity", accent = Theme.pink,
        )
    )
    val subtitle = member.titleLine.nonEmpty?.let { title -> listOfNotNull(title, member.email.nonEmpty).joinToString(" · ") }
        ?: member.email.nonEmpty ?: TeamRole.label(member.role)
    ModuleHeader(
        icon = "user-round",
        title = member.shownName,
        subtitle = subtitle,
        accent = tone,
        // The person's photo (or initials) fills the header's round, whatever size it is drawn at.
        media = {
            BoxWithConstraints {
                Avatar(name = member.shownName, imageURL = member.image, size = maxWidth, tint = Theme.aqua)
            }
        },
        stats = stats,
        storageKey = "staff-member",
        actions = if (canEditAccess) ({
            PMButton(L("Change access"), systemImage = "shield-check", action = onChangeAccess)
        }) else if (suspended && canPause) ({
            PMButton(L("Reactivate"), systemImage = "circle-play", isWorking = reactivating, action = onReactivate)
        }) else null,
    )
}

@Composable
private fun SuspendedNotice(
    member: TeamMember,
    canPause: Boolean,
    canSeeVisits: Boolean,
    booked: Int?,
    reactivating: Boolean,
    onBooked: () -> Unit,
    onReactivate: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.destructive, 6.0), shape)
            .border(2.dp, Theme.mix(Theme.destructive, 40.0, Theme.borderSoft), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowLayout(spacing = 8.dp) {
                teamSuspendedChip()
                member.suspendedAt?.let { since ->
                    Text(LF("Since %@", TeamText.shortDate(since)), style = Theme.heading(18), color = Theme.inkStrong)
                }
            }
            Text(
                LF("%@ can’t open this workspace and isn’t offered for new visits. Their role, permissions, hours and days off wait as they are.", member.shownName),
                style = Theme.text(14),
                color = Theme.inkSoft,
            )
            if (canSeeVisits && booked != null && booked > 0) {
                Text(
                    LP(booked, "1 visit is still booked with them", "%d visits are still booked with them"),
                    Modifier.plainButton(onClick = onBooked),
                    style = Theme.text(14, FontWeight.Bold),
                    color = Theme.blue,
                )
            }
        }
        if (canPause) {
            PMButton(L("Reactivate"), systemImage = "circle-play", isWorking = reactivating, action = onReactivate)
        }
    }
}

/** "En el equipo": suspend or remove (web: the card with the coral edge). */
@Composable
private fun OnTheTeam(suspended: Boolean, onSuspend: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .cardShadow(shape)
            .background(Theme.surface, shape)
            .clip(shape)
            .border(1.dp, Theme.mix(Theme.destructive, 30.0, Theme.borderSoft), shape),
    ) {
        Text(
            L("On the team"),
            Modifier
                .fillMaxWidth()
                .teamEdgeRule(Theme.borderSoft)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            style = Theme.heading(20),
            color = Theme.inkStrong,
        )
        if (!suspended) {
            TeamAction(L("Suspend access"), L("For a leave or while something is sorted out. They can’t get in; nothing is erased, and you give it back with one tap.")) {
                PMButton(L("Suspend"), systemImage = "circle-pause", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onSuspend)
            }
            RowDivider()
        }
        TeamAction(L("Remove from the team"), L("For someone who no longer works here. They lose access right away; you can invite them again later.")) {
            PMButton(L("Remove"), systemImage = "user-minus", variant = ButtonVariant.destructive, size = ButtonSize.compact, action = onRemove)
        }
    }
}

@Composable
private fun TeamAction(title: String, text: String, action: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(title, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            Text(text, style = Theme.text(14), color = Theme.inkSoft)
        }
        action()
    }
}
