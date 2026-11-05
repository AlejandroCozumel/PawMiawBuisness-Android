package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

//
//  SvcGroomRequest.kt (iOS SvcGroomRequest.swift)
//
//  "Lo que pidió el dueño" in the groom's report (web: care/visit-report.tsx,
//  lib/groom-request.ts, hooks/use-groom-request.ts): the report starts from
//  what the owner asked for, says live where it differs ("Distinto a lo que
//  se pidió", with why), and offers to keep the cut as the pet's usual style
//  on the care card — only ever when someone taps it.
//

// MARK: - Shapes (GET /provider-appointments/{id}/groom-request)

/** What to do, with "same as last time" already filled in by the server. */
@Serializable
data class SvcGroomWanted(
    val services: List<String>? = null,
    val style: String? = null,
    val styleText: String? = null,
    val length: String? = null,
)

@Serializable
data class SvcGroomStyleOffer(
    val requested: Boolean? = null,
    val done: Boolean? = null,
)

@Serializable
data class SvcGroomRequestView(
    val appointmentId: String,
    val isGroom: Boolean? = null,
    /** Only whether there is one matters here (the visit page shows it whole). */
    val request: JSONValue? = null,
    val wanted: SvcGroomWanted? = null,
    /** The report as the request says it: what a report with nothing yet starts from. */
    val prefill: SvcReport? = null,
    val careStyle: String? = null,
    val careTools: String? = null,
    val styleOffer: SvcGroomStyleOffer? = null,
) {
    val hasRequest: Boolean get() = request?.let { !it.isNull } ?: false
}

@Serializable
data class SvcUsualStyleResult(
    val groomStyle: String,
    val groomTools: String? = null,
)

/** Where the report differs from what was asked (`style`/`length`: requested, done). */
data class SvcGroomDifferences(
    val notDone: List<String>,
    val added: List<String>,
    val style: Pair<String, String>?,
    val length: Pair<String, String>?,
    val any: Boolean,
)

// MARK: - Rules (mirrors lib/groom-request.ts)

object SvcGroomAsk {
    /** The cut in words: "Puppy cut — round ears", or the free text alone. */
    fun cutText(style: String?, text: String?): String? {
        val label = style?.let { if (it == "other") null else SvcReportText.style(it) }
        val detail = text?.trim().nonEmpty
        // Words that already open with the cut ("Corte cachorro, cara redonda") say it once.
        if (label != null && detail != null && detail.lowercase().startsWith(label.lowercase())) return detail
        if (label != null && detail != null) return "$label — $detail"
        return label ?: detail
    }

    /** "Bath, Haircut · Puppy cut — round ears · #7". */
    fun summary(wanted: SvcGroomWanted): String =
        listOfNotNull(
            (wanted.services ?: emptyList()).joinToString(", ") { SvcReportText.option(it) }.nonEmpty,
            cutText(wanted.style, wanted.styleText),
            wanted.length.nonEmpty,
        ).joinToString(" · ")

    /** The same comparison the server makes, live while the report is written. */
    fun compare(wanted: SvcGroomWanted?, report: SvcReport): SvcGroomDifferences? {
        if (wanted == null || report.kind != "groom") return null
        // A report that says nothing about services, cut or length can't be compared;
        // services left blank are unknown, not "not done".
        val comparable = report.services.isNotEmpty() || report.styleKind != null || report.length?.trim().nonEmpty != null
        if (!comparable && report.differenceNote?.trim().nonEmpty == null) return null
        val asked = wanted.services ?: emptyList()
        val notDone = if (report.services.isEmpty()) emptyList() else asked.filter { it !in report.services }
        val added = report.services.filter { it !in asked }
        var style: Pair<String, String>? = null
        val requestedStyle = wanted.style
        val doneStyle = report.styleKind
        if (requestedStyle != null && doneStyle != null && requestedStyle != doneStyle) style = requestedStyle to doneStyle
        fun normal(value: String): String =
            value.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        var length: Pair<String, String>? = null
        val requestedLength = wanted.length.nonEmpty
        val doneLength = report.length?.trim().nonEmpty
        if (requestedLength != null && doneLength != null && normal(requestedLength) != normal(doneLength)) {
            length = requestedLength to doneLength
        }
        return SvcGroomDifferences(
            notDone = notDone, added = added, style = style, length = length,
            any = notDone.isNotEmpty() || added.isNotEmpty() || style != null || length != null,
        )
    }

    /** The differences as sentences ("Asked for a shave, did a puppy cut"). */
    fun lines(differences: SvcGroomDifferences): List<String> {
        val out = mutableListOf<String>()
        differences.style?.let { (requested, done) ->
            out.add(LF("Asked for %@, did %@", SvcReportText.style(requested).lowercase(), SvcReportText.style(done).lowercase()))
        }
        differences.length?.let { (requested, done) -> out.add(LF("Length: asked %@, done %@", requested, done)) }
        if (differences.notDone.isNotEmpty()) out.add(LF("Not done: %@", differences.notDone.joinToString(", ") { SvcReportText.option(it) }))
        if (differences.added.isNotEmpty()) out.add(LF("Also done: %@", differences.added.joinToString(", ") { SvcReportText.option(it) }))
        return out
    }

    /** The cut's words in both languages, so a card written in either counts. */
    private val styleWords: Map<String, List<String>> = mapOf(
        "puppy" to listOf("Puppy cut", "Corte cachorro"),
        "shave" to listOf("Shave", "Rapado"),
        "breed" to listOf("Breed standard", "Estándar de la raza"),
        "tidy" to listOf("Just a tidy", "Solo emparejar"),
    )

    /** A cut the care card doesn't already say, worth offering to keep (mirrors the server's `styleIsNewForCard`). */
    fun styleIsNewForCard(card: String?, style: String?, text: String?): Boolean {
        val detail = text?.trim()?.lowercase() ?: ""
        if (style == null && detail.isEmpty()) return false
        val saved = card?.trim()?.lowercase() ?: ""
        if (saved.isEmpty()) return true
        if (detail.isNotEmpty() && !saved.contains(detail)) return true
        if (style != null && style != "other") {
            val words = (styleWords[style] ?: emptyList()).map { it.lowercase() }
            if (words.none { saved.contains(it) }) return true
        }
        return false
    }
}

// MARK: - The pieces the report form shows

/** "Pidieron: …", the request in one line above the groom's fields. */
@Composable
fun SvcGroomAskedLine(wanted: SvcGroomWanted, modifier: Modifier = Modifier) {
    val summary = SvcGroomAsk.summary(wanted)
    if (summary.isNotEmpty()) {
        Text(LF("Asked: %@", summary), style = Theme.text(12), color = Theme.inkSoft, modifier = modifier)
    }
}

/** "Distinto a lo que se pidió" (web: the mango box in the groom's report): the lines, and why (the family reads both). */
@Composable
fun SvcGroomDifferenceBox(
    differences: SvcGroomDifferences,
    note: MutableState<String>,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme.boxRadius)
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.wash(Theme.mango, 12.0), shape)
            .border(2.dp, Theme.mix(Theme.mango, 60.0, Theme.borderSoft), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("shuffle", 14.dp, tint = Theme.inkStrong)
            Eyebrow(L("Different from what was asked"), size = 12, color = Theme.inkStrong)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (line in SvcGroomAsk.lines(differences)) {
                Text(line, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(L("Why is it different?"), style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
            Disabled(!editable) {
                FieldChrome(multiline = true) {
                    PlainTextField(
                        note,
                        prompt = L("e.g. it was matted, so a puppy cut instead of a shave"),
                        lines = 2..6,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * "Guardar como su estilo habitual": the cut done (or, before the report says one, the cut
 * asked for) kept on the care card. Offered only when it's new for the card.
 * `unsaved`: the report on screen isn't the one saved: "done" reads the saved report, so it is saved first.
 */
@Composable
fun SvcUsualStyleOffer(
    view: SvcGroomRequestView,
    report: SvcReport,
    unsaved: Boolean,
    saveFirst: suspend () -> Boolean,
    onSaved: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    val doneCut = report.styleKind != null || report.style?.trim().nonEmpty != null
    val offered = if (doneCut) {
        view.styleOffer?.done == true || SvcGroomAsk.styleIsNewForCard(view.careStyle, style = report.styleKind, text = report.style)
    } else {
        view.styleOffer?.requested == true
    }

    fun keep() {
        val from = if (doneCut) "done" else "requested"
        working = true
        scope.launch {
            try {
                if (from == "done" && unsaved) {
                    if (!saveFirst()) return@launch
                }
                val result = API.post<SvcUsualStyleResult>(
                    "/provider-appointments/${view.appointmentId}/groom-request/usual-style",
                    mapOf("from" to from, "language" to svcLocale),
                )
                Toast.show(LF("Their usual style is now: %@", result.groomStyle))
                announceDataChanged()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.error(e)
            } finally {
                working = false
            }
        }
    }

    if (offered) {
        FlowLayout(
            spacing = 8.dp,
            modifier = modifier
                .fillMaxWidth()
                .dashedBorder(1.dp, Theme.borderSoft, Theme.boxRadius, 5.dp, 4.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                L("Keep the cut asked for, or the one done, on the care card for next time."),
                style = Theme.text(12),
                color = Theme.inkSoft,
            )
            PMButton(
                L("Save as their usual style"),
                systemImage = "bookmark-plus",
                variant = ButtonVariant.outline,
                size = ButtonSize.header,
                isWorking = working,
            ) { keep() }
        }
    }
}
