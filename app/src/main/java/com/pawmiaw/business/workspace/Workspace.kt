package com.pawmiaw.business

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.text.Collator
import java.time.ZoneId

//
//  The business this person is working in, the place ("sede") this device works at, and
//  what the person may do there (iOS Workspace.swift). One object for the whole app:
//  screens and code read `Workspace.shared` (its state is Compose state, so a composable
//  that reads it recomposes when it changes).
//
//  Locations follow the web (hooks/use-provider-locations.ts): each business remembers the
//  place chosen on this device; with one active place nothing is sent (`locationId == null`);
//  owners and admins with two or more may choose "Todas las sedes", and then pages that
//  belong to one place ask which one first (`RequireLocation`).
//

@Serializable
data class WorkspaceAvailability(val timezone: String? = null)

@Serializable
data class ProviderWorkspace(
    val clinicId: String,
    val organizationId: String,
    val name: String,
    val slug: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val availability: WorkspaceAvailability? = null,
    val enabledCategories: List<String> = emptyList(),
    val status: String? = null,
    val role: String? = null,
    val plan: String? = null,
    val billingStatus: String? = null,
    val billingProvider: String? = null,
    val seatLimit: Int? = null,
    val locationLimit: Int? = null,
    val trialEndsAt: String? = null,
    val currentPeriodEndsAt: String? = null,
    val isActive: Boolean? = null,
    val effectivePermissions: List<String> = emptyList(),
    val canManagePlan: Boolean? = null,
    val logoUrl: String? = null,
    val setupCompletedAt: String? = null,
    val needsRegionConfirmation: Boolean? = null,
    val country: String? = null,
    val clientLanguage: String? = null,
    val offersHomeVisits: Boolean? = null,
) {
    val id: String get() = clinicId
}

@Serializable
data class ProviderLocation(
    val id: String,
    val clinicId: String? = null,
    val name: String,
    val isPrimary: Boolean = false,
    val status: String = "active",
    val sortOrder: Int? = null,
    val timezone: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val availability: WorkspaceAvailability? = null,
)

@Serializable
data class LocationPlan(
    val allowsMultiple: Boolean? = null,
    val requiredPlan: String? = null,
    val plan: String? = null,
    val limit: Int? = null,
    val used: Int? = null,
    val paused: Int? = null,
    val canAdd: Boolean? = null,
    val reason: String? = null,
    val addonAvailable: Boolean? = null,
    val talkToSales: Boolean? = null,
)

@Serializable
data class ProviderLocationsResponse(
    val locations: List<ProviderLocation> = emptyList(),
    val memberLocationIds: List<String>? = null,
    val openShiftLocationIds: List<String>? = null,
    val plan: LocationPlan? = null,
)

@Serializable
private data class StoreSettingsCurrency(val currency: String? = null)

class Workspace private constructor() {
    sealed class Phase {
        data object idle : Phase()
        data object loading : Phase()
        data object none : Phase()
        data object choosing : Phase()
        data object ready : Phase()
        data class failed(val message: String) : Phase()
    }

    var phase: Phase by mutableStateOf(Phase.idle)
        private set
    var workspaces: List<ProviderWorkspace> by mutableStateOf(emptyList())
        private set
    var current: ProviderWorkspace? by mutableStateOf(null)
        private set
    var locationsResponse: ProviderLocationsResponse? by mutableStateOf(null)
        private set
    var storedLocationId: String? by mutableStateOf(null)
        private set
    var currency: String by mutableStateOf("MXN")
        private set
    /** Bumped whenever the business or the place changes: screens reload on it. */
    var scopeVersion: Int by mutableIntStateOf(0)
        private set

    companion object {
        val shared = Workspace()
        /** The stored choice for "every place" (iOS `Workspace.allLocations`, the string). */
        const val allLocationsId = "all"

        private fun locationKey(clinicId: String) = "pawmiaw_negocios_location_$clinicId"

        fun sorted(locations: List<ProviderLocation>): List<ProviderLocation> {
            val collator = Collator.getInstance(appLocale)
            return locations.sortedWith { a, b ->
                when {
                    a.isPrimary != b.isPrimary -> if (a.isPrimary) -1 else 1
                    (a.sortOrder ?: 0) != (b.sortOrder ?: 0) -> (a.sortOrder ?: 0).compareTo(b.sortOrder ?: 0)
                    else -> collator.compare(a.name, b.name)
                }
            }
        }
    }

    // MARK: Permissions

    private val permissionSet: Set<String> get() = current?.effectivePermissions?.toSet() ?: emptySet()

    fun can(permission: Permission): Boolean = permission.rawValue in permissionSet
    fun canAll(vararg permissions: Permission): Boolean = permissions.all { can(it) }
    fun canAny(vararg permissions: Permission): Boolean = permissions.any { can(it) }

    val moneyLevel: MoneyLevel get() = MoneyLevel.of(permissionSet)
    val role: WorkspaceRole? get() = WorkspaceRole.from(current?.role)
    val isOwnerOrAdmin: Boolean get() = role == WorkspaceRole.owner || role == WorkspaceRole.admin

    fun offers(vararg categories: BusinessCategory): Boolean {
        val enabled = current?.enabledCategories?.toSet() ?: emptySet()
        return categories.any { it.rawValue in enabled }
    }

    val offersHomeVisits: Boolean get() = current?.offersHomeVisits ?: false
    /** Setup essentials not finished: the server refuses money, invitations and bookings. */
    val inSetup: Boolean get() = current != null && current?.setupCompletedAt == null

    // MARK: Locations

    val allLocations: List<ProviderLocation> get() = locationsResponse?.locations ?: emptyList()

    val activeLocations: List<ProviderLocation> get() = sorted(allLocations.filter { it.status == "active" })

    /** Active places this member may choose from, primary first. */
    val memberLocations: List<ProviderLocation>
        get() {
            val active = activeLocations
            val ids = locationsResponse?.memberLocationIds
            if (ids.isNullOrEmpty()) return active
            val theirs = active.filter { it.id in ids }
            return theirs.ifEmpty { active }
        }

    /** The business has two or more active places. */
    val multiLocation: Boolean get() = activeLocations.size >= 2
    val canSeeAllLocations: Boolean get() = multiLocation && isOwnerOrAdmin
    /** "Todas las sedes" is chosen. */
    val allLocationsChosen: Boolean get() = canSeeAllLocations && storedLocationId == allLocationsId
    val canSwitchLocation: Boolean get() = memberLocations.size >= 2 || canSeeAllLocations

    val selectedLocation: ProviderLocation?
        get() {
            val id = storedLocationId
            if (id != null && id != allLocationsId) memberLocations.firstOrNull { it.id == id }?.let { return it }
            return memberLocations.firstOrNull()
        }

    /** What location-scoped requests send: null with one place, or with "Todas las sedes". */
    val locationId: String?
        get() {
            if (allLocationsChosen) return null
            return if (multiLocation) selectedLocation?.id else null
        }

    /** A business-wide list's filter value: the place, "all", or nothing with one place. */
    val listLocationFilter: String?
        get() {
            if (!multiLocation) return null
            return if (allLocationsChosen) allLocationsId else selectedLocation?.id
        }

    /** The business's clock: the place's, else the workspace's (iOS `TimeZone` → `ZoneId`). */
    val timeZone: ZoneId
        get() {
            val id = (if (multiLocation) (selectedLocation?.timezone ?: selectedLocation?.availability?.timezone) else null)
                ?: current?.availability?.timezone
            return id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        }

    fun setLocation(id: String) {
        storedLocationId = id
        current?.clinicId?.let { AppContext.defaults.edit().putString(locationKey(it), id).apply() }
        scopeVersion += 1
    }

    fun setAllLocations() = setLocation(allLocationsId)

    // MARK: Loading

    suspend fun load() {
        if (current == null) phase = Phase.loading
        try {
            val list: List<ProviderWorkspace> = API.get("/provider-workspaces")
            val active: ProviderWorkspace? = runCatching { API.get<ProviderWorkspace>("/provider-workspaces/active") }.getOrNull()
            workspaces = list
            val resolved = active ?: list.firstOrNull { it.isActive == true } ?: list.singleOrNull()
            when {
                list.isEmpty() -> { current = null; phase = Phase.none }
                resolved != null -> enter(resolved)
                else -> phase = Phase.choosing
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = if (current == null) Phase.failed(errorText(e)) else Phase.ready
        }
    }

    /** Re-reads the current business (permissions, plan) without leaving the screen. */
    suspend fun refresh() {
        val id = current?.clinicId ?: return
        runCatching { API.get<List<ProviderWorkspace>>("/provider-workspaces") }.getOrNull()?.let { list ->
            workspaces = list
            list.firstOrNull { it.clinicId == id }?.let { current = it }
        }
        loadLocations()
    }

    suspend fun choose(workspace: ProviderWorkspace) {
        phase = Phase.loading
        try {
            val active: ProviderWorkspace? = API.post("/provider-workspaces/active", mapOf("clinicId" to workspace.clinicId, "organizationId" to workspace.organizationId))
            enter(active ?: workspace)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = Phase.failed(errorText(e))
        }
    }

    fun leaveToChooser() {
        phase = if (workspaces.isEmpty()) Phase.none else Phase.choosing
    }

    private suspend fun enter(workspace: ProviderWorkspace) {
        current = workspace
        storedLocationId = AppContext.defaults.getString(locationKey(workspace.clinicId), null)
        loadLocations()
        runCatching { API.get<StoreSettingsCurrency>("/provider-store/settings") }.getOrNull()?.currency?.nonEmpty?.let { currency = it }
        scopeVersion += 1
        phase = Phase.ready
    }

    private suspend fun loadLocations() {
        locationsResponse = runCatching { API.get<ProviderLocationsResponse>("/provider-locations", mapOf("includeArchived" to "true")) }.getOrNull()
    }

    fun reset() {
        workspaces = emptyList()
        current = null
        locationsResponse = null
        storedLocationId = null
        phase = Phase.idle
    }
}
