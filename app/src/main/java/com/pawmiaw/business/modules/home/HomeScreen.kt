package com.pawmiaw.business.home

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle

//
//  HomeScreen.kt (iOS HomeScreen.swift)
//
//  Inicio (web routes/provider.index.tsx): one place's day. Until the
//  business is set up it says so (the setup is on the web); with the plan
//  paused it says that; a role that sees nothing of the day is told so.
//  Otherwise: the clock and setup cards, an invited member's reminders,
//  visits to relocate, families' requests, then the day's board
//  (components/app/today/today-board.tsx) and "Te necesita".
//

/** What a role may do on Home (the web's `TodayPermissions`). */
class HomePermissions(w: Workspace) {
    val checkIn: Boolean = w.can(Permission.appointmentsCheckIn)
    val start: Boolean = w.can(Permission.appointmentsComplete)
    val cancel: Boolean = w.can(Permission.appointmentsCancel)
    val edit: Boolean = w.can(Permission.appointmentsEdit)
    val create: Boolean = w.can(Permission.appointmentsCreate)
    val takePayment: Boolean = w.can(Permission.billingRecordPayment)
    val price: Boolean = w.can(Permission.billingEditInvoice)
    val billView: Boolean = w.canAny(Permission.billingView, Permission.financeDesk, Permission.financeView)
    val billSend: Boolean = w.canAny(Permission.billingEditInvoice, Permission.billingFinalizeInvoice)
    val reassign: Boolean = w.can(Permission.appointmentsAssignAnyone)
    val followUps: Boolean = w.can(Permission.followupsEdit)
    val clients: Boolean = w.can(Permission.clientsView)
    val sign: Boolean = w.can(Permission.recordsApprove)
    val register: Boolean = w.canAny(Permission.salesSell, Permission.billingRecordPayment, Permission.registerManage)
    /** billing.waive_visit: "Perdonar el cargo" on a no-show the family disputes (docs/family-money.md §5). */
    val forgive: Boolean = w.can(Permission.billingWaiveVisit)
}

/** Everything Home reads, refreshed together (the board every 30 s, as the web). */
@Stable
class HomeModel {
    var today: HomeToday? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var grooming: HomeGroomingToday? by mutableStateOf(null)
    var groomingFailed: Boolean by mutableStateOf(false)
    var homeVisits: HomeVisitsDay? by mutableStateOf(null)
    var checklist: HomeChecklist? by mutableStateOf(null)
    var reminders: List<HomeMemberReminder> by mutableStateOf(emptyList())
    var relocations: List<HomeRelocationVisit> by mutableStateOf(emptyList())
    var familyRequests: List<HomeFamilyRequest> by mutableStateOf(emptyList())
    /** The Home visits day list whole, for "Llegué" and "Terminar" under Domicilio. */
    var homeVisitsDay: SvcHomeVisitsDay? by mutableStateOf(null)
    /** An invited member's welcome, whole (shown over Home while `show`). */
    var welcome: HomeWelcomeState? by mutableStateOf(null)
    /** The grooming settings' "simple stages". */
    var simpleStages: Boolean by mutableStateOf(false)
    var loading: Boolean by mutableStateOf(false)
        private set

    suspend fun load(w: Workspace) {
        val current = w.current ?: return
        loading = true
        try {
            coroutineScope {
                val location = w.locationId
                val seesAnything = seesAnything(w)
                val setUp = current.setupCompletedAt != null
                val asksChecklist = setUp && w.can(Permission.workspaceEdit)
                val asksReminders = setUp && current.role != null && current.role != "owner"
                val asksRelocations = w.canSeeAllLocations && w.can(Permission.appointmentsView) && w.allLocations.any { it.status == "paused" }
                val asksRequests = setUp && w.canAny(Permission.staysView, Permission.appointmentsView)

                val todayCall = if (seesAnything && setUp) async {
                    runCatching { API.get<HomeToday>("/provider-today", mapOf("locationId" to location)) }
                } else null
                val checklistCall = if (asksChecklist) async { runCatching { API.get<HomeChecklist>("/provider-setup/checklist") }.getOrNull() } else null
                val remindersCall = if (asksReminders) async {
                    runCatching { API.data("GET", "/provider-member-onboarding", emptyMap(), null) }.getOrNull()
                } else null
                val relocationsCall = if (asksRelocations) async { runCatching { API.get<HomeRelocations>("/provider-locations/relocate") }.getOrNull() } else null
                val requestsCall = if (asksRequests) async {
                    runCatching { API.get<HomeFamilyRequestList>("/provider-requests", mapOf("scope" to "open", "locationId" to location)) }.getOrNull()
                } else null

                val answer = todayCall?.await()
                if (answer == null) {
                    today = null
                } else {
                    answer.onSuccess {
                        today = it
                        error = null
                    }.onFailure {
                        if (it !is CancellationException) error = errorText(it)
                    }
                }
                checklist = checklistCall?.await()
                val onboardingData = remindersCall?.await()
                val onboarding: HomeMemberOnboarding? = onboardingData?.let { runCatching { API.decode<HomeMemberOnboarding>(it) }.getOrNull() }
                reminders = if (onboarding?.applicable == true && onboarding.show != true) (onboarding.reminders ?: emptyList()) else emptyList()
                welcome = if (onboarding?.applicable == true) onboardingData?.let { runCatching { API.decode<HomeWelcomeState>(it) }.getOrNull() } else null
                relocations = relocationsCall?.await()?.visits ?: emptyList()
                familyRequests = requestsCall?.await()?.items ?: emptyList()

                // Grooming and home visits read their own day lists, as the web's board does.
                val modules = today?.hub?.modules ?: emptyList()
                if (modules.contains("grooming")) {
                    try {
                        grooming = API.get("/provider-grooming/today", mapOf("locationId" to location))
                        groomingFailed = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        groomingFailed = grooming == null
                    }
                } else {
                    grooming = null
                }
                if (modules.contains("home")) {
                    val data = runCatching { API.data("GET", "/provider-home-visits/today", mapOf("locationId" to location), null) }.getOrNull()
                    if (data != null) {
                        homeVisits = runCatching { API.decode<HomeVisitsDay>(data) }.getOrNull()
                        homeVisitsDay = runCatching { API.decode<SvcHomeVisitsDay>(data) }.getOrNull()
                    } else {
                        homeVisits = null
                        homeVisitsDay = null
                    }
                } else {
                    homeVisits = null
                    homeVisitsDay = null
                }
                // "Simple stages": asked only where a groom's stage is shown.
                if (modules.contains("grooming") || (today?.allVisits ?: emptyList()).any { it.groomStage != null }) {
                    val settings = runCatching { API.get<SchedGroomSettings>("/provider-grooming/settings") }.getOrNull()
                    simpleStages = settings?.simpleStages ?: simpleStages
                }
            }
        } finally {
            loading = false
        }
    }

    companion object {
        /** Home is the hub: visits, stays, the hospital, money and follow-ups — whichever this role may see. */
        fun seesAnything(w: Workspace): Boolean =
            w.canAny(Permission.appointmentsView, Permission.staysView, Permission.hospitalView, Permission.followupsView, Permission.recordsView) ||
                w.moneyLevel >= MoneyLevel.charging
    }
}

/** Inicio: the place's day, or what stands before it. */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    providerPage(L("Home"), root = true, modifier = modifier) {
        RequireLocation {
            HomeContent()
        }
    }
}

@Composable
private fun HomeContent() {
    val workspace = Workspace.shared
    val model = remember { HomeModel() }
    val dayModel = remember { HomeDayModel() }
    val scope = rememberCoroutineScope()
    val actions = remember { HomeActions(scope) }
    /** Another day than today (the workspace's): a list to get ready for, or the recap of one gone. */
    val otherDay = remember { mutableStateOf<String?>(null) }

    /** Booking takes creating visits and seeing clients. */
    val canBook = workspace.canAll(Permission.appointmentsCreate, Permission.clientsView)
    val current = workspace.current

    suspend fun refresh() {
        model.load(workspace)
        otherDay.value?.let { dayModel.load(it, workspace) }
    }

    HomeActionsHost(actions) {
        PageScroll(maxWidth = 1180.dp, refresh = { refresh() }) {
            if (current != null) {
                if (current.setupCompletedAt == null) {
                    HomeWebNotice(
                        systemImage = "wrench",
                        title = L("Finish setting up your workspace"),
                        message = L("Your business details, hours and services are set up on the web. Home opens here once they are done."),
                        actionTitle = L("Open setup on the web"),
                        path = "/provider",
                    )
                } else if (!HomeAccess.canUse(current)) {
                    HomeWebNotice(
                        systemImage = "lock",
                        title = HomeAccess.lockedTitle(current),
                        message = L("Provider tools are paused until billing is connected. Plans are chosen on the web."),
                        actionTitle = L("Open the plan on the web"),
                        path = "/provider/billing",
                        tone = NoticeCard.Tone.warning,
                    )
                } else if (!HomeModel.seesAnything(workspace)) {
                    EmptyState(
                        systemImage = "calendar-clock", title = L("Nothing on the Home for your role yet."),
                        message = L("Ask the workspace owner to let your role see visits, stays, the hospital, billing or follow-ups."),
                        accent = Theme.blue, panel = true,
                    )
                } else {
                    HomeContentBody(current, model, dayModel, actions, otherDay, canBook)
                }
            }
        }
    }
    ReloadsOnScope { model.load(workspace) }
    LaunchedEffect(workspace.scopeVersion) {
        // The board is a live screen at the front desk.
        while (isActive) {
            delay(30_000)
            if (!isActive) return@LaunchedEffect
            model.load(workspace)
        }
    }
    LaunchedEffect(model.simpleStages) { actions.simpleStages = model.simpleStages }
    // An invited member's welcome opens over Home on their first entry (and stays until they leave it).
    LaunchedEffect(model.welcome?.show) {
        val welcome = model.welcome
        if (welcome?.show == true && !actions.showsWelcome) actions.openWelcome(welcome)
    }
}

@Composable
private fun ColumnScope.HomeContentBody(
    current: ProviderWorkspace,
    model: HomeModel,
    dayModel: HomeDayModel,
    actions: HomeActions,
    otherDay: MutableState<String?>,
    canBook: Boolean,
) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    // A workspace set up before onboarding asked confirms its clock and money once.
    if (workspace.can(Permission.workspaceEdit)) {
        if (current.needsRegionConfirmation == true) {
            HomeRegionCard()
        } else {
            HomeClockCheck()
        }
    }
    val checklist = model.checklist
    if (workspace.can(Permission.workspaceEdit) && checklist != null && checklist.complete != true) {
        HomeChecklistCard(checklist = checklist, onChange = { model.checklist = it })
    }
    // An invited member's items left for later: their signature, their credential, the welcome.
    if (model.reminders.isNotEmpty()) {
        HomeRemindersCard(reminders = model.reminders, onHidden = { id -> model.reminders = model.reminders.filter { it.id != id } })
    }
    // Visits left at a location the plan paused (owners and admins): move or cancel them.
    if (model.relocations.isNotEmpty()) {
        HomeRelocateBanner(visits = model.relocations)
    }
    // Families' requests waiting for an answer.
    HomeRequestsBanner(
        requests = model.familyRequests,
        online = model.today?.needsYou?.onlineRequests,
    )
    val today = model.today
    val error = model.error
    if (today != null) {
        val changeDay: ((String?) -> Unit)? = if (workspace.can(Permission.appointmentsView)) ({ otherDay.value = it }) else null
        val day = otherDay.value
        val date = today.date
        if (day != null && date != null && day != date) {
            HomeDayView(
                date = day, today = date, model = dayModel, onChangeDay = { otherDay.value = it },
                onNewAppointment = if (canBook) ({ actions.newAppointment(day = day) }) else null,
            )
        } else {
            HomeBoard(today = today, model = model, open = { actions.route = it }, changeDay = changeDay)
        }
    } else if (error != null) {
        ErrorState(message = error) { model.load(workspace) }
    } else {
        LoadingState()
    }
}

/** "miércoles 4 de noviembre" for the day the server says it is. */
object HomeHeaderDate {
    fun text(day: String?): String = HomeDayKey.long(day)
}

/** Whether the plan lets the business use its tools (web lib/provider-workspace-access.ts). */
object HomeAccess {
    fun canUse(w: ProviderWorkspace): Boolean {
        val status = w.billingStatus ?: return false
        if (w.billingProvider == null) return false
        return when (status) {
            "active", "grace_period" -> true
            "canceled" -> (DateText.parse(w.currentPeriodEndsAt) ?: Instant.MIN) > Instant.now()
            "trialing" -> (DateText.parse(w.trialEndsAt) ?: Instant.MIN) > Instant.now()
            else -> false
        }
    }

    fun lockedTitle(w: ProviderWorkspace): String =
        if (w.billingStatus == "trialing") L("Trial ended") else L("Activation needed")
}

/** What only the web does (setup, the plan): the kit's empty state, as a panel, with the way there. */
@Composable
fun HomeWebNotice(
    systemImage: String,
    title: String,
    message: String,
    actionTitle: String,
    path: String,
    tone: NoticeCard.Tone = NoticeCard.Tone.info,
    modifier: Modifier = Modifier,
) {
    val openURL = rememberOpenURL()
    EmptyState(
        systemImage = systemImage,
        title = title,
        message = message + " " + L("Opens PawMiaw on the web."),
        actionTitle = actionTitle,
        action = { openURL(APIConfig.webURL(path).toString()) },
        accent = if (tone == NoticeCard.Tone.warning) Theme.mango else Theme.blue,
        panel = true,
        modifier = modifier,
    )
}

/** "Hoy" follows the place's clock; a place left on another zone than this device's is offered this device's. */
@Composable
private fun HomeClockCheck() {
    val workspace = Workspace.shared
    var saving by remember { mutableStateOf(false) }
    var fixed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val saved = (if (workspace.multiLocation) workspace.selectedLocation?.availability?.timezone ?: workspace.selectedLocation?.timezone else null)
        ?: workspace.current?.availability?.timezone ?: "UTC"
    val device = ZoneId.systemDefault().id

    fun offset(zone: String): Int? = runCatching { ZoneId.of(zone).rules.getOffset(Instant.now()).totalSeconds }.getOrNull()

    val differs = !fixed && device != saved && offset(device) != offset(saved)

    fun label(zone: String): String {
        val name = runCatching { ZoneId.of(zone).getDisplayName(TextStyle.FULL, appLocale) }.getOrNull()
        val city = zone.split("/").lastOrNull()?.replace("_", " ") ?: zone
        return name?.let { "$city ($it)" } ?: city
    }

    suspend fun fix() {
        if (saving) return
        val clinicId = workspace.current?.clinicId ?: return
        saving = true
        try {
            val path = "/provider-workspaces/$clinicId/availability"
            val query = mapOf("locationId" to workspace.locationId)
            val availability = API.get<Map<String, JsonElement>>(path, query).toMutableMap()
            availability["timezone"] = JsonPrimitive(device)
            // The web sends the whole availability back with the new zone (the server takes it whole).
            val body = API.encode(JsonObject(availability))
            API.data("PATCH", path, query, body)
            fixed = true
            Toast.show(LF("The workspace now runs on %@.", label(device)))
            workspace.refresh()
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(e)
        } finally {
            saving = false
        }
    }

    if (differs) {
        NoticeCard(
            tone = NoticeCard.Tone.custom(Theme.mango),
            title = LF("The workspace clock is set to %@.", label(saved)),
            message = LF("This device is on %@; “today”, opening hours and the day’s totals follow the workspace.", label(device)),
            icon = "clock",
            look = NoticeCard.Look.banner,
            actionTitle = LF("Use %@", label(device)),
            isWorking = saving,
            action = { scope.launch { fix() } },
        )
    }
}
