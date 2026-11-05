package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

//
//  StaysRooms.kt (iOS StaysRooms.swift)
//
//  Rooms & rules (web stays-rooms-settings.tsx): this place's kennels, suites,
//  condos, runs and playrooms — who each is for, how many it holds, its night
//  and daycare rates (a missing one says so), its photo — and the house
//  rules every new stay starts from: check-in and check-out times, daycare
//  hours, and the vaccines checked at check-in.
//

private class StaysRoomEditing(val unit: StaysUnit?) {
    val id: String get() = unit?.id ?: "new"
}

@Composable
fun StaysRoomsView(canManage: Boolean, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val sizeClass = LocalSizeClass.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var units by remember { mutableStateOf<List<StaysUnit>>(emptyList()) }
    var settings by remember { mutableStateOf<StaysSettings?>(null) }
    var draft by remember { mutableStateOf<StaysSettings?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<StaysRoomEditing?>(null) }
    var saving by remember { mutableStateOf(false) }

    val active = units.filter { it.status != "archived" }
    val archived = units.filter { it.status == "archived" }
    val currency = settings?.currency ?: workspace.currency

    suspend fun load() {
        try {
            units = API.get<List<StaysUnit>>("/provider-stays/units", mapOf("includeArchived" to "true", "locationId" to workspace.locationId))
            val fresh = API.get<StaysSettings>("/provider-stays/settings")
            if (draft == null || draft == settings) draft = fresh
            settings = fresh
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
        loaded = true
    }

    fun saveRules() {
        val current = draft ?: return
        saving = true
        scope.launch {
            try {
                val saved = API.put<StaysSettings>(
                    "/provider-stays/settings",
                    body = mapOf(
                        "checkInTime" to current.checkInTime, "checkOutTime" to current.checkOutTime,
                        "daycareStartTime" to current.daycareStartTime, "daycareEndTime" to current.daycareEndTime,
                        "requiredVaccines" to (current.requiredVaccines ?: emptyList()), "currency" to (current.currency ?: currency),
                    ),
                )
                settings = saved
                draft = saved
                Toast.show(L("Stay settings saved."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not save the settings."))
            } finally {
                saving = false
            }
        }
    }

    /** Each pet type a room is for, in its own colour (dogs aqua, cats mango, any lime). */
    fun speciesTone(species: String?): Color = when (species) {
        "dog" -> Theme.aqua
        "cat" -> Theme.mango
        "any" -> Theme.lime
        else -> Theme.lavender
    }

    /** A rate, or plainly no rate: a stay in this room then asks for its price when it's charged. */
    @Composable
    fun RateChip(cents: Int?, per: String, missing: String, icon: String, tone: Chip.Tone) {
        if (cents != null && cents > 0) {
            Chip(text = "${Money.format(cents, currency = currency)} $per", tone = tone, icon = icon)
        } else {
            Chip(text = missing, tone = Chip.Tone.muted, icon = icon, pending = true)
        }
    }

    /** A room: what it is and who it's for, how many it holds, and its two rates — a missing one says so. */
    @Composable
    fun RoomCard(unit: StaysUnit, modifier: Modifier) {
        val tone = speciesTone(unit.species)
        Card(padding = 0.dp, spacing = 0.dp, modifier = modifier) {
            Column(Modifier.clip(RoundedCornerShape(Theme.cardRadius))) {
                // The pet type's colour along the top, like a name tag.
                Box(Modifier.fillMaxWidth().height(6.dp).background(tone))
                APIConfig.assetURL(unit.photoUrl)?.let { url ->
                    Box(Modifier.fillMaxWidth().height(120.dp).background(Theme.surfaceMuted)) {
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        IconBadge(
                            icon = if (unit.species == "dog") "dog" else if (unit.species == "cat") "cat" else "paw-print",
                            accent = tone, size = 40.dp, iconSize = 20.dp, ink = IconBadge.Ink.strong, washPercent = 38.0,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(unit.name, style = brandHeading(20), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${StaysWords.unitKind(unit.kind)} · ${StaysWords.species(unit.species)}".uppercase(),
                                style = brandText(12, FontWeight.Bold).tracking(12 * 0.12), color = Theme.inkSoft,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (canManage) Chip(text = L("Edit"), tone = Chip.Tone.muted, icon = "pencil")
                    }
                    FlowLayout(spacing = 6.dp) {
                        Chip(text = LP(unit.seats, "%d pet", "%d pets"), tone = Chip.Tone.muted, icon = "users-round")
                        if (unit.unitKind != "playroom") RateChip(unit.nightlyRateCents, per = L("/ night"), missing = L("No night rate"), icon = "moon", tone = Chip.Tone.info)
                        RateChip(unit.dayRateCents, per = L("/ day"), missing = L("No daycare rate"), icon = "sun", tone = Chip.Tone.good)
                    }
                    unit.notes.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }

    @Composable
    fun Rooms(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = "bed-double", accent = Theme.aqua, size = 44.dp, iconSize = 20.dp, ink = IconBadge.Ink.strong, washPercent = 38.0)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("Rooms"), style = brandHeading(24), color = Theme.inkStrong)
                    Text(L("Each room holds a number of pets a night, at its own rate."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
            if (active.isEmpty()) {
                EmptyState(
                    systemImage = "bed-double", title = L("No rooms yet"),
                    message = L("Add your kennels, suites and cat condos. You can book stays without a room and assign it later."),
                    actionTitle = if (canManage) L("Add room") else null,
                    action = if (canManage) ({ editing = StaysRoomEditing(unit = null) }) else null,
                    accent = Theme.aqua, panel = true,
                )
            } else {
                val cells: List<StaysUnit?> = active + (if (canManage) listOf(null) else emptyList())
                StaysGrid(items = cells, minimum = 280.dp, spacing = 12.dp) { unit ->
                    if (unit != null) {
                        RoomCard(unit, Modifier.plainButton(enabled = canManage) { editing = StaysRoomEditing(unit = unit) })
                    } else {
                        // One more room, right where it will appear.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 144.dp)
                                .dashedBorder(2.dp, Theme.blue.copy(alpha = 0.35f), Theme.cardRadius, on = 6.dp, off = 4.dp)
                                .plainButton { editing = StaysRoomEditing(unit = null) },
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            IconBadge(icon = "plus", accent = Theme.aqua, size = 36.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 35.0)
                            Text(L("Add room"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                        }
                    }
                }
            }
            if (archived.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StaysEyebrow(text = L("Archived"), tint = Theme.inkSoft)
                    FlowLayout(spacing = 8.dp) {
                        for (unit in archived) {
                            Chip(
                                text = unit.name, tone = Chip.Tone.muted, icon = "archive",
                                modifier = Modifier.plainButton(enabled = canManage) { editing = StaysRoomEditing(unit = unit) },
                            )
                        }
                    }
                }
            }
        }
    }

    fun binding(get: (StaysSettings) -> String?, set: (StaysSettings, String) -> StaysSettings): MutableState<String> =
        stateOf({ draft?.let(get) ?: "" }, { value -> draft = draft?.let { set(it, value) } })

    /** One kind of stay's two times, in its colour. */
    @Composable
    fun RuleTimes(title: String, icon: String, tint: Color, first: Pair<String, MutableState<String>>, second: Pair<String, MutableState<String>>) {
        val shape = RoundedCornerShape(Theme.boxRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(tint, 10.0), shape)
                .border(1.5.dp, Theme.mix(tint, 55.0, Theme.borderSoft), shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = icon, accent = tint, size = 28.dp, iconSize = 16.dp, ink = IconBadge.Ink.strong, washPercent = 45.0)
                Text(title, style = brandHeading(18), color = Theme.inkStrong)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { Disabled(!canManage) { TimeField(label = first.first, value = first.second, from = "00:00", to = "23:45") } }
                Box(Modifier.weight(1f)) { Disabled(!canManage) { TimeField(label = second.first, value = second.second, from = "00:00", to = "23:45") } }
            }
        }
    }

    @Composable
    fun VaccineToggle(key: String) {
        val on = draft?.requiredVaccines?.contains(key) ?: false
        val who = StaysWords.vaccineSpecies(key)
        val shape = RoundedCornerShape(Theme.boxRadius)
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (on) Theme.wash(Theme.aqua, 16.0) else Theme.surface, shape)
                .border(1.5.dp, if (on) Theme.blue else Theme.borderSoft, shape)
                .plainButton(enabled = canManage) {
                    val list = (draft?.requiredVaccines ?: emptyList()).toMutableList()
                    if (on) list.removeAll { it == key } else list.add(key)
                    draft = draft?.copy(requiredVaccines = list)
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tick(on = on)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    StaysWords.vaccine(key), style = Theme.text(14, FontWeight.Bold), color = if (on) Theme.inkStrong else Theme.inkSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (who == "dog") "dog" else if (who == "cat") "cat" else "paw-print", 12.dp, tint = Theme.inkSoft)
                    Text(if (who == "both") L("all") else if (who == "dog") L("dogs") else L("cats"), style = Theme.text(12), color = Theme.inkSoft)
                }
            }
        }
    }

    @Composable
    fun HouseRules(modifier: Modifier) {
        val dirty = draft != settings
        Card(padding = 0.dp, spacing = 0.dp, modifier = modifier) {
            Column(Modifier.clip(RoundedCornerShape(Theme.cardRadius))) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.wash(Theme.butter, 22.0))
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    IconBadge(icon = "scroll-text", accent = Theme.butter, size = 44.dp, iconSize = 20.dp, ink = IconBadge.Ink.strong, washPercent = 38.0)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(L("House rules"), style = brandHeading(24), color = Theme.inkStrong)
                        Text(L("Used for every new stay. Each booking can still change its own times."), style = Theme.text(14), color = Theme.inkSoft)
                    }
                }
                StitchedDivider()
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RuleTimes(
                            L("Boarding"), "moon", Theme.aqua,
                            L("Check-in from") to binding({ it.checkInTime }, { s, v -> s.copy(checkInTime = v) }),
                            L("Check-out by") to binding({ it.checkOutTime }, { s, v -> s.copy(checkOutTime = v) }),
                        )
                        RuleTimes(
                            L("Daycare"), "sun", Theme.butter,
                            L("Drop-off from") to binding({ it.daycareStartTime }, { s, v -> s.copy(daycareStartTime = v) }),
                            L("Pickup by") to binding({ it.daycareEndTime }, { s, v -> s.copy(daycareEndTime = v) }),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("shield-check", 16.dp, tint = Theme.blue)
                            Text(L("Required vaccines"), style = brandHeading(18), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                            Chip(text = LF("%d of %d", draft?.requiredVaccines?.size ?: 0, StaysWords.vaccineKeys.size), tone = Chip.Tone.muted)
                        }
                        Text(
                            L("Checked at check-in against the pet’s records. A dog is only asked for dog vaccines, a cat for cat ones."),
                            style = Theme.text(12), color = Theme.inkSoft,
                        )
                        StaysGrid(items = StaysWords.vaccineKeys, minimum = 220.dp, spacing = 8.dp) { VaccineToggle(it) }
                    }
                    // Stays charge in the business currency; it is set once, for everything, in Settings.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Glyph("coins", 14.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(LF("Rates and stay bills are in %@, the business currency.", currency), style = Theme.text(12), color = Theme.inkSoft)
                            if (workspace.can(Permission.workspaceEdit)) {
                                Text(
                                    L("Settings → Region and money"), style = Theme.text(12, FontWeight.SemiBold), color = Theme.blue,
                                    modifier = Modifier.plainButton { navigator.push(ProviderRoute.section(ProviderSection.settings)) },
                                )
                            }
                        }
                    }
                }
                if (canManage) {
                    // Saving shows up when there's something to save, with a way back.
                    SaveBar(dirty = dirty, working = saving, saveTitle = L("Save house rules"), onDiscard = { draft = settings }, onSave = { saveRules() })
                }
            }
        }
    }

    Box(modifier) {
        if (!loaded) {
            LoadingState()
        } else if (error != null) {
            ErrorState(message = error ?: "") { load() }
        } else if (sizeClass == SizeClass.regular) {
            // Wide: the rooms beside the house rules (web xl grid 1.4fr / 1fr).
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Rooms(Modifier.weight(1.4f))
                if (draft != null) HouseRules(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Rooms(Modifier)
                if (draft != null) HouseRules(Modifier)
            }
        }
    }

    ReloadsOnScope { load() }
    editing?.let { item ->
        AppSheet(onDismissRequest = { editing = null }) {
            StaysRoomSheet(unit = item.unit, currency = currency, nextSortOrder = units.size, rooms = active) { scope.launch { load() } }
        }
    }
}

// MARK: - A room

@Composable
private fun StaysRoomSheet(
    unit: StaysUnit?,
    currency: String,
    nextSortOrder: Int,
    rooms: List<StaysUnit>,
    onSaved: () -> Unit,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val name = remember { mutableStateOf("") }
    val kind = remember { mutableStateOf("kennel") }
    val species = remember { mutableStateOf("dog") }
    val capacity = remember { mutableStateOf("1") }
    val nightly = remember { mutableStateOf<Int?>(null) }
    val daily = remember { mutableStateOf<Int?>(null) }
    val notes = remember { mutableStateOf("") }
    val active = remember { mutableStateOf(true) }
    val draft = remember { mutableStateOf(PhotoDraft()) }
    val shareWithKind = remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val hasPhoto = draft.value.data != null || (unit?.photoUrl != null && !draft.value.removed)
    /** Ten identical kennels, one upload: the other rooms of this type still without a photo. */
    val bare = rooms.filter { it.id != unit?.id && it.unitKind == kind.value && it.photoUrl == null && (unit == null || it.locationId == unit.locationId) }

    fun save() {
        if (!form.check(listOf("name" to (if (name.value.nonEmpty == null) L("Give the room a name.") else null)))) return
        working = true
        scope.launch {
            try {
                val values = mutableMapOf<String, Any?>(
                    "name" to name.value.trim(),
                    "kind" to kind.value,
                    "species" to species.value,
                    "capacity" to min(20, max(1, capacity.value.toIntOrNull() ?: 1)),
                    // A playroom is for the day: it has no night to price.
                    "nightlyRateCents" to (if (kind.value == "playroom") 0 else (nightly.value ?: 0)),
                    "dayRateCents" to (daily.value ?: 0),
                    "notes" to notes.value.nonEmpty,
                )
                val saved: StaysUnit = if (unit != null) {
                    values["status"] = if (active.value) "active" else "archived"
                    API.patch("/provider-stays/units/${unit.id}", body = values)
                } else {
                    values["sortOrder"] = nextSortOrder
                    values["locationId"] = workspace.locationId
                    API.post("/provider-stays/units", body = values)
                }
                // The photo goes up (or comes off) once the room itself is saved; a photo that fails never loses the rest.
                try {
                    val photo = draft.value.data
                    if (photo != null) {
                        ImageUpload.upload<StaysUnit>(photo, to = "/provider-stays/units/${saved.id}/photo")
                    } else if (unit?.photoUrl != null && draft.value.removed) {
                        API.send("DELETE", "/provider-stays/units/${saved.id}/photo")
                    }
                    if (hasPhoto && shareWithKind.value && bare.isNotEmpty()) {
                        API.send("POST", "/provider-stays/units/${saved.id}/photo/share", body = mapOf("onlyEmpty" to true))
                    }
                } catch (api: APIError) {
                    Toast.error(
                        when (api.code) {
                            "invalid_image" -> L("The photo must be a JPG, PNG or WebP image.")
                            "image_too_large" -> L("The photo must be 5 MB or smaller.")
                            else -> L("The room was saved, but the photo couldn’t be. Try again.")
                        },
                    )
                }
                Toast.show(if (unit == null) LF("%@ added.", saved.name) else L("Room saved."))
                announceDataChanged()
                onSaved()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.apply(e, known = mapOf("name" to "name"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (unit == null) L("Add room") else L("Edit room"),
        subtitle = L("Rooms appear on the board in the order you add them."),
        systemImage = "bed-double",
        accent = ModalAccent.aqua,
        isDirty = started && (name.value != (unit?.name ?: "") || !draft.value.isEmpty),
        form = form,
        primaryTitle = L("Save room"),
        isWorking = working,
        primaryIcon = "save",
        onPrimary = { save() },
    ) {
        PMTextField(L("Name"), text = name, key = "name", form = form, prompt = L("e.g. Suite 1, Kennel A"))
        MenuField(label = L("Type"), options = StaysWords.unitKinds.map { it to StaysWords.unitKind(it) }, selection = kind)
        MenuField(label = L("For"), options = StaysWords.speciesKeys.map { it to StaysWords.species(it) }, selection = species)
        PMTextField(
            if (kind.value == "playroom") L("Pets at a time") else L("Pets per night"),
            text = stateOf({ capacity.value }, { value -> capacity.value = value.filter { it.isDigit() } }),
            keyboard = KeyboardType.Number,
        )
        if (kind.value == "playroom") {
            Text(L("A playroom is for daycare: it doesn’t take nights."), style = Theme.text(14), color = Theme.inkSoft)
        } else {
            MoneyField(label = LF("Per pet per night (%@)", currency), cents = nightly)
        }
        MoneyField(label = LF("Per pet per daycare day (%@)", currency), cents = daily)
        Text(
            L("Leave a rate empty if this room doesn’t offer it: a stay here asks for its price (or a courtesy) when it’s charged."),
            style = Theme.text(12), color = Theme.inkSoft,
        )
        PMTextField(L("Notes"), text = notes, prompt = L("e.g. Heated floor, garden view, for dogs under 15 kg"), multiline = true)
        PhotoField(
            label = L("Photo"), hint = L("The room as a family would see it: the bed, the space, the view. Optional."),
            currentURL = unit?.photoUrl, draft = draft, optional = false, removedNote = L("The photo is removed when you save."),
        )
        if (hasPhoto && bare.isNotEmpty()) {
            ToggleRow(
                title = if (bare.size == 1) L("Also use it for the other room of this type") else LF("Also use it for the %d other rooms of this type", bare.size),
                subtitle = L("Only the ones without a photo: rooms that have their own keep it."),
                isOn = shareWithKind,
            )
        }
        if (unit != null) {
            ToggleRow(title = L("In use"), subtitle = L("Archived rooms leave the board and cannot be booked. Past stays keep them."), isOn = active)
        }
    }

    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        name.value = unit?.name ?: ""
        kind.value = unit?.unitKind ?: "kennel"
        species.value = unit?.species ?: "dog"
        capacity.value = (unit?.seats ?: 1).toString()
        nightly.value = if ((unit?.nightlyRateCents ?: 0) > 0) unit?.nightlyRateCents else null
        daily.value = if ((unit?.dayRateCents ?: 0) > 0) unit?.dayRateCents else null
        notes.value = unit?.notes ?: ""
        active.value = unit?.let { it.status != "archived" } ?: true
        started = true
    }
}
