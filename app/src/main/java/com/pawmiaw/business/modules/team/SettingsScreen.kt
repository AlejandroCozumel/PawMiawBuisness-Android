package com.pawmiaw.business.team

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.text.NumberFormat

//
//  SettingsScreen.kt (iOS SettingsScreen.swift)
//
//  Settings (web: routes/provider.settings.tsx): how the business shows up
//  to families (name, logo, contact, address), what it offers, the country,
//  clock and money it runs on, what prints on its documents, and this
//  person's own alerts. Each section saves on its own. Staff, hours, rooms,
//  locations and the plan have their own pages and are linked. Someone who
//  cannot edit the workspace reads it, and is told so.
//

/** What Settings reads once and its sections share. `@StateObject` → `remember { TeamSettingsModel() }`. */
@Stable
class TeamSettingsModel {
    var setup by mutableStateOf<TeamSetupState?>(null)
    var setupFailed by mutableStateOf(false)
    var catalog by mutableStateOf<TeamServiceCatalog?>(null)
    var offeredCatalog by mutableStateOf<TeamServiceCatalog?>(null)
    var catalogFailed by mutableStateOf(false)
    var store by mutableStateOf<TeamStoreSettings?>(null)
    var storeFailed by mutableStateOf(false)
    var memberCount by mutableStateOf<Int?>(null)
    /** The open days of the week, for Availability's line. */
    var openDays by mutableStateOf<Int?>(null)
    /** Every room the Rooms page lists (the hospital's too), for its line. */
    var roomCount by mutableStateOf<Int?>(null)
    var loaded by mutableStateOf(false)

    suspend fun load(workspace: Workspace) {
        val clinicId = workspace.current?.clinicId ?: return
        val canEdit = workspace.can(Permission.workspaceEdit)
        try {
            catalog = API.get("/appointment-services")
            catalogFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            catalogFailed = catalog == null
        }
        if (canEdit) {
            try {
                setup = API.get("/provider-setup")
                setupFailed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setupFailed = setup == null
            }
        } else {
            offeredCatalog = runCatching { API.get<TeamServiceCatalog>("/appointment-services", query = mapOf("scope" to "provider")) }.getOrNull()
        }
        if (canSeeMoney(workspace)) {
            try {
                store = TeamStoreSettings.load()
                storeFailed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                storeFailed = store == null
            }
        }
        if (workspace.can(Permission.staffView)) {
            val members = runCatching { API.get<List<TeamMember>>("/provider-workspaces/$clinicId/members") }.getOrNull()
            memberCount = members?.size
        }
        if (canEdit) {
            runCatching { API.get<TeamAvailability>("/provider-workspaces/$clinicId/availability", query = mapOf("locationId" to workspace.locationId)) }.getOrNull()?.let { availability ->
                openDays = (availability.weeklySchedule ?: emptyList()).count { it.enabled }
            }
        }
        if (workspace.can(Permission.appointmentsView)) {
            val rooms = runCatching { API.get<List<JsonElement>>("/provider-rooms", query = mapOf("locationId" to workspace.locationId)) }.getOrNull()
            roomCount = rooms?.size
        }
        loaded = true
    }

    /** Whatever settings changed: the workspace, the currency other screens show. */
    suspend fun saved(workspace: Workspace) {
        load(workspace)
        Workspace.shared.refresh()
        announceDataChanged()
    }

    companion object {
        /** The store's settings answer anyone who sells, keeps the catalog or the stock. */
        fun canSeeMoney(workspace: Workspace): Boolean =
            workspace.canAny(Permission.salesSell, Permission.catalogView, Permission.inventoryView)
    }
}

private enum class TeamSettingsSection(val rawValue: String) {
    profile("profile"), contact("contact"), services("services"), regional("regional"), legal("legal"),
    consents("consents"), ai("ai"), notifications("notifications"), history("history");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            profile -> L("Business profile")
            contact -> L("Contact and address")
            services -> L("Services you offer")
            regional -> L("Region and money")
            legal -> L("Legal details")
            consents -> L("Consents")
            ai -> "Olfato AI"
            notifications -> L("Your notifications")
            history -> L("Change history")
        }

    /** The web's icon for the section (settings rail). */
    val icon: String
        get() = when (this) {
            profile -> "building-2"
            contact -> "mail"
            services -> "tags"
            regional -> "earth"
            legal -> "landmark"
            consents -> "file-pen-line"
            ai -> "olfato"
            notifications -> "bell"
            history -> "history"
        }

    companion object {
        fun from(raw: String?): TeamSettingsSection? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Who may open what in Settings (the web's rules, provider.settings.tsx). */
private object TeamSettingsAccess {
    fun canSeePlan(workspace: Workspace): Boolean =
        (workspace.current?.canManagePlan ?: false) || workspace.moneyLevel >= MoneyLevel.finances

    fun canSeeConsents(workspace: Workspace): Boolean =
        workspace.canAny(Permission.appointmentsView, Permission.staysView, Permission.recordsView, Permission.workspaceEdit)

    /** Olfato AI: the switch for whoever reads records or edits the workspace; the team's use for owners, admins and whoever manages roles or money. */
    fun canOpenAi(workspace: Workspace): Boolean =
        workspace.canAny(Permission.recordsView, Permission.workspaceEdit) || TeamOlfatoAccess.seesTeam(workspace)

    fun canSeeHistory(workspace: Workspace): Boolean =
        workspace.can(Permission.workspaceEdit) || workspace.isOwnerOrAdmin

    fun planLine(current: ProviderWorkspace): String {
        val plan: String? = when (current.plan) {
            "basic" -> "Basic"
            "pro" -> "Pro"
            "enterprise" -> "Enterprise"
            else -> null
        }
        val status = when (current.billingStatus) {
            "trialing" -> L("Trial")
            "active" -> L("Active")
            "past_due" -> L("Payment due")
            "canceled" -> L("Cancelled")
            "expired" -> L("Expired")
            "grace_period" -> L("Grace period")
            else -> L("No plan")
        }
        return plan?.let { LF("%1\$@ plan · %2\$@", it, status) } ?: status
    }

    fun city(zone: String): String = zone.split("/").lastOrNull()?.replace("_", " ") ?: zone
}

/**
 * Settings' module header (web: `ProviderModuleHeader` with the Settings icon
 * and its four numbers; the kinds of care and the time zone open their section).
 */
@Composable
private fun TeamSettingsHeader(
    model: TeamSettingsModel,
    showsStats: Boolean = true,
    /** The section shown under it (its number reads as chosen). */
    open: TeamSettingsSection? = null,
    onOpen: ((TeamSettingsSection) -> Unit)? = null,
) {
    val workspace = Workspace.shared
    val stats = ArrayList<ModuleStat>()
    val current = workspace.current
    if (showsStats && current != null) {
        val timezone = current.availability?.timezone ?: "UTC"
        val names = current.enabledCategories.map { id ->
            model.catalog?.categories?.firstOrNull { it.id == id }?.label ?: BusinessCategory.from(id)?.label ?: id
        }
        stats.add(
            ModuleStat(
                "services", label = L("Kinds of care"), value = "${current.enabledCategories.size}",
                hint = names.take(3).joinToString(", ") + (if (names.size > 3) "…" else ""),
                icon = "tags", accent = Theme.lime, active = open == TeamSettingsSection.services,
                action = onOpen?.let { go -> { go(TeamSettingsSection.services) } },
            )
        )
        stats.add(
            ModuleStat(
                "timezone", label = L("Time zone"), value = TeamSettingsAccess.city(timezone), hint = TeamHours.zoneLabel(timezone),
                icon = "earth", accent = Theme.aqua, active = open == TeamSettingsSection.regional,
                action = onOpen?.let { go -> { go(TeamSettingsSection.regional) } },
            )
        )
        if (TeamSettingsModel.canSeeMoney(workspace)) {
            val store = model.store
            stats.add(
                ModuleStat(
                    "money", label = L("Currency and tax"), value = store?.currency ?: "—",
                    hint = store?.let {
                        LF("%1\$@%% tax, %2\$@", NumberFormat.getNumberInstance(appLocale).format(it.defaultTaxRateBps / 100.0), if (it.pricesIncludeTax) L("in the price") else L("added on top"))
                    },
                    icon = "coins", accent = Theme.mango,
                    action = onOpen?.let { go -> { go(TeamSettingsSection.regional) } },
                )
            )
        }
        stats.add(
            ModuleStat(
                "role", label = L("Your role"), value = TeamRole.label(current.role),
                hint = if (TeamSettingsAccess.canSeePlan(workspace)) TeamSettingsAccess.planLine(current) else null,
                icon = "shield-check", accent = Theme.pink,
            )
        )
    }
    ModuleHeader(
        icon = "settings",
        title = L("Settings"),
        subtitle = L("How your business shows up to families, what it offers, and the clock and money it runs on."),
        accent = ProviderSection.settings.headerAccent,
        stats = stats,
        storageKey = "settings",
    )
}

/** "You can look, not change." over a section, for whoever can't edit the workspace. */
@Composable
private fun TeamReadOnlyBanner() {
    val card = RoundedCornerShape(Theme.cardRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.surfaceMuted, card)
            .border(1.dp, Theme.borderSoft, card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph("lock", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(L("You can look, not change.")) }
                append(" ")
                append(L("The business settings are changed by the owner or someone who edits the workspace. Your notifications are yours to set."))
            },
            style = Theme.text(14),
            color = Theme.inkStrong,
        )
    }
}

/**
 * One of Settings' two lists (web `SettingsNav`): "En esta página" (the sections)
 * and "Con su propia página" (the settings that have a page of their own).
 */
@Composable
private fun TeamSettingsNavCard(label: String, rows: @Composable ColumnScope.() -> Unit) {
    val card = RoundedCornerShape(Theme.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .cardShadow(card)
            .background(Theme.surface, card)
            .border(1.dp, Theme.borderSoft, card),
    ) {
        Text(
            label.uppercase(appLocale),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            style = brandText(10.9, FontWeight.ExtraBold).tracking(1.5),
            color = Theme.inkSoft,
        )
        Column(Modifier.fillMaxWidth().padding(6.dp), content = rows)
    }
}

/**
 * A row of the Settings lists: a 36 disc with the icon, the name, a line, and
 * a chevron (a section) or an arrow (a page of its own).
 */
@Composable
private fun TeamSettingsNavRow(
    title: String,
    hint: String?,
    icon: String,
    external: Boolean = false,
    current: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = if (current) Theme.onBlue else (if (external) Theme.inkSoft else Theme.blue)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (current) Theme.wash(Theme.blue, 12.0) else Color.Transparent, RoundedCornerShape(Theme.boxRadius))
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(if (current) Theme.blue else Theme.surfaceMuted, CircleShape), contentAlignment = Alignment.Center) {
            if (icon == "olfato") {
                TeamOlfatoFace(Modifier.size(18.dp))
            } else {
                Glyph(icon, 16.dp, tint = ink)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        Glyph(if (external) "arrow-up-right" else "chevron-right", 16.dp, tint = Theme.inkSoft)
    }
}

/**
 * A page pushed over Settings that has no route of its own (iOS `.navigationDestination(item:)`
 * and a `NavigationLink` to a section's screen): it slides in over the page and Back pops it.
 */
@Composable
private fun <T : Any> TeamPushedPage(item: T?, onBack: () -> Unit, content: @Composable (T) -> Unit) {
    // iOS `.navigationDestination(item:)` / `NavigationLink`: pushed on the stack as any page
    // (`ProviderRoute.page`), and the trigger cleared at once.
    val navigator = LocalNavigator.current
    val clear by rememberUpdatedState(onBack)
    val body by rememberUpdatedState(content)
    LaunchedEffect(item) {
        val page = item ?: return@LaunchedEffect
        clear()
        navigator.push(ProviderRoute.page("team-settings:$page") { body(page) })
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val wide = LocalSizeClass.current == SizeClass.regular
    val model = remember { TeamSettingsModel() }
    var opened by remember { mutableStateOf<TeamSettingsSection?>(null) }
    /** iPad: the section beside the rail (the web shows the first one when none is chosen). */
    var railSection by remember { mutableStateOf(TeamSettingsSection.profile) }
    val railKey = remember { TeamUnsavedKey() }
    /** iPad: a section asked for while the open one has unsaved changes. */
    var pendingSection by remember { mutableStateOf<TeamSettingsSection?>(null) }
    /** A part the change history opens (its own page). */
    var openedArea by remember { mutableStateOf<String?>(null) }

    val canEdit = workspace.can(Permission.workspaceEdit)
    val railUnsaved = railKey.value

    /** Opens a section: pushed on a phone; beside the rail on an iPad, asking first when the open one has unsaved changes. */
    fun open(section: TeamSettingsSection) {
        if (!wide) {
            opened = section
            return
        }
        if (section == railSection) return
        if (railUnsaved.isEmpty()) railSection = section else pendingSection = section
    }

    @Composable
    fun NavRow(section: TeamSettingsSection, hint: String) {
        TeamSettingsNavRow(
            title = section.title,
            hint = hint,
            icon = section.icon,
            current = wide && railSection == section,
            modifier = Modifier.plainButton { open(section) },
        )
    }

    @Composable
    fun Link(section: ProviderSection, title: String, hint: String, icon: String) {
        TeamSettingsNavRow(title = title, hint = hint, icon = icon, external = true, modifier = Modifier.plainButton { nav.push(ProviderRoute.section(section)) })
    }

    @Composable
    fun NavCards(current: ProviderWorkspace, city: String) {
        TeamSettingsNavCard(label = L("On this page")) {
            NavRow(TeamSettingsSection.profile, hint = current.name)
            NavRow(TeamSettingsSection.contact, hint = current.email.nonEmpty ?: current.phone.nonEmpty ?: L("No contact yet"))
            NavRow(TeamSettingsSection.services, hint = LP(current.enabledCategories.size, "1 kind of care", "%d kinds of care"))
            NavRow(TeamSettingsSection.regional, hint = listOfNotNull(city, model.store?.currency).joinToString(" · "))
            NavRow(TeamSettingsSection.legal, hint = model.setup?.legal?.taxId.nonEmpty ?: L("What prints on your documents"))
            if (TeamSettingsAccess.canSeeConsents(workspace)) NavRow(TeamSettingsSection.consents, hint = L("What families sign"))
            if (TeamSettingsAccess.canOpenAi(workspace)) NavRow(TeamSettingsSection.ai, hint = L("A brief on each chart"))
            NavRow(TeamSettingsSection.notifications, hint = L("Just for you"))
            if (TeamSettingsAccess.canSeeHistory(workspace)) NavRow(TeamSettingsSection.history, hint = L("Who changed what"))
        }
        val canSeeStaff = workspace.can(Permission.staffView)
        val canSeeRooms = workspace.can(Permission.appointmentsView)
        val canSeePlan = TeamSettingsAccess.canSeePlan(workspace)
        if (canSeeStaff || canEdit || canSeeRooms || canSeePlan) {
            TeamSettingsNavCard(label = L("With their own page")) {
                if (canSeeStaff) {
                    Link(ProviderSection.staff, L("Staff and access"), hint = model.memberCount?.let { LP(it, "1 person", "%d people") } ?: L("Roles and permissions"), icon = "user-round")
                }
                if (canEdit) {
                    Link(ProviderSection.availability, L("Availability"), hint = model.openDays?.let { LP(it, "1 day open", "%d days open") } ?: L("When you are open and how visits are booked."), icon = "clock-3")
                }
                if (canSeeRooms) {
                    Link(ProviderSection.rooms, L("Rooms"), hint = model.roomCount?.let { LP(it, "1 room", "%d rooms") } ?: L("Where visits happen"), icon = "door-open")
                }
                if (canEdit) {
                    Link(ProviderSection.locations, L("Locations"), hint = LP(workspace.activeLocations.size, "1 active", "%d active"), icon = "map-pin")
                }
                if (canSeePlan) {
                    Link(ProviderSection.billing, L("Subscription"), hint = TeamSettingsAccess.planLine(current), icon = "circle-dollar-sign")
                }
            }
        }
    }

    providerPage(L("Settings"), root = true, modifier = modifier) {
        ReloadsOnScope { model.load(workspace) }
        val current = workspace.current
        if (workspace.inSetup) {
            PageScroll {
                TeamSettingsHeader(model, showsStats = false)
                EmptyState(
                    systemImage = "building-2",
                    title = L("Finish workspace setup first."),
                    message = L("Save the business basics and opening hours, then its settings live here."),
                    accent = Theme.aqua,
                    panel = true,
                )
            }
        } else if (current != null) {
            val city = TeamSettingsAccess.city(current.availability?.timezone ?: "UTC")
            if (wide) {
                // The web from 768: the rail on the left, the open section beside it.
                PageScroll(maxWidth = 1200.dp, refresh = { model.load(workspace) }) {
                    TeamSettingsHeader(model, open = railSection) { open(it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.width(272.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { NavCards(current, city) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            key(railSection) {
                                CompositionLocalProvider(LocalTeamUnsaved provides railKey) {
                                    if (!canEdit) TeamReadOnlyBanner()
                                    TeamSettingsSectionBody(section = railSection, model = model) { openedArea = it }
                                }
                            }
                        }
                    }
                }
            } else {
                PageScroll(refresh = { model.load(workspace) }) {
                    TeamSettingsHeader(model) { open(it) }
                    NavCards(current, city)
                }
            }
        } else {
            LoadingState()
        }

        TeamPushedPage(item = opened, onBack = { opened = null }) { section -> TeamSettingsSectionPage(section = section, model = model) }
        TeamPushedPage(item = openedArea, onBack = { openedArea = null }) { area -> TeamSettingsSectionScreen(section = area) }

        pendingSection?.let { next ->
            AppSheet(onDismissRequest = { pendingSection = null }) {
                ConfirmSheet(
                    title = L("Leave without saving?"),
                    message = LF("Your changes to %@ are not saved yet.", railUnsaved.joinToString(", ")),
                    confirmTitle = L("Leave without saving"),
                    destructive = true,
                    cancelTitle = L("Keep editing"),
                ) { _ ->
                    railSection = next
                }
            }
        }
    }
}

/** One Settings section opened from another page (a staff member's online card → Services). */
@Composable
fun TeamSettingsSectionScreen(
    /** "profile", "contact", "services", "regional", "legal", "consents", "ai", "notifications" or "history". */
    section: String,
    modifier: Modifier = Modifier,
) {
    val model = remember { TeamSettingsModel() }
    TeamSettingsSectionPage(section = TeamSettingsSection.from(section) ?: TeamSettingsSection.services, model = model, modifier = modifier)
}

/**
 * One section of Settings on its own page: Settings' header on top (the web
 * keeps it over the open section), the read-only note, then the section.
 */
@Composable
private fun TeamSettingsSectionPage(section: TeamSettingsSection, model: TeamSettingsModel, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    /** The cards on this page with changes that aren't saved (their titles). */
    val unsavedKey = remember { TeamUnsavedKey() }
    var openedArea by remember { mutableStateOf<String?>(null) }
    providerPage(section.title, modifier = modifier) {
        CompositionLocalProvider(LocalTeamUnsaved provides unsavedKey) {
            PageScroll(refresh = { model.load(workspace) }) {
                TeamSettingsHeader(model, open = section)
                if (!workspace.can(Permission.workspaceEdit)) TeamReadOnlyBanner()
                TeamSettingsSectionBody(section = section, model = model) { openedArea = it }
            }
        }
        val unsaved = unsavedKey.value
        // Leaving with unsaved changes asks first (switching sections on the web keeps drafts; here each is a page).
        TeamLeaveGuard(dirty = unsaved.isNotEmpty(), message = LF("Your changes to %@ are not saved yet.", unsaved.joinToString(", ")))
        LaunchedEffect(Unit) { if (!model.loaded) model.load(workspace) }
        TeamPushedPage(item = openedArea, onBack = { openedArea = null }) { area -> TeamSettingsSectionScreen(section = area) }
    }
}

/** One Settings section's cards (a page of its own on a phone, beside the rail on an iPad). */
@Composable
private fun TeamSettingsSectionBody(section: TeamSettingsSection, model: TeamSettingsModel, onOpenArea: (String) -> Unit) {
    val workspace = Workspace.shared
    when (section) {
        TeamSettingsSection.profile -> TeamProfileSection(model)
        TeamSettingsSection.contact -> TeamContactSection(model)
        TeamSettingsSection.services -> {
            TeamServicesSection(model)
            if (workspace.can(Permission.workspaceEdit)) TeamOnlineServicesPanel()
        }
        TeamSettingsSection.regional -> TeamRegionSection(model)
        TeamSettingsSection.legal -> TeamLegalSection(model)
        TeamSettingsSection.consents -> TeamConsentTemplatesSection(model)
        TeamSettingsSection.ai -> TeamOlfatoSections()
        TeamSettingsSection.notifications -> TeamNotificationsSection()
        TeamSettingsSection.history -> {
            val clinicId = workspace.current?.clinicId
            if (clinicId != null) {
                AuditHistoryCard(
                    entityType = "clinic",
                    entityId = clinicId,
                    title = L("Change history"),
                    description = L("Who changed the business profile, contact, services, region and money, legal details and Olfato AI, and when."),
                    collapsible = false,
                    emptyText = L("Each save in Settings is listed here, with who made it."),
                    // The parts a change belongs to, by the server's action names (web: settings-history.ts).
                    areas = listOf(
                        AuditArea(key = "profile", label = L("Business profile"), actions = listOf("clinic.profile.updated", "clinic.logo.updated", "clinic.logo.removed", "clinic.settings.updated")),
                        AuditArea(key = "contact", label = L("Contact and address"), actions = listOf("clinic.contact.updated")),
                        AuditArea(key = "services", label = L("Services you offer"), actions = listOf("clinic.services.updated")),
                        AuditArea(key = "regional", label = L("Region and money"), actions = listOf("clinic.region.updated", "provider_store.settings.updated")),
                        AuditArea(key = "legal", label = L("Legal details"), actions = listOf("clinic.legal.updated")),
                        AuditArea(key = "ai", label = "Olfato AI", actions = listOf("provider_patient_summary.settings.updated")),
                    ),
                    openArea = onOpenArea,
                )
            }
        }
    }
}

// MARK: - Business profile

/** The business name and logo, and how families see the business. Both save together. */
@Composable
private fun TeamProfileSection(model: TeamSettingsModel) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val flash = remember { TeamSavedFlash() }
    val current = workspace.current
    val savedName = current?.name ?: ""
    // iOS `.onAppear { if !started { name = savedName } }`.
    val name = remember { mutableStateOf(savedName) }
    val logo = remember { mutableStateOf(PhotoDraft()) }
    var saving by remember { mutableStateOf(false) }

    val canEdit = workspace.can(Permission.workspaceEdit)
    val dirty = name.value.trim() != savedName.trim() || !logo.value.isEmpty

    // iOS `.onChange(of: savedName) { old, new in if name == old { name = new } }`.
    val previousName = remember { arrayOf(savedName) }
    LaunchedEffect(savedName) {
        val old = previousName[0]
        if (old != savedName) {
            if (name.value == old) name.value = savedName
            previousName[0] = savedName
        }
    }

    fun save() {
        val trimmed = name.value.trim()
        if (!form.check(listOf("name" to (if (trimmed.isEmpty()) L("Add the business name.") else if (trimmed.length > 120) L("Keep the name under 120 characters.") else null)))) return
        val clinicId = workspace.current?.clinicId ?: return
        saving = true
        scope.launch {
            try {
                // The logo first: if it is refused the name is still unsaved, and Save can be pressed again.
                try {
                    val data = logo.value.data
                    if (data != null) {
                        ImageUpload.upload<EmptyResponse>(data, to = "/provider-setup/logo", field = "logo")
                    } else if (logo.value.removed) {
                        API.send("DELETE", "/provider-setup/logo")
                    }
                    logo.value = PhotoDraft()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.error(errorText(e, fallback = L("Could not upload the logo.")))
                    return@launch
                }
                if (trimmed != savedName) {
                    try {
                        API.patch<EmptyResponse>("/provider-workspaces/$clinicId", mapOf("name" to trimmed))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        form.apply(e, known = mapOf("name" to "name"))
                        return@launch
                    }
                }
                model.saved(workspace)
                flash.mark()
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Business profile"),
        subtitle = L("The name and logo families see on visits, bills and messages. Your own account stays separate."),
        systemImage = "building-2",
        tint = Theme.blue,
        look = TeamCardLook.settings,
        state = flash.state(saving = saving, dirty = dirty),
        saveLabel = L("Save profile"),
        readOnlyText = if (canEdit) null else L("Only someone who edits the workspace can change this."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { name.value = savedName; logo.value = PhotoDraft(); form.errors = emptyMap() },
    ) {
        FormScroll(form) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Disabled(!canEdit || saving) {
                    PhotoField(
                        label = L("Logo"),
                        hint = if (canEdit) L("JPG, PNG or WebP, up to 3 MB. A square one looks best.") else null,
                        currentURL = current?.logoUrl,
                        draft = logo,
                        shape = PhotoField.Shape.rounded,
                        allowsRemove = canEdit,
                        addTitle = L("Add a logo"),
                        changeTitle = L("Change logo"),
                        removeTitle = L("Remove the logo"),
                        removedNote = L("The logo is removed when you save."),
                    )
                    PMTextField(
                        L("Business name"),
                        text = name,
                        key = "name",
                        form = form,
                        prompt = L("e.g. Veterinaria Luna"),
                        hint = L("As families know you, not the registered legal name."),
                        capitalization = KeyboardCapitalization.Words,
                        required = true,
                    )
                }
                ProfilePreview(current, name.value, logo.value)
            }
        }
    }
}

/** "How families see you" (web: the preview box beside the fields). */
@Composable
private fun ProfilePreview(current: ProviderWorkspace?, name: String, logo: PhotoDraft) {
    val rows = listOf(
        Triple("mail", current?.email ?: "", L("No email yet")),
        Triple("phone", current?.phone ?: "", L("No phone yet")),
        Triple("map-pin", current?.address ?: "", L("No address yet")),
    )
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surfaceMuted, box)
            .border(1.dp, Theme.borderSoft, box)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(L("How families see you").uppercase(appLocale), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.5), color = Theme.inkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val preview = logo.preview
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(CircleShape).border(1.dp, Theme.borderSoft, CircleShape),
                )
            } else if (!logo.removed && current?.logoUrl != null) {
                Avatar(name = current.name, imageURL = current.logoUrl, size = 44.dp, modifier = Modifier.border(1.dp, Theme.borderSoft, CircleShape))
            } else {
                IconBadge(icon = "building-2", accent = Theme.blue, size = 44.dp, iconSize = 20.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name.nonEmpty ?: current?.name ?: "", style = Theme.heading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(LF("Your role: %@", TeamRole.label(current?.role)), style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (icon, raw, empty) ->
                val value = raw.trim()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(icon, 16.dp, tint = Theme.blue)
                    Text(
                        value.nonEmpty ?: empty,
                        style = Theme.text(14).copy(fontStyle = if (value.isEmpty()) FontStyle.Italic else FontStyle.Normal),
                        color = if (value.isEmpty()) Theme.inkSoft else Theme.inkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// MARK: - Contact and address

@Composable
private fun TeamContactSection(model: TeamSettingsModel) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val flash = remember { TeamSavedFlash() }
    val canEdit = workspace.can(Permission.workspaceEdit)
    val saved = Triple(workspace.current?.email ?: "", workspace.current?.phone ?: "", workspace.current?.address ?: "")
    // iOS `.onAppear { if !started { fill() } }`.
    val email = remember { mutableStateOf(saved.first) }
    val phone = remember { mutableStateOf(saved.second) }
    val address = remember { mutableStateOf(saved.third) }
    var saving by remember { mutableStateOf(false) }

    val dirty = email.value.trim() != saved.first.trim() || phone.value.trim() != saved.second.trim() || address.value.trim() != saved.third.trim()

    fun fill() {
        email.value = saved.first; phone.value = saved.second; address.value = saved.third
        form.errors = emptyMap()
    }

    fun save() {
        val e = email.value.trim()
        val p = phone.value.trim()
        val a = address.value.trim()
        val digits = p.count { it.isDigit() }
        var emailError: String? = null
        if (e.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(e)) emailError = L("Check the email address.")
        if (e.isEmpty() && p.isEmpty()) emailError = L("Add an email or a phone so families can reach you.")
        val phoneError = if (p.isNotEmpty() && (digits < 8 || digits > 15)) L("Finish the phone number, or leave it empty.") else null
        val addressError = if (a.isEmpty()) L("Add the address or the area you serve.") else if (a.length < 5) L("Write at least 5 characters.") else if (a.length > 240) L("Keep the address under 240 characters.") else null
        if (!form.check(listOf("email" to emailError, "phone" to phoneError, "address" to addressError))) return
        val clinicId = workspace.current?.clinicId ?: return
        saving = true
        scope.launch {
            try {
                // The server needs the name on every change; send the saved one.
                API.patch<EmptyResponse>("/provider-workspaces/$clinicId", mapOf("name" to (workspace.current?.name ?: ""), "email" to e.nonEmpty, "phone" to p.nonEmpty, "address" to a))
                model.saved(workspace)
                flash.mark()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                form.apply(error, known = mapOf("email" to "email", "phone" to "phone", "address" to "address"))
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Contact and address"),
        subtitle = L("How families reach you and where you work. Add at least an email or a phone."),
        systemImage = "mail",
        tint = Theme.aqua,
        look = TeamCardLook.settings,
        state = flash.state(saving = saving, dirty = dirty),
        saveLabel = L("Save contact"),
        readOnlyText = if (canEdit) null else L("Only someone who edits the workspace can change this."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { fill() },
    ) {
        FormScroll(form) {
            Disabled(!canEdit || saving) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PMTextField(L("Email"), text = email, key = "email", form = form, prompt = "hola@veterinarialuna.mx", keyboard = KeyboardType.Email)
                    PMTextField(L("Phone"), text = phone, key = "phone", form = form, keyboard = KeyboardType.Phone)
                    PMTextField(
                        L("Address or area you serve"),
                        text = address,
                        key = "address",
                        form = form,
                        prompt = L("e.g. Av. Juárez 120, Centro — or Condesa and Roma"),
                        hint = L("A street address, or the neighbourhoods you visit if you go to the pet."),
                        required = true,
                    )
                    if (canEdit) {
                        val blue = Theme.blue
                        Text(
                            buildAnnotatedString {
                                append(L("This is your main location’s phone and address. Other locations have their own in"))
                                append(" ")
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = blue)) { append(L("Locations")) }
                                append(".")
                            },
                            Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.locations)) },
                            style = Theme.text(12),
                            color = Theme.inkSoft,
                        )
                    }
                }
            }
        }
    }
}
