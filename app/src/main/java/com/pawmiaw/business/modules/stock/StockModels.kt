@file:OptIn(ExperimentalSerializationApi::class)

package com.pawmiaw.business.stock

import com.pawmiaw.business.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

//
//  StockModels.kt (iOS StockModels.swift)
//
//  What Inventory and the Pharmacy read from the server, mirroring the web's
//  hooks (use-provider-store.ts `/provider-stock…`, use-prescriptions.ts
//  `/provider-prescriptions…`, `/provider-pharmacy…`). Anything the server
//  could leave out is nullable, so an older or newer answer never breaks
//  decoding; the few lists and numbers a screen always needs fall back to an
//  empty list or zero (the Swift custom `init(from:)`s: a value of the wrong
//  type reads as absent, exactly as their `try?` does).
//

// MARK: - Inventory

@KeepGeneratedSerializer
@Serializable(with = StockLotSerializer::class)
data class StockLot(
    val id: String,
    val lotCode: String?,
    val expiresOn: String?,
    val quantityOnHand: Int,
    val unitCostAmount: Int?,
    val receivedAt: String?,
)

object StockLotSerializer : KSerializer<StockLot> {
    override val descriptor: SerialDescriptor get() = StockLot.generatedSerializer().descriptor
    override fun serialize(encoder: Encoder, value: StockLot) = StockLot.generatedSerializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): StockLot {
        val c = decoder.stockObject()
        return StockLot(
            id = c.requiredString("id"),
            lotCode = c.strictString("lotCode"),
            expiresOn = c.strictString("expiresOn"),
            quantityOnHand = c.looseInt("quantityOnHand") ?: 0,
            unitCostAmount = c.looseInt("unitCostAmount"),
            receivedAt = c.looseString("receivedAt"),
        )
    }
}

@Serializable
data class StockPack(
    val catalogItemId: String,
    val name: String,
    val stockUnits: Int,
    val unitAmount: Int? = null,
)

/**
 * A product's stock (`/provider-stock`), and — with `inventoryItemId` null —
 * a product a delivery can be received for that is not counted yet
 * (`/provider-stock/products`).
 *
 * The constructor's first parameters are the Swift `init(inventoryItemId:…controlSchedule:)` in its
 * order; the rest default to what that init sets (no price, no backorder, prescription required).
 */
@KeepGeneratedSerializer
@Serializable(with = StockRowSerializer::class)
data class StockRow(
    var inventoryItemId: String?,
    var catalogItemId: String,
    var name: String,
    var sku: String?,
    var barcode: String?,
    var photoUrl: String?,
    var productKind: String?,
    var quantityOnHand: Int,
    /** The count it is low at while its alert is on (its own, or the default of three). */
    var reorderPoint: Int,
    /** Cost and value at cost are business finances: null for anyone else. */
    var unitCostAmount: Int?,
    var lowStock: Boolean,
    var lots: List<StockLot>,
    var packs: List<StockPack>,
    var tracked: Boolean?,
    var controlSchedule: String?,
    var unitAmount: Int? = null,
    var stockValueAmount: Int? = null,
    var allowBackorder: Boolean = false,
    var outOfStock: Boolean? = null,
    var sellableUnits: Int? = null,
    var lowStockAlert: Boolean? = null,
    var expiredUnits: Int? = null,
    var trackLots: Boolean? = null,
    var nextExpiry: String? = null,
    var requiresPrescription: Boolean? = true,
    var storageLocation: String? = null,
    var reorderQuantity: Int? = null,
) {
    val id: String get() = catalogItemId
}

object StockRowSerializer : KSerializer<StockRow> {
    override val descriptor: SerialDescriptor get() = StockRow.generatedSerializer().descriptor
    override fun serialize(encoder: Encoder, value: StockRow) = StockRow.generatedSerializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): StockRow {
        val c = decoder.stockObject()
        return StockRow(
            inventoryItemId = c.looseString("inventoryItemId"),
            catalogItemId = c.requiredString("catalogItemId"),
            name = c.looseString("name") ?: "",
            sku = c.looseString("sku"),
            barcode = c.looseString("barcode"),
            photoUrl = c.looseString("photoUrl"),
            productKind = c.looseString("productKind"),
            unitAmount = c.looseInt("unitAmount"),
            quantityOnHand = c.looseInt("quantityOnHand") ?: 0,
            reorderPoint = c.looseInt("reorderPoint") ?: 0,
            unitCostAmount = c.looseInt("unitCostAmount"),
            stockValueAmount = c.looseInt("stockValueAmount"),
            allowBackorder = c.looseBool("allowBackorder") ?: false,
            lowStock = c.looseBool("lowStock") ?: false,
            outOfStock = c.looseBool("outOfStock"),
            sellableUnits = c.looseInt("sellableUnits"),
            lowStockAlert = c.looseBool("lowStockAlert"),
            expiredUnits = c.looseInt("expiredUnits"),
            trackLots = c.looseBool("trackLots"),
            nextExpiry = c.looseString("nextExpiry"),
            lots = c.loose("lots", ListSerializer(StockLot.serializer())) ?: emptyList(),
            packs = c.loose("packs", ListSerializer(StockPack.serializer())) ?: emptyList(),
            tracked = c.looseBool("tracked"),
            requiresPrescription = c.looseBool("requiresPrescription"),
            controlSchedule = c.looseString("controlSchedule"),
            storageLocation = c.looseString("storageLocation"),
            reorderQuantity = c.looseInt("reorderQuantity"),
        )
    }
}

@Serializable
data class StockMovement(
    val id: String,
    val movementType: String,
    val quantityDelta: Int? = null,
    val quantityAfter: Int? = null,
    val unitCostAmount: Int? = null,
    val notes: String? = null,
    val lotCode: String? = null,
    val folio: String? = null,
    val invoiceId: String? = null,
    val invoiceKind: String? = null,
    val sourceType: String? = null,
    val actorName: String? = null,
    val occurredAt: String? = null,
)

/** A Catalog product as the counter sells it (`/provider-store/products`): the prescription form picks medicines from it. */
@Serializable
data class StockStoreProduct(
    val catalogItemId: String,
    val itemType: String? = null,
    val name: String,
    val sku: String? = null,
    val photoUrl: String? = null,
    val productKind: String? = null,
    val requiresPrescription: Boolean? = null,
    val controlSchedule: String? = null,
    val stock: Stock? = null,
) {
    @Serializable
    data class Stock(
        val inventoryItemId: String? = null,
        val available: Int? = null,
        val lowStock: Boolean? = null,
    )

    val id: String get() = catalogItemId
}

// MARK: - Prescriptions

@Serializable
data class StockFill(
    val allowed: Int? = null,
    val dispensed: Int? = null,
    val remaining: Int? = null,
    val fillsUsed: Int? = null,
    val refillsLeft: Int? = null,
)

@Serializable
data class StockRxProduct(
    val catalogItemId: String? = null,
    val name: String? = null,
    val active: Boolean? = null,
    val packOf: String? = null,
    val unitsPerPack: Int? = null,
)

@KeepGeneratedSerializer
@Serializable(with = StockRxItemSerializer::class)
data class StockRxItem(
    val id: String,
    val catalogItemId: String? = null,
    val medicineName: String = "",
    val activeIngredient: String? = null,
    val presentation: String? = null,
    val strength: String? = null,
    val doseAmount: Double? = null,
    val doseUnit: String? = null,
    val doseRate: Double? = null,
    val doseRateUnit: String? = null,
    val route: String? = null,
    val everyHours: Double? = null,
    val frequencyText: String? = null,
    val asNeeded: Boolean? = null,
    val durationDays: Int? = null,
    val quantity: Int? = null,
    val quantityUnit: String? = null,
    val refills: Int? = null,
    val instructions: String? = null,
    val withdrawalPeriod: String? = null,
    val controlSchedule: String? = null,
    val doseText: String? = null,
    val fill: StockFill? = null,
    val product: StockRxProduct? = null,
    val countBasis: String? = null,
    val closedAt: String? = null,
    val closedByName: String? = null,
    val closeNote: String? = null,
    val handOverBlock: String? = null,
    val nextFill: Int? = null,
)

object StockRxItemSerializer : KSerializer<StockRxItem> {
    override val descriptor: SerialDescriptor get() = StockRxItem.generatedSerializer().descriptor
    override fun serialize(encoder: Encoder, value: StockRxItem) = StockRxItem.generatedSerializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): StockRxItem {
        val c = decoder.stockObject()
        return StockRxItem(
            id = c.requiredString("id"),
            catalogItemId = c.looseString("catalogItemId"),
            medicineName = c.looseString("medicineName") ?: "",
            activeIngredient = c.looseString("activeIngredient"),
            presentation = c.looseString("presentation"),
            strength = c.looseString("strength"),
            doseAmount = c.looseDouble("doseAmount"),
            doseUnit = c.looseString("doseUnit"),
            doseRate = c.looseDouble("doseRate"),
            doseRateUnit = c.looseString("doseRateUnit"),
            route = c.looseString("route"),
            everyHours = c.looseDouble("everyHours"),
            frequencyText = c.looseString("frequencyText"),
            asNeeded = c.looseBool("asNeeded"),
            durationDays = c.looseInt("durationDays"),
            quantity = c.looseInt("quantity"),
            quantityUnit = c.looseString("quantityUnit"),
            refills = c.looseInt("refills"),
            instructions = c.looseString("instructions"),
            withdrawalPeriod = c.looseString("withdrawalPeriod"),
            controlSchedule = c.looseString("controlSchedule"),
            doseText = c.looseString("doseText"),
            fill = c.loose("fill", StockFill.serializer()),
            product = c.loose("product", StockRxProduct.serializer()),
            countBasis = c.looseString("countBasis"),
            closedAt = c.looseString("closedAt"),
            closedByName = c.looseString("closedByName"),
            closeNote = c.looseString("closeNote"),
            handOverBlock = c.looseString("handOverBlock"),
            nextFill = c.looseInt("nextFill"),
        )
    }
}

@Serializable
data class StockDispense(
    val id: String,
    val prescriptionId: String? = null,
    val prescriptionItemId: String? = null,
    val documentFolio: String? = null,
    val catalogItemId: String? = null,
    val label: String? = null,
    val quantity: Int? = null,
    val sold: String? = null,
    val kind: String? = null,
    val controlSchedule: String? = null,
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    val petName: String? = null,
    val clientName: String? = null,
    val prescriberName: String? = null,
    val prescriberCredential: String? = null,
    val dispensedByName: String? = null,
    val occurredAt: String? = null,
    val counselledAt: String? = null,
    val counselNote: String? = null,
    val lots: List<Lot>? = null,
    val prescriptionFolio: String? = null,
) {
    @Serializable
    data class Lot(val code: String? = null, val expiresOn: String? = null, val quantity: Int? = null)
}

@Serializable
data class StockRefill(
    val id: String,
    val prescriptionId: String? = null,
    val prescriptionItemId: String? = null,
    val status: String,
    val note: String? = null,
    val declineReason: String? = null,
    val renewalPrescriptionId: String? = null,
    val origin: String? = null,
    val pickupSource: String? = null,
    val pickupNote: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class StockShare(
    val id: String,
    val createdBySide: String? = null,
    val tokenHint: String? = null,
    val expiresAt: String? = null,
    val viewCount: Int? = null,
    val active: Boolean? = null,
)

@Serializable
data class StockCreatedShare(
    val id: String,
    val token: String? = null,
    val url: String,
    val qrUrl: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class StockSnapshot(
    val folio: String? = null,
    val signedAt: String? = null,
    val validUntil: String? = null,
    val country: String? = null,
    val language: String? = null,
    val legend: String? = null,
    val controlled: Boolean? = null,
    val controlSchedule: String? = null,
    val controlLabel: String? = null,
    val business: Business? = null,
    val prescriber: Prescriber? = null,
    val client: Client? = null,
    val pet: Pet? = null,
    val diagnosis: String? = null,
    val notes: String? = null,
    val items: List<StockRxItem>? = null,
) {
    @Serializable
    data class Business(
        val name: String? = null, val legalName: String? = null, val taxId: String? = null, val senasica: String? = null,
        val address: String? = null, val phone: String? = null, val email: String? = null, val locationName: String? = null,
    )

    @Serializable
    data class Dea(val number: String? = null, val registeredAddress: String? = null)

    @Serializable
    data class Prescriber(
        val name: String? = null, val title: String? = null, val credentialType: String? = null, val credentialNumber: String? = null,
        val credentialRegion: String? = null, val credentialLine: String? = null, val dea: Dea? = null,
    )

    @Serializable
    data class Client(val name: String? = null, val phone: String? = null, val address: String? = null)

    @Serializable
    data class Age(val years: Int? = null, val months: Int? = null)

    @Serializable
    data class Pet(
        val name: String? = null, val species: String? = null, val breed: String? = null, val sex: String? = null,
        val birthDate: String? = null, val age: Age? = null, val weightKg: Double? = null, val microchip: String? = null,
    )
}

/** A prescription: the list's summary, and with the detail's extra fields when read on its own. */
@KeepGeneratedSerializer
@Serializable(with = StockPrescriptionSerializer::class)
data class StockPrescription(
    val id: String,
    val folio: String? = null,
    val status: String = "draft",
    val state: String = "draft",
    val origin: String? = null,
    val providerClientId: String = "",
    val providerClientPetId: String = "",
    val appointmentId: String? = null,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val petPhotoUrl: String? = null,
    val clientName: String? = null,
    val familyLinked: Boolean? = null,
    val prescriberName: String? = null,
    val diagnosis: String? = null,
    val notes: String? = null,
    val validUntil: String? = null,
    val validDays: Int? = null,
    val controlled: Boolean? = null,
    val language: String? = null,
    val signedAt: String? = null,
    val sharedWithFamilyAt: String? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val createdAt: String? = null,
    val expiresSoon: Boolean? = null,
    val items: List<StockRxItem> = emptyList(),
    // The detail only.
    val snapshot: StockSnapshot? = null,
    val integrity: Integrity? = null,
    val dispenses: List<StockDispense>? = null,
    val shares: List<StockShare>? = null,
    val refillRequests: List<StockRefill>? = null,
    val canWrite: Boolean? = null,
    val canSign: Boolean? = null,
    val canCancel: Boolean? = null,
    val canDispenseAtVisit: Boolean? = null,
    val canLogRefill: Boolean? = null,
    val canHandOver: Boolean? = null,
    val canHandOverControlled: Boolean? = null,
    val canSell: Boolean? = null,
    val canCounsel: Boolean? = null,
) {
    @Serializable
    data class Integrity(val verified: Boolean? = null, val sha256: String? = null)

    val isSigned: Boolean get() = status == "signed"
    val isDraft: Boolean get() = status == "draft"
    val medicines: String get() = items.joinToString(", ") { it.medicineName }
}

object StockPrescriptionSerializer : KSerializer<StockPrescription> {
    override val descriptor: SerialDescriptor get() = StockPrescription.generatedSerializer().descriptor
    override fun serialize(encoder: Encoder, value: StockPrescription) = StockPrescription.generatedSerializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): StockPrescription {
        val c = decoder.stockObject()
        return StockPrescription(
            id = c.requiredString("id"),
            folio = c.looseString("folio"),
            status = c.looseString("status") ?: "draft",
            state = c.looseString("state") ?: "draft",
            origin = c.looseString("origin"),
            providerClientId = c.looseString("providerClientId") ?: "",
            providerClientPetId = c.looseString("providerClientPetId") ?: "",
            appointmentId = c.looseString("appointmentId"),
            petName = c.looseString("petName"),
            petSpecies = c.looseString("petSpecies"),
            petBreed = c.looseString("petBreed"),
            petPhotoUrl = c.looseString("petPhotoUrl"),
            clientName = c.looseString("clientName"),
            familyLinked = c.looseBool("familyLinked"),
            prescriberName = c.looseString("prescriberName"),
            diagnosis = c.looseString("diagnosis"),
            notes = c.looseString("notes"),
            validUntil = c.looseString("validUntil"),
            validDays = c.looseInt("validDays"),
            controlled = c.looseBool("controlled"),
            language = c.looseString("language"),
            signedAt = c.looseString("signedAt"),
            sharedWithFamilyAt = c.looseString("sharedWithFamilyAt"),
            cancelledAt = c.looseString("cancelledAt"),
            cancelReason = c.looseString("cancelReason"),
            createdAt = c.looseString("createdAt"),
            expiresSoon = c.looseBool("expiresSoon"),
            items = c.loose("items", ListSerializer(StockRxItem.serializer())) ?: emptyList(),
            snapshot = c.loose("snapshot", StockSnapshot.serializer()),
            integrity = c.loose("integrity", StockPrescription.Integrity.serializer()),
            dispenses = c.loose("dispenses", ListSerializer(StockDispense.serializer())),
            shares = c.loose("shares", ListSerializer(StockShare.serializer())),
            refillRequests = c.loose("refillRequests", ListSerializer(StockRefill.serializer())),
            canWrite = c.looseBool("canWrite"),
            canSign = c.looseBool("canSign"),
            canCancel = c.looseBool("canCancel"),
            canDispenseAtVisit = c.looseBool("canDispenseAtVisit"),
            canLogRefill = c.looseBool("canLogRefill"),
            canHandOver = c.looseBool("canHandOver"),
            canHandOverControlled = c.looseBool("canHandOverControlled"),
            canSell = c.looseBool("canSell"),
            canCounsel = c.looseBool("canCounsel"),
        )
    }
}

@Serializable
data class StockPrescriptionPage(
    val items: List<StockPrescription>,
    val total: Int? = null,
    val hasMore: Boolean? = null,
    val stateCounts: Map<String, Int>? = null,
    val canWrite: Boolean? = null,
    val canSign: Boolean? = null,
)

// MARK: - Pharmacy

@Serializable
data class StockHandOverTarget(
    val prescriptionId: String? = null,
    val prescriptionItemId: String? = null,
    val folio: String? = null,
    val catalogItemId: String? = null,
    val productName: String? = null,
    val remaining: Int? = null,
    val quantity: Int? = null,
    val block: String? = null,
)

@Serializable
data class StockRefillRequest(
    val id: String,
    val prescriptionId: String,
    val prescriptionItemId: String? = null,
    val status: String,
    val note: String? = null,
    val declineReason: String? = null,
    val renewalPrescriptionId: String? = null,
    val origin: String? = null,
    val pickupSource: String? = null,
    val createdAt: String? = null,
    val folio: String? = null,
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petPhotoUrl: String? = null,
    val clientName: String? = null,
    val familyLinked: Boolean? = null,
    val requestedByName: String? = null,
    val loggedByName: String? = null,
    val medicineName: String? = null,
    val strength: String? = null,
    val controlSchedule: String? = null,
    val remaining: Int? = null,
    val refillsLeft: Int? = null,
    val validUntil: String? = null,
    val needsRenewal: Boolean? = null,
    val handOver: StockHandOverTarget? = null,
)

@Serializable
data class StockRefillPage(
    val items: List<StockRefillRequest>,
    val total: Int? = null,
    val hasMore: Boolean? = null,
    val canApprove: Boolean? = null,
    val canMarkReady: Boolean? = null,
)

@Serializable
data class StockHandOverLine(
    val prescriptionItemId: String,
    val medicineName: String? = null,
    val strength: String? = null,
    val quantityUnit: String? = null,
    val controlSchedule: String? = null,
    val catalogItemId: String? = null,
    val productName: String? = null,
    val remaining: Int? = null,
    val quantity: Int? = null,
    val block: String? = null,
)

@Serializable
data class StockHandOverRow(
    val key: String,
    val kind: String? = null,
    val requestId: String? = null,
    val prescriptionId: String,
    val folio: String? = null,
    val providerClientId: String? = null,
    val providerClientPetId: String? = null,
    val appointmentId: String? = null,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petPhotoUrl: String? = null,
    val clientName: String? = null,
    val controlled: Boolean? = null,
    val since: String? = null,
    val origin: String? = null,
    val requestedByName: String? = null,
    val note: String? = null,
    val bill: Bill? = null,
    val lines: List<StockHandOverLine>? = null,
) {
    @Serializable
    data class Bill(val invoiceId: String? = null, val folio: String? = null)

    val id: String get() = key
}

@Serializable
data class StockHandOverPage(
    val items: List<StockHandOverRow>,
    val total: Int? = null,
    val hasMore: Boolean? = null,
)

@Serializable
data class StockPharmacyCounts(
    val toHandOver: Int? = null,
    val refillsWaiting: Int? = null,
    val refillsPending: Int? = null,
    val draftsToSign: Int? = null,
    val expiringSoon: Int? = null,
    val expiringDays: Int? = null,
    val badge: Int? = null,
    val stock: Stock? = null,
    val canWrite: Boolean? = null,
    val canSign: Boolean? = null,
    val canHandOver: Boolean? = null,
    val canHandOverControlled: Boolean? = null,
    val canLogRefill: Boolean? = null,
) {
    @Serializable
    data class Stock(val low: Int? = null, val expiring: Int? = null, val expired: Int? = null, val either: Int? = null, val expiringDays: Int? = null)
}

@Serializable
data class StockRegisterMedicine(
    val catalogItemId: String,
    val inventoryItemId: String? = null,
    val name: String,
    val sku: String? = null,
    val photoUrl: String? = null,
    val active: Boolean? = null,
    val controlSchedule: String? = null,
    val tracked: Boolean? = null,
    val onHand: Int? = null,
    val registerBalance: Int? = null,
    val discrepancy: Discrepancy? = null,
    val movements: Int? = null,
    val lastMovementAt: String? = null,
    val reorderPoint: Int? = null,
    val lowStock: Boolean? = null,
    val lots: List<Lot>? = null,
    val packs: List<StockPack>? = null,
) {
    @Serializable
    data class Discrepancy(val registerBalance: Int? = null, val onHand: Int? = null, val difference: Int? = null)

    @Serializable
    data class Lot(val id: String, val lotCode: String? = null, val expiresOn: String? = null, val quantityOnHand: Int? = null)

    val id: String get() = catalogItemId
}

@Serializable
data class StockRegisterMedicines(
    val today: String? = null,
    val stockIsBusinessWide: Boolean? = null,
    val locations: Int? = null,
    val items: List<StockRegisterMedicine>,
)

@Serializable
data class StockRegisterLine(
    val id: String,
    val occurredAt: String? = null,
    val type: String,
    val quantity: Int? = null,
    val balanceAfter: Int? = null,
    val catalogItemId: String? = null,
    val productName: String? = null,
    val controlSchedule: String? = null,
    val lotCode: String? = null,
    val lotExpiresOn: String? = null,
    val actorName: String? = null,
    val note: String? = null,
    val witnessName: String? = null,
    val locationName: String? = null,
    val documentFolio: String? = null,
    val petName: String? = null,
    val clientName: String? = null,
    val prescriberName: String? = null,
    val prescriberCredential: String? = null,
    val prescriptionId: String? = null,
    val prescriptionFolio: String? = null,
    val dispenseId: String? = null,
    val counselledAt: String? = null,
    val counselNote: String? = null,
    val doseText: String? = null,
)

@Serializable
data class StockControlledRegister(
    val from: String? = null,
    val to: String? = null,
    val medicine: Medicine? = null,
    val openingBalance: Int? = null,
    val closingBalance: Int? = null,
    val unitsIn: Int? = null,
    val unitsOut: Int? = null,
    val discrepancy: StockRegisterMedicine.Discrepancy? = null,
    val truncated: Boolean? = null,
    val typeCounts: Map<String, Int>? = null,
    val total: Int? = null,
    val hasMore: Boolean? = null,
    val items: List<StockRegisterLine>,
) {
    @Serializable
    data class Medicine(val catalogItemId: String? = null, val name: String? = null, val controlSchedule: String? = null, val onHand: Int? = null)
}

@Serializable
data class StockDispenseLog(
    val from: String? = null,
    val to: String? = null,
    val items: List<StockDispense>,
    val total: Int? = null,
    val hasMore: Boolean? = null,
    val canCounsel: Boolean? = null,
)

@Serializable
data class StockPharmacyReport(
    val from: String? = null,
    val to: String? = null,
    val totals: Totals,
    val currency: String? = null,
    val byProduct: List<ByProduct>,
    val byPrescriber: List<ByPrescriber>,
    val refills: Map<String, Int>,
) {
    @Serializable
    data class Totals(
        val dispenses: Int? = null, val units: Int? = null, val controlledUnits: Int? = null, val counselled: Int? = null,
        val prescriptionsSigned: Int? = null, val patients: Int? = null, val revenueAmount: Int? = null,
    )

    @Serializable
    data class ByProduct(
        val label: String, val catalogItemId: String? = null, val units: Int? = null, val dispenses: Int? = null,
        val controlled: Boolean? = null, val counselled: Int? = null, val revenueAmount: Int? = null,
    )

    @Serializable
    data class ByPrescriber(val name: String, val dispenses: Int? = null, val revenueAmount: Int? = null)
}

// MARK: - People the prescription sheet needs

@Serializable
data class StockCredential(
    val id: String,
    val type: String,
    val label: Label? = null,
    val number: String? = null,
    val region: String? = null,
    val prescribesHere: Boolean? = null,
    val allowsControlled: Boolean? = null,
) {
    @Serializable
    data class Label(val es: String? = null, val en: String? = null)
}

@Serializable
data class StockMemberCredentials(
    val country: String? = null,
    val requiredType: String? = null,
    val items: List<StockCredential>,
    val canPrescribe: Boolean? = null,
    val canPrescribeControlled: Boolean? = null,
)

/** The few parts of the patient chart the prescription sheet reads: the weight and the allergies. */
@Serializable
data class StockChart(
    val pet: Pet? = null,
    val client: Client? = null,
    val weights: Weights? = null,
    val summary: Summary? = null,
) {
    @Serializable
    data class Weights(val latestGrams: Int? = null, val latestAt: String? = null)

    @Serializable
    data class Allergy(
        val label: String,
        val draft: Boolean? = null,
        /** What the reaction was, and the record it is written in. */
        var detail: String? = null,
        var recordId: String? = null,
    )

    @Serializable
    data class Summary(val allergies: List<Allergy>? = null)

    @Serializable
    data class Pet(val name: String? = null, val weightGrams: Int? = null, val linked: Boolean? = null, val species: String? = null)

    @Serializable
    data class Client(val name: String? = null, var householdId: String? = null)
}

// MARK: - Reading the Swift custom decoders' way

private fun Decoder.stockObject(): JsonObject =
    (this as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected a JSON object")

/** `try c.decode(String.self, …)`: missing, null or not a string fails the whole decode. */
private fun JsonObject.requiredString(key: String): String = looseString(key) ?: throw SerializationException("Missing $key")

/** `try c.decodeIfPresent(String.self, …)`: absent or null is null; any other type fails the decode. */
private fun JsonObject.strictString(key: String): String? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SerializationException("$key is not a string")
}

/** `try? c.decodeIfPresent(String.self, …)`: a value of another type is null. */
private fun JsonObject.looseString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }

/** An Int as Swift reads one: a whole JSON number (3 or 3.0), nothing else. */
private fun JsonObject.looseInt(key: String): Int? {
    val p = number(key) ?: return null
    p.content.toIntOrNull()?.let { return it }
    val d = p.content.toDoubleOrNull() ?: return null
    return if (d % 1.0 == 0.0 && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
}

private fun JsonObject.looseDouble(key: String): Double? = number(key)?.content?.toDoubleOrNull()

private fun JsonObject.looseBool(key: String): Boolean? = number(key)?.content?.toBooleanStrictOrNull()

/** A nested value or list; one that doesn't read is null (the whole list, as Swift's `try?`). */
private fun <T> JsonObject.loose(key: String, serializer: KSerializer<T>): T? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { API.json.decodeFromJsonElement(serializer, it) }.getOrNull() }
