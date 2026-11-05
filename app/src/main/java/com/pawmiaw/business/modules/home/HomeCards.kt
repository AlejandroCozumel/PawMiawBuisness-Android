package com.pawmiaw.business.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.stays.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  HomeCards.kt (iOS HomeCards.swift)
//
//  What stands above Home's board (web routes/provider.index.tsx): the setup
//  checklist "Tu espacio está al N%" (setup/setup-checklist.tsx), an invited
//  member's reminders (member-onboarding/member-reminders-card.tsx), visits
//  to relocate (locations/relocate-banner.tsx), and families' requests
//  waiting for an answer (requests/provider-requests.tsx `FamilyRequestsBanner`).
//

// MARK: - Setup checklist

@Composable
fun HomeChecklistCard(checklist: HomeChecklist, onChange: (HomeChecklist) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(AppContext.defaults.getString(HomeChecklistCardKeys.openKey, null) != "closed") }
    var showHidden by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val visible = (checklist.items ?: emptyList()).filter { it.dismissed != true }
    val hidden = (checklist.items ?: emptyList()).filter { it.dismissed == true }

    fun dismiss(id: String, dismissed: Boolean) {
        busy = id
        scope.launch {
            try {
                val updated: HomeChecklist = API.put("/provider-setup/checklist/$id", mapOf("dismissed" to dismissed))
                onChange(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e, L("Could not save. Try again."))
            } finally {
                busy = null
            }
        }
    }

    val left = visible.count { it.done != true }
    val percent = checklist.percent ?: 0
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(250), label = "checklist chevron")
    val hiddenTurn by animateFloatAsState(if (showHidden) 180f else 0f, tween(200), label = "hidden chevron")
    Card(padding = 0.dp, spacing = 0.dp, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (open) 16.dp else 10.dp)) {
            // Folded it is one quiet line, so the day's work stays at the top of Home.
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainButton {
                        open = !open
                        AppContext.defaults.edit().putString(HomeChecklistCardKeys.openKey, if (open) "open" else "closed").apply()
                    }
                    .semantics { selected = open },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(LF("Your workspace is %d%% ready", percent), style = Theme.heading(if (open) 20 else 14), color = Theme.inkStrong)
                    Text(
                        if (left == 1) L("1 thing left to make it work well.") else LF("%d things left to make it work well.", left),
                        style = Theme.text(if (open) 14 else 12), color = Theme.inkSoft,
                    )
                }
                Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(250)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ProgressBar(value = percent / 100.0, label = L("Setup progress"), modifier = Modifier.padding(top = 12.dp))
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in visible) {
                            key(item.id) {
                                HomeChecklistRow(item = item, busy = busy == item.id, dismiss = { dismiss(item.id, true) })
                            }
                        }
                    }
                    if (hidden.isNotEmpty()) {
                        Row(
                            Modifier
                                .padding(top = 12.dp)
                                .heightIn(min = 32.dp)
                                .plainButton { showHidden = !showHidden },
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ink = Theme.inkSoft
                            Glyph("eye-off", 16.dp, tint = ink)
                            Text(LF("Hidden (%d)", hidden.size), style = Theme.text(14, FontWeight.SemiBold), color = ink)
                            Glyph("chevron-down", 16.dp, tint = ink, modifier = Modifier.rotate(hiddenTurn))
                        }
                        AnimatedVisibility(
                            visible = showHidden,
                            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (item in hidden) {
                                    key(item.id) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                                                .padding(horizontal = 12.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                HomeChecklistMeta.title(item.id), style = Theme.text(14), color = Theme.inkSoft,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                            )
                                            if (busy == item.id) {
                                                HomeSpinner()
                                            } else {
                                                PMButton(L("Show again"), variant = ButtonVariant.link, size = ButtonSize.slim, tint = Theme.inkStrong) {
                                                    dismiss(item.id, false)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** `HomeChecklistCard.openKey`. */
object HomeChecklistCardKeys {
    const val openKey = "pawmiaw_negocios_setup_checklist_open"
}

/** Each checklist item's words (setup-checklist.tsx `ITEM_META`, `checklistWhy`) and where it is done. */
object HomeChecklistMeta {
    fun title(id: String): String = when (id) {
        "prices" -> L("Prices for your services")
        "rooms" -> L("Rooms")
        "team" -> L("Your team")
        "online_booking" -> L("Online booking")
        "policy" -> L("No-show and late-cancel policy")
        "cash_drawer" -> L("Cash drawer")
        "receipt" -> L("Receipt and legal details")
        "credentials" -> L("Professional credentials")
        "team_setup" -> L("Your team’s profiles")
        else -> id
    }

    /** The web's `ITEM_META` icon (Lucide). */
    fun icon(id: String): String = when (id) {
        "prices" -> "badge-dollar-sign"
        "rooms" -> "door-open"
        "team" -> "users"
        "online_booking" -> "calendar-check"
        "policy" -> "circle-slash"
        "cash_drawer" -> "wallet"
        "receipt" -> "receipt-text"
        "credentials" -> "id-card"
        "team_setup" -> "user-check"
        else -> "list-checks"
    }

    fun action(item: HomeChecklistItem): String {
        if (item.id == "credentials" && item.mine == false) return L("See the team")
        return when (item.id) {
            "prices" -> L("Add prices")
            "rooms" -> L("Set up rooms")
            "team" -> L("Invite someone")
            "online_booking" -> L("Set it up")
            "policy" -> L("Set the policy")
            "cash_drawer" -> L("Set up the drawer")
            "receipt" -> L("Add details")
            "credentials" -> L("Add yours")
            "team_setup" -> L("See who")
            else -> L("Open@needs")
        }
    }

    /** Where the app does it, or null when only the web does (the row says so). */
    fun route(item: HomeChecklistItem): ProviderRoute? = when (item.id) {
        "rooms" -> ProviderRoute.section(ProviderSection.rooms)
        "team", "team_setup" -> ProviderRoute.section(ProviderSection.staff)
        "online_booking" -> ProviderRoute.section(ProviderSection.availability)
        "credentials" -> if (item.mine == false) ProviderRoute.section(ProviderSection.staff) else null
        else -> null
    }

    fun why(item: HomeChecklistItem): String {
        val count = item.count ?: 0
        if (item.done == true) {
            return when (item.id) {
                "prices" -> L("Every service you offer has a price.")
                "rooms" -> if (count == 1) L("1 room set up.") else (if (count > 0) LF("%d rooms set up.", count) else L("You don't use rooms."))
                "team" -> L("Your team is invited.")
                "online_booking" -> L("Families you work with can book online.")
                "policy" -> L("Your no-show and late-cancel policy is set.")
                "cash_drawer" -> L("Your cash drawer is ready.")
                "receipt" -> L("Receipts carry your footer and legal details.")
                "credentials" -> L("Your vets can sign prescriptions.")
                "team_setup" -> L("Everyone on your team has their signature and credentials.")
                else -> item.why ?: ""
            }
        }
        return when (item.id) {
            "prices" -> if (count == 1) L("1 service has no price: its bill asks for it before charging.") else LF("%d services have no price: their bills ask for it before charging.", count)
            "rooms" -> L("Add your rooms, or say you don't use them, so two visits never share one by accident.")
            "team" -> L("Invite the people who work with you, then set their days and hours.")
            "online_booking" -> L("Off: families you work with can't book from the app.")
            "policy" -> L("No fee yet for no-shows or late cancellations.")
            "cash_drawer" -> L("Its starting cash, and the difference that should warn you.")
            "receipt" -> L("Your tax id (RFC), tax regime and a receipt footer.")
            "credentials" -> {
                if (count == 1) return L("1 vet has no professional credential, so they can't sign prescriptions.")
                if (count > 0) LF("%d vets have no professional credential, so they can't sign prescriptions.", count) else L("Nobody on the team can sign prescriptions yet.")
            }
            "team_setup" -> {
                val members = item.members ?: emptyList()
                fun gap(kind: String): Int = members.count { (it.gaps ?: emptyList()).contains(kind) }
                listOfNotNull(
                    if (gap("signature") > 0) LF("%d without a signature", gap("signature")) else null,
                    if (gap("credential") > 0) LF("%d without a credential to prescribe", gap("credential")) else null,
                    if (gap("welcome") > 0) LF("%d yet to open their welcome", gap("welcome")) else null,
                ).joinToString(" · ")
            }
            else -> item.why ?: ""
        }
    }
}

@Composable
private fun HomeChecklistRow(item: HomeChecklistItem, busy: Boolean, dismiss: () -> Unit) {
    val openURL = rememberOpenURL()
    val actions = LocalHomeActions.current
    val nav = LocalNavigator.current
    val done = item.done == true

    /** Once done: where the team's days and profiles are set (web: "Fija sus días y horarios", "Ver el equipo"). */
    val extraLink: String? = when (item.id) {
        "team" -> L("Set their days and hours")
        "credentials", "team_setup" -> L("See the team")
        else -> null
    }

    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (done) Theme.wash(Theme.blue, 7.0) else Color.Transparent, shape)
            .border(1.dp, if (done) Color.Transparent else Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            if (done) {
                HomeDisc(icon = "check", fill = Theme.blueFixed, ink = Color.White)
            } else {
                HomeDisc(icon = HomeChecklistMeta.icon(item.id))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(HomeChecklistMeta.title(item.id), style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong)
                Text(HomeChecklistMeta.why(item), style = Theme.text(14), color = Theme.inkSoft)
                if (done && extraLink != null) {
                    Text(
                        extraLink, style = Theme.text(14, FontWeight.SemiBold), color = Theme.blue,
                        modifier = Modifier.padding(top = 4.dp).plainButton { nav.push(ProviderRoute.section(ProviderSection.staff)) },
                    )
                }
            }
        }
        if (!done) {
            Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Each is done in place, in a sheet over Home, or on its own page.
                val sheet = HomeSetupSheet.forItem(item)
                val route = HomeChecklistMeta.route(item)
                if (sheet != null && actions != null) {
                    PMButton(HomeChecklistMeta.action(item), size = ButtonSize.compact) { actions.sheet = HomeSheet.setup(sheet) }
                } else if (route != null) {
                    PMButtonStyle(ButtonVariant.primary, ButtonSize.compact, onClick = { nav.push(route) }) {
                        PMButtonContent(title = HomeChecklistMeta.action(item), variant = ButtonVariant.primary, size = ButtonSize.compact)
                    }
                } else {
                    PMButton(HomeChecklistMeta.action(item), systemImage = "external-link", size = ButtonSize.compact) {
                        openURL(APIConfig.webURL("provider").toString())
                    }
                }
                if (busy) {
                    HomeSpinner(modifier = Modifier.padding(horizontal = 12.dp))
                } else {
                    val hideLabel = LF("Hide “%@”", HomeChecklistMeta.title(item.id))
                    PMButton(
                        L("Not for me"), variant = ButtonVariant.link, size = ButtonSize.slim, tint = Theme.inkStrong,
                        modifier = Modifier.semantics { contentDescription = hideLabel }, action = dismiss,
                    )
                }
            }
        }
    }
}

// MARK: - An invited member's reminders

private data class HomeReminderCopy(val title: String, val body: String, val action: String, val icon: String)

@Composable
fun HomeRemindersCard(reminders: List<HomeMemberReminder>, onHidden: (String) -> Unit, modifier: Modifier = Modifier) {
    val openURL = rememberOpenURL()
    val actions = LocalHomeActions.current
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun copy(reminder: HomeMemberReminder): HomeReminderCopy = when (reminder.id) {
        "signature" -> HomeReminderCopy(
            L("Your signature is missing"), L("Adopt it once and prescriptions, records and discharges are signed with one tap."),
            L("Adopt my signature"), "file-pen-line",
        )
        "credential" -> {
            val noun = when (reminder.credentialType) {
                "cedula_profesional" -> L("your cédula profesional")
                "state_license" -> L("your state license")
                else -> L("your professional license")
            }
            HomeReminderCopy(LF("Add %@ to prescribe", noun), L("Without it you can’t sign prescriptions here. Everything else works as usual."), L("Add it"), "id-card")
        }
        else -> HomeReminderCopy(L("Finish your welcome"), L("A few steps left: your profile, alerts and how you work."), L("Continue@welcome"), "sparkles")
    }

    /** Each reminder opens the welcome at its step. */
    fun resume(id: String) {
        val step: String? = if (id == "signature") "signature" else (if (id == "credential") "credentials" else null)
        if (actions != null) {
            actions.resumeWelcome(step)
            return
        }
        busy = id
        scope.launch {
            try {
                API.send("POST", "/provider-member-onboarding/resume", mapOf("step" to step))
                openURL(APIConfig.webURL("provider").toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e, L("Could not open it. Try again."))
            } finally {
                busy = null
            }
        }
    }

    fun hide(id: String) {
        busy = id
        scope.launch {
            try {
                API.send("PUT", "/provider-member-onboarding/reminders/$id", mapOf("hidden" to true))
                onHidden(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                busy = null
            }
        }
    }

    val groupLabel = L("Left for later")
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = groupLabel },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (reminder in reminders) {
            key(reminder.id) {
                val copy = copy(reminder)
                val working = busy == reminder.id || (actions?.isBusy("welcome") ?: false)
                Card(padding = 16.dp, spacing = 12.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        IconBadge(icon = copy.icon, accent = Theme.mango, size = 40.dp, iconSize = 20.dp, ink = IconBadge.Ink.strong, washPercent = 32.0)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(copy.title, style = Theme.heading(18), color = Theme.inkStrong)
                            Text(copy.body, style = Theme.text(14), color = Theme.inkSoft)
                        }
                    }
                    Row(Modifier.padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PMButtonStyle(ButtonVariant.primary, ButtonSize.compact, onClick = { resume(reminder.id) }, enabled = !working) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(copy.action)
                                if (working) {
                                    HomeSpinner(color = Theme.onBlue)
                                } else {
                                    Glyph("arrow-right", 16.dp)
                                }
                            }
                        }
                        IconCircleButton(systemImage = "x", label = LF("Hide “%@”", copy.title), enabled = busy != reminder.id) { hide(reminder.id) }
                    }
                }
            }
        }
    }
}

// MARK: - Visits to relocate

@Composable
fun HomeRelocateBanner(visits: List<HomeRelocationVisit>, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val places = visits.mapNotNull { it.locationName }.distinct()
    Card(tint = Theme.mango, modifier = modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.relocate)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeDisc(icon = "arrow-right-left", size = 40.dp, iconSize = 20.dp, fill = Theme.surface)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (visits.size == 1) L("1 visit to relocate") else LF("%d visits to relocate", visits.size),
                    style = Theme.heading(18), color = Theme.inkStrong,
                )
                Text(
                    LF("%@ is paused by the plan. Move each visit to an open location or cancel it with a notice to the family.", places.joinToString(", ")),
                    style = Theme.text(14), color = Theme.inkSoft,
                )
            }
            Glyph("arrow-right", 20.dp, tint = Theme.inkSoft)
        }
    }
}

// MARK: - Families' requests

@Composable
fun HomeRequestsBanner(requests: List<HomeFamilyRequest>, online: HomeOnlineRequestList?, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current

    val waiting = requests.filter { it.displayStatus == "pending" }
    val accepted = waiting.count { it.familyAcceptedAt != null }
    val onlineCount = online?.count ?: 0
    // The server's count holds the families' asks to move a booking too ("Cambio de hora").
    val asks = minOf(onlineCount, (online?.items ?: emptyList()).count { it.isReschedule })
    val bookings = onlineCount - asks
    val total = waiting.size + onlineCount
    if (total > 0) {
        val more = listOfNotNull(
            if (bookings > 0) (if (bookings == 1) L("1 visit booked online to confirm.") else LF("%d visits booked online to confirm.", bookings)) else null,
            if (asks > 0) LP(asks, "%d change of time to answer.", "%d changes of time to answer.") else null,
            if (accepted > 0) (if (accepted == 1) L("1 family accepted the dates you proposed.") else LF("%d families accepted the dates you proposed.", accepted)) else null,
        ).joinToString(" ")
        NoticeCard(
            tone = NoticeCard.Tone.custom(Theme.pink),
            title = if (total == 1) L("1 request from a family is waiting for your answer.") else LF("%d requests from families are waiting for your answer.", total),
            message = more.nonEmpty ?: L("Stays, home stays and programs asked for from the app."),
            icon = "mail-question-mark",
            look = NoticeCard.Look.banner,
            actionTitle = L("Review requests"),
            modifier = modifier,
            action = { nav.push(ProviderRoute.page("home:requests") { HomeRequestsScreen() }) },
        )
    }
}

/**
 * "Solicitudes de familias": hotel stays, daycare and home stays (the Stays module's list and sheets,
 * with the online bookings to confirm beside them) and programs, each answered here.
 */
@Composable
fun HomeRequestsScreen(modifier: Modifier = Modifier) {
    providerPage(L("Requests from families"), modifier = modifier) {
        PageScroll {
            ModuleHeader(
                icon = "mail-question-mark", title = L("Requests from families"),
                subtitle = L("Confirm, propose other dates or decline. The family is told either way."), accent = Theme.aqua, storageKey = "requests",
            )
            StaysRequestsView(held = emptyList())
            HomeProgramRequests()
        }
    }
}
