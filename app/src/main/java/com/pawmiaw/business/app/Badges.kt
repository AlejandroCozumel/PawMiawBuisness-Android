package com.pawmiaw.business

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

//
//  The numbers beside the sections (web provider-shell.tsx; iOS Badges.swift): records to
//  complete, pharmacy waiting, late stays, hospital doses needing someone, grooms needing
//  someone, late home visits, visits to charge. Each from the server, refreshed every minute
//  while the app is open, only for whoever may see that section. The shell provides one
//  (`LocalBadges.current`, iOS `@EnvironmentObject var badges: Badges`).
//

@Serializable private data class CountPage(val total: Int? = null)
@Serializable private data class LateStays(val lateArrivals: Int? = null, val lateDepartures: Int? = null)
@Serializable private data class HospitalAttention(val due: Int? = null, val late: Int? = null, val missed: Int? = null)
@Serializable private data class GroomingDay(val needsSomeone: Int? = null)
@Serializable private data class HomeDayCounts(val late: Int? = null)
@Serializable private data class HomeDay(val counts: HomeDayCounts? = null)
@Serializable private data class ChargingCount(val count: Int? = null)
@Serializable private data class PharmacyCounts(val badge: Int? = null)
@Serializable private data class OlfatoChatStatus(val available: Boolean? = null, val workspaceOn: Boolean? = null, val noticeOutdated: Boolean? = null)
@Serializable private data class OlfatoViewer(val chat: OlfatoChatStatus? = null)
@Serializable private data class UnreadSummary(val unreadCount: Int? = null)

/** Swift `try? await`: the answer, or null when it failed (a cancel still cancels). */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

@Stable
class Badges {
    var counts: Map<ProviderSection, Int> by mutableStateOf(emptyMap())
        private set
    var olfatoShown: Boolean by mutableStateOf(false)
        private set
    var pharmacyShown: Boolean by mutableStateOf(false)
        private set
    var unreadNotifications: Int by mutableIntStateOf(0)
        private set

    fun count(section: ProviderSection): Int = counts[section] ?: 0

    suspend fun refresh(w: Workspace) {
        if (w.current == null) return
        val location: String? = w.locationId
        val next = mutableMapOf<ProviderSection, Int>()

        val asksOlfato = w.can(Permission.olfatoChat)
        val asksStays = w.offers(BusinessCategory.boarding, BusinessCategory.daycare) && w.can(Permission.staysView)
        val asksHospital = w.offers(BusinessCategory.vet, BusinessCategory.emergency) && w.can(Permission.hospitalView)
        val asksGrooming = w.offers(BusinessCategory.groomer) && w.can(Permission.appointmentsView)
        val asksHome = w.offersHomeVisits && w.canAny(Permission.appointmentsView, Permission.staysView)
        val asksCharging = w.moneyLevel >= MoneyLevel.frontDesk
        val asksPharmacy = w.can(Permission.recordsApprove)
        val sign = w.can(Permission.recordsApprove) && w.can(Permission.recordsView)
        val paperwork = w.can(Permission.followupsView)
        val unfinished = w.can(Permission.recordsView)

        coroutineScope {
            val olfato = async { if (asksOlfato) attempt { API.get<OlfatoViewer>("/provider-olfato/viewer") } else null }
            val records = async { recordsToComplete(sign = sign, paperwork = paperwork, unfinished = unfinished, location = location) }
            val stays = async { if (asksStays) attempt { API.get<LateStays>("/provider-stays/late", mapOf("locationId" to location)) } else null }
            val hospital = async { if (asksHospital) attempt { API.get<HospitalAttention>("/provider-hospital/attention", mapOf("locationId" to location)) } else null }
            val grooming = async { if (asksGrooming) attempt { API.get<GroomingDay>("/provider-grooming/today", mapOf("locationId" to location)) } else null }
            val home = async { if (asksHome) attempt { API.get<HomeDay>("/provider-home-visits/today", mapOf("locationId" to location)) } else null }
            val charging = async {
                if (asksCharging) attempt { API.get<ChargingCount>("/provider-charging", mapOf("locationId" to location, "limit" to "0", "offset" to "0")) } else null
            }
            val pharmacy = async { if (asksPharmacy) attempt { API.get<PharmacyCounts>("/provider-pharmacy/counts") } else null }
            val unread = async { unreadNotifications() }

            val viewer = olfato.await()
            olfatoShown = asksOlfato && (viewer?.chat?.available ?: false) && (viewer?.chat?.workspaceOn ?: false)
            next[ProviderSection.records] = records.await()
            stays.await()?.let { late -> next[ProviderSection.stays] = (late.lateArrivals ?: 0) + (late.lateDepartures ?: 0) }
            hospital.await()?.let { attention -> next[ProviderSection.hospital] = (attention.due ?: 0) + (attention.late ?: 0) + (attention.missed ?: 0) }
            next[ProviderSection.grooming] = grooming.await()?.needsSomeone ?: 0
            next[ProviderSection.homeVisits] = home.await()?.counts?.late ?: 0
            next[ProviderSection.invoices] = charging.await()?.count ?: 0
            next[ProviderSection.pharmacy] = pharmacy.await()?.badge ?: 0
            // The Pharmacy: for a business that prescribes or dispenses (web lib/pharmacy.ts).
            val categories = w.current?.enabledCategories ?: emptyList()
            val offersPharmacy = categories.isEmpty() || categories.any { it in listOf("vet", "pharmacy", "emergency") }
            pharmacyShown = offersPharmacy && w.canAny(Permission.recordsView, Permission.salesSell, Permission.billingEditInvoice)
            unreadNotifications = unread.await()
            counts = next
        }
    }

    private companion object {
        suspend fun recordsToComplete(sign: Boolean, paperwork: Boolean, unfinished: Boolean, location: String?): Int {
            var total = 0
            if (sign) {
                val page = attempt {
                    API.get<CountPage>("/provider-records", mapOf("status" to "draft", "readyToSign" to "true", "limit" to "1", "offset" to "0", "locationId" to location))
                }
                total += page?.total ?: 0
            }
            if (paperwork) {
                val page = attempt {
                    API.get<CountPage>("/provider-follow-ups", mapOf("status" to "to_do", "kind" to "paperwork", "limit" to "1", "offset" to "0"))
                }
                total += page?.total ?: 0
            }
            if (unfinished) {
                val page = attempt {
                    API.get<CountPage>("/provider-records", mapOf("status" to "draft", "unfinished" to "true", "limit" to "1", "offset" to "0", "locationId" to location))
                }
                total += page?.total ?: 0
            }
            return total
        }

        suspend fun unreadNotifications(): Int {
            val body = attempt { API.get<UnreadSummary>("/provider-notifications/unread-summary") }
            return body?.unreadCount ?: 0
        }
    }
}

/** The shell's badges (iOS `@EnvironmentObject private var badges: Badges`). */
val LocalBadges = staticCompositionLocalOf { Badges() }
