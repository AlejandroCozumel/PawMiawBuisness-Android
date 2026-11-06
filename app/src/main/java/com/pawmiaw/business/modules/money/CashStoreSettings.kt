package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

//
//  CashStoreSettings.kt (iOS CashStoreSettings.swift)
//
//  The Store's settings (web: store/store-settings.tsx): currency and tax,
//  receipts, the register's alert (read here, set in Settings), missed-visit
//  fees, what families see, and a clinic's emergency deposit. Whoever manages
//  billing (`billing.manage`) changes them; everyone else reads them. A
//  clinic also chooses the service added to a visit checked in after hours.
//

private val storeCurrencies = listOf("MXN", "USD", "COP", "EUR", "ARS", "CLP", "PEN", "GTQ", "CRC")

private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}

/** SwiftUI `ViewThatFits(in: .horizontal)`: the first that fits the width, else the second. */
@Composable
private fun ViewThatFits(modifier: Modifier = Modifier, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val probe = subcompose("probe", first).map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val fits = (probe.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth
        val placeables = subcompose(if (fits) "first" else "second", if (fits) first else second).map { it.measure(constraints.copy(minHeight = 0)) }
        val width = max(constraints.minWidth, placeables.maxOfOrNull { it.width } ?: 0)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** "16" for 1600, "16.50" for 1650. */
private fun storePercent(bps: Int): String {
    val value = bps / 100.0
    return if (Math.rint(value) == value) "${value.toInt()}" else String.format(Locale.US, "%.2f", value)
}

@Composable
fun CashStoreSettingsTab(settings: CashStoreSettings, onSaved: (CashStoreSettings) -> Unit, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var draft by remember { mutableStateOf<CashStoreSettings?>(null) }
    val currency = remember { mutableStateOf("") }
    val rate = remember { mutableStateOf("") }
    val footer = remember { mutableStateOf("") }
    val feeOn = remember { mutableStateOf(false) }
    val lateOn = remember { mutableStateOf(false) }
    val lateHours = remember { mutableStateOf("") }
    val depositOn = remember { mutableStateOf(false) }
    val depositPercent = remember { mutableStateOf("") }
    val emergencyOn = remember { mutableStateOf(false) }
    val surchargeId = remember { mutableStateOf("") }
    var services by remember { mutableStateOf(listOf<CashCatalogItem>()) }
    var working by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf("") }
    var justSaved by remember { mutableStateOf(false) }

    val canEdit = workspace.can(Permission.billingManage)
    val isClinic = workspace.offers(BusinessCategory.vet, BusinessCategory.emergency)
    val regular = LocalSizeClass.current == SizeClass.regular

    /** What the form holds now, to tell when something changed (the save bar shows then). */
    fun signature(): String = listOf(
        currency.value, rate.value, "${draft?.pricesIncludeTax ?: false}", footer.value, "${draft?.emailReceipts != false}", "${feeOn.value}",
        "${draft?.noShowFeeAmount ?: 0}", "${lateOn.value}", lateHours.value, "${draft?.showPricesToFamilies ?: false}", "${depositOn.value}",
        depositPercent.value, "${emergencyOn.value}", "${draft?.emergencyDepositAmount ?: 0}", surchargeId.value,
    ).joinToString("|")

    val current = signature()
    val dirty = draft != null && current != baseline

    fun start(value: CashStoreSettings) {
        draft = value
        currency.value = value.currency
        rate.value = storePercent(value.defaultTaxRateBps)
        footer.value = value.receiptFooter ?: ""
        feeOn.value = (value.noShowFeeAmount ?: 0) > 0
        lateOn.value = value.lateCancelWindowHours != null
        lateHours.value = value.lateCancelWindowHours?.toString() ?: "24"
        depositOn.value = (value.stayDepositPercent ?: 0) > 0
        depositPercent.value = value.stayDepositPercent?.toString() ?: ""
        emergencyOn.value = (value.emergencyDepositAmount ?: 0) > 0
        surchargeId.value = value.afterHoursSurchargeItemId ?: ""
        baseline = signature()
    }

    fun bind(get: (CashStoreSettings) -> Boolean, fallback: Boolean, set: (CashStoreSettings, Boolean) -> CashStoreSettings): MutableState<Boolean> =
        stateOf({ draft?.let(get) ?: fallback }) { value -> draft = draft?.let { set(it, value) } }

    fun bindOptional(get: (CashStoreSettings) -> Int?, set: (CashStoreSettings, Int?) -> CashStoreSettings): MutableState<Int?> =
        stateOf({ draft?.let(get) }) { value -> draft = draft?.let { set(it, value) } }

    fun save() {
        val base = draft ?: return
        val rateValue = rate.value.replace(",", ".").toDoubleOrNull()
        val hours = lateHours.value.toIntOrNull()
        val deposit = depositPercent.value.toIntOrNull()
        if (!form.check(listOf(
                "currency" to (if (currency.value.length == 3 && currency.value.all { it.isLetter() }) null else L("Use a three-letter currency code")),
                "rate" to (if (rateValue != null && rateValue >= 0 && rateValue <= 100) null else L("Enter a tax rate from 0 to 100.")),
                "fee" to (if (feeOn.value && (base.noShowFeeAmount ?: 0) <= 0) L("Enter the fee for a missed visit.") else null),
                "hours" to (if (feeOn.value && lateOn.value && (hours ?: 0) !in 1..336) L("Enter the hours from 1 to 336.") else null),
                "deposit" to (if (depositOn.value && (deposit ?: 0) !in 1..100) L("Enter a deposit from 1 to 100%.") else null),
                "emergency" to (if (isClinic && emergencyOn.value && (base.emergencyDepositAmount ?: 0) <= 0) L("Enter the emergency deposit.") else null),
            ))) return
        var next = base.copy(
            currency = currency.value.uppercase(),
            defaultTaxRateBps = ((rateValue ?: 0.0) * 100).roundToInt(),
            receiptFooter = footer.value.nonEmpty,
            noShowFeeAmount = if (feeOn.value) base.noShowFeeAmount else null,
            lateCancelWindowHours = if (feeOn.value && lateOn.value) hours else null,
            stayDepositPercent = if (depositOn.value) deposit else null,
            emergencyDepositAmount = if (emergencyOn.value) base.emergencyDepositAmount else null,
        )
        if (isClinic) next = next.copy(afterHoursSurchargeItemId = surchargeId.value.nonEmpty)
        working = true
        scope.launch {
            try {
                val body: Map<String, Any?> = linkedMapOf(
                    "currency" to next.currency,
                    "pricesIncludeTax" to next.pricesIncludeTax,
                    "defaultTaxRateBps" to next.defaultTaxRateBps,
                    "receiptFooter" to next.receiptFooter,
                    "noShowFeeAmount" to next.noShowFeeAmount,
                    "lateCancelWindowHours" to next.lateCancelWindowHours,
                    "cashDifferenceAlertAmount" to next.cashDifferenceAlertAmount,
                    "openingCashDefault" to next.openingCashDefault,
                    "stayDepositPercent" to next.stayDepositPercent,
                    "showPricesToFamilies" to (next.showPricesToFamilies ?: false),
                    "emergencyDepositAmount" to next.emergencyDepositAmount,
                    "afterHoursSurchargeItemId" to next.afterHoursSurchargeItemId,
                    "emailReceipts" to (next.emailReceipts ?: true),
                )
                val saved: CashStoreSettings = API.put("/provider-store/settings", body)
                Toast.show(L("Store settings saved."))
                onSaved(saved)
                start(saved)
                justSaved = true
                announceDataChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (cashErrorCode(error) == "invalid_item") {
                    form.check(listOf("surcharge" to L("That after-hours service is not in your catalog.")))
                    return@launch
                }
                form.apply(error, known = mapOf(
                    "currency" to "currency", "defaultTaxRateBps" to "rate", "noShowFeeAmount" to "fee", "lateCancelWindowHours" to "hours",
                    "stayDepositPercent" to "deposit", "emergencyDepositAmount" to "emergency",
                ))
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(Unit) { start(settings) }
    LaunchedEffect(current) { if (dirty) justSaved = false }
    LaunchedEffect(Unit) {
        if (!isClinic) return@LaunchedEffect
        val page = attempt {
            API.get<Page<CashCatalogItem>>("/provider-catalog", mapOf("limit" to "100", "offset" to "0", "itemType" to "service", "active" to "true"))
        }
        services = (page?.items ?: emptyList()).filter { it.itemType == "service" && it.active }
    }

    // MARK: Panels (web `SettingsPanel`, one card each)

    @Composable
    fun Panel(title: String, subtitle: String, icon: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
        SectionCard(title, subtitle = subtitle, icon = icon, accent = accent) {
            Disabled(!canEdit) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }

    /** A short number with its words after it (web: the hours and the deposit %). */
    @Composable
    fun InlineNumber(text: MutableState<String>, key: String, label: String, after: String) {
        Column(Modifier.formScrollTarget(key, form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldChrome(invalid = form.error(key) != null, modifier = Modifier.width(96.dp)) {
                    PlainTextField(
                        stateOf({ text.value }) { typed ->
                            text.value = typed.filter { it.isDigit() }
                            form.clear(key)
                        },
                        keyboard = KeyboardType.Number,
                        modifier = Modifier.weight(1f).semantics { contentDescription = label },
                    )
                }
                Text(after, style = Theme.text(14), color = Theme.inkSoft)
            }
            form.error(key)?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
        }
    }

    /** Why the currency no longer changes, and who can help if it is wrong (web `CurrencyLockNote`). */
    @Composable
    fun CurrencyLockNote() {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("lock", 16.dp, tint = Theme.inkStrong)
                Text(L("Set with the first bill"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            }
            Text(
                LF("Everything the business charges is in %@: prices, bills, stays, sales and reports. Now that money has been recorded in it, it no longer changes.", settings.currency),
                style = Theme.text(14), color = Theme.inkSoft,
            )
            val blue = Theme.blue
            val help = buildAnnotatedString {
                append(L("If it is the wrong currency, write to"))
                append(" ")
                withLink(LinkAnnotation.Url("mailto:contact@pawmiaw.com", TextLinkStyles(SpanStyle(color = blue)))) { append("contact@pawmiaw.com") }
                append(" ")
                append(L("and we will help you move it."))
            }
            Text(help, style = Theme.text(14), color = Theme.inkSoft)
        }
    }

    @Composable
    fun LeftPanels() {
        Panel(L("Prices and tax"), L("The currency everything is charged in and the tax on what you sell."), icon = "badge-percent", accent = Theme.mango) {
            if (settings.currencyLocked == true) {
                FormField(label = L("Currency")) {
                    FieldChrome {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("lock", 14.dp, tint = Theme.inkSoft)
                            Text(settings.currency, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                        }
                    }
                }
            } else {
                MenuField(
                    label = L("Currency"),
                    options = (listOf(currency.value) + storeCurrencies).distinct().map { it to it },
                    selection = currency, key = "currency", form = form,
                )
            }
            PMTextField(L("Standard tax rate (%)"), text = rate, key = "rate", form = form, hint = L("In Mexico, IVA is 16%."), keyboard = KeyboardType.Decimal)
            if (settings.currencyLocked == true) {
                CurrencyLockNote()
            } else {
                Text(
                    L("The currency is the whole business’s: the Catalog, bills, stays and sales. Changing it relabels prices without converting them, and the first bill fixes it."),
                    style = Theme.text(14), color = Theme.inkSoft,
                )
            }
            Text(L("Everything uses the standard rate unless you mark it zero-rated or exempt in the Catalog."), style = Theme.text(14), color = Theme.inkSoft)
            ToggleRow(
                title = L("Shelf prices include tax"),
                subtitle = if (draft?.pricesIncludeTax == true) L("The price on the shelf is what the customer pays; the receipt shows the tax inside it.") else L("Tax is added on top at the counter."),
                isOn = bind({ it.pricesIncludeTax }, false) { s, v -> s.copy(pricesIncludeTax = v) },
            )
        }
        Panel(L("Receipts"), L("What every receipt says at the end, and whether it is emailed."), icon = "receipt-text", accent = Theme.aqua) {
            PMTextField(
                L("Receipt footer"), text = footer,
                prompt = L("e.g. Thank you! Returns within 7 days with this receipt."),
                hint = L("Printed at the end of every receipt: a thank-you, your returns policy."),
                optional = true, multiline = true,
            )
            ToggleRow(
                title = L("Email receipts to clients"),
                subtitle = if (draft?.emailReceipts != false) L("Every payment sends its receipt, PDF attached, to the client’s email. Clients without an email get none; you can type one when charging.")
                else L("Receipts are only emailed when someone turns it on while charging."),
                isOn = stateOf({ draft?.emailReceipts != false }) { value -> draft = draft?.copy(emailReceipts = value) },
            )
        }
        Panel(L("Register"), L("What happens when a close doesn’t match the count. It is set with the business’s money rules."), icon = "wallet", accent = ProviderState.done.color) {
            val strong = Theme.inkStrong
            val soft = Theme.inkSoft
            val alert = buildAnnotatedString {
                withStyle(Theme.text(14, FontWeight.Bold).toSpanStyle().copy(color = strong)) { append("${L("Cash difference alert")}: ") }
                withStyle(Theme.text(14).toSpanStyle().copy(color = soft)) {
                    append(
                        if ((settings.cashDifferenceAlertAmount ?: 0) > 0) LF("a register close off by more than %@ needs a note, and the owners are told.", cashMoney(settings.cashDifferenceAlertAmount, settings.currency))
                        else L("off."),
                    )
                }
            }
            Text(alert, style = Theme.text(14))
            // iOS NavigationLink(value: .section(.settings)); like every control here, off for who only reads.
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .plainButton(label = L("Change it in Settings → Region and money")) { nav.push(ProviderRoute.section(ProviderSection.settings)) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(L("Change it in Settings → Region and money"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
            }
        }
    }

    @Composable
    fun RightPanels() {
        Panel(L("Missed visits"), L("A fee for a no-show, and for cancelling too late."), icon = "calendar-x-2", accent = Theme.pink) {
            ToggleRow(title = L("Fee for a missed visit"), subtitle = L("Offered when someone marks a no-show. Whoever marks it decides to charge or waive it, with a reason."), isOn = feeOn)
            if (feeOn.value) {
                MoneyField(
                    label = LF("Fee (%@)", currency.value.nonEmpty ?: settings.currency),
                    cents = bindOptional({ it.noShowFeeAmount }) { s, v -> s.copy(noShowFeeAmount = v) },
                    key = "fee", form = form,
                )
                // The family sees the business's terms (docs/family-money.md §5): before booking and on each visit.
                Text(L("Families see this fee, and until when they can cancel without it, when they book and on every visit."), style = Theme.text(12), color = Theme.inkSoft)
                ToggleRow(
                    title = L("Late cancellations too"),
                    subtitle = if (lateOn.value) L("Cancelling close to the visit is offered the same fee.") else L("Only no-shows are offered the fee."),
                    isOn = lateOn,
                )
                if (lateOn.value) {
                    InlineNumber(lateHours, key = "hours", label = L("Hours before the visit"), after = L("hours or less before the visit"))
                    Text(L("Families who book online cancel by themselves until this window; it is also shown in Availability."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
        Panel(L("Families in the app"), L("What families see about prices when they book or ask for a stay."), icon = "smartphone", accent = Theme.lavender) {
            ToggleRow(
                title = L("Show prices when they book"),
                subtitle = L("Each service shows its Catalog price next to its length. Off: families see only the service and how long it takes."),
                isOn = stateOf({ draft?.showPricesToFamilies ?: false }) { value -> draft = draft?.copy(showPricesToFamilies = value) },
            )
            ToggleRow(
                title = L("Ask for a deposit on stays"),
                subtitle = L("Families asking for a hotel or daycare stay are told before they send it. You take the deposit yourself once you confirm."),
                isOn = depositOn,
            )
            if (depositOn.value) {
                InlineNumber(depositPercent, key = "deposit", label = L("Deposit (%)"), after = L("% of the stay’s price"))
            }
        }
        if (isClinic) {
            Panel(L("Emergencies"), L("The deposit an emergency starts with, and the charge for coming after hours."), icon = "heart-pulse", accent = Theme.coral) {
                ToggleRow(
                    title = L("Emergency deposit"),
                    subtitle = L("A triaged emergency starts treatment once it is paid. A critical patient never waits for it; owners and admins can start anyway, with a reason."),
                    isOn = emergencyOn,
                )
                if (emergencyOn.value) {
                    MoneyField(
                        label = LF("Deposit (%@)", settings.currency),
                        cents = bindOptional({ it.emergencyDepositAmount }) { s, v -> s.copy(emergencyDepositAmount = v) },
                        key = "emergency", form = form,
                    )
                }
                val chosen = surchargeId.value
                MenuField(
                    label = L("After-hours surcharge"),
                    options = listOf("" to L("No surcharge")) +
                        services.map { it.id to "${it.name} · ${cashMoney(it.defaultUnitAmount, it.currency)}" } +
                        (if (chosen.isEmpty() || services.any { it.id == chosen }) emptyList() else listOf(chosen to L("The current service"))),
                    selection = surchargeId, key = "surcharge", form = form,
                )
                Text(L("A catalog service added once to a visit checked in outside the location’s hours."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }

    // MARK: The save bar

    @Composable
    fun BarState() {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (dirty) Theme.mango else ProviderState.done.color, CircleShape))
            Text(if (dirty) L("Unsaved changes") else L("Saved"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        }
    }

    @Composable
    fun BarButtons() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PMButton(L("Discard"), variant = ButtonVariant.outline, enabled = dirty && !working) {
                start(settings)
                form.errors = emptyMap()
            }
            PMButton(if (working) L("Saving…") else L("Save"), systemImage = "save", isWorking = working, enabled = dirty) { save() }
        }
    }

    /** Saving shows up when there is something to save, with a way back (web: the store settings' save bar). */
    @Composable
    fun SaveBar() {
        Card(padding = 16.dp, tint = if (dirty) Theme.mango else null) {
            ViewThatFits(
                Modifier.fillMaxWidth(),
                first = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BarState()
                        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                        BarButtons()
                    }
                },
                second = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BarState()
                        BarButtons()
                    }
                },
            )
        }
    }

    FormScroll(form) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!canEdit) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                        .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("lock", 16.dp, tint = Theme.inkSoft)
                    Text(L("You can read these settings. Only someone who manages billing can change them."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
            if (draft != null) {
                // Two columns on a wide screen: money and the receipt on the left, the rules around visits on the right.
                if (regular) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { LeftPanels() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { RightPanels() }
                    }
                } else {
                    LeftPanels()
                    RightPanels()
                }
                if (canEdit && (dirty || justSaved || working)) SaveBar()
            }
        }
    }
}
