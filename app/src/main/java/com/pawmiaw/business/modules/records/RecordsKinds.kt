package com.pawmiaw.business.records

import com.pawmiaw.business.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat

//
//  RecordsKinds.kt (iOS RecordsKinds.swift)
//
//  The ten kinds of record and the fields each asks for, word for word from
//  the web (lib/pet-record-definitions.ts, care-schedule.tsx,
//  lib/records-copy.ts): their names, the date each one is about, the
//  fields, and how a saved field reads back.
//

/** A record type, in the web's VISIBLE_RECORD_TYPES order. */
enum class RecordsKind(val rawValue: String) {
    vetVisit("vet_visit"),
    vaccination("vaccination"),
    deworming("deworming"),
    diagnosis("diagnosis"),
    medication("medication"),
    allergy("allergy"),
    labResult("lab_result"),
    weightCheck("weight_check"),
    surgery("surgery"),
    note("note");

    val id: String get() = rawValue

    /** The lists' and the record page's name (records-copy `recordTypeLabel`). */
    val label: String
        get() = when (this) {
            vaccination -> L("Vaccine")
            vetVisit -> L("Vet visit")
            medication -> L("Medicine")
            surgery -> L("Surgery")
            diagnosis -> L("Diagnosis")
            labResult -> L("Lab result")
            deworming -> L("Deworming")
            weightCheck -> L("Weight")
            allergy -> L("Allergy")
            note -> L("Note")
        }

    /** The record sheet's name (RECORD_TYPE_LABELS). */
    val formLabel: String
        get() = when (this) {
            vaccination -> L("Vaccination")
            vetVisit -> L("Vet Visit")
            medication -> L("Medication")
            surgery -> L("Surgery")
            diagnosis -> L("Diagnosis")
            labResult -> L("Lab Result")
            deworming -> L("Deworming")
            weightCheck -> L("Weight Check")
            allergy -> L("Allergy")
            note -> L("Note")
        }

    val summaryText: String
        get() = when (this) {
            vaccination -> L("Vaccines, boosters, and immunization follow-ups.")
            vetVisit -> L("Consults, annual checkups, and specialist visits.")
            medication -> L("Medication courses, refills, and prescribed treatments.")
            surgery -> L("Procedures, operations, and post-op history.")
            diagnosis -> L("Confirmed conditions and clinical findings.")
            labResult -> L("Lab work, panels, and test results.")
            deworming -> L("Parasite control treatments and next doses.")
            weightCheck -> L("Weight snapshots and progress tracking.")
            allergy -> L("Allergies, sensitivities, and known triggers.")
            note -> L("General medical notes and owner observations.")
        }

    val titlePlaceholder: String
        get() = when (this) {
            vaccination -> L("Rabies booster")
            vetVisit -> L("Annual wellness exam")
            medication -> L("Amoxicillin 250 mg")
            surgery -> L("Spay surgery follow-up")
            diagnosis -> L("Seasonal dermatitis")
            labResult -> L("CBC and chemistry panel")
            deworming -> L("Monthly deworming tablet")
            weightCheck -> L("Weight check")
            allergy -> L("Chicken sensitivity")
            note -> L("Behavior change after treatment")
        }

    /** What the record's date is (RECORD_EVENT_DATE_LABELS). */
    val eventDateLabel: String
        get() = when (this) {
            vaccination -> L("Date given")
            vetVisit -> L("Visit date")
            medication -> L("Start date")
            surgery -> L("Procedure date")
            diagnosis -> L("Date identified")
            labResult -> L("Result date")
            deworming -> L("Dose date")
            weightCheck -> L("Weigh-in date")
            allergy -> L("First noted")
            note -> L("Record date")
        }

    /** The web's icon for the kind (care-schedule RECORD_TYPE_ICONS), a Lucide name. */
    val systemImage: String
        get() = when (this) {
            vaccination -> "shield-check"
            vetVisit -> "stethoscope"
            medication -> "pill"
            surgery -> "scissors"
            diagnosis -> "clipboard-list"
            labResult -> "flask-conical"
            deworming -> "microscope"
            weightCheck -> "scale"
            allergy -> "triangle-alert"
            note -> "file-text"
        }

    /** Records are turquoise and a medicine blue, as in the family app (provider-colors `recordTypeHue`). */
    val hue: Hue get() = if (this == medication) Hue.sky else Hue.teal

    /** A vaccine or a dewormer: its follow-up date is the next dose. */
    val hasNextDose: Boolean get() = this == vaccination || this == deworming

    /** The field the title already names: it follows the title until typed apart (TITLE_NAMES_FIELD). */
    val titleNamesField: String?
        get() = when (this) {
            vaccination -> "vaccine_name"
            medication -> "medication_name"
            diagnosis -> "condition"
            else -> null
        }

    /** The states a diagnosis, medicine or allergy moves between, in order (the record page's switch). */
    val clinicalStates: List<String>?
        get() = when (this) {
            diagnosis -> listOf("active", "monitoring", "resolved")
            medication -> listOf("active", "completed", "stopped")
            allergy -> listOf("active", "inactive")
            else -> null
        }

    /** The current state (provider-pet-chart `getProviderRecordClinicalState`). */
    fun clinicalState(payload: Map<String, JSONValue>?): String? {
        val status = payload?.get("status")?.string
        return when (this) {
            diagnosis -> if (status == "monitoring" || status == "resolved") status else "active"
            medication -> if (status == "completed" || status == "stopped") status else "active"
            allergy -> if (status == "inactive") "inactive" else "active"
            else -> null
        }
    }

    /** A field's value when a new record of this kind starts. */
    fun defaultValue(key: String): String = when {
        this == vaccination && key == "next_due_status" -> "scheduled"
        (this == diagnosis || this == medication || this == allergy) && key == "status" -> "active"
        else -> ""
    }

    val fields: List<RecordsField> get() = RecordsField.definitions[this] ?: emptyList()

    companion object {
        fun of(raw: String?): RecordsKind = entries.firstOrNull { it.rawValue == raw } ?: note

        /** Swift `RecordsKind(rawValue:)`. */
        fun fromRaw(raw: String?): RecordsKind? = entries.firstOrNull { it.rawValue == raw }

        val allCases: List<RecordsKind> get() = entries
    }
}

/** One field a kind of record asks for (RecordFieldDefinition). Labels are English keys, read with `L` when shown. */
data class RecordsField(
    val key: String,
    val label: String,
    val placeholder: String,
    val input: Input = Input.text,
    val helper: String? = null,
    /** (value, label). */
    val options: List<Pair<String, String>> = emptyList(),
    val required: Boolean = false,
    /** (key, value). */
    val requiredWhen: Pair<String, String>? = null,
    /** (key, value). */
    val showWhen: Pair<String, String>? = null,
) {
    enum class Input { text, number, date, select }

    val id: String get() = key

    fun isRequired(values: Map<String, String>): Boolean {
        if (required) return true
        val rule = requiredWhen ?: return false
        return values[rule.first] == rule.second
    }

    fun isShown(values: Map<String, String>): Boolean {
        requiredWhen?.let { rule -> if (values[rule.first] != rule.second) return false }
        showWhen?.let { rule -> if (values[rule.first] != rule.second) return false }
        return true
    }

    companion object {
        // MARK: Option lists

        val diagnosisStatus: List<Pair<String, String>> = listOf("active" to "Active@state", "monitoring" to "Monitoring", "resolved" to "Resolved")
        val medicationStatus: List<Pair<String, String>> = listOf("active" to "Active@state", "completed" to "Completed@state")
        val allergyStatus: List<Pair<String, String>> = listOf("active" to "Active@state", "inactive" to "Inactive@state")
        val allergySeverity: List<Pair<String, String>> = listOf("mild" to "Mild", "moderate" to "Moderate", "severe" to "Severe")
        val routes: List<Pair<String, String>> = listOf(
            "oral" to "Oral", "topical" to "Topical", "otic" to "Ear", "ophthalmic" to "Eye",
            "injection" to "Injection", "inhaled" to "Inhaled", "other" to "Other@route",
        )
        val doseUnits: List<Pair<String, String>> = listOf(
            "mcg" to "mcg", "mg" to "mg", "ml" to "ml", "tablet" to "tablet", "capsule" to "capsule",
            "drop" to "drop", "gram" to "gram", "unit" to "unit", "IU" to "IU",
        )
        val therapyClasses: List<Pair<String, String>> = listOf(
            "peptide" to "Peptide (prepared before use)", "biologic" to "Biologic", "hormone" to "Hormonal", "other" to "Other medication type",
        )
        val peptideVerification: List<Pair<String, String>> = listOf(
            "vet_verified" to "Verified by veterinarian", "vet_directed" to "Veterinarian-directed", "owner_entered" to "Owner-entered / not verified",
        )
        val stopReasons: Map<String, String> = mapOf(
            "vet_advised_stop" to "Vet advised stop", "side_effects" to "Side effects", "refused" to "Pet refused medication",
            "course_changed" to "Course changed", "unavailable" to "Medication unavailable", "other" to "Other",
        )
        val noteTags: Map<String, String> = mapOf(
            "vomiting" to "Vomiting", "diarrhea" to "Diarrhea", "not_eating" to "Not eating", "drinking_more" to "Drinking more",
            "low_energy" to "Low energy", "limping" to "Limping", "coughing" to "Coughing", "itching" to "Itching",
            "injury" to "Injury", "behavior_change" to "Behavior change",
        )

        // MARK: The fields of each kind (RECORD_TYPE_FIELD_DEFINITIONS)

        val definitions: Map<RecordsKind, List<RecordsField>> by lazy {
            mapOf(
                RecordsKind.vaccination to listOf(
                    RecordsField(key = "vaccine_name", label = "Vaccine name", placeholder = "Nobivac Rabies", required = true),
                    RecordsField(key = "manufacturer_brand", label = "Manufacturer / brand", placeholder = "Merck Animal Health"),
                    RecordsField(key = "lot_serial_number", label = "Lot / serial number", placeholder = "RB-29401"),
                ),
                RecordsKind.vetVisit to listOf(
                    RecordsField(key = "instructions", label = "Instructions", placeholder = "Home care, medication timing, warnings, and what the owner should do next"),
                    RecordsField(key = "follow_up_note", label = "Follow-up note", placeholder = "Recheck in 2 weeks if limping continues"),
                ),
                RecordsKind.medication to listOf(
                    RecordsField(key = "therapy_class", label = "Therapy class", placeholder = "Medication", input = Input.select, helper = "Peptide therapies remain medication records so they stay in the medication list and emergency summary.", options = therapyClasses),
                    RecordsField(key = "medication_name", label = "Medication", placeholder = "Amoxicillin", required = true),
                    RecordsField(key = "dose_amount", label = "Dose amount", placeholder = "1", input = Input.number, required = true),
                    RecordsField(key = "dose_unit", label = "Dose unit", placeholder = "Choose unit", input = Input.select, options = doseUnits, required = true),
                    RecordsField(key = "route", label = "Route", placeholder = "Choose route", input = Input.select, options = routes, required = true),
                    RecordsField(key = "instructions", label = "Instructions", placeholder = "Give with food and finish the full course"),
                    RecordsField(key = "peptide_verification_status", label = "Instruction verification", placeholder = "Choose source", input = Input.select, options = peptideVerification, requiredWhen = "therapy_class" to "peptide", showWhen = "therapy_class" to "peptide"),
                    RecordsField(key = "concentration_label", label = "Concentration from label", placeholder = "e.g. 5 mg / 2 mL", helper = "Copy the exact label or pharmacy wording. PawMiaw does not use this to recommend a dose.", showWhen = "therapy_class" to "peptide"),
                    RecordsField(key = "administration_volume_ml", label = "Administration volume (mL)", placeholder = "Exact veterinarian-provided volume", input = Input.number, showWhen = "therapy_class" to "peptide"),
                    RecordsField(key = "injection_site_instructions", label = "Injection-site instructions", placeholder = "Copy the veterinarian-provided site or rotation instructions", showWhen = "therapy_class" to "peptide"),
                    RecordsField(key = "status", label = "Status", placeholder = "Choose status", input = Input.select, helper = "Use this to keep the current medication list accurate.", options = medicationStatus, required = true),
                    RecordsField(key = "planned_end_date", label = "Planned end date", placeholder = "Choose a day", input = Input.date),
                ),
                RecordsKind.surgery to listOf(
                    RecordsField(key = "procedure", label = "Procedure", placeholder = "Dental extraction", required = true),
                    RecordsField(key = "instructions", label = "Instructions", placeholder = "Keep the incision dry and use the cone at all times"),
                    RecordsField(key = "follow_up_note", label = "Follow-up note", placeholder = "Recheck the incision in 10 days"),
                ),
                RecordsKind.diagnosis to listOf(
                    RecordsField(key = "condition", label = "Condition", placeholder = "Seasonal dermatitis", required = true),
                    RecordsField(key = "status", label = "Status", placeholder = "Choose status", input = Input.select, options = diagnosisStatus, required = true),
                    RecordsField(key = "resolved_date", label = "Resolved date", placeholder = "Choose a day", input = Input.date, showWhen = "status" to "resolved"),
                ),
                RecordsKind.labResult to listOf(
                    RecordsField(key = "test_name", label = "Test name", placeholder = "CBC and chemistry panel", required = true),
                    RecordsField(key = "result_summary", label = "Result summary", placeholder = "Mild inflammation markers"),
                ),
                RecordsKind.deworming to listOf(
                    RecordsField(key = "product_name", label = "Product", placeholder = "Drontal Plus", required = true),
                    RecordsField(key = "dose_amount", label = "Dose amount", placeholder = "1", input = Input.number),
                    RecordsField(key = "dose_unit", label = "Dose unit", placeholder = "Choose unit", input = Input.select, options = doseUnits),
                    RecordsField(key = "route", label = "Route", placeholder = "Choose route", input = Input.select, options = routes),
                    RecordsField(key = "instructions", label = "Instructions", placeholder = "Give with food"),
                ),
                RecordsKind.weightCheck to listOf(
                    RecordsField(key = "weight_kg", label = "Weight (kg)", placeholder = "6.4", input = Input.number, helper = "Used for the detail card and trend tracking.", required = true),
                ),
                RecordsKind.allergy to listOf(
                    RecordsField(key = "trigger", label = "Trigger", placeholder = "Chicken", required = true),
                    RecordsField(key = "reaction", label = "Reaction", placeholder = "Itchy skin and ear redness"),
                    RecordsField(key = "severity", label = "Severity", placeholder = "Choose severity", input = Input.select, options = allergySeverity),
                    RecordsField(key = "status", label = "Status", placeholder = "Choose status", input = Input.select, options = allergyStatus),
                ),
                RecordsKind.note to emptyList(),
            )
        }

        /** Saved field names as the record page shows them (PAYLOAD_LABEL_OVERRIDES), in the order they read best. (key, label) */
        val payloadLabels: List<Pair<String, String>> = listOf(
            "vaccine_name" to "Vaccine Name", "manufacturer_brand" to "Manufacturer / Brand", "lot_serial_number" to "Lot / Serial Number",
            "next_due_date" to "Next Due Date", "next_due_status" to "Next Dose Plan", "next_dose_plan" to "Next Dose Plan",
            "clinic_name" to "Clinic", "doctor_name" to "Doctor", "reason" to "Reason", "instructions" to "Instructions",
            "follow_up_note" to "Follow-up Note", "medication_name" to "Medication", "dose_amount" to "Dose Amount",
            "dose_unit" to "Dose Unit", "route" to "Route", "planned_end_date" to "Planned End Date", "stop_date" to "Stop Date",
            "stop_reason" to "Stop Reason", "stop_note" to "Stop Note", "procedure" to "Procedure", "condition" to "Condition",
            "status" to "Status", "resolved_date" to "Resolved Date", "test_name" to "Test Name", "result_summary" to "Result Summary",
            "product_name" to "Product", "weight_kg" to "Weight (kg)", "trigger" to "Trigger", "reaction" to "Reaction",
        )
    }
}

// MARK: - Reading a saved record back

/** A saved field as the record page lists it. */
data class RecordsPayloadRow(val key: String, val label: String, val value: String)

object RecordsPayload {
    /** A saved field's name in the reader's language (formatPayloadLabel). */
    fun label(key: String): String {
        RecordsField.payloadLabels.firstOrNull { it.first == key }?.let { return L(it.second) }
        for (kind in RecordsKind.allCases) {
            kind.fields.firstOrNull { it.key == key }?.let { return L(it.label) }
        }
        return key.replace("_", " ").split(" ").filter { it.isNotEmpty() }
            .joinToString(" ") { it.take(1).uppercase() + it.drop(1) }
    }

    /** A saved value as words (formatPayloadEntryValue), "" when there is nothing to say. */
    fun value(key: String, value: JSONValue, kind: RecordsKind? = null): String {
        when (value) {
            is JsonNull -> return ""
            is JsonObject -> return ""
            is JsonArray -> {
                val strings = value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (key == "tags") {
                    return strings.joinToString(", ") { L(RecordsField.noteTags[it] ?: it) }
                }
                return strings.mapNotNull { it.nonEmpty }.joinToString(", ")
            }
            is JsonPrimitive -> {
                if (!value.isString) {
                    value.booleanOrNull?.let { flag -> return if (flag) L("Yes") else L("No") }
                    value.doubleOrNull?.let { number ->
                        val format = NumberFormat.getNumberInstance(appLocale).apply { maximumFractionDigits = 6 }
                        return format.format(number)
                    }
                    return value.content
                }
                val text = value.content.trim()
                if (text.isEmpty()) return ""
                if (key == "route") RecordsField.routes.firstOrNull { it.first == text }?.let { return L(it.second) }
                if (key == "status") {
                    val all = RecordsField.medicationStatus + RecordsField.diagnosisStatus + RecordsField.allergyStatus
                    all.firstOrNull { it.first == text }?.let { return L(it.second) }
                    if (text == "stopped") return L("Stopped")
                    return text
                }
                if (key == "stop_reason") return L(RecordsField.stopReasons[text] ?: text)
                if (key == "next_due_status" || key == "next_dose_plan") {
                    when (text) {
                        "scheduled" -> return L("Next due date known")
                        "not_applicable", "not_needed" -> return L("No next dose needed")
                        "unknown" -> return L("Next due date unknown")
                    }
                }
                // A choice of the kind's own fields reads as its option's name.
                val kinds = kind?.let { listOf(it) + RecordsKind.allCases } ?: RecordsKind.allCases
                for (candidate in kinds) {
                    val field = candidate.fields.firstOrNull { it.key == key && it.input == RecordsField.Input.select } ?: continue
                    field.options.firstOrNull { it.first == text }?.let { return L(it.second) }
                }
                if (text.length == 10 && Regex("^\\d{4}-\\d{2}-\\d{2}$").containsMatchIn(text)) {
                    return RecordsText.longDate(text)
                }
                if (Regex("^\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(text)) {
                    return RecordsText.dateTime(text)
                }
                return text
            }
        }
    }

    /** The record's own fields as label + value, the kind's fields first (recordPayloadRows). */
    fun rows(record: RecordsRecord): List<RecordsPayloadRow> {
        val payload = record.payload
        if (payload.isNullOrEmpty()) return emptyList()
        val kind = record.kind
        val order = kind.fields.map { it.key }.toMutableList()
        for (entry in RecordsField.payloadLabels) if (!order.contains(entry.first)) order.add(entry.first)
        val known = order.toSet()
        order += payload.keys.filter { !known.contains(it) }.sorted()
        return order.mapNotNull { key ->
            val raw = payload[key] ?: return@mapNotNull null
            val text = value(key, raw, kind = kind)
            if (text.trim().isEmpty() || text == "—") return@mapNotNull null
            RecordsPayloadRow(key, label(key), text)
        }
    }

    /** One line of what the record says, never repeating its title (record-row `recordSummaryLine`). */
    fun summaryLine(record: RecordsRecord): String {
        val title = record.titleText
        fun same(a: String, b: String): Boolean = a.trim().lowercase() == b.trim().lowercase()
        fun collapse(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val text = record.summary.nonEmpty ?: record.details.nonEmpty
        if (text != null) {
            return if (same(text, title)) "" else collapse(text)
        }
        val payload = record.payload ?: emptyMap()
        val kind = record.kind
        fun said(key: String): String = payload[key]?.let { value(key, it, kind = kind) } ?: ""
        if (kind == RecordsKind.medication) {
            val instructions = (payload["instructions"] as? JsonPrimitive)?.takeIf { it.isString }?.content.nonEmpty
            if (instructions != null) return collapse(instructions)
            val dose = listOf(said("dose_amount"), said("dose_unit")).filter { it.isNotEmpty() }.joinToString(" ")
            return listOf(dose, said("route")).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        return rows(record).map { it.value }.filter { !same(it, title) }.take(3).joinToString(" · ")
    }
}
