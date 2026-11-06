package com.pawmiaw.business.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.team.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

//
//  HomeSetupSheets.kt (iOS HomeSetupSheets.swift)
//
//  What Home sets up in place (web setup/region-card.tsx and
//  setup/setup-checklist.tsx): the one-time "Confirma tu región y dinero"
//  for a workspace set up before onboarding asked (backend
//  docs/onboarding.md), and the checklist's sheets — prices for the services
//  offered, the no-show and late-cancel policy, the cash drawer, the receipt
//  and legal details, your own credential, and who on the team still lacks
//  something. Each saves to the same endpoint the web's does.
//

// MARK: - What the setup reads (GET /provider-setup)

@Serializable
data class HomeSetupState(
    val needsRegionConfirmation: Boolean? = null,
    val canEdit: Boolean? = null,
    val canEditRegion: Boolean? = null,
    val business: Business? = null,
    val region: Region? = null,
    val location: Location? = null,
    val legal: Legal? = null,
) {
    @Serializable
    data class Service(
        val serviceId: String,
        val offered: Boolean? = null,
        val priceAmount: Int? = null,
    )

    @Serializable
    data class Business(
        val name: String? = null,
        val logoUrl: String? = null,
        val services: List<Service>? = null,
    )

    @Serializable
    data class Region(
        val confirmed: Boolean? = null,
        val country: String? = null,
        val currency: String? = null,
        val currencyLocked: Boolean? = null,
        val defaultTaxRateBps: Int? = null,
        val pricesIncludeTax: Boolean? = null,
        val clientLanguage: String? = null,
    )

    @Serializable
    data class Availability(val timezone: String? = null)

    @Serializable
    data class Location(
        val id: String? = null,
        val name: String? = null,
        val timezoneConfirmed: Boolean? = null,
        val availability: Availability? = null,
    )

    @Serializable
    data class Legal(
        val legalName: String? = null,
        val taxId: String? = null,
        val taxRegime: String? = null,
        val receiptLogoUrl: String? = null,
    )
}

/** What a country starts with (`GET /provider-country-defaults`). */
@Serializable
data class HomeCountryDefaults(
    val countries: List<Entry>? = null,
) {
    @Serializable
    data class Entry(
        val country: String? = null,
        val currency: String? = null,
        val defaultTaxRateBps: Int? = null,
        val pricesIncludeTax: Boolean? = null,
        val language: String? = null,
        val timezone: String? = null,
    )
}

/** The checklist's sheets, opened over Home. */
sealed class HomeSetupSheet {
    data object region : HomeSetupSheet()
    data object prices : HomeSetupSheet()
    data object policy : HomeSetupSheet()
    data object cashDrawer : HomeSetupSheet()
    data object receipt : HomeSetupSheet()
    data object credential : HomeSetupSheet()
    data class teamSetup(val members: List<HomeChecklistMember>) : HomeSetupSheet()

    val id: String
        get() = when (this) {
            region -> "region"
            prices -> "prices"
            policy -> "policy"
            cashDrawer -> "cash-drawer"
            receipt -> "receipt"
            credential -> "credential"
            is teamSetup -> "team-setup"
        }

    companion object {
        /** The checklist item done in this sheet (null for an item done on its own page). */
        fun forItem(item: HomeChecklistItem): HomeSetupSheet? = when (item.id) {
            "prices" -> prices
            "policy" -> policy
            "cash_drawer" -> cashDrawer
            "receipt" -> receipt
            "credentials" -> if (item.mine == false) null else credential
            "team_setup" -> teamSetup(item.members ?: emptyList())
            else -> null
        }
    }

    @Composable
    fun view(actions: HomeActions) {
        when (this) {
            region -> HomeRegionSheet()
            prices -> HomePricesSheet()
            policy -> HomePolicySheet()
            cashDrawer -> HomeCashDrawerSheet()
            receipt -> HomeReceiptSheet()
            credential -> TeamCredentialSheet(
                memberId = "me", isSelf = true, country = Workspace.shared.current?.country, businessAddress = Workspace.shared.current?.address,
                reason = L("Add your professional credential to sign prescriptions."),
            ) { announceDataChanged() }
            is teamSetup -> HomeTeamSetupSheet(members = members) { id -> actions.go(ProviderRoute.staffMember(id)) }
        }
    }
}

// MARK: - Region and money (a workspace set up before onboarding asked)

/** "Confirma tu región y dinero": a one-time card; nothing stops meanwhile. */
@Composable
fun HomeRegionCard(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val actions = LocalHomeActions.current
    val canEditRegion = workspace.can(Permission.billingManage)
    Card(padding = 16.dp, tint = Theme.mango, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Glyph("earth", 20.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("Confirm your region and money"), style = brandHeading(20), color = Theme.inkStrong)
                Text(
                    L("Your workspace was set up before we asked. Choose your time zone yourself and check the money — nothing stops meanwhile."),
                    style = Theme.text(14), color = Theme.inkSoft,
                )
                if (canEditRegion) {
                    PMButton(L("Confirm@region"), systemImage = "save", size = ButtonSize.compact, modifier = Modifier.padding(top = 8.dp)) {
                        actions?.sheet = HomeSheet.setup(HomeSetupSheet.region)
                    }
                } else {
                    Text(
                        L("The owner or whoever manages billing confirms the money. Ask them to open Home."), Modifier.padding(top = 8.dp),
                        style = Theme.text(14), color = Theme.inkSoft,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeRegionSheet() {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var state by remember { mutableStateOf<HomeSetupState?>(null) }
    var countries by remember { mutableStateOf<List<HomeCountryDefaults.Entry>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var timezone by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    val currency = remember { mutableStateOf("MXN") }
    val tax = remember { mutableStateOf("") }
    val included = remember { mutableStateOf(true) }
    val language = remember { mutableStateOf("es") }
    var pickingZone by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val locked = state?.region?.currencyLocked == true

    /** A country's defaults over the draft; a fixed currency stays. */
    fun pick(code: String) {
        country = code
        form.clear("country")
        val preset = countries.firstOrNull { it.country == code }
        if (preset == null) {
            language.value = TeamRegion.defaultLanguage(code) ?: language.value
            return
        }
        if (state?.region?.currencyLocked != true) preset.currency?.let { currency.value = it }
        tax.value = TeamRegion.percent(preset.defaultTaxRateBps ?: 0)
        included.value = preset.pricesIncludeTax ?: included.value
        language.value = preset.language ?: language.value
    }

    suspend fun load() {
        failed = false
        try {
            coroutineScope {
                val setupCall = async { API.get<HomeSetupState>("/provider-setup") }
                val defaultsCall = async { runCatching { API.get<HomeCountryDefaults>("/provider-country-defaults") }.getOrNull() }
                val loaded = setupCall.await()
                countries = defaultsCall.await()?.countries ?: emptyList()
                val region = loaded.region
                timezone = if (loaded.location?.timezoneConfirmed == true) (loaded.location?.availability?.timezone ?: "") else ""
                country = region?.country?.uppercase() ?: ""
                currency.value = region?.currency ?: workspace.currency
                tax.value = TeamRegion.percent(region?.defaultTaxRateBps ?: 0)
                included.value = region?.pricesIncludeTax ?: true
                language.value = region?.clientLanguage ?: LocalizationManager.currentLanguage.rawValue
                if (region?.confirmed != true && country.isNotEmpty()) pick(country)
                state = loaded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
        }
    }

    fun save() {
        val parsed = tax.value.replace(",", ".").replace("%", "").trim().toDoubleOrNull()
        if (!form.check(listOf(
                "timezone" to (if (timezone.isEmpty()) L("Choose the time zone your hours are in.") else null),
                "country" to (if (country.isEmpty()) L("Choose a country from the list.") else null),
                "tax" to (if (parsed != null && parsed in 0.0..100.0) null else L("Enter the tax rate as a percentage, like 16.")),
            )) || parsed == null) return
        saving = true
        scope.launch {
            try {
                API.put<EmptyResponse>(
                    "/provider-setup/region",
                    mapOf(
                        "country" to country, "currency" to currency.value, "defaultTaxRateBps" to Math.round(parsed * 100).toInt(),
                        "pricesIncludeTax" to included.value, "clientLanguage" to language.value, "timezone" to timezone,
                    ),
                )
                Toast.show(L("Region and money confirmed."))
                workspace.refresh()
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e, known = mapOf("country" to "country", "defaultTaxRateBps" to "tax", "timezone" to "timezone"))
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { if (state == null) load() }

    ProviderModal(
        title = L("Confirm your region and money"), subtitle = L("It sets \"today\", your hours, reminders and the day's cash."),
        systemImage = "earth", accent = ModalAccent.mango,
        isDirty = state != null, form = form, primaryTitle = if (state?.canEditRegion == false) null else L("Confirm@region"),
        isWorking = saving, primaryIcon = "save", onPrimary = { save() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        val loaded = state
        if (loaded != null) {
            if (loaded.canEditRegion == false) {
                Text(L("The owner or whoever manages billing confirms the money. Ask them to open Home."), style = Theme.text(14), color = Theme.inkSoft)
            } else {
                // MARK: The fields
                val saved = loaded.location?.availability?.timezone
                val device = ZoneId.systemDefault().id
                val preset = countries.firstOrNull { it.country == country }
                FormField(label = L("Time zone"), key = "timezone", hint = L("It sets \"today\", your hours, reminders and the day's cash."), form = form, required = true) {
                    FieldChrome(invalid = form.error("timezone") != null, modifier = Modifier.plainButton { pickingZone = true }) {
                        Text(
                            if (timezone.isEmpty()) L("Choose the time zone") else TeamHours.zoneLabel(timezone),
                            Modifier.weight(1f), color = if (timezone.isEmpty()) Theme.inkSoft else Theme.inkStrong,
                        )
                        Glyph("chevrons-up-down", 16.dp, tint = Theme.inkSoft)
                    }
                }
                FlowLayout(spacing = 6.dp) {
                    if (saved != null && saved != "UTC") {
                        FilterChip(title = LF("Saved now: %@", TeamHours.zoneLabel(saved)), isSelected = timezone == saved) {
                            timezone = saved
                            form.clear("timezone")
                        }
                    }
                    FilterChip(title = LF("This device says: %@", TeamHours.zoneLabel(device)), isSelected = timezone == device) {
                        timezone = device
                        form.clear("timezone")
                    }
                    val zone = preset?.timezone
                    if (zone != null && zone != device && zone != saved) {
                        FilterChip(title = "${CountryPicker.name(country) ?: country}: ${TeamHours.zoneLabel(zone)}", isSelected = timezone == zone) {
                            timezone = zone
                            form.clear("timezone")
                        }
                    }
                }
                CountryField(
                    code = country, key = "country",
                    hint = if (country.isEmpty() || preset != null) L("We fill in its usual currency, tax and language. Check each one.")
                    else L("No preset for this country yet. Choose your currency, tax and client language below."),
                    form = form,
                ) { code -> pick(code) }
                if (locked) {
                    FormField(label = L("Currency")) {
                        FieldChrome {
                            Glyph("lock", 16.dp, tint = Theme.inkStrong)
                            Spacer(Modifier.width(8.dp))
                            Text(currency.value, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                        }
                    }
                    Text(
                        LF("Everything the business charges is in %@. Now that money has been recorded in it, it no longer changes.", currency.value),
                        style = Theme.captionFont, color = Theme.inkSoft,
                    )
                } else {
                    MenuField(
                        label = L("Currency"), options = (TeamRegion.currencies + currency.value).toSet().sorted().map { it to it },
                        selection = currency, required = true,
                    )
                    Text(L("Everything is charged in it. It is fixed once you record your first bill."), style = Theme.captionFont, color = Theme.inkSoft)
                }
                PMTextField(
                    L("Sales tax"), text = tax, key = "tax", form = form, hint = L("The standard rate (IVA, VAT). 0 if you don't charge it."),
                    keyboard = KeyboardType.Decimal, required = true,
                )
                ToggleRow(
                    title = L("My prices already include tax"),
                    subtitle = if (included.value) L("A price of 100 is what the client pays; the tax is inside it.") else L("The tax is added on top of each price."),
                    isOn = included,
                )
                FormField(label = L("Language of emails to your clients"), hint = L("Used when a client hasn't chosen one."), required = true) {
                    ChoiceCards(options = listOf(Triple("es", "Español", null), Triple("en", "English", null)), selection = language)
                }
            }
        } else if (failed) {
            ErrorState(message = L("Could not load your region.")) { load() }
        } else {
            LoadingState()
        }
    }
    if (pickingZone) {
        AppSheet(onDismissRequest = { pickingZone = false }) {
            TeamTimeZonePicker(selection = timezone) {
                timezone = it
                form.clear("timezone")
            }
        }
    }
}

// MARK: - Prices for the services offered

@Serializable
private data class HomePricesCatalog(val services: List<Service>? = null) {
    @Serializable
    data class Service(val id: String, val label: String? = null)
}

@Composable
private fun HomePricesSheet() {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var unpriced by remember { mutableStateOf<List<HomeSetupState.Service>>(emptyList()) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val prices = remember { mutableStateMapOf<String, Int?>() }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        failed = false
        try {
            coroutineScope {
                val setupCall = async { API.get<HomeSetupState>("/provider-setup") }
                val catalogCall = async { runCatching { API.get<HomePricesCatalog>("/appointment-services", mapOf("scope" to "all")) }.getOrNull() }
                val state = setupCall.await()
                unpriced = (state.business?.services ?: emptyList()).filter { it.offered == true && (it.priceAmount ?: 0) <= 0 }
                val byId = LinkedHashMap<String, String>()
                for (service in catalogCall.await()?.services ?: emptyList()) if (!byId.containsKey(service.id)) byId[service.id] = service.label ?: service.id
                labels = byId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
        }
        loaded = true
    }

    fun save() {
        // $0 is "no price yet", not a price: this sheet exists to set one.
        val rules = unpriced.mapNotNull { service ->
            val value = prices[service.serviceId] ?: return@mapNotNull null
            service.serviceId to (if (value <= 0) L("Set a price above 0.") else null)
        }
        if (!form.check(rules)) return
        val services = unpriced.mapNotNull { service ->
            val value = prices[service.serviceId]
            if (value == null || value <= 0) null else mapOf("serviceId" to service.serviceId, "priceAmount" to value)
        }
        if (services.isEmpty()) {
            dismiss()
            return
        }
        saving = true
        scope.launch {
            try {
                API.put<EmptyResponse>("/provider-setup/prices", mapOf("services" to services))
                Toast.show(L("Prices saved."))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e)
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { if (!loaded) load() }

    ProviderModal(
        title = L("Prices for your services"), subtitle = L("What each visit bills. It creates the service in your Catalog."),
        systemImage = "badge-dollar-sign", accent = ModalAccent.aqua,
        isDirty = prices.values.any { it != null }, form = form, primaryTitle = if (unpriced.isEmpty()) null else L("Save"),
        isWorking = saving, primaryIcon = "save", onPrimary = { save() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        if (!loaded) {
            LoadingState()
        } else if (failed) {
            ErrorState(message = L("Could not load your services.")) { load() }
        } else if (unpriced.isEmpty()) {
            EmptyState(systemImage = "badge-dollar-sign", title = L("Every service you offer has a price."), accent = Theme.blue)
        } else {
            for (service in unpriced) {
                key(service.serviceId) {
                    val id = service.serviceId
                    val cents = remember(id) { stateOf({ prices[id] }, { value: Int? -> prices[id] = value }) }
                    MoneyField(label = labels[id] ?: id, cents = cents, key = id, form = form, hint = L("No price"))
                }
            }
        }
    }
}

// MARK: - No-show and late-cancel policy

@Composable
private fun HomePolicySheet() {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var settings by remember { mutableStateOf<TeamStoreSettings?>(null) }
    var failed by remember { mutableStateOf(false) }
    val fee = remember { mutableStateOf<Int?>(null) }
    val window = remember { mutableStateOf(24) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        failed = false
        try {
            val loaded = TeamStoreSettings.load()
            fee.value = loaded.raw["noShowFeeAmount"].int?.takeIf { it > 0 }
            window.value = loaded.raw["lateCancelWindowHours"].int ?: 24
            settings = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
        }
    }

    fun save() {
        val current = settings ?: return
        if (!form.check(listOf("fee" to (if ((fee.value ?: 1) < 0) L("Check this amount.") else null)))) return
        saving = true
        scope.launch {
            try {
                current.save(mapOf(
                    "noShowFeeAmount" to TeamStoreSettings.cents(fee.value?.takeIf { it > 0 }),
                    "lateCancelWindowHours" to JsonPrimitive(window.value),
                ))
                Toast.show(L("Saved"))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e, known = mapOf("noShowFeeAmount" to "fee"))
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { if (settings == null) load() }

    ProviderModal(
        title = L("No-show and late-cancel policy"),
        subtitle = L("The fee is offered when a visit is missed or cancelled late — never charged on its own."),
        systemImage = "circle-slash", accent = ModalAccent.aqua,
        isDirty = settings != null, form = form, primaryTitle = if (settings == null) null else L("Save"),
        isWorking = saving, primaryIcon = "save", onPrimary = { save() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        if (settings != null) {
            MoneyField(label = L("Fee"), cents = fee, key = "fee", form = form, hint = L("Leave empty for no fee."))
            MenuField(
                label = L("A cancellation is late when it comes less than"),
                options = listOf(2, 4, 12, 24, 48).map { it to LF("%d hours before", it) },
                selection = window,
            )
            // The family sees the business's terms (docs/family-money.md §5): before booking and on each visit.
            Text(L("Families see this fee, and until when they can cancel without it, when they book and on every visit."), style = Theme.text(14), color = Theme.inkSoft)
        } else if (failed) {
            ErrorState(message = L("Could not load the store settings.")) { load() }
        } else {
            LoadingState()
        }
    }
}

// MARK: - Cash drawer

@Composable
private fun HomeCashDrawerSheet() {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var settings by remember { mutableStateOf<TeamStoreSettings?>(null) }
    var failed by remember { mutableStateOf(false) }
    val starting = remember { mutableStateOf<Int?>(null) }
    val alert = remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        failed = false
        try {
            val loaded = TeamStoreSettings.load()
            starting.value = loaded.raw["openingCashDefault"].int
            alert.value = loaded.cashDifferenceAlertAmount?.takeIf { it > 0 }
            settings = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
        }
    }

    fun save() {
        val current = settings ?: return
        saving = true
        scope.launch {
            try {
                current.save(mapOf(
                    "cashDifferenceAlertAmount" to TeamStoreSettings.cents(alert.value),
                    "openingCashDefault" to TeamStoreSettings.cents(starting.value ?: 0),
                ))
                Toast.show(L("Saved"))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e, known = mapOf("cashDifferenceAlertAmount" to "alert", "openingCashDefault" to "starting"))
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { if (settings == null) load() }

    ProviderModal(
        title = L("Cash drawer"), systemImage = "wallet", accent = ModalAccent.aqua, isDirty = settings != null, form = form,
        primaryTitle = if (settings == null) null else L("Save"), isWorking = saving, primaryIcon = "save", onPrimary = { save() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        if (settings != null) {
            MoneyField(
                label = L("Starting cash"), cents = starting, key = "starting", form = form,
                hint = L("What the drawer starts the first shift with. After that, each close leaves the next one's."),
            )
            MoneyField(
                label = L("Warn me when the cash count is off by more than"), cents = alert, key = "alert", form = form,
                hint = L("When a register closes off by more, it asks for a note and tells the owners. Leave empty for no warning."),
            )
        } else if (failed) {
            ErrorState(message = L("Could not load the store settings.")) { load() }
        } else {
            LoadingState()
        }
    }
}

// MARK: - Receipt and legal details

/** Common Mexican tax regimes (SAT codes); anything else can be typed. */
private val homeReceiptRegimes = listOf(
    "601 General de Ley Personas Morales",
    "603 Personas Morales con Fines no Lucrativos",
    "605 Sueldos y Salarios",
    "606 Arrendamiento",
    "612 Personas Físicas con Actividades Empresariales y Profesionales",
    "621 Incorporación Fiscal",
    "625 Actividades Empresariales con ingresos a través de Plataformas Tecnológicas",
    "626 Régimen Simplificado de Confianza",
)

@Composable
private fun HomeReceiptSheet() {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var state by remember { mutableStateOf<HomeSetupState?>(null) }
    var failed by remember { mutableStateOf(false) }
    val legalName = remember { mutableStateOf("") }
    val taxId = remember { mutableStateOf("") }
    val taxRegime = remember { mutableStateOf("") }
    val footer = remember { mutableStateOf("") }
    val logo = remember { mutableStateOf(PhotoDraft()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val mexico = (state?.region?.country ?: workspace.current?.country)?.uppercase() == "MX"

    suspend fun load() {
        failed = false
        try {
            coroutineScope {
                val setupCall = async { API.get<HomeSetupState>("/provider-setup") }
                val storeCall = async { runCatching { TeamStoreSettings.load() }.getOrNull() }
                val loaded = setupCall.await()
                legalName.value = loaded.legal?.legalName ?: ""
                taxId.value = loaded.legal?.taxId ?: ""
                taxRegime.value = loaded.legal?.taxRegime ?: ""
                footer.value = storeCall.await()?.raw?.get("receiptFooter").string ?: ""
                state = loaded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
        }
    }

    fun save() {
        val current = state ?: return
        if (!form.check(listOf(
                "legalName" to (if (legalName.value.length > 200) L("Keep the legal name under 200 characters.") else null),
                "taxId" to (if (taxId.value.trim().length > 40) L("Keep the tax id under 40 characters.") else null),
                "receiptFooter" to (if (footer.value.length > 500) L("Keep the footer under 500 characters.") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>("legalName" to legalName.value.nonEmpty, "taxId" to taxId.value.nonEmpty, "taxRegime" to taxRegime.value.nonEmpty)
                if (current.canEditRegion == true) body["receiptFooter"] = footer.value.nonEmpty
                try {
                    API.put<EmptyResponse>("/provider-setup/legal", body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if ((e as? APIError)?.code == "invalid_tax_id") {
                        form.check(listOf("taxId" to L("Check the RFC: 12 characters for a company or 13 for a person, like XAXX010101000.")))
                    } else {
                        form.apply(e, known = mapOf("legalName" to "legalName", "taxId" to "taxId", "taxRegime" to "taxRegime", "receiptFooter" to "receiptFooter"))
                    }
                    return@launch
                }
                // The logo goes up after the details; one that fails never loses them.
                logo.value.data?.let { data ->
                    try {
                        ImageUpload.upload<EmptyResponse>(data, to = "/provider-setup/receipt-logo", field = "logo")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Toast.error(errorText(e, L("Saved, but the receipt logo could not be uploaded.")))
                    }
                }
                Toast.show(L("Saved"))
                announceDataChanged()
                dismiss()
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { if (state == null) load() }

    ProviderModal(
        title = L("Receipt and legal details"), subtitle = L("What your receipts and, later, your invoices carry."),
        systemImage = "receipt-text", accent = ModalAccent.aqua,
        isDirty = state != null, form = form, primaryTitle = if (state == null) null else L("Save"),
        isWorking = saving, primaryIcon = "save", onPrimary = { save() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        val loaded = state
        if (loaded != null) {
            PhotoField(
                label = L("Receipt logo"), currentURL = loaded.legal?.receiptLogoUrl ?: loaded.business?.logoUrl, draft = logo,
                allowsRemove = false, addTitle = L("Add a logo"), changeTitle = L("Change logo"),
            )
            PMTextField(
                L("Legal name"), text = legalName, key = "legalName", form = form, prompt = L("e.g. Veterinaria Sol SA de CV"),
                hint = L("As it appears with your tax authority."),
            )
            PMTextField(if (mexico) L("RFC") else L("Tax id"), text = taxId, key = "taxId", form = form, capitalization = KeyboardCapitalization.Characters)
            PMTextField(if (mexico) L("Régimen fiscal") else L("Tax regime"), text = taxRegime, key = "taxRegime", form = form)
            if (mexico) {
                FlowLayout(spacing = 6.dp) {
                    for (regime in homeReceiptRegimes) {
                        key(regime) {
                            FilterChip(
                                title = regime.take(3), isSelected = taxRegime.value == regime,
                                modifier = Modifier.semantics { contentDescription = regime },
                            ) { taxRegime.value = regime }
                        }
                    }
                }
            }
            if (loaded.canEditRegion == true) {
                PMTextField(
                    L("Receipt footer"), text = footer, key = "receiptFooter", form = form,
                    hint = L("A thank-you, your return policy, your hours."), multiline = true,
                )
            }
        } else if (failed) {
            ErrorState(message = L("Could not load the legal details.")) { load() }
        } else {
            LoadingState()
        }
    }
}

// MARK: - Your team's profiles

@Composable
private fun HomeTeamSetupSheet(members: List<HomeChecklistMember>, open: (String) -> Unit) {
    fun gap(id: String): String = when (id) {
        "signature" -> L("No signature adopted")
        "credential" -> L("No credential to prescribe")
        else -> L("Hasn’t opened their welcome")
    }

    val box = RoundedCornerShape(Theme.boxRadius)
    ProviderModal(
        title = L("Your team’s profiles"),
        subtitle = L("Each person adds their own signature and credentials from their welcome; a reminder waits on their Home."),
        systemImage = "user-check", accent = ModalAccent.aqua, cancelTitle = L("Close"),
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        if (members.isEmpty()) {
            EmptyState(systemImage = "user-check", title = L("Everyone on your team has their signature and credentials."), accent = Theme.blue)
        } else {
            for (member in members) {
                key(member.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainButton { open(member.memberId) }
                            .background(Theme.surface, box)
                            .border(1.dp, Theme.borderSoft, box)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(member.name ?: "—", style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlowLayout(spacing = 6.dp) {
                                for (id in member.gaps ?: emptyList()) key(id) { Chip(text = gap(id), tone = Chip.Tone.warn) }
                            }
                        }
                        Text(L("See their profile"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                    }
                }
            }
        }
    }
}
