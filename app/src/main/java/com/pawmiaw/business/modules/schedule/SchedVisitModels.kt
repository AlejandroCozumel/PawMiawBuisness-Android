package com.pawmiaw.business.schedule

import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.serialization.Serializable
import java.util.Locale

//
//  SchedVisitModels.kt (iOS SchedVisitModels.swift)
//
//  What the visit page's side cards read (web: hospital/visit-triage-card,
//  visits/visit-chart, consents, incidents, visit-updates-card,
//  care/groom-request-staff): the triage, the chart in short, the consents,
//  the incidents, the notes to the family, the owner's grooming request, the
//  pet's open hospitalization. Every field that can be missing is nullable.
//

// MARK: - Triage (`/provider-visits/{id}/triage`)

@Serializable
data class SchedVitals(
    val id: String? = null,
    val recordedAt: String? = null,
    val temperatureC: Double? = null,
    val heartRate: Double? = null,
    val respiratoryRate: Double? = null,
    val painScore: Double? = null,
    val weightGrams: Double? = null,
    val mucousMembranes: String? = null,
    val crtSeconds: Double? = null,
)

@Serializable
data class SchedDepositDue(
    val amount: Int? = null,
    val paid: Int? = null,
    val currency: String? = null,
) {
    val owed: Int get() = maxOf(0, (amount ?: 0) - (paid ?: 0))
}

@Serializable
data class SchedTriage(
    val appointmentId: String? = null,
    val triagePriority: String? = null,
    val triageReason: String? = null,
    val triagedAt: String? = null,
    val afterHours: Boolean? = null,
    val vitals: List<SchedVitals>? = null,
    val depositDue: SchedDepositDue? = null,
) {
    val isEmpty: Boolean get() = triagePriority == null && afterHours != true && (vitals ?: emptyList()).isEmpty()
}

/** A pet's hospitalizations (`/provider-hospital/hospitalizations?providerClientPetId=`). */
@Serializable
data class SchedHospStay(
    val id: String,
    val status: String? = null,
) {
    val open: Boolean get() = status == "admitted" || status == "pending"
}

@Serializable
data class SchedHospStays(val hospitalizations: List<SchedHospStay>? = null)

/** What the admission answers (only what the visit page reads). */
@Serializable
data class SchedAdmitted(
    val id: String,
    val status: String? = null,
    val patient: Patient? = null,
    val client: Client? = null,
    val admitError: Refusal? = null,
    val consentError: String? = null,
    val consentRequest: Asked? = null,
) {
    @Serializable
    data class Patient(val name: String? = null)

    @Serializable
    data class Client(val onPawMiaw: Boolean? = null)

    @Serializable
    data class Refusal(val code: String? = null, val error: String? = null)

    @Serializable
    data class Asked(val consentId: String? = null)
}

// MARK: - The chart in short (`/provider-client-pets/{id}/chart-summary`)

@Serializable
data class SchedChartRecord(
    val id: String,
    val appointmentId: String? = null,
    val recordType: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val eventAt: String? = null,
    val followUpAt: String? = null,
    val payload: JSONValue? = null,
) {
    fun text(key: String): String? = payload[key].string.nonEmpty
}

@Serializable
data class SchedChartSummary(
    val sharedHistoryAccess: Access? = null,
    val highlights: Highlights? = null,
    val sections: Sections? = null,
) {
    @Serializable
    data class Access(val canView: Boolean? = null)

    @Serializable
    data class Highlights(val latestWeight: SchedChartRecord? = null)

    @Serializable
    data class Sections(
        val currentProblems: List<SchedChartRecord>? = null,
        val currentMedications: List<SchedChartRecord>? = null,
        val allergies: List<SchedChartRecord>? = null,
        val vaccines: List<SchedChartRecord>? = null,
        val recentRecords: List<SchedChartRecord>? = null,
        val ownerRecords: List<SchedChartRecord>? = null,
    )
}

// MARK: - Notes to the family (`/provider-appointments/{id}/updates`)

@Serializable
data class SchedVisitUpdate(
    val id: String,
    val message: String? = null,
    val mood: String? = null,
    val photoUrl: String? = null,
    val authorName: String? = null,
    val createdAt: String? = null,
)

// MARK: - The owner's grooming request (`/provider-appointments/{id}/groom-request`)

/** What is sent (PUT, and `groomRequests[]` on a booking). */
data class SchedGroomInput(
    var sameAsLastTime: Boolean = false,
    var services: List<String> = emptyList(),
    var style: String? = null,
    var styleText: String = "",
    var length: String = "",
    var areas: List<String> = emptyList(),
    var photoUrls: List<String> = emptyList(),
    var note: String = "",
    var extrasUpToCents: Int? = null,
    var extras: List<String> = emptyList(),
    var pickupMinute: Int? = null,
) {
    val isEmpty: Boolean
        get() = !sameAsLastTime && services.isEmpty() && style == null && styleText.nonEmpty == null && length.nonEmpty == null &&
            areas.isEmpty() && photoUrls.isEmpty() && note.nonEmpty == null && (extrasUpToCents ?: 0) <= 0 && extras.isEmpty() && pickupMinute == null

    val pickupTime: String? get() = pickupMinute?.let { String.format(Locale.US, "%02d:%02d", it / 60, it % 60) }

    /** A clean body: trimmed text, nulls for blanks. */
    val body: Map<String, Any?>
        get() = mapOf(
            "sameAsLastTime" to sameAsLastTime,
            "services" to services,
            "style" to style,
            "styleText" to styleText.nonEmpty,
            "length" to length.nonEmpty,
            "areas" to areas,
            "photoUrls" to photoUrls,
            "note" to note.nonEmpty,
            "extrasUpToCents" to if ((extrasUpToCents ?: 0) > 0) extrasUpToCents else null,
            "extras" to extras,
            "pickupTime" to pickupTime,
        )

    companion object {
        fun empty(last: SchedGroomLastTime?): SchedGroomInput {
            val input = SchedGroomInput()
            input.sameAsLastTime = last?.exists ?: false
            return input
        }

        fun from(request: SchedGroomRequest?, last: SchedGroomLastTime?): SchedGroomInput {
            if (request == null) return empty(last)
            val input = SchedGroomInput()
            input.sameAsLastTime = request.sameAsLastTime ?: false
            input.services = request.services ?: emptyList()
            input.style = request.style
            input.styleText = request.styleText ?: ""
            input.length = request.length ?: ""
            input.areas = request.areas ?: emptyList()
            input.photoUrls = request.photoUrls ?: emptyList()
            input.note = request.note ?: ""
            input.extrasUpToCents = request.extrasUpToCents
            input.extras = request.extras ?: emptyList()
            input.pickupMinute = request.pickupTime?.let { SchedHours.startMinutes(it) }
            return input
        }
    }
}

@Serializable
data class SchedGroomLastTime(
    val appointmentId: String? = null,
    val at: String? = null,
    val services: List<String>? = null,
    val style: String? = null,
    val styleText: String? = null,
    val length: String? = null,
    val careStyle: String? = null,
    val careTools: String? = null,
) {
    val exists: Boolean get() = appointmentId != null || careStyle.nonEmpty != null || careTools.nonEmpty != null
}

@Serializable
data class SchedGroomStamp(
    val side: String? = null,
    val name: String? = null,
    val at: String? = null,
)

@Serializable
data class SchedGroomRequest(
    val sameAsLastTime: Boolean? = null,
    val services: List<String>? = null,
    val style: String? = null,
    val styleText: String? = null,
    val length: String? = null,
    val areas: List<String>? = null,
    val photoUrls: List<String>? = null,
    val note: String? = null,
    val extrasUpToCents: Int? = null,
    val extras: List<String>? = null,
    val pickupTime: String? = null,
    val setBy: SchedGroomStamp? = null,
    val checkIn: SchedGroomStamp? = null,
)

@Serializable
data class SchedGroomWanted(
    val services: List<String>? = null,
    val style: String? = null,
    val styleText: String? = null,
    val length: String? = null,
)

@Serializable
data class SchedGroomExtras(
    val authorizedCents: Int? = null,
    val chargedCents: Int? = null,
    val status: String? = null,
    val overByCents: Int? = null,
    val currency: String? = null,
)

@Serializable
data class SchedGroomView(
    val appointmentId: String,
    val isGroom: Boolean? = null,
    val request: SchedGroomRequest? = null,
    val wanted: SchedGroomWanted? = null,
    val lastTime: SchedGroomLastTime? = null,
    val prefill: SvcReport? = null,
    val extras: SchedGroomExtras? = null,
    val staffMayEdit: Boolean? = null,
)

/** The care card's part the booking reads: "same as last time" and the currency. */
@Serializable
data class SchedCareForGroom(
    val lastGroom: SchedGroomLastTime? = null,
    val currency: String? = null,
)
