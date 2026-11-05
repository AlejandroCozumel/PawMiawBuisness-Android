package com.pawmiaw.business.money

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

//
//  BillingScreen.kt (iOS BillingScreen.swift)
//
//  Suscripción (web: routes/provider.billing.tsx, subscription/*): the
//  business's PawMiaw plan — the plan, its state, the trial, the period end,
//  users and locations, what every plan includes, the next charge, the card
//  and the receipts as Stripe has them. Plans are bought and changed on the
//  web (Stripe); no in-app purchase (the backend refuses Apple/Google
//  purchases for businesses). The plan manager or business finances read it.
//
//  Drawn as the web's `SubscriptionOverview`: the module header in the plan's
//  colour with its four figures, the notice when the plan needs someone, the
//  plan card, the payment card, the receipts, the workspaces and the history.
//  Every action that changes the plan opens the web's Subscription page.
//

private data class CashPlanInfo(
    val plan: String,
    val name: String,
    val badge: String,
    val seats: Int,
    val locations: Int,
    val monthly: Int,
    val annual: Int,
    val summary: String,
    val highlights: List<String>,
    /** The plan's colour (web `PLAN_ACCENT`): starter butter, business sky, clinic lavender. */
    val accent: Color,
) {
    companion object {
        fun of(plan: String?): CashPlanInfo? = when (plan) {
            "basic" -> CashPlanInfo(
                plan = "basic", name = L("Starter"), badge = L("Solo care"), seats = 1, locations = 1, monthly = 19, annual = 190, summary = L("1 location · 1 user"),
                highlights = listOf(L("One person, one location"), L("The whole workspace, no team to set up"), L("Move up to Business when your team grows")), accent = Theme.butter,
            )
            "pro" -> CashPlanInfo(
                plan = "pro", name = L("Business"), badge = L("Care team"), seats = 10, locations = 1, monthly = 99, annual = 990, summary = L("1 location · 10 users · extra locations"),
                highlights = listOf(L("Roles and permissions for each person"), L("Extra locations, up to 3 in total"), L("Extra users in packs of 5")), accent = Hue.sky.color,
            )
            "enterprise" -> CashPlanInfo(
                plan = "enterprise", name = L("Clinic"), badge = L("Veterinary"), seats = 30, locations = 3, monthly = 249, annual = 2490, summary = L("3 locations · 30 users"),
                highlights = listOf(L("Chart, hospital and prescriptions across branches"), L("Extra locations, up to 10 in total"), L("Extra users in packs of 10")), accent = Theme.lavender,
            )
            else -> null
        }

        val everyPlan: List<String>
            get() = listOf(
                L("Schedule and booking requests"), L("Clients, pets and records"), L("Clinical chart and prescriptions"), L("Invoices, payments and store"),
                L("Boarding, daycare and hospital"), L("Reminders and follow-ups"), L("Links to family profiles"),
            )
    }
}

/** A plan state's words and colour (web `statusMeta`). */
private data class CashPlanStatus(val label: String, val state: ProviderState, val short: String, val meaning: String)

/** The notice when the plan needs someone. */
private data class CashPlanNotice(val tone: NoticeCard.Tone, val title: String, val body: String, val action: String?)

/** What the page knows about billing, read by its cards (iOS: the screen's state and computed properties). */
private class CashBillingContext(
    val billing: CashBillingSummary?,
    val billingError: String?,
    val members: Int?,
    val invitations: Int?,
    val canManage: Boolean,
    val onStripe: Boolean,
    val store: Boolean,
    val switching: String?,
    val openWeb: () -> Unit,
    val retry: () -> Unit,
    val switchTo: (ProviderWorkspace) -> Unit,
) {
    /** Our team set the plan up by hand: no card behind it. */
    val byHand: Boolean get() = onStripe && billing != null && !billing.available
    val seatsUsed: Int? get() = members?.let { m -> invitations?.let { m + it } }
}

private const val billingSupportEmail = "contact@pawmiaw.com"

@Composable
fun BillingScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    var billing by remember { mutableStateOf<CashBillingSummary?>(null) }
    var billingError by remember { mutableStateOf<String?>(null) }
    var billingLoading by remember { mutableStateOf(false) }
    var members by remember { mutableStateOf<Int?>(null) }
    var invitations by remember { mutableStateOf<Int?>(null) }
    var switching by remember { mutableStateOf<String?>(null) }

    val current = workspace.current
    val canView = (current?.canManagePlan ?: false) || workspace.moneyLevel >= MoneyLevel.finances

    suspend fun load() {
        val open = workspace.current
        val allowed = (open?.canManagePlan ?: false) || workspace.moneyLevel >= MoneyLevel.finances
        if (!allowed || open == null) return
        workspace.refresh()
        if (workspace.current?.billingProvider == "stripe") {
            billingLoading = true
            try {
                billing = API.get("/provider-subscriptions/summary")
                billingError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                billingError = errorText(failure)
            } finally {
                billingLoading = false
            }
        }
        if (workspace.can(Permission.staffView)) {
            supervisorScope {
                val memberList = async {
                    try {
                        API.get<List<CashMember>>("/provider-workspaces/${open.clinicId}/members")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        null
                    }
                }
                val invitationList = async {
                    try {
                        API.get<List<CashInvitation>>("/provider-workspaces/${open.clinicId}/invitations")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        null
                    }
                }
                val people = memberList.await()
                val waiting = invitationList.await()
                if (people != null && waiting != null) {
                    val now = Instant.now()
                    members = people.size
                    invitations = waiting.count { it.status == "pending" && (DateText.parse(it.expiresAt) ?: Instant.MIN) > now }
                }
            }
        }
    }

    val context = CashBillingContext(
        billing = billing,
        billingError = billingError,
        members = members,
        invitations = invitations,
        canManage = current?.canManagePlan ?: workspace.can(Permission.billingManage),
        onStripe = current?.billingProvider == "stripe",
        store = current?.billingProvider == "apple_iap" || current?.billingProvider == "google_play",
        switching = switching,
        // Changing the plan, the card, cancelling or keeping it happen on the web's Subscription page.
        openWeb = { openURL(APIConfig.webURL("provider/billing").toString()) },
        retry = { scope.launch { load() } },
        switchTo = { entry ->
            switching = entry.clinicId
            scope.launch {
                try {
                    workspace.choose(entry)
                } finally {
                    switching = null
                }
            }
        },
    )

    providerPage(L("Subscription"), root = true, modifier = modifier) {
        PageScroll(maxWidth = 900.dp, refresh = { load() }) {
            if (!canView) {
                NoAccessState(message = L("Ask the workspace owner for business finances to see the plan."))
            } else if (current != null) {
                BillingContent(current, context)
            } else {
                ModuleHeader(icon = "circle-dollar-sign", title = L("Subscription"), subtitle = L("Your PawMiaw plan, what it includes and when it renews."), storageKey = "billing")
                EmptyState(systemImage = "building-2", title = L("No workspace is open"), message = L("Open a workspace to see its plan."), panel = true)
            }
        }
        ReloadsOnScope { load() }
    }
}

// MARK: Content

@Composable
private fun BillingContent(w: ProviderWorkspace, ctx: CashBillingContext) {
    val workspace = Workspace.shared
    val plan = CashPlanInfo.of(w.plan)
    val status = billingStatusMeta(w.billingStatus)
    BillingHeader(w, plan, status, ctx)
    billingNotice(w, ctx)?.let { notice ->
        NoticeCard(
            tone = notice.tone,
            title = notice.title,
            message = notice.body,
            actionTitle = if (ctx.canManage) notice.action else null,
            action = if (ctx.canManage && notice.action != null) ctx.openWeb else null,
        )
    }
    NoticeCard(
        tone = NoticeCard.Tone.info,
        title = L("Plans are chosen and paid on the web"),
        message = if (ctx.canManage) {
            L("Change the plan, update the card or cancel on pawmiaw.com: Stripe’s secure page handles the payment.")
        } else {
            L("Only the workspace owner can change or cancel the plan. You can see everything here.")
        },
    )
    BillingPlanCard(w, plan, ctx)
    BillingPayment(w, status, ctx)
    if (ctx.onStripe && !ctx.byHand) BillingReceipts(ctx)
    if (workspace.workspaces.size > 1) BillingWorkspaces(w, ctx)
    AuditHistoryCard(
        entityType = "clinic_billing",
        entityId = w.organizationId,
        title = L("Subscription history"),
        description = L("Every change to the plan, its status, renewal dates and users."),
    )
}

// MARK: Header (web: CircleDollarSign in the plan's colour, four figures)

@Composable
private fun BillingHeader(w: ProviderWorkspace, plan: CashPlanInfo?, status: CashPlanStatus, ctx: CashBillingContext) {
    val next = billingNextEvent(w, ctx)
    val limit = w.seatLimit ?: plan?.seats
    val used = ctx.seatsUsed
    val full = used?.let { u -> limit?.let { u >= it } ?: false } ?: false
    val paymentProblem = w.billingStatus == "grace_period" || w.billingStatus == "past_due"
    val nextHint: String? = next.second?.let { date ->
        listOfNotNull(ctx.billing?.nextCharge?.let { cashMoney(it.amount, it.currency.uppercase()) }, billingRelativeDays(date)).mapNotNull { it.nonEmpty }.joinToString(" · ")
    }
    ModuleHeader(
        icon = "circle-dollar-sign",
        title = L("Subscription"),
        subtitle = LF("%@: your PawMiaw plan, what it includes and when it renews.", w.name),
        accent = plan?.accent ?: Theme.blue,
        stats = listOf(
            ModuleStat("plan", label = L("Plan"), value = plan?.name ?: L("None yet"), hint = plan?.badge ?: L("Choose one to start"), icon = "badge-check", accent = plan?.accent ?: Theme.blue),
            ModuleStat(
                "status", label = L("Status"), value = status.label, hint = billingTrialHint(w) ?: status.short,
                icon = if (paymentProblem) "triangle-alert" else "sparkles", accent = status.state.color, attention = paymentProblem,
            ),
            ModuleStat("next", label = next.first, value = next.second?.let { billingShortDate(it) } ?: "—", hint = nextHint, icon = "calendar-clock", accent = Theme.mango),
            ModuleStat(
                "seats", label = L("Users"),
                value = used?.let { u -> limit?.let { "$u / $it" } } ?: limit?.toString() ?: "—",
                hint = if (used != null) (if (full) L("All taken") else L("In use, invitations included")) else L("One per person on the team"),
                icon = "users-round", accent = Theme.lavender, attention = full && (limit ?: 0) > 1,
            ),
        ),
        storageKey = "billing",
        actions = if (ctx.canManage && ctx.onStripe && !ctx.byHand) ({
            PMButton(L("Manage plan on the web"), systemImage = "external-link", variant = ButtonVariant.outline) { ctx.openWeb() }
        }) else null,
    )
}

// MARK: The plan

@Composable
private fun BillingPlanCard(w: ProviderWorkspace, plan: CashPlanInfo?, ctx: CashBillingContext) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val accent = plan?.accent ?: Theme.blue
    val seats = w.seatLimit ?: plan?.seats
    val locations = w.locationLimit ?: plan?.locations
    val cycle = ctx.billing?.billingCycle
    val soft = Theme.inkSoft
    ListCard {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(0f to accent.copy(alpha = 0.26f), 0.6f to accent.copy(alpha = 0.08f), 1f to accent.copy(alpha = 0f)),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text(L("Your plan").uppercase(), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.5), color = Theme.inkSoft)
            FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 4.dp)) {
                Text(plan?.name ?: L("No plan yet"), style = Theme.heading(36), color = Theme.inkStrong)
                if (plan != null) {
                    Box(Modifier.height(28.dp).background(Theme.surface.copy(alpha = 0.75f), CapsuleShape).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(plan.badge, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    }
                }
            }
            if (plan != null) {
                val price = when (cycle) {
                    "annual" -> LF("%@ a year", billingListPrice(plan.annual))
                    "monthly" -> LF("%@ a month", billingListPrice(plan.monthly))
                    else -> LF("%@ a month", billingListPrice(plan.monthly)) + " · " + LF("or %@ a year", billingListPrice(plan.annual))
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(price) }
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = soft)) { append(" · ${L("list price, before add-ons")}") }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    style = Theme.text(14),
                    color = Theme.inkStrong,
                )
            }
        }
        if (seats != null || locations != null) {
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BillingEyebrow(L("It holds"))
                FlowLayout(spacing = 8.dp) {
                    if (locations != null) BillingLimitChip("map-pin", LP(locations, "%d location", "%d locations"))
                    if (seats != null) BillingLimitChip("users-round", LP(seats, "%d user", "%d users"))
                }
                if (seats != null) BillingSeatMeter(seats, ctx)
                if (workspace.can(Permission.staffView)) {
                    CompositionLocalProvider(LocalContentColor provides Theme.blue) {
                        Row(
                            Modifier.heightIn(min = 44.dp).plainButton { nav.push(ProviderRoute.section(ProviderSection.staff)) },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("users-round", 16.dp)
                            Text(L("Open staff"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                        }
                    }
                }
            }
        }
        if (plan != null) {
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BillingChecklist(L("Built for"), plan.highlights, bold = true)
                BillingChecklist(L("Included with every plan"), CashPlanInfo.everyPlan, bold = false)
            }
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            PMButton(if (ctx.canManage && ctx.onStripe) L("Change plan") else L("Compare plans"), systemImage = "repeat", variant = ButtonVariant.outline, size = ButtonSize.compact) { ctx.openWeb() }
        }
    }
}

@Composable
private fun BillingEyebrow(text: String) {
    Text(text.uppercase(), style = brandText(12, FontWeight.Bold).tracking(1.68), color = Theme.blue)
}

@Composable
private fun BillingLimitChip(icon: String, text: String) {
    Row(
        Modifier.height(32.dp).background(Theme.surfaceMuted, CapsuleShape).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 16.dp, tint = Theme.blue)
        Text(text, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
    }
}

@Composable
private fun BillingSeatMeter(limit: Int, ctx: CashBillingContext) {
    val used = ctx.seatsUsed
    val members = ctx.members
    val invitations = ctx.invitations
    if (used != null && members != null && invitations != null) {
        val full = used >= limit
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowLayout(spacing = 8.dp) {
                Text(LF("%d of %d users in use", used, limit), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                Text(
                    LP(members, "%d person", "%d people") + (if (invitations > 0) " · " + LP(invitations, "%d invitation waiting", "%d invitations waiting") else ""),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
            }
            ProgressBar(
                value = min(used, limit).toDouble() / max(1, limit).toDouble(),
                height = 10.dp,
                track = Theme.surfaceMuted,
                fill = if (full && limit > 1) Theme.mango else Theme.blue,
                label = L("Users in use"),
            )
            Text(
                if (full && limit == 1) {
                    L("This plan is for one person. To bring your team in, move to Business.")
                } else if (full) {
                    L("Every user is taken. To add someone, move to a bigger plan or free one.")
                } else {
                    L("Each person on the team and each invitation still waiting counts as a user.")
                },
                style = Theme.text(14, if (full && limit > 1) FontWeight.SemiBold else FontWeight.Normal),
                color = if (full && limit > 1) Theme.inkStrong else Theme.inkSoft,
            )
        }
    } else {
        Text(L("Each person on the team and each invitation still waiting counts as a user."), modifier = Modifier.padding(top = 8.dp), style = Theme.text(14), color = Theme.inkSoft)
    }
}

@Composable
private fun BillingChecklist(title: String, lines: List<String>, bold: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BillingEyebrow(title)
        for (line in lines) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph("check", 16.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp))
                Text(line, style = Theme.text(14, if (bold) FontWeight.SemiBold else FontWeight.Normal), color = Theme.inkStrong)
            }
        }
    }
}

// MARK: Payment

@Composable
private fun BillingPayment(w: ProviderWorkspace, status: CashPlanStatus, ctx: CashBillingContext) {
    val openURL = rememberOpenURL()
    val next = billingNextEvent(w, ctx)
    val paymentProblem = w.billingStatus == "grace_period" || w.billingStatus == "past_due"
    val cancellable = ctx.canManage && ctx.onStripe && !ctx.byHand && (w.billingStatus ?: "") in listOf("trialing", "active", "grace_period", "past_due")
    val soft = Theme.inkSoft
    ListCard {
        BillingPanelTitle(
            "credit-card",
            L("Payment"),
            if (ctx.byHand) L("Our team set this plan up for you. There is no card on file and nothing to pay here.")
            else if (ctx.onStripe) L("Payments are handled by Stripe. PawMiaw never sees your card.") else null,
        )
        RowDivider()
        BillingDetailRow("sparkles", status.state.color, L("Status")) {
            StatusChip(text = status.label, state = status.state)
            BillingRowDetail(status.meaning)
        }
        RowDivider()
        BillingDetailRow("calendar-clock", Theme.mango, next.first) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(next.second?.let { billingLongDate(it) } ?: "—") }
                    next.second?.let { withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = soft)) { append(" · ${billingRelativeDays(it)}") } }
                },
                style = Theme.text(16),
                color = Theme.inkStrong,
            )
            ctx.billing?.nextCharge?.let { charge ->
                Text(LF("%@ will be charged", cashMoney(charge.amount, charge.currency.uppercase())), style = Theme.text(16, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
            }
            BillingRowDetail(next.third)
        }
        val provider = w.billingProvider
        if (provider != null) {
            RowDivider()
            BillingDetailRow(if (ctx.store) "smartphone" else "circle-dollar-sign", Theme.aqua, if (ctx.byHand) L("Set up by") else L("Billed through")) {
                BillingRowValue(
                    if (ctx.byHand) L("The PawMiaw team")
                    else if (provider == "stripe") "Stripe" else if (provider == "apple_iap") "App Store" else "Google Play",
                )
                BillingRowDetail(
                    if (ctx.byHand) L("Not paid with a card. To change or end it, write to us.")
                    else if (provider == "apple_iap") L("Bought in the App Store. Change or cancel it on the iPhone or iPad: Settings → your name → Subscriptions.")
                    else if (provider == "google_play") L("Bought on Google Play. Change or cancel it in the Play Store: Profile → Payments & subscriptions.")
                    else L("The card and cancelling are handled on Stripe’s secure page."),
                )
            }
        }
        if (ctx.onStripe && !ctx.byHand) {
            RowDivider()
            BillingCardRow(paymentProblem, ctx)
        }
        val failed = ctx.billingError
        if (failed != null && ctx.billing == null) {
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp).semantics { stateDescription = failed },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Could not load the receipts."), modifier = Modifier.weight(1f), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { ctx.retry() }
            }
        }
        if (cancellable) {
            BillingFooterStrip {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (w.billingStatus == "trialing") L("Not for you? Cancel before the trial ends and pay nothing.") else L("No longer need it? You keep access until the paid time ends."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                    PMButton(
                        if (w.billingStatus == "trialing") L("Cancel trial") else L("Cancel plan"),
                        variant = ButtonVariant.destructive,
                        size = ButtonSize.compact,
                        fullWidth = true,
                    ) { ctx.openWeb() }
                }
            }
        } else if (ctx.byHand && ctx.canManage) {
            val blue = Theme.blue
            BillingFooterStrip {
                CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                    Row(
                        Modifier.plainButton { openURL("mailto:$billingSupportEmail") },
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Glyph("mail", 16.dp, modifier = Modifier.padding(top = 2.dp))
                        Text(
                            buildAnnotatedString {
                                append(L("To change or end this plan, write to"))
                                append(" ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = blue)) { append(billingSupportEmail) }
                            },
                            style = Theme.text(14),
                            color = Theme.inkSoft,
                        )
                    }
                }
            }
        } else if (!ctx.canManage && w.billingProvider != null) {
            BillingFooterStrip {
                CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Glyph("lock", 16.dp, modifier = Modifier.padding(top = 2.dp))
                        Text(
                            if (ctx.onStripe) L("Only the workspace owner can change the plan, update the card or cancel. You can see everything here.")
                            else L("Only the workspace owner can change or cancel the plan. You can see everything here."),
                            style = Theme.text(14),
                            color = Theme.inkSoft,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BillingCardRow(paymentProblem: Boolean, ctx: CashBillingContext) {
    val card = ctx.billing?.card
    val month = card?.expMonth
    val year = card?.expYear
    val expiry = if (month != null && year != null) String.format(Locale.US, "%02d/%02d", month, year % 100) else null
    val expired = if (month != null && year != null) {
        // The first day of the month after the card's last one, on the business's calendar.
        val firstAfter = runCatching { LocalDate.of(year, 1, 1).plusMonths(month.toLong()).atStartOfDay(Workspace.shared.timeZone).toInstant() }.getOrNull()
        firstAfter?.let { it <= Instant.now() } ?: false
    } else {
        false
    }
    val name = card?.last4?.let { last4 ->
        listOfNotNull(card.brand?.split(" ")?.joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) } }, "···· $last4").joinToString(" ")
    }
    val tail = if (name != null) expiry?.let { " · " + if (expired) LF("Expired %@", it) else LF("Expires %@", it) } ?: "" else ""
    val tailInk = if (expired) Theme.destructive else Theme.inkSoft
    BillingDetailRow(
        "credit-card", Theme.lime, L("Payment method"),
        action = if (ctx.canManage) ({
            PMButton(L("Update card"), systemImage = "credit-card", variant = if (paymentProblem) ButtonVariant.primary else ButtonVariant.outline, size = ButtonSize.compact) { ctx.openWeb() }
        }) else null,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name ?: L("Card saved in Stripe")) }
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = tailInk)) { append(tail) }
            },
            style = Theme.text(16),
            color = Theme.inkStrong,
        )
        BillingRowDetail(
            if (paymentProblem) L("The last charge failed on this card.")
            else if (expired) L("This card has expired. Update it before the next charge.")
            else L("The card Stripe charges for each renewal."),
        )
    }
}

// MARK: Receipts

@Composable
private fun BillingReceipts(ctx: CashBillingContext) {
    ListCard {
        BillingPanelTitle("receipt-text", L("Receipts"), L("What PawMiaw has charged for this plan. Open one or download it as a PDF."), accent = Hue.sky.color)
        RowDivider()
        val billing = ctx.billing
        if (billing != null) {
            if (billing.receipts.isEmpty()) {
                Text(
                    if (billing.reachable) L("No receipts yet. The first one arrives with the first charge.") else L("Stripe did not answer just now, so the receipts can’t be shown. Try again in a moment."),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            } else {
                billing.receipts.forEachIndexed { index, receipt ->
                    if (index > 0) RowDivider()
                    BillingReceiptRow(receipt)
                }
                BillingFooterStrip {
                    Text(
                        LP(billing.receipts.size, "%d receipt", "%d receipts, the most recent first") + (if (billing.reachable) "" else " · " + L("Stripe did not answer everything; some may be missing.")),
                        style = Theme.text(12, FontWeight.SemiBold),
                        color = Theme.inkSoft,
                    )
                }
            }
        } else if (ctx.billingError != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Could not load the receipts."), modifier = Modifier.weight(1f), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { ctx.retry() }
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Theme.inkSoft, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun BillingReceiptRow(receipt: CashBillingSummary.Receipt) {
    val openURL = rememberOpenURL()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(billingLongDate(receipt.date), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                Text(receipt.number ?: L("Receipt"), style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(cashMoney(receipt.amount, receipt.currency.uppercase()), style = Theme.text(16, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                StatusChip(text = billingReceiptStatus(receipt.status), state = billingReceiptState(receipt.status))
            }
        }
        val url = receipt.url.nonEmpty
        val pdf = receipt.pdfUrl.nonEmpty
        if (url != null || pdf != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (url != null) {
                    PMButton(
                        if (receipt.status == "open") L("Pay") else L("Open@link"),
                        systemImage = "external-link",
                        variant = if (receipt.status == "open") ButtonVariant.primary else ButtonVariant.outline,
                        size = ButtonSize.compact,
                        fullWidth = true,
                        modifier = Modifier.weight(1f),
                    ) { openURL(url) }
                }
                if (pdf != null) {
                    PMButton(L("PDF"), systemImage = "download", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true, modifier = Modifier.weight(1f)) { openURL(pdf) }
                }
            }
        }
    }
}

// MARK: Workspaces

@Composable
private fun BillingWorkspaces(w: ProviderWorkspace, ctx: CashBillingContext) {
    val workspace = Workspace.shared
    ListCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("Your workspaces"), style = Theme.heading(20), color = Theme.inkStrong)
                Text(L("Each one has its own plan. Switching changes which one opens on this browser."), style = Theme.text(14), color = Theme.inkSoft)
            }
            Box(
                Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).background(Theme.blue, CapsuleShape).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${workspace.workspaces.size}", style = Theme.text(14, FontWeight.Bold), color = Theme.onBlue)
            }
        }
        for (entry in workspace.workspaces) {
            RowDivider()
            val entryPlan = CashPlanInfo.of(entry.plan)
            val entryStatus = billingStatusMeta(entry.billingStatus)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(Theme.wash(entryPlan?.accent ?: Theme.blue, 55.0), CircleShape), contentAlignment = Alignment.Center) {
                        Text(entry.name.trim().take(1).uppercase(), style = Theme.heading(18), color = Theme.inkOnBright)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(entry.name, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${WorkspaceRole.label(entry.role)} · ${entryPlan?.name ?: L("No plan yet")}", style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StatusChip(text = entryStatus.label, state = entryStatus.state)
                }
                if (entry.clinicId == w.clinicId) {
                    CompositionLocalProvider(LocalContentColor provides Theme.blue) {
                        Row(Modifier.height(40.dp).padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("check", 16.dp)
                            Text(L("Open now"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                        }
                    }
                } else {
                    PMButton(
                        L("Switch to it"),
                        systemImage = "repeat",
                        variant = ButtonVariant.outline,
                        size = ButtonSize.compact,
                        isWorking = ctx.switching == entry.clinicId,
                        modifier = Modifier.padding(start = 52.dp),
                        enabled = ctx.switching == null,
                    ) { ctx.switchTo(entry) }
                }
            }
        }
    }
}

// MARK: Pieces of the cards

/** A card's title (web `PanelTitle`): a 40 badge, the title in Fredoka 20, one line. */
@Composable
private fun BillingPanelTitle(icon: String, title: String, hint: String?, accent: Color = Theme.aqua) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon = icon, accent = accent, size = 40.dp, iconSize = 20.dp, ink = IconBadge.Ink.strong)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme.heading(20), color = Theme.inkStrong)
            if (hint != null) Text(hint, style = Theme.text(14), color = Theme.inkSoft)
        }
    }
}

/** One fact about billing (web `DetailRow`): a 32 badge, the label as an eyebrow, the value; the action under it. */
@Composable
private fun BillingDetailRow(icon: String, accent: Color, label: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon = icon, accent = accent, size = 32.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label.uppercase(), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.31), color = Theme.inkSoft)
                content()
            }
        }
        if (action != null) Box(Modifier.padding(start = 44.dp)) { action() }
    }
}

@Composable
private fun BillingRowValue(text: String) {
    Text(text, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
}

@Composable
private fun BillingRowDetail(text: String) {
    Text(text, style = Theme.text(14), color = Theme.inkSoft)
}

/** The muted strip at a card's foot (web: the cancel row, the receipts count). */
@Composable
private fun BillingFooterStrip(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        RowDivider()
        Box(Modifier.fillMaxWidth().background(Theme.surfaceMuted).padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

// MARK: Words

private fun billingStatusMeta(status: String?): CashPlanStatus = when (status) {
    "trialing" -> CashPlanStatus(L("Free trial"), ProviderState.upcoming, L("Nothing charged yet"), L("Everything is open. Nothing has been charged yet."))
    "active" -> CashPlanStatus(L("Active"), ProviderState.done, L("Renews on its own"), L("Everything is open and the plan renews on its own."))
    "grace_period" -> CashPlanStatus(L("Payment issue"), ProviderState.waiting, L("The card needs updating"), L("The last charge failed. The workspace stays open while it is retried."))
    "past_due" -> CashPlanStatus(L("Payment failed"), ProviderState.problem, L("The card needs updating"), L("The last charge failed. Update the card to keep the workspace open."))
    "canceled" -> CashPlanStatus(L("Canceled@plan"), ProviderState.closed, L("Won’t renew"), L("It will not renew. The team keeps access until the paid time ends."))
    "expired" -> CashPlanStatus(L("Ended"), ProviderState.closed, L("Choose a plan to reopen"), L("The plan ended. Choose one again to reopen the workspace."))
    else -> CashPlanStatus(L("No plan"), ProviderState.closed, L("Choose a plan to start"), L("Choose a plan to open the provider tools."))
}

/** The next event's label, its day and what it means. */
private fun billingNextEvent(w: ProviderWorkspace, ctx: CashBillingContext): Triple<String, String?, String> = when (w.billingStatus) {
    "trialing" -> if (ctx.byHand) {
        Triple(L("Free days end"), w.trialEndsAt ?: w.currentPeriodEndsAt, L("Nothing is charged. After this day the workspace closes until a plan is chosen; nothing is deleted."))
    } else {
        Triple(
            L("First charge"),
            w.trialEndsAt ?: w.currentPeriodEndsAt,
            if (ctx.store) L("When the free days end, the store charges the plan.") else L("When the free days end, Stripe charges the card you added."),
        )
    }
    "active" -> Triple(L("Renews"), w.currentPeriodEndsAt, L("The plan renews and is charged on this day."))
    "grace_period", "past_due" -> Triple(L("Payment due"), w.currentPeriodEndsAt, L("Update the card so the next retry goes through."))
    "canceled" -> Triple(L("Access ends"), w.currentPeriodEndsAt, L("No more charges. The team can use the workspace until this day."))
    "expired" -> Triple(L("Ended"), w.currentPeriodEndsAt, L("The workspace is closed until a plan is chosen again."))
    else -> Triple(L("Next charge"), null, L("Starts when a plan is chosen."))
}

private fun billingTrialHint(w: ProviderWorkspace): String? {
    if (w.billingStatus != "trialing") return null
    val end = DateText.parse(w.trialEndsAt) ?: return null
    val zone = Workspace.shared.timeZone
    val days = max(0, ChronoUnit.DAYS.between(LocalDate.now(zone), end.atZone(zone).toLocalDate()).toInt())
    return LP(days, "%d day left", "%d days left")
}

private fun billingListPrice(amount: Int): String = "$${NumberFormat.getIntegerInstance(Locale.getDefault()).format(amount)} USD"

/** "9 de octubre de 2026". */
private fun billingLongDate(iso: String?): String {
    val date = DateText.parse(iso) ?: return "—"
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(appLocale).withZone(ZoneId.systemDefault()).format(date)
}

/** "9 oct" this year, "9 oct 2027" another. */
private fun billingShortDate(iso: String?): String {
    val date = DateText.parse(iso) ?: return "—"
    val zone = ZoneId.systemDefault()
    val sameYear = date.atZone(zone).year == LocalDate.now(zone).year
    return DateText.format(date, if (sameYear) "dMMM" else "dMMMyyyy", zone)
}

/** "en 14 días", "mañana", "hace 3 días". */
private fun billingRelativeDays(iso: String?): String {
    val date = DateText.parse(iso) ?: return ""
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), date.atZone(zone).toLocalDate()).toInt()
    val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(appLocale))
    val named: String? = when (days) {
        0 -> formatter.format(RelativeDateTimeFormatter.Direction.THIS, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        1 -> formatter.format(RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        -1 -> formatter.format(RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        2 -> formatter.format(RelativeDateTimeFormatter.Direction.NEXT_2, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        -2 -> formatter.format(RelativeDateTimeFormatter.Direction.LAST_2, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        else -> null
    }
    if (!named.isNullOrEmpty()) return named
    return if (days >= 0) {
        formatter.format(days.toDouble(), RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    } else {
        formatter.format((-days).toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    }
}

private fun billingReceiptStatus(status: String): String = when (status) {
    "paid" -> L("Paid@status")
    "open" -> L("To pay")
    "void" -> L("Voided")
    "uncollectible" -> L("Not collected")
    "draft" -> L("Draft")
    else -> status
}

private fun billingReceiptState(status: String): ProviderState = when (status) {
    "paid" -> ProviderState.done
    "open" -> ProviderState.waiting
    "uncollectible" -> ProviderState.problem
    "draft" -> ProviderState.upcoming
    else -> ProviderState.closed
}

private fun billingNotice(w: ProviderWorkspace, ctx: CashBillingContext): CashPlanNotice? = when (w.billingStatus) {
    null -> CashPlanNotice(
        NoticeCard.Tone.info,
        L("This workspace has no plan yet"),
        if (ctx.canManage) L("Choose a plan to open the provider tools. New workspaces start with a free trial.")
        else L("The workspace owner needs to choose a plan before the team can use the provider tools."),
        L("Choose a plan"),
    )
    "trialing" -> {
        val endsAt = w.trialEndsAt ?: w.currentPeriodEndsAt
        CashPlanNotice(
            NoticeCard.Tone.info,
            endsAt?.let { LF("Free trial until %@", billingLongDate(it)) } ?: L("Free trial"),
            if (ctx.byHand) L("Nothing has been charged, and there is no card on file: our team set this plan up for you.")
            else if (ctx.store) L("Nothing has been charged yet. When the trial ends the store charges the plan.")
            else L("Nothing has been charged yet."),
            null,
        )
    }
    "grace_period", "past_due" -> CashPlanNotice(
        NoticeCard.Tone.warning,
        L("The last payment didn’t go through"),
        if (ctx.store) L("Update the payment method in the store where the plan was bought so your team keeps access.")
        else if (ctx.canManage) L("The workspace stays open while Stripe tries again. Update the card so your team keeps access.")
        else L("The workspace stays open while Stripe tries again. Ask the workspace owner to update the card."),
        if (ctx.onStripe) L("Update card") else null,
    )
    "canceled", "expired" -> {
        val endsAt = w.currentPeriodEndsAt
        val ended = (DateText.parse(endsAt) ?: Instant.MIN) <= Instant.now() || w.billingStatus == "expired"
        CashPlanNotice(
            NoticeCard.Tone.warning,
            if (ended || endsAt == null) L("This plan has ended") else LF("Your plan ends on %@", billingLongDate(endsAt)),
            if (ended) L("Nothing was deleted. Choose a plan again to reopen the workspace for your team.")
            else if (ctx.canManage) L("It won’t renew. Until then everything stays open; after that the workspace closes, and nothing is deleted. Changed your mind? Keep your plan and it renews as before.")
            else L("It won’t renew. Until then everything stays open; after that the workspace closes, and nothing is deleted. Only the workspace owner can keep the plan."),
            if (ended) L("Choose a plan") else if (ctx.onStripe) L("Keep my plan") else null,
        )
    }
    else -> null
}
