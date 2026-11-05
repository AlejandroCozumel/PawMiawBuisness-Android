package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

//
//  StockPrescriptionDetail.kt (iOS StockPrescriptionDetail.swift)
//
//  One prescription (web prescriptions/prescription-view-sheet.tsx), on its
//  own page: its medicines with what is left and each time one was handed
//  out (and whether the owner was counselled), its refill requests, the link
//  and QR for an outside pharmacy (never for a controlled one) and the signed
//  snapshot as it prints. What its state allows: finish and sign a draft or
//  discard it, hand over what can be sold (the Store's counter, with the
//  client, the pet and each medicine against its line), add it to the
//  visit's bill, log a refill, mark a medicine fully dispensed or reopen it,
//  the PDF, and cancelling with its reason.
//

@Composable
fun StockPrescriptionScreen(prescriptionId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    var prescription by remember { mutableStateOf<StockPrescription?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<StockCreatedShare?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }
    var counselling by remember { mutableStateOf<StockCounselTarget?>(null) }
    var closing by remember { mutableStateOf<StockRxItem?>(null) }
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val nav = LocalNavigator.current

    val title: String = prescription.let { p ->
        when {
            p == null -> L("Prescription")
            p.folio.nonEmpty != null -> LF("Prescription %@", p.folio.nonEmpty)
            p.isDraft -> L("Prescription draft")
            else -> L("Prescription")
        }
    }

    /** A request whose refusal comes back in the Pharmacy's words, for a ConfirmSheet to show. */
    suspend fun perform(method: String, path: String, body: Map<String, Any?>?) {
        try {
            StockAPI.send<EmptyResponse>(method, path, body = body)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw APIError(status = (error as? StockRefusal)?.status ?: 400, message = StockRxWords.error(error), code = (error as? StockRefusal)?.code, fields = emptyMap())
        }
    }

    suspend fun load() {
        try {
            prescription = API.get<StockPrescription>("/provider-prescriptions/$prescriptionId")
            loadError = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = errorText(error)
        }
    }

    suspend fun changed() {
        load()
        announceDataChanged()
    }

    // MARK: What can be handed over

    /** The lines a sale at the Store can hand over now. */
    fun handOverLines(p: StockPrescription): List<StockRxItem> {
        if (!p.isSigned) return emptyList()
        return p.items.filter { item ->
            val block = item.handOverBlock ?: (if (item.catalogItemId != null && (item.fill?.remaining ?: 0) > 0) null else "not_in_catalog")
            block == null && item.product?.active != false && item.catalogItemId != null && (item.nextFill ?: item.fill?.remaining ?: 0) > 0
        }
    }

    fun handOverReason(p: StockPrescription): String? {
        if (handOverLines(p).isEmpty()) return null
        if (!(p.canSell ?: p.canHandOver ?: false)) return L("Someone who sells hands it over at the Store.")
        if (p.controlled == true && p.canHandOverControlled == false) return L("A controlled medicine is handed over by a vet. Ask one to do it.")
        return null
    }

    suspend fun downloadPDF(p: StockPrescription) {
        busy = true
        try {
            // The PDF, in the phone's viewer (which shares and prints it), as `DocumentButton` opens a server document.
            val file = API.download("/provider-prescriptions/${p.id}/pdf", fileName = p.folio ?: L("Prescription"))
            quickLookPreview(context, file)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Toast.error(stockErrorText(error, fallback = L("The file couldn’t be downloaded. Try again.")))
        } finally {
            busy = false
        }
    }

    suspend fun addToBill(p: StockPrescription) {
        busy = true
        try {
            val result: JSONValue = StockAPI.send("POST", "/provider-prescriptions/${p.id}/visit-bill", body = emptyMap())
            Toast.show(if ((result["lines"].int ?: 0) > 0) L("Added to the visit’s bill.") else L("Nothing to add: its medicines aren’t Catalog products."))
            changed()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Toast.error(StockRxWords.error(error))
        } finally {
            busy = false
        }
    }

    suspend fun reopen(item: StockRxItem, p: StockPrescription) {
        busy = true
        try {
            StockAPI.send<EmptyResponse>("POST", "/provider-prescriptions/${p.id}/items/${item.id}/close", body = mapOf("closed" to false, "note" to null))
            Toast.show(L("Reopened: it can be dispensed again."))
            changed()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Toast.error(StockRxWords.error(error))
        } finally {
            busy = false
        }
    }

    suspend fun makeLink(p: StockPrescription) {
        busy = true
        try {
            link = StockAPI.send<StockCreatedShare>("POST", "/provider-prescriptions/${p.id}/shares", body = emptyMap())
            load()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Toast.error(StockRxWords.error(error))
        } finally {
            busy = false
        }
    }

    suspend fun stopLink(p: StockPrescription, share: StockShare) {
        busy = true
        try {
            StockAPI.send<EmptyResponse>("DELETE", "/provider-prescriptions/${p.id}/shares/${share.id}")
            if (link?.id == share.id) link = null
            Toast.show(L("Link stopped."))
            load()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Toast.error(StockRxWords.error(error))
        } finally {
            busy = false
        }
    }

    fun withUnit(count: Int, unit: String?): String = unit.nonEmpty?.let { "$count $it" } ?: "$count"

    /**
     * The web modal's header: the folio, who signed it and when, and its actions (the modal's footer:
     * discard or cancel, the PDF, finish and sign, hand over).
     */
    @Composable
    fun Header(p: StockPrescription) {
        val lines = handOverLines(p)
        val prefill = StockHandOver.prefill(p)
        ModuleHeader(
            icon = "file-text",
            title = title,
            subtitle = listOf(p.prescriberName, p.signedAt?.let { StockText.day(it) } ?: LF("written %@", StockText.day(p.createdAt))).mapNotNull { it.nonEmpty }.joinToString(" · "),
            accent = Theme.aqua,
            actionsOnRow = true,
            storageKey = "prescription",
        ) {
            if (p.isDraft && p.canWrite == true) {
                PMButton(L("Discard draft"), systemImage = "trash-2", variant = ButtonVariant.destructive, enabled = !busy) { discarding = true }
            }
            if (p.isSigned && p.canCancel == true) {
                PMButton(L("Cancel prescription"), systemImage = "ban", variant = ButtonVariant.destructive, enabled = !busy) { cancelling = true }
            }
            if (p.snapshot != null && !p.isDraft) {
                PMButton(
                    if (p.controlled == true) L("Print original and copy") else L("Download PDF"),
                    systemImage = if (p.controlled == true) "printer" else "file-down",
                    variant = ButtonVariant.outline,
                    isWorking = busy,
                ) { scope.launch { downloadPDF(p) } }
            }
            if (p.isDraft && (p.canWrite == true || p.canSign == true)) {
                PMButton(if (p.canSign == true) L("Finish and sign") else L("Finish the draft"), systemImage = "pen-line", enabled = !busy) { finishing = true }
            }
            if (lines.isNotEmpty()) {
                PMButton(
                    L("Hand over"),
                    systemImage = "shopping-cart",
                    size = ButtonSize.header,
                    enabled = !(handOverReason(p) != null || busy || prefill == null),
                ) { nav.push(StockHandOver.route(prefill)) }
            }
        }
    }

    /** Whose it is and what it is (web `ModalContextPills` and the chips under them). */
    @Composable
    fun Context(p: StockPrescription) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowLayout(spacing = 8.dp) {
                StockRxStatePill(state = p.state)
                p.clientName.nonEmpty?.let { Chip(text = it, tone = Chip.Tone.muted, icon = "user-round") }
                p.petName.nonEmpty?.let { Chip(text = it, tone = Chip.Tone.muted, icon = "paw-print") }
                if (p.controlled == true) StockControlledChip()
                p.validUntil?.let { Chip(text = LF("Valid until %@", StockText.day(it)), tone = Chip.Tone.muted) }
                if (workspace.can(Permission.clientsView)) {
                    StockChartLink(clientId = p.providerClientId, petId = p.providerClientPetId, canViewClients = true)
                }
            }
            FlowLayout(spacing = 8.dp) {
                if (p.sharedWithFamilyAt != null) StockRxChip(text = L("In the family’s app"), tone = Theme.blue)
                else if (p.familyLinked == false) StockRxChip(text = L("Family not on PawMiaw"))
                if (p.origin == "discharge") StockRxChip(text = L("Hospital discharge"), tone = Hue.sky.color)
                if (p.origin == "renewal") StockRxChip(text = L("Renewal"), tone = Hue.sky.color)
                if (p.expiresSoon == true) StockRxChip(text = L("Expires soon"), tone = Theme.mango, strong = true)
            }
        }
    }

    /**
     * One medicine (web `MedicineCard`): what was prescribed, what is left in its own unit, how it is
     * counted when that isn't obvious, and each time it was handed out.
     */
    @Composable
    fun MedicineCard(item: StockRxItem, p: StockPrescription) {
        val dispenses = (p.dispenses ?: emptyList()).filter { it.prescriptionItemId == item.id }
        val closed = item.closedAt != null
        val canClose = p.canHandOver == true && p.isSigned
        val controlledBlocked = item.controlSchedule.nonEmpty != null && p.canHandOverControlled == false
        val box = RoundedCornerShape(Theme.boxRadius)
        val chip = RoundedCornerShape(Theme.chipRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.surface, box)
                .border(2.dp, Theme.borderSoft, box)
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FlowLayout(spacing = 8.dp) {
                        Text(listOf(item.medicineName, item.strength).mapNotNull { it.nonEmpty }.joinToString(" · "), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                        StockRxWords.schedule(item.controlSchedule)?.let { StockControlledChip(label = it) }
                    }
                    Text(item.doseText.nonEmpty ?: "—", style = Theme.text(14), color = Theme.inkSoft)
                }
                item.fill?.let { fill ->
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            LF("%d of %@ left", fill.remaining ?: 0, withUnit(fill.allowed ?: 0, item.quantityUnit)),
                            style = Theme.heading(20).monospacedDigit(),
                            color = Theme.inkStrong,
                            textAlign = TextAlign.End,
                        )
                        Text(if ((fill.refillsLeft ?: 0) == 1) L("1 refill left") else LF("%d refills left", fill.refillsLeft ?: 0), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
            }
            if (p.isSigned && !closed) {
                val product = item.product?.name
                val basis = when {
                    item.countBasis == "bill" -> L("Written in free text: each unit on a bill counts one, whatever its size. When what was sold covers it, mark it fully dispensed.")
                    item.countBasis == "product" && product != null -> LF("Counted in units of %@: a pack counts what it holds.", product)
                    item.countBasis == "pack" && product != null -> LF("Counted in packs of %@ (%d each).", product, item.product?.unitsPerPack ?: 1)
                    else -> null
                }
                if (basis != null) Text(basis, style = Theme.text(12), color = Theme.inkSoft, modifier = Modifier.padding(top = 8.dp))
            }
            if (closed) {
                FlowLayout(spacing = 8.dp, modifier = Modifier.padding(top = 8.dp)) {
                    StockRxChip(text = L("Fully dispensed"), tone = Hue.sky.color, icon = "check-check", strong = true)
                    Text(
                        listOf(item.closedByName, item.closedAt?.let { StockText.day(it) }, item.closeNote).mapNotNull { it.nonEmpty }.joinToString(" · "),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
            }
            if (p.isSigned && !closed && item.handOverBlock == "not_in_catalog") {
                Text(StockRxWords.block("not_in_catalog") ?: "", style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, modifier = Modifier.padding(top = 8.dp))
            }
            if (dispenses.isNotEmpty()) {
                val handedLabel = LF("Handed out of %@", item.medicineName)
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .border(1.dp, Theme.borderSoft, chip)
                        .semantics { contentDescription = handedLabel },
                ) {
                    dispenses.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val strong = Theme.inkStrong
                            val soft = Theme.inkSoft
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) {
                                        append("${if (entry.kind == "return") L("Came back") else L("Handed out")}: ${abs(entry.quantity ?: 0)}")
                                    }
                                    withStyle(SpanStyle(color = soft)) {
                                        append(" · " + (entry.sold.nonEmpty?.let { LF("sold %@", it) } ?: (entry.label ?: "")))
                                    }
                                },
                                style = Theme.text(14),
                            )
                            StockMetaLine(parts = listOf(entry.documentFolio, entry.dispensedByName, StockText.dateTime(entry.occurredAt)))
                            if (entry.kind != "return") {
                                if (entry.counselledAt != null) {
                                    StockRxChip(text = L("Counselled") + (entry.counselNote.nonEmpty?.let { ": $it" } ?: ""), tone = Theme.lime, icon = "hand-heart")
                                } else if (p.canCounsel == true) {
                                    PMButton(L("Record counselling"), systemImage = "hand-heart", variant = ButtonVariant.ghost, size = ButtonSize.modal, enabled = !busy) {
                                        counselling = StockCounselTarget(dispenseId = entry.id, label = entry.label ?: item.medicineName, petName = entry.petName)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (canClose && (closed || (item.fill?.remaining ?: 0) > 0)) {
                Box(Modifier.padding(top = 12.dp)) {
                    Disabled(busy || controlledBlocked) {
                        if (closed) {
                            PMButton(L("Reopen"), systemImage = "rotate-ccw", variant = ButtonVariant.ghost, size = ButtonSize.modal) { scope.launch { reopen(item, p) } }
                        } else {
                            PMButton(L("Mark fully dispensed"), systemImage = "package-check", variant = ButtonVariant.ghost, size = ButtonSize.modal) { closing = item }
                        }
                    }
                }
            }
        }
    }

    // MARK: For an outside pharmacy

    @Composable
    fun OutsidePharmacy(p: StockPrescription) {
        SectionCard(L("For an outside pharmacy"), icon = "qr-code", accent = Theme.aqua) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (p.controlled == true) {
                    Text(L("A controlled prescription goes on paper, signed by hand — it can’t be sent as a link."), style = Theme.text(14), color = Theme.inkSoft)
                } else {
                    Text(L("A read-only link and QR the pharmacy opens to check it. It expires in 7 days and you can stop it any time."), style = Theme.text(14), color = Theme.inkSoft)
                    val made = link
                    if (made != null) {
                        val box = RoundedCornerShape(Theme.boxRadius)
                        Row(
                            Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, box).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val qr = APIConfig.assetURL(made.qrUrl)
                            if (qr != null) {
                                val qrLabel = L("QR code of the link")
                                SubcomposeAsyncImage(
                                    model = qr,
                                    contentDescription = qrLabel,
                                    contentScale = ContentScale.Fit,
                                    filterQuality = FilterQuality.None,
                                    loading = {
                                        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = Theme.inkSoft, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    modifier = Modifier
                                        .size(128.dp)
                                        .background(Color.White, RoundedCornerShape(Theme.chipRadius)),
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SelectionContainer {
                                    Text(made.url, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = Theme.inkStrong)
                                }
                                PMButton(L("Copy link"), systemImage = "copy", variant = ButtonVariant.outline) {
                                    clipboard.setText(AnnotatedString(made.url))
                                    Toast.show(L("Link copied."))
                                }
                                if (made.url.isNotEmpty()) {
                                    PMButton(L("Share"), systemImage = "share-2", variant = ButtonVariant.outline) { shareItems(context, listOf(made.url)) }
                                }
                                made.expiresAt?.let { Text(LF("Expires %@", StockText.dateTime(it)), style = Theme.text(12), color = Theme.inkSoft) }
                            }
                        }
                    } else {
                        PMButton(L("Create link and QR"), systemImage = "qr-code", variant = ButtonVariant.outline, isWorking = busy) { scope.launch { makeLink(p) } }
                    }
                    val active = (p.shares ?: emptyList()).filter { it.active != false }
                    if (active.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            active.forEach { share ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                                        Glyph("link-2", 16.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 2.dp))
                                        Text(
                                            listOf(
                                                "…${share.tokenHint ?: ""}",
                                                if (share.createdBySide == "family") L("made by the family") else L("made here"),
                                                LF("seen %d times", share.viewCount ?: 0),
                                                LF("expires %@", StockText.day(share.expiresAt)),
                                            ).joinToString(" · "),
                                            style = Theme.text(14),
                                            color = Theme.inkStrong,
                                        )
                                    }
                                    PMButton(L("Stop link"), systemImage = "unlink", variant = ButtonVariant.ghost, size = ButtonSize.modal, enabled = !busy) {
                                        scope.launch { stopLink(p, share) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: Page

    providerPage(title, modifier = modifier) {
        val p = prescription
        val failed = loadError
        if (p != null) {
            PageScroll(refresh = { load() }) {
                Header(p)
                Context(p)
                val reason = p.cancelReason.nonEmpty
                if (p.status == "cancelled" && reason != null) {
                    NoticeCard(tone = NoticeCard.Tone.problem, message = LF("Cancelled %@: %@", StockText.day(p.cancelledAt), reason), icon = "ban")
                }
                if (p.isDraft) NoticeCard(tone = NoticeCard.Tone.warning, message = L("A draft: not printed or shared until a vet signs it."), icon = "pen-line")

                SectionCard(L("Medicines"), subtitle = if (p.isDraft) null else L("What is left to hand over, and what was handed out."), icon = "pill", accent = Theme.aqua) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        p.items.forEach { item -> MedicineCard(item, p) }
                        val logs = p.isSigned && p.canLogRefill == true
                        val bills = p.isSigned && p.appointmentId != null && p.canDispenseAtVisit == true
                        if (logs || bills) {
                            FlowLayout(spacing = 8.dp) {
                                if (logs) {
                                    PMButton(L("Log a refill request"), systemImage = "bell-plus", variant = ButtonVariant.outline, enabled = !busy) { logging = true }
                                }
                                if (bills) {
                                    PMButton(L("Add to the visit’s bill"), systemImage = "receipt-text", variant = ButtonVariant.outline, isWorking = busy) { scope.launch { addToBill(p) } }
                                }
                            }
                        }
                        handOverReason(p)?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
                    }
                }

                val requests = p.refillRequests
                if (!requests.isNullOrEmpty()) {
                    SectionCard(L("Refill requests"), icon = "bell-ring", accent = Theme.aqua) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            requests.forEach { request ->
                                val chip = RoundedCornerShape(Theme.chipRadius)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Theme.borderSoft, chip)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    FlowLayout(spacing = 8.dp) {
                                        StockRefillPill(status = request.status)
                                        if (request.origin == "staff") StockRxChip(text = L("logged by the clinic"))
                                        request.note.nonEmpty?.let { Text("“$it”", style = Theme.text(14), color = Theme.inkStrong) }
                                        request.declineReason.nonEmpty?.let { Text(LF("Reason: %@", it), style = Theme.text(14), color = Theme.inkSoft) }
                                        if (request.pickupSource == "manual") {
                                            Text(
                                                request.pickupNote.nonEmpty?.let { LF("handed over outside a sale: %@", it) } ?: L("handed over outside a sale"),
                                                style = Theme.text(14),
                                                color = Theme.inkSoft,
                                            )
                                        }
                                    }
                                    Text(StockText.dateTime(request.createdAt), style = Theme.text(14), color = Theme.inkSoft)
                                }
                            }
                        }
                    }
                }

                if (p.isSigned) OutsidePharmacy(p)

                val snapshot = p.snapshot
                if (snapshot != null) {
                    SectionCard(L("The prescription as it prints"), icon = "file-text", accent = Theme.aqua) {
                        val box = RoundedCornerShape(Theme.boxRadius)
                        StockPrescriptionDocument(
                            header = StockDocHeader(snapshot = snapshot),
                            items = (snapshot.items ?: emptyList()).map { StockDocItem(it) },
                            cancelled = p.status == "cancelled",
                            integrity = p.integrity,
                            modifier = Modifier
                                .padding(16.dp)
                                .clip(box)
                                .border(1.dp, Theme.borderSoft, box),
                        )
                    }
                }
            }
        } else if (failed != null) {
            PageScroll { ErrorState(message = failed) { load() } }
        } else {
            PageScroll { LoadingState() }
        }
    }
    ReloadsOnScope { load() }

    if (cancelling) {
        AppSheet(onDismissRequest = { cancelling = false }) {
            ConfirmSheet(
                title = L("Cancel this prescription?"),
                message = L("Links stop working and nothing more can be dispensed from it."),
                confirmTitle = L("Cancel prescription"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                reasonLabel = L("Why is it cancelled?"),
                cancelTitle = L("Keep it"),
            ) { reason ->
                perform("POST", "/provider-prescriptions/$prescriptionId/cancel", mapOf("reason" to reason))
                Toast.show(L("Prescription cancelled. Nothing more can be dispensed from it."))
                changed()
            }
        }
    }
    if (discarding) {
        AppSheet(onDismissRequest = { discarding = false }) {
            ConfirmSheet(
                title = L("Discard this draft?"),
                message = L("It was never signed, so the family never saw it. It can’t be brought back."),
                confirmTitle = L("Discard draft"),
                destructive = true,
                cancelTitle = L("Keep the draft"),
            ) { _ ->
                perform("DELETE", "/provider-prescriptions/$prescriptionId", null)
                Toast.show(L("Draft discarded."))
                announceDataChanged()
                dismiss()
            }
        }
    }
    closing?.let { item ->
        AppSheet(onDismissRequest = { closing = null }) {
            ConfirmSheet(
                title = L("Mark as fully dispensed?"),
                message = L("Nothing more is handed over from this medicine, whatever is left on paper. Use it when what was sold covers what was prescribed. It can be reopened.") + "\n" + item.medicineName,
                confirmTitle = L("Mark fully dispensed"),
                destructive = true,
                askReason = true,
                reasonRequired = false,
                reasonLabel = L("Note"),
                cancelTitle = L("Back@rx"),
            ) { note ->
                perform("POST", "/provider-prescriptions/$prescriptionId/items/${item.id}/close", mapOf("closed" to true, "note" to note))
                Toast.show(L("Marked as fully dispensed."))
                changed()
            }
        }
    }
    counselling?.let { target ->
        AppSheet(onDismissRequest = { counselling = null }) { StockCounselSheet(target = target) }
    }
    if (logging) {
        AppSheet(onDismissRequest = { logging = false }) {
            prescription?.let { StockLogRefillSheet(prescription = it) }
        }
    }
    if (finishing) {
        AppSheet(onDismissRequest = { finishing = false }) {
            prescription?.let { p ->
                StockPrescriptionSheet(
                    pet = StockRxPet(
                        clientId = p.providerClientId, petId = p.providerClientPetId, petName = p.petName ?: "",
                        species = p.petSpecies, photoUrl = p.petPhotoUrl, clientName = p.clientName,
                    ),
                    draft = p,
                ) { _ -> scope.launch { load() } }
            }
        }
    }
}
