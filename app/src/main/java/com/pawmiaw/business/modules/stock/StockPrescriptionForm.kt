package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

//
//  StockPrescriptionForm.kt (iOS StockPrescriptionForm.swift)
//
//  The pieces the prescription sheet is built from (web lib/prescriptions.ts,
//  prescriptions/prescription-medicine-form.tsx, credential-sheet.tsx,
//  client-pet-picker): one medicine as typed and the server's rules for it
//  (what a draft needs, what a signature needs), the medicine's form, the
//  credential a signature asks for, and "who is it for".
//

/** Swift `.trimmingCharacters(in: .whitespaces)`: spaces and tabs, not new lines. */
private fun String.trimSpaces(): String = trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** What Swift's `Double(String)` reads: a plain decimal number, with an optional exponent. */
private val swiftNumber = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

/** SwiftUI `.onChange(of: value)`: runs on each change after the first composition. */
@Composable
private fun <T> OnChange(value: T, action: (T) -> Unit) {
    val latest by rememberUpdatedState(action)
    val previous = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (previous[0] != value) {
            previous[0] = value
            latest(value)
        }
    }
}

// MARK: - One medicine as typed

data class StockItemDraft(
    val key: String = UUID.randomUUID().toString().uppercase(),
    val catalogItemId: String? = null,
    val medicineName: String = "",
    val activeIngredient: String = "",
    val presentation: String = "",
    val strength: String = "",
    val doseAmount: String = "",
    val doseUnit: String = "",
    val doseRate: String = "",
    val doseRateUnit: String = "mg/kg",
    val route: String = "oral",
    val everyHours: String = "12",
    val frequencyText: String = "",
    val asNeeded: Boolean = false,
    val durationDays: String = "",
    val quantity: String = "1",
    val quantityUnit: String = "",
    val refills: String = "0",
    val instructions: String = "",
    val withdrawalPeriod: String = "",
    val controlSchedule: String = "",
) {
    val id: String get() = key

    /** The draft back from what the server has (to finish a draft). */
    constructor(item: StockRxItem) : this(
        catalogItemId = item.catalogItemId,
        medicineName = item.medicineName,
        activeIngredient = item.activeIngredient ?: "",
        presentation = item.presentation ?: "",
        strength = item.strength ?: "",
        doseAmount = item.doseAmount?.let { StockDocWords.number(it) } ?: "",
        doseUnit = item.doseUnit ?: "",
        doseRate = item.doseRate?.let { StockDocWords.number(it) } ?: "",
        doseRateUnit = item.doseRateUnit ?: "mg/kg",
        route = item.route ?: "",
        everyHours = item.everyHours?.let { StockDocWords.number(it) } ?: "",
        frequencyText = item.frequencyText ?: "",
        asNeeded = item.asNeeded ?: false,
        durationDays = item.durationDays?.toString() ?: "",
        quantity = (item.quantity ?: 1).toString(),
        quantityUnit = item.quantityUnit ?: "",
        refills = (item.refills ?: 0).toString(),
        instructions = item.instructions ?: "",
        withdrawalPeriod = item.withdrawalPeriod ?: "",
        controlSchedule = item.controlSchedule ?: "",
    )

    companion object {
        /** A number as the server reads it: a comma is the decimal mark. */
        fun number(text: String): Double? {
            val cleaned = text.trimSpaces().replace(",", ".")
            if (cleaned.isEmpty() || !swiftNumber.matches(cleaned)) return null
            val value = cleaned.toDoubleOrNull() ?: return null
            return if (value.isFinite()) value else null
        }
    }

    private val hours: Double? get() = if (asNeeded && everyHours.nonEmpty == null) null else number(everyHours)

    val input: Map<String, Any?>
        get() {
            val dose = number(doseAmount)
            val rate = number(doseRate)
            return mapOf(
                "catalogItemId" to catalogItemId,
                "medicineName" to medicineName.nonEmpty,
                "activeIngredient" to activeIngredient.nonEmpty,
                "presentation" to presentation.nonEmpty,
                "strength" to strength.nonEmpty,
                "doseAmount" to dose,
                "doseUnit" to doseUnit.nonEmpty,
                "doseRate" to rate,
                "doseRateUnit" to (if (rate != null) doseRateUnit.nonEmpty else null),
                "route" to route.nonEmpty,
                "everyHours" to hours,
                "frequencyText" to frequencyText.nonEmpty,
                "asNeeded" to asNeeded,
                "durationDays" to number(durationDays)?.toInt(),
                "quantity" to number(quantity)?.toInt(),
                "quantityUnit" to quantityUnit.nonEmpty,
                "refills" to (number(refills)?.toInt() ?: 0),
                "instructions" to instructions.nonEmpty,
                "withdrawalPeriod" to withdrawalPeriod.nonEmpty,
                "controlSchedule" to controlSchedule.nonEmpty,
            )
        }

    /** "Amoxivet 250 mg tabletas · 1 tableta cada 12 h por 7 días · 14 tabletas". */
    fun summary(language: String): String {
        val name = medicineName.nonEmpty ?: return ""
        val what = listOfNotNull(name, strength.nonEmpty, presentation.nonEmpty).joinToString(" ")
        var dose = doseLine(language)
        val rate = number(doseRate)
        if (dose.isEmpty() && rate != null) dose = "${StockDocWords.number(rate)} $doseRateUnit"
        val qty = number(quantity)?.toInt()
        val dispense = qty?.let { q -> if (quantityUnit.nonEmpty != null || q != 1) "$q${quantityUnit.nonEmpty?.let { " $it" } ?: ""}" else "" } ?: ""
        return listOf(what, dose, dispense).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun doseLine(language: String): String {
        val parts = mutableListOf<String>()
        val es = language != "en"
        number(doseAmount)?.let { dose -> parts.add("${StockDocWords.number(dose)}${doseUnit.nonEmpty?.let { " $it" } ?: ""}") }
        val hours = hours
        if (hours != null) {
            parts.add(if (es) "cada ${StockDocWords.number(hours)} h" else "every ${StockDocWords.number(hours)} h")
        } else {
            frequencyText.nonEmpty?.let { parts.add(it) }
        }
        if (asNeeded) parts.add(if (es) "si es necesario" else "as needed")
        number(durationDays)?.toInt()?.let { days ->
            parts.add(if (es) "por $days ${if (days == 1) "día" else "días"}" else "for $days ${if (days == 1) "day" else "days"}")
        }
        return parts.joinToString(" ")
    }

    val docItem: StockDocItem
        get() = StockDocItem(
            id = key, medicineName = medicineName, strength = strength.nonEmpty, activeIngredient = activeIngredient.nonEmpty, presentation = presentation.nonEmpty,
            doseAmount = number(doseAmount), doseUnit = doseUnit.nonEmpty, doseRate = number(doseRate), doseRateUnit = doseRateUnit.nonEmpty,
            route = route.nonEmpty, everyHours = hours, frequencyText = frequencyText.nonEmpty,
            asNeeded = asNeeded, durationDays = number(durationDays)?.toInt(), quantity = number(quantity)?.toInt() ?: 1,
            quantityUnit = quantityUnit.nonEmpty, refills = number(refills)?.toInt() ?: 0, instructions = instructions.nonEmpty, withdrawalPeriod = withdrawalPeriod.nonEmpty,
        )
}

/** One thing wrong with a medicine, on its field. */
data class StockItemProblem(
    val field: String,
    val message: String,
    /** Stops a draft from being saved too; otherwise it only stops the signature. */
    val blocksDraft: Boolean,
)

object StockRxRules {
    const val maxItems = 10
    const val validDaysMax = 365
    val doseRateUnits = listOf("mg/kg", "UI/kg", "mL/kg", "mcg/kg")
    val routes = listOf("oral", "topical", "ophthalmic", "otic", "subcutaneous", "intramuscular", "intravenous", "inhaled", "rectal", "transdermal", "other")

    fun schedules(country: String?): List<String> = when (country?.uppercase()) {
        "MX" -> listOf("mx_group_1")
        "US" -> listOf("us_c2", "us_c3", "us_c4", "us_c5")
        else -> listOf("controlled_other")
    }

    fun maxRefills(schedule: String?): Int = when (schedule) {
        "us_c2", "mx_group_1", "controlled_other" -> 0
        "us_c3", "us_c4", "us_c5" -> 5
        else -> 12
    }

    /** (defaultDays, maxDays). */
    fun validity(schedule: String?): Pair<Int, Int> = when (schedule) {
        "mx_group_1" -> 30 to 30
        "us_c3", "us_c4", "us_c5" -> 180 to 180
        "us_c2" -> 90 to 180
        "controlled_other" -> 30 to 90
        else -> 180 to validDaysMax
    }

    /** The strictest class on the prescription: it sets how long it is valid. */
    fun strictest(drafts: List<StockItemDraft>): String? =
        listOf("us_c2", "mx_group_1", "controlled_other", "us_c3", "us_c4", "us_c5").firstOrNull { s -> drafts.any { it.controlSchedule == s } }

    fun route(route: String): String = when (route) {
        "oral" -> L("By mouth")
        "topical" -> L("On the skin")
        "ophthalmic" -> L("In the eye")
        "otic" -> L("In the ear")
        "subcutaneous" -> L("Under the skin (SC)")
        "intramuscular" -> L("Into the muscle (IM)")
        "intravenous" -> L("Into a vein (IV)")
        "inhaled" -> L("Inhaled")
        "rectal" -> L("Rectal")
        "transdermal" -> L("Transdermal")
        else -> L("Other@route")
    }

    /** A number typed in the form and why it can't be taken. */
    private fun numberProblem(text: String, whole: Boolean = false, min: Double = Double.MIN_VALUE, max: Double? = null): String? {
        if (text.nonEmpty == null) return null
        val value = StockItemDraft.number(text) ?: return "unreadable"
        if (value < min) return "not_positive"
        if (whole && Math.rint(value) != value) return "not_whole"
        if (max != null && value > max) return "too_large"
        return null
    }

    /** Everything wrong with one medicine, each on its field (web itemDraftProblems). */
    fun problems(d: StockItemDraft, country: String?): List<StockItemProblem> {
        val found = mutableListOf<StockItemProblem>()
        fun add(field: String, message: String, blocksDraft: Boolean) {
            if (found.none { it.field == field }) found.add(StockItemProblem(field = field, message = message, blocksDraft = blocksDraft))
        }
        if (d.medicineName.nonEmpty == null) add("medicineName", L("Name the medicine."), true)
        numberProblem(d.doseAmount)?.let { problem -> add("doseAmount", if (problem == "unreadable") L("Write the dose as a number, like 0.5.") else L("The dose must be more than zero."), true) }
        numberProblem(d.doseRate)?.let { problem -> add("doseRate", if (problem == "unreadable") L("Write the dose by weight as a number, like 10.") else L("The dose by weight must be more than zero."), true) }
        if (numberProblem(d.everyHours, max = 168.0) != null) add("everyHours", L("Write the hours as a number above 0, up to 168 (a week)."), true)
        if (numberProblem(d.durationDays, whole = true, min = 1.0, max = 730.0) != null) add("durationDays", L("The duration is a number of days from 1 to 730."), true)
        if (numberProblem(d.quantity, whole = true, min = 1.0, max = 10_000.0) != null) add("quantity", L("Say how much to dispense (a whole number from 1)."), true)
        if (numberProblem(d.refills, whole = true, min = 0.0, max = 12.0) != null) {
            add("refills", L("Refills are a whole number from 0 to 12."), true)
        } else if ((StockItemDraft.number(d.refills) ?: 0.0).toInt() > maxRefills(d.controlSchedule.nonEmpty)) {
            add("refills", L("This controlled medicine allows fewer refills."), true)
        }
        val schedule = d.controlSchedule.nonEmpty
        if (schedule != null && !schedules(country).contains(schedule)) add("controlSchedule", L("That controlled class isn’t used in this business’s country."), true)

        if (country?.uppercase() == "MX" && d.activeIngredient.nonEmpty == null) add("activeIngredient", L("Add the active ingredient (NOM-064 asks for it next to the commercial name)."), false)
        if (StockItemDraft.number(d.doseAmount) == null && StockItemDraft.number(d.doseRate) == null && d.instructions.nonEmpty == null) add("doseAmount", L("Add the dose."), false)
        val hours = if (d.asNeeded && d.everyHours.nonEmpty == null) null else StockItemDraft.number(d.everyHours)
        if (hours == null && d.frequencyText.nonEmpty == null && !d.asNeeded) add("everyHours", L("Say how often it is given."), false)
        if (StockItemDraft.number(d.quantity) == null) add("quantity", L("Say how much to dispense (a whole number from 1)."), false)
        return found
    }

    /** Each side is (catalog, name, active). */
    fun sameMedicine(a: Triple<String?, String, String>, b: Triple<String?, String, String>): Boolean {
        fun key(value: String) = value.trimSpaces().lowercase()
        val id = a.first
        if (id != null && id == b.first) return true
        if (key(a.second).isNotEmpty() && key(a.second) == key(b.second)) return true
        return key(a.third).isNotEmpty() && key(a.third) == key(b.third)
    }

    /**
     * The chart's allergies a medicine looks like (web lib/allergy-match.ts): its name and active
     * ingredient against each trigger, through the curated families of drugs and foods.
     */
    fun allergyConflicts(d: StockItemDraft, sources: List<StockAllergySource>): List<StockAllergyConflict> {
        val candidate = listOf(d.medicineName, d.activeIngredient).filter { it.isNotEmpty() }.joinToString(" ")
        return StockAllergyMatch.findConflicts(candidateName = candidate, allergies = sources)
    }

    /** The triggers of the chart's allergies (by their labels) a medicine looks like. */
    @JvmName("allergyConflictLabels")
    fun allergyConflicts(d: StockItemDraft, allergies: List<String>): List<String> =
        allergyConflicts(d, sources = allergies.map { StockAllergySource(trigger = it) }).map { it.trigger }
}

// MARK: - The medicine's form

@Composable
fun StockMedicineForm(
    item: MutableState<StockItemDraft>,
    country: String?,
    weightGrams: Int?,
    problems: List<StockItemProblem>,
    showDraftProblems: Boolean,
    showSignProblems: Boolean,
    allergies: List<String>,
    form: FormState,
    /** The chart's allergies with their records (the web's sources); the labels above when null. */
    allergySources: List<StockAllergySource>? = null,
    modifier: Modifier = Modifier,
) {
    val draft = item.value
    // Swift `.onAppear` (once): from the Catalog unless a name was written in.
    var fromCatalog by remember { mutableStateOf(draft.catalogItemId != null || draft.medicineName.nonEmpty == null) }
    val query = remember { mutableStateOf("") }
    var products by remember { mutableStateOf<List<StockStoreProduct>>(emptyList()) }
    var stockOf by remember { mutableStateOf<StockStoreProduct.Stock?>(null) }
    var pickedPhoto by remember { mutableStateOf<String?>(null) }

    val mexico = country?.uppercase() == "MX"
    val refillCap = StockRxRules.maxRefills(draft.controlSchedule.nonEmpty)

    fun key(field: String): String = "${draft.key}-$field"

    fun fieldValue(field: String): String = when (field) {
        "doseAmount" -> draft.doseAmount
        "doseRate" -> draft.doseRate
        "everyHours" -> draft.everyHours
        "durationDays" -> draft.durationDays
        "quantity" -> draft.quantity
        "refills" -> draft.refills
        "activeIngredient" -> draft.activeIngredient
        "controlSchedule" -> draft.controlSchedule
        else -> draft.medicineName
    }

    /** The message under a field: a number that can't be read shows at once, the rest once someone tried. */
    fun error(field: String): String? {
        form.error(key(field))?.let { return it }
        val problem = problems.firstOrNull { it.field == field } ?: return null
        val typed = fieldValue(field).trimSpaces().isNotEmpty()
        val immediate = problem.blocksDraft && typed && field != "medicineName"
        if (!(immediate || (if (problem.blocksDraft) showDraftProblems else showSignProblems))) return null
        return problem.message
    }

    // Swift `.onChange(of: fromCatalog)`: writing it in lets go of the Catalog product and keeps its name to edit.
    fun setFromCatalog(catalog: Boolean) {
        if (catalog == fromCatalog) return
        fromCatalog = catalog
        if (!catalog && item.value.catalogItemId != null) item.value = item.value.copy(catalogItemId = null)
        if (catalog && item.value.catalogItemId == null) query.value = item.value.medicineName.trimSpaces()
    }

    fun choose(product: StockStoreProduct) {
        // The class comes with a controlled product, and a controlled medicine has no refills.
        val schedule = product.controlSchedule?.takeIf { it in listOf("mx_group_1", "us_c2", "us_c3", "us_c4", "us_c5", "controlled_other") } ?: ""
        var next = item.value.copy(catalogItemId = product.catalogItemId, medicineName = product.name, controlSchedule = schedule)
        if (schedule.isNotEmpty()) next = next.copy(refills = "0")
        item.value = next
        stockOf = product.stock
        pickedPhoto = product.photoUrl
        query.value = ""
        form.clear(key("medicineName"))
    }

    // A class with fewer refills brings the number down to what it allows.
    OnChange(draft.controlSchedule) { value ->
        if (value.isNotEmpty()) {
            val refills = minOf((StockItemDraft.number(item.value.refills) ?: 0.0).toInt(), StockRxRules.maxRefills(value))
            item.value = item.value.copy(refills = refills.toString())
        }
    }

    LaunchedEffect(query.value) {
        val text = query.value.trimSpaces()
        if (text.length < 2) {
            products = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val found: List<StockStoreProduct> = try {
            API.get("/provider-store/products", mapOf("q" to text))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        products = found.filter { it.itemType == "product" }.take(6)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ModalSection(title = L("Medicine")) {
            val whereLabel = L("Where the medicine comes from")
            PillPicker(
                options = listOf(true to L("From the Catalog"), false to L("Write it in")),
                selection = stateOf({ fromCatalog }, { setFromCatalog(it) }),
                modifier = Modifier.semantics { contentDescription = whereLabel },
            )
            if (fromCatalog && draft.catalogItemId != null) {
                val box = RoundedCornerShape(Theme.boxRadius)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.surface, box)
                        .border(2.dp, Theme.borderSoft, box)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StockProductThumb(photoUrl = pickedPhoto, productKind = "medication")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(draft.medicineName, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            FlowLayout(spacing = 6.dp) {
                                StockRxChip(text = L("from the Catalog"), tone = Theme.blue, icon = "package")
                                CatalogStockChip(stockOf)
                                StockRxWords.schedule(draft.controlSchedule.nonEmpty)?.let { StockRxControlledChip(label = it) }
                            }
                        }
                    }
                    PMButton(L("Type it instead"), systemImage = "pen-line", variant = ButtonVariant.outline) { setFromCatalog(false) }
                    Text(L("Its name comes from the Catalog, so the counter and the bill can hand it out against this prescription."), style = Theme.text(12), color = Theme.inkSoft)
                }
            } else if (fromCatalog) {
                FormField(label = L("Find in the Catalog"), key = key("medicineName"), hint = L("A Catalog product can be dispensed at the counter and on the bill."), form = form) {
                    SearchField(prompt = L("Search a product"), text = query)
                }
                if (products.isNotEmpty()) {
                    ListCard(style = ListCardStyle.box) {
                        products.forEachIndexed { index, product ->
                            if (index > 0) RowDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .plainButton { choose(product) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StockProductThumb(photoUrl = product.photoUrl, productKind = product.productKind ?: "medication", size = 40.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(product.name, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    FlowLayout(spacing = 4.dp) {
                                        if (product.requiresPrescription == true) StockRxChip(text = L("Prescription only"))
                                        if (product.controlSchedule.nonEmpty != null) StockRxControlledChip()
                                        CatalogStockChip(product.stock)
                                    }
                                }
                            }
                        }
                    }
                } else if (query.value.trimSpaces().length >= 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(LF("No product matches “%@”.", query.value.trimSpaces()), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.weight(1f, fill = false))
                        PMButton(L("Write it in"), variant = ButtonVariant.link, size = ButtonSize.modal) {
                            if (item.value.medicineName.nonEmpty == null) item.value = item.value.copy(medicineName = query.value.trimSpaces())
                            setFromCatalog(false)
                        }
                    }
                }
            } else {
                PMTextField(L("Medicine (commercial name)"), text = item.map({ it.medicineName }, { d, v -> d.copy(medicineName = v) }), key = key("medicineName"), form = form, required = true)
                val message = error("medicineName")
                if (message != null && form.error(key("medicineName")) == null) FieldError(message)
            }
            val conflicts = StockRxRules.allergyConflicts(draft, sources = allergySources ?: allergies.map { StockAllergySource(trigger = it) }).map { it.trigger }
            if (conflicts.isNotEmpty()) {
                NoticeCard(
                    tone = NoticeCard.Tone.problem,
                    title = LF("It looks like an allergy on the chart: %@.", conflicts.joinToString(", ")),
                    message = L("Check it before you sign. It can still be prescribed."),
                    icon = "triangle-alert",
                )
            }
        }

        ModalSection(title = L("What it is")) {
            PMTextField(
                L("Active ingredient"),
                text = item.map({ it.activeIngredient }, { d, v -> d.copy(activeIngredient = v) }),
                key = key("activeIngredient"),
                form = form,
                hint = if (mexico) L("NOM-064 asks for it next to the commercial name.") else null,
                optional = !mexico,
            )
            val message = error("activeIngredient")
            if (message != null && form.error(key("activeIngredient")) == null) FieldError(message)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                PMTextField(L("Strength"), text = item.map({ it.strength }, { d, v -> d.copy(strength = v) }), prompt = "250 mg", modifier = Modifier.weight(1f))
                PMTextField(L("Form"), text = item.map({ it.presentation }, { d, v -> d.copy(presentation = v) }), prompt = L("e.g. tablets"), modifier = Modifier.weight(1f))
            }
        }

        ModalSection(title = L("How much"), subtitle = L("To sign: the dose, the dose by weight, or the instructions for the family.")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                StockRxNumberField(label = L("Dose"), text = item.map({ it.doseAmount }, { d, v -> d.copy(doseAmount = v) }), key = key("doseAmount"), form = form, prompt = "1", decimal = true, modifier = Modifier.weight(1f))
                PMTextField(L("Unit"), text = item.map({ it.doseUnit }, { d, v -> d.copy(doseUnit = v) }), prompt = L("tablet, mL, drops"), modifier = Modifier.weight(1f))
            }
            error("doseAmount")?.let { if (form.error(key("doseAmount")) == null) FieldError(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                StockRxNumberField(label = L("Dose by weight"), text = item.map({ it.doseRate }, { d, v -> d.copy(doseRate = v) }), key = key("doseRate"), form = form, prompt = "10", decimal = true, modifier = Modifier.weight(1f))
                MenuField(
                    label = L("Per"),
                    options = StockRxRules.doseRateUnits.map { it to it },
                    selection = item.map({ it.doseRateUnit }, { d, v -> d.copy(doseRateUnit = v) }),
                    modifier = Modifier.weight(1f),
                )
            }
            error("doseRate")?.let { if (form.error(key("doseRate")) == null) FieldError(it) }
            CompositionLocalProvider(LocalContentColor provides Theme.inkSoft) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Glyph("scale", 16.dp, modifier = Modifier.padding(top = 2.dp))
                    Text(doseMaths(draft, weightGrams, mexico), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }

        ModalSection(title = L("How it is given")) {
            MenuField(
                label = L("Route"),
                options = StockRxRules.routes.map { it to StockRxRules.route(it) },
                selection = item.map({ it.route }, { d, v -> d.copy(route = v) }),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                StockRxNumberField(label = L("Every"), text = item.map({ it.everyHours }, { d, v -> d.copy(everyHours = v) }), key = key("everyHours"), form = form, prompt = "12", suffix = L("hours"), decimal = true, modifier = Modifier.weight(1f))
                StockRxNumberField(label = L("For"), text = item.map({ it.durationDays }, { d, v -> d.copy(durationDays = v) }), key = key("durationDays"), form = form, prompt = "7", optional = true, suffix = L("days"), modifier = Modifier.weight(1f))
            }
            val timing = error("everyHours") ?: error("durationDays")
            if (timing != null && form.error(key("everyHours")) == null && form.error(key("durationDays")) == null) FieldError(timing)
            ToggleRow(
                title = L("As needed"),
                subtitle = if (draft.asNeeded && draft.everyHours.nonEmpty == null) L("It prints “as needed”, with no fixed interval.")
                else if (draft.asNeeded) L("It prints the interval and “as needed”. Empty “Every” if there is no fixed interval.")
                else L("Given only when the pet needs it, not on a fixed schedule."),
                isOn = item.map({ it.asNeeded }, { d, v -> d.copy(asNeeded = v) }),
            )
        }

        ModalSection(title = L("How much to dispense")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                StockRxNumberField(label = L("Quantity"), text = item.map({ it.quantity }, { d, v -> d.copy(quantity = v) }), key = key("quantity"), form = form, prompt = "1", modifier = Modifier.weight(1f))
                PMTextField(L("Of"), text = item.map({ it.quantityUnit }, { d, v -> d.copy(quantityUnit = v) }), prompt = L("boxes, tablets"), modifier = Modifier.weight(1f))
            }
            error("quantity")?.let { if (form.error(key("quantity")) == null) FieldError(it) }
            Disabled(refillCap == 0 && (draft.refills.nonEmpty == null || draft.refills.trimSpaces() == "0")) {
                StockRxNumberField(
                    label = L("Refills"),
                    text = item.map({ it.refills }, { d, v -> d.copy(refills = v) }),
                    key = key("refills"),
                    form = form,
                    prompt = "0",
                    hint = if (refillCap == 0) L("This class allows no refills.") else if (refillCap < 12) LF("Up to %d for this class", refillCap) else L("How many more times it can be filled, up to 12."),
                )
            }
            error("refills")?.let { if (form.error(key("refills")) == null) FieldError(it) }
        }

        ModalSection(title = L("Controlled")) {
            MenuField(
                label = L("Controlled medicine"),
                options = listOf("" to (if (mexico) L("Not controlled (Group II or III)") else L("Not controlled"))) +
                    StockRxRules.schedules(country).map { it to (StockRxWords.schedule(it) ?: it) },
                selection = item.map({ it.controlSchedule }, { d, v -> d.copy(controlSchedule = v) }),
                key = key("controlSchedule"),
                form = form,
                optional = true,
            )
            error("controlSchedule")?.let { FieldError(it) }
            val schedule = draft.controlSchedule.nonEmpty
            if (schedule != null) {
                val (defaultDays, maxDays) = StockRxRules.validity(schedule)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(L("What this class asks of the prescription:"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                    val lines = listOf(
                        L("A prescription of its own: no other medicine goes with it."),
                        if (refillCap == 0) L("No refills.") else LF("Up to %d refills.", refillCap),
                        if (defaultDays == maxDays) LF("Valid for %d days.", maxDays) else LF("Valid for %d days unless you set fewer or more, up to %d.", defaultDays, maxDays),
                        L("It prints as an original and a copy."),
                        L("A controlled prescription goes on paper, signed by hand — it can’t be sent as a link."),
                    ) + (if (schedule == "mx_group_1") listOf(L("Group I goes on the official prescription pad the health authority assigns; the pharmacy keeps the original.")) else emptyList()) +
                        (if (country?.uppercase() == "US") listOf(L("A controlled medicine needs your DEA number on the prescription.")) else emptyList())
                    lines.distinct().forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Glyph("check", 16.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp))
                            Text(line, style = Theme.text(14), color = Theme.inkStrong)
                        }
                    }
                }
            } else {
                Text(L("Choose its class if it is a controlled medicine. Most are not."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }

        ModalSection(title = L("For the family")) {
            PMTextField(
                L("Instructions for the family"),
                text = item.map({ it.instructions }, { d, v -> d.copy(instructions = v) }),
                prompt = L("e.g. Give with food. Finish the course."),
                hint = LF("%d of 1000 characters", draft.instructions.length),
                optional = true,
                multiline = true,
            )
            PMTextField(L("Withdrawal time"), text = item.map({ it.withdrawalPeriod }, { d, v -> d.copy(withdrawalPeriod = v) }), hint = L("For animals whose meat, milk or eggs are consumed."), optional = true)
        }
    }
}

/** Web `FieldError`: 14 semibold in the destructive red. */
@Composable
private fun FieldError(message: String) {
    Text(message, style = Theme.text(14, FontWeight.SemiBold), color = Theme.destructive)
}

/** What the shelf holds of the Catalog product (web `CatalogStockChip`). */
@Composable
private fun CatalogStockChip(stock: StockStoreProduct.Stock?) {
    val available = stock?.available ?: return
    when {
        available <= 0 -> StockRxChip(text = L("Out of stock"), tone = Theme.destructive, strong = true)
        stock.lowStock == true -> StockRxChip(text = LF("Low stock: %d", available), tone = Theme.mango, strong = true)
        else -> StockRxChip(text = LF("In stock: %d", available), tone = Theme.lime, strong = true)
    }
}

private fun doseMaths(item: StockItemDraft, weightGrams: Int?, mexico: Boolean): String {
    val kilos = weightGrams?.let { it.toDouble() / 1000 }
    val rate = StockItemDraft.number(item.doseRate)
    if (rate != null && rate > 0 && kilos != null && kilos > 0) {
        val total = Math.round(rate * kilos * 1000).toDouble() / 1000
        val unit = item.doseRateUnit.replace("/kg", "")
        return LF("%@ %@ × %@ kg = %@ %@", StockDocWords.number(rate), item.doseRateUnit, StockDocWords.number(kilos), StockDocWords.number(total), unit) + " " + L("For reference: the dose you typed is the one that prints.")
    }
    if (kilos != null) {
        return if (mexico) LF("NOM-064 asks for the dose by weight (mg/kg or UI/kg). Type it and the dose for %@ kg is worked out here.", StockDocWords.number(kilos))
        else LF("Type a dose by weight and the dose for %@ kg is worked out here.", StockDocWords.number(kilos))
    }
    return L("No weight on file, so a dose by weight can’t be worked out here.")
}

// MARK: - The credential a signature asks for

// MARK: - Who it is for

/** The client's pet a prescription is written for. */
data class StockRxPet(
    val clientId: String,
    val petId: String,
    val petName: String,
    val species: String? = null,
    val photoUrl: String? = null,
    val clientName: String? = null,
)
