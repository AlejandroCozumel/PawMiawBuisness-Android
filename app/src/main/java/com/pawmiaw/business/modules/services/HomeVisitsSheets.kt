package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import com.pawmiaw.business.stays.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.Locale

//
//  HomeVisitsSheets.kt (iOS HomeVisitsSheets.swift)
//
//  Domicilio's sheets and its settings (web: home-visits/reassign-sheet.tsx,
//  visit-check.tsx, addresses/vault-sheets.tsx, stays/home-stay-visit-sheet.tsx
//  for a home stay's visit — leave, couldn't get in, missed — the day moved
//  to someone else, and home-visits-settings.tsx).
//

// MARK: - Reassign one visit

/**
 * Give one visit to someone else (web `ReassignSheet`): whose visit it is, the people
 * who can take it; a clash is said and booked only on purpose.
 */
@Composable
fun SvcReassignSheet(item: SvcHomeVisitItem, members: List<SvcMember>, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    var memberId by remember { mutableStateOf("") }
    var clash by remember { mutableStateOf<String?>(null) }
    val anyway = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    /** People who work visits (or look after stays), other than who has it now. */
    val candidates = members.filter { it.can(if (item.isStay) Permission.staysCare else Permission.appointmentsComplete) && it.id != item.assigned?.id }

    fun submit() {
        if (memberId.isEmpty()) { Toast.error(L("Choose a person")); return }
        if (clash != null && !anyway.value) { Toast.error(L("Book it anyway — I know they overlap")); return }
        working = true
        scope.launch {
            try {
                val stay = item.stayId
                val visit = item.stayVisitId
                val id = item.appointmentId
                if (item.isStay && stay != null && visit != null) {
                    val body = mutableMapOf<String, Any?>("memberId" to memberId)
                    if (anyway.value) body["allowDoubleBooking"] = true
                    SvcAPI.send<SvcIgnored>("PATCH", "/provider-homestays/$stay/visits/$visit", body = body)
                } else if (id != null) {
                    val body = mutableMapOf<String, Any?>("assignedOrganizationMemberId" to memberId)
                    if (anyway.value) body["allowDoubleBooking"] = true
                    SvcAPI.send<SvcIgnored>("PATCH", "/provider-appointments/$id", body = body)
                }
                Toast.show(LF("Visit reassigned to %@.", members.firstOrNull { it.id == memberId }?.shownName ?: "—"))
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (svcCode(e) == "visit_conflict") {
                    clash = svcMessage(e)
                    anyway.value = false
                } else {
                    Toast.error(svcMessage(e, codes = SvcRefusals.homeVisits))
                }
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Reassign"),
        subtitle = L("Give this visit to"),
        systemImage = "user-round-cog",
        accent = ModalAccent.lavender,
        isDirty = memberId.isNotEmpty(),
        primaryTitle = L("Reassign"),
        isWorking = working,
        primaryIcon = "user-round-cog",
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        ContextPills(client = item.client.name, pets = item.pets.map { it.name })
        if (candidates.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 4.dp)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(L("Nobody else can take this visit."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft, textAlign = TextAlign.Center)
            }
        } else {
            ChoiceCards(
                options = candidates.map { Triple(it.id, it.shownName, null as String?) },
                selection = stateOf({ memberId }) {
                    memberId = it
                    clash = null
                    anyway.value = false
                },
            )
        }
        clash?.let { text ->
            NoticeCard(tone = NoticeCard.Tone.warning, title = L("Double booking"), message = text)
            ToggleRow(title = L("Book it anyway — I know they overlap"), isOn = anyway)
        }
    }
}

// MARK: - Where it was recorded (owners and admins)

/**
 * Where a visit's arrival and finish were recorded (web `VisitCheckSheet`), for owners
 * and admins: the distance from the address, else the point, else why there is none.
 */
@Composable
fun SvcVisitCheckSheet(item: SvcHomeVisitItem, timeZone: String?, locationOn: Boolean, modifier: Modifier = Modifier) {
    var check by remember { mutableStateOf<SvcHomeVisitCheck?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            val query: Map<String, String?> = if (item.isStay) mapOf("stayVisitId" to item.stayVisitId) else mapOf("appointmentId" to item.appointmentId)
            check = API.get("/provider-home-visits/check", query)
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = L("Where this visit was recorded couldn’t be loaded.")
        }
    }

    @Composable
    fun StampBlock(title: String, kind: String, stamp: SvcStamp?) {
        val shape = RoundedCornerShape(Theme.boxRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.5.dp, Theme.borderSoft, shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Eyebrow(title)
            val line = stamp?.let {
                SvcHomeRules.stampLine(kind, SvcStampSummary(status = it.status, recordedAt = it.recordedAt, distanceM = it.distanceM, far = it.far), isManager = true)
            }
            if (stamp != null && line != null) {
                val (text, warn, muted) = line
                Chip(
                    text = text,
                    tone = Chip.Tone.custom(SvcHomeRules.stampTone(warn = warn, muted = muted)),
                    icon = "locate-fixed",
                    percent = if (warn) 26.0 else 16.0,
                    weight = FontWeight.ExtraBold,
                    border = if (warn) 80.0 else 55.0,
                    size = Chip.Size.large,
                )
                Text(
                    stamp.recordedByName?.let { LF("Recorded by %@ at %@", it, SvcTime.clock(stamp.recordedAt, timeZone)) } ?: LF("Recorded at %@", SvcTime.clock(stamp.recordedAt, timeZone)),
                    style = Theme.text(12, FontWeight.SemiBold),
                    color = Theme.inkSoft,
                )
                val lat = stamp.latitude
                val lng = stamp.longitude
                if (stamp.status == "ok" && lat != null && lng != null) {
                    Text(
                        String.format(Locale.US, "%.6f, %.6f", lat, lng),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontFeatureSettings = "tnum"),
                        color = Theme.inkStrong,
                    )
                    stamp.accuracyM?.let { accuracy ->
                        Text(LF("Accuracy ±%d m", Math.round(accuracy).toInt()), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                    }
                    stamp.mapsUrl?.let { maps ->
                        PMButton(L("Open in maps"), systemImage = "external-link", variant = ButtonVariant.outline, size = ButtonSize.modal, fullWidth = true) { svcOpen(maps) }
                    }
                }
            } else {
                Text(L("Nothing recorded"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
            }
        }
    }

    ProviderModal(
        title = L("Where it was recorded"),
        subtitle = "${item.petNames} · ${item.serviceText}",
        systemImage = "locate-fixed",
        accent = ModalAccent.lavender,
        cancelTitle = L("Close"),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("shield-check", 14.dp, tint = Theme.blue)
            Text(L("Only owners and admins see this."), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
        }
        val loaded = check
        if (loaded != null) {
            val booked = loaded.bookedMinutes
            val actual = loaded.actualMinutes
            if (booked != null && actual != null) {
                val short = loaded.shortVisit == true
                Chip(
                    text = if (short) LF("Short visit · %d of %d min", actual, booked) else LF("Lasted %d of %d min", actual, booked),
                    tone = Chip.Tone.custom(if (short) Theme.mango else ProviderState.done.color),
                    icon = if (short) "timer-off" else null,
                    percent = if (short) 26.0 else 16.0,
                    weight = FontWeight.ExtraBold,
                    border = if (short) 80.0 else 55.0,
                    size = Chip.Size.large,
                )
            }
            if (!locationOn && loaded.stamps?.arrive == null && loaded.stamps?.finish == null) {
                // Nothing was ever saved for this visit and the business records none: said, not two empty boxes.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 4.dp)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Glyph("locate-fixed", 16.dp, tint = Theme.inkSoft, modifier = Modifier.padding(top = 1.dp))
                    Text(L("This business doesn’t record location on home visits. It is turned on in Settings."), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            } else {
                StampBlock(L("Arrival"), "arrive", loaded.stamps?.arrive)
                StampBlock(L("Departure"), "finish", loaded.stamps?.finish)
                if (loaded.address?.hasCoordinates == false) {
                    Text(L("The address has no map point, so the distance can’t be measured."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
        } else if (failed != null) {
            SvcLoadError(message = L("Where this visit was recorded couldn’t be loaded."), retry = { load() })
        } else {
            LoadingState()
        }
    }
    LaunchedEffect(Unit) { load() }
}

// MARK: - The access details: an audited reveal, five minutes on screen

@Composable
fun SvcRevealConfirmSheet(item: SvcHomeVisitItem, emergency: Boolean, modifier: Modifier = Modifier, onRevealed: (SvcVaultReveal) -> Unit) {
    ConfirmSheet(
        title = if (emergency) L("Emergency opening") else L("Show access details?"),
        message = (if (emergency) L("There is no visit now. Open them only if something is wrong, and say why: the family is told right away, with your words.")
        else L("Opening these is recorded.")) + " " + L("They stay on screen for 5 minutes at most, then hide again."),
        confirmTitle = if (emergency) L("Open in an emergency") else L("Show them"),
        destructive = emergency,
        askReason = emergency,
        reasonRequired = emergency,
        reasonLabel = L("What happened?"),
        // Web RevealConfirmSheet: teal with the key, coral with the triangle in an emergency.
        accent = if (emergency) ModalAccent.coral else ModalAccent.aqua,
        icon = if (emergency) "triangle-alert" else "key-round",
        confirmIcon = "eye",
        modifier = modifier,
    ) { reason ->
        val stay = item.stayId
        val path = if (item.isStay && stay != null) "/provider-homestays/$stay/vault/reveal"
        else "/provider-appointments/${item.appointmentId ?: ""}/access/reveal"
        val body: Map<String, Any?> = if (emergency) mapOf("emergencyReason" to reason) else emptyMap()
        val result: SvcVaultReveal = API.post(path, body)
        onRevealed(result)
    }
}

@Composable
fun SvcVaultSheet(item: SvcHomeVisitItem, reveal: SvcVaultReveal, modifier: Modifier = Modifier) {
    val dismiss = LocalDismiss.current
    ProviderModal(
        title = L("Arrival & access"),
        subtitle = item.address?.label.nonEmpty ?: item.address?.line,
        systemImage = "key-round",
        accent = ModalAccent.aqua,
        cancelTitle = L("Hide"),
        modifier = modifier,
    ) {
        val fields = SvcVault.fields.filter { reveal.vault[it].nonEmpty != null }
        Column(Modifier.fillMaxWidth()) {
            fields.forEachIndexed { index, field ->
                if (index > 0) RowDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(SvcVault.label(field), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                    SelectionContainer {
                        Text(reveal.vault[field] ?: "", style = Theme.text(17, FontWeight.Bold), color = Theme.inkStrong)
                    }
                }
            }
        }
        Text(L("They stay on screen for 5 minutes at most, then hide again."), style = Theme.text(12), color = Theme.inkSoft)
    }
    LaunchedEffect(Unit) {
        delay(300_000)
        dismiss()
    }
}

// MARK: - A home stay's visit: leave, couldn't get in, missed

/**
 * Domicilio's card (and Home) open a home stay's visit move by its card: the one move sheet
 * a stay's visit uses (Stays' StaysVisitMoveSheet), with the card's window and checklist.
 *
 * - `move`: "leave", "no-access" or "missed".
 * - `locate`: leaving from Domicilio: where the phone was, as its Finish records it.
 */
@Composable
fun SvcStayMoveSheet(
    item: SvcHomeVisitItem,
    move: String,
    timeZone: String?,
    locate: (suspend () -> VisitPosition)? = null,
    modifier: Modifier = Modifier,
) {
    StaysVisitMoveSheet(
        stayId = item.stayId ?: "",
        visitId = item.stayVisitId ?: "",
        move = move,
        `when` = "${item.petNames} · ${SvcHomeRules.window(item, timeZone)}",
        checklist = item.checklist?.let { it.done to it.total },
        locate = locate,
        modifier = modifier,
    )
}

// MARK: - Move someone's day

/** Someone is off (web `ReassignDaySheet`): their visits that day go to another person, or to whoever is free. */
@Composable
fun SvcMoveDaySheet(
    date: String,
    people: List<SvcHomePerson>,
    members: List<SvcMember>,
    initialFrom: String?,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val from = remember { mutableStateOf("") }
    val to = remember { mutableStateOf("free") }
    var working by remember { mutableStateOf(false) }
    val form = remember { FormState() }

    val fromOptions: List<Pair<String, String>> = listOf("" to L("Choose a person")) + people.mapNotNull { person -> person.memberId?.let { it to (person.name ?: "—") } }
    val toOptions: List<Pair<String, String>> = listOf("free" to L("Whoever is free")) +
        members.filter { it.can(Permission.appointmentsComplete) && it.id != from.value }.map { it.id to it.shownName }

    fun submit() {
        if (!form.check(listOf("from" to (if (from.value.isEmpty()) L("Choose a person") else null)))) return
        working = true
        scope.launch {
            try {
                val result: JsonObject = API.post(
                    "/provider-appointments/reassign",
                    mapOf("date" to date, "fromMemberId" to from.value, "toMemberId" to (if (to.value == "free") null else to.value)),
                )
                val moved = result["moved"].int ?: 0
                val skipped = result["skipped"].int ?: 0
                val off = result["off"].int ?: 0
                when {
                    off > 0 -> Toast.show(LF("%d visits moved; %d stay put because the other person is off then (a day off or their own hours).", moved, off))
                    skipped > 0 -> Toast.show(LF("%d visits moved; %d stay put (under way, or the other person is busy then).", moved, skipped))
                    else -> Toast.show(LF("%d visits moved.", moved))
                }
                announceDataChanged()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e, known = mapOf("fromMemberId" to "from"))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = L("Move someone’s day"),
        subtitle = L("Someone is off: their visits today go to another person, or to whoever is free."),
        systemImage = "users",
        accent = ModalAccent.aqua,
        isDirty = from.value.isNotEmpty(),
        form = form,
        primaryTitle = L("Move visits"),
        isWorking = working,
        cancelTitle = L("Back"),
        onPrimary = { submit() },
        modifier = modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Text(svcCapitalized(SvcTime.dayLong(date)), style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
        MenuField(label = L("From"), options = fromOptions, selection = from, key = "from", form = form)
        MenuField(label = L("To"), options = toOptions, selection = to)
    }
    LaunchedEffect(Unit) { if (initialFrom != null) from.value = initialFrom }
}

// MARK: - Settings

private val svcHomeCategoryOptions: List<Pair<String, String>> = listOf("vet" to "Vet", "groomer" to "Grooming", "trainer" to "Training", "walker" to "Walks", "sitter" to "Sitting", "other" to "Other")
private const val svcHomeStandardMessage = "Hi {client}, this is {business}: we’re on our way to see {pet}.[ We’ll be there in about {minutes} minutes.]"

/**
 * How home visits work here (web `HomeVisitsSettingsPanel`): what the family is told,
 * when a visit is late or short, and what is saved about where it happened.
 */
@Composable
fun SvcHomeVisitSettingsTab(canManage: Boolean, businessName: String, modifier: Modifier = Modifier) {
    val sizeClass = LocalSizeClass.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<SvcHomeVisitSettings?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val autoStarted = remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    val message = remember { mutableStateOf("") }
    val late = remember { mutableStateOf("") }
    val short = remember { mutableStateOf("") }
    val far = remember { mutableStateOf("") }
    val retention = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val form = remember { FormState() }

    fun number(text: String, range: IntRange): Int? {
        val value = text.trim().toIntOrNull() ?: return null
        return if (value in range) value else null
    }

    val current = saved
    val dirty = current != null && (
        autoStarted.value != (current.autoStartedNotice ?: true) || categories.toSet() != (current.onMyWayCategories ?: emptyList()).toSet() ||
            message.value.nonEmpty != current.onMyWayMessage.nonEmpty ||
            late.value != (current.lateAfterMinutes?.toString() ?: "") || short.value != (current.shortVisitPercent?.toString() ?: "") ||
            far.value != (current.farFromAddressMeters?.toString() ?: "") || retention.value != (current.locationRetentionDays?.toString() ?: "")
        )

    fun reset(settings: SvcHomeVisitSettings) {
        autoStarted.value = settings.autoStartedNotice ?: true
        categories = settings.onMyWayCategories ?: emptyList()
        message.value = settings.onMyWayMessage ?: ""
        late.value = settings.lateAfterMinutes?.toString() ?: ""
        short.value = settings.shortVisitPercent?.toString() ?: ""
        far.value = settings.farFromAddressMeters?.toString() ?: ""
        retention.value = settings.locationRetentionDays?.toString() ?: ""
    }

    suspend fun load() {
        try {
            val loaded: SvcHomeVisitSettings = API.get("/provider-home-visits/settings")
            saved = loaded
            reset(loaded)
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = L("The settings couldn’t be loaded.")
        }
    }

    fun save() {
        val lateValue = number(late.value, 5..120)
        val shortValue = number(short.value, 10..90)
        val farValue = number(far.value, 100..5000)
        val retentionValue = number(retention.value, 30..365)
        val ok = form.check(
            listOf(
                "late" to (if (lateValue == null) LF("From %d to %d", 5, 120) else null),
                "short" to (if (shortValue == null) LF("From %d to %d", 10, 90) else null),
                "far" to (if (farValue == null) LF("From %d to %d", 100, 5000) else null),
                "retention" to (if (retentionValue == null) LF("From %d to %d", 30, 365) else null),
            )
        )
        if (!ok) return
        working = true
        scope.launch {
            try {
                val result: SvcHomeVisitSettings = API.put(
                    "/provider-home-visits/settings",
                    mapOf(
                        "autoStartedNotice" to autoStarted.value,
                        "onMyWayCategories" to svcHomeCategoryOptions.map { it.first }.filter { categories.contains(it) },
                        "onMyWayMessage" to message.value.nonEmpty,
                        "lateAfterMinutes" to lateValue, "shortVisitPercent" to shortValue,
                        "farFromAddressMeters" to farValue, "locationRetentionDays" to retentionValue,
                    ),
                )
                saved = result
                reset(result)
                Toast.show(L("Settings saved."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                form.apply(e, known = mapOf("lateAfterMinutes" to "late", "shortVisitPercent" to "short", "farFromAddressMeters" to "far", "locationRetentionDays" to "retention"))
            } finally {
                working = false
            }
        }
    }

    suspend fun setLocation(key: String, on: Boolean, acknowledged: Boolean) {
        val body = mutableMapOf<String, Any?>(key to on)
        if (on && acknowledged) body["acknowledgeLocation"] = true
        val result: SvcHomeVisitSettings = API.put("/provider-home-visits/settings", body)
        saved = result
        Toast.show(if (on) (if (key == "locationStamps") L("Location turned on. Your team was told.") else L("Walk routes turned on. Your team was told.")) else L("Settings saved."))
        announceDataChanged()
    }

    /** A location switch: off at once; on only with the acknowledgement on file (asked for when it isn't). */
    fun locationBinding(key: String, value: Boolean): MutableState<Boolean> = stateOf({ value }) { on ->
        if (on && saved?.locationDisclosure?.acknowledgedAt == null) {
            confirming = key
            return@stateOf
        }
        scope.launch {
            try {
                setLocation(key, on = on, acknowledged = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(svcMessage(e, codes = SvcRefusals.homeVisits))
            }
        }
    }

    fun disclosure(days: Int): String =
        LF("When this is on, PawMiaw saves where the phone is at the moment someone presses Arrive and Finish on a home visit — not before, not after, and never in the background. During a walk with routes on, the path is recorded while the page stays open. Only owners and admins see locations; families only ever see the walk drawn as a line, without a map or an address. Locations are deleted after %d days. Tell your team before turning this on.", days)

    // The text in [brackets] goes only when "how long" is said; the preview says 10 minutes.
    val preview = (message.value.nonEmpty ?: L(svcHomeStandardMessage))
            .replace("[", "").replace("]", "")
            .replace("{pet}", "Rocky").replace("{client}", "Carlos")
            .replace("{business}", businessName.nonEmpty ?: L("your business")).replace("{minutes}", "10")

    /** "A visit is late after [15] minutes" (web `NumberRow`): the range said in red while it is out of it. */
    @Composable
    fun NumberRow(key: String, before: String, after: String, text: MutableState<String>, range: IntRange) {
        val valid = number(text.value, range) != null
        FlowLayout(spacing = 8.dp, modifier = Modifier.formScrollTarget(key, form)) {
            Text(before, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            Box(Modifier.width(80.dp).fieldChrome(invalid = form.error(key) != null || !valid), contentAlignment = Alignment.Center) {
                PlainTextField(
                    stateOf({ text.value }) { value ->
                        text.value = value.filter { it.isDigit() }.take(4)
                        form.clear(key)
                    },
                    "",
                    Modifier.fillMaxWidth(),
                    keyboard = KeyboardType.Number,
                    style = Theme.bodyFont.copy(color = Theme.inkStrong, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
                    enabled = canManage,
                )
            }
            Text(after, style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            if (!valid) Text(LF("From %d to %d", range.first, range.last), style = Theme.text(12, FontWeight.Bold), color = Theme.destructive)
        }
    }

    @Composable
    fun NoticesRule(modifier: Modifier = Modifier) {
        SettingsRule(icon = "bell-ring", tone = Theme.aqua, title = L("Notices"), hint = L("What the family hears about a visit to their home."), modifier = modifier) {
            Disabled(!canManage) {
                ToggleRow(title = L("Tell the family when a home visit starts"), subtitle = L("They get a notice in PawMiaw when you press “I’ve arrived”."), isOn = autoStarted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("Offer “On my way” for"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                FlowLayout(spacing = 6.dp) {
                    svcHomeCategoryOptions.forEach { (value, title) ->
                        val on = categories.contains(value)
                        Disabled(!canManage) {
                            FilterChip(title = L(title), isSelected = on, tone = Theme.blue, icon = if (on) "check" else null) {
                                categories = if (on) categories.filter { it != value } else categories + value
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("“On my way” message"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
                FieldChrome(multiline = true) {
                    PlainTextField(message, L(svcHomeStandardMessage), Modifier.weight(1f), lines = 3..8, enabled = canManage)
                }
                Text(L("Use {pet}, {client}, {business} and {minutes}. Text in [brackets] is sent only when you say how long."), style = Theme.text(12), color = Theme.inkSoft)
                if (canManage) {
                    SvcPlaceholderRow(
                        placeholders = listOf("{pet}", "{client}", "{business}", "{minutes}"),
                        onAdd = { message.value += it },
                        extra = if (message.value.nonEmpty != null) (L("Use the standard message") to { message.value = "" }) else null,
                    )
                }
                SvcMessagePreview(text = preview, standard = message.value.nonEmpty == null, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    @Composable
    fun LateRule() {
        SettingsRule(icon = "timer-off", tone = Theme.mango, title = L("Late and short visits"), hint = L("When the page flags a visit. Only owners and admins see short visits.")) {
            NumberRow("late", before = L("A visit is late after"), after = L("minutes"), text = late, range = 5..120)
            NumberRow("short", before = L("Flag a visit that lasted less than"), after = L("% of its booked time"), text = short, range = 10..90)
        }
    }

    @Composable
    fun LocationRule(settings: SvcHomeVisitSettings) {
        SettingsRule(icon = "locate-fixed", tone = Theme.lavender, title = L("Location"), hint = L("Off unless you turn it on. Read what it saves before you do.")) {
            Disabled(!canManage || working) {
                ToggleRow(title = L("Save the location at arrival and finish"), subtitle = L("Where the phone is at those two presses. Never in between."), isOn = locationBinding("locationStamps", settings.locationStamps ?: false))
            }
            NumberRow("far", before = L("“Far from the address” beyond"), after = L("meters"), text = far, range = 100..5000)
            Disabled(!canManage || working) {
                ToggleRow(
                    title = L("Record the route of walks"),
                    subtitle = L("The family sees it drawn as a line in the walk’s report. It may have gaps: the browser stops recording when the phone locks."),
                    isOn = locationBinding("walkRoutes", settings.walkRoutes ?: false),
                )
            }
            NumberRow("retention", before = L("Delete locations after"), after = L("days"), text = retention, range = 30..365)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surface, RoundedCornerShape(Theme.boxRadius))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(disclosure(settings.locationRetentionDays ?: 90), style = Theme.text(14), color = Theme.inkStrong)
                val acknowledged = settings.locationDisclosure
                val at = acknowledged?.acknowledgedAt
                if (acknowledged != null && at != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("shield-check", 14.dp, tint = Theme.blue)
                        Text(
                            acknowledged.acknowledgedByName?.let { LF("Confirmed by %@ on %@", it, DateText.date(at)) } ?: LF("Confirmed on %@", DateText.date(at)),
                            style = Theme.text(12, FontWeight.Bold),
                            color = Theme.inkSoft,
                        )
                    }
                }
            }
        }
    }

    val settings = saved
    if (settings != null) {
        FormScroll(form) {
            SettingsCard(
                icon = "house",
                tone = Theme.aqua,
                title = L("How home visits work"),
                hint = if (canManage) L("For the whole business: what the family is told, when a visit is late and what is saved about where it happened.") else L("Set by whoever manages the business. You can read it here."),
                note = if (canManage) null else L("Only someone who edits the workspace can change this."),
                modifier = modifier,
                content = {
                    if (sizeClass == SizeClass.regular) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            NoticesRule(Modifier.weight(1f))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                LateRule()
                                LocationRule(settings)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            NoticesRule()
                            LateRule()
                            LocationRule(settings)
                        }
                    }
                },
                footer = {
                    if (canManage) {
                        SaveBar(dirty = dirty, working = working, onDiscard = { reset(settings) }, onSave = { save() })
                    }
                },
            )
        }
        confirming?.let { key ->
            AppSheet(onDismissRequest = { confirming = null }) {
                SvcLocationDisclosureSheet(kind = key, days = settings.locationRetentionDays ?: 90) {
                    setLocation(key, on = true, acknowledged = true)
                }
            }
        }
    } else if (failed != null) {
        SvcLoadError(message = L("The settings couldn’t be loaded."), retry = { load() }, modifier = modifier)
    } else {
        LoadingState(modifier = modifier)
        LaunchedEffect(Unit) { load() }
    }
}

/**
 * Before location is turned on (web `LocationDisclosureSheet`): what it saves, who sees it
 * and for how long, and a box to tick — the team was told. Without the tick there is no "Turn on".
 */
@Composable
private fun SvcLocationDisclosureSheet(kind: String, days: Int, turnOn: suspend () -> Unit) {
    val dismiss = LocalDismiss.current
    val scope = rememberCoroutineScope()
    val told = remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    fun confirm() {
        working = true
        scope.launch {
            try {
                turnOn()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(svcMessage(e, codes = SvcRefusals.homeVisits))
            } finally {
                working = false
            }
        }
    }

    ProviderModal(
        title = if (kind == "walkRoutes") L("Turn on walk routes?") else L("Turn on location?"),
        subtitle = L("Your team is told in PawMiaw as soon as you turn it on."),
        systemImage = "locate-fixed",
        accent = ModalAccent.lavender,
        primaryTitle = L("Turn on"),
        isWorking = working,
        primaryIcon = "locate-fixed",
        primaryDisabled = !told.value,
        onPrimary = { confirm() },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium, PresentationDetent.large)),
    ) {
        Text(
            LF("When this is on, PawMiaw saves where the phone is at the moment someone presses Arrive and Finish on a home visit — not before, not after, and never in the background. During a walk with routes on, the path is recorded while the page stays open. Only owners and admins see locations; families only ever see the walk drawn as a line, without a map or an address. Locations are deleted after %d days. Tell your team before turning this on.", days),
            style = Theme.text(15.2),
            color = Theme.inkStrong,
        )
        // Web: a checkbox in a bordered row.
        StaysTickRow(title = L("I’ve told my team"), isOn = told)
    }
}

/** A plain string as a sheet's item. */
data class SvcIdentified(val id: String)
