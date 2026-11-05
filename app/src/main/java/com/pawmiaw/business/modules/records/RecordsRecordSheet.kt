package com.pawmiaw.business.records

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import com.pawmiaw.business.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

//
//  RecordsRecordSheet.kt (iOS RecordsRecordSheet.swift)
//
//  Add or edit a record (web provider-record-sheet.tsx): choose the client and
//  the pet, the kind of record, then write it — its title, the kind's own
//  fields, the date, who it is assigned to, the family's summary, a follow-up
//  date (the next dose for a vaccine or a dewormer), the details, photos and
//  PDFs (up to 6, 10 MB each, dragged into the order they upload in), and the
//  staff-only note. Someone who signs saves it signed; anyone else saves a
//  draft that waits for a signature. Opened from another screen
//  (`RecordsRecordSheet(context = …)`), the client, pet, visit and type it
//  hands in are already chosen and their steps skipped.
//

/** A photo or a document picked on this device, waiting for the record to be saved. */
class RecordsPickedFile(
    val name: String,
    val mime: String,
    val data: ByteArray,
    val preview: ImageBitmap? = null,
) {
    val id: UUID = UUID.randomUUID()
}

/**
 * What a new record starts from, handed in by the screen that asks (web provider-record-sheet.tsx
 * `fixedProviderClientId`, `fixedProviderClientPetId`, `linkedAppointmentId`, `fixedRecordType`,
 * `initialAssignedOrganizationMemberId`). A client and its pet together skip "Client + pet"; a type skips
 * "Record type"; the visit is saved with the record.
 */
data class RecordsSheetContext(
    val clientId: String? = null,
    val petId: String? = null,
    val appointmentId: String? = null,
    val kind: RecordsKind? = null,
    val assigneeId: String? = null,
) {
    companion object {
        /** The web's record type key ("vaccination", "vet_visit"…), and the names other screens may use for one. */
        fun kind(raw: String?): RecordsKind? {
            val key = raw?.trim()?.lowercase()?.replace("-", "_").nonEmpty ?: return null
            RecordsKind.fromRaw(key)?.let { return it }
            return when (key) {
                "vaccine", "vaccines" -> RecordsKind.vaccination
                "visit", "consultation", "consult", "exam" -> RecordsKind.vetVisit
                "dewormer", "deworm" -> RecordsKind.deworming
                "medicine", "medicines", "prescription", "treatment" -> RecordsKind.medication
                "lab", "labs", "lab_results", "result" -> RecordsKind.labResult
                "weight", "weigh_in" -> RecordsKind.weightCheck
                "diagnoses", "problem" -> RecordsKind.diagnosis
                "allergies" -> RecordsKind.allergy
                "notes" -> RecordsKind.note
                else -> null
            }
        }
    }
}

/** Add or edit a record (`record` null: a new one). */
@Composable
fun RecordsRecordSheet(
    record: RecordsRecord?,
    members: List<RecordsMember>,
    access: RecordsAccess,
    modifier: Modifier = Modifier,
    onSaved: ((RecordsRecord) -> Unit)? = null,
) {
    RecordsRecordSheetBody(record, RecordsSheetContext(), members, access, onSaved, modifier)
}

/** A new record with what the asking screen knows already chosen. */
@Composable
fun RecordsRecordSheet(
    context: RecordsSheetContext,
    members: List<RecordsMember>,
    access: RecordsAccess,
    modifier: Modifier = Modifier,
    onSaved: ((RecordsRecord) -> Unit)? = null,
) {
    RecordsRecordSheetBody(null, context, members, access, onSaved, modifier)
}

private enum class RecordsSheetStep { selection, type, details }

/** Images and PDFs, up to 6 files of 10 MB each. */
private const val recordsMaxFiles = 6
private const val recordsMaxBytes = 10 * 1024 * 1024
private val recordsAllowedMimes = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "application/pdf")

/** What the sheet opens with, worked out once (iOS `init`). */
private class RecordsSheetStart(record: RecordsRecord?, context: RecordsSheetContext, members: List<RecordsMember>, access: RecordsAccess) {
    val handedClient = if (record == null) context.clientId.nonEmpty else null
    val handedPet = if (record == null) context.petId.nonEmpty else null
    val linkedAppointmentId = if (record == null) context.appointmentId.nonEmpty else null
    val fixedContext = handedClient != null && handedPet != null
    val fixedType = record == null && context.kind != null
    val initialClientId = record?.providerClientId ?: handedClient ?: ""
    val kind = record?.kind ?: context.kind ?: RecordsKind.vetVisit
    val values: Map<String, String> = run {
        val result = LinkedHashMap<String, String>()
        for (field in kind.fields) {
            val saved = record?.payload?.get(field.key)
            if (saved != null && !saved.isNull) {
                val primitive = saved as? kotlinx.serialization.json.JsonPrimitive
                val number = primitive?.takeIf { !it.isString }?.content?.toDoubleOrNull()
                val flag = primitive?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
                result[field.key] = when {
                    // Typed back as written, never with the locale's grouping ("1,500" would read as 1.5).
                    number != null -> if (number == Math.rint(number) && Math.abs(number) < 1e15) number.toLong().toString() else number.toString()
                    flag != null -> if (flag) "true" else "false"
                    else -> primitive?.takeIf { it.isString }?.content ?: ""
                }
            } else {
                result[field.key] = kind.defaultValue(field.key)
            }
        }
        result
    }
    val current = members.firstOrNull { it.isCurrentMember == true }?.id
    val firstStep = when {
        record != null -> RecordsSheetStep.details
        handedClient != null && handedPet != null -> if (context.kind != null) RecordsSheetStep.details else RecordsSheetStep.type
        else -> RecordsSheetStep.selection
    }
    val clientId = record?.providerClientId ?: handedClient ?: ""
    val petId = record?.providerClientPetId ?: handedPet ?: ""
    val eventDate: Instant? = DateText.parse(record?.eventAt) ?: Instant.now()
    val followUpDate: Instant? = DateText.parse(record?.followUpAt)
    // Someone who works alone is the one responsible; without "anyone", it is the reader.
    val assignee: String = run {
        val handedAssignee = context.assigneeId?.let { id -> if (members.any { it.id == id }) id else null }
        record?.assignedMember?.id ?: handedAssignee ?: (if (access.assignAnyone) (if (members.size == 1) current else null) else current) ?: ""
    }
}

@Composable
private fun RecordsRecordSheetBody(
    record: RecordsRecord?,
    context: RecordsSheetContext,
    members: List<RecordsMember>,
    access: RecordsAccess,
    onSaved: ((RecordsRecord) -> Unit)?,
    modifier: Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val androidContext = LocalContext.current
    val form = remember { FormState() }
    val start = remember { RecordsSheetStart(record, context, members, access) }

    var step by remember { mutableStateOf(start.firstStep) }
    var clientId by remember { mutableStateOf(start.clientId) }
    var petId by remember { mutableStateOf(start.petId) }
    var client by remember { mutableStateOf<RecordsClient?>(null) }
    var kind by remember { mutableStateOf(start.kind) }
    var title by remember { mutableStateOf(record?.title ?: "") }
    val summary = remember { mutableStateOf(record?.summary ?: "") }
    val details = remember { mutableStateOf(record?.details ?: "") }
    val internalNote = remember { mutableStateOf(record?.internalNote ?: "") }
    var values by remember { mutableStateOf(start.values) }
    val eventDate = remember { mutableStateOf(start.eventDate) }
    val followUpDate = remember { mutableStateOf(start.followUpDate) }
    var assignee by remember { mutableStateOf(start.assignee) }
    var newFiles by remember { mutableStateOf<List<RecordsPickedFile>>(emptyList()) }
    var removedSaved by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember { mutableStateOf(false) }
    /** The picked file being dragged into a new place. */
    var draggingFile by remember { mutableStateOf<UUID?>(null) }

    val zone = Workspace.shared.timeZone
    val isEditing = record != null
    val currentMember = members.firstOrNull { it.isCurrentMember == true }
    val stepOrder: List<RecordsSheetStep> =
        if (isEditing) listOf(RecordsSheetStep.details)
        else (if (start.fixedContext) emptyList() else listOf(RecordsSheetStep.selection)) +
            (if (start.fixedType) emptyList() else listOf(RecordsSheetStep.type)) + listOf(RecordsSheetStep.details)
    val initialStep = stepOrder.firstOrNull() ?: RecordsSheetStep.details
    val previousStep: RecordsSheetStep? = stepOrder.indexOf(step).let { index -> if (index > 0) stepOrder[index - 1] else null }
    val chosenPet = client?.pets?.firstOrNull { it.id == petId }

    val isDirty: Boolean = run {
        if (newFiles.isNotEmpty() || removedSaved.isNotEmpty()) return@run true
        if (record != null) {
            return@run title != (record.title ?: "") || summary.value != (record.summary ?: "") || details.value != (record.details ?: "") ||
                internalNote.value != (record.internalNote ?: "")
        }
        clientId != start.initialClientId || step != initialStep || title.isNotEmpty() ||
            summary.value.isNotEmpty() || details.value.isNotEmpty() || internalNote.value.isNotEmpty()
    }

    val primaryTitle = when (step) {
        RecordsSheetStep.selection, RecordsSheetStep.type -> L("Continue")
        RecordsSheetStep.details -> if (isEditing) L("Save record") else L("Add record")
    }

    // A client handed in by id is read once (its name and pets); with one pet, that pet.
    LaunchedEffect(Unit) {
        if (record != null || client != null || clientId.isEmpty()) return@LaunchedEffect
        val wanted = clientId
        val loaded = recordsStart { RecordsAPI.client(wanted) }.await() ?: return@LaunchedEffect
        if (loaded.id != clientId) return@LaunchedEffect
        client = loaded
        if (petId.isEmpty() && loaded.activePets.size == 1) petId = loaded.activePets[0].id
    }

    // MARK: Steps

    fun stepIcon(step: RecordsSheetStep): String = when (step) {
        RecordsSheetStep.selection -> "user-round"
        RecordsSheetStep.type -> "clipboard-list"
        RecordsSheetStep.details -> "file-text"
    }

    fun stepLabel(step: RecordsSheetStep): String = when (step) {
        RecordsSheetStep.selection -> L("Client + pet")
        RecordsSheetStep.type -> L("Record type")
        RecordsSheetStep.details -> L("Record@one")
    }

    // MARK: Kind

    fun choose(next: RecordsKind) {
        if (next != kind) {
            kind = next
            val fresh = LinkedHashMap<String, String>()
            for (field in next.fields) fresh[field.key] = next.defaultValue(field.key)
            val nameKey = next.titleNamesField
            val typed = title.nonEmpty
            if (nameKey != null && typed != null) fresh[nameKey] = typed
            values = fresh
        }
        // A short beat on the chosen card before the record slides in, as the web's confirm blink.
        scope.launch { delay(220); step = RecordsSheetStep.details }
    }

    // MARK: The record's bindings

    val titleBinding = stateOf({ title }, { next ->
        // The name field follows the title until it's typed apart from it.
        val nameKey = kind.titleNamesField
        if (nameKey != null) {
            val current = values[nameKey] ?: ""
            if (current.isEmpty() || current == title) values = values + (nameKey to next)
        }
        title = next
    })

    fun value(key: String) = stateOf({ values[key] ?: "" }, { values = values + (key to it); form.clear("field.$key") })

    fun dayValue(key: String) = stateOf<Instant?>(
        { DateText.parse(values[key]) },
        { values = values + (key to (it?.let { day -> DateText.dayKey(day) } ?: "")); form.clear("field.$key") },
    )

    // MARK: Files

    val savedFiles: List<RecordsAttachment> = record?.files ?: emptyList()
    fun usedSlots(): Int = savedFiles.count { !removedSaved.contains(it.id) } + newFiles.size

    /** One place earlier or later (TalkBack's way to reorder). */
    fun move(id: UUID, offset: Int) {
        val from = newFiles.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = from + offset
        if (to !in newFiles.indices) return
        newFiles = newFiles.toMutableList().also { java.util.Collections.swap(it, from, to) }
    }

    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val problems = mutableListOf<String>()
            for ((offset, uri) in uris.withIndex()) {
                if (usedSlots() >= recordsMaxFiles) {
                    problems.add(LF("Only %d fit: %d were left out.", recordsMaxFiles, uris.size - offset))
                    break
                }
                val data = readBytes(androidContext, uri)
                if (data == null) {
                    problems.add(LF("%@ couldn’t be prepared.", L("Photo")))
                    continue
                }
                val mime = androidContext.contentResolver.getType(uri)?.lowercase() ?: "image/jpeg"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val name = "${L("Photo")}-${newFiles.size + 1}.$ext"
                if (!recordsAllowedMimes.contains(mime)) {
                    problems.add(LF("%@ isn’t a supported file.", name))
                    continue
                }
                if (data.size > recordsMaxBytes) {
                    problems.add(LF("%@ is over %@.", name, "10 MB"))
                    continue
                }
                newFiles = newFiles + RecordsPickedFile(name = name, mime = mime, data = data, preview = decodePreview(data))
            }
            problems.firstOrNull()?.let { Toast.error(it) }
        }
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val problems = mutableListOf<String>()
            for ((offset, uri) in uris.withIndex()) {
                if (usedSlots() >= recordsMaxFiles) {
                    problems.add(LF("Only %d fit: %d were left out.", recordsMaxFiles, uris.size - offset))
                    break
                }
                val name = displayName(androidContext, uri)
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = (if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null)
                    ?: androidContext.contentResolver.getType(uri)?.lowercase()
                    ?: "application/octet-stream"
                if (!recordsAllowedMimes.contains(mime)) {
                    problems.add(LF("%@ isn’t a supported file.", name))
                    continue
                }
                val data = readBytes(androidContext, uri)
                if (data == null) {
                    problems.add(LF("%@ couldn’t be prepared.", name))
                    continue
                }
                if (data.size > recordsMaxBytes) {
                    problems.add(LF("%@ is over %@.", name, "10 MB"))
                    continue
                }
                newFiles = newFiles + RecordsPickedFile(name = name, mime = mime, data = data, preview = if (mime.startsWith("image/")) decodePreview(data) else null)
            }
            problems.firstOrNull()?.let { Toast.error(it) }
        }
    }

    val room = recordsMaxFiles - usedSlots()
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, room))) { uris -> addPhotos(uris) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addPhotos(listOf(uri)) }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addFiles(uris) }

    // MARK: Saving

    fun checkSelection(): Boolean = form.check(listOf(
        "client" to (if (clientId.isEmpty()) L("Choose the client first.") else null),
        "pet" to (if (petId.isEmpty()) L("Choose the pet first.") else null),
    ))

    /** Noon of that day on the business's clock (a record saved with only its day). */
    fun noon(date: Instant): String = DateText.iso(date.atZone(zone).toLocalDate().atStartOfDay(zone).plusHours(12).toInstant())

    fun save() {
        if (clientId.isEmpty() || petId.isEmpty()) {
            if (!isEditing && stepOrder.contains(RecordsSheetStep.selection)) step = RecordsSheetStep.selection
            checkSelection()
            return
        }
        val today = LocalDate.now(zone)
        val rules = mutableListOf<Pair<String, String?>>("title" to (if (title.nonEmpty == null) L("Title is required.") else null))
        for (field in kind.fields) {
            if (!field.isShown(values)) continue
            val text = values[field.key]?.trim() ?: ""
            var message: String? = null
            if (field.isRequired(values) && text.isEmpty()) {
                message = if (field.key == "weight_kg") L("Weight is required for a weight check.") else LF("%@ is required.", L(field.label))
            }
            rules.add("field.${field.key}" to message)
        }
        val event = eventDate.value
        val eventMessage: String? = when {
            event == null -> LF("%@ is required.", kind.eventDateLabel)
            event.atZone(zone).toLocalDate() > today -> LF("%@ cannot be in the future.", kind.eventDateLabel)
            else -> null
        }
        rules.add("eventDate" to eventMessage)
        rules.add("assignee" to (if (access.assignAnyone && members.isNotEmpty() && assignee.isEmpty()) L("Assignee is required.") else null))
        val followUp = followUpDate.value
        if (followUp != null && followUp.atZone(zone).toLocalDate() < today) {
            rules.add("followUpDate" to L("Follow-up date cannot be in the past."))
        }
        if (!form.check(rules) || event == null) return

        // Saved fields this kind doesn't ask for stay as they were.
        val payload = LinkedHashMap<String, Any?>()
        record?.payload?.let { saved ->
            val known = kind.fields.map { it.key }.toSet()
            for ((key, entry) in saved) if (!known.contains(key)) payload[key] = recordsPlainValue(entry)
        }
        for (field in kind.fields) {
            if (!field.isShown(values)) continue
            val text = values[field.key]?.trim() ?: ""
            if (text.isEmpty()) continue
            val number = if (field.input == RecordsField.Input.number) text.replace(",", ".").toDoubleOrNull()?.takeIf { it.isFinite() } else null
            payload[field.key] = number ?: text
        }

        val body = linkedMapOf<String, Any?>(
            "providerClientId" to clientId,
            "providerClientPetId" to petId,
            "recordType" to kind.rawValue,
            "title" to title.trim(),
            "summary" to summary.value.nonEmpty,
            "details" to details.value.nonEmpty,
            "internalNote" to internalNote.value.nonEmpty,
            "payload" to (if (payload.isEmpty()) null else payload),
            "eventAt" to noon(event),
            "eventDate" to DateText.dayKey(event),
            "followUpAt" to followUp?.let { noon(it) },
            "followUpDate" to followUp?.let { DateText.dayKey(it) },
        )
        val assigneeId = if (access.assignAnyone) assignee.nonEmpty else (record?.assignedMember?.id ?: currentMember?.id)
        if (assigneeId != null) body["assignedOrganizationMemberId"] = assigneeId
        if (record != null) {
            record.appointmentId.nonEmpty?.let { body["appointmentId"] = it }
            record.revision?.let { body["expectedRevision"] = it }
        } else {
            // Written at a visit when one was handed in; otherwise none.
            body["appointmentId"] = start.linkedAppointmentId
        }

        val removed = removedSaved.toList()
        val added = newFiles
        saving = true
        scope.launch {
            try {
                var saved: RecordsRecord
                try {
                    saved = if (record != null) API.patch("/provider-records/${record.id}", body) else API.post("/provider-records", body)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val known = mapOf(
                        "title" to "title", "summary" to "summary", "details" to "details", "internalNote" to "internalNote",
                        "eventAt" to "eventDate", "eventDate" to "eventDate", "followUpAt" to "followUpDate", "followUpDate" to "followUpDate",
                        "assignedOrganizationMemberId" to "assignee", "providerClientId" to "client", "providerClientPetId" to "pet",
                    )
                    if (form.apply(e, known) && !isEditing && stepOrder.contains(RecordsSheetStep.selection) &&
                        (form.error("client") != null || form.error("pet") != null)
                    ) {
                        step = RecordsSheetStep.selection
                    }
                    return@launch
                }

                // Files after the record: removed ones first, then the new ones in the order they were arranged.
                try {
                    for (id in removed) {
                        API.send("DELETE", "/provider-records/${saved.id}/attachments/$id")
                    }
                    for (file in added) {
                        API.upload<RecordsAttachment>("/provider-records/${saved.id}/attachments", fileName = file.name, mimeType = file.mime, fileData = file.data)
                    }
                    if (removed.isNotEmpty() || added.isNotEmpty()) {
                        val savedId = saved.id
                        recordsStart { RecordsAPI.record(savedId) }.await()?.let { saved = it }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The record is saved: saving again would write it twice. Say so, and close.
                    Toast.error("${L("Saved, but a file could not be added. Open the record to try again.")} ${errorText(e)}")
                    announceDataChanged()
                    onSaved?.invoke(saved)
                    dismiss()
                    return@launch
                }

                if (isEditing) {
                    Toast.show(L("Record updated."))
                } else {
                    Toast.show(if (saved.isDraft) L("Saved as a draft. It waits for a signature.") else L("Record created."))
                }
                announceDataChanged()
                onSaved?.invoke(saved)
                dismiss()
            } finally {
                saving = false
            }
        }
    }

    fun primary() {
        when (step) {
            RecordsSheetStep.selection -> {
                if (!checkSelection()) return
                step = RecordsSheetStep.type
            }
            RecordsSheetStep.type -> step = RecordsSheetStep.details
            RecordsSheetStep.details -> save()
        }
    }

    // MARK: The pieces

    @Composable
    fun SelectionStep() {
        // The one client-and-pet picker (search from 2 letters, 20 at a time, the chosen one on top);
        // a vet who sees only their own clients searches only those. Each pet shows its chip.
        ClientPetPicker(
            client = stateOf({ client?.let { RecordsClientBridge.picked(it) } }, { picked ->
                client = picked?.let { RecordsClientBridge.records(it) }
                clientId = picked?.id ?: ""
            }),
            petIds = stateOf({ if (petId.isEmpty()) emptyList() else listOf(petId) }, { petId = it.firstOrNull() ?: "" }),
            pets = ClientPetPicker.Pets.one,
            petRequired = true,
            key = "client",
            petKey = "pet",
            form = form,
            assignedMemberId = if (access.assignAnyone) null else currentMember?.id,
            petNote = { pet -> pet.microchipNumber.nonEmpty?.let { LF("Chip %@", it) to false } },
        )
    }

    @Composable
    fun TypeStep() {
        ModalSection(title = L("Choose record type"), subtitle = L("Pick the kind of provider-side medical history entry you want to save."), systemImage = "clipboard-list") {
            // The web's option cards: each kind with its icon, on the sheet's aqua.
            val aqua = Theme.aqua
            OptionCards(
                options = RecordsKind.allCases.map { OptionCard(it, title = it.formLabel, description = it.summaryText, icon = it.systemImage, accent = aqua) },
                selection = stateOf<RecordsKind>({ kind }, { choose(it) }),
            )
        }
    }

    @Composable
    fun FieldView(field: RecordsField) {
        val required = field.isRequired(values)
        val key = "field.${field.key}"
        val hint = field.helper?.let { L(it) }
        when (field.input) {
            RecordsField.Input.text ->
                PMTextField(L(field.label), text = value(field.key), key = key, form = form, prompt = L(field.placeholder), hint = hint, required = required)
            RecordsField.Input.number ->
                PMTextField(L(field.label), text = value(field.key), key = key, form = form, prompt = L(field.placeholder), hint = hint, keyboard = KeyboardType.Decimal, required = required)
            RecordsField.Input.date ->
                OptionalDateField(label = L(field.label), date = dayValue(field.key), placeholder = L(field.placeholder), key = key, form = form, hint = hint, optional = false, required = required)
            RecordsField.Input.select -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MenuField(
                    label = L(field.label),
                    options = menuOptions(field),
                    selection = value(field.key),
                    key = key,
                    form = form,
                    required = required,
                )
                if (hint != null) Text(hint, style = Theme.text(13), color = Theme.inkSoft)
            }
        }
    }

    @Composable
    fun NextDosePick(label: String, add: (java.time.ZonedDateTime) -> java.time.ZonedDateTime) {
        Text(
            label,
            style = Theme.text(13, FontWeight.Bold),
            color = Theme.inkStrong,
            modifier = Modifier
                .plainButton {
                    val base = eventDate.value ?: Instant.now()
                    followUpDate.value = add(base.atZone(zone)).toInstant()
                    form.clear("followUpDate")
                }
                .height(32.dp)
                .border(1.dp, Theme.borderSoft, CircleShape)
                .padding(horizontal = 12.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }

    @Composable
    fun AssigneeField() {
        if (access.assignAnyone && members.isNotEmpty()) {
            // The reader reads "Ana (You)" once (web provider-record-sheet: `{name} (You)`).
            val options = listOf("" to L("Choose assignee")) + members.map { member ->
                member.id to (if (member.isCurrentMember == true) LF("%@ (You)", member.plainName) else member.label)
            }
            MenuField(
                label = L("Assignee"),
                options = options,
                selection = stateOf({ assignee }, { assignee = it; form.clear("assignee") }),
                key = "assignee",
                form = form,
                required = true,
            )
        } else {
            FormField(label = L("Assignee"), key = "assignee", form = form, required = true) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(Theme.surfaceMuted, RoundedCornerShape(Theme.fieldRadius))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        currentMember?.let { LF("%@ (You)", it.plainName) } ?: L("You"),
                        style = Theme.text(15, FontWeight.SemiBold),
                        color = Theme.inkStrong,
                    )
                }
            }
        }
    }

    @Composable
    fun Attachments() {
        val tileBounds = remember { mutableStateMapOf<UUID, Rect>() }
        var dragPointer by remember { mutableStateOf(Offset.Zero) }
        var dragGrab by remember { mutableStateOf(Offset.Zero) }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (savedFiles.isNotEmpty() || newFiles.isNotEmpty()) {
                RecordsAdaptiveGrid(minimum = 92.dp, spacing = 10.dp) {
                    savedFiles.forEach { saved ->
                        key(saved.id) {
                            val removed = removedSaved.contains(saved.id)
                            RecordsFileTile(
                                name = saved.fileName,
                                caption = if (removed) L("Removed on save") else L("Saved"),
                                dimmed = removed,
                                icon = if (removed) "arrow.uturn.backward" else "xmark",
                                iconLabel = if (removed) LF("Keep %@", saved.fileName) else LF("Remove %@@file", saved.fileName),
                                face = { if (saved.isImage) RecordsAuthImage(url = saved.url) else RecordsDocFace(saved.fileName) },
                            ) {
                                removedSaved = if (removed) removedSaved - saved.id else removedSaved + saved.id
                            }
                        }
                    }
                    newFiles.forEachIndexed { index, file ->
                        key(file.id) {
                            val place = if (newFiles.size > 1) index + 1 else null
                            val isDragging = draggingFile == file.id
                            val moveLabel = LF("Move %@", file.name)
                            val placeText = place?.let { LF("Place %d of %d", it, newFiles.size) } ?: ""
                            val earlier = L("Move earlier")
                            val later = L("Move later")
                            val removeLabel = LF("Remove %@@file", file.name)
                            RecordsFileTile(
                                name = file.name,
                                caption = null,
                                dimmed = false,
                                place = place,
                                icon = "xmark",
                                iconLabel = removeLabel,
                                face = {
                                    val preview = file.preview
                                    if (preview != null) {
                                        Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else {
                                        RecordsDocFace(file.name)
                                    }
                                },
                                modifier = Modifier
                                    .onGloballyPositioned { tileBounds[file.id] = it.boundsInRoot() }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .then(if (isDragging) Modifier else Modifier.recordsAnimatePlacement())
                                    .graphicsLayer {
                                        if (draggingFile == file.id) {
                                            val bounds = tileBounds[file.id]
                                            if (bounds != null) {
                                                translationX = dragPointer.x - dragGrab.x - bounds.left
                                                translationY = dragPointer.y - dragGrab.y - bounds.top
                                            }
                                            scaleX = 1.04f
                                            scaleY = 1.04f
                                        }
                                    }
                                    // Long-press and drag into another place: they upload in this order (the web's picker).
                                    .pointerInput(file.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { grab ->
                                                val bounds = tileBounds[file.id]
                                                if (bounds != null) {
                                                    draggingFile = file.id
                                                    dragGrab = grab
                                                    dragPointer = bounds.topLeft + grab
                                                }
                                            },
                                            onDrag = { change, amount ->
                                                if (draggingFile != file.id) return@detectDragGesturesAfterLongPress
                                                change.consume()
                                                dragPointer += amount
                                                // Moves into the place of the one it is over, as it passes (the web's `rectSortingStrategy`).
                                                val target = newFiles.firstOrNull { it.id != file.id && tileBounds[it.id]?.contains(dragPointer) == true }
                                                if (target != null) {
                                                    val from = newFiles.indexOfFirst { it.id == file.id }
                                                    val to = newFiles.indexOfFirst { it.id == target.id }
                                                    if (from >= 0 && to >= 0) {
                                                        newFiles = newFiles.toMutableList().also { list ->
                                                            val moved = list.removeAt(from)
                                                            list.add(to, moved)
                                                        }
                                                    }
                                                }
                                            },
                                            onDragEnd = { draggingFile = null },
                                            onDragCancel = { draggingFile = null },
                                        )
                                    }
                                    .clearAndSetSemantics {
                                        contentDescription = moveLabel
                                        stateDescription = placeText
                                        customActions = listOf(
                                            CustomAccessibilityAction(earlier) { move(file.id, -1); true },
                                            CustomAccessibilityAction(later) { move(file.id, 1); true },
                                            CustomAccessibilityAction(removeLabel) { newFiles = newFiles.filter { it.id != file.id }; true },
                                        )
                                    },
                            ) {
                                newFiles = newFiles.filter { it.id != file.id }
                            }
                        }
                    }
                }
            }
            val roomLeft = recordsMaxFiles - usedSlots()
            val limits = "${L("Images and PDFs")} · ${LF("up to %@ each", "10 MB")}"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (newFiles.size > 1) L("Drag to change the order.") else limits,
                    style = Theme.text(12), color = Theme.inkSoft,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                Text(
                    if (roomLeft <= 0) LF("Full: %d of %d", recordsMaxFiles, recordsMaxFiles) else LF("%d of %d", usedSlots(), recordsMaxFiles),
                    style = Theme.text(12, FontWeight.Bold).monospacedDigit(),
                    color = Theme.inkSoft,
                    modifier = Modifier
                        .background(if (roomLeft <= 0) Theme.wash(Theme.mango, 30.0) else Theme.surfaceMuted, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (roomLeft > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PMButton(L("Choose photos"), systemImage = "images", variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = !saving) {
                        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        if (roomLeft >= 2) pickPhotos.launch(request) else pickPhoto.launch(request)
                    }
                    PMButton(L("Choose files"), systemImage = "file-plus", variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = !saving) {
                        importFiles.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/heic", "image/gif", "image/webp"))
                    }
                }
            }
        }
    }

    @Composable
    fun DetailsStep() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PMTextField(L("Title"), text = titleBinding, key = "title", form = form, prompt = LF("e.g. %@", kind.titlePlaceholder), required = true)

            val fields = kind.fields.filter { it.isShown(values) }
            if (fields.isNotEmpty()) {
                ModalSection(
                    title = LF("%@ details", if (RecordsText.isSpanish) kind.formLabel.lowercase() else kind.formLabel),
                    systemImage = kind.systemImage,
                ) {
                    fields.forEach { field -> key(field.key) { FieldView(field) } }
                }
            }

            OptionalDateField(
                label = kind.eventDateLabel, date = eventDate, placeholder = L("Choose a day"),
                max = Instant.now(), key = "eventDate", form = form, optional = false, required = true,
            )

            AssigneeField()

            PMTextField(
                if (kind == RecordsKind.vetVisit) L("Owner summary") else L("Summary"), text = summary, key = "summary", form = form,
                prompt = if (kind == RecordsKind.vetVisit) L("Short recap the owner can understand quickly") else L("Quick clinical summary"),
                multiline = true,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionalDateField(
                    label = if (kind.hasNextDose) L("Next dose") else L("Follow-up date"), date = followUpDate,
                    placeholder = if (kind.hasNextDose) L("Add the next dose") else L("Add follow-up"),
                    min = LocalDate.now(zone).atStartOfDay(zone).toInstant(), key = "followUpDate", form = form, optional = false,
                )
                if (kind.hasNextDose) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NextDosePick(L("In 3 weeks")) { it.plusWeeks(3) }
                        NextDosePick(L("In 1 month")) { it.plusMonths(1) }
                        NextDosePick(L("In 3 months")) { it.plusMonths(3) }
                        NextDosePick(L("In 1 year")) { it.plusYears(1) }
                    }
                    Text(L("A family on PawMiaw gets a reminder a week before."), style = Theme.text(13), color = Theme.inkSoft)
                }
            }

            PMTextField(
                L("Details"), text = details, key = "details", form = form,
                prompt = if (kind == RecordsKind.vetVisit) L("Owner-facing visit details, what happened today, and anything useful to remember.")
                else L("Clinical notes, observations, medication details, or anything the team should remember."),
                multiline = true,
            )

            ModalSection(
                title = L("Attachments"),
                subtitle = L("Images and PDFs that belong to this record, up to 10 MB each with a maximum of 6 files."),
                systemImage = "paperclip",
                tint = Theme.borderSoft,
            ) {
                Attachments()
            }

            ModalSection(
                title = L("Internal"),
                subtitle = L("Staff-only context. This note stays separate from the owner-facing record."),
                systemImage = "lock",
                tint = Theme.pink,
            ) {
                PMTextField(
                    L("Internal note"), text = internalNote, key = "internalNote", form = form,
                    prompt = L("Private clinical context, follow-up rationale, or anything the team should keep internal."),
                    multiline = true,
                )
            }
        }
    }

    ProviderModal(
        title = if (isEditing) L("Edit record") else L("Add record"),
        subtitle = if (isEditing) {
            if (record?.isDraft == true) L("Change what is needed. It stays a draft until it is signed.")
            else L("Change what is needed. A signed record keeps a mark that it was corrected.")
        } else L("Choose the pet, the kind of record, and write it down."),
        systemImage = "file-text",
        accent = ModalAccent.aqua,
        isDirty = isDirty,
        form = form,
        primaryTitle = primaryTitle,
        isWorking = saving,
        steps = if (stepOrder.size > 1) ModalSteps(stepOrder.map { stepLabel(it) }, icons = stepOrder.map { stepIcon(it) }, current = stepOrder.indexOf(step).coerceAtLeast(0)) else null,
        onBack = previousStep?.let { previous -> { step = previous } },
        primaryIcon = if (step == RecordsSheetStep.details) "file-text" else null,
        onPrimary = { primary() },
        // iOS `.presentationDetents([.large])`: the modal's own whole height.
        modifier = modifier,
    ) {
        ContextPills(
            client = client?.ownerName ?: record?.clientName,
            linked = client?.let { it.householdId.nonEmpty != null } ?: record?.onPawMiaw,
            pets = listOfNotNull((chosenPet?.petName ?: record?.petName).nonEmpty),
        ) {
            if (step == RecordsSheetStep.details) recordsTypeChip(kind)
        }
        when (step) {
            RecordsSheetStep.selection -> SelectionStep()
            RecordsSheetStep.type -> TypeStep()
            RecordsSheetStep.details -> DetailsStep()
        }
    }
}

/** A choice's options, its placeholder first (nothing chosen). */
private fun menuOptions(field: RecordsField): List<Pair<String, String>> =
    listOf("" to L(field.placeholder)) + field.options.map { it.first to L(it.second) }

// MARK: - File pieces

/** A document's face: its icon and its kind ("PDF") on the pink wash. */
@Composable
private fun RecordsDocFace(name: String) {
    val dot = name.lastIndexOf('.')
    val ext = if (dot < 0) "" else name.substring(dot + 1).take(4).uppercase()
    Column(
        Modifier.fillMaxSize().background(Theme.wash(Theme.pink, 16.0)),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph("file-text", 24.dp, tint = Theme.inkStrong)
        Text(
            if (ext.isEmpty()) L("File") else ext,
            style = brandText(10, FontWeight.ExtraBold),
            color = Theme.inkStrong,
            modifier = Modifier.background(Theme.surface, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** A file in the sheet: its face in a square, a round ✕ (or ↶) at its corner, its place in the upload order, its name. */
@Composable
private fun RecordsFileTile(
    name: String,
    caption: String?,
    dimmed: Boolean,
    place: Int? = null,
    icon: String,
    iconLabel: String,
    face: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme.chipRadius)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(if (dimmed) 0.4f else 1f)
                    .clip(shape)
                    .border(1.dp, Theme.borderSoft, shape),
            ) { face() }
            // Its place in the upload order, when there is more than one.
            if (place != null) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(5.dp).size(22.dp).background(Theme.inkStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$place", style = brandText(11, FontWeight.ExtraBold), color = Theme.surface)
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(26.dp)
                    .background(Theme.surface, CircleShape)
                    .border(1.dp, Theme.borderSoft, CircleShape)
                    .plainButton(label = iconLabel, onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(icon, 14.dp, tint = Theme.inkStrong)
            }
        }
        RecordsMiddleTruncatedText(name, Theme.text(11, FontWeight.Bold), Theme.inkStrong)
        if (caption != null) Text(caption, style = Theme.text(11), color = Theme.inkSoft)
    }
}

/** SwiftUI `.lineLimit(1).truncationMode(.middle)`: a file name's start, "…", its end (the extension). */
@Composable
private fun RecordsMiddleTruncatedText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val shown = remember(text, room, style) {
            fun fits(value: String) = measurer.measure(value, style, maxLines = 1, softWrap = false).size.width <= room
            fun cut(keep: Int): String {
                val head = (keep + 1) / 2
                return text.take(head) + "…" + text.takeLast(keep - head)
            }
            if (!bounded || fits(text)) text else {
                var low = 0
                var high = text.length - 1
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (fits(cut(mid))) low = mid else high = mid - 1
                }
                cut(low)
            }
        }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/**
 * SwiftUI `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)])`: as many columns of at
 * least `minimum` as fit, sharing the width; every tile one child of one layout, so a tile moved
 * to another row keeps its state (a drag goes on).
 */
@Composable
private fun RecordsAdaptiveGrid(minimum: androidx.compose.ui.unit.Dp, spacing: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val columns = maxOf(1, (width + gap) / (minimum.roundToPx() + gap))
        val tile = maxOf(0, (width - gap * (columns - 1)) / columns)
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(tile)) }
        val rows = placeables.chunked(columns)
        val heights = rows.map { row -> row.maxOf { it.height } }
        val height = heights.sum() + gap * maxOf(0, rows.size - 1)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, placeable -> placeable.place(c * (tile + gap), y) }
                y += heights[r] + gap
            }
        }
    }
}

/** A tile glides to its new place when the order changes (iOS `.animation(.easeInOut(duration: 0.2), value:)`). */
private fun Modifier.recordsAnimatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val goal = target ?: return@offset IntOffset.Zero
            val anim = animatable ?: Animatable(goal, IntOffset.VectorConverter).also { animatable = it }
            if (anim.targetValue != goal) {
                scope.launch { anim.animateTo(goal, tween(200, easing = FastOutSlowInEasing)) }
            }
            anim.value - goal
        }
}

// MARK: - Reading a picked file

private suspend fun readBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
}

private suspend fun decodePreview(data: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()
}

private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider.nonEmpty ?: uri.lastPathSegment?.substringAfterLast('/').nonEmpty ?: "file"
}

/** The shared picker's client as the record sheet's, and back. */
private object RecordsClientBridge {
    fun picked(c: RecordsClient): PickedClient = PickedClient(
        id = c.id, ownerName = c.ownerName, ownerEmail = c.ownerEmail, ownerPhone = c.ownerPhone, householdId = c.householdId,
        photoUrl = c.photoUrl, status = c.status,
        pets = (c.pets ?: emptyList()).map { PickedPet(id = it.id, petName = it.petName, petSpecies = it.petSpecies, petBreed = it.petBreed, photoUrl = it.photoUrl, status = it.status, microchipNumber = it.microchipNumber) },
        petCount = c.petCount,
    )

    fun records(c: PickedClient): RecordsClient = RecordsClient(
        id = c.id, ownerName = c.ownerName, ownerEmail = c.ownerEmail, ownerPhone = c.ownerPhone, householdId = c.householdId,
        photoUrl = c.photoUrl, petCount = c.petCount,
        pets = (c.pets ?: emptyList()).map { RecordsClientPet(id = it.id, petName = it.petName, petSpecies = it.petSpecies, petBreed = it.petBreed, photoUrl = it.photoUrl, microchipNumber = it.microchipNumber, status = it.status) },
        status = c.status,
    )
}
