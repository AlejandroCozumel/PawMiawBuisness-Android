@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.pawmiaw.business.stays

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

//
//  StaysCheckInOut.kt (iOS StaysCheckInOut.swift)
//
//  Checking a stay in and out (web stay-check-in-sheet.tsx,
//  stay-check-out-sheet.tsx, stay-bill-choice.tsx). Check-in: each pet's
//  vaccines against the stay rules (and "I checked the vaccination card"), its
//  care plan, the belongings and the emergency contact; a stay whose kind
//  needs a signed consent waits for it, and owners and admins may check in
//  anyway with a reason. Check-out: the day it leaves, extras, what to hand
//  back, and the bill (backend docs/charging-flow.md): fully priced it goes to
//  the front desk unless kept a draft; without a bill it asks why.
//

// MARK: - Check in

private data class StaysPetDraft(
    val stayPetId: String,
    val items: List<StaysCareItem>,
    val notes: String,
    val confirmVaccines: Boolean,
) {
    val id: String get() = stayPetId
}

@Composable
fun StaysCheckInSheet(stay: StaysStay, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var intake by remember { mutableStateOf<StaysIntake?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var drafts by remember { mutableStateOf<List<StaysPetDraft>>(emptyList()) }
    var belongings by remember { mutableStateOf<List<String>>(emptyList()) }
    val belongingDraft = remember { mutableStateOf("") }
    val contactName = remember { mutableStateOf("") }
    val contactPhone = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var consentMissing by remember { mutableStateOf<String?>(null) }
    var askingOverride by remember { mutableStateOf(false) }
    // `.id("consent")` + `form.scrollTarget = "consent"`: the notice is brought into view on each refusal.
    val consentRequester = remember { BringIntoViewRequester() }
    var consentScroll by remember { mutableIntStateOf(0) }

    val unconfirmed = (intake?.pets ?: emptyList()).fold(0) { total, pet ->
        if (drafts.firstOrNull { it.stayPetId == pet.stayPetId }?.confirmVaccines == true) total
        else total + (pet.vaccines ?: emptyList()).count { it.status != "current" }
    }

    val subtitle = run {
        val today = stay.today
        if (today != null && stay.checkInDay > today) {
            // Earlier than booked: the server starts the stay today, so the extra nights count.
            LF("Arriving early: the stay starts today, until %@ at %@", StaysDay.short(stay.checkOutDay), StaysDay.clock(stay.checkOutTime))
        } else {
            LF("Until %@ at %@", StaysDay.short(stay.checkOutDay), StaysDay.clock(stay.checkOutTime))
        }
    }

    suspend fun load() {
        try {
            val loaded = API.get<StaysIntake>("/provider-stays/${stay.id}/intake")
            intake = loaded
            drafts = (loaded.pets ?: emptyList()).map { pet ->
                val items = if ((pet.carePlan?.items ?: emptyList()).isEmpty()) (pet.suggestedCare ?: emptyList()) else (pet.carePlan?.items ?: emptyList())
                StaysPetDraft(stayPetId = pet.stayPetId, items = items, notes = pet.carePlan?.notes ?: "", confirmVaccines = false)
            }
            belongings = stay.belongings ?: emptyList()
            contactName.value = stay.emergencyContact?.name ?: loaded.suggestedEmergencyContact?.name ?: ""
            contactPhone.value = stay.emergencyContact?.phone ?: loaded.suggestedEmergencyContact?.phone ?: ""
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = errorText(e)
        }
    }

    fun addBelonging() {
        val value = belongingDraft.value.nonEmpty ?: return
        belongings = belongings + value
        belongingDraft.value = ""
    }

    suspend fun submit(override: String?) {
        val body = mutableMapOf<String, Any?>(
            "pets" to drafts.map { draft ->
                mapOf<String, Any?>(
                    "stayPetId" to draft.stayPetId,
                    "confirmVaccines" to draft.confirmVaccines,
                    "carePlan" to StaysCarePlanEditor.clean(draft.items, draft.notes),
                )
            },
            "belongings" to belongings,
            "emergencyContact" to (
                if (contactName.value.nonEmpty != null && contactPhone.value.nonEmpty != null) {
                    mapOf<String, Any?>("name" to contactName.value.nonEmpty, "phone" to contactPhone.value.nonEmpty)
                } else null
                ),
        )
        if (override != null) {
            body["overrideConsent"] = true
            body["overrideReason"] = override.nonEmpty
        }
        API.post<StaysStay>("/provider-stays/${stay.id}/check-in", body = body)
        // Only a family on PawMiaw hears about it.
        Toast.show(if (stay.familyOnApp) LF("%@ checked in. The family has been told.", stay.petNames) else LF("%@ checked in.", stay.petNames))
        announceDataChanged()
        onDone()
        dismiss()
    }

    fun checkIn(override: String?) {
        // An unfinished number would be kept as the contact to call in an emergency.
        val digits = contactPhone.value.count { it.isDigit() }
        if (!form.check(listOf("phone" to (if (contactPhone.value.nonEmpty != null && digits < 8) L("Finish the phone number, or leave it empty.") else null)))) return
        working = true
        scope.launch {
            try {
                submit(override = override)
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                if (e.code == "consent_required") {
                    consentMissing = e.message
                    form.scrollTarget = null
                    form.scrollTarget = "consent"
                    consentScroll += 1
                } else {
                    Toast.error(e, fallback = L("Could not check in."))
                }
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not check in."))
            } finally {
                working = false
            }
        }
    }

    fun vaccineLine(vaccine: StaysVaccineCheck): String {
        val name = vaccine.matchedName ?: return L("No record found")
        var line = name
        vaccine.validUntil?.let { line += " · " + LF("valid until %@", StaysDay.format(it.take(10), "dMMMyyyy")) }
        if (vaccine.assumedOneYear == true) line += " · " + L("no due date recorded, one year assumed")
        return line
    }

    @Composable
    fun PetSection(pet: StaysIntakePet) {
        val index = drafts.indexOfFirst { it.stayPetId == pet.stayPetId }.takeIf { it >= 0 }
        val hint: String? = when {
            pet.linkedToHousehold != true -> L("This pet is not linked to a PawMiaw family, so only your own records are checked. Look at the paper card.")
            pet.canReadVaccines == false -> L("The family has not shared health records with you. Look at the paper card.")
            else -> null
        }
        val suggested = pet.suggestedCare ?: emptyList()
        val planHint = when {
            suggested.any { it.source == "clinic_record" } -> L("Started from the owner’s routines and the medications in your records. Adjust anything for the stay.")
            pet.canReadRoutines == true && suggested.isNotEmpty() -> L("Started from the owner’s own routines. Adjust anything for the stay.")
            else -> L("What the team does each day. Each item becomes a task on the care board.")
        }

        fun change(transform: (StaysPetDraft) -> StaysPetDraft) {
            drafts = drafts.map { if (it.stayPetId == pet.stayPetId) transform(it) else it }
        }

        // Each pet in its own bordered card (web `rounded-card border p-5`, no shadow).
        Card(padding = 20.dp, spacing = 24.dp, flat = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pet.petName ?: "", style = brandHeading(24), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                if (pet.linkedToHousehold == true) {
                    Chip(
                        text = L("From the family’s PawMiaw"), tone = Chip.Tone.custom(Theme.lime), icon = "sparkles",
                        percent = 35.0, ink = Theme.blueText, size = Chip.Size.medium,
                    )
                }
            }
            StaysAllergies(allergies = pet.allergies ?: emptyList())
            ModalSection(title = L("Vaccines"), subtitle = hint) {
                val shape = RoundedCornerShape(Theme.boxRadius)
                for (vaccine in pet.vaccines ?: emptyList()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Theme.borderSoft, shape)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(StaysWords.vaccine(vaccine.key), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                            Text(vaccineLine(vaccine), style = Theme.text(12), color = Theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(6.dp))
                        // Web: a pill in the state's colour at 45%, the words navy.
                        Chip(StaysWords.vaccineStatus(vaccine.status), color = ProviderState.vaccine(vaccine.status).color, percent = 45.0, size = Chip.Size.medium)
                    }
                }
                if ((pet.vaccines ?: emptyList()).isEmpty()) {
                    Text(L("No vaccines are required for this pet."), style = Theme.text(14), color = Theme.inkSoft)
                }
                if (index != null) {
                    StaysTickRow(
                        title = L("I checked the vaccination card"), icon = "shield-check", muted = true,
                        isOn = stateOf({ drafts.firstOrNull { it.stayPetId == pet.stayPetId }?.confirmVaccines == true }, { value -> change { it.copy(confirmVaccines = value) } }),
                    )
                }
            }
            ModalSection(title = L("Care plan"), subtitle = planHint) {
                if (index != null) {
                    StaysCarePlanEditor(
                        items = stateOf({ drafts.firstOrNull { it.stayPetId == pet.stayPetId }?.items ?: emptyList() }, { value -> change { it.copy(items = value) } }),
                        notes = stateOf({ drafts.firstOrNull { it.stayPetId == pet.stayPetId }?.notes ?: "" }, { value -> change { it.copy(notes = value) } }),
                        suggestions = suggested,
                    )
                }
            }
        }
    }

    ProviderModal(
        title = LF("Check in %@", stay.petNames),
        subtitle = subtitle,
        systemImage = "log-in",
        accent = ModalAccent.aqua,
        isDirty = intake != null,
        form = form,
        primaryTitle = L("Check in"),
        isWorking = working,
        primaryIcon = "log-in",
        primaryDisabled = intake == null,
        // The vaccines' state, on the footer's line as on the web (once the pets are read).
        footerSummary = if (intake == null) null else {
            {
                Text(
                    if (unconfirmed == 0) L("Vaccines are in order.")
                    else LP(unconfirmed, "%d vaccine is not confirmed. You can still check in.", "%d vaccines are not confirmed. You can still check in."),
                    style = Theme.text(14, FontWeight.SemiBold),
                    color = Theme.inkSoft,
                )
            }
        },
        onPrimary = { checkIn(override = null) },
        modifier = modifier,
    ) {
        val missing = consentMissing
        if (missing != null) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = LF("%@ can’t check in yet", stay.petNames),
                message = L("The service needs these consents signed first.") + " " + missing,
                actionTitle = if (workspace.isOwnerOrAdmin) L("Check in anyway") else null,
                modifier = Modifier.bringIntoViewRequester(consentRequester),
                action = if (workspace.isOwnerOrAdmin) ({ askingOverride = true }) else null,
            )
            // The missing consents, to sign here with the client or send to the family; then check in.
            ConsentsCard(context = StaysActs.consentContext(stay, businessName = workspace.current?.name), bare = true)
        }
        val loaded = intake
        if (loaded != null) {
            for (pet in loaded.pets ?: emptyList()) key(pet.stayPetId) { PetSection(pet) }
            ModalSection(title = L("Belongings"), subtitle = L("What came with them, so it all goes home.")) {
                if (belongings.isNotEmpty()) {
                    FlowLayout(spacing = 6.dp) {
                        belongings.forEachIndexed { index, item ->
                            Chip(
                                text = item, tone = Chip.Tone.muted, icon = "trash-2",
                                modifier = Modifier.plainButton(label = "${L("Remove")} $item") {
                                    belongings = belongings.toMutableList().also { if (index in it.indices) it.removeAt(index) }
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldChrome(modifier = Modifier.weight(1f)) {
                        PlainTextField(
                            belongingDraft, prompt = L("e.g. Blue blanket, leash, food bag"), modifier = Modifier.weight(1f),
                            imeAction = ImeAction.Done, onSubmit = { addBelonging() },
                        )
                    }
                    PMButton(L("Add"), variant = ButtonVariant.outline, size = ButtonSize.compact) { addBelonging() }
                }
            }
            ModalSection(title = L("Emergency contact")) {
                StaysPlainField(prompt = L("Name"), text = contactName)
                StaysPlainField(prompt = L("Phone"), text = contactPhone, key = "phone", form = form, keyboard = KeyboardType.Phone)
            }
        } else if (loadError != null) {
            ErrorState(message = loadError ?: "") { load() }
        } else {
            LoadingState()
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(consentScroll) { if (consentScroll > 0) runCatching { consentRequester.bringIntoView() } }
    if (askingOverride) {
        AppSheet(onDismissRequest = { askingOverride = false }) {
            ConfirmSheet(
                title = L("Check in without the signed consent?"),
                message = L("Only owners and admins can. It is written down with your name and the reason."),
                confirmTitle = L("Check in anyway"), destructive = true, askReason = true, reasonRequired = true, cancelTitle = L("Not now"),
            ) { reason -> submit(override = reason ?: "") }
        }
    }
}

// MARK: - Check out

@Composable
fun StaysCheckOutSheet(stay: StaysStay, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val checkOutDay = remember { mutableStateOf("") }
    val extras = remember { mutableStateOf<List<StaysExtraLine>>(emptyList()) }
    val createInvoice = remember { mutableStateOf(true) }
    val keepDraft = remember { mutableStateOf(false) }
    val noBillReason = remember { mutableStateOf("") }
    var handedBack by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var leaveBehind by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val canInvoice = workspace.can(Permission.billingCreateInvoice)
    val today = stay.today ?: StaysDay.today()
    val nights = StaysDay.nights(kind = stay.kind, from = stay.checkInDay, to = if (checkOutDay.value.isEmpty()) stay.checkInDay else checkOutDay.value)
    val stayTotal = nights * stay.rate * stay.petCount
    val extrasTotal = extras.value.sumOf { it.cents ?: 0 }
    /** A stay with no rate bills its nights at $0: the server keeps that bill a draft until it has a price. */
    val noRate = stay.rate <= 0
    val notHandedBack = (stay.belongings ?: emptyList()).filterIndexed { index, _ -> index !in handedBack }

    fun checkOut() {
        if (!form.check(listOf("noBillReason" to (if (!createInvoice.value && noBillReason.value.nonEmpty == null) L("Write why it leaves without a bill.") else null)))) return
        if (notHandedBack.isNotEmpty() && !leaveBehind) {
            leaveBehind = true
            return
        }
        working = true
        scope.launch {
            try {
                val body = mutableMapOf<String, Any?>(
                    "createInvoice" to createInvoice.value,
                    "extras" to StaysExtrasSection.payload(extras.value),
                )
                body["checkOutDay"] = checkOutDay.value
                if (createInvoice.value) body["keepDraft"] = keepDraft.value else body["noBillReason"] = noBillReason.value.nonEmpty
                API.post<StaysStay>("/provider-stays/${stay.id}/check-out", body = body)
                val toDesk = StaysBillChoice.goesToDesk(createInvoice = createInvoice.value, keepDraft = keepDraft.value, unpriced = noRate)
                Toast.show(
                    if (createInvoice.value) {
                        if (toDesk) L("Checked out. The bill is at the front desk to be charged.") else L("Checked out. A draft invoice is waiting in Invoices.")
                    } else {
                        if (stay.familyOnApp) L("Checked out. The family has been told.") else L("Checked out.")
                    },
                )
                announceDataChanged()
                onDone()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                if (e.code == "reason_required") form.check(listOf("noBillReason" to L("Write why it leaves without a bill.")))
                else Toast.error(e, fallback = L("Could not check out."))
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not check out."))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = LF("Check out %@", stay.petNames),
        subtitle = stay.clientName,
        systemImage = "log-out",
        accent = ModalAccent.aqua,
        isDirty = extras.value.isNotEmpty() || noBillReason.value.isNotEmpty(),
        form = form,
        primaryTitle = if (leaveBehind && notHandedBack.isNotEmpty()) L("Check out anyway") else L("Check out"),
        isWorking = working,
        primaryIcon = "log-out",
        // The web footer's line: the total, then what is not handed back yet or where the bill goes.
        footerSummary = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Money.format(stayTotal + extrasTotal, currency = stay.stayCurrency), style = brandHeading(24), color = Theme.inkStrong)
                if (leaveBehind && notHandedBack.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Glyph("triangle-alert", 16.dp, tint = Theme.goldText)
                        Text(LF("Not handed back yet: %@", notHandedBack.joinToString(", ")), style = Theme.text(14, FontWeight.SemiBold), color = Theme.goldText)
                    }
                } else {
                    Text(
                        StaysBillChoice.outcome(createInvoice = createInvoice.value, keepDraft = keepDraft.value, unpriced = noRate),
                        style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft,
                    )
                }
            }
        },
        onPrimary = { checkOut() },
        modifier = modifier,
    ) {
        ModalSection(title = L("The stay")) {
            // A day of daycare ends the day it started.
            if (!stay.isDaycare) {
                DayField(label = L("Leaving on"), day = checkOutDay, min = stay.checkInDay, max = if (today < stay.checkInDay) stay.checkInDay else today)
            }
            val shape = RoundedCornerShape(Theme.boxRadius)
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme.borderSoft, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (pet in stay.petList) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${StaysWords.kind(stay.kind)} — ${pet.petName}", style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (noRate) "${StaysWords.units(stay.kind, nights)} · ${L("No rate set")}"
                            else "${StaysWords.units(stay.kind, nights)} × ${Money.format(stay.rate, currency = stay.stayCurrency)}",
                            style = Theme.text(14, if (noRate) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (noRate) Theme.goldText else Theme.inkSoft,
                        )
                    }
                }
                if (noRate) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Glyph("triangle-alert", 14.dp, tint = Theme.goldText, modifier = Modifier.padding(top = 1.dp))
                        Text(
                            if (createInvoice.value) L("No rate: the bill stays a draft until you put a price on it.") else L("No rate: nothing to charge for the nights."),
                            style = Theme.text(12, FontWeight.SemiBold), color = Theme.goldText,
                        )
                    }
                }
                if (checkOutDay.value.isNotEmpty() && checkOutDay.value != stay.checkOutDay) {
                    Text(
                        if (checkOutDay.value > stay.checkOutDay) LF("Booked until %@; it stayed longer, so the extra nights are charged.", StaysDay.short(stay.checkOutDay))
                        else LF("Booked until %@; charging for the nights actually stayed.", StaysDay.short(stay.checkOutDay)),
                        style = Theme.text(12, FontWeight.SemiBold), color = Theme.goldText,
                    )
                }
            }
        }
        StaysExtrasSection(extras = extras)
        val stayBelongings = stay.belongings ?: emptyList()
        if (stayBelongings.isNotEmpty()) {
            ModalSection(title = L("Hand back")) {
                val shape = RoundedCornerShape(Theme.boxRadius)
                stayBelongings.forEachIndexed { index, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Theme.borderSoft, shape)
                            .plainButton { handedBack = if (index in handedBack) handedBack - index else handedBack + index }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tick(on = index in handedBack)
                        Glyph("package-check", 16.dp, tint = Theme.blue)
                        Text(item, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        StaysBillChoice(createInvoice = createInvoice, canInvoice = canInvoice, keepDraft = keepDraft, noBillReason = noBillReason, unpriced = noRate, form = form)
    }

    LaunchedEffect(Unit) {
        if (checkOutDay.value.isNotEmpty()) return@LaunchedEffect
        checkOutDay.value = if (stay.isDaycare || today < stay.checkInDay) stay.checkInDay else today
        createInvoice.value = canInvoice
    }
}

/** Extras added at the end (a bath before pickup, a late pickup). */
@Composable
fun StaysExtrasSection(
    extras: MutableState<List<StaysExtraLine>>,
    hint: String = L("A bath before pickup, a late pickup, anything added during the stay."),
    /** The words' placeholder; a home stay's says "What". */
    prompt: String = L("e.g. Bath before pickup"),
    removeIcon: String = "trash-2",
    /** A home stay's finish adds with a blue link (up to 20), a stay's check-out with an outline button. */
    addAsLink: Boolean = false,
    modifier: Modifier = Modifier,
) {
    fun change(id: UUID, transform: (StaysExtraLine) -> StaysExtraLine) {
        extras.value = extras.value.map { if (it.id == id) transform(it) else it }
    }

    ModalSection(title = L("Extras"), subtitle = hint, modifier = modifier) {
        // Web: what and how much side by side, no labels (the placeholders say it), a bin.
        for (extra in extras.value) {
            key(extra.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StaysPlainField(
                        prompt = prompt,
                        text = stateOf({ extras.value.firstOrNull { it.id == extra.id }?.label ?: "" }, { value -> change(extra.id) { it.copy(label = value) } }),
                        modifier = Modifier.weight(1f),
                    )
                    StaysPlainMoney(
                        cents = stateOf({ extras.value.firstOrNull { it.id == extra.id }?.cents }, { value -> change(extra.id) { it.copy(cents = value) } }),
                        modifier = Modifier.width(112.dp),
                    )
                    IconCircleButton(
                        systemImage = removeIcon, label = L("Remove"), tint = Theme.inkSoft,
                        size = if (addAsLink) 36.dp else 44.dp, bordered = false,
                    ) { extras.value = extras.value.filter { it.id != extra.id } }
                }
            }
        }
        if (addAsLink) {
            if (extras.value.size < 20) {
                PMButton(L("Add an extra"), systemImage = "plus", variant = ButtonVariant.link, size = ButtonSize.small) { extras.value = extras.value + StaysExtraLine() }
            }
        } else {
            PMButton(L("Add an extra"), systemImage = "plus", variant = ButtonVariant.outline) { extras.value = extras.value + StaysExtraLine() }
        }
    }
}

object StaysExtrasSection {
    fun payload(extras: List<StaysExtraLine>): List<Map<String, Any?>> = extras.mapNotNull { extra ->
        val label = extra.label.nonEmpty ?: return@mapNotNull null
        val cents = extra.cents ?: return@mapNotNull null
        if (cents <= 0) return@mapNotNull null
        mapOf("label" to label, "amountCents" to cents)
    }
}

/** One extra line on a stay's bill. */
data class StaysExtraLine(
    val id: UUID = UUID.randomUUID(),
    val label: String = "",
    val cents: Int? = null,
)

/**
 * The bill at the end of a stay or a home stay (docs/charging-flow.md): fully
 * priced it goes to the front desk by itself unless "Keep it a draft to
 * adjust"; a line at $0 keeps it a draft; without a bill, why (the owner's exceptions).
 */
@Composable
fun StaysBillChoice(
    createInvoice: MutableState<Boolean>,
    canInvoice: Boolean,
    keepDraft: MutableState<Boolean>,
    noBillReason: MutableState<String>,
    unpriced: Boolean,
    form: FormState,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Disabled(!canInvoice) {
            ToggleRow(
                title = L("Create the bill"),
                subtitle = if (canInvoice) L("The nights or visits and the extras, on the family’s account.") else L("Ask someone who can create invoices."),
                isOn = createInvoice,
            )
        }
        if (createInvoice.value) {
            Disabled(unpriced) {
                ToggleRow(
                    title = L("Keep it a draft to adjust"),
                    subtitle = when {
                        unpriced -> L("Something has no price yet: it stays a draft until it has one.")
                        keepDraft.value -> L("It waits in Invoices › To charge until someone sends it to the front desk.")
                        else -> L("Off, it goes to the front desk to be charged.")
                    },
                    isOn = stateOf({ keepDraft.value || unpriced }, { keepDraft.value = it }),
                )
            }
        } else {
            PMTextField(
                L("Why no bill"), text = noBillReason, key = "noBillReason", form = form,
                prompt = L("e.g. Paid in advance, a staff pet"), hint = L("The owner sees why on the day’s exceptions."), required = true,
            )
        }
    }
}

object StaysBillChoice {
    /** Where the bill goes, in a few words. */
    fun outcome(createInvoice: Boolean, keepDraft: Boolean, unpriced: Boolean): String {
        if (!createInvoice) return L("No invoice")
        return if (keepDraft || unpriced) L("Goes on a draft invoice") else L("Goes to the front desk to be charged")
    }

    fun goesToDesk(createInvoice: Boolean, keepDraft: Boolean, unpriced: Boolean): Boolean =
        createInvoice && !keepDraft && !unpriced
}
