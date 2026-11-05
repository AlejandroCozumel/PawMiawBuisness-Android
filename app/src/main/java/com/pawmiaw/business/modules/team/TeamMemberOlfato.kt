package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.inbox.InboxOlfatoPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.ZoneOffset

//
//  TeamMemberOlfato.kt (iOS TeamMemberOlfato.swift)
//
//  The "who asked" log of Olfato AI (web: components/app/olfato/
//  olfato-access.tsx, olfato-member-card.tsx; hooks/use-olfato-business.ts;
//  backend docs/olfato-business-chat-plan.md §9): one line per question —
//  who, when, which way in, the pets read, the kinds of information, what was
//  refused — never its text. A staff member's card shows their latest lines
//  and this week's count, for the person themself and for whoever reads the
//  team's use (owners, admins, whoever changes roles or manages money).
//  The log's shapes and its line are shared (design/AuditHistoryCard.kt).
//

// MARK: - Shapes

/** Questions per person per week, Monday-start weeks in the workspace's calendar. */
@Serializable
data class TeamOlfatoWeekly(
    val weeks: List<String>? = null,
    val members: List<Member>? = null,
) {
    @Serializable
    data class Member(
        val memberId: String? = null,
        val name: String? = null,
        val counts: List<Int>? = null,
        val total: Int? = null,
        val distinctPets: List<Int>? = null,
        val distinctPetsTotal: Int? = null,
        val kinds: List<Map<String, Int>>? = null,
    ) {
        val key: String get() = memberId ?: "left:${name ?: ""}"
    }
}

@Serializable
data class TeamOlfatoTeamPage(
    val items: List<OlfatoAccessEntry>? = null,
    val total: Int? = null,
    val hasMore: Boolean? = null,
    val weekly: TeamOlfatoWeekly? = null,
)

// MARK: - Rules

object TeamOlfatoAccess {
    /**
     * The admin audit rule: the owner, an admin, or whoever changes roles and access or manages money.
     * The server answers 403 to anyone else.
     */
    fun seesTeam(workspace: Workspace): Boolean =
        workspace.isOwnerOrAdmin || workspace.can(Permission.staffEditRoles) || workspace.can(Permission.billingManage)

    /** The Monday (YYYY-MM-DD) of the week a calendar day falls in. */
    fun monday(of: String): String {
        val date = TeamDays.date(of) ?: return of
        val weekday = date.atZone(ZoneOffset.UTC).dayOfWeek.value % 7 + 1 // 1 = Sunday
        val back = (weekday + 5) % 7
        return TeamDays.add(TeamDays.key(utc = date), -back)
    }

    /** How many lines (newest first) fall in the week from `monday`, and whether that is all of them: (count, exact). */
    fun weekCount(items: List<OlfatoAccessEntry>, monday: String, hasMore: Boolean): Pair<Int, Boolean> {
        val count = items.count { (it.day ?: "") >= monday }
        val exact = !hasMore || ((items.lastOrNull()?.day ?: "") < monday && items.isNotEmpty())
        return count to exact
    }
}

// MARK: - A staff member's card

/**
 * "Questions to Olfato AI" on a staff member's page: what they asked about,
 * and when (never the text), this week's count and their latest lines, a few
 * at a time. The whole team's list is in Settings › Olfato AI.
 */
@Composable
fun TeamMemberOlfatoCard(
    memberId: String,
    isSelf: Boolean,
    name: String,
    /** This reader sees the team's use. */
    seesTeam: Boolean,
    modifier: Modifier = Modifier,
) {
    val pageSize = 5
    var items by remember { mutableStateOf<List<OlfatoAccessEntry>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreFailed by remember { mutableStateOf(false) }
    var thisWeek by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var seeingTeam by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val path = "/provider-olfato/access/members/${if (isSelf) "me" else memberId}"

    /** Exact weekly counts come with the team's list; the person themself counts from their own lines. */
    suspend fun countThisWeek() {
        if (seesTeam) {
            val page = runCatching {
                API.get<TeamOlfatoTeamPage>("/provider-olfato/access/team", query = mapOf("memberId" to memberId, "limit" to "1", "offset" to "0"))
            }.getOrNull() ?: return
            val weekly = page.weekly ?: return
            val row = weekly.members?.firstOrNull { it.memberId == memberId }
            thisWeek = (row?.counts?.lastOrNull() ?: 0) to true
        } else {
            val viewer = runCatching { API.get<InboxOlfatoPanel>("/provider-olfato/viewer") }.getOrNull() ?: return
            val today = viewer.today ?: return
            thisWeek = TeamOlfatoAccess.weekCount(items, monday = TeamOlfatoAccess.monday(of = today), hasMore = hasMore)
        }
    }

    suspend fun load() {
        try {
            val page: TeamOlfatoTeamPage = API.get(path, query = mapOf("limit" to "$pageSize", "offset" to "0"))
            items = page.items ?: emptyList()
            total = page.total ?: items.size
            hasMore = page.hasMore ?: (items.size < total)
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = items.isEmpty()
        }
        loaded = true
        countThisWeek()
    }

    suspend fun more() {
        loadingMore = true
        try {
            val page: TeamOlfatoTeamPage = API.get(path, query = mapOf("limit" to "$pageSize", "offset" to "${items.size}"))
            val known = items.map { it.id }.toSet()
            items = items + (page.items ?: emptyList()).filter { !known.contains(it.id) }
            page.total?.let { total = it }
            hasMore = page.hasMore ?: (items.size < total)
            moreFailed = false
            if (!seesTeam) countThisWeek()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            moreFailed = true
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(memberId) { load() }

    TeamPanel(
        title = L("Questions to Olfato AI"),
        subtitle = if (isSelf) L("What you asked Olfato AI about, and when. Never the text.") else LF("What %@ asked Olfato AI about, and when. Never the text.", name),
        systemImage = "olfato",
        tint = Theme.aqua,
        modifier = modifier,
        action = {
            thisWeek?.let { (count, exact) ->
                Text(
                    if (exact) LF("%d this week", count) else LF("%d+ this week", count),
                    Modifier
                        .background(Theme.surfaceMuted, CapsuleShape)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    style = Theme.text(14, FontWeight.Bold),
                    color = Theme.inkStrong,
                )
            }
        },
    ) {
        if (!loaded) {
            TeamProgress(minHeight = 60.dp)
        } else if (failed) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(L("Could not load the questions to Olfato AI."), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { load() } }
            }
        } else if (items.isEmpty()) {
            EmptyState(systemImage = "message-circle-question", title = L("No questions to Olfato AI yet."))
        } else {
            Column(Modifier.fillMaxWidth()) {
                items.forEach { entry ->
                    OlfatoAccessLine(entry = entry, showPerson = false)
                    if (entry.id != items.lastOrNull()?.id) RowDivider()
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LF("Showing %1\$d of %2\$d", items.size, maxOf(total, items.size)),
                    Modifier.weight(1f),
                    style = Theme.text(12, FontWeight.SemiBold),
                    color = Theme.inkSoft,
                )
                if (hasMore || moreFailed) {
                    PMButton(
                        if (moreFailed) L("Try again") else L("Show more"),
                        systemImage = "chevron-down",
                        variant = ButtonVariant.outline,
                        size = ButtonSize.compact,
                        isWorking = loadingMore,
                    ) { scope.launch { more() } }
                }
            }
        }
        if (seesTeam) {
            Text(
                L("See the whole team’s use"),
                Modifier.plainButton { seeingTeam = true },
                style = Theme.text(14, FontWeight.Bold),
                color = Theme.blue,
            )
        }
    }

    // iOS pushes Settings › Olfato AI here (`NavigationLink { TeamOlfatoSettingsPage() }`).
    val nav = LocalNavigator.current
    LaunchedEffect(seeingTeam) {
        if (!seeingTeam) return@LaunchedEffect
        seeingTeam = false
        nav.push(ProviderRoute.page("team-olfato-use") { TeamOlfatoSettingsPage() })
    }
}
