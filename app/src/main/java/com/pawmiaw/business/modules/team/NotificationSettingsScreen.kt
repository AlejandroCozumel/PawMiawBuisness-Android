package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

//
//  NotificationSettingsScreen.kt (iOS NotificationSettingsScreen.swift)
//
//  Your notifications (web: Settings › Your notifications,
//  components/app/settings/notifications-section.tsx): which alerts from
//  this workspace reach this person, in the app or by email, and how far
//  ahead. Each teammate chooses their own. "News and offers" saves at once.
//

/**
 * The old address of the notification settings. On the web it redirects to
 * Settings › Your notifications; here it opens that same section, under
 * Settings' header.
 */
@Composable
fun NotificationSettingsScreen(modifier: Modifier = Modifier) {
    TeamSettingsSectionScreen(section = "notifications", modifier = modifier)
}

private data class TeamNotificationChoice(
    val category: String,
    val inApp: Boolean,
    val email: Boolean,
    val push: Boolean,
    val leadTimes: List<Int>,
)

private class TeamTiming(
    val title: String,
    val description: String,
    /** (value, label). */
    val options: List<Pair<Int, String>>,
    val defaults: List<Int>,
)

private object TeamNotificationCopy {
    /** The workspace's kinds of alert, in order. */
    val categories = listOf("appointments", "provider_follow_ups", "records_documentation", "billing", "client_messages", "client_facing_reminders", "security")
    val emailByDefault = setOf("appointments", "provider_follow_ups", "records_documentation", "billing", "client_facing_reminders", "security")

    fun label(category: String): String = when (category) {
        "appointments" -> L("Appointments")
        "provider_follow_ups" -> L("Provider follow-ups")
        "records_documentation" -> L("Records and documentation")
        "billing" -> L("Billing")
        "client_messages" -> L("Client messages")
        "client_facing_reminders" -> L("Client reminders")
        "security" -> L("Security")
        else -> category
    }

    fun description(category: String): String = when (category) {
        "appointments" -> L("Upcoming visits and what to get ready for them.")
        "provider_follow_ups" -> L("Follow-ups your team has to make: calls, rechecks and reminders that are due.")
        "records_documentation" -> L("Missing documents, chart updates, and record paperwork.")
        "billing" -> L("Bills, payment reminders and what is still owed.")
        "client_messages" -> L("Messages from clients that are waiting for an answer.")
        "client_facing_reminders" -> L("The reminders your business sends to its clients.")
        "security" -> L("Account security and sensitive access events.")
        else -> ""
    }

    fun icon(category: String): String = when (category) {
        "appointments" -> "calendar-clock"
        "provider_follow_ups" -> "clipboard-check"
        "records_documentation" -> "file-text"
        "billing" -> "credit-card"
        "client_messages" -> "message-circle"
        "client_facing_reminders" -> "bell"
        "security" -> "lock"
        else -> "bell"
    }

    /** The web's accent for each kind of alert (CATEGORY_META). */
    fun accent(category: String): Color = when (category) {
        "appointments", "client_messages" -> Theme.pink
        "provider_follow_ups", "client_facing_reminders" -> Theme.lime
        "records_documentation" -> Theme.aqua
        "billing", "security" -> Theme.mango
        else -> Theme.aqua
    }

    fun timing(category: String): TeamTiming? {
        val day = 24 * 60
        return when (category) {
            "appointments" -> TeamTiming(L("Appointment reminders"), L("How long before each visit you are reminded."), listOf(day to L("24h before"), 60 to L("1h before"), 0 to L("At visit time")), listOf(day, 60, 0))
            "provider_follow_ups" -> TeamTiming(L("Follow-up reminders"), L("How long before a follow-up is due you are reminded."), listOf(day to L("1d before"), 60 to L("1h before"), 0 to L("Due time")), listOf(60, 0))
            "records_documentation" -> TeamTiming(L("Record reminders"), L("How long before a record is due you are reminded."), listOf(day to L("1d before"), 60 to L("1h before"), 0 to L("Due time")), listOf(60, 0))
            "billing" -> TeamTiming(L("Bill reminders"), L("How long before a bill is due you are reminded."), listOf(3 * day to L("3d before"), day to L("1d before"), 0 to L("Due day")), listOf(day))
            "client_facing_reminders" -> TeamTiming(L("Client reminders"), L("How long before the due date they go out."), listOf(7 * day to L("7d before"), day to L("1d before"), 0 to L("Due day")), listOf(day, 0))
            else -> null
        }
    }

    /** (title, description). */
    fun notice(category: String): Pair<String, String> = when (category) {
        "client_messages" -> L("Message alert") to L("Sent as soon as a message needs an answer.")
        "security" -> L("Security alert") to L("Sent right away for sensitive account or session activity.")
        else -> L("Sent when it happens") to L("Sent when it happens: there is no timing to choose.")
    }

    fun normalized(category: String, values: List<Int>?): List<Int> {
        val timing = timing(category) ?: return if (values != null && values.isNotEmpty()) values else listOf(24 * 60, 0)
        val allowed = timing.options.map { it.first }.toSet()
        val seen = HashSet<Int>()
        val kept = (values ?: emptyList()).filter { allowed.contains(it) && seen.add(it) }
        return if (kept.isEmpty()) timing.defaults else kept
    }
}

/** Which alerts from this workspace reach this person, and how. */
@Composable
fun TeamNotificationsSection(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<List<TeamNotificationChoice>>(emptyList()) }
    var draft by remember { mutableStateOf<List<TeamNotificationChoice>>(emptyList()) }
    var channels by remember { mutableStateOf<List<TeamNotificationChannel>>(emptyList()) }
    var timezone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var marketing by remember { mutableStateOf<Boolean?>(null) }
    var marketingFailed by remember { mutableStateOf(false) }
    var savingMarketing by remember { mutableStateOf(false) }
    val flash = remember { TeamSavedFlash() }

    suspend fun load() {
        val clinicId = workspace.current?.clinicId ?: return
        try {
            val answer: TeamNotificationPreferences = API.get("/notification-preferences", query = mapOf("contextType" to "provider_workspace", "contextId" to clinicId))
            val byCategory = HashMap<String, TeamNotificationPreference>()
            for (item in answer.items ?: emptyList()) if (!byCategory.containsKey(item.category)) byCategory[item.category] = item
            val next = TeamNotificationCopy.categories.map { category ->
                val existing = byCategory[category]
                if (existing != null) {
                    TeamNotificationChoice(category, existing.inAppEnabled ?: true, existing.emailEnabled ?: false, existing.pushEnabled ?: true, TeamNotificationCopy.normalized(category, existing.reminderLeadTimes))
                } else {
                    TeamNotificationChoice(category, true, TeamNotificationCopy.emailByDefault.contains(category), true, TeamNotificationCopy.normalized(category, null))
                }
            }
            // The draft follows what is saved while it is untouched.
            if (draft == saved) draft = next
            saved = next
            channels = answer.channels ?: emptyList()
            answer.items?.firstOrNull()?.timezone?.takeIf { it.isNotEmpty() }?.let { timezone = it }
            loaded = true
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = !loaded
        }
    }

    suspend fun loadMarketing() {
        try {
            val answer: TeamMarketingEmail = API.get("/notification-preferences/marketing-email")
            marketing = answer.enabled ?: false
            marketingFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            marketingFailed = true
        }
    }

    fun changeMarketing(next: Boolean) {
        val before = marketing
        marketing = next
        savingMarketing = true
        scope.launch {
            try {
                val answer: TeamMarketingEmail = API.put("/notification-preferences/marketing-email", mapOf("enabled" to next))
                marketing = answer.enabled ?: next
                Toast.show(if (next) L("You will get news and offers by email.") else L("No more news and offers by email."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                marketing = before
                Toast.error(errorText(e, fallback = L("Could not save. Try again.")))
            } finally {
                savingMarketing = false
            }
        }
    }

    fun save() {
        val clinicId = workspace.current?.clinicId ?: return
        // Only the rows that were changed are written: a choice nobody made is never saved for them.
        val changed = draft.filter { item -> !saved.contains(item) }
        if (changed.isEmpty()) return
        saving = true
        scope.launch {
            try {
                API.put<EmptyResponse>(
                    "/notification-preferences",
                    mapOf(
                        "contextType" to "provider_workspace",
                        "contextId" to clinicId,
                        "timezone" to timezone,
                        "categories" to changed.map {
                            mapOf(
                                "category" to it.category, "inAppEnabled" to it.inApp, "emailEnabled" to it.email,
                                "pushEnabled" to it.push, "smsEnabled" to false, "reminderLeadTimes" to it.leadTimes,
                            )
                        },
                    ),
                )
                saved = draft
                flash.mark()
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(errorText(e, fallback = L("Could not save notification preferences.")))
            } finally {
                saving = false
            }
        }
    }

    ReloadsOnScope {
        load()
        loadMarketing()
    }

    val futureChannels = channels.filter { !(it.availableNow ?: false) && it.key != "push" }.mapNotNull { it.label }
        .let { if (it.isEmpty()) "SMS, WhatsApp" else it.joinToString(", ") }

    TeamSaveSection(
        title = L("Your notifications"),
        subtitle = L("Which alerts from this workspace reach you, and how. Each teammate chooses their own."),
        systemImage = "bell",
        tint = Theme.aqua,
        look = TeamCardLook.settings,
        state = flash.state(saving = saving, dirty = draft != saved),
        saveLabel = L("Save notifications"),
        onSave = if (loaded) ({ save() }) else null,
        onDiscard = { draft = saved },
        modifier = modifier,
    ) {
        if (!loaded) {
            if (failed) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(L("Could not load your notifications."), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    PMButton(L("Try again"), variant = ButtonVariant.outline) { scope.launch { load() } }
                }
            } else {
                TeamProgress(minHeight = 96.dp)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowLayout(spacing = 8.dp) {
                        CountPill(LF("%1\$d/%2\$d on in-app", draft.count { it.inApp }, draft.size), icon = "monitor-check", accent = Theme.lime)
                        CountPill(LF("%1\$d/%2\$d on email", draft.count { it.email }, draft.size), icon = "mail", accent = Theme.aqua)
                    }
                    Text(L("In the app they show on the bell; by email they go to the address of your account."), style = Theme.text(14), color = Theme.inkSoft)
                }
                TimezoneField(timezone)
                Column(Modifier.fillMaxWidth().bleed(16.dp)) {
                    draft.indices.forEach { index ->
                        androidx.compose.runtime.key(draft[index].category) {
                            RowDivider()
                            CategoryRow(
                                item = stateOf({ draft[index] }, { value -> draft = draft.toMutableList().also { if (index in it.indices) it[index] = value } }),
                                saving = saving,
                            )
                        }
                    }
                    RowDivider()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Glyph("smartphone", 16.dp, tint = Theme.blue)
                        Glyph("message-circle", 16.dp, tint = Theme.blue)
                    }
                    Text(LF("%@ remain unavailable until those delivery systems are enabled.", futureChannels), style = Theme.text(14, FontWeight.Medium), color = Theme.inkSoft)
                }
                // "Novedades y ofertas": saves the moment it is switched.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(L("News and offers").uppercase(appLocale), style = Theme.text(11, FontWeight.ExtraBold).tracking(0.8), color = Theme.inkSoft)
                        Text(L("Reminders, messages and security emails do not depend on this."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                    }
                    if (marketingFailed && marketing == null) {
                        NoticeCard(tone = NoticeCard.Tone.problem, title = L("Could not load your notification settings."), actionTitle = L("Try again")) {
                            scope.launch { loadMarketing() }
                        }
                    } else {
                        Disabled(marketing == null || savingMarketing) {
                            ToggleRow(
                                title = L("Email"),
                                subtitle = L("Now and then, about what Paw Miaw can do and its plans."),
                                isOn = stateOf({ marketing ?: false }, { changeMarketing(it) }),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "9/9 en la app": a count pill on the channel's colour. */
@Composable
private fun CountPill(text: String, icon: String, accent: Color) {
    Row(
        Modifier
            .height(32.dp)
            .background(accent, CapsuleShape)
            .border(1.dp, Theme.blue, CapsuleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 14.dp, tint = Theme.inkOnBright)
        Text(text, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkOnBright)
    }
}

/** The time zone reminders are scheduled in (read-only, as on the web). */
@Composable
private fun TimezoneField(timezone: String) {
    val shape = RoundedCornerShape(Theme.fieldRadius)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(L("Timezone").uppercase(appLocale), style = Theme.text(12, FontWeight.Bold).tracking(1.7), color = Theme.inkSoft)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Theme.surfaceMuted, shape)
                .border(1.dp, Theme.borderSoft, shape)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("globe", 16.dp, tint = Theme.blue)
            Text(TeamHours.zoneLabel(timezone), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(L("Used to schedule appointment, preventive care, and follow-up reminders."), style = Theme.text(11.2, FontWeight.Medium), color = Theme.inkSoft)
    }
}

@Composable
private fun CategoryRow(item: MutableState<TeamNotificationChoice>, saving: Boolean) {
    val category = item.value.category
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = TeamNotificationCopy.icon(category),
                    accent = TeamNotificationCopy.accent(category),
                    size = 40.dp,
                    iconSize = 16.dp,
                    ink = IconBadge.Ink.strong,
                    modifier = Modifier.border(1.dp, Theme.blue, CircleShape),
                )
                Text(TeamNotificationCopy.label(category), style = Theme.heading(20), color = Theme.inkStrong)
            }
            Text(TeamNotificationCopy.description(category), style = Theme.text(14), color = Theme.inkSoft)
        }
        val timing = TeamNotificationCopy.timing(category)
        if (timing != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, box)
                    .border(1.dp, Theme.borderSoft, box)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("clock-3", 16.dp, tint = Theme.blue)
                    Text(timing.title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(timing.description, style = Theme.text(12), color = Theme.inkSoft)
                FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 8.dp)) {
                    timing.options.forEach { (value, label) ->
                        val on = item.value.leadTimes.contains(value)
                        Box(
                            Modifier
                                .plainButton(enabled = !saving) {
                                    val next = item.value.leadTimes.toMutableSet()
                                    if (on) {
                                        if (next.size > 1) next.remove(value)
                                    } else {
                                        next.add(value)
                                    }
                                    item.value = item.value.copy(leadTimes = timing.options.map { it.first }.filter { next.contains(it) })
                                }
                                .semantics { selected = on }
                                .heightIn(min = 36.dp)
                                .background(if (on) Theme.wash(Theme.lime, 42.0) else Theme.surface, CapsuleShape)
                                .border(1.dp, if (on) Theme.blue else Theme.borderSoft, CapsuleShape)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = Theme.text(12, FontWeight.Bold), color = if (on) Theme.inkStrong else Theme.inkSoft)
                        }
                    }
                }
            }
        } else {
            val (title, description) = TeamNotificationCopy.notice(category)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, box)
                    .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("bell", 16.dp, tint = Theme.blue)
                    Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(description, style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        TeamAdaptiveGrid(items = listOf(0, 1), minimum = 150.dp, spacing = 8.dp) { index ->
            if (index == 0) {
                ChannelToggle(L("In-app"), icon = "monitor-check", accent = Theme.lime, saving = saving, isOn = stateOf({ item.value.inApp }, { item.value = item.value.copy(inApp = it) }))
            } else {
                ChannelToggle(L("Email"), icon = "mail", accent = Theme.aqua, saving = saving, isOn = stateOf({ item.value.email }, { item.value = item.value.copy(email = it) }))
            }
        }
    }
}

/** A channel's switch (web `ChannelToggle`): a 40 pill washed in its colour when on. */
@Composable
private fun ChannelToggle(title: String, icon: String, accent: Color, saving: Boolean, isOn: MutableState<Boolean>) {
    PMSwitchStyle(
        isOn = isOn,
        enabled = !saving,
        modifier = Modifier
            .alpha(if (saving) 0.6f else 1f)
            .heightIn(min = 40.dp)
            .background(if (isOn.value) Theme.wash(accent, 34.0) else Theme.surfaceMuted, CapsuleShape)
            .border(2.dp, if (isOn.value) Theme.blue else Theme.borderSoft, CapsuleShape)
            .padding(horizontal = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, 16.dp, tint = Theme.blue)
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
