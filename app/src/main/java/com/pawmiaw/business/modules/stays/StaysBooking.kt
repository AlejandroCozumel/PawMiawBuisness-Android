package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

//
//  StaysBooking.kt (iOS StaysBooking.swift)
//
//  A new stay in three steps (web stay-booking-sheet.tsx): who (the client
//  and pets), when and where (boarding or daycare, the days, the times and a
//  room that fits: species, capacity, full nights), then the price and how to
//  save it — booked now, or held as a request — with the owner's words and a
//  note for the team. Who: the shared ClientPetPicker.
//

private enum class StaysBookingStep { who, `when`, confirm }

private class StaysRoomOption(
    val unit: StaysUnit,
    val detail: String,
    val available: Boolean,
) {
    val id: String get() = unit.id
}

/** The sheet's state (Swift's `@State`s), so the work below reads it as it is now. */
private class StaysBookingModel(val preset: StaysBookingPreset) {
    val workspace = Workspace.shared
    val form = FormState()
    var step by mutableStateOf(StaysBookingStep.who)
    val kind = mutableStateOf("boarding")
    val client = mutableStateOf<PickedClient?>(null)
    val petIds = mutableStateOf<List<String>>(emptyList())
    val checkInDay = mutableStateOf("")
    val checkOutDay = mutableStateOf("")
    val checkInTime = mutableStateOf("14:00")
    val checkOutTime = mutableStateOf("11:00")
    val unitId = mutableStateOf<String?>(null)
    var rate by mutableStateOf<Int?>(null)
    var rateTouched by mutableStateOf(false)
    val asRequest = mutableStateOf(false)
    val ownerInstructions = mutableStateOf("")
    val internalNotes = mutableStateOf("")
    var settings by mutableStateOf<StaysSettings?>(null)
    var units by mutableStateOf<List<StaysUnit>>(emptyList())
    var rangeStays by mutableStateOf<List<StaysStay>>(emptyList())
    var working by mutableStateOf(false)

    val effectiveCheckOut: String get() = if (kind.value == "daycare") checkInDay.value else checkOutDay.value
    val datesReady: Boolean
        get() = checkInDay.value.isNotEmpty() && (kind.value == "daycare" || (checkOutDay.value.isNotEmpty() && checkOutDay.value > checkInDay.value))
    val pets: List<PickedPet> get() = client.value?.activePets?.filter { it.id in petIds.value } ?: emptyList()
    val petCount: Int get() = max(1, pets.size)
    val nights: Int get() = StaysDay.nights(kind = kind.value, from = checkInDay.value, to = effectiveCheckOut)
    val currency: String get() = settings?.currency ?: workspace.currency

    val roomOptions: List<StaysRoomOption>
        get() = units.map { unit ->
            val stayKind = kind.value
            val dayRoom = !StaysDay.roomTakes(unitKind = unit.unitKind, stayKind = stayKind)
            val wrongSpecies = pets.any { unit.species != "any" && (it.petSpecies ?: "").lowercase() != unit.species }
            val tooSmall = pets.size > unit.seats
            val fullOn = if (datesReady) {
                StaysDay.firstFullDay(
                    capacity = unit.seats, stays = rangeStays, unitId = unit.id, kind = stayKind,
                    from = checkInDay.value, to = effectiveCheckOut, pets = petCount,
                )
            } else null
            val unitRate = if (stayKind == "daycare") unit.dayRateCents else unit.nightlyRateCents
            val detail = when {
                dayRoom -> L("Daycare only")
                wrongSpecies -> L("Not for this species")
                tooSmall -> LF("Holds %d", unit.seats)
                fullOn != null -> LF("Full on %@", StaysDay.short(fullOn))
                else -> "${StaysWords.unitKind(unit.kind)} · ${if ((unitRate ?: 0) > 0) Money.format(unitRate, currency = currency) else L("No rate set")}"
            }
            StaysRoomOption(unit = unit, detail = detail, available = !dayRoom && !wrongSpecies && !tooSmall && fullOn == null)
        }

    suspend fun loadBasics() = coroutineScope {
        if (checkInDay.value.isEmpty()) {
            checkInDay.value = if (preset.day < StaysDay.today()) StaysDay.today() else preset.day
            checkOutDay.value = StaysDay.add(checkInDay.value, 1)
            unitId.value = preset.unitId
        }
        val settingsCall = async { runCatching { API.get<StaysSettings>("/provider-stays/settings") }.getOrNull() }
        val unitsCall = async { runCatching { API.get<List<StaysUnit>>("/provider-stays/units", mapOf("locationId" to workspace.locationId)) }.getOrNull() }
        settings = settingsCall.await()
        units = unitsCall.await() ?: emptyList()
        applyDefaultTimes()
        applyRoomRate()
        loadRange()
    }

    suspend fun loadRange() {
        if (!datesReady) return
        val to = if (effectiveCheckOut < checkInDay.value) checkInDay.value else effectiveCheckOut
        val page = runCatching {
            API.get<StaysPage>("/provider-stays", mapOf("from" to checkInDay.value, "to" to to, "locationId" to workspace.locationId))
        }.getOrNull()
        rangeStays = page?.items ?: emptyList()
        clearRoomIfUnfit()
    }

    fun applyDefaultTimes() {
        val settings = settings ?: return
        checkInTime.value = (if (kind.value == "daycare") settings.daycareStartTime else settings.checkInTime) ?: checkInTime.value
        checkOutTime.value = (if (kind.value == "daycare") settings.daycareEndTime else settings.checkOutTime) ?: checkOutTime.value
    }

    /** A room that stopped fitting (other pets, other dates) is let go. */
    fun clearRoomIfUnfit() {
        val id = unitId.value ?: return
        if (roomOptions.any { it.unit.id == id && !it.available }) unitId.value = null
    }

    fun applyRoomRate() {
        if (rateTouched) return
        val room = units.firstOrNull { it.id == unitId.value }
        if (room == null) {
            rate = null
            return
        }
        val cents = if (kind.value == "daycare") room.dayRateCents else room.nightlyRateCents
        rate = if ((cents ?: 0) > 0) cents else null
    }

    fun next(scope: CoroutineScope, onBooked: () -> Unit, dismiss: () -> Unit) {
        when (step) {
            StaysBookingStep.who -> {
                if (!form.check(
                        listOf(
                            "client" to (if (client.value == null) L("Choose the client first.") else null),
                            "pets" to (if (client.value != null && petIds.value.isEmpty()) L("Choose at least one pet.") else null),
                        ),
                    )
                ) return
                step = StaysBookingStep.`when`
            }
            StaysBookingStep.`when` -> {
                if (!form.check(listOf("dates" to (if (datesReady) null else (if (kind.value == "daycare") L("Choose a day") else L("Check-out has to be after check-in.")))))) return
                step = StaysBookingStep.confirm
            }
            StaysBookingStep.confirm -> book(scope, onBooked, dismiss)
        }
    }

    private fun book(scope: CoroutineScope, onBooked: () -> Unit, dismiss: () -> Unit) {
        val client = client.value ?: return
        if (!form.check(listOf("rate" to (if (rate != null && (rate ?: 0) <= 0) L("A price is more than $0. To not charge it, leave it empty and give it as a courtesy when charging.") else null)))) return
        working = true
        scope.launch {
            try {
                val body: Map<String, Any?> = mapOf(
                    "providerClientId" to client.id,
                    "providerClientPetIds" to petIds.value,
                    "kind" to kind.value,
                    "status" to (if (asRequest.value) "requested" else "confirmed"),
                    "unitId" to unitId.value,
                    "checkInDay" to checkInDay.value,
                    "checkInTime" to checkInTime.value,
                    "checkOutDay" to effectiveCheckOut,
                    "checkOutTime" to checkOutTime.value,
                    "rateCents" to (if ((rate ?: 0) > 0) rate else null),
                    "ownerInstructions" to ownerInstructions.value.nonEmpty,
                    "internalNotes" to internalNotes.value.nonEmpty,
                    "locationId" to workspace.locationId,
                )
                val stay: StaysStay = API.post("/provider-stays", body = body)
                val range = if (kind.value == "daycare") StaysDay.short(checkInDay.value) else "${StaysDay.short(checkInDay.value)} – ${StaysDay.short(effectiveCheckOut)}"
                Toast.show(LF("%@ booked · %@", stay.petNames, range))
                announceDataChanged()
                onBooked()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not book this stay."))
            } finally {
                working = false
            }
        }
    }
}

@Composable
fun StaysBookingSheet(
    preset: StaysBookingPreset,
    modifier: Modifier = Modifier,
    onBooked: () -> Unit,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val m = remember { StaysBookingModel(preset) }
    val step = m.step

    ProviderModal(
        title = L("New stay"),
        subtitle = L("Book boarding or a day of daycare. The room is held as soon as you save."),
        systemImage = "bed-double",
        accent = ModalAccent.aqua,
        isDirty = m.client.value != null || step != StaysBookingStep.who,
        form = m.form,
        primaryTitle = if (step == StaysBookingStep.confirm) (if (m.asRequest.value) L("Save request") else L("Book stay")) else L("Continue"),
        isWorking = m.working,
        steps = ModalSteps(
            steps = listOf(L("Who") to "user-round", L("When and where") to "calendar-days", L("Confirm") to "check"),
            current = step.ordinal,
        ),
        onBack = if (step != StaysBookingStep.who) ({ m.step = StaysBookingStep.entries.getOrElse(m.step.ordinal - 1) { StaysBookingStep.who } }) else null,
        primaryIcon = if (step == StaysBookingStep.confirm) "check" else null,
        onPrimary = { m.next(scope, onBooked, dismiss) },
        modifier = modifier,
    ) {
        val client = m.client.value
        if (step != StaysBookingStep.who && client != null) {
            // What it's about, once chosen — the same pills as the other create modals; Back changes it.
            ContextPills(client = client.ownerName, linked = client.householdId != null, pets = m.pets.map { it.name }) {
                if (step == StaysBookingStep.confirm) {
                    val kind = m.kind.value
                    Chip(
                        text = "${StaysWords.kind(kind)} · ${if (kind == "daycare") StaysDay.short(m.checkInDay.value) else "${StaysDay.short(m.checkInDay.value)} – ${StaysDay.short(m.effectiveCheckOut)}"}",
                        tone = Chip.Tone.muted, icon = if (kind == "daycare") "sun" else "moon", size = Chip.Size.large,
                    )
                    m.units.firstOrNull { it.id == m.unitId.value }?.let { room ->
                        Chip(text = room.name, tone = Chip.Tone.muted, icon = "bed-double", size = Chip.Size.large)
                    }
                }
            }
        }
        when (step) {
            StaysBookingStep.who ->
                ClientPetPicker(client = m.client, petIds = m.petIds, pets = ClientPetPicker.Pets.several, petRequired = true, petKey = "pets", form = m.form)
            StaysBookingStep.`when` -> StaysBookingWhenStep(m)
            StaysBookingStep.confirm -> StaysBookingConfirmStep(m)
        }
    }

    LaunchedEffect(Unit) { m.loadBasics() }
    StaysOnChanged(m.kind.value) {
        m.applyDefaultTimes()
        m.clearRoomIfUnfit()
    }
    StaysOnChanged(m.checkInDay.value) { m.loadRange() }
    StaysOnChanged(m.checkOutDay.value) { m.loadRange() }
    StaysOnChanged(m.unitId.value) { m.applyRoomRate() }
}

// MARK: Steps

@Composable
private fun StaysBookingWhenStep(m: StaysBookingModel) {
    val kind = m.kind.value
    Segmented(
        options = listOf(Segmented.Option("boarding", L("Boarding"), icon = "moon"), Segmented.Option("daycare", L("Daycare"), icon = "sun")),
        selection = m.kind,
        label = L("Kind"),
    )
    ModalSection(title = L("When")) {
        // A new stay starts today or later (the server says the same).
        DayField(label = if (kind == "daycare") L("Day") else L("Check-in"), day = m.checkInDay, min = StaysDay.today(), key = "dates", form = m.form)
        if (kind != "daycare") {
            DayField(label = L("Check-out"), day = m.checkOutDay, min = StaysDay.add(m.checkInDay.value, 1))
            if (m.datesReady) Text(StaysWords.units(kind, m.nights), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
        }
        TimeField(label = L("Arrival time"), value = m.checkInTime)
        TimeField(label = L("Pickup time"), value = m.checkOutTime)
    }
    ModalSection(title = L("Room"), subtitle = if (m.units.isEmpty()) L("Add rooms in the Rooms tab to assign them here.") else null) {
        // A room that doesn't fit says why and stays unchosen.
        val options = m.roomOptions
        ChoiceCards(
            options = listOf(Triple<String?, String, String?>(null, L("Decide later"), L("Book now, assign a room before arrival"))) +
                options.map { Triple<String?, String, String?>(it.unit.id, it.unit.name, it.detail) },
            selection = stateOf({ m.unitId.value }, { id ->
                val option = if (id != null) options.firstOrNull { it.unit.id == id } else null
                if (option != null && !option.available) Toast.error(option.detail) else m.unitId.value = id
            }),
        )
    }
}

@Composable
private fun StaysBookingConfirmStep(m: StaysBookingModel) {
    val kind = m.kind.value
    val rate = m.rate
    val zeroTyped = rate != null && rate <= 0
    ModalSection(title = L("Price")) {
        key("rate-${m.unitId.value ?: "none"}-$kind") {
            MoneyField(
                label = if (kind == "daycare") L("Price per pet per day") else L("Price per pet per night"),
                cents = stateOf({ m.rate }, { value -> m.rate = value; m.rateTouched = true }),
                key = "rate", form = m.form,
            )
        }
        // The total, the same height as the price.
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Theme.wash(Theme.butter, 40.0), RoundedCornerShape(Theme.boxRadius))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                StaysWords.units(kind, m.nights) + (if (m.petCount > 1) " × " + LF("%d pets", m.petCount) else ""),
                style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (rate == null || zeroTyped) L("No price yet") else Money.format(m.nights * rate * m.petCount, currency = m.currency),
                style = brandHeading(18), color = Theme.inkStrong,
            )
        }
        if (zeroTyped) {
            Text(
                L("A price is more than $0. To not charge it, leave it empty and give it as a courtesy when charging."),
                style = Theme.text(12, FontWeight.SemiBold), color = Theme.destructive,
            )
        } else if (rate == null) {
            // Booking only holds the room: the bill at check-out is where it gets its price, or goes as a courtesy.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.mango, 16.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Glyph("tag", 14.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 1.dp))
                Text(L("No price yet: when charging the check-out, you put one or give it as a courtesy."), style = Theme.text(12), color = Theme.inkStrong)
            }
        }
    }
    ModalSection(title = L("How to save it")) {
        ChoiceCards(
            options = listOf(
                Triple(false, L("Book now"), L("Confirmed, and the room is held.")),
                Triple(true, L("Hold as a request"), L("Keeps the room, confirm it later")),
            ),
            selection = m.asRequest,
        )
    }
    ModalSection(title = L("Notes")) {
        PMTextField(L("What the owner asked for"), text = m.ownerInstructions, prompt = L("e.g. Afraid of thunder, no chicken treats"), multiline = true)
        PMTextField(L("Note for the team"), text = m.internalNotes, prompt = L("Only your team sees this"), multiline = true)
    }
}
