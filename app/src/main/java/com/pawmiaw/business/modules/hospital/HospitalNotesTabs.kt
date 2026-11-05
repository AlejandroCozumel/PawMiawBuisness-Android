package com.pawmiaw.business.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  HospitalNotesTabs.kt (iOS HospitalNotesTabs.swift)
//
//  Updates to the family and notes for the team (with a condition and a
//  photo), the vet's daily rounds, and the running bill (web
//  hospital-notes-tabs.tsx). The bill shows for money level 1 and up
//  (backend docs/money-access-levels.md); a line without a price keeps it a
//  draft (docs/charging-flow.md).
//

// MARK: - Updates

@Composable
fun HospUpdatesTab(model: HospDetailModel, detail: HospDetail, canCare: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val message = remember { mutableStateOf("") }
    val condition = remember { mutableStateOf<String?>(null) }
    var visibility by remember { mutableStateOf<String?>(null) }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    var sending by remember { mutableStateOf(false) }

    val open = detail.isAdmitted || detail.isPending
    val onApp = detail.client?.onPawMiaw == true
    val toFamily = (visibility ?: (if (onApp) "owner" else "internal")) == "owner"

    fun send() {
        if (!form.check(listOf("message" to if (message.value.nonEmpty == null) L("Write the update.") else null))) return
        val who = if (toFamily) "owner" else "internal"
        sending = true
        scope.launch {
            try {
                val data = photo.value.data
                if (data != null) {
                    val fields = mutableMapOf("message" to (message.value.nonEmpty ?: ""), "visibility" to who)
                    condition.value?.let { fields["condition"] = it }
                    ImageUpload.upload<HospDetail>(data, to = HospAPI.detailPath(detail.id) + "/updates", fields = fields)
                    announceDataChanged()
                    model.load()
                } else {
                    model.write("/updates", body = mapOf("message" to message.value.nonEmpty, "condition" to condition.value, "visibility" to who))
                }
                Toast.show(if (who == "owner") L("Sent to the family.") else L("Note saved for the team."))
                message.value = ""
                condition.value = null
                photo.value = PhotoDraft()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(hospError(e, "Could not send it."))
            } finally {
                sending = false
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (canCare && open) {
            Card(spacing = 12.dp) {
                HospCardGroup(title = L("New update"), icon = "message-square-text")
                PillPicker(
                    options = (if (onApp) listOf("owner" to L("To the family")) else emptyList()) + listOf("internal" to L("Team only")),
                    choice = stateOf<String?>({ visibility ?: (if (onApp) "owner" else "internal") }, { visibility = it }),
                )
                if (!onApp) HospHint(text = L("The family isn’t on PawMiaw: updates stay with the team. Call them."))
                PMTextField(
                    L("Message"), text = message, key = "message", form = form,
                    prompt = if (toFamily) LF("e.g. %@ ate a little and is resting. Fluids continue tonight.", detail.patient?.name ?: L("the patient"))
                    else L("e.g. Watch the IV line; restless after 22:00"),
                    multiline = true,
                )
                FormField(label = L("Condition (optional)")) {
                    PillPicker(options = HospWords.conditions.map { it to HospWords.condition(it) }, choice = condition, allowsNone = true)
                }
                PhotoField(label = L("Photo"), draft = photo, allowsRemove = false)
                PMButton(if (toFamily) L("Send to the family") else L("Save note"), systemImage = "send", fullWidth = true, isWorking = sending) { send() }
            }
        }
        if ((detail.updates ?: emptyList()).isEmpty()) {
            EmptyState(systemImage = "message-square-text", title = L("No updates yet."), accent = Theme.pink, panel = true)
        }
        (detail.updates ?: emptyList()).forEach { update ->
            val teamOnly = update.visibility == "internal"
            val shape = RoundedCornerShape(Theme.cardRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (teamOnly) Theme.surface else Theme.wash(Theme.aqua, 8.0), shape)
                    .then(
                        if (teamOnly) Modifier.dashedBorder(1.dp, Theme.borderSoft, Theme.cardRadius, on = 5.dp, off = 4.dp)
                        else Modifier.border(1.dp, Theme.borderSoft, shape)
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowLayout(spacing = 6.dp) {
                    val ink = if (teamOnly) Theme.inkSoft else Theme.blue
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(if (teamOnly) "eye-off" else "users", 14.dp, tint = ink)
                        Text(if (teamOnly) L("Team only") else L("Sent to the family"), style = Theme.text(12, FontWeight.Bold), color = ink)
                    }
                    Text("· ${update.authorName ?: L("Someone")} · ${HospFmt.dateTime(update.createdAt, detail.zone)}", style = Theme.text(12), color = Theme.inkSoft)
                    update.condition?.let { HospConditionBadge(condition = it) }
                }
                update.message.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkStrong) }
                APIConfig.assetURL(update.photoUrl)?.let { url ->
                    SubcomposeAsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        loading = { Spacer(Modifier.height(120.dp)) },
                        error = { Spacer(Modifier.height(120.dp)) },
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .heightIn(max = 288.dp)
                            .clip(RoundedCornerShape(Theme.boxRadius)),
                    )
                }
            }
        }
    }
}

// MARK: - Rounds

@Composable
fun HospRoundsTab(model: HospDetailModel, detail: HospDetail, canManage: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf("") }
    val note = remember { mutableStateOf("") }
    var signing by remember { mutableStateOf(false) }

    val signedDays = (detail.rounds ?: emptyList()).mapNotNull { it.day }.toSet()
    val today = detail.sheet?.today ?: ""
    val unsigned = (detail.sheet?.days ?: emptyList()).filter { it <= today && !signedDays.contains(it) }

    /** The newest day still to sign, unless another was picked (and is still unsigned). */
    val day = if (unsigned.contains(picked)) picked else (unsigned.lastOrNull() ?: detail.sheet?.today ?: "")

    fun sign() {
        val chosen = day
        signing = true
        scope.launch {
            try {
                model.write("/rounds", body = mapOf("day" to chosen, "note" to note.value.nonEmpty))
                Toast.show(LF("Rounds signed for %@.", HospFmt.day(chosen, zone = detail.zone)))
                note.value = ""
                picked = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(hospError(e, "Could not sign the rounds."))
            } finally {
                signing = false
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (canManage && detail.isAdmitted && unsigned.isNotEmpty()) {
            Card(spacing = 12.dp) {
                HospCardGroup(title = L("Sign rounds"), icon = "shield-check")
                MenuField(
                    label = L("Day"),
                    options = unsigned.map { entry ->
                        entry to if (entry == detail.sheet?.today) LF("Today · %@", HospFmt.day(entry, zone = detail.zone)) else HospFmt.day(entry, zone = detail.zone)
                    },
                    selection = stateOf({ day }, { picked = it }),
                )
                PMTextField(L("Note"), text = note, prompt = L("e.g. Stable, eating; continue fluids 24 h, recheck bloodwork tomorrow"), optional = true, multiline = true)
                PMButton(L("Sign rounds"), systemImage = "shield-check", fullWidth = true, isWorking = signing) { sign() }
            }
        }
        if ((detail.rounds ?: emptyList()).isEmpty()) {
            EmptyState(systemImage = "shield-check", title = L("No rounds signed yet."), accent = Theme.pink, panel = true)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (detail.rounds ?: emptyList()).forEach { round ->
                Card(flat = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(40.dp).background(Theme.wash(Theme.lime, 40.0), CircleShape), contentAlignment = Alignment.Center) {
                            Glyph("shield-check", 20.dp, tint = Theme.blue)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(HospFmt.day(round.day, style = HospFmt.DayStyle.long, zone = detail.zone), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                            Text(
                                LF("Signed by %@ · %@", round.signedByName ?: L("a vet"), HospFmt.dateTime(round.signedAt, detail.zone)),
                                style = Theme.text(12), color = Theme.inkSoft,
                            )
                            round.note.nonEmpty?.let { Text(it, Modifier.padding(top = 4.dp), style = Theme.text(14), color = Theme.inkStrong) }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - The running bill

@Composable
fun HospBillTab(detail: HospDetail, canManage: Boolean, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val bill = detail.bill
    if (bill != null) {
        val currency = bill.currency ?: detail.money
        val total = bill.totalAmount ?: 0
        val deposits = bill.depositsPaid ?: 0
        val balance = maxOf(0, total - deposits)
        val taxOnTop = bill.pricesIncludeTax == false
        val againstEstimate = if (taxOnTop) total - (bill.taxAmount ?: 0) else total
        val open = detail.isAdmitted || detail.isPending
        val consent = detail.gate?.consent
        val signedAmount = if (consent?.status == "signed") (consent?.amountCents ?: (if (consent?.satisfied == true) detail.estimateAmount else null)) else null
        val lines = bill.lines ?: emptyList()
        val unpriced = lines.count { it.needsPrice }
        HospGrid(listOf(0, 1), minimum = 320.dp, spacing = 16.dp, modifier = modifier) { cell ->
            if (cell == 0) {
                SectionCard(
                    title = L("Running bill"),
                    trailing = {
                        val invoiceId = bill.invoiceId
                        if (invoiceId != null) {
                            PMButtonStyle(variant = ButtonVariant.outline, size = ButtonSize.header, onClick = { nav.push(ProviderRoute.invoice(invoiceId)) }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Glyph("receipt", 16.dp)
                                    Text(L("Open the bill"))
                                }
                            }
                        }
                    },
                ) {
                    if (lines.isEmpty()) {
                        EmptyState(systemImage = "receipt", title = if (detail.isPending) L("The bill opens at admission.") else L("Nothing charged yet."), accent = Theme.pink)
                    } else {
                        lines.forEachIndexed { index, line ->
                            if (index > 0) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(hospLineLabel(line.label ?: ""), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                                    if (line.needsPrice) Chip(text = L("Needs a price"), tone = Chip.Tone.custom(Theme.mango), dot = Theme.mango, percent = 18.0)
                                    line.description.nonEmpty?.let { Text(hospLineDescription(it), style = Theme.text(12), color = Theme.inkSoft) }
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val amount = hospRounded((line.quantity ?: 1.0) * (line.unitAmount ?: 0).toDouble())
                                    Text(Money.format(amount, currency), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                                    Text(
                                        "${HospFmt.number(line.quantity ?: 1.0)} × ${Money.format(line.unitAmount, currency)}",
                                        style = Theme.text(12).monospacedDigit(), color = Theme.inkSoft,
                                    )
                                }
                            }
                        }
                    }
                    if (unpriced > 0) {
                        RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Theme.wash(Theme.mango, 14.0))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Glyph("triangle-alert", 14.dp, tint = Theme.mango, modifier = Modifier.padding(top = 1.dp))
                            Text(
                                if (unpriced == 1) L("A line has no price: the bill can’t be charged until it has one, or is marked a courtesy.")
                                else LF("%d lines have no price: the bill can’t be charged until they have one, or are marked a courtesy.", unpriced),
                                style = Theme.text(12, FontWeight.Bold), color = Theme.inkStrong,
                            )
                        }
                    }
                }
            } else {
                Card(spacing = 12.dp) {
                    HospAmountRow(L("Total so far"), Money.format(total, currency), strong = true)
                    HospAmountRow(L("Deposits paid"), "− ${Money.format(deposits, currency)}")
                    HospAmountRow(L("Still to pay"), Money.format(balance, currency), strong = true)
                    val estimate = detail.estimateAmount
                    if (estimate != null && estimate > 0) {
                        HospAmountRow(if (taxOnTop) L("Estimate (before tax)") else L("Estimate (tax included)"), Money.format(estimate, currency))
                        if (signedAmount != null) HospAmountRow(L("Signed by the family"), Money.format(signedAmount, currency))
                    }
                    if (consent?.required == true && consent?.satisfied != true) {
                        HospBillNote(
                            if (consent?.reason == "amount_changed") L("The estimate went up after it was signed: the family signs the new amount.")
                            else L("The family hasn’t signed this estimate yet."),
                        )
                    }
                    if (estimate != null && estimate > 0 && againstEstimate > estimate) {
                        HospBillNote(
                            if (taxOnTop) LF("Over the estimate before tax (%@): tell the family and raise it (they sign again).", Money.format(againstEstimate, currency))
                            else L("Over the estimate: tell the family and raise it (they sign again)."),
                        )
                    }
                    val fee = bill.dailyFee
                    if (fee != null) {
                        Text(LF("Daily fee: %@, %@ a day.", fee.name ?: "", Money.format(fee.unitAmount, currency)), style = Theme.text(12), color = Theme.inkSoft)
                    } else if (open) {
                        // No daily fee: the days in the hospital are not charged until one is chosen.
                        val soft = Theme.inkSoft
                        val blue = Theme.blue
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = soft)) { append(L("No daily fee: the days in the hospital aren’t charged.")) }
                                withStyle(SpanStyle(color = blue, fontWeight = FontWeight.Bold)) { append(if (canManage) " " + L("Choose one in Settings") else "") }
                            },
                            Modifier
                                .fillMaxWidth()
                                .dashedBorder(1.dp, Theme.borderSoft, Theme.chipRadius, on = 4.dp, off = 3.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = Theme.text(12),
                        )
                    }
                    val earlier = bill.earlierBills
                    if (!earlier.isNullOrEmpty()) {
                        RowDivider()
                        Text(L("Earlier bills").uppercase(), style = Theme.text(12, FontWeight.Bold).tracking(1.44), color = Theme.inkSoft)
                        earlier.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth().plainButton { nav.push(ProviderRoute.invoice(entry.id)) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusChip(text = HospWords.invoiceStatus(entry.status), state = ProviderState.invoice(entry.status))
                                Spacer(Modifier.weight(1f))
                                Text(Money.format(entry.totalAmount, currency), style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
                                Spacer(Modifier.width(8.dp))
                                Glyph("chevron-right", 14.dp, tint = Theme.inkSoft)
                            }
                        }
                    }
                    if (!open && bill.invoiceId != null) Text(L("The bill stays a draft for the desk to check and send."), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    } else {
        HospHint(text = L("You don’t see billing."), modifier = modifier)
    }
}

/** A mango note in the totals (web: rounded-chip, mango 22%). */
@Composable
private fun HospBillNote(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.mango, 22.0), RoundedCornerShape(Theme.chipRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = Theme.text(12, FontWeight.Bold),
        color = Theme.inkStrong,
    )
}

@Composable
private fun HospAmountRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.alignByBaseline(), style = Theme.text(14), color = Theme.inkSoft)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            Modifier.alignByBaseline(),
            style = (if (strong) Theme.heading(20) else Theme.text(14, FontWeight.Bold)).monospacedDigit(),
            color = Theme.inkStrong,
        )
    }
}

/** Swift `.rounded()`: to the nearest, halves away from zero. */
private fun hospRounded(value: Double): Int = (if (value < 0) -Math.round(-value) else Math.round(value)).toInt()

/** The server's own English lines ("Boarding — Toby"), said in the reader's words. */
private fun hospLineLabel(label: String): String {
    if (label == "No-show fee") return L("No-show fee")
    if (label == "Late cancellation fee") return L("Late cancellation fee")
    for ((english, word) in listOf("Boarding" to L("Boarding"), "Daycare" to L("Daycare"), "Home sitting" to L("Home sitting"), "Home visits" to L("Home visits"))) {
        if (label.startsWith("$english — ")) return word + " — " + label.drop(english.length + 3)
    }
    return label
}

private fun hospLineDescription(description: String): String {
    if (description == "Hospital days") return L("Hospital days")
    if (description == "To take home") return L("To take home")
    return description
}
