package com.pawmiaw.business.services

import com.pawmiaw.business.*
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

//
//  SvcBooking.kt (iOS SvcBooking.swift)
//
//  The catalog of services as the three pages use it (web:
//  hooks/use-appointment-service-catalog.ts, the Catalog's priced services),
//  and what "New groom", "New visit" and "Book" hand to the Agenda's booking
//  (`ProviderRoute.bookVisit`), filled in with what the screen knows.
//

@Serializable
data class SvcCatalogBilling(
    val id: String? = null,
    val name: String? = null,
    val currency: String? = null,
    val defaultUnitAmount: Int? = null,
    val active: Boolean? = null,
)

/** One service a business can book (`/appointment-services`). */
@Serializable
data class SvcCatalogService(
    val id: String,
    val category: String,
    val label: String,
    val description: String? = null,
    val defaultDurationMinutes: JSONValue? = null,
    val defaultBillingItem: SvcCatalogBilling? = null,
) {
    val minutes: Int? get() = defaultDurationMinutes.double?.toInt()?.takeIf { it > 0 }
}

@Serializable
private data class SvcCatalogAnswer(val services: List<SvcCatalogService>? = null)

/** A priced Catalog service (`/provider-catalog?itemType=service`): a session named like it charges its price. */
@Serializable
data class SvcPricedService(
    val name: String,
    val defaultUnitAmount: Int? = null,
)

object SvcServices {
    private val lists = mutableMapOf<String, Pair<Instant, List<SvcCatalogService>>>()

    /** The business's services (`provider`) or every one there is (`all`), kept 15 minutes as the web does. */
    suspend fun load(scope: String): List<SvcCatalogService> {
        val kept = lists[scope]
        if (kept != null && Duration.between(kept.first, Instant.now()).seconds < 15 * 60) return kept.second
        val answer = runCatching {
            API.get<SvcCatalogAnswer>("/appointment-services", mapOf("scope" to (if (scope == "provider") "provider" else null)))
        }.getOrNull()
        val services = answer?.services ?: return lists[scope]?.second ?: emptyList()
        lists[scope] = Instant.now() to services
        return services
    }

    fun cached(scope: String): List<SvcCatalogService> = lists[scope]?.second ?: emptyList()

    /**
     * The business's service of a kind to book: the one named like the last visit's, else its first.
     * Group walks and classes are planned as sessions, never booked one by one.
     */
    fun pick(category: String?, label: String? = null): String? {
        if (category == null) return null
        val own = cached("provider").filter { it.category == category && it.id !in listOf("group_walk", "group_class") }
        val wanted = label?.trim()?.lowercase()
        if (!wanted.isNullOrEmpty()) {
            own.firstOrNull { it.label.lowercase() == wanted }?.let { return it.id }
        }
        return own.firstOrNull()?.id
    }
}

object SvcBook {
    /** The kind of visit a home-visit business books: walks first, then sitting. */
    fun homeCategory(workspace: Workspace): String? =
        if (workspace.offers(BusinessCategory.walker)) "walker" else if (workspace.offers(BusinessCategory.sitter)) "sitter" else null

    /** A groom for a pet (or for nobody yet), on its last service when the catalog has it. */
    fun groom(
        clientId: String? = null,
        petId: String? = null,
        lastService: String? = null,
        day: String? = null,
        followUpId: String? = null,
    ): ProviderRoute = ProviderRoute.bookVisit(
        BookingPrefill(
            clientId = clientId,
            petId = petId,
            serviceId = SvcServices.pick(category = "groomer", label = lastService),
            day = day,
            followUpId = followUpId,
        ),
    )
}

// MARK: - Online booking at this place

@Serializable
private data class SvcOnlineAvailability(val allowOnlineBooking: Boolean? = null)

@Serializable
private data class SvcOnlineLocation(val id: String, val availability: SvcOnlineAvailability? = null)

@Serializable
private data class SvcOnlineLocations(val locations: List<SvcOnlineLocation>? = null)

@Serializable
private data class SvcOnlineWorkspace(val availability: SvcOnlineAvailability? = null)

/** Whether families can book online at this place (Disponibilidad); null when it couldn't be read. */
suspend fun svcLocationTakesOnlineBookings(locationId: String?): Boolean? {
    if (locationId != null && Workspace.shared.multiLocation) {
        val answer = runCatching { API.get<SvcOnlineLocations>("/provider-locations", mapOf("includeArchived" to "true")) }.getOrNull()
        val found = answer?.locations?.firstOrNull { it.id == locationId }
        if (found != null) return found.availability?.allowOnlineBooking ?: false
    }
    val active = runCatching { API.get<SvcOnlineWorkspace>("/provider-workspaces/active") }.getOrNull()
    return active?.availability?.allowOnlineBooking
}
