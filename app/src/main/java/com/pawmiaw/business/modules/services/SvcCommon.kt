package com.pawmiaw.business.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pawmiaw.business.*
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

//
//  SvcCommon.kt (iOS SvcCommon.swift)
//
//  What the three Services pages share: requests whose refusals carry more
//  than a sentence (a fee to decide, places left, a clash), times on the
//  day's clock, the groom stages, the handling chips' words, the report's words,
//  and the "start anyway" gate.
//

// MARK: - Requests that read the refusal's body

/** A refusal with its whole body (a fee to decide, places left, a clash). */
class SvcRefusal(val api: APIError, val body: JSONValue?) : Exception(api.message) {
    val code: String? get() = api.code
}

object SvcAPI {
    /** Like `API.post`, but a refusal throws `SvcRefusal` with the server's body (`APIError.body`). */
    suspend inline fun <reified T> send(method: String, path: String, body: Map<String, Any?>? = null, query: Map<String, String?> = emptyMap()): T {
        try {
            return API.decode(API.data(method, path, query, body?.let { API.jsonBody(it) }))
        } catch (api: APIError) {
            if (api.status >= 300) throw SvcRefusal(api, api.body)
            throw api
        }
    }
}

/** The refusal's code, from either kind of error. */
fun svcCode(error: Throwable): String? {
    if (error is SvcRefusal) return error.code
    return (error as? APIError)?.code
}

/** What a screen says after a failed action: this module's words for a known code, else the server's. */
fun svcMessage(error: Throwable, codes: Map<String, String> = emptyMap()): String {
    val code = svcCode(error)
    if (code != null) {
        val sentence = codes[code] ?: SvcRefusals.common[code]
        if (sentence != null) return L(sentence)
    }
    if (error is SvcRefusal) return errorText(error.api)
    return errorText(error)
}

/** The `APIError` inside, for `FormState.apply`. */
fun svcAPIError(error: Throwable): Throwable = (error as? SvcRefusal)?.api ?: error

object SvcRefusals {
    val common: Map<String, String> = mapOf(
        "consent_required" to "A consent this visit needs isn’t signed yet.",
        "deposit_required" to "The emergency deposit hasn’t been taken yet.",
    )
    val grooming: Map<String, String> = mapOf(
        "no_phone" to "This client has no phone. Add one to write on WhatsApp, or tell them in PawMiaw.",
        "not_on_app" to "This family isn’t on PawMiaw yet. Tell them by WhatsApp, or invite them.",
        "not_a_groom" to "This visit isn’t a groom.",
        "visit_not_open" to "This visit isn’t open: check the pet in first, or it was already finished.",
    )
    val homeVisits: Map<String, String> = mapOf(
        "not_a_home_visit" to "Only home visits have this",
        "on_my_way_not_offered" to "“On my way” isn't offered for this service",
        "visit_not_scheduled" to "This visit already started or isn't today",
        "visit_not_open" to "This visit is over — it can't be started now",
        "disclosure_required" to "Confirm that your team was told before turning on location",
        "invalid_day" to "Choose a day within the last week or the next 30 days",
        "no_phone" to "This client has no phone. Add one to write on WhatsApp, or tell them in PawMiaw.",
        "not_on_app" to "This family isn’t on PawMiaw yet. Tell them by WhatsApp.",
    )
}

/** The language the server writes to the family in when it has nothing better. */
val svcLocale: String get() = if (LocalizationManager.currentLanguage == AppLanguage.spanish) "es" else "en"

/** "Miércoles 4 de noviembre": the first letter up, the rest as written. */
fun svcCapitalized(text: String): String {
    val first = text.firstOrNull() ?: return text
    return first.uppercase() + text.drop(1)
}

/**
 * Opens a link the server made (WhatsApp with the message, Maps). Phone and WhatsApp links
 * from a number are `ContactLinks`.
 */
fun svcOpen(link: String?) {
    val url = link.nonEmpty ?: return
    openURL(AppContext.context, url)
}

// MARK: - Time on the day's clock

object SvcTime {
    fun zone(id: String?): ZoneId = id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Workspace.shared.timeZone

    /** "2:10 p.m." on the day's clock. */
    fun clock(iso: String?, zoneId: String?): String = DateText.time(DateText.parse(iso), zone(zoneId))

    /** "12 sep" (the year when it's another year). */
    fun day(iso: String?, withYear: Boolean = false): String {
        val date = DateText.parse(iso) ?: return "—"
        val zone = Workspace.shared.timeZone
        val thisYear = date.atZone(zone).year == Instant.now().atZone(zone).year
        return DateText.format(date, if (withYear || !thisYear) "dMMMyyyy" else "dMMM", zone)
    }

    /** "Wed, Nov 5" / "Mié 5 nov": a day heading of a list (web `visitDay`, standalone). */
    fun shortDay(iso: String?): String {
        val date = DateText.parse(iso) ?: return "—"
        return svcCapitalized(DateText.format(date, "EEEdMMM", Workspace.shared.timeZone).replace(".", "").replace(" de ", " "))
    }

    /** "jueves, 1 de octubre" for a day key. */
    fun dayLong(dayKey: String): String {
        val date = DateText.parse(dayKey) ?: return dayKey
        return DateText.format(date, "EEEEdMMMM", Workspace.shared.timeZone)
    }

    /** "35 min", "2 h 10 min": how long since a moment. */
    fun waited(since: String?): String {
        val date = DateText.parse(since) ?: return ""
        val minutes = max(0L, ChronoUnit.SECONDS.between(date, Instant.now()) / 60).toInt()
        if (minutes < 60) return LF("%d min", minutes)
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) LF("%d h", hours) else LF("%d h %d min", hours, rest)
    }

    fun minutesBetween(from: String?, to: String?): Int {
        val a = DateText.parse(from) ?: return 0
        val b = DateText.parse(to) ?: return 0
        return max(0L, ChronoUnit.SECONDS.between(a, b) / 60).toInt()
    }

    /** A day key moved by whole days. */
    fun shift(dayKey: String, by: Int): String {
        val date = DateText.parse(dayKey) ?: return dayKey
        return DateText.dayKey(date.plusSeconds(by.toLong() * 86_400))
    }

    fun daysBetween(from: String, to: String): Int {
        val a = DateText.parse(from) ?: return 0
        val b = DateText.parse(to) ?: return 0
        return (ChronoUnit.SECONDS.between(a, b) / 86_400.0).roundToInt()
    }

    /** "Due today", "3 days overdue", "Due in 5 days". */
    fun dueWords(days: Int): String = when (days) {
        0 -> L("Due today")
        -1 -> L("Due yesterday")
        1 -> L("Due tomorrow")
        else -> if (days < 0) LF("%d days overdue", -days) else LF("Due in %d days", days)
    }

    /** Whole calendar days from today to a due date (negative once late). */
    fun daysUntil(iso: String?): Int? {
        val date = DateText.parse(iso) ?: return null
        val zone = Workspace.shared.timeZone
        val a = Instant.now().atZone(zone).toLocalDate()
        val b = date.atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(a, b).toInt()
    }

    fun cadence(weeks: Int): String = if (weeks == 1) L("every week") else LF("every %d weeks", weeks)
}

// MARK: - Groom stages (lib/groom-stages.ts)

object SvcGroomStage {
    val all: List<String> = listOf("scheduled", "waiting", "bath", "styling", "ready", "done")

    fun shown(stage: String, simple: Boolean): String = if (simple && stage == "styling") "bath" else stage
    fun visible(simple: Boolean): List<String> = all.filter { !(simple && it == "styling") }

    fun label(stage: String, simple: Boolean): String = when (shown(stage, simple)) {
        "scheduled" -> L("Groom stage · Scheduled")
        "waiting" -> L("Groom stage · Waiting")
        "bath" -> if (simple) L("Groom stage · In progress") else L("Groom stage · Bath")
        "styling" -> L("Groom stage · Cut & dry")
        "ready" -> L("Ready for pickup")
        else -> L("Groom stage · Done")
    }

    /** Where a groom goes next by hand; null before it arrives and once ready. */
    fun next(stage: String, simple: Boolean): String? = when (stage) {
        "waiting" -> "bath"
        "bath" -> if (simple) "ready" else "styling"
        "styling" -> "ready"
        else -> null
    }

    /** One step back for a slip of the finger; a started groom never goes back to waiting. */
    fun previous(stage: String, simple: Boolean): String? = when (stage) {
        "styling" -> "bath"
        "ready" -> if (simple) "bath" else "styling"
        else -> null
    }

    fun moveLabel(to: String, from: String, simple: Boolean): String {
        if (from == "waiting" && simple) return L("Start")
        return when (to) {
            "waiting" -> L("Back to waiting")
            "bath" -> L("Start bath")
            "styling" -> L("To cut & dry")
            else -> L("Ready for pickup")
        }
    }
}

// MARK: - Handling (the care card's tags)

object SvcHandling {
    /** What can hurt someone or lose the pet. */
    val warn: Set<String> = setOf("bites", "muzzle", "reactive_dogs", "escape_artist")
    val all: List<String> = listOf("nervous", "bites", "muzzle", "senior", "puppy", "matted", "sensitive_skin", "reactive_dogs", "escape_artist")
}

// MARK: - The report's words (lib/service-reports.ts)

object SvcReportText {
    val groomServices: List<String> = listOf("bath", "haircut", "nails", "ears", "teeth", "glands", "deshedding", "dematting", "flea_bath")
    val coats: List<String> = listOf("good", "matted", "shedding", "dry", "oily")
    val skins: List<String> = listOf("ok", "dry", "irritated", "parasites", "lump", "wound")
    val behaviours: List<String> = listOf("great", "nervous", "difficult")
    val eating: List<String> = listOf("all", "some", "none")
    val stool: List<String> = listOf("normal", "soft", "diarrhea")
    val progress: List<String> = listOf("starting", "improving", "solid")
    val styles: List<String> = listOf("puppy", "shave", "breed", "tidy", "other")

    private val english: Map<String, String> = mapOf(
        "bath" to "Bath (service)", "haircut" to "Haircut", "nails" to "Nails", "ears" to "Ears", "teeth" to "Teeth", "glands" to "Glands",
        "deshedding" to "Deshedding", "dematting" to "Dematting", "flea_bath" to "Flea bath",
        "good" to "Coat · Good", "matted" to "Matted", "shedding" to "Shedding", "dry" to "Coat · Dry", "oily" to "Oily",
        "ok" to "All clear", "irritated" to "Irritated", "parasites" to "Fleas or ticks", "lump" to "A lump", "wound" to "A wound",
        "great" to "Calm", "nervous" to "Nervous", "difficult" to "Difficult",
        "starting" to "Starting", "improving" to "Improving", "solid" to "Solid",
        "bites" to "Bites", "muzzle" to "Muzzle", "senior" to "Senior", "puppy" to "Puppy", "sensitive_skin" to "Sensitive skin",
        "reactive_dogs" to "Reactive with dogs", "escape_artist" to "Escape artist",
        "all" to "Ate everything", "some" to "Ate some", "none" to "Didn't eat",
        "normal" to "Stool · Normal", "soft" to "Stool · Soft", "diarrhea" to "Diarrhea",
    )

    /** One option's label in the reader's language. */
    fun option(value: String): String = L(english[value] ?: value)

    fun style(value: String): String = when (value) {
        "puppy" -> L("Puppy cut")
        "shave" -> L("Shave")
        "breed" -> L("Breed standard")
        "tidy" -> L("Just a tidy")
        else -> L("Style · Other")
    }

    fun kind(category: String?): String? = when (category) {
        "groomer" -> "groom"
        "walker", "sitter", "daycare" -> "walk"
        "trainer" -> "training"
        else -> null
    }

    fun title(kind: String, category: String?): String = when (kind) {
        "groom" -> L("Groom report")
        "training" -> L("Training report")
        else -> if (category == "walker") L("Walk report") else L("Visit report")
    }

    /** What a visit's report still needs before the visit is finished (mirrors the server). */
    fun missing(category: String?, report: SvcReport?): List<String> {
        val kind = kind(category) ?: return emptyList()
        val out = mutableListOf<String>()
        when (kind) {
            "groom" -> {
                val groom = if (report?.kind == "groom") report else null
                if (groom?.services.isNullOrEmpty()) out.add("services")
                if (groom != null && groom.services.contains("haircut") && groom.styleKind == null && groom.style.nonEmpty == null) out.add("style")
                if (groom?.behaviour == null) out.add("behaviour")
            }
            "training" -> {
                val training = if (report?.kind == "training") report else null
                if (training?.skills.isNullOrEmpty()) out.add("skills")
                if (training?.progress == null) out.add("progress")
            }
            else -> {
                val walk = if (report?.kind == "walk") report else null
                if (category == "walker") {
                    if (walk?.pee == null) out.add("pee")
                    if (walk?.behaviour == null) out.add("behaviour")
                } else if (category == "sitter") {
                    if (walk?.ate == null) out.add("ate")
                    if (walk?.water == null) out.add("water")
                } else if (walk?.behaviour == null) {
                    out.add("behaviour")
                }
            }
        }
        return out
    }

    /** The fields this report must have (an asterisk), filled or not. */
    fun required(category: String?, report: SvcReport?): Set<String> {
        val base = missing(category, null).toMutableSet()
        if (report?.kind == "groom" && report.services.contains("haircut")) base.add("style")
        return base
    }

    fun fieldLabel(field: String): String = when (field) {
        "services" -> L("Services")
        "style" -> L("Style")
        "behaviour" -> L("Behaviour")
        "pee" -> L("Pee")
        "ate" -> L("Food")
        "water" -> L("Water")
        "skills" -> L("Practised")
        "progress" -> L("Progress")
        else -> field
    }

    fun fieldsText(fields: List<String>): String = fields.joinToString(", ") { fieldLabel(it) }

    /** The report as rows to read (label, value, concern); photos and the note apart. */
    fun rows(report: SvcReport): List<Triple<String, String, Boolean>> {
        val rows = mutableListOf<Triple<String, String, Boolean>>()
        when (report.kind) {
            "groom" -> {
                if (report.services.isNotEmpty()) rows.add(Triple(L("Services"), report.services.joinToString(", ") { option(it) }, false))
                val cut = listOfNotNull(report.styleKind?.let { if (it == "other") null else style(it) }, report.style.nonEmpty).joinToString(" — ")
                if (cut.isNotEmpty()) rows.add(Triple(L("Cut"), cut, false))
                report.length.nonEmpty?.let { rows.add(Triple(L("Report · Length"), it, false)) }
                report.coat?.let { rows.add(Triple(L("Coat"), option(it), it == "matted")) }
                if (report.skin.isNotEmpty()) rows.add(Triple(L("Skin"), report.skin.joinToString(", ") { option(it) }, report.skin.any { it != "ok" }))
            }
            "walk" -> {
                report.minutes?.let { if (it > 0) rows.add(Triple(L("Time"), LF("%d min", it), false)) }
                report.distanceKm?.let { if (it > 0) rows.add(Triple(L("Distance"), "${svcNumber(it)} km", false)) }
                report.pee?.let { rows.add(Triple(L("Pee"), if (it) L("Yes") else L("No"), false)) }
                report.poop?.let { rows.add(Triple(L("Poop"), option(it), it == "diarrhea")) }
                report.water?.let { rows.add(Triple(L("Water"), if (it) L("Yes") else L("No"), false)) }
                report.ate?.let { rows.add(Triple(L("Food"), option(it), it == "none")) }
                report.medsGiven?.let { rows.add(Triple(L("Medicine"), if (it) L("Given") else L("Not given"), false)) }
            }
            else -> {
                if (report.skills.isNotEmpty()) rows.add(Triple(L("Practised"), report.skills.joinToString(", "), false))
                report.progress?.let { rows.add(Triple(L("Progress"), option(it), false)) }
                report.homework.nonEmpty?.let { rows.add(Triple(L("Homework"), it, false)) }
            }
        }
        report.behaviour?.let { rows.add(Triple(L("Behaviour"), option(it), it == "difficult")) }
        return rows
    }

    /** The report's photos as (label, url). */
    fun photos(report: SvcReport): List<Pair<String, String>> {
        if (report.kind == "groom") {
            return listOfNotNull(report.beforePhotoUrl?.let { L("Before") to it }, report.afterPhotoUrl?.let { L("After") to it })
        }
        return report.photoUrl?.let { listOf(L("Photo") to it) } ?: emptyList()
    }

    fun hasContent(report: SvcReport?): Boolean {
        if (report == null) return false
        return rows(report).isNotEmpty() || photos(report).isNotEmpty() || report.note.nonEmpty != null
    }
}

/** Swift `Double.formatted()`: the number as the reader writes it, no trailing zeros. */
fun svcNumber(value: Double): String = NumberFormat.getNumberInstance(appLocale).apply { maximumFractionDigits = 6 }.format(value)

/**
 * A report to read (web `ReportView`): "Worth a look" when something is, the rows
 * (label 12 bold, value 14; a worry bold red), the photos and the note.
 */
@Composable
fun SvcReportView(report: SvcReport, compact: Boolean = false, modifier: Modifier = Modifier) {
    val rows = SvcReportText.rows(report)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (rows.any { it.third }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("triangle-alert", 14.dp, tint = Theme.destructive)
                Eyebrow(L("Worth a look"), size = 12, color = Theme.destructive)
            }
        }
        report.route?.let { WalkRouteView(route = it) }
        if (rows.isNotEmpty()) SvcReportGrid(rows, compact)
        val why = report.differenceNote.nonEmpty
        if (report.kind == "groom" && why != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.wash(Theme.mango, 16.0), RoundedCornerShape(Theme.boxRadius))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("shuffle", 14.dp, tint = Theme.inkStrong)
                    Eyebrow(L("What changed"), size = 12, color = Theme.inkStrong)
                }
                Text(why, style = Theme.text(14), color = Theme.inkStrong)
            }
        }
        val photos = SvcReportText.photos(report)
        if (photos.isNotEmpty()) {
            val columns = if (photos.size > 1) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.chunked(columns).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { (label, url) ->
                            val shape = RoundedCornerShape(Theme.boxRadius)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .border(1.dp, Theme.borderSoft, shape),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(if (compact) 1f else 4f / 3f)
                                        .background(Theme.surfaceMuted),
                                ) {
                                    AsyncImage(
                                        model = APIConfig.assetURL(url),
                                        contentDescription = label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                                if (photos.size > 1) {
                                    Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                        // A lone photo on a two-column line keeps its column's width.
                        repeat(columns - line.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        report.note.nonEmpty?.let { note ->
            Text(
                note,
                style = Theme.text(14),
                color = Theme.inkStrong,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceMuted, RoundedCornerShape(Theme.chipRadius))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The report's rows as SwiftUI's `Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 16,
 * verticalSpacing: 6)`: the labels one column as wide as the widest (at least 96 unless compact),
 * the values the rest, each row on its first baseline.
 */
@Composable
private fun SvcReportGrid(rows: List<Triple<String, String, Boolean>>, compact: Boolean) {
    val gap = 16.dp
    val lineGap = 6.dp
    val minLabel = if (compact) 0.dp else 96.dp
    Layout(
        content = {
            rows.forEach { (label, value, concern) ->
                Text(label, style = Theme.text(12, FontWeight.Bold), color = Theme.inkSoft)
                Text(value, style = Theme.text(14, if (concern) FontWeight.Bold else FontWeight.Normal), color = if (concern) Theme.destructive else Theme.inkStrong)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val lineGapPx = lineGap.roundToPx()
        val labels = measurables.filterIndexed { index, _ -> index % 2 == 0 }
        val values = measurables.filterIndexed { index, _ -> index % 2 == 1 }
        // The label column: as wide as its widest label, never more than half the row.
        val labelCap = if (constraints.hasBoundedWidth) width / 2 else Constraints.Infinity
        val labelPlaceables = labels.map { it.measure(Constraints(maxWidth = labelCap)) }
        val column = max(minLabel.roundToPx(), labelPlaceables.maxOfOrNull { it.width } ?: 0)
        val valueWidth = if (constraints.hasBoundedWidth) (width - column - gapPx).coerceAtLeast(0) else Constraints.Infinity
        val valuePlaceables = values.map { it.measure(Constraints(maxWidth = valueWidth)) }
        var y = 0
        val positions = labelPlaceables.indices.map { i ->
            val label = labelPlaceables[i]
            val value = valuePlaceables[i]
            val lb = label[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
            val vb = value[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
            val baseline = max(lb, vb)
            val labelY = y + baseline - lb
            val valueY = y + baseline - vb
            val height = max(labelY + label.height, valueY + value.height) - y
            val at = Triple(labelY, valueY, y)
            y += height + lineGapPx
            at
        }
        val total = if (rows.isEmpty()) 0 else y - lineGapPx
        layout(if (constraints.hasBoundedWidth) width else column + gapPx + (valuePlaceables.maxOfOrNull { it.width } ?: 0), total) {
            labelPlaceables.indices.forEach { i ->
                labelPlaceables[i].place(0, positions[i].first)
                valuePlaceables[i].place(column + gapPx, positions[i].second)
            }
        }
    }
}

// MARK: - Staff

/** The business's people, for reassigning and leading a session. */
suspend fun svcLoadMembers(workspace: Workspace): List<SvcMember> {
    val clinic = workspace.current?.clinicId ?: return emptyList()
    val members: List<SvcMember> = try {
        API.get("/provider-workspaces/$clinic/members")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
    return members.filter { it.status != "suspended" }
}

// MARK: - "Start anyway" (a consent or a deposit stopped a start)

/** A start the server refused for a missing consent or deposit, waiting on an owner's or admin's reason. */
class SvcStartGateRequest(
    val code: String,
    val petName: String,
    /** The start again, with the override in the body. */
    val run: suspend (Map<String, Any?>) -> Unit,
) {
    val id: UUID = UUID.randomUUID()
}

@Composable
fun SvcStartGateSheet(request: SvcStartGateRequest, onDone: () -> Unit, modifier: Modifier = Modifier) {
    ConfirmSheet(
        title = L("Start anyway?"),
        message = (if (request.code == "deposit_required") L("The emergency deposit hasn’t been taken yet.") else L("A consent this visit needs isn’t signed yet."))
            + " " + LF("Starting %@ without it is recorded with your reason.", request.petName),
        confirmTitle = L("Start anyway"),
        // Web ConsentReasonSheet: the red action, "Not now" beside it.
        destructive = true,
        askReason = true,
        reasonRequired = true,
        reasonLabel = L("Why start now"),
        cancelTitle = L("Not now"),
        modifier = modifier,
    ) { reason ->
        val flag = if (request.code == "deposit_required") "overrideDeposit" else "overrideConsent"
        request.run(mapOf(flag to true, "overrideReason" to (reason ?: "")))
        onDone()
    }
}

/** Whether a refusal is the consent or deposit gate. */
fun svcIsStartGate(error: Throwable): String? {
    val code = svcCode(error) ?: return null
    return if (code == "consent_required" || code == "deposit_required") code else null
}
