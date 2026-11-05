package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

//
//  HomeVisitCard.kt (iOS HomeVisitCard.swift)
//
//  One home visit, read top to bottom on a phone in the street (web:
//  home-visits/visit-card.tsx): when, who, where (with the way there), whom
//  to call, how to get in, what to watch for with this pet, and one big
//  button for the next thing to do. Everything else is in "More".
//

class SvcVisitHandlers(
    val arrive: (SvcHomeVisitItem) -> Unit,
    val finish: (SvcHomeVisitItem) -> Unit,
    val onMyWay: (SvcHomeVisitItem) -> Unit,
    val report: (SvcHomeVisitItem) -> Unit,
    val reassign: (SvcHomeVisitItem) -> Unit,
    val check: (SvcHomeVisitItem) -> Unit,
    val reveal: (SvcHomeVisitItem) -> Unit,
    val emergency: (SvcHomeVisitItem) -> Unit,
    val open: (SvcHomeVisitItem) -> Unit,
    /** A home stay's visit: "leave", "no-access", "undo", "missed". */
    val stayMove: (SvcHomeVisitItem, String) -> Unit,
    val push: (ProviderRoute) -> Unit,
    /** A press the server refused: try it again, forget it, or settle the consent or deposit first. */
    val retry: (String) -> Unit = {},
    val discard: (String) -> Unit = {},
    val reviewConsents: (SvcOutboxEntry, SvcHomeVisitItem) -> Unit = { _, _ -> },
)

object SvcVault {
    val fields: List<String> = listOf("keys", "gateCode", "alarmCode", "wifiName", "wifiPassword", "parking", "houseRules", "hidingSpots", "notes")

    fun label(field: String): String = when (field) {
        "keys" -> L("Keys")
        "gateCode" -> L("Gate code")
        "alarmCode" -> L("Alarm code")
        "wifiName" -> L("Wi-Fi network")
        "wifiPassword" -> L("Wi-Fi password")
        "parking" -> L("Parking")
        "houseRules" -> L("House rules")
        "hidingSpots" -> L("Where the pets hide")
        else -> L("Anything else")
    }
}

/**
 * One home visit, read top to bottom on a phone in the street (web `VisitCard`): when,
 * who, where (with the way there), whom to call, how to get in, what to watch for with
 * this pet, and one big button for the next thing to do. Everything else is in "More".
 * A finished visit folds to its essentials.
 *
 * - `pending`: this phone's press not yet with the server.
 * - `failed`: this phone's presses the server refused.
 * - `tracker`: set on the walk whose route this phone records.
 * - `lastPosition`: what the phone answered at this card's last press.
 */
@Composable
fun SvcVisitCard(
    item: SvcHomeVisitItem,
    day: SvcHomeVisitsDay,
    now: Instant,
    highlight: Boolean,
    pressing: Boolean,
    handlers: SvcVisitHandlers,
    pending: SvcPending? = null,
    failed: List<SvcOutboxEntry> = emptyList(),
    tracker: SvcWalkTracker? = null,
    lastPosition: VisitPosition? = null,
    modifier: Modifier = Modifier,
) {
    val regular = LocalSizeClass.current == SizeClass.regular
    val open = rememberOpenURL()

    val viewer = day.viewer
    val isManager = viewer?.isManager ?: false
    val canAct = viewer?.canAct ?: false
    val lateAfter = day.settings?.lateAfterMinutes ?: 15
    val lateInfo = SvcHomeRules.late(item, now = now, lateAfter = lateAfter, isToday = day.isToday ?: false)
    val runningLong = SvcHomeRules.runningLong(item, now = now, lateAfter = lateAfter)
    val locationOn = day.settings?.locationStamps == true || day.settings?.walkRoutes == true
    /** A report the person can write from here (a home stay's report card is written in its visit's sheet). */
    val canWriteReport = (item.isStay || item.reportKind != null) && (item.phase == "in_progress" || item.phase == "done") && canAct
    val offersOnMyWay = item.actions?.onMyWay == true && item.onMyWay?.offered == true && item.onMyWay?.at == null && item.phase == "upcoming"

    /** Where the visit stands (web `visitChip`). */
    val chip = when (item.phase) {
        "missed" -> "missed"
        "done" -> "done"
        "in_progress" -> if (runningLong) "running_long" else "in_progress"
        else -> if (lateInfo.first) "late" else (if (item.phase == "on_my_way") "on_my_way" else "upcoming")
    }

    /** The state's colour: the card's edge and its pill (web `CHIP_TONE`). */
    @Composable
    fun tone(chip: String): Color = when (chip) {
        "on_my_way", "in_progress" -> ProviderState.active.color
        "running_long" -> ProviderState.waiting.color
        "late", "missed" -> ProviderState.problem.color
        "done" -> ProviderState.done.color
        else -> ProviderState.upcoming.color
    }

    /** The family was told only when the notice went out in PawMiaw; by WhatsApp the chat just opened. */
    val onMyWayLabel: String = run {
        val at = item.onMyWay?.at ?: return@run L("On the way")
        val time = SvcTime.clock(at, day.timeZone)
        if (item.onMyWay?.channel == "whatsapp") LF("On the way · WhatsApp opened %@", time) else LF("On the way · told at %@", time)
    }

    val stampLines: List<Pair<String, Triple<String, Boolean, Boolean>>> =
        listOf("arrive" to item.stamps?.arrive, "finish" to item.stamps?.finish).mapNotNull { (kind, stamp) ->
            SvcHomeRules.stampLine(kind, stamp, isManager = isManager)?.let { kind to it }
        }

    /** The quiet line under a card when this phone's press saved no position (never for a manager, who reads the stamps). */
    val quietReason: String? = if (isManager || stampLines.isNotEmpty()) null
    else lastPosition?.let { if (it.status == "ok") null else SvcHomeRules.noLocation(it.status) }

    // MARK: When, and where the visit stands

    @Composable
    fun Window() {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.kind == "overnight") Glyph("moon-star", 20.dp, tint = Theme.blue)
            Text(SvcHomeRules.window(item, day.timeZone), style = Theme.heading(21.6).copy(fontFeatureSettings = "tnum"), color = Theme.inkStrong, maxLines = 1, softWrap = false)
        }
    }

    @Composable
    fun Pills() {
        when (chip) {
            "late" -> {
                Chip(text = LF("Late · %d min", lateInfo.second), tone = Chip.Tone.custom(tone("late")), icon = "triangle-alert", percent = 26.0, weight = FontWeight.ExtraBold, border = 80.0, size = Chip.Size.large)
                if (item.phase == "on_my_way") Chip(text = onMyWayLabel, tone = Chip.Tone.custom(tone("on_my_way")), icon = "navigation", percent = 16.0, weight = FontWeight.ExtraBold, border = 55.0, size = Chip.Size.large)
            }
            "on_my_way" -> Chip(text = onMyWayLabel, tone = Chip.Tone.custom(tone("on_my_way")), icon = "navigation", percent = 16.0, weight = FontWeight.ExtraBold, border = 55.0, size = Chip.Size.large)
            "in_progress" -> Chip(text = L("Home visit · In progress"), tone = Chip.Tone.custom(tone(chip)), icon = "door-open", percent = 26.0, weight = FontWeight.ExtraBold, border = 80.0, size = Chip.Size.large)
            "running_long" -> Chip(text = L("Running long"), tone = Chip.Tone.custom(tone(chip)), icon = "hourglass", percent = 26.0, weight = FontWeight.ExtraBold, border = 80.0, size = Chip.Size.large)
            "done" -> Chip(text = L("Home visit · Done"), tone = Chip.Tone.custom(tone(chip)), icon = "check-check", percent = 16.0, weight = FontWeight.ExtraBold, border = 55.0, size = Chip.Size.large)
            "missed" -> Chip(text = L("Home visit · Missed"), tone = Chip.Tone.custom(tone(chip)), icon = "timer-off", percent = 26.0, weight = FontWeight.ExtraBold, border = 80.0, size = Chip.Size.large)
            else -> {}
        }
    }

    @Composable
    fun TopRow() {
        // The window at the left, where the visit stands at the right; under it when they don't fit (SwiftUI `ViewThatFits`).
        SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
            val unbounded = Constraints()
            val gap = 12.dp.roundToPx()
            val window = subcompose("window") { Window() }.map { it.measure(unbounded) }
            val pills = subcompose("pills") { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Pills() } }
                .map { it.measure(unbounded) }
            val windowWidth = window.maxOfOrNull { it.width } ?: 0
            val pillsWidth = pills.maxOfOrNull { it.width } ?: 0
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else windowWidth + gap + pillsWidth
            if (windowWidth + (if (pillsWidth > 0) gap + pillsWidth else 0) <= width) {
                val height = max(window.maxOfOrNull { it.height } ?: 0, pills.maxOfOrNull { it.height } ?: 0)
                layout(width, height) {
                    window.forEach { it.place(0, (height - it.height) / 2) }
                    pills.forEach { it.place(width - it.width, (height - it.height) / 2) }
                }
            } else {
                val stacked = subcompose("stacked") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Window()
                        FlowLayout(spacing = 6.dp) { Pills() }
                    }
                }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                val height = stacked.maxOfOrNull { it.height } ?: 0
                layout(width, height) { stacked.forEach { it.place(0, 0) } }
            }
        }
    }

    // MARK: Who

    @Composable
    fun WhoRow() {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PetStack(pets = item.pets.map { Triple(it.name, it.species, it.photoUrl) }, size = 48.dp, max = 3)
            Column(Modifier.weight(1f)) {
                Text(item.petNames, style = Theme.heading(20.8), color = Theme.inkStrong)
                Text(item.serviceText, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
            }
            AppMenu(
                label = {
                    Box(
                        Modifier.size(48.dp).border(1.5.dp, Theme.borderSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Glyph("ellipsis", 20.dp, tint = Theme.inkStrong) }
                },
                accessibilityLabel = LF("More for %@", item.petNames),
            ) {
                if (!item.isStay && item.actions?.onMyWay == true && !offersOnMyWay) {
                    item(L("On my way"), icon = "navigation") { handlers.onMyWay(item) }
                }
                if (canWriteReport) {
                    item(if (item.hasReport == true) L("Report") else L("Write report"), icon = "clipboard-pen") { handlers.report(item) }
                }
                if (item.actions?.reassign == true) {
                    item(L("Reassign"), icon = "user-round-cog") { handlers.reassign(item) }
                }
                if (isManager && (item.stamps != null || item.closed)) {
                    item(L("Check the location"), icon = "locate-fixed") { handlers.check(item) }
                }
                // A home stay's visit opens its own sheet: the checklist, the report card and every move.
                item(L("Open visit"), icon = "external-link") { handlers.open(item) }
                val stay = item.stayId
                if (item.isStay && stay != null) {
                    item(L("Open the home stay"), icon = "house") { handlers.push(ProviderRoute.stay(stay)) }
                }
            }
        }
    }

    // MARK: Where, and the way there

    /**
     * The device's own maps first, then the others the server gave (iOS opens Apple Maps first;
     * an Android phone has Google Maps, so it leads here and Apple Maps, a web page here, comes last).
     */
    fun mapLinks(address: SvcHomeAddress): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val google = address.maps?.google
        val text = (address.formatted ?: address.line).nonEmpty
        if (google != null) out.add(L("Open in Google Maps") to google)
        else ContactLinks.maps(text)?.let { out.add(L("Open in Google Maps") to it) }
        address.maps?.waze?.let { out.add(L("Open in Waze") to it) }
        address.maps?.apple?.let { out.add(L("Open in Apple Maps") to it) }
        return out
    }

    @Composable
    fun AddressBlock() {
        val address = item.address
        if (address != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Glyph("map-pin", 20.dp, tint = Theme.blue)
                    Text(
                        buildAnnotatedString {
                            address.label.nonEmpty?.let { label -> withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("$label · ") } }
                            append(address.line.nonEmpty ?: address.formatted ?: "")
                        },
                        style = Theme.text(16.3, FontWeight.Bold),
                        color = Theme.inkStrong,
                    )
                }
                address.reference.nonEmpty?.let { reference ->
                    Text(reference, style = Theme.text(14), color = Theme.inkStrong, modifier = Modifier.padding(start = 28.dp))
                }
                if (address.shared == false) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.wash(Theme.mango, 18.0), RoundedCornerShape(Theme.chipRadius))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Glyph("triangle-alert", 14.dp, tint = Theme.inkStrong, modifier = Modifier.padding(top = 1.dp))
                        Text(L("This address is no longer shared. This is how it was when the visit was booked."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkStrong)
                    }
                }
                val links = mapLinks(address)
                val primary = links.firstOrNull()
                if (primary != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PMButton(L("Directions"), systemImage = "navigation", variant = ButtonVariant.outline, fullWidth = true, modifier = Modifier.weight(1f)) { svcOpen(primary.second) }
                        if (links.size > 1) {
                            AppMenu(
                                label = {
                                    Box(
                                        Modifier
                                            .size(48.dp)
                                            .background(Theme.surface, CircleShape)
                                            .border(2.dp, Theme.blue, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Glyph("chevron-down", 20.dp, tint = Theme.blue) }
                                },
                                accessibilityLabel = L("Other map apps"),
                            ) {
                                links.drop(1).forEach { (title, url) ->
                                    item(title, icon = "map-pin") { svcOpen(url) }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 4.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("map-pin-off", 16.dp, tint = Theme.inkSoft)
                Text(L("No address yet"), style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
            }
        }
    }

    // MARK: Whom to call at the door

    @Composable
    fun ClientRow() {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Glyph("user-round", 20.dp, tint = Theme.inkSoft)
                Column {
                    Text(item.client.name, style = Theme.text(15.7, FontWeight.Bold), color = Theme.inkStrong)
                    item.client.phone.nonEmpty?.let { phone ->
                        Text(phone, style = Theme.text(12, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"), color = Theme.inkSoft)
                    }
                }
            }
            ContactLinks.phone(item.client.phone)?.let { tel ->
                val label = LF("Call %@", item.client.shortName)
                PMButton(L("Call"), systemImage = "phone", variant = ButtonVariant.outline, modifier = Modifier.semantics { contentDescription = label }) { open(tel) }
            }
        }
    }

    // MARK: How to get in (the notes anyone assigned reads; the codes only through the audited reveal)

    @Composable
    fun AccessBlock() {
        val access = item.access ?: return
        if (access.notes.nonEmpty == null && access.hasVault != true) return
        val shape = RoundedCornerShape(Theme.boxRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.aqua, 9.0), shape)
                .border(1.5.dp, Theme.mix(Theme.aqua, 55.0, Theme.borderSoft), shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("key-round", 16.dp, tint = Theme.blue)
                Text(L("Arrival & access"), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong)
            }
            access.notes.nonEmpty?.let { notes -> Text(notes, style = Theme.text(15.2), color = Theme.inkStrong) }
            if (access.hasVault == true) {
                FlowLayout(spacing = 6.dp) {
                    SvcVault.fields.filter { (access.fields ?: emptyList()).contains(it) }.forEach { field ->
                        Row(
                            Modifier
                                .background(Theme.surface, CircleShape)
                                .border(1.dp, Theme.borderSoft, RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("lock", 12.dp, tint = Theme.inkSoft)
                            Text(SvcVault.label(field), style = brandText(11.2, FontWeight.Bold), color = Theme.inkStrong)
                        }
                    }
                }
                when {
                    access.canReveal == true ->
                        PMButton(L("Show access details"), systemImage = "eye", variant = ButtonVariant.outline, fullWidth = true) { handlers.reveal(item) }
                    access.reason == "not_assigned" ->
                        Text(L("Only the person assigned sees them."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                    access.reason == "window_closed" -> {
                        Text(L("They open from 30 minutes before the visit until it ends."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                        if (access.canEmergency == true) {
                            PMButton(L("Emergency opening"), systemImage = "triangle-alert", variant = ButtonVariant.destructive, fullWidth = true) { handlers.emergency(item) }
                        }
                    }
                    access.reason == "family_off" ->
                        Text(L("The family hasn’t shared its access details with your business."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
        }
    }

    // MARK: What to watch for with this pet

    /** "Allergy: chicken, beef" — or the chart's own words when they already say it. */
    fun allergyLine(allergies: List<String>): String {
        val list = allergies.joinToString(", ")
        val saysIt = allergies.all { val word = it.lowercase().trim(); word.startsWith("alergi") || word.startsWith("allerg") }
        return if (saysIt) list else LF("Allergy: %@", list)
    }

    @Composable
    fun HandlingBlock() {
        val handling = item.pets.filter { !(it.handlingTags ?: emptyList()).isEmpty() || it.careNotes.nonEmpty != null || !(it.allergies ?: emptyList()).isEmpty() }
        if (handling.isEmpty()) return
        val shape = RoundedCornerShape(Theme.boxRadius)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(Theme.mango, 13.0), shape)
                .border(1.5.dp, Theme.mix(Theme.mango, 70.0, Theme.borderSoft), shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("shield-alert", 16.dp, tint = Theme.inkStrong)
                Text(L("Handle with care"), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong)
            }
            handling.forEach { pet ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.pets.size > 1) Text(pet.name, style = Theme.text(12, FontWeight.ExtraBold), color = Theme.inkStrong)
                    // What they must not be given comes first: a treat at the door is the usual slip.
                    val allergies = pet.allergies
                    if (!allergies.isNullOrEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                            Glyph("triangle-alert", 16.dp, tint = Theme.destructive, modifier = Modifier.padding(top = 1.dp))
                            Text(allergyLine(allergies), style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong)
                        }
                    }
                    SvcHandlingChips(tags = pet.handlingTags ?: emptyList(), large = true)
                    pet.careNotes.nonEmpty?.let { notes -> Text(notes, style = Theme.text(14), color = Theme.inkStrong) }
                }
            }
        }
    }

    // MARK: The team's side: who has it, and (owners and admins) how it went. Never a coordinate.

    @Composable
    fun TeamPills() {
        val seesAll = viewer?.seesAll ?: false
        val short = isManager && item.shortVisit == true
        val billing = SvcBilling.label(item.billing)
        if (!((seesAll && !item.closed) || short || stampLines.isNotEmpty() || billing != null || (seesAll && item.closed && item.assigned != null))) return
        FlowLayout(spacing = 6.dp) {
            if (seesAll) {
                Chip(text = item.assigned?.let { it.name ?: "—" } ?: L("Unassigned"), tone = Chip.Tone.custom(Theme.lavender), icon = "user-round", percent = 16.0, weight = FontWeight.ExtraBold, border = 55.0, size = Chip.Size.large)
            }
            if (billing != null) {
                Chip(text = billing, tone = Chip.Tone.custom(SvcBilling.state(item.billing).color), icon = "receipt", percent = 16.0, weight = FontWeight.ExtraBold, border = 55.0, size = Chip.Size.large)
            }
            if (short) {
                val booked = item.bookedMinutes
                Chip(
                    text = if (booked != null && item.arrivedAt != null && item.finishedAt != null) LF("Short visit · %d of %d min", SvcTime.minutesBetween(item.arrivedAt, item.finishedAt), booked)
                    else L("Short visit"),
                    tone = Chip.Tone.custom(Theme.mango), icon = "timer-off", percent = 26.0, weight = FontWeight.ExtraBold, border = 80.0, size = Chip.Size.large,
                )
            }
            stampLines.forEach { (_, line) ->
                val (text, warn, muted) = line
                Chip(
                    text = text, tone = Chip.Tone.custom(SvcHomeRules.stampTone(warn = warn, muted = muted)), icon = "locate-fixed",
                    percent = if (warn) 26.0 else 16.0, weight = FontWeight.ExtraBold, border = if (warn) 80.0 else 55.0, size = Chip.Size.large,
                )
            }
        }
    }

    // MARK: One next thing to do

    @Composable
    fun Actions() {
        if (item.actions?.arrive == true && failed.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PMButton(if (item.status == "missed") L("Arrived after all") else L("I’ve arrived"), systemImage = "door-open", fullWidth = true, isWorking = pressing) { handlers.arrive(item) }
                if (locationOn) {
                    Text(L("Your location is saved when you arrive and finish. Only owners and admins see it."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
        }
        if (item.actions?.finish == true && failed.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.isStay) {
                    PMButton(L("Leave"), systemImage = "log-out", fullWidth = true, isWorking = pressing) { handlers.finish(item) }
                } else if (SvcHomeRules.finishesHere(item.serviceCategory)) {
                    if (canWriteReport && item.hasReport != true) {
                        PMButton(L("Write report"), systemImage = "clipboard-pen", variant = ButtonVariant.outline, fullWidth = true) { handlers.report(item) }
                    }
                    PMButton(L("Finish"), systemImage = "badge-check", fullWidth = true, isWorking = pressing) { handlers.finish(item) }
                } else {
                    PMButton(L("Finish on the visit"), systemImage = "external-link", fullWidth = true, isWorking = false) { handlers.open(item) }
                    Text(L("A vet visit is finished on its page, with its note."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                }
            }
        }
        if (!canAct && !item.closed && !item.isStay && day.isToday == true) {
            Text(L("Your role can’t record arrivals."), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
        }
        if (item.phase == "done" && canWriteReport && item.hasReport != true) {
            PMButton(L("Write report"), systemImage = "clipboard-pen", variant = ButtonVariant.outline, fullWidth = true) { handlers.report(item) }
        }
        if (offersOnMyWay && failed.isEmpty()) {
            PMButton(L("On my way"), systemImage = "message-circle", variant = ButtonVariant.outline, fullWidth = true) { handlers.onMyWay(item) }
        }
    }

    val shape = RoundedCornerShape(Theme.cardRadius)
    val inset = if (regular) 20.dp else 16.dp
    val stripe = tone(chip)
    val edge = if (highlight) Theme.mix(Theme.blue, 55.0, Theme.borderSoft) else Theme.borderSoft
    Column(
        modifier
            .fillMaxWidth()
            // The card's shadow, gone once the visit is over (web: `closed && 'shadow-none'`).
            .then(if (!item.closed) Modifier.cardShadow(shape) else Modifier)
            .clip(shape)
            .background(Theme.surface)
            .drawBehind { drawRect(stripe, size = Size(6.dp.toPx(), size.height)) }
            .border(1.5.dp, edge, shape)
            .padding(start = inset + 6.dp, end = inset, top = inset, bottom = inset),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopRow()
        WhoRow()
        if (item.closed) {
            Text(
                listOfNotNull(
                    item.client.name,
                    item.arrivedAt?.let { LF("Arrived at %@", SvcTime.clock(it, day.timeZone)) },
                    item.finishedAt?.let { LF("Finished at %@", SvcTime.clock(it, day.timeZone)) },
                ).joinToString(" · "),
                style = Theme.text(14, FontWeight.SemiBold),
                color = Theme.inkSoft,
            )
        } else {
            AddressBlock()
            ClientRow()
            AccessBlock()
            HandlingBlock()
            item.instructions.nonEmpty?.let { instructions ->
                // A home stay carries the family's house rules; an appointment, the note written when it was booked.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("notebook-text", 14.dp, tint = Theme.inkSoft)
                        Eyebrow(if (item.isStay) L("House rules") else L("Visit notes"))
                    }
                    Text(instructions, style = Theme.text(15.2), color = Theme.inkStrong)
                }
            }
            val checklist = item.checklist
            if (checklist != null && checklist.total > 0 && item.phase != "in_progress") {
                Text(LF("Checklist · %d of %d", checklist.done, checklist.total), style = Theme.text(14, FontWeight.Bold), color = Theme.inkSoft)
            }
        }
        TeamPills()
        if (item.phase == "in_progress") {
            SvcVisitTimer(item = item, timeZone = day.timeZone)
            tracker?.let { SvcRouteRecording(tracker = it) }
        }
        pending?.let { SvcPendingLine(pending = it) }
        failed.forEach { entry ->
            SvcOutboxRow(
                entry = entry,
                onRetry = { handlers.retry(entry.id) },
                onDiscard = { handlers.discard(entry.id) },
                onOpenVisit = { handlers.open(item) },
                onReviewConsents = if (entry.kind == "arrive" || entry.kind == "stay_arrive") ({ handlers.reviewConsents(entry, item) }) else null,
            )
        }
        Actions()
        quietReason?.let { Text(it, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft) }
    }
}

/**
 * A visit under way (web `VisitInProgress`): how long the person has been there against
 * what was booked, the checklist's progress on a stay visit, and whether the report is in.
 */
@Composable
private fun SvcVisitTimer(item: SvcHomeVisitItem, timeZone: String?) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    val elapsed = SvcTime.minutesBetween(item.arrivedAt, DateText.iso(now))
    val booked = item.bookedMinutes
    val over = booked?.let { elapsed > it } ?: false
    val tone = if (over) Theme.mango else Theme.blue
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.wash(tone, 12.0), shape)
                .border(1.5.dp, Theme.mix(tone, 60.0, Theme.borderSoft), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow(L("Time at the visit"))
                    Text(SvcHomeRules.timer(item.arrivedAt, now = now), style = Theme.heading(33.6).copy(fontFeatureSettings = "tnum"), color = Theme.inkStrong)
                }
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        booked?.let { LF("%d of %d min", elapsed, it) } ?: LF("%d min", elapsed),
                        style = Theme.text(14, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
                        color = Theme.inkStrong,
                    )
                    item.arrivedAt?.let { arrived ->
                        Text(LF("Arrived at %@", SvcTime.clock(arrived, timeZone)), style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft)
                    }
                }
            }
            if (booked != null && booked > 0) {
                val share = min(1f, elapsed.toFloat() / booked.toFloat())
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Theme.wash(Theme.inkStrong, 12.0), RoundedCornerShape(50)),
                ) {
                    Box(Modifier.fillMaxWidth(share).height(8.dp).background(tone, RoundedCornerShape(50)))
                }
                Text(
                    (if (over) L("Over the booked time") + " · " else "") + LF("Booked %d min", booked),
                    style = Theme.text(12, FontWeight.Bold),
                    color = if (over) Theme.inkStrong else Theme.inkSoft,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        val checklist = item.checklist
        if (checklist != null && checklist.total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("list-checks", 16.dp, tint = Theme.blue)
                Text(LF("Checklist · %d of %d", checklist.done, checklist.total), style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong)
            }
        }
        if (item.hasReport == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("clipboard-check", 16.dp, tint = ProviderState.done.color)
                Text(L("Report"), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkSoft)
            }
        }
    }
}
