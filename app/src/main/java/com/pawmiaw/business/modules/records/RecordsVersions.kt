package com.pawmiaw.business.records

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*

//
//  RecordsVersions.kt (iOS RecordsVersions.swift)
//
//  A signed record's versions (paw-miaw-backend cce7bbd, "Signed corrections";
//  record-versions.ts): version 1 as first signed and each correction its
//  author signed, oldest first, with who signed it, when and whether the
//  signature stands, what each correction changed ("Dosis: 1 tableta → ½
//  tableta"), each version's own verify page, and a kept version opened
//  read-only. Everyone reads the latest. A record signed before 9 Nov 2026 and
//  then corrected without a signature says so plainly: the version its
//  signature covers was not kept.
//

/** The chain's words (iOS `RecordsText.versionLine` and its neighbours). */
object RecordsVersionWords {
    /** "6 oct 2026", always with the year (a signature's day). */
    fun fullDay(iso: String?): String = DateText.date(iso)

    /** Where a version's signature stands: "valid", "revoked"… */
    fun status(raw: String?): String = when (raw) {
        "valid" -> L("valid@signature")
        "revoked" -> L("revoked@signature")
        "voided" -> L("voided@signature")
        "pending" -> L("waiting for the signature@signature")
        else -> L("not signed@signature")
    }

    /**
     * "Versión 1, firmada por Dra. Loribeth el 6 oct 2026: válida" or "Corregida y firmada por
     * Dra. Loribeth el 9 nov 2026 (versión 2): válida".
     */
    fun line(entry: RecordsVersion): String {
        val who = entry.signerName.nonEmpty ?: L("someone on the team")
        val day = fullDay(entry.signedAt)
        val status = status(entry.status)
        return if (entry.version <= 1) LF("Version %1\$d, signed by %2\$@ on %3\$@: %4\$@", entry.version, who, day, status)
        else LF("Corrected and signed by %1\$@ on %2\$@ (version %3\$d): %4\$@", who, day, entry.version, status)
    }

    /** The honest note for a signature whose version was not kept (records signed before 9 Nov 2026). */
    val notKept: String
        get() = L("This signature covers a version of the record that was not kept: the record was corrected after signing without a new signature, before corrections were signed.")
}

/**
 * "Versiones firmadas" on a signed record's page: the chain, oldest first, and "Mostrando la
 * versión N". `versions` null while it loads; `failed` when it could not be read.
 */
@Composable
fun RecordsVersionsSection(
    record: RecordsRecord,
    versions: List<RecordsVersion>?,
    failed: Boolean,
    onOpen: (RecordsVersion) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = L("Signed versions"),
        subtitle = L("Each signed version stays, with its own signature. Everyone reads the latest."),
        icon = "badge-check",
        modifier = modifier,
    ) {
        when {
            versions != null && versions.isEmpty() ->
                Text(L("No signature yet."), style = Theme.text(14), color = Theme.inkSoft, modifier = Modifier.padding(16.dp))
            versions != null -> {
                val shown = versions.firstOrNull { it.isThis } ?: versions.firstOrNull { it.current } ?: versions.last()
                Column(Modifier.fillMaxWidth()) {
                    versions.forEachIndexed { index, entry ->
                        key(entry.version) {
                            if (index > 0) RowDivider()
                            VersionRow(entry, onOpen = { onOpen(entry) })
                        }
                    }
                    RowDivider()
                    Text(
                        LF("Showing version %d", shown.version), style = Theme.text(13, FontWeight.Bold), color = Theme.inkSoft,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            failed -> ErrorState(message = L("The signed versions could not be loaded.")) { onRetry() }
            else -> LoadingState()
        }
    }
}

/** One version: its line, the honest note when its copy was not kept, what its correction changed, and its links. */
@Composable
private fun VersionRow(entry: RecordsVersion, onOpen: () -> Unit) {
    val openURL = rememberOpenURL()
    val valid = entry.status == "valid"
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Glyph(
            if (valid) "badge-check" else if (entry.status == "pending") "clock-3" else "circle-alert", 18.dp,
            tint = if (valid) ProviderState.done.color else Theme.inkSoft,
            modifier = Modifier.padding(top = 1.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(RecordsVersionWords.line(entry), style = Theme.text(14, FontWeight.SemiBold), color = Theme.inkStrong)
            if (entry.correctedUnsigned) {
                Text(RecordsVersionWords.notKept, style = Theme.text(13), color = Theme.inkSoft)
            }
            if (entry.changes.isNotEmpty()) {
                SelectionContainer {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Theme.surfaceMuted, RoundedCornerShape(Theme.boxRadius))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        entry.changes.forEach { change -> Text(change.line, style = Theme.text(13), color = Theme.inkStrong) }
                    }
                }
            }
            val canOpen = entry.kept && !entry.current
            val url = entry.verifyUrl.nonEmpty
            if (canOpen || url != null) {
                FlowLayout(spacing = 8.dp) {
                    if (canOpen) {
                        PMButton(LF("See version %d", entry.version), systemImage = "file-text", variant = ButtonVariant.outline, size = ButtonSize.slim, action = onOpen)
                    }
                    if (url != null) {
                        PMButton(L("Verify the signature"), systemImage = "shield-check", variant = ButtonVariant.outline, size = ButtonSize.slim) { openURL(url) }
                    }
                }
            }
        }
    }
}

/**
 * One kept version as it was signed, read-only (GET /provider-records/{id}/versions/{n}): what it
 * said, who signed it and its own verify page. Never the staff note (it was never signed).
 */
@Composable
fun RecordsVersionSheet(record: RecordsRecord, number: Int, latest: Int, modifier: Modifier = Modifier) {
    val openURL = rememberOpenURL()
    var document by remember { mutableStateOf<RecordsVersionDocument?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(number, attempt) {
        try {
            document = RecordsAPI.version(record.id, number)
            failed = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = errorText(e, fallback = L("This version could not be loaded."))
        }
    }

    val doc = document
    ProviderModal(
        title = LF("Version %d", number),
        subtitle = doc?.let { LF("Signed by %1\$@ on %2\$@", it.signedByName.nonEmpty ?: L("someone on the team"), RecordsVersionWords.fullDay(it.signedAt)) },
        systemImage = "file-clock",
        accent = ModalAccent.aqua,
        cancelTitle = L("Close"),
        modifier = modifier,
    ) {
        val problem = failed
        when {
            doc != null -> {
                val asSigned = doc.asRecord(record)
                NoticeCard(
                    tone = NoticeCard.Tone.info,
                    title = if (doc.current) L("This is the version everyone reads.") else LF("An earlier version, as it was signed. Everyone reads version %d.", latest),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Theme.borderSoft, RoundedCornerShape(Theme.boxRadius))
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                ) {
                    RecordsLabelledRow(label = L("Title"), value = doc.title.nonEmpty ?: asSigned.kind.label)
                    RowDivider()
                    RecordsLabelledRow(label = L("Type"), value = asSigned.kind.label)
                    RowDivider()
                    RecordsLabelledRow(label = L("Date"), value = RecordsText.longDate(doc.eventAt))
                    if (doc.followUpAt.nonEmpty != null) {
                        RowDivider()
                        RecordsLabelledRow(label = if (asSigned.kind.hasNextDose) L("Next dose") else L("Follow-up date"), value = RecordsText.longDate(doc.followUpAt))
                    }
                }
                doc.summary.nonEmpty?.let {
                    RecordsNoteBlock(title = L("For the family"), audience = L("As signed"), systemImage = "eye", tone = Theme.aqua, text = it)
                }
                doc.details.nonEmpty?.let {
                    RecordsNoteBlock(title = L("Clinical notes"), audience = L("As signed"), systemImage = "stethoscope", tone = Theme.blue, text = it)
                }
                val rows = RecordsPayload.rows(asSigned)
                if (rows.isNotEmpty()) {
                    ModalSection(title = L("Record details"), subtitle = asSigned.kind.label, systemImage = "file-text") {
                        Column(Modifier.fillMaxWidth()) {
                            rows.forEachIndexed { index, row ->
                                if (index > 0) RowDivider()
                                RecordsLabelledRow(label = row.label, value = row.value)
                            }
                        }
                    }
                }
                doc.signedByName.nonEmpty?.let { name ->
                    RecordsSignature(name = name, day = RecordsVersionWords.fullDay(doc.signedAt) + " · " + LF("version %d", doc.version))
                }
                doc.verifyUrl.nonEmpty?.let { url ->
                    PMButton(L("Verify the signature"), systemImage = "shield-check", variant = ButtonVariant.outline, size = ButtonSize.compact) { openURL(url) }
                }
            }
            problem != null -> ErrorState(message = problem) { attempt += 1 }
            else -> LoadingState()
        }
    }
}
