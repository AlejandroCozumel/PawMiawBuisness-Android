package com.pawmiaw.business.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

//
//  StockPrescriptionDocument.kt (iOS StockPrescriptionDocument.swift)
//
//  A prescription as paper (web prescriptions/prescription-document.tsx), in
//  the prescription's own language (the business's), with every field its
//  country asks for: the vet, the credential (cédula profesional, state
//  license, DEA), the owner, the animal, each medicine with its active
//  ingredient, dose, dose by weight, route, frequency, duration, quantity,
//  refills and withdrawal time, the legal legend and the signature line. A
//  controlled one prints as an original and a copy. The signed snapshot is
//  the server's; the review step shows a preview built from the form.
//

/** One medicine as the document prints it. */
data class StockDocItem(
    val id: String,
    val medicineName: String,
    val strength: String?,
    val activeIngredient: String?,
    val presentation: String?,
    val doseAmount: Double?,
    val doseUnit: String?,
    val doseRate: Double?,
    val doseRateUnit: String?,
    val route: String?,
    val everyHours: Double?,
    val frequencyText: String?,
    val asNeeded: Boolean,
    val durationDays: Int?,
    val quantity: Int,
    val quantityUnit: String?,
    val refills: Int,
    val instructions: String?,
    val withdrawalPeriod: String?,
) {
    constructor(item: StockRxItem) : this(
        id = item.id, medicineName = item.medicineName, strength = item.strength, activeIngredient = item.activeIngredient,
        presentation = item.presentation, doseAmount = item.doseAmount, doseUnit = item.doseUnit, doseRate = item.doseRate,
        doseRateUnit = item.doseRateUnit, route = item.route, everyHours = item.everyHours, frequencyText = item.frequencyText,
        asNeeded = item.asNeeded ?: false, durationDays = item.durationDays, quantity = item.quantity ?: 1, quantityUnit = item.quantityUnit,
        refills = item.refills ?: 0, instructions = item.instructions, withdrawalPeriod = item.withdrawalPeriod,
    )
}

/** Everything above and below the medicines. */
class StockDocHeader(language: String, country: String?) {
    var folio: String? = null
    var signedAt: String? = null
    var validUntil: String? = null
    var country: String? = country?.uppercase()
    var language: String = language
    var legend: String? = null
    var controlled: Boolean = false
    var controlLabel: String? = null
    var businessName: String = ""
    var businessLegalName: String? = null
    var businessLine: String = ""
    var senasica: String? = null
    var prescriberName: String = ""
    var prescriberTitle: String? = null
    var credentialLine: String? = null
    var deaNumber: String? = null
    var deaAddress: String? = null
    var clientName: String? = null
    var clientAddress: String? = null
    var petName: String? = null
    var species: String? = null
    var breed: String? = null
    var sex: String? = null
    var age: String? = null
    var weightKg: Double? = null
    var microchip: String? = null
    var diagnosis: String? = null
    var notes: String? = null

    constructor(snapshot: StockSnapshot) : this(if (snapshot.language == "en") "en" else "es", snapshot.country) {
        val s = snapshot
        folio = s.folio; signedAt = s.signedAt; validUntil = s.validUntil
        legend = s.legend; controlled = s.controlled ?: false; controlLabel = s.controlLabel
        businessName = s.business?.name ?: ""; businessLegalName = s.business?.legalName
        businessLine = listOf(s.business?.address, s.business?.phone, s.business?.email).mapNotNull { it.nonEmpty }.joinToString(" · ")
        senasica = s.business?.senasica
        prescriberName = s.prescriber?.name ?: ""; prescriberTitle = s.prescriber?.title; credentialLine = s.prescriber?.credentialLine
        deaNumber = s.prescriber?.dea?.number; deaAddress = s.prescriber?.dea?.registeredAddress
        clientName = s.client?.name; clientAddress = s.client?.address
        petName = s.pet?.name; species = s.pet?.species; breed = s.pet?.breed; sex = s.pet?.sex
        age = StockDocWords.age(years = s.pet?.age?.years, months = s.pet?.age?.months, language = this.language)
        weightKg = s.pet?.weightKg; microchip = s.pet?.microchip; diagnosis = s.diagnosis; notes = s.notes
    }
}

/** The document's own words, in the prescription's language (web prescription-document.tsx `L`). */
object StockDocWords {
    fun word(key: String, language: String): String {
        val es = language != "en"
        return when (key) {
            "prescription" -> if (es) "Receta médica veterinaria" else "Veterinary prescription"
            "folio" -> if (es) "Folio" else "No."
            "date" -> if (es) "Fecha" else "Date"
            "validUntil" -> if (es) "Vigente hasta" else "Valid until"
            "vet" -> if (es) "Médico veterinario" else "Veterinarian"
            "cedula" -> "Cédula profesional"
            "license" -> if (es) "Licencia" else "License"
            "address" -> if (es) "Domicilio" else "Address"
            "senasica" -> if (es) "Registro SENASICA" else "SENASICA registration"
            "owner" -> if (es) "Propietario" else "Client"
            "animal" -> if (es) "Paciente" else "Patient"
            "species" -> if (es) "Especie" else "Species"
            "sex" -> if (es) "Sexo" else "Sex"
            "age" -> if (es) "Edad" else "Age"
            "weight" -> if (es) "Peso" else "Weight"
            "microchip" -> "Microchip"
            "diagnosis" -> if (es) "Diagnóstico" else "Diagnosis"
            "active" -> if (es) "Principio activo" else "Active ingredient"
            "presentation" -> if (es) "Presentación" else "Form"
            "dose" -> if (es) "Dosis" else "Dose"
            "doseRate" -> if (es) "Dosis por peso" else "Dose by weight"
            "route" -> if (es) "Vía" else "Route"
            "frequency" -> if (es) "Frecuencia" else "Frequency"
            "duration" -> if (es) "Duración" else "Duration"
            "quantity" -> if (es) "Cantidad a surtir" else "Quantity to dispense"
            "refills" -> if (es) "Resurtidos" else "Refills"
            "instructions" -> if (es) "Indicaciones" else "Directions"
            "withdrawal" -> if (es) "Tiempo de retiro" else "Withdrawal time"
            "notes" -> if (es) "Notas" else "Notes"
            "signature" -> if (es) "Firma del médico veterinario" else "Veterinarian’s signature"
            "original" -> if (es) "ORIGINAL — para la farmacia" else "ORIGINAL — for the pharmacy"
            "copy" -> if (es) "COPIA — para el expediente del médico" else "COPY — for the veterinarian’s record"
            "controlled" -> if (es) "Medicamento controlado" else "Controlled substance"
            "cuantificada" -> if (es) "Receta cuantificada (Grupo I): el original lo retiene la farmacia que surte; la copia la conserva el médico veterinario. Debe hacerse en el recetario oficial que la autoridad sanitaria asigna." else "Quantified prescription (Group I): the dispensing pharmacy keeps the original; the veterinarian keeps the copy."
            "usPaper" -> if (es) "Esta receta se firma a mano; no se puede enviar electrónicamente sin software certificado para controlados." else "This prescription must be signed by hand; it cannot be sent electronically without EPCS-certified software."
            "dea" -> "DEA"
            "deaAddress" -> if (es) "Domicilio registrado ante la DEA" else "DEA-registered address"
            "noRefills" -> if (es) "Sin resurtidos" else "No refills"
            "days" -> if (es) "días" else "days"
            "every" -> if (es) "cada" else "every"
            "asNeeded" -> if (es) "si es necesario" else "as needed"
            "verified" -> if (es) "Integridad verificada" else "Integrity verified"
            "fingerprint" -> if (es) "Huella" else "Fingerprint"
            "review" -> if (es) "Formato basado en fuentes públicas; debe revisarlo un profesional antes de usarse." else "Format based on public sources; a professional must review it before use."
            "preview" -> if (es) "VISTA PREVIA — sin firmar" else "PREVIEW — not signed"
            "previewNote" -> if (es) "El folio, la fecha, tu credencial y la leyenda legal se agregan al firmar." else "The number, the date, your credential and the legal legend are added when you sign."
            "cancelled" -> if (es) "Cancelada" else "Cancelled"
            else -> key
        }
    }

    fun route(route: String?, language: String): String? {
        if (route == null) return null
        val es = mapOf("oral" to "Oral", "topical" to "Tópica", "ophthalmic" to "Oftálmica", "otic" to "Ótica", "subcutaneous" to "Subcutánea", "intramuscular" to "Intramuscular", "intravenous" to "Intravenosa", "inhaled" to "Inhalada", "rectal" to "Rectal", "transdermal" to "Transdérmica", "other" to "Otra")
        val en = mapOf("oral" to "Oral (PO)", "topical" to "Topical", "ophthalmic" to "Ophthalmic", "otic" to "Otic", "subcutaneous" to "Subcutaneous (SC)", "intramuscular" to "Intramuscular (IM)", "intravenous" to "Intravenous (IV)", "inhaled" to "Inhaled", "rectal" to "Rectal", "transdermal" to "Transdermal", "other" to "Other")
        return (if (language == "en") en else es)[route] ?: route
    }

    fun species(value: String?, language: String): String? {
        val v = value.nonEmpty ?: return null
        val es = mapOf("dog" to "Perro", "cat" to "Gato", "bird" to "Ave", "rabbit" to "Conejo", "horse" to "Caballo", "reptile" to "Reptil", "other" to "Otra")
        val en = mapOf("dog" to "Dog", "cat" to "Cat", "bird" to "Bird", "rabbit" to "Rabbit", "horse" to "Horse", "reptile" to "Reptile", "other" to "Other")
        return (if (language == "en") en else es)[v.lowercase()] ?: v
    }

    fun sex(value: String?, language: String): String? {
        val v = value.nonEmpty ?: return null
        return when (v.lowercase()) {
            "female" -> if (language == "en") "Female" else "Hembra"
            "male" -> if (language == "en") "Male" else "Macho"
            else -> "—"
        }
    }

    fun age(years: Int?, months: Int?, language: String): String? {
        if (years == null && months == null) return null
        val y = years ?: 0
        val m = months ?: 0
        if (language == "en") {
            return if (y > 0) "$y ${if (y == 1) "year" else "years"}${if (m > 0) " $m ${if (m == 1) "month" else "months"}" else ""}" else "$m ${if (m == 1) "month" else "months"}"
        }
        return if (y > 0) "$y ${if (y == 1) "año" else "años"}${if (m > 0) " $m ${if (m == 1) "mes" else "meses"}" else ""}" else "$m ${if (m == 1) "mes" else "meses"}"
    }

    /** A whole number without its decimals; otherwise C's `%g` (six significant digits, no trailing zeros). */
    fun number(value: Double): String {
        if (value.isFinite() && Math.rint(value) == value && abs(value) < 9.0e18) return value.toLong().toString()
        if (!value.isFinite()) return if (value.isNaN()) "nan" else if (value > 0) "inf" else "-inf"
        val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_EVEN))
        val exponent = rounded.precision() - rounded.scale() - 1
        if (exponent < -4 || exponent >= 6) {
            val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
            val sign = if (exponent < 0) "-" else "+"
            return "${mantissa}e$sign${abs(exponent).toString().padStart(2, '0')}"
        }
        return rounded.stripTrailingZeros().toPlainString()
    }

    /** "26 de septiembre de 2026" for a day, in the prescription's language. */
    fun longDay(value: String?, language: String): String {
        val raw = value.nonEmpty ?: return "—"
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(if (language == "en") Locale.US else Locale("es", "MX"))
        if (raw.length == 10) {
            val day = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return "—"
            return formatter.format(day)
        }
        val date = DateText.parse(raw) ?: return "—"
        return formatter.format(date.atZone(Workspace.shared.timeZone).toLocalDate())
    }
}

/** The paper's own corner (web `.prescription-document`, 1.25rem): no shared token is 20. */
private val paperRadius: Dp = 20.dp

/** One page of the prescription (or, for a controlled one, the original and the copy). */
@Composable
fun StockPrescriptionDocument(
    header: StockDocHeader,
    items: List<StockDocItem>,
    preview: Boolean = false,
    cancelled: Boolean = false,
    integrity: StockPrescription.Integrity? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (header.controlled && !preview) {
            DocPage(header, items, preview, cancelled, integrity, copy = "original")
            DocPage(header, items, preview, cancelled, integrity, copy = "copy")
        } else {
            DocPage(header, items, preview, cancelled, integrity, copy = null)
        }
    }
}

@Composable
private fun DocPage(
    header: StockDocHeader,
    items: List<StockDocItem>,
    preview: Boolean,
    cancelled: Boolean,
    integrity: StockPrescription.Integrity?,
    copy: String?,
) {
    fun w(key: String): String = StockDocWords.word(key, header.language)
    val mx = header.country == "MX"
    val credentialLabel = if (mx) w("cedula") else w("license")
    val shape = RoundedCornerShape(paperRadius)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .clip(shape)
            .border(1.dp, Theme.borderSoft, shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides Theme.inkStrong) {
            Column(Modifier.fillMaxWidth()) {
                if (preview) DocBand(w("preview"), color = Theme.mango, percent = 18.0)
                if (copy != null) DocBand(if (copy == "original") w("original") else w("copy"), color = Theme.coral, percent = 12.0)
                // The business and the folio.
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(header.businessLegalName.nonEmpty ?: header.businessName, style = Theme.heading(20), color = Theme.inkStrong)
                    val legal = header.businessLegalName.nonEmpty
                    if (legal != null && legal != header.businessName) Text(header.businessName, style = Theme.text(14))
                    if (header.businessLine.isNotEmpty()) Text(header.businessLine, style = Theme.text(12), color = Theme.inkSoft)
                    val senasica = header.senasica.nonEmpty
                    if (mx && senasica != null) Text("${w("senasica")}: $senasica", style = Theme.text(12), color = Theme.inkSoft)
                    RowDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(w("prescription").uppercase(), style = brandText(11.2, FontWeight.ExtraBold).tracking(11.2 * 0.14), color = Theme.inkSoft)
                    Text(
                        "${w("folio")} ${if (preview) "—" else (header.folio ?: "—")}",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp),
                    )
                    Text("${w("date")}: ${StockDocWords.longDay(header.signedAt, header.language)}", style = Theme.text(14))
                    Text("${w("validUntil")}: ${StockDocWords.longDay(header.validUntil, header.language)}", style = Theme.text(12), color = Theme.inkSoft)
                }
                RowDivider()
                DocFacts(
                    buildList {
                        add(w("vet") to listOfNotNull(header.prescriberName.nonEmpty, header.prescriberTitle.nonEmpty).joinToString(", "))
                        add(credentialLabel to withoutLabel(header.credentialLine, credentialLabel))
                        if (header.deaNumber != null) {
                            add(w("dea") to (header.deaNumber ?: ""))
                            add(w("deaAddress") to (header.deaAddress ?: ""))
                        }
                        add(w("owner") to (header.clientName ?: ""))
                        add(w("address") to (header.clientAddress ?: ""))
                    },
                )
                RowDivider()
                DocFacts(
                    buildList {
                        add(w("animal") to (header.petName ?: ""))
                        add(w("species") to listOfNotNull(StockDocWords.species(header.species, header.language), header.breed.nonEmpty).joinToString(" · "))
                        add(w("sex") to (StockDocWords.sex(header.sex, header.language) ?: ""))
                        add(w("age") to (header.age ?: ""))
                        add(w("weight") to (header.weightKg?.let { "${StockDocWords.number(it)} kg" } ?: ""))
                        if (header.microchip.nonEmpty != null) add(w("microchip") to (header.microchip ?: ""))
                        if (header.diagnosis.nonEmpty != null) add(w("diagnosis") to (header.diagnosis ?: ""))
                    },
                )
                RowDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.forEachIndexed { index, item -> DocItemCard(item, index, mx, header.language) }
                    val notes = header.notes.nonEmpty
                    if (notes != null) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${w("notes")}: ") }
                                append(notes)
                            },
                            style = Theme.text(14),
                        )
                    }
                }
                RowDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    header.legend.nonEmpty?.let { Text(it, style = Theme.text(14, FontWeight.ExtraBold)) }
                    if (header.controlled) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("${w("controlled")}${header.controlLabel?.let { " · $it" } ?: ""}. ")
                                }
                                append(if (mx) w("cuantificada") else w("usPaper"))
                            },
                            style = Theme.text(12),
                            modifier = Modifier
                                .background(Theme.wash(Theme.coral, 12.0), RoundedCornerShape(Theme.chipRadius))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(64.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.inkStrong))
                    Text(w("signature"), style = Theme.text(12, FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text(
                        listOfNotNull(header.prescriberName.nonEmpty, header.credentialLine.nonEmpty).joinToString(" · "),
                        style = Theme.text(12),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                RowDivider()
                CompositionLocalProvider(LocalContentColor provides Theme.inkSoft, LocalTextStyle provides brandText(10.9)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (preview) {
                            Text(w("previewNote"), modifier = Modifier.weight(1f))
                        } else {
                            val broken = integrity?.verified == false
                            Glyph(if (broken) "triangle-alert" else "shield-check", 14.dp, tint = if (broken) Theme.destructive else Theme.blue)
                            Text(if (broken) "!" else w("verified"))
                            integrity?.sha256.nonEmpty?.let { sha ->
                                Text("· ${w("fingerprint")} ${sha.take(12)}", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    Text(w("review"), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                }
            }
        }
        if (cancelled) {
            Text(
                w("cancelled").uppercase(),
                style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Black).tracking(4),
                color = Theme.coral.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.Center).rotate(-18f),
            )
        }
    }
}

/** The "preview" or "original / copy" band across the top (web: 12 extrabold, tracked, the tone washed). */
@Composable
private fun DocBand(text: String, color: Color, percent: Double) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = brandText(12, FontWeight.ExtraBold).tracking(12 * 0.12),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Theme.wash(color, percent))
                .padding(vertical = 6.dp, horizontal = 20.dp),
        )
        RowDivider()
    }
}

private fun withoutLabel(value: String?, label: String): String {
    val text = (value ?: "").trim { it == ' ' || it == '\t' }
    return if (text.lowercase().startsWith(label.lowercase() + " ")) text.drop(label.length).trim { it == ' ' || it == '\t' } else text
}

@Composable
private fun DocFacts(pairs: List<Pair<String, String>>) {
    DocGrid(pairs, minimum = 140.dp, spacing = 8.dp, modifier = Modifier.padding(16.dp)) { (label, value) -> DocFact(label, value) }
}

@Composable
private fun DocFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label.uppercase(), style = brandText(9.9, FontWeight.ExtraBold).tracking(0.99), color = Theme.inkSoft)
        Text(value.nonEmpty ?: "—", style = Theme.text(14, FontWeight.SemiBold))
    }
}

/** `LazyVGrid(columns: [GridItem(.adaptive(minimum:))])`: as many equal columns as fit, rows `spacing` apart. */
@Composable
private fun <T> DocGrid(items: List<T>, minimum: Dp, spacing: Dp, modifier: Modifier = Modifier, cell: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = max(1, ((maxWidth + 8.dp) / (minimum + 8.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(count).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    chunk.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(count - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DocItemCard(item: StockDocItem, index: Int, mx: Boolean, language: String) {
    fun w(key: String): String = StockDocWords.word(key, language)
    val frequency: String = item.everyHours?.let { "${w("every")} ${StockDocWords.number(it)} h${if (item.asNeeded) " · ${w("asNeeded")}" else ""}" }
        ?: listOfNotNull(item.frequencyText.nonEmpty, if (item.asNeeded) w("asNeeded") else null).joinToString(" · ")
    val pairs = buildList {
        add(w("active") to (item.activeIngredient ?: ""))
        item.presentation.nonEmpty?.let { add(w("presentation") to it) }
        add(w("dose") to (item.doseAmount?.let { "${StockDocWords.number(it)}${item.doseUnit?.let { unit -> " $unit" } ?: ""}" } ?: ""))
        if (item.doseRate != null || mx) add(w("doseRate") to (item.doseRate?.let { "${StockDocWords.number(it)} ${item.doseRateUnit ?: ""}" } ?: ""))
        add(w("route") to (StockDocWords.route(item.route, language) ?: ""))
        add(w("frequency") to frequency)
        add(w("duration") to (item.durationDays?.let { "$it ${w("days")}" } ?: ""))
        add(w("quantity") to "${item.quantity}${item.quantityUnit.nonEmpty?.let { " $it" } ?: ""}")
        add(w("refills") to (if (item.refills > 0) "${item.refills}" else w("noRefills")))
        item.withdrawalPeriod.nonEmpty?.let { add(w("withdrawal") to it) }
    }
    val shape = RoundedCornerShape(Theme.chipRadius)
    Column(
        Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val soft = Theme.inkSoft
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = soft)) { append("Rx ${index + 1}. ") }
                append(item.medicineName)
                val strength = item.strength.nonEmpty
                if (strength != null) {
                    withStyle(Theme.text(16, FontWeight.SemiBold).toSpanStyle()) { append(" · $strength") }
                }
            },
            style = Theme.heading(18),
        )
        DocGrid(pairs, minimum = 120.dp, spacing = 6.dp) { (label, value) -> DocFact(label, value) }
        val instructions = item.instructions.nonEmpty
        if (instructions != null) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${w("instructions")}: ") }
                    append(instructions)
                },
                style = Theme.text(14),
            )
        }
    }
}
