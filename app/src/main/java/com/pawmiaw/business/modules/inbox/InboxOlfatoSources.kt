package com.pawmiaw.business.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawmiaw.business.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.intOrNull

//
//  InboxOlfatoSources.kt
//
//  What of a pet's chart an answer of Olfato AI was written from ("Fuentes"):
//  the entries it cites (paw-miaw-backend `provider-olfato.schemas.ts`
//  `SourceSchema`; web components/app/olfato-business/business-sources.ts),
//  each with the screen it opens. The server sends them on every message
//  (`sources`, empty when none; an older server sends none). They are read
//  here, never trusted whole: an entry without a kind, id or title is left
//  out, the same one twice is shown once, and a link that maps to no screen of
//  the app (`InboxOlfatoLink.route`, the same as "Ver en …") leaves the chip
//  as plain text.
//
//  Under a finished reply they are a row of small chips: four at first and
//  "+N" for the rest.
//

// MARK: - A source

/** One entry of the pet's chart a reply was written from. */
data class InboxOlfatoSource(
    /** clinic_record, family_record, visit, prescription, document, hospitalization, … (a newer one is a plain record). */
    val kind: String,
    val id: String,
    val petId: String = "",
    /** Already in the reply's language ("Receta: Benazepril, Amlodipino", "panel_renal_toby.pdf"). */
    val title: String,
    /** The day it is about, "YYYY-MM-DD". */
    val date: String? = null,
    /** A document's page. */
    val page: Int? = null,
    /** The screen it opens, as an answer's "Ver en …" links name one. */
    val link: InboxOlfatoLink? = null,
) {
    /** The same thing named twice is one chip. */
    val key: String get() = "$kind|$id|${page ?: ""}"

    /** The app's screen for it; null when it opens nothing (the chip is then plain text). */
    val route: ProviderRoute? get() = link?.route

    /** A Lucide icon for what it is. */
    val icon: String
        get() = when (kind) {
            "prescription" -> "pill"
            "visit" -> "stethoscope"
            "document" -> "file"
            "hospitalization", "hospital_order", "administration", "hospital_note", "hospital_round" -> "bed-double"
            "vital" -> "activity"
            "follow_up" -> "calendar-clock"
            "weight", "family_weight" -> "scale"
            "stay" -> "house"
            "care_profile" -> "heart"
            "patient" -> "paw-print"
            else -> "file-text"
        }

    companion object {
        private val day = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /** One entry as the server sent it, or null when it cannot be shown. */
        fun from(element: JsonElement, json: Json = API.json): InboxOlfatoSource? {
            val raw = element as? JsonObject ?: return null
            fun text(name: String): String? = (raw[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val kind = text("kind").nonEmpty ?: return null
            val id = text("id").nonEmpty ?: return null
            val title = text("title").nonEmpty ?: return null
            val page = (raw["page"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
            val link = (raw["link"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(InboxOlfatoLink.serializer(), it) }.getOrNull() }
            return InboxOlfatoSource(
                kind = kind,
                id = id,
                petId = text("petId") ?: "",
                title = title,
                date = text("date")?.takeIf { day.matches(it) },
                page = page,
                link = link,
            )
        }

        /** A message's sources: those that can be shown, each once. Anything but an array is none. */
        fun list(element: JsonElement?, json: Json = API.json): List<InboxOlfatoSource> {
            val entries = element as? JsonArray ?: return emptyList()
            val seen = HashSet<String>()
            return entries.mapNotNull { from(it, json) }.filter { seen.add(it.key) }
        }
    }
}

/** `sources` read entry by entry: one that can't be read is passed over, not the whole message. */
object InboxOlfatoSourcesSerializer : KSerializer<List<InboxOlfatoSource>> {
    private val raw = ListSerializer(JsonElement.serializer())

    override val descriptor: SerialDescriptor = raw.descriptor

    override fun deserialize(decoder: Decoder): List<InboxOlfatoSource> {
        val json = decoder as? JsonDecoder ?: return emptyList()
        return InboxOlfatoSource.list(json.decodeJsonElement(), json.json)
    }

    /** Only read from the server; written back as the plain fields if ever encoded. */
    override fun serialize(encoder: Encoder, value: List<InboxOlfatoSource>) {
        encoder.encodeSerializableValue(
            raw,
            value.map { source ->
                JsonObject(
                    buildMap {
                        put("kind", JsonPrimitive(source.kind))
                        put("id", JsonPrimitive(source.id))
                        put("petId", JsonPrimitive(source.petId))
                        put("title", JsonPrimitive(source.title))
                        source.date?.let { put("date", JsonPrimitive(it)) }
                        source.page?.let { put("page", JsonPrimitive(it)) }
                    },
                )
            },
        )
    }
}

// MARK: - The sources under a reply

private const val SHOWN_AT_FIRST = 4

/**
 * "Fuentes", and a chip for each: four at first, then "+N" for the rest. The header follows the
 * reply's language (as its "Ver en …" links do); the day is in the app's.
 */
@Composable
fun InboxOlfatoSources(
    sources: List<InboxOlfatoSource>,
    language: InboxOlfatoLanguage,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded || sources.size <= SHOWN_AT_FIRST) sources else sources.take(SHOWN_AT_FIRST)
    val hidden = sources.size - shown.size

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            language.text("Sources").uppercase(),
            style = brandText(12, FontWeight.Bold).tracking(0.3),
            color = Theme.inkSoft,
            modifier = Modifier.semantics { heading() },
        )
        FlowLayout(spacing = 6.dp) {
            shown.forEach { source -> InboxOlfatoSourceChip(source, language) }
            if (hidden > 0) {
                val label = if (hidden == 1) language.text("Show 1 more source") else language.format("Show %d more sources", hidden)
                Row(
                    Modifier
                        .clip(CircleShape)
                        .plainButton(label = label) { expanded = true }
                        .sourceChipShape(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+$hidden", style = Theme.text(13, FontWeight.ExtraBold), color = Theme.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun InboxOlfatoSourceChip(source: InboxOlfatoSource, language: InboxOlfatoLanguage) {
    val nav = LocalNavigator.current
    val route = source.route
    val day = source.date?.let { DateText.parse(it) }
    val detail = listOfNotNull(day?.let { DateText.date(it) }, source.page?.let { "p. $it" }).joinToString(" · ").ifEmpty { null }
    val spoken = language.format(
        "Source: %@",
        listOfNotNull(source.title, day?.let { DateText.format(it, "dMMMMyyyy") }, source.page?.let { language.format("Page %d", it) }).joinToString(", "),
    )
    Row(
        Modifier
            // A long title is cut here; the day stays whole.
            .widthIn(max = 260.dp)
            .clip(CircleShape)
            .then(
                if (route != null) Modifier.plainButton(label = spoken) { nav.push(route) }
                else Modifier.clearAndSetSemantics { contentDescription = spoken },
            )
            .sourceChipShape(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(source.icon, 13.dp, tint = Theme.inkSoft)
        Text(
            source.title,
            style = Theme.text(13, FontWeight.Bold),
            color = Theme.inkStrong,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (detail != null) {
            Text(detail, style = Theme.text(12), color = Theme.inkSoft, maxLines = 1, softWrap = false)
        }
    }
}

/** A source's chip: a pill in the muted surface with a soft line, as the tool chips. */
@Composable
private fun Modifier.sourceChipShape(): Modifier = this
    .heightIn(min = 32.dp)
    .background(Theme.surfaceMuted, CircleShape)
    .border(1.dp, Theme.borderSoft, CircleShape)
    .padding(horizontal = 11.dp, vertical = 6.dp)
