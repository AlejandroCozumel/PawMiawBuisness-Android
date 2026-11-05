package com.pawmiaw.business

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pawmiaw.business.money.CashDeviceRegister
import com.pawmiaw.business.money.CashKeepsRegister
import com.pawmiaw.business.money.CashRegisterGate
import com.pawmiaw.business.money.CashText
import com.pawmiaw.business.money.cashRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

//
//  "Take a deposit" (iOS DepositSheet.swift; web visits/visit-money-sheets.tsx DepositSheet;
//  docs/hospitalization.md, docs/money-access-levels.md): an amount, how they paid and its
//  reference, into the shift open at THIS device's register (Money's CashDeviceRegister and
//  CashRegisterGate, which opens a closed register in place). It stays as credit on the
//  client's account, tied to the act it is for — a visit, a stay or a hospitalization — and is
//  spent on its bill. One request per opening (`clientRequestId`): a double tap or a retry
//  never takes it twice. Once taken, the receipt's PDF is offered (or the sheet closes at once
//  when a gate goes straight on).
//  Used by the visit page and the start gate (Schedule), a home stay's confirm (Stays) and the
//  hospital's admission gate (Hospital).
//

@Serializable
data class DepositTaken(
    val id: String? = null,
    val folio: String? = null,
    val creditBalance: Int? = null,
)

/**
 * - `appointmentId` / `stayId` / `hospitalizationId`: the act it is for (one of them, or none
 *   for plain credit).
 * - `owed`: what is still owed, filled in (cents).
 * - `label`: the line under the title ("Emergency deposit · Luna").
 * - `explanation`: what happens to the money, said once (the default names the bill).
 * - `takeWith`: a walk-in not created yet: the deposit goes with it (the payment's body), and
 *   the start with them.
 * - `closesWhenTaken`: close at once when taken (a gate goes straight on) instead of showing
 *   the receipt.
 */
@Composable
fun DepositSheet(
    clientId: String,
    clientName: String,
    appointmentId: String? = null,
    stayId: String? = null,
    hospitalizationId: String? = null,
    owed: Int? = null,
    currency: String? = null,
    label: String? = null,
    explanation: String? = null,
    takeWith: (suspend (Map<String, Any?>) -> Unit)? = null,
    closesWhenTaken: Boolean = false,
    onTaken: suspend () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val device = remember { CashDeviceRegister() }
    val form = remember { FormState() }
    // iOS fills the owed amount in on appear; here it starts there.
    val amount = remember { mutableStateOf(if (owed != null && owed > 0) owed else null) }
    val method = remember { mutableStateOf("card") }
    val reference = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val requestId = remember { cashRequestId() }
    var taken by remember { mutableStateOf<DepositTaken?>(null) }
    val scope = rememberCoroutineScope()
    val taking by rememberUpdatedState(takeWith)
    val afterTaken by rememberUpdatedState(onTaken)

    val said = explanation ?: if (takeWith != null) {
        L("The visit is checked in, the deposit goes on it as credit on their account, and treatment starts. If they leave, it stays their credit until it is used or refunded.")
    } else {
        L("It stays as credit on their account and is used when they pay the bill.")
    }

    fun submit() {
        if (!form.check(listOf("amount" to (if ((amount.value ?: 0) <= 0) L("Enter the deposit amount") else null)))) return
        if (method.value == "cash" && device.sessionId == null) {
            Toast.error(L("Open the register to take a cash deposit"))
            return
        }
        val payment: Map<String, Any?> = mapOf(
            "amount" to (amount.value ?: 0),
            "method" to method.value,
            "reference" to (if (method.value == "cash") null else reference.value.nonEmpty),
            "registerSessionId" to device.sessionId,
            "clientRequestId" to (if (takeWith != null) null else requestId),
        )
        working = true
        scope.launch {
            try {
                var result: DepositTaken? = null
                val with = taking
                if (with != null) {
                    with(payment)
                } else {
                    val body = payment.toMutableMap()
                    body["appointmentId"] = appointmentId
                    body["stayId"] = stayId
                    if (hospitalizationId != null) body["hospitalizationId"] = hospitalizationId
                    val answer: DepositTaken = API.post("/provider-finance/clients/$clientId/deposit", body)
                    Toast.show(LF("Deposit %@ taken. %@ has %@ of credit.", answer.folio ?: "", clientName, Money.format(answer.creditBalance, currency)))
                    result = answer
                }
                CashDeviceRegister.changed.tryEmit(Unit)
                announceDataChanged()
                afterTaken()
                if (closesWhenTaken || result == null) dismiss() else taken = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? APIError)?.code == "deposit_required") {
                    Toast.error(L("The deposit hasn’t been paid yet."))
                } else {
                    form.apply(e, mapOf("amount" to "amount"))
                }
            } finally {
                working = false
            }
        }
    }

    CashKeepsRegister(device)
    val receipt = taken
    ProviderModal(
        title = if (receipt == null) L("Take a deposit") else L("Deposit taken"),
        subtitle = label ?: clientName,
        systemImage = "banknote.fill",
        accent = ModalAccent.mint,
        isDirty = receipt == null && (amount.value != owed || reference.value.isNotEmpty()),
        form = form,
        primaryTitle = if (receipt != null) L("Done") else (if (takeWith != null) L("Take deposit and start") else L("Take deposit")),
        isWorking = working,
        cancelTitle = if (receipt == null) L("Cancel") else L("Close"),
        onPrimary = { if (taken == null) submit() else dismiss() },
        modifier = modifier,
    ) {
        if (receipt != null) {
            NoticeCard(
                tone = NoticeCard.Tone.success,
                title = LF("Deposit %@ taken. %@ has %@ of credit.", receipt.folio ?: "", clientName, Money.format(receipt.creditBalance, currency)),
            )
            val id = receipt.id
            if (id != null) {
                DocumentButton(
                    title = L("Download PDF"),
                    path = "/provider-finance/deposits/$id/pdf",
                    fileName = receipt.folio ?: L("Deposit"),
                    variant = ButtonVariant.outline,
                    size = ButtonSize.compact,
                )
            }
        } else {
            Text(said, style = Theme.smallFont, color = Theme.inkSoft)
            MoneyField(label = L("Amount"), cents = amount, key = "amount", form = form)
            ModalSection(title = L("How they paid")) {
                FilterBar(options = CashText.methods.map { Triple(it, CashText.method(it), null) }, selection = method)
                if (method.value != "cash") {
                    PMTextField(L("Reference"), text = reference, prompt = L("Reference (authorization, transfer…)"), optional = true)
                }
                CashRegisterGate(device = device, wantsCash = method.value == "cash")
            }
        }
    }
}
