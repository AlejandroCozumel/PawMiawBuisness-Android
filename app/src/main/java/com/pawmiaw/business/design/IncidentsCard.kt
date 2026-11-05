package com.pawmiaw.business

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.max

//
//  IncidentsCard.kt (iOS IncidentsCard.swift)
//
//  The incidents of an act — a visit, a stay or a home stay (web
//  incidents/incidents-card.tsx, incident-sheet.tsx, incident-bits.tsx;
//  backend docs/consents-incidents.md): each one with what kind, how serious,
//  when, what happened and what was done, who was there, the photos (shown
//  large on a tap) and whether the family was told, and its follow-up — add a
//  photo, close it with a note the family reads, or open it again. "Record an
//  incident" once the pet has arrived; the family and the owners are told as
//  soon as it is sent, so the last step says so before it goes.
//  Used by the visit page (Schedule), the stay, the home stay and a stay's
//  report card (Stays).
//

// MARK: - Models (use-incidents.ts)

@Serializable
data class Incident(
    val id: String,
    val providerClientPetId: String? = null,
    val petName: String? = null,
    val type: String,
    val severity: String,
    val occurredAt: String? = null,
    val whatHappened: String? = null,
    val actionsTaken: String? = null,
    val involved: String? = null,
    val photos: List<String>? = null,
    val status: String,
    val resolvedAt: String? = null,
    val resolvedByName: String? = null,
    val resolutionNote: String? = null,
    val familyVisible: Boolean? = null,
    val familyNotifiedAt: String? = null,
    val reportedByName: String? = null,
    val createdAt: String? = null,
    val legacy: Boolean? = null,
)

@Serializable
data class IncidentList(val items: List<Incident>? = null, val canRecord: Boolean? = null)

@Serializable
data class IncidentRecorded(val id: String? = null, val notified: Notified? = null, val photoErrors: Int? = null) {
    @Serializable
    data class Notified(val family: Int? = null, val owners: Int? = null)
}

// MARK: - Words (incidents-copy.ts)

object IncidentWords {
    val types = listOf("bite", "injury", "escape", "illness", "property_damage", "other")
    val severities = listOf("minor", "moderate", "serious")
    const val photoMax = 6

    fun type(type: String): String = when (type) {
        "bite" -> L("Bite")
        "injury" -> L("Injury")
        "escape" -> L("Escape or lost")
        "illness" -> L("Illness")
        "property_damage" -> L("Property damage")
        else -> L("Something else")
    }

    fun typeIcon(type: String): String = when (type) {
        "bite" -> "dog.fill"
        "injury" -> "bandage.fill"
        "escape" -> "door.left.hand.open"
        "illness" -> "allergens"
        "property_damage" -> "hammer.fill"
        else -> "questionmark.circle.fill"
    }

    fun severity(severity: String): String = when (severity) {
        "moderate" -> L("Needs attention")
        "serious" -> L("Serious")
        else -> L("Minor")
    }

    fun severityHint(severity: String): String = when (severity) {
        "moderate" -> L("Needs watching or a follow-up.")
        "serious" -> L("Needed a vet, or could harm the pet or someone.")
        else -> L("Handled on the spot, nothing more expected.")
    }

    /** The colour each severity wears (INCIDENT_STATE). */
    fun state(severity: String): ProviderState = when (severity) {
        "moderate" -> ProviderState.waiting
        "serious" -> ProviderState.problem
        else -> ProviderState.upcoming
    }

    fun error(error: Throwable, fallback: String): String {
        val api = error as? APIError
        if (api != null) {
            if (api.isOffline) return api.message
            when (api.code) {
                "act_required" -> return L("Choose the visit or the stay it happened in.")
                "act_not_started" -> return L("An incident is recorded once the pet has arrived.")
                "pet_not_in_act" -> return L("That pet isn’t part of this visit or stay.")
                "future_date" -> return L("It can’t be in the future.")
                "too_many_photos" -> return L("Up to 6 photos.")
                "invalid_photo" -> return L("Photos must be JPG, PNG or WebP, up to 8 MB.")
                "already_resolved" -> return L("This incident is already resolved.")
                "not_resolved" -> return L("This incident is still open.")
                "upload_failed" -> return L("The photo didn’t upload. Try again.")
                else -> {}
            }
            if (api.status == 403) return L("You don’t have permission to do this.")
            if (api.message.isNotEmpty() && (api.isRefusal || LocalizationManager.currentLanguage != AppLanguage.spanish)) return api.message
        }
        return L(fallback)
    }

    /** Open first, then the most recent. */
    fun sorted(items: List<Incident>): List<Incident> = items.sortedWith { a, b ->
        if (a.status == b.status) (b.occurredAt ?: "").compareTo(a.occurredAt ?: "")
        else if (a.status == "open") -1 else if (b.status == "open") 1 else 0
    }
}

// MARK: - The card

private sealed class IncidentSheet {
    data object record : IncidentSheet()
    data class resolve(val incident: Incident) : IncidentSheet()

    val id: String
        get() = when (this) {
            record -> "record"
            is resolve -> "resolve-${incident.id}"
        }
}

/** The incidents of a visit or a stay: each one with its follow-up, and "Record an incident". */
@Composable
fun IncidentsCard(
    act: ConsentAct,
    pets: List<ActPet>,
    familyName: String? = null,
    /** The pet is here (or was): a stay checked in or out, a visit under way or done. */
    recordable: Boolean,
    /** Inside a page section: no card of its own. */
    bare: Boolean = false,
    /** Bumped by an action row outside the card ("Record an incident" beside the stay's moves); the card opens its sheet. */
    recordRequest: Int = 0,
    /** Whether the card shows its own "Record an incident" (false when the page's action row has it). */
    showsRecordButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var list by remember { mutableStateOf<IncidentList?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var forbidden by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<IncidentSheet?>(null) }
    val scope = rememberCoroutineScope()
    val currentAct by rememberUpdatedState(act)

    val items = IncidentWords.sorted(list?.items ?: emptyList())
    val canRecord = list?.canRecord == true
    val openCount = items.count { it.status == "open" }
    val recordButton = showsRecordButton && canRecord && recordable

    suspend fun load() {
        val act = currentAct
        if (act.isEmpty) return
        try {
            list = API.get<IncidentList>("/provider-incidents", act.query)
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A role that doesn't see incidents doesn't see the card.
            if ((e as? APIError)?.status == 403) { forbidden = true; return }
            failed = IncidentWords.error(e, "The incidents could not be loaded.")
        }
    }

    if (!forbidden) ReloadsOnScope { load() }
    IncidentOnChange(recordRequest) { sheet = IncidentSheet.record }

    val content: @Composable () -> Unit = {
        val problem = failed
        when {
            list == null && problem == null -> Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) { IncidentSpinner() }
            problem != null && list == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(problem, style = Theme.smallFont, color = Theme.inkSoft, modifier = Modifier.weight(1f, fill = false))
                PMButton(L("Try again"), variant = ButtonVariant.outline, size = ButtonSize.compact) { scope.launch { load() } }
            }
            items.isEmpty() -> Text(
                L("No incidents. If something happens — a bite, an injury, an escape — record it here and the family and the owners are told."),
                style = Theme.smallFont,
                color = Theme.inkSoft,
            )
            else -> for (incident in items) {
                key(incident.id) {
                    IncidentItem(incident = incident, showPet = pets.size > 1, canFollowUp = canRecord) { sheet = IncidentSheet.resolve(incident) }
                }
            }
        }
    }

    when {
        forbidden -> {}
        bare -> Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recordButton) {
                PMButton(L("Record an incident"), systemImage = "exclamationmark.shield", variant = ButtonVariant.outline, size = ButtonSize.compact) { sheet = IncidentSheet.record }
            }
            content()
        }
        (list != null || failed != null) && (items.isNotEmpty() || recordButton || failed != null) -> {
            // A card of its own shows once there is something in it or something to do.
            Card(modifier = modifier) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("exclamationmark.shield.fill", 18.dp, tint = Theme.inkStrong)
                        Text(L("Incidents"), style = Theme.sectionFont, color = Theme.inkStrong)
                    }
                    if (openCount > 0) StatusChip(text = if (openCount == 1) L("1 open") else LF("%d open", openCount), state = ProviderState.problem)
                }
                content()
                if (recordButton) {
                    PMButton(L("Record an incident"), systemImage = "exclamationmark.shield", variant = ButtonVariant.outline, size = ButtonSize.compact, fullWidth = true) { sheet = IncidentSheet.record }
                }
            }
        }
        // Nothing to show yet: still here, so it loads and hears of changes.
        else -> Spacer(modifier.height(0.dp))
    }

    sheet?.let { which ->
        key(which.id) {
            AppSheet(onDismissRequest = { sheet = null }) {
                when (which) {
                    IncidentSheet.record -> IncidentRecordSheet(act = act, pets = pets, familyName = familyName)
                    is IncidentSheet.resolve -> IncidentResolveSheet(incident = which.incident)
                }
            }
        }
    }
}

/** One incident as the team sees it, with its follow-up. */
@Composable
fun IncidentItem(
    incident: Incident,
    showPet: Boolean,
    canFollowUp: Boolean,
    modifier: Modifier = Modifier,
    onResolve: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var reopening by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<IncidentPhoto?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun addPhoto(item: Uri) {
        adding = true
        try {
            val jpeg = ImageUpload.load(item)
            if (jpeg == null) {
                Toast.error(L("Could not add the photo. Try again."))
                return
            }
            try {
                ImageUpload.upload<Incident>(jpeg, "/provider-incidents/${incident.id}/photos")
                Toast.show(L("Photo added."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(IncidentWords.error(e, "Could not add the photo. Try again."))
            }
        } finally {
            adding = false
        }
    }

    fun reopen() {
        reopening = true
        scope.launch {
            try {
                API.post<Incident>("/provider-incidents/${incident.id}/reopen", emptyMap())
                Toast.show(L("Follow-up open again."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(IncidentWords.error(e, "Could not open it again. Try again."))
            } finally {
                reopening = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { addPhoto(uri) }
    }

    val shape = RoundedCornerShape(16.dp)
    val open = incident.status == "open"
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(if (open) 2.dp else 1.dp, if (open) Theme.pink else Theme.borderSoft, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowLayout(spacing = 6.dp) {
            HueChip(text = IncidentWords.type(incident.type), hue = Hue.peach, systemImage = IncidentWords.typeIcon(incident.type))
            StatusChip(text = IncidentWords.severity(incident.severity), state = IncidentWords.state(incident.severity))
            StatusChip(
                text = if (incident.status == "resolved") L("Resolved") else L("Follow-up open"),
                state = if (incident.status == "resolved") ProviderState.done else ProviderState.active,
                outlined = true,
            )
            val pet = incident.petName.nonEmpty
            if (showPet && pet != null) HueChip(text = pet, hue = Hue.grey, systemImage = "pawprint.fill")
        }
        Text(
            listOfNotNull(ConsentWords.date(incident.occurredAt), incident.reportedByName?.let { LF("Recorded by %@", it) }).joinToString(" · "),
            style = Theme.text(12),
            color = Theme.inkSoft,
        )
        IncidentBlock(L("What happened"), incident.whatHappened)
        IncidentBlock(L("What was done"), incident.actionsTaken)
        IncidentBlock(L("Who was involved"), incident.involved)
        val photos = incident.photos ?: emptyList()
        if (photos.isNotEmpty()) {
            IncidentGrid(photos, minimum = 72.dp, spacing = 8.dp) { index, url ->
                val description = LF("Photo %d of the incident", index + 1)
                Box(
                    Modifier
                        .incidentPlainPress { viewing = IncidentPhoto(url = url, index = index) }
                        .clearAndSetSemantics { contentDescription = description }
                ) {
                    AsyncImage(
                        model = APIConfig.assetURL(url),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(Theme.surfaceMuted),
                        error = ColorPainter(Theme.surfaceMuted),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
        if (incident.status == "resolved") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ProviderState.done.color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("checkmark.seal.fill", 12.dp, tint = Theme.inkSoft)
                    Text(L("How it ended").uppercase(), style = brandText(11, FontWeight.ExtraBold), color = Theme.inkSoft)
                }
                Text(incident.resolutionNote ?: "—", style = Theme.smallFont, color = Theme.inkStrong)
                Text(
                    listOfNotNull(incident.resolvedByName, incident.resolvedAt?.let { ConsentWords.date(it) }).joinToString(" · "),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
            }
        }
        Text(
            when {
                incident.legacy == true -> L("From an earlier report card: only the team saw it.")
                incident.familyNotifiedAt != null -> LF("The family was told %@", ConsentWords.date(incident.familyNotifiedAt))
                incident.familyVisible == true -> L("The family sees it in their app.")
                else -> L("The family isn’t on PawMiaw: tell them yourself.")
            },
            style = Theme.text(12),
            color = Theme.inkSoft,
        )
        if (canFollowUp) {
            FlowLayout(spacing = 8.dp) {
                if (photos.size < IncidentWords.photoMax && incident.legacy != true) {
                    PMButton(L("Add photo"), systemImage = "camera.fill", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = adding) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                if (incident.status == "open") {
                    PMButton(L("Close the follow-up"), systemImage = "checkmark.seal", size = ButtonSize.compact, action = onResolve)
                } else {
                    PMButton(L("Open again"), systemImage = "arrow.counterclockwise", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = reopening) { reopen() }
                }
            }
        }
    }

    viewing?.let { photo ->
        AppSheet(onDismissRequest = { viewing = null }) {
            IncidentPhotoSheet(photos = incident.photos ?: emptyList(), start = photo.index, title = incident.petName)
        }
    }
}

/** A titled paragraph of an incident: the head 11 heavy uppercase, the words under it. */
@Composable
private fun IncidentBlock(label: String, text: String?) {
    val value = text.nonEmpty ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = brandText(11, FontWeight.ExtraBold).copy(letterSpacing = 1.sp), color = Theme.inkSoft)
        Text(value, style = Theme.smallFont, color = Theme.inkStrong)
    }
}

data class IncidentPhoto(val url: String, val index: Int) {
    val id: Int get() = index
}

/** An incident's photos, large, one after another. */
@Composable
fun IncidentPhotoSheet(photos: List<String>, start: Int, title: String? = null, modifier: Modifier = Modifier) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, max(0, photos.size - 1))) { photos.size }
    ProviderModal(
        title = title ?: L("Photos"),
        subtitle = LF("Photo %d of the incident", pager.currentPage + 1),
        systemImage = "photo.on.rectangle",
        accent = ModalAccent.pink,
        cancelTitle = L("Close"),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                SubcomposeAsyncImage(
                    model = APIConfig.assetURL(photos[index]),
                    contentDescription = LF("Photo %d of the incident", index + 1),
                    contentScale = ContentScale.Fit,
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IncidentSpinner() } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The page TabView's dots.
            if (photos.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(photos.size) { index ->
                        Box(Modifier.size(7.dp).background(Color.White.copy(alpha = if (index == pager.currentPage) 1f else 0.45f), CircleShape))
                    }
                }
            }
        }
    }
}

// MARK: - Record an incident

/**
 * What kind, how serious, when, what happened and what was done, who was there,
 * which pet and up to six photos — then a last step that says who is told before it goes.
 */
@Composable
fun IncidentRecordSheet(
    act: ConsentAct,
    pets: List<ActPet>,
    familyName: String? = null,
    initialPetId: String? = null,
    modifier: Modifier = Modifier,
    /** Once it is sent (the report card it was opened from closes too). */
    onRecorded: () -> Unit = {},
) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    var type by remember { mutableStateOf<String?>(null) }
    var severity by remember { mutableStateOf("minor") }
    val occurredAt = remember { mutableStateOf(Instant.now()) }
    var petId by remember { mutableStateOf<String?>(null) }
    val whatHappened = remember { mutableStateOf("") }
    val actionsTaken = remember { mutableStateOf("") }
    val involved = remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var confirming by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val petName = pets.firstOrNull { it.id == petId }?.name ?: (if (pets.size == 1) pets.firstOrNull()?.name else null)

    LaunchedEffect(Unit) {
        if (petId == null) {
            petId = if (pets.size == 1) pets.firstOrNull()?.id else initialPetId?.takeIf { id -> pets.any { it.id == id } }
        }
    }

    fun take(items: List<Uri>) {
        if (items.isEmpty()) return
        scope.launch {
            for (item in items) {
                if (photos.size >= IncidentWords.photoMax) break
                ImageUpload.load(item)?.let { photos = photos + listOf(it) }
            }
            form.clear("photos")
        }
    }

    val remaining = IncidentWords.photoMax - photos.size
    // PhotosPicker(maxSelectionCount:): several at a time while two or more fit, one when one does.
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(max(2, remaining))) { take(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) take(listOf(uri)) }

    fun review() {
        val text = whatHappened.value.trim()
        if (!form.check(
                listOf(
                    "type" to (if (type == null) L("Choose what kind of incident it was.") else null),
                    "occurredAt" to (if (occurredAt.value.isAfter(Instant.now().plusSeconds(300))) L("It can’t be in the future.") else null),
                    "whatHappened" to (if (SignatureRules.characterCount(text) < 3) L("Say what happened.") else null),
                    "photos" to (if (photos.size > IncidentWords.photoMax) L("Up to 6 photos.") else null),
                )
            )
        ) return
        confirming = true
    }

    fun send() {
        val chosenType = type
        if (chosenType == null) { confirming = false; return }
        val fields = act.fields.toMutableList()
        val pet = petId
        if (pets.size > 1 && pet != null) fields.add("providerClientPetId" to pet)
        fields += listOf(
            "type" to chosenType,
            "severity" to severity,
            "occurredAt" to DateText.iso(occurredAt.value),
            "whatHappened" to whatHappened.value.trim(),
        )
        actionsTaken.value.nonEmpty?.let { fields.add("actionsTaken" to it) }
        involved.value.nonEmpty?.let { fields.add("involved" to it) }
        val files = photos.mapIndexed { index, data -> API.UploadFile("photos", "photo-${index + 1}.jpg", "image/jpeg", data) }
        val parts = fields.toList()
        working = true
        scope.launch {
            try {
                val created: IncidentRecorded = API.multipart("/provider-incidents", fields = parts, files = files)
                Toast.show(if ((created.notified?.family ?: 0) > 0) L("Incident recorded. The family and the owners were told.") else L("Incident recorded. The owners were told."))
                val failedPhotos = created.photoErrors
                if (failedPhotos != null && failedPhotos > 0) Toast.error(LF("%d photos didn’t upload. Add them again from the incident.", failedPhotos))
                announceDataChanged()
                onRecorded()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                confirming = false
                val api = e as? APIError
                form.apply(
                    APIError(api?.status ?: 400, IncidentWords.error(e, "Could not record the incident. Try again."), api?.code, api?.fields ?: emptyMap()),
                    known = mapOf("type" to "type", "whatHappened" to "whatHappened", "occurredAt" to "occurredAt"),
                )
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Record an incident"),
        subtitle = L("The family and the owners are told right away, and it stays open until you close the follow-up."),
        systemImage = "exclamationmark.shield.fill",
        accent = ModalAccent.pink,
        isDirty = type != null || whatHappened.value.isNotEmpty() || photos.isNotEmpty(),
        form = form,
        primaryTitle = if (confirming) L("Send the report") else L("Review and send"),
        isWorking = working,
        modifier = modifier,
        onPrimary = { if (confirming) send() else review() },
    ) {
        if (confirming) {
            NoticeCard(
                tone = NoticeCard.Tone.problem,
                title = familyName?.let { LF("%@ and the owners get this report now, with its photos. It can’t be taken back — you can add a closing note later.", it) }
                    ?: L("The family and the owners get this report now, with its photos. It can’t be taken back — you can add a closing note later."),
            )
            PMButton(L("Back to edit"), systemImage = "chevron.left", variant = ButtonVariant.outline, size = ButtonSize.compact) { confirming = false }
        }
        // The form, still and dimmed while the last step asks.
        Column(
            Modifier
                .alpha(if (confirming) 0.6f else 1f)
                .incidentDisabled(confirming || working),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModalSection(title = L("What kind")) {
                FormField(label = "", key = "type", form = form) {
                    IncidentGrid(IncidentWords.types, minimum = 150.dp, spacing = 8.dp) { _, value ->
                        val chosen = type == value
                        val tile = RoundedCornerShape(14.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(if (chosen) Theme.pink.copy(alpha = 0.18f) else Theme.surface, tile)
                                .border(2.dp, if (chosen) Theme.pink else Theme.borderSoft, tile)
                                .incidentPlainPress { type = value; form.clear("type") }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ink = if (chosen) Theme.inkStrong else Theme.inkSoft
                            Glyph(IncidentWords.typeIcon(value), 14.dp, tint = ink)
                            Text(IncidentWords.type(value), style = Theme.text(14, FontWeight.Bold), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            ModalSection(title = L("How serious")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (value in IncidentWords.severities) {
                        val state = IncidentWords.state(value)
                        val chosen = severity == value
                        val tile = RoundedCornerShape(14.dp)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (chosen) state.color.copy(alpha = 0.14f) else Theme.surface, tile)
                                .border(2.dp, if (chosen) state.color else Theme.borderSoft, tile)
                                .incidentPlainPress { severity = value }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(state.color, CircleShape))
                                Text(IncidentWords.severity(value), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                            }
                            Text(IncidentWords.severityHint(value), style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                }
            }
            DateTimeField(label = L("When it happened"), date = occurredAt, max = Instant.now().plusSeconds(300), key = "occurredAt", form = form)
            if (pets.size > 1) {
                FormField(label = L("Which pet")) {
                    FlowLayout(spacing = 8.dp) {
                        FilterChip(title = L("All of them"), isSelected = petId == null) { petId = null }
                        for (pet in pets) FilterChip(title = pet.name, isSelected = petId == pet.id) { petId = pet.id }
                    }
                }
            }
            PMTextField(
                L("What happened"),
                text = whatHappened,
                key = "whatHappened",
                form = form,
                prompt = petName?.let { LF("e.g. During group play %@ was nipped on the left ear by another dog.", it) }
                    ?: L("e.g. During group play the dog was nipped on the left ear by another dog."),
                hint = L("The family reads this. Say it plainly and calmly."),
                multiline = true,
            )
            PMTextField(
                L("What you did"),
                text = actionsTaken,
                prompt = L("e.g. Separated them, cleaned the wound and the vet checked it. We will watch it tonight."),
                optional = true,
                multiline = true,
            )
            PMTextField(
                L("Who was involved or there"),
                text = involved,
                prompt = L("e.g. Ana (walker), another guest: Rocky"),
                hint = L("For your team only."),
                optional = true,
            )
            ModalSection(title = L("Photos"), subtitle = L("Up to 6. They go to the family with the report.")) {
                FormField(label = "", key = "photos", form = form) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (photos.isNotEmpty()) {
                            IncidentGrid(photos, minimum = 84.dp, spacing = 8.dp) { index, data ->
                                Box(contentAlignment = Alignment.TopEnd) {
                                    val image = remember(data) { runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull() }
                                    if (image != null) {
                                        Image(
                                            image,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(84.dp)
                                                .clip(RoundedCornerShape(12.dp)),
                                        )
                                    }
                                    IconCircleButton(systemImage = "xmark", label = L("Remove photo"), modifier = Modifier.padding(4.dp)) {
                                        photos = photos.filterIndexed { i, _ -> i != index }
                                    }
                                }
                            }
                        }
                        if (photos.size < IncidentWords.photoMax) {
                            PMButton(L("Add photos"), systemImage = "camera.fill", variant = ButtonVariant.outline, size = ButtonSize.compact) {
                                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                if (remaining >= 2) pickMany.launch(request) else pickOne.launch(request)
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Close the follow-up

/** Closing the follow-up: how it ended, in words the family will read. */
@Composable
fun IncidentResolveSheet(incident: Incident, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val note = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val text = note.value.trim()
        if (!form.check(listOf("note" to (if (SignatureRules.characterCount(text) < 3) L("Write how it was resolved.") else null)))) return
        working = true
        scope.launch {
            try {
                API.post<Incident>("/provider-incidents/${incident.id}/resolve", mapOf("note" to text))
                Toast.show(if (incident.familyVisible == true) L("Closed. The family was told how it ended.") else L("Closed."))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.check(listOf("note" to IncidentWords.error(e, "Could not close it. Try again.")))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Close the follow-up"),
        subtitle = if (incident.familyVisible == true) L("The family reads this note: how the pet is and what comes next, if anything.") else L("A note for your team about how it ended."),
        systemImage = "checkmark.seal.fill",
        accent = ModalAccent.mint,
        isDirty = note.value.isNotEmpty(),
        form = form,
        primaryTitle = if (incident.familyVisible == true) L("Close and tell the family") else L("Close the follow-up"),
        isWorking = working,
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
        onPrimary = { submit() },
    ) {
        PMTextField(
            L("How it ended"),
            text = note,
            key = "note",
            form = form,
            prompt = L("e.g. The scratch healed well. No treatment needed; we checked it at pickup."),
            multiline = true,
        )
    }
}

// MARK: - Pieces of this file

/**
 * `LazyVGrid(columns: [GridItem(.adaptive(minimum:), spacing:)], spacing:)` inside a scrolling
 * sheet: as many equal columns as fit at `minimum` wide, rows `spacing` apart.
 */
@Composable
private fun <T> IncidentGrid(items: List<T>, minimum: Dp, spacing: Dp, cell: @Composable (Int, T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = max(1, ((maxWidth + spacing) / (minimum + spacing)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEachIndexed { row, chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    chunk.forEachIndexed { column, item ->
                        Box(Modifier.weight(1f)) { cell(row * columns + column, item) }
                    }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** SwiftUI's `ProgressView()`: a small grey spinner. */
@Composable
private fun IncidentSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.inkSoft)
}

/** `.onChange(of: value)`: runs on a change, not on the first composition. */
@Composable
private fun <T> IncidentOnChange(value: T, action: () -> Unit) {
    val last = remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (last.value != value) {
            last.value = value
            action()
        }
    }
}

/** `.buttonStyle(.plain)`: the whole area taps, dimmed while pressed. */
@Composable
private fun Modifier.incidentPlainPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .alpha(if (pressed) 0.7f else 1f)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}

/** `.disabled(_:)` on a group: nothing inside takes a touch. */
private fun Modifier.incidentDisabled(disabled: Boolean): Modifier =
    if (!disabled) this else this.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
    }
