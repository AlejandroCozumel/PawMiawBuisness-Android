package com.pawmiaw.business.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  HomeDayScreen.kt (iOS HomeDayScreen.swift)
//
//  Home on another day (web components/app/today/home-day.tsx,
//  `GET /provider-day`), shown in place of today's board as on the web:
//  ‹ › on a phone's own line, the day's header with its numbers as filters —
//  a day ahead is the list to get ready for (not confirmed, consents
//  missing, no price, double booked, first visits); a day gone is its recap
//  (unpaid, notes to sign, reports incomplete, what was collected) — the
//  visits card, and the stays arriving and leaving that day beside it.
//

/** The other day's data, kept by Home so a pull to refresh reaches it. */
@Stable
class HomeDayModel {
    var day: HomeOtherDay? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var loading: Boolean by mutableStateOf(false)

    suspend fun load(date: String, w: Workspace) {
        loading = true
        try {
            day = API.get("/provider-day", mapOf("date" to date, "locationId" to w.locationId))
            error = null
        } catch (e: CancellationException) {
            // A newer load took over (iOS `catch is CancellationError {}`): nothing to say.
            throw e
        } catch (e: Throwable) {
            error = errorText(e)
        } finally {
            loading = false
        }
    }
}

@Composable
fun HomeDayView(
    date: String,
    today: String,
    model: HomeDayModel,
    onChangeDay: (String?) -> Unit,
    /** Booked from another day's Home: that day. Nil when this person can't book. */
    onNewAppointment: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val workspace = Workspace.shared
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(HomeDayFilter.all) }
    var width by remember { mutableStateOf(0.dp) }

    val ahead = date > today
    val phone = LocalSizeClass.current != SizeClass.regular

    val switcher: @Composable () -> Unit = {
        homeDayStepper(date = date, today = today, onChange = { next ->
            filter = HomeDayFilter.all
            onChangeDay(next)
        })
    }

    // MARK: The header

    val header: @Composable (List<ModuleStat>) -> Unit = { stats ->
        // "Mañana · sábado, 3 de octubre"; a day with no such name is just its date.
        val name = HomeDayKey.name(date, today)
        val full = HomeDayKey.long(date)
        val icon = if (ahead) "calendar-check-2" else "calendar-days"
        val accent = if (ahead) Theme.lavender else Theme.butter
        if (!phone || (ahead && onNewAppointment != null)) {
            ModuleHeader(icon = icon, title = name ?: full, subtitle = if (name == null) null else full, accent = accent, stats = stats, storageKey = "home-day") {
                if (!phone) switcher()
                if (ahead && onNewAppointment != null) {
                    PMButton(L("New appointment"), systemImage = "calendar-plus", action = onNewAppointment)
                }
            }
        } else {
            ModuleHeader(icon = icon, title = name ?: full, subtitle = if (name == null) null else full, accent = accent, stats = stats, storageKey = "home-day")
        }
    }

    fun toggle(next: HomeDayFilter) {
        filter = if (filter == next) HomeDayFilter.all else next
    }

    fun stats(day: HomeOtherDay): List<ModuleStat> {
        val counts = day.counts
        val missed = counts?.missed ?: 0
        if (ahead) {
            val seesPrices = (day.visits ?: emptyList()).any { it.prep != null && it.prep?.needsPrice != null }
            val stats = mutableListOf(
                ModuleStat(
                    "visits", label = L("Visits@day"), value = "${counts?.total ?: 0}", hint = if (missed > 0) LF("%d cancelled", missed) else null,
                    icon = "calendar-check-2", accent = Theme.aqua, active = filter == HomeDayFilter.all, action = { toggle(HomeDayFilter.all) },
                ),
                ModuleStat(
                    "unconfirmed", label = L("Not confirmed"), value = "${counts?.unconfirmed ?: 0}", icon = "mail-question-mark", accent = Theme.mango,
                    attention = (counts?.unconfirmed ?: 0) > 0, active = filter == HomeDayFilter.unconfirmed, action = { toggle(HomeDayFilter.unconfirmed) },
                ),
                ModuleStat(
                    "consents", label = L("Consent missing"), value = "${counts?.consentsMissing ?: 0}", icon = "file-pen-line", accent = Theme.pink,
                    attention = (counts?.consentsMissing ?: 0) > 0, active = filter == HomeDayFilter.consents, action = { toggle(HomeDayFilter.consents) },
                ),
            )
            if (seesPrices) {
                stats.add(
                    ModuleStat(
                        "price", label = L("No price"), value = "${counts?.needsPrice ?: 0}", icon = "tag", accent = Theme.butter,
                        attention = (counts?.needsPrice ?: 0) > 0, active = filter == HomeDayFilter.price, action = { toggle(HomeDayFilter.price) },
                    )
                )
            }
            // Said only when there is one: booked over another visit on purpose, worth a look before the day.
            if ((counts?.doubleBooked ?: 0) > 0) {
                stats.add(
                    ModuleStat(
                        "double", label = L("Double booked"), value = "${counts?.doubleBooked ?: 0}", icon = "triangle-alert", accent = Theme.mango,
                        attention = true, active = filter == HomeDayFilter.double, action = { toggle(HomeDayFilter.double) },
                    )
                )
            }
            stats.add(
                ModuleStat(
                    "first", label = L("First visits"), value = "${counts?.firstVisits ?: 0}", icon = "sparkles", accent = Theme.lavender,
                    active = filter == HomeDayFilter.first, action = { toggle(HomeDayFilter.first) },
                )
            )
            return stats
        }
        val stats = mutableListOf(
            ModuleStat(
                "done", label = L("Done@home"), value = "${counts?.done ?: 0}", hint = if (missed > 0) LF("%d missed@home", missed) else null,
                icon = "calendar-check-2", accent = Theme.aqua, active = filter == HomeDayFilter.all || filter == HomeDayFilter.missed,
                action = { toggle(if (missed > 0 && filter != HomeDayFilter.missed) HomeDayFilter.missed else HomeDayFilter.all) },
            ),
        )
        val unpaid = counts?.unpaidAmount
        if (unpaid != null) {
            val hint = listOfNotNull(
                if ((counts?.unpaid ?: 0) > 0) LF("%d visits", counts?.unpaid ?: 0) else null,
                // Bills never sent to the desk: not owed yet, but nobody charged them.
                if ((counts?.uncharged ?: 0) > 0) (if ((counts?.uncharged ?: 0) == 1) L("1 bill not closed") else LF("%d bills not closed", counts?.uncharged ?: 0)) else null,
            ).joinToString(" · ")
            stats.add(
                ModuleStat(
                    "unpaid", label = L("Unpaid"), value = Money.format(unpaid, day.currency), hint = hint.nonEmpty,
                    icon = "circle-dollar-sign", accent = Theme.pink, attention = (counts?.unpaid ?: 0) > 0, active = filter == HomeDayFilter.unpaid,
                    action = { toggle(HomeDayFilter.unpaid) },
                )
            )
        }
        stats.add(
            ModuleStat(
                "notes", label = L("Notes to sign"), value = "${counts?.notesToSign ?: 0}", icon = "notebook-pen", accent = Theme.mango,
                attention = (counts?.notesToSign ?: 0) > 0, active = filter == HomeDayFilter.notes, action = { toggle(HomeDayFilter.notes) },
            )
        )
        if ((counts?.reportsMissing ?: 0) > 0) {
            stats.add(
                ModuleStat(
                    "reports", label = L("Report incomplete"), value = "${counts?.reportsMissing ?: 0}", icon = "clipboard-check", accent = Theme.lavender,
                    attention = true, active = filter == HomeDayFilter.reports, action = { toggle(HomeDayFilter.reports) },
                )
            )
        }
        val collected = day.collected
        if (collected != null) {
            stats.add(ModuleStat("collected", label = L("Collected"), value = Money.format(collected, day.currency), icon = "hand-coins", accent = Theme.lime))
        }
        return stats
    }

    // MARK: Content

    fun matches(visit: HomeDayVisit): Boolean = when (filter) {
        HomeDayFilter.all -> true
        HomeDayFilter.unconfirmed -> visit.prep?.confirmation != null
        HomeDayFilter.consents -> !(visit.prep?.consentsMissing ?: emptyList()).isEmpty()
        HomeDayFilter.price -> visit.prep?.needsPrice == true
        HomeDayFilter.first -> visit.prep?.firstVisit == true
        HomeDayFilter.double -> visit.doubleBooked == true
        // Charged and still owed, as the server counts "Sin pagar": a draft is not a debt yet.
        HomeDayFilter.unpaid -> (visit.recap?.balanceDue ?: 0) > 0 && visit.recap?.billStatus != "draft"
        HomeDayFilter.notes -> (visit.recap?.notesToSign ?: 0) > 0
        HomeDayFilter.reports -> !(visit.recap?.reportMissing ?: emptyList()).isEmpty()
        HomeDayFilter.missed -> HomeDayRow.missed(visit)
    }

    val visitCard: @Composable (List<HomeDayVisit>) -> Unit = { visits ->
        val shown = visits.filter { matches(it) }
        val active = shown.filter { !HomeDayRow.missed(it) || filter == HomeDayFilter.missed }
        val dropped = if (filter == HomeDayFilter.all) visits.filter { HomeDayRow.missed(it) } else emptyList()
        SectionCard(
            title = if (ahead) L("Get ready") else L("How it went"),
            subtitle = if (ahead) L("What each visit still needs before they come.") else L("What was done, and what is still open."),
            trailing = {
                if (filter != HomeDayFilter.all) {
                    Box(Modifier.plainButton { filter = HomeDayFilter.all }) { Chip(text = L("See everything"), tone = Chip.Tone.muted, icon = "x") }
                }
            },
        ) {
            if (active.isEmpty() && dropped.isEmpty()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        systemImage = "calendar-days",
                        title = if (filter != HomeDayFilter.all) L("Nothing here") else (if (ahead) L("Nothing booked for this day") else L("No visits this day")),
                        message = if (filter != HomeDayFilter.all) L("No visit on this day matches.")
                        else (if (ahead) L("Visits booked for this day show here, with what each still needs.") else L("Visits of this day show here once there are some.")),
                        actionTitle = if (ahead && filter == HomeDayFilter.all && onNewAppointment != null) L("New appointment") else null,
                        action = onNewAppointment,
                        accent = Theme.aqua,
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    RowList(items = active) { visit -> HomeDayRow(visit = visit, ahead = ahead) }
                    if (dropped.isNotEmpty()) {
                        if (active.isNotEmpty()) RowDivider()
                        Text(
                            L("Cancelled or didn’t come").uppercase(),
                            Modifier
                                .fillMaxWidth()
                                .background(Theme.surfaceMuted)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            style = Theme.text(12, FontWeight.Bold).tracking(1.44),
                            color = Theme.inkSoft,
                        )
                        RowDivider()
                        RowList(items = dropped) { visit -> HomeDayRow(visit = visit, ahead = ahead) }
                    }
                }
            }
        }
    }

    /** The stays arriving and leaving that day. */
    val staysBlock: @Composable (HomeDayStays) -> Unit = { stays ->
        SectionCard(title = L("Stays"), icon = "bed-double", accent = Theme.blue) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                HomeDayStayGroup(if (ahead) L("Arriving") else L("Arrived"), "log-in", stays.arriving ?: emptyList(), arriving = true)
                HomeDayStayGroup(if (ahead) L("Leaving") else L("Left"), "log-out", stays.leaving ?: emptyList(), arriving = false)
            }
        }
    }

    val content: @Composable (HomeOtherDay) -> Unit = { day ->
        val staysAny = (day.stays?.arriving ?: emptyList()).isNotEmpty() || (day.stays?.leaving ?: emptyList()).isNotEmpty()
        val stays = day.stays
        if (width >= 940.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) { visitCard(day.visits ?: emptyList()) }
                if (staysAny && stays != null) Box(Modifier.width(352.dp)) { staysBlock(stays) }
            }
        } else {
            visitCard(day.visits ?: emptyList())
            if (staysAny && stays != null) staysBlock(stays)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = with(density) { it.width.toDp() } }
            .alpha(if (model.loading && model.day != null) 0.8f else 1f),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (phone) switcher()
        val day = model.day
        if (day != null && day.date == date) {
            header(stats(day))
            content(day)
        } else {
            header(emptyList())
            if (model.error != null) {
                EmptyState(
                    systemImage = "triangle-alert", title = L("This day couldn’t be loaded"), message = L("Check the connection and try again."),
                    actionTitle = L("Try again"), action = { scope.launch { model.load(date, workspace) } }, accent = Theme.mango, panel = true,
                )
            } else {
                LoadingState()
            }
        }
    }
    LaunchedEffect(date, workspace.scopeVersion) { model.load(date, workspace) }
    // `.onChange(of: date) { filter = .all }`: only on a change, not on the first appearance.
    val seenDate = remember { arrayOf(date) }
    LaunchedEffect(date) {
        if (seenDate[0] != date) {
            seenDate[0] = date
            filter = HomeDayFilter.all
        }
    }
}

/** One of the stays' groups: "LLEGAN · 2" and its rows. */
@Composable
private fun HomeDayStayGroup(title: String, icon: String, list: List<HomeDayStay>, arriving: Boolean) {
    val nav = LocalNavigator.current
    if (list.isNotEmpty()) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(icon, 14.dp, tint = Theme.inkSoft)
            Text("$title · ${list.size}".uppercase(), style = Theme.text(12, FontWeight.Bold).tracking(1.44), color = Theme.inkSoft)
        }
        list.forEach { stay ->
            key(stay.id) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainButton { nav.push(ProviderRoute.stay(stay.id)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (arriving) (stay.checkInTime ?: "—") else (stay.checkOutTime ?: "—"),
                        Modifier.width(48.dp),
                        style = Theme.heading(14).monospacedDigit(),
                        color = Theme.inkStrong,
                    )
                    Column(Modifier.weight(1f)) {
                        Text((stay.pets ?: emptyList()).joinToString(", "), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(stay.clientName, stay.unitName).mapNotNull { it.nonEmpty }.joinToString(" · "),
                            style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // An earlier day's arrival or departure still undone.
                        if (stay.late == true) {
                            Text(
                                if (stay.status == "checked_in") LF("Was due out %@", DateText.day(stay.checkOutDay)) else LF("Was due in %@", DateText.day(stay.checkInDay)),
                                style = Theme.text(12, FontWeight.Bold), color = Theme.destructive, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class HomeDayFilter { all, unconfirmed, consents, price, first, double, unpaid, notes, reports, missed }

/** iOS `HomeDayRow`'s static rule. */
private object HomeDayRow {
    fun missed(visit: HomeDayVisit): Boolean = visit.status == "cancelled" || visit.status == "no_show"
}

/** A visit of the day: its time and state, who and what, and what it still needs (ahead) or what is still open (gone). */
@Composable
private fun HomeDayRow(visit: HomeDayVisit, ahead: Boolean) {
    val nav = LocalNavigator.current

    /** A visit from a day gone that was never closed (still booked, waiting or in service). */
    val leftOpen = (visit.status ?: "") in listOf("scheduled", "checked_in", "in_progress")

    val state: Pair<String, Color> = when {
        HomeDayRow.missed(visit) -> (if (visit.status == "no_show") L("No-show") else L("Cancelled@visit")) to Theme.destructive
        // A family's online booking still waiting for an answer: it holds the time, it isn't booked yet.
        visit.status == "requested" -> L("To confirm") to ProviderState.waiting.color
        ahead -> if (visit.confirmationStatus == "confirmed") L("Confirmed@visit") to Theme.blue else L("Booked") to Theme.inkSoft
        else -> if (leftOpen) L("Left open") to Theme.destructive else L("Done@home") to Theme.blue
    }

    val stripe: Color = when {
        HomeDayRow.missed(visit) || (!ahead && leftOpen) -> Theme.destructive
        ahead -> Theme.lavender
        else -> Theme.borderSoft
    }

    val confirmation: Pair<String, Chip.Tone>? = when (visit.prep?.confirmation) {
        "pending" -> L("No answer yet") to Chip.Tone.warn
        "reschedule_requested" -> L("Asked to reschedule") to Chip.Tone.warn
        "cancellation_requested" -> L("Asked to cancel") to Chip.Tone.danger
        "declined" -> L("Declined@visit") to Chip.Tone.danger
        "delivery_failed" -> L("The confirmation didn’t reach them") to Chip.Tone.warn
        "expired" -> L("No answer — the request expired") to Chip.Tone.warn
        else -> null
    }

    /** "con Dra. Rivas" — unless the person is the client too (a vet's own pet). */
    val people: String = run {
        val client = visit.client?.name ?: ""
        val name = visit.assigned?.name
        val assigned: String? = if (name != null) {
            if (name.trim().equals(client.trim(), ignoreCase = true)) null else LF("with %@", name)
        } else if (ahead) L("Whoever is free") else null
        listOfNotNull(client.nonEmpty, assigned, visit.room.nonEmpty).joinToString(" · ")
    }

    val chips: List<HomeChipSpec> = run {
        val list = mutableListOf<HomeChipSpec>()
        val allergies = visit.pet?.allergies
        if (allergies != null && allergies.isNotEmpty()) {
            list.add(HomeChipSpec.chip(LF("Allergy: %@", allergies.joinToString(", ")), Chip.Tone.danger, icon = "triangle-alert"))
        }
        if (visit.doubleBooked == true) list.add(HomeChipSpec.chip(L("Double booked"), Chip.Tone.danger, icon = "triangle-alert"))
        if (visit.bookedOnline == true) list.add(HomeChipSpec.chip(L("Online@booking"), Chip.Tone.online, icon = "globe"))
        // A day ahead: what to sort out before they come.
        if (confirmation != null) list.add(HomeChipSpec.chip(confirmation.first, confirmation.second, icon = "mail-question-mark"))
        val consents = visit.prep?.consentsMissing
        if (consents != null && consents.isNotEmpty()) {
            list.add(HomeChipSpec.chip(LF("Consent missing: %@", consents.joinToString(", ")), Chip.Tone.danger, icon = "file-pen-line"))
        }
        if (visit.prep?.needsPrice == true) list.add(HomeChipSpec.chip(L("No price"), Chip.Tone.warn, icon = "tag"))
        if (visit.prep?.groomRequest == false) list.add(HomeChipSpec.chip(L("No grooming request yet"), Chip.Tone.info, icon = "scissors"))
        if (visit.prep?.groomRequest == true) list.add(HomeChipSpec.chip(L("Owner’s request in"), Chip.Tone.good, icon = "scissors"))
        if (visit.prep?.firstVisit == true) list.add(HomeChipSpec.chip(L("First visit"), Chip.Tone.info, icon = "sparkles"))
        // A day gone: what is still open.
        val recap = visit.recap
        if (recap != null) {
            val balance = recap.balanceDue
            if (balance != null && balance > 0) list.add(HomeChipSpec.chip(LF("Unpaid %@", Money.format(balance, recap.currency)), Chip.Tone.danger, icon = "circle-dollar-sign"))
            if (recap.billStatus == "paid") list.add(HomeChipSpec.chip(L("Paid@bill"), Chip.Tone.good, icon = "circle-dollar-sign"))
            if ((recap.notesToSign ?: 0) > 0) list.add(HomeChipSpec.chip(L("Note to sign"), Chip.Tone.warn, icon = "notebook-pen"))
            if (!(recap.reportMissing ?: emptyList()).isEmpty()) list.add(HomeChipSpec.chip(L("Report incomplete"), Chip.Tone.warn, icon = "clipboard-check"))
        }
        list
    }

    val label = "${L("Open visit")}: ${visit.pet?.name ?: ""} · ${visit.serviceLabel ?: ""}"
    val open = { nav.push(ProviderRoute.appointment(visit.id)) }
    EdgeRow(
        edge = stripe,
        time = DateText.time(visit.startsAt),
        timeNote = state.first,
        timeNoteColor = state.second,
        modifier = Modifier
            .alpha(if (HomeDayRow.missed(visit)) 0.7f else 1f)
            .plainButton(onClick = open)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { open(); true }
            },
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PetAvatar(name = visit.pet?.name, species = visit.pet?.species, photoURL = visit.pet?.photoUrl, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        withStyle(Theme.text(16, FontWeight.Bold).toSpanStyle().copy(color = Theme.inkStrong)) { append(visit.pet?.name ?: "—") }
                        withStyle(Theme.text(16).toSpanStyle().copy(color = Theme.inkSoft)) { append(" · " + (visit.serviceLabel ?: "")) }
                    },
                    style = Theme.text(16),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(people, Modifier.padding(top = 2.dp), style = Theme.text(12.8), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chips.isNotEmpty()) {
                    FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) { chips.forEach { chip -> key(chip.id) { chip.view() } } }
                }
            }
        }
    }
}
