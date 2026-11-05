package com.pawmiaw.business.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.time.Instant

//
//  HospitalOrdersTab.kt (iOS HospitalOrdersTab.swift)
//
//  The treatment plan (web hospital-orders-tab.tsx): every order, what it
//  says and who wrote or changed it. Vets add, change (each change kept with
//  its reason and the revision it was made on) and stop orders; each order
//  becomes rows on the sheet. An order with doses on the sheet keeps its
//  schedule (`order_has_doses`): stop it and write a new one.
//

private sealed class HospOrderEditing {
    data object new : HospOrderEditing()
    data class change(val value: HospOrder) : HospOrderEditing()

    val id: String get() = if (this is change) value.id else "new"
    val order: HospOrder? get() = (this as? change)?.value
}

@Composable
fun HospOrdersTab(model: HospDetailModel, detail: HospDetail, canManage: Boolean, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<HospOrderEditing?>(null) }
    var stopping by remember { mutableStateOf<HospOrder?>(null) }
    var history by remember { mutableStateOf<HospOrder?>(null) }

    val active = detail.allOrders.filter { !it.isStopped }
    val stopped = detail.allOrders.filter { it.isStopped }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (canManage && detail.isAdmitted) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                PMButton(L("Add an order"), systemImage = "plus", size = ButtonSize.compact) { editing = HospOrderEditing.new }
            }
        }
        if (detail.allOrders.isEmpty()) {
            EmptyState(
                systemImage = "clipboard-list", title = L("No orders yet"),
                message = if (detail.isAdmitted) L("Medicines, fluids, feeding and checks: each becomes rows on the sheet.") else L("Orders are written once the patient is admitted."),
                accent = Theme.pink, panel = true,
            )
        }
        listOf(L("Active") to active, L("Stopped") to stopped).forEach { (title, list) ->
            if (list.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = Theme.heading(18), color = Theme.inkStrong)
                        Text(
                            "${list.size}",
                            Modifier.background(Theme.surfaceMuted, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
                            style = Theme.text(12, FontWeight.Bold).monospacedDigit(),
                            color = Theme.inkStrong,
                        )
                    }
                    Card(padding = 0.dp, spacing = 0.dp) {
                        list.forEachIndexed { index, order ->
                            if (index > 0) RowDivider()
                            HospOrderRow(
                                order = order, detail = detail, canManage = canManage,
                                onHistory = { history = order },
                                onChange = { editing = HospOrderEditing.change(order) },
                                onStop = { stopping = order },
                            )
                        }
                    }
                }
            }
        }
    }
    editing?.let { which ->
        AppSheet(onDismissRequest = { editing = null }) { HospOrderSheet(model = model, detail = detail, order = which.order) }
    }
    history?.let { order ->
        AppSheet(onDismissRequest = { history = null }) { HospOrderHistorySheet(detail = detail, order = order) }
    }
    stopping?.let { order ->
        AppSheet(onDismissRequest = { stopping = null }) {
            ConfirmSheet(
                title = LF("Stop %@?", order.name.nonEmpty ?: L("this order")),
                message = L("No more doses of it come due. What was already given stays on the sheet."),
                confirmTitle = L("Stop order"),
                destructive = true,
                askReason = true,
                cancelTitle = L("Keep it"),
            ) { reason ->
                try {
                    model.write("/orders/${order.id}/stop", body = mapOf("reason" to reason))
                    Toast.show(L("Order stopped."))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw hospRefusal(e, "Could not stop it.")
                }
            }
        }
    }
}

@Composable
private fun HospOrderRow(
    order: HospOrder,
    detail: HospDetail,
    canManage: Boolean,
    onHistory: () -> Unit,
    onChange: () -> Unit,
    onStop: () -> Unit,
) {
    val zone = detail.zone
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (order.isStopped) 0.7f else 1f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(HospWords.kind(order.kind).uppercase(), style = brandText(10.4, FontWeight.ExtraBold).tracking(1.04), color = Theme.blue)
            Text(
                order.name.nonEmpty ?: L("Order"),
                style = Theme.text(16, FontWeight.Bold).copy(textDecoration = if (order.isStopped) TextDecoration.LineThrough else null),
                color = Theme.inkStrong,
            )
            val line = listOfNotNull(HospFmt.orderLine(order).nonEmpty, HospFmt.schedule(order, zone).nonEmpty).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = Theme.text(14), color = Theme.inkSoft)
            Text(
                listOfNotNull(
                    LF("From %@", HospFmt.dateTime(order.startsAt, zone)),
                    order.endsAt?.let { LF("until %@", HospFmt.dateTime(it, zone)) },
                    order.catalogItem?.let { item -> if (order.chargeable == true) LF("charges %d × %@", order.chargeQuantity ?: 1, item.name ?: "") else null },
                ).joinToString(" · "),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
            order.instructions.nonEmpty?.let { instructions ->
                Text(instructions, Modifier.padding(top = 2.dp), style = Theme.text(14).copy(fontStyle = FontStyle.Italic), color = Theme.inkSoft)
            }
            if (order.isStopped) {
                Text(
                    LF("Stopped %@", order.stoppedAt?.let { HospFmt.dateTime(it, zone) } ?: "") + (order.stopReason.nonEmpty?.let { " — $it" } ?: ""),
                    Modifier.padding(top = 2.dp),
                    style = Theme.text(12, FontWeight.Bold),
                    color = Theme.inkSoft,
                )
            }
        }
        FlowLayout(spacing = 6.dp) {
            PMButton(L("History"), systemImage = "history", variant = ButtonVariant.outline, size = ButtonSize.compact) { onHistory() }
            if (canManage && detail.isAdmitted && !order.isStopped) {
                PMButton(L("Change"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact) { onChange() }
                PMButton(L("Stop"), systemImage = "square", variant = ButtonVariant.destructive, size = ButtonSize.compact) { onStop() }
            }
        }
    }
}

// MARK: - Write or change an order

@Composable
private fun HospOrderSheet(model: HospDetailModel, detail: HospDetail, order: HospOrder?) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    // Filled once from the order being changed (iOS `fill()` on appear).
    val kind = remember { mutableStateOf<String?>(order?.kind ?: "medication") }
    var item by remember { mutableStateOf(order?.catalogItem) }
    val label = remember { mutableStateOf(order?.label ?: "") }
    val dose = remember { mutableStateOf(order?.doseAmount?.let { HospFmt.number(it) } ?: "") }
    val unit = remember { mutableStateOf(order?.doseUnit ?: "") }
    val route = remember { mutableStateOf(order?.route ?: "") }
    val rate = remember { mutableStateOf(order?.rateMlPerHour?.let { HospFmt.number(it) } ?: "") }
    val scheduleKind = remember { mutableStateOf<String?>(order?.scheduleKind ?: "times") }
    val times = remember { mutableStateOf(if (order != null) (order.scheduleTimes ?: emptyList()).joinToString(", ") else "08:00, 20:00") }
    val everyHours = remember { mutableStateOf(order?.everyHours?.toString() ?: "8") }
    val onceAt = remember { mutableStateOf(DateText.parse(order?.onceAt) ?: Instant.now()) }
    val startsAt = remember { mutableStateOf(DateText.parse(order?.startsAt) ?: Instant.now()) }
    val endsAt = remember { mutableStateOf(if (order != null) DateText.parse(order.endsAt) else null) }
    val chargeable = remember { mutableStateOf(order?.chargeable ?: true) }
    val chargeQuantity = remember { mutableStateOf(if (order != null) "${order.chargeQuantity ?: 1}" else "1") }
    val instructions = remember { mutableStateOf(order?.instructions ?: "") }
    val reason = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        val doseValue = HospFmt.read(dose.value)
        val rateValue = HospFmt.read(rate.value)
        val parsedTimes = HospFmt.parseTimesOfDay(times.value)
        val every = everyHours.value.toIntOrNull()
        val quantity = chargeQuantity.value.toIntOrNull()
        val chosen = item
        if (!form.check(listOf(
                "label" to if (label.value.nonEmpty == null && chosen == null) L("Name the order.") else null,
                "dose" to if (doseValue?.let { it.isNaN() || it <= 0 } == true) L("Enter a dose greater than zero.")
                else if (kind.value == "medication" && doseValue == null) L("A medicine needs its dose and unit.") else null,
                "unit" to if (kind.value == "medication" && unit.value.nonEmpty == null) L("A medicine needs its dose and unit.") else null,
                "rate" to if (rateValue?.let { it.isNaN() || it <= 0 || it > 5000 } == true) L("Enter the rate in ml/h, up to 5000.")
                else if (kind.value == "fluid" && rateValue == null) L("Fluids need their rate (ml/h).") else null,
                "times" to if (scheduleKind.value == "times") {
                    if ((parsedTimes ?: emptyList()).isEmpty()) L("Write the times of day, like 08:00, 20:00.")
                    else if ((parsedTimes?.size ?: 0) > 24) L("At most 24 times a day.") else null
                } else null,
                "every" to if (scheduleKind.value == "every" && !(every?.let { it in 1..72 } ?: false)) L("Every 1 to 72 hours.") else null,
                "ends" to if (endsAt.value?.let { !it.isAfter(startsAt.value) } == true) L("It ends after it starts.") else null,
                "quantity" to if (chosen != null && chargeable.value && !(quantity?.let { it in 1..1000 } ?: false)) L("Charge between 1 and 1000 per dose.") else null,
            ))) return

        val input: Map<String, Any?> = mapOf(
            "kind" to (kind.value ?: "medication"),
            "label" to label.value.nonEmpty,
            "catalogItemId" to chosen?.id,
            "doseAmount" to doseValue,
            "doseUnit" to unit.value.nonEmpty,
            "route" to route.value.nonEmpty,
            "rateMlPerHour" to rateValue,
            "scheduleKind" to (scheduleKind.value ?: "times"),
            "scheduleTimes" to if (scheduleKind.value == "times") parsedTimes else null,
            "everyHours" to if (scheduleKind.value == "every") every else null,
            "onceAt" to if (scheduleKind.value == "once") DateText.iso(onceAt.value) else null,
            "startsAt" to DateText.iso(startsAt.value),
            "endsAt" to endsAt.value?.let { DateText.iso(it) },
            "chargeable" to (chosen != null && chargeable.value),
            "chargeQuantity" to if (chosen != null && chargeable.value) (quantity ?: 1) else 1,
            "instructions" to instructions.value.nonEmpty,
        )
        saving = true
        scope.launch {
            try {
                if (order != null) {
                    val changes = hospOrderChanges(order, input)
                    if (changes.isEmpty()) {
                        dismiss()
                        return@launch
                    }
                    changes.hospSet("reason", reason.value.nonEmpty)
                    changes.hospSet("expectedRevision", order.revision)
                    model.write("/orders/${order.id}", method = "PATCH", body = changes)
                    Toast.show(L("Order changed."))
                } else {
                    model.write("/orders", body = input)
                    Toast.show(L("Order added. Its doses are on the sheet."))
                }
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if ((e as? APIError)?.code == "stale_order") {
                    model.load()
                    dismiss()
                }
                Toast.error(hospError(e, "Could not save the order."))
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = order?.let { LF("Change %@", it.name.nonEmpty ?: L("the order")) } ?: L("New order"),
        subtitle = order?.let { LF("Revision %d. The change is kept with your name and reason.", it.revision ?: 1) } ?: L("Each dose shows up on the sheet to tick."),
        systemImage = "package",
        accent = ModalAccent.aqua,
        // iOS `filled && …`: the fields are filled from the order before the first frame.
        isDirty = (label.value.isNotEmpty() || item != null || instructions.value.isNotEmpty() || reason.value.isNotEmpty()),
        form = form,
        primaryTitle = if (order == null) L("Add order") else L("Save change"),
        isWorking = saving,
        primaryIcon = "save",
        onPrimary = { save() },
    ) {
        ModalSection(title = L("What")) {
            PillPicker(options = HospWords.kinds.map { it to HospWords.kind(it) }, choice = kind, modifier = Modifier.hospLabel(L("Kind")))
            val chosen = item
            if (chosen != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.aqua, 14.0), RoundedCornerShape(Theme.chipRadius))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(chosen.name ?: "", style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                        HospHint(text = L("From the Catalog: charged and taken from stock when given"))
                    }
                    IconCircleButton(systemImage = "x", label = L("Remove item")) { item = null }
                }
            } else {
                HospCatalogSearch(
                    prompt = L("Search the Catalog: medicines, supplies, services"),
                    emptyText = L("Nothing in the Catalog by that name. Name the order below: it won’t be charged."),
                ) { entry ->
                    item = HospCatalogRef(id = entry.id, name = entry.name, itemType = entry.itemType, unitAmount = entry.defaultUnitAmount)
                }
            }
            PMTextField(
                L("Name on the sheet"), text = label, key = "label", form = form,
                prompt = item?.name ?: L("e.g. Maropitant, Lactated Ringer’s, Walk and check the wound"), required = item == null,
            )
        }
        ModalSection(title = L("How much and how")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                PMTextField(
                    L("Dose"), text = dose, key = "dose", form = form, keyboard = KeyboardType.Decimal,
                    required = kind.value == "medication", number = PMTextField.Number.plain, modifier = Modifier.weight(1f),
                )
                PMTextField(L("Unit"), text = unit, key = "unit", form = form, prompt = "mg, ml, tab", required = kind.value == "medication", modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                MenuField(
                    label = L("Route"),
                    options = listOf("" to "—") + HospWords.routes.map { it to HospWords.route(it) },
                    selection = route,
                    modifier = Modifier.weight(1f),
                )
                PMTextField(
                    L("Rate (ml/h)"), text = rate, key = "rate", form = form, prompt = if (kind.value == "fluid") "40" else null,
                    keyboard = KeyboardType.Decimal, required = kind.value == "fluid", modifier = Modifier.weight(1f),
                )
            }
        }
        ModalSection(title = L("When"), required = true) {
            PillPicker(options = HospWords.schedules.map { it to HospWords.schedule(it) }, choice = scheduleKind, modifier = Modifier.hospLabel(L("Schedule")))
            when (scheduleKind.value) {
                "times" -> {
                    val parsed = HospFmt.parseTimesOfDay(times.value)
                    PMTextField(
                        LF("Times of day (%@)", detail.zone.id), text = times, key = "times", form = form, prompt = "08:00, 14:00, 20:00",
                        hint = parsed?.let { list -> if (list.isEmpty()) null else list.joinToString(" · ") { HospFmt.timeOfDay(it) } }
                            ?: L("24-hour times or with a.m. / p.m., separated by commas."),
                        // iOS `.numbersAndPunctuation`: digits, the colon and a.m. / p.m.
                        keyboard = KeyboardType.Ascii,
                        required = true,
                    )
                }
                "every" -> PMTextField(L("Every (hours)"), text = everyHours, key = "every", form = form, hint = L("hours, from the start"), keyboard = KeyboardType.Number)
                "once" -> DateTimeField(label = L("At"), date = onceAt, zone = detail.zone, required = true)
                else -> HospHint(text = L("No set times: it is given with “Give now” when needed."))
            }
            DateTimeField(label = L("Starts"), date = startsAt, zone = detail.zone, required = true)
            OptionalDateField(
                label = L("Ends (optional)"), date = endsAt, placeholder = L("No end date"), zone = detail.zone, min = startsAt.value,
                components = setOf(DatePickerComponent.date, DatePickerComponent.hourAndMinute), defaultDate = startsAt.value.plusSeconds(86_400),
            )
            form.error("ends")?.let { message ->
                Text(message, Modifier.formScrollTarget("ends", form), style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
            }
        }
        if (item != null) {
            ToggleRow(title = L("Charge each dose"), subtitle = L("A line on the running bill for every dose given."), isOn = chargeable)
            if (chargeable.value) {
                PMTextField(L("Quantity per dose"), text = chargeQuantity, key = "quantity", form = form, keyboard = KeyboardType.Number)
            }
        }
        PMTextField(L("Instructions"), text = instructions, prompt = L("e.g. Give slowly over 2 minutes; hold if vomiting"), multiline = true)
        if (order != null) {
            PMTextField(L("Why it changes"), text = reason, prompt = L("e.g. Dose adjusted to the new weight"))
        }
    }
}

/** Only what changed (the server keeps each change as an event). A value that became empty is sent as null. */
private fun hospOrderChanges(order: HospOrder, next: Map<String, Any?>): MutableMap<String, Any?> {
    val before: Map<String, Any?> = mapOf(
        "kind" to order.kind, "label" to order.label, "catalogItemId" to order.catalogItem?.id, "doseAmount" to order.doseAmount, "doseUnit" to order.doseUnit,
        "route" to order.route, "rateMlPerHour" to order.rateMlPerHour, "scheduleKind" to order.scheduleKind,
        "scheduleTimes" to if (order.scheduleKind == "times") order.scheduleTimes else null,
        "everyHours" to if (order.scheduleKind == "every") order.everyHours else null,
        "onceAt" to if (order.scheduleKind == "once") order.onceAt else null,
        "startsAt" to order.startsAt, "endsAt" to order.endsAt, "chargeable" to order.chargeable, "chargeQuantity" to order.chargeQuantity,
        "instructions" to order.instructions,
    )
    val out = mutableMapOf<String, Any?>()
    for ((key, value) in next) {
        val previous = before[key]
        val same = if (key in listOf("startsAt", "endsAt", "onceAt")) {
            val a = (previous as? String)?.let { DateText.parse(it) }?.let { it.toEpochMilli() / 60_000 }
            val b = (value as? String)?.let { DateText.parse(it) }?.let { it.toEpochMilli() / 60_000 }
            a == b
        } else {
            hospDescribe(previous) == hospDescribe(value)
        }
        if (!same) out[key] = value
    }
    return out
}

private fun hospDescribe(value: Any?): String = when (value) {
    null -> "null"
    is Double -> HospFmt.number(value)
    is Int -> value.toString()
    is Boolean -> if (value) "true" else "false"
    is List<*> -> value.joinToString(",") { it?.toString() ?: "" }
    is String -> value
    else -> value.toString()
}

// MARK: - An order's history

@Composable
private fun HospOrderHistorySheet(detail: HospDetail, order: HospOrder) {
    var events by remember { mutableStateOf<List<HospOrderEvent>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            val answer: HospOrderEvents = API.get(HospAPI.detailPath(detail.id) + "/orders/${order.id}/events")
            events = answer.events ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = errorText(e)
        }
    }

    LaunchedEffect(Unit) { load() }

    ProviderModal(
        title = order.name.nonEmpty ?: L("Order"),
        subtitle = L("Who wrote, changed or stopped it, and why"),
        systemImage = "history",
        accent = ModalAccent.aqua,
        cancelTitle = L("Close"),
    ) {
        val loaded = events
        val failed = error
        if (loaded != null) {
            if (loaded.isEmpty()) {
                EmptyState(systemImage = "history", title = L("No history yet."), accent = Theme.pink)
            } else {
                loaded.forEach { event -> HospOrderEventRow(event, detail) }
            }
        } else if (failed != null) {
            ErrorState(message = failed) { load() }
        } else {
            LoadingState()
        }
        StitchedDivider()
        FactRow(label = L("Written by"), value = order.createdByName ?: "—")
        FactRow(label = L("Last changed by"), value = order.updatedByName ?: "—")
    }
}

@Composable
private fun HospOrderEventRow(event: HospOrderEvent, detail: HospDetail) {
    val strong = Theme.inkStrong
    val soft = Theme.inkSoft
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Theme.borderSoft, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val action = if (event.action == "created") L("Written") else if (event.action == "stopped") L("Stopped") else L("Changed")
        Text(buildAnnotatedString {
            withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle().copy(color = strong)) { append(action) }
            withStyle(Theme.text(14).toSpanStyle().copy(color = soft)) { append(" · ${event.actorName ?: L("Someone")} · ${HospFmt.dateTime(event.createdAt, detail.zone)}") }
        }, style = Theme.text(14))
        val changes = event.changes.obj
        if (event.action == "changed" && changes != null) {
            changes.keys.sorted().forEach { field ->
                val change = changes[field]
                Text(buildAnnotatedString {
                    withStyle(Theme.text(14, FontWeight.SemiBold).toSpanStyle().copy(color = strong)) { append("${hospFieldLabel(field)}: ") }
                    withStyle(Theme.text(14).toSpanStyle().copy(color = soft, textDecoration = TextDecoration.LineThrough)) { append(hospShow(change["from"], detail)) }
                    withStyle(SpanStyle(color = soft)) { append(" → ") }
                    withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle().copy(color = strong)) { append(hospShow(change["to"], detail)) }
                }, style = Theme.text(14))
            }
        }
        event.reason.nonEmpty?.let { reason ->
            Text("“$reason”", style = Theme.text(14).copy(fontStyle = FontStyle.Italic), color = soft)
        }
    }
}

private fun hospFieldLabel(field: String): String = when (field) {
    "kind" -> L("Kind")
    "label" -> L("Name")
    "catalogItemId" -> L("Catalog item")
    "doseAmount" -> L("Dose")
    "doseUnit" -> L("Unit")
    "route" -> L("Route")
    "rateMlPerHour" -> L("Rate (ml/h)")
    "scheduleKind" -> L("Schedule")
    "scheduleTimes" -> L("Times of day")
    "everyHours" -> L("Every (h)")
    "onceAt" -> L("At")
    "startsAt" -> L("Starts")
    "endsAt" -> L("Ends")
    "chargeable" -> L("Charged")
    "chargeQuantity" -> L("Quantity per dose")
    "instructions" -> L("Instructions")
    else -> field
}

private fun hospShow(value: JSONValue?, detail: HospDetail): String {
    if (value == null || value.isNull) return "—"
    value.array?.let { list -> return list.joinToString(", ") { entry -> entry.string?.let { HospFmt.timeOfDay(it) } ?: "" } }
    value.bool?.let { flag -> return if (flag) L("Yes") else L("No") }
    // Swift `JSONValue.string` says a number the way the reader writes it (`formatted()`).
    if (value is JsonPrimitive && !value.isString) {
        value.double?.let { number ->
            return NumberFormat.getNumberInstance(appLocale).apply { maximumFractionDigits = 6 }.format(number)
        }
    }
    value.string?.let { text ->
        if (text.length > 10 && text.drop(10).startsWith("T") && DateText.parse(text) != null) return HospFmt.dateTime(text, detail.zone)
        return if (text.isEmpty()) "—" else text
    }
    return "—"
}
