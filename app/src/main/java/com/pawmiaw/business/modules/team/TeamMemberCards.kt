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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

//
//  TeamMemberCards.kt (iOS TeamMemberCards.swift)
//
//  The cards of a person's page (web: components/app/staff/member-*.tsx):
//  their profile, what they can do, how many pets at once, online booking
//  ("Recibe reservas en línea", "Servicios que hace"), where they work,
//  where they go for home visits, and what is coming up for them. Each card
//  that edits saves on its own.
//

// MARK: - Profile

data class TeamProfileFields(
    val displayName: String = "",
    val professionalTitle: String = "",
    val specialty: String = "",
    val profileNotes: String = "",
) {
    constructor(member: TeamMember) : this(
        displayName = member.displayName ?: "",
        professionalTitle = member.professionalTitle ?: "",
        specialty = member.specialty ?: "",
        profileNotes = member.profileNotes ?: "",
    )

    fun same(other: TeamProfileFields): Boolean =
        displayName.trim() == other.displayName.trim() &&
            professionalTitle.trim() == other.professionalTitle.trim() &&
            specialty.trim() == other.specialty.trim() &&
            profileNotes.trim() == other.profileNotes.trim()
}

/** Who they are to the clinic and its families: the name shown, photo, title, specialty, notes. */
@Composable
fun TeamMemberProfileCard(
    member: TeamMember,
    canEdit: Boolean,
    credentialsShown: Boolean,
    onSave: suspend (TeamProfileFields) -> Unit,
    modifier: Modifier = Modifier,
    onUploadPhoto: suspend (ByteArray) -> Unit,
) {
    val saved = TeamProfileFields(member)
    var draft by remember { mutableStateOf(saved) }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    var saving by remember { mutableStateOf(false) }
    val flash = remember { TeamSavedFlash() }
    val scope = rememberCoroutineScope()
    val dirty = !draft.same(saved) || photo.value.data != null

    // iOS `.onChange(of: member)`: untouched fields follow what the server now says.
    var previous by remember { mutableStateOf(member) }
    LaunchedEffect(member) {
        if (previous != member) {
            if (draft.same(TeamProfileFields(previous))) draft = TeamProfileFields(member)
            previous = member
        }
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                try {
                    if (!draft.same(saved)) onSave(draft)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.error(e)
                    return@launch
                }
                val data = photo.value.data
                if (data != null) {
                    try {
                        onUploadPhoto(data)
                        photo.value = PhotoDraft()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.error(LF("The details were saved, but the photo didn’t upload: %@", errorText(e)))
                        return@launch
                    }
                }
                flash.mark()
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Profile"),
        subtitle = L("How they appear to the team and to families: on visits, records and report cards."),
        systemImage = "id-card",
        tint = Theme.aqua,
        state = flash.state(saving = saving, dirty = dirty),
        saveLabel = L("Save profile"),
        readOnlyText = if (canEdit) null else L("Only someone who can edit staff roles changes profiles."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { draft = saved; photo.value = PhotoDraft() },
        modifier = modifier,
    ) {
        Disabled(!canEdit || saving) {
            PhotoField(
                label = L("Photo"),
                hint = if (photo.value.data != null) L("New photo chosen — save to keep it.") else L("JPG, PNG or WebP, up to 3 MB."),
                currentURL = member.image,
                draft = photo,
                shape = PhotoField.Shape.circle,
                allowsRemove = false,
            )
        }
        Disabled(!canEdit || saving) {
            PMTextField(
                L("Name shown"),
                text = stateOf({ draft.displayName }, { draft = draft.copy(displayName = it) }),
                prompt = member.userName.nonEmpty ?: L("Their account name"),
                hint = LF("Leave empty to use their account name (%@).", member.userName.nonEmpty ?: member.email.nonEmpty ?: "—"),
                capitalization = KeyboardCapitalization.Words,
            )
        }
        FormField(L("Email"), hint = L("Their sign-in email; only they can change it.")) {
            FieldChrome {
                Text(member.email ?: "—", Modifier.weight(1f), style = Theme.bodyFont, color = Theme.inkSoft)
            }
        }
        Disabled(!canEdit || saving) {
            PMTextField(L("Title"), text = stateOf({ draft.professionalTitle }, { draft = draft.copy(professionalTitle = it) }), prompt = L("Veterinarian, groomer, trainer…"))
        }
        Disabled(!canEdit || saving) {
            PMTextField(L("Specialty"), text = stateOf({ draft.specialty }, { draft = draft.copy(specialty = it) }), prompt = L("Dermatology, behaviour, cats…"))
        }
        Disabled(!canEdit || saving) {
            PMTextField(
                L("Notes for the team"),
                text = stateOf({ draft.profileNotes }, { draft = draft.copy(profileNotes = it) }),
                prompt = L("Languages, what they focus on, anything the team should know."),
                hint = L("Only the team sees these."),
                multiline = true,
            )
        }
        if (credentialsShown) {
            val value = member.credentialValue.nonEmpty
            NoticeCard(
                tone = NoticeCard.Tone.info,
                title = L("Professional credentials"),
                message = if (value != null) {
                    LF("A licence was written here before: %@. Add it under Professional credentials so it is checked and counts for prescriptions.", listOfNotNull(member.credentialLabel.nonEmpty, value).joinToString(" "))
                } else L("Their cédula or licence goes under Professional credentials, where it is checked and counts for prescriptions."),
            )
        }
    }
}

// MARK: - Role and access

/** What this person can do, read the way a person would say it. */
@Composable
fun TeamMemberAccessCard(
    member: TeamMember,
    canEdit: Boolean,
    /** What they can do once active again, for someone suspended. */
    roleAccess: List<String>?,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
) {
    var everyOpen by remember { mutableStateOf(false) }
    val effective = if (member.isSuspended) (roleAccess ?: emptyList()) else member.effective
    val groups = TeamPermissions.byGroup(effective)
    val some = groups.filter { it.second.isNotEmpty() }
    val none = groups.filter { it.second.isEmpty() }
    val limits = TeamPermissions.limits(effective)
    val added = member.allow.toSet()
    val removed = member.deny.toSet()
    val box = RoundedCornerShape(Theme.boxRadius)
    TeamPanel(
        title = L("Role and access"),
        subtitle = if (member.isSuspended) LF("What waits for %@ once they are reactivated.", member.shownName) else LF("What %@ can see and do in this workspace.", member.shownName),
        systemImage = "shield-check",
        tint = Theme.lime,
        modifier = modifier,
        action = {
            if (canEdit) {
                PMButton(L("Change access"), systemImage = "shield-check", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onEdit)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                teamRoleChip(member.role, large = true)
                Text(TeamRole.summary(member.role), style = Theme.text(14), color = Theme.inkSoft)
            }
            if (limits.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    limits.forEach { limit ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Glyph("eye-off", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                            Text(limit, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        }
                    }
                }
            }
            if (member.roleKey != "owner" && (added.isNotEmpty() || removed.isNotEmpty())) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.mango, 9.0), box)
                        .border(1.dp, Theme.mix(Theme.mango, 50.0, Theme.borderSoft), box)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(LF("Changed just for %@", member.shownName), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    FlowLayout(spacing = 6.dp) {
                        member.allow.forEach { PermissionPill(TeamPermissions.label(it), icon = "plus", tone = Theme.blue, percent = 14.0) }
                        member.deny.forEach { PermissionPill(TeamPermissions.label(it), icon = "minus", tone = Theme.destructive, percent = 12.0) }
                    }
                }
            }
            TeamAdaptiveGrid(items = some, minimum = 220.dp, spacing = 8.dp) { (group, on, _) ->
                val all = on.size == group.permissions.size
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Theme.borderSoft, box)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(L(group.title), Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (all) L("Everything") else LF("%1\$d of %2\$d", on.size, group.permissions.size),
                            Modifier
                                .background(if (all) Theme.wash(Theme.blue, 14.0) else Theme.surfaceMuted, CapsuleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = Theme.text(11.2, FontWeight.Bold),
                            color = if (all) Theme.inkStrong else Theme.inkSoft,
                        )
                    }
                    Text(on.joinToString(" · ") { L(it.label) }, style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (none.isNotEmpty()) {
                val strong = Theme.inkStrong
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(L("No access to:")) }
                        append(" " + none.joinToString(", ") { L(it.first.title) })
                    },
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            EveryPermission(groups, effective, added, removed, everyOpen) { everyOpen = !everyOpen }
        }
    }
}

@Composable
private fun PermissionPill(text: String, icon: String, tone: Color, percent: Double) {
    Row(
        Modifier
            .background(Theme.wash(tone, percent), CapsuleShape)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 12.dp, tint = Theme.inkStrong)
        Text(text, style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Cada permiso": every permission, folded away (web: the bordered collapse). */
@Composable
private fun EveryPermission(
    groups: List<Triple<TeamPermissionGroup, List<TeamPermissionInfo>, List<TeamPermissionInfo>>>,
    effective: List<String>,
    added: Set<String>,
    removed: Set<String>,
    everyOpen: Boolean,
    toggle: () -> Unit,
) {
    val box = RoundedCornerShape(Theme.boxRadius)
    val turn by animateFloatAsState(if (everyOpen) 0f else -90f, tween(200, easing = EaseOut), label = "every")
    Column(Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, box)) {
        Row(
            Modifier
                .fillMaxWidth()
                .plainButton(onClick = toggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
            Text(L("Every permission"), Modifier.weight(1f), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(LF("%d on", effective.size), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
        }
        AnimatedVisibility(
            visible = everyOpen,
            enter = fadeIn(tween(200, easing = EaseOut)) + expandVertically(tween(200, easing = EaseOut)),
            exit = fadeOut(tween(200, easing = EaseOut)) + shrinkVertically(tween(200, easing = EaseOut)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .teamEdgeRule(Theme.borderSoft, top = true)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                groups.forEach { (group, _, _) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(L(group.title).uppercase(appLocale), style = brandText(11.2, FontWeight.ExtraBold).tracking(1.3), color = Theme.inkSoft)
                        group.permissions.forEach { permission ->
                            val on = effective.contains(permission.key)
                            val state = if (on) L("On") else L("Off")
                            Row(
                                Modifier.semantics(mergeDescendants = true) { stateDescription = state },
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Glyph(if (on) "check" else "x", 16.dp, tint = if (on) Theme.blue else Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                                FlowLayout(spacing = 6.dp) {
                                    Text(L(permission.label), style = Theme.text(14), color = if (on) Theme.inkStrong else Theme.inkSoft)
                                    if (added.contains(permission.key)) Chip(text = L("added for them"), tone = Chip.Tone.custom(Theme.lime), percent = 100.0, ink = Theme.inkOnBright, weight = FontWeight.ExtraBold)
                                    if (removed.contains(permission.key)) Chip(text = L("taken away for them"), tone = Chip.Tone.custom(Theme.lime), percent = 100.0, ink = Theme.inkOnBright, weight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Visits

/** How many pets they take at once (the double-booking rule reads it). */
@Composable
fun TeamMemberVisitsCard(
    member: TeamMember,
    canEdit: Boolean,
    takesVisits: Boolean,
    modifier: Modifier = Modifier,
    onSave: suspend (Int) -> Unit,
) {
    val value = remember { mutableStateOf(member.atOnce) }
    var saving by remember { mutableStateOf(false) }
    val flash = remember { TeamSavedFlash() }
    val scope = rememberCoroutineScope()
    // iOS `.onChange(of: member.atOnce)`: an untouched stepper follows the saved number.
    var previous by remember { mutableStateOf(member.atOnce) }
    LaunchedEffect(member.atOnce) {
        if (previous != member.atOnce) {
            if (value.value == previous) value.value = member.atOnce
            previous = member.atOnce
        }
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                onSave(value.value)
                flash.mark()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Visits"),
        subtitle = if (takesVisits) LF("How %@ takes visits.", member.shownName) else LF("%@’s role doesn’t start or finish visits, so the Schedule doesn’t give them a column.", member.shownName),
        systemImage = "calendar-clock",
        tint = Theme.mango,
        state = flash.state(saving = saving, dirty = value.value != member.atOnce),
        readOnlyText = if (canEdit) null else L("Only someone who can edit staff roles changes this."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { value.value = member.atOnce },
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("Pets at once"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                Text(
                    L("How many visits this person takes at the same time. A vet sees one; a groomer drying one dog while bathing another takes two. More than this is warned as a double booking."),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            TeamCountStepper(value = value, disabled = !canEdit || saving)
        }
    }
}

// MARK: - Online booking

/** The person's own switch as a change: `Takes(null)` = back to what their role says (Swift `Bool??`). */
private data class TeamTakesChange(val value: Boolean?)

/** "Recibe reservas en línea" and "Servicios que hace" (backend docs/online-booking.md §4). */
@Composable
fun TeamMemberOnlineCard(
    memberId: String,
    name: String,
    canEdit: Boolean,
    settings: TeamOnlineSettings?,
    /** The settings were asked for (null after that: this reader can't see them). */
    loaded: Boolean = true,
    modifier: Modifier = Modifier,
    onSaved: (TeamOnlineSettings) -> Unit,
) {
    val member = settings?.team?.firstOrNull { it.memberId == memberId }
    if (member != null) {
        TeamMemberOnlineCardBody(memberId, name, canEdit, settings, member, modifier, onSaved)
    } else if (settings == null && !loaded) {
        TeamProgress(modifier = modifier)
    }
}

@Composable
private fun TeamMemberOnlineCardBody(
    memberId: String,
    name: String,
    canEdit: Boolean,
    settings: TeamOnlineSettings,
    member: TeamOnlineMember,
    modifier: Modifier,
    onSaved: (TeamOnlineSettings) -> Unit,
) {
    var takes by remember { mutableStateOf<TeamTakesChange?>(null) }
    var serviceIds by remember { mutableStateOf<List<String>?>(null) }
    var pickingSome by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val flash = remember { TeamSavedFlash() }
    val scope = rememberCoroutineScope()
    var choosingServices by remember { mutableStateOf(false) }

    val roleDefault = member.roleDefault ?: false
    val ownSwitch: Boolean? = if (takes != null) takes!!.value else member.takesOnlineBookings
    val isOn = ownSwitch ?: roleDefault
    val ids = serviceIds ?: member.serviceIds ?: emptyList()
    val savedIds = member.serviceIds ?: emptyList()
    val changed = (takes != null && takes!!.value != member.takesOnlineBookings) || (serviceIds != null && ids.toSet() != savedIds.toSet())
    val dirty = changed || (pickingSome && ids.isEmpty() && savedIds.isEmpty() && isOn)
    val services = (settings.services ?: emptyList()).filter { it.onlineBookable ?: false }
    val chooseSome = ids.isNotEmpty() || pickingSome

    fun save(takesValue: Boolean?, ids: List<String>) {
        if (pickingSome && ids.isEmpty()) {
            error = L("Tick at least one service, or choose every service.")
            Toast.error(error ?: "")
            return
        }
        saving = true
        scope.launch {
            try {
                val updated: TeamOnlineSettings = API.put("/provider-online-booking/team/$memberId", mapOf("takesOnlineBookings" to takesValue, "serviceIds" to ids))
                takes = null; serviceIds = null; pickingSome = false
                onSaved(updated)
                flash.mark()
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Online booking"),
        subtitle = LF("Whether families can book a time with %@ from the app, and for what.", name),
        systemImage = "globe",
        tint = Theme.lavender,
        state = flash.state(saving = saving, dirty = dirty),
        readOnlyText = if (canEdit) null else L("Only someone who can edit staff roles changes this."),
        onSave = if (canEdit) ({ save(ownSwitch, ids) }) else null,
        onDiscard = { takes = null; serviceIds = null; pickingSome = false; error = null },
        modifier = modifier,
    ) {
        Disabled(!canEdit || (member.suspended ?: false)) {
            ToggleRow(
                title = L("Takes online bookings"),
                subtitle = if (ownSwitch == null) {
                    if (roleDefault) LF("Yes, as their role says. Families can book a time with %@.", name) else L("No, as their role says (the front desk and admins don’t take visits).")
                } else {
                    if (isOn) LF("Families can book a time with %@.", name) else LF("Families never get %@ when they book.", name)
                },
                isOn = stateOf({ isOn }, { checked -> error = null; takes = TeamTakesChange(if (checked == roleDefault) null else checked) }),
            )
        }
        if (member.suspended ?: false) {
            Text(L("Suspended: never offered to families until they are back."), style = Theme.text(14), color = Theme.inkSoft)
        }
        if (isOn) {
            Text(L("Services they do"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            ChoiceCards(
                options = listOf(
                    Triple("all", L("Every service their role does"), if (member.signsRecords ?: false) L("Consults and emergencies too: they sign records.") else L("Consults and emergencies only go to someone who signs records.")),
                    Triple("some", L("Only these"), L("Families get them only for the services ticked.")),
                ),
                // `.disabled(!canEdit)`: the cards don't change.
                selection = stateOf({ if (chooseSome) "some" else "all" }, { next ->
                    if (canEdit) {
                        error = null
                        if (next == "all") { pickingSome = false; serviceIds = emptyList() } else pickingSome = true
                    }
                }),
            )
            if (chooseSome) {
                val box = RoundedCornerShape(Theme.boxRadius)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(if (error != null) 2.dp else 1.dp, if (error != null) Theme.destructive else Theme.borderSoft, box)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (services.isEmpty()) {
                        Text(L("No service is booked online yet."), style = Theme.text(14), color = Theme.inkSoft)
                        Text(
                            L("Choose them in Settings"),
                            Modifier.plainButton { choosingServices = true },
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.blue,
                        )
                    }
                    services.forEach { service ->
                        val on = ids.contains(service.serviceId)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .plainButton(enabled = canEdit) {
                                    error = null
                                    serviceIds = if (on) ids.filter { it != service.serviceId } else ids + service.serviceId
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tick(on = on)
                            Text(service.label, Modifier.weight(1f), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                        }
                    }
                }
                error?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
            }
        }
        val (summary, strong) = offerSummary(name, offered = member.offered ?: false, dirty = dirty, takes = isOn)
        Text(summary, style = Theme.text(14, if (strong) FontWeight.SemiBold else FontWeight.Normal), color = if (strong) Theme.inkStrong else Theme.inkSoft)
    }

    // iOS pushes Settings › Services here (`NavigationLink { TeamSettingsSectionScreen(section: "services") }`);
    // a page that isn't a route opens over this one.
    if (choosingServices) {
        AppSheet(onDismissRequest = { choosingServices = false }) {
            Box(Modifier.sheetPresentation()) { TeamSettingsSectionScreen(section = "services") }
        }
    }
}

/** Whether families are offered the person; a change not saved yet says so ("Sin guardar…"): (text, strong). */
private fun offerSummary(name: String, offered: Boolean, dirty: Boolean, takes: Boolean): Pair<String, Boolean> {
    if (dirty && !takes) return LF("Not saved yet: once you save, families won’t be offered %@.", name) to true
    if (dirty) return L("Not saved yet: what families are offered changes once you save.") to true
    return if (offered) LF("Families are offered %@ now, at the locations where they work.", name) to false else LF("Families aren’t offered %@ now.", name) to true
}

// MARK: - Locations

/** Where this person works; none ticked means every location. */
@Composable
fun TeamMemberLocationsCard(
    member: TeamMember,
    locations: List<ProviderLocation>,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
    onSave: suspend (List<String>) -> Unit,
) {
    val active = locations.map { it.id }.toSet()
    val saved = (member.locationIds ?: emptyList()).filter { active.contains(it) }
    val value = remember { mutableStateOf(saved) }
    var saving by remember { mutableStateOf(false) }
    val flash = remember { TeamSavedFlash() }
    val scope = rememberCoroutineScope()
    // iOS `.onChange(of: member.locationIds)`.
    var previous by remember { mutableStateOf(member.locationIds ?: emptyList()) }
    LaunchedEffect(member.locationIds) {
        val now = member.locationIds ?: emptyList()
        if (previous != now) {
            value.value = saved
            previous = now
        }
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                // Every one ticked is the same as none: say "everywhere", so a new location includes them too.
                onSave(if (value.value.size == locations.size) emptyList() else value.value)
                flash.mark()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Locations"),
        subtitle = if (value.value.isEmpty()) LF("%@ works at every location.", member.shownName) else LF("Where %@ works. Their Schedule column, registers and “today” follow it.", member.shownName),
        systemImage = "map-pin",
        tint = Theme.aqua,
        state = flash.state(saving = saving, dirty = value.value.toSet() != saved.toSet()),
        readOnlyText = if (canEdit) null else L("Only someone who can edit staff roles changes this."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { value.value = saved },
        modifier = modifier,
    ) {
        TeamLocationChoiceList(locations = locations, value = value, disabled = !canEdit || saving)
    }
}

// MARK: - Home visits area

@Serializable
private data class TeamServiceArea(
    val postalCodes: List<String>? = null,
    val zones: List<String>? = null,
    val travelMinutes: Int? = null,
)

/** Where a person goes for home visits, and the time they need between two homes. */
@Composable
fun TeamServiceAreaCard(
    memberId: String,
    name: String,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    var area by remember { mutableStateOf<TeamServiceArea?>(null) }
    var failed by remember { mutableStateOf(false) }
    val postal = remember { mutableStateOf("") }
    val zones = remember { mutableStateOf("") }
    val travel = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val flash = remember { TeamSavedFlash() }
    val scope = rememberCoroutineScope()

    fun list(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    // (ok, value)
    val travelValue: Pair<Boolean, Int?> = run {
        val trimmed = travel.value.trim()
        if (trimmed.isEmpty()) true to null
        else {
            val n = trimmed.toIntOrNull()
            if (n == null || n !in 0..180) false to null else true to n
        }
    }
    val dirty = area?.let { a ->
        list(postal.value) != (a.postalCodes ?: emptyList<String>()) || list(zones.value) != (a.zones ?: emptyList<String>()) ||
            (if (travelValue.first) travelValue.second else -1) != a.travelMinutes
    } ?: false
    val anywhere = list(postal.value).isEmpty() && list(zones.value).isEmpty()

    fun fill() {
        postal.value = (area?.postalCodes ?: emptyList()).joinToString(", ")
        zones.value = (area?.zones ?: emptyList()).joinToString(", ")
        travel.value = area?.travelMinutes?.toString() ?: ""
    }

    LaunchedEffect(memberId) {
        try {
            area = API.get("/provider-members/$memberId/service-area")
            fill()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    fun save() {
        if (!travelValue.first) return
        saving = true
        scope.launch {
            try {
                area = API.put("/provider-members/$memberId/service-area", mapOf("postalCodes" to list(postal.value), "zones" to list(zones.value), "travelMinutes" to travelValue.second))
                fill()
                flash.mark()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Home visits"),
        subtitle = if (anywhere) LF("%@ goes anywhere. Add postal codes or zones to warn when a home visit is outside them.", name) else LF("Where %@ goes for walks, sitting and visits at home.", name),
        systemImage = "route",
        tint = Theme.mango,
        state = flash.state(saving = saving, dirty = dirty, valid = travelValue.first),
        invalidText = LF("Travel time goes from 0 to %d minutes.", 180),
        readOnlyText = if (canEdit) null else L("Only someone who can edit staff roles changes this."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { fill() },
        modifier = modifier,
    ) {
        if (area == null) {
            if (failed) Text(L("Couldn’t load the service area."), style = Theme.text(14), color = Theme.inkSoft) else TeamProgress(minHeight = 64.dp)
        } else {
            Disabled(!canEdit || saving) {
                PMTextField(
                    L("Postal codes"),
                    text = postal,
                    prompt = "06700, 067*",
                    hint = L("Exact (06700) or every code that starts the same (067*). Enter or a comma adds one."),
                    // iOS `.numbersAndPunctuation`: digits with the comma and the star.
                    keyboard = KeyboardType.Phone,
                )
            }
            Disabled(!canEdit || saving) {
                PMTextField(L("Zones"), text = zones, prompt = L("Condesa, Roma Norte"), hint = L("Neighbourhoods by name, as they appear in addresses (Condesa, Roma Norte)."))
            }
            Disabled(!canEdit || saving) {
                PMTextField(L("Travel time between homes"), text = travel, prompt = "20", hint = L("Kept free between two home visits at different addresses. Empty: none."), keyboard = KeyboardType.Number)
            }
        }
    }
}

// MARK: - Coming up

/** Their next visits, each opening its visit page. */
@Composable
fun TeamMemberUpcomingCard(
    name: String,
    visits: List<TeamVisit>,
    total: Int,
    loading: Boolean,
    failed: Boolean,
    /** The person, so "Open in the Schedule" opens their list. */
    memberId: String? = null,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    TeamPanel(title = L("Coming up"), subtitle = LF("Visits booked with %@.", name), systemImage = "calendar-days", tint = Theme.aqua, modifier = modifier) {
        if (loading) {
            TeamProgress(minHeight = 64.dp)
        } else if (failed) {
            Text(L("Their visits couldn’t load. Try again in a moment."), style = Theme.text(14), color = Theme.inkSoft)
        } else if (visits.isEmpty()) {
            EmptyState(systemImage = "calendar-days", title = LF("Nothing booked with %@ from today on.", name), accent = Theme.pink)
        } else {
            Column(Modifier.fillMaxWidth()) {
                visits.forEach { visit ->
                    if (visit.id != visits.firstOrNull()?.id) RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainButton { nav.push(ProviderRoute.appointment(visit.id)) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                listOfNotNull(visit.petName.nonEmpty, visit.serviceLabel.nonEmpty).joinToString(" · "),
                                style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(DateText.dayTime(visit.startsAt), visit.clientName.nonEmpty).joinToString(" · "),
                                style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            TeamMemberUpcomingCard.statusLabel(visit.status),
                            Modifier
                                .background(Theme.surfaceMuted, CapsuleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = Theme.text(11.2, FontWeight.Bold),
                            color = Theme.inkSoft,
                        )
                    }
                }
            }
        }
        if (memberId != null) {
            Text(
                if (total > visits.size) LF("See all %d in the Schedule", total) else L("Open in the Schedule"),
                Modifier.plainButton { nav.push(ProviderRoute.schedule(ScheduleFilter(memberId = memberId, view = "list"))) },
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blue,
            )
        } else if (total > visits.size) {
            Text(
                LF("See all %d in the Schedule", total),
                Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.appointments)) },
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blue,
            )
        }
    }
}

/** iOS `TeamMemberUpcomingCard.statusLabel(_:)`. */
object TeamMemberUpcomingCard {
    fun statusLabel(status: String?): String = when (status) {
        "requested" -> L("To confirm")
        "scheduled" -> L("Booked")
        "checked_in" -> L("Checked in")
        "in_progress" -> L("In the visit")
        "completed" -> L("Finished")
        "cancelled" -> L("Cancelled")
        "no_show" -> L("Didn’t come")
        else -> status ?: ""
    }
}
