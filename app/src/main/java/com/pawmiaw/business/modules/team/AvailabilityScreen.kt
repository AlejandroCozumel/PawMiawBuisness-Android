package com.pawmiaw.business.team

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.places.PlacesTimeZonePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.NumberFormat
import java.time.ZoneId
import java.time.ZonedDateTime

//
//  AvailabilityScreen.kt (iOS AvailabilityScreen.swift)
//
//  Availability of one location (web: routes/provider.availability.tsx), in
//  the order it adds up: the opening hours (a week editor, the time zone),
//  the booking rules with online booking (on/off, "Yo confirmo cada
//  reserva" / "Se confirman solas", notice, how far ahead, "Las familias
//  cancelan solas hasta…"), what families see this week, closed dates, and
//  the team's hours. Each part saves on its own.
//

private data class TeamHoursDraft(
    val timezone: String,
    val week: List<TeamDay>,
)

private data class TeamBookingRules(
    val duration: Int,
    val buffer: Int,
    val allowOnline: Boolean,
    val confirmByDefault: Boolean,
    val afterHours: Boolean,
    val notice: Int,
    val horizon: Int?,
    val confirmMode: String,
    /** The business's late-cancel window (the store's policy); null = not set (families read 24 h). */
    val lateCancel: Int?,
)

@Composable
fun AvailabilityScreen(modifier: Modifier = Modifier) {
    providerPage(L("Availability"), root = true, modifier = modifier) {
        RequireLocation { TeamAvailabilityPage() }
    }
}

/** One of "How it works"'s four steps. */
private class TeamAvailabilityStep(val id: String, val icon: String, val tint: Color, val title: String, val body: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeamAvailabilityPage() {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var server by remember { mutableStateOf<TeamAvailability?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var online by remember { mutableStateOf<TeamOnlineSettings?>(null) }
    var closures by remember { mutableStateOf<List<TeamClosure>>(emptyList()) }
    var closuresLoaded by remember { mutableStateOf(false) }
    var closuresError by remember { mutableStateOf<String?>(null) }
    var people by remember { mutableStateOf<List<TeamMember>>(emptyList()) }
    var teamHours by remember { mutableStateOf<Map<String, TeamMemberHours>>(emptyMap()) }
    var teamReady by remember { mutableStateOf(false) }
    var hoursDraft by remember { mutableStateOf<TeamHoursDraft?>(null) }
    var rulesDraft by remember { mutableStateOf<TeamBookingRules?>(null) }
    var savingPart by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    var pickingZone by remember { mutableStateOf(false) }
    // iOS `loadedFor: String??`: nil until the first load, then the location it was for.
    var loadedOnce by remember { mutableStateOf(false) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    val hoursFlash = remember { TeamSavedFlash() }
    val rulesFlash = remember { TeamSavedFlash() }

    val clinicId = workspace.current?.clinicId
    val locationId = workspace.locationId
    val canSeeStaff = workspace.can(Permission.staffView)
    val otherLocations = workspace.activeLocations.filter { it.id != workspace.selectedLocation?.id }

    val savedHours = server?.let { TeamHoursDraft(timezone = it.timezone ?: "UTC", week = TeamHours.ordered(it.weeklySchedule ?: emptyList())) }
    val savedRules = server?.let { s ->
        TeamBookingRules(
            duration = s.defaultAppointmentDurationMinutes ?: 30,
            buffer = s.defaultBufferMinutes ?: 0,
            allowOnline = s.allowOnlineBooking ?: false,
            confirmByDefault = s.requestAppointmentConfirmationByDefault ?: true,
            afterHours = s.allowAfterHoursBooking ?: true,
            notice = s.minBookingNoticeMinutes ?: 0,
            horizon = s.bookingHorizonDays,
            confirmMode = s.onlineConfirmMode ?: "approve",
            lateCancel = online?.lateCancelWindowHours,
        )
    }
    val hours = hoursDraft ?: savedHours
    val rules = rulesDraft ?: savedRules
    val hoursDirty = hoursDraft != null && savedHours != null &&
        !(hoursDraft!!.timezone == savedHours.timezone && TeamHours.sameWeek(hoursDraft!!.week, savedHours.week))
    val rulesDirty = rulesDraft != null && rulesDraft != savedRules

    // MARK: Loading

    suspend fun loadClosures() {
        try {
            val answer: TeamClosuresResponse = API.get("/provider-hours/closures", query = mapOf("locationId" to locationId))
            closures = answer.closures
            closuresError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            closuresError = errorText(e)
        }
        closuresLoaded = true
    }

    suspend fun loadTeam() {
        val id = clinicId ?: return
        if (!canSeeStaff) return
        val members = runCatching { API.get<List<TeamMember>>("/provider-workspaces/$id/members") }.getOrNull() ?: return
        val collator = Collator.getInstance(appLocale)
        people = members.filter { it.takesVisits }.sortedWith { a, b -> collator.compare(a.shownName, b.shownName) }
        val found = HashMap<String, TeamMemberHours>()
        for (person in people) {
            runCatching { API.get<TeamMemberHours>("/provider-hours/members/${person.id}") }.getOrNull()?.let { found[person.id] = it }
        }
        teamHours = found
        teamReady = true
    }

    suspend fun load() {
        val id = clinicId ?: return
        if (!workspace.can(Permission.workspaceEdit) || workspace.inSetup || workspace.allLocationsChosen) return
        // Another location's hours start from what it has saved, not from this one's draft.
        if (!loadedOnce || loadedFor != locationId) {
            hoursDraft = null
            rulesDraft = null
            loadedOnce = true
            loadedFor = locationId
        }
        try {
            server = API.get("/provider-workspaces/$id/availability", query = mapOf("locationId" to locationId))
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (server == null) loadError = errorText(e)
        }
        online = runCatching { API.get<TeamOnlineSettings>("/provider-online-booking/settings") }.getOrNull()
        loadClosures()
        loadTeam()
    }

    // MARK: Saving

    fun availabilityBody(hours: TeamHoursDraft?, rules: TeamBookingRules?): Map<String, Any?> {
        val s = server ?: return emptyMap()
        val saved = savedRules ?: return emptyMap()
        val r = rules ?: saved
        val body = mutableMapOf<String, Any?>(
            "timezone" to (hours?.timezone ?: s.timezone ?: "UTC"),
            "defaultAppointmentDurationMinutes" to r.duration,
            "allowAfterHoursBooking" to r.afterHours,
            "allowOnlineBooking" to r.allowOnline,
            "requestAppointmentConfirmationByDefault" to r.confirmByDefault,
            "defaultBufferMinutes" to r.buffer,
            "minBookingNoticeMinutes" to r.notice,
            "bookingHorizonDays" to r.horizon,
            "onlineConfirmMode" to r.confirmMode,
            "weeklySchedule" to TeamHours.body(hours?.week ?: (s.weeklySchedule ?: emptyList())),
        )
        // The window is the business's, kept in the store's policy: sent only when it changed.
        if (rules != null && rules.lateCancel != saved.lateCancel) body["lateCancelWindowHours"] = rules.lateCancel
        return body
    }

    fun save(part: String) {
        val id = clinicId ?: return
        val body = if (part == "hours") availabilityBody(hours = hoursDraft, rules = null) else availabilityBody(hours = null, rules = rulesDraft)
        var path = "/provider-workspaces/$id/availability"
        if (locationId != null) path += "?locationId=${Uri.encode(locationId)}"
        savingPart = part
        scope.launch {
            try {
                val updated: TeamAvailability = API.patch(path, body)
                server = updated
                if (part == "hours") {
                    hoursDraft = null
                    hoursFlash.mark()
                } else {
                    online = runCatching { API.get<TeamOnlineSettings>("/provider-online-booking/settings") }.getOrNull()
                    rulesDraft = null
                    rulesFlash.mark()
                }
                Workspace.shared.refresh()
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                savingPart = null
            }
        }
    }

    /** The week of another location, as a draft to look over and save here. */
    fun copyWeek(other: ProviderLocation) {
        val id = clinicId ?: return
        val current = hours ?: return
        copying = true
        scope.launch {
            try {
                val theirs: TeamAvailability = API.get("/provider-workspaces/$id/availability", query = mapOf("locationId" to other.id))
                hoursFlash.clear()
                hoursDraft = TeamHoursDraft(timezone = current.timezone, week = TeamHours.ordered(theirs.weeklySchedule ?: emptyList()))
                Toast.show(LF("%@’s week is here to look over. Save to keep it.", other.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                copying = false
            }
        }
    }

    // MARK: Header

    /** The page's module header (web `ProviderModuleHeader`, Clock3 on aqua). */
    @Composable
    fun Header(stats: List<ModuleStat>) {
        val selected = workspace.selectedLocation
        val subtitle = if (workspace.multiLocation && selected != null) {
            LF("Hours of %@", selected.name) + " · " +
                (if (workspace.canSwitchLocation) L("Each location keeps its own. Switch location in the top bar to edit another.") else L("Each location keeps its own."))
        } else {
            L("When you are open and how visits are booked. The Schedule, online booking and the double-booking check all read this.")
        }
        ModuleHeader(
            icon = ProviderSection.availability.headerIcon,
            title = L("Availability"),
            subtitle = subtitle,
            accent = ProviderSection.availability.headerAccent,
            stats = stats,
            storageKey = "availability",
        )
    }

    ReloadsOnScope { load() }

    val currentHours = hours
    val currentRules = rules
    if (!workspace.can(Permission.workspaceEdit)) {
        PageScroll { NoAccessState(message = L("Opening hours and booking rules are changed by someone who can edit the workspace. Ask the owner.")) }
    } else if (workspace.inSetup) {
        PageScroll {
            Header(emptyList())
            EmptyState(
                systemImage = "building-2",
                title = L("Finish workspace setup before setting your hours."),
                message = L("Opening hours belong to a workspace, so its basics need to be saved first."),
                accent = Theme.aqua,
                panel = true,
            )
        }
    } else if (currentHours != null && currentRules != null) {
        val screen = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
        val anchors = remember { mapOf("hours" to BringIntoViewRequester(), "rules" to BringIntoViewRequester(), "preview" to BringIntoViewRequester(), "closures" to BringIntoViewRequester(), "team" to BringIntoViewRequester()) }
        // iOS `proxy.scrollTo(id, anchor: .top)`: a screen-tall rect from the card's top lands it at the top.
        fun go(id: String) {
            val anchor = anchors[id] ?: return
            scope.launch { anchor.bringIntoView(Rect(0f, 0f, 1f, screen)) }
        }

        val stats = TeamHours.stats(currentHours.week)
        val summary = TeamHours.summary(currentHours.week)
        val today = TeamDays.today(currentHours.timezone)
        val nextClosure = TeamDays.sortSpans(
            closures.map { c -> val n = TeamClosureMath.occurrence(c, from = today); Triple(c, n.first, n.second) },
            today = today, start = { it.second }, end = { it.third },
        ).first.firstOrNull()
        val checksHours = !currentRules.afterHours
        val hoursAWeek = NumberFormat.getNumberInstance(appLocale).format(stats.second)

        PageScroll(refresh = { load() }) {
            Header(
                listOf(
                    ModuleStat(
                        "open", label = L("Open days"), value = LF("%d of 7", stats.first),
                        hint = summary.filter { it.third }.joinToString(", ") { it.first }.nonEmpty ?: L("Closed every day"),
                        icon = "calendar-check", accent = Theme.aqua, attention = stats.first == 0, action = { go("hours") },
                    ),
                    ModuleStat(
                        "hours", label = L("Hours a week"), value = LF("%@ h", hoursAWeek), hint = TeamHours.zoneLabel(currentHours.timezone),
                        icon = "hourglass", accent = Theme.blue, action = { go("hours") },
                    ),
                    ModuleStat(
                        "closed", label = L("Next closed date"),
                        value = if (!closuresLoaded || closuresError != null) "—" else (nextClosure?.let { TeamDays.short(it.second) } ?: L("None")),
                        hint = nextClosure?.let { it.first.label.nonEmpty ?: TeamClosureMath.hoursText(it.first.ranges) } ?: (if (closuresLoaded) L("Nothing coming up") else null),
                        icon = "calendar-off", accent = Theme.pink, action = { go("closures") },
                    ),
                    ModuleStat(
                        "buffer", label = L("Between visits"), value = if (currentRules.buffer == 0) L("No gap") else TeamHours.duration(currentRules.buffer),
                        hint = LF("Visits last %@", TeamHours.duration(currentRules.duration)),
                        icon = "calendar-clock", accent = Theme.mango, action = { go("rules") },
                    ),
                    ModuleStat(
                        "online", label = L("Online booking"), value = if (currentRules.allowOnline) L("On") else L("Off"),
                        hint = if (currentRules.allowOnline) confirmModeLabel(currentRules.confirmMode) else (if (checksHours) L("Hours are checked") else L("Hours are a guide")),
                        icon = "globe", accent = Theme.lime, action = { go("rules") },
                    ),
                )
            )
            HowItWorks { go(it) }
            OpeningHours(
                hours = currentHours,
                savedHours = savedHours,
                hoursDraft = hoursDraft,
                checksHours = checksHours,
                saving = savingPart == "hours",
                dirty = hoursDirty,
                flash = hoursFlash,
                otherLocations = otherLocations,
                copying = copying,
                onDraft = { hoursDraft = it },
                onPickZone = { pickingZone = true },
                onCopy = { copyWeek(it) },
                onSave = { save("hours") },
                modifier = Modifier.bringIntoViewRequester(anchors.getValue("hours")),
            )
            BookingRules(
                rules = currentRules,
                online = online,
                saving = savingPart == "rules",
                dirty = rulesDirty,
                flash = rulesFlash,
                onDraft = { rulesDraft = it },
                onSave = { save("rules") },
                modifier = Modifier.bringIntoViewRequester(anchors.getValue("rules")),
            )
            TeamWeekPreviewCard(
                days = preview(hours = currentHours, rules = currentRules, today = today, closures = closures, people = people, teamHours = teamHours, canSeeStaff = canSeeStaff, teamReady = teamReady, locationId = locationId),
                online = currentRules.allowOnline,
                notice = currentRules.notice,
                horizon = currentRules.horizon,
                canSeeStaff = canSeeStaff,
                unsaved = hoursDirty || rulesDirty,
                modifier = Modifier.bringIntoViewRequester(anchors.getValue("preview")),
            )
            TeamClosuresCard(
                locationId = locationId,
                timeZone = server?.timezone ?: currentHours.timezone,
                weeklySchedule = savedHours?.week ?: currentHours.week,
                canEdit = true,
                otherLocations = otherLocations,
                closures = closures,
                loaded = closuresLoaded,
                error = closuresError,
                modifier = Modifier.bringIntoViewRequester(anchors.getValue("closures")),
                reload = { loadClosures() },
            )
            TeamCard(
                people = people,
                teamHours = teamHours,
                teamReady = teamReady,
                canSeeStaff = canSeeStaff,
                locationId = locationId,
                today = today,
                modifier = Modifier.bringIntoViewRequester(anchors.getValue("team")),
            )
            if (clinicId != null) {
                AuditHistoryCard(
                    entityType = "clinic_availability",
                    entityId = clinicId,
                    title = L("History"),
                    description = L("Every change to the hours and booking rules."),
                    collapsible = false,
                    emptyText = L("No changes yet."),
                )
            }
        }
    } else if (loadError != null) {
        PageScroll {
            Header(emptyList())
            val card = RoundedCornerShape(Theme.cardRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surface, card)
                    .border(1.dp, Theme.borderSoft, card)
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(L("The hours couldn’t load."), style = Theme.heading(20), color = Theme.inkStrong)
                Text(L("Check your connection and try again."), style = Theme.text(14), color = Theme.inkSoft)
                PMButton(L("Try again"), systemImage = "refresh-cw", variant = ButtonVariant.outline, size = ButtonSize.compact, modifier = Modifier.padding(top = 12.dp)) {
                    scope.launch { load() }
                }
            }
        }
    } else {
        PageScroll {
            Header(emptyList())
            LoadingState()
        }
    }

    // Leaving with unsaved hours or rules asks first.
    TeamLeaveGuard(
        dirty = hoursDirty || rulesDirty,
        message = if (hoursDirty && rulesDirty) L("The opening hours and the booking rules have changes that aren’t saved.")
        else if (hoursDirty) L("The opening hours have changes that aren’t saved.") else L("The booking rules have changes that aren’t saved."),
        systemImage = "clock-3",
    )

    if (pickingZone) {
        AppSheet(onDismissRequest = { pickingZone = false }) {
            TeamTimeZonePicker(selection = hours?.timezone ?: "UTC") { zone ->
                val current = hours
                if (current != null) {
                    hoursFlash.clear()
                    hoursDraft = TeamHoursDraft(timezone = zone, week = current.week)
                }
            }
        }
    }
}

private fun confirmModeLabel(mode: String): String =
    if (mode == "instant") L("They confirm themselves") else L("I confirm each booking")

// MARK: How it works

@Composable
private fun HowItWorks(go: (String) -> Unit) {
    val steps = listOf(
        TeamAvailabilityStep("hours", "clock-3", Theme.aqua, L("Opening hours"), L("When this location is open. Everything else happens inside them.")),
        TeamAvailabilityStep("team", "users-round", Theme.pink, L("Each person’s hours"), L("The location’s, or their own inside them. Hours of their own outside opening hours don’t count.")),
        TeamAvailabilityStep("closures", "calendar-off", Theme.mango, L("Exceptions"), L("Closed dates and special hours for the place; days off for a person.")),
        TeamAvailabilityStep("preview", "eye", Theme.lime, L("What families see"), L("Times when the place is open and someone is working, after the notice you ask for.")),
    )
    Card(padding = 8.dp, spacing = 4.dp) {
        TeamAdaptiveGrid(items = steps.withIndex().toList(), minimum = 260.dp, spacing = 4.dp) { (index, step) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton { go(step.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(36.dp).background(Theme.wash(step.tint, 30.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(step.icon, 16.dp, tint = Theme.inkStrong)
                }
                Column(Modifier.weight(1f)) {
                    val soft = Theme.inkSoft
                    val strong = Theme.inkStrong
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = soft, fontFeatureSettings = "tnum")) { append("${index + 1}.") }
                            withStyle(SpanStyle(color = strong)) { append(" " + step.title) }
                        },
                        style = Theme.text(14, FontWeight.Bold),
                    )
                    Text(step.body, style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    }
}

// MARK: Opening hours

@Composable
private fun OpeningHours(
    hours: TeamHoursDraft,
    savedHours: TeamHoursDraft?,
    hoursDraft: TeamHoursDraft?,
    checksHours: Boolean,
    saving: Boolean,
    dirty: Boolean,
    flash: TeamSavedFlash,
    otherLocations: List<ProviderLocation>,
    copying: Boolean,
    onDraft: (TeamHoursDraft?) -> Unit,
    onPickZone: () -> Unit,
    onCopy: (ProviderLocation) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val valid = hours.week.all { TeamHours.dayIsValid(it) }
    val device = ZoneId.systemDefault().id
    TeamSaveSection(
        title = L("Opening hours"),
        subtitle = L("Open time in the Schedule, and the only times families can book online."),
        systemImage = "clock-3",
        tint = Theme.aqua,
        state = flash.state(saving = saving, dirty = dirty, valid = valid),
        saveLabel = L("Save hours"),
        invalidText = L("Some hours end before they start or overlap. Fix them to save."),
        onSave = onSave,
        onDiscard = { onDraft(null) },
        modifier = modifier,
        aside = {
            // The web's TimezoneField, in the card's header.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("earth", 14.dp, tint = Theme.inkSoft)
                    Text(L("Time zone").uppercase(appLocale), style = Theme.text(12, FontWeight.Bold).tracking(1.4), color = Theme.inkSoft)
                }
                PillField(
                    text = TeamHours.zoneLabel(hours.timezone),
                    icon = "chevrons-up-down",
                    height = 40.dp,
                    modifier = Modifier.plainButton(enabled = !saving, onClick = onPickZone),
                )
                if (device != hours.timezone && !saving) {
                    Text(
                        LF("Use this device’s (%@)", TeamHours.zoneLabel(device)),
                        Modifier.plainButton {
                            flash.clear()
                            onDraft(TeamHoursDraft(timezone = device, week = hours.week))
                        },
                        style = Theme.text(12, FontWeight.Bold),
                        color = Theme.blue,
                    )
                }
            }
        },
    ) {
        if (savedHours != null && hoursDraft != null && hoursDraft.timezone != savedHours.timezone) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                message = L("Changing the time zone doesn’t move what is booked: each visit keeps its moment, so it will read at a different hour here. Check the Schedule after saving."),
                icon = "triangle-alert",
            )
        }
        Disabled(saving) {
            FlowLayout(spacing = 8.dp) {
                PMButton(L("Close weekends"), variant = ButtonVariant.outline, size = ButtonSize.compact) {
                    flash.clear()
                    onDraft(TeamHoursDraft(timezone = hours.timezone, week = hours.week.map { if (TeamHours.weekend.contains(it.day)) TeamHours.day(it.day, emptyList()) else it }))
                }
                PMButton(L("Start over: weekdays 9 to 5"), variant = ButtonVariant.outline, size = ButtonSize.compact) {
                    flash.clear()
                    onDraft(TeamHoursDraft(timezone = hours.timezone, week = TeamHours.defaultWeek))
                }
                if (otherLocations.isNotEmpty()) {
                    AppMenu(
                        label = {
                            Row(
                                Modifier
                                    .height(36.dp)
                                    .background(Theme.surface, CapsuleShape)
                                    .border(1.dp, Theme.borderSoft, CapsuleShape)
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Glyph("copy", 14.dp, tint = Theme.inkStrong)
                                Text(L("Copy from another location"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                            }
                        },
                        enabled = !copying,
                    ) {
                        otherLocations.forEach { other -> item(other.name) { onCopy(other) } }
                    }
                }
            }
        }
        TeamWeekEditor(
            schedule = stateOf({ hours.week }, { week -> flash.clear(); onDraft(TeamHoursDraft(timezone = hours.timezone, week = week)) }),
            disabled = saving,
            closedHint = if (checksHours) L("Closed: booking this day asks staff to confirm, and families can’t book it online.") else L("Closed: grey in the Schedule, and families can’t book it online."),
        )
        Text(
            L("For a lunch break or a split shift, add a break or more hours to a day. Changing the hours doesn’t move or cancel visits already booked: check the Schedule for any left outside them."),
            style = Theme.text(14),
            color = Theme.inkSoft,
        )
    }
}

// MARK: Booking rules

@Composable
private fun BookingRules(
    rules: TeamBookingRules,
    online: TeamOnlineSettings?,
    saving: Boolean,
    dirty: Boolean,
    flash: TeamSavedFlash,
    onDraft: (TeamBookingRules?) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    // `.disabled(saving)` on the whole group: nothing changes while it saves.
    fun set(change: (TeamBookingRules) -> TeamBookingRules) {
        if (saving) return
        flash.clear()
        onDraft(change(rules))
    }
    TeamSaveSection(
        title = L("Booking rules"),
        subtitle = L("How new visits are made, by your team and by families."),
        systemImage = "settings-2",
        tint = Theme.mango,
        state = flash.state(saving = saving, dirty = dirty),
        saveLabel = L("Save rules"),
        onSave = onSave,
        onDiscard = { onDraft(null) },
        modifier = modifier,
    ) {
        Disabled(saving) {
            MenuField(
                label = L("Usual visit length"),
                options = TeamHours.withSaved(TeamHours.durationOptions, rules.duration).map { it to TeamHours.duration(it) },
                selection = stateOf({ rules.duration }, { v -> set { it.copy(duration = v) } }),
            )
            Text(L("New visits start at this length; each one can still be changed."), style = Theme.text(14), color = Theme.inkSoft)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Time between visits"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                Text(L("Kept free after each visit to clean up. Double bookings and free times online count it."), style = Theme.text(14), color = Theme.inkSoft)
                FlowLayout(spacing = 6.dp) {
                    TeamHours.bufferOptions.forEach { minutes ->
                        FilterChip(title = if (minutes == 0) L("None") else TeamHours.duration(minutes), isSelected = rules.buffer == minutes) {
                            set { it.copy(buffer = minutes) }
                        }
                    }
                }
            }
            ToggleRow(
                title = L("Families can ask for visits online"),
                subtitle = if (rules.confirmMode == "instant")
                    L("Linked families book a free time from the PawMiaw app, inside opening hours. It lands in the Schedule already confirmed, like any other visit.")
                else
                    L("Linked families book a free time from the PawMiaw app, inside opening hours. It arrives as a request on Home and in the Schedule, holding its time until you answer it."),
                isOn = stateOf({ rules.allowOnline }, { v -> set { it.copy(allowOnline = v) } }),
            )
            val box = RoundedCornerShape(Theme.boxRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (rules.allowOnline) Color.Transparent else Theme.wash(Theme.surfaceMuted, 45.0), box)
                    .border(1.dp, Theme.borderSoft, box)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(L("How bookings are confirmed"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                ChoiceCards(
                    options = listOf(
                        Triple("approve", L("I confirm each booking"), L("The time is held while you answer: confirm it, move it or decline it. With no answer it is released.")),
                        Triple("instant", L("They confirm themselves"), L("A booking goes straight into the Schedule, like one your team made.")),
                    ),
                    selection = stateOf({ rules.confirmMode }, { v -> set { it.copy(confirmMode = v) } }),
                )
                MenuField(
                    label = L("Minimum notice for online booking"),
                    options = TeamHours.withSaved(TeamHours.noticeOptions, rules.notice).map { it to TeamHours.notice(it) },
                    selection = stateOf({ rules.notice }, { v -> set { it.copy(notice = v) } }),
                )
                Text(L("How long before a visit families can still book it."), style = Theme.text(14), color = Theme.inkSoft)
                MenuField(
                    label = L("How far ahead families can book"),
                    options = listOf(-1 to TeamHours.horizon(null)) +
                        (rules.horizon?.let { TeamHours.withSaved(TeamHours.horizonOptions, it) } ?: TeamHours.horizonOptions).map { it to TeamHours.horizon(it) },
                    selection = stateOf({ rules.horizon ?: -1 }, { v -> set { it.copy(horizon = if (v < 0) null else v) } }),
                )
                Text(L("The latest day families see free times for."), style = Theme.text(14), color = Theme.inkSoft)
                val window = rules.lateCancel ?: (online?.familyCancelWindowHours ?: 24)
                MenuField(
                    label = L("Families cancel by themselves until"),
                    options = TeamHours.withSaved(TeamHours.lateCancelOptions, window).map { it to LP(it, "1 hour before", "%d hours before") },
                    selection = stateOf({ window }, { v -> set { it.copy(lateCancel = v) } }),
                )
                Text(
                    L("After that they ask you, and a late cancellation can carry your fee. One window for the whole business: it is also the late-cancel window in Store › Missed visits."),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
                Text(
                    if (rules.allowOnline) L("These two only apply to families booking online and to the link to ask for another time. Your team can always book any time.")
                    else L("Online booking is off, so these wait until it’s on. They never limit your team."),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
            }
            ToggleRow(
                title = L("Ask families to confirm new visits"),
                subtitle = L("A new visit emails the family a link to confirm or ask for another time. It can be switched off on each visit."),
                isOn = stateOf({ rules.confirmByDefault }, { v -> set { it.copy(confirmByDefault = v) } }),
            )
            ToggleRow(
                title = L("Check opening hours when booking"),
                subtitle = if (rules.afterHours) L("Off: the hours are only a guide; staff can book any time without a question.")
                else L("On: a visit outside the hours asks staff to confirm, and is marked as booked out of hours."),
                isOn = stateOf({ !rules.afterHours }, { v -> set { it.copy(afterHours = !v) } }),
            )
            if (workspace.offers(BusinessCategory.emergency)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.surfaceMuted, 45.0), box)
                        .border(1.dp, Theme.borderSoft, box)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Glyph("siren", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                        Text(L("Emergencies don’t follow these rules: who is on call and the out-of-hours charge are set in the Hospital."), style = Theme.text(14), color = Theme.inkSoft)
                    }
                    if (workspace.can(Permission.hospitalView)) {
                        Text(
                            L("See who is on call"),
                            Modifier
                                .padding(start = 24.dp)
                                .plainButton { nav.push(ProviderRoute.section(ProviderSection.hospital)) },
                            style = Theme.text(14, FontWeight.Bold),
                            color = Theme.blue,
                        )
                    }
                }
            }
        }
    }
}

// MARK: The team

@Composable
private fun TeamCard(
    people: List<TeamMember>,
    teamHours: Map<String, TeamMemberHours>,
    teamReady: Boolean,
    canSeeStaff: Boolean,
    locationId: String?,
    today: String,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    TeamPanel(
        title = L("Your team’s hours"),
        subtitle = L("Everyone who takes visits works these opening hours unless they have their own. Each person’s own hours and days off are set on their page; they can’t be booked online outside the location’s hours."),
        systemImage = "users-round",
        tint = Theme.pink,
        modifier = modifier,
    ) {
        if (!canSeeStaff) {
            Text(L("Your role can’t see the team list."), style = Theme.text(14), color = Theme.inkSoft)
        } else if (people.isEmpty()) {
            if (teamReady) {
                EmptyState(systemImage = "users-round", title = L("Nobody takes visits yet. A person takes visits when their role can start and finish them."))
            } else {
                TeamProgress(minHeight = 64.dp)
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                people.forEach { member ->
                    androidx.compose.runtime.key(member.id) {
                        if (member.id != people.firstOrNull()?.id) RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .plainButton { nav.push(ProviderRoute.staffMember(member.id)) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(name = member.shownName, imageURL = member.image, size = 36.dp, tint = Theme.aqua)
                            Column(Modifier.weight(1f)) {
                                Text(member.shownName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (member.atOnce > 1) LF("%d pets at once", member.atOnce) else L("One pet at a time"),
                                    style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                memberHint(teamHours[member.id], locationId, today)?.let {
                                    Text(it, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Text(LP(people.size, "1 person takes visits", "%d people take visits"), style = Theme.text(14), color = Theme.inkSoft)
        }
        if (canSeeStaff) {
            Text(
                L("Change who takes visits and how many at once"),
                Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.staff)) },
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blue,
            )
        }
    }
}

/** The person's entry at this location: the chosen one, or their main one when none is chosen. */
private fun hereFor(entry: TeamMemberHours, locationId: String?): TeamMemberLocationHours? =
    entry.locations?.firstOrNull { if (locationId != null) it.locationId == locationId else (it.isPrimary ?: false) }

/** "Own hours · Off 24 dic – 26 dic". */
private fun memberHint(entry: TeamMemberHours?, locationId: String?, today: String): String? {
    if (entry == null) return null
    val here = hereFor(entry, locationId)
    val parts = ArrayList<String>()
    if (here != null) parts.add(if (here.usesLocationHours ?: true) L("The location’s hours") else L("Own hours"))
    TeamDays.sortSpans(entry.timeOff ?: emptyList(), today = today, start = { it.startDay }, end = { it.endDay }).first.firstOrNull()?.let { next ->
        parts.add(if (next.startDay <= today) L("Off today") else LF("Off %@", TeamDays.rangeLabel(next.startDay, next.endDay, today = today)))
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

// MARK: What families see

private fun preview(
    hours: TeamHoursDraft,
    rules: TeamBookingRules,
    today: String,
    closures: List<TeamClosure>,
    people: List<TeamMember>,
    teamHours: Map<String, TeamMemberHours>,
    canSeeStaff: Boolean,
    teamReady: Boolean,
    locationId: String?,
): List<TeamPreviewDay> {
    val zone = runCatching { ZoneId.of(hours.timezone) }.getOrNull() ?: ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val earliest = now.hour * 60 + now.minute + maxOf(0, rules.notice)
    // Who works here, as "what families see" counts them; null until the team's hours are in.
    val persons: List<Pair<List<TeamDay>?, List<TeamTimeOff>>>? = if (canSeeStaff && teamReady) people.mapNotNull { member ->
        val entry = teamHours[member.id] ?: return@mapNotNull null
        val here = hereFor(entry, locationId) ?: return@mapNotNull null
        (if (here.usesLocationHours ?: true) null else (here.weeklySchedule ?: emptyList())) to (entry.timeOff ?: emptyList())
    } else null
    return (0 until 7).map { index ->
        val date = TeamDays.add(today, index)
        val closure = TeamClosureMath.on(date, closures)
        val open = closure?.let { it.ranges ?: emptyList() } ?: TeamHours.ranges(hours.week.firstOrNull { it.day == TeamDays.weekday(date) })
        val openMinutes = TeamHours.minuteRanges(open).sortedBy { it.first }
        val count: Int? = persons?.let { list ->
            if (openMinutes.isEmpty()) 0 else list.count { (own, timeOff) ->
                if (timeOff.any { it.startDay <= date && date <= it.endDay }) return@count false
                if (own == null) return@count true
                val theirs = TeamHours.minuteRanges(TeamHours.ranges(own.firstOrNull { it.day == TeamDays.weekday(date) }))
                TeamHours.intersect(openMinutes, theirs).isNotEmpty()
            }
        }
        val day = TeamPreviewDay(date = date, open = open, bookable = emptyList(), closureLabel = closure?.label, closureClosed = closure?.let { it.ranges == null }, why = null, people = count)
        if (openMinutes.isEmpty()) {
            day.why = if (closure != null) TeamPreviewDay.Why.closedDate else TeamPreviewDay.Why.closedDay
            return@map day
        }
        val horizon = rules.horizon
        if (horizon != null && index > horizon) {
            day.why = TeamPreviewDay.Why.horizon
            return@map day
        }
        val from = earliest - index * 1440
        val left = if (from <= 0) openMinutes else TeamHours.intersect(openMinutes, listOf(minOf(from, 1440) to 1440))
        if (left.isEmpty()) {
            day.why = if (index == 0 && rules.notice == 0) TeamPreviewDay.Why.over else TeamPreviewDay.Why.notice
            return@map day
        }
        if (count == 0) {
            day.why = TeamPreviewDay.Why.nobody
            return@map day
        }
        day.bookable = left.map { TeamTimeRange(start = TeamHours.time(it.first), end = TeamHours.time(it.second)) }
        day
    }
}

class TeamPreviewDay(
    val date: String,
    val open: List<TeamTimeRange>,
    var bookable: List<TeamTimeRange>,
    val closureLabel: String?,
    val closureClosed: Boolean?,
    var why: Why?,
    val people: Int?,
) {
    enum class Why { closedDay, closedDate, notice, horizon, over, nobody }
}

/** "What families see this week": when each of the next days can be booked online, and why not. */
@Composable
fun TeamWeekPreviewCard(
    days: List<TeamPreviewDay>,
    online: Boolean,
    notice: Int,
    horizon: Int?,
    canSeeStaff: Boolean,
    unsaved: Boolean,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val rulesLine = if (notice > 0) {
        if (horizon != null) LF("Families book at least %1\$@ before, and up to %2\$@ ahead.", TeamHours.notice(notice), TeamHours.horizon(horizon))
        else LF("Families book at least %@ before, as far ahead as they like.", TeamHours.notice(notice))
    } else {
        if (horizon != null) LF("Families book up to the last minute, and up to %@ ahead.", TeamHours.horizon(horizon))
        else L("Families book up to the last minute, as far ahead as they like.")
    }

    fun line(day: TeamPreviewDay): String {
        if (day.bookable.isNotEmpty()) {
            val special = if (day.closureClosed == false) " · " + (day.closureLabel.nonEmpty ?: L("Special hours")) else ""
            return TeamHours.rangesLabel(day.bookable) + special
        }
        return when (day.why) {
            TeamPreviewDay.Why.closedDay -> L("Closed")
            TeamPreviewDay.Why.closedDate -> day.closureLabel.nonEmpty?.let { LF("Closed · %@", it) } ?: L("Closed that day")
            TeamPreviewDay.Why.notice -> L("Too soon to book online")
            TeamPreviewDay.Why.horizon -> L("Further ahead than families can book")
            TeamPreviewDay.Why.over -> L("Already closed today")
            TeamPreviewDay.Why.nobody -> L("Open, but nobody takes visits")
            null -> L("Closed")
        }
    }

    TeamPanel(
        title = L("What families see this week"),
        subtitle = if (online) L("The days and hours a family can ask for online, from today.") else L("Online booking is off: families can’t book a time themselves. This is what they would see with it on."),
        systemImage = "eye",
        tint = Theme.lime,
        modifier = modifier,
    ) {
        val box = RoundedCornerShape(Theme.boxRadius)
        if (unsaved) {
            Text(
                L("With the changes you haven’t saved yet."),
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.mango, 12.0), box)
                    .border(1.dp, Theme.mix(Theme.mango, 55.0, Theme.borderSoft), box)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = Theme.text(14, FontWeight.SemiBold),
                color = Theme.inkStrong,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Theme.borderSoft, box)
                .alpha(if (online) 1f else 0.8f),
        ) {
            days.forEachIndexed { index, day ->
                if (index > 0) RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.weight(1f).alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (index == 0) L("Today") else TeamDays.weekdayLabel(day.date),
                                style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (index == 0) {
                                Text(TeamDays.weekdayLabel(day.date), style = Theme.text(14, FontWeight.Medium), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        val count = day.people
                        if (count != null && day.open.isNotEmpty()) {
                            val ink = if (count == 0) Theme.destructive else Theme.inkSoft
                            Row(Modifier.alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph("users-round", 14.dp, tint = ink)
                                Text(if (count == 0) L("Nobody working") else LP(count, "1 person", "%d people"), style = Theme.text(12, FontWeight.SemiBold), color = ink)
                            }
                        }
                    }
                    Text(line(day), style = Theme.text(14), color = if (day.bookable.isEmpty()) Theme.inkSoft else Theme.inkStrong)
                    if (day.bookable.isNotEmpty()) TeamRangesBar(ranges = day.bookable, guide = day.open, showHours = false, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Glyph("globe", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
            Text(rulesLine + " " + L("The free times they are offered also leave out what is already booked."), style = Theme.text(14), color = Theme.inkSoft)
        }
        if (canSeeStaff && days.any { it.why == TeamPreviewDay.Why.nobody }) {
            val blue = Theme.blue
            Text(
                buildAnnotatedString {
                    append(L("A day is open with nobody working when everyone who takes visits is off or has other hours."))
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = blue)) { append(L("See who takes visits")) }
                },
                Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.staff)) },
                style = Theme.text(14),
                color = Theme.inkSoft,
            )
        }
    }
}

/**
 * The clock the hours are in (web `TimezoneField`): the one time zone picker, with this
 * device's zone one tap away when it differs (also the setup checklist's).
 */
@Composable
fun TeamTimeZonePicker(selection: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val device = ZoneId.systemDefault().id
    PlacesTimeZonePicker(
        selection = selection,
        suggestions = if (device != selection) listOf(device) else emptyList(),
        notes = mapOf(device to L("This device’s time zone")),
        modifier = modifier,
        onPick = onPick,
    )
}
