package com.pawmiaw.business.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

//
//  TeamSettingsSections.kt (iOS TeamSettingsSections.swift)
//
//  Settings › Services you offer (with "Se reserva en línea" per service),
//  Region and money, and Legal details (web: components/app/settings/,
//  components/app/online-booking/online-services-panel.tsx).
//

// MARK: - Services you offer

private data class TeamOfferedService(
    val offered: Boolean,
    val minutes: Int,
)

private data class TeamOfferedDraft(
    val categories: List<String>,
    val services: Map<String, TeamOfferedService>,
) {
    fun same(other: TeamOfferedDraft): Boolean {
        if (categories.toSet() != other.categories.toSet()) return false
        for (id in services.keys + other.services.keys) {
            val a = services[id]
            val b = other.services[id]
            if ((a?.offered ?: false) != (b?.offered ?: false)) return false
            if ((a?.offered ?: false) && (b?.offered ?: false) && a?.minutes != b?.minutes) return false
        }
        return true
    }
}

private class TeamSettingsService(
    val serviceId: String,
    val category: String,
    val label: String,
    val description: String?,
    val defaultMinutes: Int,
    val suggested: Boolean?,
)

/** The section's shared words (the Swift `static` members). */
object TeamServicesSection {
    /** Boarding and daycare may be on for Stays alone, with no service ticked. */
    val stayOnly = setOf("boarding", "daycare")
    val durations = (1..12).map { it * 15 }

    /** The category's Lucide icon (web PROVIDER_CATEGORY_ICONS). */
    fun icon(category: String): String = when (category) {
        "vet" -> "stethoscope"
        "groomer" -> "scissors"
        "walker" -> "footprints"
        "sitter" -> "house"
        "trainer" -> "dumbbell"
        "daycare", "boarding" -> "building-2"
        "pharmacy" -> "package"
        "emergency" -> "siren"
        else -> "ellipsis"
    }

    fun minutesLabel(minutes: Int): String {
        if (minutes < 60) return LF("%d min", minutes)
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest > 0) LF("%1\$d h %2\$d min", hours, rest) else LF("%d h", hours)
    }
}

/** The kinds of care the business offers and, for each, the services it books and how long they take. */
@Composable
fun TeamServicesSection(model: TeamSettingsModel, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val flash = remember { TeamSavedFlash() }
    var draft by remember { mutableStateOf<TeamOfferedDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val canEdit = workspace.can(Permission.workspaceEdit)
    val stayOnly = TeamServicesSection.stayOnly

    val services: List<TeamSettingsService> = run {
        val suggested = HashMap<String, Boolean?>()
        for (service in model.setup?.business?.services ?: emptyList()) if (!suggested.containsKey(service.serviceId)) suggested[service.serviceId] = service.suggested
        (model.catalog?.services ?: emptyList()).map {
            TeamSettingsService(it.id, it.category, it.label, it.description, it.defaultMinutes, suggested[it.id])
        }
    }

    val saved: TeamOfferedDraft = run {
        val categories = model.setup?.business?.enabledCategories ?: workspace.current?.enabledCategories ?: emptyList()
        val map = LinkedHashMap<String, TeamOfferedService>()
        val setup = model.setup?.business?.services
        if (setup != null) {
            for (service in setup) map[service.serviceId] = TeamOfferedService(offered = service.offered ?: false, minutes = service.durationMinutes ?: service.defaultDurationMinutes ?: 30)
        } else {
            for (service in model.offeredCatalog?.services ?: emptyList()) map[service.id] = TeamOfferedService(offered = true, minutes = service.defaultMinutes)
        }
        TeamOfferedDraft(categories = categories, services = map)
    }

    val value = draft ?: saved

    fun empty(value: TeamOfferedDraft): Set<String> {
        val covered = HashSet<String>()
        for (service in services) if (value.services[service.serviceId]?.offered ?: false) covered.add(service.category)
        return value.categories.filter { !covered.contains(it) && !stayOnly.contains(it) }.toSet()
    }

    fun toggle(category: String) {
        if (!canEdit) return
        error = null
        var next = value
        if (next.categories.contains(category)) {
            next = next.copy(categories = next.categories.filter { it != category })
        } else {
            next = next.copy(categories = next.categories + category)
            val own = services.filter { it.category == category }
            if (!stayOnly.contains(category) && own.none { next.services[it.serviceId]?.offered ?: false }) {
                // Its usual services: the ones the server suggests, else every named one.
                val knows = own.any { it.suggested != null }
                var defaults = own.filter { if (knows) it.suggested == true else !(it.serviceId.endsWith("_other") || it.serviceId == "custom_service") }
                if (defaults.isEmpty()) defaults = own
                val map = next.services.toMutableMap()
                for (service in defaults) {
                    map[service.serviceId] = TeamOfferedService(offered = true, minutes = map[service.serviceId]?.minutes ?: service.defaultMinutes)
                }
                next = next.copy(services = map)
            }
        }
        draft = next
    }

    fun change(service: TeamSettingsService, offered: Boolean, minutes: Int) {
        if (!canEdit) return
        draft = value.copy(services = value.services + (service.serviceId to TeamOfferedService(offered = offered, minutes = minutes)))
    }

    fun save() {
        val current = value
        if (current.categories.isEmpty()) {
            error = L("Offer at least one service.")
            Toast.error(error ?: "")
            return
        }
        if (empty(current).isNotEmpty()) {
            checked = true
            error = L("Each kind of care needs at least one service. Tick one, or switch that kind of care off.")
            Toast.error(error ?: "")
            return
        }
        val on = current.categories.toSet()
        val payload = services.filter { on.contains(it.category) && (current.services[it.serviceId]?.offered ?: false) }.map {
            mapOf("serviceId" to it.serviceId, "durationMinutes" to (current.services[it.serviceId]?.minutes ?: it.defaultMinutes))
        }
        saving = true
        scope.launch {
            try {
                API.put<EmptyResponse>("/provider-setup/services", mapOf("enabledCategories" to current.categories, "services" to payload))
                draft = null; checked = false; error = null
                model.saved(workspace)
                flash.mark()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(errorText(e, fallback = L("Could not save the services.")))
            } finally {
                saving = false
            }
        }
    }

    val selected = value.categories.toSet()
    val missing = empty(value)
    val cards = model.catalog?.categories ?: emptyList()
    val losingStays = saved.categories.any { stayOnly.contains(it) } && value.categories.none { stayOnly.contains(it) }
    val box = RoundedCornerShape(Theme.boxRadius)

    TeamSaveSection(
        title = L("Services you offer"),
        subtitle = L("Bookings — yours and families’ — only offer the services you choose here.") + " " + L("Prices live in the Catalog."),
        systemImage = "tags",
        tint = Theme.lime,
        look = TeamCardLook.settings,
        state = flash.state(saving = saving, dirty = draft?.let { !it.same(saved) } ?: false),
        saveLabel = L("Save services"),
        readOnlyText = if (canEdit) null else L("Only someone who edits the workspace can change this."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { draft = null; error = null; checked = false },
        modifier = modifier,
    ) {
        if (model.catalog == null) {
            if (model.catalogFailed) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(L("Could not load the list of services."), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    Text(L("Try again"), Modifier.plainButton { scope.launch { model.load(workspace) } }, style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                }
            } else {
                TeamProgress(minHeight = 80.dp)
            }
        } else {
            TeamAdaptiveGrid(items = cards, minimum = 230.dp, spacing = 12.dp) { card ->
                CategoryCard(card, on = selected.contains(card.id), invalid = error != null && value.categories.isEmpty(), enabled = canEdit && !saving) { toggle(card.id) }
            }
            error?.let { Text(it, style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive) }
            if (losingStays) {
                Text(
                    L("Without boarding or daycare, Stays leaves the sidebar."),
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.mango, 18.0), box)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkStrong,
                )
            }
            val onCards = cards.filter { selected.contains(it.id) }
            if (onCards.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("The services of each one"), style = Theme.heading(18), color = Theme.inkStrong)
                    Text(
                        L("Tick what you book and set how long it takes. Switching a kind of care on ticks its usual services; each one needs at least one."),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
                onCards.forEach { card ->
                    androidx.compose.runtime.key(card.id) {
                        val own = services.filter { it.category == card.id }
                        val count = own.count { value.services[it.serviceId]?.offered ?: false }
                        val label = card.label ?: card.id
                        val none = missing.contains(card.id)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .border(2.dp, if (none) (if (checked) Theme.destructive else Theme.mango) else Theme.borderSoft, box)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(32.dp).background(Theme.wash(Hue.category(card.id).color, 45.0), CircleShape), contentAlignment = Alignment.Center) {
                                    Glyph(TeamServicesSection.icon(card.id), 16.dp, tint = Theme.inkStrong)
                                }
                                Text(label, Modifier.weight(1f), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                                Text(
                                    LF("%1\$d of %2\$d selected", count, own.size),
                                    Modifier
                                        .background(Theme.surfaceMuted, CapsuleShape)
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = Theme.text(12, FontWeight.SemiBold),
                                    color = Theme.inkSoft,
                                )
                            }
                            if (stayOnly.contains(card.id)) {
                                Text(
                                    L("Nights and days are charged at each space's rate, in Stays. Tick a service only if you also book it as an appointment, like an assessment."),
                                    style = Theme.text(14),
                                    color = Theme.inkSoft,
                                )
                            }
                            if (none) {
                                Text(
                                    if (canEdit) LF("Tick at least one %1\$@ service, or switch %2\$@ off. It can’t be saved with none.", label, label) else LF("No %@ service is ticked.", label),
                                    style = Theme.text(14, FontWeight.SemiBold),
                                    color = if (checked) Theme.destructive else Theme.inkStrong,
                                )
                            }
                            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                own.forEach { service ->
                                    androidx.compose.runtime.key(service.serviceId) {
                                        ServiceRow(service, value, enabled = canEdit && !saving) { offered, minutes -> change(service, offered, minutes) }
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

@Composable
private fun CategoryCard(card: TeamServiceCatalog.Category, on: Boolean, invalid: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val hue = Hue.category(card.id).color
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton(enabled = enabled, onClick = onToggle)
            .heightIn(min = 80.dp)
            .background(if (on) Theme.wash(hue, 18.0) else Theme.surface, box)
            .border(2.dp, if (on) Theme.blue else (if (invalid) Theme.destructive else Theme.borderSoft), box)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).background(Theme.wash(hue, 45.0), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(TeamServicesSection.icon(card.id), 20.dp, tint = Theme.inkStrong)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.label ?: card.id, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
            card.description?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
        }
        Tick(on = on, style = Tick.Style.choice)
    }
}

@Composable
private fun ServiceRow(service: TeamSettingsService, value: TeamOfferedDraft, enabled: Boolean, change: (Boolean, Int) -> Unit) {
    val state = value.services[service.serviceId]
    val ticked = state?.offered ?: false
    val minutes = state?.minutes ?: service.defaultMinutes
    val chip = RoundedCornerShape(Theme.chipRadius)
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (ticked) Theme.wash(Hue.sky.color, 18.0) else Theme.surface, chip)
            .border(1.dp, if (ticked) Theme.blue.copy(alpha = 0.3f) else Theme.borderSoft, chip)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .plainButton(enabled = enabled) { change(!ticked, minutes) }
                .heightIn(min = 44.dp)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tick(on = ticked, style = Tick.Style.checkbox)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(service.label, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                service.description.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft) }
            }
        }
        if (ticked) {
            AppMenu(
                label = {
                    Row(
                        Modifier
                            .size(width = 128.dp, height = 36.dp)
                            .background(Theme.inputFill, box)
                            .border(1.dp, Theme.borderSoft, box)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(TeamServicesSection.minutesLabel(minutes), Modifier.weight(1f), style = Theme.text(14), color = Theme.inkStrong, maxLines = 1)
                        Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
                    }
                },
                enabled = enabled,
                accessibilityLabel = LF("How long %@ takes", service.label),
            ) {
                picker(null, options = TeamServicesSection.durations.map { it to TeamServicesSection.minutesLabel(it) }, selection = stateOf({ minutes }, { change(true, it) }))
            }
        }
    }
}

/** "Se reserva en línea" per offered service; each switch saves at once. */
@Composable
fun TeamOnlineServicesPanel(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<TeamOnlineSettings?>(null) }
    var failed by remember { mutableStateOf(false) }
    var savingId by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            settings = API.get("/provider-online-booking/settings")
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    fun toggle(serviceId: String, on: Boolean) {
        savingId = serviceId
        scope.launch {
            try {
                settings = API.put<TeamOnlineSettings>("/provider-online-booking/services/$serviceId", mapOf("onlineBookable" to on))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                savingId = null
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val services = settings?.services ?: emptyList()
    val anyLocationOn = (settings?.locations ?: emptyList()).any { it.status == "active" && (it.allowOnlineBooking ?: false) }
    val bookable = services.count { it.onlineBookable ?: false }
    TeamPanel(
        title = L("Online booking"),
        subtitle = L("Which of your services families can book from the app. Who takes each one is set on each person’s page."),
        systemImage = "globe",
        tint = Theme.lavender,
        look = TeamCardLook.settings,
        modifier = modifier,
    ) {
        if (settings == null) {
            if (failed) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(L("The online booking settings didn’t load."), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, textAlign = TextAlign.Center)
                    PMButton(L("Try again"), systemImage = "refresh-cw", variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { load() } }
                }
            } else {
                TeamProgress(minHeight = 64.dp)
            }
        } else if (services.isEmpty()) {
            Text(L("Tick the services you offer above; each one can then be booked online."), style = Theme.text(14), color = Theme.inkSoft)
        } else {
            if (anyLocationOn) {
                Text(LP(bookable, "1 service is booked online.", "%d services are booked online."), style = Theme.text(14), color = Theme.inkSoft)
            } else {
                val blue = Theme.blue
                Text(
                    buildAnnotatedString {
                        append(LP(bookable, "1 service is booked online.", "%d services are booked online."))
                        append(" ")
                        append(L("No location takes online bookings yet."))
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = blue)) { append(L("Turn it on in Availability")) }
                    },
                    Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.availability)) },
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            TeamAdaptiveGrid(items = services, minimum = 280.dp, spacing = 8.dp) { service ->
                val minutes = service.durationMinutes ?: 0
                val length = TeamServicesSection.minutesLabel(minutes)
                Disabled(minutes <= 0 || savingId == service.serviceId) {
                    ToggleRow(
                        title = service.label,
                        subtitle = if (minutes <= 0) L("No length set: it can’t be booked online.")
                        else if (service.onlineBookable ?: false) LF("%@ · families book it online", length) else LF("%@ · only your team books it", length),
                        isOn = stateOf({ (service.onlineBookable ?: false) && minutes > 0 }, { toggle(service.serviceId, it) }),
                    )
                }
            }
        }
    }
}

// MARK: - Region and money

/** The region and money words and rules, shared with Home's "Confirma tu región y dinero". */
object TeamRegion {
    val currencies = listOf("MXN", "USD", "COP", "EUR", "ARS", "CLP", "PEN", "GTQ", "CRC", "CAD", "GBP", "BRL", "UYU", "DOP")

    /** Each language named in itself. */
    fun languageName(code: String?): String? = when (code) {
        "es" -> "Español"
        "en" -> "English"
        else -> null
    }

    fun defaultLanguage(country: String?): String? {
        val code = country?.uppercase()
        if (code.isNullOrEmpty()) return null
        return if (listOf("US", "CA", "GB", "AU", "NZ", "IE").contains(code)) "en" else "es"
    }

    fun percent(bps: Int): String {
        val value = bps / 100.0
        return if (value == Math.rint(value)) value.toInt().toString() else String.format(Locale.US, "%.2f", value).replace(Regex("0+$"), "")
    }
}

/** Where the business is and what money it counts in. */
@Composable
fun TeamRegionSection(model: TeamSettingsModel, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val flash = remember { TeamSavedFlash() }

    val current = workspace.current
    val canSeeMoney = TeamSettingsModel.canSeeMoney(workspace)
    val canEditMoney = workspace.can(Permission.billingManage)
    val canEditRegion = workspace.can(Permission.workspaceEdit) && canEditMoney
    val timezone = current?.availability?.timezone ?: "UTC"
    val language = current?.clientLanguage ?: TeamRegion.defaultLanguage(current?.country)

    @Composable
    fun MicroLabel(text: String) {
        Text(text.uppercase(appLocale), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.5), color = Theme.inkSoft)
    }

    /** The web's `SettingsRow`: the label (14 bold) and its hint, the value (14 semibold) under them on a phone. */
    @Composable
    fun SettingsRow(label: String, hint: String?, link: String? = null, value: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                if (hint != null) Text(hint, style = Theme.text(12), color = Theme.inkSoft)
                if (link != null) {
                    Text(
                        link,
                        Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.availability)) },
                        style = Theme.text(12, FontWeight.SemiBold),
                        color = Theme.blue,
                    )
                }
            }
            CompositionLocalProvider(LocalTextStyle provides Theme.text(14, FontWeight.SemiBold), LocalContentColor provides Theme.inkStrong) { value() }
        }
    }

    @Composable
    fun NotChosen() {
        Text(L("Not chosen yet"), style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium), color = Theme.inkSoft)
    }

    TeamSaveSection(
        title = L("Region and money"),
        subtitle = L("Where you are, the clock your Schedule runs on, and the currency and tax on every price, sale and bill."),
        systemImage = "earth",
        tint = Theme.mango,
        look = TeamCardLook.settings,
        state = if (flash.on) TeamSaveState.saved else TeamSaveState.clean,
        readOnlyText = if (canEditMoney) null else (if (canSeeMoney) L("Only someone who manages billing can change the currency and tax.") else L("Your role does not include prices.")),
        modifier = modifier,
        aside = {
            if (canEditMoney && model.store != null) {
                PMButton(
                    if (canEditRegion) L("Edit region and money") else L("Edit currency and tax"),
                    systemImage = "pencil",
                    variant = ButtonVariant.outline,
                    fullWidth = true,
                ) { editing = true }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MicroLabel(L("Region"))
                Column {
                    SettingsRow(L("Country"), hint = L("Sets the usual currency and tax, and how legal details are checked.")) {
                        val name = CountryPicker.name(current?.country)
                        if (name != null) Text(name) else NotChosen()
                    }
                    RowDivider()
                    val editsWorkspace = workspace.can(Permission.workspaceEdit)
                    SettingsRow(
                        L("Time zone"),
                        hint = if (editsWorkspace) null else L("Set with the opening hours, on Availability."),
                        link = if (editsWorkspace) L("Change it with your opening hours") else null,
                    ) {
                        Column {
                            Text(TeamHours.zoneLabel(timezone))
                            val now = runCatching { ZoneId.of(timezone) }.getOrNull()?.let { DateText.time(Instant.now(), it) }
                            if (now != null) Text(LF("It is %@ there now", now), style = Theme.text(12, FontWeight.Medium), color = Theme.inkSoft)
                        }
                    }
                    RowDivider()
                    SettingsRow(L("Language for clients"), hint = L("Emails and reminders go out in it when a client has not chosen their own.")) {
                        val name = TeamRegion.languageName(language)
                        if (name != null) Text(name) else NotChosen()
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MicroLabel(L("Money"))
                Column {
                    val store = model.store
                    if (!canSeeMoney) {
                        SettingsRow(L("Currency and tax"), hint = null) {
                            Text(L("Your role does not include prices."), style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium), color = Theme.inkSoft)
                        }
                    } else if (store != null) {
                        SettingsRow(L("Currency"), hint = if (store.currencyLocked) L("Set with the first bill") else L("One for the whole business: prices, bills, stays, sales and reports.")) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (store.currencyLocked) Glyph("lock", 14.dp, tint = Theme.inkSoft)
                                Text(store.currency)
                            }
                        }
                        RowDivider()
                        SettingsRow(L("Standard tax rate"), hint = L("Items can use their own rate, zero or exempt in the Catalog.")) {
                            Text(TeamRegion.percent(store.defaultTaxRateBps) + "%")
                        }
                        RowDivider()
                        SettingsRow(L("Prices include tax"), hint = if (store.pricesIncludeTax) L("The price on a tag is what the client pays.") else L("Tax is added on top of the price.")) {
                            Text(if (store.pricesIncludeTax) L("Yes") else L("No"))
                        }
                        RowDivider()
                        SettingsRow(L("Cash difference alert"), hint = L("A register close off by more than this needs a note, and the owners are told by email and in the app.")) {
                            Text(store.cashDifferenceAlertAmount?.let { Money.format(it, currency = store.currency) } ?: L("Off"))
                        }
                    } else if (model.storeFailed) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(L("Could not load the currency and tax."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                            PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { model.load(workspace) } }
                        }
                    } else {
                        TeamProgress(minHeight = 64.dp)
                    }
                }
            }
            if (canEditMoney) {
                val blue = Theme.blue
                Text(
                    buildAnnotatedString {
                        append(L("The receipt’s closing line and the missed-visit fee are in"))
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = blue)) { append(L("Store settings")) }
                        append(".")
                    },
                    Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.store)) },
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
            }
        }
    }

    if (editing) {
        AppSheet(onDismissRequest = { editing = false }) {
            model.store?.let { store ->
                TeamMoneySheet(store = store, country = current?.country, language = language, canEditRegion = canEditRegion) {
                    model.saved(workspace)
                    flash.mark()
                    Toast.show(L("Saved"))
                }
            }
        }
    }
}

/**
 * Edits the country, language, currency and tax. With the country and language it saves
 * through the setup's region (it keeps the history); without them, the store's settings.
 */
@Composable
private fun TeamMoneySheet(
    store: TeamStoreSettings,
    country: String?,
    language: String?,
    canEditRegion: Boolean,
    onSaved: suspend () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val scope = rememberCoroutineScope()
    // iOS `.onAppear`: the fields start from what is saved.
    var countryCode by remember { mutableStateOf(country?.uppercase() ?: "") }
    val clientLanguage = remember { mutableStateOf(language ?: "es") }
    val currency = remember { mutableStateOf(store.currency) }
    val rate = remember { mutableStateOf(TeamRegion.percent(store.defaultTaxRateBps)) }
    val included = remember { mutableStateOf(store.pricesIncludeTax) }
    val alertOn = remember { mutableStateOf(store.cashDifferenceAlertAmount != null) }
    val alertCents = remember { mutableStateOf<Int?>(store.cashDifferenceAlertAmount ?: 5000) }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        val normalized = rate.value.replace(",", ".").trim()
        val parsed = normalized.toDoubleOrNull()
        val rateError = if (parsed != null && parsed in 0.0..100.0) null else L("Enter a tax rate from 0 to 100.")
        val alertError = if (alertOn.value && (alertCents.value ?: 0) <= 0) L("Enter an amount above 0, or turn the alert off.") else null
        val regionChanged = canEditRegion && (countryCode != (country?.uppercase() ?: "") || clientLanguage.value != language)
        val countryError = if (regionChanged && countryCode.isEmpty()) L("Choose a country from the list.") else null
        if (!form.check(listOf("country" to countryError, "rate" to rateError, "alert" to alertError)) || parsed == null) return
        val bps = (parsed * 100).roundToInt()
        val nextCurrency = if (store.currencyLocked) store.currency else currency.value.uppercase()
        val alert: Int? = if (alertOn.value) alertCents.value else null
        saving = true
        scope.launch {
            try {
                if (regionChanged) {
                    API.put<EmptyResponse>(
                        "/provider-setup/region",
                        mapOf(
                            "country" to countryCode, "clientLanguage" to clientLanguage.value, "currency" to nextCurrency,
                            "defaultTaxRateBps" to bps, "pricesIncludeTax" to included.value, "cashDifferenceAlertAmount" to alert,
                        ),
                    )
                } else {
                    store.save(
                        mapOf(
                            "currency" to JsonPrimitive(nextCurrency), "defaultTaxRateBps" to JsonPrimitive(bps),
                            "pricesIncludeTax" to JsonPrimitive(included.value), "cashDifferenceAlertAmount" to TeamStoreSettings.cents(alert),
                        ),
                        announce = false,
                    )
                }
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("country" to "country", "defaultTaxRateBps" to "rate", "cashDifferenceAlertAmount" to "alert"))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = if (canEditRegion) L("Region and money") else L("Currency and tax"),
        subtitle = L("Used on every price, sale, bill and register close."),
        systemImage = "coins",
        accent = ModalAccent.mango,
        isDirty = true,
        form = form,
        primaryTitle = if (canEditRegion) L("Save region and money") else L("Save currency and tax"),
        isWorking = saving,
        primaryIcon = "save",
        onPrimary = { save() },
    ) {
        if (canEditRegion) {
            CountryField(code = countryCode, key = "country", hint = L("Changing it does not change your currency or tax: check them below."), form = form) { countryCode = it }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuField(label = L("Language for clients"), options = listOf("es" to "Español", "en" to "English"), selection = clientLanguage)
                Text(L("Used when a client has not chosen one."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        if (store.currencyLocked) {
            FormField(label = L("Currency")) {
                FieldChrome {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("lock", 14.dp, tint = Theme.inkSoft)
                        Text(store.currency, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    }
                }
            }
            NoticeCard(
                tone = NoticeCard.Tone.info,
                title = L("Set with the first bill"),
                message = LF("Everything the business charges is in %@: prices, bills, stays, sales and reports. Now that money has been recorded in it, it no longer changes.", store.currency) +
                    " " + L("If it is the wrong currency, write to") + " contact@pawmiaw.com " + L("and we will help you move it."),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuField(label = L("Currency"), options = (TeamRegion.currencies + store.currency).toSet().sorted().map { it to it }, selection = currency)
                Text(L("The whole business uses it. Changing it relabels Catalog prices without converting them; the first bill fixes it."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        PMTextField(L("Standard tax rate (%)"), text = rate, key = "rate", form = form, hint = L("e.g. 16 for IVA in Mexico."), keyboard = KeyboardType.Decimal)
        ToggleRow(title = L("Prices include tax"), subtitle = if (included.value) L("The price on a tag is what the client pays.") else L("Tax is added on top of the price."), isOn = included)
        ToggleRow(title = L("Cash difference alert"), subtitle = L("A register close off by more than this needs a note, and the owners are told by email and in the app."), isOn = alertOn)
        if (alertOn.value) {
            MoneyField(label = LF("Alert above (%@)", (if (store.currencyLocked) store.currency else currency.value).uppercase()), cents = alertCents, key = "alert", form = form)
        }
    }
}

// MARK: - Legal details

/** The legal name, tax id and régimen that print on bills and receipts, and — in Mexico — the Registro SENASICA. */
@Composable
fun TeamLegalSection(model: TeamSettingsModel, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val flash = remember { TeamSavedFlash() }
    val legalName = remember { mutableStateOf("") }
    val taxId = remember { mutableStateOf("") }
    val taxRegime = remember { mutableStateOf("") }
    val senasica = remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val logo = remember { mutableStateOf(PhotoDraft()) }
    var saving by remember { mutableStateOf(false) }

    val canEdit = workspace.can(Permission.workspaceEdit)
    val mexico = workspace.current?.country?.uppercase() == "MX"
    val legal = model.setup?.legal
    val saved = listOf(legal?.legalName ?: "", legal?.taxId ?: "", legal?.taxRegime ?: "", model.setup?.senasicaRegistration ?: "")
    fun t(s: String) = s.trim()
    val dirty = t(legalName.value) != t(saved[0]) || t(taxId.value) != t(saved[1]) || t(taxRegime.value) != t(saved[2]) || t(senasica.value) != t(saved[3]) || !logo.value.isEmpty

    fun fill() {
        legalName.value = saved[0]; taxId.value = saved[1]; taxRegime.value = saved[2]; senasica.value = saved[3]
        logo.value = PhotoDraft()
        form.errors = emptyMap()
    }

    // iOS `.onAppear` and `.onChange(of: model.setup == nil)`: the fields fill once the setup is in.
    val hasSetup = model.setup != null
    LaunchedEffect(hasSetup) {
        if (!started && hasSetup) {
            fill()
            started = true
        }
    }

    fun normalizedTaxId(value: String): String = value.replace(Regex("[\\s-]+"), "").uppercase()

    fun save() {
        val id = taxId.value.trim()
        val rfcError = L("Check the RFC: 12 characters for a company or 13 for a person, like XAXX010101000.")
        var taxError: String? = null
        if (id.length > 40) {
            taxError = L("Keep the tax id under 40 characters.")
        } else if (id.isNotEmpty() && mexico && !Regex("^[A-ZÑ&]{3,4}\\d{6}[A-Z0-9]{3}$").matches(normalizedTaxId(id)) && id.uppercase() != saved[1].trim().uppercase()) {
            taxError = rfcError
        }
        if (!form.check(listOf(
                "legalName" to (if (legalName.value.trim().length > 200) L("Keep the legal name under 200 characters.") else null),
                "taxId" to taxError,
                "taxRegime" to (if (taxRegime.value.trim().length > 120) L("Keep the tax regime under 120 characters.") else null),
                "senasica" to (if (senasica.value.trim().length > 60) L("Keep the SENASICA registration under 60 characters.") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                try {
                    API.put<EmptyResponse>(
                        "/provider-setup/legal",
                        mapOf(
                            "legalName" to legalName.value.nonEmpty,
                            "taxId" to (if (id.isEmpty()) null else (if (mexico) normalizedTaxId(id) else id.uppercase())),
                            "taxRegime" to taxRegime.value.nonEmpty,
                            "senasicaRegistration" to senasica.value.nonEmpty,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if ((e as? APIError)?.code == "invalid_tax_id") {
                        form.check(listOf("taxId" to rfcError))
                    } else {
                        form.apply(e, known = mapOf("legalName" to "legalName", "taxId" to "taxId", "taxRegime" to "taxRegime", "senasicaRegistration" to "senasica"))
                    }
                    return@launch
                }
                // The logo goes up (or comes off) after the details; one that fails never loses them.
                try {
                    val data = logo.value.data
                    if (data != null) {
                        ImageUpload.upload<EmptyResponse>(data, to = "/provider-setup/receipt-logo", field = "logo")
                    } else if (logo.value.removed) {
                        API.send("DELETE", "/provider-setup/receipt-logo")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.error(errorText(e, fallback = L("Saved, but the receipt logo could not be uploaded.")))
                }
                model.saved(workspace)
                fill()
                flash.mark()
            } finally {
                saving = false
            }
        }
    }

    TeamSaveSection(
        title = L("Legal details"),
        subtitle = L("What prints on your bills, receipts and prescriptions. All optional: leave empty what you don’t use."),
        systemImage = "landmark",
        tint = Theme.lavender,
        look = TeamCardLook.settings,
        state = flash.state(saving = saving, dirty = dirty),
        saveLabel = L("Save legal details"),
        readOnlyText = if (canEdit) null else L("Only someone who edits the workspace can see and change the legal details."),
        onSave = if (canEdit) ({ save() }) else null,
        onDiscard = { fill() },
        modifier = modifier,
    ) {
        if (!canEdit) {
            Text(L("They print on what your business hands to clients. Ask the owner or an administrator if one needs changing."), style = Theme.text(14), color = Theme.inkSoft)
        } else if (model.setup == null) {
            if (model.setupFailed) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(L("Could not load the legal details."), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                    PMButton(L("Try again"), variant = ButtonVariant.outline) { scope.launch { model.load(workspace) } }
                }
            } else {
                TeamProgress(minHeight = 80.dp)
            }
        } else {
            FormScroll(form) {
                Disabled(saving) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // "Logo en recibos": the one saved, else the profile logo.
                        val receipt = legal?.receiptLogoUrl
                        PhotoField(
                            label = L("Receipt logo"),
                            hint = if (receipt == null || logo.value.removed) L("Receipts print the profile logo.") else L("Receipts print this logo instead of the profile’s."),
                            currentURL = receipt ?: model.setup?.business?.logoUrl,
                            draft = logo,
                            allowsRemove = receipt != null,
                            addTitle = L("Add a logo"),
                            changeTitle = L("Change logo"),
                            removedNote = L("Receipts print the profile logo."),
                        )
                        PMTextField(L("Legal name"), text = legalName, key = "legalName", form = form, hint = L("The registered name, when it differs from the business name."))
                        PMTextField(
                            if (mexico) L("RFC") else L("Tax id"),
                            text = taxId,
                            key = "taxId",
                            form = form,
                            prompt = if (mexico) "XAXX010101000" else null,
                            hint = if (mexico) L("12 characters for a company, 13 for a person. Saved in capitals, without spaces.") else L("The number your tax authority gave the business."),
                            capitalization = KeyboardCapitalization.Characters,
                        )
                        PMTextField(
                            L("Tax regime"),
                            text = taxRegime,
                            key = "taxRegime",
                            form = form,
                            hint = if (mexico) L("As on your tax certificate, e.g. 612 Personas Físicas con Actividades Empresariales.") else L("How the business is registered for tax, if your receipts must say it."),
                        )
                        if (mexico || saved[3].isNotEmpty() || workspace.current?.country == null) {
                            PMTextField(
                                L("SENASICA registration"),
                                text = senasica,
                                key = "senasica",
                                form = form,
                                hint = if (mexico) L("The establishment’s registration with SENASICA, for prescriptions.") else L("Mexico only. Leave it empty elsewhere."),
                            )
                        }
                        val box = RoundedCornerShape(Theme.boxRadius)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Theme.surfaceMuted, box)
                                .border(1.dp, Theme.borderSoft, box)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(L("Where they print").uppercase(appLocale), style = brandText(10.9, FontWeight.ExtraBold).tracking(1.5), color = Theme.inkSoft)
                            PrintsOn("file-text", L("Bills and receipts"), if (mexico) L("Legal name, RFC and tax regime, at the top of the PDF.") else L("Legal name, tax id and tax regime, at the top of the PDF."))
                            PrintsOn("pill", L("Prescriptions"), if (mexico) L("Legal name and the SENASICA registration.") else L("Legal name."))
                            Text(L("The address and phone on them are the ones in Contact and address."), style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrintsOn(icon: String, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Glyph(icon, 16.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Text(text, style = Theme.text(14), color = Theme.inkSoft)
        }
    }
}
