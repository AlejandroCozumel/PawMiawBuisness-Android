package com.pawmiaw.business.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

//
//  PlacesLocationSheet.kt (iOS PlacesLocationSheet.swift)
//
//  Add or change a location (web components/app/locations/location-sheet.tsx):
//  its name, where it is and how to reach it, a photo of the place, its own
//  receipts (with two or more sedes), and the clock it runs on. A new one is two
//  steps — the place, then its clock and the hours it starts with (a copy of
//  another location's). Editing is one page, and changing the clock says what it
//  does to the times already booked. The plan is checked before this opens and
//  again by the server.
//

private enum class PlacesLocationStep { place, clock }

private data class PlacesLocationDraft(
    val name: String,
    val address: String,
    val phone: String,
    val email: String,
    val receiptName: String,
    val timezone: String,
    val copyFrom: String,
)

@Composable
fun PlacesLocationSheet(
    location: PlacesLocation?,
    /** Active locations, primary first: the default time zone and where hours are copied from. */
    active: List<PlacesLocation>,
    /** Every location (archived ones too): no two share a name. */
    all: List<PlacesLocation>,
    /** Visits still to come at the location being edited: what a new clock would move. */
    upcomingVisits: Int,
    modifier: Modifier = Modifier,
    onPlanRequired: () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val primary = remember { active.firstOrNull { it.primary } ?: active.firstOrNull() }
    val initial = remember {
        PlacesLocationDraft(
            name = location?.name ?: "",
            address = location?.address ?: "",
            phone = location?.phone ?: "",
            email = location?.email ?: "",
            receiptName = location?.receiptName ?: "",
            timezone = location?.timezone.nonEmpty ?: primary?.timezone.nonEmpty ?: ZoneId.systemDefault().id,
            copyFrom = primary?.id ?: "",
        )
    }
    var step by remember { mutableStateOf(PlacesLocationStep.place) }
    var draft by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    val logo = remember { mutableStateOf(PhotoDraft()) }
    var choosingZone by remember { mutableStateOf(false) }

    val editing = location != null
    // Its own receipts only matter once there are two sedes (a new one makes the second).
    val several = !editing || all.count { !it.isArchived } > 1
    val dirty = draft != initial || !photo.value.isEmpty || !logo.value.isEmpty || (!editing && step == PlacesLocationStep.clock)
    val zoneChanged = editing && draft.timezone != (location?.timezone ?: draft.timezone)

    fun placeProblems(): List<Pair<String, String?>> {
        val name = draft.name.trim()
        val nameProblem: String? = if (name.isEmpty()) {
            L("Give the location a name.")
        } else if (PlacesLocationRules.nameTaken(name, locations = if (all.isEmpty()) active else all, except = location?.id)) {
            L("Another location already has that name.")
        } else null
        val phone = draft.phone.trim()
        val digits = phone.count { it.isDigit() }
        val phoneProblem: String? = if (phone.isEmpty() || digits in 8..15) null else L("Check the phone number.")
        val email = draft.email.trim()
        val emailProblem: String? = if (email.isEmpty() || Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").containsMatchIn(email)) null else L("Check the email address.")
        return listOf("name" to nameProblem, "phone" to phoneProblem, "email" to emailProblem)
    }

    /** A photo or logo picked here goes up once the location is saved (or comes off then). */
    suspend fun apply(image: PhotoDraft, path: String, field: String, failure: (Throwable) -> String) {
        try {
            val data = image.data
            if (data != null) {
                ImageUpload.upload<PlacesLocation>(data, to = path, field = field)
            } else if (location != null && image.removed) {
                API.send("DELETE", path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.error(failure(e))
        }
    }

    fun primaryAction() {
        if (!editing && step == PlacesLocationStep.place) {
            if (form.check(placeProblems())) step = PlacesLocationStep.clock
            return
        }
        if (!form.check(placeProblems())) {
            step = PlacesLocationStep.place
            return
        }
        val fields = mutableMapOf<String, Any?>(
            "name" to draft.name.trim(),
            "timezone" to draft.timezone,
            "address" to draft.address.nonEmpty,
            "phone" to draft.phone.nonEmpty,
            "email" to draft.email.nonEmpty,
        )
        if (several) fields["receiptName"] = draft.receiptName.nonEmpty
        if (!editing && draft.copyFrom.isNotEmpty() && draft.copyFrom != primary?.id) fields["copyFromLocationId"] = draft.copyFrom

        saving = true
        val photoNow = photo.value
        val logoNow = logo.value
        scope.launch {
            try {
                val saved: PlacesLocation = if (location != null) {
                    API.patch("/provider-locations/${location.id}", fields)
                } else {
                    API.post("/provider-locations", fields)
                }
                // The photo goes up (or comes off) once the location itself is saved; a photo that fails never loses the rest.
                apply(photoNow, path = "/provider-locations/${saved.id}/photo", field = "photo", failure = ::photoError)
                apply(logoNow, path = "/provider-locations/${saved.id}/receipt-logo", field = "logo", failure = ::logoError)
                Toast.show(if (location != null) L("Location saved.") else LF("%@ added.@location", saved.name))
                placesLocationsChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is APIError) {
                    if (e.code == "location_name_taken") {
                        step = PlacesLocationStep.place
                        form.check(listOf("name" to L("Another location already has that name.")))
                        return@launch
                    }
                    if (e.code == "invalid_timezone") {
                        form.check(listOf("timezone" to L("Choose a valid time zone.")))
                        return@launch
                    }
                    // Past the plan's locations: close and show what to do, in the page's own words.
                    if (e.code == "location_plan_required" || e.code == "location_limit_reached") {
                        Toast.error(PlacesLocationRules.errorText(e))
                        dismiss()
                        onPlanRequired()
                        return@launch
                    }
                }
                form.apply(e, mapOf("name" to "name", "phone" to "phone", "email" to "email", "address" to "address", "timezone" to "timezone"))
            } finally {
                saving = false
            }
        }
    }

    // MARK: The place

    @Composable
    fun place() {
        PMTextField(
            L("Name"), text = stateOf({ draft.name }, { draft = draft.copy(name = it) }), key = "name", form = form,
            prompt = L("e.g. Downtown, North branch"), capitalization = KeyboardCapitalization.Words, required = true,
        )
        PMTextField(
            L("Address"), text = stateOf({ draft.address }, { draft = draft.copy(address = it) }), key = "address", form = form,
            prompt = L("Street and number, neighbourhood, city"), hint = L("Families see it on their visit, their stay and their receipt."),
        )
        PMTextField(L("Phone or WhatsApp"), text = stateOf({ draft.phone }, { draft = draft.copy(phone = it) }), key = "phone", form = form, keyboard = KeyboardType.Phone)
        PMTextField(L("Email"), text = stateOf({ draft.email }, { draft = draft.copy(email = it) }), key = "email", form = form, keyboard = KeyboardType.Email)

        Disabled(saving) {
            PhotoField(
                label = L("Photo"),
                hint = L("The building or the entrance: families see it when they choose where to book."),
                currentURL = location?.photoUrl,
                draft = photo,
                addTitle = L("Add a photo"),
                changeTitle = L("Change photo"),
                removedNote = L("The photo is removed when you save."),
            )
        }

        if (several) {
            ModalSection(
                title = L("Its receipts"),
                subtitle = L("Optional. Leave them empty and this sede’s receipts carry the business’s name and logo. The legal details are always the business’s."),
                systemImage = "receipt-text",
                tint = Theme.borderSoft,
            ) {
                PMTextField(
                    L("Name on receipts"), text = stateOf({ draft.receiptName }, { draft = draft.copy(receiptName = it) }),
                    prompt = Workspace.shared.current?.name ?: L("The business’s name"),
                    hint = L("When this sede goes by its own name, e.g. a second brand."),
                )
                Disabled(saving) {
                    PhotoField(
                        label = L("Logo on receipts"),
                        hint = L("Without one, this sede’s receipts print the business’s logo."),
                        currentURL = location?.receiptLogoUrl,
                        draft = logo,
                        addTitle = L("Use another logo"),
                        changeTitle = L("Change logo"),
                        removeTitle = L("Take this logo back out"),
                        removeSavedTitle = L("Use the business’s logo"),
                        removedNote = L("Back to the business’s logo when you save."),
                    )
                }
            }
        }
    }

    // MARK: The clock

    @Composable
    fun clock() {
        ModalSection(
            title = L("Its clock"),
            subtitle = L("“Today”, opening hours and the day’s totals at this location follow it."),
            systemImage = "earth",
            tint = Theme.aqua,
        ) {
            // The web's TimezonePicker under the section's line, its label read aloud only.
            val error = form.error("timezone")
            Column(Modifier.formScrollTarget("timezone", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Disabled(saving) {
                    PlacesTimeZoneButton(zone = draft.timezone, invalid = error != null) { choosingZone = true }
                }
                if (error != null) Text(error, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
            }
            val old = location?.timezone
            if (zoneChanged && old != null) {
                val shift = PlacesLocationRules.shiftMinutes(from = old, to = draft.timezone)
                PlacesConsequenceRow(
                    systemImage = "triangle-alert",
                    text = if (upcomingVisits > 0) {
                        if (upcomingVisits == 1) {
                            L("Visits keep the moment they were booked for, not the hour on the clock: 1 visit to come moves on this location’s schedule. Opening hours keep their times, now in the new zone.")
                        } else {
                            LF("Visits keep the moment they were booked for, not the hour on the clock: %d visits to come move on this location’s schedule. Opening hours keep their times, now in the new zone.", upcomingVisits)
                        }
                    } else {
                        L("Visits keep the moment they were booked for, not the hour on the clock. Opening hours keep their times, now in the new zone.")
                    },
                    title = if (shift == 0) {
                        LF("%@ and %@ read the same time today.", PlacesLocationRules.city(old), PlacesLocationRules.city(draft.timezone))
                    } else {
                        LF("Times already booked here will read %@.", PlacesLocationRules.shiftLabel(shift))
                    },
                )
            }
        }

        if (!editing) {
            ModalSection(title = L("Hours and booking rules"), systemImage = "clock-3", tint = Theme.butter) {
                if (active.size > 1) {
                    Text(L("It starts with a copy; change them afterwards in Availability."), style = Theme.text(14), color = Theme.inkSoft)
                    ChoiceCards(
                        options = active.map { Triple(it.id, LF("Same as %@", it.name), null as String?) },
                        selection = stateOf({ draft.copyFrom }, { draft = draft.copy(copyFrom = it) }),
                    )
                } else {
                    Text(
                        LF("It starts with the hours and booking rules of %@; change them afterwards in Availability.", primary?.name ?: L("the main location")),
                        style = Theme.text(14),
                        color = Theme.inkSoft,
                    )
                }
            }
        }
    }

    ProviderModal(
        title = location?.let { LF("Edit %@", it.name) } ?: L("Add a location"),
        subtitle = L("A place the business works from. It keeps its own hours, rooms, registers, visits and stays; clients and the catalog are shared."),
        systemImage = "map-pin",
        accent = ModalAccent.aqua,
        isDirty = dirty,
        form = form,
        primaryTitle = if (!editing && step == PlacesLocationStep.place) L("Continue") else (if (editing) L("Save location") else L("Add location")),
        isWorking = saving,
        steps = if (editing) null else ModalSteps(listOf(L("The place"), L("Clock and hours")), current = if (step == PlacesLocationStep.place) 0 else 1),
        onBack = if (!editing && step != PlacesLocationStep.place) { { step = PlacesLocationStep.place } } else null,
        primaryIcon = if (!editing && step == PlacesLocationStep.place) null else (if (editing) "save" else "plus"),
        onPrimary = { primaryAction() },
        modifier = modifier,
    ) {
        if (editing) {
            place()
            clock()
        } else if (step == PlacesLocationStep.place) {
            place()
        } else {
            clock()
        }
    }

    if (choosingZone) {
        AppSheet(onDismissRequest = { choosingZone = false }) {
            PlacesTimeZonePicker(
                selection = draft.timezone,
                suggestions = listOf(primary?.timezone, ZoneId.systemDefault().id).mapNotNull { it.nonEmpty },
            ) { draft = draft.copy(timezone = it) }
        }
    }
}

private fun photoError(error: Throwable): String = when ((error as? APIError)?.code) {
    "invalid_image" -> L("The photo must be a JPG, PNG or WebP image.")
    "image_too_large" -> L("The photo must be 5 MB or smaller.")
    else -> L("The photo couldn’t be saved. Try again.")
}

private fun logoError(error: Throwable): String = when ((error as? APIError)?.code) {
    "invalid_image" -> L("The logo must be a JPG, PNG or WebP image.")
    "image_too_large" -> L("The logo must be 3 MB or smaller.")
    else -> L("The logo couldn’t be saved. Try again.")
}

// MARK: - Time zone

/** The web's TimezonePicker trigger: a 48 field with the blue globe, the zone and a chevron. */
@Composable
fun PlacesTimeZoneButton(
    zone: String,
    invalid: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val label = PlacesTimeZones.label(zone)
    val name = L("Time zone")
    FieldChrome(
        invalid = invalid,
        modifier = modifier
            .plainButton { action() }
            .semantics {
                contentDescription = name
                stateDescription = label
            },
    ) {
        Glyph("earth", 16.dp, tint = Theme.blue)
        Spacer(Modifier.width(12.dp))
        Text(label, style = Theme.text(14), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Glyph("chevron-down", 16.dp, tint = Theme.inkSoft)
    }
}

/**
 * Choose a time zone (web `TimezonePicker`): the zone in use, the suggestions (the main
 * location's, this device's), the common ones, or a search. A location's clock and the
 * business hours' clock both open it.
 */
@Composable
fun PlacesTimeZonePicker(
    selection: String,
    suggestions: List<String> = emptyList(),
    /** A line under a suggestion ("This device’s time zone"). */
    notes: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val query = remember { mutableStateOf("") }
    val searching = query.value.nonEmpty != null

    val results: List<String> = run {
        val found = PlacesTimeZones.search(query.value)
        if (searching) found else {
            val seen = mutableSetOf<String>()
            (listOf(selection) + suggestions + found).filter { it.isNotEmpty() && seen.add(it) }
        }
    }

    ProviderModal(
        title = L("Time zone"),
        systemImage = "earth",
        accent = ModalAccent.aqua,
        cancelTitle = L("Close"),
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.large)),
    ) {
        SearchField(prompt = L("Search a city or zone"), text = query, shape = SearchField.Shape.field)
        Column(Modifier.fillMaxWidth()) {
            results.forEach { zone ->
                val chosen = zone == selection
                Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics { selected = chosen }
                        .plainButton {
                            onPick(zone)
                            dismiss()
                        }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(PlacesTimeZones.label(zone), style = Theme.text(14, if (chosen) FontWeight.Bold else FontWeight.Normal), color = Theme.inkStrong)
                        // Searching by region ("America/") still shows what matched.
                        val line = if (searching) zone.replace("_", " ") else notes[zone]
                        if (line != null) Text(line, style = Theme.text(12), color = Theme.inkSoft)
                    }
                    if (chosen) Glyph("check", 16.dp, tint = Theme.blue)
                }
                RowDivider()
            }
            if (results.isEmpty()) {
                Text(LF("No time zone matches “%@”.", query.value), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}
