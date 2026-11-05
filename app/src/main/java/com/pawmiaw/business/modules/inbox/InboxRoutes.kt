package com.pawmiaw.business.inbox

import com.pawmiaw.business.*

//
//  InboxRoutes.kt (iOS InboxRoutes.swift)
//
//  A web address of the business workspace (`/provider/...`, as a
//  notification's `actionUrl` or an Olfato AI answer's "Ver en …" link
//  names it) as the screen the app pushes. An address the app has no screen
//  for gives null: the row then only marks itself read.
//

object InboxRoutes {
    /** Swift `removingPercentEncoding ?? raw`: `%XX` decoded, a `+` left as it is. */
    private fun decoded(text: String): String = runCatching { android.net.Uri.decode(text) }.getOrNull() ?: text

    /** `/provider/appointments/abc`, `/provider/invoices?invoice=abc`, `/app/hospital/abc` (older hospital notices). */
    fun route(address: String?): ProviderRoute? {
        if (address == null || !address.startsWith("/") || address.startsWith("//")) return null
        val parts = address.split("?", limit = 2)
        val query = mutableMapOf<String, String>()
        if (parts.size > 1) {
            // Swift `URLComponents(string: "?" + query).queryItems`: name=value pairs, a pair without a value names nothing.
            for (item in parts[1].split("#", limit = 2)[0].split("&")) {
                if (item.isEmpty()) continue
                val pair = item.split("=", limit = 2)
                if (pair.size < 2) continue
                query[decoded(pair[0])] = decoded(pair[1])
            }
        }
        val path = parts[0].split("#").firstOrNull { it.isNotEmpty() } ?: parts[0]
        return route(path = path, query = query)
    }

    fun route(path: String, query: Map<String, String>): ProviderRoute? {
        val segments = path.split("/").filter { it.isNotEmpty() }.map { decoded(it) }
        val first = segments.firstOrNull() ?: return null
        // The hospital's first staff notices carried app paths.
        if (first == "app") {
            if (segments.size >= 3 && segments[1] == "hospital") return ProviderRoute.hospitalization(segments[2])
            if (segments.size >= 2 && segments[1] == "home") return ProviderRoute.section(ProviderSection.home)
            return null
        }
        if (first != "provider") return null
        val rest = segments.drop(1)
        val head = rest.firstOrNull() ?: return ProviderRoute.section(ProviderSection.home)
        val id = if (rest.size > 1) rest[1] else null

        return when (head) {
            "appointments" -> id?.let { ProviderRoute.appointment(it) } ?: ProviderRoute.section(ProviderSection.appointments)
            "clients" -> {
                if (id != null && rest.size >= 4 && rest[2] == "pets") ProviderRoute.clientPet(clientId = id, petId = rest[3])
                else id?.let { ProviderRoute.client(it) } ?: ProviderRoute.section(ProviderSection.clients)
            }
            "records" -> id?.let { ProviderRoute.record(it) } ?: ProviderRoute.section(ProviderSection.records)
            "hospital" -> id?.let { ProviderRoute.hospitalization(it) } ?: ProviderRoute.section(ProviderSection.hospital)
            "staff" -> id?.let { ProviderRoute.staffMember(it) } ?: ProviderRoute.section(ProviderSection.staff)
            "olfato" -> id?.let { ProviderRoute.olfatoChat(it) } ?: ProviderRoute.section(ProviderSection.olfato)
            "invoices" -> query["invoice"].nonEmpty?.let { ProviderRoute.invoice(it) } ?: ProviderRoute.section(ProviderSection.invoices)
            "stays" -> query["stay"].nonEmpty?.let { ProviderRoute.stay(it) } ?: ProviderRoute.section(ProviderSection.stays)
            "store" -> query["sale"].nonEmpty?.let { ProviderRoute.sale(it) } ?: ProviderRoute.section(ProviderSection.store)
            "settings" -> if (id == "notifications") ProviderRoute.section(ProviderSection.notificationSettings) else ProviderRoute.section(ProviderSection.settings)
            "account" -> ProviderRoute.section(ProviderSection.accountSecurity)
            "schedule", "agenda" -> ProviderRoute.section(ProviderSection.schedule)
            "grooming" -> ProviderRoute.section(ProviderSection.grooming)
            "groups" -> ProviderRoute.section(ProviderSection.groups)
            "home-visits" -> ProviderRoute.section(ProviderSection.homeVisits)
            "patients" -> ProviderRoute.section(ProviderSection.patients)
            "due" -> ProviderRoute.section(ProviderSection.due)
            "invitations" -> ProviderRoute.section(ProviderSection.invitations)
            "inventory" -> ProviderRoute.section(ProviderSection.inventory)
            "pharmacy" -> ProviderRoute.section(ProviderSection.pharmacy)
            "catalog" -> ProviderRoute.section(ProviderSection.catalog)
            "finance" -> ProviderRoute.section(ProviderSection.finance)
            "billing" -> ProviderRoute.section(ProviderSection.billing)
            "availability" -> ProviderRoute.section(ProviderSection.availability)
            "rooms" -> ProviderRoute.section(ProviderSection.rooms)
            "locations" -> ProviderRoute.section(ProviderSection.locations)
            "relocate" -> ProviderRoute.section(ProviderSection.relocate)
            "notifications" -> ProviderRoute.section(ProviderSection.notifications)
            else -> null
        }
    }
}
