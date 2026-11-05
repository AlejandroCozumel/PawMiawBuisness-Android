package com.pawmiaw.business.records

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.launch

//
//  RecordsRows.kt (iOS RecordsRows.swift)
//
//  A record and a follow-up in a list (web records-page/record-row.tsx,
//  follow-up-row.tsx, pet-cell.tsx, record-bits.tsx), and the day-grouped
//  list both sit in, inside the page's one card: a band per day, rows under
//  it between hairlines. The row opens what it is about; the pet's name, its
//  client and the buttons are their own taps.
//

// MARK: - Bits

/**
 * The record's kind, in its hue (web `RecordTypeChip`): the kit's chip at 18%, extrabold, its
 * icon in the hue (a note's in grey). (The Swift returns the `Chip`; here it draws it.)
 */
@Composable
fun recordsTypeChip(kind: RecordsKind, modifier: Modifier = Modifier) {
    Chip(
        text = kind.label,
        tone = Chip.Tone.custom(kind.hue.color),
        icon = kind.systemImage,
        percent = 18.0,
        iconInk = if (kind == RecordsKind.note) Theme.inkSoft else Theme.mix(kind.hue.color, 70.0, Theme.inkStrong),
        iconSize = 14.dp,
        weight = FontWeight.ExtraBold,
        modifier = modifier,
    )
}

/** Draft or signed, and "Corrected" when it changed after it was signed (web `RecordStatusBadge`). */
@Composable
fun RecordsStatusBadges(record: RecordsRecord, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(
            text = if (record.isDraft) L("Draft") else L("Signed"),
            tone = Chip.Tone.custom(RecordsText.statusState(record).color),
            icon = if (record.isDraft) "pen-line" else "badge-check",
        )
        if (!record.isDraft && record.amendedAt.nonEmpty != null) {
            val hint = L("Changed after it was signed")
            val word = L("Corrected")
            Chip(
                text = word,
                tone = Chip.Tone.custom(ProviderState.upcoming.color),
                icon = "rotate-ccw",
                modifier = Modifier.clearAndSetSemantics { contentDescription = "$word. $hint" },
            )
        }
    }
}

/**
 * An icon and its text, tight, for the meta line under a row (12 type, a 12 icon). The text's
 * font and ink come from around it (iOS `.font`/`.foregroundStyle` on the line).
 */
@Composable
fun RecordsMetaItem(icon: String, text: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 12.dp)
        if (text != null) Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row's tap that is not a button (iOS `.contentShape(Rectangle()).onTapGesture` + `.accessibilityAction(named:)`). */
@Composable
private fun Modifier.recordsRowTap(label: String, onTap: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null, onClickLabel = label, onClick = onTap)
}

// MARK: - The pet

/**
 * Whose row this is (web `PetCell`): the pet's face, its name (opens its chart) with the filter
 * button beside it, what it is ("Perro · Schnauzer", so two Lunas read apart) and its owner.
 */
@Composable
fun RecordsPetCell(
    pet: RecordsPetFilter,
    species: String?,
    breed: String?,
    needle: String = "",
    canViewClients: Boolean,
    filterLabel: String? = null,
    onFilter: (() -> Unit)? = null,
    onOpen: ((ProviderRoute) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PetAvatar(name = pet.name, species = pet.species, photoURL = pet.photoUrl, size = 40.dp)
        Column {
            Row(
                Modifier.heightIn(min = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val nameStyle = Theme.text(16, FontWeight.Bold)
                val name = RecordsText.highlight(pet.name, needle)
                if (canViewClients && onOpen != null) {
                    Text(
                        name, style = nameStyle, color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).plainButton(label = LF("Open %@’s chart", pet.name)) {
                            onOpen(ProviderRoute.patientChart(petId = pet.id))
                        },
                    )
                } else {
                    Text(name, style = nameStyle, color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (onFilter != null && filterLabel != null) {
                    Box(
                        Modifier.size(28.dp).plainButton(label = filterLabel, onClick = onFilter),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph("list-filter", 14.dp, tint = Theme.inkSoft)
                    }
                }
            }
            val kind = RecordsText.petKind(species = species, breed = breed)
            if (kind.isNotEmpty()) {
                Text(kind, style = Theme.text(12, FontWeight.SemiBold), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val client = RecordsText.highlight(pet.clientName, needle)
            if (canViewClients && onOpen != null) {
                Text(
                    client, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.plainButton { onOpen(ProviderRoute.client(pet.clientId)) },
                )
            } else {
                Text(client, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// MARK: - The day-grouped list

/**
 * A paged list under the day each row falls on, in the order the server sent them;
 * a day cut by the page break heads the next page too. 20 at a time, infinite scroll.
 * Drawn edge to edge inside the page's card.
 */
@Composable
fun <Item> RecordsDayList(
    pager: Pager<Item>,
    dayOf: (Item) -> String,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit,
    row: @Composable (Item) -> Unit,
) {
    class DayGroup(val id: String, val day: String, val items: MutableList<Item>)

    val error = pager.error
    when {
        !pager.loadedOnce && pager.isLoading -> LoadingState(modifier = modifier)
        error != null && pager.items.isEmpty() -> ErrorState(message = error, modifier = modifier) { pager.reload() }
        pager.items.isEmpty() -> if (pager.isLoading) LoadingState(modifier = modifier) else empty()
        else -> {
            val groups = ArrayList<DayGroup>()
            for (item in pager.items) {
                val day = dayOf(item)
                val last = groups.lastOrNull()
                if (last != null && last.day == day) last.items.add(item) else groups.add(DayGroup("$day-${groups.size}", day, mutableListOf(item)))
            }
            val fade by animateFloatAsState(if (pager.isLoading) 0.55f else 1f, tween(150, easing = EaseOut), label = "records list")
            Column(modifier.fillMaxWidth().graphicsLayer { alpha = fade }) {
                groups.forEachIndexed { index, group ->
                    key(group.id) {
                        if (index > 0) RowDivider()
                        DayBand(title = RecordsText.capitalizedFirst(RecordsText.dayHeading(group.day)), style = DayBand.Style.records)
                        group.items.forEachIndexed { position, item ->
                            key(pager.id(item)) {
                                if (position > 0) RowDivider()
                                Box(Modifier.onScrolledIntoView { pager.loadMoreIfNeeded(item) }) { row(item) }
                            }
                        }
                    }
                }
                PagerFooter(pager)
            }
        }
    }
}

// MARK: - A record

/** `RecordsRecordRow.Mode`. */
object RecordsRecordRow {
    enum class Mode { normal, unfinished }
}

/**
 * A record in a list: the pet it belongs to, its kind and state, its title and one line of what it
 * says, at what time and by whom. Drafts carry Sign for those who sign; an empty draft (To complete)
 * "Write it" and "Discard". The row opens the record's page.
 */
@Composable
fun RecordsRecordRow(
    record: RecordsRecord,
    mode: RecordsRecordRow.Mode = RecordsRecordRow.Mode.normal,
    access: RecordsAccess,
    needle: String = "",
    onSign: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    onFilterPet: (() -> Unit)? = null,
    onOpen: ((ProviderRoute) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val canEdit = access.canEdit(record) && onEdit != null
    val author = record.assignedMember?.name.nonEmpty ?: record.createdBy?.name.nonEmpty

    Column(
        modifier
            .fillMaxWidth()
            .recordsRowTap(LF("Open %@", record.titleText)) { onOpen?.invoke(ProviderRoute.record(record.id)) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            RecordsPetCell(
                pet = record.petFilter,
                species = record.petSpecies,
                breed = record.petBreed,
                needle = needle,
                canViewClients = access.viewClients,
                filterLabel = LF("See only %@’s records", record.petText),
                onFilter = onFilterPet,
                onOpen = onOpen,
                modifier = Modifier.weight(1f),
            )
            RecordsText.time(record.eventAt)?.let { time ->
                Text(time, style = Theme.text(14, FontWeight.Bold).monospacedDigit(), color = Theme.inkStrong)
            }
        }
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                recordsTypeChip(record.kind)
                RecordsStatusBadges(record)
            }
            Text(
                RecordsText.highlight(record.titleText, needle),
                style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            val summary = RecordsPayload.summaryLine(record)
            if (summary.isNotEmpty()) {
                Text(RecordsText.highlight(summary, needle), style = Theme.text(14), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The meta line: who, files, the follow-up, the staff-only note.
            CompositionLocalProvider(LocalTextStyle provides Theme.text(12), LocalContentColor provides Theme.inkSoft) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (author != null) RecordsMetaItem(icon = "user-round", text = author, modifier = Modifier.weight(1f, fill = false))
                    if (record.files.isNotEmpty()) {
                        val label = LF("%d files attached", record.files.size)
                        RecordsMetaItem(icon = "paperclip", text = "${record.files.size}", modifier = Modifier.clearAndSetSemantics { contentDescription = label })
                    }
                    if (record.followUpAt.nonEmpty != null) {
                        val label = L("Follow-up")
                        RecordsMetaItem(icon = "bell-ring", text = RecordsText.day(record.followUpAt), modifier = Modifier.clearAndSetSemantics { contentDescription = label })
                    }
                    if (record.internalNote.nonEmpty != null) {
                        val label = L("Has a staff-only note")
                        RecordsMetaItem(icon = "lock", modifier = Modifier.clearAndSetSemantics { contentDescription = label })
                    }
                }
            }
            // The actions.
            val showSign = mode == RecordsRecordRow.Mode.normal && record.isDraft && access.signRecords && onSign != null
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mode == RecordsRecordRow.Mode.unfinished) {
                    if (canEdit) {
                        PMButton(L("Write it"), systemImage = "pencil", size = ButtonSize.compact, action = onEdit)
                    }
                    if (onDiscard != null) {
                        PMButton(L("Discard"), variant = ButtonVariant.outline, size = ButtonSize.compact, action = onDiscard)
                    }
                } else {
                    if (showSign) {
                        PMButton(L("Sign"), systemImage = "badge-check", size = ButtonSize.compact, action = onSign)
                    }
                    if (canEdit) {
                        IconCircleButton(systemImage = "pencil", label = LF("Edit %@", record.titleText), action = onEdit)
                    }
                }
            }
        }
    }
}

// MARK: - A follow-up

/**
 * One follow-up: when it is due (red once late), for which pet, what it is and where it came
 * from, and what to do about it — done, a reminder by WhatsApp or in PawMiaw, or its visit.
 * A finished visit's paperwork is never "done" by hand: it closes when the note exists; an owner
 * or admin may say it needs none. The left edge carries the urgency, as on the Due page.
 * `compact`: on a record's page — no pet, no source (it is that record). `grouped`: under a day
 * heading — only the due words. `canWaive`: owners and admins may close a visit's paperwork
 * without a note.
 */
@Composable
fun RecordsFollowUpRow(
    followUp: RecordsFollowUp,
    access: RecordsAccess,
    compact: Boolean = false,
    grouped: Boolean = false,
    needle: String = "",
    reach: RecordsReach? = null,
    canWaive: Boolean = false,
    onWaive: (() -> Unit)? = null,
    onFilterPet: (() -> Unit)? = null,
    onOpen: ((ProviderRoute) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val pending = followUp.isPending
    val paperwork = followUp.isVisitPaperwork
    val showWhatsApp = reach == null || reach.phone != null
    val showApp = reach?.onPawMiaw != false
    val canRemind = access.remindFollowUps && pending && (showWhatsApp || showApp) && !RecordsText.isInternalFollowUp(followUp.followUpType)
    val writesOnVisit = pending && !compact && followUp.followUpType == "documentation_needed" && followUp.providerAppointmentId.nonEmpty != null && access.viewAppointments
    val titleText = RecordsText.followUpTitle(followUp.title)

    /** Where the row opens: its record, else its visit, else its client. */
    val source: ProviderRoute? = run {
        if (compact) return@run null
        followUp.providerRecordId.nonEmpty?.let { record -> if (access.viewRecords) return@run ProviderRoute.record(record) }
        followUp.providerAppointmentId.nonEmpty?.let { visit -> if (access.viewAppointments) return@run ProviderRoute.appointment(visit) }
        if (access.viewClients) return@run ProviderRoute.client(followUp.providerClientId)
        null
    }

    val stripe = if (pending) RecordsText.urgency(followUp.dueAt).color else if (followUp.status == "done") ProviderState.done.color else Theme.borderSoft

    val subline: String = run {
        val sourceName = followUp.recordTitle.nonEmpty ?: followUp.appointmentServiceLabel.nonEmpty
        // "From Triple felina" under "Toca: Triple felina" says the same thing twice.
        val shownSource = sourceName?.let { if (titleText.lowercase().contains(it.lowercase())) null else it }
        val parts = mutableListOf<String>()
        if (!compact) {
            if (shownSource != null) parts.add(LF("From %@", shownSource)) else if (sourceName == null) parts.add(L("Added by hand"))
        }
        RecordsText.followUpNote(followUp.note)?.let { parts.add(it) }
        RecordsText.resolutionLabel(followUp)?.let { parts.add(it) }
        if (followUp.resolvedAt.nonEmpty != null) parts.add(LF("Closed %@", RecordsText.day(followUp.resolvedAt)))
        parts.joinToString(" · ")
    }

    fun complete() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                RecordsAPI.updateFollowUp(followUp.id, mapOf("status" to "done"))
                Toast.show(L("Follow-up done."))
                announceDataChanged()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not complete this follow-up."))
            } finally {
                busy = false
            }
        }
    }

    fun remind(channel: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = RecordsAPI.remind(followUp.id, channel)
                val link = result.link.nonEmpty
                if (channel == "whatsapp" && link != null) {
                    openURL(link)
                    Toast.show(L("WhatsApp is open with the message."))
                } else {
                    Toast.show(L("Reminder sent in PawMiaw."))
                }
                announceDataChanged()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                busy = false
            }
        }
    }

    @Composable
    fun Due(trailing: Boolean) {
        Column(
            horizontalAlignment = if (trailing) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                if (pending) RecordsText.dueLabel(followUp.dueAt) else RecordsText.day(followUp.dueAt),
                style = brandText(14, FontWeight.ExtraBold),
                color = if (!pending) Theme.inkSoft else if (followUp.isOverdue) Theme.destructive else Theme.inkStrong,
                maxLines = 1,
            )
            if (pending && !grouped) {
                Text(RecordsText.day(followUp.dueAt), style = Theme.text(12), color = Theme.inkSoft)
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                drawRect(stripe, size = Size(6.dp.toPx(), size.height))
            }
            .recordsRowTap(LF("Open %@", titleText)) { source?.let { onOpen?.invoke(it) } }
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (compact) {
            Due(trailing = false)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                RecordsPetCell(
                    pet = followUp.petFilter,
                    species = followUp.petSpecies,
                    breed = followUp.petBreed,
                    needle = needle,
                    canViewClients = access.viewClients,
                    filterLabel = LF("See only %@’s follow-ups", followUp.petName.nonEmpty ?: ""),
                    onFilter = onFilterPet,
                    onOpen = onOpen,
                    modifier = Modifier.weight(1f),
                )
                Due(trailing = true)
            }
        }

        // What it is.
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(text = RecordsText.followUpTypeLabel(followUp.followUpType), tone = Chip.Tone.custom(Theme.lavender), icon = "bell-ring")
                if (!pending || followUp.status != "open") {
                    Chip(text = RecordsText.followUpStatusLabel(followUp.status), tone = Chip.Tone.custom(RecordsText.followUpState(followUp.status).color))
                }
            }
            Text(
                RecordsText.highlight(titleText, needle),
                style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (subline.isNotEmpty()) {
                Text(subline, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CompositionLocalProvider(LocalTextStyle provides Theme.text(12), LocalContentColor provides Theme.inkSoft) {
                RecordsMetaItem(icon = "user-round", text = followUp.assignedMember?.name.nonEmpty ?: L("Unassigned"), modifier = Modifier.padding(top = 4.dp))
            }
        }

        // What to do about it.
        val canDone = access.completeFollowUps && pending && !paperwork
        val canNotNeeded = paperwork && pending && canWaive && onWaive != null
        val visit = followUp.providerAppointmentId.nonEmpty
        val visitLink = !compact && !writesOnVisit && visit != null && access.viewAppointments
        if (writesOnVisit || canDone || canNotNeeded || canRemind || visitLink) {
            FlowLayout(spacing = 8.dp) {
                if (writesOnVisit && visit != null) {
                    PMButton(L("Open visit"), systemImage = "calendar-days", size = ButtonSize.header) { onOpen?.invoke(ProviderRoute.appointment(visit)) }
                }
                if (canDone) {
                    PMButton(
                        L("Done"), systemImage = "circle-check",
                        variant = if (writesOnVisit) ButtonVariant.outline else ButtonVariant.primary,
                        size = ButtonSize.header, isWorking = busy,
                    ) { complete() }
                }
                if (canNotNeeded) {
                    PMButton(L("Not needed…"), systemImage = "file-x-2", variant = ButtonVariant.outline, size = ButtonSize.header, enabled = !busy, action = onWaive)
                }
                if (canRemind) {
                    AppMenu(
                        label = {
                            PMButtonStyle(variant = ButtonVariant.outline, size = ButtonSize.header) {
                                PMButtonContent(L("Remind"), icon = "message-circle", variant = ButtonVariant.outline, size = ButtonSize.header)
                            }
                        },
                        enabled = !busy,
                    ) {
                        if (showWhatsApp) item("WhatsApp", icon = "message-circle") { remind("whatsapp") }
                        if (showApp) item(L("In PawMiaw"), icon = "smartphone") { remind("app") }
                    }
                }
                if (visitLink) {
                    PMButton(L("Visit"), systemImage = "calendar-days", variant = ButtonVariant.outline, size = ButtonSize.header) { onOpen?.invoke(ProviderRoute.appointment(visit)) }
                }
            }
        }
    }
}
