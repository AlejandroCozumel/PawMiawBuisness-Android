package com.pawmiaw.business

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

//
//  Who something is for (iOS ClientPetPicker.swift, web client-pet-picker.tsx): search the
//  business's clients, choose one, then their pet (or several, or none). The one picker of
//  every sheet that starts with a family: booking, a stay, a home stay, admitting, a
//  prescription, a bill, the Store.
//
//  Search, the same everywhere: the server searches (`GET /provider-clients`), 250 ms after
//  typing stops, from 2 characters; under that the most recent clients show. Results come 20
//  at a time and load on as the list scrolls; the count is the server's. The chosen client
//  stays on top.
//

@Serializable
data class PickedPet(
    val id: String,
    val petName: String? = null,
    val petSpecies: String? = null,
    val petBreed: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val microchipNumber: String? = null,
    val weight: Double? = null,
) {
    val name: String get() = petName ?: ""
}

@Serializable
data class PickedClient(
    val id: String,
    val ownerName: String? = null,
    val ownerEmail: String? = null,
    val ownerPhone: String? = null,
    val householdId: String? = null,
    val photoUrl: String? = null,
    val status: String? = null,
    val pets: List<PickedPet>? = null,
    val petCount: Int? = null,
) {
    val name: String get() = ownerName ?: ""
    val activePets: List<PickedPet> get() = (pets ?: emptyList()).filter { it.status != "archived" }
}

object ClientSearch {
    /** Searches start at two characters (one matches almost everyone). */
    const val minimumCharacters = 2
    const val pageSize = 20
    val debounce: Duration = 250.milliseconds

    /** The text worth sending, or null (show the most recent clients). */
    fun query(text: String): String? {
        val trimmed = text.trim()
        return if (trimmed.codePointCount(0, trimmed.length) >= minimumCharacters) trimmed else null
    }
}

/** `ClientPetPicker.Pets`. */
object ClientPetPicker {
    enum class Pets { none, one, several }
}

private val pickerEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * The client (and pet) picker.
 * - `assignedMemberId`: only this member's clients (a vet who sees only their own).
 * - `petNote`: a line under a pet (why a room won't take it) and whether it can't be chosen,
 *   as (text, disabled).
 * - `petHint`: a line under "For {name}" (how booking several pets together works).
 */
@Composable
fun ClientPetPicker(
    client: MutableState<PickedClient?>,
    petIds: MutableState<List<String>>,
    pets: ClientPetPicker.Pets = ClientPetPicker.Pets.one,
    petRequired: Boolean = false,
    label: String = L("Find client"),
    key: String = "client",
    petKey: String = "pet",
    form: FormState? = null,
    assignedMemberId: String? = null,
    petNote: ((PickedPet) -> Pair<String, Boolean>?)? = null,
    petHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val search = remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<PickedClient>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf<String?>(null) }
    // The list is open (choosing); once a client is chosen it folds to that client so the pets
    // show. A client handed in (a pet's page, a follow-up) starts folded.
    var listOpen by remember { mutableStateOf(client.value == null) }
    val scope = rememberCoroutineScope()

    val query = ClientSearch.query(search.value)
    val chosenClient = client.value

    suspend fun load(reset: Boolean) {
        val text = ClientSearch.query(search.value)
        if (reset) asked = text ?: ""
        loading = true
        try {
            val offset = if (reset) 0 else found.size
            val page: Page<PickedClient>? = try {
                API.get(
                    "/provider-clients",
                    mapOf(
                        "limit" to ClientSearch.pageSize.toString(),
                        "offset" to offset.toString(),
                        "search" to text,
                        "assignedOrganizationMemberId" to assignedMemberId,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // A slower answer for an older search never replaces a newer one.
            if (text != ClientSearch.query(search.value)) return
            val items = page?.items ?: emptyList()
            found = if (reset) items else found + items.filter { item -> found.none { it.id == item.id } }
            total = page?.total
            hasMore = page?.hasMore ?: (items.size == ClientSearch.pageSize)
        } finally {
            loading = false
        }
    }

    fun choose(entry: PickedClient) {
        form?.clear(key)
        listOpen = false
        if (entry.id == client.value?.id) return
        client.value = entry
        val available = entry.activePets.filter { petNote?.invoke(it)?.second != true }
        petIds.value = if (pets != ClientPetPicker.Pets.none && available.size == 1) listOf(available[0].id) else emptyList()
    }

    fun toggle(id: String) {
        form?.clear(petKey)
        when (pets) {
            ClientPetPicker.Pets.none -> Unit
            ClientPetPicker.Pets.one -> petIds.value = if (petIds.value == listOf(id) && !petRequired) emptyList() else listOf(id)
            ClientPetPicker.Pets.several -> petIds.value = if (id in petIds.value) petIds.value.filter { it != id } else petIds.value + id
        }
    }

    LaunchedEffect(chosenClient?.id) { if (chosenClient?.id == null) listOpen = true }
    LaunchedEffect(query ?: "", assignedMemberId ?: "") {
        if (asked != null) delay(ClientSearch.debounce)
        load(reset = true)
    }

    val folded = chosenClient != null && !listOpen
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        AnimatedContent(
            targetState = folded,
            transitionSpec = {
                val time = tween<Float>(340, easing = pickerEasing)
                val slide = tween<androidx.compose.ui.unit.IntOffset>(340, easing = pickerEasing)
                if (targetState) {
                    // Folded (owner, 2026-11-04): only the chosen client, so the pets are in view.
                    (fadeIn(time) + slideInVertically(slide) { -it }) togetherWith (fadeOut(time) + slideOutVertically(slide) { -it })
                } else {
                    (fadeIn(time) + slideInVertically(slide) { -it }) togetherWith fadeOut(time)
                } using SizeTransform(clip = true) { _, _ -> tween(340, easing = pickerEasing) }
            },
            label = "client picker fold",
        ) { isFolded ->
            if (isFolded) {
                // A tap on it opens the list again to choose someone else.
                client.value?.let { chosen ->
                    ChosenRow(chosen, key = key, form = form, onOpen = { listOpen = true })
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    FormField(label = label, key = key, form = form, required = true) {
                        SearchField(prompt = L("Search by name, email, phone, or pet"), text = search, shape = SearchField.Shape.field)
                    }
                    ClientList(
                        key = key,
                        form = form,
                        shown = chosenClient?.takeIf { c -> found.none { it.id == c.id } }?.let { listOf(it) + found } ?: found,
                        chosenId = chosenClient?.id,
                        pinned = query != null && chosenClient != null && found.none { it.id == chosenClient.id },
                        foundIsEmpty = found.isEmpty(),
                        resultCount = total ?: found.size,
                        loading = loading,
                        hasMore = hasMore,
                        query = query,
                        clientError = form?.error(key),
                        onChoose = { choose(it) },
                        onLoadMore = { scope.launch { load(reset = false) } },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = folded && pets != ClientPetPicker.Pets.none,
            enter = fadeIn(tween(340, easing = pickerEasing)) + slideInVertically(tween(340, easing = pickerEasing)) { it },
            exit = fadeOut(tween(340, easing = pickerEasing)) + slideOutVertically(tween(340, easing = pickerEasing)) { it },
            label = "client picker pets",
        ) {
            client.value?.let { chosen ->
                PetChoice(
                    petKey = petKey,
                    form = form,
                    chosen = chosen,
                    pets = pets,
                    petRequired = petRequired,
                    petIds = petIds.value,
                    petNote = petNote,
                    petHint = petHint,
                    petError = form?.error(petKey),
                    onToggle = { toggle(it) },
                )
            }
        }
    }
}

// MARK: Clients

@Composable
private fun ClientList(
    key: String,
    form: FormState?,
    shown: List<PickedClient>,
    chosenId: String?,
    pinned: Boolean,
    foundIsEmpty: Boolean,
    resultCount: Int,
    loading: Boolean,
    hasMore: Boolean,
    query: String?,
    clientError: String?,
    onChoose: (PickedClient) -> Unit,
    onLoadMore: () -> Unit,
) {
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .formScrollTarget(key, form)
            .then(if (clientError != null) Modifier.border(1.dp, Theme.destructive.copy(alpha = 0.7f), box).padding(12.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(requiredLabel(L("Choose client")), Modifier.weight(1f).alignByBaseline(), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            Spacer(Modifier.width(8.dp))
            Text(
                (if (resultCount == 1) LF("%@ result", "1") else LF("%@ results", resultCount.toString())).uppercase(),
                Modifier.alignByBaseline(),
                style = Theme.text(12).tracking(1.4),
                color = Theme.inkSoft,
            )
        }
        if (loading && shown.isEmpty()) {
            PickerEmpty(L("Searching clients…"))
        } else if (shown.isEmpty()) {
            PickerEmpty(if (query != null) L("No clients matched that search.") else L("No clients exist yet. Add a client and their pet first."))
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 280.dp),
                contentPadding = PaddingValues(2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { entry ->
                    ClientTile(client = entry, chosen = entry.id == chosenId, pinned = pinned && entry.id == chosenId) { onChoose(entry) }
                    // The next 20 when the last row shows.
                    LaunchedEffect(entry.id, loading) {
                        if (entry.id == shown.lastOrNull()?.id && hasMore && !loading) onLoadMore()
                    }
                }
                if (loading && shown.isNotEmpty()) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Theme.blue, strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
        if (pinned && foundIsEmpty && !loading && query != null) {
            Text(LF("Nobody else matches “%@”.", query), style = Theme.text(14), color = Theme.inkSoft)
        }
        if (clientError != null) Text(clientError, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
    }
}

/** The chosen client alone, with the way back to the list. */
@Composable
private fun ChosenRow(chosen: PickedClient, key: String, form: FormState?, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().formScrollTarget(key, form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(requiredLabel(L("Client")), Modifier.weight(1f).alignByBaseline(), style = Theme.text(14, FontWeight.Medium), color = Theme.inkStrong)
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.alignByBaseline().plainButton(onClick = onOpen),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Change"), style = Theme.text(13, FontWeight.Bold), color = Theme.blue)
                Glyph("chevron-down", 14.dp, tint = Theme.blue)
            }
        }
        ClientTile(client = chosen, chosen = true, pinned = false, hint = L("Opens the list to choose another client"), action = onOpen)
    }
}

/** "Words *" with the coral mark. */
@Composable
private fun requiredLabel(text: String, required: Boolean = true) = buildAnnotatedString {
    val coral = Theme.coral
    append(text)
    if (required) withStyle(SpanStyle(color = coral)) { append(" *") }
}

// MARK: Pets

@Composable
private fun PetChoice(
    petKey: String,
    form: FormState?,
    chosen: PickedClient,
    pets: ClientPetPicker.Pets,
    petRequired: Boolean,
    petIds: List<String>,
    petNote: ((PickedPet) -> Pair<String, Boolean>?)?,
    petHint: String?,
    petError: String?,
    onToggle: (String) -> Unit,
) {
    val list = chosen.activePets
    val several = pets == ClientPetPicker.Pets.several
    val box = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .formScrollTarget(petKey, form)
            .then(if (petError != null) Modifier.border(1.dp, Theme.destructive.copy(alpha = 0.7f), box).padding(12.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                requiredLabel(if (several) L("Choose pets") else L("Choose pet"), required = petRequired),
                style = Theme.text(14, FontWeight.SemiBold),
                color = Theme.inkStrong,
            )
            Text(
                if (several) LF("For %@ · choose one or more", chosen.name) else LF("For %@", chosen.name),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
            if (petHint != null) Text(petHint, style = Theme.text(12), color = Theme.inkSoft)
        }
        if (list.isEmpty()) {
            PickerEmpty(L("This client has no pets yet."))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (pet in list) {
                    val note = petNote?.invoke(pet)
                    val isChosen = pet.id in petIds
                    PetTile(
                        pet = pet,
                        chosen = isChosen,
                        several = several,
                        note = note,
                        enabled = !(note?.second == true && !isChosen),
                    ) { onToggle(pet.id) }
                }
            }
        }
        if (petError != null) Text(petError, style = Theme.text(12, FontWeight.Medium), color = Theme.coral)
    }
}

/**
 * A client in the picker (web: rounded box, 2 pt border; chosen = PAW Blue edge on MIAW
 * yellow 16%, a check; the face ringed in blue).
 */
@Composable
private fun ClientTile(client: PickedClient, chosen: Boolean, pinned: Boolean, hint: String? = null, action: () -> Unit) {
    val petCount = client.petCount ?: client.activePets.size
    val contact = client.ownerEmail.nonEmpty ?: client.ownerPhone.nonEmpty ?: L("No contact yet")
    val box = RoundedCornerShape(Theme.boxRadius)
    Row(
        Modifier
            .fillMaxWidth()
            // iOS `.accessibilityHint`: the tap's own words.
            .then(if (hint != null) Modifier.semantics { onClick(label = hint) { action(); true } } else Modifier)
            .plainButton(onClick = action)
            .semantics { selected = chosen }
            .background(if (chosen) Theme.wash(Theme.lime, 16.0) else Theme.surface, box)
            .border(2.dp, if (chosen) Theme.blue else Theme.borderSoft, box)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Theme.wash(Theme.mango, 35.0))
                .border(2.dp, Theme.blue, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(NameText.firstLetters(client.name), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            val url = APIConfig.assetURL(client.photoUrl)
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(CircleShape))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(client.name, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(contact, style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (petCount == 1) LF("%@ pet", "1") else LF("%@ pets", petCount.toString())).uppercase(),
                    style = Theme.text(12).tracking(1.4),
                    color = Theme.inkSoft,
                    maxLines = 1,
                )
                if (client.householdId != null) {
                    TagChip(text = L("Family linked"), icon = "link-2", linked = true)
                } else {
                    TagChip(text = L("Clinic only"), icon = "building-2")
                }
                if (pinned) {
                    Text(
                        L("Chosen").uppercase(),
                        Modifier.background(Theme.wash(Theme.blue, 12.0), CapsuleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = Theme.text(12, FontWeight.Bold).tracking(1.4),
                        color = Theme.blue,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        if (chosen) Glyph("check", 20.dp, tint = Theme.blue)
    }
}

/** A pet in the picker: chosen = PAW Blue edge on Sky Blue 18%; several = a tick. */
@Composable
private fun PetTile(pet: PickedPet, chosen: Boolean, several: Boolean, note: Pair<String, Boolean>?, enabled: Boolean, action: () -> Unit) {
    val box = RoundedCornerShape(Theme.boxRadius)
    // iOS `@Environment(\.isEnabled)`: its own `.disabled` or the sheet's.
    val isEnabled = enabled && LocalIsEnabled.current
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton(enabled = enabled, onClick = action)
            .semantics { selected = chosen }
            .alpha(if (isEnabled) 1f else 0.5f)
            .background(if (chosen) Theme.wash(Theme.aqua, 18.0) else Theme.surface, box)
            .border(2.dp, if (chosen) Theme.blue else Theme.borderSoft, box)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetAvatar(name = pet.name, species = pet.petSpecies, photoURL = pet.photoUrl, size = 48.dp, modifier = Modifier.border(2.dp, Theme.blue, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pet.name, style = Theme.text(16, FontWeight.SemiBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(PickerSpecies.label(pet.petSpecies), pet.petBreed.nonEmpty).joinToString(" · ").nonEmpty ?: L("Pet profile"),
                style = Theme.text(14),
                color = Theme.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Text(
                    note.first,
                    style = Theme.text(12, FontWeight.SemiBold),
                    color = if (note.second) Theme.destructive else Theme.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        if (several) {
            Tick(on = chosen)
        } else if (chosen) {
            Glyph("check", 20.dp, tint = Theme.blue)
        }
    }
}

/** "Nothing here" inside the picker: a dashed box. */
@Composable
private fun PickerEmpty(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        style = Theme.text(14),
        color = Theme.inkSoft,
    )
}

/** The species' word (web getPetSpeciesLabel). */
object PickerSpecies {
    fun label(species: String?): String? = when (species?.lowercase()) {
        "dog" -> L("Dog")
        "cat" -> L("Cat")
        "bird" -> L("Bird")
        "rabbit" -> L("Rabbit")
        "fish" -> L("Fish")
        "reptile" -> L("Reptile")
        "rodent" -> L("Rodent")
        null, "" -> null
        else -> L("Other")
    }
}
