package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import com.pawmiaw.business.team.TeamCredentialSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

//
//  StockPharmacyTodo.kt (iOS StockPharmacyTodo.swift)
//
//  The Pharmacy's "Por hacer" (web pharmacy/todo-tab.tsx, pharmacy-sheets.tsx):
//  what is ready to hand over, the refill requests waiting on the clinic and
//  the drafts nobody signed yet, oldest first; every refill request ever made
//  one tap away ("All requests"); and the sheets a refill needs — decline with
//  the reason the family reads, picked up outside a sale, logged for a client
//  who phoned.
//
//  Handing over is a sale at the Store (what moves stock, money and the log):
//  "Hand over" opens the Store's counter with the client, the pet and each
//  medicine against its prescription line already in the cart (web
//  lib/pharmacy.ts handOverSearch).
//

enum class StockTodoFocus { all, handover, refills, drafts }

/** Handing over through the Store (web lib/pharmacy.ts `handOverSearch`). */
object StockHandOver {
    /**
     * The Store's counter for a hand-over: the client, the pet and each medicine with the
     * prescription line it is dispensed against. Null when nothing on it can be sold (free
     * text, nothing left).
     */
    fun prefill(clientId: String?, petId: String?, prescriptionId: String?, lines: List<StockHandOverLine>): StorePrefill? {
        val sellable = lines.filter { it.block == null && it.catalogItemId?.isEmpty() == false && (it.quantity ?: 0) > 0 }
        val client = clientId.nonEmpty
        val pet = petId.nonEmpty
        if (sellable.isEmpty() || client == null || pet == null) return null
        return StorePrefill(
            clientId = client,
            petId = pet,
            lines = sellable.map { StorePrefill.Line(catalogItemId = it.catalogItemId ?: "", quantity = (it.quantity ?: 0).toDouble(), prescriptionItemId = it.prescriptionItemId) },
            prescriptionId = prescriptionId,
        )
    }

    /** What can be handed over from a prescription now, as the Store's counter. */
    fun prefill(p: StockPrescription): StorePrefill? {
        if (!p.isSigned) return null
        val lines = p.items.map { item ->
            StockHandOverLine(
                prescriptionItemId = item.id,
                medicineName = item.medicineName,
                strength = item.strength,
                quantityUnit = item.quantityUnit,
                controlSchedule = item.controlSchedule,
                catalogItemId = if (item.product?.active == true) item.catalogItemId else null,
                productName = item.product?.name,
                remaining = item.fill?.remaining ?: 0,
                quantity = item.nextFill ?: 0,
                // An older server doesn't say: a line with a Catalog product and something left can be sold.
                block = item.handOverBlock ?: (if (item.catalogItemId != null && (item.fill?.remaining ?: 0) > 0) null else "not_in_catalog"),
            )
        }
        return prefill(clientId = p.providerClientId, petId = p.providerClientPetId, prescriptionId = p.id, lines = lines)
    }

    fun route(prefill: StorePrefill?): ProviderRoute =
        prefill?.let { ProviderRoute.storeCheckout(it) } ?: ProviderRoute.section(ProviderSection.store)
}

/** The lists load 20 at a time and add 20 as they are scrolled (the owner's rule; the web shows 25 and "Show more"). */
private const val pharmacyPage = stockPageSize

/** Foundation's `.whitespaces`: spaces and tabs, not line breaks. */
private fun String.trimmingWhitespaces(): String =
    trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

private enum class Gate { ok, noProduct, controlled, cannotSell }

@Composable
fun StockTodoTab(
    focus: MutableState<StockTodoFocus>,
    counts: StockPharmacyCounts?,
    rights: StockRxRights,
    onOpen: (String) -> Unit,
    onNew: (() -> Unit)?,
    onLogRefill: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var handOverLimit by remember { mutableIntStateOf(pharmacyPage) }
    var refillLimit by remember { mutableIntStateOf(pharmacyPage) }
    var draftLimit by remember { mutableIntStateOf(pharmacyPage) }
    var handOver by remember { mutableStateOf<StockHandOverPage?>(null) }
    var refills by remember { mutableStateOf<StockRefillPage?>(null) }
    var drafts by remember { mutableStateOf<StockPrescriptionPage?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var declining by remember { mutableStateOf<StockRefillRequest?>(null) }
    var pickingUp by remember { mutableStateOf<Pair<String, String>?>(null) }
    var finishing by remember { mutableStateOf<StockPrescription?>(null) }
    var credential by remember { mutableStateOf<Triple<StockRefillRequest, Boolean, String?>?>(null) }

    val canApprove = refills?.canApprove ?: rights.canSign
    val canMarkReady = refills?.canMarkReady ?: (rights.canSign || rights.canSell)

    suspend fun load() {
        try {
            coroutineScope {
                val a = async { API.get<StockHandOverPage>("/provider-pharmacy/hand-over", mapOf("limit" to "$handOverLimit")) }
                val b = async { API.get<StockRefillPage>("/provider-prescriptions/refill-requests", mapOf("status" to "waiting", "limit" to "$refillLimit")) }
                val c = async { API.get<StockPrescriptionPage>("/provider-prescriptions", mapOf("state" to "draft", "limit" to "$draftLimit")) }
                val x = a.await()
                val y = b.await()
                val z = c.await()
                handOver = x; refills = y; drafts = z
            }
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadError = errorText(e)
        }
    }

    suspend fun answer(requestId: String, decision: String, reason: String? = null, ready: Boolean? = null, note: String? = null) {
        val body = mutableMapOf<String, Any?>("decision" to decision, "reason" to reason)
        if (ready != null) body["ready"] = ready
        if (decision == "picked_up") body["note"] = note
        try {
            StockAPI.send<EmptyResponse>("POST", "/provider-prescriptions/refill-requests/$requestId", body = body)
        } catch (refusal: StockRefusal) {
            throw APIError(status = refusal.status, message = StockRxWords.error(refusal), code = refusal.code, fields = refusal.api.fields)
        }
    }

    suspend fun approve(request: StockRefillRequest, ready: Boolean) {
        busy = true
        try {
            val result = StockAPI.send<Map<String, JSONValue>>(
                "POST", "/provider-prescriptions/refill-requests/${request.id}",
                body = mapOf("decision" to "approve", "reason" to null, "ready" to ready),
            )
            val told = request.familyLinked != false
            Toast.show(
                if (result["status"].string == "ready") {
                    if (told) L("The family was told it’s ready for pickup.") else L("Ready for pickup.")
                } else {
                    if (told) L("Approved. The family was told.") else L("Approved.")
                },
            )
            load()
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (refusal: StockRefusal) {
            if (refusal.code == "credential_required" || refusal.code == "dea_required") {
                credential = Triple(request, ready, if (refusal.code == "dea_required") "dea" else refusal.body["requiredType"].string)
            } else {
                Toast.error(StockRxWords.error(refusal))
            }
        } catch (e: Throwable) {
            Toast.error(StockRxWords.error(e))
        } finally {
            busy = false
        }
    }

    suspend fun markReady(request: StockRefillRequest) {
        busy = true
        try {
            answer(request.id, decision = "ready")
            Toast.show(if (request.familyLinked != false) L("The family was told it’s ready for pickup.") else L("Ready for pickup."))
            load()
            announceDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(e)
        } finally {
            busy = false
        }
    }

    fun more(grow: () -> Unit) {
        if (loadingMore) return
        grow()
        loadingMore = true
        scope.launch {
            load()
            loadingMore = false
        }
    }

    fun gate(controlled: Boolean, lines: List<StockHandOverLine>): Gate {
        if (!lines.any { it.block == null && it.catalogItemId != null }) return Gate.noProduct
        if (!rights.canSell) return Gate.cannotSell
        if (controlled && !rights.canHandOverControlled) return Gate.controlled
        return Gate.ok
    }

    fun gateNote(gate: Gate): String? = when (gate) {
        Gate.noProduct -> StockRxWords.block("not_in_catalog")
        Gate.controlled -> L("A controlled medicine is handed over by a vet. Ask one to do it.")
        Gate.cannotSell -> L("Someone who sells hands it over at the Store.")
        Gate.ok -> null
    }

    /** "Medicine · strength" in bold, then what it is in the quiet ink (web: the row's medicine line). */
    @Composable
    fun medicineLine(name: String?, strength: String?, rest: String) {
        val strong = SpanStyle(fontWeight = FontWeight.Bold, color = Theme.inkStrong)
        val soft = SpanStyle(color = Theme.inkSoft)
        Text(
            buildAnnotatedString {
                withStyle(strong) { append(listOf(name, strength).mapNotNull { it.nonEmpty }.joinToString(" · ")) }
                withStyle(soft) { append(" · $rest") }
            },
            style = Theme.text(14),
        )
    }

    /** The small facts under it: its pills, then "folio · refill · waiting 2 days". */
    @Composable
    fun factsLine(words: List<String?>, pills: @Composable () -> Unit) {
        FlowLayout(spacing = 8.dp) {
            pills()
            val text = words.mapNotNull { it.nonEmpty }.joinToString(" · ")
            if (text.isNotEmpty()) Text(text, style = Theme.text(12), color = Theme.inkSoft)
        }
    }

    @Composable
    fun reason(text: String) {
        Text(text, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
    }

    @Composable
    fun handOverRow(row: StockHandOverRow) {
        val lines = row.lines ?: emptyList()
        val g = gate(controlled = row.controlled == true, lines = lines)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StockPetLine(petName = row.petName, species = row.petSpecies, photoUrl = row.petPhotoUrl, clientName = row.clientName)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { line ->
                    medicineLine(line.medicineName, line.strength, StockRxWords.block(line.block) ?: LF("%d to hand over, %d left", line.quantity ?: 0, line.remaining ?: 0))
                }
                factsLine(
                    listOf(
                        row.folio,
                        if (row.kind == "refill") L("refill") else L("signed to hand out"),
                        StockRxWords.waiting(row.since),
                        if (row.origin == "staff") (row.requestedByName?.let { LF("asked by %@", it) } ?: L("logged by the clinic")) else null,
                    ),
                ) {
                    if (row.kind == "refill") StockRefillPill(status = "ready") else StockRxStatePill(state = "active")
                    if (row.controlled == true) StockControlledChip()
                }
                gateNote(g)?.let { reason(it) }
            }
            FlowLayout(spacing = 8.dp) {
                val appointmentId = row.appointmentId
                if (row.bill != null && appointmentId != null) {
                    PMButton(L("Charge the visit’s bill"), systemImage = "receipt-text", size = ButtonSize.modal) {
                        nav.push(ProviderRoute.appointment(appointmentId))
                    }
                } else if (g != Gate.noProduct) {
                    val prefill = StockHandOver.prefill(clientId = row.providerClientId, petId = row.providerClientPetId, prescriptionId = row.prescriptionId, lines = lines)
                    PMButton(L("Hand over"), systemImage = "shopping-cart", size = ButtonSize.modal, enabled = g == Gate.ok && prefill != null) {
                        nav.push(StockHandOver.route(prefill))
                    }
                }
                val requestId = row.requestId
                if (requestId != null && canMarkReady) {
                    PMButton(L("Mark as picked up"), systemImage = "check", variant = ButtonVariant.outline, size = ButtonSize.modal) {
                        pickingUp = Pair(requestId, listOf(lines.firstOrNull()?.medicineName, row.petName).mapNotNull { it.nonEmpty }.joinToString(" · "))
                    }
                }
                PMButton(L("Open prescription"), systemImage = "file-text", variant = ButtonVariant.ghost, size = ButtonSize.modal) { onOpen(row.prescriptionId) }
                StockChartLink(clientId = row.providerClientId, petId = row.providerClientPetId, canViewClients = rights.canViewClients)
            }
        }
    }

    @Composable
    fun handOverSection() {
        val rows = handOver?.items ?: emptyList()
        val total = handOver?.total ?: rows.size
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(
                title = L("Ready to hand over"), count = total,
                hint = L("Refills marked ready, and prescriptions signed to be handed out at the visit. The sale at the Store is what moves stock, money and the log."),
            )
            ListCard(style = ListCardStyle.panel) {
                if (rows.isEmpty()) {
                    EmptyState(systemImage = "package-check", title = L("Nothing ready to hand over."), accent = Theme.mango)
                } else {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        handOverRow(row)
                    }
                }
                LoadMoreRow(hasMore = total > rows.size, busy = loadingMore) { more { handOverLimit += pharmacyPage } }
            }
            ListCount(shown = rows.size, total = total, one = L("1 to hand over"), many = L("%d to hand over"), modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    fun refillRow(request: StockRefillRequest) {
        val target = request.handOver
        val line = target?.let {
            StockHandOverLine(
                prescriptionItemId = it.prescriptionItemId ?: "", medicineName = request.medicineName, strength = request.strength, quantityUnit = null,
                controlSchedule = request.controlSchedule, catalogItemId = it.catalogItemId, productName = it.productName, remaining = it.remaining,
                quantity = it.quantity, block = it.block,
            )
        }
        val g = line?.let { gate(controlled = request.controlSchedule.nonEmpty != null, lines = listOf(it)) } ?: Gate.noProduct
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StockPetLine(petName = request.petName, species = request.petSpecies, photoUrl = request.petPhotoUrl, clientName = request.clientName)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                medicineLine(
                    request.medicineName, request.strength,
                    if (request.needsRenewal == true) LF("nothing left on %@: approving renews it", request.folio ?: "")
                    else LF("%d left on %@, valid until %@", request.remaining ?: 0, request.folio ?: "", StockText.day(request.validUntil)),
                )
                factsLine(
                    listOf(
                        request.requestedByName?.let { LF("asked by %@", it) },
                        if (request.origin == "staff") (request.loggedByName?.let { LF("logged by %@", it) } ?: L("logged by the clinic")) else null,
                        StockRxWords.waiting(request.createdAt),
                    ),
                ) {
                    StockRefillPill(status = request.status)
                    StockRxWords.schedule(request.controlSchedule)?.let { StockControlledChip(label = it) }
                }
                request.note.nonEmpty?.let { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Glyph("message-square-text", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                        Text("“$note”", style = Theme.text(14), color = Theme.inkStrong)
                    }
                }
                if (request.status == "approved" && (g == Gate.noProduct || g == Gate.controlled)) gateNote(g)?.let { reason(it) }
            }
            FlowLayout(spacing = 8.dp) {
                if (request.status == "pending" && canApprove) {
                    PMButton(
                        if (request.needsRenewal == true) L("Renew and approve") else L("Approve"),
                        systemImage = "check", size = ButtonSize.modal, isWorking = busy,
                    ) { scope.launch { approve(request, ready = false) } }
                    PMButton(L("Approve — ready"), systemImage = "package-check", variant = ButtonVariant.outline, size = ButtonSize.modal, enabled = !busy) {
                        scope.launch { approve(request, ready = true) }
                    }
                    PMButton(L("Decline"), systemImage = "x", variant = ButtonVariant.ghost, size = ButtonSize.modal, tint = Theme.destructive, enabled = !busy) {
                        declining = request
                    }
                }
                if (request.status == "approved") {
                    if (canMarkReady) {
                        PMButton(L("Ready for pickup"), systemImage = "package-check", size = ButtonSize.modal, isWorking = busy) { scope.launch { markReady(request) } }
                    }
                    if (target != null && g != Gate.noProduct) {
                        val prefill = StockHandOver.prefill(
                            clientId = request.providerClientId, petId = request.providerClientPetId, prescriptionId = request.prescriptionId,
                            lines = line?.let { listOf(it) } ?: emptyList(),
                        )
                        PMButton(L("Hand over"), systemImage = "shopping-cart", variant = ButtonVariant.outline, size = ButtonSize.modal, enabled = g == Gate.ok && prefill != null) {
                            nav.push(StockHandOver.route(prefill))
                        }
                    }
                    if (canMarkReady) {
                        PMButton(L("Mark as picked up"), systemImage = "check", variant = ButtonVariant.outline, size = ButtonSize.modal, enabled = !busy) {
                            pickingUp = Pair(request.id, listOf(request.medicineName, request.petName).mapNotNull { it.nonEmpty }.joinToString(" · "))
                        }
                    }
                }
                PMButton(L("Open prescription"), systemImage = "file-text", variant = ButtonVariant.ghost, size = ButtonSize.modal) {
                    onOpen(request.renewalPrescriptionId ?: request.prescriptionId)
                }
                StockChartLink(clientId = request.providerClientId, petId = request.providerClientPetId, canViewClients = rights.canViewClients)
            }
        }
    }

    @Composable
    fun refillSection() {
        val rows = refills?.items ?: emptyList()
        val total = refills?.total ?: rows.size
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(title = L("Refills waiting"), count = total, hint = L("Asked for by families in their app, or logged here for a client who phoned."))
            ListCard(style = ListCardStyle.panel) {
                if (rows.isEmpty()) {
                    EmptyState(systemImage = "bell-ring", title = L("No refill requests waiting."), accent = Theme.mango)
                } else {
                    rows.forEachIndexed { index, request ->
                        if (index > 0) RowDivider()
                        refillRow(request)
                    }
                }
                LoadMoreRow(hasMore = total > rows.size, busy = loadingMore) { more { refillLimit += pharmacyPage } }
            }
            ListCount(shown = rows.size, total = total, one = L("1 request"), many = L("%d requests"), modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    fun draftSection() {
        val rows = drafts?.items ?: emptyList()
        val total = drafts?.total ?: rows.size
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(
                title = L("Drafts to sign"), count = total,
                hint = L("Written and not signed yet — from a visit, a chart or a hospital discharge. Nothing is printed or shared until a vet signs."),
            )
            ListCard(style = ListCardStyle.panel) {
                if (rows.isEmpty()) {
                    EmptyState(systemImage = "pen-line", title = L("No drafts waiting for a signature."), accent = Theme.mango)
                } else {
                    rows.forEachIndexed { index, draft ->
                        if (index > 0) RowDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StockPetLine(petName = draft.petName, species = draft.petSpecies, photoUrl = draft.petPhotoUrl, clientName = draft.clientName, breed = draft.petBreed)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(draft.medicines, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                                factsLine(listOf(if (draft.origin == "visit") L("from a visit") else null, StockRxWords.waiting(draft.createdAt))) {
                                    StockRxStatePill(state = "draft")
                                    if (draft.controlled == true) StockControlledChip()
                                    if (draft.origin == "discharge") StockRxChip(text = L("Hospital discharge"), tone = Hue.sky.color)
                                    if (draft.origin == "renewal") StockRxChip(text = L("Renewal"), tone = Hue.sky.color)
                                }
                            }
                            FlowLayout(spacing = 8.dp) {
                                if (rights.canSign || rights.canWrite) {
                                    PMButton(if (rights.canSign) L("Finish and sign") else L("Finish the draft"), systemImage = "pen-line", size = ButtonSize.modal) { finishing = draft }
                                }
                                PMButton(L("Open prescription"), systemImage = "file-text", variant = ButtonVariant.ghost, size = ButtonSize.modal) { onOpen(draft.id) }
                                StockChartLink(clientId = draft.providerClientId, petId = draft.providerClientPetId, canViewClients = rights.canViewClients)
                            }
                        }
                    }
                }
                LoadMoreRow(hasMore = total > rows.size, busy = loadingMore) { more { draftLimit += pharmacyPage } }
            }
            ListCount(shown = rows.size, total = total, one = L("1 draft"), many = L("%d drafts"), modifier = Modifier.padding(top = 4.dp))
        }
    }

    // The tab's pieces sit in the page's 20-apart column, as iOS's Group inside PageScroll.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (history) {
            StockRequestHistory(rights = rights, onBack = { history = false }, onOpen = onOpen)
        } else {
            // MARK: Content
            val tallyHandover = counts?.toHandOver ?: handOver?.total ?: 0
            val tallyRefills = counts?.refillsWaiting ?: refills?.total ?: 0
            val tallyDrafts = counts?.draftsToSign ?: drafts?.total ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToneChip(L("All@rx"), icon = "layout-grid", tone = ChipTone.page, isOn = focus.value == StockTodoFocus.all) { focus.value = StockTodoFocus.all }
                    ToneChip(L("To hand over"), icon = "package-check", tone = ChipTone.waiting, count = tallyHandover, isOn = focus.value == StockTodoFocus.handover) {
                        focus.value = if (focus.value == StockTodoFocus.handover) StockTodoFocus.all else StockTodoFocus.handover
                    }
                    ToneChip(L("Refills waiting"), icon = "bell-ring", tone = ChipTone.waiting, count = tallyRefills, isOn = focus.value == StockTodoFocus.refills) {
                        focus.value = if (focus.value == StockTodoFocus.refills) StockTodoFocus.all else StockTodoFocus.refills
                    }
                    ToneChip(L("Drafts to sign"), icon = "pen-line", tone = ChipTone.upcoming, count = tallyDrafts, isOn = focus.value == StockTodoFocus.drafts) {
                        focus.value = if (focus.value == StockTodoFocus.drafts) StockTodoFocus.all else StockTodoFocus.drafts
                    }
                }
                FlowLayout(spacing = 8.dp) {
                    if (onLogRefill != null) {
                        PMButton(L("Log a refill request"), systemImage = "bell-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onLogRefill)
                    }
                    PMButton(L("All requests"), systemImage = "history", variant = ButtonVariant.ghost, size = ButtonSize.modal) { history = true }
                }
            }
            if (handOver == null && refills == null && drafts == null) {
                val error = loadError
                if (error != null) ErrorState(message = error) { load() } else LoadingState()
            } else if ((handOver?.total ?: 0) == 0 && (refills?.total ?: 0) == 0 && (drafts?.total ?: drafts?.items?.size ?: 0) == 0) {
                EmptyState(
                    systemImage = "clipboard-check", title = L("Nothing waiting."),
                    message = L("Refill requests, drafts to sign and medicines ready to hand over show here."),
                    actionTitle = if (onNew != null) L("New prescription") else if (onLogRefill != null) L("Log a refill request") else null,
                    action = onNew ?: onLogRefill, accent = Theme.mango, panel = true,
                )
            } else {
                if (focus.value == StockTodoFocus.all || focus.value == StockTodoFocus.handover) handOverSection()
                if (focus.value == StockTodoFocus.all || focus.value == StockTodoFocus.refills) refillSection()
                if (focus.value == StockTodoFocus.all || focus.value == StockTodoFocus.drafts) draftSection()
            }
        }
    }
    ReloadsOnScope { load() }

    declining?.let { request ->
        AppSheet(onDismissRequest = { declining = null }) {
            ConfirmSheet(
                title = L("Decline this refill?"),
                message = listOf(request.medicineName, request.petName).mapNotNull { it.nonEmpty }.joinToString(" · "),
                confirmTitle = if (request.familyLinked != false) L("Decline and tell the family") else L("Decline"),
                destructive = true, askReason = true, reasonRequired = true, reasonLabel = L("Why@refill"), cancelTitle = L("Back@rx"),
            ) { reason ->
                answer(request.id, decision = "decline", reason = reason)
                Toast.show(if (request.familyLinked != false) L("Declined. The family was told why.") else L("Declined."))
                load()
                announceDataChanged()
            }
        }
    }
    pickingUp?.let { target ->
        AppSheet(onDismissRequest = { pickingUp = null }) {
            ConfirmSheet(
                title = L("Mark as picked up?"),
                message = L("For a medicine handed over outside a PawMiaw sale. Nothing is sold here: stock, money and the dispensing log stay as they are.") + "\n" + target.second,
                confirmTitle = L("Mark as picked up"), destructive = true, askReason = true, reasonRequired = false, reasonLabel = L("Note"), cancelTitle = L("Back@rx"),
            ) { note ->
                answer(target.first, decision = "picked_up", note = note)
                Toast.show(L("Marked as picked up."))
                load()
                announceDataChanged()
            }
        }
    }
    finishing?.let { draft ->
        AppSheet(onDismissRequest = { finishing = null }) {
            StockPrescriptionSheet(
                pet = StockRxPet(
                    clientId = draft.providerClientId, petId = draft.providerClientPetId, petName = draft.petName ?: "",
                    species = draft.petSpecies, photoUrl = draft.petPhotoUrl, clientName = draft.clientName,
                ),
                draft = draft,
            ) { saved -> onOpen(saved.id) }
        }
    }
    credential?.let { pending ->
        AppSheet(onDismissRequest = { credential = null }) {
            val type = pending.third
            TeamCredentialSheet(
                memberId = "me", isSelf = true, country = Workspace.shared.current?.country, businessAddress = Workspace.shared.current?.address,
                types = type?.let { listOf(it) },
                reason = when (type) {
                    "cedula_profesional" -> L("To renew it, add your cédula profesional: renewing signs a new prescription.")
                    "state_license" -> L("To renew it, add your state license: renewing signs a new prescription.")
                    else -> L("Renewing it signs a new prescription: add your professional credential first.")
                },
            ) {
                val request = pending.first
                val ready = pending.second
                credential = null
                scope.launch { approve(request, ready) }
            }
        }
    }
}

// MARK: - Every refill request

@Composable
private fun StockRequestHistory(rights: StockRxRights, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val query = remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(pharmacyPage) }
    var page by remember { mutableStateOf<StockRefillPage?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            page = API.get<StockRefillPage>("/provider-prescriptions/refill-requests", mapOf("status" to "all", "limit" to "$limit", "q" to searched.nonEmpty))
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadError = errorText(e)
        }
    }

    @Composable
    fun row(request: StockRefillRequest) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StockPetLine(petName = request.petName, species = request.petSpecies, photoUrl = request.petPhotoUrl, clientName = request.clientName)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOf(request.medicineName, request.strength).mapNotNull { it.nonEmpty }.joinToString(" · "),
                    style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                )
                FlowLayout(spacing = 8.dp) {
                    StockRefillPill(status = request.status)
                    Text(
                        listOf(
                            request.folio,
                            request.requestedByName?.let { LF("asked by %@", it) },
                            if (request.origin == "staff") L("logged by the clinic") else null,
                            StockText.dateTime(request.createdAt),
                            if (request.pickupSource == "manual") L("handed over outside a sale") else null,
                        ).mapNotNull { it.nonEmpty }.joinToString(" · "),
                        style = Theme.text(12), color = Theme.inkSoft,
                    )
                }
                request.declineReason.nonEmpty?.let { Text(LF("Reason: %@", it), style = Theme.text(14), color = Theme.inkStrong) }
            }
            FlowLayout(spacing = 8.dp) {
                PMButton(L("Open prescription"), systemImage = "file-text", variant = ButtonVariant.ghost, size = ButtonSize.modal) {
                    onOpen(request.renewalPrescriptionId ?: request.prescriptionId)
                }
                StockChartLink(clientId = request.providerClientId, petId = request.providerClientPetId, canViewClients = rights.canViewClients)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PMButton(L("Back to To do"), systemImage = "arrow-left", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onBack)
            SearchField(prompt = L("Folio, medicine, pet or client"), text = query)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(
                title = L("All refill requests"), count = page?.total ?: page?.items?.size,
                hint = L("Answered ones too: approved, picked up, declined and withdrawn."),
            )
            ListCard(style = ListCardStyle.panel) {
                val current = page
                when {
                    current == null -> {
                        val error = loadError
                        if (error != null) ErrorState(message = error) { load() } else LoadingState()
                    }
                    current.items.isEmpty() -> EmptyState(
                        systemImage = "bell-ring",
                        title = if (searched.isEmpty()) L("No refill requests yet.") else L("No request matches that."),
                        message = if (searched.isEmpty()) L("A family asks for one from a prescription in their app; the clinic can log one for a client who phoned.") else null,
                        accent = Theme.mango,
                    )
                    else -> {
                        refillGroups(current.items).forEach { group ->
                            DayBand(title = StockText.day(group.second), style = DayBand.Style.caps)
                            group.third.forEachIndexed { index, request ->
                                if (index > 0) RowDivider()
                                row(request)
                            }
                            RowDivider()
                        }
                        val total = current.total ?: current.items.size
                        LoadMoreRow(hasMore = total > current.items.size, busy = loadingMore) {
                            if (loadingMore) return@LoadMoreRow
                            limit += pharmacyPage
                            loadingMore = true
                            scope.launch { load(); loadingMore = false }
                        }
                    }
                }
            }
            page?.let {
                ListCount(shown = it.items.size, total = it.total ?: it.items.size, one = L("1 request"), many = L("%d requests"), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    Debounced(query.value) { text ->
        val trimmed = text.trimmingWhitespaces()
        if (trimmed == searched) return@Debounced
        searched = trimmed
        limit = pharmacyPage
        load()
    }
}

/** Rows under the day they were asked for, in the order they came (web `groupByDay`): (key, day, items). */
private fun refillGroups(items: List<StockRefillRequest>): List<Triple<String, String, List<StockRefillRequest>>> {
    val groups = mutableListOf<Triple<String, String, MutableList<StockRefillRequest>>>()
    for (item in items) {
        val day = DateText.parse(item.createdAt)?.let { DateText.dayKey(it, Workspace.shared.timeZone) } ?: ""
        val last = groups.lastOrNull()
        if (last != null && last.second == day) {
            last.third.add(item)
        } else {
            groups.add(Triple("$day-${groups.size}", day, mutableListOf(item)))
        }
    }
    return groups
}

// MARK: - Log a refill request

/** For a client who phoned or came by, or a family that isn't on PawMiaw: which prescription, which medicine, who asked. */
@Composable
fun StockLogRefillSheet(
    /** Opened from a prescription: it is already chosen. */
    prescription: StockPrescription?,
    onSaved: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    // `.onAppear` (once): a prescription handed in is chosen, and its only medicine with it.
    var chosen by remember { mutableStateOf(prescription) }
    var itemId by remember { mutableStateOf(prescription?.let { if (it.items.size == 1) it.items.firstOrNull()?.id else null }) }
    val who = remember { mutableStateOf("") }
    val note = remember { mutableStateOf("") }
    val query = remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<StockPrescription>?>(null) }
    /** What the server holds beyond the rows loaded (20 at a time, more as the list scrolls). */
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    suspend fun loadPage(reset: Boolean) {
        val offset = if (reset) 0 else (found?.size ?: 0)
        val page = try {
            API.get<StockPrescriptionPage>(
                "/provider-prescriptions",
                mapOf(
                    "q" to ClientSearch.query(query.value), "state" to null, "limit" to "${ClientSearch.pageSize}",
                    "offset" to (if (offset > 0) "$offset" else null),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (!currentCoroutineContext().isActive) return
        val items = page?.items ?: emptyList()
        found = if (reset) items else (found ?: emptyList()) + items
        hasMore = page?.hasMore ?: (page?.let { (it.total ?: 0) > offset + items.size } ?: false)
    }

    suspend fun loadMore() {
        if (!hasMore || loadingMore) return
        loadingMore = true
        try {
            loadPage(reset = false)
        } finally {
            loadingMore = false
        }
    }

    fun choose(entry: StockPrescription) {
        chosen = entry
        itemId = if (entry.items.size == 1) entry.items.firstOrNull()?.id else null
    }

    suspend fun save() {
        val current = chosen ?: return
        if (!form.check(listOf("item" to (if (itemId == null) L("Choose the medicine.") else null)))) return
        val item = itemId ?: return
        if (saving) return
        saving = true
        try {
            StockAPI.send<EmptyResponse>(
                "POST", "/provider-prescriptions/${current.id}/refill-requests",
                body = mapOf("prescriptionItemId" to item, "note" to note.value.nonEmpty, "requestedByName" to who.value.nonEmpty),
            )
            Toast.show(L("Refill request logged. A vet answers it in To do."))
            onSaved?.invoke()
            announceDataChanged()
            dismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(StockRxWords.error(e))
        } finally {
            saving = false
        }
    }

    ProviderModal(
        title = L("Log a refill request"), subtitle = L("For a client who phoned or came by, or a family that isn’t on PawMiaw."),
        systemImage = "bell-plus", accent = ModalAccent.butter,
        isDirty = (chosen != null && prescription == null) || who.value.isNotEmpty() || note.value.isNotEmpty(), form = form,
        primaryTitle = if (chosen != null) L("Log request") else null, isWorking = saving,
        onBack = if (chosen != null && prescription == null) ({ chosen = null; itemId = null }) else null,
        primaryIcon = "bell-plus",
        onPrimary = if (chosen != null) ({ scope.launch { save() } }) else null,
        modifier = modifier,
    ) {
        val current = chosen
        if (current != null) {
            ContextPills(client = current.clientName.nonEmpty, pets = listOfNotNull(current.petName.nonEmpty))
            FormField(label = L("Which medicine?"), key = "item", form = form) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.items.forEach { item ->
                        val on = item.id == itemId
                        val shape = RoundedCornerShape(Theme.boxRadius)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(if (on) Theme.wash(Theme.aqua, 16.0) else Theme.surface, shape)
                                .border(2.dp, if (on) Theme.blue else Theme.borderSoft, shape)
                                .semantics { selected = on }
                                .plainButton { itemId = item.id; form.clear("item") }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    listOf(item.medicineName, item.strength).mapNotNull { it.nonEmpty }.joinToString(" · "),
                                    style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong,
                                )
                                Text(
                                    if ((item.fill?.remaining ?: 0) > 0) LF("%d left on %@", item.fill?.remaining ?: 0, current.folio ?: "")
                                    else LF("Nothing left on %@: approving it renews the prescription.", current.folio ?: ""),
                                    style = Theme.text(12), color = Theme.inkSoft,
                                )
                            }
                            if (on) Glyph("package-check", 20.dp, tint = Theme.blue)
                        }
                    }
                }
            }
            PMTextField(
                L("Who asked"), text = who, prompt = L("e.g. Ana, by phone"),
                hint = L("When it wasn’t the client themselves, or to remember how they asked."), optional = true,
            )
            PMTextField(L("Note for the vet"), text = note, optional = true, multiline = true)
        } else {
            SearchField(prompt = L("Folio, medicine, pet or client"), text = query, shape = SearchField.Shape.field)
            val list = found
            if (list != null) {
                val signed = list.filter { it.isSigned }
                if (signed.isEmpty()) {
                    NoticeCard(
                        tone = NoticeCard.Tone.info,
                        message = if (query.value.isEmpty()) L("No signed prescriptions yet.") else L("No signed prescription matches that."),
                        icon = null,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        signed.forEach { entry ->
                            val shape = RoundedCornerShape(Theme.boxRadius)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .background(Theme.surface, shape)
                                    .border(2.dp, Theme.borderSoft, shape)
                                    .plainButton { choose(entry) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.medicines, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(entry.folio, entry.petName, entry.clientName, entry.signedAt?.let { StockText.day(it) }).mapNotNull { it.nonEmpty }.joinToString(" · "),
                                        style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // HStack(spacing: 12) around a Spacer(minLength: 8).
                                Spacer(Modifier.width(8.dp))
                                StockRxStatePill(state = entry.state)
                                Glyph("chevron-right", 16.dp, tint = Theme.inkSoft)
                            }
                        }
                        LoadMoreRow(hasMore = hasMore, busy = loadingMore) { scope.launch { loadMore() } }
                    }
                }
            } else {
                LoadingState()
            }
        }
    }
    // The server searches from two characters, 250 ms after typing stops; 20 at a time.
    LaunchedEffect(ClientSearch.query(query.value) ?: "") {
        if (chosen != null) return@LaunchedEffect
        if (found != null) delay(ClientSearch.debounce)
        if (!isActive) return@LaunchedEffect
        loadPage(reset = true)
    }
}
