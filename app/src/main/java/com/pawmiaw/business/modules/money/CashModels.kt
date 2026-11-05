package com.pawmiaw.business.money

import com.pawmiaw.business.*
import kotlinx.serialization.Serializable
import java.time.ZoneId

//
//  CashModels.kt (iOS CashModels.swift)
//
//  The Money module's shapes, mirroring the web's hooks (use-provider-store,
//  use-provider-invoices, use-charging-queue, use-provider-finance,
//  use-provider-billing, use-provider-billing-summary). Amounts are integer
//  cents; dates are ISO strings. Anything a server may leave out is nullable.
//

// MARK: - People

@Serializable
data class CashActor(
    val id: String? = null,
    val name: String? = null,
    val image: String? = null,
)

// MARK: - Store

@Serializable
data class CashStoreSettings(
    var currency: String,
    var pricesIncludeTax: Boolean,
    var defaultTaxRateBps: Int,
    var receiptFooter: String? = null,
    var noShowFeeAmount: Int? = null,
    var lateCancelWindowHours: Int? = null,
    var cashDifferenceAlertAmount: Int? = null,
    var openingCashDefault: Int? = null,
    var stayDepositPercent: Int? = null,
    var showPricesToFamilies: Boolean? = null,
    var currencyLocked: Boolean? = null,
    var currencyLockedReason: String? = null,
    var emergencyDepositAmount: Int? = null,
    var afterHoursSurchargeItemId: String? = null,
    var emailReceipts: Boolean? = null,
)

@Serializable
data class CashProductStock(
    val inventoryItemId: String? = null,
    val unitsPerSale: Int? = null,
    val available: Int? = null,
    val allowBackorder: Boolean? = null,
    val reorderPoint: Int? = null,
    val lowStock: Boolean? = null,
    val outOfStock: Boolean? = null,
    val lowStockAlert: Boolean? = null,
    val expiredUnits: Int? = null,
)

@Serializable
data class CashProduct(
    val catalogItemId: String,
    val itemType: String,
    val name: String,
    val sku: String? = null,
    val barcode: String? = null,
    val photoUrl: String? = null,
    val categoryId: String? = null,
    val unitAmount: Int,
    val currency: String? = null,
    val productKind: String? = null,
    val taxTreatment: String? = null,
    val taxRateBps: Int? = null,
    val requiresPrescription: Boolean? = null,
    val controlSchedule: String? = null,
    val stock: CashProductStock? = null,
) {
    val id: String get() = catalogItemId
    val isService: Boolean get() = itemType == "service"
    val rate: Int get() = taxRateBps ?: 0
    val needsPrescription: Boolean get() = requiresPrescription ?: false
}

@Serializable
data class CashSaleLot(
    val lotCode: String? = null,
    val expiresOn: String? = null,
    val quantity: Int? = null,
)

@Serializable
data class CashSaleLine(
    val id: String,
    val catalogItemId: String? = null,
    val itemType: String,
    val label: String,
    val quantity: Int,
    val unitAmount: Int,
    val listUnitAmount: Int? = null,
    val discountAmount: Int? = null,
    val taxRateBps: Int? = null,
    val taxAmount: Int? = null,
    val totalAmount: Int,
    val returnedQuantity: Int,
    val restockedQuantity: Int? = null,
    val tracksStock: Boolean? = null,
    val prescriptionFolio: String? = null,
    val lots: List<CashSaleLot>? = null,
)

@Serializable
data class CashSalePayment(
    val id: String,
    val kind: String,
    val method: String,
    val amount: Int,
    val tenderedAmount: Int? = null,
    val changeAmount: Int? = null,
    val reference: String? = null,
    val voidedAt: String? = null,
    val paidAt: String? = null,
)

@Serializable
data class CashSaleReturn(
    val id: String,
    val folio: String,
    val reason: String? = null,
    val refundAmount: Int,
    val createdAt: String? = null,
)

@Serializable
data class CashSale(
    val id: String,
    val folio: String,
    val status: String,
    val kind: String? = null,
    val createdAt: String,
    val currency: String,
    val pricesIncludeTax: Boolean? = null,
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    val walkIn: Boolean,
    val customerName: String? = null,
    val petName: String? = null,
    val cashierName: String? = null,
    val registerSessionId: String? = null,
    val subtotalAmount: Int,
    val discountAmount: Int,
    val discountReason: String? = null,
    val taxAmount: Int,
    val totalAmount: Int,
    val amountPaid: Int,
    val balanceDueAmount: Int,
    val returnedAmount: Int,
    val note: String? = null,
    val cancelReason: String? = null,
    val canceledAt: String? = null,
    val lines: List<CashSaleLine>,
    val payments: List<CashSalePayment>,
    val returns: List<CashSaleReturn>,
)

@Serializable
data class CashSaleSummary(
    val id: String,
    val folio: String,
    val status: String,
    val createdAt: String,
    val totalAmount: Int,
    val balanceDueAmount: Int,
    val returnedAmount: Int,
    val currency: String,
    val customerName: String? = null,
    val walkIn: Boolean,
    val petName: String? = null,
    val lineCount: Int? = null,
    val unitCount: Int? = null,
    val methods: List<String>,
    val cashierName: String? = null,
) {
    val canceled: Boolean get() = status == "canceled"
}

@Serializable
data class CashRegisterOpenSession(
    val id: String,
    val openedAt: String,
    val openedByName: String? = null,
    val stale: Boolean? = null,
    val timeZone: String? = null,
)

@Serializable
data class CashRegister(
    val id: String,
    val name: String,
    val openSessionId: String? = null,
    val openSession: CashRegisterOpenSession? = null,
    val lastCashLeft: Int? = null,
    val defaultOpeningCash: Int? = null,
    val locationId: String? = null,
)

@Serializable
data class CashMethodFigure(
    val method: String,
    val payments: Int,
    val refunds: Int,
    val count: Int? = null,
)

@Serializable
data class CashPersonFigure(
    val userId: String? = null,
    val name: String? = null,
    val payments: Int,
    val refunds: Int,
    val count: Int? = null,
    val net: Int? = null,
)

@Serializable
data class CashMovement(
    val id: String,
    val kind: String,
    val amount: Int,
    val reason: String? = null,
    val actorName: String? = null,
    val createdAt: String,
)

@Serializable
data class CashSession(
    val id: String,
    val registerId: String? = null,
    val registerName: String,
    val status: String,
    val openedAt: String,
    val openedDay: String? = null,
    val timeZone: String? = null,
    val openedByName: String? = null,
    val openingCash: Int,
    val openingExpected: Int? = null,
    val openingDifference: Int? = null,
    val openingNote: String? = null,
    val closedAt: String? = null,
    val closedByName: String? = null,
    val countedCash: Int? = null,
    val difference: Int? = null,
    val closingNote: String? = null,
    val cashLeft: Int? = null,
    val saleCount: Int,
    val salesTotal: Int,
    val returnCount: Int? = null,
    val byMethod: List<CashMethodFigure>,
    val byPerson: List<CashPersonFigure>? = null,
    val cashIn: Int,
    val cashOut: Int,
    val expectedCash: Int,
    val cardTotal: Int,
    val cardTerminalTotal: Int? = null,
    val cardDifference: Int? = null,
    val denominations: Map<String, Int>? = null,
    val frozen: Boolean? = null,
    val stale: Boolean? = null,
    val alertThreshold: Int? = null,
    val cashMovements: List<CashMovement>,
) {
    val isOpen: Boolean get() = status == "open"
    val zone: ZoneId get() = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Workspace.shared.timeZone
}

@Serializable
data class CashCheckoutResult(
    val saleId: String? = null,
    val invoices: List<Bill>,
    val paid: Int,
    val change: Int,
    val balanceLeft: Int,
    val receipt: Receipt? = null,
) {
    @Serializable
    data class Bill(val id: String, val folio: String, val paid: Int, val balance: Int)

    @Serializable
    data class Receipt(val emailedTo: String? = null)
}

/** A prescription line a product may be handed over against (`/provider-prescriptions/dispensable`). */
@Serializable
data class CashDispensable(
    val prescriptionItemId: String,
    val folio: String? = null,
    val medicineName: String? = null,
    val remaining: Int? = null,
    val prescriberName: String? = null,
    val exactProduct: Boolean? = null,
    val unitsPerSale: Int? = null,
    val validUntil: String? = null,
) {
    val id: String get() = prescriptionItemId
}

@Serializable
data class CashDispensableList(val items: List<CashDispensable>)

// MARK: - Invoices

@Serializable
data class CashCatalogSummary(
    val id: String,
    val name: String,
    val defaultUnitAmount: Int? = null,
)

@Serializable
data class CashInvoiceLine(
    val id: String,
    val providerCatalogItemId: String? = null,
    val catalogItem: CashCatalogSummary? = null,
    val itemType: String,
    val label: String,
    val description: String? = null,
    val quantity: Int,
    val unitAmount: Int,
    val lineTotalAmount: Int? = null,
    val taxTreatment: String? = null,
    val taxRateBps: Int? = null,
    val taxAmount: Int? = null,
    val discountAmount: Int? = null,
    val sortOrder: Int? = null,
    val sourceType: String? = null,
    val sourceId: String? = null,
    val createdBy: CashActor? = null,
    val listUnitAmount: Int? = null,
    val priceOverriddenBy: CashActor? = null,
    val sourceLabel: String? = null,
    val changedAfterSent: Boolean? = null,
    val prescriptionItemId: String? = null,
    val requiresPrescription: Boolean? = null,
    val controlSchedule: String? = null,
    val courtesy: Boolean? = null,
    val adjustmentReason: String? = null,
)

@Serializable
data class CashInvoicePayment(
    val id: String,
    val amount: Int,
    val kind: String? = null,
    val currency: String? = null,
    val method: String,
    val status: String? = null,
    val reference: String? = null,
    val notes: String? = null,
    val paidAt: String? = null,
    val collectedBy: CashActor? = null,
) {
    val isRefund: Boolean get() = kind == "refund"
    val voided: Boolean get() = status == "voided"
}

@Serializable
data class CashCreditNote(
    val id: String,
    val amount: Int,
    val reason: String? = null,
    val createdAt: String? = null,
    val byName: String? = null,
)

@Serializable
data class CashInvoice(
    val id: String,
    val providerClientId: String,
    val providerClientPetId: String? = null,
    val providerAppointmentId: String? = null,
    val assignedMember: CashActor? = null,
    val invoiceNumber: String,
    val status: String,
    val currency: String,
    val subtotalAmount: Int,
    val discountAmount: Int,
    val discountReason: String? = null,
    val taxAmount: Int,
    val totalAmount: Int,
    val amountPaid: Int,
    val balanceDueAmount: Int,
    val notes: String? = null,
    val billingNote: String? = null,
    val pdfUrl: String? = null,
    val createdFromSourceType: String? = null,
    val createdFromSourceId: String? = null,
    val issuedAt: String? = null,
    val dueAt: String? = null,
    val finalizedAt: String? = null,
    val clientName: String,
    val clientPhotoUrl: String? = null,
    val petName: String? = null,
    val petPhotoUrl: String? = null,
    val petSpecies: String? = null,
    val kind: String? = null,
    val origin: String? = null,
    val refundedAmount: Int? = null,
    val customerName: String? = null,
    val pricesIncludeTax: Boolean? = null,
    val cancelReason: String? = null,
    val canceledAt: String? = null,
    val canceledBy: CashActor? = null,
    val locationId: String? = null,
    val receiptEmailedTo: String? = null,
    val lineItemCount: Int? = null,
    val paymentCount: Int? = null,
    val lineItems: List<CashInvoiceLine>? = null,
    val payments: List<CashInvoicePayment>? = null,
    val creditNotes: List<CashCreditNote>? = null,
    val createdBy: CashActor? = null,
    val updatedBy: CashActor? = null,
    val finalizedBy: CashActor? = null,
    val revision: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
) {
    val lines: List<CashInvoiceLine> get() = lineItems ?: emptyList()
    val allPayments: List<CashInvoicePayment> get() = payments ?: emptyList()
    val refunded: Int get() = refundedAmount ?: 0
}

@Serializable
data class CashInvoiceSummary(
    val due: Count,
    val overdue: Count,
    val draft: Draft,
    val desk: Plain,
    val partial: Plain,
) {
    @Serializable
    data class Count(val count: Int, val amount: Int? = null)

    @Serializable
    data class Draft(val count: Int, val needsPrice: Int)

    @Serializable
    data class Plain(val count: Int)
}

@Serializable
data class CashInvoiceTotals(
    val billedAmount: Int,
    val paidAmount: Int,
    val balanceAmount: Int,
    val draftAmount: Int? = null,
    val standingCount: Int,
)

@Serializable
data class CashResolvedPeriod(
    val from: String,
    val to: String,
    val today: String? = null,
    val kind: String? = null,
)

@Serializable
data class CashInvoicePage(
    val summary: CashInvoiceSummary? = null,
    val totals: CashInvoiceTotals? = null,
    val items: List<CashInvoice>,
    val period: CashResolvedPeriod? = null,
    val today: String? = null,
    val total: Int,
    val hasMore: Boolean? = null,
)

@Serializable
data class CashChargingItem(
    val source: String,
    val sourceId: String,
    val appointmentId: String? = null,
    val invoiceId: String? = null,
    val state: String,
    val petName: String,
    val petNames: List<String>? = null,
    val providerClientPetId: String? = null,
    val clientName: String,
    val providerClientId: String? = null,
    val serviceLabel: String? = null,
    val at: String,
    val day: String,
    val earlierDay: Boolean,
    val vetName: String? = null,
    val amount: Int? = null,
    val currency: String? = null,
) {
    val id: String get() = invoiceId ?: sourceId
}

@Serializable
data class CashChargingQueue(
    val scope: String? = null,
    val count: Int,
    val amount: Int? = null,
    val earlierDays: Earlier? = null,
    val items: List<CashChargingItem>,
    val today: String? = null,
) {
    @Serializable
    data class Earlier(val count: Int, val oldestDay: String? = null)
}

// MARK: - Finance

@Serializable
data class CashDashboard(
    val currency: String,
    val timeZone: String? = null,
    val period: CashResolvedPeriod? = null,
    val totals: Totals,
    val days: List<Day>,
    val sources: List<Source>,
    val methods: List<Method>,
    val topItems: List<Item>,
    val staff: List<Person>,
    val collectors: List<Person>? = null,
    val topClients: List<TopClient>? = null,
    val aging: Map<String, Int>? = null,
    val cash: Cash? = null,
    val inventory: Inventory? = null,
    val locationId: String? = null,
    val byLocation: List<Place>? = null,
) {
    @Serializable
    data class Totals(
        val collected: Int,
        val previousCollected: Int,
        val refunded: Int,
        val previousRefunded: Int? = null,
        val paymentCount: Int? = null,
        val refundCount: Int? = null,
        val invoiced: Int,
        val previousInvoiced: Int,
        val tax: Int? = null,
        val previousTax: Int? = null,
        val subtotal: Int? = null,
        val depositsReceived: Int? = null,
        val creditApplied: Int? = null,
        val creditHeld: Int? = null,
        val creditClients: Int? = null,
        val documents: Int,
        val previousDocuments: Int,
        val averageTicket: Int,
        val previousAverageTicket: Int,
        val margin: Int,
        val marginCost: Int,
        val marginRevenue: Int,
        val previousMargin: Int,
        val owed: Int,
        val overdue: Int,
        val clientsOwing: Int,
    )

    @Serializable
    data class Day(val day: String, val collected: Int, val invoiced: Int)

    @Serializable
    data class Source(val source: String, val invoiced: Int, val documents: Int, val collected: Int)

    @Serializable
    data class Method(val method: String, val amount: Int, val count: Int, val refunds: Int? = null)

    @Serializable
    data class Item(val key: String, val label: String, val itemType: String? = null, val quantity: Int, val revenue: Int, val margin: Int? = null)

    @Serializable
    data class Person(
        val userId: String? = null,
        val name: String? = null,
        val image: String? = null,
        val documents: Int? = null,
        val revenue: Int? = null,
        val payments: Int? = null,
        val collected: Int? = null,
    )

    @Serializable
    data class TopClient(
        val providerClientId: String,
        val name: String,
        val photoUrl: String? = null,
        val walkIn: Boolean,
        val documents: Int,
        val invoiced: Int,
        val collected: Int,
    )

    @Serializable
    data class Cash(
        val closedShifts: Int,
        val openShifts: Int,
        val shiftsWithDifference: Int,
        val short: Int,
        val over: Int,
        val net: Int,
        val items: List<Item>,
    ) {
        @Serializable
        data class Item(val id: String, val registerName: String, val day: String? = null, val difference: Int, val closedByName: String? = null)
    }

    @Serializable
    data class Inventory(
        val tracked: Int,
        val valueAmount: Int,
        val lowStock: Int,
        val outOfStock: Int,
        val expiringSoon: Int,
        val expired: Int? = null,
        val expiringDays: Int? = null,
    )

    @Serializable
    data class Place(
        val locationId: String,
        val name: String,
        val status: String? = null,
        val isPrimary: Boolean? = null,
        val collected: Int,
        val invoiced: Int,
        val documents: Int,
    )
}

@Serializable
data class CashPromise(
    val id: String,
    val promisedAmount: Int? = null,
    val promisedOn: String,
    val status: String,
)

@Serializable
data class CashCollectionItem(
    val providerClientId: String,
    val name: String,
    val firstName: String? = null,
    val photoUrl: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val onPawMiaw: Boolean? = null,
    val walkIn: Boolean? = null,
    val creditLimitAmount: Int? = null,
    val overLimit: Boolean? = null,
    val currency: String? = null,
    val owed: Int,
    val aging: Map<String, Int>? = null,
    val credit: Int? = null,
    val overdue: Int,
    val documents: Int,
    val oldestDueDay: String? = null,
    val daysOverdue: Int,
    val petNames: List<String>? = null,
    val promise: CashPromise? = null,
    val lastContactAt: String? = null,
    val lastContactKind: String? = null,
    val lastContactChannel: String? = null,
) {
    val id: String get() = providerClientId
}

@Serializable
data class CashCollections(
    val today: String,
    val summary: Summary,
    val items: List<CashCollectionItem>,
    val credits: List<Credit>? = null,
) {
    @Serializable
    data class Summary(
        val owed: Int,
        val overdue: Int,
        val clients: Int,
        val overdueClients: Int,
        val promisesDue: Int,
        val promisesBroken: Int,
        val promisesPending: Int,
        val overLimit: Int,
        val uncontacted: Int,
        val aging: Map<String, Int>? = null,
        val agingClients: Map<String, Int>? = null,
        val creditHeld: Int? = null,
        val creditClients: Int? = null,
        val currency: String,
    )

    @Serializable
    data class Credit(val providerClientId: String, val name: String, val photoUrl: String? = null, val credit: Int, val owed: Int) {
        val id: String get() = providerClientId
    }
}

@Serializable
data class CashAccount(
    val client: Client,
    val credit: Int,
    val currency: String,
    val timeZone: String? = null,
    val today: String,
    val owed: Int,
    val overdue: Int,
    val open: List<Open>,
    val statement: List<Entry>,
    val notes: List<Note>,
) {
    @Serializable
    data class Client(
        val id: String,
        val name: String,
        val firstName: String? = null,
        val photoUrl: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val onPawMiaw: Boolean? = null,
        val walkIn: Boolean? = null,
        val creditLimitAmount: Int? = null,
    )

    @Serializable
    data class Open(
        val id: String,
        val folio: String,
        val source: String,
        val status: String,
        val petName: String? = null,
        val totalAmount: Int,
        val refundedAmount: Int? = null,
        val amountPaid: Int,
        val balanceDueAmount: Int,
        val issuedAt: String? = null,
        val dueDay: String? = null,
        val daysOverdue: Int,
    )

    @Serializable
    data class Entry(
        val at: String,
        val type: String,
        val id: String,
        val folio: String? = null,
        val label: String? = null,
        val amount: Int,
        val method: String? = null,
        val source: String? = null,
        val balance: Int,
    )

    @Serializable
    data class Note(
        val id: String,
        val kind: String,
        val body: String? = null,
        val channel: String? = null,
        val promisedAmount: Int? = null,
        val promisedOn: String? = null,
        val promiseStatus: String? = null,
        val balanceAmount: Int? = null,
        val author: String? = null,
        val createdAt: String,
    )
}

@Serializable
data class CashCollectResult(
    val collected: Int,
    val owedAfter: Int,
    val replayed: Boolean? = null,
)

@Serializable
data class CashLedgerItem(
    val id: String,
    val providerInvoiceId: String,
    val amount: Int,
    val kind: String,
    val method: String,
    val reference: String? = null,
    val paidAt: String? = null,
    val source: String? = null,
    val invoiceNumber: String,
    val providerClientId: String? = null,
    val clientName: String,
    val petName: String? = null,
    val serviceLabel: String? = null,
    val daysLate: Int? = null,
    val collectedBy: CashActor? = null,
)

@Serializable
data class CashLedgerPage(
    val items: List<CashLedgerItem>,
    val summary: Summary,
    val total: Int,
    val hasMore: Boolean,
) {
    @Serializable
    data class Summary(
        val totalAmount: Int,
        val refundedAmount: Int,
        val paymentCount: Int? = null,
        val refundCount: Int? = null,
        val creditAppliedAmount: Int? = null,
        val creditCount: Int? = null,
    )
}

@Serializable
data class CashDayClose(
    val day: String,
    val today: String,
    val isToday: Boolean,
    val timeZone: String? = null,
    val currency: String,
    val byMethod: List<Method>,
    val credit: Credit,
    val bySource: List<Source>,
    val byPerson: List<CashPersonFigure>,
    val totals: Totals,
    val outsideShifts: Outside? = null,
    val refundItems: List<Refund>,
    val creditRefundItems: List<Refund>? = null,
    val shifts: List<CashSession>,
    val byLocation: List<Place>? = null,
    val shiftTotals: ShiftTotals,
) {
    @Serializable
    data class Method(val method: String, val payments: Int, val refunds: Int, val net: Int, val count: Int)

    @Serializable
    data class Credit(val spent: Int, val returned: Int, val count: Int)

    @Serializable
    data class Source(val source: String, val net: Int, val count: Int)

    @Serializable
    data class Totals(val collected: Int, val refunded: Int, val net: Int, val payments: Int, val refunds: Int)

    @Serializable
    data class Outside(val net: Int, val count: Int)

    @Serializable
    data class Refund(
        val id: String,
        val at: String,
        val amount: Int,
        val method: String,
        val folio: String,
        val invoiceId: String,
        val clientName: String? = null,
        val by: String? = null,
        val reason: String? = null,
    )

    @Serializable
    data class Place(
        val locationId: String,
        val name: String,
        val status: String? = null,
        val net: Int,
        val payments: Int,
        val refunds: Int,
        val openShifts: Int,
    )

    @Serializable
    data class ShiftTotals(
        val shifts: Int,
        val open: Int,
        val cashExpected: Int,
        val cashCounted: Int,
        val cashDifference: Int,
        val cardTotal: Int,
        val cardTerminalTotal: Int? = null,
        val cardDifference: Int? = null,
    )
}

@Serializable
data class CashException(
    val id: String,
    val kind: String,
    val at: String,
    val actorName: String? = null,
    val amount: Int,
    val currency: String? = null,
    val reason: String? = null,
    val clientName: String? = null,
    val petName: String? = null,
    val documentId: String? = null,
    val documentFolio: String? = null,
    val documentKind: String? = null,
    val sessionId: String? = null,
    val registerName: String? = null,
    val link: Link,
    val direction: String? = null,
    val method: String? = null,
    val paidWith: List<String>? = null,
    val lineLabel: String? = null,
    val paidOn: String? = null,
    val visitOn: String? = null,
) {
    @Serializable
    data class Link(val to: String, val id: String)
}

@Serializable
data class CashExceptions(
    val day: String,
    val timeZone: String? = null,
    val currency: String,
    val total: Int,
    val withoutReason: Int,
    val items: List<CashException>,
)

@Serializable
data class CashSalesReport(
    val currency: String,
    val groupBy: String,
    val rows: List<Row>,
    val totals: Totals,
) {
    @Serializable
    data class Row(
        val key: String,
        val label: String,
        val detail: String? = null,
        val documents: Int,
        val quantity: Int,
        val revenue: Int,
        val tax: Int? = null,
        val cost: Int? = null,
        val margin: Int? = null,
    ) {
        val id: String get() = key
    }

    @Serializable
    data class Totals(
        val documents: Int,
        val quantity: Int,
        val revenue: Int,
        val tax: Int? = null,
        val cost: Int,
        val margin: Int,
        val costed: Boolean? = null,
    )
}

// MARK: - Catalog

@Serializable
data class CashCategory(
    val id: String,
    val itemType: String,
    val name: String,
    val active: Boolean,
)

@Serializable
data class CashCategoryList(val items: List<CashCategory>)

@Serializable
data class CashInventorySummary(
    /** The stock record's id (a delivery receives against it). */
    val id: String? = null,
    val barcode: String? = null,
    val storageLocation: String? = null,
    val unitCostAmount: Int? = null,
    val quantityOnHand: Int,
    val reorderPoint: Int,
    val reorderQuantity: Int? = null,
    val allowBackorder: Boolean,
    val lowStock: Boolean,
    val lowStockAlert: Boolean? = null,
    val expiredUnits: Int? = null,
)

@Serializable
data class CashCatalogItem(
    val id: String,
    val itemType: String,
    val category: CashCategory? = null,
    val name: String,
    val photoUrl: String? = null,
    val description: String? = null,
    val sku: String? = null,
    val currency: String? = null,
    val defaultUnitAmount: Int,
    val active: Boolean,
    val trackInventory: Boolean,
    val productKind: String? = null,
    val taxTreatment: String,
    val taxRateBps: Int? = null,
    val requiresPrescription: Boolean,
    val controlSchedule: String? = null,
    val stockCatalogItemId: String? = null,
    val stockUnits: Int? = null,
    val inventory: CashInventorySummary? = null,
    val updatedAt: String? = null,
) {
    val isProduct: Boolean get() = itemType == "product"
    val isPack: Boolean get() = isProduct && stockCatalogItemId != null
}

@Serializable
data class CashCatalogUsage(
    val packs: List<Pack>,
    val serviceIds: List<String>,
    val openBills: Int,
    val stockOnHand: Int,
    val prescriptions: Int? = null,
    val hospitalOrders: Int? = null,
) {
    @Serializable
    data class Pack(val id: String, val name: String)
}

// MARK: - Subscription

@Serializable
data class CashBillingSummary(
    val available: Boolean,
    val reachable: Boolean,
    val billingCycle: String? = null,
    val nextCharge: Charge? = null,
    val card: Card? = null,
    val receipts: List<Receipt>,
) {
    @Serializable
    data class Charge(val amount: Int, val currency: String, val date: String? = null)

    @Serializable
    data class Card(val brand: String? = null, val last4: String? = null, val expMonth: Int? = null, val expYear: Int? = null)

    @Serializable
    data class Receipt(
        val id: String,
        val number: String? = null,
        val date: String,
        val amount: Int,
        val currency: String,
        val status: String,
        val url: String? = null,
        val pdfUrl: String? = null,
    )
}

@Serializable
data class CashMember(val id: String)

@Serializable
data class CashInvitation(val id: String, val status: String? = null, val expiresAt: String? = null)
