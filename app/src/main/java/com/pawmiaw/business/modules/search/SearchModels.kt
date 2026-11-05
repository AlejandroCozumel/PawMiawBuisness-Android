package com.pawmiaw.business.search

import com.pawmiaw.business.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

//
//  SearchModels.kt (iOS SearchModels.swift)
//
//  The workspace's global search, its rules (web lib/search-palette.ts):
//  what `GET /provider-search` answers, which result groups a role opens,
//  the quick actions and pages a member may open, what this device
//  remembers, the groups in order and where each choice leads.
//

// MARK: - What the server answers

@Serializable
data class SearchPet(
    val id: String,
    val clientId: String,
    val name: String,
    val species: String? = null,
    val breed: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val ownerName: String? = null,
    /** Only when the microchip is what matched. */
    val microchip: String? = null,
    val matchedBy: String? = null,
)

@Serializable
data class SearchClientPet(
    val id: String,
    val name: String,
    val species: String? = null,
)

@Serializable
data class SearchClient(
    val id: String,
    val name: String,
    val email: String? = null,
    val phone: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val pets: List<SearchClientPet>? = null,
    val petCount: Int? = null,
    val matchedBy: String? = null,
)

/** A patient waiting for admission or in the hospital (opens its hospitalization). */
@Serializable
data class SearchHospitalPatient(
    /** The hospitalization. */
    val id: String,
    val status: String? = null,
    val emergency: Boolean? = null,
    val triagePriority: String? = null,
    val roomName: String? = null,
    val cageLabel: String? = null,
    val petId: String? = null,
    val petName: String,
    val petSpecies: String? = null,
    val petPhotoUrl: String? = null,
    val clientId: String? = null,
    val clientName: String? = null,
    /** Only when the business has two or more locations. */
    val locationName: String? = null,
)

@Serializable
data class SearchVisit(
    val id: String,
    val startsAt: String,
    val status: String? = null,
    val serviceLabel: String? = null,
    val upcoming: Boolean? = null,
    val petId: String? = null,
    val petName: String,
    val petSpecies: String? = null,
    val petPhotoUrl: String? = null,
    val clientId: String? = null,
    val clientName: String? = null,
    /** Only when the business has two or more locations. */
    val locationName: String? = null,
    /** The visit's location clock. */
    val timeZone: String? = null,
)

@Serializable
data class SearchInvoice(
    val id: String,
    val folio: String,
    val kind: String? = null,
    val status: String? = null,
    val currency: String? = null,
    /** Cents. */
    val totalAmount: Int? = null,
    /** Cents. */
    val balanceDueAmount: Int? = null,
    val date: String? = null,
    /** Null for a counter sale to the walk-in client. */
    val clientId: String? = null,
    val clientName: String? = null,
    val petName: String? = null,
    val locationName: String? = null,
)

@Serializable
data class SearchGroupResult<T>(
    val items: List<T>,
    val hasMore: Boolean? = null,
)

/** Null groups were not asked for, or this member's role doesn't open them. */
@Serializable
data class SearchResponse(
    val query: String? = null,
    val pets: SearchGroupResult<SearchPet>? = null,
    val hospital: SearchGroupResult<SearchHospitalPatient>? = null,
    val clients: SearchGroupResult<SearchClient>? = null,
    val visits: SearchGroupResult<SearchVisit>? = null,
    val invoices: SearchGroupResult<SearchInvoice>? = null,
) {
    /** Whether the server found anything at all. */
    val hasResults: Boolean
        get() = !(pets?.items?.isEmpty() ?: true) || !(hospital?.items?.isEmpty() ?: true) || !(clients?.items?.isEmpty() ?: true) ||
            !(visits?.items?.isEmpty() ?: true) || !(invoices?.items?.isEmpty() ?: true)
}

enum class SearchResultGroup(val rawValue: String) {
    pets("pets"), hospital("hospital"), clients("clients"), visits("visits"), invoices("invoices");
}

// MARK: - Who sees what

object SearchRules {
    /** Which result groups a member may see — the same permissions the server checks. */
    fun allowedGroups(w: Workspace): Set<SearchResultGroup> {
        val groups = mutableSetOf<SearchResultGroup>()
        val clientReach = w.can(Permission.clientsView) && w.canAny(Permission.clientsAssignAnyone, Permission.clientsAssignSelf)
        if (clientReach && w.can(Permission.recordsView)) groups.add(SearchResultGroup.pets)
        // The ward's reach: every client's patients in the hospital.
        if (w.can(Permission.hospitalView)) groups.add(SearchResultGroup.hospital)
        if (clientReach) groups.add(SearchResultGroup.clients)
        if (w.can(Permission.appointmentsView)) groups.add(SearchResultGroup.visits)
        // Bills in search are level 1 (money access levels): a higher level includes it.
        if (w.moneyLevel >= MoneyLevel.charging) groups.add(SearchResultGroup.invoices)
        return groups
    }

    /** The groups whose "see all" list this member may open: the bills list is the front desk's (level 2). */
    fun moreGroups(w: Workspace): Set<SearchResultGroup> {
        val groups = allowedGroups(w).toMutableSet()
        if (w.moneyLevel < MoneyLevel.frontDesk) groups.remove(SearchResultGroup.invoices)
        return groups
    }

    /** The actions and pages this member may open in this business. */
    fun availableActions(w: Workspace): List<SearchAction> {
        val categories = w.current?.enabledCategories ?: emptyList()
        val offersPharmacy = categories.isEmpty() || categories.any { it in listOf("vet", "pharmacy", "emergency") }
        val canManagePlan = w.current?.canManagePlan ?: false
        return SearchAction.all.filter { action ->
            when (action.offer) {
                SearchAction.Offer.stays -> if (!w.offers(BusinessCategory.boarding, BusinessCategory.daycare, BusinessCategory.sitter)) return@filter false
                SearchAction.Offer.grooming -> if (!w.offers(BusinessCategory.groomer)) return@filter false
                SearchAction.Offer.groups -> if (!w.offers(BusinessCategory.walker, BusinessCategory.trainer)) return@filter false
                SearchAction.Offer.homeVisits -> if (!w.offersHomeVisits) return@filter false
                SearchAction.Offer.pharmacy -> if (!offersPharmacy) return@filter false
                null -> {}
            }
            if (!action.needs.all { w.can(it) }) return@filter false
            if (action.anyOf.isNotEmpty() && !action.anyOf.any { w.can(it) }) return@filter false
            val money = action.money
            if (money != null && w.moneyLevel < money && !(action.orPlanManager && canManagePlan)) return@filter false
            true
        }
    }
}

// MARK: - Text

object SearchText {
    const val minLength = 2
    /** Each group asks the server for this many. */
    const val limit = 5
    /** How many actions show under results (the rest need more typing). */
    const val actionsWithResults = 4

    /** Lowercase, no accents, single spaces — how both the typed text and the labels are compared. */
    fun fold(value: String?): String {
        val plain = Normalizer.normalize(value ?: "", Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        val kept = plain.map { ch -> if (ch in 'a'..'z' || ch in '0'..'9') ch else ' ' }.joinToString("")
        return kept.split(" ").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** The text worth sending: trimmed, two characters or more, with a letter or digit in it. */
    fun searchable(text: String): String? {
        val trimmed = text.trim()
        return if (trimmed.length >= minLength && fold(trimmed).isNotEmpty()) trimmed else null
    }

    /**
     * When a visit is, in its location's clock: "Hoy, 10:00", "Mañana, 9:30", "Ayer, 17:00",
     * else "mar 30 sep, 10:00" (with the year when it isn't this one).
     */
    fun visitWhen(iso: String, timeZone: String?): String {
        val date = DateText.parse(iso) ?: return ""
        val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Workspace.shared.timeZone
        val time = DateText.time(date, zone)
        val now = Instant.now()
        val day = date.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        when (ChronoUnit.DAYS.between(today, day)) {
            0L -> return "${L("Today")}, $time"
            1L -> return "${L("Tomorrow")}, $time"
            -1L -> return "${L("Yesterday")}, $time"
            else -> {}
        }
        val sameYear = day.year == today.year
        return "${DateText.format(date, if (sameYear) "EEEdMMM" else "EEEdMMMyyyy", zone)}, $time"
    }
}

// MARK: - Quick actions and pages

/** One quick action or page. Equal (and hashed) by its `id`, as on iOS. */
class SearchAction(
    val id: String,
    /** English; the palette translates it. */
    val label: String,
    /** 'action' does something ("Nueva cita"); 'page' goes somewhere ("Agenda"). */
    val kind: Kind,
    /** The web's Lucide icon. */
    val icon: String,
    val opens: Opens,
    /** Other words people type for it, in both languages. */
    val keywords: List<String>,
    val needs: List<Permission> = emptyList(),
    /** At least one of these. */
    val anyOf: List<Permission> = emptyList(),
    val money: MoneyLevel? = null,
    /** With `money`: the plan manager opens it too, whatever their level. */
    val orPlanManager: Boolean = false,
    val offer: Offer? = null,
) {
    enum class Kind { action, page }

    /** What the business must offer for it to show. */
    enum class Offer { stays, grooming, groups, homeVisits, pharmacy }

    /** What an action opens instead of a page (the web's sheets). */
    sealed class Opens {
        data class section(val section: ProviderSection) : Opens()
        data object newAppointment : Opens()
        data object newClient : Opens()
        data object newRecord : Opens()
    }

    override fun equals(other: Any?): Boolean = other is SearchAction && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** web PALETTE_ACTIONS, in order. */
        val all: List<SearchAction> = listOf(
            SearchAction(id = "new-appointment", label = "New appointment", kind = Kind.action, icon = "calendar-plus", opens = Opens.newAppointment, keywords = listOf("nueva cita", "agendar", "reservar", "book", "visit", "visita", "cita"), needs = listOf(Permission.appointmentsCreate, Permission.clientsView)),
            SearchAction(id = "new-client", label = "New client", kind = Kind.action, icon = "user-plus", opens = Opens.newClient, keywords = listOf("nuevo cliente", "alta", "agregar cliente", "add client", "familia", "family"), needs = listOf(Permission.clientsCreate, Permission.petsCreate), anyOf = listOf(Permission.clientsAssignSelf, Permission.clientsAssignAnyone)),
            SearchAction(id = "new-record", label = "New record", kind = Kind.action, icon = "file-plus-2", opens = Opens.newRecord, keywords = listOf("nuevo registro", "nota", "note", "expediente", "vacuna", "vaccine", "record"), needs = listOf(Permission.recordsCreate, Permission.clientsView), anyOf = listOf(Permission.recordsAssignSelf, Permission.recordsAssignAnyone)),
            SearchAction(id = "open-register", label = "Open register", kind = Kind.action, icon = "wallet", opens = Opens.section(ProviderSection.store), keywords = listOf("abrir caja", "caja", "turno", "register", "cash", "shift", "efectivo"), anyOf = listOf(Permission.salesSell, Permission.billingRecordPayment, Permission.registerManage)),
            SearchAction(id = "page-home", label = "Home", kind = Kind.page, icon = "building-2", opens = Opens.section(ProviderSection.home), keywords = listOf("inicio", "hoy", "today", "tablero")),
            SearchAction(id = "page-agenda", label = "Schedule", kind = Kind.page, icon = "calendar-days", opens = Opens.section(ProviderSection.schedule), keywords = listOf("agenda", "calendario", "calendar", "citas", "appointments", "schedule"), needs = listOf(Permission.appointmentsView)),
            SearchAction(id = "page-stays", label = "Stays", kind = Kind.page, icon = "bed-double", opens = Opens.section(ProviderSection.stays), keywords = listOf("estancias", "hotel", "guarderia", "daycare", "boarding", "hospedaje"), needs = listOf(Permission.staysView), offer = Offer.stays),
            SearchAction(id = "page-groups", label = "Walks & classes", kind = Kind.page, icon = "dog", opens = Opens.section(ProviderSection.groups), keywords = listOf("paseos", "clases", "grupal", "grupo", "paseo grupal", "clase grupal", "entrenamiento", "walks", "classes", "group", "training"), needs = listOf(Permission.appointmentsView), offer = Offer.groups),
            SearchAction(id = "page-grooming", label = "Grooming", kind = Kind.page, icon = "scissors", opens = Opens.section(ProviderSection.grooming), keywords = listOf("estetica", "peluqueria", "bano", "corte", "groomer", "grooms", "bath", "haircut"), needs = listOf(Permission.appointmentsView), offer = Offer.grooming),
            SearchAction(id = "page-home-visits", label = "Home visits", kind = Kind.page, icon = "house", opens = Opens.section(ProviderSection.homeVisits), keywords = listOf("domicilio", "visitas a domicilio", "a domicilio", "paseos", "paseador", "cuidador", "en casa", "ruta", "walks", "walker", "sitter", "drop-in"), anyOf = listOf(Permission.appointmentsView, Permission.staysView), offer = Offer.homeVisits),
            // One page for both: "pacientes" or "mascotas" finds it too.
            SearchAction(id = "page-clients", label = "Clients & patients", kind = Kind.page, icon = "users-round", opens = Opens.section(ProviderSection.clients), keywords = listOf("clientes", "clients", "directorio", "familias", "owners", "duenos", "pacientes", "patients", "mascotas", "pets", "animales"), needs = listOf(Permission.clientsView)),
            SearchAction(id = "page-due", label = "Due", kind = Kind.page, icon = "bell", opens = Opens.section(ProviderSection.due), keywords = listOf("pendientes de volver", "recordatorios", "recalls", "vencidos"), needs = listOf(Permission.followupsView)),
            SearchAction(id = "page-invitations", label = "Invitations", kind = Kind.page, icon = "mail", opens = Opens.section(ProviderSection.invitations), keywords = listOf("invitaciones", "invitar", "invite"), needs = listOf(Permission.clientsView)),
            SearchAction(id = "page-records", label = "Records", kind = Kind.page, icon = "file-text", opens = Opens.section(ProviderSection.records), keywords = listOf("expedientes", "notas", "seguimientos", "follow-ups"), needs = listOf(Permission.recordsView)),
            SearchAction(id = "page-store", label = "Store", kind = Kind.page, icon = "shopping-cart", opens = Opens.section(ProviderSection.store), keywords = listOf("tienda", "vender", "venta", "sell", "pos", "punto de venta"), needs = listOf(Permission.salesSell)),
            SearchAction(id = "page-inventory", label = "Inventory", kind = Kind.page, icon = "boxes", opens = Opens.section(ProviderSection.inventory), keywords = listOf("inventario", "stock", "existencias", "lotes"), needs = listOf(Permission.inventoryView)),
            SearchAction(id = "page-pharmacy", label = "Pharmacy", kind = Kind.page, icon = "file-text", opens = Opens.section(ProviderSection.pharmacy), keywords = listOf("farmacia", "recetas", "prescriptions", "resurtidos", "refills", "controlados", "control log", "libro de control", "controlled register"), anyOf = listOf(Permission.recordsView, Permission.salesSell, Permission.billingEditInvoice), offer = Offer.pharmacy),
            SearchAction(id = "page-catalog", label = "Catalog", kind = Kind.page, icon = "package", opens = Opens.section(ProviderSection.catalog), keywords = listOf("catalogo", "servicios", "productos", "precios", "prices", "services", "products"), needs = listOf(Permission.catalogView)),
            SearchAction(id = "page-invoices", label = "Invoices", kind = Kind.page, icon = "receipt", opens = Opens.section(ProviderSection.invoices), keywords = listOf("facturas", "cuentas", "bills", "cobros", "folios"), money = MoneyLevel.frontDesk),
            SearchAction(id = "page-finance", label = "Finance", kind = Kind.page, icon = "coins", opens = Opens.section(ProviderSection.finance), keywords = listOf("finanzas", "dinero", "money", "reportes", "reports", "ingresos"), money = MoneyLevel.frontDesk),
            SearchAction(id = "page-day-close", label = "Day close", kind = Kind.page, icon = "scale", opens = Opens.section(ProviderSection.finance), keywords = listOf("cortes del dia", "corte de caja", "cierre", "day close", "cash up"), money = MoneyLevel.frontDesk),
            SearchAction(id = "page-notifications", label = "Notifications", kind = Kind.page, icon = "bell", opens = Opens.section(ProviderSection.notifications), keywords = listOf("notificaciones", "avisos", "alerts")),
            SearchAction(id = "page-staff", label = "Staff", kind = Kind.page, icon = "shield-check", opens = Opens.section(ProviderSection.staff), keywords = listOf("equipo", "team", "personal", "usuarios", "users", "roles"), needs = listOf(Permission.staffView)),
            SearchAction(id = "page-availability", label = "Availability", kind = Kind.page, icon = "clock-3", opens = Opens.section(ProviderSection.availability), keywords = listOf("disponibilidad", "horarios", "hours", "opening hours"), needs = listOf(Permission.workspaceEdit)),
            SearchAction(id = "page-rooms", label = "Rooms", kind = Kind.page, icon = "door-open", opens = Opens.section(ProviderSection.rooms), keywords = listOf("espacios", "salas", "consultorios", "rooms"), needs = listOf(Permission.appointmentsView)),
            SearchAction(id = "page-locations", label = "Locations", kind = Kind.page, icon = "map-pin", opens = Opens.section(ProviderSection.locations), keywords = listOf("sedes", "sucursales", "branches"), needs = listOf(Permission.workspaceEdit)),
            SearchAction(id = "page-settings", label = "Settings", kind = Kind.page, icon = "settings", opens = Opens.section(ProviderSection.settings), keywords = listOf("ajustes", "configuracion", "negocio", "workspace"), needs = listOf(Permission.workspaceEdit)),
            SearchAction(id = "page-subscription", label = "Subscription", kind = Kind.page, icon = "circle-dollar-sign", opens = Opens.section(ProviderSection.billing), keywords = listOf("suscripcion", "plan", "pago", "billing"), money = MoneyLevel.finances, orPlanManager = true),
        )

        /**
         * The actions whose name (in either language) or keywords have every typed word, best
         * first: a name that starts with the text, then a word of it, then the rest.
         */
        fun matching(actions: List<SearchAction>, text: String, translated: (String) -> String): List<SearchAction> {
            val words = SearchText.fold(text).split(" ").filter { it.isNotEmpty() }
            val first = words.firstOrNull() ?: return actions
            val phrase = words.joinToString(" ")
            data class Scored(val action: SearchAction, val score: Int, val index: Int)
            val scored = actions.withIndex().mapNotNull { (index, action) ->
                val names = listOf(SearchText.fold(translated(action.label)), SearchText.fold(action.label))
                val haystack = (names + action.keywords.map { SearchText.fold(it) }).joinToString(" | ")
                if (!words.all { haystack.contains(it) }) return@mapNotNull null
                val score = when {
                    names.any { it.startsWith(phrase) } -> 0
                    names.any { name -> name.split(" ").filter { it.isNotEmpty() }.any { it.startsWith(first) } } -> 1
                    else -> 2
                }
                Scored(action, score, index)
            }
            return scored.sortedWith { a, b -> if (a.score != b.score) a.score.compareTo(b.score) else a.index.compareTo(b.index) }.map { it.action }
        }
    }
}

// MARK: - What this device remembers

@Serializable
enum class SearchRecentKind {
    pet, hospital, client, visit, invoice;

    val rawValue: String get() = name

    val group: SearchResultGroup
        get() = when (this) {
            pet -> SearchResultGroup.pets
            hospital -> SearchResultGroup.hospital
            client -> SearchResultGroup.clients
            visit -> SearchResultGroup.visits
            invoice -> SearchResultGroup.invoices
        }
}

@Serializable
data class SearchRecentItem(
    val kind: SearchRecentKind,
    val id: String,
    /** The client, for a pet (its chart lives under it). */
    var clientId: String? = null,
    val title: String,
    var subtitle: String? = null,
    var photoUrl: String? = null,
    var species: String? = null,
    /** When it was opened (ms). */
    var at: Double = System.currentTimeMillis().toDouble(),
)

@Serializable
data class SearchRecents(
    var items: List<SearchRecentItem> = emptyList(),
    var queries: List<String> = emptyList(),
) {
    /** What this device remembers when an option is opened again: the item first, without its older copy, capped. */
    fun remembering(item: SearchRecentItem): SearchRecents {
        val rest = items.filter { !(it.kind == item.kind && it.id == item.id) }
        return SearchRecents(items = (listOf(item) + rest).take(itemsMax), queries = queries)
    }

    /** The search first (the same words, however cased, only once), capped. */
    fun remembering(query: String): SearchRecents {
        val trimmed = query.trim()
        if (trimmed.length < SearchText.minLength) return this
        val rest = queries.filter { SearchText.fold(it) != SearchText.fold(trimmed) }
        return SearchRecents(items = items, queries = (listOf(trimmed) + rest).take(queriesMax))
    }

    fun write(key: String) {
        if (items.isEmpty() && queries.isEmpty()) {
            AppContext.defaults.edit().remove(key).apply()
        } else {
            runCatching { API.json.encodeToString(serializer(), this) }.getOrNull()?.let {
                AppContext.defaults.edit().putString(key, it).apply()
            }
        }
    }

    companion object {
        const val itemsMax = 6
        const val queriesMax = 4

        /** Kept per person and per business on this device, so a shared front-desk device doesn't mix them. */
        fun key(userId: String?, clinicId: String?): String =
            "pawmiaw.provider.search.recent.${userId ?: "anon"}.${clinicId ?: "none"}"

        /** What this device remembers; empty when it can't be read. */
        fun read(key: String): SearchRecents {
            val data = AppContext.defaults.getString(key, null) ?: return SearchRecents()
            // Item by item, so one unreadable entry doesn't lose the rest.
            val loose = runCatching { API.json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return SearchRecents()
            val rawItems = loose["items"]
            val rawQueries = loose["queries"]
            if (rawItems != null && rawItems !is JsonArray && !rawItems.isNull) return SearchRecents()
            val queries: List<String> = if (rawQueries == null || rawQueries.isNull) emptyList()
                else runCatching { API.json.decodeFromJsonElement(ListSerializer(String.serializer()), rawQueries) }.getOrNull() ?: return SearchRecents()
            val items = ((rawItems as? JsonArray) ?: emptyList<JsonElement>()).mapNotNull { value ->
                runCatching { API.json.decodeFromJsonElement(SearchRecentItem.serializer(), value) }.getOrNull()
            }
            val kept = queries.filter { it.trim().length >= SearchText.minLength }
            return SearchRecents(items = items.take(itemsMax), queries = kept.take(queriesMax))
        }
    }
}

// MARK: - The options, in groups

enum class SearchGroupID(val rawValue: String) {
    recent("recent"), pets("pets"), hospital("hospital"), clients("clients"), visits("visits"), invoices("invoices"), actions("actions"), pages("pages");

    val title: String
        get() = when (this) {
            recent -> L("Recent")
            pets -> L("Patients")
            hospital -> L("Hospital")
            clients -> L("Clients")
            visits -> L("Visits")
            invoices -> L("Invoices")
            actions -> L("Quick actions")
            pages -> L("Go to")
        }
}

sealed class SearchOption {
    data class pet(val pet: SearchPet) : SearchOption()
    data class hospital(val patient: SearchHospitalPatient) : SearchOption()
    data class client(val client: SearchClient) : SearchOption()
    data class visit(val visit: SearchVisit) : SearchOption()
    data class invoice(val invoice: SearchInvoice) : SearchOption()
    data class more(val group: SearchResultGroup, val query: String) : SearchOption()
    data class recentItem(val item: SearchRecentItem) : SearchOption()
    data class recentQuery(val query: String) : SearchOption()
    data class action(val action: SearchAction) : SearchOption()

    val id: String
        get() = when (this) {
            is pet -> "pet:${pet.id}"
            is hospital -> "hospital:${patient.id}"
            is client -> "client:${client.id}"
            is visit -> "visit:${visit.id}"
            is invoice -> "invoice:${invoice.id}"
            is more -> "more:${group.rawValue}"
            is recentItem -> "recent:${item.kind.rawValue}:${item.id}"
            is recentQuery -> "recent-query:$query"
            is action -> "action:${action.id}"
        }

    /** What this device remembers when it is opened. */
    val recent: SearchRecentItem?
        get() = when (this) {
            is pet -> SearchRecentItem(kind = SearchRecentKind.pet, id = pet.id, clientId = pet.clientId, title = pet.name, subtitle = pet.ownerName, photoUrl = pet.photoUrl, species = pet.species)
            is hospital -> SearchRecentItem(kind = SearchRecentKind.hospital, id = patient.id, title = patient.petName, subtitle = patient.clientName, photoUrl = patient.petPhotoUrl, species = patient.petSpecies)
            is client -> SearchRecentItem(kind = SearchRecentKind.client, id = client.id, title = client.name, subtitle = client.phone ?: client.email, photoUrl = client.photoUrl)
            is visit -> SearchRecentItem(kind = SearchRecentKind.visit, id = visit.id, title = listOf(visit.petName, visit.serviceLabel ?: "").filter { it.isNotEmpty() }.joinToString(" · "), subtitle = visit.clientName, photoUrl = visit.petPhotoUrl, species = visit.petSpecies)
            is invoice -> SearchRecentItem(kind = SearchRecentKind.invoice, id = invoice.id, title = listOf(invoice.folio, invoice.clientName ?: "").filter { it.isNotEmpty() }.joinToString(" · "), subtitle = Money.format(invoice.totalAmount, currency = invoice.currency))
            is recentItem -> SearchRecentItem(kind = item.kind, id = item.id, clientId = item.clientId, title = item.title, subtitle = item.subtitle, photoUrl = item.photoUrl, species = item.species)
            else -> null
        }
}

data class SearchOptionGroup(
    val id: SearchGroupID,
    val options: List<SearchOption>,
)

/**
 * The groups, in order. Nothing typed: what this device remembers, then the quick actions
 * and pages. Something typed: patients, patients in the hospital, clients, visits, bills
 * (each with "see all" when there are more), then the actions that match.
 */
fun SearchRules.groups(
    text: String, results: SearchResponse?, actions: List<SearchAction>, recents: SearchRecents,
    moreGroups: Set<SearchResultGroup>, translated: (String) -> String,
): List<SearchOptionGroup> {
    val typed = text.trim()
    if (typed.isEmpty()) {
        val recent = recents.items.map { SearchOption.recentItem(it) } + recents.queries.map { SearchOption.recentQuery(it) }
        return listOf(
            SearchOptionGroup(id = SearchGroupID.recent, options = recent),
            SearchOptionGroup(id = SearchGroupID.actions, options = actions.filter { it.kind == SearchAction.Kind.action }.map { SearchOption.action(it) }),
            SearchOptionGroup(id = SearchGroupID.pages, options = actions.filter { it.kind == SearchAction.Kind.page }.map { SearchOption.action(it) }),
        ).filter { it.options.isNotEmpty() }
    }

    val groups = mutableListOf<SearchOptionGroup>()
    val query = SearchText.searchable(typed)
    if (results != null && query != null) {
        fun more(group: SearchResultGroup, hasMore: Boolean?): List<SearchOption> =
            if ((hasMore ?: false) && moreGroups.contains(group)) listOf(SearchOption.more(group, query = query)) else emptyList()
        results.pets?.takeIf { it.items.isNotEmpty() }?.let { pets ->
            groups.add(SearchOptionGroup(id = SearchGroupID.pets, options = pets.items.map { SearchOption.pet(it) } + more(SearchResultGroup.pets, pets.hasMore)))
        }
        results.hospital?.takeIf { it.items.isNotEmpty() }?.let { hospital ->
            groups.add(SearchOptionGroup(id = SearchGroupID.hospital, options = hospital.items.map { SearchOption.hospital(it) } + more(SearchResultGroup.hospital, hospital.hasMore)))
        }
        results.clients?.takeIf { it.items.isNotEmpty() }?.let { clients ->
            groups.add(SearchOptionGroup(id = SearchGroupID.clients, options = clients.items.map { SearchOption.client(it) } + more(SearchResultGroup.clients, clients.hasMore)))
        }
        results.visits?.takeIf { it.items.isNotEmpty() }?.let { visits ->
            groups.add(SearchOptionGroup(id = SearchGroupID.visits, options = visits.items.map { SearchOption.visit(it) } + more(SearchResultGroup.visits, visits.hasMore)))
        }
        results.invoices?.takeIf { it.items.isNotEmpty() }?.let { invoices ->
            groups.add(SearchOptionGroup(id = SearchGroupID.invoices, options = invoices.items.map { SearchOption.invoice(it) } + more(SearchResultGroup.invoices, invoices.hasMore)))
        }
    }
    val matched = SearchAction.matching(actions, text = typed, translated = translated).take(SearchText.actionsWithResults)
    if (matched.isNotEmpty()) groups.add(SearchOptionGroup(id = SearchGroupID.actions, options = matched.map { SearchOption.action(it) }))
    return groups
}

// MARK: - Where a choice leads

sealed class SearchDestination {
    /** A page: pushed on the phone's stack; a section on the tablet is chosen in the sidebar. */
    data class route(val route: ProviderRoute) : SearchDestination()
    /** The new-client sheet, then that client's page. */
    data object newClient : SearchDestination()
    /** A remembered search: typed again. */
    data class query(val query: String) : SearchDestination()
}

/**
 * Where an option leads. A patient opens its chart page; a patient in the hospital its
 * hospitalization; a client their page; a visit its page; a bill (a counter sale too) its bill.
 */
fun SearchRules.destination(option: SearchOption, workspace: Workspace): SearchDestination? {
    val w = workspace
    return when (option) {
        is SearchOption.pet -> SearchDestination.route(ProviderRoute.clientPet(clientId = option.pet.clientId, petId = option.pet.id))
        is SearchOption.hospital -> SearchDestination.route(ProviderRoute.hospitalization(option.patient.id))
        is SearchOption.client -> SearchDestination.route(ProviderRoute.client(option.client.id))
        is SearchOption.visit -> SearchDestination.route(ProviderRoute.appointment(option.visit.id))
        is SearchOption.invoice -> SearchDestination.route(ProviderRoute.invoice(option.invoice.id))
        is SearchOption.more -> when (option.group) {
            // The list page with every match (the web opens it with the words in its own search box).
            SearchResultGroup.pets -> SearchDestination.route(ProviderRoute.section(ProviderSection.patients))
            SearchResultGroup.hospital -> SearchDestination.route(ProviderRoute.section(ProviderSection.hospital))
            SearchResultGroup.clients -> SearchDestination.route(ProviderRoute.section(ProviderSection.clients))
            SearchResultGroup.visits -> SearchDestination.route(ProviderRoute.schedule(ScheduleFilter(view = "list")))
            SearchResultGroup.invoices -> SearchDestination.route(ProviderRoute.section(ProviderSection.invoices))
        }
        is SearchOption.recentQuery -> SearchDestination.query(option.query)
        is SearchOption.recentItem -> {
            val item = option.item
            when (item.kind) {
                SearchRecentKind.pet -> {
                    val clientId = item.clientId
                    if (clientId != null) SearchDestination.route(ProviderRoute.clientPet(clientId = clientId, petId = item.id))
                    else SearchDestination.route(ProviderRoute.patientChart(petId = item.id))
                }
                SearchRecentKind.hospital -> SearchDestination.route(ProviderRoute.hospitalization(item.id))
                SearchRecentKind.client -> SearchDestination.route(ProviderRoute.client(item.id))
                SearchRecentKind.visit -> SearchDestination.route(ProviderRoute.appointment(item.id))
                SearchRecentKind.invoice -> SearchDestination.route(ProviderRoute.invoice(item.id))
            }
        }
        is SearchOption.action -> when (val opens = option.action.opens) {
            is SearchAction.Opens.section -> SearchDestination.route(ProviderRoute.section(opens.section))
            // Booking needs one place: with "Todas las sedes", the Agenda asks which first.
            SearchAction.Opens.newAppointment -> SearchDestination.route(if (w.allLocationsChosen) ProviderRoute.section(ProviderSection.schedule) else ProviderRoute.bookVisit(BookingPrefill()))
            SearchAction.Opens.newClient -> SearchDestination.newClient
            SearchAction.Opens.newRecord -> SearchDestination.route(ProviderRoute.newRecord(RecordPrefill()))
        }
    }
}
