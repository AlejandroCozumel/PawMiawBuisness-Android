package com.pawmiaw.business.records

import com.pawmiaw.business.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Request
import java.util.Locale

//
//  RecordsModels.kt (iOS RecordsModels.swift)
//
//  The Records module's shapes (web hooks/use-provider-records.ts,
//  use-provider-follow-ups.ts) and the endpoints the web hooks call:
//  GET/POST /provider-records, GET/PATCH /provider-records/{id},
//  POST …/sign, POST …/discard, attachments, GET/PATCH /provider-follow-ups,
//  POST …/remind, the team (/provider-workspaces/{id}/members) and the
//  record's history (/audit-events).
//

// MARK: - Shapes

@Serializable
data class RecordsActor(
    val id: String? = null,
    val name: String? = null,
    val image: String? = null,
)

@Serializable
data class RecordsMemberRef(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class RecordsAttachment(
    val id: String,
    val fileName: String,
    val url: String,
    val mimeType: String? = null,
    val fileSizeBytes: Double? = null,
    val createdAt: String? = null,
) {
    /** A photo opens large; anything else is a document (the web's `isImage`). */
    val isImage: Boolean
        get() {
            val mime = mimeType?.lowercase()
            if (mime != null && mime.isNotEmpty()) return mime.startsWith("image/")
            val lower = fileName.lowercase()
            return listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic").any { lower.endsWith(it) }
        }

    /** "PDF", "DOCX": the tile's label for a document. */
    val fileExtension: String
        get() {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return ""
            return fileName.substring(dot + 1).take(4).uppercase()
        }

    /** "240 KB", "1.2 MB". */
    val sizeText: String?
        get() {
            val bytes = fileSizeBytes ?: return null
            if (bytes <= 0) return null
            if (bytes < 1024 * 1024) return "${maxOf(1, swiftRounded(bytes / 1024).toInt())} KB"
            return String.format(Locale.US, "%.1f MB", bytes / (1024 * 1024))
        }
}

/** A record (web `ProviderRecord`). Signed = `active`, shared with a family on PawMiaw. */
@Serializable
data class RecordsRecord(
    val id: String,
    val clinicId: String? = null,
    val appointmentId: String? = null,
    val providerClientId: String,
    val providerClientPetId: String,
    val recordType: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val details: String? = null,
    val internalNote: String? = null,
    val payload: Map<String, JsonElement>? = null,
    val eventAt: String? = null,
    val followUpAt: String? = null,
    val status: String? = null,
    val attachments: List<RecordsAttachment>? = null,
    val clientName: String? = null,
    val clientEmail: String? = null,
    val clientPhone: String? = null,
    val clientHouseholdId: String? = null,
    val clientPhotoUrl: String? = null,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val petSex: String? = null,
    val petBirthDate: String? = null,
    /** Grams. */
    val petWeight: Double? = null,
    val petIsNeutered: Boolean? = null,
    val petMicrochipNumber: String? = null,
    val petPhotoUrl: String? = null,
    val assignedMember: RecordsMemberRef? = null,
    val createdBy: RecordsActor? = null,
    val updatedBy: RecordsActor? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val signedAt: String? = null,
    val signedBy: RecordsActor? = null,
    val amendedAt: String? = null,
    val revision: Int? = null,
) {
    val kind: RecordsKind get() = RecordsKind.of(recordType)
    val isDraft: Boolean get() = status == "draft"
    val isSigned: Boolean get() = status == "active"
    val files: List<RecordsAttachment> get() = attachments ?: emptyList()
    val titleText: String get() = title.nonEmpty ?: kind.label
    val petText: String get() = petName.nonEmpty ?: "—"
    val clientText: String get() = clientName.nonEmpty ?: "—"
    val onPawMiaw: Boolean get() = clientHouseholdId.nonEmpty != null
    /** Nothing written and nothing attached: the only kind of draft the server lets go (discard). */
    val isEmptyDraft: Boolean
        get() = isDraft && summary.nonEmpty == null && details.nonEmpty == null && internalNote.nonEmpty == null && files.isEmpty()

    val petFilter: RecordsPetFilter
        get() = RecordsPetFilter(id = providerClientPetId, clientId = providerClientId, name = petText, clientName = clientText, species = petSpecies, photoUrl = petPhotoUrl)

    fun payloadText(key: String): String? {
        val value = payload?.get(key) ?: return null
        return value.string.nonEmpty
    }
}

/** A follow-up (web `ProviderFollowUp`): a recheck, a call, a vaccine due, or a visit's paperwork. */
@Serializable
data class RecordsFollowUp(
    val id: String,
    val providerRecordId: String? = null,
    val providerAppointmentId: String? = null,
    val providerClientId: String,
    val providerClientPetId: String,
    val followUpType: String? = null,
    val title: String? = null,
    val note: String? = null,
    val dueAt: String? = null,
    val status: String? = null,
    val completedAt: String? = null,
    val resolutionType: String? = null,
    val resolutionReason: String? = null,
    val resolvedAt: String? = null,
    val outcomeNote: String? = null,
    val clientName: String? = null,
    val clientPhotoUrl: String? = null,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val petPhotoUrl: String? = null,
    val recordTitle: String? = null,
    val recordType: String? = null,
    val appointmentStartsAt: String? = null,
    val appointmentServiceLabel: String? = null,
    val assignedMember: RecordsMemberRef? = null,
) {
    /** Still to act on: open, reminded, or put off. */
    val isPending: Boolean get() = (status ?: "open") in listOf("open", "contacted", "snoozed")
    /** A finished visit's paperwork: it closes itself when the note or report exists (docs/visit-paperwork.md). */
    val isVisitPaperwork: Boolean get() = followUpType == "documentation_needed" && providerAppointmentId.nonEmpty != null
    val isOverdue: Boolean get() = isPending && RecordsText.daysUntil(dueAt) < 0

    val petFilter: RecordsPetFilter
        get() = RecordsPetFilter(id = providerClientPetId, clientId = providerClientId, name = petName.nonEmpty ?: "—", clientName = clientName.nonEmpty ?: "—", species = petSpecies, photoUrl = petPhotoUrl)
}

/** The pet a list is narrowed to: enough to name it and open its chart. */
data class RecordsPetFilter(
    val id: String,
    val clientId: String,
    val name: String,
    val clientName: String,
    val species: String?,
    val photoUrl: String?,
)

/** A person on the team, as the assignee pickers and the "whose" filter read them. */
@Serializable
data class RecordsMember(
    val id: String,
    val name: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val role: String? = null,
    val status: String? = null,
    val isCurrentMember: Boolean? = null,
) {
    val plainName: String get() = name.nonEmpty ?: email.nonEmpty ?: L("Unnamed staff")
    /** "Ana (tú)" for the reader. */
    val label: String get() = if (isCurrentMember == true) LF("%@ (You)", plainName) else plainName
}

@Serializable
data class RecordsClientPet(
    val id: String,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val photoUrl: String? = null,
    val microchipNumber: String? = null,
    val status: String? = null,
)

@Serializable
data class RecordsClient(
    val id: String,
    val ownerName: String? = null,
    val ownerEmail: String? = null,
    val ownerPhone: String? = null,
    val householdId: String? = null,
    val photoUrl: String? = null,
    val petCount: Int? = null,
    val pets: List<RecordsClientPet>? = null,
    val status: String? = null,
) {
    val activePets: List<RecordsClientPet> get() = (pets ?: emptyList()).filter { it.status != "archived" }
}

/** The visit a record was written at (only what the record's page shows). */
@Serializable
data class RecordsAppointment(
    val id: String,
    val serviceLabel: String? = null,
    val status: String? = null,
    val startsAt: String? = null,
    val room: String? = null,
    val assignedMember: RecordsMemberRef? = null,
)

@Serializable
data class RecordsRemindResult(
    val channel: String? = null,
    val message: String? = null,
    val link: String? = null,
    val sentTo: Int? = null,
)

/**
 * A page of a list as this module keeps it: its rows and the server's total. (Not the shared
 * `Page`, so a page can be read in one task and used in another.)
 */
data class RecordsChunk<Item>(
    val items: List<Item>,
    val total: Int?,
    val hasMore: Boolean?,
) {
    constructor(page: Page<Item>) : this(page.items, page.total, page.hasMore)

    val page: Page<Item> get() = Page(items = items, total = total, hasMore = hasMore)
}

// MARK: - Endpoints

object RecordsAPI {
    /** GET /provider-records with the web's filters (null values are dropped). */
    suspend fun records(
        limit: Int, offset: Int,
        status: String? = null, readyToSign: Boolean = false, unfinished: Boolean = false,
        search: String? = null, recordType: String? = null,
        member: String? = null, unassigned: Boolean = false,
        location: String? = null, pet: String? = null, from: String? = null, to: String? = null,
        appointmentId: String? = null,
    ): RecordsChunk<RecordsRecord> {
        val page: Page<RecordsRecord> = API.get("/provider-records", mapOf(
            "limit" to "${minOf(limit, 100)}",
            "offset" to "$offset",
            "status" to status,
            "readyToSign" to if (readyToSign) "true" else null,
            "unfinished" to if (unfinished) "true" else null,
            "search" to search.nonEmpty,
            "recordType" to recordType,
            "assignedOrganizationMemberId" to member,
            "unassigned" to if (unassigned) "true" else null,
            "locationId" to location,
            "providerClientPetId" to pet,
            "from" to from,
            "to" to to,
            "appointmentId" to appointmentId,
        ))
        return RecordsChunk(page)
    }

    /** GET /provider-follow-ups. `to_do` (open or reminded) falls back to `open` on an older server. */
    suspend fun followUps(
        limit: Int, offset: Int,
        status: String = "to_do", kind: String? = null,
        search: String? = null, recordType: String? = null,
        member: String? = null, unassigned: Boolean = false,
        pet: String? = null, record: String? = null,
        dueFrom: String? = null, dueTo: String? = null,
    ): RecordsChunk<RecordsFollowUp> {
        suspend fun fetch(statusParam: String): RecordsChunk<RecordsFollowUp> {
            val page: Page<RecordsFollowUp> = API.get("/provider-follow-ups", mapOf(
                "limit" to "${minOf(limit, 100)}",
                "offset" to "$offset",
                "status" to statusParam,
                "kind" to kind,
                "search" to search.nonEmpty,
                "recordType" to recordType,
                "assignedOrganizationMemberId" to member,
                "unassigned" to if (unassigned) "true" else null,
                "providerClientPetId" to pet,
                "providerRecordId" to record,
                "dueFrom" to dueFrom,
                "dueTo" to dueTo,
            ))
            return RecordsChunk(page)
        }
        return try {
            fetch(status)
        } catch (error: APIError) {
            if (status == "to_do" && error.status == 400) fetch("open") else throw error
        }
    }

    suspend fun record(id: String): RecordsRecord = API.get("/provider-records/$id")

    suspend fun sign(id: String): RecordsRecord? {
        val data = API.data("POST", "/provider-records/$id/sign", emptyMap(), "{}".toByteArray())
        return runCatching { API.json.decodeFromString(RecordsRecord.serializer(), data.toString(Charsets.UTF_8)) }.getOrNull()
    }

    suspend fun discard(id: String) {
        API.send("POST", "/provider-records/$id/discard")
    }

    suspend fun update(id: String, body: Map<String, Any?>): RecordsRecord = API.patch("/provider-records/$id", body)

    suspend fun updateFollowUp(id: String, body: Map<String, Any?>): RecordsFollowUp = API.patch("/provider-follow-ups/$id", body)

    suspend fun remind(id: String, channel: String): RecordsRemindResult {
        val locale = if (LocalizationManager.currentLanguage == AppLanguage.spanish) "es" else "en"
        return API.post("/provider-follow-ups/$id/remind", mapOf("channel" to channel, "locale" to locale))
    }

    suspend fun members(clinicId: String): List<RecordsMember> = API.get("/provider-workspaces/$clinicId/members")

    suspend fun clients(limit: Int, search: String? = null, member: String? = null, unassigned: Boolean = false): RecordsChunk<RecordsClient> {
        val page: Page<RecordsClient> = API.get("/provider-clients", mapOf(
            "limit" to "${minOf(limit, 50)}",
            "offset" to "0",
            "search" to search.nonEmpty,
            "assignedOrganizationMemberId" to member,
            "unassigned" to if (unassigned) "true" else null,
        ))
        return RecordsChunk(page)
    }

    suspend fun client(id: String): RecordsClient = API.get("/provider-clients/$id")

    suspend fun appointment(id: String): RecordsAppointment = API.get("/provider-appointments/$id")

    /** A record's file, signed with the session (the web reads it through its cookie). */
    suspend fun fileData(url: String): ByteArray {
        if (url.startsWith("http")) {
            val builder = runCatching { Request.Builder().url(url) }.getOrNull() ?: throw APIError.offline()
            SessionToken.current?.let { builder.header("Authorization", "Bearer $it") }
            val response = API.execute(builder.get().build())
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val status = response.code
            response.close()
            if (status !in 200..299) throw API.errorFrom(bytes, status)
            return bytes
        }
        val path = if (url.startsWith("/api/")) url.drop(4) else url
        return API.data("GET", path, emptyMap(), null)
    }
}

/** A saved JSON value as the plain value the serializer sends back (an unknown payload entry kept on save). */
fun recordsPlainValue(value: JSONValue): Any? = when (value) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.booleanOrNull
        else -> value.doubleOrNull
    }
    is JsonArray -> value.map { recordsPlainValue(it) }
    is JsonObject -> value.mapValues { recordsPlainValue(it.value) }
}

/**
 * A request started now, on the main thread, and read later (`.await()`): several run at once
 * (iOS `Task` on the main actor). A failure reads as null.
 */
fun <T> recordsStart(run: Boolean = true, work: suspend () -> T): Deferred<T?> =
    CoroutineScope(Dispatchers.Main.immediate).async {
        if (!run) return@async null
        try {
            work()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

/** Swift `Double.rounded()`: half away from zero. */
private fun swiftRounded(value: Double): Double = if (value >= 0) Math.floor(value + 0.5) else Math.ceil(value - 0.5)
