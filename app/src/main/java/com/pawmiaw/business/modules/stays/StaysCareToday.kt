package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

//
//  StaysCareToday.kt (iOS StaysCareToday.swift)
//
//  Today's care (web stays-care-today.tsx): every pet staying in, its room,
//  pickup (red when past due and still here), what the stay costs so far,
//  allergies, vaccine issues, the owner's words and belongings, and the day's
//  tasks from its care plan to tick done or skipped as they happen, with when
//  the family last heard from the hotel and the report card a tap away.
//

private class StaysReportTarget(val stayId: String, val pet: StaysPet) {
    val id: String get() = pet.id
}

@Composable
fun StaysCareTodayView(canCare: Boolean, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf<String?>(null) }
    var board by remember { mutableStateOf<StaysCareBoard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reportFor by remember { mutableStateOf<StaysReportTarget?>(null) }

    val shownDay = board?.dayKey ?: day
    val tasks = (board?.pets ?: emptyList()).flatMap { it.tasks ?: emptyList() }

    suspend fun load() {
        try {
            board = API.get<StaysCareBoard>("/provider-stay-care", mapOf("day" to day, "locationId" to workspace.locationId))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }

    /** Ticked on the spot: the board changes before the server answers, and goes back if it refuses. */
    fun mark(taskId: String, status: String) {
        val previous = board
        board?.let { current ->
            board = current.copy(
                pets = current.pets?.map { pet ->
                    pet.copy(tasks = pet.tasks?.map { task -> if (task.id == taskId) task.copy(status = status) else task })
                },
            )
        }
        scope.launch {
            try {
                API.send("PATCH", "/provider-stay-tasks/$taskId", body = mapOf("status" to status))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                board = previous
                Toast.error(e, fallback = L("Could not save that."))
            }
        }
    }

    @Composable
    fun Fact(icon: String, label: String, value: String, hint: String?, late: Boolean, modifier: Modifier) {
        Column(
            modifier
                .fillMaxHeight()
                .background(Theme.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(icon, 14.dp, tint = Theme.inkSoft)
                Text(
                    label.uppercase(),
                    style = brandText(10.2, FontWeight.ExtraBold).tracking(10.2 * 0.12), color = Theme.inkSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FittedText(
                value,
                style = Theme.text(14, FontWeight.Bold), color = if (late) Theme.destructive else Theme.inkStrong,
                minimumScaleFactor = 0.8f,
            )
            if (hint != null) {
                Text(
                    hint,
                    style = Theme.text(11.2, if (late) FontWeight.Bold else FontWeight.Normal),
                    color = if (late) Theme.destructive else Theme.inkSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    fun TaskRow(task: StaysCareTask) {
        val finished = task.status != "pending"
        val shape = RoundedCornerShape(Theme.boxRadius)
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (task.status == "skipped") 0.7f else 1f)
                .background(
                    when (task.status) {
                        "done" -> Theme.wash(Theme.lime, 30.0)
                        "skipped" -> Theme.surfaceMuted
                        else -> Color.Transparent
                    },
                    shape,
                )
                .border(1.dp, if (task.status == "pending") Theme.borderSoft else Color.Transparent, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(StaysWords.taskIcon(task.kind), 16.dp, tint = Theme.blue)
            Text(
                task.time?.let { StaysDay.clock(it) } ?: "—",
                style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkSoft,
                modifier = Modifier.width(76.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    task.label ?: "",
                    style = Theme.text(14, FontWeight.SemiBold).copy(textDecoration = if (task.status == "skipped") TextDecoration.LineThrough else null),
                    color = Theme.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val name = task.doneByName
                if (finished && name != null) {
                    Text(
                        if (task.status == "done") LF("Done by %@", name) else LF("Skipped by %@", name),
                        style = Theme.text(12), color = Theme.inkSoft,
                    )
                }
            }
            if (canCare) {
                if (finished) {
                    PMButton(L("Undo"), variant = ButtonVariant.outline, size = ButtonSize.compact) { mark(task.id, "pending") }
                } else {
                    IconCircleButton(systemImage = "skip-forward", label = L("Skip"), tint = Theme.inkSoft) { mark(task.id, "skipped") }
                    IconCircleButton(systemImage = "check", label = L("Done"), tint = Theme.blue) { mark(task.id, "done") }
                }
            }
        }
    }

    /** When the family last heard from the hotel — a nudge when it has been a while. */
    @Composable
    fun LastReport(at: String?, modifier: Modifier = Modifier) {
        val hours = DateText.parse(at)?.let { (System.currentTimeMillis() - it.toEpochMilli()) / 3_600_000.0 }
        val stale = hours == null || hours >= 24
        val text = if (hours != null) {
            when {
                hours < 1 -> LF("Last report card %d min ago", max(1, (hours * 60).roundToInt()))
                hours < 24 -> LF("Last report card %d h ago", hours.roundToInt())
                else -> LF("Last report card %d d ago", (hours / 24).toInt())
            }
        } else {
            L("No report card sent yet")
        }
        val tint = if (stale) Theme.goldText else Theme.inkSoft
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("message-square-heart", 14.dp, tint = tint)
            Text(text, style = Theme.text(12, FontWeight.SemiBold), color = tint)
        }
    }

    @Composable
    fun PetCard(pet: StaysCarePet) {
        val petTasks = pet.tasks ?: emptyList()
        val petDone = petTasks.count { it.status != "pending" }
        Card(spacing = 12.dp) {
            Row(
                Modifier.fillMaxWidth().plainButton { navigator.push(ProviderRoute.stay(pet.stayId)) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PetAvatar(name = pet.petName, species = pet.petSpecies, photoURL = pet.photoUrl, size = 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(pet.petName, style = brandHeading(20), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pet.petBreed ?: StaysWords.petSpecies(pet.petSpecies),
                        style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Chip(
                    text = when {
                        pet.kind == "daycare" -> L("Day stay")
                        pet.lateDeparture == true -> LF("Night %d", pet.nightNumber ?: 1)
                        else -> LF("Night %d of %d", pet.nightNumber ?: 1, pet.totalNights ?: 1)
                    },
                    tone = Chip.Tone.info,
                )
            }

            val boxShape = RoundedCornerShape(Theme.boxRadius)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(boxShape)
                    .background(Theme.borderSoft)
                    .border(1.dp, Theme.borderSoft, boxShape),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Fact("bed-double", L("Room"), pet.unitName ?: L("No room yet"), hint = null, late = false, modifier = Modifier.weight(1f))
                Fact(
                    "log-out", L("Pickup"), "${StaysDay.format(pet.checkOutDay, "EEEd")} · ${StaysDay.clock(pet.checkOutTime)}",
                    hint = if (pet.lateDeparture == true) L("Past due · still here") else null, late = pet.lateDeparture == true,
                    modifier = Modifier.weight(1f),
                )
                Fact(
                    "wallet", L("Stay"), Money.format(pet.estimatedTotalCents, currency = pet.currency),
                    hint = if ((pet.rateCents ?: 0) > 0) "${StaysWords.units(pet.kind ?: "boarding", pet.totalNights ?: 1)} × ${Money.short(pet.rateCents)}" else L("No rate set"),
                    late = false, modifier = Modifier.weight(1f),
                )
            }

            val client = pet.clientName
            if (client != null) {
                FlowLayout(spacing = 16.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("user-round", 16.dp, tint = Theme.inkSoft)
                        Text(client, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                    }
                    pet.clientPhone.nonEmpty?.let { PhoneLink(phone = it) }
                }
            }
            StaysAllergies(allergies = pet.allergies ?: emptyList())
            val issues = pet.vaccineIssues
            if (!issues.isNullOrEmpty()) {
                FlowLayout(spacing = 6.dp) { for (issue in issues) StaysVaccineChip(key = issue.key, status = issue.status) }
            }
            pet.ownerInstructions.nonEmpty?.let { StaysNoteBlock(label = L("From the owner"), text = it, tint = Theme.aqua) }
            val belongings = pet.belongings
            if (!belongings.isNullOrEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("backpack", 14.dp, tint = Theme.inkSoft)
                    Text(belongings.joinToString(" · "), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
            pet.careNotes.nonEmpty?.let { StaysNoteBlock(label = "", text = it, tint = Theme.mango) }

            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StaysEyebrow(text = L("Today’s care"))
                if (petTasks.isNotEmpty()) {
                    Text("$petDone/${petTasks.size}", style = brandText(10.9, FontWeight.ExtraBold), color = Theme.inkSoft)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (task in petTasks) TaskRow(task)
                if (petTasks.isEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 3.dp)
                            .plainButton { navigator.push(ProviderRoute.stay(pet.stayId)) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("clipboard-list", 16.dp, tint = Theme.inkSoft)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(L("Nothing planned for today."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
                            Text(L("Meals, medicine and walks come from the care plan set at check-in."), style = Theme.text(12), color = Theme.inkSoft)
                        }
                    }
                }
            }
            RowDivider(modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LastReport(pet.lastReportAt, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                if (canCare) {
                    PMButton(L("Report card"), systemImage = "message-square-heart", size = ButtonSize.compact) {
                        reportFor = StaysReportTarget(
                            stayId = pet.stayId,
                            pet = StaysPet(
                                id = pet.stayPetId, providerClientPetId = null, petName = pet.petName, petSpecies = pet.petSpecies,
                                petBreed = pet.petBreed, photoUrl = pet.photoUrl, linkedToHousehold = null, carePlan = null,
                                vaccineCheck = null, vaccinesConfirmedAt = null, vaccinesConfirmedByName = null, allergies = null,
                            ),
                        )
                    }
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(systemImage = "chevron-left", label = L("Previous day"), tint = Theme.blue) {
                    shownDay?.let { day = StaysDay.add(it, -1) }
                }
                FittedText(
                    shownDay?.let { StaysDay.long(it) } ?: "…",
                    modifier = Modifier.widthIn(min = 176.dp).weight(1f, fill = false),
                    style = brandHeading(20), color = Theme.inkStrong, minimumScaleFactor = 0.8f,
                )
                IconCircleButton(systemImage = "chevron-right", label = L("Next day"), tint = Theme.blue) {
                    shownDay?.let { day = StaysDay.add(it, 1) }
                }
                if (day != null) PMButton(L("Today"), variant = ButtonVariant.outline, size = ButtonSize.compact) { day = null }
            }
            if (tasks.isNotEmpty()) {
                val done = tasks.count { it.status != "pending" }
                val skipped = tasks.count { it.status == "skipped" }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The day's progress (web: a 10 pt bar, the blue over the muted track).
                    ProgressBar(value = done.toDouble() / max(1, tasks.size).toDouble(), height = 10.dp, width = 160.dp, track = Theme.surfaceMuted)
                    Text(
                        LF("%d of %d done", done - skipped, tasks.size) + (if (skipped > 0) " · " + LP(skipped, "%d skipped", "%d skipped (plural)") else ""),
                        style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft,
                    )
                }
            }
        }
        val shown = board
        if (shown != null) {
            val pets = shown.pets ?: emptyList()
            if (pets.isEmpty()) {
                EmptyState(
                    systemImage = "moon", title = L("No pets staying in"),
                    message = L("Once a pet is checked in, its care plan turns into the day’s tasks here."), accent = Theme.aqua, panel = true,
                )
            } else {
                StaysGrid(items = pets, minimum = 340.dp, spacing = 16.dp) { PetCard(it) }
            }
        } else if (error != null) {
            ErrorState(message = error ?: "") { load() }
        } else {
            LoadingState()
        }
    }

    ReloadsOnScope { load() }
    StaysOnChanged(day) { load() }
    reportFor?.let { target ->
        AppSheet(onDismissRequest = { reportFor = null }) {
            StaysReportCardSheet(stayId = target.stayId, pets = listOf(target.pet), familyOnApp = null, initialPetId = target.pet.id) {
                scope.launch { load() }
            }
        }
    }
}
