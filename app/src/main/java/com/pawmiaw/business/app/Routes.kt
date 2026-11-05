package com.pawmiaw.business

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.pawmiaw.business.clients.ClientDetailScreen
import com.pawmiaw.business.clients.ClientPetScreen
import com.pawmiaw.business.clients.ClientsScreen
import com.pawmiaw.business.clients.DueScreen
import com.pawmiaw.business.clients.InvitationsScreen
import com.pawmiaw.business.clients.PatientChartScreen
import com.pawmiaw.business.clients.PatientsScreen
import com.pawmiaw.business.home.HomeScreen
import com.pawmiaw.business.hospital.HospitalDetailScreen
import com.pawmiaw.business.hospital.HospitalScreen
import com.pawmiaw.business.inbox.NotificationsScreen
import com.pawmiaw.business.inbox.OlfatoChatScreen
import com.pawmiaw.business.inbox.OlfatoPetChatScreen
import com.pawmiaw.business.inbox.OlfatoScreen
import com.pawmiaw.business.money.BillingScreen
import com.pawmiaw.business.money.CatalogScreen
import com.pawmiaw.business.money.FinanceScreen
import com.pawmiaw.business.money.InvoiceDetailScreen
import com.pawmiaw.business.money.InvoicesScreen
import com.pawmiaw.business.money.SaleDetailScreen
import com.pawmiaw.business.money.StoreCheckoutScreen
import com.pawmiaw.business.money.StoreScreen
import com.pawmiaw.business.places.LocationsScreen
import com.pawmiaw.business.places.RelocateScreen
import com.pawmiaw.business.places.RoomsScreen
import com.pawmiaw.business.records.NewRecordScreen
import com.pawmiaw.business.records.RecordDetailScreen
import com.pawmiaw.business.records.RecordsScreen
import com.pawmiaw.business.schedule.AppointmentDetailScreen
import com.pawmiaw.business.schedule.AppointmentsListScreen
import com.pawmiaw.business.schedule.BookVisitScreen
import com.pawmiaw.business.schedule.ScheduleScreen
import com.pawmiaw.business.services.GroomingScreen
import com.pawmiaw.business.services.GroupsScreen
import com.pawmiaw.business.services.HomeVisitsScreen
import com.pawmiaw.business.stays.StayDetailScreen
import com.pawmiaw.business.stays.StaysScreen
import com.pawmiaw.business.stock.InventoryScreen
import com.pawmiaw.business.stock.PharmacyScreen
import com.pawmiaw.business.team.AccountSecurityScreen
import com.pawmiaw.business.team.AvailabilityScreen
import com.pawmiaw.business.team.NotificationSettingsScreen
import com.pawmiaw.business.team.SettingsScreen
import com.pawmiaw.business.team.StaffMemberScreen
import com.pawmiaw.business.team.StaffScreen

//
//  The screen for each route and each section (iOS Sections.swift: `RouteView`, `SectionView`,
//  `.providerRoutes()`). Every page stack in the app draws its pushed routes with `RouteView`:
//
//      NavStack(navigator, root = { SectionView(section) }, page = { RouteView(it) })
//

/** The screen for a route. Every `NavStack` in the app uses it (iOS `.providerRoutes()`). */
@Composable
fun RouteView(route: ProviderRoute, modifier: Modifier = Modifier) {
    // Where this page sits on its stack, for its bar's back button (the page under it names it).
    val navigator = LocalNavigator.current
    val depth = remember(navigator) { navigator.depth }
    CompositionLocalProvider(LocalPageSlot provides PageSlot(navigator, depth)) {
        Box(modifier) {
            when (route) {
                is ProviderRoute.section -> SectionView(section = route.section)
                is ProviderRoute.appointment -> AppointmentDetailScreen(appointmentId = route.id)
                is ProviderRoute.client -> ClientDetailScreen(clientId = route.id)
                is ProviderRoute.clientPet -> ClientPetScreen(clientId = route.clientId, petId = route.petId)
                is ProviderRoute.patientChart -> PatientChartScreen(petId = route.petId)
                is ProviderRoute.record -> RecordDetailScreen(recordId = route.id)
                is ProviderRoute.hospitalization -> HospitalDetailScreen(hospitalizationId = route.id)
                is ProviderRoute.staffMember -> StaffMemberScreen(memberId = route.id)
                is ProviderRoute.olfatoChat -> OlfatoChatScreen(chatId = route.id)
                is ProviderRoute.invoice -> InvoiceDetailScreen(invoiceId = route.id)
                is ProviderRoute.sale -> SaleDetailScreen(saleId = route.id)
                is ProviderRoute.stay -> StayDetailScreen(stayId = route.id)
                is ProviderRoute.bookVisit -> BookVisitScreen(prefill = route.prefill)
                is ProviderRoute.storeCheckout -> StoreCheckoutScreen(prefill = route.prefill)
                is ProviderRoute.schedule -> ScheduleScreen(filter = route.filter)
                is ProviderRoute.newRecord -> NewRecordScreen(prefill = route.prefill)
                is ProviderRoute.olfatoPetChat -> OlfatoPetChatScreen(petId = route.petId)
                is ProviderRoute.page -> route.content()
            }
        }
    }
}

/** A section's page. */
@Composable
fun SectionView(section: ProviderSection, modifier: Modifier = Modifier) {
    Box(modifier) {
        when (section) {
            ProviderSection.home -> HomeScreen()
            ProviderSection.schedule -> ScheduleScreen()
            ProviderSection.appointments -> AppointmentsListScreen()
            ProviderSection.stays -> StaysScreen()
            ProviderSection.hospital -> HospitalScreen()
            ProviderSection.grooming -> GroomingScreen()
            ProviderSection.groups -> GroupsScreen()
            ProviderSection.homeVisits -> HomeVisitsScreen()
            ProviderSection.clients -> ClientsScreen()
            ProviderSection.patients -> PatientsScreen()
            ProviderSection.due -> DueScreen()
            ProviderSection.invitations -> InvitationsScreen()
            ProviderSection.records -> RecordsScreen()
            ProviderSection.olfato -> OlfatoScreen()
            ProviderSection.store -> StoreScreen()
            ProviderSection.inventory -> InventoryScreen()
            ProviderSection.pharmacy -> PharmacyScreen()
            ProviderSection.catalog -> CatalogScreen()
            ProviderSection.invoices -> InvoicesScreen()
            ProviderSection.finance -> FinanceScreen()
            ProviderSection.billing -> BillingScreen()
            ProviderSection.settings -> SettingsScreen()
            ProviderSection.staff -> StaffScreen()
            ProviderSection.availability -> AvailabilityScreen()
            ProviderSection.rooms -> RoomsScreen()
            ProviderSection.locations -> LocationsScreen()
            ProviderSection.notifications -> NotificationsScreen()
            ProviderSection.notificationSettings -> NotificationSettingsScreen()
            ProviderSection.relocate -> RelocateScreen()
            ProviderSection.accountSecurity -> AccountSecurityScreen()
        }
    }
}
