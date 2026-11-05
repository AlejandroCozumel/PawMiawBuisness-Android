package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

//
//  StockPharmacy.kt (iOS StockPharmacy.swift)
//
//  Farmacia (web provider.pharmacy.tsx → components/app/pharmacy/pharmacy-page.tsx):
//  what waits (to hand over, refills, drafts to sign), every prescription of
//  the business, the controlled register and the period's report, with the
//  counts from `/provider-pharmacy/counts` on top. A prescription is started
//  here too, and opens on its own page. See docs/prescriptions.md (PawMiawIos).
//

@Composable
fun PharmacyScreen(modifier: Modifier = Modifier) {
    StockPharmacyPage(modifier)
}

/** A prescription to open on its own page. */
data class StockRxTarget(val id: String)

/** What this person may do in the Pharmacy (web prescriptionRights + pharmacy-page). */
class StockRxRights(w: Workspace, counts: StockPharmacyCounts? = null) {
    val canRead: Boolean = w.canAny(Permission.recordsView, Permission.salesSell, Permission.billingEditInvoice)
    val canWrite: Boolean = w.can(Permission.recordsCreate)
    val canSign: Boolean = w.can(Permission.recordsApprove)
    val canDispenseAtVisit: Boolean = w.can(Permission.billingEditInvoice)
    val canViewClients: Boolean = w.can(Permission.clientsView)
    val canSell: Boolean = w.can(Permission.salesSell)
    val canHandOverControlled: Boolean = counts?.canHandOverControlled ?: w.can(Permission.pharmacyDispenseControlled)
    val canLogRefill: Boolean = counts?.canLogRefill ?: (w.can(Permission.salesSell) || w.can(Permission.recordsCreate))
    /** The register and the report: owners, admins, who signs or adjusts stock. */
    val canLog: Boolean = w.isOwnerOrAdmin || w.can(Permission.recordsApprove) || w.can(Permission.inventoryAdjust)
}

enum class StockPharmacyTab { todo, prescriptions, register, report }

/** Swift's `try?` for a read: null on any failure, cancellation passed on. */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

@Composable
private fun StockPharmacyPage(modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    var counts by remember { mutableStateOf<StockPharmacyCounts?>(null) }
    var tab by remember { mutableStateOf(StockPharmacyTab.todo) }
    val focus = remember { mutableStateOf(StockTodoFocus.all) }
    val rxFilters = remember { mutableStateOf(StockRxFilters()) }
    var writing by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }

    val rights = StockRxRights(workspace, counts = counts)
    val tabs = if (rights.canLog) {
        listOf(StockPharmacyTab.todo, StockPharmacyTab.prescriptions, StockPharmacyTab.register, StockPharmacyTab.report)
    } else {
        listOf(StockPharmacyTab.todo, StockPharmacyTab.prescriptions)
    }

    fun open(id: String) {
        // `.navigationDestination(item: $openRx)`: the prescription's own page.
        nav.push(ProviderRoute.page("rx:$id") { StockPrescriptionScreen(prescriptionId = id) })
    }

    suspend fun loadCounts() {
        if (!StockRxRights(workspace, counts = counts).canRead) return
        orNull { API.get<StockPharmacyCounts>("/provider-pharmacy/counts") }?.let { counts = it }
    }

    /** What waits in To do, on its tab. */
    val waiting: Int? = counts?.let { (it.refillsWaiting ?: 0) + (it.draftsToSign ?: 0) + (it.toHandOver ?: 0) }

    fun label(tab: StockPharmacyTab): String = when (tab) {
        StockPharmacyTab.todo -> L("To do")
        StockPharmacyTab.prescriptions -> L("Prescriptions")
        StockPharmacyTab.register -> L("Controlled register")
        StockPharmacyTab.report -> L("Report")
    }

    fun icon(tab: StockPharmacyTab): String = when (tab) {
        StockPharmacyTab.todo -> "clipboard-list"
        StockPharmacyTab.prescriptions -> "file-text"
        StockPharmacyTab.register -> "scale"
        StockPharmacyTab.report -> "pill"
    }

    fun value(number: Int?): String = number?.toString() ?: "—"

    providerPage(L("Pharmacy"), root = true, modifier = modifier) {
        if (workspace.current != null && !rights.canRead) {
            PageScroll {
                EmptyState(
                    systemImage = "lock", title = L("You do not have access to prescriptions."),
                    message = L("Ask the workspace owner to let your role see records or sell."), panel = true,
                )
            }
        } else {
            PageScroll(refresh = { loadCounts(); announceDataChanged() }) {
                // MARK: Header (web ProviderModuleHeader: Pill, teal)
                // A tile filters the page; with nothing behind it, it is only a number.
                val handOver = counts?.toHandOver ?: 0
                val refills = counts?.refillsWaiting ?: 0
                val pending = counts?.refillsPending ?: 0
                val drafts = counts?.draftsToSign ?: 0
                val expiring = counts?.expiringSoon ?: 0
                val stats = mutableListOf(
                    ModuleStat(
                        "handover", label = L("To hand over"), value = value(counts?.toHandOver), icon = "package-check", accent = ChipTone.waiting.color,
                        attention = handOver > 0, active = tab == StockPharmacyTab.todo && focus.value == StockTodoFocus.handover,
                        action = if (handOver > 0) ({ tab = StockPharmacyTab.todo; focus.value = StockTodoFocus.handover }) else null,
                    ),
                    ModuleStat(
                        "refills", label = L("Refills waiting"), value = value(counts?.refillsWaiting),
                        hint = if (pending > 0) LF("%d for a vet to answer", pending) else null,
                        icon = "bell-ring", accent = ChipTone.waiting.color, attention = pending > 0,
                        active = tab == StockPharmacyTab.todo && focus.value == StockTodoFocus.refills,
                        action = if (refills > 0) ({ tab = StockPharmacyTab.todo; focus.value = StockTodoFocus.refills }) else null,
                    ),
                    ModuleStat(
                        "drafts", label = L("Drafts to sign"), value = value(counts?.draftsToSign), icon = "pen-line", accent = ChipTone.upcoming.color,
                        active = tab == StockPharmacyTab.todo && focus.value == StockTodoFocus.drafts,
                        action = if (drafts > 0) ({ tab = StockPharmacyTab.todo; focus.value = StockTodoFocus.drafts }) else null,
                    ),
                    ModuleStat(
                        "expiring", label = LF("Expiring in %d days", counts?.expiringDays ?: 7), value = value(counts?.expiringSoon),
                        hint = L("Signed, with something left"), icon = "calendar-clock", accent = Theme.lime,
                        active = tab == StockPharmacyTab.prescriptions && rxFilters.value.expiring,
                        action = if (expiring > 0) ({ tab = StockPharmacyTab.prescriptions; rxFilters.value = StockRxFilters(state = "all", expiring = true) }) else null,
                    ),
                )
                counts?.stock?.let { stock ->
                    val either = stock.either ?: 0
                    val hint = if (either > 0) {
                        listOfNotNull(
                            if ((stock.low ?: 0) > 0) LF("%d low", stock.low ?: 0) else null,
                            if ((stock.expiring ?: 0) > 0) LF("%d expiring", stock.expiring ?: 0) else null,
                            if ((stock.expired ?: 0) > 0) LF("%d expired", stock.expired ?: 0) else null,
                        ).joinToString(" · ")
                    } else {
                        L("In Inventory")
                    }
                    stats.add(
                        ModuleStat(
                            "stock", label = L("Medicines low or expiring"), value = "$either", hint = hint, icon = "boxes", accent = Theme.mango,
                            attention = (stock.expired ?: 0) > 0,
                            action = if (either > 0) ({
                                val target = StockInventoryTarget(
                                    filter = if ((stock.low ?: 0) > 0) StockFilter.low else if ((stock.expiring ?: 0) > 0) StockFilter.expiring else StockFilter.expired,
                                )
                                // `.navigationDestination(item: $inventory)`: Inventory on its medicines.
                                nav.push(ProviderRoute.page("pharmacy-inventory:${target.id}") {
                                    StockInventoryPage(initialFilter = target.filter, initialMedicinesOnly = true)
                                })
                            }) else null,
                        ),
                    )
                }

                ModuleHeader(
                    icon = ProviderSection.pharmacy.headerIcon,
                    title = L("Pharmacy"),
                    subtitle = L("Prescriptions, refills, what was handed over and the controlled register."),
                    accent = ProviderSection.pharmacy.headerAccent,
                    stats = stats,
                    storageKey = "pharmacy",
                    actions = if (rights.canWrite) ({ PMButton(L("New prescription"), systemImage = "plus") { writing = true } }) else null,
                )
                ToneTabs(
                    tabs = tabs.map { ToneTabs.Tab(it, label(it), icon = icon(it), count = if (it == StockPharmacyTab.todo) waiting else null) },
                    selection = stateOf({ tab }, { next ->
                        tab = next
                        if (next == StockPharmacyTab.todo) focus.value = StockTodoFocus.all
                    }),
                )
                when (tab) {
                    StockPharmacyTab.todo -> StockTodoTab(
                        focus = focus, counts = counts, rights = rights, onOpen = ::open,
                        onNew = if (rights.canWrite) ({ writing = true }) else null,
                        onLogRefill = if (rights.canLogRefill) ({ logging = true }) else null,
                    )
                    StockPharmacyTab.prescriptions -> StockPrescriptionsTab(
                        filters = rxFilters, rights = rights, onOpen = ::open,
                        onNew = if (rights.canWrite) ({ writing = true }) else null,
                    )
                    StockPharmacyTab.register -> StockRegisterTab(onOpen = ::open)
                    StockPharmacyTab.report -> StockReportTab()
                }
            }
        }
    }
    ReloadsOnScope { loadCounts() }
    LaunchedEffect(workspace.scopeVersion) {
        while (isActive) {
            delay(60_000)
            if (!isActive) return@LaunchedEffect
            loadCounts()
        }
    }
    if (writing) {
        AppSheet(onDismissRequest = { writing = false }) {
            StockPrescriptionSheet(pet = null, draft = null) { saved -> open(saved.id) }
        }
    }
    if (logging) {
        AppSheet(onDismissRequest = { logging = false }) {
            StockLogRefillSheet(prescription = null)
        }
    }
}

/** Inventory opened from the Pharmacy on its medicines. */
data class StockInventoryTarget(val filter: StockFilter) {
    val id: String get() = filter.rawValue
}

// MARK: - Shared rows

object StockRxWords {
    fun state(state: String): String = when (state) {
        "draft" -> L("Draft@rx")
        "active" -> L("Active@rx")
        "expired" -> L("Expired@rx")
        "used_up" -> L("Used up")
        "cancelled" -> L("Cancelled@rx")
        "all" -> L("All@rx")
        else -> state
    }

    /** A prescription's state as its status colour. */
    fun stateState(state: String): ProviderState = when (state) {
        "draft" -> ProviderState.upcoming
        "active" -> ProviderState.active
        "used_up" -> ProviderState.done
        else -> ProviderState.closed
    }

    fun refill(status: String): String = when (status) {
        "pending" -> L("Waiting for the vet")
        "approved" -> L("Approved")
        "ready" -> L("Ready for pickup")
        "declined" -> L("Declined")
        "cancelled" -> L("Withdrawn")
        "picked_up" -> L("Picked up")
        else -> status
    }

    fun refillState(status: String): ProviderState = when (status) {
        "pending" -> ProviderState.waiting
        "approved" -> ProviderState.upcoming
        "ready" -> ProviderState.done
        "declined" -> ProviderState.problem
        else -> ProviderState.closed
    }

    fun schedule(schedule: String?): String? = when (schedule) {
        "mx_group_1" -> L("Group I (quantified prescription)")
        "us_c2" -> L("Schedule II (C-II)")
        "us_c3" -> L("Schedule III (C-III)")
        "us_c4" -> L("Schedule IV (C-IV)")
        "us_c5" -> L("Schedule V (C-V)")
        "controlled_other" -> L("Controlled")
        else -> if (schedule != null && schedule.isNotEmpty()) L("Controlled") else null
    }

    fun block(block: String?): String? = when (block) {
        "not_in_catalog" -> L("Not in the Catalog — sold outside PawMiaw")
        "nothing_left" -> L("Nothing left to hand over")
        "not_signed" -> L("Not signed yet")
        "cancelled" -> L("Cancelled@rx")
        "expired" -> L("Expired@rx")
        else -> null
    }

    /** "today", "waiting 1 day", "waiting 3 days". */
    fun waiting(since: String?): String {
        val date = DateText.parse(since) ?: return L("today")
        val zone = Workspace.shared.timeZone
        val days = maxOf(0, ChronoUnit.DAYS.between(date.atZone(zone).toLocalDate(), LocalDate.now(zone)).toInt())
        if (days == 0) return L("today")
        return if (days == 1) L("waiting 1 day") else LF("waiting %d days", days)
    }

    /** The server's refusals by code (web PRESCRIPTION_ERROR_MESSAGES), else its own sentence. */
    fun error(error: Throwable, fallback: String = L("Something went wrong. Try again.")): String {
        val code = (error as? StockRefusal)?.code ?: (error as? APIError)?.code
        val status = (error as? StockRefusal)?.status ?: (error as? APIError)?.status
        if (code == "pet_archived" && error is StockRefusal) {
            if (error.body["deceased"].bool == true) return L("This pet has died — no new prescriptions can be written for it.")
            if (error.body["deceased"].bool == false) return L("This pet is archived — restore it before writing a prescription.")
        }
        if (code != null) messages[code]?.let { return L(it) }
        if (status == 403) return L("You don’t have permission to do this.")
        if (status == 404) return L("It wasn’t found. It may have been removed.")
        return stockErrorText(error, fallback)
    }

    val messages: Map<String, String> = mapOf(
        "credential_required" to "Add your professional credential to sign prescriptions.",
        "dea_required" to "A controlled medicine needs your DEA number on the prescription.",
        "prescription_not_found" to "That prescription wasn’t found.",
        "prescription_not_signed" to "That prescription isn’t signed yet.",
        "prescription_cancelled" to "That prescription was cancelled.",
        "prescription_expired" to "That prescription expired — ask the vet for a new one.",
        "prescription_exhausted" to "That prescription has nothing left to fill — ask the vet for a refill.",
        "prescription_controlled_forbidden" to "A controlled medicine is handed over by a vet — ask one to do it.",
        "refill_already_open" to "This medicine already has a refill request open.",
        "controlled_no_electronic" to "A controlled prescription goes on paper, signed by hand — it can’t be sent as a link.",
        "controlled_separate" to "A controlled medicine goes on a prescription of its own.",
        "already_signed" to "This prescription is already signed.",
        "not_draft" to "A signed prescription can’t change — cancel it and write a new one.",
        "reason_required" to "Say why.",
        "items_required" to "Add at least one medicine.",
        "too_many_items" to "Up to 10 medicines per prescription.",
        "medicine_required" to "Name the medicine.",
        "active_ingredient_required" to "Add the active ingredient (NOM-064 asks for it next to the commercial name).",
        "dose_required" to "Add the dose.",
        "frequency_required" to "Say how often it is given.",
        "quantity_invalid" to "Say how much to dispense (a whole number from 1).",
        "refills_invalid" to "Refills are a whole number from 0 to 12.",
        "refills_not_allowed" to "This controlled medicine allows fewer refills.",
        "duration_invalid" to "The duration is a number of days from 1 to 730.",
        "dose_invalid" to "The dose must be more than zero.",
        "prescription_changed" to "This prescription changed while it was being signed. Here it is as it stands now: read it again and sign.",
        "catalog_item_not_found" to "A medicine on this prescription is no longer in the Catalog. Choose it again or write it in.",
        "schedule_country_mismatch" to "That controlled class isn’t used in this business’s country.",
        "valid_days_invalid" to "A prescription is valid for 1 to 365 days.",
        "refill_not_open" to "This request was already answered.",
        "refill_not_found" to "That refill request wasn’t found.",
        "bill_closed" to "This visit’s bill is closed — dispense it at the desk.",
        "not_a_visit" to "This prescription wasn’t written at a visit.",
        "number_required" to "Type the number.",
        "cedula_format" to "A cédula profesional has 6 to 8 digits.",
        "state_required" to "Choose the state that issued the license.",
        "dea_format" to "That isn’t a valid DEA number (two letters and seven digits).",
        "dea_address_required" to "Add the address the DEA registration is for.",
        "issuer_required" to "Say who issued the license.",
        "credential_exists" to "This credential is already on the list.",
        "forbidden" to "You don’t have permission to do this.",
    )
}

/**
 * Whose row it is, as the Records list shows it (web `PetLine` → `PetCell`): the pet's face, its
 * name, what it is ("Perro · Schnauzer", so two Lunas don't pass for one) and its owner.
 */
@Composable
fun StockPetLine(
    petName: String?,
    species: String?,
    photoUrl: String?,
    clientName: String?,
    breed: String? = null,
    modifier: Modifier = Modifier,
) {
    val language = if (LocalizationManager.currentLanguage == AppLanguage.english) "en" else "es"
    val kindLine = listOfNotNull(StockDocWords.species(species, language), breed.nonEmpty).joinToString(" · ").nonEmpty
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PetAvatar(name = petName, species = species, photoURL = photoUrl, size = 40.dp)
        Column {
            Text(petName.nonEmpty ?: L("Patient"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (kindLine != null) Text(kindLine, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            clientName.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "Controlled" (or its class) as a pill (web `ControlledPill`): the red tone with the scale. */
@Composable
fun StockControlledChip(label: String? = null, modifier: Modifier = Modifier) {
    Chip(label ?: L("Controlled"), color = Theme.destructive, percent = 22.0, icon = "scale", dot = true, modifier = modifier)
}

/** A prescription's state as a pill (web `StatePill`). */
@Composable
fun StockRxStatePill(state: String, modifier: Modifier = Modifier) {
    Chip(StockRxWords.state(state), color = StockRxWords.stateState(state).color, percent = 22.0, dot = true, modifier = modifier)
}

/** A refill request's status as a pill (web `RefillPill`). */
@Composable
fun StockRefillPill(status: String, modifier: Modifier = Modifier) {
    Chip(
        StockRxWords.refill(status),
        color = if (status in listOf("pending", "approved", "ready", "declined")) StockRxWords.refillState(status).color else Theme.borderStrong,
        percent = 22.0, dot = true, modifier = modifier,
    )
}

/** A small chip of the prescription sheet (web `RxChip`): 11.2 extrabold on the tone at 14%. */
@Composable
fun StockRxChip(
    text: String,
    tone: Color = Theme.inkSoft,
    icon: String? = null,
    strong: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(Theme.wash(tone, if (strong) 26.0 else 14.0), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Glyph(icon, 12.dp, tint = Theme.inkStrong)
        Text(text, style = brandText(11.2, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 1, softWrap = false)
    }
}

/** "Controlled" on the prescription sheet (web `ControlledChip`): the coral chip with the scale. */
@Composable
fun StockRxControlledChip(label: String? = null, modifier: Modifier = Modifier) {
    StockRxChip(text = label ?: L("Controlled"), tone = Theme.coral, icon = "scale", strong = true, modifier = modifier)
}

/**
 * A number of the prescription form (web `RxField` + `RxSuffixed`): the kit's label, the field
 * chrome, the unit inside it.
 */
@Composable
fun StockRxNumberField(
    label: String,
    text: MutableState<String>,
    key: String? = null,
    form: FormState? = null,
    prompt: String = "",
    hint: String? = null,
    optional: Boolean = false,
    suffix: String? = null,
    decimal: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FormField(label = label, key = key, hint = hint, form = form, optional = optional, modifier = modifier) {
        FieldChrome(invalid = key?.let { form?.error(it) } != null) {
            PlainTextField(
                text = text,
                prompt = prompt,
                modifier = Modifier.weight(1f),
                keyboard = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                style = Theme.bodyFont.monospacedDigit(),
            )
            if (suffix != null) {
                Spacer(Modifier.width(6.dp))
                Text(suffix, style = Theme.text(14), color = Theme.inkSoft)
            }
        }
    }
    // `.onChange(of: text)`: any change clears the field's mark.
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(text.value) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (key != null) form?.clear(key)
    }
}

/** "Open chart", on every row (web `ChartLink`). */
@Composable
fun StockChartLink(clientId: String?, petId: String?, canViewClients: Boolean, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val client = clientId.nonEmpty
    val pet = petId.nonEmpty
    if (canViewClients && client != null && pet != null) {
        PMButton(L("Open chart"), variant = ButtonVariant.link, size = ButtonSize.modal, modifier = modifier) {
            nav.push(ProviderRoute.clientPet(clientId = client, petId = pet))
        }
    }
}

/**
 * A period of days for a list or a report (web `DateRangeMenu`): a chip that names the range
 * ("Esta semana", "1 sep – 15 sep") in the chips' "on" colours, or "Any date"; it opens the quick
 * choices and the two days.
 */
@Composable
fun StockPeriodButton(
    label: String,
    noneLabel: String = L("Any date"),
    from: MutableState<String>,
    to: MutableState<String>,
    modifier: Modifier = Modifier,
) {
    var choosing by remember { mutableStateOf(false) }
    val chosen = from.value.isNotEmpty() || to.value.isNotEmpty()
    val spoken = if (chosen) "$label: ${StockPeriod.label(from.value, to.value)}" else label
    FilterChip(
        title = if (chosen) StockPeriod.label(from.value, to.value) else noneLabel,
        isSelected = chosen,
        icon = "calendar-range",
        modifier = modifier.semantics { contentDescription = spoken },
    ) {
        choosing = true
    }
    if (choosing) {
        AppSheet(onDismissRequest = { choosing = false }) {
            StockPeriodSheet(title = label, noneLabel = noneLabel, from = from, to = to)
        }
    }
}

/** The quick ranges of the web's date menu (records-list-logic.ts `rangePresets`). */
object StockPeriod {
    enum class Preset { today, week, month }

    fun range(preset: Preset): Pair<String, String> {
        val today = LocalDate.now(Workspace.shared.timeZone)
        return when (preset) {
            Preset.today -> Pair(today.toString(), today.toString())
            Preset.week -> {
                // The week starts on Monday (`firstWeekday = 2`).
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Pair(start.toString(), start.plusDays(6).toString())
            }
            Preset.month -> Pair(today.withDayOfMonth(1).toString(), today.with(TemporalAdjusters.lastDayOfMonth()).toString())
        }
    }

    fun name(preset: Preset): String = when (preset) {
        Preset.today -> L("Today")
        Preset.week -> L("This week")
        Preset.month -> L("This month")
    }

    fun preset(from: String, to: String): Preset? = Preset.entries.firstOrNull { range(it) == Pair(from, to) }

    /** What a range is called: its quick choice's name, else its days. */
    fun label(from: String, to: String): String {
        preset(from, to)?.let { return name(it) }
        if (from.isNotEmpty() && to.isNotEmpty()) return if (from == to) StockText.day(from) else "${StockText.day(from)} – ${StockText.day(to)}"
        return if (from.isNotEmpty()) LF("From %@", StockText.day(from)) else LF("Until %@", StockText.day(to))
    }
}

@Composable
private fun StockPeriodSheet(title: String, noneLabel: String, from: MutableState<String>, to: MutableState<String>) {
    val dismiss = LocalDismiss.current
    // `.onAppear` (once): the drafts start from what is chosen.
    val draftFrom = remember { mutableStateOf(from.value) }
    val draftTo = remember { mutableStateOf(to.value) }
    val form = remember { FormState() }

    ProviderModal(
        title = title, systemImage = "calendar-range", accent = ModalAccent.aqua, form = form, primaryTitle = L("Apply"),
        onPrimary = onPrimary@{
            val wrong = draftFrom.value.isNotEmpty() && draftTo.value.isNotEmpty() && draftTo.value < draftFrom.value
            if (!form.check(listOf("to" to (if (wrong) L("The end can’t be before the start.") else null)))) return@onPrimary
            from.value = draftFrom.value
            to.value = draftTo.value
            dismiss()
        },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        val current = StockPeriod.preset(from.value, to.value)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StockPeriod.Preset.entries.forEach { preset ->
                PeriodChoice(StockPeriod.name(preset), on = current == preset) {
                    val range = StockPeriod.range(preset)
                    from.value = range.first; to.value = range.second
                    dismiss()
                }
            }
            PeriodChoice(noneLabel, on = from.value.isEmpty() && to.value.isEmpty()) {
                from.value = ""; to.value = ""
                dismiss()
            }
        }
        Text(L("Or choose the days"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
        DayField(label = L("From"), day = draftFrom, optional = true, clearable = true)
        DayField(label = L("To"), day = draftTo, key = "to", form = form, optional = true, clearable = true)
    }
}

/** A quick choice, as the web's menu rows: 14 bold, a blue check on the one in use. */
@Composable
private fun PeriodChoice(title: String, on: Boolean, action: () -> Unit) {
    val shape = RoundedCornerShape(Theme.chipRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (on) Theme.wash(Theme.blue, 12.0) else Color.Transparent, shape)
            .semantics { selected = on }
            .plainButton(onClick = action)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
        if (on) Glyph("check", 16.dp, tint = Theme.blue)
    }
}
