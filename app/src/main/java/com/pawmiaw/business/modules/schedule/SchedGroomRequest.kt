package com.pawmiaw.business.schedule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import com.pawmiaw.business.services.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

//
//  SchedGroomRequest.kt (iOS SchedGroomRequest.swift)
//
//  "Lo que pidió el dueño" on the business side (web care/groom-request-staff.tsx,
//  groom-request-fields.tsx, lib/groom-request.ts): the booking sheet's
//  section (one request per pet booked, "same as last time" first when there
//  is an earlier groom), the visit page's "Today's request" card with the
//  extras on the bill against what the owner authorized, and its edit sheet.
//

// MARK: - Words

object SchedGroomWords {
    val areas = listOf("face", "feet", "tail", "body", "sanitary")
    val extras = listOf("dematting", "flea_bath", "deshedding", "teeth", "glands")
    const val photoLimit = 3

    fun style(value: String): String = when (value) {
        "puppy" -> L("Puppy cut")
        "shave" -> L("Shave")
        "breed" -> L("Breed standard")
        "tidy" -> L("Just a tidy")
        else -> L("Other")
    }

    fun area(value: String): String = when (value) {
        "face" -> L("Face")
        "feet" -> L("Feet")
        "tail" -> L("Tail")
        "body" -> L("Body")
        else -> L("Sanitary area")
    }

    fun service(value: String): String = SvcReportText.option(value)

    /** "Puppy cut — round ears", or the free text alone. */
    fun cut(style: String?, text: String?): String? {
        val label = style?.let { if (it == "other") null else style(it) }
        val detail = text.nonEmpty
        if (label != null && detail != null && detail.lowercase().startsWith(label.lowercase())) return detail
        if (label != null && detail != null) return "$label — $detail"
        return label ?: detail
    }

    /** What to do, with "same as last time" filled in (the server's `effectiveRequest`). */
    fun wanted(input: SchedGroomInput, last: SchedGroomLastTime?): SchedGroomWanted {
        val previous = if (input.sameAsLastTime) last else null
        val services = SvcReportText.groomServices.filter { input.services.contains(it) || (previous?.services ?: emptyList()).contains(it) }
        return SchedGroomWanted(
            services = services,
            style = input.style ?: previous?.style,
            styleText = input.styleText.nonEmpty ?: previous?.let { it.styleText ?: it.careStyle },
            length = input.length.nonEmpty ?: previous?.let { it.length ?: it.careTools },
        )
    }

    fun rows(wanted: SchedGroomWanted, areas: List<String>, note: String?): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val services = wanted.services ?: emptyList()
        if (services.isNotEmpty()) rows.add(L("Services") to services.joinToString(", ") { service(it) })
        cut(wanted.style, wanted.styleText)?.let { rows.add(L("Cut") to it) }
        wanted.length.nonEmpty?.let { rows.add(L("Length") to it) }
        if (areas.isNotEmpty()) rows.add(L("Areas") to areas.joinToString(", ") { area(it) })
        note.nonEmpty?.let { rows.add(L("Note") to it) }
        return rows
    }

    fun extrasText(cents: Int?, extras: List<String>, currency: String?): String? {
        if (cents == null || cents <= 0) return null
        val amount = Money.format(cents, currency)
        val which = extras.joinToString(", ") { service(it) }
        return if (which.isEmpty()) LF("Up to %@ in extras", amount) else LF("Up to %@ for %@", amount, which)
    }

    fun byline(request: SchedGroomRequest): String {
        val who = if (request.setBy?.side == "family") L("Set by the owner") else LF("Set by %@", request.setBy?.name ?: L("the team"))
        val checkIn = request.checkIn ?: return who
        return "$who · ${LF("Changed at check-in by %@ at %@", checkIn.name ?: L("the team"), DateText.time(checkIn.at))}"
    }

    fun lastTimeLine(last: SchedGroomLastTime): String {
        val input = SchedGroomInput()
        input.sameAsLastTime = true
        val wanted = wanted(input, last)
        return listOfNotNull(
            (wanted.services ?: emptyList()).joinToString(", ") { service(it) }.nonEmpty,
            cut(wanted.style, wanted.styleText),
            wanted.length.nonEmpty,
        ).joinToString(" · ")
    }

    /** A refusal from the request's endpoints, in words. */
    fun refusal(error: Throwable): String = errorText(schedAPIError(error))
}

// MARK: - The form

@Serializable
private data class SchedGroomUploaded(val url: String)

/**
 * What to do ("same as last time" or services, cut, length), the areas, reference photos and a note;
 * then the extras the owner authorizes and when they come back for the pet.
 */
@Composable
fun SchedGroomRequestFields(
    value: MutableState<SchedGroomInput>,
    lastTime: SchedGroomLastTime?,
    currency: String?,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val hasLast = lastTime?.exists ?: false
    val lastHint = lastTime?.at?.let { LF("Last time (%@)", DateText.date(it)) } ?: L("Their usual cut")

    fun update(change: SchedGroomInput.() -> SchedGroomInput) {
        value.value = value.value.change()
    }

    fun toggled(list: List<String>, item: String): List<String> = if (list.contains(item)) list.filter { it != item } else list + item

    suspend fun upload(picked: android.net.Uri) {
        uploading = true
        try {
            val jpeg = ImageUpload.load(picked) ?: return
            val result: SchedGroomUploaded = ImageUpload.upload(jpeg, to = "/provider-groom-requests/photo")
            // A photo lands after the upload: added to the request as it is now.
            if (value.value.photoUrls.size < SchedGroomWords.photoLimit) update { copy(photoUrls = photoUrls + result.url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.error(L("The photo did not upload. Try again."))
        } finally {
            uploading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { upload(uri) }
    }

    @Composable
    fun Pills(options: List<String>, selected: List<String>, label: (String) -> String, toggle: (String) -> Unit) {
        Disabled(disabled) {
            FlowLayout(spacing = 6.dp) {
                options.forEach { option ->
                    FilterChip(title = label(option), isSelected = selected.contains(option)) { toggle(option) }
                }
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FormField(label = L("What should the groomer do?")) {
            // The web's mode cards: each with its icon; "same" can't be chosen before a first groom.
            Disabled(disabled) {
                OptionCards(
                    options = listOf(
                        OptionCard(
                            true, title = L("Same as last time"), description = if (hasLast) lastHint else null, icon = "history", accent = Theme.aqua,
                            unavailableReason = if (hasLast) null else L("No earlier groom yet — choose what they should do."),
                        ),
                        OptionCard(false, title = L("Something different"), description = L("Choose the services, the cut and the length."), icon = "pen-line", accent = Theme.aqua),
                    ),
                    selection = stateOf<Boolean>({ value.value.sameAsLastTime && hasLast }) { same ->
                        if (same != value.value.sameAsLastTime) {
                            if (same && hasLast) {
                                update { copy(sameAsLastTime = true, services = emptyList(), style = null, styleText = "", length = "") }
                            } else {
                                update { copy(sameAsLastTime = false) }
                            }
                        }
                    },
                )
            }
        }
        if (value.value.sameAsLastTime && hasLast && lastTime != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.aqua, 14.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(lastHint, style = Theme.captionFont, color = Theme.inkSoft)
                Text(SchedGroomWords.lastTimeLine(lastTime).nonEmpty ?: L("Their usual cut"), style = Theme.bodyFont, color = Theme.inkStrong)
            }
        } else {
            FormField(label = L("Services")) {
                Pills(SvcReportText.groomServices, value.value.services, { SchedGroomWords.service(it) }) { item -> update { copy(services = toggled(services, item)) } }
            }
            FormField(label = L("Style")) {
                Pills(SvcReportText.styles, value.value.style?.let { listOf(it) } ?: emptyList(), { SchedGroomWords.style(it) }) { item ->
                    update { copy(style = if (style == item) null else item) }
                }
                Disabled(disabled) {
                    FieldChrome {
                        PlainTextField(
                            stateOf({ value.value.styleText }) { text -> update { copy(styleText = text) } },
                            L("e.g. round ears, leave the tail long"),
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            Disabled(disabled) {
                PMTextField(
                    L("Length"), text = stateOf({ value.value.length }) { text -> update { copy(length = text) } },
                    prompt = L("Blade number or cm, e.g. #7 or 2 cm"), optional = true,
                )
            }
        }
        FormField(label = L("Areas")) {
            Pills(SchedGroomWords.areas, value.value.areas, { SchedGroomWords.area(it) }) { item -> update { copy(areas = toggled(areas, item)) } }
        }
        FormField(label = L("Reference photo")) {
            FlowLayout(spacing = 8.dp) {
                value.value.photoUrls.forEach { url ->
                    Box(Modifier.size(76.dp)) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Theme.chipRadius)).background(Theme.surfaceMuted)) {
                            APIConfig.assetURL(url)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        if (!disabled) {
                            IconCircleButton(
                                systemImage = "x", label = L("Remove photo"), tint = Theme.destructive,
                                modifier = Modifier.align(Alignment.TopEnd).scale(0.8f),
                            ) { update { copy(photoUrls = photoUrls.filter { it != url }) } }
                        }
                    }
                }
                if (!disabled && value.value.photoUrls.size < SchedGroomWords.photoLimit) {
                    val shape = RoundedCornerShape(Theme.chipRadius)
                    Column(
                        Modifier
                            .size(76.dp)
                            .background(Theme.surfaceMuted, shape)
                            .dashedBorder(1.5.dp, Theme.borderSoft, Theme.chipRadius, on = 5.dp, off = 4.dp)
                            .plainButton(enabled = !uploading) {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.inkSoft)
                        } else {
                            Glyph("camera", 20.dp, tint = Theme.inkSoft)
                        }
                        Text(L("Add a photo"), style = Theme.text(11, FontWeight.Bold), color = Theme.inkSoft)
                    }
                }
            }
        }
        Disabled(disabled) {
            PMTextField(
                L("Note"), text = stateOf({ value.value.note }) { text -> update { copy(note = text) } },
                prompt = L("Anything else the groomer should know"), optional = true, multiline = true,
            )
        }
        // The extras the owner authorizes, and when they come back for the pet.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("banknote", 16.dp, tint = Theme.inkStrong)
                    Text(L("Extras the owner authorizes"), style = Theme.text(15, FontWeight.Bold), color = Theme.inkStrong)
                }
                Text(L("The groomer can add these without calling, up to this amount."), style = Theme.captionFont, color = Theme.inkSoft)
                Disabled(disabled) {
                    MoneyField(label = L("Up to"), cents = stateOf({ value.value.extrasUpToCents }) { cents -> update { copy(extrasUpToCents = cents) } }, optional = true)
                }
                Pills(SchedGroomWords.extras, value.value.extras, { SchedGroomWords.service(it) }) { item -> update { copy(extras = toggled(extras, item)) } }
            }
            Disabled(disabled) {
                MenuField(
                    label = L("Pickup time"),
                    options = listOf<Pair<Int?, String>>(null to L("No pickup time")) + (7 * 60..21 * 60 step 15).map { Pair<Int?, String>(it, SchedDay.timeLabel(it)) },
                    selection = stateOf({ value.value.pickupMinute }) { minute -> update { copy(pickupMinute = minute) } },
                    optional = true,
                )
            }
        }
    }
}

/** The request read back: the rows, the reference photos, the extras and pickup, who set it. */
@Composable
fun SchedGroomSummary(
    rows: List<Pair<String, String>>,
    photoUrls: List<String>,
    extras: String?,
    pickup: String?,
    byline: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, style = Theme.text(13, FontWeight.Bold), color = Theme.inkSoft, modifier = Modifier.width(84.dp).alignByBaseline())
                Text(value, style = Theme.text(15), color = Theme.inkStrong, modifier = Modifier.weight(1f).alignByBaseline())
            }
        }
        if (photoUrls.isNotEmpty()) {
            val description = L("Reference photo")
            FlowLayout(spacing = 8.dp) {
                photoUrls.forEach { url ->
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(Theme.chipRadius))
                            .background(Theme.surfaceMuted)
                            .semantics { contentDescription = description },
                    ) {
                        APIConfig.assetURL(url)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        if (extras != null || pickup != null) {
            FlowLayout(spacing = 6.dp) {
                if (extras != null) Chip(text = extras, icon = "banknote")
                if (pickup != null) Chip(text = LF("Pick up at %@", pickup), icon = "clock-3")
            }
        }
        if (byline != null) Text(byline, style = Theme.captionFont, color = Theme.inkSoft)
    }
}

// MARK: - The booking sheet's section

/** `SchedBookingGroomRequests.payload(_:petIds:)`. */
object SchedBookingGroomRequests {
    /** The requests to send: the pets booked now, leaving out any left empty. */
    fun payload(values: Map<String, SchedGroomInput>, petIds: List<String>): List<Map<String, Any?>> =
        petIds.mapNotNull { id ->
            val value = values[id]
            if (value == null || value.isEmpty) return@mapNotNull null
            val body = value.body.toMutableMap()
            body["providerClientPetId"] = id
            body
        }
}

/**
 * One request per pet on the booking. A pet with an earlier groom starts on "same as last time";
 * an untouched pet with no history stays empty and isn't sent.
 */
@Composable
fun SchedBookingGroomRequests(
    /** The pets booked: (id, name). */
    pets: List<Pair<String, String>>,
    values: MutableState<Map<String, SchedGroomInput>>,
    familyOnApp: Boolean,
    modifier: Modifier = Modifier,
) {
    ModalSection(
        title = L("What the owner asked for"),
        subtitle = if (familyOnApp) L("The groomer sees it on the visit; the family sees it in their app and can change it until the pet arrives.")
            else L("The groomer sees it on the visit. The family isn’t on PawMiaw: confirm it with them when the pet arrives."),
        modifier = modifier,
    ) {
        pets.forEach { (id, name) ->
            androidx.compose.runtime.key(id) {
                SchedBookingGroomPet(
                    petId = id,
                    name = if (pets.size > 1) name else null,
                    value = stateOf({ values.value[id] }) { next -> values.value = if (next == null) values.value - id else values.value + (id to next) },
                )
            }
        }
    }
}

@Composable
private fun SchedBookingGroomPet(petId: String, name: String?, value: MutableState<SchedGroomInput?>) {
    var care by remember { mutableStateOf<SchedCareForGroom?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(petId) {
        care = try {
            API.get<SchedCareForGroom>("/provider-client-pets/$petId/care")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        loaded = true
        // Start from the pet's history once it's known ("same as last time" when there is one).
        if (value.value == null) value.value = SchedGroomInput.empty(care?.lastGroom)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (name != null) Text(name, style = Theme.text(15, FontWeight.ExtraBold), color = Theme.inkStrong)
        val current = value.value
        if (current != null) {
            SchedGroomRequestFields(
                value = stateOf({ value.value ?: current }) { value.value = it },
                lastTime = care?.lastGroom, currency = care?.currency,
            )
        } else if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Theme.inkSoft)
            }
        }
    }
}

// MARK: - The visit page

/**
 * "Today's request": what the owner asked for, who set it, and the extras on the bill against what
 * they authorized (over warns, never blocks).
 */
@Composable
fun SchedGroomRequestCard(appointmentId: String, petName: String, canEdit: Boolean, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<SchedGroomView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }

    ReloadsOnScope {
        try {
            view = API.get<SchedGroomView>("/provider-appointments/$appointmentId/groom-request")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        } finally {
            loading = false
        }
    }

    val shown = view
    if (loading && shown == null) {
        SectionCard(title = L("Today's request"), icon = "scissors", accent = Theme.blue, modifier = modifier) { LoadingState() }
    } else if (shown != null && shown.isGroom == true) {
        val mayEdit = canEdit && shown.staffMayEdit == true
        val currency = shown.extras?.currency
        SectionCard(
            title = L("Today's request"), icon = "scissors", accent = Theme.blue, modifier = modifier,
            trailing = {
                if (mayEdit && shown.request != null) {
                    PMButton(L("Edit"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.header) { editing = true }
                }
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val request = shown.request
                val wanted = shown.wanted
                if (request != null && wanted != null) {
                    SchedGroomSummary(
                        rows = SchedGroomWords.rows(wanted, request.areas ?: emptyList(), request.note),
                        photoUrls = request.photoUrls ?: emptyList(),
                        extras = SchedGroomWords.extrasText(request.extrasUpToCents, request.extras ?: emptyList(), currency),
                        pickup = request.pickupTime?.let { SchedHours.startMinutes(it) }?.let { SchedDay.timeLabel(it) },
                        byline = SchedGroomWords.byline(request),
                    )
                    shown.extras?.let { extras ->
                        if (extras.status == "over") {
                            NoticeCard(tone = NoticeCard.Tone.problem, title = LF("Exceeds what the owner authorized by %@ — call before charging.", Money.format(extras.overByCents, currency)))
                        } else if (extras.status == "within") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph("circle-check", 14.dp, tint = Theme.inkSoft)
                                Text(
                                    LF("Extras on the bill: %@ of %@ authorized", Money.format(extras.chargedCents, currency), Money.format(extras.authorizedCents, currency)),
                                    style = Theme.captionFont, color = Theme.inkSoft,
                                )
                            }
                        }
                    }
                } else {
                    Text(L("Nothing asked for yet"), style = Theme.smallFont, color = Theme.inkSoft)
                    if (mayEdit) {
                        PMButton(L("Add what the owner asked for"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.compact) { editing = true }
                    }
                }
            }
        }
    }
    if (editing) {
        AppSheet(onDismissRequest = { editing = false }) {
            view?.let { current -> SchedGroomRequestSheet(view = current, petName = petName) { view = it } }
        }
    }
}

/** The request as a form, in a sheet of its own. */
@Composable
fun SchedGroomRequestSheet(
    view: SchedGroomView,
    petName: String,
    modifier: Modifier = Modifier,
    onSaved: (SchedGroomView) -> Unit = {},
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val original = remember { SchedGroomInput.from(view.request, view.lastTime) }
    val draft = remember { mutableStateOf(original) }
    var working by remember { mutableStateOf(false) }

    fun save() {
        if (draft.value.isEmpty) {
            Toast.error(L("Say what they should do, or choose same as last time"))
            return
        }
        val body = draft.value.body
        working = true
        scope.launch {
            try {
                val saved: SchedGroomView = SchedAPI.send("PUT", "/provider-appointments/${view.appointmentId}/groom-request", body)
                onSaved(saved)
                Toast.show(L("Saved"))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(SchedGroomWords.refusal(e))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("What the owner asked for"), subtitle = petName, systemImage = "scissors", accent = ModalAccent.aqua,
        isDirty = draft.value != original, primaryTitle = L("Save"), isWorking = working,
        onPrimary = { save() },
        modifier = modifier,
    ) {
        SchedGroomRequestFields(value = draft, lastTime = view.lastTime, currency = view.extras?.currency)
    }
}
