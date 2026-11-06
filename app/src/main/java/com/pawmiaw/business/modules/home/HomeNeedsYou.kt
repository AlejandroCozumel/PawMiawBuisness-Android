package com.pawmiaw.business.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate

//
//  HomeNeedsYou.kt (iOS HomeNeedsYou.swift)
//
//  "Te necesita" (web today-board.tsx `NeedsYou`): everything else that
//  needs someone today, a problem a day late first and in red. Late home
//  visits, grooms that need someone, a register left open, visits left open
//  on earlier days, "Por cobrar", notes to sign, "Reservas por confirmar"
//  (the server's count), families' requests to move or cancel, follow-ups
//  due, missing paperwork, "Te deben", "Excepciones de ayer" (money level 3),
//  stays, the hospital, grooms due back and stock. Blocks are the kit's
//  `NeedsCard`; pages are its `LinkLine`.
//
//  The Swift view is a struct whose `hasAnything` the board reads before drawing it: here a
//  class with the same inputs, `hasAnything`, and `body()` that draws it.
//

class HomeNeedsYou(
    val today: HomeToday,
    val model: HomeModel,
    val permissions: HomePermissions,
    val grooms: List<HomeGroomItem>,
    val lateHome: List<HomeHomeVisit>,
    val open: (ProviderRoute) -> Unit,
) {
    private val needs: HomeNeeds? get() = today.needsYou
    private val currency: String? get() = today.currency

    /** Whether anything here needs someone: the same things its blocks show. */
    val hasAnything: Boolean get() = blocks.isNotEmpty()

    private class Block(val id: String, val view: @Composable () -> Unit)

    private fun line(icon: String, title: String, hint: String?, attention: Boolean, route: ProviderRoute): @Composable () -> Unit = {
        val nav = LocalNavigator.current
        LinkLine(title = title, icon = icon, hint = hint, attention = attention, modifier = Modifier.plainButton { nav.push(route) })
    }

    private val blocks: List<Block>
        get() {
            val list = mutableListOf<Block>()
            fun push(id: String, view: @Composable () -> Unit) { list.add(Block(id, view)) }
            fun lead(id: String, view: @Composable () -> Unit) { list.add(0, Block(id, view)) }
            val needs = needs
            val permissions = permissions
            val open = open

            // A home visit nobody has reached past its time leads, in red: someone calls the family or goes.
            if (lateHome.isNotEmpty()) {
                val names = lateHome.flatMap { visit -> (visit.pets ?: emptyList()).mapNotNull { it.name } }
                push(
                    "home-late",
                    line(
                        "house",
                        if (lateHome.size == 1) LF("Home visit running late: %@", names.joinToString(", ")) else LF("%d home visits running late", lateHome.size),
                        hint = if (lateHome.size == 1) LF("%d min past its time, nobody there yet", lateHome[0].lateMinutes ?: 0) else names.take(3).joinToString(", "),
                        attention = true,
                        route = ProviderRoute.section(ProviderSection.homeVisits),
                    ),
                )
            }

            // Grooms that need someone lead, in red, until each pet goes home or its family is told.
            if (grooms.isNotEmpty()) {
                fun kinds(kind: String): Int = grooms.count { it.attention == kind }
                val hint = listOfNotNull(
                    if (kinds("carried_over") > 0) LF("%d here since an earlier day", kinds("carried_over")) else null,
                    if (kinds("not_told") > 0) LF("%d ready, family not told", kinds("not_told")) else null,
                    if (kinds("late_pickup") > 0) LF("%d not picked up", kinds("late_pickup")) else null,
                ).joinToString(" · ")
                push(
                    "grooming",
                    line(
                        "scissors",
                        if (grooms.size == 1) LF("Grooming: %@ needs you", grooms[0].pet?.name ?: "—") else LF("Grooming: %d need you", grooms.size),
                        hint = hint,
                        attention = true,
                        route = ProviderRoute.section(ProviderSection.grooming),
                    ),
                )
            }

            // A register shift left open from an earlier day comes first: today's cash must not go into it.
            for (shift in (today.registers ?: emptyList()).filter { it.stale == true }) {
                push("shift-${shift.sessionId}") { HomeOpenShiftCard(shift = shift, canClose = permissions.register) }
            }

            // Visits nobody closed stay above today's work, in amber: nothing forces closing them, so they must be hard to miss.
            val unfinished = needs?.unfinished
            if (unfinished != null && (unfinished.count ?: 0) > 0) {
                val items = unfinished.items ?: emptyList()
                push("unfinished") {
                    NeedsCard(
                        title = L("Left open on earlier days"), icon = "triangle-alert", count = unfinished.count,
                        hint = L("Record them as done, mark them no-show or cancel them so they stop counting as open."),
                        tone = Theme.mango, attention = NeedsCard.Attention.amber,
                    ) {
                        items.forEachIndexed { index, item ->
                            key(item.id) { HomeUnfinishedRow(item = item, canRecord = permissions.start, first = index == 0, open = open) }
                        }
                    }
                }
            }

            // "Sí fuimos": families who say the pet did come to a visit recorded as a no-show (docs/family-money.md §5).
            // Amber like the visits left open: the business decides — forgive the fee or dismiss — and nothing changes by itself.
            val disputes = needs?.disputes
            if (disputes != null && (disputes.count ?: 0) > 0) {
                val items = disputes.items ?: emptyList()
                val more = (disputes.count ?: 0) - items.size
                val canDismiss = permissions.edit || permissions.forgive
                push("disputes") {
                    NeedsCard(
                        title = L("They say they came"), icon = "message-circle-question-mark", count = disputes.count,
                        hint = L("Recorded as no-shows. You decide: forgive the fee or dismiss it."),
                        tone = Theme.mango, attention = NeedsCard.Attention.amber,
                        more = maxOf(0, more),
                    ) {
                        items.forEachIndexed { index, item ->
                            key(item.id) { HomeDisputeRow(item = item, canForgive = permissions.forgive, canDismiss = canDismiss, first = index == 0, open = open) }
                        }
                    }
                }
            }

            // "Por cobrar": red while one is from an earlier day — it leads, like any other day-late problem — amber for today's.
            val charging = needs?.charging
            if (charging != null && (charging.count ?: 0) > 0) {
                val late = (charging.earlierDays?.count ?: 0) > 0
                val card: @Composable () -> Unit = {
                    HomeChargingBlock(charging = charging, late = late, canCharge = permissions.takePayment, canOpen = permissions.billView, open = open)
                }
                if (late) lead("charging", card) else push("charging", card)
            }

            val notes = needs?.notesToSign
            if (notes != null && (notes.count ?: 0) > 0) {
                val items = notes.items ?: emptyList()
                push("notes") {
                    NeedsCard(title = L("Notes to sign"), icon = "notebook-pen", count = notes.count, tone = Theme.mango) {
                        items.forEachIndexed { index, item ->
                            key(item.id) { HomeNoteToSignRow(item = item, canSign = permissions.sign, first = index == 0, open = open) }
                        }
                    }
                }
            }

            // Online bookings waiting: amber, their time is held and released if nobody answers.
            val online = needs?.onlineRequests
            if (online != null && (online.count ?: 0) > 0) {
                val items = online.items ?: emptyList()
                val more = (online.count ?: 0) - items.size
                push("online") {
                    NeedsCard(
                        title = L("Bookings to confirm"), icon = "globe", count = online.count,
                        hint = L("Booked online. The time is held until you answer."), tone = Theme.mango, attention = NeedsCard.Attention.amber,
                        more = maxOf(0, more), seeAll = { open(ProviderRoute.section(ProviderSection.appointments)) },
                    ) {
                        items.forEachIndexed { index, item ->
                            key(item.id) { HomeOnlineNeedRow(item = item, canAnswer = permissions.create, first = index == 0, open = open) }
                        }
                    }
                }
            }

            val requests = needs?.requests
            if (requests != null && (requests.count ?: 0) > 0) {
                val items = requests.items ?: emptyList()
                push("requests") {
                    NeedsCard(title = L("Requests to answer"), icon = "message-circle", count = requests.count, tone = Theme.aqua) {
                        items.forEachIndexed { index, item ->
                            key(item.id) {
                                NeedsRow(
                                    title = "${item.petName ?: "—"} · ${HomeText.confirmationWord(item.kind)}",
                                    hint = listOf(DateText.dayTime(item.startsAt), item.preferredTime).mapNotNull { it.nonEmpty }.joinToString(" · "),
                                    first = index == 0,
                                    modifier = Modifier.plainButton { open(ProviderRoute.appointment(item.id)) },
                                )
                            }
                        }
                    }
                }
            }

            val followUps = needs?.followUps
            if (followUps != null && (followUps.dueToday ?: 0) + (followUps.overdue ?: 0) > 0) {
                val later = followUps.later ?: 0
                val items = followUps.items ?: emptyList()
                push("followups") {
                    NeedsCard(
                        title = L("Follow-ups due"), icon = "calendar-clock",
                        count = (followUps.dueToday ?: 0) + (followUps.overdue ?: 0), tone = Theme.blue,
                    ) {
                        items.forEachIndexed { index, item ->
                            key(item.id) { HomeFollowUpRow(item = item, canRemind = permissions.followUps, first = index == 0) }
                        }
                        HomeNeedsFooterLink(
                            title = L("All follow-ups") + (if (later > 0) " · " + LF("%d later", later) else ""),
                            route = ProviderRoute.section(ProviderSection.notifications),
                        )
                    }
                }
            }

            // The job itself, on Records › To complete — not the notices about it.
            val documentation = needs?.followUps?.documentation
            if (documentation != null && documentation > 0) {
                push(
                    "docs",
                    line(
                        "clipboard-pen", LF("%d finished visits still missing their note or report", documentation), hint = null, attention = false,
                        route = ProviderRoute.section(ProviderSection.records),
                    ),
                )
            }

            // "Te deben": what clients owe, every bill out of draft with a balance.
            val money = needs?.money
            if (money != null && (money.owed ?: 0) > 0) {
                val hint = listOfNotNull(
                    if ((money.atDesk?.amount ?: 0) > 0) LF("%@ at the desk, not charged", Money.format(money.atDesk?.amount, currency)) else null,
                    if ((money.overdue ?: 0) > 0) LF("%@ overdue", Money.format(money.overdue, currency)) else null,
                    if ((money.promisesDue ?: 0) > 0) LF("%d promises due today", money.promisesDue ?: 0) else null,
                    if ((money.promisesBroken ?: 0) > 0) LF("%d broken", money.promisesBroken ?: 0) else null,
                ).joinToString(" · ")
                push(
                    "money",
                    line(
                        "hand-coins", LF("%@ owed to you", Money.format(money.owed, currency)), hint = hint.nonEmpty,
                        attention = (money.overdue ?: 0) > 0, route = ProviderRoute.section(ProviderSection.finance),
                    ),
                )
            }

            // "Excepciones de ayer (N)": what business finances read after a day away.
            val exceptions = today.exceptionsYesterday
            if (exceptions != null && exceptions > 0 && Workspace.shared.moneyLevel >= MoneyLevel.finances) {
                push(
                    "exceptions",
                    line(
                        "triangle-alert", LF("Exceptions yesterday (%d)", exceptions), hint = L("Refunds, discounts and other moves to look at."),
                        attention = false, route = ProviderRoute.section(ProviderSection.finance),
                    ),
                )
            }

            val stays = needs?.stays
            if (stays != null && (stays.arriving ?: 0) + (stays.leaving ?: 0) + (stays.inHouse ?: 0) + (stays.lateArrivals ?: 0) > 0) {
                val lateOut = stays.lateDepartures ?: 0
                val lateIn = stays.lateArrivals ?: 0
                // A pet still here past its day, or one that never came, leads the list, in red, until it is dealt with.
                val late = lateOut + lateIn > 0
                val hint = listOfNotNull(
                    if (lateOut > 0) LF("%d still here past check-out", lateOut) else null,
                    if (lateIn > 0) LF("%d late arrivals", lateIn) else null,
                    if ((stays.arriving ?: 0) > 0) LF("%d arriving", stays.arriving ?: 0) else null,
                    if ((stays.leaving ?: 0) > 0) LF("%d leaving", stays.leaving ?: 0) else null,
                ).joinToString(" · ")
                val card = line("bed-double", LF("Stays: %d in house", stays.inHouse ?: 0), hint = hint.nonEmpty, attention = late, route = ProviderRoute.section(ProviderSection.stays))
                if (late) lead("stays", card) else push("stays", card)
            }

            // The hospital: patients in, and the doses needing someone now. A dose missed or late leads, in red.
            val hospital = today.hospital
            if (hospital != null && (hospital.admitted ?: 0) + (hospital.pending ?: 0) > 0) {
                val missed = hospital.missed ?: 0
                val lateDoses = hospital.late ?: 0
                val urgent = missed + lateDoses > 0
                val hint = listOfNotNull(
                    if (missed > 0) (if (missed == 1) L("1 missed@dose") else LF("%d missed@dose", missed)) else null,
                    if (lateDoses > 0) (if (lateDoses == 1) L("1 late@dose") else LF("%d late@dose", lateDoses)) else null,
                    if ((hospital.due ?: 0) > 0) LF("%d due@dose", hospital.due ?: 0) else null,
                    if ((hospital.pending ?: 0) > 0) LF("%d waiting for admission", hospital.pending ?: 0) else null,
                ).joinToString(" · ")
                val admitted = hospital.admitted ?: 0
                val card = line(
                    "hospital", if (admitted == 1) L("Hospital: 1 in") else LF("Hospital: %d in", admitted), hint = hint.nonEmpty,
                    attention = urgent, route = ProviderRoute.section(ProviderSection.hospital),
                )
                if (urgent) lead("hospital", card) else push("hospital", card)
            }

            val due = needs?.due
            if (due != null && (due.overdue ?: 0) + (due.thisWeek ?: 0) > 0) {
                val hint = listOfNotNull(
                    if ((due.overdue ?: 0) > 0) LF("Overdue: %d", due.overdue ?: 0) else null,
                    if ((due.thisWeek ?: 0) > 0) LF("This week: %d", due.thisWeek ?: 0) else null,
                ).joinToString(" · ")
                push("due", line("scissors", L("Due for grooming"), hint = hint, attention = (due.overdue ?: 0) > 0, route = ProviderRoute.section(ProviderSection.due)))
            }

            val stock = needs?.stock
            if (stock != null && (stock.low ?: 0) + (stock.out ?: 0) + (stock.expiring ?: 0) + (stock.expired ?: 0) > 0) {
                val hint = listOfNotNull(
                    if ((stock.out ?: 0) > 0) LF("%d out", stock.out ?: 0) else null,
                    if ((stock.low ?: 0) > 0) LF("%d low", stock.low ?: 0) else null,
                    if ((stock.expired ?: 0) > 0) LF("%d expired", stock.expired ?: 0) else null,
                    if ((stock.expiring ?: 0) > 0) LF("%d expiring", stock.expiring ?: 0) else null,
                ).joinToString(" · ")
                push(
                    "stock",
                    line(
                        "boxes", L("Stock needs you"), hint = hint, attention = (stock.out ?: 0) > 0 || (stock.expired ?: 0) > 0,
                        route = ProviderRoute.section(ProviderSection.inventory),
                    ),
                )
            }
            return list
        }

    @Composable
    fun body(modifier: Modifier = Modifier) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("Needs you@home"), style = Theme.heading(18), color = Theme.inkStrong, modifier = Modifier.padding(horizontal = 4.dp))
            for (block in blocks) {
                key(block.id) { block.view() }
            }
        }
    }
}

/** "since 8:02", "since yesterday 8:02", "since Mon 3 Nov 8:02" (register/open-shift-card.tsx). */
object HomeOpenSince {
    fun text(iso: String?): String {
        val date = DateText.parse(iso) ?: return ""
        val time = DateText.time(date)
        val zone = Workspace.shared.timeZone
        val day = date.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        if (day == today) return LF("since %@", time)
        if (day == today.minusDays(1)) return LF("since yesterday %@", time)
        return LF("since %@ %@", DateText.day(date), time)
    }
}

/** A block's footer link (web: "Ver todo · 3 más", "Todos los seguimientos"): 12 bold blue. */
@Composable
private fun HomeNeedsFooterLink(title: String, route: ProviderRoute) {
    val nav = LocalNavigator.current
    Text(
        title, style = Theme.text(12, FontWeight.Bold), color = Theme.blue,
        modifier = Modifier.padding(top = 4.dp).plainButton { nav.push(route) },
    )
}

/**
 * A register shift still open from an earlier day (web register/open-shift-card.tsx): "Caja principal abierta por
 * Ana desde ayer 8:02", with the close right here for whoever may close it. It never closes on its own.
 */
@Composable
private fun HomeOpenShiftCard(shift: HomeRegisterShift, canClose: Boolean) {
    val actions = LocalHomeActions.current
    val nav = LocalNavigator.current
    NeedsCard(
        title = LF("%@ open by %@ %@", shift.registerName ?: L("The register"), shift.openedByName ?: "—", HomeOpenSince.text(shift.openedAt)),
        icon = "wallet", hint = L("Count the cash and close it before today’s cash goes in."), tone = Theme.mango, attention = NeedsCard.Attention.amber,
    ) {
        FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 4.dp)) {
            if (canClose && actions != null) {
                PMButton(L("Count and close"), systemImage = "lock", size = ButtonSize.compact, isWorking = actions.isBusy("shift-${shift.sessionId}")) {
                    actions.closeShift(shift.sessionId)
                }
            }
            PMButtonStyle(
                ButtonVariant.link, ButtonSize.slim, modifier = Modifier.heightIn(min = 40.dp),
                onClick = { nav.push(ProviderRoute.section(ProviderSection.store)) },
            ) {
                PMButtonContent(title = L("Open the register"), variant = ButtonVariant.link, size = ButtonSize.slim)
            }
        }
    }
}

/**
 * "Por cobrar" on Home: the front desk's whole queue with amounts and "Ver todo" (Cuentas › Por cobrar), or what
 * this person prices (no amounts). Each row says what it was and opens its bill.
 */
@Composable
fun HomeChargingBlock(
    charging: HomeChargingSummary,
    late: Boolean,
    canCharge: Boolean,
    canOpen: Boolean,
    open: (ProviderRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val desk = charging.scope == "desk"
    val hint = run {
        val currency = (charging.items ?: emptyList()).firstNotNullOfOrNull { it.currency }
        val oldest = charging.earlierDays?.oldestDay?.let { DateText.parse(it) }?.let { DateText.date(it) }
        listOfNotNull(
            if (desk && charging.amount != null && currency != null) LF("%@ in all", Money.format(charging.amount, currency)) else null,
            if (late && oldest != null) LF("%d from earlier days, since %@", charging.earlierDays?.count ?: 0, oldest) else null,
            if (desk) null else L("Price them so the desk can charge them."),
        ).joinToString(" · ")
    }

    fun `when`(item: HomeChargingItem): String =
        if (item.earlierDay == true) HomeText.dayLabel(item.day) else "${L("Today")} ${DateText.time(item.at)}"

    val items = charging.items ?: emptyList()
    val more = (charging.count ?: 0) - items.size
    NeedsCard(
        title = if (desk) L("To charge") else L("Yours to bill"), icon = "receipt", count = charging.count, hint = hint.nonEmpty,
        tone = if (late) Theme.destructive else Theme.mango, attention = if (late) NeedsCard.Attention.red else NeedsCard.Attention.amber,
        more = if (desk) 0 else maxOf(0, more), modifier = modifier,
    ) {
        items.forEachIndexed { index, item ->
            key(item.id) {
                val amount = item.amount?.let { Money.format(it, item.currency) }
                val route = if (canOpen) HomeChargingBlockRules.route(item) else null
                NeedsRow(
                    title = "${(item.petNames ?: listOf(item.petName ?: "—")).joinToString(", ")} · ${HomeChargingBlockRules.what(item)}",
                    hint = listOfNotNull(
                        if (item.earlierDay == true) `when`(item) else null,
                        HomeChargingBlockRules.stage(item.state),
                        amount,
                        if (item.earlierDay == true) null else `when`(item),
                    ).joinToString(" · "),
                    first = index == 0,
                    modifier = Modifier.plainButton { if (route != null) open(route) },
                ) {
                    if (desk && canCharge && item.state == "ready" && route != null) {
                        PMButton(L("Charge"), systemImage = "hand-coins", variant = ButtonVariant.outline, size = ButtonSize.compact) { open(route) }
                    }
                }
            }
        }
        if (desk) {
            HomeNeedsFooterLink(
                title = L("See everything") + (if (more > 0) " · " + LF("%d more", more) else ""),
                route = ProviderRoute.section(ProviderSection.invoices),
            )
        }
    }
}

/** `HomeChargingBlock`'s static rules (iOS `HomeChargingBlock.route/what/stage`). */
object HomeChargingBlockRules {
    fun route(item: HomeChargingItem): ProviderRoute? {
        // A bill that exists opens in Cuentas; a visit without one opens the visit (its bill card).
        item.invoiceId?.let { return ProviderRoute.invoice(it) }
        val appointment = item.appointmentId
        if (item.source == "visit" && appointment != null) return ProviderRoute.appointment(appointment)
        val source = item.sourceId ?: return null
        return if (item.source == "hospital") ProviderRoute.hospitalization(source) else ProviderRoute.stay(source)
    }

    fun what(item: HomeChargingItem): String = when (item.source) {
        "visit" -> item.serviceLabel ?: ""
        "stay" -> L("Stay@charge")
        "hospital" -> L("Hospital")
        "home_stay" -> L("Home stay")
        else -> item.serviceLabel ?: ""
    }

    fun stage(state: String?): String = when (state) {
        "no_bill" -> HomeText.billStageLabel("no_bill")
        "needs_price" -> HomeText.billStageLabel("needs_price")
        "unsent" -> HomeText.billStageLabel("building")
        else -> HomeText.billStageLabel("ready")
    }
}

/** A note to sign: it opens its visit; whoever signs signs it here. */
@Composable
private fun HomeNoteToSignRow(item: HomeNoteToSign, canSign: Boolean, first: Boolean, open: (ProviderRoute) -> Unit) {
    var signing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val late = run {
        val updated = DateText.parse(item.updatedAt) ?: return@run false
        val zone = Workspace.shared.timeZone
        updated < LocalDate.now(zone).atStartOfDay(zone).toInstant()
    }

    fun sign() {
        signing = true
        scope.launch {
            try {
                API.send("POST", "/provider-records/${item.recordId}/sign", emptyMap())
                Toast.show(L("Signed."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                signing = false
            }
        }
    }

    NeedsRow(
        title = "${item.petName ?: "—"} · ${item.title ?: ""}",
        hint = listOfNotNull(item.author?.let { LF("by %@", it) }, if (late) L("overdue") else null).joinToString(" · "),
        first = first,
        modifier = Modifier.plainButton {
            open(item.appointmentId?.let { ProviderRoute.appointment(it) } ?: ProviderRoute.record(item.recordId))
        },
    ) {
        if (canSign) {
            PMButton(L("Sign"), systemImage = "badge-check", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = signing) { sign() }
        }
    }
}

@Serializable
private data class HomeNeedsReminded(val link: String? = null)

/** A follow-up due: remind the family in PawMiaw or on WhatsApp. */
@Composable
private fun HomeFollowUpRow(item: HomeFollowUp, canRemind: Boolean, first: Boolean) {
    val openURL = rememberOpenURL()
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun remind(channel: String) {
        sending = true
        scope.launch {
            try {
                val result: HomeNeedsReminded = API.post(
                    "/provider-follow-ups/${item.id}/remind",
                    mapOf("channel" to channel, "locale" to LocalizationManager.currentLanguage.rawValue),
                )
                val link = result.link
                if (channel == "whatsapp" && link != null) {
                    openURL(link)
                    Toast.show(L("WhatsApp is open with the message."))
                } else {
                    Toast.show(L("Reminder sent in PawMiaw."))
                }
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e)
            } finally {
                sending = false
            }
        }
    }

    NeedsRow(
        title = "${item.petName ?: "—"} · ${item.title ?: ""}",
        hint = (item.clientName ?: "") + (if (item.overdue == true) " · " + L("overdue") else ""),
        first = first,
    ) {
        if (canRemind) {
            IconCircleButton(systemImage = "smartphone", label = L("Remind in PawMiaw"), tint = Theme.blue, enabled = !sending) { remind("app") }
            IconCircleButton(systemImage = "message-circle", label = "WhatsApp", tint = Theme.blue, enabled = !sending) { remind("whatsapp") }
        }
    }
}

/** A visit left open since an earlier day: "Sí vino" records it; "Abrir" opens it to mark no-show or cancel. */
@Composable
private fun HomeUnfinishedRow(item: HomeUnfinishedVisit, canRecord: Boolean, first: Boolean, open: (ProviderRoute) -> Unit) {
    val actions = LocalHomeActions.current
    // What the family said when asked the next morning.
    val answer: String? = when (item.familyAttendance) {
        "attended" -> L("family says they went")
        "not_attended" -> L("family says they didn’t go")
        else -> null
    }
    NeedsRow(
        title = "${item.petName ?: "—"} · ${item.serviceLabel ?: ""}",
        hint = listOfNotNull(DateText.date(item.startsAt), HomeText.leftOpenWord(item.status), answer).joinToString(" · "),
        first = first,
        modifier = Modifier.plainButton { open(ProviderRoute.appointment(item.id)) },
    ) {
        if (canRecord && actions != null) {
            PMButton(
                L("They came"), systemImage = "badge-check", variant = ButtonVariant.outline, size = ButtonSize.compact,
                isWorking = actions.isBusy(item.id),
            ) { actions.recordPast(item.id) }
        }
        PMButton(L("Open@needs"), variant = ButtonVariant.outline, size = ButtonSize.compact) { open(ProviderRoute.appointment(item.id)) }
    }
}

/**
 * A family's "Sí fuimos" after a no-show: the visit opens on tap; "Perdonar el cargo" while its fee
 * bill owes, "Descartar" takes it off Home (the answer stays on the visit).
 */
@Composable
private fun HomeDisputeRow(item: HomeDispute, canForgive: Boolean, canDismiss: Boolean, first: Boolean, open: (ProviderRoute) -> Unit) {
    val actions = LocalHomeActions.current
    val feeBalance = item.fee?.balance
    val fee = if (feeBalance != null && feeBalance > 0) Money.format(feeBalance, item.fee?.currency) else null
    val feeId = item.feeInvoiceId
    NeedsRow(
        title = "${item.petName ?: "—"} · ${item.serviceLabel ?: ""}",
        hint = listOfNotNull(
            listOfNotNull(DateText.day(DateText.parse(item.startsAt)), item.clientName.nonEmpty, fee?.let { LF("fee %@", it) }).joinToString(" · "),
            item.disputedAt?.let { LF("said so %@", DateText.day(DateText.parse(it))) },
        ).joinToString("\n"),
        first = first,
        modifier = Modifier.plainButton { open(ProviderRoute.appointment(item.appointmentId)) },
    ) {
        if (canForgive && feeId != null && actions != null) {
            PMButton(L("Forgive the fee"), systemImage = "hand-heart", variant = ButtonVariant.outline, size = ButtonSize.compact) { actions.forgiveFee(feeId) }
        }
        if (canDismiss && actions != null) {
            PMButton(L("Dismiss"), variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = actions.isBusy("dispute-${item.appointmentId}")) {
                actions.dismissDispute(item.appointmentId)
            }
        }
        if (actions == null) PMButton(L("Open@needs"), variant = ButtonVariant.outline, size = ButtonSize.compact) { open(ProviderRoute.appointment(item.appointmentId)) }
    }
}

/**
 * A family's online booking: answered over Home (Confirmar, Cambiar hora y confirmar, Rechazar).
 * Their ask to move one reads "Cambio de hora: {now} → {asked for}" (Aceptar, Rechazar).
 */
@Composable
private fun HomeOnlineNeedRow(item: HomeOnlineRequest, canAnswer: Boolean, first: Boolean, open: (ProviderRoute) -> Unit) {
    val actions = LocalHomeActions.current

    fun answer() {
        if (actions != null) actions.answerOnline(item.appointmentId) else open(ProviderRoute.appointment(item.appointmentId))
    }

    // "Se libera en 3 horas" on its own line: on a phone it would be cut off at the end of the hint.
    val release = HomeText.releaseIn(item.releasesAt)?.let { if (item.isReschedule) LF("Expires %@@ask", it) else LF("Released %@", it) }
    val `when` = if (item.isReschedule && item.startsAt != null) {
        SchedOnlineText.change(item.startsAt ?: "", item.rescheduleProposal?.startsAt)
    } else {
        DateText.dayTime(item.startsAt)
    }
    NeedsRow(
        title = "${(item.pets ?: emptyList()).mapNotNull { it.name }.joinToString(", ")} · ${item.serviceLabel ?: ""}",
        hint = listOfNotNull(listOfNotNull(`when`, item.client?.name).joinToString(" · "), release).joinToString("\n"),
        first = first,
        modifier = Modifier.plainButton { answer() },
    ) {
        if (canAnswer) {
            PMButton(L("Answer"), variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = actions?.isBusy(item.appointmentId) ?: false) { answer() }
        }
    }
}
