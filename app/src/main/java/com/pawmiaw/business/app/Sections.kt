package com.pawmiaw.business

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

//
//  The business workspace's sections, grouped and filtered exactly as the web's sidebar
//  (components/app/provider-shell.tsx; iOS Sections.swift): each has its permission, some need a
//  kind of business, money pages follow the money levels. `ProviderRoute` is every place a
//  screen can push (`LocalNavigator.current.push(ProviderRoute.client(id))`).
//

enum class SectionGroup {
    operations, billing, workspace;

    val title: String
        get() = when (this) {
            operations -> L("Operations")
            billing -> L("Billing")
            workspace -> L("Workspace")
        }

    val sections: List<ProviderSection>
        get() = when (this) {
            operations -> listOf(
                ProviderSection.home, ProviderSection.schedule, ProviderSection.stays, ProviderSection.hospital,
                ProviderSection.grooming, ProviderSection.groups, ProviderSection.homeVisits, ProviderSection.clients,
                ProviderSection.due, ProviderSection.invitations, ProviderSection.records, ProviderSection.olfato,
            )
            billing -> listOf(
                ProviderSection.store, ProviderSection.inventory, ProviderSection.pharmacy,
                ProviderSection.catalog, ProviderSection.invoices, ProviderSection.finance,
            )
            workspace -> listOf(
                ProviderSection.billing, ProviderSection.settings, ProviderSection.staff,
                ProviderSection.availability, ProviderSection.rooms, ProviderSection.locations,
            )
        }
}

enum class ProviderSection(val rawValue: String) {
    home("home"), schedule("schedule"), appointments("appointments"), stays("stays"), hospital("hospital"),
    grooming("grooming"), groups("groups"), homeVisits("homeVisits"), clients("clients"), patients("patients"),
    due("due"), invitations("invitations"), records("records"), olfato("olfato"),
    store("store"), inventory("inventory"), pharmacy("pharmacy"), catalog("catalog"), invoices("invoices"), finance("finance"),
    billing("billing"), settings("settings"), staff("staff"), availability("availability"), rooms("rooms"), locations("locations"),
    notifications("notifications"), notificationSettings("notificationSettings"), relocate("relocate"), accountSecurity("accountSecurity");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            home -> L("Home")
            schedule -> L("Schedule")
            appointments -> L("Appointments")
            stays -> L("Stays")
            hospital -> L("Hospital")
            grooming -> L("Grooming")
            groups -> L("Walks & classes")
            homeVisits -> L("Home visits")
            clients -> L("Clients & patients")
            patients -> L("Patients")
            due -> L("Due")
            invitations -> L("Invitations")
            records -> L("Records")
            olfato -> "Olfato AI"
            store -> L("Store")
            inventory -> L("Inventory")
            pharmacy -> L("Pharmacy")
            catalog -> L("Catalog")
            invoices -> L("Invoices")
            finance -> L("Finance")
            billing -> L("Subscription")
            settings -> L("Settings")
            staff -> L("Staff")
            availability -> L("Availability")
            rooms -> L("Rooms")
            locations -> L("Locations")
            notifications -> L("Notifications")
            notificationSettings -> L("Notification settings")
            relocate -> L("To relocate")
            accountSecurity -> L("Your account")
        }

    /** The web sidebar's Lucide icon (provider-shell.tsx). */
    val icon: String
        get() = when (this) {
            home -> "building-2"
            schedule, appointments -> "calendar-days"
            stays -> "bed-double"
            hospital -> "hospital"
            grooming -> "scissors"
            groups -> "dog"
            homeVisits -> "house"
            clients, patients -> "users-round"
            due -> "bell-ring"
            invitations -> "mail-plus"
            records -> "file-text"
            olfato -> "stethoscope"
            store -> "shopping-cart"
            inventory -> "boxes"
            pharmacy -> "pill"
            catalog -> "package"
            invoices -> "receipt"
            finance -> "coins"
            billing -> "circle-dollar-sign"
            settings -> "settings"
            staff -> "shield-check"
            availability -> "clock-3"
            rooms -> "door-open"
            locations -> "map-pin"
            notifications -> "bell"
            notificationSettings -> "bell-ring"
            relocate -> "arrow-right-left"
            accountSecurity -> "user-round"
        }

    /** The page's module header: its Lucide icon (web, where it differs from the sidebar's). */
    val headerIcon: String
        get() = when (this) {
            home -> "calendar-clock"
            hospital -> "bed-double"
            groups, staff -> "users-round"
            finance -> "banknote"
            else -> icon
        }

    /** The page's module header colour (web `ProviderModuleHeader` accent). */
    val headerAccent: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            hospital, grooming, staff -> Theme.pink
            groups, clients, patients -> Theme.lime
            homeVisits, invitations, records, notifications, availability, accountSecurity -> Theme.aqua
            catalog, relocate -> Theme.mango
            finance -> ProviderState.done.color
            else -> Theme.blue
        }

    val systemImage: String
        get() = when (this) {
            home -> "building.2.fill"
            schedule, appointments -> "calendar"
            stays -> "bed.double.fill"
            hospital -> "cross.case.fill"
            grooming -> "scissors"
            groups -> "figure.walk"
            homeVisits -> "house.fill"
            clients, patients -> "person.2.fill"
            due -> "bell.badge.fill"
            invitations -> "envelope.badge.fill"
            records -> "doc.text.fill"
            olfato -> "stethoscope"
            store -> "cart.fill"
            inventory -> "shippingbox.fill"
            pharmacy -> "pills.fill"
            catalog -> "tag.fill"
            invoices -> "doc.plaintext.fill"
            finance -> "chart.bar.fill"
            billing -> "dollarsign.circle.fill"
            settings -> "gearshape.fill"
            staff -> "person.badge.shield.checkmark.fill"
            availability -> "clock.fill"
            rooms -> "door.left.hand.open"
            locations -> "mappin.and.ellipse"
            notifications -> "bell.fill"
            notificationSettings -> "bell.and.waves.left.and.right.fill"
            relocate -> "arrow.triangle.swap"
            accountSecurity -> "lock.shield.fill"
        }

    private val permission: Permission?
        get() = when (this) {
            schedule, appointments, grooming, groups, rooms -> Permission.appointmentsView
            clients, patients, invitations -> Permission.clientsView
            due -> Permission.followupsView
            stays -> Permission.staysView
            hospital -> Permission.hospitalView
            records -> Permission.recordsView
            store -> Permission.salesSell
            inventory -> Permission.inventoryView
            catalog -> Permission.catalogView
            settings, availability, locations -> Permission.workspaceEdit
            staff -> Permission.staffView
            else -> null
        }

    /** The sidebar's rule for this section in this business, for this person. */
    fun isVisible(w: Workspace, olfatoShown: Boolean, pharmacyShown: Boolean): Boolean {
        when (this) {
            stays -> if (!w.offers(BusinessCategory.boarding, BusinessCategory.daycare, BusinessCategory.sitter)) return false
            hospital -> if (!w.offers(BusinessCategory.vet, BusinessCategory.emergency)) return false
            grooming -> if (!w.offers(BusinessCategory.groomer)) return false
            groups -> if (!w.offers(BusinessCategory.walker, BusinessCategory.trainer)) return false
            homeVisits -> return w.offersHomeVisits && w.canAny(Permission.appointmentsView, Permission.staysView)
            pharmacy -> return pharmacyShown
            olfato -> return olfatoShown
            invoices, finance -> return w.moneyLevel >= MoneyLevel.frontDesk
            billing -> return (w.current?.canManagePlan ?: false) || w.moneyLevel >= MoneyLevel.finances
            else -> {}
        }
        permission?.let { return w.can(it) }
        return true
    }

    companion object {
        fun from(raw: String?): ProviderSection? = entries.firstOrNull { it.rawValue == raw }

        /** Home, or in a debug build the page named by the PAWMIAW_DEBUG_SECTION extra (a design review opens a page directly). */
        val launchSection: ProviderSection
            get() = from(DebugLaunch.section) ?: home
    }
}

/**
 * A visit to book, filled in by the screen that asks for it (a client's page, the Due list, a
 * record, a groom, a free gap on the Agenda). Everything is optional: what is given is chosen,
 * the rest is asked.
 */
data class BookingPrefill(
    val clientId: String? = null,
    val petId: String? = null,
    /** The catalog service to book. */
    val serviceId: String? = null,
    /** "yyyy-MM-dd" on the business's clock. */
    val day: String? = null,
    /** Minutes after midnight. */
    val startMinute: Int? = null,
    val memberId: String? = null,
    val roomId: String? = null,
    /** The Due list's follow-up this visit answers (the web ties them together). */
    val followUpId: String? = null,
    val notes: String? = null,
    /** "Sin cita": the pet is here now. */
    val walkIn: Boolean = false,
)

/** A sale at the Store, filled in by the screen that sends someone to the counter. */
data class StorePrefill(
    val clientId: String? = null,
    val petId: String? = null,
    val lines: List<Line> = emptyList(),
    /** Charge this bill with the products added. */
    val invoiceId: String? = null,
    val appointmentId: String? = null,
    val prescriptionId: String? = null,
) {
    data class Line(
        val catalogItemId: String,
        val quantity: Double,
        /** The prescription line this hands over (the Pharmacy's rule). */
        val prescriptionItemId: String? = null,
    )
}

/** The Agenda opened on a day, a person or a room ("day", "week" or "list"), its day grouped by "staff" or "rooms". */
data class ScheduleFilter(
    val day: String? = null,
    val memberId: String? = null,
    val roomId: String? = null,
    val view: String? = null,
    val by: String? = null,
)

/** A record to write, filled in by the screen that asks (a pet's chart, a visit). */
data class RecordPrefill(
    val clientId: String? = null,
    val petId: String? = null,
    val appointmentId: String? = null,
    /** The web's record type key ("vaccine", "consultation", …). */
    val recordType: String? = null,
)

/** Every place a screen pushes (iOS `ProviderRoute`). Push with `LocalNavigator.current.push(...)`. */
sealed class ProviderRoute {
    data class section(val section: ProviderSection) : ProviderRoute()
    data class appointment(val id: String) : ProviderRoute()
    data class client(val id: String) : ProviderRoute()
    data class clientPet(val clientId: String, val petId: String) : ProviderRoute()
    /** The patient chart (expediente) of a client's pet, from any screen. */
    data class patientChart(val petId: String) : ProviderRoute()
    data class record(val id: String) : ProviderRoute()
    data class hospitalization(val id: String) : ProviderRoute()
    data class staffMember(val id: String) : ProviderRoute()
    data class olfatoChat(val id: String) : ProviderRoute()
    data class invoice(val id: String) : ProviderRoute()
    data class sale(val id: String) : ProviderRoute()
    data class stay(val id: String) : ProviderRoute()
    /** Book a visit with what the asking screen knows (Schedule's booking). */
    data class bookVisit(val prefill: BookingPrefill) : ProviderRoute()
    /** The Store's counter with a client, pet and lines already in the cart. */
    data class storeCheckout(val prefill: StorePrefill) : ProviderRoute()
    /** The Agenda on a day / person / room. */
    data class schedule(val filter: ScheduleFilter) : ProviderRoute()
    /** A new record with its pet, visit or type chosen (Records' sheet). */
    data class newRecord(val prefill: RecordPrefill) : ProviderRoute()
    /** A new Olfato AI chat about one pet (the pet is the provider client pet id). */
    data class olfatoPetChat(val petId: String) : ProviderRoute()

    /**
     * A page iOS pushes with a plain `NavigationLink { SomeScreen(...) }` (no `ProviderRoute` of its
     * own): a prescription, a client's account, a group session, a settings section. It slides in
     * like any page, with the back arrow. [key] says which page it is ("rx:<id>"), so two pushes of
     * the same page are equal.
     *
     *     nav.push(ProviderRoute.page("rx:$id") { StockPrescriptionScreen(prescriptionId = id) })
     */
    class page(val key: String, val content: @Composable () -> Unit) : ProviderRoute() {
        override fun equals(other: Any?): Boolean = other is page && other.key == key
        override fun hashCode(): Int = key.hashCode()
        override fun toString(): String = "page($key)"
    }
}
