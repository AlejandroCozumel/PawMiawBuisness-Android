package com.pawmiaw.business.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

//
//  HospitalAdmin.kt (iOS HospitalAdmin.swift)
//
//  The hospital page's other two tabs (web hospital-admin.tsx): who is on
//  call at this place, now and the next two weeks (after hours a family's
//  "voy en camino" reaches them), and the hospital's rules: how long a dose
//  stays on time, when it counts as missed, the daily fee and what families
//  read about visiting.
//

// MARK: - On call

@Composable
fun HospOnCallPanel(canManage: Boolean, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<HospOnCallList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<HospMember>>(emptyList()) }
    val memberId = remember { mutableStateOf("") }
    val startsAt = remember { mutableStateOf(hospOnCallDefaultStart()) }
    val endsAt = remember { mutableStateOf(hospOnCallDefaultEnd()) }
    val note = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HospOnCallShift?>(null) }
    val form = remember { FormState() }

    val zone = workspace.timeZone
    val locationId = workspace.selectedLocation?.id ?: workspace.memberLocations.firstOrNull()?.id
    val eligible = members.filter { member ->
        member.can(Permission.hospitalView) &&
            ((member.locationIds ?: emptyList()).isEmpty() || (locationId?.let { (member.locationIds ?: emptyList()).contains(it) } ?: false))
    }

    suspend fun load() {
        try {
            list = API.get("/provider-hospital/on-call", mapOf("locationId" to workspace.locationId))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (list == null) error = errorText(e)
        }
        if (canManage && members.isEmpty()) members = HospAPI.members()
    }

    fun add() {
        if (!form.check(listOf(
                "member" to if (memberId.value.isEmpty()) L("Choose who is on call.") else null,
                "until" to if (!endsAt.value.isAfter(startsAt.value)) L("The shift ends after it starts.") else null,
            ))) return
        val location = locationId
        if (location == null) {
            Toast.error(L("Choose a location first."))
            return
        }
        saving = true
        scope.launch {
            try {
                API.post<HospOnCallList>("/provider-hospital/on-call", mapOf(
                    "locationId" to location, "organizationMemberId" to memberId.value,
                    "startsAt" to DateText.iso(startsAt.value), "endsAt" to DateText.iso(endsAt.value), "note" to note.value.nonEmpty,
                ))
                Toast.show(L("On-call shift added."))
                note.value = ""
                memberId.value = ""
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(hospError(e, "Could not add the shift."))
            } finally {
                saving = false
            }
        }
    }

    fun range(shift: HospOnCallShift): String {
        val from = HospFmt.clock(shift.startsAt, zone)
        val to = HospFmt.clock(shift.endsAt, zone)
        val startDay = HospFmt.dayKey(shift.startsAt, zone)
        val endDay = HospFmt.dayKey(shift.endsAt, zone)
        val startLabel = HospFmt.day(startDay, zone = zone)
        val endLabel = HospFmt.day(endDay, zone = zone)
        if (startDay == endDay) return "$startLabel · $from–$to"
        return "$startLabel $from → $endLabel $to"
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionCard(
            L("On call"),
            subtitle = L("After hours, a family’s “on my way” reaches whoever is on call here."),
            icon = "phone-call",
            accent = Theme.lime,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // Right now: who a family's warning reaches.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HospCardGroup(title = L("On call now"), icon = "phone-call")
                    val loaded = list
                    val failed = error
                    if (loaded != null) {
                        val now = loaded.now ?: emptyList()
                        if (now.isEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .dashedBorder(1.5.dp, Theme.borderSoft, Theme.boxRadius, on = 5.dp, off = 4.dp)
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(40.dp).background(Theme.wash(Theme.lavender, 30.0), CircleShape), contentAlignment = Alignment.Center) {
                                    Glyph("moon", 20.dp, tint = Theme.inkStrong)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(L("Nobody is on call right now"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong)
                                    Text(L("After hours, warnings go to the care team at this location."), style = Theme.text(14), color = Theme.inkSoft)
                                }
                            }
                        } else {
                            now.forEach { shift ->
                                val shape = RoundedCornerShape(Theme.boxRadius)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Theme.wash(Theme.lime, 16.0), shape)
                                        .border(1.5.dp, Theme.mix(Theme.lime, 70.0, Theme.borderSoft), shape)
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(name = shift.member?.name, imageURL = shift.member?.image, size = 48.dp, tint = Theme.lime)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(shift.member?.name ?: L("Team member"), style = Theme.heading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Glyph("clock-3", 14.dp, tint = Theme.inkSoft)
                                            Text(LF("Until %@", HospFmt.dateTime(shift.endsAt, zone)), style = Theme.text(14), color = Theme.inkSoft)
                                        }
                                        shift.note.nonEmpty?.let { text ->
                                            Text(text, style = Theme.text(12).copy(fontStyle = FontStyle.Italic), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Box(Modifier.size(36.dp).background(Theme.wash(Theme.lime, 45.0), CircleShape), contentAlignment = Alignment.Center) {
                                        Glyph("phone-call", 16.dp, tint = Theme.inkStrong)
                                    }
                                }
                            }
                        }
                    } else if (failed != null) {
                        ErrorState(message = failed) { load() }
                    } else {
                        LoadingState()
                    }
                }

                // The next two weeks, one row a shift.
                val loaded = list
                if (loaded != null) {
                    val shifts = loaded.shifts ?: emptyList()
                    val nowIds = (loaded.now ?: emptyList()).map { it.id }.toSet()
                    StitchedDivider()
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HospCardGroup(title = L("Next two weeks"), icon = "calendar-clock") {
                            if (shifts.isNotEmpty()) {
                                Text(
                                    LP(shifts.size, "1 shift", "%d shifts"),
                                    Modifier.background(Theme.surfaceMuted, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = Theme.text(12, FontWeight.Bold).monospacedDigit(),
                                    color = Theme.inkStrong,
                                )
                            }
                        }
                        if (shifts.isEmpty()) {
                            EmptyState(
                                systemImage = "calendar-clock", title = L("No shifts yet."),
                                message = if (canManage) L("Add who answers after hours: a shift lasts up to 7 days.") else L("Whoever manages the hospital adds who answers after hours."),
                                accent = Theme.lime,
                            )
                        } else {
                            val shape = RoundedCornerShape(Theme.boxRadius)
                            Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, Theme.borderSoft, shape)) {
                                shifts.forEachIndexed { index, shift ->
                                    if (index > 0) RowDivider()
                                    HospShiftRow(
                                        shift = shift, current = nowIds.contains(shift.id), canManage = canManage,
                                        range = range(shift), multiLocation = workspace.multiLocation,
                                    ) { removing = shift }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (canManage) {
            SectionCard(
                L("Add a shift"),
                subtitle = L("At most 7 days a shift; two shifts of the same person can’t overlap."),
                icon = "calendar-plus",
                accent = Theme.aqua,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    FormScroll(form) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            MenuField(
                                label = L("Who"),
                                options = listOf("" to L("Choose a person")) + eligible.map { it.id to it.label },
                                selection = memberId, key = "member", form = form, required = true,
                            )
                            DateTimeField(label = L("From"), date = startsAt, zone = zone, required = true)
                            DateTimeField(label = L("Until"), date = endsAt, zone = zone, min = startsAt.value, key = "until", form = form, required = true)
                            PMTextField(L("Note"), text = note, prompt = L("e.g. Reachable by phone, 10 min away"), optional = true)
                        }
                    }
                    RowDivider()
                    PMButton(
                        L("Add shift"), systemImage = "plus", fullWidth = true, isWorking = saving,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) { add() }
                }
            }
        }
    }
    ReloadsOnScope { load() }
    removing?.let { shift ->
        AppSheet(onDismissRequest = { removing = null }) {
            ConfirmSheet(
                title = L("Remove this shift?"),
                message = LF(
                    "%@ stops being on call %@ → %@.",
                    shift.member?.name ?: L("Team member"), HospFmt.dateTime(shift.startsAt, zone), HospFmt.dateTime(shift.endsAt, zone),
                ),
                confirmTitle = L("Remove shift"),
                destructive = true,
            ) {
                try {
                    API.send("DELETE", "/provider-hospital/on-call/${shift.id}")
                    Toast.show(L("Shift removed."))
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw hospRefusal(e, "Could not remove the shift.")
                }
            }
        }
    }
}

@Composable
private fun HospShiftRow(
    shift: HospOnCallShift,
    current: Boolean,
    canManage: Boolean,
    range: String,
    multiLocation: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) Theme.wash(Theme.lime, 10.0) else Theme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = shift.member?.name, imageURL = shift.member?.image, size = 40.dp, tint = if (current) Theme.lime else Theme.aqua)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shift.member?.name ?: L("Team member"),
                    Modifier.weight(1f, fill = false),
                    style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (current) Chip(text = L("Now"), tone = Chip.Tone.custom(Theme.lime), dot = Theme.lime, percent = 22.0)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("clock-3", 14.dp, tint = Theme.inkSoft)
                Text(range, style = Theme.text(14).monospacedDigit(), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val extra = listOfNotNull(if (multiLocation) shift.locationName else null, shift.note).mapNotNull { it.nonEmpty }.joinToString(" · ")
            if (extra.isNotEmpty()) Text(extra, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (canManage) {
            IconCircleButton(systemImage = "trash-2", label = L("Remove shift"), tint = Theme.destructive) { onRemove() }
        }
    }
}

/** Today at 20:00 on the phone's calendar (iOS `Calendar.current`). */
private fun hospOnCallDefaultStart(): Instant =
    ZonedDateTime.now(ZoneId.systemDefault()).withHour(20).withMinute(0).withSecond(0).withNano(0).toInstant()

/** Tomorrow at 08:00 on the phone's calendar. */
private fun hospOnCallDefaultEnd(): Instant =
    ZonedDateTime.now(ZoneId.systemDefault()).plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0).toInstant()

// MARK: - Settings

@Composable
fun HospSettingsPanel(canEdit: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    var settings by remember { mutableStateOf<HospSettings?>(null) }
    var services by remember { mutableStateOf<List<HospCatalogItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val grace = remember { mutableStateOf("") }
    val missed = remember { mutableStateOf("") }
    val feeGrace = remember { mutableStateOf("") }
    val visiting = remember { mutableStateOf("") }
    val feeItemId = remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val form = remember { FormState() }

    fun dirty(): Boolean {
        val s = settings ?: return false
        return grace.value != "${s.lateGraceMinutes ?: 30}" || missed.value != "${s.missedAfterMinutes ?: 120}" || feeGrace.value != "${s.feeGraceMinutes ?: 60}" ||
            visiting.value != (s.visitingInfo ?: "") || feeItemId.value != (s.dailyFeeItemId ?: "")
    }

    fun fill(s: HospSettings) {
        grace.value = "${s.lateGraceMinutes ?: 30}"
        missed.value = "${s.missedAfterMinutes ?: 120}"
        feeGrace.value = "${s.feeGraceMinutes ?: 60}"
        visiting.value = s.visitingInfo ?: ""
        feeItemId.value = s.dailyFeeItemId ?: ""
    }

    suspend fun load() {
        try {
            val loaded: HospSettings = API.get("/provider-hospital/settings")
            val page: Page<HospCatalogItem>? = try {
                API.get("/provider-catalog", mapOf("limit" to "100", "offset" to "0", "itemType" to "service", "active" to "true"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            services = page?.items ?: emptyList()
            if (settings == null || !dirty()) fill(loaded)
            settings = loaded
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (settings == null) error = errorText(e)
        }
    }

    fun save() {
        val late = grace.value.toIntOrNull()
        val miss = missed.value.toIntOrNull()
        val fee = feeGrace.value.toIntOrNull()
        if (!form.check(listOf(
                "grace" to if (late != null && late in 5..240) null else L("The grace is 5 to 240 minutes."),
                "missed" to if (!(miss != null && miss in 15..720)) L("Missed after is 15 to 720 minutes.")
                else if ((miss ?: 0) < (late ?: 0)) L("Missed after can’t be shorter than the grace.") else null,
                "feeGrace" to if (fee != null && fee in 0..360) null else L("The fee grace is 0 to 360 minutes."),
            ))) return
        saving = true
        scope.launch {
            try {
                val saved: HospSettings = API.put("/provider-hospital/settings", mapOf(
                    "lateGraceMinutes" to late, "missedAfterMinutes" to miss, "feeGraceMinutes" to fee,
                    "visitingInfo" to visiting.value.nonEmpty, "dailyFeeItemId" to feeItemId.value.nonEmpty,
                ))
                settings = saved
                fill(saved)
                Toast.show(L("Hospital settings saved."))
                announceDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.error(hospError(e, "Could not save."))
            } finally {
                saving = false
            }
        }
    }

    @Composable
    fun minutes(label: String, text: MutableState<String>, key: String, hint: String) {
        FormField(label = label, key = key, hint = hint, form = form) {
            Disabled(!canEdit) {
                FieldChrome(invalid = form.error(key) != null) {
                    PlainTextField(
                        text, "", Modifier.weight(1f), keyboard = KeyboardType.Number,
                        onChange = { value ->
                            val digits = value.filter { it.isDigit() }
                            if (digits != value) text.value = digits
                            form.clear(key)
                        },
                    )
                    Text(L("min"), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
    }

    Box(modifier.fillMaxWidth()) {
        val loaded = settings
        val failed = error
        if (loaded != null) {
            FormScroll(form) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    // The team's side: when a dose is on time or missed, and what a hospital day charges.
                    SectionCard(
                        L("Doses and daily fee"),
                        subtitle = L("How the treatment sheet colours each dose, and what a hospital day charges."),
                        icon = "clock-3",
                        accent = Theme.aqua,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HospCardGroup(title = L("Dose timing"), icon = "clock-3")
                                HospGrid(listOf(0, 1), minimum = 220.dp, spacing = 16.dp) { which ->
                                    if (which == 0) minutes(L("On time within"), grace, key = "grace", hint = L("Either side of the scheduled time."))
                                    else minutes(L("Missed after"), missed, key = "missed", hint = L("Or once the next dose is due."))
                                }
                            }
                            StitchedDivider()
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HospCardGroup(title = L("Daily hospital fee"), icon = "receipt", hint = L("A service from the Catalog, charged once per hospital day."))
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Disabled(!canEdit) {
                                        MenuField(label = L("Service"), options = hospFeeOptions(loaded, services), selection = feeItemId)
                                    }
                                    if (feeItemId.value.isNotEmpty()) HospHint(text = L("One line on the bill, its quantity the days in the hospital."))
                                }
                                minutes(L("Day grace"), feeGrace, key = "feeGrace", hint = L("A discharge this soon into a new day doesn’t charge it."))
                                val chosen = services.firstOrNull { it.id == feeItemId.value }
                                if (chosen != null && (chosen.defaultUnitAmount ?: 0) <= 0) {
                                    NoticeCard(tone = NoticeCard.Tone.warning, title = L("This service has no price: the bill asks for one before it can be charged. Set it in the Catalog."))
                                }
                            }
                        }
                    }
                    // The families' side: what they read, and where the money asked up front is set.
                    SectionCard(
                        L("For families"),
                        subtitle = L("Families read it in their app while their pet is in."),
                        icon = "heart-handshake",
                        accent = Theme.pink,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Disabled(!canEdit) {
                                PMTextField(
                                    L("Visiting information"), text = visiting,
                                    prompt = L("e.g. Visits from 12:00 to 13:00 and 18:00 to 19:00, one person at a time."),
                                    optional = true, multiline = true,
                                )
                            }
                            StitchedDivider()
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HospCardGroup(title = L("Deposit and after-hours surcharge"), icon = "coins")
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Theme.wash(Theme.aqua, 12.0), RoundedCornerShape(Theme.boxRadius))
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val deposit = loaded.emergencyDepositAmount
                                    if (deposit != null && deposit > 0) {
                                        Text(
                                            LF("Emergency deposit: %@.", Money.format(deposit, loaded.currency)),
                                            style = Theme.text(14, FontWeight.Bold), color = Theme.inkStrong,
                                        )
                                    }
                                    Text(L("The emergency deposit and the after-hours surcharge are in Store → Settings."), style = Theme.text(14), color = Theme.inkSoft)
                                    Text(
                                        L("Open Store settings"),
                                        Modifier.plainButton { nav.push(ProviderRoute.section(ProviderSection.store)) },
                                        style = Theme.text(14, FontWeight.SemiBold),
                                        color = Theme.blue,
                                    )
                                }
                            }
                        }
                    }
                    if (canEdit) {
                        // One save for both cards, with a way back.
                        SaveBar(dirty = dirty(), working = saving, saveTitle = L("Save"), floating = true, onDiscard = { fill(loaded) }, onSave = { save() })
                    } else {
                        HospHint(text = L("Only whoever manages the hospital can change these."))
                    }
                }
            }
        } else if (failed != null) {
            ErrorState(message = failed) { load() }
        } else {
            LoadingState()
        }
    }
    ReloadsOnScope { load() }
}

private fun hospFeeOptions(settings: HospSettings, services: List<HospCatalogItem>): List<Pair<String, String>> {
    val options = mutableListOf("" to L("No daily fee"))
    options += services.map { item ->
        item.id to "${item.name} · ${if ((item.defaultUnitAmount ?: 0) > 0) Money.format(item.defaultUnitAmount, item.currency) else L("No price")}"
    }
    val current = settings.dailyFeeItemId
    if (current != null && services.none { it.id == current }) {
        options += current to L("The current service")
    }
    return options
}
