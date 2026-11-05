package com.pawmiaw.business.hospital

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

//
//  HospitalSheetTab.kt (iOS HospitalSheetTab.swift)
//
//  The treatment sheet of one day (web hospital-sheet-tab.tsx): a row per
//  order, each scheduled dose a cell in its state (ProviderState.slot); tap
//  one to mark it given or not given (with the web's reasons), at its real
//  time and dose, or to strike a wrong mark out. "Give now" / "Extra dose"
//  records a dose off schedule. A dose more than an hour ahead isn't marked
//  yet; a stopped order's missed doses still are (docs/hospital-sheet-and-notices.md).
//

private class HospTicking(val order: HospOrder, val slot: HospSheetSlot?) {
    val id: String get() = order.id + (slot?.scheduledFor ?: "-now")
}

private class HospViewing(val order: HospOrder, val entry: HospAdministration) {
    val id: String get() = entry.id
}

@Composable
fun HospSheetTab(
    model: HospDetailModel,
    detail: HospDetail,
    canCare: Boolean,
    canManage: Boolean,
    onOrders: () -> Unit,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ticking by remember { mutableStateOf<HospTicking?>(null) }
    var viewing by remember { mutableStateOf<HospViewing?>(null) }
    var showVoided by remember { mutableStateOf(false) }

    val zone = detail.zone
    val orders = LinkedHashMap<String, HospOrder>().apply { detail.allOrders.forEach { if (!containsKey(it.id)) put(it.id, it) } }
    val rows = (detail.sheet?.rows ?: emptyList()).filter { orders[it.orderId] != null }
    val day = detail.sheet?.day ?: detail.sheet?.today ?: ""
    val isToday = day == detail.sheet?.today

    fun action(order: HospOrder, slot: HospSheetSlot, state: String): (() -> Unit)? {
        val entry = slot.administration
        if (entry != null) return { viewing = HospViewing(order, entry) }
        if (!(canCare && detail.isAdmitted && (!order.isStopped || state == "missed"))) return null
        if (state == "upcoming") {
            val at = DateText.parse(slot.scheduledFor)
            if (at != null && at.toEpochMilli() - System.currentTimeMillis() > 3_600_000) return null
        }
        return { ticking = HospTicking(order, slot) }
    }

    @Composable
    fun orderRow(order: HospOrder, row: HospSheetRow) {
        val stopped = order.isStopped
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(if (stopped) 0.6f else 1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(HospWords.kind(order.kind).uppercase(), style = brandText(10.4, FontWeight.ExtraBold).tracking(1.04), color = Theme.blue)
                    if (stopped) Chip(text = L("Stopped"), tone = Chip.Tone.muted)
                }
                Text(
                    order.name.nonEmpty ?: L("Order"),
                    style = Theme.text(16, FontWeight.Bold).copy(textDecoration = if (stopped) TextDecoration.LineThrough else null),
                    color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val line = listOfNotNull(HospFmt.orderLine(order).nonEmpty, HospFmt.schedule(order, zone).nonEmpty).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                order.instructions.nonEmpty?.let { instructions ->
                    Text(instructions, style = Theme.text(12).copy(fontStyle = FontStyle.Italic), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            FlowLayout(spacing = 6.dp) {
                (row.slots ?: emptyList()).forEach { slot ->
                    val state = slot.state ?: "upcoming"
                    HospSlotCell(
                        state = state,
                        time = HospFmt.clock(slot.scheduledFor, zone),
                        detail = slot.administration?.let { a -> a.initials.nonEmpty ?: (if (a.status == "given") HospFmt.clock(a.givenAt, zone) else null) },
                        action = action(order, slot, state),
                    )
                }
                (row.extra ?: emptyList()).forEach { entry ->
                    HospExtraCell(
                        time = HospFmt.clock(entry.givenAt, zone),
                        word = if (entry.status != "given") L("Not given") else if (order.scheduleKind == "prn") L("Given") else L("Extra"),
                    ) { viewing = HospViewing(order, entry) }
                }
                if ((row.slots ?: emptyList()).isEmpty() && (row.extra ?: emptyList()).isEmpty()) {
                    Box(Modifier.defaultMinSize(minHeight = 48.dp), contentAlignment = Alignment.CenterStart) {
                        Text(if (order.scheduleKind == "prn") L("Nothing given yet") else L("No doses this day"), style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                if (canCare && detail.isAdmitted && !stopped && isToday) {
                    Row(
                        Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .dashedBorder(2.dp, Theme.borderSoft, Theme.chipRadius, on = 5.dp, off = 4.dp)
                            .plainButton { ticking = HospTicking(order, null) }
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("plus", 14.dp, tint = Theme.inkSoft)
                        Text(if (order.scheduleKind == "prn") L("Give now") else L("Extra dose"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                    }
                }
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (detail.sheet?.days ?: emptyList()).forEachIndexed { index, entry ->
                val on = entry == day
                val shape = RoundedCornerShape(Theme.chipRadius)
                Column(
                    Modifier
                        .defaultMinSize(minWidth = 72.dp)
                        .background(if (on) Theme.blue else Theme.surface, shape)
                        .border(2.dp, if (on) Theme.blue else Theme.borderSoft, shape)
                        .plainButton { model.day = if (entry == detail.sheet?.today) null else entry }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        (if (entry == detail.sheet?.today) L("Today") else LF("Day %d", index + 1)).uppercase(),
                        style = brandText(9.9, FontWeight.ExtraBold).tracking(0.99),
                        color = if (on) Theme.onBlue.copy(alpha = 0.9f) else Theme.inkSoft,
                    )
                    Text(HospFmt.day(entry, style = HospFmt.DayStyle.compact, zone = zone), style = Theme.text(14, FontWeight.Bold), color = if (on) Theme.onBlue else Theme.inkStrong)
                }
            }
        }
        PMButton(L("Day’s sheet PDF"), systemImage = "printer", variant = ButtonVariant.outline, size = ButtonSize.compact) { onPrint() }

        if (rows.isEmpty()) {
            // Waiting or in: the plan is still to write. Once it ended, there was simply none.
            if (detail.isAdmitted || detail.isPending) {
                EmptyState(
                    systemImage = "syringe", title = L("No treatment yet"),
                    message = if (detail.isPending) L("The treatment plan is written once the patient is admitted.")
                    else if (canManage) L("Write the treatment plan in Orders: each dose shows up here to tick.")
                    else L("The vet writes the treatment plan; each dose shows up here to tick."),
                    actionTitle = if (detail.isAdmitted && canManage) L("Write the orders") else null,
                    action = if (detail.isAdmitted && canManage) onOrders else null,
                    accent = Theme.pink, panel = true,
                )
            } else {
                EmptyState(systemImage = "syringe", title = L("No treatment on this day"), message = L("No order had a dose on this day."), accent = Theme.pink, panel = true)
            }
        } else {
            // One card, a row per order (web: the sheet's panel).
            Card(padding = 0.dp, spacing = 0.dp) {
                rows.forEachIndexed { index, row ->
                    val order = orders[row.orderId]
                    if (order != null) {
                        if (index > 0) RowDivider()
                        orderRow(order, row)
                    }
                }
            }
        }

        HospSlotLegend()

        val voided = rows.flatMap { row -> (row.voided ?: emptyList()).map { row.orderId to it } }
        if (voided.isNotEmpty()) {
            val turn by animateFloatAsState(if (showVoided) 180f else 0f, tween(250, easing = EaseOut), label = "voided")
            Card(padding = 0.dp, spacing = 0.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainButton { showVoided = !showVoided }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (voided.size == 1) L("1 struck out") else LF("%d struck out", voided.size),
                        style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                    )
                    Spacer(Modifier.weight(1f))
                    Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
                }
                AnimatedVisibility(visible = showVoided, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.fillMaxWidth()) {
                        RowDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val strong = Theme.inkStrong
                            val soft = Theme.inkSoft
                            voided.forEach { (orderId, entry) ->
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.LineThrough, color = strong)) {
                                            append("${orders[orderId]?.name ?: ""} · ${HospFmt.clock(entry.scheduledFor ?: entry.givenAt, zone)}")
                                        }
                                        withStyle(SpanStyle(color = soft)) {
                                            append(" — " + listOfNotNull(entry.voidReason, entry.givenByName).mapNotNull { it.nonEmpty }.joinToString(" · "))
                                        }
                                    },
                                    style = Theme.text(14),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    ticking?.let { item ->
        AppSheet(onDismissRequest = { ticking = null }) { HospTickSheet(model = model, detail = detail, order = item.order, slot = item.slot) }
    }
    viewing?.let { item ->
        AppSheet(onDismissRequest = { viewing = null }) {
            HospTickViewSheet(model = model, detail = detail, order = item.order, entry = item.entry, canCare = canCare, canManage = canManage)
        }
    }
}

// MARK: - Mark a dose

@Composable
private fun HospTickSheet(model: HospDetailModel, detail: HospDetail, order: HospOrder, slot: HospSheetSlot?) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val status = remember { mutableStateOf<String?>("given") }
    val givenAt = remember { mutableStateOf(Instant.now()) }
    // The order's dose, on appear.
    val dose = remember { mutableStateOf(order.doseAmount?.let { HospFmt.number(it) } ?: "") }
    val unit = remember { mutableStateOf(order.doseUnit ?: "") }
    var reason by remember { mutableStateOf<String?>(null) }
    val initials = remember { mutableStateOf("") }
    val notes = remember { mutableStateOf("") }
    var withVitals by remember { mutableStateOf(false) }
    val vitals = remember { mutableStateOf(HospVitalsDraft()) }
    var invalidVital by remember { mutableStateOf<HospVitalsDraft.Field?>(null) }
    var saving by remember { mutableStateOf(false) }

    val given = status.value != "not_given"

    HospOnChange(initials.value) { value ->
        val next = value.uppercase().take(6)
        if (next != value) initials.value = next
    }
    HospOnChange(vitals.value) { invalidVital = null }

    fun save() {
        val amount = HospFmt.read(dose.value)
        if (!form.check(listOf(
                "reason" to if (!given && reason == null) L("Say why it wasn’t given.") else null,
                "givenAt" to if (givenAt.value.toEpochMilli() - System.currentTimeMillis() > 60_000) L("That time is in the future.") else null,
                "dose" to if (given && amount?.let { it.isNaN() || it <= 0 } == true) L("Enter a dose greater than zero.") else null,
            ))) return
        var vitalsBody: Map<String, Any>? = null
        if (withVitals) {
            val parsed = vitals.value.parsed()
            val problem = parsed.exceptionOrNull() as? HospVitalsError
            if (problem != null) {
                invalidVital = problem.field
                Toast.error(LF("Check %@.", HospVitalsDraft.label(problem.field)))
                return
            }
            vitalsBody = parsed.getOrNull()
        }
        saving = true
        scope.launch {
            try {
                model.write("/administrations", body = mapOf(
                    "orderId" to order.id,
                    "scheduledFor" to slot?.scheduledFor,
                    "status" to if (given) "given" else "not_given",
                    "givenAt" to DateText.iso(givenAt.value),
                    "doseAmount" to if (given) amount else null,
                    "doseUnit" to if (given) unit.value.nonEmpty else null,
                    "notGivenReason" to if (given) null else reason,
                    "initials" to initials.value.nonEmpty,
                    "notes" to notes.value.nonEmpty,
                    "vitals" to vitalsBody,
                ))
                val name = order.name.nonEmpty ?: L("Dose")
                Toast.show(if (given) LF("%@ ticked as given.", name) else LF("%@ marked as not given.", name))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(hospError(e, "Could not record it."))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = order.name.nonEmpty ?: L("Dose"),
        subtitle = slot?.let { LF("Scheduled %@", HospFmt.dateTime(it.scheduledFor, detail.zone)) } ?: (if (order.scheduleKind == "prn") L("As needed") else L("Off schedule")),
        systemImage = "syringe",
        accent = ModalAccent.lime,
        isDirty = notes.value.isNotEmpty() || initials.value.isNotEmpty() || reason != null,
        form = form,
        primaryTitle = if (given) L("Record as given") else L("Record as not given"),
        isWorking = saving,
        primaryIcon = "syringe",
        onPrimary = { save() },
    ) {
        Text(
            listOfNotNull(HospFmt.orderLine(order).nonEmpty, HospFmt.schedule(order, detail.zone).nonEmpty, order.instructions.nonEmpty).joinToString("\n"),
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.aqua, 16.0), RoundedCornerShape(Theme.chipRadius))
                .padding(12.dp),
            style = Theme.text(14),
            color = Theme.inkStrong,
        )
        PillPicker(options = listOf("given" to L("Given"), "not_given" to L("Not given")), choice = status, modifier = Modifier.hospLabel(L("Given or not")))
        if (!given) {
            // Web: the "Why *" part, its choice under the title.
            ModalSection(title = L("Why"), required = true) {
                Column(Modifier.fillMaxWidth().formScrollTarget("reason", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillPicker(
                        options = HospWords.reasons.map { it to HospWords.notGiven(it) },
                        choice = stateOf({ reason }, { reason = it; form.clear("reason") }),
                        modifier = Modifier.hospLabel(L("Why")),
                    )
                    form.error("reason")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
                }
            }
        }
        DateTimeField(label = if (given) L("Given at") else L("When"), date = givenAt, zone = detail.zone, max = Instant.now(), key = "givenAt", form = form)
        if (given) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                PMTextField(L("Dose"), text = dose, key = "dose", form = form, keyboard = KeyboardType.Decimal, number = PMTextField.Number.plain, modifier = Modifier.weight(1f))
                PMTextField(L("Unit"), text = unit, prompt = "mg, ml, tab", modifier = Modifier.weight(1f))
            }
        }
        PMTextField(L("Initials"), text = initials, prompt = L("Yours by default"), capitalization = KeyboardCapitalization.Characters)
        PMTextField(
            L("Note"), text = notes,
            prompt = if (given) L("e.g. Took it with food") else L("e.g. Vomited an hour before; try again at the next dose"),
            multiline = true,
        )
        val item = order.catalogItem
        if (item != null && order.chargeable == true && given) {
            HospHint(text = LF("Goes on the bill (%d × %@) and leaves the shelf.", order.chargeQuantity ?: 1, item.name ?: ""))
        }
        // Web: a box that opens on "Add vitals ⌄", the fields under a hairline.
        val turn by animateFloatAsState(if (withVitals) 180f else 0f, tween(180, easing = EaseOut), label = "vitals")
        Column(Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { selected = withVitals }
                    .plainButton { withVitals = !withVitals }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Add vitals"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                Spacer(Modifier.weight(1f))
                Glyph("chevron-down", 16.dp, tint = Theme.inkSoft, modifier = Modifier.rotate(turn))
            }
            AnimatedVisibility(
                visible = withVitals,
                enter = expandVertically(tween(180, easing = EaseOut)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180, easing = EaseOut)) + fadeOut(tween(180)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    RowDivider()
                    HospVitalsFields(draft = vitals, invalid = invalidVital, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

// MARK: - A marked dose

@Composable
private fun HospTickViewSheet(
    model: HospDetailModel,
    detail: HospDetail,
    order: HospOrder,
    entry: HospAdministration,
    canCare: Boolean,
    canManage: Boolean,
) {
    val dismiss = LocalDismiss.current
    var striking by remember { mutableStateOf(false) }
    val canStrike = canCare && detail.isAdmitted && entry.voidedAt == null

    ProviderModal(
        title = order.name.nonEmpty ?: L("Dose"),
        subtitle = entry.scheduledFor?.let { LF("Scheduled %@", HospFmt.dateTime(it, detail.zone)) } ?: (if (order.scheduleKind == "prn") L("As needed") else L("Off schedule")),
        systemImage = "syringe",
        accent = ModalAccent.lime,
        cancelTitle = L("Close"),
        // Web: Close, and "Strike out" written in red beside it.
        secondaryActions = if (canStrike) listOf(ModalAction(L("Strike out"), icon = "undo-2", tint = Theme.destructive) { striking = true }) else emptyList(),
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        FactRow(label = L("Status"), value = if (entry.status == "given") L("Given") else "${L("Not given")} · ${HospWords.notGiven(entry.notGivenReason)}")
        FactRow(label = L("At"), value = HospFmt.dateTime(entry.givenAt, detail.zone))
        FactRow(label = L("Dose"), value = HospFmt.dose(entry.doseAmount, entry.doseUnit).nonEmpty ?: "—")
        FactRow(
            label = L("By"),
            value = listOfNotNull(entry.givenByName, entry.initials?.let { "($it)" }).mapNotNull { it.nonEmpty }.joinToString(" ").nonEmpty ?: "—",
        )
        entry.notes.nonEmpty?.let { FactRow(label = L("Note"), value = it) }
        if (!canManage) HospHint(text = L("Only the person who ticked it, or a vet, can strike it out."))
    }
    if (striking) {
        AppSheet(onDismissRequest = { striking = false }) {
            ConfirmSheet(
                title = L("Strike this tick out?"),
                message = L("It stays on record, crossed out with your reason. Its charge comes off the open bill and the stock goes back."),
                confirmTitle = L("Strike out"),
                destructive = true,
                askReason = true,
                reasonRequired = true,
                cancelTitle = L("Keep it"),
            ) { reason ->
                try {
                    model.write("/administrations/${entry.id}/void", body = mapOf("reason" to (reason ?: "")))
                    Toast.show(L("Struck out."))
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw hospRefusal(e, "Could not strike it out.")
                }
            }
        }
    }
}

/** A dose off schedule on the sheet (web: the dashed blue cell). */
@Composable
private fun HospExtraCell(time: String, word: String, action: () -> Unit) {
    val shape = RoundedCornerShape(Theme.chipRadius)
    Column(
        Modifier
            .defaultMinSize(minWidth = 68.dp, minHeight = 48.dp)
            .background(Theme.wash(Theme.blue, 16.0), shape)
            .dashedBorder(2.dp, Theme.blue, Theme.chipRadius, on = 4.dp, off = 3.dp)
            .plainButton(onClick = action)
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(time, style = Theme.heading(14), color = Theme.inkStrong)
        FittedText(word.uppercase(), style = brandText(9.9, FontWeight.ExtraBold), color = Theme.inkSoft, minimumScaleFactor = 0.7f)
    }
}
