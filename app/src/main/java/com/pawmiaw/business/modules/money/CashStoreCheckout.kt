package com.pawmiaw.business.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.UUID

//
//  CashStoreCheckout.kt (iOS CashStoreCheckout.swift)
//
//  The Store's counter with a sale another screen sent here already filled in
//  (web: provider.store.tsx with `?client&pet&rx` from the Pharmacy, or
//  `?client&checkout` from a bill or a visit): the client and pet chosen, a
//  hand-over's medicines in the sale against their prescription lines, or the
//  bills to charge selected with room for products from the shelf. Once it is
//  charged and the receipt closed, it goes back to the screen that sent it.
//

@Composable
fun StoreCheckoutScreen(prefill: StorePrefill, modifier: Modifier = Modifier) {
    providerPage(L("Store"), modifier = modifier) {
        RequireLocation { CashCheckoutPage(prefill = prefill) }
    }
}

@Composable
private fun CashCheckoutPage(prefill: StorePrefill) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val nav = LocalNavigator.current
    val device = remember { CashDeviceRegister() }
    var settings by remember { mutableStateOf<CashStoreSettings?>(null) }
    var settingsError by remember { mutableStateOf<String?>(null) }

    val what = when {
        prefill.lines.isNotEmpty() || prefill.prescriptionId != null ->
            L("The prescription’s medicines are in the sale, each against its prescription line. Stock, the prescription and the receipt follow the sale.")
        prefill.invoiceId != null || prefill.appointmentId != null ->
            L("The bill is selected: add anything else from the counter and charge it all at once.")
        else -> L("Sell at the counter — stock, tax and the receipt take care of themselves.")
    }

    suspend fun load() {
        if (settings != null) return
        try {
            settings = API.get<CashStoreSettings>("/provider-store/settings")
            settingsError = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            settingsError = errorText(error)
        }
    }

    /** iOS `.navigationDestination(isPresented: $registerShown)`: the register, pushed as its own page. */
    fun showRegister() {
        nav.push(ProviderRoute.page("store-register") {
            providerPage(L("Register")) {
                PageScroll(maxWidth = 980.dp) {
                    ModuleHeader(icon = "wallet", title = L("Register"), subtitle = device.register?.name, storageKey = "store-register")
                    CashRegisterTab(device = device)
                }
            }
        })
    }

    if (!workspace.can(Permission.salesSell)) {
        PageScroll { NoAccessState(message = L("Ask the workspace owner to let your role sell.")) }
    } else {
        CashKeepsRegister(device)
        LaunchedEffect(Unit) { load() }
        PageScroll(maxWidth = 1180.dp) {
            // The Store's own header, on the Sell tab: the register at this device at the top.
            ModuleHeader(icon = "shopping-cart", title = L("Store"), subtitle = what, storageKey = "store-checkout") {
                val register = device.register
                if (register != null) {
                    if (device.registers.size > 1) {
                        AppMenu(label = { FilterMenuLabel(title = register.name) }, accessibilityLabel = L("Register at this device")) {
                            device.registers.forEach { entry ->
                                item(entry.name, checked = entry.id == register.id) { device.choose(entry.id) }
                            }
                        }
                    }
                    val open = register.openSessionId != null
                    Chip(
                        text = if (open) LF("%@ open", register.name) else LF("%@ closed", register.name),
                        tone = if (open) Chip.Tone.custom(ProviderState.done.color) else Chip.Tone.muted,
                        icon = if (open) "lock-open" else "lock", percent = 16.0,
                        ink = if (open) Theme.inkStrong else Theme.inkSoft, border = 60.0, size = Chip.Size.header,
                        modifier = Modifier.plainButton { showRegister() },
                    )
                }
            }
            val loaded = settings
            val error = settingsError
            if (loaded != null) {
                CashCounter(settings = loaded, device = device, openRegister = { showRegister() }, prefill = prefill, onCharged = {
                    announceDataChanged()
                    dismiss()
                })
            } else if (error != null) {
                ErrorState(message = error) { load() }
            } else {
                LoadingState()
            }
        }
    }
}

// MARK: - A hand-over read from its prescription

/**
 * What a signed prescription hands over now: each line sold through the Catalog
 * with its next fill (web: prescription-view-sheet.tsx `prescriptionHandOver`).
 */
object CashHandOver {
    data class Loaded(val clientId: String, val petId: String, val lines: List<StorePrefill.Line>)

    @Serializable
    private data class Prescription(
        val providerClientId: String,
        val providerClientPetId: String,
        val status: String? = null,
        val items: List<Item>? = null,
    ) {
        @Serializable
        data class Fill(val remaining: Int? = null)

        @Serializable
        data class Product(val active: Boolean? = null)

        @Serializable
        data class Item(
            val id: String,
            val catalogItemId: String? = null,
            val product: Product? = null,
            val fill: Fill? = null,
            val nextFill: Int? = null,
            val handOverBlock: String? = null,
        )
    }

    suspend fun load(prescriptionId: String): Loaded {
        val rx: Prescription = API.get("/provider-prescriptions/$prescriptionId")
        val lines: List<StorePrefill.Line> = (rx.items ?: emptyList()).mapNotNull { item ->
            val catalog = if (item.product?.active == false) null else item.catalogItemId
            val quantity = item.nextFill ?: 0
            if (item.handOverBlock != null || catalog == null || quantity <= 0) return@mapNotNull null
            StorePrefill.Line(catalogItemId = catalog, quantity = quantity.toDouble(), prescriptionItemId = item.id)
        }
        return Loaded(clientId = rx.providerClientId, petId = rx.providerClientPetId, lines = lines)
    }
}

// MARK: - A code on more than one product

data class CashAmbiguousCode(val code: String, val products: List<CashProduct>) {
    val id: UUID = UUID.randomUUID()
}

/** "¿Qué producto es?": a code saved on two products; the person says which is being sold. */
@Composable
fun CashAmbiguousCodeSheet(found: CashAmbiguousCode, onChoose: (CashProduct) -> Unit, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    ProviderModal(
        title = L("Which product is it?"),
        subtitle = LF("The code %@ is on %d products. Choose the one being sold.", found.code, found.products.size),
        systemImage = "scan-barcode",
        accent = ModalAccent.mango,
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            found.products.forEach { product ->
                val shape = RoundedCornerShape(Theme.boxRadius)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(Theme.surface, shape)
                        .border(1.5.dp, Theme.borderSoft, shape)
                        .plainButton(label = product.name) {
                            dismiss()
                            onChoose(product)
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(product.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        product.sku.nonEmpty?.let { sku ->
                            Text(sku, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        if (product.unitAmount > 0) cashMoney(product.unitAmount, product.currency) else L("No price"),
                        style = Theme.heading(16).monospacedDigit(),
                        color = Theme.inkStrong,
                    )
                }
            }
        }
        Text(L("Give each product its own barcode in the Catalog so a scan finds one."), style = Theme.text(12), color = Theme.inkSoft)
    }
}
