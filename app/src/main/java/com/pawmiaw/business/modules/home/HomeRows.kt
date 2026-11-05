package com.pawmiaw.business.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

//
//  HomeRows.kt (iOS HomeRows.swift)
//
//  The rows of Home's board (web visit-row.tsx, home-hub.tsx, the session
//  row of today-board.tsx, hospital/arrivals-block.tsx): the 3 pt edge in
//  the state's colour, the time column with the state under it, who and
//  what, the facts as small chips, and the one next step. A row opens what
//  it is about; the "⋯" holds the rest. Check-in, finish, charging,
//  cancelling and rebooking are the Agenda's sheets, opened over Home.
//
//  Android: iOS moves the time column to the row's first line on a phone
//  (`.alignmentGuide` + the top line's height) when the step sits on its own
//  line; the kit's `EdgeRow` keeps the time column centred on the whole row.
//

// MARK: - A visit

/** One step per row (iOS `HomeVisitRow.Action`). */
private sealed class HomeVisitRowAction {
    data class navigate(val title: String, val icon: String) : HomeVisitRowAction()
    data class route(val title: String, val icon: String, val route: ProviderRoute) : HomeVisitRowAction()
    class call(val title: String, val icon: String, val action: () -> Unit) : HomeVisitRowAction()
    data class run(val title: String, val icon: String, val path: String, val body: Map<String, Any?>, val success: String) : HomeVisitRowAction()
}

@Composable
fun HomeVisitRow(
    visit: HomeVisit,
    together: List<HomeVisit>,
    stateLabel: String,
    hospitalized: Boolean,
    compact: Boolean,
    permissions: HomePermissions,
    categories: List<String>,
    open: (ProviderRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openURL = rememberOpenURL()
    val nav = LocalNavigator.current
    val actions = LocalHomeActions.current
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val phone = LocalSizeClass.current != SizeClass.regular
    val tone = HomeText.laneColor(visit)
    val clock = HomeText.clock(visit)
    val busy = working || (actions?.isBusy(visit.id) ?: false)
    /** A row ahead of its arrival checks in on a tap; once they are here it opens the visit. */
    val checksInOnTap = visit.laneValue == HomeLane.upcoming && permissions.checkIn && actions != null

    /** A groom under way says where it is ("En baño") instead of the generic state (lib/groom-stages.ts `groomStageInProgress`). */
    val groomStage = visit.groomStage
    val groomState: Pair<String, Color>? =
        if (visit.status == "in_progress" && (visit.serviceCategory == null || visit.serviceCategory == "groomer") &&
            groomStage != null && groomStage in listOf("bath", "styling", "ready")
        ) {
            val simple = actions?.simpleStages ?: false
            HomeText.groomStage(SvcGroomStage.shown(groomStage, simple), simple) to ProviderState.groom(SvcGroomStage.shown(groomStage, simple)).color
        } else null

    fun openRow() {
        if (checksInOnTap && actions != null) actions.checkIn(visit.id) else open(ProviderRoute.appointment(visit.id))
    }

    fun run(path: String, body: Map<String, Any?> = emptyMap(), success: String, openOnRefusal: Boolean = false) {
        working = true
        scope.launch {
            try {
                API.send("POST", path, body)
                Toast.show(success)
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
                // A start that waits on a consent or a deposit is answered on the visit page.
                if (openOnRefusal && (e as? APIError)?.isRefusal == true) open(ProviderRoute.appointment(visit.id))
            } finally {
                working = false
            }
        }
    }

    fun move(step: String, success: String) {
        val path = "/provider-appointments/${visit.id}/$step"
        if (actions != null) actions.run(visit.id, path, success) else run(path, success = success)
    }

    /** Who, with whom, where — plain text, not tags. */
    val people: String = kotlin.run {
        val client = visit.client?.name ?: ""
        val assigned: String? = if (compact) null else {
            val person = visit.assigned
            if (person == null) L("Whoever is free") else {
                val name = person.name ?: "—"
                if (name.trim().equals(client.trim(), ignoreCase = true)) null else LF("with %@", name)
            }
        }
        listOfNotNull(client.nonEmpty, assigned, if (compact) null else visit.room.nonEmpty).joinToString(" · ")
    }

    val chips: List<HomeChipSpec> = kotlin.run {
        val list = mutableListOf<HomeChipSpec>()
        if (compact) {
            if (visit.laneValue == HomeLane.missed) list.add(HomeChipSpec.chip(if (visit.status == "no_show") L("No-show") else L("Cancelled@visit"), Chip.Tone.danger))
            if (visit.fee?.decision == "charged") list.add(HomeChipSpec.chip(L("Fee charged"), Chip.Tone.warn))
            if (visit.fee?.decision == "waived") list.add(HomeChipSpec.chip(L("Fee waived"), Chip.Tone.muted))
            if (visit.billing == "paid") {
                list.add(HomeChipSpec.chip(if ((visit.invoice?.totalAmount ?: 1) == 0) L("Nothing to charge") else L("Paid@bill"), Chip.Tone.good, icon = "circle-dollar-sign"))
            }
            if (visit.billing == "no_charge") list.add(HomeChipSpec.chip(L("No charge"), Chip.Tone.good))
            if (visit.laneValue == HomeLane.done && (visit.notesCount?.drafts ?: 0) > 0) list.add(HomeChipSpec.chip(L("Note to sign"), Chip.Tone.warn, icon = "notebook-pen"))
            if (visit.bookedOnline == true) list.add(HomeChipSpec.chip(L("Online@booking"), Chip.Tone.online, icon = "globe"))
            return@run list
        }
        // The clock takes the state's place under the time, so a groom's stage is said here.
        if (groomState != null && clock != null) list.add(HomeChipSpec.stage(groomState.first, groomState.second, icon = "scissors"))
        val priority = visit.triagePriority
        if (priority != null && visit.laneValue in listOf(HomeLane.waiting, HomeLane.inService, HomeLane.upcoming)) {
            list.add(
                HomeChipSpec.chip(
                    HomeTriage.label(priority).uppercase(),
                    if (priority == "critical") Chip.Tone.danger else (if (priority == "urgent") Chip.Tone.warn else Chip.Tone.good),
                    icon = "siren",
                )
            )
        }
        val reason = visit.triageReason.nonEmpty
        if (reason != null && visit.laneValue in listOf(HomeLane.waiting, HomeLane.inService)) {
            list.add(HomeChipSpec.chip(reason, if (visit.triagePriority == "critical") Chip.Tone.danger else Chip.Tone.warn))
        }
        if (visit.afterHours == true) list.add(HomeChipSpec.chip(L("After hours"), Chip.Tone.info, icon = "moon"))
        val minutes = visit.pickupPassedMinutes
        if (minutes != null) {
            list.add(HomeChipSpec.chip(LF("Still in your care · pickup was %@ ago", HomeText.shortDuration(minutes)), Chip.Tone.danger, icon = "clock"))
            if (visit.familyAttendance == "attended") list.add(HomeChipSpec.chip(L("The family says it is home"), Chip.Tone.warn, icon = "house"))
        }
        if (hospitalized) list.add(HomeChipSpec.chip(L("Hospitalized"), Chip.Tone.danger, icon = "hospital"))
        val allergies = visit.pet?.allergies
        if (allergies != null && allergies.isNotEmpty()) {
            list.add(HomeChipSpec.chip(LF("Allergy: %@", allergies.joinToString(", ")), Chip.Tone.danger, icon = "triangle-alert"))
        }
        if (visit.confirmationStatus == "reschedule_requested") list.add(HomeChipSpec.chip(L("Asked to reschedule"), Chip.Tone.warn))
        if (visit.confirmationStatus == "cancellation_requested") list.add(HomeChipSpec.chip(L("Asked to cancel"), Chip.Tone.warn))
        if (visit.bookedOnline == true) list.add(HomeChipSpec.chip(L("Online@booking"), Chip.Tone.online, icon = "globe"))
        if (visit.hoursOverride == true) list.add(HomeChipSpec.chip(L("After hours"), Chip.Tone.muted, icon = "moon-star"))
        if ((visit.notesCount?.drafts ?: 0) > 0) list.add(HomeChipSpec.chip(L("Note to sign"), Chip.Tone.warn, icon = "notebook-pen"))
        val bill = HomeText.billChip(visit)
        if (bill != null) list.add(HomeChipSpec.chip(bill.first, bill.second, icon = "circle-dollar-sign"))
        if (visit.billing == "no_charge") list.add(HomeChipSpec.chip(L("No charge"), Chip.Tone.good))
        list
    }

    // MARK: The next step

    /** One step per row: arrive, start, finish (or check out), price, collect, send to the desk, book again. */
    val primary: HomeVisitRowAction? = when (visit.laneValue) {
        HomeLane.upcoming ->
            if (!permissions.checkIn) null
            else if (actions != null) HomeVisitRowAction.call(L("Check in@visit"), "arrow-right-to-line") { actions.checkIn(visit.id) }
            else HomeVisitRowAction.navigate(L("Check in@visit"), "arrow-right-to-line")
        HomeLane.waiting ->
            if (!permissions.start) null
            else if (actions != null) HomeVisitRowAction.call(L("Start@visit"), "play") { actions.start(visit.id, visit.petName) }
            else HomeVisitRowAction.run(L("Start@visit"), "play", path = "/provider-appointments/${visit.id}/start", body = emptyMap(), success = LF("%@ is in.", visit.petName))
        HomeLane.inService -> {
            // Whoever takes payments checks a visit out so it can be charged; the note stays with the visit's person.
            if (!(permissions.start || permissions.takePayment)) null else {
                val title = if (permissions.start) L("Finish@visit") else L("Check out@visit")
                val image = if (permissions.start) "badge-check" else "log-out"
                if (actions != null) HomeVisitRowAction.call(title, image) { actions.finish(visit.id) } else HomeVisitRowAction.navigate(title, image)
            }
        }
        HomeLane.toPay -> kotlin.run {
            // The bill opens in Cuentas: price it, charge it, or send it to the desk — by permission, not role.
            if (visit.billing == "needs_price" || visit.billing == "needs_bill") {
                if (!(permissions.price && permissions.billView)) return@run null
                return@run HomeVisitRowAction.route(L("Price it"), "clipboard-pen", visit.invoice?.let { ProviderRoute.invoice(it.id) } ?: ProviderRoute.appointment(visit.id))
            }
            val invoice = visit.invoice ?: return@run null
            val draft = invoice.status == "draft"
            if (permissions.takePayment && permissions.billView && (!draft || permissions.billSend)) {
                val amount = if (draft) (invoice.totalAmount ?: 0) - (invoice.amountPaid ?: 0) else (invoice.balanceDueAmount ?: 0)
                return@run HomeVisitRowAction.route(LF("Collect %@", Money.format(amount, invoice.currency)), "hand-coins", ProviderRoute.invoice(invoice.id))
            }
            if (draft && permissions.billSend) {
                return@run HomeVisitRowAction.run(L("Send to the desk"), "send", path = "/provider-invoices/${invoice.id}/send-to-desk", body = emptyMap(), success = L("Sent to the desk to charge."))
            }
            null
        }
        HomeLane.missed ->
            if (!permissions.edit) null
            else if (actions != null) HomeVisitRowAction.call(L("Book again"), "calendar-plus") { actions.rebook(visit.id) }
            else HomeVisitRowAction.navigate(L("Book again"), "calendar-plus")
        HomeLane.done -> null
    }

    val menu: @Composable () -> Unit = {
        AppMenu(label = { IconCircleLabel(bordered = false) }, enabled = !busy) {
            item(L("Open visit"), icon = "stethoscope") { open(ProviderRoute.appointment(visit.id)) }
            val clientId = visit.client?.id
            if (permissions.clients && clientId != null) {
                item(L("Open client"), icon = "user-round") { open(ProviderRoute.client(clientId)) }
            }
            val petId = visit.pet?.id
            if (permissions.clients && petId != null) {
                item(L("See chart"), icon = "paw-print") { open(ProviderRoute.patientChart(petId = petId)) }
            }
            val url = ContactLinks.phone(visit.client?.phone)
            if (url != null) {
                item(LF("Call %@", NameText.firstName(visit.client?.name)), icon = "phone") { openURL(url) }
            }
            if (visit.laneValue == HomeLane.waiting && permissions.checkIn) {
                item(L("Undo arrival"), icon = "undo-2") { move("undo-arrival", success = L("Back to upcoming.")) }
            }
            val invoice = visit.invoice
            if ((visit.laneValue == HomeLane.toPay || (visit.laneValue == HomeLane.done && invoice != null)) && permissions.billView && invoice != null) {
                item(L("See the bill"), icon = "receipt") { open(ProviderRoute.invoice(invoice.id)) }
            }
            if (visit.laneValue == HomeLane.toPay && permissions.start && (visit.invoice?.amountPaid ?: 0) == 0) {
                item(L("Reopen visit"), icon = "rotate-ccw") { move("reopen", success = L("Back with the vet.")) }
            }
            if (actions != null) {
                val late = visit.laneValue == HomeLane.upcoming && permissions.checkIn && (visit.lateMinutes ?: 0) > 0
                val cancels = (visit.laneValue == HomeLane.upcoming || visit.laneValue == HomeLane.waiting) && permissions.cancel
                if (late || cancels) divider()
                if (late) {
                    item(L("They didn’t come"), icon = "triangle-alert", destructive = true) { actions.noShow(visit.id) }
                }
                if (cancels) {
                    item(L("Cancel visit"), icon = "triangle-alert", destructive = true) { actions.cancel(visit.id) }
                }
            }
        }
    }

    /** The step (an outline 36 pt button, the width of the line on a phone) and the "⋯". */
    val actionLine: @Composable () -> Unit = {
        Row(
            if (phone) Modifier.fillMaxWidth() else Modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val action = primary
            if (action != null) {
                val wide = if (phone) Modifier.weight(1f) else Modifier
                when (action) {
                    is HomeVisitRowAction.navigate ->
                        PMButtonStyle(ButtonVariant.outline, ButtonSize.header, fullWidth = phone, modifier = wide, onClick = { nav.push(ProviderRoute.appointment(visit.id)) }) {
                            PMButtonContent(title = action.title, icon = action.icon)
                        }
                    is HomeVisitRowAction.route ->
                        PMButtonStyle(ButtonVariant.outline, ButtonSize.header, fullWidth = phone, modifier = wide, onClick = { nav.push(action.route) }) {
                            PMButtonContent(title = action.title, icon = action.icon)
                        }
                    is HomeVisitRowAction.call ->
                        PMButton(action.title, systemImage = action.icon, variant = ButtonVariant.outline, size = ButtonSize.header, fullWidth = phone, isWorking = busy, modifier = wide, action = action.action)
                    is HomeVisitRowAction.run ->
                        PMButton(action.title, systemImage = action.icon, variant = ButtonVariant.outline, size = ButtonSize.header, fullWidth = phone, isWorking = busy, modifier = wide) {
                            run(action.path, body = action.body, success = action.success, openOnRefusal = true)
                        }
                }
            } else if (phone) {
                Spacer(Modifier.weight(1f))
            }
            menu()
        }
    }

    EdgeRow(
        edge = tone,
        time = DateText.time(visit.startsAt),
        timeNote = clock?.first ?: groomState?.first ?: stateLabel,
        timeNoteColor = clock?.second ?: tone,
        dimmed = visit.laneValue == HomeLane.missed || compact,
        modifier = modifier
            .plainButton { openRow() }
            .semantics {
                customActions = listOf(CustomAccessibilityAction(if (checksInOnTap) L("Check in@visit") else L("Open visit")) { openRow(); true })
            },
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // The avatar.
                if (together.isEmpty()) {
                    PetAvatar(name = visit.petName, species = visit.pet?.species, photoURL = visit.pet?.photoUrl, size = 40.dp)
                } else {
                    PetStack(pets = (listOf(visit) + together).map { Triple(it.petName, it.pet?.species, it.pet?.photoUrl) }, size = 40.dp)
                }
                // The details.
                Column(Modifier.weight(1f)) {
                    HomeRowsTitle(HomeText.names(listOf(visit.petName) + together.map { it.petName }), " · " + (visit.serviceLabel ?: ""))
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(people, Modifier.weight(1f, fill = false), style = Theme.text(12.8), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (visit.client?.onPawMiaw == false && !compact) {
                            val notOn = L("Not on PawMiaw")
                            Glyph("bell-off", 14.dp, tint = Theme.inkSoft, modifier = Modifier.alpha(0.6f).semantics { contentDescription = notOn })
                        }
                    }
                    val note = visit.arrivalNote.nonEmpty
                    if (!compact && note != null && visit.laneValue in listOf(HomeLane.waiting, HomeLane.inService)) {
                        Text(note, Modifier.padding(top = 2.dp), style = Theme.text(12).copy(fontStyle = FontStyle.Italic), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (chips.isNotEmpty()) {
                        FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
                            chips.forEach { chip -> key(chip.id) { chip.view() } }
                        }
                    }
                }
                if (!phone) actionLine()
            }
            // A phone: the step on its own line, under the words (indented past the time).
            if (phone) actionLine()
        }
    }
}

/** One fact on a row: the kit's small chip, or a groom's stage pill. */
sealed class HomeChipSpec {
    data class chip(val text: String, val tone: Chip.Tone, val icon: String? = null) : HomeChipSpec()
    data class stage(val text: String, val tone: Color, val icon: String? = null, val count: Int? = null) : HomeChipSpec()

    val id: String
        get() = when (this) {
            is chip -> "chip-$text-${icon ?: ""}"
            is stage -> "stage-$text"
        }

    @Composable
    fun view() {
        when (this) {
            is chip -> Chip(text = text, tone = tone, icon = icon)
            is stage -> Chip(text = text, tone = Chip.Tone.custom(tone), icon = icon, dot = if (icon == null) tone else null, count = count)
        }
    }
}

object HomeTriage {
    fun label(priority: String): String = when (priority) {
        "critical" -> L("Critical")
        "urgent" -> L("Urgent@triage")
        "stable" -> L("Stable")
        else -> priority
    }
}

// MARK: - A group walk or class

@Composable
fun HomeSessionRow(session: HomeSession, stateLabel: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val actions = LocalHomeActions.current
    val nav = LocalNavigator.current
    /** The session's own page (iOS `.navigationDestination(isPresented: $opened)`). */
    val openSession = { nav.push(ProviderRoute.page("session:${session.id}") { SvcSessionScreen(sessionId = session.id) }) }

    val phone = LocalSizeClass.current != SizeClass.regular

    /** It starts with whoever arrived; nobody marked yet is "take the register" in the session. */
    val next: String? = run {
        if (!workspace.can(Permission.appointmentsComplete)) return@run null
        val staying = session.staying
        if (staying.any { it.status == "in_progress" }) return@run "finish"
        if (staying.any { it.status == "checked_in" }) return@run "start"
        if (staying.any { it.status == "scheduled" }) return@run "register"
        null
    }

    val arrived = session.staying.count { it.status == "checked_in" }

    val places: String = run {
        val size = session.size ?: session.staying.size
        val capacity = session.capacity
        if (capacity != null) "$size/$capacity" else "$size"
    }

    val step: @Composable (String) -> Unit = { next ->
        val busy = actions?.isBusy(session.id) ?: false
        when (next) {
            "register" ->
                PMButton(L("Take the register"), systemImage = "list-checks", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = phone) { openSession() }
            "start" ->
                PMButton(
                    if (session.startMode == "pickup") LF("Start the walk with %d", arrived) else LF("Start with those here (%d)", arrived),
                    systemImage = "play", size = ButtonSize.header, fullWidth = phone, isWorking = busy,
                ) {
                    actions?.moveSession(session.id, "start")
                }
            else ->
                PMButton(L("Finish for everyone"), systemImage = "badge-check", size = ButtonSize.header, fullWidth = phone, isWorking = busy) {
                    actions?.moveSession(session.id, "finish")
                }
        }
    }

    EdgeRow(
        edge = Theme.blue,
        time = DateText.time(session.startsAt),
        timeNote = stateLabel,
        timeNoteColor = Theme.blue,
        modifier = modifier.plainButton { openSession() },
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (session.staying.isEmpty()) {
                    HomeDisc(icon = "users", size = 40.dp, iconSize = 16.dp, fill = Theme.surfaceMuted, ink = Theme.inkSoft)
                } else {
                    PetStack(pets = session.staying.map { Triple(it.name ?: "", it.species, it.photoUrl) }, size = 40.dp)
                }
                // The details.
                Column(Modifier.weight(1f)) {
                    HomeRowsTitle(session.serviceLabel ?: "", " · $places")
                    Text(
                        if (session.staying.isEmpty()) L("Nobody here yet") else session.staying.mapNotNull { it.name }.joinToString(", "),
                        style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
                        Chip(text = L("Group session"), tone = Chip.Tone.info, icon = "users")
                        session.assignedMember?.name?.let { name -> Chip(text = name, icon = "user-round") }
                        session.room.nonEmpty?.let { room -> Chip(text = room, icon = "door-open") }
                        if (session.startMode == "pickup") {
                            Chip(text = L("Picked up at home"), icon = "house")
                        } else {
                            session.meetingPoint.nonEmpty?.let { point -> Chip(text = point, icon = "map-pin") }
                        }
                    }
                }
                if (!phone && next != null) step(next)
            }
            if (phone && next != null) step(next)
        }
    }
}

// MARK: - Stays, hospital patients and home stays' visits

@Composable
fun HomeHubSection(module: HomeActivity, entries: List<HomeHubEntry>, memberId: String?, showEmpty: Boolean, modifier: Modifier = Modifier) {
    val actions = LocalHomeActions.current

    val link = when (module) {
        HomeActivity.hospital -> L("Open the hospital")
        HomeActivity.home -> L("Open home visits")
        else -> L("Open stays")
    }

    /** The part's own page, from the strip's link. */
    val openPage: (() -> Unit)? = actions?.let { a ->
        val section = module.section
        ({ a.route = ProviderRoute.section(section) })
    }

    val emptyTitle = when (module) {
        HomeActivity.hospital -> L("Nobody in the hospital")
        HomeActivity.home -> L("No home visits today")
        else -> L("Nothing in stays today")
    }

    val emptyBody = when (module) {
        HomeActivity.hospital -> L("Admitted patients and those waiting for a bed show up here.")
        HomeActivity.home -> L("The day's visits to families' homes show up here.")
        else -> L("Arrivals, pets in house and departures show up here.")
    }

    if (entries.isEmpty()) {
        if (showEmpty) {
            HomeRowsEmptyLink(icon = module.icon, title = emptyTitle, message = emptyBody, link = link, route = ProviderRoute.section(module.section), modifier = modifier)
        }
    } else {
        HomeStrip(storageKey = module.rawValue, title = module.label, count = entries.size, dot = module.accent, linkTitle = link, link = openPage, modifier = modifier) {
            RowList(items = entries.take(HomeCap.rows)) { entry ->
                HomeHubRow(entry = entry, memberId = memberId)
            }
            if (entries.size > HomeCap.rows) {
                HomeStripMore(more = entries.size - HomeCap.rows, route = ProviderRoute.section(module.section))
            }
        }
    }
}

@Composable
private fun HomeHubRow(entry: HomeHubEntry, memberId: String?) {
    val nav = LocalNavigator.current

    val route: ProviderRoute =
        if (entry.module == "hospital") ProviderRoute.hospitalization(entry.entityId ?: entry.id) else ProviderRoute.stay(entry.entityId ?: entry.id)

    val kind = when (entry.kind) {
        "boarding" -> L("Boarding")
        "daycare" -> L("Daycare")
        "home_sitting" -> L("Home sitting")
        "meet_greet" -> L("Meet & greet")
        "visit" -> L("Home visit")
        "overnight" -> L("Overnight")
        "hospital" -> L("Hospitalized")
        else -> entry.kind ?: ""
    }

    /** The card's one-word state and its colour (home-hub.tsx `entryState`). */
    val state: Pair<String, Color> = when {
        entry.module == "hospital" ->
            if (entry.status == "pending") L("Waiting for admission") to Theme.mango else L("Admitted") to Theme.pink
        entry.module == "home" && entry.kind != "home_sitting" -> when (entry.status) {
            "in_progress" -> L("Under way") to Theme.blue
            "done" -> L("Done@hub") to Theme.inkSoft
            "missed" -> L("Missed@hub") to Theme.destructive
            else -> if (entry.late == true) L("Running late") to Theme.destructive else L("Planned") to Theme.aqua
        }
        else -> when (entry.status) {
            "confirmed" -> if (entry.late == true) L("Late arrival") to Theme.destructive else L("Arriving") to Theme.aqua
            "leaving" -> if (entry.late == true) L("Check-out past due") to Theme.destructive else L("Leaving today") to Theme.pink
            "checked_out" -> L("Left today") to Theme.inkSoft
            else -> L("In house") to Theme.blue
        }
    }

    val lateStay = entry.module != "hospital" && entry.late == true && (entry.status == "leaving" || entry.status == "confirmed")

    /** The next thing on the clock, or the day a pet in house leaves; a stay a day late shows the day it was due. */
    val starts = entry.startsAt
    val ends = entry.endsAt
    val `when`: String = when {
        starts != null -> if (lateStay) DateText.day(starts) else DateText.time(starts)
        ends != null -> DateText.day(ends)
        else -> "—"
    }

    val details = listOfNotNull(
        entry.clientName.nonEmpty,
        if (entry.module == "hospital" && entry.startsAt != null) LF("Next dose %@", DateText.time(entry.startsAt)) else null,
        if (entry.startsAt == null && entry.endsAt != null) LF("Leaves %@", DateText.day(entry.endsAt)) else null,
        entry.room.nonEmpty,
    ).joinToString(" · ")

    val chips: List<HomeChipSpec> = run {
        val list = mutableListOf<HomeChipSpec>()
        val attention = entry.attention
        if (entry.module == "hospital" && attention != null && attention > 0) {
            list.add(
                HomeChipSpec.chip(
                    if (attention == 1) L("1 treatment needs someone") else LF("%d treatments need someone", attention),
                    if (entry.late == true) Chip.Tone.danger else Chip.Tone.warn,
                    icon = if (entry.late == true) "triangle-alert" else "clock-3",
                )
            )
        }
        if (lateStay && starts != null) {
            list.add(
                HomeChipSpec.chip(
                    if (entry.status == "leaving") LF("Was due out %@ · still here", DateText.day(starts)) else LF("Was due in %@ · not here yet", DateText.day(starts)),
                    Chip.Tone.danger,
                    icon = "triangle-alert",
                )
            )
        }
        if (entry.module == "home" && entry.kind != "home_sitting" && entry.assignedId == null) list.add(HomeChipSpec.chip(L("Nobody assigned"), Chip.Tone.info))
        val assigned = entry.assignedId
        if (assigned != null && assigned == memberId) list.add(HomeChipSpec.chip(L("Assigned to you"), Chip.Tone.info))
        list
    }

    val pets = entry.pets ?: emptyList()
    val names = pets.mapNotNull { it.name }.joinToString(", ")
    val done = entry.status == "done" || entry.status == "checked_out"
    EdgeRow(
        edge = if (done) Theme.borderSoft else state.second,
        time = `when`,
        timeNote = state.first,
        timeNoteColor = state.second,
        showsChevron = true,
        dimmed = done,
        modifier = Modifier.homeRowsLink("${L("Open@needs")}: $names · $kind") { nav.push(route) },
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pets.size > 1) {
                PetStack(pets = pets.map { Triple(it.name ?: "", it.species, it.photoUrl) }, size = 40.dp)
            } else {
                PetAvatar(name = pets.firstOrNull()?.name ?: kind, species = pets.firstOrNull()?.species, photoURL = pets.firstOrNull()?.photoUrl, size = 40.dp)
            }
            Column(Modifier.weight(1f)) {
                HomeRowsTitle(if (names.isEmpty()) "—" else names, " · $kind")
                if (details.isNotEmpty()) {
                    Text(details, Modifier.padding(top = 2.dp), style = Theme.text(12.8), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chips.isNotEmpty()) {
                    FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) { chips.forEach { chip -> key(chip.id) { chip.view() } } }
                }
            }
        }
    }
}

// MARK: - Grooming

private val homeGroomRowOrder: Map<String, Int> = mapOf("ready" to 0, "styling" to 1, "bath" to 2, "waiting" to 3, "scheduled" to 4, "done" to 5)

/**
 * The day's grooms by stage (web home-hub.tsx `GroomingSection`). Under Todos only the stage counts (the grooms
 * are agenda rows already); under Estética, every groom not handed over yet, with its next step.
 */
@Composable
fun HomeGroomingSection(data: HomeGroomingToday?, full: Boolean, modifier: Modifier = Modifier) {
    val actions = LocalHomeActions.current

    val simple = actions?.simpleStages ?: false

    val items = (data?.items ?: emptyList()).filter { SvcGroomStage.all.contains(it.groomStage ?: "") }

    val open = items.filter { it.groomStage != "done" }.sortedWith(
        compareBy<HomeGroomItem>({ homeGroomRowOrder[it.groomStage ?: ""] ?: 9 }, { it.startsAt ?: "" })
    )

    /** Counted from the rows shown, so the pills and the list never disagree; simple stages fold cut & dry into the bath. */
    val counts: List<Pair<String, Int>> = SvcGroomStage.visible(simple).mapNotNull { stage ->
        val count = items.count { SvcGroomStage.shown(it.groomStage ?: "", simple) == stage }
        if (count > 0) stage to count else null
    }

    val openBoard: (() -> Unit)? = actions?.let { a -> ({ a.route = ProviderRoute.section(ProviderSection.grooming) }) }

    if (data == null) {
        if (full) LoadingState(modifier = modifier)
    } else if (items.isEmpty()) {
        if (full) {
            HomeRowsEmptyLink(
                icon = "scissors",
                title = L("Nothing in grooming today"),
                message = L("The day's grooms show up here, stage by stage."),
                link = L("Open grooming"),
                route = ProviderRoute.section(ProviderSection.grooming),
                modifier = modifier,
            )
        }
    } else {
        HomeStrip(
            storageKey = "grooming", title = HomeActivity.grooming.label, count = items.size, dot = HomeActivity.grooming.accent,
            linkTitle = L("Open grooming"), link = openBoard, modifier = modifier,
        ) {
            val stagesLabel = L("Stages of today's grooms")
            FlowLayout(
                spacing = 6.dp,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = stagesLabel },
            ) {
                counts.forEach { (stage, count) ->
                    key(stage) {
                        val color = ProviderState.groom(stage).color
                        Chip(text = SvcGroomStage.label(stage, simple), tone = Chip.Tone.custom(color), dot = color, count = count)
                    }
                }
            }
            if (full) {
                RowDivider()
                if (open.isEmpty()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("check-check", 16.dp, tint = Theme.blue)
                        Text(L("Every groom today has been handed over."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
                    }
                } else {
                    RowList(items = open.take(HomeCap.rows)) { item ->
                        HomeGroomRow(item = item)
                    }
                    if (open.size > HomeCap.rows) {
                        HomeStripMore(more = open.size - HomeCap.rows, route = ProviderRoute.section(ProviderSection.grooming))
                    }
                }
            }
        }
    }
}

/** One groom on Home, laid out like a visit row: when, who, its stage, and the one step it takes next. */
@Composable
private fun HomeGroomRow(item: HomeGroomItem) {
    val workspace = Workspace.shared
    val actions = LocalHomeActions.current

    val phone = LocalSizeClass.current != SizeClass.regular
    val stage = item.groomStage ?: "scheduled"
    val simple = actions?.simpleStages ?: false
    val busy = actions?.isBusy(item.appointmentId) ?: false
    val canMove = workspace.can(Permission.appointmentsComplete) && actions != null
    val tone = ProviderState.groom(SvcGroomStage.shown(stage, simple)).color
    val channels = listOfNotNull(
        if (item.client?.phone.nonEmpty != null) "whatsapp" else null,
        if (item.client?.onPawMiaw == true) "app" else null,
    )

    fun openVisit() {
        actions?.route = ProviderRoute.appointment(item.appointmentId)
    }

    val buttons: @Composable (HomeActions) -> Unit = { actions ->
        val next = SvcGroomStage.next(stage, simple)
        val pet = item.pet?.name ?: "—"
        FlowLayout(spacing = 6.dp, modifier = if (phone) Modifier else Modifier.width(IntrinsicSize.Max)) {
            if (stage == "scheduled" && workspace.can(Permission.appointmentsCheckIn)) {
                PMButton(L("Check in@visit"), systemImage = "arrow-right-to-line", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busy) {
                    actions.checkIn(item.appointmentId)
                }
            }
            if (canMove && next != null) {
                PMButtonStyle(ButtonVariant.outline, ButtonSize.compact, onClick = { actions.moveGroom(item.appointmentId, pet, next) }, enabled = !busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(ProviderState.groom(next).color, CircleShape))
                        Text(SvcGroomStage.moveLabel(next, stage, simple))
                    }
                }
            }
            if (stage == "ready" && canMove && channels.isNotEmpty()) {
                if (channels.size == 1) {
                    val channel = channels.first()
                    PMButton(
                        L("Notify"), systemImage = if (channel == "whatsapp") "message-circle" else "smartphone",
                        variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = busy,
                    ) {
                        actions.tellReady(item.appointmentId, pet, item.client?.name, channel)
                    }
                } else {
                    AppMenu(
                        label = { PMButtonStyle(ButtonVariant.outline, ButtonSize.compact) { PMButtonContent(title = L("Notify"), icon = "bell-ring") } },
                        enabled = !busy,
                    ) {
                        this.item("WhatsApp", icon = "message-circle") { actions.tellReady(item.appointmentId, pet, item.client?.name, "whatsapp") }
                        this.item("PawMiaw", icon = "smartphone") { actions.tellReady(item.appointmentId, pet, item.client?.name, "app") }
                    }
                }
            }
            if (stage == "ready") {
                // Handing the pet over is finishing the visit: its page has the report, the finish and the bill.
                PMButton(L("Hand over and charge"), systemImage = "hand-coins", size = ButtonSize.compact) { openVisit() }
            }
        }
    }

    val openLabel = L("Open@needs")
    EdgeRow(
        edge = tone,
        time = DateText.time(item.startsAt),
        modifier = Modifier
            .plainButton { openVisit() }
            .semantics { customActions = listOf(CustomAccessibilityAction(openLabel) { openVisit(); true }) },
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PetAvatar(name = item.pet?.name, species = item.pet?.species, photoURL = item.pet?.photoUrl, size = 40.dp)
                // The details.
                Column(Modifier.weight(1f)) {
                    HomeRowsTitle(item.pet?.name ?: "—", " · " + (item.serviceLabel ?: ""))
                    val details = listOf(item.client?.name, item.groomer?.name?.let { LF("with %@", it) }, item.requestSummary)
                        .mapNotNull { it.nonEmpty }.joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(details, Modifier.padding(top = 2.dp), style = Theme.text(12.8), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
                        Chip(text = if (actions == null) HomeText.groomStage(stage) else SvcGroomStage.label(stage, simple), tone = Chip.Tone.custom(tone), dot = tone)
                        // Already told: so nobody messages the same family twice.
                        val told = item.readyNotifiedAt
                        if (stage == "ready" && told != null) {
                            Chip(text = LF("Notified %@", DateText.time(told)), tone = Chip.Tone.muted, icon = "check-check")
                        }
                    }
                }
                if (!phone && actions != null) buttons(actions)
            }
            if (phone && actions != null) buttons(actions)
        }
    }
}

// MARK: - Families on their way with an emergency

/** Families on their way with an emergency ("voy en camino", web hospital/arrivals-block.tsx), at the top of Home. */
@Composable
fun HomeArrivalsBlock(arrivals: List<HomeArrival>, canAct: Boolean, modifier: Modifier = Modifier) {
    Card(padding = 0.dp, tint = Theme.destructive, spacing = 0.dp, modifier = modifier) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Theme.cardRadius))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(icon = "siren", accent = Theme.destructive, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.onAccent)
                Text(L("On the way"), style = Theme.heading(18), color = Theme.inkStrong)
                CountBadge(count = arrivals.size)
                Spacer(Modifier.weight(1f))
            }
            RowDivider(color = Theme.destructive.copy(alpha = 0.25f))
            arrivals.forEachIndexed { index, arrival ->
                key(arrival.id) {
                    if (index > 0) RowDivider(color = Theme.destructive.copy(alpha = 0.18f))
                    HomeArrivalRow(arrival = arrival, canAct = canAct)
                }
            }
        }
    }
}

@Composable
private fun HomeArrivalRow(arrival: HomeArrival, canAct: Boolean) {
    val openURL = rememberOpenURL()
    val workspace = Workspace.shared
    val actions = LocalHomeActions.current
    var answering by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }

    val minutesAway: Int = DateText.parse(arrival.etaAt)?.let { eta ->
        homeRowsRounded(Duration.between(Instant.now(), eta).toMillis() / 60_000.0)
    } ?: (arrival.minutesAway ?: 0)

    /** "In 12 min", "Any minute now", "12 min past their ETA". */
    val etaText: String = when {
        minutesAway > 1 -> LF("In %d min", minutesAway)
        minutesAway >= -2 -> L("Any minute now")
        else -> LF("%d min past their ETA", abs(minutesAway))
    }

    val petName = arrival.pet?.name ?: L("A pet")
    val acknowledged = arrival.status == "acknowledged"
    val late = minutesAway < -2

    EdgeRow(edge = if (late) Theme.mango else (if (acknowledged) ProviderState.done.color else Theme.destructive)) {
        Column(Modifier.weight(1f).padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                PetAvatar(name = petName, species = arrival.pet?.species, photoURL = arrival.pet?.photoUrl, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(petName, style = Theme.heading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            arrival.client?.name ?: L("A family"),
                            if (arrival.linked == false) L("not a client yet") else null,
                            LF("ETA %@", DateText.time(arrival.etaAt)),
                        ).joinToString(" · "),
                        style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    FlowLayout(spacing = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
                        Chip(text = etaText, tone = if (late) Chip.Tone.warn else Chip.Tone.danger, icon = "car")
                        if (acknowledged) Chip(text = L("Answered"), tone = Chip.Tone.good, icon = "check-check")
                        if (arrival.afterHours == true) {
                            Chip(text = if (arrival.routedTo == "on_call") L("After hours · sent to on call") else L("After hours"), tone = Chip.Tone.info, icon = "moon")
                        }
                    }
                    val message = arrival.message.nonEmpty
                    if (message != null) {
                        Text("“$message”", Modifier.padding(top = 6.dp), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                    }
                    val reply = arrival.replyMessage.nonEmpty
                    if (acknowledged && reply != null) {
                        Text(
                            LF("You answered: “%@”", reply) + (arrival.acknowledgedByName?.let { " · $it" } ?: ""),
                            Modifier.padding(top = 4.dp), style = Theme.text(12), color = Theme.inkSoft,
                        )
                    }
                    FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 8.dp)) {
                        val phone = arrival.contactPhone.nonEmpty
                        val phoneURL = ContactLinks.phone(phone)
                        if (phone != null && phoneURL != null) {
                            PMButton(phone, systemImage = "phone", variant = ButtonVariant.outline, size = ButtonSize.header) { openURL(phoneURL) }
                        }
                        val card = arrival.emergencyCard?.url?.takeIf { runCatching { java.net.URI(it) }.isSuccess }
                        if (card != null) {
                            PMButton(L("Emergency card"), systemImage = "external-link", variant = ButtonVariant.outline, size = ButtonSize.header) { openURL(card) }
                        } else {
                            Text(
                                L("No emergency card shared"),
                                Modifier.heightIn(min = 36.dp).wrapContentHeight(Alignment.CenterVertically),
                                style = Theme.text(12), color = Theme.inkSoft,
                            )
                        }
                    }
                }
            }
            if (canAct) {
                FlowLayout(spacing = 8.dp, modifier = Modifier.padding(start = 60.dp)) {
                    if (!acknowledged) {
                        PMButton(L("We’re expecting you"), systemImage = "phone-call", variant = ButtonVariant.outline, size = ButtonSize.compact) { answering = true }
                    }
                    if (workspace.canAll(Permission.appointmentsCreate, Permission.clientsView) && actions != null) {
                        // "Ya llegaron": the walk-in opens with what is known, as an emergency.
                        PMButton(L("They’re here"), systemImage = "siren", size = ButtonSize.compact, isWorking = actions.isBusy(arrival.id)) { actions.arrived(arrival) }
                    }
                    PMButton(L("Dismiss"), systemImage = "x", variant = ButtonVariant.link, size = ButtonSize.slim, tint = Theme.inkStrong) { dismissing = true }
                }
            }
        }
    }
    if (answering) {
        AppSheet(onDismissRequest = { answering = false }) {
            HomeAcknowledgeSheet(arrival = arrival, petName = petName)
        }
    }
    if (dismissing) {
        AppSheet(onDismissRequest = { dismissing = false }) {
            ConfirmSheet(
                title = LF("Take %@ off Home?", petName),
                message = L("Use it when they went elsewhere or it was a mistake. The family isn’t told."),
                confirmTitle = L("Dismiss"),
                destructive = true,
                askReason = true,
                reasonRequired = false,
                cancelTitle = L("Keep it"),
            ) { reason ->
                API.send("POST", "/provider-hospital/arrivals/${arrival.id}/dismiss", mapOf("reason" to reason))
                Toast.show(L("Taken off Home."))
                announceDataChanged()
            }
        }
    }
}

@Composable
private fun HomeAcknowledgeSheet(arrival: HomeArrival, petName: String) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val reply = remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ProviderModal(
        title = L("“We’re expecting you”"),
        subtitle = L("The family hears it right away in their app."),
        systemImage = "phone-call",
        accent = ModalAccent.lime,
        isDirty = reply.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Send@arrival"),
        isWorking = sending,
        primaryIcon = "check-check",
        onPrimary = {
            sending = true
            scope.launch {
                try {
                    API.send("POST", "/provider-hospital/arrivals/${arrival.id}/acknowledge", mapOf("reply" to reply.value.nonEmpty))
                    Toast.show(L("The family knows you’re expecting them."))
                    announceDataChanged()
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    form.apply(e, known = mapOf("reply" to "reply"))
                } finally {
                    sending = false
                }
            }
        },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        PMTextField(
            L("A line for them (optional)"),
            text = reply,
            key = "reply",
            form = form,
            prompt = LF("e.g. Come in through the emergency door; keep %@ warm.", petName),
            multiline = true,
        )
    }
}

// MARK: - Android helpers

/** SwiftUI `Text(a).bold + Text(" · " + b)` on one line: the name in ink-strong bold 16, the rest in ink-soft 16. */
@Composable
private fun HomeRowsTitle(bold: String, rest: String) {
    Text(
        buildAnnotatedString {
            withStyle(Theme.text(16, FontWeight.Bold).toSpanStyle().copy(color = Theme.inkStrong)) { append(bold) }
            withStyle(Theme.text(16).toSpanStyle().copy(color = Theme.inkSoft)) { append(rest) }
        },
        style = Theme.text(16),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** An empty part of the board with the link to its page under the empty state (iOS `.padding(.top, -12).padding(.bottom, 24)`). */
@Composable
private fun HomeRowsEmptyLink(icon: String, title: String, message: String, link: String, route: ProviderRoute, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(systemImage = icon, title = title, message = message, accent = Theme.blue)
        PMButtonStyle(
            ButtonVariant.link, ButtonSize.slim,
            modifier = Modifier.homeRowsPullUp(12.dp).padding(bottom = 24.dp),
            onClick = { nav.push(route) },
        ) {
            PMButtonContent(title = link, trailingIcon = "chevron-right", variant = ButtonVariant.link, size = ButtonSize.slim)
        }
    }
}

/** SwiftUI `.padding(.top, -x)`: drawn `x` higher, its frame `x` shorter at the top. */
private fun Modifier.homeRowsPullUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
}

/**
 * SwiftUI `NavigationLink { row }.buttonStyle(.plain).accessibilityLabel(label)`: the row opens on a
 * tap and reads out only `label`.
 */
private fun Modifier.homeRowsLink(label: String, action: () -> Unit): Modifier =
    this
        .plainButton(onClick = action)
        .clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            onClick { action(); true }
        }

/** Swift's `.rounded()` (half away from zero). */
private fun homeRowsRounded(value: Double): Int = (if (value < 0) -Math.round(-value) else Math.round(value)).toInt()
