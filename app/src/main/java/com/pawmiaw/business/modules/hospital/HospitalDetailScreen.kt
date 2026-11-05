package com.pawmiaw.business.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

//
//  HospitalDetailScreen.kt (iOS HospitalDetailScreen.swift)
//
//  One hospitalized patient (web provider.hospital.$hospitalizationId.tsx +
//  hospital-detail-view.tsx): the pet and its family, where it stands, the
//  gate while it waits (the estimate signed, the deposit paid, Admit), the
//  estimate still to sign once in, how it ended, then the treatment sheet,
//  orders, vitals, updates to the family, rounds and the running bill; and
//  discharge, transfer, death (and undoing it) for whoever manages the
//  hospital.
//

@Composable
fun HospitalDetailScreen(hospitalizationId: String, modifier: Modifier = Modifier) {
    HospDetailPage(id = hospitalizationId, modifier = modifier)
}

/** The page's data: the detail on one day of the sheet; every write answers the detail. */
class HospDetailModel(val id: String) {
    var detail by mutableStateOf<HospDetail?>(null)
    var error by mutableStateOf<String?>(null)
    var notFound by mutableStateOf(false)
    /** The sheet's day (null = today). */
    var day by mutableStateOf<String?>(null)

    suspend fun load() {
        try {
            val loaded: HospDetail = API.get(HospAPI.detailPath(id), mapOf("day" to day))
            detail = loaded
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (detail == null) {
                notFound = (e as? APIError)?.code == "not_found" || (e as? APIError)?.status == 404
                error = errorText(e)
            }
        }
    }

    suspend fun write(path: String, method: String = "POST", body: Map<String, Any?> = emptyMap()): HospDetail {
        val answer = HospAPI.write(id, path, method = method, body = body)
        if (day == null || answer.sheet?.day == day) detail = answer
        return answer
    }
}

enum class HospDetailTab { sheet, orders, vitals, updates, rounds, bill }

/** What the page opens over itself. */
sealed class HospDetailSheet {
    data object discharge : HospDetailSheet()
    data object transfer : HospDetailSheet()
    data object death : HospDetailSheet()
    data object undoDeath : HospDetailSheet()
    data object placement : HospDetailSheet()
    data object deposit : HospDetailSheet()
    data class sign(val consentId: String, val admitAfter: Boolean) : HospDetailSheet()

    val id: String
        get() = when (this) {
            discharge -> "discharge"
            transfer -> "transfer"
            death -> "death"
            undoDeath -> "undo"
            placement -> "placement"
            deposit -> "deposit"
            is sign -> "sign-$consentId"
        }
}

@Composable
fun HospDetailPage(id: String, signFirst: String? = null, admitAfterSigning: Boolean = false, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(id) { HospDetailModel(id) }
    val tab = remember { mutableStateOf(HospDetailTab.sheet) }
    var sheet by remember { mutableStateOf<HospDetailSheet?>(null) }
    var openedSign by remember { mutableStateOf(false) }

    val canManage = workspace.can(Permission.hospitalManage)
    val canCare = workspace.can(Permission.hospitalCare)
    val canOverride = workspace.isOwnerOrAdmin
    val seesBill = workspace.moneyLevel >= MoneyLevel.charging

    /** A PDF downloaded for the phone's viewer (the same way DocumentButton opens one: share and print from there). */
    fun openPDF(path: String, query: Map<String, String?> = emptyMap(), fileName: String) {
        scope.launch {
            try {
                val file = API.download(path, query, fileName = fileName)
                quickLookPreview(context, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(e, fallback = L("The file could not be opened. Try again."))
            }
        }
    }

    fun sheetPDF(detail: HospDetail) {
        val day = detail.sheet?.day ?: detail.sheet?.today ?: DateText.dayKey(Instant.now(), detail.zone)
        openPDF(HospAPI.detailPath(detail.id) + "/sheet/pdf", mapOf("day" to day), fileName = "${L("Sheet")} ${detail.petName} $day")
    }

    fun dischargePDF(detail: HospDetail) {
        openPDF(HospAPI.detailPath(detail.id) + "/discharge/pdf", fileName = "${L("Discharge summary")} ${detail.petName}")
    }

    /** Signed here with "Admit now" on: the patient goes in when nothing else is owed. */
    suspend fun admitAfterSign(detail: HospDetail) {
        try {
            val answer = model.write("/admit")
            Toast.show(if (answer.client?.onPawMiaw == true) LF("%@ is in the hospital. The family has been told.", answer.petName) else LF("%@ is in the hospital.", answer.petName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val code = (e as? APIError)?.code
            if (code == "deposit_required") Toast.show(LF("%@ is waiting for admission.", detail.petName) + " " + L("Take the deposit, then admit."))
            else if (code != "consent_required") Toast.error(hospError(e, "Could not admit the patient."))
        }
    }

    // MARK: Header actions (web: the module header's actions)

    @Composable
    fun RowScope.headerActions(detail: HospDetail) {
        val undoable = detail.end?.kind == "deceased" && (DateText.parse(detail.end?.undoableUntil)?.isAfter(Instant.now()) ?: false)
        if (detail.isAdmitted && canManage) {
            PMButton(L("Discharge"), systemImage = "heart-handshake") { sheet = HospDetailSheet.discharge }
            AppMenu(label = { IconCircleLabel() }, accessibilityLabel = L("More")) {
                item(L("Ward, vet and estimate"), icon = "pencil") { sheet = HospDetailSheet.placement }
                item(L("Print the day’s sheet"), icon = "printer") { sheetPDF(detail) }
                item(L("Transfer"), icon = "arrow-right-left") { sheet = HospDetailSheet.transfer }
                divider()
                item(L("Record a death"), icon = "feather", destructive = true) { sheet = HospDetailSheet.death }
            }
        }
        if (detail.isPending && canManage) {
            PMButton(L("Change"), systemImage = "pencil", variant = ButtonVariant.outline) { sheet = HospDetailSheet.placement }
        }
        if (detail.end != null) {
            PMButton(
                if (detail.end?.kind == "discharged") L("Discharge PDF") else L("Discharge summary"),
                systemImage = "file-down", variant = ButtonVariant.outline,
            ) { dischargePDF(detail) }
        }
        if (!detail.isAdmitted && !detail.isPending && detail.end == null) {
            PMButton(L("Day’s sheet PDF"), systemImage = "printer", variant = ButtonVariant.outline) { sheetPDF(detail) }
        }
        if (undoable && canOverride) {
            PMButton(L("Undo"), systemImage = "rotate-ccw", variant = ButtonVariant.outline) { sheet = HospDetailSheet.undoDeath }
        }
    }

    @Composable
    fun sheetView(which: HospDetailSheet, detail: HospDetail) {
        when (which) {
            HospDetailSheet.discharge -> HospDischargeSheet(
                model = model, detail = detail,
                onGoToSheet = { day ->
                    model.day = if (day == detail.sheet?.today) null else day
                    tab.value = HospDetailTab.sheet
                },
                onPDF = { dischargePDF(detail) },
            )
            HospDetailSheet.transfer -> HospTransferSheet(model = model, detail = detail)
            HospDetailSheet.death -> HospDeathSheet(model = model, detail = detail, canOverride = canOverride)
            HospDetailSheet.undoDeath -> ConfirmSheet(
                title = L("Undo the recorded death?"),
                message = LF("%@ is admitted again and the paused routines resume. Cancelled reminders and visits stay cancelled.", detail.patient?.name ?: L("The patient")),
                confirmTitle = L("Undo"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                cancelTitle = L("Keep it"),
            ) { reason ->
                try {
                    model.write("/death/undo", body = mapOf("reason" to (reason ?: "")))
                    Toast.show(L("Undone. The patient is admitted again."))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw hospRefusal(e, "Could not undo it.")
                }
            }
            HospDetailSheet.placement -> HospPlacementSheet(model = model, detail = detail)
            HospDetailSheet.deposit -> {
                val client = detail.client
                if (client != null) {
                    val gate = detail.gate?.deposit
                    val owed = maxOf(0, (gate?.amount ?: 0) - (gate?.paid ?: 0))
                    DepositSheet(
                        clientId = client.id, clientName = client.name ?: "", hospitalizationId = detail.id, owed = if (owed > 0) owed else null,
                        currency = detail.money, label = LF("Hospitalization deposit · %@", detail.petName),
                        explanation = L("It stays as credit on their account and is used when they pay the hospital bill."),
                        onTaken = { model.load() },
                    )
                } else {
                    ProviderModal(title = L("Take a deposit"), systemImage = "hand-coins", accent = ModalAccent.lime) {
                        ErrorState(message = L("This hospitalization was not found.")) { model.load() }
                    }
                }
            }
            is HospDetailSheet.sign -> ConsentSignSheet(
                consentId = which.consentId,
                businessName = workspace.current?.name,
                successMessage = L("Signed. It is kept with the hospitalization."),
            ) {
                scope.launch { model.load() }
                if (which.admitAfter) scope.launch { admitAfterSign(detail) }
            }
        }
    }

    // MARK: Page

    @Composable
    fun content(detail: HospDetail) {
        val tabs = buildList {
            add(ToneTabs.Tab(HospDetailTab.sheet, L("Sheet"), icon = "syringe"))
            add(ToneTabs.Tab(HospDetailTab.orders, L("Orders"), icon = "clipboard-list"))
            add(ToneTabs.Tab(HospDetailTab.vitals, L("Vitals"), icon = "activity"))
            add(ToneTabs.Tab(HospDetailTab.updates, L("Updates"), icon = "message-square-text"))
            add(ToneTabs.Tab(HospDetailTab.rounds, L("Rounds"), icon = "shield-check"))
            if (seesBill) add(ToneTabs.Tab(HospDetailTab.bill, L("Bill"), icon = "receipt"))
        }
        PageScroll(maxWidth = 1100.dp, refresh = { model.load() }) {
            HospDetailHeader(detail = detail, canOpenChart = workspace.can(Permission.clientsView)) { headerActions(detail) }
            if (detail.isPending) {
                HospGatePanel(
                    model = model, detail = detail, canManage = canManage, canOverride = canOverride,
                    canTakeDeposit = workspace.can(Permission.billingRecordPayment),
                ) { sheet = it }
            }
            if (detail.isAdmitted) {
                HospEstimateToSign(detail = detail, canManage = canManage) { sheet = it }
                // Missed until marked: the patient's one notice, gone once each dose is marked.
                val missed = detail.attention?.m
                if (missed != null && missed > 0) {
                    NoticeCard(
                        tone = NoticeCard.Tone.problem,
                        title = if (missed == 1) LF("Missed treatment: %@", detail.petName) else LF("%d treatments not marked: %@", missed, detail.petName),
                        message = if (missed == 1) L("Mark it or note why it wasn’t given.") else L("Mark them or note why they weren’t given."),
                        actionTitle = if (tab.value == HospDetailTab.sheet) null else L("Open the sheet"),
                        action = { tab.value = HospDetailTab.sheet },
                    )
                }
            }
            HospEndBanner(detail = detail)
            if (detail.status != "cancelled") {
                ToneTabs(tabs = tabs, selection = tab, look = ToneTabs.Look.solid)
                when (tab.value) {
                    HospDetailTab.sheet -> HospSheetTab(
                        model = model, detail = detail, canCare = canCare, canManage = canManage,
                        onOrders = { tab.value = HospDetailTab.orders }, onPrint = { sheetPDF(detail) },
                    )
                    HospDetailTab.orders -> HospOrdersTab(model = model, detail = detail, canManage = canManage)
                    HospDetailTab.vitals -> HospVitalsTab(model = model, detail = detail, canCare = canCare)
                    HospDetailTab.updates -> HospUpdatesTab(model = model, detail = detail, canCare = canCare)
                    HospDetailTab.rounds -> HospRoundsTab(model = model, detail = detail, canManage = canManage)
                    HospDetailTab.bill -> HospBillTab(detail = detail, canManage = canManage)
                }
            }
        }
    }

    providerPage(model.detail?.petName ?: L("Hospitalization"), modifier = modifier) {
        val detail = model.detail
        if (workspace.current != null && !workspace.can(Permission.hospitalView)) {
            PageScroll { NoAccessState(message = L("Ask the workspace owner to give your role access to the hospital.")) }
        } else if (detail != null) {
            content(detail)
        } else if (model.error != null) {
            PageScroll {
                EmptyState(
                    systemImage = "bed-double",
                    title = if (model.notFound) L("This hospitalization was not found.") else L("This hospitalization can’t be opened."),
                    message = L("It may be at another location, or it was removed."), accent = Theme.pink, panel = true,
                )
                if (!model.notFound) ErrorState(message = model.error ?: "") { model.load() }
            }
        } else {
            PageScroll { LoadingState() }
        }
        ReloadsOnScope { model.load() }
        HospEveryMinute { model.load() }
        HospOnChange(model.day) { model.load() }
        HospOnChange(model.detail?.id) {
            if (signFirst != null && !openedSign) {
                openedSign = true
                sheet = HospDetailSheet.sign(signFirst, admitAfter = admitAfterSigning)
            }
        }
        val which = sheet
        val shown = model.detail
        if (which != null && shown != null) {
            AppSheet(onDismissRequest = { sheet = null }) { sheetView(which, shown) }
        }
    }
}

// MARK: - Header

/**
 * The page's module header (web hospital-detail-view): the pet's photo (it
 * opens the chart), its name, what it is; where it stands as four numbers;
 * the reason, the family and the allergies in the footer.
 */
@Composable
private fun HospDetailHeader(detail: HospDetail, canOpenChart: Boolean, actions: @Composable RowScope.() -> Unit) {
    val nav = LocalNavigator.current
    val open = rememberOpenURL()
    val regular = LocalSizeClass.current == SizeClass.regular
    val zone = detail.zone

    val weight = detail.patient?.weightGrams?.let { LF("%@ kg", HospFmt.number(it / 1000, digits = 2)) }
    val aboutPet = listOfNotNull(detail.patient?.species?.let { hospSpeciesWord(it) }, detail.patient?.breed, weight)
        .mapNotNull { it.nonEmpty }.joinToString(" · ")

    val stats = listOf(
        ModuleStat(
            "status", label = L("Status"), value = HospWords.status(detail.status),
            hint = if (detail.admittedAt != null) LF("Since %@", HospFmt.dateTime(detail.admittedAt, zone)) else LF("Asked %@", HospFmt.dateTime(detail.requestedAt, zone)),
            icon = "activity", accent = HospTone.status(detail.status), active = true,
        ),
        ModuleStat(
            "day", label = L("Day"), value = detail.dayNumber?.toString() ?: "—",
            hint = if (detail.isAdmitted) detail.nextDueAt?.let { LF("Next %@", HospFmt.clock(it, zone)) } else null,
            icon = "calendar-check", accent = Theme.mango,
        ),
        ModuleStat(
            "where", label = L("Ward"), value = detail.room?.name ?: L("Not placed"),
            hint = detail.cageLabel?.let { LF("Cage %@", it) }, icon = "bed-double", accent = Theme.aqua, muted = detail.room == null,
        ),
        ModuleStat(
            "vet", label = L("Vet"), value = detail.attending?.name ?: "—",
            hint = detail.condition?.let { HospWords.condition(it) }, icon = "stethoscope", accent = Theme.lime, muted = detail.attending == null,
        ),
    )

    val clientId = detail.client?.id
    val petId = detail.patient?.providerClientPetId
    val chartTap = if (canOpenChart && clientId != null && petId != null) {
        Modifier.plainButton { nav.push(ProviderRoute.clientPet(clientId = clientId, petId = petId)) }
    } else Modifier

    ModuleHeader(
        icon = "paw-print",
        title = detail.petName,
        subtitle = aboutPet.nonEmpty,
        accent = Theme.pink,
        media = {
            // The header's photo well: 36 on a phone, 56 wider.
            PetAvatar(
                name = detail.petName, species = detail.patient?.species, photoURL = detail.patient?.photoUrl,
                size = if (regular) 56.dp else 36.dp,
                modifier = chartTap.hospGrayscale(detail.status == "deceased"),
            )
        },
        stats = stats,
        storageKey = "hospital-patient",
        actions = actions,
    ) {
        val triage = detail.triagePriority?.takeIf { detail.isAdmitted || detail.isPending }
        val reason = detail.reason.nonEmpty
        val allergies = detail.patient?.allergies ?: emptyList()
        if (triage != null || reason != null || detail.client != null || allergies.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (triage != null || reason != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (triage != null) HospTriageBadge(priority = triage)
                            if (reason != null) Text(reason, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val client = detail.client
                    if (client != null || allergies.isNotEmpty()) {
                        FlowLayout(spacing = 8.dp) {
                            if (client != null) {
                                HospClientName(client = client, canOpenChart = canOpenChart)
                                val phone = client.phone.nonEmpty
                                if (phone != null) {
                                    val digits = phone.filter { it.isDigit() || it == '+' }
                                    PMButtonStyle(variant = ButtonVariant.outline, size = ButtonSize.compact, onClick = { open("tel:$digits") }) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Glyph("phone", 14.dp)
                                            Text(phone, style = androidx.compose.material3.LocalTextStyle.current.monospacedDigit())
                                        }
                                    }
                                }
                            }
                            HospAllergyChip(allergies = allergies)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HospClientName(client: HospClientRef, canOpenChart: Boolean) {
    val nav = LocalNavigator.current
    val row = @Composable { ink: Color ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("user-round", 16.dp, tint = Theme.inkSoft)
            Text(client.name ?: "", style = Theme.text(14, FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (canOpenChart) {
        Box(
            Modifier.defaultMinSize(minHeight = 40.dp).plainButton { nav.push(ProviderRoute.client(client.id)) },
            contentAlignment = Alignment.CenterStart,
        ) { row(Theme.blue) }
    } else {
        Box(Modifier.defaultMinSize(minHeight = 40.dp), contentAlignment = Alignment.CenterStart) { row(Theme.inkStrong) }
    }
}

private fun hospSpeciesWord(species: String): String = when (species.lowercase()) {
    "dog" -> L("Dog")
    "cat" -> L("Cat")
    "bird" -> L("Bird")
    "rabbit" -> L("Rabbit")
    "fish" -> L("Fish")
    "reptile" -> L("Reptile")
    else -> species
}

// MARK: - The gate (waiting for admission)

@Composable
private fun HospGatePanel(
    model: HospDetailModel,
    detail: HospDetail,
    canManage: Boolean,
    canOverride: Boolean,
    canTakeDeposit: Boolean,
    open: (HospDetailSheet) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var admitting by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var overriding by remember { mutableStateOf(false) }
    var roomFull by remember { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf(false) }

    val gate = detail.gate
    val consent = gate?.consent
    val deposit = gate?.deposit
    val currency = detail.money
    val amount = deposit?.amount ?: 0
    val paid = deposit?.paid ?: 0
    val owed = maxOf(0, amount - paid)
    val pending = consent?.required == true && consent?.consentId != null && consent?.status == "pending"
    val estimate = Money.format(detail.estimateAmount ?: 0, currency)
    val done = (if (consent?.satisfied == true) 1 else 0) + (if (deposit?.satisfied == true) 1 else 0)

    suspend fun ask(notify: Boolean) {
        asking = true
        try {
            val answer = HospAPI.askConsent(detail.id, kind = "estimate", notify = notify)
            model.load()
            if (notify) {
                Toast.show(if (answer.notified == true) L("Sent to the family to sign.") else L("Asked. The family isn’t on the app: sign it here with them."))
            } else {
                answer.consentId?.let { open(HospDetailSheet.sign(it, admitAfter = false)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(hospError(e, "Could not ask for the signature."))
        } finally {
            asking = false
        }
    }

    suspend fun admitOrThrow(extra: Map<String, Any?>) {
        admitting = true
        try {
            val answer = model.write("/admit", body = extra)
            Toast.show(if (answer.client?.onPawMiaw == true) LF("%@ is in the hospital. The family has been told.", detail.petName) else LF("%@ is in the hospital.", detail.petName))
            overriding = false
            roomFull = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if ((e as? APIError)?.code == "room_full" && roomFull == null && !overriding) throw e
            throw hospRefusal(e, "Could not admit the patient.")
        } finally {
            admitting = false
        }
    }

    suspend fun admit(extra: Map<String, Any?>) {
        try {
            admitOrThrow(extra)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if ((e as? APIError)?.code == "room_full") roomFull = hospError(e) else Toast.error(hospError(e, "Could not admit the patient."))
        }
    }

    val shape = RoundedCornerShape(Theme.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .cardShadow(shape)
            .background(Theme.wash(Theme.mango, 8.0), shape)
            .dashedBorder(2.dp, Theme.mix(Theme.mango, 60.0, Theme.borderSoft), Theme.cardRadius, on = 7.dp, off = 5.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("Before admission").uppercase(), style = Theme.text(12, FontWeight.Bold).tracking(12 * 0.14), color = Theme.blue)
                Text(
                    if (gate?.ready == true) L("Ready to admit") else LF("%@ is waiting for admission", detail.petName),
                    style = Theme.heading(20), color = Theme.inkStrong,
                )
            }
            Spacer(Modifier.width(8.dp))
            val ready = if (gate?.ready == true) ProviderState.done.color else Theme.mango
            Chip(text = LF("%d of %d ready", done, 2), tone = Chip.Tone.custom(ready), dot = ready, percent = 22.0)
        }
        HospGrid(listOf(0, 1), minimum = 280.dp, spacing = 12.dp) { which ->
            if (which == 0) {
                HospGateCard(
                    ok = consent?.satisfied == true,
                    icon = if (consent?.satisfied == true) "shield-check" else "shield-alert",
                    title = L("Hospitalization estimate"),
                    line = when {
                        consent?.required != true -> L("No estimate: nothing to sign.")
                        consent?.satisfied == true -> LF("Signed for %@.", Money.format(consent?.amountCents ?: detail.estimateAmount ?: 0, currency))
                        consent?.reason == "amount_changed" -> LF("The estimate went up to %@ since it was signed: ask again.", estimate)
                        consent?.reason == "missing" -> LF("Not asked yet (%@).", estimate)
                        consent?.status == "declined" -> L("The family declined it.")
                        else -> LF("Waiting for the family’s signature (%@).", estimate)
                    },
                ) {
                    if (consent?.required == true && consent?.satisfied != true && canManage) {
                        FlowLayout(spacing = 8.dp) {
                            PMButton(L("Sign here"), systemImage = "pen-line", size = ButtonSize.compact, isWorking = asking) {
                                val id = consent?.consentId
                                if (pending && id != null) open(HospDetailSheet.sign(id, admitAfter = false)) else scope.launch { ask(notify = false) }
                            }
                            if (detail.client?.onPawMiaw == true) {
                                PMButton(
                                    if (pending) L("Send again") else L("Send to the family"), systemImage = "send",
                                    variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = asking,
                                ) { scope.launch { ask(notify = true) } }
                            }
                        }
                    }
                }
            } else {
                HospGateCard(
                    ok = deposit?.satisfied == true,
                    icon = "piggy-bank",
                    title = L("Deposit"),
                    line = when {
                        amount <= 0 -> L("No deposit asked.")
                        deposit?.waivedByTriage == true -> L("Waived: a critical emergency is never held back by money.")
                        deposit?.satisfied == true -> LF("Paid: %@ of %@.", Money.format(paid, currency), Money.format(amount, currency))
                        else -> LF("%@ paid of %@ — %@ to go.", Money.format(paid, currency), Money.format(amount, currency), Money.format(owed, currency))
                    },
                ) {
                    if (deposit?.satisfied != true && canTakeDeposit && detail.client != null) {
                        PMButton(L("Take the deposit"), systemImage = "hand-coins", size = ButtonSize.compact) { open(HospDetailSheet.deposit) }
                    }
                }
            }
        }
        if (canManage) {
            // Phone: stacked, the admission on top (web flex-col-reverse).
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.mix(Theme.mango, 40.0, Theme.borderSoft)))
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PMButton(
                        LF("Admit %@", detail.petName), systemImage = "bed-double", fullWidth = true, isWorking = admitting,
                        enabled = gate?.ready == true,
                    ) { scope.launch { admit(emptyMap()) } }
                    if (gate?.ready != true && canOverride) {
                        PMButton(L("Admit anyway"), variant = ButtonVariant.outline, fullWidth = true) { overriding = true }
                    }
                    PMButton(L("Cancel admission"), systemImage = "circle-x", variant = ButtonVariant.destructive, fullWidth = true) { cancelling = true }
                }
            }
        }
    }

    if (overriding) {
        val consentOK = consent?.satisfied == true
        val depositOK = deposit?.satisfied == true
        AppSheet(onDismissRequest = { overriding = false }) {
            ConfirmSheet(
                title = if (!consentOK && !depositOK) L("Admit without the signed estimate or the deposit?")
                else if (!consentOK) L("Admit without the signed estimate?") else L("Admit before the deposit is paid?"),
                message = L("Only owners and admins can. It is written down with your name and the reason."),
                confirmTitle = L("Admit anyway"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                cancelTitle = L("Not now"),
            ) { reason ->
                admitOrThrow(mapOf("overrideConsent" to !consentOK, "overrideDeposit" to !depositOK, "overrideReason" to (reason ?: "")))
            }
        }
    }
    roomFull?.let { message ->
        AppSheet(onDismissRequest = { roomFull = null }) {
            ConfirmSheet(title = L("Place the patient anyway?"), message = message, confirmTitle = L("Place anyway"), destructive = true) {
                admitOrThrow(mapOf("allowOverCapacity" to true))
            }
        }
    }
    if (cancelling) {
        AppSheet(onDismissRequest = { cancelling = false }) {
            ConfirmSheet(
                title = L("Cancel this admission?"),
                message = LF("%@ leaves the board and the unsigned estimate is withdrawn.", detail.petName),
                confirmTitle = L("Cancel admission"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                cancelTitle = L("Keep it"),
            ) { reason ->
                try {
                    model.write("/cancel", body = mapOf("reason" to (reason ?: "")))
                    Toast.show(L("Admission cancelled."))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw hospRefusal(e, "Could not cancel it.")
                }
            }
        }
    }
}

/**
 * One thing admission waits on (web `GateCard`): a 40 disc (green with a
 * check badge when done, mango while it waits), the title and its line.
 */
@Composable
private fun HospGateCard(ok: Boolean, icon: String, title: String, line: String, actions: @Composable () -> Unit) {
    val tone = if (ok) ProviderState.done.color else Theme.mango
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(1.5.dp, Theme.mix(tone, if (ok) 45.0 else 60.0, Theme.borderSoft), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp)) {
                Box(Modifier.size(40.dp).background(Theme.wash(tone, if (ok) 18.0 else 30.0), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(icon, 20.dp, tint = if (ok) ProviderState.done.color else Theme.inkStrong)
                }
                if (ok) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp)
                            .size(16.dp)
                            .background(ProviderState.done.color, CircleShape)
                            .border(2.dp, Theme.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph("check", 10.dp, tint = Color.White)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                Text(line, style = Theme.text(14), color = Theme.inkSoft)
            }
        }
        actions()
    }
}

// MARK: - The estimate still to sign (patient in)

@Composable
private fun HospEstimateToSign(detail: HospDetail, canManage: Boolean, open: (HospDetailSheet) -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }

    suspend fun ask(notify: Boolean) {
        asking = true
        try {
            val answer = HospAPI.askConsent(detail.id, kind = "estimate", notify = notify)
            if (notify) Toast.show(if (answer.notified == true) L("Sent to the family to sign.") else L("Asked. The family isn’t on the app: sign it here with them."))
            else answer.consentId?.let { open(HospDetailSheet.sign(it, admitAfter = false)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(hospError(e, "Could not ask for the signature."))
        } finally {
            asking = false
        }
    }

    val consent = detail.gate?.consent
    if (consent != null && consent.required == true && consent.satisfied != true) {
        val pending = consent.status == "pending" && consent.consentId != null
        val amount = Money.format(detail.estimateAmount ?: 0, detail.money)
        val title = if (consent.reason == "amount_changed") {
            consent.amountCents?.let { LF("The estimate went up to %@; the family signed %@.", amount, Money.format(it, detail.money)) }
                ?: LF("The estimate went up to %@ since it was signed.", amount)
        } else if (consent.status == "declined") {
            LF("The family declined the estimate of %@.", amount)
        } else {
            LF("The estimate of %@ isn’t signed yet.", amount)
        }
        NoticeCard(tone = NoticeCard.Tone.custom(Theme.mango), title = title, icon = "shield-alert", look = NoticeCard.Look.page, content = {
            Text(if (pending) L("Waiting for the family’s signature.") else L("Ask the family to sign it."), color = Theme.inkSoft)
            if (canManage) {
                FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 4.dp)) {
                    PMButton(L("Sign here"), systemImage = "pen-line", size = ButtonSize.compact, isWorking = asking) {
                        val id = consent.consentId
                        if (pending && id != null) open(HospDetailSheet.sign(id, admitAfter = false)) else scope.launch { ask(false) }
                    }
                    if (detail.client?.onPawMiaw == true) {
                        PMButton(
                            if (pending) L("Send again") else L("Send to the family"), systemImage = "send",
                            variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = asking,
                        ) { scope.launch { ask(true) } }
                    }
                }
            }
        })
    }
}

// MARK: - How it ended

@Composable
private fun HospEndBanner(detail: HospDetail) {
    val zone = detail.zone
    val override = (detail.consentOverrideReason ?: detail.depositOverrideReason)?.let {
        LF("Admitted without the gate by %@: %@", detail.gateOverriddenByName ?: "", it)
    }
    val end = detail.end
    if (detail.status == "cancelled") {
        NoticeCard(tone = NoticeCard.Tone.custom(Theme.inkSoft), title = L("Cancelled before admission."), icon = "circle-x", look = NoticeCard.Look.page, content = {
            detail.cancelReason.nonEmpty?.let { Text(it, color = Theme.inkSoft) }
        })
    } else if (end != null) {
        val at = end.at?.let { HospFmt.dateTime(it, zone) } ?: ""
        val title = when (end.kind) {
            "discharged" -> LF("Discharged %@", at)
            "transferred" -> LF("Transferred to %@ %@", end.transferDestination ?: "", at)
            else -> LF("Passed away %@", at)
        }
        val icon = if (end.kind == "discharged") "heart-handshake" else if (end.kind == "transferred") "arrow-right-left" else "feather"
        NoticeCard(tone = NoticeCard.Tone.custom(HospTone.status(end.kind)), title = title, icon = icon, look = NoticeCard.Look.page, byline = end.byName.nonEmpty, content = {
            if (end.kind == "discharged") {
                end.diagnosis.nonEmpty?.let { Text("${L("Diagnosis")}: $it", color = Theme.inkStrong) }
                end.summary.nonEmpty?.let { Text(it, color = Theme.inkSoft) }
                end.instructions.nonEmpty?.let { care ->
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Theme.inkStrong)) { append("${L("Care at home")}: ") }
                        withStyle(SpanStyle(color = Theme.inkSoft)) { append(care) }
                    })
                }
                end.followUpAt?.let { followUp ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("calendar-check", 16.dp, tint = Theme.inkSoft)
                        Text(LF("Recheck on %@", HospFmt.day(followUp.take(10), style = HospFmt.DayStyle.long, zone = zone)), color = Theme.inkStrong)
                    }
                }
                // What went home with the family: the medicines.
                val items = detail.takeHome
                if (!items.isNullOrEmpty()) {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                                Glyph("pill", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                                Text(buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Theme.inkStrong)) { append(item.name ?: "") }
                                    withStyle(SpanStyle(color = Theme.inkSoft)) { append(hospTakeHomeLine(item)?.let { " · $it" } ?: "") }
                                })
                            }
                        }
                    }
                }
            } else if (end.kind == "transferred") {
                end.transferReason.nonEmpty?.let { Text(it, color = Theme.inkSoft) }
            } else {
                val parts = hospDeathLine(end)
                if (parts.isNotEmpty()) Text(parts, color = Theme.inkSoft)
            }
            if (override != null) Text(override, style = Theme.text(12), color = Theme.inkSoft)
        })
    } else {
        // Both can be true of one stay (admitted without the gate, then a death recorded by mistake): say both.
        val undone = detail.deathUndone?.let { LF("A recorded death was undone %@: %@", HospFmt.dateTime(it.at, zone), it.reason ?: "") }
        if (override != null) NoticeCard(tone = NoticeCard.Tone.custom(Theme.mango), title = override, icon = "triangle-alert", look = NoticeCard.Look.page)
        if (undone != null) NoticeCard(tone = NoticeCard.Tone.custom(Theme.mango), title = undone, icon = "rotate-ccw", look = NoticeCard.Look.page)
    }
}

private fun hospDeathLine(end: HospEnd): String {
    var parts = listOfNotNull(end.deathCause?.let { HospWords.deathCause(it) }, end.aftercare?.let { HospWords.aftercare(it) }, end.aftercareNote)
        .mapNotNull { it.nonEmpty }.joinToString(" · ")
    end.deathOverrideReason.nonEmpty?.let { reason ->
        parts += (if (parts.isEmpty()) "" else " · ") + LF("Recorded without the signed consent: %@", reason)
    }
    return parts
}

/** "1 tab · Oral · every 12 h · 7 days. With food". */
private fun hospTakeHomeLine(item: HospTakeHome): String? {
    val parts = listOfNotNull(
        HospFmt.dose(item.doseAmount, item.doseUnit).nonEmpty,
        item.route?.let { HospWords.route(it) },
        item.everyHours?.let { LF("every %@ h", it.toString()) },
        item.durationDays?.let { if (it == 1) L("1 day") else LF("%d days", it) },
    ).joinToString(" · ")
    return listOfNotNull(parts.nonEmpty, item.instructions.nonEmpty).joinToString(". ").nonEmpty
}
