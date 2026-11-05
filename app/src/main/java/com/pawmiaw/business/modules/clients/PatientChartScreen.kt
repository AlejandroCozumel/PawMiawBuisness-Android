package com.pawmiaw.business.clients

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.hospital.*
import com.pawmiaw.business.money.*
import com.pawmiaw.business.stock.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

//
//  PatientChartScreen.kt (iOS PatientChartScreen.swift)
//
//  The patient chart, the expediente (web: routes/provider.clients.$clientId.
//  pets.$petId.tsx and patient-chart/): the module header with the pet, its
//  owner and the numbers that matter (each opens its tab), the quick actions,
//  the red strip, the Olfato AI brief with the clinical summary, the
//  prescriptions and hospitalizations, then one card of tabs: the paged
//  history, visits, follow-ups, files, money and the pet's details. What a
//  role sees is the server's `viewer` ("who sees what").
//

/** A client's pet: its chart. */
@Composable
fun ClientPetScreen(clientId: String, petId: String, modifier: Modifier = Modifier) {
    ClientsChartView(clientId = clientId, petId = petId, modifier = modifier)
}

/** The chart of a pet known only by its id (from any screen): its client is found first. */
@Composable
fun PatientChartScreen(petId: String, modifier: Modifier = Modifier) {
    var clientId by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    /** The chart is read under its client: the pet's records, visits, follow-ups, then the patients list say whose it is. */
    suspend fun resolve() {
        val id = petId
        suspend fun read(path: String, query: Map<String, String?> = emptyMap()): JSONValue? = try {
            API.get<JSONValue>(path, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        read("/provider-client-pets/$id/chart-summary")?.let { summary -> chartClientId(summary)?.let { clientId = it; return } }
        read("/provider-appointments", mapOf("limit" to "1", "offset" to "0", "providerClientPetId" to id))?.let { visits ->
            chartClientId(visits)?.let { clientId = it; return }
        }
        read("/provider-follow-ups", mapOf("limit" to "1", "offset" to "0", "status" to "all", "providerClientPetId" to id))?.let { followUps ->
            chartClientId(followUps)?.let { clientId = it; return }
        }
        for (archived in listOf(false, true)) {
            var offset = 0
            while (offset < 1000) {
                val page: Page<ClientsPatientRow> = try {
                    API.get("/provider-patients", mapOf("limit" to "50", "offset" to "$offset", "archived" to if (archived) "true" else null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    break
                }
                page.items.firstOrNull { it.id == id }?.let { clientId = it.client.id; return }
                if (page.items.size < 50) break
                offset += 50
            }
        }
        failed = true
    }

    val found = clientId
    if (found != null) {
        ClientsChartView(clientId = found, petId = petId, modifier = modifier)
    } else if (failed) {
        providerPage(L("Patient chart"), modifier = modifier) {
            PageScroll {
                EmptyState(
                    systemImage = "paw-print", title = L("Pet not found"),
                    message = L("It may have been combined with another record, or it belongs to a client assigned to someone else."),
                    accent = Theme.lime, panel = true,
                )
            }
        }
    } else {
        providerPage(L("Patient chart"), modifier = modifier) {
            PageScroll { LoadingState() }
            androidx.compose.runtime.LaunchedEffect(Unit) { resolve() }
        }
    }
}

private fun chartClientId(value: JSONValue): String? {
    when (value) {
        is JsonObject -> {
            value["providerClientId"].string.nonEmpty?.let { return it }
            for (child in value.values) chartClientId(child)?.let { return it }
        }
        is JsonArray -> for (child in value) chartClientId(child)?.let { return it }
        else -> {}
    }
    return null
}

enum class ClientsChartTab(val rawValue: String) { history("history"), appointments("appointments"), followups("followups"), files("files"), billing("billing"), profile("profile") }

@Composable
fun ClientsChartView(clientId: String, petId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var chart by remember { mutableStateOf<ClientsChart?>(null) }
    var pet by remember { mutableStateOf<ClientsPet?>(null) }
    var clientName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var notFound by remember { mutableStateOf(false) }
    val tab = remember { mutableStateOf(ClientsChartTab.history) }
    var weighing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<ClientsClosingTarget?>(null) }
    var restoring by remember { mutableStateOf<ClientsRestoreTarget?>(null) }
    var client by remember { mutableStateOf<ClientsClient?>(null) }
    var mergingPet by remember { mutableStateOf<ClientsMergePetSource?>(null) }
    var movingPet by remember { mutableStateOf<ClientsMoveTarget?>(null) }
    var unlinkingPet by remember { mutableStateOf<ClientsUnlinkTarget?>(null) }
    var prescribing by remember { mutableStateOf(false) }
    var collectingOnAccount by remember { mutableStateOf(false) }
    var hospitalStays by remember { mutableStateOf<List<HospSummary>>(emptyList()) }

    val canView = workspace.can(Permission.clientsView) && (workspace.can(Permission.clientsAssignSelf) || workspace.can(Permission.clientsAssignAnyone))
    val canCreateRecords = workspace.can(Permission.recordsCreate) && (workspace.can(Permission.recordsAssignSelf) || workspace.can(Permission.recordsAssignAnyone))
    val canBook = workspace.can(Permission.appointmentsCreate)
    val canWeigh = workspace.can(Permission.petsEdit) || workspace.can(Permission.appointmentsEdit)
    val wide = LocalSizeClass.current == SizeClass.regular

    suspend fun load() {
        if (!canView) return
        val path = "/provider-clients/$clientId/pets/$petId"
        val clientPath = "/provider-clients/$clientId"
        try {
            coroutineScope {
                val clientAnswer = async { ClientsClientPage.fetch<ClientsClient>(true, clientPath) }
                chart = API.get<ClientsChart>("$path/chart")
                error = null
                if (workspace.can(Permission.hospitalView) && workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)) {
                    hospitalStays = ClientsHospitalCard.load(petId = petId)
                }
                val loaded = clientAnswer.await()
                if (loaded != null) {
                    clientName = loaded.ownerName
                    pet = loaded.allPets.firstOrNull { it.id == petId }
                    client = loaded
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? APIError)?.status == 404) notFound = true
            error = errorText(e)
        }
    }

    // MARK: Header

    /** "Perro · Beagle · Hembra · 3 años · 12.4 kg" and, while the family shares, their latest home weight. */
    fun subtitle(chart: ClientsChart): String {
        val p = chart.pet
        val home = chart.weights?.home
        return listOfNotNull(
            ClientsText.species(p.species), p.breed.nonEmpty, ClientsText.sex(p.sex), ClientsText.age(p.birthDate), ClientsText.weight(p.weightGrams),
            home?.let { LF("At home: %@ · %@", ClientsText.weight(it.latestGrams) ?: "—", ClientsText.shortDay(it.latestAt)) },
        ).joinToString(" · ")
    }

    /** The header's numbers (web `stats`): each opens the tab it is about. */
    @Composable
    fun stats(chart: ClientsChart): List<ModuleStat> {
        val archived = chart.isArchived
        val vaccines = chart.summary?.vaccines ?: emptyList()
        val overdue = vaccines.count { it.status == "overdue" }
        val dueSoon = vaccines.count { it.status == "due_soon" }
        val list = mutableListOf(
            ModuleStat(
                "last", label = L("Last visit"), value = chart.activity?.lastVisitAt?.let { ClientsText.shortDay(it) } ?: L("None yet"),
                icon = "stethoscope", accent = Theme.aqua, active = tab.value == ClientsChartTab.history, action = { tab.value = ClientsChartTab.history },
            ),
        )
        // A pet off the active lists has nothing coming and owes no vaccine.
        if (chart.viewer?.seesVisits == true && !archived) {
            list.add(
                ModuleStat(
                    "next", label = L("Next appointment"), value = chart.activity?.nextVisit?.let { ClientsText.shortDay(it.startsAt) } ?: L("Nothing booked"),
                    hint = chart.activity?.nextVisit?.serviceLabel, icon = "calendar-clock", accent = Theme.blue,
                    active = tab.value == ClientsChartTab.appointments, action = { tab.value = ClientsChartTab.appointments },
                ),
            )
        }
        if (chart.summary != null && !archived) {
            val value = when {
                overdue > 0 -> if (overdue == 1) L("1 overdue") else LF("%d overdue", overdue)
                dueSoon > 0 -> if (dueSoon == 1) L("1 due soon") else LF("%d due soon", dueSoon)
                vaccines.isEmpty() -> L("None on record")
                else -> L("Up to date")
            }
            list.add(ModuleStat("vaccines", label = L("Vaccines"), value = value, icon = "syringe", accent = Theme.mango, attention = overdue > 0))
        }
        val balance = chart.activity?.balance
        if (balance != null) {
            val owes = (balance.client ?: 0) > 0
            list.add(
                ModuleStat(
                    "balance", label = L("Balance"), value = if (owes) Money.format(balance.client, currency = balance.currency) else L("Nothing owed"),
                    hint = if (owes) LF("%@ owes", NameText.firstName(chart.client.name)) else null, icon = "circle-dollar-sign", accent = Theme.pink,
                    attention = owes, active = tab.value == ClientsChartTab.billing, action = { tab.value = ClientsChartTab.billing },
                ),
            )
        }
        return list
    }

    /** The owner, with call and WhatsApp, and whether the family is on PawMiaw (web `OwnerLine`); the pet's state off the active lists sits beside the title. */
    @Composable
    fun OwnerLine(chart: ClientsChart) {
        val openURL = rememberOpenURL()
        Text(
            chart.client.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.plainButton { nav.push(ProviderRoute.client(chart.client.id)) },
        )
        val phone = chart.client.phone.nonEmpty
        if (phone != null) {
            ContactLinks.phone(phone)?.let { tel ->
                Row(
                    Modifier.plainButton(label = LF("Call %@", chart.client.name)) { openURL(tel) },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("phone", 14.dp, tint = Theme.inkSoft)
                    Text(phone, style = Theme.text(14), color = Theme.inkSoft, maxLines = 1)
                }
            }
            ContactLinks.whatsApp(phone)?.let { whatsapp ->
                Row(
                    Modifier.plainButton(label = LF("WhatsApp %@", chart.client.name)) { openURL(whatsapp) },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("message-circle", 14.dp, tint = Theme.inkSoft)
                    Text("WhatsApp", style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        } else {
            Text(L("No phone"), style = Theme.text(14), color = Theme.inkSoft)
        }
        // It reads the pet's own link: a pet not matched to the family's stays clinic-only.
        ClientsLinkBadge(linked = chart.pet.linked == true)
    }

    @Composable
    fun MoreMenu(chart: ClientsChart) {
        val archived = chart.isArchived
        val movedTo = if (archived) chart.pet.movedTo else null
        val withClient = archived && (chart.pet.clientArchived == true || chart.pet.closing?.reason == "client_archived")
        val loadedClient = client
        val loadedPet = pet
        AppMenu(label = { IconCircleLabel() }, accessibilityLabel = LF("More for %@", chart.pet.name)) {
            item(L("Open client"), icon = "user-round") { nav.push(ProviderRoute.client(chart.client.id)) }
            if (workspace.can(Permission.petsEdit) && loadedPet != null) {
                item(if (chart.pet.linked == true) L("Edit clinic details") else L("Edit pet"), icon = "pencil") { editing = true }
            }
            if (workspace.can(Permission.petsEdit) && loadedClient != null && loadedPet != null) {
                item(L("Combine with another pet"), icon = "git-merge") {
                    mergingPet = ClientsMergePetSource(
                        clientId = loadedClient.id, clientName = loadedClient.ownerName, petId = loadedPet.id, name = loadedPet.petName,
                        species = loadedPet.petSpecies, breed = loadedPet.petBreed, photoUrl = loadedPet.photoUrl, linked = loadedPet.isLinked,
                        householdPetId = loadedPet.petId, details = ClientsPetDetails.of(loadedPet, createdAt = null),
                    )
                }
            }
            if (workspace.can(Permission.clientsEdit) && loadedClient != null && loadedPet != null && loadedPet.isLinked) {
                divider()
                item(L("Unlink from the family"), icon = "link-2-off", destructive = true) {
                    unlinkingPet = ClientsUnlinkTarget(clientId = loadedClient.id, clientName = loadedClient.ownerName, petId = loadedPet.id, petName = loadedPet.petName)
                }
            }
            if (workspace.can(Permission.petsEdit) && workspace.isOwnerOrAdmin && !archived) {
                item(L("Move to another client"), icon = "arrow-right-left") {
                    movingPet = ClientsMoveTarget(clientId = chart.client.id, clientName = chart.client.name, petId = chart.pet.id, name = chart.pet.name, linked = chart.pet.linked == true)
                }
            }
            if (workspace.can(Permission.petsEdit)) {
                if (archived && !withClient && movedTo == null) {
                    item(L("Restore"), icon = "rotate-ccw") { restoring = ClientsRestoreTarget.pet(id = chart.pet.id, name = chart.pet.name, deceased = chart.died) }
                }
                if (!chart.died && movedTo == null) {
                    divider()
                    if (!archived) {
                        item(L("Archive pet"), icon = "archive", destructive = true) {
                            closing = ClientsClosingTarget.archivePet(clientId = chart.client.id, petId = chart.pet.id, name = chart.pet.name)
                        }
                    }
                    item(L("Record that it died"), icon = "flower-2", destructive = true) {
                        closing = ClientsClosingTarget.petDeath(clientId = chart.client.id, petId = chart.pet.id, name = chart.pet.name)
                    }
                }
            }
        }
    }

    @Composable
    fun Header(chart: ClientsChart) {
        ModuleHeader(
            icon = "paw-print",
            title = chart.pet.name,
            subtitle = subtitle(chart).nonEmpty ?: ClientsText.species(chart.pet.species),
            accent = Theme.aqua,
            media = { PetAvatar(name = chart.pet.name, species = chart.pet.species, photoURL = chart.pet.photoUrl, size = if (wide) 56.dp else 36.dp) },
            titleAccessory = if (chart.isArchived) ({ Chip(text = if (chart.died) L("Deceased") else L("Archived"), tone = Chip.Tone.muted) }) else null,
            stats = stats(chart),
            actionsOnRow = true,
            storageKey = "patient-chart",
        ) {
            OwnerLine(chart)
            MoreMenu(chart)
        }
    }

    @Composable
    fun ClosingBanner(chart: ClientsChart) {
        val movedTo = chart.pet.movedTo
        val withClient = chart.pet.clientArchived == true || chart.pet.closing?.reason == "client_archived"
        ClientsClosingBanner(
            name = chart.pet.name, stamp = chart.pet.closing, deceasedAt = chart.pet.deceasedAt,
            hint = movedTo?.let { LF("Now with %@", it.clientName ?: "—") } ?: (if (withClient) L("It comes back when the client is restored.") else null),
            onRestore = if (workspace.can(Permission.petsEdit) && !withClient && movedTo == null) ({
                restoring = ClientsRestoreTarget.pet(id = chart.pet.id, name = chart.pet.name, deceased = chart.died)
            }) else null,
        )
        if (movedTo != null) {
            PMButton(L("Open its record there"), systemImage = "arrow-right", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                nav.push(ProviderRoute.clientPet(clientId = movedTo.clientId, petId = movedTo.petId))
            }
        } else if (withClient) {
            PMButton(L("Open the client"), systemImage = "user-round", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                nav.push(ProviderRoute.client(chart.client.id))
            }
        }
    }

    /** Quick actions, by permission: book, write, prescribe, weigh, walk in, collect what is owed. */
    @Composable
    fun QuickActions(chart: ClientsChart) {
        val archived = chart.isArchived
        val balance = chart.activity?.balance
        val canCollect = (balance?.client ?: 0) > 0 && (workspace.can(Permission.salesSell) || (workspace.can(Permission.billingRecordPayment) && workspace.moneyLevel >= MoneyLevel.charging))
        val canPrescribe = workspace.can(Permission.recordsCreate)
        val cid = chart.client.id
        val pid = chart.pet.id
        if ((!archived && (canBook || canWeigh || canPrescribe)) || canCreateRecords || canCollect) {
            FlowLayout(spacing = 8.dp) {
                if (canBook && !archived) {
                    PMButton(L("Book"), systemImage = "calendar-plus", size = ButtonSize.compact) { nav.push(ProviderRoute.bookVisit(BookingPrefill(clientId = cid, petId = pid))) }
                }
                if (canCreateRecords) {
                    PMButton(L("New record"), systemImage = "file-plus-corner", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                        nav.push(ProviderRoute.newRecord(RecordPrefill(clientId = cid, petId = pid)))
                    }
                }
                if (canPrescribe && !archived) {
                    PMButton(L("Prescribe"), systemImage = "pill", variant = ButtonVariant.outline, size = ButtonSize.compact) { prescribing = true }
                }
                if (canWeigh && !archived) {
                    PMButton(L("Weigh"), systemImage = "scale", variant = ButtonVariant.outline, size = ButtonSize.compact) { weighing = true }
                }
                if (canBook && workspace.can(Permission.appointmentsCheckIn) && !archived) {
                    PMButton(L("Start a visit"), systemImage = "zap", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                        nav.push(ProviderRoute.bookVisit(BookingPrefill(clientId = cid, petId = pid, walkIn = true)))
                    }
                }
                if (canCollect && balance != null) {
                    // The Store's checkout collects a client's open bills; without the Store, the client's account takes the payment.
                    PMButton(LF("Collect %@", Money.format(balance.client, currency = balance.currency)), systemImage = "circle-dollar-sign", size = ButtonSize.compact) {
                        if (workspace.can(Permission.salesSell)) nav.push(ProviderRoute.storeCheckout(StorePrefill(clientId = cid, petId = pid)))
                        else collectingOnAccount = true
                    }
                }
            }
        }
    }

    // MARK: Tabs

    @Composable
    fun Tabs(chart: ClientsChart) {
        val viewer = chart.viewer
        val openFollowUps = chart.counts?.openFollowUps ?: 0
        val choices = mutableListOf(Segmented.Option(ClientsChartTab.history, L("History")))
        if (viewer?.seesVisits == true) choices.add(Segmented.Option(ClientsChartTab.appointments, L("Appointments")))
        if (viewer?.seesFollowUps == true) {
            choices.add(
                Segmented.Option(
                    ClientsChartTab.followups,
                    if (openFollowUps > 0) "${L("Follow-ups")} · ${if (openFollowUps > 99) "99+" else openFollowUps.toString()}" else L("Follow-ups"),
                ),
            )
        }
        if (viewer?.seesRecords == true) choices.add(Segmented.Option(ClientsChartTab.files, L("Files")))
        if (viewer?.seesBilling == true) choices.add(Segmented.Option(ClientsChartTab.billing, L("Billing")))
        choices.add(Segmented.Option(ClientsChartTab.profile, L("Details")))
        val active = if (choices.any { it.value == tab.value }) tab.value else ClientsChartTab.history
        Card(padding = 12.dp, spacing = 16.dp) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
                Segmented(
                    options = choices,
                    selection = stateOf({ active }, { tab.value = it }),
                    look = Segmented.Look.raised,
                    label = L("Chart sections"),
                )
            }
            when (active) {
                ClientsChartTab.history -> ClientsTimelineView(clientId = chart.client.id, petId = chart.pet.id, chart = chart)
                ClientsChartTab.appointments -> ClientsChartAppointments(
                    chart = chart,
                    onBook = if (canBook && !chart.isArchived) ({ nav.push(ProviderRoute.bookVisit(BookingPrefill(clientId = chart.client.id, petId = chart.pet.id))) }) else null,
                )
                ClientsChartTab.followups -> ClientsChartFollowUps(chart = chart)
                ClientsChartTab.files -> ClientsChartFiles(chart = chart)
                ClientsChartTab.billing -> ClientsChartBilling(chart = chart)
                ClientsChartTab.profile -> ClientsChartDetails(chart = chart, onEdit = if (workspace.can(Permission.petsEdit) && pet != null) ({ editing = true }) else null)
            }
        }
    }

    // MARK: Page

    @Composable
    fun Content(chart: ClientsChart) {
        val archived = chart.isArchived
        val viewer = chart.viewer
        PageScroll(refresh = { load() }) {
            Header(chart)
            if (archived) ClosingBanner(chart)
            chart.pet.movedFrom?.let { from ->
                // It came from another client: its earlier history is read here, and lives on the earlier record.
                NoticeCard(
                    tone = NoticeCard.Tone.warning,
                    title = LF("Came from %@", from.clientName ?: "—"),
                    message = L("Its earlier history shows here to read. It is changed only on the earlier record."),
                    actionTitle = L("Open the earlier record"),
                ) {
                    nav.push(ProviderRoute.clientPet(clientId = from.clientId, petId = from.petId))
                }
            }
            QuickActions(chart)
            // Always on screen, before anything else.
            ClientsAlertStrip(alerts = chart.alerts ?: emptyList(), seesRecords = viewer?.seesRecords ?: false)
            if (viewer?.seesRecords == true) {
                ClientsChartBrief(chart = chart, canWeigh = canWeigh, reload = { load() }) { tab.value = ClientsChartTab.followups }
            } else {
                ClientsChartSummaryView(chart = chart, canWeigh = canWeigh, reload = { load() })
            }
            val toSign = chart.counts?.toSign
            if (toSign != null && toSign > 0) {
                NoticeCard(tone = NoticeCard.Tone.warning, title = if (toSign == 1) L("1 note is waiting to be signed.") else LF("%d notes are waiting to be signed.", toSign))
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L("Clinical summary"), style = Theme.heading(20), color = Theme.inkStrong)
                ClientsPrescriptionsCard(chart = chart)
                if (workspace.can(Permission.hospitalView) && workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)) {
                    ClientsHospitalCard(items = hospitalStays)
                }
            }
            Tabs(chart)
        }
    }

    providerPage(chart?.pet?.name ?: L("Patient chart"), modifier = modifier) {
        val current = chart
        val failure = error
        when {
            !canView -> PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role see clients.")) }
            current != null -> Content(current)
            notFound -> PageScroll {
                EmptyState(
                    systemImage = "paw-print", title = L("Pet not found"),
                    message = L("It may have been combined with another record, or it belongs to a client assigned to someone else."),
                    accent = Theme.lime, panel = true,
                )
            }
            failure != null -> PageScroll { ErrorState(message = L("The chart could not be loaded.") + " " + failure) { load() } }
            else -> PageScroll { LoadingState() }
        }
    }
    ReloadsOnScope { load() }

    if (weighing) {
        chart?.let { current ->
            AppSheet(onDismissRequest = { weighing = false }) { ClientsWeightSheet(chart = current, onSaved = { load() }) }
        }
    }
    if (editing) {
        pet?.let { current ->
            AppSheet(onDismissRequest = { editing = false }) { ClientsEditPetSheet(clientName = clientName, pet = current) }
        }
    }
    closing?.let { target -> AppSheet(onDismissRequest = { closing = null }) { ClientsClosingSheet(target = target) } }
    restoring?.let { target -> AppSheet(onDismissRequest = { restoring = null }) { ClientsRestoreSheet(target = target) } }
    if (collectingOnAccount) {
        ClientsChartPushedPage(onDismissRequest = { collectingOnAccount = false }) { CashAccountScreen(clientId = clientId) }
    }
    mergingPet?.let { source ->
        AppSheet(onDismissRequest = { mergingPet = null }) {
            ClientsMergePetSheet(source = source, ownPets = client?.allPets ?: emptyList(), canSearchOthers = canView, onMerged = { cid, pid ->
                scope.launch {
                    delay(400)
                    nav.push(ProviderRoute.clientPet(clientId = cid, petId = pid))
                }
            })
        }
    }
    movingPet?.let { target ->
        AppSheet(onDismissRequest = { movingPet = null }) {
            ClientsMovePetSheet(target = target, onMoved = { cid, pid ->
                scope.launch {
                    load()
                    delay(300)
                    nav.push(ProviderRoute.clientPet(clientId = cid, petId = pid))
                }
            })
        }
    }
    unlinkingPet?.let { target ->
        AppSheet(onDismissRequest = { unlinkingPet = null }) { ClientsUnlinkPetSheet(target = target, onDone = { load() }) }
    }
    if (prescribing) {
        chart?.let { current ->
            AppSheet(onDismissRequest = { prescribing = false }) {
                StockPrescriptionSheet(
                    pet = StockRxPet(
                        clientId = current.client.id, petId = current.pet.id, petName = current.pet.name,
                        species = current.pet.species, photoUrl = current.pet.photoUrl, clientName = current.client.name,
                    ),
                    draft = null,
                ) { announceDataChanged() }
            }
        }
    }
}

// MARK: - Shared pieces of the chart

/** A small uppercase heading inside the chart (web `ChartSectionTitle`): 11.2 extrabold, tracked. */
@Composable
fun ClientsChartSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = brandText(11.2, FontWeight.ExtraBold).tracking(1.5), color = Theme.inkSoft, modifier = modifier)
}

/** A bordered box inside a card (web `rounded-box border border-soft`): 16 corners, a 1 pt grey border, a fill. */
fun Modifier.clientsChartBox(fill: Color, border: Color? = null, lineWidth: Dp = 1.dp): Modifier = composed {
    val shape = RoundedCornerShape(Theme.boxRadius)
    this.background(fill, shape).border(lineWidth, border ?: Theme.borderSoft, shape)
}

/** The same box on the surface. */
fun Modifier.clientsChartBox(): Modifier = composed { this.clientsChartBox(Theme.surface) }

/** SwiftUI `.overlay(alignment: .leading) { Rectangle().fill(color).frame(width:) }`: a colour down the left edge. */
fun Modifier.clientsLeadingEdge(color: Color, width: Dp): Modifier = drawWithContent {
    drawContent()
    drawRect(color, topLeft = Offset.Zero, size = Size(width.toPx(), size.height))
}

/** iOS `.accessibilityHint(_:)` on something that isn't tapped: read after its label. */
fun Modifier.clientsHint(hint: String?): Modifier =
    if (hint.isNullOrEmpty()) this else this.semantics { stateDescription = hint }

/**
 * `LazyVGrid(columns: [GridItem(.adaptive(minimum:))])` (or `count` flexible columns) inside a
 * scrolling page: as many equal columns as fit at `minimum` wide, rows `spacing` apart.
 */
@Composable
fun <T> ClientsGrid(
    items: List<T>,
    minimum: Dp = 150.dp,
    spacing: Dp = 8.dp,
    columns: Int? = null,
    rowSpacing: Dp = spacing,
    modifier: Modifier = Modifier,
    cell: @Composable (Int, T) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = columns ?: max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
            items.chunked(count).forEachIndexed { row, chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
                    chunk.forEachIndexed { column, item ->
                        Box(Modifier.weight(1f)) { cell(row * count + column, item) }
                    }
                    repeat(count - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A page the Swift pushes with `NavigationLink { View }` / `.navigationDestination(isPresented:)`
 * that isn't a `ProviderRoute` (the Android stack pushes only routes): the page in a whole-height
 * sheet; its bar says "Close".
 */
@Composable
fun ClientsChartPushedPage(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    AppSheet(onDismissRequest = onDismissRequest) {
        Box(Modifier.fillMaxWidth().sheetPresentation(setOf(PresentationDetent.large))) { content() }
    }
}

// MARK: - The red strip

/** Allergies, the care card's handling warnings and active problems — always on screen, before anything else (web `AlertStrip`). */
@Composable
fun ClientsAlertStrip(alerts: List<ClientsChartAlert>, seesRecords: Boolean, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) {
        Row(
            modifier.fillMaxWidth().clientsChartBox().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("circle-check", 16.dp, tint = Theme.blue)
            Text(
                if (seesRecords) L("No allergies, handling warnings or active problems on record.") else L("No handling warnings. Allergies and problems need access to records."),
                style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.weight(1f),
            )
        }
    } else {
        val label = L("Alerts")
        FlowLayout(
            spacing = 6.dp,
            modifier = modifier
                .fillMaxWidth()
                .clientsChartBox(Theme.wash(Theme.destructive, 9.0), border = Theme.destructive.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = label },
        ) {
            alerts.forEach { alert ->
                val shown = if (alert.kind == "handling") alert.label.replace("_", " ").split(" ").joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { it.uppercase() }
                } else alert.label
                val prefix = when (alert.kind) {
                    "allergy" -> "${L("Allergy")}: "
                    "problem" -> "${L("Problem")}: "
                    else -> ""
                }
                val text = prefix + shown + (if (alert.source == "family") " · ${L("Family")}" else "")
                val hint = listOf(alert.detail, if (alert.source == "family") L("Registered by the family") else null).mapNotNull { it.nonEmpty }.joinToString(" · ")
                Box(Modifier.clientsHint(hint)) {
                    if (alert.kind == "problem") Chip(text = text, tone = Chip.Tone.warn, icon = "stethoscope")
                    else StatusChip(text = text, state = ProviderState.problem)
                }
            }
        }
    }
}

// MARK: - The clinical summary

/**
 * The chart's own facts (web `ChartSummary`): allergies first and in red, active problems, the medicines
 * being given, each vaccine with its status, the latest labs and the weight; `section` shows one of them
 * (under a tab of the brief).
 */
@Composable
fun ClientsChartSummaryView(
    chart: ClientsChart,
    canWeigh: Boolean,
    section: String? = null,
    reload: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun show(key: String): Boolean = section == null || section == key
    val summary = chart.summary
    if (summary == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                L("Clinical records are hidden for your role."), style = Theme.text(14), color = Theme.inkSoft,
                modifier = Modifier.fillMaxWidth().clientsChartBox().padding(12.dp),
            )
            ClientsWeightCard(chart = chart, canWeigh = canWeigh, reload = reload)
        }
        return
    }
    val soft = Theme.inkSoft
    val red = Theme.destructive
    val blocks = mutableListOf<@Composable () -> Unit>()
    if (show("overview")) blocks.add {
        SummaryBlock(L("Allergies"), "triangle-alert", Theme.destructive, empty = L("None recorded."), count = summary.allergies?.size ?: 0) {
            (summary.allergies ?: emptyList()).forEach { allergy ->
                SummaryLine(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = red)) { append(allergy.label ?: "—") }
                        withStyle(SpanStyle(color = soft)) { append(allergy.detail?.let { " · $it" } ?: "") }
                    },
                    family = allergy.source == "family", draft = allergy.draft == true,
                )
            }
        }
    }
    if (show("problems")) blocks.add {
        SummaryBlock(L("Active problems"), "stethoscope", Theme.pink, empty = L("No active problems recorded."), count = summary.problems?.size ?: 0) {
            (summary.problems ?: emptyList()).forEach { problem ->
                val state = if (problem.detail == "monitoring") L("Monitoring") else L("Active")
                SummaryLine(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(problem.label ?: "—") }
                        withStyle(SpanStyle(color = soft)) { append(" · $state · ${LF("since %@", ClientsText.shortDay(problem.at))}") }
                    },
                    family = problem.source == "family", draft = problem.draft == true,
                )
            }
        }
    }
    if (show("medications")) blocks.add {
        SummaryBlock(L("Current medications"), "pill", Theme.mango, empty = L("No active medication records."), count = summary.medications?.size ?: 0) {
            (summary.medications ?: emptyList()).forEach { medication ->
                val until = medication.endDay?.let { LF("until %@", ClientsText.shortDay(it)) } ?: L("no end date")
                SummaryLine(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(medication.name ?: "—") }
                        withStyle(SpanStyle(color = soft)) { append((medication.dose?.let { " · $it" } ?: "") + " · $until") }
                    },
                    family = medication.source == "family", draft = medication.draft == true,
                )
            }
        }
    }
    if (show("history")) blocks.add {
        SummaryBlock(L("Vaccines"), "syringe", Theme.blue, empty = L("No vaccines on record."), count = summary.vaccines?.size ?: 0) {
            (summary.vaccines ?: emptyList()).forEach { vaccine ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(vaccine.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                            if (vaccine.source == "family") Chip(text = L("Family"), tone = Chip.Tone.info)
                        }
                        Text(
                            (vaccine.lastGivenAt?.let { LF("Given %@", ClientsText.shortDay(it)) } ?: L("Date not recorded")) +
                                (vaccine.nextDueDay?.let { " · ${LF("next %@", ClientsText.shortDay(it))}" } ?: ""),
                            style = Theme.text(12), color = Theme.inkSoft,
                        )
                    }
                    ClientsVaccineBadge(status = vaccine.status)
                }
            }
        }
    }
    if (show("labs")) blocks.add {
        SummaryBlock(L("Recent labs"), "flask-conical", Theme.aqua, empty = L("No lab results yet."), count = summary.labs?.size ?: 0) {
            (summary.labs ?: emptyList()).forEach { lab ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    SummaryLine(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lab.name ?: "—") }
                            withStyle(SpanStyle(color = soft)) { append(" · ${ClientsText.shortDay(lab.at)}") }
                        },
                        family = lab.source == "family", draft = lab.draft == true,
                    )
                    lab.result.nonEmpty?.let { Text(it, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    if (show("weight")) blocks.add { ClientsWeightCard(chart = chart, canWeigh = canWeigh, reload = reload) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ClientsGrid(items = blocks, minimum = 300.dp, spacing = 12.dp) { _, block -> block() }
        if (section == null) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Glyph("share-2", 14.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp))
                Text(summaryShareLine(chart), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }
}

/** Who sees what: the family's history reaches the chart only while they share it. */
private fun summaryShareLine(chart: ClientsChart): String {
    if (chart.pet.linked != true) return L("Not linked to a family on PawMiaw: only the clinic’s records.")
    if (chart.sharedHistoryAccess?.canView != true) return L("The family hasn’t shared their medical history with you.")
    if (chart.sharedHistoryAccess?.shareScope == "full") return L("The family shares their whole medical history.")
    return LF("The family shares what they record since %@.", ClientsText.shortDay(chart.sharedHistoryAccess?.sharedSince))
}

/** One block (web `Block`): a 24 disc of its tone, the small title, its lines or a quiet empty line. */
@Composable
private fun SummaryBlock(title: String, icon: String, tone: Color, empty: String, count: Int, rows: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clientsChartBox().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(Theme.wash(tone, 18.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(icon, 14.dp, tint = Theme.mix(tone, 75.0, Theme.inkStrong))
            }
            ClientsChartSectionTitle(text = title)
        }
        if (count > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { rows() }
        } else {
            Text(empty, style = Theme.text(14), color = Theme.inkSoft)
        }
    }
}

@Composable
private fun SummaryLine(text: AnnotatedString, family: Boolean, draft: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = Theme.text(14), color = Theme.inkStrong, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
        if (family) Box(Modifier.alignByBaseline()) { Chip(text = L("Family"), tone = Chip.Tone.info) }
        if (draft) Box(Modifier.alignByBaseline()) { Chip(text = L("To sign"), tone = Chip.Tone.warn) }
    }
}

/** A vaccine's status (web `VaccineStatusBadge`): overdue solid red, due soon mango, current blue, no date grey. */
@Composable
fun ClientsVaccineBadge(status: String?, modifier: Modifier = Modifier) {
    when (status) {
        "overdue" -> StatusChip(text = ClientsText.vaccineStatus(status), state = ProviderState.problem, modifier = modifier)
        "due_soon" -> Chip(text = ClientsText.vaccineStatus(status), tone = Chip.Tone.warn, modifier = modifier)
        "current" -> Chip(text = ClientsText.vaccineStatus(status), tone = Chip.Tone.info, modifier = modifier)
        else -> Chip(text = ClientsText.vaccineStatus(status), tone = Chip.Tone.muted, modifier = modifier)
    }
}

/** One weight in the card's list (Swift's tuple). */
private data class ClientsWeightEntryRow(val at: String, val grams: Double, val home: Boolean, val id: String?, val used: Boolean)

/** The weight: the clinic's is the pet's weight; while the family shares, their latest home weight too (web `WeightCard`). */
@Composable
fun ClientsWeightCard(chart: ClientsChart, canWeigh: Boolean, reload: suspend () -> Unit, modifier: Modifier = Modifier) {
    var showAll by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val weights = chart.weights
    val home = weights?.home
    val entries = ((weights?.trend ?: emptyList()).map { ClientsWeightEntryRow(it.at, it.grams, home = false, id = it.id, used = true) } +
        (home?.trend ?: emptyList()).map { ClientsWeightEntryRow(it.at, it.grams, home = true, id = it.id, used = it.used ?: false) })
        .sortedByDescending { it.at }
    val chevron by animateFloatAsState(if (showAll) 180f else 0f, tween(200), label = "chevron")

    /** "Usar este peso": a weight the family logged at home becomes the clinic's. */
    fun take(id: String, grams: Double) {
        taking = true
        scope.launch {
            try {
                API.post<EmptyResponse>("/provider-clients/${chart.client.id}/pets/${chart.pet.id}/weights/from-family", body = mapOf("familyWeightId" to id))
                Toast.show(LF("%@ weighs %@.", chart.pet.name, ClientsText.weight(grams) ?: ""))
                announceDataChanged()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val api = e as? APIError
                when {
                    api?.code == "family_weight_not_shared" -> {
                        Toast.error(L("The family no longer shares this weight. The chart has been updated."))
                        reload()
                    }
                    api?.status == 404 -> {
                        Toast.error(L("This weight is no longer in the chart. The chart has been updated."))
                        reload()
                    }
                    api?.status == 403 -> Toast.error(L("You can’t log this pet’s weight with your access."))
                    else -> Toast.error(e, fallback = L("The weight could not be saved."))
                }
            } finally {
                taking = false
            }
        }
    }

    Column(
        modifier.fillMaxWidth().clientsChartBox().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(Theme.wash(Theme.lime, 30.0), CircleShape), contentAlignment = Alignment.Center) {
                Glyph("scale", 14.dp, tint = Theme.inkStrong)
            }
            ClientsChartSectionTitle(text = L("Weight"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            val grams = weights?.latestGrams
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (grams != null) {
                    Text(ClientsText.weight(grams) ?: "—", style = Theme.heading(24), color = Theme.inkStrong)
                    Text(weights?.latestAt?.let { LF("Weighed on %@", ClientsText.shortDay(it)) } ?: L("From the profile"), style = Theme.text(12), color = Theme.inkSoft)
                } else {
                    Text(L("Not weighed yet."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
            Spacer(Modifier.width(8.dp))
            ClientsSparkline(
                points = (weights?.trend ?: emptyList()).map { it.at to it.grams },
                homePoints = (home?.trend ?: emptyList()).map { it.at to it.grams },
                modifier = Modifier.size(width = 160.dp, height = 40.dp),
            )
        }
        val latest = home?.latestGrams
        if (home != null && latest != null) {
            RowDivider()
            FlowLayout(spacing = 8.dp) {
                Row(Modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Theme.surface, CircleShape).border(1.75.dp, Theme.apricot, CircleShape))
                    Text(LF("At home: %@ · %@", ClientsText.weight(latest) ?: "—", ClientsText.shortDay(home.latestAt)), style = Theme.text(14), color = Theme.inkStrong)
                }
                val latestId = home.latestId
                if (canWeigh && home.latestUsed != true && latestId != null) {
                    PMButton(L("Use this weight"), variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = taking) { take(latestId, grams = latest) }
                }
            }
            if (weights?.differs == true) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.mango, 14.0), RoundedCornerShape(Theme.chipRadius))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Glyph("triangle-alert", 14.dp, tint = Theme.mix(Theme.mango, 80.0, Theme.inkStrong), modifier = Modifier.padding(top = 1.dp))
                    Text(L("Different from what the family logs"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                }
            }
        }
        if (entries.size > 1) {
            Row(
                Modifier.plainButton { showAll = !showAll },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("chevron-down", 14.dp, tint = Theme.blue, modifier = Modifier.rotate(chevron))
                Text(if (showAll) L("Hide weights") else LF("All weights (%d)", entries.size), style = Theme.text(12, FontWeight.Bold), color = Theme.blue)
            }
            AnimatedVisibility(
                visible = showAll,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
            ) {
                Column {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(ClientsText.shortDay(entry.at), style = Theme.text(14), color = Theme.inkSoft)
                            if (entry.home) Chip(text = L("At home"), tone = Chip.Tone.warn)
                            Spacer(Modifier.weight(1f))
                            val id = entry.id
                            if (entry.home && canWeigh && !entry.used && id != null) {
                                Text(
                                    L("Use this weight"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue,
                                    modifier = Modifier.plainButton(enabled = !taking) { take(id, grams = entry.grams) },
                                )
                            }
                            Text(ClientsText.weight(entry.grams) ?: "—", style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                        }
                    }
                }
            }
        }
    }
}

/** A small line of the clinic's weight over time; the family's are hollow apricot dots of their own (web `WeightSparkline`). */
@Composable
fun ClientsSparkline(points: List<Pair<String, Double>>, homePoints: List<Pair<String, Double>>, modifier: Modifier = Modifier) {
    val blue = Theme.blue
    val apricot = Theme.apricot
    val surface = Theme.surface
    val label = if (homePoints.isEmpty()) L("Weight trend") else L("Weight trend, with the weights the family logged at home")
    Canvas(modifier.semantics { contentDescription = label }) {
        val all = (points + homePoints).mapNotNull { point -> DateText.parse(point.first)?.let { it.epochSecond.toDouble() to point.second } }
        if (all.size < 2) return@Canvas
        val times = all.map { it.first }
        val grams = all.map { it.second }
        val minT = times.minOrNull() ?: 0.0
        val maxT = times.maxOrNull() ?: 1.0
        val minG = grams.minOrNull() ?: 0.0
        val maxG = grams.maxOrNull() ?: 1.0
        val pad = 4.dp.toPx()
        val x = { t: Double -> if (maxT == minT) size.width / 2 else pad + ((t - minT) / (maxT - minT)).toFloat() * (size.width - pad * 2) }
        val y = { g: Double -> if (maxG == minG) size.height / 2 else size.height - pad - ((g - minG) / (maxG - minG)).toFloat() * (size.height - pad * 2) }
        val clinic = points.mapNotNull { point -> DateText.parse(point.first)?.let { Offset(x(it.epochSecond.toDouble()), y(point.second)) } }.sortedBy { it.x }
        val family = homePoints.mapNotNull { point -> DateText.parse(point.first)?.let { Offset(x(it.epochSecond.toDouble()), y(point.second)) } }
        if (clinic.size > 1) {
            val path = Path().apply {
                moveTo(clinic.first().x, clinic.first().y)
                clinic.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, blue, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val dot = 5.5.dp.toPx() / 2
        family.forEach { point ->
            drawCircle(surface, radius = dot, center = point)
            drawCircle(apricot, radius = dot, center = point, style = Stroke(width = 1.75.dp.toPx()))
        }
        clinic.lastOrNull()?.let { drawCircle(blue, radius = 3.dp.toPx(), center = it) }
    }
}
