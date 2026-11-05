package com.pawmiaw.business.stays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import com.pawmiaw.business.schedule.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

//
//  StaysHomeVisit.kt (iOS StaysHomeVisit.swift)
//
//  One visit (or night) of a home stay, run one way wherever it is opened —
//  the stay's visit rows and Domicilio (web home-stay-visit-sheet.tsx,
//  stay-visit-runner.tsx, home-stay-arrival.tsx): what the sitter does there —
//  each pet's care and the house's tasks, ticked as they happen — the house
//  rules, the report cards written at it, and every move: arrive (through the
//  consent gate the stay requires on the first arrival: the vet
//  authorization), leave with a note, undo or reopen, couldn't get in, cancel,
//  mark missed, move it or give it to someone else. Arrive and leave carry the
//  press time and, when the business records location, where the phone was
//  (core/VisitLocation.kt); from Domicilio arriving goes through its outbox.
//

private enum class StaysVisitSheetKind(val rawValue: String) {
    leave("leave"), cancel("cancel"), missed("missed"), noAccess("no-access"), change("change"), report("report"), gate("gate");

    val id: String get() = rawValue
}

/** From the stay's page: the stay and its home are already loaded (Swift `init(stay:home:visitId:)`). */
@Composable
fun StaysHomeVisitSheet(stay: StaysStay, home: StaysHomeDetail, visitId: String, modifier: Modifier = Modifier) {
    StaysHomeVisitSheetBody(
        stayId = stay.id, visitId = visitId, initialStay = stay, initialHome = home,
        startWith = null, onArrive = null, locate = null, onOpenStay = null, modifier = modifier,
    )
}

/**
 * From anywhere else (Domicilio): by its ids, the stay read here (Swift `init(stayId:visitId:…)`).
 *
 * - `startWith`: opened to leave the home or write the report card right away (Domicilio's Finish and Report).
 * - `onArrive`: Domicilio's "I've arrived": through its outbox, with the press time and place. Null: arrive here.
 * - `locate`: where the phone is at a press (Domicilio's stamp). Null: this sheet stamps when the business records location.
 * - `onOpenStay`: "Open the home stay" (shown when the sheet is opened from elsewhere).
 */
@Composable
fun StaysHomeVisitSheet(
    stayId: String,
    visitId: String,
    startWith: String? = null,
    onArrive: (() -> Unit)? = null,
    locate: (suspend () -> VisitPosition)? = null,
    onOpenStay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    StaysHomeVisitSheetBody(
        stayId = stayId, visitId = visitId, initialStay = null, initialHome = null,
        startWith = startWith, onArrive = onArrive, locate = locate, onOpenStay = onOpenStay, modifier = modifier,
    )
}

object StaysHomeVisitSheet {
    fun timeText(visit: StaysHomeVisit, zone: ZoneId): String {
        val start = StaysDay.clockAt(visit.startsAt, zone = zone)
        val end = StaysDay.clockAt(visit.endsAt, zone = zone)
        val a = DateText.parse(visit.startsAt)
        val b = DateText.parse(visit.endsAt)
        if (visit.kind == "overnight" && a != null && b != null && DateText.dayKey(a, zone = zone) != DateText.dayKey(b, zone = zone)) {
            return LF("%@ – %@ next morning", start, end)
        }
        return "$start – $end"
    }

    /** A checklist with one task's new status (its "done by" cleared when it goes back to pending). */
    fun ticked(extras: StaysVisitExtras?, taskId: String, status: String): StaysVisitExtras? {
        if (extras == null) return null
        fun change(task: StaysChecklistTask): StaysChecklistTask {
            if (task.id != taskId) return task
            return StaysChecklistTask(
                id = task.id, label = task.label, time = task.time, status = status, doneAt = task.doneAt,
                doneByName = if (status == "pending") null else task.doneByName, note = task.note, kind = task.kind,
            )
        }
        return StaysVisitExtras(
            pets = extras.pets?.map { StaysVisitPet(stayPetId = it.stayPetId, petName = it.petName, photoUrl = it.photoUrl, careNotes = it.careNotes, tasks = it.tasks?.map(::change)) },
            houseTasks = extras.houseTasks?.map(::change),
            houseRules = extras.houseRules,
            reports = extras.reports,
            pendingTasks = extras.pendingTasks,
        )
    }
}

@Composable
private fun StaysHomeVisitSheetBody(
    stayId: String,
    visitId: String,
    initialStay: StaysStay?,
    initialHome: StaysHomeDetail?,
    startWith: String?,
    onArrive: (() -> Unit)?,
    locate: (suspend () -> VisitPosition)?,
    onOpenStay: (() -> Unit)?,
    modifier: Modifier,
) {
    val workspace = Workspace.shared
    val dismiss = LocalDismiss.current
    val coroutines = rememberCoroutineScope()
    var stay by remember { mutableStateOf(initialStay) }
    var home by remember { mutableStateOf(initialHome) }
    var visit by remember { mutableStateOf<StaysHomeVisit?>(null) }
    var extras by remember { mutableStateOf<StaysVisitExtras?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var meId by remember { mutableStateOf<String?>(null) }
    var stamps by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<StaysVisitSheetKind?>(null) }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    fun zone(): ZoneId = home?.zone ?: Workspace.shared.timeZone
    fun shown(): StaysHomeVisit? = visit ?: home?.visits?.firstOrNull { it.id == visitId }
    /** The place of a press: Domicilio's, else this sheet's own when the business records it. */
    fun stamp(): (suspend () -> VisitPosition)? = locate ?: VisitLocation.stamp(stamps)

    // MARK: Loading

    suspend fun load() {
        try {
            // The stay moves with its visits (the first arrival starts it): read again each time.
            val (loaded, detail) = StaysAPI.homeStay(stayId)
            stay = loaded
            if (detail != null) home = detail
            val data = API.data("GET", "/provider-homestays/$stayId/visits/$visitId", emptyMap(), null)
            visit = API.decode<StaysHomeVisit>(data)
            extras = runCatching { API.decode<StaysVisitExtras>(data) }.getOrNull()
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (shown() == null) failed = StaysWords.homeError(e, fallback = "Couldn’t load this visit.")
        }
        if (meId == null) meId = StaysHomeTeam.members().firstOrNull { it.isCurrentMember == true }?.id
        if (locate == null && !stamps) stamps = VisitLocation.stampsOn()
        if (!started) {
            started = true
            if (startWith == "leave" && shown()?.status == "in_progress") sheet = StaysVisitSheetKind.leave
            if (startWith == "report" && stay?.status == "checked_in") sheet = StaysVisitSheetKind.report
        }
    }

    // MARK: Rules

    val canManage = workspace.can(Permission.staysManage)

    fun canAct(visit: StaysHomeVisit): Boolean {
        val theirs = visit.sitter == null || visit.sitter.memberId == meId
        return workspace.can(Permission.staysCare) && (workspace.isOwnerOrAdmin || canManage || theirs)
    }

    fun stayOpen(visit: StaysHomeVisit, stay: StaysStay): Boolean =
        stay.status == "confirmed" || stay.status == "checked_in" || (stay.status == "requested" && visit.kind == "meet_greet")

    /** Arriving opens three hours before the visit. */
    fun arrivalOpens(visit: StaysHomeVisit): Instant? = DateText.parse(visit.startsAt)?.minusSeconds(3 * 3600L)

    fun pendingTasks(): List<String> =
        ((extras?.pets ?: emptyList()).flatMap { it.tasks ?: emptyList() } + (extras?.houseTasks ?: emptyList()))
            .filter { it.status == "pending" }.mapNotNull { it.label }

    /** Ticked on the spot: shown at once, put back if the server says no. */
    fun mark(task: StaysChecklistTask, scope: String, status: String) {
        val before = extras
        extras = StaysHomeVisitSheet.ticked(extras, task.id, status)
        coroutines.launch {
            val path = if (scope == "pet") "/provider-stay-tasks/${task.id}" else "/provider-homestays/house-tasks/${task.id}"
            try {
                API.send("PATCH", path, body = mapOf("status" to status))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                extras = before
                Toast.error(StaysWords.homeError(e, fallback = "Could not save that."))
            }
        }
    }

    // MARK: Moves

    /** Through the consent gate: an owner or admin may go ahead with a reason. The press time and place go with it. */
    suspend fun arrive(visit: StaysHomeVisit, stay: StaysStay, override: String?) {
        val body = mutableMapOf<String, Any?>("at" to DateText.iso(Instant.now()))
        stamp()?.let { body["position"] = it().body }
        if (override != null) {
            body["overrideConsent"] = true
            body["overrideReason"] = override.nonEmpty
        }
        API.send("POST", "/provider-homestays/${stay.id}/visits/${visit.id}/arrive", body = body)
        Toast.show(
            if (stay.status == "confirmed" && visit.kind != "meet_greet") {
                if (stay.familyOnApp) L("Arrived. The stay has started and the family has been told.") else L("Arrived. The stay has started.")
            } else {
                L("Arrived.")
            },
        )
        announceDataChanged()
        load()
    }

    fun arrivePressed(visit: StaysHomeVisit, stay: StaysStay) {
        if (onArrive != null) {
            onArrive()
            return
        }
        working = true
        coroutines.launch {
            try {
                arrive(visit, stay, override = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is APIError && e.code == "consent_required") sheet = StaysVisitSheetKind.gate
                else Toast.error(StaysWords.homeError(e, fallback = "Could not mark the arrival."))
            } finally {
                working = false
            }
        }
    }

    fun undo(visit: StaysHomeVisit) {
        working = true
        coroutines.launch {
            try {
                API.send("POST", "/provider-homestays/$stayId/visits/${visit.id}/undo", body = emptyMap())
                Toast.show(L("Undone."))
                announceDataChanged()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(StaysWords.homeError(e, fallback = "Could not undo that."))
            } finally {
                working = false
            }
        }
    }

    // MARK: Content

    @Composable
    fun Moves(visit: StaysHomeVisit, stay: StaysStay, status: String) {
        val act = canAct(visit)
        FlowLayout(spacing = 8.dp) {
            if (act && stayOpen(visit, stay) && (status == "planned" || status == "missed")) {
                val opens = arrivalOpens(visit)
                if (status == "planned" && opens != null && opens.isAfter(Instant.now())) {
                    Text(
                        LF("You can mark the arrival from %@.", StaysDay.clockAt(DateText.iso(opens), zone = zone())),
                        style = Theme.text(13, FontWeight.SemiBold), color = Theme.inkSoft,
                    )
                } else {
                    PMButton(
                        if (status == "missed") L("Arrived after all") else L("Arrive"), systemImage = "log-in", size = ButtonSize.compact, isWorking = working,
                    ) { arrivePressed(visit, stay) }
                }
            }
            if (act && status == "in_progress") {
                PMButton(L("Leave"), systemImage = "log-out", size = ButtonSize.compact) { sheet = StaysVisitSheetKind.leave }
            }
            if (act && (status == "in_progress" || status == "done") && stay.status == "checked_in") {
                PMButton(L("Report card"), systemImage = "message-square-heart", size = ButtonSize.compact) { sheet = StaysVisitSheetKind.report }
            }
            if (act && stayOpen(visit, stay) && status in listOf("planned", "missed", "in_progress")) {
                PMButton(L("Couldn’t get in"), systemImage = "door-closed", variant = ButtonVariant.outline, size = ButtonSize.compact) { sheet = StaysVisitSheetKind.noAccess }
            }
            if (act && status in listOf("in_progress", "done", "missed") && !(status == "missed" && !canManage)) {
                PMButton(
                    if (status == "missed") L("Reopen") else L("Undo"), systemImage = "undo-2", variant = ButtonVariant.outline, size = ButtonSize.compact, isWorking = working,
                ) { undo(visit) }
            }
            if (canManage && status == "planned") {
                PMButton(L("Move or give to someone else"), systemImage = "pencil", variant = ButtonVariant.outline, size = ButtonSize.compact) { sheet = StaysVisitSheetKind.change }
                PMButton(L("Mark missed"), systemImage = "user-x", variant = ButtonVariant.outline, size = ButtonSize.compact) { sheet = StaysVisitSheetKind.missed }
            }
            if (canManage && (status == "planned" || status == "missed")) {
                PMButton(L("Cancel visit"), systemImage = "circle-x", variant = ButtonVariant.outline, size = ButtonSize.compact) { sheet = StaysVisitSheetKind.cancel }
            }
        }
    }

    @Composable
    fun TaskRow(task: StaysChecklistTask, scope: String, enabled: Boolean) {
        val done = task.status == "done"
        val skipped = task.status == "skipped"
        val soft = Theme.inkSoft
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (done) Theme.blue.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(Theme.chipRadius))
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (done) Theme.blue else Color.Transparent, CircleShape)
                    .border(2.dp, if (done) Theme.blue else Theme.borderStrong, CircleShape)
                    .plainButton(
                        enabled = enabled,
                        label = if (done) LF("Mark %@ as not done", task.label ?: "") else LF("Mark %@ as done", task.label ?: ""),
                    ) { mark(task, scope = scope, status = if (done) "pending" else "done") },
                contentAlignment = Alignment.Center,
            ) {
                if (done) Glyph("check", 16.dp, tint = Theme.onBlue)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = soft)) { append(task.time?.let { StaysDay.clock(it) + " " } ?: "") }
                        append(task.label ?: "")
                    },
                    style = Theme.text(14, FontWeight.SemiBold).copy(textDecoration = if (skipped) TextDecoration.LineThrough else null),
                    color = if (skipped) Theme.inkSoft else Theme.inkStrong,
                )
                val by = task.doneByName
                if (by != null && task.status != "pending") Text(by, style = Theme.text(12), color = Theme.inkSoft)
            }
            if (enabled && task.status == "pending") {
                Row(
                    Modifier
                        .plainButton { mark(task, scope = scope, status = "skipped") }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("skip-forward", 14.dp, tint = Theme.inkSoft)
                    Text(L("Skip"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                }
            } else if (enabled && skipped) {
                Text(
                    L("Undo"), style = Theme.text(12, FontWeight.Bold), color = Theme.blue,
                    modifier = Modifier.plainButton { mark(task, scope = scope, status = "pending") },
                )
            }
        }
    }

    @Composable
    fun Checklist(visit: StaysHomeVisit, status: String) {
        val list = extras
        val pets = (list?.pets ?: emptyList()).filter { (it.tasks ?: emptyList()).isNotEmpty() || it.careNotes.nonEmpty != null }
        val house = list?.houseTasks ?: emptyList()
        val all = (list?.pets ?: emptyList()).flatMap { it.tasks ?: emptyList() } + house
        val done = all.count { it.status != "pending" && it.status != null }
        val enabled = canAct(visit) && status == "in_progress"
        ModalSection(
            title = L("Checklist"),
            subtitle = if (status == "planned" || status == "missed") L("Tick it once you arrive.") else (if (all.isEmpty()) null else LF("%d of %d done", done, all.size)),
        ) {
            if (list == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.inkSoft)
                }
            } else if (all.isEmpty()) {
                EmptyState(systemImage = "clipboard-list", title = L("Nothing planned at this visit."), accent = Theme.aqua)
            } else {
                pets.forEachIndexed { index, pet ->
                    key(index) {
                        Card(padding = 12.dp, spacing = 8.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PetAvatar(name = pet.petName, species = null, photoURL = pet.photoUrl, size = 28.dp)
                                Text(pet.petName ?: "", style = brandHeading(18), color = Theme.inkStrong)
                            }
                            pet.careNotes.nonEmpty?.let { Text(it, style = Theme.text(14), color = Theme.inkSoft) }
                            for (task in pet.tasks ?: emptyList()) key(task.id) { TaskRow(task, scope = "pet", enabled = enabled) }
                        }
                    }
                }
                if (house.isNotEmpty()) {
                    Card(padding = 12.dp, spacing = 8.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("house", 16.dp, tint = Theme.blue)
                            Text(L("The house"), style = brandHeading(18), color = Theme.inkStrong)
                        }
                        for (task in house) key(task.id) { TaskRow(task, scope = "house", enabled = enabled) }
                    }
                }
            }
        }
    }

    val current = shown()
    val currentStay = stay
    ProviderModal(
        // Over the stay it says the visit; on its own (from Domicilio) whose visit it is, as on the web.
        title = current?.let { v ->
            if (onOpenStay != null && currentStay != null) "${currentStay.petNames} · ${StaysWords.homeVisitKind(v.kind)}"
            else "${StaysWords.homeVisitKind(v.kind)} · ${StaysDay.short(v.dayKey)}"
        } ?: L("Visit"),
        subtitle = current?.let { v ->
            if (onOpenStay != null) {
                listOfNotNull(StaysDay.short(v.dayKey), StaysHomeVisitSheet.timeText(v, zone = zone()), currentStay?.clientName).mapNotNull { it.nonEmpty }.joinToString(" · ")
            } else {
                listOf(StaysHomeVisitSheet.timeText(v, zone = zone()), v.sitter?.name ?: L("No sitter yet")).mapNotNull { it.nonEmpty }.joinToString(" · ")
            }
        },
        systemImage = if (current?.kind == "overnight") "moon-star" else "house",
        accent = ModalAccent.aqua,
        cancelTitle = L("Close"),
        modifier = modifier,
    ) {
        if (current != null && currentStay != null) {
            val status = current.status ?: "planned"
            val state = StaysWords.homeVisitStatus(status)
            FlowLayout(spacing = 8.dp) {
                StaysHomeVisitChips(visit = current, status = state)
            }
            if (current.arrivedAt != null || current.leftAt != null) {
                Text(
                    listOfNotNull(
                        current.arrivedAt?.let { LF("Arrived %@", StaysDay.clockAt(it, zone = zone())) + (current.arrivedByName?.let { name -> " · $name" } ?: "") },
                        current.leftAt?.let { LF("Left %@", StaysDay.clockAt(it, zone = zone())) },
                    ).joinToString(" · "),
                    style = Theme.text(12), color = Theme.inkSoft,
                )
            }
            Moves(current, currentStay, status = status)
            if (onOpenStay != null) {
                // From Domicilio: the way to the whole stay (its plan, keys, house, bill).
                Row(
                    Modifier.plainButton {
                        dismiss()
                        onOpenStay()
                    },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph("house", 16.dp, tint = Theme.blue)
                    Text(L("Open the home stay"), style = Theme.text(14, FontWeight.Bold), color = Theme.blue)
                }
            }
            val sitterName = current.sitter?.name
            if (!canAct(current) && workspace.can(Permission.staysCare) && sitterName != null) {
                Text(LF("This is %@’s visit.", sitterName), style = Theme.text(14), color = Theme.inkSoft)
            }
            current.note.nonEmpty?.let { StaysNoteBlock(label = L("Note from the visit"), text = it) }
            current.cancelReason.nonEmpty?.let { StaysNoteBlock(label = L("Why it was cancelled"), text = it) }
            Checklist(current, status = status)
            extras?.houseRules.nonEmpty?.let { StaysNoteBlock(label = L("House rules"), text = it, tint = Theme.mango) }
            val reports = extras?.reports ?: emptyList()
            if (reports.isNotEmpty()) {
                ModalSection(title = L("Report cards from this visit")) {
                    for (report in reports) {
                        key(report.id) {
                            val shape = RoundedCornerShape(Theme.boxRadius)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .border(1.dp, Theme.borderSoft, shape),
                            ) {
                                APIConfig.assetURL(report.photoUrl)?.let { url ->
                                    Box(Modifier.fillMaxWidth().height(180.dp).background(Theme.surfaceMuted)) {
                                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                    }
                                }
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StaysWords.mood(report.mood)?.let { Text(it.emoji) }
                                    Text(report.message ?: "", style = Theme.text(14), color = Theme.inkStrong)
                                    Text(StaysDay.clockAt(report.createdAt, zone = zone()), style = Theme.text(12), color = Theme.inkSoft)
                                }
                            }
                        }
                    }
                }
            }
        } else if (failed != null) {
            ErrorState(message = failed ?: "") { load() }
        } else {
            LoadingState()
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) { AppEvents.dataChanged.collect { launch { load() } } }

    val which = sheet
    val shownVisit = shown()
    val shownStay = stay
    if (which != null && shownVisit != null && shownStay != null) {
        AppSheet(onDismissRequest = { sheet = null }) {
            val whenText = "${StaysDay.short(shownVisit.dayKey)} · ${StaysHomeVisitSheet.timeText(shownVisit, zone = zone())}"
            when (which) {
                StaysVisitSheetKind.leave, StaysVisitSheetKind.cancel, StaysVisitSheetKind.missed, StaysVisitSheetKind.noAccess ->
                    StaysVisitMoveSheet(
                        stayId = shownStay.id, visitId = shownVisit.id, move = which.rawValue, `when` = whenText, pending = pendingTasks(),
                        locate = if (which == StaysVisitSheetKind.leave) stamp() else null,
                    )
                StaysVisitSheetKind.change -> StaysVisitChangeSheet(stay = shownStay, visit = shownVisit, zone = zone())
                StaysVisitSheetKind.report ->
                    StaysReportCardSheet(stayId = shownStay.id, pets = shownStay.petList, familyOnApp = shownStay.familyOnApp, visitId = shownVisit.id) {
                        coroutines.launch { load() }
                    }
                StaysVisitSheetKind.gate ->
                    // The same gate a visit's start has (the consents to sign here or send, then start; owners and admins may go ahead).
                    SchedGateSheet(
                        prompt = SchedGatePrompt(
                            petName = shownStay.petNames, verb = "start", kind = "consent", message = L("A required consent is still missing."),
                            attempt = { extra ->
                                val reason: String? = if (extra.containsKey("overrideConsent")) ((extra["overrideReason"] as? String) ?: "") else null
                                try {
                                    arrive(shownVisit, shownStay, override = reason)
                                } catch (api: APIError) {
                                    if (api.status >= 300) throw SchedFailure(api = api, body = api.body) else throw api
                                }
                            },
                            onDone = {},
                            clientName = shownStay.clientName, familyOnApp = shownStay.familyOnApp,
                            consents = StaysActs.consentContext(shownStay, businessName = workspace.current?.name),
                        ),
                    )
            }
        }
    }
}

// MARK: - Leave, cancel, missed, couldn't get in

/**
 * A home stay's visit: leave (with what is still undone, the press time and place), cancel, mark missed
 * or couldn't get in. Opened from the visit, from Domicilio's card and from Home.
 *
 * - `move`: "leave", "cancel", "missed" or "no-access".
 * - `when`: the visit's day and time, under the title.
 * - `pending`: the checklist still undone, said before leaving so a medicine isn't forgotten (null: read it here).
 * - `checklist`: (done, total), "3 of 5" when the undone names aren't known.
 * - `locate`: leaving: where the phone was, as Domicilio's Finish records it.
 */
@Composable
fun StaysVisitMoveSheet(
    stayId: String,
    visitId: String,
    move: String,
    `when`: String,
    pending: List<String>? = null,
    checklist: Pair<Int, Int>? = null,
    locate: (suspend () -> VisitPosition)? = null,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    val note = remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var undone by remember { mutableStateOf<List<String>>(emptyList()) }
    var working by remember { mutableStateOf(false) }

    val title = when (move) {
        "cancel" -> L("Cancel this visit?")
        "missed" -> L("Mark as missed?")
        "no-access" -> L("Couldn’t get in")
        else -> L("Leave the home")
    }

    val reasons: List<Triple<String, String, String?>> = listOf(
        Triple("code_wrong", L("The code didn’t work"), null),
        Triple("key_missing", L("The key wasn’t there"), null),
        Triple("vault_empty", L("No access details written"), null),
        Triple("nobody_home", L("Nobody was home to let me in"), null),
        Triple("other", L("Something else"), null),
    )

    fun submit() {
        if (!form.check(listOf("reason" to (if (move == "no-access" && reason.isEmpty()) L("Say what happened.") else null)))) return
        working = true
        scope.launch {
            try {
                val body: MutableMap<String, Any?> = when (move) {
                    "cancel" -> mutableMapOf("reason" to note.value.nonEmpty)
                    "missed" -> mutableMapOf("note" to note.value.nonEmpty)
                    "no-access" -> mutableMapOf("reason" to reason, "note" to note.value.nonEmpty)
                    else -> mutableMapOf<String, Any?>("note" to note.value.nonEmpty, "at" to DateText.iso(Instant.now())).also { body ->
                        if (locate != null) body["position"] = locate().body
                    }
                }
                API.send("POST", "/provider-homestays/$stayId/visits/$visitId/$move", body = body)
                Toast.show(
                    when (move) {
                        "cancel" -> L("Visit cancelled.")
                        "missed" -> L("Marked as missed.")
                        "no-access" -> L("Recorded. The family and the owners have been told.")
                        else -> L("Visit done.")
                    },
                )
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val api = e as? APIError
                form.apply(
                    APIError(
                        status = api?.status ?: 400, message = StaysWords.homeError(e, fallback = "Something went wrong. Try again."),
                        code = api?.code, fields = api?.fields ?: emptyMap(),
                    ),
                    known = mapOf("reason" to "reason", "note" to "note"),
                )
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = title,
        subtitle = `when`,
        systemImage = if (move == "leave") "log-out" else if (move == "no-access") "door-closed" else if (move == "missed") "user-x" else "circle-x",
        accent = if (move == "leave") ModalAccent.aqua else ModalAccent.coral,
        isDirty = note.value.isNotEmpty() || reason.isNotEmpty(),
        form = form,
        primaryTitle = when (move) {
            "leave" -> L("Leave")
            "cancel" -> L("Cancel visit")
            "missed" -> L("Mark missed")
            else -> L("Tell them")
        },
        primaryVariant = if (move == "leave") ButtonVariant.primary else ButtonVariant.destructive,
        isWorking = working,
        primaryIcon = if (move == "leave") "log-out" else null,
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        // Web ReasonFrame: the sentence, what it needs, then the words with no label of their own.
        when (move) {
            "no-access" -> {
                Text(L("The family and the owners are told at once, as an incident."), style = Theme.text(14), color = Theme.inkStrong)
                Column(Modifier.formScrollTarget("reason", form), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceCards(options = reasons, selection = stateOf({ reason }, { value -> reason = value; form.clear("reason") }))
                    form.error("reason")?.let { Text(it, style = Theme.text(12, FontWeight.Medium), color = Theme.coral) }
                }
                StaysPlainField(prompt = L("Anything else (optional)"), text = note, key = "note", form = form, multiline = true)
            }
            "cancel" -> {
                Text(L("It won’t be charged. Its checklist moves to another visit that day."), style = Theme.text(14), color = Theme.inkStrong)
                StaysPlainField(prompt = L("Reason (optional)"), text = note, key = "note", form = form, multiline = true)
            }
            "missed" -> {
                Text(L("Nobody went. It isn’t charged, and only someone who manages stays can reopen it."), style = Theme.text(14), color = Theme.inkStrong)
                StaysPlainField(prompt = L("What happened (optional)"), text = note, key = "note", form = form, multiline = true)
            }
            else -> {
                Text(L("The visit is done. A line on how it went stays with the visit."), style = Theme.text(14), color = Theme.inkStrong)
                if (undone.isNotEmpty()) {
                    NoticeCard(tone = NoticeCard.Tone.warning, title = LF("Not done yet: %@", undone.joinToString(", ")))
                } else if (checklist != null && checklist.first < checklist.second) {
                    NoticeCard(tone = NoticeCard.Tone.warning, title = LF("Checklist · %d of %d", checklist.first, checklist.second))
                }
                StaysPlainField(prompt = L("How it went (optional)"), text = note, key = "note", form = form, multiline = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (move != "leave") return@LaunchedEffect
        if (pending != null) {
            undone = pending
            return@LaunchedEffect
        }
        val detail = runCatching { API.get<StaysVisitExtras>("/provider-homestays/$stayId/visits/$visitId") }.getOrNull()
        if (detail != null) {
            undone = ((detail.pets ?: emptyList()).flatMap { it.tasks ?: emptyList() } + (detail.houseTasks ?: emptyList()))
                .filter { it.status == "pending" }.mapNotNull { it.label }
        }
    }
}

// MARK: - Move it or give it to someone else

@Composable
private fun StaysVisitChangeSheet(stay: StaysStay, visit: StaysHomeVisit, zone: ZoneId) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState() }
    var members by remember { mutableStateOf<List<StaysMember>>(emptyList()) }
    var date by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(45) }
    var memberId by remember { mutableStateOf("") }
    val reason = remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    val overlapOk = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val overnight = visit.kind == "overnight"
    val bookedMinutes = run {
        val a = DateText.parse(visit.startsAt)
        val b = DateText.parse(visit.endsAt)
        if (a == null || b == null) 45 else max(0, ((b.toEpochMilli() - a.toEpochMilli()) / 60_000.0).roundToInt())
    }
    val moved = date != visit.dayKey || startTime != StaysDay.clockKey(visit.startsAt, zone = zone) || (!overnight && minutes != bookedMinutes)
    val reassigned = memberId != (visit.sitter?.memberId ?: "")

    fun reset() {
        conflict = false
        overlapOk.value = false
        form.clear("date")
    }

    fun submit() {
        if (!(moved || reassigned)) {
            dismiss()
            return
        }
        if (conflict && !overlapOk.value) {
            Toast.error(L("The sitter is busy then. Check the warning and book it anyway if you meant to."))
            return
        }
        working = true
        scope.launch {
            val body = mutableMapOf<String, Any?>()
            if (moved) {
                body["date"] = date
                body["startTime"] = startTime
                if (!overnight) body["durationMinutes"] = minutes
            }
            if (reassigned) {
                body["memberId"] = memberId.nonEmpty
                body["reason"] = reason.value.nonEmpty
            }
            if (overlapOk.value) body["allowDoubleBooking"] = true
            try {
                API.send("PATCH", "/provider-homestays/${stay.id}/visits/${visit.id}", body = body)
                Toast.show(if (reassigned && stay.status != "requested") L("Visit changed. The family has been told.") else L("Visit changed."))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is APIError && e.code == "visit_conflict") {
                    conflict = true
                    overlapOk.value = false
                } else {
                    form.check(listOf("date" to StaysWords.homeError(e, fallback = "Something went wrong. Try again.")))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Move or give to someone else"),
        subtitle = "${StaysWords.homeVisitKind(visit.kind)} · ${StaysDay.short(visit.dayKey)} · ${StaysHomeVisitSheet.timeText(visit, zone = zone)}",
        systemImage = "clipboard-list",
        accent = ModalAccent.aqua,
        isDirty = moved || reassigned,
        form = form,
        primaryTitle = if (conflict) L("Save anyway") else L("Save"),
        isWorking = working,
        primaryIcon = "check",
        onPrimary = { submit() },
    ) {
        DayField(label = L("Day"), day = stateOf({ date }, { date = it; reset() }), key = "date", form = form)
        TimeField(label = L("Time"), value = stateOf({ startTime }, { startTime = it; reset() }), from = "05:00", to = "23:45")
        if (!overnight) {
            MenuField(
                label = L("Length"),
                options = (listOf(15, 20, 30, 45, 60, 90, 120, 180, 240) + minutes).toSortedSet().map { it to StaysHomePlanForm.minutesText(it) },
                selection = stateOf({ minutes }, { minutes = it; reset() }),
            )
        }
        MenuField(
            label = L("Sitter"),
            options = listOf("" to L("Nobody yet")) + members.filter { it.canCare || it.id == visit.sitter?.memberId }.map { it.id to it.label },
            selection = stateOf({ memberId }, { memberId = it; reset() }),
        )
        if (reassigned) {
            PMTextField(L("Why (the family reads it)"), text = reason, prompt = L("e.g. Laura is sick"))
        }
        if (conflict) StaysHomeTeam.conflictNotice(confirmed = overlapOk)
    }

    LaunchedEffect(Unit) {
        if (date.isEmpty()) {
            date = visit.dayKey
            startTime = StaysDay.clockKey(visit.startsAt, zone = zone)
            minutes = bookedMinutes
            memberId = visit.sitter?.memberId ?: ""
        }
        if (members.isEmpty()) members = StaysHomeTeam.members()
    }
}
