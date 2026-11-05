package com.pawmiaw.business.clients

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

//
//  ClientsForms.kt (iOS ClientsForms.swift)
//
//  The client and pet forms (web: provider-client-sheet.tsx, the edit
//  sheets, provider-client-pet-sheet.tsx): add a client with their first
//  pet in two steps, edit the client, add a pet, edit a pet. Every one
//  fails the same way (the form error standard) and answers the server's
//  "¿Eres tú?" when the email is the person's own on PawMiaw.
//

// MARK: - Pieces

/** One optional read (Swift `try?`): null when it fails; a cancel still cancels. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    null
}

/**
 * What a pet form holds, shared by "first pet", "add pet" and "edit pet" (iOS an
 * `ObservableObject`): each field is a state (`nameState` is Swift's `$draft.name`).
 */
@Stable
class ClientsPetDraft() {
    val nameState = mutableStateOf("")
    var name by nameState
    val speciesState = mutableStateOf("dog")
    var species by speciesState
    val breedState = mutableStateOf("")
    var breed by breedState
    val sexState = mutableStateOf("unknown")
    var sex by sexState
    var hasBirthday by mutableStateOf(false)
    var birthday by mutableStateOf(Instant.now())
    val weightState = mutableStateOf("")
    var weight by weightState
    val unitState = mutableStateOf("kg")
    var unit by unitState
    val neuteredState = mutableStateOf(false)
    var neutered by neuteredState
    val microchipState = mutableStateOf("")
    var microchip by microchipState
    val notesState = mutableStateOf("")
    var notes by notesState
    val preferredMemberIdState = mutableStateOf("")
    var preferredMemberId by preferredMemberIdState
    val photoState = mutableStateOf(PhotoDraft())
    var photo by photoState

    constructor(pet: ClientsPet) : this() {
        name = pet.petName
        species = pet.petSpecies ?: "dog"
        breed = pet.petBreed ?: ""
        sex = pet.sex ?: "unknown"
        DateText.parse(pet.birthDate)?.let { born -> hasBirthday = true; birthday = born }
        weight = ClientsText.kilogramsInput(pet.weight)
        neutered = pet.isNeutered ?: false
        microchip = pet.microchipNumber ?: ""
        notes = pet.notes ?: ""
        preferredMemberId = pet.preferredMember?.id ?: ""
    }

    val isEmpty: Boolean get() = name.isEmpty() && breed.isEmpty() && weight.isEmpty() && microchip.isEmpty() && notes.isEmpty() && photo.data == null

    val grams: Int?
        get() {
            val cleaned = weight.trim().replace(",", ".")
            val value = cleaned.toDoubleOrNull() ?: return null
            if (value <= 0) return null
            return (value * (if (unit == "kg") 1000.0 else 453.59237)).roundToInt()
        }

    val weightInvalid: Boolean get() = weight.trim().isNotEmpty() && grams == null

    /** Every field at once: a change of any of them (iOS `objectWillChange`). */
    fun fingerprint(): List<Any?> = listOf(name, species, breed, sex, hasBirthday, birthday, weight, unit, neutered, microchip, notes, preferredMemberId, photo)

    /** The fields as the API takes them. A linked pet's name and species are the family's. */
    fun body(linked: Boolean = false, withPreferred: Boolean = false): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "petBreed" to breed.nonEmpty,
            "sex" to (if (sex == "unknown") null else sex),
            "birthDate" to (if (hasBirthday) DateText.dayKey(birthday, zone = ZoneId.systemDefault()) else null),
            "weight" to grams,
            "isNeutered" to neutered,
            "microchipNumber" to microchip.nonEmpty,
            "notes" to notes.nonEmpty,
        )
        if (!linked) {
            body["petName"] = name.trim()
            body["petSpecies"] = species
        }
        if (withPreferred) body["preferredOrganizationMemberId"] = preferredMemberId.nonEmpty
        return body
    }
}

/**
 * The pet's fields (web: the "Perfil de la mascota" section, pink). A linked
 * pet keeps the family's name, species and photo: its own section says so.
 */
@Composable
fun ClientsPetFields(
    draft: ClientsPetDraft,
    form: FormState,
    linked: Boolean = false,
    currentPhotoURL: String? = null,
    /** The pet notes' placeholder (the first pet's sheet says "intake"). */
    notesPrompt: String = L("Anything the clinic should remember about this pet."),
    /** A linked pet: its name, species and last sync, read from the family. */
    linkedPet: ClientsPet? = null,
    /** Under the linked pet's values: where the family's profile differs. */
    linkedExtra: (@Composable () -> Unit)? = null,
    /** After the microchip: the preferred vet (editing a pet). */
    preferredVet: (@Composable () -> Unit)? = null,
    /** The name's placeholder ("e.g. Nina" when adding another pet). */
    namePrompt: String = L("e.g. Luna"),
    /** Said under the name, not refused: the client already has a pet called that. */
    nameWarning: String? = null,
) {
    if (linked) {
        ModalSection(title = L("Linked household profile"), systemImage = "link-2", tint = Theme.aqua) {
            val synced = linkedPet?.profileLastSyncedAt.nonEmpty?.let { LF("Last synced %@.", DateText.date(it)) }
            Text(
                listOfNotNull(L("Name, species and photo come from the family. Breed, sex, birthday, weight and microchip are your clinic’s own record: the family’s profile only fills what you leave empty."), synced).joinToString(" "),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
            if (linkedPet != null) {
                FactRow(label = L("Name"), value = linkedPet.petName)
                FactRow(label = L("Species"), value = ClientsText.species(linkedPet.petSpecies))
            }
            linkedExtra?.invoke()
        }
    }
    ModalSection(title = L("Pet profile"), systemImage = "paw-print", tint = Theme.pink) {
        if (!linked) {
            PhotoField(label = L("Pet photo"), hint = L("Optional, but useful for recognition."), currentURL = currentPhotoURL, draft = draft.photoState, shape = PhotoField.Shape.circle, optional = false, allowsRemove = false, addTitle = L("Upload photo"))
            PMTextField(L("Pet name"), text = draft.nameState, key = "petName", form = form, prompt = namePrompt, capitalization = KeyboardCapitalization.Words, required = true)
            if (nameWarning != null && form.error("petName") == null) {
                NoticeCard(tone = NoticeCard.Tone.warning, message = nameWarning, icon = null)
            }
            MenuField(label = L("Pet species"), options = ClientsText.speciesOptions, selection = draft.speciesState, required = true)
        }
        PMTextField(L("Breed"), text = draft.breedState, prompt = L("Optional breed or type"), capitalization = KeyboardCapitalization.Words)
        OptionalDateField(
            label = L("Birthday"),
            date = stateOf({ if (draft.hasBirthday) draft.birthday else null }, { day ->
                draft.hasBirthday = day != null
                if (day != null) draft.birthday = day
            }),
            placeholder = L("Add birthday"),
            zone = ZoneId.systemDefault(),
            max = Instant.now(),
            optional = false,
        )
        FormField(label = L("Sex")) {
            ChoiceCards(
                tiles = listOf(
                    ChoiceCards.Tile("female", L("Female"), "venus"),
                    ChoiceCards.Tile("male", L("Male"), "mars"),
                    ChoiceCards.Tile("unknown", L("Add later"), "minus"),
                ),
                selection = draft.sexState,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PMTextField(
                L("Weight"),
                text = draft.weightState,
                key = "weight",
                form = form,
                prompt = if (draft.unit == "kg") L("e.g. 12.5") else L("e.g. 27.5"),
                keyboard = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            FormField(label = L("Unit"), modifier = Modifier.weight(1f)) {
                ChoiceCards(tiles = listOf(ChoiceCards.Tile("kg", "KG"), ChoiceCards.Tile("lb", "LB")), selection = draft.unitState)
            }
        }
        PMTextField(L("Microchip number"), text = draft.microchipState, prompt = L("Optional chip number"), keyboard = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None)
        preferredVet?.invoke()
        // A linked pet's neuter status is the family's (web: no switch for it).
        if (!linked) {
            ToggleRow(title = L("Neutered or spayed"), subtitle = L("Save this now if it is already known."), isOn = draft.neuteredState)
        }
        PMTextField(L("Pet notes"), text = draft.notesState, prompt = notesPrompt, multiline = true)
    }
}

/**
 * "¿Eres tú?": the email is the person's own on PawMiaw (docs/linking.md; web `SelfLinkPrompt`):
 * a 2 pt PAW Blue box on aqua, what each answer does, and the two answers. It scrolls into
 * view as it appears (iOS `form.scrollTarget = "selfLink"` 150 ms later).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClientsSelfLinkCard(email: String, working: Boolean, choose: (String) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { requester.bringIntoView() }
    }
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .bringIntoViewRequester(requester)
            .fillMaxWidth()
            .background(Theme.wash(Theme.aqua, 14.0), shape)
            .border(2.dp, Theme.blue, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            IconBadge(icon = "user-round-check", accent = Theme.blue, size = 36.dp, iconSize = 16.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("Is this you?"), style = Theme.heading(18), color = Theme.inkStrong)
                Text(L("This will connect your pets with your business."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                Text(LF("%@ is your email, or someone’s in your family, on PawMiaw.", email), style = Theme.text(14), color = Theme.inkSoft)
            }
        }
        val strong = Theme.inkStrong
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Glyph("link-2", 16.dp, tint = Theme.blue, modifier = Modifier.padding(top = 2.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append("${L("Connect")}: ") }
                        append(L("When your family books or connects a pet with this business, this client becomes your family and your pets’ records come together."))
                    },
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Glyph("link-2-off", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 2.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append("${L("Keep separate")}: ") }
                        append(L("The client stays only in your business and never links to your family by itself."))
                    },
                    style = Theme.text(14),
                    color = Theme.inkSoft,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PMButton(L("Yes, connect my pets"), systemImage = "link-2", fullWidth = true, isWorking = working) { choose("link") }
            PMButton(L("Keep separate"), systemImage = "link-2-off", variant = ButtonVariant.outline, fullWidth = true, enabled = !working) { choose("keep_separate") }
        }
    }
}

/**
 * The client's fields, shared by "add" and "edit" (web: "Perfil del cliente" sky,
 * "Datos de contacto" butter, "Notas del cliente" neutral).
 */
@Composable
private fun ClientsContactFields(
    photo: MutableState<PhotoDraft>,
    currentPhotoURL: String? = null,
    firstName: MutableState<String>,
    lastName: MutableState<String>,
    email: MutableState<String>,
    phone: MutableState<String>,
    language: MutableState<String>,
    notes: MutableState<String>,
    form: FormState,
    /** The client being edited (never its own match in "A client already has this contact"). */
    clientId: String? = null,
    /** "Open {name}" in that notice: the sheet closes and the client opens. */
    onOpenClient: ((String) -> Unit)? = null,
) {
    ModalSection(title = L("Client profile"), systemImage = "contact-round", tint = Theme.aqua) {
        PhotoField(label = L("Client photo"), hint = L("Optional, but useful for recognition."), currentURL = currentPhotoURL, draft = photo, shape = PhotoField.Shape.circle, optional = false, allowsRemove = false, addTitle = L("Upload photo"))
        PMTextField(L("First name"), text = firstName, key = "ownerFirstName", form = form, prompt = L("e.g. Alejandro"), capitalization = KeyboardCapitalization.Words, required = true)
        PMTextField(L("Last name"), text = lastName, prompt = L("Optional last name"), capitalization = KeyboardCapitalization.Words)
    }
    ModalSection(title = L("Contact details"), systemImage = "message-square-text", tint = Theme.butter) {
        PMTextField(L("Client email"), text = email, key = "ownerEmail", form = form, prompt = "azamora@example.com", keyboard = KeyboardType.Email)
        PMTextField(L("Client phone"), text = phone, key = "ownerPhone", form = form, prompt = "+52 55 1234 5678", keyboard = KeyboardType.Phone)
        // Someone on file already has this phone or email: open them instead of adding a second.
        ClientsExistingClientNotice(phone = phone.value, email = email.value, excludeClientId = clientId, onOpenClient = onOpenClient)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuField(label = L("Language to write to this client in"), options = listOf("" to L("Automatic"), "en" to L("English"), "es" to L("Spanish")), selection = language)
            Text(
                L("Emails and ready-made messages to this client go out in it. Automatic uses the business’s language; someone with a PawMiaw account gets emails in their account’s language."),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
        }
    }
    ModalSection(title = L("Client notes"), systemImage = "notebook-pen", tint = Theme.borderSoft) {
        PMTextField(L("Client notes"), text = notes, prompt = L("Anything the clinic should remember about this client contact."), multiline = true)
    }
}

/** A phone as its digits; the last ten are what two ways of writing it share. */
private fun clientsPhoneKey(value: String?): String? {
    val digits = (value ?: "").filter { it.isDigit() }
    return if (digits.length >= 8) digits.takeLast(10) else null
}

/** An email worth looking up: it has both sides of the @. */
private fun clientsEmailKey(value: String?): String? {
    val email = (value ?: "").trim().lowercase()
    return if (ClientsText.emailLooksValid(email) && email.contains("@")) email else null
}

/**
 * Said while a client is added or edited, before anything is saved (web `ExistingClientNotice`):
 * someone on file already has this phone or this email. It never stops the save (a couple
 * shares a number); it lets the desk open the one that is there instead of making a second.
 */
@Composable
private fun ClientsExistingClientNotice(
    phone: String,
    email: String,
    excludeClientId: String? = null,
    onOpenClient: ((String) -> Unit)? = null,
) {
    var found by remember { mutableStateOf<List<Pair<ClientsClient, String>>>(emptyList()) }

    if (found.isNotEmpty()) {
        NoticeCard(
            tone = NoticeCard.Tone.warning,
            title = if (found.size == 1) L("A client already has this contact") else LF("%d clients already have this contact", found.size),
            icon = "users-round",
            content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                found.forEach { (client, shares) ->
                    key(client.id) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(client.ownerName, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                                    if (client.isArchived) Chip(text = L("Archived"), tone = Chip.Tone.muted)
                                }
                                val pets = client.allPets.filter { !it.isArchived }.joinToString(", ") { it.petName }
                                if (pets.isNotEmpty()) Text(pets, style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (shares == "both") L("Same phone and email") else if (shares == "phone") L("Same phone") else L("Same email"),
                                    style = Theme.text(12),
                                    color = Theme.inkSoft,
                                )
                            }
                            if (onOpenClient != null) {
                                val first = client.ownerName.split(" ").firstOrNull { it.isNotEmpty() } ?: client.ownerName
                                PMButton(LF("Open %@", first), variant = ButtonVariant.outline, size = ButtonSize.slim) { onOpenClient(client.id) }
                            }
                        }
                    }
                }
                Text(
                    L("If it is the same person, open their page and add the pet there. If it is someone else (a relative who shares it), carry on."),
                    style = Theme.text(12),
                    color = Theme.inkSoft,
                )
            }
            },
        )
    }
    LaunchedEffect("${clientsPhoneKey(phone) ?: ""}|${clientsEmailKey(email) ?: ""}") {
        delay(400)
        val phoneSearch = clientsPhoneKey(phone)
        val emailSearch = clientsEmailKey(email)
        val candidates = mutableListOf<ClientsClient>()
        for (search in listOfNotNull(phoneSearch, emailSearch)) {
            val page = attempt { API.get<Page<ClientsClient>>("/provider-clients", mapOf("limit" to "5", "offset" to "0", "search" to search, "includeArchived" to "true")) }
            for (client in page?.items ?: emptyList()) if (candidates.none { it.id == client.id }) candidates.add(client)
        }
        found = candidates.mapNotNull { client ->
            if (client.id == excludeClientId) return@mapNotNull null
            val samePhone = phoneSearch != null && clientsPhoneKey(client.ownerPhone) == phoneSearch
            val sameEmail = emailSearch != null && clientsEmailKey(client.ownerEmail) == emailSearch
            if (!samePhone && !sameEmail) return@mapNotNull null
            client to (if (samePhone && sameEmail) "both" else if (samePhone) "phone" else "email")
        }
    }
}

/**
 * A quiet line under a form's parts, or a quiet titled note (web: the muted rounded box,
 * `surface-muted` on a hairline).
 */
@Composable
fun clientsQuietNote(title: String? = null, text: String) {
    NoticeCard(tone = NoticeCard.Tone.custom(Theme.surfaceMuted), title = title, message = text, icon = null)
}

private fun clientsIsSelfLinkAsk(error: Throwable): Boolean {
    val api = error as? APIError ?: return false
    return api.status == 409 && api.code == "self_link_confirm"
}

private val clientsFieldKeys: Map<String, String> = mapOf(
    "ownerFirstName" to "ownerFirstName", "ownerEmail" to "ownerEmail", "ownerPhone" to "ownerPhone",
    "firstPet.petName" to "petName", "petName" to "petName", "firstPet.weight" to "weight", "weight" to "weight",
)

// MARK: - Add a client and their first pet

@Composable
fun ClientsCreateSheet(
    /** "Open {name}" in "A client already has this contact": the sheet closes, that client opens. */
    onOpenClient: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onCreated: (ClientsClient) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val pet = remember { ClientsPetDraft() }
    var step by remember { mutableIntStateOf(0) }
    val firstName = remember { mutableStateOf("") }
    val lastName = remember { mutableStateOf("") }
    val email = remember { mutableStateOf("") }
    val phone = remember { mutableStateOf("") }
    val language = remember { mutableStateOf("") }
    val notes = remember { mutableStateOf("") }
    val clientPhoto = remember { mutableStateOf(PhotoDraft()) }
    var saving by remember { mutableStateOf(false) }
    var selfLinkAsk by remember { mutableStateOf(false) }

    val dirty = firstName.value.isNotEmpty() || lastName.value.isNotEmpty() || email.value.isNotEmpty() || phone.value.isNotEmpty() || notes.value.isNotEmpty() || !pet.isEmpty

    val openExisting: ((String) -> Unit)? = onOpenClient?.let { open -> { id: String -> dismiss(); open(id) } }

    fun next() {
        // What is wrong with the contact is said on the step that has it.
        if (!form.check(listOf(
                "ownerFirstName" to (if (firstName.value.nonEmpty == null) L("Add the client first name first.") else null),
                "ownerEmail" to (if (email.value.nonEmpty != null && !ClientsText.emailLooksValid(email.value)) L("Check the email address.") else null),
                "ownerPhone" to (if (ClientsText.phoneLooksValid(phone.value)) null else L("Finish the phone number, or leave it empty.")),
            ))) return
        step = 1
    }

    fun save(selfLink: String?) {
        if (!form.check(listOf(
                "petName" to (if (pet.name.nonEmpty == null) L("Add the pet name first.") else null),
                "weight" to (if (pet.weightInvalid) L("Write the weight as a number, e.g. 12.5") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                val body = linkedMapOf<String, Any?>(
                    "ownerFirstName" to firstName.value.trim(),
                    "ownerLastName" to lastName.value.nonEmpty,
                    "ownerEmail" to email.value.nonEmpty,
                    "recipientLanguage" to language.value.nonEmpty,
                    "ownerPhone" to phone.value.nonEmpty,
                    "notes" to notes.value.nonEmpty,
                    "firstPet" to pet.body(),
                )
                if (selfLink != null) body["selfLink"] = selfLink
                val client: ClientsClient = API.post("/provider-clients", body)
                clientPhoto.value.data?.let { data ->
                    try {
                        ImageUpload.upload<EmptyResponse>(data, to = "/provider-clients/${client.id}/photo")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Toast.error(L("Client and pet were created, but the client photo upload failed. You can add it again later."))
                    }
                }
                val petPhoto = pet.photo.data
                val created = client.allPets.firstOrNull()
                if (petPhoto != null && created != null) {
                    try {
                        ImageUpload.upload<EmptyResponse>(petPhoto, to = "/provider-client-pets/${created.id}/photo")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Toast.error(L("Client and pet were created, but the pet photo upload failed. You can add it again later."))
                    }
                }
                Toast.show(LF("%@ is now in the client directory.", pet.name.trim()))
                announceDataChanged()
                dismiss()
                onCreated(client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (clientsIsSelfLinkAsk(failure) && selfLink == null) {
                    // The card scrolls itself into view as it appears.
                    selfLinkAsk = true
                    form.scrollTarget = null
                    return@launch
                }
                if ((failure as? APIError)?.fields?.keys?.any { it.startsWith("owner") } == true) step = 0
                form.apply(failure, known = clientsFieldKeys)
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = if (step == 0) L("Add client") else L("Add first pet"),
        subtitle = if (step == 0) L("Start with the client contact. Their first pet comes next.") else L("Add the first pet for this client."),
        systemImage = if (step == 0) "users-round" else "paw-print",
        accent = if (step == 0) ModalAccent.butter else ModalAccent.pink,
        isDirty = dirty,
        form = form,
        primaryTitle = if (step == 0) L("Continue") else (if (saving) L("Saving client") else L("Add client")),
        isWorking = saving,
        steps = ModalSteps(listOf(L("Client") to "users-round", L("Pet") to "paw-print"), current = step),
        onBack = if (step == 1) ({ step = 0 }) else null,
        primaryIcon = if (step == 0) "paw-print" else "users-round",
        // The family is asked "¿Eres tú?" first: nothing is saved until they answer.
        onPrimary = if (step == 1 && selfLinkAsk) null else ({ if (step == 0) next() else save(selfLink = null) }),
        modifier = modifier,
    ) {
        if (step == 0) {
            ClientsContactFields(photo = clientPhoto, firstName = firstName, lastName = lastName, email = email, phone = phone, language = language, notes = notes, form = form, onOpenClient = openExisting)
            clientsQuietNote(text = L("Save the client details first. The first pet comes next as its own step so the contact and pet profiles stay separate."))
        } else {
            ClientsPetFields(draft = pet, form = form, notesPrompt = L("Anything the clinic should remember about this pet intake."))
            clientsQuietNote(text = L("This creates the first pet under the client you just added. More pets can be attached to the same client later."))
            if (selfLinkAsk) {
                ClientsSelfLinkCard(email = email.value.trim(), working = saving) { save(selfLink = it) }
            }
        }
    }
}

// MARK: - Edit the client

@Composable
fun ClientsEditClientSheet(
    client: ClientsClient,
    /** "Open {name}" in "A client already has this contact": the sheet closes, that client opens. */
    onOpenClient: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onSaved: (ClientsClient) -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val firstName = remember { mutableStateOf(client.ownerFirstName ?: client.ownerName) }
    val lastName = remember { mutableStateOf(client.ownerLastName ?: "") }
    val email = remember { mutableStateOf(client.ownerEmail ?: "") }
    val phone = remember { mutableStateOf(client.ownerPhone ?: "") }
    val language = remember { mutableStateOf(client.recipientLanguage ?: "") }
    val notes = remember { mutableStateOf(client.notes ?: "") }
    val photo = remember { mutableStateOf(PhotoDraft()) }
    var saving by remember { mutableStateOf(false) }
    var selfLinkAsk by remember { mutableStateOf(false) }

    val dirty = firstName.value != (client.ownerFirstName ?: client.ownerName) || lastName.value != (client.ownerLastName ?: "") || email.value != (client.ownerEmail ?: "") ||
        phone.value != (client.ownerPhone ?: "") || language.value != (client.recipientLanguage ?: "") || notes.value != (client.notes ?: "") || photo.value.data != null

    fun save(selfLink: String?) {
        if (!form.check(listOf(
                "ownerFirstName" to (if (firstName.value.nonEmpty == null) L("Add the client first name first.") else null),
                "ownerEmail" to (if (email.value.nonEmpty != null && !ClientsText.emailLooksValid(email.value)) L("Check the email address.") else null),
                "ownerPhone" to (if (ClientsText.phoneLooksValid(phone.value)) null else L("Finish the phone number, or leave it empty.")),
            ))) return
        saving = true
        scope.launch {
            try {
                val body = linkedMapOf<String, Any?>(
                    "ownerFirstName" to firstName.value.trim(),
                    "ownerLastName" to lastName.value.nonEmpty,
                    "ownerEmail" to email.value.nonEmpty,
                    "recipientLanguage" to language.value.nonEmpty,
                    "ownerPhone" to phone.value.nonEmpty,
                    "notes" to notes.value.nonEmpty,
                )
                if (selfLink != null) body["selfLink"] = selfLink
                val saved: ClientsClient = API.patch("/provider-clients/${client.id}", body)
                photo.value.data?.let { data ->
                    try {
                        ImageUpload.upload<EmptyResponse>(data, to = "/provider-clients/${client.id}/photo")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Toast.error(L("Client details were saved, but the client photo upload failed. You can add it again later."))
                    }
                }
                Toast.show(LF("%@ was updated.", saved.ownerName))
                announceDataChanged()
                onSaved(saved)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (clientsIsSelfLinkAsk(failure) && selfLink == null) {
                    selfLinkAsk = true
                    form.scrollTarget = null
                    return@launch
                }
                form.apply(failure, known = clientsFieldKeys)
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("Edit client"),
        subtitle = L("Update the main contact details that the provider workspace uses for this client."),
        systemImage = "users-round",
        accent = ModalAccent.butter,
        isDirty = dirty,
        form = form,
        primaryTitle = if (saving) L("Saving client") else L("Save changes"),
        isWorking = saving,
        primaryIcon = "pencil",
        onPrimary = if (selfLinkAsk) null else ({ save(selfLink = null) }),
        modifier = modifier,
    ) {
        ClientsContactFields(
            photo = photo, currentPhotoURL = client.photoUrl, firstName = firstName, lastName = lastName, email = email, phone = phone,
            language = language, notes = notes, form = form, clientId = client.id,
            onOpenClient = onOpenClient?.let { open -> { id: String -> dismiss(); open(id) } },
        )
        if (selfLinkAsk) {
            ClientsSelfLinkCard(email = email.value.trim(), working = saving) { save(selfLink = it) }
        }
    }
}

// MARK: - Add a pet

@Composable
fun ClientsAddPetSheet(client: ClientsClient, modifier: Modifier = Modifier, onSaved: (ClientsClient) -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val pet = remember { ClientsPetDraft() }
    var saving by remember { mutableStateOf(false) }

    /** Two cats can share a name, but usually it is the same pet about to be added again. */
    val sameNamedPet: String? = pet.name.trim().nonEmpty?.let { name ->
        client.allPets.filter { !it.isArchived }.map { it.petName }.firstOrNull { it.equals(name, ignoreCase = true) }
    }

    fun save() {
        if (!form.check(listOf(
                "petName" to (if (pet.name.nonEmpty == null) L("Add the pet name first.") else null),
                "weight" to (if (pet.weightInvalid) L("Write the weight as a number, e.g. 12.5") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                val saved: ClientsClient = API.post("/provider-clients/${client.id}/pets", pet.body())
                val name = pet.name.trim()
                val photo = pet.photo.data
                val created = saved.allPets.lastOrNull { it.petName == name } ?: saved.allPets.lastOrNull()
                if (photo != null && created != null) {
                    try {
                        ImageUpload.upload<EmptyResponse>(photo, to = "/provider-client-pets/${created.id}/photo")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Toast.error(L("Pet details were saved, but the pet photo upload failed. You can add it again later."))
                    }
                }
                Toast.show(LF("%@ was added for %@.", name, client.ownerName))
                announceDataChanged()
                onSaved(saved)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                form.apply(failure, known = clientsFieldKeys)
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("Add pet"),
        subtitle = LF("Attach another pet to %@ without changing the main client contact.", client.ownerName),
        systemImage = "paw-print",
        accent = ModalAccent.pink,
        isDirty = !pet.isEmpty,
        form = form,
        primaryTitle = if (saving) L("Adding pet") else L("Add pet"),
        isWorking = saving,
        primaryIcon = "paw-print",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        ClientsPetFields(draft = pet, form = form, namePrompt = L("e.g. Nina"), nameWarning = sameNamedPet?.let { LF("%@ already has a pet called %@.", client.ownerName, it) })
    }
}

// MARK: - Edit a pet

@Composable
fun ClientsEditPetSheet(clientName: String, pet: ClientsPet, onSaved: () -> Unit = {}, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val draft = remember { ClientsPetDraft(pet) }
    var members by remember { mutableStateOf<List<ClientsMember>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var differences by remember { mutableStateOf<List<ClientsFamilyDifference>>(emptyList()) }

    /** "Usar este dato": the family's value goes into the form; nothing is saved until "Guardar cambios". */
    fun use(difference: ClientsFamilyDifference) {
        val value = difference.family
        when (difference.field) {
            "breed" -> draft.breed = value.string ?: ""
            "sex" -> draft.sex = value.string ?: "unknown"
            "birthDate" -> DateText.parse(value.string?.let { "${it.take(10)}T12:00:00Z" })?.let { born -> draft.hasBirthday = true; draft.birthday = born }
            "weight" -> {
                draft.unit = "kg"
                draft.weight = ClientsText.kilogramsInput(value.double)
            }
            "isNeutered" -> draft.neutered = value.bool ?: false
            "microchipNumber" -> draft.microchip = value.string ?: ""
            else -> {}
        }
        differences = differences.filter { it.field != difference.field }
    }

    fun save() {
        if (!form.check(listOf(
                "petName" to (if (!pet.isLinked && draft.name.nonEmpty == null) L("Add the pet name first.") else null),
                "weight" to (if (draft.weightInvalid) L("Write the weight as a number, e.g. 12.5") else null),
            ))) return
        saving = true
        scope.launch {
            try {
                API.patch<ClientsClient>("/provider-client-pets/${pet.id}", draft.body(linked = pet.isLinked, withPreferred = pet.isLinked))
                val photo = draft.photo.data
                if (photo != null && !pet.isLinked) {
                    try {
                        ImageUpload.upload<EmptyResponse>(photo, to = "/provider-client-pets/${pet.id}/photo")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Toast.error(L("Pet details were saved, but the pet photo upload failed. You can add it again later."))
                    }
                }
                Toast.show(LF("%@ was updated for %@.", if (pet.isLinked) pet.petName else draft.name.trim(), clientName))
                announceDataChanged()
                onSaved()
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                form.apply(failure, known = clientsFieldKeys)
            } finally {
                saving = false
            }
        }
    }

    ProviderModal(
        title = L("Edit pet"),
        subtitle = LF("Update the pet profile that %@'s provider records and scheduling attach to.", clientName),
        systemImage = "paw-print",
        accent = ModalAccent.pink,
        isDirty = changed,
        form = form,
        primaryTitle = if (saving) L("Saving pet") else L("Save changes"),
        isWorking = saving,
        primaryIcon = "pencil",
        onPrimary = { save() },
        modifier = modifier,
    ) {
        ClientsPetFields(
            draft = draft,
            form = form,
            linked = pet.isLinked,
            currentPhotoURL = pet.photoUrl,
            linkedPet = if (pet.isLinked) pet else null,
            linkedExtra = if (pet.isLinked && differences.isNotEmpty()) ({ ClientsDifferencesBox(differences, saving = saving, use = { use(it) }) }) else null,
            preferredVet = {
                // The preferred vet: only a linked pet keeps one.
                val vetOptions = listOf("" to L("No preferred vet yet")) + members.map { it.id to it.label }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Disabled(!pet.isLinked) {
                        MenuField(label = L("Preferred vet"), options = vetOptions, selection = draft.preferredMemberIdState, modifier = Modifier.alpha(if (pet.isLinked) 1f else 0.5f))
                    }
                    Text(
                        if (pet.isLinked) L("Use this to override which vet should stay preferred for this linked pet.") else L("Preferred vet becomes available once this pet is linked to a real household."),
                        style = Theme.text(12),
                        color = Theme.inkSoft,
                    )
                }
            },
        )
    }
    LaunchedEffect(Unit) {
        members = ClientsMembers.load()
        if (pet.isLinked) {
            attempt { API.get<ClientsFamilyDifferences>("/provider-client-pets/${pet.id}/family-profile") }?.let { differences = it.differences ?: emptyList() }
        }
    }
    // iOS `.onReceive(draft.objectWillChange) { changed = true }`.
    LaunchedEffect(draft) {
        snapshotFlow { draft.fingerprint() }.drop(1).collect { changed = true }
    }
}

/** Where the family's profile differs (web: the mango box in the linked section). */
@Composable
private fun ClientsDifferencesBox(differences: List<ClientsFamilyDifference>, saving: Boolean, use: (ClientsFamilyDifference) -> Unit) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.mango, 10.0), shape)
            .border(1.dp, Theme.mango, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(L("The family’s profile says something else"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
        differences.forEach { difference ->
            key(difference.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        LF("%1\$@: the family says %2\$@ · you have %3\$@", difference.label, difference.text(difference.family), difference.text(difference.clinic)),
                        Modifier.weight(1f),
                        style = Theme.text(14),
                        color = Theme.inkStrong,
                    )
                    PMButton(L("Use this"), variant = ButtonVariant.outline, size = ButtonSize.compact, enabled = !saving) { use(difference) }
                }
            }
        }
    }
}

// MARK: - The family's profile, where it differs

@Serializable
private data class ClientsFamilyDifferences(val differences: List<ClientsFamilyDifference>? = null)

/** One field the family's PawMiaw profile says differently (weight in grams, birthday as a day). */
@Serializable
data class ClientsFamilyDifference(
    val field: String,
    val clinic: JSONValue? = null,
    val family: JSONValue? = null,
) {
    val id: String get() = this.field

    val label: String
        get() = when (this.field) {
            "breed" -> L("Breed")
            "sex" -> L("Sex")
            "birthDate" -> L("Birthday")
            "weight" -> L("Weight")
            "isNeutered" -> L("Neutered or spayed")
            else -> L("Microchip")
        }

    fun text(value: JSONValue?): String {
        if (value == null || value.isNull) return "—"
        return when (field) {
            "sex" -> ClientsText.sex(value.string) ?: "—"
            "birthDate" -> ClientsMatches.fullDay(value.string)
            "weight" -> ClientsMatches.weight(value.double) ?: "—"
            "isNeutered" -> if (value.bool == true) L("Yes") else L("No")
            else -> value.string ?: "—"
        }
    }
}
