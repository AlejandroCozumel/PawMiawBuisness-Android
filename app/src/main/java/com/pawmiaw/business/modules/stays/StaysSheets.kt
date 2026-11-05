package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

//
//  StaysSheets.kt (iOS StaysSheets.swift)
//
//  A stay's smaller sheets: its dates (web stay-dates-sheet.tsx), the report
//  card to the family (stay-report-card-sheet.tsx), and the care plan with
//  its editor (care-plan-sheet.tsx, care-plan-editor.tsx).
//

// MARK: - Dates

/**
 * Extend, shorten or move a stay. Before the pet comes both ends move; once
 * it is in, only the day it leaves, from today on. The server checks the
 * room and the pet are free and tells the family.
 */
@Composable
fun StaysDatesSheet(stay: StaysStay, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val checkInDay = remember { mutableStateOf("") }
    val checkOutDay = remember { mutableStateOf("") }
    val checkInTime = remember { mutableStateOf("") }
    val checkOutTime = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val inHouse = stay.status == "checked_in"
    val today = stay.today ?: StaysDay.today()
    val end = if (stay.isDaycare) checkInDay.value else checkOutDay.value
    val nights = StaysDay.nights(kind = stay.kind, from = checkInDay.value, to = end)
    val before = StaysDay.nights(kind = stay.kind, from = stay.checkInDay, to = stay.checkOutDay)
    val changed = checkInDay.value != stay.checkInDay || end != stay.checkOutDay ||
        checkInTime.value != (stay.checkInTime ?: "") || checkOutTime.value != (stay.checkOutTime ?: "")

    val problem: String? = when {
        end < today -> if (inHouse) L("The pet is still here: choose today or a later day, or check it out.") else L("Choose days from today on.")
        // Not in yet: a past arrival day can stay as booked (only the end moves), never move to another past day.
        !inHouse && checkInDay.value != stay.checkInDay && checkInDay.value < today -> L("Choose days from today on.")
        !stay.isDaycare && end <= checkInDay.value -> L("Check-out must be at least one night after check-in.")
        else -> null
    }

    fun save() {
        if (!form.check(listOf("dates" to problem))) return
        working = true
        val body: Map<String, Any?> = if (inHouse) {
            mapOf("checkOutDay" to end, "checkOutTime" to checkOutTime.value)
        } else {
            mapOf("checkInDay" to checkInDay.value, "checkOutDay" to end, "checkInTime" to checkInTime.value, "checkOutTime" to checkOutTime.value)
        }
        scope.launch {
            try {
                API.patch<StaysStay>("/provider-stays/${stay.id}", body = body)
                Toast.show(if (stay.familyOnApp) L("Dates changed. The family has been told.") else L("Dates changed."))
                announceDataChanged()
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.errors = mapOf("dates" to errorText(e, fallback = L("Could not change this stay.")))
                Toast.error(e, fallback = L("Could not change this stay."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (inHouse) L("Extend or shorten the stay") else L("Change the dates"),
        subtitle = "${stay.petNames} · ${stay.clientName ?: ""}",
        systemImage = "calendar-range",
        accent = ModalAccent.aqua,
        isDirty = changed,
        form = form,
        primaryTitle = L("Save the dates"),
        isWorking = working,
        primaryIcon = "calendar-range",
        primaryDisabled = !changed,
        // The web footer's line: what the new dates cost, and the nights before and now.
        footerSummary = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (stay.rate > 0) Money.format(nights * stay.rate * stay.petCount, currency = stay.stayCurrency) else StaysWords.units(stay.kind, nights),
                    style = brandHeading(24), color = Theme.inkStrong,
                )
                Text(
                    if (nights == before) StaysWords.units(stay.kind, nights)
                    else LF("%@ before, %@ now", StaysWords.units(stay.kind, before), StaysWords.units(stay.kind, nights)),
                    style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft,
                )
            }
        },
        onPrimary = { save() },
        modifier = modifier,
    ) {
        ModalSection(title = L("When")) {
            if (inHouse) {
                Text(
                    LF("Arrived %@. The room stays held to the new day, if it is free.", StaysDay.short(stay.checkInDay)),
                    style = Theme.text(14), color = Theme.inkSoft,
                )
                DayField(label = L("Leaving on"), day = checkOutDay, min = today, key = "dates", form = form)
            } else {
                DayField(
                    label = if (stay.isDaycare) L("Day") else L("Check-in"), day = checkInDay,
                    min = minOf(today, stay.checkInDay), key = "dates", form = form,
                )
                if (!stay.isDaycare) {
                    DayField(label = L("Check-out"), day = checkOutDay, min = checkInDay.value)
                }
                TimeField(label = L("Arrival time"), value = checkInTime)
            }
            TimeField(label = L("Pickup time"), value = checkOutTime)
        }
    }

    LaunchedEffect(Unit) {
        if (checkInDay.value.isNotEmpty()) return@LaunchedEffect
        // A pet still here past its day: the sheet starts from today, the earliest it can now leave.
        checkInDay.value = stay.checkInDay
        checkOutDay.value = if (inHouse && stay.checkOutDay < today) today else stay.checkOutDay
        checkInTime.value = stay.checkInTime ?: ""
        checkOutTime.value = stay.checkOutTime ?: ""
    }
}

// MARK: - Report card

/**
 * The report card: a photo, a mood and a line, straight to the owner's app.
 * Only a family on PawMiaw receives it; otherwise it stays on the stay.
 *
 * - `pets`: the stay's pets (id = the stay pet's id).
 * - `familyOnApp`: whether the family is on PawMiaw; null reads the stay first.
 * - `visitId`: a home stay's visit it is written at (docs/homestays.md).
 */
@Composable
fun StaysReportCardSheet(
    stayId: String,
    pets: List<StaysPet>,
    familyOnApp: Boolean? = null,
    initialPetId: String? = null,
    visitId: String? = null,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var petId by remember { mutableStateOf<String?>(null) }
    var mood by remember { mutableStateOf<String?>(null) }
    val message = remember { mutableStateOf("") }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    var known by remember { mutableStateOf<StaysStay?>(null) }
    var working by remember { mutableStateOf(false) }
    var recordingIncident by remember { mutableStateOf(false) }
    var incidentSent by remember { mutableStateOf(false) }

    fun onApp(): Boolean? {
        known?.let { stay -> return stay.petList.any { (it.id == petId || petId == null) && it.linkedToHousehold == true } }
        val family = familyOnApp ?: return null
        val id = petId
        if (id != null) {
            val linked = pets.firstOrNull { it.id == id }?.linkedToHousehold
            if (linked != null) return linked
        }
        return family
    }

    val petName = pets.firstOrNull { it.id == petId }?.petName ?: pets.joinToString(", ") { it.petName }
    val shownOnApp = onApp()

    fun send() {
        if (!form.check(listOf("message" to (if (message.value.nonEmpty == null) L("Write a line for the owner.") else null)))) return
        working = true
        scope.launch {
            try {
                val fields = sortedMapOf("kind" to "report", "visibility" to "owner", "message" to message.value.trim())
                petId?.let { fields["stayPetId"] = it }
                mood?.let { fields["mood"] = it }
                visitId?.let { fields["visitId"] = it }
                val files = photo.value.data?.let { listOf(API.UploadFile(field = "photo", fileName = "photo.jpg", mimeType = "image/jpeg", data = it)) } ?: emptyList()
                API.multipart<StaysUpdate>("/provider-stays/$stayId/updates", fields = fields.toList(), files = files)
                when (onApp()) {
                    null -> Toast.show(L("Report card saved."))
                    true -> Toast.show(LF("Report card sent to %@’s family.", petName))
                    false -> Toast.show(LF("Report card saved on the stay. %@’s family isn’t on PawMiaw, so it wasn’t sent.", petName))
                }
                announceDataChanged()
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not send this."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Report card"),
        subtitle = shownOnApp?.let { if (it) L("Goes to the family in their PawMiaw app, with a notification.") else L("The family isn’t on PawMiaw: the report card stays on the stay.") },
        systemImage = "send",
        accent = ModalAccent.aqua,
        isDirty = message.value.isNotEmpty() || photo.value.data != null,
        form = form,
        primaryTitle = if (shownOnApp == false) L("Save the report card") else L("Send to the family"),
        isWorking = working,
        primaryIcon = "send",
        onPrimary = { send() },
        modifier = modifier,
    ) {
        // Something went wrong is an incident, not a report card: its own sheet tells the family and the owners properly.
        Row(
            Modifier
                .fillMaxWidth()
                .dashedBorder(2.dp, Theme.mix(Theme.pink, 55.0, Theme.borderSoft), Theme.boxRadius, on = 6.dp, off = 4.dp)
                .plainButton { recordingIncident = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("shield-alert", 20.dp, tint = Theme.pink)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("Something happened? Record an incident"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                Text(L("A bite, an injury, an escape: with how serious it was and what you did."), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
        if (pets.size > 1) {
            ModalSection(title = L("About")) {
                FlowLayout(spacing = 8.dp) {
                    FilterChip(title = L("Everyone"), isSelected = petId == null) { petId = null }
                    for (pet in pets) {
                        FilterChip(title = pet.petName, isSelected = petId == pet.id) { petId = pet.id }
                    }
                }
            }
        }
        ModalSection(title = L("How was the day?")) {
            val shape = RoundedCornerShape(Theme.boxRadius)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in StaysWords.moods) {
                    val chosen = mood == option.value
                    Column(
                        Modifier
                            .weight(1f)
                            .background(if (chosen) Theme.wash(Theme.lime, 30.0) else Theme.surface, shape)
                            .border(2.dp, if (chosen) Theme.blue else Theme.borderSoft, shape)
                            .plainButton { mood = if (chosen) null else option.value }
                            .semantics { selected = chosen }
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(option.emoji, fontSize = 24.sp)
                        FittedText(L(option.label), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft, minimumScaleFactor = 0.7f)
                    }
                }
            }
        }
        ModalSection(title = L("A line for the family")) {
            StaysPlainField(
                prompt = LF("e.g. %@ played ball all afternoon and ate everything", if (petName.isEmpty()) L("Luna") else petName),
                text = message, key = "message", form = form, multiline = true,
            )
        }
        ModalSection(title = L("Photo")) {
            PhotoField(label = L("Photo"), hint = L("Owners love seeing their pet mid-stay."), draft = photo, allowsRemove = false)
        }
    }

    LaunchedEffect(Unit) {
        if (petId == null) petId = initialPetId ?: (if (pets.size == 1) pets.firstOrNull()?.id else null)
        if (familyOnApp == null) known = runCatching { API.get<StaysStay>("/provider-stays/$stayId") }.getOrNull()
    }
    // Over the report card (it stays open behind); both close once the incident is sent.
    if (recordingIncident) {
        AppSheet(onDismissRequest = {
            recordingIncident = false
            if (incidentSent) {
                onDone()
                dismiss()
            }
        }) {
            val stayPets = known?.petList ?: pets
            IncidentRecordSheet(
                act = ConsentAct(stayId = stayId),
                pets = stayPets.mapNotNull { pet -> pet.providerClientPetId?.let { ActPet(id = it, name = pet.petName) } },
                familyName = known?.clientName,
                initialPetId = stayPets.firstOrNull { it.id == petId }?.providerClientPetId,
            ) { incidentSent = true }
        }
    }
}

// MARK: - Care plan

private data class StaysPlanDraft(
    val id: String,
    val items: List<StaysCareItem>,
    val notes: String,
)

/**
 * Change each pet's care plan once the stay is booked or running: a medicine
 * the vet just prescribed, another meal. Boarding and daycare save to the
 * stay; a home stay to its visits.
 */
@Composable
fun StaysCarePlanSheet(stay: StaysStay, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var intake by remember { mutableStateOf<StaysIntake?>(null) }
    var drafts by remember { mutableStateOf<List<StaysPlanDraft>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    fun save() {
        working = true
        scope.launch {
            try {
                val pets: List<Map<String, Any?>> = drafts.map { mapOf("stayPetId" to it.id, "carePlan" to StaysCarePlanEditor.clean(it.items, it.notes)) }
                val path = if (stay.isHome) "/provider-homestays/${stay.id}/care" else "/provider-stays/${stay.id}/care"
                API.send("PUT", path, body = mapOf("pets" to pets))
                Toast.show(if (stay.isHome) L("Care plan saved.") else L("Care plan saved. New care shows on the day’s board."))
                announceDataChanged()
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not save the care plan."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Edit the care plan"),
        subtitle = stay.petNames,
        systemImage = "clipboard-list",
        accent = ModalAccent.aqua,
        isDirty = loaded,
        primaryTitle = L("Save the plan"),
        isWorking = working,
        primaryIcon = "clipboard-list",
        primaryDisabled = drafts.isEmpty(),
        onPrimary = { save() },
        modifier = modifier,
    ) {
        if (!loaded) LoadingState()
        drafts.forEachIndexed { index, draft ->
            key(draft.id) {
                val pet = stay.petList.firstOrNull { it.id == draft.id }
                val known = intake?.pets?.firstOrNull { it.stayPetId == draft.id }
                Card(padding = 20.dp, spacing = 16.dp, flat = true) {
                    Text(pet?.petName ?: "", style = brandHeading(24), color = Theme.inkStrong)
                    StaysAllergies(allergies = known?.allergies ?: emptyList())
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(L("Care plan"), style = brandHeading(16.8), color = Theme.inkStrong)
                            Text(L("Medicines from your records and the owner’s routines are suggested below."), style = Theme.text(14), color = Theme.inkSoft)
                        }
                        StaysCarePlanEditor(
                            items = stateOf({ drafts.getOrNull(index)?.items ?: emptyList() }, { value ->
                                drafts = drafts.toMutableList().also { if (index in it.indices) it[index] = it[index].copy(items = value) }
                            }),
                            notes = stateOf({ drafts.getOrNull(index)?.notes ?: "" }, { value ->
                                drafts = drafts.toMutableList().also { if (index in it.indices) it[index] = it[index].copy(notes = value) }
                            }),
                            suggestions = known?.suggestedCare ?: emptyList(),
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        drafts = stay.petList.map { StaysPlanDraft(id = it.id, items = it.carePlan?.items ?: emptyList(), notes = it.carePlan?.notes ?: "") }
        intake = runCatching { API.get<StaysIntake>("/provider-stays/${stay.id}/intake") }.getOrNull()
        loaded = true
    }
}

/**
 * A pet's care plan, item by item (what, at what time), with the suggestions
 * not yet in it — the owner's routines and the medicines on the clinic's
 * chart — and the usual ones a tap away.
 */
@Composable
fun StaysCarePlanEditor(
    items: MutableState<List<StaysCareItem>>,
    notes: MutableState<String>,
    suggestions: List<StaysCareItem> = emptyList(),
    modifier: Modifier = Modifier,
) {
    fun update(key: String, change: (StaysCareItem) -> StaysCareItem) {
        items.value = items.value.map { if (it.key == key) change(it) else it }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in items.value) {
                key(entry.key) {
                    val shape = RoundedCornerShape(Theme.boxRadius)
                    Column(
                        Modifier.fillMaxWidth().border(1.dp, Theme.borderSoft, shape).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppMenu(
                                label = {
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .background(Theme.surface, CircleShape)
                                            .border(1.dp, Theme.borderSoft, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Glyph(StaysWords.taskIcon(entry.kind), 16.dp, tint = Theme.blue)
                                    }
                                },
                                accessibilityLabel = L("Kind"),
                            ) {
                                for (kind in StaysWords.taskKinds) {
                                    item(StaysWords.task(kind), icon = StaysWords.taskIcon(kind)) { update(entry.key) { it.copy(kind = kind) } }
                                }
                            }
                            FieldChrome(modifier = Modifier.weight(1f)) {
                                PlainTextField(
                                    stateOf({ items.value.firstOrNull { it.key == entry.key }?.label ?: "" }, { value -> update(entry.key) { it.copy(label = value) } }),
                                    prompt = L("What"),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            IconCircleButton(systemImage = "trash-2", label = L("Remove"), tint = Theme.inkSoft) {
                                items.value = items.value.filter { it.key != entry.key }
                            }
                        }
                        TimeField(
                            label = L("Time"),
                            value = stateOf({ items.value.firstOrNull { it.key == entry.key }?.time ?: "" }, { value ->
                                update(entry.key) { it.copy(time = if (value.isEmpty()) null else value) }
                            }),
                            from = "00:00", to = "23:45", allowsEmpty = true,
                        )
                    }
                }
            }
        }
        FlowLayout(spacing = 8.dp) {
            for (suggestion in suggestions.filter { suggestion -> items.value.none { it.key == suggestion.key } }) {
                FilterChip(
                    title = suggestion.label + (suggestion.time?.let { " · " + StaysDay.clock(it) } ?: ""),
                    isSelected = false, tone = Theme.lime,
                    icon = if (suggestion.source == "clinic_record") "pill" else "sparkles",
                ) { items.value = items.value + suggestion }
            }
            for (quick in StaysCarePlanEditor.quickAdd) {
                FilterChip(
                    title = L(quick.second) + (quick.third?.let { " · " + StaysDay.clock(it) } ?: ""),
                    isSelected = false, icon = "plus",
                ) {
                    items.value = items.value + StaysCareItem(
                        key = "desk:${UUID.randomUUID().toString().uppercase()}", kind = quick.first, label = L(quick.second),
                        time = quick.third, source = "front_desk",
                    )
                }
            }
        }
        PMTextField(
            L("Anything else the team should know"), text = notes,
            prompt = L("e.g. Loves tennis balls, eats slowly, pulls on the leash"), multiline = true,
        )
    }
}

object StaysCarePlanEditor {
    /** (kind, label, time): the usual ones, a tap away. */
    val quickAdd: List<Triple<String, String, String?>> = listOf(
        Triple("meal", "Breakfast", "08:00"), Triple("meal", "Dinner", "18:00"), Triple("walk", "Walk", "10:00"),
        Triple("play", "Group play", "15:00"), Triple("medication", "Medicine", null),
    )

    /** A plan to save: items with a name, trimmed; notes or nothing. */
    fun clean(items: List<StaysCareItem>, notes: String): Map<String, Any?> {
        val kept = items.mapNotNull { item ->
            val label = item.label.nonEmpty ?: return@mapNotNull null
            item.copy(label = label).body
        }
        return mapOf("items" to kept, "notes" to notes.nonEmpty)
    }
}
