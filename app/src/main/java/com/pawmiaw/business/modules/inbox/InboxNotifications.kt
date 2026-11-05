package com.pawmiaw.business.inbox

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

//
//  InboxNotifications.kt (iOS InboxNotifications.swift)
//
//  Avisos (web routes/provider.notifications.tsx,
//  components/app/notifications/, hooks/use-notifications.ts): everything
//  the workspace was told, newest first by day. The owner's rule: a row
//  opens by a tap (and is read then), leaves by a swipe to the LEFT, no
//  snooze. 20 at a time with infinite scroll; the counts are the server's.
//

// MARK: - Models

@Serializable
data class InboxNotification(
    val id: String,
    val contextType: String? = null,
    val recipientScope: String? = null,
    val category: String? = null,
    val title: String? = null,
    val body: String? = null,
    val petName: String? = null,
    val clientName: String? = null,
    val pet: InboxNotificationPet? = null,
    val severity: String? = null,
    val sourceType: String? = null,
    val sourceId: String? = null,
    val actionUrl: String? = null,
    val dueAt: String? = null,
    val readAt: String? = null,
    val resolvedAt: String? = null,
    val dismissedAt: String? = null,
    val createdAt: String? = null,
    val destinationState: String? = null,
) {
    val closed: Boolean get() = dismissedAt != null || resolvedAt != null
    val unread: Boolean get() = readAt == null
}

@Serializable
data class InboxNotificationPet(
    val clientId: String? = null,
    val providerClientPetId: String? = null,
)

@Serializable
data class InboxUnreadSummary(
    val unreadCount: Int? = null,
    val urgentUnreadCount: Int? = null,
    val activeCount: Int? = null,
    val categoryCounts: Map<String, InboxCategoryCount>? = null,
)

@Serializable
data class InboxCategoryCount(
    val active: Int? = null,
    val unread: Int? = null,
)

// MARK: - Words and rules (lib/notification-formatting.ts, lib/notification-routing.ts)

enum class InboxArea(val rawValue: String) {
    agenda("agenda"), billing("billing"), records("records"), clients("clients"), team("team");

    val id: String get() = rawValue

    val categories: List<String>
        get() = when (this) {
            agenda -> listOf("appointments", "client_facing_reminders")
            billing -> listOf("billing")
            records -> listOf("records_documentation", "provider_follow_ups", "medical_follow_ups", "routines", "routine_checkins", "preventive_care", "care_supplies")
            clients -> listOf("client_messages", "provider_invitations", "provider_updates")
            team -> listOf("security", "household_access", "owner_tasks")
        }

    val label: String
        get() = when (this) {
            agenda -> L("Schedule@area")
            billing -> L("Billing & register")
            records -> L("Records@area")
            clients -> L("Clients@area")
            team -> L("Team@area")
        }

    companion object {
        fun of(category: String?): InboxArea = entries.firstOrNull { (category ?: "") in it.categories } ?: team
    }
}

object InboxNotificationText {
    /** The device's clock (iOS `Calendar.current`): a notice says when on the reader's own day. */
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** "2:43 p.m.." → "2:43 p.m."; doubled spaces gone. */
    fun clean(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val single = Regex("(?<!\\.)\\.\\.(?!\\.)").replace(text, ".")
        return Regex("\\s{2,}").replace(single, " ").trim()
    }

    fun accessChanged(n: InboxNotification): Boolean =
        n.destinationState == "access_changed" || n.title == "Access changed"

    fun title(n: InboxNotification): String = if (accessChanged(n)) L("Access changed") else clean(n.title)

    fun body(n: InboxNotification): String =
        if (accessChanged(n)) L("This update is no longer available with your current permissions.") else clean(n.body)

    fun severity(raw: String?): String = when (raw) {
        "urgent", "critical" -> "urgent"
        "action", "warning" -> "action"
        else -> "info"
    }

    private val deadlineSources: Set<String> = setOf(
        "provider_follow_up", "provider_recall", "provider_appointment_documentation", "household_record_follow_up",
        "household_preventive_record", "missing_documentation", "pet_routine", "routine", "routine_checkin",
    )
    private val deadlineCategories: Set<String> = setOf("provider_follow_ups", "medical_follow_ups", "preventive_care", "owner_tasks")
    private val receipts: Set<String> = setOf(
        "auth_sign_in", "household_invite_accepted", "household_member_access_updated", "household_member_removed",
        "provider_client_invitation_expired", "routine_checkin",
    )
    private val actionable: Set<String> = setOf(
        "appointment", "appointment_confirmation", "appointment_reschedule", "billing", "cabinet", "cabinet_expired",
        "cabinet_expiring", "cabinet_expiring_soon", "cabinet_low_stock", "cabinet_refill_needed", "cabinet_runs_out",
        "client_message", "household_appointment", "household_invite", "household_preventive_record",
        "household_record_follow_up", "invoice", "missing_documentation", "pet_record", "pet_routine",
        "routine_course_ending", "routine_course_ended", "provider_appointment", "provider_appointment_confirmation",
        "provider_client_invitation", "provider_follow_up", "provider_invoice", "provider_link_request",
        "provider_paperwork_late", "provider_paperwork_today", "provider_record", "record", "records_documentation", "routine",
    )

    /** A notice whose date can be missed (a task, a dose); an event's date is when it happened. */
    fun hasRealDeadline(n: InboxNotification): Boolean {
        val source = n.sourceType ?: ""
        if (source.startsWith("cabinet") || source.startsWith("routine_course")) return false
        if (source in deadlineSources) return true
        if (source.startsWith("provider_register") || source.contains("invoice") || source.contains("invitation")) return false
        if (source.contains("confirmation") || source.contains("reschedule")) return false
        return (n.category ?: "") in deadlineCategories
    }

    fun isStartTime(source: String?): Boolean =
        (source ?: "") in listOf("provider_appointment", "household_appointment", "appointment", "stay")

    fun isReceipt(n: InboxNotification): Boolean = (n.sourceType ?: "") in receipts

    /** Informational notices leave the list once seen; the rest are only read. */
    fun autoDismissOnView(n: InboxNotification): Boolean {
        val source = n.sourceType ?: ""
        if (source in actionable) return false
        if (source in receipts) return true
        val severity = n.severity
        if (severity != null && severity != "info") return false
        return (n.category ?: "") in listOf("household_access", "provider_updates", "security")
    }

    /** Swift `Double.rounded()`: half away from zero. */
    private fun rounded(value: Double): Int = Math.round(value).toInt()

    /** "in 3 h", "3 h ago", "tomorrow", "in 4 days". */
    fun distance(target: Instant, now: Instant = Instant.now()): String {
        val diff = (target.toEpochMilli() - now.toEpochMilli()) / 1000.0
        val minutes = rounded(abs(diff) / 60)
        if (minutes < 1) return L("Just now").lowercase()
        if (minutes < 60) return if (diff < 0) LF("%d min ago", minutes) else LF("in %d min@notification", minutes)
        val hours = rounded(minutes.toDouble() / 60)
        if (hours < 24) return if (diff < 0) LF("%d h ago", hours) else LF("in %d h", hours)
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), target.atZone(zone).toLocalDate()).toInt()
        if (days == 1) return L("tomorrow")
        if (days == -1) return L("yesterday")
        return if (days < 0) LF("%d days ago", -days) else LF("in %d days", days)
    }

    /** The one tag beside the title, if any: overdue, due or starting soon, urgent, or needs you. */
    fun status(n: InboxNotification, now: Instant = Instant.now()): Pair<String, ProviderState>? {
        if (n.closed) return null
        val due = DateText.parse(n.dueAt)
        if (due != null && hasRealDeadline(n)) {
            if (due < now.minusSeconds(60)) return LF("Overdue · %@", distance(due, now)) to ProviderState.problem
            if (seconds(now, due) <= 2 * 86_400) return LF("Due %@", distance(due, now)) to ProviderState.waiting
        }
        if (due != null && isStartTime(n.sourceType) && due > now && seconds(now, due) <= 2 * 86_400) {
            return LF("Starts %@", distance(due, now)) to ProviderState.upcoming
        }
        if (isReceipt(n)) return null
        return when (severity(n.severity)) {
            "urgent" -> L("Urgent@notification") to ProviderState.problem
            "action" -> L("Needs you@notification") to ProviderState.waiting
            else -> null
        }
    }

    /** `to.timeIntervalSince(from)` in seconds. */
    private fun seconds(from: Instant, to: Instant): Double = (to.toEpochMilli() - from.toEpochMilli()) / 1000.0

    /** When it arrived: "Just now", "20 min ago", "3 h ago" today; "Yesterday", the weekday this week, then the date. */
    fun time(iso: String?, now: Instant = Instant.now()): String {
        val date = DateText.parse(iso) ?: return ""
        val diff = seconds(date, now)
        val group = timeGroup(date, now)
        if (diff < 60) return L("Just now")
        if (group == TimeGroup.today || diff < 3600) {
            val minutes = rounded(diff / 60)
            return if (minutes < 60) LF("%d min ago", minutes) else LF("%d h ago", rounded(minutes.toDouble() / 60))
        }
        if (group == TimeGroup.yesterday) return L("Yesterday")
        val sameYear = date.atZone(zone).year == now.atZone(zone).year
        return DateText.format(date, if (group == TimeGroup.thisWeek) "EEEE" else (if (sameYear) "dMMM" else "dMMMyyyy"), zone)
    }

    enum class TimeGroup {
        today, yesterday, thisWeek, earlier;

        val label: String
            get() = when (this) {
                today -> L("Today")
                yesterday -> L("Yesterday")
                thisWeek -> L("This week")
                earlier -> L("Earlier")
            }
    }

    fun timeGroup(date: Instant, now: Instant = Instant.now()): TimeGroup {
        val days = ChronoUnit.DAYS.between(date.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
        if (days <= 0) return TimeGroup.today
        if (days == 1L) return TimeGroup.yesterday
        return if (days < 7) TimeGroup.thisWeek else TimeGroup.earlier
    }

    /** The row's one step, named for what it does. */
    fun actionLabel(n: InboxNotification): String = when (n.sourceType) {
        "provider_register_open" -> L("Go to the register")
        "provider_register_difference" -> L("See the cash count")
        "provider_balance" -> L("See balance")
        "provider_record_signature", "provider_record_unsigned" -> L("Sign@notification")
        "provider_follow_up" -> if (n.category == "records_documentation") L("Complete the record") else L("Schedule@action")
        "provider_recall" -> L("Schedule@action")
        "provider_appointment_documentation" -> L("Complete the record")
        "provider_paperwork_today", "provider_paperwork_late" -> L("See what is missing")
        "provider_client_invitation", "provider_link_request" -> L("Review")
        "provider_appointment_confirmation" -> L("Review request")
        "appointment_confirmation" -> L("Confirm appointment")
        "appointment_reschedule" -> L("Review change")
        "provider_appointment" -> L("Open visit@notification")
        "household_appointment", "appointment" -> L("View appointment")
        "stay" -> L("Open stay")
        "provider_record" -> L("Open record")
        "pet_record", "household_preventive_record", "household_record_follow_up", "record" -> L("View record")
        "missing_documentation" -> L("Upload document")
        "provider_invoice", "invoice" -> L("Open invoice")
        "client_message" -> L("Open chat")
        "auth_sign_in" -> L("Check security")
        else -> when (n.category) {
            "provider_invitations" -> L("Review")
            "billing" -> L("Open invoice")
            "records_documentation" -> L("Review records")
            "preventive_care" -> L("View record")
            "provider_follow_ups" -> L("Open follow-up")
            "appointments" -> L("View appointment")
            "client_messages" -> L("Open chat")
            else -> L("Open@notification")
        }
    }

    /** Where a tap goes (lib/notification-routing.ts `getNotificationActionUrl`), as a screen of the app. */
    fun route(n: InboxNotification): ProviderRoute? {
        val generic = setOf("/app/notifications", "/provider/notifications")
        val knownFallback = setOf("provider_appointment", "provider_follow_up", "provider_record", "records_documentation", "provider_invoice", "invoice", "billing", "client_message")
        val source = n.sourceType ?: ""
        val url = n.actionUrl
        if (source.startsWith("hospital") && url != null && !url.startsWith("/provider")) {
            InboxRoutes.route(url)?.let { return it }
            if (source == "hospital_missed_treatment") return ProviderRoute.section(ProviderSection.hospital)
        }
        if (url != null && url.startsWith("/") && !(url in generic && source in knownFallback)) {
            return InboxRoutes.route(url)
        }
        val id = n.sourceId.nonEmpty
        return when (source) {
            "provider_appointment" -> id?.let { ProviderRoute.appointment(it) }
            "provider_follow_up" -> ProviderRoute.section(ProviderSection.records)
            "provider_record", "records_documentation" -> id?.let { ProviderRoute.record(it) } ?: ProviderRoute.section(ProviderSection.records)
            "provider_invoice", "invoice", "billing" -> ProviderRoute.section(ProviderSection.invoices)
            "client_message" -> ProviderRoute.section(ProviderSection.clients)
            else -> null
        }
    }

    /** Gente's navy, which thins to grey as a wash (web `--kind-people` / its ink); no Theme token holds it. */
    private val peopleColor = scheme(Triple(34, 58, 102), Triple(142, 167, 214))
    private val peopleInk = scheme(Triple(34, 58, 102), Triple(195, 210, 238))

    /**
     * What a notification is about (web `getNotificationKind` + `KIND_LOOK`): a Lucide icon, the colour
     * of the kind of thing it is about (the badge is a wash of it) and the glyph's ink.
     */
    @Composable
    fun look(n: InboxNotification): Triple<String, Color, Color> {
        val agenda = Hue.peach.color to Hue.peach.ink
        val record = Hue.teal.color to Hue.teal.ink
        val people = peopleColor.resolve() to peopleInk.resolve()
        val plain = Hue.grey.color to Hue.grey.ink
        val sky = Hue.sky.color to Hue.sky.ink
        val butter = Hue.butter.color to Hue.butter.ink
        fun of(icon: String, kind: Pair<Color, Color>): Triple<String, Color, Color> = Triple(icon, kind.first, kind.second)
        val source = n.sourceType ?: ""
        if (source == "provider_register_open") return Triple("wallet", Theme.mango, Theme.inkStrong)
        if (source == "provider_register_difference") return Triple("scale", Theme.coral, Theme.inkStrong)
        if (source in listOf("provider_invoice", "invoice", "provider_balance")) return Triple("receipt", Theme.mango, Theme.inkStrong)
        if (source == "provider_record_signature" || source == "provider_record_unsigned") return of("file-pen", record)
        if (source == "stay" || source.startsWith("provider_stay")) return of("bed-double", agenda)
        if (source.startsWith("cabinet")) return of("pill", sky)
        if (source == "household_preventive_record") return of("syringe", record)
        if (source.contains("invitation") || source == "provider_link_request" || source == "household_invite") return of("mail-check", people)
        if (source == "client_message") return of("message-circle", people)
        if (source == "provider_follow_up" && n.category == "records_documentation") return of("file-text", record)
        if (source == "provider_follow_up" || source == "provider_recall" || source == "household_record_follow_up") return of("clipboard-check", record)
        if (source.contains("appointment")) return of("calendar-days", agenda)
        if (source.contains("record") || source == "missing_documentation") return of("file-text", record)
        if (source.contains("routine")) return of("repeat", butter)
        if (source == "auth_sign_in") return of("shield-check", plain)
        if (source.startsWith("household_member") || source == "household_invite_accepted") return of("house", people)
        return when (n.category) {
            "appointments", "client_facing_reminders" -> of("calendar-days", agenda)
            "billing" -> Triple("receipt", Theme.mango, Theme.inkStrong)
            "records_documentation" -> of("file-text", record)
            "provider_follow_ups", "medical_follow_ups" -> of("clipboard-check", record)
            "client_messages", "provider_updates" -> of("message-circle", people)
            "provider_invitations" -> of("mail-check", people)
            "routines", "routine_checkins", "owner_tasks" -> of("repeat", butter)
            "care_supplies" -> of("pill", sky)
            "preventive_care" -> of("syringe", record)
            "security" -> of("shield-check", plain)
            "household_access" -> if (n.contextType == "provider_workspace") of("users-round", people) else of("house", people)
            else -> of("bell", plain)
        }
    }

    /** A status tag's colour (web `STATUS_ACCENT`): overdue and urgent coral, due and needs-you mango, starts aqua. */
    fun statusAccent(state: ProviderState): Color = when (state) {
        ProviderState.problem -> Theme.coral
        ProviderState.waiting -> Theme.mango
        else -> Theme.aqua
    }
}

// MARK: - The screen

private val categoryPermissions: List<Pair<String, Permission>> = listOf(
    "appointments" to Permission.notificationsAppointments,
    "provider_follow_ups" to Permission.notificationsProviderFollowups,
    "records_documentation" to Permission.notificationsRecordsDocumentation,
    "billing" to Permission.notificationsBilling,
    "client_messages" to Permission.notificationsClientMessages,
    "client_facing_reminders" to Permission.notificationsClientFacingReminders,
)

/** Notificaciones: the business's notifications, filtered by area, unread, what to show and for whom. */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val canView = categoryPermissions.any { workspace.can(it.second) }
    providerPage(L("Notifications"), root = true, modifier = modifier) {
        if (canView) {
            InboxNotificationsList()
        } else {
            PageScroll {
                EmptyState(
                    systemImage = "bell-off",
                    title = L("No notification access"),
                    message = L("Your role does not include notifications for this workspace. Ask the owner to change it."),
                    panel = true,
                )
            }
        }
    }
}

private enum class InboxShow(val rawValue: String) { active("active"), recent("recent"), all("all") }
private enum class InboxFor(val rawValue: String) { all("all"), mine("mine"), workspaceQueue("workspace_queue") }

/** One optional read (Swift `try?`): null when it fails; a cancel still cancels. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    null
}

@Composable
private fun InboxNotificationsList() {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var area by remember { mutableStateOf<InboxArea?>(null) }
    var unreadOnly by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(InboxShow.active) }
    var forWhom by remember { mutableStateOf(InboxFor.all) }
    val query = remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<InboxUnreadSummary?>(null) }
    var removing by remember { mutableStateOf<InboxNotification?>(null) }
    var markingAll by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val pager = remember { Pager<InboxNotification> { _, _ -> Page(emptyList(), 0) } }

    val filtered = area != null || unreadOnly || show != InboxShow.active || forWhom != InboxFor.all || query.value.nonEmpty != null

    // MARK: Loading

    suspend fun loadSummary() {
        attempt { API.get<InboxUnreadSummary>("/provider-notifications/unread-summary") }?.let { summary = it }
    }

    suspend fun reload() {
        val categories = area?.categories?.joinToString(",")
        val status = show.rawValue
        val assigned = forWhom.rawValue
        val unread = if (unreadOnly) "true" else null
        val search = query.value.nonEmpty
        val wanted = area?.categories?.toSet()
        val onlyUnread = unreadOnly
        pager.fetch = { offset, limit ->
            val page = API.get<Page<InboxNotification>>(
                "/provider-notifications",
                mapOf(
                    "status" to status, "limit" to "$limit", "offset" to "$offset",
                    "categories" to categories, "unread" to unread, "assigned" to assigned, "search" to search,
                ),
            )
            // An older server ignores the area and unread filters: the list keeps true to the chip either way.
            val items = page.items.filter { (wanted == null || (it.category ?: "") in wanted) && (!onlyUnread || it.readAt == null) }
            Page(items, page.total, page.hasMore)
        }
        coroutineScope {
            val summaryLoad = async { loadSummary() }
            pager.reload()
            summaryLoad.await()
        }
    }

    fun clearFilters() {
        area = null
        unreadOnly = false
        show = InboxShow.active
        forWhom = InboxFor.all
        query.value = ""
    }

    // MARK: Actions

    suspend fun quietly(path: String) {
        try {
            val updated = API.post<InboxNotification>(path)
            if (updated.closed && show == InboxShow.active) pager.remove(updated.id) else pager.replace(updated)
            loadSummary()
            announceDataChanged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Opening never waits on this; the next read of the list says how it stands.
        }
    }

    /** Opens it at once and marks it read on the way (or, for a receipt that only informs, removes it). */
    fun open(notification: InboxNotification) {
        if (!notification.closed && InboxNotificationText.autoDismissOnView(notification)) {
            scope.launch { quietly("/notifications/${notification.id}/dismiss") }
        } else if (notification.unread && notification.sourceType != "system") {
            scope.launch { quietly("/notifications/${notification.id}/read") }
        }
        // Some open the work itself (the web's "open in place"): it may need the follow-up read first.
        if (InboxNotificationWork.handles(notification)) {
            if (busyId != null) return
            busyId = notification.id
            scope.launch {
                try {
                    when (val outcome = InboxNotificationWork.open(notification, workspace)) {
                        is InboxNotificationWork.Outcome.push -> nav.push(outcome.route)
                        InboxNotificationWork.Outcome.stay -> {}
                        InboxNotificationWork.Outcome.page -> InboxNotificationText.route(notification)?.let { nav.push(it) }
                    }
                } finally {
                    busyId = null
                }
            }
            return
        }
        InboxNotificationText.route(notification)?.let { nav.push(it) }
    }

    /** The pet's name opens its chart (web notification-feed.tsx), for someone who sees clients. */
    fun chartAction(notification: InboxNotification): (() -> Unit)? {
        if (!workspace.can(Permission.clientsView) || notification.petName.nonEmpty == null) return null
        val petId = notification.pet?.providerClientPetId.nonEmpty ?: return null
        val clientId = notification.pet?.clientId.nonEmpty
        return { nav.push(clientId?.let { ProviderRoute.clientPet(clientId = it, petId = petId) } ?: ProviderRoute.patientChart(petId = petId)) }
    }

    suspend fun dismissNow(notification: InboxNotification) {
        busyId = notification.id
        try {
            val updated = API.post<InboxNotification>("/notifications/${notification.id}/dismiss")
            if (show == InboxShow.all) pager.replace(updated) else pager.remove(updated.id)
            Toast.show(L("Notification removed."))
            loadSummary()
            announceDataChanged()
        } finally {
            busyId = null
        }
    }

    /** A team's shared notice is asked about first; one's own leaves at once. */
    fun remove(notification: InboxNotification) {
        if (notification.recipientScope == "workspace_queue") {
            removing = notification
        } else {
            scope.launch {
                try {
                    dismissNow(notification)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Toast.error(failure, L("Could not remove it."))
                }
            }
        }
    }

    suspend fun markAllRead() {
        markingAll = true
        try {
            API.send("POST", "/provider-notifications/read-all")
            Toast.show(L("All marked as read."))
            reload()
            announceDataChanged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Toast.error(failure, L("Could not mark them as read. Try again."))
        } finally {
            markingAll = false
        }
    }

    // MARK: When to read

    Debounced(query.value) { reload() }
    LaunchedEffect(workspace.scopeVersion) { reload() }
    LaunchedEffect(Unit) {
        AppEvents.dataChanged.collect { launch { loadSummary() } }
    }
    LaunchedEffect(Unit) {
        // iOS `.onChange(of:)` on each filter: a read each time one changes (not on the first appear).
        snapshotFlow { listOf(area, unreadOnly, show, forWhom) }.drop(1).collect { scope.launch { reload() } }
    }

    PageScroll(refresh = { reload() }) {
        InboxNotificationsHeader(
            summary = summary,
            unreadOnly = unreadOnly,
            filtered = filtered,
            markingAll = markingAll,
            onToggleUnread = { unreadOnly = !unreadOnly },
            onClear = { clearFilters() },
            onMarkAll = { scope.launch { markAllRead() } },
            onPreferences = { nav.push(ProviderRoute.section(ProviderSection.notificationSettings)) },
        )
        Card(padding = 0.dp, spacing = 0.dp) {
            Column(Modifier.clip(RoundedCornerShape(Theme.cardRadius))) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SearchField(
                        prompt = L("Client, pet or text"),
                        text = query,
                        modifier = Modifier.semantics { contentDescription = L("Search notifications") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val showTitle = when (show) {
                            InboxShow.active -> L("Active@show")
                            InboxShow.recent -> L("Last 30 days")
                            InboxShow.all -> L("All, including dismissed")
                        }
                        AppMenu(label = { PillField(text = showTitle, height = 48.dp) }, accessibilityLabel = L("Show")) {
                            picker(
                                L("Show"),
                                options = listOf(
                                    InboxShow.active to L("Active@show"),
                                    InboxShow.recent to L("Last 30 days"),
                                    InboxShow.all to L("All, including dismissed"),
                                ),
                                selection = stateOf({ show }, { show = it }),
                            )
                        }
                        val forTitle = when (forWhom) {
                            InboxFor.all -> L("Everyone@for")
                            InboxFor.mine -> L("Only me")
                            InboxFor.workspaceQueue -> L("Team queue")
                        }
                        AppMenu(label = { PillField(text = forTitle, height = 48.dp) }, accessibilityLabel = L("For")) {
                            picker(
                                L("For"),
                                options = listOf(
                                    InboxFor.all to L("Everyone@for"),
                                    InboxFor.mine to L("Only me"),
                                    InboxFor.workspaceQueue to L("Team queue"),
                                ),
                                selection = stateOf({ forWhom }, { forWhom = it }),
                            )
                        }
                    }
                    InboxNotificationChips(
                        summary = summary,
                        area = area,
                        unreadOnly = unreadOnly,
                        onAll = {
                            area = null
                            unreadOnly = false
                        },
                        onUnread = { unreadOnly = !unreadOnly },
                        onArea = { option -> area = if (area == option) null else option },
                    )
                }
                RowDivider()
                // MARK: The list, by day
                val error = pager.error
                when {
                    !pager.loadedOnce && pager.isLoading -> LoadingState()
                    error != null && pager.items.isEmpty() -> ErrorState(message = L("Could not load notifications.") + " " + error, retry = { reload() })
                    pager.items.isEmpty() -> InboxNotificationsEmpty(
                        query = query.value,
                        area = area,
                        unreadOnly = unreadOnly,
                        filtered = filtered,
                        onClear = { clearFilters() },
                    )
                    else -> {
                        val now = Instant.now()
                        val groups = InboxNotificationText.TimeGroup.entries.mapNotNull { group ->
                            val items = pager.items.filter { InboxNotificationText.timeGroup(DateText.parse(it.createdAt) ?: now, now) == group }
                            if (items.isEmpty()) null else group to items
                        }
                        Column {
                            groups.forEachIndexed { index, (group, items) ->
                                if (index > 0) RowDivider()
                                Box(Modifier.fillMaxWidth().background(Theme.surface)) {
                                    Text(
                                        group.label.uppercase(),
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        style = brandText(12, FontWeight.ExtraBold).tracking(1.2),
                                        color = Theme.inkSoft,
                                    )
                                    RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
                                }
                                items.forEachIndexed { position, notification ->
                                    key(notification.id) {
                                        if (position > 0) RowDivider()
                                        InboxSwipeAway(
                                            enabled = !notification.closed && busyId != notification.id,
                                            onRemove = { remove(notification) },
                                            modifier = Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(notification) },
                                        ) {
                                            InboxNotificationRow(
                                                notification = notification,
                                                busy = busyId == notification.id,
                                                now = now,
                                                openChart = chartAction(notification),
                                                onRemove = if (notification.closed) null else ({ remove(notification) }),
                                            ) {
                                                open(notification)
                                            }
                                        }
                                    }
                                }
                            }
                            PagerFooter(pager)
                        }
                        val total = pager.total
                        if (total != null && total > 0) {
                            RowDivider()
                            Text(
                                LF("%d–%d of %d", 1, pager.items.size, total),
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                style = Theme.text(14).monospacedDigit(),
                                color = Theme.inkSoft,
                            )
                        }
                    }
                }
            }
        }
    }

    removing?.let { notification ->
        AppSheet(onDismissRequest = { removing = null }) {
            ConfirmSheet(
                title = L("Remove this notification for the whole team?"),
                message = L("It leaves everyone’s list; the work behind it stays where it is."),
                confirmTitle = L("Remove"),
                destructive = true,
            ) { _ ->
                dismissNow(notification)
            }
        }
    }
}

// MARK: Header: the counts, mark all, preferences

@Composable
private fun InboxNotificationsHeader(
    summary: InboxUnreadSummary?,
    unreadOnly: Boolean,
    filtered: Boolean,
    markingAll: Boolean,
    onToggleUnread: () -> Unit,
    onClear: () -> Unit,
    onMarkAll: () -> Unit,
    onPreferences: () -> Unit,
) {
    val unread = summary?.unreadCount ?: 0
    val urgent = summary?.urgentUnreadCount ?: 0
    ModuleHeader(
        icon = "bell",
        title = L("Notifications"),
        subtitle = L("Everything your workspace needs to know, newest first: what needs you, what changed, and what is coming up."),
        accent = Theme.aqua,
        stats = listOf(
            ModuleStat(
                "unread", label = L("Unread@stat"), value = summary?.unreadCount?.toString() ?: "—",
                hint = if (urgent > 0) (if (urgent == 1) L("1 urgent") else LF("%d urgent", urgent)) else L("Not opened yet"),
                icon = "bell", accent = Theme.coral, attention = unread > 0, active = unreadOnly,
                action = onToggleUnread,
            ),
            ModuleStat(
                "active", label = L("Active@stat"), value = summary?.activeCount?.toString() ?: "—",
                hint = L("In the list now"), icon = "inbox", accent = Theme.aqua, active = !filtered,
                action = onClear,
            ),
        ),
        storageKey = "notifications",
    ) {
        if (unread > 0) {
            PMButton(if (markingAll) L("Marking…") else L("Mark all as read"), systemImage = "check-check", isWorking = markingAll, action = onMarkAll)
        }
        val preferences = L("Notification preferences")
        PMButtonStyle(
            ButtonVariant.outline,
            ButtonSize.header,
            modifier = Modifier.semantics { contentDescription = preferences },
            onClick = onPreferences,
        ) {
            Glyph("settings", 16.dp)
        }
    }
}

/** All · Unread · one chip per area that has something (web `NotificationFilterChips`). */
@Composable
private fun InboxNotificationChips(
    summary: InboxUnreadSummary?,
    area: InboxArea?,
    unreadOnly: Boolean,
    onAll: () -> Unit,
    onUnread: () -> Unit,
    onArea: (InboxArea) -> Unit,
) {
    val counts: Map<InboxArea, Int>? = summary?.categoryCounts?.let { categoryCounts ->
        val totals = mutableMapOf<InboxArea, Int>()
        for ((category, count) in categoryCounts) {
            val key = InboxArea.of(category)
            totals[key] = (totals[key] ?: 0) + (if (unreadOnly) (count.unread ?: 0) else (count.active ?: 0))
        }
        totals
    }
    val areas = InboxArea.entries.filter { counts == null || (counts[it] ?: 0) > 0 || area == it }
    val label = L("Filter notifications")
    FlowLayout(spacing = 6.dp, modifier = Modifier.semantics { contentDescription = label }) {
        FilterChip(title = L("All@notification"), count = summary?.activeCount, isSelected = area == null && !unreadOnly, solid = true, action = onAll)
        FilterChip(title = L("Unread"), count = summary?.unreadCount, isSelected = unreadOnly, solid = true, action = onUnread)
        areas.forEach { option ->
            FilterChip(title = option.label, count = counts?.get(option), isSelected = area == option, solid = true) { onArea(option) }
        }
    }
}

@Composable
private fun InboxNotificationsEmpty(
    query: String,
    area: InboxArea?,
    unreadOnly: Boolean,
    filtered: Boolean,
    onClear: () -> Unit,
) {
    val typed = query.nonEmpty
    val title = when {
        typed != null -> LF("Nothing matches “%@”", typed)
        area != null -> LF("Nothing in %@", area.label)
        unreadOnly -> L("Nothing unread")
        else -> L("You’re all caught up")
    }
    val body = if (typed != null) L("Try another word, or clear the filters.")
    else if (area != null) L("Nothing here needs you right now.")
    else if (unreadOnly) L("Everything here has been read.")
    else L("New reminders and updates will show up here.")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(systemImage = "check-check", title = title, message = body, accent = Theme.aqua)
        if (filtered) {
            // iOS `.padding(.top, -12).padding(.bottom, 28)`: drawn 12 higher, 28 under it.
            PMButton(
                L("Show everything"),
                variant = ButtonVariant.outline,
                size = ButtonSize.compact,
                modifier = Modifier.offset(y = (-12).dp).padding(bottom = 16.dp),
                action = onClear,
            )
        }
    }
}

// MARK: - Pieces

/**
 * A row that leaves by a swipe to the LEFT (web `useSwipeAway`): it follows the finger over a coral
 * "Quitar" band, and goes once past a third of its width (or on a quick flick); short of that it
 * springs back. A vertical drag is left to the list.
 */
@Composable
private fun InboxSwipeAway(
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val remove by rememberUpdatedState(onRemove)
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    val armedAt = max(with(density) { 80.dp.toPx() }, width * 0.35f)
    val armed = -offset > armedAt
    val textAlpha by animateFloatAsState(if (armed) 1f else 0.7f, tween(150, easing = EaseOut), label = "swipe words")
    val iconScale by animateFloatAsState(if (armed) 1.25f else 1f, tween(150, easing = EaseOut), label = "swipe icon")

    suspend fun settle(to: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>) {
        animate(offset, to, animationSpec = spec) { value, _ -> offset = value }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat() }
            .clipToBounds()
            .draggable(
                state = rememberDraggableState { delta -> offset = min(0f, offset + delta) },
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { velocity ->
                    // SwiftUI's predicted end: where the finger's speed would carry it (about half a second on).
                    val flick = offset + velocity * 0.5f < -max(with(density) { 160.dp.toPx() }, width * 0.6f)
                    if (-offset > max(with(density) { 80.dp.toPx() }, width * 0.35f) || flick) {
                        scope.launch {
                            settle(-width, tween(220, easing = EaseOut))
                            remove()
                            // Kept after all (a team's notice asked about, a refusal): it comes back.
                            delay(700)
                            settle(0f, spring(dampingRatio = 1f, stiffness = 440f))
                        }
                    } else {
                        scope.launch { settle(0f, spring(dampingRatio = 1f, stiffness = 440f)) }
                    }
                },
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (offset < 0f) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(Theme.coral)
                    .padding(horizontal = 20.dp)
                    .clearAndSetSemantics {},
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Remove"), Modifier.graphicsLayer { alpha = textAlpha }, style = brandText(14, FontWeight.ExtraBold), color = Color.White)
                Glyph("trash-2", 20.dp, tint = Color.White, modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale })
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}

/**
 * One notification (web `NotificationRow`): what it is about (icon), what happened (title and a line),
 * whether it needs anything (a tag only when it does), when, and the one thing to do next. The whole row
 * is that next step; it leaves by a swipe to the left (and the ✕ on a tablet).
 */
@Composable
private fun InboxNotificationRow(
    notification: InboxNotification,
    busy: Boolean,
    now: Instant,
    /** The pet's name as a link to its chart, above the row's own tap. */
    openChart: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    open: () -> Unit,
) {
    val regular = LocalSizeClass.current == SizeClass.regular
    val (icon, accent, ink) = InboxNotificationText.look(notification)
    val status = InboxNotificationText.status(notification, now)
    val subject = listOfNotNull(notification.clientName.nonEmpty, notification.petName.nonEmpty).joinToString(" · ")
    val unread = notification.unread && !notification.closed
    val body = InboxNotificationText.body(notification)
    val title = InboxNotificationText.title(notification)
    val petName = notification.petName.nonEmpty
    val chipShape = RoundedCornerShape(Theme.chipRadius)
    val chartLabel = petName?.let { LF("Open %@’s chart", it) }
    val removeWord = L("Remove")
    val hint = if (notification.closed) null else L("Swipe left to remove it.")

    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (busy) 0.5f else 1f }
            // Opaque either way: it covers the band behind it until it is swiped.
            .background(if (unread) Theme.wash(Theme.aqua, 13.0) else Theme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !busy,
                role = Role.Button,
            ) { if (!busy) open() }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { if (!busy) open(); true }
                val actions = mutableListOf<CustomAccessibilityAction>()
                if (openChart != null && chartLabel != null) actions += CustomAccessibilityAction(chartLabel) { openChart(); true }
                if (onRemove != null) actions += CustomAccessibilityAction(removeWord) { onRemove(); true }
                customActions = actions
                if (hint != null) stateDescription = hint
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(40.dp)
                    .background(Theme.wash(accent, if (notification.closed) 10.0 else 24.0), chipShape)
                    .border(1.dp, Theme.mix(accent, 50.0, Theme.borderSoft), chipShape),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(icon, 17.6.dp, tint = ink)
            }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.padding(end = if (regular && onRemove != null) 32.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (unread) {
                        val unreadWord = L("Unread")
                        Box(
                            Modifier
                                .alignBy { it.measuredHeight }
                                .size(8.dp)
                                .background(Theme.coral, CircleShape)
                                .semantics { contentDescription = unreadWord }
                        )
                    }
                    Text(title, Modifier.alignBy(FirstBaseline), style = Theme.heading(16), color = Theme.inkStrong)
                }
                if (body.isNotEmpty()) {
                    Text(
                        body,
                        Modifier.padding(top = 2.dp),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status != null || subject.isNotEmpty() || notification.closed) {
                    FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 8.dp)) {
                        if (status != null) {
                            val tone = InboxNotificationText.statusAccent(status.second)
                            Chip(text = status.first, tone = Chip.Tone.custom(tone), dot = tone, percent = 16.0, border = 55.0, size = Chip.Size.medium)
                        }
                        if (notification.closed) {
                            Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
                                Text(
                                    if (notification.resolvedAt != null) L("Done@notification") else L("Dismissed"),
                                    style = Theme.text(12, FontWeight.Bold),
                                    color = Theme.inkSoft,
                                )
                            }
                        }
                        if (openChart != null && petName != null) {
                            Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                notification.clientName.nonEmpty?.let { client ->
                                    Text("$client · ", style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1)
                                }
                                Text(
                                    petName,
                                    Modifier.plainButton(enabled = !busy, label = chartLabel, onClick = openChart),
                                    style = Theme.text(12, FontWeight.Bold),
                                    color = Theme.blue,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else if (subject.isNotEmpty()) {
                            Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
                                Text(subject, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalContentColor provides Theme.blue, LocalTextStyle provides brandText(13.1, FontWeight.ExtraBold)) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(InboxNotificationText.actionLabel(notification), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Glyph("arrow-right", 14.dp)
                        }
                    }
                    Text(InboxNotificationText.time(notification.createdAt, now), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
        }
        if (regular && onRemove != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    // iOS `.allowsHitTesting(!busy)` on the whole row.
                    .plainButton(enabled = !busy, label = LF("Remove “%@”", title), onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Glyph("x", 16.dp, tint = Theme.inkSoft)
            }
        }
    }
}
