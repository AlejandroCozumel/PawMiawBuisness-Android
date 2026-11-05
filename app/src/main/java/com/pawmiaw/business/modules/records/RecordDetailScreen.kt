package com.pawmiaw.business.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawmiaw.business.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

//
//  RecordDetailScreen.kt (iOS RecordDetailScreen.swift)
//
//  One record (web routes/provider.records.$recordId.tsx): what it is and for
//  which pet, whether it is signed (shared with a family on PawMiaw) or still
//  a draft, the note labelled by who reads each part, its fields, its clinical
//  state, its files, its follow-ups, the visit it came from, the patient and
//  its history. Sign, edit, assign, add a follow-up date or files, and — for
//  an empty draft — discard it (the history keeps that it was).
//

/** The cards the header's numbers and the ⋯ menu jump to (iOS `.id(…)` + `ScrollViewReader`). */
private val recordDetailTargets = listOf("record-note", "record-files", "record-follow-ups", "record-visit", "record-history")

/** Scrolls the card to the top of the page (iOS `proxy.scrollTo(target, anchor: .top)`). */
@OptIn(ExperimentalFoundationApi::class)
private suspend fun BringIntoViewRequester.toTop() {
    // A rect taller than any screen from the card's top: the page brings its top edge up.
    bringIntoView(Rect(0f, 0f, 1f, 100_000f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordDetailScreen(recordId: String, modifier: Modifier = Modifier) {
    val workspace = Workspace.shared
    val nav = LocalNavigator.current
    val dismiss = LocalDismiss.current
    val context = LocalContext.current
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()
    val wide = LocalSizeClass.current == SizeClass.regular

    var record by remember { mutableStateOf<RecordsRecord?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var appointment by remember { mutableStateOf<RecordsAppointment?>(null) }
    var followUps by remember { mutableStateOf<List<RecordsFollowUp>>(emptyList()) }
    var members by remember { mutableStateOf<List<RecordsMember>>(emptyList()) }
    var editing by remember { mutableStateOf<RecordsSheetTarget?>(null) }
    var signing by remember { mutableStateOf(false) }
    var changingState by remember { mutableStateOf(false) }
    var openingFile by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var discarded by remember { mutableStateOf(false) }
    var scrollTarget by remember { mutableStateOf<String?>(null) }
    val targets = remember { recordDetailTargets.associateWith { BringIntoViewRequester() } }

    val access = RecordsAccess(workspace)

    LaunchedEffect(scrollTarget) {
        val target = scrollTarget ?: return@LaunchedEffect
        targets[target]?.toTop()
        scrollTarget = null
    }

    // MARK: Loading

    suspend fun load() {
        val a = access
        if (!a.records) return
        val clinic = workspace.current?.clinicId
        val needTeam = a.editRecords && members.isEmpty() && clinic != null
        val id = recordId
        try {
            val fetched = RecordsAPI.record(id)
            record = fetched
            loadError = null
            notFound = false
            val visitId = if (a.viewAppointments) fetched.appointmentId.nonEmpty else null
            val visit = recordsStart(visitId != null) { RecordsAPI.appointment(visitId ?: "") }
            val follow = recordsStart(a.viewFollowUps) { RecordsAPI.followUps(limit = 10, offset = 0, status = "all", record = id) }
            val team = recordsStart(needTeam) { RecordsAPI.members(clinic ?: "") }
            appointment = visit.await()
            followUps = follow.await()?.items ?: followUps
            team.await()?.let { members = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: APIError) {
            if (e.status == 404) {
                if (record == null) notFound = true
            } else {
                if (record == null) loadError = errorText(e) else Toast.error(e)
            }
        } catch (e: Exception) {
            if (record == null) loadError = errorText(e) else Toast.error(e)
        }
    }

    // MARK: Actions

    fun sign(current: RecordsRecord) {
        if (signing) return
        signing = true
        scope.launch {
            try {
                RecordsAPI.sign(current.id)?.let { record = it }
                Toast.show(if (current.onPawMiaw) L("Signed. The family can see it now.") else L("Signed. It is part of the chart now."))
                announceDataChanged()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not sign this record."))
            } finally {
                signing = false
            }
        }
    }

    fun changeClinicalState(current: RecordsRecord, next: String) {
        if (changingState || current.kind.clinicalState(current.payload) == next) return
        val payload = LinkedHashMap<String, JsonElement>(current.payload ?: emptyMap())
        val today = DateText.dayKey()
        when (current.kind) {
            RecordsKind.diagnosis -> {
                payload["status"] = JsonPrimitive(next)
                payload["resolved_date"] = if (next == "resolved") JsonPrimitive(today) else JsonNull
            }
            RecordsKind.medication -> {
                payload["status"] = JsonPrimitive(next)
                if (next == "active") {
                    payload["stop_date"] = JsonNull
                    payload["stop_reason"] = JsonNull
                } else if ((payload["stop_date"] as? JsonPrimitive)?.takeIf { it.isString }?.content.nonEmpty == null) {
                    payload["stop_date"] = JsonPrimitive(today)
                }
            }
            RecordsKind.allergy -> payload["status"] = JsonPrimitive(next)
            else -> return
        }
        changingState = true
        scope.launch {
            try {
                val plain: Map<String, Any?> = payload.mapValues { recordsPlainValue(it.value) }
                val body = linkedMapOf<String, Any?>("payload" to plain)
                current.revision?.let { body["expectedRevision"] = it }
                record = RecordsAPI.update(current.id, body)
                Toast.show("${LF("%@ updated.", current.kind.label)} ${LF("Now %@.", RecordsText.clinicalStateLabel(next).lowercase())}")
                announceDataChanged()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not update this record right now."))
            } finally {
                changingState = false
            }
        }
    }

    fun assign(current: RecordsRecord, member: RecordsMember) {
        scope.launch {
            try {
                val body = linkedMapOf<String, Any?>("assignedOrganizationMemberId" to member.id)
                current.revision?.let { body["expectedRevision"] = it }
                record = RecordsAPI.update(current.id, body)
                Toast.show(L("Record updated."))
                announceDataChanged()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e, fallback = L("Could not update this record right now."))
            }
        }
    }

    fun open(attachment: RecordsAttachment) {
        if (openingFile != null) return
        openingFile = attachment.id
        scope.launch {
            try {
                val file = RecordsFileStore.shared.localFile(attachment)
                quickLookPreview(context, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                openingFile = null
            }
        }
    }

    // MARK: The page

    /**
     * The header's ⋯ (web: the record's More menu): its pet, client and visit, assigning it, its
     * history, and throwing an empty draft away.
     */
    @Composable
    fun MoreMenu(record: RecordsRecord) {
        val canEditThis = access.canEdit(record)
        AppMenu(label = { IconCircleLabel() }, accessibilityLabel = L("More")) {
            if (access.viewClients) {
                item(LF("Open %@’s chart", record.petText), icon = "paw-print") { nav.push(ProviderRoute.patientChart(petId = record.providerClientPetId)) }
                item(L("Open client"), icon = "user-round") { nav.push(ProviderRoute.client(record.providerClientId)) }
            }
            val visit = record.appointmentId.nonEmpty
            if (visit != null && access.viewAppointments) {
                item(L("Open visit"), icon = "calendar-days") { nav.push(ProviderRoute.appointment(visit)) }
            }
            if (canEditThis && access.assignAnyone && members.isNotEmpty()) {
                // A submenu (iOS `Menu` inside the menu): the team, a ✓ on whoever has it.
                picker(
                    title = L("Assign to"),
                    icon = "user-plus",
                    options = members.map { it.id to it.label },
                    selection = stateOf({ record.assignedMember?.id ?: "" }, {}),
                    inline = false,
                    onChange = { id -> members.firstOrNull { it.id == id }?.let { assign(record, it) } },
                )
            }
            item(L("History of changes"), icon = "rotate-ccw") { scrollTarget = "record-history" }
            if (access.editRecords && record.isEmptyDraft) {
                divider()
                item(L("Discard this empty draft"), icon = "trash-2", destructive = true) { confirmDiscard = true }
            }
        }
    }

    @Composable
    fun headerStats(record: RecordsRecord): List<ModuleStat> {
        val household = record.onPawMiaw
        val draft = record.isDraft
        val openOnes = followUps.filter { (it.status ?: "open") in listOf("open", "contacted", "snoozed") }
        val next = openOnes.sortedBy { it.dueAt ?: "" }.firstOrNull()
        val familyValue = if (draft) L("Not shared") else if (household) L("Shared") else L("Not on PawMiaw")
        val familyHint = if (draft) {
            if (access.signRecords) (if (household) L("Draft — sign it to share") else L("Draft — sign it to make it part of the chart")) else L("Draft — waiting for a vet")
        } else {
            if (household) LF("Signed %@", RecordsText.day(record.signedAt)) else LF("Signed %@ · only your clinic sees it", RecordsText.day(record.signedAt))
        }
        val stats = mutableListOf(
            ModuleStat(
                "shared", label = L("Family"), value = familyValue, hint = familyHint, icon = if (draft) "pen-line" else "badge-check",
                accent = if (draft) ProviderState.waiting.color else ProviderState.done.color, attention = draft && access.signRecords,
                action = { scrollTarget = "record-note" },
            ),
        )
        if (access.viewFollowUps) {
            stats.add(ModuleStat(
                "follow-up", label = L("Follow-up"), value = next?.let { RecordsText.day(it.dueAt) } ?: L("None@follow-up"),
                hint = next?.let { "${RecordsText.followUpTypeLabel(it.followUpType)} · ${RecordsText.dueLabel(it.dueAt)}" } ?: (if (followUps.isEmpty()) L("Nothing due") else L("All closed")),
                icon = "bell-ring", accent = Theme.pink, attention = next?.isOverdue == true,
                action = { scrollTarget = "record-follow-ups" },
            ))
        }
        stats.add(ModuleStat(
            "files", label = L("Files"), value = "${record.files.size}",
            hint = if (record.files.isEmpty()) L("None attached") else L("Photos and documents"),
            icon = "paperclip", accent = Theme.aqua, action = { scrollTarget = "record-files" },
        ))
        if (record.appointmentId.nonEmpty != null) {
            stats.add(ModuleStat(
                "visit", label = L("Visit"), value = appointment?.let { RecordsText.day(it.startsAt) } ?: L("Linked@visit"),
                hint = appointment?.serviceLabel.nonEmpty, icon = "calendar-days", accent = Theme.blue,
                action = { scrollTarget = "record-visit" },
            ))
        }
        return stats
    }

    /**
     * The module header (web `ProviderModuleHeader`): the pet's face, the title, what it is, for whom
     * and when; its state, Sign, Edit and ⋯; the four numbers, each a jump to its card.
     */
    @Composable
    fun Header(record: RecordsRecord) {
        val canSign = record.isDraft && access.signRecords
        val canEditThis = access.canEdit(record)
        ModuleHeader(
            icon = record.kind.systemImage,
            title = record.titleText,
            subtitle = listOf(record.kind.label, record.petText, record.clientText, RecordsText.longDate(record.eventAt)).joinToString(" · "),
            accent = Theme.aqua,
            media = {
                BoxWithConstraints {
                    PetAvatar(name = record.petName, species = record.petSpecies, photoURL = record.petPhotoUrl, size = maxWidth)
                }
            },
            titleAccessory = { RecordsStatusBadges(record) },
            stats = headerStats(record),
            storageKey = "record",
        ) {
            if (canSign) {
                PMButton(if (signing) L("Signing…") else L("Sign"), systemImage = "badge-check", isWorking = signing) { sign(record) }
            }
            if (canEditThis) {
                PMButton(L("Edit"), systemImage = "pencil", variant = ButtonVariant.outline) { editing = RecordsSheetTarget(record = record) }
            }
            MoreMenu(record)
        }
    }

    /** The states a diagnosis, medicine or allergy moves through; the current one is raised (web `ClinicalStateCard`). */
    @Composable
    fun ClinicalCard(record: RecordsRecord, states: List<String>, canChange: Boolean) {
        val current = record.kind.clinicalState(record.payload)
        Card(spacing = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("Clinical status").uppercase(), style = brandText(12, FontWeight.ExtraBold).tracking(1.44), color = Theme.inkSoft)
                    Text(current?.let { RecordsText.clinicalStateLabel(it) } ?: "—", style = brandHeading(18), color = Theme.inkStrong)
                }
                if (changingState) CircularProgressIndicator(Modifier.size(20.dp), color = Theme.blue, strokeWidth = 2.dp)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).alpha(if (canChange) 1f else 0.6f)) {
                Disabled(!canChange || changingState) {
                    Segmented(
                        options = states.map { Segmented.Option(it, RecordsText.clinicalStateLabel(it)) },
                        selection = stateOf({ current ?: states[0] }, { changeClinicalState(record, it) }),
                        look = Segmented.Look.raised,
                        label = L("Clinical status"),
                    )
                }
            }
        }
    }

    @Composable
    fun NoteSection(record: RecordsRecord, onEdit: (() -> Unit)?, modifier: Modifier) {
        val summary = record.summary.nonEmpty
        val details = record.details.nonEmpty
        val staffNote = record.internalNote.nonEmpty
        val signer = record.signedBy?.name.nonEmpty
        val empty = summary == null && details == null && staffNote == null
        val familyAudience = if (!record.onPawMiaw) L("Not on PawMiaw") else if (record.isSigned) L("The family sees this") else L("The family sees it once signed")
        val hint = if (record.isSigned) LF("Signed %@ by %@", RecordsText.dateTime(record.signedAt), signer ?: L("someone on the team"))
        else L("A draft — only your team can see it.")
        SectionCard(
            title = L("Note"), subtitle = hint, icon = "file-text", modifier = modifier,
            trailing = {
                if (onEdit != null && empty) {
                    PMButton(L("Write the note"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onEdit)
                }
            },
        ) {
            if (empty) {
                EmptyState(systemImage = "file-text", title = L("Nothing written yet."))
            } else {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (summary != null) {
                        RecordsNoteBlock(title = L("For the family"), audience = familyAudience, systemImage = "eye", tone = Theme.aqua, text = summary)
                    }
                    if (details != null) {
                        RecordsNoteBlock(title = L("Clinical notes"), audience = L("In the chart"), systemImage = "stethoscope", tone = Theme.blue, text = details)
                    }
                    if (staffNote != null) {
                        RecordsNoteBlock(title = L("Staff only"), audience = L("Never shown to the family"), systemImage = "lock", tone = Theme.mango, text = staffNote)
                    }
                    if (record.isSigned && signer != null) {
                        RecordsSignature(name = signer, day = record.signedAt?.let { RecordsText.day(it) })
                    }
                }
            }
        }
    }

    @Composable
    fun FilesSection(record: RecordsRecord, onAdd: (() -> Unit)?, modifier: Modifier) {
        val files = record.files
        SectionCard(
            title = L("Files"), subtitle = if (files.isEmpty()) null else (if (files.size == 1) L("1 file") else LF("%d files", files.size)), icon = "paperclip",
            modifier = modifier,
            trailing = {
                if (onAdd != null && files.isNotEmpty()) {
                    PMButton(L("Add files"), systemImage = "plus", variant = ButtonVariant.outline, size = ButtonSize.compact, action = onAdd)
                }
            },
        ) {
            if (files.isEmpty()) {
                EmptyState(systemImage = "paperclip", title = L("No photos or documents attached."), actionTitle = if (onAdd == null) null else L("Add files"), action = onAdd)
            } else {
                val columns = if (wide) 4 else 2
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    files.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            row.forEach { attachment ->
                                key(attachment.id) {
                                    AttachmentTile(
                                        fileName = attachment.fileName, sizeText = attachment.sizeText, isImage = attachment.isImage,
                                        fileExtension = attachment.fileExtension, isOpening = openingFile == attachment.id,
                                        modifier = Modifier.weight(1f),
                                        action = { open(attachment) },
                                    ) {
                                        RecordsAuthImage(url = attachment.url)
                                    }
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FollowUpsSection(record: RecordsRecord, onEdit: (() -> Unit)?, modifier: Modifier) {
        val openCount = followUps.count { it.isPending }
        SectionCard(
            L("Follow-ups"), subtitle = if (openCount > 0) (if (openCount == 1) L("1 still open") else LF("%d still open", openCount)) else null, icon = "bell-ring",
            modifier = modifier,
        ) {
            if (followUps.isEmpty()) {
                EmptyState(
                    systemImage = "bell-ring", title = L("No follow-up. Give the record a follow-up date and it becomes a task for the team."),
                    actionTitle = if (onEdit == null) null else L("Add a follow-up date"), action = onEdit,
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    followUps.forEachIndexed { index, followUp ->
                        key(followUp.id) {
                            if (index > 0) RowDivider()
                            RecordsFollowUpRow(
                                followUp = followUp,
                                access = access,
                                compact = true,
                                reach = RecordsReach(phone = record.clientPhone.nonEmpty, onPawMiaw = record.onPawMiaw),
                                onOpen = { nav.push(it) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun VisitSection(modifier: Modifier) {
        val visit = appointment
        SectionCard(
            title = L("Visit"), subtitle = L("Written during this visit."), icon = "calendar-days", modifier = modifier,
            trailing = {
                if (visit != null && access.viewAppointments) {
                    PMButton(L("Open visit"), variant = ButtonVariant.outline, size = ButtonSize.header) { nav.push(ProviderRoute.appointment(visit.id)) }
                }
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visit != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(visit.serviceLabel.nonEmpty ?: L("Visit"), style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, modifier = Modifier.weight(1f, fill = false))
                        Chip(text = RecordsText.visitStatusLabel(visit.status), tone = Chip.Tone.custom(ProviderState.appointment(visit.status).color))
                    }
                    CompositionLocalProvider(LocalTextStyle provides Theme.text(14), LocalContentColor provides Theme.inkSoft) {
                        FlowLayout(spacing = 16.dp) {
                            RecordsMetaItem(icon = "calendar-days", text = RecordsText.dateTime(visit.startsAt))
                            visit.room.nonEmpty?.let { RecordsMetaItem(icon = "door-open", text = it) }
                            RecordsMetaItem(icon = "user-round", text = visit.assignedMember?.name.nonEmpty ?: L("Unassigned"))
                        }
                    }
                } else {
                    Text(L("This record is linked to a visit your role can’t open."), style = Theme.text(14), color = Theme.inkSoft)
                }
            }
        }
    }

    // MARK: Main column

    @Composable
    fun MainColumn(record: RecordsRecord) {
        val canEditThis = access.canEdit(record)
        val openEdit: (() -> Unit)? = if (canEditThis) ({ editing = RecordsSheetTarget(record = record) }) else null

        if (record.isDraft) {
            NoticeCard(
                tone = NoticeCard.Tone.warning,
                title = L("Draft — only your team can see it."),
                message = if (access.signRecords) {
                    if (record.onPawMiaw) LF("Sign it and %@ sees it in their app.", record.clientText) else L("Sign it to make it part of the chart.")
                } else {
                    if (record.onPawMiaw) L("A vet signs it before the family sees it.") else L("A vet signs it before it becomes part of the chart.")
                },
                actionTitle = if (access.signRecords) L("Sign") else null,
                action = if (access.signRecords) ({ sign(record) }) else null,
            )
        } else if (record.amendedAt.nonEmpty != null) {
            NoticeCard(
                tone = NoticeCard.Tone.info,
                title = if (record.onPawMiaw) LF("Corrected %@, after it was signed. The family sees the corrected version.", RecordsText.dateTime(record.amendedAt))
                else LF("Corrected %@, after it was signed.", RecordsText.dateTime(record.amendedAt)),
            )
        }

        record.kind.clinicalStates?.let { states -> ClinicalCard(record, states, canChange = canEditThis) }

        NoteSection(record, onEdit = openEdit, modifier = Modifier.bringIntoViewRequester(targets.getValue("record-note")))

        val rows = RecordsPayload.rows(record)
        if (rows.isNotEmpty()) {
            SectionCard(L("Record details"), subtitle = record.kind.label, icon = "file-text") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        RecordsLabelledRow(label = row.label, value = row.value)
                    }
                }
            }
        }

        FilesSection(record, onAdd = openEdit, modifier = Modifier.bringIntoViewRequester(targets.getValue("record-files")))

        if (access.viewFollowUps) {
            FollowUpsSection(record, onEdit = openEdit, modifier = Modifier.bringIntoViewRequester(targets.getValue("record-follow-ups")))
        }

        if (record.appointmentId.nonEmpty != null) {
            VisitSection(modifier = Modifier.bringIntoViewRequester(targets.getValue("record-visit")))
        }

        AuditHistoryCard(
            entityType = "provider_record", entityId = record.id, title = L("History of changes"),
            description = L("Every edit, signature, file and follow-up on this record, and who made it."),
            modifier = Modifier.bringIntoViewRequester(targets.getValue("record-history")),
        )
    }

    // MARK: Aside

    @Composable
    fun Aside(record: RecordsRecord) {
        SectionCard(L("Patient"), icon = "user-round") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PetAvatar(name = record.petName, species = record.petSpecies, photoURL = record.petPhotoUrl, size = 48.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (access.viewClients) {
                            Text(
                                record.petText, style = brandHeading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.plainButton(label = LF("Open %@’s chart", record.petText)) { nav.push(ProviderRoute.patientChart(petId = record.providerClientPetId)) },
                            )
                        } else {
                            Text(record.petText, style = brandHeading(18), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val profile = RecordsText.petProfile(record)
                        if (profile.isNotEmpty()) Text(profile, style = Theme.text(12), color = Theme.inkSoft)
                    }
                }
                val chip = record.petMicrochipNumber.nonEmpty
                val neutered = record.petIsNeutered
                if (chip != null || neutered != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        if (chip != null) RecordsLabelledRow(label = L("Microchip"), value = chip)
                        if (chip != null && neutered != null) RowDivider()
                        if (neutered != null) RecordsLabelledRow(label = L("Neutered"), value = if (neutered) L("Yes") else L("No"))
                    }
                }
                RowDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (access.viewClients) {
                            Text(
                                record.clientText, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).plainButton { nav.push(ProviderRoute.client(record.providerClientId)) },
                            )
                        } else {
                            Text(record.clientText, style = Theme.text(16, FontWeight.Bold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.width(4.dp))
                        TagChip(
                            text = if (record.onPawMiaw) L("Linked household") else L("Clinic-only"),
                            icon = if (record.onPawMiaw) "link-2" else "building-2",
                            linked = record.onPawMiaw,
                        )
                    }
                    record.clientPhone.nonEmpty?.let { phone -> PhoneLink(phone = phone) }
                    record.clientEmail.nonEmpty?.let { email ->
                        Row(
                            Modifier.plainButton { openURL("mailto:$email") },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph("mail", 14.dp, tint = Theme.inkSoft)
                            Text(email, style = Theme.text(14), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        SectionCard(L("About this record"), icon = "file-text") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                RecordsLabelledRow(label = L("Type"), value = record.kind.label)
                RowDivider()
                RecordsLabelledRow(label = L("Date"), value = RecordsText.longDate(record.eventAt))
                RowDivider()
                RecordsLabelledRow(label = L("Assigned to"), value = record.assignedMember?.name.nonEmpty ?: L("Unassigned"))
                RowDivider()
                RecordsLabelledRow(label = L("Written by"), value = RecordsText.actorLine(record.createdBy?.name, record.createdAt))
                if (record.updatedAt != record.createdAt) {
                    RowDivider()
                    RecordsLabelledRow(label = L("Last change"), value = RecordsText.actorLine(record.updatedBy?.name, record.updatedAt))
                }
                if (record.signedAt.nonEmpty != null) {
                    RowDivider()
                    RecordsLabelledRow(label = L("Signed"), value = RecordsText.actorLine(record.signedBy?.name, record.signedAt))
                }
                if (record.amendedAt.nonEmpty != null) {
                    RowDivider()
                    RecordsLabelledRow(label = L("Corrected"), value = RecordsText.dateTime(record.amendedAt))
                }
            }
        }
    }

    @Composable
    fun Content(record: RecordsRecord) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Header(record)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { MainColumn(record) }
                    Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Aside(record) }
                }
            } else {
                MainColumn(record)
                Aside(record)
            }
        }
    }

    providerPage(record?.titleText ?: L("Record@one"), modifier = modifier) {
        PageScroll(maxWidth = if (wide) 1120.dp else 820.dp, refresh = { load() }) {
            val shown = record
            val failure = loadError
            when {
                !access.records -> NoAccessState(message = L("Ask the workspace owner to let your role see records."))
                shown != null -> Content(shown)
                notFound -> EmptyState(
                    systemImage = "file-search",
                    title = L("Record not found"),
                    message = L("It may have been removed, or it belongs to another workspace or to someone else’s patients."),
                    actionTitle = L("See all records"),
                    action = { nav.push(ProviderRoute.section(ProviderSection.records)) },
                    panel = true,
                )
                failure != null -> ErrorState(message = failure) { load() }
                else -> LoadingState()
            }
        }
    }
    ReloadsOnScope { load() }

    editing?.let { target ->
        key(target.id) {
            AppSheet(onDismissRequest = { editing = null }) {
                RecordsRecordSheet(record = target.record, members = members, access = access) { saved -> record = saved }
            }
        }
    }
    val current = record
    if (confirmDiscard && current != null) {
        AppSheet(onDismissRequest = {
            confirmDiscard = false
            if (discarded) dismiss()
        }) {
            ConfirmSheet(
                title = L("Discard this empty draft?"),
                message = LF("%@ for %@ has nothing written in it. It goes away; the history keeps that it was discarded.", current.titleText, current.petText),
                confirmTitle = L("Discard"),
                destructive = true,
                cancelTitle = L("Keep it"),
                icon = "file-text",
            ) {
                RecordsAPI.discard(current.id)
                Toast.show(L("Draft discarded."))
                discarded = true
                announceDataChanged()
            }
        }
    }
}

// MARK: - Pieces

/** A label and its value (web `LabelledRow`): 12 bold uppercase over 14 semibold; long values wrap. */
@Composable
fun RecordsLabelledRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = brandText(12, FontWeight.Bold).tracking(0.96), color = Theme.inkSoft)
        SelectionContainer {
            Text(value, style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
        }
    }
}

/**
 * One part of the note and who reads it (web `NoteBlock`): a box washed 7% in its tone, the title
 * 14 extrabold, a pill saying who reads it, the text 14.
 */
@Composable
fun RecordsNoteBlock(title: String, audience: String, systemImage: String, tone: Color, text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(tone, 7.0), shape)
            .border(1.dp, Theme.borderSoft, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Theme.text(14, FontWeight.ExtraBold), color = Theme.inkStrong, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Row(
                Modifier.background(Theme.wash(tone, 20.0), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(systemImage, 12.dp, tint = Theme.inkStrong)
                Text(audience, style = brandText(10.9, FontWeight.ExtraBold), color = Theme.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SelectionContainer {
            // iOS `.lineSpacing(3)`: 3 more between the lines.
            Text(text, style = Theme.text(14).copy(lineHeight = 20.sp), color = Theme.inkStrong)
        }
    }
}

/** Who signed, in the signature hand, on a dashed line (as on the finish sheet and the signed PDFs). */
@Composable
fun RecordsSignature(name: String, day: String?, modifier: Modifier = Modifier) {
    val line = Theme.inkStrong.copy(alpha = 0.25f)
    Box(modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.widthIn(max = 384.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(L("Signed by").uppercase(), style = brandText(11.2, FontWeight.ExtraBold).tracking(1.1), color = Theme.inkSoft)
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        drawContent()
                        val y = size.height - 1.dp.toPx() / 2
                        drawLine(
                            line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                    .padding(bottom = 6.dp),
            ) {
                FittedText(name, Modifier.weight(1f).alignBy(LastBaseline), style = signatureScript(32), color = Theme.inkStrong, minimumScaleFactor = 0.6f)
                if (day != null) {
                    Text(day, style = Theme.text(11.2, FontWeight.SemiBold), color = Theme.inkSoft, modifier = Modifier.padding(start = 8.dp).alignBy(LastBaseline))
                }
            }
        }
    }
}
